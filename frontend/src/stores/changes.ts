/**
 * 变更域 store 与展示辅助（P-02 工作台 / P-04 列表 / P-06 详情 / P-07 审批 共用）
 * - 状态标签：合并 api/types.ts 与详细设计 §4.2 两套状态词汇；未知状态显示原值（§3.5.1）
 * - 审批守卫：职责分离 MUST-06（AUTHZ-403-002）+ 高风险负责人制（AUTHZ-403-003）
 */
import { defineStore } from 'pinia'
import { changeApi } from '@/api'
import type { ChangeListQuery } from '@/api/modules/change'
import type { ApiError } from '@/api/http'
import type { ChangeDetail, ChangeListItem, ImpactReport, PageResult } from '@/api/types'
import { useAuthStore } from '@/stores/auth'

export interface UiError {
  code?: string
  message?: string
  traceId?: string
}

export function toUiError(e: unknown): UiError {
  const err = e as Partial<ApiError> | null
  return { code: err?.code, message: err?.message, traceId: err?.traceId }
}

/** 列表端点返回数组或分页对象，统一取数组 */
export function asList<T>(res: unknown): T[] {
  if (Array.isArray(res)) return res as T[]
  const items = (res as Partial<PageResult<T>> | null)?.items
  return Array.isArray(items) ? items : []
}

export type Tone = 'volt' | 'amber' | 'red' | 'cyan' | 'mute'

export const STATUS_LABEL: Record<string, string> = {
  DRAFT: '草稿',
  PENDING_APPROVAL: '待审批',
  APPROVED: '已通过',
  PENDING_RELEASE: '待发布',
  SCHEDULED: '已定时',
  RELEASING: '发布中',
  RELEASED: '已发布',
  SUCCEEDED: '已发布',
  FAILED: '失败',
  REJECTED: '已驳回',
  CANCELED: '已取消',
  CANCELLED: '已取消',
  INDETERMINATE: '结果不确定',
}

export function statusLabel(status?: string): string {
  if (!status) return '—'
  return STATUS_LABEL[status] ?? status
}

export function statusTone(status?: string): Tone {
  switch (status) {
    case 'PENDING_APPROVAL':
    case 'INDETERMINATE':
      return 'amber'
    case 'APPROVED':
    case 'PENDING_RELEASE':
    case 'SCHEDULED':
      return 'cyan'
    case 'RELEASING':
    case 'RELEASED':
    case 'SUCCEEDED':
      return 'volt'
    case 'REJECTED':
    case 'FAILED':
      return 'red'
    default:
      return 'mute'
  }
}

const RISK_LABEL: Record<string, string> = { LOW: '低风险', MEDIUM: '中风险', HIGH: '高风险' }

export function riskLabel(risk?: string): string {
  if (!risk) return '—'
  return RISK_LABEL[risk] ?? risk
}

export function riskTone(risk?: string): Tone {
  switch (risk) {
    case 'HIGH':
      return 'red'
    case 'MEDIUM':
      return 'amber'
    case 'LOW':
      return 'cyan'
    default:
      return 'mute'
  }
}

export const CHANGE_TYPE_LABEL: Record<string, string> = {
  CREATE: '新建',
  UPDATE: '修改',
  DELETE: '删除',
  RESTORE: '恢复',
  ROLLBACK: '回滚',
  SYNC: '同步',
}

export function changeTypeLabel(type?: string): string {
  if (!type) return '—'
  return CHANGE_TYPE_LABEL[type] ?? type
}

const timeFormatter = new Intl.DateTimeFormat('zh-CN', {
  timeZone: 'Asia/Shanghai',
  year: 'numeric',
  month: '2-digit',
  day: '2-digit',
  hour: '2-digit',
  minute: '2-digit',
  second: '2-digit',
  hour12: false,
})

/** ISO-8601 UTC → Asia/Shanghai 展示（契约 §1） */
export function fmtTime(iso?: string | null): string {
  if (!iso) return '—'
  const d = new Date(iso)
  if (Number.isNaN(d.getTime())) return String(iso)
  return timeFormatter.format(d).replace(/\//g, '-')
}

/** 进行中（非草稿非终态）状态集合，用于工作台统计 */
export const ONGOING_STATUSES: string[] = [
  'PENDING_APPROVAL',
  'APPROVED',
  'PENDING_RELEASE',
  'SCHEDULED',
  'RELEASING',
]

/** 原因 / 理由最小长度（≥10 字，CR-400-001 / APR-422-001 前端先挡） */
export const REASON_MIN = 10

export function reasonInvalid(reason: string): string | null {
  const len = reason.trim().length
  if (len === 0) return '请填写原因（不少于 10 个字）'
  if (len < REASON_MIN) return `原因不少于 ${REASON_MIN} 个字（当前 ${len} 个）`
  return null
}

/* ────────────────────────── Diff 构造 ────────────────────────── */

export interface DiffRowInput {
  key: string
  state: 'added' | 'modified' | 'deleted' | 'unchanged'
  before?: string
  after?: string
  baseline?: string
  sensitive?: boolean
}

export interface ItemLike {
  key: string
  value: string
  sensitive?: boolean
}

/** 按项 Diff：next 中只包含被修改/新增的项；敏感项标记后由 DiffView 脱敏 */
export function buildItemDiffRows(current: ItemLike[], next: ItemLike[]): DiffRowInput[] {
  const nextMap = new Map(next.map((n) => [n.key, n.value]))
  const currentKeys = new Set(current.map((c) => c.key))
  const rows: DiffRowInput[] = []
  for (const item of current) {
    const nv = nextMap.get(item.key)
    if (nv === undefined) {
      rows.push({
        key: item.key,
        state: 'unchanged',
        before: item.value,
        after: item.value,
        sensitive: item.sensitive,
      })
    } else {
      rows.push({
        key: item.key,
        state: nv === item.value ? 'unchanged' : 'modified',
        before: item.value,
        after: nv,
        sensitive: item.sensitive,
      })
    }
  }
  for (const n of next) {
    if (!currentKeys.has(n.key)) {
      rows.push({ key: n.key, state: 'added', before: undefined, after: n.value, sensitive: n.sensitive })
    }
  }
  return rows
}

/** 整文件 / TEXT 内容 Diff：单行展示全文 */
export function buildContentDiffRows(
  currentContent: string | undefined,
  nextContent: string,
  opts?: { key?: string; created?: boolean; sensitive?: boolean },
): DiffRowInput[] {
  const key = opts?.key ?? 'content（整文件）'
  if (opts?.created) {
    return [{ key, state: 'added', before: undefined, after: nextContent, sensitive: opts?.sensitive }]
  }
  return [
    {
      key,
      state: nextContent === currentContent ? 'unchanged' : 'modified',
      before: currentContent,
      after: nextContent,
      sensitive: opts?.sensitive,
    },
  ]
}

/** 删除类变更 Diff：全部标记删除 */
export function buildDeleteDiffRows(current: ItemLike[]): DiffRowInput[] {
  return current.map((i) => ({ key: i.key, state: 'deleted' as const, before: i.value, sensitive: i.sensitive }))
}

/* ────────────────────────── 审批守卫 ────────────────────────── */

export interface ApproveGuardInput {
  createdBy?: string
  riskLevel?: string
  /** 应用负责人（AppItem.owner） */
  appOwner?: string
}

export interface ApproveGuardResult {
  canApprove: boolean
  why?: string
}

/**
 * 是否可执行「通过」：APPROVE 权限点 + 非提交人（MUST-06 / AUTHZ-403-002）
 * + 高风险仅应用负责人 / APP_OWNER 角色 / ADMIN（AUTHZ-403-003）
 */
export function approveGuard(input: ApproveGuardInput): ApproveGuardResult {
  const auth = useAuthStore()
  if (!auth.can('APPROVE')) {
    return { canApprove: false, why: '缺少 APPROVE 权限点，审批动作不可用' }
  }
  const me = auth.user
  const meIds = me ? [me.userId, me.username, me.displayName] : []
  if (input.createdBy && meIds.includes(input.createdBy)) {
    return { canApprove: false, why: '你提交的变更单不能审批（职责分离 MUST-06 / AUTHZ-403-002）' }
  }
  if (input.riskLevel === 'HIGH') {
    const ownerOk =
      (!!input.appOwner && meIds.includes(input.appOwner)) ||
      auth.roles.includes('APP_OWNER') ||
      auth.can('ADMIN')
    if (!ownerOk) {
      return { canApprove: false, why: '高风险变更仅应用负责人或审计过的代理人可通过（AUTHZ-403-003）' }
    }
  }
  return { canApprove: true }
}

/* ────────────────────────── store ────────────────────────── */

interface ChangesState {
  list: ChangeListItem[]
  listLoading: boolean
  listError: UiError | null
  detail: ChangeDetail | null
  detailLoading: boolean
  detailError: UiError | null
  impact: ImpactReport | null
  impactLoading: boolean
  impactError: UiError | null
}

export const useChangesStore = defineStore('changes', {
  state: (): ChangesState => ({
    list: [],
    listLoading: false,
    listError: null,
    detail: null,
    detailLoading: false,
    detailError: null,
    impact: null,
    impactLoading: false,
    impactError: null,
  }),
  actions: {
    async fetchList(params: ChangeListQuery = {}) {
      this.listLoading = true
      this.listError = null
      try {
        const res = await changeApi.listChanges(params)
        this.list = asList<ChangeListItem>(res)
      } catch (e) {
        this.list = []
        this.listError = toUiError(e)
      } finally {
        this.listLoading = false
      }
    },
    async fetchDetail(changeNo: string) {
      this.detailLoading = true
      this.detailError = null
      try {
        this.detail = await changeApi.getChange(changeNo)
      } catch (e) {
        this.detail = null
        this.detailError = toUiError(e)
      } finally {
        this.detailLoading = false
      }
    },
    async fetchImpact(changeNo: string) {
      this.impactLoading = true
      this.impactError = null
      try {
        this.impact = await changeApi.getImpact(changeNo)
      } catch (e) {
        this.impact = null
        this.impactError = toUiError(e)
      } finally {
        this.impactLoading = false
      }
    },
    resetDetail() {
      this.detail = null
      this.detailError = null
      this.impact = null
      this.impactError = null
    },
  },
})
