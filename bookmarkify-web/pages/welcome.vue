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
          <p class="text-sm uppercase tracking-[0.35em] text-white/70">Bookmarkify</p>
          <h1 class="text-4xl md:text-5xl font-semibold leading-tight drop-shadow">欢迎来到你的智能书签空间</h1>
          <p class="max-w-2xl text-base md:text-lg text-white/80">
            将灵感、阅读与资源收纳成一片宁静的“书签海”，轻轻漂浮，随时被召唤。
          </p>
        </div>

        <!-- 查看更多按钮 -->
        <div @click="handleScroll" class="relative z-10 animate-bounce flex flex-col items-center gap-5 cursor-pointer">
          <p class="icon--arrow-down-bold icon-size-40 text-white/80" />
        </div>
      </div>

      <!-- 划动显示内容 -->
      <section ref="featureSection" class="py-16">
        <UiScrollReveal v-slot="{ isVisible }" class="max-w-3xl flex flex-col mx-auto">
          <div
            :class="{ 'translate-y-8 opacity-0': !isVisible }"
            class="flex justify-center transition-[transform,opacity] delay-300 duration-500">
            <div class="rounded-full font-mono text-sm tracking-tight text-neon">Features</div>
          </div>

          <h2
            :class="{ 'translate-y-8 opacity-0': !isVisible }"
            class="mt-4 text-left font-display text-2xl font-light leading-[1.125] md:text-3xl lg:text-4xl transition-[transform,opacity] delay-300 duration-500">
            Why choose our service?
          </h2>

          <h3
            :class="{ 'translate-y-8 opacity-0': !isVisible }"
            class="mx-auto mt-4 text-center leading-relaxed text-muted-foreground md:max-w-2xl lg:mt-8 transition-[transform,opacity] delay-300 duration-500">
            Stunning UI Pro is awesome premium library.
            <br class="hidden md:block" />
            Build better, faster with Stunning UI.
          </h3>

          <div class="mt-6 transition-[transform,opacity] duration-(--duration) md:mt-12">
            <div class="grid grid-cols-1 gap-x-6 gap-y-4 md:grid-cols-2 md:gap-y-8">
              <div
                :class="{ 'translate-y-8 opacity-0': !isVisible, 'delay-(--delay)': isVisible }"
                class="space-y-3 rounded-2xl border border-white/10 bg-white/5 px-6 py-8 transition-[transform,opacity] delay-250 duration-500">
                <h1 class="font-medium text-2xl">Hover me</h1>

                <p class="line-clamp-2 text-neon-wb text-lg">
                  {{ paragraphPlaceholder }}
                </p>
              </div>
              <div
                :class="{ 'translate-y-8 opacity-0': !isVisible, 'delay-(--delay)': isVisible }"
                class="space-y-3 rounded-2xl border border-white/10 bg-white/5 px-6 py-8 transition-[transform,opacity] delay-500 duration-500">
                <h1 class="font-medium text-2xl">Hover me</h1>
                <p class="line-clamp-2 text-neon-wb text-lg">
                  {{ paragraphPlaceholder }}
                </p>
              </div>
              <div
                :class="{ 'translate-y-8 opacity-0': !isVisible, 'delay-(--delay)': isVisible }"
                class="space-y-3 rounded-2xl border border-white/10 bg-white/5 px-6 py-8 transition-[transform,opacity] delay-750 duration-500">
                <h1 class="font-medium text-2xl">Hover me</h1>
                <p class="line-clamp-2 text-neon-wb text-lg">
                  {{ paragraphPlaceholder }}
                </p>
              </div>
              <div
                :class="{ 'translate-y-8 opacity-0': !isVisible, 'delay-(--delay)': isVisible }"
                class="space-y-3 rounded-2xl border border-white/10 bg-white/5 px-6 py-8 transition-[transform,opacity] delay-1000 duration-500">
                <h1 class="font-medium text-2xl">Hover me</h1>
                <p class="line-clamp-2 text-neon-wb text-lg">
                  {{ paragraphPlaceholder }}
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
        class="fixed bottom-28 right-6 z-50 rounded-full hover:scale-110 transition-all duration-300 focus-visible:outline-2 focus-visible:outline-offset-2"
        aria-label="回到顶部"
        @click="backToTop">
        <p class="icon--arrow-up-circle icon-size-40 text-gray-400" />
      </div>
    </Transition>

    <!-- 页脚 -->
    <SiteFooter />
  </div>
</template>

<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref } from 'vue'
import FloatingBookmarks from '../components/welcome/FloatingBookmarks.vue'
import SiteFooter from '../components/welcome/SiteFooter.vue'
import UiScrollReveal from '../components/stunning/ScrollReveal.vue'

const paragraphPlaceholder = `hello hello hello hello hello hello hello hello hello hello hello hello.`
const featureSection = ref<HTMLElement | null>(null)
const handleScroll = () => {
  featureSection.value?.scrollIntoView({ behavior: 'smooth', block: 'start' })
}
const showBackToTop = ref(false)
const toggleBackToTopVisibility = () => {
  const targetTop = featureSection.value?.offsetTop ?? window.innerHeight
  // 提前一点点显示，避免刚离开第一屏还未触发
  showBackToTop.value = window.scrollY >= targetTop - 48
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
