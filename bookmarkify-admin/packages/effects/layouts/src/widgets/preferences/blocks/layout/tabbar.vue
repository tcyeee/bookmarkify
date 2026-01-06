<script setup lang="ts">
import type { SelectOption } from "@vben/types";

import { computed } from "vue";

import NumberFieldItem from "../number-field-item.vue";
import SelectItem from "../select-item.vue";
import SwitchItem from "../switch-item.vue";

defineOptions({
  name: "PreferenceTabsConfig",
});

defineProps<{ disabled?: boolean }>();

const tabbarEnable = defineModel<boolean>("tabbarEnable");
const tabbarShowIcon = defineModel<boolean>("tabbarShowIcon");
const tabbarPersist = defineModel<boolean>("tabbarPersist");
const tabbarDraggable = defineModel<boolean>("tabbarDraggable");
const tabbarWheelable = defineModel<boolean>("tabbarWheelable");
const tabbarStyleType = defineModel<string>("tabbarStyleType");
const tabbarShowMore = defineModel<boolean>("tabbarShowMore");
const tabbarShowMaximize = defineModel<boolean>("tabbarShowMaximize");
const tabbarMaxCount = defineModel<number>("tabbarMaxCount");
const tabbarMiddleClickToClose = defineModel<boolean>(
  "tabbarMiddleClickToClose"
);

const styleItems = computed((): SelectOption[] => [
  {
    label: "谷歌",
    value: "chrome",
  },
  {
    label: "朴素",
    value: "plain",
  },
  {
    label: "卡片",
    value: "card",
  },

  {
    label: "轻快",
    value: "brisk",
  },
]);
</script>

<template>
  <SwitchItem v-model="tabbarEnable" :disabled="disabled">
    {{ '启用标签栏' }}
  </SwitchItem>
  <SwitchItem v-model="tabbarPersist" :disabled="!tabbarEnable">
    {{ '持久化标签页' }}
  </SwitchItem>
  <NumberFieldItem v-model="tabbarMaxCount" :disabled="!tabbarEnable" :max="30" :min="0" :step="5" :tip="'每次打开新的标签时如果超过最大标签数，会自动关闭一个最先打开的标签。设置为 0 则不限制'">
    {{ '最大标签数' }}
  </NumberFieldItem>
  <SwitchItem v-model="tabbarDraggable" :disabled="!tabbarEnable">
    {{ '启动拖拽排序' }}
  </SwitchItem>
  <SwitchItem v-model="tabbarWheelable" :disabled="!tabbarEnable" :tip="'开启后，标签栏区域可以响应滚轮的纵向滚动事件。关闭时，只能响应系统的横向滚动事件（需要按下Shift再滚动滚轮）'">
    {{ '启用纵向滚轮响应' }}
  </SwitchItem>
  <SwitchItem v-model="tabbarMiddleClickToClose" :disabled="!tabbarEnable">
    {{ '点击鼠标中键关闭标签页' }}
  </SwitchItem>
  <SwitchItem v-model="tabbarShowIcon" :disabled="!tabbarEnable">
    {{ '显示标签栏图标' }}
  </SwitchItem>
  <SwitchItem v-model="tabbarShowMore" :disabled="!tabbarEnable">
    {{ '显示更多按钮' }}
  </SwitchItem>
  <SwitchItem v-model="tabbarShowMaximize" :disabled="!tabbarEnable">
    {{ '显示最大化按钮' }}
  </SwitchItem>
  <SelectItem v-model="tabbarStyleType" :items="styleItems">
    {{ '标签页风格' }}
  </SelectItem>
</template>
