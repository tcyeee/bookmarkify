<template>
  <div class="min-h-screen w-screen flex flex-col">
    <div class="flex-1 pb-12">
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
        <div class="absolute inset-0 pointer-events-none">
          <div
            v-for="bookmark in floatingBookmarks"
            :key="bookmark.id"
            class="floating-bookmark"
            :style="{
              left: bookmark.left,
              top: bookmark.top,
              animationDelay: bookmark.delay,
              animationDuration: bookmark.duration,
              fontSize: bookmark.size,
            }">
            {{ bookmark.icon }}
          </div>
        </div>

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
          <icon class="icon--arrow-down-bold icon-size-40 text-white/80" />
        </div>
      </div>

      <!-- 划动显示内容 -->
      <UiScrollReveal v-slot="{ isVisible }" class="max-w-3xl flex flex-col mx-auto">
        <div
          :class="{ 'translate-y-8 opacity-0': !isVisible }"
          class="flex justify-center transition-[transform,opacity] delay-100 duration-500">
          <div class="rounded-full font-mono text-sm tracking-tight text-neon">Features</div>
        </div>

        <h2
          :class="{ 'translate-y-8 opacity-0': !isVisible }"
          class="mt-4 text-left font-display text-2xl font-light leading-[1.125] md:text-3xl lg:text-4xl transition-[transform,opacity] delay-100 duration-500">
          Why choose our service?
        </h2>

        <h3
          :class="{ 'translate-y-8 opacity-0': !isVisible }"
          class="mx-auto mt-4 text-center leading-relaxed text-muted-foreground md:max-w-2xl lg:mt-8 transition-[transform,opacity] delay-100 duration-500">
          Stunning UI Pro is awesome premium library.
          <br class="hidden md:block" />
          Build better, faster with Stunning UI.
        </h3>

        <div class="mt-6 transition-[transform,opacity] duration-(--duration) md:mt-12">
          <div class="grid grid-cols-1 gap-x-6 gap-y-4 md:grid-cols-2 md:gap-y-8">
            <div
              :class="{ 'translate-y-8 opacity-0': !isVisible, 'delay-(--delay)': isVisible }"
              class="space-y-3 rounded-2xl border border-white/10 bg-white/5 px-6 py-8 transition-[transform,opacity] delay-250 duration-500">
              <Icon name="ri:magic-line" class="w-8 h-8" />

              <h1 class="font-medium text-2xl">Hover me</h1>

              <p class="line-clamp-2 text-neon-wb text-lg">
                {{ paragraphPlaceholder }}
              </p>
            </div>
            <div
              :class="{ 'translate-y-8 opacity-0': !isVisible, 'delay-(--delay)': isVisible }"
              class="space-y-3 rounded-2xl border border-white/10 bg-white/5 px-6 py-8 transition-[transform,opacity] delay-500 duration-500">
              <Icon name="ri:magic-line" class="w-8 h-8" />
              <h1 class="font-medium text-2xl">Hover me</h1>
              <p class="line-clamp-2 text-neon-wb text-lg">
                {{ paragraphPlaceholder }}
              </p>
            </div>
            <div
              :class="{ 'translate-y-8 opacity-0': !isVisible, 'delay-(--delay)': isVisible }"
              class="space-y-3 rounded-2xl border border-white/10 bg-white/5 px-6 py-8 transition-[transform,opacity] delay-750 duration-500">
              <Icon name="ri:magic-line" class="w-8 h-8" />
              <h1 class="font-medium text-2xl">Hover me</h1>
              <p class="line-clamp-2 text-neon-wb text-lg">
                {{ paragraphPlaceholder }}
              </p>
            </div>
            <div
              :class="{ 'translate-y-8 opacity-0': !isVisible, 'delay-(--delay)': isVisible }"
              class="space-y-3 rounded-2xl border border-white/10 bg-white/5 px-6 py-8 transition-[transform,opacity] delay-1000 duration-500">
              <Icon name="ri:magic-line" class="w-8 h-8" />
              <h1 class="font-medium text-2xl">Hover me</h1>
              <p class="line-clamp-2 text-neon-wb text-lg">
                {{ paragraphPlaceholder }}
              </p>
            </div>
          </div>
        </div>
      </UiScrollReveal>
    </div>

    <!-- 回到顶部 -->
    <Button
      v-if="showBackToTop"
      size="icon"
      class="fixed bottom-28 right-6 z-50 rounded-full hover:scale-110 transition-all duration-300 focus-visible:outline-2 focus-visible:outline-offset-2"
      aria-label="回到顶部"
      @click="backToTop">
      <icon class="icon--arrow-up-circle icon-size-40 text-gray-400" />
    </Button>

    <!-- 页脚 -->
    <SiteFooter />
  </div>
</template>

<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref } from 'vue'
import SiteFooter from '../components/welcome/SiteFooter.vue'

const paragraphPlaceholder = `hello hello hello hello hello hello hello hello hello hello hello hello.`
const floatingBookmarks = [
  { id: 'b1', icon: '📚', left: '12%', top: '12%', delay: '0s', duration: '12s', size: '38px' },
  { id: 'b2', icon: '🔖', left: '26%', top: '58%', delay: '1.2s', duration: '14s', size: '34px' },
  { id: 'b3', icon: '📑', left: '42%', top: '30%', delay: '2.4s', duration: '16s', size: '36px' },
  { id: 'b4', icon: '📕', left: '58%', top: '68%', delay: '0.8s', duration: '13s', size: '40px' },
  { id: 'b5', icon: '📘', left: '72%', top: '22%', delay: '1.8s', duration: '15s', size: '42px' },
  { id: 'b6', icon: '📙', left: '84%', top: '48%', delay: '2.8s', duration: '18s', size: '35px' },
  { id: 'b7', icon: '📗', left: '6%', top: '72%', delay: '3.2s', duration: '17s', size: '33px' },
  { id: 'b8', icon: '📓', left: '50%', top: '85%', delay: '4s', duration: '19s', size: '37px' },
]
const handleScroll = () => {
  window.scrollTo({ top: document.body.scrollHeight, behavior: 'smooth' })
}
const showBackToTop = ref(false)
const toggleBackToTopVisibility = () => {
  showBackToTop.value = window.scrollY > window.innerHeight
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

.floating-bookmark {
  position: absolute;
  animation: floatY ease-in-out infinite alternate;
  color: #ffffff;
  text-shadow: 0 10px 25px rgba(0, 0, 0, 0.35);
  opacity: 0.92;
}

@keyframes floatY {
  0% {
    transform: translateY(-12%);
  }
  100% {
    transform: translateY(12%);
  }
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
