<script setup lang="ts">
import InputItem from '../input-item.vue';
import SwitchItem from '../switch-item.vue';

defineOptions({
  name: 'PreferenceGeneralConfig',
});

const appDynamicTitle = defineModel<boolean>('appDynamicTitle');
const appWatermark = defineModel<boolean>('appWatermark');
const appWatermarkContent = defineModel<string>('appWatermarkContent');
const appEnableCheckUpdates = defineModel<boolean>('appEnableCheckUpdates');
</script>

<template>
  <SwitchItem v-model="appDynamicTitle">
    {{ '动态标题' }}
  </SwitchItem>
  <SwitchItem
    v-model="appWatermark"
    @update:model-value="
      (val) => {
        if (!val) appWatermarkContent = '';
      }
    "
  >
    {{ '水印' }}
  </SwitchItem>
  <InputItem
    v-if="appWatermark"
    v-model="appWatermarkContent"
    :placeholder="'请输入水印文案'"
  >
    {{ '请输入水印文案' }}
  </InputItem>
  <SwitchItem v-model="appEnableCheckUpdates">
    {{ '定时检查更新' }}
  </SwitchItem>
</template>
