<template>
  <div>
    <!-- 个人资料 -->
    <SettingAccountProfile v-if="userStore.account" :info="userStore.account" />

    <!-- 账户绑定 -->
    <SettingAccountVerify />

    <!-- 注销账户 -->
    <SettingAccountCancel />
  </div>
</template>

<script lang="ts" setup>
const userStore = useUserStore()

onMounted(() => {})
</script>

<style lang="scss" scoped></style>
