/**
 * 管理与设置 store —— P-14 / P-15 / P-16（契约 §2.5(36-40,47-48)、详细设计 §3.5.2 管理）
 * 47 GET /users、/grants（账号与授权）        48 POST /users、/grants（新建账号 / 授权）
 * 36 GET /instances（实例与环境）            37 POST /instances/{id}/test（连接测试，CON-502-001）
 * 38 GET /notify-bindings（通知绑定）        39 PUT /notify-bindings/{appId}/{envId}（保存）
 * 40 DELETE /notify-bindings/{appId}/{envId}（解绑，幂等，SYS-404-001）
 *
 * 敏感红线（MUST-NOT-05 / 详细设计 §9.5）：通知目标（Webhook）后端以密文存储，
 * 本 store 不缓存明文、视图层恒脱敏展示；保存时由用户重新输入，不回显旧值。
 *
 * 权限（§9）：本页全部动作需 ADMIN 权限点；无权限时视图不渲染动作。
 * 注：契约 48 端点未提供“禁用账号”能力（仅 GET/POST /users），故不渲染禁用动作。
 */
import { defineStore } from 'pinia'
import { authApi, catalogApi, notificationApi } from '@/api'
import type { ApiError } from '@/api/http'
import type {
  AppItem,
  CreateGrantRequest,
  CreateUserRequest,
  EnvId,
  Grant,
  InstanceInfo,
  InstanceTestResult,
  NotifyBinding,
  SaveNotifyBindingRequest,
  UserAccount,
} from '@/api/types'

export interface AdminErrorState {
  code: string
  message: string
  traceId: string
}

function toErrorState(e: unknown): AdminErrorState {
  const err = e as ApiError
  return {
    code: err?.code ?? 'SYS-500-001',
    message: err?.message ?? '请求失败，请稍后重试',
    traceId: err?.traceId ?? '',
  }
}

interface AdminState {
  users: UserAccount[]
  usersLoading: boolean
  usersError: AdminErrorState | null
  grants: Grant[]
  grantsLoading: boolean
  grantsError: AdminErrorState | null
  apps: AppItem[]
  appsLoading: boolean
  appsError: AdminErrorState | null
  instances: InstanceInfo[]
  instancesLoading: boolean
  instancesError: AdminErrorState | null
  /** instanceId → 最近一次连接测试结果 */
  testResults: Record<string, InstanceTestResult>
  testingId: string
  bindings: NotifyBinding[]
  bindingsLoading: boolean
  bindingsError: AdminErrorState | null
  savingBinding: boolean
  deletingBindingKey: string
  submittingUser: boolean
  submittingGrant: boolean
}

/** 绑定行的复合键（uk_notify_binding: app_id + env_id + channel） */
export function bindingKey(b: Pick<NotifyBinding, 'appId' | 'envId' | 'channel'>): string {
  return `${b.appId}::${b.envId}::${b.channel}`
}

export const useAdminStore = defineStore('admin', {
  state: (): AdminState => ({
    users: [],
    usersLoading: false,
    usersError: null,
    grants: [],
    grantsLoading: false,
    grantsError: null,
    apps: [],
    appsLoading: false,
    appsError: null,
    instances: [],
    instancesLoading: false,
    instancesError: null,
    testResults: {},
    testingId: '',
    bindings: [],
    bindingsLoading: false,
    bindingsError: null,
    savingBinding: false,
    deletingBindingKey: '',
    submittingUser: false,
    submittingGrant: false,
  }),

  getters: {
    /** 新建授权对话框的用户下拉（含显示名） */
    userOptions: (s): Array<{ value: string; label: string }> =>
      s.users.map((u) => ({ value: u.userId, label: `${u.displayName}（${u.username}）` })),

    appOptions: (s): Array<{ value: string; label: string }> =>
      s.apps.map((a) => ({ value: a.appId, label: `${a.appName}（${a.appId}）` })),
  },

  actions: {
    /** 47. 账号列表（P-14） */
    async fetchUsers() {
      this.usersLoading = true
      this.usersError = null
      try {
        this.users = await authApi.listUsers()
      } catch (e) {
        this.usersError = toErrorState(e)
      } finally {
        this.usersLoading = false
      }
    },

    /** 48. 新建账号（P-14；密码最小 12 位由视图校验，Argon2id 由服务端存储） */
    async createUser(data: CreateUserRequest): Promise<UserAccount> {
      this.submittingUser = true
      try {
        const user = await authApi.createUser(data)
        await this.fetchUsers()
        return user
      } finally {
        this.submittingUser = false
      }
    },

    /** 47. 授权列表（P-14） */
    async fetchGrants() {
      this.grantsLoading = true
      this.grantsError = null
      try {
        this.grants = await authApi.listGrants()
      } catch (e) {
        this.grantsError = toErrorState(e)
      } finally {
        this.grantsLoading = false
      }
    },

    /** 48. 授权（P-14；范围 = app_id × env_id × 权限点） */
    async createGrant(data: CreateGrantRequest): Promise<Grant> {
      this.submittingGrant = true
      try {
        const grant = await authApi.createGrant(data)
        await this.fetchGrants()
        return grant
      } finally {
        this.submittingGrant = false
      }
    },

    /** 应用目录（授权对话框 / 通知绑定下拉共用） */
    async fetchApps() {
      this.appsLoading = true
      try {
        this.apps = await catalogApi.listApps()
      } catch (e) {
        this.appsError = toErrorState(e)
      } finally {
        this.appsLoading = false
      }
    },

    /** 36. 实例列表（P-15） */
    async fetchInstances() {
      this.instancesLoading = true
      this.instancesError = null
      try {
        this.instances = await catalogApi.listInstances()
      } catch (e) {
        this.instancesError = toErrorState(e)
      } finally {
        this.instancesLoading = false
      }
    },

    /** 37. 连接测试（P-15；写操作三段式：预检→执行→读回，结果以内联状态回显） */
    async testInstance(id: string): Promise<InstanceTestResult> {
      this.testingId = id
      try {
        const result = await catalogApi.testInstance(id)
        this.testResults = { ...this.testResults, [id]: result }
        return result
      } finally {
        this.testingId = ''
      }
    },

    /** 38. 通知绑定列表（P-16；target 恒脱敏展示） */
    async fetchBindings() {
      this.bindingsLoading = true
      this.bindingsError = null
      try {
        this.bindings = await notificationApi.listNotifyBindings()
      } catch (e) {
        this.bindingsError = toErrorState(e)
      } finally {
        this.bindingsLoading = false
      }
    },

    /** 39. 保存通知绑定（P-16；CR-400-001 参数校验 / SYS-404-001 由全局拦截器提示） */
    async saveBinding(appId: string, envId: EnvId, data: SaveNotifyBindingRequest): Promise<NotifyBinding> {
      this.savingBinding = true
      try {
        const binding = await notificationApi.saveNotifyBinding(appId, envId, data)
        await this.fetchBindings()
        return binding
      } finally {
        this.savingBinding = false
      }
    },

    /** 40. 解绑（P-16；幂等，成功后读回列表） */
    async deleteBinding(appId: string, envId: EnvId): Promise<void> {
      this.deletingBindingKey = `${appId}::${envId}`
      try {
        await notificationApi.deleteNotifyBinding(appId, envId)
        await this.fetchBindings()
      } finally {
        this.deletingBindingKey = ''
      }
    },

    /** 页面初始化：P-14/P-15/P-16 数据并行拉取 */
    async init() {
      await Promise.all([this.fetchUsers(), this.fetchGrants(), this.fetchApps(), this.fetchInstances(), this.fetchBindings()])
    },
  },
})
