<template>
  <div class="w-full text-slate-900 dark:text-slate-100 transition-colors">
    <div class="mb-2 text-sm font-medium text-slate-700 dark:text-slate-200">背景类型</div>
    <div class="grid grid-cols-2 gap-2">
      <button
        v-for="option in options"
        :key="option.value"
        type="button"
        :class="[
          'flex items-center justify-center gap-2 rounded-lg border px-4 py-3 text-sm font-medium transition-all',
          props.modelValue === option.value
            ? 'border-blue-500 bg-blue-50 text-blue-600 shadow-sm dark:bg-blue-900/30 dark:text-blue-100 dark:border-blue-400'
            : 'border-slate-200 bg-white text-slate-600 hover:border-slate-300 hover:bg-slate-50 dark:border-slate-700 dark:bg-slate-900 dark:text-slate-300 dark:hover:border-slate-600 dark:hover:bg-slate-800',
        ]"
        @click="select(option.value)">
        <span>{{ option.label }}</span>
      </button>
    </div>
  </div>
</template>

<script lang="ts" setup>
import { BackgroundType } from '@typing'

const props = defineProps<{
  modelValue: BackgroundType
}>()

const emit = defineEmits<{
  (e: 'update:modelValue', value: BackgroundType): void
}>()

const options = [
  { label: '渐变背景', value: BackgroundType.GRADIENT },
  { label: '图片背景', value: BackgroundType.IMAGE },
]

function select(value: BackgroundType) {
  emit('update:modelValue', value)
}
</script>
