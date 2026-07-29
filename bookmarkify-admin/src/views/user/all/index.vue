<script lang="ts" setup>
import type { UserAdminVO, UserSearchParams, UserStatus } from "#/api/user-manage";

import { defineAsyncComponent, reactive } from "vue";

import { Page } from "@vben/common-ui";
import { formatDateTime } from "@vben/utils";

import { getAdminUserListApi } from "#/api";
import { useVbenVxeGrid, type VxeGridProps } from "#/adapter/vxe-table";

const ElCard = defineAsyncComponent(() =>
  Promise.all([
    import("element-plus/es/components/card/index"),
    import("element-plus/es/components/card/style/css"),
  ]).then(([res]) => res.ElCard)
);

const ElForm = defineAsyncComponent(() =>
  Promise.all([
    import("element-plus/es/components/form/index"),
    import("element-plus/es/components/form/style/css"),
  ]).then(([res]) => res.ElForm)
);

const ElFormItem = defineAsyncComponent(() =>
  Promise.all([
    import("element-plus/es/components/form/index"),
    import("element-plus/es/components/form/style/css"),
  ]).then(([res]) => res.ElFormItem)
);

const ElInput = defineAsyncComponent(() =>
  Promise.all([
    import("element-plus/es/components/input/index"),
    import("element-plus/es/components/input/style/css"),
  ]).then(([res]) => res.ElInput)
);

const ElButton = defineAsyncComponent(() =>
  Promise.all([
    import("element-plus/es/components/button/index"),
    import("element-plus/es/components/button/style/css"),
  ]).then(([res]) => res.ElButton)
);

const ElTag = defineAsyncComponent(() =>
  Promise.all([
    import("element-plus/es/components/tag/index"),
    import("element-plus/es/components/tag/style/css"),
  ]).then(([res]) => res.ElTag)
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

const searchForm = reactive<Pick<UserSearchParams, "name" | "status">>({
  name: "",
  status: undefined,
});

/** deleted / disabled 是两个独立标记，这里收敛成一个互斥状态用于展示 */
function resolveStatus(row: UserAdminVO): UserStatus {
  if (row.deleted) return "DELETED";
  return row.disabled ? "DISABLED" : "NORMAL";
}

const gridOptions: VxeGridProps<UserAdminVO> = {
  id: "admin-user-all",
  columns: [
    { type: "seq", title: "#", width: 50 },
    { field: "nickName", title: "昵称", minWidth: 160 },
    { field: "deviceId", title: "设备UID", minWidth: 220 },
    { field: "email", title: "邮箱", minWidth: 200 },
    { field: "phone", title: "手机号", minWidth: 160 },
    { field: "role", title: "角色", width: 140, slots: { default: "role" } },
    { field: "verified", title: "已验证", width: 100, slots: { default: "verified" } },
    { field: "status", title: "状态", width: 100, slots: { default: "status" } },
    {
      field: "createTime",
      title: "创建时间",
      width: 200,
      formatter: ({ cellValue }) => formatDateTime(cellValue),
    },
    {
      field: "updateTime",
      title: "更新时间",
      width: 200,
      formatter: ({ cellValue }) => formatDateTime(cellValue),
    },
  ],
  toolbarConfig: { custom: true, refresh: true },
  pagerConfig: {},
  proxyConfig: {
    ajax: {
      query: async ({ page }) => {
        const res = await getAdminUserListApi({
          name: searchForm.name || undefined,
          status: searchForm.status || undefined,
          currentPage: page.currentPage,
          pageSize: page.pageSize,
        });
        return { items: res.records, total: res.total };
      },
    },
  },
};

const [Grid, gridApi] = useVbenVxeGrid({ gridOptions });

function handleSearch() {
  gridApi.reload();
}

function handleReset() {
  searchForm.name = "";
  searchForm.status = undefined;
  gridApi.reload();
}
</script>

<template>
  <Page auto-content-height>
    <ElCard shadow="never">
      <template #header>
        <div class="flex items-center justify-between">
          <span>全部用户</span>
        </div>
      </template>
      <div class="mb-4">
        <ElForm :inline="true" :model="searchForm">
          <ElFormItem label="搜索">
            <ElInput v-model="searchForm.name" placeholder="昵称 / 邮箱 / 手机号" clearable />
          </ElFormItem>
          <ElFormItem label="状态">
            <ElSelect v-model="searchForm.status" placeholder="全部" clearable style="width: 120px">
              <ElOption label="正常" value="NORMAL" />
              <ElOption label="禁用" value="DISABLED" />
              <ElOption label="已删除" value="DELETED" />
            </ElSelect>
          </ElFormItem>
          <ElFormItem>
            <ElButton type="primary" @click="handleSearch">搜索</ElButton>
            <ElButton class="ml-2" @click="handleReset">重置</ElButton>
          </ElFormItem>
        </ElForm>
      </div>
      <Grid>
        <template #role="{ row }">
          <ElTag v-if="row.role === 'ADMIN'" type="danger" size="small">
            管理员
          </ElTag>
          <ElTag v-else-if="row.role === 'MODERATOR'" type="warning" size="small">
            协管
          </ElTag>
          <ElTag v-else-if="row.role === 'USER'" type="success" size="small">
            普通用户
          </ElTag>
          <ElTag v-else type="info" size="small">
            {{ row.role || "未知" }}
          </ElTag>
        </template>
        <template #verified="{ row }">
          <ElTag v-if="row.verified" type="success" size="small"> 已验证 </ElTag>
          <ElTag v-else type="info" size="small"> 未验证 </ElTag>
        </template>
        <template #status="{ row }">
          <ElTag v-if="resolveStatus(row) === 'DELETED'" type="info" size="small">
            已删除
          </ElTag>
          <ElTag v-else-if="resolveStatus(row) === 'DISABLED'" type="danger" size="small">
            禁用
          </ElTag>
          <ElTag v-else type="success" size="small"> 正常 </ElTag>
        </template>
      </Grid>
    </ElCard>
  </Page>
</template>
