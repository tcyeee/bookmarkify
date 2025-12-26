<template>
  <div class="flex items-center gap-2">
    <button
      v-if="!props.hideTrigger"
      class="cy-btn cy-btn-ghost h-10 px-4 min-w-[104px]"
      :disabled="loading || disabled"
      @click="openDialog">
      <span v-if="loading">处理中...</span>
      <span v-else>{{ buttonText }}</span>
    </button>

    <dialog ref="dialogRef" class="cy-modal">
      <div class="cy-modal-box max-w-md">
        <h3 class="text-lg font-semibold text-slate-800 dark:text-slate-100">绑定邮箱</h3>
        <p v-if="step == 1" class="mt-2 text-sm text-slate-500 dark:text-slate-400">
          用于账号安全、登录验证与找回，请填写常用邮箱。
        </p>

        <!-- Step 1: Email -->
        <template v-if="step === 1">
          <div class="mt-4 w-full">
            <label class="cy-input cy-validator w-full">
              <span class="icon--memory-arrow-right-circle icon-size-20 text-gray-500" />
              <input
                v-model="form.email"
                type="email"
                class="flex-1"
                required
                placeholder="请输入邮箱地址"
                @input="onEmailInput" />
            </label>
          </div>
        </template>

        <!-- Step 2: Email Code -->
        <template v-else-if="step === 2">
          <div class="mt-4">
            <p class="text-sm text-slate-600 dark:text-slate-300 mb-2">已向 {{ maskedEmail }} 发送验证码邮件</p>

            <div class="relative w-full max-w-[280px] mx-auto mt-6 mb-4">
              <!-- 隐藏的真实输入框，负责接收输入事件 -->
              <input
                ref="emailCodeInputRef"
                v-model="form.emailCode"
                type="tel"
                inputmode="numeric"
                class="absolute inset-0 opacity-0 z-10 h-12 w-full cursor-text"
                pattern="[0-9]*"
                minlength="4"
                maxlength="4"
                @input="onEmailCodeInput" />

              <div class="flex gap-3 justify-center items-center">
                <div
                  v-for="i in 4"
                  :key="i"
                  class="w-11 h-11 rounded-lg border-2 flex items-center justify-center text-lg font-semibold transition-all duration-200"
                  :class="[
                    form.emailCode.length === i - 1
                      ? 'border-primary ring-2 ring-primary/20 dark:ring-primary/40'
                      : 'border-slate-200 dark:border-slate-700',
                    form.emailCode[i - 1] ? 'text-slate-800 dark:text-slate-100 bg-slate-50 dark:bg-slate-800' : 'bg-transparent',
                  ]">
                  {{ form.emailCode[i - 1] || '' }}
                </div>
                <button
                  v-if="form.emailCode.length"
                  type="button"
                  class="relative z-20 flex items-center justify-center text-gray-400 select-none cursor-pointer"
                  @click="deleteLastEmailCodeDigit">
                  <span class="icon--memory-arrow-left-box icon-size-40" />
                </button>
              </div>
              <div v-if="emailCodeError" class="mt-2 text-sm text-red-500 text-center">
                {{ emailCodeError }}
              </div>
            </div>

            <div class="flex items-center justify-between mt-6">
              <button class="text-sm text-slate-500 hover:text-slate-700 dark:hover:text-slate-300" @click="backToStep1">
                返回上一步
              </button>
              <button
                class="text-sm text-primary hover:text-primary-focus disabled:opacity-50 disabled:cursor-not-allowed"
                :disabled="countdown > 0 || sending"
                @click="resendEmailCode">
                {{ countdown > 0 ? `${countdown}秒后可重新发送` : '重新发送' }}
              </button>
            </div>
          </div>
        </template>

        <div class="cy-modal-action mt-10">
          <button class="cy-btn cy-btn-ghost" @click="closeDialog" :disabled="loading">取消</button>

          <button v-if="step === 1" class="cy-btn min-w-[120px]" :disabled="!canSendEmail" @click="sendEmailCode">
            <span v-if="sending">发送中...</span>
            <span v-else>发送邮件</span>
          </button>

          <button
            v-if="step === 2"
            class="cy-btn cy-btn-accent min-w-[120px]"
            :disabled="!isEmailCodeValid || loading"
            @click="submit">
            <span v-if="loading">处理中...</span>
            <span v-else>确认</span>
          </button>
        </div>
      </div>
    </dialog>
  </div>
</template>

<script lang="ts" setup>
import { captchaSendEmail, captchaVerifyEmail } from '@api'
const props = defineProps<{ email?: string; disabled?: boolean; hideTrigger?: boolean }>()
const emit = defineEmits<{ (e: 'success', email: string): void }>()

const sysStore = useSysStore()
const userStore = useUserStore()
const dialogRef = ref<HTMLDialogElement>()
const emailCodeInputRef = ref<HTMLInputElement>()

const form = reactive({
  email: props.email || '',
  emailCode: '',
})

const step = ref(1)
const countdown = ref(0)
let timer: any = null

const loading = ref(false)
const sending = ref(false)
const emailCodeError = ref('')
const buttonText = computed(() => (props.email ? '更换邮箱' : '绑定邮箱'))
const isEmailValid = computed(() => /^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(form.email.trim()))
const isEmailCodeValid = computed(() => /^\d{4}$/.test(form.emailCode.trim()))
const canSendEmail = computed(() => isEmailValid.value && !sending.value)
const maskedEmail = computed(() => {
  const email = form.email.trim()
  if (!email.includes('@')) return email
  const [userRaw, domainRaw] = email.split('@')
  const user = userRaw || ''
  const domain = domainRaw || ''
  if (!domain) return email
  if (user.length <= 2) return `${user[0] || ''}***@${domain}`
  return `${user.slice(0, 2)}***@${domain}`
})

watch(
  () => props.email,
  (val) => {
    form.email = val || ''
  }
)

async function openDialog() {
  if (!import.meta.client || !dialogRef.value) return

  // Reset state
  step.value = 1
  form.emailCode = ''
  if (props.email) form.email = props.email
  stopCountdown()

  dialogRef.value.showModal()
  sysStore.togglePreventKeyEventsFlag(true)
}

function closeDialog() {
  if (!import.meta.client || !dialogRef.value) return
  handleDialogClose()
  dialogRef.value.close()
}

defineExpose({ openDialog, closeDialog })

async function submit() {
  if (!isEmailValid.value || !isEmailCodeValid.value) return
  userStore
    .loginWithEmail({ email: form.email, code: form.emailCode })
    .then(() => {
      ElNotification.success({ message: '邮箱绑定成功' })
      emit('success', form.email.trim())
      closeDialog()
    })
    .catch((err: any) => {
      if (err.code === 301) {
        emailCodeError.value = err.msg
        return
      }
    })
}

function handleDialogClose() {
  sysStore.togglePreventKeyEventsFlag(false)
  stopCountdown()

  // 清除状态
  form.email = ''
  form.emailCode = ''
  step.value = 1
  emailCodeError.value = ''
}

onMounted(() => {
  if (!import.meta.client || !dialogRef.value) return
  dialogRef.value.addEventListener('close', handleDialogClose)
  dialogRef.value.addEventListener('cancel', handleDialogClose)
})

onBeforeUnmount(() => {
  if (!import.meta.client || !dialogRef.value) return
  dialogRef.value.removeEventListener('close', handleDialogClose)
  dialogRef.value.removeEventListener('cancel', handleDialogClose)
  stopCountdown()
})

function onEmailInput(e: Event) {
  const target = e.target as HTMLInputElement
  form.email = target.value.trim()
}

function onEmailCodeInput(e: Event) {
  const target = e.target as HTMLInputElement
  const onlyDigits = target.value.replace(/\D/g, '').slice(0, 6)
  target.value = onlyDigits
  form.emailCode = onlyDigits
  if (emailCodeError.value) {
    emailCodeError.value = ''
  }
}

function startCountdown() {
  countdown.value = 60
  if (timer) clearInterval(timer)
  timer = setInterval(() => {
    countdown.value--
    if (countdown.value <= 0) {
      stopCountdown()
    }
  }, 1000)
}

function stopCountdown() {
  if (timer) {
    clearInterval(timer)
    timer = null
  }
}

async function sendEmailCode() {
  if (!canSendEmail.value) return
  emailCodeError.value = ''
  sending.value = true
  try {
    await captchaSendEmail({ email: form.email })
    ElNotification.success({ message: '已发送邮箱验证码' })
    step.value = 2
    startCountdown()
    await nextTick()
    emailCodeInputRef.value?.focus()
  } catch (error: any) {
    ElMessage.error(error?.message || '发送失败，请稍后重试')
  } finally {
    sending.value = false
  }
}

async function resendEmailCode() {
  if (countdown.value > 0) return
  emailCodeError.value = ''
  await sendEmailCode()
}

function deleteLastEmailCodeDigit() {
  if (!form.emailCode) return
  form.emailCode = form.emailCode.slice(0, -1)
  if (emailCodeError.value) {
    emailCodeError.value = ''
  }
}

function backToStep1() {
  step.value = 1
  stopCountdown()
}
</script>
