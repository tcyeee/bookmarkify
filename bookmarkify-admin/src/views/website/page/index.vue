<script lang="ts" setup>
import type { BookmarkEntity, BookmarkSearchParams } from "#/api/bookmark";
import type { SiteAdminVO, SiteLinkType } from "#/api/site";
import type { UserAdminVO } from "#/api/user-manage";

import { defineAsyncComponent, onMounted, reactive, ref } from "vue";
import { useRoute, useRouter } from "vue-router";

import { Page } from "@vben/common-ui";
import { formatDateTime } from "@vben/utils";

import { ElMessage } from "element-plus";

import {
  getBookmarkListApi,
  refreshBookmarkApi,
  updateBookmarkBasicInfoApi,
  type BookmarkParseStatus,
} from "#/api/bookmark";
import { getSiteDetailApi } from "#/api/site";
import { useVbenVxeGrid, type VxeGridProps } from "#/adapter/vxe-table";
import { FilterBar, FilterItem, useAutoSearch } from "#/components/filter-bar";

import BookmarkAssetCell from "#/views/bookmark/BookmarkAssetCell.vue";
import BookmarkDetailDialog from "#/views/bookmark/BookmarkDetailDialog.vue";
import { isScrapableType, LINK_TYPE_LABEL, LINK_TYPE_REASON } from "#/views/bookmark/linkType";
import {
  BOOKMARK_LOCKED_FIELD_LABEL,
  faviconOf,
  logoOf,
  socialOf,
} from "#/views/bookmark/siteAsset";
import {
  FETCH_LAYER_META,
  formatMetaSources,
  httpStatusType,
} from "#/views/bookmark/pageMeta";
import UserDetailDialog from "#/views/user/UserDetailDialog.vue";
import UserIdentityCell from "#/views/user/UserIdentityCell.vue";

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

const ElLink = defineAsyncComponent(() =>
  Promise.all([
    import("element-plus/es/components/link/index"),
    import("element-plus/es/components/link/style/css"),
  ]).then(([res]) => res.ElLink)
);

const route = useRoute();
const router = useRouter();

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

// ── 站点范围：从「站点管理」下钻过来时带的 ?siteId= ──
//
// 与把域名塞进关键字不是一回事：关键字是子串模糊匹配，`qq.com` 会把 `xxqq.com.cn`
// 一并捞进来。下钻要的是精确的父子关系，所以走独立的 siteId 参数。
const scopedSiteId = ref(typeof route.query.siteId === "string" ? route.query.siteId : "");
/** 站点摘要只用于顶部那条范围提示；取不到不影响列表本身（列表只需要 id） */
const scopedSite = ref<null | SiteAdminVO>(null);

const searchForm = reactive<
  Pick<BookmarkSearchParams, "name" | "status"> & {
    /** 放开默认的 linkType=DOMAIN 限制，把本机 / IP / 未归类的页面也列出来 */
    includeNonDomain?: boolean;
  }
>({
  name: "",
  // 从健康条某一段点进来时状态已经选好了，再让人手动选一次是白丢一次上下文
  status: statusOptions.some((o) => o.value === route.query.status)
    ? (route.query.status as BookmarkParseStatus)
    : undefined,
  includeNonDomain: undefined,
});

/** 把当前的站点范围写回 URL：刷新、切 vben 页签回来都还在 */
function syncScopeToUrl() {
  router.replace({
    query: { ...route.query, siteId: scopedSiteId.value || undefined, status: undefined },
  });
}

function clearScope() {
  scopedSiteId.value = "";
  scopedSite.value = null;
  syncScopeToUrl();
  gridApi.reload();
}

onMounted(async () => {
  if (!scopedSiteId.value) return;
  // 「站点已被清理」(null) 与「这次请求失败了」(抛错) 必须分开：只有前者才该摘掉 URL 上的
  // id，把一次网络抖动也当成前者，等于悄悄吞了用户的深链
  const site = await getSiteDetailApi(scopedSiteId.value).catch(() => undefined);
  if (site) scopedSite.value = site;
  else if (site === null) clearScope();
});

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

/**
 * 站内相对地址。
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

// ── 行内「更新」：重新抓取网站信息并直接覆盖持久化 ──
const refreshingMap = reactive<Record<string, boolean>>({});

async function handleRefresh(row: BookmarkEntity) {
  // 列表本身已按 linkType=DOMAIN 过滤，正常情况下这里不会遇到本机/IP 书签；
  // 但过滤看的是**站点**的类型，而这道判断看的是这一页自己的地址，两者理论上可能不一致
  if (!isScrapableType(row.linkType)) {
    ElMessage.warning(`${LINK_TYPE_REASON[row.linkType ?? "OTHER"]}，我方不抓取这类地址`);
    return;
  }
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

// ── 行内「修改」：弹窗编辑简称 / 标题 / 简介 ──
//
// 简称(appName)原先只能从已删除的 `/admin/bookmark/{id}/icon` 端点改，而后台从来没有页面调过它
// ——也就是说这个字段此前在后台**根本改不了**。它跟图标外观无关：它是 TILE 磁贴标题的主要来源
// （生产 92 个首页里 75 个靠它出标题），所以随那个端点一起并进了这里。
const editDialogVisible = ref(false);
const editingRow = ref<BookmarkEntity | null>(null);
const editForm = reactive({ appName: "", title: "", description: "" });
const savingBasicInfo = ref(false);

function handleEdit(row: BookmarkEntity) {
  editingRow.value = row;
  editForm.appName = row.appName ?? "";
  editForm.title = row.title ?? "";
  editForm.description = row.description ?? "";
  editDialogVisible.value = true;
}

async function handleSaveBasicInfo() {
  if (!editingRow.value) return;
  savingBasicInfo.value = true;
  try {
    const updated = await updateBookmarkBasicInfoApi(editingRow.value.id, {
      // 空串是有意义的取值（清空简称并解锁 APP_NAME），不能因为 falsy 就省略不传
      appName: editForm.appName,
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

function handleRowClick({ row, column }: { row: BookmarkEntity; column: any }) {
  // 操作列、收录者列与地址列有自己的点击语义，落到这里会把页面详情一起弹出来
  if (["owner", "rowActions", "urlPath"].includes(column?.field)) return;
  currentRow.value = row;
  detailVisible.value = true;
}

const gridOptions: VxeGridProps<BookmarkEntity> = {
  id: "admin-website-page",
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
    { field: "urlPath", title: "站内地址", minWidth: 200, slots: { default: "path" } },
    // 最早把这条书签加进来的用户。头像与昵称同格显示，点开看该用户的完整信息
    { field: "owner", title: "收录用户", minWidth: 160, slots: { default: "owner" } },
    { field: "parseStatus", title: "状态", width: 140, slots: { default: "parseStatus" } },
    { field: "antiCrawlerBlocked", title: "反爬拦截", width: 90, slots: { default: "antiCrawlerBlocked" } },
    { field: "nsfw", title: "NSFW", width: 90, slots: { default: "nsfw" } },
    { field: "categories", title: "分类", minWidth: 140, slots: { default: "categories" } },
    { field: "lockedFields", title: "锁定字段", minWidth: 140, slots: { default: "lockedFields" } },
    // 状态是 UNREACHABLE 时，「为什么」只有这一列答得出来
    { field: "parseErrMsg", title: "失败原因", minWidth: 180, slots: { default: "parseErrMsg" } },
    {
      field: "updateTime",
      title: "更新时间",
      width: 200,
      formatter: ({ cellValue }) => formatDateTime(cellValue),
    },

    // ── 以下默认隐藏，走工具栏「列自定义」按需打开 ──
    //
    // `page` / `page_meta` 两张表的字段在这里是**全量**下发的，默认全开会让表宽到没法看。
    // 取舍标准只有一条：日常扫表要不要用到。诊断类（巡检游标、抓取层、HTTP 状态码、
    // 字段级出处）平时是噪音，出问题时是唯一线索，所以留在这里而不是干脆不给。
    { field: "urlScheme", title: "协议", width: 90, visible: false },
    // 默认筛选是 linkType=DOMAIN，整列都会是「域名」；打开折叠区里的「非域名页面」之后
    // 它才有信息量。默认隐藏而不是不给：否则放开范围后多出来的那批行看起来只是抓取失败
    {
      field: "linkType",
      title: "类型",
      width: 100,
      visible: false,
      formatter: ({ cellValue }) => LINK_TYPE_LABEL[cellValue as SiteLinkType] ?? "-",
    },
    // canonical 四元组的后半截：整列同域同路径的深链只有靠它们才分得开
    { field: "urlQuery", title: "查询参数", minWidth: 160, visible: false, slots: { default: "urlQuery" } },
    { field: "urlFragment", title: "Fragment", minWidth: 140, visible: false, slots: { default: "urlFragment" } },
    { field: "siteId", title: "站点ID", minWidth: 200, visible: false },
    { field: "id", title: "页面ID", minWidth: 200, visible: false },
    // 页面级活性。与 parseStatus 不是一回事：抓取成功过、但这一页现在 404，两列会分叉
    { field: "isActivity", title: "页面可达", width: 100, visible: false, slots: { default: "isActivity" } },
    { field: "verifyFlag", title: "人工认证", width: 100, visible: false, slots: { default: "verifyFlag" } },
    { field: "ownerCount", title: "收录人数", width: 100, align: "right", visible: false },
    { field: "consecutiveFail", title: "连续失败", width: 100, align: "right", visible: false },
    {
      field: "lastParseAt",
      title: "上次抓取成功",
      width: 180,
      visible: false,
      formatter: ({ cellValue }) => (cellValue ? formatDateTime(cellValue) : "-"),
    },
    {
      field: "lastCheckAt",
      title: "上次探测",
      width: 180,
      visible: false,
      formatter: ({ cellValue }) => (cellValue ? formatDateTime(cellValue) : "-"),
    },
    {
      field: "nextCheckAt",
      title: "下次巡检",
      width: 180,
      visible: false,
      formatter: ({ cellValue }) => (cellValue ? formatDateTime(cellValue) : "-"),
    },
    {
      field: "createTime",
      title: "收录时间",
      width: 180,
      visible: false,
      formatter: ({ cellValue }) => formatDateTime(cellValue),
    },

    // ── page_meta：最近一次抓取的原样元数据 ──
    //
    // 与上面的「标题/网站描述」不重复：主表那两列是**当前生效值**（可能被人工改过并加锁），
    // 这里是抓取原样。两者不一致本身就是信息 —— 说明这条被人工干预过。
    { field: "metaFetchLayer", title: "抓取层", width: 100, visible: false, slots: { default: "metaFetchLayer" } },
    { field: "metaHttpStatus", title: "HTTP状态", width: 100, visible: false, slots: { default: "metaHttpStatus" } },
    { field: "metaAntiCrawler", title: "元数据反爬", width: 110, visible: false, slots: { default: "metaAntiCrawler" } },
    {
      field: "metaTitle",
      title: "抓取标题",
      minWidth: 200,
      visible: false,
      formatter: ({ row }) => row.pageMeta?.title || "-",
    },
    {
      field: "metaDescription",
      title: "抓取描述",
      minWidth: 200,
      visible: false,
      formatter: ({ row }) => truncate(row.pageMeta?.description) || "-",
    },
    {
      field: "metaSiteName",
      title: "站点名",
      minWidth: 140,
      visible: false,
      formatter: ({ row }) => row.pageMeta?.siteName || "-",
    },
    {
      field: "metaSiteShortName",
      title: "站点短名",
      minWidth: 120,
      visible: false,
      formatter: ({ row }) => row.pageMeta?.siteShortName || "-",
    },
    { field: "metaCanonicalUrl", title: "Canonical", minWidth: 220, visible: false, slots: { default: "metaCanonicalUrl" } },
    {
      field: "metaLang",
      title: "语言",
      width: 90,
      visible: false,
      formatter: ({ row }) => row.pageMeta?.lang || "-",
    },
    { field: "metaThemeColor", title: "主题色", width: 110, visible: false, slots: { default: "metaThemeColor" } },
    // 「这个标题到底是 og:title 还是 <title> 兜底来的」——字段级出处只有这里有
    { field: "metaSources", title: "字段出处", minWidth: 220, visible: false, slots: { default: "metaSources" } },
    {
      field: "metaFetchedAt",
      title: "元数据抓取时间",
      width: 180,
      visible: false,
      formatter: ({ row }) => (row.pageMeta?.fetchedAt ? formatDateTime(row.pageMeta.fetchedAt) : "-"),
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
          siteId: scopedSiteId.value || undefined,
          // 后台默认只看真实网站：localhost / 纯 IP / 未归类的地址不会被抓取，永远是一行
          // 空标题空图标的噪音，混在表里只会让「哪些页面抓失败了」变得更难看清。
          // 但要留一条查得到的路：这类书签在用户桌面上真实存在，用户报「我那条
          // 192.168.0.73:8192 坏了」时，后台得能把它翻出来看一眼类型
          linkType: searchForm.includeNonDomain ? undefined : "DOMAIN",
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

// 初始状态可能来自 URL（从站点健康条某一段点进来），「重置」要清空而不是把它填回去，
// 所以基线显式给成"什么都不筛"
const { reset } = useAutoSearch(searchForm, () => gridApi.reload(), {
  initial: { name: "", status: undefined, includeNonDomain: undefined },
});
</script>

<template>
  <Page auto-content-height>
    <ElCard shadow="never">
      <template #header>
        <div class="flex items-center justify-between">
          <span>页面管理</span>
          <span class="text-xs text-gray-400">
            页面层：一个具体地址一行，只列域名类站点下的页面。域名级的信息在「网站管理 › 站点管理」里看
          </span>
        </div>
      </template>

      <!-- 从站点管理下钻进来时的范围提示。没有它，一张被过滤过的表和全量表长得一模一样 -->
      <div v-if="scopedSiteId" class="scope-bar mb-3">
        <span class="scope-bar__label">仅看站点</span>
        <ElLink
          v-if="scopedSite"
          type="primary"
          :href="scopedSite.rootUrl"
          target="_blank"
          rel="noopener"
        >
          {{ scopedSite.host }}
        </ElLink>
        <span v-else class="text-gray-400">{{ scopedSiteId }}</span>
        <span v-if="scopedSite?.brandName" class="text-xs text-gray-400">
          {{ scopedSite.brandName }}
        </span>
        <span v-if="scopedSite" class="text-xs text-gray-400">
          共 {{ scopedSite.pageCount }} 个页面
        </span>
        <ElButton link type="primary" @click="clearScope">查看全部页面</ElButton>
      </div>

      <FilterBar class="mb-4" :advanced-count="searchForm.includeNonDomain ? 1 : 0" @reset="reset">
        <FilterItem label="关键字" width="240px">
          <ElInput v-model="searchForm.name" placeholder="名称 / 标题 / 描述 / 域名" clearable />
        </FilterItem>
        <FilterItem label="状态">
          <ElSelectV2 v-model="searchForm.status" :options="statusOptions" placeholder="全部状态" clearable>
            <template #default="{ item }">
              <ElTag :type="item.type" size="small" disable-transitions>
                {{ item.label }}
              </ElTag>
            </template>
          </ElSelectV2>
        </FilterItem>

        <template #advanced>
          <FilterItem label="非域名页面" width="140px">
            <ElSelectV2
              v-model="searchForm.includeNonDomain"
              :options="[{ label: '一并显示', value: true }]"
              placeholder="不显示"
              clearable
            />
          </FilterItem>
        </template>
      </FilterBar>
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
        <template #categories="{ row }">
          <template v-if="row.categories?.length">
            <ElTag
              v-for="c in row.categories"
              :key="c.id"
              size="small"
              class="mr-1"
              disable-transitions
              :color="c.color"
            >
              {{ c.name }}
            </ElTag>
          </template>
          <span v-else class="text-gray-400">-</span>
        </template>
        <template #lockedFields="{ row }">
          <template v-if="row.lockedFields?.length">
            <ElTag
              v-for="f in row.lockedFields"
              :key="f"
              type="warning"
              size="small"
              class="mr-1"
              disable-transitions
            >
              {{ BOOKMARK_LOCKED_FIELD_LABEL[f] ?? f }}
            </ElTag>
          </template>
          <span v-else class="text-gray-400">-</span>
        </template>
        <template #parseErrMsg="{ row }">
          <span v-if="row.parseErrMsg" class="text-red-500" :title="row.parseErrMsg">
            {{ truncate(row.parseErrMsg, 20) }}
          </span>
          <span v-else class="text-gray-400">-</span>
        </template>
        <template #urlQuery="{ row }">
          <span v-if="row.urlQuery" :title="row.urlQuery">{{ truncate(row.urlQuery, 24) }}</span>
          <span v-else class="text-gray-400">-</span>
        </template>
        <template #urlFragment="{ row }">
          <span v-if="row.urlFragment" :title="row.urlFragment">
            {{ truncate(row.urlFragment, 24) }}
          </span>
          <span v-else class="text-gray-400">-</span>
        </template>
        <template #isActivity="{ row }">
          <ElTag v-if="row.isActivity" type="success" size="small" disable-transitions>
            可达
          </ElTag>
          <ElTag v-else type="danger" size="small" disable-transitions>不可达</ElTag>
        </template>
        <template #verifyFlag="{ row }">
          <ElTag v-if="row.verifyFlag" type="success" size="small" disable-transitions>
            已认证
          </ElTag>
          <span v-else class="text-gray-400">-</span>
        </template>
        <template #metaFetchLayer="{ row }">
          <ElTag
            v-if="row.pageMeta?.fetchLayer"
            :type="FETCH_LAYER_META[row.pageMeta.fetchLayer]?.type ?? 'info'"
            size="small"
            disable-transitions
            :title="FETCH_LAYER_META[row.pageMeta.fetchLayer]?.tip"
          >
            {{ FETCH_LAYER_META[row.pageMeta.fetchLayer]?.label ?? row.pageMeta.fetchLayer }}
          </ElTag>
          <span v-else class="text-gray-400">-</span>
        </template>
        <template #metaHttpStatus="{ row }">
          <ElTag
            v-if="row.pageMeta?.httpStatus"
            :type="httpStatusType(row.pageMeta.httpStatus)"
            size="small"
            disable-transitions
          >
            {{ row.pageMeta.httpStatus }}
          </ElTag>
          <span v-else class="text-gray-400">-</span>
        </template>
        <template #metaAntiCrawler="{ row }">
          <ElTag
            v-if="row.pageMeta?.antiCrawler"
            type="warning"
            size="small"
            disable-transitions
            title="抓取侧判定这一页是反爬挑战页，元数据内容不可靠"
          >
            反爬
          </ElTag>
          <span v-else class="text-gray-400">-</span>
        </template>
        <template #metaCanonicalUrl="{ row }">
          <ElLink
            v-if="row.pageMeta?.canonicalUrl"
            type="primary"
            :href="row.pageMeta.canonicalUrl"
            target="_blank"
            rel="noopener"
            :title="row.pageMeta.canonicalUrl"
            @click.stop
          >
            {{ truncate(row.pageMeta.canonicalUrl, 30) }}
          </ElLink>
          <span v-else class="text-gray-400">-</span>
        </template>
        <template #metaThemeColor="{ row }">
          <span v-if="row.pageMeta?.themeColor" class="theme-color-cell">
            <i class="theme-color-dot" :style="{ background: row.pageMeta.themeColor }" />
            {{ row.pageMeta.themeColor }}
          </span>
          <span v-else class="text-gray-400">-</span>
        </template>
        <template #metaSources="{ row }">
          <span
            v-if="row.pageMeta?.metaSources"
            :title="row.pageMeta.metaSources"
          >
            {{ truncate(formatMetaSources(row.pageMeta.metaSources), 30) }}
          </span>
          <span v-else class="text-gray-400">-</span>
        </template>
        <template #actions="{ row }">
          <ElButton
            link
            type="primary"
            :loading="refreshingMap[row.id]"
            :disabled="!isScrapableType(row.linkType)"
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
          <ElFormItem label="简称">
            <ElInput
              v-model="editForm.appName"
              placeholder="磁贴上显示的短名称，留空则由抓取/AI 推断补上"
            />
          </ElFormItem>
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
.scope-bar {
  display: flex;
  flex-wrap: wrap;
  gap: 10px;
  align-items: center;
  padding: 8px 12px;
  background: var(--el-color-primary-light-9);
  border-radius: 4px;
}

.scope-bar__label {
  font-size: 13px;
  color: var(--el-text-color-regular);
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

.theme-color-cell {
  display: inline-flex;
  gap: 6px;
  align-items: center;
}

.theme-color-dot {
  width: 12px;
  height: 12px;
  border: 1px solid var(--el-border-color);
  border-radius: 3px;
}
</style>
