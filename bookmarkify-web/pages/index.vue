<template>
  <!-- 完整APP列表 -->
  <div ref="outerRef" class="flex w-full justify-center">
    <!-- APP列表容器：min-width 保证不被外层挤压 -->
    <div class="w-full flex justify-center">
      <!-- Vuuri 仅在客户端渲染，避免 SSR 阶段访问 DOM -->
      <ClientOnly>
        <Vuuri
          :key="`${CELL_SIZE}-${CELL_GAP}-${TITLE_HEIGHT}`"
          class="demo-grid"
          :style="vuuriStyle"
          :model-value="pageData"
          item-key="id"
          :options="vuuriOptions"
          :drag-enabled="true"
          :get-item-width="() => `${CELL_SIZE + CELL_GAP}px`"
          :get-item-height="() => `${CELL_SIZE + CELL_GAP + TITLE_HEIGHT}px`"
          @input="onGridInput"
          @drag-start="onDragStart"
          @drag-release-end="onDragReleaseEnd">
          <template #item="{ item }">
            <LaunchItem :item="item" :toggle-drag="dragState.dragging || dragState.justDropped" />
          </template>
        </Vuuri>
      </ClientOnly>
    </div>
  </div>
</template>

<script lang="ts" setup>
import { type UserLayoutNodeVO } from '@typing'
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
  // 真实宽度 = 每列外宽相加 - 尾部多余 gap；与列数计算保持一致
  width: `${Math.max(1, columnCount.value) * COLUMN_WIDTH.value}px`,
}))

/** Vuuri 布局与动画配置 */
const vuuriOptions = {
  layout: { fillGaps: true, rounding: false },
  layoutDuration: 250,
  showDuration: 150,
  hideDuration: 150,
  dragReleaseDuration: 0,
  // 需要轻微移动才开始拖拽，避免单击被当作拖拽而吃掉 click
  dragStartPredicate: { distance: 8, delay: 0 },
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
})

/** Vuuri 拖拽开始与结束，用于避免点击冲突 */
function onDragStart() {
  dragState.dragging = true
  dragState.justDropped = false
  dragState.pendingOrder = null
}

function onDragReleaseEnd() {
  dragState.dragging = false
  dragState.justDropped = true
  if (dragState.pendingOrder) {
    console.log(`拖拽后的排序：${dragState.pendingOrder.join(', ')}`)
    dragState.pendingOrder = null
  }
  // 下一帧重置，避免释放瞬间触发点击
  requestAnimationFrame(() => {
    dragState.justDropped = false
  })
}

/** Vuuri input 事件：排序数据更新 */
function onGridInput(list: UserLayoutNodeVO[]) {
  bookmarkStore.layoutNode = list
  if (dragState.dragging) {
    // TODO,接入正式代码
    dragState.pendingOrder = list.map((it) => `APP-${it.name}`)
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
</style>
