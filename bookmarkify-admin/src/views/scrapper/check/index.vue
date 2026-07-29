<script lang="ts" setup>
import type { WebsiteLivenessCheckResult } from "#/api/website";

import { computed, defineAsyncComponent, ref } from "vue";

import { Page } from "@vben/common-ui";

import { ElMessage } from "element-plus";

import { checkWebsiteLivenessApi } from "#/api/website";

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

const ElInput = defineAsyncComponent(() =>
  Promise.all([
    import("element-plus/es/components/input/index"),
    import("element-plus/es/components/input/style/css"),
  ]).then(([res]) => res.ElInput),
);

const ElTag = defineAsyncComponent(() =>
  Promise.all([
    import("element-plus/es/components/tag/index"),
    import("element-plus/es/components/tag/style/css"),
  ]).then(([res]) => res.ElTag),
);

// ── 书签活性检测：任意输入一个网站 URL，直接调用 scrapper 抓取一次，展示其返回的全部原始字段 ──
const checkUrl = ref("");
const checking = ref(false);
const checkResult = ref<null | WebsiteLivenessCheckResult>(null);

async function checkLiveness() {
  const url = checkUrl.value.trim();
  if (!url) {
    ElMessage.warning("请先输入网站 URL");
    return;
  }
  checking.value = true;
  checkResult.value = null;
  try {
    const result = await checkWebsiteLivenessApi(url);
    checkResult.value = result;
    ElMessage[result.success ? "success" : "warning"](
      result.success
        ? `检测完成，抓取成功${result.synced ? "，已同步至数据库" : ""}`
        : `检测完成，抓取失败${result.errorMsg ? `：${result.errorMsg}` : ""}`,
    );
  } finally {
    checking.value = false;
  }
}

/** scrapper 原始返回字段(不含本页自行附加的 success/errorMsg/synced)，供右侧原始 JSON 展示 */
const rawScraperJson = computed(() => {
  if (!checkResult.value) return "";
  const { title, description, image, favicon, logo, source, cached, screenshot } =
    checkResult.value;
  return JSON.stringify(
    { title, description, image, favicon, logo, source, cached, screenshot },
    null,
    2,
  );
});
</script>

<template>
  <Page auto-content-height>
    <ElCard shadow="never">
      <template #header>
        <div class="flex flex-wrap items-center justify-between gap-3">
          <span>书签活性检测</span>
        </div>
      </template>

      <div class="flex flex-col items-start gap-3">
        <p class="text-sm text-gray-500">
          输入任意网站 URL 并直接调用 scrapper 抓取一次，展示其返回的全部原始字段；无需该网站已收录为书签。
          若抓取成功且该 URL 命中已有书签，会同步覆盖持久化该书签的标题/简介/图标/活性状态。
        </p>
        <div class="flex w-full max-w-xl items-center gap-2">
          <ElInput
            v-model="checkUrl"
            placeholder="输入网站 URL，如 https://example.com"
            clearable
            @keyup.enter="checkLiveness"
          />
          <ElButton type="primary" :loading="checking" @click="checkLiveness">
            检测
          </ElButton>
        </div>

        <div v-if="checkResult" class="flex w-full flex-col gap-4 lg:flex-row">
          <div class="flex-1 space-y-2 rounded border border-gray-100 p-3 text-sm lg:w-1/2">
            <div class="flex flex-wrap items-center gap-2">
              <ElTag :type="checkResult.success ? 'success' : 'danger'" size="small">
                {{ checkResult.success ? "抓取成功" : "抓取失败" }}
              </ElTag>
              <span v-if="checkResult.source" class="text-gray-500">
                来源：{{ checkResult.source }}
              </span>
              <ElTag v-if="checkResult.cached" type="info" size="small">
                命中缓存
              </ElTag>
              <ElTag v-if="checkResult.synced" type="success" size="small">
                已同步至数据库
              </ElTag>
            </div>
            <div v-if="checkResult.title" class="flex">
              <span class="w-20 shrink-0 text-gray-500">标题</span>
              <span class="flex-1 break-all">{{ checkResult.title }}</span>
            </div>
            <div v-if="checkResult.description" class="flex">
              <span class="w-20 shrink-0 text-gray-500">描述</span>
              <span class="flex-1 break-all">{{ checkResult.description }}</span>
            </div>
            <div v-if="checkResult.errorMsg" class="flex">
              <span class="w-20 shrink-0 text-gray-500">错误信息</span>
              <span class="flex-1 break-all text-red-500">{{ checkResult.errorMsg }}</span>
            </div>
            <div
              v-if="checkResult.favicon || checkResult.logo || checkResult.image || checkResult.screenshot"
              class="flex flex-wrap gap-4 pt-1"
            >
              <div v-if="checkResult.favicon" class="flex flex-col items-center gap-1">
                <img :src="checkResult.favicon" alt="favicon" class="h-10 w-10 rounded border object-contain" />
                <span class="text-xs text-gray-400">favicon</span>
              </div>
              <div v-if="checkResult.logo" class="flex flex-col items-center gap-1">
                <img :src="checkResult.logo" alt="logo" class="h-10 w-10 rounded border object-contain" />
                <span class="text-xs text-gray-400">logo</span>
              </div>
              <div v-if="checkResult.image" class="flex flex-col items-center gap-1">
                <img :src="checkResult.image" alt="image" class="h-16 w-28 rounded border object-cover" />
                <span class="text-xs text-gray-400">image</span>
              </div>
              <div v-if="checkResult.screenshot" class="flex flex-col items-center gap-1">
                <img :src="checkResult.screenshot" alt="screenshot" class="h-16 w-28 rounded border object-cover" />
                <span class="text-xs text-gray-400">screenshot</span>
              </div>
            </div>
          </div>

          <div class="flex-1 rounded border border-gray-100 p-3 text-sm lg:w-1/2">
            <div class="mb-2 text-gray-500">原始 JSON</div>
            <pre class="max-h-96 overflow-auto whitespace-pre-wrap break-all rounded bg-gray-50 p-2 font-mono text-xs">{{ rawScraperJson }}</pre>
          </div>
        </div>
      </div>
    </ElCard>
  </Page>
</template>
