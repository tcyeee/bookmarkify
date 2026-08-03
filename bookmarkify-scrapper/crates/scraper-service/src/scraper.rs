//! Layer 1 抓取与网络安全基元。
//!
//! 元数据解析已迁往 [`crate::extract`]（纯函数、字段级出处），本模块只保留"把 HTML
//! 拿回来"以及 SSRF 防护相关的能力。

/// 抓取过程中可能发生的错误类型。
#[derive(Debug)]
pub enum ScrapeError {
    /// URL 格式非法，无法解析
    InvalidUrl,
    /// 目标主机被 SSRF 防护策略拒绝（私有/回环/链路本地等）
    ForbiddenTarget(String),
    /// HTTP 请求或无头浏览器操作超时，附带超时发生在哪一步
    Timeout(String),
    /// 传输层失败（DNS、连接、TLS、响应体读取等），附带错误描述。
    ///
    /// **不含**"服务器正常应答但状态码不是 2xx"——那是 [`ScrapeError::HttpStatus`]，
    /// 两者的排障方向完全不同：这里是"没连上"，那里是"连上了但被拒绝"。
    FetchFailed(String),
    /// 目标站点返回了非 2xx 状态码，附带完整现场（见 [`HttpErrorDetail`]）
    HttpStatus(Box<HttpErrorDetail>),
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

/// HTML 与次级资源（manifest / 图标）的最大允许字节数。
pub const MAX_HTML_BYTES: usize = 5 * 1024 * 1024;
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

/// reqwest 自定义 DNS 解析器：在每次实际 TCP 连接前验证解析出的 IP，从根本上防止
/// DNS 重绑定攻击（TOCTOU）。所有通过共享 `reqwest::Client` 发出的请求（包括 OG image、
/// logo、favicon 等次级资源）均受此保护。
/// 设置 `SSRF_ALLOW_PRIVATE=1` 可跳过验证（集成测试用）。
pub struct SsrfSafeResolver {
    /// 受信任的出站代理主机名：解析它时跳过私网 IP 校验。代理由服务端 `PROXY_URL` 配置，
    /// 通常位于 docker 私网（如 `clash:7890` → 172.19.x），属可信目标，不应被 SSRF 防护误拦。
    proxy_host: Option<String>,
}

impl SsrfSafeResolver {
    /// `proxy_host`：从 `PROXY_URL` 解析出的代理主机名（无代理时为 `None`）。
    pub fn new(proxy_host: Option<String>) -> Self {
        Self { proxy_host }
    }
}

impl reqwest::dns::Resolve for SsrfSafeResolver {
    fn resolve(&self, name: reqwest::dns::Name) -> reqwest::dns::Resolving {
        let proxy_host = self.proxy_host.clone();
        Box::pin(async move {
            if std::env::var("SSRF_ALLOW_PRIVATE").ok().as_deref() == Some("1") {
                let addrs = tokio::net::lookup_host(format!("{}:0", name.as_str()))
                    .await
                    .map_err(|e| Box::new(e) as Box<dyn std::error::Error + Send + Sync>)?;
                return Ok(Box::new(addrs) as reqwest::dns::Addrs);
            }

            let host = name.as_str();

            // 受信任的代理主机跳过私网校验（代理是服务端配置，常驻 docker 私网）
            let is_trusted_proxy = proxy_host
                .as_deref()
                .is_some_and(|p| p.eq_ignore_ascii_case(host));

            // IP 字面量：直接校验，无需 DNS 查询
            if let Ok(ip) = host.parse::<std::net::IpAddr>() {
                if is_forbidden_ip(&ip) && !is_trusted_proxy {
                    return Err(Box::new(std::io::Error::new(
                        std::io::ErrorKind::PermissionDenied,
                        format!("SSRF blocked: {ip} is a forbidden address"),
                    ))
                        as Box<dyn std::error::Error + Send + Sync>);
                }
                let addr = std::net::SocketAddr::new(ip, 0);
                return Ok(Box::new(std::iter::once(addr)) as reqwest::dns::Addrs);
            }

            let addrs: Vec<std::net::SocketAddr> = tokio::net::lookup_host(format!("{host}:0"))
                .await
                .map_err(|e| Box::new(e) as Box<dyn std::error::Error + Send + Sync>)?
                .collect();

            if addrs.is_empty() {
                return Err(Box::new(std::io::Error::new(
                    std::io::ErrorKind::NotFound,
                    format!("DNS returned no addresses for {host}"),
                ))
                    as Box<dyn std::error::Error + Send + Sync>);
            }

            if !is_trusted_proxy {
                for addr in &addrs {
                    if is_forbidden_ip(&addr.ip()) {
                        return Err(Box::new(std::io::Error::new(
                            std::io::ErrorKind::PermissionDenied,
                            format!(
                                "SSRF blocked: {host} resolves to forbidden address {}",
                                addr.ip()
                            ),
                        ))
                            as Box<dyn std::error::Error + Send + Sync>);
                    }
                }
            }

            Ok(Box::new(addrs.into_iter()) as reqwest::dns::Addrs)
        })
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
            Err(ScrapeError::ForbiddenTarget(format!(
                "blocked ip literal: {ip}"
            )))
        } else {
            Ok(())
        };
    }

    let port = url.port_or_known_default().unwrap_or(443);
    let addrs = tokio::net::lookup_host(format!("{host}:{port}"))
        .await
        .map_err(|e| ScrapeError::FetchFailed(format!("DNS 解析 {host} 失败: {e}")))?;

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
        return Err(ScrapeError::FetchFailed(format!(
            "DNS 解析 {host} 没有返回任何地址"
        )));
    }
    Ok(())
}

/// Layer 1 一次 HTTP 抓取的产物。元数据解析由 [`crate::extract`] 另行完成。
#[derive(Debug)]
pub struct HttpCapture {
    pub html: String,
    /// 跟完重定向后的最终 URL，相对路径一律以它为基准
    pub final_url: reqwest::Url,
    /// 重定向链（不含最终 URL），按发生顺序
    pub redirects: Vec<(String, u16)>,
    pub http_status: u16,
    pub content_type: Option<String>,
    pub charset: Option<String>,
    pub byte_size: u64,
}

/// 桌面版 Chrome UA。未显式覆盖时用它，避免被按爬虫区别对待。
pub const DEFAULT_UA: &str = "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) \
     AppleWebKit/537.36 (KHTML, like Gecko) Chrome/136.0.0.0 Safari/537.36";

/// 顶层导航的 `Accept`，取自 Chrome 136。
///
/// reqwest 默认发的是 `accept: */*`——一个自称 Chrome 却这么要资源的请求，
/// 是 WAF 最容易识别的爬虫特征之一。
const DEFAULT_ACCEPT: &str = "text/html,application/xhtml+xml,application/xml;q=0.9,\
     image/avif,image/webp,image/apng,*/*;q=0.8";

/// 未指定 `render.locale` 时的 `Accept-Language`。
/// 一个语言偏好都不声明同样反常，浏览器永远会带。
const DEFAULT_ACCEPT_LANGUAGE: &str = "zh-CN,zh;q=0.9,en;q=0.8";

/// 客户端提示，必须与 [`DEFAULT_UA`] 里的 Chrome 大版本号保持一致——
/// 对不上正是"伪造 UA"的判定依据。
const SEC_CH_UA: &str = r#""Chromium";v="136", "Google Chrome";v="136", "Not.A/Brand";v="99""#;
const SEC_CH_UA_PLATFORM: &str = r#""macOS""#;

/// 最多跟随的重定向跳数。
const MAX_REDIRECTS: usize = 10;

/// 出错响应正文最多读这么多字节——只用来做诊断片段，不需要完整正文。
const MAX_ERROR_BODY_BYTES: usize = 16 * 1024;

/// 诊断片段最终保留的字符数。
const ERROR_SNIPPET_CHARS: usize = 300;

/// 值得"换个姿势再试一次"的状态码：站点应答正常，只是拒绝了这次请求。
///
/// 不含 429（限流下立刻重试只会更糟）和 5xx（站点自身故障，重试姿势帮不上忙）。
const RETRYABLE_ANTI_BOT: [u16; 3] = [403, 406, 412];

/// 一次"服务器答了，但不是 2xx"的完整现场。
///
/// 单独建模而不是塞进 `FetchFailed(String)`，是因为这两类失败的排障方向相反：
/// 传输层失败要查网络和 DNS，状态码失败要查请求本身长什么样。原来 reqwest 的
/// `error_for_status()` 只给一句 `HTTP status client error (412 ...) for url (...)`，
/// 既看不到重定向链，也看不到站点在正文里写了什么原因。
#[derive(Debug)]
pub struct HttpErrorDetail {
    /// 最初请求的 URL（重定向前）
    pub requested_url: String,
    /// 真正返回这个状态码的 URL
    pub final_url: String,
    /// 走到这里经过的重定向链
    pub redirects: Vec<(String, u16)>,
    pub status: u16,
    pub content_type: Option<String>,
    /// 响应的 `Server` 头，用于识别 WAF（如 `cloudflare`、`Tengine`）
    pub server: Option<String>,
    /// 正文里的可读片段：拦截页往往会写明原因
    pub body_snippet: Option<String>,
    /// 是否已经做过根路径预热重试（见 [`warm_up_origin`]）
    pub warmed_up: bool,
    /// 无头浏览器回退的结局，`None` 表示没走到那一步。
    ///
    /// 由 AUTO 模式的调用方回填（见 `main.rs`）：Layer 1 被反爬拦下后会改用 Layer 2
    /// 再试，只有那一次也没成功才会把本错误报出去——不写明这一步，运维会以为我们
    /// 从没试过浏览器。
    pub headless_retry: Option<String>,
}

impl HttpErrorDetail {
    /// 消费响应，读出状态、关键响应头与正文片段。
    async fn capture(
        requested_url: &str,
        final_url: &reqwest::Url,
        redirects: &[(String, u16)],
        response: reqwest::Response,
        warmed_up: bool,
    ) -> Self {
        let status = response.status().as_u16();
        let header = |name: reqwest::header::HeaderName| {
            response
                .headers()
                .get(name)
                .and_then(|v| v.to_str().ok())
                .map(str::to_string)
        };
        let content_type = header(reqwest::header::CONTENT_TYPE);
        let server = header(reqwest::header::SERVER);
        // 正文读失败无所谓——它只是诊断信息，不能让"读不到正文"盖掉真正的状态码错误
        let body_snippet = read_body_capped(response, MAX_ERROR_BODY_BYTES)
            .await
            .ok()
            .and_then(|bytes| text_snippet(&bytes, ERROR_SNIPPET_CHARS));

        Self {
            requested_url: requested_url.to_string(),
            final_url: final_url.to_string(),
            redirects: redirects.to_vec(),
            status,
            content_type,
            server,
            body_snippet,
            warmed_up,
            headless_retry: None,
        }
    }

    /// 这次拦截是否值得做一轮根路径预热后重试。
    ///
    /// 根路径本身被拒就没得救了（预热请求的就是它），所以只对带 path 的 URL 生效。
    fn worth_warming_up(&self) -> bool {
        RETRYABLE_ANTI_BOT.contains(&self.status)
            && !self.warmed_up
            && reqwest::Url::parse(&self.requested_url).is_ok_and(|u| u.path() != "/")
    }

    /// 这次拒绝是不是"站点连得通，只是不认这次请求"——即值得换个抓取姿势重来。
    ///
    /// 与 [`worth_warming_up`](Self::worth_warming_up) 的区别在于不限 path：根路径被
    /// 反爬拦下时预热无从谈起（预热请求的就是它），但换无头浏览器仍然有戏。
    pub fn is_anti_bot(&self) -> bool {
        RETRYABLE_ANTI_BOT.contains(&self.status)
    }

    /// 面向人的一行诊断，最终会经 API 原样透出到管理后台。
    pub fn describe(&self) -> String {
        let reason = reqwest::StatusCode::from_u16(self.status)
            .ok()
            .and_then(|s| s.canonical_reason())
            .unwrap_or("Unknown");
        let mut out = format!("目标站点返回 HTTP {} {reason}", self.status);

        let mut parts: Vec<String> = Vec::new();
        if self.final_url != self.requested_url {
            parts.push(format!(
                "请求 {} 最终落在 {}",
                self.requested_url, self.final_url
            ));
        } else {
            parts.push(format!("请求 {}", self.requested_url));
        }
        if !self.redirects.is_empty() {
            let chain = self
                .redirects
                .iter()
                .map(|(u, s)| format!("{s} {u}"))
                .collect::<Vec<_>>()
                .join(" → ");
            parts.push(format!("重定向 {} 跳: {chain}", self.redirects.len()));
        }
        if let Some(server) = &self.server {
            parts.push(format!("server={server}"));
        }
        if let Some(ct) = &self.content_type {
            parts.push(format!("content-type={ct}"));
        }
        if self.warmed_up {
            parts.push("已做根路径 cookie 预热并重试，仍被拒".to_string());
        }
        if let Some(outcome) = &self.headless_retry {
            parts.push(format!("已回退无头浏览器重试: {outcome}"));
        }
        if let Some(hint) = status_hint(self.status) {
            parts.push(hint.to_string());
        }
        if let Some(snippet) = &self.body_snippet {
            parts.push(format!("响应正文: {snippet}"));
        }

        out.push_str(" (");
        out.push_str(&parts.join("; "));
        out.push(')');
        out
    }
}

/// 按状态码给一句"该往哪个方向查"的提示。
fn status_hint(status: u16) -> Option<&'static str> {
    match status {
        401 | 403 | 406 | 412 => Some(
            "疑似反爬/风控拦截——站点是连得通的，它主动拒绝了本次请求。\
             常见原因：请求指纹不像浏览器、缺少站点下发的 cookie、出口 IP 信誉低",
        ),
        429 => Some("触发目标站点限流，应降低抓取频率后再试"),
        404 | 410 => Some("目标页面不存在，多半是链接本身已失效"),
        451 => Some("目标站点以法律原因拒绝提供该内容"),
        500..=599 => Some("目标站点自身故障，与本次请求的姿势无关"),
        _ => None,
    }
}

/// 把 HTML 错误页压成一行可读片段：跳过 script/style、去标签、合并空白、截断。
///
/// 拦截页正文常常九成是脚本，直接截前 N 个字符只会得到一堆 JS。
fn text_snippet(bytes: &[u8], max_chars: usize) -> Option<String> {
    let raw = String::from_utf8_lossy(bytes);
    let lower = raw.to_ascii_lowercase();
    let mut text = String::new();
    let mut i = 0usize;

    while i < raw.len() {
        let Some(lt) = lower[i..].find('<').map(|p| i + p) else {
            text.push_str(&raw[i..]);
            break;
        };
        text.push_str(&raw[i..lt]);
        // 标签换成空格，否则 `</h1><p>` 会把前后两段文字粘成一个词
        text.push(' ');

        // <script>/<style> 整块跳过，连同它们的正文
        let block = ["script", "style"]
            .into_iter()
            .find(|tag| lower[lt..].starts_with(&format!("<{tag}")));
        // 无论哪种情况都先越过本标签的 '>'
        i = match lower[lt..].find('>') {
            Some(p) => lt + p + 1,
            None => raw.len(),
        };
        if let Some(tag) = block {
            i = match lower[i..].find(&format!("</{tag}")) {
                // 闭合标签自身也一并吃掉，靠下一轮循环处理它就行
                Some(p) => i + p,
                None => raw.len(),
            };
        }
    }

    let collapsed = text.split_whitespace().collect::<Vec<_>>().join(" ");
    if collapsed.is_empty() {
        return None;
    }
    let truncated: String = collapsed.chars().take(max_chars).collect();
    Some(if truncated.chars().count() < collapsed.chars().count() {
        format!("{truncated}…")
    } else {
        truncated
    })
}

/// 图片子资源的 `Accept`，取自 Chrome 136 发出的 `<img>` 请求。
pub const ACCEPT_IMAGE: &str = "image/avif,image/webp,image/apng,image/svg+xml,image/*,*/*;q=0.8";

/// Web App Manifest 的 `Accept`。
pub const ACCEPT_MANIFEST: &str = "application/manifest+json,application/json,*/*;q=0.8";

/// 每个请求都该有的身份头：UA + 客户端提示。
///
/// `Sec-CH-UA` 只在安全上下文里发，所以 http 目标要跟着省略，否则又成了另一种不一致。
fn apply_identity_headers(
    req: reqwest::RequestBuilder,
    url: &reqwest::Url,
    user_agent: &str,
) -> reqwest::RequestBuilder {
    // Accept-Encoding 交给 reqwest 的 gzip/brotli/deflate 特性自动带上并解压，
    // 手工设置反而会关掉自动解压，让我们拿到一堆压缩字节。
    let req = req.header(reqwest::header::USER_AGENT, user_agent);
    if url.scheme() == "https" {
        req.header("sec-ch-ua", SEC_CH_UA)
            .header("sec-ch-ua-mobile", "?0")
            .header("sec-ch-ua-platform", SEC_CH_UA_PLATFORM)
    } else {
        req
    }
}

/// `from` 发起、指向 `to` 的请求，`Sec-Fetch-Site` 该报什么。
///
/// 只区分 `same-origin` / `cross-site`：浏览器还有个 `same-site`（同注册域不同子域），
/// 判定它需要一份公共后缀表，为一个诊断性的头引入 PSL 依赖不划算。
fn sec_fetch_site(from: &reqwest::Url, to: &reqwest::Url) -> &'static str {
    if from.origin() == to.origin() {
        "same-origin"
    } else {
        "cross-site"
    }
}

/// 按浏览器默认的 `strict-origin-when-cross-origin` 策略算出 `Referer`。
///
/// 同源给完整页面 URL，跨源只给源，https→http 降级则不发。
///
/// 关键是它必须指向**声明这个资源的页面**：防盗链白名单校验的正是这个头。填资源
/// 自己的域名等于告诉 CDN"我是被你自己引用的"，那个值当然不在白名单里。
pub fn referer_for(page: &reqwest::Url, resource: &reqwest::Url) -> Option<String> {
    if page.scheme() == "https" && resource.scheme() != "https" {
        return None;
    }
    if page.origin() == resource.origin() {
        let mut full = page.clone();
        full.set_fragment(None);
        Some(full.to_string())
    } else {
        Some(format!("{}/", page.origin().ascii_serialization()))
    }
}

/// 给一次顶层导航（Layer 1 取页面）装上完整的浏览器请求头。
///
/// 只发 UA 而不发其余头，等于自曝是脚本。`Sec-Fetch-*` 这组头 JS 覆盖不了，
/// 因此是 WAF 判定"这是不是真实导航"的主要依据。
fn apply_navigation_headers(
    req: reqwest::RequestBuilder,
    url: &reqwest::Url,
    user_agent: &str,
    locale: &str,
    sec_fetch_site: &str,
    referer: Option<&str>,
) -> reqwest::RequestBuilder {
    use reqwest::header::{ACCEPT, ACCEPT_LANGUAGE, REFERER};

    let mut req = apply_identity_headers(req, url, user_agent)
        .header(ACCEPT, DEFAULT_ACCEPT)
        .header(ACCEPT_LANGUAGE, locale)
        .header("upgrade-insecure-requests", "1")
        .header("sec-fetch-dest", "document")
        .header("sec-fetch-mode", "navigate")
        .header("sec-fetch-site", sec_fetch_site)
        .header("sec-fetch-user", "?1");

    if let Some(referer) = referer {
        req = req.header(REFERER, referer);
    }
    req
}

/// 给一次子资源请求（图片、manifest）装上浏览器请求头。
///
/// 和导航**不是同一套**：`Sec-Fetch-Dest: document` 配一张图片是自相矛盾的，
/// 恰恰是"照抄了导航头的脚本"的特征。`dest` 取 `"image"` 或 `"manifest"`，
/// 对应的 `Sec-Fetch-Mode` 随之而定（图片走 no-cors，manifest 走 cors 并带 `Origin`）。
pub fn apply_subresource_headers(
    req: reqwest::RequestBuilder,
    resource: &reqwest::Url,
    page: &reqwest::Url,
    dest: &str,
    accept: &str,
    user_agent: Option<&str>,
    locale: Option<&str>,
) -> reqwest::RequestBuilder {
    use reqwest::header::{ACCEPT, ACCEPT_LANGUAGE, ORIGIN, REFERER};

    let cors = dest == "manifest";
    let mut req = apply_identity_headers(req, resource, user_agent.unwrap_or(DEFAULT_UA))
        .header(ACCEPT, accept)
        .header(ACCEPT_LANGUAGE, locale.unwrap_or(DEFAULT_ACCEPT_LANGUAGE))
        .header("sec-fetch-dest", dest)
        .header("sec-fetch-mode", if cors { "cors" } else { "no-cors" })
        .header("sec-fetch-site", sec_fetch_site(page, resource));

    if let Some(referer) = referer_for(page, resource) {
        req = req.header(REFERER, referer);
    }
    if cors {
        req = req.header(ORIGIN, page.origin().ascii_serialization());
    }
    req
}

/// 一次活性探测的**事实**。
///
/// 这里刻意没有 `alive` —— "什么状态码算死"是 Bookmarkify 的判断，不是网页的属性，
/// 和 `extractor`(事实) / `role`(策略) 的分工完全一致。判死规则在 API 侧的
/// `LivenessPolicy.outcomeOf`，改规则不需要动这个服务，也不需要重新探测。
#[derive(Debug, Clone)]
pub struct ProbeOutcome {
    /// 是否拿到了目标站点的 HTTP 响应。`false` 表示传输层就没成功（DNS/连接/TLS/超时）
    pub reachable: bool,
    /// 最终一跳的状态码；[`Self::reachable`] 为 false 时无意义
    pub status: Option<u16>,
    /// 本次探测被**我方**的 SSRF 策略拒绝了。
    ///
    /// 必须与 `reachable = false` 区分开：那是"站点连不上"，这是"我们没去连"。
    /// 把后者报成前者，等于用一个我方的安全决策去给用户的书签判死 —— `/scrape`
    /// 侧用 `FORBIDDEN_TARGET` 与 `FETCH_FAILED` 两个错误码守住了这条界线。
    pub blocked: bool,
    /// 实际用到的方法：`"HEAD"`，或对 HEAD 不支持的服务器回退成的 `"GET"`
    pub method: &'static str,
    /// 跟随了几跳重定向
    pub redirects: u8,
}

impl ProbeOutcome {
    fn transport_failure(method: &'static str, redirects: u8) -> Self {
        Self {
            reachable: false,
            status: None,
            blocked: false,
            method,
            redirects,
        }
    }

    fn forbidden(method: &'static str, redirects: u8) -> Self {
        Self {
            reachable: false,
            status: None,
            blocked: true,
            method,
            redirects,
        }
    }
}

/// 活性探测：发 HEAD、逐跳跟随重定向，报告最终状态码。
///
/// 与 [`fetch_html`] 的区别是它**不读正文**，因此便宜得多，可以按小时级的频率跑全表。
///
/// 三个和"能不能正确判死"直接相关的细节：
///
/// 1. **必须跟随重定向。** `http://x.com/a` → 301 → `https://x.com/a` → 404 是最常见的
///    形态；只看首跳会把它报成 301，于是一个已经消失的页面被判成存活。共享客户端配的是
///    `redirect::Policy::none()`（`fetch_html` 需要逐跳现场），所以这里同样手动跟随，
///    并对每一跳重新做 SSRF 校验 —— 重定向目标是站点控制的输入。
/// 2. **HEAD 被拒时回退 GET。** 一台对 HEAD 一律回 405 的服务器会把该站所有页面的状态码
///    抹平成同一个值，而 405 与 404 的差别恰恰就是"这个页面还在不在"。回退之后才敢按
///    状态码判死。
/// 3. **只报事实。** 状态码原样上报，不在这里折叠成布尔 —— 见 [`ProbeOutcome`]。
pub async fn probe(url: &reqwest::Url, client: &reqwest::Client) -> ProbeOutcome {
    let mut current = url.clone();
    let mut prev_origin: Option<reqwest::Url> = None;
    let mut redirects: u8 = 0;
    // 一旦回退到 GET 就保持 GET：既然这台服务器不认 HEAD，后续每一跳也一样不认
    let mut method = "HEAD";

    loop {
        if validate_url_scheme(&current).is_err() {
            return ProbeOutcome::forbidden(method, redirects);
        }
        if let Err(e) = validate_target_host(&current).await {
            tracing::info!(url = %current, ?e, "probe rejected: forbidden target");
            return ProbeOutcome::forbidden(method, redirects);
        }

        let sec_fetch_site = match &prev_origin {
            None => "none",
            Some(prev) if prev.origin() == current.origin() => "same-origin",
            Some(_) => "cross-site",
        };
        let builder = if method == "HEAD" {
            client.head(current.clone())
        } else {
            client.get(current.clone())
        };
        let req = apply_navigation_headers(
            builder,
            &current,
            DEFAULT_UA,
            DEFAULT_ACCEPT_LANGUAGE,
            sec_fetch_site,
            None,
        );

        let response = match req.send().await {
            Ok(resp) => resp,
            Err(e) => {
                tracing::debug!(url = %current, "probe transport error: {e}");
                return ProbeOutcome::transport_failure(method, redirects);
            }
        };
        let status = response.status();

        // 同一个 URL 换 GET 重来。响应体不读，`response` 出作用域即丢弃，不会真的把正文拉下来。
        //
        // 两类状态码要回退，理由不同：
        //
        // - **405/501**：服务器明说不认这个方法。不回退的话，该站所有页面的状态码会被抹平成
        //   同一个值，而"这个页面还在不在"的判断全靠状态码。
        // - **404/410**：这是**唯一会导致书签被判死**的一类结论，所以它必须由真实用户会用的
        //   方法来确认。线上实测：`xiaohongshu.com` 对 HEAD 回 404，对 GET 却是 200（跳两次
        //   到 `/explore`）—— 页面活得好好的，404 纯粹是 HEAD 在那条重定向链上没被正确处理。
        //   只信 HEAD 的话，一个健康的书签会被静默判死，而且没有任何症状可循。
        //   HEAD 只是省流量的优化，不该有权单独下达那个不可逆的结论。
        if method == "HEAD" && matches!(status.as_u16(), 404 | 405 | 410 | 501) {
            tracing::debug!(url = %current, status = status.as_u16(), "HEAD 结论存疑，改用 GET 复核");
            method = "GET";
            continue;
        }

        if status.is_redirection() && (redirects as usize) < MAX_REDIRECTS {
            let location = response
                .headers()
                .get(reqwest::header::LOCATION)
                .and_then(|v| v.to_str().ok())
                .and_then(|loc| current.join(loc).ok());
            if let Some(next) = location {
                prev_origin = Some(current);
                current = next;
                redirects += 1;
                continue;
            }
            // 3xx 但没有可用的 Location：这是站点自己的事实，原样上报
        }

        return ProbeOutcome {
            reachable: true,
            status: Some(status.as_u16()),
            blocked: false,
            method,
            redirects,
        };
    }
}

/// 通过普通 HTTP 请求（Layer 1）取回页面 HTML。
///
/// **手动跟随重定向**而不用 reqwest 的自动跟随：契约要把整条重定向链报给调用方
/// （识别域名停靠、http→https 升级、追踪跳转都要靠它），而自动跟随只会给出最终 URL。
/// 每一跳都重新跑一次 SSRF 校验 —— 共享客户端的 `SsrfSafeResolver` 已在 DNS 层兜底，
/// 这里再挡一道，顺便让被拦下的那一跳能给出明确原因。
///
/// 被反爬拦下时（见 [`RETRYABLE_ANTI_BOT`]）会做一轮**根路径预热**后重试，见
/// [`warm_up_origin`]。
///
/// `client` 必须同时配置为 `redirect::Policy::none()`（否则拿不到中间跳）和
/// `cookie_store(true)`（否则预热拿到的 cookie 留不住，重试等于白做）。
pub async fn fetch_html(
    url: &str,
    client: &reqwest::Client,
    user_agent: Option<&str>,
    locale: Option<&str>,
) -> Result<HttpCapture, ScrapeError> {
    let first = fetch_html_once(url, client, user_agent, locale, None).await;

    let Err(ScrapeError::HttpStatus(detail)) = first else {
        return first;
    };
    if !detail.worth_warming_up() {
        return Err(ScrapeError::HttpStatus(detail));
    }

    let Some(origin_root) = warm_up_origin(url, client, user_agent, locale).await else {
        return Err(ScrapeError::HttpStatus(detail));
    };
    tracing::info!(
        url,
        status = detail.status,
        "anti-bot status, retrying after origin warm-up"
    );
    fetch_html_once(url, client, user_agent, locale, Some(&origin_root)).await
}

/// 反爬预热：先访问站点根路径，把它下发的 cookie 收进 jar，再让调用方重试目标 URL。
///
/// B 站是最典型的例子：`/` 永远 200 并在响应里下发 `buvid3`/`b_nut`，而
/// `/video/BVxxx` 这类内容页在没有这些 cookie 时直接 412。真实浏览器天然满足这个
/// 前提（用户总是先到过站内某处），裸抓不会——所以这里补上那一步。
///
/// 返回根路径 URL 供重试时作 `Referer`；预热本身失败则返回 `None`（照原错误报出去，
/// 预热是尽力而为的补救，不该制造新的失败原因）。
async fn warm_up_origin(
    url: &str,
    client: &reqwest::Client,
    user_agent: Option<&str>,
    locale: Option<&str>,
) -> Option<String> {
    let mut root = reqwest::Url::parse(url).ok()?;
    root.set_path("/");
    root.set_query(None);
    root.set_fragment(None);

    validate_target_host(&root).await.ok()?;
    let req = apply_navigation_headers(
        client.get(root.clone()),
        &root,
        user_agent.unwrap_or(DEFAULT_UA),
        locale.unwrap_or(DEFAULT_ACCEPT_LANGUAGE),
        "none",
        None,
    );
    match req.send().await {
        Ok(resp) => {
            tracing::debug!(root = %root, status = resp.status().as_u16(), "warm-up done");
            Some(root.to_string())
        }
        Err(e) => {
            tracing::debug!(root = %root, "warm-up failed: {e}");
            None
        }
    }
}

/// 单趟抓取：解析 → 逐跳跟随重定向 → 读正文。`referer` 非空表示这是预热后的重试。
async fn fetch_html_once(
    url: &str,
    client: &reqwest::Client,
    user_agent: Option<&str>,
    locale: Option<&str>,
    referer: Option<&str>,
) -> Result<HttpCapture, ScrapeError> {
    let mut current = reqwest::Url::parse(url).map_err(|_| ScrapeError::InvalidUrl)?;
    let mut redirects: Vec<(String, u16)> = Vec::new();
    let user_agent = user_agent.unwrap_or(DEFAULT_UA);
    let locale = locale.unwrap_or(DEFAULT_ACCEPT_LANGUAGE);

    for _ in 0..=MAX_REDIRECTS {
        validate_url_scheme(&current)?;
        validate_target_host(&current).await?;

        // 首跳来自"地址栏/书签"，故 sec-fetch-site: none；重定向跳则按与上一跳的
        // 同源关系上报，跟浏览器一致
        let sec_fetch_site = match redirects.last() {
            None => "none",
            Some((prev, _)) => match reqwest::Url::parse(prev) {
                Ok(prev) if prev.origin() == current.origin() => "same-origin",
                _ => "cross-site",
            },
        };
        let req = apply_navigation_headers(
            client.get(current.clone()),
            &current,
            user_agent,
            locale,
            sec_fetch_site,
            // 浏览器不会凭空给重定向加 Referer，所以只在首跳带预热来源
            referer.filter(|_| redirects.is_empty()),
        );

        let response = req.send().await.map_err(|e| {
            if e.is_timeout() {
                ScrapeError::Timeout(format!("请求 {current} 超时: {e}"))
            } else {
                ScrapeError::FetchFailed(format!("请求 {current} 失败: {e}"))
            }
        })?;

        let status = response.status();

        // 3xx 且带 Location：记一跳，继续
        if status.is_redirection() {
            let location = response
                .headers()
                .get(reqwest::header::LOCATION)
                .and_then(|v| v.to_str().ok())
                .map(str::to_string);
            if let Some(loc) = location {
                let next = current.join(&loc).map_err(|_| {
                    ScrapeError::FetchFailed(format!("{current} 的重定向目标非法: {loc}"))
                })?;
                redirects.push((current.to_string(), status.as_u16()));
                current = next;
                continue;
            }
        }

        if !status.is_success() {
            let detail =
                HttpErrorDetail::capture(url, &current, &redirects, response, referer.is_some())
                    .await;
            return Err(ScrapeError::HttpStatus(Box::new(detail)));
        }

        let content_type = response
            .headers()
            .get(reqwest::header::CONTENT_TYPE)
            .and_then(|v| v.to_str().ok())
            .map(str::to_string);
        let charset = content_type.as_deref().and_then(charset_of);

        let bytes = read_body_capped(response, MAX_HTML_BYTES)
            .await
            .map_err(|e| ScrapeError::FetchFailed(format!("读取 {current} 响应体失败: {e}")))?;
        let byte_size = bytes.len() as u64;

        return Ok(HttpCapture {
            html: String::from_utf8_lossy(&bytes).into_owned(),
            final_url: current,
            redirects,
            http_status: status.as_u16(),
            content_type,
            charset,
            byte_size,
        });
    }

    Err(ScrapeError::FetchFailed(format!(
        "{url} 重定向超过 {MAX_REDIRECTS} 跳仍未到达终点"
    )))
}

/// 从 `Content-Type` 里取 `charset=` 参数。
fn charset_of(content_type: &str) -> Option<String> {
    content_type.split(';').find_map(|part| {
        let part = part.trim();
        part.strip_prefix("charset=")
            .or_else(|| part.strip_prefix("Charset="))
            .map(|c| c.trim_matches('"').to_ascii_lowercase())
    })
}

#[cfg(test)]
mod tests {
    use super::*;

    fn detail(status: u16, requested: &str) -> HttpErrorDetail {
        HttpErrorDetail {
            requested_url: requested.to_string(),
            final_url: requested.to_string(),
            redirects: Vec::new(),
            status,
            content_type: None,
            server: None,
            body_snippet: None,
            warmed_up: false,
            headless_retry: None,
        }
    }

    #[test]
    fn snippet_skips_script_and_style_bodies() {
        let html = r#"<html><head><style>body{color:red}</style>
            <script>var a = "not text at all";</script></head>
            <body><h1>访问被拒绝</h1><p>请求 ID: abc123</p></body></html>"#;
        let s = text_snippet(html.as_bytes(), 300).unwrap();
        assert_eq!(
            s, "访问被拒绝 请求 ID: abc123",
            "脚本/样式正文不该混进诊断片段"
        );
    }

    #[test]
    fn snippet_truncates_with_ellipsis() {
        let body = "x".repeat(50);
        let s = text_snippet(body.as_bytes(), 10).unwrap();
        assert_eq!(s, "xxxxxxxxxx…");
    }

    #[test]
    fn snippet_is_none_when_body_has_no_text() {
        assert!(text_snippet(b"<html><body></body></html>", 300).is_none());
    }

    #[test]
    fn describe_carries_status_reason_and_hint() {
        let mut d = detail(412, "https://www.bilibili.com/video/BV1");
        d.server = Some("nginx".to_string());
        d.redirects = vec![("https://bilibili.com/video/BV1".to_string(), 301)];
        let msg = d.describe();
        assert!(msg.contains("HTTP 412 Precondition Failed"), "{msg}");
        assert!(msg.contains("疑似反爬"), "{msg}");
        assert!(
            msg.contains("301 https://bilibili.com/video/BV1"),
            "重定向链应可见: {msg}"
        );
        assert!(msg.contains("server=nginx"), "{msg}");
    }

    #[test]
    fn describe_marks_a_failed_warm_up_retry() {
        let mut d = detail(403, "https://example.com/a");
        d.warmed_up = true;
        assert!(
            d.describe().contains("预热"),
            "重试过就该说明，否则看不出已经试过了"
        );
    }

    #[test]
    fn warm_up_only_for_anti_bot_statuses_on_sub_paths() {
        assert!(detail(412, "https://a.com/video/x").worth_warming_up());
        assert!(detail(403, "https://a.com/video/x").worth_warming_up());
        // 根路径就是预热要请求的那个 URL，重试没有意义
        assert!(!detail(412, "https://a.com/").worth_warming_up());
        // 限流下立刻重试只会更糟
        assert!(!detail(429, "https://a.com/video/x").worth_warming_up());
        // 站点自身故障，换姿势没用
        assert!(!detail(503, "https://a.com/video/x").worth_warming_up());
        assert!(!detail(404, "https://a.com/video/x").worth_warming_up());
    }

    #[test]
    fn warm_up_happens_at_most_once() {
        let mut d = detail(412, "https://a.com/video/x");
        d.warmed_up = true;
        assert!(!d.worth_warming_up());
    }

    #[test]
    fn describe_marks_a_failed_headless_retry() {
        let mut d = detail(412, "https://www.bilibili.com/video/BV1");
        d.headless_retry = Some("导航返回 HTTP 412".to_string());
        let msg = d.describe();
        assert!(msg.contains("无头浏览器"), "回退过 Layer 2 就该说明: {msg}");
        assert!(msg.contains("导航返回 HTTP 412"), "{msg}");
    }

    #[test]
    fn anti_bot_covers_the_retryable_statuses_on_any_path() {
        for status in RETRYABLE_ANTI_BOT {
            assert!(
                detail(status, "https://a.com/video/x").is_anti_bot(),
                "{status}"
            );
            // 预热在根路径上没意义，但换无头浏览器仍然有戏
            assert!(detail(status, "https://a.com/").is_anti_bot(), "{status}");
        }
        assert!(!detail(429, "https://a.com/video/x").is_anti_bot());
        assert!(!detail(503, "https://a.com/video/x").is_anti_bot());
        assert!(!detail(404, "https://a.com/video/x").is_anti_bot());
    }

    fn url(s: &str) -> reqwest::Url {
        reqwest::Url::parse(s).unwrap()
    }

    #[test]
    fn referer_is_the_full_page_url_when_same_origin() {
        let r = referer_for(
            &url("https://a.com/post/1?x=2#frag"),
            &url("https://a.com/i.png"),
        );
        assert_eq!(
            r.as_deref(),
            Some("https://a.com/post/1?x=2"),
            "同源给完整 URL，去掉 fragment"
        );
    }

    #[test]
    fn referer_is_origin_only_when_cross_origin() {
        // 防盗链白名单认的是**引用页**的域，所以这里必须是 a.com，不是 CDN 自己
        let r = referer_for(
            &url("https://a.com/post/1"),
            &url("https://cdn.b.com/i.png"),
        );
        assert_eq!(r.as_deref(), Some("https://a.com/"));
    }

    #[test]
    fn referer_is_dropped_on_https_to_http_downgrade() {
        assert_eq!(
            referer_for(&url("https://a.com/p"), &url("http://a.com/i.png")),
            None
        );
    }

    #[test]
    fn sec_fetch_site_distinguishes_origins() {
        assert_eq!(
            sec_fetch_site(&url("https://a.com/p"), &url("https://a.com/i.png")),
            "same-origin"
        );
        assert_eq!(
            sec_fetch_site(&url("https://a.com/p"), &url("https://b.com/i.png")),
            "cross-site"
        );
        // 端口不同即不同源
        assert_eq!(
            sec_fetch_site(&url("https://a.com/p"), &url("https://a.com:8443/i.png")),
            "cross-site"
        );
    }

    #[test]
    fn sec_ch_ua_version_matches_the_default_ua() {
        assert!(DEFAULT_UA.contains("Chrome/136."));
        assert!(SEC_CH_UA.contains(r#""Google Chrome";v="136""#));
    }

    #[test]
    fn validate_url_scheme_rejects_file_scheme() {
        let url = reqwest::Url::parse("file:///etc/passwd").unwrap();
        assert!(matches!(
            validate_url_scheme(&url),
            Err(ScrapeError::InvalidUrl)
        ));
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
        let _env = crate::env_guard().await;
        std::env::remove_var("SSRF_ALLOW_PRIVATE");
        let url = reqwest::Url::parse("http://127.0.0.1:8080/").unwrap();
        let r = validate_target_host(&url).await;
        assert!(
            matches!(r, Err(ScrapeError::ForbiddenTarget(_))),
            "got {r:?}"
        );
    }

    #[tokio::test]
    async fn validate_target_host_blocks_aws_metadata_literal() {
        let _env = crate::env_guard().await;
        std::env::remove_var("SSRF_ALLOW_PRIVATE");
        let url = reqwest::Url::parse("http://169.254.169.254/").unwrap();
        let r = validate_target_host(&url).await;
        assert!(
            matches!(r, Err(ScrapeError::ForbiddenTarget(_))),
            "got {r:?}"
        );
    }
}
