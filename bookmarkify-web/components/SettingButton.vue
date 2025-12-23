<template>
  <NuxtLink
    to="/setting"
    class="inline-flex items-center gap-2 mb-8 ml-8"
    @mouseenter="handleMouseEnter"
    @mouseleave="handleMouseLeave">
    <span
      :class="[
        'icon-size-30 text-gray-700 transition-all duration-200',
        isHover ? 'icon--memory-dot-hexagon-fill' : 'icon--memory-dot-hexagon',
      ]" />

    <span class="text-gray-700 text-base setting-label" :class="{ 'setting-label--visible': isHover }"> 设置 </span>
  </NuxtLink>
</template>

<script lang="ts" setup>
const isHover = ref(false)

const handleMouseEnter = () => {
  isHover.value = true
}

const handleMouseLeave = () => {
  isHover.value = false
}
</script>

<style scoped>
.setting-label {
  display: inline-block;
  opacity: 0;
  max-width: 0;
  overflow: hidden;
  white-space: nowrap;
  pointer-events: none;
  transform: translateX(-8px);
  transition: opacity 0.2s ease, transform 0.2s ease, max-width 0.2s ease;
}

.setting-label--visible {
  opacity: 1;
  max-width: 200px;
  transform: translateX(0);
}
</style>
