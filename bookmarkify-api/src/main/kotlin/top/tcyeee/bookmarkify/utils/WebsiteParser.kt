package top.tcyeee.bookmarkify.utils

import cn.hutool.core.util.URLUtil
import cn.hutool.http.HttpUtil
import cn.hutool.json.JSONObject
import cn.hutool.json.JSONUtil
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import top.tcyeee.bookmarkify.config.exception.CommonException
import top.tcyeee.bookmarkify.config.exception.ErrorType
import top.tcyeee.bookmarkify.config.log
import top.tcyeee.bookmarkify.entity.dto.BookmarkUrlWrapper
import top.tcyeee.bookmarkify.entity.dto.ManifestIcon
import top.tcyeee.bookmarkify.entity.dto.PreloadResource
import top.tcyeee.bookmarkify.entity.dto.WebManifest
import top.tcyeee.bookmarkify.entity.dto.BookmarkWrapper
import java.net.URL

/**
 * 网站信息解析器
 * 负责从 URL 获取 Document 并解析出 WebsiteHeaderInfo
 */
object WebsiteParser {

    /**
     * 解析 URL 并返回网站头信息
     */
    fun parse(url: String): BookmarkWrapper = urlWrapper(url)
        .let { this.getDocument(it) }
        .let { this.parseDocument(it) }
        .also { this.fillManifest(it) }

    /**
     * 爬取网站信息
     * @param urlWrapper 网站url包装类
     */
    private fun getDocument(urlWrapper: BookmarkUrlWrapper): Document = runCatching {
        Jsoup.connect(urlWrapper.urlFull).timeout(10000).ignoreHttpErrors(true).get()
    }.getOrElse { err ->
        err.printStackTrace()
        throw CommonException(ErrorType.E304, err.message ?: err.toString())
    }

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
            throw CommonException(ErrorType.E303)
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
     * 从 Document 解析 WebsiteHeaderInfo
     * 将原 DTO 中的构造函数逻辑迁移至此
     */
    private fun parseDocument(document: Document): BookmarkWrapper {
        val info = BookmarkWrapper()
        info.title = document.title()
        info.charset = document.charset().name()

        // Anti-crawler detection
        info.antiCrawlerDetected = detectAntiCrawler(document)

        // Meta tags
        info.keywords = getMetaContent(document, "name", "keywords")
        info.description = getMetaContent(document, "name", "description")
        info.viewport = getMetaContent(document, "name", "viewport")
        info.renderer = getMetaContent(document, "name", "renderer")
        info.copyright = getMetaContent(document, "name", "copyright")
        info.referrerPolicy = getMetaContent(document, "name", "referrer")
        info.mobileAgent = getMetaContent(document, "http-equiv", "mobile-agent")
        info.xUaCompatible = getMetaContent(document, "http-equiv", "X-UA-Compatible")
        info.ogImage = getMetaContent(document, "property", "og:image")

        // Link tags (Use abs:href for absolute URLs)
        info.canonicalUrl = getLinkHref(document, "canonical")
        info.manifestUrl = getLinkHref(document, "manifest")

        // Lists
        info.preconnectUrls = getLinkHrefs(document, "preconnect")
        info.dnsPrefetchUrls = getLinkHrefs(document, "dns-prefetch")
        info.styleSheets = getLinkHrefs(document, "stylesheet")
        info.scriptPrefetchUrls =
            document.select("link[rel=prefetch][as=script]").map { it.attr("abs:href") }.filter { it.isNotBlank() }

        // Favicons (icon, shortcut icon, apple-touch-icon, etc.)
        info.faviconUrls = document.select("link[rel~=(?i)^(shortcut|icon|shortcut icon)$]").map { it.attr("abs:href") }
            .filter { it.isNotBlank() }.distinct()

        // Complex structures
        info.appleTouchIcons = document.select("link[rel=apple-touch-icon]").associate {
            val sizes = it.attr("sizes")
            (sizes.ifBlank { "default" }) to it.attr("abs:href")
        }

        info.preloadResources =
            document.select("link[rel=preload]").map { PreloadResource(it.attr("abs:href"), it.attr("as")) }
                .filter { it.url.isNotBlank() }

        // Custom Meta (Exclude known standard names)
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
            "stylesheet",
            "preload",
            "prefetch",
            "icon",
            "shortcut icon",
            "apple-touch-icon"
        )
        info.customLink = document.select("link[rel]").toList().filter {
            !standardLinkRels.contains(it.attr("rel").lowercase()) && it.attr("href").isNotBlank()
        }.associate { it.attr("rel") to it.attr("abs:href") }

        return info
    }

    private fun getMetaContent(doc: Document, attr: String, value: String): String? =
        doc.select("meta[$attr=$value]").attr("content").ifBlank { null }

    private fun getLinkHref(doc: Document, rel: String): String? =
        doc.select("link[rel=$rel]").attr("abs:href").ifBlank { null }

    private fun getLinkHrefs(doc: Document, rel: String): List<String> =
        doc.select("link[rel=$rel]").map { it.attr("abs:href") }.filter { it.isNotBlank() }

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

    private fun fillManifest(info: BookmarkWrapper) {
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
