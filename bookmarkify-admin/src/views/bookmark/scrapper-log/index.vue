<script lang="ts" setup>
import type { ScrapperCallLogSearchParams, ScrapperCallLogVO } from "#/api/scrapper-call-log";

import { defineAsyncComponent, onMounted, reactive, ref } from "vue";

import { Page } from "@vben/common-ui";
import { formatDateTime } from "@vben/utils";

import { getAdminScrapperCallLogListApi } from "#/api/scrapper-call-log";

const ElCard = defineAsyncComponent(() =>
  Promise.all([
    import("element-plus/es/components/card/index"),
    import("element-plus/es/components/card/style/css"),
  ]).then(([res]) => res.ElCard)
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

const ElPagination = defineAsyncComponent(() =>
  Promise.all([
    import("element-plus/es/components/pagination/index"),
    import("element-plus/es/components/pagination/style/css"),
  ]).then(([res]) => res.ElPagination)
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

const loading = ref(false);
const tableData = ref<ScrapperCallLogVO[]>([]);

const pagination = reactive({
  currentPage: 1,
  pageSize: 10,
  total: 0,
});

const searchForm = reactive<Pick<ScrapperCallLogSearchParams, "urlHost" | "success">>({
  urlHost: "",
  success: undefined,
});

async function fetchData() {
  loading.value = true;
  try {
    const res = await getAdminScrapperCallLogListApi({
      urlHost: searchForm.urlHost || undefined,
      success: searchForm.success,
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

function handleSearch() {
  pagination.currentPage = 1;
  fetchData();
}

function handleReset() {
  searchForm.urlHost = "";
  searchForm.success = undefined;
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
      <ElTable :data="tableData" border v-loading="loading" style="width: 100%">
        <ElTableColumn type="index" label="#" width="50" />
        <ElTableColumn prop="urlHost" label="域名" min-width="160" />
        <ElTableColumn prop="url" label="请求URL" min-width="240" show-overflow-tooltip />
        <ElTableColumn prop="success" label="结果" width="90">
          <template #default="{ row }">
            <ElTag v-if="row.success" type="success" size="small"> 成功 </ElTag>
            <ElTag v-else type="danger" size="small"> 失败 </ElTag>
          </template>
        </ElTableColumn>
        <ElTableColumn prop="httpStatus" label="HTTP状态" width="100" />
        <ElTableColumn prop="source" label="来源" width="120" />
        <ElTableColumn prop="cached" label="缓存命中" width="100">
          <template #default="{ row }">
            <ElTag v-if="row.cached" type="info" size="small"> 命中 </ElTag>
            <span v-else>-</span>
          </template>
        </ElTableColumn>
        <ElTableColumn prop="durationMs" label="耗时(ms)" width="100" />
        <ElTableColumn prop="errorMsg" label="错误信息" min-width="200">
          <template #default="{ row }">
            <ElTooltip v-if="row.errorMsg" :content="row.errorMsg" placement="top">
              <span class="line-clamp-1">{{ row.errorMsg }}</span>
            </ElTooltip>
            <span v-else>-</span>
          </template>
        </ElTableColumn>
        <ElTableColumn prop="createTime" label="调用时间" width="200">
          <template #default="{ row }">
            {{ formatDateTime(row.createTime) }}
          </template>
        </ElTableColumn>
      </ElTable>
      <div class="mt-4 flex justify-end">
        <ElPagination v-model:current-page="pagination.currentPage" v-model:page-size="pagination.pageSize" :page-sizes="[10, 20, 50, 100]" :total="pagination.total" layout="total, sizes, prev, pager, next, jumper" @current-change="handleCurrentChange" @size-change="handleSizeChange" />
      </div>
    </ElCard>
  </Page>
</template>
