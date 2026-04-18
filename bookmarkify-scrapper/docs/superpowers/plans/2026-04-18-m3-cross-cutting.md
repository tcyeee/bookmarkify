# M3 — 横切功能实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 为 `scraper-service` 添加三个横切能力：按 API Key 限流、URL 结果缓存（TTL 1h）、Layer 1 空 title 时自动 fallback 到 Layer 2。

**Architecture:** 新增 `cache.rs` 封装 moka，`AppState` 持有 `Arc<ScrapeCache>`；`scrape_handler` 按缓存 → Layer 1 → fallback → Layer 2 的顺序路由；`tower-governor` 作为 tower layer 挂在 `/scrape` 路由的 auth 内层。

**Tech Stack:** moka 0.12 (future feature), tower-governor 0.8 (axum feature), 现有 axum 0.7 / reqwest 0.12 栈

---

## 文件变更清单

| 操作 | 文件 |
|------|------|
| Modify | `crates/scraper-service/Cargo.toml` |
| Create | `crates/scraper-service/src/cache.rs` |
| Modify | `crates/scraper-service/src/main.rs` |

---

### Task 1: 添加依赖

**Files:**
- Modify: `crates/scraper-service/Cargo.toml`

- [ ] **Step 1: 在 `[dependencies]` 末尾追加两行**

  ```toml
  moka = { version = "0.12", features = ["future"] }
  tower-governor = { version = "0.8", features = ["axum"] }
  ```

- [ ] **Step 2: 验证编译**

  ```bash
  cd /Users/tcyeee/Documents/Code/bookmarkify/bookmarkify-scrapper
  cargo build -p scraper-service 2>&1
  ```

  期望：编译成功（新依赖下载后无 error）。

- [ ] **Step 3: Commit**

  ```bash
  git add crates/scraper-service/Cargo.toml
  git commit -m "feat(m3): add moka and tower-governor deps"
  ```

---

### Task 2: 创建 cache.rs（TDD）

**Files:**
- Create: `crates/scraper-service/src/cache.rs`

- [ ] **Step 1: 先写所有单元测试**

  新建 `crates/scraper-service/src/cache.rs`，内容仅含测试（先让测试失败）：

  ```rust
  use std::sync::Arc;
  use std::time::Duration;
  use moka::future::Cache;
  use crate::scraper::ScrapeResult;

  pub struct ScrapeCache {
      inner: Cache<String, Arc<ScrapeResult>>,
  }

  impl ScrapeCache {
      pub fn new(ttl_secs: u64) -> Self {
          todo!()
      }

      pub async fn get(&self, url: &str) -> Option<Arc<ScrapeResult>> {
          todo!()
      }

      pub async fn set(&self, url: &str, result: Arc<ScrapeResult>) {
          todo!()
      }

      fn normalize(url: &str) -> Option<String> {
          todo!()
      }
  }

  #[cfg(test)]
  mod tests {
      use super::*;

      fn make_result(title: &str) -> Arc<ScrapeResult> {
          Arc::new(ScrapeResult {
              title: Some(title.to_string()),
              description: None,
              image: None,
              favicon: None,
              source: "og".to_string(),
              screenshot_bytes: None,
          })
      }

      #[test]
      fn normalize_removes_fragment() {
          let result = ScrapeCache::normalize("https://example.com/page#section");
          assert_eq!(result, Some("https://example.com/page".to_string()));
      }

      #[test]
      fn normalize_sorts_query_params() {
          let result = ScrapeCache::normalize("https://example.com/?b=2&a=1");
          assert_eq!(result, Some("https://example.com/?a=1&b=2".to_string()));
      }

      #[test]
      fn normalize_lowercases_host() {
          let result = ScrapeCache::normalize("https://EXAMPLE.COM/path");
          assert_eq!(result, Some("https://example.com/path".to_string()));
      }

      #[test]
      fn normalize_invalid_url_returns_none() {
          let result = ScrapeCache::normalize("not-a-url");
          assert_eq!(result, None);
      }

      #[tokio::test]
      async fn cache_hit_returns_same_result() {
          let cache = ScrapeCache::new(3600);
          let url = "https://example.com/";
          let r = make_result("Hello");
          cache.set(url, Arc::clone(&r)).await;
          let got = cache.get(url).await;
          assert!(got.is_some());
          assert_eq!(got.unwrap().title, r.title);
      }

      #[tokio::test]
      async fn cache_miss_returns_none() {
          let cache = ScrapeCache::new(3600);
          let got = cache.get("https://example.com/never-set").await;
          assert!(got.is_none());
      }

      #[tokio::test]
      async fn cache_normalizes_key_on_set_and_get() {
          let cache = ScrapeCache::new(3600);
          let r = make_result("Frag Test");
          cache.set("https://example.com/page#hash", Arc::clone(&r)).await;
          // same URL without fragment should hit
          let got = cache.get("https://example.com/page").await;
          assert!(got.is_some(), "should hit cache ignoring fragment");
      }
  }
  ```

- [ ] **Step 2: 在 `main.rs` 顶部加 `mod cache;`**

  将 `main.rs` 开头改为：

  ```rust
  mod cache;
  mod headless;
  mod scraper;
  ```

- [ ] **Step 3: 运行测试，确认因 `todo!()` 而 panic**

  ```bash
  cargo test -p scraper-service 2>&1 | grep -E "(FAILED|panicked|ok|error)"
  ```

  期望：`normalize_*` 和 `cache_*` 测试均 FAILED（`not yet implemented`）。

- [ ] **Step 4: 实现 `ScrapeCache`**

  用以下内容替换 `cache.rs` 中 `ScrapeCache` 的实现（保留末尾测试不变）：

  ```rust
  use std::sync::Arc;
  use std::time::Duration;
  use moka::future::Cache;
  use crate::scraper::ScrapeResult;

  pub struct ScrapeCache {
      inner: Cache<String, Arc<ScrapeResult>>,
  }

  impl ScrapeCache {
      pub fn new(ttl_secs: u64) -> Self {
          Self {
              inner: Cache::builder()
                  .time_to_live(Duration::from_secs(ttl_secs))
                  .build(),
          }
      }

      pub async fn get(&self, url: &str) -> Option<Arc<ScrapeResult>> {
          let key = Self::normalize(url)?;
          self.inner.get(&key).await
      }

      pub async fn set(&self, url: &str, result: Arc<ScrapeResult>) {
          if let Some(key) = Self::normalize(url) {
              self.inner.insert(key, result).await;
          }
      }

      fn normalize(url: &str) -> Option<String> {
          let mut parsed = reqwest::Url::parse(url).ok()?;
          // sort query params alphabetically
          let mut pairs: Vec<(String, String)> = parsed
              .query_pairs()
              .map(|(k, v)| (k.into_owned(), v.into_owned()))
              .collect();
          if pairs.is_empty() {
              parsed.set_query(None);
          } else {
              pairs.sort_by(|a, b| a.0.cmp(&b.0));
              let query = pairs
                  .iter()
                  .map(|(k, v)| format!("{}={}", k, v))
                  .collect::<Vec<_>>()
                  .join("&");
              parsed.set_query(Some(&query));
          }
          // remove fragment
          parsed.set_fragment(None);
          Some(parsed.to_string())
      }
  }
  ```

- [ ] **Step 5: 运行测试，确认全部通过**

  ```bash
  cargo test -p scraper-service 2>&1
  ```

  期望：所有非 ignore 测试通过，包括 `normalize_*` 和 `cache_*` 共 7 个新测试。

- [ ] **Step 6: Commit**

  ```bash
  git add crates/scraper-service/src/cache.rs crates/scraper-service/src/main.rs
  git commit -m "feat(m3): add ScrapeCache with URL normalization and TTL"
  ```

---

### Task 3: 接入缓存 — AppState + scrape_handler

**Files:**
- Modify: `crates/scraper-service/src/main.rs`

- [ ] **Step 1: 在 `main.rs` 顶部添加 cache import**

  在已有的 `use` 块末尾添加：

  ```rust
  use cache::ScrapeCache;
  use std::sync::Arc;
  ```

- [ ] **Step 2: 更新 `AppState` 加入 `cache` 字段**

  将 `AppState` 替换为：

  ```rust
  #[derive(Clone)]
  struct AppState {
      client: reqwest::Client,
      api_key: String,
      headless_timeout_secs: u64,
      cache: Arc<ScrapeCache>,
  }
  ```

- [ ] **Step 3: 在 `main()` 中读取 `CACHE_TTL_SECS` 并初始化缓存**

  紧接 `headless_timeout_secs` 读取之后添加：

  ```rust
  let cache_ttl_secs: u64 = env::var("CACHE_TTL_SECS")
      .ok()
      .and_then(|v| v.parse().ok())
      .unwrap_or(3600);
  let cache = Arc::new(ScrapeCache::new(cache_ttl_secs));
  ```

  将 `AppState` 构造改为：

  ```rust
  let state = AppState { client, api_key, headless_timeout_secs, cache };
  ```

- [ ] **Step 4: 更新 `ScrapeResponse` 加入 `cached` 字段**

  将 `ScrapeResponse` 替换为：

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
  }
  ```

- [ ] **Step 5: 更新 `scrape_handler` 加入缓存读写（暂无 fallback）**

  将 `scrape_handler` 替换为：

  ```rust
  async fn scrape_handler(
      State(state): State<AppState>,
      Json(body): Json<ScrapeRequest>,
  ) -> Response {
      // cache lookup
      if let Some(cached) = state.cache.get(&body.url).await {
          return Json(ScrapeResponse {
              title: cached.title.clone(),
              description: cached.description.clone(),
              image: cached.image.clone(),
              favicon: cached.favicon.clone(),
              source: cached.source.clone(),
              cached: Some(true),
          })
          .into_response();
      }

      let result = if body.headless.unwrap_or(false) {
          headless::scrape_headless(&body.url, state.headless_timeout_secs).await
      } else {
          scraper::scrape(&body.url, &state.client).await
      };

      match result {
          Ok(r) => {
              let r = Arc::new(r);
              state.cache.set(&body.url, Arc::clone(&r)).await;
              Json(ScrapeResponse {
                  title: r.title.clone(),
                  description: r.description.clone(),
                  image: r.image.clone(),
                  favicon: r.favicon.clone(),
                  source: r.source.clone(),
                  cached: None,
              })
              .into_response()
          }

          Err(scraper::ScrapeError::InvalidUrl) => (
              StatusCode::UNPROCESSABLE_ENTITY,
              Json(ErrorResponse { error: "invalid url".to_string(), detail: None }),
          )
              .into_response(),

          Err(scraper::ScrapeError::Timeout) => (
              StatusCode::GATEWAY_TIMEOUT,
              Json(ErrorResponse { error: "timeout".to_string(), detail: None }),
          )
              .into_response(),

          Err(scraper::ScrapeError::FetchFailed(msg)) => (
              StatusCode::BAD_GATEWAY,
              Json(ErrorResponse { error: "fetch failed".to_string(), detail: Some(msg) }),
          )
              .into_response(),

          Err(scraper::ScrapeError::HeadlessFailed(msg)) => (
              StatusCode::BAD_GATEWAY,
              Json(ErrorResponse { error: "headless failed".to_string(), detail: Some(msg) }),
          )
              .into_response(),
      }
  }
  ```

- [ ] **Step 6: 编译验证**

  ```bash
  cargo build -p scraper-service 2>&1
  ```

  期望：编译成功，无 error。

- [ ] **Step 7: 运行所有测试**

  ```bash
  cargo test -p scraper-service 2>&1
  ```

  期望：全部非 ignore 测试通过。

- [ ] **Step 8: 手动验证缓存**

  ```bash
  API_KEY=test cargo run -p scraper-service 2>/dev/null &
  sleep 2
  # 第一次请求
  curl -s -X POST http://localhost:3000/scrape \
    -H "Authorization: Bearer test" -H "Content-Type: application/json" \
    -d '{"url":"https://example.com"}' | python3 -m json.tool
  # 第二次请求 — 应含 "cached": true
  curl -s -X POST http://localhost:3000/scrape \
    -H "Authorization: Bearer test" -H "Content-Type: application/json" \
    -d '{"url":"https://example.com"}' | python3 -m json.tool
  kill %1 2>/dev/null
  ```

  期望：第一次无 `cached` 字段，第二次含 `"cached": true`。

- [ ] **Step 9: Commit**

  ```bash
  git add crates/scraper-service/src/main.rs
  git commit -m "feat(m3): wire ScrapeCache into AppState and scrape_handler"
  ```

---

### Task 4: 自动 fallback 路由（Layer 1 title=None → Layer 2）

**Files:**
- Modify: `crates/scraper-service/src/main.rs`

- [ ] **Step 1: 更新 `scrape_handler` 中 `headless: false` 路径加入 fallback 逻辑**

  将 `scrape_handler` 中 `headless.unwrap_or(false)` 判断及其后逻辑替换为：

  ```rust
      let result = if body.headless.unwrap_or(false) {
          headless::scrape_headless(&body.url, state.headless_timeout_secs).await
      } else {
          match scraper::scrape(&body.url, &state.client).await {
              Ok(r) if r.title.is_none() => {
                  // Layer 1 returned no title — fallback to Layer 2
                  headless::scrape_headless(&body.url, state.headless_timeout_secs).await
              }
              other => other,
          }
      };
  ```

- [ ] **Step 2: 编译验证**

  ```bash
  cargo build -p scraper-service 2>&1
  ```

  期望：编译成功，无 error。

- [ ] **Step 3: 运行所有测试**

  ```bash
  cargo test -p scraper-service 2>&1
  ```

  期望：全部非 ignore 测试通过。

- [ ] **Step 4: Commit**

  ```bash
  git add crates/scraper-service/src/main.rs
  git commit -m "feat(m3): auto-fallback to Layer 2 when Layer 1 returns empty title"
  ```

---

### Task 5: 限流（tower-governor 按 API Key）

**Files:**
- Modify: `crates/scraper-service/src/main.rs`

- [ ] **Step 1: 在 `main.rs` 顶部添加 tower-governor imports**

  在已有 `use` 块末尾添加：

  ```rust
  use tower_governor::{
      governor::GovernorConfigBuilder,
      key_extractor::KeyExtractor,
      GovernorLayer,
      GovernorError,
  };
  use axum::http::Request as HttpRequest;
  ```

- [ ] **Step 2: 在 `AppState` 定义之前添加 `ApiKeyExtractor`**

  ```rust
  #[derive(Clone)]
  struct ApiKeyExtractor;

  impl KeyExtractor for ApiKeyExtractor {
      type Key = String;

      fn extract<T>(&self, req: &HttpRequest<T>) -> Result<Self::Key, GovernorError> {
          req.headers()
              .get("Authorization")
              .and_then(|v| v.to_str().ok())
              .and_then(|v| v.strip_prefix("Bearer "))
              .map(|k| k.to_string())
              .ok_or(GovernorError::UnableToExtractKey)
      }
  }
  ```

- [ ] **Step 3: 在 `main()` 中读取 `RATE_LIMIT_RPS` 并构建 governor config**

  紧接 `cache` 初始化之后添加：

  ```rust
  let rate_limit_rps: u32 = env::var("RATE_LIMIT_RPS")
      .ok()
      .and_then(|v| v.parse().ok())
      .unwrap_or(10);
  let governor_config = Arc::new(
      GovernorConfigBuilder::default()
          .key_extractor(ApiKeyExtractor)
          .per_second(rate_limit_rps)
          .burst_size(rate_limit_rps * 2)
          .finish()
          .expect("invalid governor config"),
  );
  ```

- [ ] **Step 4: 将 `GovernorLayer` 挂在 `/scrape` 路由上（auth 内层）**

  将 `protected` 路由构建改为：

  ```rust
  let protected = Router::new()
      .route("/scrape", post(scrape_handler))
      .layer(GovernorLayer { config: Arc::clone(&governor_config) })
      .layer(middleware::from_fn_with_state(state.clone(), auth_middleware));
  ```

- [ ] **Step 5: 编译验证**

  ```bash
  cargo build -p scraper-service 2>&1
  ```

  期望：编译成功，无 error。如果 `GovernorError` 相关类型有问题，检查 tower-governor 0.8 文档并调整 import 路径（`GovernorError` 可能在 `tower_governor::errors` 模块）。

- [ ] **Step 6: 运行所有测试**

  ```bash
  cargo test -p scraper-service 2>&1
  ```

  期望：全部非 ignore 测试通过。

- [ ] **Step 7: 手动验证限流**

  ```bash
  API_KEY=test RATE_LIMIT_RPS=2 cargo run -p scraper-service 2>/dev/null &
  sleep 2
  # 快速连发 6 次，应在第 5-6 次左右触发 429
  for i in $(seq 1 6); do
    curl -s -o /dev/null -w "req $i: %{http_code}\n" \
      -X POST http://localhost:3000/scrape \
      -H "Authorization: Bearer test" -H "Content-Type: application/json" \
      -d '{"url":"https://example.com"}'
  done
  kill %1 2>/dev/null
  ```

  期望：前几次 200，之后出现 429。

- [ ] **Step 8: Commit**

  ```bash
  git add crates/scraper-service/src/main.rs
  git commit -m "feat(m3): add per-API-key rate limiting with tower-governor"
  ```

---

## M3 交付验收

全部 Task 完成后，确认以下检查项：

- [ ] `cargo build -p scraper-service` 无 error
- [ ] `cargo test -p scraper-service` 全部非 ignore 测试通过（含 7 个新 cache 测试）
- [ ] 同一 URL 第二次请求含 `"cached": true`
- [ ] Layer 1 空 title URL 自动走 Layer 2（`source: "headless"`）
- [ ] 超出 RPS 限制返回 429
