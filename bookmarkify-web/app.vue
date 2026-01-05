<template>
  <NuxtRouteAnnouncer />
  <NuxtLayout>
    <NuxtPage />
  </NuxtLayout>
  <CommonCommandPalette />
  <AddOneDialog />
  
</template>
<script setup lang="ts">
import { usePreferredDark } from '@vueuse/core'
import AddOneDialog from './components/launchpad/AddOneDialog.vue'
const isDark = usePreferredDark()

useHead(() => ({
  bodyAttrs: {
    class: isDark.value ? 'dark' : '',
    'data-theme': isDark.value ? 'dark' : 'light',
  },
}))
</script>
