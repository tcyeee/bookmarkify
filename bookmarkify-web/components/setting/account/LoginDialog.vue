<template>
  <div
    class="mx-auto mb-4 flex h-14 w-14 items-center justify-center rounded-full bg-slate-100 text-slate-500 dark:bg-slate-800 dark:text-slate-300">
    <span class="icon--memory--alert icon-size-26"></span>
  </div>
  <div class="text-xl font-semibold text-slate-800 dark:text-slate-100 mb-2">未完成验证</div>
  <div class="text-slate-500 dark:text-slate-300 mb-4 text-sm">
    请绑定手机号或邮箱完成验证，以便保障账号安全并支持跨设备同步。
  </div>

  <div class="grid grid-cols-1 gap-3">
    <button
      v-for="method in loginMethods"
      :key="method.key"
      type="button"
      class="flex items-center justify-between rounded-xl border px-4 py-3 text-left transition-all duration-200"
      :class="[
        selectedMethod === method.key
          ? 'border-primary bg-primary/5 text-primary dark:border-primary/70 dark:bg-primary/10'
          : 'border-slate-200 bg-white hover:border-slate-300 hover:bg-slate-50 dark:border-slate-700 dark:bg-slate-900 dark:hover:border-slate-500 dark:hover:bg-slate-800',
      ]"
      @click="selectedMethod = method.key">
      <div class="flex items-center gap-3">
        <span :class="[method.icon, 'icon-size-22 text-slate-500 dark:text-slate-300']"></span>
        <div>
          <div class="text-sm font-semibold">
            {{ method.label }}
          </div>
          <div class="text-xs text-slate-500 dark:text-slate-400">
            {{ method.description }}
          </div>
        </div>
      </div>
      <span
        class="inline-flex h-5 w-5 items-center justify-center rounded-full border text-[10px]"
        :class="
          selectedMethod === method.key
            ? 'border-primary bg-primary text-white'
            : 'border-slate-300 text-slate-400 dark:border-slate-600 dark:text-slate-500'
        ">
        {{ selectedMethod === method.key ? '✓' : '' }}
      </span>
    </button>
  </div>

  <div class="flex justify-center gap-3">
    <button class="cy-btn cy-btn-ghost" @click="userStore.refreshUserInfo()" :disabled="saving">刷新状态</button>
  </div>
</template>

<script lang="ts" setup>
import { ref, watch, type CSSProperties } from 'vue'
import type { UserInfo, LoginMethod } from '@typing'
import { useUserStore } from '@stores/user.store'

const userStore = useUserStore()
const selectedMethod = ref<LoginMethod['key']>('phone')
const account = computed<UserInfo | undefined>(() => userStore.account)

const loginMethods: LoginMethod[] = [
  {
    key: 'phone',
    label: '手机号验证登录',
    icon: 'icon--memory-speaker',
    description: '通过短信验证码快速登录',
  },
  {
    key: 'email',
    label: '邮箱验证码登录',
    icon: 'icon--memory-email',
    description: '适合常用邮箱用户',
  },
]

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
