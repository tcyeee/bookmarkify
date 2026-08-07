<script lang="ts" setup>
/**
 * 巡检与判定：多久探一次、失败几次才算失活、多久重新抓一次内容。
 * 与「重试与归档」共用同一份配置对象，见 useLivenessConfig。
 */
import { defineAsyncComponent } from 'vue';

import SettingRow from './SettingRow.vue';
import { useLivenessConfig } from './useLivenessConfig';

const { config, save, saving } = useLivenessConfig();

const ElButton = defineAsyncComponent(() =>
  Promise.all([
    import('element-plus/es/components/button/index'),
    import('element-plus/es/components/button/style/css'),
  ]).then(([res]) => res.ElButton),
);

const ElInputNumber = defineAsyncComponent(() =>
  Promise.all([
    import('element-plus/es/components/input-number/index'),
    import('element-plus/es/components/input-number/style/css'),
  ]).then(([res]) => res.ElInputNumber),
);
</script>

<template>
  <div>
    <SettingRow
      help="「检测」只发一个 HEAD 请求判断站点是否还在，代价很低，因此可以配得比重新抓取密得多。存活状态的书签每隔这么久探测一次。"
      label="已激活书签的检测频率"
      tip="活着的书签多久探一次"
    >
      <ElInputNumber
        v-model="config.activeCheckIntervalHours"
        :min="1"
        :step="1"
        controls-position="right"
      />
      <span class="text-muted-foreground text-sm">小时</span>
    </SettingRow>

    <SettingRow
      help="单次探测失败不算数 —— 目标站点重启、我方出口抖动都会产生一次失败。必须连续失败到这个次数，书签才被标记为失活；在此之前它对用户照常显示为可用。探测「无结论」（反爬拦截、抓取服务自身故障）不计入这个次数。该值必须小于「最大重试次数」，否则书签会在被标成失活之前先归档，「失活」这个状态永远不出现。"
      label="判定失活所需连续失败次数"
      tip="连续失败到这个次数才判失活"
    >
      <!-- 上界跟着「最大重试次数」走，理由同 help 文案末句 -->
      <ElInputNumber
        v-model="config.deadConfirmFailures"
        :max="Math.max(1, config.maxRetryFailures - 1)"
        :min="1"
        :step="1"
        controls-position="right"
      />
      <span class="text-muted-foreground text-sm">次</span>
    </SettingRow>

    <SettingRow
      help="「重新抓取」会走完整抓取链路更新标题与图标，代价远高于一次探测，所以间隔单独配置，且不应短于检测频率。"
      label="内容重新抓取间隔"
      tip="多久重跑一次完整抓取，刷新标题与图标"
    >
      <ElInputNumber
        v-model="config.contentRefreshIntervalDays"
        :min="1"
        :step="1"
        controls-position="right"
      />
      <span class="text-muted-foreground text-sm">天</span>
    </SettingRow>

    <div class="mt-6">
      <ElButton :loading="saving" type="primary" @click="save">保存</ElButton>
    </div>
  </div>
</template>
