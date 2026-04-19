# OSS Asset Upload Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Upload scraped favicon, OG image, and screenshot to Alibaba Cloud OSS and return stable OSS URLs in `/scrape` responses.

**Architecture:** New `oss.rs` module wraps `oss-rust-sdk` with an `OssClient` struct held in `AppState`. After a successful scrape, `upload_assets` downloads image/favicon URLs and uploads all three assets concurrently via `tokio::join!`. Hard failure (503) on any OSS error. OSS is opt-in — if the five `OSS_*` env vars are absent the service starts normally and returns original URLs.

**Tech Stack:** `oss-rust-sdk = "0.3"` (async feature), `sha2 = "0.10"`, `hex = "0.4"`, `base64 = "0.22"`.

---

## File Map

| File | Action | Responsibility |
|---|---|---|
| `crates/scraper-service/Cargo.toml` | Modify | Add oss-rust-sdk, sha2, hex, base64 deps |
| `crates/scraper-service/src/scraper.rs` | Modify | Add `screenshot_url` field and `OssFailed` error variant |
| `crates/scraper-service/src/oss.rs` | Create | `OssClient`, key generation, upload primitives, orchestration |
| `crates/scraper-service/src/main.rs` | Modify | `AppState.oss`, updated `scrape_handler`, `ScrapeResponse.screenshot` |
| `CLAUDE.md` | Modify | Document new OSS env vars |

---

### Task 1: Add dependencies and extend `ScrapeResult` / `ScrapeError`

**Files:**
- Modify: `crates/scraper-service/Cargo.toml`
- Modify: `crates/scraper-service/src/scraper.rs`

- [ ] **Step 1: Add dependencies to Cargo.toml**

In `crates/scraper-service/Cargo.toml`, add to `[dependencies]`:

```toml
oss-rust-sdk = { version = "0.3", features = ["put_object"] }
sha2 = "0.10"
hex = "0.4"
base64 = "0.22"
```

- [ ] **Step 2: Add `screenshot_url` field to `ScrapeResult`**

In `crates/scraper-service/src/scraper.rs`, update the struct (around line 5):

```rust
#[derive(Debug, Serialize, PartialEq)]
pub struct ScrapeResult {
    pub title: Option<String>,
    pub description: Option<String>,
    pub image: Option<String>,
    pub favicon: Option<String>,
    pub source: String,
    pub screenshot_bytes: Option<Vec<u8>>,
    pub screenshot_url: Option<String>,
}
```

- [ ] **Step 3: Update all four `ScrapeResult` literals in `parse_metadata()`**

Each return site needs `screenshot_url: None`. The four blocks are: OG tags (~line 173), Twitter Card (~line 187), JSON-LD (~line 199), HTML fallback (~line 210).

OG block — replace the return:
```rust
return ScrapeResult {
    title: Some(title),
    description: meta_property(&document, "og:description")
        .or_else(|| meta_name(&document, "description")),
    image: meta_property(&document, "og:image"),
    favicon: extract_favicon(&document, base_url),
    source: "og".to_string(),
    screenshot_bytes: None,
    screenshot_url: None,
};
```

Twitter Card block:
```rust
return ScrapeResult {
    title: Some(title),
    description: meta_name(&document, "twitter:description")
        .or_else(|| meta_name(&document, "description")),
    image: meta_name(&document, "twitter:image"),
    favicon: extract_favicon(&document, base_url),
    source: "twitter_card".to_string(),
    screenshot_bytes: None,
    screenshot_url: None,
};
```

JSON-LD block:
```rust
return ScrapeResult {
    title,
    description: desc.or_else(|| meta_name(&document, "description")),
    image,
    favicon: extract_favicon(&document, base_url),
    source: "json_ld".to_string(),
    screenshot_bytes: None,
    screenshot_url: None,
};
```

HTML fallback block:
```rust
ScrapeResult {
    title: extract_title(&document),
    description: meta_name(&document, "description"),
    image: None,
    favicon: extract_favicon(&document, base_url),
    source: "html".to_string(),
    screenshot_bytes: None,
    screenshot_url: None,
}
```

- [ ] **Step 4: Add `OssFailed` variant to `ScrapeError`**

```rust
#[derive(Debug)]
pub enum ScrapeError {
    InvalidUrl,
    Timeout,
    FetchFailed(String),
    HeadlessFailed(String),
    OssFailed(String),
}
```

- [ ] **Step 5: Verify compilation**

```bash
cargo check -p scraper-service
```

Expected: exits 0 with no errors.

- [ ] **Step 6: Commit**

```bash
git add crates/scraper-service/Cargo.toml crates/scraper-service/src/scraper.rs
git commit -m "feat: add screenshot_url to ScrapeResult and OssFailed to ScrapeError"
```

---

### Task 2: Create `oss.rs` — `OssClient`, `from_env()`, key helpers

**Files:**
- Create: `crates/scraper-service/src/oss.rs`
- Modify: `crates/scraper-service/src/main.rs` (add `mod oss;`)

- [ ] **Step 1: Add `mod oss;` to `main.rs`**

At the top of `crates/scraper-service/src/main.rs`, add alongside existing `mod` declarations:

```rust
mod cache;
mod headless;
mod oss;
mod scraper;
```

- [ ] **Step 2: Write the test module first (TDD)**

Create `crates/scraper-service/src/oss.rs` with just the test module to establish what we need to build:

```rust
use crate::scraper::ScrapeError;

pub struct OssClient {
    key_id: String,
    key_secret: String,
    endpoint: String,
    bucket: String,
    pub base_url: String,
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn screenshot_key_is_sha256_of_page_url() {
        use sha2::{Digest, Sha256};
        let url = "https://example.com/page";
        let hash = hex::encode(Sha256::digest(url.as_bytes()));
        assert_eq!(OssClient::screenshot_key(url), format!("screenshots/{hash}.png"));
    }

    #[test]
    fn asset_key_defaults_to_png() {
        use sha2::{Digest, Sha256};
        let url = "https://example.com/logo";
        let hash = hex::encode(Sha256::digest(url.as_bytes()));
        assert_eq!(OssClient::asset_key(url, None), format!("images/{hash}.png"));
    }

    #[test]
    fn asset_key_uses_jpeg_content_type() {
        use sha2::{Digest, Sha256};
        let url = "https://example.com/photo";
        let hash = hex::encode(Sha256::digest(url.as_bytes()));
        assert_eq!(
            OssClient::asset_key(url, Some("image/jpeg")),
            format!("images/{hash}.jpg")
        );
    }

    #[test]
    fn asset_key_uses_webp_content_type() {
        use sha2::{Digest, Sha256};
        let url = "https://cdn.example.com/img";
        let hash = hex::encode(Sha256::digest(url.as_bytes()));
        assert_eq!(
            OssClient::asset_key(url, Some("image/webp")),
            format!("images/{hash}.webp")
        );
    }

    #[test]
    fn from_env_returns_none_when_vars_missing() {
        std::env::remove_var("OSS_ACCESS_KEY_ID");
        std::env::remove_var("OSS_ACCESS_KEY_SECRET");
        std::env::remove_var("OSS_BUCKET");
        std::env::remove_var("OSS_ENDPOINT");
        std::env::remove_var("OSS_BASE_URL");
        assert!(OssClient::from_env().is_none());
    }
}
```

- [ ] **Step 3: Run tests to confirm they fail (compile error expected)**

```bash
cargo test -p scraper-service oss:: 2>&1 | head -20
```

Expected: compile errors — `screenshot_key`, `asset_key`, `from_env` are not defined yet.

- [ ] **Step 4: Implement `OssClient`, `from_env()`, and key helpers**

Replace the stub in `oss.rs` with the full module:

```rust
use crate::scraper::{ScrapeError, ScrapeResult};
use sha2::{Digest, Sha256};

pub struct OssClient {
    key_id: String,
    key_secret: String,
    endpoint: String,
    bucket: String,
    pub base_url: String,
}

impl OssClient {
    pub fn from_env() -> Option<Self> {
        let key_id = std::env::var("OSS_ACCESS_KEY_ID").ok()?;
        let key_secret = std::env::var("OSS_ACCESS_KEY_SECRET").ok()?;
        let bucket = std::env::var("OSS_BUCKET").ok()?;
        let endpoint = std::env::var("OSS_ENDPOINT").ok()?;
        let base_url = std::env::var("OSS_BASE_URL").ok()?;
        Some(Self { key_id, key_secret, endpoint, bucket, base_url })
    }

    fn oss(&self) -> oss_rust_sdk::oss::OSS {
        oss_rust_sdk::oss::OSS::new(
            self.key_id.clone(),
            self.key_secret.clone(),
            self.endpoint.clone(),
            self.bucket.clone(),
        )
    }

    pub fn screenshot_key(page_url: &str) -> String {
        let hash = hex::encode(Sha256::digest(page_url.as_bytes()));
        format!("screenshots/{hash}.png")
    }

    pub fn asset_key(asset_url: &str, content_type: Option<&str>) -> String {
        let hash = hex::encode(Sha256::digest(asset_url.as_bytes()));
        let ext = ext_from_content_type(content_type.unwrap_or(""));
        format!("images/{hash}.{ext}")
    }
}

fn ext_from_content_type(ct: &str) -> &str {
    match ct.split(';').next().unwrap_or("").trim() {
        "image/jpeg" | "image/jpg" => "jpg",
        "image/gif" => "gif",
        "image/webp" => "webp",
        "image/svg+xml" => "svg",
        "image/x-icon" | "image/vnd.microsoft.icon" => "ico",
        _ => "png",
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn screenshot_key_is_sha256_of_page_url() {
        let url = "https://example.com/page";
        let hash = hex::encode(Sha256::digest(url.as_bytes()));
        assert_eq!(OssClient::screenshot_key(url), format!("screenshots/{hash}.png"));
    }

    #[test]
    fn asset_key_defaults_to_png() {
        let url = "https://example.com/logo";
        let hash = hex::encode(Sha256::digest(url.as_bytes()));
        assert_eq!(OssClient::asset_key(url, None), format!("images/{hash}.png"));
    }

    #[test]
    fn asset_key_uses_jpeg_content_type() {
        let url = "https://example.com/photo";
        let hash = hex::encode(Sha256::digest(url.as_bytes()));
        assert_eq!(
            OssClient::asset_key(url, Some("image/jpeg")),
            format!("images/{hash}.jpg")
        );
    }

    #[test]
    fn asset_key_uses_webp_content_type() {
        let url = "https://cdn.example.com/img";
        let hash = hex::encode(Sha256::digest(url.as_bytes()));
        assert_eq!(
            OssClient::asset_key(url, Some("image/webp")),
            format!("images/{hash}.webp")
        );
    }

    #[test]
    fn from_env_returns_none_when_vars_missing() {
        std::env::remove_var("OSS_ACCESS_KEY_ID");
        std::env::remove_var("OSS_ACCESS_KEY_SECRET");
        std::env::remove_var("OSS_BUCKET");
        std::env::remove_var("OSS_ENDPOINT");
        std::env::remove_var("OSS_BASE_URL");
        assert!(OssClient::from_env().is_none());
    }
}
```

- [ ] **Step 5: Run tests to confirm they pass**

```bash
cargo test -p scraper-service oss::
```

Expected: 5 tests pass.

- [ ] **Step 6: Commit**

```bash
git add crates/scraper-service/src/oss.rs crates/scraper-service/src/main.rs
git commit -m "feat: add OssClient with from_env and key generation helpers"
```

---

### Task 3: Implement `upload_bytes` and `upload_url_asset`

**Files:**
- Modify: `crates/scraper-service/src/oss.rs`

`upload_bytes` handles data already in memory (screenshot). `upload_url_asset` downloads a remote URL then delegates to `upload_bytes`.

- [ ] **Step 1: Implement `upload_bytes`**

Add the following method inside `impl OssClient` in `oss.rs`, after `asset_key`:

```rust
/// Uploads bytes to OSS at `key`. Skips upload if the object already exists (HEAD 200).
/// Returns the full public URL on success.
async fn upload_bytes(
    &self,
    key: &str,
    bytes: &[u8],
    content_type: &str,
) -> Result<String, ScrapeError> {
    use std::collections::HashMap;
    let oss = self.oss();

    // Deduplication: if HEAD succeeds the object already exists
    match oss.head_object(key, None).await {
        Ok(_) => return Ok(format!("{}/{}", self.base_url, key)),
        Err(e) => {
            let msg = e.to_string();
            // 404 / NoSuchKey means not found — proceed with upload
            if !msg.contains("404") && !msg.contains("NoSuchKey") {
                return Err(ScrapeError::OssFailed(format!("OSS HEAD failed: {msg}")));
            }
        }
    }

    let mut headers = HashMap::new();
    headers.insert("Content-Type".to_string(), content_type.to_string());

    oss.put_object_from_buffer(&bytes.to_vec(), key, headers, None)
        .await
        .map_err(|e| ScrapeError::OssFailed(e.to_string()))?;

    Ok(format!("{}/{}", self.base_url, key))
}
```

- [ ] **Step 2: Implement `upload_url_asset`**

Add the following method inside `impl OssClient`, after `upload_bytes`:

```rust
/// Downloads the image at `url` (with a Referer header to bypass hotlink protection),
/// then uploads to OSS. Returns `None` if `url` is `None`; `Some(oss_url)` on success.
pub async fn upload_url_asset(
    &self,
    url: Option<&str>,
    http: &reqwest::Client,
) -> Result<Option<String>, ScrapeError> {
    let url = match url {
        Some(u) => u,
        None => return Ok(None),
    };

    // Derive Referer from the URL's origin to bypass simple hotlink checks
    let referer = reqwest::Url::parse(url)
        .ok()
        .map(|u| format!("{}://{}", u.scheme(), u.host_str().unwrap_or("")))
        .unwrap_or_default();

    let response = http
        .get(url)
        .header("Referer", &referer)
        .send()
        .await
        .map_err(|e| ScrapeError::OssFailed(format!("image download failed: {e}")))?;

    let content_type = response
        .headers()
        .get(reqwest::header::CONTENT_TYPE)
        .and_then(|v| v.to_str().ok())
        .unwrap_or("image/png")
        .to_string();

    let bytes = response
        .bytes()
        .await
        .map_err(|e| ScrapeError::OssFailed(format!("image read failed: {e}")))?;

    let key = Self::asset_key(url, Some(&content_type));
    let oss_url = self.upload_bytes(&key, &bytes, &content_type).await?;
    Ok(Some(oss_url))
}
```

- [ ] **Step 3: Verify compilation**

```bash
cargo check -p scraper-service
```

Expected: exits 0.

- [ ] **Step 4: Commit**

```bash
git add crates/scraper-service/src/oss.rs
git commit -m "feat: implement upload_bytes and upload_url_asset with deduplication"
```

---

### Task 4: Implement `upload_assets` orchestration

**Files:**
- Modify: `crates/scraper-service/src/oss.rs`

- [ ] **Step 1: Implement `upload_assets`**

Add the following method inside `impl OssClient`, after `upload_url_asset`:

```rust
/// Uploads favicon, OG image, and screenshot bytes to OSS concurrently.
/// `page_url` is the original scraped URL — used as the key seed for the screenshot.
/// Returns a modified `ScrapeResult` with OSS URLs replacing original values.
pub async fn upload_assets(
    &self,
    mut result: ScrapeResult,
    page_url: &str,
    http: &reqwest::Client,
) -> Result<ScrapeResult, ScrapeError> {
    let screenshot_bytes = result.screenshot_bytes.take(); // removes bytes from result
    let image_url = result.image.clone();
    let favicon_url = result.favicon.clone();

    let screenshot_key = Self::screenshot_key(page_url);
    let screenshot_fut = async {
        match screenshot_bytes {
            Some(bytes) => {
                self.upload_bytes(&screenshot_key, &bytes, "image/png")
                    .await
                    .map(Some)
            }
            None => Ok(None),
        }
    };

    let (screenshot_result, image_result, favicon_result) = tokio::join!(
        screenshot_fut,
        self.upload_url_asset(image_url.as_deref(), http),
        self.upload_url_asset(favicon_url.as_deref(), http),
    );

    result.screenshot_url = screenshot_result?;
    result.image = image_result?;
    result.favicon = favicon_result?;

    Ok(result)
}
```

- [ ] **Step 2: Verify compilation**

```bash
cargo check -p scraper-service
```

Expected: exits 0.

- [ ] **Step 3: Commit**

```bash
git add crates/scraper-service/src/oss.rs
git commit -m "feat: implement upload_assets with concurrent tokio::join!"
```

---

### Task 5: Wire `OssClient` into `AppState` and `scrape_handler`

**Files:**
- Modify: `crates/scraper-service/src/main.rs`

- [ ] **Step 1: Add `oss` field to `AppState`**

Update the `AppState` struct in `main.rs`:

```rust
#[derive(Clone)]
struct AppState {
    client: reqwest::Client,
    api_key: String,
    headless_timeout_secs: u64,
    cache: Arc<ScrapeCache>,
    oss: Option<Arc<oss::OssClient>>,
}
```

- [ ] **Step 2: Initialize `OssClient` in `main()`**

In `main()`, after the `client` is built and before creating `state`, add:

```rust
let oss = oss::OssClient::from_env().map(Arc::new);
if oss.is_some() {
    tracing::info!("OSS upload enabled");
} else {
    tracing::info!("OSS upload disabled (OSS_* env vars not configured)");
}

let state = AppState { client, api_key, headless_timeout_secs, cache, oss };
```

- [ ] **Step 3: Add `screenshot` field to `ScrapeResponse`**

```rust
#[derive(Serialize)]
struct ScrapeResponse {
    title: Option<String>,
    description: Option<String>,
    image: Option<String>,
    favicon: Option<String>,
    source: String,
    #[serde(skip_serializing_if = "Option::is_none")]
    cached: Option<bool>,
    #[serde(skip_serializing_if = "Option::is_none")]
    screenshot: Option<String>,
}
```

- [ ] **Step 4: Add `base64_encode` helper**

Add this free function anywhere in `main.rs` (below the structs is fine):

```rust
fn base64_encode(bytes: &[u8]) -> String {
    use base64::{engine::general_purpose::STANDARD, Engine};
    STANDARD.encode(bytes)
}
```

- [ ] **Step 5: Update the `Ok(r)` arm in `scrape_handler`**

Replace the existing `Ok(r) => { ... }` arm in the `match result` block with:

```rust
Ok(r) => {
    // If OSS is configured, upload assets and replace URLs; hard-fail on error
    let r = if let Some(oss) = &state.oss {
        match oss.upload_assets(r, &body.url, &state.client).await {
            Ok(uploaded) => uploaded,
            Err(scraper::ScrapeError::OssFailed(msg)) => {
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
```

- [ ] **Step 6: Update the cache-hit return at the top of `scrape_handler`**

Replace the existing cache-hit early return with:

```rust
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
```

- [ ] **Step 7: Add `OssFailed` to the bottom error match arms**

After the existing `HeadlessFailed` arm, add:

```rust
Err(scraper::ScrapeError::OssFailed(msg)) => (
    StatusCode::SERVICE_UNAVAILABLE,
    Json(ErrorResponse { error: "oss upload failed".to_string(), detail: Some(msg) }),
)
    .into_response(),
```

- [ ] **Step 8: Run all unit tests**

```bash
cargo test -p scraper-service
```

Expected: all tests pass.

- [ ] **Step 9: Commit**

```bash
git add crates/scraper-service/src/main.rs
git commit -m "feat: wire OssClient into AppState, scrape_handler, and ScrapeResponse"
```

---

### Task 6: Update `CLAUDE.md`

**Files:**
- Modify: `CLAUDE.md`

- [ ] **Step 1: Add OSS env vars to the Environment Variables table**

In `CLAUDE.md`, add the following rows to the table (after the `PROXY_URL` row):

```markdown
| `OSS_ACCESS_KEY_ID` | (optional) | Alibaba Cloud Access Key ID. All five OSS_* vars must be set to enable OSS upload. |
| `OSS_ACCESS_KEY_SECRET` | (optional) | Alibaba Cloud Access Key Secret |
| `OSS_BUCKET` | (optional) | OSS bucket name |
| `OSS_ENDPOINT` | (optional) | OSS endpoint, e.g. `oss-cn-hangzhou.aliyuncs.com` |
| `OSS_BASE_URL` | (optional) | Public URL prefix for returned OSS links, e.g. `https://<bucket>.oss-cn-hangzhou.aliyuncs.com` |
```

- [ ] **Step 2: Commit**

```bash
git add CLAUDE.md
git commit -m "docs: add OSS environment variables to CLAUDE.md"
```
