<template>
  <!-- 未配置 ClientId 时不渲染，避免出现一个点不动的按钮 -->
  <button v-if="clientId" type="button" class="cy-btn cy-btn-neutral w-full max-w-[320px] gap-2" @click="onClick">
    <Icon icon="flat-color-icons:google" class="size-5" />
    <span>使用 Google 登录</span>
  </button>
</template>

<script lang="ts" setup>
// 整页跳转到 Google 登录，回调页 pages/auth/google/callback.vue 承接后自行完成登录并跳转首页
const { googleClientId: clientId, redirectToGoogleLogin } = useGoogleOAuth()

function onClick() {
  redirectToGoogleLogin()
}
</script>
