<template>
  <div class="w-20 flex flex-col items-center" :class="{ 'justify-center': !showTitle }">
    <div class="w-app h-app rounded-xl bg-gray-300 flex justify-center items-center shadow">
      <div>
        <div class="flex mb-[0.4rem]">
          <BookmarkLogo v-if="value.bookmarkList.length > 0" sm :bookmark="value.bookmarkList[0]!" class="mr-[0.4rem]" />
          <BookmarkLogo v-if="value.bookmarkList.length > 1" sm :bookmark="value.bookmarkList[1]!" />
        </div>
        <div class="flex">
          <BookmarkLogo v-if="value.bookmarkList.length > 2" sm :bookmark="value.bookmarkList[2]!" class="mr-[0.4rem]" />
          <BookmarkLogo v-if="value.bookmarkList.length > 3" sm :bookmark="value.bookmarkList[3]!" />
        </div>
      </div>
    </div>
    <div v-if="showTitle" class="w-18 text-xs mt-[0.3rem] text-gray-800 truncate text-center">{{ value.name }}</div>
  </div>
</template>

<script lang="ts" setup>
import type { Bookmark, BookmarkDir } from '@typing'

const props = withDefaults(defineProps<{ value: BookmarkDir; showTitle?: boolean }>(), {
  showTitle: true,
})
const { showTitle, value } = toRefs(props)

reactive<{ bookmarks: Array<Bookmark> }>({
  bookmarks: [],
})
</script>
