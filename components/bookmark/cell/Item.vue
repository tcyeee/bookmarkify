<template>
  <div class="w-[5rem] flex flex-col items-center">
    <div class="w-app h-app rounded-xl bg-gray-100 center shadow overflow-hidden" :class="data.isDev?'border-[4px] border-dashed border-red-200':''">
      <BookmarkLogo :bookmark="props.value" />
    </div>
    <div class="w-[4.5rem] text-sm mt-[0.3rem] text-white opacity-90  truncate text-center">{{ value.title }}</div>
  </div>
</template>

<script lang="ts" setup>
import type { Bookmark } from "~/server/apis/bookmark/typing";
const props = defineProps<{
  value: Bookmark;
}>();
const data = reactive<{
  isDev: boolean;
}>({
  isDev: false,
});

onMounted(() => {
  data.isDev = isLocalhostOrIP(props.value.urlFull);
});

function isLocalhostOrIP(url: string): boolean {
  // 定义匹配 localhost 或者 IP 地址的正则表达式
  const localhostRegex = /^(localhost|127\.0\.0\.1|::1)$/i;
  const ipRegex = /^(?:[0-9]{1,3}\.){3}[0-9]{1,3}$/;

  try {
    // 获取 URL 的主机部分
    const hostname = new URL(url).hostname;
    // 判断是否为 localhost 或者是 IP 地址
    return localhostRegex.test(hostname) || ipRegex.test(hostname);
  } catch {
    return false;
  }
}
</script>
