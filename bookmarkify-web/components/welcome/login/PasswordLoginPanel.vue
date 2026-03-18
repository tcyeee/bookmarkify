<template>
  <div class="space-y-3">
    <input
      v-model="form.account"
      type="text"
      :class="INPUT_CLS + ' w-full'"
      placeholder="手机号 / 邮箱"
      autocomplete="username" />

    <div class="relative">
      <input
        v-model="form.password"
        :type="showPassword ? 'text' : 'password'"
        :class="INPUT_CLS + ' w-full pr-11'"
        placeholder="密码"
        autocomplete="current-password"
        @keydown.enter="submit" />
      <button
        type="button"
        class="absolute right-3 top-1/2 -translate-y-1/2 text-white/35 hover:text-white/70 transition-colors"
        @click="showPassword = !showPassword">
        <span v-if="showPassword" class="icon--memory-eye-off icon-size-18" />
        <Icon v-else icon="memory:eye" class="size-[18px]" />
      </button>
    </div>

    <p v-if="errorMsg" class="text-sm text-red-400">{{ errorMsg }}</p>

    <button
      type="button"
      :class="BTN_CLS"
      :style="BTN_STYLE"
      :disabled="!canSubmit || loading"
      @click="submit">
      <Icon v-if="loading" icon="memory:rotate-clockwise" class="size-4 animate-spin" />
      {{ loading ? '登录中...' : '登录' }}
    </button>
  </div>
</template>

<script lang="ts" setup>
import { useAuthStore } from '@stores/auth.store'

const emit = defineEmits<{ (e: 'success'): void }>()

const INPUT_CLS =
  'rounded-xl border border-white/10 bg-white/8 px-4 py-2.5 text-sm text-white placeholder:text-white/30 outline-none transition-all duration-200 focus:border-indigo-400/60 focus:ring-2 focus:ring-indigo-400/20'
const BTN_CLS =
  'flex w-full items-center justify-center gap-2 rounded-xl py-2.5 text-sm font-semibold text-white shadow-lg shadow-indigo-500/25 transition-all duration-200 hover:scale-[1.01] active:scale-[0.99] disabled:cursor-not-allowed disabled:opacity-50'
const BTN_STYLE = 'background: linear-gradient(to right, #0ea5e9, #6366f1, #a855f7)'

const authStore = useAuthStore()
const form = reactive({ account: '', password: '' })
const loading = ref(false)
const showPassword = ref(false)
const errorMsg = ref('')

const canSubmit = computed(() => form.account.trim().length > 0 && form.password.length > 0)

async function submit() {
  if (!canSubmit.value || loading.value) return
  errorMsg.value = ''
  loading.value = true
  try {
    await authStore.loginWithPassword({ account: form.account.trim(), password: form.password })
    emit('success')
  } catch (err: any) {
    errorMsg.value = err?.msg || '账号或密码错误'
  } finally {
    loading.value = false
  }
}
</script>

