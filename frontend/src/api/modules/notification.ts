/**
 * notification 模块 —— 契约 §2.5(38-40)
 * 38 GET /notify-bindings
 * 39 PUT /notify-bindings/{appId}/{envId}
 * 40 DELETE /notify-bindings/{appId}/{envId}
 */
import { api } from '../http'
import type { EnvId, NotifyBinding, SaveNotifyBindingRequest } from '../types'

/** 38. 应用 × 环境通知绑定列表（ADMIN） */
export function listNotifyBindings() {
  return api.get<NotifyBinding[]>('/notify-bindings')
}

/** 39. 保存通知绑定（错误 CR-400-001 / SYS-404-001） */
export function saveNotifyBinding(appId: string, envId: EnvId, data: SaveNotifyBindingRequest) {
  return api.put<NotifyBinding>(`/notify-bindings/${appId}/${envId}`, data)
}

/** 40. 删除通知绑定（幂等；错误 SYS-404-001） */
export function deleteNotifyBinding(appId: string, envId: EnvId) {
  return api.delete<void>(`/notify-bindings/${appId}/${envId}`)
}
