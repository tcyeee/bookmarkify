<template>
  <div class="min-h-screen w-screen flex flex-col">
    <div class="flex-1 pb-12">
      <div class="w-full h-screen pb-16 flex flex-col items-center justify-between bg-gray-300">
        <span class="text-neon-wb text-2xl"> Scroll-driven Section Reveal </span>

        <div @click="handleScroll" class="animate-bounce flex flex-col items-center gap-5">
          <icon class="icon--arrow-down-bold icon-size-40 text-gray-400" />
        </div>
      </div>

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
              :class="{
                'translate-y-8 opacity-0': !isVisible,
                'delay-(--delay)': isVisible,
              }"
              class="space-y-3 rounded-2xl border border-white/10 bg-white/5 px-6 py-8 transition-[transform,opacity] delay-250 duration-500">
              <Icon name="ri:magic-line" class="w-8 h-8" />

              <h1 class="font-medium text-2xl">Hover me</h1>

              <p class="line-clamp-2 text-neon-wb text-lg">
                {{ paragraphPlaceholder }}
              </p>
            </div>
            <div
              :class="{
                'translate-y-8 opacity-0': !isVisible,
                'delay-(--delay)': isVisible,
              }"
              class="space-y-3 rounded-2xl border border-white/10 bg-white/5 px-6 py-8 transition-[transform,opacity] delay-500 duration-500">
              <Icon name="ri:magic-line" class="w-8 h-8" />

              <h1 class="font-medium text-2xl">Hover me</h1>

              <p class="line-clamp-2 text-neon-wb text-lg">
                {{ paragraphPlaceholder }}
              </p>
            </div>
            <div
              :class="{
                'translate-y-8 opacity-0': !isVisible,
                'delay-(--delay)': isVisible,
              }"
              class="space-y-3 rounded-2xl border border-white/10 bg-white/5 px-6 py-8 transition-[transform,opacity] delay-750 duration-500">
              <Icon name="ri:magic-line" class="w-8 h-8" />

              <h1 class="font-medium text-2xl">Hover me</h1>

              <p class="line-clamp-2 text-neon-wb text-lg">
                {{ paragraphPlaceholder }}
              </p>
            </div>
            <div
              :class="{
                'translate-y-8 opacity-0': !isVisible,
                'delay-(--delay)': isVisible,
              }"
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
    <Button
      v-if="showBackToTop"
      size="icon"
      class="fixed bottom-28 right-6 z-50 rounded-full hover:scale-110 transition-all duration-300 focus-visible:outline-2 focus-visible:outline-offset-2"
      aria-label="回到顶部"
      @click="backToTop">
      <icon class="icon--arrow-up-circle icon-size-40 text-gray-400" />
    </Button>
    <SiteFooter />
  </div>
</template>

<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref } from 'vue'
import SiteFooter from '../components/welcome/SiteFooter.vue'

const paragraphPlaceholder = `hello hello hello hello hello hello hello hello hello hello hello hello.`
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
