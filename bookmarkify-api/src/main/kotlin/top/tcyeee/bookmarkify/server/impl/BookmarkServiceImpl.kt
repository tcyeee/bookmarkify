package top.tcyeee.bookmarkify.server.impl

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl
import org.springframework.stereotype.Service
import org.springframework.web.multipart.MultipartFile
import top.tcyeee.bookmarkify.config.entity.ProjectConfig
import top.tcyeee.bookmarkify.entity.AllOfMyBookmarkParams
import top.tcyeee.bookmarkify.entity.BookmarkShow
import top.tcyeee.bookmarkify.entity.HomeItemShow
import top.tcyeee.bookmarkify.entity.entity.Bookmark
import top.tcyeee.bookmarkify.entity.entity.BookmarkUserLink
import top.tcyeee.bookmarkify.entity.entity.HomeItem
import top.tcyeee.bookmarkify.mapper.BookmarkMapper
import top.tcyeee.bookmarkify.mapper.BookmarkUserLinkMapper
import top.tcyeee.bookmarkify.mapper.HomeItemMapper
import top.tcyeee.bookmarkify.server.IBookmarkService
import top.tcyeee.bookmarkify.server.IKafkaMessageService
import top.tcyeee.bookmarkify.server.IWebsiteLogoService
import top.tcyeee.bookmarkify.utils.*
import java.time.LocalDateTime

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
    private val kafkaMessageService: IKafkaMessageService,
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

    /**
     * 数据读取完成以后,立即返回占位信息
     * 等待书签解析完成以后,通过WebSocket逐个向前端返回解析完成后的数据
     */
    override fun importBookmarkFile(file: MultipartFile, uid: String): List<BookmarkShow>? {
        // 拿到原始数据
        val trim = ChromeBookmarkParser.trim(file)
        // TODO 生成占位信息并返回
        // TODO 通过kafka检查数据并通知
        return emptyList()
    }

    override fun checkAll() = ktQuery().lt(Bookmark::updateTime, yesterday()).list()
        .forEach(kafkaMessageService::bookmarkParse)

    override fun addOne(url: String, uid: String): HomeItemShow {
        val bookmarkUrl = WebsiteParser.urlWrapper(url)
        val bookmark = this.getByHost(bookmarkUrl.urlHost) ?: Bookmark(bookmarkUrl).also { save(it) }

        // 添加用户关联和桌面布局
        val userLink = BookmarkUserLink(bookmarkUrl, uid, bookmark).also { bookmarkUserLinkMapper.insert(it) }
        val homeItem = HomeItem(uid, userLink.id).also { homeItemMapper.insert(it) }

        // 异步检查 书签如果没有高清icon, 并且updateTime已经超过一天再检查
        if (bookmark.checkFlag) return HomeItemShow(homeItem.id, uid, bookmark.id)
            .also { kafkaMessageService.bookmarkParseAndNotice(uid, bookmark, userLink.id, homeItem.id) }

        // 如果无需更新书签,则直接将旧书签打包为桌面元素返回
        return bookmarkUserLinkMapper.findShowById(userLink.id)
            .let { HomeItemShow(uid, homeItem.id, it.initLogo()) }
    }

    override fun parseBookmark(bookmark: Bookmark) {
        log.warn("[CHECK] 开始解析域名:{}...${bookmark.rawUrl}")
        val wrapper = runCatching { WebsiteParser.parse(bookmark.rawUrl) }
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
        if (wrapper.distinctIcons.isNullOrEmpty()) return
        OssUtils.restoreWebsiteLogoAndOg(wrapper.distinctIcons, bookmark.id)
            // 更新/保存LOGO到数据库
            ?.also { websiteLogoService.updateMaximalLogoByBookmarkId(it) }
            // 更新/保存最大图标信息
            ?.also { bookmark.setMaximalLogoSize(it.width) }

    }

    override fun findBookmarkAndNotice(bookmarkUserLinkId: String, homeItemId: String, uid: String) =
        bookmarkUserLinkMapper
            // 找到用户自定义书签
            .findShowById(bookmarkUserLinkId)
            // 包装为桌面元素
            .let { HomeItemShow(uid, homeItemId, it.also { it.initLogo() }) }
            // 通知
            .also { SocketUtils.homeItemUpdate(uid, it) }.run {}

    private fun getByHost(urlHost: String): Bookmark? = ktQuery().eq(Bookmark::urlHost, urlHost).one()

    fun Bookmark.setMaximalLogoSize(maximal: Int) {
        this.maximalLogoSize = maximal
        this.isActivity = true
        this.updateTime = LocalDateTime.now()
        saveOrUpdate(this)
    }

    fun findById(bookmarkId: String): Bookmark = requireNotNull(ktQuery().eq(Bookmark::id, bookmarkId).one())
}