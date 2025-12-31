<template>
  <Transition name="fade-main">
    <BookmarkList v-if="status" class="mx-20 sm:w-[90vw] md:w-[80vw] lg:w-[70vw] xl:w-280" />
  </Transition>
</template>

<script lang="ts" setup>
defineProps<{  status: boolean}>()
</script>

<style scoped>
.fade-main-enter-active,
.fade-main-leave-active {
  transition: all 100ms ease-in-out;
}

.fade-main-enter-from,
.fade-main-leave-to {
  transform: scale(1.2);
  opacity: 0;
}
</style>
