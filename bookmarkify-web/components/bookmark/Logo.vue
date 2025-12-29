<template>
  <div class="h-20 w-20 rounded-2xl bg-white flex justify-center items-center">
    <img v-if="bookmark.iconHdUrl && !hdError" :src="bookmark.iconHdUrl" alt="" @error="onHdError" />
    <img
      v-else-if="!iconError"
      class="w-8 h-8"
      :src="`data:image/png;base64,${bookmark.iconBase64}`"
      alt=""
      @error="onIconError" />
    <img v-else class="w-8 h-8" src="/avatar/default.png" alt="" />
  </div>
</template>

<script lang="ts" setup>
import { ref } from 'vue'
import type { Bookmark } from '@typing'
const props = defineProps<{ bookmark: Bookmark }>()

const hdError = ref(false)
const iconError = ref(false)

function onHdError() {
  hdError.value = true
}

function onIconError() {
  iconError.value = true
}
</script>
