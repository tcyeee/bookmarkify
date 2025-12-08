<template>
  <div class="space-y-6">
    <div>
      <div class="mb-2 text-sm font-medium text-slate-700">预设渐变</div>
      <div class="grid grid-cols-2 gap-3 sm:grid-cols-3 md:grid-cols-5">
        <button
          v-for="(preset, index) in presets"
          :key="index"
          type="button"
          :style="{ backgroundImage: `linear-gradient(135deg, ${preset.colors.join(', ')})` }"
          :class="[
            'aspect-square rounded-lg border-2 transition-all shadow-sm',
            isPresetActive(preset)
              ? 'border-blue-500 ring-2 ring-blue-200'
              : 'border-transparent hover:-translate-y-0.5 hover:shadow-md',
          ]"
          @click="selectPreset(preset)" />
      </div>
    </div>

    <div class="space-y-4">
      <div class="text-sm font-medium text-slate-700">自定义渐变</div>
      <div class="space-y-3">
        <div
          v-for="(color, index) in colors"
          :key="index"
          class="flex flex-col gap-2 rounded-lg border border-slate-100 bg-white p-3 shadow-sm sm:flex-row sm:items-center">
          <label class="text-xs font-medium text-slate-500 sm:w-20 sm:text-right">颜色 {{ index + 1 }}</label>
          <div class="flex flex-1 flex-col items-start gap-2 sm:flex-row sm:items-center">
            <input
              :value="color"
              type="color"
              class="h-10 w-16 cursor-pointer rounded border border-slate-200"
              @input="updateColor(index, ($event.target as HTMLInputElement).value)" />
            <input
              :value="color"
              type="text"
              class="w-full flex-1 rounded border border-slate-200 px-3 py-2 font-mono text-sm text-slate-700 shadow-sm focus:border-blue-500 focus:outline-none"
              placeholder="#000000"
              @input="updateColor(index, ($event.target as HTMLInputElement).value)" />
          </div>
          <button
            v-if="colors.length > 2"
            type="button"
            class="self-start rounded-lg border border-red-100 px-3 py-2 text-sm font-medium text-red-600 hover:bg-red-50"
            @click="removeColor(index)">
            删除
          </button>
        </div>
      </div>
      <button
        type="button"
        class="inline-flex items-center gap-2 rounded-lg border border-slate-200 bg-white px-3 py-2 text-sm font-medium text-slate-700 shadow-sm hover:border-slate-300 hover:bg-slate-50"
        @click="addColor">
        + 添加颜色
      </button>
    </div>

    <div class="space-y-2">
      <div class="text-sm font-medium text-slate-700">渐变方向</div>
      <input
        :value="direction"
        type="range"
        min="0"
        max="360"
        step="1"
        class="w-full accent-blue-500"
        @input="updateDirection(Number(($event.target as HTMLInputElement).value))" />
      <div class="text-center text-sm font-medium text-slate-600">{{ direction }}°</div>
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
import type { BacGradientVO } from '@typing'

const props = defineProps<{
  colors: string[]
  direction: number
  presets: BacGradientVO[]
  saving: boolean
  hasBackground: boolean
}>()

const emit = defineEmits<{
  (e: 'update:colors', value: string[]): void
  (e: 'update:direction', value: number): void
  (e: 'save'): void
  (e: 'reset'): void
}>()

function updateColor(index: number, value: string) {
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

function selectPreset(preset: BacGradientVO) {
  emit('update:colors', [...preset.colors])
  emit('update:direction', preset.direction ?? 135)
}

function updateDirection(value: number) {
  emit('update:direction', value)
}

function isPresetActive(preset: BacGradientVO) {
  return (
    props.colors.length === preset.colors.length &&
    props.colors.every((color, index) => color === preset.colors[index]) &&
    props.direction === (preset.direction ?? 135)
  )
}
</script>
