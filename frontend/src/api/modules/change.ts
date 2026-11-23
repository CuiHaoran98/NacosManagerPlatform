/**
 * change + impact 模块 —— 契约 §2.2(12-18)
 * 12 POST /changes                    13 PUT /changes/{changeNo}
 * 14 POST /changes/{changeNo}/submit  15 POST /changes/{changeNo}/cancel
 * 16 GET /changes                     17 GET /changes/{changeNo}
 * 18 GET /changes/{changeNo}/impact
 */
import { api } from '../http'
import type {
  ChangeCreatedResponse,
  ChangeDetail,
  ChangeListItem,
  CreateChangeRequest,
  ImpactReport,
  PageQuery,
  UpdateChangeRequest,
} from '../types'

export interface ChangeListQuery extends PageQuery {
  appId?: string
  envId?: string
  status?: string
  keyword?: string
  createdBy?: string
}

/** 12. 创建变更草稿（items[] 或 content 二选一；错误 CR-400-001 / CR-409-001 / CR-415-001 / CR-422-001） */
export function createChange(data: CreateChangeRequest) {
  return api.post<ChangeCreatedResponse>('/changes', data)
}

/** 13. 修改草稿内容（错误 CR-400-001 / CR-415-001 / CR-422-001 / SYS-409-001） */
export function updateChange(changeNo: string, data: UpdateChangeRequest) {
  return api.put<ChangeCreatedResponse>(`/changes/${changeNo}`, data)
}

/** 14. 提交审批并冻结基线（错误 CR-412-001 / CR-413-001） */
export function submitChange(changeNo: string) {
  return api.post<ChangeDetail>(`/changes/${changeNo}/submit`)
}

/** 15. 取消变更单（SUBMIT / PUBLISH；幂等；错误 SYS-409-001） */
export function cancelChange(changeNo: string) {
  return api.post<ChangeDetail>(`/changes/${changeNo}/cancel`)
}

/** 16. 变更单列表与筛选 */
export function listChanges(params: ChangeListQuery = {}) {
  return api.get<ChangeListItem[]>('/changes', params as Record<string, unknown>)
}

/** 17. 变更详情（错误 SYS-404-001） */
export function getChange(changeNo: string) {
  return api.get<ChangeDetail>(`/changes/${changeNo}`)
}

/** 18. 影响面与风险（错误 IMP-409-001；影响面未知时 blocked=true） */
export function getImpact(changeNo: string) {
  return api.get<ImpactReport>(`/changes/${changeNo}/impact`)
}
