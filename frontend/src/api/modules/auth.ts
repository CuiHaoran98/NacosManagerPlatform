/**
 * auth 模块 —— 契约 §2.1(1-3)、§2.5(47-48)
 * 1  POST /auth/login      2  POST /auth/logout     3  GET /auth/me
 * 47 GET /users、/grants   48 POST /users、/grants
 */
import { api } from '../http'
import type { ApiRequestOptions } from '../http'
import type {
  CreateGrantRequest,
  CreateUserRequest,
  CurrentUser,
  Grant,
  LoginRequest,
  UserAccount,
} from '../types'

/** 1. 登录并创建会话（匿名；错误 AUTH-401-001 / AUTH-401-002）。
 *  后端为 Spring Security formLogin（application/x-www-form-urlencoded），
 *  这里显式按表单编码上送 username/password（联调对齐项，见 INTEGRATION-REPORT）。 */
export function login(data: LoginRequest) {
  const form = new URLSearchParams()
  form.set('username', data.username)
  form.set('password', data.password)
  return api.post<CurrentUser>('/auth/login', form)
}

/** 2. 注销当前会话（幂等） */
export function logout() {
  return api.post<void>('/auth/logout')
}

/** 3. 当前用户、角色与权限点（应用启动、路由守卫；幂等） */
export function me(opts?: ApiRequestOptions) {
  return api.get<CurrentUser>('/auth/me', undefined, { silent: true, ...opts })
}

/** 47a. 账号查询 */
export function listUsers() {
  return api.get<UserAccount[]>('/users')
}

/** 48a. 新建账号 */
export function createUser(data: CreateUserRequest) {
  return api.post<UserAccount>('/users', data)
}

/** 47b. 授权查询 */
export function listGrants() {
  return api.get<Grant[]>('/grants')
}

/** 48b. 授予权限 */
export function createGrant(data: CreateGrantRequest) {
  return api.post<Grant>('/grants', data)
}
