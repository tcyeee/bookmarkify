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
Findings from each batch get written to `.context/code-review/<batch-id>.md` before starting the next batch, so
a fresh session can resume without re-discovering what a prior batch already found.

## Batches

| # | Batch | api | web | admin |
|---|---|---|---|---|
| F1 | 加书签全流程 (add-bookmark end-to-end) | `BookmarkController`, `ApiServiceImpl`, async parse event listener, `drainStuckLoading`, `ParseLock` | `stores/bookmark.store.ts`, `stores/websocket.store.ts`, `pages/index.vue` (add flow + `HOME_ITEM_UPDATE`), `components/launchpad/AddOneDialog.vue` | — |
| F2 | 图标 / 站点资产 (icons & site assets) | `AssetRolePolicy`, `IconResolver`, `CoverResolver`, `SiteAssetIngestor`, `SiteAssetWriter`, `SiteAssetQuery`, `AssetUrlSigner`, `OssUtils.signAsset` | `composables/useBookmarkIcon.ts`, icon rendering in `pages/index.vue` | `views/website/icon-verdict/`, `api/icon.ts` |
| A1 | 活性巡检与调度 (liveness sweeps & scheduling) | sweep services, `LivenessPolicy`, cron config, `ScrapeTargetGuard` | — | — |
| A2 | OSS 对象治理 (OSS object governance) | `OssReconcileServiceImpl`, `OrphanCleanupService`, `oss_object` ledger | — | — |
| F4 | 鉴权与会话 (auth & session) | satoken `USER`/`ADMIN` realms, `/auth/track`, interceptors | `stores/auth.store.ts`, `pages/auth/`, `pages/login.vue`, `composables/useGithubOAuth.ts`, `composables/useGoogleOAuth.ts` | `views/_core/authentication/` |
| W1 | 布局 / 置顶 / 文件夹 / 重新归类 | — | layout nodes, pin/pin-order, reclassify feature | — |
| M1 | 书签管理 (bookmark admin) | — | — | `views/bookmark/`, `views/website/page/`, `views/website/site/`, `views/bookmark-collection/` (excl. icon-verdict, covered by F2) |
| A3 | 外围功能 (peripheral features) | similar-bookmarks, website feedback, `config_change_log`, `ai_call_log`, admin-only endpoints not covered above | — | — |
| M2 | 用户管理 / 反馈 / scrapper 可观测性 / AI 日志 | — | — | `views/user/`, `views/feedback/`, `views/scrapper/`, `views/ai/` |
| W2 | 其余页面 (remaining pages) | — | `pages/welcome.vue`, `pages/share/`, `pages/setting.vue`, everything not covered above | — |
| M3 | 系统配置 (system config pages) | — | — | `views/system/`, `views/subsystem/` |
| F3 | 跨服务类型 / 枚举对齐 (cross-service type & enum alignment) | shared enum source, `SharedEnumContractTest`, `./gradlew generateSharedEnums` drift | `typing/*.ts` hand-copied VOs vs. `typing/enums.generated.ts` | `src/api/*.ts` hand-copied VOs vs. `src/api/enums.generated.ts`, `pnpm typecheck` health |

## Execution notes

- Run batches sequentially, one `/code-review high <path>` per path listed in a batch, one batch at a time.
- Persist findings to `.context/code-review/<batch-id>.md` (e.g. `.context/code-review/F1.md`) immediately after
  each batch so progress survives a context reset. For multi-path batches, one file holds all paths' findings,
  grouped by path.
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
