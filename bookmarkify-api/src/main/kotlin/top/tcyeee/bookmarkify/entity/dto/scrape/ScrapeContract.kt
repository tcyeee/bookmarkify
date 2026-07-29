package top.tcyeee.bookmarkify.entity.dto.scrape

import com.fasterxml.jackson.annotation.JsonEnumDefaultValue
import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.databind.JsonNode

/**
 * bookmarkify-scrapper `POST /scrape` 的契约（请求 + 响应），与 Rust 侧
 * `crates/scraper-service/src/contract.rs` 一一对应。**改动必须两侧同步。**
 *
 * 核心约定：
 *
 * > scrapper 只报告事实（页面声明了什么），不做业务解释（这些事实该当什么用）。
 *
 * 所以本文件里只有 [AssetExtractor]（这张图**是从哪个标签/字段拿到的**），没有
 * role/logo/favicon 之类的用途判定 —— "apple-touch-icon 算不算 LOGO"是书签鸭的
 * 策略，归 API 侧的 `extractor → role` 映射表管，改判定规则时不需要动 scrapper，
 * 也不需要重抓。
 *
 * 线上格式为 camelCase，枚举为 SCREAMING_SNAKE_CASE，两侧默认策略天然对齐。
 *
 * 所有响应 DTO 一律 `ignoreUnknown = true`：scrapper 作为通用服务会持续增加字段，
 * API 不该因为拿到了不认识的东西就整体失败。
 */

// ─────────────────────────────────────────────────────────────────────────────
// 请求
// ─────────────────────────────────────────────────────────────────────────────

/**
 * `POST /scrape` 请求体。
 *
 * 各配置块的默认值与 Rust 侧 `Default` 实现保持一致，等价于"抓元数据和图片声明、
 * 只探测不下载图片正文、自动决定是否走无头浏览器"。
 *
 * 注意 Rust 侧对请求体启用了 `deny_unknown_fields`：**多发一个字段会被整体拒绝**，
 * 这是刻意的，避免拼错字段名后被静默忽略成默认值。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
data class ScrapeRequest(
    /** 目标 URL，必填 */
    val url: String,
    val render: RenderOptions = RenderOptions(),
    val extract: ExtractOptions = ExtractOptions(),
    val assets: AssetOptions = AssetOptions(),
    val screenshot: ScreenshotOptions = ScreenshotOptions(),
    val cache: CacheOptions = CacheOptions(),
    val robots: RobotsOptions = RobotsOptions(),
)

/** 渲染层行为 */
@JsonInclude(JsonInclude.Include.NON_NULL)
data class RenderOptions(
    val mode: RenderMode = RenderMode.AUTO,
    /** 单次抓取总超时(毫秒)。null 时由 scrapper 环境变量决定 */
    val timeoutMs: Long? = null,
    /** 无头模式下的页面就绪判定，仅 headless 生效 */
    val waitUntil: WaitUntil = WaitUntil.LOAD,
    /** 无头模式视口，仅 headless 生效 */
    val viewport: Viewport? = null,
    /** 覆盖 User-Agent，null 时用 scrapper 默认的桌面 Chrome UA */
    val userAgent: String? = null,
    /** 覆盖 Accept-Language，如 "zh-CN" */
    val locale: String? = null,
    /** 声明配色偏好以抓取暗色 LOGO，仅 headless 生效 */
    val colorScheme: ColorScheme? = null,
)

/** 抓取模式 */
enum class RenderMode {
    /** 先普通 HTTP，未拿到标题时回退无头浏览器 */
    AUTO,

    /** 只走普通 HTTP，失败即失败 */
    HTTP,

    /** 直接走无头浏览器 */
    HEADLESS,
}

/** 无头模式下的页面就绪判定 */
enum class WaitUntil { LOAD, DOM_CONTENT_LOADED, NETWORK_IDLE }

/** 配色偏好，映射为 prefers-color-scheme */
enum class ColorScheme { LIGHT, DARK }

/** 无头浏览器视口 */
data class Viewport(
    val width: Int,
    val height: Int,
    /** 设备像素比，取 2 可拿到 2x 截图 */
    val dpr: Float = 1f,
)

/**
 * 各提取模块的开关。关掉用不上的模块能省下解析和网络开销
 * （例如 [manifest] 需要额外一次 HTTP 请求）。
 */
data class ExtractOptions(
    /** 标题/描述/canonical/语言等基础元数据 */
    val meta: Boolean = true,
    /** 图片资源声明(assets[]) */
    val assets: Boolean = true,
    /** 拉取并解析 <link rel="manifest"> 指向的 Web App Manifest */
    val manifest: Boolean = true,
    /** 页面内全部 JSON-LD 块，原样透传 */
    val jsonld: Boolean = true,
    /** 全部 og:* 键值对，原样透传 */
    val opengraph: Boolean = true,
    /** 全部 twitter:* 键值对，原样透传 */
    val twitter: Boolean = true,
    /** RSS / Atom 订阅源声明 */
    val feeds: Boolean = false,
    /** <link rel="alternate"> 多语言/多端替代地址 */
    val alternates: Boolean = false,
    /** 页面正文纯文本(体积大，默认关) */
    val text: Boolean = false,
)

/** 图片资源的处理方式 */
data class AssetOptions(
    val download: AssetDownload = AssetDownload.PROBE,
    /** 单张图片字节上限，超过则跳过并在该项记 error */
    val maxBytes: Long = 2L * 1024 * 1024,
    /** 最多处理多少张图片，超出部分仍出现在 assets[] 但不下载 */
    val maxCount: Int = 20,
)

/**
 * 图片下载策略。
 *
 * [PROBE] 是默认值。三种下载模式**都会取回正文**(contentHash 和真实像素尺寸都必须读到
 * 字节才能算)，区别只在拿到之后怎么处置：PROBE 算完即丢、INLINE 编码进 dataUrl、
 * UPLOAD 传对象存储。图标普遍只有几 KB，PROBE 的带宽代价很低，换来的是判定"这张图够不够
 * 大、能不能当 LOGO 用"以及跨 extractor 去重所需的全部依据。
 */
enum class AssetDownload {
    /** 只报告页面声明，不发任何额外请求。尺寸只有 declared.sizes 里写的那个 */
    NONE,

    /** 取回正文算出 width/height/mime/byteSize/contentHash 后丢弃，不落任何存储 */
    PROBE,

    /** 取回正文并以 data: URL 内联在 dataUrl 字段 */
    INLINE,

    /** 取回正文并上传 OSS，返回 storageKey；scrapper 未配置 OSS 时自动降级为 PROBE */
    UPLOAD,
}

/** 页面截图 */
data class ScreenshotOptions(
    /** 开启会强制走无头浏览器 */
    val enabled: Boolean = false,
    val fullPage: Boolean = false,
    val format: ImageFormat = ImageFormat.WEBP,
    /** 有损格式质量 1-100 */
    val quality: Int = 80,
)

/** 截图输出格式 */
enum class ImageFormat {
    WEBP,
    PNG,
    JPEG,

    @JsonEnumDefaultValue
    UNKNOWN,
}

/** 缓存策略 */
@JsonInclude(JsonInclude.Include.NON_NULL)
data class CacheOptions(
    val mode: CacheMode = CacheMode.DEFAULT,
    /** 可接受的最大缓存年龄(秒)，null 时用 scrapper 的 CACHE_TTL_SECS */
    val maxAgeS: Long? = null,
)

/** 缓存模式 */
enum class CacheMode {
    /** 命中则用缓存，否则实时抓 */
    DEFAULT,

    /**
     * 无视缓存强制重抓并覆盖缓存。
     * 管理后台"重试"必须传这个 —— 否则重试可能直接命中缓存，等于没试。
     */
    BYPASS,

    /** 只用缓存，未命中直接 404，不发起任何网络请求 */
    ONLY_IF_CACHED,
}

/** robots.txt 策略 */
data class RobotsOptions(
    /** 禁止抓取时 scrapper 返回 403，判定结果记在 [Diagnostics.robots] */
    val respect: Boolean = true,
)

// ─────────────────────────────────────────────────────────────────────────────
// 响应
// ─────────────────────────────────────────────────────────────────────────────

/**
 * `POST /scrape` 成功响应体。
 *
 * 原始块（[jsonld] / [opengraph] / [twitter] / [manifest]）一律原样透传，scrapper
 * 不替调用方做取舍；API 侧建议整体存进 `scrape_snapshot.response` (jsonb)，需要什么
 * 再投影成结构化列 —— 将来想启用当时没提列的字段可以从快照回填，不必重爬全站。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
data class ScrapeResponse(
    /**
     * 回显**实际生效**的请求参数(含 scrapper 兜底后的值)。
     *
     * 保持 [JsonNode] 而不反序列化成 [ScrapeRequest]：这块只用于排障和归档，
     * 弱类型可以免疫 scrapper 后续新增的参数。
     */
    val request: JsonNode? = null,

    /** 本次抓取的传输层事实 */
    val fetch: FetchInfo,

    /** 基础元数据，extract.meta = false 时为 null */
    val meta: PageMeta? = null,

    /** 页面声明的全部图片资源，extract.assets = false 时为空 */
    val assets: List<Asset> = emptyList(),

    val manifest: ManifestBlock? = null,

    /** 页面内全部 JSON-LD 块，原样透传(不筛选、不合并) */
    val jsonld: List<JsonNode> = emptyList(),

    /** 全部 og:* 键值对，键已去掉 og: 前缀 */
    val opengraph: Map<String, String> = emptyMap(),

    /** 全部 twitter:* 键值对，键已去掉 twitter: 前缀 */
    val twitter: Map<String, String> = emptyMap(),

    val feeds: List<Feed> = emptyList(),
    val alternates: List<Alternate> = emptyList(),
    val text: String? = null,
    val screenshot: Screenshot? = null,

    /** 诊断信息，与业务数据分开放 */
    val diagnostics: Diagnostics = Diagnostics(),
)

/** 传输层事实 */
@JsonIgnoreProperties(ignoreUnknown = true)
data class FetchInfo(
    /** 跟完重定向后的最终 URL，相对路径解析与 contentHash 均以它为基准 */
    val finalUrl: String,
    /** 重定向链，不含最终 URL */
    val redirectChain: List<Redirect> = emptyList(),
    val httpStatus: Int,
    /** 实际使用的抓取层。请求 AUTO 时，这里告诉你到底走了哪层 */
    val layerUsed: RenderLayer = RenderLayer.UNKNOWN,
    val fromCache: Boolean = false,
    val contentType: String? = null,
    val charset: String? = null,
    /** HTML 正文字节数 */
    val byteSize: Long? = null,
    /** TLS 证书信息，https 且握手成功时才有 */
    val tls: TlsInfo? = null,
    val timingMs: Timing = Timing(),
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class Redirect(val url: String, val status: Int)

/** 实际使用的抓取层 */
enum class RenderLayer {
    /** Layer 1：普通 HTTP */
    HTTP,

    /** Layer 2：无头浏览器 */
    HEADLESS,

    @JsonEnumDefaultValue
    UNKNOWN,
}

/** TLS 证书信息，可据此判定"不支持 SSL / 证书过期" */
@JsonIgnoreProperties(ignoreUnknown = true)
data class TlsInfo(
    val valid: Boolean = false,
    val issuer: String? = null,
    /** 证书有效期截止时间，RFC 3339 */
    val notAfter: String? = null,
)

/** 分阶段耗时(毫秒)。各分段可能缺失(命中缓存、复用连接等) */
@JsonIgnoreProperties(ignoreUnknown = true)
data class Timing(
    val dns: Long? = null,
    val connect: Long? = null,
    val tls: Long? = null,
    val ttfb: Long? = null,
    /** 端到端总耗时，始终存在 */
    val total: Long = 0,
)

/**
 * 基础元数据。
 *
 * 每个字段的出处单独记在 [sources] 里，**不设全局 source**。旧契约的单一 `source`
 * 是错的：OG 分支里 description 会回落到 `meta[name=description]`，却仍然整体上报
 * `"og"` —— 那个值一直在说谎。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
data class PageMeta(
    val title: String? = null,
    val description: String? = null,
    /** 站点名(og:site_name / manifest.name)，区别于单页标题 */
    val siteName: String? = null,
    /** 站点短名(manifest.short_name)，空间受限的图标下方文案专用 */
    val shortName: String? = null,
    val canonicalUrl: String? = null,
    /** 页面语言(<html lang>) */
    val lang: String? = null,
    /** 主题色(meta[name=theme-color] / manifest.theme_color) */
    val themeColor: String? = null,
    val author: String? = null,
    /** 发布时间，RFC 3339 */
    val publishedAt: String? = null,
    /** meta[name=robots] 原始值 */
    val robots: String? = null,
    val keywords: List<String> = emptyList(),
    /** 字段级出处，键是本类的字段名(camelCase)，只包含实际有值的字段 */
    val sources: Map<String, MetaSource> = emptyMap(),
)

/** 单个元数据字段的出处 */
@JsonIgnoreProperties(ignoreUnknown = true)
data class MetaSource(
    val extractor: MetaExtractor = MetaExtractor.UNKNOWN,
    /** 具体键名，如 "og:title"、"description"、"short_name" */
    val rawKey: String? = null,
)

/** 元数据来源类别 */
enum class MetaExtractor {
    /** meta[property^=og:] */
    OG,

    /** meta[name^=twitter:] */
    TWITTER_CARD,

    /** script[type="application/ld+json"] */
    JSON_LD,

    /** Web App Manifest */
    MANIFEST,

    /** meta[name=...] */
    META_NAME,

    /** <title> */
    TITLE_TAG,

    /** <html lang> 等标签属性 */
    HTML_ATTR,

    /** link[rel=canonical] 等 link 标签 */
    LINK_TAG,

    @JsonEnumDefaultValue
    UNKNOWN,
}

/**
 * 一张图片资源的完整声明。
 *
 * **本类刻意不含 role/用途字段** —— 见文件头说明。API 侧按 [extractor] 映射用途与
 * 质量分级：`JSON_LD_ORG_LOGO` / `MANIFEST_ICON` 是可信 LOGO，
 * `APPLE_TOUCH_ICON` / `LINK_ICON` 只是降级候选。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
data class Asset(
    /** 这张图从哪个标签/字段拿到的。事实，非判定 */
    val extractor: AssetExtractor = AssetExtractor.UNKNOWN,
    /** 源站原样声明的属性，未做任何规范化 */
    val declared: DeclaredAttrs = DeclaredAttrs(),
    /** 声明中的原始 URL，可能是相对路径 */
    val originUrl: String,
    /** 相对 [FetchInfo.finalUrl] 解析后的绝对 URL */
    val resolvedUrl: String,

    /** 真实像素宽，download != NONE 且探测成功时才有 */
    val width: Int? = null,
    val height: Int? = null,
    val byteSize: Long? = null,
    /** 实际 MIME，以响应头/文件魔数为准，可能与 declared.type 不符 */
    val mime: String? = null,
    /** 是否矢量图(SVG)。矢量图无固有像素尺寸，小图场景优先选它 */
    val isVector: Boolean? = null,

    /**
     * 图片字节的 SHA-256，形如 "sha256:ab12…"。
     *
     * 用来跨 extractor 去重，并判定"这站的 apple-touch-icon 和 favicon 其实是同一
     * 张图" —— 也就意味着它没有独立 LOGO，大图模式应直接走首字母色块，而不是把
     * 32px 的 favicon 拉伸到 72px。
     */
    val contentHash: String? = null,

    /**
     * 上传 OSS 后的 **object key**（不含域名），download = UPLOAD 时才有。
     *
     * scrapper 刻意不返回完整 URL：域名、签名、按展示模式缩放都是本服务的策略。
     * 要给浏览器用必须先过 [OssUtils.signAsset][top.tcyeee.bookmarkify.utils.OssUtils.signAsset]。
     */
    val storageKey: String? = null,
    /** data: 内联，download = INLINE 时才有 */
    val dataUrl: String? = null,

    /** 这一张的处理失败原因。单张失败不影响整体成功，也不影响其余图片 */
    val error: String? = null,
)

/** 源站原样声明的图片属性，全部保留原始字符串，不做解析或归一 */
@JsonIgnoreProperties(ignoreUnknown = true)
data class DeclaredAttrs(
    /** rel 原值，如 "icon"、"apple-touch-icon" */
    val rel: String? = null,
    /** sizes 原值，如 "180x180"、"any"。**只放尺寸，不放类型标记** */
    val sizes: String? = null,
    /** type 原值，如 "image/png" */
    val type: String? = null,
    /** media 原值，如 "(prefers-color-scheme: dark)" */
    val media: String? = null,
    /** manifest icon 的 purpose，如 "maskable"、"any" */
    val purpose: String? = null,
)

/**
 * 图片资源的提取来源。
 *
 * 枚举值只描述**出处**，用途映射由 API 侧的策略表负责。
 */
enum class AssetExtractor {
    /** link[rel=icon] / rel="shortcut icon" */
    LINK_ICON,

    /** link[rel=mask-icon]，Safari 固定标签页矢量图 */
    LINK_MASK_ICON,

    /** link[rel=apple-touch-icon] */
    APPLE_TOUCH_ICON,

    /** 页面未声明任何图标时，对 /favicon.ico 的约定式兜底探测 */
    FAVICON_ICO_FALLBACK,

    /** Web App Manifest 的 icons[] */
    MANIFEST_ICON,

    /** meta[name=msapplication-TileImage] */
    MS_TILE_IMAGE,

    /** JSON-LD Organization.logo */
    JSON_LD_ORG_LOGO,

    /** JSON-LD image */
    JSON_LD_IMAGE,

    /** meta[property=og:image] */
    OG_IMAGE,

    /** meta[name=twitter:image] */
    TWITTER_IMAGE,

    @JsonEnumDefaultValue
    UNKNOWN,
}

/** Web App Manifest */
@JsonIgnoreProperties(ignoreUnknown = true)
data class ManifestBlock(
    /** manifest 文件自身的绝对 URL */
    val url: String,
    /** 解析后的原始 JSON，原样透传。icons[] 同时会展开进顶层 assets[] */
    val raw: JsonNode? = null,
)

/** RSS / Atom 订阅源 */
@JsonIgnoreProperties(ignoreUnknown = true)
data class Feed(val url: String, val title: String? = null, val mime: String? = null)

/** link[rel=alternate] 替代地址 */
@JsonIgnoreProperties(ignoreUnknown = true)
data class Alternate(val url: String, val hreflang: String? = null, val media: String? = null)

/** 页面截图 */
@JsonIgnoreProperties(ignoreUnknown = true)
data class Screenshot(
    /** 上传 OSS 后的 object key（不含域名）；scrapper 未配置 OSS 时为空，转用 [dataUrl] */
    val storageKey: String? = null,
    val dataUrl: String? = null,
    val width: Int = 0,
    val height: Int = 0,
    val format: ImageFormat = ImageFormat.UNKNOWN,
    val byteSize: Long? = null,
)

/** 诊断信息，与业务数据分开放，便于选择性落库或直接丢弃 */
@JsonIgnoreProperties(ignoreUnknown = true)
data class Diagnostics(
    /** 非致命问题，如 "manifest fetch 404"、"asset probe timeout" */
    val warnings: List<String> = emptyList(),
    val antiCrawler: AntiCrawler? = null,
    /** robots.respect = false 时为 null */
    val robots: RobotsVerdict? = null,
)

/** 反爬 / WAF 挑战页检测结果 */
@JsonIgnoreProperties(ignoreUnknown = true)
data class AntiCrawler(
    /** 为真时 [PageMeta] 的内容可能不可靠 */
    val detected: Boolean = false,
    /** 命中的信号，如 "cf-chl"、"title:Just a moment..." */
    val signal: String? = null,
)

/** robots.txt 判定结果 */
@JsonIgnoreProperties(ignoreUnknown = true)
data class RobotsVerdict(
    val allowed: Boolean = true,
    /** 命中的规则行，如 "Disallow: /private" */
    val matchedRule: String? = null,
)

/** 错误响应体。HTTP 状态码承载类别，本类承载细节 */
@JsonIgnoreProperties(ignoreUnknown = true)
data class ScrapeErrorResponse(
    /** 稳定的机器可读错误码，如 "INVALID_URL"、"TIMEOUT"、"ROBOTS_DENIED" */
    val error: String,
    /** 面向人的详细信息 */
    val detail: String? = null,
    /** 失败前已经拿到的传输层事实，用于排障 */
    val fetch: FetchInfo? = null,
)
