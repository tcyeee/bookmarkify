![](../assets/banner.png)

<div align="center">中文 ｜ <a href="../README.md">English</a></div>

# Bookmarkify

Bookmarkify 是一个现代化的书签管理平台，帮助用户轻松保存、组织、分享和探索优质网页书签。通过简洁直观的界面和强大的功能，让书签管理变得简单高效。

## ✨ 核心功能

- **📚 书签管理** - 快速保存和管理你的网页书签，支持分类和标签
- **🔗 智能解析** - 自动提取网页标题、描述和图标，让书签更美观
- **👥 分享协作** - 与朋友分享书签，或公开发布让更多人发现
- **🔥 热门探索** - 发现社区中最近流行的优质书签
- **🌐 实时同步** - 基于 WebSocket 的实时数据同步，多设备无缝体验
- **📱 响应式设计** - 完美适配桌面端和移动端

## 🛠️ 技术栈

### 后端 (Backend)
- **语言**: Kotlin 2.1.20
- **框架**: Spring Boot 3.5.0
- **数据库**: PostgreSQL
- **缓存**: Redis
- **ORM**: MyBatis Plus
- **认证**: Sa-Token
- **实时通信**: WebSocket
- **API 文档**: Knife4j (Swagger)
- **网页解析**: JSoup

### 前端 (Frontend)
- **框架**: Nuxt.js 4.2.1
- **UI 库**: Vue 3 + Element Plus
- **状态管理**: Pinia
- **样式**: Tailwind CSS + DaisyUI
- **拖拽**: Vue Draggable

## 📁 项目结构

```
bookmarkify/
├── bookmarkify-api/     # 后端服务 (Kotlin + Spring Boot)
└── bookmarkify-web/      # 前端应用 (Nuxt.js + Vue 3)
```

## 🚀 快速开始

### 环境要求
- JDK 21+
- Node.js 18+
- PostgreSQL 14+
- Redis 6+

### 后端启动
```bash
cd bookmarkify-api
./gradlew bootRun
```

### 前端启动
```bash
cd bookmarkify-web
pnpm install
pnpm dev
```

## 📄 许可证

本项目采用 MIT 许可证。
