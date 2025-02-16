<template>
  <div>
    <el-tag type="primary">{{ value.name }}</el-tag>
  </div>
</template>

<script lang="ts" setup>
import type { BookmarkTag } from "~/server/apis/bookmark/typing";

defineProps<{
  value: BookmarkTag;
}>();
</script>

<style>
</style>