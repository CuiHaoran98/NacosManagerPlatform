<script setup lang="ts">
/**
 * 跨环境同步（P-09，契约 #33 对比 / #34 生成目标环境变更单 / #35 快照详情）
 * 只读对比：同步只创建目标环境变更单，必须经目标环境审批与发布，源环境不被修改。
 * SYN-409-002 映射或格式不一致；SYN-409-001 目标环境已有进行中变更单。
 */
import { computed, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import 'element-plus/es/components/message/style/css'
import PageShell from '@/components/PageShell.vue'
import EmptyState from '@/components/EmptyState.vue'
import { useAppStore } from '@/stores/app'
import { useAuthStore } from '@/stores/auth'
import { useEnvSyncStore } from '@/stores/env-sync'
import { toErrorState, fmtTime, type ErrorState } from '@/stores/schedules'
import type { EnvSyncDiff } from '@/api/types'

const router = useRouter()
const store = useEnvSyncStore()
const auth = useAuthStore()
const app = useAppStore()

const canApply = computed(() => auth.can('EDIT'))

const ENVS = [
  { value: 'dev', label: 'DEV' },
  { value: 'staging', label: 'STAGING' },
  { value: 'prod', label: 'PROD' },
]

function envLabel(v: string): string {
  return ENVS.find((e) => e.value === v)?.label ?? v
}

/** 错误文案按 code 分支（§4.2 / §6.6），不解析 message */
const CODE_COPY: Record<string, string> = {
  'SYN-409-001': '目标环境已有进行中变更单，请处理后再生成',
  'SYN-409-002': '跨环境映射或格式不一致，无法对比 / 生成',
  'SYS-404-001': '快照不存在或已删除',
  'CR-400-001': '参数缺失或格式错误',
  'SYS-500-001': '请求失败，请稍后重试',
}

function codeText(e: ErrorState): string {
  return CODE_COPY[e.code] ?? e.message
}

/* ---------- 对比 ---------- */

const form = reactive<{ sourceEnvId: string; targetEnvId: string; appId: string; dataId: string }>({
  sourceEnvId: 'dev',
  targetEnvId: 'staging',
  appId: '',
  dataId: '',
})

async function runCompare() {
  if (!form.sourceEnvId || !form.targetEnvId) {
    ElMessage.error('请选择源环境与目标环境')
    return
  }
  if (form.sourceEnvId === form.targetEnvId) {
    ElMessage.error('源环境与目标环境不能相同')
    return
  }
  try {
    await store.compare({
      sourceEnvId: form.sourceEnvId,
      targetEnvId: form.targetEnvId,
      appId: form.appId.trim() || undefined,
      dataId: form.dataId.trim() || undefined,
    })
    ElMessage.success('对比完成，仅差异项可选')
  } catch {
    // store.error 已记录（保留表单内容）
  }
}

/* ---------- 勾选（仅差异项可选） ---------- */

function onSelectable(row: EnvSyncDiff): boolean {
  return !row.same
}

function onSelectionChange(rows: EnvSyncDiff[]) {
  store.selected = rows.map((r) => r.mappingId)
}

/* ---------- 生成目标环境变更单（源环境只读） ---------- */

const applyVisible = ref(false)
const applyError = ref<ErrorState | null>(null)
const applyForm = reactive<{ title: string; reason: string }>({ title: '', reason: '' })

function openApply() {
  if (!store.selected.length) return
  applyError.value = null
  applyForm.title = ''
  applyForm.reason = ''
  applyVisible.value = true
}

async function submitApply() {
  applyError.value = null
  try {
    const resp = await store.apply({
      title: applyForm.title.trim() || undefined,
      reason: applyForm.reason.trim() || undefined,
    })
    ElMessage.success(`已生成目标变更单：${resp.changeNos.join('、')}`)
    applyVisible.value = false
  } catch (e) {
    applyError.value = toErrorState(e) // 保留已填内容（§3.5.4）
  }
}

function goChange(changeNo: string) {
  router.push({ name: 'change-detail', params: { changeNo } })
}

/* ---------- 快照详情 ---------- */

const snapshotVisible = ref(false)

function openSnapshot() {
  snapshotVisible.value = true
}

async function reloadSnapshot() {
  if (!store.snapshot) return
  try {
    await store.loadSnapshot(store.snapshot.snapshotId)
    ElMessage.success('快照详情已刷新')
    snapshotVisible.value = false
  } catch {
    // store.error 已记录
  }
}
</script>

<template>
  <PageShell
    title="跨环境同步"
    description="只读对比 dev / staging / prod。同步只能生成目标环境变更单，必须经过目标环境审批与发布，源环境不被修改。"
    :loading="store.loading"
    :error="store.error"
    @retry="store.error = null"
  >
    <template #actions>
      <el-button v-if="store.snapshot" @click="openSnapshot">查看快照详情</el-button>
    </template>

    <!-- 对比表单：源 → 目标 -->
    <el-card shadow="never" class="compare-card">
      <div class="compare-form">
        <div class="env-flow">
          <span class="env-pill">源 {{ envLabel(form.sourceEnvId) }}</span>
          <span class="arrow">→</span>
          <span class="env-pill target">目标 {{ envLabel(form.targetEnvId) }}</span>
        </div>
        <el-select v-model="form.sourceEnvId" class="env-select" placeholder="源环境">
          <el-option v-for="e in ENVS" :key="e.value" :label="e.label" :value="e.value" />
        </el-select>
        <el-select v-model="form.targetEnvId" class="env-select" placeholder="目标环境">
          <el-option v-for="e in ENVS" :key="e.value" :label="e.label" :value="e.value" />
        </el-select>
        <el-input v-model="form.appId" placeholder="AppId（可选）" class="filter-input mono" />
        <el-input v-model="form.dataId" placeholder="DataId（可选）" class="filter-input mono" />
        <el-button type="primary" :loading="store.loading" @click="runCompare">对比</el-button>
      </div>
      <div class="ncg-hint">
        源环境只读：同步不会修改 {{ envLabel(form.sourceEnvId) }}，只会为目标环境创建待审批变更单（SYN-409-001 目标已有进行中变更单时阻断）。
      </div>
    </el-card>

    <!-- 生成成功横幅 -->
    <el-alert
      v-if="store.createdChangeNos.length"
      type="success"
      :closable="false"
      class="ncg-banner"
    >
      <template #title>
        <span>
          已生成目标变更单
          <b v-for="cn in store.createdChangeNos" :key="cn" class="mono">
            <a class="ncg-link" @click.prevent="goChange(cn)">{{ cn }}</a>
          </b>
          （{{ envLabel(store.createdTargetEnvId) }}）：需经 {{ envLabel(store.createdTargetEnvId) }}
          审批与发布；{{ envLabel(store.snapshot?.sourceEnvId ?? form.sourceEnvId) }} 内容未修改。
          <a
            v-if="store.createdChangeNos[0]"
            class="ncg-link"
            @click.prevent="goChange(store.createdChangeNos[0])"
          >
            打开变更单 →
          </a>
        </span>
      </template>
    </el-alert>

    <!-- 快照对比表 -->
    <template v-if="store.snapshot">
      <div class="snap-meta mono ncg-sub">
        快照 {{ store.snapshot.snapshotId }} · 采集于 {{ fmtTime(store.snapshot.collectedAt) }} ·
        {{ envLabel(store.snapshot.sourceEnvId) }} → {{ envLabel(store.snapshot.targetEnvId) }}
      </div>
      <el-table
        :data="store.snapshot.diffs"
        class="ncg-table"
        @selection-change="onSelectionChange"
      >
        <el-table-column type="selection" width="42" :selectable="onSelectable" />
        <el-table-column label="DataId" min-width="180">
          <template #default="{ row }">
            <span class="mono">{{ row.dataId || row.mappingId }}</span>
            <span v-if="row.same" class="ncg-sub"> · 一致</span>
          </template>
        </el-table-column>
        <el-table-column :label="`${envLabel(store.snapshot!.sourceEnvId)}（源）`" min-width="160">
          <template #default="{ row }">
            <span class="mono sync-val" :class="{ diff: !row.same }">{{ row.sourceMd5 }}</span>
          </template>
        </el-table-column>
        <el-table-column :label="`${envLabel(store.snapshot!.targetEnvId)}（目标）`" min-width="160">
          <template #default="{ row }">
            <span class="mono sync-val" :class="{ diff: !row.same }">{{ row.targetMd5 }}</span>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="110">
          <template #default="{ row }">
            <el-tag :type="row.same ? 'success' : 'warning'" effect="plain" round size="small">
              {{ row.same ? '一致' : '差异' }}
            </el-tag>
          </template>
        </el-table-column>
      </el-table>

      <div class="sync-foot">
        <span class="ncg-sub">
          {{ store.selected.length }} 项已选择 · 仅差异项可选（共 {{ store.diffRows.length }} 项差异）
        </span>
        <el-button
          v-if="canApply"
          type="primary"
          :disabled="!store.selected.length || app.readOnly"
          :loading="store.applying"
          @click="openApply"
        >
          生成 {{ envLabel(store.snapshot.targetEnvId) }} 变更单（{{ store.selected.length }}）
        </el-button>
      </div>
    </template>

    <EmptyState
      v-else
      reason="尚未对比环境"
      next-step="选择源环境与目标环境后点击“对比”，仅差异项可勾选并生成目标环境变更单。"
    />
  </PageShell>

  <!-- 生成目标环境变更单 -->
  <el-dialog v-model="applyVisible" title="生成目标环境变更单" width="520px" destroy-on-close>
    <el-alert type="info" :closable="false" class="ncg-banner">
      <template #title>
        只创建 {{ envLabel(store.snapshot?.targetEnvId ?? '') }} 的变更单草稿，需经目标环境审批与发布；
        {{ envLabel(store.snapshot?.sourceEnvId ?? '') }} 不会被修改。
      </template>
    </el-alert>
    <div class="ncg-kv">
      <div>
        <span>差异项</span><b class="mono">{{ store.selected.length }} 项</b>
      </div>
    </div>
    <el-form label-position="top">
      <el-form-item label="变更单标题（可选）">
        <el-input v-model="applyForm.title" placeholder="默认按同步内容生成" maxlength="120" />
      </el-form-item>
      <el-form-item label="变更原因（可选）">
        <el-input
          v-model="applyForm.reason"
          type="textarea"
          :rows="2"
          placeholder="例如：dev 验证通过的配置同步到 staging"
          maxlength="500"
        />
      </el-form-item>
    </el-form>
    <div v-if="applyError" class="ncg-form-error" role="alert">
      <div><b class="mono">{{ applyError.code }}</b> {{ codeText(applyError) }}</div>
      <div v-if="applyError.traceId" class="ncg-sub mono">TraceId {{ applyError.traceId }}</div>
    </div>
    <template #footer>
      <el-button @click="applyVisible = false">取消</el-button>
      <el-button type="primary" :loading="store.applying" @click="submitApply">生成变更单</el-button>
    </template>
  </el-dialog>

  <!-- 快照详情 -->
  <el-dialog v-model="snapshotVisible" title="快照详情" width="520px">
    <div v-if="store.snapshot" class="ncg-kv">
      <div><span>快照 ID</span><b class="mono">{{ store.snapshot.snapshotId }}</b></div>
      <div>
        <span>采集时间</span><b class="mono">{{ fmtTime(store.snapshot.collectedAt) }}</b>
      </div>
      <div>
        <span>对比方向</span>
        <b class="mono">
          {{ envLabel(store.snapshot.sourceEnvId) }}（源）→ {{ envLabel(store.snapshot.targetEnvId) }}（目标）
        </b>
      </div>
      <div>
        <span>差异统计</span>
        <b>{{ store.diffRows.length }} 项差异 / 共 {{ store.snapshot.diffs.length }} 项</b>
      </div>
    </div>
    <div class="ncg-hint">
      快照为采集时刻的对比结果；重新对比会生成新快照。生成目标变更单以当前勾选为准。
    </div>
    <template #footer>
      <el-button @click="snapshotVisible = false">关闭</el-button>
      <el-button type="primary" :loading="store.loading" @click="reloadSnapshot">
        按快照 ID 重新加载
      </el-button>
    </template>
  </el-dialog>
</template>

<style scoped>
.compare-card {
  border: 1px solid var(--ncg-border);
  border-radius: 12px;
  margin-bottom: var(--ncg-space-2);
}
.compare-form {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}
.env-flow {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-right: 8px;
}
.env-pill {
  font-family: var(--ncg-font-data);
  font-size: var(--ncg-fs-xs);
  letter-spacing: 0.06em;
  border: 1px solid var(--ncg-border);
  border-radius: 999px;
  padding: 4px 12px;
}
.env-pill.target {
  border-color: var(--ncg-accent);
  color: var(--ncg-accent);
}
.arrow {
  color: var(--ncg-text-muted);
}
.env-select {
  width: 130px;
}
.filter-input {
  width: 170px;
}
.ncg-hint {
  font-size: var(--ncg-fs-xs);
  color: var(--ncg-text-muted);
  line-height: 1.6;
  margin-top: var(--ncg-space-2);
}
.ncg-banner {
  margin-bottom: var(--ncg-space-2);
}
.snap-meta {
  margin-bottom: 8px;
}
.ncg-table {
  width: 100%;
}
.sync-val {
  font-size: var(--ncg-fs-xs);
  color: var(--ncg-text-muted);
}
.sync-val.diff {
  color: var(--ncg-warning, var(--ncg-amber));
  font-weight: 600;
}
.sync-foot {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-top: var(--ncg-space-2);
  padding-top: var(--ncg-space-2);
  border-top: 1px dashed var(--ncg-border);
}
.ncg-sub {
  font-size: var(--ncg-fs-xs);
  color: var(--ncg-text-muted);
}
.ncg-link {
  color: var(--ncg-accent);
  cursor: pointer;
  margin: 0 2px;
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
.ncg-form-error {
  margin-top: var(--ncg-space-2);
  padding: var(--ncg-space-2);
  border: 1px solid var(--ncg-danger);
  border-radius: var(--ncg-radius-md);
  font-size: var(--ncg-fs-xs);
  color: var(--ncg-danger);
}
</style>
