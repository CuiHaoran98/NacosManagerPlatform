/**
 * 发布控制台 store（P-08，契约 #22 发布 / #23 回滚 / #26 对账 / #27 发布记录 / #28 结果详情）
 * 业务红线：发布结果三态 verified / rejected / indeterminate；
 * 不确定结果只进人工对账、禁止自动重试（REL-502-001）。
 */
import { defineStore } from 'pinia'
import { releaseApi } from '@/api'
import type { ReleaseDetail, ReleaseRecord, ReleaseResult } from '@/api/types'
import { toErrorState, type ErrorState } from './schedules'

export interface ReleasesQuery {
  changeNo?: string
  result?: string
  envId?: string
}

/** 三态展示元数据（仅展示层映射，业务状态值仍用后端返回值） */
export const RESULT_META: Record<
  ReleaseResult,
  { label: string; type: 'success' | 'danger' | 'warning'; desc: string }
> = {
  verified: { label: 'verified · 读回一致', type: 'success', desc: '读回 MD5 与草稿一致，发布记录与审计已写入。' },
  rejected: { label: 'rejected · 已拒绝', type: 'danger', desc: '写入被 TongNCS 拒绝，未产生生效变更；可修改后重新提交。' },
  indeterminate: {
    label: 'indeterminate · 结果不确定',
    type: 'warning',
    desc: '写入请求已发出但读回无法确认。禁止自动重试，只允许人工对账。',
  },
}

interface ReleasesState {
  records: ReleaseRecord[]
  query: ReleasesQuery
  loading: boolean
  error: ErrorState | null
  detail: ReleaseDetail | null
  detailLoading: boolean
  /** 最近一次执行发布 / 回滚的结果（三态结果卡） */
  lastRun: ReleaseDetail | null
}

export const useReleasesStore = defineStore('releases', {
  state: (): ReleasesState => ({
    records: [],
    query: {},
    loading: false,
    error: null,
    detail: null,
    detailLoading: false,
    lastRun: null,
  }),
  actions: {
    /** #27 发布记录（VIEW；支持 changeNo/result/envId 筛选） */
    async fetch() {
      this.loading = true
      this.error = null
      try {
        this.records = await releaseApi.listReleases({
          changeNo: this.query.changeNo || undefined,
          result: this.query.result || undefined,
          envId: this.query.envId || undefined,
          page: 0,
          size: 100,
        })
      } catch (e) {
        this.error = toErrorState(e)
      } finally {
        this.loading = false
      }
    },
    /** #28 结果详情（SYS-404-001） */
    async loadDetail(releaseId: string): Promise<ReleaseDetail> {
      this.detailLoading = true
      try {
        this.detail = await releaseApi.getRelease(releaseId)
        return this.detail
      } finally {
        this.detailLoading = false
      }
    },
    /** #22 执行发布（PUBLISH；幂等键=变更单号+目标版本；REL-409-002 幂等命中返回既有记录） */
    async runRelease(changeNo: string): Promise<ReleaseDetail> {
      const detail = await releaseApi.releaseChange(changeNo)
      this.lastRun = detail
      await this.fetch()
      return detail
    },
    /** #23 回滚（ROLLBACK；RB-410-001 超 30 天不可恢复） */
    async rollback(changeNo: string, targetVersion: string): Promise<ReleaseDetail> {
      const detail = await releaseApi.rollbackChange(changeNo, { targetVersion })
      this.lastRun = detail
      await this.fetch()
      return detail
    },
    /** #26 人工对账（PUBLISH；幂等；indeterminate 只走人工，REL-502-001） */
    async reconcile(releaseId: string, conclusion?: 'EFFECTIVE' | 'NOT_EFFECTIVE' | 'OVERRIDDEN'): Promise<ReleaseDetail> {
      const detail = await releaseApi.reconcileRelease(releaseId, conclusion)
      this.lastRun = detail
      await this.fetch()
      return detail
    },
  },
})

/** 结果三态判断（展示层） */
export function resultMeta(result: string) {
  return (
    RESULT_META[result as ReleaseResult] ?? {
      label: result,
      type: 'info' as const,
      desc: '',
    }
  )
}

export type { ErrorState }
export { toErrorState }
