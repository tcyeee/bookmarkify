<template>
  <!-- 完整APP列表 -->
  <div ref="outerRef" class="flex w-full justify-center">
    <!-- APP列表容器：min-width 保证不被外层挤压 -->
    <div class="w-full flex justify-center">
      <!-- Vuuri 仅在客户端渲染，避免 SSR 阶段访问 DOM -->
      <ClientOnly>
        <Vuuri :key="`${CELL_SIZE}-${CELL_GAP}-${TITLE_HEIGHT}-${gridKey}`" group-id="launchpad" class="demo-grid" :style="vuuriStyle" :model-value="pageData" item-key="id" :options="vuuriOptions" :drag-enabled="true" :get-item-width="() => `${CELL_SIZE + CELL_GAP}px`" :get-item-height="() => `${CELL_SIZE + CELL_GAP + TITLE_HEIGHT}px`" @input="onGridInput" @receive="onMainReceive" @send="onMainSend" @drag-start="onDragStart" @drag-end="onDragEnd" @drag-release-end="onDragReleaseEnd">
          <template #item="{ item }">
            <LaunchItem :key="`${item.id}-${item.type}`" :item="item" :toggle-drag="dragState.dragging || dragState.justDropped" @show-detail="onShowDetail" @open-dir="onOpenDir" />
          </template>
        </Vuuri>
      </ClientOnly>
    </div>
  </div>

  <el-dialog v-model="detailVisible" title="书签详情" width="480px" :close-on-click-modal="true">
    <LaunchpadDetail :data="detailBookmark" />
  </el-dialog>

  <LaunchpadFolderPanel :visible="folderPanelVisible" :folder="folderPanelItem" :anchor-rect="folderAnchorRect" @close="folderPanelVisible = false" />
</template>

<script lang="ts" setup>
import { bookmarksSort, bookmarksCreateDir, bookmarksMoveNode } from '@api'
import { HomeItemType, type BookmarkShow, type UserLayoutNodeVO } from '@typing'
definePageMeta({ middleware: 'auth', layout: 'launch' })

const bookmarkStore = useBookmarkStore()
const preferenceStore = usePreferenceStore()

const pageData = computed<Array<UserLayoutNodeVO>>(() => bookmarkStore.layoutNode || [])

/** id → 节点 的查找表，供拖拽热路径 O(1) 查询，随 layoutNode 变化自动重建 */
const nodeById = computed(() => new Map((bookmarkStore.layoutNode ?? []).map((n) => [n.id, n] as const)))

/** 单元格尺寸与间距，跟随用户偏好 */
const CELL_SIZE = computed(() => preferenceStore.bookmarkCellSizePx)
const CELL_GAP = computed(() => preferenceStore.bookmarkGapPx)

const TITLE_HEIGHT = computed(() => (preferenceStore.preference?.showTitle ? 28 : 0))
const COLUMN_WIDTH = computed(() => CELL_SIZE.value + CELL_GAP.value)

/** 客户端按需加载 Vuuri；服务端阶段返回空占位以规避报错 */
const Vuuri = import.meta.client
  ? defineAsyncComponent(() => import('vuuri'))
  : defineComponent({ name: 'VuuriPlaceholder', setup: () => () => null })

const detailVisible = ref(false)
const detailBookmark = ref<BookmarkShow | null>(null)

function onShowDetail(bookmark: BookmarkShow) {
  detailBookmark.value = bookmark
  detailVisible.value = true
}

const folderPanelVisible = ref(false)
const folderPanelId = ref<string | null>(null)
const folderAnchorRect = ref<DOMRect | null>(null)
// 跨网格拖出：记录本次从文件夹移入主网格的节点 id 及其来源文件夹 id，供释放时持久化
const receivedFromFolderId = ref<string | null>(null)
const receivedFromFolderSrcId = ref<string | null>(null)
// computed 保证 folderPanelItem 始终指向 store 中的最新节点，而非点击时的快照
const folderPanelItem = computed(() => (folderPanelId.value ? nodeById.value.get(folderPanelId.value) ?? null : null))

function onOpenDir(item: UserLayoutNodeVO) {
  const el = document.querySelector(`.demo-grid [data-item-key="${item.id}"]`) as HTMLElement | null
  folderAnchorRect.value = el ? el.getBoundingClientRect() : null
  folderPanelId.value = item.id
  folderPanelVisible.value = true
}

const gridKey = ref(0)

const dragState = reactive<{ dragging: boolean; justDropped: boolean; dirty: boolean }>({
  dragging: false,
  justDropped: false,
  dirty: false,
})
const outerRef = ref<HTMLElement | null>(null)
const columnCount = ref(1)
let resizeObserver: ResizeObserver | null = null

/** 控制 Vuuri 容器宽度，使列在左右留白时仍居中 */
const vuuriStyle = computed(() => ({
  width: `${Math.max(1, columnCount.value) * COLUMN_WIDTH.value}px`,
}))

// ── 合并/创建文件夹/移入文件夹 状态 ──────────────────────────────────────────
const mergeTargetId = ref<string | null>(null)
let mergeTargetType: HomeItemType | null = null  // 目标节点类型
let mergeTargetEl: HTMLElement | null = null     // .muuri-item 容器
let mergeIconEl: HTMLElement | null = null       // 图标根 div（overflow-hidden）
const mergeReady = ref(false)                    // 悬停满 300ms，可触发操作
let mergeTimer: ReturnType<typeof setTimeout> | null = null
let currentDraggedId = ''

function clearMergeState() {
  if (mergeTimer) {
    clearTimeout(mergeTimer)
    mergeTimer = null
  }
  mergeIconEl?.classList.remove('merge-glow')
  mergeIconEl = null
  mergeTargetEl = null
  mergeTargetId.value = null
  mergeTargetType = null
  mergeReady.value = false
}

type OverlapResult = { targetId: string; targetEl: HTMLElement; index: number; grid: any; targetType: HomeItemType | null }

/**
 * 合并/移入意图检测：当拖动图标的中心点落在目标图标的中心 50% 区域内时触发。
 * 触发区域为目标图标中心 70%（15%~85%）。
 * - 目标为 BOOKMARK   → 两者合并创建新文件夹
 * - 目标为 BOOKMARK_DIR → 将拖动书签移入该文件夹
 */
function findMergeTarget(item: any): OverlapResult | null {
  const grid = item.getGrid?.()
  if (!grid) return null
  const dragEl = item.getElement?.() as HTMLElement | undefined
  if (!dragEl) return null
  const dr = dragEl.getBoundingClientRect()
  const cx = dr.left + dr.width / 2
  const cy = dr.top + dr.height / 2

  const items: any[] = grid.getItems()
  for (let i = 0; i < items.length; i++) {
    const targetItem = items[i]
    if (targetItem === item || !targetItem.isActive?.()) continue
    const el = targetItem.getElement?.() as HTMLElement | undefined
    if (!el) continue
    const r = el.getBoundingClientRect()
    const zx1 = r.left + r.width * 0.15
    const zx2 = r.left + r.width * 0.85
    const zy1 = r.top + r.height * 0.15
    const zy2 = r.top + r.height * 0.85
    if (cx < zx1 || cx > zx2 || cy < zy1 || cy > zy2) continue

    const targetId = el.dataset.itemKey
    if (!targetId) continue
    const targetNode = nodeById.value.get(targetId)
    if (targetNode?.type !== HomeItemType.BOOKMARK && targetNode?.type !== HomeItemType.BOOKMARK_DIR) continue
    return { targetId, targetEl: el, index: i, grid, targetType: targetNode.type }
  }
  return null
}

/**
 * 正常排序：面积重叠 >= 50% 时告知 Muuri 应移动到哪个位置。
 * 关键约束：若拖动图标的中心点落在任意其他图标的范围内，则抑制排序。
 * 这样可以确保"目标图标"在整个合并过程中不会被挤压移位，
 * 使得文件夹最终出现在目标图标的实际位置上。
 */
function computeNormalSort(item: any): OverlapResult | null {
  const grid = item.getGrid?.()
  if (!grid) return null
  const dragEl = item.getElement?.() as HTMLElement | undefined
  if (!dragEl) return null
  const dr = dragEl.getBoundingClientRect()
  const cx = dr.left + dr.width / 2
  const cy = dr.top + dr.height / 2

  let bestScore = 0
  let bestIndex = -1
  let bestItem: any = null

  ;(grid.getItems() as any[]).forEach((targetItem: any, index: number) => {
    if (targetItem === item || !targetItem.isActive?.()) return
    const el = targetItem.getElement?.() as HTMLElement | undefined
    if (!el) return
    const r = el.getBoundingClientRect()

    const ox = Math.min(dr.right, r.right) - Math.max(dr.left, r.left)
    const oy = Math.min(dr.bottom, r.bottom) - Math.max(dr.top, r.top)
    if (ox <= 0 || oy <= 0) return
    const maxArea = Math.min(dr.width, r.width) * Math.min(dr.height, r.height)
    const score = maxArea > 0 ? ((ox * oy) / maxArea) * 100 : 0
    if (score > bestScore) {
      bestScore = score
      bestIndex = index
      bestItem = targetItem
    }
  })

  if (bestScore < 50 || bestIndex === -1 || !bestItem) return null
  return { targetId: '', targetEl: bestItem.getElement(), index: bestIndex, grid, targetType: null }
}

/**
 * 取同 group 中打开着的文件夹网格（class 含 folder-grid）。
 * vuuri group 把 dragSort 设为「() => 同组所有网格」，故经被拖项所属网格的 _settings.dragSort 取到组内网格。
 */
function findOpenFolderGrid(item: any): any | null {
  const grids: any[] = item.getGrid?.()?._settings?.dragSort?.() ?? []
  return grids.find((g) => g !== item.getGrid?.() && g?.getElement?.()?.classList?.contains('folder-grid')) ?? null
}

/** 拖动图标中心是否落在文件夹卡片范围内（用整张卡片做命中区，比网格本身大得多，便于拖入） */
function isCenterOverGrid(item: any, grid: any): boolean {
  const dragEl = item.getElement?.() as HTMLElement | undefined
  const gridEl = grid?.getElement?.() as HTMLElement | undefined
  if (!dragEl || !gridEl) return false
  // 文件夹卡片是 .folder-grid 最近的 pointer-events-auto 祖先；取不到则退回网格本身
  const hitEl = (gridEl.closest('.pointer-events-auto') as HTMLElement | null) ?? gridEl
  const dr = dragEl.getBoundingClientRect()
  const cx = dr.left + dr.width / 2
  const cy = dr.top + dr.height / 2
  const r = hitEl.getBoundingClientRect()
  return cx >= r.left && cx <= r.right && cy >= r.top && cy <= r.bottom
}

/** Vuuri 布局与拖拽配置，dragSortPredicate 兼管合并意图检测 */
const vuuriOptions = {
  layout: { fillGaps: true, rounding: false },
  layoutDuration: 250,
  showDuration: 150,
  hideDuration: 150,
  dragReleaseDuration: 0,
  dragStartPredicate: { distance: 8, delay: 0 },
  dragSortPredicate: (item: any) => {
    currentDraggedId = (item.getElement?.() as HTMLElement | undefined)?.dataset?.itemKey ?? ''

    // 仅 BOOKMARK 类型可触发合并/移入
    const draggedNode = nodeById.value.get(currentDraggedId)

    // ⓪ 反向拖入：文件夹打开时，BOOKMARK 图标中心落入文件夹网格范围 → 移入该文件夹（禁止文件夹套文件夹）
    if (folderPanelVisible.value && draggedNode?.type === HomeItemType.BOOKMARK) {
      const folderGrid = findOpenFolderGrid(item)
      if (folderGrid && isCenterOverGrid(item, folderGrid)) {
        return { grid: folderGrid, index: folderGrid.getItems().length, action: 'move' }
      }
    }
    const canMerge = draggedNode?.type === HomeItemType.BOOKMARK

    // ① 优先检测合并意图（中心点命中目标内圈）
    const mergeTarget = canMerge ? findMergeTarget(item) : null

    if (mergeTarget) {
      // 目标发生变化：重新开始 300ms 计时
      if (mergeTarget.targetId !== mergeTargetId.value) {
        clearMergeState()
        mergeTargetId.value = mergeTarget.targetId
        mergeTargetEl = mergeTarget.targetEl
        mergeTargetType = mergeTarget.targetType
        mergeTimer = setTimeout(() => {
          mergeReady.value = true
          // 书签用 div.overflow-hidden，文件夹用 div.folder-icon
          const iconEl = (mergeTargetEl?.querySelector('div.folder-icon') ??
            mergeTargetEl?.querySelector('div.overflow-hidden')) as HTMLElement | null
          if (iconEl) {
            iconEl.classList.add('merge-glow')
            mergeIconEl = iconEl
          }
        }, 300)
      }
      // 只要中心在内圈，立即抑制排序（无论 300ms 是否到）
      return null
    }

    // ② 中心不在任何目标内圈：清除合并状态，走正常排序
    if (mergeTargetId.value !== null) clearMergeState()

    const sortResult = computeNormalSort(item)
    if (sortResult) return { grid: sortResult.grid, index: sortResult.index, action: 'move' }
    return null
  },
}

/** 根据可用宽度重新计算列数，确保容器宽度与列数对齐 */
const recalcColumns = () => {
  const container = outerRef.value
  if (!container) return
  const available = container.clientWidth
  const next = Math.max(1, Math.floor((available + CELL_GAP.value) / COLUMN_WIDTH.value))
  columnCount.value = next
}

// WebSocket 就地替换单元格内容（如 LOADING→BOOKMARK）时，
// Vuuri 内部按 id diff 感知不到同 id 节点的内容变化，需 bump gridKey 强制重新同步。
watch(
  () => bookmarkStore.cellRevision,
  () => {
    gridKey.value++
  },
)

onMounted(() => {
  recalcColumns()
  watch([CELL_SIZE, CELL_GAP], () => recalcColumns())
  resizeObserver = new ResizeObserver(() => recalcColumns())
  if (outerRef.value) resizeObserver.observe(outerRef.value)
  window.addEventListener('resize', recalcColumns)
})

onBeforeUnmount(() => {
  resizeObserver?.disconnect()
  window.removeEventListener('resize', recalcColumns)
  clearMergeState()
})

/** 拖拽开始 */
function onDragStart(item: any) {
  dragState.dragging = true
  dragState.justDropped = false
  dragState.dirty = false
  currentDraggedId = (item?.getElement?.() as HTMLElement | undefined)?.dataset?.itemKey ?? ''
}

/**
 * dragEnd 先于 dragReleaseEnd 触发，在此处理合并逻辑。
 * 若是合并操作，清除 dirty 标志避免 onDragReleaseEnd 再走排序。
 */
function onDragEnd() {
  if (mergeReady.value && mergeTargetId.value && currentDraggedId) {
    const draggedId = currentDraggedId
    const targetId = mergeTargetId.value
    const targetType = mergeTargetType
    clearMergeState()
    dragState.dirty = false
    if (targetType === HomeItemType.BOOKMARK_DIR) {
      moveToFolder(draggedId, targetId)
    } else {
      createFolder(draggedId, targetId)
    }
  } else {
    clearMergeState()
  }
}

/** 释放动画完成后：处理排序、跨网格拖出持久化和拖拽状态重置（合并逻辑已在 onDragEnd 处理） */
async function onDragReleaseEnd() {
  dragState.dragging = false
  dragState.justDropped = true

  const movedInId = receivedFromFolderId.value
  const srcFolderId = receivedFromFolderSrcId.value
  receivedFromFolderId.value = null
  receivedFromFolderSrcId.value = null

  // 跨网格拖出：改归属到根，并从来源文件夹本地 children 移除（避免与根列表重复）
  if (movedInId) {
    const dir = bookmarkStore.layoutNode?.find((n) => n.id === srcFolderId)
    if (dir?.children) dir.children = dir.children.filter((c) => c.id !== movedInId)
    try {
      const result = await bookmarksMoveNode(movedInId, null)
      // 文件夹被后端自动解散（剩 ≤1 项）：result 为剩余的那个节点（非 BOOKMARK_DIR）。
      // 移除文件夹节点，把剩余节点并入根。
      if (result && result.type !== HomeItemType.BOOKMARK_DIR && srcFolderId) {
        bookmarkStore.layoutNode = [
          ...(bookmarkStore.layoutNode ?? []).filter((n) => n.id !== srcFolderId && n.id !== result.id),
          { ...result, parentId: null },
        ]
      }
    } catch {
      // 错误已由 http 层统一提示
    }
    bookmarkStore.dedupeLayout()
  }

  // 普通排序 dirty 或 跨网格移入都需重排根列表落地位置
  if (dragState.dirty || movedInId) {
    const params: Record<string, number> = {}
    bookmarkStore.layoutNode?.forEach((node, index) => {
      params[node.id] = index
    })
    bookmarksSort(params)
    dragState.dirty = false
  }

  requestAnimationFrame(() => {
    dragState.justDropped = false
  })
}

/** Vuuri input 事件：排序数据更新 */
function onGridInput(list: UserLayoutNodeVO[]) {
  bookmarkStore.layoutNode = list
  if (dragState.dragging) dragState.dirty = true
}

/**
 * 主网格收到来自文件夹的跨网格图标（vuuri group 的 receive）：立即关闭文件夹浮层。
 * 此刻迁移已完成（图标已并入主网格），再卸载文件夹是安全的；拖拽继续在主网格进行。
 */
function onMainReceive(e: any) {
  receivedFromFolderId.value = (e?.item?.getElement?.() as HTMLElement | undefined)?.dataset?.itemKey ?? null
  receivedFromFolderSrcId.value = folderPanelId.value
  if (folderPanelVisible.value) folderPanelVisible.value = false
}

/**
 * 主网格图标被拖入文件夹（vuuri group 的 send）：该项已离开主网格，
 * 主网格不会再收到 drag-end/release-end，在此复位拖拽状态避免 toggle-drag 卡死。
 * 持久化由 FolderPanel 的 drag-release-end 处理。
 */
function onMainSend() {
  dragState.dragging = false
  dragState.dirty = false
  requestAnimationFrame(() => {
    dragState.justDropped = false
  })
}

// ── 移入文件夹 ────────────────────────────────────────────────────────────────
async function moveToFolder(draggedId: string, dirNodeId: string) {
  try {
    const updatedDir = await bookmarksMoveNode(draggedId, dirNodeId)
    // 从根列表移除被拖动节点，用返回的最新文件夹替换目标文件夹
    bookmarkStore.layoutNode = (bookmarkStore.layoutNode ?? [])
      .filter((n) => n.id !== draggedId)
      .map((n) => (n.id === dirNodeId ? updatedDir : n))
    gridKey.value++
    ElNotification.success({ message: '已移入文件夹' })
  } catch {
    // 错误已由 http 层统一提示
  }
}

// ── 创建文件夹 ────────────────────────────────────────────────────────────────
async function createFolder(draggedId: string, targetId: string) {
  const nodes = bookmarkStore.layoutNode ?? []
  const draggedNode = nodes.find((n) => n.id === draggedId)
  const targetNode = nodes.find((n) => n.id === targetId)
  if (!draggedNode || !targetNode) return

  const draggedIndex = nodes.findIndex((n) => n.id === draggedId)
  const sort = draggedIndex === -1 ? nodes.length : draggedIndex

  try {
    const folderNode = await bookmarksCreateDir([draggedId, targetId], '新建文件夹', sort)

    // 用返回的文件夹节点替换被拖动节点，移除目标节点
    bookmarkStore.layoutNode = (bookmarkStore.layoutNode ?? [])
      .map((n) => (n.id === draggedId ? folderNode : n))
      .filter((n) => n.id !== targetId)

    gridKey.value++
    ElNotification.success({ message: '已创建文件夹' })
  } catch {
    // 错误已由 http 层统一提示
  }
}
</script>

<style>
.demo-grid {
  margin: 0 auto;
  /* 移除 Muuri 默认 margin，完全由 gap 控制间距 */
}

.demo-grid .muuri-item {
  margin: 0;
}

.demo-grid .muuri-item-content {
  width: 100%;
  height: 100%;
  display: flex;
  justify-content: center;
  align-items: center;
  box-sizing: border-box;
}

/* 合并目标：图标白色外边框 + 缓慢闪烁 */
.merge-glow {
  box-shadow:
    0 0 0 3px rgba(255, 255, 255, 0.95),
    0 0 16px rgba(255, 255, 255, 0.4) !important;
  animation: merge-blink 700ms ease-in-out infinite !important;
  transition: none !important;
}

@keyframes merge-blink {
  0%, 100% {
    opacity: 1;
    box-shadow:
      0 0 0 3px rgba(255, 255, 255, 0.95),
      0 0 16px rgba(255, 255, 255, 0.4);
  }
  50% {
    opacity: 0.55;
    box-shadow:
      0 0 0 3px rgba(255, 255, 255, 0.3),
      0 0 6px rgba(255, 255, 255, 0.15);
  }
}
</style>
