package top.tcyeee.bookmarkify.server.repair

import com.baomidou.mybatisplus.extension.kotlin.KtQueryWrapper
import com.baomidou.mybatisplus.extension.kotlin.KtUpdateWrapper
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import top.tcyeee.bookmarkify.entity.dto.BookmarkUrlWrapper
import top.tcyeee.bookmarkify.entity.entity.PageEntity
import top.tcyeee.bookmarkify.entity.entity.BookmarkEntity
import top.tcyeee.bookmarkify.mapper.PageMapper
import top.tcyeee.bookmarkify.mapper.BookmarkMapper
import top.tcyeee.bookmarkify.server.IBookmarkService
import top.tcyeee.bookmarkify.utils.WebsiteParser

/**
 * 一次性修复：把此前被合并成一条的深链拆开。
 *
 * ## 修的是什么
 *
 * canonical key 曾经只有 `(url_host, url_path)`，query 被整个丢掉，于是
 * `youtube.com/watch?v=A` 与 `?v=B` 共用同一条 `bookmark`，而且那条记录的抓取目标退化成了
 * `https://www.youtube.com/watch` —— 一个不存在的页面。**库里所有带参数的深链，标题/描述/
 * og 图都不是"旧"，是错的。**
 *
 * ## 为什么只能用代码做，不能用 SQL
 *
 * 被丢掉的 query 在 `bookmark` 里已经不存在了。唯一还留着完整地址的地方是
 * `bookmark_user_link.url_full`（那一列一直存的是用户给的原始网址）。所以修复必须走
 * 「读 url_full → 重新规范化 → get-or-create → 重绑 page_id」这条代码路径。
 *
 * ## 幂等
 *
 * 规范化是纯函数、`getOrCreateCanonical` 是 get-or-create，所以重复跑只是多做一遍
 * SELECT，不会产生新记录、不会重复计数。跑一半中断也可以直接再跑一遍。
 *
 * ## 刻意不做的两件事
 *
 * 1. **不发解析事件。** 拆出来的新记录是 `PENDING`，交给 `checkAll()`（5 分钟一轮，专挑
 *    `PENDING` 且陈旧的记录）按自己的节奏喂解析池。这里一次性 publish 几千个事件会打满
 *    解析池及其有界队列，之后 `CallerRunsPolicy` 会把剩下的抓取直接跑在调用线程上 ——
 *    批量导入当初就是因为这个才改成不发事件的（见 `bookmarkify-api/CLAUDE.md`）。
 * 2. **不删空出来的旧记录。** 拆完之后那条 `(host, /watch)` 可能已经没有任何用户链接引用，
 *    但它同时挂着 `site_page_meta` / `site_asset` / `scrape_snapshot`，级联删除的边界值得单独
 *    一轮处理。这里只把数量报出来，删不删由人决定。
 */
@Service
class DeepLinkSplitRepair(
    private val bookmarkService: IBookmarkService,
    private val bookmarkUserLinkMapper: BookmarkMapper,
    private val bookmarkMapper: PageMapper,
) {

    private val logger = LoggerFactory.getLogger(javaClass)

    /**
     * @param scanned 扫过的用户链接数
     * @param repointed 实际改了 page_id 的条数（即真正被拆开的深链）
     * @param awaitingCrawl 重绑到的目标记录中**尚未抓取过**的条数 —— 也就是还要等
     *   `checkAll()` 抓多少个页面。刻意不报"新建了多少条"：`getOrCreateCanonical` 不区分
     *   自己建的和早就存在的，硬报一个数只会是猜的；而"还有多少待抓"才是运维真正要看的。
     * @param unparsable url_full 解析不出来的条数（javascript: / about: 之类，无需处理）
     * @param failed 处理时抛异常的条数，已跳过
     * @param orphanedBookmarks 修复后不再被任何用户链接引用的旧 canonical 记录数（未删除）
     */
    data class Report(
        val scanned: Int = 0,
        val repointed: Int = 0,
        val awaitingCrawl: Int = 0,
        val unparsable: Int = 0,
        val failed: Int = 0,
        val orphanedBookmarks: Int = 0,
    )

    /**
     * 执行修复。
     *
     * @param dryRun 为 true 时只统计不写库 —— 上生产前先看一眼影响面
     * @param batchSize 每批读多少条用户链接，按 id 游标翻页
     */
    fun run(dryRun: Boolean = false, batchSize: Int = 500): Report {
        logger.warn("[DeepLinkSplitRepair] 开始深链拆分修复: dryRun=$dryRun, batchSize=$batchSize")

        var scanned = 0
        var repointed = 0
        var awaitingCrawl = 0
        var unparsable = 0
        var failed = 0
        // 按 id 游标翻页而不是 OFFSET：这个任务会**改动**正在遍历的表（重绑 page_id），
        // OFFSET 分页在数据变动时会漏行/重复行，游标不会。
        var cursor: String? = null

        while (true) {
            val batch = bookmarkUserLinkMapper.selectList(
                KtQueryWrapper(BookmarkEntity::class.java)
                    .eq(BookmarkEntity::deleted, false)
                    .apply { cursor?.let { gt(BookmarkEntity::id, it) } }
                    .orderByAsc(BookmarkEntity::id)
                    .last("LIMIT $batchSize")
            )
            if (batch.isEmpty()) break
            cursor = batch.last().id

            batch.forEach { link ->
                scanned++
                // 导入时还没绑定源书签的（page_id = 'LOADING'）交给 drainStuckLoading 处理，
                // 那条链路本来就会为它们建 canonical 记录，这里插手只会两边抢
                if (link.pageId == null || link.pageId == LOADING) return@forEach

                val wrapper = runCatching { WebsiteParser.urlWrapper(link.urlFull) }.getOrNull()
                if (wrapper == null) {
                    unparsable++
                    return@forEach
                }

                runCatching {
                    // 先看一眼规范化后的 key 是否已经指向当前记录，避免为绝大多数「本来就对」的
                    // 链接白建一次 site 的 get-or-create
                    val current = bookmarkMapper.selectById(link.pageId)
                    if (current != null && current.matches(wrapper)) return@runCatching

                    if (dryRun) {
                        repointed++
                        return@runCatching
                    }

                    val target = bookmarkService.getOrCreateCanonical(link.urlFull)
                    if (target.id == link.pageId) return@runCatching
                    if (target.updateTime == null) awaitingCrawl++

                    bookmarkUserLinkMapper.update(
                        null,
                        KtUpdateWrapper(BookmarkEntity::class.java)
                            .eq(BookmarkEntity::id, link.id)
                            .set(BookmarkEntity::pageId, target.id)
                    )
                    repointed++
                    logger.debug(
                        "[DeepLinkSplitRepair] 已重绑: userLinkId={}, {} -> {}, url={}",
                        link.id, link.pageId, target.id, link.urlFull
                    )
                }.onFailure {
                    failed++
                    logger.warn(
                        "[DeepLinkSplitRepair] 处理失败(跳过): userLinkId={}, url={}, err={}",
                        link.id, link.urlFull, it.message
                    )
                }
            }
            logger.info("[DeepLinkSplitRepair] 进度: scanned=$scanned, repointed=$repointed, awaitingCrawl=$awaitingCrawl")
        }

        val orphaned = if (dryRun) 0 else countOrphanedBookmarks()
        val report = Report(scanned, repointed, awaitingCrawl, unparsable, failed, orphaned)
        logger.warn("[DeepLinkSplitRepair] 修复结束: $report")
        return report
    }

    /**
     * 库里这条记录是否就是这个网址规范化后应该落到的那条。
     *
     * 比的是四元组本身而不是拿 `getOrCreateCanonical` 的结果去对 —— 后者会为每一条链接都跑一次
     * site 的 get-or-create，而绝大多数链接本来就是对的，那是白花的两次查询。
     */
    private fun PageEntity.matches(w: BookmarkUrlWrapper): Boolean =
        urlHost == w.urlHost &&
            urlPath == (w.urlPath ?: "/") &&
            urlQuery == w.urlQuery &&
            urlFragment == w.urlFragment

    /** 修复后不再被任何用户链接引用的 canonical 记录数。只统计，不删。 */
    private fun countOrphanedBookmarks(): Int = runCatching {
        // 单列投影走 selectObjs：实体没有无参构造，只选一列时 MyBatis 会去找单参构造函数并抛异常
        val referenced = bookmarkUserLinkMapper.selectObjs<Any?>(
            KtQueryWrapper(BookmarkEntity::class.java)
                .select(BookmarkEntity::pageId)
                .eq(BookmarkEntity::deleted, false)
        ).mapNotNull { it?.toString() }.toSet()

        bookmarkMapper.selectObjs<Any?>(
            KtQueryWrapper(PageEntity::class.java).select(PageEntity::id)
        ).mapNotNull { it?.toString() }.count { it !in referenced }
    }.getOrElse {
        logger.warn("[DeepLinkSplitRepair] 孤儿记录统计失败(忽略): ${it.message}")
        0
    }

    companion object {
        /** 批量导入时的占位值，见 BookmarkEntity 的第三个构造函数 */
        private const val LOADING = "LOADING"
    }
}
