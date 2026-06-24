# 书签导入重设计 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 两阶段书签导入：先预览+重复检测（用户确认跳过哪些），再导入并立即展示 LOADING 状态，后端 WebSocket 逐个回填。

**Architecture:** 新增只读预览端点 `POST /bookmark/upload/preview`；改造导入端点接受 `skipUrls` 过滤集合，返回 `List<UserLayoutNodeVO>`（LOADING 占位）；前端三阶段状态机（idle → reviewing → importing），收到节点列表后直接注入 store 而非全量重拉。

**Tech Stack:** Kotlin 2.1 + Spring Boot 3.5 + MyBatis-Plus（后端）；Nuxt 4 + Vue 3 + Pinia + TypeScript（前端）

## Global Constraints

- 后端包名前缀：`top.tcyeee.bookmarkify`
- 重复判断：以 `bookmark_user_link.url_full` 精确匹配（完整 URL，非 host）
- 后端 `bookmark_user_link` 查询须加 `deleted = false` 过滤
- 文件夹裁剪（skipUrls 过滤后）：0 书签 → 跳过整个文件夹；1 书签 → 书签升根（parentId=null），不建文件夹节点；≥2 书签 → 正常建文件夹
- 前端 store action 直接使用后端返回的 `node.type`，不在前端覆盖
- DaisyUI 前缀：`cy-`；Toast 用 `ElMessage`；不写注释（除非逻辑非显而易见）
- 不写额外的测试文件（两端均无测试框架），改用手动验证步骤替代

---

## File Map

### bookmarkify-api（改动文件）

| 文件 | 操作 | 说明 |
|---|---|---|
| `entity/Response.kt` | 修改 | 追加 `BookmarkImportPreviewVO`、`BookmarkImportItemVO` |
| `server/IBookmarkUserLinkService.kt` | 修改 | 新增 `urlsByUid(uid)` 方法声明 |
| `server/impl/BookmarkUserLinkServiceImpl.kt` | 修改 | 实现 `urlsByUid` |
| `server/IBookmarkService.kt` | 修改 | 新增 `previewImport`；更新 `importBookmarkFile` 签名 |
| `server/impl/BookmarkServiceImpl.kt` | 修改 | 实现 `previewImport`；重写 `importBookmarkFile` |
| `controller/bookmark/BookmarkController.kt` | 修改 | 新增 `/upload/preview`；更新 `/upload` 签名和返回类型 |

### bookmarkify-web（改动文件）

| 文件 | 操作 | 说明 |
|---|---|---|
| `typing/bookmark.ts` | 修改 | 追加 `BookmarkImportItemVO`、`BookmarkImportPreviewVO` |
| `typing/index.ts` | 修改 | re-export 新类型 |
| `server/apis/http.ts` | 修改 | 新增 `uploadWithForm` 方法 |
| `server/apis/index.ts` | 修改 | 新增 `bookmarksUploadPreview`；更新 `bookmarksUpload` 签名 |
| `stores/bookmark.store.ts` | 修改 | 新增 `addImportLoadingBatch` action |
| `components/setting/BookmarkManage.vue` | 修改 | 三阶段状态机 + review 面板 |

---

### Task 1: 后端 — 新增导入响应类型

**Files:**
- Modify: `bookmarkify-api/src/main/kotlin/top/tcyeee/bookmarkify/entity/Response.kt`

**Interfaces:**
- Produces:
  ```kotlin
  data class BookmarkImportItemVO(val title: String, val url: String, val folder: String?, val isDuplicate: Boolean)
  data class BookmarkImportPreviewVO(val total: Int, val duplicateCount: Int, val items: List<BookmarkImportItemVO>)
  ```

- [ ] **Step 1: 在 `Response.kt` 末尾追加两个数据类**

  打开 `bookmarkify-api/src/main/kotlin/top/tcyeee/bookmarkify/entity/Response.kt`，在文件最后加：

  ```kotlin
  data class BookmarkImportItemVO(
      val title: String,
      val url: String,
      val folder: String?,
      val isDuplicate: Boolean,
  )

  data class BookmarkImportPreviewVO(
      val total: Int,
      val duplicateCount: Int,
      val items: List<BookmarkImportItemVO>,
  )
  ```

- [ ] **Step 2: 编译验证**

  ```bash
  cd bookmarkify-api
  ./gradlew compileKotlin
  ```

  预期：BUILD SUCCESSFUL，无编译错误。

- [ ] **Step 3: Commit**

  ```bash
  git add bookmarkify-api/src/main/kotlin/top/tcyeee/bookmarkify/entity/Response.kt
  git commit -m "feat(api): add BookmarkImportPreviewVO and BookmarkImportItemVO response types"
  ```

---

### Task 2: 后端 — 新增 `urlsByUid` 到 `IBookmarkUserLinkService`

**Files:**
- Modify: `bookmarkify-api/src/main/kotlin/top/tcyeee/bookmarkify/server/IBookmarkUserLinkService.kt`
- Modify: `bookmarkify-api/src/main/kotlin/top/tcyeee/bookmarkify/server/impl/BookmarkUserLinkServiceImpl.kt`

**Interfaces:**
- Produces: `IBookmarkUserLinkService.urlsByUid(uid: String): Set<String>` — 返回该用户所有未删除书签的 `urlFull` 集合

- [ ] **Step 1: 在接口中声明方法**

  打开 `server/IBookmarkUserLinkService.kt`，在接口内追加：

  ```kotlin
  /** 返回用户所有未删除书签的完整 URL 集合，用于导入时重复检测 */
  fun urlsByUid(uid: String): Set<String>
  ```

- [ ] **Step 2: 在实现类中实现**

  打开 `server/impl/BookmarkUserLinkServiceImpl.kt`，在 class body 末尾追加：

  ```kotlin
  override fun urlsByUid(uid: String): Set<String> =
      ktQuery()
          .eq(BookmarkUserLink::uid, uid)
          .eq(BookmarkUserLink::deleted, false)
          .list()
          .map { it.urlFull }
          .toHashSet()
  ```

  注意：`BookmarkUserLink` 已在该文件的现有 `ktQuery()` 调用中使用，无需额外 import。

- [ ] **Step 3: 编译验证**

  ```bash
  ./gradlew compileKotlin
  ```

  预期：BUILD SUCCESSFUL。

- [ ] **Step 4: Commit**

  ```bash
  git add bookmarkify-api/src/main/kotlin/top/tcyeee/bookmarkify/server/IBookmarkUserLinkService.kt \
          bookmarkify-api/src/main/kotlin/top/tcyeee/bookmarkify/server/impl/BookmarkUserLinkServiceImpl.kt
  git commit -m "feat(api): add urlsByUid to IBookmarkUserLinkService for import duplicate detection"
  ```

---

### Task 3: 后端 — 预览端点（服务层 + 控制器）

**Files:**
- Modify: `bookmarkify-api/src/main/kotlin/top/tcyeee/bookmarkify/server/IBookmarkService.kt`
- Modify: `bookmarkify-api/src/main/kotlin/top/tcyeee/bookmarkify/server/impl/BookmarkServiceImpl.kt`
- Modify: `bookmarkify-api/src/main/kotlin/top/tcyeee/bookmarkify/controller/bookmark/BookmarkController.kt`

**Interfaces:**
- Consumes: `IBookmarkUserLinkService.urlsByUid(uid)` (Task 2)；`BookmarkImportPreviewVO`、`BookmarkImportItemVO` (Task 1)；`ChromeBookmarkParser.trim(file)`（已存在）
- Produces: `IBookmarkService.previewImport(file, uid): BookmarkImportPreviewVO`；HTTP `POST /bookmark/upload/preview`

- [ ] **Step 1: 在 `IBookmarkService` 中声明 `previewImport`**

  打开 `server/IBookmarkService.kt`，在 `importBookmarkFile` 声明之前插入：

  ```kotlin
  /** 解析 Chrome 书签 HTML，返回含重复标记的预览列表（不写库） */
  fun previewImport(file: MultipartFile, uid: String): BookmarkImportPreviewVO
  ```

  文件顶部 import 块确认已有 `import org.springframework.web.multipart.MultipartFile`；还需添加：
  ```kotlin
  import top.tcyeee.bookmarkify.entity.BookmarkImportPreviewVO
  ```

- [ ] **Step 2: 在 `BookmarkServiceImpl` 中实现**

  打开 `server/impl/BookmarkServiceImpl.kt`，在 `importBookmarkFile` 方法之前插入：

  ```kotlin
  override fun previewImport(file: MultipartFile, uid: String): BookmarkImportPreviewVO {
      val existingUrls: Set<String> = bookmarkUserLinkService.urlsByUid(uid)
      val structures = ChromeBookmarkParser.trim(file)
      val items = structures.flatMap { structure ->
          structure.bookmarks.map { raw ->
              BookmarkImportItemVO(
                  title = raw.title,
                  url = raw.url,
                  folder = structure.folderName.takeIf { it != "ROOT" },
                  isDuplicate = raw.url in existingUrls,
              )
          }
      }
      return BookmarkImportPreviewVO(
          total = items.size,
          duplicateCount = items.count { it.isDuplicate },
          items = items,
      )
  }
  ```

  确认文件顶部 import 中有：
  ```kotlin
  import top.tcyeee.bookmarkify.entity.BookmarkImportPreviewVO
  import top.tcyeee.bookmarkify.entity.BookmarkImportItemVO
  ```

- [ ] **Step 3: 在 `BookmarkController` 中新增端点**

  打开 `controller/bookmark/BookmarkController.kt`，在 `upload` 方法之前插入：

  ```kotlin
  @PostMapping("/upload/preview")
  @Operation(summary = "书签导入预览（不写库）")
  fun uploadPreview(@RequestParam file: MultipartFile): BookmarkImportPreviewVO =
      bookmarkService.previewImport(file, BaseUtils.uid())
  ```

  确认 import 中有（或追加）：
  ```kotlin
  import top.tcyeee.bookmarkify.entity.BookmarkImportPreviewVO
  ```

- [ ] **Step 4: 编译并启动本地服务验证**

  ```bash
  ./gradlew compileKotlin
  ./gradlew bootRun --args='--spring.profiles.active=dev'
  ```

  用 curl 上传一个 Chrome 书签 HTML：
  ```bash
  curl -X POST http://localhost:8001/bookmark/upload/preview \
    -H "satoken: <你的token>" \
    -F "file=@/path/to/bookmarks.html"
  ```

  预期：返回 JSON，含 `total`、`duplicateCount`、`items` 数组；已有书签的 `isDuplicate=true`，其余 `false`。

- [ ] **Step 5: Commit**

  ```bash
  git add bookmarkify-api/src/main/kotlin/top/tcyeee/bookmarkify/server/IBookmarkService.kt \
          bookmarkify-api/src/main/kotlin/top/tcyeee/bookmarkify/server/impl/BookmarkServiceImpl.kt \
          bookmarkify-api/src/main/kotlin/top/tcyeee/bookmarkify/controller/bookmark/BookmarkController.kt
  git commit -m "feat(api): add POST /bookmark/upload/preview endpoint for import duplicate detection"
  ```

---

### Task 4: 后端 — 重构 `importBookmarkFile`（接受 skipUrls，返回节点列表）

**Files:**
- Modify: `bookmarkify-api/src/main/kotlin/top/tcyeee/bookmarkify/server/IBookmarkService.kt`
- Modify: `bookmarkify-api/src/main/kotlin/top/tcyeee/bookmarkify/server/impl/BookmarkServiceImpl.kt`
- Modify: `bookmarkify-api/src/main/kotlin/top/tcyeee/bookmarkify/controller/bookmark/BookmarkController.kt`

**Interfaces:**
- Consumes: `UserLayoutNodeVO`（已存在）；`NodeTypeEnum.BOOKMARK_DIR`（已存在）
- Produces: `IBookmarkService.importBookmarkFile(file, uid, skipUrls): List<UserLayoutNodeVO>`；HTTP `POST /bookmark/upload` 返回 `List<UserLayoutNodeVO>`

- [ ] **Step 1: 更新 `IBookmarkService` 中 `importBookmarkFile` 的签名**

  把原来：
  ```kotlin
  /** 导入 Chrome 书签 */
  fun importBookmarkFile(file: MultipartFile, uid: String)
  ```
  改为：
  ```kotlin
  /** 导入 Chrome 书签；skipUrls 为用户选择跳过的完整 URL 集合；返回创建好的 LOADING 占位节点列表 */
  fun importBookmarkFile(file: MultipartFile, uid: String, skipUrls: Set<String> = emptySet()): List<UserLayoutNodeVO>
  ```

- [ ] **Step 2: 重写 `BookmarkServiceImpl.importBookmarkFile`**

  用下面的实现完整替换 `importBookmarkFile` 方法体（保持方法签名，仅替换 `{...}` 内部）：

  ```kotlin
  override fun importBookmarkFile(
      file: MultipartFile,
      uid: String,
      skipUrls: Set<String>,
  ): List<UserLayoutNodeVO> {
      val structures: List<SystemBookmarkStructure> = ChromeBookmarkParser.trim(file)

      // 每个 structure 过滤掉 skipUrls，再按剩余书签数裁剪文件夹
      data class FolderSlice(
          val folderNode: UserLayoutNodeEntity?,          // null = 根目录（1书签升根）
          val bookmarks: List<ChromeBookmarkRawData>,
      )

      val slices: List<FolderSlice> = structures.mapNotNull { s ->
          val kept = s.bookmarks.filter { it.url !in skipUrls }
          when (kept.size) {
              0    -> null                                // 整个文件夹跳过
              1    -> FolderSlice(null, kept)             // 书签升根
              else -> FolderSlice(UserLayoutNodeEntity(uid, s), kept)
          }
      }

      // 事务内批量写库
      val pairs: List<Pair<UserLayoutNodeEntity, BookmarkUserLink>> = txTemplate.execute {
          // 先插文件夹节点（仅 folderNode != null 的）
          val folderNodes = slices.mapNotNull { it.folderNode }
          if (folderNodes.isNotEmpty()) layoutNodeMapper.insert(folderNodes)

          // 再批量插书签节点 + 用户链接
          slices.flatMap { slice ->
              val parentId = slice.folderNode?.id  // null → 根
              slice.bookmarks.map { raw ->
                  val node = UserLayoutNodeEntity(uid = uid, type = NodeTypeEnum.BOOKMARK_LOADING, parentId = parentId)
                  Pair(node, BookmarkUserLink(uid, node.id, raw))
              }
          }.also { data ->
              layoutNodeMapper.insert(data.map { it.first })
              bookmarkUserLinkMapper.insert(data.map { it.second })
          }
      } ?: emptyList()

      // 事务提交后发布解析事件
      pairs.forEach { (node, link) ->
          eventPublisher.publishEvent(BookmarkParseAndResetUserItemEvent(uid, link.urlFull, link.id, node.id))
      }

      // 构造返回的 VO 列表（文件夹节点 + LOADING 书签节点）
      val result = mutableListOf<UserLayoutNodeVO>()
      for (slice in slices) {
          slice.folderNode?.let { f ->
              result.add(UserLayoutNodeVO(id = f.id, type = NodeTypeEnum.BOOKMARK_DIR, name = f.name))
          }
          for (pair in pairs.filter { (node, _) -> node.parentId == slice.folderNode?.id }) {
              val (node, link) = pair
              result.add(UserLayoutNodeVO(id = node.id, type = NodeTypeEnum.BOOKMARK_LOADING, name = link.title, parentId = node.parentId))
          }
      }
      return result
  }
  ```

  确认 `BookmarkUserLink` 有一个接受 `(uid, nodeId, ChromeBookmarkRawData)` 的构造器（在 `BookmarkEntity.kt` 中已存在，对应 `constructor(uid: String, layoutNodeId: String, raw: ChromeBookmarkRawData)`）。

  还需确认 `UserLayoutNodeEntity` 有接受 `(uid, type, parentId)` 的构造器——当前主构造器包含所有这些字段，可以具名参数调用：
  ```kotlin
  UserLayoutNodeEntity(uid = uid, type = NodeTypeEnum.BOOKMARK_LOADING, parentId = parentId)
  ```

- [ ] **Step 3: 更新 `BookmarkController.upload`**

  把原来：
  ```kotlin
  @PostMapping("/upload")
  @Operation(summary = "书签上传")
  fun upload(@RequestParam file: MultipartFile): Boolean = true
      .also { bookmarkService.importBookmarkFile(file, BaseUtils.uid()) }
  ```
  改为：
  ```kotlin
  @PostMapping("/upload")
  @Operation(summary = "书签上传")
  fun upload(
      @RequestParam file: MultipartFile,
      @RequestParam(required = false) skipUrls: List<String>?,
  ): List<UserLayoutNodeVO> =
      bookmarkService.importBookmarkFile(file, BaseUtils.uid(), skipUrls?.toHashSet() ?: emptySet())
  ```

- [ ] **Step 4: 检查 `BookmarkUserLink` 构造器与 `link.title`**

  打开 `bookmarkify-api/src/main/kotlin/top/tcyeee/bookmarkify/entity/entity/BookmarkEntity.kt`，找到 `BookmarkUserLink` 的 `constructor(uid: String, layoutNodeId: String, raw: ChromeBookmarkRawData)` 构造器，确认 `title` 字段赋值为 `raw.title`（以便在 VO 中使用）。若 `BookmarkUserLink` 没有直接暴露 `title`，则用 `raw.title` 替代 `link.title`——届时需要在 `pairs` 上存储 `raw` 引用。

  最简单的做法：把 `data class` 改为 `Triple<UserLayoutNodeEntity, BookmarkUserLink, ChromeBookmarkRawData>`，或者从 `link.urlFull` 中提取 host 作为 name。推荐直接存 title：

  在 `importBookmarkFile` 里，用三元组代替 pair：
  ```kotlin
  data class ImportEntry(val node: UserLayoutNodeEntity, val link: BookmarkUserLink, val title: String)
  ```

  整体调整后的实现（完整版，替换 Step 2 的代码）：

  ```kotlin
  override fun importBookmarkFile(
      file: MultipartFile,
      uid: String,
      skipUrls: Set<String>,
  ): List<UserLayoutNodeVO> {
      val structures = ChromeBookmarkParser.trim(file)

      data class FolderSlice(val folderNode: UserLayoutNodeEntity?, val items: List<Pair<ChromeBookmarkRawData, UserLayoutNodeEntity>>)

      val slices: List<FolderSlice> = structures.mapNotNull { s ->
          val kept = s.bookmarks.filter { it.url !in skipUrls }
          when (kept.size) {
              0    -> null
              1    -> {
                  val node = UserLayoutNodeEntity(uid = uid, type = NodeTypeEnum.BOOKMARK_LOADING)
                  FolderSlice(null, listOf(Pair(kept[0], node)))
              }
              else -> {
                  val folder = UserLayoutNodeEntity(uid, s)
                  val nodes = kept.map { raw -> Pair(raw, UserLayoutNodeEntity(uid = uid, type = NodeTypeEnum.BOOKMARK_LOADING, parentId = folder.id)) }
                  FolderSlice(folder, nodes)
              }
          }
      }

      val allBookmarkNodes: List<Pair<ChromeBookmarkRawData, UserLayoutNodeEntity>> = slices.flatMap { it.items }
      val allLinks: List<BookmarkUserLink> = allBookmarkNodes.map { (raw, node) -> BookmarkUserLink(uid, node.id, raw) }

      txTemplate.execute {
          val folderNodes = slices.mapNotNull { it.folderNode }
          if (folderNodes.isNotEmpty()) layoutNodeMapper.insert(folderNodes)
          layoutNodeMapper.insert(allBookmarkNodes.map { it.second })
          bookmarkUserLinkMapper.insert(allLinks)
      }

      allLinks.zip(allBookmarkNodes.map { it.second }).forEach { (link, node) ->
          eventPublisher.publishEvent(BookmarkParseAndResetUserItemEvent(uid, link.urlFull, link.id, node.id))
      }

      val result = mutableListOf<UserLayoutNodeVO>()
      slices.forEach { slice ->
          slice.folderNode?.let { f ->
              result.add(UserLayoutNodeVO(id = f.id, type = NodeTypeEnum.BOOKMARK_DIR, name = f.name))
          }
          slice.items.forEach { (raw, node) ->
              result.add(UserLayoutNodeVO(id = node.id, type = NodeTypeEnum.BOOKMARK_LOADING, name = raw.title, parentId = node.parentId))
          }
      }
      return result
  }
  ```

- [ ] **Step 5: 编译并手动验证**

  ```bash
  ./gradlew compileKotlin
  ./gradlew bootRun --args='--spring.profiles.active=dev'
  ```

  用 curl 测试：
  ```bash
  # 不带 skipUrls（全部导入）
  curl -X POST http://localhost:8001/bookmark/upload \
    -H "satoken: <token>" \
    -F "file=@bookmarks.html"
  # 预期：返回 UserLayoutNodeVO[] JSON 列表；包含文件夹节点（type=BOOKMARK_DIR）和书签节点（type=BOOKMARK_LOADING）

  # 带 skipUrls
  curl -X POST http://localhost:8001/bookmark/upload \
    -H "satoken: <token>" \
    -F "file=@bookmarks.html" \
    -F "skipUrls=https://github.com" \
    -F "skipUrls=https://google.com"
  # 预期：返回列表中不含被跳过的 URL；1书签文件夹的书签 parentId=null
  ```

- [ ] **Step 6: Commit**

  ```bash
  git add bookmarkify-api/src/main/kotlin/top/tcyeee/bookmarkify/server/IBookmarkService.kt \
          bookmarkify-api/src/main/kotlin/top/tcyeee/bookmarkify/server/impl/BookmarkServiceImpl.kt \
          bookmarkify-api/src/main/kotlin/top/tcyeee/bookmarkify/controller/bookmark/BookmarkController.kt
  git commit -m "feat(api): rework POST /bookmark/upload to accept skipUrls and return LOADING node list"
  ```

---

### Task 5: 前端 — 新增导入相关类型

**Files:**
- Modify: `bookmarkify-web/typing/bookmark.ts`
- Modify: `bookmarkify-web/typing/index.ts`

**Interfaces:**
- Produces:
  ```ts
  interface BookmarkImportItemVO { title: string; url: string; folder?: string | null; isDuplicate: boolean }
  interface BookmarkImportPreviewVO { total: number; duplicateCount: number; items: BookmarkImportItemVO[] }
  ```

- [ ] **Step 1: 追加类型到 `typing/bookmark.ts`**

  打开 `bookmarkify-web/typing/bookmark.ts`，在文件末尾追加：

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

- [ ] **Step 2: 在 `typing/index.ts` 中 re-export**

  打开 `bookmarkify-web/typing/index.ts`，确认有导出 bookmark.ts 的内容（通常已有 `export * from './bookmark'`）。若没有则追加：

  ```ts
  export type { BookmarkImportItemVO, BookmarkImportPreviewVO } from './bookmark'
  ```

- [ ] **Step 3: Commit**

  ```bash
  git add bookmarkify-web/typing/bookmark.ts bookmarkify-web/typing/index.ts
  git commit -m "feat(web): add BookmarkImportItemVO and BookmarkImportPreviewVO types"
  ```

---

### Task 6: 前端 — 更新 HTTP 客户端 + API 函数

**Files:**
- Modify: `bookmarkify-web/server/apis/http.ts`
- Modify: `bookmarkify-web/server/apis/index.ts`

**Interfaces:**
- Consumes: `BookmarkImportPreviewVO`、`UserLayoutNodeVO`（Task 5）
- Produces:
  - `http.uploadWithForm<T>(path, file, extra)` 方法
  - `bookmarksUploadPreview(file: File): Promise<BookmarkImportPreviewVO>`
  - `bookmarksUpload(file: File, skipUrls: string[]): Promise<UserLayoutNodeVO[]>`

- [ ] **Step 1: 在 `http.ts` 中新增 `uploadWithForm`**

  打开 `bookmarkify-web/server/apis/http.ts`，在 `upload` 静态方法之后、`uploadFile` 之前，插入：

  ```ts
  static uploadWithForm<T = unknown>(path: string, file: File, extra: Record<string, string | string[]>): Promise<T> {
    return this.uploadFormData<T>(path, (form) => {
      form.append('file', file)
      for (const [key, value] of Object.entries(extra)) {
        if (Array.isArray(value)) {
          value.forEach((v) => form.append(key, v))
        } else {
          form.append(key, value)
        }
      }
    })
  }
  ```

  然后把 `uploadFile` 方法改造为通用的 `uploadFormData`，原有的 `uploadFile` 调用 `uploadFormData`：

  ```ts
  static async uploadFormData<T = unknown>(path: string, buildForm: (form: FormData) => void): Promise<T> {
    const authStore = useAuthStore()
    if (!authStore.account?.token) {
      await authStore.logout()
      return Promise.reject(new Error('未登录'))
    }

    const formData = new FormData()
    buildForm(formData)

    const url = useRuntimeConfig().public.apiBase + path
    console.log(`[API] UPLOAD::${path}`)

    const exec = async (): Promise<T> => {
      const token = authStore.account?.token ?? ''
      try {
        const response = await fetch(url, {
          method: 'POST',
          headers: { satoken: token },
          body: formData,
        })
        const data = (await response.json()) as Result<T>
        return (await handleResult(data)) as T
      } catch (error) {
        if ((error instanceof TypeError || error instanceof SyntaxError) && import.meta.client) ElMessage.error(`Oops,网络错误,请重试`)
        return Promise.reject(error)
      }
    }

    return this.withDebounce(`UPLOAD:${url}`, exec)
  }

  static async uploadFile<T = unknown>(path: string, file: File): Promise<T> {
    return this.uploadFormData<T>(path, (form) => form.append('file', file))
  }
  ```

- [ ] **Step 2: 更新 `server/apis/index.ts`**

  找到：
  ```ts
  export const bookmarksUpload = (file: File) => http.upload<boolean>('/bookmark/upload', file)
  ```
  替换为：
  ```ts
  export const bookmarksUploadPreview = (file: File) =>
    http.upload<t.BookmarkImportPreviewVO>('/bookmark/upload/preview', file)

  export const bookmarksUpload = (file: File, skipUrls: string[]) =>
    http.uploadWithForm<t.UserLayoutNodeVO[]>('/bookmark/upload', file, { skipUrls })
  ```

- [ ] **Step 3: 手动验证 TypeScript 类型正确**

  ```bash
  cd bookmarkify-web
  npx nuxi prepare   # 生成类型
  ```

  预期：无类型错误输出（Nuxt 会报告类型问题）。

- [ ] **Step 4: Commit**

  ```bash
  git add bookmarkify-web/server/apis/http.ts bookmarkify-web/server/apis/index.ts
  git commit -m "feat(web): add uploadWithForm and update bookmarksUpload signature for import redesign"
  ```

---

### Task 7: 前端 — 更新 bookmark store

**Files:**
- Modify: `bookmarkify-web/stores/bookmark.store.ts`

**Interfaces:**
- Consumes: `UserLayoutNodeVO`（已有）、`HomeItemType`（已有）、`ROOT_KEY`（已有）
- Produces: `bookmarkStore.addImportLoadingBatch(nodes: UserLayoutNodeVO[])`

- [ ] **Step 1: 在 store actions 中新增 `addImportLoadingBatch`**

  打开 `bookmarkify-web/stores/bookmark.store.ts`，在 `addLoading` action 之后插入：

  ```ts
  // 批量注入导入书签的 LOADING 节点（含文件夹）；避免全量 update()
  addImportLoadingBatch(nodes: UserLayoutNodeVO[]) {
    for (const node of nodes) {
      this.nodes[node.id] = { ...node, parentId: node.parentId ?? null, children: undefined }
      // 为文件夹节点初始化子顺序表
      if (node.type === HomeItemType.BOOKMARK_DIR) {
        this.order[node.id] ??= []
      }
    }
    // 根节点（无 parentId）追加到根列表末尾
    const rootIds = nodes.filter((n) => !n.parentId).map((n) => n.id)
    this.order[ROOT_KEY] = [...(this.order[ROOT_KEY] ?? []), ...rootIds]
    // 有 parentId 的节点追加到对应文件夹的子顺序列表
    for (const node of nodes) {
      if (node.parentId) {
        this.order[node.parentId] = [...(this.order[node.parentId] ?? []), node.id]
      }
    }
  },
  ```

- [ ] **Step 2: Commit**

  ```bash
  git add bookmarkify-web/stores/bookmark.store.ts
  git commit -m "feat(web): add addImportLoadingBatch action to bookmark store"
  ```

---

### Task 8: 前端 — 改造 `BookmarkManage.vue`（三阶段状态机 + review 面板）

**Files:**
- Modify: `bookmarkify-web/components/setting/BookmarkManage.vue`

**Interfaces:**
- Consumes: `bookmarksUploadPreview(file)`（Task 6）；`bookmarksUpload(file, skipUrls)`（Task 6）；`bookmarkStore.addImportLoadingBatch(nodes)`（Task 7）；`BookmarkImportPreviewVO`、`BookmarkImportItemVO`（Task 5）

- [ ] **Step 1: 用新版本完整替换 `BookmarkManage.vue`**

  用下面的内容完整替换文件：

  ```vue
  <template>
    <div class="space-y-6 text-slate-900 dark:text-slate-100 transition-colors">
      <div>
        <h3 class="text-xl font-semibold">导入书签</h3>
        <p class="mt-1 text-sm text-slate-500 dark:text-slate-400">从 Chrome 导出的 HTML 文件中批量导入书签。</p>
      </div>

      <!-- 阶段：idle / importing — 上传区 -->
      <template v-if="phase !== 'reviewing'">
        <label
          class="relative flex flex-col items-center justify-center gap-4 w-full rounded-2xl border-2 border-dashed cursor-pointer transition-colors"
          :class="[
            isDragging
              ? 'border-sky-400 bg-sky-50 dark:bg-sky-950/30'
              : 'border-slate-200 dark:border-slate-700 bg-slate-50 dark:bg-slate-900/50 hover:border-sky-300 hover:bg-sky-50/50 dark:hover:bg-sky-950/20',
            phase !== 'idle' ? 'pointer-events-none opacity-60' : '',
          ]"
          style="min-height: 220px"
          @dragover.prevent="isDragging = true"
          @dragleave.prevent="isDragging = false"
          @drop.prevent="handleDrop">
          <input ref="fileInputRef" type="file" accept=".html,.htm" class="sr-only" :disabled="phase !== 'idle'" @change="handleFileChange" />
          <div class="flex flex-col items-center gap-3 px-6 py-8 text-center select-none">
            <div class="flex items-center justify-center w-14 h-14 rounded-2xl bg-white dark:bg-slate-800 shadow-sm border border-slate-100 dark:border-slate-700">
              <Icon
                :icon="phase !== 'idle' ? 'memory:rotate-clockwise' : 'memory:upload'"
                class="size-7 text-sky-500"
                :class="{ 'animate-spin': phase !== 'idle' }" />
            </div>
            <div>
              <p class="text-base font-medium text-slate-700 dark:text-slate-200">
                {{ phase === 'importing' ? '导入已开始，书签解析中…' : phase === 'loading' ? '正在读取文件…' : '点击选择或拖拽文件到此处' }}
              </p>
              <p class="mt-1 text-sm text-slate-400 dark:text-slate-500">支持 Chrome / Edge 导出的 .html 书签文件</p>
            </div>
            <button v-if="phase === 'idle'" type="button" class="cy-btn cy-btn-accent cy-btn-sm pointer-events-none">选择文件</button>
          </div>
        </label>
      </template>

      <!-- 阶段：reviewing — review 面板 -->
      <template v-if="phase === 'reviewing' && previewData">
        <div class="rounded-2xl border border-slate-200 dark:border-slate-700 overflow-hidden">
          <!-- 头部统计 -->
          <div class="flex items-center justify-between px-4 py-3 bg-slate-50 dark:bg-slate-800/60 border-b border-slate-200 dark:border-slate-700">
            <p class="text-sm text-slate-600 dark:text-slate-300">
              共 <span class="font-semibold">{{ previewData.total }}</span> 个书签
              <template v-if="previewData.duplicateCount > 0">
                ，其中 <span class="font-semibold text-amber-600 dark:text-amber-400">{{ previewData.duplicateCount }}</span> 个已有
              </template>
            </p>
            <p class="text-sm text-sky-600 dark:text-sky-400 font-medium">将导入 {{ selectedCount }} 个</p>
          </div>

          <!-- 书签列表（按文件夹分组） -->
          <div class="max-h-80 overflow-y-auto divide-y divide-slate-100 dark:divide-slate-800">
            <div v-for="group in groupedItems" :key="group.folder ?? '__root__'">
              <!-- 文件夹标题行 -->
              <div
                v-if="group.folder"
                class="flex items-center gap-2 px-4 py-2 bg-slate-50/70 dark:bg-slate-800/40 sticky top-0 z-10">
                <Icon icon="memory:folder" class="size-4 text-amber-500 shrink-0" />
                <span class="text-xs font-semibold text-slate-500 dark:text-slate-400 uppercase tracking-wide truncate">{{ group.folder }}</span>
              </div>
              <!-- 书签项 -->
              <label
                v-for="item in group.items"
                :key="item.url"
                class="flex items-center gap-3 px-4 py-2.5 hover:bg-slate-50 dark:hover:bg-slate-800/50 cursor-pointer transition-colors">
                <input type="checkbox" class="cy-checkbox cy-checkbox-sm" :checked="checkedUrls.has(item.url)" @change="toggleUrl(item.url)" />
                <div class="flex-1 min-w-0">
                  <p class="text-sm font-medium truncate" :class="item.isDuplicate ? 'text-slate-400 dark:text-slate-500' : 'text-slate-700 dark:text-slate-200'">
                    {{ item.title }}
                  </p>
                  <p class="text-xs text-slate-400 dark:text-slate-500 truncate">{{ item.url }}</p>
                </div>
                <span v-if="item.isDuplicate" class="shrink-0 text-xs px-1.5 py-0.5 rounded bg-amber-100 text-amber-700 dark:bg-amber-900/40 dark:text-amber-300">已有</span>
              </label>
            </div>
          </div>

          <!-- 底部操作 -->
          <div class="flex items-center justify-between px-4 py-3 bg-slate-50 dark:bg-slate-800/60 border-t border-slate-200 dark:border-slate-700">
            <button type="button" class="cy-btn cy-btn-ghost cy-btn-sm" @click="reset">取消</button>
            <button
              type="button"
              class="cy-btn cy-btn-accent cy-btn-sm"
              :disabled="selectedCount === 0"
              @click="startImport">
              开始导入（{{ selectedCount }} 个）
            </button>
          </div>
        </div>
      </template>

      <!-- 状态消息 -->
      <Transition name="fade-fast">
        <div
          v-if="statusMessage"
          class="flex items-start gap-3 rounded-xl px-4 py-3 text-sm transition-colors"
          :class="statusClass">
          <Icon :icon="statusType === 'success' ? 'memory:check-circle' : statusType === 'error' ? 'memory:close-circle' : 'memory:information'" class="size-5 mt-0.5 shrink-0" />
          <span>{{ statusMessage }}</span>
        </div>
      </Transition>

      <!-- 使用说明（仅 idle 时展示） -->
      <div v-if="phase === 'idle'" class="rounded-xl border border-slate-100 dark:border-slate-800 bg-slate-50 dark:bg-slate-900/50 p-5 text-sm text-slate-500 dark:text-slate-400 space-y-2">
        <p class="font-medium text-slate-600 dark:text-slate-300">如何导出 Chrome 书签？</p>
        <ol class="list-decimal list-inside space-y-1 text-slate-500 dark:text-slate-400">
          <li>打开 Chrome，点击右上角菜单 → 书签 → 书签管理器</li>
          <li>点击右上角三点图标 → 导出书签</li>
          <li>保存为 <code class="px-1 py-0.5 rounded bg-slate-200 dark:bg-slate-700 text-slate-700 dark:text-slate-200 font-mono text-xs">.html</code> 文件后上传即可</li>
        </ol>
      </div>
    </div>
  </template>

  <script lang="ts" setup>
  import { bookmarksUploadPreview, bookmarksUpload } from '@api'
  import type * as t from '@typing'

  type Phase = 'idle' | 'loading' | 'reviewing' | 'importing'

  const bookmarkStore = useBookmarkStore()

  const fileInputRef = ref<HTMLInputElement>()
  const phase = ref<Phase>('idle')
  const isDragging = ref(false)
  const statusMessage = ref('')
  const statusType = ref<'default' | 'success' | 'error'>('default')
  const previewData = ref<t.BookmarkImportPreviewVO | null>(null)
  const pendingFile = ref<File | null>(null)
  const checkedUrls = ref(new Set<string>())

  const statusClass = computed(() =>
    statusType.value === 'success'
      ? 'bg-emerald-50 text-emerald-700 dark:bg-emerald-900/40 dark:text-emerald-200'
      : statusType.value === 'error'
        ? 'bg-rose-50 text-rose-700 dark:bg-rose-900/40 dark:text-rose-200'
        : 'bg-sky-50 text-sky-700 dark:bg-sky-900/40 dark:text-sky-200'
  )

  const groupedItems = computed<Array<{ folder: string | null; items: t.BookmarkImportItemVO[] }>>(() => {
    if (!previewData.value) return []
    const map = new Map<string, t.BookmarkImportItemVO[]>()
    for (const item of previewData.value.items) {
      const key = item.folder ?? ''
      if (!map.has(key)) map.set(key, [])
      map.get(key)!.push(item)
    }
    return [...map.entries()].map(([key, items]) => ({ folder: key || null, items }))
  })

  const selectedCount = computed(() => checkedUrls.value.size)

  function toggleUrl(url: string) {
    if (checkedUrls.value.has(url)) {
      checkedUrls.value.delete(url)
    } else {
      checkedUrls.value.add(url)
    }
    // 触发响应式更新
    checkedUrls.value = new Set(checkedUrls.value)
  }

  function reset() {
    phase.value = 'idle'
    previewData.value = null
    pendingFile.value = null
    checkedUrls.value = new Set()
    statusMessage.value = ''
    if (fileInputRef.value) fileInputRef.value.value = ''
  }

  function handleDrop(event: DragEvent) {
    isDragging.value = false
    const file = event.dataTransfer?.files?.[0]
    if (file) processFile(file)
  }

  function handleFileChange(event: Event) {
    const file = (event.target as HTMLInputElement).files?.[0]
    if (file) processFile(file)
  }

  async function processFile(file: File) {
    if (!file.name.endsWith('.html') && !file.name.endsWith('.htm')) {
      ElMessage.warning('请上传从 Chrome 导出的 HTML 书签文件')
      if (fileInputRef.value) fileInputRef.value.value = ''
      return
    }

    phase.value = 'loading'
    statusMessage.value = ''

    try {
      const data = await bookmarksUploadPreview(file)

      if (data.total === 0) {
        statusMessage.value = '文件中没有找到可导入的书签。'
        statusType.value = 'default'
        phase.value = 'idle'
        return
      }

      previewData.value = data
      pendingFile.value = file

      // 非重复项默认勾选
      const initial = new Set(data.items.filter((i) => !i.isDuplicate).map((i) => i.url))

      if (data.duplicateCount === 0) {
        // 无重复，直接导入
        checkedUrls.value = initial
        await startImport()
      } else {
        checkedUrls.value = initial
        phase.value = 'reviewing'
      }
    } catch (error: any) {
      statusMessage.value = error?.msg || error?.message || '读取文件失败，请重试。'
      statusType.value = 'error'
      phase.value = 'idle'
      if (fileInputRef.value) fileInputRef.value.value = ''
    }
  }

  async function startImport() {
    if (!pendingFile.value) return
    const skipUrls = previewData.value
      ? previewData.value.items.filter((i) => !checkedUrls.value.has(i.url)).map((i) => i.url)
      : []

    phase.value = 'importing'

    try {
      const nodes = await bookmarksUpload(pendingFile.value, skipUrls)
      bookmarkStore.addImportLoadingBatch(nodes)
      statusMessage.value = `导入已开始！共 ${nodes.length} 项正在后台解析，稍后会自动更新。`
      statusType.value = 'success'
    } catch (error: any) {
      statusMessage.value = error?.msg || error?.message || '导入失败，请稍后重试。'
      statusType.value = 'error'
      phase.value = 'idle'
    } finally {
      previewData.value = null
      pendingFile.value = null
      checkedUrls.value = new Set()
      if (fileInputRef.value) fileInputRef.value.value = ''
    }
  }
  </script>

  <style scoped>
  .fade-fast-enter-active,
  .fade-fast-leave-active {
    transition: opacity 200ms ease, transform 200ms ease;
  }
  .fade-fast-enter-from,
  .fade-fast-leave-to {
    opacity: 0;
    transform: translateY(4px);
  }
  </style>
  ```

- [ ] **Step 2: 本地启动前端验证完整流程**

  ```bash
  cd bookmarkify-web
  pnpm dev
  ```

  打开 `http://localhost:3000` → 设置 → 书签管理，验证以下场景：

  1. **无重复**：上传一个全新书签的 HTML → 直接进入 importing 状态，主页出现 LOADING 格子，WS 更新后变为真实书签
  2. **有重复**：上传含已有 URL 的 HTML → 弹出 review 面板，重复项打"已有"tag 且默认不勾选 → 调整后点"开始导入" → LOADING 格子出现
  3. **取消**：review 面板点"取消" → 回到 idle 状态
  4. **文件夹裁剪**：导入文件夹含 1 个非重复书签 → 该书签出现在根目录（无文件夹）

- [ ] **Step 3: Commit**

  ```bash
  git add bookmarkify-web/components/setting/BookmarkManage.vue
  git commit -m "feat(web): redesign BookmarkManage with duplicate review panel and LOADING batch injection"
  ```

---

## 自查

**Spec 覆盖：**
- ✅ 导入前返回所有重复书签 → Task 3（preview 端点）+ Task 8（review 面板）
- ✅ 用户决定是否添加 → Task 8（checkedUrls 状态 + startImport 计算 skipUrls）
- ✅ 空文件夹/单书签文件夹去掉 → Task 4（FolderSlice 裁剪逻辑）
- ✅ 导入后全部 LOADING 状态 → Task 4（返回 LOADING VO）+ Task 7（addImportLoadingBatch）+ Task 8（phase=importing 时注入 store）
- ✅ WS 更新通知前端 → 已存在的 `replaceContent` 无需改动（已保留 parentId）

**类型一致性：**
- `BookmarkImportPreviewVO` / `BookmarkImportItemVO`：Task 1（后端）/ Task 5（前端）定义，Task 3/6/8 消费，字段名一致
- `UserLayoutNodeVO`：后端 Task 4 返回，前端 Task 6 (`UserLayoutNodeVO[]`) 接收，Task 7 / Task 8 消费
- `addImportLoadingBatch(nodes: UserLayoutNodeVO[])` ：Task 7 定义，Task 8 调用，参数类型一致
- `bookmarksUpload(file: File, skipUrls: string[])` ：Task 6 定义，Task 8 调用，参数一致

**无占位符扫描：** 所有步骤均含完整代码，无 TBD/TODO。
