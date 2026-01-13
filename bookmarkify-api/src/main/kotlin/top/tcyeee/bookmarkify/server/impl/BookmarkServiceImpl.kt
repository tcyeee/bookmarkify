package top.tcyeee.bookmarkify.server.impl

import com.baomidou.mybatisplus.core.metadata.IPage
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.multipart.MultipartFile
import top.tcyeee.bookmarkify.config.entity.ProjectConfig
import top.tcyeee.bookmarkify.entity.*
import top.tcyeee.bookmarkify.entity.dto.BookmarkUrlWrapper
import top.tcyeee.bookmarkify.entity.entity.*
import top.tcyeee.bookmarkify.entity.entity.BookmarkUserLink
import top.tcyeee.bookmarkify.entity.enums.ParseStatusEnum
import top.tcyeee.bookmarkify.mapper.*
import top.tcyeee.bookmarkify.server.IBookmarkService
import top.tcyeee.bookmarkify.server.IBookmarkUserLinkService
import top.tcyeee.bookmarkify.server.IKafkaMessageService
import top.tcyeee.bookmarkify.server.IUserLayoutNodeService
import top.tcyeee.bookmarkify.utils.*
import java.time.LocalDateTime

/**
 * @author tcyeee
 * @date 3/10/24 15:46
 */
@Service
class BookmarkServiceImpl(
    private val bookmarkUserLinkMapper: BookmarkUserLinkMapper,
    private val projectConfig: ProjectConfig,
    private val kafkaMessageService: IKafkaMessageService,
    private val layoutNodeService: IUserLayoutNodeService,
    private val layoutNodeMapper: UserLayoutNodeMapper,
    private val websiteLogoMapper: WebsiteLogoMapper,
    private val bookmarkUserLinkService: IBookmarkUserLinkService,
    private val bookmarkFunctionMapper: BookmarkFunctionMapper,
) : IBookmarkService, ServiceImpl<BookmarkMapper, BookmarkEntity>() {

    // 找到全部的系统默认书签,存储用户桌面布局和自定义书签
    override fun setDefaultBookmark(uid: String) =
        projectConfig.defaultBookmarkify.map { WebsiteParser.urlWrapper(it).urlHost }.let { this.findListByHost(it) }
            .map { bookmark ->
                UserLayoutNodeEntity(uid = uid).let { node -> Pair(node, BookmarkUserLink(bookmark, node.id, uid)) }
            }.also { pair ->
                layoutNodeMapper.insert(pair.map { it.first })
                bookmarkUserLinkMapper.insert(pair.map { it.second })
            }.run {}

    override fun findByHost(host: String): BookmarkEntity? = ktQuery().eq(BookmarkEntity::urlHost, host).one()

    @Transactional
    override fun setDefaultFuction(uid: String) =
        UserLayoutNodeEntity(uid = uid, type = NodeTypeEnum.FUNCTION).also { layoutNodeMapper.insert(it) }
            .let { BookmarkFunctionEntity(it, uid) }.also { bookmarkFunctionMapper.insert(it) }.run {}

    override fun search(name: String): List<BookmarkEntity> =
        ktQuery().eq(BookmarkEntity::isActivity, true).like(BookmarkEntity::appName, name).or()
            .like(BookmarkEntity::title, name).or().like(BookmarkEntity::description, name).or()
            .like(BookmarkEntity::urlHost, name).last("limit 5").list()

    override fun linkOne(bookmarkId: String, uid: String): UserLayoutNodeVO {
        val nodeEntity = UserLayoutNodeEntity(uid = uid).also { layoutNodeMapper.insert(it) }

        val userLink = this.findById(bookmarkId).let { BookmarkUserLink(it, nodeEntity.id, uid) }
            .also { bookmarkUserLinkMapper.insert(it) }

        return bookmarkUserLinkMapper.findShowById(userLink.id).initLogo().let { UserLayoutNodeVO(nodeEntity, it) }
    }

    override fun allOfMyBookmark(uid: String, params: AllOfMyBookmarkParams): IPage<BookmarkShow> {
        val result = bookmarkUserLinkMapper.selectPage(params.toPage(), params.toWrapper())
        val bookmarkIds: List<String> = result.records.mapNotNull { it.bookmarkId }
        val bookmarkEntityMap = baseMapper.selectByIds(bookmarkIds).associateBy { it.id }
        return result.convert { BookmarkShow(it, bookmarkEntityMap[it.bookmarkId]).initLogo() }
    }

    /**
     * 数据读取完成以后,立即返回占位信息
     * 等待书签解析完成以后,通过WebSocket逐个向前端返回解析完成后的数据
     */
    override fun importBookmarkFile(file: MultipartFile, uid: String): UserLayoutNodeVO {
        // 1. 解析上传文件，获取扁平化后的书签结构
        val structures: List<SystemBookmarkStructure> = ChromeBookmarkParser.trim(file)
        // 2.保存所有的文件夹,同时保存ID
        structures.map { item -> UserLayoutNodeEntity(uid, item).also { item.nodeId = it.id } }
            .also { layoutNodeMapper.insert(it) }
        // 3.初始化全部的用户布局item和自定义标签,同时保存
        // 因为这里用户的自定义书签是已知的,但是不确定源书签不会在数据库中存在,所以先存储用户的自定义书签,关联书签ID设置为LOADING,
        // 然后对每个源书签单独检查,每检查完一个源书签,就根据源书签host,去找到用户书签的host,将书签ID补上.
        val pair = structures.flatMap { node -> node.bookmarks.map { it.pair(uid) } }
            .also { data -> layoutNodeMapper.insert(data.map { it.first }) }
            .also { data -> bookmarkUserLinkMapper.insert(data.map { it.second }) }

        // 4.数据清洗,只保留raw-url, 解析为源书签,解析以后,重新绑定用户自定义书签
        pair.forEach {
            kafkaMessageService.bookmarkParseAndResetUserItem(uid, it.second.urlFull, it.second.id, it.first.id)
        }

        // 重新获取书签数据
        return layoutNodeService.layout(uid)
    }

    override fun checkAll() =
        ktQuery().lt(BookmarkEntity::updateTime, yesterday()).lt(BookmarkEntity::verifyFlag, false).list()
            .forEach { kafkaMessageService.bookmarkParse(it.id) }

    override fun addOne(url: String, uid: String): UserLayoutNodeVO {
        // 添加书签信息
        val bookmarkUrl: BookmarkUrlWrapper = WebsiteParser.urlWrapper(url)
        val bookmark = this.getByHost(bookmarkUrl.urlHost) ?: BookmarkEntity(bookmarkUrl).also { save(it) }

        // 添加用户布局信息
        val nodeEntity =
            UserLayoutNodeEntity(uid = uid, type = NodeTypeEnum.BOOKMARK_LOADING).also { layoutNodeMapper.insert(it) }
        // 添加用户关联
        val userLink = BookmarkUserLink(url, uid, nodeEntity.id, bookmark).also { bookmarkUserLinkMapper.insert(it) }

        // 返回占位信息,同时通知队列去检查书签
        if (bookmark.checkFlag()) return nodeEntity.loadingVO()
            .also { kafkaMessageService.bookmarkParseAndNotice(uid, bookmark.id, userLink.id, nodeEntity.id) }

        // 如果无需更新书签,则直接将旧书签打包为桌面元素返回
        return bookmarkUserLinkMapper.findShowById(userLink.id).let { UserLayoutNodeVO(nodeEntity, it) }
    }

    override fun adminListAll(params: BookmarkSearchParams): IPage<BookmarkAdminVO> =
        baseMapper.selectPage(params.toPage(), params.toWrapper()).convert { BookmarkAdminVO(it) }

    private fun getByHost(urlHost: String): BookmarkEntity? = ktQuery().eq(BookmarkEntity::urlHost, urlHost).one()
    private fun findById(bookmarkId: String): BookmarkEntity =
        requireNotNull(ktQuery().eq(BookmarkEntity::id, bookmarkId).one())

    /** 解析书签,然后保存到数据库,同时通知到用户 */
    override fun kafKaBookmarkParseAndNotice(uid: String, bookmarkId: String, userLinkId: String, nodeId: String) {
        val bookmark = baseMapper.selectById(bookmarkId)
        this.parseBookmarkAndSave(bookmark)
        // 找到用户自定义书签
        val bookmarkShow = bookmarkUserLinkMapper.findShowById(userLinkId).initLogo()
        // 找到用户的单条布局信息
        val layoutEntity = layoutNodeMapper.selectById(nodeId)
        // 通知到前端
        UserLayoutNodeVO(layoutEntity, bookmarkShow).also { SocketUtils.homeItemUpdate(uid, it) }
    }

    /**
     * 通过网址解析为书签,同时重新绑定到添加这个网址的用户
     * 1.解析书签,更新书签状态(之前是LOADING)
     * 2.根据host重新绑定用户自定义书签
     * 3.修改用户布局元素状态(之前是LOADING)
     *
     * 为什么要重新绑定？
     * 答: 用户添加网址的时候是批量添加的,只能提前批量返回用户自定义的书签,用户自定义的书签具体有没有存在源书签还不知道,所以查询完毕知道以后,再重新关联回去
     */
    override fun kafkaBookmarkParseAndResetUserItem(
        uid: String, rawUrl: String, userLinkId: String, layoutNodeId: String
    ) {
        // 先通过Host看一下数据库有没有有元书签，如果有的话，那么那么直接使用元书签,没有则解析出元书签,同时存储
        val urlWrapper = WebsiteParser.urlWrapper(rawUrl)
        val entity = this.findByHost(urlWrapper.urlHost) ?: BookmarkEntity(urlWrapper).also { save(it) }

        // 解析书签
        this.parseBookmarkAndSave(entity)
        // 将用户自定义书签和原书签关联
        bookmarkUserLinkService.resetBookmarkId(uid, userLinkId, entity.id)
        // 修改用户节点状态
        val node = UserLayoutNodeEntity(id = layoutNodeId, uid = uid, type = NodeTypeEnum.BOOKMARK)
            .also { layoutNodeMapper.insertOrUpdate(it) }
        // 通知到用户
        val bookmarkShow = bookmarkUserLinkMapper.findShowById(userLinkId).initLogo()
        UserLayoutNodeVO(node, bookmarkShow).also { SocketUtils.homeItemUpdate(uid, it) }
    }

    override fun kafkaBookmarkParse(bookmarkId: String) =
        baseMapper.selectById(bookmarkId).also { parseBookmarkAndSave(it) }.run {}

    override fun findListByHost(defaultBookmarkify: List<String>): List<BookmarkEntity> =
        ktQuery().`in`(BookmarkEntity::urlHost, defaultBookmarkify).list()

    /** 解析书签,保存书签到根节点,并通知到用户 */
    override fun kafKaBookmarkParseAndNotice(uid: String, bookmarkId: String, parentNodeId: String?) {
        val bookmark = baseMapper.selectById(bookmarkId)
        // 保存书签信息
        this.parseBookmarkAndSave(bookmark)
        // 保存布局信息
        val layoutEntity = UserLayoutNodeEntity(uid = uid, parentId = parentNodeId).also { layoutNodeMapper.insert(it) }
        // 保存用户自定义书签信息
        val userLinkEntity = BookmarkUserLink(bookmark, layoutEntity.id, uid).also { bookmarkUserLinkMapper.insert(it) }
        // 找到用户自定义书签
        val bookmarkShow = bookmarkUserLinkMapper.findShowById(userLinkEntity.id).initLogo()
        // 通知到前端
        UserLayoutNodeVO(layoutEntity, bookmarkShow).also { SocketUtils.homeItemUpdate(uid, it) }
    }

    /**
     * 1.解析书签
     * 2.保存到数据库
     *
     * 这里的书签大概率是临时生成的,数据库可能会有一模一样且不需要进行任何处理的完整书签
     * 所以先进判断，如果数据库有，并且verifyFlag为True,那么就不再进行解析了
     * verifyFlag: 手动认证开关, 这个字段如果为真, 该不会再进行任何变动.
     */
    private fun parseBookmarkAndSave(bookmark: BookmarkEntity): BookmarkEntity {
        // 如果书签依旧解析了,则不需要继续解析
        val oldBookmarkEneity = baseMapper.selectById(bookmark.id)
        if (oldBookmarkEneity != null && oldBookmarkEneity.verifyFlag) return oldBookmarkEneity

        log.trace("[CHECK] 开始解析域名:${bookmark.rawUrl}")
        val wrapper = runCatching { WebsiteParser.parse(bookmark.rawUrl) }.getOrElse {
            if (it.message.toString().contains("403")) bookmark.parseStatus = ParseStatusEnum.BLOCKED
            bookmark.parseErrMsg = it.message.toString()
            bookmark.isActivity = false
            bookmark.parseStatus = ParseStatusEnum.CLOSED
            baseMapper.insertOrUpdate(bookmark)
            it.printStackTrace()
            return bookmark
        }
            // 填充bookmark基础信息 以及 bookmark-ico-base64信息
            .also { bookmark.successInit(it) }
            // 更新书签
            .also { baseMapper.insertOrUpdate(bookmark) }

        // 保存网站LOGO/OG图片到OSS和数据库
        if (wrapper.distinctIcons.isNullOrEmpty()) return bookmark
        OssUtils.restoreWebsiteLogoAndOg(wrapper.distinctIcons, bookmark.id)
            // 更新/保存LOGO到数据库
            ?.also { websiteLogoMapper.insertOrUpdate(it) }
            // 更新/保存最大图标信息
            ?.also { bookmark.setMaximalLogoSize(it.width) }
        return bookmark
    }

    // 在BookmarkEntity设置LOGO最大尺寸
    private fun BookmarkEntity.setMaximalLogoSize(maximal: Int) {
        this.maximalLogoSize = maximal
        this.isActivity = true
        this.updateTime = LocalDateTime.now()
        baseMapper.insertOrUpdate(this)
    }
}
