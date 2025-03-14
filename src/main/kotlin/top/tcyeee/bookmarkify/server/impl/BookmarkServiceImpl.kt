package top.tcyeee.bookmarkify.server.impl

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl
import org.jsoup.nodes.Document
import org.springframework.stereotype.Service
import top.tcyeee.bookmarkify.config.entity.ProjectConfig
import top.tcyeee.bookmarkify.config.exception.CommonException
import top.tcyeee.bookmarkify.config.exception.ErrorType
import top.tcyeee.bookmarkify.entity.dto.BookmarkUrl
import top.tcyeee.bookmarkify.entity.po.Bookmark
import top.tcyeee.bookmarkify.entity.po.BookmarkUserLink
import top.tcyeee.bookmarkify.entity.po.HomeItem
import top.tcyeee.bookmarkify.mapper.BookmarkMapper
import top.tcyeee.bookmarkify.mapper.BookmarkUserLinkMapper
import top.tcyeee.bookmarkify.mapper.HomeItemMapper
import top.tcyeee.bookmarkify.server.IBookmarkService
import top.tcyeee.bookmarkify.utils.BaseUtils
import top.tcyeee.bookmarkify.utils.BookmarkUtils
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


    override fun checkOne(bookmark: Bookmark?) {
        if (bookmark == null) throw CommonException(ErrorType.E105)
        log.trace("[CHECK] 开始解析域名:{}...${bookmark.fullUrl()}")

        // 检查网站活性
        val document: Document? = BookmarkUtils.getDocument(bookmark.fullUrl())
        if (document == null) {
            bookmark.title = bookmark.urlHost
            bookmark.checkActity(false)
            updateById(bookmark)
            return
        }

        // 设置标题,描述,LOGO
        bookmark.title = BookmarkUtils.getTitle(document)
        bookmark.description = BookmarkUtils.getDescription(document)
        bookmark.addIcon(BookmarkUtils.getLogoUrl(document, projectConfig.imgPath, bookmark))
        log.trace("[CHECK] 书签:{} 解析完成! ${bookmark.fullUrl()}")
        updateById(bookmark)
    }

    override fun checkAll() {
        ktQuery().lt(Bookmark::updateTime, BaseUtils.yesterday()).list()
            .forEach(this::checkOne)
    }

    override fun addOne(url: String, uid: String) {
        val bookmarkUrl = BookmarkUrl(url)
        var bookmark = ktQuery().eq(Bookmark::urlHost, bookmarkUrl.urlHost).one()
        if (bookmark == null) {
            bookmark = Bookmark(bookmarkUrl)
            save(bookmark)
        }

        val finalBookmark = bookmark
        CompletableFuture.runAsync { this.checkOne(finalBookmark) }

        // 添加用户关联和桌面布局
        val userLink = BookmarkUserLink(bookmarkUrl, uid, bookmark)
        bookmarkUserLinkMapper.insert(userLink)
        homeItemMapper.insert(HomeItem(bookmark, uid, userLink.id))
    }
}
