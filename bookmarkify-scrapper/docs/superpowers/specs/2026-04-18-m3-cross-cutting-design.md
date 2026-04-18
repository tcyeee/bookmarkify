# M3 — 横切功能设计文档

**日期:** 2026-04-18  
**状态:** 已批准

---

## 目标

在现有 `scraper-service` 基础上添加三个横切能力：
1. **限流**：按 API Key 限制请求速率（tower-governor）
2. **缓存**：相同 URL 在 TTL 内不重复抓取（moka）
3. **自动 fallback**：Layer 1 返回空 title 时自动调用 Layer 2

---

## 模块结构

```
crates/scraper-service/src/
├── main.rs       # 已有 — 新增限流层挂载、缓存初始化、fallback 路由
├── scraper.rs    # 已有 — 不变
├── headless.rs   # 已有 — 不变
└── cache.rs      # 新增 — URL 规范化 + moka 缓存封装
```

`Cargo.toml` 新增依赖：

```toml
moka = { version = "0.12", features = ["future"] }
tower-governor = "0.4"
```

---

## 缓存设计（cache.rs）

### 数据结构

```rust
pub struct ScrapeCache {
    inner: Cache<String, Arc<ScrapeResult>>,
}
```

Key 为规范化 URL，Value 为 `Arc<ScrapeResult>`（避免克隆开销）。

### 公开接口

```rust
impl ScrapeCache {
    pub fn new(ttl_secs: u64) -> Self
    pub async fn get(&self, url: &str) -> Option<Arc<ScrapeResult>>
    pub async fn set(&self, url: &str, result: Arc<ScrapeResult>)
    fn normalize(url: &str) -> Option<String>
}
```

### URL 规范化规则

`normalize(url)` 返回用于 cache key 的字符串：
1. 解析 URL，scheme 和 host 转小写
2. query params 按字母排序后重新拼接
3. 去掉 fragment（`#` 及之后部分）
4. 解析失败返回 `None`（不缓存）

示例：
- `https://Example.com/page?b=2&a=1#section` → `https://example.com/page?a=1&b=2`

### 配置

| env var | 默认值 | 说明 |
|---------|--------|------|
| `CACHE_TTL_SECS` | `3600` | 缓存 TTL（秒） |

### 响应字段

缓存命中时 `ScrapeResponse` 新增 `"cached": true`：

```json
{ "title": "...", "source": "og", "cached": true }
```

未命中时省略该字段（`#[serde(skip_serializing_if = "Option::is_none")]`）。

---

## Fallback 路由逻辑（main.rs）

`scrape_handler` 完整路由流程：

```
请求到达
  │
  ├─ 检查缓存 → 命中 → 返回（cached: true）
  │
  ├─ headless: true → Layer 2 → 写缓存 → 返回
  │
  └─ headless: false（默认）
       │
       ├─ Layer 1
       │    ├─ title 非空 → 写缓存 → 返回
       │    └─ title 为 None → 自动 fallback Layer 2 → 写缓存 → 返回
       │
       └─ Layer 1/2 均失败 → 返回对应错误（不写缓存）
```

fallback 对调用方透明，`source` 字段变为 `"headless"` 表示实际走了 Layer 2。

---

## 限流设计（main.rs）

### 实现

使用 `tower-governor` 挂在 `/scrape` 路由上，自定义 `KeyExtractor` 从 `Authorization: Bearer <key>` 提取限流 key。

```rust
struct ApiKeyExtractor;

impl KeyExtractor for ApiKeyExtractor {
    type Key = String;
    fn extract<B>(&self, req: &Request<B>) -> Result<Self::Key, GovernorError> {
        req.headers()
            .get("Authorization")
            .and_then(|v| v.to_str().ok())
            .and_then(|v| v.strip_prefix("Bearer "))
            .map(|k| k.to_string())
            .ok_or(GovernorError::UnableToExtractKey)
    }
}
```

### 配置

| env var | 默认值 | 说明 |
|---------|--------|------|
| `RATE_LIMIT_RPS` | `10` | 每个 API Key 每秒最大请求数 |

burst_size = `RATE_LIMIT_RPS * 2`（允许短暂突发）。

### 限流响应

```
429 Too Many Requests
{"error": "rate limit exceeded"}
```

---

## AppState 变更

```rust
#[derive(Clone)]
struct AppState {
    client: reqwest::Client,
    api_key: String,
    headless_timeout_secs: u64,
    cache: Arc<ScrapeCache>,  // 新增
}
```

---

## 测试策略

### 单元测试（cache.rs）

| 测试 | 验证内容 |
|------|---------|
| `normalize_removes_fragment` | `#section` 被去掉 |
| `normalize_sorts_query_params` | `?b=2&a=1` → `?a=1&b=2` |
| `normalize_lowercases_host` | `HTTPS://EXAMPLE.COM` → `https://example.com` |
| `normalize_invalid_url_returns_none` | `not-a-url` → `None` |
| `cache_hit_returns_same_result` | set 后 get 返回相同数据 |

### 行为验证（手动 curl）

- 同一 URL 连续两次请求：第二次响应含 `"cached": true`
- 超出 RPS 限制：返回 `429`

---

## 交付标准

- [ ] `cargo build -p scraper-service` 无 error
- [ ] `cargo test -p scraper-service` 全部非 ignore 测试通过（含新增 cache 单元测试）
- [ ] 同一 URL 第二次请求返回 `cached: true`
- [ ] Layer 1 拿不到 title 的 URL 自动走 Layer 2（source 变为 headless）
- [ ] 超出限流返回 429
