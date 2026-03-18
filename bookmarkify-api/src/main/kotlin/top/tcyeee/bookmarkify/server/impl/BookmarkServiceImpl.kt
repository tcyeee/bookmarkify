package top.tcyeee.bookmarkify.server.impl

import cn.hutool.core.date.LocalDateTimeUtil
import com.baomidou.mybatisplus.core.metadata.IPage
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.multipart.MultipartFile
import top.tcyeee.bookmarkify.config.entity.ProjectConfig
import top.tcyeee.bookmarkify.config.exception.CommonException
import top.tcyeee.bookmarkify.config.exception.ErrorType
import top.tcyeee.bookmarkify.entity.*
import top.tcyeee.bookmarkify.entity.dto.BookmarkUrlWrapper
import top.tcyeee.bookmarkify.entity.dto.ManifestIcon
import top.tcyeee.bookmarkify.entity.entity.*
import top.tcyeee.bookmarkify.entity.enums.ParseStatusEnum
import top.tcyeee.bookmarkify.mapper.*
import top.tcyeee.bookmarkify.server.IApiService
import top.tcyeee.bookmarkify.server.IBookmarkService
import top.tcyeee.bookmarkify.server.IBookmarkUserLinkService
import top.tcyeee.bookmarkify.server.IKafkaMessageService
import top.tcyeee.bookmarkify.utils.*
import java.time.LocalDateTime
import java.time.temporal.ChronoUnit

/**
 * @author tcyeee
 * @date 3/10/24 15:46
 */
@Service
class BookmarkServiceImpl(
    private val bookmarkUserLinkMapper: BookmarkUserLinkMapper,
    private val projectConfig: ProjectConfig,
    private val kafkaMessageService: IKafkaMessageService,
    private val apiService: IApiService,
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
    override fun setDefaultFunction(uid: String) =
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
        val bookmarkEntityMap =
            if (bookmarkIds.isEmpty()) emptyMap() else baseMapper.selectByIds(bookmarkIds).associateBy { it.id }
        return result.convert { BookmarkShow(it, bookmarkEntityMap[it.bookmarkId]).initLogo() }
    }

    /**
     * 数据读取完成以后,立即返回占位信息
     * 等待书签解析完成以后,通过WebSocket逐个向前端返回解析完成后的数据
     */
    override fun importBookmarkFile(file: MultipartFile, uid: String) {
        // 1. 解析上传文件，获取扁平化后的书签结构
        val structures: List<SystemBookmarkStructure> = ChromeBookmarkParser.trim(file)
        // 2. 保存所有的文件夹,同时保存 nodeId
        structures.map { item -> UserLayoutNodeEntity(uid, item).also { item.nodeId = it.id } }
            .also { layoutNodeMapper.insert(it) }
        // 3. 批量保存布局节点和用户自定义书签（bookmarkId 暂置 LOADING，后续逐个绑定）
        val pair = structures.flatMap { node -> node.bookmarks.map { it.pair(uid, node.nodeId) } }
            .also { data -> layoutNodeMapper.insert(data.map { it.first }) }
            .also { data -> bookmarkUserLinkMapper.insert(data.map { it.second }) }
        // 4. 异步解析每个 URL，解析完成后重新绑定用户自定义书签
        pair.forEach {
            kafkaMessageService.bookmarkParseAndResetUserItem(uid, it.second.urlFull, it.second.id, it.first.id)
        }
    }

    override fun checkAll() =
        ktQuery()
            .lt(BookmarkEntity::updateTime, LocalDateTimeUtil.offset(LocalDateTime.now(), -1, ChronoUnit.DAYS))
            .lt(BookmarkEntity::verifyFlag, false).list()
            .forEach { kafkaMessageService.bookmarkParse(it.id) }

    override fun addOne(url: String, uid: String): UserLayoutNodeVO {
        log.debug("[addOne] uid=$uid 开始添加书签, rawUrl=$url")

        // 1. 标准化 URL，解析出 host、完整地址等结构化信息
        val bookmarkUrl: BookmarkUrlWrapper = WebsiteParser.urlWrapper(url)
        log.debug("[addOne] Step1 URL 标准化完成: urlHost=${bookmarkUrl.urlHost}, urlFull=${bookmarkUrl.urlFull}")

        // 2. 按 urlHost 查找系统中是否已存在该书签记录；
        //    不存在则创建新的占位书签并持久化。
        //    多个用户共享同一条 bookmark 记录（一对多），避免重复抓取同一网站。
        val existingBookmark = this.getByHost(bookmarkUrl.urlHost)
        val bookmark = existingBookmark ?: BookmarkEntity(bookmarkUrl).also {
            save(it)
            log.debug("[addOne] Step2 书签不存在，已创建新书签记录: bookmarkId=${it.id}, urlHost=${bookmarkUrl.urlHost}")
        }
        if (existingBookmark != null) {
            log.debug("[addOne] Step2 命中已有书签记录: bookmarkId=${bookmark.id}, urlHost=${bookmark.urlHost}, parseStatus=${bookmark.parseStatus}")
        }

        // 3. 为当前用户创建桌面布局节点，初始类型为 BOOKMARK_LOADING，
        //    在书签解析完成前前端展示 loading 占位状态。
        val nodeEntity =
            UserLayoutNodeEntity(uid = uid, type = NodeTypeEnum.BOOKMARK_LOADING).also { layoutNodeMapper.insert(it) }
        log.debug("[addOne] Step3 已创建布局节点(LOADING): nodeId=${nodeEntity.id}, uid=$uid")

        // 4. 创建用户与书签的关联记录（bookmark_user_link），
        //    保存该用户自定义的完整 URL、标题、描述等个性化数据。
        val userLink = BookmarkUserLink(url, uid, nodeEntity.id, bookmark).also { bookmarkUserLinkMapper.insert(it) }
        log.debug("[addOne] Step4 已创建用户关联记录: userLinkId=${userLink.id}, bookmarkId=${bookmark.id}")

        // 5. 检查书签是否需要重新解析（首次添加 / 上次解析距今超过 1 天）：
        //    ↳ 需要解析 → 立即返回 loading 占位 VO，同时向 Kafka 发送异步解析任务。
        //                  解析完成后由 kafKaBookmarkParseAndNotice 通过 WebSocket 将最终结果推送到客户端。
        if (bookmark.checkFlag()) {
            log.debug("[addOne] Step5 书签需要解析，返回 LOADING 占位，已发送 Kafka 异步任务: bookmarkId=${bookmark.id}, userLinkId=${userLink.id}, nodeId=${nodeEntity.id}")
            return nodeEntity.loadingVO(bookmark.urlHost)
                .also { kafkaMessageService.bookmarkParseAndNotice(uid, bookmark.id, userLink.id, nodeEntity.id) }
        }

        // 6. 书签在有效期内（1 天内已解析），无需重新抓取。
        //    将节点类型由 BOOKMARK_LOADING 更新为 BOOKMARK 并持久化，然后直接返回完整数据。
        log.debug("[addOne] Step6 书签在有效期内，无需重新解析，直接返回完整数据: bookmarkId=${bookmark.id}, nodeId=${nodeEntity.id}")
        nodeEntity.type = NodeTypeEnum.BOOKMARK
        layoutNodeMapper.updateById(nodeEntity)
        return bookmarkUserLinkMapper.findShowById(userLink.id).let { UserLayoutNodeVO(nodeEntity, it) }
    }

    override fun adminListAll(params: BookmarkSearchParams): IPage<BookmarkAdminVO> =
        baseMapper.selectPage(params.toPage(), params.toWrapper()).convert { BookmarkAdminVO(it) }

    override fun findListByHost(defaultBookmarkify: List<String>): List<BookmarkEntity> =
        ktQuery().`in`(BookmarkEntity::urlHost, defaultBookmarkify).list()

    // ────── Kafka 消费入口 ──────

    override fun kafkaBookmarkParse(bookmarkId: String) {
        parseBookmark(baseMapper.selectById(bookmarkId))
    }

    /** 解析书签，然后保存到数据库，同时通知到用户 */
    override fun kafKaBookmarkParseAndNotice(uid: String, bookmarkId: String, userLinkId: String, nodeId: String) {
        log.debug("[kafKaBookmarkParseAndNotice-4] 开始书签解析: uid=$uid, bookmarkId=$bookmarkId, userLinkId=$userLinkId, nodeId=$nodeId")
        parseBookmark(baseMapper.selectById(bookmarkId))
        log.debug("[kafKaBookmarkParseAndNotice-4] 书签解析完成, 开始构建展示数据: userLinkId=$userLinkId")
        val bookmarkShow = bookmarkUserLinkMapper.findShowById(userLinkId).initLogo()
        log.debug("[kafKaBookmarkParseAndNotice-4] 已查询 bookmarkShow, title=${bookmarkShow.title}, 开始更新布局节点类型: nodeId=$nodeId")
        val layoutEntity = layoutNodeMapper.selectById(nodeId).also {
            it.type = NodeTypeEnum.BOOKMARK
            layoutNodeMapper.updateById(it)
        }
        log.debug("[kafKaBookmarkParseAndNotice-4] 布局节点已更新为 BOOKMARK, 准备推送 WebSocket: uid=$uid, nodeId=$nodeId")
        UserLayoutNodeVO(layoutEntity, bookmarkShow).also { SocketUtils.homeItemUpdate(uid, it) }
        log.debug("[kafKaBookmarkParseAndNotice-4] WebSocket 推送完成: uid=$uid, nodeId=$nodeId")
    }

    /**
     * 通过网址解析为书签，同时重新绑定到添加这个网址的用户
     * 1. 解析书签，更新书签状态（之前是 LOADING）
     * 2. 根据 host 重新绑定用户自定义书签
     * 3. 修改用户布局元素状态（之前是 LOADING）
     *
     * 为什么要重新绑定？
     * 答: 用户添加网址的时候是批量添加的,只能提前批量返回用户自定义的书签,用户自定义的书签具体有没有存在源书签还不知道,所以查询完毕知道以后,再重新关联回去
     */
    override fun kafkaBookmarkParseAndResetUserItem(
        uid: String, rawUrl: String, userLinkId: String, layoutNodeId: String
    ) {
        val urlWrapper = WebsiteParser.urlWrapper(rawUrl)
        val entity = this.findByHost(urlWrapper.urlHost) ?: BookmarkEntity(urlWrapper).also { save(it) }
        if (entity.parseStatus == ParseStatusEnum.LOADING) parseBookmark(entity)
        bookmarkUserLinkService.resetBookmarkId(uid, userLinkId, entity.id)
        val layoutNode: UserLayoutNodeEntity = layoutNodeMapper.selectById(layoutNodeId)
            ?.apply { type = NodeTypeEnum.BOOKMARK }
            ?.also { layoutNodeMapper.updateById(it) }
            ?: throw CommonException(ErrorType.E999)
        bookmarkUserLinkMapper.findShowById(userLinkId).initLogo()
            .let { UserLayoutNodeVO(layoutNode, it) }
            .also { SocketUtils.homeItemUpdate(uid, it) }
    }

    /** 解析书签，保存书签到根节点，并通知到用户 */
    override fun kafKaBookmarkParseAndNotice(uid: String, bookmarkId: String, parentNodeId: String?) {
        log.debug("[kafKaBookmarkParseAndNotice-3] 开始: uid=$uid, bookmarkId=$bookmarkId, parentNodeId=$parentNodeId")
        val bookmark = parseBookmark(baseMapper.selectById(bookmarkId))
        log.debug("[kafKaBookmarkParseAndNotice-3] 书签解析完成: bookmarkId=${bookmark.id}, parseStatus=${bookmark.parseStatus}, appName=${bookmark.appName}")
        val layoutEntity = UserLayoutNodeEntity(uid = uid, parentId = parentNodeId).also { layoutNodeMapper.insert(it) }
        log.debug("[kafKaBookmarkParseAndNotice-3] 已创建布局节点: nodeId=${layoutEntity.id}, parentNodeId=$parentNodeId")
        val userLinkEntity = BookmarkUserLink(bookmark, layoutEntity.id, uid).also { bookmarkUserLinkMapper.insert(it) }
        log.debug("[kafKaBookmarkParseAndNotice-3] 已创建用户关联记录: userLinkId=${userLinkEntity.id}")
        val bookmarkShow = bookmarkUserLinkMapper.findShowById(userLinkEntity.id).initLogo()
        log.debug("[kafKaBookmarkParseAndNotice-3] 已查询 bookmarkShow, 准备推送 WebSocket: uid=$uid, nodeId=${layoutEntity.id}")
        UserLayoutNodeVO(layoutEntity, bookmarkShow).also { SocketUtils.homeItemUpdate(uid, it) }
        log.debug("[kafKaBookmarkParseAndNotice-3] WebSocket 推送完成: uid=$uid, nodeId=${layoutEntity.id}")
    }

    // ────── 公开接口（明确指定解析方式时调用）──────

    /** 通过 iframely 第三方 API 解析书签，若书签已通过手动认证则直接返回 */
    override fun parseBookmarkByApi(bookmark: BookmarkEntity): BookmarkEntity {
        val existing = baseMapper.selectById(bookmark.id)
        if (existing != null && existing.verifyFlag) return existing
        return parseByApi(bookmark)
    }

    // ────── 私有解析层 ──────

    /**
     * 统一解析调度：检查 verifyFlag 后根据配置选择解析方式
     */
    private fun parseBookmark(bookmark: BookmarkEntity): BookmarkEntity {
        log.debug("[parseBookmark] 开始调度解析: bookmarkId=${bookmark.id}, urlHost=${bookmark.urlHost}")
        val existing = baseMapper.selectById(bookmark.id)
        if (existing != null && existing.verifyFlag) {
            log.debug("[parseBookmark] 书签已手动认证(verifyFlag=true), 跳过解析直接返回: bookmarkId=${bookmark.id}")
            return existing
        }
        val mode = if (projectConfig.useThirdPartyParser) "第三方API" else "本地Jsoup"
        log.debug("[parseBookmark] 选择解析模式: $mode, bookmarkId=${bookmark.id}")
        return if (projectConfig.useThirdPartyParser) parseByApi(bookmark) else parseLocally(bookmark)
    }

    /**
     * 本地解析（Jsoup）：抓取网页元信息 + favicon base64 + LOGO/OG 存 OSS
     */
    private fun parseLocally(bookmark: BookmarkEntity): BookmarkEntity {
        log.debug("[parseLocally] 开始本地解析(Jsoup): bookmarkId=${bookmark.id}, rawUrl=${bookmark.rawUrl}")
        val wrapper = runCatching { WebsiteParser.parse(bookmark.rawUrl) }.getOrElse {
            val status = if (it.message?.contains("403") == true) ParseStatusEnum.BLOCKED else ParseStatusEnum.CLOSED
            log.debug("[parseLocally] 页面抓取失败: bookmarkId=${bookmark.id}, status=$status, err=${it.message}")
            bookmark.apply {
                parseStatus = status
                isActivity = false
                parseErrMsg = it.message
                baseMapper.insertOrUpdate(this)
                it.printStackTrace()
            }
            return bookmark
        }
        log.debug("[parseLocally] 页面抓取成功, 开始填充元信息: bookmarkId=${bookmark.id}, title=${wrapper.title}")
        bookmark.successInit(wrapper)
        inferAndSetAppName(bookmark)
        baseMapper.insertOrUpdate(bookmark)
        log.debug("[parseLocally] 元信息已保存, 开始存储 LOGO 到 OSS: bookmarkId=${bookmark.id}, iconCount=${wrapper.distinctIcons?.size ?: 0}")
        saveLogoToOss(wrapper.distinctIcons ?: emptyList(), bookmark)
        log.debug("[parseLocally] 本地解析全部完成: bookmarkId=${bookmark.id}, parseStatus=${bookmark.parseStatus}, appName=${bookmark.appName}")
        return bookmark
    }

    /**
     * 第三方 API 解析（iframely）：通过 API 获取元信息 + favicon base64 + LOGO/OG 存 OSS
     */
    private fun parseByApi(bookmark: BookmarkEntity): BookmarkEntity {
        log.debug("[parseByApi] 开始第三方API解析(iframely): bookmarkId=${bookmark.id}, rawUrl=${bookmark.rawUrl}")
        return runCatching { apiService.queryWebsiteInfo(bookmark.rawUrl) }.fold(
            onSuccess = { vo ->
                val icons = vo.toManifestIcons()
                log.debug("[parseByApi] API 返回成功: bookmarkId=${bookmark.id}, title=${vo.meta?.title}, iconCount=${icons.size}")
                // 填充基础信息 + iconBase64 + DeepSeek 简称推断，保存一次
                vo.entity(bookmark).also {
                    it.iconBase64 = FileUtils.icoBase64(icons, it.rawUrl)
                    inferAndSetAppName(it)
                    baseMapper.insertOrUpdate(it)
                    log.debug("[parseByApi] 元信息已保存: bookmarkId=${it.id}, appName=${it.appName}, parseStatus=${it.parseStatus}")
                }
                log.debug("[parseByApi] 开始存储 LOGO 到 OSS: bookmarkId=${bookmark.id}, iconCount=${icons.size}")
                saveLogoToOss(icons, bookmark)
                log.debug("[parseByApi] 第三方API解析全部完成: bookmarkId=${bookmark.id}")
                bookmark
            },
            onFailure = { e ->
                log.debug("[parseByApi] API 调用失败: bookmarkId=${bookmark.id}, err=${e.message}")
                bookmark.apply {
                    isActivity = false
                    parseStatus = ParseStatusEnum.CLOSED
                    parseErrMsg = e.message
                    updateTime = LocalDateTime.now()
                    baseMapper.insertOrUpdate(this)
                }
            }
        )
    }

    /**
     * 将网站 LOGO/OG 图片存入 OSS，并更新书签的最大 LOGO 尺寸
     */
    private fun saveLogoToOss(icons: List<ManifestIcon>, bookmark: BookmarkEntity) {
        if (icons.isEmpty()) {
            log.debug("[saveLogoToOss] icon 列表为空，跳过 OSS 上传: bookmarkId=${bookmark.id}")
            return
        }
        log.debug("[saveLogoToOss] 开始上传 LOGO/OG 到 OSS: bookmarkId=${bookmark.id}, iconCount=${icons.size}")
        OssUtils.restoreWebsiteLogoAndOg(icons, bookmark.id)
            ?.also {
                websiteLogoMapper.insertOrUpdate(it)
                log.debug("[saveLogoToOss] OSS 上传完成，已更新 website_logo: bookmarkId=${bookmark.id}, width=${it.width}")
            }
            ?.also { bookmark.setMaximalLogoSize(it.width) }
    }

    // ────── 私有工具 ──────

    /** 通过 DeepSeek 推断书签简称，有结果则覆盖 appName，失败静默忽略 */
    private fun inferAndSetAppName(bookmark: BookmarkEntity) {
        val title = bookmark.title ?: run {
            log.debug("[inferAndSetAppName] title 为空，跳过 appName 推断: bookmarkId=${bookmark.id}")
            return
        }
        log.debug("[inferAndSetAppName] 调用 DeepSeek 推断 appName: bookmarkId=${bookmark.id}, title=$title")
        apiService.inferAppName(title)?.takeIf { it.isNotBlank() }
            ?.also {
                bookmark.appName = it
                log.debug("[inferAndSetAppName] appName 推断成功: bookmarkId=${bookmark.id}, appName=$it")
            } ?: log.debug("[inferAndSetAppName] appName 推断结果为空，保持原值: bookmarkId=${bookmark.id}")
    }

    private fun getByHost(urlHost: String): BookmarkEntity? = ktQuery().eq(BookmarkEntity::urlHost, urlHost).one()

    private fun findById(bookmarkId: String): BookmarkEntity =
        requireNotNull(ktQuery().eq(BookmarkEntity::id, bookmarkId).one())

    private fun BookmarkEntity.setMaximalLogoSize(maximal: Int) {
        this.maximalLogoSize = maximal
        this.isActivity = true
        this.updateTime = LocalDateTime.now()
        baseMapper.insertOrUpdate(this)
    }
}
