<template>
  <ul class="cy-menu bg-white rounded-xl">
    <li v-for="(item,index) in pages">
      <a class="text-gray-400 text-lg font-medium" @click="selectOne(index)" :class="sysStore.settingTabIndex==index?'cy-menu-active':''">
        <span>{{item}}</span>
      </a>
    </li>
  </ul>
</template>

<script lang="ts" setup>
const sysStore = useSysStore();
const pages = ["个人资料", "账户安全", "书签管理", "偏好设置"];

function selectOne(index: number) {
  sysStore.settingTabIndex = index;
}
</script>

<style>
</style>