package top.tcyeee.bookmarkify.server.impl

import cn.hutool.core.util.ArrayUtil
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl
import java.time.LocalDateTime
import java.util.concurrent.CompletableFuture
import org.springframework.stereotype.Service
import top.tcyeee.bookmarkify.config.entity.ProjectConfig
import top.tcyeee.bookmarkify.entity.BookmarkShow
import top.tcyeee.bookmarkify.entity.HomeItemShow
import top.tcyeee.bookmarkify.entity.dto.BookmarkWrapper
import top.tcyeee.bookmarkify.entity.entity.Bookmark
import top.tcyeee.bookmarkify.entity.entity.BookmarkUserLink
import top.tcyeee.bookmarkify.entity.entity.HomeItem
import top.tcyeee.bookmarkify.entity.entity.WebsiteLogoEntity
import top.tcyeee.bookmarkify.mapper.BookmarkMapper
import top.tcyeee.bookmarkify.mapper.BookmarkUserLinkMapper
import top.tcyeee.bookmarkify.mapper.HomeItemMapper
import top.tcyeee.bookmarkify.server.IBookmarkService
import top.tcyeee.bookmarkify.server.IWebsiteLogoService
import top.tcyeee.bookmarkify.utils.*

/**
 * @author tcyeee
 * @date 3/10/24 15:46
 */
@Service
class BookmarkServiceImpl(
    private val bookmarkUserLinkMapper: BookmarkUserLinkMapper,
    private val homeItemMapper: HomeItemMapper,
    private val projectConfig: ProjectConfig,
    private val websiteLogoService: IWebsiteLogoService,
) : IBookmarkService, ServiceImpl<BookmarkMapper, Bookmark>() {

    override fun checkOne(rawUrl: String) =
        WebsiteParser.urlWrapper(rawUrl)
            .let { this.getByUrl(it.urlHost) ?: Bookmark(it) }
            .let { this.checkOne(it) }

    override fun checkOne(bookmark: Bookmark, bookmarkUserLinkId: String) {
        WebsiteParser.parse(bookmark.rawUrl)

        checkOne(bookmark)
        val bookmarkShow: BookmarkShow = bookmarkUserLinkMapper.findOne(bookmarkUserLinkId)
        bookmarkShow.clean(projectConfig.imgPrefix)
        SocketUtils.updateBookmark(bookmarkShow.uid!!, bookmarkShow)
    }

    override fun checkOne(bookmark: Bookmark) {
        log.trace("[CHECK] 开始解析域名:{}...${bookmark.rawUrl}")
        WebsiteParser.parse(bookmark)
            // 填充bookmark基础信息
            ?.also { bookmark.initBaseInfo(it) }
            // 设置bokmark-ico base64信息
            ?.also { bookmark.iconBase64 = FileUtils.icoBase64(it.distinctIcons) }
            // 更新书签
            ?.also { this.saveOrUpdate(bookmark) }
            // 保存网站LOGO/OG图片到OSS和数据库
            ?.also { this.saveOrUpdateLogo(it, bookmark.id) }
    }

    /**
     * 保存网站LOGO/OG图片到数据库
     *
     * @param wrapper 爬取到的网页
     * @param bookmarkId 书签ID
     */
    private fun saveOrUpdateLogo(wrapper: BookmarkWrapper?, bookmarkId: String) {
        if (wrapper == null || ArrayUtil.isEmpty(wrapper)) return

        val logolistNew = OssUtils.restoreWebsiteLogo(wrapper.distinctIcons, bookmarkId)
        val logoListOld = websiteLogoService.findByBookmarkId(bookmarkId)

        val toUpdate = mutableListOf<WebsiteLogoEntity>()
        val toInsert = mutableListOf<WebsiteLogoEntity>()

        logolistNew.forEach { it ->
            logoListOld.find { old -> old.isSame(it) }
                ?.let { toUpdate.add(it.copy(updateTime = LocalDateTime.now())) }
                ?: toInsert.add(it)
        }

        if (toUpdate.isNotEmpty()) websiteLogoService.updateBatchById(toUpdate)
        if (toInsert.isNotEmpty()) websiteLogoService.saveBatch(toInsert)
    }

    private fun getByUrl(urlHost: String): Bookmark? = ktQuery().eq(Bookmark::urlHost, urlHost).one()

    override fun offline(bookmark: Bookmark) {
        bookmark.title = bookmark.urlHost
        updateById(bookmark)
    }

    // 获取配置信息
    override fun setDefaultBookmark(uid: String) =
        projectConfig.defaultBookmarkify.forEach { addOne(it, uid) }

    override fun checkAll() =
        ktQuery().lt(Bookmark::updateTime, yesterday()).list().forEach(this::checkOne)

    override fun addOne(url: String, uid: String): HomeItemShow {
        val bookmarkUrl = WebsiteParser.urlWrapper(url)
        val bookmark =
            ktQuery().eq(Bookmark::urlHost, bookmarkUrl.urlHost).one()
                ?: Bookmark(bookmarkUrl).also { save(it) }
        // 添加用户关联和桌面布局
        val userLink = BookmarkUserLink(bookmarkUrl, uid, bookmark)
        bookmarkUserLinkMapper.insert(userLink)
        val homeItem = HomeItem(uid, userLink.id)
        homeItemMapper.insert(homeItem)
        // 异步检查
        CompletableFuture.runAsync { this.checkOne(bookmark, userLink.id) }
        return HomeItemShow(homeItem.id, uid, bookmark.id)
    }
}
