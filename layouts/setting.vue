<template>
  <div class="bg-gray-200 h-screen pt-[4rem]">
    <div class="flex gap-5 px-[10rem]">
      <div>
        <NuxtLink to="/">
          <div class="cy-btn cy-btn-wide cy-btn-lg rounded-xl mb-[2rem]">返回</div>
        </NuxtLink>
        <SettingSideBar class="w-[10rem]" />
      </div>
      <NuxtPage class="flex-1 rounded-xl min-h-screen" />
    </div>
    <LayoutFooter class="fixed bottom-0" />
  </div>
</template>

<script lang="ts" setup>
</script>

<style scoped land='scss'>
</style>
