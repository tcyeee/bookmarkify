<template>
  <NuxtRouteAnnouncer />
  <NuxtLayout>
    <NuxtPage />
  </NuxtLayout>
  <CommonCommandPalette />
  <CommonToastHost />
  <CommonConfirmDialog />
  <CommonMovePickerDialog />
  <AddOneDialog />
</template>
<script setup lang="ts">
import { usePreferredDark } from '@vueuse/core'
import AddOneDialog from './components/launchpad/AddOneDialog.vue'
import { useThemeStore } from '@stores/theme.store'

// 主题只有这一个落点。命令面板过去是直接改 document.body 的 class，那份修改既不持久化，
// 也会被这里的下一次 useHead 求值原样盖回去 —— 看起来能切，刷新或换页就没了。
const prefersDark = usePreferredDark()
const themeStore = useThemeStore()
const isDark = computed(() => (themeStore.mode === 'system' ? prefersDark.value : themeStore.mode === 'dark'))

useHead(() => ({
  bodyAttrs: {
    class: isDark.value ? 'dark' : '',
    'data-theme': isDark.value ? 'dark' : 'light',
  },
}))
</script>
