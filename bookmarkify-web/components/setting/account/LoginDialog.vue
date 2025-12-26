<template>
  <div
    class="mx-auto mb-4 flex h-14 w-14 items-center justify-center rounded-full bg-slate-100 text-slate-500 dark:bg-slate-800 dark:text-slate-300">
    <span class="icon--memory--alert icon-size-26"></span>
  </div>
  <div class="text-xl font-semibold text-slate-800 dark:text-slate-100 mb-2">未完成登录</div>
  <div class="text-slate-500 dark:text-slate-300 mb-4 text-sm">
    请绑定手机号或邮箱完成登录，以便保障账号安全并支持跨设备同步。
  </div>

  <div class="grid grid-cols-1 gap-3">
    <button
      type="button"
      class="flex items-center justify-between rounded-xl border px-4 py-3 text-left transition-all duration-200"
      :class="[
        selectedMethod === 'phone'
          ? 'border-primary bg-primary/5 text-primary dark:border-primary/70 dark:bg-primary/10'
          : 'border-slate-200 bg-white hover:border-slate-300 hover:bg-slate-50 dark:border-slate-700 dark:bg-slate-900 dark:hover:border-slate-500 dark:hover:bg-slate-800',
      ]"
      @click="selectedMethod = 'phone'">
      <div class="flex items-center gap-3">
        <span class="icon--memory-speaker icon-size-22 text-slate-500 dark:text-slate-300"></span>
        <div>
          <div class="text-sm font-semibold">手机号登录</div>
          <div class="text-xs text-slate-500 dark:text-slate-400">通过短信验证码快速登录</div>
        </div>
      </div>
      <span
        class="inline-flex h-5 w-5 items-center justify-center rounded-full border text-[10px]"
        :class="
          selectedMethod === 'phone'
            ? 'border-primary bg-primary text-white'
            : 'border-slate-300 text-slate-400 dark:border-slate-600 dark:text-slate-500'
        ">
        {{ selectedMethod === 'phone' ? '✓' : '' }}
      </span>
    </button>
    <button
      type="button"
      class="flex items-center justify-between rounded-xl border px-4 py-3 text-left transition-all duration-200"
      :class="[
        selectedMethod === 'email'
          ? 'border-primary bg-primary/5 text-primary dark:border-primary/70 dark:bg-primary/10'
          : 'border-slate-200 bg-white hover:border-slate-300 hover:bg-slate-50 dark:border-slate-700 dark:bg-slate-900 dark:hover:border-slate-500 dark:hover:bg-slate-800',
      ]"
      @click="selectedMethod = 'email'">
      <div class="flex items-center gap-3">
        <span class="icon--memory-email icon-size-22 text-slate-500 dark:text-slate-300"></span>
        <div>
          <div class="text-sm font-semibold">邮箱登录</div>
          <div class="text-xs text-slate-500 dark:text-slate-400">适合常用邮箱用户</div>
        </div>
      </div>
      <span
        class="inline-flex h-5 w-5 items-center justify-center rounded-full border text-[10px]"
        :class="
          selectedMethod === 'email'
            ? 'border-primary bg-primary text-white'
            : 'border-slate-300 text-slate-400 dark:border-slate-600 dark:text-slate-500'
        ">
        {{ selectedMethod === 'email' ? '✓' : '' }}
      </span>
    </button>
  </div>

  <div class="flex justify-center gap-3 w-full">
    <button class="cy-btn cy-btn-wide cy-btn-lg mt-5 cy-btn-primary" @click="" :disabled="saving">开始登录</button>
  </div>

  <!-- 第三方登录 -->
  <div class="mt-6 space-y-3">
    <div class="flex items-center gap-3 text-xs text-slate-400 dark:text-slate-500">
      <span class="h-px flex-1 bg-slate-200 dark:bg-slate-700"></span>
      <span>第三方登录</span>
      <span class="h-px flex-1 bg-slate-200 dark:bg-slate-700"></span>
    </div>
    <div class="flex justify-center gap-4 text-xl">
      <button
        type="button"
        class="flex h-10 w-10 items-center justify-center rounded-full border border-slate-200 bg-white shadow-sm transition hover:border-primary hover:text-primary dark:border-slate-700 dark:bg-slate-900 dark:hover:border-primary/70"
        aria-label="微信登录"
        title="微信登录">
        <span class="icon--icon-wechat icon-size-22 text-slate-500 dark:text-slate-300"></span>
      </button>
      <button
        type="button"
        class="flex h-10 w-10 items-center justify-center rounded-full border border-slate-200 bg-white shadow-sm transition hover:border-primary hover:text-primary dark:border-slate-700 dark:bg-slate-900 dark:hover:border-primary/70"
        aria-label="Github 登录"
        title="Github 登录">
        <span class="icon--icon-github icon-size-22 text-slate-500 dark:text-slate-300"></span>
      </button>
      <button
        type="button"
        class="flex h-10 w-10 items-center justify-center rounded-full border border-slate-200 bg-white shadow-sm transition hover:border-primary hover:text-primary dark:border-slate-700 dark:bg-slate-900 dark:hover:border-primary/70"
        aria-label="谷歌登录"
        title="谷歌登录">
        <span class="icon--icon-google icon-size-20 text-slate-500 dark:text-slate-300"></span>
      </button>
      <button
        type="button"
        class="flex h-10 w-10 items-center justify-center rounded-full border border-slate-200 bg-white shadow-sm transition hover:border-primary hover:text-primary dark:border-slate-700 dark:bg-slate-900 dark:hover:border-primary/70"
        aria-label="抖音登录"
        title="抖音登录">
        <span class="icon--icon-tiktok icon-size-21 text-slate-500 dark:text-slate-300"></span>
      </button>
    </div>
  </div>
</template>

<script lang="ts" setup>
import { ref, watch } from 'vue'
import type { UserInfo, LoginMethod } from '@typing'
import { useUserStore } from '@stores/user.store'

const userStore = useUserStore()
const selectedMethod = ref<LoginMethod['key']>('phone')
const account = computed<UserInfo | undefined>(() => userStore.account)

const form = reactive({
  nickName: '',
  phone: '',
  email: '',
})
const saving = ref(false)

watch(
  account,
  (val) => {
    form.nickName = val?.nickName || ''
    form.phone = val?.phone || ''
    form.email = val?.email || ''
  },
  { immediate: true }
)
</script>
