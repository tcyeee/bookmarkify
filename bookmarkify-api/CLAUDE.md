# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

**Bookmarkify API** (书签鸭) is a bookmark management backend service built with Kotlin + Spring Boot. It allows users to save, organize, and enrich browser bookmarks. The system automatically scrapes website metadata (title, description, favicon, OG image), stores enriched data, and delivers real-time updates via WebSocket.

## Tech Stack

- **Language:** Kotlin 2.1.20 on JVM 21
- **Framework:** Spring Boot 3.5.0-M2
- **Build:** Gradle 8.9
- **Database:** PostgreSQL (database `bookmarkify`, tables live in schema `public`)
- **ORM:** MyBatis-Plus 3.5.15
- **Cache/Session:** Redis (Spring Data Redis)
- **Async:** Spring `@Async` + `ApplicationEvent` (in-process background parsing; no message broker)
- **Auth:** Sa-Token 1.40.0 (dual realm: USER + ADMIN), plus Google/GitHub OAuth login
- **Object Storage:** Alibaba Cloud OSS
- **SMS:** Alibaba Cloud SMS
- **Email:** WeChat Work API (not SMTP)
- **HTML Parsing:** Jsoup 1.17.2 (local) — toggleable with self-hosted `bookmarkify-scrapper` (remote)
- **LLM:** DeepSeek API (used to infer app/brand short name from page titles)
- **API Docs:** Knife4j (OpenAPI 3)
- **WebSocket:** Spring WebSocket at `/ws`
- **Sibling services** (separate repos): `bookmarkify-scrapper` (Rust headless scraper), `bookmarkify-web` (Nuxt frontend)

## Project Structure

```
src/main/kotlin/top/tcyeee/bookmarkify/
├── Bookmarkify.kt                    # Main entry point
├── config/
│   ├── cache/                        # Redis config, @RedisCache AOP
│   ├── entity/                       # @ConfigurationProperties classes
│   ├── exception/                    # GlobalExceptionHandler, ErrorType codes
│   ├── filter/                       # Rate limiter, Sa-Token interceptors
│   ├── init/                         # AppInit (ApplicationRunner startup)
│   ├── async/                        # AsyncConfig (@EnableAsync, bookmarkParseExecutor)
│   ├── event/                        # Parse events + @Async BookmarkParseEventListener
│   ├── result/                       # ResultWrapper, PageBean
│   ├── throttle/                     # @Throttle annotation + AOP aspect
│   └── websocket/                    # WebSocket config, handler, session manager
├── controller/
│   ├── admin/                        # Admin CRUD endpoints (/admin/**)
│   ├── auth/                         # Login, SMS/email verification (/auth/**)
│   ├── bookmark/                     # Bookmark CRUD (/bookmark/**)
│   ├── scheduled/                    # Cron jobs, delayed task scheduler
│   ├── setting/                      # Background, user preferences
│   └── user/                         # User profile (/user/**)
├── entity/
│   ├── Request.kt                    # All request param DTOs
│   ├── Response.kt                   # All response VOs
│   ├── dto/                          # Internal DTOs (BookmarkWrapper, UserSessionInfo)
│   ├── entity/                       # Database entities (MyBatis-Plus @TableName)
│   ├── enums/                        # Enum types
│   └── json/                         # Types stored as JSON columns (e.g. BookmarkDir)
├── mapper/                           # MyBatis-Plus BaseMapper interfaces
├── server/                           # Service interfaces (I*Service)
│   └── impl/                         # Service implementations
└── utils/                            # Utility classes (OSS, Redis, parser, etc.)
```

### Resource Files

```
src/main/resources/
├── application.yml                   # Base config (port 7001, Sa-Token)
├── application-dev.yml               # Dev profile (local DB/Redis, overrides port → 8001)
├── application-online.yml            # Prod profile (all env vars)
└── banner.txt                        # ASCII banner
```

## Build & Run

```bash
# Build → produces build/libs/bookmarkify-api.jar
./gradlew bootJar

# Run (dev profile)
./gradlew bootRun --args='--spring.profiles.active=dev'

# Run all tests
./gradlew test

# Run a single test class / method
./gradlew test --tests "top.tcyeee.bookmarkify.utils.PasswordUtilsTest"
./gradlew test --tests "*PasswordUtilsTest.bcrypt roundtrip matches*"

# Show resolved dependency tree (useful when bumping versions)
./gradlew dependencies

# Docker — the Dockerfile is runtime-only (it just COPYs a prebuilt jar onto a
# JRE; it does NOT compile). Build the jar first, then the image.
./gradlew bootJar
cp build/libs/bookmarkify-api.jar .          # Dockerfile expects ./bookmarkify-api.jar
docker build -t bookmarkify-api .
docker run -p 7001:7001 bookmarkify-api
```

## Deployment

Deploys via GitHub Actions on push to the **`prod`** branch (`.github/workflows/deploy-api.yml`).
The cross-border `docker push` to Tencent TCR was retired (2026-06-20, ~42 min → ~2 min): the
runner compiles the jar, uploads it to Aliyun OSS, and the server pulls it intra-China and
assembles the thin runtime image locally (no Kotlin compile on the small prod host). Each run
clears the previous deploy's jar from OSS. TCR is no longer involved.

**Server port:** 7001 (prod). Local dev runs on **8001** — the `dev` profile (`application-dev.yml`) overrides the base `application.yml` port.

## Environment Variables

Required variables (see local `.env`, gitignored):

| Variable | Purpose |
|---|---|
| `BOOKMARKIFY_POSTGRES_HOST/USERNAME/PASSWORD` | PostgreSQL connection |
| `BOOKMARKIFY_REDIS_HOST/PORT/PASSWORD` | Redis connection |
| `BOOKMARKIFY_FILE_UPLOAD_DIR` / `PREFIX` | Local file storage paths |
| `BOOKMARKIFY_WECHAT_WORK_CORPID/CORPSECRET` | WeChat Work email API |
| `BOOKMARKIFY_ALIYUN_OSS_*` | Aliyun OSS (endpoint, keys, bucket, domain) |
| `BOOKMARKIFY_SCRAPPER_BASE_URL` | Self-hosted bookmarkify-scrapper base URL (replaced Iframely) |
| `BOOKMARKIFY_DEEPSEEK_API_KEY` | DeepSeek LLM (app-name inference) |
| `BOOKMARKIFY_GOOGLE_CLIENT_ID` | Google OAuth login |
| `BOOKMARKIFY_GITHUB_CLIENT_ID/SECRET` | GitHub OAuth login |
| `BOOKMARKIFY_LOG_DIR` | Log file output directory |

## Architecture

### Request Flow

```
HTTP → PreRequestFilter (20 req/s) → SaTokenConfigure (auth) → Controller → Service → Mapper → PostgreSQL
                                                                                          ↕
                                                                              Redis (cache/session)
                                                                                          ↕
                                                                  Async executor (@Async parsing)
                                                                                          ↕
                                                                              WebSocket (push updates)
```

### Key Patterns

1. **Dual auth realms:** `StpKit.USER` for regular users, `StpKit.ADMIN` for admin panel. Completely separate session stores.
2. **Anonymous-first identity:** Every visitor gets a `deviceUid` cookie and auto-created `user_info` row. Users "upgrade" by verifying phone/email.
3. **Three-layer model (`site` / `page` / `bookmark`):** `site` is one row per domain, `page` one row per canonical page under it, `bookmark` one row per user's saved item. Deduplication happens at the `page` level — two users saving the same URL share one `page` row. The names were corrected on 2026-08-03; before that `bookmark` meant the page and the user's item was `bookmark_user_link`, which is why a `site` row can have many `page` rows (bilibili: homepage + 3 videos).
4. **Async parsing pipeline:** Adding a bookmark returns a loading placeholder immediately. A Spring `ApplicationEvent` is published; an `@Async` listener (`BookmarkParseEventListener`, running on the `bookmarkParseExecutor` thread pool) parses the website, uploads logos to OSS, and pushes the result via WebSocket (`HOME_ITEM_UPDATE`). Failed parses are not retried inline — see *Reconciliation* below.
   - **Bulk import publishes no events at all.** `importBookmarkFile` only writes rows (`bookmark.page_id = 'LOADING'` marks the unfinished ones). Fanning out thousands of events would saturate the parse pool *and* its bounded queue, after which `CallerRunsPolicy` runs the remaining scrapes on the caller — which is the Tomcat request thread. `drainStuckLoading()` (every 30s) feeds the pool from the DB instead, sized to the queue's free capacity.
   - **DeepSeek enrichment is off the parse path.** Category tagging and the NSFW check run on a separate `bookmarkEnrichExecutor` via `BookmarkEnrichEvent`. They are invisible to the user but cost ~20s of round-trips, and the parse pool's throughput is literally "how long a bookmark spins".
   - **No pre-scrape ping.** Both parse paths already resolve unreachable sites to `UNREACHABLE` on their own (`classifyScrapperError` → E304, or a Jsoup fetch exception). `pingWebsite` is only for the scheduled liveness sweeps.
5. **Desktop layout tree:** `user_layout_node` stores a tree with `parentId` (ROOT → folders → bookmarks). Sort order is a JSON map in `user_preference` to avoid bulk DB writes.
6. **AOP caching/throttling:** `@RedisCache` for method-level caching; `@Throttle` for per-user rate limiting via Redis SETNX.
7. **Unified response wrapper:** `GlobalExceptionHandler` (ResponseBodyAdvice) wraps all responses in `ResultWrapper{ok, code, data, msg}`.
8. **Pluggable parser:** `bookmarkify.config.use-third-party-parser` toggles between local `WebsiteParser` (Jsoup) and `ApiServiceImpl.queryWebsiteInfo`, which calls the self-hosted `bookmarkify-scrapper` `POST /scrape` (configured via `bookmarkify.scrapper.base-url`). DeepSeek is invoked separately via `IApiService.inferAppName` to extract a short brand name from the parsed title.

### Database Tables

| Table | Description |
|---|---|
| `user_info` | Users (anonymous + registered, role: USER/ADMIN) |
| `site` | **Layer 1** — one row per domain. Brand name, favicon/logo, NSFW verdict, domain liveness |
| `page` | **Layer 2** — one row per canonical page, keyed `(site_id, url_path, url_query, url_fragment)`. Page title/description, page liveness. Was named `bookmark` until 2026-08-03 |
| `bookmark` | **Layer 3** — one row per user's saved item; this is what a user means by "bookmark". Their own title/note, pin, open count. Was named `bookmark_user_link` |
| `user_layout_node` | Desktop layout tree (bookmark, folder, function nodes) |
| `layout_node_function` | System function items attached to a layout node (e.g., Settings) |
| `user_preference` | Per-user preferences (background, layout, sort order) |
| `background_config` / `background_image` / `background_gradient` | Background settings |
| `oss_object` | **The ledger for every object in the OSS bucket** — one row per set of bytes, no owner. Replaced `user_file`; see root `FILE-SYSTEM-REFACTOR.md` |
| `site_asset` / `page_meta` / `scrape_snapshot` / `site_display_pref` | Crawl results + display prefs (replaced `bookmark_logo`; see root `CLAUDE.md`) |
| `page_ping_log` | One row per liveness probe (`outcome` = ALIVE/DEAD/UNKNOWN), purged after 90 days |
| `sweep_log` | One row per **sweep round** — candidates/backlog/outcome counts/deferred/breaker reason. The sweep's only SLI; see "Liveness sweeps" |
| `ai_call_log` | One row per DeepSeek call, **including request/response bodies** — see below |
| `system_config` | Generic key-value config (JSON), e.g. the liveness sweep intervals |
| `category` / `page_category` | Category dictionary + bookmark↔category links |

### DeepSeek calls go through one door

`ApiServiceImpl.chatCompletion` is the **only** place that talks to `api.deepseek.com`. All six scenes (`AiCallScene`: app name, category infer/propose, similar sites, NSFW, share review) hand it a `DeepSeekRequest` and get back the message content or `null` — each scene then applies its own fail-open/fail-closed policy to the `null`.

Do not add a seventh scene by copy-pasting an `HttpUtil.createPost` block. Everything that goes through `chatCompletion` lands in `ai_call_log` (prompt + raw response + tokens + duration, bodies truncated to 8000 chars); anything that doesn't is invisible forever, because AI output is consumed and discarded — once "疑似赌博博彩内容" is written onto a bookmark, what the model actually returned is unrecoverable. `success = false` also covers HTTP 200 with empty content, so the admin log's success rate matches the rate at which callers actually got an answer.

The admin panel reads it at `POST /admin/ai-call-log/all` (第三方管理 › AI检测管理).

### Async Parse Events

In-process Spring events (`config/event/`), dispatched by `BookmarkParseEventListener` (`config/async/AsyncConfig.kt`):

| Event | Pool | Purpose |
|---|---|---|
| `BookmarkParseEvent` | `bookmarkParseExecutor` | Parse + save a bookmark (cron / startup) |
| `BookmarkParseAndNoticeEvent` | `bookmarkParseExecutor` | Parse + WebSocket push (single add) |
| `BookmarkParseAndResetUserItemEvent` | `bookmarkParseExecutor` | Parse + bind user link (import) |
| `BookmarkEnrichEvent` | `bookmarkEnrichExecutor` | Category + NSFW (DeepSeek); deliberately off the parse pool |

`ParseLock` (Redis SETNX) guards two things: one scrape at a time per canonical bookmark (concurrent adds of the same URL would otherwise interleave `SiteAssetWriter`'s delete-then-insert and corrupt the asset rows), and one in-flight re-dispatch per user link.

### Reconciliation (four tasks, disjoint by design)

| Task | Cadence | Selects on | Fixes |
|---|---|---|---|
| `checkAll()` | 5 min | `bookmark.parse_status = PENDING` stale by `COALESCE(update_time, create_time)` | Lost parse events for the canonical record |
| `drainStuckLoading()` | 30s | `user_layout_node.type = BOOKMARK_LOADING` | **User-visible** stuck spinners: the import backlog, plus any add whose event was lost |
| `livenessCheckStaleBookmarks()` | hourly, :00 | `SUCCESS` + `next_check_at <= now()` | Site liveness **and** periodic content refresh |
| `retryUnreachableBookmarks()` | hourly, :30 | `UNREACHABLE` + `next_check_at <= now()` | Recovery of failed sites (these three own `pingWebsite`) |

(There used to be a fifth, `reviveArchivedBookmarks()` — daily, 02:00, selecting `ARCHIVED`. It was removed on 2026-08-07; archival is now terminal and its exit is on-demand. See *`ARCHIVED` is terminal* below.)

`drainStuckLoading` keys off the layout node rather than `parse_status` on purpose: when a bookmark scrapes fine but the user-link rebind or node flip fails, `parse_status` is `SUCCESS` and every status-based task skips it — while the user's tile spins forever.

**`drainStuckLoading` has a retry budget, and E307 must not consume it.** `findStuckLoading` is `ORDER BY created_at ASC LIMIT n`; the dispatch lock only makes in-flight rows skip, it does not stop them from still being at the head of that ordering. So a batch of rows that can never settle would permanently occupy those `n` slots and starve everything behind them — the back half of an import silently never finishing. `bookmark.dispatch_attempts` caps this at `MAX_DISPATCH_ATTEMPTS` (5), after which `terminateExhaustedLoading` settles the row as a source-less bookmark (node flipped, no canonical record — the user's own title and URL still render). Crucially, the two E307 early-return branches call `forgiveDispatchAttempt` to **reset the counter**: the budget exists to catch "this row is broken", and E307 means *we* are broken. Without the reset, a 30-minute scrapper outage burns every backlogged row's budget and they all get permanently downgraded on recovery.

**Every write to `parse_status` goes through `markParseSucceeded()` / `markParseUnreachable()`.** Those four fields are mutually constrained (SUCCESS implies `isActivity = true` and a null `parseErrMsg`), and forgetting the `scheduleAfterParse*` line raises no error — it just leaves `next_check_at` frozen, so the row is either re-selected every round or never selected again. The five-line block used to be copy-pasted at ten call sites.

**The unique index is what prevents duplicate tiles, not `assertNotAlreadyLinked`.** That check is check-then-act with no atomicity; what actually held the line was the 1-second `@Throttle` on `addOne` — a UX facility that gets widened when users complain, and that `ThrottleAspect` deliberately fails **open** when Redis is down. `uk_bookmark_uid_page` now backs it, and `insertNodeAndLink` translates `DuplicateKeyException` into E126 (same pattern as `getOrCreateByUrl` converging on `uk_page_canonical`).

**Observability:** `drainStuckLoading` emits `stuckLoadingStats` every tick — how many tiles are spinning right now, how long the oldest has been spinning, how many of those are import backlog. Anything past the 30-minute stale threshold logs at `warn`. This is the one real SLI for the add path; nothing else in the system answers it (`scrapper_call_log` is per-call, `page_ping_log` is per-sweep).

**`COALESCE(update_time, create_time)` in `checkAll` is load-bearing.** A fresh `PageEntity` has `updateTime = null`, and SQL `NULL < ?` is never true, so a plain `update_time <` predicate silently excludes exactly the never-parsed rows that need reconciling most.

### Liveness sweeps

Read this before touching `pingSweep`, `LivenessPolicy`, or the `next_check_at` columns.

**`pingWebsite` returns three states, not a boolean.** `PingOutcome.UNKNOWN` means *our* chain failed (scrapper unreachable, auth wrong, `load_shed` 503, contract mismatch) — it never reaches `page`. Only `ALIVE`/`DEAD` are facts about the site. The classification reuses `classifyScrapperError`, the same helper `scrape` uses: `E304` → `DEAD`, everything else → `UNKNOWN`. Collapsing these into `false` is what used to let one scrapper outage rewrite hundreds of healthy bookmarks per hour as `UNREACHABLE`.

**`/ping` reports facts; `LivenessPolicy.outcomeOf` decides what they mean.** The scrapper returns `{reachable, status, blocked, method, redirects}` — never `alive`. It used to return `alive = status < 500`, which put a Bookmarkify policy inside a service that has no business holding one (same error as `extractor` vs `role`), and cost far more than tidiness: **404/410 were reported as alive**, so deep-link rot — the single most common way a bookmark actually breaks — was undetectable. The mapping now lives in one pure function: `404/410` → `DEAD`; `403/406/412/425/429/451` → `UNKNOWN` (anti-bot rejects our datacenter IP; the same URL is fine in the user's browser, so judging the site on it is judging our network position); `5xx` and transport failure → `DEAD`; `blocked` → `UNKNOWN`, mirroring why E308 is kept out of E304. `PingResponse.alive` and the `body.alive` fallback in `pingWebsite` are a **deploy-window shim only** — the two services deploy from separate path-filtered workflows, so a version-skew window is unavoidable. Delete both once every deployed scrapper is on the new contract. On the scrapper side `scraper::probe` follows redirects (a `301 → 404` chain read only one hop deep reports `301`, i.e. alive) and retries with `GET` when `HEAD` yields `404/405/410/501`. Two different reasons: `405/501` means the server rejects the method outright, which would flatten every page on that site to one status code; `404/410` gets re-verified because **it is the only verdict that kills a bookmark, so it must be confirmed with the method a real user would use.** Verified against production: `xiaohongshu.com` answers `404` to `HEAD` but `200` to `GET` (two redirects to `/explore`) — trusting the `HEAD` alone silently marks a healthy bookmark dead, with no symptom at all (clean logs, normal latency, correct-looking HTTP). Regression: `ping_reverifies_head_404_with_get` plus `ping_still_reports_a_genuine_404` so the re-verification cannot quietly disable death detection altogether.

**`retryUnreachableBookmarks` re-crawls on `UNKNOWN` too — only `DEAD` is skipped.** A ping is one cheap `HEAD`; the scrape path is strictly more capable (headless fallback, plus the `siteapi.rs` official-API rescue). Anti-bot sites (`403/406/412`) can only ever be `UNKNOWN` from a ping, because the probe can see "we were refused" but not "a different approach would work". Gating on `== ALIVE` was verified in production to strand exactly the sites this project invested the most in rescuing: bilibili video pages answer `412` to the datacenter IP forever, so they would never be re-crawled again despite `siteapi.rs` existing for precisely that case. These rows are already `UNREACHABLE`, so an extra attempt costs almost nothing, and a genuine outage on our side is caught upstream by the breaker (>50% `UNKNOWN` aborts the whole round) rather than turning into a retry storm.

**Short-circuited rows never trigger a re-crawl, in any sweep.** This is an invariant of `pingSweepExclusively`, not a per-task policy: the row was not probed this round, so its outcome is a replayed site-level verdict. It matters as soon as any task widens its predicate to include `UNKNOWN` (as above) — a dead domain plus an inconclusive root probe would otherwise dispatch a re-crawl for *every* page under that domain, reinflating exactly the cost the site-level short-circuit exists to avoid.

**A sweep must never hand a row to the parse chain without first moving the cursor.** The `triggeredParse` branch deliberately delegates schedule writes to the parse chain (writing here would just be overwritten and would mask the real failure count) — but that chain has at least three exits that write nothing: `parseByApi`'s E307 early return, `parseBookmark` failing to take the parse lock, and the listener's `runCatching`. Any of them leaves `next_check_at` in the past, and since candidates are `ORDER BY <cursor> ASC LIMIT n`, those rows **occupy the head of the ordering forever** and starve everything behind them — `retryUnreachableBookmarks` only takes 50 per round, so 50 such rows kill the task outright. `protectSchedule` writes a short (1h) cursor before publishing; the parse chain overwrites it with the real one on every normal path. Same class of bug as the one `dispatch_attempts` fixes in `drainStuckLoading`.

**Sweeps dispatch re-crawls under backpressure, like `drainStuckLoading`.** A round could publish 200 `BookmarkParseEvent`s into the 500-slot parse queue that interactive `addOne` shares; overflow means `CallerRunsPolicy` runs 60-second scrapes **on the sweep thread**, which blows past the 30-minute sweep lock and lets the next hour's round start concurrently — and 32 parse threads hitting the scrapper at once exceeds its concurrency limit, producing the `load_shed` 503 → E307 → no-cursor-write case above. Three failures feeding each other. Grants are now capped by `queue.remainingCapacity() - SWEEP_PARSE_QUEUE_HEADROOM` (150, deliberately larger than `DRAIN_QUEUE_HEADROOM`: a spinning tile has a user waiting on it, a background content refresh does not). Denied rows get `protectSchedule` rather than a normal persist — persisting `ALIVE` would push them out a full 7-day cycle when they are precisely the rows most overdue.

**Only a direct probe is evidence.** Site-level short-circuit (`site.is_alive = false` → skip probing that domain's pages) hands out `DEAD` verdicts that are *reused*, not observed. Those were already excluded from the breaker sample; `consecutiveFail` and archival were still counting them, so one bad site verdict walked an entire domain's bookmarks into `ARCHIVED` within 10 backoff cycles. `ProbeEvidence` now carries `directlyProbed` through to `advanceSchedule`/`persistProbeResult`: no direct probe, no increment. Archival for short-circuited pages keys off the **site's** `consecutiveFail` instead (built from real root probes) — without that branch a dead domain's pages would sit in the candidate pool forever eating `LIMIT` slots.

**`ARCHIVED` is terminal, and its exit is on-demand rather than scheduled.** The threshold is `maxRetryFailures` (admin-configurable, default 10, floor `LivenessPolicy.MIN_MAX_RETRY_FAILURES` = 2). Once a row archives, **no scheduled task selects it again** — the three sweeps take `UNREACHABLE` / `SUCCESS` / `PENDING` respectively.

An exit is still mandatory: everything that pushes a row into archival is automated and fallible (a temporary DNS change, or the target blackholing our egress IP for a while, is enough), and an automatic entrance with no exit turns one misjudgement into permanent deletion. But the exit does not have to be a timer. `reviveArchivedBookmarks` (daily, batch 50) was removed on 2026-08-07 because its cost is **permanent** — a domain that is never coming back still eats a probe and a `LIMIT` slot every 30 days — while its yield decays to zero, archival meaning the row has already failed `maxRetryFailures` consecutive times. The replacement is `PageEntity.reviveOnAdd()`, called from `getOrCreateByUrl`: when any user adds that URL, `consecutive_fail` resets to 0, `parse_status` goes back to `PENDING`, `next_check_at` to now, and the row re-enters the normal parse chain. "Someone is trying to bookmark this right now" is a far stronger revival signal than "30 days elapsed", and it costs nothing when nobody asks.

**The hook belongs in `getOrCreateByUrl`, not in `addOne`.** That method is the single door for "someone wants this URL" — single add, bulk import (`parseAndResetUserItem`), similar-site ingest, admin create-by-URL all pass through it. Putting the reset in any one caller silently leaves the others broken, and the symptom is invisible: adding an archived site from that entry point just stays grey forever.

**Resetting `consecutive_fail` is not optional, and neither is the floor on `maxRetryFailures`.** The archive threshold reads that counter, so changing only the status means the very next probe failure re-archives the row — as if the revival never happened. And `shouldArchive` clamps `maxRetryFailures` up to `MIN_MAX_RETRY_FAILURES` rather than trusting it: the value comes from `system_config`, `getConfig` falls back to defaults on a parse error, and a 0 or 1 reaching the policy would archive every row that fails a single probe in one sweep round — an action with no automatic undo.

**A round with zero due candidates still writes a row.** "Nothing was due" and "the sweep never ran" must be distinguishable in the data, because `SweepHealthVO.lastRoundAt` — and the admin's standing alert, which flags `> 3h` since the last round — cannot tell them apart otherwise. Returning early on an empty candidate list (as the code originally did) means a healthy but *idle* deployment, which is the normal state whenever bookmark count is low and the check interval is long, leaves `lastRoundAt` frozen in the past and lights the alert permanently. A perpetually-firing alert is the same as no alert.

**`sweep_log` is the sweep's SLI, one row per round.** `page_ping_log` is per-probe and cannot answer "was that round aborted", "is the backlog outrunning the configured interval", "how many re-crawls got deferred". Most importantly the breaker — whose whole meaning is "our chain is broken, this round's verdicts are worthless" — previously had exactly one output: a `log.error` that scrolls away unwatched. Admin reads it at `POST /admin/bookmark-ping-log/sweeps` (`onlyBreaker=true` filters to aborted rounds). Purged on the same 90-day cycle as the ping log.

**The candidate query uses `COALESCE(next_check_at, epoch)`, matching `idx_page_due_check (parse_status, COALESCE(...))`.** The old form — `next_check_at <= ? OR next_check_at IS NULL` with `ORDER BY next_check_at ASC NULLS FIRST` — was unsargable on both halves (the `OR`, plus btree ascending defaulting to NULLS LAST), so every round sorted the whole status partition twice (count + select). Keep predicate and `ORDER BY` textually identical to the index expression or it silently degrades to a seq scan. Same idiom as `checkAll`'s `COALESCE(update_time, create_time)`.

**A sweep probes the whole batch first, then writes.** In between sits `LivenessPolicy.breakerReason`, which aborts the round if >50% came back `UNKNOWN` (≥10 samples) or >90% came back `DEAD` (≥20 samples). The second rule catches the case the first cannot: the scrapper is up but its egress is broken, so it *honestly* reports `alive=false` for everything. Ping logs are still written on an aborted round — that batch is the evidence of what broke. Order matters; probing and writing in one pass makes the breaker useless.

**Scheduling state lives in its own columns.** `next_check_at` is the only cursor the sweeps read; `last_check_at` / `last_parse_at` / `consecutive_fail` carry the rest. `update_time` went back to meaning "record last modified" — when it doubled as the cursor, an admin editing a title postponed that bookmark's next sweep by a full cycle, and a successful ping polluted the field's public meaning. `next_check_at IS NULL` counts as due, so a row that somehow missed its scheduling write gets picked up instead of vanishing. **Every site that writes `parse_status` must call `scheduleAfterParseSuccess()` / `scheduleAfterParseFailure()`** — miss one and that row either repeats every round or is never selected again.

**One failed check never kills a bookmark.** Both paths that can declare a site dead go through `LivenessPolicy.confirmsDead(consecutive_fail, deadConfirmFailures)` (default 3, admin-configurable). A single failure is not evidence of death: a target restart, an egress hiccup, a CDN node swap all produce exactly the same result, and marking dead has a *user-visible* consequence (`isActivity = false`, the tile greys out).

- **Probe path** — `persistProbeResult` only writes `UNREACHABLE` once the count is reached. Until then the row stays `SUCCESS` and keeps being re-selected by `livenessCheckStaleBookmarks` on the backoff cursor, so confirmation costs nothing but a few days of latency on a verdict that has no deadline. `UNKNOWN` does not count (that is our chain, not the site's) — same reason it does not advance the backoff. **Only `livenessCheckStaleBookmarks` passes `mayConfirmDeath = true`;** it is the sole sweep allowed to turn a working bookmark into a failed one.
- **Parse path** — `markParseUnreachable` gates on the same threshold **when the row was already `SUCCESS`**, and settles immediately otherwise. Without the gate the sweep quietly bypasses its own rule: `livenessCheckStaleBookmarks` pings `ALIVE`, sees content older than `contentRefreshIntervalDays`, dispatches a re-crawl, and one timeout or one anti-bot reject greys out a site that had just answered the ping. The `PENDING` exemption is what keeps the interactive add path correct — a bookmark that has never parsed must reach a terminal state or the user's `BOOKMARK_LOADING` tile has nowhere to land. While unconfirmed the row keeps its old title and icon, and `parseErrMsg` is left null so the `SUCCESS ⇒ no error message` invariant holds; the failure is still recorded by `recordScrapeFailure` and `consecutive_fail`.

**Site-level `consecutive_fail` must be fed by every root probe, including the failing ones.** `updateSiteLiveness`'s `needRootProbe` deliberately skips domains already marked dead, so after the first `DEAD` verdict a site never reaches the `siteVerdict` branch again — its counter is advanced *only* by the recovery probe that the site-level short-circuit already runs each round. Feed back `ALIVE` alone (as the code originally did) and the counter freezes at 1 forever. That counter is the sole evidence source for short-circuited pages, whose own `consecutive_fail` stops growing by design (`directlyProbed = false`), so freezing it means those pages can reach neither the death threshold nor `maxRetryFailures`: they stay `SUCCESS`, come due every backoff cycle, and permanently occupy the head of `ORDER BY next_check_at ASC LIMIT n` — the same starvation `protectSchedule` and `dispatch_attempts` exist to prevent, and a whole dead domain's worth of tiles stays green. `UNKNOWN` is still excluded, as everywhere else.

**Backoff and archival** (`LivenessPolicy`): `ALIVE` → +`activeCheckIntervalHours`; `DEAD` → `abnormalCheckIntervalHours × abnormalBackoffMultiplier^(fail-1)`, capped at `abnormalMaxIntervalHours`; `UNKNOWN` → +1h and **no** increment of `consecutive_fail` (our own outage must not push the whole table to the end of the backoff curve). All three backoff knobs are admin-configurable in `system_config` (defaults 24h / ×2 / 384h — the last one is exactly what the old hardcoded `2^4` cap computed, so the default curve is unchanged). `backoffHours` multiplies in a loop and returns the moment it hits the cap rather than computing `base × m^n`: `consecutive_fail` comes out of the database, and an overflowed `Int` yields a `next_check_at` **in the past**, which parks that row permanently at the head of `ORDER BY next_check_at ASC LIMIT n` and starves everything behind it — the same failure mode `protectSchedule` exists to prevent. At `maxRetryFailures` consecutive failures (admin-configurable, default 10) the row becomes `ParseStatusEnum.ARCHIVED`, which drops out of every sweep — nothing pings a domain that has been gone for two months, and the `LIMIT` slots stay free for rows that matter. `isActivity` stays false, so users still see it as a dead bookmark. **`deadConfirmFailures` is validated to stay below `maxRetryFailures`:** configure it higher and rows would archive before they were ever marked `UNREACHABLE`, so `retryUnreachableBookmarks` — which selects on exactly that status — would never find a candidate again. The admin form enforces the same relation by deriving that input's `:max` from `maxRetryFailures`.

**Content refresh is what makes this an "update" mechanism.** `shouldRefreshContent` re-scrapes when `last_parse_at` is older than `contentRefreshIntervalDays` (default 30). The old condition (`alive && !isActivity`) was dead code — `isActivity=false` never coexists with `parse_status=SUCCESS` — so healthy sites were never re-crawled at all.

**Manual edits are protected per field.** `page.locked_fields` (`PageLockedField`) lists what a crawl may not overwrite. Editing a field in the admin locks it; explicitly accepting a crawled value (`adminRefresh`, `adminApplyRefetch` with `useNewTitle`) unlocks it. Crawl paths may read locks, never write them. Without this, turning on periodic refresh would silently revert every hand-fixed title within a month. `verifyFlag` remains the coarse "stop crawling this record entirely" switch.

**Pools and mutual exclusion.** Sweeps run on `bookmarkSweepExecutor` (1 thread, no queue) rather than the parse pool, so a long round cannot push user-facing adds into `CallerRunsPolicy`. Inside a round, pings go out `AsyncConfig.PING_CONCURRENCY`-wide on `bookmarkPingExecutor` — **this value is coupled to the scrapper's `MAX_CONCURRENT_REQUESTS` (default 32)**: exceed it and `load_shed` returns 503s, which register as `UNKNOWN` and trip the breaker, i.e. too much concurrency makes the sweep abort itself. `ParseLock.sweep(taskLabel)` guarantees one round at a time; `@Async` removed the scheduler's built-in non-overlap guarantee, and being in Redis the lock also covers multi-instance deploys.

### Redis Cache Keys

- `CODE_PHONE:<uid>` / `CODE_EMAIL:<uid>` / `CAPTCHA_CODE:<uid>` — Verification codes (3-15 min TTL)
- `DEFAULT_BACKGROUND_*` — Cached default backgrounds (12h TTL)
- `WECHAT_WORK_ACCESS_TOKEN` — OAuth token (1h TTL)
- `throttle:<uid>:<method>` — Rate limit locks
- `parse:lock:bookmark:<id>` / `parse:lock:dispatch:<userLinkId>` / `parse:lock:sweep:<taskLabel>` — `ParseLock` mutexes (5 min / 5 min / 30 min TTL)

## Coding Conventions

- **Package:** `top.tcyeee.bookmarkify`
- **Service layer:** Interface `I*Service` + implementation `*ServiceImpl` extending MyBatis-Plus `ServiceImpl<Mapper, Entity>`
- **Request/Response DTOs:** Centralized in `entity/Request.kt` and `entity/Response.kt`
- **Error codes:** Defined in `config/exception/ErrorType.kt` (E101–E999)
- **Logging:** `LoggingExtensions.kt` provides a `log` extension property on any receiver. **It does not work inside `ServiceImpl` subclasses** — MyBatis-Plus's `ServiceImpl` carries its own `org.apache.ibatis.logging.Log` member that shadows it, and that interface has no `info()` and no placeholder overloads. There, declare `private val logger = LoggerFactory.getLogger(javaClass)` (as `SiteServiceImpl` / `UserServiceImpl` do)
- **User context:** `BaseUtils.uid()` and `BaseUtils.user()` retrieve current user from Sa-Token session
- **Tests:** minimal coverage in `src/test/kotlin/` (currently a handful of unit tests for parsing/password utils) — this is not a fully tested codebase, don't assume behavior is spec'd by tests

## API Endpoint Groups

| Prefix | Auth | Description |
|---|---|---|
| `/auth/**` | Mixed | Login, SMS/email verification, CAPTCHA |
| `/bookmark/**` | USER | Bookmark CRUD, import, search |
| `/user/**` | USER | Profile, avatar |
| `/background/**` | USER | Background management |
| `/preference/**` | USER | User preference settings |
| `/admin/**` | ADMIN | Admin panel (user/bookmark management) |
| `/ws` | Token param | WebSocket real-time updates |

## Important Notes

- The `bin/` directory contains compiled class output — do not edit files there
- `TestController.kt` (`/test/**`, `@SaIgnore`) is a live scratch endpoint for manually triggering parses — not covered by real tests, don't build on it
- The `server/` package (not `service/`) holds service interfaces — keep this naming when adding services
- Admin login credentials default to `tcyeee@outlook.com` / `admin` in config
- **Scheduled tasks run in production only — `bookmarkify.scheduling.enabled` is `false` in the `dev` profile.** Every task in `ScheduledTasks` writes production data (dispatches parses, rewrites `parse_status`, deletes log rows, reclaims OSS objects), and `application-dev.yml` points at the **production database**. So a casual local `bootRun` used to add a second full set of cron jobs writing to the same database as the deployed instance. Not hypothetical: on 2026-08-06 `sweep_log` recorded two rows for the same round 128 ms apart, and the Redis ownership key named a developer laptop (`cy.local/74447`) — a local instance was sweeping production and had taken the lease out from under the server. The new table surfaced it within minutes of existing. An instance with scheduling off also **abstains from `SingleInstanceGuard` ownership**: it cannot write production data on a timer, so letting it grab the lease would only spam the real instance with hourly errors — a guard that always fires is a guard nobody reads. Turn it back on deliberately (`--bookmarkify.scheduling.enabled=true`) only after pointing the datasource at a local database. **The cost of it being off is silent**: `drainStuckLoading` is the sole consumer for bulk import, so locally imported bookmarks spin forever with no error anywhere — hence the explicit `warn` line at startup in `AppInit`.
- **The API runs as a single instance and cannot currently be scaled horizontally.** Two things assume it: `@Scheduled` tasks have no distributed lock (every instance would run every sweep — duplicate pings, duplicate parse dispatch), and `SessionManager` keeps WebSocket sessions in a process-local `ConcurrentHashMap` (a push only reaches users connected to *that* instance, so roughly half of all `HOME_ITEM_UPDATE` pushes would silently vanish behind a load balancer). Adding a second instance requires ShedLock (or equivalent) plus Redis pub/sub fan-out for WebSocket pushes — `ParseLock` already being in Redis is not sufficient on its own. `SingleInstanceGuard` (`config/init/`) holds a Redis lease and logs an `error` every minute when it sees another holder, because the failure mode is otherwise entirely silent — a second instance starts cleanly and just drops half the pushes. It warns rather than refuses to start (failing to boot over a lock is worse in production); disable with `bookmarkify.single-instance-guard.enabled=false`.
- **Only the scrapper path is SSRF-safe by construction.** With `bookmarkify.config.use-third-party-parser = false` the API itself fetches the user-supplied URL via Jsoup, from a process that can reach the database, Redis and the rest of the internal network. `classifyLinkType != DOMAIN` does not cover this: it only rejects literals (`localhost`, bare IPs), while an ordinary domain can have an A record pointing at `169.254.169.254`, and Jsoup follows redirects by default. `SsrfGuard` now validates every hop (redirects are followed manually), checks **all** resolved addresses rather than just the first, and covers loopback / RFC1918 / link-local / CGNAT (100.64/10) / IPv6 ULA. Rejections surface as `E308`, deliberately distinct from `E304` — "we refused to fetch it" is our own security decision, not evidence that the site is down. `fetchManifest` is guarded too: the manifest URL comes from the target page, i.e. it is attacker-controlled input.
- Icons live in `site_asset` and are **not** joined by any `BookmarkShow` SQL. Anything building a `BookmarkShow` must call `initDisplay(resolved)` with a `SiteAssetResolver` result — the required parameter exists precisely because the earlier `logo` default let several call sites forget, silently degrading the whole desktop to monogram tiles. Use `resolveBatch` for lists.
