<script lang="ts" setup>
import type { UserAdminVO, UserSearchParams, UserStatus } from "#/api/user-manage";

import { defineAsyncComponent, reactive, ref } from "vue";

import { Page } from "@vben/common-ui";
import { formatDateTime } from "@vben/utils";

import { getAdminUserListApi } from "#/api";
import { useVbenVxeGrid, type VxeGridProps } from "#/adapter/vxe-table";
import { FilterBar, FilterItem, useAutoSearch } from "#/components/filter-bar";
import UserQuerySelect from "#/components/user/UserQuerySelect.vue";
import UserDetailDialog from "#/views/user/UserDetailDialog.vue";
import UserIdentityCell from "#/views/user/UserIdentityCell.vue";

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

const searchForm = reactive<Pick<UserSearchParams, "status" | "uid">>({
  uid: "",
  status: undefined,
});

/** deleted / disabled 是两个独立标记，这里收敛成一个互斥状态用于展示 */
function resolveStatus(row: UserAdminVO): UserStatus {
  if (row.deleted) return "DELETED";
  return row.disabled ? "DISABLED" : "NORMAL";
}

const userVisible = ref(false);
const currentUser = ref<null | UserAdminVO>(null);

function handleUserClick(row: UserAdminVO) {
  currentUser.value = row;
  userVisible.value = true;
}

const gridOptions: VxeGridProps<UserAdminVO> = {
  id: "admin-user-all",
  columns: [
    { type: "seq", title: "#", width: 50 },
    { field: "nickName", title: "用户", minWidth: 180, slots: { default: "user" } },
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
          uid: searchForm.uid || undefined,
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

const { reset } = useAutoSearch(searchForm, () => gridApi.reload());
</script>

<template>
  <Page auto-content-height>
    <ElCard shadow="never">
      <template #header>
        <div class="flex items-center justify-between">
          <span>全部用户</span>
        </div>
      </template>
      <FilterBar class="mb-4" @reset="reset">
        <FilterItem label="用户" width="280px">
          <UserQuerySelect v-model="searchForm.uid" />
        </FilterItem>
        <FilterItem label="状态" width="120px">
          <ElSelect v-model="searchForm.status" placeholder="全部" clearable>
            <ElOption label="正常" value="NORMAL" />
            <ElOption label="禁用" value="DISABLED" />
            <ElOption label="已删除" value="DELETED" />
          </ElSelect>
        </FilterItem>
      </FilterBar>
      <Grid>
        <template #user="{ row }">
          <div class="user-cell" @click.stop="handleUserClick(row)">
            <UserIdentityCell :user="row" />
          </div>
        </template>
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

      <UserDetailDialog v-model="userVisible" :user="currentUser" />
    </ElCard>
  </Page>
</template>

<style scoped>
.user-cell {
  min-width: 0;
  cursor: pointer;
}

.user-cell:hover {
  color: var(--el-color-primary);
}
</style>
