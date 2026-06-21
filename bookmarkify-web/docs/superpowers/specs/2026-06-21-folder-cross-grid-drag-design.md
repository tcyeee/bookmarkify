# 设计：macOS 启动台式文件夹（原位浮层 + 真跨网格拖拽）

- 日期：2026-06-21
- 范围：`bookmarkify-web` 前端
- 状态：待评审

## 1. 背景与目标

主页启动台已支持图标拖拽排序、以及"拖到一起自动建文件夹"。当前缺口是**文件夹内图标的"移出"只能靠每个图标上的 ↩ 按钮调接口**，不是拖拽，且落点只能追加到末尾。

目标：实现 **macOS 启动台式**的文件夹体验——

1. 点文件夹 → 原位浮层卡片展开，背后主网格变暗但位置不动、仍可作落点。
2. 把卡片内图标**拖出卡片、落到主网格精确位置**（移出文件夹）。
3. 把主网格图标**拖入打开的卡片**（加入文件夹，反向）。
4. 移除现有的 ↩ 移出按钮（拖拽上线后不再需要）。

## 2. 现状

- **主网格** `pages/index.vue`：Vuuri/Muuri 网格。合并建文件夹/移入文件夹已实现（`createFolder`、`moveToFolder`、合并意图检测）。
- **文件夹面板** `components/launchpad/FolderPanel.vue`：`Teleport to body` 的**全屏模态 + 背景毛玻璃**，内含独立 Vuuri 网格。移出靠 `↩` 按钮（`moveOut`）调 `moveNode(child, null)`。
- **数据** `stores/bookmark.store.ts`：`layoutNode: UserLayoutNodeVO[]` 树，文件夹的 `children` 为子节点。
- **关键事实**：vuuri 0.4.6 内部打包 Muuri 0.9.5，**Vue 封装本身内置多网格分组拖拽**——`group-id` / `group-ids` prop，设置后自动 `dragSort = () => 同组所有网格`，并在 `_onItemSend`/`_onItemReceive` 中处理跨网格时两边 `modelValue` 的增删并 emit（见 `node_modules/vuuri/dist/vuuri.js` 4879–4986 行）。

## 3. 交互模型（最终）

1. 点文件夹图标 → 在**原位**弹出圆角卡片浮层（内含文件夹图标网格）；背后主网格 **dim + 轻微 blur，但位置不变、保持挂载可作为落点**。
2. 卡片内图标可拖动排序（文件夹内排序，已有）。
3. **拖出**：把卡片内图标拖出卡片、落到背后主网格 → 跨网格 send/receive，图标插入主网格精确位置。
4. **拖入**：把主网格图标拖入打开的卡片 → 加入该文件夹（精确位置）。
5. 点卡片外空白 / Esc → 收起卡片。
6. 文件夹被拖到只剩 ≤1 项 → 后端自动解散、卡片关闭（沿用 `moveOut` 现有逻辑）。
7. 移除每个图标上的 ↩ 移出按钮。

## 4. 技术方案

### 4.1 复用 vuuri 内置 group
给 `index.vue` 主网格与 `FolderPanel` 的网格设**同一个 `group-id`**（例如常量 `'launchpad'`）。跨网格拖拽的 `dragSort` 与两边 `modelValue` 增删由 vuuri 自动完成，组件通过 `@input` / `update:modelValue`（`_emitValue`）把更新后的列表 emit 出来。

### 4.2 FolderPanel：模态 → 内联浮层卡片
- 去掉"全屏遮罩占满 + Teleport 把主网格盖住"的结构，改成**定位在文件夹图标附近的卡片浮层**；主网格保持挂载、可见、可拖。
- 卡片打开期间主网格加 dim/blur 视觉态（CSS class），但**不可在主网格祖先链上用 `transform`/`filter`**（见 §5）。
- 背景点击 / Esc 收起。

### 4.3 数据流与持久化
- 监听两个网格的 `send` / `receive` / `input`：
  - **拖出**（文件夹→主网格）：从文件夹 `children` 移除、加入 `layoutNode` 根列表 → `moveNode(childId, null)` 改归属 → `sort(根列表按 index)` 落地精确位置。
  - **拖入**（主网格→文件夹）：从 `layoutNode` 根列表移除、加入文件夹 `children` → `moveNode(childId, folderId)` → `sort(文件夹 children 按 index)`。
- 没有"移动到指定位置"的单一接口：**精确落点 = `moveNode` 改归属 + `sort` 重排目标列表**（两步）。
- 写回 `bookmarkStore` 时遵循"替换为新对象引用"以触发响应式（参考 `updateOneBookmarkCell` 约定）。

## 5. 头号技术风险与对策（开发前先做 spike）

跨网格拖拽时被拖元素要在"卡片"和"主网格"两个定位上下文间正确显示。**若卡片或其祖先使用 `backdrop-filter` / `transform`，会创建新的包含块，导致拖拽元素（`dragContainer` 内的 fixed/absolute 元素）坐标错乱**。当前 `FolderPanel` 正用了 `backdrop-blur`。

对策：
- 把两个网格的 `dragContainer` 统一到 `document.body`（或一个不含 transform/filter 的公共拖拽层）。
- 卡片的毛玻璃效果改用**不影响包含块**的方式（如独立的背景层兄弟元素承载 blur，而非作为拖拽元素的祖先）。
- **先写最小 spike**：两个相邻 Vuuri 网格设同一 group-id，验证在本项目浮层结构下跨网格拖拽坐标正确、`send`/`receive` 正常触发。spike 通过再进入正式实现。

## 6. 范围

**包含**：拖出、拖入（反向）、内联浮层卡片改造、移除 ↩ 按钮、跨网格持久化。

**不包含**：合并建文件夹逻辑改动（保持现状）、文件夹分页、嵌套文件夹（文件夹套文件夹）。

## 7. 边界与异常

- 文件夹拖到只剩 ≤1 项：后端自动解散，卡片关闭（沿用现有分支处理）。
- 拖出/拖入接口失败：错误由 http 层统一提示；前端需回滚本地列表到接口调用前的快照，避免 UI 与后端不一致。
- 拖拽进行中隐藏卡片内的点击/悬停态（沿用现有 `dragging` 抑制）。
- WebSocket `HOME_ITEM_UPDATE` 与拖拽并发：沿用 `cellRevision` / `gridKey` 重新同步机制。

## 8. 涉及文件

- `pages/index.vue` —— 主网格加 `group-id`；处理跨网格 emit 与持久化；主网格 dim/blur 态。
- `components/launchpad/FolderPanel.vue` —— 模态改内联浮层卡片；加 `group-id`；移除 ↩ 按钮；调整毛玻璃结构避开包含块问题。
- `stores/bookmark.store.ts` —— 视需要补跨网格移动的 store action（移出/移入根与 children 的引用更新）。
- 接口复用：`bookmarksMoveNode`、`bookmarksSort`（已存在，无需新增）。

## 9. 实施步骤概览

1. **Spike**：相邻双网格 group 跨网格拖拽 + dragContain=body 坐标验证。
2. FolderPanel 模态 → 内联浮层卡片（先不接跨网格，确认展开/收起/原位定位/dim 正常）。
3. 主网格 + 卡片设同一 group-id，打通拖出。
4. 打通拖入（反向）。
5. 接持久化（moveNode + sort）与失败回滚。
6. 移除 ↩ 按钮，清理。
