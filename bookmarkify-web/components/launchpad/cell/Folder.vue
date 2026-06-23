<template>
  <div class="flex flex-col items-center" :class="{ 'justify-center': !showTitle }" @click="onClick">
    <div
      class="rounded-xl bg-white/20 flex flex-wrap content-center justify-center shadow overflow-hidden folder-icon"
      :style="folderSizeStyle">
      <template v-if="previewChildren.length > 0">
        <BookmarkLogo
          v-for="child in previewChildren"
          :key="child.id"
          :value="child.typeApp!"
          :size="miniItemSize" />
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
import BookmarkLogo from './BookmarkLogo.vue'

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

// 最多展示 4 个（仅取有书签详情的子节点，交给 BookmarkLogo 统一渲染）
const previewChildren = computed(() =>
  (value.value.children ?? []).filter((child) => child.typeApp).slice(0, 4),
)

const miniItemSize = computed(() => {
  const padding = Math.round(folderAreaSize.value * 0.1) * 2
  const gap = Math.round(folderAreaSize.value * 0.05)
  return Math.floor((folderAreaSize.value - padding - gap) / 2)
})

function onClick() {
  if (toggleDrag?.value) return
  emit('open-dir')
}
</script>
