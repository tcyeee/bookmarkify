<script lang="ts" setup>
/**
 * 系统配置：全局参数的唯一入口。
 *
 * 这些参数存在 `system_config` 里（一组配置一个 key，值是整份 JSON），跟任何一个业务列表页都不是同一件事 ——
 * 它们此前挂在「书签检查配置」那张本该是日志页的路由下，加第二组配置时无处可放。
 *
 * 版式刻意不跟其他页面一样：列表页那套卡片 + 表格是为「看很多行」设计的，而设置页要看的是
 * 「一项是什么意思、现在是多少」。所以左侧分类、右侧一行一项，长解释收进帮助图标的 tooltip，
 * 不再在页面底部堆一大段与具体某一行对不上号的说明。
 *
 * 加一组配置 = 往 CATEGORIES 里加一条，组件自带取数与保存。最后一条「变更记录」不是设置组，
 * 而是这一页所有保存动作的留痕，所以在导航里用一条分隔线与上面的设置分类隔开。
 */
import type { Component } from 'vue';

import { computed, markRaw, onMounted, ref } from 'vue';

import { Page } from '@vben/common-ui';

import BookmarkCleanupSection from './BookmarkCleanupSection.vue';
import ConfigChangeLogSection from './ConfigChangeLogSection.vue';
import LivenessCheckSection from './LivenessCheckSection.vue';
import LivenessRetrySection from './LivenessRetrySection.vue';
import { useLivenessConfig } from './useLivenessConfig';

interface SettingCategory {
  component: Component;
  desc: string;
  key: string;
  /** 与上一条之间画一条分隔线：用于把「不是设置项」的条目分出来 */
  separated?: boolean;
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
  // 清理不是"设置"——它是一次有副作用且不可撤销的动作，所以与上面两组设置之间画一条线。
  // 放在这一页而不是书签管理页，是因为它的判据（多久算失活、失败几次才归档）正是上面配的那几项。
  {
    component: markRaw(BookmarkCleanupSection),
    desc: '删除已无人收藏、且不会再有内容的页面与站点',
    key: 'cleanup',
    separated: true,
    title: '书签清理',
  },
  {
    component: markRaw(ConfigChangeLogSection),
    desc: '谁在什么时候把哪一项改成了什么',
    key: 'change-log',
    separated: true,
    title: '变更记录',
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
        <template v-for="category in CATEGORIES" :key="category.key">
          <hr v-if="category.separated" class="border-border my-2 border-t border-solid border-x-0 border-b-0" />
          <button
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
        </template>
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
