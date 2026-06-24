package top.tcyeee.bookmarkify.server.impl

import cn.hutool.core.date.LocalDateTimeUtil
import com.baomidou.mybatisplus.core.metadata.IPage
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
import top.tcyeee.bookmarkify.entity.enums.ParseStatusEnum
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
    transactionManager: PlatformTransactionManager,
) : IBookmarkService, ServiceImpl<BookmarkMapper, BookmarkEntity>() {

    // 用于在「网络抓取完成之后」把多条 DB 写入包进一个短事务，
    // 避免直接在方法上加 @Transactional 而在整个抓取期间长时间占用数据库连接。
    private val txTemplate = TransactionTemplate(transactionManager)

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
        val result = bookmarkUserLinkMapper.selectPage(params.toPage(), params.toWrapper())
        val bookmarkIds: List<String> = result.records.mapNotNull { it.bookmarkId }
        val bookmarkEntityMap =
            if (bookmarkIds.isEmpty()) emptyMap() else baseMapper.selectByIds(bookmarkIds).associateBy { it.id }
        val logoMap = logosByBookmarkIds(bookmarkIds)
        return result.convert { BookmarkShow(it, bookmarkEntityMap[it.bookmarkId], logoMap[it.bookmarkId]).initLogo() }
    }

    override fun previewImport(file: MultipartFile, uid: String): BookmarkImportPreviewVO {
        val existingUrls: Set<String> = bookmarkUserLinkService.urlsByUid(uid)
        val structures = ChromeBookmarkParser.trim(file)
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
    override fun importBookmarkFile(file: MultipartFile, uid: String) {
        // 1. 解析上传文件，获取扁平化后的书签结构
        val structures: List<SystemBookmarkStructure> = ChromeBookmarkParser.trim(file)
        // 2~3 的批量写入需原子提交：任一批失败都不应留下孤儿文件夹/节点。整段没有网络 IO，事务很短。
        val pair = txTemplate.execute {
            // 2. 保存所有的文件夹,同时保存 nodeId
            structures.map { item -> UserLayoutNodeEntity(uid, item).also { item.nodeId = it.id } }
                .also { layoutNodeMapper.insert(it) }
            // 3. 批量保存布局节点和用户自定义书签（bookmarkId 暂置 LOADING，后续逐个绑定）
            structures.flatMap { node -> node.bookmarks.map { it.pair(uid, node.nodeId) } }
                .also { data -> layoutNodeMapper.insert(data.map { it.first }) }
                .also { data -> bookmarkUserLinkMapper.insert(data.map { it.second }) }
        } ?: emptyList()
        // 4. 异步解析每个 URL，解析完成后重新绑定用户自定义书签（事务提交后再发布事件，避免回滚后仍触发解析）
        pair.forEach {
            eventPublisher.publishEvent(BookmarkParseAndResetUserItemEvent(uid, it.second.urlFull, it.second.id, it.first.id))
        }
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

    override fun addOne(url: String, uid: String): UserLayoutNodeVO {
        log.debug("[addOne] uid=$uid 开始添加书签, rawUrl=$url")

        // 1. 标准化 URL，解析出 host、完整地址等结构化信息
        val bookmarkUrl: BookmarkUrlWrapper = WebsiteParser.urlWrapper(url)
        log.debug("[addOne] Step1 URL 标准化完成: urlHost=${bookmarkUrl.urlHost}, urlFull=${bookmarkUrl.urlFull}")

        // 2. 按 urlHost 获取或创建 canonical 书签记录。
        //    多个用户共享同一条 bookmark 记录（一对多），避免重复抓取同一网站。
        //    getOrCreateByHost 容忍并发插入同一 host（依赖 url_host 唯一约束）。
        val bookmark = getOrCreateByHost(bookmarkUrl)
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
        val bookmark = getOrCreateByHost(wrapper)
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
        parseBookmark(baseMapper.selectById(bookmarkId))
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
        val entity = getOrCreateByHost(urlWrapper)
        if (entity.parseStatus == ParseStatusEnum.LOADING) parseBookmark(entity)
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
     * 统一解析调度：检查 verifyFlag 后根据配置选择解析方式
     */
    private fun parseBookmark(bookmark: BookmarkEntity): BookmarkEntity {
        log.debug("[parseBookmark] 开始调度解析: bookmarkId=${bookmark.id}, urlHost=${bookmark.urlHost}")
        val existing = baseMapper.selectById(bookmark.id)
        if (existing != null && existing.verifyFlag) {
            log.debug("[parseBookmark] 书签已手动认证(verifyFlag=true), 跳过解析直接返回: bookmarkId=${bookmark.id}")
            return existing
        }
        val mode = if (projectConfig.useThirdPartyParser) "远程scrapper" else "本地Jsoup"
        log.debug("[parseBookmark] 选择解析模式: $mode, bookmarkId=${bookmark.id}")
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
        bookmark.successInit(wrapper)
        inferAndSetAppName(bookmark)
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
                vo.entity(bookmark).also {
                    inferAndSetAppName(it)
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

    /**
     * 按 host 获取或创建 canonical 书签。
     * `bookmark.url_host` 上有唯一约束：并发插入同一 host 时，落败的一方捕获唯一键冲突后
     * 回查已存在记录，保证「一域一条」，杜绝重复 canonical 记录。
     */
    private fun getOrCreateByHost(urlWrapper: BookmarkUrlWrapper): BookmarkEntity {
        getByHost(urlWrapper.urlHost)?.let { return it }
        return try {
            BookmarkEntity(urlWrapper).also { save(it) }
        } catch (e: DuplicateKeyException) {
            getByHost(urlWrapper.urlHost) ?: throw e
        }
    }

    private fun findById(bookmarkId: String): BookmarkEntity =
        requireNotNull(ktQuery().eq(BookmarkEntity::id, bookmarkId).one())

    companion object {
        // F-08: cap each checkAll() run to prevent flooding the parse executor when a large backlog
        // of unverified bookmarks exists (e.g., on first deployment after fixing the .lt→.eq bug).
        private const val CHECKALL_BATCH_SIZE = 100
    }
}
