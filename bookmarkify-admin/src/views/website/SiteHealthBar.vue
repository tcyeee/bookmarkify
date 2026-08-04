<script lang="ts" setup>
import type { BookmarkParseStatus } from "#/api/bookmark";

import { computed } from "vue";

import { PAGE_STATUS_META, PAGE_STATUS_ORDER } from "./pageStatus";

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

const parts = computed(() =>
  PAGE_STATUS_ORDER.map((status) => ({
    status,
    label: PAGE_STATUS_META[status].label,
    cssVar: PAGE_STATUS_META[status].cssVar,
    count: props.counts?.[status] ?? 0,
  })).filter((p) => p.count > 0),
);

const total = computed(() => parts.value.reduce((sum, p) => sum + p.count, 0));

function handlePick(status: BookmarkParseStatus) {
  if (props.interactive) emit("pick", status);
}
</script>

<template>
  <div
    v-if="total > 0"
    class="health-bar"
    :class="{ 'health-bar--interactive': interactive }"
    :aria-label="`页面健康：${parts.map((p) => `${p.label} ${p.count}`).join('，')}`"
  >
    <span
      v-for="p in parts"
      :key="p.status"
      class="health-seg"
      :class="{ 'health-seg--clickable': interactive }"
      :style="{ flexGrow: p.count, background: `var(${p.cssVar})` }"
      :title="interactive ? `${p.label} ${p.count} / ${total}（点击只看这些页面）` : `${p.label} ${p.count} / ${total}`"
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
  height: 6px;
  overflow: hidden;
  border-radius: 3px;
  transition: height 0.12s ease, box-shadow 0.12s ease;
}

/*
 * 「点某一段直接下钻到该状态的页面」是这条 bar 最有用的交互，但挂在 6px 高的元素上
 * 几乎没有可发现性 —— 光靠 cursor:pointer 谁都注意不到。悬浮时整条长高一倍并加描边，
 * 让它明确表现得像个控件。真正的键盘可达路径不在这里，见调用方的状态 chip。
 */
.health-bar--interactive:hover {
  height: 12px;
  box-shadow: 0 0 0 1px var(--el-border-color);
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
  filter: brightness(1.15);
}
</style>
