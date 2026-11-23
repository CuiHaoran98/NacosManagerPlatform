/**
 * release 模块 —— 契约 §2.3(22-23、26-28)
 * 22 POST /changes/{changeNo}/release   23 POST /changes/{changeNo}/rollback
 * 26 POST /releases/{releaseId}/reconcile
 * 27 GET /releases                      28 GET /releases/{releaseId}
 */
import { api } from '../http'
import type { PageQuery, ReleaseDetail, ReleaseRecord } from '../types'

export interface ReleaseListQuery extends PageQuery {
  changeNo?: string
  result?: string
  envId?: string
}

/** 22. 执行发布（唯一写入口，写 TongNCS 并读回；幂等键=变更单号+目标版本）
 *  错误 REL-403-001 / REL-409-001 / REL-412-001 / REL-422-001 / REL-502-001 / REL-503-001
 */
export function releaseChange(changeNo: string) {
  return api.post<ReleaseDetail>(`/changes/${changeNo}/release`)
}

/** 23. 回滚到历史版本（幂等；错误 RB-410-001 / REL-403-001 / REL-502-001 / REL-503-001） */
export function rollbackChange(changeNo: string, data: { targetVersion: string }) {
  return api.post<ReleaseDetail>(`/changes/${changeNo}/rollback`, data)
}

/** 26. 不确定结果人工对账（indeterminate 只走人工；错误 REL-502-001）。
 *  conclusion 三选一 EFFECTIVE/NOT_EFFECTIVE/OVERRIDDEN（差异5：契约 #26 body 补齐后可上送）。 */
export function reconcileRelease(releaseId: string, conclusion?: 'EFFECTIVE' | 'NOT_EFFECTIVE' | 'OVERRIDDEN') {
  return api.post<ReleaseDetail>(`/releases/${releaseId}/reconcile`, conclusion ? { conclusion } : undefined)
}

/** 27. 发布记录列表 */
export function listReleases(params: ReleaseListQuery = {}) {
  return api.get<ReleaseRecord[]>('/releases', params as Record<string, unknown>)
}

/** 28. 发布记录详情（错误 SYS-404-001） */
export function getRelease(releaseId: string) {
  return api.get<ReleaseDetail>(`/releases/${releaseId}`)
}
