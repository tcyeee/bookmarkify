mod cache;
mod headless;
mod oss;
mod scraper;

use axum::{
    error_handling::HandleErrorLayer,
    extract::{Request, State},
    http::{header::AUTHORIZATION, StatusCode},
    middleware::{self, Next},
    response::{IntoResponse, Json, Response},
    routing::{get, post},
    Router,
};
use cache::ScrapeCache;
use serde::{Deserialize, Serialize};
use std::{env, sync::Arc, time::{Duration, Instant}};
use subtle::ConstantTimeEq;
use tower::ServiceBuilder;
use tower_http::trace::TraceLayer;

/// 全局应用状态，通过 `Arc` 在所有请求处理器之间共享。
#[derive(Clone)]
struct AppState {
    /// 共享的 HTTP 客户端，内置连接池和超时配置
    client: reqwest::Client,
    /// 无头浏览器单次抓取的最大等待时间（秒），对应环境变量 `HEADLESS_TIMEOUT_SECS`
    headless_timeout_secs: u64,
    /// 网络空闲等待时间（秒），对应环境变量 `HEADLESS_IDLE_WAIT_SECS`
    headless_idle_wait_secs: u64,
    /// 基于 URL 的抓取结果内存缓存
    cache: Arc<ScrapeCache>,
    /// OSS 客户端，用于将截图和图片上传到对象存储（可选）
    oss: Option<Arc<oss::OssClient>>,
    /// 共享密钥鉴权 token，对应环境变量 `SCRAPER_AUTH_TOKEN`。
    /// `None` 时 `/scrape`、`/ping` 不做鉴权（本地开发默认状态）。
    auth_token: Option<Arc<String>>,
}

/// 服务入口：读取环境变量、构建路由并启动 HTTP 服务器。
///
/// ## 环境变量
/// | 变量名 | 默认值 | 说明 |
/// |---|---|---|
/// | `REQUEST_TIMEOUT_SECS` | 10 | HTTP 请求超时（秒） |
/// | `HEADLESS_TIMEOUT_SECS` | 30 | 无头浏览器超时（秒） |
/// | `HEADLESS_IDLE_WAIT_SECS` | 10 | 网络空闲等待时间（秒），用于等待 JS 渲染完成 |
/// | `CACHE_TTL_SECS` | 3600 | 缓存条目存活时间（秒） |
/// | `PROXY_URL` | (无默认) | HTTP 代理地址，例如 `http://127.0.0.1:7890`，不设则直连 |
/// | `PORT` | 3000 | 监听端口 |
///
/// ## 路由
/// - `GET /health`：健康检查
/// - `POST /scrape`：抓取入口
#[tokio::main]
async fn main() {
    tracing_subscriber::fmt()
        .with_env_filter(
            tracing_subscriber::EnvFilter::from_default_env()
                .add_directive("scraper_service=info".parse().unwrap()),
        )
        .init();

    let timeout_secs: u64 = env_or("REQUEST_TIMEOUT_SECS", 10);
    let headless_timeout_secs: u64 = env_or("HEADLESS_TIMEOUT_SECS", 30);
    let headless_idle_wait_secs: u64 = env_or("HEADLESS_IDLE_WAIT_SECS", 10);
    let cache_ttl_secs: u64 = env_or("CACHE_TTL_SECS", 3600);
    let cache = Arc::new(ScrapeCache::new(cache_ttl_secs));
    let port: u16 = env_or("PORT", 3000);

    if headless_idle_wait_secs >= headless_timeout_secs {
        tracing::warn!(
            "HEADLESS_IDLE_WAIT_SECS ({headless_idle_wait_secs}) >= HEADLESS_TIMEOUT_SECS ({headless_timeout_secs}): headless scrapes will always timeout"
        );
    }

    let proxy_url = env::var("PROXY_URL").ok().filter(|s| !s.is_empty());

    // 从 PROXY_URL 提取代理主机名，让 SSRF 解析器放行它（代理常驻 docker 私网）
    let proxy_host = proxy_url
        .as_deref()
        .and_then(|u| reqwest::Url::parse(u).ok())
        .and_then(|u| u.host_str().map(|h| h.to_string()));

    let mut client_builder = reqwest::Client::builder()
        .dns_resolver(Arc::new(scraper::SsrfSafeResolver::new(proxy_host)))
        .timeout(Duration::from_secs(timeout_secs));

    if let Some(url) = proxy_url {
        match reqwest::Proxy::all(&url) {
            Ok(proxy) => {
                client_builder = client_builder.proxy(proxy);
                tracing::info!("proxy enabled: {url}");
            }
            Err(e) => {
                tracing::warn!("PROXY_URL '{url}' is invalid, continuing without proxy: {e}");
            }
        }
    }

    let client = client_builder.build().expect("failed to build reqwest client");

    let oss = oss::OssClient::from_env().map(Arc::new);
    if oss.is_some() {
        tracing::info!("OSS upload enabled");
    } else {
        tracing::info!("OSS upload disabled (OSS_* env vars not configured)");
    }

    let auth_token = env::var("SCRAPER_AUTH_TOKEN")
        .ok()
        .filter(|s| !s.is_empty())
        .map(Arc::new);
    if auth_token.is_some() {
        tracing::info!("auth enabled: /scrape and /ping require Authorization: Bearer <token>");
    } else {
        tracing::warn!(
            "auth disabled (SCRAPER_AUTH_TOKEN not set): /scrape and /ping accept unauthenticated requests"
        );
    }

    let state = AppState { client, headless_timeout_secs, headless_idle_wait_secs, cache, oss, auth_token };

    // Bounds how many /scrape + /ping requests run at once. Layer 1 fetches have no
    // per-request cost limit otherwise, and Layer 2 already serializes on HEADLESS_LOCK
    // but with no cap on how many callers can be queued waiting for it. Beyond this cap,
    // load_shed fails fast with 503 instead of letting requests queue indefinitely.
    let max_concurrent: usize = env_or("MAX_CONCURRENT_REQUESTS", 32);

    // Installs a process-wide global metrics recorder — must happen exactly once, so it
    // lives here rather than in `build_router` (which integration tests also call, and
    // would panic on the second `Router` built within the same test binary).
    let (prometheus_layer, metric_handle) = axum_prometheus::PrometheusMetricLayer::pair();
    let app = build_router(state, max_concurrent)
        .route("/metrics", get(move || async move { metric_handle.render() }))
        .layer(prometheus_layer);

    let addr = format!("0.0.0.0:{port}");
    tracing::info!("scraper-service listening on {addr}");
    let listener = tokio::net::TcpListener::bind(&addr).await
        .expect("failed to bind TCP listener");
    axum::serve(listener, app)
        .with_graceful_shutdown(shutdown_signal())
        .await
        .expect("server error");
}

/// 等待 SIGTERM（容器编排下发的停止信号）或 Ctrl+C。
///
/// 触发后 `axum::serve` 会停止接受新连接，但等正在处理的请求（包括可能耗时
/// 到 `HEADLESS_TIMEOUT_SECS` 的无头抓取）跑完再退出，而不是直接腰斩。
/// 对应地，容器编排的停止宽限期需要大于 `HEADLESS_TIMEOUT_SECS`
/// （见 `deploy/compose.prod.yml` 的 `stop_grace_period`）。
async fn shutdown_signal() {
    let ctrl_c = async {
        tokio::signal::ctrl_c().await.expect("failed to install Ctrl+C handler");
    };

    #[cfg(unix)]
    let terminate = async {
        tokio::signal::unix::signal(tokio::signal::unix::SignalKind::terminate())
            .expect("failed to install SIGTERM handler")
            .recv()
            .await;
    };
    #[cfg(not(unix))]
    let terminate = std::future::pending::<()>();

    tokio::select! {
        _ = ctrl_c => {},
        _ = terminate => {},
    }
    tracing::info!("shutdown signal received, draining in-flight requests");
}

/// 组装 `/health`、`/scrape`、`/ping` 路由：`/health` 不鉴权、不限流（运维探活用，
/// 且服务只监听回环地址，见 `deploy/compose.prod.yml`）；`/scrape`、`/ping` 经过鉴权
/// 中间件（`auth_token` 为 `None` 时是 no-op）和并发上限 + 过载快速失败。
///
/// 不含 `/metrics`——那需要装一个进程级全局 metrics recorder，只能装一次，装在
/// `main()` 里；这里独立成函数纯粹是为了让集成测试能直接构造 `Router` 发请求，
/// 而不必绑定真实端口，也不必触碰那个全局单例。
fn build_router(state: AppState, max_concurrent: usize) -> Router {
    // Layer order: auth (outer, added last via route_layer) runs before a request can
    // consume a concurrency permit, so unauthenticated requests never count against it.
    let protected = Router::new()
        .route("/scrape", post(scrape_handler))
        .route("/ping", post(ping_handler))
        .layer(
            ServiceBuilder::new()
                .layer(HandleErrorLayer::new(handle_overload))
                .load_shed()
                .concurrency_limit(max_concurrent),
        )
        .route_layer(middleware::from_fn_with_state(state.clone(), auth_middleware));

    Router::new()
        .route("/health", get(health_handler))
        .merge(protected)
        .layer(TraceLayer::new_for_http())
        .with_state(state)
}

/// 健康检查处理器。
///
/// 始终返回 `200 OK` 和 `{"status": "ok"}`，供负载均衡器或容器编排系统探活。
async fn health_handler() -> Json<serde_json::Value> {
    Json(serde_json::json!({"status": "ok"}))
}

/// `/scrape`、`/ping` 的鉴权中间件。
///
/// `state.auth_token` 为 `None` 时直接放行（本地开发 / 未配置场景）。
/// 否则要求 `Authorization: Bearer <token>` 且与配置值常量时间相等，防止时序攻击泄露 token。
async fn auth_middleware(State(state): State<AppState>, req: Request, next: Next) -> Response {
    let Some(expected) = &state.auth_token else {
        return next.run(req).await;
    };

    let provided = req
        .headers()
        .get(AUTHORIZATION)
        .and_then(|v| v.to_str().ok())
        .and_then(|v| v.strip_prefix("Bearer "));

    let authorized = provided.is_some_and(|token| {
        token.as_bytes().ct_eq(expected.as_bytes()).into()
    });

    if authorized {
        next.run(req).await
    } else {
        (
            StatusCode::UNAUTHORIZED,
            Json(ErrorResponse { error: "unauthorized".to_string(), detail: None }),
        )
            .into_response()
    }
}


/// POST /scrape 的请求体结构。
#[derive(Deserialize)]
struct ScrapeRequest {
    /// 目标 URL，必填
    url: String,
    /// 是否强制使用无头浏览器（Layer 2）。默认为 `false`：先尝试普通 HTTP，
    /// 若 Layer 1 未获取到标题则自动回退到 Layer 2。
    headless: Option<bool>,
}

/// POST /scrape 的成功响应体结构。
#[derive(Serialize)]
struct ScrapeResponse {
    /// 页面标题
    title: Option<String>,
    /// 页面描述
    description: Option<String>,
    /// 页面主图 URL
    image: Option<String>,
    /// 网站图标（base64 data URL）
    favicon: Option<String>,
    /// 网站 Logo URL（有 OSS 时为 OSS URL，否则为原始 URL）
    logo: Option<String>,
    /// 数据来源标识（"og" / "twitter_card" / "json_ld" / "html" / "headless"）
    source: String,
    /// 命中缓存时为 `Some(true)`，实时抓取时省略此字段
    #[serde(skip_serializing_if = "Option::is_none")]
    cached: Option<bool>,
    /// 截图：OSS 上传后为 URL，否则为 base64 编码的 PNG 数据（仅 headless 模式下存在）
    #[serde(skip_serializing_if = "Option::is_none")]
    screenshot: Option<String>,
}

/// 错误响应体结构。
#[derive(Serialize)]
struct ErrorResponse {
    /// 错误类型描述
    error: String,
    /// 可选的详细错误信息（如网络错误消息）
    #[serde(skip_serializing_if = "Option::is_none")]
    detail: Option<String>,
}

/// POST /ping 的请求体结构。
#[derive(Deserialize)]
struct PingRequest {
    /// 目标 URL，必填
    url: String,
}

/// POST /ping 的响应体结构。
#[derive(Serialize)]
struct PingResponse {
    /// 网站是否存活（HTTP 响应状态码 < 500）
    alive: bool,
}

/// POST /ping：通过代理向目标 URL 发送 HEAD 请求，返回网站是否存活。
///
/// 存活判定：收到任意 HTTP 响应（包括 4xx）即视为存活；连接失败或超时视为不存活。
async fn ping_handler(
    State(state): State<AppState>,
    Json(body): Json<PingRequest>,
) -> Response {
    let url = match reqwest::Url::parse(&body.url) {
        Ok(u) => u,
        Err(_) => {
            return (
                StatusCode::UNPROCESSABLE_ENTITY,
                Json(ErrorResponse { error: "invalid url".to_string(), detail: None }),
            )
                .into_response();
        }
    };

    if !matches!(url.scheme(), "http" | "https") {
        return (
            StatusCode::UNPROCESSABLE_ENTITY,
            Json(ErrorResponse { error: "invalid url".to_string(), detail: None }),
        )
            .into_response();
    }

    let domain = url.host_str().unwrap_or(&body.url).to_string();
    tracing::info!(domain, "ping");

    // Explicit SSRF pre-flight: the shared client's SsrfSafeResolver only runs for
    // hostnames it actually has to resolve. Many HTTP stacks (including this one) skip
    // DNS resolution entirely when the host is already an IP literal, so a bare
    // "http://169.254.169.254/" would otherwise reach the target directly. scrape() and
    // favicon_to_base64() already guard against this with the same call — ping_handler
    // was missing it. A blocked target degrades to `alive: false` rather than a distinct
    // error, keeping this endpoint's contract simple and not revealing to the caller
    // that we recognized it as an internal address.
    if let Err(e) = scraper::validate_target_host(&url).await {
        tracing::info!(domain, ?e, "ping rejected: forbidden target");
        return Json(PingResponse { alive: false }).into_response();
    }

    let alive = match state.client.head(url.as_str()).send().await {
        Ok(resp) => resp.status().as_u16() < 500,
        Err(_) => false,
    };

    tracing::info!(domain, alive, "ping done");
    Json(PingResponse { alive }).into_response()
}

/// 并发上限触发时的兜底响应：`load_shed` 把"超过 concurrency_limit"包装成一个
/// `tower::BoxError`，这里统一转成 `503`，而不是让请求无限排队等待许可。
async fn handle_overload(_err: tower::BoxError) -> Response {
    (
        StatusCode::SERVICE_UNAVAILABLE,
        Json(ErrorResponse {
            error: "service overloaded".to_string(),
            detail: Some("too many concurrent scrape requests, retry shortly".to_string()),
        }),
    )
        .into_response()
}

fn env_or<T: std::str::FromStr>(key: &str, default: T) -> T {
    env::var(key).ok().and_then(|v| v.parse().ok()).unwrap_or(default)
}

fn base64_encode(bytes: &[u8]) -> String {
    use base64::{engine::general_purpose::STANDARD, Engine};
    STANDARD.encode(bytes)
}

async fn favicon_to_base64(url: Option<&str>, http: &reqwest::Client) -> Option<String> {
    let url = url?;
    let parsed = reqwest::Url::parse(url).ok()?;
    // SSRF pre-flight: reject private/loopback targets before making the request.
    // The shared client's SsrfSafeResolver also enforces this at the DNS level,
    // but the pre-flight gives a clean early return rather than a connection error.
    scraper::validate_target_host(&parsed).await.ok()?;
    let referer = format!("{}://{}", parsed.scheme(), parsed.host_str().unwrap_or(""));
    let response = http.get(url).header("Referer", &referer).send().await.ok()?.error_for_status().ok()?;
    let content_type = response
        .headers()
        .get(reqwest::header::CONTENT_TYPE)
        .and_then(|v| v.to_str().ok())
        // Strip control characters (e.g. \t) to prevent data-URI injection
        .map(|s| s.chars().take_while(|c| !c.is_ascii_control()).collect::<String>())
        .filter(|s| !s.trim().is_empty())
        .unwrap_or_else(|| "image/png".to_string());
    let bytes = scraper::read_body_capped(response, scraper::MAX_FAVICON_BYTES)
        .await
        .map_err(|e| tracing::warn!("favicon download failed for {url}: {e}"))
        .ok()?;
    use base64::{engine::general_purpose::STANDARD, Engine};
    Some(format!("data:{content_type};base64,{}", STANDARD.encode(&bytes)))
}

/// POST /scrape 主处理器：协调缓存查询、普通抓取和无头抓取的完整流程。
///
/// ## 处理流程
/// 1. **缓存命中**：若 URL 在缓存中存在，直接返回缓存结果（`cached: true`）
/// 2. **headless=true**：直接调用无头浏览器抓取（Layer 2）
/// 3. **headless=false（默认）**：
///    - 先调用普通 HTTP 抓取（Layer 1）
///    - 若 Layer 1 返回的 `title` 为 `None`（JS 渲染页面），自动回退到 Layer 2
/// 4. 抓取成功后将结果写入缓存，再返回给客户端
///
/// ## 响应状态码
/// | 状态 | 含义 |
/// |---|---|
/// | 200 | 成功（含缓存命中） |
/// | 422 | URL 格式非法 |
/// | 504 | 抓取超时 |
/// | 502 | 网络请求失败或无头浏览器失败 |
async fn scrape_handler(
    State(state): State<AppState>,
    Json(body): Json<ScrapeRequest>,
) -> Response {
    let start = Instant::now();
    let domain = reqwest::Url::parse(&body.url)
        .ok()
        .and_then(|u| u.host_str().map(str::to_string))
        .unwrap_or_else(|| body.url.clone());

    // 近期失败的 URL 直接拒绝，防止重复触发高开销的 headless 抓取
    if state.cache.get_error(&body.url).await {
        tracing::info!(domain, "scrape rejected: recently failed (negative cache hit)");
        return (
            StatusCode::BAD_GATEWAY,
            Json(ErrorResponse {
                error: "scrape failed recently, retry after 60s".to_string(),
                detail: None,
            }),
        )
            .into_response();
    }

    // 优先返回缓存结果，避免重复抓取
    if let Some(cached) = state.cache.get(&body.url).await {
        let screenshot = cached.screenshot_url.clone().or_else(|| {
            cached.screenshot_bytes.as_ref().map(|b| base64_encode(b))
        });
        tracing::info!(domain, elapsed_ms = start.elapsed().as_millis(), source = cached.source, "cache hit");
        return Json(ScrapeResponse {
            title: cached.title.clone(),
            description: cached.description.clone(),
            image: cached.image.clone(),
            favicon: cached.favicon.clone(),
            logo: cached.logo.clone(),
            source: cached.source.clone(),
            cached: Some(true),
            screenshot,
        })
        .into_response();
    }

    let result = if body.headless.unwrap_or(false) {
        tracing::info!(domain, "scraping (layer2/headless)");
        headless::scrape_headless(&body.url, state.headless_timeout_secs, state.headless_idle_wait_secs).await
    } else {
        tracing::info!(domain, "scraping (layer1)");
        match scraper::scrape(&body.url, &state.client).await {
            Ok(r) if r.title.is_none() => {
                tracing::info!(domain, elapsed_ms = start.elapsed().as_millis(), "layer1 no title, falling back to layer2");
                headless::scrape_headless(&body.url, state.headless_timeout_secs, state.headless_idle_wait_secs).await
            }
            other => other,
        }
    };

    match result {
        Ok(r) => {
            // If OSS is configured, upload assets concurrently. Asset failures (image,
            // logo, screenshot, favicon) are non-fatal: fields are set to None or the
            // original URL with a warning rather than failing the whole request.
            let r = if let Some(oss) = &state.oss {
                oss.upload_assets(r, &body.url, &state.client).await
            } else {
                let favicon_b64 = favicon_to_base64(r.favicon.as_deref(), &state.client).await;
                // Pre-encode screenshot once so subsequent cache hits don't re-encode.
                let mut r = scraper::ScrapeResult { favicon: favicon_b64, ..r };
                if let Some(bytes) = r.screenshot_bytes.take() {
                    r.screenshot_url = Some(base64_encode(&bytes));
                }
                r
            };

            let screenshot = r.screenshot_url.clone().or_else(|| {
                r.screenshot_bytes.as_ref().map(|b| base64_encode(b))
            });

            let r = Arc::new(r);
            state.cache.set(&body.url, Arc::clone(&r)).await;

            tracing::info!(domain, elapsed_ms = start.elapsed().as_millis(), source = r.source, "scraped ok");

            Json(ScrapeResponse {
                title: r.title.clone(),
                description: r.description.clone(),
                image: r.image.clone(),
                favicon: r.favicon.clone(),
                logo: r.logo.clone(),
                source: r.source.clone(),
                cached: None,
                screenshot,
            })
            .into_response()
        }

        Err(scraper::ScrapeError::InvalidUrl) => {
            tracing::info!(domain, elapsed_ms = start.elapsed().as_millis(), "scrape failed: invalid url");
            (
                StatusCode::UNPROCESSABLE_ENTITY,
                Json(ErrorResponse { error: "invalid url".to_string(), detail: None }),
            )
                .into_response()
        }

        Err(scraper::ScrapeError::ForbiddenTarget(msg)) => {
            tracing::info!(domain, elapsed_ms = start.elapsed().as_millis(), %msg, "scrape failed: forbidden target");
            (
                StatusCode::FORBIDDEN,
                Json(ErrorResponse { error: "forbidden target".to_string(), detail: Some(msg) }),
            )
                .into_response()
        }

        Err(scraper::ScrapeError::Timeout) => {
            tracing::info!(domain, elapsed_ms = start.elapsed().as_millis(), "scrape failed: timeout");
            state.cache.set_error(&body.url).await;
            (
                StatusCode::GATEWAY_TIMEOUT,
                Json(ErrorResponse { error: "timeout".to_string(), detail: None }),
            )
                .into_response()
        }

        Err(scraper::ScrapeError::FetchFailed(msg)) => {
            tracing::info!(domain, elapsed_ms = start.elapsed().as_millis(), %msg, "scrape failed: fetch error");
            state.cache.set_error(&body.url).await;
            (
                StatusCode::BAD_GATEWAY,
                Json(ErrorResponse { error: "fetch failed".to_string(), detail: Some(msg) }),
            )
                .into_response()
        }

        Err(scraper::ScrapeError::HeadlessFailed(msg)) => {
            tracing::info!(domain, elapsed_ms = start.elapsed().as_millis(), %msg, "scrape failed: headless error");
            state.cache.set_error(&body.url).await;
            (
                StatusCode::BAD_GATEWAY,
                Json(ErrorResponse { error: "headless failed".to_string(), detail: Some(msg) }),
            )
                .into_response()
        }

        Err(scraper::ScrapeError::OssFailed(msg)) => {
            // upload_assets no longer propagates OssFailed (asset failures are non-fatal).
            // This arm satisfies exhaustive matching; it cannot be reached in practice.
            (
                StatusCode::SERVICE_UNAVAILABLE,
                Json(ErrorResponse { error: "oss upload failed".to_string(), detail: Some(msg) }),
            )
                .into_response()
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use axum::body::{to_bytes, Body};
    use std::sync::atomic::{AtomicUsize, Ordering};
    use tower::ServiceExt;

    fn test_state() -> AppState {
        AppState {
            client: reqwest::Client::new(),
            headless_timeout_secs: 5,
            headless_idle_wait_secs: 1,
            cache: Arc::new(ScrapeCache::new(3600)),
            oss: None,
            auth_token: None,
        }
    }

    fn json_request(method: &str, uri: &str, body: serde_json::Value, bearer: Option<&str>) -> Request {
        let mut builder = axum::http::Request::builder()
            .method(method)
            .uri(uri)
            .header("content-type", "application/json");
        if let Some(token) = bearer {
            builder = builder.header("authorization", format!("Bearer {token}"));
        }
        builder.body(Body::from(body.to_string())).unwrap()
    }

    async fn call(app: Router, req: Request) -> (StatusCode, serde_json::Value) {
        let response = app.oneshot(req).await.unwrap();
        let status = response.status();
        let bytes = to_bytes(response.into_body(), 1024 * 1024).await.unwrap();
        let json = if bytes.is_empty() {
            serde_json::Value::Null
        } else {
            serde_json::from_slice(&bytes).unwrap()
        };
        (status, json)
    }

    #[tokio::test]
    async fn health_returns_ok() {
        let app = build_router(test_state(), 32);
        let req = axum::http::Request::builder().uri("/health").body(Body::empty()).unwrap();
        let (status, json) = call(app, req).await;
        assert_eq!(status, StatusCode::OK);
        assert_eq!(json["status"], "ok");
    }

    #[tokio::test]
    async fn health_bypasses_auth_even_when_token_is_set() {
        let mut state = test_state();
        state.auth_token = Some(Arc::new("secret".to_string()));
        let app = build_router(state, 32);
        let req = axum::http::Request::builder().uri("/health").body(Body::empty()).unwrap();
        let (status, _) = call(app, req).await;
        assert_eq!(status, StatusCode::OK);
    }

    #[tokio::test]
    async fn scrape_invalid_url_returns_422() {
        let app = build_router(test_state(), 32);
        let req = json_request("POST", "/scrape", serde_json::json!({"url": "not-a-url"}), None);
        let (status, json) = call(app, req).await;
        assert_eq!(status, StatusCode::UNPROCESSABLE_ENTITY);
        assert_eq!(json["error"], "invalid url");
    }

    #[tokio::test]
    async fn scrape_forbidden_target_returns_403() {
        std::env::remove_var("SSRF_ALLOW_PRIVATE");
        let app = build_router(test_state(), 32);
        let req = json_request("POST", "/scrape", serde_json::json!({"url": "http://127.0.0.1/"}), None);
        let (status, json) = call(app, req).await;
        assert_eq!(status, StatusCode::FORBIDDEN);
        assert_eq!(json["error"], "forbidden target");
    }

    #[tokio::test]
    async fn scrape_negative_cache_hit_returns_502_with_retry_message() {
        let state = test_state();
        state.cache.set_error("https://example.com/flaky").await;
        let app = build_router(state, 32);
        let req = json_request("POST", "/scrape", serde_json::json!({"url": "https://example.com/flaky"}), None);
        let (status, json) = call(app, req).await;
        assert_eq!(status, StatusCode::BAD_GATEWAY);
        assert!(json["error"].as_str().unwrap().contains("retry after 60s"));
    }

    #[tokio::test]
    async fn scrape_cache_hit_returns_cached_result() {
        let state = test_state();
        let result = Arc::new(scraper::ScrapeResult {
            title: Some("Cached Title".to_string()),
            description: None,
            image: None,
            favicon: None,
            logo: None,
            source: "og".to_string(),
            screenshot_bytes: None,
            screenshot_url: None,
        });
        state.cache.set("https://example.com/cached", result).await;
        let app = build_router(state, 32);
        let req = json_request("POST", "/scrape", serde_json::json!({"url": "https://example.com/cached"}), None);
        let (status, json) = call(app, req).await;
        assert_eq!(status, StatusCode::OK);
        assert_eq!(json["title"], "Cached Title");
        assert_eq!(json["cached"], true);
    }

    #[tokio::test]
    async fn scrape_without_token_is_rejected_when_auth_enabled() {
        let mut state = test_state();
        state.auth_token = Some(Arc::new("s3cret".to_string()));
        let app = build_router(state, 32);
        let req = json_request("POST", "/scrape", serde_json::json!({"url": "https://example.com/"}), None);
        let (status, json) = call(app, req).await;
        assert_eq!(status, StatusCode::UNAUTHORIZED);
        assert_eq!(json["error"], "unauthorized");
    }

    #[tokio::test]
    async fn scrape_with_wrong_token_is_rejected() {
        let mut state = test_state();
        state.auth_token = Some(Arc::new("s3cret".to_string()));
        let app = build_router(state, 32);
        let req = json_request(
            "POST",
            "/scrape",
            serde_json::json!({"url": "https://example.com/"}),
            Some("wrong-token"),
        );
        let (status, _) = call(app, req).await;
        assert_eq!(status, StatusCode::UNAUTHORIZED);
    }

    #[tokio::test]
    async fn scrape_with_correct_token_passes_auth() {
        let mut state = test_state();
        state.auth_token = Some(Arc::new("s3cret".to_string()));
        // Pre-populate the cache so this request never touches the network — the point
        // of this test is only that the auth middleware lets a valid token through.
        state
            .cache
            .set(
                "https://example.com/authed",
                Arc::new(scraper::ScrapeResult {
                    title: Some("Authed".to_string()),
                    description: None,
                    image: None,
                    favicon: None,
                    logo: None,
                    source: "html".to_string(),
                    screenshot_bytes: None,
                    screenshot_url: None,
                }),
            )
            .await;
        let app = build_router(state, 32);
        let req = json_request(
            "POST",
            "/scrape",
            serde_json::json!({"url": "https://example.com/authed"}),
            Some("s3cret"),
        );
        let (status, json) = call(app, req).await;
        assert_eq!(status, StatusCode::OK);
        assert_eq!(json["title"], "Authed");
    }

    #[tokio::test]
    async fn ping_invalid_url_returns_422() {
        let app = build_router(test_state(), 32);
        let req = json_request("POST", "/ping", serde_json::json!({"url": "not-a-url"}), None);
        let (status, json) = call(app, req).await;
        assert_eq!(status, StatusCode::UNPROCESSABLE_ENTITY);
        assert_eq!(json["error"], "invalid url");
    }

    /// Regression test for an SSRF gap found in review: `ping_handler` used to rely
    /// solely on the shared client's DNS-level `SsrfSafeResolver`, which is never
    /// consulted for hosts that are already IP literals (hyper connects to those
    /// directly) — so `"http://127.0.0.1:<port>/"` reached a real local listener
    /// instead of being blocked. Verifies both that the target is reported as
    /// not-alive AND that the local listener is never actually contacted, proving
    /// it's blocked pre-flight rather than just refused for some other reason.
    #[tokio::test]
    async fn ping_blocks_loopback_ip_literal_and_never_connects() {
        std::env::remove_var("SSRF_ALLOW_PRIVATE");

        let hits = Arc::new(AtomicUsize::new(0));
        let hits_clone = Arc::clone(&hits);
        let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
        let addr = listener.local_addr().unwrap();
        tokio::spawn(async move {
            if let Ok((mut socket, _)) = listener.accept().await {
                hits_clone.fetch_add(1, Ordering::SeqCst);
                use tokio::io::AsyncWriteExt;
                let _ = socket.write_all(b"HTTP/1.1 200 OK\r\ncontent-length: 0\r\n\r\n").await;
            }
        });

        let app = build_router(test_state(), 32);
        let req = json_request("POST", "/ping", serde_json::json!({"url": format!("http://{addr}/")}), None);
        let (status, json) = call(app, req).await;

        assert_eq!(status, StatusCode::OK);
        assert_eq!(json["alive"], false);

        tokio::time::sleep(Duration::from_millis(50)).await;
        assert_eq!(hits.load(Ordering::SeqCst), 0, "loopback listener should never be contacted");
    }
}
