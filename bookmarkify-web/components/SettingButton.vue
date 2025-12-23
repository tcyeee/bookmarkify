<template>
  <div class="flex flex-col gap-2 mb-8 ml-8 setting-entry" :class="{ 'setting-entry--enter': visible }">
    <div
      class="inline-flex items-center gap-2"
      @mouseenter="handleMouseEnter"
      @mouseleave="handleMouseLeave"
      @click="handleHomeClick">
      <span
        :class="[
          'icon-size-30 text-gray-700 transition-all duration-200',
          isHover ? 'icon--memory-apps-box-fill' : 'icon--memory-apps-box',
        ]" />

      <span class="text-gray-700 text-base setting-label" :class="{ 'setting-label--visible': isHover }"> 首页 (Esc) </span>
    </div>

    <NuxtLink to="/setting" class="inline-flex items-center gap-2" @mouseenter="handleMouseEnter" @mouseleave="handleMouseLeave">
      <span
        :class="[
          'icon-size-30 text-gray-700 transition-all duration-200',
          isHover ? 'icon--memory-dot-hexagon-fill' : 'icon--memory-dot-hexagon',
        ]" />

      <span class="text-gray-700 text-base setting-label" :class="{ 'setting-label--visible': isHover }"> 设置 </span>
    </NuxtLink>
  </div>
</template>

<script lang="ts" setup>
const emits = defineEmits(['home-click'])

const isHover = ref(false)
const visible = ref(false)

const handleMouseEnter = () => {
  isHover.value = true
}

const handleMouseLeave = () => {
  isHover.value = false
}

const handleHomeClick = () => {
  emits('home-click')
}

onMounted(() => {
  requestAnimationFrame(() => {
    visible.value = true
  })
})
</script>

<style scoped>
.setting-entry {
  transform: translateX(-120%);
  opacity: 0;
  transition: transform 0.45s ease, opacity 0.45s ease;
}

.setting-entry--enter {
  transform: translateX(0);
  opacity: 1;
}

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
