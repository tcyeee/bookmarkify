<template>
  <div class="flex h-dvh w-full items-center justify-center text-white/70">
    <p>{{ message }}</p>
  </div>
</template>

<script lang="ts" setup>
// Google OAuth 回调承接页：整页跳转登录，从 URL hash 取回 id_token 并完成登录后跳回首页。
definePageMeta({ layout: 'default' })

const { consumeGoogleCallback } = useGoogleOAuth()
const authStore = useAuthStore()
const message = ref('正在完成 Google 登录…')

onMounted(async () => {
  const result = consumeGoogleCallback(location.hash)

  if ('error' in result) {
    message.value = result.error === 'access_denied' ? '已取消 Google 登录' : 'Google 登录失败，即将返回'
    setTimeout(() => navigateTo('/welcome'), 1200)
    return
  }

  try {
    await authStore.loginWithGoogle(result.idToken)
    await authStore.postLoginSetup()
    message.value = '登录成功，正在跳转…'
    await navigateTo('/')
  } catch (err: any) {
    message.value = err?.msg || 'Google 登录失败，即将返回'
    setTimeout(() => navigateTo('/welcome'), 1200)
  }
})
</script>
