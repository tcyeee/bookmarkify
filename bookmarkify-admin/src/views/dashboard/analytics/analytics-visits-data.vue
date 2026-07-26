<script lang="ts" setup>
import type { EchartsUIType } from '@vben/plugins/echarts';

import type { NamedCount } from '#/api/analytics';

import { onMounted, ref, watch } from 'vue';

import { EchartsUI, useEcharts } from '@vben/plugins/echarts';

const props = defineProps<{
  data: NamedCount[];
}>();

const chartRef = ref<EchartsUIType>();
const { renderEcharts } = useEcharts(chartRef);

function render() {
  const max = Math.max(1, ...props.data.map((item) => item.count));
  renderEcharts({
    legend: {
      bottom: 0,
      data: ['访问量'],
    },
    radar: {
      indicator: props.data.map((item) => ({ max, name: item.name })),
      radius: '60%',
      splitNumber: 8,
    },
    series: [
      {
        areaStyle: {
          opacity: 1,
          shadowBlur: 0,
          shadowColor: 'rgba(0,0,0,.2)',
          shadowOffsetX: 0,
          shadowOffsetY: 10,
        },
        data: [
          {
            itemStyle: { color: '#5ab1ef' },
            name: '访问量',
            value: props.data.map((item) => item.count),
          },
        ],
        itemStyle: {
          borderRadius: 10,
          borderWidth: 2,
        },
        symbolSize: 4,
        type: 'radar',
      },
    ],
    tooltip: {},
  });
}

onMounted(render);
watch(() => props.data, render);
</script>

<template>
  <EchartsUI ref="chartRef" />
</template>
