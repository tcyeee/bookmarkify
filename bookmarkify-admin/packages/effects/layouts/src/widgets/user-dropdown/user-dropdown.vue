<script setup lang="ts">
import type { Component } from "vue";

import type { AnyFunction } from "@vben/types";

import { computed, useTemplateRef, watch } from "vue";

import { useHoverToggle } from "@vben/hooks";
import { LogOut } from "@vben/icons";
import { preferences, usePreferences } from "@vben/preferences";
import { isWindowsOs } from "@vben/utils";

import { useVbenModal } from "@vben-core/popup-ui";
import {
  Badge,
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuLabel,
  DropdownMenuSeparator,
  DropdownMenuShortcut,
  DropdownMenuTrigger,
  VbenAvatar,
  VbenIcon,
} from "@vben-core/shadcn-ui";

import { useMagicKeys, whenever } from "@vueuse/core";

interface Props {
  /**
   * 头像
   */
  avatar?: string;
  /**
   * @zh_CN 描述
   */
  description?: string;
  /**
   * 是否启用快捷键
   */
  enableShortcutKey?: boolean;
  /**
   * 菜单数组
   */
  menus?: Array<{
    handler: AnyFunction;
    icon?: Component | Function | string;
    text: string;
  }>;

  /**
   * 标签文本
   */
  tagText?: string;
  /**
   * 文本
   */
  text?: string;
  /** 触发方式 */
  trigger?: "both" | "click" | "hover";
  /** hover触发时，延迟响应的时间 */
  hoverDelay?: number;
}

defineOptions({
  name: "UserDropdown",
});

const props = withDefaults(defineProps<Props>(), {
  avatar: "",
  description: "",
  enableShortcutKey: true,
  menus: () => [],
  showShortcutKey: true,
  tagText: "",
  text: "",
  trigger: "click",
  hoverDelay: 500,
});

const emit = defineEmits<{ logout: [] }>();

const { globalLogoutShortcutKey } = usePreferences();
const [LogoutModal, logoutModalApi] = useVbenModal({
  onConfirm() {
    handleSubmitLogout();
  },
});

const refTrigger = useTemplateRef("refTrigger");
const refContent = useTemplateRef("refContent");
const [openPopover, hoverWatcher] = useHoverToggle(
  [refTrigger, refContent],
  () => props.hoverDelay
);

watch(
  () => props.trigger === "hover" || props.trigger === "both",
  (val) => {
    if (val) {
      hoverWatcher.enable();
    } else {
      hoverWatcher.disable();
    }
  },
  {
    immediate: true,
  }
);

const altView = computed(() => (isWindowsOs() ? "Alt" : "⌥"));

const enableLogoutShortcutKey = computed(() => {
  return props.enableShortcutKey && globalLogoutShortcutKey.value;
});

const enableShortcutKey = computed(() => {
  return props.enableShortcutKey && preferences.shortcutKeys.enable;
});

function handleLogout() {
  // emit
  logoutModalApi.open();
  openPopover.value = false;
}

function handleSubmitLogout() {
  emit("logout");
  logoutModalApi.close();
}

if (enableShortcutKey.value) {
  const keys = useMagicKeys();
  whenever(keys["Alt+KeyQ"]!, () => {
    if (enableLogoutShortcutKey.value) {
      handleLogout();
    }
  });
}
</script>

<template>
  <LogoutModal :cancel-text="'取消'" :confirm-text="'确认'" :fullscreen-button="false" :title="'提示'" centered content-class="px-8 min-h-10" footer-class="border-none mb-3 mr-3" header-class="border-none">
    {{ '是否退出登录？' }}
  </LogoutModal>

  <DropdownMenu v-model:open="openPopover">
    <DropdownMenuTrigger ref="refTrigger" :disabled="props.trigger === 'hover'">
      <div class="hover:bg-accent ml-1 mr-2 cursor-pointer rounded-full p-1.5">
        <div class="hover:text-accent-foreground flex-center">
          <VbenAvatar :alt="text" :src="avatar" class="size-8" dot />
        </div>
      </div>
    </DropdownMenuTrigger>
    <DropdownMenuContent class="mr-2 min-w-[240px] p-0 pb-1">
      <div ref="refContent">
        <DropdownMenuLabel class="flex items-center p-3">
          <VbenAvatar :alt="text" :src="avatar" class="size-12" dot dot-class="bottom-0 right-1 border-2 size-4 bg-green-500" />
          <div class="ml-2 w-full">
            <div v-if="tagText || text || $slots.tagText" class="text-foreground mb-1 flex items-center text-sm font-medium">
              {{ text }}
              <slot name="tagText">
                <Badge v-if="tagText" class="ml-2 text-green-400">
                  {{ tagText }}
                </Badge>
              </slot>
            </div>
            <div class="text-muted-foreground text-xs font-normal">
              {{ description }}
            </div>
          </div>
        </DropdownMenuLabel>
        <DropdownMenuSeparator v-if="menus?.length" />
        <DropdownMenuItem v-for="menu in menus" :key="menu.text" class="mx-1 flex cursor-pointer items-center rounded-sm py-1 leading-8" @click="menu.handler">
          <VbenIcon :icon="menu.icon" class="mr-2 size-4" />
          {{ menu.text }}
        </DropdownMenuItem>
        <DropdownMenuSeparator />
        <DropdownMenuItem class="mx-1 flex cursor-pointer items-center rounded-sm py-1 leading-8" @click="handleLogout">
          <LogOut class="mr-2 size-4" />
          {{ '退出登录' }}
          <DropdownMenuShortcut v-if="enableLogoutShortcutKey">
            {{ altView }} Q
          </DropdownMenuShortcut>
        </DropdownMenuItem>
      </div>
    </DropdownMenuContent>
  </DropdownMenu>
</template>
