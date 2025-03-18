<template>
  <div class="bg-gray-200 h-screen">
    <div class="h-[7rem]">
      <NuxtLink to="/">
        <div class="cy-btn cy-btn-primary mt-5 ml-5">返回</div>
      </NuxtLink>
    </div>
    <div class="flex gap-5 px-[10rem]">
      <div>
        <SettingSideBar class="w-[10rem]" />
      </div>
      <NuxtPage class="flex-1 rounded-box min-h-screen" />
    </div>
    <LayoutFooter class="fixed bottom-0" />
  </div>
</template>

<script lang="ts" setup>
</script>

<style scoped land='scss'>
.min-h-screen {
  min-height: calc(100vh - 13rem);
}
</style>
