<script setup lang="ts">

import SwitchItem from '../switch-item.vue';

defineOptions({
  name: 'PreferenceColorMode',
});

const appColorWeakMode = defineModel<boolean>('appColorWeakMode', {
  default: false,
});

const appColorGrayMode = defineModel<boolean>('appColorGrayMode', {
  default: false,
});
</script>

<template>
  <SwitchItem v-model="appColorWeakMode">
    {{ '色弱模式' }}
  </SwitchItem>
  <SwitchItem v-model="appColorGrayMode">
    {{ '灰色模式' }}
  </SwitchItem>
</template>
