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
3. **Bookmark deduplication:** The `bookmark` table stores one canonical record per domain. User-specific data lives in `bookmark_user_link`.
4. **Async parsing pipeline:** Adding a bookmark returns a loading placeholder immediately. A Spring `ApplicationEvent` is published; an `@Async` listener (`BookmarkParseEventListener`, running on the `bookmarkParseExecutor` thread pool) parses the website, uploads logos to OSS, and pushes the result via WebSocket (`HOME_ITEM_UPDATE`). Failed parses are not retried inline — see *Reconciliation* below.
   - **Bulk import publishes no events at all.** `importBookmarkFile` only writes rows (`bookmark_user_link.bookmark_id = 'LOADING'` marks the unfinished ones). Fanning out thousands of events would saturate the parse pool *and* its bounded queue, after which `CallerRunsPolicy` runs the remaining scrapes on the caller — which is the Tomcat request thread. `drainStuckLoading()` (every 30s) feeds the pool from the DB instead, sized to the queue's free capacity.
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
| `bookmark` | Canonical bookmark records (one per domain) |
| `bookmark_user_link` | User's personal bookmark copy (title, desc, URL) |
| `user_layout_node` | Desktop layout tree (bookmark, folder, function nodes) |
| `layout_node_function` | System function items attached to a layout node (e.g., Settings) |
| `user_preference` | Per-user preferences (background, layout, sort order) |
| `background_config` / `background_image` / `background_gradient` | Background settings |
| `oss_object` | **The ledger for every object in the OSS bucket** — one row per set of bytes, no owner. Replaced `user_file`; see root `FILE-SYSTEM-REFACTOR.md` |
| `site_asset` / `site_page_meta` / `scrape_snapshot` / `site_display_pref` | Crawl results + display prefs (replaced `bookmark_logo`; see root `CLAUDE.md`) |
| `bookmark_ping_log` | One row per liveness probe (`outcome` = ALIVE/DEAD/UNKNOWN), purged after 90 days |
| `ai_call_log` | One row per DeepSeek call, **including request/response bodies** — see below |
| `system_config` | Generic key-value config (JSON), e.g. the liveness sweep intervals |
| `category` / `bookmark_category` | Category dictionary + bookmark↔category links |

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
| `retryUnreachableBookmarks()` | hourly, :30 | `UNREACHABLE` + `next_check_at <= now()` | Recovery of failed sites (these two own `pingWebsite`) |

`drainStuckLoading` keys off the layout node rather than `parse_status` on purpose: when a bookmark scrapes fine but the user-link rebind or node flip fails, `parse_status` is `SUCCESS` and every status-based task skips it — while the user's tile spins forever.

**`drainStuckLoading` has a retry budget, and E307 must not consume it.** `findStuckLoading` is `ORDER BY created_at ASC LIMIT n`; the dispatch lock only makes in-flight rows skip, it does not stop them from still being at the head of that ordering. So a batch of rows that can never settle would permanently occupy those `n` slots and starve everything behind them — the back half of an import silently never finishing. `bookmark_user_link.dispatch_attempts` caps this at `MAX_DISPATCH_ATTEMPTS` (5), after which `terminateExhaustedLoading` settles the row as a source-less bookmark (node flipped, no canonical record — the user's own title and URL still render). Crucially, the two E307 early-return branches call `forgiveDispatchAttempt` to **reset the counter**: the budget exists to catch "this row is broken", and E307 means *we* are broken. Without the reset, a 30-minute scrapper outage burns every backlogged row's budget and they all get permanently downgraded on recovery.

**Every write to `parse_status` goes through `markParseSucceeded()` / `markParseUnreachable()`.** Those four fields are mutually constrained (SUCCESS implies `isActivity = true` and a null `parseErrMsg`), and forgetting the `scheduleAfterParse*` line raises no error — it just leaves `next_check_at` frozen, so the row is either re-selected every round or never selected again. The five-line block used to be copy-pasted at ten call sites.

**The unique index is what prevents duplicate tiles, not `assertNotAlreadyLinked`.** That check is check-then-act with no atomicity; what actually held the line was the 1-second `@Throttle` on `addOne` — a UX facility that gets widened when users complain, and that `ThrottleAspect` deliberately fails **open** when Redis is down. `uk_bul_uid_bookmark` now backs it, and `insertNodeAndLink` translates `DuplicateKeyException` into E126 (same pattern as `getOrCreateByUrl` converging on `uk_bookmark_canonical`).

**Observability:** `drainStuckLoading` emits `stuckLoadingStats` every tick — how many tiles are spinning right now, how long the oldest has been spinning, how many of those are import backlog. Anything past the 30-minute stale threshold logs at `warn`. This is the one real SLI for the add path; nothing else in the system answers it (`scrapper_call_log` is per-call, `bookmark_ping_log` is per-sweep).

**`COALESCE(update_time, create_time)` in `checkAll` is load-bearing.** A fresh `BookmarkEntity` has `updateTime = null`, and SQL `NULL < ?` is never true, so a plain `update_time <` predicate silently excludes exactly the never-parsed rows that need reconciling most.

### Liveness sweeps

Read this before touching `pingSweep`, `LivenessPolicy`, or the `next_check_at` columns.

**`pingWebsite` returns three states, not a boolean.** `PingOutcome.UNKNOWN` means *our* chain failed (scrapper unreachable, auth wrong, `load_shed` 503, contract mismatch) — it never reaches `bookmark`. Only `ALIVE`/`DEAD` are facts about the site. The classification reuses `classifyScrapperError`, the same helper `scrape` uses: `E304` → `DEAD`, everything else → `UNKNOWN`. Collapsing these into `false` is what used to let one scrapper outage rewrite hundreds of healthy bookmarks per hour as `UNREACHABLE`.

**A sweep probes the whole batch first, then writes.** In between sits `LivenessPolicy.breakerReason`, which aborts the round if >50% came back `UNKNOWN` (≥10 samples) or >90% came back `DEAD` (≥20 samples). The second rule catches the case the first cannot: the scrapper is up but its egress is broken, so it *honestly* reports `alive=false` for everything. Ping logs are still written on an aborted round — that batch is the evidence of what broke. Order matters; probing and writing in one pass makes the breaker useless.

**Scheduling state lives in its own columns.** `next_check_at` is the only cursor the sweeps read; `last_check_at` / `last_parse_at` / `consecutive_fail` carry the rest. `update_time` went back to meaning "record last modified" — when it doubled as the cursor, an admin editing a title postponed that bookmark's next sweep by a full cycle, and a successful ping polluted the field's public meaning. `next_check_at IS NULL` counts as due, so a row that somehow missed its scheduling write gets picked up instead of vanishing. **Every site that writes `parse_status` must call `scheduleAfterParseSuccess()` / `scheduleAfterParseFailure()`** — miss one and that row either repeats every round or is never selected again.

**Backoff and archival** (`LivenessPolicy`): `ALIVE` → +`activeCheckIntervalHours`; `DEAD` → `abnormalCheckIntervalHours × 2^(fail-1)`, capped at 16×; `UNKNOWN` → +1h and **no** increment of `consecutive_fail` (our own outage must not push the whole table to the end of the backoff curve). At 10 consecutive failures the row becomes `ParseStatusEnum.ARCHIVED`, which drops out of both sweeps — nothing pings a domain that has been gone for two months, and the `LIMIT` slots stay free for rows that matter. `isActivity` stays false, so users still see it as a dead bookmark.

**Content refresh is what makes this an "update" mechanism.** `shouldRefreshContent` re-scrapes when `last_parse_at` is older than `contentRefreshIntervalDays` (default 30). The old condition (`alive && !isActivity`) was dead code — `isActivity=false` never coexists with `parse_status=SUCCESS` — so healthy sites were never re-crawled at all.

**Manual edits are protected per field.** `bookmark.locked_fields` (`BookmarkLockedField`) lists what a crawl may not overwrite. Editing a field in the admin locks it; explicitly accepting a crawled value (`adminRefresh`, `adminApplyRefetch` with `useNewTitle`) unlocks it. Crawl paths may read locks, never write them. Without this, turning on periodic refresh would silently revert every hand-fixed title within a month. `verifyFlag` remains the coarse "stop crawling this record entirely" switch.

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
- **The API runs as a single instance and cannot currently be scaled horizontally.** Two things assume it: `@Scheduled` tasks have no distributed lock (every instance would run every sweep — duplicate pings, duplicate parse dispatch), and `SessionManager` keeps WebSocket sessions in a process-local `ConcurrentHashMap` (a push only reaches users connected to *that* instance, so roughly half of all `HOME_ITEM_UPDATE` pushes would silently vanish behind a load balancer). Adding a second instance requires ShedLock (or equivalent) plus Redis pub/sub fan-out for WebSocket pushes — `ParseLock` already being in Redis is not sufficient on its own. `SingleInstanceGuard` (`config/init/`) holds a Redis lease and logs an `error` every minute when it sees another holder, because the failure mode is otherwise entirely silent — a second instance starts cleanly and just drops half the pushes. It warns rather than refuses to start (failing to boot over a lock is worse in production); disable with `bookmarkify.single-instance-guard.enabled=false`.
- **Only the scrapper path is SSRF-safe by construction.** With `bookmarkify.config.use-third-party-parser = false` the API itself fetches the user-supplied URL via Jsoup, from a process that can reach the database, Redis and the rest of the internal network. `classifyLinkType != DOMAIN` does not cover this: it only rejects literals (`localhost`, bare IPs), while an ordinary domain can have an A record pointing at `169.254.169.254`, and Jsoup follows redirects by default. `SsrfGuard` now validates every hop (redirects are followed manually), checks **all** resolved addresses rather than just the first, and covers loopback / RFC1918 / link-local / CGNAT (100.64/10) / IPv6 ULA. Rejections surface as `E308`, deliberately distinct from `E304` — "we refused to fetch it" is our own security decision, not evidence that the site is down. `fetchManifest` is guarded too: the manifest URL comes from the target page, i.e. it is attacker-controlled input.
- Icons live in `site_asset` and are **not** joined by any `BookmarkShow` SQL. Anything building a `BookmarkShow` must call `initDisplay(resolved)` with a `SiteAssetResolver` result — the required parameter exists precisely because the earlier `logo` default let several call sites forget, silently degrading the whole desktop to monogram tiles. Use `resolveBatch` for lists.
