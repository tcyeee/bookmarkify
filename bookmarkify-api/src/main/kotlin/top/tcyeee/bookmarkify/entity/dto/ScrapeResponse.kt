package top.tcyeee.bookmarkify.entity.dto

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import top.tcyeee.bookmarkify.entity.entity.BookmarkEntity
import top.tcyeee.bookmarkify.entity.enums.ParseStatusEnum
import java.net.URI
import java.time.LocalDateTime

/** bookmarkify-scrapper POST /scrape 的请求体 */
data class ScrapeRequest(
    /** 目标 URL */
    val url: String,
    /** 是否强制走无头浏览器；null 时由 scrapper 自行决定（Layer1 失败回退 Layer2） */
    val headless: Boolean? = null,
)

/**
 * bookmarkify-scrapper POST /scrape 的成功响应体，字段与 Rust 服务的 ScrapeResponse 对齐。
 * 接替原 iframely 作为远程解析的数据来源。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
data class ScrapeResponse(
    /** 页面标题 */
    val title: String? = null,
    /** 页面描述 */
    val description: String? = null,
    /** OG 主图 URL（scrapper 配置了 OSS 时为 OSS URL） */
    val image: String? = null,
    /** 网站图标，base64 data URL */
    val favicon: String? = null,
    /** 网站 LOGO URL（scrapper 配置了 OSS 时为 OSS URL） */
    val logo: String? = null,
    /** 数据来源：og / twitter_card / json_ld / html / headless */
    val source: String? = null,
    /** 命中 scrapper 缓存时为 true */
    val cached: Boolean? = null,
    /** 截图：OSS URL 或 base64 PNG（仅 headless 模式存在） */
    val screenshot: String? = null,
) {
    /** 将 scrapper 解析结果填充到已有书签实体并返回 */
    fun entity(bookmark: BookmarkEntity): BookmarkEntity = bookmark.apply {
        title = this@ScrapeResponse.title
        description = this@ScrapeResponse.description
        isActivity = true
        parseStatus = ParseStatusEnum.SUCCESS
        updateTime = LocalDateTime.now()
    }

    /**
     * 将 scrapper 的 logo / image 转为 ManifestIcon 列表，复用既有 OSS 存储流程
     * （OssUtils.restoreWebsiteLogoAndOg）：
     * - image → sizes="og"（宽屏 OG 分享图）
     * - logo  → 普通 LOGO
     *
     * scrapper 解析 HTML 时可能返回相对路径（如 `/icon.png`），OSS 下载要求绝对 URL，
     * 否则 `URI.toURL()` 会抛 "URI is not absolute"。这里统一相对 [pageUrl] 解析为绝对 URL。
     */
    fun toManifestIcons(pageUrl: String? = null): List<ManifestIcon> = buildList {
        image?.takeIf { it.isNotBlank() }?.let { add(ManifestIcon(src = absolutize(it, pageUrl), sizes = "og")) }
        logo?.takeIf { it.isNotBlank() }?.let { add(ManifestIcon(src = absolutize(it, pageUrl))) }
    }

    /** 将可能为相对路径的 [src] 相对 [pageUrl] 解析为绝对 URL；无法解析时原样返回 */
    private fun absolutize(src: String, pageUrl: String?): String = runCatching {
        val uri = URI(src)
        when {
            uri.isAbsolute -> src
            pageUrl.isNullOrBlank() -> src
            else -> URI(pageUrl).resolve(uri).toString()
        }
    }.getOrDefault(src)
}
