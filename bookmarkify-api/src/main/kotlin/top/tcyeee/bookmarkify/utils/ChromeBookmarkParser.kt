package top.tcyeee.bookmarkify.utils

import cn.hutool.core.util.StrUtil
import org.springframework.web.multipart.MultipartFile
import top.tcyeee.bookmarkify.config.log
import java.nio.charset.StandardCharsets
import java.util.ArrayDeque

/**
 * Chrome 书签解析工具
 *
 * 仅负责从导出的 HTML 中提取纯数据: 目录结构、创建时间、名称、图标(base64)和 URL.
 */
object ChromeBookmarkParser {

    data class ChromeBookmark(
        val title: String,
        val url: String,
        val createTime: Long?,
        val iconBase64: String?,
        val paths: List<String>,
    )

    fun parse(file: MultipartFile): List<ChromeBookmark> = runCatching {
        file.inputStream.bufferedReader(StandardCharsets.UTF_8).use { it.readText() }
    }.mapCatching { content ->
        parseContent(content)
    }.getOrElse {
        log.error("读取 Chrome 书签文件失败", it)
        emptyList()
    }

    private fun parseContent(content: String): List<ChromeBookmark> {
        val pathStack = ArrayDeque<String>()
        val result = mutableListOf<ChromeBookmark>()

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

    private fun extractFolder(line: String): String? = runCatching {
        // <DT><H3 ADD_DATE="..." LAST_MODIFIED="...">Folder</H3>
        line.substringAfter('>').substringBefore("</H3>").trim().takeIf { it.isNotBlank() }
    }.getOrNull()

    private fun parseBookmark(line: String, pathStack: ArrayDeque<String>): ChromeBookmark? = runCatching {
        val attributes = extractAttributes(line)
        val title = extractTitle(line) ?: return null
        val url = attributes["HREF"] ?: return null
        val createTime = attributes["ADD_DATE"]?.toLongOrNull()
        val iconBase64 = normalizeIcon(attributes["ICON"] ?: attributes["ICON_URI"])

        ChromeBookmark(
            title = title,
            url = url,
            createTime = createTime,
            iconBase64 = iconBase64,
            paths = pathStack.toList(),
        )
    }.onFailure { log.warn("解析书签节点失败: {}", line) }.getOrNull()

    private fun extractAttributes(line: String): Map<String, String> {
        val regex = Regex("""(\w+)=["']([^"']+)["']""")
        return regex.findAll(line).associate { it.groupValues[1].uppercase() to it.groupValues[2] }
    }

    private fun extractTitle(line: String): String? = runCatching {
        val title = line.substringAfter('>').substringBeforeLast("</A>", "").trim()
        if (StrUtil.isBlank(title)) null else StrUtil.unescapeHtml4(title)
    }.getOrNull()

    private fun normalizeIcon(icon: String?): String? {
        icon ?: return null
        // Chrome 导出 ICON 字段通常是 data:image/...;base64,xxxx
        return icon.substringAfter("base64,", icon).takeIf { it.isNotBlank() }
    }
}
