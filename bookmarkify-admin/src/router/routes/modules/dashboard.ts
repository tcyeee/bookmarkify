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
      title: '书签集管理',
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
        // 紧挨着调用日志：两者是同一批数据的两个粒度（一次调用一行 vs 按域名聚合），
        // 排障时来回跳，隔开只会让人每次都去菜单里找
        name: 'ScrapperFailedHost',
        path: '/scrapper/failed-host',
        component: () => import('#/views/scrapper/failed-host/index.vue'),
        meta: {
          icon: 'carbon:warning-alt',
          title: 'Scrapper失败站点排行',
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
      {
        // 归在站点层下：图标是站点级资产（一个域名一套），不是某个页面的属性。
        // 这一页不管数据本身，只量「选图规则在存量数据上判成了什么」——
        // 改造计划见仓库根 docs/ICON-DISPLAY-TODO.md
        name: 'IconVerdict',
        path: '/website/icon-verdict',
        component: () => import('#/views/website/icon-verdict/index.vue'),
        meta: {
          icon: 'carbon:image-search',
          title: '图标判定总览',
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
  {
    meta: {
      icon: 'lucide:settings',
      // 放在最后：这里是调参的地方，不是日常干活的地方
      order: 100,
      title: '系统管理',
    },
    name: 'System',
    path: '/system',
    children: [
      {
        // 全局参数集中在一页，按设置块分组。此前它们借住在「书签管理 > 书签检查配置」下，
        // 那条路由的 path 还是 /bookmark/ping-log —— 配置和巡检日志被当成了同一件事
        name: 'SystemConfig',
        path: '/system/config',
        component: () => import('#/views/system/config/index.vue'),
        meta: {
          icon: 'carbon:settings-adjust',
          title: '系统配置',
        },
      },
    ],
  },
];

export default routes;
