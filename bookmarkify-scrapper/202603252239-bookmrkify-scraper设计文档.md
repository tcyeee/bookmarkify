
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

单一 Rust 二进制，两层解析逻辑在同一进程内以函数调用衔接：

```
                ┌─────────────────────────────────────────┐
                │          单一 Rust 服务                   │
  HTTP 请求 ──▶ │                                           │
                │  Layer 1: reqwest + scraper              │  ← 处理 ~80% 网站
                │  (OG / Twitter Card / JSON-LD)           │
                │              │ 函数调用（失败时 fallback）  │
                │  Layer 2: spider-rs (headless Chrome)    │  ← 处理剩余网站 + 截图
                └─────────────────────────────────────────┘
```

### Layer 1 — 轻量解析

- 框架：`axum`
- 解析：`reqwest` + `scraper`，提取 OG / Twitter Card / JSON-LD
- 限流：`tower-governor`
- 缓存：`moka`（内存）
- 认证：API Key 校验

### Layer 2 — headless 渲染

- 核心：`spider-rs`（封装 CDP + stealth 策略）
- 功能：JS 渲染、反爬绕过、截图生成
- 截图存储：OSS / S3
- 与 Layer 1 在同一进程内，通过 async 函数调用衔接，无 HTTP 开销

---

## 里程碑

### M0 — 验证可行性（1–2 天）✅ PARTIAL PASS

- [x] 用 spider-rs 跑 demo：单 URL 抓取 + 截图
- [x] 验证花瓣、知乎等目标站点能否正常渲染
- [ ] 确认 Chrome 在目标云服务器环境可运行（sandbox 问题）

**结论（2026-04-18）：** 花瓣元数据绕过成功（title/description/og:image 均正确）；知乎 stealth 策略不稳定，有时返回验证页或空页面；spider-rs `screenshot_bytes` 未生效，截图功能待 M2 专项解决。截图与知乎问题不阻塞 M1，M1 正常推进。详见 [docs/m0-results.md](docs/m0-results.md)。

### M1 — 模块 A 基础（3–4 天）

- [ ] axum 框架搭建，健康检查接口
- [ ] API Key 认证中间件
- [ ] `POST /scrape` 接口：接收 URL，返回元数据 JSON
- [ ] Layer 1 解析：OG tags、Twitter Card、JSON-LD、`<title>`

### M2 — Layer 2 基础（3–4 天）

- [ ] spider-rs 封装为 async 函数，与 Layer 1 在同一进程内调用
- [ ] 截图功能集成，结果上传至 OSS/S3
- [ ] Chrome 实例生命周期管理（防内存泄漏）

### M3 — 横切功能（2–3 天）

- [ ] 限流（tower-governor）
- [ ] 缓存（相同 URL 短期内不重复抓取）
- [ ] 模块 A → 模块 B fallback 路由逻辑

### M4 — 部署（2–3 天）

- [ ] 单一 Dockerfile（包含 Chrome + Rust 二进制）
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

| 职责            | 方案                |
| --------------- | ------------------- |
| HTTP 框架       | axum                |
| 轻量 HTTP 请求  | reqwest             |
| HTML 解析       | scraper             |
| headless 浏览器 | spider-rs（CDP）    |
| 限流            | tower-governor      |
| 缓存            | moka / Redis        |
| 截图存储        | 阿里云 OSS / AWS S3 |
| 部署            | Docker + 云服务器   |

---

## 风险备忘

| 风险               | 等级 | 应对                                      |
| ------------------ | ---- | ----------------------------------------- |
| Chrome 并发管理    | 高   | M0 阶段确定并发上限和超时策略             |
| VPS 沙箱限制       | 中   | 提前在目标服务器验证（`--no-sandbox`）    |
| spider-rs 改造成本 | 中   | M0 先跑 demo，摸清 Browser 生命周期再集成 |
| Chrome 内存泄漏    | 中   | 实现实例定期回收/重启机制                 |
| 截图存储对接       | 低   | 提前创建 bucket，接入成本不高             |
| 反爬策略维护       | 低   | 接受为长期维护项                          |

---

## 成本弹性部署模式

| 预算模式 | 部署方式                       | 能力                                 |
| -------- | ------------------------------ | ------------------------------------ |
| 最省钱   | 启动时禁用 Layer 2（env flag） | 普通网站元数据提取，无截图，无反爬   |
| 标准     | 单实例全功能启动               | 全功能，单机部署                     |
| 高流量   | 多实例横向扩容                 | 弹性伸缩（Layer 1 + Layer 2 一起扩） |

---

## 当前状态

- **阶段**：M1（模块 A 基础）
- **下一步**：搭建 axum 服务框架，实现健康检查、API Key 认证与 `POST /scrape` 接口（Layer 1 轻量解析）
