<template>
  <div class="space-y-3">
    <!-- Step 1: 邮箱地址 -->
    <template v-if="step === 1">
      <input
        v-model="form.email"
        type="email"
        :class="INPUT_CLS + ' w-full'"
        placeholder="请输入邮箱地址"
        @input="(e) => (form.email = (e.target as HTMLInputElement).value.trim())" />

      <button
        type="button"
        :class="BTN_CLS"
        :style="BTN_STYLE"
        :disabled="!canSend || countdown > 0 || sending"
        @click="sendCode">
        <span v-if="sending" class="icon--memory-rotate-clockwise icon-size-16 animate-spin"></span>
        {{ sending ? '发送中...' : countdown > 0 ? `${countdown}s 后可重新发送` : '发送验证码' }}
      </button>
    </template>

    <!-- Step 2: 邮箱验证码 -->
    <template v-else>
      <p class="text-center text-sm text-white/45">
        已向 <span class="text-white/75">{{ maskedEmail }}</span> 发送验证码
      </p>

      <!-- OTP 输入框 -->
      <div class="relative mx-auto my-3 w-fit cursor-text" @click="codeInputRef?.focus()">
        <input
          ref="codeInputRef"
          v-model="form.code"
          type="tel"
          inputmode="numeric"
          maxlength="4"
          class="absolute inset-0 z-10 h-full w-full cursor-text opacity-0"
          @input="onCodeInput" />
        <div class="flex items-center gap-3">
          <div
            v-for="i in 4"
            :key="i"
            class="flex h-12 w-12 items-center justify-center rounded-xl border-2 text-xl font-bold text-white transition-all duration-200"
            :class="
              form.code.length === i - 1
                ? 'border-indigo-400 ring-2 ring-indigo-400/25 bg-indigo-400/5'
                : 'border-white/15 bg-white/5'
            ">
            {{ form.code[i - 1] || '' }}
          </div>
          <button
            v-if="form.code.length"
            type="button"
            class="relative z-20 text-white/40 hover:text-white/70 transition-colors"
            @click.stop="deleteLast">
            <span class="icon--memory-arrow-left-box icon-size-36" />
          </button>
        </div>
      </div>

      <p v-if="codeError" class="text-center text-sm text-red-400">{{ codeError }}</p>

      <div class="flex justify-between text-sm">
        <button type="button" class="text-white/35 hover:text-white/70 transition-colors" @click="step = 1">
          ← 返回
        </button>
        <button
          type="button"
          :disabled="countdown > 0 || sending"
          :class="countdown > 0 ? 'text-white/25 cursor-not-allowed' : 'text-indigo-300 hover:text-indigo-200 transition-colors'"
          @click="resend">
          {{ countdown > 0 ? `${countdown}s 后重发` : '重新发送' }}
        </button>
      </div>

      <button
        type="button"
        :class="BTN_CLS"
        :style="BTN_STYLE"
        :disabled="!isCodeValid || loading"
        @click="submit">
        <span v-if="loading" class="icon--memory-rotate-clockwise icon-size-16 animate-spin"></span>
        {{ loading ? '登录中...' : '确认登录' }}
      </button>
    </template>
  </div>
</template>

<script lang="ts" setup>
import { captchaSendEmail } from '@api'
import { useAuthStore } from '@stores/auth.store'

const emit = defineEmits<{ (e: 'success'): void }>()

const INPUT_CLS =
  'rounded-xl border border-white/10 bg-white/8 px-4 py-2.5 text-sm text-white placeholder:text-white/30 outline-none transition-all duration-200 focus:border-indigo-400/60 focus:ring-2 focus:ring-indigo-400/20'
const BTN_CLS =
  'flex w-full items-center justify-center gap-2 rounded-xl py-2.5 text-sm font-semibold text-white shadow-lg shadow-indigo-500/25 transition-all duration-200 hover:scale-[1.01] active:scale-[0.99] disabled:cursor-not-allowed disabled:opacity-50'
const BTN_STYLE = 'background: linear-gradient(to right, #0ea5e9, #6366f1, #a855f7)'

const authStore = useAuthStore()
const sysStore = useSysStore()

const step = ref(1)
const codeInputRef = ref<HTMLInputElement>()
const countdown = computed(() => sysStore.emailCountdown)

const form = reactive({ email: '', code: '' })
const loading = ref(false)
const sending = ref(false)
const codeError = ref('')

const isEmailValid = computed(() => /^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(form.email.trim()))
const isCodeValid = computed(() => /^\d{4}$/.test(form.code.trim()))
const canSend = computed(() => isEmailValid.value && !sending.value)
const maskedEmail = computed(() => {
  const [user = '', domain = ''] = form.email.split('@')
  return user.length <= 2 ? `${user[0] || ''}***@${domain}` : `${user.slice(0, 2)}***@${domain}`
})

function onCodeInput(e: Event) {
  const t = e.target as HTMLInputElement
  const v = t.value.replace(/\D/g, '').slice(0, 4)
  t.value = v
  form.code = v
  codeError.value = ''
}

function deleteLast() {
  form.code = form.code.slice(0, -1)
  codeInputRef.value?.focus()
}

async function sendCode() {
  if (!canSend.value) return
  if (countdown.value > 0) {
    step.value = 2
    await nextTick()
    codeInputRef.value?.focus()
    return
  }
  codeError.value = ''
  sending.value = true
  try {
    await captchaSendEmail({ email: form.email })
    sysStore.startEmailCountdown()
    ElNotification.success({ message: '已发送邮箱验证码' })
    step.value = 2
    await nextTick()
    codeInputRef.value?.focus()
  } finally {
    sending.value = false
  }
}

async function resend() {
  if (countdown.value > 0) return
  step.value = 1
  await nextTick()
  await sendCode()
}

async function submit() {
  if (!isCodeValid.value || loading.value) return
  loading.value = true
  try {
    await authStore.loginWithEmail({ email: form.email, code: form.code })
    emit('success')
  } catch (err: any) {
    if (err.code === 301) codeError.value = err.msg
  } finally {
    loading.value = false
  }
}
</script>

