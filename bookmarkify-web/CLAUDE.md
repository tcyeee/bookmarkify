# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

**Bookmarkify Web** (书签鸭) is the Nuxt 4 + Vue 3 frontend for a bookmark management platform. It presents a browser-style launchpad where users save, organize, and browse bookmarks with drag-and-drop, real-time updates via WebSocket, and customizable backgrounds. UI text, comments, and debug logs are in Chinese (Simplified) — the app targets Chinese users.

The backend (Kotlin/Spring Boot) lives at `../bookmarkify-api/` on port 8001 (local dev; prod is 7001). See `api.md` for the REST contract.

## Tech Stack

- **Framework:** Nuxt 4.2 (Vue 3, SSR/SPA hybrid), Vite, TypeScript 5.9
- **Styling:** Tailwind CSS 4 + DaisyUI 5 (prefix `cy-`) + Sass. No component library — element-plus was fully removed; all UI is DaisyUI/native HTML.
- **State:** Pinia 3 + `pinia-plugin-persistedstate` (Option Store syntax)
- **Package manager:** pnpm (Node 18+)
- **Notable libs:** @atlaskit/pragmatic-drag-and-drop (drag-and-drop grid), GSAP, Typed.js, Lenis, @vueuse/core, vue-command-palette, @imengyu/vue3-context-menu, @iconify/vue

## Commands

```bash
pnpm install              # also runs `nuxt prepare` via postinstall
pnpm dev                  # http://localhost:3000 (needs backend at 127.0.0.1:8001)
pnpm build
pnpm preview
pnpm generate             # static site generation → .output/public
```

## Deployment

Deployed as a **static site** (`nuxt generate` → `.output/public`) served directly by nginx — there is no Node/Docker runtime in production. CI (`.github/workflows/deploy-web.yml`, monorepo root) builds on push to the `prod` branch, rsyncs `.output/public` to `/home/ubuntu/www/bookmarkify-web/` on the server (nginx `location /` serves it with a `/200.html` SPA fallback), then notifies WeChat via Server酱. The prod public URLs (`NUXT_API_BASE=https://bookmarkify.cc/api`, `NUXT_WS_BASE=wss://bookmarkify.cc`) are baked in at build time by the workflow.

There is no test runner or lint script configured in `package.json`. No tests exist in the repo. A `pnpm typecheck` script (`nuxt typecheck`) does exist, but it currently fails with several pre-existing type errors (implicit `any`, possibly-`undefined` access in `bookmark.store.ts`) that predate this note — it is not wired into CI.

## Environment Variables

Copy `.env.example` to `.env`:

| Variable | Purpose | Default |
|---|---|---|
| `NUXT_API_BASE` | Backend REST URL | `http://127.0.0.1:8001` |
| `NUXT_WS_BASE` | Backend WebSocket URL | `ws://localhost:8001` |
| `NUXT_PUBLIC_SITE_URL` | Public site URL (SEO/canonical) | `https://bookmarkify.cc` |

## Architecture (the parts that span files)

### Anonymous-first auth
Every visitor gets a session via `POST /auth/track` — no login required. Guest sessions "upgrade" by verifying phone or email. The auth plugin (`plugins/auth.ts`) restores the session on page load, then reconnects WebSocket and re-fetches user + bookmark data. `middleware/auth.ts` redirects unauthenticated users to `/welcome`. Token is sent via the **`satoken`** HTTP header (not `Authorization`).

### Tree-based bookmark layout
Bookmarks are a `UserLayoutNodeVO[]` tree in `bookmark.store.ts`. Node types (see `typing/enum.ts` `HomeItemType`): `BOOKMARK`, `BOOKMARK_DIR`, `FUNCTION`, `BOOKMARK_LOADING` (placeholder while the backend parses the URL). `components/launch/LaunchItem.vue` dispatches each node to the right cell component under `components/launchpad/cell/`.

### WebSocket-driven live updates
After the user adds a URL, the backend parses the page asynchronously (Spring `ApplicationEvent` + `@Async`, in-process — not a message queue) and pushes a `HOME_ITEM_UPDATE` message. `stores/websocket.store.ts` connects to `{wsBase}/ws?token={token}`, pings every 5s, and reconnects with exponential backoff (1s → 30s, max 5 attempts). On `HOME_ITEM_UPDATE`, the bookmark store **must replace nodes with new object references** to trigger Vue reactivity — see `updateOneBookmarkCell()` for the pattern. Direct nested mutation will not re-render.

### HTTP client
All API calls go through the static `http` class in `server/apis/http.ts`. Endpoint functions live in `server/apis/index.ts` and return `Promise<t.SomeType>`.

- Auto-injects `satoken` header
- On response code `101` (token expired), it logs the user out (`authStore.logout()`) and redirects to `/welcome` — there is no silent re-login/retry
- `http.withDebounce()` deduplicates in-flight requests within a 600ms window
- Response shape: `Result<T> { code, msg, data, ok }` — `code === 0` is success, `1xx` shows an error toast via `useToastStore().error()`, `3xx` rejects silently
- Components should not duplicate API error toasts; the client handles them centrally

### Background rendering & preferences
`preference.store.ts` drives grid cell size (60/80/100px), gap mode, page-turn behavior, title visibility, and link-open target. Background images are converted to DataURL and cached in `localStorage` for instant paint; gradients are pure CSS `linear-gradient` (no image files). Background rendering happens in `layouts/launch.vue`.

### Layouts
- `launch.vue` — main app (background + launchpad)
- `setting.vue` — settings sidebar
- `explore.vue` — `/welcome` landing
- `default.vue` — pass-through

Pages declare layout via `definePageMeta({ layout: '...' })`.

## Path Aliases

Configured in both `nuxt.config.ts` and `tsconfig.json`:

| Alias | Target |
|---|---|
| `@api` | `server/apis` |
| `@stores` | `stores` |
| `@config` | `server/config` |
| `@typing` | `typing` |
| `@utils` | `server/utils` |

Pinia stores, `@vueuse/core` composables, and Vue components are auto-imported by Nuxt — don't add manual imports for them.

## Conventions

- **Vue:** `<script setup lang="ts">` only; `defineProps<{...}>()` / `defineEmits<{...}>()`; files `PascalCase.vue`.
- **Stores:** `camelCase.store.ts`, exported as `useXxxStore()`, **Option Store** syntax (not Setup Store). Persistence via `persist: true` or explicit `persist: { storage: piniaPluginPersistedstate.localStorage() }`. The WebSocket store must NOT persist.
- **Types:** Define in `typing/`, barrel-export from `typing/index.ts`. Cross-module type access uses `import * as t from '@typing'`.
- **DaisyUI:** the prefix is `cy-` (e.g. `cy-btn`, `cy-modal`, `cy-tooltip`, `cy-alert`). Prefer DaisyUI components over raw Tailwind / custom CSS when one fits. Themes: `light` (default), `dark` (prefers-dark), `cupcake`. Dark mode toggles `.dark` on `<body>` and `data-theme="dark"`.
- **Class composition:** use `cn()` from `@utils` (`twMerge(clsx(...))`).
- **Toasts:** `useToastStore().success/error/warning/info(message)` (`stores/toast.store.ts`, rendered by `components/common/ToastHost.vue`, mounted once in `app.vue`) — a small DaisyUI-based (`cy-toast`/`cy-alert`) replacement for element-plus's `ElMessage`/`ElNotification`. Do not reintroduce a UI library for this.
- **Confirm dialogs:** `useConfirmStore().confirm(message, { title?, confirmText?, cancelText?, type? })` (`stores/confirm.store.ts` + `components/common/ConfirmDialog.vue`) returns a `Promise<void>` that resolves on confirm and rejects on cancel/Esc/backdrop-click — the same `try { await ... } catch { return }` shape element-plus's `ElMessageBox.confirm` used.
- **Prettier:** 130 char width, single quotes, no semicolons, bracket same line.

## Plugins (load order in `nuxt.config.ts`)

`iconify.ts` → `keyListener.ts` → `contextMenu.ts` → `auth.ts`. The auth plugin runs last because it depends on the WebSocket connection and key listener being ready.

## Notes

- `public/upload/` and `server/routes/upload/[...path].ts` are dev-only static file proxies; production serves files from `https://file.bookmarkify.cc` (see `server/config/image.config.ts`).
- `pages/market.vue` is a stub; not yet implemented.
- `AGENTS.md` documents per-domain agent roles (UI, state, API, styling, auth) with deeper conventions for each — useful when scoping a task to one area.
- `api.md` describes the backend API surface this client consumes.
