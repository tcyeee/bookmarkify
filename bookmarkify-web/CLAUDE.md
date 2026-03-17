# CLAUDE.md - Bookmarkify Web

## Project Overview

**Bookmarkify Web** (书签鸭) is the frontend for a bookmark management platform built with Nuxt.js 4 + Vue 3. It provides a browser-style launchpad where users can save, organize, and browse bookmarks with drag-and-drop, real-time updates via WebSocket, and customizable backgrounds. The UI is entirely in Chinese (Simplified).

## Tech Stack

- **Framework:** Nuxt.js 4.2.1 (Vue 3, SSR/SPA hybrid)
- **Language:** TypeScript 5.9.3
- **Styling:** Tailwind CSS 4 + DaisyUI 5 (prefix: `cy-`) + Element Plus 2.11 + Sass
- **State:** Pinia 3 with `pinia-plugin-persistedstate`
- **Package Manager:** pnpm
- **Build:** Vite (via Nuxt)
- **Animation:** GSAP 3.14, Typed.js, Lenis (smooth scroll)
- **Grid:** vuuri 0.4 (Muuri-based drag-and-drop)
- **Utilities:** @vueuse/core, nanoid, clsx + tailwind-merge

## Project Structure

```
bookmarkify-web/
├── app.vue                        # Root Vue component
├── error.vue                      # Global error page (404/500)
├── nuxt.config.ts                 # Nuxt configuration
├── tailwind.config.ts             # Tailwind/DaisyUI config
├── pages/                         # File-based routing
│   ├── index.vue                  # Main launchpad/home
│   ├── welcome.vue                # Landing/onboarding page
│   ├── setting.vue                # Settings page
│   └── market.vue                 # Marketplace (stub)
├── layouts/                       # Nuxt layouts
│   ├── default.vue                # Bare pass-through
│   ├── launch.vue                 # Launchpad (bg image/gradient)
│   ├── setting.vue                # Settings (sidebar nav)
│   └── explore.vue                # Welcome/landing
├── components/
│   ├── common/                    # Header, Footer, CommandPalette, AddBookmarkFab, ActionInput
│   ├── launch/                    # LaunchItem (dispatches to cell subtypes)
│   ├── launchpad/                 # AddOneDialog, Detail, cell/ subfolder
│   │   └── cell/                  # Bookmark, BookmarkLogo, Folder, Function cells
│   ├── setting/                   # Account, background, preference panels
│   │   ├── account/               # Profile, avatar, login, verify, bind, delete
│   │   └── background/            # Gradient config, image upload, preview
│   ├── home/                      # TimeStr widget
│   ├── upload/                    # Upload components
│   ├── stunning/                  # Visual effects: DotPattern, ScrollReveal, ShimmerText, TypedText
│   └── welcome/                   # FloatingBookmarks animation
├── stores/                        # Pinia state management
│   ├── auth.store.ts              # Auth state, login/logout/token refresh
│   ├── bookmark.store.ts          # Bookmark layout tree, real-time updates
│   ├── preference.store.ts        # User preferences, background cache
│   ├── sys.store.ts               # System: key events, dialogs, countdown
│   └── websocket.store.ts         # WebSocket connection, heartbeat, reconnect
├── server/                        # Nuxt server-side utilities
│   ├── apis/
│   │   ├── http.ts                # HTTP client class (fetch-based, debounce, auto-reauth)
│   │   └── index.ts               # All API endpoint functions
│   ├── config/
│   │   └── image.config.ts        # Image URL helpers, env-aware file URLs
│   ├── utils/index.ts             # cn(), randomId(), limitAction(), getCurrentEnvironment()
│   └── routes/upload/[...path].ts # Dev-only static file proxy
├── middleware/
│   └── auth.ts                    # Route guard: redirect unauthenticated users
├── plugins/
│   ├── auth.ts                    # Startup: reconnect WS, refresh user/bookmarks
│   ├── keyListener.ts             # Global keyboard event listener
│   └── contextMenu.ts             # Right-click context menu registration
├── typing/                        # TypeScript type definitions
│   ├── index.ts                   # Barrel export
│   ├── bookmark.ts                # UserLayoutNodeVO, BookmarkShow, etc.
│   ├── user.ts                    # UserInfo, login/captcha param types
│   ├── enum.ts                    # All enums (HomeItemType, AuthStatus, etc.)
│   ├── result.ts                  # Result<T> API response wrapper
│   ├── setting.ts                 # UserPreference, BacSettingVO, etc.
│   └── websocket.ts               # SocketMessage
└── assets/
    ├── css/                       # app.css, common.scss, icon.scss
    └── fonts/                     # Jersey10-Regular.ttf
```

## Build & Run

```bash
# Install dependencies
pnpm install

# Dev server (http://localhost:3000)
pnpm dev

# Production build
pnpm build

# Preview production build
pnpm preview

# Docker (build first, then containerize)
pnpm build && docker build -t bookmarkify-web .
```

**Prerequisites:** Node.js 18+, pnpm, backend API running at `http://127.0.0.1:7001`

## Environment Variables

Copy `.env.example` to `.env`:

| Variable | Purpose | Default |
|---|---|---|
| `NUXT_API_BASE` | Backend REST API URL | `http://127.0.0.1:7001` |
| `NUXT_WS_BASE` | Backend WebSocket URL | `ws://localhost:7001` |
| `NUXT_PUBLIC_SITE_URL` | Public site URL | `https://bookmarkify.cc` |

## Architecture

### Key Patterns

1. **Anonymous-first auth:** Backend auto-creates a session via `/auth/track`. Guests get a temporary token and "upgrade" by verifying phone or email.

2. **Tree-based bookmark layout:** Bookmarks stored as `UserLayoutNodeVO[]` tree with types: `BOOKMARK`, `BOOKMARK_DIR`, `FUNCTION`, `BOOKMARK_LOADING` (placeholder during async parsing).

3. **WebSocket real-time updates:** After adding a bookmark, the backend parses the website asynchronously via Kafka, then pushes `HOME_ITEM_UPDATE` via WebSocket. The store applies the update with new object references to trigger Vue reactivity.

4. **Request debounce guard:** `http.withDebounce()` prevents duplicate in-flight requests within 600ms.

5. **Background caching:** Background images are converted to DataURL and cached in localStorage. Gradients rendered via CSS `linear-gradient`.

6. **Preference-driven UI:** Grid cell size (60/80/100px), gap mode, page turn mode, title visibility, and open-link behavior are all driven by `preferenceStore`.

### API Layer

All API calls go through the static `http` class (`server/apis/http.ts`):
- Auto token injection via `satoken` header
- Auto re-login on token expiry (response code 101)
- Request deduplication with 600ms debounce window
- Centralized error display via `ElMessage.error()`

Response format: `Result<T> { code, msg, data, ok }` — code 0 = success, 1xx = user error, 3xx = silent error.

### WebSocket

- URL: `{wsBase}/ws?token={token}`
- Heartbeat: ping every 5 seconds
- Reconnect: exponential backoff (1s → 30s max, 5 attempts max)
- Message type: `HOME_ITEM_UPDATE` (bookmark cell refresh)

## Path Aliases

| Alias | Target |
|---|---|
| `@api` | `server/apis` |
| `@stores` | `stores` |
| `@config` | `server/config` |
| `@typing` | `typing` |
| `@utils` | `server/utils` |
| `~/` | Project root (Nuxt standard) |

## Coding Conventions

### Vue Components
- All use `<script setup lang="ts">` (Composition API)
- Props: `defineProps<{...}>()`
- Emits: `defineEmits<{...}>()`
- File naming: `PascalCase.vue`

### Pinia Stores
- Named exports: `useXxxStore()` pattern
- Option Store syntax (not Setup Store / Composition API)
- Persistence: `persist: true` or explicit `persist: { storage: piniaPluginPersistedstate.localStorage() }`
- WebSocket store does NOT persist

### Styling
- DaisyUI components use `cy-` prefix: `cy-btn`, `cy-modal`, `cy-input`
- **Prefer DaisyUI components** for all UI elements (https://daisyui.com) — use `cy-btn`, `cy-badge`, `cy-alert`, `cy-tooltip`, etc. before reaching for raw Tailwind or custom CSS
- Tailwind utilities used directly in templates
- Dark mode via `.dark` class on `<body>` + `data-theme="dark"`
- Custom `cn()` utility for conditional classes: `twMerge(clsx(...))`
- DaisyUI themes configured: `light` (default), `dark` (prefers-dark), `cupcake`

### API Functions
- Descriptive names: `bookmarksShowAll()`, `captchaVerifyEmail()`, `updateUserPreference()`
- Typed returns: `Promise<t.SomeType>`
- HTTP client is a static class (not a composable or plugin)

### Code Style
- Prettier: 130 char width, single quotes, no semicolons, bracket same line
- UI text, comments, and debug logs are in Chinese
- TypeScript stores: `camelCase.store.ts`

### Error Handling
- API errors display via `ElMessage.error()` (Element Plus)
- Token expiry (code 101) triggers auto re-login
- Codes 1xx show error messages; codes 3xx silently reject

## Important Notes

- **No tests exist** — no test framework or test files in this project
- The `public/upload/` directory is for dev-only local file serving; production uses `https://file.bookmarkify.cc`
- `market.vue` is a stub page (not yet implemented)
- The backend API project lives at `../bookmarkify-api/` (Kotlin/Spring Boot, port 7001)
