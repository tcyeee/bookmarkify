<script lang="ts" setup>
import type { EchartsUIType } from '@vben/plugins/echarts';

import { onMounted, ref, watch } from 'vue';

import { EchartsUI, useEcharts } from '@vben/plugins/echarts';

const props = defineProps<{
  months: string[];
  pageviews: number[];
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
        barMaxWidth: 80,
        data: props.pageviews,
        type: 'bar',
      },
    ],
    tooltip: {
      axisPointer: {
        lineStyle: { width: 1 },
      },
      trigger: 'axis',
    },
    xAxis: {
      data: props.months,
      type: 'category',
    },
    yAxis: {
      minInterval: 1,
      splitNumber: 4,
      type: 'value',
    },
  });
}

onMounted(render);
watch(() => [props.months, props.pageviews], render);
</script>

<template>
  <EchartsUI ref="chartRef" />
</template>
