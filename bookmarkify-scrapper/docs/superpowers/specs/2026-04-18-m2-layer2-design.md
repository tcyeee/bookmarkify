# M2 — Layer 2 基础设计文档

**日期:** 2026-04-18  
**状态:** 已批准

---

## 目标

在 `scraper-service` 进程内封装 spider-rs 为可调用的 async 函数，实现 Chrome 实例生命周期管理，并修复 M0 遗留的截图功能（screenshot_bytes 未返回问题）。

---

## 范围

- 新增 `headless.rs` 模块，封装 Layer 2 headless 元数据抓取
- `POST /scrape` 新增可选 `headless: true` 参数，直接路由到 Layer 2
- Chrome 全局单例，进程内复用，崩溃后自动重建
- 系统性排查截图 API，使 `screenshot_bytes` 正确返回
- **不含**：OSS 上传（M4）、自动 fallback 路由（M3）

---

## 模块结构

```
crates/scraper-service/src/
├── main.rs          # 已有：axum 路由、AppState
├── scraper.rs       # 已有：Layer 1 reqwest 解析
└── headless.rs      # 新增：Layer 2 spider-rs 封装
```

`scraper-service/Cargo.toml` 新增：

```toml
spider = { version = "2", features = ["chrome", "chrome_stealth"] }
```

---

## Chrome 生命周期管理

用 `tokio::sync::RwLock<Option<Arc<HeadlessBrowser>>>` 持有全局单例，允许崩溃后重建：

```rust
static BROWSER: Lazy<RwLock<Option<Arc<HeadlessBrowser>>>> =
    Lazy::new(|| RwLock::new(None));
```

每次调用 `scrape_headless` 前：
1. 读锁检查单例是否存在且存活
2. 若不存在或已崩溃，写锁重建实例
3. 用 `Mutex` 串行化 headless 任务（单实例不支持并发 CDP 操作）

---

## 对外接口

### Rust 函数

```rust
pub async fn scrape_headless(url: &str) -> Result<ScrapeResult, ScrapeError>
```

返回与 Layer 1 相同的 `ScrapeResult`，`source` 字段值为 `"headless"`。

`ScrapeResult` 新增字段：

```rust
pub screenshot_bytes: Option<Vec<u8>>,
```

Layer 1 的 `scrape()` 返回时该字段始终为 `None`。

### HTTP 接口

`POST /scrape` 请求体新增可选字段：

```json
{ "url": "https://example.com", "headless": true }
```

- `headless` 缺省 `false`，走 Layer 1
- `headless: true` 跳过 Layer 1，直接调 `scrape_headless`

---

## 错误处理

`ScrapeError` 新增变体：

```rust
HeadlessFailed(String),  // Chrome 启动或渲染失败
```

| 错误 | HTTP 状态码 |
|------|------------|
| `HeadlessFailed` | 502 Bad Gateway |
| `Timeout`（headless） | 504 Gateway Timeout |

headless 超时默认 30s，通过 env var `HEADLESS_TIMEOUT_SECS` 配置。

---

## 截图调试策略

M0 问题：`page.screenshot_bytes` 始终为 `None`。

排查步骤：
1. 先用 `save: true` 验证 Chrome 截图基本可用（写磁盘）
2. 若可用，切回 `bytes` 模式，核查 spider-rs v2 正确的 `ScreenShotConfig` 参数
3. 若 spider-rs 截图 API 无效，fallback 到 CDP `Page.captureScreenshot` 直接调用

M2 交付物：`screenshot_bytes` 能正确返回 PNG 字节（> 10KB）；OSS 上传留 M4。

---

## 测试策略

- **单元测试**：不测 headless（依赖真实 Chrome），`scraper.rs` 现有测试保持不变
- **集成测试**：新增 `tests/headless_integration.rs`，用 `#[ignore]` 标记，手动运行
  - 验证花瓣元数据：title 非空、source 为 `"headless"`
  - 验证截图：`screenshot_bytes` 非 `None` 且长度 > 10KB

---

## 交付标准

- [ ] `cargo build` 通过（含 spider 依赖）
- [ ] `POST /scrape` 带 `"headless": true` 返回花瓣正确元数据
- [ ] `screenshot_bytes` 返回非空 PNG 字节
- [ ] Chrome 崩溃后下一次请求能自动重建实例（手动 kill chrome 验证）
