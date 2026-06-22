<template>
  <!-- 全新干净实现：基于已验证的 Pragmatic DnD 原型。无任何手搓遗留 CSS。 -->
  <TransitionGroup
    ref="gridRef"
    name="cell"
    tag="div"
    class="flex flex-wrap content-start justify-center"
    :style="{ gap: `${gap}px`, maxWidth: maxWidth ? `${maxWidth}px` : undefined }">
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
import LaunchCell from './LaunchCell.vue'

/** 拖拽提交动作（本组件自带，不依赖任何旧文件） */
export type DragCommit =
  | { kind: 'reorder'; ids: string[] }
  | { kind: 'merge'; draggedId: string; targetId: string; index: number }
  | { kind: 'moveInto'; draggedId: string; folderId: string }
  | { kind: 'eject'; draggedId: string }

const props = defineProps<{
  items: UserLayoutNodeVO[]
  parentKey: string
  isFolder?: boolean // 文件夹内：禁套娃，folder 命中退化为排序
  maxWidth?: number
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

const draggingId = ref<string | null>(null)
const dropTargetId = ref<string | null>(null)
const dropMode = ref<'folder' | 'left' | 'right' | null>(null)

const gridRef = ref<any>(null)
let cleanup: (() => void) | null = null

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

function register() {
  cleanup?.()
  const root = gridRef.value?.$el as HTMLElement | undefined
  const cells = Array.from(root?.querySelectorAll<HTMLElement>('[data-cell-id]') ?? [])
  const disposers = cells.map((el) => {
    const id = el.dataset.cellId!
    return combine(
      draggable({
        element: el,
        getInitialData: () => ({ id }),
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
  cleanup = combine(
    ...disposers,
    monitorForElements({
      onDrop: ({ source, location }) => {
        reset()
        const target = location.current.dropTargets[0]
        const sourceId = String(source.data.id)
        if (!props.items.some((n) => n.id === sourceId)) return // 跨网格隔离
        if (!target) return
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
  () => nextTick(register),
)
onMounted(() => nextTick(register))
onBeforeUnmount(() => cleanup?.())
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
