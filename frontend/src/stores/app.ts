/**
 * 应用上下文 store —— 环境（全局上下文）、实例可用性（只读降级）、导航折叠
 * 原则（详细设计 §3.5.1）：环境是全局上下文；切换环境不改变权限；
 * 实例不可用时页面进入只读（SYS-423-001），恢复后不自动补发。
 */
import { defineStore } from 'pinia'
import { catalogApi } from '@/api'
import type { EnvItem, EnvName } from '@/api/types'

export interface EnvOption {
  value: EnvName
  label: string
}

export const ENV_OPTIONS: EnvOption[] = [
  { value: 'dev', label: '开发 dev' },
  { value: 'staging', label: '预发 staging' },
  { value: 'prod', label: '生产 prod' },
]

interface AppState {
  env: EnvName
  /** GET /envs 环境清单（envId↔envCode↔displayName）；接口失败时为空表，显示回退原值 */
  envs: EnvItem[]
  /** 实例不可用时全站只读（写按钮全部禁用） */
  instanceAvailable: boolean
  sidebarCollapsed: boolean
}

const ENV_STORAGE_KEY = 'ncg.env'

export const useAppStore = defineStore('app', {
  state: (): AppState => ({
    env: (localStorage.getItem(ENV_STORAGE_KEY) as EnvName | null) ?? 'dev',
    envs: [],
    instanceAvailable: true,
    sidebarCollapsed: false,
  }),
  getters: {
    readOnly: (s) => !s.instanceAvailable,
    envOptions: () => ENV_OPTIONS,
    envLabel: (s) => ENV_OPTIONS.find((o) => o.value === s.env)?.label ?? s.env,
    /**
     * 当前环境的 envId（ULID）。业务过滤/查询一律用它，而不是 envCode
     *（后端按 envId 全等比较，传 'dev' 会恒不匹配）。找不到映射时回退 envCode。
     */
    currentEnvId(s): string {
      return s.envs.find((e) => e.envCode === s.env)?.envId ?? s.env
    },
    /** envId（ULID）或 envCode → 显示名；未加载/未命中回退原值 */
    envLabelOf: (s) => (idOrCode: string): string => {
      const hit = s.envs.find((e) => e.envId === idOrCode || e.envCode === idOrCode)
      return hit?.displayName ?? idOrCode
    },
    /** 环境下拉选项（value=envId ULID，label=显示名）；envs 未加载时回退 ENV_OPTIONS 语义值 */
    envIdOptions(s): { value: string; label: string }[] {
      return s.envs.length
        ? s.envs.map((e) => ({ value: e.envId, label: e.displayName }))
        : ENV_OPTIONS.map((o) => ({ value: o.value, label: o.label }))
    },
  },
  actions: {
    /** 拉取环境清单；幂等（已加载不重复请求），失败静默留空表由 getters 回退 */
    async loadEnvs() {
      if (this.envs.length) return
      try {
        this.envs = await catalogApi.listEnvs()
      } catch {
        this.envs = []
      }
    },
    setEnv(env: EnvName) {
      this.env = env
      localStorage.setItem(ENV_STORAGE_KEY, env)
    },
    setInstanceAvailable(available: boolean) {
      this.instanceAvailable = available
    },
    toggleSidebar() {
      this.sidebarCollapsed = !this.sidebarCollapsed
    },
  },
})
