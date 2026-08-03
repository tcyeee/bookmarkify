<script lang="ts" setup>
import type { ScrapperCallLogSearchParams, ScrapperCallLogVO } from "#/api/scrapper-call-log";

import { defineAsyncComponent, reactive, ref } from "vue";

import { Page } from "@vben/common-ui";
import { formatDateTime } from "@vben/utils";

import { getAdminScrapperCallLogListApi } from "#/api/scrapper-call-log";
import { useVbenVxeGrid, type VxeGridProps } from "#/adapter/vxe-table";

import BookmarkDetailDialog from "#/views/bookmark/BookmarkDetailDialog.vue";
import SweepBreakerAlert from "#/views/scrapper/SweepBreakerAlert.vue";

import ScrapeResultDialog from "./ScrapeResultDialog.vue";

const ElCard = defineAsyncComponent(() =>
  Promise.all([
    import("element-plus/es/components/card/index"),
    import("element-plus/es/components/card/style/css"),
  ]).then(([res]) => res.ElCard)
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

const ElButton = defineAsyncComponent(() =>
  Promise.all([
    import("element-plus/es/components/button/index"),
    import("element-plus/es/components/button/style/css"),
  ]).then(([res]) => res.ElButton)
);

const ElTag = defineAsyncComponent(() =>
  Promise.all([
    import("element-plus/es/components/tag/index"),
    import("element-plus/es/components/tag/style/css"),
  ]).then(([res]) => res.ElTag)
);

const ElTooltip = defineAsyncComponent(() =>
  Promise.all([
    import("element-plus/es/components/tooltip/index"),
    import("element-plus/es/components/tooltip/style/css"),
  ]).then(([res]) => res.ElTooltip)
);

const searchForm = reactive<Pick<ScrapperCallLogSearchParams, "urlHost" | "success">>({
  urlHost: "",
  success: undefined,
});

// ── 书签解析对话框：失败行点"重试"后重新调用 scrapper 并展示其返回的全部信息 ──
const parseDialogVisible = ref(false);
const parseUrl = ref("");

function handleRetry(row: ScrapperCallLogVO) {
  parseUrl.value = row.url;
  parseDialogVisible.value = true;
}

// ── 书签详情弹窗：日志表只存了抓过哪个地址，没有书签 ID，交给弹窗按域名+路径反查 ──
const detailVisible = ref(false);
const detailUrl = ref("");

function handleCellClick({ row, column }: { row: ScrapperCallLogVO; column: any }) {
  if (column?.field === "rowActions") return;
  detailUrl.value = row.url;
  detailVisible.value = true;
}

// 日志表未存 favicon，直接按域名取站点根目录的 favicon.ico，加载失败时换成兜底地球图标
const FALLBACK_FAVICON = `data:image/svg+xml;utf8,${encodeURIComponent(
  `<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24" fill="none" stroke="#9ca3af" stroke-width="1.6"><circle cx="12" cy="12" r="9"/><path d="M3 12h18M12 3a14 14 0 0 1 0 18M12 3a14 14 0 0 0 0 18"/></svg>`
)}`;

function faviconOf(urlHost: string) {
  return urlHost ? `https://${urlHost}/favicon.ico` : FALLBACK_FAVICON;
}

function onFaviconError(event: Event) {
  const img = event.target as HTMLImageElement;
  // 已经是兜底图还报错就不再重置，避免 error 事件死循环
  if (img.src !== FALLBACK_FAVICON) img.src = FALLBACK_FAVICON;
}

/** 来源枚举释义，鼠标悬浮在"来源"表头时展示 */
const SOURCE_LEGEND: Array<[string, string]> = [
  ["og", "命中 Open Graph 标签(og:title/og:description/og:image)，优先级最高"],
  ["twitter_card", "无 OG，命中 Twitter Card 标签(twitter:title 等)"],
  ["json_ld", "无 OG/Twitter，命中页面 JSON-LD 结构化数据(name/description/image)"],
  ["html", "以上均未命中，回退到 <title> 标签与 meta[name=description]"],
  ["headless", "普通 HTTP 抓取失败，由无头浏览器渲染后抓取，并附带页面截图"],
];

function handleSearch() {
  gridApi.reload();
}

function handleReset() {
  searchForm.urlHost = "";
  searchForm.success = undefined;
  gridApi.reload();
}

const gridOptions: VxeGridProps<ScrapperCallLogVO> = {
  id: "admin-scrapper-call-log",
  columns: [
    { type: "seq", title: "#", width: 50 },
    { field: "urlHost", title: "域名", minWidth: 180, slots: { default: "urlHost" } },
    { field: "url", title: "请求URL", minWidth: 240, showOverflow: "tooltip" },
    { field: "success", title: "结果", width: 90, slots: { default: "success" } },
    { field: "httpStatus", title: "HTTP状态", width: 100 },
    { field: "source", title: "来源", width: 120, slots: { header: "sourceHeader" } },
    { field: "cached", title: "缓存命中", width: 100, slots: { default: "cached" } },
    { field: "durationMs", title: "耗时(ms)", width: 100 },
    { field: "errorMsg", title: "错误信息", minWidth: 200, slots: { default: "errorMsg" } },
    {
      field: "createTime",
      title: "调用时间",
      width: 200,
      formatter: ({ cellValue }) => formatDateTime(cellValue),
    },
    // field 必填：点击行要弹书签详情，靠它把「操作」列排除在外
    { field: "rowActions", title: "操作", width: 90, fixed: "right", slots: { default: "action" } },
  ],
  toolbarConfig: { custom: true, refresh: true },
  pagerConfig: { pageSize: 50 },
  proxyConfig: {
    ajax: {
      query: async ({ page }) => {
        const res = await getAdminScrapperCallLogListApi({
          urlHost: searchForm.urlHost || undefined,
          success: searchForm.success,
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
  gridEvents: { cellClick: handleCellClick },
});
</script>

<template>
  <Page auto-content-height>
    <!-- 巡检熔断/停摆的常驻告警。一切正常时整条不渲染，不占版面 -->
    <SweepBreakerAlert />
    <ElCard shadow="never">
      <template #header>
        <div class="flex items-center justify-between">
          <span>Scrapper 调用日志</span>
        </div>
      </template>
      <div class="mb-4">
        <ElForm :inline="true" :model="searchForm">
          <ElFormItem label="域名">
            <ElInput v-model="searchForm.urlHost" placeholder="urlHost 模糊搜索" clearable />
          </ElFormItem>
          <ElFormItem label="状态">
            <ElSelect v-model="searchForm.success" placeholder="全部" clearable style="width: 120px">
              <ElOption label="成功" :value="true" />
              <ElOption label="失败" :value="false" />
            </ElSelect>
          </ElFormItem>
          <ElFormItem>
            <ElButton type="primary" @click="handleSearch">搜索</ElButton>
            <ElButton class="ml-2" @click="handleReset">重置</ElButton>
          </ElFormItem>
        </ElForm>
      </div>
      <Grid>
        <template #urlHost="{ row }">
          <span class="inline-flex items-center justify-end gap-1.5">
            <img
              :src="faviconOf(row.urlHost)"
              alt=""
              class="h-4 w-4 shrink-0 rounded-sm object-contain"
              @error="onFaviconError"
            />
            <span class="truncate">{{ row.urlHost }}</span>
          </span>
        </template>
        <template #sourceHeader="{ column }">
          <ElTooltip placement="top">
            <template #content>
              <div class="max-w-md space-y-1 text-xs leading-relaxed">
                <div class="font-medium">命中来源(scrapper 解析元数据的实际出处)</div>
                <div v-for="[key, desc] in SOURCE_LEGEND" :key="key">
                  <span class="font-mono">{{ key }}</span>
                  ：{{ desc }}
                </div>
                <div class="text-gray-300">抓取失败时该列为空</div>
              </div>
            </template>
            <span class="cursor-help underline decoration-dotted underline-offset-4">
              {{ column.title }}
            </span>
          </ElTooltip>
        </template>
        <template #action="{ row }">
          <ElButton v-if="!row.success" link type="primary" size="small" @click.stop="handleRetry(row)">
            重试
          </ElButton>
          <span v-else>-</span>
        </template>
        <template #success="{ row }">
          <ElTag v-if="row.success" type="success" size="small"> 成功 </ElTag>
          <ElTag v-else type="danger" size="small"> 失败 </ElTag>
        </template>
        <template #cached="{ row }">
          <ElTag v-if="row.cached" type="info" size="small"> 命中 </ElTag>
          <span v-else>-</span>
        </template>
        <template #errorMsg="{ row }">
          <ElTooltip v-if="row.errorMsg" :content="row.errorMsg" placement="top">
            <span class="line-clamp-1">{{ row.errorMsg }}</span>
          </ElTooltip>
          <span v-else>-</span>
        </template>
      </Grid>
    </ElCard>

    <ScrapeResultDialog v-model="parseDialogVisible" :url="parseUrl" />
    <BookmarkDetailDialog v-model="detailVisible" :lookup-url="detailUrl" />
  </Page>
</template>
