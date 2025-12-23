<template>
  <div class="absolute inset-0 pointer-events-none">
    <div
      v-for="bookmark in bookmarks"
      :key="bookmark.id"
      class="floating-bookmark"
      :style="{ left: bookmark.left, top: bookmark.top, fontSize: bookmark.size }">
      {{ bookmark.icon }}
    </div>
  </div>
</template>

<script setup lang="ts">
const bookmarks = [
  { id: 'b1', icon: '📚', left: '12%', top: '12%', size: '38px' },
  { id: 'b2', icon: '🔖', left: '26%', top: '58%', size: '34px' },
  { id: 'b3', icon: '📑', left: '42%', top: '30%', size: '36px' },
  { id: 'b4', icon: '📕', left: '58%', top: '68%', size: '40px' },
  { id: 'b5', icon: '📘', left: '72%', top: '22%', size: '42px' },
  { id: 'b6', icon: '📙', left: '84%', top: '48%', size: '35px' },
  { id: 'b7', icon: '📗', left: '6%', top: '72%', size: '33px' },
  { id: 'b8', icon: '📓', left: '50%', top: '85%', size: '37px' },
]
</script>

<style scoped>
.floating-bookmark {
  position: absolute;
  color: #ffffff;
  text-shadow: 0 10px 25px rgba(0, 0, 0, 0.35);
  opacity: 0.92;
}
</style>
