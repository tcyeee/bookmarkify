<!-- components/setting/account/BindGithubModal.vue -->
<template>
  <div class="flex items-center gap-2">
    <button
      v-if="props.githubLogin"
      class="cy-btn cy-btn-ghost h-10 px-4 min-w-[100px]"
      :disabled="loading || disabled"
      @click="handleUnbind">
      <span v-if="loading">处理中...</span>
      <span v-else>解绑</span>
    </button>
    <button
      v-else
      class="cy-btn cy-btn-primary cy-btn-outline h-10 px-4 min-w-[100px]"
      :disabled="loading || disabled || !githubClientId"
      @click="handleBind">
      <span>{{ loading ? '授权中...' : '关联 GitHub' }}</span>
    </button>
  </div>
</template>

<script lang="ts" setup>
import { bindGithub, unbindGithub } from '@api'
import { useAuthStore } from '@stores/auth.store'

const props = defineProps<{ githubLogin?: string | null; disabled?: boolean }>()
const emit = defineEmits<{ (e: 'success'): void }>()

const authStore = useAuthStore()
const { githubClientId, requestGithubCode } = useGithubOAuth()
const loading = ref(false)

async function handleBind() {
  if (loading.value) return
  loading.value = true
  try {
    const { code, redirectUri } = await requestGithubCode()
    const result = await bindGithub(code, redirectUri)
    authStore.account = { ...authStore.account, ...result } as any
    ElNotification.success({ message: 'GitHub 关联成功' })
    emit('success')
  } catch (err: any) {
    if (err?.message && err.message !== '已取消 GitHub 授权') ElMessage.error(err.message)
  } finally {
    loading.value = false
  }
}

async function handleUnbind() {
  try {
    await ElMessageBox.confirm('解绑后将无法用此 GitHub 账号登录，确定解绑吗？', '解绑 GitHub', {
      confirmButtonText: '解绑',
      cancelButtonText: '取消',
      type: 'warning',
    })
  } catch {
    return // 用户取消
  }
  loading.value = true
  try {
    const result = await unbindGithub()
    authStore.account = { ...authStore.account, ...result } as any
    ElNotification.success({ message: '已解绑 GitHub' })
    emit('success')
  } catch {
    // http 客户端已统一提示
  } finally {
    loading.value = false
  }
}
</script>
