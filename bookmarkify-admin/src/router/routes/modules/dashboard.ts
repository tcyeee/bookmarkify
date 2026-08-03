import type { RouteRecordRaw } from 'vue-router';


const routes: RouteRecordRaw[] = [
  {
    meta: {
      icon: 'lucide:layout-dashboard',
      order: -1,
      title: '概览',
    },
    name: 'Dashboard',
    path: '/dashboard',
    children: [
      {
        name: 'Analytics',
        path: '/analytics',
        component: () => import('#/views/dashboard/analytics/index.vue'),
        meta: {
          affixTab: true,
          icon: 'lucide:area-chart',
          title: '分析页',
        },
      },
    ],
  },
  {
    meta: {
      icon: 'lucide:bookmark',
      title: '书签管理',
    },
    name: 'Bookmark',
    path: '/bookmark',
    children: [
      {
        name: 'BookmarkCleaning',
        path: '/bookmark/cleaning',
        component: () => import('#/views/bookmark/cleaning/index.vue'),
        meta: {
          icon: 'carbon:workspace',
          title: '书签管理',
        },
      },
      {
        name: 'BookmarkLiveness',
        path: '/bookmark/liveness',
        component: () => import('#/views/bookmark/liveness/index.vue'),
        meta: {
          icon: 'carbon:workspace',
          title: '书签图标管理',
        },
      },
      {
        name: 'BookmarkCategory',
        path: '/bookmark/category',
        component: () => import('#/views/bookmark/category/index.vue'),
        meta: {
          icon: 'carbon:tag',
          title: '分类管理',
        },
      },
      {
        name: 'BookmarkPingLog',
        path: '/bookmark/ping-log',
        component: () => import('#/views/bookmark/ping-log/index.vue'),
        meta: {
          icon: 'carbon:cloud-logging',
          title: '书签检查配置',
        },
      },
      {
        name: 'WebsiteManagement',
        path: '/bookmark/website',
        component: () => import('#/views/bookmark/website/index.vue'),
        meta: {
          icon: 'carbon:workspace',
          title: '网站管理',
        },
      },
    ],
  },
  {
    meta: {
      icon: 'lucide:plug',
      title: '第三方管理',
    },
    // 分组下每个子页面的 path 按它对接的第三方服务命名（/scrapper、/ai），
    // 分组本身只是个不落地的容器，沿用旧路径反而会让 AI 页看着像 scrapper 的一部分
    name: 'ThirdParty',
    path: '/third-party',
    children: [
      {
        name: 'ScrapperCallLog',
        path: '/scrapper/call-log',
        component: () => import('#/views/scrapper/call-log/index.vue'),
        meta: {
          icon: 'carbon:cloud-logging',
          title: 'Scrapper调用日志',
        },
      },
      {
        name: 'ScrapperSweep',
        path: '/scrapper/sweep',
        component: () => import('#/views/scrapper/sweep/index.vue'),
        meta: {
          icon: 'carbon:activity',
          title: 'Scrapper 巡检健康',
        },
      },
      {
        name: 'ScrapperCheck',
        path: '/scrapper/check',
        component: () => import('#/views/scrapper/check/index.vue'),
        meta: {
          icon: 'carbon:debug',
          title: 'Scrapper 测试台',
        },
      },
      {
        name: 'AiCallLog',
        path: '/ai/call-log',
        component: () => import('#/views/ai/call-log/index.vue'),
        meta: {
          icon: 'carbon:machine-learning-model',
          title: 'AI检测管理',
        },
      },
    ],
  },
  {
    meta: {
      icon: 'lucide:library',
      title: '书签集管理',
    },
    name: 'BookmarkCollection',
    path: '/bookmark-collection',
    children: [
      {
        name: 'SystemBookmarkCollection',
        path: '/bookmark-collection/system',
        component: () => import('#/views/bookmark-collection/system/index.vue'),
        meta: {
          icon: 'carbon:workspace',
          title: '系统书签集',
        },
      },
      {
        name: 'CustomBookmarkCollection',
        path: '/bookmark-collection/custom',
        component: () => import('#/views/bookmark-collection/custom/index.vue'),
        meta: {
          icon: 'carbon:workspace',
          title: '用户自定义书签集',
        },
      },
    ],
  },
  {
    meta: {
      icon: 'lucide:users',
      title: '用户管理',
    },
    name: 'UserManagement',
    path: '/user',
    children: [
      {
        name: 'AllUsers',
        path: '/user/all',
        component: () => import('#/views/user/all/index.vue'),
        meta: {
          icon: 'carbon:workspace',
          title: '全部用户',
        },
      },
      {
        name: 'UserBehavior',
        path: '/user/behavior',
        component: () => import('#/views/user/behavior/index.vue'),
        meta: {
          icon: 'carbon:workspace',
          title: '用户行为管理',
        },
      },
    ],
  },
];

export default routes;
