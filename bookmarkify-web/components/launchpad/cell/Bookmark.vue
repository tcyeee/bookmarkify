<template>
  <div class="w-20 flex flex-col items-center" :class="{ 'justify-center': !showTitle }" @click="onClick">
    <!-- 加载状态 -->
    <div v-if="!props.value" class="loading-cell h-20 w-20 rounded-2xl skeleton-shimmer" />
    <!-- 正常书签状态 -->
    <BookmarkLogo v-else :value="props.value" :size="iconSize" />
    <div v-if="showTitle" class="w-18 text-sm mt-[0.3rem] truncate text-center" :class="props.value ? 'text-white opacity-90' : 'text-gray-300'">
      {{ props.value ? (props.value.title || props.value.urlBase) : props.tempTitle ?? 'loading...' }}
    </div>
  </div>
</template>

<script lang="ts" setup>
import { computed, toRefs } from 'vue'
import { BookmarkOpenMode, type BookmarkShow } from '@typing'
import { usePreferenceStore } from '@stores/preference.store'
import BookmarkLogo from './BookmarkLogo.vue'

const props = defineProps<{ value?: BookmarkShow | null; tempTitle?: string; toggleDrag?: boolean }>()
const { toggleDrag } = toRefs(props)

const preferenceStore = usePreferenceStore()
const showTitle = computed<boolean>(() => preferenceStore.preference?.showTitle ?? true)
const iconSize = computed(() => preferenceStore.bookmarkCellSizePx)
const bookmarkOpenMode = computed<BookmarkOpenMode>(
  () => preferenceStore.preference?.bookmarkOpenMode ?? BookmarkOpenMode.CURRENT_TAB
)

function onClick() {
  if (toggleDrag?.value || !props.value) return
  const target = bookmarkOpenMode.value === BookmarkOpenMode.NEW_TAB ? '_blank' : '_self'
  window.open(props.value.urlFull, target)
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
  background: linear-gradient(
    135deg,
    transparent 30%,
    rgba(255, 255, 255, 0.7) 50%,
    transparent 70%
  );
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
