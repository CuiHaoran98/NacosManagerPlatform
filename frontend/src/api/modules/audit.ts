/**
 * audit 模块 —— 契约 §2.5(41-44)
 * 41 GET /audit-events                42 POST /audit-events/export
 * 43 GET /direct-changes              44 POST /direct-changes/{id}/close
 */
import { api } from '../http'
import type {
  AuditEvent,
  AuditEventQuery,
  AuditExportRequest,
  DirectChangeEvent,
  PageResult,
} from '../types'

/** 41. 审计事件查询（VIEW_AUDIT；append-only 哈希链） */
export function listAuditEvents(params: AuditEventQuery = {}) {
  return api.get<PageResult<AuditEvent>>('/audit-events', params as Record<string, unknown>)
}

/** 42. 导出审计事件（EXPORT_AUDIT；错误 AUD-500-001；导出内容不含敏感明文） */
export function exportAuditEvents(data: AuditExportRequest) {
  return api.postBlob('/audit-events/export', data)
}

/** 43. 直连变更告警列表（5 分钟内生成未授权事件） */
export function listDirectChanges(params: { status?: string } = {}) {
  return api.get<DirectChangeEvent[]>('/direct-changes', params as Record<string, unknown>)
}

/** 44. 处置直连告警（幂等；错误 SYS-404-001） */
export function closeDirectChange(id: string) {
  return api.post<DirectChangeEvent>(`/direct-changes/${id}/close`)
}
