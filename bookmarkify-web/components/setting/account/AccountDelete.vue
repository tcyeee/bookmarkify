<template>
  <button class="cy-btn cy-btn-error rounded-2xl" @click="openDelDialog">注销账户</button>

  <dialog ref="delDialogRef" class="cy-modal">
    <div class="cy-modal-box">
      <div class="flex items-end gap-2">
        <Icon icon="memory:alert-box-fill" class="size-7 text-red-500" />
        <h3 class="text-lg font-bold text-red-500">警告：账号注销后将无法找回！</h3>
      </div>
      <p class="mt-4 text-sm text-slate-500 dark:text-slate-400">注销后账号及全部数据将被销毁且不可恢复，请谨慎操作。</p>

      <!-- 已绑定邮箱：要求完整输入本账号邮箱以确认 -->
      <template v-if="boundEmail">
        <p class="mt-5 text-sm text-slate-600 dark:text-slate-300">
          请输入本账号邮箱 <span class="font-semibold text-red-500">{{ boundEmail }}</span> 以确认注销：
        </p>
        <input
          v-model="inputEmail"
          type="email"
          autocomplete="off"
          placeholder="请输入账号邮箱"
          class="cy-input w-full mt-3"
          @keyup.enter="emailMatched && !loading && accountDel()" />
      </template>

      <div class="cy-modal-action mt-8">
        <button class="cy-btn cy-btn-ghost" :disabled="loading" @click="closeDelDialog">取消</button>
        <button class="cy-btn cy-btn-error" :disabled="!canConfirm || loading" @click="accountDel">
          <span v-if="loading">注销中...</span>
          <span v-else>确认注销</span>
        </button>
      </div>
    </div>
    <form method="dialog" class="cy-modal-backdrop">
      <button>close</button>
    </form>
  </dialog>
</template>

<script lang="ts" setup>
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { accountDelete } from '@api'
import { useAuthStore } from '@stores/auth.store'

const authStore = useAuthStore()
const sysStore = useSysStore()

const delDialogRef = ref<HTMLDialogElement | null>(null)
const inputEmail = ref('')
const loading = ref(false)

const boundEmail = computed(() => authStore.account?.email?.trim() || '')
// 已绑定邮箱时需输入一致(忽略大小写)；无邮箱账号(匿名/仅 Google)只需点确认
const emailMatched = computed(() => inputEmail.value.trim().toLowerCase() === boundEmail.value.toLowerCase())
const canConfirm = computed(() => !boundEmail.value || emailMatched.value)

function handleDialogClose() {
  sysStore.togglePreventKeyEventsFlag(false)
  inputEmail.value = ''
}

function openDelDialog() {
  if (!import.meta.client) return
  sysStore.togglePreventKeyEventsFlag(true)
  delDialogRef.value?.showModal()
}

function closeDelDialog() {
  if (!import.meta.client) return
  delDialogRef.value?.close()
}

async function accountDel() {
  if (!canConfirm.value || loading.value) return
  loading.value = true
  try {
    await accountDelete(boundEmail.value || undefined)
    closeDelDialog()
    // 复用退出登录的清理流程：重置各 store、清缓存、跳转 /welcome
    await authStore.logout()
    ElMessage.success('账号已注销')
  } catch {
    // 错误已由 http 层统一提示（如邮箱不匹配 E116）
  } finally {
    loading.value = false
  }
}

onMounted(() => {
  const dialog = delDialogRef.value
  dialog?.addEventListener('close', handleDialogClose)
  dialog?.addEventListener('cancel', handleDialogClose)
})

onBeforeUnmount(() => {
  const dialog = delDialogRef.value
  dialog?.removeEventListener('close', handleDialogClose)
  dialog?.removeEventListener('cancel', handleDialogClose)
})
</script>
