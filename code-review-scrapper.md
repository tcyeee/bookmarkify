# Code Review Report — bookmarkify-scrapper

**日期**：2026-05-27  
**审查范围**：`bookmarkify-scrapper/` 目录（相对于 `main` 分支的全量变更）  
**审查方法**：3 角度并行扫描（逐行差异、移除行为审计、跨文件调用链追踪）→ 独立核实 → 按严重程度排序，保留 ≤10 条  
**结论**：发现 **7 条已确认（CONFIRMED）**、**3 条合理可信（PLAUSIBLE）** 问题，其中含 2 个高危 SSRF 漏洞。

---

## 摘要

| # | 严重程度 | 文件 | 核实结论 | 一句话描述 |
|---|---|---|---|---|
| 1 | 🔴 严重 | `oss.rs:183` | CONFIRMED | favicon URL SSRF + 响应体外泄 |
| 2 | 🔴 高危 | `oss.rs:222` | CONFIRMED | OG image / logo URL 盲 SSRF |
| 3 | 🔴 高危 | `main.rs:178` | CONFIRMED | 非 OSS 路径 favicon SSRF |
| 4 | 🔴 高危 | `scraper.rs:141` | CONFIRMED | DNS 重绑定绕过 SSRF 防护（TOCTOU） |
| 5 | 🟠 中高危 | `oss.rs:239` / `main.rs:258` | CONFIRMED | 图片下载失败导致成功抓取返回 503 |
| 6 | 🟠 中危 | `oss.rs:95` | CONFIRMED | OSS 上传无超时，可阻塞请求处理器 |
| 7 | 🟡 中危 | `headless.rs:38` | CONFIRMED | 锁等待与 Chrome 执行各占一个完整超时预算 |
| 8 | 🟡 中危 | `oss.rs:111` | PLAUSIBLE | OSS 错误响应检测过窄，可能误判为成功 |
| 9 | 🟡 低中危 | `main.rs:183` | PLAUSIBLE | Content-Type 未清理，注入 data URI |
| 10 | 🟡 低危 | `main.rs:253` | PLAUSIBLE | 失败结果不入缓存，重复请求独占 Chrome 锁 |

---

## 详细说明

### #1 🔴 `oss.rs:183` — favicon SSRF + 响应体数据外泄（CONFIRMED）

**位置**：`crates/scraper-service/src/oss.rs` → `fetch_as_base64()`

**问题描述**

`fetch_as_base64` 用于在 OSS 启用时下载 favicon 并以 base64 data URL 形式返回。该函数直接对抓取页面提供的 favicon URL 发起 HTTP GET，既不调用 `validate_target_host()`，也不对响应的 `Content-Type` 进行过滤。任意响应内容都会被 base64 编码后放入 API 响应的 `favicon` 字段。

```rust
// oss.rs:183（问题路径）
let response = http
    .get(url)
    .header("Referer", &referer)
    .send()
    .await  // ← 无 SSRF 校验，url 来自被抓取页面
    ...;

let bytes = read_body_capped(response, MAX_FAVICON_BYTES).await...;
// ↑ 无 Content-Type 检查，任何响应体都被编码返回
Ok(Some(format!("data:{content_type};base64,{b64}")))
```

**攻击场景**

攻击者控制一个被抓取的页面，在其中嵌入：

```html
<link rel="icon" href="http://169.254.169.254/latest/meta-data/iam/security-credentials/role">
```

服务器在 OSS 启用时调用 `fetch_as_base64`，向 AWS 元数据端点发出 GET 请求，将返回的 IAM 凭据 JSON 原文 base64 编码后通过 `favicon` 字段透传给调用方（bookmarkify-api）。这是**完整的响应体数据外泄型 SSRF**，不同于盲 SSRF。

**修复建议**

在 `fetch_as_base64` 中，获取响应前先调用 `validate_target_host()`；同时增加 Content-Type 检查（仅允许 `image/*`）。

```rust
let parsed = reqwest::Url::parse(url).map_err(...)?;
validate_target_host(&parsed).await?;
// ...
if !content_type.starts_with("image/") {
    return Ok(None); // 非图片内容静默忽略
}
```

---

### #2 🔴 `oss.rs:222` — OG image / logo URL 盲 SSRF（CONFIRMED）

**位置**：`crates/scraper-service/src/oss.rs` → `upload_url_asset()`

**问题描述**

`upload_url_asset` 下载 OG image 和 logo URL（均来自被抓取页面的 HTML 元数据）并上传至 OSS。函数在发出 HTTP GET **之后**才检查 `Content-Type`，意味着对内部地址的实际 HTTP 连接已经建立并完成。

```rust
// oss.rs:222
let response = http
    .get(url)           // ← 已向内部地址发出请求
    .header("Referer", &referer)
    .send()
    .await
    ...;

let content_type = ...;
if !content_type.starts_with("image/") {  // ← 此时请求已完成
    return Err(ScrapeError::OssFailed(...));
}
```

**攻击场景**

```html
<meta property="og:image" content="http://169.254.169.254/latest/meta-data/">
```

服务会向元数据端点发 GET，虽然最终因 Content-Type 不是 `image/*` 而返回 OssFailed，但 HTTP 连接已建立。攻击者可通过 API 响应（成功 vs OssFailed）判断内部端点的可达性，实现盲 SSRF 探测。

**修复建议**

与 #1 相同：在 `upload_url_asset` 函数入口处对 `url` 调用 `validate_target_host()`。

---

### #3 🔴 `main.rs:178` — 非 OSS 路径 favicon SSRF（CONFIRMED）

**位置**：`crates/scraper-service/src/main.rs` → `favicon_to_base64()`

**问题描述**

当 OSS 未配置时（开发/测试环境的常见配置），`favicon_to_base64` 承担相同职责，同样缺少 SSRF 校验。

```rust
// main.rs:178
let response = http.get(url).header("Referer", &referer).send().await.ok()?...;
// ↑ 无 validate_target_host() 调用
```

本函数与 #1 是同一漏洞在不同代码路径的复现。两条路径需要同步修复，否则 OSS 关闭时安全防护退化。

---

### #4 🔴 `scraper.rs:141` — DNS 重绑定绕过 SSRF（TOCTOU）（CONFIRMED）

**位置**：`crates/scraper-service/src/scraper.rs` → `validate_target_host()`

**问题描述**

`validate_target_host` 在"校验时"单独解析一次域名并检查 IP，校验通过后，`reqwest` 在"连接时"**再次**独立发起 DNS 查询。两次查询之间存在时间窗口，可被 DNS 重绑定利用。

```
validate_target_host()
  └─ tokio::net::lookup_host("attacker.com:443")  → 1.2.3.4 (公网，通过)
  └─ 返回 Ok

client.get(url).send()
  └─ reqwest 内部再次解析 "attacker.com"          → 169.254.169.254 (DNS 已切换，内网)
  └─ TCP 连接到 169.254.169.254
```

`reqwest` 使用独立的 DNS 解析器，不复用 `validate_target_host` 的查询结果，TOCTOU 窗口确实存在。该漏洞同时影响 Layer 1（`scraper::scrape`）和 Layer 2（`scrape_headless`）。

**修复建议**

使用自定义 DNS 解析器（`TrustDNS` / `hickory`），在解析时缓存并绑定 IP，使 reqwest 的实际连接复用预校验的解析结果：

```rust
let addrs: Vec<SocketAddr> = lookup_and_validate(host, port).await?;
// 使用 client.get(url).resolve_to_addrs(host, &addrs) 强制绑定
```

---

### #5 🟠 `oss.rs:239` / `main.rs:258` — 图片下载失败导致成功抓取返回 503（CONFIRMED）

**位置**：`oss.rs:239`（Content-Type 检查）→ `main.rs:258`（错误传播）

**问题描述**

当 og:image 或 logo URL 受到防盗链保护，返回 HTTP 200 + `text/html` 时，`upload_url_asset` 抛出 `OssFailed`，`upload_assets` 通过 `?` 传播，`main.rs` 将其作为整体失败处理，返回 HTTP 503。

```rust
// oss.rs:239
if !content_type.starts_with("image/") {
    return Err(ScrapeError::OssFailed(...));  // ← 阻止整个请求
}

// main.rs:260-274
Err(e) => {
    return (StatusCode::SERVICE_UNAVAILABLE, ...).into_response();
    // ↑ 丢弃已成功抓取的 title、description 等元数据
}
```

**攻击场景**（非恶意，是真实生产问题）

页面标题、描述均已成功提取，但 og:image CDN 防盗链生效返回 HTML 页面 → 触发 `OssFailed` → bookmarkify-api 收到 503 → 书签永久停在 `BOOKMARK_LOADING` 状态 → WebSocket `HOME_ITEM_UPDATE` 推送永远不会到达。

**修复建议**

对资产下载失败降级处理（保留原始 URL 或置为 `null`），而非传播为致命错误：

```rust
result.image = match image_result {
    Ok(v) => v,
    Err(e) => {
        tracing::warn!("image upload failed: {e:?}, using original URL");
        image_url // 回退到原始 URL
    }
};
```

---

### #6 🟠 `oss.rs:95` — OSS 上传无超时（CONFIRMED）

**位置**：`crates/scraper-service/src/oss.rs` → `upload_bytes_once()`

**问题描述**

`oss-rust-sdk` 内部调用 `reqwest::Client::new()`（无超时配置），`upload_bytes_once` 也未包裹 `tokio::time::timeout`。`upload_bytes` 的重试循环（3 次，退避 200/400ms）仅限于应用层错误，对底层 TCP 阻塞无效。

```rust
fn oss(&self) -> oss_rust_sdk::oss::OSS<'_> {
    // NOTE: oss-rust-sdk creates its own reqwest::Client internally
    // ← 该 Client 无超时设置
    oss_rust_sdk::oss::OSS::new(...)
}
```

若 OSS 端点在建立 TCP 连接后停止响应，`upload_bytes_once` 将永远等待，占用 axum worker 线程直至 OS TCP keepalive 超时（通常数分钟）。

**修复建议**

为整个 `upload_assets` 调用包裹超时：

```rust
tokio::time::timeout(
    Duration::from_secs(OSS_UPLOAD_TIMEOUT_SECS),
    self.upload_bytes(key, bytes, content_type),
)
.await
.map_err(|_| ScrapeError::OssFailed("OSS upload timeout".to_string()))?
```

或向 oss-rust-sdk 提 issue / 自行构建带超时的 Client。

---

### #7 🟡 `headless.rs:38` — 锁等待与 Chrome 执行各占完整超时预算（CONFIRMED）

**位置**：`crates/scraper-service/src/headless.rs` → `scrape_headless()`

**问题描述**

`HEADLESS_LOCK` 的等待超时和 Chrome 执行超时使用同一个 `timeout_secs`，且两个计时器**独立启动**：

```rust
// 第一个 timeout_secs：等待锁
let _guard = tokio::time::timeout(
    Duration::from_secs(timeout_secs),   // ← 最多等 30s
    HEADLESS_LOCK.lock(),
).await.map_err(|_| ScrapeError::Timeout)?;

// 第二个 timeout_secs：运行 Chrome
let pages = tokio::time::timeout(
    Duration::from_secs(timeout_secs),   // ← 又是 30s，独立计时
    async move { website.scrape().await; ... }
).await.map_err(|_| ScrapeError::Timeout)?;
```

最差情况：排队请求等待锁 29.9s，获取锁后 Chrome 再运行 30s，共 ~60s 才返回 `Timeout`。上游调用方（bookmarkify-api）早已超时断开连接。

代码在 `main.rs:64` 已对 `idle_wait_secs >= timeout_secs` 发出警告，但未考虑这个复合延迟问题。

**修复建议**

将两个阶段共享同一个"剩余预算"计时：

```rust
let deadline = tokio::time::Instant::now() + Duration::from_secs(timeout_secs);

let _guard = tokio::time::timeout_at(deadline, HEADLESS_LOCK.lock())
    .await.map_err(|_| ScrapeError::Timeout)?;

let remaining = deadline.saturating_duration_since(tokio::time::Instant::now());
let pages = tokio::time::timeout(remaining, async move { ... })
    .await.map_err(|_| ScrapeError::Timeout)?;
```

---

### #8 🟡 `oss.rs:111` — OSS 错误响应检测过窄（PLAUSIBLE）

**位置**：`crates/scraper-service/src/oss.rs` → `upload_bytes_once()`

**问题描述**

成功判断逻辑依赖"空 body = 成功，body 含 `<Error>` 或 `<Code>` = 失败"。

```rust
if !response_body.is_empty() {
    let body_str = String::from_utf8_lossy(&response_body);
    if body_str.contains("<Error>") || body_str.contains("<Code>") {
        return Err(...);
    }
}
Ok(format!("{}/{}", self.base_url, key))  // ← 其余情况都视为成功
```

若 OSS 返回非标准 XML（如纯文本 `Access Denied`、`<ErrorResponse>` 根元素等），或 CDN 层的错误页面（HTML 格式），函数将返回一个"幽灵 URL"——该 URL 指向一个从未写入的 OSS 对象。此 URL 随后被缓存并持续返回给所有调用方直至 TTL 过期。

---

### #9 🟡 `main.rs:183` — Content-Type 未清理注入 data URI（PLAUSIBLE）

**位置**：`crates/scraper-service/src/main.rs` → `favicon_to_base64()`  
同见：`crates/scraper-service/src/oss.rs` → `fetch_as_base64()`

**问题描述**

`HeaderValue::to_str()` 允许水平制表符（`\t`，ASCII 0x09），但会拒绝其他控制字符。若 favicon 服务器返回 `Content-Type: image/png\t`，最终生成：

```
data:image/png	;base64,<bytes>
```

该值被写入缓存，随后在 TTL 时间内对所有命中缓存的请求返回。下游解析 MIME 类型的代码（日志、管理界面、内容嗅探）将接收到含攻击者控制字符的字符串。

---

### #10 🟡 `main.rs:253` — 失败结果不入缓存，Chrome 锁被重复独占（PLAUSIBLE）

**位置**：`crates/scraper-service/src/main.rs` → `scrape_handler()`

**问题描述**

缓存只在 `Ok(r)` 分支写入，所有失败（`Timeout`、`FetchFailed`、`HeadlessFailed`）均不缓存：

```rust
match result {
    Ok(r) => {
        state.cache.set(&body.url, ...).await;  // ← 仅成功写缓存
        ...
    }
    Err(...) => { /* 直接返回错误，不写缓存 */ }
}
```

对于"Layer 1 返回 HTTP 200 但无 `<title>`（需要 JS 渲染）"的 URL，每次请求都会：

1. Layer 1 抓取成功但 `title == None`
2. 自动回退 Layer 2
3. 等待 `HEADLESS_LOCK`（最多 30s）
4. 运行 Chrome（最多 30s）
5. 失败 → 不写缓存

若调用方（或 bookmarkify-api 重试逻辑）频繁请求此类 URL，`HEADLESS_LOCK` 队列被持续占据，阻塞其他所有合法的 headless 请求。

---

## SSRF 防护现状总结

当前 SSRF 防护仅覆盖**主 URL**（用户直接提交的抓取目标），次级 URL（从页面 HTML 中提取的资源）全部暴露：

| 调用路径 | SSRF 校验 | 说明 |
|---|---|---|
| `scraper::scrape()` 主 URL | ✅ | `validate_target_host()` |
| `headless::scrape_headless()` 主 URL | ✅ | `validate_target_host()` |
| `oss::upload_url_asset()` OG image / logo | ❌ | 无校验 |
| `oss::fetch_as_base64()` favicon（OSS 路径） | ❌ | 无校验 + 无 Content-Type 过滤 |
| `main::favicon_to_base64()` favicon（非 OSS 路径） | ❌ | 无校验 |

此外，`validate_target_host` 自身存在 DNS 重绑定 TOCTOU 问题（#4），使主 URL 的防护也不完整。

---

## 修复优先级

| 优先级 | 问题 | 所需改动 |
|---|---|---|
| P0（立即） | #1 #2 #3：次级 URL SSRF | 在三个函数入口各加一次 `validate_target_host()` |
| P0（立即） | #4：DNS 重绑定 | 使用固定 DNS 解析结果或禁用跟随重定向 + 重新校验 |
| P1（本周） | #5：503 on success | 资产下载失败降级处理，不传播为致命错误 |
| P1（本周） | #6：OSS 无超时 | 给 `upload_bytes_once` 包裹 `tokio::time::timeout` |
| P2（近期） | #7：双重超时预算 | 改用共享 deadline 而非两个独立计时器 |
| P2（近期） | #8：OSS 误判成功 | 扩展 XML 错误检测模式，或改用 HTTP 状态码判断 |
| P3（排期） | #9：Content-Type 注入 | 对 header 值做 ASCII 可见字符过滤后再插入格式串 |
| P3（排期） | #10：缓存未覆盖失败 | 对短暂性失败写入短 TTL 的负缓存条目 |
