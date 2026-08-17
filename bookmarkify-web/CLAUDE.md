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

Deployed as a **static site** (`nuxt generate` → `.output/public`) served directly by nginx — there is no Node/Docker runtime in production. CI (`.github/workflows/deploy-web.yml`, monorepo root) builds on push to the `prod` branch, rsyncs `.output/public` to `/home/ubuntu/www/bookmarkify-web/` on the server (nginx `location /` serves it with a `/200.html` SPA fallback), then notifies WeChat via Server酱. The workflow sets `NUXT_BACKEND=https://bookmarkify.cc` and `nuxt.config.ts` derives the public `apiBase` (`https://bookmarkify.cc/api`) and `wsBase` (`wss://bookmarkify.cc`) from it; both are baked into the static output at build time.

### Shipping a new version to already-open tabs

Three pieces that only work as a set — this is a launchpad users leave open for days, so "they'll refresh eventually" is not a strategy.

- **The deploy is two rsync passes, and the order is load-bearing.** `_nuxt/` goes first **without** `--delete` (content-hashed names never collide, so adding is always safe), everything else after **with** `--delete`. Keeping the previous build's chunks alive is what stops an open tab from breaking mid-upgrade: it lazy-loads a route chunk that no longer exists, `try_files` hands it `/200.html`, and the dynamic import dies on the MIME type rather than on a clean 404. Assets-before-HTML also closes the reverse window, where fresh HTML references a chunk that hasn't landed. A `find -mtime +14` sweep collects the old ones; `rsync -a` rewrites the mtime of every file it ships, so a chunk the current build still references never ages out.
- **nginx caching is layered by path** (`deploy/nginx/bookmakify.cc.conf`): `/_nuxt/` is `immutable` for a year, `/_nuxt/builds/latest.json` is `no-store`, everything else is `no-cache`. Before this the file had **no** cache headers at all, so HTML fell to browser heuristic caching and a user could hard-refresh and still get an `index.html` pointing at chunks the next deploy had already replaced. **The `no-cache` must sit on `location /` itself, not on a `~* \.html$` regex location** — `try_files` only performs an internal redirect for its *last* argument; earlier ones (`$uri/index.html`, which is what the homepage hits) are processed in the current context and never re-match, so the regex form silently leaves `/` with no header at all. Verify with `curl -I https://bookmarkify.cc/` — exactly one `Cache-Control` per response (two means an `expires` directive is fighting an `add_header`).
- **Detection is Nuxt's app manifest, retimed.** Every build emits `_nuxt/builds/latest.json` with a fresh `buildId`; Nuxt's built-in `check-outdated-build.client` polls it and fires `app:manifest:update`, whose default handler hard-reloads **on the next route navigation**. That default is nearly useless here — the desktop is effectively one page (`/`), so navigation may never happen. `plugins/version.client.ts` adds the missing triggers: `experimental.checkOutdatedBuildInterval` drops 1h → 5min, a `visibilitychange` listener re-checks whenever the tab comes back to the foreground (timers are throttled while hidden, and returning to the tab is also the moment no drag or input is in flight), and the outcome splits by visibility — a hidden page reloads silently, a visible one only gets a toast, because yanking the page out from under someone mid-drag is worse than staying a version behind.

There is no test runner or lint script configured in `package.json`. No tests exist in the repo. `pnpm typecheck` (`nuxt typecheck`) is clean (0 errors) and **runs in CI before the build** — a failure there blocks the deploy. It has to be a separate step because `nuxt generate` does not type check at all (vite strips types without reading them), so without it a broken type ships silently.

This suite used to fail with ~86 errors that were written off as "pre-existing noise". They were not independent: `bookmark.store.ts` passed `persist: { paths: [...] }`, and `paths` is the **v3** name for that option (v4 renamed it `pick`). An unrecognized key made the whole options object fail `defineStore`'s overload resolution, collapsing getters and actions to `{}` and cascading ~80 "Property 'xxx' does not exist" errors across every file that touched the store. If this suite ever goes loud again, look for one root cause before assuming the errors are unrelated.

## Environment Variables

Copy `.env.example` to `.env`:

| Variable | Purpose | Default |
|---|---|---|
| `NUXT_BACKEND` | Backend origin — the **only** switch for which backend to hit. REST and WebSocket are both derived from it in `nuxt.config.ts`: remote values get `/api` appended for REST (nginx strips it again), local ones don't; `wsBase` is the same origin with `http`→`ws`. Pass an origin only — no `/api`, no `/ws`. | `http://127.0.0.1:8001` |
| `NUXT_PUBLIC_SITE_URL` | Public site URL (SEO/canonical) | `https://bookmarkify.cc` |
| `NUXT_PUBLIC_GOOGLE_CLIENT_ID` | Google OAuth client ID | — |
| `NUXT_PUBLIC_GITHUB_CLIENT_ID` | GitHub OAuth client ID | — |

## Architecture (the parts that span files)

### Anonymous-first auth
Every visitor gets a session via `POST /auth/track` — no login required. Guest sessions "upgrade" by verifying phone or email. The auth plugin (`plugins/auth.ts`) restores the session on page load, then reconnects WebSocket and re-fetches user + bookmark data. `middleware/auth.ts` redirects unauthenticated users to `/welcome`. Token is sent via the **`satoken`** HTTP header (not `Authorization`).

### Tree-based bookmark layout
Bookmarks are a `UserLayoutNodeVO[]` tree in `bookmark.store.ts`. Node types (see `typing/enum.ts` `HomeItemType`): `BOOKMARK`, `BOOKMARK_DIR`, `FUNCTION`, `BOOKMARK_LOADING` (placeholder while the backend parses the URL). `pages/index.vue` renders `BOOKMARK` / `BOOKMARK_DIR` (via `BookmarkFolderCard` → `BookmarkTreeRow`) and `BOOKMARK_LOADING`. **`FUNCTION` has no renderer** — its cell was part of the launchpad board that the rewrite orphaned and `2026-08-04` deleted; a `FUNCTION` node reaching the client renders as nothing. The type stays in the wire contract (`UserLayoutNodeVO.typeFuc`).

### WebSocket-driven live updates
After the user adds a URL, the backend parses the page asynchronously (Spring `ApplicationEvent` + `@Async`, in-process — not a message queue) and pushes the result back. `stores/websocket.store.ts` connects to `{wsBase}/ws?token={token}`, pings every 5s, and reconnects with exponential backoff (1s → 30s, max 5 attempts).

**A push is fire-and-forget: there is no offline queue and no server-side retry.** `SessionManager.send` drops it if the user has no live session. Everything below exists because of that one fact — treat them as a set, not as optional hardening:

- **Reconnect re-syncs.** `onopen` calls `bookmarkStore.refresh()` when this is a *re*connect (`hasEverConnected`), because whatever was pushed while the socket was down is simply gone. Skipped on the first connect — `plugins/auth.ts` just fetched.
- **The heartbeat is two-way and watched.** The server replies `pong` (`WebSocketHandler.handleTextMessage`); the client records `lastMessageAt` on *any* frame and force-closes after 3 silent ping intervals. Without the reply, a half-open socket (network switch, NAT timeout, laptop lid) keeps `readyState === OPEN` and `send()` keeps succeeding, so `onclose` never fires and the tile spins forever. A send-only heartbeat proves nothing.
- **`online` / `visibilitychange` bypass the backoff budget** via `forceReconnect()`. Five attempts is ~31s; a real outage exhausts it and nothing would ever bring the socket back.
- **Loading tiles poll as a backstop** — see `watchForResolution` / `armPendingWatches` in `bookmark.store.ts`, which retry on a widening interval (30s → 5min, 8 times, ~35 min total) so the window crosses the backend's 30-minute `drainStuckLoading` threshold. Watchers are armed centrally (`setLayout`, `addImportLoadingBatch`, `plugins/auth.ts`), *not* by each caller that inserts a placeholder — that was how nodes restored from `localStorage` ended up with nobody watching them.

The server keeps **all** of a user's sessions and broadcasts to each (`SessionManager`, keyed `realm:uid` → list). It used to keep one, so a second tab kicked the first offline, the first auto-reconnected and kicked the second, and two tabs flapped against each other about once a second forever.

Three layout message types, each with a **different payload shape** — they are separate types precisely so the client can tell them apart:

| Type | Payload | Store action |
|---|---|---|
| `HOME_ITEM_UPDATE` | one node, always `type=BOOKMARK` with non-null `typeApp` | `replaceContent()` |
| `HOME_DIR_UPDATE` | one `BOOKMARK_DIR` node plus its direct `children` | `replaceFolder()` |
| `HOME_LAYOUT_REFRESH` | the whole tree root (same shape as `/bookmark/query`) | `setLayout()` |

All three previously shared the single name `HOME_ITEM_UPDATE`, and `replaceContent()` dropped anything that wasn't the first shape — so folder moves never synced across tabs. If you add a fourth push, give it its own type rather than overloading one.

Whatever the type, the store **must replace nodes with new object references** to trigger Vue reactivity — see `replaceContent()` for the pattern. Direct nested mutation will not re-render.

**A payload's `children` array order is not the contract — `sort` is.** Both entry points that ingest children (`normalize()` for the full tree, `replaceFolder()` for `HOME_DIR_UPDATE`) go through `sortedByServerSort()`; keep any new one on it too. `replaceFolder()` used to take `children.map(c => c.id)` verbatim, which is how "drag a bookmark from folder A into folder B and B's existing bookmarks shuffle" happened: `moveNode` builds that push from a plain `WHERE parent_id = ?` query, so the array arrives in **Postgres heap order**, and a row that has ever been UPDATEd (creating a folder, moving in or out — all rewrite `parent_id`) has its new tuple version somewhere else in the heap, permanently decoupling scan order from `sort`. Production had folders in exactly that state. The API now sorts on its side too (`UserLayoutNodeServiceImpl.CHILD_ORDER`), but the client must not depend on that: **the two services deploy from separate path-filtered pipelines, so a version-skew window is guaranteed.** The matching write-order rule lives in `BookmarkFolderCard.persist()` — `bookmarksSort` must be awaited *before* `bookmarksMoveNode`, because the server builds that push from the sort map as it stands at that moment.

### HTTP client
All API calls go through the static `http` class in `server/apis/http.ts`. Endpoint functions live in `server/apis/index.ts` and return `Promise<t.SomeType>`.

- Auto-injects `satoken` header
- On response code `101` (token expired), it logs the user out (`authStore.logout()`) and redirects to `/welcome` — there is no silent re-login/retry
- `http.withDebounce()` deduplicates in-flight requests within a 600ms window
- Response shape: `Result<T> { code, msg, data, ok }` — `code === 0` is success, `1xx` shows an error toast via `useToastStore().error()`, `3xx` rejects silently
- Components should not duplicate API error toasts; the client handles them centrally

### Background rendering & preferences
`preference.store.ts` drives grid cell size (60/80/100px), gap mode, page-turn behavior, title visibility, and link-open target. Background images are converted to DataURL and cached in `localStorage` for instant paint; gradients are pure CSS `linear-gradient` (no image files). Background rendering happens in `layouts/setting.vue` / `components/setting/background/Preview.vue`; the old full-screen launchpad background (`layouts/launch.vue`) was deleted as dead code on 2026-08-04.

### OAuth login (Google + GitHub)
The site is a static SPA with no server, so both flows run entirely client-side. **Google** (`composables/useGoogleOAuth.ts`): classic OAuth2 implicit flow (`response_type=id_token`) — full-page redirect to Google, credential returns via URL hash to `pages/auth/google/callback.vue`, `state`/`nonce` round-tripped through `sessionStorage` (not usable for a popup since implicit-flow redirects can't reliably `postMessage` cross-origin before unload). **GitHub** (`composables/useGithubOAuth.ts`): authorization-code flow via a popup window — `pages/auth/github/callback.vue` `postMessage`s the code back to the opener (checked against `location.origin` and a `state` value), and the caller exchanges it through the backend. Callback pages are the only consumers of these composables.

### Bulk import
`components/setting/BookmarkManage.vue` uploads a browser bookmark file: `bookmarksUploadPreview()` first (server returns per-item `isDuplicate`, matched on the **canonical** URL quadruple, not on the raw string), the user unchecks what to skip, then `bookmarksUpload(file, skipUrls)` returns the created nodes — folders plus `BOOKMARK_LOADING` placeholders. Those go into the tree via `bookmarkStore.addImportLoadingBatch()`, and each loading node registers a 60s `watchForResolution()` fallback. The backend deliberately does **not** publish parse events for an import (it would flood the parse pool); its `drainStuckLoading()` sweep picks the placeholder rows up in batches, so results trickle back over WebSocket. There is no aggregate progress UI — an earlier `importProgress.store.ts` + `ImportProgressNotice.vue` pair was removed in `fc66cb23`.

### Layouts
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
- **Persist whitelists use `pick`, not `paths`** (`pinia-plugin-persistedstate` v4 renamed it; the repo is on 4.7.1). **Unknown keys are silently ignored**, so the wrong name doesn't warn — it just persists the entire state. That is not merely wasteful: anything non-serializable in state (a `Promise`, a timer handle) comes back from JSON as `{}`, which is truthy, and any `if (this.someInflightThing)` guard is then permanently stuck. Re-check this whenever the plugin is upgraded.
- **Types:** Define in `typing/`, barrel-export from `typing/index.ts`. Cross-module type access uses `import * as t from '@typing'`.
- **Enums owned by the backend are generated, not written here.** `typing/enums.generated.ts` comes from the Kotlin enums (`cd bookmarkify-api && ./gradlew generateSharedEnums`) and `typing/enum.ts` re-exports the ones this app uses. Don't hand-edit the generated file and don't re-declare an API enum locally: `tsc` accepts a union missing a member, so a value the backend added would simply fall through a `switch` and render blank — no error anywhere. Frontend-only enums (`HomeItemType`, `SocketTypes`, `BookmarkGapMode`, …) stay hand-written in `typing/enum.ts`; the backend doesn't know about them.
- **DaisyUI:** the prefix is `cy-` (e.g. `cy-btn`, `cy-modal`, `cy-tooltip`, `cy-alert`). Prefer DaisyUI components over raw Tailwind / custom CSS when one fits. Themes: `light` (default), `dark` (prefers-dark), `cupcake`. Dark mode toggles `.dark` on `<body>` and `data-theme="dark"`.
- **Class composition:** use `cn()` from `@utils` (`twMerge(clsx(...))`).
- **Toasts:** `useToastStore().success/error/warning/info(message)` (`stores/toast.store.ts`, rendered by `components/common/ToastHost.vue`, mounted once in `app.vue`) — a small DaisyUI-based (`cy-toast`/`cy-alert`) replacement for element-plus's `ElMessage`/`ElNotification`. Do not reintroduce a UI library for this.
- **Confirm dialogs:** `useConfirmStore().confirm(message, { title?, confirmText?, cancelText?, type? })` (`stores/confirm.store.ts` + `components/common/ConfirmDialog.vue`) returns a `Promise<void>` that resolves on confirm and rejects on cancel/Esc/backdrop-click — the same `try { await ... } catch { return }` shape element-plus's `ElMessageBox.confirm` used.
- **Prettier:** 130 char width, single quotes, no semicolons, bracket same line.

## Plugins (load order in `nuxt.config.ts`)

`iconify.ts` → `keyListener.ts` → `contextMenu.ts` → `auth.ts` → `analytics.client.ts`. The auth plugin runs before analytics because analytics only needs the router; it does not depend on auth state.

## Notes

- `public/upload/` and `server/routes/upload/[...path].ts` are dev-only static file proxies; production serves files from `https://cdn.bookmarkify.cc` (see `server/config/image.config.ts`).
- `pages/market.vue` is a stub; not yet implemented.
- `AGENTS.md` documents per-domain agent roles (UI, state, API, styling, auth) with deeper conventions for each — useful when scoping a task to one area.
- `api.md` describes the backend API surface this client consumes.
- `/` and `/setting` are forced client-only (`routeRules: { ssr: false }` in `nuxt.config.ts`) because login state only lives in client `localStorage` — SSR/prerender can't read it, so the `auth` middleware would redirect the prerendered HTML to `/welcome` and cause a flash on refresh. `/welcome` stays SSR/prerendered for landing-page SEO and is explicitly listed under `nitro.prerender.routes` since the SPA-ified `/` can no longer be crawled to discover it.
- Analytics is self-hosted GoatCounter, proxied same-origin through nginx as `/count.js` + `/count` (no third-party domain exposed). All tracking goes through `plugins/analytics.client.ts`; call `useNuxtApp().$track('event-name')` from business code rather than touching `window.goatcounter` directly. No-ops in dev.
