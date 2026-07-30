<script lang="ts" setup>
import type { BookmarkPingLogSearchParams, BookmarkPingLogVO, PingOutcome } from "#/api/bookmark-ping-log";

import { defineAsyncComponent, onMounted, reactive, ref } from "vue";

import { Page } from "@vben/common-ui";
import { formatDateTime } from "@vben/utils";

import { ElMessage } from "element-plus";

import { getAdminBookmarkPingLogListApi } from "#/api/bookmark-ping-log";
import { getBookmarkLivenessConfigApi, saveBookmarkLivenessConfigApi } from "#/api/bookmark-liveness-config";

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

const ElInputNumber = defineAsyncComponent(() =>
  Promise.all([
    import("element-plus/es/components/input-number/index"),
    import("element-plus/es/components/input-number/style/css"),
  ]).then(([res]) => res.ElInputNumber)
);

const configLoading = ref(false);
const configSaving = ref(false);
const livenessConfig = reactive({
  activeCheckIntervalHours: 168,
  abnormalCheckIntervalHours: 24,
  contentRefreshIntervalDays: 30,
});

async function fetchLivenessConfig() {
  configLoading.value = true;
  try {
    const res = await getBookmarkLivenessConfigApi();
    livenessConfig.activeCheckIntervalHours = res.activeCheckIntervalHours;
    livenessConfig.abnormalCheckIntervalHours = res.abnormalCheckIntervalHours;
    livenessConfig.contentRefreshIntervalDays = res.contentRefreshIntervalDays;
  } finally {
    configLoading.value = false;
  }
}

async function saveLivenessConfig() {
  configSaving.value = true;
  try {
    const res = await saveBookmarkLivenessConfigApi({
      activeCheckIntervalHours: livenessConfig.activeCheckIntervalHours,
      abnormalCheckIntervalHours: livenessConfig.abnormalCheckIntervalHours,
      contentRefreshIntervalDays: livenessConfig.contentRefreshIntervalDays,
    });
    livenessConfig.activeCheckIntervalHours = res.activeCheckIntervalHours;
    livenessConfig.abnormalCheckIntervalHours = res.abnormalCheckIntervalHours;
    livenessConfig.contentRefreshIntervalDays = res.contentRefreshIntervalDays;
    ElMessage.success("已保存");
  } finally {
    configSaving.value = false;
  }
}

const loading = ref(false);
const tableData = ref<BookmarkPingLogVO[]>([]);

const pagination = reactive({
  currentPage: 1,
  pageSize: 50,
  total: 0,
});

const searchForm = reactive<Pick<BookmarkPingLogSearchParams, "urlHost" | "outcome">>({
  urlHost: "",
  outcome: undefined,
});

/** 三态结论的展示配置：标签类型 + 文案 + 悬浮说明 */
const OUTCOME_META: Record<PingOutcome, { label: string; tip: string; type: "danger" | "success" | "warning" }> = {
  ALIVE: { label: "存活", tip: "目标站点有响应", type: "success" },
  DEAD: { label: "失活", tip: "抓取服务明确判定目标站点打不开", type: "danger" },
  UNKNOWN: {
    label: "无结论",
    tip: "我方探测链路有问题（抓取服务不可达/鉴权失败/被限流），本轮未改动书签状态",
    type: "warning",
  },
};

async function fetchData() {
  loading.value = true;
  try {
    const res = await getAdminBookmarkPingLogListApi({
      urlHost: searchForm.urlHost || undefined,
      outcome: searchForm.outcome,
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
  searchForm.outcome = undefined;
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
  fetchLivenessConfig();
  fetchData();
});
</script>

<template>
  <Page auto-content-height>
    <ElCard shadow="never">
      <template #header>
        <div class="flex items-center justify-between">
          <span>书签检查配置</span>
        </div>
      </template>
      <div class="mb-4 rounded border border-solid border-gray-200 p-4 dark:border-gray-700">
        <ElForm :inline="true" :model="livenessConfig" v-loading="configLoading">
          <ElFormItem label="已激活的书签检测频率">
            <ElInputNumber v-model="livenessConfig.activeCheckIntervalHours" :min="1" :step="1" />
            <span class="ml-2 text-gray-500">小时</span>
          </ElFormItem>
          <ElFormItem label="异常书签检测频率">
            <ElInputNumber v-model="livenessConfig.abnormalCheckIntervalHours" :min="1" :step="1" />
            <span class="ml-2 text-gray-500">小时</span>
          </ElFormItem>
          <ElFormItem label="内容重新抓取间隔">
            <ElInputNumber v-model="livenessConfig.contentRefreshIntervalDays" :min="1" :step="1" />
            <span class="ml-2 text-gray-500">天</span>
          </ElFormItem>
          <ElFormItem>
            <ElButton type="primary" :loading="configSaving" @click="saveLivenessConfig">保存</ElButton>
          </ElFormItem>
        </ElForm>
        <div class="text-xs text-gray-400">
          异常书签检测频率不能低于已激活书签检测频率（即间隔小时数不能更大）；
          「检测」只发一个 HEAD 请求判断站点是否存活，「重新抓取」会走完整抓取链路更新标题与图标，代价高得多，所以间隔单独配置且不能短于检测频率。
          连续失败 10 次的书签会转入归档、停止巡检。
        </div>
      </div>
      <div class="mb-4">
        <ElForm :inline="true" :model="searchForm">
          <ElFormItem label="域名">
            <ElInput v-model="searchForm.urlHost" placeholder="urlHost 模糊搜索" clearable />
          </ElFormItem>
          <ElFormItem label="状态">
            <ElSelect v-model="searchForm.outcome" placeholder="全部" clearable style="width: 130px">
              <ElOption label="存活" value="ALIVE" />
              <ElOption label="失活" value="DEAD" />
              <ElOption label="无结论" value="UNKNOWN" />
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
        <ElTableColumn prop="urlHost" label="域名" min-width="200" />
        <ElTableColumn prop="bookmarkId" label="书签ID" min-width="260" show-overflow-tooltip />
        <ElTableColumn prop="outcome" label="探测结论" width="110">
          <template #default="{ row }">
            <ElTag :type="OUTCOME_META[row.outcome]?.type ?? 'info'" size="small" :title="OUTCOME_META[row.outcome]?.tip">
              {{ OUTCOME_META[row.outcome]?.label ?? row.outcome }}
            </ElTag>
          </template>
        </ElTableColumn>
        <ElTableColumn prop="triggeredParse" label="是否触发重新解析" width="150">
          <template #default="{ row }">
            <ElTag v-if="row.triggeredParse" type="info" size="small"> 是 </ElTag>
            <span v-else>-</span>
          </template>
        </ElTableColumn>
        <ElTableColumn prop="createTime" label="检查时间" width="200">
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
