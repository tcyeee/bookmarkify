<script lang="ts" setup>
import type { ShareSearchParams, UserShareAdminVO } from "#/api/share";

import { defineAsyncComponent, reactive, ref } from "vue";

import { Page } from "@vben/common-ui";
import { formatDateTime } from "@vben/utils";

import { ElMessage, ElMessageBox } from "element-plus";

import { getAdminShareListApi, takeDownShareApi } from "#/api/share";
import { useVbenVxeGrid, type VxeGridProps } from "#/adapter/vxe-table";
import { FilterBar, FilterItem, useAutoSearch } from "#/components/filter-bar";
import UserQuerySelect from "#/components/user/UserQuerySelect.vue";
import UserDetailDialog from "#/views/user/UserDetailDialog.vue";
import UserIdentityCell from "#/views/user/UserIdentityCell.vue";

import ShareDetailDialog from "./ShareDetailDialog.vue";
import { SHARE_STATUS_META, shareStatusMeta } from "./shareStatus";

const ElCard = defineAsyncComponent(() =>
  Promise.all([
    import("element-plus/es/components/card/index"),
    import("element-plus/es/components/card/style/css"),
  ]).then(([res]) => res.ElCard),
);
const ElSelect = defineAsyncComponent(() =>
  Promise.all([
    import("element-plus/es/components/select/index"),
    import("element-plus/es/components/select/style/css"),
  ]).then(([res]) => res.ElSelect),
);
const ElOption = defineAsyncComponent(() =>
  Promise.all([
    import("element-plus/es/components/select/index"),
    import("element-plus/es/components/select/style/css"),
  ]).then(([res]) => res.ElOption),
);
const ElButton = defineAsyncComponent(() =>
  Promise.all([
    import("element-plus/es/components/button/index"),
    import("element-plus/es/components/button/style/css"),
  ]).then(([res]) => res.ElButton),
);
const ElTag = defineAsyncComponent(() =>
  Promise.all([
    import("element-plus/es/components/tag/index"),
    import("element-plus/es/components/tag/style/css"),
  ]).then(([res]) => res.ElTag),
);

const searchForm = reactive<Pick<ShareSearchParams, "status" | "uid">>({
  uid: "",
  status: "",
});

const gridOptions: VxeGridProps<UserShareAdminVO> = {
  id: "admin-share-all",
  columns: [
    { type: "seq", title: "#", width: 50 },
    { field: "nickName", title: "分享人", minWidth: 180, slots: { default: "user" } },
    { field: "note", title: "文案", minWidth: 220 },
    { field: "bookmarkCount", title: "书签数", width: 90 },
    { field: "status", title: "状态", width: 120, slots: { default: "status" } },
    {
      field: "rejectReason",
      title: "提示信息",
      minWidth: 200,
      showOverflow: "tooltip",
      formatter: ({ cellValue }) => cellValue || "-",
    },
    {
      field: "expireTime",
      title: "过期时间",
      width: 180,
      formatter: ({ cellValue }) => (cellValue ? formatDateTime(cellValue) : "永不过期"),
    },
    {
      field: "createTime",
      title: "创建时间",
      width: 180,
      formatter: ({ cellValue }) => formatDateTime(cellValue),
    },
    // 操作列必须有 field：行点击要靠它把这一列排除在「打开详情」之外
    { field: "rowActions", title: "操作", width: 120, slots: { default: "actions" } },
  ],
  toolbarConfig: { custom: true, refresh: true },
  pagerConfig: {},
  proxyConfig: {
    ajax: {
      query: async ({ page }) => {
        const res = await getAdminShareListApi({
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

// ── 详情弹窗 ────────────────────────────────────────────────────────────────
const detailVisible = ref(false);
const detailRow = ref<null | UserShareAdminVO>(null);
const userVisible = ref(false);
const currentUser = ref<null | UserShareAdminVO["user"]>(null);

function handleUserClick(row: UserShareAdminVO) {
  if (!row.user) return;
  currentUser.value = row.user;
  userVisible.value = true;
}

function handleCellClick({ row, column }: { column: any; row: UserShareAdminVO }) {
  if (column?.field === "rowActions") return;
  detailRow.value = row;
  detailVisible.value = true;
}

// 行点击必须走 gridEvents：Grid 包装组件的根节点是个 div，模板上写 @cell-click 只会
// 作为原生监听落到那个 div 上（DOM 没有 cell-click 事件），内层 VxeGrid 收不到
const [Grid, gridApi] = useVbenVxeGrid({
  gridOptions,
  gridEvents: { cellClick: handleCellClick },
});

const { reset } = useAutoSearch(searchForm, () => gridApi.reload());

async function handleTakeDown(row: UserShareAdminVO) {
  try {
    await ElMessageBox.confirm(
      `确认强制下架「${row.nickName}」的这条分享吗？下架后该分享链接将无法再被访问。`,
      "提示",
      { type: "warning" },
    );
  } catch {
    return;
  }
  await takeDownShareApi(row.id);
  ElMessage.success("已强制下架");
  // 下架后弹窗里那份状态就过期了，直接关掉；重开一次拿到的是新状态
  detailVisible.value = false;
  await gridApi.query();
}
</script>

<template>
  <Page auto-content-height>
    <ElCard shadow="never">
      <template #header>
        <div class="flex items-center justify-between">
          <span>用户自定义书签集</span>
        </div>
      </template>
      <FilterBar class="mb-4" @reset="reset">
        <FilterItem label="分享人" width="280px">
          <UserQuerySelect
            v-model="searchForm.uid"
            placeholder="输入分享人邮箱或昵称查询"
          />
        </FilterItem>
        <FilterItem label="状态">
          <ElSelect v-model="searchForm.status" placeholder="全部状态" clearable>
            <ElOption
              v-for="[value, meta] in Object.entries(SHARE_STATUS_META)"
              :key="value"
              :label="meta.label"
              :value="value" />
          </ElSelect>
        </FilterItem>
      </FilterBar>
      <Grid class="clickable-rows">
        <template #user="{ row }">
          <div class="user-cell" @click.stop="handleUserClick(row)">
            <UserIdentityCell :user="row.user" />
          </div>
        </template>
        <template #status="{ row }">
          <ElTag :type="shareStatusMeta(row.status).type" size="small">
            {{ shareStatusMeta(row.status).label }}
          </ElTag>
        </template>
        <template #actions="{ row }">
          <ElButton
            link
            type="danger"
            :disabled="row.status === 'ADMIN_TAKEDOWN'"
            @click="handleTakeDown(row)">
            强制下架
          </ElButton>
        </template>
      </Grid>
    </ElCard>

    <ShareDetailDialog v-model="detailVisible" :row="detailRow" @takedown="handleTakeDown" />
    <UserDetailDialog v-model="userVisible" :user="currentUser" />
  </Page>
</template>

<style scoped>
/* 整行可点开详情，光标要说明这件事 —— 否则唯一能发现它的方法是乱点 */
.clickable-rows :deep(.vxe-body--row) {
  cursor: pointer;
}

.user-cell {
  min-width: 0;
  cursor: pointer;
}

.user-cell:hover {
  color: var(--el-color-primary);
}
</style>
