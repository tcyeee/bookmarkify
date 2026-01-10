package top.tcyeee.bookmarkify.utils

import cn.hutool.core.util.StrUtil
import cn.hutool.http.HtmlUtil
import com.fasterxml.jackson.annotation.JsonIgnore
import org.springframework.web.multipart.MultipartFile
import top.tcyeee.bookmarkify.config.exception.CommonException
import top.tcyeee.bookmarkify.config.exception.ErrorType
import top.tcyeee.bookmarkify.config.log
import top.tcyeee.bookmarkify.entity.entity.BookmarkEntity
import top.tcyeee.bookmarkify.entity.entity.BookmarkUserLink
import top.tcyeee.bookmarkify.entity.entity.NodeTypeEnum
import top.tcyeee.bookmarkify.entity.entity.UserLayoutNodeEntity
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.util.ArrayDeque

data class ChromeBookmarkRawData(
    val title: String,
    val url: String,
    val createTime: LocalDateTime?,
    val iconBase64: String?,
    val paths: String,
){
    fun pair(uid: String): Pair<UserLayoutNodeEntity, BookmarkUserLink> =
        UserLayoutNodeEntity(uid = uid).let { Pair(it, BookmarkUserLink(uid, it.id, this)) }
}

data class ChromeBookmarkStructure(
    val folderName: String,
    val bookmarks: List<ChromeBookmarkRawData>,
    val childFolder: List<ChromeBookmarkStructure>,
)

data class SystemBookmarkStructure(
    val folderName: String,
    val bookmarks: List<ChromeBookmarkRawData>,

    @JsonIgnore var nodeId: String? = null, // nodeID,仅用临时记录
) {
    val isRoot: Boolean get() = folderName == "ROOT"

    fun initFolder(uid: String) = UserLayoutNodeEntity(type = NodeTypeEnum.BOOKMARK_DIR, uid = uid, name = folderName)
}

/**
 * Chrome 书签解析工具
 */
object ChromeBookmarkParser {

    /**
     * 对外入口：从浏览器导出的 HTML 书签文件中提取结构化数据。
     *
     * 设计目标：
     * - 将“读取文件 / 解析行数据 / 构建树结构”三步解耦，便于单元测试与复用；
     * - 所有错误统一转换为业务异常，避免上层混用异常类型；
     * - 仅做数据清洗与转换，不进行任何持久化或业务校验。
     */
    fun trim(file: MultipartFile): List<SystemBookmarkStructure> =
        // 1.读取并校验原始 HTML 文本
        readHtmlContent(file)
            // 2.解析并直接返回树形结构
            .let { parseRows(it) }
            // 3.将chrome数据结构, 转变为系统数据结构
            .let { transferStructure(it) }

    /**
     * 将chrome数据结构, 转变为系统数据结构
     *
     * chrome数据结构: 无限层级, 无限嵌套
     * system数据结构: 不可嵌套, 文件夹中不可包含文件夹
     *
     * eg:chrome数据结构
     * 文件夹1:
     *  |- 书签1
     *  |- 书签2
     *  |- 文件夹2
     *    |- 书签3
     *    |- 书签4
     *
     * eg: 转化后的system数据结构
     * 文件夹1:
     *  |- 书签1
     *  |- 书签2
     * 文件夹2
     *  |- 书签3
     *  |- 书签4
     */
    fun transferStructure(chromeStructure: ChromeBookmarkStructure): List<SystemBookmarkStructure> {
        val result = mutableListOf<SystemBookmarkStructure>()

        fun traverse(node: ChromeBookmarkStructure) {
            // 将当前节点落地为一个扁平文件夹，名称沿用原目录名
            // 为防止数据丢失，即便是 ROOT 也保留其书签（若存在）
            if (node.bookmarks.isNotEmpty() || node.folderName != "ROOT") {
                result.add(
                    SystemBookmarkStructure(
                        folderName = node.folderName,
                        bookmarks = node.bookmarks,
                    )
                )
            }
            // 递归子目录，按深度优先展开
            node.childFolder.forEach { traverse(it) }
        }

        traverse(chromeStructure)
        return result
    }

    /**
     * 读取上传的 MultipartFile，按 UTF-8 获取完整字符串。
     *
     * 失败时统一抛出 E228，避免泄露底层 IO 异常。
     */
    private fun readHtmlContent(file: MultipartFile): String = runCatching {
        file.inputStream.bufferedReader(StandardCharsets.UTF_8).use { it.readText() }
    }.mapCatching { content ->
        content.takeIf { it.isNotBlank() } ?: throw IllegalArgumentException("文件内容为空")
    }.getOrElse {
        log.error("读取书签文件失败", it)
        throw CommonException(ErrorType.E228)
    }

    /**
     * 包装 parseContent，集中处理解析异常。
     */
    private fun parseRows(content: String): ChromeBookmarkStructure = runCatching {
        parseContent(content)
    }.getOrElse {
        log.error("解析书签内容失败", it)
        throw CommonException(ErrorType.E228)
    }

    /**
     * 将 HTML 内容转换为书签列表。
     *
     * 算法要点:
     * - 使用栈维护目录层级（H3/DL）；
     * - 仅在遇到 <A ...> 节点时落地为结果；
     * - 遇到异常行直接跳过，避免中断整体解析。
     */

    fun parseContent(content: String): ChromeBookmarkStructure {
        val pathStack = ArrayDeque<String>()
        val result = mutableListOf<ChromeBookmarkRawData>()

        content.lineSequence().forEach { rawLine ->
            val line = rawLine.trim()
            if (line.isBlank() || line.startsWith("<!DOCTYPE", true)) return@forEach

            val upper = line.uppercase()
            when {
                // 目录结束：遇到 </DL> 即弹栈
                upper.contains("</DL>") -> if (pathStack.isNotEmpty()) pathStack.removeLast()
                // 目录开始：兼容 <DT><H3 ...> 与裸 <H3 ...>
                upper.contains("<H3") -> extractFolder(line)?.let { pathStack.addLast(it) }
                // 书签行：兼容 <DT><A ...> 与裸 <A ...>
                upper.contains("<A") -> {
                    val anchor = line.substringAfter("<A", missingDelimiterValue = "")
                        .let { if (it.isBlank()) line else "<A$it" }
                    parseBookmark(anchor, pathStack)?.let { result.add(it) }
                }
            }
        }
        return buildTree(result)
    }

    /** 解析目录名称，失败返回 null */
    private fun extractFolder(line: String): String? = runCatching {
        // 典型形态：<DT><H3 ADD_DATE="..." LAST_MODIFIED="...">Folder</H3>
        val regex = Regex("""<H3[^>]*>(.*?)</H3>""", RegexOption.IGNORE_CASE)
        regex.find(line)?.groupValues?.getOrNull(1)?.trim()?.takeIf { it.isNotBlank() }
    }.getOrNull()

    /** 解析单个书签节点 */
    private fun parseBookmark(line: String, pathStack: ArrayDeque<String>): ChromeBookmarkRawData? = runCatching {
        val attributes = extractAttributes(line)
        val title = extractTitle(line) ?: return null
        val url = attributes["HREF"] ?: return null
        val createTime = attributes["ADD_DATE"]?.toLongOrNull()?.let {
            // Chrome 导出为秒级时间戳
            Instant.ofEpochSecond(it).atZone(ZoneOffset.UTC).toLocalDateTime()
        }
        val iconBase64 = normalizeIcon(attributes["ICON"] ?: attributes["ICON_URI"])

        ChromeBookmarkRawData(
            title = title,
            url = url,
            createTime = createTime,
            iconBase64 = iconBase64,
            paths = pathStack.joinToString("/"),
        )
    }.onFailure { log.warn("解析书签节点失败: {}", line) }.getOrNull()

    /** 提取标签属性键值对，全部转为大写键 */
    private fun extractAttributes(line: String): Map<String, String> = Regex("""(\w+)=["']([^"']+)["']""").findAll(line)
        .associate { it.groupValues[1].uppercase() to it.groupValues[2] }

    /** 提取并反转义标题文本 */
    private fun extractTitle(line: String): String? = runCatching {
        val title = line.substringAfter('>').substringBeforeLast("</A>", "").trim()
        if (StrUtil.isBlank(title)) null else HtmlUtil.unescape(title)
    }.getOrNull()

    /** 归一化 ICON 字符串，提取 base64 部分 */
    private fun normalizeIcon(icon: String?): String? {
        icon ?: return null
        // Chrome 导出 ICON 字段通常是 data:image/...;base64,xxxx
        return icon.substringAfter("base64,", icon).takeIf { it.isNotBlank() }
    }

    /**
     * 根据平铺的行数据构建目录树.
     */
    private fun buildTree(rows: List<ChromeBookmarkRawData>): ChromeBookmarkStructure {
        data class Node(
            val name: String,
            val bookmarks: MutableList<ChromeBookmarkRawData> = mutableListOf(),
            val children: MutableMap<String, Node> = mutableMapOf(),
        )

        val root = Node("ROOT")
        rows.forEach { row ->
            val parts = row.paths.takeIf { it.isNotBlank() }?.split("/") ?: emptyList()
            var current = root
            parts.forEach { part ->
                current = current.children.getOrPut(part) { Node(part) }
            }
            current.bookmarks.add(row)
        }

        fun Node.toStructure(): ChromeBookmarkStructure = ChromeBookmarkStructure(
            folderName = name,
            bookmarks = bookmarks.toList(),
            childFolder = children.values.map { it.toStructure() },
        )

        return root.toStructure()
    }
}
