# 文件夹跨网格拖拽（macOS 启动台式）实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 把文件夹从全屏模态改成原位浮层卡片，并用 vuuri 内置 group 实现"文件夹内图标拖出到主网格精确位置"与"主网格图标拖入打开的文件夹"。

**Architecture:** 复用 vuuri 0.4.6 内置的多网格分组拖拽（`group-id` → `dragSort=()=>同组网格` + `send`/`receive` 自动增删 modelValue）。主网格与文件夹卡片设同一 group-id，跨网格移动后用 `moveNode`(改归属) + `sort`(重排目标列表) 两步持久化。

**Tech Stack:** Nuxt 4 + Vue 3 `<script setup>`、Pinia（Option Store）、vuuri 0.4.6（内含 Muuri 0.9.5）、Tailwind 4 + DaisyUI(`cy-`)、Element Plus（`ElNotification`）。

设计文档：`docs/superpowers/specs/2026-06-21-folder-cross-grid-drag-design.md`

## Global Constraints

- **无自动化测试框架**：项目无 test runner / lint / typecheck 脚本，无测试。每个任务的"验证"= `pnpm dev`（http://localhost:3000，需后端 :7001）下浏览器手动实测，必要时用 claude-in-chrome 辅助截图/控制台。
- **Vue 规范**：`<script setup lang="ts">`；组件 `PascalCase.vue`；Pinia 用 **Option Store**；WebSocket store 不持久化。
- **样式**：DaisyUI 前缀 `cy-`；类组合用 `cn()`（`@utils`）；Toast 用 `ElMessage`/`ElNotification`。
- **Prettier**：130 列宽、单引号、无分号、bracket same line。
- **注释/文案中文**（简体），延续仓库风格。
- **响应式约定**：写回 `bookmarkStore.layoutNode` 必须用新对象引用替换节点（参考 `stores/bookmark.store.ts` 的 `updateOneBookmarkCell`）。
- **错误提示**：HTTP 错误由 `server/apis/http.ts` 统一 toast，组件内不要重复弹错误 toast。
- **提交**：每个任务结束 `git add <本任务涉及文件>` 后提交；commit message 末尾加
  `Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>`。
  工作区当前有用户进行中的改动（`AccountProfile.vue`、`auth.store.ts` 等），**只 add 本任务涉及的文件，绝不 `git add -A`**。

---

### Task 1: Spike —— 验证 vuuri group 跨网格拖拽 + dragContainer 坐标

**目的：** 在写正式改造前，确认两个相邻 Vuuri 网格设同一 `group-id` 时，跨网格拖拽能正常触发 `send`/`receive` 且被拖元素坐标不错乱（尤其在浮层/blur 结构下）。这是设计文档 §5 的头号风险。

**Files:**
- Create: `pages/spike-cross-grid.vue`（临时验证页，任务末尾删除）

**Interfaces:**
- Consumes: vuuri 的 `group-id` prop、`@send`/`@receive`/`@input` 事件。
- Produces: 结论——group 跨网格在本项目可行（进入 Task 2）或需绕过封装（回设计文档调整）。

- [ ] **Step 1: 写验证页**

创建 `pages/spike-cross-grid.vue`：

```vue
<template>
  <div class="flex gap-10 p-10">
    <ClientOnly>
      <!-- 网格 A：模拟主网格 -->
      <Vuuri group-id="spike" class="grid-a border border-red-400 min-h-[300px] w-[320px]" :model-value="listA" item-key="id"
        :options="opts" :drag-enabled="true" :get-item-width="() => '80px'" :get-item-height="() => '80px'"
        @input="listA = $event" @send="onSend('A', $event)" @receive="onReceive('A', $event)">
        <template #item="{ item }"><div class="size-[72px] rounded-xl bg-red-300 flex items-center justify-center">{{ item.id }}</div></template>
      </Vuuri>
      <!-- 网格 B：模拟浮层卡片，故意套一层 backdrop-blur 还原真实风险 -->
      <div class="rounded-2xl bg-white/20 backdrop-blur-xl p-4">
        <Vuuri group-id="spike" class="grid-b border border-blue-400 min-h-[300px] w-[320px]" :model-value="listB" item-key="id"
          :options="opts" :drag-enabled="true" :get-item-width="() => '80px'" :get-item-height="() => '80px'"
          @input="listB = $event" @send="onSend('B', $event)" @receive="onReceive('B', $event)">
          <template #item="{ item }"><div class="size-[72px] rounded-xl bg-blue-300 flex items-center justify-center">{{ item.id }}</div></template>
        </Vuuri>
      </div>
    </ClientOnly>
  </div>
</template>

<script lang="ts" setup>
const Vuuri = import.meta.client
  ? defineAsyncComponent(() => import('vuuri'))
  : defineComponent({ name: 'VuuriPlaceholder', setup: () => () => null })
const opts = { layout: { fillGaps: true }, dragStartPredicate: { distance: 8, delay: 0 } }
const listA = ref([{ id: 'a1' }, { id: 'a2' }, { id: 'a3' }])
const listB = ref([{ id: 'b1' }, { id: 'b2' }])
function onSend(grid: string, e: any) { console.log('[SPIKE] send from', grid, e) }
function onReceive(grid: string, e: any) { console.log('[SPIKE] receive into', grid, e) }
</script>
```

- [ ] **Step 2: 跑起来手动验证**

```bash
pnpm dev
```

打开 http://localhost:3000/spike-cross-grid 。验证清单（用 claude-in-chrome 或手动）：
1. 把 A 的方块拖入 B —— 方块跟随光标无错位、能落入 B，控制台打印 `[SPIKE] send from A` 与 `[SPIKE] receive into B`。
2. 把 B（带 backdrop-blur 的卡片内）的方块拖入 A —— **重点看拖拽时方块是否相对光标偏移/错位**（这是包含块风险的实测）。
3. `listA`/`listB` 经 `@input` 更新后数量正确。

Expected：跨网格能拖、事件触发。**若发现 B→A 拖拽时方块明显错位**，记录现象，进入 Step 3 的对策验证。

- [ ] **Step 3: 若有坐标错位，验证 dragContainer=body 对策**

给两个 Vuuri 都加 `:options` 合并 `dragContainer: typeof document !== 'undefined' ? document.body : null`，重测 Step 2 第 2 项。记录：默认行为是否已 OK / 加 body 后是否 OK。

> 决策点：若 body 方案能修正错位 → Task 2 起统一用 `dragContainer: document.body`。若仍不行 → 停下，回设计文档 §5 评估"绕过 vuuri 直接配原生 Muuri"，不要继续后续任务。

- [ ] **Step 4: 删除验证页并提交结论**

```bash
rm pages/spike-cross-grid.vue
```

把 spike 结论（默认可行 / 需 dragContainer=body / 需绕过封装）追加到设计文档 §5 末尾一行，然后：

```bash
git add docs/superpowers/specs/2026-06-21-folder-cross-grid-drag-design.md
git commit -m "docs: record cross-grid drag spike result

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

### Task 2: FolderPanel 模态 → 原位浮层卡片（暂不接跨网格）

**Files:**
- Modify: `components/launchpad/FolderPanel.vue`（template 容器结构 + dim/blur 调整）
- Modify: `pages/index.vue:21`（传入文件夹锚点位置，用于卡片原位定位）

**Interfaces:**
- Consumes: 现有 props `{ visible: boolean; folder: UserLayoutNodeVO | null }`、emit `close`。
- Produces: 新增 prop `anchorRect?: DOMRect | null`（文件夹图标在视口的位置，卡片据此定位）；主网格在卡片打开时获得 dim/blur 视觉态（通过 body class `folder-open` 或 store 标志，见下）。

- [ ] **Step 1: 让 index.vue 在打开文件夹时记录锚点矩形**

`pages/index.vue` 的 `onOpenDir`（62–65 行）改为同时记录被点元素的 rect：

```ts
const folderAnchorRect = ref<DOMRect | null>(null)
function onOpenDir(item: UserLayoutNodeVO, ev?: MouseEvent) {
  const el = (ev?.currentTarget as HTMLElement | undefined) ?? null
  folderAnchorRect.value = el ? el.getBoundingClientRect() : null
  folderPanelId.value = item.id
  folderPanelVisible.value = true
}
```

模板里 `LaunchItem` 的 `@open-dir` 已透传 item；确认 `components/launch/Item.vue` 的 `open-dir` emit 带上原生事件（若没有，改为 `@open-dir="(it, ev) => onOpenDir(it, ev)"` 并在 Item 内 `emit('open-dir', item, ev)`）。把 `:anchor-rect="folderAnchorRect"` 传给 `LaunchpadFolderPanel`（21 行）。

- [ ] **Step 2: FolderPanel 容器从"全屏占满"改为"原位卡片"**

`components/launchpad/FolderPanel.vue` template 顶层（2–9 行）改为：背景遮罩层**单独一个兄弟元素**承载 dim+blur（不作为卡片祖先，规避包含块），卡片本身用 fixed 定位到 `anchorRect` 附近、不带 backdrop-filter：

```vue
<Teleport to="body">
  <Transition name="folder-panel">
    <div v-if="visible" class="fixed inset-0 z-50">
      <!-- 遮罩：承载 dim/blur，点击收起；与卡片同级，不是卡片祖先 -->
      <div class="absolute inset-0 bg-black/30 backdrop-blur-md" @click="close" />
      <!-- 卡片：原位定位，无 backdrop-filter，避免成为拖拽元素的包含块 -->
      <div class="absolute rounded-3xl bg-white/20 border border-white/30 shadow-2xl p-5" :style="cardStyle">
        <!-- ...原有名称编辑 + Vuuri 网格... -->
      </div>
    </div>
  </Transition>
</Teleport>
```

新增 props 与定位计算：

```ts
const props = defineProps<{ visible: boolean; folder: UserLayoutNodeVO | null; anchorRect?: DOMRect | null }>()

const cardStyle = computed(() => {
  const r = props.anchorRect
  const w = columnCount.value * ITEM_WIDTH.value + 40 // p-5*2
  if (!r) return { left: '50%', top: '50%', transform: 'translate(-50%,-50%)', width: `${w}px` }
  // 以锚点为中心展开，限制在视口内
  const left = Math.min(Math.max(8, r.left + r.width / 2 - w / 2), windowWidth.value - w - 8)
  const top = Math.min(Math.max(8, r.top), window.innerHeight - 8)
  return { left: `${left}px`, top: `${top}px`, width: `${w}px` }
})
```

（`columnCount`、`ITEM_WIDTH`、`windowWidth` 已在文件内存在，可直接复用。）

- [ ] **Step 3: 加展开动画从锚点缩放**

`<style scoped>` 把现有 `.folder-panel-enter-from/.leave-to`（237–241 行）的 `transform: scale(0.95)` 改为 `scale(0.85)` 并加 `transform-origin: center`，让卡片有"从图标弹开"的观感（遮罩层用 opacity 过渡即可）。

- [ ] **Step 4: 手动验证**

`pnpm dev` → 主页点不同位置的文件夹：
1. 卡片出现在**被点文件夹附近**（不是永远屏幕正中），且不超出视口。
2. 背后主网格**可见且变暗/模糊**、图标位置未移动。
3. 点遮罩 / Esc 能收起。
4. 卡片内排序、重命名仍正常（回归）。

- [ ] **Step 5: 提交**

```bash
git add components/launchpad/FolderPanel.vue pages/index.vue components/launch/Item.vue
git commit -m "feat(folder): 文件夹改为原位浮层卡片展开

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

### Task 3: 打通"拖出"（文件夹卡片 → 主网格精确位置）

**Files:**
- Modify: `pages/index.vue`（主 Vuuri 加 `group-id` + dragContainer + 跨网格 emit 处理）
- Modify: `components/launchpad/FolderPanel.vue`（卡片 Vuuri 加同 `group-id` + dragContainer + send 处理）

**Interfaces:**
- Consumes: vuuri `group-id`、`@send`(源网格移出某项)、`@receive`(目标网格收到某项)、`@input`(列表重排)。
- Produces: 拖出后本地 `bookmarkStore.layoutNode` 根列表含被移出节点、文件夹 `children` 已去除该节点（持久化在 Task 5）。

- [ ] **Step 1: 两个网格设同一 group-id 与统一 dragContainer**

常量：在两文件各定义 `const LAUNCHPAD_GROUP = 'launchpad'`（或放 `@config`）。
`pages/index.vue` 主 `<Vuuri>`（8 行）加：`:group-id="LAUNCHPAD_GROUP"`。
`components/launchpad/FolderPanel.vue` `<Vuuri>`（33 行）加：`:group-id="LAUNCHPAD_GROUP"`。
若 Task 1 结论为"需 dragContainer=body"，给两个网格的 `:options` 合并 `dragContainer: import.meta.client ? document.body : null`。

- [ ] **Step 2: index.vue 处理卡片项落入主网格（receive）**

主 Vuuri 加 `@receive="onReceiveFromFolder"`。`@input`（`onGridInput`，315 行）vuuri 已会把新节点 push 进列表并 emit，这里记录"有跨网格变更，待持久化"：

```ts
const crossGridDirty = ref(false)
function onReceiveFromFolder() {
  // vuuri 已把被拖节点并入 pageData/emit 的 input；此处仅标脏，落地在 onDragReleaseEnd
  crossGridDirty.value = true
}
```

- [ ] **Step 3: FolderPanel 处理项被移出（send）**

卡片 Vuuri 加 `@send="onSendOut"`。vuuri `_onItemSend` 已从 `localChildren` 移除该项，这里同步 store 文件夹 children 并标记需要持久化的节点：

```ts
const sentOutIds = ref<string[]>([])
function onSendOut({ item }: any) {
  const id = item?.getElement?.()?.dataset?.itemKey
  if (id) sentOutIds.value.push(id)
}
```

- [ ] **Step 4: 手动验证（仅前端态，先不接后端）**

`pnpm dev` → 打开一个有 ≥3 项的文件夹，把卡片内某图标拖到背后主网格：
1. 图标能跨过卡片边界、落到主网格目标位置（精确插入，不是末尾）。
2. 卡片内该图标消失，主网格出现该图标。
3. 控制台无坐标错位（对照 Task 1 结论）。

> 此时刷新页面数据会回退（未持久化），属预期。

- [ ] **Step 5: 提交**

```bash
git add pages/index.vue components/launchpad/FolderPanel.vue
git commit -m "feat(folder): group 跨网格打通文件夹拖出

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

### Task 4: 打通"拖入"（主网格图标 → 打开的文件夹卡片）

**Files:**
- Modify: `pages/index.vue`（主网格 send 处理）
- Modify: `components/launchpad/FolderPanel.vue`（卡片 receive 处理）

**Interfaces:**
- Consumes: 主网格 `@send`、卡片 `@receive`。
- Produces: 拖入后本地文件夹 `children` 含新节点、根列表去除该节点（持久化在 Task 5）。

- [ ] **Step 1: 主网格 send 处理**

`pages/index.vue` 主 Vuuri 加 `@send="onSendToFolder"`：

```ts
function onSendToFolder({ item }: any) {
  // vuuri 已从根列表 splice 该项；标脏，落地在释放时
  crossGridDirty.value = true
  movedIntoFolderId.value = (item?.getElement?.()?.dataset?.itemKey as string) ?? null
}
const movedIntoFolderId = ref<string | null>(null)
```

- [ ] **Step 2: 卡片 receive 处理**

`FolderPanel.vue` 卡片 Vuuri 加 `@receive="onReceiveIntoFolder"`，vuuri 已把节点 push 进 `localChildren`，这里记录待持久化：

```ts
const receivedIds = ref<string[]>([])
function onReceiveIntoFolder() {
  receivedIds.value = localChildren.value.map((c) => c.id)
}
```

- [ ] **Step 3: 手动验证（前端态）**

`pnpm dev` → 打开文件夹卡片，把主网格某图标拖进卡片：
1. 图标从主网格消失、出现在卡片内目标位置。
2. 跨网格双向都不错位。

- [ ] **Step 4: 提交**

```bash
git add pages/index.vue components/launchpad/FolderPanel.vue
git commit -m "feat(folder): 打通主网格图标拖入文件夹

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

### Task 5: 持久化（moveNode + sort）与失败回滚

**Files:**
- Modify: `components/launchpad/FolderPanel.vue`（拖出/拖入释放时落地）
- Modify: `pages/index.vue`（拖出落入主网格时的根列表 sort）

**Interfaces:**
- Consumes: `bookmarksMoveNode(nodeId, dirNodeId | null)`（`server/apis/index.ts:26`，返回受影响的 `UserLayoutNodeVO`，文件夹解散时返回另一节点）、`bookmarksSort(Record<id, index>)`（`:16`）。
- Produces: 后端与前端一致；接口失败时本地列表回滚到拖拽前快照。

- [ ] **Step 1: 拖出落地（卡片 → 根）**

在 `FolderPanel.vue` 的 `onDragReleaseEnd`（144 行）扩展：若本次有 `sentOutIds`，对每个移出节点 `moveNode(id, null)`，再让 index 重排根列表。由于排序需要根列表的最终顺序，约定 **FolderPanel 只负责改归属，根列表 sort 由 index 在其 `onDragReleaseEnd` 做**。具体：

```ts
async function onDragReleaseEnd() {
  dragging.value = false
  // 1) 文件夹内部排序（原有逻辑保留）
  if (pendingSort) { /* ...原有 bookmarksSort(文件夹children) ... */ }
  // 2) 跨网格移出：改归属
  if (sentOutIds.value.length) {
    const ids = [...sentOutIds.value]; sentOutIds.value = []
    try {
      for (const id of ids) await bookmarksMoveNode(id, null)
      // 同步 store 文件夹 children（去除移出项）
      const dir = bookmarkStore.layoutNode?.find((n) => n.id === props.folder?.id)
      if (dir) dir.children = [...localChildren.value]
    } catch { /* http 层已提示；回滚见 Step 3 */ }
  }
  // 3) 跨网格移入：改归属到本文件夹
  if (receivedIds.value.length) {
    const newIds = receivedIds.value.filter((id) => !(props.folder?.children ?? []).some((c) => c.id === id))
    receivedIds.value = []
    try { for (const id of newIds) await bookmarksMoveNode(id, props.folder!.id) } catch {}
    const dir = bookmarkStore.layoutNode?.find((n) => n.id === props.folder?.id)
    if (dir) dir.children = [...localChildren.value]
  }
}
```

- [ ] **Step 2: 根列表 sort（index 释放时）**

`pages/index.vue` 的 `onDragReleaseEnd`（296 行）：把 `crossGridDirty` 也作为触发 sort 的条件之一，确保跨网格变更后根列表顺序落地：

```ts
if (dragState.dirty || crossGridDirty.value) {
  const params: Record<string, number> = {}
  bookmarkStore.layoutNode?.forEach((node, index) => { params[node.id] = index })
  bookmarksSort(params)
  dragState.dirty = false
  crossGridDirty.value = false
}
```

- [ ] **Step 3: 失败回滚快照**

在拖拽开始（`onDragStart`）时各自存一份快照：`index.vue` 存 `bookmarkStore.layoutNode` 的浅拷贝；`FolderPanel` 存 `localChildren` 浅拷贝。上面 `try/catch` 的 `catch` 分支里恢复快照并 `gridKey.value++` / `vuuriKey.value++` 强制重渲染。示例（FolderPanel）：

```ts
let dragSnapshot: UserLayoutNodeVO[] = []
// onDragStart: dragSnapshot = [...localChildren.value]
// catch: localChildren.value = [...dragSnapshot]; vuuriKey.value++
```

- [ ] **Step 4: 处理文件夹自动解散**

`moveNode` 返回值 `type !== BOOKMARK_DIR` 表示文件夹已被后端解散（沿用现有 `moveOut` 199–210 行逻辑）：移出导致文件夹只剩 ≤1 项时，更新根列表为解散后的节点并 `emit('close')`。把该判断并入 Step 1 拖出分支：检查最后一次 `moveNode` 的返回 `result.type`。

- [ ] **Step 5: 手动验证（端到端）**

`pnpm dev`（后端 :7001 在跑）：
1. 拖出图标 → **刷新页面**，图标仍在主网格正确位置、文件夹内已无该项。
2. 拖入图标 → 刷新后图标在文件夹内。
3. 把文件夹拖到只剩 1 项 → 文件夹解散、卡片关闭、刷新后两项都在主网格。
4. （模拟失败）断开后端或改错接口 → 拖拽后本地 UI 回滚、不残留错误中间态。

- [ ] **Step 6: 提交**

```bash
git add components/launchpad/FolderPanel.vue pages/index.vue
git commit -m "feat(folder): 跨网格拖拽持久化与失败回滚

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

### Task 6: 移除 ↩ 移出按钮并清理

**Files:**
- Modify: `components/launchpad/FolderPanel.vue`（删除 ↩ 按钮模板 51–58 行 + `moveOut` 函数 189–215 行 + 不再用到的 import）

**Interfaces:**
- Consumes: 无（删除）。
- Produces: 文件夹卡片不再有 ↩ 按钮；移出唯一入口是拖拽。

- [ ] **Step 1: 删除按钮模板**

删除 `FolderPanel.vue` 51–58 行 `<button ... title="移出到桌面">↩</button>` 整块。

- [ ] **Step 2: 删除 moveOut 函数与冗余 import**

删除 `moveOut`（189–215 行）。若 `bookmarksMoveNode` 在 Task 5 后仍被 `onDragReleaseEnd` 使用则保留 import，否则一并清理。检查 `HomeItemType`、`BookmarkOpenMode` 等 import 是否仍被使用，移除未用项（避免 lint/构建告警）。

- [ ] **Step 3: 手动验证**

`pnpm dev`：
1. 文件夹卡片内 hover 图标**不再出现 ↩ 按钮**。
2. 拖出仍正常工作（回归 Task 5）。
3. 构建无告警：`pnpm build` 通过。

- [ ] **Step 4: 提交**

```bash
git add components/launchpad/FolderPanel.vue
git commit -m "refactor(folder): 移除 ↩ 移出按钮，拖拽为唯一移出入口

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

## 验证总览（人工回归清单）

- [ ] 点文件夹 → 原位卡片展开、主网格变暗不移位、Esc/点外收起
- [ ] 文件夹内排序、重命名正常（回归）
- [ ] 拖出到主网格精确位置 → 刷新后持久
- [ ] 主网格拖入文件夹 → 刷新后持久
- [ ] 文件夹剩 ≤1 项自动解散、卡片关闭
- [ ] 接口失败时本地回滚、无错误中间态
- [ ] ↩ 按钮已移除；`pnpm build` 通过
- [ ] 跨网格拖拽双向均无坐标错位（对照 Task 1 spike 结论）
