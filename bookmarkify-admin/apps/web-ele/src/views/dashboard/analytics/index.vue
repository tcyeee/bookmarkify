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

import { getAnalyticsOverviewApi } from '#/api/analytics';

import AnalyticsTrends from './analytics-trends.vue';
import AnalyticsVisitsData from './analytics-visits-data.vue';
import AnalyticsVisitsSales from './analytics-visits-sales.vue';
import AnalyticsVisitsSource from './analytics-visits-source.vue';
import AnalyticsVisits from './analytics-visits.vue';

const cardIcons = [SvgCardIcon, SvgCakeIcon, SvgDownloadIcon, SvgBellIcon];

const overview = ref<AnalyticsOverview>();
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
  loading.value = true;
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
          :dates="overview?.trend.dates ?? []"
          :pageviews="overview?.trend.pageviews ?? []"
          :trend="overview?.trend.trend ?? []"
        />
      </template>
      <template #visits>
        <AnalyticsVisits
          :months="overview?.monthly.months ?? []"
          :pageviews="overview?.monthly.pageviews ?? []"
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
  </div>
</template>
