package top.tcyeee.bookmarkify.entity.dto

import io.swagger.v3.oas.annotations.media.Schema
import top.tcyeee.bookmarkify.entity.dto.scrape.ScrapeResponse

/**
 * 调试接口的返回体：**原始响应 + 一份纯展示用的签名地址**。
 *
 * 为什么不直接把 [ScrapeResponse] 里的 `storageKey` 改写成 URL：
 * scrapper 报告的是事实（字节落在哪个 object key），域名 / 签名 / 缩放是本服务的部署策略。
 * 把签名后的 URL 塞回原响应，就等于让调试页看到的"原始 JSON"不再是 scrapper 真正返回的东西 ——
 * 而那恰恰是这个页面存在的唯一理由。所以签名结果单独放一层，[response] 保持逐字节原样。
 */
data class ScrapeDebugVO(
    @field:Schema(description = "scrapper 原样返回的响应，不做任何改写")
    val response: ScrapeResponse,
    @field:Schema(description = "仅供后台预览用的可直接访问地址，由本服务对 storageKey 签名得到")
    val previews: ScrapePreviews,
)

/**
 * 与 [ScrapeResponse] 平行的预览地址。
 *
 * [assets] 按**下标**与 `response.assets` 一一对应（含 null 占位），不用 map-by-key 是因为
 * 同一张图可能被多个 extractor 声明、`storageKey` 会重复，键并不唯一。
 */
data class ScrapePreviews(
    @field:Schema(description = "与 response.assets 下标一一对应；该项未落对象存储时为 null")
    val assets: List<String?> = emptyList(),
    @field:Schema(description = "截图的签名地址；未落对象存储时为 null（此时用 response.screenshot.dataUrl）")
    val screenshot: String? = null,
)
