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
        val file = MultipartFileToFile(multipartFile)
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
    private fun MultipartFileToFile(multiFile: MultipartFile): File {
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
     * @param document 原始文件
     * @return url的相对路径
     */
    fun getLogoUrl(document: Document, urlPre: String, bookmark: Bookmark): File? {
        // 使用常规URL拼接方式获取
        val onlineUrl = java.lang.String.format("%s://%s/favicon.ico", bookmark.urlScheme, bookmark.urlHost)
        val tmpFileName = java.lang.String.format("/favicon/%s.%s", bookmark.id, FileUtil.extName(onlineUrl))
        val downloaded = downloadLogo(onlineUrl, urlPre + tmpFileName)
        if (downloaded != null) return downloaded

        // 在文档中获取特殊地址进行下载
        var logoUrl = getLogoUrlFromDocument(document)

        // 如果是相对路径,则修改
        val relative = StrUtil.startWithAny(logoUrl, ".", "/")
        if (relative) logoUrl = bookmark.fullUrl() + logoUrl

        // 对URL进行清洗(去除后面可能携带的参数)
        val urlForHttp = URLUtil.toUrlForHttp(logoUrl)
        logoUrl = urlForHttp.host + urlForHttp.path

        // 下载书签图标到本地
        val fileName = java.lang.String.format("/favicon/%s.%s", bookmark.id, FileUtil.extName(logoUrl))
        val storeUrl = urlPre + fileName
        val file = downloadLogo(logoUrl, storeUrl)

        log.info("[CHECK] 获取到了书签LOGO:{}", storeUrl)
        return file
    }

    /**
     * 将LOGO下载到本地
     *
     * @param logoUrl  LOGO的线上地址
     * @param storeUrl 存储地址
     */
    private fun downloadLogo(logoUrl: String?, storeUrl: String): File? {
        try {
            log.info("[CHECK] 书签图标下载路径为:{}", logoUrl)
            return HttpUtil.downloadFileFromUrl(logoUrl, File(storeUrl))
        } catch (e: Exception) {
            log.warn("[CHECK] 书签LOGO下载失败,请检查!")
        }
        return null
    }

    /**
     * 从节点中解析图标LOGO
     *
     * @param document 网站解析后信息
     * @return 可能为绝对路径, 可能为相对路径, 可能为空
     */
    private fun getLogoUrlFromDocument(document: Document): String? {
        val linkTags = document.select("link")
        for (link in linkTags) {
            val relList = arrayOf("icon", "shortcut icon")
            val isInArray = listOf(*relList).contains(link.attr("rel"))
            if (isInArray) {
                val url = link.attr("href")
                log.info("[CHECK] 书签图标原始路径为:{}", url)
                return url
            }
        }
        return null
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
        var document: Document? = null
        try {
            document = Jsoup.connect(url).timeout(10000).get()
        } catch (e: IOException) {
            log.info("[CHECK] 该无法正常解析,标记此网站为离线:{}", url)
        }
        if (document == null) log.info("[CHECK] 书签解析失败,获取到页面数据为空...")
        return document
    }
}