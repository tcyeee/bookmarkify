# API 文档

bookmarkify-scrapper 对外暴露 REST API，供 bookmarkify-api 调用。

---

## 认证

所有接口需在 Header 中携带 API Key：

```
Authorization: Bearer <api-key>
```

---

## 接口

### POST /scrape

解析指定 URL 的网页元数据，可选截图。

**请求**

```http
POST /scrape
Authorization: Bearer <api-key>
Content-Type: application/json

{
  "url": "https://example.com",
  "screenshot": false
}
```

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `url` | string | ✅ | 目标网页 URL |
| `screenshot` | boolean | ❌ | 是否截图，默认 `false` |

**响应 200 OK**

```json
{
  "title": "Example Domain",
  "description": "This domain is for use in illustrative examples.",
  "image": "https://example.com/og-image.png",
  "favicon": "data:image/png;base64,iVBORw0KGgo...",
  "logo": "https://example.com/logo-180x180.png",
  "screenshot_url": "https://oss.example.com/screenshots/xxx.png",
  "source": "og"
}
```

| 字段 | 类型 | 说明 |
|------|------|------|
| `title` | string \| null | 页面标题 |
| `description` | string \| null | 页面描述 |
| `image` | string \| null | 封面图 URL（OG image 优先）；配置 OSS 时为 OSS URL |
| `favicon` | string \| null | 网站图标，始终以 base64 data URL 格式返回 |
| `logo` | string \| null | 网站 Logo URL；来源优先级：JSON-LD logo → apple-touch-icon → 最大尺寸 icon；配置 OSS 时为 OSS URL |
| `screenshot_url` | string \| null | 截图 URL（仅 `screenshot=true` 且成功时返回） |
| `source` | string | 元数据来源：`og` \| `twitter_card` \| `json_ld` \| `headless` |

**错误响应**

| HTTP 状态码 | 说明 |
|-------------|------|
| 400 | 请求参数错误（如 URL 格式非法） |
| 401 | API Key 缺失或无效 |
| 429 | 请求频率超限 |
| 502 | 目标页面抓取失败 |
| 500 | 服务内部错误 |

```json
{
  "error": "invalid_url",
  "message": "The provided URL is not valid."
}
```

---

### GET /health

健康检查。

**请求**

```http
GET /health
```

**响应 200 OK**

```json
{
  "status": "ok"
}
```

---

## 解析策略说明

Module A 按以下顺序尝试提取元数据，成功即返回：

1. **OG Tags** — `og:title`, `og:description`, `og:image`
2. **Twitter Card** — `twitter:title`, `twitter:description`, `twitter:image`
3. **JSON-LD** — `@type: WebPage/Article` 中的 name/description/image
4. **Fallback** — `<title>` + `<meta name="description">`

以上均失败时，转发给 Module B（headless Chrome），`source` 字段返回 `headless`。

---

## 限流

默认限流策略（可配置）：

- 每个 IP：60 次 / 分钟
- 超限返回 `429 Too Many Requests`，响应头携带 `Retry-After`

---

## 示例

```bash
curl -X POST https://scraper.example.com/scrape \
  -H "Authorization: Bearer my-api-key" \
  -H "Content-Type: application/json" \
  -d '{"url": "https://huaban.com/", "screenshot": false}'
```
