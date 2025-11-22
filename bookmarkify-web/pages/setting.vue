<template>
  <div class="bg-white p-[2rem] min-w-[35rem]">
    <SettingAccount v-show="sysStore.settingTabIndex === 0" />
    <SettingBookmarkManage v-show="sysStore.settingTabIndex === 1" />
    <SettingSettings v-show="sysStore.settingTabIndex === 2" />
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
