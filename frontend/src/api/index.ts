/**
 * API 层出口 —— doc/ncg-api-contract.md §2 全部 48 端点已预注册。
 * 后续成员直接引用本模块，不要绕过 http.ts 自建请求（统一封装见 http.ts）。
 *
 * 端点覆盖索引：
 *  auth(1-3,47-48) catalog(4-11,36-37) change(12-18) approval(19-21)
 *  release(22-23,26-28) scheduler(24-25) batch(29-32) envsync(33-35)
 *  notification(38-40) audit(41-44) recycle-bin(45-46)
 */
export { api, http, ApiError, registerUnauthorizedHandler } from './http'
export type { ApiRequestOptions } from './http'

export * as authApi from './modules/auth'
export * as catalogApi from './modules/catalog'
export * as changeApi from './modules/change'
export * as approvalApi from './modules/approval'
export * as releaseApi from './modules/release'
export * as schedulerApi from './modules/scheduler'
export * as batchApi from './modules/batch'
export * as envSyncApi from './modules/envSync'
export * as notificationApi from './modules/notification'
export * as auditApi from './modules/audit'
export * as recycleBinApi from './modules/recycleBin'

export type * from './types'
