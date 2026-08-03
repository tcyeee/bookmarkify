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
  └─ render.mode = HEADLESS, or screenshot.enabled with mode = AUTO? → skip to Layer 2
       (an explicit mode = HTTP is NOT promoted — that combination can never produce a
        screenshot, and says so in diagnostics.warnings rather than silently omitting it)
  └─ Layer 1: reqwest HTTP fetch
       └─ title found? → cache + return
       └─ no title (JS-rendered) → Layer 2
       └─ 403/406/412 (anti-bot) → origin warm-up retry → still blocked? → rescue ladder:
             ① site official API (siteapi.rs, a few KB) → hit? → done, no Chrome at all
             ② host already in the headless breaker (900s)? → skip Layer 2, don't spend a Chrome
             ③ Layer 2; navigation also non-2xx? → trip the breaker for this host,
                report the Layer 1 error, NOT the block page
  └─ Layer 2: headless Chrome (spider-rs) → rendered HTML (+ screenshot only if requested,
       which also switches OFF the CSS/image/font blocking — see headless.rs below)
  └─ extract.rs: HTML → metadata + declared image list (pure, no network)
  └─ pipeline.rs: manifest fetch, image probe/download/upload; screenshot uploaded separately
  └─ Timeout / FetchFailed / HttpStatus / HeadlessFailed → write to negative cache (60s TTL) before returning the error
```

Errors are JSON `{"error": "<type>", "detail": "<optional>"}`. Status mapping: `422 invalid url`, `403 forbidden target`, `504 timeout`, `502 fetch failed` / `headless failed` / negative-cache rejection. Per-image failures never fail the request — that entry carries an `error` field and a warning is logged instead. A failed screenshot upload likewise degrades to an inline `dataUrl` plus a warning.

### Key Modules (`crates/scraper-service/src/`)

**`main.rs`** — Server setup, route handlers, `AppState`.
- Routes: `GET /health`, `GET /metrics` (Prometheus text format, via `axum-prometheus`), `POST /scrape`, `POST /ping`. `/health` and `/metrics` are unauthenticated and unlimited (ops endpoints); `/scrape` and `/ping` sit behind `auth_middleware` (no-op unless `SCRAPER_AUTH_TOKEN` is set) and a `concurrency_limit` + `load_shed` layer (`MAX_CONCURRENT_REQUESTS`, fails fast with `503 service overloaded` instead of queueing).
- `build_router(state, max_concurrent) -> Router` assembles everything except `/metrics` (which needs a process-global metrics recorder installed exactly once — done in `main()`, not in `build_router`, so integration tests can call `build_router` repeatedly within one test binary without hitting `axum-prometheus`'s "recorder already set" panic).
- `POST /ping` (`{"url": "..."}` → `{"alive": bool}`): does its own `validate_target_host()` pre-flight — added after finding that the shared client's `SsrfSafeResolver` is *not* consulted for hosts that are already IP literals (many HTTP stacks, including this one via hyper, connect straight to an IP literal without a DNS step), so a bare `"http://169.254.169.254/"` used to reach the target directly. A blocked target now degrades to `alive: false` rather than a distinct error. Otherwise sends a `HEAD` request through the shared client; any response status `< 500` counts as alive, connection failure/timeout counts as dead. Used by `bookmarkify-api`'s bookmark-liveness/ping-log feature — independent of the scrape cache.
- `scrape_handler` is the whole pipeline: cache → fetch (rescue ladder above) → `extract::extract_page` → `pipeline::process_assets` → screenshot upload → assemble `ScrapeResponse`. Response shape lives in `contract.rs`; see the API section below for why it is not restated here.
- **The positive cache key includes "was a screenshot requested"** (`ScrapeCache::with_shot`). Screenshot presence is a difference in *kind*, not detail: without it a plain request would be served a cached response carrying a screenshot (a multi-MB base64 `dataUrl` when OSS is unconfigured), and a screenshot request would be served one without — indistinguishable from the feature being broken. Other options (`download`, the `extract` toggles) only vary the level of detail, so they stay out of the key.
- Graceful shutdown: `shutdown_signal()` waits on SIGTERM/Ctrl+C and is wired via `axum::serve(..).with_graceful_shutdown(..)`, so an in-flight headless scrape gets to finish instead of being cut off mid-request. The container's `stop_grace_period` (see `deploy/compose.prod.yml`) must stay comfortably above `HEADLESS_TIMEOUT_SECS` or Docker SIGKILLs before that drain completes.
- Integration tests at the bottom of `main.rs` exercise the full router via `tower::ServiceExt::oneshot` (cache hit/miss, negative cache, auth allow/deny, SSRF blocks) without binding a real port or hitting the network — including a regression test for the `/ping` IP-literal SSRF gap above.

**`scraper.rs`** — Layer 1 HTTP scraping and HTML parsing.
- HTML → metadata parsing lives in `extract.rs` (pure, offline-testable), not here; `scraper.rs` owns the transport (fetch, redirects, SSRF, anti-bot header sets).
- `ScrapeError` variants: `InvalidUrl`, `ForbiddenTarget`, `Timeout`, `FetchFailed`, `HttpStatus`, `HeadlessFailed`, `OssFailed`. `HttpStatus` ("connected but rejected") is deliberately distinct from `FetchFailed` ("never connected") — opposite debugging paths — though both surface as the `FETCH_FAILED` wire code.
- **SSRF protection:** `validate_target_host()` + a custom `SsrfSafeResolver` reject hosts resolving to private/loopback/link-local IPs (both IP literals and DNS results). The configured proxy host is exempt (trusted, lives on the docker private network). Bypass with `SSRF_ALLOW_PRIVATE=1`. A blocked target maps to `ForbiddenTarget` → HTTP `403`.

**`headless.rs`** — Layer 2 headless Chrome via spider-rs.
- Global `HEADLESS_LOCK: Mutex<()>` enforces serial Chrome execution (only one browser instance at a time).
- Lock-wait and the Chrome run itself share a single deadline (`timeout_secs` total) via `tokio::time::timeout_at` — the two phases used to each get their own `timeout_secs`, which could double worst-case latency.
- `with_limit(2)` (not `1`) to scrape only the seed page: spider's budget check treats `budget == 1` as already over-budget, so `with_limit(1)` skips even the seed page and returns zero pages.
- Clears `chromiumoxide-runner/SingletonLock` before each run to recover from prior Chrome crashes (falls back to `remove_dir_all` on the whole runner dir if the single-file removal fails for a reason other than "not found").
- Features: stealth mode, request interception, idle-network wait (configurable via `HEADLESS_IDLE_WAIT_SECS`, separate from `HEADLESS_TIMEOUT_SECS`), screenshot capture into `screenshot_bytes`.
- **⚠️ Screenshots still come out as bare unstyled HTML — open bug.** No CSS, no images, no web fonts (Times serif, default bullets, blue underlined links). Nothing in the response reveals it: HTTP 200, plausible byte count, valid `storageKey` — which is why it went unnoticed for so long. The obvious suspect was `RequestInterceptConfiguration::new(true)`, whose constructor also sets `block_visuals` (Image/Media/Font) and `block_stylesheets` (CSS). **Measurement disproved it**: stripe.com at 1280×720 yields a *byte-identical* 107264-byte capture with those flags on, with them off, and with interception `enabled: false` entirely. Styles never participate in the render at all; the real cause is still unlocated (candidates: the capture happens before styles apply; or spider's `scrape()` path does not load subresources). See `headless.rs::screenshot_still_renders_unstyled` for the recorded measurements and a probe that writes the PNG to temp for eyeballing. Do not claim screenshots work until this is closed.
- `intercept_config(want_screenshot)` clears `block_visuals`/`block_stylesheets` for screenshot requests and keeps `block_analytics` on either way. This does **not** fix the bug above, but blocking styles while taking a screenshot is semantically wrong regardless, and it is guarded by an offline unit test.
- **`omit_background` must be passed as `Some(false)`, not `None`.** spider resolves `None` by reading `SCREENSHOT_OMIT_BACKGROUND`, whose fallback when unset is **`true`** — i.e. the page background gets knocked out to transparent. `None` here means "guess from the environment", not "use the default".
- `screenshot.format`/`quality`/`fullPage` and `render.viewport.dpr` are all honored. Format goes straight to CDP (`png|jpeg|webp` are native — no transcoding, no image crate), `quality` is sent only for the lossy formats, and `dpr` rides on `Viewport.device_scale_factor` (note `Viewport::new(w, h)` leaves that `None`, which silently drops it).
- **`HeadlessCapture.status` is the navigation status code, and a non-2xx there means the HTML is untrustworthy.** A WAF block page is a perfectly well-formed document with a `<title>` — without this field the caller stores "出错啦! - bilibili.com" as the site title. spider also normalizes Chrome's own `ERR_*` pages (served as 200) to 599. `capture_headless` itself does *not* treat non-2xx as an error: the anti-bot fallback rejects such a result outright (it has a better error to report), while the other Layer 2 entry points keep the content and push a warning.
- Integration tests are `#[ignore]` — run explicitly when Chrome is available. Any test that drives headless **through the router** also needs `RUST_MIN_STACK=16777216`: spider/chromiumoxide's debug-build frames overflow the test thread's default 2MB stack. Release builds are unaffected.

**`cache.rs`** — In-memory LRU cache via moka, holding three independent `moka::future::Cache` instances.
- Positive cache (`inner`): 10 000-entry capacity, TTL = `CACHE_TTL_SECS` (default 3600s). URL normalization before keying: lowercase host, sort query params, strip fragment (`normalize()`, shared by get/set).
- Negative cache (`errors`): 1 000-entry capacity, fixed 60s TTL, keyed the same way. `get_error()`/`set_error()` back the "recently failed, retry after 60s" fast-reject path in `main.rs`; only `Timeout`/`FetchFailed`/`HeadlessFailed` populate it — `InvalidUrl`/`ForbiddenTarget` do not, since those aren't transient.
- Headless breaker (`headless_futile`): 1 000-entry capacity, 900s TTL, keyed by **`scheme://host`, not URL**. Set when the anti-bot Layer 2 fallback fails (blocked or Chrome error); checked before that fallback launches Chrome at all. The long TTL and coarse key are both deliberate — a re-crawl of one account's few hundred bilibili `/video/BVxxx` links must cost **one** Chrome launch, not a few hundred, because the prod container is capped at `mem_limit: 1g` and a browser tree is the single largest thing that runs in it. The site is rejecting this machine, not that path.

**`siteapi.rs`** — Site official-API adapters. **The only host-specific code in this service**, and it stays narrow on purpose.
- Why it exists: measured on the prod box (Tencent Cloud IDC IP), `www.bilibili.com/` → 200 but `www.bilibili.com/video/BV…/` → **412**, while `api.bilibili.com/x/web-interface/view` → **200** with the same title/cover. Root-path `buvid3`/`b_nut` and even a genuine fingerprint from `x/frontend/finger/spi` do not help — what is being rejected is the **egress IP**, not the request shape. Headless Chrome cannot fix that either (it changes the shape, not the IP) and costs ~400MB, so the API adapter runs *first* in the rescue ladder.
- What it does **not** do: it still only reports facts (title/desc/cover as the site states them) and records provenance honestly as `SITE_API` / `SITE_API_COVER` — never impersonating `OG`. Role and quality remain the API's call.
- It is a **rescue path only**: reached solely after Layer 1 was blocked by an anti-bot status. From an environment where the normal fetch works (e.g. a laptop), it never runs.
- `SiteApiMeta::into_extracted()` flattens the result into the same `Extracted` the HTML parser produces, so asset probing/OSS upload and response assembly are reused unchanged — the cover image gets exactly the same treatment as an `og:image`.
- The URL→id parsing (`bilibili_view_query`) is pure and unit-tested offline; only the request itself needs the network (`#[ignore]`).

**`oss.rs`** — Optional Alibaba Cloud OSS upload, signed and sent directly over `reqwest` (no SDK dependency).
- `OssClient::from_env()` returns `None` when any OSS_* var is missing; OSS is silently disabled. It builds its own `reqwest::Client` (30s timeout, honors `PROXY_URL`) — separate from the page-scrape client since uploads can be several MB and shouldn't share `REQUEST_TIMEOUT_SECS`.
- Requests are signed with Aliyun OSS's V1 scheme by hand: `Authorization: OSS <key_id>:<base64(hmac_sha1(secret, string_to_sign))>` (`sign_hmac_sha1_base64`), PUT straight to the virtual-hosted-style URL `https://{bucket}.{endpoint}/{key}`. This replaced the `oss-rust-sdk` crate, which was unmaintained, built its own untimeoutable/unproxyable client, and dragged in a whole second major version of `reqwest` plus two extra `base64` versions as transitive dependencies — removing it also made the `quick-xml` future-incompat warning `cargo build` used to print go away (it was `oss-rust-sdk`'s dependency, not ours).
- `upload_bytes()` is the only upload entry point: PUT with up to 3 retries, non-retryable failures (credentials/signature/permission) fail fast rather than burning three identical attempts. It returns an **object key, never a URL** — domain, presigning and resizing are the consumer's deployment policy. Declared images go through `pipeline.rs` (`assets.download = UPLOAD`); the screenshot is uploaded separately in `main.rs` since it is not a declared asset.
- **Asset keys are content-addressed: SHA-256 of the *bytes*, with no file extension** (`asset_key`). Identical images reachable at several URLs collapse onto one object, and a key's contents never change (a site swapping its logo yields a new key rather than overwriting the old one). The extension is omitted on purpose — the same bytes can be declared `image/png` by one site and `application/octet-stream` by another, and deriving a suffix from that would produce two keys for one hash, breaking the consumer's uniqueness constraint on the hash. MIME travels in the response instead.
- **Screenshot keys stay SHA-256 of the page URL** (`screenshot_key`), deliberately. URL addressing is self-overwriting, so a page re-crawled a hundred times occupies one object; a screenshot differs on every capture, so content addressing would give unbounded growth for zero deduplication benefit. The extension follows the requested format (`.webp`/`.jpg`/`.png`) — a `.png`-suffixed WebP is purely misleading when someone is eyeballing keys in the bucket. Production pins one format, so self-overwriting still holds in practice.
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

{ "url": "https://example.com", "render": { "mode": "AUTO" } }
```

The response is the structured contract in `contract.rs` — `request` (echoed), `fetch`, `meta`
(with per-field `sources`), `assets[]`, `manifest`, `jsonld`, `opengraph`, `twitter`, `feeds`,
`alternates`, `text`, `screenshot`, `diagnostics`. Wire format is camelCase with
`SCREAMING_SNAKE_CASE` enums; `None` and empty collections are omitted.

**The field list is deliberately not duplicated here.** It is pinned in three places that must
stay in sync — `crates/scraper-service/src/contract.rs`, `bookmarkify-api/.../ScrapeContract.kt`,
and the shared fixture `contract/scrape-response.sample.json` (deserialized by three test suites,
so changing one side without the others turns all three red). Copying it into prose just adds a
fourth copy with no test behind it, which is how this section came to describe a flat
`{title, description, image, favicon, logo, source}` response that had not existed for a long
time. Read `api.md` for the prose walkthrough and `contract.rs` for the truth.

```
POST /ping
Content-Type: application/json

{ "url": "https://example.com" }
→ { "alive": true }
```
Simple liveness check (`HEAD` request, status `< 500` ⇒ alive) — not cached, used by `bookmarkify-api`'s ping-log feature.
