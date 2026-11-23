/**
 * 回收站 store（P-12，契约 #45 列表 / #46 恢复）
 * 软删除保留 30 天；同名冲突只允许“覆盖并重新审批”或“放弃”（RB-409-001，禁止静默覆盖）；
 * 超期不可恢复（RB-410-001）。恢复必须写 TongNCS 并读回。
 */
import { defineStore } from 'pinia'
import { recycleBinApi } from '@/api'
import type {
  RecycleBinItem,
  RestoreRecycleRequest,
  RestoreRecycleResponse,
} from '@/api/types'
import { toErrorState, type ErrorState } from './schedules'

/** 剩余保留天数（expireAt 为 30 天保留期截止） */
export function daysLeft(expireAt: string): number {
  const t = new Date(expireAt).getTime()
  if (Number.isNaN(t)) return 0
  return Math.ceil((t - Date.now()) / 86_400_000)
}

export interface RecycleBinFilters {
  appId?: string
  envId?: string
  keyword?: string
  page?: number
  size?: number
}

interface RecycleBinState {
  items: RecycleBinItem[]
  total: number
  filters: RecycleBinFilters
  loading: boolean
  error: ErrorState | null
  restoring: Record<string, boolean>
}

export const useRecycleBinStore = defineStore('recycle-bin', {
  state: (): RecycleBinState => ({
    items: [],
    total: 0,
    filters: { page: 0, size: 20 },
    loading: false,
    error: null,
    restoring: {},
  }),
  actions: {
    /** #45 回收站列表（PUBLISH；软删 30 天；分页 page 从 0 开始） */
    async fetch() {
      this.loading = true
      this.error = null
      try {
        const page = await recycleBinApi.listRecycleBin({
          appId: this.filters.appId || undefined,
          envId: this.filters.envId || undefined,
          keyword: this.filters.keyword || undefined,
          page: this.filters.page ?? 0,
          size: this.filters.size ?? 20,
        })
        this.items = page.items
        this.total = page.total
      } catch (e) {
        this.error = toErrorState(e)
      } finally {
        this.loading = false
      }
    },
    /**
     * #46 恢复（PUBLISH；幂等）。
     * 首次调用传 onConflict='ABANDON'（绝不静默覆盖）；
     * 返回 conflict=true 或 RB-409-001 时由视图弹冲突选择：
     * “覆盖并重新审批”（OVERRIDE_AND_REAPPROVE，生成覆盖变更单）或“放弃”。
     */
    async restore(
      id: string,
      onConflict: RestoreRecycleRequest['onConflict'],
      itemVersion?: RestoreRecycleRequest['itemVersion'],
    ): Promise<RestoreRecycleResponse> {
      this.restoring = { ...this.restoring, [id]: true }
      try {
        return await recycleBinApi.restoreRecycleItem(id, { onConflict, itemVersion })
      } finally {
        this.restoring = { ...this.restoring, [id]: false }
      }
    },
  },
})
