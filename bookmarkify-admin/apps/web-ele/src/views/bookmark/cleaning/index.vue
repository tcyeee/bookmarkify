<script lang="ts" setup>
import type { BookmarkEntity, BookmarkSearchParams } from "#/api/bookmark";

import { defineAsyncComponent, onMounted, reactive, ref } from "vue";

import { Page } from "@vben/common-ui";
import { formatDateTime } from "@vben/utils";

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

const ElDialog = defineAsyncComponent(() =>
  Promise.all([
    import("element-plus/es/components/dialog/index"),
    import("element-plus/es/components/dialog/style/css"),
  ]).then(([res]) => res.ElDialog)
);

const ElTable = defineAsyncComponent(() =>
  Promise.all([
    import("element-plus/es/components/table/index"),
    import("element-plus/es/components/table/style/css"),
  ]).then(([res]) => res.ElTable)
);

const ElTableColumn = defineAsyncComponent(() =>
  Promise.all([
    import("element-plus/es/components/table/index"),
    import("element-plus/es/components/table/style/css"),
  ]).then(([res]) => res.ElTableColumn)
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

const ElPagination = defineAsyncComponent(() =>
  Promise.all([
    import("element-plus/es/components/pagination/index"),
    import("element-plus/es/components/pagination/style/css"),
  ]).then(([res]) => res.ElPagination)
);

const detailVisible = ref(false);
const currentRow = ref<BookmarkEntity | null>(null);

const loading = ref(false);
const tableData = ref<BookmarkEntity[]>([]);

const pagination = reactive({
  currentPage: 1,
  pageSize: 10,
  total: 0,
});

const searchForm = reactive<Pick<BookmarkSearchParams, "name" | "status">>({
  name: "",
  status: undefined,
});

const statusOptions: {
  label: string;
  value: BookmarkSearchParams["status"];
}[] = [
  { label: "Loading", value: "LOADING" },
  { label: "Success", value: "SUCCESS" },
  { label: "Closed", value: "CLOSED" },
  { label: "Blocked", value: "BLOCKED" },
];

async function fetchData() {
  loading.value = true;
  try {
    const res = await getBookmarkListApi({
      name: searchForm.name || undefined,
      status: searchForm.status || undefined,
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

function handleRowClick(row: BookmarkEntity) {
  currentRow.value = row;
  detailVisible.value = true;
}

function handleSearch() {
  pagination.currentPage = 1;
  fetchData();
}

function handleReset() {
  searchForm.name = "";
  searchForm.status = undefined;
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
          <span>书签清理列表</span>
        </div>
      </template>
      <div class="mb-4">
        <ElForm :inline="true" :model="searchForm">
          <ElFormItem label="搜索">
            <ElInput v-model="searchForm.name" placeholder="名称/标题/描述/域名" clearable />
          </ElFormItem>
          <ElFormItem label="状态">
            <ElSelectV2 v-model="searchForm.status" :options="statusOptions" placeholder="请选择状态" clearable style="width: 160px" />
          </ElFormItem>
          <ElFormItem>
            <ElButton type="primary" @click="handleSearch">搜索</ElButton>
            <ElButton class="ml-2" @click="handleReset">重置</ElButton>
          </ElFormItem>
        </ElForm>
      </div>
      <ElTable :data="tableData" border v-loading="loading" style="width: 100%" @row-click="handleRowClick">
        <ElTableColumn type="index" label="#" width="50" />
        <ElTableColumn prop="appName" label="App Name" min-width="120" />
        <ElTableColumn prop="title" label="标题" min-width="220" />
        <ElTableColumn prop="urlHost" label="域名" min-width="180" />
        <ElTableColumn prop="parseStatus" label="状态" width="140">
          <template #default="{ row }">
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
        </ElTableColumn>
        <ElTableColumn label="活跃" width="120">
          <template #default="{ row }">
            <div class="flex items-center gap-2">
              <ElSwitch :model-value="row.isActivity" active-color="#13ce66" inactive-color="#ff4949" disabled />
              <span>{{ row.isActivity ? "活跃" : "不活跃" }}</span>
            </div>
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
      <ElDialog v-model="detailVisible" title="书签详情" width="600px">
        <div v-if="currentRow" class="space-y-3 text-sm">
          <div class="flex">
            <span class="w-24 text-gray-500">App Name</span>
            <span class="flex-1 font-medium break-all">{{ currentRow.appName || "-" }}</span>
          </div>
          <div class="flex">
            <span class="w-24 text-gray-500">标题</span>
            <span class="flex-1 font-medium break-all">{{ currentRow.title || "-" }}</span>
          </div>
          <div class="flex">
            <span class="w-24 text-gray-500">域名</span>
            <span class="flex-1 break-all">{{ currentRow.urlHost }}</span>
          </div>
          <div class="flex">
            <span class="w-24 text-gray-500">路径</span>
            <span class="flex-1 break-all">{{ currentRow.urlPath || "-" }}</span>
          </div>
          <div class="flex">
            <span class="w-24 text-gray-500">协议</span>
            <span class="flex-1">{{ currentRow.urlScheme }}</span>
          </div>
          <div class="flex">
            <span class="w-24 text-gray-500">描述</span>
            <span class="flex-1 break-all">{{ currentRow.description || "-" }}</span>
          </div>
          <div class="flex items-center">
            <span class="w-24 text-gray-500">状态</span>
            <div>
              <ElTag v-if="currentRow.parseStatus === 'SUCCESS'" type="success" size="small">
                成功
              </ElTag>
              <ElTag v-else-if="currentRow.parseStatus === 'LOADING'" type="info" size="small">
                解析中
              </ElTag>
              <ElTag v-else-if="currentRow.parseStatus === 'CLOSED'" type="warning" size="small">
                已关闭
              </ElTag>
              <ElTag v-else-if="currentRow.parseStatus === 'BLOCKED'" type="danger" size="small">
                已阻止
              </ElTag>
              <ElTag v-else size="small">
                {{ currentRow.parseStatus || "未知" }}
              </ElTag>
            </div>
          </div>
          <div class="flex items-center">
            <span class="w-24 text-gray-500">活跃</span>
            <div class="flex items-center gap-2">
              <ElSwitch :model-value="currentRow.isActivity" active-color="#13ce66" inactive-color="#ff4949" disabled />
              <span>{{ currentRow.isActivity ? "活跃" : "不活跃" }}</span>
            </div>
          </div>
          <div class="flex">
            <span class="w-24 text-gray-500">错误信息</span>
            <span class="flex-1 break-all">{{ currentRow.parseErrMsg || "-" }}</span>
          </div>
          <div class="flex">
            <span class="w-24 text-gray-500">创建时间</span>
            <span class="flex-1">{{ formatDateTime(currentRow.createTime) }}</span>
          </div>
          <div class="flex">
            <span class="w-24 text-gray-500">更新时间</span>
            <span class="flex-1">{{ formatDateTime(currentRow.updateTime) }}</span>
          </div>
        </div>
      </ElDialog>
    </ElCard>
  </Page>
</template>
