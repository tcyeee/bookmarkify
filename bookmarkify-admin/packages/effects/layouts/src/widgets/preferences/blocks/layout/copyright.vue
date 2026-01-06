<script setup lang="ts">
import { computed } from 'vue';


import InputItem from '../input-item.vue';
import SwitchItem from '../switch-item.vue';

const props = defineProps<{ disabled: boolean }>();

const copyrightEnable = defineModel<boolean>('copyrightEnable');
const copyrightDate = defineModel<string>('copyrightDate');
const copyrightIcp = defineModel<string>('copyrightIcp');
const copyrightIcpLink = defineModel<string>('copyrightIcpLink');
const copyrightCompanyName = defineModel<string>('copyrightCompanyName');
const copyrightCompanySiteLink = defineModel<string>(
  'copyrightCompanySiteLink',
);

const itemDisabled = computed(() => props.disabled || !copyrightEnable.value);
</script>

<template>
  <SwitchItem v-model="copyrightEnable" :disabled="disabled">
    {{ '启用版权' }}
  </SwitchItem>

  <InputItem v-model="copyrightCompanyName" :disabled="itemDisabled">
    {{ '公司名' }}
  </InputItem>
  <InputItem v-model="copyrightCompanySiteLink" :disabled="itemDisabled">
    {{ '公司主页' }}
  </InputItem>
  <InputItem v-model="copyrightDate" :disabled="itemDisabled">
    {{ '日期' }}
  </InputItem>

  <InputItem v-model="copyrightIcp" :disabled="itemDisabled">
    {{ 'ICP 备案号' }}
  </InputItem>
  <InputItem v-model="copyrightIcpLink" :disabled="itemDisabled">
    {{ 'ICP 网站链接' }}
  </InputItem>
</template>
