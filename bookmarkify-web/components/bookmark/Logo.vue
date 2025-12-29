<template>
  <div class="h-20 w-20 rounded-2xl bg-white flex justify-center items-center">
    <img
      v-if="bookmark.hdSize"
      :src="`https://i.tcyeee.top/apps/bookmarkfiy/website/${bookmark.bookmarkId}/${bookmark.hdSize}.png`"
      alt="" />
    <img v-else class="w-8 h-8" :src="`data:image/png;base64,${bookmark.iconBase64}`" />
  </div>
</template>

<script lang="ts" setup>
import type { Bookmark } from '@typing'
const props = defineProps<{ bookmark: Bookmark }>()
</script>
