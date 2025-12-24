<template>
  <div
    v-for="(bookmark, index) in bookmarks"
    :key="index"
    class="absolute inset-0 pointer-events-none"
    :style="{ left: bookmark.left, top: bookmark.top, '--i': index }">
    <img class="rounded-4xl w-25 h-25 blur opacity-50 floating-img" :src="bookmark.src" loading="lazy" />
  </div>
</template>

<script setup lang="ts">
const bookmarks = [
  { src: '/welcome/01.png', left: '10%', top: '15%' },
  { src: '/welcome/02.png', left: '22%', top: '52%' },
  { src: '/welcome/03.png', left: '38%', top: '26%' },
  { src: '/welcome/04.png', left: '55%', top: '60%' },
  { src: '/welcome/05.png', left: '70%', top: '18%' },
  { src: '/welcome/06.png', left: '82%', top: '44%' },
  { src: '/welcome/07.png', left: '8%', top: '72%' },
  { src: '/welcome/08.png', left: '48%', top: '82%' },
  { src: '/welcome/09.png', left: '30%', top: '74%' },
  { src: '/welcome/10.png', left: '62%', top: '32%' },
  { src: '/welcome/11.png', left: '78%', top: '68%' },
  { src: '/welcome/12.png', left: '18%', top: '34%' },
]
</script>

<style scoped>
.floating-img {
  animation: float 7s ease-in-out infinite;
  animation-delay: calc(var(--i) * 0.25s);
  transform-origin: center;
  will-change: transform;
}

@keyframes float {
  0% {
    transform: translate3d(0, 0, 0) scale(1);
  }
  25% {
    transform: translate3d(4px, -8px, 0) scale(1.02);
  }
  50% {
    transform: translate3d(0, -12px, 0) scale(1.03);
  }
  75% {
    transform: translate3d(-4px, -8px, 0) scale(1.02);
  }
  100% {
    transform: translate3d(0, 0, 0) scale(1);
  }
}
</style>
