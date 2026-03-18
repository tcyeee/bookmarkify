<template>
  <div class="flex items-center gap-2">
    <button
      v-if="!props.hideTrigger"
      class="cy-btn cy-btn-ghost h-10 px-4 min-w-[104px]"
      :disabled="loading || disabled"
      @click="openDialog">
      <span v-if="loading">处理中...</span>
      <span v-else>账号密码登录</span>
    </button>

    <dialog ref="dialogRef" class="cy-modal">
      <div class="cy-modal-box max-w-md">
        <h3 class="text-lg font-semibold text-slate-800 dark:text-slate-100">账号密码登录</h3>
        <p class="mt-2 text-sm text-slate-500 dark:text-slate-400">使用绑定的手机号或邮箱配合密码登录。</p>

        <div class="mt-4 space-y-3 w-full">
          <label class="cy-input cy-validator w-full">
            <Icon icon="memory:arrow-right-circle" class="size-5 text-gray-500" />
            <input
              v-model="form.account"
              type="text"
              class="flex-1"
              required
              placeholder="手机号 / 邮箱"
              autocomplete="username" />
          </label>
          <label class="cy-input cy-validator w-full">
            <Icon icon="memory:lock" class="size-5 text-gray-500" />
            <input
              v-model="form.password"
              :type="showPassword ? 'text' : 'password'"
              class="flex-1"
              required
              placeholder="密码"
              autocomplete="current-password"
              @keydown.enter="submit" />
            <button type="button" class="text-gray-400 hover:text-gray-600 dark:hover:text-gray-300" @click="showPassword = !showPassword">
              <span v-if="showPassword" class="icon--memory-eye-off icon-size-18" />
              <Icon v-else icon="memory:eye" class="size-[18px]" />
            </button>
          </label>
          <div v-if="errorMsg" class="text-sm text-red-500">{{ errorMsg }}</div>
        </div>

        <div class="cy-modal-action mt-8">
          <button class="cy-btn cy-btn-ghost" @click="closeDialog" :disabled="loading">取消</button>
          <button class="cy-btn cy-btn-accent min-w-[120px]" :disabled="!canSubmit || loading" @click="submit">
            <span v-if="loading">登录中...</span>
            <span v-else>登录</span>
          </button>
        </div>
      </div>
    </dialog>
  </div>
</template>

<script lang="ts" setup>
import { useAuthStore } from '@stores/auth.store'

const props = defineProps<{ disabled?: boolean; hideTrigger?: boolean }>()
const emit = defineEmits<{ (e: 'success', account: string): void }>()

const authStore = useAuthStore()
const dialogRef = ref<HTMLDialogElement>()

const form = reactive({ account: '', password: '' })
const loading = ref(false)
const showPassword = ref(false)
const errorMsg = ref('')

const canSubmit = computed(() => form.account.trim().length > 0 && form.password.length > 0)

function openDialog() {
  if (!import.meta.client || !dialogRef.value) return
  form.account = ''
  form.password = ''
  errorMsg.value = ''
  showPassword.value = false
  dialogRef.value.showModal()
  useSysStore().togglePreventKeyEventsFlag(true)
}

function closeDialog() {
  if (!import.meta.client || !dialogRef.value) return
  handleDialogClose()
  dialogRef.value.close()
}

defineExpose({ openDialog, closeDialog })

async function submit() {
  if (!canSubmit.value || loading.value) return
  errorMsg.value = ''
  loading.value = true
  try {
    await authStore.loginWithPassword({ account: form.account.trim(), password: form.password })
    emit('success', form.account.trim())
    closeDialog()
  } catch (err: any) {
    errorMsg.value = err?.msg || '账号或密码错误'
  } finally {
    loading.value = false
  }
}

function handleDialogClose() {
  useSysStore().togglePreventKeyEventsFlag(false)
  form.account = ''
  form.password = ''
  errorMsg.value = ''
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
