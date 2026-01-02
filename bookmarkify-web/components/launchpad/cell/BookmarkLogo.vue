<template>
  <div
    class="w-app h-app rounded-xl bg-gray-100 center shadow overflow-hidden"
    :class="isDev ? 'border-4 border-dashed border-red-200' : ''">
    <div class="h-20 w-20 rounded-2xl bg-white flex justify-center items-center">
      <img v-if="props.value.iconHdUrl && !hdError" :src="props.value.iconHdUrl" alt="" @error="onHdError" />
      <img
        v-else-if="!iconError"
        class="w-8 h-8"
        :src="`data:image/png;base64,${props.value.iconBase64}`"
        alt=""
        @error="onIconError" />
      <img v-else class="w-8 h-8" src="/avatar/default.png" alt="" />
    </div>
  </div>
</template>

<script lang="ts" setup>
import { computed, ref } from 'vue'
import type { Bookmark } from '@typing'

const props = defineProps<{  value: Bookmark}>()

const hdError = ref(false)
const iconError = ref(false)
const isDev = computed(() => isLocalhostOrIP(props.value.urlFull))

function onHdError() {
  hdError.value = true
}

function onIconError() {
  iconError.value = true
}

function isLocalhostOrIP(url: string): boolean {
  const localhostRegex = /^(localhost|127\.0\.0\.1|::1)$/i
  const ipRegex = /^(?:[0-9]{1,3}\.){3}[0-9]{1,3}$/

  try {
    const hostname = new URL(url).hostname
    return localhostRegex.test(hostname) || ipRegex.test(hostname)
  } catch {
    return false
  }
}
</script>

