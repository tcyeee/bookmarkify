<script lang="ts" setup>
import { computed, defineAsyncComponent, onMounted, reactive, ref } from "vue";

import { Page } from "@vben/common-ui";

import { ElMessage } from "element-plus";

import type { BookmarkLivenessConfigVO } from "#/api/bookmark-liveness-config";

import { getBookmarkLivenessConfigApi, saveBookmarkLivenessConfigApi } from "#/api/bookmark-liveness-config";

const ElCard = defineAsyncComponent(() =>
  Promise.all([
    import("element-plus/es/components/card/index"),
    import("element-plus/es/components/card/style/css"),
  ]).then(([res]) => res.ElCard)
);

const ElForm = defineAsyncComponent(() =>
  Promise.all([
    import("element-plus/es/components/form/index"),
    import("element-plus/es/components/form/style/css"),
  ]).then(([res]) => res.ElForm)
);

const ElFormItem = defineAsyncComponent(() =>
  Promise.all([
    import("element-plus/es/components/form/index"),
    import("element-plus/es/components/form/style/css"),
  ]).then(([res]) => res.ElFormItem)
);

const ElButton = defineAsyncComponent(() =>
  Promise.all([
    import("element-plus/es/components/button/index"),
    import("element-plus/es/components/button/style/css"),
  ]).then(([res]) => res.ElButton)
);

const ElInputNumber = defineAsyncComponent(() =>
  Promise.all([
    import("element-plus/es/components/input-number/index"),
    import("element-plus/es/components/input-number/style/css"),
  ]).then(([res]) => res.ElInputNumber)
);

const configLoading = ref(false);
const configSaving = ref(false);
// 字段与 BookmarkLivenessConfigVO 一一对应，所以下面统一用 Object.assign 同步，
// 不再逐字段抄一遍 —— 加一项配置时漏抄一行不会报错，只会让那一项永远保存不上
const livenessConfig = reactive<BookmarkLivenessConfigVO>({
  activeCheckIntervalHours: 168,
  abnormalBackoffMultiplier: 2,
  abnormalCheckIntervalHours: 24,
  abnormalMaxIntervalHours: 384,
  contentRefreshIntervalDays: 30,
  deadConfirmFailures: 3,
});

async function fetchLivenessConfig() {
  configLoading.value = true;
  try {
    Object.assign(livenessConfig, await getBookmarkLivenessConfigApi());
  } finally {
    configLoading.value = false;
  }
}

async function saveLivenessConfig() {
  configSaving.value = true;
  try {
    Object.assign(
      livenessConfig,
      await saveBookmarkLivenessConfigApi({ ...livenessConfig }),
    );
    ElMessage.success("已保存");
  } finally {
    configSaving.value = false;
  }
}

/**
 * 前五次重试的实际间隔，把三个数字换算成管理员真正关心的那条曲线。
 *
 * 「基数 24、倍数 2、上限 384」单看是三个无从判断的数字，写成「24 / 48 / 96 / 192 / 384」
 * 才看得出「一个死站点大约两周后就不怎么重试了」——而那才是要调的东西。
 */
const backoffPreview = computed(() => {
  const base = Math.max(1, livenessConfig.abnormalCheckIntervalHours);
  const cap = Math.max(base, livenessConfig.abnormalMaxIntervalHours);
  const multiplier = Math.max(1, livenessConfig.abnormalBackoffMultiplier);
  const steps: number[] = [];
  let hours = base;
  for (let i = 0; i < 5; i++) {
    steps.push(Math.min(hours, cap));
    hours = Math.min(hours * multiplier, cap);
  }
  return steps.join(" / ");
});

onMounted(() => {
  fetchLivenessConfig();
});
</script>

<template>
  <Page auto-content-height>
    <ElCard shadow="never">
      <template #header>
        <div class="flex items-center justify-between">
          <span>书签检查配置</span>
        </div>
      </template>
      <div class="rounded border border-solid border-gray-200 p-4 dark:border-gray-700">
        <ElForm :inline="true" :model="livenessConfig" v-loading="configLoading">
          <ElFormItem label="已激活的书签检测频率">
            <ElInputNumber v-model="livenessConfig.activeCheckIntervalHours" :min="1" :step="1" />
            <span class="ml-2 text-gray-500">小时</span>
          </ElFormItem>
          <ElFormItem label="判定失活所需连续失败次数">
            <ElInputNumber v-model="livenessConfig.deadConfirmFailures" :max="9" :min="1" :step="1" />
            <span class="ml-2 text-gray-500">次</span>
          </ElFormItem>
          <ElFormItem label="初次重试间隔">
            <ElInputNumber v-model="livenessConfig.abnormalCheckIntervalHours" :min="1" :step="1" />
            <span class="ml-2 text-gray-500">小时</span>
          </ElFormItem>
          <ElFormItem label="重试叠加倍数">
            <ElInputNumber v-model="livenessConfig.abnormalBackoffMultiplier" :min="1" :step="1" />
            <span class="ml-2 text-gray-500">倍</span>
          </ElFormItem>
          <ElFormItem label="最长重试间隔">
            <ElInputNumber v-model="livenessConfig.abnormalMaxIntervalHours" :min="1" :step="1" />
            <span class="ml-2 text-gray-500">小时</span>
          </ElFormItem>
          <ElFormItem label="内容重新抓取间隔">
            <ElInputNumber v-model="livenessConfig.contentRefreshIntervalDays" :min="1" :step="1" />
            <span class="ml-2 text-gray-500">天</span>
          </ElFormItem>
          <ElFormItem>
            <ElButton type="primary" :loading="configSaving" @click="saveLivenessConfig">保存</ElButton>
          </ElFormItem>
        </ElForm>
        <div class="text-xs leading-relaxed text-gray-400">
          <p>
            <b>判失活：</b>单次探测失败不算数（目标站点重启、我方出口抖动都会产生一次失败），
            必须连续失败到设定次数才把书签标记为失活；在此之前书签对用户照常显示为可用。
            探测「无结论」（反爬拦截、抓取服务自身故障）不计入这个次数。
          </p>
          <p>
            <b>重试退避：</b>失败后按「初次重试间隔 × 叠加倍数^(失败次数-1)」逐次拉长，涨到「最长重试间隔」封顶。
            当前配置下依次为
            {{ backoffPreview }} 小时…（上限 {{ livenessConfig.abnormalMaxIntervalHours }} 小时）。
            叠加倍数填 1 即固定间隔、不退避。初次重试间隔不能大于已激活书签检测频率，最长重试间隔不能小于初次重试间隔。
          </p>
          <p>
            「检测」只发一个 HEAD 请求判断站点是否存活，「重新抓取」会走完整抓取链路更新标题与图标，代价高得多，
            所以间隔单独配置且不能短于检测频率。连续失败 10 次的书签会转入归档、停止巡检（每天仍有一次复活探测）。
          </p>
        </div>
      </div>
    </ElCard>
  </Page>
</template>
