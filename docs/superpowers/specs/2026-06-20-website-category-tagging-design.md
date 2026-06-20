# 网站自动分类标签（DeepSeek）— 设计文档

- 日期: 2026-06-20
- 服务: `bookmarkify-api`
- 状态: 已确认，待实现

## 背景与目标

用户添加书签后，系统应自动为该书签对应的**网站**打上分类标签（可多个），用于给网站分类。
分类通过 DeepSeek API 推断，从一组**固定受控词表**中挑选 1~N 个分类。

关键事实（来自代码勘察）：

- `bookmark` 表是 **canonical 的「一域一条」网站记录**（`url_host` 唯一约束，全站用户共享）。
  用户的个人副本在 `bookmark_user_link`。因此分类是网站级属性，挂在 `bookmark` 上，**每个网站只算一次、全站共享**。
- DeepSeek 已接入：`ApiServiceImpl.inferAppName` 用于从标题推断品牌简称，
  在解析流程 `parseByApi` / `parseLocally` 中通过 `inferAndSetAppName(bookmark)` 调用。
  复用同一套 `DeepSeekRequest/Response` 模型与调用方式。
- 现有 `bookmark_tag` / `bookmark_tag_link`（带 `uid`）是**每用户的个人标签**（2024 年遗留，目前未使用），
  与本功能是不同概念，**本次不复用、不迁移、不改动**。

## 范围（本期）

**做**：DeepSeek 生成网站分类 → 写入两张新表。纯后端。

**不做（YAGNI）**：

- 不改前端。
- 不改 `bookmark` 的 API 响应体。
- 不改 WebSocket 推送（`HOME_ITEM_UPDATE`）。
- 不做分类的查询 / 筛选接口（后续单独迭代）。
- 不复用 / 不迁移旧的 `bookmark_tag` 个人标签表。
- 无单元测试（项目当前无 `src/test/`）。

## 数据模型（2 张新表）

Schema `bookmarkify`。沿用现有约定：snake_case 表名、`varchar` String 主键（`IdUtil.fastUUID()`）、
`create_time` / `deleted` 软删列、MyBatis-Plus 实体（`@TableName` + `@TableId`）。

### `website_category`（分类字典表，预先 seed）

| 列 | 类型 | 说明 |
|---|---|---|
| `id` | varchar PK | UUID |
| `slug` | varchar **unique** | 稳定 key，喂给 DeepSeek、未来筛选用（如 `dev`） |
| `name` | varchar | 中文展示名（如 `开发`） |
| `description` | varchar 可空 | 给 DeepSeek 的判定说明 |
| `color` | varchar 可空 | 预留给未来 UI |
| `sort` | int | 展示顺序 |
| `deleted` | bool | 软删 |
| `create_time` | timestamp | |
| `last_modified` | timestamp | |

约束：`unique(slug)`。

### `bookmark_category`（网站 ↔ 分类 关联表）

| 列 | 类型 | 说明 |
|---|---|---|
| `id` | varchar PK | UUID |
| `bookmark_id` | varchar | canonical `bookmark.id` |
| `category_id` | varchar | `website_category.id` |
| `source` | varchar | 来源，默认 `DEEPSEEK`，便于以后人工/规则补充 |
| `create_time` | timestamp | |
| `deleted` | bool | 软删 |

约束：`unique(bookmark_id, category_id)` 防重复关联。

### 重解析幂等

网站会被定时对账重解析（`BookmarkEntity.checkFlag()` / `checkAll()` cron）。因此每次分类执行为：
**先物理删除该 `bookmark_id` 的旧 link（`DELETE WHERE bookmark_id = ?`），再插入新 link**，保证可重复执行、结果稳定。
采用物理删除（而非软删）是为了避免与 `unique(bookmark_id, category_id)` 约束冲突——软删后重插同一组合会触发唯一键冲突。
`bookmark_category.deleted` 列仍保留，供未来「用户手动移除某分类」等软删场景使用。

### 起始词表（seed）

约 16 个分类，迁移 SQL 中以 `INSERT` 形式 seed：

| slug | name | slug | name |
|---|---|---|---|
| `dev` | 开发 | `news` | 新闻资讯 |
| `design` | 设计 | `study` | 学习教育 |
| `ai` | AI | `finance` | 金融 |
| `tool` | 效率工具 | `game` | 游戏 |
| `social` | 社交 | `read` | 阅读 |
| `video` | 影视 | `job` | 求职招聘 |
| `music` | 音乐 | `gov` | 政务 |
| `shopping` | 购物 | `other` | 其他 |

## DeepSeek 集成

- `IApiService` 新增方法：

  ```
  fun inferCategories(meta: WebsiteMeta, candidates: List<CategoryCandidate>): List<String>
  ```

  - 入参 `meta`：网站元信息（title + description + host）。
  - 入参 `candidates`：候选词表（slug + name + description），来自 `website_category` 字典表。
  - 返回：命中的 `slug` 列表（已按已知 slug 校验、去重、丢弃非法值）。

- 复用现有 `DeepSeekRequest` / `DeepSeekResponse`，但本次调用单独构造、`maxTokens` 调大
  （现默认 20 太小，无法容纳多个分类）。
- Prompt 设计：system 消息列出全部允许的 `slug(name)` 及其 `description`，要求模型
  **只从列表里**返回 1~3 个 slug、逗号分隔、无任何多余文字；user 消息为网站元信息。
- 返回解析：按候选 slug 集合校验，丢弃非法值，去重。
- 候选词表从 `website_category` 加载（可加缓存）。**因此以后往字典表加一行即可扩展分类，无需改代码。**

## 服务层与挂载点

### 新增组件

- 实体：`WebsiteCategory`、`BookmarkCategory`（`entity/entity/`，MyBatis-Plus `@TableName`）。
- Mapper：`WebsiteCategoryMapper`、`BookmarkCategoryMapper`（`BaseMapper`，`@Mapper`）。
- Service：`IWebsiteCategoryService` / `WebsiteCategoryServiceImpl`、
  `IBookmarkCategoryService` / `BookmarkCategoryServiceImpl`（遵循 `I*Service` + `*ServiceImpl extends ServiceImpl<Mapper, Entity>` 约定，放在 `server/` 包）。
- 分类编排逻辑 `categorize(bookmark)`：加载词表 → 调 `apiService.inferCategories` →
  slug → categoryId 映射 → 替换 `bookmark_category` 的 link（幂等）。

### 挂载点

`BookmarkServiceImpl.parseBookmark()`（三条解析流程 `parseAndSave` / `parseAndNotice` /
`parseAndResetUserItem` 的统一收口）在 `parseByApi` / `parseLocally` 返回后，
**仅当 `parseStatus ∈ {SUCCESS, BLOCKED}`（即有 title）时**触发分类。
一处接入即覆盖「单个添加 / 批量导入 / 定时重解析」全部路径。

### 失败处理

与 `inferAndSetAppName` 行为一致：DeepSeek 调用或解析失败只记日志（warn/debug），
**不影响书签解析主流程**，不抛异常。分类缺失由后续重解析兜底重试。

## 实现产出清单

- SQL 迁移文件（`sql/2026-06-20-website-category.sql`）：建 2 张表 + seed 词表。
- 2 个实体、2 个 Mapper、2 组 Service 接口+实现。
- `IApiService.inferCategories` + `ApiServiceImpl` 实现。
- `BookmarkServiceImpl.parseBookmark()` 挂载分类调用。
- 必要的 DTO（`WebsiteMeta` / `CategoryCandidate`，或直接复用现有结构）。
