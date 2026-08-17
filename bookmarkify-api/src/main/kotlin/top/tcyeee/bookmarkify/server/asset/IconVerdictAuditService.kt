package top.tcyeee.bookmarkify.server.asset

import com.baomidou.mybatisplus.extension.kotlin.KtQueryWrapper
import org.springframework.stereotype.Service
import top.tcyeee.bookmarkify.entity.IconCandidateBucketVO
import top.tcyeee.bookmarkify.entity.IconVerdictBucketVO
import top.tcyeee.bookmarkify.entity.IconVerdictOverviewVO
import top.tcyeee.bookmarkify.entity.IconVerdictQueryParams
import top.tcyeee.bookmarkify.entity.IconVerdictSiteVO
import top.tcyeee.bookmarkify.entity.entity.OssObjectEntity
import top.tcyeee.bookmarkify.entity.entity.SiteAssetEntity
import top.tcyeee.bookmarkify.entity.entity.SiteEntity
import top.tcyeee.bookmarkify.entity.enums.AssetOwnerType
import top.tcyeee.bookmarkify.entity.enums.AssetRole
import top.tcyeee.bookmarkify.entity.enums.DisplayMode
import top.tcyeee.bookmarkify.entity.enums.IconVerdict
import top.tcyeee.bookmarkify.mapper.SiteAssetMapper
import top.tcyeee.bookmarkify.mapper.SiteMapper

/**
 * 后台「图标判定总览」：把 [AssetRolePolicy] 在**存量数据**上的判定结果按档聚合。
 *
 * ## 为什么需要它
 *
 * 图标规则的每一次改动都要能回答「27 变成多少了」。在这之前那三个数只能连生产库手敲一段
 * SQL 拿到（`docs/ICON-DISPLAY-TODO.md` §1 存着那段查询），于是实际上没人量 —— 规则改得
 * 对不对全凭印象。这个服务把那段 SQL 变成一个可以随时点开的页面。
 *
 * ## 判定必须走真代码，不许复刻 SQL
 *
 * §1 里那段 SQL 是 [AssetRolePolicy.resolve] 在 TILE 模式下行为的**手抄复刻**。手抄件的问题
 * 不是写错，是**会漂**：规则一改它就悄悄和线上不一致，然后用一个错的数字证明改动有效 ——
 * 这比没有这张表更糟。所以这里一律加载实体、调用真正的 [AssetRolePolicy]，SQL 只负责取数。
 *
 * ## 代价
 *
 * 一次全量：把所有站点级 FAVICON/LOGO 行读进内存跑纯函数。当前量级是 153 个站点 / 数百行，
 * 完全不成问题；这是一个**管理员按需点开**的页面，不在任何热路径上。真长到读不动的那天，
 * 该做的是按站点分批而不是回去写 SQL —— 口径一致比这点开销值钱得多。
 */
@Service
class IconVerdictAuditService(
    private val siteAssetMapper: SiteAssetMapper,
    private val siteMapper: SiteMapper,
    private val siteAssetQuery: SiteAssetQuery,
) {

    /** 汇总：三档 + 候选数直方图 + 改进空间。 */
    fun overview(): IconVerdictOverviewVO {
        val judged = judgeAll()
        val byVerdict = judged.groupBy { it.verdict }

        return IconVerdictOverviewVO(
            // 按枚举声明顺序铺满全部四档，含 0 的档也要出现：
            // 「这一档是 0」和「这一档没统计」在页面上必须能分辨
            buckets = IconVerdict.entries.map { verdict ->
                val rows = byVerdict[verdict].orEmpty()
                IconVerdictBucketVO(
                    verdict = verdict,
                    count = rows.size,
                    salvageable = rows.count { it.salvageable },
                )
            },
            candidateHistogram = histogramOf(judged),
            siteTotal = judged.size,
            siteWithoutAssets = (siteMapper.selectCount(null).toInt() - judged.size).coerceAtLeast(0),
            salvageable = judged.count { it.salvageable },
            tileMinSize = AssetRolePolicy.TILE_MIN_SIZE,
        )
    }

    /** 下钻：一个站点一行。 */
    fun sites(params: IconVerdictQueryParams): List<IconVerdictSiteVO> {
        val p = params.sanitized()
        val matched = judgeAll()
            .filter { p.verdict == null || it.verdict == p.verdict }
            .filter { !p.onlySalvageable || it.salvageable }
            // 排序即优先级：有救的排最前(它们是规则的待办清单)，其次按候选图多的排前
            // (候选越多越可能是"选错了"而不是"没得选")，最后按域名稳定收敛
            .sortedWith(
                compareByDescending<Judged> { it.salvageable }
                    .thenByDescending { it.candidateCount }
                    .thenBy { it.site.host }
            )
            .take(p.limit)

        // 签名地址要批量换账本行，逐行查就是 N+1
        val objectByFileId = siteAssetQuery.objectsOf(matched.mapNotNull { it.chosen })

        return matched.map { it.toVO(objectByFileId) }
    }

    // ────── 判定 ──────

    /**
     * 一次判完全部站点。
     *
     * 只看 [AssetOwnerType.SITE] 层：图标归站点，页面层那些是社交图和截图，与图标判定无关。
     * 页面自有图标（[AssetRolePolicy.divergesFromSite] 判定出来的那批）确实存在于 PAGE 层，
     * 但它按定义只影响那一个页面，不是"这个站点的图标长什么样"这个问题的答案。
     */
    private fun judgeAll(): List<Judged> {
        val assetsBySite = siteAssetMapper.selectList(
            KtQueryWrapper(SiteAssetEntity::class.java)
                .eq(SiteAssetEntity::ownerType, AssetOwnerType.SITE)
                .`in`(SiteAssetEntity::role, listOf(AssetRole.FAVICON, AssetRole.LOGO))
        ).groupBy { it.ownerId }
        if (assetsBySite.isEmpty()) return emptyList()

        val siteById = siteMapper.selectByIds(assetsBySite.keys).associateBy { it.id }

        return assetsBySite.mapNotNull { (siteId, assets) ->
            // 站点行已被清理但资产行还在（`OrphanCleanupService` 漏表就是这个形态）：
            // 跳过而不是造一个假 host —— 这张表是用来排查规则的，混进孤儿行只会误导
            val site = siteById[siteId] ?: return@mapNotNull null
            judge(site, assets)
        }
    }

    private fun judge(site: SiteEntity, assets: List<SiteAssetEntity>): Judged {
        val usable = assets.filter { it.renderable() }
        val (verdict, chosen) = AssetRolePolicy.tileVerdict(assets)

        return Judged(
            site = site,
            chosen = chosen,
            verdict = verdict,
            candidateCount = candidateCountOf(usable),
            best = usable.maxByOrNull { it.effectiveSize() },
            // 判成色块、而库里躺着一张「换了它就能正常显示」的图 —— 这才是规则的改进空间
            salvageable = verdict != IconVerdict.IMAGE && usable.any { AssetRolePolicy.qualifiesForTile(it) },
        )
    }

    /**
     * 有几张**真正不同**的候选图。
     *
     * 按 `content_hash` 去重：同一份字节被 `<link rel=icon>` 和 `apple-touch-icon` 各声明一遍
     * 是常态，按行数算会把"这个站只有一张图"报成三张，而"选无可选"恰恰是这张表要回答的问题之一。
     * 哈希缺失的行（download=NONE、或那张取回失败）各算一张：无从比较时不该假定它们相同。
     */
    private fun candidateCountOf(usable: List<SiteAssetEntity>): Int =
        usable.map { it.contentHash ?: "id:${it.id}" }.distinct().size

    /** 候选数 1..5 各一档，6 张及以上并入最后一档。0 不出现——没有候选的站点走 NO_ASSET */
    private fun histogramOf(judged: List<Judged>): List<IconCandidateBucketVO> {
        val counted = judged.filter { it.candidateCount > 0 }
            .groupingBy { minOf(it.candidateCount, HISTOGRAM_TAIL) }
            .eachCount()
        return (1..HISTOGRAM_TAIL).map { IconCandidateBucketVO(candidates = it, sites = counted[it] ?: 0) }
    }

    // ────── 组装 ──────

    private fun Judged.toVO(objectByFileId: Map<String, OssObjectEntity>) = IconVerdictSiteVO(
        siteId = site.id,
        host = site.host,
        brandName = site.brandName,
        verdict = verdict,
        // 判成色块的行也照样签：这张表最主要的用法就是人眼核对「判色块判得对不对」，
        // 而那个判断没有图是做不出来的。按 TILE 签，因为这张表判的就是 TILE 模式
        chosenUrl = chosen?.let { AssetUrlSigner.signedIcon(it, DisplayMode.TILE, objectByFileId) },
        chosenRole = chosen?.role,
        chosenQuality = chosen?.quality,
        chosenExtractor = chosen?.extractor,
        // 矢量图的 effectiveSize 是 Int.MAX_VALUE，那是个用来排序的哨兵值，不是尺寸。
        // 原样下发会在页面上显示成 21 亿像素
        chosenSize = chosen?.takeIf { !it.isVector }?.effectiveSize(),
        chosenIsVector = chosen?.isVector == true,
        candidateCount = candidateCount,
        bestSize = best?.takeIf { !it.isVector }?.effectiveSize(),
        bestIsVector = best?.isVector == true,
        salvageable = salvageable,
    )

    /** 一个站点的判定中间结果，只在本类内部流转 */
    private data class Judged(
        val site: SiteEntity,
        val chosen: SiteAssetEntity?,
        val verdict: IconVerdict,
        val candidateCount: Int,
        val best: SiteAssetEntity?,
        val salvageable: Boolean,
    )

    private companion object {
        /** 直方图最后一档：6 表示"6 张及以上" */
        const val HISTOGRAM_TAIL = 6
    }
}
