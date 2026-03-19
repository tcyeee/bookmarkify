# Bookmarkify API

Base URL: `http://localhost:7001`

All responses are wrapped in a unified envelope:

```ts
interface ApiResponse<T> {
  ok: boolean
  code: number
  data: T
  msg: string | null
}
```

Authentication is handled via Sa-Token session cookie. All `/bookmark/**` endpoints require a valid user session.

---

## Types

```ts
type NodeType = 'BOOKMARK' | 'BOOKMARK_LOADING' | 'BOOKMARK_DIR' | 'FUNCTION'

interface BookmarkShow {
  bookmarkId: string | null
  bookmarkUserLinkId: string | null
  title: string | null
  description: string | null
  urlFull: string | null
  urlBase: string | null
  iconBase64: string | null
  iconHdUrl: string | null
  isActivity: boolean | null
}

interface BookmarkFunctionVO {
  id: string
  layoutNodeId: string
  type: string
}

interface UserLayoutNodeVO {
  id: string
  parentId: string | null
  sort: number
  type: NodeType
  name: string | null
  typeApp: BookmarkShow | null       // 当 type === 'BOOKMARK' 时有值
  typeFuc: BookmarkFunctionVO | null // 当 type === 'FUNCTION' 时有值
  children: UserLayoutNodeVO[]       // 当 type === 'BOOKMARK_DIR' 时包含子节点
}
```

---

## 文件夹

### 创建文件夹

将两个已有书签节点合并到一个新文件夹中。

- **Method:** `POST`
- **Path:** `/bookmark/createDir`
- **Auth:** 需要登录

**Request Body**

```ts
interface CreateDirParams {
  /** 要放入文件夹的两个书签节点 ID */
  nodeIds: [string, string]
  /** 文件夹名称 */
  name: string
  /** 文件夹在桌面上的排序值，数值越小越靠前 */
  sort: number
}
```

**Response** `ApiResponse<UserLayoutNodeVO>`

返回新建的文件夹节点，`type` 为 `'BOOKMARK_DIR'`，`children` 包含传入的两个书签节点（含完整书签数据）。

同时会通过 WebSocket 推送 `HOME_ITEM_UPDATE` 事件，payload 与响应体中的 `data` 相同。

**Example**

```ts
const res = await fetch('/bookmark/createDir', {
  method: 'POST',
  headers: { 'Content-Type': 'application/json' },
  body: JSON.stringify({
    nodeIds: ['node-uuid-1', 'node-uuid-2'],
    name: '常用工具',
    sort: 0,
  }),
})
const { data } = await res.json() // data: UserLayoutNodeVO
```

---

### 修改文件夹名称

- **Method:** `POST`
- **Path:** `/bookmark/renameDir`
- **Auth:** 需要登录

**Request Body**

```ts
interface RenameDirParams {
  /** 文件夹节点 ID（type 必须为 BOOKMARK_DIR） */
  nodeId: string
  /** 新名称 */
  name: string
}
```

**Response** `ApiResponse<boolean>`

`true` 表示更新成功，`false` 表示节点不存在或不属于当前用户。

**Example**

```ts
const res = await fetch('/bookmark/renameDir', {
  method: 'POST',
  headers: { 'Content-Type': 'application/json' },
  body: JSON.stringify({
    nodeId: 'dir-node-uuid',
    name: '工作',
  }),
})
const { data } = await res.json() // data: boolean
```
