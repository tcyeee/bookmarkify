<template>
  <div>
    <!-- 个人资料 -->
    <SettingAccountProfile v-if="userInfo" :info="userInfo" />

    <!-- 账户绑定 -->
    <SettingAccountVerify />

    <!-- 注销账户 -->
    <!-- <SettingAccountCancel /> -->
  </div>
</template>

<script lang="ts" setup>
import { queryUserInfo } from "@api";
import type { UserInfoShow } from "@api/typing";

var userInfo = reactive({} as UserInfoShow);

onMounted(() => {
  getUserInfo();
});

function getUserInfo() {
  queryUserInfo().then((res) => {
    Object.assign(userInfo, res);
  });
}
</script>

<style lang="scss" scoped>
</style>