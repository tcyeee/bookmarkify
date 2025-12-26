<template>
  <NuxtRouteAnnouncer />
  <NuxtLayout>
    <NuxtPage />
  </NuxtLayout>
  <CommonCommandPalette />
</template>
<script setup lang="ts">
import { usePreferredDark } from '@vueuse/core'

const isDark = usePreferredDark()

useHead(() => ({
  bodyAttrs: {
    class: isDark.value ? 'dark' : '',
    'data-theme': isDark.value ? 'dark' : 'light',
  },
}))

onMounted(() => webSocketInit())

function webSocketInit() {
  // const webSocketStore = useWebSocketStore();
  // webSocketStore.connect();
}
</script>
