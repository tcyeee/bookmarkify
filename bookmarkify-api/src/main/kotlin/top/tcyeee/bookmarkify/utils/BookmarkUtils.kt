package top.tcyeee.bookmarkify.utils

import cn.hutool.core.io.FileUtil
import cn.hutool.core.util.StrUtil
import cn.hutool.core.util.URLUtil
import cn.hutool.core.util.XmlUtil
import cn.hutool.http.HtmlUtil
import cn.hutool.http.HttpUtil
import jdk.jfr.Description
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.springframework.web.multipart.MultipartFile
import top.tcyeee.bookmarkify.config.exception.CommonException
import top.tcyeee.bookmarkify.config.log
import top.tcyeee.bookmarkify.entity.BookmarkDetail
import top.tcyeee.bookmarkify.entity.dto.BookmarkUrl
import top.tcyeee.bookmarkify.entity.entity.Bookmark
import java.io.File
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.util.*

/**
 * 表示网站 <head> 中常见信息的实体类
 */
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

/**
 * 预加载资源对象，包含 URL 及资源类型
 */
data class PreloadResource(
    val url: String, // 资源URL
    val asType: String? = null // 资源类型，例如 "script", "style", "image"
)

fun parse(url: String) {

}


/**
 * @author tcyeee
 * @date 3/10/24 17:32
 */
object BookmarkUtils {
    /**
     * 处理Chrome导出的书签
     *
     * @param multipartFile 导出的文件内容
     * @return info
     */
    fun initChromeBookmark(multipartFile: MultipartFile): List<BookmarkDetail> {
        val file = mulFileToFile(multipartFile)
        var content = FileUtil.readString(file, StandardCharsets.UTF_8)
        content = HtmlUtil.removeHtmlTag(content, "META", "TITLE", "!DOCTYPE", "!--", "H1")
        content = HtmlUtil.removeHtmlAttr(content, "PERSONAL_TOOLBAR_FOLDER")
        content = content.replace("(?m)^\\s*\\n".toRegex(), "")
        content = HtmlUtil.unwrapHtmlTag(content, "p", "DT")

        val result: MutableList<BookmarkDetail> = ArrayList<BookmarkDetail>()
        val paths: MutableList<String> = ArrayList()
        val scanner = Scanner(content)
        while (scanner.hasNextLine()) {
            val line = StrUtil.trim(scanner.nextLine())
            if (line.contains("<DL>")) continue
            if (line.contains("<H3")) {
                val element = XmlUtil.readXML(line).documentElement
                val tagName = element.textContent
                paths.add(tagName)
                continue
            }
            if (line.contains("</DL>")) {
                if (paths.isEmpty()) continue
                paths.removeLast()
                continue
            }
            if (line.startsWith("<A")) {
                val bookmarkDetail: BookmarkDetail = lineInitForA(line, paths) ?: continue
                result.add(bookmarkDetail)
            }
        }
        scanner.close()
        return result
    }


    /**
     * 对A标签进行单独数据清洗
     *
     * @param line A标签数据
     * @return bookmark
     */
    private fun lineInitForA(line: String, paths: List<String>): BookmarkDetail? {
        var line2 = line
        val url: String
        val addDate: String
        val name: String
        try {
            line2 = line2.replace("&".toRegex(), "%26")
            val element = XmlUtil.readXML(line2).documentElement
            url = element.getAttribute("HREF")
            addDate = element.getAttribute("ADD_DATE")
            name = element.textContent
        } catch (e: Exception) {
            log.error("A标签解析出错!,目标数据:{}", line2)
            return null
        }

        val bookmarkUrlInfo: BookmarkUrl
        try {
            bookmarkUrlInfo = BookmarkUrl(url)
        } catch (e: CommonException) {
            return null
        }
        return BookmarkDetail(paths, bookmarkUrlInfo, addDate, name)
    }

    /**
     * 文件转换,文件名,后缀等信息将丢弃
     *
     * @param multiFile 源文件
     * @return file
     */
    private fun mulFileToFile(multiFile: MultipartFile): File {
        try {
            val tempFile = FileUtil.createTempFile()
            multiFile.transferTo(tempFile)
            return tempFile
        } catch (e: Exception) {
            throw RuntimeException(e)
        }
    }

    /**
     * 获取目标网站LOGO,同时下载到本地
     *
     * @param document 解析后的网页文件
     * @return url的存储地址
     */
    fun getLogoUrl(document: Document, urlPre: String, bookmark: Bookmark): String? {
        // 尝试从 httpCommonIcoUrl 下载 logo，如果成功返回 true
        val rawIconUrl = urlPre + bookmark.defaultIconUrl
        if (downloadLogo(bookmark.httpCommonIcoUrl, rawIconUrl)) return bookmark.defaultIconUrl

        // 获取文档中的 logo URL 下载 logo，如果成功返回 true
        val logoUrl = this.checkLogoUrl(document) ?: return null
        val storeUrl = "/favicon/${bookmark.id}.${FileUtil.extName(logoUrl)}"
        if (downloadLogo(logoUrl, urlPre + storeUrl)) return storeUrl

        return null
    }

    /**
     * 将LOGO下载到本地
     *
     * @param logoUrl  LOGO的线上地址
     * @param storeUrl 存储地址
     */
    private fun downloadLogo(logoUrl: String, storeUrl: String): Boolean =
        runCatching {
            HttpUtil.downloadFileFromUrl(logoUrl, File(storeUrl))
            log.info("[CHECK] 获取到了书签LOGO:{}", storeUrl)
        }.isSuccess


    /**
     * 从节点中解析图标LOGO
     *
     * @param document 网站解析后信息
     * @return 可能为绝对路径, 可能为相对路径, 可能为空
     */
    private fun checkLogoUrl(document: Document): String? {
        // 在标签中找到icon标签的href属性
        val url = document.select("link")
            .firstOrNull { listOf("icon", "shortcut icon").contains(it.attr("rel")) }
            ?.attr("href")
            ?: return null

        // 这里可能是相对路径,遇到了再说
        // 对URL进行清洗(去除后面可能携带的参数)
        val tmpUrl = URLUtil.toUrlForHttp(url)
        return "${tmpUrl.protocol}://${tmpUrl.host}${tmpUrl.path}"
    }

    /**
     * 获取页面标题
     *
     * @param document 网站解析后信息
     * @return 网站标题
     */
    fun getTitle(document: Document?): String? {
        if (document == null) {
            log.warn("[CHECK] 书签对应页面为空,无法获取标题!")
            return null
        }
        if (StrUtil.isBlank(document.title())) {
            log.warn("[CHECK] 书签对应页面标题为空!")
            return null
        }

        log.info("[CHECK] 解析到了书签标题:{}", document.title())
        return document.title()
    }

    /**
     * 获取页面描述
     *
     * @param document 网站解析后信息
     * @return 网站描述
     */
    fun getDescription(document: Document?): String? {
        if (document == null) {
            log.warn("[CHECK] 书签对应页面为空,无法获取描述!")
            return null
        }

        val metaTags = document.select("meta[name=description]")
        if (metaTags.isEmpty()) {
            log.warn("[CHECK] 书签对应描述为空!")
            return null
        }

        val content = Objects.requireNonNull(metaTags.first())?.attr("content")
        log.info("[CHECK] 获取到了书签描述:{}", content)
        return content
    }

    /**
     * 从地址中解析出网站数据
     *
     * @param url 地址
     * @return 网站数据
     */
    fun getDocument(url: String): Document? {
        return try {
            Jsoup.connect(url).timeout(10000).get()
        } catch (e: IOException) {
            log.info("[CHECK] 该网址无法正常解析, 标记此网站为离线: {}", url)
            null
        }
    }
}