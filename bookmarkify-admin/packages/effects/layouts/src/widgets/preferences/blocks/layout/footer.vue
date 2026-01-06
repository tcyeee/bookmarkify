<script setup lang="ts">

import SwitchItem from '../switch-item.vue';

const footerEnable = defineModel<boolean>('footerEnable');
const footerFixed = defineModel<boolean>('footerFixed');
</script>

<template>
  <SwitchItem v-model="footerEnable">
    {{ '显示底栏' }}
  </SwitchItem>
  <SwitchItem v-model="footerFixed" :disabled="!footerEnable">
    {{ '固定在底部' }}
  </SwitchItem>
</template>
