use scraper::{Html, Selector};
use serde::Serialize;

/// 网页元数据抓取结果，包含标题、描述、图片、图标等信息。
#[derive(Debug, Serialize, PartialEq)]
pub struct ScrapeResult {
    /// 页面标题（来自 og:title、twitter:title、JSON-LD name 或 <title> 标签）
    pub title: Option<String>,
    /// 页面描述（来自 og:description、twitter:description、JSON-LD description 或 meta description）
    pub description: Option<String>,
    /// 页面主图（来自 og:image、twitter:image 或 JSON-LD image）
    pub image: Option<String>,
    /// 网站图标 URL（来自 <link rel="icon"> 或默认的 /favicon.ico）
    pub favicon: Option<String>,
    /// 网站 Logo URL（来自 JSON-LD logo、apple-touch-icon 或最大尺寸的 icon）
    pub logo: Option<String>,
    /// 数据来源标识，取值为 "og" / "twitter_card" / "json_ld" / "html" / "headless"
    pub source: String,
    /// 无头浏览器模式下捕获的截图字节（PNG 格式）；普通抓取模式下为 None
    pub screenshot_bytes: Option<Vec<u8>>,
    /// 截图上传到 OSS 后的 URL；未上传或上传失败时为 None
    pub screenshot_url: Option<String>,
}

/// 抓取过程中可能发生的错误类型。
#[derive(Debug)]
pub enum ScrapeError {
    /// URL 格式非法，无法解析
    InvalidUrl,
    /// 目标主机被 SSRF 防护策略拒绝（私有/回环/链路本地等）
    ForbiddenTarget(String),
    /// HTTP 请求或无头浏览器操作超时
    Timeout,
    /// HTTP 请求失败（网络错误、非 2xx 响应等），附带错误描述
    FetchFailed(String),
    /// 无头浏览器启动或页面加载失败，附带错误描述
    HeadlessFailed(String),
    /// OSS 上传失败，附带错误描述
    OssFailed(String),
}

pub fn validate_url_scheme(url: &reqwest::Url) -> Result<(), ScrapeError> {
    if matches!(url.scheme(), "http" | "https") {
        Ok(())
    } else {
        Err(ScrapeError::InvalidUrl)
    }
}

/// 流式读取响应体，超过 `max_bytes` 时立即中止并返回错误。
///
/// 先检查 `Content-Length`：若声明值已超限则直接拒绝；
/// 否则按 chunk 累积，累计大小超限同样拒绝。
/// 失败时返回 `Err(描述字符串)`，由调用方包装为合适的 `ScrapeError`。
pub async fn read_body_capped(
    response: reqwest::Response,
    max_bytes: usize,
) -> Result<Vec<u8>, String> {
    use futures_util::StreamExt;

    if let Some(len) = response.content_length() {
        if len as usize > max_bytes {
            return Err(format!(
                "response too large: declared {len} bytes, limit {max_bytes}"
            ));
        }
    }

    let mut stream = response.bytes_stream();
    let mut buf: Vec<u8> = Vec::new();
    while let Some(chunk) = stream.next().await {
        let chunk = chunk.map_err(|e| format!("read failed: {e}"))?;
        if buf.len() + chunk.len() > max_bytes {
            return Err(format!(
                "response too large: exceeded {max_bytes} bytes during read"
            ));
        }
        buf.extend_from_slice(&chunk);
    }
    Ok(buf)
}

/// HTML / 图片 / favicon 的最大允许字节数。
pub const MAX_HTML_BYTES: usize = 5 * 1024 * 1024;
pub const MAX_IMAGE_BYTES: usize = 10 * 1024 * 1024;
pub const MAX_FAVICON_BYTES: usize = 2 * 1024 * 1024;

/// 判断 IP 是否属于禁止抓取的范围（loopback/私有/链路本地/广播/未指定/文档/CGN/ULA 等）。
pub fn is_forbidden_ip(ip: &std::net::IpAddr) -> bool {
    match ip {
        std::net::IpAddr::V4(v4) => {
            let o = v4.octets();
            v4.is_loopback()
                || v4.is_private()
                || v4.is_link_local()
                || v4.is_unspecified()
                || v4.is_broadcast()
                || v4.is_documentation()
                // 100.64.0.0/10 — Carrier-Grade NAT（RFC 6598）
                || (o[0] == 100 && (o[1] & 0xC0) == 64)
        }
        std::net::IpAddr::V6(v6) => {
            if v6.is_loopback() || v6.is_unspecified() || v6.is_multicast() {
                return true;
            }
            let seg0 = v6.segments()[0];
            // fc00::/7 unique-local
            if (seg0 & 0xfe00) == 0xfc00 {
                return true;
            }
            // fe80::/10 link-local
            if (seg0 & 0xffc0) == 0xfe80 {
                return true;
            }
            // IPv4-mapped IPv6 (::ffff:a.b.c.d) — apply IPv4 rules
            if let Some(v4) = v6.to_ipv4_mapped() {
                return is_forbidden_ip(&std::net::IpAddr::V4(v4));
            }
            false
        }
    }
}

/// 在发起任何网络请求前校验目标主机：解析 host 并拒绝指向私有/回环/链路本地的地址。
/// 设置环境变量 `SSRF_ALLOW_PRIVATE=1` 可关闭检查（用于内网集成测试等可信场景）。
pub async fn validate_target_host(url: &reqwest::Url) -> Result<(), ScrapeError> {
    if std::env::var("SSRF_ALLOW_PRIVATE").ok().as_deref() == Some("1") {
        return Ok(());
    }
    let host = url.host_str().ok_or(ScrapeError::InvalidUrl)?;

    if let Ok(ip) = host.parse::<std::net::IpAddr>() {
        return if is_forbidden_ip(&ip) {
            Err(ScrapeError::ForbiddenTarget(format!("blocked ip literal: {ip}")))
        } else {
            Ok(())
        };
    }

    let port = url.port_or_known_default().unwrap_or(443);
    let addrs = tokio::net::lookup_host(format!("{host}:{port}"))
        .await
        .map_err(|e| ScrapeError::FetchFailed(format!("dns lookup failed: {e}")))?;

    let mut had_any = false;
    for addr in addrs {
        had_any = true;
        if is_forbidden_ip(&addr.ip()) {
            return Err(ScrapeError::ForbiddenTarget(format!(
                "host {host} resolves to blocked address {}",
                addr.ip()
            )));
        }
    }
    if !had_any {
        return Err(ScrapeError::FetchFailed(format!("dns lookup returned no addresses for {host}")));
    }
    Ok(())
}

/// 从解析后的 HTML 文档中读取指定 `property` 属性的 `<meta>` 标签内容。
///
/// 例如读取 `<meta property="og:title" content="My Title"/>` 时
/// 传入 `property = "og:title"`，返回 `Some("My Title")`。
///
/// # 参数
/// - `document`：已解析的 HTML 文档
/// - `property`：`property` 属性的值（如 `"og:title"`、`"og:image"`）
///
/// # 返回
/// 找到时返回 `content` 属性值，否则返回 `None`。
pub fn meta_property(document: &Html, property: &str) -> Option<String> {
    let selector = Selector::parse(&format!(r#"meta[property="{}"]"#, property)).ok()?;
    document
        .select(&selector)
        .next()
        .and_then(|e| e.value().attr("content"))
        .map(str::to_string)
}

/// 从解析后的 HTML 文档中读取指定 `name` 属性的 `<meta>` 标签内容。
///
/// 例如读取 `<meta name="description" content="A desc"/>` 时
/// 传入 `name = "description"`，返回 `Some("A desc")`。
///
/// # 参数
/// - `document`：已解析的 HTML 文档
/// - `name`：`name` 属性的值（如 `"description"`、`"twitter:title"`）
///
/// # 返回
/// 找到时返回 `content` 属性值，否则返回 `None`。
pub fn meta_name(document: &Html, name: &str) -> Option<String> {
    let selector = Selector::parse(&format!(r#"meta[name="{}"]"#, name)).ok()?;
    document
        .select(&selector)
        .next()
        .and_then(|e| e.value().attr("content"))
        .map(str::to_string)
}

/// 从解析后的 HTML 文档中提取 `<title>` 标签的文本内容。
///
/// 提取后会去除首尾空白；若标签存在但内容为空则返回 `None`。
///
/// # 参数
/// - `document`：已解析的 HTML 文档
///
/// # 返回
/// 非空标题字符串，或 `None`。
pub fn extract_title(document: &Html) -> Option<String> {
    let selector = Selector::parse("title").ok()?;
    document
        .select(&selector)
        .next()
        .map(|e| e.text().collect::<String>().trim().to_string())
        .filter(|s| !s.is_empty())
}

/// 从解析后的 HTML 文档中提取网站图标（favicon）的绝对 URL。
///
/// 查找 `<link rel="icon" href="..."/>` 标签；若未找到则回退到 `{base_url}/favicon.ico`。
/// 相对路径会基于 `base_url` 解析为绝对 URL。
///
/// # 参数
/// - `document`：已解析的 HTML 文档
/// - `base_url`：当前页面的基础 URL，用于将相对路径转换为绝对路径
///
/// # 返回
/// favicon 的绝对 URL 字符串，或 `None`（URL join 失败时）。
pub fn extract_favicon(document: &Html, base_url: &reqwest::Url) -> Option<String> {
    let selector = Selector::parse(r#"link[rel~="icon"]"#).ok()?;

    // 优先选择 sizes 属性面积最大的 icon；多个无 sizes 时退回到第一个。
    let mut best_sized: Option<(u32, String)> = None;
    let mut first_unsized: Option<String> = None;
    for elem in document.select(&selector) {
        let Some(href) = elem.value().attr("href") else { continue };
        let sizes = elem.value().attr("sizes").unwrap_or("");
        let area = sizes
            .split_whitespace()
            .filter_map(|s| {
                let mut parts = s.splitn(2, 'x');
                let w: u32 = parts.next()?.parse().ok()?;
                let h: u32 = parts.next()?.parse().ok()?;
                Some(w.saturating_mul(h))
            })
            .max()
            .unwrap_or(0);

        if area > 0 {
            if best_sized.as_ref().is_none_or(|(a, _)| area > *a) {
                if let Ok(url) = base_url.join(href) {
                    best_sized = Some((area, url.to_string()));
                }
            }
        } else if first_unsized.is_none() {
            if let Ok(url) = base_url.join(href) {
                first_unsized = Some(url.to_string());
            }
        }
    }

    if let Some((_, url)) = best_sized {
        return Some(url);
    }
    if let Some(url) = first_unsized {
        return Some(url);
    }
    base_url.join("/favicon.ico").ok().map(|u| u.to_string())
}

/// 从解析后的 HTML 文档中提取网站 Logo 的绝对 URL。
///
/// 按优先级尝试以下来源：
/// 1. JSON-LD `logo` 字段（字符串或 `{ "url": "..." }` 对象）
/// 2. `<link rel="apple-touch-icon">` 标签（通常为 180×180 高分辨率图标）
/// 3. 带 `sizes` 属性的 `<link rel="icon">` 中面积最大的一项
///
/// # 参数
/// - `document`：已解析的 HTML 文档
/// - `base_url`：当前页面的基础 URL，用于将相对路径转换为绝对路径
///
/// # 返回
/// Logo 的绝对 URL 字符串，或 `None`（均未找到时）。
pub fn extract_logo(document: &Html, base_url: &reqwest::Url) -> Option<String> {
    // 1. JSON-LD logo field
    if let Ok(sel) = Selector::parse(r#"script[type="application/ld+json"]"#) {
        for element in document.select(&sel) {
            let text = element.text().collect::<String>();
            if let Ok(json) = serde_json::from_str::<serde_json::Value>(&text) {
                let logo = json.get("logo").and_then(|v| {
                    v.as_str().map(String::from).or_else(|| {
                        v.get("url").and_then(|u| u.as_str()).map(String::from)
                    })
                });
                if let Some(url) = logo {
                    let resolved = base_url.join(&url).map(|u| u.to_string()).unwrap_or(url);
                    return Some(resolved);
                }
            }
        }
    }

    // 2. apple-touch-icon
    if let Ok(sel) = Selector::parse(r#"link[rel~="apple-touch-icon"]"#) {
        if let Some(href) = document.select(&sel).next().and_then(|e| e.value().attr("href")) {
            if let Ok(url) = base_url.join(href) {
                return Some(url.to_string());
            }
        }
    }

    // 3. Largest sized icon
    if let Ok(sel) = Selector::parse(r#"link[rel~="icon"][sizes]"#) {
        let mut best: Option<(u32, String)> = None;
        for elem in document.select(&sel) {
            let Some(href) = elem.value().attr("href") else { continue };
            let sizes = elem.value().attr("sizes").unwrap_or("");
            let area = sizes
                .split_whitespace()
                .filter_map(|s| {
                    let mut parts = s.splitn(2, 'x');
                    let w: u32 = parts.next()?.parse().ok()?;
                    let h: u32 = parts.next()?.parse().ok()?;
                    Some(w.saturating_mul(h))
                })
                .max()
                .unwrap_or(0);
            if area > 0 && best.as_ref().is_none_or(|(a, _)| area > *a) {
                if let Ok(url) = base_url.join(href) {
                    best = Some((area, url.to_string()));
                }
            }
        }
        if let Some((_, url)) = best {
            return Some(url);
        }
    }

    None
}

/// 从解析后的 HTML 文档中提取 JSON-LD 结构化数据里的标题、描述和图片。
///
/// 遍历所有 `<script type="application/ld+json">` 标签，找到第一个包含
/// `name` 字段（非空）的 JSON 对象，提取其 `name`、`description` 和 `image`。
///
/// `image` 字段支持两种格式：
/// - 字符串：`"image": "https://..."`
/// - 对象：`"image": { "url": "https://..." }`
///
/// # 参数
/// - `document`：已解析的 HTML 文档
///
/// # 返回
/// 找到时返回 `Some((title, description, image))`，其中每项均为 `Option<String>`；
/// 没有有效 JSON-LD 数据时返回 `None`。
pub fn extract_json_ld(document: &Html) -> Option<(Option<String>, Option<String>, Option<String>)> {
    let selector = Selector::parse(r#"script[type="application/ld+json"]"#).ok()?;

    // 优先匹配代表当前页面的类型（WebPage/Article/Product 等），
    // 避免被 Organization/BreadcrumbList 等"周边"实体抢占标题。
    const PREFERRED: &[&str] = &[
        "WebPage", "WebSite", "Article", "NewsArticle", "BlogPosting",
        "Product", "VideoObject", "Recipe", "Event",
    ];

    fn extract_fields(json: &serde_json::Value) -> Option<(Option<String>, Option<String>, Option<String>)> {
        let title = json.get("name").and_then(|v| v.as_str()).map(String::from);
        title.as_ref()?;
        let desc = json.get("description").and_then(|v| v.as_str()).map(String::from);
        let image = json.get("image").and_then(|v| {
            v.as_str().map(String::from).or_else(|| {
                v.get("url").and_then(|u| u.as_str()).map(String::from)
            })
        });
        Some((title, desc, image))
    }

    fn type_matches(json: &serde_json::Value, preferred: &[&str]) -> bool {
        match json.get("@type") {
            Some(serde_json::Value::String(s)) => preferred.contains(&s.as_str()),
            Some(serde_json::Value::Array(arr)) => arr.iter().any(|v| {
                v.as_str().is_some_and(|s| preferred.contains(&s))
            }),
            _ => false,
        }
    }

    // 收集所有可解析的 JSON-LD 节点（含 @graph 内的子节点）。
    let mut candidates: Vec<serde_json::Value> = Vec::new();
    for element in document.select(&selector) {
        let text = element.text().collect::<String>();
        let Ok(json) = serde_json::from_str::<serde_json::Value>(&text) else { continue };
        if let Some(graph) = json.get("@graph").and_then(|v| v.as_array()) {
            candidates.extend(graph.iter().cloned());
        }
        candidates.push(json);
    }

    // 第一遍：偏好类型 + 有 name
    for json in &candidates {
        if type_matches(json, PREFERRED) {
            if let Some(fields) = extract_fields(json) {
                return Some(fields);
            }
        }
    }
    // 第二遍：任何带 name 的节点
    for json in &candidates {
        if let Some(fields) = extract_fields(json) {
            return Some(fields);
        }
    }
    None
}

/// 解析 HTML 字符串，按优先级依次提取页面元数据，返回 `ScrapeResult`。
///
/// 提取优先级（高到低）：
/// 1. **Open Graph**：`og:title`、`og:description`、`og:image`
/// 2. **Twitter Card**：`twitter:title`、`twitter:description`、`twitter:image`
/// 3. **JSON-LD**：结构化数据中的 `name`、`description`、`image`
/// 4. **HTML 回退**：`<title>` 标签文本、`meta[name="description"]`
///
/// 所有来源均会尝试提取 favicon。`source` 字段记录实际命中的来源。
///
/// # 参数
/// - `html`：原始 HTML 字符串
/// - `base_url`：页面 URL，用于将相对路径转换为绝对路径
///
/// # 返回
/// 填充了可用字段的 `ScrapeResult`，缺失字段为 `None`。
pub fn parse_metadata(html: &str, base_url: &reqwest::Url) -> ScrapeResult {
    let document = Html::parse_document(html);

    let favicon = extract_favicon(&document, base_url);
    let logo = extract_logo(&document, base_url);

    // OG tags
    if let Some(title) = meta_property(&document, "og:title") {
        return ScrapeResult {
            title: Some(title),
            description: meta_property(&document, "og:description")
                .or_else(|| meta_name(&document, "description")),
            image: meta_property(&document, "og:image"),
            favicon,
            logo,
            source: "og".to_string(),
            screenshot_bytes: None,
            screenshot_url: None,
        };
    }

    // Twitter Card
    if let Some(title) = meta_name(&document, "twitter:title") {
        return ScrapeResult {
            title: Some(title),
            description: meta_name(&document, "twitter:description")
                .or_else(|| meta_name(&document, "description")),
            image: meta_name(&document, "twitter:image"),
            favicon,
            logo,
            source: "twitter_card".to_string(),
            screenshot_bytes: None,
            screenshot_url: None,
        };
    }

    // JSON-LD
    if let Some((title, desc, image)) = extract_json_ld(&document) {
        return ScrapeResult {
            title,
            description: desc.or_else(|| meta_name(&document, "description")),
            image,
            favicon,
            logo,
            source: "json_ld".to_string(),
            screenshot_bytes: None,
            screenshot_url: None,
        };
    }

    // HTML fallback
    ScrapeResult {
        title: extract_title(&document),
        description: meta_name(&document, "description"),
        image: None,
        favicon,
        logo,
        source: "html".to_string(),
        screenshot_bytes: None,
        screenshot_url: None,
    }
}

/// 通过普通 HTTP 请求（Layer 1）抓取指定 URL 的页面元数据。
///
/// 使用桌面端 Chrome 的 User-Agent 发送 GET 请求，读取响应体 HTML，
/// 然后调用 [`parse_metadata`] 提取结构化元数据。
///
/// 超时时间由调用方传入的 `client` 决定（在 `AppState` 构建时配置）。
///
/// # 参数
/// - `url`：目标页面 URL 字符串
/// - `client`：共享的 `reqwest::Client` 实例
///
/// # 返回
/// 成功时返回 `Ok(ScrapeResult)`；失败时返回对应的 `ScrapeError`：
/// - `InvalidUrl`：URL 格式非法
/// - `Timeout`：请求超时
/// - `FetchFailed`：网络错误或 HTTP 错误状态码
pub async fn scrape(url: &str, client: &reqwest::Client) -> Result<ScrapeResult, ScrapeError> {
    let parsed = reqwest::Url::parse(url).map_err(|_| ScrapeError::InvalidUrl)?;
    validate_url_scheme(&parsed)?;
    validate_target_host(&parsed).await?;

    let response = client
        .get(parsed.clone())
        .header(
            "User-Agent",
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) \
             AppleWebKit/537.36 (KHTML, like Gecko) Chrome/136.0.0.0 Safari/537.36",
        )
        .send()
        .await
        .map_err(|e| {
            if e.is_timeout() {
                ScrapeError::Timeout
            } else {
                ScrapeError::FetchFailed(e.to_string())
            }
        })?
        .error_for_status()
        .map_err(|e| ScrapeError::FetchFailed(e.to_string()))?;

    let bytes = read_body_capped(response, MAX_HTML_BYTES)
        .await
        .map_err(ScrapeError::FetchFailed)?;
    let body = String::from_utf8_lossy(&bytes);

    Ok(parse_metadata(&body, &parsed))
}

#[cfg(test)]
mod tests {
    use super::*;

    fn doc(html: &str) -> Html {
        Html::parse_document(html)
    }

    fn base() -> reqwest::Url {
        reqwest::Url::parse("https://example.com").unwrap()
    }

    #[test]
    fn meta_property_finds_og_title() {
        let d = doc(r#"<html><head><meta property="og:title" content="My Title"/></head></html>"#);
        assert_eq!(meta_property(&d, "og:title"), Some("My Title".to_string()));
    }

    #[test]
    fn meta_property_returns_none_when_absent() {
        let d = doc("<html><head></head></html>");
        assert_eq!(meta_property(&d, "og:title"), None);
    }

    #[test]
    fn meta_name_finds_description() {
        let d = doc(r#"<html><head><meta name="description" content="A desc"/></head></html>"#);
        assert_eq!(meta_name(&d, "description"), Some("A desc".to_string()));
    }

    #[test]
    fn extract_title_gets_title_tag() {
        let d = doc("<html><head><title>Hello World</title></head></html>");
        assert_eq!(extract_title(&d), Some("Hello World".to_string()));
    }

    #[test]
    fn extract_title_trims_whitespace() {
        let d = doc("<html><head><title>  Trimmed  </title></head></html>");
        assert_eq!(extract_title(&d), Some("Trimmed".to_string()));
    }

    #[test]
    fn extract_favicon_finds_link_icon() {
        let d = doc(r#"<html><head><link rel="icon" href="/favicon.ico"/></head></html>"#);
        assert_eq!(extract_favicon(&d, &base()), Some("https://example.com/favicon.ico".to_string()));
    }

    #[test]
    fn extract_favicon_falls_back_to_root() {
        let d = doc("<html><head></head></html>");
        assert_eq!(extract_favicon(&d, &base()), Some("https://example.com/favicon.ico".to_string()));
    }

    #[test]
    fn extract_favicon_picks_largest_sized() {
        let d = doc(r#"<html><head>
            <link rel="icon" sizes="16x16" href="/small.ico"/>
            <link rel="icon" sizes="64x64" href="/big.ico"/>
            <link rel="icon" sizes="32x32" href="/medium.ico"/>
        </head></html>"#);
        assert_eq!(
            extract_favicon(&d, &base()),
            Some("https://example.com/big.ico".to_string())
        );
    }

    #[test]
    fn extract_json_ld_extracts_name() {
        let d = doc(r#"<html><head><script type="application/ld+json">{"@type":"Article","name":"LD Title","description":"LD Desc"}</script></head></html>"#);
        let result = extract_json_ld(&d);
        assert!(result.is_some());
        let (title, desc, _) = result.unwrap();
        assert_eq!(title, Some("LD Title".to_string()));
        assert_eq!(desc, Some("LD Desc".to_string()));
    }

    #[test]
    fn extract_json_ld_prefers_article_over_organization() {
        // Organization comes first in source order; Article should still win because @type is preferred.
        let d = doc(r#"<html><head>
            <script type="application/ld+json">{"@type":"Organization","name":"Acme Corp"}</script>
            <script type="application/ld+json">{"@type":"Article","name":"The Real Page Title"}</script>
        </head></html>"#);
        let (title, _, _) = extract_json_ld(&d).expect("should extract");
        assert_eq!(title, Some("The Real Page Title".to_string()));
    }

    #[test]
    fn extract_json_ld_falls_back_to_organization_when_only_one() {
        let d = doc(r#"<html><head>
            <script type="application/ld+json">{"@type":"Organization","name":"Acme Corp"}</script>
        </head></html>"#);
        let (title, _, _) = extract_json_ld(&d).expect("should extract");
        assert_eq!(title, Some("Acme Corp".to_string()));
    }

    #[test]
    fn extract_json_ld_handles_graph_array() {
        let d = doc(r#"<html><head>
            <script type="application/ld+json">{
                "@graph": [
                    {"@type":"Organization","name":"Acme Corp"},
                    {"@type":"WebPage","name":"Page Title"}
                ]
            }</script>
        </head></html>"#);
        let (title, _, _) = extract_json_ld(&d).expect("should extract");
        assert_eq!(title, Some("Page Title".to_string()));
    }

    #[test]
    fn parse_metadata_prefers_og_over_twitter() {
        let html = r#"<html><head>
            <meta property="og:title" content="OG Title"/>
            <meta name="twitter:title" content="TW Title"/>
        </head></html>"#;
        let result = parse_metadata(html, &base());
        assert_eq!(result.title, Some("OG Title".to_string()));
        assert_eq!(result.source, "og");
    }

    #[test]
    fn parse_metadata_falls_back_to_html() {
        let html = "<html><head><title>Plain Title</title></head></html>";
        let result = parse_metadata(html, &base());
        assert_eq!(result.title, Some("Plain Title".to_string()));
        assert_eq!(result.source, "html");
    }

    #[test]
    fn validate_url_scheme_rejects_file_scheme() {
        let url = reqwest::Url::parse("file:///etc/passwd").unwrap();
        assert!(matches!(validate_url_scheme(&url), Err(ScrapeError::InvalidUrl)));
    }

    #[test]
    fn validate_url_scheme_accepts_https() {
        let url = reqwest::Url::parse("https://example.com").unwrap();
        assert!(validate_url_scheme(&url).is_ok());
    }

    #[test]
    fn forbidden_ip_blocks_loopback_v4() {
        let ip: std::net::IpAddr = "127.0.0.1".parse().unwrap();
        assert!(is_forbidden_ip(&ip));
    }

    #[test]
    fn forbidden_ip_blocks_aws_metadata() {
        let ip: std::net::IpAddr = "169.254.169.254".parse().unwrap();
        assert!(is_forbidden_ip(&ip));
    }

    #[test]
    fn forbidden_ip_blocks_rfc1918() {
        for ip in ["10.0.0.1", "172.16.0.1", "192.168.1.1"] {
            let ip: std::net::IpAddr = ip.parse().unwrap();
            assert!(is_forbidden_ip(&ip), "{ip} should be blocked");
        }
    }

    #[test]
    fn forbidden_ip_blocks_cgn() {
        let ip: std::net::IpAddr = "100.64.0.1".parse().unwrap();
        assert!(is_forbidden_ip(&ip));
    }

    #[test]
    fn forbidden_ip_allows_public_v4() {
        let ip: std::net::IpAddr = "8.8.8.8".parse().unwrap();
        assert!(!is_forbidden_ip(&ip));
    }

    #[test]
    fn forbidden_ip_blocks_v6_loopback() {
        let ip: std::net::IpAddr = "::1".parse().unwrap();
        assert!(is_forbidden_ip(&ip));
    }

    #[test]
    fn forbidden_ip_blocks_v6_link_local() {
        let ip: std::net::IpAddr = "fe80::1".parse().unwrap();
        assert!(is_forbidden_ip(&ip));
    }

    #[test]
    fn forbidden_ip_blocks_v6_unique_local() {
        let ip: std::net::IpAddr = "fc00::1".parse().unwrap();
        assert!(is_forbidden_ip(&ip));
    }

    #[test]
    fn forbidden_ip_blocks_v4_mapped_in_v6() {
        let ip: std::net::IpAddr = "::ffff:127.0.0.1".parse().unwrap();
        assert!(is_forbidden_ip(&ip));
    }

    #[tokio::test]
    async fn validate_target_host_blocks_loopback_literal() {
        std::env::remove_var("SSRF_ALLOW_PRIVATE");
        let url = reqwest::Url::parse("http://127.0.0.1:8080/").unwrap();
        let r = validate_target_host(&url).await;
        assert!(matches!(r, Err(ScrapeError::ForbiddenTarget(_))), "got {r:?}");
    }

    #[tokio::test]
    async fn validate_target_host_blocks_aws_metadata_literal() {
        std::env::remove_var("SSRF_ALLOW_PRIVATE");
        let url = reqwest::Url::parse("http://169.254.169.254/").unwrap();
        let r = validate_target_host(&url).await;
        assert!(matches!(r, Err(ScrapeError::ForbiddenTarget(_))), "got {r:?}");
    }
}
