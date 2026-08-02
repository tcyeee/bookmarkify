mod cache;
mod contract;
mod extract;
mod headless;
mod oss;
mod pipeline;
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

/// 串行化所有会读写 `SSRF_ALLOW_PRIVATE` 的测试。
///
/// 该开关是进程级环境变量，而单元测试默认并行跑：一部分用例要求它**不存在**（验证
/// SSRF 拦截生效），端到端用例又必须把它打开才能访问本机测试服务器。两者并行就会互相
/// 掀桌子，所以统一在这把锁下排队。
#[cfg(test)]
static ENV_LOCK: tokio::sync::Mutex<()> = tokio::sync::Mutex::const_new(());

/// 取 [`ENV_LOCK`]。用 tokio 的异步互斥而非 `std::sync::Mutex`：后者跨 `.await`
/// 持有时，在多线程 runtime 下可能把整个 worker 线程连同锁一起阻塞住。
#[cfg(test)]
async fn env_guard() -> tokio::sync::MutexGuard<'static, ()> {
    ENV_LOCK.lock().await
}

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
        // 必须关掉自动跟随：`scraper::fetch_html` 手动逐跳跟随，才能把整条重定向链
        // 报进 `fetch.redirectChain`，并让每一跳都重新过一次 SSRF 校验。开着自动跟随
        // 会让那个循环永远看不到 3xx，`finalUrl` 也会停留在初始 URL 上。
        .redirect(reqwest::redirect::Policy::none())
        // 关掉自动跟随的代价是 cookie 也不会跨跳保留，而不少站点正是在 301 那一跳下发
        // 风控 cookie（B 站的 buvid3 就是），拿不到就在下一跳被 412 拦下。开着 jar 后，
        // 手动跟随的每一跳和 `warm_up_origin` 的预热请求才能共享同一份 cookie。
        .cookie_store(true)
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
            Json(contract::ErrorResponse { error: "UNAUTHORIZED".to_string(), detail: None, fetch: None }),
        )
            .into_response()
    }
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
                Json(contract::ErrorResponse { error: "INVALID_URL".to_string(), detail: None, fetch: None }),
            )
                .into_response();
        }
    };

    if !matches!(url.scheme(), "http" | "https") {
        return (
            StatusCode::UNPROCESSABLE_ENTITY,
            Json(contract::ErrorResponse { error: "INVALID_URL".to_string(), detail: None, fetch: None }),
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
        Json(contract::ErrorResponse {
            error: "OVERLOADED".to_string(),
            detail: Some("too many concurrent scrape requests, retry shortly".to_string()),
            fetch: None,
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

/// POST /scrape 主处理器：按请求参数编排取回、提取、富化三个阶段。
///
/// ## 处理流程
/// 1. **负缓存**：近期失败过的 URL 直接拒绝，避免重复触发高开销的 headless
/// 2. **缓存**：按 `cache.mode` 决定命中 / 绕过 / 只读缓存
/// 3. **取回 HTML**：按 `render.mode` 走 Layer 1、Layer 2 或"L1 无标题则回退 L2"
/// 4. **提取**：[`extract::extract_page`] 从 HTML 得出元数据与图片**声明**
/// 5. **富化**：拉 manifest 回填 `shortName` 与 `MANIFEST_ICON`；按 `assets.download`
///    取回图片正文算出真实尺寸 / `contentHash`
///
/// 各阶段的局部失败一律降级为 `diagnostics.warnings`，不让增量信息拖垮整次抓取。
///
/// ## 响应状态码
/// | 状态 | 含义 |
/// |---|---|
/// | 200 | 成功（含缓存命中） |
/// | 403 | SSRF 拦截 / robots 禁止 |
/// | 404 | `cache.mode = ONLY_IF_CACHED` 且未命中 |
/// | 422 | URL 格式非法 |
/// | 502 | 网络请求失败或无头浏览器失败 |
/// | 504 | 抓取超时 |
async fn scrape_handler(
    State(state): State<AppState>,
    Json(body): Json<contract::ScrapeRequest>,
) -> Response {
    use contract::{
        CacheMode, Diagnostics, FetchInfo, ImageFormat, RenderMode,
        ScrapeResponse, Screenshot, Timing,
    };

    let start = Instant::now();
    let domain = reqwest::Url::parse(&body.url)
        .ok()
        .and_then(|u| u.host_str().map(str::to_string))
        .unwrap_or_else(|| body.url.clone());

    // 近期失败的 URL 直接拒绝，防止重复触发高开销的 headless 抓取
    if body.cache.mode != CacheMode::Bypass && state.cache.get_error(&body.url).await {
        tracing::info!(domain, "scrape rejected: recently failed (negative cache hit)");
        return error_response(
            StatusCode::BAD_GATEWAY,
            "RECENTLY_FAILED",
            Some("scrape failed recently, retry after 60s".to_string()),
        );
    }

    // BYPASS 强制重抓（管理后台"重试"走这条，否则重试可能直接命中缓存等于没试）
    if body.cache.mode != CacheMode::Bypass {
        if let Some(cached) = state.cache.get(&body.url).await {
            tracing::info!(domain, elapsed_ms = start.elapsed().as_millis(), "cache hit");
            let mut hit = (*cached).clone();
            hit.fetch.from_cache = true;
            // 回显本次请求，而不是当初把它写进缓存的那次
            hit.request = body;
            return Json(hit).into_response();
        }
    }
    if body.cache.mode == CacheMode::OnlyIfCached {
        return error_response(StatusCode::NOT_FOUND, "CACHE_MISS", None);
    }

    // ── 阶段 1：取回 HTML ──────────────────────────────────────────────────
    let viewport = body.render.viewport.map(|v| (v.width, v.height));
    let want_screenshot = body.screenshot.enabled;
    // 截图只有无头浏览器能出，请求截图即隐含要走 Layer 2
    let effective_mode = if want_screenshot && body.render.mode == RenderMode::Auto {
        RenderMode::Headless
    } else {
        body.render.mode
    };

    let mut warnings: Vec<String> = Vec::new();
    let fetched = match effective_mode {
        RenderMode::Headless => {
            tracing::info!(domain, "scraping (layer2/headless)");
            headless::capture_headless(
                &body.url,
                state.headless_timeout_secs,
                state.headless_idle_wait_secs,
                viewport,
                want_screenshot,
            )
            .await
            .map(|c| Fetched::from_headless(&body.url, c))
        }
        RenderMode::Http => {
            tracing::info!(domain, "scraping (layer1)");
            scraper::fetch_html(
                &body.url,
                &state.client,
                body.render.user_agent.as_deref(),
                body.render.locale.as_deref(),
            )
            .await
            .map(Fetched::from_http)
        }
        RenderMode::Auto => {
            tracing::info!(domain, "scraping (layer1, auto-fallback)");
            match scraper::fetch_html(
                &body.url,
                &state.client,
                body.render.user_agent.as_deref(),
                body.render.locale.as_deref(),
            )
            .await
            {
                // Layer 1 拿不到标题基本意味着页面靠 JS 渲染，回退 Layer 2
                Ok(cap) if !html_has_title(&cap.html) => {
                    tracing::info!(
                        domain,
                        elapsed_ms = start.elapsed().as_millis(),
                        "layer1 no title, falling back to layer2"
                    );
                    warnings.push("layer1 produced no title, fell back to headless".to_string());
                    headless::capture_headless(
                        &body.url,
                        state.headless_timeout_secs,
                        state.headless_idle_wait_secs,
                        viewport,
                        want_screenshot,
                    )
                    .await
                    .map(|c| Fetched::from_headless(&body.url, c))
                }
                other => other.map(Fetched::from_http),
            }
        }
    };

    let fetched = match fetched {
        Ok(f) => f,
        Err(e) => {
            if !matches!(
                e,
                scraper::ScrapeError::InvalidUrl | scraper::ScrapeError::ForbiddenTarget(_)
            ) {
                state.cache.set_error(&body.url).await;
            }
            // 状态码失败用 describe()：Debug 会把正文片段连同转义一起糊成一行，读不了
            let reason = match &e {
                scraper::ScrapeError::HttpStatus(d) => d.describe(),
                other => format!("{other:?}"),
            };
            tracing::info!(domain, elapsed_ms = start.elapsed().as_millis(), "scrape failed: {reason}");
            return scrape_error_response(e, start.elapsed().as_millis() as u64);
        }
    };

    // ── 阶段 2：纯提取 ────────────────────────────────────────────────────
    let mut extracted = extract::extract_page(&fetched.html, &fetched.final_url, &body.extract);

    // ── 阶段 3：网络富化 ──────────────────────────────────────────────────
    let mut manifest_block = None;
    if let Some(manifest_url) = extracted.manifest_url.clone() {
        match pipeline::fetch_manifest(&manifest_url, &fetched.final_url, &state.client).await {
            Ok((block, icons, mmeta)) => {
                // manifest 是 shortName 的唯一标准来源，也是多尺寸 icons 的正统出处
                apply_manifest_meta(&mut extracted.meta, mmeta);
                extracted.assets.extend(icons);
                manifest_block = Some(block);
            }
            Err(e) => warnings.push(format!("manifest: {e}")),
        }
    }

    let (assets, asset_warnings) = pipeline::process_assets(
        std::mem::take(&mut extracted.assets),
        &body.assets,
        &state.client,
        state.oss.as_deref(),
    )
    .await;
    warnings.extend(asset_warnings);

    // 截图：有 OSS 传 OSS，没有则内联 data URL
    let screenshot = match fetched.screenshot_bytes {
        Some(bytes) if !bytes.is_empty() => {
            let (w, h) = pipeline::image_dimensions(&bytes).unwrap_or((0, 0));
            let byte_size = Some(bytes.len() as u64);
            let mut shot = Screenshot {
                storage_key: None,
                data_url: None,
                width: w,
                height: h,
                // spider 侧固定输出 PNG；format 参数目前只影响调用方预期，不做转码
                format: ImageFormat::Png,
                byte_size,
            };
            match state.oss.as_deref() {
                Some(oss) => {
                    let key = oss.screenshot_key(&body.url);
                    match oss.upload_bytes(&key, &bytes, "image/png").await {
                        Ok(k) => shot.storage_key = Some(k),
                        Err(e) => {
                            warnings.push(format!("screenshot upload failed: {e:?}"));
                            shot.data_url = Some(format!("data:image/png;base64,{}", base64_encode(&bytes)));
                        }
                    }
                }
                None => {
                    shot.data_url = Some(format!("data:image/png;base64,{}", base64_encode(&bytes)))
                }
            }
            Some(shot)
        }
        _ => None,
    };

    // ── 组装响应 ─────────────────────────────────────────────────────────
    let fetch = FetchInfo {
        final_url: fetched.final_url.to_string(),
        redirect_chain: fetched
            .redirects
            .into_iter()
            .map(|(url, status)| contract::Redirect { url, status })
            .collect(),
        http_status: fetched.http_status,
        layer_used: fetched.layer,
        from_cache: false,
        content_type: fetched.content_type,
        charset: fetched.charset,
        byte_size: Some(fetched.byte_size),
        // TLS 细节需要连接层钩子，reqwest 当前不透出；留空而不是编造
        tls: None,
        timing_ms: Timing { total: start.elapsed().as_millis() as u64, ..Default::default() },
    };

    let mut response = ScrapeResponse::new(body.clone(), fetch);
    response.meta = body.extract.meta.then_some(extracted.meta);
    response.assets = assets;
    response.manifest = manifest_block;
    response.jsonld = extracted.jsonld;
    response.opengraph = extracted.opengraph;
    response.twitter = extracted.twitter;
    response.feeds = extracted.feeds;
    response.alternates = extracted.alternates;
    response.text = body.extract.text.then(|| html_to_text(&fetched.html));
    response.screenshot = screenshot;
    response.diagnostics = Diagnostics {
        warnings,
        anti_crawler: extracted.anti_crawler,
        robots: None, // robots.txt 判定尚未实现，按契约省略而不是谎报 allowed
    };

    tracing::info!(
        domain,
        elapsed_ms = start.elapsed().as_millis(),
        assets = response.assets.len(),
        layer = ?response.fetch.layer_used,
        "scraped ok"
    );

    // 只缓存实打实抓到的结果；download 模式不同产出不同，但 key 只按 URL，
    // 因此缓存里存的是"当次参数下的产物"，BYPASS 可随时覆盖。
    state.cache.set(&body.url, Arc::new(response.clone())).await;

    Json(response).into_response()
}

/// 两条取回路径的统一产物，抹平 Layer 1 / Layer 2 的差异。
struct Fetched {
    html: String,
    final_url: reqwest::Url,
    redirects: Vec<(String, u16)>,
    http_status: u16,
    content_type: Option<String>,
    charset: Option<String>,
    byte_size: u64,
    layer: contract::RenderLayer,
    screenshot_bytes: Option<Vec<u8>>,
}

impl Fetched {
    fn from_http(c: scraper::HttpCapture) -> Self {
        Self {
            byte_size: c.byte_size,
            html: c.html,
            final_url: c.final_url,
            redirects: c.redirects,
            http_status: c.http_status,
            content_type: c.content_type,
            charset: c.charset,
            layer: contract::RenderLayer::Http,
            screenshot_bytes: None,
        }
    }

    fn from_headless(requested_url: &str, c: headless::HeadlessCapture) -> Self {
        let final_url = reqwest::Url::parse(requested_url)
            .unwrap_or_else(|_| reqwest::Url::parse("https://invalid.local/").unwrap());
        Self {
            byte_size: c.html.len() as u64,
            html: c.html,
            final_url,
            // Chrome 内部的跳转不经过我们，拿不到链路；留空而不是编造
            redirects: Vec::new(),
            http_status: 200,
            content_type: Some("text/html".to_string()),
            charset: Some("utf-8".to_string()),
            layer: contract::RenderLayer::Headless,
            screenshot_bytes: c.screenshot_bytes,
        }
    }
}

/// 把 manifest 的 name / short_name / theme_color 回填进已提取的元数据。
///
/// **只填空缺**，不覆盖页面自己声明的值：页面级的 `og:site_name` 比站点级的
/// `manifest.name` 更贴近当前页。唯一例外是 `shortName` —— 它只可能来自 manifest。
fn apply_manifest_meta(meta: &mut contract::PageMeta, m: pipeline::ManifestMeta) {
    use contract::{MetaExtractor, MetaSource};
    let note = |meta: &mut contract::PageMeta, field: &str, key: &str| {
        meta.sources.insert(
            field.to_string(),
            MetaSource { extractor: MetaExtractor::Manifest, raw_key: Some(key.to_string()) },
        );
    };
    if let Some(short) = m.short_name {
        meta.short_name = Some(short);
        note(meta, "shortName", "short_name");
    }
    if meta.site_name.is_none() {
        if let Some(name) = m.name {
            meta.site_name = Some(name);
            note(meta, "siteName", "name");
        }
    }
    if meta.theme_color.is_none() {
        if let Some(color) = m.theme_color {
            meta.theme_color = Some(color);
            note(meta, "themeColor", "theme_color");
        }
    }
}

/// Layer 1 是否拿到了非空 `<title>` —— 决定 AUTO 模式要不要回退无头浏览器。
fn html_has_title(html: &str) -> bool {
    let opts = contract::ExtractOptions {
        meta: true,
        assets: false,
        manifest: false,
        jsonld: false,
        opengraph: true,
        twitter: true,
        feeds: false,
        alternates: false,
        text: false,
    };
    let base = reqwest::Url::parse("https://placeholder.local/").expect("static url");
    extract::extract_page(html, &base, &opts)
        .meta
        .title
        .is_some_and(|t| !t.trim().is_empty())
}

/// 极简正文抽取：去掉 script/style 后取文本节点。仅在 `extract.text = true` 时调用。
fn html_to_text(html: &str) -> String {
    use ::scraper::{Html, Selector};
    let doc = Html::parse_document(html);
    let Ok(body_sel) = Selector::parse("body") else { return String::new() };
    let Some(body) = doc.select(&body_sel).next() else { return String::new() };
    let drop_sel = Selector::parse("script, style, noscript").ok();
    let mut drops: Vec<_> = Vec::new();
    if let Some(sel) = &drop_sel {
        drops = doc.select(sel).map(|e| e.id()).collect();
    }
    let mut out = String::new();
    for node in body.descendants() {
        if drops.iter().any(|d| *d == node.id()) {
            continue;
        }
        if let Some(text) = node.value().as_text() {
            let t = text.trim();
            if !t.is_empty() {
                if !out.is_empty() {
                    out.push(' ');
                }
                out.push_str(t);
            }
        }
    }
    out
}

fn error_response(status: StatusCode, code: &str, detail: Option<String>) -> Response {
    (
        status,
        Json(contract::ErrorResponse { error: code.to_string(), detail, fetch: None }),
    )
        .into_response()
}

fn scrape_error_response(e: scraper::ScrapeError, elapsed_ms: u64) -> Response {
    use scraper::ScrapeError as E;
    match e {
        E::InvalidUrl => error_response(StatusCode::UNPROCESSABLE_ENTITY, "INVALID_URL", None),
        E::ForbiddenTarget(msg) => {
            error_response(StatusCode::FORBIDDEN, "FORBIDDEN_TARGET", Some(msg))
        }
        E::Timeout(msg) => error_response(StatusCode::GATEWAY_TIMEOUT, "TIMEOUT", Some(msg)),
        E::FetchFailed(msg) => error_response(StatusCode::BAD_GATEWAY, "FETCH_FAILED", Some(msg)),
        // 沿用 FETCH_FAILED 这个错误码：调用方（API 的 classifyScrapperError）据它判定
        // "目标站点打不开"，新增码会掉进 else 分支被误判成我方服务故障。细节走 detail
        // 和 fetch 两个字段透出，机器可读的那份放 fetch。
        E::HttpStatus(d) => http_status_error_response(*d, elapsed_ms),
        E::HeadlessFailed(msg) => {
            error_response(StatusCode::BAD_GATEWAY, "HEADLESS_FAILED", Some(msg))
        }
        E::OssFailed(msg) => {
            error_response(StatusCode::SERVICE_UNAVAILABLE, "OSS_FAILED", Some(msg))
        }
    }
}

/// 非 2xx 失败：把现场同时填进 `detail`（给人看）和 `fetch`（给机器看）。
///
/// `ErrorResponse.fetch` 契约上一直写着"失败前已经拿到的传输层事实，用于排障"，
/// 但此前从没有人往里填过东西。
fn http_status_error_response(d: scraper::HttpErrorDetail, elapsed_ms: u64) -> Response {
    let fetch = contract::FetchInfo {
        final_url: d.final_url.clone(),
        redirect_chain: d
            .redirects
            .iter()
            .map(|(url, status)| contract::Redirect { url: url.clone(), status: *status })
            .collect(),
        http_status: d.status,
        layer_used: contract::RenderLayer::Http,
        from_cache: false,
        content_type: d.content_type.clone(),
        charset: None,
        byte_size: None,
        tls: None,
        timing_ms: contract::Timing { total: elapsed_ms, ..Default::default() },
    };
    (
        StatusCode::BAD_GATEWAY,
        Json(contract::ErrorResponse {
            error: "FETCH_FAILED".to_string(),
            detail: Some(d.describe()),
            fetch: Some(fetch),
        }),
    )
        .into_response()
}

#[cfg(test)]
mod tests {
    use super::*;
    use axum::body::{to_bytes, Body};
    use std::sync::atomic::{AtomicUsize, Ordering};
    use tower::ServiceExt;

    /// 构造一条最小可用的缓存条目，供只关心"缓存/鉴权是否放行"的用例复用。
    fn cached_response(title: &str) -> Arc<contract::ScrapeResponse> {
        use contract::{FetchInfo, PageMeta, RenderLayer, Timing};
        let request: contract::ScrapeRequest =
            serde_json::from_str(r#"{"url":"https://example.com"}"#).unwrap();
        let fetch = FetchInfo {
            final_url: "https://example.com/".to_string(),
            redirect_chain: Vec::new(),
            http_status: 200,
            layer_used: RenderLayer::Http,
            from_cache: false,
            content_type: None,
            charset: None,
            byte_size: None,
            tls: None,
            timing_ms: Timing { total: 1, ..Default::default() },
        };
        let mut resp = contract::ScrapeResponse::new(request, fetch);
        resp.meta = Some(PageMeta { title: Some(title.to_string()), ..Default::default() });
        Arc::new(resp)
    }

    fn test_state() -> AppState {
        AppState {
            // 与 main() 一致：关掉自动跟随（否则 fetch_html 的手动跟随拿不到 3xx），
            // 开 cookie jar（否则跨跳/预热拿到的 cookie 留不住）
            client: reqwest::Client::builder()
                .redirect(reqwest::redirect::Policy::none())
                .cookie_store(true)
                .build()
                .expect("test client"),
            headless_timeout_secs: 5,
            headless_idle_wait_secs: 1,
            cache: Arc::new(ScrapeCache::new(3600)),
            oss: None,
            auth_token: None,
        }
    }

    /// 起一个极简 HTTP/1.1 测试服务器，返回监听地址。
    ///
    /// 只为端到端验证"取回 → 提取 → manifest → 资产富化"这条完整链路，因此手写而非
    /// 引入 wiremock 之类的测试依赖（本 crate 一贯克制依赖）。
    async fn spawn_test_site() -> String {
        use tokio::io::{AsyncReadExt, AsyncWriteExt};

        // 16x8 的合法 PNG：只有文件头是真的，足够 image_dimensions 读出尺寸
        let mut png: Vec<u8> = vec![0x89, b'P', b'N', b'G', 0x0D, 0x0A, 0x1A, 0x0A];
        png.extend_from_slice(&[0, 0, 0, 13]);
        png.extend_from_slice(b"IHDR");
        png.extend_from_slice(&16u32.to_be_bytes());
        png.extend_from_slice(&8u32.to_be_bytes());
        png.extend_from_slice(&[8, 6, 0, 0, 0]);

        let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
        let addr = listener.local_addr().unwrap();
        let base = format!("http://{addr}");

        let html = format!(
            r#"<html lang="en"><head>
<title>Fallback Title</title>
<meta property="og:title" content="OG Title"/>
<meta name="description" content="From meta name"/>
<link rel="icon" sizes="16x16" href="/icon.png"/>
<link rel="apple-touch-icon" href="/icon.png"/>
<link rel="manifest" href="/site.webmanifest"/>
<script type="application/ld+json">{{"@type":"Organization","logo":"{base}/icon.png"}}</script>
</head><body>hello</body></html>"#
        );
        // r## 而非 r#：主题色里的 "# 会提前闭合 r#"…"# 字面量
        let manifest = r##"{"name":"Full Name","short_name":"Shorty","theme_color":"#123456",
            "icons":[{"src":"/icon.png","sizes":"512x512","type":"image/png","purpose":"maskable"}]}"##;

        tokio::spawn(async move {
            loop {
                let Ok((mut sock, _)) = listener.accept().await else { break };
                let (html, manifest, png) = (html.clone(), manifest.to_string(), png.clone());
                tokio::spawn(async move {
                    let mut buf = [0u8; 2048];
                    let Ok(n) = sock.read(&mut buf).await else { return };
                    let req = String::from_utf8_lossy(&buf[..n]);
                    let path = req.split_whitespace().nth(1).unwrap_or("/").to_string();

                    let resp: Vec<u8> = match path.as_str() {
                        // 用一跳 302 验证 redirectChain 与 finalUrl
                        "/start" => b"HTTP/1.1 302 Found\r\nLocation: /page\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".to_vec(),
                        "/page" => format!(
                            "HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=utf-8\r\nContent-Length: {}\r\nConnection: close\r\n\r\n{html}",
                            html.len()
                        )
                        .into_bytes(),
                        "/site.webmanifest" => format!(
                            "HTTP/1.1 200 OK\r\nContent-Type: application/manifest+json\r\nContent-Length: {}\r\nConnection: close\r\n\r\n{manifest}",
                            manifest.len()
                        )
                        .into_bytes(),
                        "/icon.png" => {
                            let mut r = format!(
                                "HTTP/1.1 200 OK\r\nContent-Type: image/png\r\nContent-Length: {}\r\nConnection: close\r\n\r\n",
                                png.len()
                            )
                            .into_bytes();
                            r.extend_from_slice(&png);
                            r
                        }
                        _ => b"HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".to_vec(),
                    };
                    let _ = sock.write_all(&resp).await;
                    let _ = sock.shutdown().await;
                });
            }
        });

        base
    }

    /// 起一个复刻 B 站风控行为的测试站，返回 (监听地址, 收到的请求头快照)。
    ///
    /// 规则与线上观察到的一致：
    /// - `/` 永远 200，并在响应里下发 `wm=1` cookie；
    /// - `/guarded` 在**没有** `wm=1` cookie 时返回 412 拦截页，有则 200。
    ///
    /// 这正是"根域名正常、带 path 的 412"那个现象的最小复现。
    async fn spawn_guarded_site() -> (String, Arc<std::sync::Mutex<Vec<String>>>) {
        use tokio::io::{AsyncReadExt, AsyncWriteExt};

        let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
        let base = format!("http://{}", listener.local_addr().unwrap());
        let seen: Arc<std::sync::Mutex<Vec<String>>> = Arc::new(std::sync::Mutex::new(Vec::new()));
        let seen_bg = seen.clone();

        tokio::spawn(async move {
            loop {
                let Ok((mut sock, _)) = listener.accept().await else { break };
                let seen = seen_bg.clone();
                tokio::spawn(async move {
                    let mut buf = [0u8; 4096];
                    let Ok(n) = sock.read(&mut buf).await else { return };
                    let req = String::from_utf8_lossy(&buf[..n]).to_string();
                    let path = req.split_whitespace().nth(1).unwrap_or("/").to_string();
                    let has_cookie = req.to_ascii_lowercase().contains("cookie: wm=1");
                    seen.lock().unwrap().push(req);

                    let ok = |body: &str| {
                        format!(
                            "HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=utf-8\r\nSet-Cookie: wm=1; Path=/\r\nContent-Length: {}\r\nConnection: close\r\n\r\n{body}",
                            body.len()
                        )
                        .into_bytes()
                    };
                    let resp: Vec<u8> = match (path.as_str(), has_cookie) {
                        ("/", _) => ok("<html><head><title>Home</title></head><body>home</body></html>"),
                        ("/guarded", true) => {
                            ok("<html><head><title>Guarded Page</title></head><body>ok</body></html>")
                        }
                        ("/guarded", false) => {
                            let body = "<html><head><style>b{}</style></head><body><h1>访问被拒绝</h1></body></html>";
                            format!(
                                "HTTP/1.1 412 Precondition Failed\r\nContent-Type: text/html\r\nServer: waf-test\r\nSet-Cookie: wm=1; Path=/\r\nContent-Length: {}\r\nConnection: close\r\n\r\n{body}",
                                body.len()
                            )
                            .into_bytes()
                        }
                        _ => b"HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".to_vec(),
                    };
                    let _ = sock.write_all(&resp).await;
                    let _ = sock.shutdown().await;
                });
            }
        });

        (base, seen)
    }

    /// Layer 1 必须发出一整套浏览器请求头，而不是只发一个 UA。
    ///
    /// 只带 UA、`accept: */*`、无 `Sec-Fetch-*` 的请求是 WAF 最容易识别的爬虫特征。
    #[tokio::test]
    async fn layer1_sends_a_consistent_browser_header_set() {
        let _env = env_guard().await;
        std::env::set_var("SSRF_ALLOW_PRIVATE", "1");
        let (base, seen) = spawn_guarded_site().await;

        let app = build_router(test_state(), 32);
        let req = json_request(
            "POST",
            "/scrape",
            serde_json::json!({
                "url": format!("{base}/"),
                "render": { "mode": "HTTP" },
                "assets": { "download": "PROBE" }
            }),
            None,
        );
        let (status, json) = call(app, req).await;
        std::env::remove_var("SSRF_ALLOW_PRIVATE");
        assert_eq!(status, StatusCode::OK, "body: {json}");

        // 只看导航请求：资产/favicon 走的是 pipeline 的普通下载，不该被当成文档导航
        let raw = seen
            .lock()
            .unwrap()
            .iter()
            .find(|r| r.starts_with("GET / "))
            .expect("应有一次对 / 的导航请求")
            .to_ascii_lowercase();
        for header in [
            "accept: text/html",
            "accept-language:",
            "accept-encoding:", // 由 reqwest 的 gzip/brotli/deflate 特性自动带上
            "sec-fetch-dest: document",
            "sec-fetch-mode: navigate",
            "sec-fetch-site: none",
            "upgrade-insecure-requests: 1",
        ] {
            assert!(raw.contains(header), "缺少请求头 {header}，实际:\n{raw}");
        }
        assert!(!raw.contains("accept: */*"), "不该再退回 reqwest 默认的 accept: */*");
    }

    /// 被反爬拦下时先访问根路径拿 cookie 再重试——正是 B 站 `/video/BVxxx` 那个场景。
    #[tokio::test]
    async fn anti_bot_block_recovers_via_origin_warm_up() {
        let _env = env_guard().await;
        std::env::set_var("SSRF_ALLOW_PRIVATE", "1");
        let (base, seen) = spawn_guarded_site().await;

        let app = build_router(test_state(), 32);
        let req = json_request(
            "POST",
            "/scrape",
            serde_json::json!({
                "url": format!("{base}/guarded"),
                "render": { "mode": "HTTP" },
                "assets": { "download": "PROBE" }
            }),
            None,
        );
        let (status, json) = call(app, req).await;
        std::env::remove_var("SSRF_ALLOW_PRIVATE");

        assert_eq!(status, StatusCode::OK, "预热重试后应当抓成功，body: {json}");
        assert_eq!(json["meta"]["title"], "Guarded Page");

        // 抓完还会去取 favicon，只校验前三条导航请求的次序
        let paths: Vec<String> = seen
            .lock()
            .unwrap()
            .iter()
            .filter_map(|r| r.split_whitespace().nth(1).map(str::to_string))
            .take(3)
            .collect();
        assert_eq!(paths, vec!["/guarded", "/", "/guarded"], "应为 拦截 → 预热根路径 → 重试");
    }

    /// 无法恢复的状态码要给出完整现场，而不是 reqwest 那句干巴巴的 error_for_status。
    #[tokio::test]
    async fn http_status_failure_reports_a_detailed_diagnosis() {
        let _env = env_guard().await;
        std::env::set_var("SSRF_ALLOW_PRIVATE", "1");
        let (base, _seen) = spawn_guarded_site().await;

        let app = build_router(test_state(), 32);
        let req = json_request(
            "POST",
            "/scrape",
            serde_json::json!({
                "url": format!("{base}/nope"),
                "render": { "mode": "HTTP" },
                "assets": { "download": "PROBE" }
            }),
            None,
        );
        let (status, json) = call(app, req).await;
        std::env::remove_var("SSRF_ALLOW_PRIVATE");

        assert_eq!(status, StatusCode::BAD_GATEWAY);
        // 错误码保持不变：API 侧靠它区分"目标站点的问题"与"我方服务的问题"
        assert_eq!(json["error"], "FETCH_FAILED");
        let detail = json["detail"].as_str().unwrap_or_default();
        assert!(detail.contains("HTTP 404 Not Found"), "{detail}");
        assert!(detail.contains("目标页面不存在"), "应给出排障方向: {detail}");
        assert!(detail.contains("/nope"), "应指明是哪个 URL: {detail}");
        // 机器可读的那份走 fetch —— 这个字段契约里一直有，此前从没填过
        assert_eq!(json["fetch"]["httpStatus"], 404);
        assert_eq!(json["fetch"]["layerUsed"], "HTTP");
    }

    /// 端到端跑通整条链路，并逐条验证本次重构的核心承诺。
    #[tokio::test]
    async fn end_to_end_scrape_produces_full_contract() {
        let _env = env_guard().await;
        std::env::set_var("SSRF_ALLOW_PRIVATE", "1");
        let base = spawn_test_site().await;

        let app = build_router(test_state(), 32);
        let req = json_request(
            "POST",
            "/scrape",
            serde_json::json!({
                "url": format!("{base}/start"),
                "render": { "mode": "HTTP" },
                "assets": { "download": "PROBE" }
            }),
            None,
        );
        let (status, json) = call(app, req).await;
        std::env::remove_var("SSRF_ALLOW_PRIVATE");
        assert_eq!(status, StatusCode::OK, "body: {json}");

        // 1. 手动跟随重定向：链路可见，finalUrl 是跳转后的地址
        assert_eq!(json["fetch"]["finalUrl"], format!("{base}/page"));
        assert_eq!(json["fetch"]["redirectChain"][0]["status"], 302);
        assert_eq!(json["fetch"]["layerUsed"], "HTTP");
        assert_eq!(json["fetch"]["httpStatus"], 200);

        // 2. 字段级出处：title 来自 OG，description 回落到 meta[name]，两者如实分开记录
        assert_eq!(json["meta"]["title"], "OG Title");
        assert_eq!(json["meta"]["sources"]["title"]["extractor"], "OG");
        assert_eq!(json["meta"]["description"], "From meta name");
        assert_eq!(json["meta"]["sources"]["description"]["extractor"], "META_NAME");

        // 3. manifest 被真的抓了：shortName 回填，且出处标成 MANIFEST
        assert_eq!(json["meta"]["shortName"], "Shorty");
        assert_eq!(json["meta"]["sources"]["shortName"]["extractor"], "MANIFEST");
        assert_eq!(json["manifest"]["raw"]["name"], "Full Name");

        // 4. 每张声明独立成条，且带出处而非用途
        let assets = json["assets"].as_array().expect("assets 应为数组");
        let kinds: Vec<&str> = assets.iter().map(|a| a["extractor"].as_str().unwrap()).collect();
        for expected in ["LINK_ICON", "APPLE_TOUCH_ICON", "JSON_LD_ORG_LOGO", "MANIFEST_ICON"] {
            assert!(kinds.contains(&expected), "缺少 {expected}，实际: {kinds:?}");
        }
        assert!(assets.iter().all(|a| a.get("role").is_none()), "响应里不该出现 role");

        // 5. PROBE 取回了正文：真实尺寸覆盖了声明的 sizes，并算出 contentHash
        let link_icon = assets.iter().find(|a| a["extractor"] == "LINK_ICON").unwrap();
        assert_eq!(link_icon["width"], 16);
        assert_eq!(link_icon["height"], 8);
        assert_eq!(link_icon["mime"], "image/png");
        let hash = link_icon["contentHash"].as_str().unwrap();
        assert!(hash.starts_with("sha256:"));
        // PROBE 不保留正文
        assert!(link_icon.get("dataUrl").is_none());
        assert!(link_icon.get("storageKey").is_none());

        // 6. 同一张图被多个 extractor 命中时 hash 相同 —— 这正是"该站没有独立 LOGO"的判据
        let apple = assets.iter().find(|a| a["extractor"] == "APPLE_TOUCH_ICON").unwrap();
        assert_eq!(apple["contentHash"].as_str().unwrap(), hash);
        // 声明的 512x512 是假的，实测应以真实像素为准
        let manifest_icon = assets.iter().find(|a| a["extractor"] == "MANIFEST_ICON").unwrap();
        assert_eq!(manifest_icon["declared"]["sizes"], "512x512");
        assert_eq!(manifest_icon["width"], 16, "真实尺寸应覆盖声明值");
        assert_eq!(manifest_icon["declared"]["purpose"], "maskable");

        // 7. 原始块原样透传
        assert_eq!(json["opengraph"]["title"], "OG Title");
        assert_eq!(json["jsonld"][0]["@type"], "Organization");
    }

    /// BYPASS 必须无视缓存重抓 —— 管理后台"重试"依赖这个语义
    #[tokio::test]
    async fn cache_bypass_ignores_a_cached_entry() {
        let state = test_state();
        state.cache.set("https://example.com/cached", cached_response("Stale")).await;
        let app = build_router(state, 32);
        let req = json_request(
            "POST",
            "/scrape",
            serde_json::json!({"url": "https://example.com/cached", "cache": {"mode": "BYPASS"}}),
            None,
        );
        let (status, json) = call(app, req).await;
        // 没命中缓存就会真的去抓 example.com，测试环境下必然失败 —— 失败本身即证明绕过了缓存
        assert_ne!(status, StatusCode::OK, "BYPASS 不应命中缓存: {json}");
    }

    /// ONLY_IF_CACHED 未命中时应 404，且绝不发起网络请求
    #[tokio::test]
    async fn only_if_cached_misses_with_404() {
        let app = build_router(test_state(), 32);
        let req = json_request(
            "POST",
            "/scrape",
            serde_json::json!({"url": "https://example.com/never", "cache": {"mode": "ONLY_IF_CACHED"}}),
            None,
        );
        let (status, json) = call(app, req).await;
        assert_eq!(status, StatusCode::NOT_FOUND);
        assert_eq!(json["error"], "CACHE_MISS");
    }

    /// 请求体里的未知字段必须被拒绝，而不是静默当成默认值
    #[tokio::test]
    async fn legacy_headless_field_is_rejected() {
        let app = build_router(test_state(), 32);
        let req = json_request(
            "POST",
            "/scrape",
            serde_json::json!({"url": "https://example.com/", "headless": true}),
            None,
        );
        let (status, _) = call(app, req).await;
        assert_eq!(
            status,
            StatusCode::UNPROCESSABLE_ENTITY,
            "旧的 headless 字段应被 deny_unknown_fields 拒绝"
        );
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
        // axum 的 Json 提取器被拒时返回的是纯文本而非 JSON（如 deny_unknown_fields
        // 命中），此时退化成字符串，让只关心状态码的用例不必为此炸掉
        let json = if bytes.is_empty() {
            serde_json::Value::Null
        } else {
            serde_json::from_slice(&bytes).unwrap_or_else(|_| {
                serde_json::Value::String(String::from_utf8_lossy(&bytes).into_owned())
            })
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
        assert_eq!(json["error"], "INVALID_URL");
    }

    #[tokio::test]
    async fn scrape_forbidden_target_returns_403() {
        let _env = env_guard().await;
        std::env::remove_var("SSRF_ALLOW_PRIVATE");
        let app = build_router(test_state(), 32);
        let req = json_request("POST", "/scrape", serde_json::json!({"url": "http://127.0.0.1/"}), None);
        let (status, json) = call(app, req).await;
        assert_eq!(status, StatusCode::FORBIDDEN);
        assert_eq!(json["error"], "FORBIDDEN_TARGET");
    }

    #[tokio::test]
    async fn scrape_negative_cache_hit_returns_502_with_retry_message() {
        let state = test_state();
        state.cache.set_error("https://example.com/flaky").await;
        let app = build_router(state, 32);
        let req = json_request("POST", "/scrape", serde_json::json!({"url": "https://example.com/flaky"}), None);
        let (status, json) = call(app, req).await;
        assert_eq!(status, StatusCode::BAD_GATEWAY);
        assert_eq!(json["error"], "RECENTLY_FAILED");
    }

    #[tokio::test]
    async fn scrape_cache_hit_returns_cached_result() {
        let state = test_state();
        state.cache.set("https://example.com/cached", cached_response("Cached Title")).await;
        let app = build_router(state, 32);
        let req = json_request("POST", "/scrape", serde_json::json!({"url": "https://example.com/cached"}), None);
        let (status, json) = call(app, req).await;
        assert_eq!(status, StatusCode::OK);
        assert_eq!(json["meta"]["title"], "Cached Title");
        assert_eq!(json["fetch"]["fromCache"], true);
    }

    #[tokio::test]
    async fn scrape_without_token_is_rejected_when_auth_enabled() {
        let mut state = test_state();
        state.auth_token = Some(Arc::new("s3cret".to_string()));
        let app = build_router(state, 32);
        let req = json_request("POST", "/scrape", serde_json::json!({"url": "https://example.com/"}), None);
        let (status, json) = call(app, req).await;
        assert_eq!(status, StatusCode::UNAUTHORIZED);
        assert_eq!(json["error"], "UNAUTHORIZED");
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
                cached_response("Authed"),
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
        assert_eq!(json["meta"]["title"], "Authed");
    }

    #[tokio::test]
    async fn ping_invalid_url_returns_422() {
        let app = build_router(test_state(), 32);
        let req = json_request("POST", "/ping", serde_json::json!({"url": "not-a-url"}), None);
        let (status, json) = call(app, req).await;
        assert_eq!(status, StatusCode::UNPROCESSABLE_ENTITY);
        assert_eq!(json["error"], "INVALID_URL");
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
        let _env = env_guard().await;
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
