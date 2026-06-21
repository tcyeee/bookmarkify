<script lang="ts" setup>
import type { EchartsUIType } from '@vben/plugins/echarts';

import { onMounted, ref, watch } from 'vue';

import { EchartsUI, useEcharts } from '@vben/plugins/echarts';

const props = defineProps<{
  dates: string[];
  pageviews: number[];
  trend: number[];
}>();

const chartRef = ref<EchartsUIType>();
const { renderEcharts } = useEcharts(chartRef);

function render() {
  renderEcharts({
    grid: {
      bottom: 0,
      containLabel: true,
      left: '1%',
      right: '1%',
      top: '2 %',
    },
    series: [
      {
        areaStyle: {},
        data: props.pageviews,
        itemStyle: { color: '#5ab1ef' },
        name: '访问量',
        smooth: true,
        type: 'line',
      },
      {
        areaStyle: {},
        data: props.trend,
        itemStyle: { color: '#019680' },
        name: '趋势',
        smooth: true,
        type: 'line',
      },
    ],
    tooltip: {
      axisPointer: {
        lineStyle: { color: '#019680', width: 1 },
      },
      trigger: 'axis',
    },
    xAxis: {
      axisTick: { show: false },
      boundaryGap: false,
      data: props.dates,
      splitLine: {
        lineStyle: { type: 'solid', width: 1 },
        show: true,
      },
      type: 'category',
    },
    yAxis: [
      {
        axisTick: { show: false },
        minInterval: 1,
        splitArea: { show: true },
        splitNumber: 4,
        type: 'value',
      },
    ],
  });
}

onMounted(render);
watch(() => [props.dates, props.pageviews, props.trend], render);
</script>

<template>
  <EchartsUI ref="chartRef" />
</template>
