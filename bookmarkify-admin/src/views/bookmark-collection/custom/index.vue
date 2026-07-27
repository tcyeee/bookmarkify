<script lang="ts" setup>
import type { ShareSearchParams, UserShareAdminVO } from "#/api/share";

import { defineAsyncComponent, reactive } from "vue";

import { Page } from "@vben/common-ui";
import { formatDateTime } from "@vben/utils";

import { ElMessage, ElMessageBox } from "element-plus";

import { getAdminShareListApi, takeDownShareApi } from "#/api/share";
import { useVbenVxeGrid, type VxeGridProps } from "#/adapter/vxe-table";

const ElCard = defineAsyncComponent(() =>
  Promise.all([
    import("element-plus/es/components/card/index"),
    import("element-plus/es/components/card/style/css"),
  ]).then(([res]) => res.ElCard),
);
const ElForm = defineAsyncComponent(() =>
  Promise.all([
    import("element-plus/es/components/form/index"),
    import("element-plus/es/components/form/style/css"),
  ]).then(([res]) => res.ElForm),
);
const ElFormItem = defineAsyncComponent(() =>
  Promise.all([
    import("element-plus/es/components/form/index"),
    import("element-plus/es/components/form/style/css"),
  ]).then(([res]) => res.ElFormItem),
);
const ElInput = defineAsyncComponent(() =>
  Promise.all([
    import("element-plus/es/components/input/index"),
    import("element-plus/es/components/input/style/css"),
  ]).then(([res]) => res.ElInput),
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
    { field: "nickName", title: "分享人", minWidth: 140 },
    { field: "note", title: "文案", minWidth: 220 },
    { field: "bookmarkCount", title: "书签数", width: 90, align: "center" },
    { field: "status", title: "状态", width: 120, slots: { default: "status" } },
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
    { title: "操作", width: 120, align: "center", slots: { default: "actions" } },
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

const [Grid, gridApi] = useVbenVxeGrid({ gridOptions });

function handleSearch() {
  gridApi.reload();
}
function handleReset() {
  searchForm.uid = "";
  searchForm.status = "";
  gridApi.reload();
}

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
      <div class="mb-4">
        <ElForm :inline="true" :model="searchForm">
          <ElFormItem label="用户ID">
            <ElInput v-model="searchForm.uid" placeholder="分享人用户ID" clearable />
          </ElFormItem>
          <ElFormItem label="状态">
            <ElSelect v-model="searchForm.status" placeholder="全部状态" clearable style="width: 160px">
              <ElOption label="正常" value="NORMAL" />
              <ElOption label="到期下架" value="EXPIRED" />
              <ElOption label="管理员下架" value="ADMIN_TAKEDOWN" />
              <ElOption label="未通过审核" value="REVIEW_REJECTED" />
            </ElSelect>
          </ElFormItem>
          <ElFormItem>
            <ElButton type="primary" @click="handleSearch">搜索</ElButton>
            <ElButton class="ml-2" @click="handleReset">重置</ElButton>
          </ElFormItem>
        </ElForm>
      </div>
      <Grid>
        <template #status="{ row }">
          <ElTag v-if="row.status === 'NORMAL'" type="success" size="small">正常</ElTag>
          <ElTag v-else-if="row.status === 'EXPIRED'" type="info" size="small">到期下架</ElTag>
          <ElTag v-else-if="row.status === 'ADMIN_TAKEDOWN'" type="danger" size="small">管理员下架</ElTag>
          <template v-else-if="row.status === 'REVIEW_REJECTED'">
            <ElTag type="warning" size="small" :title="row.rejectReason || undefined">未通过审核</ElTag>
            <div v-if="row.rejectReason" class="mt-1 text-xs text-gray-400">{{ row.rejectReason }}</div>
          </template>
          <ElTag v-else type="info" size="small">{{ row.status || "未知" }}</ElTag>
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
  </Page>
</template>
