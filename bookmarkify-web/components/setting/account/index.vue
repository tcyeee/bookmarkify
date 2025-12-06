<template>
  <div>
    <!-- 个人资料 -->
    <!-- <SettingAccountProfile v-if="userInfo" :info="userInfo" /> -->

    <!-- 账户绑定 -->
    <SettingAccountVerify />

    <!-- 注销账户 -->
    <!-- <SettingAccountCancel /> -->
  </div>
</template>

<script lang="ts" setup>
import { queryUserInfo } from "@api";
import type { UserInfoShow } from "@api/typing";
const userStore = useUserStore();
onMounted(() => {
  userStore.userInfo();
});

</script>

<style lang="scss" scoped>
</style>