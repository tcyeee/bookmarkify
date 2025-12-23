<template>
  <div class="min-h-screen bg-gray-950 text-gray-100 flex flex-col">
    <CommonHeader class="shadow-[0_2px_20px_rgba(0,0,0,0.35)]" />

    <main class="flex-1 px-6 py-16 flex flex-col items-center justify-center text-center gap-6">
      <img src="/images/404.png" alt="404" class="w-1/2" />
      <h1 class="text-4xl md:text-5xl font-semibold text-white drop-shadow">
        <span class="text-gray-500 text-xl md:text-2xl ml-3">{{ statusCode === 404 ? '页面未找到' : '发生错误' }}</span>
      </h1>

      <p class="max-w-xl text-gray-400 leading-relaxed">
        {{ description }}
      </p>

      <div class="flex flex-wrap justify-center gap-3">
        <button class="cy-btn cy-btn-wide rounded-xl" @click="goHome">返回首页</button>
        <button class="cy-btn cy-btn-ghost rounded-xl" @click="goBack">返回上一页</button>
      </div>
    </main>

    <CommonFooter />
  </div>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import type { NuxtError } from 'nuxt/app'

const props = defineProps<{
  error: NuxtError
}>()

const statusCode = computed(() => props.error?.statusCode || 500)
const description = computed(() =>
  statusCode.value === 404
    ? '这里好像什么都没有，你可以返回首页或检查网址是否正确。'
    : props.error?.message || '抱歉，出现了一些问题，请稍后再试。'
)

const goHome = () => clearError({ redirect: '/' })
const goBack = () => {
  if (history.length > 1) history.back()
  else goHome()
}

useHead({
  title: statusCode.value === 404 ? '页面未找到 - Bookmarkify' : '出错了 - Bookmarkify',
})
</script>
