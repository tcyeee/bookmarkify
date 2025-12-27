package top.tcyeee.bookmarkify.server.impl

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl
import org.jsoup.nodes.Document
import org.springframework.stereotype.Service
import top.tcyeee.bookmarkify.config.entity.ProjectConfig
import top.tcyeee.bookmarkify.entity.BookmarkShow
import top.tcyeee.bookmarkify.entity.HomeItemShow
import top.tcyeee.bookmarkify.entity.dto.BookmarkUrl
import top.tcyeee.bookmarkify.entity.entity.Bookmark
import top.tcyeee.bookmarkify.entity.entity.BookmarkUserLink
import top.tcyeee.bookmarkify.entity.entity.HomeItem
import top.tcyeee.bookmarkify.mapper.BookmarkMapper
import top.tcyeee.bookmarkify.mapper.BookmarkUserLinkMapper
import top.tcyeee.bookmarkify.mapper.HomeItemMapper
import top.tcyeee.bookmarkify.server.IBookmarkService
import top.tcyeee.bookmarkify.utils.BookmarkUtils
import top.tcyeee.bookmarkify.utils.SocketUtils
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
        BookmarkUtils.parse(bookmark.rawUrl)

        checkOne(bookmark)
        val bookmarkShow: BookmarkShow = bookmarkUserLinkMapper.findOne(id)
        bookmarkShow.clean(projectConfig.imgPrefix)
        SocketUtils.updateBookmark(bookmarkShow.uid!!, bookmarkShow)
    }

    override fun checkOne(bookmark: Bookmark) {
        log.trace("[CHECK] 开始解析域名:{}...${bookmark.rawUrl}")

        /* 检查网站活性 */
        val document: Document? = BookmarkUtils.getDocument(bookmark.rawUrl)
        document ?: return offline(bookmark)

        /* 保存网站标题,描述 */
        bookmark.setTitle(document)

        /* 保存网站LOGO */
        val iconStorePath = BookmarkUtils.getLogoUrl(document, projectConfig.imgPath, bookmark)
        bookmark.setLogo(iconStorePath)

        updateById(bookmark)
    }

    override fun offline(bookmark: Bookmark) {
        bookmark.title = bookmark.urlHost
        bookmark.checkActivity(false)
        updateById(bookmark)
    }

    override fun setDefaultBookmark(uid: String) {
        // 获取配置信息
        projectConfig.defaultBookmarkify.forEach { addOne(it, uid) }
    }

    override fun check(url: String) {
        BookmarkUtils.parse(url)
    }

    override fun checkAll() = ktQuery().lt(Bookmark::updateTime, yesterday()).list().forEach(this::checkOne)

    override fun addOne(url: String, uid: String): HomeItemShow {
        val bookmarkUrl = BookmarkUrl(url)
        val bookmark =
            ktQuery().eq(Bookmark::urlHost, bookmarkUrl.urlHost).one() ?: Bookmark(bookmarkUrl).also { save(it) }
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
