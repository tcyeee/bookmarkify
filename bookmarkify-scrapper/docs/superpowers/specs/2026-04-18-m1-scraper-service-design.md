# M1 设计文档 — scraper-service（Layer 1 基础服务）

**日期：** 2026-04-18  
**阶段：** M1（模块 A 基础，3–4 天）

---

## 目标

构建独立的 `scraper-service` crate，对外暴露 HTTP API，使用 `reqwest` + `scraper` 实现 Layer 1 轻量元数据提取，不依赖 headless Chrome。

---

## Crate 结构

```
crates/scraper-service/
├── Cargo.toml
└── src/
    ├── main.rs      # axum server、routes、auth middleware
    └── scraper.rs   # Layer 1：reqwest fetch + HTML metadata 解析
```

`scraper-demo` 保留为 M0 参考，不做修改。

---

## API

### `GET /health`

无需认证。

```json
200 OK
{"status": "ok"}
```

### `POST /scrape`

```
Authorization: Bearer <API_KEY>
Content-Type: application/json

{"url": "https://example.com"}
```

成功响应：

```json
200 OK
{
  "title": "...",
  "description": "...",
  "image": "https://...",
  "favicon": "https://...",
  "source": "og" | "twitter_card" | "json_ld" | "html"
}
```

字段均为 `Option<String>`，未抓到时返回 `null`。

错误响应：

| 状态码 | body |
|--------|------|
| 401 | `{"error": "unauthorized"}` |
| 422 | `{"error": "invalid url"}` |
| 502 | `{"error": "fetch failed", "detail": "..."}` |
| 504 | `{"error": "timeout"}` |

---

## 认证

`main.rs` 实现 axum extractor 或 middleware，从 `Authorization: Bearer <token>` 提取 token，与 `API_KEY` 环境变量比较。不匹配返回 401。`/health` 路由跳过认证。

---

## Layer 1 解析逻辑

`scraper.rs` 暴露：

```rust
pub async fn scrape(url: &str, client: &reqwest::Client) -> Result<ScrapeResult, ScrapeError>
```

**流程：**

1. `reqwest` GET，携带伪装 User-Agent（`Mozilla/5.0 ... Chrome/...`）
2. 解析 HTML，按优先级提取元数据：
   - OG tags（`og:title`, `og:description`, `og:image`）
   - Twitter Card（`twitter:title`, `twitter:description`, `twitter:image`）
   - JSON-LD（`<script type="application/ld+json">`，取 `name`/`description`/`image`）
   - HTML fallback（`<title>`, `<meta name="description">`）
3. favicon：`<link rel="icon">` / `<link rel="shortcut icon">`，fallback 拼接 `{scheme}://{host}/favicon.ico`
4. `source` 字段记录实际使用的数据源

**错误类型：**

```rust
pub enum ScrapeError {
    InvalidUrl,
    Timeout,
    FetchFailed(String),
}
```

Handler 直接 match 转对应 HTTP 状态码。

---

## 环境变量

| 变量 | 必填 | 默认值 | 说明 |
|------|------|--------|------|
| `API_KEY` | 是 | — | 启动时缺失则 panic |
| `REQUEST_TIMEOUT_SECS` | 否 | `10` | reqwest 超时秒数 |
| `PORT` | 否 | `3000` | 监听端口 |

---

## 依赖

```toml
axum = "0.7"
tokio = { version = "1", features = ["full"] }
reqwest = { version = "0.12", features = ["json"] }
scraper = "0.19"
serde = { version = "1", features = ["derive"] }
serde_json = "1"
tower-http = { version = "0.5", features = ["trace"] }
```

---

## 不在 M1 范围内

- headless Chrome / spider-rs（M2）
- 限流（M3）
- 缓存（M3）
- Layer 1 → Layer 2 fallback 路由（M3）
- Docker 部署（M4）
