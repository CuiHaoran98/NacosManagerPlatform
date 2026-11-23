/**
 * 路由 —— 12 条业务路由 + 登录页（详细设计 §3.5.2）
 * 入口守卫：未登录访问业务页 → /auth/me 校验 → 未通过跳登录并保留 redirect
 * 401 统一处理由 api/http.ts 触发（registerUnauthorizedHandler）
 */
import { createRouter, createWebHistory, type RouteRecordRaw } from 'vue-router'
import { useAuthStore } from '@/stores/auth'
import { registerUnauthorizedHandler } from '@/api/http'

const routes: RouteRecordRaw[] = [
  {
    path: '/login',
    name: 'login',
    component: () => import('@/views/login/LoginView.vue'),
    meta: { public: true, title: '登录' },
  },
  {
    path: '/',
    component: () => import('@/layouts/AppLayout.vue'),
    redirect: '/workbench',
    children: [
      {
        path: 'workbench',
        name: 'workbench',
        component: () => import('@/views/workbench/WorkbenchView.vue'),
        meta: { title: '工作台' },
      },
      {
        path: 'catalog',
        name: 'catalog',
        component: () => import('@/views/catalog/CatalogView.vue'),
        meta: { title: '配置目录' },
      },
      {
        path: 'changes',
        name: 'changes',
        component: () => import('@/views/changes/ChangesView.vue'),
        meta: { title: '变更单' },
      },
      {
        path: 'changes/:changeNo',
        name: 'change-detail',
        component: () => import('@/views/changes/ChangeDetailView.vue'),
        meta: { title: '变更详情', hidden: true },
      },
      {
        path: 'approvals',
        name: 'approvals',
        component: () => import('@/views/approvals/ApprovalsView.vue'),
        meta: { title: '审批中心' },
      },
      {
        path: 'schedules',
        name: 'schedules',
        component: () => import('@/views/schedules/SchedulesView.vue'),
        meta: { title: '定时任务' },
      },
      {
        path: 'batches',
        name: 'batches',
        component: () => import('@/views/batches/BatchesView.vue'),
        meta: { title: '批量变更' },
      },
      {
        path: 'releases',
        name: 'releases',
        component: () => import('@/views/releases/ReleasesView.vue'),
        meta: { title: '发布控制台' },
      },
      {
        path: 'env-sync',
        name: 'env-sync',
        component: () => import('@/views/env-sync/EnvSyncView.vue'),
        meta: { title: '跨环境同步' },
      },
      {
        path: 'recycle-bin',
        name: 'recycle-bin',
        component: () => import('@/views/recycle-bin/RecycleBinView.vue'),
        meta: { title: '回收站' },
      },
      {
        path: 'audit',
        name: 'audit',
        component: () => import('@/views/audit/AuditView.vue'),
        meta: { title: '审计与检测' },
      },
      {
        path: 'admin',
        name: 'admin',
        component: () => import('@/views/admin/AdminView.vue'),
        meta: { title: '管理与设置' },
      },
    ],
  },
  { path: '/:pathMatch(.*)*', redirect: '/workbench' },
]

const router = createRouter({
  history: createWebHistory(),
  routes,
  scrollBehavior: () => ({ top: 0 }),
})

/** 401 统一处理：清会话并跳登录（由 http.ts 拦截器触发） */
registerUnauthorizedHandler(() => {
  const auth = useAuthStore()
  auth.reset()
  const current = router.currentRoute.value
  if (current.name !== 'login') {
    router.push({ name: 'login', query: { redirect: current.fullPath } })
  }
})

router.beforeEach(async (to) => {
  const auth = useAuthStore()

  if (to.meta.public) {
    // 已登录访问登录页 → 直接进工作台
    if (to.name === 'login' && (await auth.fetchMe())) {
      return { name: 'workbench' }
    }
    return true
  }

  // 入口守卫：会话恢复（/auth/me），失败跳登录并保留 redirect
  const ok = await auth.fetchMe()
  if (!ok) {
    return { name: 'login', query: { redirect: to.fullPath } }
  }
  return true
})

router.afterEach((to) => {
  const title = to.meta.title as string | undefined
  document.title = title ? `${title} · NCG 配置治理平台` : 'NCG 配置治理平台'
})

export default router
