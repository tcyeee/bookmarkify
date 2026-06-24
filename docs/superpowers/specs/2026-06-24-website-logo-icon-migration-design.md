# 图标字段迁移到 website_logo（1:1）设计

日期: 2026-06-24

## 目标

把 `bookmark` 表中「图标相关字段」迁移到 `website_logo` 表（与书签一对一），后端响应改为嵌套 `logo` 对象，admin + web 前端同步更新引用。

迁移的 6 个字段：`icon_base64`、`logo_url`、`maximal_logo_size`、`icon_padding`、`icon_bg_color`、`use_hd_logo`。

## 关键现状（调研结论）

- `website_logo` 原本只存高清 LOGO 文件元数据（size/height/width/suffix/is_og_img），由 `saveLogoToOss` 每次解析 insert 一行（随机 UUID，会累积多行）。
- 它被 `BookmarkUserLinkMapper` 的 LATERAL 子查询读取 `height` 当作前台 `hdSize`（阈值 ≥150/180）。
- `maximal_logo_size` == 高清 LOGO 的 width（正方形即 width==height），且是 OSS 对象名：`logo/{bookmarkId}/{width}.png`，URL 构建 load-bearing。
- web 前端只吃后端 VO 的 JSON（`BookmarkShow`），不直接读库；admin 吃 `BookmarkAdminVO`。
- `/bookmark/search` 直接返回 `List<BookmarkEntity>`，其中 `icon_base64` 被前台「添加书签」搜索结果用到。

## 目标表结构

`website_logo`（每书签唯一一行 = 该书签的图标记录）：

| 列 | 来源 |
|---|---|
| id, bookmark_id(唯一) | 保留 |
| icon_base64 | ← bookmark 迁入 |
| logo_url | ← bookmark 迁入（未签名 OSS 地址） |
| icon_padding(默认25) / icon_bg_color / use_hd_logo(默认false) | ← bookmark 迁入 |
| size / height / width / suffix | 保留（width 即原 maximal_logo_size） |
| create_time / update_time | 保留 |
| ~~is_og_img~~ | 删除 |

`bookmark`：删除上述 6 列。

> 行为变化（已确认）：1:1 后 LATERAL「多尺寸选最优」退化为单行直读（实运维每书签只存一个最大 LOGO，等价）；`height>=150/180` 的高清品质门槛保留。

## 后端改动（bookmarkify-api）

- `entity/entity/BookmarkEntity.kt`：`BookmarkEntity` 删 6 个图标字段；`successInit` 不再写 iconBase64；`WebsiteLogoEntity` 增图标字段、字段改 `var`、删 `isOgImg`。
- `entity/Response.kt`：
  - `BookmarkShow`：图标扁平字段加 `@JsonIgnore`，新增计算属性 `logo`（`BookmarkLogoShowVO`）；构造器加 `logo` 参数，`hdSize` 取自 `website_logo.height`。
  - `BookmarkAdminVO`：6 字段收进 `logo`（`BookmarkLogoAdminVO`，`maximalLogoSize`=width，logoUrl 仍签名）；构造器 `(entity, logo)`。
  - 新增 `BookmarkSearchVO`（搜索结果，含 `logo.iconBase64`）。
- `mapper/BookmarkUserLinkMapper.kt`：两条 SQL 改从 `website_logo` 读图标字段（LATERAL 取主图标行，COALESCE 兜底，CASE 维持 hdSize 阈值）。
- `server/impl/BookmarkServiceImpl.kt`：注入改 `IWebsiteLogoService`；新增 `logoOf` / `logosByBookmarkIds` / `saveIconAndLogo` / `applyHdLogo`；`parseLocally`/`parseByApi`/`adminUpdateIcon`/`adminApplyRefetch`/`adminListAll`/`allOfMyBookmark`/`search` 全部改走 website_logo；删 `saveLogoToOss`/`setMaximalLogoSize`。
- `server/IBookmarkService.kt` + `controller/bookmark/BookmarkController.kt`：`search` 返回 `List<BookmarkSearchVO>`。
- `utils/OssUtils.kt`：构造 `WebsiteLogoEntity` 去掉 `isOgImg`。

## 前端改动

- admin：`api/bookmark.ts`（`BookmarkEntity` 嵌套 `logo`，新增 `BookmarkLogo`）、`liveness/index.vue`、`liveness/BookmarkIcon.vue`、`cleaning/index.vue` 全部改读 `logo.*`。
- web：`typing/bookmark.ts`（`BookmarkShow` 嵌套 `logo`）、`cell/BookmarkLogo.vue`、`launchpad/Detail.vue`、`launchpad/AddOneDialog.vue` 改读 `logo?.*`。

## 数据迁移（手写 SQL，两段式）

文件：`bookmarkify-api/sql/2026-06-24-website-logo-icon-migration.sql`，均幂等。

- **Part 1（向后兼容，部署新代码前执行）**：`website_logo` 增列 + 从 bookmark 回填。旧代码无感。
- 部署新代码（push prod）。
- **Part 2（破坏性，新代码上线后执行）**：再回填一次 + 去重 + 加唯一约束 + 删 is_og_img + 删 bookmark 6 列。

> ⚠️ 若旧代码仍在跑时执行 Part 2（删列/唯一约束），生产会立即报错。务必先上线新代码。

## 验证

后端不在本地构建（按用户要求）；依赖 deploy-api CI 编译 jar 把关（编译失败则部署失败、不影响生产）。前端依赖 admin `build:ele`/web `build` CI。
