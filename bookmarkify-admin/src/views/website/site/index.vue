<script lang="ts" setup>
import type { SiteAdminVO, SiteLinkType } from "#/api/site";

import { reactive, ref } from "vue";
import { useRouter } from "vue-router";

import { Page } from "@vben/common-ui";
import { formatDateTime } from "@vben/utils";

import { ElButton, ElCard, ElLink, ElTag } from "#/adapter/element";
import { abnormalPageCount, getSiteListApi } from "#/api/site";
import { useVbenVxeGrid, type VxeGridProps } from "#/adapter/vxe-table";
import { useAutoSearch } from "#/components/filter-bar";

import BookmarkAssetCell from "#/views/bookmark/BookmarkAssetCell.vue";
import { LINK_TYPE_LABEL } from "#/views/bookmark/linkType";
import { assetByRole } from "#/views/bookmark/siteAsset";

import { PAGE_STATUS_META, PAGE_STATUS_ORDER } from "../pageStatus";
import SiteEditDialog from "../SiteEditDialog.vue";
import SiteFilterBar from "../SiteFilterBar.vue";
import SiteHealthBar from "../SiteHealthBar.vue";
import {
  createSiteFilters,
  SITE_LOCKED_FIELD_LABEL,
  toSiteSearchParams,
} from "../siteFilters";

const searchForm = reactive(createSiteFilters());

const router = useRouter();

/**
 * 下钻到页面层。
 *
 * 带的是 siteId 而不是 host —— 页面管理页按 host 关键字过滤是子串匹配，
 * 拿 `qq.com` 下钻会把 `xxqq.com.cn` 一起捞进来。父子关系要精确。
 */
function gotoPages(site: SiteAdminVO, status?: string) {
  router.push({
    path: "/website/page",
    query: { siteId: site.id, ...(status ? { status } : {}) },
  });
}

// ── 手工编辑 ──
//
// 「站点全名 = 为空」「人工认证」这两个筛选项在这之前是死胡同：筛得出需要人工过一遍的站点，
// 却没有任何入口去改。编辑入口就该落在这张平表上 —— 它本来就是为"批量过一遍"存在的。
const editVisible = ref(false);
const editingSite = ref<null | SiteAdminVO>(null);

function handleEdit(site: SiteAdminVO) {
  editingSite.value = site;
  editVisible.value = true;
}

/**
 * 就地改那一行，不整表重查。
 *
 * `editingSite` 拿到的就是 vxe 传出来的**那个行对象本身**，改它即改表格行 —— 与
 * `BookmarkDetailDialog` / 页面管理页的「更新」是同一套做法。重查会把滚动位置和当前排序下的
 * 行序全部重置，而批量过站点时每保存一次就跳一次是没法用的。
 */
function handleSaved(updated: SiteAdminVO) {
  if (editingSite.value) Object.assign(editingSite.value, updated);
}

const gridOptions: VxeGridProps<SiteAdminVO> = {
  id: "admin-website-site",
  columns: [
    // 图标按 role 分两列：站点层只有 favicon 与 logo，社交图和截图挂在页面上
    { field: "assetFavicon", title: "favicon", width: 80, slots: { default: "favicon" } },
    { field: "assetLogo", title: "logo", width: 80, slots: { default: "logo" } },
    { field: "host", title: "域名", minWidth: 220, sortable: true, slots: { default: "host" } },
    { field: "brandName", title: "站点全名", minWidth: 160, slots: { default: "brandName" } },
    { field: "shortName", title: "短名", minWidth: 120, slots: { default: "shortName" } },
    // 页面数只回答「这个站有多大」，健康条才回答「这个站烂不烂」——后台要的是后者
    { field: "pageCount", title: "页面数", width: 110, slots: { default: "pageCount" } },
    { field: "pageHealth", title: "页面健康", minWidth: 140, slots: { default: "pageHealth" } },
    { field: "isAlive", title: "域名活性", width: 100, slots: { default: "alive" } },
    { field: "consecutiveFail", title: "连续失败", width: 100, align: "right", sortable: true },
    { field: "nsfw", title: "NSFW", width: 90, slots: { default: "nsfw" } },
    { field: "verifyFlag", title: "人工认证", width: 100, slots: { default: "verifyFlag" } },
    { field: "lockedFields", title: "锁定字段", minWidth: 150, slots: { default: "lockedFields" } },
    {
      field: "lastCheckAt",
      title: "上次探测",
      width: 180,
      sortable: true,
      formatter: ({ cellValue }) => (cellValue ? formatDateTime(cellValue) : "-"),
    },
    {
      field: "createTime",
      title: "收录时间",
      width: 180,
      sortable: true,
      formatter: ({ cellValue }) => formatDateTime(cellValue),
    },

    // ── 以下默认隐藏，走工具栏「列自定义」按需打开 ──
    //
    // `site` 表的字段在这里是**全量**下发的，默认全开会让表宽到没法看。取舍标准只有一条：
    // 日常「批量过一遍站点」用不用得上。
    { field: "scheme", title: "协议", width: 90, visible: false },
    // 默认筛选是 linkType=DOMAIN，这一列整列都会是「域名」——只有在折叠区里打开
    // 「非域名站点」之后它才有信息量，所以默认隐藏而不是删掉：删掉的话放开范围后
    // 多出来的那批行看起来只是"抓取失败"，看不出它们本来就不参与抓取
    {
      field: "linkType",
      title: "类型",
      width: 100,
      visible: false,
      formatter: ({ cellValue }) => LINK_TYPE_LABEL[cellValue as SiteLinkType] ?? "-",
    },
    // 短名→全名→域名 的兜底结果。它与上面三列不是重复：前台磁贴上实际印的就是这个值，
    // 「为什么这个磁贴显示的是域名」只有对着它才答得出来
    { field: "displayName", title: "展示名", minWidth: 160, visible: false },
    // nsfwReason 平时只做上面 NSFW 列的悬浮文案；判定可疑时需要能整列扫过去
    { field: "nsfwReason", title: "NSFW 理由", minWidth: 180, visible: false, slots: { default: "nsfwReason" } },
    {
      field: "nextCheckAt",
      title: "下次巡检",
      width: 180,
      visible: false,
      formatter: ({ cellValue }) => (cellValue ? formatDateTime(cellValue) : "-"),
    },
    {
      field: "updateTime",
      title: "更新时间",
      width: 180,
      sortable: true,
      visible: false,
      formatter: ({ cellValue }) => (cellValue ? formatDateTime(cellValue) : "-"),
    },
    { field: "id", title: "站点ID", minWidth: 200, visible: false },
    // 健康条把分布画出来了，但画不出精确数字，也没法按某一种状态排序。这四列是同一份
    // pageStatusCounts 的数值形态，纯前端派生，不多花一次查询
    ...PAGE_STATUS_ORDER.map((status) => ({
      field: `pageCount${status}`,
      title: `${PAGE_STATUS_META[status].label}页面数`,
      width: 120,
      align: "right" as const,
      visible: false,
      formatter: ({ row }: { row: SiteAdminVO }) => String(row.pageStatusCounts?.[status] ?? 0),
    })),

    {
      field: "rowActions",
      title: "操作",
      width: 130,
      fixed: "right",
      slots: { default: "actions" },
    },
  ],
  toolbarConfig: { custom: true, refresh: true },
  pagerConfig: {},
  // 排序在服务端做：分页列表按当前页的 20 行本地排序会给出错误的"最差的站点"
  sortConfig: { remote: true, defaultSort: { field: "createTime", order: "desc" } },
  proxyConfig: {
    ajax: {
      query: async ({ page, sort }) => {
        const res = await getSiteListApi({
          ...toSiteSearchParams(searchForm),
          sortField: sort?.field || undefined,
          sortAsc: sort?.order === "asc",
          currentPage: page.currentPage,
          pageSize: page.pageSize,
        });
        return { items: res.records, total: res.total };
      },
    },
  },
};

const [Grid, gridApi] = useVbenVxeGrid({ gridOptions });

const { reset } = useAutoSearch(searchForm, () => gridApi.reload());
</script>

<template>
  <Page auto-content-height>
    <ElCard shadow="never">
      <template #header>
        <div class="flex items-center justify-between">
          <span>站点管理</span>
          <span class="text-xs text-gray-400">
            站点层：一个域名一行，只列域名类站点。同域名下的具体页面在「网站管理 › 页面管理」里看
          </span>
        </div>
      </template>
      <SiteFilterBar v-model="searchForm" class="mb-4" @reset="reset" />
      <Grid>
        <template #favicon="{ row }">
          <BookmarkAssetCell :src="assetByRole(row.assets, 'FAVICON')?.url" />
        </template>
        <template #logo="{ row }">
          <BookmarkAssetCell :src="assetByRole(row.assets, 'LOGO')?.url" />
        </template>
        <template #host="{ row }">
          <ElLink type="primary" :href="row.rootUrl" target="_blank" rel="noopener">
            {{ row.host }}
          </ElLink>
        </template>
        <template #brandName="{ row }">
          <span v-if="row.brandName">{{ row.brandName }}</span>
          <span v-else class="text-gray-400">-</span>
        </template>
        <template #shortName="{ row }">
          <span v-if="row.shortName">{{ row.shortName }}</span>
          <span v-else class="text-gray-400">-</span>
        </template>
        <template #pageCount="{ row }">
          <div class="page-count">
            <span>{{ row.pageCount }}</span>
            <ElTag
              v-if="abnormalPageCount(row) > 0"
              type="danger"
              size="small"
              disable-transitions
              :title="`${abnormalPageCount(row)} 个页面不是抓取成功状态`"
            >
              {{ abnormalPageCount(row) }}
            </ElTag>
          </div>
        </template>
        <template #pageHealth="{ row }">
          <!-- 点某一段 = 「我要看这个站这种状态的页面」，下钻时把状态一并带过去 -->
          <SiteHealthBar
            :counts="row.pageStatusCounts"
            interactive
            @pick="(status) => gotoPages(row, status)"
          />
        </template>
        <template #alive="{ row }">
          <ElTag v-if="row.isAlive" type="success" size="small" disable-transitions>可达</ElTag>
          <ElTag v-else type="danger" size="small" disable-transitions>不可达</ElTag>
        </template>
        <template #nsfw="{ row }">
          <!-- nsfwReason 为空表示还没判过，与"判过且干净"是两回事 -->
          <ElTag v-if="row.nsfw" type="danger" size="small" :title="row.nsfwReason">NSFW</ElTag>
          <span v-else-if="!row.nsfwReason" class="text-gray-400" title="尚未做过 NSFW 判定">未判定</span>
          <span v-else class="text-gray-400">-</span>
        </template>
        <template #nsfwReason="{ row }">
          <!-- CLEAN = 判过且干净；为空 = 还没判过。两者不能都显示成 "-" -->
          <span v-if="row.nsfwReason" :title="row.nsfwReason">{{ row.nsfwReason }}</span>
          <span v-else class="text-gray-400" title="尚未做过 NSFW 判定">未判定</span>
        </template>
        <template #verifyFlag="{ row }">
          <ElTag
            v-if="row.verifyFlag"
            type="success"
            size="small"
            title="品牌名与图标已人工核对，抓取不再覆盖"
          >
            已认证
          </ElTag>
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
              {{ SITE_LOCKED_FIELD_LABEL[f] ?? f }}
            </ElTag>
          </template>
          <span v-else class="text-gray-400">-</span>
        </template>
        <template #actions="{ row }">
          <ElButton link type="primary" @click.stop="gotoPages(row)">页面</ElButton>
          <ElButton link type="primary" @click.stop="handleEdit(row)">编辑</ElButton>
        </template>
      </Grid>
    </ElCard>

    <SiteEditDialog v-model="editVisible" :site="editingSite" @saved="handleSaved" />
  </Page>
</template>

<style scoped>
.page-count {
  display: flex;
  gap: 6px;
  align-items: center;
  justify-content: flex-end;
}
</style>
