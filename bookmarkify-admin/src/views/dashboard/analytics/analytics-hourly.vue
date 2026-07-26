<script lang="ts" setup>
import type { EchartsUIType } from '@vben/plugins/echarts';

import { onMounted, ref, watch } from 'vue';

import { EchartsUI, useEcharts } from '@vben/plugins/echarts';

const props = withDefaults(
  defineProps<{
    hourly?: number[];
  }>(),
  { hourly: () => [] },
);

const chartRef = ref<EchartsUIType>();
const { renderEcharts } = useEcharts(chartRef);

function render() {
  renderEcharts({
    grid: {
      bottom: '3%',
      containLabel: true,
      left: '2%',
      right: '3%',
      top: '8%',
    },
    series: [
      {
        barWidth: '60%',
        data: props.hourly,
        itemStyle: { borderRadius: [4, 4, 0, 0], color: '#b6a2de' },
        type: 'bar',
      },
    ],
    tooltip: {
      formatter: (params: any) => `${params.name}:00 — ${params.value} 次访问`,
      trigger: 'axis',
    },
    xAxis: {
      axisLabel: { interval: 1 },
      data: Array.from({ length: 24 }, (_, h) => `${h}`),
      type: 'category',
    },
    yAxis: { minInterval: 1, type: 'value' },
  });
}

onMounted(render);
watch(() => props.hourly, render);
</script>

<template>
  <EchartsUI ref="chartRef" />
</template>
