<template>
  <div class="cursor-pointer">{{data.currentTime}}</div>
</template>

<script lang="ts" setup>
const now = new Date();
const data = reactive<{
  currentTime: string;
}>({
  currentTime: getCurrentTimeStr(now),
});

onMounted(() => {
  // 对时间进行更新
  setInterval(() => updateTime(), 1000);
});

function getCurrentTimeStr(current: Date): string {
  let minutes: string = current.getMinutes().toString();
  minutes = current.getMinutes() < 10 ? `0${minutes}` : minutes;
  return `${current.getHours().toString()} : ${minutes}`;
}

function updateTime() {
  data.currentTime = getCurrentTimeStr(new Date());
}
</script>

<style>
</style>