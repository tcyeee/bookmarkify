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
| `API_KEY` | (required) | Bearer token for auth |
| `PORT` | 3000 | HTTP listen port |
| `REQUEST_TIMEOUT_SECS` | 10 | Layer 1 HTTP timeout |
| `HEADLESS_TIMEOUT_SECS` | 30 | Layer 2 Chrome timeout |
| `CACHE_TTL_SECS` | 3600 | Cache entry lifetime |
| `RATE_LIMIT_RPS` | 10 | Per-API-key requests/sec (burst = 2×) |
| `PROXY_URL` | (optional) | HTTP proxy URL, e.g. `http://127.0.0.1:7890`. Direct connection if not set. |
| `RUST_LOG` | info | Tracing filter |

## Architecture

Cargo workspace with two crates:
- `crates/scraper-service/` — production HTTP service
- `crates/scraper-demo/` — M0 anti-crawler validation demo (not used in production)

### Request Flow

```
POST /scrape
  └─ Bearer auth middleware
  └─ Rate limit middleware (per API key, tower-governor)
  └─ Cache check → hit: return immediately
  └─ headless=true? → skip to Layer 2
  └─ Layer 1: reqwest HTTP fetch → parse HTML metadata
       └─ title found? → cache + return
       └─ no title (JS-rendered) → Layer 2
  └─ Layer 2: headless Chrome (spider-rs) → render → extract + screenshot
       └─ cache + return
```

### Key Modules (`crates/scraper-service/src/`)

**`main.rs`** — Server setup, route handlers, `AppState`.
- Middleware ordering is load-bearing: auth layer wraps rate-limit layer (auth first, so rate-limit key extraction works on authenticated requests).
- Routes: `GET /health`, `POST /scrape`.

**`scraper.rs`** — Layer 1 HTTP scraping and HTML parsing.
- `parse_metadata()` extracts in priority order: Open Graph → Twitter Card → JSON-LD → raw HTML.
- `ScrapeResult` and `ScrapeError` are the canonical types used throughout.

**`headless.rs`** — Layer 2 headless Chrome via spider-rs.
- Global `HEADLESS_LOCK: Mutex<()>` enforces serial Chrome execution (only one browser instance at a time).
- Features: stealth mode, request interception, 5 s JS settle wait, PNG screenshot capture.
- Integration tests are `#[ignore]` — run explicitly when Chrome is available.

**`cache.rs`** — In-memory LRU cache via moka.
- URL normalization before caching: lowercase host, sort query params, strip fragment.
- 10 000-entry capacity with configurable TTL.

## API

```
POST /scrape
Authorization: Bearer <API_KEY>
Content-Type: application/json

{ "url": "https://example.com", "headless": false }
```

Response includes: `url`, `title`, `description`, `image`, `favicon`, `site_name`, `cached`, optional `screenshot` (base64 PNG).
