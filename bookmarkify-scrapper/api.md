# API 文档

bookmarkify-scrapper 对外暴露 REST API，供上游 `bookmarkify-api` 调用。

- `/scrape`、`/ping` 在配置了 `SCRAPER_AUTH_TOKEN` 环境变量后需要鉴权：请求头带
  `Authorization: Bearer <token>`，`bookmarkify-api` 对应配置项是
  `bookmarkify.scrapper.auth-token`（环境变量 `BOOKMARKIFY_SCRAPPER_AUTH_TOKEN`），
  两边的值必须一致。未设置该环境变量时（本地开发默认状态）两个端点不做鉴权校验。
  `/health`、`/metrics` 任何时候都不需要鉴权。
- `/scrape`、`/ping` 同时被 `MAX_CONCURRENT_REQUESTS`（默认 32）限制并发数，超出时返回
  `503 service overloaded`，不会排队等待。
- 所有请求与响应均为 JSON。
- 基础地址：`http://<host>:<PORT>`，本地默认 `http://localhost:3000`（端口由 `PORT` 环境变量控制）。

| 方法 | 路径 | 说明 | 需要鉴权 |
|------|------|------|----------|
| `GET` | `/health` | 健康检查 | 否 |
| `GET` | `/metrics` | Prometheus 格式的请求量/延迟/状态码指标 | 否 |
| `POST` | `/scrape` | 抓取页面元数据 | 视 `SCRAPER_AUTH_TOKEN` 而定 |
| `POST` | `/ping` | 探测目标 URL 是否存活 | 视 `SCRAPER_AUTH_TOKEN` 而定 |

---

## POST /scrape

抓取指定 URL，返回页面元数据、图片资源声明与传输层事实。

契约的核心约定只有一条：

> **scrapper 只报告事实（页面声明了什么），不做业务解释（这些事实该当什么用）。**

所以响应里只有 `extractor`（这张图**从哪个标签/字段拿到的**），没有 role / logo / favicon
之类的用途判定 —— "apple-touch-icon 算不算 LOGO"是调用方的策略，不是网页的事实。调用方改
判定规则时无需改动 scrapper，也无需重抓。

契约定义见 `crates/scraper-service/src/contract.rs`，调用方侧的镜像见
`bookmarkify-api/.../entity/dto/scrape/ScrapeContract.kt`，两侧共读的样例见仓库根目录
`contract/scrape-response.sample.json`（两端的测试都会反序列化它，任一侧改动都会同时变红）。

### 请求

除 `url` 外全部可省略，省略即取下表默认值。**请求体启用了 `deny_unknown_fields`：多发一个
字段会被整体拒绝（`422`）**，这是刻意的——避免字段名拼错后被静默忽略成默认值。

```jsonc
POST /scrape
Content-Type: application/json

{
  "url": "https://example.com",                 // 必填

  "render": {
    "mode": "AUTO",                             // AUTO(默认) | HTTP | HEADLESS
    "timeoutMs": 15000,                         // 省略则用服务端环境变量
    "waitUntil": "LOAD",                        // LOAD(默认) | DOM_CONTENT_LOADED | NETWORK_IDLE，仅 headless
    "viewport": { "width": 1280, "height": 720, "dpr": 2 },  // 仅 headless
    "userAgent": "...",                         // 省略则用桌面 Chrome UA
    "locale": "zh-CN",                          // 覆盖 Accept-Language
    "colorScheme": "DARK"                       // LIGHT | DARK，抓暗色 LOGO 用，仅 headless
  },

  "extract": {                                  // 各模块独立开关，关掉可省下解析与网络开销
    "meta": true, "assets": true, "manifest": true,
    "jsonld": true, "opengraph": true, "twitter": true,
    "feeds": false, "alternates": false, "text": false
  },

  "assets": {
    "download": "PROBE",                        // NONE | PROBE(默认) | INLINE | UPLOAD
    "maxBytes": 2097152,                        // 单张上限，超出记该张 error
    "maxCount": 20                              // 超出部分仍出现在 assets[] 但不发请求
  },

  "screenshot": { "enabled": false, "fullPage": false, "format": "WEBP", "quality": 80 },  // format: WEBP|JPEG|PNG，由 Chrome 原生输出不做转码；quality 仅对有损格式生效
  "cache":  { "mode": "DEFAULT", "maxAgeS": 86400 },   // DEFAULT | BYPASS | ONLY_IF_CACHED
  "robots": { "respect": true }
}
```

#### `render.mode`

| 取值 | 行为 |
|---|---|
| `AUTO`（默认） | 先走 Layer 1 普通 HTTP；未拿到 `<title>`（常见于纯 JS 渲染页）自动回退 Layer 2 无头浏览器，并在 `diagnostics.warnings` 留一条记录 |
| `HTTP` | 只走 Layer 1，失败即失败，不回退 |
| `HEADLESS` | 直接走 Layer 2 |

`screenshot.enabled = true` 时隐含要走 Layer 2（截图只有无头浏览器能出），`AUTO` 会被提升为 `HEADLESS`。
**显式指定 `HTTP` 不会被提升** —— 那条组合永远出不了图，服务端会在 `diagnostics.warnings` 里
说明，而不是静默省略 `screenshot` 字段。被反爬拦下并走了站点 API 救援时同样没有截图（页面压根
没渲染过，`fetch.layerUsed` 为 `SITE_API`），也会有对应 warning。

开启截图还会**放开无头浏览器的资源拦截**：不截图时 CSS / 图片 / 字体是被拦掉的（Layer 2 的
主要用途是拿渲染后的 HTML，拦掉能省下可观的内存和时间），只有截图才需要它们。

> **截图的"裸 HTML"老问题已于 2026-08-09 随 Layer 2 重写修复。** 病根不在资源拦截（当年实测
> 拦截全开 / 全关 / 完全禁用三种配置下截出的图**逐字节相同**），而在于当时用的是 spider 的
> **爬虫**接口去要一张图 —— 那条路径收集的是爬虫抓到的页面，从来不是"导航到这一页、等它渲染
> 完、再截一帧"。改成直接驱动 CDP 后，同一个 stripe.com 从 107264 字节的无样式文档变成
> 287698 字节的完整渲染图。

#### `assets.download`

三种下载模式**都会取回正文** —— `contentHash` 与真实像素尺寸都必须读到字节才能算。区别只在
拿到之后怎么处置：

| 模式 | 取回正文 | 正文去向 | 产出字段 |
|---|---|---|---|
| `NONE` | 否 | —— | 仅 `declared.*` |
| `PROBE`（默认） | 是 | 算完即丢 | `width` / `height` / `mime` / `byteSize` / `contentHash` |
| `INLINE` | 是 | 编码进 `dataUrl` | 同上 + `dataUrl` |
| `UPLOAD` | 是 | 传对象存储 | 同上 + `storageKey`（object key，不含域名）；服务端未配置 OSS 时自动降级为 `PROBE` |

图标普遍只有几 KB，`PROBE` 的带宽代价很低，换来的是调用方判定"这张图够不够大、能不能当
LOGO 用"以及跨 `extractor` 去重所需的全部依据。

#### `cache.mode`

| 取值 | 行为 |
|---|---|
| `DEFAULT` | 命中则用缓存，否则实时抓 |
| `BYPASS` | 无视正/负缓存强制重抓并覆盖缓存。**管理后台的"重试"必须传这个** —— 否则重试可能直接命中缓存，等于没试 |
| `ONLY_IF_CACHED` | 只用缓存，未命中直接 `404 CACHE_MISS`，不发起任何网络请求 |

### 响应 · `200 OK`

`Option::None` 与空集合一律省略，载荷保持紧凑。原始块（`jsonld` / `opengraph` / `twitter` /
`manifest.raw`）原样透传，不做筛选或合并。

```jsonc
{
  "request": { /* 回显实际生效的参数（含服务端兜底后的值），便于排障与归档 */ },

  "fetch": {
    "finalUrl": "https://example.com/page",     // 跟完重定向后的地址，相对路径均以它为基准
    "redirectChain": [{ "url": "http://example.com/start", "status": 301 }],
    "httpStatus": 200,
    "layerUsed": "HTTP",                        // HTTP | HEADLESS —— AUTO 时告诉你实际走了哪层
    "fromCache": false,
    "contentType": "text/html; charset=utf-8",
    "charset": "utf-8",
    "byteSize": 48213,
    "timingMs": { "total": 421 }
  },

  "meta": {
    "title": "…", "description": "…",
    "siteName": "…",                            // og:site_name / manifest.name
    "shortName": "…",                           // manifest.short_name —— 图标下方短文案的唯一标准来源
    "canonicalUrl": "…", "lang": "en", "themeColor": "#123456",
    "author": "…", "publishedAt": "…", "robots": "…", "keywords": ["…"],

    // 出处**下沉到字段级**：title 可能来自 OG 而 description 回落到 meta[name]，
    // 二者本就可以不同源。旧契约用单一 source 把它们压扁成一个值，那个值一直在说谎。
    "sources": {
      "title":       { "extractor": "OG",        "rawKey": "og:title" },
      "description": { "extractor": "META_NAME", "rawKey": "description" },
      "shortName":   { "extractor": "MANIFEST",  "rawKey": "short_name" }
    }
  },

  "assets": [{
    "extractor": "APPLE_TOUCH_ICON",            // 事实（出处），不是判定（用途）
    "declared": { "rel": "apple-touch-icon", "sizes": "180x180", "type": "image/png" },
    "originUrl": "/touch.png",                  // 声明原值，可能是相对路径
    "resolvedUrl": "https://example.com/touch.png",
    "width": 180, "height": 180, "byteSize": 8123, "mime": "image/png",
    "isVector": false,
    "contentHash": "sha256:…",                  // 跨 extractor 去重 / 判定"没有独立 LOGO"
    "storageKey": null, "dataUrl": null,
    "error": null                               // 单张失败隔离在此，不影响其余图片与整次抓取
  }],

  "manifest":   { "url": "…/site.webmanifest", "raw": { /* 原样透传 */ } },
  "jsonld":     [ /* 全部 JSON-LD 节点，@graph 已展开 */ ],
  "opengraph":  { "title": "…", "site_name": "…" },   // 键已去掉 og: 前缀
  "twitter":    { "card": "summary_large_image" },    // 键已去掉 twitter: 前缀
  "feeds":      [ /* extract.feeds = true 时才有 */ ],
  "alternates": [ /* extract.alternates = true 时才有 */ ],
  "text":       "…",                                  // extract.text = true 时才有
  "screenshot": { "storageKey": "…", "width": 1280, "height": 720, "format": "WEBP", "byteSize": 143507 },

  "diagnostics": {
    "warnings": ["manifest: fetch failed: 404"],      // 非致命问题
    "antiCrawler": { "detected": false },             // 命中时 meta 内容可能不可靠
    "robots": null                                    // robots.txt 判定尚未实现，省略而非谎报
  }
}
```

#### `assets[].extractor` 取值

只描述**出处**。用途（role）与质量分级由调用方的映射表决定。

| 取值 | 来源 |
|---|---|
| `LINK_ICON` | `<link rel="icon">` / `rel="shortcut icon"` |
| `LINK_MASK_ICON` | `<link rel="mask-icon">`（Safari 固定标签页矢量图） |
| `APPLE_TOUCH_ICON` | `<link rel="apple-touch-icon">` |
| `FAVICON_ICO_FALLBACK` | 页面一个 icon 都没声明时，对 `/favicon.ico` 的约定式兜底探测 |
| `MANIFEST_ICON` | Web App Manifest 的 `icons[]` |
| `MS_TILE_IMAGE` | `<meta name="msapplication-TileImage">` |
| `JSON_LD_ORG_LOGO` | JSON-LD `Organization.logo` —— 唯一语义明确的品牌 LOGO |
| `JSON_LD_IMAGE` | JSON-LD `image` |
| `OG_IMAGE` | `<meta property="og:image">` |
| `TWITTER_IMAGE` | `<meta name="twitter:image">` |

页面声明的**每一张**都独立成条，不做择优。同一张图被多个 `extractor` 命中时 `contentHash`
相同 —— 这正是"该站没有独立 LOGO，`apple-touch-icon` 只是 favicon 换个名字"的判据，调用方
据此可以让大图场景走首字母色块，而不是把 32px 的 favicon 拉伸到 72px。

#### `meta.sources[].extractor` 取值

`OG` / `TWITTER_CARD` / `JSON_LD` / `MANIFEST` / `META_NAME` / `TITLE_TAG` / `HTML_ATTR` / `LINK_TAG`。

### 错误响应

统一为 `{"error": "<机器可读码>", "detail": "<可选详情>", "fetch": {…}}`，`detail` 与 `fetch`
仅在有附加信息时出现。`error` 是**稳定的大写常量**，可直接用于分支判断。

| HTTP 状态码 | `error` | 触发条件 |
|-------------|---------|----------|
| `401` | `UNAUTHORIZED` | 配置了 `SCRAPER_AUTH_TOKEN` 但请求未带 / 带错 `Authorization: Bearer <token>` |
| `403` | `FORBIDDEN_TARGET` | 目标命中 SSRF 防护（解析到私有 / 回环 / 链路本地地址） |
| `404` | `CACHE_MISS` | `cache.mode = ONLY_IF_CACHED` 且未命中 |
| `422` | `INVALID_URL` | URL 格式非法（非 http/https 或无法解析） |
| `422` | *(axum 纯文本)* | 请求体含未知字段或类型不符（`deny_unknown_fields`），响应体非 JSON |
| `502` | `FETCH_FAILED` | Layer 1 网络请求失败 |
| `502` | `HEADLESS_FAILED` | Layer 2 跑通了，但这一页没有产出可用文档（空 HTML 等）——**关于目标页面的结论** |
| `503` | `HEADLESS_UNAVAILABLE` | **我方**无头能力当下不可用：Chrome 起不来、CDP 报错、或等不到并发名额（`HEADLESS_QUEUE_WAIT_SECS`）。与目标站点无关，调用方应当稍后重试而不是据此判定站点失联 |
| `502` | `RECENTLY_FAILED` | 命中**负缓存**：该 URL 60 秒内刚失败过。`cache.mode = BYPASS` 可绕过 |
| `503` | `OVERLOADED` | 并发中的 `/scrape` + `/ping` 超过 `MAX_CONCURRENT_REQUESTS`，快速失败而非排队 |
| `503` | `OSS_FAILED` | 对象存储上传失败 |
| `504` | `TIMEOUT` | 抓取超时（Layer 1 见 `REQUEST_TIMEOUT_SECS`，Layer 2 见 `HEADLESS_TIMEOUT_SECS`——**只算页面时间**，排队等待另计，见 `HEADLESS_UNAVAILABLE`） |

```json
{
  "error": "FORBIDDEN_TARGET",
  "detail": "host resolves to blocked address 10.0.0.5"
}
```

---

## POST /ping

探测目标 URL 是否存活：向目标发 `HEAD` 请求，收到任意状态码（含 4xx）即视为存活；
连接失败、超时或目标被 SSRF 防护拦截均视为不存活。不经过 `/scrape` 的缓存/负缓存,
每次都是一次实时请求。供 `bookmarkify-api` 的书签存活检测（ping-log）功能使用。

### 请求

```http
POST /ping
Content-Type: application/json

{ "url": "https://example.com" }
```

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `url` | string | ✅ | 目标 URL，仅支持 `http` / `https` |

### 响应 · `200 OK`

```json
{ "alive": true }
```

被 SSRF 防护拦截的目标（含 IP 字面量形式的内网/回环地址，如 `169.254.169.254`）
会直接返回 `{"alive": false}`，不会暴露"这是因为命中了 SSRF 防护"这类信息。

### 错误响应

只会返回 `422 invalid url`（URL 格式非法）；其余情况一律归入 `alive: false`。

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

## GET /metrics

Prometheus text 格式的运行指标（请求量、状态码分布、延迟直方图），按路径/方法/状态码
打标签，供本机 Prometheus 抓取。不鉴权、不计入并发限制。

```http
GET /metrics
```

```
# TYPE axum_http_requests_total counter
axum_http_requests_total{method="GET",status="200",endpoint="/health"} 1
# TYPE axum_http_requests_duration_seconds histogram
axum_http_requests_duration_seconds_bucket{method="GET",status="200",endpoint="/health",le="0.005"} 1
...
```

---

## 解析策略

元数据提取是**逐字段独立回落**的，各字段的实际出处记录在 `meta.sources[字段名]` 里：

| 字段 | 回落顺序 |
|---|---|
| `title` | `og:title` → `twitter:title` → JSON-LD `name` → `<title>` |
| `description` | `og:description` → `twitter:description` → JSON-LD `description` → `meta[name=description]` |
| `siteName` | `og:site_name` → JSON-LD `publisher` → manifest `name` |
| `shortName` | manifest `short_name`（唯一来源） |
| `themeColor` | `meta[name=theme-color]` → manifest `theme_color` |

**这与旧版按 og / twitter_card / json_ld / html 分四个互斥分支、整体上报一个 `source` 的做法
不同。** 旧做法在分支内部本就会混用来源（OG 分支里 `og:description` 缺失时会回落到
`meta[name=description]`），却仍整体上报 `"og"` —— 那个字段一直在说谎，现已移除。

图片方面，页面声明的每一张都会返回，标注其 `extractor`，不做择优。`<link rel="manifest">`
存在时会**额外发一次请求**拉取 Web App Manifest（`extract.manifest = false` 可关闭），其
`icons[]` 展开为 `MANIFEST_ICON` 资产，`name` / `short_name` / `theme_color` 回填进 `meta`
（只填空缺，不覆盖页面自己声明的值；`shortName` 除外，它只可能来自 manifest）。

---

## 缓存语义

- **正向缓存**：成功结果以规范化 URL（小写主机、排序查询参数、去除 fragment）为键写入内存缓存，命中时响应的 `fetch.fromCache` 为 `true`，且 `request` 块回显的是**本次**请求参数而非当初写入缓存的那次。容量 10,000 条，TTL 由 `CACHE_TTL_SECS` 控制（默认 21600s / 6h —— 重复请求由调用方的小时级巡检驱动，1h 的 TTL 每次都刚好过期，等于没有缓存）。
- **负向缓存**：`TIMEOUT` / `FETCH_FAILED` / `HEADLESS_FAILED` 会将 URL 标记为近期失败（TTL 60s），期间重复请求直接返回 `502 RECENTLY_FAILED`。`INVALID_URL`、`FORBIDDEN_TARGET` 与 `HEADLESS_UNAVAILABLE` **不写入**负缓存——前两个是我方的决定，后一个是我方的状态，把它们记成"这条 URL 刚失败过"会让下一个调用方把我方拥塞读成站点故障。
- **绕过**：`cache.mode = BYPASS` 同时绕过正向与负向缓存并覆盖正向缓存。缓存键只按 URL，不含请求参数，因此缓存里存的是"当次参数下的产物"——参数不同需要重取时请用 `BYPASS`。

---

## 调用示例

```bash
# 默认抓取（Layer 1 优先，无标题时自动回退到无头）
curl -X POST http://localhost:3000/scrape \
  -H "Content-Type: application/json" \
  -d '{"url": "https://github.com"}'
```

```bash
# 强制使用无头浏览器（适用于 JS 渲染页面）并要截图
curl -X POST http://localhost:3000/scrape \
  -H "Content-Type: application/json" \
  -d '{"url": "https://huaban.com/",
       "render": {"mode": "HEADLESS"},
       "screenshot": {"enabled": true}}'
```

```bash
# 管理后台"重试"：绕过缓存强制重抓，并把图标传到对象存储
curl -X POST http://localhost:3000/scrape \
  -H "Content-Type: application/json" \
  -d '{"url": "https://github.com",
       "cache": {"mode": "BYPASS"},
       "assets": {"download": "UPLOAD"}}'
```

```bash
# 只要文字元数据，省掉图片与 manifest 的额外请求
curl -X POST http://localhost:3000/scrape \
  -H "Content-Type: application/json" \
  -d '{"url": "https://github.com",
       "extract": {"assets": false, "manifest": false, "jsonld": false}}'
```

```bash
# 存活检测
curl -X POST http://localhost:3000/ping \
  -H "Content-Type: application/json" \
  -d '{"url": "https://github.com"}'
```

```bash
# 健康检查
curl http://localhost:3000/health
```

```bash
# 配置了 SCRAPER_AUTH_TOKEN 时，/scrape 和 /ping 都要带上 Bearer token
curl -X POST http://localhost:3000/scrape \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer $SCRAPER_AUTH_TOKEN" \
  -d '{"url": "https://github.com"}'
```
