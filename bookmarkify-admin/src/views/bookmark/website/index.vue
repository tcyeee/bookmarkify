<script lang="ts" setup>
import type { WebsiteLivenessCheckResult } from "#/api/website";

import { defineAsyncComponent, ref } from "vue";

import { Page } from "@vben/common-ui";

import { ElMessage } from "element-plus";

import {
  checkWebsiteLivenessApi,
  classifyBookmarkLinkTypeApi,
} from "#/api/website";

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
        ? "检测完成，抓取成功"
        : `检测完成，抓取失败${result.errorMsg ? `：${result.errorMsg}` : ""}`,
    );
  } finally {
    checking.value = false;
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

    <ElCard shadow="never" class="mt-4">
      <template #header>
        <div class="flex flex-wrap items-center justify-between gap-3">
          <span>书签活性检测</span>
        </div>
      </template>

      <div class="flex flex-col items-start gap-3">
        <p class="text-sm text-gray-500">
          输入任意网站 URL 并直接调用 scrapper 抓取一次，展示其返回的全部原始字段；无需该网站已收录为书签。
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

        <div v-if="checkResult" class="w-full space-y-2 rounded border border-gray-100 p-3 text-sm">
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
      </div>
    </ElCard>
  </Page>
</template>
