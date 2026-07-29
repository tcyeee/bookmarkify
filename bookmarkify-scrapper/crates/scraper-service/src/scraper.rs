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
                    )) as Box<dyn std::error::Error + Send + Sync>);
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
                )) as Box<dyn std::error::Error + Send + Sync>);
            }

            if !is_trusted_proxy {
                for addr in &addrs {
                    if is_forbidden_ip(&addr.ip()) {
                        return Err(Box::new(std::io::Error::new(
                            std::io::ErrorKind::PermissionDenied,
                            format!("SSRF blocked: {host} resolves to forbidden address {}", addr.ip()),
                        )) as Box<dyn std::error::Error + Send + Sync>);
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

/// 最多跟随的重定向跳数。
const MAX_REDIRECTS: usize = 10;

/// 通过普通 HTTP 请求（Layer 1）取回页面 HTML。
///
/// **手动跟随重定向**而不用 reqwest 的自动跟随：契约要把整条重定向链报给调用方
/// （识别域名停靠、http→https 升级、追踪跳转都要靠它），而自动跟随只会给出最终 URL。
/// 每一跳都重新跑一次 SSRF 校验 —— 共享客户端的 `SsrfSafeResolver` 已在 DNS 层兜底，
/// 这里再挡一道，顺便让被拦下的那一跳能给出明确原因。
///
/// `client` 必须配置为 `redirect::Policy::none()`，否则拿不到中间跳。
pub async fn fetch_html(
    url: &str,
    client: &reqwest::Client,
    user_agent: Option<&str>,
    locale: Option<&str>,
) -> Result<HttpCapture, ScrapeError> {
    let mut current = reqwest::Url::parse(url).map_err(|_| ScrapeError::InvalidUrl)?;
    let mut redirects: Vec<(String, u16)> = Vec::new();

    for _ in 0..=MAX_REDIRECTS {
        validate_url_scheme(&current)?;
        validate_target_host(&current).await?;

        let mut req = client
            .get(current.clone())
            .header(reqwest::header::USER_AGENT, user_agent.unwrap_or(DEFAULT_UA));
        if let Some(locale) = locale {
            req = req.header(reqwest::header::ACCEPT_LANGUAGE, locale);
        }

        let response = req.send().await.map_err(|e| {
            if e.is_timeout() {
                ScrapeError::Timeout
            } else {
                ScrapeError::FetchFailed(e.to_string())
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
                let next = current
                    .join(&loc)
                    .map_err(|_| ScrapeError::FetchFailed(format!("bad redirect target: {loc}")))?;
                redirects.push((current.to_string(), status.as_u16()));
                current = next;
                continue;
            }
        }

        let response = response
            .error_for_status()
            .map_err(|e| ScrapeError::FetchFailed(e.to_string()))?;

        let content_type = response
            .headers()
            .get(reqwest::header::CONTENT_TYPE)
            .and_then(|v| v.to_str().ok())
            .map(str::to_string);
        let charset = content_type.as_deref().and_then(charset_of);

        let bytes = read_body_capped(response, MAX_HTML_BYTES)
            .await
            .map_err(ScrapeError::FetchFailed)?;
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
        "too many redirects (>{MAX_REDIRECTS})"
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
        let _env = crate::env_guard().await;
        std::env::remove_var("SSRF_ALLOW_PRIVATE");
        let url = reqwest::Url::parse("http://127.0.0.1:8080/").unwrap();
        let r = validate_target_host(&url).await;
        assert!(matches!(r, Err(ScrapeError::ForbiddenTarget(_))), "got {r:?}");
    }

    #[tokio::test]
    async fn validate_target_host_blocks_aws_metadata_literal() {
        let _env = crate::env_guard().await;
        std::env::remove_var("SSRF_ALLOW_PRIVATE");
        let url = reqwest::Url::parse("http://169.254.169.254/").unwrap();
        let r = validate_target_host(&url).await;
        assert!(matches!(r, Err(ScrapeError::ForbiddenTarget(_))), "got {r:?}");
    }
}
