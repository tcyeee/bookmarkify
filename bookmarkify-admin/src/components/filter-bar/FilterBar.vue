<script lang="ts" setup>
import { computed, ref, useSlots } from "vue";

import { ElButton, ElTag } from "#/adapter/element";

/**
 * 条件筛选栏的统一外壳，配合 {@link FilterItem} 使用。
 *
 * 每个列表页以前各写一份 `<ElForm :inline>` + 「搜索 / 重置」，间距、标签样式、控件宽度、
 * 按钮顺序八个页面八种样子。现在版式全在这里，页面只声明筛选项本身。
 *
 * **没有「搜索」按钮**：改条件即搜，见 {@link useAutoSearch}。「重置」留着 —— 它做的是
 * 「一次清掉全部条件」，那是自动搜索无法表达的另一个意图。
 *
 * 筛选项多的页面把常用的几项放默认插槽、其余塞进 `advanced` 插槽：九项一字排开在窄屏会折成
 * 两三行，而这些页面多是 `auto-content-height` 的，筛选栏每多一行下面的表格就少一行。
 * 折叠起来的条件必须留痕迹，否则用户会对着一张被过滤过的表找不到原因 —— 那是 `advancedCount`
 * 角标的作用。
 */
defineProps<{
  /** 折叠区里正在生效的筛选项数量，挂在「更多筛选」上当角标 */
  advancedCount?: number;
}>();

const emit = defineEmits<{ (e: "reset"): void }>();

const slots = useSlots();
const expanded = ref(false);
const hasAdvanced = computed(() => Boolean(slots.advanced));
</script>

<template>
  <div class="filter-bar">
    <div class="filter-bar__row">
      <slot />
      <div class="filter-bar__actions">
        <slot name="actions" />
        <ElButton @click="emit('reset')">重置</ElButton>
        <ElButton v-if="hasAdvanced" link type="primary" @click="expanded = !expanded">
          {{ expanded ? "收起" : "更多筛选" }}
          <ElTag
            v-if="!expanded && advancedCount"
            size="small"
            type="primary"
            disable-transitions
            class="ml-1"
          >
            {{ advancedCount }}
          </ElTag>
        </ElButton>
      </div>
    </div>

    <div v-if="hasAdvanced" v-show="expanded" class="filter-bar__row filter-bar__row--advanced">
      <slot name="advanced" />
    </div>
  </div>
</template>

<style scoped>
.filter-bar__row {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  column-gap: 24px;
  row-gap: 12px;
}

.filter-bar__row--advanced {
  margin-top: 12px;
}

.filter-bar__actions {
  display: flex;
  flex: none;
  align-items: center;
  gap: 8px;
  margin-left: auto;
}
</style>
