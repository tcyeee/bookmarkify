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
  renderEcharts({
    series: [
      {
        animationDelay() {
          return Math.random() * 400;
        },
        animationEasing: 'exponentialInOut',
        animationType: 'scale',
        center: ['50%', '50%'],
        color: ['#5ab1ef', '#b6a2de', '#67e0e3', '#2ec7c9', '#ffb980', '#d87a80'],
        data: props.data
          .map((item) => ({ name: item.name, value: item.count }))
          .toSorted((a, b) => a.value - b.value),
        name: '浏览器分布',
        radius: '80%',
        roseType: 'radius',
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
