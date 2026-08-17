<script lang="ts" setup>
import type { UserAdminVO } from "#/api/user-manage";

import { computed, ref, watch } from "vue";

const props = withDefaults(
  defineProps<{
    /** 只显示头像：详情弹窗头部右侧已单独排了昵称，再画一遍就重复了 */
    hideName?: boolean;
    /** 头像直径(px)。表格行 24，详情头部 48 */
    size?: number;
    /** 为空表示这一行没有关联用户（如全部收录者都已删除） */
    user?: null | UserAdminVO;
  }>(),
  { size: 24 },
);

// 头像地址是限时签名的，过期或源图 404 时会加载失败；这时退回首字母色块，
// 而不是留一个破图 —— 表格里一整列破图比没有头像更难看
const loadError = ref(false);
watch(
  () => props.user?.avatarUrl,
  () => {
    loadError.value = false;
  },
);

const showAvatar = computed(() => Boolean(props.user?.avatarUrl) && !loadError.value);

/** 首字母色块的字：昵称首字符，取不到时用问号占位 */
const initial = computed(() => props.user?.nickName?.trim()?.charAt(0)?.toUpperCase() || "?");

/**
 * 色块底色按用户ID散列，同一个用户在任何页面都是同一个颜色 —— 随机取色的话
 * 每次翻页颜色都在跳，反而让人以为换了个人。
 */
const MONOGRAM_COLORS = [
  "#3b82f6",
  "#8b5cf6",
  "#ec4899",
  "#f97316",
  "#10b981",
  "#0ea5e9",
  "#f59e0b",
  "#6366f1",
];
const monogramColor = computed(() => {
  const id = props.user?.id ?? "";
  let hash = 0;
  for (let i = 0; i < id.length; i++) hash = (hash * 31 + id.codePointAt(i)!) % 997;
  return MONOGRAM_COLORS[hash % MONOGRAM_COLORS.length];
});

// 首字母跟着头像一起放大，写死字号在 48px 的详情头像里会缩成一个小点
const avatarStyle = computed(() => ({
  width: `${props.size}px`,
  height: `${props.size}px`,
  fontSize: `${Math.round(props.size * 0.45)}px`,
}));

const identityStyle = computed(() => ({
  // 首字母头像与容器使用同一色相；透明度足够低，不会抢昵称和表格数据的视觉重点。
  backgroundColor: `${monogramColor.value}12`,
}));
</script>

<template>
  <div v-if="user" class="user-identity" :style="identityStyle" :title="user.nickName">
    <img
      v-if="showAvatar"
      class="user-identity__avatar"
      :style="avatarStyle"
      :src="user.avatarUrl!"
      alt=""
      draggable="false"
      @error="loadError = true"
    />
    <span
      v-else
      class="user-identity__avatar user-identity__monogram"
      :style="{ ...avatarStyle, backgroundColor: monogramColor }"
    >
      {{ initial }}
    </span>
    <span v-if="!hideName" class="user-identity__name">{{ user.nickName }}</span>
  </div>
  <span v-else class="text-gray-400">-</span>
</template>

<style scoped>
.user-identity {
  display: flex;
  gap: 8px;
  align-items: center;
  min-width: 0;
  padding: 3px 8px 3px 4px;
  border-radius: 999px;
  transition: background-color 0.15s ease;
}

/* 尺寸由 avatarStyle 内联给出 */
.user-identity__avatar {
  flex: none;
  object-fit: cover;
  border-radius: 50%;
}

.user-identity__monogram {
  display: flex;
  align-items: center;
  justify-content: center;
  font-weight: 600;
  line-height: 1;
  color: #fff;
}

.user-identity__name {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
</style>
