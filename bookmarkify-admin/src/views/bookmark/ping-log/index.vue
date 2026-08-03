<script lang="ts" setup>
import { defineAsyncComponent, onMounted, reactive, ref } from "vue";

import { Page } from "@vben/common-ui";

import { ElMessage } from "element-plus";

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
const livenessConfig = reactive({
  activeCheckIntervalHours: 168,
  abnormalCheckIntervalHours: 24,
  contentRefreshIntervalDays: 30,
});

async function fetchLivenessConfig() {
  configLoading.value = true;
  try {
    const res = await getBookmarkLivenessConfigApi();
    livenessConfig.activeCheckIntervalHours = res.activeCheckIntervalHours;
    livenessConfig.abnormalCheckIntervalHours = res.abnormalCheckIntervalHours;
    livenessConfig.contentRefreshIntervalDays = res.contentRefreshIntervalDays;
  } finally {
    configLoading.value = false;
  }
}

async function saveLivenessConfig() {
  configSaving.value = true;
  try {
    const res = await saveBookmarkLivenessConfigApi({
      activeCheckIntervalHours: livenessConfig.activeCheckIntervalHours,
      abnormalCheckIntervalHours: livenessConfig.abnormalCheckIntervalHours,
      contentRefreshIntervalDays: livenessConfig.contentRefreshIntervalDays,
    });
    livenessConfig.activeCheckIntervalHours = res.activeCheckIntervalHours;
    livenessConfig.abnormalCheckIntervalHours = res.abnormalCheckIntervalHours;
    livenessConfig.contentRefreshIntervalDays = res.contentRefreshIntervalDays;
    ElMessage.success("已保存");
  } finally {
    configSaving.value = false;
  }
}

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
          <ElFormItem label="异常书签检测频率">
            <ElInputNumber v-model="livenessConfig.abnormalCheckIntervalHours" :min="1" :step="1" />
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
        <div class="text-xs text-gray-400">
          异常书签检测频率不能低于已激活书签检测频率（即间隔小时数不能更大）；
          「检测」只发一个 HEAD 请求判断站点是否存活，「重新抓取」会走完整抓取链路更新标题与图标，代价高得多，所以间隔单独配置且不能短于检测频率。
          连续失败 10 次的书签会转入归档、停止巡检。
        </div>
      </div>
    </ElCard>
  </Page>
</template>
