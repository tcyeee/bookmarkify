<template>
  <div class="flex h-dvh w-full items-center justify-center text-white/70">
    <p>{{ message }}</p>
  </div>
</template>

<script lang="ts" setup>
// GitHub OAuth 回调承接页。两种入口，用是否有 window.opener 区分：
// - 弹窗场景（桌面端）：仅把 code/state 回传给打开它的主窗口后自关，不调任何业务接口。
// - 整页跳转场景（移动端，见 useGithubOAuth.redirectToGithubLogin）：本页自己校验
//   sessionStorage 里存的 state 并直接完成登录，因为已经没有"主窗口"可以回传了。
import { useAuthStore } from '@stores/auth.store'

definePageMeta({ layout: 'default' })

const message = ref('正在完成 GitHub 授权…')
const route = useRoute()
const authStore = useAuthStore()

onMounted(async () => {
  const code = (route.query.code as string) || ''
  const state = (route.query.state as string) || ''
  const error = (route.query.error_description as string) || (route.query.error as string) || ''

  if (window.opener) {
    window.opener.postMessage({ source: 'bookmarkify-github-oauth', code, state, error }, location.origin)
    message.value = '授权完成，正在关闭…'
    setTimeout(() => window.close(), 300)
    return
  }

  const expectedState = sessionStorage.getItem(GITHUB_OAUTH_REDIRECT_STATE_KEY) || ''
  sessionStorage.removeItem(GITHUB_OAUTH_REDIRECT_STATE_KEY)

  if (error) {
    message.value = error === 'access_denied' ? '已取消 GitHub 登录' : 'GitHub 登录失败，即将返回'
    setTimeout(() => navigateTo('/login'), 1200)
    return
  }
  if (!expectedState || state !== expectedState || !code) {
    message.value = '授权异常，即将返回'
    setTimeout(() => navigateTo('/login'), 1200)
    return
  }

  try {
    const redirectUri = `${location.origin}/auth/github/callback`
    await authStore.loginWithGithub(code, redirectUri)
    await authStore.postLoginSetup()
    message.value = '登录成功，正在跳转…'
    await navigateTo('/')
  } catch (err: any) {
    message.value = err?.msg || 'GitHub 登录失败，即将返回'
    setTimeout(() => navigateTo('/login'), 1200)
  }
})
</script>
