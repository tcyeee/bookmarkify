# 书签分类管理 & AI 相似网站发现 — 设计文档

> 日期：2026-06-23
> 范围：`bookmarkify-api`（后端）+ `bookmarkify-admin`（管理后台）
> 入口页面：`bookmarkify-admin/apps/web-ele/src/views/bookmark/cleaning/index.vue`

## 背景与现状

后端**已存在**完整的分类基础设施，缺的只是「管理后台」这一层与一个全新的「AI 相似网站」能力：

- `website_category`（受控分类词表：`slug` / `name` / `description` / `color` / `sort`）— 实体 `WebsiteCategory.kt`，服务 `WebsiteCategoryServiceImpl.activeCandidates()`
- `bookmark_category`（书签 ↔ 分类 多对多关联，含 `source` 字段）— 实体 `BookmarkCategory.kt`
- `BookmarkCategoryServiceImpl.categorize(bookmark)` — 解析新书签时**自动**调用 DeepSeek 从词表挑 1~3 个分类，已接入 `BookmarkServiceImpl:303`
- `IApiService.inferCategories(...)` — DeepSeek 选 slug 的实现
- DeepSeek 集成：`DeepSeekConfig`（`bookmarkify.deepseek.apiKey`）、`DeepSeekModels`、`ApiServiceImpl`

**结论**：「一个书签可同时属于多种分类」的数据模型与自动归类已就绪。本设计补齐后台管理层 + 新增 AI 相似网站。

## 决策记录（来自需求澄清）

- 任务一后台范围：分类词表 CRUD 页面 + 详情弹窗显示分类 + 手动编辑书签分类 + 重新触发 AI 归类（四项全要）。
- 任务二技术路线：**DeepSeek 纯知识推荐**（国内 API，无需 clash 代理；缺点是可能过时/臆造，可接受）。
- 任务二结果用途：**仅展示**（名称 + 域名 + 理由），不入库、不一键加书签。

---

## 阶段一：书签分类

### 后端 `bookmarkify-api`

**1. 分类词表 CRUD** — 新增 `controller/admin/AdminCategoryController.kt`，`@SaCheckRole("ADMIN")`，`@RequestMapping("/admin/category")`：

| 方法 | 路径 | 说明 |
|---|---|---|
| `POST` | `/admin/category/list` | 列出全部未删除分类（按 `sort` 升序） |
| `POST` | `/admin/category/save` | 新增 / 修改（按 `id` 是否存在 upsert；新建时生成 id） |
| `POST` | `/admin/category/{id}/delete` | 软删（`deleted=true`） |

`IWebsiteCategoryService` 扩展：`listAll()`、`save(WebsiteCategory)`、`softDelete(id)`。

**2. 详情暴露分类** — `BookmarkAdminVO`（`entity/Response.kt`）新增字段：

```kotlin
@field:Schema(description = "命中的分类") var categories: List<CategoryVO> = emptyList()
```

`CategoryVO{ id, slug, name, color }`（新建轻量 VO）。

回填策略（避免 N+1）：`adminListAll` 分页查询后，收集当前页 `bookmarkIds` → 一次性查 `bookmark_category` + `website_category` → 批量回填。`IBookmarkCategoryService` 新增：

```kotlin
fun categoriesOf(bookmarkIds: Collection<String>): Map<String, List<WebsiteCategory>>
```

单条 VO（`adminApplyRefetch` 等返回路径）同样回填。

**3. 手动编辑书签分类** — `AdminBookmarkManageController` 新增：

```
POST /admin/bookmark/{bookmarkId}/categories   body: { categoryIds: List<String> }
```

复用已有 `BookmarkCategoryServiceImpl.replaceLinks`，将其提升到 `IBookmarkCategoryService` 接口并加 `source` 参数（手动编辑传 `"MANUAL"`）。返回更新后的 `List<CategoryVO>`。

**4. 重新触发 AI 归类** — `AdminBookmarkManageController` 新增：

```
POST /admin/bookmark/{bookmarkId}/categorize
```

载入书签实体 → 调已有 `categorize(bookmark)` → 返回最新 `List<CategoryVO>`。

### 后台 `bookmarkify-admin`

- **新页面** `views/bookmark/category/index.vue`：ElTable 列出分类（名称/slug/描述/颜色块/排序）+ 工具栏「新增」+ 行内「编辑/删除」+ 增改 ElDialog 表单（slug/name/description/color/sort）。
- **路由 & 菜单**：`router/routes/modules/dashboard.ts` 在 `/bookmark` 父级下新增 `/bookmark/category`，菜单标题「分类管理」，与「书签管理」「活性检测」并列。
- **新 API** `api/category.ts`：`getCategoryListApi` / `saveCategoryApi` / `deleteCategoryApi`，对应 `CategoryEntity{ id, slug, name, description?, color?, sort }`。
- **`cleaning/index.vue` 详情弹窗** 增加「分类」区块：
  - 只读：彩色分类标签（用 category.color）。
  - 编辑：`ElSelectV2` 多选（选项来自分类词表）+「保存分类」按钮（调 `updateBookmarkCategoriesApi`）。
  - 「重新 AI 归类」按钮（调 `recategorizeBookmarkApi`，成功后刷新该行分类）。
- **`api/bookmark.ts` 扩展**：`getCategoryDictApi`（取词表供下拉）、`updateBookmarkCategoriesApi(id, categoryIds)`、`recategorizeBookmarkApi(id)`；`BookmarkEntity` 加 `categories?: CategoryVO[]`。

---

## 阶段二：AI 相似网站发现

### 后端 `bookmarkify-api`

- `IApiService` 新增：

```kotlin
fun inferSimilarSites(title: String?, description: String?, host: String): List<SimilarSite>
```

`SimilarSite{ name: String, domain: String, reason: String }`（DTO）。

- `ApiServiceImpl` 实现：单次 DeepSeek 调用，system prompt 要求**严格输出 JSON 数组**（`[{"name","domain","reason"}]`，5~8 条），`maxTokens` 放大到 ~600。解析全程 `runCatching`，失败/非法 JSON 返回空列表。
- `AdminBookmarkManageController` 新增：

```
POST /admin/bookmark/{bookmarkId}/similar   → List<SimilarSiteVO>
```

载入书签 → 调 `inferSimilarSites(title, description, urlHost)`。

### 后台 `bookmarkify-admin`

- `api/bookmark.ts`：`findSimilarSitesApi(id): Promise<SimilarSite[]>`。
- `cleaning/index.vue` 详情弹窗加「查找相似网站」按钮 → loading → 渲染结果列表（名称 + 可点击域名 `<a target="_blank">` + 理由文字）。**仅展示，不入库**。

---

## 错误处理

- 所有 DeepSeek 调用 `runCatching` 兜底，失败返回空列表；前端 loading 结束后空结果显示「未找到相似网站 / 归类失败」提示。
- 现有 `categorize` 已吞异常并记 warn，保持不变。
- 分类 CRUD 的删除为软删，不影响历史关联（关联表独立软删，互不级联）。

## 测试策略

- 后端：`./gradlew test`。DeepSeek 为外部调用，以手动验证为主；纯逻辑（如 `categoriesOf` 批量回填映射、JSON 解析容错）可加单元测试。
- 后台：UI 以手动验证为主（`pnpm dev:ele` / `pnpm build:ele` 通过编译与 lint）。

## 前置条件（实施前先确认）

- `website_category` / `bookmark_category` 物理表必须已存在（MyBatis-Plus 不自动建表）。自动归类虽已接线，但词表可能从未被填充。实施第一步：确认表结构存在；若缺，补建表 SQL（含 `bookmark_category` 的 unique 约束 `bookmarkId+categoryId`）。

## 实施顺序

先做阶段一（分类）至可用，再做阶段二（AI 相似网站）。两者相互独立，互不阻塞。
