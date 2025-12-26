<template>
  <div class="bg-white p-8 w-full">
    <Transition name="fade-fast" mode="out-in">
      <component :is="currentComponent" :key="sysStore.settingTabIndex" />
    </Transition>
  </div>
</template>

<script lang="ts" setup>
import { computed } from 'vue'
import AccountProfile from '~/components/setting/account/AccountProfile.vue'
import SettingBookmarkManage from '~/components/setting/BookmarkManage.vue'
import SettingSettings from '~/components/setting/Settings.vue'

definePageMeta({
  middleware: 'auth',
  layout: 'setting',
})

const sysStore = useSysStore()
const components = [AccountProfile, SettingBookmarkManage, SettingSettings] as const
const currentComponent = computed(() => components[sysStore.settingTabIndex] ?? components[0])
</script>

<style scoped land="scss">
.min-h-screen {
  min-height: calc(100vh - 13rem);
}

:global(.fade-fast-enter-active),
:global(.fade-fast-leave-active) {
  transition: opacity 200ms ease, transform 200ms ease;
}

:global(.fade-fast-enter-from),
:global(.fade-fast-leave-to) {
  opacity: 0;
  transform: translateY(4px);
}
</style>
