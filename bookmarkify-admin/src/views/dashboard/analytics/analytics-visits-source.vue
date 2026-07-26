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
    legend: {
      bottom: '2%',
      left: 'center',
    },
    series: [
      {
        animationDelay() {
          return Math.random() * 100;
        },
        animationEasing: 'exponentialInOut',
        animationType: 'scale',
        avoidLabelOverlap: false,
        color: ['#5ab1ef', '#b6a2de', '#67e0e3', '#2ec7c9', '#ffb980', '#d87a80'],
        data: props.data.map((item) => ({ name: item.name, value: item.count })),
        emphasis: {
          label: {
            fontSize: '12',
            fontWeight: 'bold',
            show: true,
          },
        },
        itemStyle: {
          borderRadius: 10,
          borderWidth: 2,
        },
        label: {
          position: 'center',
          show: false,
        },
        labelLine: {
          show: false,
        },
        name: '访问来源',
        radius: ['40%', '65%'],
        type: 'pie',
      },
    ],
    tooltip: {
      trigger: 'item',
    },
  });
}

onMounted(render);
watch(() => props.data, render);
</script>

<template>
  <EchartsUI ref="chartRef" />
</template>
