package top.tcyeee.bookmarkify.entity.dto

/* 书签地址 */
data class BookmarkUrlWrapper(
    var urlRaw: String, // https://tool.chinaz.com/linksTesting/list?url=bilibili.com&type=1
    var urlScheme: String, // https
    var urlHost: String, // tool.chinaz.com
    var urlRoot: String, // https://tool.chinaz.com
    var urlFull: String, // https://tool.chinaz.com/linksTesting/list
    var urlPath: String?, // /linksTesting/list
    var urlQuery: String?, // url=bilibili.com&type=1
)

/* 页面请求头 */
data class BookmarkWrapper(
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
    var antiCrawlerDetected: Boolean = false, // 是否检测到反爬虫/WAF
    var distinctIcons: List<ManifestIcon>? = emptyList(), // 网站所有存在的图标(统一为线上地址)
    var baseUrl: String? = null, // 网站基础地址(用于解析相对路径)
) {
    // 网站基础图标(.ico)是否存在
    fun iconActivity(): Boolean = distinctIcons?.any {
        it.sizes == "16x16" || it.src?.endsWith(".ico", ignoreCase = true) == true
    } == true

    // 网站是否存在高清图标
    fun iconHd(): Boolean = distinctIcons?.any { it.sizes != "16x16" && it.sizes != "og" } == true
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

/* icon */
data class ManifestIcon(
    val src: String? = null, val sizes: String? = null, val type: String? = null
) {
    fun size(): Int {
        if (sizes.isNullOrBlank() || sizes.equals("og", ignoreCase = true)) return 0
        return sizes.split(Regex("\\s+")).mapNotNull { s -> s.split(Regex("[xX]")).firstOrNull()?.toIntOrNull() }
            .maxOrNull() ?: 0
    }
}

/* 预加载资源对象，包含 URL 及资源类型 */
data class PreloadResource(val url: String, val asType: String? = null)
