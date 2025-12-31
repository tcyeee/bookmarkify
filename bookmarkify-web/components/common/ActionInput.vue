<template>
  <div class="flex items-end gap-3">
    <label class="block flex-1 min-w-0">
      <span class="text-sm text-slate-600 dark:text-slate-300">{{ label }}</span>
      <input
        v-model="inputValue"
        :type="type"
        :maxlength="maxLength"
        :placeholder="placeholder"
        :disabled="disabled"
        class="mt-1 h-12 w-full rounded-lg border border-slate-200 bg-white px-3 text-sm focus:border-slate-400 focus:outline-none focus:ring-2 focus:ring-slate-200 dark:border-slate-700 dark:bg-slate-900 dark:text-slate-100 dark:placeholder:text-slate-500 dark:focus:border-slate-500 dark:focus:ring-slate-600" />
    </label>
    <div
      class="overflow-hidden transition-[width] duration-200 ease-out flex justify-end gap-2"
      :style="buttonWrapperStyle">
      <button
        class="cy-btn cy-btn-accent h-12 px-5 min-w-24 transition duration-180 ease-out"
        :style="buttonStyle"
        @click="emit('primary')"
        :disabled="busy || disabled">
        <span v-if="busy">{{ primaryLoadingText }}</span>
        <span v-else>{{ primaryText }}</span>
      </button>
      <button
        class="cy-btn cy-btn-ghost h-12 px-4 min-w-20 transition duration-180 ease-out"
        :style="buttonStyle"
        @click="emit('secondary')"
        :disabled="busy || disabled">
        {{ secondaryText }}
      </button>
    </div>
  </div>
</template>

<script lang="ts" setup>
import { computed, type CSSProperties } from 'vue'

defineOptions({ name: 'ActionInput' })

const props = withDefaults(
  defineProps<{
    modelValue: string
    label: string
    placeholder?: string
    maxLength?: number
    dirty: boolean
    busy?: boolean
    type?: string
    primaryText?: string
    primaryLoadingText?: string
    secondaryText?: string
    disabled?: boolean
    actionWidth?: number
  }>(),
  {
    placeholder: '',
    maxLength: undefined,
    busy: false,
    type: 'text',
    primaryText: '保存',
    primaryLoadingText: '保存中...',
    secondaryText: '取消',
    disabled: false,
    actionWidth: 190,
  }
)

const emit = defineEmits<{
  (e: 'update:modelValue', value: string): void
  (e: 'primary'): void
  (e: 'secondary'): void
}>()

const inputValue = computed({
  get: () => props.modelValue,
  set: (value: string) => emit('update:modelValue', value),
})

const buttonWrapperStyle = computed(() => ({
  width: props.dirty ? `${props.actionWidth}px` : '0px',
}))

const buttonStyle = computed<CSSProperties>(() => ({
  opacity: props.dirty ? 1 : 0,
  transform: props.dirty ? 'translateX(0)' : 'translateX(10px)',
  pointerEvents: props.dirty ? 'auto' : 'none',
}))
</script>

