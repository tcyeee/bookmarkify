<template>
  <!-- 未配置 ClientId 时不渲染，避免出现点不动的按钮 -->
  <button
    v-if="githubClientId"
    type="button"
    class="cy-btn cy-btn-neutral w-full max-w-[320px] gap-2"
    :disabled="loading"
    @click="onClick">
    <Icon icon="mdi:github" class="size-5" />
    <span>{{ loading ? '授权中…' : '使用 GitHub 登录' }}</span>
  </button>
</template>

<script lang="ts" setup>
import { useAuthStore } from '@stores/auth.store'

const emit = defineEmits<{ (e: 'success'): void }>()

const authStore = useAuthStore()
const { githubClientId, requestGithubCode } = useGithubOAuth()
const loading = ref(false)

async function onClick() {
  if (loading.value) return
  loading.value = true
  try {
    const { code, redirectUri } = await requestGithubCode()
    await authStore.loginWithGithub(code, redirectUri)
    emit('success')
  } catch (err: any) {
    // 用户取消等本地错误在这里提示；后端 1xx 业务错误由 http 客户端统一弹窗
    if (err?.message && err.message !== '已取消 GitHub 授权') ElMessage.error(err.message)
  } finally {
    loading.value = false
  }
}
</script>
