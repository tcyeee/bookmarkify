# bookmarkify-scrapper

Bookmarkify 的独立网页元数据解析服务，基于 Rust，解决反爬虫、截图、性能三大问题，替换 bookmarkify-api 内的 Jsoup 方案。

---

## 架构

```
                ┌──────────────────────────────────┐
                │      Module A — API 网关层 (Rust)  │
  HTTP 请求 ──▶ │                                    │
                │  Layer 1: reqwest + scraper        │  ← 处理 ~80% 网站
                │  (OG / Twitter Card / JSON-LD)     │
                │                                    │
                │  Layer 2: 转发给 Module B           │  ← Layer 1 失败时触发
                └──────────────┬─────────────────────┘
                               │ HTTP
                ┌──────────────▼─────────────────────┐
                │      Module B — headless 渲染层      │
                │      (Rust + spider-rs)             │
                └────────────────────────────────────┘
```

- **Module A**：axum HTTP 服务，轻量 reqwest + scraper 解析，API Key 认证，限流缓存
- **Module B**：spider-rs 封装的 headless Chrome，处理 JS 渲染、反爬绕过、截图

---

## 项目结构

```
bookmarkify-scrapper/
├── Cargo.toml                  # workspace root
├── crates/
│   └── scraper-demo/           # M0 验证 binary（spider-rs 可行性验证）
│       └── src/main.rs
├── docs/
│   ├── m0-results.md           # M0 验证结论
│   └── superpowers/
│       ├── specs/              # 设计文档
│       └── plans/              # 实现计划
└── screenshots/                # 截图输出目录（gitignored）
```

---

## 当前状态

| 里程碑 | 状态 | 说明 |
|--------|------|------|
| M0 — 可行性验证 | ✅ PARTIAL PASS | 花瓣绕过成功；知乎不稳定；截图 API 待调研 |
| M1 — Module A 基础 | 🔜 待开始 | axum + Layer 1 解析 |
| M2 — Module B 基础 | 🔜 待开始 | spider-rs 封装 + 截图 |
| M3 — 横切功能 | 🔜 待开始 | 限流、缓存、fallback |
| M4 — 部署 | 🔜 待开始 | Docker + 云服务器 |

---

## 快速开始

**前置要求**

- Rust 1.90+
- Google Chrome（用于 headless 模式）

**运行 M0 demo**

```bash
CHROME="/Applications/Google Chrome.app/Contents/MacOS/Google Chrome" cargo run -p scraper-demo
```

输出：提取花瓣、知乎的 title / description / og:image，截图保存到 `screenshots/`。

---

## 技术选型

| 职责 | 方案 |
|------|------|
| HTTP 框架 | axum |
| 轻量 HTTP 请求 | reqwest |
| HTML 解析 | scraper |
| headless 浏览器 | spider-rs v2（CDP + stealth） |
| 限流 | tower-governor |
| 缓存 | moka / Redis |
| 截图存储 | 阿里云 OSS / AWS S3 |
| 部署 | Docker + 云服务器 |

---

## 部署模式

| 预算 | 部署方式 | 能力 |
|------|----------|------|
| 最省 | 仅 Module A | 普通站元数据，无截图，无反爬 |
| 标准 | Module A + B 同机 | 全功能单机 |
| 高流量 | Module A 多实例 + Module B 独立扩容 | 弹性伸缩 |
