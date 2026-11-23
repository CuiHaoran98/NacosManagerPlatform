/**
 * API 统一封装（doc/ncg-api-contract.md §1）
 * - Base path：/api/v1；认证走 NCG_SESSION Cookie（withCredentials）
 * - 写请求自动携带 X-XSRF-TOKEN（读取 XSRF-TOKEN Cookie）
 * - 每个请求带 X-Trace-Id（可由调用方指定，否则生成），响应回写值保留在错误体中
 * - 统一错误体：code + message + traceId + details + timestamp；未识别按 SYS-500-001
 * - 全局错误提示：ElMessage（可按请求 silent 关闭）；401 统一处理（清会话 + 跳登录）
 */
import axios, { AxiosError, type AxiosHeaders, type AxiosRequestConfig } from 'axios'
import { ElMessage } from 'element-plus'
import 'element-plus/es/components/message/style/css'
import type { ApiErrorBody } from './types'

export type { ApiErrorBody }

/** 请求级选项（不属于 Axios 配置，由封装层消费） */
export interface ApiRequestOptions {
  /** 静默：不弹全局错误提示（页面自行处理时使用） */
  silent?: boolean
  /** 指定 TraceId（默认生成） */
  traceId?: string
}

export class ApiError extends Error {
  code: string
  traceId: string
  details?: Record<string, unknown>
  timestamp: string
  httpStatus: number

  constructor(body: ApiErrorBody, httpStatus: number) {
    super(body.message)
    this.name = 'ApiError'
    this.code = body.code
    this.traceId = body.traceId
    this.details = body.details
    this.timestamp = body.timestamp
    this.httpStatus = httpStatus
  }
}

export const http = axios.create({
  baseURL: '/api/v1',
  timeout: 30000,
  withCredentials: true,
  headers: {
    'Content-Type': 'application/json; charset=UTF-8',
    Accept: 'application/json',
  },
})

function readCookie(name: string): string | null {
  const match = document.cookie.match(new RegExp('(?:^|; )' + name.replace(/([.$?*|{}()[\]\\/+^])/g, '\\$1') + '=([^;]*)'))
  return match ? decodeURIComponent(match[1]) : null
}

function randomTraceId(): string {
  return (crypto.randomUUID?.() ?? String(Date.now()) + Math.random().toString(16).slice(2))
}

/* ---------- 401 统一处理 ---------- */
let redirectingToLogin = false

type UnauthorizedHandler = () => void
let unauthorizedHandler: UnauthorizedHandler | null = null

/** 由 auth store / router 注册：清空会话并跳转登录页 */
export function registerUnauthorizedHandler(handler: UnauthorizedHandler | null): void {
  unauthorizedHandler = handler
}

function handleUnauthorized(): void {
  if (redirectingToLogin) return
  redirectingToLogin = true
  // 已在登录页（如 /auth/me 预检 401）时不重复打扰
  if (window.location.pathname !== '/login') {
    ElMessage.warning('登录状态已失效，请重新登录')
  }
  try {
    unauthorizedHandler?.()
  } finally {
    setTimeout(() => {
      redirectingToLogin = false
    }, 1000)
  }
}

/* ---------- 拦截器 ---------- */
http.interceptors.request.use((config) => {
  const opts = (config as ConfigWithOpts).apiOptions ?? {}
  config.headers.set('X-Trace-Id', opts.traceId ?? randomTraceId())
  // URLSearchParams 是表单体：必须覆盖实例默认的 application/json 头，
  // 否则 Spring Security formLogin 读不到 username/password，一律按空凭据拒绝（AUTH-401-001）。
  if (config.data instanceof URLSearchParams) {
    config.headers.set('Content-Type', 'application/x-www-form-urlencoded; charset=UTF-8')
  }
  if (config.method && config.method.toLowerCase() !== 'get') {
    const xsrf = readCookie('XSRF-TOKEN')
    if (xsrf) config.headers.set('X-XSRF-TOKEN', xsrf)
  }
  return config
})

type ConfigWithOpts = AxiosRequestConfig & { apiOptions?: ApiRequestOptions }

interface ErrorPayload {
  code: string
  message: string
  traceId: string
  details?: Record<string, unknown>
  timestamp: string
  silent?: boolean
}

function toApiError(error: AxiosError<unknown>): ApiError {
  const fallback: ApiErrorBody = {
    code: 'SYS-500-001',
    message: error.message || '请求失败，请稍后重试',
    traceId: String((error.config?.headers as AxiosHeaders | undefined)?.get?.('X-Trace-Id') ?? ''),
    timestamp: new Date().toISOString(),
  }
  const data = error.response?.data as Partial<ApiErrorBody> | undefined
  const body: ApiErrorBody = {
    code: typeof data?.code === 'string' ? data.code : fallback.code,
    message: typeof data?.message === 'string' ? data.message : fallback.message,
    traceId: typeof data?.traceId === 'string' ? data.traceId : fallback.traceId,
    details: data?.details,
    timestamp: typeof data?.timestamp === 'string' ? data.timestamp : fallback.timestamp,
  }
  return new ApiError(body, error.response?.status ?? 0)
}

http.interceptors.response.use(
  (response) => response,
  (error: AxiosError<unknown>) => {
    const opts = (error.config as ConfigWithOpts | undefined)?.apiOptions ?? {}
    const apiError = toApiError(error)

    // 登录接口自身的鉴权失败（AUTH-401-001/002）不算会话失效，交由登录页就地提示
    const isLoginCall = (error.config?.url ?? '').includes('/auth/login')
    const unauthorized =
      !isLoginCall &&
      (apiError.httpStatus === 401 || apiError.code === 'AUTH-401-001' || apiError.code === 'AUTH-401-002')

    if (unauthorized) {
      handleUnauthorized()
    } else if (!opts.silent) {
      // 全局错误提示：文案 + 错误码 + traceId（可定位）
      ElMessage.error({
        message: `${apiError.message}`,
        duration: 6000,
        grouping: true,
      })
      if (apiError.code) {
        ElMessage.error({
          message: `错误码 ${apiError.code}${apiError.traceId ? ` · TraceId ${apiError.traceId}` : ''}`,
          duration: 6000,
          grouping: true,
        })
      }
    }

    return Promise.reject(apiError)
  },
)

/* ---------- 便捷方法 ---------- */
export interface RequestConfig extends AxiosRequestConfig {
  apiOptions?: ApiRequestOptions
}

async function request<T>(config: RequestConfig): Promise<T> {
  const res = await http.request<unknown, { data: T }>(config as AxiosRequestConfig)
  return res.data as T
}

export const api = {
  get<T>(url: string, params?: Record<string, unknown>, opts?: ApiRequestOptions): Promise<T> {
    return request<T>({ method: 'get', url, params, apiOptions: opts })
  },
  post<T>(url: string, data?: unknown, opts?: ApiRequestOptions): Promise<T> {
    return request<T>({ method: 'post', url, data, apiOptions: opts })
  },
  put<T>(url: string, data?: unknown, opts?: ApiRequestOptions): Promise<T> {
    return request<T>({ method: 'put', url, data, apiOptions: opts })
  },
  delete<T>(url: string, params?: Record<string, unknown>, opts?: ApiRequestOptions): Promise<T> {
    return request<T>({ method: 'delete', url, params, apiOptions: opts })
  },
  /** 导出类接口：返回 Blob（错误体仍是 JSON，交由拦截器统一处理） */
  postBlob(url: string, data?: unknown, opts?: ApiRequestOptions): Promise<Blob> {
    return request<Blob>({ method: 'post', url, data, responseType: 'blob', apiOptions: opts })
  },
}
