<script lang="ts" setup>
import type { AiCallLogSearchParams, AiCallLogVO } from "#/api/ai-call-log";

import { defineAsyncComponent, reactive, ref } from "vue";

import { Page } from "@vben/common-ui";
import { formatDateTime } from "@vben/utils";

import { getAdminAiCallLogListApi } from "#/api/ai-call-log";
import { useVbenVxeGrid, type VxeGridProps } from "#/adapter/vxe-table";
import { FilterBar, FilterItem, useAutoSearch } from "#/components/filter-bar";

import AiCallDetailDialog from "./AiCallDetailDialog.vue";
import { AI_SCENE_LABEL, AI_SCENE_LEGEND } from "./scene";

const ElCard = defineAsyncComponent(() =>
  Promise.all([
    import("element-plus/es/components/card/index"),
    import("element-plus/es/components/card/style/css"),
  ]).then(([res]) => res.ElCard)
);

const ElInput = defineAsyncComponent(() =>
  Promise.all([
    import("element-plus/es/components/input/index"),
    import("element-plus/es/components/input/style/css"),
  ]).then(([res]) => res.ElInput)
);

const ElSelect = defineAsyncComponent(() =>
  Promise.all([
    import("element-plus/es/components/select/index"),
    import("element-plus/es/components/select/style/css"),
  ]).then(([res]) => res.ElSelect)
);

const ElOption = defineAsyncComponent(() =>
  Promise.all([
    import("element-plus/es/components/select/index"),
    import("element-plus/es/components/select/style/css"),
  ]).then(([res]) => res.ElOption)
);

const ElTag = defineAsyncComponent(() =>
  Promise.all([
    import("element-plus/es/components/tag/index"),
    import("element-plus/es/components/tag/style/css"),
  ]).then(([res]) => res.ElTag)
);

const ElTooltip = defineAsyncComponent(() =>
  Promise.all([
    import("element-plus/es/components/tooltip/index"),
    import("element-plus/es/components/tooltip/style/css"),
  ]).then(([res]) => res.ElTooltip)
);

const searchForm = reactive<Pick<AiCallLogSearchParams, "scene" | "subject" | "success">>({
  subject: "",
  scene: undefined,
  success: undefined,
});

// ── 详情弹窗：列表接口已带回请求/响应原文，点行直接展示，不再请求详情接口 ──
const detailVisible = ref(false);
const detailRow = ref<AiCallLogVO | null>(null);

function handleCellClick({ row }: { row: AiCallLogVO }) {
  detailRow.value = row;
  detailVisible.value = true;
}

const gridOptions: VxeGridProps<AiCallLogVO> = {
  id: "admin-ai-call-log",
  columns: [
    { type: "seq", title: "#", width: 50 },
    { field: "scene", title: "场景", width: 130, slots: { default: "scene", header: "sceneHeader" } },
    { field: "subject", title: "判定对象", minWidth: 200, showOverflow: "tooltip" },
    { field: "model", title: "模型", width: 140 },
    { field: "success", title: "结果", width: 90, slots: { default: "success" } },
    { field: "httpStatus", title: "HTTP状态", width: 100 },
    { field: "totalTokens", title: "token", width: 110, slots: { default: "tokens" } },
    { field: "durationMs", title: "耗时(ms)", width: 100 },
    // 全文展示，不做单行截断：showOverflow=false 才能换行并撑开行高（全局默认是 true）
    {
      field: "responseBody",
      title: "模型输出",
      minWidth: 360,
      showOverflow: false,
      slots: { default: "output" },
    },
    { field: "errorMsg", title: "错误信息", minWidth: 180, slots: { default: "errorMsg" } },
    {
      field: "createTime",
      title: "调用时间",
      width: 180,
      formatter: ({ cellValue }) => formatDateTime(cellValue),
    },
  ],
  toolbarConfig: { custom: true, refresh: true },
  // 每行都带着请求/响应原文（各自最多 8000 字符），页大小取小一些，避免一页拉回几 MB
  pagerConfig: { pageSize: 20 },
  proxyConfig: {
    ajax: {
      query: async ({ page }) => {
        const res = await getAdminAiCallLogListApi({
          subject: searchForm.subject || undefined,
          scene: searchForm.scene,
          success: searchForm.success,
          currentPage: page.currentPage,
          pageSize: page.pageSize,
        });
        return { items: res.records, total: res.total };
      },
    },
  },
};

/**
 * 列表里展示模型输出的完整正文。
 * 解析不出正文（失败/纯文本错误页/结构异常）时退回原始响应体，保证任何情况下都能看到 AI 返回了什么。
 */
function outputOf(row: AiCallLogVO): string {
  const raw = row.responseBody ?? "";
  if (!raw) return "";
  try {
    return JSON.parse(raw)?.choices?.[0]?.message?.content ?? raw;
  } catch {
    return raw;
  }
}

// 行点击必须走 gridEvents：Grid 包装组件的根节点是个 div，模板上写 @cell-click 只会
// 作为原生监听落到那个 div 上（DOM 没有 cell-click 事件），内层 VxeGrid 收不到
const [Grid, gridApi] = useVbenVxeGrid({
  gridOptions,
  gridEvents: { cellClick: handleCellClick },
});

const { reset } = useAutoSearch(searchForm, () => gridApi.reload());
</script>

<template>
  <Page auto-content-height>
    <ElCard shadow="never">
      <template #header>
        <div class="flex items-center justify-between">
          <span>AI 检测管理</span>
          <span class="text-xs text-gray-400">记录与第三方 AI(DeepSeek) 的全部通讯，点击任意行查看完整 prompt 与响应</span>
        </div>
      </template>
      <FilterBar class="mb-4" @reset="reset">
        <FilterItem label="判定对象" width="240px">
          <ElInput v-model="searchForm.subject" placeholder="域名 / 标题" clearable />
        </FilterItem>
        <FilterItem label="场景">
          <ElSelect v-model="searchForm.scene" placeholder="全部" clearable>
            <ElOption
              v-for="[value, label] in Object.entries(AI_SCENE_LABEL)"
              :key="value"
              :label="label"
              :value="value"
            />
          </ElSelect>
        </FilterItem>
        <FilterItem label="状态" width="120px">
          <ElSelect v-model="searchForm.success" placeholder="全部" clearable>
            <ElOption label="成功" :value="true" />
            <ElOption label="失败" :value="false" />
          </ElSelect>
        </FilterItem>
      </FilterBar>
      <Grid>
        <template #sceneHeader="{ column }">
          <ElTooltip placement="top">
            <template #content>
              <div class="max-w-md space-y-1 text-xs leading-relaxed">
                <div class="font-medium">调用场景(这次通讯是为哪件业务发起的)</div>
                <div v-for="[key, desc] in AI_SCENE_LEGEND" :key="key">
                  <span class="font-medium">{{ AI_SCENE_LABEL[key] }}</span>
                  ：{{ desc }}
                </div>
              </div>
            </template>
            <span class="cursor-help underline decoration-dotted underline-offset-4">
              {{ column.title }}
            </span>
          </ElTooltip>
        </template>
        <template #scene="{ row }">
          {{ AI_SCENE_LABEL[row.scene] ?? row.scene }}
        </template>
        <template #success="{ row }">
          <ElTag v-if="row.success" type="success" size="small"> 成功 </ElTag>
          <ElTag v-else type="danger" size="small"> 失败 </ElTag>
        </template>
        <template #tokens="{ row }">
          <ElTooltip
            v-if="row.totalTokens"
            :content="`输入 ${row.promptTokens ?? '-'} / 输出 ${row.completionTokens ?? '-'}`"
            placement="top"
          >
            <span class="cursor-help">{{ row.totalTokens }}</span>
          </ElTooltip>
          <span v-else>-</span>
        </template>
        <template #output="{ row }">
          <!-- 全文展示；超长响应在单元格内滚动，避免一行把整个表格撑爆 -->
          <div
            v-if="outputOf(row)"
            class="max-h-40 overflow-auto py-1 text-xs leading-relaxed break-words whitespace-pre-wrap"
          >
            {{ outputOf(row) }}
          </div>
          <span v-else>-</span>
        </template>
        <template #errorMsg="{ row }">
          <ElTooltip v-if="row.errorMsg" :content="row.errorMsg" placement="top">
            <span class="line-clamp-1">{{ row.errorMsg }}</span>
          </ElTooltip>
          <span v-else>-</span>
        </template>
      </Grid>
    </ElCard>

    <AiCallDetailDialog v-model="detailVisible" :row="detailRow" />
  </Page>
</template>
