# Code Review Findings

Results of the batched review described in `CODE-REVIEW-PLAN.md`. Each batch is run as
`/code-review high <path>` per path listed in the plan's task list — a **path-target audit against
the current tree**, not a diff against `main` (this branch carries no code changes, only docs).
This file is the durable, committed record; append each completed batch here before moving to the next one.

Progress: **F1, F2, A1, A2, W1 done.** Remaining batches (F4, M1, A3, M2, W2, M3, F3) not yet run.

---

# F1 — 加书签全流程 (add-bookmark end-to-end)

Paths reviewed:
- api: `BookmarkController.kt`, `ApiServiceImpl.kt`, `BookmarkServiceImpl.kt` (drainStuckLoading + parse flow), `BookmarkParseEventListener.kt`, `ParseLock.kt`
- web: `stores/bookmark.store.ts`, `stores/websocket.store.ts`, `pages/index.vue`, `components/launchpad/AddOneDialog.vue`

## api/BookmarkController.kt

1. **[correctness, high] `POST /bookmark/list` lets any authenticated caller read another user's bookmarks via a client-controlled `uid`.**
   `BookmarkController.kt:63` — `list()` forwards the request body unchanged to `bookmarkService.allOfMyBookmark(BaseUtils.uid(), params)`. `AllOfMyBookmarkParams.uid` (`entity/Request.kt:186`) defaults to `BaseUtils.uid()` but is a mutable field, overridable by Jackson when present in the JSON body. `BookmarkServiceImpl.allOfMyBookmark` (line 236) builds the actual page query from `params.toWrapper(restrictIds)`, whose `toWrapper()` filters `eq(BookmarkEntity::uid, uid)` using `this.uid` — the attacker-supplied value, not the session uid passed into the function. The controller's real `BaseUtils.uid()` is only used for the optional duplicatesOnly/invalidOnly restriction sets.
   Repro: `POST /bookmark/list` with body `{"uid":"<victim-uid>","currentPage":1,"pageSize":50}` returns the victim's full paginated bookmark list (titles, descriptions, pageId, layoutNodeId, pin state).
   **Security finding — recommend fixing promptly.**

2. **[correctness, medium] `/pin` uses the default `@Throttle` (keyed by uid+method only, not per-item), so pinning two different bookmarks within 1s fails the second request.**
   `BookmarkController.kt:175` — `ThrottleAspect.around` keys the Redis SETNX lock as `throttle:<uid>:...pin`, ignoring `params.linkId`. Pinning bookmark A then bookmark B within 1000ms hits the still-live key and throws `E107`("请求过于频繁"), which the web client surfaces as an error toast — even though the two pins target unrelated bookmarks. The same file's comments for `/open` and `/pinSort` explicitly call out and avoid this exact class of bug by omitting `@Throttle`; `/pin` was missed.

## api/ApiServiceImpl.kt

3. **[correctness, low] `scrape()` validates/logs a locally-derived URL but serializes the original (possibly different) `request.url` over the wire.**
   Line 153/170 — if a future/indirect caller invokes `scrape(domain, request)` with `request.url` blank and a distinct `domain`, `ScrapeTargetGuard`/logging validate the resolved domain URL, but the actual POST body still carries the blank `url`, so the scrapper gets an empty URL while logs claim a different target. All current call sites happen to pass `domain == request.url`, so this is latent, not yet triggered.

4. **[correctness, low] `PING_READ_TIMEOUT_MS` (15s) comment assumes the scrapper sends "only one HEAD request", but `/ping` documented behavior includes redirect-following and HEAD→GET retry on 404/405/410/501.**
   Line 701 — a slow multi-hop site that also needs HEAD→GET re-verification can exceed 15s even with fast individual hops, causing the probe to misclassify as UNKNOWN rather than confirming liveness.

5. **[simplification, low] `stripJsonFence` helper exists but `parseFolderAssignments`/`parseSimilarSites` still inline the identical fence-stripping logic.**
   Line 610 — three copies of the same `.removePrefix("```json")...` chain; a future change to the stripping rule risks being applied incompletely.

6. **[efficiency, low] Regex objects recompiled per call instead of hoisted to constants.**
   Line 636 — `extractLinks`'s per-link `Regex("^https?://.*")` (up to 300x per call) and `buildUrl`'s equivalent regex are recompiled on every invocation, including every hourly liveness-sweep probe.

## api/BookmarkServiceImpl.kt

7. **[correctness, medium] `importBookmarkFile` flattens a folder to ROOT based on the post-filter bookmark count, not the original folder size — silently drops named folders when filtering leaves exactly one item.**
   Line 338 — a Chrome-export folder with 3 bookmarks where 2 are filtered out (already-saved / over length) leaves `kept.size == 1`, which triggers the `when (kept.size) { 1 -> FolderSlice(null, ...) }` branch regardless of the original folder name. The surviving bookmark lands on the desktop root instead of inside a newly created folder — common right after a duplicate-skip import.

8. **[correctness, high — needs verification] `BookmarkServiceImpl` extends MyBatis-Plus `ServiceImpl`, which declares its own `protected final Log log` field, potentially shadowing the app's SLF4J `log` extension property for all 73 `log.*` call sites in the file.**
   Line 108 — CLAUDE.md documents this exact trap for `ServiceImpl` subclasses, and sibling classes (`SiteServiceImpl`, `UserServiceImpl`) work around it with an explicit `private val logger = LoggerFactory.getLogger(javaClass)`. If confirmed, every log line in this file — including `reportStuckLoading`'s warn line, which the file's own comments call "整条添加链路唯一真正的 SLI" — silently goes through MyBatis's internal Log abstraction instead of the app's normal logging path. **Verify against actual runtime logs/config before treating as certain** (Kotlin member-resolution shadowing was confirmed via bytecode inspection by the reviewing agent, but cross-check with an actual log line from this class in practice).

9. **[correctness, medium] `captureScreenshot` applies the 35-day futile-screenshot suppression lock even on `isScrapperUnavailable()` (our own outage), unlike `parseByApi` which explicitly exempts that case elsewhere in this file.**
   Line 779 — a transient scrapper restart/blip during a screenshot batch causes every touched page to get a 35-day suppression window even though a retry seconds later would succeed. `ParseLock.screenshot(pageId)` is only released on success (line 802), never on this failure path.

10. **[correctness, low] `parseAndSave` passes a nullable `selectById(pageId)` into a non-null `parseBookmark(bookmark: PageEntity, ...)` parameter with no guard, unlike sibling methods `enrich`/`captureScreenshot` which both handle a missing entity explicitly.**
    Line 710 — if the page row is deleted between `checkAll()` publishing the event and the listener processing it, this throws an NPE (caught by the listener's outer `runCatching`, so no crash) but surfaces as a raw stack trace instead of the clean `log.debug("...跳过")` pattern used elsewhere in the file for the same condition.

11. **[efficiency, low] `checkNsfwForAll()` loads the entire `page` table into memory via `list()` with no batching/streaming for an admin-triggered action.**
    Line 1064 — on a production-sized table this risks a large GC pause/OOM, inconsistent with the batch-limited patterns (`checkAll`/`drainStuckLoading`) used elsewhere in the same file.

## api/BookmarkParseEventListener.kt

12. **[correctness, low] `onScreenshot` logs `it.message` instead of the throwable `it`, discarding the stack trace that every other handler in this file preserves.**
    Line 55 — for exceptions with a null message (common for NPEs/IOExceptions/timeouts), the log line reads `err=null` with no stack trace or exception type, making a production screenshot failure undiagnosable.

13. **[simplification, low] The `runCatching {...}.onFailure {...}.let {}` pattern is duplicated verbatim across all 5 methods in this file and again in `ShareAiReviewEventListener`/`SimilarColdComputeListener`.**
    Line 22 — seven near-identical call sites obscure the pattern's actual purpose (forcing `Unit` return so Spring's `@EventListener` doesn't re-publish a `Result` as a new event) and invite drift — finding #12 is exactly this kind of drift.

14. **[correctness, low] `runCatching` catches `Throwable`, not `Exception`, so a JVM `Error` (OOM, StackOverflow) is silently swallowed and logged like an ordinary failure.**
    Line 22 — plausible under the documented headless-browser/screenshot OOM risk; the executor keeps scheduling more work on a JVM that may already be in a corrupted state instead of surfacing a fatal signal.

## api/ParseLock.kt

15. **[correctness, medium] `acquire()` fails open on *any* single-command Redis exception, not just a genuine outage — a transient per-call error can grant a duplicate lock while the real lock is still legitimately held.**
    Line 54 — a brief connection-pool saturation on one `setIfAbsent` call triggers `getOrElse` to return a fresh "acquired" token even though Redis and the real lock are both fine, letting two `SiteAssetWriter` delete-then-insert transactions interleave for the same page — the exact corruption this lock exists to prevent.

16. **[correctness, medium] `SimilarBookmarkServiceImpl.maybeTriggerCold()` uses `ParseLock.isHeld()` as a real business gate (skip budget charge) even though its own KDoc says it is "display only" and must not be used for mutual-exclusion decisions.**
    `SimilarBookmarkServiceImpl.kt:187` — the real mutex is only enforced later, asynchronously, in `runColdCompute`'s `tryAcquire`. Two near-simultaneous triggers for the same site can both pass the `isHeld` check, both consume the daily cold-compute budget, but only one actually runs — the loser's budget is spent for zero effect.

17. **[docs, low] Class-level KDoc claims exactly two release strategies, but `screenshot()`'s conditional-release pattern (acquire, release only on success, else TTL backoff) fits neither.**
    Line 21 — risk that a future key is modeled on the documented binary choice and reintroduces a stuck-lock or needless-retry bug that the screenshot pattern was specifically designed to avoid.

## web/stores/bookmark.store.ts

18. **[correctness, medium] `dedupeLayout()` — the documented runtime safety net for cross-card drag desync ("历史踩坑#10") — is never invoked anywhere in the app; only a separate inline reimplementation runs once, at hydration.**
    Line 700 — grep confirms zero call sites for `this.dedupeLayout(`. A duplicate-id-across-lists desync (e.g. from cross-tab drag) silently inflates a folder's child count and stays wrong for the rest of the session; it's only fixed on next page reload via `afterHydrate`'s separate reimplementation, which also lacks the `console.error('[重大事故]')` alert the real function would have produced.

19. **[correctness, low] `replaceContent()`/`replaceFolder()` (WS resolution paths) never call `clearResolutionWatch()`, despite its own doc comment saying it should be called on WS update.**
    Line 364 — when a LOADING node resolves via WebSocket before its next `watchForResolution` timeout, the stale `setTimeout` still fires later; the type-guard makes it a no-op for `pendingTimeouts`, but `resolutionAttempts[nodeId]` is never cleared for WS-resolved nodes. In a long-lived session (launchpad tabs left open for days), this accumulates one permanent entry per bookmark ever added.

20. **[dead-code, low] `createFolderLocal()`'s only caller (`archive/bookmark-management/index.vue`) is outside the live routed app; it also only strips ids from the root order list, not from other folders' lists.**
    Line 624 — latent bug if ever reconnected: moving an item that's inside an existing folder into a new folder would duplicate it across two `order` lists (same class of bug as #18) without being removed from the source.

21. **[correctness, low] `setPinnedLocal` computes pin position via `Math.max(-1, ...this.pinnedNodes.map(...))`, which spreads the whole array as call arguments and can throw `RangeError` once the pinned set is large enough.**
    Line 447 — tens of thousands of pinned bookmarks (or a corrupted/duplicated state from #18) can exceed the engine's argument-spread limit (~65k-128k), throwing synchronously and failing the pin with an unhandled exception.

## web/stores/websocket.store.ts

22. **[correctness, medium] `reconnect()`'s deferred callback silently abandons the retry chain if the auth token is momentarily unavailable, with no automatic path to resume.**
    Line 187 — if `authStore.account?.token` is transiently empty when the scheduled callback fires (token not yet rehydrated, race during logout/relogin), it increments `reconnectAttempts` and returns without calling `connect()` or rescheduling. No new socket → no further `onclose` → no further retry. `forceReconnect()` has the identical early return, so recovery depends on the user triggering `online`/`visibilitychange` after the token becomes valid.

23. **[correctness, low] `SHARE_STATUS_CHANGED` handler ignores `message.data.status` and always shows a "rejected/taken down" toast regardless of the actual transition.**
    Line 131 — `ShareStatus` covers NORMAL/EXPIRED/CANCELLED/ADMIN_TAKEDOWN/REVIEW_REJECTED, but the frontend never branches on it. Currently harmless (backend only emits this for REVIEW_REJECTED today), but latent: any future push for e.g. ADMIN_TAKEDOWN would show a misleading review-rejection message.

24. **[correctness, low] `connect()`'s token-change path closes the old socket without calling `stopHeartbeat()`, leaving the previous connection's ping/watchdog intervals running until the new socket's `onopen` fires.**
    Line 58 — brief, mostly-harmless overlap against shared mutable state during every re-login/token-swap; fragile under future refactors.

25. **[dead-code, low] The `actions` Map state field is declared/typed but never populated or read; actual dispatch is a hardcoded if/else chain in `onmessage`.**
    Line 30 — misleading to future readers per its own "按类型分发回调" comment.

## web/pages/index.vue

26. **[correctness, medium] A local `BookmarkSearchVO` interface shadows and diverges from the canonical one in `typing/bookmark.ts`, dropping `role`/`quality`/`isVector`/`monogram` and making `logo` optional instead of required.**
    Line 293 — `bookmarksSearch()` returns untyped `Array<any>`, so the `as BookmarkSearchVO[]` cast at line 632 is checked against this weaker local type. A suggested site with `monogram: true` (empty `url`) falls back to a generic globe icon instead of the colored monogram tile every other bookmark renders. Also violates CLAUDE.md's "Define in `typing/`, barrel-export from `typing/index.ts`" convention. (Also relevant to F2 — icon rendering.)

27. **[correctness, low] The 建议书签 "no results" message can flash for up to 500ms before the debounced search actually runs, because `isSuggesting` is only set on fetch, not on schedule.**
    Line 640 — first-keystroke UX flicker: "暂无来自其他用户的匹配书签" shows during the pending debounce window, then is replaced once results arrive.

28. **[duplication, low] Folder-card drag-and-drop scaffolding (classify-by-half-point, drag/reset state, register/monitor) is re-implemented near-identically in `pages/index.vue`, `BookmarkFolderCard.vue`, and `PinnedBookmarkGrid.vue`.**
    Line 395 — three hand-written copies differing only in axis/kind tag; a bugfix to drag/drop mechanics has to be applied and re-verified three times, and has already drifted slightly between them.

29. **[correctness, low] The waterfall column-height estimate ORs in `bookmarkStore.isFolderCollapsed(folder.id)` even when a folder already has an explicit `collapsed` value, which can disagree with what `BookmarkFolderCard` actually renders.**
    Line 375 — `BookmarkFolderCard`'s own `collapsed` computed only falls back to the store when `initialCollapsed === undefined`. A stale `true` entry in the legacy `collapsedFolders` map (described in `bookmark.store.ts` as a "旧版本缓存的兜底") can make the waterfall bucket a visually-expanded folder as height 1, unbalancing the layout.

## web/components/launchpad/AddOneDialog.vue

30. **[correctness, low] Validation trims the input before checking `isBookmarkableUrl`, but the untrimmed raw value is what's actually sent on submit — a leading/trailing-whitespace URL can pass validation yet fail on submit.**
    Line 233 — e.g. a URL pasted with a leading space from Slack. `isBookmarkableUrl` trims internally and shows green/valid; `addOne()` sends the untrimmed string; backend `WebsiteParser.urlWrapper`'s regex match isn't trimmed either, so it mis-prepends another `https://`, producing a malformed URL and a generic `E303` error toast for input the UI had just called valid.

31. **[correctness, low] `handleSearch` has no request-sequencing guard — overlapping in-flight `bookmarksSearch` calls can resolve out of order, letting a stale response overwrite a newer query's results.**
    Line 141 — classic race: typing "a" then quickly extending to "abcdef" can show results for "a" if that response happens to land later.

32. **[correctness, low] The success `setTimeout` that closes the dialog isn't scoped to the operation that scheduled it — reopening the dialog within the 500ms window force-closes the new session.**
    Line 247 — add succeeds, user manually closes/reopens the dialog within 500ms to add a second bookmark; the stale timeout still fires and abruptly closes the freshly reopened dialog.

33. **[correctness, low] `showEmptyState` can render "暂无可关联书签" before the debounced search has run, because `isSearching` only flips inside the debounced callback, not when scheduled.**
    Line 218 — visible flicker/misleading message on every fresh valid-URL entry, same root cause as finding #27.

34. **[minor, low] `checkInput`'s early-return for an emptied field clears local state but doesn't cancel a previously scheduled debounced `handleSearch`, so a stale search can still fire after the input was cleared.**
    Line 265 — wasted network call, currently harmless only because the results container is hidden by `v-if="data.input"`.

### F1 Summary

- **Security-relevant / recommend prioritizing:** #1 (IDOR on `/bookmark/list`)
- **High-confidence correctness bugs:** #2, #7, #9, #15, #16, #18, #22, #26
- **Needs manual verification before acting:** #8 (ServiceImpl logger shadowing — bytecode-confirmed by reviewer, but cross-check against a real log line from `BookmarkServiceImpl` in practice)
- **Lower-severity / cleanup:** the rest (dead code, duplication, minor UX flicker, efficiency)

---

# F2 — 图标 / 站点资产 (icons & site assets)

Paths reviewed:
- api: `server/asset/` (AssetRolePolicy, IconResolver, CoverResolver, SiteAssetIngestor, SiteAssetWriter, SiteAssetQuery, AssetUrlSigner, AssetVerdictRecomputeService, IconVerdictAuditService, BookmarkDisplayPolicy, ResolvedIcon), `utils/OssUtils.kt`
- web: `composables/useBookmarkIcon.ts` (icon rendering in `pages/index.vue` covered under F1 finding #26)
- admin: `views/website/icon-verdict/index.vue`, `api/icon.ts`

## api/server/asset/

1. **[correctness, high] Every regular content re-crawl deletes a page's SCREENSHOT asset, then forces an unnecessary 30s headless re-capture — the "skip if already has a screenshot" optimization is permanently defeated for every bookmark that ever had one.**
   `SiteAssetWriter.kt:99` — `replaceAssets(PAGE, pageId, pageAssets)` full-replaces all PAGE-owned rows with the current scrape's projection, which never includes a screenshot (screenshots come from a separate async path, `ScrapperConfig.screenshot` defaults `false`). On the 30-day content-refresh cycle, `parseByApi`'s `persist(...)` commits this replace, wiping the existing SCREENSHOT row (and orphaning its OSS object). Since `parseStatus == SUCCESS`, `BookmarkScreenshotEvent` fires immediately after; `captureScreenshot`'s skip-guard (`siteAssetQuery.assetsOf(pageId).any { isScreenshot }`) now finds nothing, so it doesn't skip — every 30-day cycle re-captures from scratch on the single-threaded screenshot pool, for every bookmark with a screenshot, plus a window where the page's cover is simply missing.
   **Recommend fixing — this defeats a documented, purpose-built optimization and has a real user-visible symptom (missing cover during the gap).**

2. **[correctness, medium] `divergesFromSite`'s 60-day freshness gate relies on `fetchedAt` being bumped on every re-crawl, but `SiteAssetWriter`'s `isIdenticalToExisting` short-circuit skips the write (and the `fetchedAt` bump) whenever a site's icons haven't changed — which is the common case for stable sites.**
   `AssetRolePolicy.kt:176` — a favicon captured on day 0 that never changes keeps `fetched_at` pinned at day 0 across every subsequent re-crawl. By day 61, a genuinely different-product deep link under the same domain computes `freshest = day0`, and the 60-day-old-or-fresher check fails, so multi-product-domain detection silently stops firing for any long-lived stable bookmark — well before a truly stale snapshot would occur. This contradicts the class comment's claim that "a still-bookmarked homepage always falls inside this window."

3. **[correctness, low] `IconResolver.resolveForDisplay` in LIST mode calls `build(...)` twice per page (once for `icon`, once for `tileIcon`), double-counting the shared `hotlinked` counter against a denominator (`ids.size`) counted once — the aggregated warning log can report >100%.**
   `IconResolver.kt:86` — e.g. 20 LIST-mode bookmarks all hotlinking for both resolutions logs "40/20 个书签回退到源站直连图片", a mathematically impossible fraction that undermines the diagnostic (added specifically to replace a previously silent degradation).

## api/utils/OssUtils.kt

4. **[correctness, medium — security-adjacent] `uploadUserFile`'s content-type validation is silently skipped when the multipart part has no `Content-Type` header, because the null-safe chain short-circuits entirely on null instead of rejecting it.**
   Line 484 — a client that omits `Content-Type` on the file part (trivial with non-browser HTTP clients) bypasses `CommonException(E103)` entirely; only the loose extension/length check still applies, so an arbitrary non-image file with an image-like extension is accepted into the OSS bucket (avatar/background paths).

5. **[correctness, low] `ownOssObjectKey` unconditionally strips the `oss/` proxy-path prefix regardless of which "own" host actually matched, conflating the proxy domain with the direct/custom OSS domain.**
   Line 345 — if a stored key legitimately begins with `oss/` (e.g. a folder literally named `oss`) and is referenced via the direct custom domain rather than the proxy, the prefix is still stripped, truncating the real key and producing a broken image with no surfaced error.

6. **[correctness, low] `OssObjectVO`'s constructor calls `OssUtils.signAsset` without passing `immutable = entity.immutable`, so content-addressed/write-once objects get the short `DEFAULT_TTL_MILLIS` signature instead of the long-lived treatment their own `immutable` flag calls for.**
   `Response.kt:1141` — inconsistent with `OssObjectEntity.signedUrl(size)`, which already wires this correctly but isn't used at this call site; a preview link in the admin OSS ledger for an immutable object gets an unnecessarily short-lived signature.

7. **[correctness, low] `signAsset` falls back to returning the raw, un-prefixed object key when `signWithResize` throws — not a valid absolute URL for the common "bare key" input form.**
   Line 311 — if `generatePresignedUrl` throws for some object key, callers get back a bare key string (e.g. `scrapper/asset/abc123`) used directly as `<img src>`, rendering a broken relative-path image instead of something callers could branch on (e.g. `null`).

8. **[dead-code, low] `resizeAndSignImg` has zero callers anywhere in `src/main` or `src/test`.**
   Line 465 — its comment references `signWithResize`'s extension guard as if actively used; unexercised code that could silently break without any test catching it.

9. **[docs, low] `signWithResize`'s docstring claims remaining validity is `[TTL/2, TTL]`, but the window-alignment formula actually guarantees `(TTL, 1.5·TTL]` — the documented bound doesn't match the code.**
   Line 406 — a future "fix" aligning the code to the wrong documented bound would under-promise and could violate the actual guarantee callers rely on (usable for at least the requested TTL).

## web/composables/useBookmarkIcon.ts

10. **[correctness, low] `monogramChar` uses `String.charAt(0)`, which returns only one UTF-16 code unit and breaks on characters outside the BMP (emoji, some rare CJK).**
    Line 113 — a bookmark titled e.g. "🔥 Awesome Tool" produces a lone surrogate half; the monogram tile renders a broken/tofu glyph. Reachable in practice since many real site titles start with an emoji.

11. **[correctness, low] The previous blob's object URL is revoked synchronously before the replacement image has finished loading, while `displaySrc` (still bound to the `<img>`) keeps pointing at the now-invalid URL until the async fetch resolves.**
    Line 73 — when a live bookmark's `logo.url` changes in place (e.g. a WebSocket `HOME_ITEM_UPDATE` from a periodic re-crawl), `releaseDisplayObjectUrl()` revokes the old blob URL immediately but `displaySrc.value` isn't updated until the new one is ready. Given this app's tabs-left-open-for-days usage model, if the browser re-decodes the `<img>` from `src` during this window (e.g. tab returns from background, image evicted from decode cache), the request against the revoked `blob:` URL fails, firing `@error` → incorrectly falling back to the monogram even though a valid new image is on the way.

*(Noted but not raised as standalone findings: two independent `watch()` calls rely on same-flush registration-order execution — fragile implicit coupling, not currently broken; optional chaining throughout is defensive-only since `BookmarkShow.logo` is a required field.)*

## admin/views/website/icon-verdict/index.vue + admin/api/icon.ts

12. **[correctness, high] `POST /admin/icon/recompute-verdict` — which the backend's own KDoc says "must be an action a human presses, with logs, repeatable" — has no frontend trigger anywhere in admin.**
    `icon-verdict/index.vue:367` and `api/icon.ts:113` — `AdminIconController.recomputeVerdict` (added alongside this page, commit `4ff11403`; `docs/ICON-DISPLAY-TODO.md:114` specifies the entry point should be "后台一个按钮 + 一条可重跑的脚本") is unreachable from the UI: `api/icon.ts` only exports `getAdminIconVerdictOverviewApi`/`getAdminIconVerdictSitesApi`, no wrapper for recompute-verdict exists, and a repo-wide grep for `recompute-verdict`/`recomputeVerdict`/`AssetVerdictRecomputeReport` finds nothing else under `bookmarkify-admin/src/`. After any `AssetRolePolicy` rule change, an operator has no in-panel way to run (or dry-run) the recompute and must curl the endpoint directly — exactly what the design note was written to prevent.
    **Recommend prioritizing — feature gap on a deliberately-designed operational control.**

13. **[correctness, medium] The drill-down grid has no pagination and never sends `limit`, so it silently defaults to the backend's 300-row cap while the overview counts above it are computed over the unlimited full set.**
    `index.vue:183` — `pagerConfig` is `{ enabled: false }`, no escape hatch. Once a bucket's true count exceeds 300 (current baseline ~153 sites and growing), the overview card shows the real count (e.g. 340) but the grid silently renders only 300 rows with no indicator — the page's own stated purpose (a trustworthy count) breaks silently.

14. **[correctness, low] The "改进空间" (salvageable) summary card — its own tooltip calls it "the number most worth watching" — is not clickable, unlike the four verdict buckets next to it.**
    `index.vue:310` — every other summary tile drills into its rows on click (per the code's own comment, "看到「40」之后第一个动作必然是「哪 40 个」"); this one is a plain `<div>` with no `@click`, forcing a manual flip of the separate "只看有救的" filter switch instead.

15. **[correctness, low] The "6+" histogram tail label hardcodes the backend's private `HISTOGRAM_TAIL = 6` constant by hand, instead of receiving it from the API the way `tileMinSize` is.**
    `index.vue:352` — `IconVerdictOverviewVO` explicitly ships `tileMinSize` specifically so the frontend never hardcodes thresholds (comment: "由后端下发而不是前端写死"), but the histogram tail label duplicates `IconVerdictAuditService.HISTOGRAM_TAIL` by hand. If that constant ever changes, the frontend keeps showing "6" as a bare number instead of the new tail, silently mislabeling the bucket — same hand-copied-VO risk CLAUDE.md calls out, landed on a magic number instead of a field.

### F2 Summary

- **Recommend prioritizing:** #1 (screenshot wiped + re-captured every re-crawl — real user-visible symptom), #12 (recompute-verdict has no UI, contradicts its own design doc)
- **Security-adjacent:** #4 (content-type check bypassable when header omitted)
- **High-confidence correctness bugs:** #1, #2, #4, #5, #6, #12, #13
- **Lower-severity / cleanup:** #3, #7, #8, #9, #10, #11, #14, #15

---

# A1 — 活性巡检与调度 (liveness sweeps & scheduling)

Paths reviewed:
- api: `server/liveness/` (`LivenessSweepService`, `LivenessPolicy`, `PageScheduleWriter`, `ILivenessSweepService`)
- api: `server/impl/BookmarkPingLogServiceImpl.kt`, `server/impl/BookmarkLivenessConfigServiceImpl.kt`, `server/impl/ApiServiceImpl.kt` (`pingWebsite`)
- api: `controller/scheduled/ScheduledTasks.kt`, `controller/admin/AdminBookmarkPingLogController.kt`
- api: `config/async/AsyncConfig.kt` (sweep/ping executors), `config/init/SingleInstanceGuard.kt`, scheduling properties
- api: `utils/ScrapeTargetGuard.kt` plus the delegated `WebsiteParser.classifyLinkType`

## api/liveness sweeps & scheduling

1. **[correctness/efficiency, medium] Pages short-circuited by an already-dead site never use the site's failure count for their backoff, so the configured exponential curve collapses to the page's frozen first-failure interval and dead domains are retried/archived far too quickly.**
   `LivenessSweepService.kt:774` — `scheduleWriter.advance(... directlyProbed = false)` deliberately leaves `bookmark.consecutiveFail` unchanged, and `PageScheduleWriter.advance` then feeds that unchanged page counter into `LivenessPolicy.nextCheckAt`. `persistProbeResult` switches to `evidence.siteConsecutiveFail` only *afterward*, for the death/archive thresholds. A page that failed once when its site was first declared dead therefore keeps getting `base × multiplier^(1-1)` (24h by default), even while the site's real root-probe count grows through 2, 3, … 10; it can reach terminal `ARCHIVED` in roughly daily rounds instead of the documented two-month backoff curve, repeatedly consuming the head of the due-candidate queue along the way. The current root probe is also not included in `evidence.siteConsecutiveFail` (`siteMap` is captured before `updateSiteLiveness`), so threshold transitions lag one probe, and the archival warning prints the frozen page count rather than the site count (e.g. “连续失败 1 次，转入归档”).
   **Recommend prioritizing — this defeats all three admin-configurable abnormal-backoff knobs exactly on the high-cardinality dead-domain path the site-level short circuit exists to optimize.**

2. **[correctness, medium] The sweep executor says the two sweeps must be serial, but `corePoolSize=1`, `maxPoolSize=2`, `queueCapacity=0` actually permits two rounds to run concurrently.**
   `AsyncConfig.kt:128` — Spring turns a zero-capacity queue into `SynchronousQueue`; after the core thread is occupied, a second submission cannot queue and is therefore accepted on a second worker up to `maxPoolSize`. The Redis locks are keyed by `taskLabel`, so `livenessCheckStaleBookmarks` and `retryUnreachableBookmarks` do not block one another. A slow `:30` round can overlap the `:00` round (or an admin can trigger the other task), contrary to the class's “1 thread / two tasks serial / reject overlap” invariant. The rounds then share the ping pool and parse queue and can read/write the same site's liveness counters from stale snapshots; pages transitioning SUCCESS → UNREACHABLE during the overlap make the nominally disjoint status filters insufficient protection.

3. **[correctness, medium] The manual-trigger preview labels `mayTriggerParse` as a maximum but excludes every page currently under a dead site, even though those pages can be revived and re-crawled by the actual round.**
   `LivenessSweepService.kt:258` — the count is computed only from `pagesOfLiveSites`. During execution, each dead site's root is probed; if it responds ALIVE, all its pages move into `revived`, are probed individually, and can satisfy the same parse predicate. The confirmation dialog can consequently say “最多 0 条” while the accepted operation dispatches up to the whole batch (50/200) for re-crawl. This is not just preview/execution timing drift: the candidates and site state can remain identical, and the undercount follows deterministically from a successful recovery probe.

4. **[observability, medium] Sweep health has only one global `lastRoundAt`, so one of the two hourly tasks can stop permanently while the other keeps the standing “巡检仍在运行” alert green.**
   `BookmarkPingLogServiceImpl.kt:75` — the latest row is selected across all task labels. If (for example) `livenessCheckStaleBookmarks` fails before `recordSweepRound` on every run but `retryUnreachableBookmarks` continues to write its half-hour rows, `lastRoundAt` never becomes older than three hours and the admin alert cannot detect that all SUCCESS bookmarks have stopped receiving liveness/content refreshes. Breaker streaks are correctly grouped per task already; liveness timestamps need the same per-task treatment (or an explicit missing/stale-task list).

5. **[correctness, low] IPv4-mapped IPv6 literals bypass the “only domains are scrapable” guard and are classified as domains.**
   `WebsiteParser.kt:128` / `ScrapeTargetGuard.kt:45` — `[::ffff:127.0.0.1]` becomes `::ffff:127.0.0.1`; the IPv6 recognizer rejects it because it contains dots, then `hostname.contains(".")` classifies it as `DOMAIN`. `ScrapeTargetGuard.isScrapable("http://[::ffff:127.0.0.1]/")` therefore returns true and sends the request across the service boundary, defeating the guard's stated purpose (the scrapper's own SSRF validation remains the final security boundary).

6. **[correctness, low] A manual trigger can return `accepted=true` even when no round will run or appear in `sweep_log`.**
   `AdminBookmarkPingLogController.kt:85` — `preview.running` and the async method's later Redis acquire are separated by a race the comment acknowledges. Two simultaneous requests can both see false and both return “已触发”; one worker then loses SETNX and exits without a round row. The response contract and KDoc promise that `accepted=false` represents this case, so operators can be told an action was accepted and to wait for a result that will never exist.

7. **[correctness, low] `PageScheduleWriter` can overflow `consecutiveFail` before the policy's overflow-safe backoff code ever sees it.**
   `PageScheduleWriter.kt:70` — `page.consecutiveFail + 1` wraps `Int.MAX_VALUE` to `Int.MIN_VALUE`. `nextCheckAt` then falls back to the base interval and `shouldArchive` sees a negative count, leaving the row permanently unarchived. `LivenessPolicy.backoffHours` explicitly defends against anomalously large database counters, but the unchecked increment one layer above invalidates that guarantee at the largest legal PostgreSQL `integer` value.

8. **[docs, low] The health DTO still states that breaker rounds do not advance the cursor and therefore self-deadlock on the same candidates, but the execution path now advances every probed cursor by one hour specifically to prevent that incident.**
   `Response.kt:1178` — this stale statement is also copied into the admin alert's operator guidance. `LivenessSweepService.kt:553-562` calls `scheduleWriter.protect` for every breaker result before recording the round, so the next hour is not guaranteed to select the same batch. The outdated warning can send an operator toward threshold changes for a failure mode the current code already removed.

### A1 Summary

- **Recommend prioritizing:** #1 (dead-site short circuits bypass configured exponential backoff)
- **High-confidence correctness bugs:** #1, #2, #3, #4, #5
- **Lower-severity / edge cases:** #6, #7, #8
- **Coverage gap:** `LivenessPolicy` and `ScrapeTargetGuard` have strong pure-function tests, but there are no tests for `LivenessSweepService`, `PageScheduleWriter`, the sweep executor topology, manual preview/trigger behavior, or per-task health. All orchestration findings above sit in that untested layer.

---

# A2 — OSS 对象治理 (OSS object governance)

Paths reviewed:
- api core: `server/impl/OssReconcileServiceImpl.kt`, `server/impl/OssObjectServiceImpl.kt`,
  `server/repair/OrphanCleanupService.kt`, `server/IOssObjectService.kt`,
  `server/IOssReconcileService.kt`, `mapper/OssObjectMapper.kt`
- api boundaries: `config/entity/OssGovernanceConfig.kt`, `entity/entity/OssObjectEntity.kt`,
  `entity/enums/OssObjectEnums.kt`, `controller/scheduled/ScheduledTasks.kt`,
  `controller/admin/AdminOssObjectController.kt`, `controller/admin/AdminBookmarkCleanupController.kt`,
  `utils/OssUtils.kt`, `deploy/schema.sql`
- reference producers/removers: `server/asset/SiteAssetWriter.kt`, `server/impl/FileServiceImpl.kt`,
  `server/impl/BackgroundImageServiceImpl.kt`, `server/impl/UserServiceImpl.kt`
- registry guard: `server/repair/RegistryCoverageTest.kt`

## api/OssReconcileServiceImpl.kt + OssUtils.kt

1. **[correctness, high] Managed prefixes are not directory-delimited, so enabling reclamation can delete objects from a neighbouring/unmanaged namespace.**
   `OssReconcileServiceImpl.kt:185` removes the trailing slash from every configured folder and passes values such as `bookmarkify/avatar` and `scrapper` to OSS. OSS prefix matching is textual (`OssUtils.kt:373`), and the ledger filter/source classifier repeat the same plain `startsWith` test at lines 101/193. Consequently `bookmarkify/avatar-backup/x` is included in the `bookmarkify/avatar` sweep and `scrapper-old/x` in the `scrapper` sweep. With `reclaim-orphans=true`, an older object in either neighbouring prefix has no registered referrer and is physically deleted, contradicting the method's stated safety boundary that unrelated/shared-bucket content is never touched. Normalize managed directories to exactly one trailing `/` (and use the same boundary for ledger/source matching).
   **Recommend fixing before enabling reclamation.**

2. **[correctness, high] Both OSS garbage-collection paths have a check-then-delete race that can remove a content-addressed object while a concurrent scrape is attaching a new reference to it.**
   In the nightly path, `collectReferencedKeys()` takes one snapshot at `OssReconcileServiceImpl.kt:106`; deletion happens much later at lines 288-298 without rechecking the referrers. An object that has been orphaned for 30 days can be reused by a concurrent scrape (content-addressed keys deliberately make this common), whose `site_asset` row commits after the snapshot but before the delete; the old `lastRefAt` still satisfies the grace predicate and the newly-live bytes are deleted. `registerAll` does not close the window because `ON CONFLICT DO NOTHING` leaves the existing ledger row's ORPHAN state/old `lastRefAt` unchanged. The immediate path has the same race at `SiteAssetWriter.kt:387`: it queries committed `site_asset` rows, cannot see another owner's in-flight transaction (the scrapper has already PUT the shared key), deletes at line 399, then that other transaction commits a reference to the missing object. Parse locks are per page, so different pages/sites do not exclude each other. A candidate must be revalidated against all referrers at the destructive boundary with a protocol writers participate in (or deletion must be made conditional/serialized); a stale in-memory set is not sufficient for irreversible GC.
   **Recommend fixing before enabling/retaining either automatic deletion path.**

3. **[correctness, medium] Hitting `reconcileMaxKeys` returns a partial bucket snapshot but the caller treats it as complete and marks every omitted ledger row DELETED.**
   `OssUtils.listAllObjects` (`OssUtils.kt:368-383`) returns only a `Map`; when the safety cap is reached it logs and returns early without carrying an `isComplete` flag. `reconcile()` then computes `ledgerKeys - bucket.keys` at line 136 and reports/marks that entire suffix as missing from OSS. At the default 200,000-key threshold, all later lexicographic keys are permanently classified DELETED on every run even when the objects and their live referrers still exist; the report still has no `errorMsg` and logs “对账完成”. Physical reclamation happens only inside the partial map, so this does not itself delete the omitted keys, but it makes the ledger and operational report cease to represent the bucket exactly when the safety valve is needed most.

4. **[correctness, medium] OSS delete failures are swallowed, after which the ledger and report still claim that every candidate was deleted.**
   `OssUtils.delete` (`OssUtils.kt:393`) returns `Unit` and catches every SDK failure. `reclaimOrphans` cannot observe that outcome: it calls delete for each candidate, unconditionally updates all rows to DELETED, returns `candidates.size`, and logs all keys as reclaimed (`OssReconcileServiceImpl.kt:297-304`). The same false transition occurs in the immediate asset/user-file cleanup callers that invoke `markDeleted` after this void method. A transient credential/network failure therefore produces a successful admin report and a DELETED ledger row for bytes still present in the bucket; only a later full reconciliation can repair it.

5. **[correctness, medium] Destructive governance settings accept unsafe values without validation; a negative grace period defeats the fresh-upload safety gate.**
   `OssGovernanceConfig.kt:11-33` has no `@Validated`/range constraints or explicit startup validation. With reclamation enabled, `orphanGraceDays=-1` makes the cutoff one day in the future, so a just-uploaded object observed before its API reference commits satisfies both age predicates and can be deleted in the same run. Zero/negative `reconcileMaxKeys` also degenerates the “complete bucket” view to its first OSS page, triggering finding #3. These are operator-controlled values, but this switch performs irreversible deletion and should fail closed on invalid configuration rather than silently removing its own safety margin.

6. **[correctness, low] Backfilled system default backgrounds are permanently labelled as USER_UPLOAD rather than SYSTEM.**
   `sourceOf` (`OssReconcileServiceImpl.kt:192`) only distinguishes the scrapper prefix from everything else. Default backgrounds share `FileType.BACKGROUND.folder`, so their backfilled ledger rows receive USER_UPLOAD even though `OssObjectSource.SYSTEM` exists specifically for “人工预置，例如系统默认背景图”. This makes source filters/audits lie and the row is never corrected because subsequent registrations use `ON CONFLICT DO NOTHING`.

## api/OrphanCleanupService.kt

7. **[correctness, high] Cleanup can race an add/revival and delete the canonical page or site after the new bookmark has started using it, leaving dangling rows because the schema has no foreign keys.**
   `run()` snapshots every referenced page at lines 175-180, selects doomed pages/sites in memory at lines 182-212, then deletes them at lines 216-228. A concurrent add can obtain an old UNREACHABLE/ARCHIVED page after the snapshot and insert its `bookmark` row before cleanup deletes the page; `RECENT_GRACE` does not protect an old canonical row. Likewise, a new page can be inserted under an empty dead site after `doomedSiteIds` is computed and before the site delete. The local/IP creation path is even exposed for brand-new sites because there is no site-level grace and those sites satisfy `NON_CRAWLABLE` immediately. The result is a user bookmark pointing to a missing page, or a page pointing to a missing site, with no constraint error to stop the delete. The preview and execute endpoints are separate unsnapshotted requests, so the confirmation counts can also become stale before execution.
   **Recommend fixing before using the execute endpoint in production.**

8. **[correctness, medium] Published system collections are not treated as references, so cleanup irreversibly removes their pages and the collection silently loses items.**
   The only protection set is built from `bookmark.page_id` (`OrphanCleanupService.kt:175-180`). `SystemCollectionPageEntity` is explicitly registered as `Retained` at lines 141-145, meaning its row is kept while the target page is allowed to be deleted. `BookmarkAdminService.loadSystemCollectionVO` then silently drops missing targets with `mapNotNull`, so a curated collection shrinks with no error and the dangling row cannot reconnect when the same URL is later recreated under a new page ID. A published collection is itself a real reference; retaining only the join row preserves neither the content nor a useful audit trail.

9. **[correctness, low] `releasedFiles` is neither a reliable unique-file count nor complete for the rows cleanup actually releases.**
   `purgeAssets` (`OrphanCleanupService.kt:267`) counts distinct nonblank `fileId` values separately for PAGE and SITE purges. A content-addressed object referenced in both groups is counted twice, while a bare `storageUrl` whose ledger registration failed (a supported fallback shape) is not counted at all even though deleting that asset row releases an OSS object that the next reconciliation will classify as orphan. The confirmation report can therefore over- or under-state the storage impact.

## api/oss_object ledger + registry guard

10. **[correctness, medium] `registerAll` promises per-item failure isolation but catches SQL errors inside one PostgreSQL transaction, where one bad statement aborts the whole batch.**
    `OssObjectServiceImpl.kt:73-99` opens a single `REQUIRES_NEW` transaction and wraps each `insertIgnore` in `runCatching`. `ON CONFLICT` handles duplicates, but any other SQL error (for example one overlong/malformed object key) puts PostgreSQL's transaction in the aborted state; every later insert and the final `findByKeys` then fail, and earlier successful inserts roll back at commit. One bad asset therefore strips `fileId` from the entire scrape batch, contrary to `IOssObjectService.registerAll`'s “单条失败不影响其余” contract. Per-item validation/savepoints or truly separate transactions are required for that guarantee.

11. **[correctness, low] The admin ledger query says it puts orphans first but orders only by creation time.**
    `OssObjectSearchParams.toWrapper()` (`Request.kt:427-428`) comments “孤儿排前面” and then applies only `orderByDesc(createTime)`. Without an explicit state expression, recent ACTIVE rows push old ORPHAN rows off the first pages, undermining the page's stated purpose of making reclamation candidates visible.

12. **[test-guard, low] Registry coverage verifies that every owned entity is named, but not that every `Cascade` entry has an actual purge implementation.**
    `RegistryCoverageTest.kt:95-116` considers an entity covered as soon as it is present in `OWNERSHIP_REGISTRY`; it cannot connect `Disposition.Cascade` to a corresponding call in `OrphanCleanupService.run`. A future table can make the guard green by adding one map entry while still leaking all of its rows. The current Cascade entries do have matching purge calls, so this is a false-assurance gap rather than a present data leak.

### Cross-batch dependency

- F1 finding #15 applies directly here: `OssReconcileServiceImpl` relies on the shared fail-open `ParseLock.acquire`. On a Redis error, every contender receives a synthetic token and proceeds, even though this task's own KDoc says concurrent rounds may both delete against inconsistent reference snapshots. For bookmark parsing the fail-open tradeoff is duplicate work; for irreversible OSS GC it is not the same risk class. This is not renumbered as a second finding, but it should be addressed as part of the A2 fix.
- No reconciliation/cleanup behavior test exists beyond reflection-based registry coverage. The full API suite is green, but none of findings #1-#10 is exercised by it.

### A2 Summary

- **Fix before destructive use:** #1 (prefix escape), #2 (reference-check/delete races), #7 (cleanup/add race), plus the F1 #15 fail-open lock dependency
- **High-confidence correctness bugs:** #1, #2, #3, #4, #6, #7, #8, #10, #11
- **Configuration/reporting/guard gaps:** #5, #9, #12
- **Verification:** `./gradlew test` passed (including all 5 `RegistryCoverageTest` cases); the existing suite has no direct `OssReconcileServiceImpl` or `OrphanCleanupService` behavior tests

---

# W1 — 布局 / 置顶 / 文件夹 / 重新归类

Paths reviewed:
- web: `composables/useBookmarkMove.ts`, `components/BookmarkFolderCard.vue`, `components/BookmarkTreeRow.vue`,
  `components/PinnedBookmarkGrid.vue`, `components/ReclassifyDialog.vue`, `components/setting/BookmarkLibrary.vue`

(`stores/bookmark.store.ts` layout/pin logic was already given a full high-effort pass under F1 — findings #18-21
in that batch cover `dedupeLayout`, `replaceContent`/`replaceFolder`, `createFolderLocal`, and `setPinnedLocal`.
Not re-run here to avoid duplicate findings against an unchanged file.)

## web/composables/useBookmarkMove.ts

1. **[correctness, medium] `moveToFolder()` calls `bookmarksMoveNode` regardless of whether the preceding `persistOrder('move')` succeeded, because `persistOrder` swallows its own `bookmarksSort` failure.**
   Line 71 — `persistOrder`'s internal `.catch` (line 26-28) logs and resolves normally, so `await persistOrder('move')` never throws. `bookmarksMoveNode` still runs and — per its own preceding comment — builds the `HOME_DIR_UPDATE` broadcast from the server's current sort table, which never got the move's sort write. Every open tab, including this one, receives a broadcast placing the moved bookmark at its stale pre-move position — the exact corruption the sequential-await ordering was written to prevent.

2. **[correctness, low] A `bookmarksMoveNode` failure (including a silently-rejected 3xx application code) produces no user-facing feedback after the optimistic local move has already been applied.**
   Line 72 — `moveLocal` moves the node in local state before either network call runs. Per `server/apis/http.ts`'s `handleResult`, a 3xx code is deliberately rejected with no toast; the catch at line 75-77 only `console.error`s. The bookmark stays visually in the new folder with no indication the server never recorded it.

3. **[correctness, low] `moveLocal`'s synchronous auto-dissolve of an emptied source folder fires its own unawaited `persistDissolve()` network calls with no coordination against `moveToFolder`'s own `persistOrder`/`bookmarksMoveNode` sequence.**
   Line 68 — moving the second-to-last item out of folder A triggers `bookmark.store.ts`'s `dissolveFolderLocal` → `persistDissolve` (`bookmarksMoveNode(remainingId, null)` then `bookmarksDel(folderId)`), running concurrently and unawaited alongside `moveToFolder`'s two awaited calls. Four backend writes race with no cross-chain ordering guarantee; another open tab (or this one, via broadcast) can momentarily observe an inconsistent tree if they arrive out of order.

4. **[simplification, low] The file's own header comment claims persistence logic is centralized here so the drag path and this path "won't evolve independently," but `BookmarkFolderCard.vue`'s `persist()` reimplements the identical `bookmarksSort`-then-`bookmarksMoveNode` sequence instead of calling this composable.**
   Line 12 — a future fix to sequencing/error-handling here (e.g. fixing #1/#2) won't propagate to `BookmarkFolderCard.vue`'s separate `persist()` (line 383), which duplicates the same pattern with its own try/catch — exactly the divergence the centralization comment says it prevents.

5. **[correctness, low] `moveWithin()` fires an uncoalesced `bookmarksSort` POST on every call with no debounce/lock, so rapid repeated clicks can have their responses resolve out of request order.**
   Line 44 — each "上移" click reorders local state correctly but also fires a fresh `persistOrder('reorder')` call; `http.ts`'s `withDebounce` keys on method+url+body, and each call's body differs, so nothing is deduped. A late-arriving response from an earlier click can overwrite the backend's persisted order with a stale intermediate state until the next full refresh.

## web/components/BookmarkFolderCard.vue

6. **[correctness, medium] `persist()` proceeds to call `bookmarksMoveNode` even when the preceding `bookmarksSort` call fails, defeating the documented sequencing invariant — same root cause as #1, independently reimplemented.**
   Line 383 — the catch block only logs; execution falls through and still calls `bookmarksMoveNode`, which reads the stale pre-reorder sort map and broadcasts it back, overwriting the just-set correct local order via `replaceFolder()`.

7. **[correctness, low] `delFolder`'s confirmation dialog labels every direct child as a "书签" and only counts direct children, undercounting/mislabeling when a child is itself a nested subfolder.**
   Line 464 — a folder from a bulk import containing 1 bookmark + 1 subfolder (20 bookmarks) shows "确定删除文件夹「X」及其中的 2 个书签吗？" but the cascading delete actually removes 21 bookmarks plus the subfolder.

8. **[efficiency, low] Each `BookmarkFolderCard` instance registers its own global `monitorForElements` and document-level `pointerdown` listener, so every drop/pointerdown anywhere on the page is redundantly handled by every mounted folder card.**
   Line 322 — with dozens of folders, each drag-and-drop or click anywhere triggers N near-immediate no-op callbacks, scaling linearly with folder count instead of being centralized once.

9. **[duplication, low] `openMenuFromButton`'s rect-based menu-anchoring logic is duplicated verbatim in `BookmarkTreeRow.vue`'s own `openMenuFromButton`.**
   Line 540 — a future change to button-triggered menu anchoring (e.g. viewport-edge clamping) has to be applied in two places with no compiler warning if one is missed.

## web/components/BookmarkTreeRow.vue

10. **[correctness, low] The per-row `useLongPress()` timer is never cancelled on component teardown, so a pending long-press can fire a context menu for a bookmark already removed from the tree.**
    Line 35 — if the row's bookmark is deleted/moved via a WebSocket push while a touch-and-hold is in progress (unmounting this row via the `v-for` key disappearing), the composable's `setTimeout` still lives in its own closure and fires `openMenu(x, y, node)` with the stale `node`.

11. **[correctness, low] The `BOOKMARK_DIR` branch (a folder nested inside another folder) has no collapse toggle, context menu, or rename/delete/move support, unlike top-level folders in `BookmarkFolderCard.vue`.**
    Line 3 — the store's `order`/`childrenOf` getters are folder-id-agnostic and support nesting (e.g. via multi-level bulk import), but this component renders such a subfolder's descendants fully expanded with zero interactivity beyond deleting bookmarks one at a time.

12. **[correctness, low] A node with `type === BOOKMARK` but a falsy `typeApp` matches none of the three template branches and silently renders nothing.**
    Line 21 — the contract says `typeApp` is always non-null, but a transient partial store update, corrupted `localStorage` restore, or future backend regression producing one makes the bookmark disappear from the list with no visual trace or console warning.

13. **[duplication, low] `recordOpen` and `delOne` are copy-pasted verbatim (including confirm-dialog wording) into both this file and `PinnedBookmarkGrid.vue` instead of a shared composable.**
    Line 100 — the same class of drift this file's own comment on the context-menu definition warns against ("复制一份的话，以后加菜单项必然漏改一处"), applied to action handlers rather than menu items.

## web/components/PinnedBookmarkGrid.vue

14. **[correctness, low] Rapid successive reorders (drag-drop or repeated 左移/右移 clicks) fire independent, unawaited `bookmarksPinSort` requests with no sequencing, so a slower earlier request can overwrite a faster later one.**
    Line 189 — two overlapping full-order payloads race; if the server finishes the stale first request after the second, persisted `pinned_sort` reflects the stale order while the UI shows the newer one, invisible until the next `bookmarkStore.refresh()`.

15. **[correctness, low] The 左移/右移 menu items' `disabled` state is computed once at menu-open time from `props.nodes.findIndex` and never re-evaluated while the menu stays open.**
    Line 249 — a concurrent WS-driven pin-order change from another tab (e.g. a new tile appended after this one) leaves an open menu's disabled state stale; `moveBy` self-guards against the enabled-but-invalid case, but the disabled-when-should-be-enabled case leaves the action unreachable without closing/reopening the menu.

## web/components/ReclassifyDialog.vue

16. **[correctness, medium] `runPlan()` has no guard against overlapping calls, so reopening the dialog for a different folder while a previous plan request is still in flight lets the stale response overwrite the fresh one.**
    Line 162 — closing the dialog on folder A before its `bookmarksReclassifyPlan` resolves, then opening it on folder B, can have A's response land after B's `props.folderId`/`folderName` are already active, displaying A's plan under B's header. Confirming calls apply with `folderId=B` but A's `nodeIds`; the backend's `movable` filter (children-of-source check) silently drops all of them, so confirm still shows a success toast even though nothing moved.

17. **[correctness, low] `apply()`'s success path unconditionally calls `close()`, which can dismiss a dialog session the user has already reopened for a new plan while the previous `apply()` request was still pending.**
    Line 184 — an old apply resolving after the user dismissed and reopened the dialog for a new session still runs `close()`, yanking away the dialog the user is currently reviewing with no explanation.

18. **[correctness, low] The native `<dialog>`'s Esc/backdrop cancel is never intercepted, so it can dismiss the dialog while `applying=true` even though the visible close button is disabled.**
    Line 2 — pressing Esc during an in-flight `bookmarksReclassifyApply` still fires the browser's native cancel behavior regardless of the disabled button state, hiding the dialog mid-request with no way to tell whether the move happened.

19. **[efficiency, low] `apply()` calls `bookmarkStore.setLayout(root)` with the HTTP response, duplicating the work the subsequent `HOME_LAYOUT_REFRESH` WebSocket push (sent by the same backend transaction) performs moments later.**
    Line 185 — the full tree is normalized and the desktop re-rendered twice in quick succession per apply; idempotent, so not incorrect, just wasted work.

## web/components/setting/BookmarkLibrary.vue

20. **[correctness, medium] `deleteFolder()` deletes a folder and its bookmarks locally and server-side but never refreshes/invalidates the backend-paginated `page` ref, leaving deleted bookmarks as ghost rows in the "全部书签" view.**
    Line 397 — the stale `page.value` (still containing the now-deleted bookmarks) renders on returning to the all-bookmarks view, until an unrelated action happens to re-trigger `fetchPage()`.

21. **[correctness, low] `performDelete()` only refreshes the cached `page` (全部书签 list) when the deletion happened in the all-bookmarks view; deletions performed while browsing a folder leave the previously-fetched `page.value` stale.**
    Line 532 — deleting a bookmark inside a folder while `page.value` was already fetched from an earlier all-bookmarks visit leaves that cached page unrefreshed; returning to 全部书签 (with `keyword` unchanged, so the debounced watcher doesn't fire) still shows the deleted row, and re-deleting it hits the backend with an already-gone `layoutNodeId`.

22. **[correctness, low] `cleanInvalid()`'s guard against sweeping up still-parsing (`BOOKMARK_LOADING`) bookmarks relies on `bookmarkStore.nodes[id]` already containing that id — an id absent from the local tree (e.g. added from another session/tab, local Pinia snapshot not yet caught up) bypasses the exclusion.**
    Line 632 — `bookmarkStore.nodes[id]?.type !== HomeItemType.BOOKMARK_LOADING` evaluates `undefined !== ...` as `true` when the id isn't locally known yet, so a bookmark still legitimately being parsed elsewhere can be included in the "清理失效链接" batch and deleted.

23. **[correctness, low] The sidebar's per-folder bookmark count includes `BOOKMARK_LOADING` placeholders, while the folder's own content list and its "· N 项" header exclude them — the two counts for the same folder can disagree on screen simultaneously.**
    Line 57 — a folder with 5 just-imported items, 2 still `BOOKMARK_LOADING`, shows "5" in the sidebar but "· 3 项" and only 3 rows when opened, reading as a rendering bug rather than pending imports.

### W1 Summary

- **High-confidence correctness bugs:** #1, #6 (same underlying sequencing bug, two independent implementations), #16, #20
- **Recommend prioritizing:** #1/#6 — the "await sort before move" invariant exists specifically to prevent order corruption and is silently defeated by both implementations on the exact failure path (a transient sort-write error) it was written for
- **Duplication worth consolidating:** #4 (persist logic split between the composable and `BookmarkFolderCard.vue`), #9 (menu-anchoring math), #13 (`recordOpen`/`delOne`) — three separate instances of the same "no shared home for this logic" pattern across this batch
- **Lower-severity / cleanup:** the rest (stale-menu-state, missing refresh-after-delete, nested-folder UX gaps, double-render on apply)
