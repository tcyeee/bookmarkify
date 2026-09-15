# Code Review Findings

Results of the batched review described in `CODE-REVIEW-PLAN.md`. Each batch is run as
`/code-review high <path>` per path listed in the plan's table — a **path-target audit against
the current tree**, not a diff against `main` (this branch carries no code changes, only docs).
Working notes per batch also live in `.context/code-review/<batch-id>.md` (gitignored); this file
is the durable, committed record.

Progress: **F1, F2 done.** Remaining batches (A1, A2, F4, W1, M1, A3, M2, W2, M3, F3) not yet run.

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
