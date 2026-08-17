package top.tcyeee.bookmarkify.server.admin

import com.baomidou.mybatisplus.core.metadata.IPage
import com.baomidou.mybatisplus.extension.kotlin.KtUpdateWrapper
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Async
import org.springframework.stereotype.Service
import top.tcyeee.bookmarkify.config.async.AsyncConfig
import top.tcyeee.bookmarkify.config.cache.RedisType
import top.tcyeee.bookmarkify.config.exception.CommonException
import top.tcyeee.bookmarkify.config.exception.ErrorType
import top.tcyeee.bookmarkify.entity.*
import top.tcyeee.bookmarkify.entity.dto.SimilarIngestUpdate
import top.tcyeee.bookmarkify.entity.dto.SimilarSite
import top.tcyeee.bookmarkify.entity.dto.scrape.CacheMode
import top.tcyeee.bookmarkify.entity.dto.scrape.ScrapeResponse
import top.tcyeee.bookmarkify.entity.dto.scrape.cached
import top.tcyeee.bookmarkify.entity.dto.scrape.description
import top.tcyeee.bookmarkify.entity.dto.scrape.faviconUrl
import top.tcyeee.bookmarkify.entity.dto.scrape.logoUrl
import top.tcyeee.bookmarkify.entity.dto.scrape.primarySource
import top.tcyeee.bookmarkify.entity.dto.scrape.screenshotUrl
import top.tcyeee.bookmarkify.entity.dto.scrape.socialUrl
import top.tcyeee.bookmarkify.entity.dto.scrape.title
import top.tcyeee.bookmarkify.entity.entity.BookmarkEntity
import top.tcyeee.bookmarkify.entity.entity.CategorySource
import top.tcyeee.bookmarkify.entity.entity.PageEntity
import top.tcyeee.bookmarkify.entity.entity.SiteAssetEntity
import top.tcyeee.bookmarkify.entity.enums.DisplayMode
import top.tcyeee.bookmarkify.entity.enums.PageLockedField
import top.tcyeee.bookmarkify.entity.enums.ParseStatusEnum
import top.tcyeee.bookmarkify.mapper.PageMapper
import top.tcyeee.bookmarkify.server.IApiService
import top.tcyeee.bookmarkify.server.IBookmarkCategoryService
import top.tcyeee.bookmarkify.server.IBookmarkService
import top.tcyeee.bookmarkify.server.IBookmarkUserLinkService
import top.tcyeee.bookmarkify.server.ISiteService
import top.tcyeee.bookmarkify.server.asset.IconResolver
import top.tcyeee.bookmarkify.server.asset.SiteAssetQuery
import top.tcyeee.bookmarkify.server.asset.SiteAssetWriter
import top.tcyeee.bookmarkify.server.liveness.PageScheduleWriter
import top.tcyeee.bookmarkify.server.parse.PageParseStateWriter
import top.tcyeee.bookmarkify.server.parse.PageParseStateWriter.Companion.isRefusedTarget
import top.tcyeee.bookmarkify.server.parse.PageParseStateWriter.Companion.isScrapperUnavailable
import top.tcyeee.bookmarkify.utils.OssUtils
import top.tcyeee.bookmarkify.utils.RedisUtils
import top.tcyeee.bookmarkify.utils.SocketUtils
import top.tcyeee.bookmarkify.utils.WebsiteParser
import java.time.LocalDateTime

/**
 * 管理后台的书签操作。见 [IBookmarkAdminService]。
 *
 * 从 `BookmarkServiceImpl` 拆出（2026-08-11）。它与用户侧那条链路的关系是**调用方**而非同居者：
 * 需要抓取时走 [IApiService]，需要解析一整条链路时走 [IBookmarkService] 的公开入口，
 * 需要写终态时走 [PageParseStateWriter] —— 拆分前这三件事都是直接摸私有方法完成的，
 * 于是"改一个后台按钮的行为"与"改加书签主链路"在同一个 2881 行的类里没有边界。
 */
@Service
class BookmarkAdminService(
    private val pageMapper: PageMapper,
    private val siteService: ISiteService,
    private val iconResolver: IconResolver,
    private val siteAssetQuery: SiteAssetQuery,
    private val siteAssetWriter: SiteAssetWriter,
    private val bookmarkCategoryService: IBookmarkCategoryService,
    private val bookmarkUserLinkService: IBookmarkUserLinkService,
    private val adminUserViewAssembler: AdminUserViewAssembler,
    private val apiService: IApiService,
    private val bookmarkService: IBookmarkService,
    private val parseStateWriter: PageParseStateWriter,
    private val scheduleWriter: PageScheduleWriter,
) : IBookmarkAdminService {

    private val log = LoggerFactory.getLogger(javaClass)

    override fun adminListAll(params: BookmarkSearchParams): IPage<BookmarkAdminVO> {
        val entityPage = pageMapper.selectPage(params.toPage(), params.toWrapper())
        // NSFW 是站点级判定，而 BookmarkAdminVO 是靠 BeanUtil 从 PageEntity 整体拷贝的 ——
        // `bookmark.nsfw` 删列之后那次拷贝什么也拷不到，vo.nsfw 会**静默**停在默认的 false，
        // 后台从此再也标不出违规站点，且没有任何报错。所以这里必须显式按 siteId 回填。
        // siteId 要在 convert **之前**取：IPage.convert 是就地替换 records，之后拿到的已是 VO。
        val siteIdOf = entityPage.records.associate { it.id to it.siteId }
        val page = entityPage.convert { BookmarkAdminVO(it) }
        val siteMap = siteService.mapByIds(siteIdOf.values.toSet())
        page.records.forEach { vo -> vo.nsfw = siteMap[siteIdOf[vo.id]]?.nsfw ?: false }
        // 后台列表按 role 分列展示 favicon/logo/社交图，缺哪张要一眼可见，所以资产必须随列表下发；
        // 用一条 in 查询批量取回避免 N+1，签名按列表格子的尺寸缩放
        runCatching {
            val ids = page.records.map { it.id }
            val assetMap = siteAssetQuery.assetsOfBatch(ids)
            page.records.forEach { vo -> vo.assets = toAssetVOs(assetMap[vo.id].orEmpty(), ADMIN_LIST_ASSET_SIZE) }
            // 规则在两种模式下各选了哪张图 —— 取图优先级是反的，只看资产列表推不出结果，
            // 所以必须随列表下发。批量解析，查询条数只与展示模式个数有关，与行数无关
            val resolvedByMode = DisplayMode.entries.associateWith { iconResolver.resolveBatch(ids, it) }
            page.records.forEach { vo ->
                vo.iconRenders = DisplayMode.entries.map { mode ->
                    val resolved = resolvedByMode[mode]?.get(vo.id)
                    IconRenderVO(
                        displayMode = mode,
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
        // 抓取原样的元数据（走了哪一层、HTTP 状态码、字段级出处…）。一条 in 查询，缺行的
        // 页面保持 null —— 「从没抓成功过」与「抓过但站点没声明」在后台是两个结论
        runCatching {
            val metaMap = siteAssetWriter.pageMetaOfBatch(page.records.map { it.id })
            page.records.forEach { vo -> vo.pageMeta = metaMap[vo.id]?.let(::PageMetaAdminVO) }
        }.onFailure { log.warn("[adminListAll] 页面元数据回填失败(忽略): ${it.message}") }
        runCatching { fillOwners(page.records) }
            .onFailure { log.warn("[adminListAll] 收录者回填失败(忽略): ${it.message}") }
        return page
    }

    /**
     * 给后台列表回填「收录者」：最早把该书签加进来的那个用户，外加收录人数。
     *
     * 书签表没有属主列 —— 它是全站共享的规范化记录，归属只存在于 `bookmark_user_link`。所以
     * 这里按 pageId 批量捞关联行（一次 in 查询，不是逐行查），同一书签内按 createTime 取
     * 最早的一条当收录者。软删的关联不算：用户把书签从桌面删掉之后，他就不该再作为收录者出现。
     */
    private fun fillOwners(records: List<BookmarkAdminVO>) {
        if (records.isEmpty()) return
        val links = bookmarkUserLinkService.ktQuery()
            .`in`(BookmarkEntity::pageId, records.map { it.id })
            .eq(BookmarkEntity::deleted, false)
            .list()
        if (links.isEmpty()) return
        val byBookmark = links.groupBy { it.pageId }
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

    override fun adminRefetch(pageId: String): BookmarkRefetchVO {
        val bookmark = pageMapper.selectById(pageId) ?: throw CommonException(ErrorType.E102)
        log.debug("[adminRefetch] 管理员重新获取书签元信息: pageId=$pageId, rawUrl=${bookmark.rawUrl}")
        // 仅预览，不落库：重新抓取一次，拿到新的标题与小图标。
        // BYPASS 是必须的——命中 scrapper 缓存的"重新获取"等于没获取
        val vo = apiService.scrape(bookmark.rawUrl, apiService.scrapeRequest(bookmark.rawUrl, CacheMode.BYPASS))
        val iconUrl = vo.faviconUrl
        // 预览与应用之间用 Redis 暂存完整抓取结果，确保「所见即所存」且避免应用时再抓一次造成漂移
        RedisUtils.set(RedisType.BOOKMARK_REFETCH, pageId, vo)
        // vo.logoUrl / vo.faviconUrl 已在 ScrapeResponseExt 里过了 OssUtils.signAsset，
        // 这里不能再签一次(会把签名 query 当成 key 的一部分)。未抓到则为 null，交由前端说明。
        val logoUrl = vo.logoUrl?.takeIf { it.isNotBlank() }
        log.debug("[adminRefetch] 重新获取完成并已暂存: pageId=$pageId, newTitle=${vo.title}, hasLogo=${logoUrl != null}")
        return BookmarkRefetchVO(title = vo.title, iconUrl = iconUrl, logoUrl = logoUrl)
    }

    override fun adminCheckLiveness(pageId: String): BookmarkLivenessVO {
        val bookmark = pageMapper.selectById(pageId) ?: throw CommonException(ErrorType.E102)
        log.debug("[adminCheckLiveness] 管理员触发书签活性检测: pageId=$pageId, rawUrl=${bookmark.rawUrl}")
        val startedAt = System.currentTimeMillis()
        return runCatching { apiService.queryWebsiteInfo(bookmark.rawUrl) }.fold(
            onSuccess = { vo ->
                parseStateWriter.markSucceeded(bookmark)
                pageMapper.updateById(bookmark)
                log.debug("[adminCheckLiveness] 检测成功: pageId=$pageId, source=${vo.primarySource}")
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
                // 我方不可用、或我方拒绝抓这个目标——两者都不是站点的事实，不改动书签状态，
                // 原样抛给管理员看理由（拒绝的文案里写了拒的是什么、为什么）
                if (e.isScrapperUnavailable() || e.isRefusedTarget()) {
                    log.warn("[adminCheckLiveness] 未实际检测，不改动书签状态: pageId=$pageId, err=${e.message}")
                    throw e
                }
                parseStateWriter.recordScrapeFailure(bookmark, e, startedAt)
                parseStateWriter.markUnreachable(bookmark, e.message)
                pageMapper.updateById(bookmark)
                log.debug("[adminCheckLiveness] 检测失败: pageId=$pageId, err=${e.message}")
                BookmarkLivenessVO(
                    success = false,
                    errorMsg = e.message,
                    isActivity = false,
                    parseStatus = ParseStatusEnum.UNREACHABLE,
                )
            },
        )
    }

    override fun adminApplyRefetch(pageId: String, params: BookmarkRefetchApplyParams): BookmarkAdminVO {
        val bookmark = pageMapper.selectById(pageId) ?: throw CommonException(ErrorType.E102)
        val vo = RedisUtils.get<ScrapeResponse>(RedisType.BOOKMARK_REFETCH, pageId)
            ?: throw CommonException(ErrorType.E112)
        log.debug("[adminApplyRefetch] 应用重新获取结果: pageId=$pageId, useNewTitle=${params.useNewTitle}, useNewIcon=${params.useNewIcon}, useNewLogo=${params.useNewLogo}")

        // 管理员显式选择采用抓取来的标题：这个字段此后不再是人工值，解锁交回自动链路
        if (params.useNewTitle) {
            bookmark.title = vo.title
            bookmark.unlock(PageLockedField.TITLE)
        }
        // 资产是整体替换的：图标与 LOGO 同源于一次抓取，没法只采用其中一半而保持一致，
        // 因此只要任一开关打开就整批落库。这两个开关是「要不要这一批新图」，不是「用哪一张」——
        // 后者是规则的事（AssetRolePolicy），没有、也不再有人工逐张钉图的入口
        if (params.useNewIcon || params.useNewLogo) {
            // 回放的是 adminRefetch 暂存在 Redis 里的那次抓取结果，本次没发生网络请求，
            // 耗时记 0 是准确的（真实耗时属于当初那次抓取）
            siteAssetWriter.persist(bookmark.siteId, pageId, bookmark.rawUrl, vo, 0, bookmark.isRootPage)
        }
        bookmark.updateTime = LocalDateTime.now()
        // 管理员刚刚亲眼确认过这份内容是新的，等价于一次成功的重新抓取：
        // 不推进 lastParseAt 的话，这条记录仍会被内容刷新巡检当成过期的再抓一遍
        scheduleWriter.advanceAfterParseSuccess(bookmark)
        pageMapper.insertOrUpdate(bookmark)
        RedisUtils.del(RedisType.BOOKMARK_REFETCH, pageId)
        log.debug("[adminApplyRefetch] 应用完成: pageId=$pageId, title=${bookmark.title}")
        return adminDetail(pageId)
    }

    override fun adminRefresh(pageId: String): BookmarkAdminVO {
        val bookmark = pageMapper.selectById(pageId) ?: throw CommonException(ErrorType.E102)
        log.debug("[adminRefresh] 管理员一键更新书签信息: pageId=$pageId, rawUrl=${bookmark.rawUrl}")
        val startedAt = System.currentTimeMillis()
        runCatching { apiService.scrape(bookmark.rawUrl, apiService.scrapeRequest(bookmark.rawUrl, CacheMode.BYPASS)) }.fold(
            onSuccess = { vo ->
                parseStateWriter.markSucceeded(bookmark).apply {
                    title = vo.title
                    description = vo.description
                    // 「一键更新」是管理员显式要求采用抓取值，标题/简介此后不再是人工值 → 解锁
                    unlock(PageLockedField.TITLE, PageLockedField.DESCRIPTION)
                }
                siteAssetWriter.persist(bookmark.siteId, pageId, bookmark.rawUrl, vo, PageParseStateWriter.elapsedMs(startedAt), bookmark.isRootPage)
                log.debug("[adminRefresh] 更新成功: pageId=$pageId, title=${bookmark.title}")
            },
            onFailure = { e ->
                if (e.isScrapperUnavailable() || e.isRefusedTarget()) {
                    log.warn("[adminRefresh] 未实际抓取，不改动书签状态: pageId=$pageId, err=${e.message}")
                    throw e
                }
                parseStateWriter.recordScrapeFailure(bookmark, e, startedAt)
                parseStateWriter.markUnreachable(bookmark, e.message)
                log.debug("[adminRefresh] 更新失败: pageId=$pageId, err=${e.message}")
            },
        )
        pageMapper.updateById(bookmark)
        return adminDetail(pageId)
    }

    override fun adminSyncFromExternalScrape(url: String, vo: ScrapeResponse): Boolean {
        // findListByUrl 内部就是 urlWrapper + 按 canonical 四元组查，与原先的私有 getByUrl 逐字等价
        val bookmark = bookmarkService.findListByUrl(listOf(url)).firstOrNull() ?: return false
        log.debug("[adminSyncFromExternalScrape] 网站管理活性检测命中已有书签，同步落库: pageId=${bookmark.id}, url=$url")
        parseStateWriter.markSucceeded(bookmark).apply {
            title = vo.title
            description = vo.description
        }
        siteAssetWriter.persist(bookmark.siteId, bookmark.id, bookmark.rawUrl, vo, 0, bookmark.isRootPage)
        pageMapper.updateById(bookmark)
        log.debug("[adminSyncFromExternalScrape] 同步完成: pageId=${bookmark.id}, title=${bookmark.title}")
        return true
    }

    override fun adminUpdateBasicInfo(pageId: String, params: BookmarkBasicInfoUpdateParams): BookmarkAdminVO {
        val bookmark = pageMapper.selectById(pageId) ?: throw CommonException(ErrorType.E102)
        // 手工改过的字段加锁，否则定期重抓会在下一个刷新周期把它静默改回抓取值
        params.title?.let { bookmark.title = it; bookmark.lock(PageLockedField.TITLE) }
        params.description?.let { bookmark.description = it; bookmark.lock(PageLockedField.DESCRIPTION) }
        // 简称的加解锁与上面两个相反：**填了值才锁，清空则解锁** —— 空的简称本来就会被下一次
        // 抓取用 manifest.short_name 或 LLM 推断补上，锁住一个空值只会让它永远补不上。
        // 这段逻辑随 appName 一起从已删除的 adminUpdateIcon 搬过来（见 BookmarkBasicInfoUpdateParams）
        params.appName?.let {
            bookmark.appName = it.takeIf(String::isNotBlank)
            if (bookmark.appName == null) bookmark.unlock(PageLockedField.APP_NAME)
            else bookmark.lock(PageLockedField.APP_NAME)
        }
        bookmark.updateTime = LocalDateTime.now()
        // 逐列显式 set，不能用 updateById：它跳过 null 字段，而这里有**两列都能合法地变成 NULL**
        // ——清空简称，以及解掉最后一把锁时 lockedFields 归 NULL（空集合存 NULL，见 LockedFieldCodec）。
        // 用 updateById 的后果不是报错，是「清空按钮点了没反应」和「锁永远解不掉」
        pageMapper.update(
            null,
            KtUpdateWrapper(PageEntity::class.java)
                .eq(PageEntity::id, pageId)
                .set(PageEntity::title, bookmark.title)
                .set(PageEntity::description, bookmark.description)
                .set(PageEntity::appName, bookmark.appName)
                .set(PageEntity::lockedFields, bookmark.lockedFields)
                .set(PageEntity::updateTime, bookmark.updateTime)
        )
        log.debug(
            "[adminUpdateBasicInfo] 管理员手动更新基础信息: pageId=$pageId, title=${bookmark.title}, " +
                "appName=${bookmark.appName}, lockedFields=${bookmark.lockedFields}"
        )
        return adminDetail(pageId)
    }

    override fun adminUpdateCategories(pageId: String, categoryIds: List<String>): List<CategoryVO> {
        pageMapper.selectById(pageId) ?: throw CommonException(ErrorType.E102)
        bookmarkCategoryService.replaceLinks(pageId, categoryIds, CategorySource.MANUAL)
        return loadCategoryVOs(pageId)
    }

    /**
     * 后台「重新 AI 归类」：走开词表那条路径 —— AI 提议的新分类会被建进 `category` 字典。
     *
     * 自动抓取链路仍然用闭词表的 [IBookmarkCategoryService.categorize]，两者不能互换：
     * 让爬虫有权写分类字典，收录量一上来分类体系就散了。
     */
    override fun adminRecategorize(pageId: String): List<CategoryVO> {
        val bookmark = pageMapper.selectById(pageId) ?: throw CommonException(ErrorType.E102)
        bookmarkCategoryService.categorizeAllowingNew(bookmark)
        return loadCategoryVOs(pageId)
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
    override fun adminRefetchAssets(pageId: String): BookmarkAssetRefetchVO {
        val bookmark = pageMapper.selectById(pageId) ?: throw CommonException(ErrorType.E102)
        log.debug("[adminRefetchAssets] 管理员重抓图片资产: pageId=$pageId, rawUrl=${bookmark.rawUrl}")
        val startedAt = System.currentTimeMillis()
        // BYPASS：不绕开 scrapper 缓存的话"重新抓取"可能直接命中上一次的结果，等于没抓
        return runCatching {
            apiService.scrape(bookmark.rawUrl, apiService.scrapeRequest(bookmark.rawUrl, CacheMode.BYPASS))
        }.fold(
            onSuccess = { vo ->
                siteAssetWriter.persist(
                    bookmark.siteId, pageId, bookmark.rawUrl, vo, PageParseStateWriter.elapsedMs(startedAt), bookmark.isRootPage,
                )
                val count = vo.assets.size
                log.debug("[adminRefetchAssets] 抓取成功: pageId=$pageId, scrapedAssets=$count")
                BookmarkAssetRefetchVO(success = true, scrapedAssetCount = count, bookmark = adminDetail(pageId))
            },
            onFailure = { e ->
                // 抓取服务本身不可用、或我方拒绝抓这个目标，都要如实报错，
                // 不能伪装成"这个站没有图"——那两种情况我方连试都没试过
                if (e.isScrapperUnavailable() || e.isRefusedTarget()) throw e
                parseStateWriter.recordScrapeFailure(bookmark, e, startedAt)
                log.debug("[adminRefetchAssets] 抓取失败: pageId=$pageId, err=${e.message}")
                BookmarkAssetRefetchVO(
                    success = false, scrapedAssetCount = 0, errorMsg = e.message, bookmark = adminDetail(pageId),
                )
            },
        )
    }

    override fun adminSimilarSites(pageId: String): List<SimilarSite> {
        val bookmark = pageMapper.selectById(pageId) ?: throw CommonException(ErrorType.E102)
        val sites = apiService.inferSimilarSites(bookmark.title, bookmark.description, bookmark.urlHost)
        if (sites.isEmpty()) return sites
        // 把推荐域名按与入库一致的方式归一化为 urlHost，再批量比对本地是否已收录
        val hosts = sites.map { runCatching { WebsiteParser.urlWrapper("https://${it.domain}").urlHost }.getOrNull() }
        val existingHosts = hosts.filterNotNull().distinct()
            .takeIf { it.isNotEmpty() }
            ?.let { bookmarkService.findListByHost(it).map { b -> b.urlHost }.toSet() }
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
        val url = "https://${domain.trim().substringAfter("://")}"
        val wrapper = WebsiteParser.urlWrapper(url)
        bookmarkService.findRootPageByHost(wrapper.urlHost)?.let { return "EXISTS" }
        val bookmark = bookmarkService.getOrCreateCanonical(url)
        // 抓取可能抛异常（本地解析器）或落 UNREACHABLE（scrapper 不可达）；统一以「最终落库状态」判定，
        // 抓到正文(SUCCESS，反爬页面也算)才保留，其余一律删除——保证幻觉域名绝不留在库里。
        runCatching { bookmarkService.parseAndSave(bookmark.id) }
            .onFailure { log.warn("[ingestOneSimilar] 解析异常 domain=$domain: ${it.message}") }
        val saved = pageMapper.selectById(bookmark.id)
        val ok = saved != null && saved.parseStatus == ParseStatusEnum.SUCCESS
        return if (ok) {
            "INGESTED"
        } else {
            pageMapper.deleteById(bookmark.id)
            "SKIPPED"
        }
    }

    private fun loadCategoryVOs(pageId: String): List<CategoryVO> =
        bookmarkCategoryService.categoriesOf(listOf(pageId))[pageId].orEmpty()
            .map { CategoryVO(it.id, it.slug, it.name, it.color) }

    override fun adminGenerateAppName(pageId: String): String? {
        val bookmark = pageMapper.selectById(pageId) ?: throw CommonException(ErrorType.E102)
        val title = bookmark.title?.takeIf { it.isNotBlank() } ?: run {
            log.debug("[adminGenerateAppName] title 为空，跳过生成: pageId=$pageId")
            return null
        }
        log.debug("[adminGenerateAppName] 调用 DeepSeek 生成 appName: pageId=$pageId, title=$title")
        return apiService.inferAppName(title)?.takeIf { it.isNotBlank() }
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
        val objectByFileId = siteAssetQuery.objectsOf(assets)

        return assets.map { a ->
            SiteAssetAdminVO(
                id = a.id,
                role = a.role,
                ownerType = a.ownerType,
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
     * 组装管理后台的书签详情：主表字段 + **全部**图片资产 + 各展示模式下规则选出的渲染结果。
     *
     * 刻意返回全部资产而非仅选中的那张 —— 排查"这站为什么用了张丑图"时需要看到它到底
     * 声明了哪些图、各自出处是什么、有没有互相撞 hash。
     */
    private fun adminDetail(pageId: String): BookmarkAdminVO {
        val bookmark = pageMapper.selectById(pageId) ?: throw CommonException(ErrorType.E102)
        val vo = BookmarkAdminVO(bookmark)
        // 同 adminListAll：NSFW 在站点层，BeanUtil 拷不到，必须显式取
        vo.nsfw = siteService.mapByIds(setOf(bookmark.siteId))[bookmark.siteId]?.nsfw ?: false

        vo.assets = toAssetVOs(siteAssetQuery.assetsOf(pageId))

        vo.iconRenders = DisplayMode.entries.map { mode ->
            val resolved = iconResolver.resolveOne(pageId, mode)
            IconRenderVO(
                displayMode = mode,
                previewUrl = resolved.url,
                monogram = resolved.monogram,
            )
        }

        runCatching {
            vo.categories = bookmarkCategoryService.categoriesOf(listOf(pageId))[pageId]
                .orEmpty().map { CategoryVO(it.id, it.slug, it.name, it.color) }
        }.onFailure { log.warn("[adminDetail] 分类回填失败(忽略): ${it.message}") }

        // 与列表口径一致：缺行就是 null，不补空实例
        runCatching {
            vo.pageMeta = siteAssetWriter.pageMetaOfBatch(listOf(pageId))[pageId]?.let(::PageMetaAdminVO)
        }.onFailure { log.warn("[adminDetail] 页面元数据回填失败(忽略): ${it.message}") }

        return vo
    }

    private companion object {
        // 后台列表里图片格子是 32px，2x 屏取 128 已经绰绰有余，回原图只是白烧带宽
        const val ADMIN_LIST_ASSET_SIZE = 128
    }
}
