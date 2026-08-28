<script lang="ts" setup>
import type { FeedbackSearchParams, FeedbackTargetVO, FeedbackVO } from '#/api/feedback';

import { computed, onMounted, reactive, ref } from 'vue';

import { Page } from '@vben/common-ui';
import { formatDateTime } from '@vben/utils';

import {
  ElButton,
  ElCard,
  ElInput,
  ElMessage,
  ElMessageBox,
  ElOption,
  ElSelect,
  ElTag,
} from '#/adapter/element';
import { useVbenVxeGrid, type VxeGridProps } from '#/adapter/vxe-table';
import {
  addFeedbackTargetApi,
  deleteFeedbackApi,
  deleteFeedbackTargetApi,
  getFeedbackListApi,
  getFeedbackTargetsApi,
  getFeedbackUnreadCountApi,
  markFeedbackReadApi,
} from '#/api/feedback';
import { FilterBar, FilterItem, useAutoSearch } from '#/components/filter-bar';

import FeedbackDetailDialog from './FeedbackDetailDialog.vue';

/** 生产环境下这个公开接口的完整地址；本地开发经 Vite 代理时用相对路径也可 */
const SUBMIT_ENDPOINT = 'https://bookmarkify.cc/api/feedback/submit';

// ── 所属产品 ───────────────────────────────────────────────────────────
const targets = ref<FeedbackTargetVO[]>([]);
const unreadTotal = ref(0);
const newTargetName = ref('');
const addingTarget = ref(false);

async function loadTargets() {
  targets.value = await getFeedbackTargetsApi();
}

async function loadUnread() {
  unreadTotal.value = await getFeedbackUnreadCountApi();
}

async function handleAddTarget() {
  const name = newTargetName.value.trim();
  if (!name) return;
  addingTarget.value = true;
  try {
    await addFeedbackTargetApi(name);
    newTargetName.value = '';
    await loadTargets();
    ElMessage.success('已添加');
  } finally {
    addingTarget.value = false;
  }
}

async function handleRemoveTarget(t: FeedbackTargetVO) {
  try {
    await ElMessageBox.confirm(
      `删除产品「${t.name}」？已有的 ${t.total ?? 0} 条历史反馈会保留，但调用方将无法再用这个名字提交。`,
      '删除所属产品',
      { type: 'warning' },
    );
  } catch {
    return;
  }
  await deleteFeedbackTargetApi(t.id);
  await loadTargets();
  ElMessage.success('已删除');
}

// ── Agent 提示语 ───────────────────────────────────────────────────────
const agentPrompt = computed(() => {
  const names = targets.value.map((t) => t.name);
  const allowed = names.length > 0 ? names.join(' / ') : 'Bookmarkify';
  return [
    '当你在使用或测试过程中发现问题、或有改进建议时，请通过以下 HTTP 接口反馈给产品团队：',
    '',
    `接口：POST ${SUBMIT_ENDPOINT}`,
    '认证：无需认证',
    'Content-Type: application/json',
    '',
    '请求体字段：',
    `- target（必填）：所属产品，必须是以下之一：${allowed}`,
    '- email（选填）：你的联系邮箱，便于我们回复',
    '- content（必填）：反馈的具体内容，尽量写清复现步骤或建议细节',
    '',
    '示例：',
    JSON.stringify(
      { target: names[0] ?? 'Bookmarkify', email: 'you@example.com', content: '在 XXX 页面点击 YYY 时出现 ZZZ 错误' },
      null,
      2,
    ),
  ].join('\n');
});

async function copyPrompt() {
  try {
    await navigator.clipboard.writeText(agentPrompt.value);
    ElMessage.success('已复制到剪贴板');
  } catch {
    ElMessage.error('复制失败，请手动选择文本复制');
  }
}

// ── 收件箱 ─────────────────────────────────────────────────────────────
const searchForm = reactive<Pick<FeedbackSearchParams, 'keyword' | 'read' | 'target'>>({
  target: undefined,
  read: undefined,
  keyword: '',
});

const detailVisible = ref(false);
const detailRow = ref<FeedbackVO | null>(null);

const gridOptions: VxeGridProps<FeedbackVO> = {
  id: 'admin-feedback-inbox',
  checkboxConfig: { highlight: true },
  columns: [
    { type: 'checkbox', width: 45 },
    { type: 'seq', title: '#', width: 50 },
    { field: 'read', title: '状态', width: 80, slots: { default: 'read' } },
    { field: 'target', title: '所属产品', width: 130 },
    { field: 'content', title: '反馈内容', minWidth: 360, showOverflow: 'tooltip' },
    { field: 'email', title: '联系邮箱', width: 200, slots: { default: 'email' } },
    {
      field: 'createTime',
      title: '提交时间',
      width: 170,
      sortable: true,
      formatter: ({ cellValue }) => formatDateTime(cellValue),
    },
  ],
  toolbarConfig: { custom: true, refresh: true },
  pagerConfig: { pageSize: 20 },
  proxyConfig: {
    ajax: {
      query: async ({ page }) => {
        const res = await getFeedbackListApi({
          target: searchForm.target || undefined,
          read: searchForm.read,
          keyword: searchForm.keyword || undefined,
          currentPage: page.currentPage,
          pageSize: page.pageSize,
        });
        return { items: res.records, total: res.total };
      },
    },
  },
};

function handleCellClick({ column, row }: { column: { type?: string }; row: FeedbackVO }) {
  // 点勾选框那一列不算“查看详情”
  if (column?.type === 'checkbox') return;
  detailRow.value = row;
  detailVisible.value = true;
  // 打开详情即视为已读
  if (!row.read) void markRead([row.id], true, false);
}

async function refreshInbox() {
  await Promise.all([gridApi.reload(), loadUnread(), loadTargets()]);
}

async function markRead(ids: string[], read: boolean, notify = true) {
  if (ids.length === 0) return;
  await markFeedbackReadApi(ids, read);
  if (notify) ElMessage.success(read ? '已标记为已读' : '已标记为未读');
  await refreshInbox();
}

function selectedIds(): string[] {
  const rows = (gridApi.grid?.getCheckboxRecords() ?? []) as FeedbackVO[];
  return rows.map((r) => r.id);
}

async function batchMarkRead(read: boolean) {
  const ids = selectedIds();
  if (ids.length === 0) {
    ElMessage.warning('请先勾选反馈');
    return;
  }
  await markRead(ids, read);
}

async function batchDelete() {
  const ids = selectedIds();
  if (ids.length === 0) {
    ElMessage.warning('请先勾选反馈');
    return;
  }
  try {
    await ElMessageBox.confirm(`确认删除选中的 ${ids.length} 条反馈？此操作不可恢复。`, '批量删除', {
      type: 'warning',
    });
  } catch {
    return;
  }
  await deleteFeedbackApi(ids);
  ElMessage.success('已删除');
  await refreshInbox();
}

const [Grid, gridApi] = useVbenVxeGrid({
  gridOptions,
  gridEvents: { cellClick: handleCellClick },
});

const { reset } = useAutoSearch(searchForm, () => gridApi.reload());

onMounted(() => {
  void loadTargets();
  void loadUnread();
});
</script>

<template>
  <Page auto-content-height>
    <!-- 介绍 + Agent 提示语 -->
    <ElCard shadow="never" class="mb-3">
      <template #header>
        <div class="flex items-center justify-between">
          <span>接口说明</span>
          <ElButton type="primary" size="small" @click="copyPrompt">复制为 Agent 提示语</ElButton>
        </div>
      </template>
      <div class="space-y-2 text-sm leading-relaxed text-gray-600 dark:text-gray-300">
        <p>
          这是一个<b>无需登录</b>的公开反馈接口，面向外部 Agent / 集成脚本。调用方带上「所属产品 +
          可选邮箱 + 反馈正文」<code>POST {{ SUBMIT_ENDPOINT }}</code>，反馈就会出现在下方收件箱里，默认未读。
        </p>
        <pre
          class="max-h-72 overflow-auto rounded border bg-gray-50 p-3 text-xs whitespace-pre-wrap dark:bg-gray-900"
          >{{ agentPrompt }}</pre
        >
      </div>
    </ElCard>

    <!-- 所属产品维护 -->
    <ElCard shadow="never" class="mb-3">
      <template #header>
        <div class="flex items-center justify-between">
          <span>所属产品（target 可选值）</span>
          <span class="text-xs text-gray-400">
            调用方提交时的 <code>target</code> 必须命中这里的某一项，否则接口返回参数错误
          </span>
        </div>
      </template>
      <div class="flex flex-wrap items-center gap-2">
        <ElTag
          v-for="t in targets"
          :key="t.id"
          size="large"
          closable
          @close="handleRemoveTarget(t)"
        >
          {{ t.name }}
          <span class="ml-1 text-xs opacity-70">
            {{ t.total ?? 0 }} 条<template v-if="(t.unread ?? 0) > 0">，{{ t.unread }} 未读</template>
          </span>
        </ElTag>
        <ElInput
          v-model="newTargetName"
          placeholder="新增产品名"
          size="small"
          style="width: 160px"
          @keyup.enter="handleAddTarget"
        />
        <ElButton size="small" :loading="addingTarget" @click="handleAddTarget">添加</ElButton>
      </div>
    </ElCard>

    <!-- 收件箱 -->
    <ElCard shadow="never">
      <template #header>
        <div class="flex items-center justify-between">
          <span>
            反馈收件箱
            <ElTag v-if="unreadTotal > 0" type="danger" size="small" class="ml-2">
              {{ unreadTotal }} 未读
            </ElTag>
          </span>
          <span class="text-xs text-gray-400">点击任意行查看详情，打开即自动标记为已读</span>
        </div>
      </template>

      <FilterBar class="mb-4" @reset="reset">
        <FilterItem label="所属产品">
          <ElSelect v-model="searchForm.target" placeholder="全部" clearable>
            <ElOption v-for="t in targets" :key="t.id" :label="t.name" :value="t.name" />
          </ElSelect>
        </FilterItem>
        <FilterItem label="状态" width="120px">
          <ElSelect v-model="searchForm.read" placeholder="全部" clearable>
            <ElOption label="未读" :value="false" />
            <ElOption label="已读" :value="true" />
          </ElSelect>
        </FilterItem>
        <FilterItem label="关键字" width="220px">
          <ElInput v-model="searchForm.keyword" placeholder="正文 / 邮箱" clearable />
        </FilterItem>
      </FilterBar>

      <div class="mb-3 flex gap-2">
        <ElButton size="small" @click="batchMarkRead(true)">标记为已读</ElButton>
        <ElButton size="small" @click="batchMarkRead(false)">标记为未读</ElButton>
        <ElButton size="small" type="danger" @click="batchDelete">删除</ElButton>
      </div>

      <Grid>
        <template #read="{ row }">
          <ElTag :type="row.read ? 'info' : 'danger'" size="small">
            {{ row.read ? '已读' : '未读' }}
          </ElTag>
        </template>
        <template #email="{ row }">
          {{ row.email || '-' }}
        </template>
      </Grid>
    </ElCard>

    <FeedbackDetailDialog v-model="detailVisible" :row="detailRow" />
  </Page>
</template>
