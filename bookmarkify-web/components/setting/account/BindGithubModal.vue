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
import { useMediaQuery } from '@vueuse/core'
import { bindGithub, unbindGithub } from '@api'
import { useAuthStore } from '@stores/auth.store'

const props = defineProps<{ githubLogin?: string | null; disabled?: boolean }>()
const emit = defineEmits<{ (e: 'success'): void }>()

const authStore = useAuthStore()
const { githubClientId, requestGithubCode, redirectToGithubBind } = useGithubOAuth()
const loading = ref(false)

// 弹窗在移动端浏览器/微信内置浏览器中不可靠，和登录入口一样改走整页跳转
// （回调页 pages/auth/github/callback.vue 按 intent='bind' 直接完成关联）
const isMobile = useMediaQuery('(max-width: 640px)')

async function handleBind() {
  if (loading.value) return
  if (isMobile.value) {
    redirectToGithubBind()
    return
  }
  loading.value = true
  try {
    const { code, redirectUri } = await requestGithubCode()
    const result = await bindGithub(code, redirectUri)
    authStore.account = { ...authStore.account, ...result } as any
    useToastStore().success('GitHub 关联成功')
    emit('success')
  } catch (err: any) {
    if (err?.message && err.message !== '已取消 GitHub 授权') useToastStore().error(err.message)
  } finally {
    loading.value = false
  }
}

async function handleUnbind() {
  try {
    await useConfirmStore().confirm('解绑后将无法用此 GitHub 账号登录，确定解绑吗？', {
      title: '解绑 GitHub',
      confirmText: '解绑',
      cancelText: '取消',
      type: 'warning',
    })
  } catch {
    return // 用户取消
  }
  loading.value = true
  try {
    const result = await unbindGithub()
    authStore.account = { ...authStore.account, ...result } as any
    useToastStore().success('已解绑 GitHub')
    emit('success')
  } catch {
    // http 客户端已统一提示
  } finally {
    loading.value = false
  }
}
</script>
