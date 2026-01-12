<template>
  <div class="w-20 flex flex-col items-center" :class="{ 'justify-center': !showTitle }" @click="handleClick">
    <div class="w-app h-app flex justify-center items-center">
      <div class="rounded-xl shadow flex justify-center items-center" :class="iconWrapperClass" :style="logoSizeStyle">
        <span :class="iconClass" :style="iconSizeStyle" />
      </div>
    </div>
    <div v-if="showTitle" class="w-18 text-sm mt-[0.3rem] text-white opacity-90 truncate text-center">
      {{ label }}
    </div>
  </div>
</template>

<script lang="ts" setup>
import { computed, toRefs } from 'vue'
import type { BookmarkFunctionVO } from '@typing'
import { FunctionType } from '@typing'
import { usePreferenceStore } from '@stores/preference.store'

const props = defineProps<{ value: BookmarkFunctionVO; toggleDrag?: boolean }>()
const { value } = toRefs(props)
const preferenceStore = usePreferenceStore()
const iconAreaSize = computed(() => preferenceStore.bookmarkCellSizePx)
const showTitle = computed<boolean>(() => preferenceStore.preference?.showTitle ?? true)
const logoSizeStyle = computed(() => ({
  width: `${iconAreaSize.value}px`,
  height: `${iconAreaSize.value}px`,
}))

const label = computed(() => {
  if (!value.value) return '功能'
  switch (value.value.type) {
    case FunctionType.SETTING:
      return '设置'
    default:
      return '功能'
  }
})

const iconClass = computed(() => {
  if (!value.value) return 'icon--memory-dot-hexagon-fill text-white'
  switch (value.value.type) {
    case FunctionType.SETTING:
      return 'icon--memory-dot-hexagon-fill text-white'
    default:
      return 'icon--memory-dot-hexagon-fill text-white'
  }
})

const iconPixelSize = computed(() => Math.round(iconAreaSize.value * 0.5))
const iconSizeStyle = computed(() => ({
  width: `${iconPixelSize.value}px`,
  height: `${iconPixelSize.value}px`,
}))

const iconWrapperClass = computed(() => {
  if (!value.value) return 'bg-slate-900/80'
  switch (value.value.type) {
    case FunctionType.SETTING:
      return 'bg-slate-900/80'
    default:
      return 'bg-slate-900/80'
  }
})

function handleClick() {
  if (props.toggleDrag) return
  if (!value.value) return
  switch (value.value.type) {
    case FunctionType.SETTING:
      navigateTo('/setting')
      break
    default:
      break
  }
}
</script>
