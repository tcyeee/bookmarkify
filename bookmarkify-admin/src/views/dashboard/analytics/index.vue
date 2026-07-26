<script lang="ts" setup>
import type { AnalysisOverviewItem } from '@vben/common-ui';
import type { TabOption } from '@vben/types';

import type { AnalyticsOverview } from '#/api/analytics';

import { computed, onMounted, ref } from 'vue';

import {
  AnalysisChartCard,
  AnalysisChartsTabs,
  AnalysisOverview,
} from '@vben/common-ui';
import {
  SvgBellIcon,
  SvgCakeIcon,
  SvgCardIcon,
  SvgDownloadIcon,
} from '@vben/icons';
import { StorageSerializers, useLocalStorage } from '@vueuse/core';

import { getAnalyticsOverviewApi } from '#/api/analytics';

import AnalyticsEvents from './analytics-events.vue';
import AnalyticsHourly from './analytics-hourly.vue';
import AnalyticsLocations from './analytics-locations.vue';
import AnalyticsSizes from './analytics-sizes.vue';
import AnalyticsTopPages from './analytics-top-pages.vue';
import AnalyticsTrends from './analytics-trends.vue';
import AnalyticsVisitsData from './analytics-visits-data.vue';
import AnalyticsVisitsSales from './analytics-visits-sales.vue';
import AnalyticsVisitsSource from './analytics-visits-source.vue';
import AnalyticsVisits from './analytics-visits.vue';

const cardIcons = [SvgCardIcon, SvgCakeIcon, SvgDownloadIcon, SvgBellIcon];

// 持久化缓存：将上一次的看板数据写入 localStorage，使得
// 1. 页面切换 / 组件重新挂载之间保留数据；
// 2. 浏览器刷新后仍能立即展示旧数据，等后台拉到新数据再静默替换，
//    避免每次进入分析页都从空白重新加载导致的长时间等待与闪烁。
//
// 必须显式指定 object 序列化器：默认值为 null 时 useLocalStorage 无法推断类型，
// 会回退到 any 序列化器（write=String(v)），把对象写成 "[object Object]"，
// 导致缓存恒为脏字符串、图表拿到畸形数据而崩溃。key 升 v2 以绕开历史脏值。
const overview = useLocalStorage<AnalyticsOverview | null>(
  'analytics-overview-cache:v2',
  null,
  { serializer: StorageSerializers.object },
);
const loading = ref(false);

const overviewItems = computed<AnalysisOverviewItem[]>(() =>
  (overview.value?.cards ?? []).map((card, index) => ({
    icon: cardIcons[index % cardIcons.length]!,
    title: card.title,
    totalTitle: `总${card.title}`,
    totalValue: card.total,
    value: card.recent,
  })),
);

const chartTabs: TabOption[] = [
  {
    label: '流量趋势',
    value: 'trends',
  },
  {
    label: '月访问量',
    value: 'visits',
  },
];

onMounted(async () => {
  // 仅当没有缓存时才展示 loading 遮罩；有缓存时静默后台刷新，避免闪烁。
  loading.value = !overview.value;
  try {
    overview.value = await getAnalyticsOverviewApi(30);
  } finally {
    loading.value = false;
  }
});
</script>

<template>
  <div v-loading="loading" class="p-5">
    <AnalysisOverview :items="overviewItems" />
    <AnalysisChartsTabs :tabs="chartTabs" class="mt-5">
      <template #trends>
        <AnalyticsTrends
          :dates="overview?.trend?.dates ?? []"
          :pageviews="overview?.trend?.pageviews ?? []"
          :trend="overview?.trend?.trend ?? []"
        />
      </template>
      <template #visits>
        <AnalyticsVisits
          :months="overview?.monthly?.months ?? []"
          :pageviews="overview?.monthly?.pageviews ?? []"
        />
      </template>
    </AnalysisChartsTabs>

    <div class="mt-5 w-full md:flex">
      <AnalysisChartCard class="mt-5 md:mr-4 md:mt-0 md:w-1/3" title="系统分布">
        <AnalyticsVisitsData :data="overview?.systems ?? []" />
      </AnalysisChartCard>
      <AnalysisChartCard class="mt-5 md:mr-4 md:mt-0 md:w-1/3" title="访问来源">
        <AnalyticsVisitsSource :data="overview?.referrers ?? []" />
      </AnalysisChartCard>
      <AnalysisChartCard class="mt-5 md:mt-0 md:w-1/3" title="浏览器分布">
        <AnalyticsVisitsSales :data="overview?.browsers ?? []" />
      </AnalysisChartCard>
    </div>

    <div class="mt-5 w-full md:flex">
      <AnalysisChartCard class="mt-5 md:mr-4 md:mt-0 md:w-1/3" title="地理分布">
        <AnalyticsLocations :data="overview?.locations ?? []" />
      </AnalysisChartCard>
      <AnalysisChartCard class="mt-5 md:mr-4 md:mt-0 md:w-1/3" title="设备类型">
        <AnalyticsSizes :data="overview?.sizes ?? []" />
      </AnalysisChartCard>
      <AnalysisChartCard class="mt-5 md:mt-0 md:w-1/3" title="事件明细">
        <AnalyticsEvents :data="overview?.events ?? []" />
      </AnalysisChartCard>
    </div>

    <div class="mt-5 w-full md:flex">
      <AnalysisChartCard class="mt-5 md:mr-4 md:mt-0 md:w-1/2" title="热门页面">
        <AnalyticsTopPages :data="overview?.topPages ?? []" />
      </AnalysisChartCard>
      <AnalysisChartCard class="mt-5 md:mt-0 md:w-1/2" title="24 小时活跃分布">
        <AnalyticsHourly :hourly="overview?.hourly ?? []" />
      </AnalysisChartCard>
    </div>
  </div>
</template>
