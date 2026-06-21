<template>
  <div ref="wrapperRef" class="flex w-full justify-center">
    <div
      ref="containerRef"
      class="relative"
      :style="{ width: `${layout.gridWidth.value}px`, height: `${layout.gridHeight(items.length)}px` }">
      <div
        v-for="(item, i) in orderedItems"
        :key="`${item.id}-${item.type}`"
        class="absolute select-none"
        :class="[item.id === drag.draggingId.value ? 'launch-cell-dragging' : 'transition-transform duration-200 ease-out']"
        :data-folder-anchor="item.id"
        :style="cellStyle(item, i)"
        @pointerdown="drag.onPointerDown($event, item.id)">
        <div class="h-full w-full" :class="{ 'merge-glow-host': drag.mergeReady.value && drag.mergeTargetId.value === item.id }">
          <LaunchCell :item="item" :dragging="cellDragging" @open-dir="emit('open-dir', $event)" @show-detail="emit('show-detail', $event)" />
        </div>
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
const emit = defineEmits<{
  (e: 'commit', c: DragCommit): void
  (e: 'open-dir', item: UserLayoutNodeVO): void
  (e: 'show-detail', b: BookmarkShow): void
}>()

const wrapperRef = ref<HTMLElement | null>(null) // 全宽，用于测量可用宽度算列数
const containerRef = ref<HTMLElement | null>(null) // 定位网格，宽度 = 列数×列宽
const layout = useGridLayout(wrapperRef)
const itemsRef = toRef(props, 'items')

// 真实拖拽刚结束后短暂置位，吞掉松手后浏览器补发的 click，避免误触发打开书签
const justDropped = ref(false)

const drag = useLaunchpadDrag({
  containerRef,
  items: itemsRef,
  layout,
  isFolder: props.isFolder ?? false,
  folderBoundsRef: computed(() => props.folderBoundsRef ?? null),
  onCommit: (c) => {
    if (c.kind !== 'none') {
      justDropped.value = true
      requestAnimationFrame(() => (justDropped.value = false))
    }
    emit('commit', c)
  },
})

const cellDragging = computed(() => drag.isDragging.value || justDropped.value)

// 拖拽中用 previewIds 顺序定位（非拖拽项让位动画），静止用真实 items
const orderedItems = computed<UserLayoutNodeVO[]>(() => {
  if (!drag.isDragging.value) return props.items
  const map = new Map(props.items.map((n) => [n.id, n]))
  return drag.previewIds.value.map((id) => map.get(id)).filter(Boolean) as UserLayoutNodeVO[]
})

function cellStyle(item: UserLayoutNodeVO, i: number) {
  const base = { width: `${layout.cellW.value}px`, height: `${layout.cellH.value}px` }
  if (item.id === drag.draggingId.value && drag.isDragging.value) {
    return {
      ...base,
      transform: `translate(${drag.pointer.value.x}px, ${drag.pointer.value.y}px) scale(1.08)`,
      zIndex: 50,
      pointerEvents: 'none' as const,
    }
  }
  const p = layout.posOf(i)
  return { ...base, transform: `translate(${p.x}px, ${p.y}px)` }
}
</script>

<style scoped>
.launch-cell-dragging {
  transition: none;
  opacity: 0.92;
}
</style>
