<template>
  <CommonHeader :class="['fixed top-0 z-10 w-full transition-transform duration-300', { '-translate-y-full': isHeaderHidden }]" />
  <div class="bg-gray-100 min-h-screen pt-28 flex flex-col">
    <div class="flex-1">
      <div class="mx-auto w-full px-4 sm:px-6 lg:px-8" :style="containerStyle">
        <div class="flex items-start gap-6 lg:gap-8 pb-[10vh]">
          <aside class="w-60 sm:w-64 lg:w-72 shrink-0">
            <NuxtLink to="/" class="block w-full">
              <div class="cy-btn w-full cy-btn-xl cy-btn-ghost bg-white rounded-xl mb-6 text-lg">返回</div>
            </NuxtLink>

            <!-- 侧边栏 -->
            <ul class="flex flex-col gap-3 bg-white rounded-xl w-full p-6 text-gray-500 text-lg font-medium select-none">
              <li>
                <a
                  @click="selectOne(0)"
                  class="flex items-center gap-3 px-4 py-3 rounded-lg transition-colors duration-150 hover:bg-gray-100/80 hover:text-gray-800"
                  :class="sysStore.settingTabIndex == 0 ? 'cy-menu-active bg-gray-100 text-gray-900' : ''">
                  <span class="icon--memory-account-box icon-size-22 shrink-0"></span>
                  <span class="leading-6">个人资料</span>
                </a>
              </li>
              <li>
                <a
                  @click="selectOne(1)"
                  class="flex items-center gap-3 px-4 py-3 rounded-lg transition-colors duration-150 hover:bg-gray-100/80 hover:text-gray-800"
                  :class="sysStore.settingTabIndex == 1 ? 'cy-menu-active bg-gray-100 text-gray-900' : ''">
                  <span class="icon--memory-application-code icon-size-22 shrink-0"></span>
                  <span class="leading-6">书签管理</span>
                </a>
              </li>
              <li>
                <a
                  @click="selectOne(2)"
                  class="flex items-center gap-3 px-4 py-3 rounded-lg transition-colors duration-150 hover:bg-gray-100/80 hover:text-gray-800"
                  :class="sysStore.settingTabIndex == 2 ? 'cy-menu-active bg-gray-100 text-gray-900' : ''">
                  <span class="icon--memory-dot-hexagon icon-size-22 shrink-0"></span>
                  <span class="leading-6">偏好设置</span>
                </a>
              </li>
            </ul>
          </aside>

          <main class="flex-1 min-w-0">
            <NuxtPage class="rounded-xl min-h-[70vh]" />
          </main>
        </div>
      </div>
    </div>
    <CommonFooter />
  </div>
</template>

<script lang="ts" setup>
import { onBeforeUnmount, onMounted, ref, type CSSProperties } from 'vue'

const sysStore = useSysStore()

const isHeaderHidden = ref(false)
const lastScrollY = ref(0)
const SCROLL_THRESHOLD = 12
const containerStyle: CSSProperties = {
  maxWidth: 'clamp(960px, 80vw, 1280px)',
}

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
})

onBeforeUnmount(() => {
  if (import.meta.server) return
  window.removeEventListener('scroll', handleScroll)
})

function selectOne(index: number) {
  sysStore.settingTabIndex = index
}
</script>
