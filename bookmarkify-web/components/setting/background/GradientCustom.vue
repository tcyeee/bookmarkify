<template>
  <div class="space-y-6">
    <div class="text-sm font-medium text-slate-700 dark:text-slate-200">自定义渐变</div>
    <div class="space-y-3">
      <div
        v-for="(color, index) in colors"
        :key="index"
        class="flex flex-col gap-2 rounded-lg border border-slate-100 bg-white p-3 shadow-sm sm:flex-row sm:items-center dark:border-slate-800 dark:bg-slate-900">
        <label class="text-xs font-medium text-slate-500 sm:w-20 sm:text-right dark:text-slate-400">颜色 {{ index + 1 }}</label>
        <div class="flex flex-1 flex-col items-start gap-2 sm:flex-row sm:items-center">
          <input
            :value="color"
            type="color"
            class="h-10 w-16 cursor-pointer rounded border border-slate-200 dark:border-slate-700 dark:bg-slate-800"
            @input="onColorChange(index, ($event.target as HTMLInputElement).value)" />
          <input
            :value="color"
            type="text"
            class="w-full flex-1 rounded border border-slate-200 px-3 py-2 font-mono text-sm text-slate-700 shadow-sm focus:border-blue-500 focus:outline-none dark:border-slate-700 dark:bg-slate-900 dark:text-slate-100 dark:placeholder:text-slate-500 dark:focus:border-blue-400"
            placeholder="#000000"
            @input="onColorChange(index, ($event.target as HTMLInputElement).value)" />
        </div>
        <button
          v-if="colors.length > 2"
          type="button"
          class="self-start rounded-lg border border-red-100 px-3 py-2 text-sm font-medium text-red-600 hover:bg-red-50 dark:border-red-500/30 dark:text-red-200 dark:hover:bg-red-500/10"
          @click="removeColor(index)">
          删除
        </button>
      </div>
    </div>
    <div class="flex flex-wrap items-center gap-3">
      <button
        type="button"
        class="inline-flex items-center gap-2 rounded-lg border border-slate-200 bg-white px-3 py-2 text-sm font-medium text-slate-700 shadow-sm hover:border-slate-300 hover:bg-slate-50 dark:border-slate-700 dark:bg-slate-900 dark:text-slate-100 dark:hover:border-slate-600 dark:hover:bg-slate-800"
        @click="addColor">
        + 添加颜色
      </button>
      <div class="flex-1" />
      <div class="space-y-2 w-full sm:w-auto sm:min-w-[220px]">
        <div class="text-sm font-medium text-slate-700 dark:text-slate-200">渐变方向</div>
        <input
          :value="direction"
          type="range"
          min="0"
          max="360"
          step="1"
          class="w-full accent-blue-500"
          @input="onDirectionChange(Number(($event.target as HTMLInputElement).value))" />
        <div class="text-center text-sm font-medium text-slate-600 dark:text-slate-300">{{ direction }}°</div>
      </div>
    </div>

    <div class="flex flex-wrap items-center justify-center gap-3">
      <button type="button" class="cy-btn cy-btn-accent" :disabled="saving" @click="$emit('save')">
        <span v-if="saving">保存中...</span>
        <span v-else>保存渐变背景</span>
      </button>
      <button v-if="hasBackground" type="button" class="cy-btn cy-btn-ghost" :disabled="saving" @click="$emit('reset')">
        恢复默认
      </button>
    </div>
  </div>
</template>

<script lang="ts" setup>
const props = defineProps<{
  colors: string[]
  direction: number
  saving: boolean
  hasBackground: boolean
}>()

const emit = defineEmits<{
  (e: 'update:colors', value: string[]): void
  (e: 'update:direction', value: number): void
  (e: 'save'): void
  (e: 'reset'): void
}>()

function onColorChange(index: number, value: string) {
  const next = [...props.colors]
  next[index] = value
  emit('update:colors', next)
}

function addColor() {
  emit('update:colors', [...props.colors, '#000000'])
}

function removeColor(index: number) {
  if (props.colors.length <= 2) return
  emit(
    'update:colors',
    props.colors.filter((_, i) => i !== index)
  )
}

function onDirectionChange(value: number) {
  emit('update:direction', value)
}
</script>

