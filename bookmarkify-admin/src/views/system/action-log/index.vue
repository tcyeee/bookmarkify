<script lang="ts" setup>
import type { AdminActionLogVO } from '#/api/admin-action-log';

import { reactive, ref } from 'vue';

import { Page } from '@vben/common-ui';
import { formatDateTime } from '@vben/utils';

import {
  ElButton,
  ElCard,
  ElDatePicker,
  ElDialog,
  ElInput,
  ElSwitch,
  ElTag,
  ElTooltip,
} from '#/adapter/element';
import { useVbenVxeGrid, type VxeGridProps } from '#/adapter/vxe-table';
import { getAdminActionLogListApi } from '#/api/admin-action-log';
import { FilterBar, FilterItem, useAutoSearch } from '#/components/filter-bar';

interface SearchForm {
  keyword: string;
  onlyFailed: boolean;
  createRange?: [string, string];
}

const INITIAL: SearchForm = { keyword: '', onlyFailed: false, createRange: undefined };

const searchForm = reactive<SearchForm>({ ...INITIAL });

// 详情弹窗：入参摘要与失败原因都可能很长，塞进表格一列只会被截断
const detailVisible = ref(false);
const current = ref<AdminActionLogVO | null>(null);

function openDetail(row: AdminActionLogVO) {
  current.value = row;
  detailVisible.value = true;
}

const gridOptions: VxeGridProps<AdminActionLogVO> = {
  id: 'admin-action-log',
  columns: [
    { type: 'seq', title: '#', width: 50 },
    { field: 'action', title: '动作', width: 180, slots: { default: 'action' } },
    { field: 'operatorName', title: '操作者', width: 140, slots: { default: 'operator' } },
    { field: 'targetId', title: '操作对象', width: 220, showOverflow: 'tooltip' },
    { field: 'path', title: '接口', minWidth: 260, showOverflow: 'tooltip' },
    {
      field: 'durationMs',
      title: '耗时',
      width: 90,
      formatter: ({ cellValue }) => `${cellValue ?? 0}ms`,
    },
    {
      field: 'createTime',
      title: '发生时间',
      width: 180,
      formatter: ({ cellValue }) => formatDateTime(cellValue),
    },
    { title: '操作', width: 80, fixed: 'right', slots: { default: 'operate' } },
  ],
  toolbarConfig: { custom: true, refresh: true },
  pagerConfig: { pageSize: 50 },
  proxyConfig: {
    ajax: {
      query: async ({ page }) => {
        const res = await getAdminActionLogListApi({
          keyword: searchForm.keyword || undefined,
          // false 不下发：后端把它当「只看失败」的开关，传 false 等于不过滤，但显式发一个
          // false 会让 URL/请求体里多一个无意义的条件
          onlyFailed: searchForm.onlyFailed || undefined,
          createTimeFrom: searchForm.createRange?.[0],
          createTimeTo: searchForm.createRange?.[1],
          currentPage: page.currentPage,
          pageSize: page.pageSize,
        });
        return { items: res.records, total: res.total };
      },
    },
  },
};

const [Grid, gridApi] = useVbenVxeGrid({ gridOptions });

const { reset } = useAutoSearch(searchForm, () => gridApi.reload(), { initial: { ...INITIAL } });
</script>

<template>
  <Page auto-content-height>
    <ElCard shadow="never">
      <template #header>
        <div class="flex items-center justify-between">
          <span>操作审计</span>
          <span class="text-xs text-gray-400">
            记录带 @AdminAudit 的后台动作（删除、下架、清理、重算、登录…），失败的操作同样留痕；保留 2 年
          </span>
        </div>
      </template>
      <FilterBar class="mb-4" @reset="reset">
        <FilterItem label="关键字" width="240px">
          <ElInput v-model="searchForm.keyword" placeholder="动作 / 接口 / 操作者" clearable />
        </FilterItem>
        <FilterItem label="只看失败" width="90px">
          <ElSwitch v-model="searchForm.onlyFailed" />
        </FilterItem>
        <FilterItem label="发生时间" width="360px">
          <ElDatePicker
            v-model="searchForm.createRange"
            type="datetimerange"
            value-format="YYYY-MM-DDTHH:mm:ss"
            :default-time="[new Date(2000, 0, 1, 0, 0, 0), new Date(2000, 0, 1, 23, 59, 59)]"
            start-placeholder="开始"
            end-placeholder="结束"
          />
        </FilterItem>
      </FilterBar>
      <Grid>
        <template #action="{ row }">
          <ElTooltip :content="row.handler" placement="top">
            <ElTag :type="row.success ? 'success' : 'danger'" size="small">
              {{ row.action }}
            </ElTag>
          </ElTooltip>
        </template>
        <template #operator="{ row }">
          <!-- 昵称是写入时的快照：管理员之后改名不该让历史记录跟着变 -->
          <span>{{ row.operatorName || row.operatorId || '-' }}</span>
        </template>
        <template #operate="{ row }">
          <ElButton link type="primary" @click="openDetail(row)">详情</ElButton>
        </template>
      </Grid>

      <ElDialog v-model="detailVisible" title="操作详情" width="720px">
        <!-- 用朴素的定义列表而不是 ElDescriptions：本项目的详情弹窗一律是自己排的网格
             （见 AiCallDetailDialog / FeedbackDetailDialog），element 适配层也只按需导出了
             用得到的那些组件，为一个弹窗多引一个组件模块不划算 -->
        <div v-if="current" class="detail">
          <div class="row">
            <span class="key">动作</span>
            <span>{{ current.action }}</span>
          </div>
          <div class="row">
            <span class="key">结果</span>
            <ElTag :type="current.success ? 'success' : 'danger'" size="small">
              {{ current.success ? '成功' : '失败' }}
            </ElTag>
          </div>
          <div v-if="current.errorMsg" class="row">
            <span class="key">失败原因</span>
            <span class="text-red-500">{{ current.errorMsg }}</span>
          </div>
          <div class="row">
            <!-- 昵称是写入时的快照：管理员之后改名不该让历史记录跟着变 -->
            <span class="key">操作者</span>
            <span>{{ current.operatorName || '-' }}（{{ current.operatorId || '无会话' }}）</span>
          </div>
          <div class="row">
            <span class="key">接口</span>
            <span>{{ current.httpMethod }} {{ current.path }}</span>
          </div>
          <div class="row">
            <span class="key">代码位置</span>
            <span>{{ current.handler }}</span>
          </div>
          <div class="row">
            <span class="key">操作对象</span>
            <span>{{ current.targetId || '-' }}</span>
          </div>
          <div class="row">
            <span class="key">入参摘要</span>
            <!-- 凭据字段在服务端写入前已打码(AuditParamRedactor)，这里展示的就是入库的原样 -->
            <pre class="params">{{ current.params || '-' }}</pre>
          </div>
          <div class="row">
            <span class="key">请求号</span>
            <!-- 同一串打在服务端每行文本日志上：docker logs | grep &lt;requestId&gt; 能捞出全部现场 -->
            <span>{{ current.requestId || '-' }}</span>
          </div>
          <div class="row">
            <span class="key">耗时</span>
            <span>{{ current.durationMs }}ms</span>
          </div>
          <div class="row">
            <span class="key">发生时间</span>
            <span>{{ formatDateTime(current.createTime) }}</span>
          </div>
        </div>
      </ElDialog>

    </ElCard>
  </Page>
</template>

<style scoped>
.detail {
  font-size: 13px;
}

.row {
  display: flex;
  gap: 12px;
  padding: 6px 0;
  border-bottom: 1px solid var(--el-border-color-lighter);
}

.row > .key {
  flex: 0 0 90px;
  color: var(--el-text-color-secondary);
}

.params {
  max-height: 220px;
  margin: 0;
  overflow: auto;
  font-size: 12px;
  white-space: pre-wrap;
  word-break: break-all;
}
</style>
