<template>
  <div class="flex h-screen w-screen items-center justify-center text-white/70">
    <p>{{ message }}</p>
  </div>
</template>

<script lang="ts" setup>
// GitHub OAuth 回调承接页：仅把 code/state 回传给打开它的主窗口后自关，不调任何业务接口。
definePageMeta({ layout: 'default' })

const message = ref('正在完成 GitHub 授权…')
const route = useRoute()

onMounted(() => {
  const payload = {
    source: 'bookmarkify-github-oauth',
    code: (route.query.code as string) || '',
    state: (route.query.state as string) || '',
    error: (route.query.error_description as string) || (route.query.error as string) || '',
  }
  if (window.opener) {
    window.opener.postMessage(payload, location.origin)
    message.value = '授权完成，正在关闭…'
    setTimeout(() => window.close(), 300)
  } else {
    // 非弹窗场景（用户直接访问）兜底回首页
    message.value = '授权异常，即将返回首页'
    setTimeout(() => navigateTo('/'), 1200)
  }
})
</script>
