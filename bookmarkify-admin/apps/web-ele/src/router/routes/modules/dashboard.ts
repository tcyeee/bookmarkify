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
      {
        name: 'Workspace',
        path: '/workspace',
        component: () => import('#/views/dashboard/workspace/index.vue'),
        meta: {
          icon: 'carbon:workspace',
          title: '工作台',
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
          title: '书签清洗',
        },
      },
      {
        name: 'BookmarkLiveness',
        path: '/bookmark/liveness',
        component: () => import('#/views/bookmark/liveness/index.vue'),
        meta: {
          title: '书签活性检测',
        },
      },
    ],
  },
];

export default routes;
