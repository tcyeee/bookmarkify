<template>
  <div class="min-h-screen w-screen flex flex-col">
    <div class="flex-1">
      <!-- 第一屏 -->
      <div
        class="welcome-hero relative w-full h-screen pb-16 flex flex-col items-center justify-between overflow-hidden text-white">
        <!-- 背景光晕 -->
        <div class="absolute inset-0 pointer-events-none">
          <div class="halo halo-1" />
          <div class="halo halo-2" />
          <div class="halo halo-3" />
        </div>

        <!-- 漂浮书签 -->
        <FloatingBookmarks />

        <!-- 文案 -->
        <div class="relative z-10 flex-1 flex flex-col items-center justify-center gap-4 text-center px-6">
          <p class="text-sm uppercase tracking-[0.4em] text-white/60">Bookmarkify</p>
          <h1 class="text-4xl md:text-5xl font-semibold leading-tight drop-shadow">管理、分享你的应用书签</h1>
          <p class="max-w-2xl text-base md:text-lg text-white/80">集中收藏应用、工具与灵感，探索互联网更多可能</p>

          <!-- 开始使用按钮 -->
          <div
            @click="startUse"
            :class="[
              'group inline-flex items-center gap-2 rounded-full border border-white/15 bg-linear-to-r from-sky-200/80 via-indigo-200/80 to-fuchsia-200/80 px-10 py-2 text-base font-medium shadow-[0_10px_40px_-18px_rgba(56,189,248,0.55)] backdrop-blur transition-all duration-300 ease-out hover:scale-[1.03] hover:shadow-[0_16px_50px_-18px_rgba(129,140,248,0.65)] mt-15 cursor-pointer select-none',
              startLoading ? 'opacity-75 cursor-wait pointer-events-none' : '',
            ]"
            :aria-busy="startLoading">
            <ShimmerText
              :shimmerWidth="100"
              class="inline-flex dark:text-black/50! items-center justify-center px-4 py-1 transition ease-out hover:text-neutral-600 hover:duration-300 text-lg">
              <template v-if="startLoading">
                <span class="icon--rotate-counterclockwise icon-size-24 animate-spin"></span>
                <span>&emsp;处理中...</span>
              </template>
              <template v-else>
                <span>✨ 开始使用</span>
                <span
                  class="icon--memory-arrow-right icon-size-30 w-4 h-4 ml-1 size-3 transition-transform duration-300 ease-in-out group-hover:translate-x-0.5"></span>
              </template>
            </ShimmerText>
          </div>
        </div>

        <!-- 查看更多按钮 -->
        <div
          @click="handleScroll"
          class="relative z-10 animate-bounce flex flex-col items-center gap-5 cursor-pointer select-none">
          <span class="icon--memory-arrow-down-bold icon-size-40 text-white/80"></span>
        </div>
      </div>

      <!-- 划动显示内容 -->
      <section
        ref="featureSection"
        class="relative px-6 py-16 md:py-24 bg-linear-to-b from-white via-white to-slate-50 text-slate-900 transition-colors dark:from-slate-950 dark:via-slate-950/90 dark:to-slate-950 dark:text-slate-100">
        <UiScrollReveal v-slot="{ isVisible }" class="max-w-3xl flex flex-col mx-auto justify-center">
          <h2
            :class="{ 'translate-y-8 opacity-0': !isVisible }"
            class="mb-10 text-center font-display text-2xl font-light leading-[1.125] md:text-3xl lg:text-4xl transition-[transform,opacity] delay-300 duration-500 text-slate-900 dark:text-white">
            为什么选择 Bookmarkify?
          </h2>

          <h3
            :class="{ 'translate-y-8 opacity-0': !isVisible }"
            class="mx-auto mt-4 text-center leading-relaxed text-muted-foreground md:max-w-2xl lg:mt-8 transition-[transform,opacity] delay-300 duration-500 text-slate-600 dark:text-slate-300">
            一键收藏、智能分类、云端同步与社区分享，把分散的网页应用放进同一个入口。
            <br class="hidden md:block" />
            你的常用应用、工作流和灵感清单，随时随地一触即达。
          </h3>

          <div class="mt-6 transition-[transform,opacity] duration-(--duration) md:mt-12">
            <div class="grid grid-cols-1 gap-x-6 gap-y-4 md:grid-cols-2 md:gap-y-8">
              <div
                :class="{ 'translate-y-8 opacity-0': !isVisible, 'delay-(--delay)': isVisible }"
                class="space-y-3 rounded-2xl border border-slate-200 bg-white/80 px-6 py-8 shadow-sm transition-[transform,opacity] delay-250 duration-500 dark:border-white/10 dark:bg-white/5 dark:shadow-none backdrop-blur">
                <span class="icon--memory-floppy-disk icon-size-40 text-current"></span>

                <h1 class="font-medium text-2xl">一键收藏任意页面</h1>

                <p class="line-clamp-2 text-neon-wb text-lg dark:text-slate-200">
                  使用扩展或快捷键秒存网址，自动抓取标题、图标与简介。
                </p>
              </div>
              <div
                :class="{ 'translate-y-8 opacity-0': !isVisible, 'delay-(--delay)': isVisible }"
                class="space-y-3 rounded-2xl border border-slate-200 bg-white/80 px-6 py-8 shadow-sm transition-[transform,opacity] delay-500 duration-500 dark:border-white/10 dark:bg-white/5 dark:shadow-none backdrop-blur">
                <span class="icon--memory-search icon-size-40 text-current"></span>

                <h1 class="font-medium text-2xl">智能分类与搜索</h1>
                <p class="line-clamp-2 text-neon-wb text-lg dark:text-slate-200">
                  标签、文件夹与 AI 分类建议，模糊搜索让常用应用随手可得。
                </p>
              </div>
              <div
                :class="{ 'translate-y-8 opacity-0': !isVisible, 'delay-(--delay)': isVisible }"
                class="space-y-3 rounded-2xl border border-slate-200 bg-white/80 px-6 py-8 shadow-sm transition-[transform,opacity] delay-750 duration-500 dark:border-white/10 dark:bg-white/5 dark:shadow-none backdrop-blur">
                <span class="icon--memory-checkbox-marked icon-size-40 text-current"></span>
                <h1 class="font-medium text-2xl">书签活性检测</h1>
                <p class="line-clamp-2 text-neon-wb text-lg dark:text-slate-200">自动检测书签是否有效，及时更新书签状态。</p>
              </div>
              <div
                :class="{ 'translate-y-8 opacity-0': !isVisible, 'delay-(--delay)': isVisible }"
                class="space-y-3 rounded-2xl border border-slate-200 bg-white/80 px-6 py-8 shadow-sm transition-[transform,opacity] delay-1000 duration-500 dark:border-white/10 dark:bg-white/5 dark:shadow-none backdrop-blur">
                <span class="icon--memory-cloud icon-size-40 text-current"></span>
                <h1 class="font-medium text-2xl">分享与发现</h1>
                <p class="line-clamp-2 text-neon-wb text-lg dark:text-slate-200">
                  公开或私密分享收藏夹，浏览社区精选书签，一键加入自己的库。
                </p>
              </div>
            </div>
          </div>
        </UiScrollReveal>
      </section>
    </div>

    <!-- 回到顶部 -->
    <Transition name="fade-up">
      <div
        v-if="showBackToTop"
        class="fixed bottom-28 right-6 z-50 rounded-full hover:scale-110 transition-all duration-300 focus-visible:outline-2 focus-visible:outline-offset-2 cursor-pointer select-none"
        aria-label="回到顶部"
        @click="backToTop">
        <span class="icon--memory-arrow-up-circle icon-size-40 text-gray-400"></span>
      </div>
    </Transition>
  </div>
</template>

<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref } from 'vue'
import FloatingBookmarks from '../components/welcome/FloatingBookmarks.vue'
import UiScrollReveal from '../components/stunning/ScrollReveal.vue'
import ShimmerText from '../components/stunning/ShimmerText.vue'

definePageMeta({ layout: 'explore' })

const userStore = useUserStore()
const startLoading = ref(false)

async function startUse() {
  if (startLoading.value) return
  startLoading.value = true
  try {
    await userStore.loginOrRegister()
    await navigateTo('/')
  } finally {
    startLoading.value = false
  }
}

const featureSection = ref<HTMLElement | null>(null)
const handleScroll = () => {
  featureSection.value?.scrollIntoView({ behavior: 'smooth', block: 'start' })
}
const showBackToTop = ref(false)
const toggleBackToTopVisibility = () => {
  const targetTop = featureSection.value?.offsetTop ?? window.innerHeight
  // 提前一点点显示，避免刚离开第一屏还未触发
  showBackToTop.value = window.scrollY >= targetTop - 500
}
onMounted(() => {
  toggleBackToTopVisibility()
  window.addEventListener('scroll', toggleBackToTopVisibility, { passive: true })
})
onBeforeUnmount(() => {
  window.removeEventListener('scroll', toggleBackToTopVisibility)
})
const backToTop = () => {
  window.scrollTo({ top: 0, behavior: 'smooth' })
}
</script>

<style scoped>
.welcome-hero {
  background: radial-gradient(circle at 20% 20%, rgba(94, 234, 212, 0.15), transparent 40%),
    radial-gradient(circle at 80% 30%, rgba(129, 140, 248, 0.18), transparent 45%),
    radial-gradient(circle at 50% 80%, rgba(236, 72, 153, 0.12), transparent 50%), #0b1220;
}

.halo {
  position: absolute;
  width: 62vmax;
  height: 62vmax;
  border-radius: 9999px;
  filter: blur(120px);
  opacity: 0.65;
  animation: haloDrift 26s ease-in-out infinite alternate;
  mix-blend-mode: screen;
}

.halo-1 {
  background: radial-gradient(circle, rgba(94, 234, 212, 0.38), transparent 55%);
  top: -28%;
  left: -20%;
}

.halo-2 {
  background: radial-gradient(circle, rgba(129, 140, 248, 0.35), transparent 52%);
  top: 10%;
  right: -25%;
  animation-duration: 32s;
  animation-delay: 4s;
}

.halo-3 {
  background: radial-gradient(circle, rgba(236, 72, 153, 0.28), transparent 60%);
  bottom: -35%;
  left: 20%;
  animation-duration: 38s;
  animation-delay: 2s;
}

.fade-up-enter-active,
.fade-up-leave-active {
  transition: opacity 220ms ease, transform 220ms ease;
}
.fade-up-enter-from,
.fade-up-leave-to {
  opacity: 0;
  transform: translateY(8px) scale(0.96);
}
.fade-up-enter-to,
.fade-up-leave-from {
  opacity: 1;
  transform: translateY(0) scale(1);
}

@keyframes haloDrift {
  0% {
    transform: translate3d(0, 0, 0) scale(1);
  }
  50% {
    transform: translate3d(4%, -3%, 0) scale(1.08);
  }
  100% {
    transform: translate3d(-3%, 2%, 0) scale(0.98);
  }
}
</style>
