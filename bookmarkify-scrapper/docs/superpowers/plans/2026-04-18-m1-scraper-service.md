# M1 scraper-service Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build `crates/scraper-service` — an axum HTTP service with API Key auth and Layer 1 metadata scraping via reqwest + scraper crate.

**Architecture:** Two files: `main.rs` owns the axum router, auth middleware, and request/response types; `scraper.rs` owns reqwest fetching and HTML metadata extraction. Shared state (`AppState`) holds a `reqwest::Client` and the `api_key` string, injected via axum's `State` extractor.

**Tech Stack:** axum 0.7, tokio 1, reqwest 0.12, scraper 0.19, serde/serde_json 1, tower-http 0.5

---

## File Map

| File | Action | Responsibility |
|------|--------|----------------|
| `Cargo.toml` (workspace root) | Modify | Add `crates/scraper-service` to `members` |
| `crates/scraper-service/Cargo.toml` | Create | Crate manifest with all dependencies |
| `crates/scraper-service/src/main.rs` | Create | axum server, routes, auth middleware, handlers |
| `crates/scraper-service/src/scraper.rs` | Create | `ScrapeResult`, `ScrapeError`, `scrape()`, HTML parsing helpers |

---

## Task 1: Scaffold the crate

**Files:**
- Modify: `Cargo.toml`
- Create: `crates/scraper-service/Cargo.toml`
- Create: `crates/scraper-service/src/main.rs`
- Create: `crates/scraper-service/src/scraper.rs`

- [ ] **Step 1: Add crate to workspace**

Edit `Cargo.toml`:
```toml
[workspace]
members = ["crates/scraper-demo", "crates/scraper-service"]
resolver = "2"
```

- [ ] **Step 2: Create crate Cargo.toml**

Create `crates/scraper-service/Cargo.toml`:
```toml
[package]
name = "scraper-service"
version = "0.1.0"
edition = "2021"

[dependencies]
axum = "0.7"
tokio = { version = "1", features = ["full"] }
reqwest = { version = "0.12", features = ["json"] }
scraper = "0.19"
serde = { version = "1", features = ["derive"] }
serde_json = "1"
tower-http = { version = "0.5", features = ["trace"] }
```

- [ ] **Step 3: Create stub main.rs**

Create `crates/scraper-service/src/main.rs`:
```rust
mod scraper;

#[tokio::main]
async fn main() {
    println!("scraper-service starting...");
}
```

- [ ] **Step 4: Create stub scraper.rs**

Create `crates/scraper-service/src/scraper.rs`:
```rust
pub struct ScrapeResult;
pub enum ScrapeError {}
```

- [ ] **Step 5: Verify it compiles**

```bash
cargo build -p scraper-service
```
Expected: compiles without errors.

- [ ] **Step 6: Commit**

```bash
git add Cargo.toml crates/scraper-service/
git commit -m "feat(m1): scaffold scraper-service crate"
```

---

## Task 2: Define types and parsing helpers in scraper.rs

**Files:**
- Modify: `crates/scraper-service/src/scraper.rs`

- [ ] **Step 1: Write failing tests for HTML parsing helpers**

Replace `crates/scraper-service/src/scraper.rs` with:
```rust
use scraper::{Html, Selector};
use serde::Serialize;

#[derive(Debug, Serialize, PartialEq)]
pub struct ScrapeResult {
    pub title: Option<String>,
    pub description: Option<String>,
    pub image: Option<String>,
    pub favicon: Option<String>,
    pub source: String,
}

#[derive(Debug)]
pub enum ScrapeError {
    InvalidUrl,
    Timeout,
    FetchFailed(String),
}

pub fn meta_property(document: &Html, property: &str) -> Option<String> {
    todo!()
}

pub fn meta_name(document: &Html, name: &str) -> Option<String> {
    todo!()
}

pub fn extract_title(document: &Html) -> Option<String> {
    todo!()
}

pub fn extract_favicon(document: &Html, base_url: &reqwest::Url) -> Option<String> {
    todo!()
}

pub fn extract_json_ld(document: &Html) -> Option<(Option<String>, Option<String>, Option<String>)> {
    todo!()
}

pub fn parse_metadata(html: &str, base_url: &reqwest::Url) -> ScrapeResult {
    todo!()
}

pub async fn scrape(url: &str, client: &reqwest::Client) -> Result<ScrapeResult, ScrapeError> {
    todo!()
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
    fn extract_json_ld_extracts_name() {
        let d = doc(r#"<html><head><script type="application/ld+json">{"@type":"Article","name":"LD Title","description":"LD Desc"}</script></head></html>"#);
        let result = extract_json_ld(&d);
        assert!(result.is_some());
        let (title, desc, _) = result.unwrap();
        assert_eq!(title, Some("LD Title".to_string()));
        assert_eq!(desc, Some("LD Desc".to_string()));
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
}
```

- [ ] **Step 2: Run tests, confirm they fail with `todo!()`**

```bash
cargo test -p scraper-service 2>&1 | head -30
```
Expected: multiple `panicked at 'not yet implemented'` errors.

- [ ] **Step 3: Implement parsing helpers**

Replace all `todo!()` stubs (keep the `scrape` and `parse_metadata` stubs for now) with real implementations:

```rust
pub fn meta_property(document: &Html, property: &str) -> Option<String> {
    let selector = Selector::parse(&format!(r#"meta[property="{}"]"#, property)).ok()?;
    document
        .select(&selector)
        .next()
        .and_then(|e| e.value().attr("content"))
        .map(str::to_string)
}

pub fn meta_name(document: &Html, name: &str) -> Option<String> {
    let selector = Selector::parse(&format!(r#"meta[name="{}"]"#, name)).ok()?;
    document
        .select(&selector)
        .next()
        .and_then(|e| e.value().attr("content"))
        .map(str::to_string)
}

pub fn extract_title(document: &Html) -> Option<String> {
    let selector = Selector::parse("title").ok()?;
    document
        .select(&selector)
        .next()
        .map(|e| e.text().collect::<String>().trim().to_string())
        .filter(|s| !s.is_empty())
}

pub fn extract_favicon(document: &Html, base_url: &reqwest::Url) -> Option<String> {
    let selector = Selector::parse(r#"link[rel~="icon"]"#).ok()?;
    let href = document
        .select(&selector)
        .next()
        .and_then(|e| e.value().attr("href"));

    let favicon_url = match href {
        Some(h) => base_url.join(h).ok()?,
        None => base_url.join("/favicon.ico").ok()?,
    };
    Some(favicon_url.to_string())
}

pub fn extract_json_ld(document: &Html) -> Option<(Option<String>, Option<String>, Option<String>)> {
    let selector = Selector::parse(r#"script[type="application/ld+json"]"#).ok()?;
    for element in document.select(&selector) {
        let text = element.text().collect::<String>();
        if let Ok(json) = serde_json::from_str::<serde_json::Value>(&text) {
            let title = json.get("name").and_then(|v| v.as_str()).map(String::from);
            if title.is_none() {
                continue;
            }
            let desc = json.get("description").and_then(|v| v.as_str()).map(String::from);
            let image = json
                .get("image")
                .and_then(|v| v.as_str().map(String::from).or_else(|| {
                    v.get("url").and_then(|u| u.as_str()).map(String::from)
                }));
            return Some((title, desc, image));
        }
    }
    None
}
```

- [ ] **Step 4: Run helper tests, confirm they pass**

```bash
cargo test -p scraper-service 2>&1 | grep -E "test .* (ok|FAILED)"
```
Expected: all helper tests pass (parse_metadata and scrape tests still panic — that's fine).

- [ ] **Step 5: Commit**

```bash
git add crates/scraper-service/src/scraper.rs
git commit -m "feat(m1): implement HTML parsing helpers with tests"
```

---

## Task 3: Implement parse_metadata and scrape()

**Files:**
- Modify: `crates/scraper-service/src/scraper.rs`

- [ ] **Step 1: Implement parse_metadata**

Replace the `parse_metadata` `todo!()`:
```rust
pub fn parse_metadata(html: &str, base_url: &reqwest::Url) -> ScrapeResult {
    let document = Html::parse_document(html);

    // OG tags
    if let Some(title) = meta_property(&document, "og:title") {
        return ScrapeResult {
            title: Some(title),
            description: meta_property(&document, "og:description")
                .or_else(|| meta_name(&document, "description")),
            image: meta_property(&document, "og:image"),
            favicon: extract_favicon(&document, base_url),
            source: "og".to_string(),
        };
    }

    // Twitter Card
    if let Some(title) = meta_name(&document, "twitter:title") {
        return ScrapeResult {
            title: Some(title),
            description: meta_name(&document, "twitter:description")
                .or_else(|| meta_name(&document, "description")),
            image: meta_name(&document, "twitter:image"),
            favicon: extract_favicon(&document, base_url),
            source: "twitter_card".to_string(),
        };
    }

    // JSON-LD
    if let Some((title, desc, image)) = extract_json_ld(&document) {
        return ScrapeResult {
            title,
            description: desc.or_else(|| meta_name(&document, "description")),
            image,
            favicon: extract_favicon(&document, base_url),
            source: "json_ld".to_string(),
        };
    }

    // HTML fallback
    ScrapeResult {
        title: extract_title(&document),
        description: meta_name(&document, "description"),
        image: None,
        favicon: extract_favicon(&document, base_url),
        source: "html".to_string(),
    }
}
```

- [ ] **Step 2: Implement scrape()**

Replace the `scrape` `todo!()`:
```rust
pub async fn scrape(url: &str, client: &reqwest::Client) -> Result<ScrapeResult, ScrapeError> {
    let parsed = reqwest::Url::parse(url).map_err(|_| ScrapeError::InvalidUrl)?;

    let response = client
        .get(url)
        .header(
            "User-Agent",
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) \
             AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
        )
        .send()
        .await
        .map_err(|e| {
            if e.is_timeout() {
                ScrapeError::Timeout
            } else {
                ScrapeError::FetchFailed(e.to_string())
            }
        })?;

    let body = response
        .text()
        .await
        .map_err(|e| ScrapeError::FetchFailed(e.to_string()))?;

    Ok(parse_metadata(&body, &parsed))
}
```

- [ ] **Step 3: Add `use serde_json;` at top of file if not present**

Ensure the top of `scraper.rs` has:
```rust
use scraper::{Html, Selector};
use serde::Serialize;
```

- [ ] **Step 4: Run all scraper tests**

```bash
cargo test -p scraper-service 2>&1
```
Expected: all tests pass.

- [ ] **Step 5: Commit**

```bash
git add crates/scraper-service/src/scraper.rs
git commit -m "feat(m1): implement parse_metadata and scrape() in scraper.rs"
```

---

## Task 4: Build axum server with health endpoint

**Files:**
- Modify: `crates/scraper-service/src/main.rs`

- [ ] **Step 1: Replace main.rs with full server skeleton**

```rust
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
    println!("scraper-service listening on {addr}");
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
```

- [ ] **Step 2: Verify it compiles**

```bash
cargo build -p scraper-service
```
Expected: compiles without errors.

- [ ] **Step 3: Smoke test the health endpoint**

In one terminal:
```bash
API_KEY=testkey cargo run -p scraper-service
```

In another terminal:
```bash
curl -s http://localhost:3000/health
```
Expected: `{"status":"ok"}`

- [ ] **Step 4: Smoke test auth rejection**

```bash
curl -s -X POST http://localhost:3000/scrape \
  -H "Content-Type: application/json" \
  -d '{"url":"https://example.com"}'
```
Expected: `{"error":"unauthorized"}` with HTTP 401.

- [ ] **Step 5: Smoke test with valid key**

```bash
curl -s -X POST http://localhost:3000/scrape \
  -H "Authorization: Bearer testkey" \
  -H "Content-Type: application/json" \
  -d '{"url":"https://example.com"}'
```
Expected: JSON with `title`, `source` fields (example.com has basic HTML).

- [ ] **Step 6: Stop the server, commit**

```bash
git add crates/scraper-service/src/main.rs
git commit -m "feat(m1): add axum server with health endpoint, auth middleware, and scrape handler"
```

---

## Task 5: Integration smoke test against real sites

**Files:** No new files — manual verification only.

- [ ] **Step 1: Test against huaban.com**

```bash
API_KEY=testkey cargo run -p scraper-service &
sleep 2
curl -s -X POST http://localhost:3000/scrape \
  -H "Authorization: Bearer testkey" \
  -H "Content-Type: application/json" \
  -d '{"url":"https://huaban.com/"}' | jq .
```
Expected: `title` and `description` non-null, `source` = `"og"` or `"html"`.

- [ ] **Step 2: Test invalid URL**

```bash
curl -s -X POST http://localhost:3000/scrape \
  -H "Authorization: Bearer testkey" \
  -H "Content-Type: application/json" \
  -d '{"url":"not-a-url"}' | jq .
```
Expected: `{"error":"invalid url"}` with HTTP 422.

- [ ] **Step 3: Kill server and update design doc M1 checklist**

Kill the server (`Ctrl+C` or `kill %1`).

In `202603252239-bookmrkify-scraper设计文档.md`, mark all M1 items done:
```markdown
### M1 — 模块 A 基础（3–4 天）✅

- [x] axum 框架搭建，健康检查接口
- [x] API Key 认证中间件
- [x] `POST /scrape` 接口：接收 URL，返回元数据 JSON
- [x] Layer 1 解析：OG tags、Twitter Card、JSON-LD、`<title>`
```

- [ ] **Step 4: Final commit**

```bash
git add "202603252239-bookmrkify-scraper设计文档.md"
git commit -m "docs(m1): mark M1 complete in project design doc"
```
