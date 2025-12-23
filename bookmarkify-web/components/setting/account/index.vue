<template>
  <div>
    <!-- 个人资料 -->
    <SettingAccountProfile :info="userStore.account" />

    <!-- TODO 账户绑定 -->
    <!-- <SettingAccountVerify /> -->

    <!-- 退出登录 -->
    <SettingAccountLogout v-if="accountStatus === AuthStatusEnum.AUTHED" />

    <!-- TODO 注销账户 -->
    <!-- <SettingAccountCancel /> -->
  </div>
</template>

<script lang="ts" setup>
import { AuthStatusEnum } from '@typing'

const userStore = useUserStore()

const accountStatus = computed<AuthStatusEnum | undefined>(() => userStore.authStatus)
onMounted(() => {})
</script>

<style lang="scss" scoped></style>
