package top.tcyeee.bookmarkify.server.asset

import top.tcyeee.bookmarkify.entity.dto.scrape.AssetExtractor
import top.tcyeee.bookmarkify.entity.entity.SiteAssetEntity
import top.tcyeee.bookmarkify.entity.enums.AssetQuality
import top.tcyeee.bookmarkify.entity.enums.AssetRole
import top.tcyeee.bookmarkify.entity.enums.DisplayMode

/**
 * 把 scrapper 报告的**事实**（[AssetExtractor]，这张图从哪个标签拿到的）映射成书签鸭的
 * **判断**（[AssetRole] 用途 + [AssetQuality] 可信度），并按展示模式选出该用哪一张。
 *
 * 这是整套重构里唯一需要业务判断的地方，也是刻意把它单独成文件的原因：
 * "apple-touch-icon 到底算不算 LOGO"这类规则以后会变，改这里即可 —— 不用改 scrapper，
 * 不用重抓，不用动数据库结构。
 */
object AssetRolePolicy {

    /**
     * 出处 → (用途, 可信度) 的映射表。
     *
     * 判定 [AssetQuality.TRUSTED] 的标准是"该来源在规范里语义明确"：
     * - `JSON_LD_ORG_LOGO` 是 schema.org 的 `Organization.logo`，明确就是品牌 LOGO；
     * - `MANIFEST_ICON` 是 PWA 规范里站点自己声明的多尺寸图标，也是明确的；
     * - 而 `APPLE_TOUCH_ICON` / `LINK_ICON` 本质是 favicon 的变体，把它们当 LOGO 用
     *   属于借用，故标 [AssetQuality.DEGRADED]。
     */
    private val TABLE: Map<AssetExtractor, Pair<AssetRole, AssetQuality>> = mapOf(
        // ── 明确的图标 ──
        AssetExtractor.LINK_ICON to (AssetRole.FAVICON to AssetQuality.TRUSTED),
        AssetExtractor.LINK_MASK_ICON to (AssetRole.FAVICON to AssetQuality.TRUSTED),
        AssetExtractor.FAVICON_ICO_FALLBACK to (AssetRole.FAVICON to AssetQuality.DEGRADED),
        // apple-touch-icon 是 iOS 主屏图标：尺寸够大，当 FAVICON 用是可信的
        AssetExtractor.APPLE_TOUCH_ICON to (AssetRole.FAVICON to AssetQuality.TRUSTED),
        AssetExtractor.MS_TILE_IMAGE to (AssetRole.FAVICON to AssetQuality.DEGRADED),

        // ── 明确的品牌 LOGO ──
        AssetExtractor.JSON_LD_ORG_LOGO to (AssetRole.LOGO to AssetQuality.TRUSTED),
        AssetExtractor.MANIFEST_ICON to (AssetRole.LOGO to AssetQuality.TRUSTED),

        // ── 社交分享图 ──
        AssetExtractor.OG_IMAGE to (AssetRole.SOCIAL to AssetQuality.TRUSTED),
        AssetExtractor.TWITTER_IMAGE to (AssetRole.SOCIAL to AssetQuality.TRUSTED),
        AssetExtractor.JSON_LD_IMAGE to (AssetRole.SOCIAL to AssetQuality.DEGRADED),
    )

    /**
     * LOGO 缺位时，可以从哪些出处降级借用一张来充数。
     *
     * 借来的一律标 [AssetQuality.DEGRADED]，让渲染层知道"这不是真 LOGO"。
     */
    private val LOGO_FALLBACK_ORDER = listOf(
        AssetExtractor.APPLE_TOUCH_ICON,
        AssetExtractor.MS_TILE_IMAGE,
        AssetExtractor.LINK_ICON,
    )

    /** 认定"图标足够大，可以撑起大图展示"的最小边长（CSS 像素）。 */
    const val TILE_MIN_SIZE = 128

    /** 未知出处一律按社交图之外的最低档处理，不至于让新枚举把整条流程打挂。 */
    fun classify(extractor: AssetExtractor): Pair<AssetRole, AssetQuality> =
        TABLE[extractor] ?: (AssetRole.FAVICON to AssetQuality.DEGRADED)

    /**
     * 给一批**同一书签**的资产定角色、定质量、定 primary，返回可直接落库的列表。
     *
     * 两条降级规则在这里生效：
     * 1. **hash 相同即降级**：某张 LOGO 与该站任一 FAVICON 字节完全一致时，说明它只是
     *    favicon 换了个 rel 名字，[AssetQuality] 压到 DEGRADED。这是前几轮反复提到的
     *    "很多站根本没有独立 LOGO"在数据层的落点。
     * 2. **LOGO 缺位时借用**：完全没有可信 LOGO 时，按 [LOGO_FALLBACK_ORDER] 借一张，
     *    并标记为降级。
     */
    fun assignRoles(assets: List<SiteAssetEntity>): List<SiteAssetEntity> {
        if (assets.isEmpty()) return assets

        // 第一遍：查表定 role/quality
        assets.forEach { asset ->
            val extractor = runCatching { AssetExtractor.valueOf(asset.extractor) }
                .getOrDefault(AssetExtractor.UNKNOWN)
            val (role, quality) = classify(extractor)
            asset.role = role
            asset.quality = quality
        }

        // 第二遍：hash 撞上 FAVICON 的 LOGO 一律降级
        val faviconHashes = assets
            .filter { it.role == AssetRole.FAVICON }
            .mapNotNull { it.contentHash }
            .toSet()
        assets.filter { it.role == AssetRole.LOGO }
            .filter { it.contentHash != null && it.contentHash in faviconHashes }
            .forEach { it.quality = AssetQuality.DEGRADED }

        // 第三遍：没有任何可渲染的 LOGO 时，从 favicon 家族借一张顶上
        val hasUsableLogo = assets.any { it.role == AssetRole.LOGO && it.renderable() }
        if (!hasUsableLogo) {
            LOGO_FALLBACK_ORDER.firstNotNullOfOrNull { wanted ->
                assets.firstOrNull { it.extractor == wanted.name && it.renderable() }
            }?.let { borrowed ->
                borrowed.role = AssetRole.LOGO
                borrowed.quality = AssetQuality.DEGRADED
            }
        }

        // 第四遍：同 role 内选 primary —— 先看可信度，再看有效尺寸
        assets.groupBy { it.role }.forEach { (_, group) ->
            group.forEach { it.isPrimary = false }
            group.filter { it.renderable() }
                .maxWithOrNull(
                    compareBy<SiteAssetEntity> { if (it.quality == AssetQuality.TRUSTED) 1 else 0 }
                        .thenBy { it.effectiveSize() }
                )
                ?.isPrimary = true
        }

        return assets
    }

    /**
     * 按展示模式挑出该渲染哪张图。
     *
     * **两种模式的优先级是反的**，这正是"不能用单一 is_primary 全局标记"的原因：
     * - [DisplayMode.TILE]（大图 72px+）要品牌 LOGO 优先，拿不到再退 FAVICON；
     * - [DisplayMode.LIST]（小图 16~24px）要 FAVICON 优先 —— 把 512px 的 LOGO 塞进
     *   16px 的行里既浪费带宽，观感也不比 favicon 好。
     *
     * @param pinnedAssetId 人工钉死的资产，存在即无条件胜出（见 site_display_pref）
     * @return 选中的资产；一张可渲染的都没有时返回 null，调用方应回退到首字母色块
     */
    fun resolve(
        assets: List<SiteAssetEntity>,
        mode: DisplayMode,
        pinnedAssetId: String? = null,
    ): SiteAssetEntity? {
        val usable = assets.filter { it.renderable() }
        if (usable.isEmpty()) return null

        pinnedAssetId?.let { pinned ->
            usable.firstOrNull { it.id == pinned }?.let { return it }
        }

        val roleOrder = when (mode) {
            DisplayMode.TILE -> listOf(AssetRole.LOGO, AssetRole.FAVICON)
            DisplayMode.LIST -> listOf(AssetRole.FAVICON, AssetRole.LOGO)
        }

        for (role in roleOrder) {
            val candidates = usable.filter { it.role == role }
            if (candidates.isEmpty()) continue
            val best = when (mode) {
                // 大图要尽量大：先可信度，再尺寸
                DisplayMode.TILE -> candidates.maxWithOrNull(
                    compareBy<SiteAssetEntity> { if (it.quality == AssetQuality.TRUSTED) 1 else 0 }
                        .thenBy { it.effectiveSize() }
                )
                // 小图要"刚好够用"：矢量图最优，其次是最接近 64px 且不小于它的那张
                DisplayMode.LIST -> candidates.minWithOrNull(
                    compareBy<SiteAssetEntity> { if (it.isVector) 0 else 1 }
                        .thenBy { listSizePenalty(it.effectiveSize()) }
                )
            }
            if (best != null) return best
        }
        return null
    }

    /**
     * 小图场景下与理想尺寸(64px)的距离：不足 64 的惩罚更重（放大会糊），
     * 超出的只按超出量线性计罚（无非是多传点字节）。
     */
    private fun listSizePenalty(size: Int): Int =
        if (size >= 64) size - 64 else (64 - size) * 4

    /**
     * 大图模式是否应当放弃图片、改用首字母色块。
     *
     * 判据：选中的图要么不可信（只是 favicon 借来充数），要么实际像素撑不到
     * [TILE_MIN_SIZE]。把 32px 的 favicon 拉伸到 72px 观感很差，宁可不用。
     */
    fun shouldFallbackToMonogram(chosen: SiteAssetEntity?): Boolean {
        if (chosen == null) return true
        if (chosen.isVector) return false
        return chosen.quality == AssetQuality.DEGRADED || chosen.effectiveSize() < TILE_MIN_SIZE
    }
}
