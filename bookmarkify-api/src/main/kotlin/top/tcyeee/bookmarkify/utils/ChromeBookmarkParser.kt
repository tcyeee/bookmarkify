package top.tcyeee.bookmarkify.utils

import cn.hutool.core.util.StrUtil
import cn.hutool.http.HtmlUtil
import org.springframework.web.multipart.MultipartFile
import top.tcyeee.bookmarkify.config.exception.CommonException
import top.tcyeee.bookmarkify.config.exception.ErrorType
import top.tcyeee.bookmarkify.config.log
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.util.ArrayDeque


data class ChromeBookmarkRowData(
    val title: String,
    val url: String,
    val createTime: LocalDateTime?,
    val iconBase64: String?,
    val paths: String,
)

data class ChromeBookmarkStructure(
    val folderName: String,
    val bookmarks: List<ChromeBookmarkRowData>,
    val childFolder: List<ChromeBookmarkStructure>,
)

/**
 * Chrome 书签解析工具
 */
object ChromeBookmarkParser {

    /**
     * 从 MultipartFile 读取 HTML 并解析为书签列表。
     *
     * 仅负责数据清洗，不做任何持久化或业务校验。
     *
     * @param file Chrome 导出的书签 HTML
     * @return 解析得到的书签数据，若解析失败则返回空列表
     */
    fun trim(file: MultipartFile): ChromeBookmarkStructure {
        val rows = runCatching {
            file.inputStream.bufferedReader(StandardCharsets.UTF_8).use { it.readText() }
        }.mapCatching { content ->
            parseContent(content)
        }.getOrElse {
            it.printStackTrace()
            throw CommonException(ErrorType.E228)
        }
        return buildTree(rows)
    }

    /**
     * 将 HTML 内容转换为书签列表。
     *
     * 算法要点:
     * - 使用栈维护目录层级（H3/DL）；
     * - 仅在遇到 <A ...> 节点时落地为结果；
     * - 遇到异常行直接跳过，避免中断整体解析。
     */

    fun parseContent(content: String): List<ChromeBookmarkRowData> {
        val pathStack = ArrayDeque<String>()
        val result = mutableListOf<ChromeBookmarkRowData>()

        content.lineSequence().forEach { rawLine ->
            val line = rawLine.trim()
            when {
                line.isBlank() || line.startsWith("<!DOCTYPE", true) -> Unit
                line.contains("</DL>", true) -> if (pathStack.isNotEmpty()) pathStack.removeLast()
                line.startsWith("<H3", true) -> extractFolder(line)?.let { pathStack.addLast(it) }
                line.startsWith("<A", true) -> parseBookmark(line, pathStack)?.let { result.add(it) }
            }
        }
        return result
    }

    /** 解析目录名称，失败返回 null */
    private fun extractFolder(line: String): String? = runCatching {
        // <DT><H3 ADD_DATE="..." LAST_MODIFIED="...">Folder</H3>
        line.substringAfter('>').substringBefore("</H3>").trim().takeIf { it.isNotBlank() }
    }.getOrNull()

    /** 解析单个书签节点 */
    private fun parseBookmark(line: String, pathStack: ArrayDeque<String>): ChromeBookmarkRowData? = runCatching {
        val attributes = extractAttributes(line)
        val title = extractTitle(line) ?: return null
        val url = attributes["HREF"] ?: return null
        val createTime = attributes["ADD_DATE"]?.toLongOrNull()?.let {
            // Chrome 导出为秒级时间戳
            Instant.ofEpochSecond(it).atZone(ZoneOffset.UTC).toLocalDateTime()
        }
        val iconBase64 = normalizeIcon(attributes["ICON"] ?: attributes["ICON_URI"])

        ChromeBookmarkRowData(
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
    private fun buildTree(rows: List<ChromeBookmarkRowData>): ChromeBookmarkStructure {
        data class Node(
            val name: String,
            val bookmarks: MutableList<ChromeBookmarkRowData> = mutableListOf(),
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
