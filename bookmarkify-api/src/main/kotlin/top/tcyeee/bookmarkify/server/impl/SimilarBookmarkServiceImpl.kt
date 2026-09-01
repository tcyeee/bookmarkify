package top.tcyeee.bookmarkify.server.impl

import com.baomidou.mybatisplus.extension.kotlin.KtQueryWrapper
import com.baomidou.mybatisplus.extension.kotlin.KtUpdateWrapper
import org.slf4j.LoggerFactory
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Service
import top.tcyeee.bookmarkify.config.async.ParseLock
import top.tcyeee.bookmarkify.config.cache.RedisType
import top.tcyeee.bookmarkify.config.event.SimilarColdComputeEvent
import top.tcyeee.bookmarkify.config.exception.CommonException
import top.tcyeee.bookmarkify.config.exception.ErrorType
import top.tcyeee.bookmarkify.entity.BookmarkSearchVO
import top.tcyeee.bookmarkify.entity.SimilarBookmarkItemVO
import top.tcyeee.bookmarkify.entity.SimilarBookmarksVO
import top.tcyeee.bookmarkify.entity.entity.PageEntity
import top.tcyeee.bookmarkify.entity.entity.SiteEntity
import top.tcyeee.bookmarkify.entity.entity.SiteSimilarItemEntity
import top.tcyeee.bookmarkify.entity.entity.SiteSimilarItemEntity.Companion.STATUS_INGESTED
import top.tcyeee.bookmarkify.entity.entity.SiteSimilarItemEntity.Companion.STATUS_PENDING
import top.tcyeee.bookmarkify.entity.entity.SiteSimilarItemEntity.Companion.STATUS_SKIPPED
import top.tcyeee.bookmarkify.entity.enums.DisplayMode
import top.tcyeee.bookmarkify.entity.enums.ParseStatusEnum
import top.tcyeee.bookmarkify.mapper.PageCategoryMapper
import top.tcyeee.bookmarkify.mapper.PageMapper
import top.tcyeee.bookmarkify.mapper.SiteSimilarItemMapper
import top.tcyeee.bookmarkify.server.IApiService
import top.tcyeee.bookmarkify.server.IBookmarkCategoryService
import top.tcyeee.bookmarkify.server.IBookmarkService
import top.tcyeee.bookmarkify.server.IBookmarkUserLinkService
import top.tcyeee.bookmarkify.server.ISimilarBookmarkService
import top.tcyeee.bookmarkify.server.ISiteService
import top.tcyeee.bookmarkify.server.asset.IconResolver
import top.tcyeee.bookmarkify.utils.RedisUtils
import top.tcyeee.bookmarkify.utils.WebsiteParser
import java.time.Duration
import java.time.LocalDateTime

/**
 * 「更多相似书签」。见 [ISimilarBookmarkService]。
 *
 * 与加书签主链路的关系是**调用方**：收录一个相似域名 = `getOrCreateCanonical` + `parseAndSave`
 * + `BookmarkEnrichEvent`，与 `addOne` 少的只有那条 `bookmark_user_link`（相似站没有归属用户）。
 */
@Service
class SimilarBookmarkServiceImpl(
    private val pageMapper: PageMapper,
    private val pageCategoryMapper: PageCategoryMapper,
    private val similarItemMapper: SiteSimilarItemMapper,
    private val siteService: ISiteService,
    private val iconResolver: IconResolver,
    private val apiService: IApiService,
    private val bookmarkService: IBookmarkService,
    private val bookmarkCategoryService: IBookmarkCategoryService,
    private val bookmarkUserLinkService: IBookmarkUserLinkService,
    private val parseLock: ParseLock,
    private val eventPublisher: ApplicationEventPublisher,
) : ISimilarBookmarkService {

    private val log = LoggerFactory.getLogger(javaClass)

    private companion object {
        /** AI 推荐结果的缓存有效期。相似站基本不变，取得比内容重抓周期(30 天)还长。 */
        const val CACHE_TTL_DAYS = 60L

        /** 本地相似最终展示条数 / 取候选条数（候选取得多是为了按 site 去重、排除已收藏后还够数）。 */
        const val LOCAL_LIMIT = 12
        const val LOCAL_CANDIDATE_LIMIT = 40

        /** DeepSeek 推荐最多收录多少个（它偶发超量）。 */
        const val MAX_AI_ITEMS = 10

        /** 单用户 24h 内可触发多少次冷计算。频率不必高（见 RedisType.SIMILAR_COLD_BUDGET）。 */
        const val DAILY_COLD_BUDGET = 30

        /** 冷计算抑制锁 TTL：覆盖「1 次 DeepSeek + ~8 个域名逐个抓取」的最坏耗时，只靠过期释放。 */
        val COLD_LOCK_TTL: Duration = Duration.ofMinutes(30)

        /**
         * PENDING 行的自愈阈值。正常一轮冷计算几分钟内会把每行推到终态；超过这个时长还 PENDING，
         * 基本可判定是进程重启把那轮打断了 —— 当作缓存失效重新触发（此时 [COLD_LOCK_TTL] 也早过期了）。
         */
        val PENDING_STUCK_AFTER: Duration = Duration.ofHours(1)

        const val SOURCE_LOCAL = "LOCAL"
        const val SOURCE_AI = "AI"
    }

    override fun similarFor(pageId: String, uid: String): SimilarBookmarksVO {
        val page = pageMapper.selectById(pageId) ?: throw CommonException(ErrorType.E102)
        val ownedPageIds = bookmarkUserLinkService.bookmarkIdsByUid(uid)

        val local = buildLocal(pageId, page, ownedPageIds)
        // 已在本地相似里出现过的 host 不再由 AI 那路重复给一遍；站点自己也排除
        val usedHosts = local.mapNotNullTo(mutableSetOf()) { it.bookmark?.urlHost }
        usedHosts += page.urlHost

        val cached = similarItemMapper.selectList(
            KtQueryWrapper(SiteSimilarItemEntity::class.java)
                .eq(SiteSimilarItemEntity::siteId, page.siteId)
                .orderByAsc(SiteSimilarItemEntity::rank)
        )
        val now = LocalDateTime.now()
        // 一轮冷计算被进程重启打断时会留下永远 PENDING 的行 —— 超过自愈阈值就当缓存失效，重新触发
        val stuckPending = cached.any {
            it.status == STATUS_PENDING && it.createTime.isBefore(now.minus(PENDING_STUCK_AFTER))
        }
        val fresh = cached.isNotEmpty() && !stuckPending &&
            cached.maxOf { it.createTime }.isAfter(now.minusDays(CACHE_TTL_DAYS))

        val (aiItems, computing) = if (fresh) {
            renderCached(cached, ownedPageIds, usedHosts) to cached.any { it.status == STATUS_PENDING }
        } else {
            emptyList<SimilarBookmarkItemVO>() to maybeTriggerCold(uid, page)
        }

        return SimilarBookmarksVO(computing = computing, items = local + aiItems)
    }

    // ────────────────────────── 本地相似 ──────────────────────────

    private fun buildLocal(pageId: String, page: PageEntity, ownedPageIds: Set<String>): List<SimilarBookmarkItemVO> {
        val categoryIds = bookmarkCategoryService.categoriesOf(listOf(pageId))[pageId].orEmpty().map { it.id }
        if (categoryIds.isEmpty()) return emptyList()

        val candidateIds = pageCategoryMapper.similarPageIds(pageId, categoryIds, LOCAL_CANDIDATE_LIMIT)
        if (candidateIds.isEmpty()) return emptyList()

        val pageById = pageMapper.selectBatchIds(candidateIds).associateBy { it.id }
        val picked = ArrayList<PageEntity>(LOCAL_LIMIT)
        val seenSites = hashSetOf(page.siteId)   // 同站不同深链只留一条，也不推荐站点自己
        for (id in candidateIds) {
            val p = pageById[id] ?: continue
            if (!seenSites.add(p.siteId)) continue
            picked += p
            if (picked.size >= LOCAL_LIMIT) break
        }
        if (picked.isEmpty()) return emptyList()

        val siteById = siteService.mapByIds(picked.mapTo(hashSetOf()) { it.siteId })
        val icons = iconResolver.resolveBatch(picked.map { it.id }, DisplayMode.LIST)
        return picked.mapNotNull { p ->
            val site = siteById[p.siteId] ?: return@mapNotNull null
            SimilarBookmarkItemVO(
                source = SOURCE_LOCAL,
                domain = p.urlHost,
                name = site.displayName,
                bookmark = BookmarkSearchVO(p, site, icons[p.id]),
                alreadyBookmarked = p.id in ownedPageIds,
            )
        }
    }

    // ────────────────────────── AI 推荐（缓存读 + 冷计算触发） ──────────────────────────

    private fun renderCached(
        cached: List<SiteSimilarItemEntity>,
        ownedPageIds: Set<String>,
        usedHosts: MutableSet<String>,
    ): List<SimilarBookmarkItemVO> {
        val ingestedDomains = cached.filter { it.status == STATUS_INGESTED }.map { it.domain }
        val rootByHost = rootPagesByHosts(ingestedDomains)
        val icons = iconResolver.resolveBatch(rootByHost.values.map { it.second.id }, DisplayMode.LIST)

        val out = ArrayList<SimilarBookmarkItemVO>()
        for (row in cached) when (row.status) {
            STATUS_INGESTED -> {
                val (site, p) = rootByHost[row.domain] ?: continue
                if (!usedHosts.add(p.urlHost)) continue
                out += SimilarBookmarkItemVO(
                    source = SOURCE_AI, domain = row.domain, name = row.name, reason = row.reason,
                    bookmark = BookmarkSearchVO(p, site, icons[p.id]),
                    alreadyBookmarked = p.id in ownedPageIds,
                )
            }
            STATUS_PENDING -> {
                if (!usedHosts.add(row.domain)) continue
                out += SimilarBookmarkItemVO(source = SOURCE_AI, domain = row.domain, name = row.name, reason = row.reason)
            }
            else -> Unit   // SKIPPED：幻觉/失效域名，不展示
        }
        return out
    }

    /** 触发冷计算，返回「是否有 AI 结果正在/即将产出」（computing）。受单用户每日预算限制。 */
    private fun maybeTriggerCold(uid: String, page: PageEntity): Boolean {
        if (parseLock.isHeld(ParseLock.similar(page.siteId))) return true   // 已在算，别重复计费

        val used = coldBudgetUsed(uid)
        if (used >= DAILY_COLD_BUDGET) {
            log.info("[similar] 用户冷计算预算已用尽，本次只返回本地相似: uid={}, used={}", uid, used)
            return false
        }
        RedisUtils.set(RedisType.SIMILAR_COLD_BUDGET, uid, used + 1)
        eventPublisher.publishEvent(SimilarColdComputeEvent(page.siteId, page.title, page.description, page.urlHost))
        log.info("[similar] 触发冷计算: uid={}, siteId={}, host={}", uid, page.siteId, page.urlHost)
        return true
    }

    private fun coldBudgetUsed(uid: String): Int =
        (RedisUtils.get<Any>(RedisType.SIMILAR_COLD_BUDGET, uid) as? Number)?.toInt() ?: 0

    override fun runColdCompute(siteId: String, title: String?, description: String?, host: String) {
        // 不主动释放，靠 TTL 退避：两个用户同时点开同域书签会各触发一次，锁挡住第二次
        if (!parseLock.tryAcquire(ParseLock.similar(siteId), COLD_LOCK_TTL)) {
            log.debug("[similar] 冷计算锁未取到，跳过: siteId={}", siteId)
            return
        }

        val recommended = apiService.inferSimilarSites(title, description, host)
        // 整组重算：先清旧行（含上一轮的 SKIPPED 负缓存）
        similarItemMapper.delete(
            KtQueryWrapper(SiteSimilarItemEntity::class.java).eq(SiteSimilarItemEntity::siteId, siteId)
        )
        if (recommended.isEmpty()) {
            log.info("[similar] DeepSeek 未返回相似站点: host={}", host)
            return
        }

        val rows = recommended.mapIndexedNotNull { i, s ->
            val domain = normalizeHost(s.domain) ?: return@mapIndexedNotNull null
            if (domain.equals(host, ignoreCase = true)) return@mapIndexedNotNull null
            SiteSimilarItemEntity(
                siteId = siteId, rank = i,
                name = s.name.ifBlank { domain }, domain = domain,
                reason = s.reason.ifBlank { null }, status = STATUS_PENDING,
            )
        }.distinctBy { it.domain }.take(MAX_AI_ITEMS)
        if (rows.isEmpty()) return

        similarItemMapper.insert(rows)

        var ingested = 0
        for (row in rows) {
            val outcome = runCatching { ingestOne(row.domain) }.getOrElse {
                log.warn("[similar] 收录异常 domain={}: {}", row.domain, it.message)
                STATUS_SKIPPED
            }
            val status = if (outcome == STATUS_SKIPPED) STATUS_SKIPPED else STATUS_INGESTED
            if (status == STATUS_INGESTED) ingested++
            similarItemMapper.update(
                null,
                KtUpdateWrapper(SiteSimilarItemEntity::class.java)
                    .eq(SiteSimilarItemEntity::id, row.id)
                    .set(SiteSimilarItemEntity::status, status)
                    .set(SiteSimilarItemEntity::updateTime, LocalDateTime.now()),
            )
        }
        log.info("[similar] 冷计算完成: host={}, 收录 {}/{}", host, ingested, rows.size)
    }

    override fun ingestOne(domain: String): String {
        val url = "https://${domain.trim().substringAfter("://")}"
        val wrapper = WebsiteParser.urlWrapper(url)
        bookmarkService.findRootPageByHost(wrapper.urlHost)?.let { return "EXISTS" }

        val page = bookmarkService.getOrCreateCanonical(url)
        // 与 addOne 完全同一条链路：parseAndSave → parseBookmark，抓到正文后由它自己发
        // BookmarkEnrichEvent(分类+NSFW) 与 BookmarkScreenshotEvent(截图)，图标也在这一步落 OSS。
        // 抓取可能抛异常（本地解析器）或落 UNREACHABLE（scrapper 不可达）；统一以「最终落库状态」判定，
        // 抓到正文(SUCCESS，反爬页面也算)才保留，其余一律删除——保证幻觉域名绝不留在库里。
        runCatching { bookmarkService.parseAndSave(page.id) }
            .onFailure { log.warn("[ingestOne] 解析异常 domain={}: {}", domain, it.message) }
        val saved = pageMapper.selectById(page.id)
        return if (saved != null && saved.parseStatus == ParseStatusEnum.SUCCESS) {
            STATUS_INGESTED
        } else {
            pageMapper.deleteById(page.id)
            STATUS_SKIPPED
        }
    }

    // ────────────────────────── helpers ──────────────────────────

    private fun normalizeHost(domain: String): String? =
        runCatching { WebsiteParser.urlWrapper("https://${domain.trim().substringAfter("://")}").urlHost }
            .getOrNull()?.takeIf { it.isNotBlank() }

    /** host → (站点, 首页 page)。与 `BookmarkServiceImpl.search` 同一套：只认 canonical 首页。 */
    private fun rootPagesByHosts(hosts: Collection<String>): Map<String, Pair<SiteEntity, PageEntity>> {
        val distinct = hosts.filter { it.isNotBlank() }.distinct()
        if (distinct.isEmpty()) return emptyMap()

        val siteByHost = siteService.ktQuery().`in`(SiteEntity::host, distinct).list().associateBy { it.host }
        if (siteByHost.isEmpty()) return emptyMap()

        val siteIdToHost = siteByHost.values.associate { it.id to it.host }
        val roots = pageMapper.selectList(
            KtQueryWrapper(PageEntity::class.java)
                .`in`(PageEntity::siteId, siteIdToHost.keys)
                .eq(PageEntity::urlPath, "/")
                .eq(PageEntity::urlQuery, "")
                .eq(PageEntity::urlFragment, "")
                .eq(PageEntity::isActivity, true)
        )
        return roots.mapNotNull { p ->
            val host = siteIdToHost[p.siteId] ?: return@mapNotNull null
            host to (siteByHost.getValue(host) to p)
        }.toMap()
    }
}
