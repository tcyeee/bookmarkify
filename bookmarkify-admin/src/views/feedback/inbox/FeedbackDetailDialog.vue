<script lang="ts" setup>
import type { FeedbackVO } from '#/api/feedback';

import { formatDateTime } from '@vben/utils';

import { ElDialog, ElTag } from '#/adapter/element';

defineProps<{
  /** 当前查看的反馈行；列表接口已带回全部字段，无需再请求详情 */
  row?: FeedbackVO | null;
}>();

const visible = defineModel<boolean>({ default: false });
</script>

<template>
  <ElDialog v-model="visible" title="反馈详情" width="680px" top="8vh">
    <div v-if="row" class="space-y-4 text-sm">
      <div class="grid grid-cols-2 gap-x-6 gap-y-2">
        <div>
          <span class="text-gray-400">所属产品：</span>
          <span>{{ row.target }}</span>
        </div>
        <div>
          <span class="text-gray-400">状态：</span>
          <ElTag :type="row.read ? 'info' : 'danger'" size="small">
            {{ row.read ? '已读' : '未读' }}
          </ElTag>
        </div>
        <div>
          <span class="text-gray-400">联系邮箱：</span>
          <span>{{ row.email || '未留' }}</span>
        </div>
        <div>
          <span class="text-gray-400">提交时间：</span>
          <span>{{ formatDateTime(row.createTime) }}</span>
        </div>
        <div v-if="row.readTime">
          <span class="text-gray-400">首次已读：</span>
          <span>{{ formatDateTime(row.readTime) }}</span>
        </div>
      </div>

      <div>
        <div class="mb-2 font-medium">反馈内容</div>
        <pre
          class="max-h-96 overflow-auto rounded border bg-gray-50 p-3 text-xs leading-relaxed whitespace-pre-wrap dark:bg-gray-900"
          >{{ row.content }}</pre
        >
      </div>

      <details class="text-xs text-gray-500">
        <summary class="cursor-pointer">提交现场（IP / UA，可伪造，仅供排障）</summary>
        <div class="mt-2 space-y-1">
          <div><span class="text-gray-400">IP：</span>{{ row.sourceIp || '-' }}</div>
          <div class="break-all"><span class="text-gray-400">UA：</span>{{ row.userAgent || '-' }}</div>
        </div>
      </details>
    </div>
  </ElDialog>
</template>
