<template>
  <div class="w-20 flex flex-col items-center" :class="{ 'justify-center': !showTitle }" @click="onClick">
    <!-- 加载状态 -->
    <div v-if="!props.value" class="relative h-20 w-20">
      <div class="loading-cell h-20 w-20 rounded-2xl skeleton-shimmer" />
      <button
        class="cy-btn cy-btn-circle cy-btn-xs absolute -top-2 -right-2 z-10 bg-red-500 hover:bg-red-600 border-none text-white shadow"
        @click.stop="onDelete">
        <svg xmlns="http://www.w3.org/2000/svg" class="h-3 w-3" viewBox="0 0 20 20" fill="currentColor">
          <path
            fill-rule="evenodd"
            d="M4.293 4.293a1 1 0 011.414 0L10 8.586l4.293-4.293a1 1 0 111.414 1.414L11.414 10l4.293 4.293a1 1 0 01-1.414 1.414L10 11.414l-4.293 4.293a1 1 0 01-1.414-1.414L8.586 10 4.293 5.707a1 1 0 010-1.414z"
            clip-rule="evenodd" />
        </svg>
      </button>
    </div>
    <!-- 正常书签状态 -->
    <BookmarkLogo v-else :value="props.value" :size="iconSize" />
    <div
      v-if="showTitle"
      class="w-18 text-sm mt-[0.3rem] truncate text-center"
      :class="props.value ? 'text-white opacity-90' : 'text-gray-300'">
      {{ props.value ? props.value.title || props.value.urlBase : (props.tempTitle ?? 'loading...') }}
    </div>
  </div>
</template>

<script lang="ts" setup>
import { computed, toRefs } from 'vue'
import { BookmarkOpenMode, type BookmarkShow } from '@typing'
import { usePreferenceStore } from '@stores/preference.store'
import { bookmarksDel } from '@api'
import BookmarkLogo from './BookmarkLogo.vue'

const props = defineProps<{ value?: BookmarkShow | null; tempTitle?: string; toggleDrag?: boolean; nodeId?: string }>()
const { toggleDrag } = toRefs(props)

const preferenceStore = usePreferenceStore()
const showTitle = computed<boolean>(() => preferenceStore.preference?.showTitle ?? true)
const iconSize = computed(() => preferenceStore.bookmarkCellSizePx)
const bookmarkOpenMode = computed<BookmarkOpenMode>(
  () => preferenceStore.preference?.bookmarkOpenMode ?? BookmarkOpenMode.CURRENT_TAB,
)

function onClick() {
  if (toggleDrag?.value || !props.value) return
  const target = bookmarkOpenMode.value === BookmarkOpenMode.NEW_TAB ? '_blank' : '_self'
  window.open(props.value.urlFull, target)
}

async function onDelete() {
  if (!props.nodeId) return
  const bookmarkStore = useBookmarkStore()
  try {
    await bookmarksDel([props.nodeId])
    const index = bookmarkStore.layoutNode.findIndex((it) => it.id === props.nodeId)
    if (index !== -1) bookmarkStore.layoutNode.splice(index, 1)
  } catch (error) {
    console.error('[Bookmark] 删除加载中书签失败', error)
  }
}
</script>

<style scoped>
.loading-cell {
  background: #e5e7eb;
  position: relative;
  overflow: hidden;
}

.skeleton-shimmer::before {
  content: '';
  position: absolute;
  top: -50%;
  left: -50%;
  width: 200%;
  height: 200%;
  background: linear-gradient(135deg, transparent 30%, rgba(255, 255, 255, 0.7) 50%, transparent 70%);
  animation: shimmer 1.5s linear infinite;
}

@keyframes shimmer {
  0% {
    transform: translate(-50%, -50%);
  }
  100% {
    transform: translate(50%, 50%);
  }
}
</style>
