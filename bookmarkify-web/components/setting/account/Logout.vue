<template>
  <div v-if="userStore.account">
    <div class="setting-title mt-25">退出登录111</div>
    <div class="setting-subtitle">退出后可重新登录，数据仍将保留。</div>
    <button class="cy-btn rounded-2xl mt-2" @click="handleLogout">退出登录</button>
  </div>
</template>

<script lang="ts" setup>
const userStore = useUserStore()

async function handleLogout() {
  await userStore.logout()
  navigateTo('/welcome')
}
</script>

<style></style>
