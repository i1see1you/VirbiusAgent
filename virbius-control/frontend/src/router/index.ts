import { createRouter, createWebHashHistory } from 'vue-router';

const routes = [
  { path: '/', redirect: '/lists' },
  { path: '/tenants', name: 'tenants', component: () => import('@/views/TenantsView.vue') },
  { path: '/lists', name: 'lists', component: () => import('@/views/ListsView.vue') },
  { path: '/cumulatives', name: 'cumulatives', component: () => import('@/views/CumulativesView.vue') },
  { path: '/tools', name: 'tools', component: () => import('@/views/ToolsView.vue') },
  { path: '/license', name: 'license', component: () => import('@/views/LicenseView.vue') },
  { path: '/rules', name: 'rules', component: () => import('@/views/RulesView.vue') },
  { path: '/rollout', name: 'rollout', component: () => import('@/views/RolloutView.vue') },
  { path: '/audit-center', name: 'audit-center', component: () => import('@/views/AuditCenterView.vue') },
  { path: '/monitor', name: 'monitor', component: () => import('@/views/MonitorView.vue') },
  { path: '/challenge', name: 'challenge', component: () => import('@/views/ChallengeView.vue') },
  { path: '/trace', name: 'trace', component: () => import('@/views/TraceView.vue') }
];

const router = createRouter({
  history: createWebHashHistory(),
  routes
});

export default router;
