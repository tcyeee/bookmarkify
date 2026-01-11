package top.tcyeee.bookmarkify.utils

import cn.hutool.core.util.StrUtil
import cn.hutool.core.util.URLUtil
import cn.hutool.http.HttpUtil
import cn.hutool.json.JSONObject
import cn.hutool.json.JSONUtil
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import top.tcyeee.bookmarkify.config.exception.CommonException
import top.tcyeee.bookmarkify.config.exception.ErrorType
import top.tcyeee.bookmarkify.entity.dto.*
import top.tcyeee.bookmarkify.entity.entity.BookmarkEntity
import java.net.URL

/** 网站信息解析器 负责从 URL 获取 Document 并解析出 WebsiteHeaderInfo */
object WebsiteParser {
    /** 解析 URL 并返回网站头信息 */
    fun parse(url: String): BookmarkWrapper = urlWrapper(url)
        .let { this.getDocument(it) } // 爬取
        .let { this.parseDocument(it) } // 解析基础信息
        .also { this.fillManifest(it) } // 解析Manifest
        .also { this.initLogo(it) } // 解析网站图片(LOGO/OG)

    fun urlToBookmark(urlRowStr: String): BookmarkEntity=
         urlWrapper(urlRowStr).let{ BookmarkEntity(it) }

    /**
     * 格式化URL字符串
     *
     * @param urlRowStr 原版URL
     * @return 格式化URL
     */
    fun urlWrapper(urlRowStr: String): BookmarkUrlWrapper {
        if (urlRowStr.isBlank()) throw CommonException(ErrorType.E305)
        var urlStr = urlRowStr // 如果不是http://,或者htts://开始,则手动补全,默认Https
        if (!urlRowStr.matches(Regex("^https?://.*"))) urlStr = "https://$urlStr"

        val url: URL = runCatching { URLUtil.toUrlForHttp(urlStr) }.getOrElse {
            throw CommonException(ErrorType.E303, "${ErrorType.E303.code()}:${it.message}")
        }

        return BookmarkUrlWrapper(
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
     * 爬取网站信息
     * @param urlWrapper 网站url包装类
     */
    private fun getDocument(urlWrapper: BookmarkUrlWrapper): Document = runCatching {
        Jsoup.connect(urlWrapper.urlFull).timeout(10000).ignoreHttpErrors(false).get()
    }.getOrElse { err ->
        err.printStackTrace()
        throw CommonException(ErrorType.E304, err.message ?: err.toString())
    }

    /**
     * 获网站图片
     * @param info 网站信息
     * @return 网站所有不同格式和大小的图标文件, 包含favicon.ico
     */
    private fun initLogo(info: BookmarkWrapper) {
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
            info.manifest?.icons?.forEach { icon -> icon.src?.normalize()?.let { add(icon.copy(src = it)) } }
            // 2. Apple Touch Icons
            info.appleTouchIcons.forEach { (size, url) ->
                url.normalize()?.let { add(ManifestIcon(src = it, sizes = size, type = "image/png")) }
            }

            // 3. Favicons
            info.faviconUrls.forEach { url ->
                url.normalize()?.let { normUrl ->
                    val type = when {
                        normUrl.endsWith(".ico", true) -> "image/x-icon"
                        normUrl.endsWith(".png", true) -> "image/png"
                        normUrl.endsWith(".svg", true) -> "image/svg+xml"
                        normUrl.endsWith(".jpg", true) || normUrl.endsWith(".jpeg", true) -> "image/jpeg"
                        else -> null
                    }
                    add(ManifestIcon(src = normUrl, sizes = "16x16", type = type))
                }
            }

            // 4. OG Image
            info.ogImage?.normalize()?.let {
                add(ManifestIcon(src = it, sizes = "og", type = null))
            }
        }

        // Deduplicate
        val distinctIcons = icons.distinctBy { it.src }

        // Update manifest
        info.manifest = (info.manifest ?: WebManifest()).copy(icons = distinctIcons)
        info.distinctIcons = distinctIcons
    }

    /** 从 Document 解析 WebsiteHeaderInfo 将原 DTO 中的构造函数逻辑迁移至此 */
    private fun parseDocument(document: Document): BookmarkWrapper {
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
        return info
    }

    private fun fillManifest(info: BookmarkWrapper) {
        if (StrUtil.isBlank(info.manifestUrl)) return

        fetchManifest(info.manifestUrl!!)?.let { json ->
            info.manifest = runCatching { parseManifestJson(json) }.getOrElse {
                it.printStackTrace()
                throw CommonException(ErrorType.E222, "Failed to parse manifest from ${info.manifestUrl}, $it")
            }
        }

        // 整理manifest中的信息
        if (info.manifest != null) {
            info.name = info.manifest?.name
            if (StrUtil.isBlank(info.description)) info.description = info.manifest?.description
        }
    }

    private fun detectAntiCrawler(doc: Document): Boolean {
        val title = doc.title().lowercase()
        val text = doc.text().lowercase()

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
        if (wafTitles.any { title.contains(it) }) return true

        // 2. EdgeOne / Generic JS Challenge (Short body, script only, no visible content)
        if (title.isBlank() && doc.body().text().isBlank() && doc.select("script").isNotEmpty()) {
            val scriptContent = doc.select("script").html()
            if (scriptContent.contains("document.cookie") || scriptContent.contains("location.href")) {
                return true
            }
        }

        // 3. Cloudflare specific text
        if (text.contains("please enable cookies") && text.contains("security by cloudflare")) return true

        return false
    }

    private fun getMetaContent(doc: Document, attr: String, value: String): String? =
        doc.select("meta[$attr=$value]").attr("content").ifBlank { null }

    private fun getLinkHref(doc: Document, rel: String): String? =
        doc.select("link[rel=$rel]").attr("abs:href").ifBlank { null }

    private fun getLinkHrefs(doc: Document, rel: String): List<String> =
        doc.select("link[rel=$rel]").map { it.attr("abs:href") }.filter { it.isNotBlank() }

    private fun fetchManifest(manifestUrl: String): String? {
        return runCatching {
            HttpUtil.createGet(manifestUrl).header(
                "User-Agent",
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/91.0.4472.124 Safari/537.36"
            ).execute().body()
        }.getOrNull()
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
}
