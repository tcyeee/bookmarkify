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
- **OSS 上传**：可选接入阿里云 OSS。图片按**内容寻址**（`scrapper/asset/<sha256-of-bytes>`，同图去重），截图按**页面 URL 寻址**（`scrapper/screenshots/<sha256-of-url>.<ext>`，自我覆盖）。返回的是 **object key 而非 URL** —— 桶是私有读的，域名/签名/缩放都是调用方的部署策略
- **代理支持**：通过 `PROXY_URL` 配置 HTTP 代理，Layer 1/Layer 2/OSS 上传均生效
- **SSRF 防护**：默认拦截解析到私有 / 回环 / 链路本地地址的目标，IP 字面量与 DNS 解析结果均校验；可信代理主机自动放行。设置 `SSRF_ALLOW_PRIVATE=1` 关闭（用于内网集成测试等可信场景）
- **鉴权 + 限流**：设置 `SCRAPER_AUTH_TOKEN` 后 `/scrape`、`/ping` 要求 `Authorization: Bearer <token>`；`MAX_CONCURRENT_REQUESTS` 限制并发数，超出快速返回 `503` 而非排队
- **可观测性**：`GET /metrics` 输出 Prometheus 格式的请求量/延迟/状态码指标

## 项目结构

```
bookmarkify-scrapper/
├── Cargo.toml                       # Workspace 配置
└── crates/
    └── scraper-service/             # 生产服务
    │   └── src/
    │       ├── main.rs              # HTTP 服务器、路由、AppState、/scrape 编排
    │       ├── contract.rs          # 对外契约（请求/响应类型），唯一的跨服务耦合面
    │       ├── scraper.rs           # Layer 1：取回 HTML + SSRF 防护基元
    │       ├── headless.rs          # Layer 2：无头 Chrome 取回渲染后的 HTML
    │       ├── extract.rs           # 纯函数提取层：HTML → 元数据/图片声明（可离线测试）
    │       ├── pipeline.rs          # 网络富化层：manifest 抓取 + 图片 probe/下载/上传
    │       ├── cache.rs             # URL 规范化缓存
    │       └── oss.rs               # 阿里云 OSS 上传客户端
```

## 快速开始

### 前置条件

- Rust 1.88+（`rustup` 安装；Docker 镜像基于 `cargo-chef:latest-rust-1.88`）
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
OSS_KEY_PREFIX=scrapper \
./target/release/scraper-service
```

服务默认监听 `0.0.0.0:3000`。

## 环境变量

| 变量名 | 必填 | 默认值 | 说明 |
|---|---|---|---|
| `PORT` | | `3000` | HTTP 监听端口 |
| `REQUEST_TIMEOUT_SECS` | | `10` | Layer 1 HTTP 请求超时（秒） |
| `HEADLESS_TIMEOUT_SECS` | | `30` | Layer 2 无头浏览器整体超时（秒） |
| `HEADLESS_IDLE_WAIT_SECS` | | `10` | Layer 2 网络空闲等待时间（秒），用于等待 JS 渲染完成；须小于 `HEADLESS_TIMEOUT_SECS` |
| `CACHE_TTL_SECS` | | `3600` | 缓存条目存活时间（秒） |
| `PROXY_URL` | | — | HTTP 代理地址，例如 `http://127.0.0.1:7890`，不设则直连 |
| `SSRF_ALLOW_PRIVATE` | | — | 设为 `1` 关闭 SSRF 防护，允许访问私有 / 回环地址；不设则默认拦截 |
| `SCRAPER_AUTH_TOKEN` | | — | 设置后 `/scrape`、`/ping` 要求 `Authorization: Bearer <token>`；不设则不鉴权 |
| `MAX_CONCURRENT_REQUESTS` | | `32` | `/scrape` + `/ping` 最大并发数，超出返回 `503` |
| `OSS_ACCESS_KEY_ID` | | — | 阿里云 Access Key ID，前四个 OSS_* 变量须同时配置才生效 |
| `OSS_ACCESS_KEY_SECRET` | | — | 阿里云 Access Key Secret |
| `OSS_BUCKET` | | — | OSS Bucket 名称 |
| `OSS_ENDPOINT` | | — | OSS 地域 Endpoint，例如 `oss-cn-hangzhou.aliyuncs.com` |
| `OSS_KEY_PREFIX` | | `scrapper` | 对象 key 的统一前缀。桶上的 `PutObject` 授权与生命周期规则都按它书写，改这里必须同步改桶策略 |
| `RUST_LOG` | | `info` | 日志过滤器，支持 `debug`、`info`、`warn` 等 |

## API 文档

服务对外暴露四个端点：`GET /health`、`GET /metrics`、`POST /scrape`、`POST /ping`。
`/scrape`、`/ping` 在设置了 `SCRAPER_AUTH_TOKEN` 时需要鉴权，否则四个端点均无需鉴权。

完整的接口说明（请求 / 响应字段、错误码、缓存语义、调用示例）见 **[api.md](./api.md)**。

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
| OSS 上传 | `hmac` / `sha1` / `httpdate` | 手写阿里云 OSS V1 签名，直接用上面的 `reqwest` 发出 PUT，不依赖第三方 OSS SDK |
| 限流保护 | `tower`（`limit` / `load-shed`） | `/scrape`、`/ping` 并发上限 + 过载快速失败 |
| 指标 | `axum-prometheus` | `GET /metrics` 输出 Prometheus 格式指标 |
| 序列化 | `serde / serde_json` | JSON 请求/响应 |
| 日志 | `tracing / tracing-subscriber` | 结构化日志，支持 `RUST_LOG` |
