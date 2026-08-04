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
        <div class="flex flex-col md:flex-row md:items-start gap-4 md:gap-6 lg:gap-8 pb-[10vh]">
          <!-- 窄屏下侧边栏放不下（240px 侧栏 + 间距会把正文挤到 80px 左右），改成横向滚动的标签条 -->
          <aside
            :class="[
              'w-full md:w-64 lg:w-72 shrink-0 space-y-3 md:space-y-6 md:sticky md:self-start transition-[top] duration-200 ease-out',
            ]"
            :style="asideStyle">
            <NuxtLink to="/" class="block w-full">
              <div
                class="cy-btn w-full cy-btn-sm md:cy-btn-xl cy-btn-ghost bg-white dark:bg-slate-900 dark:border-slate-700 rounded-xl text-base md:text-lg transition-colors">
                {{ $t('settingLayout.back') }}
              </div>
            </NuxtLink>

            <!-- 侧边栏 -->
            <div
              class="p-2 md:p-6 bg-white dark:bg-slate-900 dark:border dark:border-slate-800 rounded-xl md:rounded-2xl shadow-sm transition-colors">
              <ul
                ref="tabListRef"
                class="setting-tabs relative flex flex-row md:flex-col gap-1 md:gap-3 bg-white dark:bg-slate-900 w-full text-gray-500 dark:text-slate-400 text-base md:text-lg font-medium select-none overflow-x-auto md:overflow-hidden transition-colors">
                <!-- 滑块只在竖排下成立（它按 Y 轴平移），横排标签条改用各自的选中底色 -->
                <span
                  class="hidden md:block absolute left-0 right-0 rounded-lg bg-gray-100 dark:bg-slate-800 transition-[transform,height] duration-250 ease-out will-change-transform pointer-events-none"
                  :style="indicatorStyle"
                  aria-hidden="true" />
                <li v-for="tab in tabs" :key="tab.value" class="relative z-0 shrink-0 md:shrink">
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

          <main class="w-full flex-1 min-w-0">
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

function selectOne(index: number) {
  sysStore.settingTabIndex = index
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

<style scoped>
/* 窄屏标签条横向滚动：留着滚动条会在标签下方压出一条灰杠，手势本身已足够表达可滚动 */
.setting-tabs {
  scrollbar-width: none;
  -ms-overflow-style: none;
}
.setting-tabs::-webkit-scrollbar {
  display: none;
}
</style>
