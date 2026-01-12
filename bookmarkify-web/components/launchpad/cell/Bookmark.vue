<template>
  <div class="w-20 flex flex-col items-center" :class="{ 'justify-center': !showTitle }" @click="onClick">
    <BookmarkLogo :value="props.value" :size="iconSize" />
    <div
      v-if="showTitle"
      class="w-18 text-sm mt-[0.3rem] text-white opacity-90 truncate text-center">
      {{ props.value.title || props.value.urlBase }}
    </div>
  </div>
</template>

<script lang="ts" setup>
import { computed, toRefs } from 'vue'
import { BookmarkOpenMode, type BookmarkShow } from '@typing'
import { usePreferenceStore } from '@stores/preference.store'
import BookmarkLogo from './BookmarkLogo.vue'

const props = withDefaults(defineProps<{ value: BookmarkShow; showTitle?: boolean; toggleDrag?: boolean }>(), {
  showTitle: true,
  toggleDrag: false,
})
const { showTitle, toggleDrag } = toRefs(props)

const preferenceStore = usePreferenceStore()
const iconSize = computed(() => preferenceStore.bookmarkCellSizePx)
const bookmarkOpenMode = computed<BookmarkOpenMode>(
  () => preferenceStore.preference?.bookmarkOpenMode ?? BookmarkOpenMode.CURRENT_TAB
)

function onClick() {
  if (toggleDrag.value) return
  const target = bookmarkOpenMode.value === BookmarkOpenMode.NEW_TAB ? '_blank' : '_self'
  window.open(props.value.urlFull, target)
}
</script>
