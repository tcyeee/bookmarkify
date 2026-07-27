<template>
  <div :class="cn('px-4 h-16 w-full overflow-hidden shadow flex items-center select-none', dark ? 'bg-gray-900 text-gray-100' : 'bg-white')">
    <div class="flex items-center gap-2 cursor-pointer" @click="navigateTo('/')">
      <img src="/favicon.ico" alt="bookmarkify" class="size-[30px]" />
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
import { cn } from '@utils'

defineProps<{
  dark?: boolean
}>()

const pages = [
  { name: 'welcome', path: '/welcome' },
  { name: 'index', path: '/' },
  { name: 'setting', path: '/setting' },
]
</script>
