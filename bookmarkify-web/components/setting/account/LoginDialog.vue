<template>
  <div class="flex items-center gap-2">
    <button class="cy-btn cy-btn-accent h-10 px-6 min-w-[148px]" :disabled="disabled || loading" @click="openDialog">
      <span v-if="loading">处理中...</span>
      <span v-else>立即登录/注册</span>
    </button>

    <dialog ref="dialogRef" class="cy-modal">
      <div class="cy-modal-box max-w-md">
        <h3 class="text-lg font-semibold text-slate-800 dark:text-slate-100">登录 / 注册</h3>
        <p class="mt-2 text-sm text-slate-500 dark:text-slate-400">
          这里展示登录/注册对话框的样式示例，当前为模拟数据与占位逻辑。
        </p>

        <div class="mt-5 space-y-4">
          <div class="text-sm font-medium text-left text-slate-700 dark:text-slate-200">选择登录方式</div>
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
        </div>

        <div class="mt-6 rounded-xl border border-dashed border-slate-300 bg-slate-50/80 p-4 dark:border-slate-700 dark:bg-slate-900/70">
          <div class="flex items-center gap-2 mb-2">
            <span class="icon--memory-account-box icon-size-20 text-slate-500 dark:text-slate-300"></span>
            <span class="text-sm font-medium text-slate-700 dark:text-slate-200">模拟账号信息</span>
          </div>
          <div class="grid grid-cols-2 gap-2 text-xs text-slate-600 dark:text-slate-300">
            <div class="flex flex-col gap-1">
              <span class="text-slate-500 dark:text-slate-400">UID</span>
              <span class="font-mono text-[11px] break-all">{{ mockAccount.uid }}</span>
            </div>
            <div class="flex flex-col gap-1">
              <span class="text-slate-500 dark:text-slate-400">昵称</span>
              <span>{{ mockAccount.nickName }}</span>
            </div>
            <div class="flex flex-col gap-1">
              <span class="text-slate-500 dark:text-slate-400">邮箱</span>
              <span class="font-mono text-[11px] break-all">{{ mockAccount.email }}</span>
            </div>
            <div class="flex flex-col gap-1">
              <span class="text-slate-500 dark:text-slate-400">登录方式</span>
              <span>{{ currentMethodLabel }}</span>
            </div>
          </div>
        </div>

        <div class="cy-modal-action mt-8">
          <button class="cy-btn cy-btn-ghost" :disabled="loading" @click="handleCancel">暂不登录</button>
          <button class="cy-btn cy-btn-accent min-w-[120px]" :disabled="loading" @click="handleMockSubmit">
            <span v-if="loading">模拟登录中...</span>
            <span v-else>模拟登录</span>
          </button>
        </div>
      </div>
      <form method="dialog" class="cy-modal-backdrop">
        <button @click="handleCancel">close</button>
      </form>
    </dialog>
  </div>
</template>

<script lang="ts" setup>
interface LoginMethod {
  key: 'phone' | 'email' | 'guest'
  label: string
  icon: string
  description: string
}

const props = defineProps<{
  disabled?: boolean
}>()

const emit = defineEmits<{
  (e: 'success'): void
  (e: 'cancel'): void
}>()

const sysStore = useSysStore()
const dialogRef = ref<HTMLDialogElement>()
const loading = ref(false)

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
  {
    key: 'guest',
    label: '临时访客登录',
    icon: 'icon--memory-account-box',
    description: '无需绑定信息，体验基础功能',
  },
]

const selectedMethod = ref<LoginMethod['key']>('phone')

const mockAccount = computed(() => ({
  uid: 'UID-1234-5678-MOCK',
  nickName: '模拟用户',
  email: 'mock_user@example.com',
}))

const currentMethodLabel = computed(() => {
  const current = loginMethods.find((m) => m.key === selectedMethod.value)
  return current ? current.label : ''
})

function openDialog() {
  if (!import.meta.client || !dialogRef.value || props.disabled) return
  dialogRef.value.showModal()
  sysStore.togglePreventKeyEventsFlag(true)
  loading.value = false
}

function closeDialog() {
  if (!import.meta.client || !dialogRef.value) return
  dialogRef.value.close()
}

function handleDialogClose() {
  sysStore.togglePreventKeyEventsFlag(false)
  loading.value = false
}

function handleCancel() {
  handleDialogClose()
  closeDialog()
  emit('cancel')
}

async function handleMockSubmit() {
  loading.value = true
  try {
    await new Promise((resolve) => setTimeout(resolve, 400))
    ElNotification.success({ message: '模拟登录成功' })
    emit('success')
    handleDialogClose()
    closeDialog()
  } catch (error: any) {
    ElMessage.error(error?.message || '模拟登录失败，请稍后重试')
  } finally {
    loading.value = false
  }
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
})
</script>

