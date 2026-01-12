<template>
  <div class="loading-cell h-20 w-20 rounded-2xl flex justify-center items-center">
    <span class="icon--memory-rotate-clockwise icon-size-35 text-gray-400 icon-spin" />
  </div>
  <div v-if="showTitle" class="text-gray-300 w-18 text-sm mt-[0.3rem] truncate text-center">loading...</div>
</template>

<script lang="ts" setup>
import { computed } from 'vue'
import { usePreferenceStore } from '@stores/preference.store'

const preferenceStore = usePreferenceStore()
const showTitle = computed<boolean>(() => preferenceStore.preference?.showTitle ?? true)
</script>

<style scoped>
.icon-spin {
  animation: rotate 1.2s linear infinite;
}

.loading-cell {
  background: #f8fafc;
  animation: bgPulse 2.2s ease-in-out infinite;
}

@keyframes rotate {
  to {
    transform: rotate(360deg);
  }
}

@keyframes bgPulse {
  0% {
    background-color: #f8fafc;
  }
  50% {
    background-color: #e5e7eb;
  }
  100% {
    background-color: #f8fafc;
  }
}
</style>
