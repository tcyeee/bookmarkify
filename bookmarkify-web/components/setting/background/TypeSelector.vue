<template>
  <div class="w-full">
    <div class="mb-2 text-sm font-medium text-slate-700">背景类型</div>
    <div class="grid grid-cols-2 gap-2">
      <button
        v-for="option in options"
        :key="option.value"
        type="button"
        :class="[
          'flex items-center justify-center gap-2 rounded-lg border px-4 py-3 text-sm font-medium transition-all',
          props.modelValue === option.value
            ? 'border-blue-500 bg-blue-50 text-blue-600 shadow-sm'
            : 'border-slate-200 bg-white text-slate-600 hover:border-slate-300 hover:bg-slate-50',
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
