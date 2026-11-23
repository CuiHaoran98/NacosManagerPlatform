/**
 * recycle-bin 模块 —— 契约 §2.5(45-46)
 * 45 GET /recycle-bin   46 POST /recycle-bin/{id}/restore
 */
import { api } from '../http'
import type { PageQuery, PageResult, RecycleBinItem, RestoreRecycleRequest, RestoreRecycleResponse } from '../types'

export interface RecycleBinQuery extends PageQuery {
  appId?: string
  envId?: string
  keyword?: string
}

/** 45. 回收站列表（软删除保留 30 天；PUBLISH） */
export function listRecycleBin(params: RecycleBinQuery = {}) {
  return api.get<PageResult<RecycleBinItem>>('/recycle-bin', params as Record<string, unknown>)
}

/** 46. 恢复配置（同名冲突只允许“覆盖并重新审批”或“放弃”；
 *  错误 RB-409-001 / RB-410-001 / REL-503-001）
 */
export function restoreRecycleItem(id: string, data: RestoreRecycleRequest) {
  return api.post<RestoreRecycleResponse>(`/recycle-bin/${id}/restore`, data)
}
