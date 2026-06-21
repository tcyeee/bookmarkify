<script lang="ts" setup>
import type { NotificationItem } from "@vben/layouts";

import { computed, ref, watch } from "vue";
import { useRouter } from "vue-router";

import { AuthenticationLoginExpiredModal } from "@vben/common-ui";
import { useWatermark } from "@vben/hooks";
import { BasicLayout, Notification, UserDropdown } from "@vben/layouts";
import { preferences } from "@vben/preferences";
import { useAccessStore, useUserStore } from "@vben/stores";

import { useAuthStore } from "#/store";
import LoginForm from "#/views/_core/authentication/login.vue";

const notifications = ref<NotificationItem[]>([
  {
    id: 1,
    avatar: "https://avatar.vercel.sh/system?text=BK",
    date: "3小时前",
    isRead: true,
    message: "本轮书签清洗已完成，共处理 128 条数据。",
    title: "书签清洗完成",
  },
  {
    id: 2,
    avatar: "https://avatar.vercel.sh/check",
    date: "刚刚",
    isRead: false,
    message: "检测到 12 条书签目标站点不可访问。",
    title: "活性检测告警",
  },
  {
    id: 3,
    avatar: "https://avatar.vercel.sh/user",
    date: "2024-01-01",
    isRead: false,
    message: "今日新增注册用户 8 名。",
    title: "新用户注册",
  },
  {
    id: 4,
    avatar: "https://avatar.vercel.sh/feedback",
    date: "1天前",
    isRead: false,
    message: "有用户反馈书签解析失败，请及时处理。",
    title: "用户反馈",
  },
  {
    id: 5,
    avatar: "https://avatar.vercel.sh/workspace",
    date: "1天前",
    isRead: false,
    message: "点击查看后台工作台概览。",
    title: "前往工作台",
    link: "/workspace",
  },
  {
    id: 6,
    avatar: "https://avatar.vercel.sh/site",
    date: "1天前",
    isRead: false,
    message: "访问书签鸭官网了解最新功能。",
    title: "书签鸭官网",
    link: "https://bookmarkify.cc",
  },
]);

const router = useRouter();
const userStore = useUserStore();
const authStore = useAuthStore();
const accessStore = useAccessStore();
const { destroyWatermark, updateWatermark } = useWatermark();
const showDot = computed(() =>
  notifications.value.some((item) => !item.isRead)
);

const menus = computed(() => [
  {
    handler: () => {
      router.push({ name: "Profile" });
    },
    icon: "lucide:user",
    text: "个人中心",
  },
]);

const avatar = computed(() => {
  return userStore.userInfo?.avatar ?? preferences.app.defaultAvatar;
});

async function handleLogout() {
  await authStore.logout(false);
}

function handleNoticeClear() {
  notifications.value = [];
}

function markRead(id: number | string) {
  const item = notifications.value.find((item) => item.id === id);
  if (item) {
    item.isRead = true;
  }
}

function remove(id: number | string) {
  notifications.value = notifications.value.filter((item) => item.id !== id);
}

function handleMakeAll() {
  notifications.value.forEach((item) => (item.isRead = true));
}
watch(
  () => ({
    enable: preferences.app.watermark,
    content: preferences.app.watermarkContent,
  }),
  async ({ enable, content }) => {
    if (enable) {
      await updateWatermark({
        content:
          content ||
          `${userStore.userInfo?.username} - ${userStore.userInfo?.nickName}`,
      });
    } else {
      destroyWatermark();
    }
  },
  {
    immediate: true,
  }
);
</script>

<template>
  <BasicLayout @clear-preferences-and-logout="handleLogout">
    <template #user-dropdown>
      <UserDropdown :avatar :menus :text="userStore.userInfo?.nickName" description="tcyeee@outlook.com" tag-text="Pro" @logout="handleLogout" />
    </template>
    <template #notification>
      <Notification :dot="showDot" :notifications="notifications" @clear="handleNoticeClear" @read="(item) => item.id && markRead(item.id)" @remove="(item) => item.id && remove(item.id)" @make-all="handleMakeAll" />
    </template>
    <template #extra>
      <AuthenticationLoginExpiredModal v-model:open="accessStore.loginExpired" :avatar>
        <LoginForm />
      </AuthenticationLoginExpiredModal>
    </template>
  </BasicLayout>
</template>
