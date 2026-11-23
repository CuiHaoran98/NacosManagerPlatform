<script setup lang="ts">
/**
 * ChangeCreateDialog —— 新建变更 / 发起变更（P-05，契约 §2.1(12)、§3.4）
 * - 载入当前值（GET /configs/{mappingId}）；items[] 与 content 互斥：
 *   结构化格式（PROPERTIES/YAML/JSON）按项填值；CREATE / TEXT / 整文件替换用 content
 * - 提交前本地预览 Diff；敏感项不回显旧明文（留空=不变）
 * - 错误按 code 分支（§4.2）：CR-409-001 顶部提示+阻断提交但草稿可存；
 *   CR-422-001 行内行号高亮+保留草稿；CR-415-001 只读禁发起；CR-400-001 互斥校验
 */
import { computed, reactive, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import 'element-plus/es/components/message/style/css'
import 'element-plus/es/components/alert/style/css'
import 'element-plus/es/components/radio/style/css'
import 'element-plus/es/components/select/style/css'
import 'element-plus/es/components/option/style/css'
import 'element-plus/es/components/input/style/css'
import 'element-plus/es/components/form/style/css'
import 'element-plus/es/components/form-item/style/css'
import 'element-plus/es/components/button/style/css'
import 'element-plus/es/components/empty/style/css'
import { changeApi, catalogApi } from '@/api'
import type { ApiError } from '@/api/http'
import type { ConfigContent, ParseError } from '@/api/types'
import { useAppStore } from '@/stores/app'
import { useCatalogStore, isFormatKnown, isStructuredFormat, FORMAT_READONLY_HINT, formatLabel } from '@/stores/catalog'
import {
  buildItemDiffRows,
  buildContentDiffRows,
  reasonInvalid,
  REASON_MIN,
  type DiffRowInput,
} from '@/stores/changes'
import DiffView from '@/components/DiffView.vue'
import ChangeValueEditor from './ChangeValueEditor.vue'

const props = withDefaults(
  defineProps<{
    visible: boolean
    /** 从目录页带入的映射（appId/envId/mappingId） */
    preset?: { appId: string; envId: string; mappingId: string; dataId?: string } | null
  }>(),
  { preset: null },
)

const emit = defineEmits<{
  'update:visible': [v: boolean]
  'created': [changeNo: string]
}>()

const catalog = useCatalogStore()
const appStore = useAppStore()

/* ── 目标选择（无 preset 时：应用 → DataId） ── */
const selectedAppId = ref(props.preset?.appId ?? '')
const selectedMappingId = ref(props.preset?.mappingId ?? '')
const mappingOptions = ref<{ mappingId: string; dataId: string; format: string }[]>([])
const targetLoading = ref(false)

async function loadMappings(appId: string) {
  if (!appId) {
    mappingOptions.value = []
    return
  }
  targetLoading.value = true
  try {
    const res = await catalogApi.listAppConfigs(appId)
    mappingOptions.value = (Array.isArray(res) ? res : []).map((m) => ({
      mappingId: m.mappingId,
      dataId: m.dataId,
      format: m.format,
    }))
  } catch {
    mappingOptions.value = []
  } finally {
    targetLoading.value = false
  }
}

/* ── 当前值载入 ── */
const current = ref<ConfigContent | null>(null)
const loadError = ref<{ code?: string; message?: string; traceId?: string } | null>(null)
const loading = ref(false)

async function loadCurrent() {
  const mappingId = selectedMappingId.value
  current.value = null
  loadError.value = null
  if (!mappingId) return
  loading.value = true
  try {
    current.value = await catalogApi.getConfig(mappingId)
    // 载入后重置编辑态：TEXT/整文件预填当前内容；按项默认全部不变
    if (editMode.value === 'content') {
      form.content = changeType.value === 'UPDATE' ? (current.value.content ?? '') : ''
    } else {
      form.values = {}
    }
  } catch (e) {
    const err = e as ApiError
    loadError.value = { code: err?.code, message: err?.message, traceId: err?.traceId }
  } finally {
    loading.value = false
  }
}

/* ── 编辑表单 ── */
const changeType = ref<'UPDATE' | 'CREATE'>('UPDATE')
const editMode = ref<'items' | 'content'>('items')
const form = reactive<{ values: Record<string, string>; content: string; title: string; reason: string }>({
  values: {},
  content: '',
  title: '',
  reason: '',
})

const format = computed(() => current.value?.format)
const structured = computed(() => isStructuredFormat(format.value))
const readonlyFormat = computed(() => !!current.value && !isFormatKnown(format.value))

watch(
  () => format.value,
  (f) => {
    // 格式决定编辑器：结构化默认按项；TEXT/未知 → content
    editMode.value = isStructuredFormat(f) && changeType.value === 'UPDATE' ? 'items' : 'content'
  },
)

watch(changeType, (t) => {
  if (t === 'CREATE') {
    editMode.value = 'content'
    form.content = ''
    form.values = {}
  } else {
    editMode.value = structured.value ? 'items' : 'content'
    if (editMode.value === 'content') form.content = current.value?.content ?? ''
    else form.values = {}
  }
})

function switchMode(mode: 'items' | 'content') {
  editMode.value = mode
  if (mode === 'content') form.content = current.value?.content ?? ''
  else form.values = {}
}

/* ── Diff 预览（提交前） ── */
const diffRows = computed<DiffRowInput[]>(() => {
  if (!current.value && changeType.value !== 'CREATE') return []
  if (changeType.value === 'CREATE') {
    return buildContentDiffRows(undefined, form.content, { created: true, key: 'content（新建文件）' })
  }
  if (editMode.value === 'content') {
    return buildContentDiffRows(current.value?.content, form.content, {
      key: format.value === 'TEXT' ? 'content（整文件）' : 'content（整文件替换）',
    })
  }
  const next = Object.entries(form.values).map(([key, value]) => ({
    key,
    value,
    sensitive: current.value?.items?.find((i) => i.key === key)?.sensitive ?? false,
  }))
  return buildItemDiffRows(current.value?.items ?? [], next)
})

const reasonError = computed(() => (form.reason ? reasonInvalid(form.reason) : null))

/* ── 错误分支（§4.2） ── */
const createError = ref<{ code?: string; message?: string; traceId?: string } | null>(null)
/** CR-409-001：阻断提交但草稿可存 */
const blocked409 = ref<{ changeNo?: string } | null>(null)
/** CR-422-001：行内行号高亮，保留草稿 */
const localParseError = ref<ParseError | null>(null)
const submitting = ref(false)

function detailField(err: unknown, field: string): string | undefined {
  const d = (err as { details?: unknown } | null)?.details
  if (d && typeof d === 'object') {
    const v = (d as Record<string, unknown>)[field]
    return v == null ? undefined : String(v)
  }
  return undefined
}

function detailParseError(err: unknown): ParseError | null {
  const d = (err as { details?: unknown } | null)?.details
  if (d && typeof d === 'object' && 'line' in d) {
    const rec = d as Record<string, unknown>
    return {
      line: Number(rec.line) || 0,
      column: rec.column == null ? undefined : Number(rec.column),
      message: String(rec.message ?? '解析失败'),
    }
  }
  return null
}

async function submit() {
  createError.value = null
  if (!selectedMappingId.value) {
    createError.value = { message: '请选择目标配置（DataId）' }
    return
  }
  if (readonlyFormat.value) {
    createError.value = { code: 'CR-415-001', message: FORMAT_READONLY_HINT }
    return
  }
  if (!form.title.trim()) {
    createError.value = { message: '请填写变更标题' }
    return
  }
  const reasonErr = reasonInvalid(form.reason)
  if (reasonErr) {
    createError.value = { code: 'CR-400-001', message: reasonErr }
    return
  }
  if (changeType.value === 'UPDATE' && editMode.value === 'items' && Object.keys(form.values).length === 0) {
    createError.value = { message: '请至少修改一项配置（新值留空视为不变）' }
    return
  }
  if (editMode.value === 'content' && !form.content.trim()) {
    createError.value = { message: '请填写文件内容' }
    return
  }

  submitting.value = true
  try {
    const payload: Parameters<typeof changeApi.createChange>[0] = {
      appId: selectedAppId.value,
      envId: props.preset?.envId ?? catalog.selectedApp?.envIds?.[0] ?? appStore.currentEnvId,
      mappingId: selectedMappingId.value,
      changeType: changeType.value,
      title: form.title.trim(),
      reason: form.reason.trim(),
      // 计划发布时间留空 = 创建后立即进入流转（新建变更交互不含定时输入，定时在「定时任务」页配置）
      plannedAt: '',
    }
    if (changeType.value === 'UPDATE') payload.baselineMd5 = current.value?.md5
    if (editMode.value === 'items') {
      payload.items = Object.entries(form.values).map(([key, value]) => ({ key, value }))
    } else {
      payload.content = form.content
    }
    const res = await changeApi.createChange(payload)
    ElMessage.success(`变更草稿已创建：${res.changeNo}`)
    emit('created', res.changeNo)
    close()
  } catch (e) {
    const err = e as ApiError
    if (err?.code === 'CR-409-001') {
      // 已有进行中变更单：顶部提示 + 阻断提交，草稿保留
      blocked409.value = { changeNo: detailField(e, 'changeNo') }
    } else if (err?.code === 'CR-422-001') {
      localParseError.value = detailParseError(e) ?? { line: 0, message: err.message ?? '解析失败' }
    }
    createError.value = { code: err?.code, message: err?.message, traceId: err?.traceId }
  } finally {
    submitting.value = false
  }
}

/* ── 弹窗开关 ── */
watch(
  () => props.visible,
  async (v) => {
    if (!v) return
    createError.value = null
    blocked409.value = null
    localParseError.value = null
    if (props.preset) {
      selectedAppId.value = props.preset.appId
      selectedMappingId.value = props.preset.mappingId
    }
    if (!catalog.apps.length) await catalog.fetchApps()
    if (selectedAppId.value && !props.preset) await loadMappings(selectedAppId.value)
    await loadCurrent()
  },
)

function close() {
  emit('update:visible', false)
}
</script>

<template>
  <el-dialog
    :model-value="visible"
    title="发起变更"
    width="min(860px, 94vw)"
    top="6vh"
    destroy-on-close
    @update:model-value="close"
  >
    <div class="ncg-create">
      <el-alert type="info" :closable="false" title="一次变更 = 一个 DataId 文件">
        <template #default>
          items[] 与 content 互斥：结构化格式（PROPERTIES/YAML/JSON）按项填值；CREATE / TEXT / 整文件替换用 content
          （契约 §1.3）。敏感项留空 = 不变，不会回显旧明文。
        </template>
      </el-alert>

      <!-- CR-409-001：顶部提示 + 阻断提交 -->
      <el-alert
        v-if="blocked409"
        type="warning"
        :closable="false"
        title="该配置已有进行中变更单（CR-409-001）"
        :description="`变更单 ${blocked409.changeNo ?? ''} 正在流转，提交已阻断；当前草稿内容已保留，可先行处理该单后再提交。`"
      />

      <!-- 目标选择 -->
      <div class="ncg-create__target">
        <el-form label-position="top" :disabled="!!props.preset">
          <el-form-item v-if="!props.preset" label="应用">
            <el-select v-model="selectedAppId" filterable placeholder="选择应用" @change="loadMappings">
              <el-option v-for="a in catalog.apps" :key="a.appId" :label="a.appName" :value="a.appId" />
            </el-select>
          </el-form-item>
          <el-form-item v-if="!props.preset" label="目标配置（DataId）">
            <el-select
              v-model="selectedMappingId"
              filterable
              :loading="targetLoading"
              placeholder="选择 DataId"
              @change="loadCurrent"
            >
              <el-option
                v-for="m in mappingOptions"
                :key="m.mappingId"
                :label="`${m.dataId}（${formatLabel(m.format)}）`"
                :value="m.mappingId"
              />
            </el-select>
          </el-form-item>
        </el-form>
        <div v-if="current" class="ncg-create__meta ncg-muted">
          DataId <code class="ncg-mono">{{ current.dataId }}</code>
          · 格式 {{ formatLabel(current.format) }}
          · MD5 <code class="ncg-mono">{{ current.md5 }}</code>
          · 配置项 {{ current.items?.length ?? 0 }}
        </div>
      </div>

      <!-- 变更类型 + 编辑模式 -->
      <div v-if="current && !readonlyFormat" class="ncg-create__modes">
        <el-radio-group v-model="changeType" size="small">
          <el-radio-button value="UPDATE">修改现有文件</el-radio-button>
          <el-radio-button value="CREATE">新建文件</el-radio-button>
        </el-radio-group>
        <el-radio-group
          v-if="changeType === 'UPDATE' && structured"
          :model-value="editMode"
          size="small"
          @update:model-value="switchMode($event as 'items' | 'content')"
        >
          <el-radio-button value="items">按项编辑</el-radio-button>
          <el-radio-button value="content">整文件替换</el-radio-button>
        </el-radio-group>
      </div>

      <el-alert
        v-if="readonlyFormat"
        type="warning"
        :closable="false"
        :title="FORMAT_READONLY_HINT"
        description="未知格式只允许只读浏览，禁止发起变更（MUST-NOT-11）。"
      />

      <el-alert
        v-else-if="current?.parseError"
        type="error"
        :closable="false"
        title="当前文件解析失败，仅能整文件替换修正"
        :description="`第 ${current.parseError.line} 行：${current.parseError.message}`"
      />

      <!-- 编辑器 -->
      <ChangeValueEditor
        v-if="current && !readonlyFormat"
        :mode="editMode"
        :items="current.items ?? []"
        :values="form.values"
        :content="form.content"
        :parse-error="localParseError ?? current.parseError ?? null"
        :disabled="submitting"
        @update:values="form.values = $event"
        @update:content="form.content = $event"
      />

      <!-- 标题 + 原因 -->
      <el-form label-position="top">
        <el-form-item label="变更标题" required>
          <el-input v-model="form.title" maxlength="100" placeholder="一句话说明本次变更" />
        </el-form-item>
        <el-form-item label="变更原因" required :error="reasonError ?? ''">
          <el-input
            v-model="form.reason"
            type="textarea"
            :rows="3"
            maxlength="500"
            placeholder="不少于 10 个字；将写入审计记录"
          />
          <div class="ncg-create__reason-count ncg-muted">{{ form.reason.trim().length }} / {{ REASON_MIN }} 字起</div>
        </el-form-item>
      </el-form>

      <!-- 提交前 Diff 预览 -->
      <div class="ncg-create__diff">
        <div class="ncg-create__diff-title">提交前 Diff 预览（敏感值自动脱敏）</div>
        <DiffView v-if="diffRows.length" :rows="diffRows" mode="pair" />
        <div v-else class="ncg-muted">暂无差异：填写新值后此处实时预览。</div>
      </div>

      <!-- CR-422-001 / 其他错误 -->
      <el-alert
        v-if="createError"
        type="error"
        :closable="false"
        :title="createError.message ?? '提交失败'"
        :description="createError.code ? `错误码 ${createError.code}${createError.traceId ? ` · TraceId ${createError.traceId}` : ''}` : ''"
      />
    </div>

    <template #footer>
      <el-button @click="close">取消</el-button>
      <el-button
        type="primary"
        :loading="submitting"
        :disabled="!!blocked409 || readonlyFormat"
        @click="submit"
      >
        创建草稿
      </el-button>
    </template>
  </el-dialog>
</template>

<style scoped>
.ncg-create {
  display: flex;
  flex-direction: column;
  gap: var(--ncg-space-2);
}
.ncg-create__target {
  display: flex;
  flex-direction: column;
  gap: 4px;
}
.ncg-create__meta {
  font-size: var(--ncg-fs-xs);
}
.ncg-create__modes {
  display: flex;
  gap: var(--ncg-space-2);
  flex-wrap: wrap;
}
.ncg-create__reason-count {
  font-size: var(--ncg-fs-xs);
  margin-top: 4px;
}
.ncg-create__diff {
  border: 1px solid var(--ncg-line);
  border-radius: var(--ncg-radius-md);
  padding: var(--ncg-space-2);
}
.ncg-create__diff-title {
  font-size: var(--ncg-fs-sm);
  color: var(--ncg-text-1);
  margin-bottom: var(--ncg-space-1);
}
</style>
