<template>
  <div class="bg-white p-[1.5rem]">

    <el-button @click="testLing()" type="primary">Test Login Statue</el-button>
  </div>
</template>

<script lang="ts" setup>
import { sysLinkTest } from "~/server/apis";

function testLing() {
  console.log("test");
  sysLinkTest();
}
</script>

<style>
</style>