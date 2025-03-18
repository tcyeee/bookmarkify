<template>
  <div class="bg-white p-[1.5rem]">
    <div class="cy-btn">button</div>
    <el-button @click="testLing()" type="primary">Test Login Statue</el-button>
  </div>
</template>

<script lang="ts" setup>
const socketStore = useWebSocketStore();
const userStore = useUserStore();

function testLing() {}
</script>

<style lang="scss" scoped>
</style>