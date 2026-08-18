<template>
  <div class="bg-white p-4 sm:p-6 lg:p-8 w-full">
    <Transition :name="contentTransitionName" mode="out-in">
      <component :is="currentComponent" :key="sysStore.settingTabIndex" />
    </Transition>
  </div>
</template>

<script lang="ts" setup>
import { computed } from 'vue'
import { breakpointsTailwind, useBreakpoints } from '@vueuse/core'
import AccountProfile from '~/components/setting/account/AccountProfile.vue'
import SettingBookmarkManage from '~/components/setting/BookmarkManage.vue'
import BackgroundSettings from '~/components/setting/BackgroundSettings.vue'
import PreferenceSettings from '~/components/setting/PreferenceSettings.vue'
import ShortcutSettings from '~/components/setting/ShortcutSettings.vue'
import BookmarkLibrary from '~/components/setting/BookmarkLibrary.vue'
import ShareManage from '~/components/setting/ShareManage.vue'
import AccessTokenManage from '~/components/setting/AccessTokenManage.vue'

definePageMeta({
  middleware: 'auth',
  layout: 'setting',
})

const sysStore = useSysStore()
const components = [
  AccountProfile,
  SettingBookmarkManage,
  BackgroundSettings,
  PreferenceSettings,
  ShortcutSettings,
  BookmarkLibrary,
  ShareManage,
  AccessTokenManage,
] as const
const currentComponent = computed(() => components[sysStore.settingTabIndex] ?? components[0])

// 移动端点菜单项时，settingTabIndex 的变化和"菜单→详情"揭示动画是同一次点击触发的：
// mode="out-in" 会先把上一次打开的旧内容播完离场动画才挂载新内容，于是揭示动画播放期间
// 用户看到的其实是上一个设置页，等它淡出才切到刚点的这个。桌面端两栏一直并排显示，
// 切 tab 时是在同一块可见区域里换内容，交叉淡出没问题，所以只在移动端关掉这层动画。
const breakpoints = useBreakpoints(breakpointsTailwind)
const isMobileViewport = breakpoints.smaller('md')
const contentTransitionName = computed(() => (isMobileViewport.value ? undefined : 'fade-fast'))
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
