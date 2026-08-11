package top.tcyeee.bookmarkify.server.impl

import cn.hutool.core.date.LocalDateTimeUtil
import cn.hutool.core.util.IdUtil
import com.baomidou.mybatisplus.core.metadata.IPage
import com.baomidou.mybatisplus.extension.kotlin.KtQueryWrapper
import com.baomidou.mybatisplus.extension.plugins.pagination.Page
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.context.ApplicationEventPublisher
import org.springframework.dao.DuplicateKeyException
import org.springframework.scheduling.annotation.Async
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor
import org.springframework.stereotype.Service
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionTemplate
import org.springframework.web.multipart.MultipartFile
import top.tcyeee.bookmarkify.config.async.AsyncConfig
import top.tcyeee.bookmarkify.config.async.ParseLock
import top.tcyeee.bookmarkify.entity.dto.StuckLoadingItem
import top.tcyeee.bookmarkify.entity.dto.scrape.CacheMode
import top.tcyeee.bookmarkify.entity.dto.scrape.ScrapeResponse
import top.tcyeee.bookmarkify.entity.dto.scrape.applyTo
import top.tcyeee.bookmarkify.entity.dto.scrape.cached
import top.tcyeee.bookmarkify.entity.dto.scrape.description
import top.tcyeee.bookmarkify.entity.dto.scrape.faviconUrl
import top.tcyeee.bookmarkify.entity.dto.scrape.logoUrl
import top.tcyeee.bookmarkify.entity.dto.scrape.primarySource
import top.tcyeee.bookmarkify.entity.dto.scrape.screenshotUrl
import top.tcyeee.bookmarkify.entity.dto.scrape.shortName
import top.tcyeee.bookmarkify.entity.dto.scrape.socialUrl
import top.tcyeee.bookmarkify.entity.dto.scrape.title
import top.tcyeee.bookmarkify.entity.enums.DisplayMode
import top.tcyeee.bookmarkify.server.asset.SiteAssetResolver
import top.tcyeee.bookmarkify.server.asset.SiteAssetWriter
import top.tcyeee.bookmarkify.server.asset.SiteDisplayPrefService
import top.tcyeee.bookmarkify.config.log
import top.tcyeee.bookmarkify.config.cache.RedisType
import top.tcyeee.bookmarkify.config.entity.ProjectConfig
import top.tcyeee.bookmarkify.config.entity.ScrapperConfig
import top.tcyeee.bookmarkify.config.exception.CommonException
import top.tcyeee.bookmarkify.config.exception.ErrorType
import top.tcyeee.bookmarkify.entity.*
import top.tcyeee.bookmarkify.entity.dto.BookmarkUrlWrapper
import top.tcyeee.bookmarkify.entity.dto.ManifestIcon
import top.tcyeee.bookmarkify.entity.dto.SimilarIngestUpdate
import top.tcyeee.bookmarkify.entity.dto.SimilarSite
import top.tcyeee.bookmarkify.entity.entity.*
import top.tcyeee.bookmarkify.entity.enums.AssetRole
import top.tcyeee.bookmarkify.entity.enums.BookmarkLinkType
import top.tcyeee.bookmarkify.entity.enums.PageLockedField
import top.tcyeee.bookmarkify.entity.enums.ParseStatusEnum
import top.tcyeee.bookmarkify.server.liveness.LivenessPolicy
import top.tcyeee.bookmarkify.server.liveness.PageScheduleWriter
import top.tcyeee.bookmarkify.server.parse.PageParseStateWriter
import top.tcyeee.bookmarkify.server.parse.PageParseStateWriter.Companion.isRefusedTarget
import top.tcyeee.bookmarkify.server.parse.PageParseStateWriter.Companion.isScrapperUnavailable
import top.tcyeee.bookmarkify.mapper.*
import top.tcyeee.bookmarkify.server.IApiService
import top.tcyeee.bookmarkify.server.IBookmarkLivenessConfigService
import top.tcyeee.bookmarkify.server.IBookmarkService
import top.tcyeee.bookmarkify.server.ISiteService
import top.tcyeee.bookmarkify.server.admin.AdminUserViewAssembler
import top.tcyeee.bookmarkify.config.event.BookmarkEnrichEvent
import top.tcyeee.bookmarkify.config.event.BookmarkParseAndNoticeEvent
import top.tcyeee.bookmarkify.config.event.BookmarkParseAndResetUserItemEvent
import top.tcyeee.bookmarkify.config.event.BookmarkParseEvent
import top.tcyeee.bookmarkify.config.event.BookmarkScreenshotEvent
import top.tcyeee.bookmarkify.server.IBookmarkCategoryService
import top.tcyeee.bookmarkify.server.IBookmarkUserLinkService
import top.tcyeee.bookmarkify.server.IUserLayoutNodeService
import top.tcyeee.bookmarkify.utils.*
import java.time.Duration
import java.time.LocalDateTime
import java.time.temporal.ChronoUnit
import java.util.concurrent.CompletableFuture

/**
 * @author tcyeee
 * @date 3/10/24 15:46
 */
@Service
class BookmarkServiceImpl(
    private val bookmarkUserLinkMapper: BookmarkMapper,
    private val projectConfig: ProjectConfig,
    private val scrapperConfig: ScrapperConfig,
    private val eventPublisher: ApplicationEventPublisher,
    private val apiService: IApiService,
    private val layoutNodeMapper: UserLayoutNodeMapper,
    private val siteAssetResolver: SiteAssetResolver,
    private val siteAssetWriter: SiteAssetWriter,
    private val siteDisplayPrefService: SiteDisplayPrefService,
    private val siteService: ISiteService,
    private val bookmarkUserLinkService: IBookmarkUserLinkService,
    private val userLayoutNodeService: IUserLayoutNodeService,
    private val layoutNodeFunctionMapper: LayoutNodeFunctionMapper,
    private val bookmarkCategoryService: IBookmarkCategoryService,
    private val adminUserViewAssembler: AdminUserViewAssembler,
    private val bookmarkLivenessConfigService: IBookmarkLivenessConfigService,
    private val parseLock: ParseLock,
    private val scheduleWriter: PageScheduleWriter,
    private val parseStateWriter: PageParseStateWriter,
    @Qualifier(AsyncConfig.BOOKMARK_PARSE_EXECUTOR) private val parseExecutor: ThreadPoolTaskExecutor,
    transactionManager: PlatformTransactionManager,
) : IBookmarkService, ServiceImpl<PageMapper, PageEntity>() {

    // 用于在「网络抓取完成之后」把多条 DB 写入包进一个短事务，
    // 避免直接在方法上加 @Transactional 而在整个抓取期间长时间占用数据库连接。
    private val txTemplate = TransactionTemplate(transactionManager)

    // 找到全部的系统默认书签,存储用户桌面布局和自定义书签
    override fun setDefaultBookmark(uid: String) =
        findListByUrl(projectConfig.defaultBookmarkify)
            .map { bookmark ->
                UserLayoutNodeEntity(uid = uid).let { node -> Pair(node, BookmarkEntity(bookmark, node.id, uid)) }
            }.also { pair ->
                layoutNodeMapper.insert(pair.map { it.first })
                bookmarkUserLinkMapper.insert(pair.map { it.second })
            }.run {}

    /**
     * 某域名的**首页**是否已收录。
     *
     * 原先是 `ktQuery().eq(urlHost, host).one()`，这在同一 host 下存在两条路径时会直接抛异常
     * （MyBatis-Plus 的 `one()` 不允许多行）—— 而按路径去重之后这是必然会出现的情况。
     * 唯一的调用方（相似站点收录）真正想问的是「这个域名的首页收没收过」，所以收窄成按
     * canonical 根页面查询：域名下有别的深链被收录过，不代表首页也有了。
     */
    override fun findRootPageByHost(host: String): PageEntity? =
        siteService.findByHost(host)?.let { getByCanonical(it.id, "/", "", "") }

    override fun findListByUrl(urls: List<String>): List<PageEntity> =
        urls.mapNotNull { runCatching { WebsiteParser.urlWrapper(it) }.getOrNull() }
            .mapNotNull { getByUrl(it) }

    override fun getOrCreateCanonical(url: String): PageEntity =
        getOrCreateByUrl(WebsiteParser.urlWrapper(url))

    @Transactional
    override fun setDefaultFunction(uid: String) =
        UserLayoutNodeEntity(uid = uid, type = NodeTypeEnum.FUNCTION).also { layoutNodeMapper.insert(it) }
            .let { LayoutNodeFunctionEntity(it, uid) }.also { layoutNodeFunctionMapper.insert(it) }.run {}

    /**
     * 搜索范围限定在 site 层，不再按具体页面的标题/简介匹配。
     *
     * 原实现直接在 [PageEntity] 上模糊匹配 appName/title/description/urlHost：既会把某个用户
     * 深链页面里的私有标题（论坛帖子、视频标题）当成"网站"推荐给别人，NSFW 判定又是站点级属性
     * （见 [SiteEntity.nsfw]），单查 page 完全查不到这一列，搜索结果里混进过 NSFW 内容也无从挡。
     * 改成先在 site 上按 host/brandName/shortName 匹配 + 排除 nsfw，再取每个命中站点的首页
     * （唯一允许出现在搜索结果里的落点）作为可关联的 [BookmarkSearchVO.id]。
     *
     * 候选站点数取得比最终返回数大：命中的站点未必已经抓到一个可用的首页（尚未收录/抓取失败），
     * 直接按最终条数取会在这些站点身上白白浪费名额。
     */
    override fun search(name: String): List<BookmarkSearchVO> {
        val sites = siteService.ktQuery()
            .eq(SiteEntity::nsfw, false)
            .and { w ->
                w.like(SiteEntity::host, name).or()
                    .like(SiteEntity::brandName, name).or()
                    .like(SiteEntity::shortName, name)
            }
            .last("limit $SEARCH_SITE_CANDIDATE_LIMIT")
            .list()
        if (sites.isEmpty()) return emptyList()

        val siteIds = sites.map { it.id }
        val rootPageBySite = ktQuery()
            .`in`(PageEntity::siteId, siteIds)
            .eq(PageEntity::urlPath, "/")
            .eq(PageEntity::urlQuery, "")
            .eq(PageEntity::urlFragment, "")
            .eq(PageEntity::isActivity, true)
            .list()
            .associateBy { it.siteId }

        val matched = sites.mapNotNull { site -> rootPageBySite[site.id]?.let { site to it } }
            .take(SEARCH_RESULT_LIMIT)
        if (matched.isEmpty()) return emptyList()

        // 搜索结果是小图 + 全名的形态，按 LIST 模式解析图标
        val logoMap = siteAssetResolver.resolveBatch(matched.map { it.second.id }, DisplayMode.LIST)
        return matched.map { (site, page) -> BookmarkSearchVO(page, site, logoMap[page.id]) }
    }

    override fun linkOne(pageId: String, uid: String): UserLayoutNodeVO {
        // 与 addOne 同一套前置检查：这两个方法对用户是同一件事（把一个页面放到我的桌面上），
        // 差别只在 canonical 记录是现查的还是现建的，重复判定自然也该一致——**包括导入队列里
        // 那批还没绑定 canonical 记录的占位**，它们同样会在桌面上变成第二个一模一样的磁贴。
        // 记录先查出来再判重：目标都不存在的话，重复与否根本无从谈起。
        val bookmark = findById(pageId)
        assertNotAlreadyLinked(uid, bookmark)

        val nodeEntity = UserLayoutNodeEntity(uid = uid)
        val userLink = BookmarkEntity(bookmark, nodeEntity.id, uid)
        insertNodeAndLink(nodeEntity, userLink)
        return showForDesktop(userLink.id).let { UserLayoutNodeVO(nodeEntity, it) }
    }

    /**
     * 写入「桌面节点 + 用户关联」这一对记录。`addOne` 与 `linkOne` 共用。
     *
     * **必须原子提交**：分开写时第二条失败会在用户桌面上留下一个没有任何书签数据的孤儿节点
     * ——`layout()` 按 `layoutNodeId` 找不到对应的 `BookmarkShow`，前端只能渲染出一个点不开
     * 也删不掉的空格子。
     *
     * **唯一键冲突翻成 E126，这里才是判重的权威。** 上游的 [assertNotAlreadyLinked] 是
     * check-then-act：查一次、再插入，两个并发请求可以同时通过那道检查。此前真正挡住重复磁贴的
     * 其实是 `addOne` 上那个 1 秒的 `@Throttle` —— 而限流是 UX 设施不是正确性设施，它的参数会
     * 因为「加书签太慢」被调宽，`ThrottleAspect` 在 Redis 故障时更是**明确降级放行**。
     * 现在由 `uk_bul_uid_bookmark` 兜底，与 `getOrCreateByUrl` 靠 `uk_bookmark_canonical`
     * 收敛并发插入是同一个套路。
     */
    private fun insertNodeAndLink(node: UserLayoutNodeEntity, link: BookmarkEntity) {
        try {
            txTemplate.execute {
                layoutNodeMapper.insert(node)
                bookmarkUserLinkMapper.insert(link)
            }
        } catch (e: DuplicateKeyException) {
            // 事务已整体回滚，那个刚插进去的布局节点不会留下来
            log.debug("[insertNodeAndLink] 唯一键冲突，判定为重复收藏: uid=${link.uid}, pageId=${link.pageId}, err=${e.message}")
            throw CommonException(ErrorType.E126)
        }
    }

    /**
     * 该用户已经收藏过这个 canonical 页面时直接拒绝，避免桌面上出现两个一模一样的磁贴。
     *
     * `deleted = false` 不能省：本项目没有配置 MyBatis-Plus 的逻辑删除，`deleted` 全靠各查询手写
     * 过滤。漏掉这个条件，用户删掉一条书签之后就再也加不回来了。
     */
    private fun assertNotAlreadyLinked(uid: String, bookmark: PageEntity) {
        val exists = bookmarkUserLinkService.ktQuery()
            .eq(BookmarkEntity::uid, uid)
            .eq(BookmarkEntity::pageId, bookmark.id)
            .eq(BookmarkEntity::deleted, false)
            .exists()
        if (exists) {
            log.debug("[assertNotAlreadyLinked] 用户已收藏该页面，拒绝重复添加: uid=$uid, pageId=${bookmark.id}")
            throw CommonException(ErrorType.E126)
        }
        assertNotPendingImport(uid, bookmark)
    }

    /**
     * 导入还没抓完的那批占位是否已经包含了这个页面。
     *
     * 上面那道检查按 canonical `pageId` 比对，而批量导入写下的关联行 `page_id` 是字符串
     * 常量 `'LOADING'`（canonical 记录要等 drainStuckLoading 抓完才绑上去），永远匹配不上——
     * 导入正在跑的时候手动添加同一个网址，桌面上就会多出一个磁贴，等两边都抓完才看得出重复。
     *
     * 判定必须落在 canonical 四元组上而不是 URL 字符串上，理由与上面那道检查完全相同
     * （`github.com/x` / `https://github.com/x/` 是同一个页面）。基准直接取自 canonical 记录
     * 自己的那四列，而不是再解析一遍入参网址——addOne 与 linkOne 因此比的是同一份东西。
     * 反过来占位行只有用户给的原始网址，库里没有可比的规范化列，只能取回来在内存里规范化：
     * 所以先用 host 子串在 SQL 侧收窄（host 必然逐字出现在原始网址中），再逐条比对
     * (path, query, fragment)。这样即使正在导入几千条，参与比对的也只是同域名下的那几条。
     */
    private fun assertNotPendingImport(uid: String, bookmark: PageEntity) {
        val pending = bookmarkUserLinkService.ktQuery()
            .eq(BookmarkEntity::uid, uid)
            .eq(BookmarkEntity::pageId, StuckLoadingItem.UNBOUND_BOOKMARK_ID)
            .eq(BookmarkEntity::deleted, false)
            .like(BookmarkEntity::urlFull, bookmark.urlHost)
            .last("LIMIT $IMPORT_DUPLICATE_SCAN_LIMIT")
            .list()
        if (pending.isEmpty()) return
        // 规范化失败的占位行直接跳过：那种网址本来就进不了 canonical 体系，谈不上与它重复
        val duplicated = pending.any { row ->
            runCatching { WebsiteParser.urlWrapper(row.urlFull) }.getOrNull()?.let { other ->
                other.urlHost == bookmark.urlHost &&
                    (other.urlPath ?: "/") == bookmark.urlPath &&
                    other.urlQuery == bookmark.urlQuery &&
                    other.urlFragment == bookmark.urlFragment
            } == true
        }
        if (duplicated) {
            log.debug("[assertNotPendingImport] 该页面已在导入队列中，拒绝重复添加: uid=$uid, urlHost=${bookmark.urlHost}")
            throw CommonException(ErrorType.E126)
        }
    }

    override fun allOfMyBookmark(uid: String, params: AllOfMyBookmarkParams): IPage<BookmarkShow> {
        // "重复书签"/"失效书签" 筛选：先在用户自己的书签范围内算出候选 pageId 集合，
        // 再作为 IN 条件叠加到分页查询上；两者同时开启时取交集。
        val duplicateIds = if (params.duplicatesOnly) bookmarkUserLinkService.duplicatePageIds(uid) else null
        val invalidIds = if (params.invalidOnly) {
            val mine = bookmarkUserLinkService.bookmarkIdsByUid(uid)
            if (mine.isEmpty()) emptySet() else ktQuery().`in`(PageEntity::id, mine).eq(PageEntity::isActivity, false).list().map { it.id }.toSet()
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
        val pageIds: List<String> = result.records.mapNotNull { it.pageId }
        val bookmarkEntityMap =
            if (pageIds.isEmpty()) emptyMap() else baseMapper.selectByIds(pageIds).associateBy { it.id }
        // 前台桌面是大图 + 短名的形态，按 TILE 模式解析图标
        val logoMap = siteAssetResolver.resolveBatch(pageIds, DisplayMode.TILE)
        // 站点层带上品牌名/短名/NSFW：文案优先级要用它们，一次批量取回避免 N+1
        val siteMap = siteService.mapByIds(bookmarkEntityMap.values.map { it.siteId })

        // 所属文件夹：布局节点(layoutNodeId) -> 父节点(parentId) -> 父节点名称，两次批量查询避免 N+1
        val layoutNodeIds = result.records.map { it.layoutNodeId }
        val layoutNodeMap = if (layoutNodeIds.isEmpty()) emptyMap() else layoutNodeMapper.selectByIds(layoutNodeIds).associateBy { it.id }
        val folderIds = layoutNodeMap.values.mapNotNull { it.parentId }.distinct()
        val folderMap = if (folderIds.isEmpty()) emptyMap() else layoutNodeMapper.selectByIds(folderIds).associateBy { it.id }

        return result.convert {
            val folder = layoutNodeMap[it.layoutNodeId]?.parentId?.let { fid -> folderMap[fid] }
            val bookmark = bookmarkEntityMap[it.pageId]
            BookmarkShow(it, bookmark, siteMap[bookmark?.siteId])
                .initDisplay(logoMap[it.pageId], DisplayMode.TILE).apply {
                folderId = folder?.id
                folderName = folder?.name
            }
        }
    }

    override fun previewImport(file: MultipartFile, uid: String): BookmarkImportPreviewVO {
        val existingKeys: Set<String> = bookmarkUserLinkService.urlsByUid(uid).mapNotNullTo(HashSet()) { canonicalKeyOf(it) }
        val structures = ChromeBookmarkParser.trim(file)
        assertImportSizeWithinLimit(structures)
        val items = structures.flatMap { structure ->
            structure.bookmarks.map { raw ->
                BookmarkImportItemVO(
                    title = raw.title,
                    url = raw.url,
                    folder = structure.folderName.takeIf { it != "ROOT" },
                    // 按 canonical 四元组比对，不是比字符串：同一个页面在两次导出里可能写作
                    // github.com/x、https://github.com/x、https://github.com/x/?utm_source=y，
                    // 逐字节相等才算重复的话，这个检测基本等于没有。
                    isDuplicate = canonicalKeyOf(raw.url)?.let { it in existingKeys } ?: false,
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
     * 把任意写法的网址归一成 canonical 去重键 `host|path|query|fragment`，与 `uk_bookmark_canonical`
     * 唯一索引的口径一致 —— 判定「是不是同一个页面」全系统只该有这一个标准。
     *
     * 解析不出来（`javascript:` 小书签、超长网址等）返回 null：这类网址进不了 canonical 体系，
     * 也就无从谈重复，一律按「不重复」放行，由后续导入流程收口成无源书签。
     */
    private fun canonicalKeyOf(rawUrl: String?): String? {
        if (rawUrl.isNullOrBlank()) return null
        return runCatching {
            WebsiteParser.urlWrapper(rawUrl).let { "${it.urlHost}|${it.urlPath ?: "/"}|${it.urlQuery}|${it.urlFragment}" }
        }.getOrNull()
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
            // 超过 url_full 列宽的网址整条丢弃：它没法落库，留着只会让整批导入在 INSERT 处
            // 一起回滚（这几条写入是一个事务），用户看到的是"导入失败"而不是"跳过了 3 条"。
            val kept = s.bookmarks
                .filter { it.url !in skipUrls }
                .filter { raw ->
                    (raw.url.length <= MAX_STORABLE_URL_LENGTH).also {
                        if (!it) log.warn("[importBookmarkFile] 网址超出字段上限，已跳过该条: uid=$uid, length=${raw.url.length}, title=${raw.title.take(50)}")
                    }
                }
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
        val allLinks: List<BookmarkEntity> = allBookmarkNodes.map { (raw, node) -> BookmarkEntity(uid, node.id, raw) }

        txTemplate.execute {
            val folderNodes = slices.mapNotNull { it.folderNode }
            if (folderNodes.isNotEmpty()) layoutNodeMapper.insert(folderNodes)
            if (allBookmarkNodes.isNotEmpty()) layoutNodeMapper.insert(allBookmarkNodes.map { it.second })
            if (allLinks.isNotEmpty()) bookmarkUserLinkMapper.insert(allLinks)
        }

        // 刻意**不在这里投递解析事件**。导入一次最多 MAX_IMPORT_BOOKMARK_COUNT 条，而单条解析
        // 最坏要花几十秒，逐条投递会瞬间灌满解析线程池连同它的有界队列，随后 CallerRunsPolicy
        // 让**调用线程**同步跑完剩下的任务——调用线程就是当前这个 HTTP 请求线程，于是一次大导入
        // 能把 Tomcat 的线程一个个钉死在网络等待上，拖垮整个 API。
        //
        // 改由 drainStuckLoading() 按解析线程池的空闲容量分批捞取（占位行 page_id='LOADING'
        // 就是待办标记）。压力落在数据库这个本来就要写的地方，而不是某个线程上；顺带获得了
        // 「进程重启后导入能自动接着做完」的能力——以前重启会让在途事件连同队列一起丢光。

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
        // 职责收窄为「异步解析事件丢失/未完成」的兜底对账：只处理 PENDING，不再涉足 UNREACHABLE/SUCCESS 的复查——
        // 那是 retryUnreachableBookmarks / livenessCheckStaleBookmarks 的职责，避免同一条记录被多个任务抢跑。
        // PENDING 正常应在几分钟内被异步解析消费掉，超过 CHECKALL_PENDING_STALE_MINUTES 未更新基本可判定是事件丢失。
        //
        // 时间基准取 COALESCE(update_time, create_time) 而非 update_time：新建的书签 update_time 为 NULL
        // （PageEntity 只在解析出结果时才写它），而 SQL 里 `NULL < ?` 恒为 NULL，于是「从未被解析过」
        // 的书签——恰恰是最需要兜底的那批——反而一条都选不出来。一旦 addOne 之后的解析事件丢失
        // （进程重启、线程池饱和回退到调用线程后抛异常），这条书签就会永久停在 PENDING：
        // needRecheckOnAdd() 对 PENDING 直接返回 false，另两个定时任务又按 status 把它过滤掉，
        // 结果是所有用户的桌面节点永久停在 BOOKMARK_LOADING 转圈。
        // 用 create_time 兜底而不是把 NULL 直接视作「已过期」，是为了保住那 30 分钟的窗口：
        // 否则刚添加、事件还在途中的书签会在下一次 tick(5min) 就被重复投递一次解析。
        ktQuery()
            .eq(PageEntity::parseStatus, ParseStatusEnum.PENDING)
            .eq(PageEntity::verifyFlag, false)
            .apply(
                "COALESCE(update_time, create_time) < {0}",
                LocalDateTimeUtil.offset(LocalDateTime.now(), -CHECKALL_PENDING_STALE_MINUTES, ChronoUnit.MINUTES)
            )
            // 最旧的优先处理，配合 LIMIT 保证积压记录会被逐批消费，不会被新记录饿死。
            .last("ORDER BY COALESCE(update_time, create_time) ASC LIMIT $CHECKALL_BATCH_SIZE")
            .list()
            .forEach { eventPublisher.publishEvent(BookmarkParseEvent(it.id)) }

    /**
     * 把「用户桌面上还在转圈」的书签补投递给解析线程池。
     *
     * 这是导入路径的**正式消费通道**（导入只落库不投递事件），同时也兜底 addOne 丢失的解析事件。
     * 与 [checkAll] 的分工：那边看 canonical 书签的 parse_status，这边看用户可见的节点状态——
     * 书签抓取成功但重绑/翻转节点失败的情况只有这边能发现。
     *
     * 投递量按线程池**当前空闲的队列容量**决定，而不是一个固定批次大小：队列本来就是用来削峰的，
     * 填满它即可，多投的部分只会触发 CallerRunsPolicy 在本线程上同步执行，白白拖慢下一轮。
     * 留一点余量给交互式的 addOne，别让批量导入把队列占满、把单个添加挤到调用线程上去。
     */
    // 不加 @Async：本方法只做两次查询和若干次事件投递，全程不等网络，跑在调度线程上即可。
    // 反倒是投递到解析池这件事必须由一个「不怕被 CallerRunsPolicy 拖住」的线程来做——
    // 上面的余量检查就是为此，headroom 保证正常情况下永远投得进队列。
    override fun drainStuckLoading() {
        // 先报「还有多少人在等」。放在最前面且无条件执行：队列打满而提前 return 的那一轮，
        // 恰恰是这个数字最该被看到的时候
        reportStuckLoading()

        // 重试预算已经用尽的先就地终结。必须排在补投递**之前**：它们正是按 created_at 排在
        // 队头、把后面的行挡住的那批，不清掉的话下面捞回来的还是同一批人
        terminateExhaustedLoading()

        val free = parseExecutor.threadPoolExecutor.queue.remainingCapacity() - DRAIN_QUEUE_HEADROOM
        if (free <= 0) {
            log.debug("[drainStuckLoading] 解析队列余量不足，本轮跳过: remaining=${free + DRAIN_QUEUE_HEADROOM}")
            return
        }

        val staleBefore = LocalDateTimeUtil.offset(LocalDateTime.now(), -CHECKALL_PENDING_STALE_MINUTES, ChronoUnit.MINUTES)
        val items = bookmarkUserLinkMapper.findStuckLoading(
            staleBefore, minOf(free, DRAIN_MAX_BATCH_SIZE), MAX_DISPATCH_ATTEMPTS
        )
        if (items.isEmpty()) return

        // 取锁失败说明这条已经在途（上一轮投递的任务还没跑完），跳过即可，别投第二遍
        val dispatched = items.filter { parseLock.tryAcquire(ParseLock.dispatch(it.userLinkId), DISPATCH_LOCK_TTL) }
        if (dispatched.isEmpty()) {
            log.debug("[drainStuckLoading] 本轮 ${items.size} 条全部在途，无需补投递")
            return
        }

        // 计数先落库再投递。反过来的话，任务跑得快的那些会在 UPDATE 之前就把节点翻走，
        // 这条 UPDATE 于是加在一条已经收口的记录上——虽然无害，但计数就不再等于"投了几次"
        runCatching { bookmarkUserLinkMapper.incrementDispatchAttempts(dispatched.map { it.userLinkId }) }
            .onFailure { log.warn("[drainStuckLoading] 重试计数累加失败(仍继续投递): ${it.message}") }

        dispatched.forEach { item ->
            if (item.unbound) {
                eventPublisher.publishEvent(
                    BookmarkParseAndResetUserItemEvent(item.uid, item.urlFull, item.userLinkId, item.layoutNodeId)
                )
            } else {
                eventPublisher.publishEvent(
                    BookmarkParseAndNoticeEvent(item.uid, item.pageId!!, item.userLinkId, item.layoutNodeId)
                )
            }
        }
        log.debug("[drainStuckLoading] 本轮补投递 ${dispatched.size}/${items.size} 条(其余在途)，解析队列余量 $free")
    }

    /**
     * 输出「此刻有多少用户桌面在转圈、最久的转了多久」。
     *
     * 这是整条添加链路唯一真正的 SLI，而在此之前它没有任何一处被观测 —— `scrapper_call_log`
     * 记的是单次调用、`bookmark_ping_log` 记的是巡检，都回答不了「用户现在还在等的有几条」。
     * 于是 ADD-BOOKMARK-FLOW.md §7 那张兜底矩阵只是"设计上应该成立"，线上无从验证。
     *
     * 分级刻意做了区分：有积压但都很新是正常的（抓取本来就要几十秒），**超过陈旧阈值还在转**
     * 才说明某条兜底没兜住，那才值得 warn。
     */
    private fun reportStuckLoading() = runCatching {
        val stats = bookmarkUserLinkMapper.stuckLoadingStats()
        if (stats.total == 0L) return@runCatching
        val oldestMinutes = stats.oldestAgeSeconds / 60
        val line = "[drainStuckLoading] 转圈中: 共 ${stats.total} 条(导入积压 ${stats.importPending} 条), " +
            "最久已等 ${oldestMinutes} 分钟"
        if (oldestMinutes >= CHECKALL_PENDING_STALE_MINUTES) {
            log.warn("$line —— 已超过 ${CHECKALL_PENDING_STALE_MINUTES} 分钟陈旧阈值，说明有兜底没生效")
        } else {
            log.debug(line)
        }
    }.onFailure { log.warn("[drainStuckLoading] 转圈统计失败(忽略): ${it.message}") }.let { }

    /**
     * 把重试预算已经用尽、仍停在 `BOOKMARK_LOADING` 的占位就地终结成无源书签。
     *
     * 没有这一步，[drainStuckLoading] 就是一个**没有放弃条件**的重试循环：`findStuckLoading`
     * 按 `created_at ASC LIMIT n` 取行，补投递锁只让在途的被跳过、并不改变它们仍排在最前面
     * 这一事实，于是一批永远收不了口的记录会稳定占满那 n 个名额，新记录一轮都轮不到。
     *
     * 终结方式与「网址本身就解析不出来」完全一致（[finishNodeWithoutBookmark]）：翻成普通磁贴，
     * 不绑 canonical 书签 —— `bookmark_user_link` 里存着用户自己的标题和网址，足够渲染，
     * 这也是这类记录能有的最好结果。**比一个永远转圈的格子好。**
     */
    private fun terminateExhaustedLoading() = runCatching {
        val exhausted = bookmarkUserLinkMapper.findExhaustedLoading(DRAIN_MAX_BATCH_SIZE, MAX_DISPATCH_ATTEMPTS)
        if (exhausted.isEmpty()) return@runCatching
        log.warn(
            "[drainStuckLoading] ${exhausted.size} 条占位补投递已达 $MAX_DISPATCH_ATTEMPTS 次仍未收口，" +
                "就地终结为无源书签(样例: ${exhausted.take(3).joinToString { it.urlFull.take(80) }})"
        )
        exhausted.forEach { item ->
            runCatching { finishNodeWithoutBookmark(item.uid, item.userLinkId, item.layoutNodeId) }
                .onFailure { log.warn("[drainStuckLoading] 终结占位失败: userLinkId=${item.userLinkId}, err=${it.message}") }
        }
    }.onFailure { log.warn("[drainStuckLoading] 终结耗尽占位失败(忽略): ${it.message}") }.let { }

    /**
     * 归档记录的**唯一**复活入口：有用户来添加这个网址，就地把重试次数清零并重新检查一次。
     *
     * ## 为什么出口是「有人添加」而不是「过了 N 天」
     *
     * 归档必须有出口——通往它的证据链是自动且可能出错的（域名临时改了 DNS、机房出口被目标站点
     * 拉黑一段时间、我方连续几轮判断失误），让一个自动流程能把记录推进终态却不给出口，
     * 这个不对称本身就是设计缺陷。但出口不必是定时的：此前那条每天一轮的复活探测，成本是
     * **永久**的（一个再也不会回来的域名，每 30 天照样吃一次探测和一个 `LIMIT` 名额），
     * 收益却随时间趋近于零——归档意味着已经连续失败到了配置的上限。
     *
     * 「现在有人正要收藏它」是强得多的复活信号，而且不花任何空转成本：没人添加的死站点
     * 一次也不探，有人添加的立刻就探。
     *
     * 清零 [PageEntity.consecutiveFail] 是必需的，不能只改状态：归档阈值看的就是这个计数，
     * 不清零的话下一轮巡检第一次失败就又满足 `shouldArchive`，等于压根没复活过。
     * 状态改回 PENDING 而不是 UNREACHABLE，是为了让 [checkAll] 也能兜住它——这条记录
     * 接下来要走的是完整解析链路，和一条全新书签没有区别。
     */
    private fun PageEntity.reviveOnAdd() {
        if (parseStatus != ParseStatusEnum.ARCHIVED) return
        log.warn("[reviveOnAdd] 归档记录被重新添加，重置重试次数并重新检查: pageId=$id, urlHost=$urlHost, 原连续失败=$consecutiveFail")
        runCatching {
            ktUpdate().eq(PageEntity::id, id)
                .set(PageEntity::parseStatus, ParseStatusEnum.PENDING)
                .set(PageEntity::consecutiveFail, 0)
                // 立刻到期：万一下面的解析链路没跑成，巡检也能马上接手，而不是又等一个周期
                .set(PageEntity::nextCheckAt, LocalDateTime.now())
                .set(PageEntity::updateTime, LocalDateTime.now())
                .update()
        }.onFailure {
            log.warn("[reviveOnAdd] 重置失败，本次按原状态继续: pageId=$id, err=${it.message}")
            return
        }
        // 内存里的这份也要跟上：调用方紧接着就用它算 needParse / needRecheckOnAdd
        parseStatus = ParseStatusEnum.PENDING
        consecutiveFail = 0
        // updateTime 置空是**必需**的，不是顺手清理。复活之后这一趟必须真的重抓一次，
        // 而 needParse = checkFlag() || needRecheckOnAdd()：后者对 PENDING 恒为 false
        // （PENDING 归 checkFlag 管），前者又是拿 updateTime 和 24h 比。于是「刚归档不久
        // 又被添加」这条路上两个条件同时不成立，用户拿到的是一个不会被解析的空磁贴 ——
        // 而这恰恰是最该重抓的情形。置空即 checkFlag() 返回 true，语义上也正确：
        // 这条记录刚被重置成「从没解析过」的状态
        updateTime = null
    }

    // ────── 解析结果的两种终态与调度列（**所有**改 parseStatus 的地方都必须走这里）──────
    //
    // 实现在 [PageParseStateWriter] / [PageScheduleWriter]，这里只是四个就近可读的别名。
    //
    // 这五行此前被逐字复制了十遍：`isActivity` / `parseStatus` / `parseErrMsg` / `updateTime`
    // 再加一句 schedule*。四个字段之间是有约束的（SUCCESS 必然 isActivity=true 且 errMsg 为空），
    // 而调度列那一句漏掉不会报任何错 —— 那条记录的 next_check_at 就停在旧值上，要么被每轮巡检
    // 重复选中，要么再也不被选中。收成两个私有扩展之后还剩一个问题：后台那批操作也写这几个
    // 字段，而它们已经拆到了 [top.tcyeee.bookmarkify.server.admin.BookmarkAdminService]。
    // 所以实现必须住在一个两边都够得着的地方，否则拆分的代价就是把这五行再抄一遍。
    //
    // 保留这层 `PageEntity.` 接收者形式的薄壳，是因为下面那条纪律（"每个写 parse_status 的
    // 地方都必须调 markParse*"）按这个名字写了十来处注释，直接展开成组件调用会让它们全部失准。
    //
    // 新增解析路径时**不要**再手写这四个字段，调这两个方法。

    /** 解析成功后推进调度：内容确实被刷新了。 */
    private fun PageEntity.scheduleAfterParseSuccess() = scheduleWriter.advanceAfterParseSuccess(this)

    /**
     * 解析判定站点不可达后推进调度：计入连续失败，走指数退避。
     *
     * 这里**不做归档**：归档是「候选池该不该继续包含这条记录」的调度决定，归巡检
     * （`LivenessSweepService.persistProbeResult`）负责。解析失败而 ping 仍然通得过，
     * 说明站点活着、只是我方抓不动，那种情况值得继续按最长退避间隔偶尔重试，而不是就地判死。
     */
    private fun PageEntity.scheduleAfterParseFailure() = scheduleWriter.advanceAfterParseFailure(this)

    /** 落成「抓到了」。**不写库**，见 [PageParseStateWriter.markSucceeded]。 */
    private fun PageEntity.markParseSucceeded() = parseStateWriter.markSucceeded(this)

    /**
     * 落成「这个站点抓不到」，够次数了才真的判失活。见 [PageParseStateWriter.markUnreachable]。
     *
     * ⚠️ 只用于**目标站点**的失败。「我方抓取服务不可用」(E307) 绝不能走到这里 ——
     * 判据见 [isScrapperUnavailable]，每个调用点都在进来之前先挡了一道。
     */
    private fun PageEntity.markParseUnreachable(errMsg: String?) = parseStateWriter.markUnreachable(this, errMsg)

    /**
     * 抓取结果落库前，把管理员手工锁定的字段还原成人工值。
     *
     * [manual] 是抓取开始之前从库里读出来的那份快照。定期重抓一旦开启，没有这一步，管理员改过的
     * 标题就会在下一个刷新周期被静默覆盖——而用户看到的是「后台明明改好了，过一个月又变回去了」。
     *
     * 锁本身也一并还原：自动链路只读锁、不改锁。
     */
    private fun PageEntity.restoreLockedFields(manual: PageEntity) {
        if (manual.isLocked(PageLockedField.TITLE)) title = manual.title
        if (manual.isLocked(PageLockedField.DESCRIPTION)) description = manual.description
        if (manual.isLocked(PageLockedField.APP_NAME)) appName = manual.appName
        lockedFields = manual.lockedFields
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
        //    刻意留在下面的事务**之外**：它靠捕获唯一键冲突后回查来收敛，而在 PostgreSQL 里一旦
        //    事务内触发约束冲突，整个事务就进入 aborted 状态，回查那条 SELECT 也会一并失败。
        //    即便后续步骤失败，多出来的只是一条无人引用的 canonical 记录，下次添加同一网址会复用它。
        val bookmark = getOrCreateByUrl(bookmarkUrl)
        log.debug("[addOne] Step2 书签记录就绪: pageId=${bookmark.id}, urlHost=${bookmark.urlHost}, parseStatus=${bookmark.parseStatus}")

        // 2.5 该用户是否已经收藏过这个页面。判定落在 canonical pageId 上而不是 URL 字符串上：
        //     同一个页面用户可能写作 github.com/x、https://github.com/x、https://github.com/x/，
        //     字符串各不相同，canonical 记录却是同一条。此前完全没有这道检查，同一个网址点两次
        //     就在桌面上留下两个一模一样的磁贴（导入路径反倒有重复检测，两条入口行为不一致）。
        //     除了按 canonical id 比对，还会盖住导入占位那一类：它们的 page_id 还是 'LOADING'，
        //     光比 canonical id 匹配不上（见 assertNotPendingImport）。
        assertNotAlreadyLinked(uid, bookmark)

        // 3. 判断书签是否需要重新解析（首次添加 / 上次解析距今超过有效期 / 已有记录处于失效状态）。
        //    先判断再插入：需要解析的节点以 BOOKMARK_LOADING 落库等待推送，不需要的直接落 BOOKMARK，
        //    省掉原先「先插 LOADING 再 update 成 BOOKMARK」那次多余的写。
        val needParse = bookmark.checkFlag() || bookmark.needRecheckOnAdd()
        val nodeEntity = UserLayoutNodeEntity(
            uid = uid,
            type = if (needParse) NodeTypeEnum.BOOKMARK_LOADING else NodeTypeEnum.BOOKMARK,
        )
        // 用户与书签的关联记录（bookmark_user_link），保存该用户自定义的完整 URL、标题、描述等个性化数据。
        // 存 urlRaw 而不是入参 url：两者的差别只有「协议头补全」这一步，参数一个不少。用户手输
        // 时省略协议是常态（github.com/tcyeee），存原样的话这一列就不是个可跳转的地址，前端把它
        // 放进 href 会被当成站内相对路径，点开变成 https://bookmarkify.cc/github.com/tcyeee。
        val userLink = BookmarkEntity(bookmarkUrl.urlRaw, uid, nodeEntity.id, bookmark)

        // 4. 布局节点与用户关联原子写入，见 insertNodeAndLink。
        insertNodeAndLink(nodeEntity, userLink)
        log.debug("[addOne] Step3 已创建布局节点与用户关联: nodeId=${nodeEntity.id}, userLinkId=${userLink.id}, type=${nodeEntity.type}")

        // 5. 需要解析 → 立即返回 loading 占位 VO，同时发布异步解析事件。
        //    解析完成后由 parseAndNotice 通过 WebSocket 将最终结果推送到客户端。
        //    事件在事务提交之后发布，避免回滚后监听器读到不存在的记录。
        if (needParse) {
            log.debug("[addOne] Step5 书签需要解析，返回 LOADING 占位，已发布异步解析事件: pageId=${bookmark.id}, parseStatus=${bookmark.parseStatus}, isActivity=${bookmark.isActivity}, userLinkId=${userLink.id}, nodeId=${nodeEntity.id}")
            return nodeEntity.loadingVO(bookmark.urlHost)
                .also { eventPublisher.publishEvent(BookmarkParseAndNoticeEvent(uid, bookmark.id, userLink.id, nodeEntity.id)) }
        }

        // 6. 书签在有效期内，无需重新抓取，直接返回完整数据。
        log.debug("[addOne] Step6 书签在有效期内，无需重新解析，直接返回完整数据: pageId=${bookmark.id}, nodeId=${nodeEntity.id}")
        return showForDesktop(userLink.id).let { UserLayoutNodeVO(nodeEntity, it) }
    }


    override fun findListByHost(defaultBookmarkify: List<String>): List<PageEntity> =
        ktQuery().`in`(PageEntity::urlHost, defaultBookmarkify).list()

    // ────── 异步解析入口（由 BookmarkParseEventListener 调用）──────

    override fun parseAndSave(pageId: String) {
        parseBookmark(baseMapper.selectById(pageId))
    }

    /**
     * 抓取成功后的元数据富化：分类打标 + NSFW 判定。
     *
     * 两者都只写各自的字段、互不影响，任一失败也不该拖累另一个，故各自 runCatching 收口。
     */
    override fun enrich(pageId: String) {
        val bookmark = baseMapper.selectById(pageId) ?: run {
            log.debug("[enrich] 书签已不存在，跳过: pageId=$pageId")
            return
        }
        runCatching { bookmarkCategoryService.categorize(bookmark) }
            .onFailure { log.warn("[enrich] 分类打标失败(忽略): pageId=$pageId, err=${it.message}") }
        checkNsfw(bookmark)
    }

    override fun coverOf(linkId: String, uid: String): String? {
        if (linkId.isBlank() || uid.isBlank()) return null
        // uid 一并进 where：拿别人的 linkId 来问，查不到就是 null，而不是别人的封面。
        // deleted 也必须显式带上：本项目没有配 MyBatis-Plus 的逻辑删除，不写这一条，
        // 已删除的书签照样能查出封面来
        val link = bookmarkUserLinkMapper.selectOne(
            KtQueryWrapper(BookmarkEntity::class.java)
                .eq(BookmarkEntity::id, linkId)
                .eq(BookmarkEntity::uid, uid)
                .eq(BookmarkEntity::deleted, false)
        ) ?: return null
        val pageId = link.pageId?.takeIf { it.isNotBlank() } ?: return null
        return siteAssetResolver.resolveCoverOne(pageId)
    }

    override fun captureScreenshot(pageId: String) {
        val bookmark = baseMapper.selectById(pageId) ?: run {
            log.debug("[captureScreenshot] 书签已不存在，跳过: pageId=$pageId")
            return
        }
        // 用户手动认证过的书签不再被自动抓取覆盖，截图同样不该去动它
        if (bookmark.verifyFlag) return
        val url = bookmark.rawUrl.takeIf { it.isNotBlank() } ?: return

        // 已经有封面截图就不再截：内容定期重抓（默认 30 天一轮）会让每条成功的书签重新投递一次
        // 截图事件，而截图是全系统最贵的一次调用——强制无头浏览器，对端 Chrome 全局串行、
        // 生产容器只有 1GB。不拦这一道，稳态下截图池会长期占着对端那把锁，把用户当场触发的
        // 反爬无头回退饿死在锁上（那条路是有人在等结果的）。页面改版换封面的收益远抵不过这个代价。
        if (siteAssetResolver.assetsOf(pageId).any { it.role == AssetRole.SCREENSHOT }) {
            log.debug("[captureScreenshot] 已有截图，跳过: pageId=$pageId")
            return
        }

        // 上面那道「已有截图就跳过」的闸门只拦得住**成功过**的页面：截不出图时下面什么也不写，
        // 于是它对失败页面永远不成立，每一轮内容重抓都要为同一个页面再付一次 30s 的无头。
        // 2026-08-07 查生产，297 个页面里只有 66 个有截图 —— 其余每轮全都在重试。
        // 这道锁把「截不出来」这件事本身记下来，取 TTL 而非永久：站点改版、反爬策略调整、
        // 我方无头链路修好，都可能让原本截不出的页面变得可截，一个永久标记会把这些永远挡在门外。
        val futileKey = ParseLock.screenshot(pageId)
        val futileToken = parseLock.acquire(futileKey, SCREENSHOT_FUTILE_TTL) ?: run {
            log.debug("[captureScreenshot] 近期已确认截不出图，跳过(省一次无头): pageId=$pageId")
            return
        }

        // BYPASS 是必需的：scrapper 的缓存键含"要不要截图"，但正常抓取那份结果可能仍在
        // 有效期内，DEFAULT 会命中它并原样返回一个没有截图的响应。
        // extractAssets = false：页面声明的那些图主抓取已经落过库，再探测一轮只是白跑几十次 HTTP。
        val response = runCatching {
            apiService.scrape(
                url,
                apiService.scrapeRequest(url, CacheMode.BYPASS, screenshot = true, extractAssets = false),
            )
        }.getOrElse {
            log.debug("[captureScreenshot] 抓取失败(不影响书签): pageId=$pageId, err=${it.message}")
            return
        }

        if (response.screenshot?.storageKey == null) {
            // 常态而非异常：反爬站点、无头熔断、站点 API 救援都会走到这里
            log.debug(
                "[captureScreenshot] 本次没有截图: pageId=$pageId, " +
                    "layer=${response.fetch.layerUsed}, warnings=${response.diagnostics?.warnings}"
            )
            return
        }

        runCatching { siteAssetWriter.upsertScreenshot(pageId, url, response) }
            // 成功之后抑制标记就该让位：此后由「已有截图资产」那道闸门接管，它更准确
            // （直接看落库结果），也不会在 TTL 到期后凭空放一次无头进来
            .onSuccess {
                parseLock.release(futileKey, futileToken)
                if (it) log.debug("[captureScreenshot] 封面已更新: pageId=$pageId")
            }
            .onFailure { log.warn("[captureScreenshot] 截图落库失败: pageId=$pageId, err=${it.message}") }
    }

    /** 解析书签，然后保存到数据库，同时通知到用户 */
    override fun parseAndNotice(uid: String, pageId: String, userLinkId: String, nodeId: String) {
        log.debug("[parseAndNotice-4] 开始书签解析: uid=$uid, pageId=$pageId, userLinkId=$userLinkId, nodeId=$nodeId")
        // 交互式路径绕过 scrapper 的缓存。要绕的主要是那 60 秒的**负缓存**：它是
        // "刚刚有人抓这个网址失败过"，命中即 RECENTLY_FAILED → E304 → 这条书签直接落成
        // UNREACHABLE —— 而当前这个用户根本没有得到过一次真实的尝试，别人的一次失败记在了
        // 他账上。用户此刻正盯着那个转圈的格子，多花几 KB 换一次真实结论是划算的。
        // 导入路径(parseAndResetUserItem)刻意**不**这么做：那里几千条一起跑，缓存正是要用的。
        val resolved = runCatching { parseBookmark(baseMapper.selectById(pageId), CacheMode.BYPASS) }.onFailure { ex ->
            // 解析链路中的未预期异常（而非「抓取失败」这类已内部兜底为 UNREACHABLE 的正常业务失败）不能让节点
            // 永久停在 BOOKMARK_LOADING——此前这里的异常会一路冒泡到事件监听器，被其 runCatching 吞掉且
            // 不回写任何状态，用户端只会看到一个转不动的加载占位符。与 parseAndResetUserItem 保持一致，
            // 退化为与「ping 不通」一致的处理：落一条 UNREACHABLE 记录，让节点照常收口而不是无限转圈。
            log.error("[parseAndNotice-4] 解析异常，标记为不可用: pageId=$pageId", ex)
            baseMapper.selectById(pageId)
                ?.markParseUnreachable("parse failed: ${ex.message}")
                ?.also { baseMapper.insertOrUpdate(it) }
        }.getOrNull()

        // parseByApi 在「抓取服务本身不可用」(isScrapperUnavailable) 时会刻意把书签留在 PENDING，
        // 交给 checkAll() 之后重投递——那是我方故障，不是这个网站真的挂了。但 checkAll() 重投递的是
        // BookmarkParseEvent（只调 parseAndSave，不通知任何用户），如果这里照常把节点收口成 BOOKMARK
        // 并推送，用户看到的就是一个永久定格的"断网"占位符：不仅把我方故障误报成网站失联，节点还从
        // BOOKMARK_LOADING 状态消失，永远脱离 drainStuckLoading() 的重投递范围——checkAll() 事后即使
        // 重新抓取成功，也没有任何机制会把结果回传给这个已经"收口"的节点。
        // 因此仍是 PENDING 时直接返回，节点继续留在 LOADING，等 drainStuckLoading() 按陈旧阈值补投递。
        if (resolved?.parseStatus == ParseStatusEnum.PENDING) {
            log.debug("[parseAndNotice-4] 书签仍为 PENDING(多半是抓取服务暂不可用)，节点保持 LOADING 等待重投递: nodeId=$nodeId, pageId=$pageId")
            // 这一次补投递没有得到任何关于这个网址的结论，不能算在它头上。少了这一步，一次几十
            // 分钟的 scrapper 故障会把积压里每条记录的重试预算耗光，恢复后它们已经被
            // terminateExhaustedLoading 当作"重试到上限"终结成无源书签了 —— 我方故障不该有这种后果
            forgiveDispatchAttempt(userLinkId)
            return
        }

        // 抓取要花几十秒，这期间用户完全可能把这个还在转圈的书签删掉。节点没了就无事可做，
        // 早退出即可——原先这里直接对 selectById 的结果解引用，会抛 NPE 冒泡到监听器被吞掉，
        // 表面上什么都没发生，日志里却多一条看不懂的堆栈。
        val layoutEntity = layoutNodeMapper.selectById(nodeId) ?: run {
            log.debug("[parseAndNotice-4] 布局节点已被删除，放弃推送: nodeId=$nodeId, uid=$uid")
            return
        }
        log.debug("[parseAndNotice-4] 书签解析完成, 开始构建展示数据: userLinkId=$userLinkId")
        val bookmarkShow = showForDesktop(userLinkId)
        log.debug("[parseAndNotice-4] 已查询 bookmarkShow, title=${bookmarkShow.title}, 开始更新布局节点类型: nodeId=$nodeId")
        layoutEntity.also {
            it.type = NodeTypeEnum.BOOKMARK
            layoutNodeMapper.updateById(it)
        }
        log.debug("[parseAndNotice-4] 布局节点已更新为 BOOKMARK, 准备推送 WebSocket: uid=$uid, nodeId=$nodeId")
        UserLayoutNodeVO(layoutEntity, bookmarkShow).also { SocketUtils.homeItemUpdate(uid, it) }
        log.debug("[parseAndNotice-4] WebSocket 推送完成: uid=$uid, nodeId=$nodeId")
    }

    /**
     * 通过网址解析为书签，同时重新绑定到添加这个网址的用户
     * 1. 解析书签，更新书签状态（之前是 PENDING）
     * 2. 根据 host 重新绑定用户自定义书签
     * 3. 修改用户布局元素状态（之前是 LOADING）
     *
     * 为什么要重新绑定？
     * 答: 用户添加网址的时候是批量添加的,只能提前批量返回用户自定义的书签,用户自定义的书签具体有没有存在源书签还不知道,所以查询完毕知道以后,再重新关联回去
     */
    override fun parseAndResetUserItem(
        uid: String, rawUrl: String, userLinkId: String, layoutNodeId: String
    ) {
        // 网址本身就解析不出来（浏览器书签栏里的 javascript: 小书签、about: 页面之类，导入时不做过滤），
        // 重试多少次都是同样的结果。必须在这里终结掉：drainStuckLoading 是靠「节点还停在
        // BOOKMARK_LOADING」来找待办的，任由异常冒泡的话这条会被每一轮对账无限重投。
        // 收口方式是把节点翻成 BOOKMARK 但不绑定 canonical 书签——bookmark_user_link 里存着用户
        // 自己的标题和网址，足够渲染成一个普通磁贴，这正是这类书签能有的最好结果。
        val urlWrapper = runCatching { WebsiteParser.urlWrapper(rawUrl) }.getOrElse { ex ->
            log.warn("[parseAndResetUserItem] 网址无法解析，作为无源书签收口: rawUrl=$rawUrl, err=${ex.message}")
            finishNodeWithoutBookmark(uid, userLinkId, layoutNodeId)
            return
        }
        val entity = runCatching {
            getOrCreateByUrl(urlWrapper).also { if (it.parseStatus == ParseStatusEnum.PENDING) parseBookmark(it) }
        }.getOrElse { ex ->
            // 解析链路中的未预期异常不能让节点永久停在 BOOKMARK_LOADING——此前这里的异常会一路
            // 冒泡到事件监听器，被 runCatching 吞掉且不回写任何状态，用户端只会看到一个转不动的
            // 加载占位符。这里退化为与「ping 不通」一致的处理：落一条 UNREACHABLE 记录，让节点照常收口。
            log.error("[parseAndResetUserItem] 解析异常，标记为不可用: urlHost=${urlWrapper.urlHost}, urlPath=${urlWrapper.urlPath}", ex)
            // 只认已存在的记录：能走到这里的绝大多数情况是「canonical 记录建好了、抓取那步炸了」，
            // 回查必然命中。反过来连记录都没有，说明连 site/bookmark 的插入本身都失败了（库层面
            // 的问题），此时没有 siteId 可用，硬造一条 site_id 为空的孤儿页面记录只会污染数据 ——
            // 按「网址解析不出来」的同一套方式收口，让节点照常翻成普通磁贴。
            val existing = getByUrl(urlWrapper) ?: run {
                log.warn("[parseAndResetUserItem] canonical 记录不存在且无法创建，作为无源书签收口: rawUrl=$rawUrl")
                finishNodeWithoutBookmark(uid, userLinkId, layoutNodeId)
                return
            }
            existing.markParseUnreachable("parse failed: ${ex.message}")
                .also { baseMapper.insertOrUpdate(it) }
        }

        // 与 parseAndNotice 同理：parseByApi 在「抓取服务本身不可用」时会刻意把 entity 留在 PENDING，
        // 交给 drainStuckLoading 之后重投递。这里若仍照常重绑 + 收口成 BOOKMARK，节点会永久脱离
        // BOOKMARK_LOADING 状态、也脱离 drainStuckLoading 的重投递范围，且 page_id 提前绑死在一条
        // 还没抓到内容的记录上。保持不重绑、不收口、直接返回，节点(及 page_id='LOADING' 占位)
        // 原样留给下一轮 drainStuckLoading 重新触发本方法。
        if (entity.parseStatus == ParseStatusEnum.PENDING) {
            log.debug("[parseAndResetUserItem] 书签仍为 PENDING(多半是抓取服务暂不可用)，节点保持 LOADING 等待重投递: rawUrl=$rawUrl")
            // 同 parseAndNotice：我方故障不消耗这条记录的重试预算
            forgiveDispatchAttempt(userLinkId)
            return
        }

        // 抓取已结束，下面两处写入（重绑 userLink + 更新节点类型）需原子提交，放进短事务。
        // 节点找不到不是异常：抓取要花几十秒，这期间用户完全可能把还在转圈的书签删掉。
        // 原先抛 E999 只会在日志里留下一条误导性的错误堆栈，实际什么都不用做。
        val layoutNode: UserLayoutNodeEntity? = try {
            txTemplate.execute {
                layoutNodeMapper.selectById(layoutNodeId)
                    ?.apply { type = NodeTypeEnum.BOOKMARK }
                    ?.also {
                        bookmarkUserLinkService.resetPageId(uid, userLinkId, entity.id)
                        layoutNodeMapper.updateById(it)
                    }
            }
        } catch (e: DuplicateKeyException) {
            // 事务已整体回滚，占位行仍是 page_id='LOADING'，节点仍是 BOOKMARK_LOADING
            discardDuplicatePlaceholder(uid, userLinkId, layoutNodeId, entity.id, e)
            return
        }
        if (layoutNode == null) {
            log.debug("[parseAndResetUserItem] 布局节点已被删除，放弃推送: nodeId=$layoutNodeId, uid=$uid")
            return
        }
        showForDesktop(userLinkId)
            .let { UserLayoutNodeVO(layoutNode, it) }
            .also { SocketUtils.homeItemUpdate(uid, it) }
    }

    // ────── 公开接口（明确指定解析方式时调用）──────

    /** 通过 scrapper 远程解析书签，若书签已通过手动认证则直接返回 */
    override fun parseBookmarkByApi(bookmark: PageEntity): PageEntity {
        val existing = baseMapper.selectById(bookmark.id)
        if (existing != null && existing.verifyFlag) return existing
        return parseByApi(bookmark)
    }

    // ────── 私有解析层 ──────

    /**
     * 统一解析调度入口，负责保证**同一 canonical 书签同一时刻只有一次抓取在跑**。
     *
     * 多个用户同时添加同一个 URL 时，getOrCreateByUrl 会收敛成一条 bookmark 记录，但每个用户各发
     * 一个解析事件：没有这把锁，同一个页面会被 ping/抓取多次，更糟的是多个 SiteAssetWriter.persist
     * 的「先删旧资产再插新资产」事务交错执行，资产行可能翻倍或整体丢失，OSS 孤儿回收的引用计数
     * 也会把对方刚上传的对象当成无人引用删掉。
     *
     * 抢不到锁时直接返回库里当前的记录，不阻塞等待：调用方（parseAndNotice / parseAndResetUserItem）
     * 据此照常翻转节点并推送，用户先看到基础信息，在跑的那次解析完成后刷新即是完整数据。
     * 让一个解析线程空等几十秒去换这一次的完整度，不划算。
     */
    private fun parseBookmark(bookmark: PageEntity, cacheMode: CacheMode = CacheMode.DEFAULT): PageEntity {
        val lockKey = ParseLock.bookmark(bookmark.id)
        val token = parseLock.acquire(lockKey, PARSE_LOCK_TTL) ?: run {
            log.debug("[parseBookmark] 该书签已有解析在途，跳过本次: pageId=${bookmark.id}, urlHost=${bookmark.urlHost}")
            return baseMapper.selectById(bookmark.id) ?: bookmark
        }
        return try {
            parseBookmarkExclusively(bookmark, cacheMode)
        } finally {
            parseLock.release(lockKey, token)
        }
    }

    /**
     * 实际的解析流程（已持有该书签的解析锁）：检查 verifyFlag 与链接类型后直接抓取。
     *
     * **不再前置 ping**。这一步原本是为了「站点已经挂了就别浪费 headless 开销」，但两条解析路径
     * 本来就会把抓不到的情况自行收口成 UNREACHABLE——scrapper 路径由 classifyScrapperError 把
     * FETCH_FAILED/TIMEOUT 判成 E304「目标站点打不开」，Jsoup 路径直接捕获抓取异常——所以 ping 对
     * 结果的正确性没有任何贡献，只是在**每一条**书签（绝大多数是好站点）上白加一个最长 15s 的往返。
     * 更别说 retryUnreachableBookmarks 是先 ping 通了才投递解析事件的，进到这里等于连着 ping 两次。
     *
     * ping 保留给定时活性巡检 [pingSweep]：那里它是主角（判定站点死活并写 bookmark_ping_log），
     * 而不是抓取前的一道预检。
     */
    private fun parseBookmarkExclusively(
        bookmark: PageEntity,
        cacheMode: CacheMode = CacheMode.DEFAULT,
    ): PageEntity {
        log.debug("[parseBookmark] 开始调度解析: pageId=${bookmark.id}, urlHost=${bookmark.urlHost}")
        val existing = baseMapper.selectById(bookmark.id)
        if (existing != null && existing.verifyFlag) {
            log.debug("[parseBookmark] 书签已手动认证(verifyFlag=true), 跳过解析直接返回: pageId=${bookmark.id}")
            return existing
        }

        // 非域名类型(本地/IP/其他)不进行网络抓取：直接标记为可用，
        // 前端会对这类书签展示统一的圆圈图标，不依赖抓取到的标题/图标。
        if (WebsiteParser.classifyLinkType(bookmark.urlHost) != BookmarkLinkType.DOMAIN) {
            log.debug("[parseBookmark] 非域名类型，跳过抓取: pageId=${bookmark.id}, urlHost=${bookmark.urlHost}")
            // markParseSucceeded 顺带写上调度列：这类书签被 pingSweep 的 DOMAIN 过滤排除在外，
            // 调度列对它们没有实际作用，但宁可多余，也不要留下一批 next_check_at 永远为 NULL
            // 的记录 —— 那会让「NULL 视为到期」的兜底规则每轮都把它们捞出来
            return bookmark.markParseSucceeded().also { baseMapper.insertOrUpdate(it) }
        }

        val mode = if (projectConfig.useThirdPartyParser) "远程scrapper" else "本地Jsoup"
        log.debug("[parseBookmark] 选择解析模式: $mode, pageId=${bookmark.id}")
        val parsed = if (projectConfig.useThirdPartyParser) parseByApi(bookmark, cacheMode) else parseLocally(bookmark)
        // 分类与 NSFW 判定都是纯后台元数据，用户看不到，却各要一次 10s 的 DeepSeek 往返。
        // 留在这里等于让每条书签多占解析线程 20s，而解析池的吞吐直接决定「加书签要等多久」。
        // 拆到独立线程池上异步补，主链路抓完就能推送给用户。
        if (parsed.parseStatus == ParseStatusEnum.SUCCESS) {
            eventPublisher.publishEvent(BookmarkEnrichEvent(parsed.id))
            // 截图同样拆出去，但理由更硬：它强制走无头浏览器，而对端的 Chrome 是全局
            // 串行的。留在这里等于让每条书签排队等浏览器。见 BookmarkScreenshotEvent。
            if (scrapperConfig.screenshotAsync && !scrapperConfig.screenshot) {
                eventPublisher.publishEvent(BookmarkScreenshotEvent(parsed.id))
            }
        }
        return parsed
    }

    /**
     * 通过 DeepSeek 判断**站点**是否 NSFW（成人/赌博等），结果写回 `site.nsfw` / `site.nsfw_reason`。
     * 失败静默，不影响解析主流程。
     *
     * 判定挂在站点而不是页面上：涉黄/涉赌是域名的属性，同一个域名下 1000 个页面各判一次，
     * 就是 1000 次 10s 的 LLM 往返换同一个结论。**同一站点只判一次** —— 已经判过的（有 reason
     * 或已标记）直接跳过，这也让"重抓一批深链"不再连带触发一批重复判定。
     *
     * 判定输入仍然用页面的标题/描述：站点自己没有文字，首页或任一页面的文案就是判据。
     */
    private fun checkNsfw(bookmark: PageEntity) {
        val siteId = bookmark.siteId.takeIf { it.isNotBlank() } ?: run {
            log.debug("[checkNsfw] 书签未挂站点，跳过: pageId=${bookmark.id}")
            return
        }
        runCatching {
            val site = siteService.getById(siteId) ?: return
            // 已判过就不再判：结论对整个域名是一样的
            if (site.nsfw || site.nsfwReason != null) {
                log.debug("[checkNsfw] 该站点已判定过，跳过: siteId=$siteId, host=${site.host}, nsfw=${site.nsfw}")
                return
            }
            val result = apiService.inferNsfw(bookmark.title, bookmark.description, bookmark.urlHost)
            siteService.markNsfw(siteId, result.nsfw, result.reason)
        }.onFailure {
            log.warn("[checkNsfw] NSFW 检测失败(忽略): pageId=${bookmark.id}, siteId=$siteId, err=${it.message}")
        }
    }

    /**
     * 管理员「一键分类」的 NSFW 全量重判。
     *
     * 遍历的是**站点**而不是书签：判定本就是域名级的，按书签遍历会在同一域名上重复烧 LLM 往返。
     * 判定输入取该站点下任意一个已抓到标题的页面（优先首页）。
     *
     * @return (站点总数, 命中数)
     */
    override fun checkNsfwForAll(): Pair<Int, Int> {
        val sites = siteService.list()
        // 每个站点挑一条有标题的页面当判定输入，首页优先；一次查询取回全部页面避免 N+1
        val sampleBySite = list()
            .filter { !it.title.isNullOrBlank() && it.siteId.isNotBlank() }
            .groupBy { it.siteId }
            .mapValues { (_, pages) -> pages.firstOrNull { it.isRootPage } ?: pages.first() }

        var flagged = 0
        sites.forEach { site ->
            val sample = sampleBySite[site.id] ?: return@forEach
            runCatching {
                val result = apiService.inferNsfw(sample.title, sample.description, site.host)
                if (result.nsfw) flagged++
                siteService.markNsfw(site.id, result.nsfw, result.reason)
            }.onFailure { log.warn("[checkNsfwForAll] NSFW 检测失败(忽略): siteId=${site.id}, host=${site.host}, err=${it.message}") }
        }
        return sites.size to flagged
    }

    /**
     * 本地解析（Jsoup）：只抓文字元信息。
     *
     * **不产出图片资产** —— 图片统一由 scrapper 路径按契约落 `site_asset`。两套解析各写
     * 一份图标正是本次重构要消除的问题（模型不同、字段不同、互相覆盖）。走这条路径的书签
     * 只有标题/描述，图标需要后续由 scrapper 补齐。
     */
    private fun parseLocally(bookmark: PageEntity): PageEntity {
        log.debug("[parseLocally] 开始本地解析(Jsoup): pageId=${bookmark.id}, rawUrl=${bookmark.rawUrl}")
        // 同 parseByApi：successInit 会覆盖 title/description/appName，先留一份人工值
        val manual = bookmark.copy()
        val wrapper = runCatching { WebsiteParser.parse(bookmark.rawUrl) }.getOrElse {
            log.debug("[parseLocally] 页面抓取失败: pageId=${bookmark.id}, err=${it.message}")
            bookmark.markParseUnreachable(it.message).also { b -> baseMapper.insertOrUpdate(b) }
            log.warn("[parseLocally] 页面抓取失败: pageId=${bookmark.id}, err=${it.message}")
            return bookmark
        }
        log.debug("[parseLocally] 页面抓取成功, 开始填充元信息: pageId=${bookmark.id}, title=${wrapper.title}")
        val previousTitle = bookmark.title
        bookmark.successInit(wrapper)
        bookmark.scheduleAfterParseSuccess()
        if (!manual.isLocked(PageLockedField.APP_NAME)) inferAndSetAppName(bookmark, previousTitle)
        bookmark.restoreLockedFields(manual)
        baseMapper.insertOrUpdate(bookmark)
        // 本地解析路径（Jsoup）不产出契约资产，图标改由 scrapper 路径统一落 site_asset；
        // 这里只保住主表的文字信息，避免两套解析各写一份互相打架
        log.debug("[parseLocally] 本地解析全部完成: pageId=${bookmark.id}, parseStatus=${bookmark.parseStatus}, appName=${bookmark.appName}")
        return bookmark
    }

    /** 抓取耗时，落进 `scrape_snapshot.duration_ms`。此前这里一律硬编码 0，那一列等于没数据。 */
    private fun elapsedMs(startedAt: Long): Int = PageParseStateWriter.elapsedMs(startedAt)

    /**
     * 落一条失败快照。
     *
     * 只记"这个站点抓不到"这一事实；我方服务不可用（[isScrapperUnavailable]）的情况不该走到这里。
     * 快照纯属诊断数据，写不进去也不能反过来影响解析主流程，故失败只记日志。
     */
    private fun recordScrapeFailure(bookmark: PageEntity, e: Throwable, startedAt: Long) =
        parseStateWriter.recordScrapeFailure(bookmark, e, startedAt)

    /**
     * 远程解析（scrapper）：通过自部署的 bookmarkify-scrapper 获取元信息 + favicon base64 + LOGO/OG 存 OSS
     */
    private fun parseByApi(bookmark: PageEntity, cacheMode: CacheMode = CacheMode.DEFAULT): PageEntity {
        log.debug("[parseByApi] 开始远程解析(scrapper): pageId=${bookmark.id}, rawUrl=${bookmark.rawUrl}, cacheMode=$cacheMode")
        val startedAt = System.currentTimeMillis()
        // 抓取会覆盖 title/description/appName，先留一份人工值，落库前还原被锁定的那些
        val manual = bookmark.copy()
        // 这次实际请求出去的地址。不能在下面重新读一次 `bookmark.rawUrl`：applyTo 可能把
        // urlScheme 升成 https，而 rawUrl 是拿它现拼的，快照里就会记成一个我们本次并没有
        // 请求过的地址 —— 那一列存在的意义正是"这次到底抓的哪个 URL"
        val requestedUrl = bookmark.rawUrl
        return runCatching {
            apiService.scrape(requestedUrl, apiService.scrapeRequest(requestedUrl, cacheMode))
        }.fold(
            onSuccess = { vo ->
                log.debug("[parseByApi] scrapper 返回成功: pageId=${bookmark.id}, title=${vo.title}, source=${vo.primarySource}, assets=${vo.assets.size}")
                val previousTitle = bookmark.title
                // applyTo 可能把 urlScheme 从 http 升成 https（抓取真正落地在哪个协议上），
                // 记下升级前的值，下面据此决定要不要把站点那一层也一并升上去
                val previousScheme = bookmark.urlScheme
                vo.applyTo(bookmark)
                bookmark.scheduleAfterParseSuccess()
                // 简称优先用 manifest.short_name（W3C 就是为"图标下方空间受限"定义的），
                // 拿不到才退回 DeepSeek 推断。这一步最长 10s，必须留在事务外——
                // 否则一个数据库连接要陪着外部 API 一起干等。
                bookmark.appName = vo.shortName?.takeIf { n -> n.isNotBlank() }
                // appName 已被人工锁定时连推断都不必做：结果反正要被 restoreLockedFields 丢掉，
                // 白烧一次 10s 的 LLM 往返还占着解析线程
                if (bookmark.appName.isNullOrBlank() && !manual.isLocked(PageLockedField.APP_NAME)) {
                    inferAndSetAppName(bookmark, previousTitle)
                }
                bookmark.restoreLockedFields(manual)

                // 主表字段与「快照 + 元数据 + 资产」必须一起提交。分成两个事务时，中间失败会留下
                // parse_status=SUCCESS 却一条 site_asset 都没有的书签：前端永远渲染首字母色块，
                // 而 checkAll/retryUnreachable/livenessCheck 三个对账任务都按 parse_status 过滤，
                // 没有任何一个会回来补这条。抓取此时已经结束，合并进一个短事务不增加持锁时间。
                txTemplate.execute {
                    baseMapper.insertOrUpdate(bookmark)
                    siteAssetWriter.persist(bookmark.siteId, bookmark.id, requestedUrl, vo, elapsedMs(startedAt), bookmark.isRootPage)
                }
                // 站点级文字信息（品牌名/短名）落到 site 那一层。写入强度按「抓的是不是首页」分档：
                // 首页是权威来源、可以覆盖；深链只在站点侧还没有值时回填 —— 否则某个视频页里写歪的
                // og:site_name 会把整站品牌名带跑。失败不能影响页面本身的解析结果，故降级为日志。
                runCatching {
                    siteService.applyCrawledMeta(
                        siteId = bookmark.siteId,
                        brandName = vo.meta?.siteName,
                        shortName = vo.shortName,
                        fromRootPage = bookmark.isRootPage,
                    )
                }.onFailure { log.warn("[parseByApi] 站点信息回写失败(忽略): siteId=${bookmark.siteId}, err=${it.message}") }
                // 页面协议被升级了，说明这个 host 现在确实在 https 上服务；站点那一层的 scheme
                // 建行之后从没人回写过，顺手一并升上去（只升不降，判据见 ScrapeResponseExt）。
                // 同样降级为日志：站点层写失败不该把这一页已经抓好的结果拖下水
                if (previousScheme != bookmark.urlScheme) {
                    runCatching { siteService.upgradeSchemeToHttps(bookmark.siteId) }
                        .onFailure { log.warn("[parseByApi] 站点协议升级失败(忽略): siteId=${bookmark.siteId}, err=${it.message}") }
                }
                log.debug("[parseByApi] 第三方API解析全部完成: pageId=${bookmark.id}, assets=${vo.assets.size}")
                bookmark
            },
            onFailure = { e ->
                // 抓取服务没起/配错时保持 PENDING 原样，交给 checkAll() 之后重来，
                // 别把我方故障记成书签失联（异步链路不抛，抛了也没人接）。
                // 也不落失败快照：那记录的是我方故障，不是这个站点的抓取事实
                if (e.isScrapperUnavailable()) {
                    log.warn("[parseByApi] 抓取服务不可用，保留待抓取状态: pageId=${bookmark.id}, err=${e.message}")
                    return@fold bookmark
                }
                // 我方**主动拒绝**抓这个目标（E309 非域名 / E308 内网）。同样不是站点的事实，
                // 落成 UNREACHABLE 就是拿我方的一个策略决定给用户自家的服务判死刑 —— 见
                // isRefusedTarget。处置与 parseBookmarkExclusively 里那道非域名前置过滤完全
                // 一致：标成 SUCCESS 收口成普通磁贴，前端渲染统一的圆圈图标，不落失败快照
                // （那记录的是"这个站点抓不到"，而我方压根没去抓）。
                //
                // 能走到这里说明前置过滤没拦住：它看的是 page.url_host 经 classifyLinkType 的
                // 结论，而这里看的是 raw_url 经 ScrapeTargetGuard 的结论，两条提取路径不同。
                // 这属于不该发生的不一致，所以是 warn 而不是 debug。
                if (e.isRefusedTarget()) {
                    log.warn(
                        "[parseByApi] 目标被拒绝抓取，按非域名书签收口: " +
                            "pageId=${bookmark.id}, rawUrl=${bookmark.rawUrl}, urlHost=${bookmark.urlHost}, err=${e.message}"
                    )
                    return@fold bookmark.markParseSucceeded().also { baseMapper.insertOrUpdate(it) }
                }
                log.debug("[parseByApi] API 调用失败: pageId=${bookmark.id}, err=${e.message}")
                // 失败也留快照：只把书签标成 UNREACHABLE 的话，事后只知道"抓不到"，
                // 不知道抓的是哪个 URL、报了什么错、耗了多久。persistFailure 一直没人调用
                recordScrapeFailure(bookmark, e, startedAt)
                bookmark.markParseUnreachable(e.message).also { baseMapper.insertOrUpdate(it) }
            }
        )
    }


    // ────── 私有工具 ──────

    /**
     * 通过 DeepSeek 推断书签简称，有结果则覆盖 appName，失败静默忽略。
     *
     * [previousTitle] 是本次解析开始前（覆盖 title 之前）该书签原有的标题：checkAll/retryUnreachableBookmarks
     * 这类定时对账会对同一 canonical 书签反复重新解析，若网页标题相较上次没有变化、且已经有 appName，
     * 就没必要再打一次 DeepSeek——这既省了一次外部 API 调用，也缩短了异步解析任务占用线程池的时间。
     */
    private fun inferAndSetAppName(bookmark: PageEntity, previousTitle: String? = null) {
        val title = bookmark.title ?: run {
            log.debug("[inferAndSetAppName] title 为空，跳过 appName 推断: pageId=${bookmark.id}")
            return
        }
        if (!bookmark.appName.isNullOrBlank() && title == previousTitle) {
            log.debug("[inferAndSetAppName] 标题未变化且已有 appName，跳过重复推断: pageId=${bookmark.id}, appName=${bookmark.appName}")
            return
        }
        log.debug("[inferAndSetAppName] 调用 DeepSeek 推断 appName: pageId=${bookmark.id}, title=$title")
        apiService.inferAppName(title)?.takeIf { it.isNotBlank() }
            ?.also {
                bookmark.appName = it
                log.debug("[inferAndSetAppName] appName 推断成功: pageId=${bookmark.id}, appName=$it")
            } ?: log.debug("[inferAndSetAppName] appName 推断结果为空，保持原值: pageId=${bookmark.id}")
    }

    /**
     * 免掉这一次补投递的重试计数。
     *
     * 只用于「我方抓取服务不可用」(E307) 的早退路径。重试上限要防的是「这条记录本身有问题、
     * 重试多少次都收不了口」，而 E307 说明**我方**坏了 —— 拿它扣用户书签的重试预算，等于把
     * 一次运维故障变成一批永久降级的无源书签。判据必须只计入「跑到底了仍然没收口」的那些。
     */
    private fun forgiveDispatchAttempt(userLinkId: String) {
        runCatching { bookmarkUserLinkMapper.resetDispatchAttempts(userLinkId) }
            .onFailure { log.warn("[forgiveDispatchAttempt] 重试计数清零失败(忽略): userLinkId=$userLinkId, err=${it.message}") }
    }

    /**
     * 导入的占位抓完之后，发现它指向的页面**这个用户已经收藏过了** —— 丢弃这条多余的占位。
     *
     * 这是 E126 的语义迟到地落在导入路径上。`addOne` 早就有两道防线（[assertNotAlreadyLinked]
     * 前置查、[insertNodeAndLink] 兜 `uk_bookmark_uid_page` 唯一键），但导入路径的绑定不是
     * INSERT 而是 [IBookmarkUserLinkService.resetPageId] 这条 UPDATE：写占位行的时候
     * `page_id` 还是 `'LOADING'`，重不重复要等抓完拿到 canonical id 才知道，前置查根本无从查起。
     * 于是唯一键在这里是**唯一**的防线，而它原先没人接。
     *
     * 后果不是"报个错"那么轻：异常从这里冒到 [BookmarkParseEventListener] 的 runCatching 被吞掉，
     * 节点原样留在 BOOKMARK_LOADING，下一轮 [drainStuckLoading] 又把它捞出来重投，再撞同一个
     * 唯一键 —— 一个没有出口的循环，按 DISPATCH_LOCK_TTL 每 5 分钟刷一屏堆栈。最终由
     * `dispatch_attempts` 耗尽收场，但那条路径 ([finishNodeWithoutBookmark]) 是给「这个网址
     * 永远抓不成书签」准备的，用在这里等于把一条**完全正常、只是重复**的记录降级成无源磁贴：
     * 用户桌面上于是有两个同名格子，其中一个没图标没标题。2026-08-04 线上就是这个状态。
     *
     * 正确的终局是让重复的那个消失，跟 `addOne` 撞到 E126 时不留下任何东西一致。已经存在的那条
     * 书签是先到的，原样保留。
     *
     * 删除必须连节点带关联行一起，且推一次整树重置：这条占位此刻正在用户桌面上转圈，只删库不
     * 推送的话，那个格子会一直转到用户手动刷新为止 —— 比留个降级磁贴还糟。[SocketMsgType.HOME_ITEM_UPDATE]
     * 在这里用不了，它只能表达"某个节点变成了什么"，表达不了"某个节点没了"。
     */
    private fun discardDuplicatePlaceholder(
        uid: String, userLinkId: String, layoutNodeId: String, pageId: String, cause: DuplicateKeyException
    ) {
        // 用插值而非占位符：ServiceImpl 自带的 org.apache.ibatis.logging.Log 把全局 log 扩展
        // 遮蔽掉了，那个接口既没有 info() 也没有占位符重载（见 bookmarkify-api/CLAUDE.md › 日志）
        log.warn("[parseAndResetUserItem] 导入占位与既有书签重复，丢弃占位: uid=$uid, userLinkId=$userLinkId, pageId=$pageId, err=${cause.message}")
        runCatching {
            txTemplate.execute {
                // 按 (nodeId, uid) 删关联行，而不是按 userLinkId 直删：与 UserLayoutNodeServiceImpl
                // 的删除路径用同一个 uid 收窄的助手，越权删不到别人的行
                bookmarkUserLinkService.deleteOneByNodeId(layoutNodeId, uid)
                layoutNodeMapper.deleteById(layoutNodeId)
            }
        }.onFailure {
            // 删不掉就退回原有的终结方式：留个降级磁贴，总好过继续无限重投刷屏
            log.warn("[parseAndResetUserItem] 丢弃重复占位失败，退回无源收口: userLinkId=$userLinkId, err=${it.message}")
            runCatching { finishNodeWithoutBookmark(uid, userLinkId, layoutNodeId) }
            return
        }
        runCatching { SocketUtils.homeLayoutRefresh(uid, userLayoutNodeService.layout(uid)) }
            .onFailure { log.warn("[parseAndResetUserItem] 丢弃重复占位后推送失败(忽略): uid=$uid, err=${it.message}") }
    }

    /**
     * 把一个布局节点从 BOOKMARK_LOADING 收口成 BOOKMARK，但不绑定任何 canonical 书签。
     *
     * 用于「这个网址永远抓不成书签」的终局（如 javascript: 小书签，或补投递到达上限仍不收口）：
     * 留在 LOADING 会被 [drainStuckLoading] 当作待办无限重投，而它的展示数据本来就只能来自
     * 用户自己填的那份。
     *
     * 关联行的 `page_id` 必须从 `'LOADING'` 改成 NULL，不能原样留着：那个字面量的含义是
     * 「等着被绑定」，[assertNotPendingImport] 正是靠它判断「这个网址已经在导入队列里了」。
     * 留着的话，用户日后再添加同一个网址会撞上一个**假的 E126**，而且再也解释不清 ——
     * 队列里那条其实早就终结了。语义收敛成：`'LOADING'` = 待绑定，NULL = 确定没有 canonical 记录。
     */
    private fun finishNodeWithoutBookmark(uid: String, userLinkId: String, layoutNodeId: String) {
        val node = layoutNodeMapper.selectById(layoutNodeId) ?: return
        // 两处写入放进同一个短事务：节点翻了而标记没清，就是上面说的假 E126；
        // 标记清了而节点没翻，这条记录会掉出 findStuckLoading 的 unbound 分支永远转圈
        txTemplate.execute {
            bookmarkUserLinkService.clearUnboundMarker(userLinkId)
            node.type = NodeTypeEnum.BOOKMARK
            layoutNodeMapper.updateById(node)
        }
        showForDesktop(userLinkId)
            .let { UserLayoutNodeVO(node, it) }
            .also { SocketUtils.homeItemUpdate(uid, it) }
    }

    /**
     * 查单条用户书签的桌面展示视图。
     *
     * 桌面现在是「小图 + 全名」的列表形态（`pages/index.vue`），按 [DisplayMode.LIST] 解析图标与文案。
     * SQL 本身不再联图标表（图片已改为 site_asset 一行一图），漏了这一步前端就只能渲染首字母色块。
     */
    private fun showForDesktop(userLinkId: String): BookmarkShow =
        bookmarkUserLinkMapper.findShowById(userLinkId)
            .let { it.initDisplay(it.pageId?.let { id -> siteAssetResolver.resolveOne(id, DisplayMode.LIST) }, DisplayMode.LIST) }

    /** 按 canonical 四元组精确命中一条页面记录。 */
    private fun getByCanonical(siteId: String, urlPath: String, urlQuery: String, urlFragment: String): PageEntity? =
        ktQuery().eq(PageEntity::siteId, siteId)
            .eq(PageEntity::urlPath, urlPath)
            .eq(PageEntity::urlQuery, urlQuery)
            .eq(PageEntity::urlFragment, urlFragment)
            .one()

    private fun getByUrl(siteId: String, w: BookmarkUrlWrapper): PageEntity? =
        getByCanonical(siteId, w.urlPath ?: "/", w.urlQuery, w.urlFragment)

    /** 同上，但站点未知时用（先按 host 找 site；site 都没有就必然没有页面记录）。 */
    private fun getByUrl(w: BookmarkUrlWrapper): PageEntity? =
        siteService.findByHost(w.urlHost)?.let { getByUrl(it.id, w) }

    /**
     * 按 canonical 四元组 (siteId, urlPath, urlQuery, urlFragment) 获取或创建页面记录，
     * 顺带保证它所属的 `site` 行存在。
     *
     * `bookmark` 在这四列上有联合唯一约束：并发插入同一页面时，落败的一方捕获唯一键冲突后回查，
     * 保证「一页一条」。
     *
     * 为什么 query 必须进 key：同一域名下不同路径**和不同参数**是完全不同的页面（不同 GitHub 仓库、
     * 不同 YouTube 视频），各自的标题/图标不能共用同一次抓取结果。此前 key 只有 (host, path)，
     * `?v=A` 与 `?v=B` 收敛成一条，抓取目标还退化成了不存在的 `/watch`。
     *
     * 两次 getOrCreate 都刻意留在事务**之外**（[getOrCreateByHost] 的注释同理）：它们靠捕获唯一键
     * 冲突后回查来收敛，而 PostgreSQL 里一旦事务内触发约束冲突，整个事务就进入 aborted 状态，
     * 回查那条 SELECT 也会一并失败。
     */
    private fun getOrCreateByUrl(urlWrapper: BookmarkUrlWrapper): PageEntity {
        val site = siteService.getOrCreateByHost(urlWrapper.urlHost, urlWrapper.urlScheme)
        // 复活判定放在这里而不是 addOne 里：这个方法是「有人现在要这个网址」的**唯一**入口
        // （单条添加、批量导入、相似站点收录、管理端按 URL 建档都经过它），放在任一个调用方
        // 都会漏掉其余几条，而漏掉的表现是"从别的入口添加归档站点，永远还是灰的"
        getByUrl(site.id, urlWrapper)?.let { return it.also { p -> p.reviveOnAdd() } }
        return try {
            PageEntity(urlWrapper, site.id).also { save(it) }
        } catch (e: DuplicateKeyException) {
            getByUrl(site.id, urlWrapper) ?: throw e
        }
    }

    /**
     * 用户新增书签时，已存在的 canonical 记录是否需要强制重新解析一次。
     *
     * checkFlag() 只看「上次解析距今是否超过 1 天」，对刚刚被判定失效(UNREACHABLE / isActivity=false)的书签会返回
     * false，于是新增的用户直接拿到一条失效数据，只能等定时任务(retryUnreachableBookmarks)下一轮才可能恢复。
     * 而用户主动添加这个网址本身就是「站点现在应该是好的」的强信号，所以这里立即重新解析一次；
     * 抓取成功后由解析链路(parseBookmark → parseByApi/parseLocally)写回数据库，并通过 WebSocket 推送最新结果。
     *
     * 三个例外：
     * - PENDING：还没解析过或正在排队解析，归 checkFlag() 负责，不在这里重复触发。
     * - verifyFlag=true(已人工认证)：parseBookmark() 会直接短路返回，重新解析是无效开销。
     * - 距上次检查不足 [DEAD_RECHECK_COOLDOWN_MINUTES]：避免确实已经挂掉的站点被反复添加时反复 ping/抓取。
     */
    private fun PageEntity.needRecheckOnAdd(): Boolean {
        if (parseStatus == ParseStatusEnum.PENDING || verifyFlag) return false
        if (parseStatus == ParseStatusEnum.SUCCESS && isActivity) return false
        val lastCheck = updateTime ?: return true
        return LocalDateTimeUtil.between(lastCheck, LocalDateTime.now(), ChronoUnit.MINUTES) >= DEAD_RECHECK_COOLDOWN_MINUTES
    }

    private fun findById(pageId: String): PageEntity =
        requireNotNull(ktQuery().eq(PageEntity::id, pageId).one())

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
        // PENDING 正常几分钟内就会被异步解析消费掉；超过这个时长还没变化，基本可判定是解析事件丢失/进程重启丢弃，
        // 而不是网站本身的活性问题——所以用远短于活性检测的分钟级窗口，而不是活性检测的小时/天级窗口。
        private const val CHECKALL_PENDING_STALE_MINUTES = 30L
        // search() 的候选站点数：命中的站点未必已有可用首页，取得比最终返回数大，
        // 免得候选刚好被几个"还没抓到首页"的站点占满
        private const val SEARCH_SITE_CANDIDATE_LIMIT = 20
        // search() 最终返回给前端的条数
        private const val SEARCH_RESULT_LIMIT = 5
        /**
         * 「这个页面截不出图」的抑制时长。
         *
         * 取得比内容重抓周期（默认 30 天）略长一点：目标是让**每一轮**内容重抓都跳过它，
         * 取成 30 天整会卡在边界上，两者一前一后错开一点点就又放进去一次无头。
         */
        private val SCREENSHOT_FUTILE_TTL: Duration = Duration.ofDays(35)
        private const val MAX_IMPORT_BOOKMARK_COUNT = 2000
        // addOne 判重时最多回捞多少条「同域名的导入占位」做规范化比对（见 assertNotPendingImport）。
        // 同一用户在同一域名下同时挂着几百条待抓占位已经极端，够用且不会让判重本身变成慢查询。
        private const val IMPORT_DUPLICATE_SCAN_LIMIT = 200
        // 对应 `bookmark_user_link.url_full varchar(1000)`（见 deploy/schema.sql）。
        // 与 WebsiteParser 里的入口校验同源，只是导入路径不经过那里：它刻意保留 javascript:
        // 这类解析不出来的网址，所以只能在这里单独按列宽兜一道。
        private const val MAX_STORABLE_URL_LENGTH = 1000
        // 补投递时给解析队列留出的余量，供交互式 addOne 抢占——批量导入把队列填满的话，
        // 用户手动添加的那一条就会被 CallerRunsPolicy 甩回 HTTP 请求线程上同步抓取。
        private const val DRAIN_QUEUE_HEADROOM = 50
        // 单轮补投递上限：即使队列很空也不一次性捞几千条，避免一次导入独占整个队列
        private const val DRAIN_MAX_BATCH_SIZE = 200
        // 一条占位最多补投递几次。超过即由 terminateExhaustedLoading 就地终结成无源书签。
        //
        // 取 5 而不是更大：补投递锁 TTL 是 5 分钟，5 次意味着一条记录在放弃前会横跨约 25 分钟、
        // 期间足够覆盖 scrapper 重启这类短暂故障；而真正持续的我方故障(E307)根本不消耗这个
        // 预算（见 forgiveDispatchAttempt），所以能耗尽它的只有「跑到底了仍然收不了口」——
        // 那种记录再试 50 次也是一样的结果，还会一直占着队头把新记录饿死。
        private const val MAX_DISPATCH_ATTEMPTS = 5
        // 补投递锁的存活时间，需大于单条解析的最长耗时(ping 15s + 抓取 60s + LLM 富化)，
        // 否则任务还在跑锁就过期了，下一轮会重复投递同一条
        private val DISPATCH_LOCK_TTL: Duration = Duration.ofMinutes(5)
        // 抓取锁的兜底存活时间。正常路径在 finally 里主动释放，这个值只在进程被强杀时起作用，
        // 因此取得比单条解析的最长耗时宽裕一些即可
        private val PARSE_LOCK_TTL: Duration = Duration.ofMinutes(5)
        // 失效书签在「用户新增」时的重检冷却：既保证用户手动添加能立刻触发一次重试，
        // 又避免同一个已经挂掉的站点被连续添加时把 ping/抓取打满。
        private const val DEAD_RECHECK_COOLDOWN_MINUTES = 10L
    }
}
