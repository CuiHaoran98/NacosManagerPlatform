/**
 * 导航配置 —— 按详细设计 §3.5.2 信息架构分组：治理 / 发布 / 监督 / 管理
 * 侧边导航 11 页（变更详情 /changes/:changeNo 不进导航）
 */
export interface NavItem {
  /** 路由 name */
  name: string
  path: string
  title: string
  /** 所需权限点（用于权限驱动渲染；缺省=已登录即可看） */
  perm?: string
}

export interface NavGroup {
  group: string
  items: NavItem[]
}

export const NAV_GROUPS: NavGroup[] = [
  {
    group: '治理',
    items: [
      { name: 'workbench', path: '/workbench', title: '工作台' },
      { name: 'catalog', path: '/catalog', title: '配置目录' },
      { name: 'changes', path: '/changes', title: '变更单' },
      { name: 'approvals', path: '/approvals', title: '审批中心', perm: 'APPROVE' },
      { name: 'schedules', path: '/schedules', title: '定时任务', perm: 'PUBLISH' },
      { name: 'batches', path: '/batches', title: '批量变更' },
    ],
  },
  {
    group: '发布',
    items: [
      { name: 'releases', path: '/releases', title: '发布控制台' },
      { name: 'env-sync', path: '/env-sync', title: '跨环境同步' },
      { name: 'recycle-bin', path: '/recycle-bin', title: '回收站', perm: 'PUBLISH' },
    ],
  },
  {
    group: '监督',
    items: [{ name: 'audit', path: '/audit', title: '审计与检测', perm: 'VIEW_AUDIT' }],
  },
  {
    group: '管理',
    items: [{ name: 'admin', path: '/admin', title: '管理与设置', perm: 'ADMIN' }],
  },
]
