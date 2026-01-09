<template>
  <div ref="outerRef" class="w-full flex justify-center bg-amber-200">
    <div
      class="grid"
      :style="{
        gap: `${gap}px`,
        width: `${gridWidth}px`,
        gridTemplateColumns: `repeat(${cols}, ${boxSize}px)`,
        visibility: ready ? 'visible' : 'hidden'
      }"
    >
      <div class="contents">
        <slot />
      </div>
    </div>
  </div>
</template>

<script lang="ts" setup>
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'

const boxSize = ref(150)
const gap = 0
const cols = ref(1)
const outerRef = ref<HTMLElement | null>(null)
const ready = ref(false)

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
})

onBeforeUnmount(() => {
  resizeObserver?.disconnect()
  window.removeEventListener('resize', recalcCols)
})
</script>
