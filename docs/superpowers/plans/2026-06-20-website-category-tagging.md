# 网站自动分类标签（DeepSeek）Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 书签（canonical 网站记录）解析完成后，调用 DeepSeek 从固定受控词表中为该网站挑选 1~N 个分类标签并落库。

**Architecture:** 新增两张表 `website_category`（分类字典，预先 seed）与 `bookmark_category`（网站↔分类 关联表）。在统一解析收口 `BookmarkServiceImpl.parseBookmark()` 成功后触发分类编排：加载字典 → 调 DeepSeek 选 slug → slug 映射 categoryId → 幂等替换该书签的关联。纯后端，不改前端/API 响应体/WebSocket。

**Tech Stack:** Kotlin 2.1 + Spring Boot 3.5 + MyBatis-Plus 3.5 + PostgreSQL（schema `bookmarkify`）+ DeepSeek API（复用现有 `DeepSeekRequest/Response` + Hutool HttpUtil）。

## Global Constraints

- 包名根：`top.tcyeee.bookmarkify`。
- Service 约定：接口 `I*Service` + 实现 `*ServiceImpl extends ServiceImpl<Mapper, Entity>`，放 `server/` 包（非 `service/`）。
- 实体：`entity/entity/` 下，MyBatis-Plus `@TableName` + `@TableId`，String 主键，构造时用 `cn.hutool.core.util.IdUtil.fastUUID()`。
- Mapper：`mapper/` 下，`@Mapper interface X : BaseMapper<Entity>`。
- DB：schema `bookmarkify`，snake_case 表名/列名，软删列 `deleted`，时间列 `create_time`。
- **本项目无测试框架**（`src/test/` 不存在）。每个任务的验证 = `./gradlew compileKotlin` 编译通过 + 指定的人工/日志核对；不新增测试框架。
- 失败静默：DeepSeek 调用/解析失败只记日志，**绝不**中断书签解析主流程、不抛异常（与现有 `inferAndSetAppName` 一致）。
- DeepSeek 配置已存在：`bookmarkify.deepseek.api-key`（`DeepSeekConfig`，已被 `@ConfigurationPropertiesScan` 自动注册）；endpoint `https://api.deepseek.com/chat/completions`。
- **不动**遗留的 `bookmark_tag` / `bookmark_tag_link`（每用户个人标签，与本功能无关）。
- 分类挂在 canonical `bookmark`（一域一条，全站共享），不是每用户。

---

### Task 1: 数据库迁移（2 张表 + seed 词表）

**Files:**
- Create: `bookmarkify-api/sql/2026-06-20-website-category.sql`

**Interfaces:**
- Produces: 表 `bookmarkify.website_category(id, slug, name, description, color, sort, deleted, create_time, last_modified)`，`unique(slug)`；表 `bookmarkify.bookmark_category(id, bookmark_id, category_id, source, create_time, deleted)`，`unique(bookmark_id, category_id)`。seed 16 行分类，id 形如 `cat_<slug>`。

- [ ] **Step 1: 编写迁移 SQL**

```sql
-- 网站自动分类标签：分类字典表 + 网站↔分类 关联表
-- 日期: 2026-06-20
-- schema: bookmarkify
-- 说明: 全新表，无存量数据；可重复执行（IF NOT EXISTS / ON CONFLICT DO NOTHING）。

-- 1) 分类字典表（受控词表，预先 seed）
CREATE TABLE IF NOT EXISTS bookmarkify.website_category (
    id            varchar(64)  PRIMARY KEY,
    slug          varchar(64)  NOT NULL,
    name          varchar(64)  NOT NULL,
    description   varchar(500),
    color         varchar(16),
    sort          int          NOT NULL DEFAULT 0,
    deleted       boolean      NOT NULL DEFAULT false,
    create_time   timestamp    NOT NULL DEFAULT now(),
    last_modified timestamp    NOT NULL DEFAULT now(),
    CONSTRAINT uk_website_category_slug UNIQUE (slug)
);

-- 2) 网站↔分类 关联表
CREATE TABLE IF NOT EXISTS bookmarkify.bookmark_category (
    id          varchar(64) PRIMARY KEY,
    bookmark_id varchar(64) NOT NULL,
    category_id varchar(64) NOT NULL,
    source      varchar(32) NOT NULL DEFAULT 'DEEPSEEK',
    create_time timestamp   NOT NULL DEFAULT now(),
    deleted     boolean     NOT NULL DEFAULT false,
    CONSTRAINT uk_bookmark_category UNIQUE (bookmark_id, category_id)
);
CREATE INDEX IF NOT EXISTS idx_bookmark_category_bookmark ON bookmarkify.bookmark_category (bookmark_id);

-- 3) seed 固定词表（id 形如 cat_<slug>，deterministic，便于重复执行）
INSERT INTO bookmarkify.website_category (id, slug, name, sort) VALUES
    ('cat_dev',      'dev',      '开发',     10),
    ('cat_design',   'design',   '设计',     20),
    ('cat_ai',       'ai',       'AI',       30),
    ('cat_tool',     'tool',     '效率工具', 40),
    ('cat_social',   'social',   '社交',     50),
    ('cat_video',    'video',    '影视',     60),
    ('cat_music',    'music',    '音乐',     70),
    ('cat_shopping', 'shopping', '购物',     80),
    ('cat_news',     'news',     '新闻资讯', 90),
    ('cat_study',    'study',    '学习教育', 100),
    ('cat_finance',  'finance',  '金融',     110),
    ('cat_game',     'game',     '游戏',     120),
    ('cat_read',     'read',     '阅读',     130),
    ('cat_job',      'job',      '求职招聘', 140),
    ('cat_gov',      'gov',      '政务',     150),
    ('cat_other',    'other',    '其他',     160)
ON CONFLICT (slug) DO NOTHING;
```

- [ ] **Step 2: 校验 SQL 语法（不依赖运行库）**

Run: `grep -c "INSERT\|CREATE TABLE" bookmarkify-api/sql/2026-06-20-website-category.sql`
Expected: 输出 `3`（2 个 CREATE TABLE + 1 个 INSERT）。
> 若有可用的本地 PostgreSQL，可进一步 `psql -f` 实跑确认；否则人工核对列名与下方实体字段一一对应。

- [ ] **Step 3: Commit**

```bash
git add bookmarkify-api/sql/2026-06-20-website-category.sql
git commit -m "feat(db): add website_category + bookmark_category tables and seed taxonomy"
```

---

### Task 2: 实体 + Mapper

**Files:**
- Create: `bookmarkify-api/src/main/kotlin/top/tcyeee/bookmarkify/entity/entity/WebsiteCategory.kt`
- Create: `bookmarkify-api/src/main/kotlin/top/tcyeee/bookmarkify/mapper/WebsiteCategoryMapper.kt`
- Create: `bookmarkify-api/src/main/kotlin/top/tcyeee/bookmarkify/mapper/BookmarkCategoryMapper.kt`

**Interfaces:**
- Consumes: 表结构（Task 1）。
- Produces: `WebsiteCategory(id, slug, name, description, color, sort, deleted, createTime, lastModified)` 与 `BookmarkCategory(id, bookmarkId, categoryId, source, createTime, deleted)`（含便捷构造器 `BookmarkCategory(bookmarkId, categoryId)`）；`WebsiteCategoryMapper : BaseMapper<WebsiteCategory>`、`BookmarkCategoryMapper : BaseMapper<BookmarkCategory>`。

- [ ] **Step 1: 创建实体 `WebsiteCategory.kt`**

```kotlin
package top.tcyeee.bookmarkify.entity.entity

import com.baomidou.mybatisplus.annotation.TableId
import com.baomidou.mybatisplus.annotation.TableName
import com.fasterxml.jackson.annotation.JsonIgnore
import io.swagger.v3.oas.annotations.media.Schema
import java.time.LocalDateTime

/** 网站分类字典（受控词表，预先 seed） */
@TableName("website_category")
data class WebsiteCategory(
    @TableId var id: String,
    @field:Schema(description = "稳定 slug，喂给 DeepSeek/未来筛选") var slug: String,
    @field:Schema(description = "分类中文展示名") var name: String,
    @field:Schema(description = "给 DeepSeek 的判定说明") var description: String? = null,
    @field:Schema(description = "预留 UI 颜色") var color: String? = null,
    @field:Schema(description = "展示顺序") var sort: Int = 0,

    @JsonIgnore @field:Schema(description = "是否删除") var deleted: Boolean = false,
    @JsonIgnore @field:Schema(description = "创建时间") var createTime: LocalDateTime = LocalDateTime.now(),
    @JsonIgnore @field:Schema(description = "更新时间") var lastModified: LocalDateTime = LocalDateTime.now(),
)
```

- [ ] **Step 2: 创建实体 `BookmarkCategory.kt`**（与 WebsiteCategory 同文件追加，或新建文件 `BookmarkCategory.kt`；这里新建独立文件）

Create: `bookmarkify-api/src/main/kotlin/top/tcyeee/bookmarkify/entity/entity/BookmarkCategory.kt`

```kotlin
package top.tcyeee.bookmarkify.entity.entity

import cn.hutool.core.util.IdUtil
import com.baomidou.mybatisplus.annotation.TableId
import com.baomidou.mybatisplus.annotation.TableName
import com.fasterxml.jackson.annotation.JsonIgnore
import io.swagger.v3.oas.annotations.media.Schema
import java.time.LocalDateTime

/** 网站(canonical bookmark) ↔ 分类 关联 */
@TableName("bookmark_category")
data class BookmarkCategory(
    @TableId var id: String = IdUtil.fastUUID(),
    @field:Schema(description = "canonical 书签ID") var bookmarkId: String,
    @field:Schema(description = "分类ID(website_category.id)") var categoryId: String,
    @field:Schema(description = "来源") var source: String = "DEEPSEEK",

    @JsonIgnore @field:Schema(description = "创建时间") var createTime: LocalDateTime = LocalDateTime.now(),
    @JsonIgnore @field:Schema(description = "是否删除") var deleted: Boolean = false,
) {
    constructor(bookmarkId: String, categoryId: String) : this(
        id = IdUtil.fastUUID(), bookmarkId = bookmarkId, categoryId = categoryId,
    )
}
```

- [ ] **Step 3: 创建两个 Mapper**

`WebsiteCategoryMapper.kt`：

```kotlin
package top.tcyeee.bookmarkify.mapper

import com.baomidou.mybatisplus.core.mapper.BaseMapper
import org.apache.ibatis.annotations.Mapper
import top.tcyeee.bookmarkify.entity.entity.WebsiteCategory

@Mapper
interface WebsiteCategoryMapper : BaseMapper<WebsiteCategory>
```

`BookmarkCategoryMapper.kt`：

```kotlin
package top.tcyeee.bookmarkify.mapper

import com.baomidou.mybatisplus.core.mapper.BaseMapper
import org.apache.ibatis.annotations.Mapper
import top.tcyeee.bookmarkify.entity.entity.BookmarkCategory

@Mapper
interface BookmarkCategoryMapper : BaseMapper<BookmarkCategory>
```

- [ ] **Step 4: 编译**

Run: `cd bookmarkify-api && ./gradlew compileKotlin`
Expected: BUILD SUCCESSFUL。

- [ ] **Step 5: Commit**

```bash
git add bookmarkify-api/src/main/kotlin/top/tcyeee/bookmarkify/entity/entity/WebsiteCategory.kt \
        bookmarkify-api/src/main/kotlin/top/tcyeee/bookmarkify/entity/entity/BookmarkCategory.kt \
        bookmarkify-api/src/main/kotlin/top/tcyeee/bookmarkify/mapper/WebsiteCategoryMapper.kt \
        bookmarkify-api/src/main/kotlin/top/tcyeee/bookmarkify/mapper/BookmarkCategoryMapper.kt
git commit -m "feat(api): add WebsiteCategory/BookmarkCategory entities and mappers"
```

---

### Task 3: DeepSeek `inferCategories` 推断

**Files:**
- Create: `bookmarkify-api/src/main/kotlin/top/tcyeee/bookmarkify/entity/dto/CategoryModels.kt`
- Modify: `bookmarkify-api/src/main/kotlin/top/tcyeee/bookmarkify/server/IApiService.kt`
- Modify: `bookmarkify-api/src/main/kotlin/top/tcyeee/bookmarkify/server/impl/ApiServiceImpl.kt`

**Interfaces:**
- Consumes: 现有 `DeepSeekRequest`、`DeepSeekResponse`、`DeepSeekMessage`、`deepSeekConfig`、`objectMapper`。
- Produces: `data class CategoryCandidate(slug, name, description)`；`IApiService.inferCategories(title: String?, description: String?, host: String, candidates: List<CategoryCandidate>): List<String>`，返回**已校验**（只含 candidates 中存在的 slug）、去重的 slug 列表；任何失败返回 `emptyList()`。

- [ ] **Step 1: 创建 DTO `CategoryModels.kt`**

```kotlin
package top.tcyeee.bookmarkify.entity.dto

/** 传给 DeepSeek 的候选分类（来自 website_category 字典） */
data class CategoryCandidate(
    val slug: String,
    val name: String,
    val description: String? = null,
)
```

- [ ] **Step 2: 在 `IApiService` 增加方法声明**

在 `interface IApiService { ... }` 内、`inferAppName` 之后追加：

```kotlin
    /**
     * 通过 DeepSeek 从固定候选词表中为网站挑选分类 slug（可多个）。
     * @return 命中的 slug 列表（已按 candidates 校验、去重）；失败或无结果返回空列表。
     */
    fun inferCategories(
        title: String?,
        description: String?,
        host: String,
        candidates: List<CategoryCandidate>,
    ): List<String>
```

并补充 import：`import top.tcyeee.bookmarkify.entity.dto.CategoryCandidate`

- [ ] **Step 3: 在 `ApiServiceImpl` 实现 `inferCategories`**

在 `class ApiServiceImpl` 内追加（同时补 import `top.tcyeee.bookmarkify.entity.dto.CategoryCandidate`）：

```kotlin
    override fun inferCategories(
        title: String?,
        description: String?,
        host: String,
        candidates: List<CategoryCandidate>,
    ): List<String> {
        if (candidates.isEmpty()) return emptyList()
        val allowed = candidates.map { it.slug }.toSet()

        val catalogue = candidates.joinToString("\n") { c ->
            "- ${c.slug}（${c.name}）${c.description?.let { "：$it" } ?: ""}"
        }
        val systemPrompt = """
            你是一个网站分类助手。下面是允许使用的分类列表（slug 及含义）：
            $catalogue
            根据用户给出的网站信息，从上面的列表中选出 1~3 个最贴切的分类。
            规则：只返回 slug 本身，多个用英文逗号分隔；只能用列表里出现过的 slug；
            不要任何解释、标点或额外文字。实在无法判断时返回 other。
        """.trimIndent()
        val userContent = "host: $host\ntitle: ${title ?: ""}\ndescription: ${description ?: ""}"

        val request = DeepSeekRequest(
            messages = listOf(
                DeepSeekMessage(role = "system", content = systemPrompt),
                DeepSeekMessage(role = "user", content = userContent),
            ),
            maxTokens = 40,
        )

        val responseBody = runCatching {
            HttpUtil.createPost("https://api.deepseek.com/chat/completions")
                .header("Authorization", "Bearer ${deepSeekConfig.apiKey}")
                .header("Content-Type", "application/json")
                .body(objectMapper.writeValueAsString(request))
                .timeout(10000)
                .execute()
                .body()
        }.getOrNull() ?: return emptyList()

        val raw = runCatching {
            objectMapper.readValue<DeepSeekResponse>(responseBody)
                .choices?.firstOrNull()?.message?.content
        }.getOrNull() ?: return emptyList()

        return raw.split(',', '，', '\n', ' ')
            .map { it.trim().lowercase() }
            .filter { it.isNotBlank() && it in allowed }
            .distinct()
    }
```

> 说明：`DeepSeekRequest.maxTokens` 当前默认 20，这里显式传 40 以容纳多个 slug。已有 `import com.fasterxml.jackson.module.kotlin.readValue` 与 `HttpUtil`，无需新增。

- [ ] **Step 4: 编译**

Run: `cd bookmarkify-api && ./gradlew compileKotlin`
Expected: BUILD SUCCESSFUL。

- [ ] **Step 5: Commit**

```bash
git add bookmarkify-api/src/main/kotlin/top/tcyeee/bookmarkify/entity/dto/CategoryModels.kt \
        bookmarkify-api/src/main/kotlin/top/tcyeee/bookmarkify/server/IApiService.kt \
        bookmarkify-api/src/main/kotlin/top/tcyeee/bookmarkify/server/impl/ApiServiceImpl.kt
git commit -m "feat(api): add DeepSeek inferCategories website classification"
```

---

### Task 4: Service 层（字典加载 + 分类编排）

**Files:**
- Create: `bookmarkify-api/src/main/kotlin/top/tcyeee/bookmarkify/server/IWebsiteCategoryService.kt`
- Create: `bookmarkify-api/src/main/kotlin/top/tcyeee/bookmarkify/server/impl/WebsiteCategoryServiceImpl.kt`
- Create: `bookmarkify-api/src/main/kotlin/top/tcyeee/bookmarkify/server/IBookmarkCategoryService.kt`
- Create: `bookmarkify-api/src/main/kotlin/top/tcyeee/bookmarkify/server/impl/BookmarkCategoryServiceImpl.kt`

**Interfaces:**
- Consumes: `WebsiteCategoryMapper`、`BookmarkCategoryMapper`（Task 2）；`IApiService.inferCategories` + `CategoryCandidate`（Task 3）；`BookmarkEntity`（含 `id/title/description/urlHost/parseStatus`）。
- Produces:
  - `IWebsiteCategoryService.activeCandidates(): List<WebsiteCategory>`（`deleted=false`，按 `sort` 升序）。
  - `IBookmarkCategoryService.categorize(bookmark: BookmarkEntity)`：编排「加载字典 → 调 DeepSeek → slug→categoryId → 幂等替换关联」；失败静默。

- [ ] **Step 1: `IWebsiteCategoryService` + 实现**

`IWebsiteCategoryService.kt`：

```kotlin
package top.tcyeee.bookmarkify.server

import com.baomidou.mybatisplus.extension.service.IService
import top.tcyeee.bookmarkify.entity.entity.WebsiteCategory

interface IWebsiteCategoryService : IService<WebsiteCategory> {
    /** 全部启用的分类，按 sort 升序 */
    fun activeCandidates(): List<WebsiteCategory>
}
```

`WebsiteCategoryServiceImpl.kt`：

```kotlin
package top.tcyeee.bookmarkify.server.impl

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl
import org.springframework.stereotype.Service
import top.tcyeee.bookmarkify.entity.entity.WebsiteCategory
import top.tcyeee.bookmarkify.mapper.WebsiteCategoryMapper
import top.tcyeee.bookmarkify.server.IWebsiteCategoryService

@Service
class WebsiteCategoryServiceImpl :
    IWebsiteCategoryService, ServiceImpl<WebsiteCategoryMapper, WebsiteCategory>() {

    override fun activeCandidates(): List<WebsiteCategory> =
        ktQuery().eq(WebsiteCategory::deleted, false).orderByAsc(WebsiteCategory::sort).list()
}
```

- [ ] **Step 2: `IBookmarkCategoryService` 接口**

`IBookmarkCategoryService.kt`：

```kotlin
package top.tcyeee.bookmarkify.server

import com.baomidou.mybatisplus.extension.service.IService
import top.tcyeee.bookmarkify.entity.entity.BookmarkCategory
import top.tcyeee.bookmarkify.entity.entity.BookmarkEntity

interface IBookmarkCategoryService : IService<BookmarkCategory> {
    /**
     * 为 canonical 书签生成并保存分类（幂等：先删旧关联再插新）。
     * 失败静默，不抛异常、不影响解析主流程。
     */
    fun categorize(bookmark: BookmarkEntity)
}
```

- [ ] **Step 3: `BookmarkCategoryServiceImpl` 编排实现**

`BookmarkCategoryServiceImpl.kt`：

```kotlin
package top.tcyeee.bookmarkify.server.impl

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import top.tcyeee.bookmarkify.entity.dto.CategoryCandidate
import top.tcyeee.bookmarkify.entity.entity.BookmarkCategory
import top.tcyeee.bookmarkify.entity.entity.BookmarkEntity
import top.tcyeee.bookmarkify.mapper.BookmarkCategoryMapper
import top.tcyeee.bookmarkify.server.IApiService
import top.tcyeee.bookmarkify.server.IBookmarkCategoryService
import top.tcyeee.bookmarkify.server.IWebsiteCategoryService

@Service
class BookmarkCategoryServiceImpl(
    private val websiteCategoryService: IWebsiteCategoryService,
    private val apiService: IApiService,
) : IBookmarkCategoryService, ServiceImpl<BookmarkCategoryMapper, BookmarkCategory>() {

    private val log = LoggerFactory.getLogger(javaClass)

    override fun categorize(bookmark: BookmarkEntity) {
        runCatching {
            val categories = websiteCategoryService.activeCandidates()
            if (categories.isEmpty()) {
                log.debug("[categorize] 字典为空，跳过: bookmarkId=${bookmark.id}")
                return
            }
            val candidates = categories.map { CategoryCandidate(it.slug, it.name, it.description) }
            val slugs = apiService.inferCategories(
                bookmark.title, bookmark.description, bookmark.urlHost, candidates,
            )
            if (slugs.isEmpty()) {
                log.debug("[categorize] DeepSeek 未返回有效分类: bookmarkId=${bookmark.id}")
                return
            }
            val slugToId = categories.associate { it.slug to it.id }
            val categoryIds = slugs.mapNotNull { slugToId[it] }
            if (categoryIds.isEmpty()) return
            replaceLinks(bookmark.id, categoryIds)
            log.debug("[categorize] 分类完成: bookmarkId=${bookmark.id}, slugs=$slugs")
        }.onFailure {
            log.warn("[categorize] 分类失败(忽略): bookmarkId=${bookmark.id}, err=${it.message}")
        }
    }

    /** 幂等替换：物理删除旧关联，再插入新关联（避开 unique 约束与软删冲突） */
    @Transactional
    fun replaceLinks(bookmarkId: String, categoryIds: List<String>) {
        ktUpdate().eq(BookmarkCategory::bookmarkId, bookmarkId).remove()
        saveBatch(categoryIds.map { BookmarkCategory(bookmarkId, it) })
    }
}
```

- [ ] **Step 4: 编译**

Run: `cd bookmarkify-api && ./gradlew compileKotlin`
Expected: BUILD SUCCESSFUL。

- [ ] **Step 5: Commit**

```bash
git add bookmarkify-api/src/main/kotlin/top/tcyeee/bookmarkify/server/IWebsiteCategoryService.kt \
        bookmarkify-api/src/main/kotlin/top/tcyeee/bookmarkify/server/impl/WebsiteCategoryServiceImpl.kt \
        bookmarkify-api/src/main/kotlin/top/tcyeee/bookmarkify/server/IBookmarkCategoryService.kt \
        bookmarkify-api/src/main/kotlin/top/tcyeee/bookmarkify/server/impl/BookmarkCategoryServiceImpl.kt
git commit -m "feat(api): add website category service and categorize orchestration"
```

---

### Task 5: 接入解析收口 `parseBookmark`

**Files:**
- Modify: `bookmarkify-api/src/main/kotlin/top/tcyeee/bookmarkify/server/impl/BookmarkServiceImpl.kt`（构造器注入 + `parseBookmark` 末尾触发）

**Interfaces:**
- Consumes: `IBookmarkCategoryService.categorize`（Task 4）；`ParseStatusEnum.SUCCESS/BLOCKED`。
- Produces: 单个添加 / 批量导入 / 定时重解析 三条路径在解析成功后均触发分类（一处接入全覆盖）。

- [ ] **Step 1: 构造器注入 `IBookmarkCategoryService`**

在 `BookmarkServiceImpl(...)` 构造参数列表中（如 `bookmarkFunctionMapper` 之后、`transactionManager` 之前）加入：

```kotlin
    private val bookmarkCategoryService: top.tcyeee.bookmarkify.server.IBookmarkCategoryService,
```

> 或在文件顶部 import `top.tcyeee.bookmarkify.server.IBookmarkCategoryService` 后写短名 `bookmarkCategoryService: IBookmarkCategoryService,`。

- [ ] **Step 2: 在 `parseBookmark` 成功后触发分类**

将 `parseBookmark`（约 238 行）末尾：

```kotlin
        return if (projectConfig.useThirdPartyParser) parseByApi(bookmark) else parseLocally(bookmark)
```

改为：

```kotlin
        val parsed = if (projectConfig.useThirdPartyParser) parseByApi(bookmark) else parseLocally(bookmark)
        if (parsed.parseStatus == ParseStatusEnum.SUCCESS || parsed.parseStatus == ParseStatusEnum.BLOCKED) {
            bookmarkCategoryService.categorize(parsed)
        }
        return parsed
```

> `ParseStatusEnum` 已在文件顶部 import。分类发生在元信息落库之后；`categorize` 内部已 try/catch，绝不影响解析主流程。

- [ ] **Step 3: 编译**

Run: `cd bookmarkify-api && ./gradlew compileKotlin`
Expected: BUILD SUCCESSFUL。

- [ ] **Step 4: 人工验证（需本地 DB + DeepSeek key）**

1. 执行 `bookmarkify-api/sql/2026-06-20-website-category.sql` 建表 + seed。
2. `./gradlew bootRun --args='--spring.profiles.active=dev'` 启动。
3. 通过前端或接口添加一个新书签（如 `https://github.com`）。
4. 等异步解析完成后查库：
   `SELECT * FROM bookmarkify.bookmark_category WHERE bookmark_id = (SELECT id FROM bookmarkify.bookmark WHERE url_host='github.com');`
   Expected: 出现 1~3 行，`category_id` 指向合理分类（如 `cat_dev`）。
5. 再次触发同一书签重解析（或重启后 `checkAll` 对账），确认关联未重复累加（仍 1~3 行）。

Expected: 关联正确写入且幂等；DeepSeek/网络异常时书签照常解析、日志出现 `[categorize] ... 失败(忽略)` 而非抛错。

- [ ] **Step 5: Commit**

```bash
git add bookmarkify-api/src/main/kotlin/top/tcyeee/bookmarkify/server/impl/BookmarkServiceImpl.kt
git commit -m "feat(api): trigger DeepSeek website categorization after parse"
```

---

## Self-Review

**Spec coverage:**
- 两张新表 + seed → Task 1 ✅
- 实体/Mapper/Service 约定 → Task 2、4 ✅
- DeepSeek `inferCategories`（受控词表、maxTokens 调大、slug 校验）→ Task 3 ✅
- 字典驱动（加分类无需改代码）→ Task 4 `activeCandidates` 从表加载 ✅
- 挂载点 `parseBookmark` 统一收口、仅 SUCCESS/BLOCKED 触发 → Task 5 ✅
- 重解析幂等（物理删除再插）→ Task 4 `replaceLinks` ✅
- 失败静默 → Task 3 返回 emptyList + Task 4 try/catch ✅
- 范围外（不改前端/响应体/WS、不动旧 tag 表）→ 计划未触及这些文件 ✅

**Placeholder scan:** 无 TBD/TODO；所有代码步骤给出完整代码。

**Type consistency:** `inferCategories(title, description, host, candidates)` 在 Task 3 定义、Task 4 调用一致；`CategoryCandidate(slug, name, description)` 一致；`activeCandidates()`、`categorize(bookmark)`、`replaceLinks(bookmarkId, categoryIds)` 跨任务一致；`WebsiteCategory.deleted/sort/slug/id` 字段与 SQL 列一致。

**测试说明:** 本项目无测试框架，验证以 `compileKotlin` + Task 5 的人工/日志核对替代 TDD（已在 Global Constraints 标注）。
