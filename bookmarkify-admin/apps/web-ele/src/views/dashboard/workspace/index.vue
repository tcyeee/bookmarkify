<script lang="ts" setup>
import type {
  WorkbenchProjectItem,
  WorkbenchQuickNavItem,
  WorkbenchTodoItem,
  WorkbenchTrendItem,
} from '@vben/common-ui';

import { onMounted, ref } from 'vue';
import { useRouter } from 'vue-router';

import {
  AnalysisChartCard,
  WorkbenchHeader,
  WorkbenchProject,
  WorkbenchQuickNav,
  WorkbenchTodo,
  WorkbenchTrends,
} from '@vben/common-ui';
import { preferences } from '@vben/preferences';
import { useUserStore } from '@vben/stores';
import { openWindow } from '@vben/utils';

import { type NamedCount, getAnalyticsOverviewApi } from '#/api/analytics';

import AnalyticsVisitsSource from '../analytics/analytics-visits-source.vue';

const userStore = useUserStore();

const referrers = ref<NamedCount[]>([]);
onMounted(async () => {
  referrers.value = (await getAnalyticsOverviewApi()).referrers;
});

// 这是一个示例数据，实际项目中需要根据实际情况进行调整
// url 也可以是内部路由，在 navTo 方法中识别处理，进行内部跳转
// 例如：url: /dashboard/workspace
const projectItems: WorkbenchProjectItem[] = [
  {
    color: '#1fdaca',
    content: '清洗异常、重复与失效的书签数据。',
    date: '书签管理',
    group: '书签管理',
    icon: 'ion:sparkles-outline',
    title: '书签清洗',
    url: '/bookmark/cleaning',
  },
  {
    color: '#3fb27f',
    content: '检测书签目标站点的可访问性与活性。',
    date: '书签管理',
    group: '书签管理',
    icon: 'ion:pulse-outline',
    title: '书签活性检测',
    url: '/bookmark/liveness',
  },
  {
    color: '#e18525',
    content: '维护平台内置的系统级书签集。',
    date: '书签集管理',
    group: '书签集管理',
    icon: 'ion:albums-outline',
    title: '系统书签集',
    url: '/bookmark-collection/system',
  },
  {
    color: '#bf0c2c',
    content: '管理用户创建的自定义书签集。',
    date: '书签集管理',
    group: '书签集管理',
    icon: 'ion:folder-open-outline',
    title: '用户自定义书签集',
    url: '/bookmark-collection/custom',
  },
  {
    color: '#00d8ff',
    content: '查看与管理全部注册及匿名用户。',
    date: '用户管理',
    group: '用户管理',
    icon: 'ion:people-outline',
    title: '全部用户',
    url: '/user/all',
  },
  {
    color: '#EBD94E',
    content: '分析用户行为与平台使用数据。',
    date: '用户管理',
    group: '用户管理',
    icon: 'ion:bar-chart-outline',
    title: '用户行为管理',
    url: '/user/behavior',
  },
];

// 同样，这里的 url 也可以使用以 http 开头的外部链接
const quickNavItems: WorkbenchQuickNavItem[] = [
  {
    color: '#1fdaca',
    icon: 'ion:sparkles-outline',
    title: '书签清洗',
    url: '/bookmark/cleaning',
  },
  {
    color: '#bf0c2c',
    icon: 'ion:pulse-outline',
    title: '活性检测',
    url: '/bookmark/liveness',
  },
  {
    color: '#e18525',
    icon: 'ion:albums-outline',
    title: '系统书签集',
    url: '/bookmark-collection/system',
  },
  {
    color: '#3fb27f',
    icon: 'ion:folder-open-outline',
    title: '自定义书签集',
    url: '/bookmark-collection/custom',
  },
  {
    color: '#4daf1bc9',
    icon: 'ion:people-outline',
    title: '全部用户',
    url: '/user/all',
  },
  {
    color: '#00d8ff',
    icon: 'ion:bar-chart-outline',
    title: '分析页',
    url: '/analytics',
  },
];

const todoItems = ref<WorkbenchTodoItem[]>([
  {
    completed: false,
    content: `审查最近提交到Git仓库的前端代码，确保代码质量和规范。`,
    date: '2024-07-30 11:00:00',
    title: '审查前端代码提交',
  },
  {
    completed: true,
    content: `检查并优化系统性能，降低CPU使用率。`,
    date: '2024-07-30 11:00:00',
    title: '系统性能优化',
  },
  {
    completed: false,
    content: `进行系统安全检查，确保没有安全漏洞或未授权的访问。 `,
    date: '2024-07-30 11:00:00',
    title: '安全检查',
  },
  {
    completed: false,
    content: `更新项目中的所有npm依赖包，确保使用最新版本。`,
    date: '2024-07-30 11:00:00',
    title: '更新项目依赖',
  },
  {
    completed: false,
    content: `修复用户报告的页面UI显示问题，确保在不同浏览器中显示一致。 `,
    date: '2024-07-30 11:00:00',
    title: '修复UI显示问题',
  },
]);
const trendItems: WorkbenchTrendItem[] = [
  {
    avatar: 'svg:avatar-1',
    content: `清洗了 <a>失效书签</a>，共处理 128 条`,
    date: '刚刚',
    title: '系统',
  },
  {
    avatar: 'svg:avatar-2',
    content: `完成了一轮 <a>书签活性检测</a>`,
    date: '1个小时前',
    title: '系统',
  },
  {
    avatar: 'svg:avatar-3',
    content: `更新了 <a>系统书签集</a> 的分类`,
    date: '1天前',
    title: '管理员',
  },
  {
    avatar: 'svg:avatar-4',
    content: `新增用户自定义书签集 <a>前端学习</a>`,
    date: '2天前',
    title: '用户',
  },
  {
    avatar: 'svg:avatar-1',
    content: `处理了 <a>用户反馈</a> 的书签解析问题`,
    date: '3天前',
    title: '管理员',
  },
  {
    avatar: 'svg:avatar-2',
    content: `匿名会话升级为 <a>注册用户</a>`,
    date: '1周前',
    title: '用户',
  },
  {
    avatar: 'svg:avatar-3',
    content: `归档了一批 <a>长期未访问书签</a>`,
    date: '1周前',
    title: '系统',
  },
  {
    avatar: 'svg:avatar-4',
    content: `导入了 <a>浏览器书签</a>，共 56 条`,
    date: '2021-04-01 20:00',
    title: '用户',
  },
];

const router = useRouter();

// 这是一个示例方法，实际项目中需要根据实际情况进行调整
// This is a sample method, adjust according to the actual project requirements
function navTo(nav: WorkbenchProjectItem | WorkbenchQuickNavItem) {
  if (nav.url?.startsWith('http')) {
    openWindow(nav.url);
    return;
  }
  if (nav.url?.startsWith('/')) {
    router.push(nav.url).catch((error) => {
      console.error('Navigation failed:', error);
    });
  } else {
    console.warn(`Unknown URL for navigation item: ${nav.title} -> ${nav.url}`);
  }
}
</script>

<template>
  <div class="p-5">
    <WorkbenchHeader
      :avatar="userStore.userInfo?.avatar || preferences.app.defaultAvatar"
    >
      <template #title>
        早安, {{ userStore.userInfo?.nickName }}, 开始您一天的工作吧！
      </template>
      <template #description> 欢迎使用书签鸭后台管理系统！ </template>
    </WorkbenchHeader>

    <div class="mt-5 flex flex-col lg:flex-row">
      <div class="mr-4 w-full lg:w-3/5">
        <WorkbenchProject :items="projectItems" title="项目" @click="navTo" />
        <WorkbenchTrends :items="trendItems" class="mt-5" title="最新动态" />
      </div>
      <div class="w-full lg:w-2/5">
        <WorkbenchQuickNav
          :items="quickNavItems"
          class="mt-5 lg:mt-0"
          title="快捷导航"
          @click="navTo"
        />
        <WorkbenchTodo :items="todoItems" class="mt-5" title="待办事项" />
        <AnalysisChartCard class="mt-5" title="访问来源">
          <AnalyticsVisitsSource :data="referrers" />
        </AnalysisChartCard>
      </div>
    </div>
  </div>
</template>
