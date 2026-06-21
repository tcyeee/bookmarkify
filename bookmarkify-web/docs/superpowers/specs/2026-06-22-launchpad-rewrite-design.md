# 启动台主页彻底重构设计（方案 A）

> 日期：2026-06-22
> 范围：`bookmarkify-web` 用户书签主页（`pages/index.vue` 及其拖拽 / 状态 / 文件夹体系）
> 目标：代码结构清晰、从根因消灭长期 bug，**后端零改动**

## 背景与诊断

现有主页是 macOS 启动台风格（图标拖到图标上建文件夹、文件夹浮层打开、图标可拖进/拖出文件夹），基于 `vuuri`（内含 Muuri 0.9.5）。长期高频 bug 与"每次改动消耗巨量 token 还留下新 bug"的根因有且只有两个：

1. **没有单一数据源。** 同一份数据被复制成 3 份：根列表 `bookmarkStore.layoutNode`、文件夹面板 `localChildren`、store 里文件夹节点的 `children`。靠手写代码来回同步，任一处漏同步 → 同一 id 同时出现在根与某文件夹 → Vuuri `item-key` 重复 → 渲染死循环 → 持久化后**刷新即卡死**（见 `自定义拖拽踩坑合集.md` #10）。`dedupeLayout()`、`persist.afterHydrate` 去重、`pageData` 的 `filter(Boolean)`、`cellRevision`、bump `gridKey` 全是给这个病打的补丁。

2. **库选型错误。** Muuri 为不等高瀑布流（masonry）设计，而启动台**每个格子尺寸完全相同**。等大格子里"光标落在第几个格子"只是一次 `floor` 除法，根本不需要布局引擎。更糟的是为实现文件夹用了 Muuri 的**跨网格 group 迁移**（主网格与文件夹两个 Muuri 实例互传图标），其 `receive`/`send` 内部时序极脆弱，是踩坑 #9 / #10 的全部来源。

**结论**：后端 API 已足够（`query / sort / moveNode / createDir / renameDir / delete / addOne`），灾难全在前端状态管理。本次重构后端零改动，风险可控。

## 方案概述（方案 A：自研均匀网格 + 单一归一化数据源）

最终形态：**一个数据模型 + 一个拖拽控制器 + 一个递归网格组件**。扔掉 `vuuri`/Muuri，消灭跨网格实时迁移与多份列表。

## 1. 数据模型——单一归一化数据源

`stores/bookmark.store.ts` 重写为扁平归一化结构：

```ts
nodes: Record<string, LayoutNode>   // 节点内容 map（type/name/typeApp/typeFuc/parentId 等），唯一真相
order: Record<string, string[]>     // parentKey → 有序子 id 列表；根用 ROOT 常量键
```

**核心不变量**：一个 id 只能出现在 `order` 的某一个数组里 → 节点的归属与顺序都只有这一个来源 → **同一 id 物理上不可能同时出现在两处**。踩坑 #10 这一整类崩溃从数据结构层面不可能发生。

因此**删除**：`dedupeLayout()`、`persist.afterHydrate` 去重、`pageData.filter(Boolean)`、`cellRevision`、所有 `gridKey` bump。

派生（computed getter）：

- `rootNodes`：`order[ROOT].map(id => nodes[id])`；其中文件夹节点的 `children` 由 `order[folderId]` 即时填充，使得 `cell/Folder.vue`（预览图读 `value.children`）等纯展示组件**无需修改**。
- `folderChildren(folderId)`：`(order[folderId] ?? []).map(id => nodes[id])`。

Actions（统一模式：先本地乐观更新 → 调 API → 失败回滚）：

- `setLayout(backendRoot)`：把后端返回的树归一化为 `nodes` + `order`（权威校正）。
- `reorder(parentKey, ids)`：替换某父级顺序数组；持久化调 `sort`。
- `moveNode(id, toParentKey, index)`：从原父数组移除、插入目标父数组指定位置；调 `moveNode` + 必要的 `sort`。
- `createFolder(draggedId, targetId, index)`：本地乐观建夹；调 `createDir`，用返回节点 reconcile。
- `removeNode(id)`：删除节点与其在 `order` 中的引用；调 `delete`。
- `replaceContent(node)`：WebSocket 内容替换（如 `BOOKMARK_LOADING → BOOKMARK`），**只改 `nodes[id]` 内容**，靠 Vue 响应式重渲染，**不再 bump 任何 key**。
- `addLoading(node)`：插入加载占位项到根。
- `reconcileAfterMove(result, srcParentKey)`：统一处理后端**自动解散文件夹**（剩 ≤1 项）——把文件夹从 `order` 移除、剩余节点并入父级。

`persist`：仍持久化 `nodes` + `order` 以秒开；因重复-id 结构上不可能，去掉全部水合去重 hack；随后 `update()` 调 `setLayout` 拉后端权威数据校正。

## 2. 组件结构

```
pages/index.vue            薄页面：组装 LaunchGrid + 详情弹窗 + 文件夹浮层，几乎零逻辑
components/launchpad/
  LaunchGrid.vue           网格：从一组 nodes 渲染单元格（相对容器 + 绝对定位 + transform）
  LaunchCell.vue           单元格类型分发（取代 launch/Item.vue），派发到 cell/*；含右键菜单
  FolderOverlay.vue        文件夹浮层（取代 FolderPanel.vue），内部复用 LaunchGrid 渲染 children
  cell/Bookmark|Folder|Function|BookmarkLogo.vue   纯展示，基本不动
composables/
  useLaunchpadDrag.ts      唯一拖拽控制器（pointer 事件 + 命中检测 + 松手提交）
  useGridLayout.ts         列数 / 格子尺寸 / index→坐标 纯函数
```

旧文件处置：`pages/index.vue` 重写；`launch/Item.vue → LaunchCell.vue`；`launchpad/FolderPanel.vue → FolderOverlay.vue`；`cell/*` 保留。

## 3. 布局——等大格子 = 纯数学

容器相对定位，每个 cell 绝对定位 `transform: translate(x, y)`。`useGridLayout` 提供 `posOf(index) → {x, y}`（`列 = index % cols`，`行 = floor(index / cols)`），并依据容器宽度算列数（沿用现 `recalcColumns` 逻辑）。重排只改 index → transform 变化 → **CSS transition 自动产生 FLIP 平滑动画**。无布局引擎。

## 4. 拖拽——唯一控制器，松手提交

`useLaunchpadDrag`（root 网格与文件夹浮层共用同一份逻辑）：

- `pointerdown` 后位移超过 8px 阈值 → 起拖，被拖 cell 抬起（z-index / scale）跟随光标。
- `pointermove`：
  - 命中某 BOOKMARK / 文件夹的内圈（中心 70% 区）且停留 300ms → 标记合并 / 移入目标，复用现有 `.merge-glow` 白框闪烁。
  - 否则按光标所在 slot 做**容器内实时重排预览**（纯本地，不碰后端）。
  - 在文件夹浮层中且光标移出浮层边界（含滞回边距，沿用 `EJECT_MARGIN`）→ 标记"松手将弹出到根"。
- `pointerup` 是**唯一提交点**：
  - 命中合并目标（BOOKMARK）→ `createFolder`
  - 命中移入目标（文件夹）→ `moveNode(目标文件夹)`
  - 弹出标记 → `moveNode(ROOT)`
  - 否则 → `reorder`

**全程无跨容器实时迁移**：容器内拖拽实时，跨容器移动一律在松手那一刻提交数据再各自重渲染。这是踩坑 #9 整类时序 bug 被根除的原因。手感对用户几乎无差（已与用户确认采用"松手提交"）。

## 5. 错误处理与边界

- **乐观更新 + 回滚**：提交前快照受影响的 `order`，API 失败则恢复（现版本不回滚，会留下前后端不一致）。http 层统一 toast 不变，组件不重复弹错。
- **后端自动解散文件夹**：收敛到 `reconcileAfterMove` 一个函数，不再散落在页面多处。
- **持久化**：见数据模型节；去重 hack 全删。
- **SSR**：网格需量容器宽度，沿用 `ClientOnly` 包裹，服务端渲染空占位。
- **加载占位 / WebSocket 更新**：`addLoading` + `replaceContent`，不再依赖 `gridKey`/`cellRevision`。

## 6. 后端与依赖

- **后端零改动**：复用现有 `/bookmark/*` 接口。`sort` 接收某父级下的 `id→index` 映射，按受影响父级分别调用。
- **移除 `vuuri` 依赖**（package.json + import）。
- 更新 store 消费方到新 action 名：`plugins/auth.ts`、`stores/auth.store.ts`、`stores/websocket.store.ts`、`components/launchpad/AddOneDialog.vue`、`components/setting/BookmarkManage.vue`、`cell/Bookmark.vue`、`launch/Item.vue`（并入 LaunchCell）。

## 验收标准

1. 增删改、排序、建夹、移入、拖出、重命名、文件夹自动解散全部可用，体验与现版本一致。
2. 反复跨文件夹拖拽后刷新页面**不卡死**（核心回归）。
3. WebSocket `HOME_ITEM_UPDATE`（LOADING→BOOKMARK）就地更新无需强制重建。
4. 代码中不再出现 `dedupeLayout` / `cellRevision` / `gridKey` / `filter(Boolean)` 兜底 / `vuuri`。
5. `pnpm build` 通过。

## 非目标（YAGNI）

- 不改后端数据结构与 API。
- 不引入新拖拽库。
- 不做文件夹套文件夹（沿用现有"禁止"约束）。
- 不改动 `cell/*` 纯展示组件的视觉。
