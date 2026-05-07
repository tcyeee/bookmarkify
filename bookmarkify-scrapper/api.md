# API 文档

bookmarkify-scrapper 对外暴露 REST API，供 bookmarkify-api 调用。

---

## 接口

### POST /scrape

解析指定 URL 的网页元数据，可选无头浏览器渲染。

**请求**

```http
POST /scrape
Content-Type: application/json

{
  "url": "https://example.com",
  "headless": false
}
```

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `url` | string | ✅ | 目标网页 URL（仅支持 http/https） |
| `headless` | boolean | ❌ | 是否强制使用无头浏览器，默认 `false`（Layer 1 无标题时自动回退） |

**响应 200 OK**

```json
{
  "title": "Example Domain",
  "description": "This domain is for use in illustrative examples.",
  "image": "https://example.com/og-image.png",
  "favicon": "data:image/png;base64,iVBORw0KGgo...",
  "logo": "https://example.com/logo-180x180.png",
  "source": "og",
  "cached": true,
  "screenshot": "https://oss.example.com/screenshots/xxx.png"
}
```

| 字段 | 类型 | 说明 |
|------|------|------|
| `title` | string \| null | 页面标题 |
| `description` | string \| null | 页面描述 |
| `image` | string \| null | 封面图 URL（OG image 优先）；配置 OSS 时为 OSS URL |
| `favicon` | string \| null | 网站图标，始终以 base64 data URL 格式返回 |
| `logo` | string \| null | 网站 Logo URL；来源优先级：JSON-LD logo → apple-touch-icon → 最大尺寸 icon；配置 OSS 时为 OSS URL |
| `source` | string | 元数据来源：`og` \| `twitter_card` \| `json_ld` \| `html` \| `headless` |
| `cached` | boolean | 仅命中缓存时出现，值为 `true` |
| `screenshot` | string | 截图（仅 headless 模式下出现）：配置 OSS 时为 OSS URL，否则为 base64 PNG |

**错误响应**

| HTTP 状态码 | 说明 |
|-------------|------|
| 422 | URL 格式非法（非 http/https 或无法解析） |
| 504 | 抓取超时 |
| 502 | 目标页面抓取失败或无头浏览器失败 |
| 503 | OSS 上传失败 |

```json
{
  "error": "invalid url",
  "detail": "可选的详细错误信息"
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

Layer 1（普通 HTTP）按以下顺序尝试提取元数据，成功即返回：

1. **OG Tags** — `og:title`, `og:description`, `og:image`
2. **Twitter Card** — `twitter:title`, `twitter:description`, `twitter:image`
3. **JSON-LD** — `@type: WebPage/Article` 中的 name/description/image
4. **Fallback** — `<title>` + `<meta name="description">`

Layer 1 未获取到标题时，自动回退至 Layer 2（headless Chrome），`source` 字段返回 `headless`。

---

## 示例

```bash
curl -X POST https://scraper.example.com/scrape \
  -H "Content-Type: application/json" \
  -d '{"url": "https://huaban.com/", "headless": false}'
```
