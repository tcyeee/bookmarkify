<template>
  <!-- 品牌头部 -->
  <div v-if="!isVerifying" class="flex flex-col items-center pb-6 pt-1">
    <div
      class="mb-4 flex h-12 w-12 items-center justify-center rounded-2xl bg-gradient-to-br from-slate-700 via-slate-900 to-black shadow-lg shadow-black/30">
      <Icon icon="mdi:bookmark" class="size-6 text-white" />
    </div>
    <h2 class="text-xl font-semibold text-white">欢迎登录 Bookmarkify</h2>
    <p class="mt-1.5 text-sm text-white/45">登录后可保障数据安全并支持跨设备同步</p>
  </div>

  <!-- 内联登录面板，key 绑定确保切换 Tab 时重置表单状态 -->
  <EmailLoginPanel v-if="selectedMethod === 'email'" :key="'email'" @success="onSuccess" @step="verifyStep = $event" />
  <PasswordLoginPanel v-else-if="selectedMethod === 'password'" :key="'password'" @success="onSuccess" />

  <!-- 分割线 + 第三方登录（验证码输入步骤时隐藏） -->
  <div v-if="!isVerifying" class="mt-6">
    <div class="flex items-center gap-3 text-xs text-white/20">
      <span class="h-px flex-1 bg-white/8"></span>
      <span>或</span>
      <span class="h-px flex-1 bg-white/8"></span>
    </div>
    <div class="mt-4 flex flex-col items-center gap-3">
      <GoogleLoginButton />
      <!-- 整页跳转到 github.com 官方授权页，而不是弹窗——弹窗在用户已授权过本应用时会被 GitHub 瞬间自动关闭，
           表现为"点了按钮毫无反应就直接登录成功"，体验上不够正式。登录结果在回调页 pages/auth/github/callback.vue 处理，
           这里不会触发 success 事件。 -->
      <GithubLoginButton redirect />
    </div>

    <!-- 测试环境快捷登录：仅本地开发环境显示，免密码登录固定测试账号 -->
    <div v-if="isDev" class="mt-4 text-center">
      <button
        type="button"
        class="text-xs text-white/25 underline decoration-dotted underline-offset-2 hover:text-white/50 transition-colors"
        @click="quickLogin">
        测试环境登录
      </button>
    </div>
  </div>
</template>

<script lang="ts" setup>
import EmailLoginPanel from './EmailLoginPanel.vue'
import PasswordLoginPanel from './PasswordLoginPanel.vue'
import GoogleLoginButton from './GoogleLoginButton.vue'
import GithubLoginButton from './GithubLoginButton.vue'
import { useAuthStore } from '@stores/auth.store'

const emit = defineEmits<{
  (e: 'success'): void
}>()

const authStore = useAuthStore()
const selectedMethod = ref<'email' | 'password'>('email')
const isDev = import.meta.dev

// 验证码步骤：进入验证码输入后隐藏头部与登录方式 Tab
const verifyStep = ref(1)
const isVerifying = computed(() => verifyStep.value === 2)
watch(selectedMethod, () => (verifyStep.value = 1))

async function onSuccess() {
  await authStore.postLoginSetup()
  emit('success')
}

async function quickLogin() {
  try {
    await authStore.loginWithQuickLogin()
    await onSuccess()
  } catch {
    // 失败原因（如非本地环境）由 http 客户端统一弹出错误提示，这里无需重复处理
  }
}
</script>
