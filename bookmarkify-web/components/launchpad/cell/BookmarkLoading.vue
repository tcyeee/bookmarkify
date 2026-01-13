<template>
  <div class="loading-cell h-20 w-20 rounded-2xl skeleton-shimmer" />
  <div v-if="showTitle" class="text-gray-300 w-18 text-sm mt-[0.3rem] truncate text-center">loading...</div>
</template>

<script lang="ts" setup>
import { computed } from 'vue'
import { usePreferenceStore } from '@stores/preference.store'

const preferenceStore = usePreferenceStore()
const showTitle = computed<boolean>(() => preferenceStore.preference?.showTitle ?? true)
</script>

<style scoped>
.loading-cell {
  background: #e5e7eb;
  position: relative;
  overflow: hidden;
}

.skeleton-shimmer::before {
  content: '';
  position: absolute;
  top: -50%;
  left: -50%;
  width: 200%;
  height: 200%;
  background: linear-gradient(
    135deg,
    transparent 30%,
    rgba(255, 255, 255, 0.7) 50%,
    transparent 70%
  );
  animation: shimmer 1.5s linear infinite;
}

@keyframes shimmer {
  0% {
    transform: translate(-50%, -50%);
  }
  100% {
    transform: translate(50%, 50%);
  }
}
</style>
