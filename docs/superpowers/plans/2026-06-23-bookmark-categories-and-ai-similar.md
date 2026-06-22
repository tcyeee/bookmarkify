# 书签分类管理 & AI 相似网站发现 — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 给管理后台补齐书签分类管理（词表 CRUD + 详情显示/编辑/重跑 AI 归类），并新增「点详情→DeepSeek 推荐相似网站（仅展示）」能力。

**Architecture:** 后端复用已有 `website_category` / `bookmark_category` 多对多模型与 DeepSeek 集成，新增 admin 控制器与服务方法；前端在 Vben Admin（Element Plus）新增「分类管理」页面，并扩展现有书签详情弹窗。两阶段独立交付：阶段一=分类，阶段二=AI 相似网站。

**Tech Stack:** Kotlin 2.1 + Spring Boot 3.5 + MyBatis-Plus + Sa-Token（API）；Vue 3 + TypeScript + Element Plus + Vben Admin（Admin）；DeepSeek `deepseek-chat`（国内 API，无需代理）。

## Global Constraints

- API 端 admin 接口前缀 `/admin/**`，类上加 `@SaCheckRole(value = ["ADMIN"], type = "ADMIN")`；写操作一律用 `POST`（项目约定，替代 PUT/DELETE）。
- 所有 DeepSeek 调用必须 `runCatching` 兜底，失败返回空集合，绝不向上抛、不阻塞主流程。
- Admin 端 Vite dev 代理 `/api/admin` → 去掉 `/api` → `http://localhost:8001`，故前端请求路径写 `/admin/**`。
- git commit message 一律英文。
- Element Plus 组件在 `cleaning/index.vue` 沿用现有 `defineAsyncComponent` 懒加载写法；`ElMessage` 直接 `import { ElMessage } from "element-plus"`（与 `liveness/index.vue` 一致）。
- 后端实体 id 均为 `String`；`BookmarkEntity.id` / `WebsiteCategory.id` / `BookmarkCategory.id` 全是字符串 UUID。

---

## Task 0: 确认数据库表存在（前置）

**Files:** 无（只读验证）

- [ ] **Step 1: 确认 `website_category` 与 `bookmark_category` 物理表存在**

连接 dev 库（schema `bookmarkify`）执行：

```sql
SELECT to_regclass('bookmarkify.website_category'), to_regclass('bookmarkify.bookmark_category');
```

Expected: 两列都非 `NULL`。

- [ ] **Step 2: 若任一为 NULL，补建表**

```sql
CREATE TABLE IF NOT EXISTS bookmarkify.website_category (
    id            VARCHAR(64) PRIMARY KEY,
    slug          VARCHAR(64)  NOT NULL,
    name          VARCHAR(128) NOT NULL,
    description   VARCHAR(512),
    color         VARCHAR(16),
    sort          INT          NOT NULL DEFAULT 0,
    deleted       BOOLEAN      NOT NULL DEFAULT FALSE,
    create_time   TIMESTAMP    NOT NULL DEFAULT now(),
    last_modified TIMESTAMP    NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS bookmarkify.bookmark_category (
    id          VARCHAR(64) PRIMARY KEY,
    bookmark_id VARCHAR(64) NOT NULL,
    category_id VARCHAR(64) NOT NULL,
    source      VARCHAR(32) NOT NULL DEFAULT 'DEEPSEEK',
    create_time TIMESTAMP   NOT NULL DEFAULT now(),
    deleted     BOOLEAN     NOT NULL DEFAULT FALSE
);
CREATE INDEX IF NOT EXISTS idx_bc_bookmark ON bookmarkify.bookmark_category (bookmark_id);
```

> 注：`replaceLinks` 采用「物理删旧 + 插新」幂等策略，故 `bookmark_category` 不加 unique 约束以免与历史软删行冲突（与现有 `BookmarkCategoryServiceImpl` 注释一致）。

- [ ] **Step 3: 不提交**（纯验证 / 建表，无代码改动）

---

## Task 1: 后端 DTO/VO 定义

**Files:**
- Modify: `bookmarkify-api/src/main/kotlin/top/tcyeee/bookmarkify/entity/Response.kt`（新增 `CategoryVO`）
- Modify: `bookmarkify-api/src/main/kotlin/top/tcyeee/bookmarkify/entity/Request.kt`（新增 `CategorySaveParams`、`BookmarkCategoriesParams`）

**Interfaces:**
- Produces:
  - `data class CategoryVO(id: String, slug: String, name: String, color: String?)`
  - `data class CategorySaveParams(id: String?, slug: String, name: String, description: String?, color: String?, sort: Int)`
  - `data class BookmarkCategoriesParams(categoryIds: List<String>)`

- [ ] **Step 1: 在 `Response.kt` 末尾新增 `CategoryVO`**

```kotlin
/** 书签命中的分类（精简视图，用于后台列表/详情展示） */
data class CategoryVO(
    var id: String,
    var slug: String,
    var name: String,
    var color: String? = null,
)
```

- [ ] **Step 2: 在 `Request.kt` 末尾新增两个入参 DTO**

```kotlin
/** 管理后台新增/修改分类词条的入参（id 为空表示新增） */
data class CategorySaveParams(
    val id: String? = null,
    val slug: String,
    val name: String,
    val description: String? = null,
    val color: String? = null,
    val sort: Int = 0,
)

/** 管理后台手动设置某书签分类的入参 */
data class BookmarkCategoriesParams(
    val categoryIds: List<String> = emptyList(),
)
```

- [ ] **Step 3: 编译验证**

Run: `cd bookmarkify-api && ./gradlew compileKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: Commit**

```bash
git add bookmarkify-api/src/main/kotlin/top/tcyeee/bookmarkify/entity/Response.kt \
        bookmarkify-api/src/main/kotlin/top/tcyeee/bookmarkify/entity/Request.kt
git commit -m "feat(api): add CategoryVO and category request DTOs"
```

---

## Task 2: 分类词表 CRUD（service + controller）

**Files:**
- Modify: `bookmarkify-api/src/main/kotlin/top/tcyeee/bookmarkify/server/IWebsiteCategoryService.kt`
- Modify: `bookmarkify-api/src/main/kotlin/top/tcyeee/bookmarkify/server/impl/WebsiteCategoryServiceImpl.kt`
- Create: `bookmarkify-api/src/main/kotlin/top/tcyeee/bookmarkify/controller/admin/AdminCategoryController.kt`

**Interfaces:**
- Consumes: `CategorySaveParams`（Task 1）、`WebsiteCategory` 实体
- Produces:
  - `IWebsiteCategoryService.listAll(): List<WebsiteCategory>`
  - `IWebsiteCategoryService.saveCategory(params: CategorySaveParams): WebsiteCategory`
  - `IWebsiteCategoryService.softDelete(id: String)`
  - HTTP: `POST /admin/category/list`、`POST /admin/category/save`、`POST /admin/category/{id}/delete`

- [ ] **Step 1: 扩展 `IWebsiteCategoryService` 接口**

```kotlin
package top.tcyeee.bookmarkify.server

import com.baomidou.mybatisplus.extension.service.IService
import top.tcyeee.bookmarkify.entity.CategorySaveParams
import top.tcyeee.bookmarkify.entity.entity.WebsiteCategory

interface IWebsiteCategoryService : IService<WebsiteCategory> {
    /** 全部启用的分类，按 sort 升序 */
    fun activeCandidates(): List<WebsiteCategory>

    /** 后台：全部未删除分类，按 sort 升序 */
    fun listAll(): List<WebsiteCategory>

    /** 后台：新增或修改一条分类（id 为空=新增） */
    fun saveCategory(params: CategorySaveParams): WebsiteCategory

    /** 后台：软删一条分类 */
    fun softDelete(id: String)
}
```

- [ ] **Step 2: 实现 `WebsiteCategoryServiceImpl`**

```kotlin
package top.tcyeee.bookmarkify.server.impl

import cn.hutool.core.util.IdUtil
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl
import org.springframework.stereotype.Service
import top.tcyeee.bookmarkify.entity.CategorySaveParams
import top.tcyeee.bookmarkify.entity.entity.WebsiteCategory
import top.tcyeee.bookmarkify.mapper.WebsiteCategoryMapper
import top.tcyeee.bookmarkify.server.IWebsiteCategoryService
import java.time.LocalDateTime

@Service
class WebsiteCategoryServiceImpl :
    IWebsiteCategoryService, ServiceImpl<WebsiteCategoryMapper, WebsiteCategory>() {

    override fun activeCandidates(): List<WebsiteCategory> =
        ktQuery().eq(WebsiteCategory::deleted, false).orderByAsc(WebsiteCategory::sort).list()

    override fun listAll(): List<WebsiteCategory> =
        ktQuery().eq(WebsiteCategory::deleted, false).orderByAsc(WebsiteCategory::sort).list()

    override fun saveCategory(params: CategorySaveParams): WebsiteCategory {
        val now = LocalDateTime.now()
        val entity = if (params.id.isNullOrBlank()) {
            WebsiteCategory(
                id = IdUtil.fastUUID(), slug = params.slug, name = params.name,
                description = params.description, color = params.color, sort = params.sort,
                createTime = now, lastModified = now,
            )
        } else {
            val existed = getById(params.id) ?: throw IllegalArgumentException("分类不存在: ${params.id}")
            existed.slug = params.slug
            existed.name = params.name
            existed.description = params.description
            existed.color = params.color
            existed.sort = params.sort
            existed.lastModified = now
            existed
        }
        saveOrUpdate(entity)
        return entity
    }

    override fun softDelete(id: String) {
        ktUpdate().eq(WebsiteCategory::id, id).set(WebsiteCategory::deleted, true).update()
    }
}
```

- [ ] **Step 3: 新建 `AdminCategoryController`**

```kotlin
package top.tcyeee.bookmarkify.controller.admin

import cn.dev33.satoken.annotation.SaCheckRole
import org.springframework.web.bind.annotation.*
import top.tcyeee.bookmarkify.entity.CategorySaveParams
import top.tcyeee.bookmarkify.entity.entity.WebsiteCategory
import top.tcyeee.bookmarkify.server.IWebsiteCategoryService

@RestController
@SaCheckRole(value = ["ADMIN"], type = "ADMIN")
@RequestMapping("/admin/category")
class AdminCategoryController(
    private val websiteCategoryService: IWebsiteCategoryService,
) {
    @PostMapping("/list")
    fun list(): List<WebsiteCategory> = websiteCategoryService.listAll()

    @PostMapping("/save")
    fun save(@RequestBody params: CategorySaveParams): WebsiteCategory =
        websiteCategoryService.saveCategory(params)

    @PostMapping("/{id}/delete")
    fun delete(@PathVariable id: String) = websiteCategoryService.softDelete(id)
}
```

- [ ] **Step 4: 编译验证**

Run: `cd bookmarkify-api && ./gradlew compileKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 5: Commit**

```bash
git add bookmarkify-api/src/main/kotlin/top/tcyeee/bookmarkify/server/IWebsiteCategoryService.kt \
        bookmarkify-api/src/main/kotlin/top/tcyeee/bookmarkify/server/impl/WebsiteCategoryServiceImpl.kt \
        bookmarkify-api/src/main/kotlin/top/tcyeee/bookmarkify/controller/admin/AdminCategoryController.kt
git commit -m "feat(api): add website category dictionary CRUD endpoints"
```

---

## Task 3: 书签关联分类查询 + 在 admin 列表暴露分类

**Files:**
- Modify: `bookmarkify-api/src/main/kotlin/top/tcyeee/bookmarkify/server/IBookmarkCategoryService.kt`
- Modify: `bookmarkify-api/src/main/kotlin/top/tcyeee/bookmarkify/server/impl/BookmarkCategoryServiceImpl.kt`
- Modify: `bookmarkify-api/src/main/kotlin/top/tcyeee/bookmarkify/entity/Response.kt`（`BookmarkAdminVO` 加字段）
- Modify: `bookmarkify-api/src/main/kotlin/top/tcyeee/bookmarkify/server/impl/BookmarkServiceImpl.kt`（`adminListAll` 回填）

**Interfaces:**
- Consumes: `WebsiteCategory`、`CategoryVO`（Task 1）
- Produces:
  - `IBookmarkCategoryService.categoriesOf(bookmarkIds: Collection<String>): Map<String, List<WebsiteCategory>>`
  - `IBookmarkCategoryService.replaceLinks(bookmarkId: String, categoryIds: List<String>, source: String)`（提升到接口）
  - `BookmarkAdminVO.categories: List<CategoryVO>` 字段

- [ ] **Step 1: 扩展 `IBookmarkCategoryService` 接口**

```kotlin
package top.tcyeee.bookmarkify.server

import com.baomidou.mybatisplus.extension.service.IService
import top.tcyeee.bookmarkify.entity.entity.BookmarkCategory
import top.tcyeee.bookmarkify.entity.entity.BookmarkEntity
import top.tcyeee.bookmarkify.entity.entity.WebsiteCategory

interface IBookmarkCategoryService : IService<BookmarkCategory> {
    /**
     * 为 canonical 书签生成并保存分类（幂等：先删旧关联再插新）。
     * 失败静默，不抛异常、不影响解析主流程。
     */
    fun categorize(bookmark: BookmarkEntity)

    /** 批量查询多个书签各自命中的分类（避免 N+1）。返回 bookmarkId -> 分类列表。 */
    fun categoriesOf(bookmarkIds: Collection<String>): Map<String, List<WebsiteCategory>>

    /** 幂等替换某书签的全部分类关联（物理删旧 + 插新）。source 标记来源（DEEPSEEK / MANUAL）。 */
    fun replaceLinks(bookmarkId: String, categoryIds: List<String>, source: String)
}
```

- [ ] **Step 2: 更新 `BookmarkCategoryServiceImpl`**

把 `replaceLinks` 改为 `override` 并加 `source` 参数；新增 `categoriesOf`；`categorize` 内调用补 `"DEEPSEEK"`。完整替换该类：

```kotlin
package top.tcyeee.bookmarkify.server.impl

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import top.tcyeee.bookmarkify.entity.dto.CategoryCandidate
import top.tcyeee.bookmarkify.entity.entity.BookmarkCategory
import top.tcyeee.bookmarkify.entity.entity.BookmarkEntity
import top.tcyeee.bookmarkify.entity.entity.WebsiteCategory
import top.tcyeee.bookmarkify.mapper.BookmarkCategoryMapper
import top.tcyeee.bookmarkify.server.IApiService
import top.tcyeee.bookmarkify.server.IBookmarkCategoryService
import top.tcyeee.bookmarkify.server.IWebsiteCategoryService

@Service
class BookmarkCategoryServiceImpl(
    private val websiteCategoryService: IWebsiteCategoryService,
    private val apiService: IApiService,
    transactionManager: PlatformTransactionManager,
) : IBookmarkCategoryService, ServiceImpl<BookmarkCategoryMapper, BookmarkCategory>() {

    private val logger = LoggerFactory.getLogger(javaClass)
    private val txTemplate = TransactionTemplate(transactionManager)

    override fun categorize(bookmark: BookmarkEntity) {
        runCatching {
            val categories = websiteCategoryService.activeCandidates()
            if (categories.isEmpty()) {
                logger.debug("[categorize] 字典为空，跳过: bookmarkId=${bookmark.id}")
                return
            }
            val candidates = categories.map { CategoryCandidate(it.slug, it.name, it.description) }
            val slugs = apiService.inferCategories(
                bookmark.title, bookmark.description, bookmark.urlHost, candidates,
            )
            if (slugs.isEmpty()) {
                logger.debug("[categorize] DeepSeek 未返回有效分类: bookmarkId=${bookmark.id}")
                return
            }
            val slugToId = categories.associate { it.slug to it.id }
            val categoryIds = slugs.mapNotNull { slugToId[it] }
            if (categoryIds.isEmpty()) return
            replaceLinks(bookmark.id, categoryIds, "DEEPSEEK")
            logger.debug("[categorize] 分类完成: bookmarkId=${bookmark.id}, slugs=$slugs")
        }.onFailure {
            logger.warn("[categorize] 分类失败(忽略): bookmarkId=${bookmark.id}, err=${it.message}")
        }
    }

    override fun categoriesOf(bookmarkIds: Collection<String>): Map<String, List<WebsiteCategory>> {
        if (bookmarkIds.isEmpty()) return emptyMap()
        val links = ktQuery()
            .`in`(BookmarkCategory::bookmarkId, bookmarkIds)
            .eq(BookmarkCategory::deleted, false)
            .list()
        if (links.isEmpty()) return emptyMap()
        val catById = websiteCategoryService
            .listByIds(links.map { it.categoryId }.distinct())
            .associateBy { it.id }
        return links.groupBy { it.bookmarkId }
            .mapValues { (_, ls) -> ls.mapNotNull { catById[it.categoryId] } }
    }

    /** 幂等替换：物理删除旧关联，再插入新关联（避开 unique 约束与软删冲突）。
     *  用 TransactionTemplate 包住删除+插入，保证原子（@Transactional 在同 bean 自调用下会失效）。 */
    override fun replaceLinks(bookmarkId: String, categoryIds: List<String>, source: String) {
        txTemplate.execute {
            ktUpdate().eq(BookmarkCategory::bookmarkId, bookmarkId).remove()
            saveBatch(categoryIds.map {
                BookmarkCategory(bookmarkId = bookmarkId, categoryId = it, source = source)
            })
        }
    }
}
```

- [ ] **Step 3: `BookmarkAdminVO` 加 `categories` 字段**

在 `Response.kt` 的 `BookmarkAdminVO` 主构造参数末尾（`updateTime` 之后、`)` 之前）加：

```kotlin
    @field:Schema(description = "命中的分类") var categories: List<CategoryVO> = emptyList(),
```

> `BeanUtil.copyProperties` 不会覆盖它（`BookmarkEntity` 无 categories 字段），保持默认空列表，由 `adminListAll` 回填。

- [ ] **Step 4: `adminListAll` 批量回填分类**

把 `BookmarkServiceImpl.adminListAll` 由单行表达式改为：

```kotlin
override fun adminListAll(params: BookmarkSearchParams): IPage<BookmarkAdminVO> {
    val page = baseMapper.selectPage(params.toPage(), params.toWrapper())
        .convert { BookmarkAdminVO(it) }
    val catMap = bookmarkCategoryService.categoriesOf(page.records.map { it.id })
    page.records.forEach { vo ->
        vo.categories = catMap[vo.id].orEmpty()
            .map { CategoryVO(it.id, it.slug, it.name, it.color) }
    }
    return page
}
```

`BookmarkServiceImpl` 顶部 import 补充（若缺）：`import top.tcyeee.bookmarkify.entity.CategoryVO`（`CategoryVO` 在 `entity` 包，`import top.tcyeee.bookmarkify.entity.*` 已覆盖则无需新增）。

- [ ] **Step 5: 编译验证**

Run: `cd bookmarkify-api && ./gradlew compileKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 6: Commit**

```bash
git add bookmarkify-api/src/main/kotlin/top/tcyeee/bookmarkify/server/IBookmarkCategoryService.kt \
        bookmarkify-api/src/main/kotlin/top/tcyeee/bookmarkify/server/impl/BookmarkCategoryServiceImpl.kt \
        bookmarkify-api/src/main/kotlin/top/tcyeee/bookmarkify/entity/Response.kt \
        bookmarkify-api/src/main/kotlin/top/tcyeee/bookmarkify/server/impl/BookmarkServiceImpl.kt
git commit -m "feat(api): expose bookmark categories in admin list with batch loading"
```

---

## Task 4: 手动编辑 / 重跑 AI 归类 端点

**Files:**
- Modify: `bookmarkify-api/src/main/kotlin/top/tcyeee/bookmarkify/server/IBookmarkService.kt`
- Modify: `bookmarkify-api/src/main/kotlin/top/tcyeee/bookmarkify/server/impl/BookmarkServiceImpl.kt`
- Modify: `bookmarkify-api/src/main/kotlin/top/tcyeee/bookmarkify/controller/admin/AdminBookmarkManageController.kt`

**Interfaces:**
- Consumes: `IBookmarkCategoryService.replaceLinks` / `categoriesOf` / `categorize`（Task 3）、`BookmarkCategoriesParams`、`CategoryVO`
- Produces:
  - `IBookmarkService.adminUpdateCategories(bookmarkId: String, categoryIds: List<String>): List<CategoryVO>`
  - `IBookmarkService.adminRecategorize(bookmarkId: String): List<CategoryVO>`
  - HTTP: `POST /admin/bookmark/{id}/categories`、`POST /admin/bookmark/{id}/categorize`

- [ ] **Step 1: `IBookmarkService` 接口新增两方法**

在 `interface IBookmarkService` 内（`adminApplyRefetch` 附近）加：

```kotlin
    /** 管理员手动设置某书签的分类（覆盖式），返回更新后的分类列表 */
    fun adminUpdateCategories(bookmarkId: String, categoryIds: List<String>): List<CategoryVO>

    /** 管理员对某书签重新跑一次 DeepSeek 自动归类，返回更新后的分类列表 */
    fun adminRecategorize(bookmarkId: String): List<CategoryVO>
```

顶部补 import：`import top.tcyeee.bookmarkify.entity.CategoryVO`

- [ ] **Step 2: `BookmarkServiceImpl` 实现两方法**

在 `adminApplyRefetch` 之后加：

```kotlin
    override fun adminUpdateCategories(bookmarkId: String, categoryIds: List<String>): List<CategoryVO> {
        baseMapper.selectById(bookmarkId) ?: throw CommonException(ErrorType.E102)
        bookmarkCategoryService.replaceLinks(bookmarkId, categoryIds, "MANUAL")
        return loadCategoryVOs(bookmarkId)
    }

    override fun adminRecategorize(bookmarkId: String): List<CategoryVO> {
        val bookmark = baseMapper.selectById(bookmarkId) ?: throw CommonException(ErrorType.E102)
        bookmarkCategoryService.categorize(bookmark)
        return loadCategoryVOs(bookmarkId)
    }

    private fun loadCategoryVOs(bookmarkId: String): List<CategoryVO> =
        bookmarkCategoryService.categoriesOf(listOf(bookmarkId))[bookmarkId].orEmpty()
            .map { CategoryVO(it.id, it.slug, it.name, it.color) }
```

- [ ] **Step 3: `AdminBookmarkManageController` 新增两端点**

在「删除单个书签」方法之前加（并补 import `BookmarkCategoriesParams`、`CategoryVO`）：

```kotlin
    // 手动覆盖式设置某书签的分类
    @PostMapping("/{bookmarkId}/categories")
    fun updateCategories(
        @PathVariable bookmarkId: String, @RequestBody params: BookmarkCategoriesParams
    ): List<CategoryVO> = bookmarkService.adminUpdateCategories(bookmarkId, params.categoryIds)

    // 对某书签重新执行 DeepSeek 自动归类
    @PostMapping("/{bookmarkId}/categorize")
    fun recategorize(@PathVariable bookmarkId: String): List<CategoryVO> =
        bookmarkService.adminRecategorize(bookmarkId)
```

控制器顶部 import 补：
```kotlin
import top.tcyeee.bookmarkify.entity.BookmarkCategoriesParams
import top.tcyeee.bookmarkify.entity.CategoryVO
```

- [ ] **Step 4: 编译验证**

Run: `cd bookmarkify-api && ./gradlew compileKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 5: Commit**

```bash
git add bookmarkify-api/src/main/kotlin/top/tcyeee/bookmarkify/server/IBookmarkService.kt \
        bookmarkify-api/src/main/kotlin/top/tcyeee/bookmarkify/server/impl/BookmarkServiceImpl.kt \
        bookmarkify-api/src/main/kotlin/top/tcyeee/bookmarkify/controller/admin/AdminBookmarkManageController.kt
git commit -m "feat(api): add manual category edit and re-categorize endpoints"
```

---

## Task 5: 前端 API 封装（分类 + 书签扩展）

**Files:**
- Create: `bookmarkify-admin/apps/web-ele/src/api/category.ts`
- Modify: `bookmarkify-admin/apps/web-ele/src/api/bookmark.ts`

**Interfaces:**
- Produces:
  - `CategoryEntity`、`getCategoryListApi`、`saveCategoryApi`、`deleteCategoryApi`
  - `CategoryVO`、`SimilarSite`、`BookmarkEntity.categories`、`updateBookmarkCategoriesApi`、`recategorizeBookmarkApi`、`findSimilarSitesApi`

- [ ] **Step 1: 新建 `api/category.ts`**

```ts
import { requestClient } from '#/api/request';

export interface CategoryEntity {
  id: string;
  slug: string;
  name: string;
  description?: string;
  color?: string;
  sort: number;
}

/** 获取全部分类词条 */
export async function getCategoryListApi() {
  return requestClient.post<CategoryEntity[]>('/admin/category/list');
}

/** 新增或修改一条分类（id 为空=新增） */
export async function saveCategoryApi(data: Partial<CategoryEntity>) {
  return requestClient.post<CategoryEntity>('/admin/category/save', data);
}

/** 软删一条分类 */
export async function deleteCategoryApi(id: string) {
  return requestClient.post<void>(`/admin/category/${id}/delete`);
}
```

- [ ] **Step 2: 扩展 `api/bookmark.ts`**

在 `BookmarkEntity` interface 内（`updateTime?` 之后）加一行：

```ts
  categories?: CategoryVO[];
```

在 `BookmarkEntity` 之前（与其它 interface 并列）加：

```ts
export interface CategoryVO {
  id: string;
  slug: string;
  name: string;
  color?: string;
}

export interface SimilarSite {
  name: string;
  domain: string;
  reason: string;
}
```

在文件末尾追加三个函数：

```ts
/** 手动覆盖式设置某书签的分类，返回更新后的分类列表 */
export async function updateBookmarkCategoriesApi(
  bookmarkId: string,
  categoryIds: string[],
) {
  return requestClient.post<CategoryVO[]>(
    `/admin/bookmark/${bookmarkId}/categories`,
    { categoryIds },
  );
}

/** 对某书签重新执行 DeepSeek 自动归类，返回更新后的分类列表 */
export async function recategorizeBookmarkApi(bookmarkId: string) {
  return requestClient.post<CategoryVO[]>(
    `/admin/bookmark/${bookmarkId}/categorize`,
  );
}

/** AI 推荐相似网站（仅展示，不入库） */
export async function findSimilarSitesApi(bookmarkId: string) {
  return requestClient.post<SimilarSite[]>(
    `/admin/bookmark/${bookmarkId}/similar`,
  );
}
```

- [ ] **Step 3: 类型检查**

Run: `cd bookmarkify-admin && pnpm -F @vben/web-ele typecheck 2>/dev/null || cd apps/web-ele && pnpm vue-tsc --noEmit`
Expected: 无新增类型错误（若仓库无该脚本，跳到 Task 6 末的整体 `pnpm build:ele` 统一验证）。

- [ ] **Step 4: Commit**

```bash
git add bookmarkify-admin/apps/web-ele/src/api/category.ts \
        bookmarkify-admin/apps/web-ele/src/api/bookmark.ts
git commit -m "feat(admin): add category api and bookmark category/similar api"
```

---

## Task 6: 分类管理页面 + 路由菜单

**Files:**
- Create: `bookmarkify-admin/apps/web-ele/src/views/bookmark/category/index.vue`
- Modify: `bookmarkify-admin/apps/web-ele/src/router/routes/modules/dashboard.ts`

**Interfaces:**
- Consumes: `getCategoryListApi`、`saveCategoryApi`、`deleteCategoryApi`、`CategoryEntity`（Task 5）

- [ ] **Step 1: 新建分类管理页 `views/bookmark/category/index.vue`**

```vue
<script lang="ts" setup>
import type { CategoryEntity } from "#/api/category";

import { onMounted, reactive, ref } from "vue";

import { Page } from "@vben/common-ui";

import { ElMessage, ElMessageBox } from "element-plus";

import {
  deleteCategoryApi,
  getCategoryListApi,
  saveCategoryApi,
} from "#/api/category";

const loading = ref(false);
const tableData = ref<CategoryEntity[]>([]);
const dialogVisible = ref(false);
const saving = ref(false);

const form = reactive<Partial<CategoryEntity>>({
  id: undefined,
  slug: "",
  name: "",
  description: "",
  color: "",
  sort: 0,
});

function resetForm() {
  form.id = undefined;
  form.slug = "";
  form.name = "";
  form.description = "";
  form.color = "";
  form.sort = 0;
}

async function fetchData() {
  loading.value = true;
  try {
    tableData.value = await getCategoryListApi();
  } finally {
    loading.value = false;
  }
}

function handleAdd() {
  resetForm();
  dialogVisible.value = true;
}

function handleEdit(row: CategoryEntity) {
  form.id = row.id;
  form.slug = row.slug;
  form.name = row.name;
  form.description = row.description ?? "";
  form.color = row.color ?? "";
  form.sort = row.sort ?? 0;
  dialogVisible.value = true;
}

async function handleSave() {
  if (!form.slug?.trim() || !form.name?.trim()) {
    ElMessage.warning("slug 和名称不能为空");
    return;
  }
  saving.value = true;
  try {
    await saveCategoryApi({ ...form });
    ElMessage.success("已保存");
    dialogVisible.value = false;
    await fetchData();
  } finally {
    saving.value = false;
  }
}

async function handleDelete(row: CategoryEntity) {
  await ElMessageBox.confirm(`确认删除分类「${row.name}」？`, "提示", {
    type: "warning",
  });
  await deleteCategoryApi(row.id);
  ElMessage.success("已删除");
  await fetchData();
}

onMounted(fetchData);
</script>

<template>
  <Page auto-content-height>
    <ElCard shadow="never">
      <template #header>
        <div class="flex items-center justify-between">
          <span>分类管理</span>
          <ElButton type="primary" @click="handleAdd">新增分类</ElButton>
        </div>
      </template>
      <ElTable :data="tableData" v-loading="loading" border style="width: 100%">
        <ElTableColumn prop="name" label="名称" min-width="140" />
        <ElTableColumn prop="slug" label="Slug" min-width="140" />
        <ElTableColumn prop="description" label="描述" min-width="220" />
        <ElTableColumn label="颜色" width="100" align="center">
          <template #default="{ row }">
            <span
              v-if="row.color"
              class="inline-block h-4 w-4 rounded"
              :style="{ backgroundColor: row.color }"
            />
            <span v-else class="text-gray-300">-</span>
          </template>
        </ElTableColumn>
        <ElTableColumn prop="sort" label="排序" width="80" align="center" />
        <ElTableColumn label="操作" width="160" align="center">
          <template #default="{ row }">
            <ElButton link type="primary" @click="handleEdit(row)">编辑</ElButton>
            <ElButton link type="danger" @click="handleDelete(row)">删除</ElButton>
          </template>
        </ElTableColumn>
      </ElTable>

      <ElDialog v-model="dialogVisible" :title="form.id ? '编辑分类' : '新增分类'" width="480px">
        <ElForm :model="form" label-width="80px">
          <ElFormItem label="名称">
            <ElInput v-model="form.name" placeholder="分类中文展示名" />
          </ElFormItem>
          <ElFormItem label="Slug">
            <ElInput v-model="form.slug" placeholder="稳定标识，喂给 DeepSeek" />
          </ElFormItem>
          <ElFormItem label="描述">
            <ElInput v-model="form.description" type="textarea" :rows="2" placeholder="给 DeepSeek 的判定说明" />
          </ElFormItem>
          <ElFormItem label="颜色">
            <ElInput v-model="form.color" placeholder="#RRGGBB" />
          </ElFormItem>
          <ElFormItem label="排序">
            <ElInputNumber v-model="form.sort" :min="0" />
          </ElFormItem>
        </ElForm>
        <template #footer>
          <ElButton @click="dialogVisible = false">取消</ElButton>
          <ElButton type="primary" :loading="saving" @click="handleSave">保存</ElButton>
        </template>
      </ElDialog>
    </ElCard>
  </Page>
</template>
```

> 注：Element Plus 组件（`ElCard` / `ElTable` / `ElDialog` / `ElInputNumber` 等）在 web-ele 中已全局注册（参考 `liveness/index.vue` 直接使用未 import 的组件标签），故模板里直接用标签即可；若构建报「组件未注册」，再按 `cleaning/index.vue` 的 `defineAsyncComponent` 方式补声明。

- [ ] **Step 2: 注册路由菜单**

在 `dashboard.ts` 的 `Bookmark` 节点 `children` 数组里（`BookmarkLiveness` 之后）追加：

```ts
      {
        name: 'BookmarkCategory',
        path: '/bookmark/category',
        component: () => import('#/views/bookmark/category/index.vue'),
        meta: {
          icon: 'carbon:tag',
          title: '分类管理',
        },
      },
```

- [ ] **Step 3: 构建验证**

Run: `cd bookmarkify-admin && pnpm build:ele`
Expected: 构建成功，无类型/编译错误。

- [ ] **Step 4: 手动验证**

启动 `pnpm dev:ele`，登录后台（`tcyeee@outlook.com` / `admin`），进入「书签管理 → 分类管理」：新增一条分类（如 name=工具、slug=tools）、编辑、删除均生效，刷新后持久化。

- [ ] **Step 5: Commit**

```bash
git add bookmarkify-admin/apps/web-ele/src/views/bookmark/category/index.vue \
        bookmarkify-admin/apps/web-ele/src/router/routes/modules/dashboard.ts
git commit -m "feat(admin): add category management page and route"
```

---

## Task 7: 书签详情弹窗 — 分类显示 / 编辑 / 重跑 AI

**Files:**
- Modify: `bookmarkify-admin/apps/web-ele/src/views/bookmark/cleaning/index.vue`

**Interfaces:**
- Consumes: `getCategoryListApi`、`CategoryEntity`（Task 5/6）、`updateBookmarkCategoriesApi`、`recategorizeBookmarkApi`、`CategoryVO`（Task 5）

- [ ] **Step 1: 脚本部分新增分类状态与方法**

在 `cleaning/index.vue` `<script setup>` 内，`import { getBookmarkListApi }` 一行替换为：

```ts
import {
  getBookmarkListApi,
  recategorizeBookmarkApi,
  updateBookmarkCategoriesApi,
} from "#/api/bookmark";
import { getCategoryListApi, type CategoryEntity } from "#/api/category";
```

并在文件顶部 import 区补：

```ts
import { ElMessage } from "element-plus";
```

在 `const currentRow = ref<BookmarkEntity | null>(null);` 之后加：

```ts
const categoryDict = ref<CategoryEntity[]>([]);
const editingCategoryIds = ref<string[]>([]);
const savingCategories = ref(false);
const recategorizing = ref(false);

async function loadCategoryDict() {
  if (categoryDict.value.length === 0) {
    categoryDict.value = await getCategoryListApi();
  }
}

async function saveCategories() {
  if (!currentRow.value) return;
  savingCategories.value = true;
  try {
    const updated = await updateBookmarkCategoriesApi(
      currentRow.value.id,
      editingCategoryIds.value,
    );
    currentRow.value.categories = updated;
    syncRowCategories(currentRow.value.id, updated);
    ElMessage.success("分类已保存");
  } finally {
    savingCategories.value = false;
  }
}

async function recategorize() {
  if (!currentRow.value) return;
  recategorizing.value = true;
  try {
    const updated = await recategorizeBookmarkApi(currentRow.value.id);
    currentRow.value.categories = updated;
    editingCategoryIds.value = updated.map((c) => c.id);
    syncRowCategories(currentRow.value.id, updated);
    ElMessage.success(
      updated.length > 0 ? "AI 归类完成" : "AI 未返回分类（检查词表是否为空）",
    );
  } finally {
    recategorizing.value = false;
  }
}

function syncRowCategories(
  id: string,
  categories: BookmarkEntity["categories"],
) {
  const row = tableData.value.find((r) => r.id === id);
  if (row) row.categories = categories;
}
```

- [ ] **Step 2: 打开详情时载入词表并回填已选分类**

把 `handleRowClick` 改为：

```ts
async function handleRowClick(row: BookmarkEntity) {
  currentRow.value = row;
  detailVisible.value = true;
  editingCategoryIds.value = (row.categories ?? []).map((c) => c.id);
  await loadCategoryDict();
}
```

- [ ] **Step 3: 详情弹窗模板加「分类」区块**

在详情弹窗里「状态」区块（`<span class="w-24 text-gray-500">状态</span>` 那个 `div`）之前插入：

```html
          <div class="flex items-start">
            <span class="w-24 text-gray-500">分类</span>
            <div class="flex-1">
              <div class="mb-2 flex flex-wrap gap-1">
                <ElTag
                  v-for="c in currentRow.categories ?? []"
                  :key="c.id"
                  size="small"
                  :color="c.color || undefined"
                  :style="c.color ? { color: '#fff', borderColor: c.color } : {}"
                >
                  {{ c.name }}
                </ElTag>
                <span
                  v-if="(currentRow.categories ?? []).length === 0"
                  class="text-gray-400"
                >
                  暂无分类
                </span>
              </div>
              <ElSelectV2
                v-model="editingCategoryIds"
                :options="categoryDict.map((c) => ({ label: c.name, value: c.id }))"
                multiple
                clearable
                placeholder="选择分类"
                style="width: 100%"
              />
              <div class="mt-2 flex gap-2">
                <ElButton
                  type="primary"
                  size="small"
                  :loading="savingCategories"
                  @click="saveCategories"
                >
                  保存分类
                </ElButton>
                <ElButton
                  size="small"
                  :loading="recategorizing"
                  @click="recategorize"
                >
                  重新 AI 归类
                </ElButton>
              </div>
            </div>
          </div>
```

- [ ] **Step 4: 构建验证**

Run: `cd bookmarkify-admin && pnpm build:ele`
Expected: 构建成功。

- [ ] **Step 5: 手动验证**

后台「书签管理」点开任一书签详情：显示已命中分类标签；多选下拉改分类→「保存分类」→ 列表与详情同步更新；「重新 AI 归类」可触发（词表非空时返回分类）。

- [ ] **Step 6: Commit**

```bash
git add bookmarkify-admin/apps/web-ele/src/views/bookmark/cleaning/index.vue
git commit -m "feat(admin): show/edit/recategorize bookmark categories in detail dialog"
```

---

## Task 8: AI 相似网站 — 后端

**Files:**
- Create: `bookmarkify-api/src/main/kotlin/top/tcyeee/bookmarkify/entity/dto/SimilarSiteModels.kt`
- Modify: `bookmarkify-api/src/main/kotlin/top/tcyeee/bookmarkify/server/IApiService.kt`
- Modify: `bookmarkify-api/src/main/kotlin/top/tcyeee/bookmarkify/server/impl/ApiServiceImpl.kt`
- Modify: `bookmarkify-api/src/main/kotlin/top/tcyeee/bookmarkify/server/IBookmarkService.kt`
- Modify: `bookmarkify-api/src/main/kotlin/top/tcyeee/bookmarkify/server/impl/BookmarkServiceImpl.kt`
- Modify: `bookmarkify-api/src/main/kotlin/top/tcyeee/bookmarkify/controller/admin/AdminBookmarkManageController.kt`
- Test: `bookmarkify-api/src/test/kotlin/top/tcyeee/bookmarkify/SimilarSiteParseTest.kt`

**Interfaces:**
- Produces:
  - `data class SimilarSite(name: String, domain: String, reason: String)`
  - `IApiService.inferSimilarSites(title: String?, description: String?, host: String): List<SimilarSite>`
  - `ApiServiceImpl.parseSimilarSites(content: String): List<SimilarSite>`（internal，纯函数，可测）
  - `IBookmarkService.adminSimilarSites(bookmarkId: String): List<SimilarSite>`
  - HTTP: `POST /admin/bookmark/{id}/similar`

- [ ] **Step 1: 新建 `SimilarSiteModels.kt`**

```kotlin
package top.tcyeee.bookmarkify.entity.dto

/** DeepSeek 推荐的相似网站（仅展示，不入库） */
data class SimilarSite(
    val name: String = "",
    val domain: String = "",
    val reason: String = "",
)
```

- [ ] **Step 2: 写失败测试（JSON 解析容错，纯函数 TDD）**

`src/test/kotlin/top/tcyeee/bookmarkify/SimilarSiteParseTest.kt`：

```kotlin
package top.tcyeee.bookmarkify

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import top.tcyeee.bookmarkify.config.entity.DeepSeekConfig
import top.tcyeee.bookmarkify.config.entity.ScrapperConfig
import top.tcyeee.bookmarkify.server.impl.ApiServiceImpl

class SimilarSiteParseTest {
    private val svc = ApiServiceImpl(
        ScrapperConfig(), DeepSeekConfig(), ObjectMapper().registerKotlinModule(),
    )

    @Test
    fun `parses plain json array`() {
        val raw = """[{"name":"知乎","domain":"zhihu.com","reason":"问答社区"}]"""
        val list = svc.parseSimilarSites(raw)
        assertEquals(1, list.size)
        assertEquals("zhihu.com", list[0].domain)
    }

    @Test
    fun `strips markdown code fence`() {
        val raw = "```json\n[{\"name\":\"A\",\"domain\":\"a.com\",\"reason\":\"r\"}]\n```"
        assertEquals(1, svc.parseSimilarSites(raw).size)
    }

    @Test
    fun `returns empty on garbage`() {
        assertTrue(svc.parseSimilarSites("not json at all").isEmpty())
    }
}
```

> 确认 `ScrapperConfig` 的构造可无参实例化（其为 `@ConfigurationProperties` data class，字段有默认值）。若无默认值，测试里用 `ScrapperConfig(baseUrl = "")` 等显式构造。

- [ ] **Step 3: 运行测试，确认失败**

Run: `cd bookmarkify-api && ./gradlew test --tests 'top.tcyeee.bookmarkify.SimilarSiteParseTest'`
Expected: 编译失败 / FAIL —— `parseSimilarSites` 与 `inferSimilarSites` 尚未定义。

- [ ] **Step 4: `IApiService` 接口新增方法**

在 `interface IApiService` 末尾加：

```kotlin
    /**
     * 通过 DeepSeek（纯知识，不联网）推荐若干功能/定位相似的网站。
     * @return 相似网站列表；失败或无结果返回空列表。
     */
    fun inferSimilarSites(title: String?, description: String?, host: String): List<SimilarSite>
```

顶部 import 补：`import top.tcyeee.bookmarkify.entity.dto.SimilarSite`

- [ ] **Step 5: `ApiServiceImpl` 实现 + 抽出可测纯函数**

顶部 import 补：`import top.tcyeee.bookmarkify.entity.dto.SimilarSite`

在类内末尾（`buildUrl` 之前）加：

```kotlin
    override fun inferSimilarSites(title: String?, description: String?, host: String): List<SimilarSite> {
        val systemPrompt = """
            你是一个网站推荐助手。根据用户给出的网站信息，推荐 5~8 个功能或定位相似的其它网站。
            严格只返回 JSON 数组，每个元素形如 {"name":"网站名","domain":"example.com","reason":"一句话理由"}。
            不要 markdown 代码块，不要任何额外解释文字。domain 只填主域名，不带 http 前缀。
        """.trimIndent()
        val userContent = "host: $host\ntitle: ${title ?: ""}\ndescription: ${description ?: ""}"
        val request = DeepSeekRequest(
            messages = listOf(
                DeepSeekMessage(role = "system", content = systemPrompt),
                DeepSeekMessage(role = "user", content = userContent),
            ),
            maxTokens = 600,
        )
        val responseBody = runCatching {
            HttpUtil.createPost("https://api.deepseek.com/chat/completions")
                .header("Authorization", "Bearer ${deepSeekConfig.apiKey}")
                .header("Content-Type", "application/json")
                .body(objectMapper.writeValueAsString(request))
                .timeout(20000)
                .execute()
                .body()
        }.getOrNull() ?: return emptyList()
        val content = runCatching {
            objectMapper.readValue<DeepSeekResponse>(responseBody)
                .choices?.firstOrNull()?.message?.content
        }.getOrNull() ?: return emptyList()
        return parseSimilarSites(content)
    }

    /** 解析 DeepSeek 返回的文本为相似网站列表：剥离 ```json 围栏后按 JSON 数组解析，失败返回空。 */
    internal fun parseSimilarSites(content: String): List<SimilarSite> {
        val json = content.trim()
            .removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
        return runCatching { objectMapper.readValue<List<SimilarSite>>(json) }.getOrElse { emptyList() }
    }
```

- [ ] **Step 6: 运行测试，确认通过**

Run: `cd bookmarkify-api && ./gradlew test --tests 'top.tcyeee.bookmarkify.SimilarSiteParseTest'`
Expected: PASS（3 个测试）

- [ ] **Step 7: `IBookmarkService` + impl + controller 接线**

`IBookmarkService` 内加：

```kotlin
    /** 管理员：AI 推荐与该书签相似的网站（仅展示） */
    fun adminSimilarSites(bookmarkId: String): List<SimilarSite>
```
顶部 import：`import top.tcyeee.bookmarkify.entity.dto.SimilarSite`

`BookmarkServiceImpl` 内（`adminRecategorize` 之后）加：

```kotlin
    override fun adminSimilarSites(bookmarkId: String): List<SimilarSite> {
        val bookmark = baseMapper.selectById(bookmarkId) ?: throw CommonException(ErrorType.E102)
        return apiService.inferSimilarSites(bookmark.title, bookmark.description, bookmark.urlHost)
    }
```
（`SimilarSite` 已被 `import top.tcyeee.bookmarkify.entity.dto.*`? 否则补 import `import top.tcyeee.bookmarkify.entity.dto.SimilarSite`）

`AdminBookmarkManageController` 内（`recategorize` 之后）加：

```kotlin
    // AI 推荐相似网站（仅展示，不入库）
    @PostMapping("/{bookmarkId}/similar")
    fun similar(@PathVariable bookmarkId: String): List<SimilarSite> =
        bookmarkService.adminSimilarSites(bookmarkId)
```
顶部 import：`import top.tcyeee.bookmarkify.entity.dto.SimilarSite`

- [ ] **Step 8: 全量编译 + 测试**

Run: `cd bookmarkify-api && ./gradlew compileKotlin test --tests 'top.tcyeee.bookmarkify.SimilarSiteParseTest'`
Expected: BUILD SUCCESSFUL，测试通过。

- [ ] **Step 9: Commit**

```bash
git add bookmarkify-api/src/main/kotlin/top/tcyeee/bookmarkify/entity/dto/SimilarSiteModels.kt \
        bookmarkify-api/src/main/kotlin/top/tcyeee/bookmarkify/server/IApiService.kt \
        bookmarkify-api/src/main/kotlin/top/tcyeee/bookmarkify/server/impl/ApiServiceImpl.kt \
        bookmarkify-api/src/main/kotlin/top/tcyeee/bookmarkify/server/IBookmarkService.kt \
        bookmarkify-api/src/main/kotlin/top/tcyeee/bookmarkify/server/impl/BookmarkServiceImpl.kt \
        bookmarkify-api/src/main/kotlin/top/tcyeee/bookmarkify/controller/admin/AdminBookmarkManageController.kt \
        bookmarkify-api/src/test/kotlin/top/tcyeee/bookmarkify/SimilarSiteParseTest.kt
git commit -m "feat(api): add DeepSeek similar-site recommendation endpoint"
```

---

## Task 9: AI 相似网站 — 前端详情弹窗

**Files:**
- Modify: `bookmarkify-admin/apps/web-ele/src/views/bookmark/cleaning/index.vue`

**Interfaces:**
- Consumes: `findSimilarSitesApi`、`SimilarSite`（Task 5）

- [ ] **Step 1: 脚本部分新增相似网站状态与方法**

把 Task 7 加的 bookmark import 扩成（追加 `findSimilarSitesApi` 与 `SimilarSite` 类型）：

```ts
import {
  findSimilarSitesApi,
  getBookmarkListApi,
  recategorizeBookmarkApi,
  updateBookmarkCategoriesApi,
  type SimilarSite,
} from "#/api/bookmark";
```

在分类相关 ref 之后加：

```ts
const similarSites = ref<SimilarSite[]>([]);
const loadingSimilar = ref(false);
const similarLoaded = ref(false);

async function findSimilar() {
  if (!currentRow.value) return;
  loadingSimilar.value = true;
  try {
    similarSites.value = await findSimilarSitesApi(currentRow.value.id);
    similarLoaded.value = true;
  } finally {
    loadingSimilar.value = false;
  }
}
```

在 `handleRowClick` 内（打开详情时）重置状态，于 `editingCategoryIds.value = ...` 之后加：

```ts
  similarSites.value = [];
  similarLoaded.value = false;
```

- [ ] **Step 2: 详情弹窗模板末尾加「相似网站」区块**

在详情内容容器（`<div v-if="currentRow" class="space-y-3 text-sm">`）的最后一个子 `div`（更新时间）之后、容器闭合 `</div>` 之前插入：

```html
          <div class="flex items-start">
            <span class="w-24 text-gray-500">相似网站</span>
            <div class="flex-1">
              <ElButton
                size="small"
                :loading="loadingSimilar"
                @click="findSimilar"
              >
                查找相似网站
              </ElButton>
              <ul v-if="similarSites.length > 0" class="mt-2 space-y-2">
                <li
                  v-for="s in similarSites"
                  :key="s.domain"
                  class="rounded border border-gray-100 p-2"
                >
                  <div class="font-medium">
                    {{ s.name }}
                    <a
                      :href="`https://${s.domain}`"
                      target="_blank"
                      rel="noopener noreferrer"
                      class="ml-1 text-blue-500"
                    >
                      {{ s.domain }}
                    </a>
                  </div>
                  <div class="text-gray-500">{{ s.reason }}</div>
                </li>
              </ul>
              <div
                v-else-if="similarLoaded"
                class="mt-2 text-gray-400"
              >
                未找到相似网站
              </div>
            </div>
          </div>
```

- [ ] **Step 3: 构建验证**

Run: `cd bookmarkify-admin && pnpm build:ele`
Expected: 构建成功。

- [ ] **Step 4: 手动验证**

后台书签详情点「查找相似网站」→ loading → 列出 5~8 条（名称 + 可点击域名 + 理由）；DeepSeek 失败/无结果时显示「未找到相似网站」。

- [ ] **Step 5: Commit**

```bash
git add bookmarkify-admin/apps/web-ele/src/views/bookmark/cleaning/index.vue
git commit -m "feat(admin): add AI similar-site discovery in bookmark detail dialog"
```

---

## 端到端验证（全部完成后）

- [ ] API 整体编译 + 测试：`cd bookmarkify-api && ./gradlew compileKotlin test`
- [ ] Admin 整体构建：`cd bookmarkify-admin && pnpm build:ele` 与 `pnpm lint`
- [ ] 手动联调：起 API（`--spring.profiles.active=dev`）+ `pnpm dev:ele`，验证：分类 CRUD → 词表非空 → 书签详情显示分类 → 手动改分类 → 重跑 AI 归类 → 查找相似网站，全链路通畅。
- [ ] 确认 `bookmarkify.deepseek.apiKey` 在 dev 配置中有效（归类与相似网站均依赖）。
