<script lang="ts" setup>
/**
 * 活性巡检轮次明细：一轮一行。
 *
 * 与「Scrapper 调用日志」「书签活性日志」的分工：那两张表分别是一次 HTTP 调用一行、
 * 一次探测一行，都回答不了「巡检系统本身怎么样」——有没有被熔断、积压追不追得上、
 * 有多少重新抓取因为解析队列拥堵被推迟。这三个数只有按轮聚合才看得出走势。
 */
import type { BookmarkSweepLogVO } from '#/api/bookmark-sweep-log';

import { defineAsyncComponent, onMounted, reactive } from 'vue';

import { useRoute } from 'vue-router';

import { Page } from '@vben/common-ui';
import { formatDateTime } from '@vben/utils';

import { useVbenVxeGrid, type VxeGridProps } from '#/adapter/vxe-table';
import { getAdminSweepLogListApi, SWEEP_TASK_LABELS } from '#/api/bookmark-sweep-log';

const ElCard = defineAsyncComponent(() =>
  Promise.all([
    import('element-plus/es/components/card/index'),
    import('element-plus/es/components/card/style/css'),
  ]).then(([res]) => res.ElCard),
);

const ElForm = defineAsyncComponent(() =>
  Promise.all([
    import('element-plus/es/components/form/index'),
    import('element-plus/es/components/form/style/css'),
  ]).then(([res]) => res.ElForm),
);

const ElFormItem = defineAsyncComponent(() =>
  Promise.all([
    import('element-plus/es/components/form/index'),
    import('element-plus/es/components/form/style/css'),
  ]).then(([res]) => res.ElFormItem),
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

const ElButton = defineAsyncComponent(() =>
  Promise.all([
    import('element-plus/es/components/button/index'),
    import('element-plus/es/components/button/style/css'),
  ]).then(([res]) => res.ElButton),
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

const searchForm = reactive({
  taskLabel: undefined as string | undefined,
  onlyBreaker: false,
});

// 告警条跳过来时带 onlyBreaker=1，直接落在熔断轮次上，省掉"再点一下筛选"
onMounted(() => {
  if (route.query.onlyBreaker === '1') {
    searchForm.onlyBreaker = true;
    gridApi.reload();
  }
});

function handleSearch() {
  gridApi.reload();
}

function handleReset() {
  searchForm.taskLabel = undefined;
  searchForm.onlyBreaker = false;
  gridApi.reload();
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
    { field: 'durationMs', title: '耗时(ms)', width: 110 },
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
      <div class="mb-4">
        <ElForm :inline="true" :model="searchForm">
          <ElFormItem label="巡检任务">
            <ElSelect
              v-model="searchForm.taskLabel"
              placeholder="全部"
              clearable
              style="width: 160px"
            >
              <ElOption
                v-for="(label, key) in SWEEP_TASK_LABELS"
                :key="key"
                :label="label"
                :value="key"
              />
            </ElSelect>
          </ElFormItem>
          <ElFormItem label="只看熔断">
            <ElSwitch v-model="searchForm.onlyBreaker" />
          </ElFormItem>
          <ElFormItem>
            <ElButton type="primary" @click="handleSearch">搜索</ElButton>
            <ElButton class="ml-2" @click="handleReset">重置</ElButton>
          </ElFormItem>
        </ElForm>
      </div>
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

        <!-- 积压 > 候选说明当前数据量下配置的检测间隔已经追不上，是个需要调参的信号 -->
        <template #candidates="{ row }">
          <ElTooltip
            v-if="row.backlog > row.candidates"
            :content="`到期候选共 ${row.backlog} 条，超过单轮上限 ${row.candidates}：检测间隔配置已追不上数据量`"
            placement="top"
          >
            <span class="text-orange-500">{{ row.candidates }} / {{ row.backlog }}</span>
          </ElTooltip>
          <span v-else>{{ row.candidates }}</span>
        </template>

        <!-- 站点层短路：域名已判死，其下页面不再逐个探测，直接复用上一轮的站点结论 -->
        <template #probed="{ row }">
          <ElTooltip
            v-if="row.shortCircuited > 0"
            :content="`另有 ${row.shortCircuited} 条因所属域名已判定死亡被站点层短路，未实际探测`"
            placement="top"
          >
            <span>{{ row.probed }}<span class="text-gray-400"> +{{ row.shortCircuited }}</span></span>
          </ElTooltip>
          <span v-else>{{ row.probed }}</span>
        </template>

        <template #dead="{ row }">
          <span :class="row.deadCount > 0 ? 'text-red-500' : ''">{{ row.deadCount }}</span>
        </template>

        <!-- 无结论 = 我方链路的问题，不会写进书签状态，但占比高就是熔断的前兆 -->
        <template #unknown="{ row }">
          <ElTooltip
            v-if="row.unknownCount > 0"
            content="「我方探不到」而非「站点失联」，不会改动书签状态；占比过半即触发熔断"
            placement="top"
          >
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
