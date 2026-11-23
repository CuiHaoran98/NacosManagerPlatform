/**
 * 鉴权 store —— 登录 / 当前用户 / 权限点
 * 401 统一处理通过 registerUnauthorizedHandler 接入 http.ts
 */
import { defineStore } from 'pinia'
import { authApi } from '@/api'
import type { CurrentUser, LoginRequest } from '@/api/types'

interface AuthState {
  user: CurrentUser | null
  /** /auth/me 是否已尝试加载（区分“未登录”与“尚未检查”） */
  loaded: boolean
  loading: boolean
}

export const useAuthStore = defineStore('auth', {
  state: (): AuthState => ({
    user: null,
    loaded: false,
    loading: false,
  }),
  getters: {
    isAuthenticated: (s) => s.user !== null,
    roles: (s) => s.user?.roles ?? [],
    permissions: (s) => s.user?.permissions ?? [],
    /** 权限驱动渲染：无权限的动作不渲染（前端隐藏 ≠ 授权，后端仍 fail-closed） */
    can: (s) => (perm: string) => (s.user?.permissions ?? []).includes(perm),
  },
  actions: {
    async login(payload: LoginRequest) {
      this.loading = true
      try {
        this.user = await authApi.login(payload)
        this.loaded = true
      } finally {
        this.loading = false
      }
    },
    /** 应用启动 / 路由守卫调用；返回是否已登录 */
    async fetchMe(force = false): Promise<boolean> {
      if (this.loaded && !force) return this.user !== null
      this.loading = true
      try {
        this.user = await authApi.me()
        this.loaded = true
        return true
      } catch {
        this.user = null
        this.loaded = true
        return false
      } finally {
        this.loading = false
      }
    },
    async logout() {
      try {
        await authApi.logout()
      } finally {
        this.reset()
      }
    },
    reset() {
      this.user = null
      this.loaded = true
    },
  },
})
