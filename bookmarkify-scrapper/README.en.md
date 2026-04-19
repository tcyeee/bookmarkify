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

- **Bearer Token authentication**: All `/scrape` requests require `Authorization: Bearer <API_KEY>`
- **Per-API-key rate limiting**: Built on `tower-governor` with independent token buckets per key, supporting burst traffic
- **URL-normalized caching**: Built on `moka` — strips fragments, sorts query params, configurable TTL
- **Screenshot support**: Captures full-page PNG screenshots in headless mode

## Project Structure

```
bookmarkify-scrapper/
├── Cargo.toml                       # Workspace configuration
└── crates/
    ├── scraper-service/             # Production service
    │   └── src/
    │       ├── main.rs              # HTTP server, routing, auth, rate limiting
    │       ├── scraper.rs           # Layer 1: HTML metadata parsing
    │       ├── headless.rs          # Layer 2: headless Chrome scraping
    │       └── cache.rs             # URL-normalized in-memory cache
    └── scraper-demo/                # M0 validation demo (not for production)
        └── src/
            └── main.rs              # Anti-crawler verification script
```

## Getting Started

### Prerequisites

- Rust 1.75+ (install via `rustup`)
- Chrome browser (required for headless mode)

### Build

```bash
cargo build -p scraper-service --release
```

### Start the service

```bash
API_KEY=your-secret-key ./target/release/scraper-service
```

The service listens on `0.0.0.0:3000` by default.

## Environment Variables

| Variable | Required | Default | Description |
|---|---|---|---|
| `API_KEY` | ✅ | — | Bearer token auth secret; service panics on startup if missing |
| `PORT` | | `3000` | HTTP listen port |
| `REQUEST_TIMEOUT_SECS` | | `10` | Layer 1 HTTP request timeout (seconds) |
| `HEADLESS_TIMEOUT_SECS` | | `30` | Layer 2 headless browser timeout (seconds) |
| `CACHE_TTL_SECS` | | `3600` | Cache entry time-to-live (seconds) |
| `RATE_LIMIT_RPS` | | `10` | Max requests per second per API key (burst is 2×) |

## API Reference

### `GET /health`

Health check endpoint. No authentication required. Intended for load balancers and container orchestration liveness probes.

**Response**

```json
200 OK
{"status": "ok"}
```

---

### `POST /scrape`

Scrape page metadata for a given URL.

**Request headers**

```
Authorization: Bearer <API_KEY>
Content-Type: application/json
```

**Request body**

```json
{
  "url": "https://example.com",
  "headless": false
}
```

| Field | Type | Required | Description |
|---|---|---|---|
| `url` | string | ✅ | Target page URL |
| `headless` | boolean | | `true` forces Layer 2; `false` (default) tries Layer 1 first and falls back to Layer 2 if no title is found |

**Success response**

```json
200 OK
{
  "title": "Example Page Title",
  "description": "A description of the page",
  "image": "https://example.com/og-image.jpg",
  "favicon": "https://example.com/favicon.ico",
  "source": "og",
  "cached": true
}
```

| Field | Description |
|---|---|
| `title` | Page title; may be `null` |
| `description` | Page description; may be `null` |
| `image` | Primary image URL; may be `null` |
| `favicon` | Site favicon URL; may be `null` |
| `source` | Data source used: `"og"` / `"twitter_card"` / `"json_ld"` / `"html"` / `"headless"` |
| `cached` | `true` when served from cache; omitted on fresh scrapes |

**Error responses**

| Status | Description | Response body example |
|---|---|---|
| `401` | Authentication failed | `{"error": "unauthorized"}` |
| `422` | Invalid URL format | `{"error": "invalid url"}` |
| `429` | Rate limit exceeded | `{"error": "rate limit exceeded"}` |
| `502` | Network error or headless browser failure | `{"error": "fetch failed", "detail": "..."}` |
| `504` | Request timed out | `{"error": "timeout"}` |

**Example requests**

```bash
curl -X POST http://localhost:3000/scrape \
  -H "Authorization: Bearer your-secret-key" \
  -H "Content-Type: application/json" \
  -d '{"url": "https://github.com"}'
```

```bash
# Force headless mode for JavaScript-rendered pages
curl -X POST http://localhost:3000/scrape \
  -H "Authorization: Bearer your-secret-key" \
  -H "Content-Type: application/json" \
  -d '{"url": "https://huaban.com", "headless": true}'
```

## Running Tests

```bash
# Run all unit tests (excludes integration tests that require a real browser)
cargo test -p scraper-service

# Run headless integration tests (requires Chrome; slow)
cargo test -p scraper-service -- --ignored
```

## Tech Stack

| Component | Library | Notes |
|---|---|---|
| HTTP framework | `axum 0.7` | Async routing and middleware |
| Async runtime | `tokio 1` | Multi-threaded async executor |
| HTTP client | `reqwest 0.12` | Layer 1 page fetching |
| HTML parsing | `scraper 0.19` | CSS selector DOM parsing |
| Headless browser | `spider 2` | Chrome driver with stealth mode |
| Rate limiting | `tower-governor 0.4` | Token bucket, per-API-key buckets |
| Caching | `moka 0.12` | Async in-memory cache with TTL |
| Serialization | `serde / serde_json` | JSON request/response |
| Logging | `tracing / tracing-subscriber` | Structured logging, `RUST_LOG` support |
