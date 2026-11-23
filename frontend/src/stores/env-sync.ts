/**
 * 跨环境同步 store（P-09，契约 #33 对比 / #34 生成目标变更单 / #35 快照详情）
 * 只读对比：同步只创建目标环境变更单，必须经目标环境审批与发布，源环境不被修改。
 */
import { defineStore } from 'pinia'
import { envSyncApi } from '@/api'
import type {
  EnvSyncApplyRequest,
  EnvSyncCompareRequest,
  EnvSyncSnapshot,
} from '@/api/types'
import { toErrorState, type ErrorState } from './schedules'

interface EnvSyncState {
  snapshot: EnvSyncSnapshot | null
  /** 勾选的差异项 mappingId（仅差异项可选） */
  selected: string[]
  loading: boolean
  applying: boolean
  error: ErrorState | null
  /** 生成结果：目标环境变更单号 */
  createdChangeNos: string[]
  createdTargetEnvId: string
}

export const useEnvSyncStore = defineStore('env-sync', {
  state: (): EnvSyncState => ({
    snapshot: null,
    selected: [],
    loading: false,
    applying: false,
    error: null,
    createdChangeNos: [],
    createdTargetEnvId: '',
  }),
  getters: {
    /** 差异项（仅这些可勾选） */
    diffRows: (s) => s.snapshot?.diffs.filter((d) => !d.same) ?? [],
  },
  actions: {
    /** #33 跨环境对比（VIEW；SYN-409-002 映射或格式不一致） */
    async compare(data: EnvSyncCompareRequest) {
      this.loading = true
      this.error = null
      this.createdChangeNos = []
      try {
        this.snapshot = await envSyncApi.compareEnv(data)
        this.selected = []
      } catch (e) {
        this.error = toErrorState(e)
        throw e
      } finally {
        this.loading = false
      }
    },
    /** #35 快照详情（SYS-404-001） */
    async loadSnapshot(snapshotId: string) {
      this.loading = true
      this.error = null
      try {
        this.snapshot = await envSyncApi.getSyncSnapshot(snapshotId)
        this.selected = []
      } catch (e) {
        this.error = toErrorState(e)
        throw e
      } finally {
        this.loading = false
      }
    },
    /** #34 生成目标环境变更单（EDIT；SYN-409-001 目标已有进行中变更单） */
    async apply(data: Omit<EnvSyncApplyRequest, 'snapshotId' | 'targetEnvId' | 'mappingIds'>) {
      if (!this.snapshot) return { changeNos: [] }
      this.applying = true
      this.error = null
      const req: EnvSyncApplyRequest = {
        snapshotId: this.snapshot.snapshotId,
        targetEnvId: this.snapshot.targetEnvId,
        mappingIds: this.selected,
        title: data.title,
        reason: data.reason,
      }
      try {
        const resp = await envSyncApi.applyEnvSync(req)
        this.createdChangeNos = resp.changeNos
        this.createdTargetEnvId = this.snapshot.targetEnvId
        this.selected = []
        return resp
      } catch (e) {
        this.error = toErrorState(e)
        throw e
      } finally {
        this.applying = false
      }
    },
  },
})
