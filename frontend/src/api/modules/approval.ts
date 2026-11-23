/**
 * approval 模块 —— 契约 §2.2(19-21)
 * 19 POST /changes/{changeNo}/approve
 * 20 POST /changes/{changeNo}/reject   （理由 ≥ 10 字）
 * 21 POST /changes/{changeNo}/transfer
 */
import { api } from '../http'
import type { ChangeDetail, RejectRequest, TransferRequest } from '../types'

/** 19. 审批通过（错误 AUTHZ-403-002 职责分离 / AUTHZ-403-003 高风险 / APR-409-001） */
export function approveChange(changeNo: string) {
  return api.post<ChangeDetail>(`/changes/${changeNo}/approve`)
}

/** 20. 驳回（reason ≥ 10 字；错误 APR-422-001） */
export function rejectChange(changeNo: string, data: RejectRequest) {
  return api.post<ChangeDetail>(`/changes/${changeNo}/reject`, data)
}

/** 21. 转交审批（错误 AUTHZ-403-001） */
export function transferChange(changeNo: string, data: TransferRequest) {
  return api.post<ChangeDetail>(`/changes/${changeNo}/transfer`, data)
}
