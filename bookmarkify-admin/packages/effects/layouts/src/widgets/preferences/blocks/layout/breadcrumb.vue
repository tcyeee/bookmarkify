<script setup lang="ts">
import type { SelectOption } from '@vben/types';

import { computed } from 'vue';


import SwitchItem from '../switch-item.vue';
import ToggleItem from '../toggle-item.vue';

defineOptions({
  name: 'PreferenceBreadcrumbConfig',
});

const props = defineProps<{ disabled?: boolean }>();

const breadcrumbEnable = defineModel<boolean>('breadcrumbEnable');
const breadcrumbShowIcon = defineModel<boolean>('breadcrumbShowIcon');
const breadcrumbStyleType = defineModel<string>('breadcrumbStyleType');
const breadcrumbShowHome = defineModel<boolean>('breadcrumbShowHome');
const breadcrumbHideOnlyOne = defineModel<boolean>('breadcrumbHideOnlyOne');

const typeItems: SelectOption[] = [
  { label: '常规', value: 'normal' },
  { label: '背景', value: 'background' },
];

const disableItem = computed(() => {
  return !breadcrumbEnable.value || props.disabled;
});
</script>

<template>
  <SwitchItem v-model="breadcrumbEnable" :disabled="disabled">
    {{ '开启面包屑导航' }}
  </SwitchItem>
  <SwitchItem v-model="breadcrumbHideOnlyOne" :disabled="disableItem">
    {{ '仅有一个时隐藏' }}
  </SwitchItem>
  <SwitchItem v-model="breadcrumbShowIcon" :disabled="disableItem">
    {{ '显示面包屑图标' }}
  </SwitchItem>
  <SwitchItem
    v-model="breadcrumbShowHome"
    :disabled="disableItem || !breadcrumbShowIcon"
  >
    {{ '显示首页按钮' }}
  </SwitchItem>
  <ToggleItem
    v-model="breadcrumbStyleType"
    :disabled="disableItem"
    :items="typeItems"
  >
    {{ '面包屑风格' }}
  </ToggleItem>
</template>
