/**
 * batch 模块 —— 契约 §2.4(29-32)、§3.5
 * 29 POST /batches                            30 PUT /batches/{batchId}/items/{itemId}
 * 31 POST /batches/{batchId}/submit            32 POST /batches/{batchId}/retry
 */
import { api } from '../http'
import type {
  BatchCreateRequest,
  BatchCreateResponse,
  BatchItemUpdateRequest,
  BatchItemUpdateResponse,
} from '../types'

/** 29. 创建批量父单与 DataId 子草稿（错误 CR-400-001 / CR-409-001 / CR-415-001 / CR-422-001 / BAT-413-001） */
export function createBatch(data: BatchCreateRequest) {
  return api.post<BatchCreateResponse>('/batches', data)
}

/** 30. 草稿阶段编辑子项配置值（已提交返回 SYS-409-001；敏感项需重新输入） */
export function updateBatchItem(batchId: string, itemId: string, data: BatchItemUpdateRequest) {
  return api.put<BatchItemUpdateResponse>(`/batches/${batchId}/items/${itemId}`, data)
}

/** 31. 提交批量父单（SUBMIT；错误 BAT-413-001） */
export function submitBatch(batchId: string) {
  return api.post<{ batchId: string; status: string }>(`/batches/${batchId}/submit`)
}

/** 32. 只重试失败子项（成功项不重发；幂等；错误 SYS-409-001） */
export function retryBatch(batchId: string) {
  return api.post<{ batchId: string; retried: number }>(`/batches/${batchId}/retry`)
}
