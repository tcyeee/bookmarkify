<template>
  <!-- 品牌头部 -->
  <div class="flex flex-col items-center pb-6 pt-1">
    <div
      class="mb-4 flex h-12 w-12 items-center justify-center rounded-2xl bg-gradient-to-br from-sky-400 via-indigo-500 to-fuchsia-500 shadow-lg shadow-indigo-500/30">
      <Icon icon="memory:bookmark" class="size-6 text-white" />
    </div>
    <h2 class="text-xl font-semibold text-white">欢迎登录 Bookmarkify</h2>
    <p class="mt-1.5 text-sm text-white/45">登录后可保障数据安全并支持跨设备同步</p>
  </div>

  <!-- 登录方式 Tab -->
  <div class="mb-5 flex gap-1 rounded-xl bg-white/5 p-1">
    <button
      v-for="m in methods"
      :key="m.key"
      type="button"
      @click="selectedMethod = m.key"
      :class="[
        'flex flex-1 items-center justify-center gap-1.5 rounded-lg py-2 text-sm font-medium transition-all duration-200',
        selectedMethod === m.key ? 'bg-white/12 text-white shadow-sm ring-1 ring-white/10' : 'text-white/40 hover:text-white/65',
      ]">
      <Icon :icon="m.icon" class="size-4" />
      {{ m.label }}
    </button>
  </div>

  <!-- 内联登录面板，key 绑定确保切换 Tab 时重置表单状态 -->
  <PhoneLoginPanel v-if="selectedMethod === 'phone'" :key="'phone'" @success="onSuccess" />
  <EmailLoginPanel v-else-if="selectedMethod === 'email'" :key="'email'" @success="onSuccess" />
  <PasswordLoginPanel v-else-if="selectedMethod === 'password'" :key="'password'" @success="onSuccess" />

  <!-- 跳过登录 -->
  <button
    type="button"
    class="mt-5 w-full py-1 text-center text-sm text-white/25 transition-colors duration-200 hover:text-white/55"
    @click="$emit('skip')">
    跳过登录，以访客身份继续使用 →
  </button>

  <!-- 分割线 + 第三方登录 -->
  <!-- <div class="mt-6">
    <div class="flex items-center gap-3 text-xs text-white/20">
      <span class="h-px flex-1 bg-white/8"></span>
      <span>第三方登录</span>
      <span class="h-px flex-1 bg-white/8"></span>
    </div>
    <div class="mt-4 flex justify-center gap-3">
      <button
        v-for="s in socials"
        :key="s.label"
        type="button"
        :aria-label="s.label"
        :title="s.label"
        @click="showNotReady(s.label)"
        class="flex h-10 w-10 items-center justify-center rounded-full border border-white/10 bg-white/5 text-white/40 transition-all duration-200 hover:scale-105 hover:border-indigo-400/50 hover:bg-white/10 hover:text-white/80">
        <span :class="[s.icon, 'icon-size-20']"></span>
      </button>
    </div>
  </div> -->
</template>

<script lang="ts" setup>
import PhoneLoginPanel from './PhoneLoginPanel.vue'
import EmailLoginPanel from './EmailLoginPanel.vue'
import PasswordLoginPanel from './PasswordLoginPanel.vue'
import { useAuthStore } from '@stores/auth.store'

const emit = defineEmits<{
  (e: 'success'): void
  (e: 'skip'): void
}>()

const authStore = useAuthStore()
const selectedMethod = ref<'phone' | 'email' | 'password'>('password')

const methods = [
  // 暂时停用手机号登录
  // { key: 'phone' as const, label: '手机号', icon: 'icon--memory-speaker' },
  { key: 'password' as const, label: '密码', icon: 'memory:lock' },
  { key: 'email' as const, label: '邮箱', icon: 'memory:email' },
]

const socials = [
  { label: '微信', icon: 'icon--icon-wechat' },
  { label: 'Github', icon: 'icon--icon-github' },
  { label: '谷歌', icon: 'icon--icon-google' },
  { label: '抖音', icon: 'icon--icon-tiktok' },
]

async function onSuccess() {
  await authStore.postLoginSetup()
  emit('success')
}

function showNotReady(provider: string) {
  ElMessage.info(`暂未适配 ${provider} 登录`)
}
</script>
