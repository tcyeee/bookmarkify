<template>
  <p
    :style="{ '--shimmer-width': `${shimmerWidth}px` }"
    :class="
      cn(
        'mx-auto max-w-md text-neutral-600/50 dark:text-neutral-400/50 ',
        'animate-shimmer bg-clip-text bg-no-repeat bg-position-[0_0] bg-size-[var(--shimmer-width)_100%] [transition:background-position_1s_cubic-bezier(.6,.6,0,1)_infinite]',
        'bg-linear-to-r from-transparent via-black/80 via-50% to-transparent dark:via-white/80'
      )
    ">
    <slot />
  </p>
</template>

<script lang="ts" setup>
import { cn } from '@utils'

withDefaults(defineProps<{ shimmerWidth: number }>(), { shimmerWidth: 100 })
</script>

<style scoped>
@keyframes shimmer {
  0%,
  90%,
  100% {
    background-position: calc(-100% - var(--shimmer-width)) 0;
  }
  30%,
  60% {
    background-position: calc(100% + var(--shimmer-width)) 0;
  }
}

.animate-shimmer {
  animation: shimmer 8s infinite;
}
</style>
