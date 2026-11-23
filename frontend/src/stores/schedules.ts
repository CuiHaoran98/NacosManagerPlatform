/**
 * 定时任务 store（P-10，契约 #24 定时设置 / #25 取消定时）
 *
 * 契约未提供定时任务列表端点 [TBD]，行集合 = 两部分合并（状态只用后端返回值）：
 *  - listChanges({status:'SCHEDULED'}) 推导行（到点执行中的计划）；
 *  - 本会话 createSchedule / cancelSchedule 返回的 ScheduleJob（覆盖对应变更单行的状态，
 *    未在推导行中的（如已取消 / 复核失败）也保留展示）。
 */
import { defineStore } from 'pinia'
import { changeApi, schedulerApi } from '@/api'
import type { ScheduleJob } from '@/api/types'

/** 统一错误态（README：错误按 err.code 分支，不解析 message） */
export interface ErrorState {
  code: string
  message: string
  traceId: string
}

export function toErrorState(e: unknown): ErrorState {
  const err = e as { code?: unknown; message?: unknown; traceId?: unknown }
  return {
    code: typeof err?.code === 'string' ? err.code : 'SYS-500-001',
    message: typeof err?.message === 'string' ? err.message : '请求失败，请稍后重试',
    traceId: typeof err?.traceId === 'string' ? err.traceId : '',
  }
}

/** 时间展示：后端 ISO-8601 UTC → Asia/Shanghai（README 时间约定） */
export function fmtTime(iso?: string): string {
  if (!iso) return '—'
  const d = new Date(iso)
  if (Number.isNaN(d.getTime())) return iso
  return new Intl.DateTimeFormat('zh-CN', {
    timeZone: 'Asia/Shanghai',
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
    hour12: false,
  })
    .format(d)
    .replace(/\//g, '-')
}

export interface ScheduleRow {
  jobId: string
  changeNo: string
  title: string
  envId: string
  plannedAt: string
  status: string
  reviewReason?: string
}

interface SchedulesState {
  rows: ScheduleRow[]
  loading: boolean
  error: ErrorState | null
  /** 本会话经 #24/#25 拿到的任务（key = changeNo） */
  tracked: Record<string, ScheduleJob>
}

export const useSchedulesStore = defineStore('schedules', {
  state: (): SchedulesState => ({
    rows: [],
    loading: false,
    error: null,
    tracked: {},
  }),
  actions: {
    async fetch() {
      this.loading = true
      this.error = null
      try {
        const changes = await changeApi.listChanges({ status: 'SCHEDULED', size: 100 })
        const byNo = new Map<string, ScheduleRow>()
        for (const c of changes) {
          const job = this.tracked[c.changeNo]
          byNo.set(c.changeNo, {
            jobId: job?.jobId ?? c.changeNo,
            changeNo: c.changeNo,
            title: c.title,
            envId: c.envId,
            plannedAt: job?.plannedAt ?? c.plannedAt ?? '',
            status: job?.status ?? 'ACTIVE',
            reviewReason: job?.reviewReason,
          })
        }
        for (const job of Object.values(this.tracked)) {
          if (!byNo.has(job.changeNo)) {
            byNo.set(job.changeNo, {
              jobId: job.jobId,
              changeNo: job.changeNo,
              title: '',
              envId: '',
              plannedAt: job.plannedAt,
              status: job.status,
              reviewReason: job.reviewReason,
            })
          }
        }
        this.rows = [...byNo.values()].sort((a, b) => (a.plannedAt < b.plannedAt ? 1 : -1))
      } catch (e) {
        this.error = toErrorState(e)
      } finally {
        this.loading = false
      }
    },
    /** #24 定时设置（PUBLISH；非幂等；错误 SCH-409-001） */
    async schedule(
      changeNo: string,
      plannedAt: string,
      hint?: { title?: string; envId?: string },
    ): Promise<ScheduleJob> {
      const job = await schedulerApi.createSchedule(changeNo, { plannedAt })
      this.tracked = { ...this.tracked, [changeNo]: job }
      const prev = this.rows.find((r) => r.changeNo === changeNo)
      this.rows = [
        {
          jobId: job.jobId,
          changeNo,
          title: hint?.title ?? prev?.title ?? '',
          envId: hint?.envId ?? prev?.envId ?? '',
          plannedAt: job.plannedAt,
          status: job.status,
          reviewReason: job.reviewReason,
        },
        ...this.rows.filter((r) => r.changeNo !== changeNo),
      ]
      return job
    },
    /** #25 取消定时（PUBLISH；幂等；错误 SCH-409-001） */
    async cancel(changeNo: string) {
      await schedulerApi.cancelSchedule(changeNo)
      const prev = this.tracked[changeNo]
      if (prev) {
        this.tracked = { ...this.tracked, [changeNo]: { ...prev, status: 'CANCELED' } }
      }
      this.rows = this.rows.map((r) =>
        r.changeNo === changeNo ? { ...r, status: prev ? 'CANCELED' : r.status } : r,
      )
      await this.fetch()
    },
  },
})
