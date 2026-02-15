# AGENTS.md - Bookmarkify Web

## Agent Roles and Responsibilities

This document defines the specialized agent roles for working on the Bookmarkify Web codebase.

---

### UI Component Agent

**Scope:** Vue components under `components/`, `pages/`, and `layouts/`

**Key responsibilities:**
- Build new Vue components following `<script setup lang="ts">` pattern
- Create page components in `pages/` with proper `definePageMeta` (layout, middleware)
- Add layout variants in `layouts/` when new page structures are needed
- Implement responsive designs using Tailwind CSS + DaisyUI (`cy-` prefix)
- Add visual effects using GSAP, Typed.js, or CSS animations

**Conventions to follow:**
- Component files: `PascalCase.vue`, organized by feature domain under `components/`
- Props typed with `defineProps<{...}>()`, emits with `defineEmits<{...}>()`
- Use `cn()` utility from `@utils` for conditional class composition
- DaisyUI components always use `cy-` prefix: `cy-btn`, `cy-modal`, `cy-input`, `cy-chat`
- Dark mode: respect `.dark` class and `data-theme` attribute
- UI text must be in Chinese (Simplified) — the app targets Chinese users
- Use Element Plus `ElMessage` for toast notifications
- Import types from `@typing`, stores from `@stores`, APIs from `@api`

**Key files to understand first:**
- `app.vue` — Root component structure
- `layouts/launch.vue` — Main launchpad layout (background rendering logic)
- `components/launchpad/cell/` — All bookmark cell type implementations
- `components/common/Header.vue` — Global header with auth state
- `components/common/CommandPalette.vue` — Cmd+K search interface

---

### State Management Agent

**Scope:** Pinia stores under `stores/` and data flow

**Key responsibilities:**
- Create and maintain Pinia stores using Option Store syntax
- Manage state persistence strategy (which stores persist, which don't)
- Handle WebSocket message routing and state updates
- Ensure Vue reactivity by creating new object references on mutations
- Coordinate cross-store interactions (e.g., auth → websocket → bookmark)

**Conventions to follow:**
- Store files: `camelCase.store.ts`, export `useXxxStore()`
- Use Option Store syntax (not Setup Store / Composition API)
- Persistence via `persist: true` or `persist: { storage: piniaPluginPersistedstate.localStorage() }`
- WebSocket store must NOT persist (`persist: false`)
- When updating nested objects in arrays, always create new references to trigger reactivity
- Access other stores via `useXxxStore()` within actions (not in state)

**Key files:**
- `stores/auth.store.ts` — Auth lifecycle, token management, user info
- `stores/bookmark.store.ts` — Bookmark tree, sort order, real-time cell updates
- `stores/preference.store.ts` — User preferences, background DataURL caching
- `stores/websocket.store.ts` — WebSocket connection, heartbeat, reconnect logic
- `stores/sys.store.ts` — System-level dialogs, key events, countdown timers

---

### API Integration Agent

**Scope:** API client, endpoint functions, and server utilities under `server/`

**Key responsibilities:**
- Add new API endpoint functions in `server/apis/index.ts`
- Maintain the HTTP client class in `server/apis/http.ts`
- Handle auth token lifecycle (injection, expiry detection, auto re-login)
- Define TypeScript types for API request/response in `typing/`
- Manage image URL configuration in `server/config/image.config.ts`

**Conventions to follow:**
- API functions are descriptive: `bookmarksShowAll()`, `captchaVerifyEmail()`
- Return typed promises: `Promise<t.SomeType>`
- The `http` class is static — do not instantiate, call `http.get()`, `http.post()` directly
- Use `http.withDebounce()` for endpoints that may be called rapidly
- Token is sent via `satoken` HTTP header (not Authorization)
- Response codes: 0 = success, 101 = token expired (triggers auto re-login), 1xx = show error, 3xx = silent reject
- Centralized error display via `ElMessage.error()`
- All request/response types defined in `typing/` and barrel-exported from `typing/index.ts`

**Key files:**
- `server/apis/http.ts` — HTTP client with debounce, token injection, error handling
- `server/apis/index.ts` — All API endpoint function definitions
- `typing/result.ts` — `Result<T>` response wrapper type
- `typing/bookmark.ts` — Bookmark-related types
- `typing/user.ts` — User and auth types
- `typing/setting.ts` — Preference and background types
- `server/config/image.config.ts` — Environment-aware file URL helpers

---

### Styling/Theme Agent

**Scope:** CSS, Tailwind configuration, DaisyUI theming, visual design

**Key responsibilities:**
- Maintain Tailwind CSS 4 configuration and DaisyUI theme setup
- Style components using Tailwind utilities and DaisyUI classes
- Manage dark mode implementation (`.dark` class + `data-theme`)
- Handle background rendering (gradients via CSS, images via DataURL)
- Maintain custom fonts and global CSS in `assets/css/`

**Conventions to follow:**
- DaisyUI always uses `cy-` prefix (configured in `tailwind.config.ts`)
- Tailwind v4 syntax: `@import 'tailwindcss'` in `app.css`
- Three themes: `light` (default), `dark` (prefers-dark), `cupcake`
- Use `cn()` for conditional class merging (clsx + tailwind-merge)
- Background images cached as DataURL in localStorage for performance
- Gradient backgrounds are pure CSS `linear-gradient` — no image files
- Custom font: Jersey10-Regular (pixel-art style) for header logo

**Key files:**
- `assets/css/app.css` — Tailwind setup, DaisyUI themes, custom font
- `assets/css/common.scss` — Global SCSS utilities, command dialog styles
- `tailwind.config.ts` — DaisyUI plugin config with `cy-` prefix
- `layouts/launch.vue` — Background image/gradient rendering
- `components/setting/background/` — Background configuration UI

---

### Auth/Plugin Agent

**Scope:** Authentication flow, middleware, and Nuxt plugins

**Key responsibilities:**
- Maintain the auth middleware (`middleware/auth.ts`) for route guards
- Handle the auth plugin (`plugins/auth.ts`) for session restoration on app load
- Manage keyboard listener plugin and context menu plugin
- Implement login/register flows (SMS, email, image CAPTCHA verification)

**Conventions to follow:**
- Anonymous-first: every visitor gets a session via `/auth/track`; users "upgrade" by verifying phone/email
- Auth state lives in `authStore` — check `isLogin` for authenticated state
- Route guard redirects unauthenticated users to `/welcome`
- On app startup, the auth plugin reconnects WebSocket and refreshes user/bookmark data
- Login dialogs are components in `components/setting/account/`
- CAPTCHA and verification flows use countdown timers from `sysStore`

**Key files:**
- `middleware/auth.ts` — Route guard logic
- `plugins/auth.ts` — Startup session restoration
- `plugins/keyListener.ts` — Global keyboard event binding
- `plugins/contextMenu.ts` — Right-click menu registration
- `stores/auth.store.ts` — Auth state and actions
- `components/setting/account/LoginDialog.vue` — Login UI
- `components/setting/account/VerifyEmail.vue` / `VerifyPhone.vue` — Verification flows

---

## Cross-Cutting Concerns

### Type Safety
All agents should define TypeScript types in `typing/` and export them from `typing/index.ts`. Use the `t` namespace import pattern (`import * as t from '@typing'`) when accessing types across modules.

### Error Handling
Use `ElMessage.error()` for user-facing errors. The HTTP client handles API-level errors centrally — individual components should not duplicate error toasts for API calls.

### Reactivity
When updating store state that contains nested objects or arrays, always create new object references. Direct mutation of nested properties will not trigger Vue reactivity. The bookmark store's `updateOneBookmarkCell()` demonstrates the correct pattern.

### Testing
No tests currently exist. When adding tests:
- Use Vitest (Nuxt's recommended test runner)
- Place test files alongside source files or in a `tests/` directory
- Mock API calls and store state
- Test component rendering with `@vue/test-utils`

### Code Organization
- Components are auto-imported by Nuxt — no manual registration needed
- Composables from `@vueuse/core` are auto-imported
- Pinia stores are auto-imported via `@pinia/nuxt` module
- Path aliases (`@api`, `@stores`, `@typing`, etc.) are configured in both `nuxt.config.ts` and `tsconfig.json`
