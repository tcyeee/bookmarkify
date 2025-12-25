<template>
  <div class="flex items-center gap-2">
    <button class="cy-btn cy-btn-ghost h-10 px-4 min-w-[104px]" :disabled="loading || disabled" @click="openDialog">
      <span v-if="loading">处理中...</span>
      <span v-else>{{ buttonText }}</span>
    </button>

    <dialog ref="dialogRef" class="cy-modal">
      <div class="cy-modal-box max-w-md">
        <h3 class="text-lg font-semibold text-slate-800 dark:text-slate-100">绑定手机号</h3>
        <p v-if="step == 1" class="mt-2 text-sm text-slate-500 dark:text-slate-400">
          用于账号安全、登录验证与找回，请填写常用手机号。
        </p>

        <!-- Step 1: Phone & Captcha -->
        <template v-if="step === 1">
          <div class="mt-4 w-full">
            <label class="cy-input cy-validator w-full">
              <span class="icon--memory-arrow-right-circle icon-size-20 text-gray-500" />
              <input
                v-model="form.phone"
                type="tel"
                inputmode="numeric"
                class="tabular-nums flex-1"
                required
                placeholder="请输入手机号"
                pattern="[0-9]*"
                minlength="11"
                maxlength="11"
                @input="onPhoneInput" />
            </label>
            <p class="text-xs cy-validator-hint" :class="{ hidden: isPhoneValid || !form.phone }">手机号格式错误</p>
          </div>

          <div class="flex items-center gap-3 mt-4">
            <label class="cy-input cy-validator flex-1">
              <span class="icon--memory-arrow-right-circle icon-size-20 text-gray-500" />
              <input
                v-model="form.captchaCode"
                type="text"
                maxlength="5"
                minlength="5"
                class="flex-1"
                required
                placeholder="请输入图形验证码(5位)"
                @input="onCaptchaInput" />
            </label>
            <!-- 验证码图片 -->
            <div>
              <div
                class="flex h-11 w-24 items-center justify-center rounded border border-slate-200 bg-slate-50 text-xs text-slate-400 dark:border-slate-700 dark:bg-slate-800 cursor-pointer"
                v-if="!captchaImgBase64"
                @click="refreshCaptcha">
                加载中…
              </div>
              <img
                v-else
                class="h-10 w-20 rounded border border-slate-200 object-contain dark:border-slate-700 cursor-pointer"
                :src="`data:image/png;base64,${captchaImgBase64}`"
                alt="图形验证码"
                @click="refreshCaptcha" />
            </div>
          </div>
        </template>

        <!-- Step 2: SMS Code -->
        <template v-else-if="step === 2">
          <div class="mt-4">
            <p class="text-sm text-slate-600 dark:text-slate-300 mb-2">已向 {{ maskedPhone }} 发送验证码短信</p>

            <div class="relative w-full max-w-[240px] mx-auto mt-6 mb-4">
              <!-- 隐藏的真实输入框，负责接收输入事件 -->
              <input
                v-model="form.smsCode"
                type="tel"
                inputmode="numeric"
                class="absolute inset-0 opacity-0 z-10 h-12 w-full cursor-text"
                pattern="[0-9]*"
                minlength="4"
                maxlength="4"
                @input="onSmsInput" />

              <!-- 显示的4个方框 -->
              <div class="flex gap-3 justify-center">
                <div
                  v-for="i in 4"
                  :key="i"
                  class="w-12 h-12 rounded-lg border-2 flex items-center justify-center text-xl font-bold transition-all duration-200"
                  :class="[
                    form.smsCode.length === i - 1
                      ? 'border-primary ring-2 ring-primary/20 dark:ring-primary/40'
                      : 'border-slate-200 dark:border-slate-700',
                    form.smsCode[i - 1] ? 'text-slate-800 dark:text-slate-100 bg-slate-50 dark:bg-slate-800' : 'bg-transparent',
                  ]">
                  {{ form.smsCode[i - 1] || '' }}
                </div>
              </div>
            </div>

            <div class="flex items-center justify-between mt-6">
              <button class="text-sm text-slate-500 hover:text-slate-700 dark:hover:text-slate-300" @click="backToStep1">
                返回上一步
              </button>
              <button
                class="text-sm text-primary hover:text-primary-focus disabled:opacity-50 disabled:cursor-not-allowed"
                :disabled="countdown > 0 || sending"
                @click="resendSms">
                {{ countdown > 0 ? `${countdown}秒后可重新发送` : '重新发送' }}
              </button>
            </div>
          </div>
        </template>

        <div class="cy-modal-action mt-10">
          <button class="cy-btn cy-btn-ghost" @click="closeDialog" :disabled="loading">取消</button>

          <button v-if="step === 1" class="cy-btn min-w-[120px]" :disabled="!canSendSms" @click="sendSms">
            <span v-if="sending">发送中...</span>
            <span v-else>发送短信</span>
          </button>

          <button v-if="step === 2" class="cy-btn cy-btn-accent min-w-[120px]" :disabled="!isSmsValid || loading" @click="submit">
            <span v-if="loading">处理中...</span>
            <span v-else>确认</span>
          </button>
        </div>
      </div>
      <form method="dialog" class="cy-modal-backdrop">
        <button @click="closeDialog">close</button>
      </form>
    </dialog>
  </div>
</template>

<script lang="ts" setup>
import { captchaImage } from '@api'
const props = defineProps<{ phone?: string; disabled?: boolean }>()
const emit = defineEmits<{ (e: 'success', phone: string): void }>()

const sysStore = useSysStore()
const dialogRef = ref<HTMLDialogElement>()

const form = reactive({
  phone: props.phone || '',
  captchaCode: '',
  smsCode: '',
})

const step = ref(1)
const countdown = ref(0)
let timer: any = null

const captchaImgBase64 = ref('') // 图形验证码

const loading = ref(false)
const sending = ref(false)
const buttonText = computed(() => (props.phone ? '更换手机号' : '绑定手机号'))
const isPhoneValid = computed(() => /^\d{11}$/.test(form.phone.trim()))
const isHumanValid = computed(() => form.captchaCode.trim().length > 0)
const isSmsValid = computed(() => /^\d{4}$/.test(form.smsCode.trim()))
const canSendSms = computed(() => isPhoneValid.value && isHumanValid.value && !sending.value)
const maskedPhone = computed(() => {
  const p = form.phone.trim()
  if (p.length < 7) return p
  return p.replace(/(\d{3})\d{4}(\d{4})/, '$1****$2')
})

watch(
  () => props.phone,
  (val) => {
    form.phone = val || ''
  }
)

async function refreshCaptcha() {
  captchaImgBase64.value = await captchaImage()
}

async function openDialog() {
  if (!import.meta.client || !dialogRef.value) return

  // Reset state
  step.value = 1
  form.smsCode = ''
  form.captchaCode = ''
  if (props.phone) form.phone = props.phone
  stopCountdown()

  // 请求获取人机验证码
  await refreshCaptcha()

  dialogRef.value.showModal()
  sysStore.togglePreventKeyEventsFlag(true)
}

function closeDialog() {
  if (!import.meta.client || !dialogRef.value) return
  handleDialogClose()
  dialogRef.value.close()
}

async function submit() {
  if (!isPhoneValid.value || !isSmsValid.value) return
  if (form.smsCode !== '0000') {
    ElMessage.error('模拟校验失败，测试请输入 0000')
    return
  }
  loading.value = true
  const phoneValue = form.phone.trim()
  try {
    await new Promise((resolve) => setTimeout(resolve, 300))
    ElNotification.success({ message: '手机号绑定成功（模拟）' })
    emit('success', phoneValue)
    closeDialog()
  } catch (error: any) {
    ElMessage.error(error?.message || '手机号绑定失败，请稍后重试')
  } finally {
    loading.value = false
  }
}

function handleDialogClose() {
  sysStore.togglePreventKeyEventsFlag(false)
  stopCountdown()

  // 清除状态
  form.phone = ''
  form.captchaCode = ''
  form.smsCode = ''
  step.value = 1
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

function onPhoneInput(e: Event) {
  const target = e.target as HTMLInputElement
  const onlyDigits = target.value.replace(/\D/g, '').slice(0, 11)
  target.value = onlyDigits
  form.phone = onlyDigits
}

function onSmsInput(e: Event) {
  const target = e.target as HTMLInputElement
  const onlyDigits = target.value.replace(/\D/g, '').slice(0, 4)
  target.value = onlyDigits
  form.smsCode = onlyDigits
}

function onCaptchaInput(e: Event) {
  const target = e.target as HTMLInputElement
  form.captchaCode = target.value
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

async function sendSms() {
  if (!canSendSms.value) return
  sending.value = true
  try {
    await new Promise((resolve) => setTimeout(resolve, 400))
    ElNotification.success({ message: '已发送短信验证码（模拟，输入 0000 即可）' })
    step.value = 2
    startCountdown()
  } catch (error: any) {
    ElMessage.error(error?.message || '发送失败，请稍后重试')
  } finally {
    sending.value = false
  }
}

async function resendSms() {
  if (countdown.value > 0) return
  await sendSms()
}

function backToStep1() {
  step.value = 1
  stopCountdown()
}
</script>
