# bookmarkify-scrapper

面向书签场景的网页元数据抓取服务，支持静态页面与 JavaScript 渲染页面，提供 HTTP API 接口。

## 功能特性

- **双层抓取策略**
  - **Layer 1（轻量）**：通过 `reqwest` 发起 HTTP 请求，解析 HTML 提取元数据，延迟低、资源占用少
  - **Layer 2（无头浏览器）**：通过 `spider-rs` 驱动 Chrome，适用于重度依赖 JavaScript 渲染的页面
  - 当 Layer 1 无法获取标题时，自动回退到 Layer 2，对调用方透明

- **元数据提取优先级**（高到低）
  1. Open Graph（`og:title`、`og:description`、`og:image`）
  2. Twitter Card（`twitter:title`、`twitter:description`、`twitter:image`）
  3. JSON-LD 结构化数据（`name`、`description`、`image`）
  4. HTML 标签回退（`<title>`、`<meta name="description">`）

- **URL 规范化缓存**：基于 `moka`，自动去除 fragment、排序查询参数，TTL 可配置
- **截图能力**：无头模式下捕获全页 PNG 截图；配置 OSS 后自动上传并返回公网 URL，否则返回 base64 编码数据
- **OSS 上传**：可选接入阿里云 OSS，自动将截图和封面图上传并替换为持久化 URL
- **代理支持**：通过 `PROXY_URL` 配置 HTTP 代理，适用于受限网络环境

## 项目结构

```
bookmarkify-scrapper/
├── Cargo.toml                       # Workspace 配置
└── crates/
    ├── scraper-service/             # 生产服务
    │   └── src/
    │       ├── main.rs              # HTTP 服务器、路由、AppState
    │       ├── scraper.rs           # Layer 1：HTML 元数据解析
    │       ├── headless.rs          # Layer 2：无头 Chrome 抓取
    │       ├── cache.rs             # URL 规范化缓存
    │       └── oss.rs               # 阿里云 OSS 上传客户端
    └── scraper-demo/                # M0 验证 Demo（不用于生产）
        └── src/
            └── main.rs              # 花瓣/知乎反爬验证脚本
```

## 快速开始

### 前置条件

- Rust 1.75+（`rustup` 安装）
- Chrome 浏览器（无头模式所需）

### 构建

```bash
cargo build -p scraper-service --release
```

### 启动服务

```bash
# 最简启动
./target/release/scraper-service

# 启用代理
PROXY_URL=http://127.0.0.1:7890 ./target/release/scraper-service

# 启用 OSS 上传
OSS_ACCESS_KEY_ID=xxx OSS_ACCESS_KEY_SECRET=xxx \
OSS_BUCKET=my-bucket OSS_ENDPOINT=oss-cn-hangzhou.aliyuncs.com \
OSS_BASE_URL=https://my-bucket.oss-cn-hangzhou.aliyuncs.com \
./target/release/scraper-service
```

服务默认监听 `0.0.0.0:3000`。

## 环境变量

| 变量名 | 必填 | 默认值 | 说明 |
|---|---|---|---|
| `PORT` | | `3000` | HTTP 监听端口 |
| `REQUEST_TIMEOUT_SECS` | | `10` | Layer 1 HTTP 请求超时（秒） |
| `HEADLESS_TIMEOUT_SECS` | | `30` | Layer 2 无头浏览器超时（秒） |
| `CACHE_TTL_SECS` | | `3600` | 缓存条目存活时间（秒） |
| `PROXY_URL` | | — | HTTP 代理地址，例如 `http://127.0.0.1:7890`，不设则直连 |
| `OSS_ACCESS_KEY_ID` | | — | 阿里云 Access Key ID，五个 OSS_* 变量须同时配置才生效 |
| `OSS_ACCESS_KEY_SECRET` | | — | 阿里云 Access Key Secret |
| `OSS_BUCKET` | | — | OSS Bucket 名称 |
| `OSS_ENDPOINT` | | — | OSS 地域 Endpoint，例如 `oss-cn-hangzhou.aliyuncs.com` |
| `OSS_BASE_URL` | | — | 返回链接的公网前缀，例如 `https://<bucket>.oss-cn-hangzhou.aliyuncs.com` |
| `RUST_LOG` | | `info` | 日志过滤器，支持 `debug`、`info`、`warn` 等 |

## API 文档

### `GET /health`

健康检查，无需鉴权，供负载均衡器或容器编排探活使用。

**响应**

```json
200 OK
{"status": "ok"}
```

---

### `POST /scrape`

抓取目标 URL 的页面元数据。

**请求头**

```
Content-Type: application/json
```

**请求体**

```json
{
  "url": "https://example.com",
  "headless": false
}
```

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `url` | string | ✅ | 目标页面 URL |
| `headless` | boolean | | `true` 强制使用 Layer 2；`false`（默认）先尝试 Layer 1，title 为空时自动回退 |

**成功响应**

```json
200 OK
{
  "title": "示例页面标题",
  "description": "页面描述文字",
  "image": "https://example.com/og-image.jpg",
  "favicon": "data:image/png;base64,iVBORw0KGgo...",
  "logo": "https://example.com/logo-180x180.png",
  "source": "og",
  "cached": true,
  "screenshot": "https://my-bucket.oss-cn-hangzhou.aliyuncs.com/screenshots/xxx.png"
}
```

| 字段 | 说明 |
|---|---|
| `title` | 页面标题，可能为 `null` |
| `description` | 页面描述，可能为 `null` |
| `image` | 页面主图 URL，可能为 `null`；配置 OSS 时为 OSS URL |
| `favicon` | 网站图标，始终以 base64 data URL 格式返回，可能为 `null` |
| `logo` | 网站 Logo URL，可能为 `null`；来源优先级：JSON-LD logo → apple-touch-icon → 最大尺寸 icon；配置 OSS 时为 OSS URL |
| `source` | 数据来源：`"og"` / `"twitter_card"` / `"json_ld"` / `"html"` / `"headless"` |
| `cached` | 命中缓存时为 `true`，实时抓取时省略此字段 |
| `screenshot` | 仅无头模式下存在。配置 OSS 时为公网 URL，否则为 base64 编码的 PNG 数据 |

**错误响应**

| 状态码 | 说明 | 响应体示例 |
|---|---|---|
| `422` | URL 格式非法 | `{"error": "invalid url"}` |
| `502` | 网络请求失败 | `{"error": "fetch failed", "detail": "..."}` |
| `502` | 无头浏览器失败 | `{"error": "headless failed", "detail": "..."}` |
| `503` | OSS 上传失败 | `{"error": "oss upload failed", "detail": "..."}` |
| `504` | 请求超时 | `{"error": "timeout"}` |

**请求示例**

```bash
curl -X POST http://localhost:3000/scrape \
  -H "Content-Type: application/json" \
  -d '{"url": "https://github.com"}'
```

```bash
# 强制使用无头浏览器（适用于 JS 渲染页面）
curl -X POST http://localhost:3000/scrape \
  -H "Content-Type: application/json" \
  -d '{"url": "https://huaban.com", "headless": true}'
```

## 运行测试

```bash
# 运行所有单元测试（排除需要真实浏览器的集成测试）
cargo test -p scraper-service

# 运行需要 Chrome 的无头集成测试（耗时较长）
cargo test -p scraper-service -- --ignored
```

## 技术栈

| 组件 | 库 | 说明 |
|---|---|---|
| HTTP 框架 | `axum 0.7` | 异步路由与中间件 |
| 异步运行时 | `tokio 1` | 多线程异步执行器 |
| HTTP 客户端 | `reqwest 0.12` | Layer 1 页面抓取，支持代理 |
| HTML 解析 | `scraper 0.19` | CSS 选择器解析 DOM |
| 无头浏览器 | `spider 2` | Chrome 驱动，含隐身模式 |
| 缓存 | `moka 0.12` | 异步内存缓存，支持 TTL |
| OSS 上传 | `oss-rust-sdk 0.3` | 阿里云对象存储客户端 |
| 序列化 | `serde / serde_json` | JSON 请求/响应 |
| 日志 | `tracing / tracing-subscriber` | 结构化日志，支持 `RUST_LOG` |
