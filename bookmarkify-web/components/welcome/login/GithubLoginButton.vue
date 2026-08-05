<template>
  <!-- 未配置 ClientId 时不渲染，避免出现点不动的按钮 -->
  <button
    v-if="githubClientId"
    type="button"
    class="cy-btn cy-btn-neutral w-full max-w-[320px] gap-2"
    :disabled="loading"
    @click="onClick">
    <IconMdiGithub class="size-5" />
    <span>{{ loading ? '授权中…' : '使用 GitHub 登录' }}</span>
  </button>
</template>

<script lang="ts" setup>
import IconMdiGithub from '~icons/mdi/github'
import { useAuthStore } from '@stores/auth.store'

// redirect: 整页跳转授权而非弹窗，供移动端登录页使用（弹窗在移动端浏览器/内置浏览器中不可靠）。
// 该模式下登录动作在回调页 pages/auth/github/callback.vue 内完成，success 事件不会触发。
const props = defineProps<{ redirect?: boolean }>()
const emit = defineEmits<{ (e: 'success'): void }>()

const authStore = useAuthStore()
const { githubClientId, requestGithubCode, redirectToGithubLogin } = useGithubOAuth()
const loading = ref(false)

async function onClick() {
  if (loading.value) return
  if (props.redirect) {
    redirectToGithubLogin()
    return
  }
  loading.value = true
  try {
    const { code, redirectUri } = await requestGithubCode()
    await authStore.loginWithGithub(code, redirectUri)
    emit('success')
  } catch (err: any) {
    // 用户取消等本地错误在这里提示；后端 1xx 业务错误由 http 客户端统一弹窗
    if (err?.message && err.message !== '已取消 GitHub 授权') useToastStore().error(err.message)
  } finally {
    loading.value = false
  }
}
</script>
