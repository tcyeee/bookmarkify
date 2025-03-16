<template>
  <div class="bg-gray-200">
    <div class="h-[7rem]">
      <NuxtLink to="/">
        <div class="cy-btn mt-5 ml-5">返回</div>
      </NuxtLink>
    </div>
    <el-row>
      <el-col :span="6" :offset="2">
        <SettingSideBar />
      </el-col>
      <el-col :span="15">
        <NuxtPage class="rounded-box mb-[6rem] min-h-screen" />
      </el-col>
    </el-row>
    <LayoutFooter class="fixed bottom-0" />
  </div>
</template>

<script lang="ts" setup>
</script>

<style scoped land='scss'>
.min-h-screen {
  min-height: calc(100vh - 13rem);
}
</style>
