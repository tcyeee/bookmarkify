<template>
  <div class="flex flex-col items-center" :class="{ 'justify-center': !showTitle }" @click="onClick">
    <div
      class="rounded-xl bg-white/20 flex flex-wrap content-center justify-center shadow overflow-hidden folder-icon"
      :style="folderSizeStyle">
      <template v-if="previewChildren.length > 0">
        <div
          v-for="child in previewChildren"
          :key="child.id"
          class="rounded-[18%] overflow-hidden bg-gray-200"
          :style="miniItemStyle">
          <img
            v-if="child.typeApp?.iconHdUrl"
            :src="child.typeApp.iconHdUrl"
            class="w-full h-full object-contain"
            alt="" />
          <img
            v-else-if="child.typeApp?.iconBase64"
            :src="buildBase64Src(child.typeApp.iconBase64)"
            class="w-full h-full object-contain"
            alt="" />
          <div v-else class="w-full h-full bg-gray-300" />
        </div>
      </template>
      <span v-else class="text-xs text-white/60">空</span>
    </div>
    <div v-if="showTitle" class="text-xs mt-[0.3rem] text-white opacity-90 truncate text-center" :style="{ width: `${folderAreaSize}px` }">
      {{ value.name || '文件夹' }}
    </div>
  </div>
</template>

<script lang="ts" setup>
import { computed, toRefs } from 'vue'
import type { UserLayoutNodeVO } from '@typing'
import { usePreferenceStore } from '@stores/preference.store'

const emit = defineEmits<{ (e: 'open-dir'): void }>()

const props = defineProps<{ value: UserLayoutNodeVO; toggleDrag?: boolean }>()
const { value, toggleDrag } = toRefs(props)

const preferenceStore = usePreferenceStore()
const showTitle = computed<boolean>(() => preferenceStore.preference?.showTitle ?? true)
const folderAreaSize = computed(() => preferenceStore.bookmarkCellSizePx)

const folderSizeStyle = computed(() => ({
  width: `${folderAreaSize.value}px`,
  height: `${folderAreaSize.value}px`,
  padding: `${Math.round(folderAreaSize.value * 0.1)}px`,
  gap: `${Math.round(folderAreaSize.value * 0.05)}px`,
}))

// 最多展示 4 个
const previewChildren = computed(() => (value.value.children ?? []).slice(0, 4))

const miniItemSize = computed(() => {
  const padding = Math.round(folderAreaSize.value * 0.1) * 2
  const gap = Math.round(folderAreaSize.value * 0.05)
  return Math.floor((folderAreaSize.value - padding - gap) / 2)
})

const miniItemStyle = computed(() => ({
  width: `${miniItemSize.value}px`,
  height: `${miniItemSize.value}px`,
}))

function buildBase64Src(base64: string): string {
  if (!base64) return ''
  const trimmed = base64.trim()
  if (trimmed.startsWith('data:')) return trimmed
  return `data:image/png;base64,${trimmed}`
}

function onClick() {
  if (toggleDrag?.value) return
  emit('open-dir')
}
</script>
