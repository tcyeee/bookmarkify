package top.tcyeee.bookmarkify.server.asset

import top.tcyeee.bookmarkify.entity.entity.OssObjectEntity
import top.tcyeee.bookmarkify.entity.entity.SiteAssetEntity
import top.tcyeee.bookmarkify.entity.enums.DisplayMode
import top.tcyeee.bookmarkify.utils.OssUtils

/**
 * 把一行 `site_asset` 变成浏览器能用的地址：**账本 → object key → 签名 → 按用途缩放**。
 *
 * 这一层是**策略**，不是工具。OSS 桶是私有读的，库里存的是 object key，域名、签名有效期、
 * 缩放参数全是本服务的部署决定 —— scrapper 只报「字节落在哪个 key」，别的一概不知
 * （见根 `CLAUDE.md`「Site Assets & the Scrapper Contract」）。
 *
 * 抽成独立对象是因为它有三个消费者（[IconResolver]、[CoverResolver]、
 * [IconVerdictAuditService]），而它们各写一份的话，「file_id 优先、storage_url 兜底」
 * 这条取值顺序迟早会分叉 —— 那种分叉不会报错，只会让某一处的图静默变成空白。
 */
object AssetUrlSigner {

    /**
     * 各展示模式期望的渲染边长（CSS 像素）。
     *
     * **这张表是渲染尺寸的唯一来源。** 此前它散在三处靠人记：`SiteAssetResolver.renderSize`
     * 私有一份、`InternalAssetController.ALLOWED_SIZES` 用注释同步一份、nginx 的 `map`
     * 白名单再一份。名单对不上的后果不是报错，是那个尺寸退化成原图直出 —— 多下几百 KB，
     * 而且没有任何症状。
     */
    fun renderSize(mode: DisplayMode): Int = when (mode) {
        // 2x 屏下 72px 的格子需要 144px 的图源
        DisplayMode.TILE -> 256
        DisplayMode.LIST -> 64
    }

    /** 本服务实际会签发的全部尺寸，供 `InternalAssetController` 与 nginx 白名单对齐 */
    val ICON_SIZES: Set<Int> = DisplayMode.entries.map { renderSize(it) }.toSet()

    /**
     * 图标的签名地址：**只认我方 OSS，没落 OSS 返回 null，绝不回退源站直连**。
     *
     * 回不回退是**调用方**的选择，不是这一层的：前台宁可热链也好过用户看到空白
     * （[IconResolver] 自己接了那条兜底并计数），后台则一律不回退 —— 让管理员的浏览器去连
     * 一批我们自己都抓不动的站点，等于把管理员公网 IP 交出去，还会在控制台刷出成片的
     * 超时与证书报错。把兜底放在这里就等于替后台做了那个决定。
     */
    fun signedIcon(
        asset: SiteAssetEntity,
        mode: DisplayMode,
        objectByFileId: Map<String, OssObjectEntity>,
    ): String? = signedIcon(asset, renderSize(mode), objectByFileId)

    /** 指定边长的重载，给不按 [DisplayMode] 取尺寸的场景（后台预览等）用 */
    fun signedIcon(
        asset: SiteAssetEntity,
        size: Int,
        objectByFileId: Map<String, OssObjectEntity>,
    ): String? {
        val (ref, ledgerRow) = refOf(asset, objectByFileId) ?: return null
        // 内容寻址的对象字节永不改变，签长效链接换缓存命中率（回源一次要付一次 OSS 图片处理费）
        return OssUtils.signAsset(
            ref,
            size,
            ledgerRow?.immutable == true,
            // 账本记的是桶里那份字节的 MIME，比抓取时落在 site_asset 上的更贴近实际，优先用它
            mime = ledgerRow?.mime ?: asset.mime,
            isVector = asset.isVector,
        )
    }

    /**
     * 封面的签名地址：**只限宽，不限高**（`m_lfit`）。
     *
     * 图标是方的，走等宽高的 `m_fill`；封面不能这么处理 —— 一张 1280×720 的截图被裁成
     * 640×640 等于把两侧砍掉一半，正好毁掉「这个页面长什么样」这个唯一用途。
     *
     * 与图标的另一处不同：这里接受**源站直连地址**。[SiteAssetEntity.renderable] 明确接纳
     * 「没落 OSS 但有源站地址」的资产，于是 [AssetRolePolicy.resolveCover] 会正常选中它们；
     * 这里若只认 OSS，选中的资产就会被签成 null —— 表现为 upload-assets 关闭时「退 og:image」
     * 这条兜底永远不生效。源站地址会被 `signAsset` 原样返回，无需在此分流。
     */
    fun signedCover(asset: SiteAssetEntity, objectByFileId: Map<String, OssObjectEntity>): String? {
        val ledgerRow = asset.fileId?.let { objectByFileId[it] }
        val ref = ledgerRow?.objectKey
            ?: asset.storageUrl?.takeIf { it.isNotBlank() }
            ?: asset.resolvedUrl.takeIf { it.isNotBlank() }
        // 可变性必须**逐对象**判，不能按"封面"这个用途一刀切。截图 key 按页面 URL 寻址、会被
        // 后续补抓原地覆盖，确实不能签长效链接；但同样当封面用的 SOCIAL/OG 图是内容寻址的，
        // 字节永不改变。以前这里不分青红皂白全走短有效期，等于让**全站字节最大的一类资产**
        // 每小时换一次 URL、每小时全量回源一次 —— 而账本行的 immutable 早就能把两者分开
        return OssUtils.signCover(
            ref,
            mime = ledgerRow?.mime ?: asset.mime,
            immutable = ledgerRow?.immutable == true,
        )
    }

    /**
     * 取这行资产在我方 OSS 里的引用。
     *
     * `file_id` 优先：它是与存储层解耦后的正式来源。`storage_url` 只作为兜底 —— 覆盖迁移
     * 尚未回填的行、以及改造前写入的完整 URL 存量（`signAsset` 统一处理这两种形态）。
     */
    private fun refOf(
        asset: SiteAssetEntity,
        objectByFileId: Map<String, OssObjectEntity>,
    ): Pair<String, OssObjectEntity?>? {
        val ledgerRow = asset.fileId?.let { objectByFileId[it] }
        val ref = ledgerRow?.objectKey
            ?: asset.storageUrl?.takeIf { it.isNotBlank() }
            ?: return null
        return ref to ledgerRow
    }
}
