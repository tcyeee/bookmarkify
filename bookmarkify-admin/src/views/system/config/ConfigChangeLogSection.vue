<script lang="ts" setup>
/**
 * 系统配置变更记录：谁在什么时候把哪一项改成了什么。
 *
 * 刻意不用其他管理页那套 vxe 表格：一次保存改动的往往只有一两项，而表格要为「旧值/新值」
 * 各留一列、每行还只能塞下一项，改了三项就得拆成三行或挤进一个单元格。这里按「一次变更一块」
 * 排版，改了哪几项就列哪几项，与设置页本身的读法一致。
 *
 * 每次翻页只追加，不替换：看变更史是往下捋的，翻到第二页把第一页顶掉会打断这件事。
 */
import type { ConfigChangeLogVO } from '#/api/config-change-log';

import { computed, onMounted, ref } from 'vue';

import { formatDateTime } from '@vben/utils';

import { LIVENESS_FIELD_LABELS } from '#/api/bookmark-liveness-config';
import { CONFIG_KEY_LABELS, getConfigChangeLogListApi } from '#/api/config-change-log';

const PAGE_SIZE = 20;

const rows = ref<ConfigChangeLogVO[]>([]);
const total = ref(0);
const page = ref(1);
const loading = ref(false);

const hasMore = computed(() => rows.value.length < total.value);

async function fetchPage(next = false) {
  loading.value = true;
  try {
    const res = await getConfigChangeLogListApi({
      currentPage: next ? page.value + 1 : 1,
      pageSize: PAGE_SIZE,
    });
    rows.value = next ? [...rows.value, ...res.records] : res.records;
    total.value = res.total;
    page.value = res.current;
  } finally {
    loading.value = false;
  }
}

function labelOf(key: string) {
  return CONFIG_KEY_LABELS[key] ?? key;
}

/**
 * 字段名 → 中文名。认不出的字段原样显示 camelCase：新增配置项时忘了补映射，
 * 至少还能看出改的是哪一项，比显示成空白强。
 */
function fieldLabelOf(field: string) {
  return LIVENESS_FIELD_LABELS[field]?.label ?? field;
}

function valueText(value: null | string, field: string) {
  if (value === null) return '—';
  const unit = LIVENESS_FIELD_LABELS[field]?.unit ?? '';
  return `${value}${unit}`;
}

onMounted(() => {
  fetchPage();
});
</script>

<template>
  <div v-loading="loading && rows.length === 0">
    <p v-if="!loading && rows.length === 0" class="text-muted-foreground py-8 text-center text-sm">
      还没有变更记录。任何一次配置保存都会在这里留痕。
    </p>

    <ol class="m-0 list-none p-0">
      <li
        v-for="row in rows"
        :key="row.id"
        class="border-border border-b border-solid border-x-0 border-t-0 py-4 last:border-b-0"
      >
        <div class="text-muted-foreground flex flex-wrap items-center gap-x-2 gap-y-1 text-xs">
          <span class="text-foreground font-medium">
            {{ row.operatorName ?? row.operatorId ?? '未知操作者' }}
          </span>
          <span>{{ formatDateTime(row.createTime) }}</span>
          <span class="bg-accent rounded px-1.5 py-0.5">{{ labelOf(row.configKey) }}</span>
          <span v-if="row.initial" class="text-primary">首次写入</span>
        </div>

        <ul class="mt-2 space-y-1 pl-0">
          <li
            v-for="change in row.changes"
            :key="change.field"
            class="flex list-none flex-wrap items-baseline gap-x-2 text-sm"
          >
            <span class="text-muted-foreground">{{ fieldLabelOf(change.field) }}</span>
            <span class="text-muted-foreground line-through">
              {{ valueText(change.oldValue, change.field) }}
            </span>
            <span class="text-muted-foreground">→</span>
            <span class="text-foreground font-medium">
              {{ valueText(change.newValue, change.field) }}
            </span>
          </li>
          <!-- 服务端算不出差异（原文解析失败、或确实没有字段变化）时，至少说明这一点 -->
          <li v-if="row.changes.length === 0" class="text-muted-foreground list-none text-sm">
            本次保存没有改变任何配置项
          </li>
        </ul>
      </li>
    </ol>

    <div v-if="hasMore" class="mt-4 text-center">
      <button
        class="text-muted-foreground hover:text-foreground cursor-pointer border-none bg-transparent text-xs"
        :disabled="loading"
        type="button"
        @click="fetchPage(true)"
      >
        {{ loading ? '加载中…' : `加载更多（共 ${total} 条）` }}
      </button>
    </div>
  </div>
</template>
