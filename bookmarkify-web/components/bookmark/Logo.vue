<template>
  <div class="bookmark-icon">
    <!-- 如果网站有LOGO -->
    <el-image v-if="bookmark.iconActivity" :src="bookmark.iconUrlFull" @error="imageLoadFailed" :class="sm?'w-ico-dir h-ico-dir':bookmark.iconHd?'w-app h-app':'w-ico h-ico'">
      <template #placeholder>
        <svg :class="sm?'w-ico-dir h-ico-dir':'w-ico h-ico'" viewBox="0 0 48 48" fill="none" xmlns="http://www.w3.org/2000/svg">
          <path d="M24 40C32.8366 40 40 32.8366 40 24C40 15.1634 32.8366 8 24 8C15.1634 8 8 15.1634 8 24C8 32.8366 15.1634 40 24 40Z" fill="none" stroke="#4b5563" stroke-width="4" stroke-linejoin="round" />
          <path d="M37.5641 15.5098C41.7833 15.878 44.6787 17.1724 45.2504 19.306C46.3939 23.5737 37.8068 29.5827 26.0705 32.7274C14.3343 35.8721 3.89316 34.9617 2.74963 30.694C2.1505 28.458 4.22245 25.744 8.01894 23.2145" stroke="#4b5563" stroke-width="4" stroke-linecap="round" stroke-linejoin="round" />
        </svg>
      </template>
      <template #error>
        <svg :class="sm?'w-ico-dir h-ico-dir':'w-ico h-ico'" viewBox="0 0 48 48" fill="none" xmlns="http://www.w3.org/2000/svg">
          <path d="M24 40C32.8366 40 40 32.8366 40 24C40 15.1634 32.8366 8 24 8C15.1634 8 8 15.1634 8 24C8 32.8366 15.1634 40 24 40Z" fill="none" stroke="#4b5563" stroke-width="4" stroke-linejoin="round" />
          <path d="M37.5641 15.5098C41.7833 15.878 44.6787 17.1724 45.2504 19.306C46.3939 23.5737 37.8068 29.5827 26.0705 32.7274C14.3343 35.8721 3.89316 34.9617 2.74963 30.694C2.1505 28.458 4.22245 25.744 8.01894 23.2145" stroke="#4b5563" stroke-width="4" stroke-linecap="round" stroke-linejoin="round" />
        </svg>
      </template>
    </el-image>

    <!-- 如果网站没有LOGO -->
    <div v-else>
      <svg :class="sm?'w-ico-dir h-ico-dir':'w-ico h-ico'" viewBox="0 0 48 48" fill="none" xmlns="http://www.w3.org/2000/svg">
        <path d="M24 40C32.8366 40 40 32.8366 40 24C40 15.1634 32.8366 8 24 8C15.1634 8 8 15.1634 8 24C8 32.8366 15.1634 40 24 40Z" fill="none" stroke="#4b5563" stroke-width="4" stroke-linejoin="round" />
        <path d="M37.5641 15.5098C41.7833 15.878 44.6787 17.1724 45.2504 19.306C46.3939 23.5737 37.8068 29.5827 26.0705 32.7274C14.3343 35.8721 3.89316 34.9617 2.74963 30.694C2.1505 28.458 4.22245 25.744 8.01894 23.2145" stroke="#4b5563" stroke-width="4" stroke-linecap="round" stroke-linejoin="round" />
      </svg>
    </div>
  </div>
</template>

<script lang="ts" setup>
import type { Bookmark } from "@api/typing";

const props = defineProps<{
  sm?: boolean;
  bookmark: Bookmark;
}>();

onMounted(() => {
  dataInit();
});

function dataInit() {
  const item = props.bookmark;
  if (!item.iconActivity) return;
  props.bookmark.iconUrlFull = item.iconUrlFull
    ? item.iconUrlFull
    : getBaseAvatarUrl(item.urlFull);
}

function imageLoadFailed() {
  if (!props.bookmark.bookmarkId) return;
  props.bookmark.iconActivity = false;
}

function getBaseAvatarUrl(url: string): string {
  try {
    const parsedUrl = new URL(url);
    return `${parsedUrl.origin}/favicon.ico`;
  } catch (error) {}
  return "";
}
</script>