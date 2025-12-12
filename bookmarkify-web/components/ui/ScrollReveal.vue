<template>
  <div ref="scrollRef" class="max-w-5xl min-h-screen items-center flex justify-center relative">
    <slot :isVisible="targetIsVisible" ref="contentRef" />
  </div>
</template>

<script lang="ts" setup>
import { useElementVisibility } from '@vueuse/core'
import { useSmoothScroll } from './composables/useSmoothScroll'

const scrollRef = ref(null)

const targetIsVisible = useElementVisibility(scrollRef)

useSmoothScroll()
</script>

<style scoped></style>
