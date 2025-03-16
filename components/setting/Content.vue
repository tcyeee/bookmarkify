<template>
  <div class="bg-white p-[1.5rem]">
    <el-button @click="testLing()" type="primary">Test Login Statue</el-button>
  </div>
</template>

<script lang="ts" setup>
const socketStore = useWebSocketStore();
const userStore = useUserStore();

function testLing() {
  socketStore.connect(userStore.auth.token!);
}
</script>

<style>
</style>