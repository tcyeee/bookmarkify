<template>
  <div class="flex items-center gap-2">
    <button
      class="cy-btn cy-btn-ghost h-10 px-4 min-w-[104px]"
      :disabled="loading || disabled"
      @click="openDialog">
      <span v-if="loading">处理中...</span>
      <span v-else>{{ buttonText }}</span>
    </button>

    <dialog ref="dialogRef" class="cy-modal">
      <div class="cy-modal-box max-w-md">
        <div class="flex items-center gap-2">
          <span class="icon--memory-speaker icon-size-22 text-slate-500"></span>
          <h3 class="text-lg font-semibold text-slate-800 dark:text-slate-100">绑定手机号</h3>
        </div>
        <p class="mt-2 text-sm text-slate-500 dark:text-slate-400">用于账号安全、登录验证与找回，请填写常用手机号。</p>

        <label class="cy-input cy-validator mt-5">
          <span class="icon--phone icon-size-20 text-gray-500" />
          <input
            v-model="form.phone"
            type="tel"
            class="tabular-nums flex-1"
            required
            placeholder="请输入手机号"
            pattern="[0-9]*"
            minlength="11"
            maxlength="20" />
        </label>
        <p class="text-xs cy-validator-hint" :class="{ 'opacity-0': isPhoneValid || !form.phone }">手机号格式错误</p>

        <div class="cy-modal-action mt-6">
          <button class="cy-btn" @click="closeDialog" :disabled="loading">取消</button>
          <button class="cy-btn cy-btn-accent" :disabled="!isPhoneValid || loading" @click="submit">
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
import { updateUserInfo } from '@api'
import { useUserStore } from '@stores/user.store'
import { useSysStore } from '@stores/sys.store'

const props = defineProps<{
  phone?: string
  disabled?: boolean
}>()

const emit = defineEmits<{
  (e: 'success', phone: string): void
}>()

const userStore = useUserStore()
const sysStore = useSysStore()
const dialogRef = ref<HTMLDialogElement>()

const form = reactive({
  phone: props.phone || '',
})

const loading = ref(false)
const buttonText = computed(() => (props.phone ? '更换手机号' : '绑定手机号'))
const isPhoneValid = computed(() => /^\d{11,20}$/.test(form.phone.trim()))

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
  if (!isPhoneValid.value) return
  loading.value = true
  const phoneValue = form.phone.trim()
  try {
    await updateUserInfo({ phone: phoneValue })
    await userStore.refreshUserInfo()
    ElNotification.success({ message: '手机号绑定成功' })
    emit('success', phoneValue)
    closeDialog()
  } catch (error: any) {
    ElMessage.error(error?.message || '手机号绑定失败，请稍后重试')
  } finally {
    loading.value = false
  }
}
</script>

