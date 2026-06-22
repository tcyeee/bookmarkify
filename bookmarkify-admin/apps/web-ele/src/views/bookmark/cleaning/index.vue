<script lang="ts" setup>
import type { BookmarkEntity, BookmarkSearchParams } from "#/api/bookmark";

import { defineAsyncComponent, onMounted, reactive, ref } from "vue";

import { Page } from "@vben/common-ui";
import { formatDateTime } from "@vben/utils";

import {
  getBookmarkListApi,
  recategorizeBookmarkApi,
  updateBookmarkCategoriesApi,
} from "#/api/bookmark";
import { getCategoryListApi, type CategoryEntity } from "#/api/category";
import { ElMessage } from "element-plus";

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

const categoryDict = ref<CategoryEntity[]>([]);
const editingCategoryIds = ref<string[]>([]);
const savingCategories = ref(false);
const recategorizing = ref(false);

async function loadCategoryDict() {
  if (categoryDict.value.length === 0) {
    categoryDict.value = await getCategoryListApi();
  }
}

async function saveCategories() {
  if (!currentRow.value) return;
  savingCategories.value = true;
  try {
    const updated = await updateBookmarkCategoriesApi(
      currentRow.value.id,
      editingCategoryIds.value,
    );
    currentRow.value.categories = updated;
    syncRowCategories(currentRow.value.id, updated);
    ElMessage.success("分类已保存");
  } finally {
    savingCategories.value = false;
  }
}

async function recategorize() {
  if (!currentRow.value) return;
  recategorizing.value = true;
  try {
    const updated = await recategorizeBookmarkApi(currentRow.value.id);
    currentRow.value.categories = updated;
    editingCategoryIds.value = updated.map((c) => c.id);
    syncRowCategories(currentRow.value.id, updated);
    ElMessage.success(
      updated.length > 0 ? "AI 归类完成" : "AI 未返回分类（检查词表是否为空）",
    );
  } finally {
    recategorizing.value = false;
  }
}

function syncRowCategories(
  id: string,
  categories: BookmarkEntity["categories"],
) {
  const row = tableData.value.find((r) => r.id === id);
  if (row) row.categories = categories;
}

const loading = ref(false);
const tableData = ref<BookmarkEntity[]>([]);

const pagination = reactive({
  currentPage: 1,
  pageSize: 20,
  total: 0,
});

const searchForm = reactive<Pick<BookmarkSearchParams, "name" | "status">>({
  name: "",
  status: undefined,
});

const statusOptions: {
  label: string;
  value: BookmarkSearchParams["status"];
  type: "danger" | "info" | "success" | "warning";
}[] = [
  { label: "解析中", value: "LOADING", type: "info" },
  { label: "成功", value: "SUCCESS", type: "success" },
  { label: "已关闭", value: "CLOSED", type: "warning" },
  { label: "已阻止", value: "BLOCKED", type: "danger" },
];

function iconSrc(row: BookmarkEntity | null): string {
  const v = row?.iconBase64;
  if (!v) return "";
  const trimmed = v.trim();
  if (trimmed.startsWith("data:") || trimmed.startsWith("http")) return trimmed;
  return `data:image/png;base64,${trimmed}`;
}

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

async function handleRowClick(row: BookmarkEntity) {
  currentRow.value = row;
  detailVisible.value = true;
  editingCategoryIds.value = (row.categories ?? []).map((c) => c.id);
  await loadCategoryDict();
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
      <ElTable :data="tableData" border v-loading="loading" style="width: 100%" @row-click="handleRowClick">
        <ElTableColumn label="头像" width="80" align="center">
          <template #default="{ row }">
            <img v-if="iconSrc(row)" :src="iconSrc(row)" class="mx-auto h-8 w-8 rounded object-contain" :alt="row.title" />
            <span v-else class="text-gray-300">-</span>
          </template>
        </ElTableColumn>
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
          <div class="flex items-center">
            <span class="w-24 text-gray-500">头像</span>
            <img v-if="iconSrc(currentRow)" :src="iconSrc(currentRow)" class="h-12 w-12 rounded object-contain" :alt="currentRow.title" />
            <span v-else class="text-gray-400">-</span>
          </div>
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
          <div class="flex items-start">
            <span class="w-24 text-gray-500">分类</span>
            <div class="flex-1">
              <div class="mb-2 flex flex-wrap gap-1">
                <ElTag
                  v-for="c in currentRow.categories ?? []"
                  :key="c.id"
                  size="small"
                  :color="c.color || undefined"
                  :style="c.color ? { color: '#fff', borderColor: c.color } : {}"
                >
                  {{ c.name }}
                </ElTag>
                <span
                  v-if="(currentRow.categories ?? []).length === 0"
                  class="text-gray-400"
                >
                  暂无分类
                </span>
              </div>
              <ElSelectV2
                v-model="editingCategoryIds"
                :options="categoryDict.map((c) => ({ label: c.name, value: c.id }))"
                multiple
                clearable
                placeholder="选择分类"
                style="width: 100%"
              />
              <div class="mt-2 flex gap-2">
                <ElButton
                  type="primary"
                  size="small"
                  :loading="savingCategories"
                  @click="saveCategories"
                >
                  保存分类
                </ElButton>
                <ElButton
                  size="small"
                  :loading="recategorizing"
                  @click="recategorize"
                >
                  重新 AI 归类
                </ElButton>
              </div>
            </div>
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

<style scoped>
.search-bar :deep(.el-form-item__label) {
  height: 32px;
  font-weight: 400 !important;
  line-height: 32px;
  color: var(--el-text-color-regular);
}
</style>
