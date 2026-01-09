<template>
  {{ preferenceStore.preference }}
<!-- 完整APP列表 -->
<div ref="outerRef" class="flex w-full justify-center">
  <!-- APP列表容器：min-width 保证不被外层挤压 -->
  <div class="w-full flex justify-center" :style="gridContainerStyle">
      <!-- Vuuri 仅在客户端渲染，避免 SSR 阶段访问 DOM -->
      <ClientOnly>
        <Vuuri
          class="demo-grid min-h-[calc(var(--cell-size)+var(--cell-gap)+var(--title-height))]"
          :style="vuuriStyle"
          :model-value="pageData"
          item-key="id"
          :options="vuuriOptions"
          :drag-enabled="true"
          :get-item-width="getItemWidth"
          :get-item-height="getItemHeight"
          @input="onGridInput"
          @drag-start="onDragStart"
          @drag-release-end="onDragReleaseEnd">
          <template #item="{ item }">

            <div
              class="flex w-(--cell-size) flex-col items-center border border-gray-300 border-dashed"
              @click="onItemClick(item)">
              <div
                class="flex h-(--cell-size) w-(--cell-size) select-none items-center justify-center border-4 border-gray-300 rounded-3xl text-4xl font-bold text-white shadow opacity-80"
                :style="{ backgroundColor: item.color }">
                {{ item.value }}
              </div>
              <div
                class="mt-1 flex h-(--title-height) w-full items-center justify-center rounded bg-gray-200 text-sm font-semibold text-gray-600">
                APP-{{ item.value }}
              </div>
            </div>

              <!-- <LaunchpadCellFolder
                v-if="item.type === HomeItemType.BOOKMARK_DIR"
                :value="toBookmarkDir(item)"
                :show-title="showTitle"                />
              <LaunchpadCellBookmark
                v-else-if="item.type === HomeItemType.BOOKMARK"
                :value="item.typeApp"
                :show-title="showTitle"
                @click="openPage(item.typeApp)" />
              <LaunchpadCellBookmarkLoading
                v-else-if="item.type === HomeItemType.BOOKMARK_LOADING"
                :show-title="showTitle" /> -->
          </template>
        </Vuuri>
      </ClientOnly>
    </div>
  </div>
</template>

<script lang="ts" setup>
import {
  BookmarkOpenMode,
  HomeItemType,
  type BookmarkDir,
  type BookmarkShow,
  type UserLayoutNodeVO,
} from '@typing';
import { computed, defineAsyncComponent, defineComponent, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
definePageMeta({ middleware: 'auth',  layout: 'launch' })

type GridItem = { id: number; value: number; color: string }

const bookmarkStore = useBookmarkStore()
const preferenceStore = usePreferenceStore()

const pageData = computed<Array<UserLayoutNodeVO>>(() => bookmarkStore.layoutNode || [])
const showTitle = computed<boolean>(() => preferenceStore.preference?.showTitle ?? true)
const bookmarkOpenMode = computed<BookmarkOpenMode>(
  () => preferenceStore.preference?.bookmarkOpenMode ?? BookmarkOpenMode.CURRENT_TAB,
)

/** 单元格尺寸与间距，跟随用户偏好 */
const CELL_SIZE = computed(() => preferenceStore.bookmarkCellSizePx)
const CELL_GAP = computed(() => preferenceStore.bookmarkGapPx)

const TITLE_HEIGHT = 28
const COLUMN_WIDTH = computed(() => CELL_SIZE.value + CELL_GAP.value)

/** 客户端按需加载 Vuuri；服务端阶段返回空占位以规避报错 */
const Vuuri = import.meta.client
  ? defineAsyncComponent(() => import('vuuri'))
  : defineComponent({ name: 'VuuriPlaceholder', setup: () => () => null })

const items = ref<GridItem[]>([])
const dragState = reactive<{ dragging: boolean; justDropped: boolean; pendingOrder: string[] | null }>({
  dragging: false,
  justDropped: false,
  pendingOrder: null,
})
const outerRef = ref<HTMLElement | null>(null)
const columnCount = ref(1)
let resizeObserver: ResizeObserver | null = null

/** 将尺寸写入 CSS 变量，模板与样式保持一致 */
const gridStyle = computed(() => ({
  '--cell-size': `${CELL_SIZE.value}px`,
  '--cell-gap': `${CELL_GAP.value}px`,
  '--title-height': `${TITLE_HEIGHT}px`,
}))

/** 容器样式：携带 CSS 变量并确保宽度不小于内部网格 */
const gridContainerStyle = computed(() => ({
  ...gridStyle.value,
  minWidth: vuuriStyle.value.width,
}))

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

/** 告诉 Vuuri 每个 item 的宽高（含间距），用于正确计算列数与动画 */
const getItemWidth = () => `calc(var(--cell-size) + var(--cell-gap))`
const getItemHeight = () => `calc(var(--cell-size) + var(--cell-gap) + var(--title-height))`

/** 根据可用宽度重新计算列数，确保容器宽度与列数对齐 */
const recalcColumns = () => {
  const container = outerRef.value
  if (!container) return
  const available = container.clientWidth
  const next = Math.max(1, Math.floor((available + CELL_GAP.value) / COLUMN_WIDTH.value))
  columnCount.value = next
}

/** 初始化示例数据：50 个带随机色块的占位项 */
onMounted(() => {
  recalcColumns()
  watch([CELL_SIZE, CELL_GAP], () => recalcColumns())
  resizeObserver = new ResizeObserver(() => recalcColumns())
  if (outerRef.value) resizeObserver.observe(outerRef.value)
  window.addEventListener('resize', recalcColumns)

  items.value = Array.from({ length: 120 }, (_, idx) => ({
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

/** 点击 APP 时输出名称，便于调试 */
function onItemClick(item: GridItem) {
  // 拖拽完成瞬间可能触发 click，这里做保护
  if (dragState.dragging || dragState.justDropped) return
  console.log(`点击 APP：APP-${item.value}`)
}

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
function onGridInput(newItems: GridItem[]) {
  items.value = newItems
  if (dragState.dragging) {
    dragState.pendingOrder = newItems.map((it) => `APP-${it.value}`)
  }
}

function toBookmarkDir(item: UserLayoutNodeVO): BookmarkDir {
  return {
    name: item.name ?? '文件夹',
    bookmarkList: (item.children ?? [])
      .map((child) => child.typeApp)
      .filter((child): child is BookmarkShow => Boolean(child)),
  }
}

function openPage(bookmark: BookmarkShow) {
  if (dragState.dragging || dragState.justDropped) return
  const target = bookmarkOpenMode.value === BookmarkOpenMode.NEW_TAB ? '_blank' : '_self'
  window.open(bookmark.urlFull, target)
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
