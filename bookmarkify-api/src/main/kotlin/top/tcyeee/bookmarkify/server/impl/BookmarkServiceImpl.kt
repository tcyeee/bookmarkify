package top.tcyeee.bookmarkify.server.impl

import com.baomidou.mybatisplus.core.metadata.IPage
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl
import mapPage
import org.springframework.stereotype.Service
import org.springframework.web.multipart.MultipartFile
import top.tcyeee.bookmarkify.config.entity.ProjectConfig
import top.tcyeee.bookmarkify.entity.AllOfMyBookmarkParams
import top.tcyeee.bookmarkify.entity.BookmarkAdminVO
import top.tcyeee.bookmarkify.entity.BookmarkSearchParams
import top.tcyeee.bookmarkify.entity.BookmarkShow
import top.tcyeee.bookmarkify.entity.HomeItemShow
import top.tcyeee.bookmarkify.entity.UserLayoutNodeVO
import top.tcyeee.bookmarkify.entity.dto.BookmarkUrlWrapper
import top.tcyeee.bookmarkify.entity.entity.BookmarkEntity
import top.tcyeee.bookmarkify.entity.entity.BookmarkUserLink
import top.tcyeee.bookmarkify.entity.entity.UserLayoutNodeEntity
import top.tcyeee.bookmarkify.mapper.BookmarkMapper
import top.tcyeee.bookmarkify.mapper.BookmarkUserLinkMapper
import top.tcyeee.bookmarkify.mapper.UserLayoutNodeMapper
import top.tcyeee.bookmarkify.server.IBookmarkService
import top.tcyeee.bookmarkify.server.IHomeItemService
import top.tcyeee.bookmarkify.server.IKafkaMessageService
import top.tcyeee.bookmarkify.utils.ChromeBookmarkParser
import top.tcyeee.bookmarkify.utils.WebsiteParser
import top.tcyeee.bookmarkify.utils.yesterday
import java.time.LocalDateTime

/**
 * @author tcyeee
 * @date 3/10/24 15:46
 */
@Service
class BookmarkServiceImpl(
    private val bookmarkUserLinkMapper: BookmarkUserLinkMapper,
    private val homeItemService: IHomeItemService,
    private val projectConfig: ProjectConfig,
    private val kafkaMessageService: IKafkaMessageService,
    private val nodeMapper: UserLayoutNodeMapper
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

    override fun linkOne(bookmarkId: String, uid: String): UserLayoutNodeVO {
        val nodeEntity = UserLayoutNodeEntity(uid = uid).also { nodeMapper.insert(it) }

        val userLink = this.findById(bookmarkId)
            .let { BookmarkUserLink(it, nodeEntity.id, uid) }
            .also { bookmarkUserLinkMapper.insert(it) }

        return bookmarkUserLinkMapper.findShowById(userLink.id).initLogo()
            .let { UserLayoutNodeVO(nodeEntity, it) }
    }

    override fun allOfMyBookmark(uid: String, params: AllOfMyBookmarkParams): List<BookmarkShow> =
        bookmarkUserLinkMapper.allBookmarkByUid(uid).onEach { it.initLogo() }

    /**
     * 数据读取完成以后,立即返回占位信息
     * 等待书签解析完成以后,通过WebSocket逐个向前端返回解析完成后的数据
     */
    override fun importBookmarkFile(file: MultipartFile, uid: String): List<HomeItemShow>? {
        // 1. 解析上传文件，获取扁平化后的书签结构
        val structures = ChromeBookmarkParser.trim(file)
        // 2. 将解析结果落库，并立即生成占位 HomeItemShow，同时提交解析任务
        return homeItemService.chromeBookmarksPackage(structures, uid)
    }

    override fun checkAll() = ktQuery().lt(BookmarkEntity::updateTime, yesterday()).list()
        .forEach(kafkaMessageService::bookmarkParse)

    override fun addOne(url: String, uid: String): UserLayoutNodeVO {
        // 添加书签信息
        val bookmarkUrl: BookmarkUrlWrapper = WebsiteParser.urlWrapper(url)
        val bookmark = this.getByHost(bookmarkUrl.urlHost) ?: BookmarkEntity(bookmarkUrl).also { save(it) }

        // 添加用户布局信息
        val nodeEntity = UserLayoutNodeEntity(uid = uid).also { nodeMapper.insert(it) }
        // 添加用户关联
        val userLink = BookmarkUserLink(url, uid, nodeEntity.id, bookmark).also { bookmarkUserLinkMapper.insert(it) }

        // 返回占位信息,同时通知队列去检查书签
        if (bookmark.checkFlag()) return nodeEntity.loadingVO()
            .also { kafkaMessageService.bookmarkParseAndNotice(uid, bookmark, userLink.id, nodeEntity.id) }

        // 如果无需更新书签,则直接将旧书签打包为桌面元素返回
        return bookmarkUserLinkMapper.findShowById(userLink.id).let { UserLayoutNodeVO(nodeEntity, it) }
    }


    override fun adminUpdateOne(
        id: String,
        appName: String?,
        title: String?,
        description: String?,
        iconBase64: String?,
        isActivity: Boolean?,
        maximalLogoSize: Int?
    ): Boolean {
        val entity = ktQuery().eq(BookmarkEntity::id, id).one() ?: return false
        appName?.let { entity.appName = it }
        title?.let { entity.title = it }
        description?.let { entity.description = it }
        iconBase64?.let { entity.iconBase64 = it }
        isActivity?.let { entity.isActivity = it }
        maximalLogoSize?.let { entity.maximalLogoSize = it }
        entity.updateTime = LocalDateTime.now()
        return updateById(entity)
    }

    override fun adminListAll(params: BookmarkSearchParams): IPage<BookmarkAdminVO> {
        return baseMapper
            .selectPage(params.toPage(), params.toWrapper())
            .mapPage { BookmarkAdminVO(it) }
    }


    private fun getByHost(urlHost: String): BookmarkEntity? = ktQuery().eq(BookmarkEntity::urlHost, urlHost).one()
    private fun findById(bookmarkId: String): BookmarkEntity =
        requireNotNull(ktQuery().eq(BookmarkEntity::id, bookmarkId).one())
}