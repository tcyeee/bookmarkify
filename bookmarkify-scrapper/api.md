# API 文档

bookmarkify-scrapper 对外暴露 REST API，供上游 `bookmarkify-api` 调用。

- 所有端点**均无需鉴权**（鉴权由上游 `bookmarkify-api` 负责）。
- 所有请求与响应均为 JSON。
- 基础地址：`http://<host>:<PORT>`，本地默认 `http://localhost:3000`（端口由 `PORT` 环境变量控制）。

| 方法 | 路径 | 说明 |
|------|------|------|
| `GET` | `/health` | 健康检查 |
| `POST` | `/scrape` | 抓取页面元数据 |

---

## POST /scrape

解析指定 URL 的网页元数据，可选无头浏览器渲染。

### 请求

```http
POST /scrape
Content-Type: application/json

{
  "url": "https://example.com",
  "headless": false
}
```

| 字段 | 类型 | 必填 | 默认 | 说明 |
|------|------|------|------|------|
| `url` | string | ✅ | — | 目标网页 URL，仅支持 `http` / `https` |
| `headless` | boolean | ❌ | `false` | `true` 强制使用无头浏览器（Layer 2）；`false` 先尝试 Layer 1，`title` 为空时自动回退到 Layer 2 |

### 响应 · `200 OK`

```json
{
  "title": "Example Domain",
  "description": "This domain is for use in illustrative examples.",
  "image": "https://example.com/og-image.png",
  "favicon": "data:image/png;base64,iVBORw0KGgo...",
  "logo": "https://example.com/logo-180x180.png",
  "source": "og",
  "cached": true,
  "screenshot": "https://oss.example.com/bookmarkify/scrapper/screenshots/xxx.png"
}
```

| 字段 | 类型 | 说明 |
|------|------|------|
| `title` | string \| null | 页面标题 |
| `description` | string \| null | 页面描述 |
| `image` | string \| null | 封面图 URL（OG image 优先）；配置 OSS 时替换为 OSS URL |
| `favicon` | string \| null | 网站图标，**始终**以 base64 `data:` URL 返回（从不上传 OSS）；下载上限 2 MB |
| `logo` | string \| null | 网站 Logo URL，来源优先级：JSON-LD `logo` → `apple-touch-icon` → 最大尺寸 `icon`；配置 OSS 时替换为 OSS URL |
| `source` | string | 元数据来源：`og` \| `twitter_card` \| `json_ld` \| `html` \| `headless` |
| `cached` | boolean | **仅命中缓存时出现**且恒为 `true`；实时抓取时省略该字段 |
| `screenshot` | string | **仅无头模式下出现**；配置 OSS 时为公网 URL，否则为 base64 编码的 PNG 数据 |

> **资源非致命降级**：`image` / `logo` / `favicon` / `screenshot` 任一资源的下载或 OSS 上传失败时，对应字段会被置为 `null`（或退回原始 URL）并记录告警日志，**不会**使整个请求失败。

### 错误响应

错误响应体统一为 `{"error": "<类型>", "detail": "<可选详情>"}`，`detail` 仅在有附加信息时出现。

| HTTP 状态码 | `error` | 触发条件 |
|-------------|---------|----------|
| `403` | `forbidden target` | 目标命中 SSRF 防护（解析到私有 / 回环 / 链路本地地址） |
| `422` | `invalid url` | URL 格式非法（非 http/https 或无法解析） |
| `502` | `fetch failed` | Layer 1 网络请求失败 |
| `502` | `headless failed` | Layer 2 无头浏览器抓取失败 |
| `502` | `scrape failed recently, retry after 60s` | 命中**负缓存**：该 URL 60 秒内刚失败过，直接拒绝以避免重复触发高开销抓取 |
| `504` | `timeout` | 抓取超时（Layer 1 见 `REQUEST_TIMEOUT_SECS`，Layer 2 见 `HEADLESS_TIMEOUT_SECS`） |

```json
{
  "error": "forbidden target",
  "detail": "host resolves to private address 10.0.0.5"
}
```

---

## GET /health

健康检查，供负载均衡器或容器编排探活使用。始终返回 `200 OK`。

### 请求

```http
GET /health
```

### 响应 · `200 OK`

```json
{ "status": "ok" }
```

---

## 解析策略

不指定 `headless`（或 `headless=false`）时，Layer 1（普通 HTTP）按以下优先级提取元数据，成功即返回：

1. **Open Graph** — `og:title`、`og:description`、`og:image`
2. **Twitter Card** — `twitter:title`、`twitter:description`、`twitter:image`
3. **JSON-LD** — 结构化数据中的 `name` / `description` / `image`
4. **HTML 回退** — `<title>` + `<meta name="description">`

Layer 1 未获取到 `title` 时（常见于纯 JS 渲染页面），自动回退至 Layer 2（headless Chrome），此时 `source` 返回 `headless`，并附带 `screenshot` 字段。`headless=true` 时跳过 Layer 1，直接走 Layer 2。

---

## 缓存语义

- **正向缓存**：成功结果以规范化 URL（小写主机、排序查询参数、去除 fragment）为键写入内存缓存，命中时响应携带 `"cached": true`。容量 10,000 条，TTL 由 `CACHE_TTL_SECS` 控制（默认 3600s）。
- **负向缓存**：`timeout` / `fetch failed` / `headless failed` 会将 URL 标记为近期失败（TTL 60s），期间重复请求直接返回 `502 scrape failed recently, retry after 60s`。`invalid url` 与 `forbidden target` 不写入负缓存。

---

## 调用示例

```bash
# 默认抓取（Layer 1 优先，无标题时自动回退到无头）
curl -X POST http://localhost:3000/scrape \
  -H "Content-Type: application/json" \
  -d '{"url": "https://github.com"}'
```

```bash
# 强制使用无头浏览器（适用于 JS 渲染页面）
curl -X POST http://localhost:3000/scrape \
  -H "Content-Type: application/json" \
  -d '{"url": "https://huaban.com/", "headless": true}'
```

```bash
# 健康检查
curl http://localhost:3000/health
```
