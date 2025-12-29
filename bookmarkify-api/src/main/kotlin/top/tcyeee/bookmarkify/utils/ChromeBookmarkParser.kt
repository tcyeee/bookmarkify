import cn.hutool.core.util.IdUtil
import org.springframework.web.multipart.MultipartFile
import top.tcyeee.bookmarkify.config.log
import top.tcyeee.bookmarkify.entity.entity.Bookmark
import top.tcyeee.bookmarkify.entity.entity.BookmarkUserLink
import top.tcyeee.bookmarkify.entity.entity.HomeItem
import java.util.function.Consumer
import java.util.stream.Collectors

//package top.tcyeee.bookmarkify.utils
//
//import cn.hutool.core.io.FileUtil
//import cn.hutool.core.util.StrUtil
//import cn.hutool.core.util.XmlUtil
//import cn.hutool.http.HtmlUtil
//import java.io.File
//import java.nio.charset.StandardCharsets
//import java.util.*
//import org.springframework.web.multipart.MultipartFile
//import top.tcyeee.bookmarkify.config.log
//import top.tcyeee.bookmarkify.entity.BookmarkDetail
//
///** Chrome书签解析工具类 */
//object ChromeBookmarkParser {
//
//    /**
//     * 处理Chrome导出的书签
//     *
//     * @param multipartFile 导出的文件内容
//     * @return info
//     */
//    fun parse(multipartFile: MultipartFile): List<BookmarkDetail> {
//        val file = mulFileToFile(multipartFile)
//        var content = FileUtil.readString(file, StandardCharsets.UTF_8)
//        content = HtmlUtil.removeHtmlTag(content, "META", "TITLE", "!DOCTYPE", "!--", "H1")
//        content = HtmlUtil.removeHtmlAttr(content, "PERSONAL_TOOLBAR_FOLDER")
//        content = content.replace("(?m)^\\s*\\n".toRegex(), "")
//        content = HtmlUtil.unwrapHtmlTag(content, "p", "DT")
//
//        val result: MutableList<BookmarkDetail> = ArrayList()
//        val paths: MutableList<String> = ArrayList()
//        val scanner = Scanner(content)
//        while (scanner.hasNextLine()) {
//            val line = StrUtil.trim(scanner.nextLine())
//            if (line.contains("<DL>")) continue
//            if (line.contains("<H3")) {
//                val element = XmlUtil.readXML(line).documentElement
//                val tagName = element.textContent
//                paths.add(tagName)
//                continue
//            }
//            if (line.contains("</DL>")) {
//                if (paths.isEmpty()) continue
//                paths.removeLast()
//                continue
//            }
//            if (line.startsWith("<A")) {
//                val bookmarkDetail: BookmarkDetail? = lineInitForA(line, paths)
//                if (bookmarkDetail != null) {
//                    result.add(bookmarkDetail)
//                }
//            }
//        }
//        scanner.close()
//        return result
//    }
//
//    /**
//     * 对A标签进行单独数据清洗
//     *
//     * @param line A标签数据
//     * @return bookmark
//     */
//    private fun lineInitForA(line: String, paths: List<String>): BookmarkDetail? {
//        var line2 = line
//        val url: String
//        val addDate: String
//        val name: String
//        try {
//            line2 = line2.replace("&".toRegex(), "%26")
//            val element = XmlUtil.readXML(line2).documentElement
//            url = element.getAttribute("HREF")
//            addDate = element.getAttribute("ADD_DATE")
//            name = element.textContent
//        } catch (_: Exception) {
//            log.error("A标签解析出错!,目标数据:{}", line2)
//            return null
//        }
//
//        return BookmarkDetail(paths, WebsiteParser.parseUrl(url), addDate, name)
//    }
//
//    /**
//     * 文件转换,文件名,后缀等信息将丢弃
//     *
//     * @param multiFile 源文件
//     * @return file
//     */
//    private fun mulFileToFile(multiFile: MultipartFile): File {
//        try {
//            val tempFile = FileUtil.createTempFile()
//            multiFile.transferTo(tempFile)
//            return tempFile
//        } catch (e: Exception) {
//            throw RuntimeException(e)
//        }
//    }
//}

//
//
///**
// * 整理上传的全部书签
// *
// * @param file 书签文件(chrome)
// * @return 整理好的书签文件
// */
//private fun updateBookmark(file: MultipartFile, uid: String): List<BookmarkDetail> {
//    val allBookmarkDetail: List<BookmarkDetail> = ChromeBookmarkParser.parse(file)
//
//    // 清洗拿到去重后的全局Bookmark,包含了原有的和新增的  key:host value:BookmarkDetail
//    val allBookmark: MutableMap<String, Bookmark> = HashMap()
//    allBookmarkDetail.forEach(
//        Consumer { item: BookmarkDetail -> allBookmark[item.url.urlHost] = item.bookmark })
//
//    // 找到数据库已经有的,移出掉,只留下所有需要新增的数据
//    val hasSave: List<Bookmark> = bookmarkService.ktQuery().`in`(Bookmark::urlHost, allBookmark.keys).list()
//    val hasSaveHost = hasSave.stream().map<Any>(Bookmark::urlHost).collect(Collectors.toSet())
//    allBookmark.keys.removeIf { o: String -> hasSaveHost.contains(o) }
//
//    // 存入所有书签
//    val collect: MutableList<Bookmark> = ArrayList(allBookmark.values)
//    collect.forEach(Consumer { bookmark: Bookmark -> bookmark.id = IdUtil.fastUUID() })
//    bookmarkService.saveBatch(collect)
//
//    // 新增书签组合成可供查询的Bookmark数据库key:host,将新增的ID返回到原始数据库
//    collect.addAll(hasSave)
//    val database: Map<String, Bookmark> = collect.associateBy { it.urlHost }
//
//    allBookmarkDetail.forEach(
//        Consumer { item: BookmarkDetail ->
//            val id: String = database[item.bookmark.urlHost]?.id.toString()
//            item.bookmark.id = id
//        })
//
//    // 存入用户-书签关联
//    val links: MutableList<BookmarkUserLink> = ArrayList()
//    allBookmarkDetail.forEach(
//        Consumer { item: BookmarkDetail ->
//            val userLink = BookmarkUserLink(item, uid)
//            item.bookmarkUserLinkId = userLink.id
//            links.add(userLink)
//        })
//    bookmarkUserLinkService.saveBatch(links)
//
//    // 存入桌面布局
//    val homeItems: List<HomeItem> = this.changeToItem(allBookmarkDetail, uid)
//    homeItemService.saveBatch(homeItems)
//
//    log.info("用户{}书签导入完成,共计导入{}条", uid, allBookmarkDetail.size)
//    return allBookmarkDetail
//}

///**
// * 整理出所有的桌面Items
// *
// * @param allBookmark 待整理
// * @return 整理完成
// */
//private fun changeToItem(allBookmark: List<BookmarkDetail>, uid: String): List<HomeItem> {
//    val dirList: MutableMap<String, MutableList<String>> = HashMap() // 根据最后的目录整理书签, 结构为 目录名:用户-书签-IDS
//    val rootBookmarks: MutableList<HomeItem> = ArrayList() // 根路径书签
//
//    allBookmark.forEach(
//        Consumer { item: BookmarkDetail ->
//            /* 添加书签 */
//            if (item.paths.isEmpty()) {
//                rootBookmarks.add(HomeItem(uid, item.bookmarkUserLinkId))
//            } else {
//                val dirStr: String = item.paths.last()
//                dirList.computeIfAbsent(dirStr) { ArrayList() }.add(item.bookmarkUserLinkId)
//            }
//        })
//
//    // 向目录中存放书签
//    dirList.forEach { (dirStr: String, bookmarkIds: MutableList<String>) ->
//        rootBookmarks.add(HomeItem(dirStr, uid))
//    }
//    return rootBookmarks
//}