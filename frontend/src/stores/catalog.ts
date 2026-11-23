/**
 * 配置目录 store 与格式辅助（P-03 配置目录 / P-05 新建变更 共用）
 * - 格式按 DataId 后缀识别并纳管固化（MUST-15）；未知格式只读，禁发起变更（MUST-NOT-11 / CR-415-001）
 * - 敏感项默认脱敏，明文只经 SensitiveValue 组件按 itemKey+reason 查看（30 秒再脱敏）
 */
import { defineStore } from 'pinia'
import { catalogApi } from '@/api'
import type { AppItem, ConfigContent, ConfigItem, ConfigMapping, ParseError } from '@/api/types'
import { toUiError, type Tone, type UiError } from '@/stores/changes'

export const FORMAT_LABEL: Record<string, string> = {
  PROPERTIES: 'PROPERTIES',
  YAML: 'YAML',
  JSON: 'JSON',
  TEXT: 'TEXT',
}

export function formatLabel(format?: string): string {
  if (!format) return '未知格式'
  return FORMAT_LABEL[format] ?? format
}

export function formatTone(format?: string): Tone {
  switch (format) {
    case 'PROPERTIES':
      return 'amber'
    case 'YAML':
      return 'cyan'
    case 'JSON':
      return 'volt'
    default:
      return 'mute'
  }
}

/** 格式是否可编辑（MUST-NOT-11：未知格式只读并禁发起变更 CR-415-001） */
export function isFormatKnown(format?: string): boolean {
  return format === 'PROPERTIES' || format === 'YAML' || format === 'JSON' || format === 'TEXT'
}

export const FORMAT_READONLY_HINT = '该 DataId 格式不支持，只能只读浏览（CR-415-001）'

/** 结构化格式（按项编辑 items[]） */
export function isStructuredFormat(format?: string): boolean {
  return format === 'PROPERTIES' || format === 'YAML' || format === 'JSON'
}

/** 解析失败提示（MUST-15：展示行号（可得列号），浏览态只读不阻断；提交阻断 CR-422-001） */
export function parseErrorText(err: ParseError): string {
  const loc = err.column != null ? `第 ${err.line} 行第 ${err.column} 列` : `第 ${err.line} 行`
  return `配置内容解析失败（${loc}）：${err.message}`
}

/** 详情统计：配置项数 / 敏感项数 */
export function contentStats(content?: ConfigContent | null): { itemCount: number; sensitiveCount: number } {
  const items: ConfigItem[] = content?.items ?? []
  return {
    itemCount: items.length,
    sensitiveCount: items.filter((i) => i.sensitive).length,
  }
}

interface CatalogState {
  apps: AppItem[]
  appsLoading: boolean
  appsError: UiError | null
  selectedAppId: string
  configs: ConfigMapping[]
  configsLoading: boolean
  configsError: UiError | null
  /** mappingId → 详情（items/统计用；敏感值为脱敏占位） */
  contents: Record<string, ConfigContent>
  detail: ConfigContent | null
  detailLoading: boolean
  detailError: UiError | null
  /** 表格多选 mappingId */
  selectedIds: string[]
}

export const useCatalogStore = defineStore('catalog', {
  state: (): CatalogState => ({
    apps: [],
    appsLoading: false,
    appsError: null,
    selectedAppId: '',
    configs: [],
    configsLoading: false,
    configsError: null,
    contents: {},
    detail: null,
    detailLoading: false,
    detailError: null,
    selectedIds: [],
  }),
  getters: {
    selectedApp(state): AppItem | null {
      return state.apps.find((a) => a.appId === state.selectedAppId) ?? null
    },
  },
  actions: {
    async fetchApps() {
      this.appsLoading = true
      this.appsError = null
      try {
        const res = await catalogApi.listApps()
        this.apps = Array.isArray(res) ? res : []
      } catch (e) {
        this.apps = []
        this.appsError = toUiError(e)
      } finally {
        this.appsLoading = false
      }
    },
    async selectApp(appId: string) {
      this.selectedAppId = appId
      this.selectedIds = []
      this.detail = null
      if (!appId) {
        this.configs = []
        return
      }
      this.configsLoading = true
      this.configsError = null
      try {
        const res = await catalogApi.listAppConfigs(appId)
        this.configs = Array.isArray(res) ? res : []
        // 并行预取详情用于「配置项数 / 敏感项数」列（失败不阻断列表）
        await Promise.all(
          this.configs.map((c) =>
            catalogApi
              .getConfig(c.mappingId, { silent: true })
              .then((content) => {
                this.contents[c.mappingId] = content
              })
              .catch(() => undefined),
          ),
        )
      } catch (e) {
        this.configs = []
        this.configsError = toUiError(e)
      } finally {
        this.configsLoading = false
      }
    },
    async openDetail(mappingId: string) {
      this.detailLoading = true
      this.detailError = null
      try {
        this.detail = await catalogApi.getConfig(mappingId, { silent: true })
        this.contents[mappingId] = this.detail
      } catch (e) {
        this.detail = null
        this.detailError = toUiError(e)
      } finally {
        this.detailLoading = false
      }
    },
    closeDetail() {
      this.detail = null
      this.detailError = null
    },
    setSelection(ids: string[]) {
      this.selectedIds = ids
    },
  },
})
