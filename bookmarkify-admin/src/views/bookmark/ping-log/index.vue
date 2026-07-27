<script lang="ts" setup>
import type { BookmarkPingLogSearchParams, BookmarkPingLogVO } from "#/api/bookmark-ping-log";

import { defineAsyncComponent, onMounted, reactive, ref } from "vue";

import { Page } from "@vben/common-ui";
import { formatDateTime } from "@vben/utils";

import { getAdminBookmarkPingLogListApi } from "#/api/bookmark-ping-log";

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

const loading = ref(false);
const tableData = ref<BookmarkPingLogVO[]>([]);

const pagination = reactive({
  currentPage: 1,
  pageSize: 50,
  total: 0,
});

const searchForm = reactive<Pick<BookmarkPingLogSearchParams, "urlHost" | "alive">>({
  urlHost: "",
  alive: undefined,
});

async function fetchData() {
  loading.value = true;
  try {
    const res = await getAdminBookmarkPingLogListApi({
      urlHost: searchForm.urlHost || undefined,
      alive: searchForm.alive,
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
  searchForm.alive = undefined;
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
          <span>书签活性检查日志</span>
        </div>
      </template>
      <div class="mb-4">
        <ElForm :inline="true" :model="searchForm">
          <ElFormItem label="域名">
            <ElInput v-model="searchForm.urlHost" placeholder="urlHost 模糊搜索" clearable />
          </ElFormItem>
          <ElFormItem label="状态">
            <ElSelect v-model="searchForm.alive" placeholder="全部" clearable style="width: 120px">
              <ElOption label="存活" :value="true" />
              <ElOption label="失活" :value="false" />
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
        <ElTableColumn prop="alive" label="存活状态" width="100">
          <template #default="{ row }">
            <ElTag v-if="row.alive" type="success" size="small"> 存活 </ElTag>
            <ElTag v-else type="danger" size="small"> 失活 </ElTag>
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
