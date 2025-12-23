<template>
  <CommonHeader :class="['fixed top-0 z-10 w-full transition-transform duration-300', { '-translate-y-full': isHeaderHidden }]" />
  <div class="bg-gray-200 h-screen pt-28">
    <div class="flex gap-10 sm:px-[1vw] md:px-[2vw] lg:px-[4vw] xl:px-[6vw] pb-[10vh] bg-gray-200">
      <div>
        <NuxtLink to="/">
          <div class="cy-btn cy-btn-wide cy-btn-lg rounded-xl mb-8">返回</div>
        </NuxtLink>

        <!-- 侧边栏 -->
        <ul class="cy-menu bg-white rounded-xl w-40">
          <li v-for="(item, index) in pages">
            <a
              class="text-gray-400 text-lg font-medium"
              @click="selectOne(index)"
              :class="sysStore.settingTabIndex == index ? 'cy-menu-active' : ''">
              <span>{{ item }}</span>
            </a>
          </li>
        </ul>
      </div>
      <NuxtPage class="flex-1 rounded-xl min-h-screen" />
    </div>
    <CommonFooter />
  </div>
</template>

<script lang="ts" setup>
import { onBeforeUnmount, onMounted, ref } from 'vue'

const sysStore = useSysStore()
const pages = ['个人资料', '书签管理', '偏好设置']

const isHeaderHidden = ref(false)
const lastScrollY = ref(0)
const SCROLL_THRESHOLD = 12

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
