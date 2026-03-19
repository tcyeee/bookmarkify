<template>
  <!-- 完整APP列表 -->
  <div ref="outerRef" class="flex w-full justify-center">
    <!-- APP列表容器：min-width 保证不被外层挤压 -->
    <div class="w-full flex justify-center">
      <!-- Vuuri 仅在客户端渲染，避免 SSR 阶段访问 DOM -->
      <ClientOnly>
        <Vuuri :key="`${CELL_SIZE}-${CELL_GAP}-${TITLE_HEIGHT}-${gridKey}`" class="demo-grid" :style="vuuriStyle" :model-value="pageData" item-key="id" :options="vuuriOptions" :drag-enabled="true" :get-item-width="() => `${CELL_SIZE + CELL_GAP}px`" :get-item-height="() => `${CELL_SIZE + CELL_GAP + TITLE_HEIGHT}px`" @input="onGridInput" @drag-start="onDragStart" @drag-end="onDragEnd" @drag-release-end="onDragReleaseEnd">
          <template #item="{ item }">
            <LaunchItem :key="`${item.id}-${item.type}`" :item="item" :toggle-drag="dragState.dragging || dragState.justDropped" @show-detail="onShowDetail" />
          </template>
        </Vuuri>
      </ClientOnly>
    </div>
  </div>

  <el-dialog v-model="detailVisible" title="书签详情" width="480px" :close-on-click-modal="true">
    <LaunchpadDetail :data="detailBookmark" />
  </el-dialog>
</template>

<script lang="ts" setup>
import { bookmarksSort, bookmarksCreateDir } from '@api'
import { HomeItemType, type BookmarkShow, type UserLayoutNodeVO } from '@typing'
import { computed, defineAsyncComponent, defineComponent, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
definePageMeta({ middleware: 'auth', layout: 'launch' })

const bookmarkStore = useBookmarkStore()
const preferenceStore = usePreferenceStore()

const pageData = computed<Array<UserLayoutNodeVO>>(() => bookmarkStore.layoutNode || [])

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

const gridKey = ref(0)

const dragState = reactive<{ dragging: boolean; justDropped: boolean; pendingOrder: string[] | null }>({
  dragging: false,
  justDropped: false,
  pendingOrder: null,
})
const outerRef = ref<HTMLElement | null>(null)
const columnCount = ref(1)
let resizeObserver: ResizeObserver | null = null

/** 控制 Vuuri 容器宽度，使列在左右留白时仍居中 */
const vuuriStyle = computed(() => ({
  width: `${Math.max(1, columnCount.value) * COLUMN_WIDTH.value}px`,
}))

// ── 合并/创建文件夹 状态 ──────────────────────────────────────────────────────
const mergeTargetId = ref<string | null>(null)
let mergeTargetEl: HTMLElement | null = null  // .muuri-item 容器
let mergeIconEl: HTMLElement | null = null    // BookmarkLogo 根 div（overflow-hidden）
const mergeReady = ref(false)                 // 悬停满 300ms，可触发合并
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
  mergeReady.value = false
}

type OverlapResult = { targetId: string; targetEl: HTMLElement; index: number; grid: any }

/**
 * 合并意图检测：当拖动图标的中心点落在目标图标的中心 50% 区域内时触发。
 * 相当于把目标图标等分成 4 份，只有落在中间 2 份时才算"精准放置"。
 * 仅检测 BOOKMARK 类型目标（不允许拖到文件夹/功能按钮上）。
 */
function findMergeTarget(item: any): OverlapResult | null {
  const grid = item.getGrid?.()
  if (!grid) return null
  const dragEl = item.getElement?.() as HTMLElement | undefined
  if (!dragEl) return null
  const dr = dragEl.getBoundingClientRect()
  // 以拖动图标的中心点作为判断基准
  const cx = dr.left + dr.width / 2
  const cy = dr.top + dr.height / 2

  const items: any[] = grid.getItems()
  for (let i = 0; i < items.length; i++) {
    const targetItem = items[i]
    if (targetItem === item || !targetItem.isActive?.()) continue
    const el = targetItem.getElement?.() as HTMLElement | undefined
    if (!el) continue
    const r = el.getBoundingClientRect()
    // 目标图标中心区域：水平/垂直各取内侧 50%（25%~75%）
    const zx1 = r.left + r.width * 0.25
    const zx2 = r.left + r.width * 0.75
    const zy1 = r.top + r.height * 0.25
    const zy2 = r.top + r.height * 0.75
    if (cx < zx1 || cx > zx2 || cy < zy1 || cy > zy2) continue

    const targetId = el.dataset.itemKey
    if (!targetId) continue
    const targetNode = bookmarkStore.layoutNode?.find((n) => n.id === targetId)
    if (targetNode?.type !== HomeItemType.BOOKMARK) continue
    return { targetId, targetEl: el, index: i, grid }
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
  return { targetId: '', targetEl: bestItem.getElement(), index: bestIndex, grid }
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

    // 仅 BOOKMARK 类型可触发合并
    const draggedNode = bookmarkStore.layoutNode?.find((n) => n.id === currentDraggedId)
    const canMerge = draggedNode?.type === HomeItemType.BOOKMARK

    // ① 优先检测合并意图（中心点命中目标内圈）
    const mergeTarget = canMerge ? findMergeTarget(item) : null

    if (mergeTarget) {
      // 目标发生变化：重新开始 300ms 计时
      if (mergeTarget.targetId !== mergeTargetId.value) {
        clearMergeState()
        mergeTargetId.value = mergeTarget.targetId
        mergeTargetEl = mergeTarget.targetEl
        mergeTimer = setTimeout(() => {
          mergeReady.value = true
          const iconEl = mergeTargetEl?.querySelector('div.overflow-hidden') as HTMLElement | null
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
  dragState.pendingOrder = null
  currentDraggedId = (item?.getElement?.() as HTMLElement | undefined)?.dataset?.itemKey ?? ''
}

/**
 * dragEnd 先于 dragReleaseEnd 触发，在此处理合并逻辑。
 * 若是合并操作，清除 pendingOrder 避免 onDragReleaseEnd 再走排序。
 */
function onDragEnd() {
  if (mergeReady.value && mergeTargetId.value && currentDraggedId) {
    const draggedId = currentDraggedId
    const targetId = mergeTargetId.value
    clearMergeState()
    dragState.pendingOrder = null
    createFolder(draggedId, targetId)
  } else {
    clearMergeState()
  }
}

/** 释放动画完成后：处理排序和拖拽状态重置（合并逻辑已在 onDragEnd 处理） */
function onDragReleaseEnd() {
  dragState.dragging = false
  dragState.justDropped = true

  if (dragState.pendingOrder) {
    const params: Record<string, number> = {}
    bookmarkStore.layoutNode?.forEach((node, index) => {
      params[node.id] = index
    })
    bookmarksSort(params)
    dragState.pendingOrder = null
  }

  requestAnimationFrame(() => {
    dragState.justDropped = false
  })
}

/** Vuuri input 事件：排序数据更新 */
function onGridInput(list: UserLayoutNodeVO[]) {
  bookmarkStore.layoutNode = list
  if (dragState.dragging) dragState.pendingOrder = list.map((it, index) => `${it.id},${index + 1}`)
}

// ── 创建文件夹 ────────────────────────────────────────────────────────────────
async function createFolder(draggedId: string, targetId: string) {
  const nodes = bookmarkStore.layoutNode ?? []
  const draggedNode = nodes.find((n) => n.id === draggedId)
  const targetNode = nodes.find((n) => n.id === targetId)
  if (!draggedNode || !targetNode) return

  const sort = nodes.findIndex((n) => n.id === draggedId)

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
