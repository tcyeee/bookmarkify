<script lang="ts" setup>
/**
 * 系统配置：全局参数的唯一入口。
 *
 * 这些参数存在 `system_config` 里，一条一个 key，跟任何一个业务列表页都不是同一件事 ——
 * 它们此前挂在「书签检查配置」那张本该是日志页的路由下，加第二组配置时无处可放。
 *
 * 版式刻意不跟其他页面一样：列表页那套卡片 + 表格是为「看很多行」设计的，而设置页要看的是
 * 「一项是什么意思、现在是多少」。所以左侧分类、右侧一行一项，长解释收进帮助图标的 tooltip，
 * 不再在页面底部堆一大段与具体某一行对不上号的说明。
 *
 * 加一组配置 = 往 CATEGORIES 里加一条，组件自带取数与保存。
 */
import type { Component } from 'vue';

import { computed, markRaw, onMounted, ref } from 'vue';

import { Page } from '@vben/common-ui';

import LivenessCheckSection from './LivenessCheckSection.vue';
import LivenessRetrySection from './LivenessRetrySection.vue';
import { useLivenessConfig } from './useLivenessConfig';

interface SettingCategory {
  component: Component;
  desc: string;
  key: string;
  title: string;
}

const CATEGORIES: SettingCategory[] = [
  {
    component: markRaw(LivenessCheckSection),
    desc: '书签活性探测的频率，以及失败几次才算失活',
    key: 'liveness-check',
    title: '巡检与判定',
  },
  {
    component: markRaw(LivenessRetrySection),
    desc: '探测失败后的重试退避曲线与归档阈值',
    key: 'liveness-retry',
    title: '重试与归档',
  },
];

const activeKey = ref(CATEGORIES[0]!.key);
const active = computed(
  () => CATEGORIES.find((item) => item.key === activeKey.value) ?? CATEGORIES[0]!,
);

// 两个分类共用一份配置，取数放在页面这一层做一次，切分类不再重复请求
const { load, loading } = useLivenessConfig();

onMounted(() => {
  load();
});
</script>

<template>
  <Page auto-content-height>
    <div class="flex h-full gap-4">
      <aside
        class="bg-card border-border w-52 shrink-0 overflow-y-auto rounded-lg border border-solid p-2"
      >
        <button
          v-for="category in CATEGORIES"
          :key="category.key"
          class="mb-1 block w-full cursor-pointer rounded-md border-none px-3 py-2 text-left text-sm transition-colors"
          :class="
            activeKey === category.key
              ? 'bg-accent text-foreground font-medium'
              : 'text-muted-foreground hover:bg-accent hover:text-foreground bg-transparent'
          "
          type="button"
          @click="activeKey = category.key"
        >
          {{ category.title }}
        </button>
      </aside>

      <section
        v-loading="loading"
        class="bg-card border-border flex-1 overflow-y-auto rounded-lg border border-solid px-6 py-5"
      >
        <header class="border-border mb-2 border-b border-solid border-x-0 border-t-0 pb-4">
          <h2 class="text-foreground m-0 text-base font-medium">
            {{ active.title }}
          </h2>
          <p class="text-muted-foreground mt-1 text-xs">{{ active.desc }}</p>
        </header>

        <component :is="active.component" />
      </section>
    </div>
  </Page>
</template>
