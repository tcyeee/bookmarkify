<script lang="ts" setup>
import type { UserAdminVO, UserStatus } from "#/api/user-manage";

import { computed, defineAsyncComponent } from "vue";

import { formatDateTime } from "@vben/utils";

import UserIdentityCell from "./UserIdentityCell.vue";

const props = defineProps<{
  /** 当前查看的用户；列表接口已带回完整用户视图，无需再单独请求详情 */
  user?: null | UserAdminVO;
}>();

const visible = defineModel<boolean>({ default: false });

const ElDialog = defineAsyncComponent(() =>
  Promise.all([
    import("element-plus/es/components/dialog/index"),
    import("element-plus/es/components/dialog/style/css"),
  ]).then(([res]) => res.ElDialog)
);

const ElTag = defineAsyncComponent(() =>
  Promise.all([
    import("element-plus/es/components/tag/index"),
    import("element-plus/es/components/tag/style/css"),
  ]).then(([res]) => res.ElTag)
);

/** deleted / disabled 是两个独立标记，收敛成一个互斥状态展示（与全部用户页一致） */
const status = computed<UserStatus>(() => {
  if (props.user?.deleted) return "DELETED";
  return props.user?.disabled ? "DISABLED" : "NORMAL";
});
</script>

<template>
  <ElDialog v-model="visible" title="用户详情" width="620px" top="10vh">
    <div v-if="user" class="space-y-4">
      <!-- 头部：大头像 + 昵称 + 状态，一眼认人 -->
      <div class="flex items-center gap-3">
        <UserIdentityCell :user="user" :size="48" hide-name />
        <div class="min-w-0 flex-1">
          <div class="truncate text-base font-medium">{{ user.nickName }}</div>
          <div class="mt-1 flex flex-wrap items-center gap-2">
            <ElTag v-if="user.role === 'ADMIN'" type="danger" size="small">管理员</ElTag>
            <ElTag v-else-if="user.role === 'MODERATOR'" type="warning" size="small">协管</ElTag>
            <ElTag v-else-if="user.role === 'USER'" type="success" size="small">普通用户</ElTag>
            <ElTag v-else type="info" size="small">{{ user.role || "未知" }}</ElTag>

            <ElTag v-if="status === 'DELETED'" type="info" size="small">已删除</ElTag>
            <ElTag v-else-if="status === 'DISABLED'" type="danger" size="small">禁用</ElTag>
            <ElTag v-else type="success" size="small">正常</ElTag>

            <ElTag :type="user.verified ? 'success' : 'info'" size="small">
              {{ user.verified ? "已验证" : "未验证" }}
            </ElTag>
          </div>
        </div>
      </div>

      <!-- 统计：与基础资料分开，管理员打开弹窗即可看到用户贡献规模 -->
      <div class="grid grid-cols-2 gap-3">
        <div class="stat-card">
          <div class="stat-card__label">书签数量</div>
          <div class="stat-card__value">{{ user.bookmarkCount }}</div>
        </div>
        <div class="stat-card">
          <div class="stat-card__label">生成 Token</div>
          <div class="stat-card__value">{{ user.tokenCount }}</div>
        </div>
      </div>

      <!-- 明细 -->
      <div class="grid grid-cols-1 gap-x-6 gap-y-2 text-sm md:grid-cols-2">
        <div class="col-span-1 md:col-span-2">
          <span class="text-gray-400">用户ID：</span>
          <span class="font-mono break-all">{{ user.id }}</span>
        </div>
        <div class="col-span-1 md:col-span-2">
          <span class="text-gray-400">设备UID：</span>
          <span class="font-mono break-all">{{ user.deviceId || "-" }}</span>
        </div>
        <div class="truncate">
          <span class="text-gray-400">邮箱：</span>
          <span>{{ user.email || "-" }}</span>
        </div>
        <div class="truncate">
          <span class="text-gray-400">手机号：</span>
          <span>{{ user.phone || "-" }}</span>
        </div>
        <div class="truncate">
          <span class="text-gray-400">Google：</span>
          <span>{{ user.googleEmail || "未绑定" }}</span>
        </div>
        <div class="truncate">
          <span class="text-gray-400">GitHub：</span>
          <span>{{ user.githubLogin || "未绑定" }}</span>
        </div>
        <div>
          <span class="text-gray-400">注册时间：</span>
          <span>{{ formatDateTime(user.createTime) }}</span>
        </div>
        <div>
          <span class="text-gray-400">更新时间：</span>
          <span>{{ formatDateTime(user.updateTime) }}</span>
        </div>
      </div>
    </div>
    <div v-else class="py-6 text-center text-sm text-gray-400">没有关联的用户</div>
  </ElDialog>
</template>

<style scoped>
.stat-card {
  padding: 12px 14px;
  background: var(--el-fill-color-light);
  border-radius: 8px;
}

.stat-card__label {
  color: var(--el-text-color-secondary);
  font-size: 13px;
}

.stat-card__value {
  margin-top: 4px;
  color: var(--el-text-color-primary);
  font-size: 22px;
  font-weight: 600;
  line-height: 1.2;
}
</style>
