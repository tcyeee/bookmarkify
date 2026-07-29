<script lang="ts" setup>
import type { WebsiteLivenessCheckResult } from "#/api/website";

import { computed, defineAsyncComponent, ref, watch } from "vue";

import { checkWebsiteLivenessApi } from "#/api/website";

const props = defineProps<{
  /** 待重新解析的 URL */
  url?: string;
}>();

const visible = defineModel<boolean>({ default: false });

const ElDialog = defineAsyncComponent(() =>
  Promise.all([
    import("element-plus/es/components/dialog/index"),
    import("element-plus/es/components/dialog/style/css"),
  ]).then(([res]) => res.ElDialog)
);

const ElTag = defineAsyncComponent(() =>
  Promise.all([
    import("element-plus/es/components/tag/index"),
    import("element-plus/es/components/tag/style/css"),
  ]).then(([res]) => res.ElTag)
);

const ElButton = defineAsyncComponent(() =>
  Promise.all([
    import("element-plus/es/components/button/index"),
    import("element-plus/es/components/button/style/css"),
  ]).then(([res]) => res.ElButton)
);

const loading = ref(false);
const result = ref<null | WebsiteLivenessCheckResult>(null);

/** 打开对话框时自动发起一次解析；关闭时清空上一次结果 */
watch(visible, (opened) => {
  if (opened) {
    result.value = null;
    parse();
  } else {
    result.value = null;
  }
});

async function parse() {
  if (!props.url) return;
  loading.value = true;
  try {
    result.value = await checkWebsiteLivenessApi(props.url);
  } finally {
    loading.value = false;
  }
}

/** 图片区展示项：favicon / logo / og 图 / 截图，没有的也要占位写"没有" */
const imageSlots = computed(() => [
  { key: "favicon", label: "favicon 图", src: result.value?.favicon, wide: false },
  { key: "logo", label: "logo 图", src: result.value?.logo, wide: false },
  { key: "image", label: "og 图", src: result.value?.image, wide: true },
  { key: "screenshot", label: "截图", src: result.value?.screenshot, wide: true },
]);

/** favicon 是 base64 data URL，原样塞进 JSON 会撑爆面板，只保留前 10 个字符 */
function truncate(value?: string) {
  if (!value) return value ?? null;
  return value.length > 10 ? `${value.slice(0, 10)}…(共 ${value.length} 字符)` : value;
}

/** scrapper 原始返回字段(不含本页自行附加的 success/errorMsg/synced) */
const rawJson = computed(() => {
  if (!result.value) return "";
  const { title, description, image, favicon, logo, source, cached, screenshot } = result.value;
  return JSON.stringify(
    {
      title: title ?? null,
      description: description ?? null,
      image: image ?? null,
      favicon: truncate(favicon),
      logo: logo ?? null,
      source: source ?? null,
      cached: cached ?? null,
      // 截图在 headless 模式下可能是 base64 PNG，同样截断
      screenshot: screenshot?.startsWith("data:") ? truncate(screenshot) : (screenshot ?? null),
    },
    null,
    2
  );
});
</script>

<template>
  <ElDialog v-model="visible" title="书签解析" width="1000px" top="6vh">
    <div class="mb-3 flex items-center gap-2 text-sm">
      <span class="shrink-0 text-gray-500">URL</span>
      <span class="flex-1 break-all">{{ url }}</span>
      <ElButton size="small" :loading="loading" @click="parse">重新解析</ElButton>
    </div>

    <div v-if="loading" class="py-10 text-center text-sm text-gray-400">解析中…</div>

    <div v-else-if="result" class="flex flex-col gap-4 lg:flex-row">
      <!-- 左：带样式的信息 -->
      <div class="flex-1 space-y-2 rounded border border-gray-100 p-3 text-sm lg:w-1/2">
        <div class="flex flex-wrap items-center gap-2">
          <ElTag :type="result.success ? 'success' : 'danger'" size="small">
            {{ result.success ? "抓取成功" : "抓取失败" }}
          </ElTag>
          <span v-if="result.source" class="text-gray-500">来源：{{ result.source }}</span>
          <ElTag v-if="result.cached" type="info" size="small">命中缓存</ElTag>
          <ElTag v-if="result.synced" type="success" size="small">已同步至数据库</ElTag>
        </div>
        <div class="flex">
          <span class="w-20 shrink-0 text-gray-500">标题</span>
          <span class="flex-1 break-all">{{ result.title || "没有" }}</span>
        </div>
        <div class="flex">
          <span class="w-20 shrink-0 text-gray-500">描述</span>
          <span class="flex-1 break-all">{{ result.description || "没有" }}</span>
        </div>
        <div v-if="result.errorMsg" class="flex">
          <span class="w-20 shrink-0 text-gray-500">错误信息</span>
          <span class="flex-1 break-all text-red-500">{{ result.errorMsg }}</span>
        </div>
        <div class="flex flex-wrap gap-4 pt-1">
          <div
            v-for="item in imageSlots"
            :key="item.key"
            class="flex flex-col items-center gap-1"
          >
            <img
              v-if="item.src"
              :src="item.src"
              :alt="item.key"
              class="rounded border object-contain"
              :class="item.wide ? 'h-16 w-28 object-cover' : 'h-10 w-10'"
            />
            <div
              v-else
              class="flex items-center justify-center rounded border border-dashed text-xs text-gray-400"
              :class="item.wide ? 'h-16 w-28' : 'h-10 w-10'"
            >
              没有
            </div>
            <span class="text-xs text-gray-400">{{ item.label }}</span>
          </div>
        </div>
      </div>

      <!-- 右：原始 JSON -->
      <div class="flex-1 rounded border border-gray-100 p-3 text-sm lg:w-1/2">
        <div class="mb-2 text-gray-500">原始 JSON</div>
        <pre
          class="max-h-[60vh] overflow-auto whitespace-pre-wrap break-all rounded bg-gray-50 p-2 font-mono text-xs"
        >{{ rawJson }}</pre>
      </div>
    </div>
  </ElDialog>
</template>
