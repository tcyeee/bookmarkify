<script lang="ts" setup>
import type { VxeGridProps } from "#/adapter/vxe-table";
import type { BookmarkEntity } from "#/api/bookmark";

import { defineAsyncComponent, ref } from "vue";

import { Page } from "@vben/common-ui";
import { formatDateTime } from "@vben/utils";

import { useVbenVxeGrid } from "#/adapter/vxe-table";
import { getBookmarkListApi } from "#/api/bookmark";

const ElCard = defineAsyncComponent(() =>
  Promise.all([
    import("element-plus/es/components/card/index"),
    import("element-plus/es/components/card/style/css"),
  ]).then(([res]) => res.ElCard)
);

const ElTag = defineAsyncComponent(() =>
  Promise.all([
    import("element-plus/es/components/tag/index"),
    import("element-plus/es/components/tag/style/css"),
  ]).then(([res]) => res.ElTag)
);

const ElSwitch = defineAsyncComponent(() =>
  Promise.all([
    import("element-plus/es/components/switch/index"),
    import("element-plus/es/components/switch/style/css"),
  ]).then(([res]) => res.ElSwitch)
);

const ElDialog = defineAsyncComponent(() =>
  Promise.all([
    import("element-plus/es/components/dialog/index"),
    import("element-plus/es/components/dialog/style/css"),
  ]).then(([res]) => res.ElDialog)
);

const gridOptions: VxeGridProps = {
  border: true,
  columns: [
    { type: "seq", width: 50 },
    { field: "appName", title: "App Name", minWidth: 120, align: "left" },
    { field: "title", title: "标题", minWidth: 220, align: "left" },
    { field: "urlHost", title: "域名", minWidth: 180, align: "left" },
    {
      field: "parseStatus",
      title: "状态",
      width: 140,
      align: "left",
      slots: { default: "parseStatus" },
    },
    {
      field: "isActivity",
      title: "活跃",
      width: 120,
      align: "left",
      slots: { default: "activity" },
    },
    {
      field: "updateTime",
      title: "更新时间",
      width: 200,
      align: "left",
      formatter: ({ cellValue }) => formatDateTime(cellValue),
    },
  ],
  proxyConfig: {
    ajax: {
      query: async ({ page }, form) => {
        const res = await getBookmarkListApi({
          currentPage: page.currentPage,
          pageSize: page.pageSize,
          ...(form || {}),
        });
        return {
          items: res.records,
          total: res.total,
        };
      },
    },
  },
};

const detailVisible = ref(false);
const currentRow = ref<BookmarkEntity | null>(null);

function handleCellClick({ row }: { row: BookmarkEntity }) {
  currentRow.value = row;
  detailVisible.value = true;
}

const [Grid] = useVbenVxeGrid({
  formOptions: {
    schema: [
      {
        fieldName: "name",
        label: "搜索",
        component: "Input",
        componentProps: {
          placeholder: "名称/标题/描述/域名",
          clearable: true,
        },
      },
      {
        fieldName: "status",
        label: "状态",
        component: "Select",
        componentProps: {
          options: [
            { label: "Loading", value: "LOADING" },
            { label: "Success", value: "SUCCESS" },
            { label: "Closed", value: "CLOSED" },
            { label: "Blocked", value: "BLOCKED" },
          ],
          placeholder: "请选择状态",
          clearable: true,
        },
      },
    ],
  },
  gridOptions,
  gridEvents: {
    cellClick: handleCellClick,
  },
} as any);
</script>

<template>
  <Page auto-content-height>
    <ElCard shadow="never">
      <template #header>
        <div class="flex items-center justify-between">
          <span>书签清理列表</span>
        </div>
      </template>
      <Grid>
        <template #parseStatus="{ row }">
          <ElTag v-if="row.parseStatus === 'SUCCESS'" type="success" size="small">
            成功
          </ElTag>
          <ElTag v-else-if="row.parseStatus === 'LOADING'" type="info" size="small">
            解析中
          </ElTag>
          <ElTag v-else-if="row.parseStatus === 'CLOSED'" type="warning" size="small">
            已关闭
          </ElTag>
          <ElTag v-else-if="row.parseStatus === 'BLOCKED'" type="danger" size="small">
            已阻止
          </ElTag>
          <ElTag v-else size="small"> 未知 </ElTag>
        </template>
        <template #activity="{ row }">
          <div class="flex items-center gap-2">
            <ElSwitch :model-value="row.isActivity" active-color="#13ce66" inactive-color="#ff4949" disabled />
            <span>{{ row.isActivity ? "活跃" : "不活跃" }}</span>
          </div>
        </template>
      </Grid>
      <ElDialog v-model="detailVisible" title="书签详情" width="600px">
        <div v-if="currentRow" class="space-y-2">
          <div>App Name：{{ currentRow.appName || "-" }}</div>
          <div>标题：{{ currentRow.title || "-" }}</div>
          <div>域名：{{ currentRow.urlHost }}</div>
          <div>路径：{{ currentRow.urlPath || "-" }}</div>
          <div>协议：{{ currentRow.urlScheme }}</div>
          <div>描述：{{ currentRow.description || "-" }}</div>
          <div>状态：{{ currentRow.parseStatus }}</div>
          <div>活跃：{{ currentRow.isActivity ? "活跃" : "不活跃" }}</div>
          <div>错误信息：{{ currentRow.parseErrMsg || "-" }}</div>
          <div>创建时间：{{ currentRow.createTime }}</div>
          <div>更新时间：{{ currentRow.updateTime || "-" }}</div>
        </div>
      </ElDialog>
    </ElCard>
  </Page>
</template>
