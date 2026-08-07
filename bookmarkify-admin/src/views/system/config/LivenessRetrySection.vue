<script lang="ts" setup>
/**
 * 重试与归档：探测失败之后的退避曲线，以及失败到什么程度就不再管它。
 * 与「巡检与判定」共用同一份配置对象，见 useLivenessConfig。
 */
import { computed, defineAsyncComponent } from 'vue';

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

/**
 * 前五次重试的实际间隔，把三个数字换算成管理员真正关心的那条曲线。
 *
 * 「基数 24、倍数 2、上限 384」单看是三个无从判断的数字，写成「24 / 48 / 96 / 192 / 384」
 * 才看得出「一个死站点大约两周后就不怎么重试了」——而那才是要调的东西。
 */
const backoffPreview = computed(() => {
  const base = Math.max(1, config.abnormalCheckIntervalHours);
  const cap = Math.max(base, config.abnormalMaxIntervalHours);
  const multiplier = Math.max(1, config.abnormalBackoffMultiplier);
  const steps: number[] = [];
  let hours = base;
  for (let i = 0; i < 5; i++) {
    steps.push(Math.min(hours, cap));
    hours = Math.min(hours * multiplier, cap);
  }
  return steps.join(' / ');
});
</script>

<template>
  <div>
    <SettingRow
      help="第一次探测失败后隔多久重试。不能大于「已激活书签的检测频率」——重试比常规巡检还稀疏，就等于失败反而降低了关注度。"
      label="初次重试间隔"
      tip="失败后第一次重试等多久"
    >
      <ElInputNumber
        v-model="config.abnormalCheckIntervalHours"
        :min="1"
        :step="1"
        controls-position="right"
      />
      <span class="text-muted-foreground text-sm">小时</span>
    </SettingRow>

    <SettingRow
      help="每多失败一次，间隔就乘以这个倍数：初次重试间隔 × 倍数^(失败次数-1)，涨到「最长重试间隔」封顶。填 1 即固定间隔、不退避。"
      label="重试叠加倍数"
      tip="每失败一次，下次等待时间乘以它"
    >
      <ElInputNumber
        v-model="config.abnormalBackoffMultiplier"
        :min="1"
        :step="1"
        controls-position="right"
      />
      <span class="text-muted-foreground text-sm">倍</span>
    </SettingRow>

    <SettingRow
      help="退避曲线的封顶值，不能小于「初次重试间隔」。"
      label="最长重试间隔"
      tip="退避涨到这里为止"
    >
      <ElInputNumber
        v-model="config.abnormalMaxIntervalHours"
        :min="1"
        :step="1"
        controls-position="right"
      />
      <span class="text-muted-foreground text-sm">小时</span>
    </SettingRow>

    <SettingRow
      help="连续失败到这个次数的书签转入归档，此后不再有任何定时任务检测它 —— 一个再也回不来的域名每轮都要吃掉一次探测和一个批量名额，而它恢复的可能性随时间趋近于零。归档不是死路：只要有用户重新添加这个网址，重试次数就地清零并立即重新检查，管理员手动刷新/检测同样有效。该值必须大于「判定失活所需连续失败次数」。"
      label="失活网站最大重试次数"
      tip="失败到这个次数就归档，不再自动检测"
    >
      <ElInputNumber
        v-model="config.maxRetryFailures"
        :min="2"
        :step="1"
        controls-position="right"
      />
      <span class="text-muted-foreground text-sm">次</span>
    </SettingRow>

    <div
      class="bg-accent text-muted-foreground mt-4 rounded p-3 text-xs leading-relaxed"
    >
      当前配置下，一个持续失败的书签依次在
      <b class="text-foreground">{{ backoffPreview }}</b> 小时后重试，之后固定为
      {{ config.abnormalMaxIntervalHours }} 小时一次，累计失败
      {{ config.maxRetryFailures }} 次后归档。
    </div>

    <div class="mt-6">
      <ElButton :loading="saving" type="primary" @click="save">保存</ElButton>
    </div>
  </div>
</template>
