<template>
  <Transition name="fade-fast">
    <div
      v-if="store.active"
      class="fixed top-4 right-4 z-[9999] w-72 rounded-xl border border-slate-200 dark:border-slate-700 bg-white dark:bg-slate-800 shadow-lg overflow-hidden">
      <div class="flex items-start gap-3 px-4 py-3">
        <Icon
          :icon="store.done ? 'memory:check-circle' : 'memory:rotate-clockwise'"
          class="size-5 mt-0.5 shrink-0"
          :class="store.done ? 'text-emerald-500' : 'text-sky-500 animate-spin'" />
        <div class="flex-1 min-w-0">
          <p class="text-sm font-medium text-slate-700 dark:text-slate-200">
            {{ store.done ? '导入完成' : '正在导入书签…' }}
          </p>
          <p class="mt-0.5 text-xs text-slate-400 dark:text-slate-500">已解析 {{ store.resolved }}/{{ store.total }} 个</p>
        </div>
        <button
          type="button"
          class="shrink-0 text-slate-400 hover:text-slate-600 dark:hover:text-slate-300"
          aria-label="关闭"
          @click="store.dismiss()">
          <Icon icon="memory:close" class="size-4" />
        </button>
      </div>
      <div class="h-1 bg-slate-100 dark:bg-slate-700">
        <div
          class="h-full bg-sky-500 transition-all duration-300"
          :class="{ 'bg-emerald-500': store.done }"
          :style="{ width: `${progressPct}%` }" />
      </div>
    </div>
  </Transition>
</template>

<script lang="ts" setup>
const store = useImportProgressStore()

const progressPct = computed(() => (store.total === 0 ? 0 : Math.min(100, Math.round((store.resolved / store.total) * 100))))
</script>

<style scoped>
.fade-fast-enter-active,
.fade-fast-leave-active {
  transition: opacity 200ms ease, transform 200ms ease;
}
.fade-fast-enter-from,
.fade-fast-leave-to {
  opacity: 0;
  transform: translateY(-8px);
}
</style>
