<script lang="ts" setup>
import type { UserAdminVO, UserSearchParams } from "#/api/user-manage";

import { defineAsyncComponent, onMounted, reactive, ref } from "vue";

import { Page } from "@vben/common-ui";
import { formatDateTime } from "@vben/utils";

import { getAdminUserListApi } from "#/api";

function createAsyncElComponent(
  importer: () => Promise<any>,
  styleImporter: () => Promise<unknown>,
  exportName: string
) {
  return defineAsyncComponent(() =>
    Promise.all([importer(), styleImporter()]).then(
      ([res]) => (res as any)[exportName]
    )
  );
}

const ElCard = createAsyncElComponent(
  () => import("element-plus/es/components/card/index"),
  () => import("element-plus/es/components/card/style/css"),
  "ElCard"
);

const ElTag = createAsyncElComponent(
  () => import("element-plus/es/components/tag/index"),
  () => import("element-plus/es/components/tag/style/css"),
  "ElTag"
);

const ElTable = createAsyncElComponent(
  () => import("element-plus/es/components/table/index"),
  () => import("element-plus/es/components/table/style/css"),
  "ElTable"
);

const ElTableColumn = createAsyncElComponent(
  () => import("element-plus/es/components/table/index"),
  () => import("element-plus/es/components/table/style/css"),
  "ElTableColumn"
);

const ElForm = createAsyncElComponent(
  () => import("element-plus/es/components/form/index"),
  () => import("element-plus/es/components/form/style/css"),
  "ElForm"
);

const ElFormItem = createAsyncElComponent(
  () => import("element-plus/es/components/form/index"),
  () => import("element-plus/es/components/form/style/css"),
  "ElFormItem"
);

const ElInput = createAsyncElComponent(
  () => import("element-plus/es/components/input/index"),
  () => import("element-plus/es/components/input/style/css"),
  "ElInput"
);

const ElButton = createAsyncElComponent(
  () => import("element-plus/es/components/button/index"),
  () => import("element-plus/es/components/button/style/css"),
  "ElButton"
);

const ElPagination = createAsyncElComponent(
  () => import("element-plus/es/components/pagination/index"),
  () => import("element-plus/es/components/pagination/style/css"),
  "ElPagination"
);

const loading = ref(false);
const tableData = ref<UserAdminVO[]>([]);

const pagination = reactive({
  currentPage: 1,
  pageSize: 10,
  total: 0,
});

const searchForm = reactive<Pick<UserSearchParams, "name">>({
  name: "",
});

async function fetchData() {
  loading.value = true;
  try {
    const res = await getAdminUserListApi({
      name: searchForm.name || undefined,
      currentPage: pagination.currentPage,
      pageSize: pagination.pageSize,
    });
    tableData.value = res.records;
    pagination.total = res.total;
    pagination.pageSize = res.size;
    pagination.currentPage = res.current;
  } finally {
    loading.value = false;
  }
}

function handleSearch() {
  pagination.currentPage = 1;
  fetchData();
}

function handleReset() {
  searchForm.name = "";
  pagination.currentPage = 1;
  fetchData();
}

function handleCurrentChange(page: number) {
  pagination.currentPage = page;
  fetchData();
}

function handleSizeChange(size: number) {
  pagination.pageSize = size;
  pagination.currentPage = 1;
  fetchData();
}

onMounted(() => {
  fetchData();
});
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
          <ElFormItem>
            <ElButton type="primary" @click="handleSearch">搜索</ElButton>
            <ElButton class="ml-2" @click="handleReset">重置</ElButton>
          </ElFormItem>
        </ElForm>
      </div>
      <ElTable :data="tableData" border v-loading="loading" style="width: 100%">
        <ElTableColumn type="index" label="#" width="50" />
        <ElTableColumn prop="nickName" label="昵称" min-width="160" />
        <ElTableColumn prop="deviceId" label="设备UID" min-width="220" />
        <ElTableColumn prop="email" label="邮箱" min-width="200" />
        <ElTableColumn prop="phone" label="手机号" min-width="160" />
        <ElTableColumn prop="role" label="角色" width="140">
          <template #default="{ row }">
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
        </ElTableColumn>
        <ElTableColumn prop="verified" label="已验证" width="100">
          <template #default="{ row }">
            <ElTag v-if="row.verified" type="success" size="small"> 已验证 </ElTag>
            <ElTag v-else type="info" size="small"> 未验证 </ElTag>
          </template>
        </ElTableColumn>
        <ElTableColumn prop="disabled" label="禁用" width="100">
          <template #default="{ row }">
            <ElTag v-if="row.disabled" type="danger" size="small"> 禁用 </ElTag>
            <ElTag v-else type="success" size="small"> 正常 </ElTag>
          </template>
        </ElTableColumn>
        <ElTableColumn prop="createTime" label="创建时间" width="200">
          <template #default="{ row }">
            {{ formatDateTime(row.createTime) }}
          </template>
        </ElTableColumn>
        <ElTableColumn prop="updateTime" label="更新时间" width="200">
          <template #default="{ row }">
            {{ formatDateTime(row.updateTime) }}
          </template>
        </ElTableColumn>
      </ElTable>
      <div class="mt-4 flex justify-end">
        <ElPagination v-model:current-page="pagination.currentPage" v-model:page-size="pagination.pageSize" :page-sizes="[10, 20, 50, 100]" :total="pagination.total" layout="total, sizes, prev, pager, next, jumper" @current-change="handleCurrentChange" @size-change="handleSizeChange" />
      </div>
    </ElCard>
  </Page>
</template>
