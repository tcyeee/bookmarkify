package top.tcyeee.bookmarkify.server.asset

import com.baomidou.mybatisplus.extension.kotlin.KtQueryWrapper
import org.springframework.stereotype.Service
import top.tcyeee.bookmarkify.config.log
import top.tcyeee.bookmarkify.entity.entity.SiteAssetEntity
import top.tcyeee.bookmarkify.entity.entity.SiteDisplayPrefEntity
import top.tcyeee.bookmarkify.entity.enums.AssetQuality
import top.tcyeee.bookmarkify.entity.enums.AssetRole
import top.tcyeee.bookmarkify.entity.enums.DisplayMode
import top.tcyeee.bookmarkify.mapper.SiteAssetMapper
import top.tcyeee.bookmarkify.mapper.SiteDisplayPrefMapper
import top.tcyeee.bookmarkify.utils.OssUtils

/**
 * 按展示模式解析出"这个书签该渲染哪张图、用什么显示参数"。
 *
 * 职责边界：[AssetRolePolicy] 是纯规则（可离线测试），本类负责取数据、签地址、
 * 套用人工偏好。前台每次列表渲染都会走这里，因此提供批量接口避免 N+1。
 */
@Service
class SiteAssetResolver(
    private val siteAssetMapper: SiteAssetMapper,
    private val siteDisplayPrefMapper: SiteDisplayPrefMapper,
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
     * 批量解析。两次查询搞定（资产 + 偏好），其余在内存里完成。
     */
    fun resolveBatch(bookmarkIds: List<String>, mode: DisplayMode): Map<String, ResolvedLogo> {
        val ids = bookmarkIds.filter { it.isNotBlank() }.distinct()
        if (ids.isEmpty()) return emptyMap()

        val assetsByBookmark = siteAssetMapper
            .selectList(KtQueryWrapper(SiteAssetEntity::class.java).`in`(SiteAssetEntity::bookmarkId, ids))
            .groupBy { it.bookmarkId }

        val prefByBookmark = siteDisplayPrefMapper
            .selectList(
                KtQueryWrapper(SiteDisplayPrefEntity::class.java)
                    .`in`(SiteDisplayPrefEntity::bookmarkId, ids)
                    .eq(SiteDisplayPrefEntity::displayMode, mode)
            )
            .associateBy { it.bookmarkId }

        // 回退到源站直连是**降级**，不是正常形态：说明这些图没进我方 OSS，可用性依赖第三方站点。
        // 逐张打日志会把列表渲染刷爆，因此按批聚合成一行——这条降级以前是完全静默的，
        // 结果整站图片长期热链而监控上毫无痕迹
        var hotlinked = 0
        val resolved = ids.associateWith { id ->
            build(assetsByBookmark[id].orEmpty(), prefByBookmark[id], mode) { hotlinked++ }
        }
        if (hotlinked > 0) log.warn(
            "[SiteAssetResolver] {}/{} 个书签回退到源站直连图片(未落 OSS)，" +
                "请检查 bookmarkify.scrapper.upload-assets 是否开启、以及这些书签是否需要重抓: mode={}",
            hotlinked, ids.size, mode
        )
        return resolved
    }

    /** 后台用：某书签的全部资产原样返回，供人工挑选与排查。 */
    fun assetsOf(bookmarkId: String): List<SiteAssetEntity> = siteAssetMapper.selectList(
        KtQueryWrapper(SiteAssetEntity::class.java)
            .eq(SiteAssetEntity::bookmarkId, bookmarkId)
            .orderByAsc(SiteAssetEntity::role)
            .orderByDesc(SiteAssetEntity::isPrimary)
    )

    /**
     * 后台列表用：一次查出多个书签的全部资产。
     *
     * 后台列表要按 role 分列展示 favicon/logo/社交图，逐行调 [assetsOf] 就是 N+1；
     * 这里一条 in 查询取回后在内存分组。
     */
    fun assetsOfBatch(bookmarkIds: List<String>): Map<String, List<SiteAssetEntity>> {
        val ids = bookmarkIds.filter { it.isNotBlank() }.distinct()
        if (ids.isEmpty()) return emptyMap()
        return siteAssetMapper.selectList(
            KtQueryWrapper(SiteAssetEntity::class.java)
                .`in`(SiteAssetEntity::bookmarkId, ids)
                .orderByAsc(SiteAssetEntity::role)
                .orderByDesc(SiteAssetEntity::isPrimary)
        ).groupBy { it.bookmarkId }
    }

    /** 后台列表用：一次查出多个书签在全部模式下的人工偏好。 */
    fun prefsOfBatch(bookmarkIds: List<String>): Map<String, List<SiteDisplayPrefEntity>> {
        val ids = bookmarkIds.filter { it.isNotBlank() }.distinct()
        if (ids.isEmpty()) return emptyMap()
        return siteDisplayPrefMapper.selectList(
            KtQueryWrapper(SiteDisplayPrefEntity::class.java).`in`(SiteDisplayPrefEntity::bookmarkId, ids)
        ).groupBy { it.bookmarkId }
    }

    /** 后台用：读取某书签在某模式下的人工偏好，没有则返回默认值。 */
    fun prefOf(bookmarkId: String, mode: DisplayMode): SiteDisplayPrefEntity =
        siteDisplayPrefMapper.selectList(
            KtQueryWrapper(SiteDisplayPrefEntity::class.java)
                .eq(SiteDisplayPrefEntity::bookmarkId, bookmarkId)
                .eq(SiteDisplayPrefEntity::displayMode, mode)
        ).firstOrNull() ?: SiteDisplayPrefEntity(bookmarkId = bookmarkId, displayMode = mode)

    /** @param onHotlink 选中的图没落 OSS、只能给源站直连地址时回调，供调用方聚合告警 */
    private fun build(
        assets: List<SiteAssetEntity>,
        pref: SiteDisplayPrefEntity?,
        mode: DisplayMode,
        onHotlink: () -> Unit,
    ): ResolvedLogo {
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
                    "[presentUrl] 资产未落 OSS，回退源站直连: bookmarkId={}, role={}, url={}",
                    asset.bookmarkId, asset.role, it
                )
                onHotlink()
            }

        // storage 可能是 object key（新契约）或存量的完整 URL，signAsset 统一处理这两种形态
        return OssUtils.signAsset(storage, if (asset.isVector) null else renderSize(mode))
    }
}
