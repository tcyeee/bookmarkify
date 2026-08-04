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
    // 站点层：一个域名一行。与「书签管理」下的页面层刻意分成两个菜单 ——
    // 同域名下的上千个深链会把域名级的问题(品牌名没抓到/整站 NSFW/域名不可达)完全淹没
    meta: {
      icon: 'lucide:globe',
      title: '网站管理',
    },
    name: 'Website',
    path: '/website',
    children: [
      {
        // 站点→页面的下钻视图。与下面那张纯站点平表是**增量关系**，不是替代：
        // 「所有抓取失败的页面」这类跨站点的问题不属于任何单一站点，从这里永远看不到，
        // 那种工作流依然要走「书签管理 › 书签管理」的页面平表
        name: 'WebsiteExplorer',
        path: '/website/explorer',
        component: () => import('#/views/website/explorer/index.vue'),
        meta: {
          icon: 'carbon:tree-view-alt',
          title: '站点与页面',
        },
      },
      {
        name: 'AllWebsites',
        path: '/website/all',
        component: () => import('#/views/website/all/index.vue'),
        meta: {
          icon: 'carbon:collaborate',
          title: '全部网站管理',
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
