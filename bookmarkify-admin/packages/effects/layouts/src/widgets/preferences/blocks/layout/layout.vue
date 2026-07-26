<script setup lang="ts">
import type { Component } from 'vue';

import type { LayoutType } from '@vben/types';

import { computed } from 'vue';

import { CircleHelp } from '@vben/icons';

import { VbenTooltip } from '@vben-core/shadcn-ui';

import {
  FullContent,
  HeaderMixedNav,
  HeaderNav,
  HeaderSidebarNav,
  MixedNav,
  SidebarMixedNav,
  SidebarNav,
} from '../../icons';

interface PresetItem {
  name: string;
  tip: string;
  type: LayoutType;
}

defineOptions({
  name: 'PreferenceLayout',
});

const modelValue = defineModel<LayoutType>({ default: 'sidebar-nav' });

const components: Record<LayoutType, Component> = {
  'full-content': FullContent,
  'header-nav': HeaderNav,
  'mixed-nav': MixedNav,
  'sidebar-mixed-nav': SidebarMixedNav,
  'sidebar-nav': SidebarNav,
  'header-mixed-nav': HeaderMixedNav,
  'header-sidebar-nav': HeaderSidebarNav,
};

const PRESET = computed((): PresetItem[] => [
  {
    name: '垂直',
    tip: '侧边垂直菜单模式',
    type: 'sidebar-nav',
  },
  {
    name: '双列菜单',
    tip: '垂直双列菜单模式',
    type: 'sidebar-mixed-nav',
  },
  {
    name: '水平',
    tip: '水平菜单模式，菜单全部显示在顶部',
    type: 'header-nav',
  },
  {
    name: '侧边导航',
    tip: '顶部通栏，侧边导航模式',
    type: 'header-sidebar-nav',
  },
  {
    name: '混合垂直',
    tip: '垂直水平菜单共存',
    type: 'mixed-nav',
  },
  {
    name: '混合双列',
    tip: '双列、水平菜单共存模式',
    type: 'header-mixed-nav',
  },
  {
    name: '内容全屏',
    tip: '不显示任何菜单，只显示内容主体',
    type: 'full-content',
  },
]);

function activeClass(theme: string): string[] {
  return theme === modelValue.value ? ['outline-box-active'] : [];
}
</script>

<template>
  <div class="flex w-full flex-wrap gap-5">
    <template v-for="theme in PRESET" :key="theme.name">
      <div
        class="flex w-[100px] cursor-pointer flex-col"
        @click="modelValue = theme.type"
      >
        <div :class="activeClass(theme.type)" class="outline-box flex-center">
          <component :is="components[theme.type]" />
        </div>
        <div
          class="text-muted-foreground flex-center hover:text-foreground mt-2 text-center text-xs"
        >
          {{ theme.name }}
          <VbenTooltip v-if="theme.tip" side="bottom">
            <template #trigger>
              <CircleHelp class="ml-1 size-3 cursor-help" />
            </template>
            {{ theme.tip }}
          </VbenTooltip>
        </div>
      </div>
    </template>
  </div>
</template>
