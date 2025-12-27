package top.tcyeee.bookmarkify.entity.dto

import org.jsoup.nodes.Document

/**
 * @author tcyeee
 * @date 12/27/25 16:42
 */

/* 表示网站 <head> 中常见信息的实体类 */
data class WebsiteHeaderInfo(
    var title: String? = null, // 网页标题，<title> 标签内容，通常用于浏览器标签页显示
    var charset: String? = null, // 页面编码，通常是 "utf-8" 等，来自 <meta charset>
    var keywords: String? = null, // 页面关键词，用于SEO，来自 <meta name="keywords">
    var description: String? = null, // 页面描述，用于SEO，来自 <meta name="description">
    var viewport: String? = null, // 视口设置，控制移动端页面缩放和宽度，来自 <meta name="viewport">
    var xUaCompatible: String? = null, // 兼容模式，如 IE 浏览器兼容设置，来自 <meta http-equiv="X-UA-Compatible">
    var renderer: String? = null, // 浏览器渲染引擎提示，如 WebKit，来自 <meta name="renderer">
    var copyright: String? = null, // 网站版权信息，通常来自 <meta name="copyright">
    var referrerPolicy: String? = null, // 页面引用来源控制策略，来自 <meta name="referrer">
    var mobileAgent: String? = null, // 移动端跳转地址，通常为移动端页面地址，来自 <meta http-equiv="mobile-agent">
    var ogImage: String? = null, // Open Graph 图片，用于社交媒体分享显示的图片地址，来自 <meta property="og:image">
    var canonicalUrl: String? = null, // Canonical 链接，用于指定页面标准地址，避免SEO重复内容，来自 <link rel="canonical">
    var manifestUrl: String? = null, // 网站 manifest 文件路径，定义PWA相关配置，来自 <link rel="manifest">
    var appleTouchIcons: Map<String, String> = emptyMap(), // Apple Touch Icons，适配iOS主屏幕图标，多尺寸时可用尺寸为key，URL为value
    var preconnectUrls: List<String> = emptyList(), // 预连接域名，用于提升首次请求速度，来自 <link rel="preconnect">
    var preloadResources: List<PreloadResource> = emptyList(), // 预加载资源，如js、css、图片资源URL，来自 <link rel="preload">
    var dnsPrefetchUrls: List<String> = emptyList(), // DNS 预取，用于提前解析域名，来自 <link rel="dns-prefetch">
    var styleSheets: List<String> = emptyList(), // 样式表链接，CSS 文件 URL 列表，来自 <link rel="stylesheet">
    var scriptPrefetchUrls: List<String> = emptyList(), // 预获取的脚本文件，来自 <link rel="prefetch" as="script">
    var faviconUrls: List<String> = emptyList(), // 页面图标 favicon 的路径，可能是 .ico 或 png
    var customMeta: Map<String, String> = emptyMap(), // 其他自定义或额外的 meta 信息，如自定义属性或扩展字段
    var customLink: Map<String, String> = emptyMap(), // 其他自定义或额外的 link 信息，方便扩展
    var manifest: WebManifest? = null, // 网站 Manifest 内容
    var antiCrawlerDetected: Boolean = false // 是否检测到反爬虫/WAF
) {
    constructor(document: Document) : this() {
        this.title = document.title()
        this.charset = document.charset().name()

        // Anti-crawler detection
        this.antiCrawlerDetected = detectAntiCrawler(document)

        // Meta tags
        this.keywords = getMetaContent(document, "name", "keywords")
        this.description = getMetaContent(document, "name", "description")
        this.viewport = getMetaContent(document, "name", "viewport")
        this.renderer = getMetaContent(document, "name", "renderer")
        this.copyright = getMetaContent(document, "name", "copyright")
        this.referrerPolicy = getMetaContent(document, "name", "referrer")
        this.mobileAgent = getMetaContent(document, "http-equiv", "mobile-agent")
        this.xUaCompatible = getMetaContent(document, "http-equiv", "X-UA-Compatible")
        this.ogImage = getMetaContent(document, "property", "og:image")

        // Link tags (Use abs:href for absolute URLs)
        this.canonicalUrl = getLinkHref(document, "canonical")
        this.manifestUrl = getLinkHref(document, "manifest")

        // Lists
        this.preconnectUrls = getLinkHrefs(document, "preconnect")
        this.dnsPrefetchUrls = getLinkHrefs(document, "dns-prefetch")
        this.styleSheets = getLinkHrefs(document, "stylesheet")
        this.scriptPrefetchUrls =
            document.select("link[rel=prefetch][as=script]").map { it.attr("abs:href") }.filter { it.isNotBlank() }

        // Favicons (icon, shortcut icon, apple-touch-icon, etc.)
        this.faviconUrls = document.select("link[rel~=(?i)^(shortcut|icon|shortcut icon)$]").map { it.attr("abs:href") }
            .filter { it.isNotBlank() }.distinct()

        // Complex structures
        this.appleTouchIcons = document.select("link[rel=apple-touch-icon]").associate {
            val sizes = it.attr("sizes")
            (sizes.ifBlank { "default" }) to it.attr("abs:href")
        }

        this.preloadResources =
            document.select("link[rel=preload]").map { PreloadResource(it.attr("abs:href"), it.attr("as")) }
                .filter { it.url.isNotBlank() }

        // Custom Meta (Exclude known standard names)
        val standardMetaNames = setOf(
            "viewport",
            "renderer",
            "copyright",
            "referrer",
            "keywords",
            "description",
            "application-name",
            "author",
            "generator"
        )
        this.customMeta = document.select("meta[name]").toList().filter {
                !standardMetaNames.contains(it.attr("name").lowercase()) && it.attr("content").isNotBlank()
            }.associate { it.attr("name") to it.attr("content") }

        // Custom Link (Exclude known rels)
        val standardLinkRels = setOf(
            "canonical",
            "manifest",
            "preconnect",
            "dns-prefetch",
            "stylesheet",
            "preload",
            "prefetch",
            "icon",
            "shortcut icon",
            "apple-touch-icon"
        )
        this.customLink = document.select("link[rel]").toList().filter {
                !standardLinkRels.contains(it.attr("rel").lowercase()) && it.attr("href").isNotBlank()
            }.associate { it.attr("rel") to it.attr("abs:href") }
    }

    private fun getMetaContent(doc: Document, attr: String, value: String): String? =
        doc.select("meta[$attr=$value]").attr("content").ifBlank { null }

    private fun getLinkHref(doc: Document, rel: String): String? =
        doc.select("link[rel=$rel]").attr("abs:href").ifBlank { null }

    private fun getLinkHrefs(doc: Document, rel: String): List<String> =
        doc.select("link[rel=$rel]").map { it.attr("abs:href") }.filter { it.isNotBlank() }

    private fun detectAntiCrawler(doc: Document): Boolean {
        val title = doc.title().lowercase()
        val text = doc.text().lowercase()

        // 1. Common WAF Titles
        val wafTitles = listOf(
            "just a moment...",
            "attention required",
            "security check",
            "ddos-guard",
            "bitmitigate",
            "shieldsquare",
            "human verification"
        )
        if (wafTitles.any { title.contains(it) }) return true

        // 2. EdgeOne / Generic JS Challenge (Short body, script only, no visible content)
        // Huaban case: No title, short body, contains script with "cookie" manipulation or heavy
        // obfuscation
        if (title.isBlank() && doc.body().text().isBlank() && doc.select("script").isNotEmpty()) {
            val scriptContent = doc.select("script").html()
            if (scriptContent.contains("document.cookie") || scriptContent.contains("location.href")) {
                return true
            }
        }

        // 3. Cloudflare specific text
        if (text.contains("please enable cookies") && text.contains("security by cloudflare")) return true

        return false
    }
}

/* Web Manifest 数据结构 */
data class WebManifest(
    val name: String? = null,
    val shortName: String? = null,
    val description: String? = null,
    val startUrl: String? = null,
    val display: String? = null,
    val backgroundColor: String? = null,
    val themeColor: String? = null,
    val icons: List<ManifestIcon> = emptyList()
)

data class ManifestIcon(
    val src: String? = null, val sizes: String? = null, val type: String? = null
)

/* 预加载资源对象，包含 URL 及资源类型 */
data class PreloadResource(
    val url: String, // 资源URL
    val asType: String? = null // 资源类型，例如 "script", "style", "image"
)

/* 书签地址 */
data class BookmarkUrl(
    var urlRaw: String, // https://tool.chinaz.com/linksTesting/list?url=bilibili.com&type=1
    var urlScheme: String, // https
    var urlHost: String, // tool.chinaz.com
    var urlRoot: String, // https://tool.chinaz.com
    var urlFull: String, // https://tool.chinaz.com/linksTesting/list
    var urlPath: String?, // /linksTesting/list
    var urlQuery: String?, // url=bilibili.com&type=1
)
