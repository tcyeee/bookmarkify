<template>
  <!-- 整个APP列表 -->
  <div ref="outerRef" class="w-full flex justify-center bg-gray-100">
    <!-- APP列表的容器 -->
    <div
      class="grid"
      :style="{
        gap: `${gap}px`,
        width: `${gridWidth}px`,
        gridTemplateColumns: `repeat(${cols}, ${boxSize}px)`,
        visibility: ready ? 'visible' : 'hidden',
      }">
      <!-- APP列表的内部容器(无样式) -->
      <div class="contents">
        <!-- APP-ITEM -->
        <div
          v-for="item in items"
          :key="item.value"
          class="border border-gray-300 border-dashed flex-col">
          <!-- APP-LOGO -->
          <div
            class="text-center flex justify-center items-center border-4 rounded-3xl border-gray-300 text-white text-5xl font-semibold shadow opacity-80"
            :style="{
              width: `${boxSize}px`,
              height: `${boxSize}px`,
              backgroundColor: item.color,
            }">
            {{ item.value }}
          </div>
          <!-- APP-NAME -->
          <div class="text-center text-sm font-semibold bg-gray-200 rounded mt-1 text-gray-600">
            APP-{{ item.value }}
          </div>
        </div>
      </div>
    </div>
  </div>
</template>

<script lang="ts" setup>
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'

definePageMeta({ layout: 'launch' })

const boxSize = ref(120)
const gap = 20
const cols = ref(1)
const outerRef = ref<HTMLElement | null>(null)
const ready = ref(false)
const items = ref<{ value: number; color: string }[]>([])
let resizeObserver: ResizeObserver | null = null

const gridWidth = computed(() => cols.value * boxSize.value + Math.max(cols.value - 1, 0) * gap)

const recalcCols = () => {
  const container = outerRef.value
  if (!container) return
  const available = container.clientWidth
  const next = Math.max(1, Math.floor((available + gap) / (boxSize.value + gap)))
  cols.value = next
  ready.value = true
}

onMounted(() => {
  recalcCols()
  resizeObserver = new ResizeObserver(() => recalcCols())
  if (outerRef.value) resizeObserver.observe(outerRef.value)
  window.addEventListener('resize', recalcCols)
  items.value = Array.from({ length: 50 }, (_, idx) => ({
    value: idx + 1,
    color: randomColor(),
  }))
})

onBeforeUnmount(() => {
  resizeObserver?.disconnect()
  window.removeEventListener('resize', recalcCols)
})

const randomColor = () => {
  const h = Math.floor(Math.random() * 360)
  const s = 60 + Math.floor(Math.random() * 30) // 60-89%
  const l = 45 + Math.floor(Math.random() * 15) // 45-59%
  return `hsl(${h} ${s}% ${l}%)`
}
</script>
