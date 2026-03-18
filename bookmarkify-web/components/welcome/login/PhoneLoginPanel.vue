<template>
  <div class="space-y-3">
    <!-- Step 1: 手机号 + 图形验证码 -->
    <template v-if="step === 1">
      <input
        v-model="form.phone"
        type="tel"
        inputmode="numeric"
        maxlength="11"
        :class="INPUT_CLS + ' w-full'"
        placeholder="请输入手机号"
        @input="onPhoneInput"
        @blur="phoneTouched = true" />

      <div class="flex gap-3">
        <input
          v-model="form.captchaCode"
          type="text"
          maxlength="4"
          :class="INPUT_CLS + ' flex-1'"
          placeholder="图形验证码（4位）"
          @input="() => (captchaError = '')" />
        <div
          class="flex h-11 w-22 shrink-0 cursor-pointer items-center justify-center overflow-hidden rounded-xl border border-white/10 bg-white/8 transition-opacity hover:opacity-80"
          title="点击刷新"
          @click="refreshCaptcha">
          <span v-if="!captchaImgBase64" class="text-xs text-white/30">加载中…</span>
          <img v-else :src="`data:image/png;base64,${captchaImgBase64}`" alt="图形验证码" class="h-full w-full object-contain" />
        </div>
      </div>

      <p v-if="captchaError" class="text-sm text-red-400">{{ captchaError }}</p>

      <button
        type="button"
        :class="BTN_CLS"
        :style="BTN_STYLE"
        :disabled="!canSendSms || countdown > 0 || sending"
        @click="sendSms">
        <Icon v-if="sending" icon="memory:rotate-clockwise" class="size-4 animate-spin" />
        {{ sending ? '发送中...' : countdown > 0 ? `${countdown}s 后可重新发送` : '发送验证码' }}
      </button>
    </template>

    <!-- Step 2: 短信验证码 -->
    <template v-else>
      <p class="text-center text-sm text-white/45">
        已向 <span class="text-white/75">{{ maskedPhone }}</span> 发送验证码
      </p>

      <!-- OTP 输入框 -->
      <div class="relative mx-auto my-3 w-fit cursor-text" @click="smsInputRef?.focus()">
        <input
          ref="smsInputRef"
          v-model="form.smsCode"
          type="tel"
          inputmode="numeric"
          maxlength="4"
          class="absolute inset-0 z-10 h-full w-full cursor-text opacity-0"
          @input="onSmsInput" />
        <div class="flex items-center gap-3">
          <div
            v-for="i in 4"
            :key="i"
            class="flex h-12 w-12 items-center justify-center rounded-xl border-2 text-xl font-bold text-white transition-all duration-200"
            :class="
              form.smsCode.length === i - 1
                ? 'border-indigo-400 ring-2 ring-indigo-400/25 bg-indigo-400/5'
                : 'border-white/15 bg-white/5'
            ">
            {{ form.smsCode[i - 1] || '' }}
          </div>
          <button
            v-if="form.smsCode.length"
            type="button"
            class="relative z-20 text-white/40 hover:text-white/70 transition-colors"
            @click.stop="deleteLast">
            <Icon icon="memory:arrow-left-box" class="size-9" />
          </button>
        </div>
      </div>

      <p v-if="smsError" class="text-center text-sm text-red-400">{{ smsError }}</p>

      <div class="flex justify-between text-sm">
        <button type="button" class="text-white/35 hover:text-white/70 transition-colors" @click="step = 1">
          ← 返回
        </button>
        <button
          type="button"
          :disabled="countdown > 0 || sending"
          :class="countdown > 0 ? 'text-white/25 cursor-not-allowed' : 'text-indigo-300 hover:text-indigo-200 transition-colors'"
          @click="resendSms">
          {{ countdown > 0 ? `${countdown}s 后重发` : '重新发送' }}
        </button>
      </div>

      <button
        type="button"
        :class="BTN_CLS"
        :style="BTN_STYLE"
        :disabled="!isSmsValid || loading"
        @click="submit">
        <Icon v-if="loading" icon="memory:rotate-clockwise" class="size-4 animate-spin" />
        {{ loading ? '登录中...' : '确认登录' }}
      </button>
    </template>
  </div>
</template>

<script lang="ts" setup>
import { captchaImage, captchaSendSms } from '@api'
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
const smsInputRef = ref<HTMLInputElement>()
const countdown = computed(() => sysStore.smsCountdown)

const form = reactive({ phone: '', captchaCode: '', smsCode: '' })
const captchaImgBase64 = ref('')
const phoneTouched = ref(false)
const loading = ref(false)
const sending = ref(false)
const captchaError = ref('')
const smsError = ref('')

const isPhoneValid = computed(() => /^1[3-9]\d{9}$/.test(form.phone.trim()))
const isCaptchaValid = computed(() => form.captchaCode.trim().length === 4)
const isSmsValid = computed(() => /^\d{4}$/.test(form.smsCode.trim()))
const canSendSms = computed(() => isPhoneValid.value && isCaptchaValid.value)
const maskedPhone = computed(() => form.phone.replace(/(\d{3})\d{4}(\d{4})/, '$1****$2'))

async function refreshCaptcha() {
  captchaImgBase64.value = await captchaImage()
}

function onPhoneInput(e: Event) {
  const t = e.target as HTMLInputElement
  const v = t.value.replace(/\D/g, '').slice(0, 11)
  t.value = v
  form.phone = v
  if (v.length) phoneTouched.value = true
}

function onSmsInput(e: Event) {
  const t = e.target as HTMLInputElement
  const v = t.value.replace(/\D/g, '').slice(0, 4)
  t.value = v
  form.smsCode = v
  smsError.value = ''
}

function deleteLast() {
  form.smsCode = form.smsCode.slice(0, -1)
  smsInputRef.value?.focus()
}

async function sendSms() {
  if (!canSendSms.value) return
  if (countdown.value > 0) {
    step.value = 2
    await nextTick()
    smsInputRef.value?.focus()
    return
  }
  sending.value = true
  try {
    await captchaSendSms({ phone: form.phone, captcha: form.captchaCode })
    sysStore.startSmsCountdown()
    ElNotification.success({ message: '已发送短信验证码' })
    step.value = 2
    await nextTick()
    smsInputRef.value?.focus()
  } catch (error: any) {
    if (error.code === 302) {
      captchaError.value = error.msg || '图形验证码错误'
      await refreshCaptcha()
    }
  } finally {
    sending.value = false
  }
}

async function resendSms() {
  if (countdown.value > 0) return
  step.value = 1
  await nextTick()
  await sendSms()
}

async function submit() {
  if (!isSmsValid.value || loading.value) return
  loading.value = true
  try {
    await authStore.loginWithPhone({ smsCode: form.smsCode, phone: form.phone.trim() })
    emit('success')
  } catch (err: any) {
    if (err.code === 301) smsError.value = err.msg
  } finally {
    loading.value = false
  }
}

onMounted(refreshCaptcha)
</script>
