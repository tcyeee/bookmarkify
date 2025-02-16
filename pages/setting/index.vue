<template>
  <div class="bg-gray-200">
    <NuxtLink to="/">
      <div class="cy-btn mt-5 ml-5">返回</div>
    </NuxtLink>
    <el-row class="h-screen pt-[6%]">
      <el-col :span="8" class="relative">
        <SettingSideBar class="absolute right-[2rem]" />
      </el-col>
      <el-col :span="16" class="relative">
        <SettingContent class="absolute left-[2rem] rounded-box w-[90%]" />
      </el-col>
    </el-row>
  </div>
</template>

<script lang="ts" setup>
</script>

<style>
</style>