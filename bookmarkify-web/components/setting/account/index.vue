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
const userStore = useUserStore();

onMounted(() => {
  userStore.getUserInfo();
});

</script>

<style lang="scss" scoped>
</style>