package top.tcyeee.bookmarkify.server.impl

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl
import org.springframework.stereotype.Service
import org.springframework.web.multipart.MultipartFile
import top.tcyeee.bookmarkify.config.entity.ProjectConfig
import top.tcyeee.bookmarkify.entity.AllOfMyBookmarkParams
import top.tcyeee.bookmarkify.entity.BookmarkShow
import top.tcyeee.bookmarkify.entity.HomeItemShow
import top.tcyeee.bookmarkify.entity.entity.BookmarkEntity
import top.tcyeee.bookmarkify.entity.entity.BookmarkUserLink
import top.tcyeee.bookmarkify.entity.entity.HomeItem
import top.tcyeee.bookmarkify.mapper.BookmarkMapper
import top.tcyeee.bookmarkify.mapper.BookmarkUserLinkMapper
import top.tcyeee.bookmarkify.mapper.HomeItemMapper
import top.tcyeee.bookmarkify.server.IBookmarkService
import top.tcyeee.bookmarkify.server.IKafkaMessageService
import top.tcyeee.bookmarkify.utils.ChromeBookmarkParser
import top.tcyeee.bookmarkify.utils.WebsiteParser
import top.tcyeee.bookmarkify.utils.yesterday

/**
 * @author tcyeee
 * @date 3/10/24 15:46
 */
@Service
class BookmarkServiceImpl(
    private val bookmarkUserLinkMapper: BookmarkUserLinkMapper,
    private val homeItemMapper: HomeItemMapper,
    private val projectConfig: ProjectConfig,
    private val kafkaMessageService: IKafkaMessageService,
) : IBookmarkService, ServiceImpl<BookmarkMapper, BookmarkEntity>() {

    // 获取配置信息
    override fun setDefaultBookmark(uid: String) = projectConfig.defaultBookmarkify.forEach { this.addOne(it, uid) }

    override fun search(name: String): List<BookmarkEntity> =
        ktQuery()
            .eq(BookmarkEntity::isActivity, true)
            .like(BookmarkEntity::appName, name)
            .or().like(BookmarkEntity::title, name)
            .or().like(BookmarkEntity::description, name)
            .or().like(BookmarkEntity::urlHost, name)
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

    override fun checkAll() = ktQuery().lt(BookmarkEntity::updateTime, yesterday()).list()
        .forEach(kafkaMessageService::bookmarkParse)

    override fun addOne(url: String, uid: String): HomeItemShow {
        val bookmarkUrl = WebsiteParser.urlWrapper(url)
        val bookmark = this.getByHost(bookmarkUrl.urlHost) ?: BookmarkEntity(bookmarkUrl).also { save(it) }

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

    private fun getByHost(urlHost: String): BookmarkEntity? = ktQuery().eq(BookmarkEntity::urlHost, urlHost).one()
    private fun findById(bookmarkId: String): BookmarkEntity =
        requireNotNull(ktQuery().eq(BookmarkEntity::id, bookmarkId).one())
}