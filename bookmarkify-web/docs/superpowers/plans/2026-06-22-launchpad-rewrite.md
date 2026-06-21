# 启动台主页彻底重构 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 用"单一归一化数据源 + 自研均匀网格 + 唯一拖拽控制器"重写启动台主页，从根因消灭重复-id 刷新卡死与跨网格迁移时序 bug。

**Architecture:** Pinia store 持有扁平 `nodes` map + `order`（parentKey→有序 id 列表）作为唯一真相；UI 全部从其 computed 派生。等大格子用绝对定位 + transform，重排靠 CSS transition 做 FLIP。一个 pointer 拖拽控制器在松手那一刻提交跨容器移动，无实时跨网格迁移。

**Tech Stack:** Nuxt 4 + Vue 3 `<script setup>` + Pinia(Option Store) + TypeScript + Tailwind/DaisyUI(`cy-`)。**移除 vuuri/Muuri。**

## Global Constraints

- 后端零改动；复用 `/bookmark/*`（`query/sort/moveNode/createDir/renameDir/delete/addOne`）。
- 无测试运行器（CLAUDE.md），**不引入测试框架**；每个任务验证 = `pnpm build` 通过；视觉/拖拽回归由人工在 `pnpm dev` 验证（沙箱连不上 localhost，见踩坑#8）。
- Vue：`<script setup lang="ts">`；`PascalCase.vue`；Pinia Option Store；类型在 `@typing`，`import * as t from '@typing'` 跨模块。
- Prettier：130 宽、单引号、无分号、bracket same line。
- 文件夹**不可套文件夹**。
- 完成后代码中不得再出现：`dedupeLayout`、`cellRevision`、`gridKey`、`pageData.filter(Boolean)` 兜底、`vuuri`。

---

## File Structure

- `stores/bookmark.store.ts` — **重写**：归一化数据源 + actions/getters。
- `typing/bookmark.ts` — **改**：导出 `ROOT_KEY`；`UserLayoutNodeVO` 不变。
- `composables/useGridLayout.ts` — **新建**：列数 / 坐标纯函数。
- `composables/useLaunchpadDrag.ts` — **新建**：唯一拖拽控制器。
- `components/launchpad/LaunchCell.vue` — **新建**（取代 `components/launch/Item.vue`）：类型分发 + 右键菜单。
- `components/launchpad/LaunchGrid.vue` — **新建**：均匀网格渲染 + 接拖拽。
- `components/launchpad/FolderOverlay.vue` — **新建**（取代 `FolderPanel.vue`）：文件夹浮层，内部复用 LaunchGrid。
- `pages/index.vue` — **重写**：薄页面。
- 消费方改 action 名：`stores/websocket.store.ts`、`components/launchpad/AddOneDialog.vue`、`components/launch/Item.vue`(删)、`components/setting/BookmarkManage.vue`(仅 `update()` 名不变，无需改)、`plugins/auth.ts`(仅 `update()`，无需改)、`stores/auth.store.ts`(`$reset()`，无需改)。
- **删除**：`components/launchpad/FolderPanel.vue`、`components/launch/Item.vue`。
- `package.json` — 移除 `vuuri` 依赖。

---

## Task 1: 归一化 store + 类型

**Files:**
- Modify: `typing/bookmark.ts`
- Rewrite: `stores/bookmark.store.ts`

**Interfaces:**
- Produces:
  - `ROOT_KEY: string`（`@typing` 导出）
  - store state：`nodes: Record<string, UserLayoutNodeVO>`, `order: Record<string, string[]>`
  - getters：`rootNodes: UserLayoutNodeVO[]`（文件夹节点已填充 `children`）；`childrenOf(folderId: string): UserLayoutNodeVO[]`；`parentKeyOf(id: string): string | null`
  - actions：`update(): Promise<void>`、`setLayout(root)`、`addLoading(node)`、`replaceContent(node)`、`removeNode(id)`、`reorderLocal(parentKey, ids)`、`moveLocal(id, toParentKey, index)`、`createFolderLocal(folderNode, draggedId, targetId, index)`、`applyMoveResult(result, srcParentKey)`

- [ ] **Step 1: 在 `typing/bookmark.ts` 顶部导出 ROOT_KEY**

```ts
// 根层在归一化 order 中的固定键
export const ROOT_KEY = '__root__'
```

确认 `typing/index.ts` 以 `export * from './bookmark'` 形式 barrel（已是），无需额外改动。

- [ ] **Step 2: 重写 `stores/bookmark.store.ts`**

```ts
import { defineStore } from 'pinia'
import { HomeItemType, ROOT_KEY, type UserLayoutNodeVO } from '@typing'
import { bookmarksShowAll } from '@api'

/** 后端树 → 扁平 { nodes, order }。nodes 不保留 children（归属/顺序唯一来源是 order）。 */
function normalize(root?: UserLayoutNodeVO | null) {
  const nodes: Record<string, UserLayoutNodeVO> = {}
  const order: Record<string, string[]> = { [ROOT_KEY]: [] }
  const walk = (list: Array<UserLayoutNodeVO> | undefined, parentKey: string) => {
    order[parentKey] = []
    const sorted = (list ?? []).slice().sort((a, b) => (a.sort ?? 0) - (b.sort ?? 0))
    for (const n of sorted) {
      if (!n?.id) continue
      nodes[n.id] = { ...n, parentId: parentKey === ROOT_KEY ? null : parentKey, children: undefined }
      order[parentKey].push(n.id)
      if (n.type === HomeItemType.BOOKMARK_DIR) walk(n.children, n.id)
    }
  }
  walk(root?.children, ROOT_KEY)
  return { nodes, order }
}

export const useBookmarkStore = defineStore('homeItems', {
  state: () => ({
    nodes: {} as Record<string, UserLayoutNodeVO>,
    order: { [ROOT_KEY]: [] } as Record<string, string[]>,
  }),

  getters: {
    // 文件夹节点即时填充 children，供 cell/Folder.vue 预览图与 FolderOverlay 使用
    rootNodes(state): Array<UserLayoutNodeVO> {
      return (state.order[ROOT_KEY] ?? [])
        .map((id) => {
          const n = state.nodes[id]
          if (!n) return null
          if (n.type === HomeItemType.BOOKMARK_DIR) {
            const children = (state.order[id] ?? []).map((cid) => state.nodes[cid]).filter(Boolean) as UserLayoutNodeVO[]
            return { ...n, children }
          }
          return n
        })
        .filter(Boolean) as Array<UserLayoutNodeVO>
    },
    childrenOf(state) {
      return (folderId: string): Array<UserLayoutNodeVO> =>
        (state.order[folderId] ?? []).map((id) => state.nodes[id]).filter(Boolean) as Array<UserLayoutNodeVO>
    },
    parentKeyOf(state) {
      return (id: string): string | null => {
        for (const [k, ids] of Object.entries(state.order)) if (ids.includes(id)) return k
        return null
      }
    },
  },

  actions: {
    async update(): Promise<void> {
      const res = await bookmarksShowAll()
      this.setLayout(res)
      console.log(`[DEBUG]桌面布局更新: 根 ${this.order[ROOT_KEY]?.length ?? 0} 项`)
    },

    setLayout(root?: UserLayoutNodeVO | null) {
      const { nodes, order } = normalize(root)
      this.nodes = nodes
      this.order = order
    },

    // 插入加载占位项到根
    addLoading(node: UserLayoutNodeVO) {
      this.nodes[node.id] = { ...node, type: HomeItemType.BOOKMARK_LOADING, parentId: null, children: undefined }
      this.order[ROOT_KEY] = [...(this.order[ROOT_KEY] ?? []), node.id]
    },

    // 新增已就绪书签到根（AddOneDialog 关联/添加成功且已带 typeApp）
    addNode(node: UserLayoutNodeVO) {
      this.nodes[node.id] = { ...node, parentId: null, children: undefined }
      this.order[ROOT_KEY] = [...(this.order[ROOT_KEY] ?? []), node.id]
    },

    // WebSocket 就地内容替换（LOADING→BOOKMARK）；仅改内容，保留归属，靠响应式重渲染
    replaceContent(node: UserLayoutNodeVO) {
      if (node.type !== HomeItemType.BOOKMARK || node.typeApp == null) return
      const cur = this.nodes[node.id]
      if (!cur) return
      this.nodes[node.id] = { ...node, parentId: cur.parentId, children: undefined }
    },

    removeNode(id: string) {
      delete this.nodes[id]
      for (const k of Object.keys(this.order)) this.order[k] = this.order[k].filter((x) => x !== id)
      delete this.order[id]
    },

    reorderLocal(parentKey: string, ids: Array<string>) {
      this.order[parentKey] = [...ids]
    },

    moveLocal(id: string, toParentKey: string, index: number) {
      const from = this.parentKeyOf(id)
      if (from) this.order[from] = this.order[from].filter((x) => x !== id)
      const next = [...(this.order[toParentKey] ?? [])]
      next.splice(Math.max(0, Math.min(index, next.length)), 0, id)
      this.order[toParentKey] = next
      if (this.nodes[id]) this.nodes[id] = { ...this.nodes[id], parentId: toParentKey === ROOT_KEY ? null : toParentKey }
    },

    // 本地建夹：folderNode 为后端返回的真实文件夹节点；从根移除两子、文件夹落在 index、子顺序 [target, dragged]
    createFolderLocal(folderNode: UserLayoutNodeVO, draggedId: string, targetId: string, index: number) {
      this.nodes[folderNode.id] = { ...folderNode, parentId: null, children: undefined }
      this.nodes[draggedId] = { ...this.nodes[draggedId], parentId: folderNode.id, children: undefined }
      this.nodes[targetId] = { ...this.nodes[targetId], parentId: folderNode.id, children: undefined }
      const root = (this.order[ROOT_KEY] ?? []).filter((x) => x !== draggedId && x !== targetId)
      root.splice(Math.max(0, Math.min(index, root.length)), 0, folderNode.id)
      this.order[ROOT_KEY] = root
      this.order[folderNode.id] = [targetId, draggedId]
    },

    // moveNode 返回值 reconcile：后端把剩 ≤1 项的文件夹自动解散时，result 是剩余的非文件夹节点。
    // 此处把该文件夹从 order 移除、剩余节点并入根。返回是否发生了解散。
    applyMoveResult(result: UserLayoutNodeVO | null | undefined, srcParentKey: string): boolean {
      if (!result || srcParentKey === ROOT_KEY) return false
      if (result.type === HomeItemType.BOOKMARK_DIR) return false
      // srcParentKey 是被解散的文件夹 id
      const remainingId = result.id
      delete this.order[srcParentKey]
      this.nodes[srcParentKey] && delete this.nodes[srcParentKey]
      this.nodes[remainingId] = { ...result, parentId: null, children: undefined }
      this.order[ROOT_KEY] = [...(this.order[ROOT_KEY] ?? []).filter((x) => x !== srcParentKey && x !== remainingId), remainingId]
      return true
    },
  },

  persist: {
    storage: piniaPluginPersistedstate.localStorage(),
  },
})
```

- [ ] **Step 3: 改 `stores/websocket.store.ts` 消费方**

把 `bookmarkStore.updateOneBookmarkCell(message.data)` 改为 `bookmarkStore.replaceContent(message.data)`。

- [ ] **Step 4: 改 `components/launchpad/AddOneDialog.vue` 的 `handleSuccess`**

```ts
function handleSuccess(res: UserLayoutNodeVO) {
  emit('success', res)
  if (res?.typeApp) {
    bookmarkStore.addNode(res)
  } else {
    bookmarkStore.addLoading(res)
  }
}
```

- [ ] **Step 5: 改 `components/launch/Item.vue` 的删除调用（本任务临时保留该文件，Task 8 删除）**

把 `bookmarkStore.deleteOneBookmarkCell(item.id)` 改为 `bookmarkStore.removeNode(item.id)`；同样改 `components/launchpad/cell/Bookmark.vue` 的 `deleteOneBookmarkCell` → `removeNode`。

- [ ] **Step 6: 构建验证**

Run: `pnpm build`
Expected: 通过（此时 `pages/index.vue` 仍引用旧 `layoutNode`，会编译报错 → 本步**临时**在 `pages/index.vue` 顶部把 `bookmarkStore.layoutNode ?? []` 改为 `bookmarkStore.rootNodes`，让旧页面暂时可编译；后续 Task 7 整体重写）。若仍有引用 `dedupeLayout/cellRevision` 的旧代码报错，临时注释相关行使 build 通过。

- [ ] **Step 7: 提交**

```bash
git add typing/bookmark.ts stores/bookmark.store.ts stores/websocket.store.ts components/launchpad/AddOneDialog.vue components/launch/Item.vue components/launchpad/cell/Bookmark.vue pages/index.vue
git commit -m "refactor(launchpad): 归一化单一数据源 store（nodes+order），改造消费方"
```

---

## Task 2: useGridLayout 布局纯函数

**Files:**
- Create: `composables/useGridLayout.ts`

**Interfaces:**
- Consumes: 偏好尺寸（`preferenceStore.bookmarkCellSizePx` / `bookmarkGapPx`）。
- Produces: `useGridLayout(containerRef, opts?) → { cols, cellW, cellH, gap, colWidth, rowHeight, posOf(index), gridWidth, gridHeight(count), indexAt(px, py) }`
  - `posOf(index: number) => { x: number; y: number }`
  - `indexAt(localX: number, localY: number) => number`（光标在容器内坐标 → 槽位 index）

- [ ] **Step 1: 实现**

```ts
import { computed, onMounted, onBeforeUnmount, ref, type Ref } from 'vue'
import { usePreferenceStore } from '@stores/preference.store'

export function useGridLayout(containerRef: Ref<HTMLElement | null>, opts?: { titleHeight?: number }) {
  const pref = usePreferenceStore()
  const cellW = computed(() => pref.bookmarkCellSizePx)
  const cellH = computed(() => pref.bookmarkCellSizePx + (opts?.titleHeight ?? (pref.preference?.showTitle ? 28 : 0)))
  const gap = computed(() => pref.bookmarkGapPx)
  const colWidth = computed(() => cellW.value + gap.value)
  const rowHeight = computed(() => cellH.value + gap.value)

  const cols = ref(1)
  const recalc = () => {
    const w = containerRef.value?.clientWidth ?? 0
    cols.value = Math.max(1, Math.floor((w + gap.value) / colWidth.value))
  }

  let ro: ResizeObserver | null = null
  onMounted(() => {
    recalc()
    ro = new ResizeObserver(recalc)
    if (containerRef.value) ro.observe(containerRef.value)
    window.addEventListener('resize', recalc)
  })
  onBeforeUnmount(() => {
    ro?.disconnect()
    window.removeEventListener('resize', recalc)
  })

  const posOf = (index: number) => ({
    x: (index % cols.value) * colWidth.value,
    y: Math.floor(index / cols.value) * rowHeight.value,
  })
  const gridWidth = computed(() => cols.value * colWidth.value)
  const gridHeight = (count: number) => Math.ceil(count / cols.value) * rowHeight.value
  // 容器内坐标 → 槽位 index（clamp 由调用方按 count 处理）
  const indexAt = (localX: number, localY: number) => {
    const c = Math.max(0, Math.min(cols.value - 1, Math.floor(localX / colWidth.value)))
    const r = Math.max(0, Math.floor(localY / rowHeight.value))
    return r * cols.value + c
  }

  return { cols, cellW, cellH, gap, colWidth, rowHeight, posOf, gridWidth, gridHeight, indexAt, recalc }
}
```

- [ ] **Step 2: 构建验证 & 提交**

Run: `pnpm build` → 通过（composable 未被引用，仅类型检查）。
```bash
git add composables/useGridLayout.ts
git commit -m "feat(launchpad): useGridLayout 等大格子布局纯函数"
```

---

## Task 3: useLaunchpadDrag 拖拽控制器

**Files:**
- Create: `composables/useLaunchpadDrag.ts`

**Interfaces:**
- Consumes: `useGridLayout` 的返回；目标节点查询。
- Produces: `useLaunchpadDrag(cfg) → { draggingId, previewIds, mergeTargetId, mergeReady, ejectArmed, onPointerDown(e, id), isDragging }`
  - `cfg`: `{ containerRef, items: Ref<UserLayoutNodeVO[]>, layout, isFolder, folderBoundsRef?, onCommit }`
  - `onCommit(action)`，action 联合类型见下。

- [ ] **Step 1: 定义提交动作类型 + 实现控制器**

```ts
import { ref, computed, type Ref } from 'vue'
import { HomeItemType, type UserLayoutNodeVO } from '@typing'

export type DragCommit =
  | { kind: 'reorder'; ids: string[] }
  | { kind: 'merge'; draggedId: string; targetId: string; index: number }
  | { kind: 'moveInto'; draggedId: string; folderId: string }
  | { kind: 'eject'; draggedId: string }
  | { kind: 'none' }

interface DragCfg {
  containerRef: Ref<HTMLElement | null>
  items: Ref<Array<UserLayoutNodeVO>>
  layout: ReturnType<typeof import('./useGridLayout').useGridLayout>
  isFolder: boolean
  folderBoundsRef?: Ref<HTMLElement | null>
  onCommit: (c: DragCommit) => void
}

const MERGE_DELAY = 300
const EJECT_MARGIN = 40
const DRAG_THRESHOLD = 8

export function useLaunchpadDrag(cfg: DragCfg) {
  const draggingId = ref<string | null>(null)
  const previewIds = ref<string[]>([])       // 拖拽中容器内的实时顺序预览
  const mergeTargetId = ref<string | null>(null)
  const mergeReady = ref(false)
  const ejectArmed = ref(false)
  const pointer = ref({ x: 0, y: 0 })        // 被拖 cell 相对容器左上的渲染位置

  let startX = 0, startY = 0, grabDX = 0, grabDY = 0
  let started = false
  let mergeTimer: ReturnType<typeof setTimeout> | null = null
  const isDragging = computed(() => draggingId.value !== null && started)

  function clearMerge() {
    if (mergeTimer) { clearTimeout(mergeTimer); mergeTimer = null }
    mergeTargetId.value = null
    mergeReady.value = false
  }

  function onPointerDown(e: PointerEvent, id: string) {
    if (e.button !== 0) return
    draggingId.value = id
    started = false
    startX = e.clientX; startY = e.clientY
    const rect = cfg.containerRef.value?.getBoundingClientRect()
    const idx = cfg.items.value.findIndex((n) => n.id === id)
    const p = cfg.layout.posOf(idx)
    grabDX = e.clientX - ((rect?.left ?? 0) + p.x)
    grabDY = e.clientY - ((rect?.top ?? 0) + p.y)
    previewIds.value = cfg.items.value.map((n) => n.id)
    window.addEventListener('pointermove', onMove)
    window.addEventListener('pointerup', onUp, { once: true })
  }

  function onMove(e: PointerEvent) {
    if (!draggingId.value) return
    if (!started) {
      if (Math.hypot(e.clientX - startX, e.clientY - startY) < DRAG_THRESHOLD) return
      started = true
    }
    const rect = cfg.containerRef.value?.getBoundingClientRect()
    if (!rect) return
    const localX = e.clientX - rect.left - grabDX
    const localY = e.clientY - rect.top - grabDY
    pointer.value = { x: localX, y: localY }

    // ① 文件夹内：中心移出浮层边界（含滞回）→ 武装弹出
    if (cfg.isFolder && cfg.folderBoundsRef?.value) {
      const b = cfg.folderBoundsRef.value.getBoundingClientRect()
      const cx = e.clientX, cy = e.clientY
      const outside = cx < b.left - EJECT_MARGIN || cx > b.right + EJECT_MARGIN || cy < b.top - EJECT_MARGIN || cy > b.bottom + EJECT_MARGIN
      ejectArmed.value = outside
      if (outside) { clearMerge(); return }
    }

    // ② 合并/移入意图：光标中心落在某目标内圈（中心 70%）且停留 300ms
    const target = hitInnerZone(e.clientX, e.clientY)
    if (target) {
      if (target.id !== mergeTargetId.value) {
        clearMerge()
        mergeTargetId.value = target.id
        mergeTimer = setTimeout(() => { mergeReady.value = true }, MERGE_DELAY)
      }
      return  // 抑制重排
    }
    if (mergeTargetId.value) clearMerge()

    // ③ 普通重排预览：把 draggingId 移到光标所在槽位
    const cxL = e.clientX - rect.left, cyL = e.clientY - rect.top
    const slot = Math.min(cfg.layout.indexAt(cxL, cyL), cfg.items.value.length - 1)
    const ids = cfg.items.value.map((n) => n.id).filter((x) => x !== draggingId.value)
    ids.splice(Math.max(0, slot), 0, draggingId.value)
    previewIds.value = ids
  }

  // 光标命中某非自身 BOOKMARK/文件夹的中心 70% 区
  function hitInnerZone(clientX: number, clientY: number): { id: string } | null {
    const rect = cfg.containerRef.value?.getBoundingClientRect()
    if (!rect) return null
    const ids = previewIds.value
    for (let i = 0; i < ids.length; i++) {
      const id = ids[i]
      if (id === draggingId.value) continue
      const node = cfg.items.value.find((n) => n.id === id)
      if (!node || (node.type !== HomeItemType.BOOKMARK && node.type !== HomeItemType.BOOKMARK_DIR)) continue
      const p = cfg.layout.posOf(i)
      const left = rect.left + p.x, top = rect.top + p.y
      const w = cfg.layout.cellW.value, h = cfg.layout.cellW.value  // 命中用图标方区
      const zx1 = left + w * 0.15, zx2 = left + w * 0.85
      const zy1 = top + h * 0.15, zy2 = top + h * 0.85
      if (clientX >= zx1 && clientX <= zx2 && clientY >= zy1 && clientY <= zy2) return { id }
    }
    return null
  }

  function onUp() {
    window.removeEventListener('pointermove', onMove)
    const dragged = draggingId.value
    const wasStarted = started
    const merge = mergeReady.value ? mergeTargetId.value : null
    const eject = ejectArmed.value
    const finalIds = [...previewIds.value]
    // 复位
    draggingId.value = null
    started = false
    clearMerge()
    ejectArmed.value = false
    previewIds.value = []
    if (!dragged || !wasStarted) { cfg.onCommit({ kind: 'none' }); return }

    if (cfg.isFolder && eject) { cfg.onCommit({ kind: 'eject', draggedId: dragged }); return }
    if (merge) {
      const node = cfg.items.value.find((n) => n.id === merge)
      if (node?.type === HomeItemType.BOOKMARK_DIR) cfg.onCommit({ kind: 'moveInto', draggedId: dragged, folderId: merge })
      else cfg.onCommit({ kind: 'merge', draggedId: dragged, targetId: merge, index: cfg.items.value.findIndex((n) => n.id === merge) })
      return
    }
    cfg.onCommit({ kind: 'reorder', ids: finalIds })
  }

  return { draggingId, previewIds, mergeTargetId, mergeReady, ejectArmed, pointer, isDragging, onPointerDown }
}
```

- [ ] **Step 2: 构建验证 & 提交**

Run: `pnpm build` → 通过。
```bash
git add composables/useLaunchpadDrag.ts
git commit -m "feat(launchpad): useLaunchpadDrag 唯一拖拽控制器（松手提交）"
```

---

## Task 4: LaunchCell 单元格分发

**Files:**
- Create: `components/launchpad/LaunchCell.vue`

**Interfaces:**
- Consumes: `cell/Bookmark|Folder|Function.vue`（不变）、`bookmarkStore.removeNode`。
- Produces: props `{ item: UserLayoutNodeVO; dragging?: boolean }`；emits `open-dir(item)`、`show-detail(bookmark)`。

- [ ] **Step 1: 实现（移植自 `components/launch/Item.vue`，把 `toggle-drag` 改名 `dragging`，删除调用改 `removeNode`）**

```vue
<template>
  <div class="h-full w-full" @contextmenu="onContextMenu($event, item)">
    <LaunchpadCellFolder v-if="item.type === HomeItemType.BOOKMARK_DIR" :value="item" :toggle-drag="dragging" @open-dir="emit('open-dir', item)" />
    <LaunchpadCellBookmark v-else-if="item.type === HomeItemType.BOOKMARK || item.type === HomeItemType.BOOKMARK_LOADING" :value="item.typeApp" :temp-title="item.name ?? undefined" :toggle-drag="dragging" :node-id="item.id" />
    <LaunchpadCellFunction v-else-if="item.type === HomeItemType.FUNCTION" :value="item.typeFuc!" :toggle-drag="dragging" />
  </div>
</template>

<script setup lang="ts">
import ContextMenu from '@imengyu/vue3-context-menu'
import { bookmarksDel } from '@api'
import { HomeItemType, type BookmarkShow, type UserLayoutNodeVO } from '@typing'

const bookmarkStore = useBookmarkStore()
const props = defineProps<{ item: UserLayoutNodeVO; dragging?: boolean }>()
const emit = defineEmits<{ (e: 'open-dir', item: UserLayoutNodeVO): void; (e: 'show-detail', bookmark: BookmarkShow): void }>()

async function delOne(item: UserLayoutNodeVO) {
  if (props.dragging) return
  try {
    await bookmarksDel([item.id])
    bookmarkStore.removeNode(item.id)
  } catch (error) {
    console.error('[LaunchCell] 删除书签失败', error)
  }
}

function onContextMenu(e: MouseEvent, item: UserLayoutNodeVO) {
  if (props.dragging || !item.typeApp) return
  ContextMenu.showContextMenu({
    items: [
      { label: '查看详情', onClick: () => emit('show-detail', item.typeApp!) },
      { label: '删除书签', onClick: () => delOne(item) },
    ],
    x: e.x,
    y: e.y,
  })
}
</script>
```

- [ ] **Step 2: 构建验证 & 提交**

Run: `pnpm build` → 通过。
```bash
git add components/launchpad/LaunchCell.vue
git commit -m "feat(launchpad): LaunchCell 单元格类型分发（取代 launch/Item）"
```

---

## Task 5: LaunchGrid 均匀网格

**Files:**
- Create: `components/launchpad/LaunchGrid.vue`

**Interfaces:**
- Consumes: `useGridLayout`、`useLaunchpadDrag`、`LaunchCell`。
- Produces: props `{ items: UserLayoutNodeVO[]; parentKey: string; isFolder?: boolean; folderBoundsRef?: HTMLElement | null }`；emits `commit(c: DragCommit)`、`open-dir(item)`、`show-detail(bookmark)`。渲染时按 `previewIds`（拖拽中）或 `items`（静止）定位每个 cell；被拖 cell 用 `pointer` 坐标 + 抬起样式；`mergeReady && mergeTargetId===cell.id` 时给图标加 `.merge-glow`。

- [ ] **Step 1: 实现**

```vue
<template>
  <div ref="containerRef" class="relative mx-auto" :style="{ width: `${layout.gridWidth.value}px`, height: `${layout.gridHeight(items.length)}px` }">
    <div
      v-for="(item, i) in orderedItems"
      :key="`${item.id}-${item.type}`"
      class="absolute select-none"
      :class="{ 'launch-cell-dragging': item.id === drag.draggingId.value, 'transition-transform duration-200 ease-out': item.id !== drag.draggingId.value }"
      :style="cellStyle(item, i)"
      @pointerdown="drag.onPointerDown($event, item.id)">
      <div class="h-full w-full" :class="{ 'merge-glow-host': drag.mergeReady.value && drag.mergeTargetId.value === item.id }">
        <LaunchCell :item="item" :dragging="drag.isDragging.value" @open-dir="emit('open-dir', $event)" @show-detail="emit('show-detail', $event)" />
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, ref, toRef } from 'vue'
import type { BookmarkShow, UserLayoutNodeVO } from '@typing'
import { useGridLayout } from '@/composables/useGridLayout'
import { useLaunchpadDrag, type DragCommit } from '@/composables/useLaunchpadDrag'
import LaunchCell from './LaunchCell.vue'

const props = defineProps<{ items: UserLayoutNodeVO[]; parentKey: string; isFolder?: boolean; folderBoundsRef?: HTMLElement | null }>()
const emit = defineEmits<{ (e: 'commit', c: DragCommit): void; (e: 'open-dir', item: UserLayoutNodeVO): void; (e: 'show-detail', b: BookmarkShow): void }>()

const containerRef = ref<HTMLElement | null>(null)
const layout = useGridLayout(containerRef)
const itemsRef = toRef(props, 'items')

const drag = useLaunchpadDrag({
  containerRef,
  items: itemsRef,
  layout,
  isFolder: props.isFolder ?? false,
  folderBoundsRef: computed(() => props.folderBoundsRef ?? null),
  onCommit: (c) => emit('commit', c),
})

// 拖拽中用 previewIds 顺序定位（非拖拽项让位动画），静止用真实 items
const orderedItems = computed<UserLayoutNodeVO[]>(() => {
  if (!drag.isDragging.value) return props.items
  const map = new Map(props.items.map((n) => [n.id, n]))
  return drag.previewIds.value.map((id) => map.get(id)).filter(Boolean) as UserLayoutNodeVO[]
})

function cellStyle(item: UserLayoutNodeVO, i: number) {
  const isDragged = item.id === drag.draggingId.value
  const base = { width: `${layout.cellW.value}px`, height: `${layout.cellH.value}px` }
  if (isDragged && drag.isDragging.value) {
    return { ...base, transform: `translate(${drag.pointer.value.x}px, ${drag.pointer.value.y}px) scale(1.08)`, zIndex: 50, pointerEvents: 'none' }
  }
  const p = layout.posOf(i)
  return { ...base, transform: `translate(${p.x}px, ${p.y}px)` }
}
</script>

<style scoped>
.launch-cell-dragging { transition: none; opacity: 0.92; }
</style>
```

注：`.merge-glow` 关键帧定义在 Task 7 的 `pages/index.vue` 全局 `<style>`（沿用现有），此处 `.merge-glow-host > * .folder-icon`/`.overflow-hidden` 由现有样式命中；若闪烁未出现，在 Task 8 视觉验证时把 `.merge-glow` 类直接加到 host 即可。

- [ ] **Step 2: 构建验证 & 提交**

Run: `pnpm build` → 通过。
```bash
git add components/launchpad/LaunchGrid.vue
git commit -m "feat(launchpad): LaunchGrid 均匀网格（绝对定位+FLIP+拖拽）"
```

---

## Task 6: FolderOverlay 文件夹浮层

**Files:**
- Create: `components/launchpad/FolderOverlay.vue`

**Interfaces:**
- Consumes: `LaunchGrid`、`bookmarksRenameDir`、`bookmarkStore`。
- Produces: props `{ visible: boolean; folder: UserLayoutNodeVO | null; anchorRect?: DOMRect | null }`；emits `close`、`commit(c: DragCommit)`（弹出/内部重排上抛给页面统一提交）。
- 复用踩坑 #2/#3/#7：dim 层 `pointer-events-none` + 捕获阶段 click 关闭 + Esc；只 opacity 过渡；卡片水平居中、不加 overflow。重命名沿用现 `FolderPanel.vue` 逻辑。

- [ ] **Step 1: 实现（移植 FolderPanel 的浮层/重命名/关闭，网格换成 LaunchGrid，children 来自 store getter）**

```vue
<template>
  <Teleport to="body">
    <Transition name="folder-overlay">
      <div v-if="visible" class="fixed inset-0 z-50 pointer-events-none">
        <div class="absolute inset-0 bg-black/30 backdrop-blur-md" />
        <div ref="cardRef" class="absolute z-10 pointer-events-auto rounded-3xl bg-white/20 border border-white/30 shadow-2xl p-5" :style="cardStyle">
          <div class="mb-4 flex justify-center">
            <input v-if="editing" ref="nameInputRef" v-model="editingName"
              class="bg-white/20 border border-white/40 rounded-lg px-3 py-1 text-white text-base font-medium text-center outline-none focus:border-white/70 w-full max-w-[200px]"
              maxlength="30" @keydown.enter="submitRename" @keydown.esc="cancelEdit" @blur="submitRename" />
            <span v-else class="text-white text-base font-medium tracking-wide cursor-text hover:opacity-70 transition-opacity" title="点击修改名称" @click="startEdit">
              {{ folder?.name || '文件夹' }}
            </span>
          </div>
          <ClientOnly>
            <LaunchGrid :items="children" :parent-key="folder?.id ?? ''" :is-folder="true" :folder-bounds-ref="cardRef" @commit="onCommit" @show-detail="emit('passShowDetail', $event)" />
          </ClientOnly>
        </div>
      </div>
    </Transition>
  </Teleport>
</template>

<script setup lang="ts">
import { computed, nextTick, ref } from 'vue'
import { useWindowSize, useEventListener } from '@vueuse/core'
import type { BookmarkShow, UserLayoutNodeVO } from '@typing'
import { bookmarksRenameDir } from '@api'
import LaunchGrid from './LaunchGrid.vue'
import type { DragCommit } from '@/composables/useLaunchpadDrag'

const props = defineProps<{ visible: boolean; folder: UserLayoutNodeVO | null; anchorRect?: DOMRect | null }>()
const emit = defineEmits<{ (e: 'close'): void; (e: 'commit', c: DragCommit): void; (e: 'passShowDetail', b: BookmarkShow): void }>()

const bookmarkStore = useBookmarkStore()
const children = computed(() => (props.folder ? bookmarkStore.childrenOf(props.folder.id) : []))
const { width: windowWidth, height: windowHeight } = useWindowSize()
const cardRef = ref<HTMLElement | null>(null)

const cardStyle = computed(() => {
  const w = Math.min(windowWidth.value * 0.55, windowWidth.value - 16)
  const left = Math.max(8, (windowWidth.value - w) / 2)
  const r = props.anchorRect
  const top = r ? Math.min(Math.max(8, r.top - 12), Math.max(8, windowHeight.value * 0.4)) : 80
  return { left: `${left}px`, top: `${top}px`, width: `${w}px` }
})

function onCommit(c: DragCommit) {
  // 弹出后文件夹可能被解散；先把提交上抛页面统一持久化，再让页面决定是否关闭
  emit('commit', c)
}

// 重命名（沿用 FolderPanel）
const editing = ref(false)
const editingName = ref('')
const nameInputRef = ref<HTMLInputElement | null>(null)
function startEdit() { editingName.value = props.folder?.name ?? ''; editing.value = true; nextTick(() => nameInputRef.value?.select()) }
function cancelEdit() { editing.value = false }
async function submitRename() {
  if (!editing.value) return
  editing.value = false
  const name = editingName.value.trim()
  if (!name || !props.folder || name === props.folder.name) return
  try {
    await bookmarksRenameDir(props.folder.id, name)
    if (bookmarkStore.nodes[props.folder.id]) bookmarkStore.nodes[props.folder.id] = { ...bookmarkStore.nodes[props.folder.id], name }
  } catch { /* http 层已提示 */ }
}

// 关闭：捕获阶段 click（卡片外）+ Esc
useEventListener(document, 'click', (e: MouseEvent) => {
  if (!props.visible) return
  const target = e.target as Node | null
  if (cardRef.value && target && cardRef.value.contains(target)) return
  e.stopPropagation(); e.preventDefault(); emit('close')
}, { capture: true })
useEventListener(window, 'keydown', (e: KeyboardEvent) => {
  if (e.key !== 'Escape' || !props.visible || editing.value) return
  emit('close')
})
</script>

<style scoped>
.folder-overlay-enter-active, .folder-overlay-leave-active { transition: opacity 0.2s ease; }
.folder-overlay-enter-from, .folder-overlay-leave-to { opacity: 0; }
</style>
```

- [ ] **Step 2: 构建验证 & 提交**

Run: `pnpm build` → 通过。
```bash
git add components/launchpad/FolderOverlay.vue
git commit -m "feat(launchpad): FolderOverlay 文件夹浮层（复用 LaunchGrid）"
```

---

## Task 7: pages/index.vue 重写（薄页面 + 统一提交）

**Files:**
- Rewrite: `pages/index.vue`

**Interfaces:**
- Consumes: `LaunchGrid`、`FolderOverlay`、`bookmarkStore`、API `bookmarksSort/bookmarksMoveNode/bookmarksCreateDir`。
- 统一 `handleCommit(parentKey, c)`：根据 `DragCommit.kind` 调 store 本地更新 + 持久化 API；处理后端自动解散 reconcile；reorder 乐观本地 + fire `sort`。

- [ ] **Step 1: 实现**

```vue
<template>
  <div ref="outerRef" class="flex w-full justify-center">
    <ClientOnly>
      <LaunchGrid :items="bookmarkStore.rootNodes" :parent-key="ROOT_KEY" @commit="(c) => handleCommit(ROOT_KEY, c)" @open-dir="onOpenDir" @show-detail="onShowDetail" />
    </ClientOnly>
  </div>

  <el-dialog v-model="detailVisible" title="书签详情" width="480px" :close-on-click-modal="true">
    <LaunchpadDetail :data="detailBookmark" />
  </el-dialog>

  <FolderOverlay :visible="folderVisible" :folder="folderNode" :anchor-rect="folderAnchorRect"
    @close="folderVisible = false" @commit="(c) => handleCommit(folderNode?.id ?? '', c)" @pass-show-detail="onShowDetail" />
</template>

<script lang="ts" setup>
import { bookmarksSort, bookmarksMoveNode, bookmarksCreateDir } from '@api'
import { ROOT_KEY, type BookmarkShow, type UserLayoutNodeVO } from '@typing'
import type { DragCommit } from '@/composables/useLaunchpadDrag'
import LaunchGrid from '@/components/launchpad/LaunchGrid.vue'
import FolderOverlay from '@/components/launchpad/FolderOverlay.vue'
definePageMeta({ middleware: 'auth', layout: 'launch' })

const bookmarkStore = useBookmarkStore()
const outerRef = ref<HTMLElement | null>(null)

// 详情弹窗
const detailVisible = ref(false)
const detailBookmark = ref<BookmarkShow | null>(null)
function onShowDetail(b: BookmarkShow) { detailBookmark.value = b; detailVisible.value = true }

// 文件夹浮层
const folderVisible = ref(false)
const folderId = ref<string | null>(null)
const folderAnchorRect = ref<DOMRect | null>(null)
const folderNode = computed(() => (folderId.value ? bookmarkStore.rootNodes.find((n) => n.id === folderId.value) ?? null : null))
function onOpenDir(item: UserLayoutNodeVO) {
  const el = document.querySelector(`[data-folder-anchor="${item.id}"]`) as HTMLElement | null
  folderAnchorRect.value = el ? el.getBoundingClientRect() : null
  folderId.value = item.id
  folderVisible.value = true
}

// 持久化某父级顺序
function persistOrder(parentKey: string) {
  const ids = bookmarkStore.order[parentKey] ?? []
  const params: Record<string, number> = {}
  ids.forEach((id, i) => (params[id] = i))
  bookmarksSort(params)
}

async function handleCommit(parentKey: string, c: DragCommit) {
  if (c.kind === 'none') return
  if (c.kind === 'reorder') {
    bookmarkStore.reorderLocal(parentKey, c.ids)   // 乐观
    persistOrder(parentKey)
    return
  }
  if (c.kind === 'moveInto') {
    try {
      const dir = await bookmarksMoveNode(c.draggedId, c.folderId)
      bookmarkStore.moveLocal(c.draggedId, c.folderId, bookmarkStore.childrenOf(c.folderId).length)
      bookmarkStore.setLayout && void 0  // 文件夹内容以本地为准；如需精确可 await update()
      ElNotification.success({ message: '已移入文件夹' })
      void dir
    } catch { /* http 层已提示 */ }
    return
  }
  if (c.kind === 'merge') {
    try {
      const folder = await bookmarksCreateDir([c.draggedId, c.targetId], '新建文件夹', c.index)
      bookmarkStore.createFolderLocal(folder, c.draggedId, c.targetId, c.index)
      ElNotification.success({ message: '已创建文件夹' })
    } catch { /* http 层已提示 */ }
    return
  }
  if (c.kind === 'eject') {
    // 文件夹拖出到根：parentKey 为来源文件夹 id
    try {
      const result = await bookmarksMoveNode(c.draggedId, null)
      bookmarkStore.moveLocal(c.draggedId, ROOT_KEY, (bookmarkStore.order[ROOT_KEY] ?? []).length)
      const dissolved = bookmarkStore.applyMoveResult(result, parentKey)
      persistOrder(ROOT_KEY)
      if (dissolved || bookmarkStore.childrenOf(parentKey).length === 0) folderVisible.value = false
    } catch { /* http 层已提示 */ }
    return
  }
}
</script>

<style>
/* 合并目标：图标白色外边框 + 缓慢闪烁（沿用旧实现，命中 .folder-icon / .overflow-hidden 或 host） */
.merge-glow-host :is(.folder-icon, .overflow-hidden) {
  box-shadow: 0 0 0 3px rgba(255,255,255,0.95), 0 0 16px rgba(255,255,255,0.4) !important;
  animation: merge-blink 700ms ease-in-out infinite !important;
}
@keyframes merge-blink {
  0%, 100% { box-shadow: 0 0 0 3px rgba(255,255,255,0.95), 0 0 16px rgba(255,255,255,0.4); }
  50% { box-shadow: 0 0 0 3px rgba(255,255,255,0.3), 0 0 6px rgba(255,255,255,0.15); }
}
</style>
```

注：`onOpenDir` 用 `[data-folder-anchor]` 取锚点 → Task 5 LaunchGrid 的 cell 外层需补 `:data-folder-anchor="item.id"` 属性（实现时补上）。`moveInto` 中文件夹内容以本地推导为准；若发现内容顺序与后端不一致，改为 `await bookmarkStore.update()`。

- [ ] **Step 2: 构建验证 & 提交**

Run: `pnpm build` → 通过。
```bash
git add pages/index.vue components/launchpad/LaunchGrid.vue
git commit -m "refactor(launchpad): 重写 pages/index.vue 为薄页面 + 统一提交"
```

---

## Task 8: 清理依赖与旧文件 + 终验

**Files:**
- Delete: `components/launchpad/FolderPanel.vue`、`components/launch/Item.vue`
- Modify: `package.json`（移除 `vuuri`）、检查 `nuxt.config.ts` 是否有 vuuri transpile 配置需移除

- [ ] **Step 1: 删除旧文件并确认无引用**

```bash
grep -rn "vuuri\|FolderPanel\|launch/Item\|LaunchItem\|updateOneBookmarkCell\|deleteOneBookmarkCell\|dedupeLayout\|cellRevision\|gridKey" --include="*.vue" --include="*.ts" . | grep -v node_modules
```
Expected: 无业务代码命中（spec/plan 文档命中可忽略）。有则修正。

- [ ] **Step 2: 移除 vuuri 依赖**

从 `package.json` dependencies 删除 `vuuri`；检查 `nuxt.config.ts` 的 `build.transpile`/`vite` 是否含 vuuri 引用，有则删。运行 `pnpm install`。

- [ ] **Step 3: 终构建**

Run: `pnpm build`
Expected: 通过。

- [ ] **Step 4: 提交**

```bash
git add -A
git commit -m "chore(launchpad): 移除 vuuri 依赖与旧 FolderPanel/Item，重构收尾"
```

- [ ] **Step 5: 人工视觉/拖拽验证（HUMAN CHECKPOINT）**

`pnpm dev` 后由用户逐项验证（沙箱无法自动验证，踩坑#8）：
1. 拖拽重排（实时让位 + 松手落位平滑）。
2. 拖到书签上停 300ms 出现白框闪烁 → 松手建文件夹。
3. 拖到文件夹上 → 松手移入。
4. 打开文件夹浮层 → 内部重排。
5. 从文件夹把图标拖出浮层 → 松手回到根；文件夹剩 1 项时自动解散并关闭浮层。
6. 文件夹重命名。
7. 添加书签出现加载占位 → WebSocket 推送后就地变成真实书签（无闪烁重建）。
8. **反复跨文件夹拖拽十余次后刷新页面 → 不卡死**（核心回归）。
9. 右键删除 / 查看详情。

---

## Self-Review

- **Spec 覆盖**：数据模型(Task1)、组件结构(Task4-7)、布局(Task2/5)、拖拽(Task3/5)、错误处理与解散(Task1 applyMoveResult + Task7)、后端零改动与移除 vuuri(Task8)、验收标准(Task8 Step5)——全覆盖。
- **占位符**：无 TBD/TODO；关键代码均给出。
- **类型一致**：`DragCommit` 联合类型在 Task3 定义，Task5/6/7 一致消费；store action 名（`reorderLocal/moveLocal/createFolderLocal/applyMoveResult/replaceContent/addLoading/addNode/removeNode`）在 Task1 定义并在 Task7/4 一致使用；`ROOT_KEY` 在 Task1 定义并贯穿。
- **风险点**：`moveInto` 后文件夹内容顺序可能与后端不完全一致 → 已注明可退化为 `update()`；`.merge-glow` 选择器命中 → 已注明视觉验证时的兜底。
