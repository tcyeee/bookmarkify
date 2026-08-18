<template>
  <CommonHeader
    :class="[
      'fixed top-0 z-10 w-full transition-transform duration-300 backdrop-blur bg-white/80 dark:bg-slate-950/80 border-b border-white/20 dark:border-slate-800 text-gray-900 dark:text-slate-100',
      { '-translate-y-full': isHeaderHidden },
    ]" />
  <div
    class="bg-gray-100 text-gray-900 dark:bg-slate-950 dark:text-slate-100 min-h-screen pt-20 md:pt-28 flex flex-col transition-colors">
    <div class="flex-1">
      <div class="mx-auto w-full px-3 sm:px-6 lg:px-8" :style="containerStyle">
        <div
          class="grid grid-cols-1 md:flex md:flex-row md:items-start gap-4 md:gap-6 lg:gap-8 pb-[10vh] overflow-hidden md:overflow-visible">
          <!-- 移动端把菜单和内容拆成两屏，纯靠水平位移做"推入/退出"的动画（不叠加透明度淡出，
               否则观感会被拉扯成一次淡入淡出而不是干净的左右移动）；两者叠在同一个 grid 单元格里
               （col-start-1 row-start-1）而不是用 display:none 互斥，这样容器高度始终取两者中较高的一个，
               动画过程中不会有高度跳变。位移把非活动面板完全推出可视区（配合外层 overflow-hidden），
               所以不需要靠透明度来隐藏它。桌面端 md:flex 接管后这套堆叠失效，两者按原先并排布局显示。 -->
          <aside
            :class="[
              'col-start-1 row-start-1 w-full md:w-64 lg:w-72 shrink-0 space-y-3 md:space-y-6 md:sticky md:self-start transition-transform duration-300 ease-out md:translate-x-0 md:pointer-events-auto',
              isMobileDetailView ? '-translate-x-full pointer-events-none' : 'translate-x-0 pointer-events-auto',
            ]"
            :inert="isAsideInert"
            :style="asideStyle">
            <!-- 返回首页：桌面端和移动端菜单页都固定在顶部 -->
            <NuxtLink to="/" class="block w-full">
              <div
                class="cy-btn w-full cy-btn-sm md:cy-btn-xl cy-btn-ghost bg-white dark:bg-slate-900 dark:border-slate-700 rounded-xl text-base md:text-lg transition-colors">
                {{ $t('settingLayout.back') }}
              </div>
            </NuxtLink>

            <!-- 菜单 -->
            <div
              class="p-2 md:p-6 bg-white dark:bg-slate-900 dark:border dark:border-slate-800 rounded-xl md:rounded-2xl shadow-sm transition-colors">
              <ul
                ref="tabListRef"
                class="relative flex flex-col gap-1 md:gap-3 bg-white dark:bg-slate-900 w-full text-gray-500 dark:text-slate-400 text-base md:text-lg font-medium select-none transition-colors">
                <span
                  class="absolute left-0 right-0 rounded-lg bg-gray-100 dark:bg-slate-800 transition-[transform,height] duration-250 ease-out will-change-transform pointer-events-none"
                  :style="indicatorStyle"
                  aria-hidden="true" />
                <li v-for="tab in tabs" :key="tab.value" class="relative z-0">
                  <a
                    :ref="setTabRef(tab.value)"
                    @click="selectOne(tab.value)"
                    class="relative z-10 flex items-center gap-2 md:gap-3 px-3 md:px-4 py-2.5 md:py-3 rounded-lg whitespace-nowrap transition-colors duration-200 ease-out hover:text-gray-800 hover:dark:text-slate-100"
                    :class="
                      sysStore.settingTabIndex === tab.value
                        ? 'cy-menu-active bg-gray-100 dark:bg-slate-800 md:bg-transparent md:dark:bg-transparent text-gray-900 dark:text-slate-100'
                        : ''
                    "
                    :aria-current="sysStore.settingTabIndex === tab.value ? 'page' : undefined">
                    <Icon :icon="tab.icon" class="shrink-0 size-5 md:size-[22px]" />
                    <span class="leading-6">{{ tab.label }}</span>
                  </a>
                </li>
              </ul>
            </div>
          </aside>

          <main
            :class="[
              'col-start-1 row-start-1 w-full flex-1 min-w-0 transition-transform duration-300 ease-out md:translate-x-0 md:pointer-events-auto',
              isMobileDetailView ? 'translate-x-0 pointer-events-auto' : 'translate-x-full pointer-events-none',
            ]"
            :inert="isMainInert">
            <!-- 移动端详情页：顶部返回按钮回到菜单列表，而不是首页 -->
            <button
              type="button"
              class="md:hidden cy-btn cy-btn-ghost cy-btn-sm gap-1 mb-3 px-2"
              @click="backToMenu">
              <Icon icon="mdi:chevron-left" class="size-5" />
              {{ $t('settingLayout.back') }}
            </button>
            <NuxtPage
              class="rounded-xl min-h-[70vh] bg-white dark:bg-slate-900 dark:border dark:border-slate-800 transition-colors" />
          </main>
        </div>
      </div>
    </div>
    <CommonFooter />
  </div>
</template>

<script lang="ts" setup>
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import type { CSSProperties, ComponentPublicInstance } from 'vue'
import { breakpointsTailwind, useBreakpoints } from '@vueuse/core'

const sysStore = useSysStore()

const isHeaderHidden = ref(false)
const lastScrollY = ref(0)
const SCROLL_THRESHOLD = 12
const containerStyle: CSSProperties = {
  maxWidth: 'clamp(960px, 80vw, 1280px)',
}
const { t } = useI18n()
const tabs = computed(() => [
  { value: 0, label: t('settingLayout.tabs.profile'), icon: 'mdi:account-box' },
  { value: 1, label: t('settingLayout.tabs.importBookmarks'), icon: 'mdi:upload' },
  { value: 2, label: t('settingLayout.tabs.background'), icon: 'mdi:cloud' },
  { value: 3, label: t('settingLayout.tabs.preference'), icon: 'mdi:toggle-switch-off' },
  // { value: 4, label: t('settingLayout.tabs.shortcuts'), icon: 'mdi:console' },
  { value: 5, label: t('settingLayout.tabs.bookmarkLibrary'), icon: 'mdi:bookmark-multiple' },
  { value: 6, label: t('settingLayout.tabs.shareManage'), icon: 'mdi:share-variant' },
  { value: 7, label: t('settingLayout.tabs.accessToken'), icon: 'mdi:key-variant' },
])
const indicatorStyle = ref<CSSProperties>({
  transform: 'translate3d(0, 0, 0)',
  height: '0px',
})
const tabRefs = new Map<number, HTMLElement>()
const tabListRef = ref<HTMLElement | null>(null)

const handleScroll = () => {
  if (import.meta.server) return
  const currentY = window.scrollY
  const delta = currentY - lastScrollY.value
  if (Math.abs(delta) > SCROLL_THRESHOLD) {
    isHeaderHidden.value = delta > 0 && currentY > 40
    lastScrollY.value = currentY
  }
}

onMounted(() => {
  if (import.meta.server) return
  lastScrollY.value = window.scrollY
  window.addEventListener('scroll', handleScroll, { passive: true })
  window.addEventListener('resize', handleResize, { passive: true })
  nextTick(updateIndicator)
})

onBeforeUnmount(() => {
  if (import.meta.server) return
  window.removeEventListener('scroll', handleScroll)
  window.removeEventListener('resize', handleResize)
})

// 移动端专用：是否已进入某个菜单项的详情视图。桌面端始终并排显示，不读这个状态。
// 每次进入 /setting 该 layout 都会重新挂载，所以默认值天然满足“默认进入菜单页”的要求，无需手动重置。
const isMobileDetailView = ref(false)

// aside/main 现在始终挂载在 DOM 里（靠位移切出屏幕做动画，而不是 v-if 卸载），
// 移动端下不在屏幕上的那一侧必须 inert，否则键盘 Tab 还能焦点到看不见、点不到的按钮上。
// 断点必须和 Tailwind 的 md（768px）对齐，且只在移动端生效——桌面端两侧始终可交互，
// 不能让 isMobileDetailView 的值（在窄屏下产生）意外让某一侧在宽屏下也变 inert。
const breakpoints = useBreakpoints(breakpointsTailwind)
const isMobileViewport = breakpoints.smaller('md')
const isAsideInert = computed(() => isMobileViewport.value && isMobileDetailView.value)
const isMainInert = computed(() => isMobileViewport.value && !isMobileDetailView.value)

function selectOne(index: number) {
  sysStore.settingTabIndex = index
  isMobileDetailView.value = true
}

function backToMenu() {
  isMobileDetailView.value = false
}

const setTabRef = (key: number) => (el: Element | ComponentPublicInstance | null) => {
  const htmlEl = el instanceof HTMLElement ? el : null
  if (!htmlEl) {
    tabRefs.delete(key)
    return
  }
  tabRefs.set(key, htmlEl)
}

const updateIndicator = () => {
  const el = tabRefs.get(sysStore.settingTabIndex)
  const listEl = tabListRef.value
  if (!el || !listEl) return
  const listRect = listEl.getBoundingClientRect()
  const elRect = el.getBoundingClientRect()
  const top = elRect.top - listRect.top + listEl.scrollTop
  indicatorStyle.value = {
    transform: `translate3d(0, ${top}px, 0)`,
    height: `${elRect.height}px`,
  }
}

const handleResize = () => {
  nextTick(updateIndicator)
}

const asideStyle = computed<CSSProperties>(() => ({
  top: isHeaderHidden.value ? '2rem' : '7rem',
}))

watch(
  () => sysStore.settingTabIndex,
  async () => {
    await nextTick()
    updateIndicator()
  }
)
</script>
