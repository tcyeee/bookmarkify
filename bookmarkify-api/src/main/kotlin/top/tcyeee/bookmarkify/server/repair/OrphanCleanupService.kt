package top.tcyeee.bookmarkify.server.repair

import com.baomidou.mybatisplus.core.mapper.BaseMapper
import com.baomidou.mybatisplus.extension.kotlin.KtQueryWrapper
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import top.tcyeee.bookmarkify.config.log
import top.tcyeee.bookmarkify.entity.OrphanCleanupReport
import top.tcyeee.bookmarkify.entity.entity.BookmarkEntity
import top.tcyeee.bookmarkify.entity.entity.PageCategory
import top.tcyeee.bookmarkify.entity.entity.PageEntity
import top.tcyeee.bookmarkify.entity.entity.PageMetaEntity
import top.tcyeee.bookmarkify.entity.entity.PagePingLogEntity
import top.tcyeee.bookmarkify.entity.entity.ScrapeSnapshotEntity
import top.tcyeee.bookmarkify.entity.entity.SiteAssetEntity
import top.tcyeee.bookmarkify.entity.entity.SiteDisplayPrefEntity
import top.tcyeee.bookmarkify.entity.entity.SiteEntity
import top.tcyeee.bookmarkify.entity.enums.AssetOwnerType
import top.tcyeee.bookmarkify.entity.enums.BookmarkLinkType
import top.tcyeee.bookmarkify.entity.enums.ParseStatusEnum
import top.tcyeee.bookmarkify.mapper.BookmarkMapper
import top.tcyeee.bookmarkify.mapper.PageCategoryMapper
import top.tcyeee.bookmarkify.mapper.PageMapper
import top.tcyeee.bookmarkify.mapper.PageMetaMapper
import top.tcyeee.bookmarkify.mapper.PagePingLogMapper
import top.tcyeee.bookmarkify.mapper.ScrapeSnapshotMapper
import top.tcyeee.bookmarkify.mapper.SiteAssetMapper
import top.tcyeee.bookmarkify.mapper.SiteDisplayPrefMapper
import top.tcyeee.bookmarkify.mapper.SiteMapper
import top.tcyeee.bookmarkify.utils.WebsiteParser
import java.time.Duration
import java.time.LocalDateTime

/**
 * 清理**无人引用**的页面与站点。
 *
 * ## 为什么这些记录会堆积
 *
 * `page` / `site` 不随用户删书签而消失——它们是共享的抓取产物，一个用户删掉 `youtube.com/watch?v=A`
 * 不代表另一个用户没收藏它。于是「最后一个引用它的用户删掉了书签」这件事在库里没有任何出口，
 * 记录只增不减。深链拆分修复（[DeepLinkSplitRepair]）当年只把这个数报出来、明确把删除留给
 * 「单独一轮处理」，就是这一轮。
 *
 * ## 删哪些（两条规则，都以「无人引用」为前提）
 *
 * 1. **本地地址 / IP 站点**。它们从来就不会被抓取（三道门都拦着，见根目录 `CLAUDE.md`），
 *    标题、图标、`page_meta`、巡检游标**永远**是空的，留着只是噪音。
 * 2. **已判定失活的页面**（`UNREACHABLE` / `ARCHIVED`）。失活判定本身是有门槛的——要连续失败
 *    到管理员配的次数才落定，归档更是按退避曲线累计两个多月，所以走到这一步的记录已经被反复
 *    确认过打不开了。
 *
 * 两条规则都**不碰还有人收藏的记录**：判据是 `bookmark` 表里有没有行指向它，与那行是不是
 * 软删无关（软删行按引用算，宁可少删）。
 *
 * ## 站点这一层
 *
 * 站点没有直接的引用方，它只能经由页面被读到，所以「无人引用」= **名下一个页面都不剩**。
 * 在此之上仍要求它满足与页面同样的两条规则之一（本地/IP，或域名已判定不可达），
 * 而不是「凡是空站点一律删」——后者会顺手删掉刚建站点、页面还没落库的那一瞬间的记录。
 *
 * ## 与并发抓取的时序
 *
 * 添加书签是「先建页面、再建用户关联」，中间这段窗口里页面看起来正是无人引用的。
 * [RECENT_GRACE] 把太新的页面一律排除在外，代价只是它们等下一次清理。
 *
 * ## 对象存储
 *
 * 这里**只删数据库行，不删 OSS 对象**。失去引用的对象由既有的对账链路（`OssReconcileServiceImpl`）
 * 在下一轮认定为孤儿后按 `bookmarkify.oss.reclaim-orphans` 的策略回收——那条链路有宽限期、
 * 有"分类不完整就不删"的闸门，绕开它自己删对象等于把那几道保护也一并绕开了。
 */
@Service
class OrphanCleanupService(
    private val pageMapper: PageMapper,
    private val siteMapper: SiteMapper,
    private val bookmarkMapper: BookmarkMapper,
    private val pageMetaMapper: PageMetaMapper,
    private val scrapeSnapshotMapper: ScrapeSnapshotMapper,
    private val pagePingLogMapper: PagePingLogMapper,
    private val pageCategoryMapper: PageCategoryMapper,
    private val siteAssetMapper: SiteAssetMapper,
    private val siteDisplayPrefMapper: SiteDisplayPrefMapper,
) {

    companion object {
        /** IN 列表的分片大小，取值理由同 `OssReconcileServiceImpl.CHUNK` */
        private const val CHUNK = 500

        /**
         * 页面的"保护期"：创建时间在这个窗口内的一律不删。
         *
         * 挡的是添加书签路径上「页面已建、用户关联还没建」那一瞬间——那时页面确实无人引用，
         * 而它马上就会有人引用。取 10 分钟是因为这条链路最长的一段是抓取（无头浏览器的
         * 排队 20s + 页面 30s），10 分钟已经比它宽出一个数量级。
         */
        private val RECENT_GRACE: Duration = Duration.ofMinutes(10)

        /** 「已判定失活」的两个状态：反复确认过打不开，与 PENDING（还没抓过）是两回事 */
        private val DEAD_STATUSES = setOf(ParseStatusEnum.UNREACHABLE, ParseStatusEnum.ARCHIVED)

        /** 从来不抓取、因而永远没有内容的两类站点 */
        private val NON_CRAWLABLE = setOf(BookmarkLinkType.LOCAL, BookmarkLinkType.IP)
    }

    /**
     * 执行（或预演）一轮清理。
     *
     * @param dryRun 为 true 时一行都不删，只统计。后台确认框里的每个数字都来自这里，
     *   用的是与真正删除完全相同的一段判定代码——分成两套实现，确认框就不再是承诺。
     */
    @Transactional
    fun run(dryRun: Boolean): OrphanCleanupReport {
        val startedAt = System.currentTimeMillis()
        val report = OrphanCleanupReport(dryRun = dryRun)

        // ── 1. 引用方事实：任何一行 bookmark 指向的页面都算"有人引用"
        //     不过滤 deleted：那一列现在没有写入方（删书签走的是物理删除），
        //     真出现历史软删行时按"有引用"处理是安全的那一侧——少删可以下轮再删，删错找不回来
        val referencedPageIds = bookmarkMapper.selectObjs<Any?>(
            KtQueryWrapper(BookmarkEntity::class.java).select(BookmarkEntity::pageId)
        ).mapNotNull { it?.toString() }.toSet()

        // ── 2. 页面与站点全量。两张表都是几百行的量级，全部拉进内存做集合运算，
        //     比为这一个操作去写一串带子查询的 SQL 更好读，也更好改判定规则
        val sites = siteMapper.selectList(null).associateBy { it.id }
        val pages = pageMapper.selectList(null)

        // ── 3. 挑出该删的页面
        val cutoff = LocalDateTime.now().minus(RECENT_GRACE)
        val doomedPages = mutableListOf<PageEntity>()
        pages.forEach { page ->
            if (page.id in referencedPageIds) return@forEach
            val nonCrawlable = page.linkTypeOf(sites) in NON_CRAWLABLE
            val dead = page.parseStatus in DEAD_STATUSES
            if (!nonCrawlable && !dead) return@forEach
            if (page.createTime.isAfter(cutoff)) {
                report.skippedRecentPages++
                return@forEach
            }
            if (nonCrawlable) report.localIpPages++
            if (dead) report.deadPages++
            doomedPages += page
        }
        val doomedPageIds = doomedPages.map { it.id }
        val doomedPageIdSet = doomedPageIds.toSet()

        // ── 4. 挑出该删的站点：名下页面在本轮全部会被删光，且它自己也满足同样两条规则之一
        val sitesWithSurvivingPage = pages.filterNot { it.id in doomedPageIdSet }
            .mapTo(mutableSetOf()) { it.siteId }
        val doomedSiteIds = sites.values.filter { site ->
            site.id !in sitesWithSurvivingPage &&
                (site.linkType in NON_CRAWLABLE || !site.isAlive)
        }.map { it.id }

        // ── 5. 级联删除。顺序是"先附属、后主体"：中途失败时留下的是孤立的附属行，
        //     由下一轮清理收掉；反过来先删主体则会留下永远找不到归属的附属行
        report.pageMeta = purge(pageMetaMapper, doomedPageIds, dryRun) { `in`(PageMetaEntity::pageId, it) }
        report.snapshots = purge(scrapeSnapshotMapper, doomedPageIds, dryRun) { `in`(ScrapeSnapshotEntity::pageId, it) }
        report.pingLogs = purge(pagePingLogMapper, doomedPageIds, dryRun) { `in`(PagePingLogEntity::pageId, it) }
        report.pageCategories = purge(pageCategoryMapper, doomedPageIds, dryRun) { `in`(PageCategory::pageId, it) }

        // 资产分两次删，因为归属是 (ownerType, ownerId) 而不是 page_id —— site_asset.page_id
        // 只是溯源列，站点级图标那一行的 page_id 可能正指向本轮要删的页面，按它删会连站点图标一起删掉
        report.pageAssets = purgeAssets(AssetOwnerType.PAGE, doomedPageIds, dryRun, report)
        report.siteAssets = purgeAssets(AssetOwnerType.SITE, doomedSiteIds, dryRun, report)
        report.displayPrefs = purge(siteDisplayPrefMapper, doomedSiteIds, dryRun) {
            `in`(SiteDisplayPrefEntity::siteId, it)
        }

        report.pages = purge(pageMapper, doomedPageIds, dryRun) { `in`(PageEntity::id, it) }
        report.sites = purge(siteMapper, doomedSiteIds, dryRun) { `in`(SiteEntity::id, it) }

        report.durationMs = System.currentTimeMillis() - startedAt
        // 预演也记一行：它是"当时看到的范围"，出问题时唯一能和执行结果对照的东西
        log.warn("[OrphanCleanup] {}: {}", if (dryRun) "预演" else "已清理", report)
        return report
    }

    /**
     * 页面所属站点的链接类型。
     *
     * 站点行缺失（历史脏数据）时按 host 现算一次，而不是当作 OTHER 放过 ——
     * 一条 `site_id` 指向不存在站点的 `127.0.0.1` 页面正是最该被清掉的那种记录。
     */
    private fun PageEntity.linkTypeOf(sites: Map<String, SiteEntity>): BookmarkLinkType =
        sites[siteId]?.linkType ?: runCatching { WebsiteParser.classifyLinkType(urlHost) }
            .getOrDefault(BookmarkLinkType.OTHER)

    /**
     * 删除（或统计）资产行，并把它们引用的对象存储文件数记进报告。
     *
     * 先查出来再按主键删，是为了让 [OrphanCleanupReport.releasedFiles] 在预演和执行两种模式下
     * 都是同一个数：那一列要去重 `file_id`，SQL 的 `delete` 只会告诉你删了几行。
     */
    private fun purgeAssets(
        ownerType: AssetOwnerType,
        ownerIds: List<String>,
        dryRun: Boolean,
        report: OrphanCleanupReport,
    ): Int {
        if (ownerIds.isEmpty()) return 0
        val assets = ownerIds.chunked(CHUNK).flatMap { chunk ->
            siteAssetMapper.selectList(
                KtQueryWrapper(SiteAssetEntity::class.java)
                    .eq(SiteAssetEntity::ownerType, ownerType)
                    .`in`(SiteAssetEntity::ownerId, chunk)
            )
        }
        if (assets.isEmpty()) return 0
        report.releasedFiles += assets.mapNotNull { it.fileId?.takeIf(String::isNotBlank) }.distinct().size
        return purge(siteAssetMapper, assets.map { it.id }, dryRun) { `in`(SiteAssetEntity::id, it) }
    }

    /**
     * 分片执行「按 id 列表删除」，[dryRun] 时换成同一个条件的 count。
     *
     * 两种模式共用同一个 where 子句是这个方法存在的理由：预览与执行只要有一处条件写法不同，
     * 确认框里的数字就是另一个查询的结果。
     */
    private inline fun <reified T : Any> purge(
        mapper: BaseMapper<T>,
        ids: Collection<String>,
        dryRun: Boolean,
        crossinline where: KtQueryWrapper<T>.(List<String>) -> Unit,
    ): Int {
        if (ids.isEmpty()) return 0
        var affected = 0
        ids.chunked(CHUNK).forEach { chunk ->
            val wrapper = KtQueryWrapper(T::class.java).apply { where(chunk) }
            affected += if (dryRun) mapper.selectCount(wrapper).toInt() else mapper.delete(wrapper)
        }
        return affected
    }
}
