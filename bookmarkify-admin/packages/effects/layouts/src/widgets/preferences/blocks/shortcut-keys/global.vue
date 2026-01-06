<script setup lang="ts">
import { computed } from 'vue';

import { isWindowsOs } from '@vben/utils';

import SwitchItem from '../switch-item.vue';

defineOptions({
  name: 'PreferenceGeneralConfig',
});

const shortcutKeysEnable = defineModel<boolean>('shortcutKeysEnable');
const shortcutKeysGlobalSearch = defineModel<boolean>(
  'shortcutKeysGlobalSearch',
);
const shortcutKeysLogout = defineModel<boolean>('shortcutKeysLogout');
// const shortcutKeysPreferences = defineModel<boolean>('shortcutKeysPreferences');
const shortcutKeysLockScreen = defineModel<boolean>('shortcutKeysLockScreen');

const altView = computed(() => (isWindowsOs() ? 'Alt' : '⌥'));
</script>

<template>
  <SwitchItem v-model="shortcutKeysEnable">
    {{ '快捷键' }}
  </SwitchItem>
  <SwitchItem
    v-model="shortcutKeysGlobalSearch"
    :disabled="!shortcutKeysEnable"
  >
    {{ '全局搜索' }}
    <template #shortcut>
      {{ isWindowsOs() ? 'Ctrl' : '⌘' }}
      <kbd> K </kbd>
    </template>
  </SwitchItem>
  <SwitchItem v-model="shortcutKeysLogout" :disabled="!shortcutKeysEnable">
    {{ '退出登录' }}
    <template #shortcut> {{ altView }} Q </template>
  </SwitchItem>
  <!-- <SwitchItem v-model="shortcutKeysPreferences" :disabled="!shortcutKeysEnable">
    {{ '偏好设置' }}
    <template #shortcut> {{ altView }} , </template>
  </SwitchItem> -->
  <SwitchItem v-model="shortcutKeysLockScreen" :disabled="!shortcutKeysEnable">
    {{ '锁定屏幕' }}
    <template #shortcut> {{ altView }} L </template>
  </SwitchItem>
</template>
