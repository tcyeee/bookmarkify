<template>
  <div class="flex items-center gap-2">
    <button class="cy-btn cy-btn-ghost h-10 px-4 min-w-[104px]" :disabled="loading || disabled" @click="openDialog">
      <span v-if="loading">处理中...</span>
      <span v-else>{{ buttonText }}</span>
    </button>

    <dialog ref="dialogRef" class="cy-modal">
      <div class="cy-modal-box max-w-md">
        <h3 class="text-lg font-semibold text-slate-800 dark:text-slate-100">绑定手机号</h3>
        <p class="mt-2 text-sm text-slate-500 dark:text-slate-400">用于账号安全、登录验证与找回，请填写常用手机号。</p>

        <label class="cy-input cy-validator mt-5">
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
        <p class="text-xs cy-validator-hint" :class="{ 'opacity-0': isPhoneValid || !form.phone }">手机号格式错误</p>

        <label class="cy-input cy-validator mt-4">
          <span class="icon--memory-robot icon-size-20 text-gray-500" />
          <input
            v-model="form.humanCode"
            type="text"
            class="flex-1"
            required
            placeholder="请输入人机验证验证码"
            @input="onHumanInput" />
        </label>
        <p class="text-xs text-slate-500 dark:text-slate-400">输入人机验证后才能发送短信验证码（模拟）。</p>

        <div class="flex items-center gap-3 mt-4">
          <label class="cy-input cy-validator flex-1">
            <span class="icon--memory-lock icon-size-20 text-gray-500" />
            <input
              v-model="form.smsCode"
              type="tel"
              inputmode="numeric"
              class="tabular-nums flex-1"
              required
              placeholder="短信验证码（输入 0000 通过）"
              pattern="[0-9]*"
              minlength="4"
              maxlength="4"
              @input="onSmsInput" />
          </label>
          <button class="cy-btn cy-btn-ghost min-w-[120px]" :disabled="!canSendSms" @click="sendSms">
            <span v-if="sending">发送中...</span>
            <span v-else>发送短信</span>
          </button>
        </div>
        <p class="text-xs cy-validator-hint" :class="{ 'opacity-0': isSmsValid || !form.smsCode }">短信验证码需为4位数字</p>

        <div class="cy-modal-action mt-6">
          <button class="cy-btn" @click="closeDialog" :disabled="loading">取消</button>
          <button class="cy-btn cy-btn-accent" :disabled="!isPhoneValid || !isSmsValid || loading" @click="submit">
            <span v-if="loading">绑定中...</span>
            <span v-else>确认绑定</span>
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
import { computed, reactive, ref, watch } from 'vue'
import { useSysStore } from '@stores/sys.store'

const props = defineProps<{
  phone?: string
  disabled?: boolean
}>()

const emit = defineEmits<{
  (e: 'success', phone: string): void
}>()

const sysStore = useSysStore()
const dialogRef = ref<HTMLDialogElement>()

const form = reactive({
  phone: props.phone || '',
  humanCode: '',
  smsCode: '',
})

const loading = ref(false)
const sending = ref(false)
const buttonText = computed(() => (props.phone ? '更换手机号' : '绑定手机号'))
const isPhoneValid = computed(() => /^\d{11}$/.test(form.phone.trim()))
const isHumanValid = computed(() => form.humanCode.trim().length > 0)
const isSmsValid = computed(() => /^\d{4}$/.test(form.smsCode.trim()))
const canSendSms = computed(() => isPhoneValid.value && isHumanValid.value && !sending.value)

watch(
  () => props.phone,
  (val) => {
    form.phone = val || ''
  }
)

function openDialog() {
  if (!import.meta.client || !dialogRef.value) return
  dialogRef.value.showModal()
  sysStore.preventKeyEventsFlag = true
}

function closeDialog() {
  if (!import.meta.client || !dialogRef.value) return
  dialogRef.value.close()
  sysStore.preventKeyEventsFlag = false
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

function onHumanInput(e: Event) {
  const target = e.target as HTMLInputElement
  form.humanCode = target.value
}

async function sendSms() {
  if (!canSendSms.value) return
  sending.value = true
  try {
    await new Promise((resolve) => setTimeout(resolve, 400))
    ElNotification.success({ message: '已发送短信验证码（模拟，输入 0000 即可）' })
  } catch (error: any) {
    ElMessage.error(error?.message || '发送失败，请稍后重试')
  } finally {
    sending.value = false
  }
}
</script>
