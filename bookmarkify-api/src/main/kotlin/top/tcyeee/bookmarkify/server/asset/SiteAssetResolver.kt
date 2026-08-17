package top.tcyeee.bookmarkify.server.asset

import com.baomidou.mybatisplus.extension.kotlin.KtQueryWrapper
import org.springframework.stereotype.Service
import top.tcyeee.bookmarkify.config.log
import top.tcyeee.bookmarkify.entity.entity.PageEntity
import top.tcyeee.bookmarkify.entity.entity.OssObjectEntity
import top.tcyeee.bookmarkify.entity.entity.SiteAssetEntity
import top.tcyeee.bookmarkify.entity.entity.SiteEntity
import top.tcyeee.bookmarkify.entity.enums.AssetOwnerType
import top.tcyeee.bookmarkify.entity.enums.AssetQuality
import top.tcyeee.bookmarkify.entity.enums.AssetRole
import top.tcyeee.bookmarkify.entity.enums.DisplayMode
import top.tcyeee.bookmarkify.mapper.PageMapper
import top.tcyeee.bookmarkify.mapper.SiteAssetMapper
import top.tcyeee.bookmarkify.mapper.SiteMapper
import top.tcyeee.bookmarkify.server.IOssObjectService
import top.tcyeee.bookmarkify.utils.OssUtils

/**
 * 按展示模式解析出"这个书签该渲染哪张图、用什么显示参数"。
 *
 * 职责边界：[AssetRolePolicy] 是纯规则（可离线测试），本类负责取数据、签地址。
 * 前台每次列表渲染都会走这里，因此提供批量接口避免 N+1。
 *
 * **对外接口一律只收 pageId，站点那一层由本类自己去查。** 图标现在挂在 site 上、
 * 社交图和截图挂在 bookmark 上（见 [AssetOwnerType]），但"这个书签该显示什么图"的调用方
 * 没有理由知道这件事 —— 分层的意义恰恰是把它收在一个地方。代价是每批多一次
 * `bookmark → site_id` 的查询，与批量大小无关。
 */
@Service
class SiteAssetResolver(
    private val siteAssetMapper: SiteAssetMapper,
    private val bookmarkMapper: PageMapper,
    private val siteMapper: SiteMapper,
    private val ossObjectService: IOssObjectService,
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
    fun resolveOne(pageId: String, mode: DisplayMode): ResolvedLogo =
        resolveBatch(listOf(pageId), mode)[pageId] ?: ResolvedLogo.EMPTY

    /**
     * 批量解析**页面封面**（详情面板顶部那张宽图），与 [resolveBatch] 是两件事：
     * 那个选图标，这个选"页面长什么样"。选取规则见 [AssetRolePolicy.resolveCover]。
     *
     * 只查 PAGE 层：封面的两个来源（SCREENSHOT / SOCIAL）都归属页面，不需要 site 那一跳。
     *
     * @return 只含**有封面**的书签；没有的键直接缺席，让调用方 `?:` 成 null 而不是空串
     */
    fun resolveCoverBatch(pageIds: List<String>): Map<String, String> {
        val ids = pageIds.filter { it.isNotBlank() }.distinct()
        if (ids.isEmpty()) return emptyMap()

        val pageAssets = siteAssetMapper.selectList(
            KtQueryWrapper(SiteAssetEntity::class.java)
                .eq(SiteAssetEntity::ownerType, AssetOwnerType.PAGE)
                .`in`(SiteAssetEntity::ownerId, ids)
        ).groupBy { it.ownerId }

        // 与 presentUrl 同一条规矩：file_id 才是解耦后的正式来源，storage_url 只是兜底。
        // 必须批量取，否则详情列表就是 N+1
        val objectByFileId = objectsOf(pageAssets.values.flatten())

        return ids.mapNotNull { id ->
            val chosen = AssetRolePolicy.resolveCover(pageAssets[id].orEmpty()) ?: return@mapNotNull null
            val url = coverUrl(chosen, objectByFileId) ?: return@mapNotNull null
            id to url
        }.toMap()
    }

    /**
     * 封面地址。取值优先级与 [presentUrl] 完全一致（file_id → storage_url → 源站直连），
     * 独立成一段只因缩放策略不同：封面按宽度等比缩，不像图标那样裁成正方形。
     *
     * 三级兜底缺一不可。[SiteAssetEntity.renderable] 明确接纳"没落 OSS 但有源站地址"的资产，
     * 于是 [AssetRolePolicy.resolveCover] 会正常选中它们；这里若只认 `storage_url`，选中的资产
     * 就会被签成 null —— 表现为 upload-assets 关闭时"退 og:image"这条兜底永远不生效，
     * 以及两张 SOCIAL 里挑中了更大的那张未上传的，结果一张封面都没有。
     */
    private fun coverUrl(asset: SiteAssetEntity, objectByFileId: Map<String, OssObjectEntity>): String? {
        val ledgerRow = asset.fileId?.let { objectByFileId[it] }
        val ref = ledgerRow?.objectKey
            ?: asset.storageUrl?.takeIf { it.isNotBlank() }
            ?: asset.resolvedUrl.takeIf { it.isNotBlank() }
        // 可变性必须**逐对象**判，不能按"封面"这个用途一刀切。截图 key 按页面 URL 寻址、会被
        // 后续补抓原地覆盖，确实不能签长效链接；但同样当封面用的 SOCIAL/OG 图是内容寻址的，
        // 字节永不改变。以前这里不分青红皂白全走短有效期，等于让**全站字节最大的一类资产**
        // 每小时换一次 URL、每小时全量回源一次 —— 而账本行的 immutable 早就能把两者分开。
        // 源站直连地址会被 signAsset 原样返回（外链签名反而会破坏它），无需在此分流
        return OssUtils.signCover(
            ref,
            mime = ledgerRow?.mime ?: asset.mime,
            immutable = ledgerRow?.immutable == true,
        )
    }

    /** 单个书签的封面。列表场景请用 [resolveCoverBatch]，避免 N+1。 */
    fun resolveCoverOne(pageId: String): String? =
        resolveCoverBatch(listOf(pageId))[pageId]

    /**
     * 批量解析。三次查询搞定（书签→站点、站点资产、页面资产），其余在内存里完成。
     */
    fun resolveBatch(pageIds: List<String>, mode: DisplayMode): Map<String, ResolvedLogo> {
        val ids = pageIds.filter { it.isNotBlank() }.distinct()
        if (ids.isEmpty()) return emptyMap()

        val assetsByBookmark = assetsByBookmark(ids, siteIdOf(ids))

        // 回退到源站直连是**降级**，不是正常形态：说明这些图没进我方 OSS，可用性依赖第三方站点。
        // 逐张打日志会把列表渲染刷爆，因此按批聚合成一行——这条降级以前是完全静默的，
        // 结果整站图片长期热链而监控上毫无痕迹
        // 一次取全本批资产的账本行，把 file_id 换成真正的 object key。**必须批量** ——
        // 首页一屏几十个图标，在 presentUrl 里逐张查库就是教科书式的 N+1
        val objectByFileId = objectsOf(assetsByBookmark.values.flatten())

        var hotlinked = 0
        val resolved = ids.associateWith { id ->
            build(assetsByBookmark[id].orEmpty(), mode, objectByFileId) { hotlinked++ }
        }
        if (hotlinked > 0) log.warn(
            "[SiteAssetResolver] {}/{} 个书签回退到源站直连图片(未落 OSS)，" +
                "请检查 bookmarkify.scrapper.upload-assets 是否开启、以及这些书签是否需要重抓: mode={}",
            hotlinked, ids.size, mode
        )
        return resolved
    }

    /** 后台用：某书签可用的全部资产（站点图标 + 该页自己的图），供人工挑选与排查。 */
    fun assetsOf(pageId: String): List<SiteAssetEntity> =
        assetsOfBatch(listOf(pageId))[pageId].orEmpty()

    /**
     * 后台列表用：一次查出多个书签可用的全部资产。
     *
     * 后台列表要按 role 分列展示 favicon/logo/社交图，逐行调 [assetsOf] 就是 N+1；
     * 这里批量取回后在内存分组。
     */
    fun assetsOfBatch(pageIds: List<String>): Map<String, List<SiteAssetEntity>> {
        val ids = pageIds.filter { it.isNotBlank() }.distinct()
        if (ids.isEmpty()) return emptyMap()
        return assetsByBookmark(ids, siteIdOf(ids))
    }

    // ────── 取数 ──────

    /**
     * pageId → siteId。一条 in 查询，只取需要的两列。
     *
     * 用 `selectMaps` 而不是把两列映射回 [PageEntity]：实体是 Kotlin data class，
     * `id`/`urlHost`/`urlScheme` 没有默认值 ⇒ 没有无参构造，MyBatis 只能退化成
     * 「按结果列去找同签名的构造函数」，两列 String 匹配不上任何一个，运行时直接抛
     * `No constructor found ... matching [String, String]`。**投影查询一律走
     * selectMaps/selectObjs，别拿实体接残缺的列。**
     */
    private fun siteIdOf(pageIds: List<String>): Map<String, String> = bookmarkMapper.selectMaps(
        KtQueryWrapper(PageEntity::class.java)
            .select(PageEntity::id, PageEntity::siteId)
            .`in`(PageEntity::id, pageIds)
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
        pageIds: List<String>,
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
                .`in`(SiteAssetEntity::ownerId, pageIds)
                .orderByAsc(SiteAssetEntity::role)
                .orderByDesc(SiteAssetEntity::isPrimary)
        ).groupBy { it.ownerId }

        return pageIds.associateWith { id ->
            siteAssets[siteIdOf[id]].orEmpty() + pageAssets[id].orEmpty()
        }
    }

    // ────── 组装 ──────

    /**
     * 一批资产的 `file_id` → `object_key`。
     *
     * 对外暴露成一个显式的 Map 而不是让 [presentUrl] 自己去查，是为了逼着调用方在批量入口处
     * 就把这次查询做掉 —— `SiteAssetResolver` 的全部方法都是列表场景，任何"用的时候再查"
     * 的写法在这里都会退化成 N+1。
     */
    fun objectsOf(assets: Collection<SiteAssetEntity>): Map<String, OssObjectEntity> {
        val fileIds = assets.mapNotNull { it.fileId?.takeIf { id -> id.isNotBlank() } }.distinct()
        if (fileIds.isEmpty()) return emptyMap()
        return ossObjectService.findByIds(fileIds)
    }

    /** @param onHotlink 选中的图没落 OSS、只能给源站直连地址时回调，供调用方聚合告警 */
    private fun build(
        assets: List<SiteAssetEntity>,
        mode: DisplayMode,
        objectByFileId: Map<String, OssObjectEntity>,
        onHotlink: () -> Unit,
    ): ResolvedLogo {
        val chosen = AssetRolePolicy.resolve(assets, mode) ?: return ResolvedLogo.EMPTY

        // 大图模式下拿到的若是降级小图，宁可走首字母色块也不要拉伸
        if (mode == DisplayMode.TILE && AssetRolePolicy.shouldFallbackToMonogram(chosen)) {
            return ResolvedLogo(
                url = null,
                role = chosen.role,
                quality = chosen.quality,
                monogram = true,
            )
        }

        return ResolvedLogo(
            url = presentUrl(chosen, mode, objectByFileId, onHotlink),
            role = chosen.role,
            quality = chosen.quality,
            isVector = chosen.isVector,
            monogram = false,
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
    private fun presentUrl(
        asset: SiteAssetEntity,
        mode: DisplayMode,
        objectByFileId: Map<String, OssObjectEntity>,
        onHotlink: () -> Unit,
    ): String? = signedOssUrl(asset, mode, objectByFileId)
    // 没落到我们自己的 OSS（抓取时只做了 PROBE，或那张图当时下载失败），只能给源站直连
    // 地址。这是降级路径：源站防盗链、改版 404、境外站点不可达都会直接砸到用户脸上
        ?: asset.resolvedUrl.takeIf { it.isNotBlank() }?.also {
            log.debug(
                "[presentUrl] 资产未落 OSS，回退源站直连: ownerType={}, ownerId={}, role={}, url={}",
                asset.ownerType, asset.ownerId, asset.role, it
            )
            onHotlink()
        }

    /**
     * 只认我方 OSS 的签名地址；这张图没落 OSS 就返回 null，**绝不回退源站直连**。
     *
     * 与 [presentUrl] 的区别只在缺图时的行为，而这个区别是有调用方专门要的：后台那些
     * 「顺带显示个图标」的场景（如 scrapper 调用日志列表）宁可显示本地兜底图，也不能让
     * 浏览器去请求外站 —— 那会把管理员的 IP 暴露给一批我们自己都抓不动的站点，并在控制台
     * 刷出成片的超时/证书报错。前台书签渲染则相反：热链再差也好过用户看到空白，所以它走
     * [presentUrl]，代价记在 `hotlinked` 计数里。
     */
    private fun signedOssUrl(
        asset: SiteAssetEntity,
        mode: DisplayMode,
        objectByFileId: Map<String, OssObjectEntity>,
    ): String? {
        // file_id 优先：它是与存储层解耦后的正式来源。storage_url 只作为兜底 ——
        // 覆盖迁移尚未回填的行、以及改造前写入的完整 URL 存量
        val ledgerRow = asset.fileId?.let { objectByFileId[it] }
        val storage = ledgerRow?.objectKey
            ?: asset.storageUrl?.takeIf { it.isNotBlank() }
            ?: return null

        // storage 可能是 object key（新契约）或存量的完整 URL，signAsset 统一处理这两种形态。
        // 内容寻址的对象字节永不改变，签长效链接换缓存命中率（回源一次要付一次 OSS 图片处理费）
        return OssUtils.signAsset(
            storage,
            renderSize(mode),
            ledgerRow?.immutable == true,
            // 账本记的是桶里那份字节的 MIME，比抓取时落在 site_asset 上的更贴近实际，优先用它
            mime = ledgerRow?.mime ?: asset.mime,
            isVector = asset.isVector,
        )
    }

    /**
     * 按**域名**取站点图标的签名地址 —— 全类唯一不以 pageId 为入口的方法。
     *
     * 存在的理由是调用方手上真的只有一个域名字符串：`scrapper_call_log` 记的是一次抓取动作，
     * 抓失败时压根不存在对应的 page 行，自然也没有 pageId。此前后台就是因为没有这个入口，
     * 在前端拼了 `https://<host>/favicon.ico` 直连外站。
     *
     * 只查 [AssetOwnerType.SITE] 层：域名能确定的就到站点为止，PAGE 层资产属于某个具体页面，
     * 用它来代表整个域名是错的。取图顺序复用 [DisplayMode.LIST]（FAVICON → LOGO，按 64px
     * 挑最合适的一张），因为调用方就是列表里的 16px 小图标。
     *
     * @return host → 签名地址；该域名没有站点图标、或图标未落 OSS 时**不出现在结果里**，
     *   调用方据此走自己的本地兜底图
     */
    fun siteFaviconByHost(hosts: Collection<String>): Map<String, String> {
        val wanted = hosts.filter { it.isNotBlank() }.distinct()
        if (wanted.isEmpty()) return emptyMap()

        val siteIdByHost = siteMapper.selectMaps(
            KtQueryWrapper(SiteEntity::class.java)
                .select(SiteEntity::id, SiteEntity::host)
                .`in`(SiteEntity::host, wanted)
        ).mapNotNull { row ->
            val id = row.column("id")
            val host = row.column("host")
            if (id.isNullOrBlank() || host.isNullOrBlank()) null else host to id
        }.toMap()
        if (siteIdByHost.isEmpty()) return emptyMap()

        val assetsBySite = siteAssetMapper.selectList(
            KtQueryWrapper(SiteAssetEntity::class.java)
                .eq(SiteAssetEntity::ownerType, AssetOwnerType.SITE)
                .`in`(SiteAssetEntity::ownerId, siteIdByHost.values.distinct())
                .`in`(SiteAssetEntity::role, listOf(AssetRole.FAVICON, AssetRole.LOGO))
        ).groupBy { it.ownerId }
        val objectByFileId = objectsOf(assetsBySite.values.flatten())

        return siteIdByHost.mapNotNull { (host, siteId) ->
            val chosen = AssetRolePolicy.resolve(assetsBySite[siteId].orEmpty(), DisplayMode.LIST)
                ?: return@mapNotNull null
            signedOssUrl(chosen, DisplayMode.LIST, objectByFileId)?.let { host to it }
        }.toMap()
    }
}
