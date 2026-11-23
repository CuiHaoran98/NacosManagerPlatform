<script setup lang="ts">
/**
 * 批量变更（P-11，契约 #29 新建 / #30 草稿编辑值 / #31 提交 / #32 重试失败项）
 * 父单 + DataId 子项：每个 DataId 一个子草稿，同 DataId 多 key 合并；
 * 未填任何新值阻断（CR-400-001）；上限 50（BAT-413-001）；同 DataId 冲突整批拒绝（CR-409-001）。
 * 草稿可“编辑值”（乐观锁 version，SYS-409-001 提交后不可改）；敏感项重新输入不回显旧明文。
 * 部分成功：成功项标“不重发”，失败项可单独重试（#32 只重试失败子项）。
 */
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import 'element-plus/es/components/message/style/css'
import PageShell from '@/components/PageShell.vue'
import EmptyState from '@/components/EmptyState.vue'
import { useAppStore } from '@/stores/app'
import { useAuthStore } from '@/stores/auth'
import {
  useBatchesStore,
  batchErrorText,
  type BatchChild,
  type BatchRecord,
} from '@/stores/batches'
import { toErrorState, type ErrorState } from '@/stores/schedules'
import type { ChangeItemInput } from '@/api/types'

const store = useBatchesStore()
const auth = useAuthStore()
const app = useAppStore()

const canCreate = computed(() => auth.can('EDIT'))
const canSubmit = computed(() => auth.can('SUBMIT'))
const canEdit = computed(() => auth.can('EDIT'))
const canRetry = computed(() => auth.can('PUBLISH'))

const MAX_BATCH = 50

/** 错误文案按 code 分支（§4.2），不解析 message */
const CODE_COPY: Record<string, string> = {
  'CR-400-001': '参数缺失或格式错误，请检查必填项与值格式',
  'CR-409-001': '该配置已有进行中变更单，整批已阻断',
  'CR-415-001': '该 DataId 格式不支持，只能只读浏览',
  'CR-422-001': '配置内容解析失败，请修正后重新提交',
  'BAT-413-001': '批量数量超过上限（50），请拆分后提交',
  'SYS-409-001': '乐观锁冲突或已提交，子项内容不变',
  'SYS-500-001': '请求失败，请稍后重试',
}

function codeText(e: ErrorState): string {
  let t = CODE_COPY[e.code] ?? e.message
  if (e.code === 'CR-422-001') {
    const line = (e as ErrorState & { details?: { line?: number } }).details?.line
    if (line) t = `配置内容解析失败（第 ${line} 行），请修正后重新提交`
  }
  if (e.code === 'CR-409-001') {
    const changeNo = (e as ErrorState & { details?: { changeNo?: string } }).details?.changeNo
    if (changeNo) t = `该配置已有进行中变更单（${changeNo}），请处理后再发起`
  }
  return t
}

/* ---------- 状态展示（状态值只用后端返回的） ---------- */

const BATCH_LABEL: Record<string, string> = {
  DRAFT: '草稿 · 待提交',
  CREATED: '草稿 · 待提交',
  SUBMITTED: '审批中 · 等待通过',
  PENDING_APPROVAL: '审批中 · 等待通过',
  APPROVED: '审批中 · 等待通过',
  RUNNING: '发布中 · 逐个写入',
  PARTIAL_SUCCESS: '部分成功',
  PARTIALLY_SUCCEEDED: '部分成功',
  SUCCESS: '全部成功',
  SUCCEEDED: '全部成功',
  COMPLETED: '全部成功',
  RELEASED: '全部成功',
  FAILED: '失败',
}

const CHILD_OK = ['SUCCESS', 'SUCCEEDED', 'RELEASED', 'EXECUTED', 'VERIFIED']
const CHILD_FAIL = ['FAILED', 'ERROR', 'REJECTED']

function batchLabel(b: BatchRecord): string {
  const base = BATCH_LABEL[b.status] ?? b.status
  const failed = b.children.filter((c) => isFailed(c)).length
  if (b.status === 'PARTIAL_SUCCESS' || b.status === 'PARTIALLY_SUCCEEDED') {
    return `部分成功 · ${failed} 项失败`
  }
  return base
}

function batchType(b: BatchRecord): 'info' | 'warning' | 'success' | 'danger' {
  if (isDraft(b)) return 'info'
  if (b.status === 'SUCCESS' || b.status === 'SUCCEEDED' || b.status === 'COMPLETED' || b.status === 'RELEASED')
    return 'success'
  if (b.status === 'FAILED') return 'danger'
  return 'warning'
}

function isDraft(b: BatchRecord): boolean {
  return b.status === 'DRAFT' || b.status === 'CREATED'
}

function isOk(c: unknown): boolean {
  return CHILD_OK.includes((c as BatchChild).status)
}

function isFailed(c: unknown): boolean {
  const cc = c as BatchChild
  return !!cc.error || CHILD_FAIL.includes(cc.status)
}

function childLabel(c: unknown): string {
  const cc = c as BatchChild
  if (isOk(cc)) return '成功 · 不重发'
  if (isFailed(cc)) return '失败'
  if (cc.status === 'DRAFT') return '待提交'
  return cc.status
}

function childType(c: unknown): 'info' | 'warning' | 'success' | 'danger' {
  const cc = c as BatchChild
  if (isOk(cc)) return 'success'
  if (isFailed(cc)) return 'danger'
  return 'warning'
}

function okCount(b: BatchRecord): number {
  return b.children.filter((c) => isOk(c)).length
}

/* ---------- 新建批量弹窗（逐项填值） ---------- */

interface KeyRow {
  key: string
  value: string
  valueType: ChangeItemInput['valueType']
}
interface ItemRow {
  mappingId: string
  dataId: string
  sensitive: boolean
  keys: KeyRow[]
}

const createVisible = ref(false)
const createSubmitting = ref(false)
const createError = ref<ErrorState | null>(null)
const createForm = reactive<{ title: string; reason: string; rows: ItemRow[] }>({
  title: '',
  reason: '',
  rows: [],
})

function blankKey(): KeyRow {
  return { key: '', value: '', valueType: 'STRING' }
}
function blankRow(): ItemRow {
  return { mappingId: '', dataId: '', sensitive: false, keys: [blankKey()] }
}

function openCreate() {
  createError.value = null
  createForm.title = ''
  createForm.reason = ''
  const seeded = store.pendingCreate
  if (seeded?.length) {
    createForm.rows = seeded.map((p) => ({
      mappingId: p.mappingId,
      dataId: p.dataId ?? '',
      sensitive: !!p.sensitive,
      keys: p.items.length
        ? p.items.map((i) => ({
            key: i.key,
            value: i.value ?? '',
            valueType: i.valueType ?? 'STRING',
          }))
        : [blankKey()],
    }))
    store.clearPendingCreate()
  } else {
    createForm.rows = [blankRow()]
  }
  createVisible.value = true
}

function addRow() {
  createForm.rows.push(blankRow())
}
function removeRow(idx: number) {
  createForm.rows.splice(idx, 1)
}
function addKey(row: ItemRow) {
  row.keys.push(blankKey())
}
function removeKey(row: ItemRow, idx: number) {
  row.keys.splice(idx, 1)
}

async function submitCreate() {
  createError.value = null
  // 前端预检：未填任何新值 / 必填缺失 / 超限（服务端仍会校验，这里先行阻断并保留内容）
  if (!createForm.title.trim()) {
    createError.value = { code: 'CR-400-001', message: '请填写批量标题', traceId: '' }
    return
  }
  if (!createForm.rows.length) {
    createError.value = { code: 'CR-400-001', message: '未填任何新值，请至少添加一个 DataId 子项', traceId: '' }
    return
  }
  if (createForm.rows.length > MAX_BATCH) {
    createError.value = { code: 'BAT-413-001', message: CODE_COPY['BAT-413-001'], traceId: '' }
    return
  }
  for (const r of createForm.rows) {
    if (!r.mappingId.trim()) {
      createError.value = { code: 'CR-400-001', message: '请填写每个子项的 mappingId', traceId: '' }
      return
    }
    const filled = r.keys.filter((k) => k.key.trim() && k.value.trim())
    if (!filled.length) {
      createError.value = {
        code: 'CR-400-001',
        message: `未填任何新值（${r.dataId || r.mappingId}）`,
        traceId: '',
      }
      return
    }
    if (filled.length !== r.keys.length) {
      createError.value = {
        code: 'CR-400-001',
        message: `存在未填值的配置项（${r.dataId || r.mappingId}），请补全或删除空行`,
        traceId: '',
      }
      return
    }
  }
  createSubmitting.value = true
  try {
    const record = await store.createBatch({
      title: createForm.title.trim(),
      reason: createForm.reason.trim(),
      items: createForm.rows.map((r) => ({
        mappingId: r.mappingId.trim(),
        dataId: r.dataId.trim() || undefined,
        sensitive: r.sensitive,
        items: r.keys.map((k) => ({
          key: k.key.trim(),
          value: k.value,
          valueType: k.valueType,
        })),
      })),
    })
    ElMessage.success(`批量已创建：${record.batchId} · ${record.childCount} 个 DataId 子草稿`)
    createVisible.value = false
  } catch (e) {
    createError.value = batchErrorText(e) // 保留已填内容（§3.5.4）
  } finally {
    createSubmitting.value = false
  }
}

/* ---------- 草稿编辑值弹窗（#30 PUT /batches/{batchId}/items/{itemId}） ---------- */

const editVisible = ref(false)
const editSubmitting = ref(false)
const editError = ref<ErrorState | null>(null)
const editForm = reactive<{
  batchId: string
  itemId: string
  dataId: string
  version: number
  sensitive: boolean
  keys: KeyRow[]
}>({ batchId: '', itemId: '', dataId: '', version: 1, sensitive: false, keys: [] })

function openEdit(b: BatchRecord, c: unknown) {
  const row = c as BatchChild
  editError.value = null
  editForm.batchId = b.batchId
  editForm.itemId = row.itemId ?? ''
  editForm.dataId = row.dataId ?? row.mappingId
  editForm.version = row.version
  editForm.sensitive = !!row.sensitive
  editForm.keys = row.items.map((i) => ({
    key: i.key,
    value: i.value ?? '', // 敏感项不回显旧明文，重新输入
    valueType: i.valueType ?? 'STRING',
  }))
  if (!editForm.keys.length) editForm.keys = [blankKey()]
  editVisible.value = true
}

async function submitEdit() {
  editError.value = null
  const filled = editForm.keys.filter((k) => k.key.trim() && k.value.trim())
  if (!filled.length) {
    editError.value = { code: 'CR-400-001', message: '未填任何新值', traceId: '' }
    return
  }
  if (filled.length !== editForm.keys.length) {
    editError.value = { code: 'CR-400-001', message: '存在未填值的配置项，请补全或删除空行', traceId: '' }
    return
  }
  editSubmitting.value = true
  try {
    const resp = await store.updateItem(editForm.batchId, editForm.itemId, {
      version: editForm.version,
      items: editForm.keys.map((k) => ({
        key: k.key.trim(),
        value: k.value,
        valueType: k.valueType,
      })),
    })
    ElMessage.success(`已更新子项：版本 ${resp.version} · 共 ${resp.itemCount} 个配置项，Diff 已重算`)
    editVisible.value = false
  } catch (e) {
    editError.value = toErrorState(e) // SYS-409-001：子项内容不变
  } finally {
    editSubmitting.value = false
  }
}

/* ---------- 提交审批（#31） ---------- */

const submittingId = ref('')

async function onSubmitBatch(b: BatchRecord) {
  submittingId.value = b.batchId
  try {
    await store.submit(b.batchId)
    ElMessage.success('父单已提交审批：通过后按 DataId 逐个发布，成功项不重发')
  } catch (e) {
    const err = toErrorState(e)
    ElMessage.error(codeText(err))
  } finally {
    submittingId.value = ''
  }
}

/* ---------- 重试失败子项弹窗（#32） ---------- */

const retryVisible = ref(false)
const retrySubmitting = ref(false)
const retryBatchId = ref('')
const retryError = ref<ErrorState | null>(null)

const retryTarget = computed(() => store.getBatch(retryBatchId.value))
const retryFailed = computed(() => (retryTarget.value?.children ?? []).filter((c) => isFailed(c)))

/** 模板内按批次取失败子项 */
function failedList(b: BatchRecord): BatchChild[] {
  return b.children.filter((c) => isFailed(c))
}

function openRetry(b: BatchRecord) {
  retryError.value = null
  retryBatchId.value = b.batchId
  retryVisible.value = true
}

async function submitRetry() {
  retryError.value = null
  retrySubmitting.value = true
  try {
    const resp = await store.retry(retryBatchId.value)
    ElMessage.success(`已重试 ${resp.retried} 个失败子项（已成功子项不重发）`)
    retryVisible.value = false
    // 终态轮询细节交由发布页；这里刷新一次观测状态
  } catch (e) {
    retryError.value = toErrorState(e)
  } finally {
    retrySubmitting.value = false
  }
}

onMounted(() => {
  // 从配置目录多选发起的待填值子项（P-03 → P-11 交接）
  if (store.pendingCreate?.length) openCreate()
})
</script>

<template>
  <PageShell
    title="批量变更"
    description="父单 + DataId 子项。新建批量时逐项填写新值；草稿阶段可点“编辑值”修改。发布按 DataId 逐个写入并读回。"
    :loading="store.loading"
    :error="store.error"
    @retry="store.error = null"
  >
    <template #actions>
      <el-button v-if="canCreate" type="primary" :disabled="app.readOnly" @click="openCreate">
        + 新建批量
      </el-button>
    </template>

    <template v-if="store.batches.length">
      <el-card v-for="b in store.batches" :key="b.batchId" class="batch-card" shadow="never">
        <div class="batch-head">
          <div>
            <div class="batch-title">
              <b>{{ b.title }}</b>
              <el-tag :type="batchType(b)" effect="plain" round size="small">
                {{ batchLabel(b) }}
              </el-tag>
            </div>
            <div class="ncg-sub mono">
              {{ b.batchId }}
              <template v-if="b.reason"> · {{ b.reason }}</template>
            </div>
          </div>
          <div class="batch-counter">
            <b :class="okCount(b) === b.children.length ? 'ok' : 'partial'">
              {{ okCount(b) }} / {{ b.children.length || b.childCount }}
            </b>
            <span class="ncg-sub">成功 / 子项</span>
          </div>
        </div>

        <el-progress
          :percentage="b.children.length ? Math.round((okCount(b) / b.children.length) * 100) : 0"
          :stroke-width="6"
          :show-text="false"
          :status="okCount(b) === b.children.length && b.children.length ? 'success' : undefined"
        />

        <el-alert
          v-if="b.status === 'SUBMITTED' || b.status === 'PENDING_APPROVAL' || b.status === 'APPROVED'"
          type="info"
          :closable="false"
          class="ncg-banner"
        >
          <template #title>
            父单审批中。通过后按 DataId 逐个发布；成功项不重发，失败项可单独重试。
          </template>
        </el-alert>

        <el-table :data="b.children" size="small" class="child-table">
          <el-table-column label="子项" min-width="200">
            <template #default="{ row }">
              <div class="mono ncg-sub">{{ row.itemId || row.mappingId }}</div>
              <div class="mono">{{ row.dataId || row.mappingId }}</div>
            </template>
          </el-table-column>
          <el-table-column label="变更内容" min-width="220">
            <template #default="{ row }">
              <div v-for="it in row.items" :key="it.key" class="mono change-line">
                <template v-if="row.sensitive">{{ it.key }}: ••••••（已更新）</template>
                <template v-else>{{ it.key }}: → {{ it.value }}</template>
              </div>
            </template>
          </el-table-column>
          <el-table-column label="状态" width="130">
            <template #default="{ row }">
              <el-tag :type="childType(row)" effect="plain" round size="small">
                {{ childLabel(row) }}
              </el-tag>
              <div v-if="isFailed(row) && row.error" class="ncg-sub">
                <span class="mono">{{ row.error.code }}</span> {{ codeText(row.error) }}
              </div>
            </template>
          </el-table-column>
          <el-table-column label="操作" width="120" fixed="right">
            <template #default="{ row }">
              <el-button
                v-if="canEdit && isDraft(b) && row.itemId"
                link
                type="primary"
                :disabled="app.readOnly"
                @click="openEdit(b, row)"
              >
                编辑值
              </el-button>
              <span v-else-if="isDraft(b)" class="ncg-sub" title="创建响应未返回子项 itemId [TBD]，联调补齐后可编辑">
                不可编辑
              </span>
              <span v-else class="ncg-sub">—</span>
            </template>
          </el-table-column>
        </el-table>

        <div v-if="isDraft(b)" class="batch-foot">
          <el-button
            v-if="canSubmit"
            type="primary"
            :loading="submittingId === b.batchId"
            :disabled="app.readOnly"
            @click="onSubmitBatch(b)"
          >
            提交审批
          </el-button>
          <span class="ncg-sub">父单统一审批；发布仍按 DataId 逐个写入并读回。</span>
        </div>
        <div v-else-if="failedList(b).length && canRetry" class="batch-foot">
          <el-button type="warning" :disabled="app.readOnly" @click="openRetry(b)">
            重试失败子项（{{ failedList(b).length }}）
          </el-button>
          <span class="ncg-sub">只重试失败项；已成功子项不会重复写入。</span>
        </div>
      </el-card>
    </template>

    <EmptyState
      v-else
      reason="暂无批量任务"
      next-step="在配置目录多选 DataId 发起批量，或点击“新建批量”逐项填写新值。"
      action-text="新建批量"
      @action="openCreate"
    />
  </PageShell>

  <!-- 新建批量：逐项填值 -->
  <el-dialog v-model="createVisible" title="新建批量变更" width="680px" destroy-on-close>
    <el-form label-position="top">
      <el-form-item label="批量标题" required>
        <el-input v-model="createForm.title" placeholder="例如：大促前批量调整支付参数" maxlength="120" />
      </el-form-item>
      <el-form-item label="批量原因">
        <el-input
          v-model="createForm.reason"
          type="textarea"
          :rows="2"
          placeholder="例如：大促容量与日志级别统一调整"
          maxlength="500"
        />
      </el-form-item>
    </el-form>

    <div class="rows-head">
      <b>配置子项（{{ createForm.rows.length }} / {{ MAX_BATCH }}）</b>
      <el-button link type="primary" @click="addRow">+ 添加 DataId</el-button>
    </div>
    <div class="ncg-hint">
      每个 DataId 一个子草稿，同 DataId 多 key 合并为一个子项；未填任何新值将阻断提交（CR-400-001）；超过 50 个 DataId 将被拒绝（BAT-413-001）。
    </div>

    <el-card v-for="(row, ri) in createForm.rows" :key="ri" shadow="never" class="row-card">
      <div class="row-head">
        <el-input v-model="row.mappingId" placeholder="mappingId（必填）" class="mono row-mapping" />
        <el-input v-model="row.dataId" placeholder="DataId（展示用）" class="mono row-dataid" />
        <el-checkbox v-model="row.sensitive">含敏感项</el-checkbox>
        <el-button link type="danger" @click="removeRow(ri)">删除</el-button>
      </div>
      <div v-for="(k, ki) in row.keys" :key="ki" class="key-row">
        <el-input v-model="k.key" placeholder="key" class="mono key-name" />
        <el-input
          v-model="k.value"
          :placeholder="row.sensitive ? '输入新值（当前脱敏）' : '新值'"
          class="key-value"
        />
        <el-select v-model="k.valueType" class="key-type">
          <el-option label="STRING" value="STRING" />
          <el-option label="NUMBER" value="NUMBER" />
          <el-option label="BOOLEAN" value="BOOLEAN" />
          <el-option label="JSON" value="JSON" />
        </el-select>
        <el-button link type="danger" @click="removeKey(row, ki)">删</el-button>
      </div>
      <el-button link type="primary" size="small" @click="addKey(row)">+ 添加配置项</el-button>
    </el-card>

    <div v-if="createError" class="ncg-form-error" role="alert">
      <div><b class="mono">{{ createError.code }}</b> {{ codeText(createError) }}</div>
      <div v-if="createError.traceId" class="ncg-sub mono">TraceId {{ createError.traceId }}</div>
    </div>

    <template #footer>
      <el-button @click="createVisible = false">取消</el-button>
      <el-button type="primary" :loading="createSubmitting" @click="submitCreate">
        创建批量（逐项填值）
      </el-button>
    </template>
  </el-dialog>

  <!-- 草稿编辑值 -->
  <el-dialog v-model="editVisible" title="编辑值（草稿）" width="560px" destroy-on-close>
    <el-alert type="info" :closable="false" class="ncg-banner">
      <template #title>
        修改后重算 content 与 Diff；敏感项必须重新输入新值，不回显旧明文。提交后子项只读（SYS-409-001）。
      </template>
    </el-alert>
    <div class="ncg-kv">
      <div><span>子项</span><b class="mono">{{ editForm.dataId }}</b></div>
      <div><span>乐观锁版本</span><b class="mono">v{{ editForm.version }}</b></div>
    </div>
    <div v-for="(k, ki) in editForm.keys" :key="ki" class="key-row">
      <el-input v-model="k.key" placeholder="key" class="mono key-name" disabled />
      <el-input
        v-model="k.value"
        :placeholder="editForm.sensitive ? '输入新值（当前脱敏）' : '新值'"
        class="key-value"
      />
      <el-select v-model="k.valueType" class="key-type">
        <el-option label="STRING" value="STRING" />
        <el-option label="NUMBER" value="NUMBER" />
        <el-option label="BOOLEAN" value="BOOLEAN" />
        <el-option label="JSON" value="JSON" />
      </el-select>
    </div>
    <div v-if="editError" class="ncg-form-error" role="alert">
      <div><b class="mono">{{ editError.code }}</b> {{ codeText(editError) }}</div>
      <div v-if="editError.traceId" class="ncg-sub mono">TraceId {{ editError.traceId }}</div>
    </div>
    <template #footer>
      <el-button @click="editVisible = false">取消</el-button>
      <el-button type="primary" :loading="editSubmitting" @click="submitEdit">保存并重算 Diff</el-button>
    </template>
  </el-dialog>

  <!-- 重试失败子项 -->
  <el-dialog v-model="retryVisible" title="重试失败子项" width="560px">
    <el-alert type="warning" :closable="false" class="ncg-banner">
      <template #title>只重试失败项，已成功的子项不会重复写入。</template>
    </el-alert>
    <el-table :data="retryFailed" size="small">
      <el-table-column label="子项 / 配置" min-width="180">
        <template #default="{ row }">
          <div class="mono">{{ row.dataId || row.mappingId }}</div>
          <div class="ncg-sub mono">{{ row.items.map((i: ChangeItemInput) => i.key).join('、') }}</div>
        </template>
      </el-table-column>
      <el-table-column label="失败原因" min-width="200">
        <template #default="{ row }">
          <template v-if="row.error">
            <span class="mono">{{ row.error.code }}</span> {{ codeText(row.error) }}
          </template>
          <span v-else class="ncg-sub">—</span>
        </template>
      </el-table-column>
      <el-table-column label="已重试" width="80">
        <template #default="{ row }">
          <span class="mono">{{ row.retries ?? 0 }}</span>
        </template>
      </el-table-column>
    </el-table>
    <div v-if="retryError" class="ncg-form-error" role="alert">
      <div><b class="mono">{{ retryError.code }}</b> {{ codeText(retryError) }}</div>
      <div v-if="retryError.traceId" class="ncg-sub mono">TraceId {{ retryError.traceId }}</div>
    </div>
    <template #footer>
      <el-button @click="retryVisible = false">取消</el-button>
      <el-button type="primary" :loading="retrySubmitting" @click="submitRetry">确认重试</el-button>
    </template>
  </el-dialog>
</template>

<style scoped>
.batch-card {
  margin-bottom: var(--ncg-space-3);
  border: 1px solid var(--ncg-border);
  border-radius: 12px;
}
.batch-head {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
  gap: var(--ncg-space-2);
  margin-bottom: var(--ncg-space-2);
}
.batch-title {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: var(--ncg-fs-base);
}
.batch-counter {
  text-align: right;
  font-size: var(--ncg-fs-xs);
}
.batch-counter .ok {
  color: var(--ncg-success, var(--ncg-accent));
}
.batch-counter .partial {
  color: var(--ncg-warning, var(--ncg-amber));
}
.child-table {
  margin-top: var(--ncg-space-2);
}
.batch-foot {
  display: flex;
  align-items: center;
  gap: var(--ncg-space-2);
  margin-top: var(--ncg-space-2);
  padding-top: var(--ncg-space-2);
  border-top: 1px dashed var(--ncg-border);
}
.change-line {
  font-size: var(--ncg-fs-xs);
}
.ncg-sub {
  font-size: var(--ncg-fs-xs);
  color: var(--ncg-text-muted);
  margin-top: 2px;
}
.ncg-hint {
  font-size: var(--ncg-fs-xs);
  color: var(--ncg-text-muted);
  line-height: 1.6;
  margin-bottom: var(--ncg-space-2);
}
.ncg-banner {
  margin-bottom: var(--ncg-space-2);
}
.rows-head {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 4px;
  font-size: var(--ncg-fs-sm);
}
.row-card {
  margin-bottom: var(--ncg-space-2);
  border: 1px solid var(--ncg-border);
  border-radius: var(--ncg-radius-md);
}
.row-head {
  display: flex;
  gap: 8px;
  align-items: center;
  margin-bottom: 8px;
}
.row-mapping {
  flex: 2;
}
.row-dataid {
  flex: 2;
}
.key-row {
  display: flex;
  gap: 8px;
  align-items: center;
  margin-bottom: 6px;
}
.key-name {
  flex: 2;
}
.key-value {
  flex: 3;
}
.key-type {
  width: 110px;
}
.ncg-form-error {
  margin-top: var(--ncg-space-2);
  padding: var(--ncg-space-2);
  border: 1px solid var(--ncg-danger);
  border-radius: var(--ncg-radius-md);
  font-size: var(--ncg-fs-xs);
  color: var(--ncg-danger);
}
.ncg-kv {
  display: grid;
  gap: 6px;
  margin-bottom: var(--ncg-space-2);
  font-size: var(--ncg-fs-sm);
}
.ncg-kv span {
  display: inline-block;
  width: 90px;
  color: var(--ncg-text-muted);
}
</style>
