# 书签导入重设计 — Design Spec

**日期：** 2026-06-24  
**范围：** bookmarkify-api + bookmarkify-web

---

## 背景

当前一键导入功能（`POST /bookmark/upload`）存在两个问题：

1. 无重复检测：若用户已有某书签，导入会直接创建重复项。
2. 无 LOADING 状态感知：前端收到响应后调 `bookmarkStore.update()` 全量重拉，体验割裂。

---

## 目标

1. 导入前展示预览，标出重复书签，由用户决定是否跳过。
2. 确认后立即把所有待导入书签以 LOADING 状态注入 store，后续由 WebSocket 逐个更新为完整书签。
3. 文件夹裁剪：skipUrls 过滤后，0 书签的文件夹整体丢弃，1 书签的文件夹升根（书签直接放根目录，不建文件夹）。

---

## 重复判断标准

以完整 URL（`urlFull`，即 `bookmark_user_link.url_full`）为准，与当前用户已有的全部 `url_full` 集合做精确匹配。不按域名（host）去重。

---

## 后端设计

### 新增端点：`POST /bookmark/upload/preview`

- **无副作用，不写库。**
- 接收：`@RequestParam file: MultipartFile`
- 逻辑：
  1. `ChromeBookmarkParser.trim(file)` 解析文件
  2. 查当前用户所有 `bookmark_user_link.url_full`，放入 `Set<String>`
  3. 遍历所有书签，标记 `isDuplicate = urlFull in existingUrls`
- 返回：

```kotlin
// entity/Response.kt 新增
data class BookmarkImportPreviewVO(
    val total: Int,
    val duplicateCount: Int,
    val items: List<BookmarkImportItemVO>,
)
data class BookmarkImportItemVO(
    val title: String,
    val url: String,
    val folder: String?,      // 原始文件夹路径（"/" 分隔，显示用）
    val isDuplicate: Boolean,
)
```

### 改造：`POST /bookmark/upload`

- **原签名：** `upload(@RequestParam file: MultipartFile): Boolean`
- **新签名：** `upload(@RequestParam file: MultipartFile, @RequestParam(required = false, defaultValue = "") skipUrls: Set<String>): List<UserLayoutNodeVO>`
- 返回值：创建好的 LOADING 占位节点列表（含文件夹节点）
- 服务层 `importBookmarkFile` 变更：
  1. 解析文件
  2. 对每个 `SystemBookmarkStructure`，过滤掉 `urlFull in skipUrls` 的书签
  3. 文件夹裁剪：
     - 0 书签 → 整体跳过
     - 1 书签 → 该书签放根目录（`parentNodeId = null`），不建文件夹节点
     - ≥2 书签 → 正常建文件夹
  4. 事务内批量写 `user_layout_node` + `bookmark_user_link`
  5. 事务提交后发布 `BookmarkParseAndResetUserItemEvent`
  6. 返回 `List<UserLayoutNodeVO>`（所有创建的 LOADING 节点，包含文件夹节点和书签节点）

### 涉及的后端文件

| 文件 | 变更 |
|---|---|
| `entity/Response.kt` | 新增 `BookmarkImportPreviewVO`、`BookmarkImportItemVO` |
| `controller/bookmark/BookmarkController.kt` | 新增 `/upload/preview`；改造 `/upload` 签名和返回类型 |
| `server/IBookmarkService.kt` | 新增 `previewImport`；更新 `importBookmarkFile` 签名 |
| `server/impl/BookmarkServiceImpl.kt` | 实现 `previewImport`；改写 `importBookmarkFile` |
| `mapper/BookmarkUserLinkMapper.kt` | 新增 `selectUrlsByUid(uid)` 查询用户全部 urlFull |

---

## 前端设计

### 新增类型（`typing/bookmark.ts`）

```ts
export interface BookmarkImportItemVO {
  title: string
  url: string
  folder?: string | null
  isDuplicate: boolean
}

export interface BookmarkImportPreviewVO {
  total: number
  duplicateCount: number
  items: BookmarkImportItemVO[]
}
```

### HTTP 层（`server/apis/http.ts`）

新增 `uploadWithForm<T>(path, file, extra)` 方法：构造 `FormData`，将 `extra` 中的字符串数组以多值字段追加（Spring `Set<String>` 会自动绑定）。

### API 函数（`server/apis/index.ts`）

```ts
export const bookmarksUploadPreview = (file: File) =>
  http.upload<BookmarkImportPreviewVO>('/bookmark/upload/preview', file)

// 旧的 bookmarksUpload 保持函数名，更新签名
export const bookmarksUpload = (file: File, skipUrls: string[]) =>
  http.uploadWithForm<UserLayoutNodeVO[]>('/bookmark/upload', file, { skipUrls })
```

### Store（`stores/bookmark.store.ts`）

新增 action：

```ts
addImportLoadingBatch(nodes: UserLayoutNodeVO[]) {
  // node.type 来自后端：文件夹 BOOKMARK_DIR，书签 BOOKMARK_LOADING，不需要在前端覆盖
  for (const node of nodes) {
    this.nodes[node.id] = { ...node, parentId: node.parentId ?? null, children: undefined }
    if (node.type === HomeItemType.BOOKMARK_DIR) this.order[node.id] ??= []
  }
  const rootIds = nodes.filter(n => !n.parentId).map(n => n.id)
  this.order[ROOT_KEY] = [...(this.order[ROOT_KEY] ?? []), ...rootIds]
  for (const node of nodes) {
    if (node.parentId) {
      this.order[node.parentId] = [...(this.order[node.parentId] ?? []), node.id]
    }
  }
}
```

### `BookmarkManage.vue` 状态机

三阶段（`phase: 'idle' | 'reviewing' | 'importing'`）：

| 阶段 | 触发 | 展示 |
|---|---|---|
| `idle` | 初始 / 导入完成 | 现有拖拽上传区 |
| `reviewing` | preview 接口返回后 | review 面板：分文件夹分组，重复项显示「已有」tag 并默认不勾选；底部"开始导入 (N 个)"按钮 |
| `importing` | 点击确认后 | 上传区显示 spinner，statusMessage 显示"导入已开始，书签解析中" |

**用户路径：**

1. 选/拖文件 → 调 `bookmarksUploadPreview` → phase → `reviewing`
2. 若 `duplicateCount === 0`，自动跳过 review，直接走步骤 3
3. 用户调整勾选 → 点"开始导入" → phase → `importing`
4. 调 `bookmarksUpload(file, skipUrls)` → 返回 LOADING 节点列表 → 调 `bookmarkStore.addImportLoadingBatch(nodes)` → statusMessage 更新 → phase 保持 `importing`（WS 更新后用户自行切换到主页查看）

**review 面板 UI 要点：**
- 书签按 `folder` 字段分组（`folder` 为空的书签放在「根目录」分组）
- 每项：勾选框 + 网站标题 + URL 灰色小字
- 重复项：「已有」橙色/黄色 tag，默认 unchecked
- 非重复项：默认 checked
- 底部统计："共 N 个，其中 M 个已有，将导入 K 个"

---

## 涉及文件汇总

### bookmarkify-api
- `entity/Response.kt`
- `controller/bookmark/BookmarkController.kt`
- `server/IBookmarkService.kt`
- `server/impl/BookmarkServiceImpl.kt`
- `mapper/BookmarkUserLinkMapper.kt`

### bookmarkify-web
- `typing/bookmark.ts`
- `typing/index.ts`（re-export 新类型）
- `server/apis/http.ts`
- `server/apis/index.ts`
- `stores/bookmark.store.ts`
- `components/setting/BookmarkManage.vue`
