<script lang="ts" setup>
import type { BookmarkEntity, BookmarkParseStatus } from "#/api/bookmark";
import type { SiteAdminVO, SiteLinkType, SiteSearchParams } from "#/api/site";
import type { UserAdminVO } from "#/api/user-manage";

import { computed, defineAsyncComponent, onMounted, reactive, ref } from "vue";
import { useRoute, useRouter } from "vue-router";

import { Page } from "@vben/common-ui";
import { formatDateTime } from "@vben/utils";

import { ElMessage } from "element-plus";

import { getBookmarkListApi, refreshBookmarkApi } from "#/api/bookmark";
import { abnormalPageCount, getSiteDetailApi, getSiteListApi } from "#/api/site";
import { useVbenVxeGrid, type VxeGridProps } from "#/adapter/vxe-table";

import BookmarkAssetCell from "#/views/bookmark/BookmarkAssetCell.vue";
import BookmarkDetailDialog from "#/views/bookmark/BookmarkDetailDialog.vue";
import { assetByRole, socialOf } from "#/views/bookmark/siteAsset";
import UserDetailDialog from "#/views/user/UserDetailDialog.vue";
import UserIdentityCell from "#/views/user/UserIdentityCell.vue";

import SiteHealthBar from "./SiteHealthBar.vue";

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

const ElInputNumber = defineAsyncComponent(() =>
  Promise.all([
    import("element-plus/es/components/input-number/index"),
    import("element-plus/es/components/input-number/style/css"),
  ]).then(([res]) => res.ElInputNumber),
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

const ElDatePicker = defineAsyncComponent(() =>
  Promise.all([
    import("element-plus/es/components/date-picker/index"),
    import("element-plus/es/components/date-picker/style/css"),
  ]).then(([res]) => res.ElDatePicker),
);

const ElButton = defineAsyncComponent(() =>
  Promise.all([
    import("element-plus/es/components/button/index"),
    import("element-plus/es/components/button/style/css"),
  ]).then(([res]) => res.ElButton),
);

const ElLink = defineAsyncComponent(() =>
  Promise.all([
    import("element-plus/es/components/link/index"),
    import("element-plus/es/components/link/style/css"),
  ]).then(([res]) => res.ElLink),
);

const ElPagination = defineAsyncComponent(() =>
  Promise.all([
    import("element-plus/es/components/pagination/index"),
    import("element-plus/es/components/pagination/style/css"),
  ]).then(([res]) => res.ElPagination),
);

const ElEmpty = defineAsyncComponent(() =>
  Promise.all([
    import("element-plus/es/components/empty/index"),
    import("element-plus/es/components/empty/style/css"),
  ]).then(([res]) => res.ElEmpty),
);

const LINK_TYPE_META: Record<
  SiteLinkType,
  { label: string; tip: string; type: "danger" | "info" | "success" | "warning" }
> = {
  DOMAIN: { label: "域名", tip: "正常网站地址，会抓取标题/图标", type: "success" },
  LOCAL: { label: "本地", tip: "localhost / 127.0.0.1 / ::1，不会被抓取", type: "info" },
  IP: { label: "IP", tip: "纯 IP 地址，不会被抓取", type: "warning" },
  OTHER: { label: "其他", tip: "未归类", type: "info" },
};

const LOCKED_FIELD_LABEL: Record<string, string> = {
  BRAND_NAME: "全名已锁定",
  SHORT_NAME: "短名已锁定",
};

const PARSE_STATUS_META: Record<
  BookmarkParseStatus,
  { label: string; tip?: string; type: "danger" | "info" | "success" | "warning" }
> = {
  PENDING: { label: "等待中", type: "info" },
  SUCCESS: { label: "成功", type: "success" },
  UNREACHABLE: { label: "抓取失败", type: "danger" },
  ARCHIVED: {
    label: "已归档",
    tip: "连续失败达到阈值，已停止巡检；手动刷新/检测可恢复",
    type: "warning",
  },
};

/** 三态筛选统一用这套选项：不选=不筛，与后端 null=不筛一致，别拿哨兵值代替 */
const BOOL_OPTIONS = [
  { label: "是", value: true },
  { label: "否", value: false },
];

// ──────────────────────────────────────────────────────────────
// 左侧：站点选择器
//
// 刻意不是表格。它是个「选择器」——每项只承担 favicon / 域名 / 页面数 / 异常数 / 健康条，
// 站点层那些成列的字段(锁定字段、认证、上次探测)在右侧摘要条上展开。正因为它没有列，
// 顶部那条七项筛选栏才能横跨全宽而不必挤进窄栏。
// ──────────────────────────────────────────────────────────────

type Filters = Omit<SiteSearchParams, "currentPage" | "pageSize" | "sortAsc" | "sortField"> & {
  /** ElDatePicker 的区间值，提交前拆成 createTimeStart / createTimeEnd */
  createRange?: [string, string];
};

const searchForm = reactive<Filters>({
  keyword: "",
  linkType: undefined,
  nsfw: undefined,
  alive: undefined,
  verifyFlag: undefined,
  brandNameEmpty: undefined,
  minConsecutiveFail: undefined,
  createRange: undefined,
});

const sites = ref<SiteAdminVO[]>([]);
const sitesTotal = ref(0);
const sitesLoading = ref(false);
const sitePager = reactive({ currentPage: 1, pageSize: 30 });

const selectedSite = ref<null | SiteAdminVO>(null);

const route = useRoute();
const router = useRouter();

async function loadSites() {
  sitesLoading.value = true;
  try {
    const res = await getSiteListApi({
      keyword: searchForm.keyword || undefined,
      linkType: searchForm.linkType,
      nsfw: searchForm.nsfw,
      alive: searchForm.alive,
      verifyFlag: searchForm.verifyFlag,
      brandNameEmpty: searchForm.brandNameEmpty,
      minConsecutiveFail: searchForm.minConsecutiveFail ?? undefined,
      createTimeStart: searchForm.createRange?.[0],
      createTimeEnd: searchForm.createRange?.[1],
      currentPage: sitePager.currentPage,
      pageSize: sitePager.pageSize,
    });
    sites.value = res.records;
    sitesTotal.value = res.total;
    // 选中的站点若也在本页，换成列表里这份：两处显示同一个站点却各拿一份快照的话，
    // 刷新后左边计数变了而右边摘要条没变，看起来就是「保存没生效」
    const fresh = res.records.find((s) => s.id === selectedSite.value?.id);
    if (fresh) selectedSite.value = fresh;
  } catch {
    // 这个函数被几处事件处理器裸调用(搜索/重置/翻页)，不接住就是未捕获的 promise rejection；
    // 在 onMounted 里还会把后面的初始化一起中断掉。提示已由请求拦截器弹出，这里只清空列表，
    // 免得旧数据配着新筛选条件留在屏幕上，看起来像"筛选没生效"
    sites.value = [];
    sitesTotal.value = 0;
  } finally {
    sitesLoading.value = false;
  }
}

function handleSearch() {
  sitePager.currentPage = 1;
  loadSites();
}

function handleReset() {
  searchForm.keyword = "";
  searchForm.linkType = undefined;
  searchForm.nsfw = undefined;
  searchForm.alive = undefined;
  searchForm.verifyFlag = undefined;
  searchForm.brandNameEmpty = undefined;
  searchForm.minConsecutiveFail = undefined;
  searchForm.createRange = undefined;
  sitePager.currentPage = 1;
  loadSites();
}

function handleSitePageChange(current: number) {
  sitePager.currentPage = current;
  loadSites();
}

/**
 * 选中一个站点。
 *
 * [status] 由健康条的分段点击带入 —— 点红色那段的语义是「我要看这个站失败的那些页面」，
 * 落到右侧就该是已经过滤好的，再让人手动去选一次状态是白丢一次上下文。
 */
function selectSite(site: SiteAdminVO, status?: BookmarkParseStatus) {
  // 表格挂在 `v-if="selectedSite"` 后面：首次选中时它还不存在，此刻 gridApi.grid 仍是空对象，
  // reload 会在它自己的 try/catch 里抛掉、只留一行日志。而这次不 reload 也是对的 ——
  // 表格随即被挂上，适配器默认 proxyConfig.autoLoad=true 会自己发起首查，
  // 再 reload 一次就是同一份数据查两遍
  const gridMounted = selectedSite.value !== null;
  selectedSite.value = site;
  pageFilters.name = "";
  pageFilters.status = status;
  // 选中态进 URL：刷新、切 vben 页签回来都还在，也让别处能深链到某个站点的页面列表
  router.replace({ query: { ...route.query, siteId: site.id } });
  if (gridMounted) gridApi.reload();
}

// ──────────────────────────────────────────────────────────────
// 右侧：该站点下的页面
// ──────────────────────────────────────────────────────────────

const pageFilters = reactive<{ name: string; status: BookmarkParseStatus | undefined }>({
  name: "",
  status: undefined,
});

const detailVisible = ref(false);
const currentRow = ref<BookmarkEntity | null>(null);

const userVisible = ref(false);
const currentUser = ref<null | UserAdminVO>(null);

const refreshingMap = reactive<Record<string, boolean>>({});

/**
 * 站内相对地址。域名已经是这一栏的上下文，逐行重复 `https://www.youtube.com` 是纯噪声。
 *
 * query 与 fragment 必须带上：整列都是同域同路径的深链时，只显示 path 会让
 * `/watch?v=A` 和 `/watch?v=B` 变成看起来一模一样的两行。库里这两列不含 `?` / `#`。
 */
function relativePath(row: BookmarkEntity) {
  const path = row.urlPath || "/";
  const query = row.urlQuery ? `?${row.urlQuery}` : "";
  const fragment = row.urlFragment ? `#${row.urlFragment}` : "";
  return `${path}${query}${fragment}`;
}

function fullUrl(row: BookmarkEntity) {
  return `${row.urlScheme}://${row.urlHost}${relativePath(row)}`;
}

function handleOwnerClick(row: BookmarkEntity) {
  if (!row.owner) return;
  currentUser.value = row.owner;
  userVisible.value = true;
}

function handleRowClick({ row, column }: { column: any; row: BookmarkEntity }) {
  // 操作列与收录者列有自己的点击语义，落到这里会把页面详情一起弹出来
  if (column?.field === "rowActions" || column?.field === "owner") return;
  currentRow.value = row;
  detailVisible.value = true;
}

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
    // 抓取服务不可用(E307)等已由请求拦截器弹出提示，这里只需别留下未捕获的 rejection
  } finally {
    refreshingMap[row.id] = false;
  }
}

function handlePageSearch() {
  gridApi.reload();
}

const gridOptions: VxeGridProps<BookmarkEntity> = {
  id: "admin-website-explorer-pages",
  columns: [
    { field: "urlPath", title: "站内地址", minWidth: 240, slots: { default: "path" } },
    { field: "title", title: "标题", minWidth: 200, slots: { default: "title" } },
    // favicon 与 logo 刻意不在这里：它们是**站点级**资产，整列 200 行会渲染出 200 个
    // 一模一样的图标。它们在右上角的站点摘要条里出现一次就够了。社交图是页面级的，留着。
    { field: "assetSocial", title: "社交图", width: 90, slots: { default: "social" } },
    { field: "parseStatus", title: "状态", width: 110, slots: { default: "parseStatus" } },
    {
      field: "antiCrawlerBlocked",
      title: "反爬拦截",
      width: 90,
      slots: { default: "antiCrawlerBlocked" },
    },
    { field: "owner", title: "收录用户", minWidth: 160, slots: { default: "owner" } },
    { field: "consecutiveFail", title: "连续失败", width: 90, align: "right" },
    {
      field: "lastCheckAt",
      title: "上次探测",
      width: 170,
      formatter: ({ cellValue }) => (cellValue ? formatDateTime(cellValue) : "-"),
    },
    {
      field: "updateTime",
      title: "更新时间",
      width: 170,
      formatter: ({ cellValue }) => (cellValue ? formatDateTime(cellValue) : "-"),
    },
    { field: "rowActions", title: "操作", width: 90, fixed: "right", slots: { default: "actions" } },
  ],
  toolbarConfig: { custom: true, refresh: true },
  pagerConfig: {},
  proxyConfig: {
    ajax: {
      query: async ({ page }) => {
        const siteId = selectedSite.value?.id;
        // 没选站点时不发请求：这里一旦漏掉 siteId 就会变成"全站页面列表"，
        // 而那正是本视图要避免的那张把域名层淹掉的平表
        if (!siteId) return { items: [], total: 0 };
        const res = await getBookmarkListApi({
          siteId,
          name: pageFilters.name || undefined,
          status: pageFilters.status || undefined,
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

onMounted(async () => {
  const siteId = route.query.siteId;
  if (typeof siteId === "string" && siteId) {
    // 「站点已被清理」(null) 与「这次请求失败了」(抛错) 必须分开：
    // 只有前者才该摘掉 URL 上的 id，把一次网络抖动也当成前者，等于悄悄吞了用户的深链
    const site = await getSiteDetailApi(siteId).catch(() => undefined);
    if (site) selectedSite.value = site;
    else if (site === null) router.replace({ query: { ...route.query, siteId: undefined } });
  }
  await loadSites();
  // 这里刻意不 reload：表格在 selectedSite 被赋值后才由 v-if 挂上，
  // 适配器默认的 proxyConfig.autoLoad 会带着 siteId 自己发起首查
});

const selectedAbnormal = computed(() =>
  selectedSite.value ? abnormalPageCount(selectedSite.value) : 0,
);
</script>

<template>
  <Page auto-content-height>
    <div class="explorer">
      <ElCard shadow="never" class="explorer__filters" :body-style="{ padding: '14px 16px' }">
        <div class="search-bar">
          <ElForm :model="searchForm" label-position="left" @submit.prevent="handleSearch">
            <div class="flex flex-wrap items-center gap-x-6 gap-y-3">
              <ElFormItem label="搜索" class="!mb-0">
                <ElInput
                  v-model="searchForm.keyword"
                  placeholder="域名 / 站点全名 / 短名"
                  clearable
                  style="width: 200px"
                  @keyup.enter="handleSearch"
                />
              </ElFormItem>
              <ElFormItem label="类型" class="!mb-0">
                <ElSelect
                  v-model="searchForm.linkType"
                  placeholder="全部"
                  clearable
                  style="width: 110px"
                >
                  <ElOption
                    v-for="(meta, value) in LINK_TYPE_META"
                    :key="value"
                    :label="meta.label"
                    :value="value"
                  />
                </ElSelect>
              </ElFormItem>
              <ElFormItem label="域名活性" class="!mb-0">
                <ElSelect
                  v-model="searchForm.alive"
                  placeholder="全部"
                  clearable
                  style="width: 105px"
                >
                  <ElOption label="可达" :value="true" />
                  <ElOption label="不可达" :value="false" />
                </ElSelect>
              </ElFormItem>
              <ElFormItem label="NSFW" class="!mb-0">
                <ElSelect
                  v-model="searchForm.nsfw"
                  placeholder="全部"
                  clearable
                  style="width: 95px"
                >
                  <ElOption
                    v-for="o in BOOL_OPTIONS"
                    :key="String(o.value)"
                    :label="o.label"
                    :value="o.value"
                  />
                </ElSelect>
              </ElFormItem>
              <ElFormItem label="人工认证" class="!mb-0">
                <ElSelect
                  v-model="searchForm.verifyFlag"
                  placeholder="全部"
                  clearable
                  style="width: 95px"
                >
                  <ElOption
                    v-for="o in BOOL_OPTIONS"
                    :key="String(o.value)"
                    :label="o.label"
                    :value="o.value"
                  />
                </ElSelect>
              </ElFormItem>
              <ElFormItem label="站点全名" class="!mb-0">
                <ElSelect
                  v-model="searchForm.brandNameEmpty"
                  placeholder="全部"
                  clearable
                  style="width: 105px"
                >
                  <ElOption label="为空" :value="true" />
                  <ElOption label="已抓到" :value="false" />
                </ElSelect>
              </ElFormItem>
              <ElFormItem label="连续失败 ≥" class="!mb-0">
                <ElInputNumber
                  v-model="searchForm.minConsecutiveFail"
                  :min="1"
                  :step="1"
                  controls-position="right"
                  style="width: 105px"
                />
              </ElFormItem>
              <ElFormItem label="收录时间" class="!mb-0">
                <ElDatePicker
                  v-model="searchForm.createRange"
                  type="daterange"
                  value-format="YYYY-MM-DDTHH:mm:ss"
                  :default-time="[new Date(2000, 0, 1, 0, 0, 0), new Date(2000, 0, 1, 23, 59, 59)]"
                  start-placeholder="开始"
                  end-placeholder="结束"
                  style="width: 240px"
                />
              </ElFormItem>
              <ElFormItem class="!mb-0 ml-auto">
                <ElButton type="primary" @click="handleSearch">搜索</ElButton>
                <ElButton class="ml-2" @click="handleReset">重置</ElButton>
              </ElFormItem>
            </div>
          </ElForm>
        </div>
      </ElCard>

      <div class="explorer__body">
        <!-- 左：站点选择器。自带分页，与右侧表格的分页器是**平级**的两个，不是嵌套 -->
        <ElCard
          shadow="never"
          class="site-pane"
          :body-style="{ padding: '0', display: 'flex', flexDirection: 'column', height: '100%' }"
        >
          <div class="site-pane__head">
            <span>站点</span>
            <span class="text-xs text-gray-400">共 {{ sitesTotal }} 个</span>
          </div>
          <div v-loading="sitesLoading" class="site-pane__list">
            <div
              v-for="site in sites"
              :key="site.id"
              class="site-item"
              :class="{ 'site-item--active': site.id === selectedSite?.id }"
              @click="selectSite(site)"
            >
              <div class="site-item__row">
                <BookmarkAssetCell :src="assetByRole(site.assets, 'FAVICON')?.url" />
                <div class="site-item__name">
                  <div class="site-item__host" :title="site.host">{{ site.host }}</div>
                  <div class="site-item__brand">
                    {{ site.brandName || site.shortName || "—" }}
                  </div>
                </div>
                <div class="site-item__counts">
                  <span class="site-item__total" title="已收录页面数">{{ site.pageCount }}</span>
                  <ElTag
                    v-if="abnormalPageCount(site) > 0"
                    type="danger"
                    size="small"
                    disable-transitions
                    :title="`${abnormalPageCount(site)} 个页面不是抓取成功状态`"
                  >
                    {{ abnormalPageCount(site) }}
                  </ElTag>
                </div>
              </div>
              <SiteHealthBar
                :counts="site.pageStatusCounts"
                interactive
                @pick="(status) => selectSite(site, status)"
              />
              <div v-if="!site.isAlive || site.nsfw" class="site-item__badges">
                <ElTag v-if="!site.isAlive" type="danger" size="small" disable-transitions>
                  域名不可达
                </ElTag>
                <ElTag v-if="site.nsfw" type="danger" size="small" disable-transitions>
                  NSFW
                </ElTag>
              </div>
            </div>
            <ElEmpty v-if="!sitesLoading && sites.length === 0" description="没有符合条件的站点" />
          </div>
          <div class="site-pane__pager">
            <ElPagination
              small
              layout="prev, pager, next"
              :pager-count="5"
              :current-page="sitePager.currentPage"
              :page-size="sitePager.pageSize"
              :total="sitesTotal"
              @current-change="handleSitePageChange"
            />
          </div>
        </ElCard>

        <!-- 右：该站点下的页面 -->
        <ElCard
          shadow="never"
          class="page-pane"
          :body-style="{ padding: '0', display: 'flex', flexDirection: 'column', height: '100%' }"
        >
          <template v-if="selectedSite">
            <!-- 站点摘要条：站点层那些不适合成列的信息(锁定字段、NSFW 理由、两张图标)的去处 -->
            <div class="site-summary">
              <div class="site-summary__assets">
                <BookmarkAssetCell :src="assetByRole(selectedSite.assets, 'FAVICON')?.url" />
                <BookmarkAssetCell :src="assetByRole(selectedSite.assets, 'LOGO')?.url" />
              </div>
              <div class="site-summary__main">
                <div class="site-summary__title">
                  <ElLink
                    type="primary"
                    :href="selectedSite.rootUrl"
                    target="_blank"
                    rel="noopener"
                  >
                    {{ selectedSite.host }}
                  </ElLink>
                  <span v-if="selectedSite.brandName" class="site-summary__brand">
                    {{ selectedSite.brandName }}
                  </span>
                  <ElTag
                    :type="LINK_TYPE_META[selectedSite.linkType]?.type ?? 'info'"
                    size="small"
                    disable-transitions
                    :title="LINK_TYPE_META[selectedSite.linkType]?.tip"
                  >
                    {{ LINK_TYPE_META[selectedSite.linkType]?.label ?? selectedSite.linkType }}
                  </ElTag>
                  <ElTag
                    :type="selectedSite.isAlive ? 'success' : 'danger'"
                    size="small"
                    disable-transitions
                  >
                    {{ selectedSite.isAlive ? "域名可达" : "域名不可达" }}
                  </ElTag>
                  <ElTag
                    v-if="selectedSite.nsfw"
                    type="danger"
                    size="small"
                    :title="selectedSite.nsfwReason"
                  >
                    NSFW
                  </ElTag>
                  <ElTag
                    v-if="selectedSite.verifyFlag"
                    type="success"
                    size="small"
                    title="品牌名与图标已人工核对，抓取不再覆盖"
                  >
                    已认证
                  </ElTag>
                  <ElTag
                    v-for="f in selectedSite.lockedFields"
                    :key="f"
                    type="warning"
                    size="small"
                    disable-transitions
                  >
                    {{ LOCKED_FIELD_LABEL[f] ?? f }}
                  </ElTag>
                </div>
                <div class="site-summary__meta">
                  <span>{{ selectedSite.pageCount }} 个页面</span>
                  <span v-if="selectedAbnormal > 0" class="site-summary__bad">
                    {{ selectedAbnormal }} 个异常
                  </span>
                  <span>短名：{{ selectedSite.shortName || "—" }}</span>
                  <span>连续失败：{{ selectedSite.consecutiveFail }}</span>
                  <span>
                    上次探测：{{
                      selectedSite.lastCheckAt ? formatDateTime(selectedSite.lastCheckAt) : "—"
                    }}
                  </span>
                </div>
              </div>
            </div>

            <div class="page-pane__filters">
              <ElInput
                v-model="pageFilters.name"
                placeholder="标题 / 描述"
                clearable
                size="small"
                style="width: 200px"
                @keyup.enter="handlePageSearch"
                @clear="handlePageSearch"
              />
              <ElSelect
                v-model="pageFilters.status"
                placeholder="全部状态"
                clearable
                size="small"
                style="width: 130px"
                @change="handlePageSearch"
              >
                <ElOption
                  v-for="(meta, value) in PARSE_STATUS_META"
                  :key="value"
                  :label="meta.label"
                  :value="value"
                />
              </ElSelect>
              <ElButton type="primary" size="small" @click="handlePageSearch">搜索</ElButton>
            </div>

            <div class="page-pane__grid">
              <Grid>
                <template #path="{ row }">
                  <ElLink
                    type="primary"
                    :href="fullUrl(row)"
                    target="_blank"
                    rel="noopener"
                    :title="fullUrl(row)"
                    @click.stop
                  >
                    {{ relativePath(row) }}
                  </ElLink>
                </template>
                <template #title="{ row }">
                  <span v-if="row.title" :title="row.title">{{ row.title }}</span>
                  <span v-else class="text-gray-400">-</span>
                </template>
                <template #social="{ row }">
                  <BookmarkAssetCell :src="socialOf(row)" wide />
                </template>
                <template #parseStatus="{ row }">
                  <ElTag
                    :type="PARSE_STATUS_META[row.parseStatus]?.type ?? 'info'"
                    size="small"
                    disable-transitions
                    :title="PARSE_STATUS_META[row.parseStatus]?.tip"
                  >
                    {{ PARSE_STATUS_META[row.parseStatus]?.label ?? row.parseStatus }}
                  </ElTag>
                </template>
                <template #antiCrawlerBlocked="{ row }">
                  <ElTag v-if="row.antiCrawlerBlocked" type="warning" size="small">反爬拦截</ElTag>
                  <span v-else class="text-gray-400">-</span>
                </template>
                <template #owner="{ row }">
                  <div v-if="row.owner" class="owner-cell" @click.stop="handleOwnerClick(row)">
                    <UserIdentityCell :user="row.owner" />
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
                <template #actions="{ row }">
                  <ElButton
                    link
                    type="primary"
                    :loading="refreshingMap[row.id]"
                    @click.stop="handleRefresh(row)"
                  >
                    更新
                  </ElButton>
                </template>
              </Grid>
            </div>
          </template>

          <div v-else class="page-pane__placeholder">
            <ElEmpty description="从左侧选择一个站点，查看它下面的页面" />
          </div>
        </ElCard>
      </div>

      <BookmarkDetailDialog v-model="detailVisible" :bookmark="currentRow" />
      <UserDetailDialog v-model="userVisible" :user="currentUser" />
    </div>
  </Page>
</template>

<style scoped>
.explorer {
  display: flex;
  flex-direction: column;
  gap: 12px;
  height: 100%;
  min-height: 0;
}

.explorer__filters {
  flex-shrink: 0;
}

.explorer__body {
  display: flex;
  flex: 1;
  gap: 12px;
  min-height: 0;
}

/* ── 左：站点选择器 ── */

.site-pane {
  flex-shrink: 0;
  width: 300px;
}

.site-pane__head {
  display: flex;
  flex-shrink: 0;
  align-items: center;
  justify-content: space-between;
  padding: 10px 12px;
  font-size: 13px;
  border-bottom: 1px solid var(--el-border-color-lighter);
}

.site-pane__list {
  flex: 1;
  min-height: 0;
  overflow-y: auto;
}

.site-pane__pager {
  display: flex;
  flex-shrink: 0;
  justify-content: center;
  padding: 8px 4px;
  border-top: 1px solid var(--el-border-color-lighter);
}

.site-item {
  display: flex;
  flex-direction: column;
  gap: 6px;
  padding: 8px 12px;
  cursor: pointer;
  border-bottom: 1px solid var(--el-border-color-lighter);
}

.site-item:hover {
  background: var(--el-fill-color-light);
}

.site-item--active {
  background: var(--el-color-primary-light-9);
  box-shadow: inset 3px 0 0 var(--el-color-primary);
}

.site-item__row {
  display: flex;
  gap: 8px;
  align-items: center;
  min-width: 0;
}

.site-item__name {
  flex: 1;
  min-width: 0;
}

.site-item__host {
  overflow: hidden;
  font-size: 13px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.site-item__brand {
  overflow: hidden;
  font-size: 12px;
  color: var(--el-text-color-secondary);
  text-overflow: ellipsis;
  white-space: nowrap;
}

.site-item__counts {
  display: flex;
  flex-shrink: 0;
  gap: 4px;
  align-items: center;
}

.site-item__total {
  font-size: 12px;
  color: var(--el-text-color-secondary);
}

.site-item__badges {
  display: flex;
  flex-wrap: wrap;
  gap: 4px;
}

/* ── 右：页面表格 ── */

.page-pane {
  flex: 1;
  min-width: 0;
}

.site-summary {
  display: flex;
  flex-shrink: 0;
  gap: 12px;
  align-items: flex-start;
  padding: 12px 16px;
  border-bottom: 1px solid var(--el-border-color-lighter);
}

.site-summary__assets {
  display: flex;
  flex-shrink: 0;
  gap: 6px;
}

.site-summary__main {
  flex: 1;
  min-width: 0;
}

.site-summary__title {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  align-items: center;
}

.site-summary__brand {
  font-size: 13px;
  color: var(--el-text-color-secondary);
}

.site-summary__meta {
  display: flex;
  flex-wrap: wrap;
  gap: 14px;
  margin-top: 6px;
  font-size: 12px;
  color: var(--el-text-color-secondary);
}

.site-summary__bad {
  color: var(--el-color-danger);
}

.page-pane__filters {
  display: flex;
  flex-shrink: 0;
  gap: 8px;
  align-items: center;
  padding: 10px 16px 0;
}

.page-pane__grid {
  flex: 1;
  min-height: 0;
  padding: 0 8px;
}

.page-pane__placeholder {
  display: flex;
  flex: 1;
  align-items: center;
  justify-content: center;
}

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
