<template>
  <!-- 全新干净实现：基于已验证的 Pragmatic DnD 原型。无任何手搓遗留 CSS。 -->
  <TransitionGroup
    ref="gridRef"
    name="cell"
    tag="div"
    class="mx-auto flex flex-wrap content-start justify-start"
    :style="{ gap: `${gap}px`, width: gridWidth ? `${gridWidth}px` : undefined }">
    <div
      v-for="item in items"
      :key="`${item.id}-${item.type}`"
      :data-cell-id="item.id"
      class="board-cell relative"
      :class="{ 'opacity-40': draggingId === item.id }"
      :style="{ width: `${cellW}px`, height: `${cellH}px` }">
      <div
        class="flex h-full w-full items-start justify-center"
        :class="{ 'merge-glow-host': dropMode === 'folder' && dropTargetId === item.id }">
        <LaunchCell :item="item" :dragging="draggingId !== null" @open-dir="emit('open-dir', $event)" @show-detail="emit('show-detail', $event)" />
      </div>
      <span v-if="dropTargetId === item.id && dropMode === 'left'" class="insert-bar -left-1" />
      <span v-if="dropTargetId === item.id && dropMode === 'right'" class="insert-bar -right-1" />
    </div>
  </TransitionGroup>
</template>

<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch, nextTick } from 'vue'
import type { BookmarkShow, UserLayoutNodeVO } from '@typing'
import { HomeItemType } from '@typing'
import { usePreferenceStore } from '@stores/preference.store'
import { draggable, dropTargetForElements, monitorForElements } from '@atlaskit/pragmatic-drag-and-drop/element/adapter'
import { combine } from '@atlaskit/pragmatic-drag-and-drop/combine'
import { setCustomNativeDragPreview } from '@atlaskit/pragmatic-drag-and-drop/element/set-custom-native-drag-preview'
import { preserveOffsetOnSource } from '@atlaskit/pragmatic-drag-and-drop/element/preserve-offset-on-source'
import LaunchCell from './LaunchCell.vue'

/** 拖拽提交动作（本组件自带，不依赖任何旧文件） */
export type DragCommit =
  | { kind: 'reorder'; ids: string[] }
  | { kind: 'merge'; draggedId: string; targetId: string; index: number }
  | { kind: 'moveInto'; draggedId: string; folderId: string }

const props = defineProps<{
  items: UserLayoutNodeVO[]
  parentKey: string
  isFolder?: boolean // 文件夹内：禁套娃，folder 命中退化为排序
}>()
const emit = defineEmits<{
  (e: 'commit', c: DragCommit): void
  (e: 'open-dir', item: UserLayoutNodeVO): void
  (e: 'show-detail', b: BookmarkShow): void
}>()

const pref = usePreferenceStore()
const cellW = computed(() => pref.bookmarkCellSizePx)
const cellH = computed(() => pref.bookmarkCellSizePx + (pref.preference?.showTitle ? 28 : 0))
const gap = computed(() => pref.bookmarkGapPx)

// ── 定宽块：按父容器可用宽度算整数列数，网格取定宽 → 内部 justify-start 左对齐，
//    外层（页面 / 文件夹浮层）负责把这个定宽块居中。满行铺满、最后一行靠左。──
const availWidth = ref(0)
let ro: ResizeObserver | null = null
function measure() {
  const parent = (gridRef.value?.$el as HTMLElement | undefined)?.parentElement
  if (parent) availWidth.value = parent.clientWidth
}
const gridWidth = computed(() => {
  const avail = availWidth.value
  if (!avail) return undefined
  const pitch = cellW.value + gap.value
  const fit = Math.max(1, Math.floor((avail + gap.value) / pitch))
  const cols = Math.min(fit, props.items.length || 1) // 项目数不足一行时块只取实际列数，仍居中
  return cols * cellW.value + (cols - 1) * gap.value
})

const draggingId = ref<string | null>(null)
const dropTargetId = ref<string | null>(null)
const dropMode = ref<'folder' | 'left' | 'right' | null>(null)

const gridRef = ref<any>(null)
// monitor 必须 mount 时创建一次且全程稳定：pragmatic-dnd 只通知「创建早于拖拽开始」的 monitor。
// 若随 items 变化重建 monitor，拖拽进行中（如实时拖出导致 root items 变化）新 monitor 收不到本次 onDrop，
// reset() 不触发 → draggingId 残留、图标卡在半透明。故 cells 与 monitor 分开管理。
let cellsCleanup: (() => void) | null = null
let monitorCleanup: (() => void) | null = null

// 拖拽预览应只取「图标方块」，而非含标题与空白的整格。三种 cell 的图标都是
// cell 根节点（.flex.flex-col）的第一个子元素。
function findIconEl(cell: HTMLElement): HTMLElement | null {
  return (cell.querySelector<HTMLElement>('.flex.flex-col')?.firstElementChild as HTMLElement) ?? null
}

function classify(el: HTMLElement, clientX: number): 'folder' | 'left' | 'right' {
  const r = el.getBoundingClientRect()
  const ratio = (clientX - r.left) / r.width
  if (ratio >= 0.25 && ratio <= 0.75) return 'folder'
  return ratio < 0.25 ? 'left' : 'right'
}

function reset() {
  draggingId.value = null
  dropTargetId.value = null
  dropMode.value = null
}

// 仅注册各格子的 draggable / dropTarget；items 变化时重建（新增/移除格子）。
function registerCells() {
  cellsCleanup?.()
  const root = gridRef.value?.$el as HTMLElement | undefined
  const cells = Array.from(root?.querySelectorAll<HTMLElement>('[data-cell-id]') ?? [])
  const disposers = cells.map((el) => {
    const id = el.dataset.cellId!
    return combine(
      draggable({
        element: el,
        getInitialData: () => ({ id }),
        onGenerateDragPreview: ({ location, nativeSetDragImage }) => {
          const icon = findIconEl(el)
          if (!icon) return // 兜底：交回原生整格预览
          const rect = icon.getBoundingClientRect()
          setCustomNativeDragPreview({
            nativeSetDragImage,
            getOffset: preserveOffsetOnSource({ element: icon, input: location.current.input }),
            render: ({ container }) => {
              const clone = icon.cloneNode(true) as HTMLElement
              clone.style.width = `${rect.width}px`
              clone.style.height = `${rect.height}px`
              clone.style.margin = '0'
              container.appendChild(clone)
            },
          })
        },
        onDragStart: () => (draggingId.value = id),
        onDrop: reset,
      }),
      dropTargetForElements({
        element: el,
        canDrop: ({ source }) => source.data.id !== id,
        getData: () => ({ id }),
        onDrag: ({ location }) => {
          const mode = classify(el, location.current.input.clientX)
          dropTargetId.value = id
          dropMode.value = mode
        },
        onDragLeave: () => {
          if (dropTargetId.value === id) {
            dropTargetId.value = null
            dropMode.value = null
          }
        },
      }),
    )
  })
  cellsCleanup = combine(...disposers)
}

// monitor + 整块 board 落点目标，仅创建一次、全程稳定（回调读响应式 props.items，无需随 items 重建）。
function registerMonitor() {
  monitorCleanup?.()
  const root = gridRef.value?.$el as HTMLElement | undefined
  monitorCleanup = combine(
    // 覆盖整块网格的兜底落点：拖到格子之间的空白处也命中此目标，浏览器视为「已接受」，
    // 消除原生拖拽松手时「预览图飞回原点」的回弹动画（仅移出不排序时表现为 ~0.8s 停顿）。
    ...(root ? [dropTargetForElements({ element: root, getData: () => ({ zone: 'board', parentKey: props.parentKey }) })] : []),
    monitorForElements({
      // 暗显：无论拖拽源最初属于哪个 board，只要当前归属本 board（含「实时拖出」后并入根的情况），
      // 就把它标记为拖拽态（opacity-40）。源离开本 board 时清空。
      onDrag: ({ source }) => {
        const sid = String(source.data.id)
        if (props.items.some((n) => n.id === sid)) {
          if (draggingId.value !== sid) draggingId.value = sid
        } else if (draggingId.value !== null) {
          draggingId.value = null
        }
      },
      onDrop: ({ source, location }) => {
        reset()
        const target = location.current.dropTargets[0]
        const sourceId = String(source.data.id)
        if (!props.items.some((n) => n.id === sourceId)) return // 跨网格隔离
        // 落在空白兜底区（非具体格子）→ 仅接受、不排序（实时拖出时该项已占位在末尾）
        if (!target || target.data.zone === 'board') return
        const mode = classify(target.element as HTMLElement, location.current.input.clientX)
        decide(sourceId, String(target.data.id), mode)
      },
    }),
  )
}

function decide(sourceId: string, targetId: string, mode: 'folder' | 'left' | 'right') {
  if (sourceId === targetId) return
  const list = props.items
  const targetNode = list.find((n) => n.id === targetId)
  const ti = list.findIndex((n) => n.id === targetId)
  if (ti < 0) return

  if (mode === 'folder' && !props.isFolder) {
    if (targetNode?.type === HomeItemType.BOOKMARK_DIR) return emit('commit', { kind: 'moveInto', draggedId: sourceId, folderId: targetId })
    if (targetNode?.type === HomeItemType.BOOKMARK) return emit('commit', { kind: 'merge', draggedId: sourceId, targetId, index: ti })
  }

  const ids = list.map((n) => n.id).filter((x) => x !== sourceId)
  const newTi = ids.indexOf(targetId)
  ids.splice(mode === 'right' ? newTi + 1 : newTi, 0, sourceId)
  emit('commit', { kind: 'reorder', ids })
}

watch(
  () => props.items.map((n) => n.id).join(','),
  () => nextTick(registerCells),
)
onMounted(() =>
  nextTick(() => {
    registerCells()
    registerMonitor()
    measure()
    const parent = (gridRef.value?.$el as HTMLElement | undefined)?.parentElement
    if (parent) {
      ro = new ResizeObserver(measure)
      ro.observe(parent)
    }
  }),
)
onBeforeUnmount(() => {
  cellsCleanup?.()
  monitorCleanup?.()
  ro?.disconnect()
})
</script>

<style scoped>
.cell-move {
  transition: transform 0.26s cubic-bezier(0.2, 0, 0, 1);
}
.cell-enter-active,
.cell-leave-active {
  transition: all 0.2s ease;
}
.cell-enter-from,
.cell-leave-to {
  opacity: 0;
  transform: scale(0.6);
}
.cell-leave-active {
  position: absolute;
}

/* 仅抑制 img 自身被原生拖拽（拖拽源应是整个 cell）；cell 本身绝不加 user-drag:none */
.board-cell :deep(img) {
  -webkit-user-drag: none;
  user-select: none;
  pointer-events: none;
}

.insert-bar {
  position: absolute;
  top: 6%;
  height: 70%;
  width: 4px;
  border-radius: 2px;
  background: rgba(255, 255, 255, 0.9);
  pointer-events: none;
}
</style>
