package top.tcyeee.bookmarkify.utils

import cn.hutool.core.io.FileUtil
import cn.hutool.core.util.StrUtil
import cn.hutool.core.util.URLUtil
import cn.hutool.core.util.XmlUtil
import cn.hutool.http.HtmlUtil
import cn.hutool.http.HttpUtil
import cn.hutool.json.JSONObject
import cn.hutool.json.JSONUtil
import java.io.File
import java.net.URL
import java.nio.charset.StandardCharsets
import java.util.*
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.springframework.web.multipart.MultipartFile
import top.tcyeee.bookmarkify.config.exception.CommonException
import top.tcyeee.bookmarkify.config.exception.ErrorType
import top.tcyeee.bookmarkify.config.log
import top.tcyeee.bookmarkify.entity.BookmarkDetail
import top.tcyeee.bookmarkify.entity.dto.BookmarkUrl
import top.tcyeee.bookmarkify.entity.dto.ManifestIcon
import top.tcyeee.bookmarkify.entity.dto.WebManifest
import top.tcyeee.bookmarkify.entity.dto.WebsiteHeaderInfo
import top.tcyeee.bookmarkify.entity.entity.Bookmark

/**
 * @author tcyeee
 * @date 3/10/24 17:32
 */
object BookmarkUtils {

    fun parse(url: String): WebsiteHeaderInfo {
        val fullUrl = this.parseUrl(url).urlFull
        val doc = getDocument(fullUrl)
        val info = WebsiteHeaderInfo(doc)
        fillManifest(info)
        return info
    }


    private fun fillManifest(info: WebsiteHeaderInfo) {
        info.manifestUrl?.let { mUrl ->
            fetchManifest(mUrl)?.let { json ->
                try {
                    info.manifest = parseManifestJson(json)
                } catch (e: Exception) {
                    log.error("Failed to parse manifest from $mUrl", e)
                }
            }
        }
    }

    private fun parseManifestJson(json: String): WebManifest {
        val jsonObj = JSONUtil.parseObj(json)
        return WebManifest(
            name = jsonObj.getStr("name"),
            shortName = jsonObj.getStr("short_name") ?: jsonObj.getStr("shortName"),
            description = jsonObj.getStr("description"),
            startUrl = jsonObj.getStr("start_url") ?: jsonObj.getStr("startUrl"),
            display = jsonObj.getStr("display"),
            backgroundColor = jsonObj.getStr("background_color") ?: jsonObj.getStr("backgroundColor"),
            themeColor = jsonObj.getStr("theme_color") ?: jsonObj.getStr("themeColor"),
            icons = jsonObj.getJSONArray("icons")?.mapNotNull {
                if (it is JSONObject) {
                    ManifestIcon(
                        src = it.getStr("src"), sizes = it.getStr("sizes"), type = it.getStr("type")
                    )
                } else null
            } ?: emptyList())
    }

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
        } catch (_: Exception) {
            log.error("A标签解析出错!,目标数据:{}", line2)
            return null
        }

        return BookmarkDetail(paths, parseUrl(url), addDate, name)
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
     * @param logoUrl LOGO的线上地址
     * @param storeUrl 存储地址
     */
    private fun downloadLogo(logoUrl: String, storeUrl: String): Boolean = runCatching {
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
        val url = document.select("link").firstOrNull { listOf("icon", "shortcut icon").contains(it.attr("rel")) }
            ?.attr("href") ?: return null

        // 这里可能是相对路径,遇到了再说
        // 对URL进行清洗(去除后面可能携带的参数)
        val tmpUrl = URLUtil.toUrlForHttp(url)
        return "${tmpUrl.protocol}://${tmpUrl.host}${tmpUrl.path}"
    }

    /**
     * 格式化URL字符串
     *
     * @param urlRowStr 原版URL
     * @return 格式化URL
     */
    fun parseUrl(urlRowStr: String): BookmarkUrl {
        if (urlRowStr.isBlank()) throw CommonException(ErrorType.E305)
        var urlStr = urlRowStr // 如果不是http://,或者htts://开始,则手动补全,默认Https
        if (!urlRowStr.matches(Regex("^https?://.*"))) urlStr = "https://$urlStr"

        val url: URL = runCatching { URLUtil.toUrlForHttp(urlStr) }.getOrElse {
            throw CommonException(ErrorType.E303)
        }
        return BookmarkUrl(
            urlScheme = url.protocol,
            urlHost = url.authority,
            urlQuery = url.query,
            urlPath = url.path,
            urlRaw = urlStr,
            urlRoot = "${url.protocol}://${url.host}",
            urlFull = "${url.protocol}://${url.host}${url.path}",
        )
    }

    /**
     * 从地址中解析出网站数据
     *
     * @param url 地址
     * @return 网站数据
     */
    private fun getDocument(url: String): Document = runCatching {
        Jsoup.connect(url).timeout(10000).ignoreHttpErrors(true) // 允许获取 4xx/5xx 页面，以便后续检测反爬虫特征
            .get()
    }.getOrElse { err ->
        err.printStackTrace()
        throw CommonException(ErrorType.E304, err.message ?: err.toString())
    }

    private fun fetchManifest(manifestUrl: String): String? {
        return runCatching {
            HttpUtil.createGet(manifestUrl).header(
                "User-Agent",
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/91.0.4472.124 Safari/537.36"
            ).execute().body()
        }.getOrNull()
    }
}