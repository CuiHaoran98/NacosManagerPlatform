/**
 * catalog 模块 —— 契约 §2.1(4-11)、§2.5(36-37)
 * 4  GET /apps                       5  GET /apps/{appId}/configs
 * 6  GET /configs/{mappingId}        7  POST /configs/{mappingId}/sensitive-view
 * 8  POST /mappings                  9  PUT /mappings/{id}
 * 10 DELETE /mappings/{id}           11 POST /mappings/import
 * 36 GET /instances                  37 POST /instances/{id}/test
 */
import { api } from '../http'
import type { ApiRequestOptions } from '../http'
import type {
  AppItem,
  ConfigContent,
  ConfigMapping,
  EnvItem,
  InstanceInfo,
  InstanceTestResult,
  MappingCreateRequest,
  MappingImportRequest,
  MappingUpdateRequest,
  SensitiveViewRequest,
  SensitiveViewResponse,
} from '../types'

/** 4. 应用列表（P-03 左侧应用树） */
export function listApps() {
  return api.get<AppItem[]>('/apps')
}

/** GET /envs —— 环境清单（envId↔envCode↔displayName 映射源；环境列显示与过滤值都取 envId） */
export function listEnvs() {
  return api.get<EnvItem[]>('/envs')
}

/** 5. 应用下 DataId 配置文件列表（错误 SYS-404-001） */
export function listAppConfigs(appId: string) {
  return api.get<ConfigMapping[]>(`/apps/${appId}/configs`)
}

/** 6. 读取 DataId 内容与配置项 items[]（错误 SYS-404-001 / CON-502-001） */
export function getConfig(mappingId: string, opts?: ApiRequestOptions) {
  return api.get<ConfigContent>(`/configs/${mappingId}`, undefined, opts)
}

/**
 * 7. 按 itemKey 查看单项明文（VIEW_SENSITIVE；原因 ≥ 10 字并写审计；
 *    错误 AUTHZ-403-001 / CR-400-001 / SYS-404-001）
 */
export function viewSensitive(mappingId: string, data: SensitiveViewRequest) {
  return api.post<SensitiveViewResponse>(`/configs/${mappingId}/sensitive-view`, data)
}

/** 8. 纳管 TongNCS 配置为映射（ADMIN；错误 CAT-409-001） */
export function createMapping(data: MappingCreateRequest) {
  return api.post<ConfigMapping>('/mappings', data)
}

/** 9. 更新映射元数据（不含 format——纳管后固化；错误 CAT-409-001 / SYS-409-001） */
export function updateMapping(id: string, data: MappingUpdateRequest) {
  return api.put<ConfigMapping>(`/mappings/${id}`, data)
}

/** 10. 解除纳管（ADMIN；错误 SYS-404-001） */
export function deleteMapping(id: string) {
  return api.delete<void>(`/mappings/${id}`)
}

/** 11. 批量导入映射（ADMIN；错误 CAT-409-002） */
export function importMappings(data: MappingImportRequest) {
  return api.post<{ imported: number }>('/mappings/import', data)
}

/** 36. TongNCS 实例列表与健康（ADMIN；幂等） */
export function listInstances() {
  return api.get<InstanceInfo[]>('/instances')
}

/** 37. 连接测试（ADMIN；错误 CON-502-001） */
export function testInstance(id: string) {
  return api.post<InstanceTestResult>(`/instances/${id}/test`)
}
