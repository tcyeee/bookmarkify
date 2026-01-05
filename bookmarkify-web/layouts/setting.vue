<template>
  <CommonHeader
    :class="[
      'fixed top-0 z-10 w-full transition-transform duration-300 backdrop-blur bg-white/80 dark:bg-slate-950/80 border-b border-white/20 dark:border-slate-800 text-gray-900 dark:text-slate-100',
      { '-translate-y-full': isHeaderHidden },
    ]" />
  <div class="bg-gray-100 text-gray-900 dark:bg-slate-950 dark:text-slate-100 min-h-screen pt-28 flex flex-col transition-colors">
    <div class="flex-1">
      <div class="mx-auto w-full px-4 sm:px-6 lg:px-8" :style="containerStyle">
        <div class="flex items-start gap-6 lg:gap-8 pb-[10vh]">
          <aside
            :class="[
              'sticky self-start w-60 sm:w-64 lg:w-72 shrink-0 space-y-6 transition-[top] duration-200 ease-out',
            ]"
            :style="asideStyle">
            <NuxtLink to="/" class="block w-full">
              <div
                class="cy-btn w-full cy-btn-xl cy-btn-ghost bg-white dark:bg-slate-900 dark:border-slate-700 rounded-xl text-lg transition-colors">
                返回
              </div>
            </NuxtLink>

            <!-- 侧边栏 -->
            <div class="p-6 bg-white dark:bg-slate-900 dark:border dark:border-slate-800 rounded-2xl shadow-sm transition-colors">
              <ul
                ref="tabListRef"
                class="relative flex flex-col gap-3 bg-white dark:bg-slate-900 w-full text-gray-500 dark:text-slate-400 text-lg font-medium select-none overflow-hidden transition-colors">
                <span
                  class="absolute left-0 right-0 rounded-lg bg-gray-100 dark:bg-slate-800 transition-[transform,height] duration-250 ease-out will-change-transform pointer-events-none"
                  :style="indicatorStyle"
                  aria-hidden="true" />
                <li v-for="tab in tabs" :key="tab.value" class="relative z-0">
                  <a
                    :ref="setTabRef(tab.value)"
                    @click="selectOne(tab.value)"
                    class="relative z-10 flex items-center gap-3 px-4 py-3 rounded-lg transition-colors duration-200 ease-out hover:text-gray-800 hover:dark:text-slate-100"
                    :class="sysStore.settingTabIndex === tab.value ? 'cy-menu-active text-gray-900 dark:text-slate-100' : ''"
                    :aria-current="sysStore.settingTabIndex === tab.value ? 'page' : undefined">
                    <span :class="['shrink-0', tab.icon]"></span>
                    <span class="leading-6">{{ tab.label }}</span>
                  </a>
                </li>
              </ul>
            </div>
          </aside>

          <main class="flex-1 min-w-0">
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
const tabs = [
  { value: 0, label: '个人资料', icon: 'icon--memory-account-box icon-size-22' },
  { value: 1, label: '书签管理', icon: 'icon--memory-application-code icon-size-22' },
  { value: 2, label: '主页背景', icon: 'icon--memory-cloud icon-size-22' },
  { value: 3, label: '偏好设置', icon: 'icon--memory-toggle-switch-off icon-size-22' },
  { value: 4, label: '快捷键', icon: 'icon--memory-terminal icon-size-22' },
]
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
