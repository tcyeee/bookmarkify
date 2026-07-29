![](assets/banner.png)

<div align="center"><a href="i18n/README.zh.md">中文</a> ｜ English</div>

# Bookmarkify

Bookmarkify is a modern web application for saving, organizing, sharing, and exploring web bookmarks. With an intuitive interface and powerful features, it makes bookmark management simple and efficient.

## ✨ Features

- **📚 Bookmark Management** - Quickly save and organize your web bookmarks with categories and tags
- **🔗 Smart Parsing** - A dedicated scraping service extracts titles, descriptions, icons, and social images — falling back to a headless browser for JavaScript-rendered pages
- **🎨 Adaptive Icons** - Every image a site declares is captured with its provenance, so the right one can be picked per layout — with a letter-tile fallback when a site offers nothing good enough
- **👥 Share & Collaborate** - Share bookmarks with friends or publish them publicly for others to discover
- **🔥 Trending Discovery** - Explore recently popular bookmarks from the community
- **🌐 Real-time Sync** - WebSocket-based real-time data synchronization for seamless multi-device experience
- **📱 Responsive Design** - Perfectly adapted for both desktop and mobile devices

## 🏗️ Architecture

Four services work together:

| Service | Role | Port | Stack |
|---|---|---|---|
| `bookmarkify-api` | REST API + WebSocket server | 8001 | Kotlin + Spring Boot |
| `bookmarkify-web` | User-facing frontend | 3000 | Nuxt + Vue 3 |
| `bookmarkify-scrapper` | Page-scraping microservice | 3000 | Rust (axum + spider-rs) |
| `bookmarkify-admin` | Admin panel | 5777 | Vue 3 + Vite (Vben Admin) |

```
Browser ─────────────────────────────────────────────────┐
  │                                                       │
  ▼ HTTP/WS                                               │ Admin Panel
bookmarkify-web ──── REST+WS ────► bookmarkify-api ◄──────┘
                                    │
                                    ├── PostgreSQL
                                    ├── Redis
                                    └── POST /scrape ──► bookmarkify-scrapper
```

The scraper is a **standalone, domain-neutral service**: it reports what a page *declared* (which tag each image came from, where each metadata field originated) and takes no view on what any of it means. All product judgement — which image is a logo, which one to render at which size — lives in the API. Changing those rules requires no scraper change and no re-crawl.

## 🛠️ Tech Stack

### Backend (`bookmarkify-api`)
- **Language**: Kotlin 2.1.20
- **Framework**: Spring Boot 3.5.4
- **Database**: PostgreSQL
- **Cache**: Redis
- **ORM**: MyBatis Plus
- **Authentication**: Sa-Token
- **Real-time**: WebSocket
- **API Documentation**: Knife4j (Swagger)
- **Object Storage**: Alibaba Cloud OSS

### Scraper (`bookmarkify-scrapper`)
- **Language**: Rust
- **Framework**: axum + tokio
- **HTML Parsing**: `scraper` (CSS selectors)
- **Headless Browser**: spider-rs (Chrome, stealth mode) — used as a fallback for JS-rendered pages
- **Cache**: moka (in-memory, TTL)
- **Hardening**: SSRF protection at the DNS-resolver level, body size caps, concurrency limits

### Frontend (`bookmarkify-web`)
- **Framework**: Nuxt 4.2
- **UI**: Vue 3
- **State Management**: Pinia
- **Styling**: Tailwind CSS + DaisyUI
- **Drag & Drop**: Vue Draggable

### Admin (`bookmarkify-admin`)
- **Framework**: Vue 3 + Vite (pruned Vben Admin, Element Plus)

## 📁 Project Structure

```
bookmarkify/
├── bookmarkify-api/        # Backend service (Kotlin + Spring Boot)
├── bookmarkify-web/        # Frontend application (Nuxt + Vue 3)
├── bookmarkify-scrapper/   # Page-scraping microservice (Rust)
├── bookmarkify-admin/      # Admin panel (Vue 3 + Vite)
├── contract/               # Cross-language contract fixture, shared by Rust and Kotlin tests
└── deploy/                 # Compose files, nginx config, SQL migrations
```

## 🚀 Quick Start

### Prerequisites
- JDK 21+
- Rust (stable)
- Node.js 20.12+ & pnpm
- PostgreSQL 14+
- Redis 6+

### Backend
```bash
cd bookmarkify-api
./gradlew bootRun
```

### Scraper
```bash
cd bookmarkify-scrapper
# web and the scraper both default to port 3000 — remap one when running both
PORT=3001 cargo run -p scraper-service
```

### Frontend
```bash
cd bookmarkify-web
pnpm install
pnpm dev
```

### Admin
```bash
cd bookmarkify-admin
pnpm install
pnpm dev
```

### Database

Migrations under `deploy/migrations/` are plain SQL and applied by hand, in filename order. They are **not** run by the deployment pipeline — apply them before rolling out a version that depends on them.

## 📄 License

This project is licensed under the MIT License.
