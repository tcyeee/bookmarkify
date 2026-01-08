<script lang="ts" setup>
import type { VxeGridProps } from "#/adapter/vxe-table";

import { Page } from "@vben/common-ui";

import { useVbenVxeGrid } from "#/adapter/vxe-table";
import { getBookmarkListApi } from "#/api/bookmark";

const gridOptions: VxeGridProps = {
  columns: [
    { type: "seq", width: 50 },
    { field: "appName", title: "App Name", minWidth: 100 },
    { field: "title", title: "标题", minWidth: 200 },
    { field: "urlHost", title: "域名", minWidth: 150 },
    { field: "parseStatus", title: "状态", width: 100 },
    {
      field: "isActivity",
      title: "活跃",
      width: 80,
      slots: { default: "activity" },
    },
    { field: "updateTime", title: "更新时间", width: 180 },
  ],
  proxyConfig: {
    ajax: {
      query: async ({ page, form }) => {
        const res = await getBookmarkListApi({
          currentPage: page.currentPage,
          pageSize: page.pageSize,
          ...form,
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
        },
      },
    ],
  },
  gridOptions,
});
</script>

<template>
  <Page title="书签管理">
    <Grid>
      <template #activity="{ row }">
        <span v-if="row.isActivity" class="text-green-500">是</span>
        <span v-else class="text-red-500">否</span>
      </template>
    </Grid>
  </Page>
</template>
