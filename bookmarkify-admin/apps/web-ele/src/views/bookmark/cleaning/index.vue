<script lang="ts" setup>
import type { VxeGridProps } from "#/adapter/vxe-table";

import { defineAsyncComponent } from "vue";

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

const gridOptions: VxeGridProps = {
  columns: [
    { type: "seq", width: 50 },
    { field: "appName", title: "App Name", minWidth: 120 },
    { field: "title", title: "标题", minWidth: 220 },
    { field: "urlHost", title: "域名", minWidth: 180 },
    {
      field: "parseStatus",
      title: "状态",
      width: 140,
      slots: { default: "parseStatus" },
    },
    {
      field: "isActivity",
      title: "活跃",
      width: 120,
      slots: { default: "activity" },
    },
    {
      field: "updateTime",
      title: "更新时间",
      width: 200,
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
    </ElCard>
  </Page>
</template>
