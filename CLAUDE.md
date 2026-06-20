# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What This Is

**Bookmarkify** (书签鸭) is a bookmark management platform consisting of four services that work together. Each service has its own `CLAUDE.md` with detailed conventions — read those when working inside a specific service.

## Services

| Directory | What it is | Port | Tech |
|---|---|---|---|
| `bookmarkify-api/` | Backend REST API + WebSocket server | 7001 | Kotlin 2.1 + Spring Boot 3.5 |
| `bookmarkify-web/` | User-facing frontend | 3000 | Nuxt 4 + Vue 3 + TypeScript |
| `bookmarkify-scrapper/` | Headless page-scraping microservice | 3000 | Rust (axum + spider-rs) |
| `bookmarkify-admin/` | Admin panel (Vben Admin monorepo) | 5173 | Vue 3 + Turborepo |

## Quick Start Commands

### API (bookmarkify-api)
```bash
cd bookmarkify-api
./gradlew bootRun --args='--spring.profiles.active=dev'   # run
./gradlew bootJar                                          # build jar
./gradlew test                                             # run tests
```

### Web (bookmarkify-web)
```bash
cd bookmarkify-web
pnpm install
pnpm dev        # http://localhost:3000 — needs API on :7001
pnpm build
```

### Scrapper (bookmarkify-scrapper)
```bash
cd bookmarkify-scrapper
cargo run -p scraper-service                               # run
cargo build -p scraper-service --release                  # build
cargo test -p scraper-service                             # unit tests
cargo test -p scraper-service -- --ignored                # integration (needs Chrome)
cargo clippy -p scraper-service && cargo fmt              # lint + format
```

### Admin (bookmarkify-admin)
```bash
cd bookmarkify-admin
pnpm install
pnpm dev:ele    # dev server for the web-ele app
pnpm build:ele  # build
pnpm test:unit  # vitest unit tests
pnpm lint       # ESLint
```

## Admin Panel (bookmarkify-admin)

`bookmarkify-admin/` has no per-service `CLAUDE.md`. Key points:

- **Framework:** Vben Admin monorepo (Turborepo + pnpm workspaces). The production app is `apps/web-ele/`.
- **API proxy:** In dev, Vite proxies `/api/admin` → strips the `/api` prefix → forwards to `http://localhost:7001`. So admin endpoints are `/admin/**` on the API.
- **Auth:** Uses Sa-Token `ADMIN` realm (separate from the `USER` realm used by the web frontend). Default credentials: `tcyeee@outlook.com` / `admin`.
- **No `src/test/` in the monorepo root.** Only `pnpm test:unit` runs Vitest inside `apps/web-ele/`.

## Service Interaction

```
Browser ──────────────────────────────────────────────────────────┐
  │                                                                │
  ▼ HTTP/WS                                                        │ Admin Panel (bookmarkify-admin)
bookmarkify-web ──── REST+WS ────► bookmarkify-api ◄── REST+WS ───┘
(Nuxt, port 3000)    satoken       (Spring Boot, port 7001)
                     header         │
                                    ├── PostgreSQL (schema: bookmarkify)
                                    ├── Redis (session/cache)
                                    ├── @Async executor (in-process parsing)
                                    └── POST /scrape ──► bookmarkify-scrapper
                                                         (Rust, port 3000)
```

Key flows:
- **Adding a bookmark:** Web → API stores a `BOOKMARK_LOADING` placeholder → publishes a Spring `ApplicationEvent` → an `@Async` listener triggers `/scrape` on the scrapper → result saved and pushed to the browser via WebSocket (`HOME_ITEM_UPDATE`).
- **Auth:** Every visitor gets an anonymous session via `/auth/track`. The `satoken` cookie/header is used on all requests — not `Authorization`. Registered users "upgrade" the anonymous session.
- **Storage:** Files (avatars, logos, OG images) go to Alibaba Cloud OSS; the scrapper uploads concurrently, the API also uploads via its own OSS util.

## Local Dev Port Conflict

Both `bookmarkify-web` and `bookmarkify-scrapper` listen on port **3000**. You cannot run them simultaneously without remapping one. In production, Nginx routes traffic so they never bind on the same host. For local development, either run only one at a time or override the scrapper port via the `PORT` environment variable (`PORT=3001 cargo run -p scraper-service`).

## Infrastructure (deploy/)

`deploy/compose.yml` — Docker Compose for the full stack; every secret is a `REPLACE_FLAG` placeholder that must be substituted manually before use. `deploy/nginx/` — reverse-proxy config (`bookmakify.cc.conf`, `admin.bookmarkify.cc.conf`, `file.bookmakify.cc.conf`).

## Deployment (CI/CD)

All four services deploy via GitHub Actions (`.github/workflows/deploy-{api,web,scrapper,admin}.yml`), **triggered on push to the `prod` branch** (or manual `workflow_dispatch`). `main` is the working branch; deploys happen by promoting to `prod`. The `api`/`scrapper` workflows are path-filtered to their own directory; `web`/`admin` run on every `prod` push. Concurrency guards prevent overlapping deploys, and WeChat/Server酱 notifications fire on success/failure. Target host: `ubuntu@123.206.216.124`. API & scrapper build Docker images (Tencent TCR) and run via Compose; web & admin are static builds rsynced to nginx-served directories.

## Prerequisites

- JDK 21+ (API)
- Cargo / Rust stable (Scrapper)
- Node.js 18+ & pnpm (Web)
- Node.js 20.12+ & pnpm 10+ (Admin)
- PostgreSQL 14+, Redis 6+ (for API)
