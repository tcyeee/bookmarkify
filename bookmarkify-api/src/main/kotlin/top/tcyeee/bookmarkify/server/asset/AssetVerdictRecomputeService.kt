package top.tcyeee.bookmarkify.server.asset

import com.baomidou.mybatisplus.extension.kotlin.KtUpdateWrapper
import org.springframework.stereotype.Service
import top.tcyeee.bookmarkify.config.log
import top.tcyeee.bookmarkify.entity.AssetVerdictRecomputeReport
import top.tcyeee.bookmarkify.entity.entity.SiteAssetEntity
import top.tcyeee.bookmarkify.mapper.SiteAssetMapper
import java.time.LocalDateTime

/**
 * 把 [AssetRolePolicy.assignRoles] 在**存量** `site_asset` 行上重跑一遍并回写。
 *
 * ## 为什么必须有这个东西
 *
 * `role` / `quality` / `is_primary` 是**判断**，却被物化在一张自称「只存抓取事实」的表里
 * （唯一写入方是 `SiteAssetIngestor`，也就是抓取那一刻）。后果是「规则是纯函数，改一次同时
 * 作用于全部站点」这条前提**对写侧规则不成立** —— 改了 `assignRoles` 之后，存量行还留着旧
 * 判定，要等 30 天的内容重抓周期自然轮到才生效。
 *
 * 而重跑根本不需要重抓：`assignRoles` 的全部输入（`extractor`、`content_hash`、宽高、
 * `error_msg`）都已经在行里了。这个服务就是把那件显然的事做出来。
 *
 * ## 边界：只改判定，不碰归属
 *
 * `ownerType` / `ownerId` **一个字都不动**。它们只依赖 `classify(extractor)` 这一层的基础
 * 分类（`SiteAssetIngestor` 那句「归属必须在 assignRoles 之后算」在注释里已经承认结论不变：
 * 借用只把 FAVICON 改成 LOGO，两者都归 SITE），而动归属就意味着要走
 * `SiteAssetWriter.replaceLayer` 的整层删除重插 —— 那会给每一行换一个新 id，把「重算判定」
 * 变成一次全表重写。
 *
 * ## 幂等，且按 owner 分组
 *
 * [AssetRolePolicy.assignRoles] 的第二、三、四遍都要看**同一批兄弟行**（hash 撞车、有没有
 * 可用 LOGO、同 role 内谁最大），所以必须按 `(ownerType, ownerId)` 整组喂进去 —— 逐行调用
 * 会得到完全不同的结果。规则没变时重跑一次不会产生任何更新，这也是「空跑验证」的依据。
 */
@Service
class AssetVerdictRecomputeService(
    private val siteAssetMapper: SiteAssetMapper,
) {

    /**
     * 重算全部资产的判定。
     *
     * @param dryRun 只统计会改多少行，不写库。上线一条规则改动前先空跑一次，
     *   看变更量对不对得上预期 —— 这个动作会改写全站图标的显示结果，值得先看一眼
     */
    fun recomputeAll(dryRun: Boolean = false): AssetVerdictRecomputeReport {
        val startedAt = System.currentTimeMillis()

        // 全表读进内存跑纯函数。当前量级是几百行；这是**管理员按需触发**的动作，不在任何热路径上。
        // 真长到读不动的那天该做的是按 owner 分页，而不是把判定逻辑塞回 SQL 里
        val all = siteAssetMapper.selectList(null)
        val groups = all.groupBy { it.ownerType to it.ownerId }

        var changed = 0
        val changedOwners = HashSet<String>()
        groups.forEach { (owner, rows) ->
            // 先快照旧判定：assignRoles 是原地改实体的，比完再比就没得比了
            val before = rows.associate { it.id to Verdict(it.role.name, it.quality.name, it.isPrimary) }
            AssetRolePolicy.assignRoles(rows)

            rows.forEach { row ->
                val old = before[row.id] ?: return@forEach
                if (old == Verdict(row.role.name, row.quality.name, row.isPrimary)) return@forEach
                changed++
                changedOwners += "${owner.first}:${owner.second}"
                if (!dryRun) writeVerdict(row)
            }
        }

        val report = AssetVerdictRecomputeReport(
            dryRun = dryRun,
            scanned = all.size,
            owners = groups.size,
            changed = changed,
            changedOwners = changedOwners.size,
            durationMs = System.currentTimeMillis() - startedAt,
            finishedAt = LocalDateTime.now(),
        )
        log.info(
            "[AssetVerdictRecompute] {}完成: 扫描 {} 行 / {} 个归属，改判 {} 行 / {} 个归属，耗时 {}ms",
            if (dryRun) "空跑" else "重算", report.scanned, report.owners,
            report.changed, report.changedOwners, report.durationMs,
        )
        return report
    }

    /**
     * 只回写这三列。
     *
     * 用逐列 `set` 而不是 `updateById`：后者会把实体上的每一个字段都写一遍，包括
     * `fetched_at` 这类**抓取事实** —— 重算判定不是一次抓取，把抓取时间改成今天等于伪造现场，
     * 而且会让 `divergesFromSite` 的「站点图标必须新鲜」判据凭空续命 60 天。
     */
    private fun writeVerdict(row: SiteAssetEntity) {
        siteAssetMapper.update(
            null,
            KtUpdateWrapper(SiteAssetEntity::class.java)
                .eq(SiteAssetEntity::id, row.id)
                .set(SiteAssetEntity::role, row.role)
                .set(SiteAssetEntity::quality, row.quality)
                .set(SiteAssetEntity::isPrimary, row.isPrimary)
        )
    }

    /** 参与比较的三列。用 name 而不是枚举本身，避免比较时被 MyBatis 的类型处理绕进去 */
    private data class Verdict(val role: String, val quality: String, val isPrimary: Boolean)
}
