# AGENTS.md - Bookmarkify API

## Agent Roles and Responsibilities

This document defines the specialized agent roles for working on the Bookmarkify API codebase.

---

### Backend Developer Agent

**Scope:** Kotlin/Spring Boot application code under `src/main/kotlin/`

**Key responsibilities:**
- Implement new API endpoints following the existing Controller → Service → Mapper pattern
- Add new service interfaces in `server/` with implementations in `server/impl/`
- Create new MyBatis-Plus mapper interfaces in `mapper/`
- Define request/response DTOs in `entity/Request.kt` and `entity/Response.kt`
- Add new database entities in `entity/entity/` with `@TableName` annotations

**Conventions to follow:**
- Service interfaces: `I<Name>Service`, implementations: `<Name>ServiceImpl` extending `ServiceImpl<Mapper, Entity>`
- Use `BaseUtils.uid()` and `BaseUtils.user()` for current user context (Sa-Token session)
- Use `logger()` delegate from `LoggingExtensions.kt` for logging
- Throw `CommonException(ErrorType.XXX)` for business errors; add new codes in `ErrorType.kt`
- All responses auto-wrapped by `GlobalExceptionHandler` into `ResultWrapper{ok, code, data, msg}`
- Rate-limit sensitive endpoints with `@Throttle` annotation
- Cache expensive queries with `@RedisCache` annotation and define key in `RedisType` enum
- Use `@SaIgnore` for unauthenticated endpoints; admin endpoints need `@SaCheckRole(["ADMIN"], type="ADMIN")`

**Key files to understand first:**
- `config/exception/ErrorType.kt` — Error code registry
- `config/filter/SaTokenConfigure.kt` — Auth interceptor routing
- `config/result/ResultWrapper.kt` — Response envelope
- `entity/Request.kt` / `entity/Response.kt` — DTO definitions
- `utils/BaseUtils.kt` — User context helpers
- `utils/StpKit.kt` — Dual Sa-Token realm definitions

---

### Kafka/Async Processing Agent

**Scope:** Asynchronous bookmark parsing pipeline

**Key responsibilities:**
- Handle Kafka message consumption in `config/kafka/KafkaMessageListener.kt`
- Add new Kafka actions in `config/kafka/KafkaEnums.kt` (`KafkaMethodsEnums`)
- Produce messages via `IKafkaMessageService`
- Ensure WebSocket notifications are sent after async operations via `SocketUtils.homeItemUpdate()`

**Flow to understand:**
1. Producer sends JSON to topic `BOOKMARKIFY` with `method` + `data` fields
2. `KafkaMessageListener.listen()` routes by `KafkaMethodsEnums` action
3. Consumer calls `BookmarkServiceImpl` or `WebsiteParser` for parsing
4. Results pushed to user via `SessionManager` → WebSocket

**Key files:**
- `config/kafka/KafkaMessageListener.kt` — Consumer dispatcher
- `config/kafka/KafkaEnums.kt` — Topic and action enums
- `server/impl/KafkaMessageServiceImpl.kt` — Producer service
- `utils/WebsiteParser.kt` — HTML scraper (Jsoup)
- `utils/SocketUtils.kt` — WebSocket push helper
- `config/websocket/SessionManager.kt` — UID-to-session registry

---

### Database/Data Agent

**Scope:** PostgreSQL schema, MyBatis-Plus mappers, data access

**Key responsibilities:**
- Design and evolve database tables in the `bookmarkify` schema
- Write custom SQL queries using `@Select` / `@Update` annotations on mapper interfaces
- Maintain entity classes with `@TableName`, `@TableId`, `@TableField` annotations
- Handle pagination using MyBatis-Plus `Page<T>` and `PageBean`

**Important patterns:**
- All entities use `@TableId(type = IdType.ASSIGN_UUID)` for UUID primary keys
- Logical delete via `@TableLogic` on `deleted` field (0 = active, 1 = deleted)
- Auto-fill `createTime` / `updateTime` via MyBatis-Plus MetaObjectHandler
- Custom JOIN queries in `BookmarkUserLinkMapper.kt` (bookmark + logo data)
- Sort order stored as JSON in `user_preference.nodeSortMapJson` (not in node table)

**Key files:**
- `mapper/BookmarkUserLinkMapper.kt` — Complex JOIN queries
- `entity/entity/` — All database entity definitions
- `config/MybatisPlusConfig.kt` — MyBatis-Plus configuration

---

### Auth/Security Agent

**Scope:** Authentication, authorization, and rate limiting

**Key responsibilities:**
- Manage Sa-Token dual-realm configuration (USER + ADMIN)
- Configure route-level auth in `SaTokenConfigure.kt`
- Implement verification flows (SMS, email, image CAPTCHA)
- Maintain rate limiting (PreRequestFilter global + @Throttle per-method)

**Security architecture:**
- `StpKit.USER` — Regular user realm, cookie-based `deviceUid` + Sa-Token session
- `StpKit.ADMIN` — Admin realm, completely separate session store
- `PreRequestFilter` — Global 20 req/sec per token (in-memory sliding window)
- `@Throttle` — Per-user per-method cooldown via Redis SETNX
- WebSocket auth via `?token=` query parameter at handshake

**Key files:**
- `utils/StpKit.kt` — Dual StpLogic definitions
- `config/filter/SaTokenConfigure.kt` — Interceptor path routing
- `config/filter/PreRequestFilter.kt` — Global rate limiter
- `config/throttle/ThrottleAspect.kt` — AOP rate limiter
- `controller/auth/LoginController.kt` — Auth endpoints
- `config/websocket/AuthHandshakeInterceptor.kt` — WebSocket auth

---

### Infrastructure/DevOps Agent

**Scope:** Build, deployment, configuration

**Key responsibilities:**
- Maintain `build.gradle` dependencies and plugins
- Manage Spring profiles (`dev`, `online`) in `application-*.yml`
- Configure Docker builds via `DockerFile`
- Manage environment variables and secrets

**Configuration hierarchy:**
1. `application.yml` — Base config (always loaded)
2. `application-dev.yml` — Dev overrides (local services)
3. `application-online.yml` — Prod overrides (all from env vars)

**Key files:**
- `build.gradle` — Dependencies and build config
- `settings.gradle` — Project name (`bookmarkify-api`)
- `DockerFile` — Container build (OpenJDK 21)
- `.evn.local.example` — Required environment variables reference
- `src/main/resources/application*.yml` — Spring configuration

**Build commands:**
```bash
./gradlew bootJar          # Build executable JAR
./gradlew bootRun          # Run with default profile
./gradlew dependencies     # Show dependency tree
```

---

### File Storage Agent

**Scope:** Aliyun OSS integration, file upload/management

**Key responsibilities:**
- Upload files to Aliyun OSS via `OssUtils`
- Generate signed/resized URLs for serving files
- Handle avatar uploads, background images, website logos, and OG images
- Manage file records in `user_file` table

**OSS bucket structure:**
```
bookmarkify/
├── avatar/               # User avatars (5 MB limit)
├── bac/                  # Background images (10 MB limit)
├── logo/<bookmarkId>/    # Website logos (1 MB limit, sized by px width)
└── og/<bookmarkId>/      # OG images (5 MB limit)
```

**Key files:**
- `utils/OssUtils.kt` — OSS client, upload, sign, resize
- `utils/FileUtils.kt` — File type detection, base64 conversion
- `server/impl/FileServiceImpl.kt` — File upload service
- `entity/entity/UserFile.kt` — File record entity

---

## Cross-Cutting Concerns

### Error Handling
All agents should use `CommonException(ErrorType.XXX)` for business errors. The `GlobalExceptionHandler` catches these and returns standardized `ResultWrapper` responses. New error codes must be added to `ErrorType.kt` with a unique `Exxx` code.

### Logging
Use `private val log = logger()` (from `LoggingExtensions.kt`) in any class. Follow existing log level conventions: `log.info` for business events, `log.error` for failures, `log.warn` for non-critical issues.

### Testing
No tests currently exist. When adding tests:
- Place under `src/test/kotlin/top/tcyeee/bookmarkify/`
- Use Spring Boot test starter (already in `build.gradle`)
- Mock external services (OSS, SMS, Kafka, Redis)

### Code Organization
- **Do not** modify files under `bin/` — those are compiled outputs
- Request/Response DTOs go in `entity/Request.kt` and `entity/Response.kt` (centralized)
- Enums go in `entity/enums/` or within the relevant entity file
- The `server/` package (not `service/`) holds service interfaces — follow this naming
