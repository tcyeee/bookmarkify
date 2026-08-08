<script lang="ts" setup>
/**
 * 活性巡检轮次明细：一轮一行。
 *
 * 与「Scrapper 调用日志」「书签活性日志」的分工：那两张表分别是一次 HTTP 调用一行、
 * 一次探测一行，都回答不了「巡检系统本身怎么样」——有没有被熔断、积压追不追得上、
 * 有多少重新抓取因为解析队列拥堵被推迟。这三个数只有按轮聚合才看得出走势。
 */
import type { BookmarkSweepLogVO } from '#/api/bookmark-sweep-log';

import { defineAsyncComponent, reactive } from 'vue';

import { useRoute } from 'vue-router';

import { Page } from '@vben/common-ui';
import { formatDateTime } from '@vben/utils';

import { useVbenVxeGrid, type VxeGridProps } from '#/adapter/vxe-table';
import {
  getAdminSweepLogListApi,
  SWEEP_TASK_LABELS,
  SWEEP_TASK_RETIRED,
} from '#/api/bookmark-sweep-log';
import { FilterBar, FilterItem, useAutoSearch } from '#/components/filter-bar';

const ElCard = defineAsyncComponent(() =>
  Promise.all([
    import('element-plus/es/components/card/index'),
    import('element-plus/es/components/card/style/css'),
  ]).then(([res]) => res.ElCard),
);

const ElSelect = defineAsyncComponent(() =>
  Promise.all([
    import('element-plus/es/components/select/index'),
    import('element-plus/es/components/select/style/css'),
  ]).then(([res]) => res.ElSelect),
);

const ElOption = defineAsyncComponent(() =>
  Promise.all([
    import('element-plus/es/components/select/index'),
    import('element-plus/es/components/select/style/css'),
  ]).then(([res]) => res.ElOption),
);

const ElSwitch = defineAsyncComponent(() =>
  Promise.all([
    import('element-plus/es/components/switch/index'),
    import('element-plus/es/components/switch/style/css'),
  ]).then(([res]) => res.ElSwitch),
);

const ElTag = defineAsyncComponent(() =>
  Promise.all([
    import('element-plus/es/components/tag/index'),
    import('element-plus/es/components/tag/style/css'),
  ]).then(([res]) => res.ElTag),
);

const ElTooltip = defineAsyncComponent(() =>
  Promise.all([
    import('element-plus/es/components/tooltip/index'),
    import('element-plus/es/components/tooltip/style/css'),
  ]).then(([res]) => res.ElTooltip),
);

const route = useRoute();

// 告警条跳过来时带 onlyBreaker=1，直接落在熔断轮次上，省掉"再点一下筛选"。
// 在 setup 里就写进初值，而不是挂载后再改：后者会让表格先按"无筛选"查一次、
// 再被自动搜索翻一次，中间那一版无关的数据还会闪一下
const searchForm = reactive({
  taskLabel: undefined as string | undefined,
  onlyBreaker: route.query.onlyBreaker === '1',
});

// 「重置」要还原成"什么都不筛"，而不是 URL 带进来的那个筛选态（见 useAutoSearch 的 initial）
const DEFAULT_FILTERS: typeof searchForm = {
  taskLabel: undefined,
  onlyBreaker: false,
};

/**
 * 耗时：一轮最坏是「单轮上限 × 单条 15s 超时」，毫秒原样显示会出现读不动的七位数。
 */
function formatDuration(ms: number) {
  if (ms < 1000) return `${ms} ms`;
  if (ms < 60_000) return `${(ms / 1000).toFixed(1)} s`;
  return `${Math.floor(ms / 60_000)}m ${Math.round((ms % 60_000) / 1000)}s`;
}

/**
 * 候选列。
 *
 * `backlog` 与 `candidates` 的差有两个来源，含义相反：**被单轮上限截断**（要调参的信号）和
 * **非域名书签被过滤掉**（本地地址/IP，压根不该探测，完全正常）。所以告警只看 `backlog > batchSize`
 * —— 早先按 `backlog > candidates` 判，一条本地地址书签就能让橙色告警常亮。
 */
function candidateCell(row: BookmarkSweepLogVO) {
  const text =
    row.backlog === row.candidates
      ? `${row.candidates}`
      : `${row.candidates} / ${row.backlog}`;

  if (row.batchSize != null && row.backlog > row.batchSize) {
    return {
      text,
      tone: 'text-orange-500',
      tip: `到期候选共 ${row.backlog} 条，超过单轮上限 ${row.batchSize}：当前数据量下检测间隔配置已追不上，需要调大上限或拉长间隔`,
    };
  }
  if (row.backlog <= row.candidates) return { text, tone: '', tip: '' };
  return {
    text,
    tone: 'text-gray-400',
    tip:
      row.batchSize == null
        ? `到期 ${row.backlog} 条；该轮早于 2026-08-08，未记录单轮上限，无法区分这 ${row.backlog - row.candidates} 条是被上限截断还是被非域名过滤扣掉`
        : `到期 ${row.backlog} 条，其中 ${row.backlog - row.candidates} 条是本地地址/IP 等非域名书签，不做探测（单轮上限 ${row.batchSize}，未触顶）`,
  };
}

/** 短路结果里判死 / 无结论各有多少；历史行没记这个数，返回 null 表示"拆不出来" */
function shortCircuitSplit(row: BookmarkSweepLogVO) {
  if (row.shortCircuitedDead == null) return null;
  return { dead: row.shortCircuitedDead, unknown: row.shortCircuited - row.shortCircuitedDead };
}

/**
 * 失联列的说明。
 *
 * 这一列是「本轮探测 + 站点层短路复用」的合计，而熔断判据的分母只有真正探测过的那部分 ——
 * 一个域名判死会连带它名下所有页面进这个数，不说明白就会被读成"本轮死了 180 个站点"。
 */
function deadTip(row: BookmarkSweepLogVO) {
  const split = shortCircuitSplit(row);
  const reused =
    split && split.dead > 0
      ? `其中 ${split.dead} 条是站点层短路复用的上一轮站点结论、本轮并未探测，真正探测判死 ${row.deadCount - split.dead} 条；`
      : '';
  return `${reused}探测判死占实际探测 ≥90%（且实际探测 ≥20 条）时触发熔断，判定为我方出网链路故障而非站点集体下线`;
}

/** 无结论列的说明。阈值与样本量见 LivenessPolicy.breakerReason */
function unknownTip(row: BookmarkSweepLogVO) {
  const split = shortCircuitSplit(row);
  const reused =
    split && split.unknown > 0
      ? `本列含 ${split.unknown} 条站点层短路复用的结论，它们不计入熔断判据；`
      : '';
  return `「我方探不到」而非「站点失联」，不会改动书签状态。${reused}占实际探测 ≥50%（且实际探测 ≥10 条）即触发熔断`;
}

const gridOptions: VxeGridProps<BookmarkSweepLogVO> = {
  id: 'admin-bookmark-sweep-log',
  columns: [
    { type: 'seq', title: '#', width: 50 },
    {
      field: 'createTime',
      title: '轮次时间',
      width: 180,
      formatter: ({ cellValue }) => formatDateTime(cellValue),
    },
    { field: 'taskLabel', title: '巡检任务', width: 120, slots: { default: 'taskLabel' } },
    { field: 'breakerReason', title: '结果', minWidth: 260, slots: { default: 'result' } },
    { field: 'candidates', title: '候选', width: 90, slots: { default: 'candidates' } },
    { field: 'probed', title: '实际探测', width: 100, slots: { default: 'probed' } },
    { field: 'aliveCount', title: '存活', width: 80 },
    { field: 'deadCount', title: '失联', width: 80, slots: { default: 'dead' } },
    { field: 'unknownCount', title: '无结论', width: 90, slots: { default: 'unknown' } },
    { field: 'triggeredParse', title: '触发重抓', width: 100 },
    { field: 'deferredParse', title: '推迟', width: 90, slots: { default: 'deferred' } },
    {
      field: 'durationMs',
      title: '耗时',
      width: 100,
      formatter: ({ cellValue }) => formatDuration(cellValue),
    },
  ],
  toolbarConfig: { custom: true, refresh: true },
  pagerConfig: { pageSize: 50 },
  proxyConfig: {
    ajax: {
      query: async ({ page }) => {
        const res = await getAdminSweepLogListApi({
          taskLabel: searchForm.taskLabel,
          onlyBreaker: searchForm.onlyBreaker || undefined,
          currentPage: page.currentPage,
          pageSize: page.pageSize,
        });
        return { items: res.records, total: res.total };
      },
    },
  },
};

const [Grid, gridApi] = useVbenVxeGrid({ gridOptions });

const { reset } = useAutoSearch(searchForm, () => gridApi.reload(), {
  initial: DEFAULT_FILTERS,
});
</script>

<template>
  <Page auto-content-height>
    <ElCard shadow="never">
      <template #header>
        <div class="flex items-center justify-between">
          <span>活性巡检轮次</span>
          <span class="text-xs text-gray-400">
            一轮一行。熔断 = 本轮整体结论被判定为不可信，没有改动任何书签
          </span>
        </div>
      </template>
      <FilterBar class="mb-4" @reset="reset">
        <FilterItem label="巡检任务">
          <ElSelect v-model="searchForm.taskLabel" placeholder="全部" clearable>
            <ElOption
              v-for="(label, key) in SWEEP_TASK_LABELS"
              :key="key"
              :label="SWEEP_TASK_RETIRED.has(key) ? `${label}（已下线）` : label"
              :value="key"
            />
          </ElSelect>
        </FilterItem>
        <FilterItem label="只看熔断" width="auto">
          <ElSwitch v-model="searchForm.onlyBreaker" />
        </FilterItem>
      </FilterBar>
      <Grid>
        <template #taskLabel="{ row }">
          {{ SWEEP_TASK_LABELS[row.taskLabel] ?? row.taskLabel }}
        </template>

        <template #result="{ row }">
          <ElTooltip v-if="row.breakerReason" :content="row.breakerReason" placement="top">
            <span class="inline-flex items-center gap-1.5">
              <ElTag type="danger" size="small">熔断</ElTag>
              <span class="line-clamp-1 text-xs">{{ row.breakerReason }}</span>
            </span>
          </ElTooltip>
          <ElTag v-else type="success" size="small">正常</ElTag>
        </template>

        <!-- 橙色只在 backlog 超过单轮上限时出现，判据见 candidateCell -->
        <template #candidates="{ row }">
          <ElTooltip
            v-if="candidateCell(row).tip"
            :content="candidateCell(row).tip"
            placement="top"
          >
            <span :class="candidateCell(row).tone">{{ candidateCell(row).text }}</span>
          </ElTooltip>
          <span v-else>{{ candidateCell(row).text }}</span>
        </template>

        <!-- 站点层短路：域名已判死，其下页面不再逐个探测，直接复用上一轮的站点结论 -->
        <template #probed="{ row }">
          <ElTooltip
            v-if="row.shortCircuited > 0"
            :content="`另有 ${row.shortCircuited} 条因所属域名已判定死亡被站点层短路，未实际探测；右侧失联/无结论两列含这部分复用结论，而熔断只看实际探测的 ${row.probed} 条`"
            placement="top"
          >
            <span>{{ row.probed }}<span class="text-gray-400"> +{{ row.shortCircuited }}</span></span>
          </ElTooltip>
          <span v-else>{{ row.probed }}</span>
        </template>

        <!-- 含站点层短路复用的结论，所以要说明其中真正探测出来的有多少 -->
        <template #dead="{ row }">
          <ElTooltip v-if="row.deadCount > 0" :content="deadTip(row)" placement="top">
            <span class="text-red-500">{{ row.deadCount }}</span>
          </ElTooltip>
          <span v-else>0</span>
        </template>

        <!-- 无结论 = 我方链路的问题，不会写进书签状态，但占比高就是熔断的前兆 -->
        <template #unknown="{ row }">
          <ElTooltip v-if="row.unknownCount > 0" :content="unknownTip(row)" placement="top">
            <span class="text-orange-500">{{ row.unknownCount }}</span>
          </ElTooltip>
          <span v-else>0</span>
        </template>

        <template #deferred="{ row }">
          <ElTooltip
            v-if="row.deferredParse > 0"
            content="解析队列余量不足，这些重新抓取被推迟到下一轮，避免挤占用户添加书签的队列"
            placement="top"
          >
            <span class="text-orange-500">{{ row.deferredParse }}</span>
          </ElTooltip>
          <span v-else>0</span>
        </template>
      </Grid>
    </ElCard>
  </Page>
</template>
