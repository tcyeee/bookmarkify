<script lang="ts" setup>
import type { BookmarkParseStatus } from "#/api/bookmark";

import { computed } from "vue";

/**
 * 站点下页面抓取状态的分段条。
 *
 * 存在的意义是「不下钻就知道要不要下钻」：站点行只给一个 pageCount 时，
 * 「这个站有 1284 个页面」完全回答不了「这个站烂不烂」，而后台列表要的从来是后者。
 */
const props = defineProps<{
  counts?: Partial<Record<BookmarkParseStatus, number>>;
  /** 点击某段即按该状态过滤右侧页面表 */
  interactive?: boolean;
}>();

const emit = defineEmits<{
  (e: "pick", status: BookmarkParseStatus): void;
}>();

/** 段序固定为「越靠右越糟」，扫一眼右侧的颜色宽度就是问题的量，不必逐个读数字 */
const SEGMENTS: {
  status: BookmarkParseStatus;
  label: string;
  cssVar: string;
}[] = [
  { status: "SUCCESS", label: "成功", cssVar: "--el-color-success" },
  { status: "PENDING", label: "等待中", cssVar: "--el-color-info" },
  { status: "UNREACHABLE", label: "抓取失败", cssVar: "--el-color-danger" },
  { status: "ARCHIVED", label: "已归档", cssVar: "--el-color-warning" },
];

const parts = computed(() =>
  SEGMENTS.map((seg) => ({ ...seg, count: props.counts?.[seg.status] ?? 0 })).filter(
    (p) => p.count > 0,
  ),
);

const total = computed(() => parts.value.reduce((sum, p) => sum + p.count, 0));

function handlePick(status: BookmarkParseStatus) {
  if (props.interactive) emit("pick", status);
}
</script>

<template>
  <div v-if="total > 0" class="health-bar">
    <span
      v-for="p in parts"
      :key="p.status"
      class="health-seg"
      :class="{ 'health-seg--clickable': interactive }"
      :style="{ flexGrow: p.count, background: `var(${p.cssVar})` }"
      :title="`${p.label} ${p.count} / ${total}`"
      @click.stop="handlePick(p.status)"
    />
  </div>
  <div v-else class="health-bar health-bar--empty" title="该站点下还没有收录任何页面" />
</template>

<style scoped>
.health-bar {
  display: flex;
  gap: 1px;
  width: 100%;
  height: 4px;
  overflow: hidden;
  border-radius: 2px;
}

.health-bar--empty {
  background: var(--el-fill-color);
}

/*
 * min-width 不能省：按 flex-grow 严格配比时，2000 个页面里的 1 个失败段只有 0.05% 宽，
 * 渲染出来就是不存在。而「这个站有没有坏页面」恰恰是这条 bar 唯一要回答的问题。
 */
.health-seg {
  min-width: 3px;
  height: 100%;
}

.health-seg--clickable {
  cursor: pointer;
}

.health-seg--clickable:hover {
  opacity: 0.75;
}
</style>
