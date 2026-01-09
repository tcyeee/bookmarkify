<template>
  <!-- 完整APP列表 -->
  <div ref="outerRef" class="flex w-full justify-center bg-gray-100">
    <!-- APP列表容器 -->
    <div class="w-full flex justify-center" :style="gridStyle">
      <!-- Vuuri 仅在客户端渲染，避免 SSR 阶段访问 DOM -->
      <ClientOnly>
        <Vuuri
          class="demo-grid min-h-[calc(var(--cell-size)+var(--cell-gap)+var(--title-height))] bg-gray-400"
          :style="vuuriStyle"
          :model-value="items"
          item-key="id"
          :options="vuuriOptions"
          :drag-enabled="true"
          :get-item-width="getItemWidth"
          :get-item-height="getItemHeight">
          <template #item="{ item }">
            <!-- APP_LOGO和APP_标题 -->
            <div class="flex w-(--cell-size) flex-col items-center border border-gray-300 border-dashed">
              <!-- APP_LOGO -->
              <div
                class="flex h-(--cell-size) w-(--cell-size) select-none items-center justify-center border-4 border-gray-300 rounded-3xl text-4xl font-bold text-white shadow opacity-80"
                :style="{ backgroundColor: item.color }">
                {{ item.value }}
              </div>
              <!-- APP_标题 -->
              <div
                class="mt-1 flex h-(--title-height) w-full items-center justify-center rounded bg-gray-200 text-sm font-semibold text-gray-600">
                APP-{{ item.value }}
              </div>
            </div>
          </template>
        </Vuuri>
      </ClientOnly>
    </div>
  </div>
</template>

<script lang="ts" setup>
/**
 * 使用 Vuuri 重写首页网格：
 * - Vuuri 是 Muuri 的 Vue 适配器，支持自动填补空隙、动画过渡。
 * - 客户端按需加载，避免 SSR 阶段因缺少 DOM / window 报错。
 * - item 尺寸统一由回调返回，保持与 CSS 变量同步，便于后续统一调节。
 */
import { computed, defineAsyncComponent, defineComponent, onBeforeUnmount, onMounted, ref } from 'vue'

definePageMeta({ layout: 'launch' })

type GridItem = { id: number; value: number; color: string }

/** 单元格尺寸与间距，可按需集中调节 */
const CELL_SIZE = 120
const CELL_GAP = 20
const TITLE_HEIGHT = 28
const COLUMN_WIDTH = CELL_SIZE + CELL_GAP

/** 客户端按需加载 Vuuri；服务端阶段返回空占位以规避报错 */
const Vuuri = import.meta.client
  ? defineAsyncComponent(() => import('vuuri'))
  : defineComponent({ name: 'VuuriPlaceholder', setup: () => () => null })

const items = ref<GridItem[]>([])
const outerRef = ref<HTMLElement | null>(null)
const columnCount = ref(1)
let resizeObserver: ResizeObserver | null = null

/** 将尺寸写入 CSS 变量，模板与样式保持一致 */
const gridStyle = computed(() => ({
  '--cell-size': `${CELL_SIZE}px`,
  '--cell-gap': `${CELL_GAP}px`,
  '--title-height': `${TITLE_HEIGHT}px`,
}))

/** 控制 Vuuri 容器宽度，使列在左右留白时仍居中 */
const vuuriStyle = computed(() => ({
  width: `${columnCount.value * COLUMN_WIDTH}px`,
}))

/** Vuuri 布局与动画配置 */
const vuuriOptions = {
  layout: { fillGaps: true, rounding: false },
  layoutDuration: 250,
  showDuration: 150,
  hideDuration: 150,
  dragReleaseDuration: 0,
}

/** 告诉 Vuuri 每个 item 的宽高（含间距），用于正确计算列数与动画 */
const getItemWidth = () => `calc(var(--cell-size) + var(--cell-gap))`
const getItemHeight = () => `calc(var(--cell-size) + var(--cell-gap) + var(--title-height))`

/** 根据可用宽度重新计算列数，确保容器宽度与列数对齐 */
const recalcColumns = () => {
  const container = outerRef.value
  if (!container) return
  const available = container.clientWidth
  const next = Math.max(1, Math.floor((available + CELL_GAP) / COLUMN_WIDTH))
  columnCount.value = next
}

/** 初始化示例数据：50 个带随机色块的占位项 */
onMounted(() => {
  recalcColumns()
  resizeObserver = new ResizeObserver(() => recalcColumns())
  if (outerRef.value) resizeObserver.observe(outerRef.value)
  window.addEventListener('resize', recalcColumns)

  items.value = Array.from({ length: 50 }, (_, idx) => ({
    id: idx + 1,
    value: idx + 1,
    color: randomColor(),
  }))
})

onBeforeUnmount(() => {
  resizeObserver?.disconnect()
  window.removeEventListener('resize', recalcColumns)
})

/** 生成柔和的 HSL 随机色，避免过亮或过暗 */
function randomColor() {
  const h = Math.floor(Math.random() * 360)
  const s = 65 + Math.floor(Math.random() * 20) // 65-84%
  const l = 48 + Math.floor(Math.random() * 12) // 48-59%
  return `hsl(${h} ${s}% ${l}%)`
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
