package top.tcyeee.bookmarkify.utils

import cn.hutool.core.util.StrUtil
import cn.hutool.core.util.URLUtil
import cn.hutool.http.HttpUtil
import cn.hutool.json.JSONObject
import cn.hutool.json.JSONUtil
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.slf4j.LoggerFactory
import top.tcyeee.bookmarkify.config.exception.CommonException
import top.tcyeee.bookmarkify.config.exception.ErrorType
import top.tcyeee.bookmarkify.entity.dto.*
import top.tcyeee.bookmarkify.entity.enums.BookmarkLinkType
import java.net.URL

/** 网站信息解析器 负责从 URL 获取 Document 并解析出 WebsiteHeaderInfo */
object WebsiteParser {
    private val log = LoggerFactory.getLogger(WebsiteParser::class.java)

    /** 解析 URL 并返回网站头信息 */
    fun parse(url: String): BookmarkWrapper {
        log.debug("[parse] 开始解析URL: {}", url)
        return urlWrapper(url)
            .also { log.debug("[parse] URL包装完成: urlRoot={}, urlFull={}", it.urlRoot, it.urlFull) }
            .let { this.getDocument(it) }
            .also { log.debug("[parse] 页面爬取完成: baseUri={}, title={}", it.baseUri(), it.title()) }
            .let { this.parseDocument(it) }
            .also { log.debug("[parse] 文档解析完成: title={}, charset={}, antiCrawler={}, manifestUrl={}", it.title, it.charset, it.antiCrawlerDetected, it.manifestUrl) }
            .also { this.fillManifest(it) }
            .also { log.debug("[parse] Manifest填充完成: name={}, iconCount={}", it.manifest?.name, it.manifest?.icons?.size) }
            .also { this.initLogo(it) }
            .also { log.debug("[parse] 图标初始化完成: distinctIconCount={}", it.distinctIcons?.size) }
    }

    // urlToBookmark 已移除：canonical 记录必须先有所属 site 才能建（品牌名/图标/NSFW/域名活性都在
    // 那一层），而这个工具类既拿不到也不该拿 SiteService。建记录统一走
    // IBookmarkService.getOrCreateCanonical，它同时负责 site 的 get-or-create 与并发收敛。

    /**
     * 格式化URL字符串
     *
     * @param urlRowStr 原版URL
     * @return 格式化URL
     */
    fun urlWrapper(urlRowStr: String): BookmarkUrlWrapper {
        log.debug("[urlWrapper] 原始输入: {}", urlRowStr)
        if (urlRowStr.isBlank()) throw CommonException(ErrorType.E305)
        var urlStr = urlRowStr // 如果不是http://,或者htts://开始,则手动补全,默认Https
        if (!urlRowStr.matches(Regex("^https?://.*"))) {
            urlStr = "https://$urlStr"
            log.debug("[urlWrapper] 补全协议头: {}", urlStr)
        }

        val url: URL = runCatching { URLUtil.toUrlForHttp(urlStr) }.getOrElse {
            throw CommonException(ErrorType.E303, "${ErrorType.E303.code()}:${it.message}")
        }
        // canonical 书签按 (host, path, query, 路由型 fragment) 四元组去重
        // （见 BookmarkServiceImpl.getOrCreateByUrl），规则全部收在 UrlCanonicalizer 里。
        //
        // query 必须参与：youtube.com/watch?v=A 与 ?v=B 是两个完全不同的页面，此前 query 被整个
        // 丢掉，两者收敛成同一条记录，抓取目标还退化成了 https://www.youtube.com/watch（不是任何
        // 一个视频）。详见根目录 SITE_LAYERING_DESIGN.md §1。
        val canonical = UrlCanonicalizer.canonicalize(url.path, url.query, url.ref)

        return BookmarkUrlWrapper(
            urlScheme = url.protocol,
            urlHost = url.authority,
            urlQuery = canonical.query,
            urlFragment = canonical.fragment,
            urlPath = canonical.path,
            urlRaw = urlStr,
            urlRoot = "${url.protocol}://${url.host}",
            // 用 authority 而不是 host：带端口的地址（localhost:3000）拼掉端口就指向了另一个服务
            urlFull = canonical.rawUrl(url.protocol, url.authority),
        ).also {
            log.debug(
                "[urlWrapper] 解析结果: scheme={}, host={}, path={}, query={}, fragment={}",
                it.urlScheme, it.urlHost, it.urlPath, it.urlQuery, it.urlFragment,
            )
        }
    }

    /**
     * 按 host 对书签进行分类：域名 / 本地(localhost、127.0.0.1) / IP / 其他。
     * host 可能带端口（如 "localhost:3000"、"192.168.1.5:8080"）或 IPv6 字面量（如 "[::1]:8080"），
     * 先剥离端口/中括号再判断。
     */
    fun classifyLinkType(host: String): BookmarkLinkType {
        val hostname = extractHostname(host)
        return when {
            hostname.isBlank() -> BookmarkLinkType.OTHER
            hostname.equals("localhost", ignoreCase = true) || hostname == "127.0.0.1" || hostname == "::1" -> BookmarkLinkType.LOCAL
            isIpAddress(hostname) -> BookmarkLinkType.IP
            hostname.contains(".") -> BookmarkLinkType.DOMAIN
            else -> BookmarkLinkType.OTHER
        }
    }

    private fun extractHostname(host: String): String {
        if (host.startsWith("[")) {
            val end = host.indexOf(']')
            if (end > 0) return host.substring(1, end)
        }
        return host.substringBeforeLast(":")
    }

    private fun isIpAddress(host: String): Boolean {
        val ipv4 = Regex("^(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})$")
        val match = ipv4.matchEntire(host)
        if (match != null) return match.groupValues.drop(1).all { (it.toIntOrNull() ?: -1) in 0..255 }
        // 粗略判断 IPv6：包含冒号，且仅由十六进制字符/冒号组成
        return host.contains(":") && host.all { it.isDigit() || it in 'a'..'f' || it in 'A'..'F' || it == ':' }
    }

    /**
     * 爬取网站信息
     * @param urlWrapper 网站url包装类
     */
    private fun getDocument(urlWrapper: BookmarkUrlWrapper): Document {
        log.debug("[getDocument] 开始爬取: url={}", urlWrapper.urlFull)
        return runCatching {
            Jsoup.connect(urlWrapper.urlFull).timeout(10000).ignoreHttpErrors(false).get()
        }.onSuccess {
            log.debug("[getDocument] 爬取成功: title={}, charset={}, elementCount={}", it.title(), it.charset(), it.allElements.size)
        }.getOrElse {
            log.debug("[getDocument] 爬取失败: url={}, error={}", urlWrapper.urlFull, it.message)
            throw CommonException(ErrorType.E304, it.message ?: it.toString())
        }
    }

    /**
     * 获网站图片
     * @param info 网站信息
     * @return 网站所有不同格式和大小的图标文件, 包含favicon.ico
     */
    private fun initLogo(info: BookmarkWrapper) {
        log.debug("[initLogo] 开始整理图标资源: baseUrl={}", info.baseUrl)

        fun String.normalize(): String? {
            if (this.isBlank()) return null
            return runCatching {
                val fullUrl = if (this.startsWith("http")) this
                else info.baseUrl?.let { URLUtil.completeUrl(it, this) }
                fullUrl?.substringBefore('?')
            }.getOrNull()
        }

        val icons = buildList {
            // 1. Manifest Icons
            val manifestIcons = info.manifest?.icons?.mapNotNull { icon -> icon.src?.normalize()?.let { icon.copy(src = it) } } ?: emptyList()
            log.debug("[initLogo] Manifest图标数={}", manifestIcons.size)
            addAll(manifestIcons)

            // 2. Apple Touch Icons
            val appleIcons = info.appleTouchIcons.mapNotNull { (size, url) ->
                url.normalize()?.let { ManifestIcon(src = it, sizes = size, type = "image/png") }
            }
            log.debug("[initLogo] Apple Touch图标数={}", appleIcons.size)
            addAll(appleIcons)

            // 3. Favicons
            val favicons = info.faviconUrls.mapNotNull { url ->
                url.normalize()?.let { normUrl ->
                    val type = when {
                        normUrl.endsWith(".ico", true) -> "image/x-icon"
                        normUrl.endsWith(".png", true) -> "image/png"
                        normUrl.endsWith(".svg", true) -> "image/svg+xml"
                        normUrl.endsWith(".jpg", true) || normUrl.endsWith(".jpeg", true) -> "image/jpeg"
                        else -> null
                    }
                    ManifestIcon(src = normUrl, sizes = "16x16", type = type)
                }
            }
            log.debug("[initLogo] Favicon图标数={}", favicons.size)
            addAll(favicons)

            // 4. OG Image
            info.ogImage?.normalize()?.let {
                log.debug("[initLogo] 添加OG图片: {}", it)
                add(ManifestIcon(src = it, sizes = "og", type = null))
            }
        }

        // Deduplicate
        val distinctIcons = icons.distinctBy { it.src }
        log.debug("[initLogo] 去重后图标总数: {} -> {}", icons.size, distinctIcons.size)

        // Update manifest
        info.manifest = (info.manifest ?: WebManifest()).copy(icons = distinctIcons)
        info.distinctIcons = distinctIcons
    }

    /** 从 Document 解析 WebsiteHeaderInfo 将原 DTO 中的构造函数逻辑迁移至此 */
    private fun parseDocument(document: Document): BookmarkWrapper {
        log.debug("[parseDocument] 开始解析文档: baseUri={}", document.baseUri())
        val info = BookmarkWrapper()
        info.baseUrl = document.baseUri()
        info.title = document.title()
        info.charset = document.charset().name()
        info.antiCrawlerDetected = detectAntiCrawler(document)
        info.keywords = getMetaContent(document, "name", "keywords")
        info.description = getMetaContent(document, "name", "description")
        info.viewport = getMetaContent(document, "name", "viewport")
        info.renderer = getMetaContent(document, "name", "renderer")
        info.copyright = getMetaContent(document, "name", "copyright")
        info.referrerPolicy = getMetaContent(document, "name", "referrer")
        info.mobileAgent = getMetaContent(document, "http-equiv", "mobile-agent")
        info.xUaCompatible = getMetaContent(document, "http-equiv", "X-UA-Compatible")
        info.ogImage = getMetaContent(document, "property", "og:image")
        info.canonicalUrl = getLinkHref(document, "canonical")
        info.manifestUrl = getLinkHref(document, "manifest")
        info.preconnectUrls = getLinkHrefs(document, "preconnect")
        info.dnsPrefetchUrls = getLinkHrefs(document, "dns-prefetch")
        info.styleSheets = getLinkHrefs(document, "stylesheet")
        info.scriptPrefetchUrls =
            document.select("link[rel=prefetch][as=script]").map { it.attr("abs:href") }.filter { it.isNotBlank() }
        info.faviconUrls = document.select("link[rel~=(?i)^(shortcut|icon|shortcut icon)$]").map { it.attr("abs:href") }
            .filter { it.isNotBlank() }.distinct()
        info.appleTouchIcons = document.select("link[rel=apple-touch-icon]").associate {
            val sizes = it.attr("sizes")
            (sizes.ifBlank { "default" }) to it.attr("abs:href")
        }
        info.preloadResources =
            document.select("link[rel=preload]").map { PreloadResource(it.attr("abs:href"), it.attr("as")) }
                .filter { it.url.isNotBlank() }
        val standardMetaNames = setOf(
            "viewport",
            "renderer",
            "copyright",
            "referrer",
            "keywords",
            "description",
            "application-name",
            "author",
            "generator"
        )
        info.customMeta = document.select("meta[name]").toList().filter {
            !standardMetaNames.contains(it.attr("name").lowercase()) && it.attr("content").isNotBlank()
        }.associate { it.attr("name") to it.attr("content") }
        // Custom Link (Exclude known rels)
        val standardLinkRels = setOf(
            "canonical",
            "manifest",
            "preconnect",
            "dns-prefetch",
            "shortcut icon",
            "stylesheet",
            "preload",
            "prefetch",
            "icon",
            "apple-touch-icon"
        )
        info.customLink = document.select("link[rel]").toList().filter {
            !standardLinkRels.contains(it.attr("rel").lowercase()) && it.attr("href").isNotBlank()
        }.associate { it.attr("rel") to it.attr("abs:href") }

        log.debug(
            "[parseDocument] 解析完成: title={}, description={}, ogImage={}, faviconCount={}, appleTouchCount={}, antiCrawler={}",
            info.title, info.description?.take(50), info.ogImage, info.faviconUrls.size, info.appleTouchIcons.size, info.antiCrawlerDetected
        )
        return info
    }

    private fun fillManifest(info: BookmarkWrapper) {
        if (StrUtil.isBlank(info.manifestUrl)) {
            log.debug("[fillManifest] 无manifestUrl, 跳过")
            return
        }
        log.debug("[fillManifest] 开始拉取Manifest: {}", info.manifestUrl)

        fetchManifest(info.manifestUrl!!)?.let { json ->
            log.debug("[fillManifest] Manifest拉取成功, jsonLength={}", json.length)
            info.manifest = runCatching { parseManifestJson(json) }.getOrElse {
                it.printStackTrace()
                throw CommonException(ErrorType.E222, "Failed to parse manifest from ${info.manifestUrl}, $it")
            }
        } ?: log.debug("[fillManifest] Manifest拉取返回null: {}", info.manifestUrl)

        // 整理manifest中的信息
        if (info.manifest != null) {
            info.name = info.manifest?.name
            if (StrUtil.isBlank(info.description)) info.description = info.manifest?.description
            log.debug("[fillManifest] Manifest信息填充: name={}, description={}, iconCount={}", info.name, info.description?.take(50), info.manifest?.icons?.size)
        }
    }

    private fun detectAntiCrawler(doc: Document): Boolean {
        val title = doc.title().lowercase()
        val text = doc.text().lowercase()
        log.debug("[detectAntiCrawler] 检测反爬: title={}", title.take(80))

        // 1. Common WAF Titles
        val wafTitles = listOf(
            "just a moment...",
            "attention required",
            "security check",
            "ddos-guard",
            "bitmitigate",
            "shieldsquare",
            "human verification"
        )
        if (wafTitles.any { title.contains(it) }) {
            log.debug("[detectAntiCrawler] 命中WAF标题, 判定为反爬")
            return true
        }

        // 2. EdgeOne / Generic JS Challenge (Short body, script only, no visible content)
        if (title.isBlank() && doc.body().text().isBlank() && doc.select("script").isNotEmpty()) {
            val scriptContent = doc.select("script").html()
            if (scriptContent.contains("document.cookie") || scriptContent.contains("location.href")) {
                log.debug("[detectAntiCrawler] 命中JS Challenge特征, 判定为反爬")
                return true
            }
        }

        // 3. Cloudflare specific text
        if (text.contains("please enable cookies") && text.contains("security by cloudflare")) {
            log.debug("[detectAntiCrawler] 命中Cloudflare特征, 判定为反爬")
            return true
        }

        log.debug("[detectAntiCrawler] 未检测到反爬特征")
        return false
    }

    private fun getMetaContent(doc: Document, attr: String, value: String): String? =
        doc.select("meta[$attr=$value]").attr("content").ifBlank { null }

    private fun getLinkHref(doc: Document, rel: String): String? =
        doc.select("link[rel=$rel]").attr("abs:href").ifBlank { null }

    private fun getLinkHrefs(doc: Document, rel: String): List<String> =
        doc.select("link[rel=$rel]").map { it.attr("abs:href") }.filter { it.isNotBlank() }

    private fun fetchManifest(manifestUrl: String): String? {
        log.debug("[fetchManifest] 请求Manifest: {}", manifestUrl)
        return runCatching {
            HttpUtil.createGet(manifestUrl).header(
                "User-Agent",
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/91.0.4472.124 Safari/537.36"
            ).execute().body()
        }.onSuccess {
            log.debug("[fetchManifest] 请求成功, bodyLength={}", it?.length)
        }.onFailure {
            log.debug("[fetchManifest] 请求失败: {}", it.message)
        }.getOrNull()
    }

    private fun parseManifestJson(json: String): WebManifest {
        log.debug("[parseManifestJson] 开始解析ManifestJSON, length={}", json.length)
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
            } ?: emptyList()
        ).also { log.debug("[parseManifestJson] 解析完成: name={}, shortName={}, iconCount={}", it.name, it.shortName, it.icons?.size) }
    }
}
