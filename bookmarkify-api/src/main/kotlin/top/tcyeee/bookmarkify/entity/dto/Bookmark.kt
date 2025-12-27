package top.tcyeee.bookmarkify.entity.dto

import cn.hutool.core.util.URLUtil
import top.tcyeee.bookmarkify.config.exception.CommonException
import top.tcyeee.bookmarkify.config.exception.ErrorType

/**
 * @author tcyeee
 * @date 12/27/25 16:42
 */

/* 表示网站 <head> 中常见信息的实体类 */
data class WebsiteHeaderInfo(
    val title: String? = null, // 网页标题，<title> 标签内容，通常用于浏览器标签页显示
    val charset: String? = null, // 页面编码，通常是 "utf-8" 等，来自 <meta charset>
    val viewport: String? = null, // 视口设置，控制移动端页面缩放和宽度，来自 <meta name="viewport">
    val xUaCompatible: String? = null, // 兼容模式，如 IE 浏览器兼容设置，来自 <meta http-equiv="X-UA-Compatible">
    val renderer: String? = null, // 浏览器渲染引擎提示，如 WebKit，来自 <meta name="renderer">
    val copyright: String? = null, // 网站版权信息，通常来自 <meta name="copyright">
    val referrerPolicy: String? = null, // 页面引用来源控制策略，来自 <meta name="referrer">
    val mobileAgent: String? = null, // 移动端跳转地址，通常为移动端页面地址，来自 <meta http-equiv="mobile-agent">
    val keywords: String? = null, // 页面关键词，用于SEO，来自 <meta name="keywords">
    val description: String? = null, // 页面描述，用于SEO，来自 <meta name="description">
    val ogImage: String? = null, // Open Graph 图片，用于社交媒体分享显示的图片地址，来自 <meta property="og:image">
    val canonicalUrl: String? = null, // Canonical 链接，用于指定页面标准地址，避免SEO重复内容，来自 <link rel="canonical">
    val manifestUrl: String? = null, // 网站 manifest 文件路径，定义PWA相关配置，来自 <link rel="manifest">
    val appleTouchIcons: Map<String, String> = emptyMap(), // Apple Touch Icons，适配iOS主屏幕图标，多尺寸时可用尺寸为key，URL为value
    val preconnectUrls: List<String> = emptyList(), // 预连接域名，用于提升首次请求速度，来自 <link rel="preconnect">
    val preloadResources: List<PreloadResource> = emptyList(), // 预加载资源，如js、css、图片资源URL，来自 <link rel="preload">
    val dnsPrefetchUrls: List<String> = emptyList(), // DNS 预取，用于提前解析域名，来自 <link rel="dns-prefetch">
    val styleSheets: List<String> = emptyList(), // 样式表链接，CSS 文件 URL 列表，来自 <link rel="stylesheet">
    val scriptPrefetchUrls: List<String> = emptyList(), // 预获取的脚本文件，来自 <link rel="prefetch" as="script">
    val faviconUrls: List<String> = emptyList(), // 页面图标 favicon 的路径，可能是 .ico 或 png
    val customMeta: Map<String, String> = emptyMap(), // 其他自定义或额外的 meta 信息，如自定义属性或扩展字段
    val customLink: Map<String, String> = emptyMap() // 其他自定义或额外的 link 信息，方便扩展
)

/* 预加载资源对象，包含 URL 及资源类型 */
data class PreloadResource(
    val url: String, // 资源URL
    val asType: String? = null // 资源类型，例如 "script", "style", "image"
)

/* 书签地址 */
data class BookmarkUrl(val urlStrRow: String) {
    var urlRaw: String    // https://tool.chinaz.com/linksTesting/list?url=bilibili.com&type=1
    var urlScheme: String  // https
    var urlHost: String    // tool.chinaz.com
    var urlPath: String    // /linksTesting/list
    var urlQuery: String? = null   // url=bilibili.com&type=1
    var urlRoot: String? = null    // https://tool.chinaz.com
    var urlFull: String? = null    // https://tool.chinaz.com/linksTesting/list

    init {
        var urlStr = urlStrRow // 如果不是http://,或者htts://开始,则手动补全,默认Https
        if (!urlStrRow.matches(Regex("^https?://.*"))) urlStr = "https://$urlStr"
        try {
            val url = URLUtil.toUrlForHttp(urlStr)
            this.urlScheme = url.protocol
            this.urlHost = url.authority
            this.urlQuery = url.query
            this.urlPath = url.path
            this.urlRaw = urlStr
            this.urlRoot = "${url.protocol}://${url.host}"
            this.urlFull = "${url.protocol}://${url.host}${url.path}"
        } catch (e: Exception) {
            e.printStackTrace()
            throw CommonException(ErrorType.E303, "用户添加了一个非法地址:$urlStr")
        }
    }
}
