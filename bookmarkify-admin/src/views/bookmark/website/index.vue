<script lang="ts" setup>
import { defineAsyncComponent, ref } from "vue";

import { Page } from "@vben/common-ui";

import { ElMessage } from "element-plus";

import { classifyBookmarkLinkTypeApi } from "#/api/website";

const ElCard = defineAsyncComponent(() =>
  Promise.all([
    import("element-plus/es/components/card/index"),
    import("element-plus/es/components/card/style/css"),
  ]).then(([res]) => res.ElCard),
);

const ElButton = defineAsyncComponent(() =>
  Promise.all([
    import("element-plus/es/components/button/index"),
    import("element-plus/es/components/button/style/css"),
  ]).then(([res]) => res.ElButton),
);

const classifying = ref(false);
const lastTotal = ref<null | number>(null);

/** 对全部书签重新按地址分类为 域名/本地/IP/其他 四种类型 */
async function classifyLinkType() {
  classifying.value = true;
  try {
    const res = await classifyBookmarkLinkTypeApi();
    lastTotal.value = res.total;
    ElMessage.success(`分类完成，共处理 ${res.total} 条书签`);
  } finally {
    classifying.value = false;
  }
}
</script>

<template>
  <Page auto-content-height>
    <ElCard shadow="never">
      <template #header>
        <div class="flex flex-wrap items-center justify-between gap-3">
          <span>网站类型分类</span>
        </div>
      </template>

      <div class="flex flex-col items-start gap-3">
        <p class="text-sm text-gray-500">
          将全部书签按地址重新分类为「域名 / 本地 / IP /
          其他」四种类型：本地(localhost、127.0.0.1)与 IP
          地址类型的书签不会再被抓取网站信息，前端仅展示统一图标。
        </p>
        <ElButton
          type="primary"
          :loading="classifying"
          @click="classifyLinkType"
        >
          一键分类全部书签
        </ElButton>
        <p v-if="lastTotal !== null" class="text-sm text-gray-500">
          上次处理：{{ lastTotal }} 条
        </p>
      </div>
    </ElCard>
  </Page>
</template>
