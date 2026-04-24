# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Commands

```bash
# Build
cargo build -p scraper-service --release

# Run (API_KEY required)
API_KEY=your-secret-key cargo run -p scraper-service

# Test (unit tests only, no browser required)
cargo test -p scraper-service

# Integration tests (requires Chrome installed)
cargo test -p scraper-service -- --ignored

# Run a single test
cargo test -p scraper-service <test_name>

# Lint / format
cargo clippy -p scraper-service
cargo fmt
```

## Environment Variables

| Variable | Default | Description |
|---|---|---|
| `API_KEY` | (required) | Bearer token for auth — listed in docs but **not yet enforced in code** |
| `PORT` | 3000 | HTTP listen port |
| `REQUEST_TIMEOUT_SECS` | 10 | Layer 1 HTTP timeout |
| `HEADLESS_TIMEOUT_SECS` | 30 | Layer 2 Chrome timeout |
| `CACHE_TTL_SECS` | 3600 | Cache entry lifetime |
| `RATE_LIMIT_RPS` | 10 | Per-API-key requests/sec — **not yet enforced in code** |
| `PROXY_URL` | (optional) | HTTP proxy URL, e.g. `http://127.0.0.1:7890`. Does **not** apply to OSS uploads (oss-rust-sdk creates its own client). |
| `OSS_ACCESS_KEY_ID` | (optional) | Alibaba Cloud Access Key ID. All five OSS_* vars must be set to enable OSS upload. |
| `OSS_ACCESS_KEY_SECRET` | (optional) | Alibaba Cloud Access Key Secret |
| `OSS_BUCKET` | (optional) | OSS bucket name |
| `OSS_ENDPOINT` | (optional) | OSS endpoint, e.g. `oss-cn-hangzhou.aliyuncs.com` |
| `OSS_BASE_URL` | (optional) | Public URL prefix for returned OSS links, e.g. `https://<bucket>.oss-cn-hangzhou.aliyuncs.com` |
| `RUST_LOG` | info | Tracing filter |

## Architecture

Cargo workspace with two crates:
- `crates/scraper-service/` — production HTTP service
- `crates/scraper-demo/` — M0 anti-crawler validation demo (not used in production)

### Request Flow

```
POST /scrape
  └─ Cache check → hit: return immediately
  └─ headless=true? → skip to Layer 2
  └─ Layer 1: reqwest HTTP fetch → parse HTML metadata
       └─ title found? → cache + return
       └─ no title (JS-rendered) → Layer 2
  └─ Layer 2: headless Chrome (spider-rs) → render → extract + screenshot
       └─ OSS configured? → upload image/logo/screenshot concurrently → cache + return
       └─ no OSS → convert favicon to base64 → cache + return
```

### Key Modules (`crates/scraper-service/src/`)

**`main.rs`** — Server setup, route handlers, `AppState`.
- Routes: `GET /health`, `POST /scrape`.
- `ScrapeResponse` fields: `title`, `description`, `image`, `favicon`, `logo`, `source`, `cached` (optional), `screenshot` (optional).

**`scraper.rs`** — Layer 1 HTTP scraping and HTML parsing.
- `parse_metadata()` extracts in priority order: Open Graph → Twitter Card → JSON-LD → raw HTML.
- `ScrapeResult` and `ScrapeError` are the canonical types used throughout.
- `ScrapeError` variants: `InvalidUrl`, `Timeout`, `FetchFailed`, `HeadlessFailed`, `OssFailed`.

**`headless.rs`** — Layer 2 headless Chrome via spider-rs.
- Global `HEADLESS_LOCK: Mutex<()>` enforces serial Chrome execution (only one browser instance at a time).
- Clears `chromiumoxide-runner/SingletonLock` before each run to recover from prior Chrome crashes.
- Features: stealth mode, request interception, idle-network wait, PNG screenshot capture into `screenshot_bytes`.
- Integration tests are `#[ignore]` — run explicitly when Chrome is available.

**`cache.rs`** — In-memory LRU cache via moka.
- URL normalization before caching: lowercase host, sort query params, strip fragment.
- 10 000-entry capacity with configurable TTL.

**`oss.rs`** — Optional Alibaba Cloud OSS upload via oss-rust-sdk.
- `OssClient::from_env()` returns `None` when any OSS_* var is missing; OSS is silently disabled.
- `upload_assets()` concurrently uploads OG image, logo, and screenshot; replaces URLs in `ScrapeResult`.
- Favicon is **never** uploaded to OSS — always fetched and returned as a base64 `data:` URL.
- OSS object keys are SHA-256 of the source URL, so the same source always maps to the same key (no deduplication check, unconditional PUT).
- `PROXY_URL` / `REQUEST_TIMEOUT_SECS` do not apply to OSS operations.

## API

```
POST /scrape
Authorization: Bearer <API_KEY>
Content-Type: application/json

{ "url": "https://example.com", "headless": false }
```

Response fields:
| Field | Type | Notes |
|---|---|---|
| `title` | string\|null | Page title |
| `description` | string\|null | Page description |
| `image` | string\|null | OG image; OSS URL if OSS configured, otherwise original URL |
| `favicon` | string\|null | Always a base64 `data:` URL |
| `logo` | string\|null | JSON-LD logo → apple-touch-icon → largest sized icon; OSS URL if OSS configured |
| `source` | string | `"og"` / `"twitter_card"` / `"json_ld"` / `"html"` / `"headless"` |
| `cached` | boolean | Present and `true` only on cache hits |
| `screenshot` | string | OSS URL (if OSS configured) or base64 PNG; only present for headless scrapes |
