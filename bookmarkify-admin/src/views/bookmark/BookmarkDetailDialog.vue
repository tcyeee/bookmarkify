<script lang="ts" setup>
import type { BookmarkEntity, BookmarkLivenessResult, SimilarSite } from "#/api/bookmark";
import type { CategoryEntity } from "#/api/category";

import { computed, defineAsyncComponent, onUnmounted, ref, watch } from "vue";

import { formatDateTime } from "@vben/utils";
import { ElMessage } from "element-plus";

import {
  checkBookmarkLivenessApi,
  findBookmarkByUrlApi,
  findSimilarSitesApi,
  getSiblingBookmarksApi,
  ingestSimilarSitesApi,
  recategorizeBookmarkApi,
  refetchBookmarkAssetsApi,
  updateBookmarkCategoriesApi,
} from "#/api/bookmark";
import { getCategoryListApi } from "#/api/category";
import { createIngestSocket, type IngestSocketHandle } from "#/api/similarIngestSocket";

import BookmarkAssetCell from "./BookmarkAssetCell.vue";
import BookmarkIcon from "./BookmarkIcon.vue";
import {
  isScrapableType,
  LINK_TYPE_LABEL,
  LINK_TYPE_REASON,
  linkTypeOfUrl,
} from "./linkType";
import {
  FETCH_LAYER_META,
  formatMetaSources,
  httpStatusType,
  metaNeedsAttention,
} from "./pageMeta";
import {
  BOOKMARK_LOCKED_FIELD_LABEL,
  DISPLAY_MODE_LABEL,
  EXTRACTOR_LEGEND,
  faviconOf,
  logoOf,
} from "./siteAsset";

/**
 * 书签详情弹窗。
 *
 * 后台的三个入口（书签管理表格 / 图标平铺墙 / scrapper 调用日志）此前各有一套详情展示，
 * 字段互相缺斤少两。这里收成一个组件：**展示后端下发的全部字段**，包括抓取事实（图片资产、
 * 出处、hash）、人工策略（分类、显示设置、字段锁）与巡检调度状态。
 *
 * 两种取数方式：
 * - [bookmark] 直接给行对象（表格页已有完整数据，且就地改动会同步回表格行）；
 * - [lookupUrl] 只有一个 URL（调用日志表存的是抓取记录，不带书签 ID），按域名反查。
 */
const props = defineProps<{
  /** 已有的书签行对象；组件会就地修改它，调用方的表格行随之更新 */
  bookmark?: BookmarkEntity | null;
  /** 仅有 URL 时按域名+路径反查书签；[bookmark] 存在时忽略 */
  lookupUrl?: string;
}>();

const emit = defineEmits<{
  /** 弹窗内的操作改动了书签（分类/活性等），供调用方刷新自己的数据 */
  (e: "updated", bookmark: BookmarkEntity): void;
}>();

const visible = defineModel<boolean>({ default: false });

const ElCard = defineAsyncComponent(() =>
  Promise.all([
    import("element-plus/es/components/card/index"),
    import("element-plus/es/components/card/style/css"),
  ]).then(([res]) => res.ElCard),
);

const ElTag = defineAsyncComponent(() =>
  Promise.all([
    import("element-plus/es/components/tag/index"),
    import("element-plus/es/components/tag/style/css"),
  ]).then(([res]) => res.ElTag),
);

const ElSwitch = defineAsyncComponent(() =>
  Promise.all([
    import("element-plus/es/components/switch/index"),
    import("element-plus/es/components/switch/style/css"),
  ]).then(([res]) => res.ElSwitch),
);

const ElDialog = defineAsyncComponent(() =>
  Promise.all([
    import("element-plus/es/components/dialog/index"),
    import("element-plus/es/components/dialog/style/css"),
  ]).then(([res]) => res.ElDialog),
);

const ElSelectV2 = defineAsyncComponent(() =>
  Promise.all([
    import("element-plus/es/components/select-v2/index"),
    import("element-plus/es/components/select-v2/style/css"),
  ]).then(([res]) => res.ElSelectV2),
);

const ElButton = defineAsyncComponent(() =>
  Promise.all([
    import("element-plus/es/components/button/index"),
    import("element-plus/es/components/button/style/css"),
  ]).then(([res]) => res.ElButton),
);

const ElImageViewer = defineAsyncComponent(() =>
  Promise.all([
    import("element-plus/es/components/image-viewer/index"),
    import("element-plus/es/components/image-viewer/style/css"),
  ]).then(([res]) => res.ElImageViewer),
);

// ── 图片放大 ──────────────────────────────────────────────────────────────────
// 后台判断一张图能不能用，靠的就是看清它本身（LOGO 是不是糊的、截图是不是白板），
// 32px 的格子做不到这件事。查看器按「同一组图」打开，可左右翻，不用退出再点下一张。
const previewList = ref<string[]>([]);
const previewIndex = ref(0);

/** [group] 是同组可翻页的图，[src] 是本次点中的那张 */
function openPreview(group: (null | string | undefined)[], src?: null | string) {
  if (!src) return;
  const list = group.filter((u): u is string => !!u);
  const index = list.indexOf(src);
  previewList.value = list.length > 0 ? list : [src];
  previewIndex.value = index < 0 ? 0 : index;
}

function closePreview() {
  previewList.value = [];
  previewIndex.value = 0;
}

// ── 当前书签：外部给的行对象优先，否则用 lookupUrl 反查到的那条 ──────────────────
const resolved = ref<BookmarkEntity | null>(null);
const looking = ref(false);
const lookupFailed = ref(false);

const current = computed<BookmarkEntity | null>(
  () => props.bookmark ?? resolved.value,
);

/**
 * 完整地址。**query 与 fragment 必须带上** —— 它们是 canonical 四元组的后半截，
 * 少了它们 `/watch?v=A` 与 `/watch?v=B` 的详情弹窗会显示成同一个地址，而拆开这两者
 * 正是后端 DeepLinkSplitRepair 干的事。库里这两列不含 `?` / `#`。
 */
const currentUrl = computed(() => {
  const row = current.value;
  if (!row) return "";
  const query = row.urlQuery ? `?${row.urlQuery}` : "";
  const fragment = row.urlFragment ? `#${row.urlFragment}` : "";
  return `${row.urlScheme}://${row.urlHost}${row.urlPath ?? ""}${query}${fragment}`;
});

const dialogTitle = computed(() => {
  const row = current.value;
  if (!row) return "书签详情";
  return `书签详情 · ${row.appName || row.title || row.urlHost}`;
});

// ── 本机 / IP 书签：这条记录不参与抓取 ─────────────────────────────────────────
//
// `localhost:5173`、`192.168.0.73:8192`、`47.97.71.143:8001` 这类地址后端从不抓取
// （ScrapeTargetGuard，E309），所以它们的标题、图标、page_meta、巡检游标**永远**是空的。
// 照常把那些卡片摆出来，等于让管理员对着一屏"没抓到"去排查一个根本不存在的抓取失败，
// 然后挨个去点重抓和活性检测——每一次都是一个注定被后端拒绝的请求。
//
// 判据优先用后端下发的 linkType（权威值），老数据/反查路径缺这一列时退回按 URL 现算。
const linkType = computed(
  () => current.value?.linkType ?? linkTypeOfUrl(currentUrl.value),
);
/** 这条书签会不会被抓取。false 时详情页收起全部与抓取相关的展示与操作 */
const scrapable = computed(() => isScrapableType(linkType.value));
const notScrapableReason = computed(() => LINK_TYPE_REASON[linkType.value]);
const linkTypeLabel = computed(() => LINK_TYPE_LABEL[linkType.value]);

async function lookup() {
  if (!props.lookupUrl) return;
  looking.value = true;
  lookupFailed.value = false;
  try {
    const found = await findBookmarkByUrlApi(props.lookupUrl);
    resolved.value = found;
    lookupFailed.value = found === null;
  } catch {
    lookupFailed.value = true;
  } finally {
    looking.value = false;
  }
}

// ── 分类：展示 + 人工覆盖 + 重新 AI 归类 ────────────────────────────────────────
const categoryDict = ref<CategoryEntity[]>([]);
const editingCategoryIds = ref<string[]>([]);
const savingCategories = ref(false);
const recategorizing = ref(false);

async function loadCategoryDict(force = false) {
  if (force || categoryDict.value.length === 0) {
    categoryDict.value = await getCategoryListApi();
  }
}

async function saveCategories() {
  const row = current.value;
  if (!row) return;
  savingCategories.value = true;
  try {
    row.categories = await updateBookmarkCategoriesApi(
      row.id,
      editingCategoryIds.value,
    );
    emit("updated", row);
    ElMessage.success("分类已保存");
  } finally {
    savingCategories.value = false;
  }
}

/**
 * 重新 AI 归类。后端走的是**开词表**路径：AI 觉得现有分类都不贴切时会新建分类并落进分类字典，
 * 所以字典必须强制重拉 —— 否则刚建出来的那几个在下拉框里根本选不到，看着像"归类没生效"。
 */
async function recategorize() {
  const row = current.value;
  if (!row) return;
  recategorizing.value = true;
  try {
    const updated = await recategorizeBookmarkApi(row.id);
    row.categories = updated;
    editingCategoryIds.value = updated.map((c) => c.id);
    await loadCategoryDict(true);
    emit("updated", row);
    ElMessage[updated.length > 0 ? "success" : "warning"](
      updated.length > 0
        ? `AI 归类完成：${updated.map((c) => c.name).join("、")}`
        : "AI 未返回分类",
    );
  } finally {
    recategorizing.value = false;
  }
}

// ── 图片资产重新抓取：只重抓图，本次没抓到时后端保留原图，不会把已有图片清空 ──────
const refetchingAssets = ref(false);

async function refetchAssets() {
  const row = current.value;
  // 后端会拒绝这类目标(E309)，前端不必先发一次请求再看它报错
  if (!row || !scrapable.value) return;
  refetchingAssets.value = true;
  try {
    const res = await refetchBookmarkAssetsApi(row.id);
    row.assets = res.bookmark.assets;
    row.displayPrefs = res.bookmark.displayPrefs;
    emit("updated", row);
    if (res.success && res.scrapedAssetCount > 0) {
      ElMessage.success(`抓到 ${res.scrapedAssetCount} 张图片，已更新`);
    } else if (res.success) {
      ElMessage.warning("本次一张图都没抓到，已保留原有图片");
    } else {
      ElMessage.warning(
        `抓取失败，已保留原有图片${res.errorMsg ? `：${res.errorMsg}` : ""}`,
      );
    }
  } catch {
    // 抓取服务不可用(E307)等由请求拦截器统一提示
  } finally {
    refetchingAssets.value = false;
  }
}

// ── 关联网站之一：同域名下已收录的其它页面 ───────────────────────────────────────
const siblings = ref<BookmarkEntity[]>([]);
const loadingSiblings = ref(false);

async function loadSiblings() {
  const row = current.value;
  if (!row) return;
  loadingSiblings.value = true;
  try {
    siblings.value = await getSiblingBookmarksApi(row.urlHost, row.id);
  } catch {
    siblings.value = [];
  } finally {
    loadingSiblings.value = false;
  }
}

// ── 关联网站之二：AI 推荐的相似站点（点按钮才查，会走一次大模型） ────────────────
const similarSites = ref<SimilarSite[]>([]);
const loadingSimilar = ref(false);
const similarLoaded = ref(false);

async function findSimilar() {
  const row = current.value;
  if (!row) return;
  loadingSimilar.value = true;
  try {
    similarSites.value = await findSimilarSitesApi(row.id);
    similarLoaded.value = true;
  } finally {
    loadingSimilar.value = false;
  }
}

// ── 一键收录：异步逐站收录，进度经 WebSocket 推回 ────────────────────────────────
const ingesting = ref(false);
// domain -> 状态：LOADING(收录中) / INGESTED(已收录) / SKIPPED(已跳过) / EXISTS(本地已有)
const ingestStatus = ref<Record<string, string>>({});
let ingestSocket: IngestSocketHandle | null = null;
let pendingDomains = new Set<string>();

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
  const row = current.value;
  if (!row) return;
  const targets = ingestTargets.value.map((s) => s.domain);
  if (targets.length === 0) return;

  ingesting.value = true;
  pendingDomains = new Set(targets);
  const next = { ...ingestStatus.value };
  targets.forEach((d) => (next[d] = "LOADING"));
  ingestStatus.value = next;

  closeIngestSocket();
  ingestSocket = createIngestSocket((update) => {
    ingestStatus.value = { ...ingestStatus.value, [update.domain]: update.status };
    pendingDomains.delete(update.domain);
    if (pendingDomains.size === 0) {
      ingesting.value = false;
      closeIngestSocket();
    }
  });

  try {
    await ingestSimilarSitesApi(row.id, targets);
  } catch {
    resetIngest();
  }
}

onUnmounted(() => closeIngestSocket());

// ── 活性检测：直接调 scrapper 重抓一次，展示其返回的全部字段 ─────────────────────
const checkingLiveness = ref(false);
const livenessChecked = ref(false);
const livenessResult = ref<BookmarkLivenessResult | null>(null);

function resetLiveness() {
  livenessChecked.value = false;
  livenessResult.value = null;
}

async function checkLiveness() {
  const row = current.value;
  if (!row || !scrapable.value) return;
  checkingLiveness.value = true;
  try {
    const result = await checkBookmarkLivenessApi(row.id);
    livenessResult.value = result;
    livenessChecked.value = true;
    row.isActivity = result.isActivity;
    row.parseStatus = result.parseStatus;
    row.antiCrawlerBlocked = result.antiCrawlerBlocked;
    if (result.errorMsg) row.parseErrMsg = result.errorMsg;
    emit("updated", row);
    ElMessage[result.success ? "success" : "warning"](
      result.success
        ? "检测完成，网站存活"
        : `检测完成，网站不可访问${result.errorMsg ? `：${result.errorMsg}` : ""}`,
    );
  } catch {
    // 抓取服务自身不可用时接口直接报错，提示已由请求拦截器弹出
  } finally {
    checkingLiveness.value = false;
  }
}

// ── 打开/关闭时的状态复位 ──────────────────────────────────────────────────────
watch(visible, (opened) => {
  closePreview();
  if (!opened) {
    resetIngest();
    resetLiveness();
    return;
  }
  similarSites.value = [];
  similarLoaded.value = false;
  siblings.value = [];
  resetIngest();
  resetLiveness();
  loadCategoryDict();
  if (props.bookmark) {
    resolved.value = null;
    editingCategoryIds.value = (props.bookmark.categories ?? []).map((c) => c.id);
    // 非域名书签不展示「关联网站」，也就不必去查同域页面：localhost 下的"同域页面"
    // 是别人机器上的另一个服务，摆在一起只会误导
    if (scrapable.value) loadSiblings();
  } else {
    resolved.value = null;
    lookup().then(() => {
      if (!resolved.value) return;
      editingCategoryIds.value = (resolved.value.categories ?? []).map((c) => c.id);
      if (scrapable.value) loadSiblings();
    });
  }
});

// ── 展示辅助 ──────────────────────────────────────────────────────────────────
const ASSET_ROLE_LABEL: Record<string, string> = {
  FAVICON: "小图标",
  LOGO: "高清 LOGO",
  SCREENSHOT: "页面截图",
  SOCIAL: "社交分享图",
};

const PARSE_STATUS_META: Record<
  string,
  { label: string; tip?: string; type: "danger" | "info" | "success" | "warning" }
> = {
  ARCHIVED: {
    label: "已归档",
    type: "warning",
    tip: "连续失败达到阈值，已停止巡检；手动刷新/检测可恢复",
  },
  PENDING: { label: "等待中", type: "info" },
  SUCCESS: { label: "成功", type: "success" },
  UNREACHABLE: { label: "抓取失败", type: "danger" },
};

const parseStatusMeta = computed(() => {
  const status = current.value?.parseStatus;
  return (
    (status && PARSE_STATUS_META[status]) ?? {
      label: status || "未知",
      type: "info" as const,
      tip: undefined,
    }
  );
});

// ── 抓取元数据（page_meta）──────────────────────────────────────────────────────
const pageMeta = computed(() => current.value?.pageMeta);

/**
 * 抓取原样的标题/描述是否已经与主表当前生效值分叉。
 *
 * 分叉本身就是结论：要么这条被管理员手工改过（多半还带着字段锁），要么站点在上次抓取后
 * 改了内容而主表那一列被锁住没跟上。不标出来的话，两个几乎一样的字符串摆在一起没人会去比。
 */
const titleDiverged = computed(() => {
  const scraped = pageMeta.value?.title;
  return !!scraped && scraped !== (current.value?.title ?? "");
});

const descriptionDiverged = computed(() => {
  const scraped = pageMeta.value?.description;
  return !!scraped && scraped !== (current.value?.description ?? "");
});

function formatBytes(bytes?: number) {
  if (!bytes && bytes !== 0) return "-";
  if (bytes < 1024) return `${bytes} B`;
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`;
  return `${(bytes / 1024 / 1024).toFixed(2)} MB`;
}

function formatTime(value?: string) {
  return value ? formatDateTime(value) : "-";
}

function siblingUrl(row: BookmarkEntity) {
  const query = row.urlQuery ? `?${row.urlQuery}` : "";
  const fragment = row.urlFragment ? `#${row.urlFragment}` : "";
  return `${row.urlScheme}://${row.urlHost}${row.urlPath ?? ""}${query}${fragment}`;
}

// 各卡片内部的图片各成一组，放大后左右翻页只在同类图之间进行
const assetUrls = computed(() => (current.value?.assets ?? []).map((a) => a.url));
const displayPrefUrls = computed(() =>
  (current.value?.displayPrefs ?? []).map((p) => p.previewUrl),
);
const siblingIconUrls = computed(() =>
  siblings.value.map((s) => logoOf(s) ?? faviconOf(s)),
);
const livenessImageUrls = computed(() => {
  const r = livenessResult.value;
  return r ? [r.favicon, r.logo, r.image, r.screenshot] : [];
});
</script>

<template>
  <ElDialog v-model="visible" :title="dialogTitle" width="820px" top="6vh" append-to-body>
    <div v-if="looking" class="py-10 text-center text-sm text-gray-400">查询书签中…</div>

    <div v-else-if="!current" class="py-10 text-center text-sm text-gray-400">
      {{ lookupFailed ? "该地址尚未收录为书签" : "没有可展示的书签" }}
    </div>

    <div v-else class="detail-body space-y-4 pr-1 text-sm">
      <!-- 非域名书签的说明条：把"为什么这页什么都没有"一次讲清楚，
           省得管理员对着一屏空字段当成抓取故障去排查 -->
      <div
        v-if="!scrapable"
        class="rounded border border-amber-200 bg-amber-50 px-3 py-2 text-amber-800 dark:border-amber-900 dark:bg-amber-950/40 dark:text-amber-200"
      >
        <div class="font-medium">{{ linkTypeLabel }}书签 · 不参与抓取</div>
        <div class="mt-1 text-xs leading-relaxed">
          {{ notScrapableReason }}。因此它没有标题、图标、页面元数据与巡检记录，
          相关卡片与操作已收起 —— 这不是抓取失败。
        </div>
      </div>

      <!-- 卡片一：基础信息 -->
      <ElCard shadow="never" class="detail-card">
        <template #header>
          <span class="font-medium">基础信息</span>
        </template>
        <div class="flex gap-6">
          <!-- 左：图标 + 状态 + 活跃 -->
          <div class="flex w-32 shrink-0 flex-col items-center gap-3">
            <!-- 这张图是「当前展示用的那张」，不属于下面任何一组，单张放大 -->
            <div
              class="icon-zoom"
              :class="{ 'is-zoomable': !!(logoOf(current) ?? faviconOf(current)) }"
              @click="openPreview([], logoOf(current) ?? faviconOf(current))"
            >
              <BookmarkIcon :src="logoOf(current) ?? faviconOf(current)" :size="64" />
            </div>
            <div class="flex flex-wrap justify-center gap-1">
              <ElTag :type="parseStatusMeta.type" size="small" :title="parseStatusMeta.tip">
                {{ parseStatusMeta.label }}
              </ElTag>
              <!-- 状态标签旁边直接标出类型：这类书签的"成功"是「无需抓取」而不是「抓到了」 -->
              <ElTag
                v-if="!scrapable"
                type="info"
                size="small"
                :title="notScrapableReason"
              >
                {{ linkTypeLabel }}·不抓取
              </ElTag>
              <ElTag v-if="current.antiCrawlerBlocked" type="warning" size="small">
                反爬拦截
              </ElTag>
              <ElTag v-if="current.nsfw" type="danger" size="small">NSFW</ElTag>
              <ElTag v-if="current.verifyFlag" type="success" size="small" title="已人工认证">
                已认证
              </ElTag>
            </div>
            <div class="flex items-center gap-2">
              <ElSwitch
                :model-value="current.isActivity"
                active-color="#13ce66"
                inactive-color="#ff4949"
                disabled
              />
              <span class="text-gray-500">{{ current.isActivity ? "活跃" : "不活跃" }}</span>
            </div>
          </div>
          <!-- 右：全部字段 -->
          <div class="min-w-0 flex-1 space-y-2">
            <div class="flex">
              <span class="w-20 shrink-0 text-gray-500">App Name</span>
              <span class="flex-1 font-medium break-all">{{ current.appName || "-" }}</span>
            </div>
            <div class="flex">
              <span class="w-20 shrink-0 text-gray-500">标题</span>
              <span class="flex-1 font-medium break-all">{{ current.title || "-" }}</span>
            </div>
            <div class="flex">
              <span class="w-20 shrink-0 text-gray-500">域名</span>
              <span class="flex-1 break-all">{{ current.urlHost }}</span>
            </div>
            <div class="flex">
              <span class="w-20 shrink-0 text-gray-500">完整地址</span>
              <a
                :href="currentUrl"
                target="_blank"
                rel="noopener noreferrer"
                class="flex-1 break-all text-blue-500"
              >
                {{ currentUrl }}
              </a>
            </div>
            <div class="flex">
              <span class="w-20 shrink-0 text-gray-500">描述</span>
              <span class="flex-1 break-all">{{ current.description || "-" }}</span>
            </div>
            <div class="flex">
              <span class="w-20 shrink-0 text-gray-500">创建时间</span>
              <span class="flex-1">{{ formatTime(current.createTime) }}</span>
            </div>
            <div class="flex">
              <span class="w-20 shrink-0 text-gray-500">更新时间</span>
              <span class="flex-1">{{ formatTime(current.updateTime) }}</span>
            </div>
            <div class="flex">
              <span class="w-20 shrink-0 text-gray-500">书签 ID</span>
              <span class="flex-1 break-all text-gray-400">{{ current.id }}</span>
            </div>
            <!-- 品牌名/图标/NSFW/域名活性都挂在站点上，站点 ID 是接上那一层的唯一钥匙 -->
            <div class="flex">
              <span class="w-20 shrink-0 text-gray-500">站点 ID</span>
              <span class="flex-1 break-all text-gray-400">{{ current.siteId || "-" }}</span>
            </div>
            <div v-if="current.parseErrMsg" class="flex">
              <span class="w-20 shrink-0 text-gray-500">错误信息</span>
              <span class="flex-1 break-all text-red-500">{{ current.parseErrMsg }}</span>
            </div>
          </div>
        </div>
      </ElCard>

      <!-- 卡片二：巡检调度 —— 回答「这条为什么还没被复查」「为什么一直没变」。
           非域名书签不进巡检队列（pingSweep 按 linkType 过滤掉了它们），这四个游标恒为空 -->
      <ElCard v-if="scrapable" shadow="never" class="detail-card">
        <template #header>
          <span class="font-medium">巡检与人工锁</span>
        </template>
        <div class="grid grid-cols-2 gap-x-6 gap-y-2">
          <div class="flex">
            <span class="w-24 shrink-0 text-gray-500">上次抓到内容</span>
            <span class="flex-1">{{ formatTime(current.lastParseAt) }}</span>
          </div>
          <div class="flex">
            <span class="w-24 shrink-0 text-gray-500">上次探测</span>
            <span class="flex-1">{{ formatTime(current.lastCheckAt) }}</span>
          </div>
          <div class="flex">
            <span class="w-24 shrink-0 text-gray-500">下次巡检</span>
            <span class="flex-1">{{ formatTime(current.nextCheckAt) }}</span>
          </div>
          <div class="flex">
            <span class="w-24 shrink-0 text-gray-500">连续失败</span>
            <span class="flex-1" :class="{ 'text-red-500': (current.consecutiveFail ?? 0) > 0 }">
              {{ current.consecutiveFail ?? 0 }} 次
            </span>
          </div>
          <div class="col-span-2 flex">
            <span class="w-24 shrink-0 text-gray-500">人工锁定字段</span>
            <span class="flex flex-1 flex-wrap gap-1">
              <ElTag
                v-for="f in current.lockedFields ?? []"
                :key="f"
                size="small"
                type="warning"
                title="管理员手工改过，自动抓取不会覆盖"
              >
                {{ BOOKMARK_LOCKED_FIELD_LABEL[f] ?? f }}
              </ElTag>
              <span v-if="(current.lockedFields ?? []).length === 0" class="text-gray-400">
                无（全部字段可被自动抓取覆盖）
              </span>
            </span>
          </div>
        </div>
      </ElCard>

      <!--
        卡片二·五：抓取元数据（page_meta）

        与上面的「标题/描述」不重复：那两个是**当前生效值**（可能被人工改过并加了锁），
        这里是**抓取原样**，外加主表根本没有的抓取事实 —— 走的哪一层、站点回的什么状态码、
        canonical 指向哪、每个字段各自从哪个标签取来的。
        「标题为什么是这个」「这一页到底抓没抓通」只有对着这张卡片才答得出来。
      -->
      <ElCard v-if="scrapable" shadow="never" class="detail-card">
        <template #header>
          <div class="flex items-center gap-3">
            <span class="font-medium">抓取元数据</span>
            <!-- 抓回来了但内容可能不对：抓失败的话主表 parseStatus 早就是 UNREACHABLE 了 -->
            <ElTag
              v-if="metaNeedsAttention(pageMeta)"
              type="warning"
              size="small"
              title="抓取层退到了无头浏览器、状态码非 2xx、或被判为反爬挑战页 —— 内容可能不可靠"
            >
              需关注
            </ElTag>
          </div>
        </template>

        <div v-if="!pageMeta" class="text-gray-400">
          该页面从未抓取成功过（page_meta 里没有这一行）
        </div>
        <div v-else class="grid grid-cols-2 gap-x-6 gap-y-2">
          <div class="flex">
            <span class="w-24 shrink-0 text-gray-500">抓取层</span>
            <span class="flex-1">
              <ElTag
                v-if="pageMeta.fetchLayer"
                :type="FETCH_LAYER_META[pageMeta.fetchLayer]?.type ?? 'info'"
                size="small"
                :title="FETCH_LAYER_META[pageMeta.fetchLayer]?.tip"
              >
                {{ FETCH_LAYER_META[pageMeta.fetchLayer]?.label ?? pageMeta.fetchLayer }}
              </ElTag>
              <span v-else class="text-gray-400">-</span>
            </span>
          </div>
          <div class="flex">
            <span class="w-24 shrink-0 text-gray-500">HTTP 状态</span>
            <span class="flex-1">
              <ElTag
                v-if="pageMeta.httpStatus"
                :type="httpStatusType(pageMeta.httpStatus)"
                size="small"
              >
                {{ pageMeta.httpStatus }}
              </ElTag>
              <span v-else class="text-gray-400">-</span>
            </span>
          </div>
          <div class="flex">
            <span class="w-24 shrink-0 text-gray-500">反爬挑战页</span>
            <span class="flex-1">
              <ElTag
                v-if="pageMeta.antiCrawler"
                type="warning"
                size="small"
                title="抓取侧判定这一页是反爬挑战页，下面的标题/描述都可能是拦截页的文案"
              >
                是
              </ElTag>
              <span v-else class="text-gray-400">否</span>
            </span>
          </div>
          <div class="flex">
            <span class="w-24 shrink-0 text-gray-500">抓取时间</span>
            <span class="flex-1">{{ formatTime(pageMeta.fetchedAt) }}</span>
          </div>
          <div class="flex">
            <span class="w-24 shrink-0 text-gray-500">页面语言</span>
            <span class="flex-1">{{ pageMeta.lang || "-" }}</span>
          </div>
          <div class="flex">
            <span class="w-24 shrink-0 text-gray-500">主题色</span>
            <span class="flex-1">
              <span
                v-if="pageMeta.themeColor"
                class="mr-1 inline-block h-3 w-3 rounded-sm border align-middle"
                :style="{ backgroundColor: pageMeta.themeColor }"
              />
              {{ pageMeta.themeColor || "-" }}
            </span>
          </div>
          <div class="flex">
            <span class="w-24 shrink-0 text-gray-500">站点名</span>
            <span class="flex-1 break-all">{{ pageMeta.siteName || "-" }}</span>
          </div>
          <div class="flex">
            <span class="w-24 shrink-0 text-gray-500">站点短名</span>
            <span class="flex-1 break-all">{{ pageMeta.siteShortName || "-" }}</span>
          </div>
          <div class="col-span-2 flex">
            <span class="w-24 shrink-0 text-gray-500">Canonical</span>
            <a
              v-if="pageMeta.canonicalUrl"
              :href="pageMeta.canonicalUrl"
              target="_blank"
              rel="noopener noreferrer"
              class="flex-1 break-all text-blue-500"
            >
              {{ pageMeta.canonicalUrl }}
            </a>
            <span v-else class="flex-1 text-gray-400">-</span>
          </div>
          <div class="col-span-2 flex">
            <span class="w-24 shrink-0 text-gray-500">抓取标题</span>
            <span class="flex-1 break-all">
              {{ pageMeta.title || "-" }}
              <ElTag
                v-if="titleDiverged"
                class="ml-1"
                type="warning"
                size="small"
                title="与上面「基础信息」里当前生效的标题不一致 —— 多半是被人工改过（看下面的人工锁），也可能是站点改了内容而该字段被锁住没跟上"
              >
                与当前生效值不一致
              </ElTag>
            </span>
          </div>
          <div class="col-span-2 flex">
            <span class="w-24 shrink-0 text-gray-500">抓取描述</span>
            <span class="flex-1 break-all">
              {{ pageMeta.description || "-" }}
              <ElTag
                v-if="descriptionDiverged"
                class="ml-1"
                type="warning"
                size="small"
                title="与上面「基础信息」里当前生效的描述不一致"
              >
                与当前生效值不一致
              </ElTag>
            </span>
          </div>
          <!-- 字段级出处：「这个标题是 og:title 来的还是 <title> 兜底来的」只有这里有 -->
          <div class="col-span-2 flex">
            <span class="w-24 shrink-0 text-gray-500">字段出处</span>
            <span
              v-if="pageMeta.metaSources"
              class="flex-1 break-all"
              :title="pageMeta.metaSources"
            >
              {{ formatMetaSources(pageMeta.metaSources) }}
            </span>
            <span v-else class="flex-1 text-gray-400">-</span>
          </div>
        </div>
      </ElCard>

      <!-- 卡片三：分类 -->
      <ElCard v-if="scrapable" shadow="never" class="detail-card">
        <template #header>
          <span class="font-medium">分类</span>
        </template>
        <div class="mb-2 flex flex-wrap gap-1">
          <ElTag
            v-for="c in current.categories ?? []"
            :key="c.id"
            size="small"
            :color="c.color || undefined"
            :style="c.color ? { color: '#fff', borderColor: c.color } : {}"
          >
            {{ c.name }}
          </ElTag>
          <span v-if="(current.categories ?? []).length === 0" class="text-gray-400">
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
          <ElButton type="primary" size="small" :loading="savingCategories" @click="saveCategories">
            保存分类
          </ElButton>
          <ElButton size="small" :loading="recategorizing" @click="recategorize">
            重新 AI 归类
          </ElButton>
        </div>
      </ElCard>

      <!-- 卡片四：图片资产 —— 后台刻意展示**全部**声明的图，而不是选好的那一张 -->
      <ElCard v-if="scrapable" shadow="never" class="detail-card">
        <template #header>
          <div class="flex items-center gap-3">
            <span class="font-medium">图片资产（{{ (current.assets ?? []).length }}）</span>
            <ElButton
              size="small"
              :loading="refetchingAssets"
              title="只重抓图片，不改标题/简介；本次没抓到时保留原有图片"
              @click="refetchAssets"
            >
              重新抓取
            </ElButton>
          </div>
        </template>
        <ul v-if="(current.assets ?? []).length > 0" class="space-y-2">
          <li
            v-for="a in current.assets"
            :key="a.id"
            class="flex gap-3 rounded border border-gray-100 p-2"
          >
            <BookmarkAssetCell
              :src="a.url"
              :wide="a.role === 'SOCIAL' || a.role === 'SCREENSHOT'"
              preview
              @preview="openPreview(assetUrls, $event)"
            />
            <div class="min-w-0 flex-1 space-y-1">
              <div class="flex flex-wrap items-center gap-1">
                <ElTag size="small" type="primary">
                  {{ ASSET_ROLE_LABEL[a.role] ?? a.role }}
                </ElTag>
                <!-- extractor 是抓取服务报告的事实（图从哪个标签来），悬浮给中文释义 -->
                <ElTag
                  size="small"
                  type="info"
                  :title="EXTRACTOR_LEGEND[a.extractor] ?? a.extractor"
                >
                  {{ a.extractor }}
                </ElTag>
                <ElTag size="small" :type="a.quality === 'TRUSTED' ? 'success' : 'warning'">
                  {{ a.quality === "TRUSTED" ? "可信" : "降级" }}
                </ElTag>
                <ElTag v-if="a.isPrimary" size="small" type="success">主图</ElTag>
                <ElTag v-if="a.isVector" size="small" type="info">矢量</ElTag>
                <!--
                  图标归页面层是**例外**，只在该页被判成"同域下的另一个产品"时出现，
                  所以只给例外打标；社交图/截图本来就归页面层，标了纯属噪音
                -->
                <ElTag
                  v-if="a.ownerType === 'PAGE' && (a.role === 'FAVICON' || a.role === 'LOGO')"
                  size="small"
                  type="warning"
                  title="这一页有自己的一套图标(与站点图标字节毫无交集)，被判定为同域下的另一个产品，因此图标不与全站共享"
                >
                  本页专属图标
                </ElTag>
                <ElTag
                  v-if="a.duplicateOfOther"
                  size="small"
                  type="warning"
                  title="与本书签其它资产字节相同 —— 说明该站没有独立 LOGO"
                >
                  与其它图重复
                </ElTag>
              </div>
              <div class="text-gray-500">
                {{ a.width && a.height ? `${a.width}×${a.height}` : "尺寸未知" }}
                · {{ formatBytes(a.byteSize) }}
                · {{ a.mime || "类型未知" }}
              </div>
              <div v-if="a.contentHash" class="truncate text-xs text-gray-400" :title="a.contentHash">
                hash: {{ a.contentHash }}
              </div>
              <div class="truncate text-xs text-gray-400" :title="a.resolvedUrl">
                源地址: {{ a.resolvedUrl }}
              </div>
              <div v-if="a.errorMsg" class="break-all text-red-500">{{ a.errorMsg }}</div>
            </div>
          </li>
        </ul>
        <div v-else class="text-gray-400">该站没有抓到任何图片</div>
      </ElCard>

      <!-- 卡片五：显示设置 —— 大图/列表两种模式的取图优先级相反，所以按模式分行 -->
      <ElCard v-if="scrapable" shadow="never" class="detail-card">
        <template #header>
          <span class="font-medium">显示设置</span>
        </template>
        <ul v-if="(current.displayPrefs ?? []).length > 0" class="space-y-2">
          <li
            v-for="p in current.displayPrefs"
            :key="p.displayMode"
            class="flex items-center gap-3 rounded border border-gray-100 p-2"
          >
            <BookmarkAssetCell
              :src="p.previewUrl"
              preview
              @preview="openPreview(displayPrefUrls, $event)"
            />
            <div class="min-w-0 flex-1 space-y-1">
              <div class="flex flex-wrap items-center gap-1 font-medium">
                <span>{{ DISPLAY_MODE_LABEL[p.displayMode] ?? p.displayMode }}</span>
                <ElTag
                  v-if="p.monogram"
                  size="small"
                  type="warning"
                  title="该模式下没有合适的图，会渲染首字母色块"
                >
                  首字母色块
                </ElTag>
                <ElTag v-if="p.pinnedAssetId" size="small" type="info">已钉图</ElTag>
              </div>
              <div class="text-gray-500">
                内边距 {{ p.iconPadding }}% · 背景
                <span
                  v-if="p.iconBgColor"
                  class="ml-1 inline-block h-3 w-3 rounded-sm border align-middle"
                  :style="{ backgroundColor: p.iconBgColor }"
                />
                {{ p.iconBgColor || "默认" }}
              </div>
            </div>
          </li>
        </ul>
        <div v-else class="text-gray-400">未设置，按默认值渲染</div>
      </ElCard>

      <!-- 卡片六：关联网站 —— 同域名下已收录的其它页面 + AI 推荐的相似站点 -->
      <ElCard v-if="scrapable" shadow="never" class="detail-card">
        <template #header>
          <div class="flex items-center gap-3">
            <span class="font-medium">关联网站</span>
            <ElButton
              size="small"
              :loading="loadingSimilar"
              title="调用 DeepSeek 推荐功能/定位相似的其它网站"
              @click="findSimilar"
            >
              查找相似网站
            </ElButton>
          </div>
        </template>

        <div class="mb-1 text-gray-500">
          同域名下的其它页面（{{ siblings.length }}）
        </div>
        <div v-if="loadingSiblings" class="text-gray-400">加载中…</div>
        <ul v-else-if="siblings.length > 0" class="space-y-2">
          <li
            v-for="s in siblings"
            :key="s.id"
            class="flex items-center gap-3 rounded border border-gray-100 p-2"
          >
            <BookmarkAssetCell
              :src="logoOf(s) ?? faviconOf(s)"
              preview
              @preview="openPreview(siblingIconUrls, $event)"
            />
            <div class="min-w-0 flex-1">
              <div class="truncate font-medium" :title="s.title || s.appName || ''">
                {{ s.title || s.appName || "（无标题）" }}
              </div>
              <a
                :href="siblingUrl(s)"
                target="_blank"
                rel="noopener noreferrer"
                class="block truncate text-xs text-blue-500"
                :title="siblingUrl(s)"
              >
                {{ s.urlPath || "/" }}
              </a>
            </div>
            <ElTag
              :type="PARSE_STATUS_META[s.parseStatus]?.type ?? 'info'"
              size="small"
              class="shrink-0"
            >
              {{ PARSE_STATUS_META[s.parseStatus]?.label ?? s.parseStatus }}
            </ElTag>
          </li>
        </ul>
        <div v-else class="text-gray-400">该域名下只收录了当前这一个页面</div>

        <div class="mt-4 mb-1 text-gray-500">AI 推荐的相似网站</div>
        <ul v-if="similarLoaded && similarSites.length > 0" class="space-y-2">
          <li v-for="s in similarSites" :key="s.domain" class="rounded border border-gray-100 p-2">
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
              <ElTag v-if="ingestStatus[s.domain] === 'LOADING'" type="info" size="small">
                收录中
              </ElTag>
              <ElTag v-else-if="ingestStatus[s.domain] === 'INGESTED'" type="success" size="small">
                已收录
              </ElTag>
              <ElTag v-else-if="ingestStatus[s.domain] === 'SKIPPED'" type="danger" size="small">
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
        <div v-else-if="similarLoaded" class="text-gray-400">未找到相似网站</div>
        <div v-else class="text-gray-400">尚未查询，点击上方「查找相似网站」</div>
        <div v-if="similarSites.length > 0" class="mt-3 flex justify-end">
          <ElButton
            type="primary"
            size="small"
            :loading="ingesting"
            :disabled="ingestTargets.length === 0"
            @click="oneClickIngest"
          >
            一键收录{{ ingestTargets.length ? `（${ingestTargets.length}）` : "" }}
          </ElButton>
        </div>
      </ElCard>

      <!-- 卡片七：活性检测结果（点底部按钮后出现，直接展示 scrapper 返回的全部字段） -->
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
            <ElTag v-if="livenessResult.cached" type="info" size="small">命中缓存</ElTag>
          </div>
          <div v-if="livenessResult.title" class="flex">
            <span class="w-20 shrink-0 text-gray-500">新标题</span>
            <span class="flex-1 break-all">{{ livenessResult.title }}</span>
          </div>
          <div v-if="livenessResult.description" class="flex">
            <span class="w-20 shrink-0 text-gray-500">新描述</span>
            <span class="flex-1 break-all">{{ livenessResult.description }}</span>
          </div>
          <div v-if="livenessResult.errorMsg" class="flex">
            <span class="w-20 shrink-0 text-gray-500">错误信息</span>
            <span class="flex-1 break-all text-red-500">{{ livenessResult.errorMsg }}</span>
          </div>
          <div
            v-if="
              livenessResult.favicon ||
              livenessResult.logo ||
              livenessResult.image ||
              livenessResult.screenshot
            "
            class="flex flex-wrap gap-4 pt-1"
          >
            <div v-if="livenessResult.favicon" class="flex flex-col items-center gap-1">
              <img
                :src="livenessResult.favicon"
                alt="favicon"
                class="zoomable h-10 w-10 rounded border object-contain"
                @click="openPreview(livenessImageUrls, livenessResult.favicon)"
              />
              <span class="text-xs text-gray-400">favicon</span>
            </div>
            <div v-if="livenessResult.logo" class="flex flex-col items-center gap-1">
              <img
                :src="livenessResult.logo"
                alt="logo"
                class="zoomable h-10 w-10 rounded border object-contain"
                @click="openPreview(livenessImageUrls, livenessResult.logo)"
              />
              <span class="text-xs text-gray-400">logo</span>
            </div>
            <div v-if="livenessResult.image" class="flex flex-col items-center gap-1">
              <img
                :src="livenessResult.image"
                alt="image"
                class="zoomable h-16 w-28 rounded border object-cover"
                @click="openPreview(livenessImageUrls, livenessResult.image)"
              />
              <span class="text-xs text-gray-400">image</span>
            </div>
            <div v-if="livenessResult.screenshot" class="flex flex-col items-center gap-1">
              <img
                :src="livenessResult.screenshot"
                alt="screenshot"
                class="zoomable h-16 w-28 rounded border object-cover"
                @click="openPreview(livenessImageUrls, livenessResult.screenshot)"
              />
              <span class="text-xs text-gray-400">screenshot</span>
            </div>
          </div>
        </div>
      </ElCard>
    </div>

    <template #footer>
      <!-- 调用方追加的页面专属操作（如图标平铺页的「图标设置」） -->
      <slot name="extra-actions" :bookmark="current" />
      <!-- 非域名书签不给点：这次检测后端一定会拒（E309），点了只会弹一条报错 -->
      <ElButton
        :disabled="!current || !scrapable"
        :loading="checkingLiveness"
        :title="scrapable ? undefined : `${linkTypeLabel}不参与抓取，无法检测活性`"
        @click="checkLiveness"
      >
        检测活性
      </ElButton>
      <ElButton @click="visible = false">关闭</ElButton>
    </template>
  </ElDialog>

  <!-- 查看器 teleport 到 body，层级在弹窗之上；ESC 由它自己吃掉，不会连带关闭详情弹窗 -->
  <ElImageViewer
    v-if="previewList.length > 0"
    :url-list="previewList"
    :initial-index="previewIndex"
    teleported
    hide-on-click-modal
    @close="closePreview"
  />
</template>

<style scoped>
/* 详情字段很多，弹窗自身撑不下，正文单独滚动，底部按钮固定可见 */
.detail-body {
  max-height: 66vh;
  overflow-y: auto;
}

.detail-card :deep(.el-card__header) {
  padding: 10px 16px;
}

.detail-card :deep(.el-card__body) {
  padding: 16px;
}

.icon-zoom.is-zoomable {
  cursor: zoom-in;
}

.icon-zoom.is-zoomable:hover :deep(.icon-card) {
  transform: scale(1.05);
}

.zoomable {
  cursor: zoom-in;
  transition: border-color 0.15s ease;
}

.zoomable:hover {
  border-color: var(--el-color-primary);
}
</style>
