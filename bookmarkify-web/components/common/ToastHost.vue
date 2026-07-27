<template>
  <div class="cy-toast cy-toast-top cy-toast-center z-[9999]">
    <TransitionGroup name="toast">
      <div v-for="t in store.toasts" :key="t.id" class="cy-alert shadow-lg" :class="alertClass(t.type)">
        <Icon :icon="iconOf(t.type)" class="size-5 shrink-0" />
        <span>{{ t.message }}</span>
      </div>
    </TransitionGroup>
  </div>
</template>

<script lang="ts" setup>
import { useToastStore } from '@stores/toast.store'

const store = useToastStore()

function alertClass(type: string) {
  return {
    success: 'cy-alert-success',
    error: 'cy-alert-error',
    warning: 'cy-alert-warning',
    info: 'cy-alert-info',
  }[type]
}

function iconOf(type: string) {
  return (
    {
      success: 'memory:check-circle',
      error: 'memory:close-circle',
      warning: 'memory:alert-box-fill',
      info: 'memory:information',
    }[type] || 'memory:information'
  )
}
</script>

<style scoped>
.toast-enter-active,
.toast-leave-active {
  transition: all 0.25s ease;
}
.toast-enter-from,
.toast-leave-to {
  opacity: 0;
  transform: translateY(-8px);
}
</style>
