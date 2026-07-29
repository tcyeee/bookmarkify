# bookmarkify-scrapper

A web metadata scraping service for bookmarks, supporting both static pages and JavaScript-rendered pages via an HTTP API.

## Features

- **Two-layer scraping strategy**
  - **Layer 1 (lightweight)**: Fetches pages via `reqwest` and parses HTML metadata — low latency, minimal resource usage
  - **Layer 2 (headless browser)**: Drives Chrome via `spider-rs` for pages that require JavaScript rendering
  - Automatically falls back from Layer 1 to Layer 2 when no title is found — transparent to callers

- **Metadata extraction priority** (highest to lowest)
  1. Open Graph (`og:title`, `og:description`, `og:image`)
  2. Twitter Card (`twitter:title`, `twitter:description`, `twitter:image`)
  3. JSON-LD structured data (`name`, `description`, `image`)
  4. HTML fallback (`<title>` tag, `<meta name="description">`)

- **URL-normalized caching**: Built on `moka` — strips fragments, sorts query params, configurable TTL, plus a 60s negative cache for recently-failed URLs
- **Screenshot support**: Captures a full-page PNG screenshot in headless mode; uploaded to OSS when configured, otherwise returned as base64
- **OSS upload**: Optional Alibaba Cloud OSS integration — screenshots and cover images are uploaded and replaced with persistent URLs, all under the `bookmarkify/scrapper/{og,logo,screenshots}/` prefix
- **Proxy support**: `PROXY_URL` configures an HTTP proxy, applied to Layer 1, Layer 2, and OSS uploads alike
- **SSRF protection**: Blocks targets resolving to private/loopback/link-local addresses by default, checking both IP literals and DNS results; the configured proxy host is exempted. Disable with `SSRF_ALLOW_PRIVATE=1` (trusted internal testing only)
- **Auth + rate limiting**: Setting `SCRAPER_AUTH_TOKEN` requires `Authorization: Bearer <token>` on `/scrape` and `/ping`; `MAX_CONCURRENT_REQUESTS` caps in-flight requests, failing fast with `503` instead of queueing
- **Observability**: `GET /metrics` exposes Prometheus-format request/latency/status-code metrics

## Project Structure

```
bookmarkify-scrapper/
├── Cargo.toml                       # Workspace configuration
└── crates/
    └── scraper-service/             # Production service
    │   └── src/
    │       ├── main.rs              # HTTP server, routing, AppState
    │       ├── scraper.rs           # Layer 1: HTML metadata parsing
    │       ├── headless.rs          # Layer 2: headless Chrome scraping
    │       ├── cache.rs             # URL-normalized cache (positive + negative)
    │       └── oss.rs               # Aliyun OSS upload client
```

## Getting Started

### Prerequisites

- Rust 1.88+ (install via `rustup`; the Docker image is based on `cargo-chef:latest-rust-1.88`)
- Chrome browser (required for headless mode)

### Build

```bash
cargo build -p scraper-service --release
```

### Start the service

```bash
# Minimal
./target/release/scraper-service

# With a proxy
PROXY_URL=http://127.0.0.1:7890 ./target/release/scraper-service

# With OSS upload enabled
OSS_ACCESS_KEY_ID=xxx OSS_ACCESS_KEY_SECRET=xxx \
OSS_BUCKET=my-bucket OSS_ENDPOINT=oss-cn-hangzhou.aliyuncs.com \
OSS_KEY_PREFIX=scrapper \
./target/release/scraper-service
```

The service listens on `0.0.0.0:3000` by default.

## Environment Variables

| Variable | Required | Default | Description |
|---|---|---|---|
| `PORT` | | `3000` | HTTP listen port |
| `REQUEST_TIMEOUT_SECS` | | `10` | Layer 1 HTTP request timeout (seconds) |
| `HEADLESS_TIMEOUT_SECS` | | `30` | Layer 2 headless browser total timeout (seconds) |
| `HEADLESS_IDLE_WAIT_SECS` | | `10` | Layer 2 network-idle wait for JS rendering (seconds); must be less than `HEADLESS_TIMEOUT_SECS` |
| `CACHE_TTL_SECS` | | `3600` | Cache entry time-to-live (seconds) |
| `PROXY_URL` | | — | HTTP proxy URL, e.g. `http://127.0.0.1:7890`; direct connection if unset |
| `SSRF_ALLOW_PRIVATE` | | — | Set to `1` to disable SSRF protection; blocked by default |
| `SCRAPER_AUTH_TOKEN` | | — | When set, `/scrape` and `/ping` require `Authorization: Bearer <token>`; unset means no auth |
| `MAX_CONCURRENT_REQUESTS` | | `32` | Max concurrent `/scrape` + `/ping` requests; beyond this, `503` |
| `OSS_ACCESS_KEY_ID` | | — | Alibaba Cloud Access Key ID; all five `OSS_*` vars must be set together to enable upload |
| `OSS_ACCESS_KEY_SECRET` | | — | Alibaba Cloud Access Key Secret |
| `OSS_BUCKET` | | — | OSS bucket name |
| `OSS_ENDPOINT` | | — | OSS region endpoint, e.g. `oss-cn-hangzhou.aliyuncs.com` |
| `OSS_KEY_PREFIX` | | `scrapper` | Common prefix for all object keys. The bucket's `PutObject` grant and lifecycle rule are written against it — change both together |
| `RUST_LOG` | | `info` | Tracing filter, e.g. `debug` / `info` / `warn` |

## API Reference

The service exposes four endpoints: `GET /health`, `GET /metrics`, `POST /scrape`, `POST /ping`.
`/scrape` and `/ping` require auth only when `SCRAPER_AUTH_TOKEN` is set; all four are
unauthenticated otherwise.

Full request/response fields, error codes, caching semantics, and curl examples: see
**[api.md](./api.md)** (Chinese). Summary:

### `GET /health`

Health check. Always `200 OK`, `{"status": "ok"}`. Never requires auth or counts against the concurrency limit.

### `GET /metrics`

Prometheus text-format metrics (request counts, status codes, latency histograms). Never requires auth.

### `POST /scrape`

```json
{ "url": "https://example.com", "headless": false }
```

| Field | Type | Notes |
|---|---|---|
| `title` | string\|null | Page title |
| `description` | string\|null | Page description |
| `image` | string\|null | OG image; OSS URL if OSS is configured, otherwise the original URL |
| `favicon` | string\|null | Always a base64 `data:` URL |
| `logo` | string\|null | JSON-LD `logo` → `apple-touch-icon` → largest sized icon; OSS URL if configured |
| `source` | string | `"og"` / `"twitter_card"` / `"json_ld"` / `"html"` / `"headless"` |
| `cached` | boolean | Present and `true` only on cache hits |
| `screenshot` | string | OSS URL or base64 PNG; only present for headless scrapes |

Errors are `{"error": "<type>", "detail": "<optional>"}`:

| Status | `error` | When |
|---|---|---|
| `401` | `unauthorized` | `SCRAPER_AUTH_TOKEN` is set and the request is missing/wrong the bearer token |
| `403` | `forbidden target` | Target hit SSRF protection |
| `422` | `invalid url` | Malformed URL |
| `502` | `fetch failed` / `headless failed` | Layer 1/Layer 2 network failure |
| `502` | `scrape failed recently, retry after 60s` | Negative-cache hit |
| `503` | `service overloaded` | Over `MAX_CONCURRENT_REQUESTS` |
| `504` | `timeout` | Exceeded `REQUEST_TIMEOUT_SECS` / `HEADLESS_TIMEOUT_SECS` |

```bash
curl -X POST http://localhost:3000/scrape \
  -H "Content-Type: application/json" \
  -d '{"url": "https://github.com"}'
```

```bash
# Force headless mode for JavaScript-rendered pages
curl -X POST http://localhost:3000/scrape \
  -H "Content-Type: application/json" \
  -d '{"url": "https://huaban.com", "headless": true}'
```

```bash
# With SCRAPER_AUTH_TOKEN configured
curl -X POST http://localhost:3000/scrape \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer $SCRAPER_AUTH_TOKEN" \
  -d '{"url": "https://github.com"}'
```

### `POST /ping`

```json
{ "url": "https://example.com" }
```
→ `{"alive": bool}` — any HTTP response (including 4xx) counts as alive; connection failure, timeout, or an SSRF-blocked target counts as not alive. Not cached.

## Running Tests

```bash
# Run all unit + in-process integration tests (no browser required)
cargo test -p scraper-service

# Run headless integration tests (requires Chrome; slow)
cargo test -p scraper-service -- --ignored
```

## Tech Stack

| Component | Library | Notes |
|---|---|---|
| HTTP framework | `axum 0.7` | Async routing and middleware |
| Async runtime | `tokio 1` | Multi-threaded async executor |
| HTTP client | `reqwest 0.12` | Layer 1 page fetching, proxy support |
| HTML parsing | `scraper 0.19` | CSS selector DOM parsing |
| Headless browser | `spider 2` | Chrome driver with stealth mode |
| Caching | `moka 0.12` | Async in-memory cache with TTL |
| OSS upload | `hmac` / `sha1` / `httpdate` | Hand-rolled Aliyun OSS V1 request signing over the same `reqwest` client — no third-party OSS SDK |
| Overload protection | `tower` (`limit` / `load-shed`) | Concurrency cap + fast-fail for `/scrape`, `/ping` |
| Metrics | `axum-prometheus` | `GET /metrics` |
| Serialization | `serde / serde_json` | JSON request/response |
| Logging | `tracing / tracing-subscriber` | Structured logging, `RUST_LOG` support |
