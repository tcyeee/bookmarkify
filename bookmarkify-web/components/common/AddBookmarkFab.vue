<template>
  <button
    type="button"
    class="cy-button fixed bottom-6 right-6 z-50 flex h-20 w-20 items-center justify-center rounded-full bg-primary text-white shadow-lg transition hover:shadow-xl active:scale-95"
    aria-label="添加书签"
    @click="handleClick">
    <span class="relative flex items-center justify-center">
      <span class="icon--memory-gamepad icon-size-35 icon-default" aria-hidden="true" />
      <span class="icon--memory-gamepad-fill icon-size-35 icon-active" aria-hidden="true" />
    </span>
  </button>
</template>

<script setup lang="ts">
import { useSysStore } from '@stores/sys.store'

const sysStore = useSysStore()

const handleClick = () => {
  sysStore.addBookmarkDialogVisible = true
}
</script>

<style scoped>
.cy-button {
  transition: transform 0.18s ease, box-shadow 0.25s ease, background-color 0.2s ease;
  animation: float 2.6s ease-in-out infinite;
}

.cy-button:hover {
  transform: translateY(-2px) scale(1.03);
  box-shadow: 0 12px 28px -12px rgba(0, 0, 0, 0.35);
}

.cy-button:active {
  transform: translateY(0) scale(0.95);
  animation-play-state: paused;
}

.cy-button:focus-visible {
  outline: none;
  box-shadow: 0 0 0 4px rgba(59, 130, 246, 0.35);
}

.cy-button .icon-active {
  display: none;
}

.cy-button:hover .icon-default,
.cy-button:active .icon-default,
.cy-button:focus-visible .icon-default {
  display: none;
}

.cy-button:hover .icon-active,
.cy-button:active .icon-active,
.cy-button:focus-visible .icon-active {
  display: inline-block;
}

.cy-button:hover .icon-active,
.cy-button:focus-visible .icon-active {
  animation: pop 0.32s ease;
}

@keyframes float {
  0%,
  100% {
    transform: translateY(0);
  }
  50% {
    transform: translateY(-3px);
  }
}

@keyframes pop {
  0% {
    transform: scale(0.85);
  }
  55% {
    transform: scale(1.1);
  }
  100% {
    transform: scale(1);
  }
}
</style>
