<script lang="ts" setup>
import type { SiteAdminVO, SiteLinkType, SiteSearchParams } from "#/api/site";

import { defineAsyncComponent, reactive } from "vue";

import { Page } from "@vben/common-ui";
import { formatDateTime } from "@vben/utils";

import { getSiteListApi } from "#/api/site";
import { useVbenVxeGrid, type VxeGridProps } from "#/adapter/vxe-table";

import BookmarkAssetCell from "#/views/bookmark/BookmarkAssetCell.vue";
import { assetByRole } from "#/views/bookmark/siteAsset";

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

const ElInputNumber = defineAsyncComponent(() =>
  Promise.all([
    import("element-plus/es/components/input-number/index"),
    import("element-plus/es/components/input-number/style/css"),
  ]).then(([res]) => res.ElInputNumber)
);

const ElSelect = defineAsyncComponent(() =>
  Promise.all([
    import("element-plus/es/components/select/index"),
    import("element-plus/es/components/select/style/css"),
  ]).then(([res]) => res.ElSelect)
);

const ElOption = defineAsyncComponent(() =>
  Promise.all([
    import("element-plus/es/components/select/index"),
    import("element-plus/es/components/select/style/css"),
  ]).then(([res]) => res.ElOption)
);

const ElDatePicker = defineAsyncComponent(() =>
  Promise.all([
    import("element-plus/es/components/date-picker/index"),
    import("element-plus/es/components/date-picker/style/css"),
  ]).then(([res]) => res.ElDatePicker)
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

/** 三态筛选统一用这套选项：不选=不筛，与后端 null=不筛一致，别拿哨兵值代替 */
const BOOL_OPTIONS = [
  { label: "是", value: true },
  { label: "否", value: false },
];

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

function handleSearch() {
  gridApi.reload();
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
  gridApi.reload();
}

const gridOptions: VxeGridProps<SiteAdminVO> = {
  id: "admin-website-all",
  columns: [
    // 图标按 role 分两列：站点层只有 favicon 与 logo，社交图和截图挂在页面上
    { field: "assetFavicon", title: "favicon", width: 80, slots: { default: "favicon" } },
    { field: "assetLogo", title: "logo", width: 80, slots: { default: "logo" } },
    { field: "host", title: "域名", minWidth: 220, sortable: true, slots: { default: "host" } },
    { field: "brandName", title: "站点全名", minWidth: 160, slots: { default: "brandName" } },
    { field: "shortName", title: "短名", minWidth: 120, slots: { default: "shortName" } },
    { field: "linkType", title: "类型", width: 90, slots: { default: "linkType" } },
    { field: "pageCount", title: "页面数", width: 90, align: "right" },
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
  ],
  toolbarConfig: { custom: true, refresh: true },
  pagerConfig: {},
  // 排序在服务端做：分页列表按当前页的 20 行本地排序会给出错误的"最差的站点"
  sortConfig: { remote: true, defaultSort: { field: "createTime", order: "desc" } },
  proxyConfig: {
    ajax: {
      query: async ({ page, sort }) => {
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
</script>

<template>
  <Page auto-content-height>
    <ElCard shadow="never">
      <template #header>
        <div class="flex items-center justify-between">
          <span>全部网站管理</span>
          <span class="text-xs text-gray-400">
            一个域名一行；同域名下的具体页面在「书签管理 › 书签管理」里看
          </span>
        </div>
      </template>
      <div class="search-bar mb-4">
        <ElForm :model="searchForm" label-position="left" @submit.prevent="handleSearch">
          <div class="flex flex-wrap items-center gap-x-6 gap-y-3">
            <ElFormItem label="搜索" class="!mb-0">
              <ElInput
                v-model="searchForm.keyword"
                placeholder="域名 / 站点全名 / 短名"
                clearable
                style="width: 220px"
                @keyup.enter="handleSearch"
              />
            </ElFormItem>
            <ElFormItem label="类型" class="!mb-0">
              <ElSelect v-model="searchForm.linkType" placeholder="全部" clearable style="width: 120px">
                <ElOption
                  v-for="(meta, value) in LINK_TYPE_META"
                  :key="value"
                  :label="meta.label"
                  :value="value"
                />
              </ElSelect>
            </ElFormItem>
            <ElFormItem label="域名活性" class="!mb-0">
              <ElSelect v-model="searchForm.alive" placeholder="全部" clearable style="width: 110px">
                <ElOption label="可达" :value="true" />
                <ElOption label="不可达" :value="false" />
              </ElSelect>
            </ElFormItem>
            <ElFormItem label="NSFW" class="!mb-0">
              <ElSelect v-model="searchForm.nsfw" placeholder="全部" clearable style="width: 100px">
                <ElOption v-for="o in BOOL_OPTIONS" :key="String(o.value)" :label="o.label" :value="o.value" />
              </ElSelect>
            </ElFormItem>
            <ElFormItem label="人工认证" class="!mb-0">
              <ElSelect v-model="searchForm.verifyFlag" placeholder="全部" clearable style="width: 100px">
                <ElOption v-for="o in BOOL_OPTIONS" :key="String(o.value)" :label="o.label" :value="o.value" />
              </ElSelect>
            </ElFormItem>
            <ElFormItem label="站点全名" class="!mb-0">
              <ElSelect
                v-model="searchForm.brandNameEmpty"
                placeholder="全部"
                clearable
                style="width: 110px"
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
                style="width: 110px"
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
                style="width: 260px"
              />
            </ElFormItem>
            <ElFormItem class="!mb-0 ml-auto">
              <ElButton type="primary" @click="handleSearch">搜索</ElButton>
              <ElButton class="ml-2" @click="handleReset">重置</ElButton>
            </ElFormItem>
          </div>
        </ElForm>
      </div>
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
        <template #linkType="{ row }">
          <ElTag
            :type="LINK_TYPE_META[row.linkType]?.type ?? 'info'"
            size="small"
            disable-transitions
            :title="LINK_TYPE_META[row.linkType]?.tip"
          >
            {{ LINK_TYPE_META[row.linkType]?.label ?? row.linkType }}
          </ElTag>
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
              {{ LOCKED_FIELD_LABEL[f] ?? f }}
            </ElTag>
          </template>
          <span v-else class="text-gray-400">-</span>
        </template>
      </Grid>
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
