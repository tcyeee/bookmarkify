<template>
  <div
    ref="rootRef"
    :class="cn('px-3 sm:px-4 h-14 sm:h-16 w-full shadow flex items-center gap-2 select-none', dark ? 'bg-gray-900 text-gray-100' : 'bg-white')">
    <div class="flex items-center gap-2 min-w-0 cursor-pointer" @click="navigateTo('/')">
      <img src="/favicon.ico" alt="bookmarkify" class="size-[26px] sm:size-[30px] shrink-0" />
      <div class="font-jersey10 font-bold text-2xl sm:text-4xl truncate">bookmarkify</div>
    </div>

    <!-- 宽屏：链接横排铺开 -->
    <nav class="ml-auto hidden sm:flex items-center gap-2">
      <NuxtLink v-for="page in pages" :key="page.path" :to="page.path" class="cy-btn cy-btn-link cy-btn-sm no-underline">
        {{ page.name }}
      </NuxtLink>
    </nav>

    <!-- 窄屏：横排放不下（logo 本身就占掉大半行宽），收进汉堡菜单 -->
    <div class="ml-auto sm:hidden relative">
      <button
        type="button"
        class="cy-btn cy-btn-ghost cy-btn-sm cy-btn-square"
        :aria-expanded="menuOpen"
        aria-label="导航菜单"
        @click="menuOpen = !menuOpen">
        <Icon :icon="menuOpen ? 'mdi:close' : 'mdi:menu'" class="size-6" />
      </button>

      <Transition name="header-menu">
        <nav
          v-if="menuOpen"
          :class="
            cn(
              'absolute right-0 top-full mt-2 z-50 min-w-36 rounded-xl border py-1 shadow-lg',
              dark ? 'bg-gray-900 border-white/10' : 'bg-white border-slate-200',
            )
          ">
          <NuxtLink
            v-for="page in pages"
            :key="page.path"
            :to="page.path"
            class="block px-4 py-2.5 text-sm no-underline hover:bg-slate-100 dark:hover:bg-slate-800"
            @click="menuOpen = false">
            {{ page.name }}
          </NuxtLink>
        </nav>
      </Transition>
    </div>
  </div>
</template>

<script lang="ts" setup>
import { onClickOutside } from '@vueuse/core'
import { cn } from '@utils'
import { AuthStatusEnum } from '@typing'

defineProps<{
  dark?: boolean
}>()

const allPages = [
  { name: 'welcome', path: '/welcome' },
  { name: 'index', path: '/' },
  { name: 'setting', path: '/setting' },
]

const authStore = useAuthStore()
const route = useRoute()

const pages = computed(() =>
  allPages.filter(page => {
    if (page.path === '/welcome' && authStore.authStatus !== AuthStatusEnum.NONE) return false
    if (page.path === '/' && route.path === '/') return false
    if (page.path === '/setting' && route.path.startsWith('/setting')) return false
    return true
  }),
)

const rootRef = ref<HTMLElement | null>(null)
const menuOpen = ref(false)

onClickOutside(rootRef, () => (menuOpen.value = false))

// 同路由点击不会触发导航，但菜单必须收起，否则会挡住页面
watch(() => route.fullPath, () => (menuOpen.value = false))
</script>

<style scoped>
.header-menu-enter-active,
.header-menu-leave-active {
  transition:
    opacity 150ms ease,
    transform 150ms ease;
}
.header-menu-enter-from,
.header-menu-leave-to {
  opacity: 0;
  transform: translateY(-4px);
}
</style>
