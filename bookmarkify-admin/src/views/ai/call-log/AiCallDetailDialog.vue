<script lang="ts" setup>
import type { AiCallLogVO } from "#/api/ai-call-log";

import { computed, defineAsyncComponent } from "vue";

import { formatDateTime } from "@vben/utils";

import { AI_SCENE_LABEL } from "./scene";

const props = defineProps<{
  /** 当前查看的日志行；列表接口已带回请求/响应原文，无需再单独请求详情 */
  row?: AiCallLogVO | null;
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

interface ChatMessage {
  role: string;
  content: string;
}

/** 请求体是我方拼的，结构稳定；解析失败也要能看到原文，所以额外返回 raw */
const request = computed<{ messages: ChatMessage[]; maxTokens?: number; raw: string }>(() => {
  const raw = props.row?.requestBody ?? "";
  try {
    const parsed = JSON.parse(raw);
    return {
      messages: Array.isArray(parsed?.messages) ? parsed.messages : [],
      maxTokens: parsed?.max_tokens,
      raw,
    };
  } catch {
    return { messages: [], raw };
  }
});

/** 响应体来自第三方：失败时可能是纯文本错误页，一律先尝试 JSON，失败就原样显示 */
const response = computed<{ content?: string; pretty: string }>(() => {
  const raw = props.row?.responseBody ?? "";
  try {
    const parsed = JSON.parse(raw);
    return {
      content: parsed?.choices?.[0]?.message?.content,
      pretty: JSON.stringify(parsed, null, 2),
    };
  } catch {
    return { pretty: raw };
  }
});

const ROLE_LABEL: Record<string, string> = {
  system: "系统提示词",
  user: "用户输入",
  assistant: "模型回复",
};
</script>

<template>
  <ElDialog v-model="visible" title="AI 通讯详情" width="860px" top="6vh">
    <div v-if="row" class="space-y-4">
      <!-- 概览 -->
      <div class="grid grid-cols-2 gap-x-6 gap-y-2 text-sm md:grid-cols-3">
        <div>
          <span class="text-gray-400">场景：</span>
          <span>{{ AI_SCENE_LABEL[row.scene] ?? row.scene }}</span>
        </div>
        <div>
          <span class="text-gray-400">服务商：</span>
          <span>{{ row.provider }}</span>
        </div>
        <div>
          <span class="text-gray-400">模型：</span>
          <span class="font-mono">{{ row.model || "-" }}</span>
        </div>
        <div class="col-span-2 truncate md:col-span-3">
          <span class="text-gray-400">判定对象：</span>
          <span>{{ row.subject || "-" }}</span>
        </div>
        <div>
          <span class="text-gray-400">结果：</span>
          <ElTag :type="row.success ? 'success' : 'danger'" size="small">
            {{ row.success ? "成功" : "失败" }}
          </ElTag>
        </div>
        <div>
          <span class="text-gray-400">HTTP：</span>
          <span>{{ row.httpStatus ?? "-" }}</span>
        </div>
        <div>
          <span class="text-gray-400">耗时：</span>
          <span>{{ row.durationMs }} ms</span>
        </div>
        <div>
          <span class="text-gray-400">token：</span>
          <span>
            {{ row.promptTokens ?? "-" }} 入 / {{ row.completionTokens ?? "-" }} 出 /
            {{ row.totalTokens ?? "-" }} 合计
          </span>
        </div>
        <div>
          <span class="text-gray-400">max_tokens：</span>
          <span>{{ request.maxTokens ?? "-" }}</span>
        </div>
        <div>
          <span class="text-gray-400">时间：</span>
          <span>{{ formatDateTime(row.createTime) }}</span>
        </div>
      </div>

      <div v-if="row.errorMsg" class="rounded bg-red-50 p-3 text-sm text-red-600 dark:bg-red-950">
        {{ row.errorMsg }}
      </div>

      <!-- 请求 -->
      <div>
        <div class="mb-2 font-medium">请求</div>
        <div v-if="request.messages.length > 0" class="space-y-2">
          <div v-for="(msg, i) in request.messages" :key="i" class="rounded border">
            <div class="border-b bg-gray-50 px-3 py-1 text-xs text-gray-500 dark:bg-gray-800">
              {{ ROLE_LABEL[msg.role] ?? msg.role }}
            </div>
            <pre class="max-h-60 overflow-auto px-3 py-2 text-xs leading-relaxed whitespace-pre-wrap">{{
              msg.content
            }}</pre>
          </div>
        </div>
        <pre
          v-else
          class="max-h-60 overflow-auto rounded border px-3 py-2 text-xs whitespace-pre-wrap"
          >{{ request.raw || "-" }}</pre
        >
      </div>

      <!-- 响应 -->
      <div>
        <div class="mb-2 font-medium">响应</div>
        <div v-if="response.content" class="mb-2 rounded border border-green-200 bg-green-50 dark:bg-green-950">
          <div class="border-b border-green-200 px-3 py-1 text-xs text-gray-500">模型输出正文</div>
          <pre class="max-h-60 overflow-auto px-3 py-2 text-xs leading-relaxed whitespace-pre-wrap">{{
            response.content
          }}</pre>
        </div>
        <details>
          <summary class="cursor-pointer text-xs text-gray-500">原始响应体</summary>
          <pre class="mt-2 max-h-72 overflow-auto rounded border px-3 py-2 text-xs whitespace-pre-wrap">{{
            response.pretty || "-"
          }}</pre>
        </details>
      </div>
    </div>
  </ElDialog>
</template>
