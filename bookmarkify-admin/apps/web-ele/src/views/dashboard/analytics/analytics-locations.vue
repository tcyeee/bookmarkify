<script lang="ts" setup>
import type { EchartsUIType } from '@vben/plugins/echarts';

import type { NamedCount } from '#/api/analytics';

import { onMounted, ref, watch } from 'vue';

import { EchartsUI, useEcharts } from '@vben/plugins/echarts';

const props = withDefaults(
  defineProps<{
    data?: NamedCount[];
  }>(),
  { data: () => [] },
);

const chartRef = ref<EchartsUIType>();
const { renderEcharts } = useEcharts(chartRef);

function render() {
  // 横向柱状图：count 升序排列，最大值在顶部
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
        itemStyle: { borderRadius: [0, 6, 6, 0], color: '#5ab1ef' },
        type: 'bar',
      },
    ],
    tooltip: { trigger: 'axis' },
    xAxis: { minInterval: 1, type: 'value' },
    yAxis: {
      axisLabel: {
        formatter: (value: string) =>
          value.length > 10 ? `${value.slice(0, 10)}…` : value,
      },
      data: sorted.map((item) => item.name),
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
