package top.tcyeee.bookmarkify.server.asset

import org.springframework.stereotype.Service
import top.tcyeee.bookmarkify.config.log
import top.tcyeee.bookmarkify.entity.BookmarkShow
import top.tcyeee.bookmarkify.entity.entity.OssObjectEntity
import top.tcyeee.bookmarkify.entity.entity.SiteAssetEntity
import top.tcyeee.bookmarkify.entity.enums.DisplayMode

/**
 * 「这个书签的**图标**该渲染成什么」—— 按展示模式解析出唯一一张图。
 *
 * 职责边界：[AssetRolePolicy] 是纯规则（可离线测试），[SiteAssetQuery] 只取数，
 * [AssetUrlSigner] 只签地址，本类负责把三者串起来。
 *
 * **与 [CoverResolver] 是两件事，不能混。** 这里选的是**图标**：方形、几十到几百像素、
 * 代表「这个站」；封面选的是「这一页长什么样」：宽幅、代表这一个页面。把截图塞进图标的
 * 候选池会让一张 1280×720 的图因为「尺寸最大」在 TILE 模式下胜出，变成书签的图标。
 * 两者从前住在同一个类里，只靠一段注释拦着；现在由类型拦着。
 *
 * **对外接口一律只收 pageId，站点那一层由 [SiteAssetQuery] 自己去查。** 图标挂在 site 上、
 * 社交图和截图挂在 page 上，但「这个书签该显示什么图」的调用方没有理由知道这件事 ——
 * 分层的意义恰恰是把它收在一个地方。
 */
@Service
class IconResolver(
    private val query: SiteAssetQuery,
) {

    /**
     * 批量解析。列表场景一律用它，[resolveOne] 只是包了一层。
     */
    fun resolveBatch(pageIds: List<String>, mode: DisplayMode): Map<String, ResolvedIcon> {
        val ids = pageIds.filter { it.isNotBlank() }.distinct()
        if (ids.isEmpty()) return emptyMap()

        val assetsByPage = query.assetsOfBatch(ids)
        // 一次取全本批资产的账本行，把 file_id 换成真正的 object key。**必须批量** ——
        // 首页一屏几十个图标，在签名处逐张查库就是教科书式的 N+1
        val objectByFileId = query.objectsOf(assetsByPage.values.flatten())

        // 回退到源站直连是**降级**，不是正常形态：说明这些图没进我方 OSS，可用性依赖第三方站点。
        // 逐张打日志会把列表渲染刷爆，因此按批聚合成一行——这条降级以前是完全静默的，
        // 结果整站图片长期热链而监控上毫无痕迹
        var hotlinked = 0
        val resolved = ids.associateWith { id ->
            build(assetsByPage[id].orEmpty(), mode, objectByFileId) { hotlinked++ }
        }
        if (hotlinked > 0) warnHotlink(hotlinked, ids.size, mode)
        return resolved
    }

    private fun warnHotlink(hotlinked: Int, total: Int, mode: DisplayMode) = log.warn(
        "[IconResolver] {}/{} 个书签回退到源站直连图片(未落 OSS)，" +
            "请检查 bookmarkify.scrapper.upload-assets 是否开启、以及这些书签是否需要重抓: mode={}",
        hotlinked, total, mode
    )

    /** 单个书签的解析结果。列表场景请用 [resolveBatch]，避免 N+1。 */
    fun resolveOne(pageId: String, mode: DisplayMode): ResolvedIcon =
        resolveBatch(listOf(pageId), mode)[pageId] ?: ResolvedIcon.EMPTY

    /**
     * 一批书签渲染所需的**全部**图标：本模式那一份 + 置顶区磁贴那一份。见 [DisplayIcons]。
     *
     * **一次取数，纯函数跑两遍。** 两个模式读的是同一批 `site_asset` 行和同一批账本行，
     * 差别只在 [AssetRolePolicy.resolve] 的排序与签名尺寸 —— 所以数据库开销与解析一个模式
     * 完全相同，多出来的只是一遍内存里的排序。
     */
    fun resolveForDisplay(pageIds: List<String>, mode: DisplayMode): Map<String, DisplayIcons> {
        val ids = pageIds.filter { it.isNotBlank() }.distinct()
        if (ids.isEmpty()) return emptyMap()

        val assetsByPage = query.assetsOfBatch(ids)
        val objectByFileId = query.objectsOf(assetsByPage.values.flatten())

        var hotlinked = 0
        val resolved = ids.associateWith { id ->
            val assets = assetsByPage[id].orEmpty()
            val icon = build(assets, mode, objectByFileId) { hotlinked++ }
            DisplayIcons(
                mode = mode,
                icon = icon,
                // TILE 模式下两份本来就是同一次解析，没有理由再算一遍
                tileIcon = if (mode == DisplayMode.TILE) icon
                else build(assets, DisplayMode.TILE, objectByFileId) { hotlinked++ },
            )
        }
        if (hotlinked > 0) warnHotlink(hotlinked, ids.size, mode)
        return resolved
    }

    /**
     * 把解析结果直接装进一批 [BookmarkShow]，调用方不必知道 pageId → 资产的映射。
     *
     * 这是给调用方用的入口。此前「收集 pageId → resolveBatch → forEach initDisplay」这套三步舞
     * 在 5 个调用点各写了一遍，而 `initDisplay` 的 KDoc 自己记着：那两个参数做成必填，正是因为
     * 历史上有调用点忘了调，前端静默退化成首字母色块、没有任何报错。**重复 + 曾经漏过**，
     * 就该收成一次调用。
     */
    fun decorate(shows: List<BookmarkShow>, mode: DisplayMode): List<BookmarkShow> {
        val iconsByPage = resolveForDisplay(shows.mapNotNull { it.pageId }, mode)
        return shows.onEach { show ->
            show.initDisplay(show.pageId?.let { iconsByPage[it] } ?: DisplayIcons.empty(mode))
        }
    }

    /** 单条书签的装配。列表场景请用 [decorate]。 */
    fun decorateOne(show: BookmarkShow, mode: DisplayMode): BookmarkShow =
        decorate(listOf(show), mode).first()

    /**
     * 按**域名**取站点图标的签名地址。
     *
     * 调用方手上只有一个域名字符串（`scrapper_call_log` 记的是一次抓取动作，抓失败时压根
     * 没有对应的 page 行）。取图顺序复用 [DisplayMode.LIST]（FAVICON → LOGO，按 64px 挑最
     * 合适的一张），因为调用方就是列表里的 16px 小图标。
     *
     * @return host → 签名地址；该域名没有站点图标、或图标未落 OSS 时**不出现在结果里**，
     *   调用方据此走自己的本地兜底图（后台绝不能去直连外站，见 [AssetUrlSigner.signedIcon]）
     */
    fun siteFaviconByHost(hosts: Collection<String>): Map<String, String> {
        val assetsByHost = query.siteIconsByHost(hosts)
        if (assetsByHost.isEmpty()) return emptyMap()
        val objectByFileId = query.objectsOf(assetsByHost.values.flatten())

        return assetsByHost.mapNotNull { (host, assets) ->
            val chosen = AssetRolePolicy.resolve(assets, DisplayMode.LIST) ?: return@mapNotNull null
            AssetUrlSigner.signedIcon(chosen, DisplayMode.LIST, objectByFileId)?.let { host to it }
        }.toMap()
    }

    /** @param onHotlink 选中的图没落 OSS、只能给源站直连地址时回调，供调用方聚合告警 */
    private fun build(
        assets: List<SiteAssetEntity>,
        mode: DisplayMode,
        objectByFileId: Map<String, OssObjectEntity>,
        onHotlink: () -> Unit,
    ): ResolvedIcon {
        val chosen = AssetRolePolicy.resolve(assets, mode) ?: return ResolvedIcon.EMPTY

        // 大图模式下拿到的若是小图，宁可走首字母色块也不要拉伸
        if (mode == DisplayMode.TILE && AssetRolePolicy.shouldFallbackToMonogram(chosen)) {
            return ResolvedIcon(
                url = null,
                role = chosen.role,
                quality = chosen.quality,
                monogram = true,
            )
        }

        return ResolvedIcon(
            url = presentUrl(chosen, mode, objectByFileId, onHotlink),
            role = chosen.role,
            quality = chosen.quality,
            isVector = chosen.isVector,
            monogram = false,
        )
    }

    /**
     * 前台用的地址：签名地址优先，**没落 OSS 时回退源站直连**。
     *
     * 这是降级路径：源站防盗链、改版 404、境外站点不可达都会直接砸到用户脸上。但对前台而言
     * 热链再差也好过用户看到空白，所以这里接了这条兜底，代价记在 `hotlinked` 计数里。
     * 后台相反 —— 它直接用 [AssetUrlSigner.signedIcon]，拿不到就用本地兜底图。
     */
    private fun presentUrl(
        asset: SiteAssetEntity,
        mode: DisplayMode,
        objectByFileId: Map<String, OssObjectEntity>,
        onHotlink: () -> Unit,
    ): String? = AssetUrlSigner.signedIcon(asset, mode, objectByFileId)
        ?: asset.resolvedUrl.takeIf { it.isNotBlank() }?.also {
            log.debug(
                "[presentUrl] 资产未落 OSS，回退源站直连: ownerType={}, ownerId={}, role={}, url={}",
                asset.ownerType, asset.ownerId, asset.role, it
            )
            onHotlink()
        }
}
