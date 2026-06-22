# 管理台书签图标详情弹窗改造 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 改造管理台「书签图标管理/详情弹窗」：预览三尺寸同显、编辑区左右布局、新增高清图开关与 AppName（手填+DeepSeek），并修复编辑后 web 前台不更新。

**Architecture:** 跨三服务。API 落库新字段 `use_hd_logo` 并把 `icon_padding`/`icon_bg_color`/`use_hd_logo` 透传进 `BookmarkShow`（修 web 渲染）；新增 DeepSeek 生成 appName 端点；编辑字段经单一保存端点 `/icon` 一次性持久化。admin/web 前端跟随渲染。

**Tech Stack:** Kotlin 2.1 + Spring Boot 3.5 + MyBatis-Plus（api）、Vue 3 + Element Plus + Vben（admin）、Nuxt 4 + Vue 3（web）、PostgreSQL。

## Global Constraints

- **无测试框架**：三仓库均无单测。每个 task 的「验证」= 编译/构建通过 + 手动检查；非 TDD。
- **DB 迁移**：手写幂等 SQL 放 `bookmarkify-api/sql/`，人工执行；MyBatis-Plus 按实体字段 snake_case 自动映射，无自动 DDL。
- **保存策略 A**：单一「保存」按钮，扩展现有 `POST /admin/bookmark/{id}/icon` 持久化 `iconPadding`+`iconBgColor`+`useHdLogo`+`appName`，端点路径不改名。
- **commit 信息用英文**（全局规则）。
- **HD 渲染语义**：`useHdLogo==true` 且存在达标高清图（`hdSize>0`）时前台才用 `iconHdUrl`，否则退回 base64。
- **admin Element Plus 组件**用 `defineAsyncComponent` 异步注册（沿用本文件现有写法）。

---

### Task 1: API — 落库编辑字段（迁移 + 实体 + 保存端点）

**Files:**
- Create: `bookmarkify-api/sql/2026-06-23-bookmark-use-hd-logo.sql`
- Modify: `bookmarkify-api/src/main/kotlin/top/tcyeee/bookmarkify/entity/entity/BookmarkEntity.kt:44`
- Modify: `bookmarkify-api/src/main/kotlin/top/tcyeee/bookmarkify/entity/Request.kt:40-43`
- Modify: `bookmarkify-api/src/main/kotlin/top/tcyeee/bookmarkify/entity/Response.kt:161`（`BookmarkAdminVO`）
- Modify: `bookmarkify-api/src/main/kotlin/top/tcyeee/bookmarkify/server/impl/BookmarkServiceImpl.kt:175-182`（`adminUpdateIcon`）

**Interfaces:**
- Produces: `BookmarkEntity.useHdLogo: Boolean`；`BookmarkIconUpdateParams(iconPadding: Int, iconBgColor: String?, useHdLogo: Boolean, appName: String?)`；`BookmarkAdminVO.useHdLogo: Boolean`。

- [ ] **Step 1: 写迁移脚本**

创建 `bookmarkify-api/sql/2026-06-23-bookmark-use-hd-logo.sql`：

```sql
-- bookmark 表新增 use_hd_logo：是否在前台用高清 LOGO 渲染
-- 日期: 2026-06-23
-- schema: bookmarkify
-- 说明: 控制 web 前台渲染时用高清 LOGO 还是小图标。回填保持现状(尺寸达标即自动用高清)，
--      避免存量书签退化为小图标。可重复执行。
ALTER TABLE bookmark
    ADD COLUMN IF NOT EXISTS use_hd_logo boolean NOT NULL DEFAULT false;

UPDATE bookmark SET use_hd_logo = true WHERE maximal_logo_size > 50;
```

- [ ] **Step 2: `BookmarkEntity` 加字段**

在 `BookmarkEntity.kt` 第 44 行 `iconBgColor` 之后插入：

```kotlin
    @field:Schema(description = "是否在前台用高清LOGO渲染") var useHdLogo: Boolean = false,
```

- [ ] **Step 3: 扩展 `BookmarkIconUpdateParams`**

`Request.kt` 第 40-43 行整体替换为：

```kotlin
data class BookmarkIconUpdateParams(
    @field:Schema(description = "图片内边距") var iconPadding: Int = 0,
    @field:Schema(description = "图标背景色") var iconBgColor: String? = null,
    @field:Schema(description = "是否使用高清图") var useHdLogo: Boolean = false,
    @field:Schema(description = "书签简称") var appName: String? = null,
)
```

- [ ] **Step 4: `BookmarkAdminVO` 加字段**

`Response.kt` 第 161 行 `iconBgColor` 之后插入（构造函数里的 `BeanUtil.copyProperties(entity, this)` 会自动带出）：

```kotlin
    @field:Schema(description = "是否使用高清图") var useHdLogo: Boolean = false,
```

- [ ] **Step 5: `adminUpdateIcon` 持久化全部字段**

`BookmarkServiceImpl.kt` 第 175-182 行整体替换为：

```kotlin
    override fun adminUpdateIcon(bookmarkId: String, params: BookmarkIconUpdateParams) {
        baseMapper.selectById(bookmarkId) ?: throw CommonException(ErrorType.E102)
        ktUpdate()
            .eq(BookmarkEntity::id, bookmarkId)
            .set(BookmarkEntity::iconPadding, params.iconPadding)
            .set(BookmarkEntity::iconBgColor, params.iconBgColor)
            .set(BookmarkEntity::useHdLogo, params.useHdLogo)
            .set(BookmarkEntity::appName, params.appName)
            .update()
    }
```

- [ ] **Step 6: 编译验证**

Run: `cd bookmarkify-api && ./gradlew compileKotlin -q`
Expected: `BUILD SUCCESSFUL`，无编译错误。

- [ ] **Step 7: Commit**

```bash
cd /Users/tcyeee/Documents/Code/bookmarkify
git add bookmarkify-api/sql/2026-06-23-bookmark-use-hd-logo.sql \
        bookmarkify-api/src/main/kotlin/top/tcyeee/bookmarkify/entity/entity/BookmarkEntity.kt \
        bookmarkify-api/src/main/kotlin/top/tcyeee/bookmarkify/entity/Request.kt \
        bookmarkify-api/src/main/kotlin/top/tcyeee/bookmarkify/entity/Response.kt \
        bookmarkify-api/src/main/kotlin/top/tcyeee/bookmarkify/server/impl/BookmarkServiceImpl.kt
git commit -m "feat(api): persist useHdLogo and appName via admin icon endpoint"
```

---

### Task 2: API — BookmarkShow 透传 padding/bgColor/HD（修 Bug #6 + 高清开关渲染）

**Files:**
- Modify: `bookmarkify-api/src/main/kotlin/top/tcyeee/bookmarkify/entity/Response.kt:21,30,42-49`（`BookmarkShow` 字段与 `isHd`/`initLogo`）
- Modify: `bookmarkify-api/src/main/kotlin/top/tcyeee/bookmarkify/mapper/BookmarkUserLinkMapper.kt:30,63`（两条 SQL 增选 3 列）

**Interfaces:**
- Consumes: `BookmarkEntity.useHdLogo`（Task 1）。
- Produces: `BookmarkShow` 序列化新增 `iconPadding: Int`、`iconBgColor: String?`（供 web）；`useHdLogo` 仅服务端用（`@JsonIgnore`）。

- [ ] **Step 1: `BookmarkShow` 加字段**

`Response.kt` 第 21 行 `iconBase64` 那行之后插入：

```kotlin
    @field:Schema(description = "图片内边距") var iconPadding: Int = 25,
    @field:Schema(description = "图标背景色") var iconBgColor: String? = null,
    @JsonIgnore @field:Schema(description = "是否使用高清图") var useHdLogo: Boolean = false,
```

- [ ] **Step 2: 改 `isHd` 判定为受开关控制**

`Response.kt` 第 30 行：

```kotlin
    val isHd: Boolean get() = hdSize > 50
```

替换为：

```kotlin
    // 高清渲染改由用户开关控制：开关开启且存在达标高清图时才用高清
    val isHd: Boolean get() = useHdLogo && hdSize > 0
```

（`initLogo()` 第 42-49 行不变，仍用 `isHd` 决定是否设置 `iconHdUrl`。）

- [ ] **Step 3: 两条 SQL 增选 3 列**

`BookmarkUserLinkMapper.kt` 中 `allBookmarkByUid`（第 30 行 `b.app_name AS appName,` 之后）和 `findShowById`（第 63 行 `b.app_name AS appName,` 之后），**两处都**插入：

```sql
               b.icon_padding                               AS iconPadding,
               b.icon_bg_color                              AS iconBgColor,
               b.use_hd_logo                                AS useHdLogo,
```

- [ ] **Step 4: 编译验证**

Run: `cd bookmarkify-api && ./gradlew compileKotlin -q`
Expected: `BUILD SUCCESSFUL`。

- [ ] **Step 5: Commit**

```bash
cd /Users/tcyeee/Documents/Code/bookmarkify
git add bookmarkify-api/src/main/kotlin/top/tcyeee/bookmarkify/entity/Response.kt \
        bookmarkify-api/src/main/kotlin/top/tcyeee/bookmarkify/mapper/BookmarkUserLinkMapper.kt
git commit -m "fix(api): flow iconPadding/iconBgColor and HD toggle into BookmarkShow"
```

---

### Task 3: API — DeepSeek 生成 appName 端点

**Files:**
- Modify: `bookmarkify-api/src/main/kotlin/top/tcyeee/bookmarkify/entity/Response.kt`（新增 `AppNameSuggestVO`，加在 `BookmarkRefetchVO` 之后）
- Modify: `bookmarkify-api/src/main/kotlin/top/tcyeee/bookmarkify/server/IBookmarkService.kt:56`（接口加方法）
- Modify: `bookmarkify-api/src/main/kotlin/top/tcyeee/bookmarkify/server/impl/BookmarkServiceImpl.kt`（实现 `adminGenerateAppName`，加在 `adminApplyRefetch` 之后约第 223 行）
- Modify: `bookmarkify-api/src/main/kotlin/top/tcyeee/bookmarkify/controller/admin/AdminBookmarkManageController.kt:54`（新增端点 + import）

**Interfaces:**
- Consumes: 已有 `apiService.inferAppName(title: String): String?`。
- Produces: 端点 `POST /admin/bookmark/{bookmarkId}/appname/generate` → `AppNameSuggestVO(appName: String?)`；service `adminGenerateAppName(bookmarkId: String): String?`。

- [ ] **Step 1: 新增 `AppNameSuggestVO`**

`Response.kt` 中 `BookmarkRefetchVO`（第 186-190 行那块）之后插入：

```kotlin
/** 管理后台 DeepSeek 生成 appName 建议（不落库，供前端填入编辑框） */
data class AppNameSuggestVO(
    @field:Schema(description = "DeepSeek 推断的书签简称(可能为空)") var appName: String? = null,
)
```

- [ ] **Step 2: 接口加方法**

`IBookmarkService.kt` 第 56 行 `adminApplyRefetch` 声明之后插入：

```kotlin
    fun adminGenerateAppName(bookmarkId: String): String?
```

- [ ] **Step 3: 实现 `adminGenerateAppName`**

`BookmarkServiceImpl.kt` 中 `adminApplyRefetch` 方法（结束于约第 223 行 `}`）之后插入：

```kotlin
    override fun adminGenerateAppName(bookmarkId: String): String? {
        val bookmark = baseMapper.selectById(bookmarkId) ?: throw CommonException(ErrorType.E102)
        val title = bookmark.title?.takeIf { it.isNotBlank() } ?: run {
            log.debug("[adminGenerateAppName] title 为空，跳过生成: bookmarkId=$bookmarkId")
            return null
        }
        log.debug("[adminGenerateAppName] 调用 DeepSeek 生成 appName: bookmarkId=$bookmarkId, title=$title")
        return apiService.inferAppName(title)?.takeIf { it.isNotBlank() }
    }
```

- [ ] **Step 4: 新增端点 + import**

`AdminBookmarkManageController.kt` 第 9 行附近 import 区加：

```kotlin
import top.tcyeee.bookmarkify.entity.AppNameSuggestVO
```

第 54 行 `applyRefetchBookmark` 方法之后插入：

```kotlin
    // DeepSeek 生成书签简称建议（不落库，供前端填入编辑框）
    @PostMapping("/{bookmarkId}/appname/generate")
    fun generateAppName(@PathVariable bookmarkId: String): AppNameSuggestVO =
        AppNameSuggestVO(bookmarkService.adminGenerateAppName(bookmarkId))
```

- [ ] **Step 5: 编译验证**

Run: `cd bookmarkify-api && ./gradlew compileKotlin -q`
Expected: `BUILD SUCCESSFUL`。

- [ ] **Step 6: Commit**

```bash
cd /Users/tcyeee/Documents/Code/bookmarkify
git add bookmarkify-api/src/main/kotlin/top/tcyeee/bookmarkify/entity/Response.kt \
        bookmarkify-api/src/main/kotlin/top/tcyeee/bookmarkify/server/IBookmarkService.kt \
        bookmarkify-api/src/main/kotlin/top/tcyeee/bookmarkify/server/impl/BookmarkServiceImpl.kt \
        bookmarkify-api/src/main/kotlin/top/tcyeee/bookmarkify/controller/admin/AdminBookmarkManageController.kt
git commit -m "feat(api): add DeepSeek appName generation endpoint"
```

---

### Task 4: Admin — API 客户端补字段与端点

**Files:**
- Modify: `bookmarkify-admin/apps/web-ele/src/api/bookmark.ts:3-21,48-53`（`BookmarkEntity` 加字段；`updateBookmarkIconApi` 参数扩展；新增 `generateAppNameApi`）

**Interfaces:**
- Consumes: api 端点 `/icon`（Task 1）、`/appname/generate`（Task 3）。
- Produces: `BookmarkEntity.useHdLogo?: boolean`；`updateBookmarkIconApi(id, {iconPadding, iconBgColor?, useHdLogo, appName?})`；`generateAppNameApi(id): Promise<{appName?: string}>`。

- [ ] **Step 1: `BookmarkEntity` 加字段**

`bookmark.ts` 第 15 行 `iconBgColor?: string;` 之后插入：

```typescript
  useHdLogo?: boolean;
```

- [ ] **Step 2: 扩展 `updateBookmarkIconApi`**

第 48-53 行整体替换为：

```typescript
/**
 * 修改书签编辑设置（内边距、背景色、是否高清、AppName），单一保存端点
 */
export async function updateBookmarkIconApi(
  bookmarkId: string,
  data: {
    appName?: null | string;
    iconBgColor?: null | string;
    iconPadding: number;
    useHdLogo: boolean;
  },
) {
  return requestClient.post<void>(`/admin/bookmark/${bookmarkId}/icon`, data);
}
```

- [ ] **Step 3: 新增 `generateAppNameApi`**

文件末尾追加：

```typescript
/** DeepSeek 生成书签简称建议（不落库） */
export async function generateAppNameApi(bookmarkId: string) {
  return requestClient.post<{ appName?: string }>(
    `/admin/bookmark/${bookmarkId}/appname/generate`,
  );
}
```

- [ ] **Step 4: Commit**

```bash
cd /Users/tcyeee/Documents/Code/bookmarkify
git add bookmarkify-admin/apps/web-ele/src/api/bookmark.ts
git commit -m "feat(admin): extend bookmark icon API with HD/appName fields and generate endpoint"
```

---

### Task 5: Admin — 预览区同时显示大中小三种状态

**Files:**
- Modify: `bookmarkify-admin/apps/web-ele/src/views/bookmark/liveness/index.vue`（移除 `ElSegmented`/`previewSize`；预览改三图并排；调宽弹窗/面板；加 CSS）

**Interfaces:**
- Consumes: 现有 `PREVIEW_SIZES`、`BookmarkIcon`、`previewValue`、`editPadding`、`editBgColor`。
- Produces: 无对外接口；纯 UI。

- [ ] **Step 1: 移除 `ElSegmented` 异步组件**

删除第 68-73 行整段：

```typescript
const ElSegmented = defineAsyncComponent(() =>
  Promise.all([
    import("element-plus/es/components/segmented/index"),
    import("element-plus/es/components/segmented/style/css"),
  ]).then(([res]) => res.ElSegmented),
);
```

- [ ] **Step 2: 移除 `previewSize` 状态**

删除第 135 行 `const previewSize = ref(120);`。
`PREVIEW_SIZES`（第 130-134 行）保留，值即小80/中120/大160。

- [ ] **Step 3: 移除 openDetail 中的 previewSize 重置**

删除第 185 行 `previewSize.value = 120;`。

- [ ] **Step 4: 模板——小图标块改三图并排**

将第 361-370 行的「小图标」块（`<!-- 小图标 ... -->` 到对应 `</div>`）替换为：

```html
              <!-- 小图标：大中小三尺寸同显，均实时套用内边距 / 背景色 -->
              <div class="preview-block">
                <span class="preview-block-label">小图标</span>
                <div class="preview-sizes">
                  <div
                    v-for="s in PREVIEW_SIZES"
                    :key="s.value"
                    class="preview-size-item"
                  >
                    <BookmarkIcon
                      :value="previewValue ?? detailItem"
                      :size="s.value"
                      :padding="editPadding"
                      :bg-color="editBgColor ?? undefined"
                    />
                    <span class="preview-size-tag">{{ s.label }}</span>
                  </div>
                </div>
              </div>
```

- [ ] **Step 5: 模板——移除 ElSegmented 标签**

删除第 390-394 行：

```html
            <ElSegmented
              v-model="previewSize"
              :options="PREVIEW_SIZES"
              size="small"
            />
```

- [ ] **Step 6: 调宽弹窗与预览面板**

第 338 行 `width="900px"` 改为 `width="980px"`。
CSS 第 591-601 行 `.preview-pane` 的 `flex: 0 0 220px;` 改为 `flex: 0 0 430px;`。

- [ ] **Step 7: 加预览尺寸排布 CSS**

在 `.preview-block-label` 规则（约第 625-628 行）之后插入：

```css
/* 三尺寸并排：底部对齐，大图最高 */
.preview-sizes {
  display: flex;
  gap: 16px;
  align-items: flex-end;
  justify-content: center;
}

.preview-size-item {
  display: flex;
  flex-direction: column;
  gap: 4px;
  align-items: center;
}

.preview-size-tag {
  font-size: 11px;
  color: var(--el-text-color-secondary);
}
```

- [ ] **Step 8: 构建/视觉验证**

Run: `cd bookmarkify-admin && pnpm dev:ele`
打开「书签图标管理」，点任一书签开弹窗，确认：预览区小图标以小/中/大三个尺寸同时显示、底部对齐、改背景色/内边距三图实时联动；弹窗不溢出。

- [ ] **Step 9: Commit**

```bash
cd /Users/tcyeee/Documents/Code/bookmarkify
git add bookmarkify-admin/apps/web-ele/src/views/bookmark/liveness/index.vue
git commit -m "feat(admin): show small icon at three sizes simultaneously in preview"
```

---

### Task 6: Admin — 编辑区左右布局 + 高清开关 + AppName 行 + 保存

**Files:**
- Modify: `bookmarkify-admin/apps/web-ele/src/views/bookmark/liveness/index.vue`（新增 `ElSwitch`；新增 refs/方法；扩展 iconDirty/saveIcon/openDetail；模板加两行；`.edit-row` 改左右 CSS）

**Interfaces:**
- Consumes: `updateBookmarkIconApi`、`generateAppNameApi`（Task 4）；`BookmarkEntity.useHdLogo`/`appName`。
- Produces: 无对外接口。

- [ ] **Step 1: 新增 `ElSwitch` 异步组件**

在 `ElSlider` 异步组件定义（第 82-87 行）之后插入：

```typescript
const ElSwitch = defineAsyncComponent(() =>
  Promise.all([
    import("element-plus/es/components/switch/index"),
    import("element-plus/es/components/switch/style/css"),
  ]).then(([res]) => res.ElSwitch),
);
```

- [ ] **Step 2: 新增编辑态 refs**

在 `const savingIcon = ref(false);`（第 127 行）之后插入：

```typescript
// 是否使用高清图（落库）、书签简称（落库）、DeepSeek 生成中
const editUseHdLogo = ref(false);
const editAppName = ref("");
const generatingAppName = ref(false);
```

- [ ] **Step 3: 扩展 `iconDirty`**

第 139-146 行 `iconDirty` 整体替换为：

```typescript
const iconDirty = computed(() => {
  const item = detailItem.value;
  if (!item) return false;
  return (
    editPadding.value !== item.iconPadding ||
    normColor(editBgColor.value) !== normColor(item.iconBgColor) ||
    editUseHdLogo.value !== (item.useHdLogo ?? false) ||
    (editAppName.value.trim() || "") !== (item.appName || "")
  );
});
```

- [ ] **Step 4: openDetail 初始化新字段**

在 `editBgColor.value = row.iconBgColor ?? null;`（第 184 行）之后插入：

```typescript
  editUseHdLogo.value = row.useHdLogo ?? false;
  editAppName.value = row.appName ?? "";
```

- [ ] **Step 5: 新增 `generateAppName` 方法**

在 `pickScreenColor` 方法（结束于第 232 行 `}`）之后插入：

```typescript
/** DeepSeek 生成书签简称，填入编辑框（仍可手改） */
async function generateAppName() {
  const item = detailItem.value;
  if (!item) return;
  generatingAppName.value = true;
  try {
    const res = await generateAppNameApi(item.id);
    if (res.appName) {
      editAppName.value = res.appName;
      ElMessage.success("已生成");
    } else {
      ElMessage.warning("未能生成简称（标题为空或模型无结果）");
    }
  } finally {
    generatingAppName.value = false;
  }
}
```

- [ ] **Step 6: import `generateAppNameApi`**

第 10-15 行的 import 块加入 `generateAppNameApi`：

```typescript
import {
  applyRefetchBookmarkApi,
  generateAppNameApi,
  getBookmarkListApi,
  refetchBookmarkApi,
  updateBookmarkIconApi,
} from "#/api/bookmark";
```

- [ ] **Step 7: saveIcon 写入新字段**

第 256-263 行（`if (iconDirty.value) { ... }` 整块）替换为：

```typescript
    if (iconDirty.value) {
      const nextAppName = editAppName.value.trim() || null;
      await updateBookmarkIconApi(item.id, {
        iconPadding: editPadding.value,
        iconBgColor: editBgColor.value || null,
        useHdLogo: editUseHdLogo.value,
        appName: nextAppName,
      });
      item.iconPadding = editPadding.value;
      item.iconBgColor = editBgColor.value || undefined;
      item.useHdLogo = editUseHdLogo.value;
      item.appName = nextAppName || undefined;
    }
```

- [ ] **Step 8: 模板——编辑区加 AppName 行与高清开关行**

在编辑区 `<div class="pane-title">编辑</div>`（第 399 行）之后、`<!-- 背景颜色 -->` 行之前插入：

```html
            <!-- 书签简称：手填 + DeepSeek 生成 -->
            <div class="edit-row">
              <span class="edit-label">书签简称</span>
              <div class="edit-control">
                <ElInput
                  v-model="editAppName"
                  placeholder="书签简称"
                  size="small"
                  class="appname-input"
                />
                <ElButton
                  size="small"
                  :loading="generatingAppName"
                  @click="generateAppName"
                >
                  DeepSeek 生成
                </ElButton>
              </div>
            </div>

            <!-- 使用高清图：无高清 LOGO 时禁用 -->
            <div class="edit-row">
              <span class="edit-label">使用高清图</span>
              <div class="edit-control">
                <ElSwitch
                  v-model="editUseHdLogo"
                  :disabled="!detailItem.logoUrl"
                />
                <span v-if="!detailItem.logoUrl" class="edit-hint">
                  未获取到高清 LOGO
                </span>
              </div>
            </div>
```

- [ ] **Step 9: CSS——`.edit-row` 改左右布局**

第 682-691 行的 `.edit-row` 与 `.edit-label` 规则整体替换为：

```css
.edit-row {
  display: flex;
  flex-direction: row;
  gap: 12px;
  align-items: center;
}

.edit-label {
  flex: 0 0 72px;
  font-size: 13px;
  color: var(--el-text-color-regular);
}
```

- [ ] **Step 10: CSS——`.edit-control` 占满 + 新样式**

第 693-697 行的 `.edit-control` 规则整体替换为：

```css
.edit-control {
  display: flex;
  flex: 1;
  min-width: 0;
  align-items: center;
  gap: 12px;
}

.appname-input {
  flex: 1;
  min-width: 0;
}

.edit-hint {
  font-size: 12px;
  color: var(--el-text-color-secondary);
}
```

- [ ] **Step 11: 构建/视觉验证**

Run: `cd bookmarkify-admin && pnpm dev:ele`
确认：编辑区每行 label 在左、控件在右；书签简称行可手填、点「DeepSeek 生成」后填入结果；高清开关在无高清 LOGO 时禁用并显示提示；改任一字段后「保存」可点击；保存成功后平铺列表名称即时更新（appName 生效）。

- [ ] **Step 12: Commit**

```bash
cd /Users/tcyeee/Documents/Code/bookmarkify
git add bookmarkify-admin/apps/web-ele/src/views/bookmark/liveness/index.vue
git commit -m "feat(admin): left-right edit layout with HD toggle and appName field"
```

---

### Task 7: Web — BookmarkLogo 应用 iconBgColor / iconPadding（修 Bug #6 前台侧）

**Files:**
- Modify: `bookmarkify-web/typing/bookmark.ts:25-37`（`BookmarkShow` 加 `iconPadding?`/`iconBgColor?`）
- Modify: `bookmarkify-web/components/launchpad/cell/BookmarkLogo.vue`（加 `customBgColor`/`effectivePadding`；`logoStyle` 与 `base64PixelSize` 套用）

**Interfaces:**
- Consumes: api `BookmarkShow.iconPadding`/`iconBgColor`（Task 2）。
- Produces: 无对外接口；纯渲染。

- [ ] **Step 1: typing 加字段**

`typing/bookmark.ts` 第 33 行 `iconHdUrl: string` 之后插入：

```typescript
  iconPadding?: number
  iconBgColor?: string
```

- [ ] **Step 2: 新增 customBgColor / effectivePadding computed**

`BookmarkLogo.vue` 在 `const logoSize = computed(() => props.size ?? 80)`（第 45 行）之后插入：

```typescript
// 自定义背景色（管理台设置）：存在则直接铺该色
const customBgColor = computed(() => props.value.iconBgColor || '')
// 图片内边距（管理台设置）：收缩 base64 图标
const effectivePadding = computed(() => props.value.iconPadding ?? 0)
```

- [ ] **Step 3: `logoStyle` 优先自定义背景色**

第 59-66 行 `logoStyle` 整体替换为：

```typescript
const logoStyle = computed(() => {
  if (customBgColor.value) {
    return { backgroundColor: customBgColor.value }
  }
  return shouldUseBase64.value
    ? {
        backgroundColor: backgroundColor.value,
        backgroundImage: 'linear-gradient(rgba(255,255,255,0.88), rgba(255,255,255,0.58))',
      }
    : undefined
})
```

- [ ] **Step 4: `base64PixelSize` 套用内边距**

第 68-70 行 `base64PixelSize` 整体替换为：

```typescript
const base64PixelSize = computed(() =>
  Math.max(4, Math.round(logoSize.value * (shouldUpscale.value ? 0.6 : 0.4) - 2 * effectivePadding.value)),
)
```

- [ ] **Step 5: 构建/视觉验证**

需 api（含 Task 1/2 改动且 DB 已执行迁移）在 8001 运行。
Run: `cd bookmarkify-web && pnpm dev`
在管理台给某书签设背景色 + 内边距 + 关/开高清图并保存；刷新 web 前台，确认该书签图标的背景色、内边距、是否高清与管理台一致。

- [ ] **Step 6: Commit**

```bash
cd /Users/tcyeee/Documents/Code/bookmarkify
git add bookmarkify-web/typing/bookmark.ts \
        bookmarkify-web/components/launchpad/cell/BookmarkLogo.vue
git commit -m "fix(web): apply iconBgColor and iconPadding in BookmarkLogo"
```

---

## 执行顺序与联调

- Task 1 → 2 → 3（api）→ 4（admin 客户端）→ 5、6（admin UI）→ 7（web）。
- **DB 迁移**：Task 1 的 SQL 需在本地 PostgreSQL 手动执行后，api 才能读写 `use_hd_logo`；Task 2/7 的前台联调依赖迁移已执行。
- Task 5、6 改同一文件 `index.vue`，须按序进行（6 基于 5 的文件状态）。

## Self-Review 结论

- **Spec 覆盖**：预览三尺寸(Task5)、左右布局(Task6)、高清开关(Task1/2/6/7)、AppName 手填+DeepSeek(Task3/4/6)、Bug#6 修复(Task2/7)、保存策略 A(Task1/4/6) 均有对应 task。Bug#1 已由用户修复，不在计划内（符合 spec 范围外说明）。
- **占位符**：无 TBD/TODO；每个改代码的 step 均给出完整代码块。
- **类型一致**：`useHdLogo`/`appName`/`iconPadding`/`iconBgColor` 在 api 实体、VO、params、admin 客户端、web typing 间命名一致；端点路径 `/icon` 与 `/appname/generate` 与 Task 间引用一致；`generateAppNameApi` 返回 `{appName?: string}` 与 admin 调用一致。
