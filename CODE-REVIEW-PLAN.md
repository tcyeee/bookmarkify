# Code Review Plan — web / admin / api (by feature)

Full-codebase review of `bookmarkify-web`, `bookmarkify-admin`, `bookmarkify-api` (scrapper excluded — its own
audit, see note at bottom). Batches are grouped by **feature**, not by service: anywhere a feature has a real
contract across services (the WebSocket push, the icon-selection rules, the session cookie, the shared enums),
one batch pulls in the relevant paths from all sides it touches. Purely backend concerns with no frontend
counterpart (liveness sweeps, OSS ledger) stay single-service — splitting those by service would just be
splitting them by file location with extra steps.

Each batch is run with the `/code-review` skill at **high** effort (correctness bugs + reuse/simplification/
efficiency cleanups), against a **path target** (this is a full audit, not a diff against `main`). A batch that
spans services is **one `/code-review` invocation per path**, findings merged into one file — e.g.:

```
/code-review high bookmarkify-api/src/main/kotlin/.../server/asset/
/code-review high bookmarkify-web/composables/useBookmarkIcon.ts
/code-review high bookmarkify-admin/src/views/website/icon-verdict/
```

Batches are ordered highest blast-radius first, with **F3 (cross-service type/enum alignment) deliberately
last** — it's a consistency sweep, most useful after the batches that might reshape a VO have already run.
Findings from each batch are appended to `CODE-REVIEW-FINDINGS.md` before starting the next batch, so a fresh
session can resume without re-discovering what a prior batch already found.

## Review tasks

Progress: **5 / 12 batches completed.** Completion status is synchronized with
`CODE-REVIEW-FINDINGS.md`.

- [x] **F1 — 加书签全流程 (add-bookmark end-to-end)**
  - [x] api: `BookmarkController`, `ApiServiceImpl`, async parse event listener,
    `drainStuckLoading`, `ParseLock`
  - [x] web: `stores/bookmark.store.ts`, `stores/websocket.store.ts`, `pages/index.vue`
    (add flow + `HOME_ITEM_UPDATE`), `components/launchpad/AddOneDialog.vue`
- [x] **F2 — 图标 / 站点资产 (icons & site assets)**
  - [x] api: `AssetRolePolicy`, `IconResolver`, `CoverResolver`, `SiteAssetIngestor`,
    `SiteAssetWriter`, `SiteAssetQuery`, `AssetUrlSigner`, `OssUtils.signAsset`
  - [x] web: `composables/useBookmarkIcon.ts`, icon rendering in `pages/index.vue`
  - [x] admin: `views/website/icon-verdict/`, `api/icon.ts`
- [x] **A1 — 活性巡检与调度 (liveness sweeps & scheduling)**
  - [x] api: sweep services, `LivenessPolicy`, cron config, `ScrapeTargetGuard`
- [x] **A2 — OSS 对象治理 (OSS object governance)**
  - [x] api: `OssReconcileServiceImpl`, `OrphanCleanupService`, `oss_object` ledger
- [ ] **F4 — 鉴权与会话 (auth & session)**
  - [ ] api: satoken `USER`/`ADMIN` realms, `/auth/track`, interceptors
  - [ ] web: `stores/auth.store.ts`, `pages/auth/`, `pages/login.vue`,
    `composables/useGithubOAuth.ts`, `composables/useGoogleOAuth.ts`
  - [ ] admin: `views/_core/authentication/`
- [x] **W1 — 布局 / 置顶 / 文件夹 / 重新归类**
  - [x] web: layout nodes, pin/pin-order, reclassify feature
- [ ] **M1 — 书签管理 (bookmark admin)**
  - [ ] admin: `views/bookmark/`, `views/website/page/`, `views/website/site/`,
    `views/bookmark-collection/` (excl. icon-verdict, covered by F2)
- [ ] **A3 — 外围功能 (peripheral features)**
  - [ ] api: similar-bookmarks, website feedback, `config_change_log`, `ai_call_log`,
    admin-only endpoints not covered above
- [ ] **M2 — 用户管理 / 反馈 / scrapper 可观测性 / AI 日志**
  - [ ] admin: `views/user/`, `views/feedback/`, `views/scrapper/`, `views/ai/`
- [ ] **W2 — 其余页面 (remaining pages)**
  - [ ] web: `pages/welcome.vue`, `pages/share/`, `pages/setting.vue`, everything not
    covered above
- [ ] **M3 — 系统配置 (system config pages)**
  - [ ] admin: `views/system/`, `views/subsystem/`
- [ ] **F3 — 跨服务类型 / 枚举对齐 (cross-service type & enum alignment)**
  - [ ] api: shared enum source, `SharedEnumContractTest`,
    `./gradlew generateSharedEnums` drift
  - [ ] web: `typing/*.ts` hand-copied VOs vs. `typing/enums.generated.ts`
  - [ ] admin: `src/api/*.ts` hand-copied VOs vs. `src/api/enums.generated.ts`,
    `pnpm typecheck` health

## Execution notes

- Run batches sequentially, one `/code-review high <path>` per path listed in a batch, one batch at a time.
- Append findings to `CODE-REVIEW-FINDINGS.md` immediately after each batch so progress survives a context reset.
  For multi-path batches, group the findings by path.
- Re-check drift-prone seams called out in `CLAUDE.md` while reviewing the relevant batch: `SharedEnumContractTest`
  (F3), `contract/scrape-response.sample.json` (F2 — Kotlin side only, `ScrapeContract.kt`; the Rust side is out of
  scope per the scrapper exclusion below), `OssReconcileServiceImpl.collectReferencedKeys` (F2/A2),
  `OrphanCleanupService` ownership registry (A2).
- F1/F2/F4 are genuinely cross-service: prefer reviewing all paths of a batch before moving to the next batch,
  so findings that only make sense together (e.g. an enum value the backend added vs. the frontend `switch` that
  doesn't handle it) surface in the same sitting.

## Scrapper (excluded, separate audit)

`bookmarkify-scrapper` is left out of this plan on purpose, not by oversight — but it deserves its own audit,
not a "yet another batch" bolt-on, because of its shape: it's the one Rust surface in an otherwise Kotlin/
TypeScript codebase, and `CLAUDE.md`'s own gotchas section is dominated by scrapper incidents (headless "no
pages returned", screenshots silently missing CSS/fonts, the anti-bot fallback ladder, cache/breaker TTLs tuned
against production incident data). A reviewer working through the same "high effort, path target" pass without
that context is likely to flag several of these as bugs when they're deliberate, already-incident-tested
behavior — worth a pass with the relevant `CLAUDE.md` gotchas loaded going in, run separately from this plan.
