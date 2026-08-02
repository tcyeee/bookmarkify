<script lang="ts" setup>
import type { BookmarkEntity, BookmarkSearchParams } from "#/api/bookmark";
import type { UserAdminVO } from "#/api/user-manage";

import { defineAsyncComponent, reactive, ref } from "vue";

import { Page } from "@vben/common-ui";
import { formatDateTime } from "@vben/utils";

import {
  getBookmarkListApi,
  refreshBookmarkApi,
  updateBookmarkBasicInfoApi,
  type BookmarkParseStatus,
} from "#/api/bookmark";
import { ElMessage } from "element-plus";
import { useVbenVxeGrid, type VxeGridProps } from "#/adapter/vxe-table";

import UserDetailDialog from "#/views/user/UserDetailDialog.vue";
import UserIdentityCell from "#/views/user/UserIdentityCell.vue";

import BookmarkAssetCell from "../BookmarkAssetCell.vue";
import BookmarkDetailDialog from "../BookmarkDetailDialog.vue";
import { faviconOf, logoOf, socialOf } from "../siteAsset";

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

const ElDialog = defineAsyncComponent(() =>
  Promise.all([
    import("element-plus/es/components/dialog/index"),
    import("element-plus/es/components/dialog/style/css"),
  ]).then(([res]) => res.ElDialog)
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

const ElSelectV2 = defineAsyncComponent(() =>
  Promise.all([
    import("element-plus/es/components/select-v2/index"),
    import("element-plus/es/components/select-v2/style/css"),
  ]).then(([res]) => res.ElSelectV2)
);

const ElButton = defineAsyncComponent(() =>
  Promise.all([
    import("element-plus/es/components/button/index"),
    import("element-plus/es/components/button/style/css"),
  ]).then(([res]) => res.ElButton)
);

// 详情弹窗直接拿表格行对象：弹窗里的改动就地写回该行，表格无需二次同步
const detailVisible = ref(false);
const currentRow = ref<BookmarkEntity | null>(null);

// 收录者详情：列表接口已带回完整的用户视图，点开不再发请求
const userVisible = ref(false);
const currentUser = ref<null | UserAdminVO>(null);

function handleOwnerClick(row: BookmarkEntity) {
  if (!row.owner) return;
  currentUser.value = row.owner;
  userVisible.value = true;
}

/** 列表里描述只给一眼的量，完整文本靠 title 悬浮和详情弹窗看 */
const DESC_MAX = 15;
function truncate(text: string | undefined, max = DESC_MAX) {
  if (!text) return "";
  return text.length > max ? `${text.slice(0, max)}…` : text;
}

// ── 行内「更新」：重新抓取网站信息并直接覆盖持久化 ──
const refreshingMap = reactive<Record<string, boolean>>({});

async function handleRefresh(row: BookmarkEntity) {
  refreshingMap[row.id] = true;
  try {
    const updated = await refreshBookmarkApi(row.id);
    Object.assign(row, updated);
    ElMessage[updated.isActivity ? "success" : "warning"](
      updated.isActivity
        ? "已重新抓取并更新"
        : `重新抓取失败${updated.parseErrMsg ? `：${updated.parseErrMsg}` : ""}`,
    );
  } catch {
    // 抓取服务不可用(E307)等会走接口错误分支，消息已由请求拦截器弹出，这里只需别留下未捕获的 rejection
  } finally {
    refreshingMap[row.id] = false;
  }
}

// ── 行内「修改」：弹窗编辑标题 / 简介 ──
const editDialogVisible = ref(false);
const editingRow = ref<BookmarkEntity | null>(null);
const editForm = reactive({ title: "", description: "" });
const savingBasicInfo = ref(false);

function handleEdit(row: BookmarkEntity) {
  editingRow.value = row;
  editForm.title = row.title ?? "";
  editForm.description = row.description ?? "";
  editDialogVisible.value = true;
}

async function handleSaveBasicInfo() {
  if (!editingRow.value) return;
  savingBasicInfo.value = true;
  try {
    const updated = await updateBookmarkBasicInfoApi(editingRow.value.id, {
      title: editForm.title,
      description: editForm.description,
    });
    Object.assign(editingRow.value, updated);
    editDialogVisible.value = false;
    ElMessage.success("已保存");
  } finally {
    savingBasicInfo.value = false;
  }
}

const searchForm = reactive<Pick<BookmarkSearchParams, "name" | "status">>({
  name: "",
  status: undefined,
});

const statusOptions: {
  label: string;
  value: BookmarkParseStatus;
  type: "danger" | "info" | "success" | "warning";
}[] = [
  { label: "等待中", value: "PENDING", type: "info" },
  { label: "成功", value: "SUCCESS", type: "success" },
  { label: "抓取失败", value: "UNREACHABLE", type: "danger" },
  { label: "已归档", value: "ARCHIVED", type: "warning" },
];

function handleRowClick({ row, column }: { row: BookmarkEntity; column: any }) {
  // 操作列与收录者列有自己的点击语义，落到这里会把书签详情一起弹出来
  if (column?.field === "rowActions" || column?.field === "owner") return;
  currentRow.value = row;
  detailVisible.value = true;
}

function handleSearch() {
  gridApi.reload();
}

function handleReset() {
  searchForm.name = "";
  searchForm.status = undefined;
  gridApi.reload();
}

const gridOptions: VxeGridProps<BookmarkEntity> = {
  id: "admin-bookmark-cleaning",
  columns: [
    // 头像拆成三类图分别展示：favicon(小图标) / logo(高清 LOGO) / 社交图(宽屏分享图)，缺哪张一眼可见。
    // 三列的数据都取自 row.assets，但 field 必须各不相同：它同时是列自定义(customConfig.storage)
    // 的持久化 key，三列共用一个 key 时 vxe-table 会报 colRepet，且隐藏其中一列会连带隐藏另外两列
    { field: "assetFavicon", title: "favicon", width: 80, slots: { default: "favicon" } },
    { field: "assetLogo", title: "logo", width: 80, slots: { default: "logo" } },
    { field: "assetSocial", title: "社交图", width: 90, slots: { default: "og" } },
    { field: "appName", title: "App Name", minWidth: 120 },
    { field: "title", title: "标题", minWidth: 220 },
    // 只截前 15 个字，完整描述在悬浮 title 与详情弹窗里
    { field: "description", title: "网站描述", minWidth: 160, slots: { default: "description" } },
    { field: "urlHost", title: "域名", minWidth: 180 },
    // 最早把这条书签加进来的用户。头像与昵称同格显示，点开看该用户的完整信息
    { field: "owner", title: "收录用户", minWidth: 160, slots: { default: "owner" } },
    { field: "parseStatus", title: "状态", width: 140, slots: { default: "parseStatus" } },
    { field: "antiCrawlerBlocked", title: "反爬拦截", width: 90, slots: { default: "antiCrawlerBlocked" } },
    { field: "nsfw", title: "NSFW", width: 90, slots: { default: "nsfw" } },
    {
      field: "updateTime",
      title: "更新时间",
      width: 200,
      formatter: ({ cellValue }) => formatDateTime(cellValue),
    },
    {
      field: "rowActions",
      title: "操作",
      width: 140,
      fixed: "right",
      slots: { default: "actions" },
    },
  ],
  toolbarConfig: { custom: true, refresh: true },
  pagerConfig: {},
  proxyConfig: {
    ajax: {
      query: async ({ page }) => {
        const res = await getBookmarkListApi({
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

// 行点击必须走 gridEvents：Grid 包装组件的根节点是个 div，模板上写 @cell-click 只会
// 作为原生监听落到那个 div 上（DOM 没有 cell-click 事件），内层 VxeGrid 收不到
const [Grid, gridApi] = useVbenVxeGrid({
  gridOptions,
  gridEvents: { cellClick: handleRowClick },
});
</script>

<template>
  <Page auto-content-height>
    <ElCard shadow="never">
      <template #header>
        <div class="flex items-center justify-between">
          <span>书签管理</span>
        </div>
      </template>
      <div class="search-bar mb-4">
        <ElForm :model="searchForm" label-position="left" @submit.prevent="handleSearch">
          <div class="flex flex-wrap items-center gap-x-6 gap-y-3">
            <ElFormItem label="搜索" class="!mb-0">
              <ElInput v-model="searchForm.name" placeholder="名称 / 标题 / 描述 / 域名" clearable style="width: 240px" @keyup.enter="handleSearch" />
            </ElFormItem>
            <ElFormItem label="状态" class="!mb-0">
              <ElSelectV2 v-model="searchForm.status" :options="statusOptions" placeholder="全部状态" clearable style="width: 160px">
                <template #default="{ item }">
                  <ElTag :type="item.type" size="small" disable-transitions>
                    {{ item.label }}
                  </ElTag>
                </template>
              </ElSelectV2>
            </ElFormItem>
            <ElFormItem class="!mb-0 ml-auto">
              <ElButton type="primary" @click="handleSearch">搜索</ElButton>
              <ElButton class="ml-2" @click="handleReset">重置</ElButton>
            </ElFormItem>
          </div>
        </ElForm>
      </div>
      <Grid>
        <template #favicon="{ row }">
          <BookmarkAssetCell :src="faviconOf(row)" />
        </template>
        <template #logo="{ row }">
          <BookmarkAssetCell :src="logoOf(row)" />
        </template>
        <template #og="{ row }">
          <BookmarkAssetCell :src="socialOf(row)" wide />
        </template>
        <template #description="{ row }">
          <span v-if="row.description" :title="row.description">
            {{ truncate(row.description) }}
          </span>
          <span v-else class="text-gray-400">-</span>
        </template>
        <template #owner="{ row }">
          <div v-if="row.owner" class="owner-cell" @click.stop="handleOwnerClick(row)">
            <UserIdentityCell :user="row.owner" />
            <!-- 只显示一个头像会让人以为全站就这一个人收藏了它，多人时把总数标出来 -->
            <ElTag
              v-if="row.ownerCount > 1"
              type="info"
              size="small"
              disable-transitions
              :title="`共 ${row.ownerCount} 位用户收录`"
            >
              +{{ row.ownerCount - 1 }}
            </ElTag>
          </div>
          <span v-else class="text-gray-400">-</span>
        </template>
        <template #parseStatus="{ row }">
          <ElTag v-if="row.parseStatus === 'SUCCESS'" type="success" size="small">
            成功
          </ElTag>
          <ElTag v-else-if="row.parseStatus === 'PENDING'" type="info" size="small">
            等待中
          </ElTag>
          <ElTag v-else-if="row.parseStatus === 'UNREACHABLE'" type="danger" size="small">
            抓取失败
          </ElTag>
          <ElTag
            v-else-if="row.parseStatus === 'ARCHIVED'"
            type="warning"
            size="small"
            title="连续失败达到阈值，已停止巡检；手动刷新/检测可恢复"
          >
            已归档
          </ElTag>
          <ElTag v-else size="small"> 未知 </ElTag>
        </template>
        <template #antiCrawlerBlocked="{ row }">
          <ElTag v-if="row.antiCrawlerBlocked" type="warning" size="small">反爬拦截</ElTag>
        </template>
        <template #nsfw="{ row }">
          <ElTag v-if="row.nsfw" type="danger" size="small">NSFW</ElTag>
        </template>
        <template #actions="{ row }">
          <ElButton
            link
            type="primary"
            :loading="refreshingMap[row.id]"
            @click.stop="handleRefresh(row)"
          >
            更新
          </ElButton>
          <ElButton link type="primary" @click.stop="handleEdit(row)">
            修改
          </ElButton>
        </template>
      </Grid>

      <BookmarkDetailDialog v-model="detailVisible" :bookmark="currentRow" />

      <UserDetailDialog v-model="userVisible" :user="currentUser" />

      <ElDialog v-model="editDialogVisible" title="修改基础信息" width="480px">
        <ElForm :model="editForm" label-width="60px">
          <ElFormItem label="标题">
            <ElInput v-model="editForm.title" placeholder="书签标题" />
          </ElFormItem>
          <ElFormItem label="简介">
            <ElInput
              v-model="editForm.description"
              type="textarea"
              :rows="4"
              placeholder="书签简介"
            />
          </ElFormItem>
        </ElForm>
        <template #footer>
          <ElButton @click="editDialogVisible = false">取消</ElButton>
          <ElButton
            type="primary"
            :loading="savingBasicInfo"
            @click="handleSaveBasicInfo"
          >
            保存
          </ElButton>
        </template>
      </ElDialog>
    </ElCard>
  </Page>
</template>

<style scoped>
.owner-cell {
  display: flex;
  gap: 6px;
  align-items: center;
  min-width: 0;
  cursor: pointer;
}

.owner-cell:hover {
  color: var(--el-color-primary);
}

.search-bar :deep(.el-form-item__label) {
  height: 32px;
  font-weight: 400 !important;
  line-height: 32px;
  color: var(--el-text-color-regular);
}
</style>
