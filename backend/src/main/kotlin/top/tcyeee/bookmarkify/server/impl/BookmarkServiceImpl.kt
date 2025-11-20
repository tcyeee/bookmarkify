package top.tcyeee.bookmarkify.server.impl

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl
import org.jsoup.nodes.Document
import org.springframework.stereotype.Service
import top.tcyeee.bookmarkify.config.entity.ProjectConfig
import top.tcyeee.bookmarkify.entity.dto.BookmarkUrl
import top.tcyeee.bookmarkify.entity.entity.Bookmark
import top.tcyeee.bookmarkify.entity.entity.BookmarkUserLink
import top.tcyeee.bookmarkify.entity.entity.HomeItem
import top.tcyeee.bookmarkify.entity.response.BookmarkShow
import top.tcyeee.bookmarkify.entity.response.HomeItemShow
import top.tcyeee.bookmarkify.mapper.BookmarkMapper
import top.tcyeee.bookmarkify.mapper.BookmarkUserLinkMapper
import top.tcyeee.bookmarkify.mapper.HomeItemMapper
import top.tcyeee.bookmarkify.server.IBookmarkService
import top.tcyeee.bookmarkify.utils.BaseUtils
import top.tcyeee.bookmarkify.utils.BookmarkUtils
import top.tcyeee.bookmarkify.utils.SocketUtils
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
        checkOne(bookmark)
        val bookmarkShow: BookmarkShow = bookmarkUserLinkMapper.findOne(id)
        bookmarkShow.clean(projectConfig.imgPrefix)
        SocketUtils.updateMarkbook(bookmarkShow.uid!!, bookmarkShow)
    }

    override fun checkOne(bookmark: Bookmark) {
        log.trace("[CHECK] 开始解析域名:{}...${bookmark.rawUrl}")

        /* 检查网站活性 */
        val document: Document? = BookmarkUtils.getDocument(bookmark.rawUrl)
        document ?: return offline(bookmark)

        /* 设置标题,描述,LOGO */
        bookmark.setTitle(document)
        val iconStorePath = BookmarkUtils.getLogoUrl(document, projectConfig.imgPath, bookmark)
        bookmark.setLogo(iconStorePath)
        updateById(bookmark)
    }

    override fun offline(bookmark: Bookmark) {
        bookmark.title = bookmark.urlHost
        bookmark.checkActity(false)
        updateById(bookmark)
    }

    override fun checkAll() =
        ktQuery().lt(Bookmark::updateTime, BaseUtils.yesterday()).list().forEach(this::checkOne)

    override fun addOne(url: String, uid: String): HomeItemShow {
        val bookmarkUrl = BookmarkUrl(url)
        val bookmark = findByHost(bookmarkUrl.urlHost) ?: Bookmark(bookmarkUrl).also { save(it) }

        // 添加用户关联和桌面布局
        val userLink = BookmarkUserLink(bookmarkUrl, uid, bookmark)
        bookmarkUserLinkMapper.insert(userLink)
        val homeItem = HomeItem(uid, userLink.id)
        homeItemMapper.insert(homeItem)

        // 异步检查
        CompletableFuture.runAsync { this.checkOne(bookmark, userLink.id) }
        return HomeItemShow(homeItem.id, uid, bookmark.id)
    }

    override fun findByHost(host: String): Bookmark? = ktQuery().eq(Bookmark::urlHost, host).one()
}
