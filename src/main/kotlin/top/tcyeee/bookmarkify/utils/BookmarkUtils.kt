package top.tcyeee.bookmarkify.utils

import cn.hutool.core.io.FileUtil
import cn.hutool.core.util.StrUtil
import cn.hutool.core.util.URLUtil
import cn.hutool.core.util.XmlUtil
import cn.hutool.http.HtmlUtil
import cn.hutool.http.HttpUtil
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.springframework.web.multipart.MultipartFile
import top.tcyeee.bookmarkify.config.exception.CommonException
import top.tcyeee.bookmarkify.config.log
import top.tcyeee.bookmarkify.entity.dto.BookmarkUrl
import top.tcyeee.bookmarkify.entity.po.Bookmark
import top.tcyeee.bookmarkify.entity.response.BookmarkDetail
import java.io.File
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.util.*

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
     * 获取用户LOGO文件,同时下载到本地
     *
     * @param document 解析后的网页文件
     * @return url的相对路径
     */
    fun getLogoUrl(document: Document, urlPre: String, bookmark: Bookmark): Boolean {
        // 尝试从 httpCommonIcoUrl 下载 logo，如果成功返回 true
        if (downloadLogo(bookmark.httpCommonIcoUrl, urlPre + bookmark.fileName)) return true

        // 获取文档中的 logo URL 下载 logo，如果成功返回 true
        val logoUrl = this.checkLogoUrl(document) ?: return false
        val storeUrl = "${urlPre}/favicon/${bookmark.id}.${FileUtil.extName(logoUrl)}"
        return downloadLogo(logoUrl, storeUrl)
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