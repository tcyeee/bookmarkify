mod cache;
mod headless;
mod oss;
mod scraper;

use axum::{
    extract::State,
    http::StatusCode,
    response::{IntoResponse, Json, Response},
    routing::{get, post},
    Router,
};
use cache::ScrapeCache;
use serde::{Deserialize, Serialize};
use std::{env, sync::Arc, time::Duration};
use tower_http::trace::TraceLayer;

/// 全局应用状态，通过 `Arc` 在所有请求处理器之间共享。
#[derive(Clone)]
struct AppState {
    /// 共享的 HTTP 客户端，内置连接池和超时配置
    client: reqwest::Client,
    /// 无头浏览器单次抓取的最大等待时间（秒），对应环境变量 `HEADLESS_TIMEOUT_SECS`
    headless_timeout_secs: u64,
    /// 基于 URL 的抓取结果内存缓存
    cache: Arc<ScrapeCache>,
    /// OSS 客户端，用于将截图和图片上传到对象存储（可选）
    oss: Option<Arc<oss::OssClient>>,
}

/// 服务入口：读取环境变量、构建路由并启动 HTTP 服务器。
///
/// ## 环境变量
/// | 变量名 | 默认值 | 说明 |
/// |---|---|---|
/// | `API_KEY` | 必填 | Bearer Token 鉴权密钥 |
/// | `REQUEST_TIMEOUT_SECS` | 10 | HTTP 请求超时（秒） |
/// | `HEADLESS_TIMEOUT_SECS` | 30 | 无头浏览器超时（秒） |
/// | `CACHE_TTL_SECS` | 3600 | 缓存条目存活时间（秒） |
/// | `RATE_LIMIT_RPS` | 10 | 每个 API Key 的每秒请求上限 |
/// | `PROXY_URL` | (无默认) | HTTP 代理地址，例如 `http://127.0.0.1:7890`，不设则直连 |
/// | `PORT` | 3000 | 监听端口 |
///
/// ## 路由
/// - `GET /health`：健康检查，无需鉴权
/// - `POST /scrape`：抓取入口，需要 Bearer Token 鉴权 + 速率限制
#[tokio::main]
async fn main() {
    tracing_subscriber::fmt()
        .with_env_filter(
            tracing_subscriber::EnvFilter::from_default_env()
                .add_directive("scraper_service=info".parse().unwrap()),
        )
        .init();

    let timeout_secs: u64 = env::var("REQUEST_TIMEOUT_SECS")
        .ok()
        .and_then(|v| v.parse().ok())
        .unwrap_or(10);
    let headless_timeout_secs: u64 = env::var("HEADLESS_TIMEOUT_SECS")
        .ok()
        .and_then(|v| v.parse().ok())
        .unwrap_or(30);
    let cache_ttl_secs: u64 = env::var("CACHE_TTL_SECS")
        .ok()
        .and_then(|v| v.parse().ok())
        .unwrap_or(3600);
    let cache = Arc::new(ScrapeCache::new(cache_ttl_secs));
    let port: u16 = env::var("PORT")
        .ok()
        .and_then(|v| v.parse().ok())
        .unwrap_or(3000);

    let proxy_url = env::var("PROXY_URL").ok();

    let mut client_builder = reqwest::Client::builder()
        .timeout(Duration::from_secs(timeout_secs));

    if let Some(url) = proxy_url {
        let proxy = reqwest::Proxy::all(&url)
            .expect("PROXY_URL is not a valid proxy URL");
        client_builder = client_builder.proxy(proxy);
        tracing::info!("proxy enabled: {url}");
    }

    let client = client_builder.build().expect("failed to build reqwest client");

    let oss = oss::OssClient::from_env().map(Arc::new);
    if oss.is_some() {
        tracing::info!("OSS upload enabled");
    } else {
        tracing::info!("OSS upload disabled (OSS_* env vars not configured)");
    }

    let state = AppState { client, headless_timeout_secs, cache, oss };

    let app = Router::new()
        .route("/health", get(health_handler))
        .route("/scrape", post(scrape_handler))
        .layer(TraceLayer::new_for_http())
        .with_state(state);

    let addr = format!("0.0.0.0:{port}");
    tracing::info!("scraper-service listening on {addr}");
    let listener = tokio::net::TcpListener::bind(&addr).await.unwrap();
    axum::serve(listener, app).await.unwrap();
}

/// 健康检查处理器。
///
/// 始终返回 `200 OK` 和 `{"status": "ok"}`，供负载均衡器或容器编排系统探活。
async fn health_handler() -> Json<serde_json::Value> {
    Json(serde_json::json!({"status": "ok"}))
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
    /// 网站图标 URL
    favicon: Option<String>,
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

fn base64_encode(bytes: &[u8]) -> String {
    use base64::{engine::general_purpose::STANDARD, Engine};
    STANDARD.encode(bytes)
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
    // 优先返回缓存结果，避免重复抓取
    if let Some(cached) = state.cache.get(&body.url).await {
        let screenshot = cached.screenshot_url.clone().or_else(|| {
            cached.screenshot_bytes.as_ref().map(|b| base64_encode(b))
        });
        return Json(ScrapeResponse {
            title: cached.title.clone(),
            description: cached.description.clone(),
            image: cached.image.clone(),
            favicon: cached.favicon.clone(),
            source: cached.source.clone(),
            cached: Some(true),
            screenshot,
        })
        .into_response();
    }

    let result = if body.headless.unwrap_or(false) {
        // 调用方明确要求无头浏览器
        headless::scrape_headless(&body.url, state.headless_timeout_secs).await
    } else {
        match scraper::scrape(&body.url, &state.client).await {
            Ok(r) if r.title.is_none() => {
                // Layer 1 returned no title — fallback to Layer 2
                headless::scrape_headless(&body.url, state.headless_timeout_secs).await
            }
            other => other,
        }
    };

    match result {
        Ok(r) => {
            // If OSS is configured, upload assets and replace URLs; hard-fail on error
            let r = if let Some(oss) = &state.oss {
                match oss.upload_assets(r, &body.url, &state.client).await {
                    Ok(uploaded) => uploaded,
                    Err(scraper::ScrapeError::OssFailed(msg)) => {
                        tracing::warn!("OSS upload failed for {}: {msg}", body.url);
                        return (
                            StatusCode::SERVICE_UNAVAILABLE,
                            Json(ErrorResponse {
                                error: "oss upload failed".to_string(),
                                detail: Some(msg),
                            }),
                        )
                            .into_response();
                    }
                    Err(_) => unreachable!("upload_assets only returns OssFailed"),
                }
            } else {
                r
            };

            let screenshot = r.screenshot_url.clone().or_else(|| {
                r.screenshot_bytes.as_ref().map(|b| base64_encode(b))
            });

            let r = Arc::new(r);
            state.cache.set(&body.url, Arc::clone(&r)).await;

            Json(ScrapeResponse {
                title: r.title.clone(),
                description: r.description.clone(),
                image: r.image.clone(),
                favicon: r.favicon.clone(),
                source: r.source.clone(),
                cached: None,
                screenshot,
            })
            .into_response()
        }

        Err(scraper::ScrapeError::InvalidUrl) => (
            StatusCode::UNPROCESSABLE_ENTITY,
            Json(ErrorResponse { error: "invalid url".to_string(), detail: None }),
        )
            .into_response(),

        Err(scraper::ScrapeError::Timeout) => (
            StatusCode::GATEWAY_TIMEOUT,
            Json(ErrorResponse { error: "timeout".to_string(), detail: None }),
        )
            .into_response(),

        Err(scraper::ScrapeError::FetchFailed(msg)) => (
            StatusCode::BAD_GATEWAY,
            Json(ErrorResponse { error: "fetch failed".to_string(), detail: Some(msg) }),
        )
            .into_response(),

        Err(scraper::ScrapeError::HeadlessFailed(msg)) => (
            StatusCode::BAD_GATEWAY,
            Json(ErrorResponse { error: "headless failed".to_string(), detail: Some(msg) }),
        )
            .into_response(),

        Err(scraper::ScrapeError::OssFailed(msg)) => {
            // OssFailed is handled in the Ok(r) arm above; this arm is required for
            // exhaustive matching but cannot be reached via the scrape/headless paths.
            (
                StatusCode::SERVICE_UNAVAILABLE,
                Json(ErrorResponse { error: "oss upload failed".to_string(), detail: Some(msg) }),
            )
                .into_response()
        }
    }
}
