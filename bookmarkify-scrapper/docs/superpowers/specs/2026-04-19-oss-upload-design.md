# OSS Asset Upload Design

## Overview

Upload scraped assets (favicon, OG image, screenshot) to Alibaba Cloud OSS and return stable OSS URLs in the `/scrape` response. Solves hotlink protection, CDN expiry, and broken image URLs from source sites.

## Architecture

### New Module: `crates/scraper-service/src/oss.rs`

```rust
pub struct OssClient {
    inner: aliyun_oss_client::Client,
    bucket: String,
    base_url: String,  // e.g. https://<bucket>.<endpoint>
}

impl OssClient {
    pub fn from_env() -> Option<Self>
    pub async fn upload_assets(
        &self,
        result: ScrapeResult,
        http: &reqwest::Client,
    ) -> Result<ScrapeResult, ScrapeError>
}
```

`AppState` gains:
```rust
oss: Option<Arc<OssClient>>
```

When OSS is not configured (`from_env()` returns `None`), `upload_assets` is never called and the original `ScrapeResult` is used as-is.

### Error Variant

`ScrapeError` gains:
```rust
OssFailed(String)
```

Maps to `503 Service Unavailable` with `error: "oss upload failed"`.

## Upload Flow

### favicon / OG image

1. Compute key: `images/{sha256(original_url)}.{ext}` where ext is inferred from Content-Type or URL suffix (default: `.png`)
2. HEAD request to OSS — if key exists, return existing OSS URL (deduplication, skip re-upload)
3. Download image via `reqwest::Client` with `Referer: <source domain>` to bypass hotlink protection
4. PUT upload to OSS
5. Return `{base_url}/images/{hash}.{ext}`

### screenshot

1. Key: `screenshots/{sha256(page_url)}.png` (hash of the scraped page URL, not screenshot content)
2. HEAD check for deduplication
3. Upload `screenshot_bytes` directly (no download step)
4. Return `{base_url}/screenshots/{hash}.png`

### Concurrency

favicon, OG image, and screenshot uploads run concurrently via `tokio::join!`.

### Failure

Any upload failure returns `ScrapeError::OssFailed(msg)` immediately. The entire `/scrape` request fails with `503`.

## Environment Variables

All five variables must be present to enable OSS. If any is missing, OSS is disabled and the service starts normally.

| Variable | Description |
|---|---|
| `OSS_ACCESS_KEY_ID` | Alibaba Cloud Access Key ID |
| `OSS_ACCESS_KEY_SECRET` | Alibaba Cloud Access Key Secret |
| `OSS_BUCKET` | Target bucket name |
| `OSS_ENDPOINT` | Endpoint, e.g. `oss-cn-hangzhou.aliyuncs.com` |
| `OSS_BASE_URL` | Public URL prefix returned to clients, e.g. `https://<bucket>.oss-cn-hangzhou.aliyuncs.com` |

## Response Changes

`ScrapeResponse` gains one new field:

```rust
#[serde(skip_serializing_if = "Option::is_none")]
screenshot: Option<String>,  // OSS URL (headless mode) or None
```

**With OSS enabled:**
- `image` → OSS URL (or `None` if no OG image found)
- `favicon` → OSS URL
- `screenshot` → OSS URL (headless only)

**With OSS disabled:**
- `image` / `favicon` → original source URL
- `screenshot` → base64-encoded PNG string (existing behavior)

## ScrapeResult Changes

`ScrapeResult` gains one new field:

```rust
pub screenshot_url: Option<String>,  // populated by upload_assets; None for Layer 1 results
```

`upload_assets` modifies `ScrapeResult` in-place (or returns a new one):
- `image` → replaced with OSS URL
- `favicon` → replaced with OSS URL
- `screenshot_url` → set to OSS URL
- `screenshot_bytes` → cleared (`None`) after successful upload

## Cache Interaction

OSS upload happens after a successful scrape, before writing to the in-memory cache. The cached `ScrapeResult` carries OSS URLs in `image`, `favicon`, and `screenshot_url`. Cache hits return OSS URLs directly without re-uploading.
