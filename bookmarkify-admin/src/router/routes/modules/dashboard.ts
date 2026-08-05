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
      {
        name: 'BookmarkPingLog',
        path: '/bookmark/ping-log',
        component: () => import('#/views/bookmark/ping-log/index.vue'),
        meta: {
          icon: 'carbon:cloud-logging',
          title: '书签检查配置',
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
    // 书签在库里分两层：站点(一个域名一行)与页面(一个具体地址一行)。这里刻意给两层
    // **各一张平表**，而不是一张混合表 —— 同域名下的上千个深链会把域名级的问题
    // (品牌名没抓到/整站 NSFW/域名不可达)完全淹没
    meta: {
      icon: 'lucide:globe',
      title: '网站管理',
    },
    name: 'Website',
    path: '/website',
    children: [
      {
        name: 'SiteManage',
        path: '/website/site',
        component: () => import('#/views/website/site/index.vue'),
        meta: {
          icon: 'carbon:collaborate',
          title: '站点管理',
        },
      },
      {
        // 页面层平表。跨站点的问题(「所有抓取失败的页面」)只有在这里看得到 ——
        // 那种工作流不属于任何单一站点，下钻视图永远回答不了
        name: 'PageManage',
        path: '/website/page',
        component: () => import('#/views/website/page/index.vue'),
        meta: {
          icon: 'carbon:document',
          title: '页面管理',
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
