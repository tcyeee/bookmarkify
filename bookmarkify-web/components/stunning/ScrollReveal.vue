<template>
  <div ref="scrollRef" class="reveal-container" v-bind="attrs">
    <slot :isVisible="isVisible" />
  </div>
</template>

<script lang="ts" setup>
import { useIntersectionObserver } from '@vueuse/core'

const props = withDefaults(
  defineProps<{
    threshold?: number
    rootMargin?: string
    once?: boolean
  }>(),
  {
    threshold: 0.2,
    rootMargin: '0px',
    once: false, // 每次进入视口都触发
  }
)

const attrs = useAttrs()
const scrollRef = ref<HTMLElement | null>(null)
const isVisible = ref(false)

const { stop } = useIntersectionObserver(
  scrollRef,
  ([entry]) => {
    if (!entry) return
    if (entry.isIntersecting) {
      isVisible.value = true
      if (props.once) stop()
    } else if (!props.once) {
      isVisible.value = false
    }
  },
  {
    threshold: props.threshold,
    root: null,
    rootMargin: props.rootMargin,
  }
)

onBeforeUnmount(() => stop())
</script>

<style scoped>
.reveal-container {
  min-height: 100vh;
}
</style>
