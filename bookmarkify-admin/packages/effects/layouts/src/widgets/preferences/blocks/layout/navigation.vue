<script setup lang="ts">
import type { SelectOption } from '@vben/types';


import SwitchItem from '../switch-item.vue';
import ToggleItem from '../toggle-item.vue';

defineOptions({
  name: 'PreferenceNavigationConfig',
});

defineProps<{ disabled?: boolean; disabledNavigationSplit?: boolean }>();

const navigationStyleType = defineModel<string>('navigationStyleType');
const navigationSplit = defineModel<boolean>('navigationSplit');
const navigationAccordion = defineModel<boolean>('navigationAccordion');

const stylesItems: SelectOption[] = [
  { label: '圆润', value: 'rounded' },
  { label: '朴素', value: 'plain' },
];
</script>

<template>
  <ToggleItem
    v-model="navigationStyleType"
    :disabled="disabled"
    :items="stylesItems"
  >
    {{ '导航菜单风格' }}
  </ToggleItem>
  <SwitchItem
    v-model="navigationSplit"
    :disabled="disabledNavigationSplit || disabled"
  >
    {{ '导航菜单分离' }}
    <template #tip>
      {{ '开启时，侧边栏显示顶栏对应菜单的子菜单' }}
    </template>
  </SwitchItem>
  <SwitchItem v-model="navigationAccordion" :disabled="disabled">
    {{ '侧边导航菜单手风琴模式' }}
  </SwitchItem>
</template>
