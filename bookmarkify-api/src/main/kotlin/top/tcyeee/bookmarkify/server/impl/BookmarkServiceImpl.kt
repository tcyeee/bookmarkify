package top.tcyeee.bookmarkify.server.impl

import cn.hutool.core.date.LocalDateTimeUtil
import com.baomidou.mybatisplus.core.metadata.IPage
import com.baomidou.mybatisplus.extension.plugins.pagination.Page
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl
import org.springframework.context.ApplicationEventPublisher
import org.springframework.dao.DuplicateKeyException
import org.springframework.scheduling.annotation.Async
import org.springframework.stereotype.Service
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionTemplate
import org.springframework.web.multipart.MultipartFile
import top.tcyeee.bookmarkify.config.async.AsyncConfig
import top.tcyeee.bookmarkify.config.log
import top.tcyeee.bookmarkify.config.cache.RedisType
import top.tcyeee.bookmarkify.config.entity.ProjectConfig
import top.tcyeee.bookmarkify.config.exception.CommonException
import top.tcyeee.bookmarkify.config.exception.ErrorType
import top.tcyeee.bookmarkify.entity.*
import top.tcyeee.bookmarkify.entity.dto.BookmarkUrlWrapper
import top.tcyeee.bookmarkify.entity.dto.ManifestIcon
import top.tcyeee.bookmarkify.entity.dto.ScrapeResponse
import top.tcyeee.bookmarkify.entity.dto.SimilarIngestUpdate
import top.tcyeee.bookmarkify.entity.dto.SimilarSite
import top.tcyeee.bookmarkify.entity.entity.*
import top.tcyeee.bookmarkify.entity.enums.BookmarkLinkType
import top.tcyeee.bookmarkify.entity.enums.ParseStatusEnum
import top.tcyeee.bookmarkify.entity.entity.BookmarkPingLogEntity
import top.tcyeee.bookmarkify.mapper.*
import top.tcyeee.bookmarkify.server.IApiService
import top.tcyeee.bookmarkify.server.IBookmarkService
import top.tcyeee.bookmarkify.config.event.BookmarkParseAndNoticeEvent
import top.tcyeee.bookmarkify.config.event.BookmarkParseAndResetUserItemEvent
import top.tcyeee.bookmarkify.config.event.BookmarkParseEvent
import top.tcyeee.bookmarkify.server.IBookmarkCategoryService
import top.tcyeee.bookmarkify.server.IBookmarkUserLinkService
import top.tcyeee.bookmarkify.server.IWebsiteLogoService
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
    private val eventPublisher: ApplicationEventPublisher,
    private val apiService: IApiService,
    private val layoutNodeMapper: UserLayoutNodeMapper,
    private val websiteLogoService: IWebsiteLogoService,
    private val bookmarkUserLinkService: IBookmarkUserLinkService,
    private val bookmarkFunctionMapper: BookmarkFunctionMapper,
    private val bookmarkCategoryService: IBookmarkCategoryService,
    private val pingLogMapper: BookmarkPingLogMapper,
    transactionManager: PlatformTransactionManager,
) : IBookmarkService, ServiceImpl<BookmarkMapper, BookmarkEntity>() {

    // 用于在「网络抓取完成之后」把多条 DB 写入包进一个短事务，
    // 避免直接在方法上加 @Transactional 而在整个抓取期间长时间占用数据库连接。
    private val txTemplate = TransactionTemplate(transactionManager)

    // 找到全部的系统默认书签,存储用户桌面布局和自定义书签
    override fun setDefaultBookmark(uid: String) =
        findListByUrl(projectConfig.defaultBookmarkify)
            .map { bookmark ->
                UserLayoutNodeEntity(uid = uid).let { node -> Pair(node, BookmarkUserLink(bookmark, node.id, uid)) }
            }.also { pair ->
                layoutNodeMapper.insert(pair.map { it.first })
                bookmarkUserLinkMapper.insert(pair.map { it.second })
            }.run {}

    override fun findByHost(host: String): BookmarkEntity? = ktQuery().eq(BookmarkEntity::urlHost, host).one()

    override fun findListByUrl(urls: List<String>): List<BookmarkEntity> =
        urls.mapNotNull { runCatching { WebsiteParser.urlWrapper(it) }.getOrNull() }
            .mapNotNull { getByUrl(it.urlHost, it.urlPath ?: "/") }

    @Transactional
    override fun setDefaultFunction(uid: String) =
        UserLayoutNodeEntity(uid = uid, type = NodeTypeEnum.FUNCTION).also { layoutNodeMapper.insert(it) }
            .let { BookmarkFunctionEntity(it, uid) }.also { bookmarkFunctionMapper.insert(it) }.run {}

    override fun search(name: String): List<BookmarkSearchVO> {
        val list = ktQuery().eq(BookmarkEntity::isActivity, true).like(BookmarkEntity::appName, name).or()
            .like(BookmarkEntity::title, name).or().like(BookmarkEntity::description, name).or()
            .like(BookmarkEntity::urlHost, name).last("limit 5").list()
        // 小图标来自 website_logo，批量取后组装
        val logoMap = logosByBookmarkIds(list.map { it.id })
        return list.map { BookmarkSearchVO(it, logoMap[it.id]) }
    }

    override fun linkOne(bookmarkId: String, uid: String): UserLayoutNodeVO {
        val nodeEntity = UserLayoutNodeEntity(uid = uid).also { layoutNodeMapper.insert(it) }
        val userLink = this.findById(bookmarkId).let { BookmarkUserLink(it, nodeEntity.id, uid) }
            .also { bookmarkUserLinkMapper.insert(it) }
        return bookmarkUserLinkMapper.findShowById(userLink.id).initLogo().let { UserLayoutNodeVO(nodeEntity, it) }
    }

    override fun allOfMyBookmark(uid: String, params: AllOfMyBookmarkParams): IPage<BookmarkShow> {
        // "重复书签"/"失效书签" 筛选：先在用户自己的书签范围内算出候选 bookmarkId 集合，
        // 再作为 IN 条件叠加到分页查询上；两者同时开启时取交集。
        val duplicateIds = if (params.duplicatesOnly) bookmarkUserLinkService.duplicateBookmarkIds(uid) else null
        val invalidIds = if (params.invalidOnly) {
            val mine = bookmarkUserLinkService.bookmarkIdsByUid(uid)
            if (mine.isEmpty()) emptySet() else ktQuery().`in`(BookmarkEntity::id, mine).eq(BookmarkEntity::isActivity, false).list().map { it.id }.toSet()
        } else null
        val restrictIds: Set<String>? = when {
            duplicateIds != null && invalidIds != null -> duplicateIds intersect invalidIds
            duplicateIds != null -> duplicateIds
            invalidIds != null -> invalidIds
            else -> null
        }
        // 候选集合已知为空：直接返回空页，避免下面拼出一条恒假的 IN () 查询
        if (restrictIds != null && restrictIds.isEmpty()) return Page(params.currentPage.toLong(), params.pageSize.toLong(), 0)

        val result = bookmarkUserLinkMapper.selectPage(params.toPage(), params.toWrapper(restrictIds))
        val bookmarkIds: List<String> = result.records.mapNotNull { it.bookmarkId }
        val bookmarkEntityMap =
            if (bookmarkIds.isEmpty()) emptyMap() else baseMapper.selectByIds(bookmarkIds).associateBy { it.id }
        val logoMap = logosByBookmarkIds(bookmarkIds)

        // 所属文件夹：布局节点(layoutNodeId) -> 父节点(parentId) -> 父节点名称，两次批量查询避免 N+1
        val layoutNodeIds = result.records.map { it.layoutNodeId }
        val layoutNodeMap = if (layoutNodeIds.isEmpty()) emptyMap() else layoutNodeMapper.selectByIds(layoutNodeIds).associateBy { it.id }
        val folderIds = layoutNodeMap.values.mapNotNull { it.parentId }.distinct()
        val folderMap = if (folderIds.isEmpty()) emptyMap() else layoutNodeMapper.selectByIds(folderIds).associateBy { it.id }

        return result.convert {
            val folder = layoutNodeMap[it.layoutNodeId]?.parentId?.let { fid -> folderMap[fid] }
            BookmarkShow(it, bookmarkEntityMap[it.bookmarkId], logoMap[it.bookmarkId]).initLogo().apply {
                folderId = folder?.id
                folderName = folder?.name
            }
        }
    }

    override fun previewImport(file: MultipartFile, uid: String): BookmarkImportPreviewVO {
        val existingUrls: Set<String> = bookmarkUserLinkService.urlsByUid(uid)
        val structures = ChromeBookmarkParser.trim(file)
        assertImportSizeWithinLimit(structures)
        val items = structures.flatMap { structure ->
            structure.bookmarks.map { raw ->
                BookmarkImportItemVO(
                    title = raw.title,
                    url = raw.url,
                    folder = structure.folderName.takeIf { it != "ROOT" },
                    isDuplicate = raw.url in existingUrls,
                )
            }
        }
        return BookmarkImportPreviewVO(
            total = items.size,
            duplicateCount = items.count { it.isDuplicate },
            items = items,
        )
    }

    /**
     * 数据读取完成以后,立即返回占位信息
     * 等待书签解析完成以后,通过WebSocket逐个向前端返回解析完成后的数据
     */
    override fun importBookmarkFile(
        file: MultipartFile,
        uid: String,
        skipUrls: Set<String>,
    ): List<UserLayoutNodeVO> {
        val structures = ChromeBookmarkParser.trim(file)
        assertImportSizeWithinLimit(structures)

        data class FolderSlice(val folderNode: UserLayoutNodeEntity?, val items: List<Pair<ChromeBookmarkRawData, UserLayoutNodeEntity>>)

        val slices: List<FolderSlice> = structures.mapNotNull { s ->
            val kept = s.bookmarks.filter { it.url !in skipUrls }
            when (kept.size) {
                0    -> null
                1    -> {
                    val node = UserLayoutNodeEntity(uid = uid, type = NodeTypeEnum.BOOKMARK_LOADING)
                    FolderSlice(null, listOf(Pair(kept[0], node)))
                }
                else -> if (s.folderName == "ROOT") {
                    val nodes = kept.map { raw -> Pair(raw, UserLayoutNodeEntity(uid = uid, type = NodeTypeEnum.BOOKMARK_LOADING)) }
                    FolderSlice(null, nodes)
                } else {
                    val folder = UserLayoutNodeEntity(uid, s)
                    val nodes = kept.map { raw -> Pair(raw, UserLayoutNodeEntity(uid = uid, type = NodeTypeEnum.BOOKMARK_LOADING, parentId = folder.id)) }
                    FolderSlice(folder, nodes)
                }
            }
        }

        val allBookmarkNodes: List<Pair<ChromeBookmarkRawData, UserLayoutNodeEntity>> = slices.flatMap { it.items }
        val allLinks: List<BookmarkUserLink> = allBookmarkNodes.map { (raw, node) -> BookmarkUserLink(uid, node.id, raw) }

        txTemplate.execute {
            val folderNodes = slices.mapNotNull { it.folderNode }
            if (folderNodes.isNotEmpty()) layoutNodeMapper.insert(folderNodes)
            if (allBookmarkNodes.isNotEmpty()) layoutNodeMapper.insert(allBookmarkNodes.map { it.second })
            if (allLinks.isNotEmpty()) bookmarkUserLinkMapper.insert(allLinks)
        }

        // 事务提交后发布解析事件，避免回滚后仍触发解析
        allLinks.zip(allBookmarkNodes.map { it.second }).forEach { (link, node) ->
            eventPublisher.publishEvent(BookmarkParseAndResetUserItemEvent(uid, link.urlFull, link.id, node.id))
        }

        // 构造返回的 VO 列表（文件夹节点 + LOADING 书签节点）
        val result = mutableListOf<UserLayoutNodeVO>()
        slices.forEach { slice ->
            slice.folderNode?.let { f ->
                result.add(UserLayoutNodeVO(id = f.id, type = NodeTypeEnum.BOOKMARK_DIR, name = f.name))
            }
            slice.items.forEach { (raw, node) ->
                result.add(UserLayoutNodeVO(id = node.id, type = NodeTypeEnum.BOOKMARK_LOADING, name = raw.title, parentId = node.parentId))
            }
        }
        return result
    }

    override fun checkAll() =
        // F-08: Limit each scheduler tick to CHECKALL_BATCH_SIZE records.
        // The previous .lt(verifyFlag, false) was a no-op on PostgreSQL booleans, so in
        // production there may be a large backlog of unverified bookmarks. Without a limit,
        // the first post-fix run would flood the parse executor and delay newly-added bookmark parsing.
        ktQuery()
            .lt(BookmarkEntity::updateTime, LocalDateTimeUtil.offset(LocalDateTime.now(), -1, ChronoUnit.DAYS))
            .eq(BookmarkEntity::verifyFlag, false)
            // 最旧的优先处理，配合 LIMIT 保证积压记录会被逐批消费，不会被新记录饿死。
            .orderByAsc(BookmarkEntity::updateTime)
            .last("LIMIT $CHECKALL_BATCH_SIZE")
            .list()
            .forEach { eventPublisher.publishEvent(BookmarkParseEvent(it.id)) }

    override fun retryClosedBookmarks() {
        val candidates = ktQuery()
            .eq(BookmarkEntity::parseStatus, ParseStatusEnum.CLOSED)
            .eq(BookmarkEntity::verifyFlag, false)
            .lt(BookmarkEntity::updateTime, LocalDateTimeUtil.offset(LocalDateTime.now(), -1, ChronoUnit.DAYS))
            .orderByAsc(BookmarkEntity::updateTime)
            .last("LIMIT $RETRY_CLOSED_BATCH_SIZE")
            .list()
            // 非域名类型(本地/IP/其他)不抓取，也不应对其发起存活 ping
            .filter { WebsiteParser.classifyLinkType(it.urlHost) == BookmarkLinkType.DOMAIN }

        log.debug("[retryClosedBookmarks] 本次待重试书签数: ${candidates.size}")
        candidates.forEach { bookmark ->
            val alive = apiService.pingWebsite(bookmark.rawUrl)
            val triggeredParse = alive && !bookmark.verifyFlag
            pingLogMapper.insert(
                BookmarkPingLogEntity(
                    bookmarkId = bookmark.id,
                    urlHost = bookmark.urlHost,
                    alive = alive,
                    triggeredParse = triggeredParse,
                )
            )
            if (triggeredParse) {
                log.debug("[retryClosedBookmarks] ping 成功，触发重新解析: bookmarkId=${bookmark.id}, urlHost=${bookmark.urlHost}")
                eventPublisher.publishEvent(BookmarkParseEvent(bookmark.id))
            } else {
                log.debug("[retryClosedBookmarks] ping 失败，更新 updateTime: bookmarkId=${bookmark.id}, urlHost=${bookmark.urlHost}")
                // 更新 updateTime，使该记录在下次调度周期之前不会被重复选中
                ktUpdate().eq(BookmarkEntity::id, bookmark.id)
                    .set(BookmarkEntity::updateTime, LocalDateTime.now())
                    .update()
            }
        }
    }

    override fun livenessCheckStaleBookmarks() {
        // 范围覆盖全部书签（含已手动认证的），因为 checkAll/retryClosedBookmarks 都只处理 verifyFlag=false，
        // 已认证书签此前从未被自动复查过。排除 LOADING：尚未解析完成的记录由 checkAll 每5分钟负责兜底。
        val candidates = ktQuery()
            .ne(BookmarkEntity::parseStatus, ParseStatusEnum.LOADING)
            .lt(BookmarkEntity::updateTime, LocalDateTimeUtil.offset(LocalDateTime.now(), -7, ChronoUnit.DAYS))
            .orderByAsc(BookmarkEntity::updateTime)
            .last("LIMIT $LIVENESS_CHECK_BATCH_SIZE")
            .list()
            // 非域名类型(本地/IP/其他)不抓取，也不应对其发起存活 ping
            .filter { WebsiteParser.classifyLinkType(it.urlHost) == BookmarkLinkType.DOMAIN }

        log.debug("[livenessCheckStaleBookmarks] 本次待检查书签数: ${candidates.size}")
        candidates.forEach { bookmark ->
            val alive = apiService.pingWebsite(bookmark.rawUrl)
            // parseBookmark() 对 verifyFlag=true 的书签直接短路返回，不会更新 updateTime，
            // 发布重新解析事件对已认证书签是无效操作，会导致该记录每小时被重复选中。
            val triggeredParse = alive && !bookmark.isActivity && !bookmark.verifyFlag
            pingLogMapper.insert(
                BookmarkPingLogEntity(
                    bookmarkId = bookmark.id,
                    urlHost = bookmark.urlHost,
                    alive = alive,
                    triggeredParse = triggeredParse,
                )
            )
            when {
                triggeredParse -> {
                    log.debug("[livenessCheckStaleBookmarks] ping 成功且此前不活跃，触发重新解析: bookmarkId=${bookmark.id}, urlHost=${bookmark.urlHost}")
                    eventPublisher.publishEvent(BookmarkParseEvent(bookmark.id))
                }
                alive -> {
                    log.debug("[livenessCheckStaleBookmarks] ping 成功，仅刷新 updateTime: bookmarkId=${bookmark.id}, urlHost=${bookmark.urlHost}")
                    ktUpdate().eq(BookmarkEntity::id, bookmark.id)
                        .set(BookmarkEntity::updateTime, LocalDateTime.now())
                        .update()
                }
                else -> {
                    log.debug("[livenessCheckStaleBookmarks] ping 失败，标记 CLOSED: bookmarkId=${bookmark.id}, urlHost=${bookmark.urlHost}")
                    ktUpdate().eq(BookmarkEntity::id, bookmark.id)
                        .set(BookmarkEntity::updateTime, LocalDateTime.now())
                        .set(BookmarkEntity::parseStatus, ParseStatusEnum.CLOSED)
                        .set(BookmarkEntity::isActivity, false)
                        .update()
                }
            }
        }
    }

    override fun addOne(url: String, uid: String): UserLayoutNodeVO {
        log.debug("[addOne] uid=$uid 开始添加书签, rawUrl=$url")

        // 1. 标准化 URL，解析出 host、完整地址等结构化信息
        val bookmarkUrl: BookmarkUrlWrapper = WebsiteParser.urlWrapper(url)
        log.debug("[addOne] Step1 URL 标准化完成: urlHost=${bookmarkUrl.urlHost}, urlFull=${bookmarkUrl.urlFull}")

        // 2. 按 (urlHost, urlPath) 获取或创建 canonical 书签记录。
        //    多个用户添加同一个页面时共享同一条 bookmark 记录（一对多），避免重复抓取同一页面；
        //    不同路径（即使同域名）各自独立记录，因为路径不同即页面不同，标题/简称/图标不能共用。
        //    getOrCreateByUrl 容忍并发插入同一 (host, path)（依赖 (url_host, url_path) 联合唯一约束）。
        val bookmark = getOrCreateByUrl(bookmarkUrl)
        log.debug("[addOne] Step2 书签记录就绪: bookmarkId=${bookmark.id}, urlHost=${bookmark.urlHost}, parseStatus=${bookmark.parseStatus}")

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
        //    ↳ 需要解析 → 立即返回 loading 占位 VO，同时发布异步解析事件。
        //                  解析完成后由 parseAndNotice 通过 WebSocket 将最终结果推送到客户端。
        if (bookmark.checkFlag()) {
            log.debug("[addOne] Step5 书签需要解析，返回 LOADING 占位，已发布异步解析事件: bookmarkId=${bookmark.id}, userLinkId=${userLink.id}, nodeId=${nodeEntity.id}")
            return nodeEntity.loadingVO(bookmark.urlHost)
                .also { eventPublisher.publishEvent(BookmarkParseAndNoticeEvent(uid, bookmark.id, userLink.id, nodeEntity.id)) }
        }

        // 6. 书签在有效期内（1 天内已解析），无需重新抓取。
        //    将节点类型由 BOOKMARK_LOADING 更新为 BOOKMARK 并持久化，然后直接返回完整数据。
        log.debug("[addOne] Step6 书签在有效期内，无需重新解析，直接返回完整数据: bookmarkId=${bookmark.id}, nodeId=${nodeEntity.id}")
        nodeEntity.type = NodeTypeEnum.BOOKMARK
        layoutNodeMapper.updateById(nodeEntity)
        return bookmarkUserLinkMapper.findShowById(userLink.id).let { UserLayoutNodeVO(nodeEntity, it) }
    }

    override fun adminListAll(params: BookmarkSearchParams): IPage<BookmarkAdminVO> {
        val entityPage = baseMapper.selectPage(params.toPage(), params.toWrapper())
        // 批量取每个书签的图标记录(website_logo)，与书签实体一起组装 VO
        val logoMap = logosByBookmarkIds(entityPage.records.map { it.id })
        val page = entityPage.convert { BookmarkAdminVO(it, logoMap[it.id]) }
        // 分类回填失败(如分类表缺失/查询异常)不应拖垮整个书签列表，降级为空分类
        runCatching {
            val catMap = bookmarkCategoryService.categoriesOf(page.records.map { it.id })
            page.records.forEach { vo ->
                vo.categories = catMap[vo.id].orEmpty()
                    .map { CategoryVO(it.id, it.slug, it.name, it.color) }
            }
        }.onFailure { log.warn("[adminListAll] 分类回填失败(忽略): ${it.message}") }
        return page
    }

    override fun adminUpdateIcon(bookmarkId: String, params: BookmarkIconUpdateParams) {
        baseMapper.selectById(bookmarkId) ?: throw CommonException(ErrorType.E102)
        // appName 仍属于 bookmark 主表
        ktUpdate().eq(BookmarkEntity::id, bookmarkId).set(BookmarkEntity::appName, params.appName).update()
        // 图标显示设置(内边距/背景色/高清开关)落到 website_logo（与书签 1:1，upsert）
        val logo = logoOf(bookmarkId).apply {
            iconPadding = params.iconPadding
            iconBgColor = params.iconBgColor
            useHdLogo = params.useHdLogo
            updateTime = LocalDateTime.now()
        }
        websiteLogoService.saveOrUpdate(logo)
    }

    override fun adminRefetch(bookmarkId: String): BookmarkRefetchVO {
        val bookmark = baseMapper.selectById(bookmarkId) ?: throw CommonException(ErrorType.E102)
        log.debug("[adminRefetch] 管理员重新获取书签元信息: bookmarkId=$bookmarkId, rawUrl=${bookmark.rawUrl}")
        // 仅预览，不落库：重新抓取一次，拿到新的标题与小图标
        val vo = apiService.queryWebsiteInfo(bookmark.rawUrl)
        val iconBase64 = vo.favicon?.takeIf { it.isNotBlank() }
            ?: ChromeBookmarkParser.icoBase64(vo.toManifestIcons(bookmark.rawUrl), bookmark.rawUrl)
        // 预览与应用之间用 Redis 暂存完整抓取结果，确保「所见即所存」且避免应用时再抓一次造成漂移
        RedisUtils.set(RedisType.BOOKMARK_REFETCH, bookmarkId, vo)
        // 高清 LOGO：scrapper 与 API 共用同一私有读 OSS 桶，vo.logo 是未签名地址(浏览器直连会 403)，
        // 用 API 的 OSS 客户端换成限时签名地址(同桶同密钥，签名有效)。未抓到/签名失败则为 null，交由前端说明。
        val logoUrl = vo.logo?.takeIf { it.isNotBlank() }
            ?.let { runCatching { OssUtils.resizeAndSignImg(it, 0, 0) }.getOrNull() }
        log.debug("[adminRefetch] 重新获取完成并已暂存: bookmarkId=$bookmarkId, newTitle=${vo.title}, hasLogo=${logoUrl != null}")
        return BookmarkRefetchVO(title = vo.title, iconBase64 = iconBase64, logoUrl = logoUrl)
    }

    override fun adminCheckLiveness(bookmarkId: String): BookmarkLivenessVO {
        val bookmark = baseMapper.selectById(bookmarkId) ?: throw CommonException(ErrorType.E102)
        log.debug("[adminCheckLiveness] 管理员触发书签活性检测: bookmarkId=$bookmarkId, rawUrl=${bookmark.rawUrl}")
        return runCatching { apiService.queryWebsiteInfo(bookmark.rawUrl) }.fold(
            onSuccess = { vo ->
                bookmark.apply {
                    isActivity = true
                    parseStatus = ParseStatusEnum.SUCCESS
                    parseErrMsg = null
                    updateTime = LocalDateTime.now()
                }
                baseMapper.updateById(bookmark)
                log.debug("[adminCheckLiveness] 检测成功: bookmarkId=$bookmarkId, source=${vo.source}")
                BookmarkLivenessVO(
                    success = true,
                    title = vo.title,
                    description = vo.description,
                    image = vo.image,
                    favicon = vo.favicon,
                    logo = vo.logo,
                    source = vo.source,
                    cached = vo.cached,
                    screenshot = vo.screenshot,
                    isActivity = true,
                    parseStatus = ParseStatusEnum.SUCCESS,
                )
            },
            onFailure = { e ->
                bookmark.apply {
                    isActivity = false
                    parseStatus = ParseStatusEnum.CLOSED
                    parseErrMsg = e.message
                    updateTime = LocalDateTime.now()
                }
                baseMapper.updateById(bookmark)
                log.debug("[adminCheckLiveness] 检测失败: bookmarkId=$bookmarkId, err=${e.message}")
                BookmarkLivenessVO(
                    success = false,
                    errorMsg = e.message,
                    isActivity = false,
                    parseStatus = ParseStatusEnum.CLOSED,
                )
            },
        )
    }

    override fun adminApplyRefetch(bookmarkId: String, params: BookmarkRefetchApplyParams): BookmarkAdminVO {
        val bookmark = baseMapper.selectById(bookmarkId) ?: throw CommonException(ErrorType.E102)
        val vo = RedisUtils.get<ScrapeResponse>(RedisType.BOOKMARK_REFETCH, bookmarkId)
            ?: throw CommonException(ErrorType.E112)
        log.debug("[adminApplyRefetch] 应用重新获取结果: bookmarkId=$bookmarkId, useNewTitle=${params.useNewTitle}, useNewIcon=${params.useNewIcon}, useNewLogo=${params.useNewLogo}")

        if (params.useNewTitle) bookmark.title = vo.title
        // 小图标与大图标(高清 LOGO)分开应用到图标记录(website_logo)，可单独采用其中之一
        val logo = logoOf(bookmarkId)
        val iconOrLogoChanged = params.useNewIcon || params.useNewLogo
        if (iconOrLogoChanged) {
            val icons = vo.toManifestIcons(bookmark.rawUrl)
            if (params.useNewIcon) {
                logo.iconBase64 = vo.favicon?.takeIf { it.isNotBlank() }
                    ?: ChromeBookmarkParser.icoBase64(icons, bookmark.rawUrl)
            }
            // 采用新大图标时，重抓高清 LOGO/OG 上传 OSS，并把元数据写回图标记录
            if (params.useNewLogo) applyHdLogo(logo, icons, bookmarkId)
            logo.updateTime = LocalDateTime.now()
            websiteLogoService.saveOrUpdate(logo)
        }
        bookmark.updateTime = LocalDateTime.now()
        baseMapper.insertOrUpdate(bookmark)
        RedisUtils.del(RedisType.BOOKMARK_REFETCH, bookmarkId)
        log.debug("[adminApplyRefetch] 应用完成: bookmarkId=$bookmarkId, title=${bookmark.title}")
        return BookmarkAdminVO(bookmark, logo)
    }

    override fun adminUpdateCategories(bookmarkId: String, categoryIds: List<String>): List<CategoryVO> {
        baseMapper.selectById(bookmarkId) ?: throw CommonException(ErrorType.E102)
        bookmarkCategoryService.replaceLinks(bookmarkId, categoryIds, "MANUAL")
        return loadCategoryVOs(bookmarkId)
    }

    override fun adminRecategorize(bookmarkId: String): List<CategoryVO> {
        val bookmark = baseMapper.selectById(bookmarkId) ?: throw CommonException(ErrorType.E102)
        bookmarkCategoryService.categorize(bookmark)
        return loadCategoryVOs(bookmarkId)
    }

    override fun adminSimilarSites(bookmarkId: String): List<SimilarSite> {
        val bookmark = baseMapper.selectById(bookmarkId) ?: throw CommonException(ErrorType.E102)
        val sites = apiService.inferSimilarSites(bookmark.title, bookmark.description, bookmark.urlHost)
        if (sites.isEmpty()) return sites
        // 把推荐域名按与入库一致的方式归一化为 urlHost，再批量比对本地是否已收录
        val hosts = sites.map { runCatching { WebsiteParser.urlWrapper("https://${it.domain}").urlHost }.getOrNull() }
        val existingHosts = hosts.filterNotNull().distinct()
            .takeIf { it.isNotEmpty() }
            ?.let { findListByHost(it).map { b -> b.urlHost }.toSet() }
            ?: emptySet()
        sites.forEachIndexed { i, s -> s.exists = hosts[i]?.let { it in existingHosts } ?: false }
        return sites
    }

    @Async(AsyncConfig.BOOKMARK_PARSE_EXECUTOR)
    override fun adminIngestSimilar(adminUid: String, domains: List<String>) {
        // 异步顺序收录（站点不多，顺序处理即可，避免并发打爆 scrapper）；逐站通过 WebSocket 回推状态。
        // 关弹窗后管理端会断开 WS，此处推送命中不到 session 即静默丢弃，无需感知前端是否还在。
        domains.distinct().forEach { domain ->
            val status = runCatching { ingestOneSimilar(domain) }
                .getOrElse {
                    log.warn("[adminIngestSimilar] 收录异常 domain=$domain: ${it.message}")
                    "SKIPPED"
                }
            SocketUtils.similarIngestUpdate(adminUid, SimilarIngestUpdate(domain, status))
        }
    }

    /** 收录单个相似站点：本地已有→EXISTS；抓取失败(不可达=幻觉/失效)→删除记录并 SKIPPED；抓到(SUCCESS/BLOCKED)→INGESTED。 */
    private fun ingestOneSimilar(domain: String): String {
        val wrapper = WebsiteParser.urlWrapper("https://${domain.trim().substringAfter("://")}")
        findByHost(wrapper.urlHost)?.let { return "EXISTS" }
        val bookmark = getOrCreateByUrl(wrapper)
        // 抓取可能抛异常（本地解析器）或落 CLOSED（scrapper 不可达）；统一以「最终落库状态」判定，
        // 抓到正文(SUCCESS/BLOCKED)才保留，其余一律删除——保证幻觉域名绝不留在库里。
        runCatching { parseBookmark(bookmark) }
            .onFailure { log.warn("[ingestOneSimilar] 解析异常 domain=$domain: ${it.message}") }
        val saved = baseMapper.selectById(bookmark.id)
        val ok = saved != null &&
            (saved.parseStatus == ParseStatusEnum.SUCCESS || saved.parseStatus == ParseStatusEnum.BLOCKED)
        return if (ok) {
            "INGESTED"
        } else {
            removeById(bookmark.id)
            "SKIPPED"
        }
    }

    private fun loadCategoryVOs(bookmarkId: String): List<CategoryVO> =
        bookmarkCategoryService.categoriesOf(listOf(bookmarkId))[bookmarkId].orEmpty()
            .map { CategoryVO(it.id, it.slug, it.name, it.color) }

    override fun adminGenerateAppName(bookmarkId: String): String? {
        val bookmark = baseMapper.selectById(bookmarkId) ?: throw CommonException(ErrorType.E102)
        val title = bookmark.title?.takeIf { it.isNotBlank() } ?: run {
            log.debug("[adminGenerateAppName] title 为空，跳过生成: bookmarkId=$bookmarkId")
            return null
        }
        log.debug("[adminGenerateAppName] 调用 DeepSeek 生成 appName: bookmarkId=$bookmarkId, title=$title")
        return apiService.inferAppName(title)?.takeIf { it.isNotBlank() }
    }

    override fun findListByHost(defaultBookmarkify: List<String>): List<BookmarkEntity> =
        ktQuery().`in`(BookmarkEntity::urlHost, defaultBookmarkify).list()

    // ────── 异步解析入口（由 BookmarkParseEventListener 调用）──────

    override fun parseAndSave(bookmarkId: String) {
        parseBookmark(baseMapper.selectById(bookmarkId))
    }

    /** 解析书签，然后保存到数据库，同时通知到用户 */
    override fun parseAndNotice(uid: String, bookmarkId: String, userLinkId: String, nodeId: String) {
        log.debug("[parseAndNotice-4] 开始书签解析: uid=$uid, bookmarkId=$bookmarkId, userLinkId=$userLinkId, nodeId=$nodeId")
        runCatching { parseBookmark(baseMapper.selectById(bookmarkId)) }.onFailure { ex ->
            // 解析链路中的未预期异常（而非「抓取失败」这类已内部兜底为 CLOSED 的正常业务失败）不能让节点
            // 永久停在 BOOKMARK_LOADING——此前这里的异常会一路冒泡到事件监听器，被其 runCatching 吞掉且
            // 不回写任何状态，用户端只会看到一个转不动的加载占位符。与 parseAndResetUserItem 保持一致，
            // 退化为与「ping 不通」一致的处理：落一条 CLOSED 记录，让节点照常收口而不是无限转圈。
            log.error("[parseAndNotice-4] 解析异常，标记为不可用: bookmarkId=$bookmarkId", ex)
            baseMapper.selectById(bookmarkId)?.apply {
                isActivity = false
                parseStatus = ParseStatusEnum.CLOSED
                parseErrMsg = "parse failed: ${ex.message}"
                updateTime = LocalDateTime.now()
                baseMapper.insertOrUpdate(this)
            }
        }
        log.debug("[parseAndNotice-4] 书签解析完成, 开始构建展示数据: userLinkId=$userLinkId")
        val bookmarkShow = bookmarkUserLinkMapper.findShowById(userLinkId).initLogo()
        log.debug("[parseAndNotice-4] 已查询 bookmarkShow, title=${bookmarkShow.title}, 开始更新布局节点类型: nodeId=$nodeId")
        val layoutEntity = layoutNodeMapper.selectById(nodeId).also {
            it.type = NodeTypeEnum.BOOKMARK
            layoutNodeMapper.updateById(it)
        }
        log.debug("[parseAndNotice-4] 布局节点已更新为 BOOKMARK, 准备推送 WebSocket: uid=$uid, nodeId=$nodeId")
        UserLayoutNodeVO(layoutEntity, bookmarkShow).also { SocketUtils.homeItemUpdate(uid, it) }
        log.debug("[parseAndNotice-4] WebSocket 推送完成: uid=$uid, nodeId=$nodeId")
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
    override fun parseAndResetUserItem(
        uid: String, rawUrl: String, userLinkId: String, layoutNodeId: String
    ) {
        val urlWrapper = WebsiteParser.urlWrapper(rawUrl)
        val entity = runCatching {
            getOrCreateByUrl(urlWrapper).also { if (it.parseStatus == ParseStatusEnum.LOADING) parseBookmark(it) }
        }.getOrElse { ex ->
            // 解析链路中的未预期异常不能让节点永久停在 BOOKMARK_LOADING——此前这里的异常会一路
            // 冒泡到事件监听器，被 runCatching 吞掉且不回写任何状态，用户端只会看到一个转不动的
            // 加载占位符。这里退化为与「ping 不通」一致的处理：落一条 CLOSED 记录，让节点照常收口。
            log.error("[parseAndResetUserItem] 解析异常，标记为不可用: urlHost=${urlWrapper.urlHost}, urlPath=${urlWrapper.urlPath}", ex)
            (getByUrl(urlWrapper.urlHost, urlWrapper.urlPath ?: "/") ?: BookmarkEntity(urlWrapper)).apply {
                isActivity = false
                parseStatus = ParseStatusEnum.CLOSED
                parseErrMsg = "parse failed: ${ex.message}"
                updateTime = LocalDateTime.now()
                baseMapper.insertOrUpdate(this)
            }
        }
        // 抓取已结束，下面两处写入（重绑 userLink + 更新节点类型）需原子提交，放进短事务。
        val layoutNode: UserLayoutNodeEntity = txTemplate.execute {
            bookmarkUserLinkService.resetBookmarkId(uid, userLinkId, entity.id)
            layoutNodeMapper.selectById(layoutNodeId)
                ?.apply { type = NodeTypeEnum.BOOKMARK }
                ?.also { layoutNodeMapper.updateById(it) }
                ?: throw CommonException(ErrorType.E999)
        }!!
        bookmarkUserLinkMapper.findShowById(userLinkId).initLogo()
            .let { UserLayoutNodeVO(layoutNode, it) }
            .also { SocketUtils.homeItemUpdate(uid, it) }
    }

    // ────── 公开接口（明确指定解析方式时调用）──────

    /** 通过 scrapper 远程解析书签，若书签已通过手动认证则直接返回 */
    override fun parseBookmarkByApi(bookmark: BookmarkEntity): BookmarkEntity {
        val existing = baseMapper.selectById(bookmark.id)
        if (existing != null && existing.verifyFlag) return existing
        return parseByApi(bookmark)
    }

    // ────── 私有解析层 ──────

    /**
     * 统一解析调度：检查 verifyFlag 后先 ping 确认网站存活，再根据配置选择解析方式。
     * ping 不通时直接标记 CLOSED 并跳过抓取（节省无效的 headless 开销）。
     */
    private fun parseBookmark(bookmark: BookmarkEntity): BookmarkEntity {
        log.debug("[parseBookmark] 开始调度解析: bookmarkId=${bookmark.id}, urlHost=${bookmark.urlHost}")
        val existing = baseMapper.selectById(bookmark.id)
        if (existing != null && existing.verifyFlag) {
            log.debug("[parseBookmark] 书签已手动认证(verifyFlag=true), 跳过解析直接返回: bookmarkId=${bookmark.id}")
            return existing
        }

        // 非域名类型(本地/IP/其他)不进行网络抓取：跳过 ping 与内容解析，直接标记为可用，
        // 前端会对这类书签展示统一的圆圈图标，不依赖抓取到的标题/图标。
        if (WebsiteParser.classifyLinkType(bookmark.urlHost) != BookmarkLinkType.DOMAIN) {
            log.debug("[parseBookmark] 非域名类型，跳过抓取: bookmarkId=${bookmark.id}, urlHost=${bookmark.urlHost}")
            return bookmark.apply {
                isActivity = true
                parseStatus = ParseStatusEnum.SUCCESS
                parseErrMsg = null
                updateTime = LocalDateTime.now()
                baseMapper.insertOrUpdate(this)
            }
        }

        // ping 前置：网站不存活则直接标 CLOSED，避免无效爬取
        val alive = apiService.pingWebsite(bookmark.rawUrl)
        log.debug("[parseBookmark] ping 结果: bookmarkId=${bookmark.id}, urlHost=${bookmark.urlHost}, alive=$alive")
        if (!alive) {
            log.debug("[parseBookmark] ping 失败，网站不可达，标记 CLOSED: bookmarkId=${bookmark.id}, urlHost=${bookmark.urlHost}")
            return bookmark.apply {
                isActivity = false
                parseStatus = ParseStatusEnum.CLOSED
                parseErrMsg = "ping failed: site unreachable"
                updateTime = LocalDateTime.now()
                baseMapper.insertOrUpdate(this)
            }
        }

        val mode = if (projectConfig.useThirdPartyParser) "远程scrapper" else "本地Jsoup"
        log.debug("[parseBookmark] ping 通过，选择解析模式: $mode, bookmarkId=${bookmark.id}")
        val parsed = if (projectConfig.useThirdPartyParser) parseByApi(bookmark) else parseLocally(bookmark)
        if (parsed.parseStatus == ParseStatusEnum.SUCCESS || parsed.parseStatus == ParseStatusEnum.BLOCKED) {
            bookmarkCategoryService.categorize(parsed)
        }
        return parsed
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
            }
            log.warn("[parseLocally] 页面抓取失败: bookmarkId=${bookmark.id}, status=$status, err=${it.message}")
            return bookmark
        }
        log.debug("[parseLocally] 页面抓取成功, 开始填充元信息: bookmarkId=${bookmark.id}, title=${wrapper.title}")
        val previousTitle = bookmark.title
        bookmark.successInit(wrapper)
        inferAndSetAppName(bookmark, previousTitle)
        baseMapper.insertOrUpdate(bookmark)
        log.debug("[parseLocally] 元信息已保存, 开始存储图标记录(website_logo): bookmarkId=${bookmark.id}, iconCount=${wrapper.distinctIcons?.size ?: 0}")
        // 小图标(favicon) + 高清 LOGO 一并 upsert 到 website_logo
        saveIconAndLogo(
            bookmark.id,
            ChromeBookmarkParser.icoBase64(wrapper.distinctIcons, bookmark.rawUrl),
            wrapper.distinctIcons ?: emptyList()
        )
        log.debug("[parseLocally] 本地解析全部完成: bookmarkId=${bookmark.id}, parseStatus=${bookmark.parseStatus}, appName=${bookmark.appName}")
        return bookmark
    }

    /**
     * 远程解析（scrapper）：通过自部署的 bookmarkify-scrapper 获取元信息 + favicon base64 + LOGO/OG 存 OSS
     */
    private fun parseByApi(bookmark: BookmarkEntity): BookmarkEntity {
        log.debug("[parseByApi] 开始远程解析(scrapper): bookmarkId=${bookmark.id}, rawUrl=${bookmark.rawUrl}")
        return runCatching { apiService.queryWebsiteInfo(bookmark.rawUrl) }.fold(
            onSuccess = { vo ->
                val icons = vo.toManifestIcons(bookmark.rawUrl)
                log.debug("[parseByApi] scrapper 返回成功: bookmarkId=${bookmark.id}, title=${vo.title}, source=${vo.source}, iconCount=${icons.size}")
                // 填充基础信息 + iconBase64 + DeepSeek 简称推断，保存一次
                val previousTitle = bookmark.title
                vo.entity(bookmark).also {
                    inferAndSetAppName(it, previousTitle)
                    baseMapper.insertOrUpdate(it)
                    log.debug("[parseByApi] 元信息已保存: bookmarkId=${it.id}, appName=${it.appName}, parseStatus=${it.parseStatus}")
                }
                log.debug("[parseByApi] 开始存储图标记录(website_logo): bookmarkId=${bookmark.id}, iconCount=${icons.size}")
                // scrapper 已返回 base64 data URL 的 favicon，优先直用；否则回退到本地下载
                val iconBase64 = vo.favicon?.takeIf { f -> f.isNotBlank() }
                    ?: ChromeBookmarkParser.icoBase64(icons, bookmark.rawUrl)
                saveIconAndLogo(bookmark.id, iconBase64, icons)
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

    // ────── 图标记录(website_logo, 与书签 1:1) ──────

    /** 取该书签的图标记录；不存在则返回一个未持久化的新实例（带默认显示设置）。 */
    private fun logoOf(bookmarkId: String): WebsiteLogoEntity =
        websiteLogoService.ktQuery()
            .eq(WebsiteLogoEntity::bookmarkId, bookmarkId)
            .orderByDesc(WebsiteLogoEntity::height)
            .last("limit 1")
            .one()
            ?: WebsiteLogoEntity(bookmarkId = bookmarkId)

    /** 批量取一组书签的图标记录，按 bookmarkId 聚合（每书签取高度最大的一条，兼容历史多行）。 */
    private fun logosByBookmarkIds(bookmarkIds: List<String>): Map<String, WebsiteLogoEntity> {
        if (bookmarkIds.isEmpty()) return emptyMap()
        return websiteLogoService.ktQuery().`in`(WebsiteLogoEntity::bookmarkId, bookmarkIds).list()
            .groupBy { it.bookmarkId }
            .mapValues { (_, list) -> list.maxByOrNull { it.height } ?: list.first() }
    }

    /**
     * 把小图标(favicon) + 高清 LOGO 一并 upsert 到该书签的图标记录(website_logo)。
     * iconBase64 总会写入；高清 LOGO/OG 上传成功才回写其地址与文件元数据。
     */
    private fun saveIconAndLogo(bookmarkId: String, iconBase64: String?, icons: List<ManifestIcon>) {
        val logo = logoOf(bookmarkId).apply { this.iconBase64 = iconBase64 }
        applyHdLogo(logo, icons, bookmarkId)
        logo.updateTime = LocalDateTime.now()
        websiteLogoService.saveOrUpdate(logo)
        log.debug("[saveIconAndLogo] 图标记录已保存: bookmarkId=$bookmarkId, hasIcon=${iconBase64 != null}, width=${logo.width}")
    }

    /** 把 LOGO/OG 上传 OSS，成功则把高清 LOGO 的地址与文件元数据写入图标记录（失败静默忽略，不影响小图标）。 */
    private fun applyHdLogo(logo: WebsiteLogoEntity, icons: List<ManifestIcon>, bookmarkId: String) {
        if (icons.isEmpty()) return
        runCatching { OssUtils.restoreWebsiteLogoAndOg(icons, bookmarkId) }
            .onSuccess { result ->
                result.logo?.let { meta ->
                    logo.logoUrl = result.logoUrl
                    logo.width = meta.width
                    logo.height = meta.height
                    logo.size = meta.size
                    logo.suffix = meta.suffix
                }
            }
            .onFailure { log.warn("[applyHdLogo] 高清 LOGO 上传失败: bookmarkId=$bookmarkId, err=${it.message}") }
    }

    // ────── 私有工具 ──────

    /**
     * 通过 DeepSeek 推断书签简称，有结果则覆盖 appName，失败静默忽略。
     *
     * [previousTitle] 是本次解析开始前（覆盖 title 之前）该书签原有的标题：checkAll/retryClosedBookmarks
     * 这类定时对账会对同一 canonical 书签反复重新解析，若网页标题相较上次没有变化、且已经有 appName，
     * 就没必要再打一次 DeepSeek——这既省了一次外部 API 调用，也缩短了异步解析任务占用线程池的时间。
     */
    private fun inferAndSetAppName(bookmark: BookmarkEntity, previousTitle: String? = null) {
        val title = bookmark.title ?: run {
            log.debug("[inferAndSetAppName] title 为空，跳过 appName 推断: bookmarkId=${bookmark.id}")
            return
        }
        if (!bookmark.appName.isNullOrBlank() && title == previousTitle) {
            log.debug("[inferAndSetAppName] 标题未变化且已有 appName，跳过重复推断: bookmarkId=${bookmark.id}, appName=${bookmark.appName}")
            return
        }
        log.debug("[inferAndSetAppName] 调用 DeepSeek 推断 appName: bookmarkId=${bookmark.id}, title=$title")
        apiService.inferAppName(title)?.takeIf { it.isNotBlank() }
            ?.also {
                bookmark.appName = it
                log.debug("[inferAndSetAppName] appName 推断成功: bookmarkId=${bookmark.id}, appName=$it")
            } ?: log.debug("[inferAndSetAppName] appName 推断结果为空，保持原值: bookmarkId=${bookmark.id}")
    }

    private fun getByUrl(urlHost: String, urlPath: String): BookmarkEntity? =
        ktQuery().eq(BookmarkEntity::urlHost, urlHost).eq(BookmarkEntity::urlPath, urlPath).one()

    /**
     * 按 (urlHost, urlPath) 获取或创建 canonical 书签。
     * `bookmark` 表在 (url_host, url_path) 上有联合唯一约束：并发插入同一 (host, path) 时，落败的一方
     * 捕获唯一键冲突后回查已存在记录，保证「一页一条」，杜绝重复 canonical 记录。
     * 之所以不能只按 host 去重：同一域名下不同路径是完全不同的页面（不同 GitHub 仓库、不同 Notion
     * 页面……），各自的标题/简称/图标不能共用同一次抓取结果。
     */
    private fun getOrCreateByUrl(urlWrapper: BookmarkUrlWrapper): BookmarkEntity {
        val path = urlWrapper.urlPath ?: "/"
        getByUrl(urlWrapper.urlHost, path)?.let { return it }
        return try {
            BookmarkEntity(urlWrapper).also { save(it) }
        } catch (e: DuplicateKeyException) {
            getByUrl(urlWrapper.urlHost, path) ?: throw e
        }
    }

    private fun findById(bookmarkId: String): BookmarkEntity =
        requireNotNull(ktQuery().eq(BookmarkEntity::id, bookmarkId).one())

    // 单次导入的书签数量上限：10MB 的书签文件可能包含数万条 <A> 记录，
    // 若不加限制会在一个事务里批量写入并逐条发布异步解析事件，冲垮解析线程池与抓取下游。
    private fun assertImportSizeWithinLimit(structures: List<SystemBookmarkStructure>) {
        val total = structures.sumOf { it.bookmarks.size }
        if (total > MAX_IMPORT_BOOKMARK_COUNT) {
            throw CommonException(ErrorType.E121, "${ErrorType.E121.msg}(${total}/${MAX_IMPORT_BOOKMARK_COUNT})")
        }
    }

    companion object {
        // F-08: cap each checkAll() run to prevent flooding the parse executor when a large backlog
        // of unverified bookmarks exists (e.g., on first deployment after fixing the .lt→.eq bug).
        private const val CHECKALL_BATCH_SIZE = 100
        private const val RETRY_CLOSED_BATCH_SIZE = 50
        private const val LIVENESS_CHECK_BATCH_SIZE = 200
        private const val MAX_IMPORT_BOOKMARK_COUNT = 2000
    }
}
