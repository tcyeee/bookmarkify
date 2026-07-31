package top.tcyeee.bookmarkify.server.asset

import com.baomidou.mybatisplus.extension.kotlin.KtQueryWrapper
import org.springframework.stereotype.Service
import top.tcyeee.bookmarkify.config.log
import top.tcyeee.bookmarkify.entity.entity.BookmarkEntity
import top.tcyeee.bookmarkify.entity.entity.SiteAssetEntity
import top.tcyeee.bookmarkify.entity.entity.SiteDisplayPrefEntity
import top.tcyeee.bookmarkify.entity.enums.AssetOwnerType
import top.tcyeee.bookmarkify.entity.enums.AssetQuality
import top.tcyeee.bookmarkify.entity.enums.AssetRole
import top.tcyeee.bookmarkify.entity.enums.DisplayMode
import top.tcyeee.bookmarkify.mapper.BookmarkMapper
import top.tcyeee.bookmarkify.mapper.SiteAssetMapper
import top.tcyeee.bookmarkify.mapper.SiteDisplayPrefMapper
import top.tcyeee.bookmarkify.utils.OssUtils

/**
 * 按展示模式解析出"这个书签该渲染哪张图、用什么显示参数"。
 *
 * 职责边界：[AssetRolePolicy] 是纯规则（可离线测试），本类负责取数据、签地址、
 * 套用人工偏好。前台每次列表渲染都会走这里，因此提供批量接口避免 N+1。
 *
 * **对外接口一律只收 bookmarkId，站点那一层由本类自己去查。** 图标现在挂在 site 上、
 * 社交图和截图挂在 bookmark 上（见 [AssetOwnerType]），但"这个书签该显示什么图"的调用方
 * 没有理由知道这件事 —— 分层的意义恰恰是把它收在一个地方。代价是每批多一次
 * `bookmark → site_id` 的查询，与批量大小无关。
 */
@Service
class SiteAssetResolver(
    private val siteAssetMapper: SiteAssetMapper,
    private val siteDisplayPrefMapper: SiteDisplayPrefMapper,
    private val bookmarkMapper: BookmarkMapper,
) {

    /**
     * 一次解析的结果。
     *
     * [monogram] 为 true 时前端应当放弃图片改用首字母色块 —— 不是"加载失败"，而是
     * "这个站根本没提供够格的图，硬拉伸只会更难看"。
     */
    data class ResolvedLogo(
        val url: String? = null,
        val role: AssetRole? = null,
        val quality: AssetQuality? = null,
        val isVector: Boolean = false,
        val monogram: Boolean = true,
        val iconPadding: Int = 25,
        val iconBgColor: String? = null,
    ) {
        companion object {
            /** 无任何可用资产时的空结果，前端据此走首字母色块 */
            val EMPTY = ResolvedLogo()
        }
    }

    /** 各展示模式期望的渲染边长（CSS 像素），用于向 OSS 请求合适尺寸的缩略图。 */
    private fun renderSize(mode: DisplayMode) = when (mode) {
        // 2x 屏下 72px 的格子需要 144px 的图源
        DisplayMode.TILE -> 256
        DisplayMode.LIST -> 64
    }

    /** 单个书签的解析结果。列表场景请用 [resolveBatch]，避免 N+1。 */
    fun resolveOne(bookmarkId: String, mode: DisplayMode): ResolvedLogo =
        resolveBatch(listOf(bookmarkId), mode)[bookmarkId] ?: ResolvedLogo.EMPTY

    /**
     * 批量解析。四次查询搞定（书签→站点、站点资产、页面资产、偏好），其余在内存里完成。
     */
    fun resolveBatch(bookmarkIds: List<String>, mode: DisplayMode): Map<String, ResolvedLogo> {
        val ids = bookmarkIds.filter { it.isNotBlank() }.distinct()
        if (ids.isEmpty()) return emptyMap()

        val siteIdOf = siteIdOf(ids)
        val assetsByBookmark = assetsByBookmark(ids, siteIdOf)
        val prefBySite = prefsBySite(siteIdOf.values, mode)

        // 回退到源站直连是**降级**，不是正常形态：说明这些图没进我方 OSS，可用性依赖第三方站点。
        // 逐张打日志会把列表渲染刷爆，因此按批聚合成一行——这条降级以前是完全静默的，
        // 结果整站图片长期热链而监控上毫无痕迹
        var hotlinked = 0
        val resolved = ids.associateWith { id ->
            build(assetsByBookmark[id].orEmpty(), prefBySite[siteIdOf[id]], mode) { hotlinked++ }
        }
        if (hotlinked > 0) log.warn(
            "[SiteAssetResolver] {}/{} 个书签回退到源站直连图片(未落 OSS)，" +
                "请检查 bookmarkify.scrapper.upload-assets 是否开启、以及这些书签是否需要重抓: mode={}",
            hotlinked, ids.size, mode
        )
        return resolved
    }

    /** 后台用：某书签可用的全部资产（站点图标 + 该页自己的图），供人工挑选与排查。 */
    fun assetsOf(bookmarkId: String): List<SiteAssetEntity> =
        assetsOfBatch(listOf(bookmarkId))[bookmarkId].orEmpty()

    /**
     * 后台列表用：一次查出多个书签可用的全部资产。
     *
     * 后台列表要按 role 分列展示 favicon/logo/社交图，逐行调 [assetsOf] 就是 N+1；
     * 这里批量取回后在内存分组。
     */
    fun assetsOfBatch(bookmarkIds: List<String>): Map<String, List<SiteAssetEntity>> {
        val ids = bookmarkIds.filter { it.isNotBlank() }.distinct()
        if (ids.isEmpty()) return emptyMap()
        return assetsByBookmark(ids, siteIdOf(ids))
    }

    /** 后台列表用：一次查出多个书签在全部模式下的人工偏好（实际按站点存）。 */
    fun prefsOfBatch(bookmarkIds: List<String>): Map<String, List<SiteDisplayPrefEntity>> {
        val ids = bookmarkIds.filter { it.isNotBlank() }.distinct()
        if (ids.isEmpty()) return emptyMap()
        val siteIdOf = siteIdOf(ids)
        val bySite = prefsBySite(siteIdOf.values)
        // 同站点的多个书签共享同一份偏好，这里按书签展开回去，调用方无需感知站点层
        return ids.mapNotNull { id -> siteIdOf[id]?.let { site -> id to bySite[site].orEmpty() } }.toMap()
    }

    /** 后台用：读取某书签所属站点在某模式下的人工偏好，没有则返回默认值。 */
    fun prefOf(bookmarkId: String, mode: DisplayMode): SiteDisplayPrefEntity {
        val siteId = siteIdOf(listOf(bookmarkId))[bookmarkId]
        return siteId
            ?.let { prefsBySite(listOf(it), mode)[it]?.firstOrNull() }
            ?: SiteDisplayPrefEntity(siteId = siteId.orEmpty(), displayMode = mode)
    }

    // ────── 取数 ──────

    /**
     * bookmarkId → siteId。一条 in 查询，只取需要的两列。
     *
     * 用 `selectMaps` 而不是把两列映射回 [BookmarkEntity]：实体是 Kotlin data class，
     * `id`/`urlHost`/`urlScheme` 没有默认值 ⇒ 没有无参构造，MyBatis 只能退化成
     * 「按结果列去找同签名的构造函数」，两列 String 匹配不上任何一个，运行时直接抛
     * `No constructor found ... matching [String, String]`。**投影查询一律走
     * selectMaps/selectObjs，别拿实体接残缺的列。**
     */
    private fun siteIdOf(bookmarkIds: List<String>): Map<String, String> = bookmarkMapper.selectMaps(
        KtQueryWrapper(BookmarkEntity::class.java)
            .select(BookmarkEntity::id, BookmarkEntity::siteId)
            .`in`(BookmarkEntity::id, bookmarkIds)
    ).mapNotNull { row ->
        val id = row.column("id")
        val siteId = row.column("site_id")
        if (id.isNullOrBlank() || siteId.isNullOrBlank()) null else id to siteId
    }.toMap()

    /**
     * Map 结果的 key 取自结果集列名（`id` / `site_id`）。这里仍按「忽略大小写与下划线」去匹配：
     * 认错一列的后果是整批解析静默退化成首字母色块，而不是报错，不值得赌驱动/配置的大小写行为。
     */
    private fun Map<String, Any?>.column(name: String): String? {
        val key = name.replace("_", "").lowercase()
        return entries.firstOrNull { it.key.replace("_", "").lowercase() == key }?.value?.toString()
    }

    /**
     * 每个书签**可用的全部资产** = 它所属站点的图标 + 它自己的社交图/截图。
     *
     * 站点图标只查一次、按 siteId 分组后共享给同站点的多个书签，这正是分层省下来的开销：
     * 一屏 20 个 YouTube 视频此前要读 20 份一模一样的 favicon 行。
     */
    private fun assetsByBookmark(
        bookmarkIds: List<String>,
        siteIdOf: Map<String, String>,
    ): Map<String, List<SiteAssetEntity>> {
        val siteIds = siteIdOf.values.distinct()

        val siteAssets = if (siteIds.isEmpty()) emptyMap() else siteAssetMapper.selectList(
            KtQueryWrapper(SiteAssetEntity::class.java)
                .eq(SiteAssetEntity::ownerType, AssetOwnerType.SITE)
                .`in`(SiteAssetEntity::ownerId, siteIds)
                .orderByAsc(SiteAssetEntity::role)
                .orderByDesc(SiteAssetEntity::isPrimary)
        ).groupBy { it.ownerId }

        val pageAssets = siteAssetMapper.selectList(
            KtQueryWrapper(SiteAssetEntity::class.java)
                .eq(SiteAssetEntity::ownerType, AssetOwnerType.PAGE)
                .`in`(SiteAssetEntity::ownerId, bookmarkIds)
                .orderByAsc(SiteAssetEntity::role)
                .orderByDesc(SiteAssetEntity::isPrimary)
        ).groupBy { it.ownerId }

        return bookmarkIds.associateWith { id ->
            siteAssets[siteIdOf[id]].orEmpty() + pageAssets[id].orEmpty()
        }
    }

    /** siteId → 该站点的展示偏好行（可按模式过滤）。 */
    private fun prefsBySite(
        siteIds: Collection<String>,
        mode: DisplayMode? = null,
    ): Map<String, List<SiteDisplayPrefEntity>> {
        val ids = siteIds.filter { it.isNotBlank() }.distinct()
        if (ids.isEmpty()) return emptyMap()
        return siteDisplayPrefMapper.selectList(
            KtQueryWrapper(SiteDisplayPrefEntity::class.java)
                .`in`(SiteDisplayPrefEntity::siteId, ids)
                .apply { mode?.let { eq(SiteDisplayPrefEntity::displayMode, it) } }
        ).groupBy { it.siteId }
    }

    // ────── 组装 ──────

    /** @param onHotlink 选中的图没落 OSS、只能给源站直连地址时回调，供调用方聚合告警 */
    private fun build(
        assets: List<SiteAssetEntity>,
        prefs: List<SiteDisplayPrefEntity>?,
        mode: DisplayMode,
        onHotlink: () -> Unit,
    ): ResolvedLogo {
        val pref = prefs?.firstOrNull { it.displayMode == mode }
        val chosen = AssetRolePolicy.resolve(assets, mode, pref?.pinnedAssetId)
            ?: return ResolvedLogo.EMPTY.copy(
                iconPadding = pref?.iconPadding ?: 25,
                iconBgColor = pref?.iconBgColor,
            )

        // 大图模式下拿到的若是降级小图，宁可走首字母色块也不要拉伸
        if (mode == DisplayMode.TILE && AssetRolePolicy.shouldFallbackToMonogram(chosen)) {
            return ResolvedLogo(
                url = null,
                role = chosen.role,
                quality = chosen.quality,
                monogram = true,
                iconPadding = pref?.iconPadding ?: 25,
                iconBgColor = pref?.iconBgColor,
            )
        }

        return ResolvedLogo(
            url = presentUrl(chosen, mode, onHotlink),
            role = chosen.role,
            quality = chosen.quality,
            isVector = chosen.isVector,
            monogram = false,
            iconPadding = pref?.iconPadding ?: 25,
            iconBgColor = pref?.iconBgColor,
        )
    }

    /**
     * 把资产地址转成前端可直接用的地址。
     *
     * OSS 桶是**私有读**的，库里存的是未签名地址，直接访问会 403，因此必须换成限时签名
     * 地址。同时借 OSS 的图片处理按模式缩放 —— 把 512px 的原图丢进 16px 的列表行是纯浪费，
     * 而缩放在服务端完成不需要我们自己存多份尺寸变体。
     *
     * 矢量图不缩放（本就与分辨率无关，缩放反而会被栅格化）。
     */
    private fun presentUrl(asset: SiteAssetEntity, mode: DisplayMode, onHotlink: () -> Unit): String? {
        val storage = asset.storageUrl?.takeIf { it.isNotBlank() }
        // 没落到我们自己的 OSS（抓取时只做了 PROBE，或那张图当时下载失败），只能给源站直连
        // 地址。这是降级路径：源站防盗链、改版 404、境外站点不可达都会直接砸到用户脸上
            ?: return asset.resolvedUrl.takeIf { it.isNotBlank() }?.also {
                log.debug(
                    "[presentUrl] 资产未落 OSS，回退源站直连: ownerType={}, ownerId={}, role={}, url={}",
                    asset.ownerType, asset.ownerId, asset.role, it
                )
                onHotlink()
            }

        // storage 可能是 object key（新契约）或存量的完整 URL，signAsset 统一处理这两种形态
        return OssUtils.signAsset(storage, if (asset.isVector) null else renderSize(mode))
    }
}
