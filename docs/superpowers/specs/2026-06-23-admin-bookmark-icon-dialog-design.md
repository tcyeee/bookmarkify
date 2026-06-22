# 管理台「书签图标管理 / 详情弹窗」改造 — 设计文档

- 日期：2026-06-23
- 范围服务：`bookmarkify-admin`（Vue）、`bookmarkify-api`（Kotlin）、`bookmarkify-web`（Nuxt/Vue）
- 主体文件：`bookmarkify-admin/apps/web-ele/src/views/bookmark/liveness/index.vue` 及其 `BookmarkIcon.vue`

## 背景

管理台「书签图标管理」详情弹窗（`liveness/index.vue`）用于审阅/微调单个书签的展示形态：小图标、高清 LOGO、内边距、背景色，并支持「重新获取」后对比择优。本次围绕该弹窗做一组改造与一处前台修复。

原始待办 6 项，其中 **「fix: 管理台图片更新失败」已由用户修复**，本设计不再涉及。其余 5 项见下。

## 关键现状（已核对代码）

- **小图标**：`bookmark.icon_base64`（base64），由 `BookmarkIcon.vue` 渲染，支持自定义 `iconBgColor` 覆盖背景 + `iconPadding` 收缩图片。
- **高清 LOGO**：`bookmark.logo_url`（私有读 OSS 地址）。管理台预览通过 `OssUtils.resizeAndSignImg` 签名后展示。
- **web 前台渲染**：`bookmarkify-web/components/launchpad/cell/BookmarkLogo.vue`。优先用 `iconHdUrl`，否则回退 `iconBase64`，再兜底默认头像。**它当前完全没有实现 `iconBgColor` / `iconPadding`。**
- **web 数据来源**：`BookmarkShow` VO **由 `BookmarkUserLinkMapper` 的两条原生 `@Select` SQL 填充**（`allBookmarkByUid` / `findShowById`），不是用实体 `copyProperties`。SQL 当前选了 `icon_base64`、`app_name`，**没有选 `icon_padding` / `icon_bg_color`**。
- **web 高清判定**：`BookmarkShow.initLogo()` 中 `isHd = hdSize > 50`，`hdSize` 来自 `website_logo.height`（LATERAL join，列表阈值 `>=150`、单条 `>=180`）。满足则 `iconHdUrl = OssUtils.getLogoUrl(bookmarkId, hdSize, 256)`。即：高清是按尺寸**自动**启用，没有用户开关。
- **DeepSeek**：`IApiService.inferAppName(title: String): String?` 已存在，解析阶段用它推断 `appName`。
- **DB 迁移机制**：手写 SQL 文件放 `bookmarkify-api/sql/`，幂等（`IF NOT EXISTS`），人工执行；MyBatis-Plus 按实体字段 snake_case 直接映射，无自动 DDL。
- **保存端点**：`POST /admin/bookmark/{id}/icon`（`adminUpdateIcon`）当前只存 `iconPadding` + `iconBgColor`。

## 决策汇总（已与用户确认）

1. 高清图开关作用范围：**落库字段，前台按开关决定用高清 LOGO 还是小图标**。
2. AppName 交互：**输入框 + DeepSeek 生成按钮**；DeepSeek 仅返回建议、保存时才落库。
3. AppName 位置：**放在始终可见的「编辑」区**（不放只在重新获取后才出现的「更新」区）。
4. 保存策略：**方案 A —— 单一「保存」按钮，扩展现有 `/icon` 端点一次性持久化全部编辑字段**。
5. Bug「编辑后其他地方不显示」的「其他地方」：**web 前台 / 桌面**。

---

## 改造项

### 1. 预览区：同时显示大中小三种状态（admin only）

`index.vue` 预览区现状：单个小图标（尺寸由 `ElSegmented` 80/120/160 切换）+ 一个高清 LOGO 框。

改为：

- 去掉 `ElSegmented` 尺寸切换控件及 `previewSize`/`PREVIEW_SIZES` 相关状态。
- 小图标区**并排同时**渲染三个 `BookmarkIcon`，尺寸固定 **小 80 / 中 120 / 大 160**，各带标签「小 / 中 / 大」，全部实时套用 `editPadding` / `editBgColor`。
- 高清 LOGO 预览框保留（120px 固定，逻辑不变）。
- 布局自适应：三个尺寸较宽，预览面板宽度（当前 `flex: 0 0 220px`）需放宽，或三图换行/缩放排布，保证不溢出 900px 弹窗。

### 2. 编辑区：key/value 左右分布（admin only，纯 CSS）

`.edit-row` 由 `flex-direction: column`（label 在上、控件在下）改为左右：

- label 左侧、固定宽度（如 80px）右对齐或左对齐统一；控件占右侧剩余空间。
- 作用于全部编辑行：背景颜色、图片内边距、新增的「使用高清图」开关、新增的「AppName」行。
- 内边距行的「滑块 + 数字输入」组合在右侧控件区内排布。

### 3. 编辑区：使用高清图开关（admin + api + web）

**DB 迁移**（`bookmarkify-api/sql/2026-06-23-bookmark-use-hd-logo.sql`）：

```sql
ALTER TABLE bookmark
    ADD COLUMN IF NOT EXISTS use_hd_logo boolean NOT NULL DEFAULT false;
-- 回填：保持现有「尺寸达标即自动用高清」的行为，避免存量书签退化为小图标
UPDATE bookmark SET use_hd_logo = true WHERE maximal_logo_size > 50;
```

**api**：

- `BookmarkEntity` 增 `var useHdLogo: Boolean = false`。
- `BookmarkAdminVO` 增 `useHdLogo`（构造里随 `copyProperties` 带出）。
- `BookmarkIconUpdateParams` 增 `useHdLogo`（见「保存策略」）。
- `adminUpdateIcon` 持久化 `useHdLogo`。
- `BookmarkUserLinkMapper` 两条 SQL 增选 `b.use_hd_logo AS useHdLogo`。
- `BookmarkShow` 增 `useHdLogo`；`initLogo()` 改为：**`useHdLogo == true` 且存在高清图（`hdSize > 0`）时才设置 `iconHdUrl`**，取代原 `isHd = hdSize > 50` 的隐式自动规则。
  - 注意：`hdSize` 仍来自 `website_logo.height` 的 LATERAL join（阈值 150/180）。开关打开但无达标 logo 时，`hdSize` 为空 → 退回 base64。这与「无 `logoUrl` 时禁用开关」的前端约束一致。

**admin**（`index.vue`）：

- 编辑区加一行 `ElSwitch`「使用高清图」，绑定新 ref `editUseHdLogo`。
- 当 `detailItem.logoUrl` 为空时禁用开关并提示「未获取到高清 LOGO」。
- 纳入 `iconDirty` 判定与保存载荷。

**web**：

- `iconHdUrl` 由后端 `initLogo()` 决定，`BookmarkLogo.vue` 现有「优先 `iconHdUrl`」逻辑无需改动即生效。

### 4. 编辑区：AppName（手动输入 + DeepSeek 生成）（admin + api）

**api**：

- 新增端点 `POST /admin/bookmark/{bookmarkId}/appname/generate`：取书签 `title` 调 `apiService.inferAppName(title)`，返回 `{ appName: String? }`，**不落库**。`title` 为空时返回 `appName=null`。
  - 新增返回 VO（如 `AppNameSuggestVO(appName: String?)`）放 `Response.kt`。
- `appName` 的持久化纳入统一保存端点（见「保存策略」）。

**admin**（`index.vue`）：

- 编辑区加一行 AppName：`[输入框 editAppName] [DeepSeek 生成按钮]`，符合左右布局。
- 「DeepSeek 生成」点击 → 调 generate 端点 → 结果填入输入框（仍可手改），加载态 `generatingAppName`。
- `editAppName` 初始化自 `detailItem.appName`，纳入 `iconDirty` 与保存载荷。
- 保存成功后回写 `item.appName`，使平铺列表 `displayName` 立即更新。

### 5. 修复：编辑后 web 前台不显示（Bug #6）（api + web）

根因：`icon_padding` / `icon_bg_color` 未进 SQL、未进 `BookmarkShow`，且 `web/BookmarkLogo.vue` 未实现这两者。

**api**：

- `BookmarkUserLinkMapper` 两条 SQL 各增选 `b.icon_padding AS iconPadding`、`b.icon_bg_color AS iconBgColor`。
- `BookmarkShow` 增字段 `iconPadding: Int = 25`、`iconBgColor: String? = null`。

**web**：

- `typing/bookmark.ts` 的 `BookmarkShow` 类型增 `iconPadding?: number`、`iconBgColor?: string`（`useHdLogo` 前端不直接用，无需加——高清与否后端已折算进 `iconHdUrl`）。
- `BookmarkLogo.vue` 镜像 admin `BookmarkIcon.vue` 的逻辑：
  - 自定义背景色：`iconBgColor` 存在时直接铺该色（覆盖均值色 + 蒙版）。
  - 内边距：base64 像素尺寸按 `iconPadding` 收缩（`base - 2 * padding`，下限保护）。
  - HD 分支：背景色同样作为容器底色应用；padding 主要影响 base64 分支，HD 大图保持 `object-fit: contain`。

**生效时机**：管理台改的是规范化 `bookmark` 主表；web 用户下次加载（`getBookmarkList` / `findShow`）即反映，无需 WebSocket 实时推送。

---

## 保存策略（方案 A）

单一「保存」按钮，扩展现有 `POST /admin/bookmark/{id}/icon` 一次性持久化编辑区全部字段：

`BookmarkIconUpdateParams` 扩展为：

```kotlin
data class BookmarkIconUpdateParams(
    var iconPadding: Int = 0,
    var iconBgColor: String? = null,
    var useHdLogo: Boolean = false,
    var appName: String? = null,
)
```

`adminUpdateIcon` 相应地一并 set `useHdLogo`、`appName`。

> 端点路径名 `/icon` 与扩展后的语义（含 appName/useHdLogo）略有偏差，但为减少改动与保持单按钮交互，保留该路径，不重命名。

前端 `updateBookmarkIconApi` 的 `data` 类型同步扩展为 `{ iconPadding, iconBgColor, useHdLogo, appName }`。

「重新获取 / 应用」（`/refetch`、`/refetch/apply`）逻辑保持不变，仍走独立流程。`canSave` / `iconDirty` 需把 `useHdLogo`、`appName` 的改动也算进去。

---

## 受影响文件清单

**bookmarkify-api**

- `sql/2026-06-23-bookmark-use-hd-logo.sql`（新建）
- `entity/entity/BookmarkEntity.kt`（+`useHdLogo`）
- `entity/Response.kt`（`BookmarkAdminVO` +`useHdLogo`；`BookmarkShow` +`iconPadding`/`iconBgColor`/`useHdLogo` 及 `initLogo` 改造；新增 `AppNameSuggestVO`）
- `entity/Request.kt`（`BookmarkIconUpdateParams` 扩展）
- `mapper/BookmarkUserLinkMapper.kt`（两条 SQL 增选 3 列）
- `server/impl/BookmarkServiceImpl.kt`（`adminUpdateIcon` 持久化新字段；新增生成 appName 的 service 方法）
- `server/IBookmarkService.kt`（接口签名）
- `controller/admin/AdminBookmarkManageController.kt`（新增 `/appname/generate` 端点）

**bookmarkify-admin**

- `apps/web-ele/src/api/bookmark.ts`（`BookmarkEntity` +`useHdLogo`；`updateBookmarkIconApi` 参数扩展；新增 `generateAppNameApi`）
- `apps/web-ele/src/views/bookmark/liveness/index.vue`（预览三图、编辑区左右布局、高清开关、AppName 行、保存载荷）

**bookmarkify-web**

- `typing/bookmark.ts`（`BookmarkShow` +`iconPadding?`/`iconBgColor?`）
- `components/launchpad/cell/BookmarkLogo.vue`（实现 `iconBgColor`/`iconPadding`）

## 测试与验证

- 三仓库均无单测框架。验证以手动为主：
  - admin：打开弹窗，确认预览三尺寸同显、编辑区左右布局、改背景色/内边距实时预览、高清开关、AppName 手填与 DeepSeek 生成、保存后平铺列表名称即时更新。
  - api：迁移脚本执行后存量书签 `use_hd_logo` 回填正确；保存端点写入四字段；`/appname/generate` 返回建议。
  - web：管理台改 padding/背景色/高清开关后，前台刷新（重新加载书签）渲染一致。

## 不在本次范围

- Bug「管理台图片更新失败」（用户已修复）。
- 「上传自定义图片」功能（用户确认非本次需求）。
- HD 判定阈值（`website_logo.height >= 150/180`）的调整：本次沿用现状；若开关打开但无达标 logo，则退回 base64。
