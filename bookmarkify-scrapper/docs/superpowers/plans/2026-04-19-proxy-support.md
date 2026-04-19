# Proxy Support Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Route Layer 1 (reqwest HTTP) requests through an HTTP proxy configured via `PROXY_URL` environment variable.

**Architecture:** Read `PROXY_URL` at startup in `main.rs`; if present, inject `reqwest::Proxy::all(url)` into the client builder before calling `.build()`. No other files change. Proxy failure at runtime surfaces through the existing `ScrapeError::FetchFailed` path.

**Tech Stack:** Rust, reqwest 0.12 (proxy support is built-in, no new dependencies needed)

---

### Task 1: Implement PROXY_URL support in main.rs

**Files:**
- Modify: `crates/scraper-service/src/main.rs:105-108`

The current client-building block (lines 105–108) is:

```rust
let client = reqwest::Client::builder()
    .timeout(Duration::from_secs(timeout_secs))
    .build()
    .expect("failed to build reqwest client");
```

- [ ] **Step 1: Replace the client-building block**

Open `crates/scraper-service/src/main.rs` and replace the block above with:

```rust
let proxy_url = env::var("PROXY_URL").ok();

let mut client_builder = reqwest::Client::builder()
    .timeout(Duration::from_secs(timeout_secs));

if let Some(ref url) = proxy_url {
    let proxy = reqwest::Proxy::all(url)
        .expect("PROXY_URL is not a valid proxy URL");
    client_builder = client_builder.proxy(proxy);
    tracing::info!("proxy enabled: {url}");
}

let client = client_builder.build().expect("failed to build reqwest client");
```

- [ ] **Step 2: Verify the service compiles**

```bash
cargo build -p scraper-service
```

Expected: compiles without errors or warnings.

- [ ] **Step 3: Verify existing unit tests still pass**

```bash
cargo test -p scraper-service
```

Expected: all tests pass (proxy change does not affect HTML parsing tests).

- [ ] **Step 4: Smoke-test without proxy (no-op path)**

```bash
API_KEY=test cargo run -p scraper-service &
sleep 2
curl -s -X POST http://localhost:3000/scrape \
  -H "Authorization: Bearer test" \
  -H "Content-Type: application/json" \
  -d '{"url":"https://example.com"}' | jq .title
kill %1
```

Expected: returns `"Example Domain"` with no proxy-related errors in logs.

- [ ] **Step 5: Smoke-test with an invalid PROXY_URL (fail-fast path)**

```bash
PROXY_URL=not-a-valid-url API_KEY=test cargo run -p scraper-service
```

Expected: process exits immediately with a panic message containing `"PROXY_URL is not a valid proxy URL"`.

- [ ] **Step 6: Update CLAUDE.md env var table**

In `CLAUDE.md`, add a row to the environment variables table:

```markdown
| `PROXY_URL` | 无（可选）| HTTP 代理地址，如 `http://127.0.0.1:7890`。未设置时直连。 |
```

- [ ] **Step 7: Commit**

```bash
git add crates/scraper-service/src/main.rs CLAUDE.md
git commit -m "feat: add PROXY_URL env var for HTTP proxy support (Layer 1)"
```
