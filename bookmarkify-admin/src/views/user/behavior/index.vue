<script lang="ts" setup>
import type { UserBehaviorLogVO, UserBehaviorType } from "#/api/user-behavior-log";

import { reactive, ref } from "vue";

import { Page } from "@vben/common-ui";
import { formatDateTime } from "@vben/utils";

import {
  ElCard,
  ElDatePicker,
  ElInput,
  ElOption,
  ElSelect,
  ElTag,
  ElTooltip,
} from "#/adapter/element";
import { useVbenVxeGrid, type VxeGridProps } from "#/adapter/vxe-table";
import { FilterBar, FilterItem, useAutoSearch } from "#/components/filter-bar";
import {
  getAdminUserBehaviorLogListApi,
  USER_BEHAVIOR_TYPE_DESC,
} from "#/api/user-behavior-log";
import UserDetailDialog from "#/views/user/UserDetailDialog.vue";
import UserIdentityCell from "#/views/user/UserIdentityCell.vue";

interface SearchForm {
  keyword: string;
  behaviorType?: UserBehaviorType;
  createRange?: [string, string];
}

const searchForm = reactive<SearchForm>({
  keyword: "",
  behaviorType: undefined,
  createRange: undefined,
});

function behaviorMetaOf(row: UserBehaviorLogVO) {
  return USER_BEHAVIOR_TYPE_DESC[row.behaviorType];
}

const userVisible = ref(false);
const currentUser = ref<UserBehaviorLogVO["user"]>(null);

function handleUserClick(row: UserBehaviorLogVO) {
  if (!row.user) return;
  currentUser.value = row.user;
  userVisible.value = true;
}

const gridOptions: VxeGridProps<UserBehaviorLogVO> = {
  id: "admin-user-behavior-log",
  columns: [
    { type: "seq", title: "#", width: 50 },
    { field: "user", title: "用户", minWidth: 180, slots: { default: "user" } },
    { field: "behaviorType", title: "行为类型", width: 140, slots: { default: "behaviorType" } },
    { field: "detail", title: "详情", minWidth: 260, showOverflow: "tooltip" },
    {
      field: "createTime",
      title: "发生时间",
      width: 200,
      formatter: ({ cellValue }) => formatDateTime(cellValue),
    },
  ],
  toolbarConfig: { custom: true, refresh: true },
  pagerConfig: { pageSize: 50 },
  proxyConfig: {
    ajax: {
      query: async ({ page }) => {
        const res = await getAdminUserBehaviorLogListApi({
          keyword: searchForm.keyword || undefined,
          behaviorType: searchForm.behaviorType,
          createTimeFrom: searchForm.createRange?.[0],
          createTimeTo: searchForm.createRange?.[1],
          currentPage: page.currentPage,
          pageSize: page.pageSize,
        });
        return { items: res.records, total: res.total };
      },
    },
  },
};

const [Grid, gridApi] = useVbenVxeGrid({ gridOptions });

const { reset } = useAutoSearch(searchForm, () => gridApi.reload(), {
  initial: { keyword: "", behaviorType: undefined, createRange: undefined },
});
</script>

<template>
  <Page auto-content-height>
    <ElCard shadow="never">
      <template #header>
        <div class="flex items-center justify-between">
          <span>用户行为管理</span>
        </div>
      </template>
      <FilterBar class="mb-4" @reset="reset">
        <FilterItem label="关键字" width="220px">
          <ElInput v-model="searchForm.keyword" placeholder="昵称 / uid" clearable />
        </FilterItem>
        <FilterItem label="行为类型" width="160px">
          <ElSelect v-model="searchForm.behaviorType" placeholder="全部" clearable>
            <ElOption
              v-for="[value, meta] in Object.entries(USER_BEHAVIOR_TYPE_DESC)"
              :key="value"
              :label="meta.label"
              :value="value"
            />
          </ElSelect>
        </FilterItem>
        <FilterItem label="发生时间" width="360px">
          <ElDatePicker
            v-model="searchForm.createRange"
            type="datetimerange"
            value-format="YYYY-MM-DDTHH:mm:ss"
            :default-time="[new Date(2000, 0, 1, 0, 0, 0), new Date(2000, 0, 1, 23, 59, 59)]"
            start-placeholder="开始"
            end-placeholder="结束"
          />
        </FilterItem>
      </FilterBar>
      <Grid>
        <template #user="{ row }">
          <div class="user-cell" @click.stop="handleUserClick(row)">
            <UserIdentityCell :user="row.user" />
          </div>
        </template>
        <template #behaviorType="{ row }">
          <ElTooltip v-if="behaviorMetaOf(row)" :content="behaviorMetaOf(row)!.desc" placement="top">
            <ElTag :type="behaviorMetaOf(row)!.type" size="small">
              {{ behaviorMetaOf(row)!.label }}
            </ElTag>
          </ElTooltip>
          <!-- 认不出的取值原样显示，别让新增的行为类型在后台变成一个空格 -->
          <span v-else>{{ row.behaviorType }}</span>
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
