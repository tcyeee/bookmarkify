---
tags:
  - bookmarkify
created: 2026-03-25 22:39
---

# Bookmarkify Scraper — 项目规划

> 替换 metascraper 的独立网页解析服务，基于 Rust + headless Chrome，解决反爬虫、截图、性能三大问题。

---

## 目标

构建一个独立的 HTTP 服务，作为 bookmarkify 的网页元数据解析后端，具备以下能力：

- 提取网页元数据（标题、描述、封面图、favicon 等）
- 绕过常见反爬虫机制（花瓣、知乎等）
- 对网页截图，结果上传至对象存储
- 对外暴露 REST API，支持限流与认证

---

## 架构

```
                ┌──────────────────────────────────┐
                │      模块 A — API 网关层 (Rust)    │
  HTTP 请求 ──▶ │                                    │
                │  Layer 1: reqwest + scraper        │  ← 处理 ~80% 网站
                │  (OG / Twitter Card / JSON-LD)     │
                │                                    │
                │  Layer 2: 转发给模块 B              │  ← Layer 1 失败时触发
                └──────────────┬─────────────────────┘
                               │ HTTP
                ┌──────────────▼─────────────────────┐
                │      模块 B — headless 渲染层        │  ← 独立进程，可按需启停
                │      (Rust + spider-rs)             │
                └────────────────────────────────────┘
```

### 模块 A — API 网关层

- 框架：`axum`
- 解析：`reqwest` + `scraper`，提取 OG / Twitter Card / JSON-LD
- 限流：`tower-governor`
- 缓存：`moka`（内存）或 Redis（分布式）
- 认证：API Key 校验

### 模块 B — headless 渲染层

- 核心：`spider-rs`（封装 CDP + stealth 策略）
- 功能：JS 渲染、反爬绕过、截图生成
- 截图存储：OSS / S3
- 对模块 A 暴露内部 HTTP 接口

---

## 里程碑

### M0 — 验证可行性（1–2 天）

- [ ] 用 spider-rs 跑 demo：单 URL 抓取 + 截图
- [ ] 验证花瓣、知乎等目标站点能否正常渲染
- [ ] 确认 Chrome 在目标云服务器环境可运行（sandbox 问题）

### M1 — 模块 A 基础（3–4 天）

- [ ] axum 框架搭建，健康检查接口
- [ ] API Key 认证中间件
- [ ] `POST /scrape` 接口：接收 URL，返回元数据 JSON
- [ ] Layer 1 解析：OG tags、Twitter Card、JSON-LD、`<title>`

### M2 — 模块 B 基础（3–4 天）

- [ ] spider-rs 封装为单 URL 同步解析服务
- [ ] 暴露内部 HTTP 接口（供模块 A 调用）
- [ ] 截图功能集成，结果上传至 OSS/S3
- [ ] Chrome 实例生命周期管理（防内存泄漏）

### M3 — 横切功能（2–3 天）

- [ ] 限流（tower-governor）
- [ ] 缓存（相同 URL 短期内不重复抓取）
- [ ] 模块 A → 模块 B fallback 路由逻辑

### M4 — 部署（2–3 天）

- [ ] Dockerfile（模块 A、模块 B 分别打包）
- [ ] docker-compose（本地联调）
- [ ] 云服务器部署验证
- [ ] 接入 bookmarkify，替换 metascraper

---

## API 设计（草稿）

```
POST /scrape
Authorization: Bearer <api-key>

{
  "url": "https://example.com",
  "screenshot": true   // 可选，默认 false
}

→ 200 OK
{
  "title": "...",
  "description": "...",
  "image": "https://...",
  "favicon": "https://...",
  "screenshot_url": "https://oss.../xxx.png",  // screenshot=true 时返回
  "source": "og" | "twitter_card" | "json_ld" | "headless"
}
```

---

## 技术选型汇总

| 职责 | 方案 |
|------|------|
| HTTP 框架 | axum |
| 轻量 HTTP 请求 | reqwest |
| HTML 解析 | scraper |
| headless 浏览器 | spider-rs（CDP） |
| 限流 | tower-governor |
| 缓存 | moka / Redis |
| 截图存储 | 阿里云 OSS / AWS S3 |
| 部署 | Docker + 云服务器 |

---

## 风险备忘

| 风险 | 等级 | 应对 |
|------|------|------|
| Chrome 并发管理 | 高 | M0 阶段确定并发上限和超时策略 |
| VPS 沙箱限制 | 中 | 提前在目标服务器验证（`--no-sandbox`） |
| spider-rs 改造成本 | 中 | M0 先跑 demo，摸清 Browser 生命周期再集成 |
| Chrome 内存泄漏 | 中 | 实现实例定期回收/重启机制 |
| 截图存储对接 | 低 | 提前创建 bucket，接入成本不高 |
| 反爬策略维护 | 低 | 接受为长期维护项 |

---

## 成本弹性部署模式

| 预算模式 | 部署方式 | 能力 |
|----------|----------|------|
| 最省钱 | 只跑模块 A | 普通网站元数据提取，无截图，无反爬 |
| 标准 | 模块 A + 模块 B 同机 | 全功能，单机部署 |
| 高流量 | 模块 A 多实例 + 模块 B 独立扩容 | 弹性伸缩 |

---

## 当前状态

- **阶段**：M0（验证可行性）
- **下一步**：用 spider-rs 跑第一个 demo，抓取花瓣或知乎的一个 URL，验证截图可行性
