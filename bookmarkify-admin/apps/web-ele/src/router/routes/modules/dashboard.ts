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
          icon: 'carbon:workspace',
          title: '书签清洗',
        },
      },
      {
        name: 'BookmarkLiveness',
        path: '/bookmark/liveness',
        component: () => import('#/views/bookmark/liveness/index.vue'),
        meta: {
          icon: 'carbon:workspace',
          title: '书签活性检测',
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
