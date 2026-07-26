<script setup lang="ts">
import type { SelectOption } from "@vben/types";

import { computed } from "vue";

import SelectItem from "../select-item.vue";
import SwitchItem from "../switch-item.vue";

defineOptions({
  name: "PreferenceInterfaceControl",
});

const widgetGlobalSearch = defineModel<boolean>("widgetGlobalSearch");
const widgetFullscreen = defineModel<boolean>("widgetFullscreen");
const widgetNotification = defineModel<boolean>("widgetNotification");
const widgetThemeToggle = defineModel<boolean>("widgetThemeToggle");
const widgetSidebarToggle = defineModel<boolean>("widgetSidebarToggle");
const appPreferencesButtonPosition = defineModel<string>(
  "appPreferencesButtonPosition"
);
const widgetRefresh = defineModel<boolean>("widgetRefresh");

const positionItems = computed((): SelectOption[] => [
  {
    label: "自动",
    value: "auto",
  },
  {
    label: "顶栏",
    value: "header",
  },
  {
    label: "固定",
    value: "fixed",
  },
]);
</script>

<template>
  <SwitchItem v-model="widgetGlobalSearch">
    {{ '启用全局搜索' }}
  </SwitchItem>
  <SwitchItem v-model="widgetThemeToggle">
    {{ '启用主题切换' }}
  </SwitchItem>
  <SwitchItem v-model="widgetFullscreen">
    {{ '启用全屏' }}
  </SwitchItem>
  <SwitchItem v-model="widgetNotification">
    {{ '启用通知' }}
  </SwitchItem>
  <SwitchItem v-model="widgetSidebarToggle">
    {{ '启用侧边栏切换' }}
  </SwitchItem>
  <SwitchItem v-model="widgetRefresh">
    {{ '启用刷新' }}
  </SwitchItem>
  <SelectItem v-model="appPreferencesButtonPosition" :items="positionItems">
    {{ '偏好设置位置' }}
  </SelectItem>
</template>
