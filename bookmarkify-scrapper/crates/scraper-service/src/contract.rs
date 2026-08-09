//! `POST /scrape` 的对外契约（请求 + 响应）。
//!
//! 这个模块是 scrapper 与调用方之间**唯一**的耦合面，独立于实现存在，作为后续
//! 改造的基准。核心约定只有一条：
//!
//! > **scrapper 只报告事实（页面声明了什么），不做业务解释（这些事实该当什么用）。**
//!
//! 具体体现为：本模块只有 [`AssetExtractor`]（这张图**是从哪个标签/字段拿到的**），
//! 没有 role/logo/favicon 之类的用途判定 —— "apple-touch-icon 算不算 LOGO"是调用方
//! 的策略，不是网页的事实。调用方改判定规则时不需要动 scrapper，也不需要重抓。
//!
//! 线上格式为 camelCase（对齐调用方 Jackson 默认策略），枚举为 SCREAMING_SNAKE_CASE。
//! 所有响应字段中 `Option::None` 与空集合均省略，保持载荷紧凑。
//!
//! 本模块**不加** `#![allow(dead_code)]`。曾经加过，代价是
//! `screenshot.full_page` / `screenshot.quality` 两个字段接了参数却从来没被读取，
//! 编译器本来会报 "field is never read"，被这条 allow 一并压掉了 —— 于是调试页上的
//! 开关点了没反应，而没有任何信号提示。契约里出现读不到的字段，本身就是要修的 bug。

use serde::{Deserialize, Serialize};
use std::collections::BTreeMap;

// ─────────────────────────────────────────────────────────────────────────────
// 请求
// ─────────────────────────────────────────────────────────────────────────────

/// `POST /scrape` 请求体。
///
/// 除 [`url`](ScrapeRequest::url) 外全部可省略；省略时取各配置块的 `Default`，
/// 等价于"抓元数据和图片声明、不下载图片正文、自动决定是否走无头浏览器"。
#[derive(Debug, Clone, Deserialize, Serialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct ScrapeRequest {
    /// 目标 URL，必填。
    pub url: String,

    /// 渲染层行为（HTTP / 无头浏览器、超时、视口、UA…）。
    #[serde(default)]
    pub render: RenderOptions,

    /// 各提取模块的开关，按需付费。
    #[serde(default)]
    pub extract: ExtractOptions,

    /// 图片资源的处理方式（是否下载 / 内联 / 上传 OSS）。
    #[serde(default)]
    pub assets: AssetOptions,

    /// 页面截图。
    #[serde(default)]
    pub screenshot: ScreenshotOptions,

    /// 缓存策略。
    #[serde(default)]
    pub cache: CacheOptions,

    /// robots.txt 策略。
    #[serde(default)]
    pub robots: RobotsOptions,
}

/// 渲染层行为。
#[derive(Debug, Clone, Default, Deserialize, Serialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct RenderOptions {
    /// 抓取模式，默认 [`RenderMode::Auto`]。
    #[serde(default)]
    pub mode: RenderMode,

    /// 单次抓取总超时（毫秒）。`None` 时由服务端环境变量决定。
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub timeout_ms: Option<u64>,

    /// 无头模式下的页面就绪判定，默认 [`WaitUntil::Load`]。仅 headless 生效。
    #[serde(default)]
    pub wait_until: WaitUntil,

    /// 无头模式视口。仅 headless 生效。
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub viewport: Option<Viewport>,

    /// 覆盖 User-Agent。`None` 时用服务端默认的桌面 Chrome UA。
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub user_agent: Option<String>,

    /// 覆盖 `Accept-Language`，如 `"zh-CN"`。
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub locale: Option<String>,

    /// 请求页面时声明的配色偏好，用于抓取暗色模式下的 LOGO。仅 headless 生效。
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub color_scheme: Option<ColorScheme>,
}

/// 抓取模式。
#[derive(Debug, Clone, Copy, PartialEq, Eq, Deserialize, Serialize, Default)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
pub enum RenderMode {
    /// 先走普通 HTTP（Layer 1），未拿到标题时回退无头浏览器（Layer 2）。
    #[default]
    Auto,
    /// 只走普通 HTTP，失败即失败，不回退。
    Http,
    /// 直接走无头浏览器。
    Headless,
}

/// 无头模式下的页面就绪判定。
#[derive(Debug, Clone, Copy, PartialEq, Eq, Deserialize, Serialize, Default)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
pub enum WaitUntil {
    /// `load` 事件触发。
    #[default]
    Load,
    /// `DOMContentLoaded` 事件触发，最快但可能漏掉 JS 注入的元数据。
    DomContentLoaded,
    /// 网络空闲，最慢但最完整。
    NetworkIdle,
}

/// 配色偏好，映射为 `prefers-color-scheme`。
#[derive(Debug, Clone, Copy, PartialEq, Eq, Deserialize, Serialize)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
pub enum ColorScheme {
    Light,
    Dark,
}

/// 无头浏览器视口。
#[derive(Debug, Clone, Copy, Deserialize, Serialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct Viewport {
    pub width: u32,
    pub height: u32,
    /// 设备像素比，默认 1。取 2 可拿到 2x 截图。
    #[serde(default = "default_dpr")]
    pub dpr: f32,
}

fn default_dpr() -> f32 {
    1.0
}

/// 各提取模块的开关。关掉用不上的模块可以省下解析和网络开销
/// （例如 `manifest` 需要额外一次 HTTP 请求）。
#[derive(Debug, Clone, Deserialize, Serialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct ExtractOptions {
    /// 标题/描述/canonical/语言等基础元数据。
    #[serde(default = "yes")]
    pub meta: bool,
    /// 图片资源声明（`assets[]`）。
    #[serde(default = "yes")]
    pub assets: bool,
    /// 拉取并解析 `<link rel="manifest">` 指向的 Web App Manifest。
    #[serde(default = "yes")]
    pub manifest: bool,
    /// 页面内全部 JSON-LD 块，原样透传。
    #[serde(default = "yes")]
    pub jsonld: bool,
    /// 全部 `og:*` 键值对，原样透传。
    #[serde(default = "yes")]
    pub opengraph: bool,
    /// 全部 `twitter:*` 键值对，原样透传。
    #[serde(default = "yes")]
    pub twitter: bool,
    /// RSS / Atom 订阅源声明。
    #[serde(default = "no")]
    pub feeds: bool,
    /// `<link rel="alternate">` 多语言/多端替代地址。
    #[serde(default = "no")]
    pub alternates: bool,
    /// 页面正文纯文本（体积大，默认关）。
    #[serde(default = "no")]
    pub text: bool,
}

impl Default for ExtractOptions {
    fn default() -> Self {
        Self {
            meta: true,
            assets: true,
            manifest: true,
            jsonld: true,
            opengraph: true,
            twitter: true,
            feeds: false,
            alternates: false,
            text: false,
        }
    }
}

fn yes() -> bool {
    true
}

fn no() -> bool {
    false
}

/// 图片资源的处理方式。
#[derive(Debug, Clone, Deserialize, Serialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct AssetOptions {
    /// 下载策略，默认 [`AssetDownload::Probe`]。
    #[serde(default)]
    pub download: AssetDownload,

    /// 单张图片的字节上限，超过则跳过并在该项上记 `error`。默认 2 MiB。
    #[serde(default = "default_max_bytes")]
    pub max_bytes: u64,

    /// 最多处理多少张图片，超出部分仍会出现在 `assets[]` 里但不下载。默认 20。
    #[serde(default = "default_max_count")]
    pub max_count: usize,
}

impl Default for AssetOptions {
    fn default() -> Self {
        Self {
            download: AssetDownload::default(),
            max_bytes: default_max_bytes(),
            max_count: default_max_count(),
        }
    }
}

fn default_max_bytes() -> u64 {
    2 * 1024 * 1024
}

fn default_max_count() -> usize {
    20
}

/// 图片下载策略。
///
/// [`Probe`](AssetDownload::Probe) 是默认值。三种下载模式**都会取回正文**（`contentHash`
/// 和真实像素尺寸都必须读到字节才能算），区别只在拿到之后怎么处置：
///
/// | 模式 | 取回正文 | 正文去向 |
/// |---|---|---|
/// | `NONE` | 否 | —— |
/// | `PROBE` | 是 | 算完 hash/尺寸即丢弃 |
/// | `INLINE` | 是 | 编码进 `dataUrl` |
/// | `UPLOAD` | 是 | 传对象存储，返回 `storageKey` |
///
/// 图标普遍只有几 KB，`PROBE` 的带宽代价很低，换来的是调用方判定"这张图够不够大、
/// 能不能当 LOGO 用"以及跨 extractor 去重所需的全部依据。
#[derive(Debug, Clone, Copy, PartialEq, Eq, Deserialize, Serialize, Default)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
pub enum AssetDownload {
    /// 只报告页面声明，不发任何额外请求。尺寸只有 `declared.sizes` 里写的那个。
    None,
    /// 取回正文算出 `width`/`height`/`mime`/`byteSize`/`contentHash` 后丢弃，不落任何存储。
    #[default]
    Probe,
    /// 取回正文并以 `data:` URL 内联在 `dataUrl` 字段里。适合小图标。
    Inline,
    /// 取回正文并上传对象存储，返回 `storageKey`（object key，不含域名）。服务端未配置 OSS 时自动降级为 `PROBE`。
    Upload,
}

/// 页面截图。
#[derive(Debug, Clone, Deserialize, Serialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct ScreenshotOptions {
    /// 是否截图。开启会强制走无头浏览器。默认关。
    #[serde(default = "no")]
    pub enabled: bool,
    /// 整页截图（而非仅视口内）。
    #[serde(default = "no")]
    pub full_page: bool,
    /// 输出格式，默认 [`ImageFormat::Webp`]。
    #[serde(default)]
    pub format: ImageFormat,
    /// 有损格式的质量 1–100，默认 80。
    #[serde(default = "default_quality")]
    pub quality: u8,
}

impl Default for ScreenshotOptions {
    fn default() -> Self {
        Self {
            enabled: false,
            full_page: false,
            format: ImageFormat::default(),
            quality: default_quality(),
        }
    }
}

fn default_quality() -> u8 {
    80
}

/// 截图输出格式。
#[derive(Debug, Clone, Copy, PartialEq, Eq, Deserialize, Serialize, Default)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
pub enum ImageFormat {
    #[default]
    Webp,
    Png,
    Jpeg,
}

/// 缓存策略。
#[derive(Debug, Clone, Default, Deserialize, Serialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct CacheOptions {
    /// 缓存模式，默认 [`CacheMode::Default`]。
    #[serde(default)]
    pub mode: CacheMode,
    /// 可接受的最大缓存年龄（秒）。`None` 时用服务端 `CACHE_TTL_SECS`。
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub max_age_s: Option<u64>,
}

/// 缓存模式。
#[derive(Debug, Clone, Copy, PartialEq, Eq, Deserialize, Serialize, Default)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
pub enum CacheMode {
    /// 命中则用缓存，否则实时抓。
    #[default]
    Default,
    /// 无视缓存强制重抓，并用新结果覆盖缓存。管理后台"重试"应当传这个 ——
    /// 否则重试可能直接命中缓存，等于没试。
    Bypass,
    /// 只用缓存；未命中直接返回 404，不发起任何网络请求。
    OnlyIfCached,
}

/// robots.txt 策略。
#[derive(Debug, Clone, Deserialize, Serialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct RobotsOptions {
    /// 是否遵守 robots.txt。禁止抓取时返回 403，判定结果记在
    /// [`Diagnostics::robots`]。
    #[serde(default = "yes")]
    pub respect: bool,
}

impl Default for RobotsOptions {
    fn default() -> Self {
        Self { respect: true }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// 响应
// ─────────────────────────────────────────────────────────────────────────────

/// `POST /scrape` 成功响应体。
///
/// 设计原则是"把知道的全说出来"：原始块（[`jsonld`](ScrapeResponse::jsonld) /
/// [`opengraph`](ScrapeResponse::opengraph) / [`twitter`](ScrapeResponse::twitter) /
/// [`manifest`](ScrapeResponse::manifest)）一律原样透传，不替调用方做取舍。
#[derive(Debug, Clone, Deserialize, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct ScrapeResponse {
    /// 回显**实际生效**的请求参数（含服务端兜底后的值）。
    ///
    /// 排障时不必猜"这次到底用了什么参数"，也让响应可以脱离请求单独归档。
    pub request: ScrapeRequest,

    /// 本次抓取的传输层事实。
    pub fetch: FetchInfo,

    /// 基础元数据。`extract.meta = false` 时省略。
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub meta: Option<PageMeta>,

    /// 页面声明的全部图片资源。`extract.assets = false` 时为空。
    #[serde(default, skip_serializing_if = "Vec::is_empty")]
    pub assets: Vec<Asset>,

    /// Web App Manifest。
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub manifest: Option<ManifestBlock>,

    /// 页面内全部 JSON-LD 块，原样透传（不做筛选、不做合并）。
    #[serde(default, skip_serializing_if = "Vec::is_empty")]
    pub jsonld: Vec<serde_json::Value>,

    /// 全部 `og:*` 键值对，键已去掉 `og:` 前缀。
    #[serde(default, skip_serializing_if = "BTreeMap::is_empty")]
    pub opengraph: BTreeMap<String, String>,

    /// 全部 `twitter:*` 键值对，键已去掉 `twitter:` 前缀。
    #[serde(default, skip_serializing_if = "BTreeMap::is_empty")]
    pub twitter: BTreeMap<String, String>,

    /// RSS / Atom 订阅源。`extract.feeds = true` 时才有。
    #[serde(default, skip_serializing_if = "Vec::is_empty")]
    pub feeds: Vec<Feed>,

    /// `<link rel="alternate">` 替代地址。`extract.alternates = true` 时才有。
    #[serde(default, skip_serializing_if = "Vec::is_empty")]
    pub alternates: Vec<Alternate>,

    /// 页面正文纯文本。`extract.text = true` 时才有。
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub text: Option<String>,

    /// 截图。`screenshot.enabled = true` 且成功时才有。
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub screenshot: Option<Screenshot>,

    /// 诊断信息：局部失败、反爬信号、robots 判定。与业务数据分开放。
    pub diagnostics: Diagnostics,
}

impl ScrapeResponse {
    /// 以必填字段起手，其余块留空 —— 各提取模块按开关逐个填进去。
    pub fn new(request: ScrapeRequest, fetch: FetchInfo) -> Self {
        Self {
            request,
            fetch,
            meta: None,
            assets: Vec::new(),
            manifest: None,
            jsonld: Vec::new(),
            opengraph: BTreeMap::new(),
            twitter: BTreeMap::new(),
            feeds: Vec::new(),
            alternates: Vec::new(),
            text: None,
            screenshot: None,
            diagnostics: Diagnostics::default(),
        }
    }
}

/// 传输层事实。
#[derive(Debug, Clone, Deserialize, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct FetchInfo {
    /// 跟完重定向后的最终 URL。相对路径解析、`contentHash` 计算都以它为基准。
    pub final_url: String,
    /// 重定向链，不含最终 URL。无重定向时为空。
    #[serde(default, skip_serializing_if = "Vec::is_empty")]
    pub redirect_chain: Vec<Redirect>,
    /// 最终响应状态码。
    pub http_status: u16,
    /// 实际使用的抓取层。请求 `AUTO` 时，这里告诉你到底走了哪层。
    pub layer_used: RenderLayer,
    /// 本次结果是否来自缓存。
    pub from_cache: bool,
    /// 响应 `Content-Type`。
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub content_type: Option<String>,
    /// 实际解码用的字符集。
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub charset: Option<String>,
    /// HTML 正文字节数。
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub byte_size: Option<u64>,
    /// TLS 证书信息，https 且握手成功时才有。
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub tls: Option<TlsInfo>,
    /// 分阶段耗时。
    pub timing_ms: Timing,
}

/// 单次重定向。
#[derive(Debug, Clone, Deserialize, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct Redirect {
    pub url: String,
    pub status: u16,
}

/// 实际使用的抓取层。
#[derive(Debug, Clone, Copy, PartialEq, Eq, Deserialize, Serialize)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
pub enum RenderLayer {
    /// Layer 1：普通 HTTP。
    Http,
    /// Layer 2：无头浏览器。
    Headless,
    /// 页面本身没抓到（被反爬拦下），元数据来自站点自己的公开 API。
    ///
    /// 单列一层而不是谎报 `HTTP`：此时 `httpStatus` 记的是页面那次被拒的状态码，
    /// 而 `meta` 里的内容根本不是从那份响应里解析出来的。见 `siteapi.rs`。
    SiteApi,
}

/// TLS 证书信息。调用方可据此判定"不支持 SSL / 证书过期"。
#[derive(Debug, Clone, Deserialize, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct TlsInfo {
    pub valid: bool,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub issuer: Option<String>,
    /// 证书有效期截止时间，RFC 3339。
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub not_after: Option<String>,
}

/// 分阶段耗时（毫秒）。各分段可能缺失（如命中缓存、复用连接）。
#[derive(Debug, Clone, Default, Deserialize, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct Timing {
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub dns: Option<u64>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub connect: Option<u64>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub tls: Option<u64>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub ttfb: Option<u64>,
    /// 端到端总耗时，始终存在。
    pub total: u64,
}

/// 基础元数据。
///
/// 每个字段的出处单独记在 [`sources`](PageMeta::sources) 里，**不设全局 `source`**。
/// 旧契约的单一 `source` 是错的：OG 分支里 `description` 会回落到
/// `meta[name=description]`，却仍然整体上报 `"og"`。
#[derive(Debug, Clone, Default, Deserialize, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct PageMeta {
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub title: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub description: Option<String>,
    /// 站点名（`og:site_name` / `manifest.name`），区别于单页标题。
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub site_name: Option<String>,
    /// 站点短名（`manifest.short_name`）。空间受限的图标下方文案专用。
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub short_name: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub canonical_url: Option<String>,
    /// 页面语言（`<html lang>`）。
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub lang: Option<String>,
    /// 主题色（`<meta name="theme-color">` / `manifest.theme_color`）。
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub theme_color: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub author: Option<String>,
    /// 发布时间（`article:published_time` / JSON-LD `datePublished`），RFC 3339。
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub published_at: Option<String>,
    /// `<meta name="robots">` 原始值。
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub robots: Option<String>,
    #[serde(default, skip_serializing_if = "Vec::is_empty")]
    pub keywords: Vec<String>,

    /// 字段级出处。键是本结构体的字段名（camelCase），只包含实际有值的字段。
    #[serde(default, skip_serializing_if = "BTreeMap::is_empty")]
    pub sources: BTreeMap<String, MetaSource>,
}

/// 单个元数据字段的出处。
#[derive(Debug, Clone, Deserialize, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct MetaSource {
    /// 从哪类来源提取的。
    pub extractor: MetaExtractor,
    /// 具体的键名，如 `"og:title"`、`"description"`、`"short_name"`。
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub raw_key: Option<String>,
}

/// 元数据来源类别。
#[derive(Debug, Clone, Copy, PartialEq, Eq, Deserialize, Serialize)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
pub enum MetaExtractor {
    /// `<meta property="og:*">`
    Og,
    /// `<meta name="twitter:*">`
    TwitterCard,
    /// `<script type="application/ld+json">`
    JsonLd,
    /// Web App Manifest
    Manifest,
    /// `<meta name="...">`
    MetaName,
    /// `<title>`
    TitleTag,
    /// `<html lang>`
    HtmlAttr,
    /// `<link rel="canonical">` 等 link 标签
    LinkTag,
    /// 站点自己的公开 API（见 `siteapi.rs`）。`rawKey` 记具体字段，如 `bilibili:view.title`。
    SiteApi,
}

/// 一张图片资源的完整声明。
///
/// **本结构体刻意不含 role/用途字段** —— 见模块文档。调用方按
/// [`extractor`](Asset::extractor) 自行映射用途。
#[derive(Debug, Clone, Deserialize, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct Asset {
    /// 这张图从哪个标签/字段拿到的。事实，非判定。
    pub extractor: AssetExtractor,

    /// 源站原样声明的属性，未做任何规范化。约定式来源（如 `FAVICON_ICO_FALLBACK`）
    /// 没有任何声明属性，此时为空对象。
    #[serde(default)]
    pub declared: DeclaredAttrs,

    /// 声明中的原始 URL，可能是相对路径。
    pub origin_url: String,
    /// 相对 [`FetchInfo::final_url`] 解析后的绝对 URL。
    pub resolved_url: String,

    /// 真实像素宽，`download != NONE` 且探测成功时才有。
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub width: Option<u32>,
    /// 真实像素高。
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub height: Option<u32>,
    /// 字节数。
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub byte_size: Option<u64>,
    /// 实际 MIME，以响应头/文件魔数为准，可能与 `declared.type` 不符。
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub mime: Option<String>,
    /// 是否矢量图（SVG）。矢量图无固有像素尺寸，小图场景优先选它。
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub is_vector: Option<bool>,

    /// 图片字节的 SHA-256，形如 `"sha256:ab12…"`。
    ///
    /// 用途：跨 `extractor` 去重，并让调用方能判定"这站的 apple-touch-icon 和
    /// favicon 其实是同一张图" —— 也就意味着它没有独立 LOGO。
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub content_hash: Option<String>,

    /// 上传对象存储后的 **object key**（不含域名），`download = UPLOAD` 时才有。
    ///
    /// 刻意不返回完整 URL：域名、签名、按展示模式缩放都是消费端的策略，本服务只报
    /// "字节落在了哪个 key 上"这个事实。
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub storage_key: Option<String>,
    /// `data:` 内联，`download = INLINE` 时才有。
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub data_url: Option<String>,

    /// 这一张的处理失败原因。单张失败不影响整体成功，也不影响其余图片。
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub error: Option<String>,
}

/// 源站原样声明的图片属性。全部保留原始字符串，不做解析或归一。
#[derive(Debug, Clone, Default, Deserialize, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct DeclaredAttrs {
    /// `rel` 属性原值，如 `"icon"`、`"apple-touch-icon"`。
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub rel: Option<String>,
    /// `sizes` 属性原值，如 `"180x180"`、`"any"`。**只放尺寸，不放类型标记。**
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub sizes: Option<String>,
    /// `type` 属性原值，如 `"image/png"`。
    #[serde(default, rename = "type", skip_serializing_if = "Option::is_none")]
    pub type_: Option<String>,
    /// `media` 属性原值，如 `"(prefers-color-scheme: dark)"`。
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub media: Option<String>,
    /// manifest icon 的 `purpose`，如 `"maskable"`、`"any"`。
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub purpose: Option<String>,
}

/// 图片资源的提取来源。
///
/// 枚举值只描述**出处**。调用方据此映射用途与质量分级，例如
/// `JSON_LD_ORG_LOGO` / `MANIFEST_ICON` 是可信 LOGO，而
/// `APPLE_TOUCH_ICON` / `LINK_ICON` 只是降级候选。
#[derive(Debug, Clone, Copy, PartialEq, Eq, Deserialize, Serialize)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
pub enum AssetExtractor {
    /// `<link rel="icon">` / `rel="shortcut icon"`
    LinkIcon,
    /// `<link rel="mask-icon">`（Safari 固定标签页矢量图）
    LinkMaskIcon,
    /// `<link rel="apple-touch-icon">`
    AppleTouchIcon,
    /// 页面未声明任何图标时，对 `/favicon.ico` 的约定式兜底探测
    FaviconIcoFallback,
    /// Web App Manifest 的 `icons[]`
    ManifestIcon,
    /// `<meta name="msapplication-TileImage">`
    MsTileImage,
    /// JSON-LD `Organization.logo`
    JsonLdOrgLogo,
    /// JSON-LD `image`
    JsonLdImage,
    /// `<meta property="og:image">`
    OgImage,
    /// `<meta name="twitter:image">`
    TwitterImage,
    /// 站点公开 API 给出的封面图（见 `siteapi.rs`），语义等同 `OG_IMAGE`。
    SiteApiCover,
}

/// Web App Manifest。
#[derive(Debug, Clone, Deserialize, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct ManifestBlock {
    /// manifest 文件自身的绝对 URL。
    pub url: String,
    /// 解析后的原始 JSON，原样透传。`icons[]` 同时会展开进顶层 `assets[]`。
    pub raw: serde_json::Value,
}

/// RSS / Atom 订阅源。
#[derive(Debug, Clone, Deserialize, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct Feed {
    pub url: String,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub title: Option<String>,
    /// `application/rss+xml` / `application/atom+xml`
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub mime: Option<String>,
}

/// `<link rel="alternate">` 替代地址。
#[derive(Debug, Clone, Deserialize, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct Alternate {
    pub url: String,
    /// `hreflang` 值，如 `"zh-CN"`、`"x-default"`。
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub hreflang: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub media: Option<String>,
}

/// 页面截图。
#[derive(Debug, Clone, Deserialize, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct Screenshot {
    /// 上传对象存储后的 **object key**（不含域名）；未配置 OSS 时为空，转用 `dataUrl`。
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub storage_key: Option<String>,
    /// `data:` 内联，仅在未配置 OSS 时出现。
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub data_url: Option<String>,
    pub width: u32,
    pub height: u32,
    pub format: ImageFormat,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub byte_size: Option<u64>,
}

/// 诊断信息。与业务数据分开放，便于调用方选择性落库或直接丢弃。
#[derive(Debug, Clone, Default, Deserialize, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct Diagnostics {
    /// 非致命问题，如 `"manifest fetch 404"`、`"asset probe timeout"`。
    #[serde(default, skip_serializing_if = "Vec::is_empty")]
    pub warnings: Vec<String>,
    /// 反爬 / WAF 挑战页检测。
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub anti_crawler: Option<AntiCrawler>,
    /// robots.txt 判定结果。`robots.respect = false` 时省略。
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub robots: Option<RobotsVerdict>,
}

/// 反爬 / WAF 挑战页检测结果。
#[derive(Debug, Clone, Deserialize, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct AntiCrawler {
    /// 是否疑似挑战页。为真时 `meta` 的内容可能不可靠。
    pub detected: bool,
    /// 命中的信号，如 `"cf-chl"`、`"title:Just a moment..."`。
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub signal: Option<String>,
}

/// robots.txt 判定结果。
#[derive(Debug, Clone, Deserialize, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct RobotsVerdict {
    pub allowed: bool,
    /// 命中的规则行，如 `"Disallow: /private"`。
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub matched_rule: Option<String>,
}

/// 错误响应体。HTTP 状态码承载类别，本结构体承载细节。
#[derive(Debug, Clone, Deserialize, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct ErrorResponse {
    /// 稳定的机器可读错误码，如 `"INVALID_URL"`、`"TIMEOUT"`、`"ROBOTS_DENIED"`。
    pub error: String,
    /// 面向人的详细信息。
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub detail: Option<String>,
    /// 失败前已经拿到的传输层事实，用于排障。
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub fetch: Option<FetchInfo>,
    /// **最后尝试到的抓取层**，`None` 表示一个字节都没发出去（URL 非法、目标不是域名、
    /// 负缓存命中、并发过载）。
    ///
    /// 与 [`FetchInfo::layer_used`] 是两个问题，失败时两者会不一致，这是刻意的：
    /// 后者说的是"上面那份传输层事实由哪层产出"，而 AUTO 模式下 Layer 1 被反爬拦下、
    /// 回退 Layer 2 又同样被拦时，报出去的是 **Layer 1 的**错误现场（它更完整），
    /// 于是 `fetch.layerUsed = HTTP` 而本字段 = `HEADLESS`。调用方要回答的
    /// "这次到底试到哪一层"只能看本字段 —— 没有它，"压根没启浏览器"（同站熔断跳过）
    /// 和"浏览器也过不去"在日志里长得一模一样。
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub layer_used: Option<RenderLayer>,
}

#[cfg(test)]
mod tests {
    use super::*;

    /// 最小请求：只给 url，其余全部走默认值。
    #[test]
    fn minimal_request_fills_defaults() {
        let req: ScrapeRequest = serde_json::from_str(r#"{"url":"https://example.com"}"#).unwrap();
        assert_eq!(req.render.mode, RenderMode::Auto);
        assert_eq!(req.assets.download, AssetDownload::Probe);
        assert_eq!(req.cache.mode, CacheMode::Default);
        assert!(req.extract.meta);
        assert!(req.extract.manifest);
        assert!(!req.extract.text);
        assert!(req.robots.respect);
        assert!(!req.screenshot.enabled);
    }

    /// 枚举走 SCREAMING_SNAKE_CASE，字段走 camelCase。
    #[test]
    fn wire_format_is_camel_case_with_screaming_enums() {
        let req: ScrapeRequest = serde_json::from_str(
            r#"{"url":"https://example.com",
                "render":{"mode":"HEADLESS","waitUntil":"NETWORK_IDLE","colorScheme":"DARK"},
                "assets":{"download":"UPLOAD","maxBytes":1024},
                "cache":{"mode":"BYPASS"}}"#,
        )
        .unwrap();
        assert_eq!(req.render.mode, RenderMode::Headless);
        assert_eq!(req.render.wait_until, WaitUntil::NetworkIdle);
        assert_eq!(req.render.color_scheme, Some(ColorScheme::Dark));
        assert_eq!(req.assets.download, AssetDownload::Upload);
        assert_eq!(req.assets.max_bytes, 1024);
        assert_eq!(req.cache.mode, CacheMode::Bypass);
    }

    /// 拼错的字段必须报错，而不是被静默忽略成默认值。
    #[test]
    fn unknown_request_field_is_rejected() {
        let err = serde_json::from_str::<ScrapeRequest>(
            r#"{"url":"https://example.com","headless":true}"#,
        );
        assert!(
            err.is_err(),
            "旧的 headless 字段应被拒绝，提示改用 render.mode"
        );
    }

    /// 空值与空集合不出现在线上载荷里。
    #[test]
    fn empty_optionals_are_omitted() {
        let resp = ScrapeResponse {
            request: serde_json::from_str(r#"{"url":"https://example.com"}"#).unwrap(),
            fetch: FetchInfo {
                final_url: "https://example.com/".into(),
                redirect_chain: vec![],
                http_status: 200,
                layer_used: RenderLayer::Http,
                from_cache: false,
                content_type: None,
                charset: None,
                byte_size: None,
                tls: None,
                timing_ms: Timing {
                    total: 42,
                    ..Default::default()
                },
            },
            meta: None,
            assets: vec![],
            manifest: None,
            jsonld: vec![],
            opengraph: BTreeMap::new(),
            twitter: BTreeMap::new(),
            feeds: vec![],
            alternates: vec![],
            text: None,
            screenshot: None,
            diagnostics: Diagnostics::default(),
        };
        // 必须查顶层键：`meta`/`assets`/`jsonld` 这几个名字同时也出现在被回显的
        // request.extract 里，直接对整串做 contains 会误判。
        let json = serde_json::to_value(&resp).unwrap();
        let top = json.as_object().unwrap();
        for absent in [
            "meta",
            "assets",
            "manifest",
            "jsonld",
            "opengraph",
            "screenshot",
            "text",
        ] {
            assert!(
                !top.contains_key(absent),
                "空字段 {absent} 不应出现在载荷里"
            );
        }
        for present in ["request", "fetch", "diagnostics"] {
            assert!(top.contains_key(present), "必填字段 {present} 缺失");
        }

        let fetch = top["fetch"].as_object().unwrap();
        assert!(fetch.contains_key("finalUrl"), "字段名应为 camelCase");
        assert_eq!(fetch["layerUsed"], "HTTP", "枚举应为 SCREAMING_SNAKE_CASE");
        assert!(!fetch.contains_key("redirectChain"), "空数组应省略");
        assert_eq!(fetch["timingMs"]["total"], 42);
    }

    /// 跨语言契约样例：Kotlin 侧 `ScrapeContractTest` 读的是同一个文件。
    /// 任何一侧改了字段名或枚举取值，两边的测试会同时变红。
    #[test]
    fn shared_fixture_round_trips() {
        let path = concat!(
            env!("CARGO_MANIFEST_DIR"),
            "/../../../contract/scrape-response.sample.json"
        );
        let raw =
            std::fs::read_to_string(path).unwrap_or_else(|e| panic!("读不到契约样例 {path}: {e}"));

        let resp: ScrapeResponse = serde_json::from_str(&raw).expect("契约样例应能反序列化");

        // 回显的请求块必须能被 deny_unknown_fields 的 ScrapeRequest 接受
        assert_eq!(resp.request.cache.mode, CacheMode::Bypass);
        assert_eq!(resp.request.assets.download, AssetDownload::Probe);

        assert_eq!(resp.fetch.layer_used, RenderLayer::Http);
        assert_eq!(resp.fetch.redirect_chain.len(), 1);

        // 字段级出处：title 来自 OG，description 却回落到了 meta[name]
        let meta = resp.meta.as_ref().expect("样例应含 meta");
        assert_eq!(meta.sources["title"].extractor, MetaExtractor::Og);
        assert_eq!(
            meta.sources["description"].extractor,
            MetaExtractor::MetaName
        );
        assert_eq!(meta.short_name.as_deref(), Some("GitHub"));

        // 同 hash 的两张图 = 该站没有独立 LOGO，调用方据此降级
        let by = |e: AssetExtractor| resp.assets.iter().find(|a| a.extractor == e).unwrap();
        assert_eq!(
            by(AssetExtractor::LinkIcon).content_hash,
            by(AssetExtractor::AppleTouchIcon).content_hash
        );
        // 约定式来源没有任何声明属性
        assert!(by(AssetExtractor::FaviconIcoFallback)
            .declared
            .rel
            .is_none());
        assert!(by(AssetExtractor::FaviconIcoFallback).error.is_some());
        assert_eq!(
            by(AssetExtractor::AppleTouchIcon).declared.sizes.as_deref(),
            Some("180x180")
        );

        // 截图独立于 assets[]：它不是页面声明的图，没有 extractor，也不走 assets.download。
        // 这几行是它唯一的跨语言字段名校验 —— 顶层曾经没有这个对象，于是 Kotlin 侧改了
        // 字段名也不会有任何测试变红。
        let shot = resp.screenshot.as_ref().expect("样例应含 screenshot");
        assert_eq!(shot.format, ImageFormat::Webp);
        assert_eq!((shot.width, shot.height), (1280, 720));
        assert!(
            shot.storage_key
                .as_deref()
                .is_some_and(|k| !k.starts_with("http")),
            "storageKey 必须是裸 object key，不含域名"
        );
        assert!(shot.data_url.is_none(), "落了存储就不该再内联一份 base64");

        // 再序列化后仍能读回，确保 skip_serializing_if 没有吃掉必填字段
        let again: ScrapeResponse =
            serde_json::from_str(&serde_json::to_string(&resp).unwrap()).unwrap();
        assert_eq!(again.assets.len(), resp.assets.len());
        assert_eq!(again.fetch.final_url, resp.fetch.final_url);
        assert_eq!(
            again.screenshot.as_ref().map(|s| s.format),
            Some(ImageFormat::Webp),
            "format 的 SCREAMING_SNAKE 线路形态必须往返稳定"
        );
    }

    /// `declared.type` 在线上是 `type`，不是 `type_`。
    #[test]
    fn declared_type_keeps_html_attribute_name() {
        let attrs = DeclaredAttrs {
            rel: Some("apple-touch-icon".into()),
            sizes: Some("180x180".into()),
            type_: Some("image/png".into()),
            ..Default::default()
        };
        let json = serde_json::to_string(&attrs).unwrap();
        assert!(json.contains("\"type\":\"image/png\""));
        assert!(!json.contains("type_"));
    }
}
