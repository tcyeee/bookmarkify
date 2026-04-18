mod scraper;

use axum::{
    extract::State,
    http::{HeaderMap, Request, StatusCode},
    middleware::{self, Next},
    response::{IntoResponse, Json, Response},
    routing::{get, post},
    Router,
};
use serde::{Deserialize, Serialize};
use std::{env, time::Duration};
use tower_http::trace::TraceLayer;

#[derive(Clone)]
struct AppState {
    client: reqwest::Client,
    api_key: String,
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
    let port: u16 = env::var("PORT")
        .ok()
        .and_then(|v| v.parse().ok())
        .unwrap_or(3000);

    let client = reqwest::Client::builder()
        .timeout(Duration::from_secs(timeout_secs))
        .build()
        .expect("failed to build reqwest client");

    let state = AppState { client, api_key };

    let protected = Router::new()
        .route("/scrape", post(scrape_handler))
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
}

#[derive(Serialize)]
struct ScrapeResponse {
    title: Option<String>,
    description: Option<String>,
    image: Option<String>,
    favicon: Option<String>,
    source: String,
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
    match scraper::scrape(&body.url, &state.client).await {
        Ok(result) => Json(ScrapeResponse {
            title: result.title,
            description: result.description,
            image: result.image,
            favicon: result.favicon,
            source: result.source,
        })
        .into_response(),

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
    }
}
