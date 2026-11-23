/**
 * 审计与检测 store —— P-13（契约 §2.5(41-44)、详细设计 §3.5.2 监督）
 * 41 GET /audit-events（查询）      42 POST /audit-events/export（导出，AUD-500-001）
 * 43 GET /direct-changes（直连告警） 44 POST /direct-changes/{id}/close（处置，幂等）
 *
 * 敏感红线（MUST-NOT-05 / 详细设计 §9.5）：审计事件仅含脱敏快照（before/after masked），
 * 本 store 不请求、不缓存任何敏感明文；导出内容亦不含敏感明文。
 *
 * 哈希链校验：事件为 append-only 按日链（chain_date + seq），每条携带 prevHash / hash。
 * 前端校验 = 对当前查询结果做链式一致性核对（prevHash === 上一条 hash + 64 位十六进制格式），
 * 校验范围与结论一并展示，不臆断链外结论。
 */
import { defineStore } from 'pinia'
import { auditApi } from '@/api'
import type { ApiError } from '@/api/http'
import type { AuditEvent, AuditEventQuery, DirectChangeEvent } from '@/api/types'

/** 查询筛选（§2.5(41)：keyword / eventType / actor / from / to + page / size） */
export interface AuditFilter {
  keyword: string
  /** 动作枚举（§10.1 固定枚举）；空 = 不限 */
  eventType: string
  actor: string
  /** ISO-8601 时间范围 */
  from: string
  to: string
}

export type ChainCheckStatus = 'idle' | 'ok' | 'broken'

/** 链校验结果（只对当前已加载事件下结论） */
export interface ChainCheckResult {
  status: ChainCheckStatus
  /** 参与校验的事件数 */
  checked: number
  /** 断链 / 格式异常条数 */
  breaks: number
  /** 异常事件 ID（含哈希格式不符、prevHash 断链） */
  brokenEventIds: string[]
  /** 链头哈希（当前最新事件的 event_hash） */
  headHash: string
  checkedAt: string
}

export interface AuditErrorState {
  code: string
  message: string
  traceId: string
}

function toErrorState(e: unknown): AuditErrorState {
  const err = e as ApiError
  return {
    code: err?.code ?? 'SYS-500-001',
    message: err?.message ?? '请求失败，请稍后重试',
    traceId: err?.traceId ?? '',
  }
}

const HASH_RE = /^[0-9a-f]{64}$/i

interface AuditState {
  events: AuditEvent[]
  total: number
  page: number
  size: number
  filter: AuditFilter
  loading: boolean
  error: AuditErrorState | null
  exporting: boolean
  chainResult: ChainCheckResult | null
  chainChecking: boolean
  directChanges: DirectChangeEvent[]
  directLoading: boolean
  directError: AuditErrorState | null
  /** '' = 全部；否则按状态过滤（OPEN / CLOSED） */
  directStatus: string
  closingId: string
}

export const useAuditStore = defineStore('audit', {
  state: (): AuditState => ({
    events: [],
    total: 0,
    page: 0,
    size: 20,
    filter: { keyword: '', eventType: '', actor: '', from: '', to: '' },
    loading: false,
    error: null,
    exporting: false,
    chainResult: null,
    chainChecking: false,
    directChanges: [],
    directLoading: false,
    directError: null,
    directStatus: '',
    closingId: '',
  }),

  getters: {
    /** 当前查询条件（丢弃空值，保持请求体干净） */
    query: (s): AuditEventQuery => {
      const q: AuditEventQuery = { page: s.page, size: s.size }
      if (s.filter.keyword.trim()) q.keyword = s.filter.keyword.trim()
      if (s.filter.eventType) q.eventType = s.filter.eventType
      if (s.filter.actor.trim()) q.actor = s.filter.actor.trim()
      if (s.filter.from) q.from = s.filter.from
      if (s.filter.to) q.to = s.filter.to
      return q
    },

    /** 直连告警按状态过滤 */
    visibleDirectChanges: (s): DirectChangeEvent[] =>
      s.directStatus ? s.directChanges.filter((d) => d.status === s.directStatus) : s.directChanges,

    openDirectCount: (s): number => s.directChanges.filter((d) => d.status === 'OPEN').length,
  },

  actions: {
    /** 41. 审计事件查询；换筛选 / 翻页后链校验结论失效，需重新校验 */
    async fetchEvents() {
      this.loading = true
      this.error = null
      try {
        const res = await auditApi.listAuditEvents(this.query)
        this.events = res.items
        this.total = res.total
        this.chainResult = null
      } catch (e) {
        this.error = toErrorState(e)
      } finally {
        this.loading = false
      }
    },

    /** 翻页（0 基，与契约 §1.1 一致） */
    async setPage(page: number) {
      this.page = page
      await this.fetchEvents()
    },

    async setSize(size: number) {
      this.size = size
      this.page = 0
      await this.fetchEvents()
    },

    /** 应用筛选并回到第一页 */
    async applyFilter(filter: Partial<AuditFilter>) {
      this.filter = { ...this.filter, ...filter }
      this.page = 0
      await this.fetchEvents()
    },

    /** 42. 导出审计事件（EXPORT_AUDIT；返回 Blob 由视图触发下载；导出不含敏感明文） */
    async exportEvents(format: 'CSV' | 'JSON'): Promise<Blob> {
      this.exporting = true
      try {
        return await auditApi.exportAuditEvents({ ...this.query, format })
      } finally {
        this.exporting = false
      }
    },

    /**
     * 哈希链校验（前端一致性核对，范围 = 当前查询已加载事件）：
     * 1. 每条 event_hash / prev_hash 须为 64 位十六进制；
     * 2. 相邻事件 prevHash 必须等于上一条 hash（append-only 链式结构）。
     * 不臆断链外结论：只报告已核对条数与断点位置。
     */
    verifyChain(): ChainCheckResult {
      this.chainChecking = true
      try {
        const list = [...this.events].sort((a, b) =>
          a.createdAt === b.createdAt ? a.eventId.localeCompare(b.eventId) : a.createdAt < b.createdAt ? -1 : 1,
        )
        const brokenEventIds: string[] = []
        for (const ev of list) {
          if (!HASH_RE.test(ev.hash ?? '') || !HASH_RE.test(ev.prevHash ?? '')) brokenEventIds.push(ev.eventId)
        }
        for (let i = 1; i < list.length; i++) {
          const prev = list[i - 1]
          const cur = list[i]
          if (HASH_RE.test(cur.prevHash ?? '') && HASH_RE.test(prev.hash ?? '') && cur.prevHash !== prev.hash) {
            if (!brokenEventIds.includes(cur.eventId)) brokenEventIds.push(cur.eventId)
          }
        }
        const result: ChainCheckResult = {
          status: brokenEventIds.length > 0 ? 'broken' : 'ok',
          checked: list.length,
          breaks: brokenEventIds.length,
          brokenEventIds,
          headHash: list.length > 0 ? list[list.length - 1].hash : '',
          checkedAt: new Date().toISOString(),
        }
        this.chainResult = result
        return result
      } finally {
        this.chainChecking = false
      }
    },

    /** 43. 直连变更告警列表 */
    async fetchDirectChanges() {
      this.directLoading = true
      this.directError = null
      try {
        this.directChanges = await auditApi.listDirectChanges()
      } catch (e) {
        this.directError = toErrorState(e)
      } finally {
        this.directLoading = false
      }
    },

    /** 44. 处置直连告警（幂等；关闭即“已知悉”并写审计） */
    async closeDirectChange(id: string): Promise<void> {
      this.closingId = id
      try {
        await auditApi.closeDirectChange(id)
        await this.fetchDirectChanges()
      } finally {
        this.closingId = ''
      }
    },

    /** 页面初始化：并行拉取审计流与直连告警 */
    async init() {
      await Promise.all([this.fetchEvents(), this.fetchDirectChanges()])
    },
  },
})
