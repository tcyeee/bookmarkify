# Code Review Plan — web / admin / api

Full-codebase review of `bookmarkify-web`, `bookmarkify-admin`, `bookmarkify-api` (scrapper excluded), split into
batches by domain boundary rather than by directory size — cross-file invariants (e.g. `OrphanCleanupService`'s
table registry, `OssReconcileServiceImpl.collectReferencedKeys`) get missed if a module is split mid-boundary.

Each batch is run with the `/code-review` skill at **high** effort (correctness bugs + reuse/simplification/
efficiency cleanups). Since these are full-codebase audits and not diffs against `main`, invoke with a **path
target** rather than the default diff target, e.g.:

```
/code-review high bookmarkify-api/src/main/kotlin/.../server/asset/
```

Batches are ordered highest blast-radius first. Findings from each batch get written to
`.context/code-review/<batch-id>.md` before starting the next batch, so a fresh session can resume without
re-discovering what a prior batch already found.

## bookmarkify-api

| # | Batch | Scope |
|---|---|---|
| A1 | Add-bookmark main flow | `BookmarkController`, `ApiServiceImpl`, async parse event listener, `drainStuckLoading`, `ParseLock` |
| A2 | Site assets & scrapper contract | `AssetRolePolicy`, `IconResolver`, `CoverResolver`, `SiteAssetIngestor`, `SiteAssetWriter`, `SiteAssetQuery`, `AssetUrlSigner`, `OssUtils.signAsset` |
| A3 | Liveness sweeps & scheduling | sweep services, `LivenessPolicy`, cron config, `ScrapeTargetGuard` |
| A4 | OSS object governance | `OssReconcileServiceImpl`, `OrphanCleanupService`, `oss_object` ledger |
| A5 | Auth & session | satoken `USER`/`ADMIN` realms, `/auth/track`, interceptors |
| A6 | Peripheral features | similar-bookmarks, website feedback, `config_change_log`, `ai_call_log`, admin-only endpoints |

## bookmarkify-web

| # | Batch | Scope |
|---|---|---|
| W1 | Desktop core UI + WebSocket | main bookmark grid/list, `HOME_ITEM_UPDATE` handling |
| W2 | Layout / pinning / folders | layout nodes, pin/pin-order, reclassify feature |
| W3 | Cross-service types | `typing/` hand-copied VOs vs. `typing/enums.generated.ts` drift |
| W4 | Auth/session + remaining pages | everything not covered above |

## bookmarkify-admin

| # | Batch | Scope |
|---|---|---|
| M1 | Bookmark admin | list/detail, icon verdict audit board |
| M2 | User mgmt / feedback inbox / scrapper observability | respective admin pages |
| M3 | System config pages | config editors |
| M4 | Cross-service types + typecheck health | `src/api/*.ts` hand-copied VOs, `pnpm typecheck` status |

## Execution notes

- Run batches sequentially, one `/code-review high <path>` per batch.
- Persist findings to `.context/code-review/<batch-id>.md` (e.g. `.context/code-review/A1.md`) immediately after
  each batch so progress survives a context reset.
- Re-check drift-prone seams called out in `CLAUDE.md` while reviewing the relevant batch: `SharedEnumContractTest`
  (W3/M4), `contract/scrape-response.sample.json` (A2), `OssReconcileServiceImpl.collectReferencedKeys` (A2/A4),
  `OrphanCleanupService` ownership registry (A4).
