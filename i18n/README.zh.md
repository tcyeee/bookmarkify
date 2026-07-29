![](../assets/banner.png)

<div align="center">中文 ｜ <a href="../README.md">English</a></div>

# Bookmarkify

Bookmarkify 是一个现代化的书签管理平台，帮助用户轻松保存、组织、分享和探索优质网页书签。通过简洁直观的界面和强大的功能，让书签管理变得简单高效。

## ✨ 核心功能

- **📚 书签管理** - 快速保存和管理你的网页书签，支持分类和标签
- **🔗 智能解析** - 由独立的抓取服务提取标题、描述、图标与社交分享图；纯 JS 渲染的页面自动回退到无头浏览器
- **🎨 自适应图标** - 网站声明的每一张图都会连同其出处一并记录，从而按展示形态挑出最合适的那张；网站没有够格的图时则渲染首字母色块
- **👥 分享协作** - 与朋友分享书签，或公开发布让更多人发现
- **🔥 热门探索** - 发现社区中最近流行的优质书签
- **🌐 实时同步** - 基于 WebSocket 的实时数据同步，多设备无缝体验
- **📱 响应式设计** - 完美适配桌面端和移动端

## 🏗️ 架构

由四个服务协同组成：

| 服务 | 职责 | 端口 | 技术栈 |
|---|---|---|---|
| `bookmarkify-api` | REST API + WebSocket 服务 | 8001 | Kotlin + Spring Boot |
| `bookmarkify-web` | 用户前台 | 3000 | Nuxt + Vue 3 |
| `bookmarkify-scrapper` | 网页抓取微服务 | 3000 | Rust (axum + spider-rs) |
| `bookmarkify-admin` | 管理后台 | 5777 | Vue 3 + Vite (Vben Admin) |

```
浏览器 ───────────────────────────────────────────────────┐
  │                                                       │
  ▼ HTTP/WS                                               │ 管理后台
bookmarkify-web ──── REST+WS ────► bookmarkify-api ◄──────┘
                                    │
                                    ├── PostgreSQL
                                    ├── Redis
                                    └── POST /scrape ──► bookmarkify-scrapper
```

抓取服务是一个**与业务解耦的通用服务**：它只报告网页**声明了什么**（每张图来自哪个标签、每个元数据字段的出处），不对这些事实作任何业务解释。「哪张图算 LOGO」「什么形态下该渲染哪张」这类判断全部留在 API 侧 —— 改判定规则既不需要动抓取服务，也不需要重新抓取。

## 🛠️ 技术栈

### 后端 (`bookmarkify-api`)
- **语言**: Kotlin 2.1.20
- **框架**: Spring Boot 3.5.4
- **数据库**: PostgreSQL
- **缓存**: Redis
- **ORM**: MyBatis Plus
- **认证**: Sa-Token
- **实时通信**: WebSocket
- **API 文档**: Knife4j (Swagger)
- **对象存储**: 阿里云 OSS

### 抓取服务 (`bookmarkify-scrapper`)
- **语言**: Rust
- **框架**: axum + tokio
- **HTML 解析**: `scraper`（CSS 选择器）
- **无头浏览器**: spider-rs（Chrome，隐身模式）—— 作为 JS 渲染页面的回退方案
- **缓存**: moka（内存缓存，带 TTL）
- **安全加固**: DNS 解析层的 SSRF 防护、响应体大小上限、并发限流

### 前端 (`bookmarkify-web`)
- **框架**: Nuxt 4.2
- **UI**: Vue 3
- **状态管理**: Pinia
- **样式**: Tailwind CSS + DaisyUI
- **拖拽**: Vue Draggable

### 管理后台 (`bookmarkify-admin`)
- **框架**: Vue 3 + Vite（精简版 Vben Admin，Element Plus）

## 📁 项目结构

```
bookmarkify/
├── bookmarkify-api/        # 后端服务 (Kotlin + Spring Boot)
├── bookmarkify-web/        # 前端应用 (Nuxt + Vue 3)
├── bookmarkify-scrapper/   # 网页抓取微服务 (Rust)
├── bookmarkify-admin/      # 管理后台 (Vue 3 + Vite)
├── contract/               # 跨语言契约样例，Rust 与 Kotlin 的测试共读同一份
└── deploy/                 # Compose 配置、nginx 配置、SQL 迁移
```

## 🚀 快速开始

### 环境要求
- JDK 21+
- Rust (stable)
- Node.js 20.12+ 与 pnpm
- PostgreSQL 14+
- Redis 6+

### 后端启动
```bash
cd bookmarkify-api
./gradlew bootRun
```

### 抓取服务启动
```bash
cd bookmarkify-scrapper
# 前台与抓取服务默认都监听 3000，同时运行时需要改掉其中一个
PORT=3001 cargo run -p scraper-service
```

### 前端启动
```bash
cd bookmarkify-web
pnpm install
pnpm dev
```

### 管理后台启动
```bash
cd bookmarkify-admin
pnpm install
pnpm dev
```

### 数据库

`deploy/migrations/` 下是纯 SQL 迁移，按文件名顺序**手工执行**。部署流程**不会**自动跑这些迁移 —— 请在发布依赖它们的版本之前先执行。

## 📄 许可证

本项目采用 MIT 许可证。
