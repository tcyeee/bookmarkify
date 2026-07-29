<script lang="ts" setup>
import type { ScrapperCallLogSearchParams, ScrapperCallLogVO } from "#/api/scrapper-call-log";

import { defineAsyncComponent, reactive } from "vue";

import { Page } from "@vben/common-ui";
import { formatDateTime } from "@vben/utils";

import { getAdminScrapperCallLogListApi } from "#/api/scrapper-call-log";
import { useVbenVxeGrid, type VxeGridProps } from "#/adapter/vxe-table";

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
    { field: "urlHost", title: "域名", minWidth: 160 },
    { field: "url", title: "请求URL", minWidth: 240, showOverflow: "tooltip" },
    { field: "success", title: "结果", width: 90, slots: { default: "success" } },
    { field: "httpStatus", title: "HTTP状态", width: 100 },
    { field: "source", title: "来源", width: 120 },
    { field: "cached", title: "缓存命中", width: 100, slots: { default: "cached" } },
    { field: "durationMs", title: "耗时(ms)", width: 100 },
    { field: "errorMsg", title: "错误信息", minWidth: 200, slots: { default: "errorMsg" } },
    {
      field: "createTime",
      title: "调用时间",
      width: 200,
      formatter: ({ cellValue }) => formatDateTime(cellValue),
    },
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

const [Grid, gridApi] = useVbenVxeGrid({ gridOptions });
</script>

<template>
  <Page auto-content-height>
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
  </Page>
</template>
