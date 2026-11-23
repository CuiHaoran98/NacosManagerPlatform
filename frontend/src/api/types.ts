/**
 * API DTO 类型 —— 对齐 doc/ncg-api-contract.md（v1.2）
 * 字段口径以契约 §3 示例与详细设计 §3.5.7 为准；
 * 契约未给出的列表分页字段标注 [TBD]，联调时以实际后端为准。
 */

/* ===================== 通用 ===================== */

/** 统一错误体（§1.2） */
export interface ApiErrorBody {
  code: string
  message: string
  traceId: string
  details?: Record<string, unknown>
  timestamp: string
}

/** 列表分页（§1.1：page 从 0 开始；size 默认 20、最大 100）[TBD: 列表响应包裹字段] */
export interface PageQuery {
  page?: number
  size?: number
}

export interface PageResult<T> {
  items: T[]
  total: number
  page: number
  size: number
}

export type EnvId = string

/** 环境上下文（顶栏切换 dev / staging / prod） */
export type EnvName = 'dev' | 'staging' | 'prod'

/** GET /envs —— 环境清单（envId=ULID 主键；envCode=dev/staging/prod；displayName=显示名） */
export interface EnvItem {
  envId: EnvId
  envCode: string
  displayName: string
  sortOrder?: number
  status?: string
}

/* ===================== 1-3 auth ===================== */

export interface LoginRequest {
  username: string
  password: string
}

/** GET /auth/me */
export interface CurrentUser {
  userId: string
  username: string
  displayName: string
  roles: string[]
  /** 权限点：VIEW / EDIT / SUBMIT / APPROVE / PUBLISH / ROLLBACK / VIEW_SENSITIVE / VIEW_AUDIT / EXPORT_AUDIT / ADMIN */
  permissions: string[]
}

/* ===================== 47-48 账号与授权 ===================== */

/** GET /users */
export interface UserAccount {
  userId: string
  username: string
  displayName: string
  roles: string[]
  enabled: boolean
  createdAt: string
}

/** POST /users */
export interface CreateUserRequest {
  username: string
  displayName: string
  password: string
  roles: string[]
}

/** GET /grants */
export interface Grant {
  grantId: string
  userId: string
  username: string
  appId: string
  envId: EnvId
  permission: string
  grantedBy: string
  grantedAt: string
}

/** POST /grants */
export interface CreateGrantRequest {
  userId: string
  appId: string
  envId: EnvId
  permission: string
}

/* ===================== 4-11 配置目录 ===================== */

/** GET /apps */
export interface AppItem {
  appId: string
  appName: string
  /** 应用负责人（高风险审批依据 R-AUTH-07） */
  owner: string
  envIds: EnvId[]
}

/** 配置格式（§1.3）：纳管时按后缀固化，不可变更 */
export type ConfigFormat = 'PROPERTIES' | 'YAML' | 'JSON' | 'TEXT'

/** GET /apps/{appId}/configs */
export interface ConfigMapping {
  mappingId: string
  appId: string
  envId: EnvId
  dataId: string
  format: ConfigFormat
  md5: string
  collectedAt: string
}

/** 配置项（§3.2） */
export interface ConfigItem {
  key: string
  /** 敏感项默认脱敏为 •••••• */
  value: string
  valueType: 'STRING' | 'NUMBER' | 'BOOLEAN' | 'JSON'
  sensitive: boolean
}

/** 解析失败定位（CR-422-001，阻断提交但不阻断浏览） */
export interface ParseError {
  line: number
  column?: number
  message: string
}

/** GET /configs/{mappingId}（TEXT 只返回 content，结构化返回 items[]） */
export interface ConfigContent {
  mappingId: string
  dataId: string
  format: ConfigFormat
  md5: string
  collectedAt: string
  items?: ConfigItem[]
  content?: string
  parseError?: ParseError
}

/** POST /configs/{mappingId}/sensitive-view */
export interface SensitiveViewRequest {
  itemKey: string
  /** 查看原因，至少 10 字；写审计 */
  reason: string
}

export interface SensitiveViewResponse {
  itemKey: string
  value: string
  /** 30 秒后前端重新脱敏 */
  expiresAt: string
}

/** POST /mappings（ADMIN 纳管） */
export interface MappingCreateRequest {
  appId: string
  envId: EnvId
  dataId: string
}

/** PUT /mappings/{id}（不含 format——format 纳管后固化） */
export interface MappingUpdateRequest {
  appName?: string
  owner?: string
  remark?: string
}

/** POST /mappings/import */
export interface MappingImportRequest {
  mappings: MappingCreateRequest[]
}

/* ===================== 12-18 变更与影响面 ===================== */

export type ChangeType = 'CREATE' | 'UPDATE' | 'DELETE'

/** 状态机（详细设计 §4.2）；未知状态显示原值 */
export type ChangeStatus = 'DRAFT' | 'PENDING_APPROVAL' | 'APPROVED' | 'PENDING_RELEASE' | 'SCHEDULED' | 'RELEASED' | 'REJECTED' | 'CANCELED' | (string & {})

export type RiskLevel = 'LOW' | 'MEDIUM' | 'HIGH'

/** 变更配置项输入（items[] 与 content 互斥） */
export interface ChangeItemInput {
  key: string
  value: string
  valueType?: 'STRING' | 'NUMBER' | 'BOOLEAN' | 'JSON'
}

/** POST /changes（§3.4：UPDATE 用 items[]；CREATE / TEXT / 整文件替换用 content） */
export interface CreateChangeRequest {
  appId: string
  envId: EnvId
  mappingId: string
  changeType: ChangeType
  title: string
  /** 变更原因，至少 10 字 */
  reason: string
  items?: ChangeItemInput[]
  content?: string
  baselineMd5?: string
  plannedAt: string
}

/** POST /changes 响应（§3.1 格式回显） */
export interface ChangeCreatedResponse {
  changeNo: string
  status: ChangeStatus
  riskLevel: RiskLevel
  version: number
  format: ConfigFormat
  itemCount: number
}

/** PUT /changes/{changeNo}（草稿编辑） */
export interface UpdateChangeRequest {
  version: number
  title?: string
  reason?: string
  items?: ChangeItemInput[]
  content?: string
  plannedAt?: string
}

/** GET /changes 列表项 [TBD: 字段以联调为准] */
export interface ChangeListItem {
  changeNo: string
  title: string
  appId: string
  appName?: string
  envId: EnvId
  dataId: string
  changeType: ChangeType
  status: ChangeStatus
  riskLevel: RiskLevel
  createdBy: string
  createdAt: string
  plannedAt?: string
}

/** GET /changes/{changeNo} */
export interface ChangeDetail {
  changeNo: string
  appId: string
  envId: EnvId
  mappingId: string
  dataId: string
  format: ConfigFormat
  changeType: ChangeType
  title: string
  reason: string
  status: ChangeStatus
  riskLevel: RiskLevel
  version: number
  items?: ConfigItem[]
  content?: string
  baselineMd5?: string
  currentMd5?: string
  plannedAt?: string
  createdAt: string
  /** 基线漂移标记（MUST-05：发布前重读不一致退回审批并展示三方 Diff） */
  baselineDrift?: boolean
}

/** GET /changes/{changeNo}/impact */
export interface ImpactSubscriber {
  instanceId: string
  appName?: string
  owner?: string
  /** 映射缺失时为“未知”并阻断高风险发布（IMP-409-001） */
  unknown: boolean
}

export interface ImpactReport {
  changeNo: string
  riskLevel: RiskLevel
  subscribers: ImpactSubscriber[]
  unknownCount: number
  /** 影响面未知时高风险发布被阻断 */
  blocked: boolean
}

/* ===================== 19-21 审批 ===================== */

/** POST /changes/{changeNo}/reject（理由 ≥ 10 字，APR-422-001） */
export interface RejectRequest {
  reason: string
}

/** POST /changes/{changeNo}/transfer */
export interface TransferRequest {
  targetUserId: string
  reason?: string
}

/* ===================== 22-28 发布 / 回滚 / 定时 ===================== */

/** 发布结果三态（MUST-04）：不确定只进人工对账，不自动重试 */
export type ReleaseResult = 'verified' | 'rejected' | 'indeterminate'

/** 发布预检项（P-08 展示 6 项预检）[TBD: 具体 6 项以联调为准] */
export interface PreflightCheck {
  name: string
  passed: boolean
  reason?: string
}

/** GET /releases */
export interface ReleaseRecord {
  releaseId: string
  changeNo: string
  result: ReleaseResult
  /** 写后读回 MD5（MUST-02） */
  readbackMd5?: string
  traceId?: string
  releasedBy: string
  releasedAt: string
}

/** GET /releases/{releaseId} */
export interface ReleaseDetail extends ReleaseRecord {
  preflight?: PreflightCheck[]
  dataId: string
  envId: EnvId
  targetVersion?: string
  error?: ApiErrorBody
}

/** POST /changes/{changeNo}/schedule */
export interface ScheduleRequest {
  plannedAt: string
}

/** 定时任务（§3.5.2：取消、查看复核失败；到点复核失败转“待发布”并告警，不自动补发） */
export interface ScheduleJob {
  jobId: string
  changeNo: string
  plannedAt: string
  status: 'ACTIVE' | 'CANCELED' | 'REVIEW_FAILED' | 'EXECUTED' | (string & {})
  reviewReason?: string
}

/* ===================== 29-32 批量 ===================== */

/** 批量子项输入：每个 DataId 一个子项，同 DataId 多 key 合并 */
export interface BatchItemInput {
  mappingId: string
  items: ChangeItemInput[]
}

/** POST /batches */
export interface BatchCreateRequest {
  title: string
  reason: string
  items: BatchItemInput[]
}

export interface BatchCreateResponse {
  batchId: string
  childCount: number
  status: 'DRAFT' | (string & {})
}

/** PUT /batches/{batchId}/items/{itemId}（草稿阶段编辑值） */
export interface BatchItemUpdateRequest {
  version: number
  items: ChangeItemInput[]
}

export interface BatchItemUpdateResponse {
  itemId: string
  status: string
  version: number
  itemCount: number
}

/* ===================== 33-35 跨环境同步 ===================== */

/** POST /env-sync/compare（只读对比） */
export interface EnvSyncCompareRequest {
  sourceEnvId: EnvId
  targetEnvId: EnvId
  appId?: string
  dataId?: string
}

export interface EnvSyncDiff {
  mappingId: string
  dataId: string
  same: boolean
  sourceMd5: string
  targetMd5: string
}

export interface EnvSyncSnapshot {
  snapshotId: string
  sourceEnvId: EnvId
  targetEnvId: EnvId
  collectedAt: string
  diffs: EnvSyncDiff[]
}

/** POST /env-sync/apply（只创建目标环境变更单，源环境只读） */
export interface EnvSyncApplyRequest {
  snapshotId: string
  targetEnvId: EnvId
  mappingIds: string[]
  title?: string
  reason?: string
}

export interface EnvSyncApplyResponse {
  changeNos: string[]
}

/* ===================== 36-37 实例 ===================== */

/** GET /instances */
export interface InstanceInfo {
  instanceId: string
  envId: EnvId
  endpoint: string
  health: 'UP' | 'DOWN' | 'DEGRADED' | (string & {})
  lastCheckedAt: string
}

/** POST /instances/{id}/test */
export interface InstanceTestResult {
  instanceId: string
  ok: boolean
  latencyMs?: number
  message?: string
  testedAt: string
}

/* ===================== 38-40 通知绑定 ===================== */

/** GET /notify-bindings */
export interface NotifyBinding {
  appId: string
  envId: EnvId
  channel: string
  target: string
  updatedAt: string
}

/** PUT /notify-bindings/{appId}/{envId} */
export interface SaveNotifyBindingRequest {
  channel: string
  target: string
}

/* ===================== 41-44 审计与直连 ===================== */

/** GET /audit-events（append-only + 哈希链） */
export interface AuditEvent {
  eventId: string
  eventType: string
  actor: string
  target: string
  traceId: string
  createdAt: string
  hash: string
  prevHash: string
}

export interface AuditEventQuery extends PageQuery {
  keyword?: string
  eventType?: string
  actor?: string
  from?: string
  to?: string
}

/** POST /audit-events/export */
export interface AuditExportRequest extends AuditEventQuery {
  format: 'CSV' | 'JSON'
}

/** GET /direct-changes（直连变更告警，5 分钟内生成） */
export interface DirectChangeEvent {
  id: string
  instanceId: string
  dataId: string
  detectedAt: string
  /** 未匹配到应用/负责人时为 false（检测盲区） */
  matched: boolean
  status: 'OPEN' | 'CLOSED' | (string & {})
}

/* ===================== 45-46 回收站 ===================== */

/** GET /recycle-bin（软删除保留 30 天） */
export interface RecycleBinItem {
  itemId: string
  appId: string
  envId: EnvId
  dataId: string
  deletedBy: string
  deletedAt: string
  /** 过期时间（30 天保留期） */
  expireAt: string
}

/** POST /recycle-bin/{id}/restore（同名冲突只允许“覆盖并重新审批”或“放弃”） */
export interface RestoreRecycleRequest {
  onConflict: 'OVERRIDE_AND_REAPPROVE' | 'ABANDON'
  itemVersion?: string
}

export interface RestoreRecycleResponse {
  changeNo?: string
  conflict: boolean
}
