<template>
  <!-- 页面容器：居中承载 Vuuri 网格 -->
  <div class="page-wrapper">
    <div class="grid-shell bg-amber-100" :style="gridStyle">
      <!-- Vuuri 仅在客户端渲染，避免 SSR 阶段访问 DOM -->
      <ClientOnly>
        <Vuuri
          class="demo-grid"
          :model-value="items"
          item-key="id"
          :options="vuuriOptions"
          :drag-enabled="false"
          :get-item-width="getItemWidth"
          :get-item-height="getItemHeight">
          <template #item="{ item }">
            <!-- 单个卡片：色块 + 标题，可作为后续拖拽的基础 -->
            <div class="demo-card">
              <div class="demo-card__logo" :style="{ backgroundColor: item.color }">
                {{ item.value }}
              </div>
              <div class="demo-card__label">
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
import { computed, defineAsyncComponent, defineComponent, onMounted, ref } from 'vue'

definePageMeta({ layout: 'launch' })

type GridItem = { id: number; value: number; color: string }

/** 单元格尺寸与间距，可按需集中调节 */
const CELL_SIZE = 120
const CELL_GAP = 20
const TITLE_HEIGHT = 28

/** 客户端按需加载 Vuuri；服务端阶段返回空占位以规避报错 */
const Vuuri = process.client
  ? defineAsyncComponent(() => import('vuuri'))
  : defineComponent({ name: 'VuuriPlaceholder', setup: () => () => null })

const items = ref<GridItem[]>([])

/** 将尺寸写入 CSS 变量，模板与样式保持一致 */
const gridStyle = computed(() => ({
  '--cell-size': `${CELL_SIZE}px`,
  '--cell-gap': `${CELL_GAP}px`,
  '--title-height': `${TITLE_HEIGHT}px`,
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
const getItemHeight = () =>
  `calc(var(--cell-size) + var(--cell-gap) + var(--title-height))`

/** 初始化示例数据：50 个带随机色块的占位项 */
onMounted(() => {
  items.value = Array.from({ length: 50 }, (_, idx) => ({
    id: idx + 1,
    value: idx + 1,
    color: randomColor(),
  }))
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
.page-wrapper {
  width: 100%;
  display: flex;
  justify-content: center;
  background: #f5f5f5;
}

.grid-shell {
  width: 100%;
  max-width: 1400px;
  padding: 32px 20px;
  box-sizing: border-box;
  --cell-size: 120px;
  --cell-gap: 20px;
  --title-height: 28px;
}

.demo-grid {
  width: 100%;
  min-height: calc(var(--cell-size) + var(--cell-gap) + var(--title-height));
}

/* 移除 Muuri 默认 margin，完全由 gap 控制间距 */
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

.demo-card {
  width: var(--cell-size);
}

.demo-card__logo {
  width: var(--cell-size);
  height: var(--cell-size);
  border-radius: 28px;
  color: #fff;
  font-size: 42px;
  font-weight: 700;
  display: flex;
  align-items: center;
  justify-content: center;
  box-shadow: 0 8px 24px rgba(0, 0, 0, 0.12);
  user-select: none;
}

.demo-card__label {
  margin-top: 8px;
  height: var(--title-height);
  display: flex;
  align-items: center;
  justify-content: center;
  border-radius: 12px;
  background: #e5e7eb;
  color: #4b5563;
  font-weight: 600;
  font-size: 14px;
}
</style>
