package top.tcyeee.bookmarkify.server.impl

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl
import org.springframework.stereotype.Service
import top.tcyeee.bookmarkify.config.entity.ProjectConfig
import top.tcyeee.bookmarkify.entity.AllOfMyBookmarkParams
import top.tcyeee.bookmarkify.entity.BookmarkShow
import top.tcyeee.bookmarkify.entity.HomeItemShow
import top.tcyeee.bookmarkify.entity.dto.BookmarkWrapper
import top.tcyeee.bookmarkify.entity.entity.Bookmark
import top.tcyeee.bookmarkify.entity.entity.BookmarkUserLink
import top.tcyeee.bookmarkify.entity.entity.HomeItem
import top.tcyeee.bookmarkify.mapper.BookmarkMapper
import top.tcyeee.bookmarkify.mapper.BookmarkUserLinkMapper
import top.tcyeee.bookmarkify.mapper.HomeItemMapper
import top.tcyeee.bookmarkify.server.IBookmarkService
import top.tcyeee.bookmarkify.server.IWebsiteLogoService
import top.tcyeee.bookmarkify.utils.OssUtils
import top.tcyeee.bookmarkify.utils.SocketUtils
import top.tcyeee.bookmarkify.utils.WebsiteParser
import top.tcyeee.bookmarkify.utils.yesterday
import java.time.LocalDateTime
import java.util.concurrent.CompletableFuture

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

    // 获取配置信息
    override fun setDefaultBookmark(uid: String) = projectConfig.defaultBookmarkify.forEach { this.addOne(it, uid) }

    override fun search(name: String): List<Bookmark> =
        ktQuery()
            .eq(Bookmark::isActivity, true)
            .like(Bookmark::appName, name)
            .or().like(Bookmark::title, name)
            .or().like(Bookmark::description, name)
            .or().like(Bookmark::urlHost, name)
            .last("limit 5")
            .list()

    override fun linkOne(bookmarkId: String, uid: String): HomeItemShow {
        val userLink = this.findById(bookmarkId)
            .let { BookmarkUserLink(it, uid) }
            .also { bookmarkUserLinkMapper.insert(it) }

        val homeItem = HomeItem(uid, userLink.id).also { homeItemMapper.insert(it) }

        return bookmarkUserLinkMapper.findShowById(userLink.id)
            .let { HomeItemShow(uid, homeItem.id, it.also { it.initLogo() }) }
    }

    override fun allOfMyBookmark(uid: String, params: AllOfMyBookmarkParams): List<BookmarkShow> =
        bookmarkUserLinkMapper.allBookmarkByUid(uid).onEach { it.initLogo() }

    override fun checkAll() = ktQuery().lt(Bookmark::updateTime, yesterday()).list().forEach(this::parseBookmark)

    override fun addOne(url: String, uid: String): HomeItemShow {
        val bookmarkUrl = WebsiteParser.urlWrapper(url)
        val bookmark = this.getByHost(bookmarkUrl.urlHost) ?: Bookmark(bookmarkUrl).also { save(it) }

        // 添加用户关联和桌面布局
        val userLink = BookmarkUserLink(bookmarkUrl, uid, bookmark).also { bookmarkUserLinkMapper.insert(it) }
        val homeItem = HomeItem(uid, userLink.id).also { homeItemMapper.insert(it) }

        // 异步检查 书签如果没有高清icon, 并且updateTime已经超过一天再检查
        if (bookmark.checkFlag) CompletableFuture.runAsync {
            bookmarkUserLinkMapper.findShowById(userLink.id)
                .let { HomeItemShow(uid, homeItem.id, it.also { it.initLogo() }) }
                .also { SocketUtils.homeItemUpdate(uid, it) }
        }.also { return HomeItemShow(homeItem.id, uid, bookmark.id) }

        // 如果无需更新书签,则直接将旧书签打包为桌面元素返回
        return bookmarkUserLinkMapper.findShowById(userLink.id)
            .let { HomeItemShow(uid, homeItem.id, it.initLogo()) }
    }

    private fun parseBookmark(bookmark: Bookmark) {
        log.warn("[CHECK] 开始解析域名:{}...${bookmark.rawUrl}")
        runCatching { WebsiteParser.parse(bookmark.rawUrl) }
            .getOrElse {
                if (it.message.toString().contains("403")) bookmark.antiCrawlerDetected = true
                bookmark.parseErrMsg = it.message.toString()
                bookmark.isActivity = false
                saveOrUpdate(bookmark)
                it.printStackTrace()
                return
            }
            // 填充bookmark基础信息 以及 bookmark-ico-base64信息
            .also { bookmark.initBaseInfo(it) }
            // 更新书签
            .also { this.saveOrUpdate(bookmark) }
            // 保存网站LOGO/OG图片到OSS和数据库
            .also { this.saveOrUpdateLogo(it, bookmark) }
    }

    /**
     * 保存网站LOGO/OG图片到数据库
     * 1.找到最大尺寸的LOGO重命名为logo.png
     *
     * @param wrapper 爬取到的网页
     * @param bookmark 书签信息
     */
    private fun saveOrUpdateLogo(wrapper: BookmarkWrapper?, bookmark: Bookmark) {
        if (wrapper?.distinctIcons.isNullOrEmpty()) return

        OssUtils.restoreWebsiteLogoAndOg(wrapper.distinctIcons, bookmark.id)
            // 更新/保存LOGO到数据库
            ?.also { websiteLogoService.updateMaximalLogoByBookmarkId(it) }
            // 更新/保存最大图标信息
            ?.also { bookmark.setMaximalLogoSize(it.width) }
    }

    private fun getByHost(urlHost: String): Bookmark? = ktQuery().eq(Bookmark::urlHost, urlHost).one()

    fun Bookmark.setMaximalLogoSize(maximal: Int) {
        this.maximalLogoSize = maximal
        this.isActivity = true
        this.updateTime = LocalDateTime.now()
        saveOrUpdate(this)
    }

    fun findById(bookmarkId: String): Bookmark = requireNotNull(ktQuery().eq(Bookmark::id, bookmarkId).one())
}