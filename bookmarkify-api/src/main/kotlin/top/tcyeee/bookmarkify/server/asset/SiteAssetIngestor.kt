package top.tcyeee.bookmarkify.server.asset

import com.fasterxml.jackson.databind.ObjectMapper
import top.tcyeee.bookmarkify.entity.dto.scrape.Asset
import top.tcyeee.bookmarkify.entity.dto.scrape.ScrapeResponse
import top.tcyeee.bookmarkify.entity.entity.ScrapeSnapshotEntity
import top.tcyeee.bookmarkify.entity.entity.SiteAssetEntity
import top.tcyeee.bookmarkify.entity.entity.PageMetaEntity
import top.tcyeee.bookmarkify.entity.enums.AssetOwnerType
import top.tcyeee.bookmarkify.entity.enums.AssetRole
import top.tcyeee.bookmarkify.entity.enums.AssetQuality
import java.time.LocalDateTime

/**
 * 把 scrapper 的 [ScrapeResponse] 投影成可落库的行。
 *
 * 纯函数、无副作用、不碰数据库 —— 落库由调用方负责。这样投影规则本身可以完全离线测试，
 * 不必起 Spring 上下文。
 */
object SiteAssetIngestor {

    /** 一次抓取投影出来的全部行。 */
    data class Projection(
        val snapshot: ScrapeSnapshotEntity,
        val pageMeta: PageMetaEntity?,
        val assets: List<SiteAssetEntity>,
    )

    /**
     * 成功抓取的投影。
     *
     * @param pageId 目标书签
     * @param url 请求的 URL（非 finalUrl —— 快照要记的是"我们请求了什么"）
     * @param response scrapper 响应
     * @param durationMs 端到端耗时
     * @param mapper 用于把原始块序列化进 jsonb 列
     */
    fun project(
        siteId: String,
        pageId: String,
        url: String,
        response: ScrapeResponse,
        durationMs: Int,
        mapper: ObjectMapper,
    ): Projection {
        val snapshot = ScrapeSnapshotEntity(
            pageId = pageId,
            url = url,
            ok = true,
            request = response.request?.let { mapper.writeValueAsString(it) },
            response = mapper.writeValueAsString(response),
            durationMs = durationMs,
        )

        val meta = response.meta
        val pageMeta = meta?.let {
            PageMetaEntity(
                pageId = pageId,
                title = it.title,
                description = it.description,
                siteName = it.siteName,
                // 短名只可能来自 manifest.short_name；旧实现解析了却丢弃，这里正式落库
                siteShortName = it.shortName,
                canonicalUrl = it.canonicalUrl,
                lang = it.lang,
                themeColor = it.themeColor,
                metaSources = it.sources.takeIf { s -> s.isNotEmpty() }
                    ?.let { s -> mapper.writeValueAsString(s) },
                fetchLayer = response.fetch.layerUsed.name,
                httpStatus = response.fetch.httpStatus,
                antiCrawler = response.diagnostics.antiCrawler?.detected == true,
                fetchedAt = LocalDateTime.now(),
                updateTime = LocalDateTime.now(),
            )
        }

        // 归属必须在 assignRoles 之后算：那一步的第三遍会把借来的 favicon 改判成 LOGO，
        // 归属得按最终的 role 来定。
        val declared = AssetRolePolicy.assignRoles(
            response.assets.mapNotNull { toEntity(pageId, it) }
        ).onEach { it.assignOwner(siteId) }

        val assets = declared + listOfNotNull(screenshotAsset(pageId, url, response))

        return Projection(snapshot, pageMeta, assets)
    }

    /**
     * 按最终 role 决定挂在 site 还是 bookmark 上。
     *
     * `pageId` 保持原值不动 —— 它降级成了纯溯源信息（"这张图当初是从哪个页面抓到的"），
     * 归属看 `ownerType` / `ownerId`。
     */
    private fun SiteAssetEntity.assignOwner(siteId: String) {
        ownerType = AssetRolePolicy.ownerTypeOf(role)
        ownerId = when (ownerType) {
            AssetOwnerType.SITE -> siteId
            AssetOwnerType.PAGE -> pageId.orEmpty()
        }
    }

    /** 抓取失败时只留快照，便于事后排查为什么失败。 */
    fun projectFailure(
        pageId: String,
        url: String,
        errorMsg: String?,
        durationMs: Int,
    ): Projection = Projection(
        snapshot = ScrapeSnapshotEntity(
            pageId = pageId,
            url = url,
            ok = false,
            errorMsg = errorMsg?.take(1000),
            durationMs = durationMs,
        ),
        pageMeta = null,
        assets = emptyList(),
    )

    /**
     * 单张资产声明 → 实体。
     *
     * 处理失败的那张（`error != null`）仍然保留，只是带上 errorMsg —— 它记录了"这个站
     * 声明了这张图但取不到"，对排查比直接丢弃有用。role/quality 稍后由
     * [AssetRolePolicy.assignRoles] 统一判定。
     */
    private fun toEntity(pageId: String, a: Asset): SiteAssetEntity? {
        if (a.resolvedUrl.isBlank()) return null
        return SiteAssetEntity(
            pageId = pageId,
            extractor = a.extractor.name,
            originUrl = a.originUrl.take(1000),
            resolvedUrl = a.resolvedUrl.take(1000),
            storageUrl = a.storageKey?.take(1000),
            width = a.width,
            height = a.height,
            byteSize = a.byteSize,
            mime = a.mime,
            isVector = a.isVector == true,
            contentHash = a.contentHash,
            errorMsg = a.error?.take(500),
        )
    }

    /**
     * 截图单列一条 [AssetRole.SCREENSHOT]。
     *
     * 它不在 `assets[]` 里（截图不是页面"声明"的资源，是我们渲染出来的），所以不参与
     * [AssetRolePolicy.assignRoles] 的角色判定，直接定死角色。
     *
     * [originUrl]/[resolvedUrl] 记的是**被截图的那个页面**，不是截图文件本身 —— 截图没有
     * "源地址"可言。此前这两列填的是 object key，既不是 URL（后台详情页的"源地址"列会显示
     * 一个裸 key），也让"这张图哪来的"这个信息彻底丢失。
     *
     * 截图补抓（`BookmarkScreenshotEvent`）只需要这一行，所以它是 public 的：那条链路走
     * [SiteAssetWriter.upsertScreenshot]，不能借道 [project]——整条投影会连带重写 PAGE 层
     * 的社交图。
     *
     * @return 没有截图、或截图只内联未落存储时返回 null
     */
    fun screenshotAsset(
        pageId: String,
        pageUrl: String,
        response: ScrapeResponse,
    ): SiteAssetEntity? {
        val shot = response.screenshot ?: return null
        // 只内联(dataUrl)未落存储的截图不入库：库里存 base64 会把 site_asset 撑爆，
        // 且它无法参与签名/缩放那套展示策略
        val storageKey = shot.storageKey ?: return null
        // 截图针对的是跟完重定向后的最终页面
        val source = response.fetch.finalUrl.takeIf { it.isNotBlank() } ?: pageUrl
        return SiteAssetEntity(
            pageId = pageId,
            // 截图就是这一个页面的内容，天然属于 PAGE 层
            ownerType = AssetOwnerType.PAGE,
            ownerId = pageId,
            role = AssetRole.SCREENSHOT,
            extractor = "HEADLESS_CAPTURE",
            quality = AssetQuality.TRUSTED,
            originUrl = source.take(1000),
            resolvedUrl = source.take(1000),
            storageUrl = storageKey.take(1000),
            width = shot.width.takeIf { it > 0 },
            height = shot.height.takeIf { it > 0 },
            byteSize = shot.byteSize,
            mime = "image/${shot.format.name.lowercase()}",
            isPrimary = true,
        )
    }
}
