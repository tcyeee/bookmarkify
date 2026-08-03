package top.tcyeee.bookmarkify.server.impl

import cn.hutool.core.date.LocalDateTimeUtil
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
import top.tcyeee.bookmarkify.entity.dto.BookmarkLivenessConfigValue
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
import top.tcyeee.bookmarkify.entity.enums.BookmarkLockedField
import top.tcyeee.bookmarkify.entity.enums.ParseStatusEnum
import top.tcyeee.bookmarkify.entity.enums.PingOutcome
import top.tcyeee.bookmarkify.server.liveness.LivenessPolicy
import top.tcyeee.bookmarkify.entity.entity.BookmarkPingLogEntity
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
    private val bookmarkUserLinkMapper: BookmarkUserLinkMapper,
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
    private val layoutNodeFunctionMapper: LayoutNodeFunctionMapper,
    private val bookmarkCategoryService: IBookmarkCategoryService,
    private val adminUserViewAssembler: AdminUserViewAssembler,
    private val pingLogMapper: BookmarkPingLogMapper,
    private val sweepLogMapper: BookmarkSweepLogMapper,
    private val bookmarkLivenessConfigService: IBookmarkLivenessConfigService,
    private val parseLock: ParseLock,
    @Qualifier(AsyncConfig.BOOKMARK_PARSE_EXECUTOR) private val parseExecutor: ThreadPoolTaskExecutor,
    @Qualifier(AsyncConfig.BOOKMARK_PING_EXECUTOR) private val pingExecutor: ThreadPoolTaskExecutor,
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

    /**
     * 某域名的**首页**是否已收录。
     *
     * 原先是 `ktQuery().eq(urlHost, host).one()`，这在同一 host 下存在两条路径时会直接抛异常
     * （MyBatis-Plus 的 `one()` 不允许多行）—— 而按路径去重之后这是必然会出现的情况。
     * 唯一的调用方（相似站点收录）真正想问的是「这个域名的首页收没收过」，所以收窄成按
     * canonical 根页面查询：域名下有别的深链被收录过，不代表首页也有了。
     */
    override fun findRootPageByHost(host: String): BookmarkEntity? =
        siteService.findByHost(host)?.let { getByCanonical(it.id, "/", "", "") }

    override fun findListByUrl(urls: List<String>): List<BookmarkEntity> =
        urls.mapNotNull { runCatching { WebsiteParser.urlWrapper(it) }.getOrNull() }
            .mapNotNull { getByUrl(it) }

    override fun getOrCreateCanonical(url: String): BookmarkEntity =
        getOrCreateByUrl(WebsiteParser.urlWrapper(url))

    @Transactional
    override fun setDefaultFunction(uid: String) =
        UserLayoutNodeEntity(uid = uid, type = NodeTypeEnum.FUNCTION).also { layoutNodeMapper.insert(it) }
            .let { LayoutNodeFunctionEntity(it, uid) }.also { layoutNodeFunctionMapper.insert(it) }.run {}

    override fun search(name: String): List<BookmarkSearchVO> {
        val list = ktQuery().eq(BookmarkEntity::isActivity, true).like(BookmarkEntity::appName, name).or()
            .like(BookmarkEntity::title, name).or().like(BookmarkEntity::description, name).or()
            .like(BookmarkEntity::urlHost, name).last("limit 5").list()
        // 搜索结果是小图 + 全名的形态，按 LIST 模式解析图标
        val logoMap = siteAssetResolver.resolveBatch(list.map { it.id }, DisplayMode.LIST)
        return list.map { BookmarkSearchVO(it, logoMap[it.id]) }
    }

    override fun linkOne(bookmarkId: String, uid: String): UserLayoutNodeVO {
        // 与 addOne 同一套前置检查：这两个方法对用户是同一件事（把一个页面放到我的桌面上），
        // 差别只在 canonical 记录是现查的还是现建的，重复判定自然也该一致——**包括导入队列里
        // 那批还没绑定 canonical 记录的占位**，它们同样会在桌面上变成第二个一模一样的磁贴。
        // 记录先查出来再判重：目标都不存在的话，重复与否根本无从谈起。
        val bookmark = findById(bookmarkId)
        assertNotAlreadyLinked(uid, bookmark)

        val nodeEntity = UserLayoutNodeEntity(uid = uid)
        val userLink = BookmarkUserLink(bookmark, nodeEntity.id, uid)
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
    private fun insertNodeAndLink(node: UserLayoutNodeEntity, link: BookmarkUserLink) {
        try {
            txTemplate.execute {
                layoutNodeMapper.insert(node)
                bookmarkUserLinkMapper.insert(link)
            }
        } catch (e: DuplicateKeyException) {
            // 事务已整体回滚，那个刚插进去的布局节点不会留下来
            log.debug("[insertNodeAndLink] 唯一键冲突，判定为重复收藏: uid=${link.uid}, bookmarkId=${link.bookmarkId}, err=${e.message}")
            throw CommonException(ErrorType.E126)
        }
    }

    /**
     * 该用户已经收藏过这个 canonical 页面时直接拒绝，避免桌面上出现两个一模一样的磁贴。
     *
     * `deleted = false` 不能省：本项目没有配置 MyBatis-Plus 的逻辑删除，`deleted` 全靠各查询手写
     * 过滤。漏掉这个条件，用户删掉一条书签之后就再也加不回来了。
     */
    private fun assertNotAlreadyLinked(uid: String, bookmark: BookmarkEntity) {
        val exists = bookmarkUserLinkService.ktQuery()
            .eq(BookmarkUserLink::uid, uid)
            .eq(BookmarkUserLink::bookmarkId, bookmark.id)
            .eq(BookmarkUserLink::deleted, false)
            .exists()
        if (exists) {
            log.debug("[assertNotAlreadyLinked] 用户已收藏该页面，拒绝重复添加: uid=$uid, bookmarkId=${bookmark.id}")
            throw CommonException(ErrorType.E126)
        }
        assertNotPendingImport(uid, bookmark)
    }

    /**
     * 导入还没抓完的那批占位是否已经包含了这个页面。
     *
     * 上面那道检查按 canonical `bookmarkId` 比对，而批量导入写下的关联行 `bookmark_id` 是字符串
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
    private fun assertNotPendingImport(uid: String, bookmark: BookmarkEntity) {
        val pending = bookmarkUserLinkService.ktQuery()
            .eq(BookmarkUserLink::uid, uid)
            .eq(BookmarkUserLink::bookmarkId, StuckLoadingItem.UNBOUND_BOOKMARK_ID)
            .eq(BookmarkUserLink::deleted, false)
            .like(BookmarkUserLink::urlFull, bookmark.urlHost)
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
        // 前台桌面是大图 + 短名的形态，按 TILE 模式解析图标
        val logoMap = siteAssetResolver.resolveBatch(bookmarkIds, DisplayMode.TILE)
        // 站点层带上品牌名/短名/NSFW：文案优先级要用它们，一次批量取回避免 N+1
        val siteMap = siteService.mapByIds(bookmarkEntityMap.values.map { it.siteId })

        // 所属文件夹：布局节点(layoutNodeId) -> 父节点(parentId) -> 父节点名称，两次批量查询避免 N+1
        val layoutNodeIds = result.records.map { it.layoutNodeId }
        val layoutNodeMap = if (layoutNodeIds.isEmpty()) emptyMap() else layoutNodeMapper.selectByIds(layoutNodeIds).associateBy { it.id }
        val folderIds = layoutNodeMap.values.mapNotNull { it.parentId }.distinct()
        val folderMap = if (folderIds.isEmpty()) emptyMap() else layoutNodeMapper.selectByIds(folderIds).associateBy { it.id }

        return result.convert {
            val folder = layoutNodeMap[it.layoutNodeId]?.parentId?.let { fid -> folderMap[fid] }
            val bookmark = bookmarkEntityMap[it.bookmarkId]
            BookmarkShow(it, bookmark, siteMap[bookmark?.siteId])
                .initDisplay(logoMap[it.bookmarkId], DisplayMode.TILE).apply {
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
        val allLinks: List<BookmarkUserLink> = allBookmarkNodes.map { (raw, node) -> BookmarkUserLink(uid, node.id, raw) }

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
        // 改由 drainStuckLoading() 按解析线程池的空闲容量分批捞取（占位行 bookmark_id='LOADING'
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
        // （BookmarkEntity 只在解析出结果时才写它），而 SQL 里 `NULL < ?` 恒为 NULL，于是「从未被解析过」
        // 的书签——恰恰是最需要兜底的那批——反而一条都选不出来。一旦 addOne 之后的解析事件丢失
        // （进程重启、线程池饱和回退到调用线程后抛异常），这条书签就会永久停在 PENDING：
        // needRecheckOnAdd() 对 PENDING 直接返回 false，另两个定时任务又按 status 把它过滤掉，
        // 结果是所有用户的桌面节点永久停在 BOOKMARK_LOADING 转圈。
        // 用 create_time 兜底而不是把 NULL 直接视作「已过期」，是为了保住那 30 分钟的窗口：
        // 否则刚添加、事件还在途中的书签会在下一次 tick(5min) 就被重复投递一次解析。
        ktQuery()
            .eq(BookmarkEntity::parseStatus, ParseStatusEnum.PENDING)
            .eq(BookmarkEntity::verifyFlag, false)
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
                    BookmarkParseAndNoticeEvent(item.uid, item.bookmarkId!!, item.userLinkId, item.layoutNodeId)
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
     * 本轮巡检对某条记录掌握的**证据强度**。
     *
     * 存在的理由：站点层短路（见 [pingSweepExclusively]）会给一批页面直接安上 DEAD，而那不是
     * 本轮探测出来的，是上一轮站点结论的复用。这批"非证据"已经被排除在熔断样本之外，但
     * 失败计数与归档判定这两处一直照单全收 —— 于是一次站点级误判能在 10 个退避周期内把
     * 该域名下**所有**书签推进 ARCHIVED，而归档此前没有任何自动出口。
     */
    private data class ProbeEvidence(
        /** 本轮是否真的对这个页面发起了探测 */
        val directlyProbed: Boolean,
        /** 所属域名的连续失败次数，来自根地址的真实探测 */
        val siteConsecutiveFail: Int,
    ) {
        companion object {
            /** 解析链路等"就是刚探过/刚抓过这一条"的场景 */
            val DIRECT = ProbeEvidence(directlyProbed = true, siteConsecutiveFail = 0)
        }
    }

    @Async(AsyncConfig.BOOKMARK_SWEEP_EXECUTOR)
    override fun retryUnreachableBookmarks() {
        // 一轮只读一次配置：getConfig() 是一次 system_config 查询，没有缓存，
        // 放在逐条的回调里会变成几百次多余的往返
        val config = bookmarkLivenessConfigService.getConfig()
        // 职责范围为全部 UNREACHABLE（含已认证），不再按 verifyFlag 过滤：认证书签的重新解析仍会被
        // parseBookmark() 短路跳过，但 ping 结果本身依旧值得记录，且它是 UNREACHABLE 唯一的负责任务。
        pingSweep(
            taskLabel = "retryUnreachableBookmarks",
            statusFilter = ParseStatusEnum.UNREACHABLE,
            configuredIntervalHours = config.abnormalCheckIntervalHours,
            batchSize = RETRY_UNREACHABLE_BATCH_SIZE,
            triggeredParseOf = { bookmark, outcome -> outcome == PingOutcome.ALIVE && !bookmark.verifyFlag },
        ) { bookmark, outcome, triggeredParse, evidence ->
            if (triggeredParse) {
                // 调度状态交给重新解析那条链路去写（成功回到正常周期、失败继续退避），
                // 这里抢着写只会被它覆盖，还会掩盖真实的失败次数
                log.debug("[retryUnreachableBookmarks] ping 成功，触发重新解析: bookmarkId=${bookmark.id}, urlHost=${bookmark.urlHost}")
            } else {
                val reason = when (outcome) {
                    PingOutcome.ALIVE -> "ping 成功但已手动认证，跳过重新解析"
                    PingOutcome.DEAD -> "ping 失败"
                    PingOutcome.UNKNOWN -> "探测无结论"
                }
                log.debug("[retryUnreachableBookmarks] $reason: bookmarkId=${bookmark.id}, urlHost=${bookmark.urlHost}")
                persistProbeResult(bookmark, outcome, config, evidence = evidence)
            }
        }
    }

    @Async(AsyncConfig.BOOKMARK_SWEEP_EXECUTOR)
    override fun livenessCheckStaleBookmarks() {
        // 同上：一轮只读一次配置
        val config = bookmarkLivenessConfigService.getConfig()
        // 职责范围收窄为 SUCCESS（含已认证）：UNREACHABLE 已由 retryUnreachableBookmarks 独占负责，
        // 避免同一条 UNREACHABLE 记录被两个任务重复 ping。PENDING 由 checkAll 负责，与此无关。
        pingSweep(
            taskLabel = "livenessCheckStaleBookmarks",
            statusFilter = ParseStatusEnum.SUCCESS,
            configuredIntervalHours = config.activeCheckIntervalHours,
            batchSize = LIVENESS_CHECK_BATCH_SIZE,
            triggeredParseOf = { bookmark, outcome -> shouldRefreshContent(bookmark, outcome, config) },
        ) { bookmark, outcome, triggeredParse, evidence ->
            if (triggeredParse) {
                // 同上：调度状态由重新解析那条链路负责写
                log.debug("[livenessCheckStaleBookmarks] 内容已过期，触发重新抓取: bookmarkId=${bookmark.id}, urlHost=${bookmark.urlHost}")
            } else {
                val reason = when (outcome) {
                    PingOutcome.ALIVE -> "ping 成功且内容未过期，仅推进下次检查时间"
                    PingOutcome.DEAD -> "ping 失败，标记 UNREACHABLE"
                    // 无结论绝不能落库成 UNREACHABLE：那正是「一次抓取服务故障洗掉一批健康书签」的成因
                    PingOutcome.UNKNOWN -> "探测无结论，只做短退避"
                }
                log.debug("[livenessCheckStaleBookmarks] $reason: bookmarkId=${bookmark.id}, urlHost=${bookmark.urlHost}")
                persistProbeResult(
                    bookmark, outcome, config,
                    markUnreachable = outcome == PingOutcome.DEAD,
                    evidence = evidence,
                )
            }
        }
    }

    /**
     * 复活探测：每天捞一批 [ParseStatusEnum.ARCHIVED] 的记录，ping 通就重新抓取。
     *
     * ## 为什么必须有这个任务
     *
     * 归档此前是一个**没有出口的终态**：两个常规巡检分别只认 SUCCESS 与 UNREACHABLE，
     * `checkAll` 只认 PENDING，于是一条记录一旦归档，除非管理员手动点刷新，否则永远
     * 不会再被碰一次。而通往归档的证据链上是有可能出错的 —— 域名临时改了 DNS、
     * 机房出口被目标站点拉黑一段时间、我方连续几轮判断失误，都足以把一个健在的站点
     * 连同它下面所有页面送进去。让一个自动流程能把记录推进不可逆终态，却不给同样自动的
     * 出口，这个不对称本身就是设计缺陷。
     *
     * 频率取每天一次、批量取得很小：归档记录按定义是"两个多月都没通过"的，它们恢复
     * 与否没有任何时效压力，这个任务的存在只是为了保证**存在一条回来的路**。
     */
    @Async(AsyncConfig.BOOKMARK_SWEEP_EXECUTOR)
    override fun reviveArchivedBookmarks() {
        val config = bookmarkLivenessConfigService.getConfig()
        pingSweep(
            taskLabel = "reviveArchivedBookmarks",
            statusFilter = ParseStatusEnum.ARCHIVED,
            configuredIntervalHours = (ARCHIVE_RECHECK_DAYS * 24).toInt(),
            batchSize = REVIVE_ARCHIVED_BATCH_SIZE,
            // 已手动认证的不重抓（与另两个巡检一致），但 ping 结果照样值得记录
            triggeredParseOf = { bookmark, outcome -> outcome == PingOutcome.ALIVE && !bookmark.verifyFlag },
        ) { bookmark, outcome, triggeredParse, _ ->
            if (triggeredParse) {
                // 抓取成功会走 markParseSucceeded 写回 SUCCESS，这条记录就此回到常规巡检的候选池
                log.warn("[reviveArchivedBookmarks] 归档站点重新可达，触发重新抓取: bookmarkId=${bookmark.id}, urlHost=${bookmark.urlHost}")
            } else {
                // 仍然不通：只把游标推到下一个复活探测周期。**不走 persistProbeResult** ——
                // 那会继续累加 consecutiveFail 并按退避曲线算 next_check_at，而归档记录的
                // 退避早已到顶，再累加只是让这个数字无意义地涨下去
                runCatching {
                    ktUpdate().eq(BookmarkEntity::id, bookmark.id)
                        .set(BookmarkEntity::lastCheckAt, LocalDateTime.now())
                        .set(BookmarkEntity::nextCheckAt, LocalDateTime.now().plusDays(ARCHIVE_RECHECK_DAYS))
                        .update()
                }.onFailure { log.warn("[reviveArchivedBookmarks] 游标推进失败: bookmarkId=${bookmark.id}, err=${it.message}") }
            }
        }
    }

    /**
     * 这条已确认存活的书签是否该重新抓一次内容。
     *
     * 这是「书签更新」真正发生的地方。此前这里的条件是 `alive && !isActivity`，而
     * `isActivity=false` 在全代码里从不与 `parseStatus=SUCCESS` 共存（每一处置 false 的地方
     * 都同时写 UNREACHABLE），所以那个分支是死代码——正常站点改了标题、换了图标，线上永远
     * 不会重抓，只有管理员手动刷新才会更新。改为按内容陈旧度判定。
     *
     * 已手动认证(verifyFlag)的书签排除在外：那是人工确认过的终态，parseBookmark 也会短路跳过，
     * 投了事件只会白跑一趟并让这条记录每轮都被重新选中。
     */
    private fun shouldRefreshContent(
        bookmark: BookmarkEntity,
        outcome: PingOutcome,
        config: BookmarkLivenessConfigValue,
    ): Boolean {
        if (outcome != PingOutcome.ALIVE || bookmark.verifyFlag) return false
        // 从未成功抓过内容（老数据 / 一直失败的记录）也算过期，正好借这一轮补齐
        val lastParseAt = bookmark.lastParseAt ?: return true
        return lastParseAt.isBefore(LocalDateTime.now().minusDays(config.contentRefreshIntervalDays.toLong()))
    }

    /**
     * 定时活性检测任务的通用骨架：按 [statusFilter] + 调度游标选出候选、逐条 ping、写 [BookmarkPingLogEntity]，
     * 再交给 [onResult] 决定各自的落库/重新解析动作。[triggeredParseOf] 决定是否需要发布 [BookmarkParseEvent]。
     *
     * 候选只看 `next_check_at`，不再按 `update_time` 倒推时间窗：那一列还兼着「记录最近修改时间」，
     * 管理员改个标题就会把这条记录的下次巡检推迟一整个周期。`next_check_at` 为 NULL 的记录一律视为
     * 到期——宁可多查一次，也不能让一条没有调度状态的记录永久失踪。
     *
     * **先整批探测、再统一落库**，中间隔着一道熔断（[LivenessPolicy.breakerReason]）。顺序不能颠倒：
     * 熔断的判据是整批结果的形态，边探边写就来不及了——等发现异常时，前面几十条已经被写成失联。
     *
     * 候选总数（不含 LIMIT）超过 [batchSize] 时打印告警：说明当前数据量下，配置的检测间隔已经追不上，
     * 只是「目标值」而非「保证值」——需要调大 batchSize 或拉长间隔配置。[configuredIntervalHours]
     * 仅用于这条告警文案。
     */
    private fun pingSweep(
        taskLabel: String,
        statusFilter: ParseStatusEnum,
        configuredIntervalHours: Int,
        batchSize: Int,
        triggeredParseOf: (bookmark: BookmarkEntity, outcome: PingOutcome) -> Boolean,
        onResult: (bookmark: BookmarkEntity, outcome: PingOutcome, triggeredParse: Boolean, evidence: ProbeEvidence) -> Unit,
    ) {
        val lockKey = ParseLock.sweep(taskLabel)
        // 带凭据释放：一轮跑超 TTL 时，无条件 DEL 会把下一轮刚拿到的锁删掉，
        // 于是"跑得比预期慢"直接升级成"两轮并发跑"。见 ParseLock.acquire
        val token = parseLock.acquire(lockKey, SWEEP_LOCK_TTL) ?: run {
            log.warn("[$taskLabel] 上一轮巡检仍在进行(或另一实例正在跑)，本轮跳过")
            return
        }
        try {
            pingSweepExclusively(taskLabel, statusFilter, configuredIntervalHours, batchSize, triggeredParseOf, onResult)
        } finally {
            parseLock.release(lockKey, token)
        }
    }

    private fun pingSweepExclusively(
        taskLabel: String,
        statusFilter: ParseStatusEnum,
        configuredIntervalHours: Int,
        batchSize: Int,
        triggeredParseOf: (bookmark: BookmarkEntity, outcome: PingOutcome) -> Boolean,
        onResult: (bookmark: BookmarkEntity, outcome: PingOutcome, triggeredParse: Boolean, evidence: ProbeEvidence) -> Unit,
    ) {
        val startedAt = System.currentTimeMillis()
        val now = LocalDateTime.now()

        val totalBacklog = ktQuery()
            .eq(BookmarkEntity::parseStatus, statusFilter)
            .apply(DUE_CLAUSE, now)
            .count()
        if (totalBacklog > batchSize) {
            log.warn(
                "[$taskLabel] 候选积压 $totalBacklog 条，超过单次处理上限 $batchSize：" +
                    "当前数据量下 ${configuredIntervalHours}h 的检测间隔配置可能无法按时完成一轮检测"
            )
        }

        val candidates = ktQuery()
            .eq(BookmarkEntity::parseStatus, statusFilter)
            .apply(DUE_CLAUSE, now)
            // 最该查的优先处理，配合 LIMIT 保证积压记录会被逐批消费，不会被新记录饿死
            .last("ORDER BY $DUE_CURSOR ASC LIMIT $batchSize")
            .list()
            // 非域名类型(本地/IP/其他)不抓取，也不应对其发起存活 ping
            .filter { WebsiteParser.classifyLinkType(it.urlHost) == BookmarkLinkType.DOMAIN }
        if (candidates.isEmpty()) return

        log.debug("[$taskLabel] 本次待检查书签数: ${candidates.size}")

        // ── 站点层短路 ──
        // 域名已经判定死亡的，不再逐页探测：一个挂掉的域名有 1000 个页面，就是 1000 次 15s 超时
        // 换同一个结论。每个这样的域名只对根地址探一次，看它是不是恢复了。
        val siteMap = siteService.mapByIds(candidates.map { it.siteId })
        val (pagesOfDeadSites, pagesOfLiveSites) = candidates.partition { siteMap[it.siteId]?.isAlive == false }
        val recovery = probeRoots(pagesOfDeadSites.mapNotNull { siteMap[it.siteId] })
        // 根地址通了 → 域名恢复，这些页面回到正常逐页探测的路径
        val revived = pagesOfDeadSites.filter { recovery[it.siteId] == PingOutcome.ALIVE }
        val shortCircuited: List<Pair<BookmarkEntity, PingOutcome>> = pagesOfDeadSites
            .filterNot { recovery[it.siteId] == PingOutcome.ALIVE }
            // 根地址无结论（我方链路的问题）时给 UNKNOWN，不能记在站点账上
            .map { it to (recovery[it.siteId] ?: PingOutcome.UNKNOWN) }
        // 用 debug 而非 info：ServiceImpl 自带的 log 字段遮蔽了项目的 log 扩展属性，
        // 而那个接口没有 info 方法（同 persistProbeResult 里的那条注释）。
        // 短路条数会并入本轮的汇总行，那里才是看走势的地方。
        if (shortCircuited.isNotEmpty()) log.debug(
            "[$taskLabel] 站点已判定死亡，短路 ${shortCircuited.size} 个页面的探测（省下同样多次超时等待）"
        )

        // 并行探测。串行时最坏耗时是 batchSize × 单条超时(15s)，200 条要 50 分钟、贴着调度周期；
        // 并发度受 scrapper 的全局并发上限约束，见 AsyncConfig.PING_CONCURRENCY 的说明。
        val actuallyProbed: List<Pair<BookmarkEntity, PingOutcome>> = (pagesOfLiveSites + revived)
            .map { bookmark ->
                bookmark to CompletableFuture.supplyAsync({ apiService.pingWebsite(bookmark.rawUrl) }, pingExecutor)
            }
            // 先全部投递、再统一 join：边投边等就退化成串行了
            .map { (bookmark, future) -> bookmark to future.join() }

        // **熔断只看真正探测过的结果。** 短路出来的那些 DEAD 不是探测结论，是上一轮的结论在复用；
        // 把它们混进来会凭空拉高失联比例，让「>90% DEAD」这条规则在一个健康的系统里误触发 ——
        // 而那条规则本来是用来发现"scrapper 通着但出口坏了、于是诚实地把一切报成死"的。
        val breakerReason = LivenessPolicy.breakerReason(actuallyProbed.map { it.second })

        val probed = actuallyProbed + shortCircuited

        // ── 向解析池投递的背压 ──
        // 一轮最多 200 条候选，全都想重新抓取时就是 200 个事件一次性砸进解析池。那个池的队列
        // (500) 是和用户交互式 addOne 共享的，且满了以后走 CallerRunsPolicy —— 调用线程正是
        // 本方法所在的巡检线程，于是最长 60s 的抓取会在这里**同步**跑，200 条就是几小时，
        // 远超巡检锁 30 分钟的 TTL，下一个整点的轮次会与本轮并发跑。
        // 更隐蔽的一层：解析池最多 32 线程，一起打 scrapper 会顶穿它 32 的全局并发上限
        // (AsyncConfig.PING_CONCURRENCY 的注释里记着这条跨服务约束)，被 load_shed 打回 503
        // → E307 → parseByApi 直接原样返回、一个字段都不落库 → 这些行的 next_check_at 停在
        // 过去，永久占据 `ORDER BY next_check_at` 的队头。三件事在这里闭环成一个自我强化的
        // 坏循环，所以按队列余量截断，与 drainStuckLoading 守同一条规矩。
        val parseBudget = if (breakerReason != null) 0 else {
            (parseExecutor.threadPoolExecutor.queue.remainingCapacity() - SWEEP_PARSE_QUEUE_HEADROOM).coerceAtLeast(0)
        }
        var granted = 0
        // 熔断时依旧落 ping 日志：这批结果本身就是判断「我方哪里坏了」的证据，
        // 不落等于把唯一的现场也丢了。只是 triggeredParse 全为 false，且不改动任何书签。
        val wantsParse = probed.map { (bookmark, outcome) ->
            breakerReason == null && triggeredParseOf(bookmark, outcome)
        }
        val triggeredParseOfEach = wantsParse.map { want ->
            (want && granted < parseBudget).also { if (it) granted++ }
        }
        val deferredCount = wantsParse.count { it } - granted
        if (deferredCount > 0) log.warn(
            "[$taskLabel] 解析队列余量不足，本轮推迟 $deferredCount 条重新抓取(可用额度=$parseBudget)，" +
                "这些记录会在 ${TRIGGERED_PARSE_PROTECT_HOURS}h 后重新入选"
        )
        // 只为**真正探测过**的页面落 ping 日志：这张表的语义是"一次探测一行"，
        // 把短路的也写进去会让失联率、探测耗时这些基于它的统计全部失真。
        pingLogMapper.insert(
            actuallyProbed.mapIndexed { index, (bookmark, outcome) ->
                BookmarkPingLogEntity(
                    bookmarkId = bookmark.id,
                    urlHost = bookmark.urlHost,
                    outcome = outcome,
                    triggeredParse = triggeredParseOfEach[index],
                )
            }
        )

        // 一轮一行，无论正常完成还是熔断。见 BookmarkSweepLogEntity：
        // 熔断的语义是"我方链路坏了，本轮全表结论不可信"，此前它唯一的出口是一行 log.error，
        // 而日志会滚动、没人盯着。落成数据之后"最近一天熔断过几次"才是一句 SQL
        fun recordRound() = runCatching {
            sweepLogMapper.insert(
                BookmarkSweepLogEntity(
                    taskLabel = taskLabel,
                    candidates = candidates.size,
                    backlog = totalBacklog,
                    probed = actuallyProbed.size,
                    shortCircuited = shortCircuited.size,
                    aliveCount = probed.count { it.second == PingOutcome.ALIVE },
                    deadCount = probed.count { it.second == PingOutcome.DEAD },
                    unknownCount = probed.count { it.second == PingOutcome.UNKNOWN },
                    triggeredParse = granted,
                    deferredParse = deferredCount,
                    breakerReason = breakerReason?.take(500),
                    durationMs = System.currentTimeMillis() - startedAt,
                )
            )
        }.onFailure { log.warn("[$taskLabel] 巡检汇总落库失败(忽略): ${it.message}") }.let { }

        if (breakerReason != null) {
            log.error("[$taskLabel] 熔断，本轮不改动任何书签: $breakerReason")
            recordRound()
            return
        }

        // 哪些是站点层短路出来的（结论是复用的，不是本轮的新证据）。
        // 用 id 而不是引用比较：同一条记录在两个列表里是同一个对象，但显式一点更稳
        val shortCircuitedIds = shortCircuited.mapTo(HashSet()) { it.first.id }

        probed.forEachIndexed { index, (bookmark, outcome) ->
            val evidence = ProbeEvidence(
                directlyProbed = bookmark.id !in shortCircuitedIds,
                siteConsecutiveFail = siteMap[bookmark.siteId]?.consecutiveFail ?: 0,
            )
            when {
                triggeredParseOfEach[index] -> {
                    // **投递前先把游标推开。** 见 protectSchedule：调用方刻意把调度状态交给解析
                    // 链路去写，而那条链路有好几条不落库的出口
                    protectSchedule(bookmark)
                    onResult(bookmark, outcome, true, evidence)
                    eventPublisher.publishEvent(BookmarkParseEvent(bookmark.id))
                }
                // 想抓但被背压推迟：探测结论不落库（它只是"该重抓了"的依据，不是一个终态），
                // 只把游标推开一小段，等队列松快了下一轮再来。若走 onResult 的常规分支，
                // 这条 ALIVE 会被写成"正常周期"，于是一次队列拥堵会把这批记录推迟整整一个
                // 检测周期（默认 7 天），而它们本该是最急着被重抓的那批
                wantsParse[index] -> protectSchedule(bookmark)
                else -> onResult(bookmark, outcome, false, evidence)
            }
        }

        updateSiteLiveness(taskLabel, actuallyProbed, siteMap, recovery)

        // 一轮一条汇总，胜过几百条 debug：判断「检测间隔配置是否追得上」「站点失联率是否异常」
        // 靠的是这几个数字随时间的走势，逐条日志既翻不动也留不久。
        log.warn(
            "[$taskLabel] 本轮完成: 候选=${candidates.size}/积压=$totalBacklog, " +
                "实际探测=${actuallyProbed.size}/站点层短路=${shortCircuited.size}, " +
                "存活=${probed.count { it.second == PingOutcome.ALIVE }}, " +
                "失联=${probed.count { it.second == PingOutcome.DEAD }}, " +
                "无结论=${probed.count { it.second == PingOutcome.UNKNOWN }}, " +
                "触发重新抓取=${triggeredParseOfEach.count { it }}, " +
                "因队列拥堵推迟=$deferredCount, " +
                "耗时=${System.currentTimeMillis() - startedAt}ms"
        )
        recordRound()
    }

    // ────── 站点层活性 ──────

    /**
     * 对一批站点的**根地址**各探一次，返回 siteId → 结论。
     *
     * 探根地址而不是探某个页面：判断的是"这个域名还在不在"，而具体页面 404 是常态。
     */
    private fun probeRoots(sites: List<SiteEntity>): Map<String, PingOutcome> {
        val distinct = sites.distinctBy { it.id }
        if (distinct.isEmpty()) return emptyMap()
        return distinct
            .map { site -> site to CompletableFuture.supplyAsync({ apiService.pingWebsite(site.rootUrl) }, pingExecutor) }
            .associate { (site, future) -> site.id to future.join() }
    }

    /**
     * 按本轮的页面探测结果推进 `site.is_alive`。
     *
     * 判定规则本身是纯函数 [LivenessPolicy.siteVerdict]（那里写着为什么"页面全挂"不等于"域名死了"）；
     * 这里只负责取数、按需补探根地址、落库。
     *
     * 补探根地址的时机刻意压到最小：只有「本轮全部页面失联、且该域名当前还被认为活着」的站点才
     * 需要一次根地址探测。健康的域名一次都不会多探。
     */
    private fun updateSiteLiveness(
        taskLabel: String,
        probed: List<Pair<BookmarkEntity, PingOutcome>>,
        siteMap: Map<String, SiteEntity>,
        recovery: Map<String, PingOutcome>,
    ) = runCatching {
        // 站点层短路时探到的"域名已恢复"，先落回来
        recovery.filterValues { it == PingOutcome.ALIVE }.keys
            .forEach { siteService.recordLiveness(it, alive = true) }

        val bySite = probed.filter { it.first.siteId.isNotBlank() }
            .groupBy { it.first.siteId }
            .mapValues { (_, group) -> group.map { it.second } }

        // 只有"页面全挂"的站点才需要根地址确认；判活那一侧不需要额外探测。
        // 已经是死的也不必再探：那批走的是站点层短路，recovery 刚探过。
        val needRootProbe = bySite
            .filterKeys { siteMap[it]?.isAlive != false }
            .filterValues { LivenessPolicy.siteVerdict(it, rootOutcome = null) == LivenessPolicy.SiteVerdict.UNCHANGED }
            .filterValues { outcomes -> outcomes.isNotEmpty() && outcomes.all { it == PingOutcome.DEAD } }
            .keys
        // 根页面本身就在本轮候选里时直接复用那条结果，别重复探一次
        val rootFromBatch = probed.filter { it.first.isRootPage }.associate { it.first.siteId to it.second }
        val rootOutcome = rootFromBatch + probeRoots(needRootProbe.filter { it !in rootFromBatch }.mapNotNull { siteMap[it] })

        var dead = 0
        var alive = 0
        var unchanged = 0
        bySite.forEach { (siteId, outcomes) ->
            when (LivenessPolicy.siteVerdict(outcomes, rootOutcome[siteId])) {
                LivenessPolicy.SiteVerdict.ALIVE -> {
                    // 本来就活着的不必重复写库，只有"从死转活"才是一次状态变更
                    if (siteMap[siteId]?.isAlive == false) {
                        siteService.recordLiveness(siteId, alive = true)
                        alive++
                    }
                }
                LivenessPolicy.SiteVerdict.DEAD -> {
                    siteService.recordLiveness(siteId, alive = false)
                    dead++
                }
                LivenessPolicy.SiteVerdict.UNCHANGED -> unchanged++
            }
        }
        if (dead > 0 || alive > 0) log.warn(
            "[$taskLabel] 站点活性更新: 经根地址确认死亡=$dead, 恢复存活=$alive, " +
                "证据不足保持原状=$unchanged(含『页面已消失但域名健在』), 补探根地址=${needRootProbe.size}"
        )
    }.onFailure {
        // 站点层活性只是优化探测开销的辅助信息，算错不该反过来影响已经落库的页面巡检结果
        log.warn("[$taskLabel] 站点活性更新失败(忽略): ${it.message}")
    }.let { }

    // ────── 巡检调度状态的推进 ──────

    /**
     * 按本次结论推进调度列（[BookmarkEntity.lastCheckAt] / [BookmarkEntity.nextCheckAt] /
     * [BookmarkEntity.consecutiveFail]，成功抓到内容时还有 [BookmarkEntity.lastParseAt]）。
     *
     * **所有**改动 `parseStatus` 的地方都必须经过这里：漏一处，那条记录的 `next_check_at`
     * 就停在旧值上，要么被每轮重复选中，要么再也不被选中。
     *
     * 刻意不碰 `updateTime` —— 那是「记录最近修改时间」，由真正改了内容的调用方自己写。
     */
    private fun BookmarkEntity.advanceSchedule(
        outcome: PingOutcome,
        contentRefreshed: Boolean = false,
        // 默认自己去读：解析链路一次只处理一条书签，多一次查询无所谓。批量巡检必须显式传入
        // 本轮已经读好的那份，否则每条记录都要多一次 system_config 往返
        config: BookmarkLivenessConfigValue = bookmarkLivenessConfigService.getConfig(),
        // 本轮结论是否来自对**这个页面**的实际探测。站点层短路出来的 DEAD 是上一轮结论的复用，
        // 不是新证据 —— 它已经被排除在熔断样本之外（见 pingSweepExclusively），
        // 失败计数这一侧却一直漏着，理由完全相同
        directlyProbed: Boolean = true,
    ) {
        val now = LocalDateTime.now()
        lastCheckAt = now
        if (contentRefreshed) lastParseAt = now
        consecutiveFail = when {
            outcome == PingOutcome.ALIVE -> 0
            // 没探测就没有新证据。少了这一条，一个域名被误判死亡后，其下**所有**页面
            // 会在 10 个退避周期里被逐条推进 ARCHIVED —— 而归档没有自动出口，
            // 一次站点级误判就这样静默吃掉整个域名的书签
            !directlyProbed -> consecutiveFail
            outcome == PingOutcome.DEAD -> consecutiveFail + 1
            // 无结论是我方链路的问题，不能记在站点账上：否则一次抓取服务故障就把全表推到
            // 退避曲线末端，之后半个月都不再复查
            else -> consecutiveFail
        }
        nextCheckAt = LivenessPolicy.nextCheckAt(
            now = now,
            outcome = outcome,
            consecutiveFail = consecutiveFail,
            activeIntervalHours = config.activeCheckIntervalHours,
            abnormalIntervalHours = config.abnormalCheckIntervalHours,
        )
    }

    /**
     * 把调度游标往前推一小段，**只动游标**，不碰状态、不碰失败计数。
     *
     * ## 这个方法存在的全部理由
     *
     * 巡检在决定"这条要重新抓取"时刻意不写调度列，把它交给解析链路（成功回到正常周期、
     * 失败继续退避）——这个分工本身是对的，抢着写只会被覆盖，还会掩盖真实的失败次数。
     * 但它有个前提：**解析链路一定会写**。而它有好几条出口不写：
     *
     * - `parseByApi` 判定 [isScrapperUnavailable]（E307）时原样 `return`，一个字段都不落库；
     * - [parseBookmark] 拿不到解析锁时直接返回；
     * - 监听器里的 `runCatching` 吞掉任意异常。
     *
     * 任一发生，这条记录的 `next_check_at` 就停在一个已经过去的时刻。而候选查询是
     * `ORDER BY next_check_at ASC NULLS FIRST LIMIT n` —— 它会**永久占据队头**，把后面的
     * 记录全部饿死。`retryUnreachableBookmarks` 的批量只有 50，凑够 50 条这样的记录，
     * 这个任务就再也探不到第 51 条。这与 `drainStuckLoading` 用 `dispatch_attempts`
     * 解决的是同一类事故，只是巡检这侧一直没有对应的机制。
     *
     * 取一个短间隔而不是完整周期：正常情况下解析链路马上就会用真实结论覆盖它，这个值
     * 只在上面那几条异常出口上生效，那时我们希望它尽快被重试，而不是等一整个周期。
     */
    private fun protectSchedule(bookmark: BookmarkEntity) {
        val now = LocalDateTime.now()
        // 刻意不写 updateTime：这里没有改动记录的任何业务内容，只是挪了一下巡检游标
        runCatching {
            ktUpdate().eq(BookmarkEntity::id, bookmark.id)
                .set(BookmarkEntity::lastCheckAt, now)
                .set(BookmarkEntity::nextCheckAt, now.plusHours(TRIGGERED_PARSE_PROTECT_HOURS))
                .update()
        }.onFailure {
            log.warn("[protectSchedule] 保护性游标写入失败: bookmarkId=${bookmark.id}, err=${it.message}")
        }
    }

    /** 解析成功后推进调度：内容确实被刷新了。 */
    private fun BookmarkEntity.scheduleAfterParseSuccess() =
        advanceSchedule(PingOutcome.ALIVE, contentRefreshed = true)

    // ────── 解析结果的两种终态（**所有**改 parseStatus 的地方都必须走这里）──────
    //
    // 这五行此前被逐字复制了十遍：`isActivity` / `parseStatus` / `parseErrMsg` / `updateTime`
    // 再加一句 schedule*。四个字段之间是有约束的（SUCCESS 必然 isActivity=true 且 errMsg 为空），
    // 而调度列那一句漏掉不会报任何错 —— 那条记录的 next_check_at 就停在旧值上，要么被每轮巡检
    // 重复选中，要么再也不被选中。把它写成注释里的一条纪律（"每个写 parse_status 的地方都必须
    // 调 scheduleAfterParse*"）是不够的：纪律靠人记，而这里可以靠类型。
    //
    // 新增解析路径时**不要**再手写这四个字段，调这两个方法。

    /**
     * 落成「抓到了」：可用、SUCCESS、清空错误、推进内容刷新周期。
     *
     * 注意它**不写库** —— 调用方往往还要在同一次 update 里带上 title/资产等其它字段，
     * 强行在这里落库会变成两次写。
     */
    private fun BookmarkEntity.markParseSucceeded() = apply {
        isActivity = true
        parseStatus = ParseStatusEnum.SUCCESS
        parseErrMsg = null
        updateTime = LocalDateTime.now()
        scheduleAfterParseSuccess()
    }

    /**
     * 落成「这个站点抓不到」：不可用、UNREACHABLE、记下原因、计入连续失败走退避。
     *
     * ⚠️ 只用于**目标站点**的失败。「我方抓取服务不可用」(E307) 绝不能走到这里——那种情况必须
     * 保持 PENDING 不收口，否则用户桌面上那个节点会脱离 BOOKMARK_LOADING、也就脱离
     * drainStuckLoading 的重投递范围，之后即使抓取成功也没有任何机制会把结果回传给它。
     * 判据见 [isScrapperUnavailable]，每个调用点都在进来之前先挡了一道。
     */
    private fun BookmarkEntity.markParseUnreachable(errMsg: String?) = apply {
        isActivity = false
        parseStatus = ParseStatusEnum.UNREACHABLE
        parseErrMsg = errMsg
        updateTime = LocalDateTime.now()
        scheduleAfterParseFailure()
    }

    /**
     * 解析判定站点不可达后推进调度：计入连续失败，走指数退避。
     *
     * 这里**不做归档**：归档是「候选池该不该继续包含这条记录」的调度决定，归巡检
     * （[persistProbeResult]）负责。解析失败而 ping 仍然通得过，说明站点活着、只是我方抓不动，
     * 那种情况值得继续按最长退避间隔偶尔重试，而不是就地判死。
     */
    private fun BookmarkEntity.scheduleAfterParseFailure() = advanceSchedule(PingOutcome.DEAD)

    /**
     * 抓取结果落库前，把管理员手工锁定的字段还原成人工值。
     *
     * [manual] 是抓取开始之前从库里读出来的那份快照。定期重抓一旦开启，没有这一步，管理员改过的
     * 标题就会在下一个刷新周期被静默覆盖——而用户看到的是「后台明明改好了，过一个月又变回去了」。
     *
     * 锁本身也一并还原：自动链路只读锁、不改锁。
     */
    private fun BookmarkEntity.restoreLockedFields(manual: BookmarkEntity) {
        if (manual.isLocked(BookmarkLockedField.TITLE)) title = manual.title
        if (manual.isLocked(BookmarkLockedField.DESCRIPTION)) description = manual.description
        if (manual.isLocked(BookmarkLockedField.APP_NAME)) appName = manual.appName
        lockedFields = manual.lockedFields
    }

    /**
     * 把一次纯探测（没有抓取内容）的结论落库：只动调度列，必要时再改状态。
     *
     * [markUnreachable] 由调用方给出，表示「这条记录本轮**刚刚**从可用变成失联」，
     * 只有 SUCCESS 那条巡检会传 true。归档则与它无关：
     *
     * 连续失败累计到阈值就转 [ParseStatusEnum.ARCHIVED]，这条记录从两个巡检任务的候选池里彻底
     * 移出（各自只认 UNREACHABLE / SUCCESS），不再无休止地每半个月 ping 一个早就没了的域名。
     * **失败次数是在 UNREACHABLE 巡检里一轮轮累积起来的，而那条路径的 markUnreachable 恒为 false**
     * ——所以归档判定必须独立于它，否则永远只在「首次失联」那一刻检查阈值（那时计数才 1），
     * 归档实际上一次都不会发生。
     *
     * 用户侧没有新语义：`isActivity=false` 不变，照旧算失效书签，归档只是停止巡检。
     */
    private fun persistProbeResult(
        bookmark: BookmarkEntity,
        outcome: PingOutcome,
        config: BookmarkLivenessConfigValue,
        markUnreachable: Boolean = false,
        evidence: ProbeEvidence = ProbeEvidence.DIRECT,
    ) {
        bookmark.advanceSchedule(outcome, config = config, directlyProbed = evidence.directlyProbed)
        val archived = outcome == PingOutcome.DEAD && when {
            evidence.directlyProbed -> LivenessPolicy.shouldArchive(bookmark.consecutiveFail)
            // 站点层短路的页面本轮没被探测，它的 consecutiveFail 已经不再增长（见 advanceSchedule），
            // 于是永远够不到归档阈值。归档改由**域名**的连续失败次数决定 —— 那来自根地址的
            // 真实探测，是这条路径上唯一的新证据。少了这一支，一个域名下上千个页面会永远
            // 停在候选池里，每轮吃掉 LIMIT 名额，把真正该复查的记录挤出去
            else -> LivenessPolicy.shouldArchive(evidence.siteConsecutiveFail)
        }
        val update = ktUpdate().eq(BookmarkEntity::id, bookmark.id)
            .set(BookmarkEntity::lastCheckAt, bookmark.lastCheckAt)
            .set(BookmarkEntity::nextCheckAt, bookmark.nextCheckAt)
            .set(BookmarkEntity::consecutiveFail, bookmark.consecutiveFail)
        if (archived) {
            // 归档不是终点，只是退出常规巡检。游标推到复活探测的周期上，
            // 由 reviveArchivedBookmarks 每天捞一次 —— 见那个方法的说明
            update.set(
                BookmarkEntity::nextCheckAt,
                LocalDateTime.now().plusDays(ARCHIVE_RECHECK_DAYS)
            )
        }
        if (markUnreachable || archived) {
            if (archived) {
                // 用 warn 而不是 info：MyBatis-Plus 的 ServiceImpl 自带一个 org.apache.ibatis Log
                // 成员，它遮蔽了项目的 log 扩展属性，而那个接口压根没有 info 方法。
                // 归档是个值得留痕的终态变更，warn 的级别也合适。
                log.warn(
                    "[persistProbeResult] 连续失败 ${bookmark.consecutiveFail} 次，转入归档不再巡检: " +
                        "bookmarkId=${bookmark.id}, urlHost=${bookmark.urlHost}"
                )
            }
            update.set(BookmarkEntity::parseStatus, if (archived) ParseStatusEnum.ARCHIVED else ParseStatusEnum.UNREACHABLE)
                .set(BookmarkEntity::isActivity, false)
                // 状态真的变了，这才算记录被修改
                .set(BookmarkEntity::updateTime, LocalDateTime.now())
        }
        update.update()
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
        log.debug("[addOne] Step2 书签记录就绪: bookmarkId=${bookmark.id}, urlHost=${bookmark.urlHost}, parseStatus=${bookmark.parseStatus}")

        // 2.5 该用户是否已经收藏过这个页面。判定落在 canonical bookmarkId 上而不是 URL 字符串上：
        //     同一个页面用户可能写作 github.com/x、https://github.com/x、https://github.com/x/，
        //     字符串各不相同，canonical 记录却是同一条。此前完全没有这道检查，同一个网址点两次
        //     就在桌面上留下两个一模一样的磁贴（导入路径反倒有重复检测，两条入口行为不一致）。
        //     除了按 canonical id 比对，还会盖住导入占位那一类：它们的 bookmark_id 还是 'LOADING'，
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
        val userLink = BookmarkUserLink(bookmarkUrl.urlRaw, uid, nodeEntity.id, bookmark)

        // 4. 布局节点与用户关联原子写入，见 insertNodeAndLink。
        insertNodeAndLink(nodeEntity, userLink)
        log.debug("[addOne] Step3 已创建布局节点与用户关联: nodeId=${nodeEntity.id}, userLinkId=${userLink.id}, type=${nodeEntity.type}")

        // 5. 需要解析 → 立即返回 loading 占位 VO，同时发布异步解析事件。
        //    解析完成后由 parseAndNotice 通过 WebSocket 将最终结果推送到客户端。
        //    事件在事务提交之后发布，避免回滚后监听器读到不存在的记录。
        if (needParse) {
            log.debug("[addOne] Step5 书签需要解析，返回 LOADING 占位，已发布异步解析事件: bookmarkId=${bookmark.id}, parseStatus=${bookmark.parseStatus}, isActivity=${bookmark.isActivity}, userLinkId=${userLink.id}, nodeId=${nodeEntity.id}")
            return nodeEntity.loadingVO(bookmark.urlHost)
                .also { eventPublisher.publishEvent(BookmarkParseAndNoticeEvent(uid, bookmark.id, userLink.id, nodeEntity.id)) }
        }

        // 6. 书签在有效期内，无需重新抓取，直接返回完整数据。
        log.debug("[addOne] Step6 书签在有效期内，无需重新解析，直接返回完整数据: bookmarkId=${bookmark.id}, nodeId=${nodeEntity.id}")
        return showForDesktop(userLink.id).let { UserLayoutNodeVO(nodeEntity, it) }
    }

    override fun adminListAll(params: BookmarkSearchParams): IPage<BookmarkAdminVO> {
        val entityPage = baseMapper.selectPage(params.toPage(), params.toWrapper())
        val page = entityPage.convert { BookmarkAdminVO(it) }
        // 后台列表按 role 分列展示 favicon/logo/社交图，缺哪张要一眼可见，所以资产必须随列表下发；
        // 用一条 in 查询批量取回避免 N+1，签名按列表格子的尺寸缩放
        runCatching {
            val ids = page.records.map { it.id }
            val assetMap = siteAssetResolver.assetsOfBatch(ids)
            page.records.forEach { vo -> vo.assets = toAssetVOs(assetMap[vo.id].orEmpty(), ADMIN_LIST_ASSET_SIZE) }
            // 图标管理页直接在列表上编辑内边距/背景色，列表不下发 displayPrefs 就只能显示默认值，
            // 看起来像"保存没生效"。这里的查询条数只与展示模式个数有关，与行数无关
            val prefMap = siteAssetResolver.prefsOfBatch(ids)
            val resolvedByMode = DisplayMode.entries.associateWith { siteAssetResolver.resolveBatch(ids, it) }
            page.records.forEach { vo ->
                vo.displayPrefs = DisplayMode.entries.map { mode ->
                    val pref = prefMap[vo.id]?.firstOrNull { it.displayMode == mode }
                    val resolved = resolvedByMode[mode]?.get(vo.id)
                    SiteDisplayPrefVO(
                        displayMode = mode,
                        iconPadding = pref?.iconPadding ?: 25,
                        iconBgColor = pref?.iconBgColor,
                        pinnedAssetId = pref?.pinnedAssetId,
                        previewUrl = resolved?.url,
                        monogram = resolved?.monogram ?: true,
                    )
                }
            }
        }.onFailure { log.warn("[adminListAll] 资产回填失败(忽略): ${it.message}") }
        // 分类回填失败(如分类表缺失/查询异常)不应拖垮整个书签列表，降级为空分类
        runCatching {
            val catMap = bookmarkCategoryService.categoriesOf(page.records.map { it.id })
            page.records.forEach { vo ->
                vo.categories = catMap[vo.id].orEmpty()
                    .map { CategoryVO(it.id, it.slug, it.name, it.color) }
            }
        }.onFailure { log.warn("[adminListAll] 分类回填失败(忽略): ${it.message}") }
        runCatching { fillOwners(page.records) }
            .onFailure { log.warn("[adminListAll] 收录者回填失败(忽略): ${it.message}") }
        return page
    }

    /**
     * 给后台列表回填「收录者」：最早把该书签加进来的那个用户，外加收录人数。
     *
     * 书签表没有属主列 —— 它是全站共享的规范化记录，归属只存在于 `bookmark_user_link`。所以
     * 这里按 bookmarkId 批量捞关联行（一次 in 查询，不是逐行查），同一书签内按 createTime 取
     * 最早的一条当收录者。软删的关联不算：用户把书签从桌面删掉之后，他就不该再作为收录者出现。
     */
    private fun fillOwners(records: List<BookmarkAdminVO>) {
        if (records.isEmpty()) return
        val links = bookmarkUserLinkService.ktQuery()
            .`in`(BookmarkUserLink::bookmarkId, records.map { it.id })
            .eq(BookmarkUserLink::deleted, false)
            .list()
        if (links.isEmpty()) return
        val byBookmark = links.groupBy { it.bookmarkId }
        // 收录者用户信息同样批量取：整页的 uid 去重后一次查完
        val firstUidOf = byBookmark.mapValues { (_, rows) -> rows.minBy { it.createTime }.uid }
        val users = adminUserViewAssembler.findByIds(firstUidOf.values.toSet())
        records.forEach { vo ->
            val rows = byBookmark[vo.id].orEmpty()
            // 人数按 uid 去重：同一个人可以把同一个网址放到桌面上的多个位置，那仍然只是一个收录者
            vo.ownerCount = rows.map { it.uid }.distinct().size
            vo.owner = firstUidOf[vo.id]?.let(users::get)
        }
    }

    override fun adminUpdateIcon(bookmarkId: String, params: BookmarkIconUpdateParams) {
        val bookmark = baseMapper.selectById(bookmarkId) ?: throw CommonException(ErrorType.E102)
        // appName 仍属于 bookmark 主表。手工填了值就加锁，清空则解锁——空的简称本来就会
        // 被下一次抓取用 manifest.short_name 或 LLM 推断补上，锁住一个空值没有意义
        if (params.appName.isNullOrBlank()) {
            bookmark.unlock(BookmarkLockedField.APP_NAME)
        } else {
            bookmark.lock(BookmarkLockedField.APP_NAME)
        }
        ktUpdate().eq(BookmarkEntity::id, bookmarkId)
            .set(BookmarkEntity::appName, params.appName)
            .set(BookmarkEntity::lockedFields, bookmark.lockedFields)
            .update()
        // 显示设置按（站点 × 展示模式）分行：72px 大图上的内边距/背景色，与 16px 列表行
        // 完全是两回事，不该互相影响；而它们调的都是站点图标的观感，所以键是站点而非书签
        siteDisplayPrefService.save(
            siteId = bookmark.siteId,
            bookmarkId = bookmarkId,
            mode = params.displayMode,
            iconPadding = params.iconPadding,
            iconBgColor = params.iconBgColor,
            pinnedAssetId = params.pinnedAssetId,
        )
    }

    override fun adminRefetch(bookmarkId: String): BookmarkRefetchVO {
        val bookmark = baseMapper.selectById(bookmarkId) ?: throw CommonException(ErrorType.E102)
        log.debug("[adminRefetch] 管理员重新获取书签元信息: bookmarkId=$bookmarkId, rawUrl=${bookmark.rawUrl}")
        // 仅预览，不落库：重新抓取一次，拿到新的标题与小图标。
        // BYPASS 是必须的——命中 scrapper 缓存的"重新获取"等于没获取
        val vo = apiService.scrape(bookmark.rawUrl, apiService.scrapeRequest(bookmark.rawUrl, CacheMode.BYPASS))
        val iconUrl = vo.faviconUrl
        // 预览与应用之间用 Redis 暂存完整抓取结果，确保「所见即所存」且避免应用时再抓一次造成漂移
        RedisUtils.set(RedisType.BOOKMARK_REFETCH, bookmarkId, vo)
        // vo.logoUrl / vo.faviconUrl 已在 ScrapeResponseExt 里过了 OssUtils.signAsset，
        // 这里不能再签一次(会把签名 query 当成 key 的一部分)。未抓到则为 null，交由前端说明。
        val logoUrl = vo.logoUrl?.takeIf { it.isNotBlank() }
        log.debug("[adminRefetch] 重新获取完成并已暂存: bookmarkId=$bookmarkId, newTitle=${vo.title}, hasLogo=${logoUrl != null}")
        return BookmarkRefetchVO(title = vo.title, iconUrl = iconUrl, logoUrl = logoUrl)
    }

    /**
     * 抓取服务(scrapper)自身不可用 ≠ 目标站点失联。前者常见于本地开发没起 scrapper、
     * 鉴权 token 配错，此时若照旧把书签写成 UNREACHABLE，一次本地调试就能把好端端的
     * 书签洗成"失联"。这类失败一律不落库，直接向上抛出让调用方看到真实原因。
     */
    private fun Throwable.isScrapperUnavailable(): Boolean =
        this is CommonException && errorType == ErrorType.E307

    override fun adminCheckLiveness(bookmarkId: String): BookmarkLivenessVO {
        val bookmark = baseMapper.selectById(bookmarkId) ?: throw CommonException(ErrorType.E102)
        log.debug("[adminCheckLiveness] 管理员触发书签活性检测: bookmarkId=$bookmarkId, rawUrl=${bookmark.rawUrl}")
        val startedAt = System.currentTimeMillis()
        return runCatching { apiService.queryWebsiteInfo(bookmark.rawUrl) }.fold(
            onSuccess = { vo ->
                bookmark.markParseSucceeded()
                baseMapper.updateById(bookmark)
                log.debug("[adminCheckLiveness] 检测成功: bookmarkId=$bookmarkId, source=${vo.primarySource}")
                BookmarkLivenessVO(
                    success = true,
                    title = vo.title,
                    description = vo.description,
                    image = vo.socialUrl,
                    favicon = vo.faviconUrl,
                    logo = vo.logoUrl,
                    source = vo.primarySource,
                    cached = vo.cached,
                    screenshot = vo.screenshotUrl,
                    isActivity = true,
                    parseStatus = ParseStatusEnum.SUCCESS,
                )
            },
            onFailure = { e ->
                if (e.isScrapperUnavailable()) {
                    log.warn("[adminCheckLiveness] 抓取服务不可用，不改动书签状态: bookmarkId=$bookmarkId, err=${e.message}")
                    throw e
                }
                recordScrapeFailure(bookmark, e, startedAt)
                bookmark.markParseUnreachable(e.message)
                baseMapper.updateById(bookmark)
                log.debug("[adminCheckLiveness] 检测失败: bookmarkId=$bookmarkId, err=${e.message}")
                BookmarkLivenessVO(
                    success = false,
                    errorMsg = e.message,
                    isActivity = false,
                    parseStatus = ParseStatusEnum.UNREACHABLE,
                )
            },
        )
    }

    override fun adminApplyRefetch(bookmarkId: String, params: BookmarkRefetchApplyParams): BookmarkAdminVO {
        val bookmark = baseMapper.selectById(bookmarkId) ?: throw CommonException(ErrorType.E102)
        val vo = RedisUtils.get<ScrapeResponse>(RedisType.BOOKMARK_REFETCH, bookmarkId)
            ?: throw CommonException(ErrorType.E112)
        log.debug("[adminApplyRefetch] 应用重新获取结果: bookmarkId=$bookmarkId, useNewTitle=${params.useNewTitle}, useNewIcon=${params.useNewIcon}, useNewLogo=${params.useNewLogo}")

        // 管理员显式选择采用抓取来的标题：这个字段此后不再是人工值，解锁交回自动链路
        if (params.useNewTitle) {
            bookmark.title = vo.title
            bookmark.unlock(BookmarkLockedField.TITLE)
        }
        // 资产是整体替换的：图标与 LOGO 同源于一次抓取，没法只采用其中一半而保持一致，
        // 因此只要任一开关打开就整批落库（细粒度取舍改由 site_display_pref.pinnedAssetId 表达）
        if (params.useNewIcon || params.useNewLogo) {
            // 回放的是 adminRefetch 暂存在 Redis 里的那次抓取结果，本次没发生网络请求，
            // 耗时记 0 是准确的（真实耗时属于当初那次抓取）
            siteAssetWriter.persist(bookmark.siteId, bookmarkId, bookmark.rawUrl, vo, 0, bookmark.isRootPage)
        }
        bookmark.updateTime = LocalDateTime.now()
        // 管理员刚刚亲眼确认过这份内容是新的，等价于一次成功的重新抓取：
        // 不推进 lastParseAt 的话，这条记录仍会被内容刷新巡检当成过期的再抓一遍
        bookmark.scheduleAfterParseSuccess()
        baseMapper.insertOrUpdate(bookmark)
        RedisUtils.del(RedisType.BOOKMARK_REFETCH, bookmarkId)
        log.debug("[adminApplyRefetch] 应用完成: bookmarkId=$bookmarkId, title=${bookmark.title}")
        return adminDetail(bookmarkId)
    }

    override fun adminRefresh(bookmarkId: String): BookmarkAdminVO {
        val bookmark = baseMapper.selectById(bookmarkId) ?: throw CommonException(ErrorType.E102)
        log.debug("[adminRefresh] 管理员一键更新书签信息: bookmarkId=$bookmarkId, rawUrl=${bookmark.rawUrl}")
        val startedAt = System.currentTimeMillis()
        runCatching { apiService.scrape(bookmark.rawUrl, apiService.scrapeRequest(bookmark.rawUrl, CacheMode.BYPASS)) }.fold(
            onSuccess = { vo ->
                bookmark.markParseSucceeded().apply {
                    title = vo.title
                    description = vo.description
                    // 「一键更新」是管理员显式要求采用抓取值，标题/简介此后不再是人工值 → 解锁
                    unlock(BookmarkLockedField.TITLE, BookmarkLockedField.DESCRIPTION)
                }
                siteAssetWriter.persist(bookmark.siteId, bookmarkId, bookmark.rawUrl, vo, elapsedMs(startedAt), bookmark.isRootPage)
                log.debug("[adminRefresh] 更新成功: bookmarkId=$bookmarkId, title=${bookmark.title}")
            },
            onFailure = { e ->
                if (e.isScrapperUnavailable()) {
                    log.warn("[adminRefresh] 抓取服务不可用，不改动书签状态: bookmarkId=$bookmarkId, err=${e.message}")
                    throw e
                }
                recordScrapeFailure(bookmark, e, startedAt)
                bookmark.markParseUnreachable(e.message)
                log.debug("[adminRefresh] 更新失败: bookmarkId=$bookmarkId, err=${e.message}")
            },
        )
        baseMapper.updateById(bookmark)
        return adminDetail(bookmarkId)
    }

    override fun adminSyncFromExternalScrape(url: String, vo: ScrapeResponse): Boolean {
        val urlWrapper = WebsiteParser.urlWrapper(url)
        val bookmark = getByUrl(urlWrapper) ?: return false
        log.debug("[adminSyncFromExternalScrape] 网站管理活性检测命中已有书签，同步落库: bookmarkId=${bookmark.id}, url=$url")
        bookmark.markParseSucceeded().apply {
            title = vo.title
            description = vo.description
        }
        siteAssetWriter.persist(bookmark.siteId, bookmark.id, bookmark.rawUrl, vo, 0, bookmark.isRootPage)
        baseMapper.updateById(bookmark)
        log.debug("[adminSyncFromExternalScrape] 同步完成: bookmarkId=${bookmark.id}, title=${bookmark.title}")
        return true
    }

    override fun adminUpdateBasicInfo(bookmarkId: String, params: BookmarkBasicInfoUpdateParams): BookmarkAdminVO {
        val bookmark = baseMapper.selectById(bookmarkId) ?: throw CommonException(ErrorType.E102)
        // 手工改过的字段加锁，否则定期重抓会在下一个刷新周期把它静默改回抓取值
        params.title?.let { bookmark.title = it; bookmark.lock(BookmarkLockedField.TITLE) }
        params.description?.let { bookmark.description = it; bookmark.lock(BookmarkLockedField.DESCRIPTION) }
        bookmark.updateTime = LocalDateTime.now()
        baseMapper.updateById(bookmark)
        log.debug("[adminUpdateBasicInfo] 管理员手动更新基础信息: bookmarkId=$bookmarkId, title=${bookmark.title}, lockedFields=${bookmark.lockedFields}")
        return adminDetail(bookmarkId)
    }

    override fun adminUpdateCategories(bookmarkId: String, categoryIds: List<String>): List<CategoryVO> {
        baseMapper.selectById(bookmarkId) ?: throw CommonException(ErrorType.E102)
        bookmarkCategoryService.replaceLinks(bookmarkId, categoryIds, CategorySource.MANUAL)
        return loadCategoryVOs(bookmarkId)
    }

    /**
     * 后台「重新 AI 归类」：走开词表那条路径 —— AI 提议的新分类会被建进 `category` 字典。
     *
     * 自动抓取链路仍然用闭词表的 [IBookmarkCategoryService.categorize]，两者不能互换：
     * 让爬虫有权写分类字典，收录量一上来分类体系就散了。
     */
    override fun adminRecategorize(bookmarkId: String): List<CategoryVO> {
        val bookmark = baseMapper.selectById(bookmarkId) ?: throw CommonException(ErrorType.E102)
        bookmarkCategoryService.categorizeAllowingNew(bookmark)
        return loadCategoryVOs(bookmarkId)
    }

    /**
     * 后台「图片资产 · 重新抓取」：只重抓图片，**不碰标题/简介，也不解锁人工锁**。
     *
     * 与「一键更新」([adminRefresh]) 刻意分开：那个是"整条记录以抓取值为准"，会覆盖标题简介并
     * 解锁 TITLE/DESCRIPTION；管理员只想补一张缺失的 LOGO 时用它，手工改过的标题会被静默改回去。
     *
     * "抓到的没有就不覆盖"这条语义由 [SiteAssetWriter.persist] 自己保证：本次没抓到该层资产时
     * 它保留库中现值，不清空。
     */
    override fun adminRefetchAssets(bookmarkId: String): BookmarkAssetRefetchVO {
        val bookmark = baseMapper.selectById(bookmarkId) ?: throw CommonException(ErrorType.E102)
        log.debug("[adminRefetchAssets] 管理员重抓图片资产: bookmarkId=$bookmarkId, rawUrl=${bookmark.rawUrl}")
        val startedAt = System.currentTimeMillis()
        // BYPASS：不绕开 scrapper 缓存的话"重新抓取"可能直接命中上一次的结果，等于没抓
        return runCatching {
            apiService.scrape(bookmark.rawUrl, apiService.scrapeRequest(bookmark.rawUrl, CacheMode.BYPASS))
        }.fold(
            onSuccess = { vo ->
                siteAssetWriter.persist(
                    bookmark.siteId, bookmarkId, bookmark.rawUrl, vo, elapsedMs(startedAt), bookmark.isRootPage,
                )
                val count = vo.assets.size
                log.debug("[adminRefetchAssets] 抓取成功: bookmarkId=$bookmarkId, scrapedAssets=$count")
                BookmarkAssetRefetchVO(success = true, scrapedAssetCount = count, bookmark = adminDetail(bookmarkId))
            },
            onFailure = { e ->
                // 抓取服务本身不可用要如实报错，不能伪装成"这个站没有图"
                if (e.isScrapperUnavailable()) throw e
                recordScrapeFailure(bookmark, e, startedAt)
                log.debug("[adminRefetchAssets] 抓取失败: bookmarkId=$bookmarkId, err=${e.message}")
                BookmarkAssetRefetchVO(
                    success = false, scrapedAssetCount = 0, errorMsg = e.message, bookmark = adminDetail(bookmarkId),
                )
            },
        )
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

    /** 收录单个相似站点：本地已有→EXISTS；抓取失败(不可达=幻觉/失效)→删除记录并 SKIPPED；抓到(SUCCESS)→INGESTED。 */
    private fun ingestOneSimilar(domain: String): String {
        val wrapper = WebsiteParser.urlWrapper("https://${domain.trim().substringAfter("://")}")
        findRootPageByHost(wrapper.urlHost)?.let { return "EXISTS" }
        val bookmark = getOrCreateByUrl(wrapper)
        // 抓取可能抛异常（本地解析器）或落 UNREACHABLE（scrapper 不可达）；统一以「最终落库状态」判定，
        // 抓到正文(SUCCESS，反爬页面也算)才保留，其余一律删除——保证幻觉域名绝不留在库里。
        runCatching { parseBookmark(bookmark) }
            .onFailure { log.warn("[ingestOneSimilar] 解析异常 domain=$domain: ${it.message}") }
        val saved = baseMapper.selectById(bookmark.id)
        val ok = saved != null && saved.parseStatus == ParseStatusEnum.SUCCESS
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

    /**
     * 抓取成功后的元数据富化：分类打标 + NSFW 判定。
     *
     * 两者都只写各自的字段、互不影响，任一失败也不该拖累另一个，故各自 runCatching 收口。
     */
    override fun enrich(bookmarkId: String) {
        val bookmark = baseMapper.selectById(bookmarkId) ?: run {
            log.debug("[enrich] 书签已不存在，跳过: bookmarkId=$bookmarkId")
            return
        }
        runCatching { bookmarkCategoryService.categorize(bookmark) }
            .onFailure { log.warn("[enrich] 分类打标失败(忽略): bookmarkId=$bookmarkId, err=${it.message}") }
        checkNsfw(bookmark)
    }

    override fun coverOf(linkId: String, uid: String): String? {
        if (linkId.isBlank() || uid.isBlank()) return null
        // uid 一并进 where：拿别人的 linkId 来问，查不到就是 null，而不是别人的封面。
        // deleted 也必须显式带上：本项目没有配 MyBatis-Plus 的逻辑删除，不写这一条，
        // 已删除的书签照样能查出封面来
        val link = bookmarkUserLinkMapper.selectOne(
            KtQueryWrapper(BookmarkUserLink::class.java)
                .eq(BookmarkUserLink::id, linkId)
                .eq(BookmarkUserLink::uid, uid)
                .eq(BookmarkUserLink::deleted, false)
        ) ?: return null
        val bookmarkId = link.bookmarkId?.takeIf { it.isNotBlank() } ?: return null
        return siteAssetResolver.resolveCoverOne(bookmarkId)
    }

    override fun captureScreenshot(bookmarkId: String) {
        val bookmark = baseMapper.selectById(bookmarkId) ?: run {
            log.debug("[captureScreenshot] 书签已不存在，跳过: bookmarkId=$bookmarkId")
            return
        }
        // 用户手动认证过的书签不再被自动抓取覆盖，截图同样不该去动它
        if (bookmark.verifyFlag) return
        val url = bookmark.rawUrl.takeIf { it.isNotBlank() } ?: return

        // 已经有封面截图就不再截：内容定期重抓（默认 30 天一轮）会让每条成功的书签重新投递一次
        // 截图事件，而截图是全系统最贵的一次调用——强制无头浏览器，对端 Chrome 全局串行、
        // 生产容器只有 1GB。不拦这一道，稳态下截图池会长期占着对端那把锁，把用户当场触发的
        // 反爬无头回退饿死在锁上（那条路是有人在等结果的）。页面改版换封面的收益远抵不过这个代价。
        if (siteAssetResolver.assetsOf(bookmarkId).any { it.role == AssetRole.SCREENSHOT }) {
            log.debug("[captureScreenshot] 已有截图，跳过: bookmarkId=$bookmarkId")
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
            log.debug("[captureScreenshot] 抓取失败(不影响书签): bookmarkId=$bookmarkId, err=${it.message}")
            return
        }

        if (response.screenshot?.storageKey == null) {
            // 常态而非异常：反爬站点、无头熔断、站点 API 救援都会走到这里
            log.debug(
                "[captureScreenshot] 本次没有截图: bookmarkId=$bookmarkId, " +
                    "layer=${response.fetch.layerUsed}, warnings=${response.diagnostics?.warnings}"
            )
            return
        }

        runCatching { siteAssetWriter.upsertScreenshot(bookmarkId, url, response) }
            .onSuccess { if (it) log.debug("[captureScreenshot] 封面已更新: bookmarkId=$bookmarkId") }
            .onFailure { log.warn("[captureScreenshot] 截图落库失败: bookmarkId=$bookmarkId, err=${it.message}") }
    }

    /** 解析书签，然后保存到数据库，同时通知到用户 */
    override fun parseAndNotice(uid: String, bookmarkId: String, userLinkId: String, nodeId: String) {
        log.debug("[parseAndNotice-4] 开始书签解析: uid=$uid, bookmarkId=$bookmarkId, userLinkId=$userLinkId, nodeId=$nodeId")
        // 交互式路径绕过 scrapper 的缓存。要绕的主要是那 60 秒的**负缓存**：它是
        // "刚刚有人抓这个网址失败过"，命中即 RECENTLY_FAILED → E304 → 这条书签直接落成
        // UNREACHABLE —— 而当前这个用户根本没有得到过一次真实的尝试，别人的一次失败记在了
        // 他账上。用户此刻正盯着那个转圈的格子，多花几 KB 换一次真实结论是划算的。
        // 导入路径(parseAndResetUserItem)刻意**不**这么做：那里几千条一起跑，缓存正是要用的。
        val resolved = runCatching { parseBookmark(baseMapper.selectById(bookmarkId), CacheMode.BYPASS) }.onFailure { ex ->
            // 解析链路中的未预期异常（而非「抓取失败」这类已内部兜底为 UNREACHABLE 的正常业务失败）不能让节点
            // 永久停在 BOOKMARK_LOADING——此前这里的异常会一路冒泡到事件监听器，被其 runCatching 吞掉且
            // 不回写任何状态，用户端只会看到一个转不动的加载占位符。与 parseAndResetUserItem 保持一致，
            // 退化为与「ping 不通」一致的处理：落一条 UNREACHABLE 记录，让节点照常收口而不是无限转圈。
            log.error("[parseAndNotice-4] 解析异常，标记为不可用: bookmarkId=$bookmarkId", ex)
            baseMapper.selectById(bookmarkId)
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
            log.debug("[parseAndNotice-4] 书签仍为 PENDING(多半是抓取服务暂不可用)，节点保持 LOADING 等待重投递: nodeId=$nodeId, bookmarkId=$bookmarkId")
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
        // BOOKMARK_LOADING 状态、也脱离 drainStuckLoading 的重投递范围，且 bookmark_id 提前绑死在一条
        // 还没抓到内容的记录上。保持不重绑、不收口、直接返回，节点(及 bookmark_id='LOADING' 占位)
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
        val layoutNode: UserLayoutNodeEntity = txTemplate.execute {
            layoutNodeMapper.selectById(layoutNodeId)
                ?.apply { type = NodeTypeEnum.BOOKMARK }
                ?.also {
                    bookmarkUserLinkService.resetBookmarkId(uid, userLinkId, entity.id)
                    layoutNodeMapper.updateById(it)
                }
        } ?: run {
            log.debug("[parseAndResetUserItem] 布局节点已被删除，放弃推送: nodeId=$layoutNodeId, uid=$uid")
            return
        }
        showForDesktop(userLinkId)
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
    private fun parseBookmark(bookmark: BookmarkEntity, cacheMode: CacheMode = CacheMode.DEFAULT): BookmarkEntity {
        val lockKey = ParseLock.bookmark(bookmark.id)
        val token = parseLock.acquire(lockKey, PARSE_LOCK_TTL) ?: run {
            log.debug("[parseBookmark] 该书签已有解析在途，跳过本次: bookmarkId=${bookmark.id}, urlHost=${bookmark.urlHost}")
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
        bookmark: BookmarkEntity,
        cacheMode: CacheMode = CacheMode.DEFAULT,
    ): BookmarkEntity {
        log.debug("[parseBookmark] 开始调度解析: bookmarkId=${bookmark.id}, urlHost=${bookmark.urlHost}")
        val existing = baseMapper.selectById(bookmark.id)
        if (existing != null && existing.verifyFlag) {
            log.debug("[parseBookmark] 书签已手动认证(verifyFlag=true), 跳过解析直接返回: bookmarkId=${bookmark.id}")
            return existing
        }

        // 非域名类型(本地/IP/其他)不进行网络抓取：直接标记为可用，
        // 前端会对这类书签展示统一的圆圈图标，不依赖抓取到的标题/图标。
        if (WebsiteParser.classifyLinkType(bookmark.urlHost) != BookmarkLinkType.DOMAIN) {
            log.debug("[parseBookmark] 非域名类型，跳过抓取: bookmarkId=${bookmark.id}, urlHost=${bookmark.urlHost}")
            // markParseSucceeded 顺带写上调度列：这类书签被 pingSweep 的 DOMAIN 过滤排除在外，
            // 调度列对它们没有实际作用，但宁可多余，也不要留下一批 next_check_at 永远为 NULL
            // 的记录 —— 那会让「NULL 视为到期」的兜底规则每轮都把它们捞出来
            return bookmark.markParseSucceeded().also { baseMapper.insertOrUpdate(it) }
        }

        val mode = if (projectConfig.useThirdPartyParser) "远程scrapper" else "本地Jsoup"
        log.debug("[parseBookmark] 选择解析模式: $mode, bookmarkId=${bookmark.id}")
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
    private fun checkNsfw(bookmark: BookmarkEntity) {
        val siteId = bookmark.siteId.takeIf { it.isNotBlank() } ?: run {
            log.debug("[checkNsfw] 书签未挂站点，跳过: bookmarkId=${bookmark.id}")
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
            log.warn("[checkNsfw] NSFW 检测失败(忽略): bookmarkId=${bookmark.id}, siteId=$siteId, err=${it.message}")
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
    private fun parseLocally(bookmark: BookmarkEntity): BookmarkEntity {
        log.debug("[parseLocally] 开始本地解析(Jsoup): bookmarkId=${bookmark.id}, rawUrl=${bookmark.rawUrl}")
        // 同 parseByApi：successInit 会覆盖 title/description/appName，先留一份人工值
        val manual = bookmark.copy()
        val wrapper = runCatching { WebsiteParser.parse(bookmark.rawUrl) }.getOrElse {
            log.debug("[parseLocally] 页面抓取失败: bookmarkId=${bookmark.id}, err=${it.message}")
            bookmark.markParseUnreachable(it.message).also { b -> baseMapper.insertOrUpdate(b) }
            log.warn("[parseLocally] 页面抓取失败: bookmarkId=${bookmark.id}, err=${it.message}")
            return bookmark
        }
        log.debug("[parseLocally] 页面抓取成功, 开始填充元信息: bookmarkId=${bookmark.id}, title=${wrapper.title}")
        val previousTitle = bookmark.title
        bookmark.successInit(wrapper)
        bookmark.scheduleAfterParseSuccess()
        if (!manual.isLocked(BookmarkLockedField.APP_NAME)) inferAndSetAppName(bookmark, previousTitle)
        bookmark.restoreLockedFields(manual)
        baseMapper.insertOrUpdate(bookmark)
        // 本地解析路径（Jsoup）不产出契约资产，图标改由 scrapper 路径统一落 site_asset；
        // 这里只保住主表的文字信息，避免两套解析各写一份互相打架
        log.debug("[parseLocally] 本地解析全部完成: bookmarkId=${bookmark.id}, parseStatus=${bookmark.parseStatus}, appName=${bookmark.appName}")
        return bookmark
    }

    /** 抓取耗时，落进 `scrape_snapshot.duration_ms`。此前这里一律硬编码 0，那一列等于没数据。 */
    private fun elapsedMs(startedAt: Long): Int = (System.currentTimeMillis() - startedAt).toInt()

    /**
     * 落一条失败快照。
     *
     * 只记"这个站点抓不到"这一事实；我方服务不可用（[isScrapperUnavailable]）的情况不该走到这里。
     * 快照纯属诊断数据，写不进去也不能反过来影响解析主流程，故失败只记日志。
     */
    private fun recordScrapeFailure(bookmark: BookmarkEntity, e: Throwable, startedAt: Long) {
        runCatching {
            siteAssetWriter.persistFailure(bookmark.id, bookmark.rawUrl, e.message, elapsedMs(startedAt))
        }.onFailure {
            log.warn("[recordScrapeFailure] 失败快照落库失败(忽略): bookmarkId=${bookmark.id}, err=${it.message}")
        }
    }

    /**
     * 远程解析（scrapper）：通过自部署的 bookmarkify-scrapper 获取元信息 + favicon base64 + LOGO/OG 存 OSS
     */
    private fun parseByApi(bookmark: BookmarkEntity, cacheMode: CacheMode = CacheMode.DEFAULT): BookmarkEntity {
        log.debug("[parseByApi] 开始远程解析(scrapper): bookmarkId=${bookmark.id}, rawUrl=${bookmark.rawUrl}, cacheMode=$cacheMode")
        val startedAt = System.currentTimeMillis()
        // 抓取会覆盖 title/description/appName，先留一份人工值，落库前还原被锁定的那些
        val manual = bookmark.copy()
        return runCatching {
            apiService.scrape(bookmark.rawUrl, apiService.scrapeRequest(bookmark.rawUrl, cacheMode))
        }.fold(
            onSuccess = { vo ->
                log.debug("[parseByApi] scrapper 返回成功: bookmarkId=${bookmark.id}, title=${vo.title}, source=${vo.primarySource}, assets=${vo.assets.size}")
                val previousTitle = bookmark.title
                vo.applyTo(bookmark)
                bookmark.scheduleAfterParseSuccess()
                // 简称优先用 manifest.short_name（W3C 就是为"图标下方空间受限"定义的），
                // 拿不到才退回 DeepSeek 推断。这一步最长 10s，必须留在事务外——
                // 否则一个数据库连接要陪着外部 API 一起干等。
                bookmark.appName = vo.shortName?.takeIf { n -> n.isNotBlank() }
                // appName 已被人工锁定时连推断都不必做：结果反正要被 restoreLockedFields 丢掉，
                // 白烧一次 10s 的 LLM 往返还占着解析线程
                if (bookmark.appName.isNullOrBlank() && !manual.isLocked(BookmarkLockedField.APP_NAME)) {
                    inferAndSetAppName(bookmark, previousTitle)
                }
                bookmark.restoreLockedFields(manual)

                // 主表字段与「快照 + 元数据 + 资产」必须一起提交。分成两个事务时，中间失败会留下
                // parse_status=SUCCESS 却一条 site_asset 都没有的书签：前端永远渲染首字母色块，
                // 而 checkAll/retryUnreachable/livenessCheck 三个对账任务都按 parse_status 过滤，
                // 没有任何一个会回来补这条。抓取此时已经结束，合并进一个短事务不增加持锁时间。
                txTemplate.execute {
                    baseMapper.insertOrUpdate(bookmark)
                    siteAssetWriter.persist(bookmark.siteId, bookmark.id, bookmark.rawUrl, vo, elapsedMs(startedAt), bookmark.isRootPage)
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
                log.debug("[parseByApi] 第三方API解析全部完成: bookmarkId=${bookmark.id}, assets=${vo.assets.size}")
                bookmark
            },
            onFailure = { e ->
                // 抓取服务没起/配错时保持 PENDING 原样，交给 checkAll() 之后重来，
                // 别把我方故障记成书签失联（异步链路不抛，抛了也没人接）。
                // 也不落失败快照：那记录的是我方故障，不是这个站点的抓取事实
                if (e.isScrapperUnavailable()) {
                    log.warn("[parseByApi] 抓取服务不可用，保留待抓取状态: bookmarkId=${bookmark.id}, err=${e.message}")
                    return@fold bookmark
                }
                log.debug("[parseByApi] API 调用失败: bookmarkId=${bookmark.id}, err=${e.message}")
                // 失败也留快照：只把书签标成 UNREACHABLE 的话，事后只知道"抓不到"，
                // 不知道抓的是哪个 URL、报了什么错、耗了多久。persistFailure 一直没人调用
                recordScrapeFailure(bookmark, e, startedAt)
                bookmark.markParseUnreachable(e.message).also { baseMapper.insertOrUpdate(it) }
            }
        )
    }

    // ────── 管理后台：书签详情组装 ──────

    /**
     * 把某个书签的资产行转成后台视图。
     *
     * [size] 是希望 OSS 返回的边长：列表页的格子只有 32px，回原图纯属浪费带宽；详情页要
     * 看清原图则传 null 不缩放。矢量图任何情况下都不缩放（缩放会被栅格化）。
     */
    private fun toAssetVOs(assets: List<SiteAssetEntity>, size: Int? = null): List<SiteAssetAdminVO> {
        // 出现次数 >1 的 hash 说明同一张图被多个 extractor 共用，据此在后台标出
        // "该站没有独立 LOGO"，省得人工逐张比对
        val dupHashes = assets.mapNotNull { it.contentHash }
            .groupingBy { it }.eachCount().filterValues { it > 1 }.keys

        // 一次取全这批资产的账本行，别在下面的 map 里逐张查
        val objectByFileId = siteAssetResolver.objectsOf(assets)

        return assets.map { a ->
            SiteAssetAdminVO(
                id = a.id,
                role = a.role,
                extractor = a.extractor,
                quality = a.quality,
                // 私有读桶里的对象直连会 403，后台预览换成签名地址。
                // file_id 优先，storage_url 兜底（未回填的行与存量完整 URL）
                url = a.fileId?.let { objectByFileId[it] }
                    .let { row ->
                        OssUtils.signAsset(
                            row?.objectKey ?: a.storageUrl,
                            size,
                            row?.immutable == true,
                            mime = row?.mime ?: a.mime,
                            isVector = a.isVector,
                        )
                    } ?: a.resolvedUrl,
                resolvedUrl = a.resolvedUrl,
                width = a.width,
                height = a.height,
                byteSize = a.byteSize,
                mime = a.mime,
                isVector = a.isVector,
                contentHash = a.contentHash,
                isPrimary = a.isPrimary,
                duplicateOfOther = a.contentHash != null && a.contentHash in dupHashes,
                errorMsg = a.errorMsg,
            )
        }
    }

    /**
     * 组装管理后台的书签详情：主表字段 + **全部**图片资产 + 各展示模式的设置。
     *
     * 刻意返回全部资产而非仅选中的那张 —— 排查"这站为什么用了张丑图"时需要看到它到底
     * 声明了哪些图、各自出处是什么、有没有互相撞 hash。
     */
    private fun adminDetail(bookmarkId: String): BookmarkAdminVO {
        val bookmark = baseMapper.selectById(bookmarkId) ?: throw CommonException(ErrorType.E102)
        val vo = BookmarkAdminVO(bookmark)

        vo.assets = toAssetVOs(siteAssetResolver.assetsOf(bookmarkId))

        vo.displayPrefs = DisplayMode.entries.map { mode ->
            val pref = siteDisplayPrefService.find(bookmark.siteId, mode)
            val resolved = siteAssetResolver.resolveOne(bookmarkId, mode)
            SiteDisplayPrefVO(
                displayMode = mode,
                iconPadding = pref?.iconPadding ?: 25,
                iconBgColor = pref?.iconBgColor,
                pinnedAssetId = pref?.pinnedAssetId,
                previewUrl = resolved.url,
                monogram = resolved.monogram,
            )
        }

        runCatching {
            vo.categories = bookmarkCategoryService.categoriesOf(listOf(bookmarkId))[bookmarkId]
                .orEmpty().map { CategoryVO(it.id, it.slug, it.name, it.color) }
        }.onFailure { log.warn("[adminDetail] 分类回填失败(忽略): ${it.message}") }

        return vo
    }

    // ────── 私有工具 ──────

    /**
     * 通过 DeepSeek 推断书签简称，有结果则覆盖 appName，失败静默忽略。
     *
     * [previousTitle] 是本次解析开始前（覆盖 title 之前）该书签原有的标题：checkAll/retryUnreachableBookmarks
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
     * 把一个布局节点从 BOOKMARK_LOADING 收口成 BOOKMARK，但不绑定任何 canonical 书签。
     *
     * 用于「这个网址永远抓不成书签」的终局（如 javascript: 小书签，或补投递到达上限仍不收口）：
     * 留在 LOADING 会被 [drainStuckLoading] 当作待办无限重投，而它的展示数据本来就只能来自
     * 用户自己填的那份。
     *
     * 关联行的 `bookmark_id` 必须从 `'LOADING'` 改成 NULL，不能原样留着：那个字面量的含义是
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
            .let { it.initDisplay(it.bookmarkId?.let { id -> siteAssetResolver.resolveOne(id, DisplayMode.LIST) }, DisplayMode.LIST) }

    /** 按 canonical 四元组精确命中一条页面记录。 */
    private fun getByCanonical(siteId: String, urlPath: String, urlQuery: String, urlFragment: String): BookmarkEntity? =
        ktQuery().eq(BookmarkEntity::siteId, siteId)
            .eq(BookmarkEntity::urlPath, urlPath)
            .eq(BookmarkEntity::urlQuery, urlQuery)
            .eq(BookmarkEntity::urlFragment, urlFragment)
            .one()

    private fun getByUrl(siteId: String, w: BookmarkUrlWrapper): BookmarkEntity? =
        getByCanonical(siteId, w.urlPath ?: "/", w.urlQuery, w.urlFragment)

    /** 同上，但站点未知时用（先按 host 找 site；site 都没有就必然没有页面记录）。 */
    private fun getByUrl(w: BookmarkUrlWrapper): BookmarkEntity? =
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
    private fun getOrCreateByUrl(urlWrapper: BookmarkUrlWrapper): BookmarkEntity {
        val site = siteService.getOrCreateByHost(urlWrapper.urlHost, urlWrapper.urlScheme)
        getByUrl(site.id, urlWrapper)?.let { return it }
        return try {
            BookmarkEntity(urlWrapper, site.id).also { save(it) }
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
    private fun BookmarkEntity.needRecheckOnAdd(): Boolean {
        if (parseStatus == ParseStatusEnum.PENDING || verifyFlag) return false
        if (parseStatus == ParseStatusEnum.SUCCESS && isActivity) return false
        val lastCheck = updateTime ?: return true
        return LocalDateTimeUtil.between(lastCheck, LocalDateTime.now(), ChronoUnit.MINUTES) >= DEAD_RECHECK_COOLDOWN_MINUTES
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
        // PENDING 正常几分钟内就会被异步解析消费掉；超过这个时长还没变化，基本可判定是解析事件丢失/进程重启丢弃，
        // 而不是网站本身的活性问题——所以用远短于活性检测的分钟级窗口，而不是活性检测的小时/天级窗口。
        private const val CHECKALL_PENDING_STALE_MINUTES = 30L
        /**
         * 巡检候选的调度游标表达式。
         *
         * `next_check_at IS NULL` 一律视为「到期」（宁可多查一次，也不能让一条没有调度状态的
         * 记录永久失踪），但把它写成 `next_check_at <= ? OR next_check_at IS NULL` 有两个代价：
         * 那个 OR 不可 sarg，而 `ORDER BY next_check_at ASC NULLS FIRST` 又与 btree 升序默认的
         * NULLS LAST 相反 —— 两件事合起来意味着每一轮的 count 与 select 都要把该状态下的全部
         * 记录排一遍。折成 COALESCE 之后谓词与排序用的是同一个表达式，正好配一条表达式索引
         * (`idx_bookmark_due_check`)，也和 `checkAll` 里 `COALESCE(update_time, create_time)`
         * 的既有写法一致。
         *
         * 纪元时间充当「从未调度过」的哨兵：它永远小于任何 now，语义上就是最优先。
         */
        private const val DUE_CURSOR = "COALESCE(next_check_at, TIMESTAMP '1970-01-01')"
        private const val DUE_CLAUSE = "$DUE_CURSOR <= {0}"
        private const val RETRY_UNREACHABLE_BATCH_SIZE = 50
        private const val LIVENESS_CHECK_BATCH_SIZE = 200
        // 复活探测的批量。取得小是刻意的：这批记录按定义已经两个多月探不通，恢复与否
        // 没有任何时效压力，这个任务存在只是为了保证「有一条回来的路」，不是为了跑得快
        private const val REVIVE_ARCHIVED_BATCH_SIZE = 50
        // 归档记录多久复活探测一次。与退避曲线的顶端(abnormal × 16 ≈ 16 天)同量级，
        // 略长一点，表示它已经不属于常规巡检的节奏了
        private const val ARCHIVE_RECHECK_DAYS = 30L
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
        // 巡检向解析池投递时留出的余量。比 DRAIN_QUEUE_HEADROOM 更保守：补投递面向的是
        // 用户桌面上正在转圈的格子（有人在等），而巡检的重新抓取是纯后台内容刷新（没人在等），
        // 两者抢同一个队列时，该让路的显然是后者。
        private const val SWEEP_PARSE_QUEUE_HEADROOM = 150
        // 巡检投递重新抓取前写入的保护性游标间隔，见 protectSchedule。
        // 取 1h（与调度周期同量级）而不是更短：解析链路正常会立刻用真实结论覆盖它，
        // 这个值只在那几条不落库的异常出口上生效，太短会让一个持续故障变成每分钟重试。
        private const val TRIGGERED_PARSE_PROTECT_HOURS = 1L
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
        // 巡检锁的存活时间。取值要大于「一轮巡检的最坏耗时」——并行 8 路、单条超时 15s、
        // 批量 200 条，理论最坏约 6~7 分钟，再留足富余；同时必须小于调度周期(1h)，
        // 否则进程被强杀后这把锁会一直挡住后续所有轮次。
        private val SWEEP_LOCK_TTL: Duration = Duration.ofMinutes(30)
        // 失效书签在「用户新增」时的重检冷却：既保证用户手动添加能立刻触发一次重试，
        // 又避免同一个已经挂掉的站点被连续添加时把 ping/抓取打满。
        private const val DEAD_RECHECK_COOLDOWN_MINUTES = 10L
        // 后台列表里图片格子是 32px，2x 屏取 128 已经绰绰有余，回原图只是白烧带宽
        private const val ADMIN_LIST_ASSET_SIZE = 128
    }
}
