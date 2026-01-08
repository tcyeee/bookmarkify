<script lang="ts" setup>
import type { VxeGridProps } from "#/adapter/vxe-table";

import { defineAsyncComponent } from "vue";

import { Page } from "@vben/common-ui";

import { useVbenVxeGrid } from "#/adapter/vxe-table";
import { getAdminUserListApi } from "#/api";

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

const gridOptions: VxeGridProps = {
  columns: [
    { type: "seq", width: 50 },
    { field: "nickName", title: "昵称", minWidth: 160 },
    { field: "deviceId", title: "设备UID", minWidth: 220 },
    { field: "email", title: "邮箱", minWidth: 200 },
    { field: "phone", title: "手机号", minWidth: 160 },
    { field: "role", title: "角色", width: 120 },
    {
      field: "verified",
      title: "已验证",
      width: 100,
      slots: { default: "verified" },
    },
    {
      field: "disabled",
      title: "禁用",
      width: 100,
      slots: { default: "disabled" },
    },
    { field: "createTime", title: "创建时间", width: 200 },
    { field: "updateTime", title: "更新时间", width: 200 },
  ],
  proxyConfig: {
    ajax: {
      query: async ({ page }, form) => {
        const res = await getAdminUserListApi({
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
          placeholder: "昵称 / 邮箱 / 手机号",
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
          <span>全部用户</span>
        </div>
      </template>
      <Grid>
        <template #verified="{ row }">
          <ElTag v-if="row.verified" type="success" size="small"> 已验证 </ElTag>
          <ElTag v-else type="info" size="small"> 未验证 </ElTag>
        </template>
        <template #disabled="{ row }">
          <ElTag v-if="row.disabled" type="danger" size="small"> 状态 </ElTag>
          <ElTag v-else type="success" size="small"> 正常 </ElTag>
        </template>
      </Grid>
    </ElCard>
  </Page>
</template>
