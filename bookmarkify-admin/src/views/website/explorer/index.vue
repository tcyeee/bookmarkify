<script lang="ts" setup>
import type { BookmarkEntity, BookmarkParseStatus } from "#/api/bookmark";
import type { SiteAdminVO } from "#/api/site";
import type { UserAdminVO } from "#/api/user-manage";

import { computed, onBeforeUnmount, onMounted, reactive, ref } from "vue";
import { useRoute, useRouter } from "vue-router";

import { Page } from "@vben/common-ui";
import { formatDateTime } from "@vben/utils";

import {
  ElButton,
  ElCard,
  ElEmpty,
  ElInput,
  ElLink,
  ElMessage,
  ElOption,
  ElPagination,
  ElSelect,
  ElTag,
} from "#/adapter/element";
import { getBookmarkListApi, refreshBookmarkApi } from "#/api/bookmark";
import { abnormalPageCount, getSiteDetailApi, getSiteListApi } from "#/api/site";
import { useVbenVxeGrid, type VxeGridProps } from "#/adapter/vxe-table";

import BookmarkAssetCell from "#/views/bookmark/BookmarkAssetCell.vue";
import BookmarkDetailDialog from "#/views/bookmark/BookmarkDetailDialog.vue";
import { assetByRole, socialOf } from "#/views/bookmark/siteAsset";
import UserDetailDialog from "#/views/user/UserDetailDialog.vue";
import UserIdentityCell from "#/views/user/UserIdentityCell.vue";

import { PAGE_STATUS_META, PAGE_STATUS_ORDER } from "../pageStatus";
import SiteEditDialog from "../SiteEditDialog.vue";
import SiteFilterBar from "../SiteFilterBar.vue";
import SiteHealthBar from "../SiteHealthBar.vue";
import {
  createSiteFilters,
  LINK_TYPE_META,
  SITE_LOCKED_FIELD_LABEL,
  toSiteSearchParams,
} from "../siteFilters";

// ──────────────────────────────────────────────────────────────
// 左侧：站点选择器
//
// 刻意不是表格。它是个「选择器」——每项只承担 favicon / 域名 / 页面数 / 异常数 / 健康条，
// 站点层那些成列的字段(锁定字段、认证、上次探测)在右侧摘要条上展开。
// ──────────────────────────────────────────────────────────────

const searchForm = reactive(createSiteFilters());

const sites = ref<SiteAdminVO[]>([]);
const sitesTotal = ref(0);
const sitesLoading = ref(false);
const sitePager = reactive({ currentPage: 1, pageSize: 30 });

const selectedSite = ref<null | SiteAdminVO>(null);

const route = useRoute();
const router = useRouter();

/**
 * 左栏排序。后端 `sortField` 是白名单映射，这四项都在白名单里。
 *
 * 后台看站点最常见的诉求是「最烂的排前面」，只靠「连续失败 ≥ N」筛只能逼近 ——
 * 筛不出"第 3 差的那个"，也回答不了"最差的到底有多差"。
 */
const SITE_SORTS = [
  { label: "最近收录", field: "createTime", asc: false },
  { label: "连续失败最多", field: "consecutiveFail", asc: false },
  // 升序即"上次探测时间最早的排前面"。从未探测过的站点 lastCheckAt 为 NULL，
  // PostgreSQL 的 ASC 把 NULL 排在最后 —— 所以这一项找的是"探过但很久没再探"的站
  { label: "上次探测（早→晚）", field: "lastCheckAt", asc: true },
  { label: "域名 A→Z", field: "host", asc: true },
] as const;

const sortIndex = ref(0);

/**
 * 请求序号，用来丢弃过期响应。
 *
 * `loadSites` 被搜索/重置/翻页/排序/保存后同步等多处调用，快速连点时先发的请求可能后到，
 * 把新筛选条件下的结果覆盖回旧的 —— 表现就是"筛选没生效"。
 */
let sitesRequestSeq = 0;

async function loadSites() {
  const seq = ++sitesRequestSeq;
  sitesLoading.value = true;
  try {
    const sort = SITE_SORTS[sortIndex.value]!;
    const res = await getSiteListApi({
      ...toSiteSearchParams(searchForm),
      sortField: sort.field,
      sortAsc: sort.asc,
      currentPage: sitePager.currentPage,
      pageSize: sitePager.pageSize,
    });
    if (seq !== sitesRequestSeq) return;
    // v-for 的函数 ref 不会为消失的行清理数组槽位，换页后残留的旧元素会让方向键跳到已卸载的节点
    itemRefs.value = [];
    sites.value = res.records;
    sitesTotal.value = res.total;
    // 选中的站点若也在本页，换成列表里这份：两处显示同一个站点却各拿一份快照的话，
    // 刷新后左边计数变了而右边摘要条没变，看起来就是「保存没生效」
    const fresh = res.records.find((s) => s.id === selectedSite.value?.id);
    if (fresh) selectedSite.value = fresh;
  } catch {
    if (seq !== sitesRequestSeq) return;
    // 这个函数被几处事件处理器裸调用(搜索/重置/翻页)，不接住就是未捕获的 promise rejection；
    // 在 onMounted 里还会把后面的初始化一起中断掉。提示已由请求拦截器弹出，这里只清空列表，
    // 免得旧数据配着新筛选条件留在屏幕上，看起来像"筛选没生效"
    itemRefs.value = [];
    sites.value = [];
    sitesTotal.value = 0;
  } finally {
    if (seq === sitesRequestSeq) sitesLoading.value = false;
  }
}

/**
 * 重新拉取当前选中站点的聚合数据。
 *
 * 页面被修好之后必须调它：`pageStatusCounts` / `pageCount` / `consecutiveFail` 全部来自站点
 * 接口，不会因为右侧某一行变了而自动重算。少了这一步，顺着红色段点进去、把失败页面更新成功、
 * 回头一看左边那条红段还在原地 —— 这个页面「健康条告诉你要不要下钻」的立意就断在下钻之后。
 */
async function syncSelectedSite() {
  const id = selectedSite.value?.id;
  if (!id) return;
  const fresh = await getSiteDetailApi(id).catch(() => null);
  if (!fresh) return;
  selectedSite.value = fresh;
  const i = sites.value.findIndex((s) => s.id === id);
  if (i !== -1) sites.value[i] = fresh;
}

function handleSearch() {
  sitePager.currentPage = 1;
  loadSites();
}

/**
 * 重置筛选。
 *
 * 连右侧一起清：选中的站点很可能已经不在新的结果集里，而右边还完整显示着它的页面表，
 * 那正是「筛选没生效」的观感。URL 上的 siteId 也要摘掉，否则刷新后又会被深链恢复回来。
 */
function handleReset() {
  sitePager.currentPage = 1;
  clearSelection();
  loadSites();
}

function clearSelection() {
  selectedSite.value = null;
  pageFilters.name = "";
  pageFilters.status = undefined;
  router.replace({ query: { ...route.query, siteId: undefined } });
}

function handleSitePageChange(current: number) {
  sitePager.currentPage = current;
  loadSites();
}

function handleSortChange() {
  sitePager.currentPage = 1;
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

/** 只换页面表的状态过滤，不动选中的站点（摘要条上的状态 chip 用） */
function applyPageStatus(status: BookmarkParseStatus) {
  pageFilters.status = pageFilters.status === status ? undefined : status;
  gridApi.reload();
}

// ── 左栏宽度：可拖拽 ──
//
// 域名是这一栏唯一的主标识，而 `xxx.oss-cn-hangzhou.aliyuncs.com` 在 300px 里必然被截断。
// 宽度存在 localStorage 而不是后端：这是每台设备的屏幕宽度问题，不是账号偏好。
const SITE_PANE_WIDTH_KEY = "admin:explorer:site-pane-width";
const SITE_PANE_MIN = 240;
const SITE_PANE_MAX = 520;

const sitePaneWidth = ref(
  Math.min(
    SITE_PANE_MAX,
    Math.max(SITE_PANE_MIN, Number(localStorage.getItem(SITE_PANE_WIDTH_KEY)) || 300),
  ),
);

let stopResize: (() => void) | null = null;

function startResize(e: PointerEvent) {
  e.preventDefault();
  const startX = e.clientX;
  const startWidth = sitePaneWidth.value;

  const onMove = (ev: PointerEvent) => {
    sitePaneWidth.value = Math.min(
      SITE_PANE_MAX,
      Math.max(SITE_PANE_MIN, startWidth + ev.clientX - startX),
    );
  };
  const onUp = () => {
    stopResize?.();
    localStorage.setItem(SITE_PANE_WIDTH_KEY, String(sitePaneWidth.value));
  };

  stopResize = () => {
    window.removeEventListener("pointermove", onMove);
    window.removeEventListener("pointerup", onUp);
    stopResize = null;
  };
  window.addEventListener("pointermove", onMove);
  window.addEventListener("pointerup", onUp);
}

// 拖拽途中卸载(切页签)会把两个 window 级监听留在原地
onBeforeUnmount(() => stopResize?.());

// ── 键盘可达 ──
const itemRefs = ref<(HTMLElement | null)[]>([]);

function focusSibling(index: number, delta: number) {
  itemRefs.value[index + delta]?.focus();
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

const siteEditVisible = ref(false);

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

/** vxe 的 cell-click 参数里这里只用得到这两项，不必把 vxe 的全量类型拖进来 */
type CellClickParams = { column?: { field?: string }; row: BookmarkEntity };

function handleRowClick({ row, column }: CellClickParams) {
  // 操作列有自己的点击语义，落到这里会把页面详情一起弹出来。
  // 收录者列**不在**这个名单里：那一列的可点区域是 `.owner-cell`，它自己 @click.stop 了；
  // 整列拦截会让"没有收录者(显示 -)"的行点上去毫无反应，同一列两种行为。
  if (column?.field === "rowActions") return;
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
    // 这一行的状态可能从 UNREACHABLE 变成了 SUCCESS，站点的健康条要跟着重算
    await syncSelectedSite();
  } catch {
    // 抓取服务不可用(E307)等已由请求拦截器弹出提示，这里只需别留下未捕获的 rejection
  } finally {
    // 只增不减的话，翻页/换站之后旧 id 会永久驻留在这个 reactive 对象里
    delete refreshingMap[row.id];
  }
}

/** 详情弹窗就地改了行对象，但站点级聚合(健康条/异常数)只有重拉才会变 */
function handleBookmarkUpdated() {
  syncSelectedSite();
}

function handleSiteSaved(site: SiteAdminVO) {
  selectedSite.value = site;
  const i = sites.value.findIndex((s) => s.id === site.id);
  if (i !== -1) sites.value[i] = site;
}

function handlePageSearch() {
  gridApi.reload();
}

const gridOptions: VxeGridProps<BookmarkEntity> = {
  id: "admin-website-explorer-pages",
  columns: [
    { field: "urlPath", title: "站内地址", minWidth: 220, slots: { default: "path" } },
    { field: "title", title: "标题", minWidth: 180, slots: { default: "title" } },
    // favicon 与 logo 刻意不在这里：它们是**站点级**资产，整列 200 行会渲染出 200 个
    // 一模一样的图标。它们在右上角的站点摘要条里出现一次就够了。社交图是页面级的，留着。
    { field: "assetSocial", title: "社交图", width: 90, slots: { default: "social" } },
    // 反爬拦截并进状态列：它本质是 SUCCESS 的一个亚态，独占一列时绝大多数行都是 "-"，
    // 而这张表的列宽合计本来就超出可用宽度，横滚会把真正要看的东西挤出屏幕
    { field: "parseStatus", title: "状态", width: 130, slots: { default: "parseStatus" } },
    { field: "owner", title: "收录用户", minWidth: 150, slots: { default: "owner" } },
    {
      field: "consecutiveFail",
      title: "连续失败",
      width: 85,
      align: "right",
      // 该字段可选，缺失时给 "-" 与相邻的时间列保持一致，别留一格空白
      formatter: ({ cellValue }) => cellValue ?? "-",
    },
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
      // 默认隐藏：与「上次探测」信息高度重叠，而两列都显示会让总列宽超出可用宽度。
      // 列显隐由 customConfig.storage 持久化，需要的人在工具栏勾出来即可
      visible: false,
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
    else if (site === null) {
      ElMessage.warning("链接里的站点已不存在，已为你回到站点列表");
      router.replace({ query: { ...route.query, siteId: undefined } });
    } else {
      // 请求失败：URL 上的 id 留着(刷新即可重试)，但必须说出来 ——
      // 否则用户看到的只是"右侧空着"，会以为这个深链本身是坏的
      ElMessage.error("这个站点没打开，可能是网络问题，刷新页面可重试");
    }
  }
  await loadSites();
  // 这里刻意不 reload：表格在 selectedSite 被赋值后才由 v-if 挂上，
  // 适配器默认的 proxyConfig.autoLoad 会带着 siteId 自己发起首查
});

const selectedAbnormal = computed(() =>
  selectedSite.value ? abnormalPageCount(selectedSite.value) : 0,
);

/**
 * 摘要条上的状态 chip：既是健康条的图例，也是它的键盘可达替身。
 *
 * 那条 bar 只有 6px 高、颜色到状态的映射全靠 title —— 第一次看到的人既不知道红色代表
 * 「抓取失败」还是「域名不可达」，也不可能用键盘点到它。这排 chip 把两件事都补上。
 */
const statusChips = computed(() => {
  const counts = selectedSite.value?.pageStatusCounts ?? {};
  return PAGE_STATUS_ORDER.map((status) => ({
    status,
    ...PAGE_STATUS_META[status],
    count: counts[status] ?? 0,
  })).filter((c) => c.count > 0);
});

/** 左栏分页：页数多了给个跳页框，但 300px 宽的栏里默认不占这个位置 */
const sitePageCount = computed(() => Math.ceil(sitesTotal.value / sitePager.pageSize) || 1);
const pagerLayout = computed(() =>
  sitePageCount.value > 10 ? "prev, pager, next, jumper" : "prev, pager, next",
);
</script>

<template>
  <Page auto-content-height>
    <div class="explorer">
      <ElCard shadow="never" class="explorer__filters" :body-style="{ padding: '14px 16px' }">
        <SiteFilterBar v-model="searchForm" @search="handleSearch" @reset="handleReset" />
      </ElCard>

      <div class="explorer__body">
        <!-- 左：站点选择器。自带分页，与右侧表格的分页器是**平级**的两个，不是嵌套 -->
        <ElCard
          shadow="never"
          class="site-pane"
          :style="{ width: `${sitePaneWidth}px` }"
          :body-style="{ padding: '0', display: 'flex', flexDirection: 'column', height: '100%' }"
        >
          <div class="site-pane__head">
            <span>站点</span>
            <span class="text-xs text-gray-400">共 {{ sitesTotal }} 个</span>
          </div>

          <div class="site-pane__sort">
            <ElSelect v-model="sortIndex" size="small" class="w-full" @change="handleSortChange">
              <ElOption
                v-for="(s, i) in SITE_SORTS"
                :key="s.field"
                :label="`排序：${s.label}`"
                :value="i"
              />
            </ElSelect>
          </div>

          <div v-loading="sitesLoading" class="site-pane__list" role="listbox" aria-label="站点列表">
            <div
              v-for="(site, i) in sites"
              :key="site.id"
              :ref="(el) => (itemRefs[i] = el as HTMLElement)"
              class="site-item"
              :class="{ 'site-item--active': site.id === selectedSite?.id }"
              role="option"
              :aria-selected="site.id === selectedSite?.id"
              tabindex="0"
              @click="selectSite(site)"
              @keydown.enter.prevent="selectSite(site)"
              @keydown.space.prevent="selectSite(site)"
              @keydown.down.prevent="focusSibling(i, 1)"
              @keydown.up.prevent="focusSibling(i, -1)"
            >
              <div class="site-item__row">
                <BookmarkAssetCell :src="assetByRole(site.assets, 'FAVICON')?.url" />
                <div class="site-item__name">
                  <div class="site-item__host" :title="site.host">{{ site.host }}</div>
                  <!-- displayName 是后端算好的「短名→全名→域名」，别在前端再拼一套 -->
                  <div class="site-item__brand">{{ site.displayName }}</div>
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
            <ElEmpty
              v-if="!sitesLoading && sites.length === 0"
              :image-size="60"
              description="没有符合条件的站点"
            />
          </div>

          <div class="site-pane__pager">
            <ElPagination
              small
              :layout="pagerLayout"
              :pager-count="5"
              :current-page="sitePager.currentPage"
              :page-size="sitePager.pageSize"
              :total="sitesTotal"
              @current-change="handleSitePageChange"
            />
          </div>
        </ElCard>

        <!-- 拖拽把手：域名长度差异很大，固定宽度必然在某一批站点上截断 -->
        <div
          class="pane-resizer"
          role="separator"
          aria-orientation="vertical"
          aria-label="调整站点栏宽度"
          @pointerdown="startResize"
        />

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
                  <ElLink type="primary" :href="selectedSite.rootUrl" target="_blank" rel="noopener">
                    {{ selectedSite.host }}
                  </ElLink>
                  <span
                    v-if="selectedSite.displayName !== selectedSite.host"
                    class="site-summary__brand"
                  >
                    {{ selectedSite.displayName }}
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
                  <!-- NSFW 是三态：判定为脏 / 判过且干净 / 还没判过。少了最后一态，
                       "这个站没问题"和"这个站还没人看过"在界面上完全一样 -->
                  <ElTag
                    v-if="selectedSite.nsfw"
                    type="danger"
                    size="small"
                    :title="selectedSite.nsfwReason"
                  >
                    NSFW
                  </ElTag>
                  <ElTag
                    v-else-if="!selectedSite.nsfwReason"
                    type="info"
                    size="small"
                    disable-transitions
                    title="尚未做过 NSFW 判定"
                  >
                    NSFW 未判定
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
                    {{ SITE_LOCKED_FIELD_LABEL[f] ?? f }}
                  </ElTag>
                  <ElButton
                    link
                    type="primary"
                    size="small"
                    class="ml-auto"
                    @click="siteEditVisible = true"
                  >
                    编辑站点
                  </ElButton>
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
                  <!-- 「这个站为什么还没被复查」只有 nextCheckAt 回答得了 -->
                  <span>
                    下次巡检：{{
                      selectedSite.nextCheckAt ? formatDateTime(selectedSite.nextCheckAt) : "—"
                    }}
                  </span>
                </div>

                <!-- 健康条的图例兼键盘可达替身；点一下按该状态过滤，再点一下取消 -->
                <div v-if="statusChips.length > 0" class="site-summary__chips">
                  <button
                    v-for="c in statusChips"
                    :key="c.status"
                    type="button"
                    class="status-chip"
                    :class="{ 'status-chip--on': pageFilters.status === c.status }"
                    :title="c.tip ?? `只看${c.label}的页面`"
                    @click="applyPageStatus(c.status)"
                  >
                    <i class="status-chip__dot" :style="{ background: `var(${c.cssVar})` }" />
                    {{ c.label }} {{ c.count }}
                  </button>
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
                  v-for="s in PAGE_STATUS_ORDER"
                  :key="s"
                  :label="PAGE_STATUS_META[s].label"
                  :value="s"
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
                  <div class="status-cell">
                    <ElTag
                      :type="PAGE_STATUS_META[row.parseStatus]?.type ?? 'info'"
                      size="small"
                      disable-transitions
                      :title="PAGE_STATUS_META[row.parseStatus]?.tip"
                    >
                      {{ PAGE_STATUS_META[row.parseStatus]?.label ?? row.parseStatus }}
                    </ElTag>
                    <ElTag
                      v-if="row.antiCrawlerBlocked"
                      type="warning"
                      size="small"
                      disable-transitions
                      title="抓取成功但页面疑似反爬虫/WAF挑战页，内容可能不可靠"
                    >
                      反爬
                    </ElTag>
                  </div>
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

      <BookmarkDetailDialog
        v-model="detailVisible"
        :bookmark="currentRow"
        @updated="handleBookmarkUpdated"
      />
      <UserDetailDialog v-model="userVisible" :user="currentUser" />
      <SiteEditDialog v-model="siteEditVisible" :site="selectedSite" @saved="handleSiteSaved" />
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
  min-height: 0;
}

/* ── 左：站点选择器 ── */

.site-pane {
  flex-shrink: 0;
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

.site-pane__sort {
  flex-shrink: 0;
  padding: 8px 10px;
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

/* 键盘焦点必须看得见，否则方向键导航等于没有 */
.site-item:focus-visible {
  background: var(--el-fill-color-light);
  outline: 2px solid var(--el-color-primary);
  outline-offset: -2px;
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

/* ── 拖拽把手 ── */

.pane-resizer {
  flex-shrink: 0;
  width: 12px;
  cursor: col-resize;
}

.pane-resizer::after {
  display: block;
  width: 2px;
  height: 100%;
  margin: 0 auto;
  content: "";
  background: transparent;
  transition: background 0.12s ease;
}

.pane-resizer:hover::after {
  background: var(--el-color-primary);
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

.site-summary__chips {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  margin-top: 8px;
}

.status-chip {
  display: inline-flex;
  gap: 5px;
  align-items: center;
  padding: 2px 8px;
  font-size: 12px;
  color: var(--el-text-color-regular);
  cursor: pointer;
  background: var(--el-fill-color-light);
  border: 1px solid transparent;
  border-radius: 10px;
}

.status-chip:hover {
  border-color: var(--el-border-color);
}

.status-chip--on {
  color: var(--el-color-primary);
  background: var(--el-color-primary-light-9);
  border-color: var(--el-color-primary);
}

.status-chip__dot {
  width: 8px;
  height: 8px;
  border-radius: 50%;
}

.status-cell {
  display: flex;
  flex-wrap: wrap;
  gap: 4px;
  align-items: center;
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
</style>
