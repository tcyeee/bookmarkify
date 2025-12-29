package top.tcyeee.bookmarkify.server.impl

import cn.hutool.core.util.ArrayUtil
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl
import org.aspectj.util.FileUtil
import org.springframework.stereotype.Service
import top.tcyeee.bookmarkify.config.entity.ProjectConfig
import top.tcyeee.bookmarkify.entity.BookmarkShow
import top.tcyeee.bookmarkify.entity.HomeItemShow
import top.tcyeee.bookmarkify.entity.dto.BookmarkUrlWrapper
import top.tcyeee.bookmarkify.entity.dto.BookmarkWrapper
import top.tcyeee.bookmarkify.entity.entity.Bookmark
import top.tcyeee.bookmarkify.entity.entity.BookmarkUserLink
import top.tcyeee.bookmarkify.entity.entity.HomeItem
import top.tcyeee.bookmarkify.entity.enums.FileType
import top.tcyeee.bookmarkify.mapper.BookmarkMapper
import top.tcyeee.bookmarkify.mapper.BookmarkUserLinkMapper
import top.tcyeee.bookmarkify.mapper.HomeItemMapper
import top.tcyeee.bookmarkify.server.IBookmarkService
import top.tcyeee.bookmarkify.utils.FileUtils
import top.tcyeee.bookmarkify.utils.OssUtils
import top.tcyeee.bookmarkify.utils.SocketUtils
import top.tcyeee.bookmarkify.utils.WebsiteParser
import top.tcyeee.bookmarkify.utils.yesterday
import java.util.concurrent.CompletableFuture

/**
 * @author tcyeee
 * @date 3/10/24 15:46
 */
@Service
class BookmarkServiceImpl(
    private val bookmarkUserLinkMapper: BookmarkUserLinkMapper,
    private val homeItemMapper: HomeItemMapper,
    private val projectConfig: ProjectConfig
) : IBookmarkService, ServiceImpl<BookmarkMapper, Bookmark>() {

    override fun checkOne(bookmark: Bookmark, id: String) {
        WebsiteParser.parse(bookmark.rawUrl)

        checkOne(bookmark)
        val bookmarkShow: BookmarkShow = bookmarkUserLinkMapper.findOne(id)
        bookmarkShow.clean(projectConfig.imgPrefix)
        SocketUtils.updateBookmark(bookmarkShow.uid!!, bookmarkShow)
    }

    override fun checkOne(bookmark: Bookmark) {
        log.trace("[CHECK] 开始解析域名:{}...${bookmark.rawUrl}")
        val wrapper = WebsiteParser.parse(bookmark)
            // 填充bookmark基础信息
            ?.also { bookmark.initBaseInfo(it) }
            // 设置bokmark-ico base64信息
            ?.also { bookmark.iconBase64 = FileUtils.icoBase64(it.distinctIcons) }
            // 更新书签
            ?.also { saveOrUpdate(bookmark) }

        // 保存网站LOGO/OG图片
        if (wrapper != null && ArrayUtil.isNotEmpty(wrapper)) {
            // restore 文件
            val list = OssUtils.restoreWebsiteLogo(wrapper.distinctIcons, bookmark.id)

            // 存储到数据库
            list.forEach {
                log.trace(it.toString())
            }
        }
    }

    override fun check(rawUrl: String) {
        WebsiteParser.urlWrapper(rawUrl)
            .let { Bookmark(it) }
            .let { checkOne(it) }
    }


    override fun offline(bookmark: Bookmark) {
        bookmark.title = bookmark.urlHost
        updateById(bookmark)
    }

    // 获取配置信息
    override fun setDefaultBookmark(uid: String) =
        projectConfig.defaultBookmarkify.forEach { addOne(it, uid) }


    override fun checkAll() = ktQuery().lt(Bookmark::updateTime, yesterday()).list().forEach(this::checkOne)

    override fun addOne(url: String, uid: String): HomeItemShow {
        val bookmarkUrl = WebsiteParser.urlWrapper(url)
        val bookmark = ktQuery().eq(Bookmark::urlHost, bookmarkUrl.urlHost).one()
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
