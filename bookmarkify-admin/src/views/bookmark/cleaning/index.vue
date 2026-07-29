<script lang="ts" setup>
import type { BookmarkEntity, BookmarkSearchParams } from "#/api/bookmark";

import {
  computed,
  defineAsyncComponent,
  onUnmounted,
  reactive,
  ref,
} from "vue";

import { Page } from "@vben/common-ui";
import { formatDateTime } from "@vben/utils";

import {
  checkBookmarkLivenessApi,
  findSimilarSitesApi,
  getBookmarkListApi,
  ingestSimilarSitesApi,
  recategorizeBookmarkApi,
  refreshBookmarkApi,
  updateBookmarkBasicInfoApi,
  updateBookmarkCategoriesApi,
  type BookmarkLivenessResult,
  type BookmarkParseStatus,
  type SimilarSite,
} from "#/api/bookmark";
import { getCategoryListApi, type CategoryEntity } from "#/api/category";
import {
  createIngestSocket,
  type IngestSocketHandle,
} from "#/api/similarIngestSocket";
import { ElMessage } from "element-plus";
import { useVbenVxeGrid, type VxeGridProps } from "#/adapter/vxe-table";

import BookmarkAssetCell from "../BookmarkAssetCell.vue";
import { faviconOf, logoOf, socialOf } from "../siteAsset";
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

// ── 书签检测：直接调用 scrapper 重新抓取一次，展示其给出的全部字段 ──
const checkingLiveness = ref(false);
const livenessChecked = ref(false);
const livenessResult = ref<BookmarkLivenessResult | null>(null);

function resetLiveness() {
  livenessChecked.value = false;
  livenessResult.value = null;
}

async function checkLiveness() {
  if (!currentRow.value) return;
  checkingLiveness.value = true;
  try {
    const result = await checkBookmarkLivenessApi(currentRow.value.id);
    livenessResult.value = result;
    livenessChecked.value = true;
    currentRow.value.isActivity = result.isActivity;
    currentRow.value.parseStatus = result.parseStatus;
    currentRow.value.antiCrawlerBlocked = result.antiCrawlerBlocked;
    if (result.errorMsg) currentRow.value.parseErrMsg = result.errorMsg;
    syncRowLiveness(currentRow.value.id, result);
    ElMessage[result.success ? "success" : "warning"](
      result.success
        ? "检测完成，网站存活"
        : `检测完成，网站不可访问${result.errorMsg ? `：${result.errorMsg}` : ""}`,
    );
  } catch {
    // 同上：抓取服务自身不可用时接口直接报错，提示已由拦截器弹出
  } finally {
    checkingLiveness.value = false;
  }
}

function syncRowLiveness(id: string, result: BookmarkLivenessResult) {
  const row = gridApi.grid?.getTableData().fullData.find((r: BookmarkEntity) => r.id === id);
  if (row) {
    row.isActivity = result.isActivity;
    row.parseStatus = result.parseStatus;
    row.antiCrawlerBlocked = result.antiCrawlerBlocked;
    if (result.errorMsg) row.parseErrMsg = result.errorMsg;
  }
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
  const row = gridApi.grid?.getTableData().fullData.find((r: BookmarkEntity) => r.id === id);
  if (row) row.categories = categories;
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
];

function handleRowClick({ row, column }: { row: BookmarkEntity; column: any }) {
  if (column?.field === "rowActions") return;
  currentRow.value = row;
  detailVisible.value = true;
  editingCategoryIds.value = (row.categories ?? []).map((c) => c.id);
  similarSites.value = [];
  similarLoaded.value = false;
  resetIngest();
  resetLiveness();
  loadCategoryDict();
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
    { field: "urlHost", title: "域名", minWidth: 180 },
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

const [Grid, gridApi] = useVbenVxeGrid({ gridOptions });
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
      <Grid @cell-click="handleRowClick">
        <template #favicon="{ row }">
          <BookmarkAssetCell :src="faviconOf(row)" />
        </template>
        <template #logo="{ row }">
          <BookmarkAssetCell :src="logoOf(row)" />
        </template>
        <template #og="{ row }">
          <BookmarkAssetCell :src="socialOf(row)" wide />
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
      <ElDialog
        v-model="detailVisible"
        title="书签详情"
        width="640px"
        @close="
          resetIngest();
          resetLiveness();
        "
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
                  :src="logoOf(currentRow) ?? faviconOf(currentRow)"
                  :size="64"
                  
                />
                <div>
                  <ElTag v-if="currentRow.parseStatus === 'SUCCESS'" type="success" size="small">
                    成功
                  </ElTag>
                  <ElTag v-else-if="currentRow.parseStatus === 'PENDING'" type="info" size="small">
                    等待中
                  </ElTag>
                  <ElTag v-else-if="currentRow.parseStatus === 'UNREACHABLE'" type="danger" size="small">
                    抓取失败
                  </ElTag>
                  <ElTag v-else size="small">
                    {{ currentRow.parseStatus || "未知" }}
                  </ElTag>
                  <ElTag v-if="currentRow.antiCrawlerBlocked" type="warning" size="small" class="ml-1">
                    反爬拦截
                  </ElTag>
                  <ElTag v-if="currentRow.nsfw" type="danger" size="small" class="ml-1">
                    NSFW
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

          <!-- 卡片四：书签检测（点击操作栏按钮后出现，直接展示 scrapper 返回的全部字段） -->
          <ElCard v-if="livenessChecked" shadow="never" class="detail-card">
            <template #header>
              <span class="font-medium">检测结果</span>
            </template>
            <div v-if="livenessResult" class="space-y-2">
              <div class="flex flex-wrap items-center gap-2">
                <ElTag :type="livenessResult.success ? 'success' : 'danger'" size="small">
                  {{ livenessResult.success ? "存活" : "不可访问" }}
                </ElTag>
                <span v-if="livenessResult.source" class="text-gray-500">
                  来源：{{ livenessResult.source }}
                </span>
                <ElTag v-if="livenessResult.cached" type="info" size="small">
                  命中缓存
                </ElTag>
              </div>
              <div v-if="livenessResult.title" class="flex">
                <span class="w-20 text-gray-500">新标题</span>
                <span class="flex-1 break-all">{{ livenessResult.title }}</span>
              </div>
              <div v-if="livenessResult.description" class="flex">
                <span class="w-20 text-gray-500">新描述</span>
                <span class="flex-1 break-all">{{ livenessResult.description }}</span>
              </div>
              <div v-if="livenessResult.errorMsg" class="flex">
                <span class="w-20 text-gray-500">错误信息</span>
                <span class="flex-1 break-all text-red-500">{{ livenessResult.errorMsg }}</span>
              </div>
              <div
                v-if="livenessResult.favicon || livenessResult.logo || livenessResult.image || livenessResult.screenshot"
                class="flex flex-wrap gap-4 pt-1"
              >
                <div v-if="livenessResult.favicon" class="flex flex-col items-center gap-1">
                  <img :src="livenessResult.favicon" alt="favicon" class="h-10 w-10 rounded border object-contain" />
                  <span class="text-xs text-gray-400">favicon</span>
                </div>
                <div v-if="livenessResult.logo" class="flex flex-col items-center gap-1">
                  <img :src="livenessResult.logo" alt="logo" class="h-10 w-10 rounded border object-contain" />
                  <span class="text-xs text-gray-400">logo</span>
                </div>
                <div v-if="livenessResult.image" class="flex flex-col items-center gap-1">
                  <img :src="livenessResult.image" alt="image" class="h-16 w-28 rounded border object-cover" />
                  <span class="text-xs text-gray-400">image</span>
                </div>
                <div v-if="livenessResult.screenshot" class="flex flex-col items-center gap-1">
                  <img :src="livenessResult.screenshot" alt="screenshot" class="h-16 w-28 rounded border object-cover" />
                  <span class="text-xs text-gray-400">screenshot</span>
                </div>
              </div>
            </div>
          </ElCard>
        </div>
        <template #footer>
          <ElButton
            :loading="checkingLiveness"
            @click="checkLiveness"
          >
            检测活性
          </ElButton>
          <ElButton
            :loading="loadingSimilar"
            @click="findSimilar"
          >
            查找相似网站
          </ElButton>
          <ElButton @click="detailVisible = false">关闭</ElButton>
        </template>
      </ElDialog>
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
