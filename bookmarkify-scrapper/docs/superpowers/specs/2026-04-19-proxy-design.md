# Proxy Support Design

**Date:** 2026-04-19  
**Status:** Approved

## Goal

Enable the scraper service to access overseas websites when deployed in mainland China, by routing Layer 1 (reqwest HTTP) requests through a configurable HTTP proxy.

## Scope

- **In scope:** Layer 1 (reqwest client) proxy via environment variable
- **Out of scope:** Layer 2 (headless Chrome) proxy, per-request proxy override, SOCKS5, proxy rotation

## Changes

### `crates/scraper-service/src/main.rs` only

Read `PROXY_URL` before building `reqwest::Client`. If present, inject `reqwest::Proxy::all(url)` into the client builder and log it at startup.

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

No other files require changes. `AppState`, API schema, and all other modules are unaffected.

## Environment Variable

| Variable | Default | Description |
|---|---|---|
| `PROXY_URL` | none (optional) | HTTP proxy URL, e.g. `http://127.0.0.1:7890`. Omit to connect directly. |

Invalid values cause a startup panic (fail-fast, not silent).

## Error Handling

- Invalid `PROXY_URL` format → panic at startup with a clear message
- Proxy connection failure at runtime → surfaced as `ScrapeError::FetchFailed` (existing behavior, no new handling needed)

## Testing

- Unit tests: no change needed (existing tests mock HTTP, not the client)
- Manual verification: set `PROXY_URL=http://127.0.0.1:7890` and scrape a blocked overseas URL (e.g. `https://twitter.com`)
