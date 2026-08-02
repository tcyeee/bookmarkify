# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Commands

```bash
# Build
cargo build -p scraper-service --release

# Run
cargo run -p scraper-service

# Test (unit tests only, no browser required)
cargo test -p scraper-service

# Integration tests (requires Chrome installed)
cargo test -p scraper-service -- --ignored

# Run a single test
cargo test -p scraper-service <test_name>

# Lint / format
cargo clippy -p scraper-service
cargo fmt

# Docker
docker build -t bookmarkify-scraper .
docker-compose up -d
```

## Environment Variables

| Variable | Default | Description |
|---|---|---|
| `PORT` | 3000 | HTTP listen port |
| `REQUEST_TIMEOUT_SECS` | 10 | Layer 1 HTTP timeout |
| `HEADLESS_TIMEOUT_SECS` | 30 | Layer 2 Chrome total timeout |
| `HEADLESS_IDLE_WAIT_SECS` | 10 | Layer 2 network-idle wait for JS rendering |
| `CACHE_TTL_SECS` | 3600 | Cache entry lifetime |
| `PROXY_URL` | (optional) | HTTP proxy URL, e.g. `http://127.0.0.1:7890` (or `http://clash:7890` in prod). Applies to Layer 1 (reqwest), Layer 2 (headless Chrome `--proxy-server`), **and** the OSS upload client (`oss.rs` builds its own `reqwest::Client` that also reads this var). |
| `OSS_ACCESS_KEY_ID` | (optional) | Alibaba Cloud Access Key ID. The first four OSS_* vars must all be set to enable OSS upload. |
| `OSS_ACCESS_KEY_SECRET` | (optional) | Alibaba Cloud Access Key Secret |
| `OSS_BUCKET` | (optional) | OSS bucket name |
| `OSS_ENDPOINT` | (optional) | OSS endpoint, e.g. `oss-cn-hangzhou.aliyuncs.com` |
| `OSS_KEY_PREFIX` | (optional) | Common prefix for all object keys, default `scrapper`. The bucket's `PutObject` grant and lifecycle rule are scoped to it |
| `SSRF_ALLOW_PRIVATE` | (optional) | Set to `1` to disable SSRF protection (allow targets resolving to private/loopback/link-local addresses). Unset → protection is **on** by default. |
| `SCRAPER_AUTH_TOKEN` | (optional) | Shared secret for `/scrape` and `/ping`: when set, requests must send `Authorization: Bearer <token>` (constant-time compared) or get `401`. Unset (default) → those routes are unauthenticated. `/health` and `/metrics` never require it. `bookmarkify-api` sends this via `bookmarkify.scrapper.auth-token` / `BOOKMARKIFY_SCRAPPER_AUTH_TOKEN`, which must match. |
| `MAX_CONCURRENT_REQUESTS` | 32 | Caps in-flight `/scrape` + `/ping` requests; beyond this, `load_shed` fails fast with `503` instead of queuing. |
| `RUST_LOG` | info | Tracing filter |
| `CHROME_BIN` | (auto) | Path to Chromium binary for headless mode; Docker sets this to `/usr/bin/chromium`. Required for local headless runs if `chromium` is not on PATH. |

## Architecture

Cargo workspace with a single crate:
- `crates/scraper-service/` — production HTTP service

### Request Flow

```
POST /scrape
  └─ Negative-cache check → recently failed? return 502 immediately ("retry after 60s")
  └─ Cache check → hit: return immediately
  └─ headless=true? → skip to Layer 2
  └─ Layer 1: reqwest HTTP fetch → parse HTML metadata
       └─ title found? → cache + return
       └─ no title (JS-rendered) → Layer 2
       └─ 403/406/412 (anti-bot) → origin warm-up retry → still blocked? → Layer 2
             └─ host already in the headless breaker (900s)? → skip Layer 2 entirely, don't spend a Chrome
             └─ Layer 2 navigation also non-2xx? → trip the breaker for this host,
                report the Layer 1 error, NOT the block page
  └─ Layer 2: headless Chrome (spider-rs) → render → extract + screenshot
       └─ OSS configured? → upload image/logo/screenshot concurrently (non-fatal on failure) → cache + return
       └─ no OSS → convert favicon to base64 → cache + return
  └─ Timeout / FetchFailed / HeadlessFailed → write to negative cache (60s TTL) before returning the error
```

Errors are JSON `{"error": "<type>", "detail": "<optional>"}`. Status mapping: `422 invalid url`, `403 forbidden target`, `504 timeout`, `502 fetch failed` / `headless failed` / negative-cache rejection. Asset failures (image/logo/favicon/screenshot download or OSS upload) never fail the request — the field is set to `null`/original URL and a warning is logged instead.

### Key Modules (`crates/scraper-service/src/`)

**`main.rs`** — Server setup, route handlers, `AppState`.
- Routes: `GET /health`, `GET /metrics` (Prometheus text format, via `axum-prometheus`), `POST /scrape`, `POST /ping`. `/health` and `/metrics` are unauthenticated and unlimited (ops endpoints); `/scrape` and `/ping` sit behind `auth_middleware` (no-op unless `SCRAPER_AUTH_TOKEN` is set) and a `concurrency_limit` + `load_shed` layer (`MAX_CONCURRENT_REQUESTS`, fails fast with `503 service overloaded` instead of queueing).
- `build_router(state, max_concurrent) -> Router` assembles everything except `/metrics` (which needs a process-global metrics recorder installed exactly once — done in `main()`, not in `build_router`, so integration tests can call `build_router` repeatedly within one test binary without hitting `axum-prometheus`'s "recorder already set" panic).
- `POST /ping` (`{"url": "..."}` → `{"alive": bool}`): does its own `validate_target_host()` pre-flight — added after finding that the shared client's `SsrfSafeResolver` is *not* consulted for hosts that are already IP literals (many HTTP stacks, including this one via hyper, connect straight to an IP literal without a DNS step), so a bare `"http://169.254.169.254/"` used to reach the target directly. A blocked target now degrades to `alive: false` rather than a distinct error. Otherwise sends a `HEAD` request through the shared client; any response status `< 500` counts as alive, connection failure/timeout counts as dead. Used by `bookmarkify-api`'s bookmark-liveness/ping-log feature — independent of the scrape cache.
- `ScrapeResponse` fields: `title`, `description`, `image`, `favicon`, `logo`, `source`, `cached` (optional), `screenshot` (optional).
- `favicon_to_base64()` does its own `validate_target_host()` pre-flight before downloading (belt-and-suspenders on top of the client's SSRF resolver) and strips ASCII control chars from the sniffed `Content-Type` before embedding it in the `data:` URI, since that value flows unescaped into the response.
- Graceful shutdown: `shutdown_signal()` waits on SIGTERM/Ctrl+C and is wired via `axum::serve(..).with_graceful_shutdown(..)`, so an in-flight headless scrape gets to finish instead of being cut off mid-request. The container's `stop_grace_period` (see `deploy/compose.prod.yml`) must stay comfortably above `HEADLESS_TIMEOUT_SECS` or Docker SIGKILLs before that drain completes.
- Integration tests at the bottom of `main.rs` exercise the full router via `tower::ServiceExt::oneshot` (cache hit/miss, negative cache, auth allow/deny, SSRF blocks) without binding a real port or hitting the network — including a regression test for the `/ping` IP-literal SSRF gap above.

**`scraper.rs`** — Layer 1 HTTP scraping and HTML parsing.
- `parse_metadata()` extracts in priority order: Open Graph → Twitter Card → JSON-LD → raw HTML.
- `ScrapeResult` and `ScrapeError` are the canonical types used throughout.
- `ScrapeError` variants: `InvalidUrl`, `ForbiddenTarget`, `Timeout`, `FetchFailed`, `HeadlessFailed`, `OssFailed` — `OssFailed` is unreachable in practice since `oss.rs::upload_assets()` degrades to `None`/original-URL on failure instead of propagating; the match arm exists only for exhaustiveness.
- **SSRF protection:** `validate_target_host()` + a custom `SsrfSafeResolver` reject hosts resolving to private/loopback/link-local IPs (both IP literals and DNS results). The configured proxy host is exempt (trusted, lives on the docker private network). Bypass with `SSRF_ALLOW_PRIVATE=1`. A blocked target maps to `ForbiddenTarget` → HTTP `403`.

**`headless.rs`** — Layer 2 headless Chrome via spider-rs.
- Global `HEADLESS_LOCK: Mutex<()>` enforces serial Chrome execution (only one browser instance at a time).
- Lock-wait and the Chrome run itself share a single deadline (`timeout_secs` total) via `tokio::time::timeout_at` — the two phases used to each get their own `timeout_secs`, which could double worst-case latency.
- `with_limit(2)` (not `1`) to scrape only the seed page: spider's budget check treats `budget == 1` as already over-budget, so `with_limit(1)` skips even the seed page and returns zero pages.
- Clears `chromiumoxide-runner/SingletonLock` before each run to recover from prior Chrome crashes (falls back to `remove_dir_all` on the whole runner dir if the single-file removal fails for a reason other than "not found").
- Features: stealth mode, request interception, idle-network wait (configurable via `HEADLESS_IDLE_WAIT_SECS`, separate from `HEADLESS_TIMEOUT_SECS`), PNG screenshot capture into `screenshot_bytes`.
- **`HeadlessCapture.status` is the navigation status code, and a non-2xx there means the HTML is untrustworthy.** A WAF block page is a perfectly well-formed document with a `<title>` — without this field the caller stores "出错啦! - bilibili.com" as the site title. spider also normalizes Chrome's own `ERR_*` pages (served as 200) to 599. `capture_headless` itself does *not* treat non-2xx as an error: the anti-bot fallback rejects such a result outright (it has a better error to report), while the other Layer 2 entry points keep the content and push a warning.
- Integration tests are `#[ignore]` — run explicitly when Chrome is available. Any test that drives headless **through the router** also needs `RUST_MIN_STACK=16777216`: spider/chromiumoxide's debug-build frames overflow the test thread's default 2MB stack. Release builds are unaffected.

**`cache.rs`** — In-memory LRU cache via moka, holding three independent `moka::future::Cache` instances.
- Positive cache (`inner`): 10 000-entry capacity, TTL = `CACHE_TTL_SECS` (default 3600s). URL normalization before keying: lowercase host, sort query params, strip fragment (`normalize()`, shared by get/set).
- Negative cache (`errors`): 1 000-entry capacity, fixed 60s TTL, keyed the same way. `get_error()`/`set_error()` back the "recently failed, retry after 60s" fast-reject path in `main.rs`; only `Timeout`/`FetchFailed`/`HeadlessFailed` populate it — `InvalidUrl`/`ForbiddenTarget` do not, since those aren't transient.
- Headless breaker (`headless_futile`): 1 000-entry capacity, 900s TTL, keyed by **`scheme://host`, not URL**. Set when the anti-bot Layer 2 fallback fails (blocked or Chrome error); checked before that fallback launches Chrome at all. The long TTL and coarse key are both deliberate — a re-crawl of one account's few hundred bilibili `/video/BVxxx` links must cost **one** Chrome launch, not a few hundred, because the prod container is capped at `mem_limit: 1g` and a browser tree is the single largest thing that runs in it. The site is rejecting this machine, not that path.

**`oss.rs`** — Optional Alibaba Cloud OSS upload, signed and sent directly over `reqwest` (no SDK dependency).
- `OssClient::from_env()` returns `None` when any OSS_* var is missing; OSS is silently disabled. It builds its own `reqwest::Client` (30s timeout, honors `PROXY_URL`) — separate from the page-scrape client since uploads can be several MB and shouldn't share `REQUEST_TIMEOUT_SECS`.
- Requests are signed with Aliyun OSS's V1 scheme by hand: `Authorization: OSS <key_id>:<base64(hmac_sha1(secret, string_to_sign))>` (`sign_hmac_sha1_base64`), PUT straight to the virtual-hosted-style URL `https://{bucket}.{endpoint}/{key}`. This replaced the `oss-rust-sdk` crate, which was unmaintained, built its own untimeoutable/unproxyable client, and dragged in a whole second major version of `reqwest` plus two extra `base64` versions as transitive dependencies — removing it also made the `quick-xml` future-incompat warning `cargo build` used to print go away (it was `oss-rust-sdk`'s dependency, not ours).
- `upload_assets()` concurrently uploads OG image, logo, and screenshot; replaces URLs in `ScrapeResult`.
- Favicon is **never** uploaded to OSS — always fetched and returned as a base64 `data:` URL.
- **Asset keys are content-addressed: SHA-256 of the *bytes*, with no file extension** (`asset_key`). Identical images reachable at several URLs collapse onto one object, and a key's contents never change (a site swapping its logo yields a new key rather than overwriting the old one). The extension is omitted on purpose — the same bytes can be declared `image/png` by one site and `application/octet-stream` by another, and deriving a suffix from that would produce two keys for one hash, breaking the consumer's uniqueness constraint on the hash. MIME travels in the response instead.
- **Screenshot keys stay SHA-256 of the page URL** (`screenshot_key`), deliberately. URL addressing is self-overwriting, so a page re-crawled a hundred times occupies one object; a screenshot differs on every capture, so content addressing would give unbounded growth for zero deduplication benefit.
- All keys live under the `OSS_KEY_PREFIX` (default `scrapper`) prefix. PUT is unconditional (no existence check — with content addressing a redundant PUT just rewrites identical bytes). Upload returns the **object key**, not a URL — the domain, signing and resizing are the caller's policy.
- `sign_hmac_sha1_base64` has a known-answer test against RFC 2202 test case 1 — worth keeping if this ever gets refactored, since a silent signing bug would fail every OSS upload without necessarily erroring loudly (Aliyun returns a plain `403` for a bad signature, same as for a lot of other misconfigurations).

## Deployment Notes

- `deploy/compose.prod.yml` binds the host port as `127.0.0.1:3001:3000` — **loopback only**. It was briefly changed to a bare `3001:3000` (public on all interfaces) for manual testing and left that way for weeks before being caught in review; this service has no network-level protection of its own beyond `SCRAPER_AUTH_TOKEN`, so don't republish it to `0.0.0.0`/a public IP without also turning that on. Local dev used to point straight at the public prod port (`bookmarkify-api`'s `application-dev.yml`) — now run scrapper locally (`PORT=3001 cargo run -p scraper-service`) or use an SSH tunnel instead.
- `deploy/compose.prod.yml` sets `stop_grace_period: 40s`, comfortably above `HEADLESS_TIMEOUT_SECS` (30s), so graceful shutdown (see `main.rs`) has time to drain an in-flight headless scrape before Docker SIGKILLs the container.
- The Dockerfile supports `--build-arg USE_CN_MIRROR=1` to enable `rsproxy.cn` (Cargo) and `mirrors.aliyun.com` (apt) for faster builds in China. Defaults to `0` (standard mirrors) for CI/CD compatibility.
- `.github/workflows/deploy-scrapper.yml` runs `cargo test` and `cargo clippy -p scraper-service --all-targets -- -D warnings` before building the release binary — a failing test or lint blocks the deploy.

## API

Full request/response/error details: see `api.md`. Summary:

`/scrape` and `/ping` require `Authorization: Bearer <SCRAPER_AUTH_TOKEN>` whenever that env var is set (`401 unauthorized` otherwise); both are also capped at `MAX_CONCURRENT_REQUESTS` in-flight requests (`503 service overloaded` beyond that). `GET /metrics` serves Prometheus text-format request/latency metrics, unauthenticated.

```
POST /scrape
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

```
POST /ping
Content-Type: application/json

{ "url": "https://example.com" }
→ { "alive": true }
```
Simple liveness check (`HEAD` request, status `< 500` ⇒ alive) — not cached, used by `bookmarkify-api`'s ping-log feature.
