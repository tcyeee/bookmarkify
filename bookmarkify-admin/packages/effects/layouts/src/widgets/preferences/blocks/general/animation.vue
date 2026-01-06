<script setup lang="ts">

import SwitchItem from '../switch-item.vue';

defineOptions({
  name: 'PreferenceAnimation',
});

const transitionProgress = defineModel<boolean>('transitionProgress', {
  // 默认值
  default: false,
});
const transitionName = defineModel<string>('transitionName');
const transitionEnable = defineModel<boolean>('transitionEnable');
const transitionLoading = defineModel<boolean>('transitionLoading');

const transitionPreset = ['fade', 'fade-slide', 'fade-up', 'fade-down'];

function handleClick(value: string) {
  transitionName.value = value;
}
</script>

<template>
  <SwitchItem v-model="transitionProgress">
    {{ '页面切换进度条' }}
  </SwitchItem>
  <SwitchItem v-model="transitionLoading">
    {{ '页面切换 Loading' }}
  </SwitchItem>
  <SwitchItem v-model="transitionEnable">
    {{ '页面切换动画' }}
  </SwitchItem>
  <div
    v-if="transitionEnable"
    class="mb-2 mt-3 flex justify-between gap-3 px-2"
  >
    <div
      v-for="item in transitionPreset"
      :key="item"
      :class="{
        'outline-box-active': transitionName === item,
      }"
      class="outline-box p-2"
      @click="handleClick(item)"
    >
      <div :class="`${item}-slow`" class="bg-accent h-10 w-12 rounded-md"></div>
    </div>
  </div>
</template>
