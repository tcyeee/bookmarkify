<template>
  <button
    type="button"
    class="cy-button fixed bottom-6 right-6 z-50 flex h-14 w-14 items-center justify-center rounded-full bg-primary text-white shadow-lg transition hover:shadow-xl active:scale-95"
    aria-label="添加书签"
    @click="handleClick">
    <span class="relative flex h-6 w-6 items-center justify-center">
      <span class="icon--memory-gamepad icon-size-24 icon-default" aria-hidden="true" />
      <span class="icon--memory-gamepad-fill icon-size-24 icon-active" aria-hidden="true" />
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
</style>
