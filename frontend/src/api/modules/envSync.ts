/**
 * envsync 模块 —— 契约 §2.4(33-35)
 * 33 POST /env-sync/compare   34 POST /env-sync/apply   35 GET /env-sync/{snapshotId}
 */
import { api } from '../http'
import type {
  EnvSyncApplyRequest,
  EnvSyncApplyResponse,
  EnvSyncCompareRequest,
  EnvSyncSnapshot,
} from '../types'

/** 33. 三环境内容对比（只读快照；错误 SYN-409-002） */
export function compareEnv(data: EnvSyncCompareRequest) {
  return api.post<EnvSyncSnapshot>('/env-sync/compare', data)
}

/** 34. 生成目标环境变更单（源环境只读；错误 SYN-409-001 / SYN-409-002） */
export function applyEnvSync(data: EnvSyncApplyRequest) {
  return api.post<EnvSyncApplyResponse>('/env-sync/apply', data)
}

/** 35. 查看同步快照（错误 SYS-404-001） */
export function getSyncSnapshot(snapshotId: string) {
  return api.get<EnvSyncSnapshot>(`/env-sync/${snapshotId}`)
}
