<template>
  <div class="px-4 h-16 bg-white w-full overflow-hidden shadow flex items-center select-none">
    <div class="flex items-center gap-2 cursor-pointer" @click="navigateTo('/')">
      <Icon icon="mdi:monitor" class="size-[30px] hover:text-white" />
      <div class="font-jersey10 font-bold text-4xl">bookmarkify</div>
    </div>

    <nav class="ml-auto flex items-center gap-2">
      <NuxtLink v-for="page in pages" :key="page.path" :to="page.path" class="cy-btn cy-btn-link cy-btn-sm no-underline">
        {{ page.name }}
      </NuxtLink>
    </nav>
  </div>
</template>

<script lang="ts" setup>
const pages = [
  { name: 'welcome', path: '/welcome' },
  { name: 'index', path: '/' },
  { name: 'setting', path: '/setting' },
]
</script>
