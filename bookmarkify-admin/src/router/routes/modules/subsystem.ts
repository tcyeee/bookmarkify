import type { RouteRecordRaw } from 'vue-router';

const routes: RouteRecordRaw[] = [
  {
    meta: {
      icon: 'lucide:box',
      title: '子系统',
    },
    name: 'Subsystem',
    path: '/subsystem',
    children: [
      {
        name: 'AgentTools',
        path: '/subsystem/agent-tools',
        component: () => import('#/views/subsystem/agent-tools/index.vue'),
        meta: {
          icon: 'carbon:bot',
          title: 'Agent-Tools',
        },
      },
    ],
  },
];

export default routes;
