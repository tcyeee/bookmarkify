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
  renderEcharts({
    grid: {
      bottom: '3%',
      containLabel: true,
      left: '2%',
      right: '4%',
      top: '8%',
    },
    series: [
      {
        barWidth: '45%',
        data: props.data.map((item) => item.count),
        itemStyle: { borderRadius: [6, 6, 0, 0], color: '#ffb980' },
        type: 'bar',
      },
    ],
    tooltip: { trigger: 'axis' },
    xAxis: {
      axisLabel: { interval: 0, rotate: props.data.length > 4 ? 30 : 0 },
      data: props.data.map((item) => item.name),
      type: 'category',
    },
    yAxis: { minInterval: 1, type: 'value' },
  });
}

onMounted(render);
watch(() => props.data, render);
</script>

<template>
  <EchartsUI ref="chartRef" />
</template>
