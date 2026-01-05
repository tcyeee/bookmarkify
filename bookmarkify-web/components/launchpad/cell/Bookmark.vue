<template>
  <div class="w-20 flex flex-col items-center" :class="{ 'justify-center': !showTitle }">
    <BookmarkLogo :value="props.value" />
    <div
      v-if="showTitle"
      class="w-18 text-sm mt-[0.3rem] text-white opacity-90 truncate text-center">
      {{ props.value.title || props.value.urlBase }}
    </div>
  </div>
</template>

<script lang="ts" setup>
import type { Bookmark } from '@typing'
import BookmarkLogo from './BookmarkLogo.vue'

const props = withDefaults(defineProps<{ value: Bookmark; showTitle?: boolean }>(), {
  showTitle: true,
})
const { showTitle } = toRefs(props)
</script>
