package top.tcyeee.bookmarkify.server.impl

import cn.hutool.core.util.ArrayUtil
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl
import org.springframework.stereotype.Service
import top.tcyeee.bookmarkify.config.entity.ProjectConfig
import top.tcyeee.bookmarkify.config.exception.CommonException
import top.tcyeee.bookmarkify.config.exception.ErrorType
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

    override fun parseAndNotice(bookmark: Bookmark, bookmarkUserLinkId: String) =
        parseBookmark(bookmark)
            // 更新用户布局
            .let { bookmarkUserLinkMapper.findOne(bookmarkUserLinkId) }
            // 通知到前端
            .also { SocketUtils.updateBookmark(it.uid!!, it) }.let { }

    private fun parseBookmark(bookmark: Bookmark) {
        log.warn("[CHECK] 开始解析域名:{}...${bookmark.rawUrl}")
        runCatching { WebsiteParser.parse(bookmark.rawUrl) }.getOrElse {
            if (it.message.toString().contains("403")) bookmark.toggleAntiCrawlerDetected(true)
            if (it.message.toString().contains("304")) bookmark.toggleActivity(false)
            return
        }
            // 填充bookmark基础信息 以及 bokmark-ico-base64信息
            .also { bookmark.initBaseInfo(it) }
            // 更新书签
            .also { this.saveOrUpdate(bookmark) }
            // 保存网站LOGO/OG图片到OSS和数据库
            .also { this.saveOrUpdateLogo(it, bookmark) }
    }

    // 获取配置信息
    override fun setDefaultBookmark(uid: String) =
        projectConfig.defaultBookmarkify.forEach { this.addOne(it, uid) }

    override fun checkAll() = ktQuery().lt(Bookmark::updateTime, yesterday()).list().forEach(this::parseBookmark)

    override fun addOne(url: String, uid: String): HomeItemShow {
        val bookmarkUrl = WebsiteParser.urlWrapper(url)
        val bookmark = this.getByHost(bookmarkUrl.urlHost) ?: Bookmark(bookmarkUrl).also { save(it) }

        // 添加用户关联和桌面布局
        val userLink = BookmarkUserLink(bookmarkUrl, uid, bookmark)
        bookmarkUserLinkMapper.insert(userLink)

        val homeItem = HomeItem(uid, userLink.id)
        homeItemMapper.insert(homeItem)

        // 异步检查 书签如果没有高清icon, 并且updatetime已经超过一天再检查
        if (bookmark.maximalLogoSize == 0 && bookmark.updateTime.isBefore(yesterday())) {
            CompletableFuture.runAsync { this.parseAndNotice(bookmark, userLink.id) }
        }

        // 返回临时网站信息
        return HomeItemShow(homeItem.id, uid, bookmark.id)
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

    fun Bookmark.toggleAntiCrawlerDetected(antiCrawlerDetected: Boolean) {
        this.antiCrawlerDetected = antiCrawlerDetected
        this.updateTime = LocalDateTime.now()
        saveOrUpdate(this)
    }

    fun Bookmark.toggleActivity(activity: Boolean) {
        this.isActivity = activity
        this.updateTime = LocalDateTime.now()
        saveOrUpdate(this)
    }

    fun Bookmark.setMaximalLogoSize(maximal: Int) {
        this.maximalLogoSize = maximal
        this.isActivity = true
        this.updateTime = LocalDateTime.now()
        saveOrUpdate(this)
    }
}