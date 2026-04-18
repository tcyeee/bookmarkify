mod cache;
mod headless;
mod scraper;

use axum::{
    extract::State,
    http::{HeaderMap, Request, StatusCode},
    middleware::{self, Next},
    response::{IntoResponse, Json, Response},
    routing::{get, post},
    Router,
};
use cache::ScrapeCache;
use serde::{Deserialize, Serialize};
use std::{env, sync::Arc, time::Duration};
use tower_governor::{
    governor::GovernorConfigBuilder, key_extractor::KeyExtractor, GovernorError, GovernorLayer,
};
use tower_http::trace::TraceLayer;

/// Extracts the API key from the `Authorization: Bearer <key>` header for rate limiting.
#[derive(Debug, Clone)]
struct ApiKeyExtractor;

impl KeyExtractor for ApiKeyExtractor {
    type Key = String;

    fn extract<T>(&self, req: &Request<T>) -> Result<Self::Key, GovernorError> {
        req.headers()
            .get("Authorization")
            .and_then(|v| v.to_str().ok())
            .and_then(|v| v.strip_prefix("Bearer "))
            .map(|key| key.to_owned())
            .ok_or(GovernorError::UnableToExtractKey)
    }
}

#[derive(Clone)]
struct AppState {
    client: reqwest::Client,
    api_key: String,
    headless_timeout_secs: u64,
    cache: Arc<ScrapeCache>,
}

#[tokio::main]
async fn main() {
    tracing_subscriber::fmt()
        .with_env_filter(
            tracing_subscriber::EnvFilter::from_default_env()
                .add_directive("scraper_service=info".parse().unwrap()),
        )
        .init();

    let api_key = env::var("API_KEY").expect("API_KEY must be set");
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
    let rate_limit_rps: u32 = env::var("RATE_LIMIT_RPS")
        .ok()
        .and_then(|v| v.parse().ok())
        .unwrap_or(10);
    let port: u16 = env::var("PORT")
        .ok()
        .and_then(|v| v.parse().ok())
        .unwrap_or(3000);

    let client = reqwest::Client::builder()
        .timeout(Duration::from_secs(timeout_secs))
        .build()
        .expect("failed to build reqwest client");

    let state = AppState { client, api_key, headless_timeout_secs, cache };

    let governor_config = Arc::new(
        GovernorConfigBuilder::default()
            .key_extractor(ApiKeyExtractor)
            .per_second(1)
            .burst_size(rate_limit_rps * 2)
            .finish()
            .expect("invalid governor config"),
    );

    let protected = Router::new()
        .route("/scrape", post(scrape_handler))
        .layer(GovernorLayer { config: governor_config })
        .layer(middleware::from_fn_with_state(state.clone(), auth_middleware));

    let app = Router::new()
        .route("/health", get(health_handler))
        .merge(protected)
        .layer(TraceLayer::new_for_http())
        .with_state(state);

    let addr = format!("0.0.0.0:{port}");
    tracing::info!("scraper-service listening on {addr}");
    let listener = tokio::net::TcpListener::bind(&addr).await.unwrap();
    axum::serve(listener, app).await.unwrap();
}

async fn health_handler() -> Json<serde_json::Value> {
    Json(serde_json::json!({"status": "ok"}))
}

async fn auth_middleware(
    State(state): State<AppState>,
    headers: HeaderMap,
    request: Request<axum::body::Body>,
    next: Next,
) -> Result<Response, (StatusCode, Json<serde_json::Value>)> {
    let token = headers
        .get("Authorization")
        .and_then(|v| v.to_str().ok())
        .and_then(|v| v.strip_prefix("Bearer "));

    match token {
        Some(t) if t == state.api_key => Ok(next.run(request).await),
        _ => Err((
            StatusCode::UNAUTHORIZED,
            Json(serde_json::json!({"error": "unauthorized"})),
        )),
    }
}

#[derive(Deserialize)]
struct ScrapeRequest {
    url: String,
    headless: Option<bool>,
}

#[derive(Serialize)]
struct ScrapeResponse {
    title: Option<String>,
    description: Option<String>,
    image: Option<String>,
    favicon: Option<String>,
    source: String,
    #[serde(skip_serializing_if = "Option::is_none")]
    cached: Option<bool>,
}

#[derive(Serialize)]
struct ErrorResponse {
    error: String,
    #[serde(skip_serializing_if = "Option::is_none")]
    detail: Option<String>,
}

async fn scrape_handler(
    State(state): State<AppState>,
    Json(body): Json<ScrapeRequest>,
) -> Response {
    // cache lookup
    if let Some(cached) = state.cache.get(&body.url).await {
        return Json(ScrapeResponse {
            title: cached.title.clone(),
            description: cached.description.clone(),
            image: cached.image.clone(),
            favicon: cached.favicon.clone(),
            source: cached.source.clone(),
            cached: Some(true),
        })
        .into_response();
    }

    let result = if body.headless.unwrap_or(false) {
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
            let r = Arc::new(r);
            state.cache.set(&body.url, Arc::clone(&r)).await;
            Json(ScrapeResponse {
                title: r.title.clone(),
                description: r.description.clone(),
                image: r.image.clone(),
                favicon: r.favicon.clone(),
                source: r.source.clone(),
                cached: None,
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
    }
}
