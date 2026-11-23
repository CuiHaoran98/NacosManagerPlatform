/**
 * 批量变更 store（P-11，契约 #29 新建 / #30 草稿编辑值 / #31 提交 / #32 重试失败项）
 *
 * 契约未提供批量列表/详情端点 [TBD]：本会话创建的批次由本 store 跟踪。
 * 子项初始取自创建请求；创建响应若返回 items[]（含 itemId/version）则以响应为准
 * （契约 BatchCreateResponse 未列 items[]，联调补齐后编辑值自动可用）。
 * 提交/重试响应若返回 items[] 子项状态（[TBD]）则更新子项，否则保留已观测状态。
 */
import { defineStore } from 'pinia'
import { batchApi } from '@/api'
import type {
  BatchCreateRequest,
  BatchCreateResponse,
  BatchItemInput,
  BatchItemUpdateRequest,
  BatchItemUpdateResponse,
  ChangeItemInput,
} from '@/api/types'
import { toErrorState, type ErrorState } from './schedules'

/** 创建子项输入（契约 BatchItemInput + 展示用 dataId/敏感标记，发起方透传 [TBD]） */
export interface BatchCreateItem extends BatchItemInput {
  dataId?: string
  /** 含敏感项：编辑值需重新输入新值，不回显旧明文（契约 §3.5） */
  sensitive?: boolean
}

/** 批量子项（一 DataId 一子项，同 DataId 多 key 合并） */
export interface BatchChild {
  /** PUT /batches/{batchId}/items/{itemId} 需要；创建响应未返回 items[] 时为空 → 不可编辑 */
  itemId?: string
  mappingId: string
  dataId?: string
  items: ChangeItemInput[]
  /** 乐观锁版本（SYS-409-001） */
  version: number
  status: string
  itemCount?: number
  /** 失败子项错误（部分成功展示 + 重试失败项） */
  error?: ErrorState
  sensitive?: boolean
  /** 已重试次数 */
  retries?: number
}

export interface BatchRecord {
  batchId: string
  title: string
  reason?: string
  status: string
  childCount: number
  children: BatchChild[]
}

/** 创建响应扩展 [TBD]：items[] 含子项 itemId/version（契约未列，联调补齐） */
interface CreateResponseExt extends BatchCreateResponse {
  items?: Array<Partial<BatchItemUpdateResponse> & { mappingId?: string; error?: ErrorState }>
}

/** 提交/重试响应扩展 [TBD]：items[] 回传子项状态 */
interface ChildStatusExt {
  items?: Array<{
    itemId?: string
    mappingId?: string
    status?: string
    error?: ErrorState
    version?: number
    itemCount?: number
  }>
}

interface BatchesState {
  batches: BatchRecord[]
  /** 配置目录多选批量发起的待办输入（P-03 → P-11 交接） */
  pendingCreate: BatchCreateItem[] | null
  loading: boolean
  error: ErrorState | null
  /** 最近一次重试返回的子项数 */
  lastRetried: number | null
}

export const useBatchesStore = defineStore('batches', {
  state: (): BatchesState => ({
    batches: [],
    pendingCreate: null,
    loading: false,
    error: null,
    lastRetried: null,
  }),
  actions: {
    /** 配置目录多选批量发起：登记待填值子项，跳转 /batches 后打开新建弹窗 */
    seedFromCatalog(payload: BatchCreateItem[]) {
      this.pendingCreate = payload.map((p) => ({ ...p, items: p.items.map((i) => ({ ...i })) }))
    },
    clearPendingCreate() {
      this.pendingCreate = null
    },
    getBatch(batchId: string): BatchRecord | undefined {
      return this.batches.find((b) => b.batchId === batchId)
    },
    /** #29 新建批量（EDIT；CR-400-001/CR-409-001/CR-415-001/CR-422-001/BAT-413-001） */
    async createBatch(payload: {
      title: string
      reason: string
      items: BatchCreateItem[]
    }): Promise<BatchRecord> {
      const req: BatchCreateRequest = {
        title: payload.title,
        reason: payload.reason,
        items: payload.items.map((it) => ({ mappingId: it.mappingId, items: it.items })),
      }
      const resp = (await batchApi.createBatch(req)) as CreateResponseExt
      const children: BatchChild[] = payload.items.map((it, i) => {
        const s = resp.items?.[i]
        return {
          itemId: s?.itemId,
          mappingId: it.mappingId,
          dataId: it.dataId ?? s?.mappingId,
          items: it.items.map((x) => ({ ...x })),
          version: s?.version ?? 1,
          status: s?.status ?? 'DRAFT',
          itemCount: s?.itemCount,
          error: s?.error,
          sensitive: it.sensitive,
        }
      })
      const record: BatchRecord = {
        batchId: resp.batchId,
        title: payload.title,
        reason: payload.reason,
        status: resp.status,
        childCount: resp.childCount,
        children,
      }
      this.batches = [record, ...this.batches]
      return record
    },
    /** #30 草稿编辑值（EDIT；SYS-409-001 已提交后不可改；敏感项重新输入） */
    async updateItem(batchId: string, itemId: string, data: BatchItemUpdateRequest) {
      const resp = await batchApi.updateBatchItem(batchId, itemId, data)
      this.patchChild(batchId, itemId, {
        items: data.items.map((x) => ({ ...x })),
        status: resp.status,
        version: resp.version,
        itemCount: resp.itemCount,
        error: undefined,
      })
      return resp
    },
    /** #31 提交批量父单（SUBMIT；BAT-413-001） */
    async submit(batchId: string) {
      const resp = (await batchApi.submitBatch(batchId)) as {
        batchId: string
        status: string
      } & ChildStatusExt
      this.patchBatch(batchId, (b) => ({ ...b, status: resp.status }))
      this.patchChildrenFromExt(batchId, resp)
      return resp
    },
    /** #32 重试失败项（PUBLISH；幂等；只重试失败子项，成功子项永不重发；SYS-409-001） */
    async retry(batchId: string) {
      const resp = (await batchApi.retryBatch(batchId)) as {
        batchId: string
        retried: number
      } & ChildStatusExt
      this.lastRetried = resp.retried
      this.patchBatch(batchId, (b) => ({
        ...b,
        children: b.children.map((c) =>
          c.error ? { ...c, retries: (c.retries ?? 0) + 1 } : c,
        ),
      }))
      this.patchChildrenFromExt(batchId, resp)
      return resp
    },
    patchBatch(batchId: string, fn: (b: BatchRecord) => BatchRecord) {
      this.batches = this.batches.map((b) => (b.batchId === batchId ? fn(b) : b))
    },
    patchChild(batchId: string, itemId: string, patch: Partial<BatchChild>) {
      this.patchBatch(batchId, (b) => ({
        ...b,
        children: b.children.map((c) => (c.itemId === itemId ? { ...c, ...patch } : c)),
      }))
    },
    patchChildrenFromExt(batchId: string, ext: ChildStatusExt) {
      if (!ext.items?.length) return
      this.patchBatch(batchId, (b) => ({
        ...b,
        children: b.children.map((c) => {
          const s = ext.items?.find((x) => (x.itemId ?? x.mappingId) === (c.itemId ?? c.mappingId))
          return s ? { ...c, ...s, items: c.items } : c
        }),
      }))
    },
  },
})

/** 批量弹窗内联错误处理（保留用户已填内容，§3.5.4） */
export function batchErrorText(e: unknown): ErrorState {
  return toErrorState(e)
}
