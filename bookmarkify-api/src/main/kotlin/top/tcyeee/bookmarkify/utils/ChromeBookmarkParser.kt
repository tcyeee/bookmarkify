package top.tcyeee.bookmarkify.utils

import cn.hutool.core.codec.Base64
import cn.hutool.core.io.FileUtil
import cn.hutool.core.util.StrUtil
import cn.hutool.http.HtmlUtil
import cn.hutool.http.HttpUtil
import com.fasterxml.jackson.annotation.JsonIgnore
import org.springframework.web.multipart.MultipartFile
import top.tcyeee.bookmarkify.config.exception.CommonException
import top.tcyeee.bookmarkify.config.exception.ErrorType
import top.tcyeee.bookmarkify.config.log
import top.tcyeee.bookmarkify.entity.dto.ManifestIcon
import top.tcyeee.bookmarkify.entity.entity.BookmarkUserLink
import top.tcyeee.bookmarkify.entity.entity.NodeTypeEnum
import top.tcyeee.bookmarkify.entity.entity.UserLayoutNodeEntity
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.util.ArrayDeque
import kotlin.collections.isEmpty

data class ChromeBookmarkRawData(
    val title: String,
    val url: String,
    val createTime: LocalDateTime?,
    val iconBase64: String?,
    val paths: String,
) {
    fun pair(uid: String, parentNodeId: String?): Pair<UserLayoutNodeEntity, BookmarkUserLink> =
        UserLayoutNodeEntity(uid = uid, type = NodeTypeEnum.BOOKMARK_LOADING, parentId = parentNodeId)
            .let { Pair(it, BookmarkUserLink(uid, it.id, this)) }
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
)

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
    fun trim(file: MultipartFile): List<SystemBookmarkStructure> {
        log.debug("[trim] 开始解析书签文件: name={}, size={} bytes", file.originalFilename, file.size)
        return readHtmlContent(file)
            .also { log.debug("[trim] HTML内容读取完成, 长度={} chars", it.length) }
            .let { parseRows(it) }
            .also {
                log.debug(
                    "[trim] 树形结构解析完成: folderName={}, bookmarks={}, childFolders={}",
                    it.folderName,
                    it.bookmarks.size,
                    it.childFolder.size
                )
            }
            .let { transferStructure(it) }
            .also { log.debug("[trim] 结构转换完成, 共 {} 个文件夹", it.size) }
    }

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
        log.debug("[transferStructure] 开始转换Chrome结构, 根节点folderName={}", chromeStructure.folderName)
        val result = mutableListOf<SystemBookmarkStructure>()

        fun traverse(node: ChromeBookmarkStructure) {
            log.debug(
                "[transferStructure] 遍历节点: folderName={}, bookmarks={}, childFolders={}",
                node.folderName,
                node.bookmarks.size,
                node.childFolder.size
            )
            // 将当前节点落地为一个扁平文件夹，名称沿用原目录名
            // 为防止数据丢失，即便是 ROOT 也保留其书签（若存在）
            if (node.bookmarks.isNotEmpty() || node.folderName != "ROOT") {
                if (node.bookmarks.isNotEmpty()) {
                    log.debug(
                        "[transferStructure] 落地文件夹: folderName={}, bookmarkCount={}",
                        node.folderName,
                        node.bookmarks.size
                    )
                    result.add(
                        SystemBookmarkStructure(
                            folderName = node.folderName,
                            bookmarks = node.bookmarks,
                        )
                    )
                } else {
                    log.debug("[transferStructure] 跳过空文件夹: folderName={}", node.folderName)
                }
            }
            // 递归子目录，按深度优先展开
            node.childFolder.forEach { traverse(it) }
        }

        traverse(chromeStructure)
        log.debug("[transferStructure] 转换完成, 共生成 {} 个SystemBookmarkStructure", result.size)
        return result
    }

    /**
     * 读取上传的 MultipartFile，按 UTF-8 获取完整字符串。
     *
     * 失败时统一抛出 E228，避免泄露底层 IO 异常。
     */
    private fun readHtmlContent(file: MultipartFile): String {
        log.debug(
            "[readHtmlContent] 开始读取文件: name={}, contentType={}, size={}",
            file.originalFilename,
            file.contentType,
            file.size
        )
        return runCatching {
            file.inputStream.bufferedReader(StandardCharsets.UTF_8).use { it.readText() }
        }.mapCatching { content ->
            content.takeIf { it.isNotBlank() } ?: throw IllegalArgumentException("文件内容为空")
        }.onSuccess {
            log.debug("[readHtmlContent] 文件读取成功, 内容长度={} chars", it.length)
        }.getOrElse {
            log.error("读取书签文件失败", it)
            throw CommonException(ErrorType.E228)
        }
    }

    /**
     * 包装 parseContent，集中处理解析异常。
     */
    private fun parseRows(content: String): ChromeBookmarkStructure {
        log.debug("[parseRows] 开始解析HTML内容, 内容长度={} chars", content.length)
        return runCatching {
            parseContent(content)
        }.onSuccess {
            log.debug(
                "[parseRows] 解析成功: folderName={}, bookmarks={}, childFolders={}",
                it.folderName,
                it.bookmarks.size,
                it.childFolder.size
            )
        }.getOrElse {
            log.error("解析书签内容失败", it)
            throw CommonException(ErrorType.E228)
        }
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
        log.debug("[parseContent] 开始逐行解析HTML, 共 {} 行", content.lines().size)
        val pathStack = ArrayDeque<String>()
        val result = mutableListOf<ChromeBookmarkRawData>()
        var lineCount = 0
        var skippedCount = 0

        content.lineSequence().forEach { rawLine ->
            lineCount++
            val line = rawLine.trim()
            if (line.isBlank() || line.startsWith("<!DOCTYPE", true)) {
                skippedCount++
                return@forEach
            }

            val upper = line.uppercase()
            when {
                // 目录结束：遇到 </DL> 即弹栈
                upper.contains("</DL>") -> {
                    val popped = if (pathStack.isNotEmpty()) pathStack.removeLast() else null
                    log.debug("[parseContent] </DL> 弹栈: popped={}, 当前栈深={}", popped, pathStack.size)
                }
                // 目录开始：兼容 <DT><H3 ...> 与裸 <H3 ...>
                upper.contains("<H3") -> extractFolder(line)?.let {
                    pathStack.addLast(it)
                    log.debug("[parseContent] <H3> 入栈: folder={}, 当前路径={}", it, pathStack.joinToString("/"))
                }
                // 书签行：兼容 <DT><A ...> 与裸 <A ...>
                upper.contains("<A") -> {
                    val anchor = line.substringAfter("<A", missingDelimiterValue = "")
                        .let { if (it.isBlank()) line else "<A$it" }
                    parseBookmark(anchor, pathStack)?.let {
                        result.add(it)
                        log.debug("[parseContent] 书签解析成功: title={}, url={}, path={}", it.title, it.url, it.paths)
                    }
                }
            }
        }
        log.debug(
            "[parseContent] 逐行解析完成: 总行数={}, 跳过={}, 解析到书签={}",
            lineCount,
            skippedCount,
            result.size
        )
        return buildTree(result)
    }

    /** 解析目录名称，失败返回 null */
    private fun extractFolder(line: String): String? {
        log.debug("[extractFolder] 解析文件夹行: {}", line)
        return runCatching {
            // 典型形态：<DT><H3 ADD_DATE="..." LAST_MODIFIED="...">Folder</H3>
            val regex = Regex("""<H3[^>]*>(.*?)</H3>""", RegexOption.IGNORE_CASE)
            regex.find(line)?.groupValues?.getOrNull(1)?.trim()?.takeIf { it.isNotBlank() }
        }.onSuccess { name ->
            if (name != null) log.debug("[extractFolder] 提取文件夹名成功: {}", name)
            else log.debug("[extractFolder] 未匹配到文件夹名, 返回null")
        }.onFailure {
            log.debug("[extractFolder] 提取文件夹名异常, line={}", line)
        }.getOrNull()
    }

    /** 解析单个书签节点 */
    private fun parseBookmark(line: String, pathStack: ArrayDeque<String>): ChromeBookmarkRawData? {
        log.debug("[parseBookmark] 开始解析书签行, 当前路径栈={}", pathStack.joinToString("/"))
        return runCatching {
            val attributes = extractAttributes(line)
            log.debug("[parseBookmark] 提取属性: keys={}", attributes.keys)
            val title = extractTitle(line) ?: run {
                log.debug("[parseBookmark] title为空, 跳过该行")
                return null
            }
            val url = attributes["HREF"] ?: run {
                log.debug("[parseBookmark] HREF缺失, 跳过该行: title={}", title)
                return null
            }
            val createTime = attributes["ADD_DATE"]?.toLongOrNull()?.let {
                // Chrome 导出为秒级时间戳
                Instant.ofEpochSecond(it).atZone(ZoneOffset.UTC).toLocalDateTime()
            }
            log.debug(
                "[parseBookmark] createTime={}, hasIcon={}",
                createTime,
                attributes.containsKey("ICON") || attributes.containsKey("ICON_URI")
            )
            val iconBase64 = normalizeIcon(attributes["ICON"] ?: attributes["ICON_URI"])

            ChromeBookmarkRawData(
                title = title,
                url = url,
                createTime = createTime,
                iconBase64 = iconBase64,
                paths = pathStack.joinToString("/"),
            )
        }.onFailure { log.warn("解析书签节点失败: {}", line) }.getOrNull()
    }

    /** 提取标签属性键值对，全部转为大写键 */
    private fun extractAttributes(line: String): Map<String, String> {
        val attrs = Regex("""(\w+)=["']([^"']+)["']""").findAll(line)
            .associate { it.groupValues[1].uppercase() to it.groupValues[2] }
        log.debug("[extractAttributes] 提取到 {} 个属性: {}", attrs.size, attrs.keys)
        return attrs
    }

    /** 提取并反转义标题文本 */
    private fun extractTitle(line: String): String? {
        return runCatching {
            val raw = line.substringAfter('>').substringBeforeLast("</A>", "").trim()
            val title = if (StrUtil.isBlank(raw)) null else HtmlUtil.unescape(raw)
            log.debug("[extractTitle] 提取标题: raw={}, unescaped={}", raw.take(80), title?.take(80))
            title
        }.onFailure {
            log.debug("[extractTitle] 提取标题异常")
        }.getOrNull()
    }

    /** 归一化 ICON 字符串，提取 base64 部分 */
    private fun normalizeIcon(icon: String?): String? {
        if (icon == null) {
            log.debug("[normalizeIcon] icon为null, 跳过")
            return null
        }
        // Chrome 导出 ICON 字段通常是 data:image/...;base64,xxxx
        val result = icon.substringAfter("base64,", icon).takeIf { it.isNotBlank() }
        log.debug("[normalizeIcon] icon归一化: hasBase64={}, resultLength={}", icon.contains("base64,"), result?.length)
        return result
    }

    /**
     * 根据平铺的行数据构建目录树.
     */
    private fun buildTree(rows: List<ChromeBookmarkRawData>): ChromeBookmarkStructure {
        log.debug("[buildTree] 开始构建目录树, 共 {} 条书签数据", rows.size)
        data class Node(
            val name: String,
            val bookmarks: MutableList<ChromeBookmarkRawData> = mutableListOf(),
            val children: MutableMap<String, Node> = mutableMapOf(),
        )

        val root = Node("ROOT")
        rows.forEach { row ->
            val parts = row.paths.takeIf { it.isNotBlank() }?.split("/") ?: emptyList()
            log.debug("[buildTree] 插入书签: title={}, pathParts={}", row.title, parts)
            var current = root
            parts.forEach { part ->
                val isNew = !current.children.containsKey(part)
                current = current.children.getOrPut(part) { Node(part) }
                if (isNew) log.debug("[buildTree] 创建新目录节点: {}", part)
            }
            current.bookmarks.add(row)
        }

        fun Node.toStructure(): ChromeBookmarkStructure = ChromeBookmarkStructure(
            folderName = name,
            bookmarks = bookmarks.toList(),
            childFolder = children.values.map { it.toStructure() },
        )

        val structure = root.toStructure()
        log.debug(
            "[buildTree] 目录树构建完成: 根节点bookmarks={}, 子目录数={}",
            structure.bookmarks.size,
            structure.childFolder.size
        )
        return structure
    }


    // 找到Base64图标
    fun icoBase64(imgs: List<ManifestIcon>?, rawUrl: String): String? {
        val icon = imgs?.firstOrNull { it.sizes == "16x16" || it.src?.endsWith(".ico", ignoreCase = true) == true }
            ?: ManifestIcon(src = "${rawUrl}/favicon.ico")
        return runCatching {
            HttpUtil.createGet(icon.src).execute().use { resp ->
                val bytes = resp.bodyBytes()
                if (bytes == null || bytes.isEmpty()) return@runCatching null
                val contentType = resp.header("Content-Type")?.substringBefore(";")?.takeIf { it.isNotBlank() }
                    ?: guessImageMime(icon.src!!)
                "data:${contentType};base64,${Base64.encode(bytes)}"
            }
        }.getOrNull()
    }

    private fun guessImageMime(src: String): String {
        val ext = FileUtil.extName(src.substringBefore("?")).lowercase()
        return when (ext) {
            "png" -> "image/png"
            "jpg", "jpeg" -> "image/jpeg"
            "svg" -> "image/svg+xml"
            "ico" -> "image/x-icon"
            "gif" -> "image/gif"
            "webp" -> "image/webp"
            else -> "application/octet-stream"
        }
    }
}
