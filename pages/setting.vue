<template>
  <div class="bg-white p-[1.5rem]">
    <SettingUserProfile v-show="sysStore.settingTabIndex === 0" />
    <SettingAccount v-show="sysStore.settingTabIndex === 1" />
    <SettingBookmarkManage v-show="sysStore.settingTabIndex === 2" />
    <SettingSettings v-show="sysStore.settingTabIndex === 3" />
  </div>
</template>

<script lang="ts" setup>
definePageMeta({ layout: "setting" });
const sysStore = useSysStore();
</script>

<style scoped land='scss'>
.min-h-screen {
  min-height: calc(100vh - 13rem);
}
</style>
