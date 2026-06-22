<template>
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
      class="launch-cell relative"
      :class="{ 'opacity-40': drag.draggingId.value === item.id }"
      :style="{ width: `${cellW}px`, height: `${cellH}px` }"
      @dragstart.prevent>
      <div
        class="flex h-full w-full items-start justify-center"
        :class="{ 'merge-glow-host': drag.dropMode.value === 'folder' && drag.dropTargetId.value === item.id }">
        <LaunchCell :item="item" :dragging="drag.draggingId.value !== null" @open-dir="emit('open-dir', $event)" @show-detail="emit('show-detail', $event)" />
      </div>
      <!-- 排序插入指示线 -->
      <span v-if="drag.dropTargetId.value === item.id && drag.dropMode.value === 'left'" class="insert-bar -left-1" />
      <span v-if="drag.dropTargetId.value === item.id && drag.dropMode.value === 'right'" class="insert-bar -right-1" />
    </div>
  </TransitionGroup>
</template>

<script setup lang="ts">
import { computed, ref } from 'vue'
import type { BookmarkShow, UserLayoutNodeVO } from '@typing'
import { usePreferenceStore } from '@stores/preference.store'
import { useLaunchpadDrag, type DragCommit } from '@/composables/useLaunchpadDrag'
import LaunchCell from './LaunchCell.vue'

const props = defineProps<{
  items: UserLayoutNodeVO[]
  parentKey: string
  /** 文件夹内为 true：禁套娃，folder 命中退化为排序 */
  isFolder?: boolean
  /** 可选最大宽度（文件夹浮层内用卡片宽约束列数） */
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

const gridRef = ref<any>(null)
const itemsRef = computed(() => props.items)

const drag = useLaunchpadDrag({
  gridEl: () => (gridRef.value?.$el as HTMLElement) ?? null,
  items: itemsRef,
  allowFolder: !props.isFolder,
  onCommit: (c) => emit('commit', c),
})
</script>

<style scoped>
/* 重排位移动画（FLIP，TransitionGroup 自动加 transform 过渡）*/
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

.launch-cell {
  touch-action: none;
  -webkit-user-drag: none;
}
.launch-cell :deep(img) {
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
