<script lang="ts" setup>
import type { EchartsUIType } from '@vben/plugins/echarts';

import type { PageCount } from '#/api/analytics';

import { onMounted, ref, watch } from 'vue';

import { EchartsUI, useEcharts } from '@vben/plugins/echarts';

const props = withDefaults(
  defineProps<{
    data?: PageCount[];
  }>(),
  { data: () => [] },
);

const chartRef = ref<EchartsUIType>();
const { renderEcharts } = useEcharts(chartRef);

function render() {
  // 后端已按访问量降序；横向柱状图需升序使最大值在顶部
  const sorted = [...props.data].sort((a, b) => a.count - b.count);
  renderEcharts({
    grid: {
      bottom: '3%',
      containLabel: true,
      left: '2%',
      right: '6%',
      top: '4%',
    },
    series: [
      {
        barWidth: '60%',
        data: sorted.map((item) => item.count),
        itemStyle: { borderRadius: [0, 6, 6, 0], color: '#67e0e3' },
        type: 'bar',
      },
    ],
    tooltip: {
      formatter: (params: any) => {
        const item = sorted[params.dataIndex];
        return `${item?.path ?? ''}<br/>${item?.title ?? ''}：<b>${params.value}</b>`;
      },
      trigger: 'axis',
    },
    xAxis: { minInterval: 1, type: 'value' },
    yAxis: {
      axisLabel: {
        formatter: (value: string) =>
          value.length > 14 ? `${value.slice(0, 14)}…` : value,
      },
      data: sorted.map((item) => item.path),
      type: 'category',
    },
  });
}

onMounted(render);
watch(() => props.data, render);
</script>

<template>
  <EchartsUI ref="chartRef" />
</template>
