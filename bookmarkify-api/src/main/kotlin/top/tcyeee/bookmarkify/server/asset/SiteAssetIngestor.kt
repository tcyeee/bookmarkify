package top.tcyeee.bookmarkify.server.asset

import com.fasterxml.jackson.databind.ObjectMapper
import top.tcyeee.bookmarkify.entity.dto.scrape.Asset
import top.tcyeee.bookmarkify.entity.dto.scrape.ScrapeResponse
import top.tcyeee.bookmarkify.entity.entity.ScrapeSnapshotEntity
import top.tcyeee.bookmarkify.entity.entity.SiteAssetEntity
import top.tcyeee.bookmarkify.entity.entity.SitePageMetaEntity
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
        val pageMeta: SitePageMetaEntity?,
        val assets: List<SiteAssetEntity>,
    )

    /**
     * 成功抓取的投影。
     *
     * @param bookmarkId 目标书签
     * @param url 请求的 URL（非 finalUrl —— 快照要记的是"我们请求了什么"）
     * @param response scrapper 响应
     * @param durationMs 端到端耗时
     * @param mapper 用于把原始块序列化进 jsonb 列
     */
    fun project(
        bookmarkId: String,
        url: String,
        response: ScrapeResponse,
        durationMs: Int,
        mapper: ObjectMapper,
    ): Projection {
        val snapshot = ScrapeSnapshotEntity(
            bookmarkId = bookmarkId,
            url = url,
            ok = true,
            request = response.request?.let { mapper.writeValueAsString(it) },
            response = mapper.writeValueAsString(response),
            durationMs = durationMs,
        )

        val meta = response.meta
        val pageMeta = meta?.let {
            SitePageMetaEntity(
                bookmarkId = bookmarkId,
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

        val assets = AssetRolePolicy.assignRoles(
            response.assets.mapNotNull { toEntity(bookmarkId, it) }
        ) + screenshotAsset(bookmarkId, response)

        return Projection(snapshot, pageMeta, assets)
    }

    /** 抓取失败时只留快照，便于事后排查为什么失败。 */
    fun projectFailure(
        bookmarkId: String,
        url: String,
        errorMsg: String?,
        durationMs: Int,
    ): Projection = Projection(
        snapshot = ScrapeSnapshotEntity(
            bookmarkId = bookmarkId,
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
    private fun toEntity(bookmarkId: String, a: Asset): SiteAssetEntity? {
        if (a.resolvedUrl.isBlank()) return null
        return SiteAssetEntity(
            bookmarkId = bookmarkId,
            extractor = a.extractor.name,
            originUrl = a.originUrl.take(1000),
            resolvedUrl = a.resolvedUrl.take(1000),
            storageUrl = a.storageUrl?.take(1000),
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
     */
    private fun screenshotAsset(bookmarkId: String, response: ScrapeResponse): List<SiteAssetEntity> {
        val shot = response.screenshot ?: return emptyList()
        val url = shot.storageUrl ?: return emptyList() // 只内联未落存储的截图不入库
        return listOf(
            SiteAssetEntity(
                bookmarkId = bookmarkId,
                role = AssetRole.SCREENSHOT,
                extractor = "HEADLESS_CAPTURE",
                quality = AssetQuality.TRUSTED,
                originUrl = url.take(1000),
                resolvedUrl = url.take(1000),
                storageUrl = url.take(1000),
                width = shot.width.takeIf { it > 0 },
                height = shot.height.takeIf { it > 0 },
                byteSize = shot.byteSize,
                mime = "image/${shot.format.name.lowercase()}",
                isPrimary = true,
            )
        )
    }
}
