<script lang="ts" setup>
import type { BookmarkEntity, BookmarkSearchParams } from "#/api/bookmark";

import {
  computed,
  defineAsyncComponent,
  onMounted,
  onUnmounted,
  reactive,
  ref,
} from "vue";

import { Page } from "@vben/common-ui";
import { formatDateTime } from "@vben/utils";

import {
  findSimilarSitesApi,
  getBookmarkListApi,
  ingestSimilarSitesApi,
  recategorizeBookmarkApi,
  updateBookmarkCategoriesApi,
  type SimilarSite,
} from "#/api/bookmark";
import { getCategoryListApi, type CategoryEntity } from "#/api/category";
import {
  createIngestSocket,
  type IngestSocketHandle,
} from "#/api/similarIngestSocket";
import { ElMessage } from "element-plus";

import BookmarkIcon from "../liveness/BookmarkIcon.vue";

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

const similarSites = ref<SimilarSite[]>([]);
const loadingSimilar = ref(false);
const similarLoaded = ref(false);

async function findSimilar() {
  if (!currentRow.value) return;
  loadingSimilar.value = true;
  try {
    similarSites.value = await findSimilarSitesApi(currentRow.value.id);
    similarLoaded.value = true;
  } finally {
    loadingSimilar.value = false;
  }
}

// ── 一键收录 ──
const ingesting = ref(false);
// domain -> 状态：LOADING(收录中) / INGESTED(已收录) / SKIPPED(已跳过) / EXISTS(本地已有)
const ingestStatus = ref<Record<string, string>>({});
let ingestSocket: IngestSocketHandle | null = null;
let pendingDomains = new Set<string>();

// 待收录：本地不存在且本次尚未处理过的站点
const ingestTargets = computed(() =>
  similarSites.value.filter((s) => !s.exists && !ingestStatus.value[s.domain]),
);

function closeIngestSocket() {
  ingestSocket?.close();
  ingestSocket = null;
}

function resetIngest() {
  closeIngestSocket();
  ingesting.value = false;
  ingestStatus.value = {};
  pendingDomains = new Set();
}

async function oneClickIngest() {
  if (!currentRow.value) return;
  const targets = ingestTargets.value.map((s) => s.domain);
  if (targets.length === 0) return;

  ingesting.value = true;
  pendingDomains = new Set(targets);
  // 先把目标站点标记为「收录中」
  const next = { ...ingestStatus.value };
  targets.forEach((d) => (next[d] = "LOADING"));
  ingestStatus.value = next;

  // 开 WS 接收逐站进度（关弹窗或全部完成即断开）
  closeIngestSocket();
  ingestSocket = createIngestSocket((update) => {
    ingestStatus.value = {
      ...ingestStatus.value,
      [update.domain]: update.status,
    };
    pendingDomains.delete(update.domain);
    if (pendingDomains.size === 0) {
      ingesting.value = false;
      closeIngestSocket();
    }
  });

  try {
    await ingestSimilarSitesApi(currentRow.value.id, targets);
  } catch {
    resetIngest();
  }
}

onUnmounted(() => closeIngestSocket());

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
  similarSites.value = [];
  similarLoaded.value = false;
  resetIngest();
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
            <BookmarkIcon
              :value="row"
              :size="32"
              :hd-url="row.logo?.useHdLogo ? row.logo?.logoUrl : undefined"
              class="mx-auto"
            />
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
      <ElDialog
        v-model="detailVisible"
        title="书签详情"
        width="640px"
        @close="resetIngest"
      >
        <div v-if="currentRow" class="space-y-4 text-sm">
          <!-- 卡片一：基础信息 -->
          <ElCard shadow="never" class="detail-card">
            <template #header>
              <span class="font-medium">基础信息</span>
            </template>
            <div class="flex gap-6">
              <!-- 左：头像 + 状态 + 活跃 -->
              <div class="flex w-32 flex-col items-center gap-3">
                <BookmarkIcon
                  :value="currentRow"
                  :size="64"
                  :hd-url="currentRow.logo?.useHdLogo ? currentRow.logo?.logoUrl : undefined"
                />
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
                <div class="flex items-center gap-2">
                  <ElSwitch :model-value="currentRow.isActivity" active-color="#13ce66" inactive-color="#ff4949" disabled />
                  <span class="text-gray-500">{{ currentRow.isActivity ? "活跃" : "不活跃" }}</span>
                </div>
              </div>
              <!-- 右：其余信息 -->
              <div class="flex-1 space-y-2">
                <div class="flex">
                  <span class="w-20 text-gray-500">App Name</span>
                  <span class="flex-1 font-medium break-all">{{ currentRow.appName || "-" }}</span>
                </div>
                <div class="flex">
                  <span class="w-20 text-gray-500">标题</span>
                  <span class="flex-1 font-medium break-all">{{ currentRow.title || "-" }}</span>
                </div>
                <div class="flex">
                  <span class="w-20 text-gray-500">域名</span>
                  <span class="flex-1 break-all">{{ currentRow.urlHost }}</span>
                </div>
                <div class="flex">
                  <span class="w-20 text-gray-500">描述</span>
                  <span class="flex-1 break-all">{{ currentRow.description || "-" }}</span>
                </div>
                <div class="flex">
                  <span class="w-20 text-gray-500">创建时间</span>
                  <span class="flex-1">{{ formatDateTime(currentRow.createTime) }}</span>
                </div>
                <div v-if="currentRow.parseErrMsg" class="flex">
                  <span class="w-20 text-gray-500">错误信息</span>
                  <span class="flex-1 break-all text-red-500">{{ currentRow.parseErrMsg }}</span>
                </div>
              </div>
            </div>
          </ElCard>

          <!-- 卡片二：分类 -->
          <ElCard shadow="never" class="detail-card">
            <template #header>
              <span class="font-medium">分类</span>
            </template>
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
            <div class="mt-2 flex justify-end gap-2">
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
          </ElCard>

          <!-- 卡片三：相似网站（点击操作栏按钮后出现） -->
          <ElCard v-if="similarLoaded" shadow="never" class="detail-card">
            <template #header>
              <span class="font-medium">相似网站</span>
            </template>
            <ul v-if="similarSites.length > 0" class="space-y-2">
              <li
                v-for="s in similarSites"
                :key="s.domain"
                class="rounded border border-gray-100 p-2"
              >
                <div class="flex flex-wrap items-center gap-2 font-medium">
                  <span>{{ s.name }}</span>
                  <a
                    :href="`https://${s.domain}`"
                    target="_blank"
                    rel="noopener noreferrer"
                    class="text-blue-500"
                  >
                    {{ s.domain }}
                  </a>
                  <!-- 逐站收录状态优先于「本地已有」标记 -->
                  <ElTag
                    v-if="ingestStatus[s.domain] === 'LOADING'"
                    type="info"
                    size="small"
                  >
                    收录中
                  </ElTag>
                  <ElTag
                    v-else-if="ingestStatus[s.domain] === 'INGESTED'"
                    type="success"
                    size="small"
                  >
                    已收录
                  </ElTag>
                  <ElTag
                    v-else-if="ingestStatus[s.domain] === 'SKIPPED'"
                    type="danger"
                    size="small"
                  >
                    已跳过
                  </ElTag>
                  <ElTag
                    v-else-if="s.exists || ingestStatus[s.domain] === 'EXISTS'"
                    type="warning"
                    size="small"
                  >
                    本地已有
                  </ElTag>
                </div>
                <div class="text-gray-500">{{ s.reason }}</div>
              </li>
            </ul>
            <div v-else class="text-gray-400">未找到相似网站</div>
            <div v-if="similarSites.length > 0" class="mt-3 flex justify-end">
              <ElButton
                type="primary"
                size="small"
                :loading="ingesting"
                :disabled="ingestTargets.length === 0"
                @click="oneClickIngest"
              >
                一键收录{{
                  ingestTargets.length ? `（${ingestTargets.length}）` : ""
                }}
              </ElButton>
            </div>
          </ElCard>
        </div>
        <template #footer>
          <ElButton
            :loading="loadingSimilar"
            @click="findSimilar"
          >
            查找相似网站
          </ElButton>
          <ElButton @click="detailVisible = false">关闭</ElButton>
        </template>
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

.detail-card :deep(.el-card__header) {
  padding: 10px 16px;
}

.detail-card :deep(.el-card__body) {
  padding: 16px;
}
</style>
