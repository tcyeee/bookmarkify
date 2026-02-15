# CLAUDE.md - Bookmarkify API

## Project Overview

**Bookmarkify API** (书签鸭) is a bookmark management backend service built with Kotlin + Spring Boot. It allows users to save, organize, and enrich browser bookmarks. The system automatically scrapes website metadata (title, description, favicon, OG image), stores enriched data, and delivers real-time updates via WebSocket.

## Tech Stack

- **Language:** Kotlin 2.1.20 on JVM 21
- **Framework:** Spring Boot 3.5.0-M2
- **Build:** Gradle 8.9
- **Database:** PostgreSQL (schema: `bookmarkify`)
- **ORM:** MyBatis-Plus 3.5.15
- **Cache/Session:** Redis (Spring Data Redis)
- **Messaging:** Apache Kafka (topic: `BOOKMARKIFY`)
- **Auth:** Sa-Token 1.40.0 (dual realm: USER + ADMIN)
- **Object Storage:** Alibaba Cloud OSS
- **SMS:** Alibaba Cloud SMS
- **Email:** WeChat Work API (not SMTP)
- **HTML Parsing:** Jsoup 1.17.2
- **API Docs:** Knife4j (OpenAPI 3)
- **WebSocket:** Spring WebSocket at `/ws`

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
│   ├── kafka/                        # KafkaMessageListener, topic/action enums
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
│   └── enums/                        # Enum types
├── mapper/                           # MyBatis-Plus BaseMapper interfaces
├── server/                           # Service interfaces (I*Service)
│   └── impl/                         # Service implementations
└── utils/                            # Utility classes (OSS, Redis, parser, etc.)
```

### Resource Files

```
src/main/resources/
├── application.yml                   # Base config (port 7001, Kafka, Sa-Token)
├── application-dev.yml               # Dev profile (local DB/Redis)
├── application-online.yml            # Prod profile (all env vars)
└── banner.txt                        # ASCII banner
```

## Build & Run

```bash
# Build
./gradlew bootJar

# Run (dev profile)
./gradlew bootRun --args='--spring.profiles.active=dev'

# Docker
docker build -f DockerFile -t bookmarkify-api .
docker run -p 7001:7001 bookmarkify-api
```

**Server port:** 7001

## Environment Variables

Required variables (see `.evn.local.example`):

| Variable | Purpose |
|---|---|
| `BOOKMARKIFY_POSTGRES_HOST/USERNAME/PASSWORD` | PostgreSQL connection |
| `BOOKMARKIFY_REDIS_HOST/PORT/PASSWORD` | Redis connection |
| `BOOKMARKIFY_FILE_UPLOAD_DIR` / `PREFIX` | Local file storage paths |
| `BOOKMARKIFY_WECHAT_WORK_CORPID/CORPSECRET` | WeChat Work email API |
| `BOOKMARKIFY_ALIYUN_OSS_*` | Aliyun OSS (endpoint, keys, bucket, domain) |
| `BOOKMARKIFY_KAFKA_BOOTSTRAP_SERVERS` | Kafka broker address |
| `SPRING_KAFKA_BOOTSTRAP_SERVERS` | Kafka (base config) |

## Architecture

### Request Flow

```
HTTP → PreRequestFilter (20 req/s) → SaTokenConfigure (auth) → Controller → Service → Mapper → PostgreSQL
                                                                                          ↕
                                                                              Redis (cache/session)
                                                                                          ↕
                                                                              Kafka (async parsing)
                                                                                          ↕
                                                                              WebSocket (push updates)
```

### Key Patterns

1. **Dual auth realms:** `StpKit.USER` for regular users, `StpKit.ADMIN` for admin panel. Completely separate session stores.
2. **Anonymous-first identity:** Every visitor gets a `deviceUid` cookie and auto-created `sys_user`. Users "upgrade" by verifying phone/email.
3. **Bookmark deduplication:** The `bookmark` table stores one canonical record per domain. User-specific data lives in `bookmark_user_link`.
4. **Async parsing pipeline:** Adding a bookmark returns a loading placeholder immediately. Kafka consumer parses the website, uploads logos to OSS, and pushes the result via WebSocket (`HOME_ITEM_UPDATE`).
5. **Desktop layout tree:** `user_layout_node` stores a tree with `parentId` (ROOT → folders → bookmarks). Sort order is a JSON map in `user_preference` to avoid bulk DB writes.
6. **AOP caching/throttling:** `@RedisCache` for method-level caching; `@Throttle` for per-user rate limiting via Redis SETNX.
7. **Unified response wrapper:** `GlobalExceptionHandler` (ResponseBodyAdvice) wraps all responses in `ResultWrapper{ok, code, data, msg}`.

### Database Tables

| Table | Description |
|---|---|
| `sys_user` | Users (anonymous + registered, role: USER/ADMIN) |
| `bookmark` | Canonical bookmark records (one per domain) |
| `bookmark_user_link` | User's personal bookmark copy (title, desc, URL) |
| `user_layout_node` | Desktop layout tree (bookmark, folder, function nodes) |
| `bookmark_function` | System function items (e.g., Settings) |
| `bookmark_tag` / `bookmark_tag_link` | User tags (many-to-many) |
| `user_preference` | Per-user preferences (background, layout, sort order) |
| `background_config` / `background_image` / `background_gradient` | Background settings |
| `user_file` | Uploaded file records (avatar, background) |
| `website_logo` | Logo/OG metadata for bookmarks |
| `sms_record` | SMS send logs |

### Kafka Topics & Actions

Single topic `BOOKMARKIFY`, actions:
- `BOOKMARK_PARSE` — Re-parse a bookmark (cron / startup)
- `PARSE_NOTICE_EXISTING` — Parse + WebSocket push (single add)
- `BOOKMARK_PARSE_AND_RESET_USER_ITEM` — Parse + bind user link (import)

### Redis Cache Keys

- `CODE_PHONE:<uid>` / `CODE_EMAIL:<uid>` / `CAPTCHA_CODE:<uid>` — Verification codes (3-15 min TTL)
- `DEFAULT_BACKGROUND_*` — Cached default backgrounds (12h TTL)
- `WECHAT_WORK_ACCESS_TOKEN` — OAuth token (1h TTL)
- `throttle:<uid>:<method>` — Rate limit locks

## Coding Conventions

- **Package:** `top.tcyeee.bookmarkify`
- **Service layer:** Interface `I*Service` + implementation `*ServiceImpl` extending MyBatis-Plus `ServiceImpl<Mapper, Entity>`
- **Request/Response DTOs:** Centralized in `entity/Request.kt` and `entity/Response.kt`
- **Error codes:** Defined in `config/exception/ErrorType.kt` (E101–E999)
- **Logging:** `LoggingExtensions.kt` provides `logger()` delegate for any class
- **User context:** `BaseUtils.uid()` and `BaseUtils.user()` retrieve current user from Sa-Token session
- **No tests exist** — `src/test/` directory is absent

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
- `TestController.kt` is fully commented out (dead code)
- `HomeItem` / `HomeItemMapper` are legacy — replaced by `UserLayoutNode` system
- The `server/` package (not `service/`) holds service interfaces
- Admin login credentials default to `tcyeee@outlook.com` / `admin` in config
