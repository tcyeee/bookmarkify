<script lang="ts" setup>
import type { BreadcrumbStyleType } from '@vben/types';

import type { IBreadcrumb } from '@vben-core/shadcn-ui';

import { computed } from 'vue';
import { useRoute, useRouter } from 'vue-router';

import { Check, Copy } from '@vben/icons';

import { VbenBreadcrumbView } from '@vben-core/shadcn-ui';
import { useClipboard } from '@vueuse/core';

interface Props {
  hideWhenOnlyOne?: boolean;
  showHome?: boolean;
  showIcon?: boolean;
  type?: BreadcrumbStyleType;
}

const props = withDefaults(defineProps<Props>(), {
  showHome: false,
  showIcon: false,
  type: 'normal',
});

const route = useRoute();
const router = useRouter();

const breadcrumbs = computed((): IBreadcrumb[] => {
  const matched = route.matched;

  const resultBreadcrumb: IBreadcrumb[] = [];

  for (const match of matched) {
    const { meta, path } = match;
    const { hideChildrenInMenu, hideInBreadcrumb, icon, name, title } =
      meta || {};
    if (hideInBreadcrumb || hideChildrenInMenu || !path) {
      continue;
    }

    resultBreadcrumb.push({
      icon,
      path: path || route.path,
      title: (title || name) as string,
    });
  }
  if (props.showHome) {
    resultBreadcrumb.unshift({
      icon: 'mdi:home-outline',
      isHome: true,
      path: '/',
    });
  }
  if (props.hideWhenOnlyOne && resultBreadcrumb.length === 1) {
    return [];
  }

  return resultBreadcrumb;
});

function handleSelect(path: string) {
  router.push(path);
}

/** 当前页面组件的源码路径，由 vite 插件构建期注入到 meta.source */
const source = computed(() => route.meta?.source as string | undefined);

const { copied, copy } = useClipboard({ legacy: true });
</script>
<template>
  <div class="flex items-center">
    <VbenBreadcrumbView
      :breadcrumbs="breadcrumbs"
      :show-icon="showIcon"
      :style-type="type"
      class="ml-2"
      @select="handleSelect"
    />
    <button
      v-if="source && breadcrumbs.length > 0"
      :title="copied ? '已复制' : `复制页面源码路径：${source}`"
      class="text-muted-foreground hover:text-foreground hover:bg-accent ml-1 flex size-6 items-center justify-center rounded transition-colors"
      type="button"
      @click="copy(source)"
    >
      <Check v-if="copied" class="size-3.5 text-green-500" />
      <Copy v-else class="size-3.5" />
    </button>
  </div>
</template>
