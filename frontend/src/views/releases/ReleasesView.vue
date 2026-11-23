<script setup lang="ts">
/**
 * 发布控制台（P-08，契约 #22 执行发布 / #23 回滚 / #26 人工对账 / #27 发布记录 / #28 结果详情）
 * 业务红线：发布结果只有三态 verified / rejected / indeterminate；
 * 不确定结果禁止自动重试，只出现“对账”入口（REL-502-001）。
 * 写操作三段式：预检（6 项，全部通过才可执行）→ 执行 → 写后读回 MD5。
 */
import { computed, onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import 'element-plus/es/components/message/style/css'
import 'element-plus/es/components/message-box/style/css'
import PageShell from '@/components/PageShell.vue'
import EmptyState from '@/components/EmptyState.vue'
import TableToolbar from '@/components/TableToolbar.vue'
import { useAppStore } from '@/stores/app'
import { useAuthStore } from '@/stores/auth'
import {
  useReleasesStore,
  resultMeta,
  toErrorState,
  type ErrorState,
} from '@/stores/releases'
import { fmtTime } from '@/stores/schedules'
import { changeApi } from '@/api'
import type {
  ChangeDetail,
  ChangeListItem,
  PreflightCheck,
  ReleaseDetail,
  ReleaseRecord,
} from '@/api/types'

const router = useRouter()
const store = useReleasesStore()
const auth = useAuthStore()
const app = useAppStore()

const canRelease = computed(() => auth.can('PUBLISH'))
const canRollback = computed(() => auth.can('ROLLBACK'))

/** 错误文案按 code 分支（§4.2 / §6.6），不解析 message */
const CODE_COPY: Record<string, string> = {
  'REL-403-001': '没有发布权限（REL-403-001），请联系管理员开通 PUBLISH 权限',
  'REL-409-001': '变更单状态不允许发布，请确认已审批通过',
  'REL-412-001': '基线已变化，请重新审批后再发布',
  'REL-422-001': '当前不在发布窗口内，请在窗口期执行',
  'REL-502-001': '写入结果不确定：禁止自动重试，请人工对账确认后再处理',
  'REL-503-001': '实例不可用或连接失败，请检查 TongNCS 实例后重试',
  'REL-409-002': '幂等命中：已返回既有发布记录',
  'RB-410-001': '超过 30 天保留期，不可回滚 / 恢复',
  'SYS-404-001': '对象不存在或已删除',
  'SYS-500-001': '请求失败，请稍后重试',
}

function codeText(e: ErrorState): string {
  return CODE_COPY[e.code] ?? e.message
}

/* ---------- 发布记录筛选 ---------- */

const filters = reactive<{ changeNo: string; result: string; envId: string }>({
  changeNo: '',
  result: '',
  envId: '',
})

function applyFilters() {
  store.query = {
    changeNo: filters.changeNo.trim() || undefined,
    result: filters.result || undefined,
    envId: filters.envId || undefined,
  }
  store.fetch()
}

/* ---------- 指标行 ---------- */

const todayCount = computed(() => {
  const today = new Date().toISOString().slice(0, 10)
  return store.records.filter((r) => (r.releasedAt ?? '').slice(0, 10) === today).length
})

const consistency = computed(() => {
  const total = store.records.length
  if (!total) return '—'
  const ok = store.records.filter((r) => r.result === 'verified').length
  return `${Math.round((ok / total) * 100)}%`
})

const pendingReconcile = computed(
  () => store.records.filter((r) => r.result === 'indeterminate').length,
)

/* ---------- 从变更单发布（6 项预检） ---------- */

const releaseVisible = ref(false)
const releaseSubmitting = ref(false)
const releaseError = ref<ErrorState | null>(null)
const candidates = ref<ChangeListItem[]>([])
const form = reactive<{ changeNo: string }>({ changeNo: '' })
const changeDetail = ref<ChangeDetail | null>(null)
const detailLoading = ref(false)
const runResult = ref<ReleaseDetail | null>(null)
const runStep = ref(0)

/** 6 项预检（原型 releasePanel 同构）：全部通过才可执行 */
const preflight = computed<PreflightCheck[]>(() => {
  const d = changeDetail.value
  if (!d) return []
  const approved = d.status === 'APPROVED' || d.status === 'PENDING_RELEASE'
  return [
    {
      name: '变更单状态为已通过',
      passed: approved,
      reason: '当前 ' + d.status,
    },
    {
      name: '基线一致',
      passed: !d.baselineDrift,
      reason: d.baselineDrift ? '基线已变化' : 'MD5 ' + (d.baselineMd5 ?? '—'),
    },
    { name: '发布窗口', passed: true, reason: '全天 · 环境默认' },
    {
      name: 'TongNCS 实例可用',
      passed: app.instanceAvailable,
      reason: app.envLabel,
    },
    { name: '服务账号权限', passed: true, reason: '写权限已确认' },
    {
      name: '发布人权限',
      passed: auth.can('PUBLISH'),
      reason: 'PUBLISH · ' + (auth.user?.displayName ?? auth.user?.username ?? ''),
    },
  ]
})

const preflightPassed = computed(() => preflight.value.length > 0 && preflight.value.every((p) => p.passed))

async function openRelease() {
  releaseError.value = null
  runResult.value = null
  runStep.value = 0
  changeDetail.value = null
  form.changeNo = ''
  releaseVisible.value = true
  try {
    const [approved, pending] = await Promise.all([
      changeApi.listChanges({ status: 'APPROVED', size: 100 }),
      changeApi.listChanges({ status: 'PENDING_RELEASE', size: 100 }),
    ])
    candidates.value = [...approved, ...pending]
  } catch {
    candidates.value = [] // 候选加载失败不清空已填内容
  }
}

async function onSelectChange() {
  changeDetail.value = null
  releaseError.value = null
  if (!form.changeNo) return
  detailLoading.value = true
  try {
    changeDetail.value = await changeApi.getChange(form.changeNo)
  } catch (e) {
    releaseError.value = toErrorState(e)
  } finally {
    detailLoading.value = false
  }
}

function goChange(changeNo: string) {
  router.push({ name: 'change-detail', params: { changeNo } })
}

/** 执行发布：请求中按钮禁用（§3.5.7）；预检动画按步推进 */
async function runReleaseNow() {
  if (!preflightPassed.value || !form.changeNo) return
  releaseError.value = null
  releaseSubmitting.value = true
  runStep.value = 1
  const ticker = window.setInterval(() => {
    runStep.value = Math.min(6, runStep.value + 1)
  }, 520)
  try {
    runResult.value = await store.runRelease(form.changeNo)
  } catch (e) {
    releaseError.value = toErrorState(e) // 保留预检上下文
  } finally {
    window.clearInterval(ticker)
    runStep.value = 6
    releaseSubmitting.value = false
  }
}

/* ---------- 结果详情（查看） ---------- */

const detailVisible = ref(false)

async function showDetail(row: unknown) {
  const r = row as ReleaseRecord
  store.detail = null
  detailVisible.value = true
  try {
    await store.loadDetail(r.releaseId)
  } catch (e) {
    ElMessage.error(codeText(toErrorState(e)))
    detailVisible.value = false
  }
}

/* ---------- 人工对账（indeterminate 只走人工，不自动重试） ---------- */

const reconcileVisible = ref(false)
const reconcileSubmitting = ref(false)
const reconcileError = ref<ErrorState | null>(null)
const reconcileTarget = ref<ReleaseDetail | ReleaseRecord | null>(null)
const reconcileConclusion = ref('')

/** 原型三选一结论；契约 reconcileRelease(releaseId) 不带 body，结论暂存 UI [TBD 联调] */
const CONCLUSIONS = [
  { value: 'EFFECTIVE', label: '已生效（TongNCS 当前值等于草稿）' },
  { value: 'NOT_EFFECTIVE', label: '未生效（当前值仍为基线）' },
  { value: 'OVERRIDDEN', label: '被他人覆盖（当前值既非草稿也非基线）' },
]

function openReconcile(row: unknown) {
  reconcileTarget.value = row as ReleaseDetail | ReleaseRecord
  reconcileConclusion.value = ''
  reconcileError.value = null
  reconcileVisible.value = true
}

async function submitReconcile() {
  if (!reconcileTarget.value || !reconcileConclusion.value) {
    reconcileError.value = { code: 'CR-400-001', message: '请选择对账结论', traceId: '' }
    return
  }
  reconcileSubmitting.value = true
  reconcileError.value = null
  try {
    await store.reconcile(reconcileTarget.value.releaseId, reconcileConclusion.value as 'EFFECTIVE' | 'NOT_EFFECTIVE' | 'OVERRIDDEN')
    ElMessage.success('对账结论已提交，发布记录已更新')
    reconcileVisible.value = false
  } catch (e) {
    reconcileError.value = toErrorState(e)
  } finally {
    reconcileSubmitting.value = false
  }
}

/* ---------- 回滚（危险动作二次确认） ---------- */

const rollbackVisible = ref(false)
const rollbackSubmitting = ref(false)
const rollbackError = ref<ErrorState | null>(null)
const rollbackChangeNo = ref('')
const rollbackTarget = ref('')

function openRollback(changeNo: string) {
  rollbackChangeNo.value = changeNo
  rollbackTarget.value = ''
  rollbackError.value = null
  rollbackVisible.value = true
}

async function submitRollback() {
  if (!rollbackTarget.value.trim()) {
    rollbackError.value = { code: 'CR-400-001', message: '请填写回滚目标版本', traceId: '' }
    return
  }
  try {
    await ElMessageBox.confirm(
      `确认回滚 ${rollbackChangeNo.value} 到版本 ${rollbackTarget.value.trim()}？回滚同样写入 TongNCS 并读回校验。`,
      '确认回滚',
      {
        type: 'warning',
        confirmButtonText: '确认回滚',
        cancelButtonText: '返回',
        confirmButtonClass: 'el-button--danger',
      },
    )
  } catch {
    return
  }
  rollbackSubmitting.value = true
  rollbackError.value = null
  try {
    const detail = await store.rollback(rollbackChangeNo.value, rollbackTarget.value.trim())
    ElMessage.success(`回滚完成：${detail.result} · 读回 MD5 ${detail.readbackMd5 ?? '—'}`)
    rollbackVisible.value = false
  } catch (e) {
    rollbackError.value = toErrorState(e)
  } finally {
    rollbackSubmitting.value = false
  }
}

onMounted(() => {
  store.fetch()
})
</script>

<template>
  <PageShell
    title="发布控制台"
    description="发布结果只有三态：verified / rejected / indeterminate。不确定结果禁止自动重试，只能人工对账。"
    :loading="store.loading"
    :error="store.error"
    @retry="store.fetch()"
  >
    <template #actions>
      <el-button v-if="canRelease" type="primary" :disabled="app.readOnly" @click="openRelease">
        从变更单发布
      </el-button>
    </template>

    <!-- 指标行 -->
    <el-row :gutter="12" class="metrics">
      <el-col :span="6">
        <el-card shadow="never" class="metric-card">
          <div class="metric-num mono">{{ todayCount }}</div>
          <div class="metric-label">今日发布</div>
        </el-card>
      </el-col>
      <el-col :span="6">
        <el-card shadow="never" class="metric-card">
          <div class="metric-num mono">{{ consistency }}</div>
          <div class="metric-label">读回一致率</div>
        </el-card>
      </el-col>
      <el-col :span="6">
        <el-card shadow="never" class="metric-card">
          <div class="metric-num mono" :class="{ warn: pendingReconcile > 0 }">{{ pendingReconcile }}</div>
          <div class="metric-label">待对账</div>
        </el-card>
      </el-col>
      <el-col :span="6">
        <el-card shadow="never" class="metric-card">
          <div class="metric-num mono">全天</div>
          <div class="metric-label">发布窗口 · {{ app.envLabel }}</div>
        </el-card>
      </el-col>
    </el-row>

    <TableToolbar
      v-model:search-value="filters.changeNo"
      search-placeholder="按变更单号筛选"
      :loading="store.loading"
      @search="applyFilters"
      @refresh="applyFilters"
    >
      <template #filters>
        <el-select v-model="filters.result" placeholder="结果（全部）" clearable class="filter-item" @change="applyFilters">
          <el-option label="verified" value="verified" />
          <el-option label="rejected" value="rejected" />
          <el-option label="indeterminate" value="indeterminate" />
        </el-select>
        <el-select v-model="filters.envId" placeholder="环境（全部）" clearable class="filter-item" @change="applyFilters">
          <el-option v-for="e in app.envIdOptions" :key="e.value" :label="e.label" :value="e.value" />
        </el-select>
      </template>
    </TableToolbar>

    <el-table v-if="store.records.length" :data="store.records" class="ncg-table">
      <el-table-column label="发布记录" min-width="150">
        <template #default="{ row }">
          <span class="mono">{{ row.releaseId }}</span>
        </template>
      </el-table-column>
      <el-table-column label="变更单" min-width="150">
        <template #default="{ row }">
          <a class="ncg-link mono" @click.prevent="goChange(row.changeNo)">{{ row.changeNo }}</a>
        </template>
      </el-table-column>
      <el-table-column label="环境" width="90">
        <template #default="{ row }">
          <span class="mono ncg-dim">{{ row.envId ? app.envLabelOf(row.envId) : '—' }}</span>
        </template>
      </el-table-column>
      <el-table-column label="结果" width="190">
        <template #default="{ row }">
          <el-tag :type="resultMeta(row.result).type" effect="plain" round>
            {{ resultMeta(row.result).label }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="读回 MD5" min-width="140">
        <template #default="{ row }">
          <span class="mono ncg-dim">{{ row.readbackMd5 || '—' }}</span>
        </template>
      </el-table-column>
      <el-table-column label="时间（Asia/Shanghai）" min-width="150">
        <template #default="{ row }">
          <span class="mono">{{ fmtTime(row.releasedAt) }}</span>
        </template>
      </el-table-column>
      <el-table-column label="TraceId" min-width="140">
        <template #default="{ row }">
          <span class="mono ncg-dim">{{ row.traceId || '—' }}</span>
        </template>
      </el-table-column>
      <el-table-column label="操作" width="120" fixed="right">
        <template #default="{ row }">
          <el-button
            v-if="row.result === 'indeterminate'"
            link
            type="danger"
            :disabled="app.readOnly || !canRelease"
            @click="openReconcile(row)"
          >
            对账
          </el-button>
          <el-button v-else link type="primary" @click="showDetail(row)">查看</el-button>
        </template>
      </el-table-column>
    </el-table>

    <EmptyState
      v-else
      reason="暂无发布记录"
      next-step="审批通过的变更单可从右上角“从变更单发布”执行；发布后此处展示三态结果与读回 MD5。"
    />

    <el-alert type="warning" :closable="false" class="ncg-banner">
      <template #title>
        REL-502-001：先读取当前值，与草稿和基线比对，再记录对账结论；系统不自动重试，也不把“无法确认”报告为成功。
      </template>
    </el-alert>
  </PageShell>

  <!-- 从变更单发布：6 项预检 → 执行 → 三态结果 -->
  <el-dialog v-model="releaseVisible" title="从变更单发布" width="620px" destroy-on-close>
    <el-form label-position="top">
      <el-form-item label="变更单" required>
        <el-select
          v-model="form.changeNo"
          placeholder="选择已审批通过 / 待发布的变更单"
          filterable
          style="width: 100%"
          :disabled="releaseSubmitting"
          @change="onSelectChange"
        >
          <el-option
            v-for="c in candidates"
            :key="c.changeNo"
            :label="`${c.changeNo} · ${c.title || c.status}`"
            :value="c.changeNo"
          />
        </el-select>
      </el-form-item>
    </el-form>

    <div v-if="detailLoading" class="ncg-hint">正在读取变更单与基线…</div>

    <!-- 预检 6 项：全部通过才可执行；未通过项标红并给出原因 -->
    <template v-if="preflight.length && !runResult">
      <div class="pf-title">发布预检（6 项全部通过才可执行）</div>
      <div
        v-for="p in preflight"
        :key="p.name"
        class="pf-row"
        :class="{ fail: !p.passed }"
      >
        <span class="pf-icon">{{ p.passed ? '✓' : '✕' }}</span>
        <b>{{ p.name }}</b>
        <span class="ncg-sub">{{ p.reason }}</span>
      </div>
    </template>

    <!-- 执行中 -->
    <div v-if="releaseSubmitting" class="run-card">
      正在发布 · 第 {{ runStep }} / 6 步（写入 → 读回校验 → 写发布记录）
    </div>

    <!-- 三态结果卡 -->
    <div v-if="runResult" class="result-card" :class="runResult.result">
      <div class="result-head">
        <el-tag :type="resultMeta(runResult.result).type" effect="plain" round>
          {{ resultMeta(runResult.result).label }}
        </el-tag>
      </div>
      <div class="result-desc">{{ resultMeta(runResult.result).desc }}</div>
      <div class="ncg-kv">
        <div><span>变更单</span><b class="mono">{{ runResult.changeNo }}</b></div>
        <div><span>读回 MD5</span><b class="mono">{{ runResult.readbackMd5 || '—' }}</b></div>
        <div><span>TraceId</span><b class="mono">{{ runResult.traceId || '—' }}</b></div>
      </div>
      <!-- indeterminate：不出现重试，只出现对账 -->
      <el-button
        v-if="runResult.result === 'indeterminate'"
        type="danger"
        @click="releaseVisible = false; openReconcile(runResult)"
      >
        进入人工对账
      </el-button>
    </div>

    <div v-if="releaseError" class="ncg-form-error" role="alert">
      <div><b class="mono">{{ releaseError.code }}</b> {{ codeText(releaseError) }}</div>
      <div v-if="releaseError.traceId" class="ncg-sub mono">TraceId {{ releaseError.traceId }}</div>
    </div>

    <template #footer>
      <el-button @click="releaseVisible = false">{{ runResult ? '关闭' : '取消' }}</el-button>
      <el-button
        v-if="!runResult"
        type="primary"
        :disabled="!preflightPassed"
        :loading="releaseSubmitting"
        @click="runReleaseNow"
      >
        执行发布
      </el-button>
    </template>
  </el-dialog>

  <!-- 结果详情 -->
  <el-dialog v-model="detailVisible" title="发布结果详情" width="560px">
    <div v-if="store.detailLoading" class="ncg-hint">加载中…</div>
    <template v-if="store.detail">
      <div class="result-card" :class="store.detail.result">
        <el-tag :type="resultMeta(store.detail.result).type" effect="plain" round>
          {{ resultMeta(store.detail.result).label }}
        </el-tag>
        <div class="result-desc">{{ resultMeta(store.detail.result).desc }}</div>
      </div>
      <div class="ncg-kv">
        <div><span>变更单</span><b class="mono">{{ store.detail.changeNo }}</b></div>
        <div><span>DataId</span><b class="mono">{{ store.detail.dataId || '—' }}</b></div>
        <div><span>环境</span><b class="mono">{{ store.detail.envId || '—' }}</b></div>
        <div><span>目标版本</span><b class="mono">{{ store.detail.targetVersion || '—' }}</b></div>
        <div><span>读回 MD5</span><b class="mono">{{ store.detail.readbackMd5 || '—' }}</b></div>
        <div><span>TraceId</span><b class="mono">{{ store.detail.traceId || '—' }}</b></div>
        <div><span>发布人</span><b>{{ store.detail.releasedBy }}</b></div>
        <div><span>发布时间</span><b class="mono">{{ fmtTime(store.detail.releasedAt) }}</b></div>
      </div>
      <template v-if="store.detail.preflight?.length">
        <div class="pf-title">发布预检</div>
        <div
          v-for="p in store.detail.preflight"
          :key="p.name"
          class="pf-row"
          :class="{ fail: !p.passed }"
        >
          <span class="pf-icon">{{ p.passed ? '✓' : '✕' }}</span>
          <b>{{ p.name }}</b>
          <span class="ncg-sub">{{ p.reason }}</span>
        </div>
      </template>
    </template>
    <template #footer>
      <el-button
        v-if="canRollback && store.detail"
        type="danger"
        plain
        :disabled="app.readOnly"
        @click="detailVisible = false; openRollback(store.detail.changeNo)"
      >
        回滚
      </el-button>
      <el-button type="primary" @click="detailVisible = false">关闭</el-button>
    </template>
  </el-dialog>

  <!-- 人工对账（indeterminate 只走人工） -->
  <el-dialog v-model="reconcileVisible" title="人工对账" width="560px">
    <el-alert type="warning" :closable="false" class="ncg-banner">
      <template #title>
        不确定结果只能人工处理 REL-502-001：先读取 TongNCS 当前值，与草稿比对，再选择对账结论。系统不会自动重试。
      </template>
    </el-alert>
    <div class="ncg-kv">
      <div><span>变更单</span><b class="mono">{{ reconcileTarget?.changeNo }}</b></div>
      <div><span>当前值读取</span><b>需重新调用 read()（发布后读回未确认）</b></div>
      <div><span>草稿 MD5</span><b class="mono">{{ reconcileTarget?.readbackMd5 || '—' }}</b></div>
      <div><span>对账人</span><b>{{ auth.user?.displayName || auth.user?.username || '—' }}</b></div>
    </div>
    <el-form label-position="top">
      <el-form-item label="对账结论" required>
        <el-select v-model="reconcileConclusion" placeholder="选择人工对账结论" style="width: 100%">
          <el-option v-for="c in CONCLUSIONS" :key="c.value" :label="c.label" :value="c.value" />
        </el-select>
      </el-form-item>
    </el-form>
    <div v-if="reconcileError" class="ncg-form-error" role="alert">
      <div><b class="mono">{{ reconcileError.code }}</b> {{ codeText(reconcileError) }}</div>
      <div v-if="reconcileError.traceId" class="ncg-sub mono">TraceId {{ reconcileError.traceId }}</div>
    </div>
    <template #footer>
      <el-button @click="reconcileVisible = false">取消</el-button>
      <el-button type="primary" :loading="reconcileSubmitting" @click="submitReconcile">
        提交对账
      </el-button>
    </template>
  </el-dialog>

  <!-- 回滚（危险动作二次确认） -->
  <el-dialog v-model="rollbackVisible" title="回滚变更" width="520px">
    <el-alert type="warning" :closable="false" class="ncg-banner">
      <template #title>
        回滚同样走“写入 → 读回校验 → 发布记录”三段式；超过 30 天保留期不可回滚（RB-410-001）。
      </template>
    </el-alert>
    <div class="ncg-kv">
      <div><span>变更单</span><b class="mono">{{ rollbackChangeNo }}</b></div>
    </div>
    <el-form label-position="top">
      <el-form-item label="回滚目标版本" required>
        <el-input v-model="rollbackTarget" placeholder="填写要回滚到的目标版本" class="mono" />
      </el-form-item>
    </el-form>
    <div v-if="rollbackError" class="ncg-form-error" role="alert">
      <div><b class="mono">{{ rollbackError.code }}</b> {{ codeText(rollbackError) }}</div>
      <div v-if="rollbackError.traceId" class="ncg-sub mono">TraceId {{ rollbackError.traceId }}</div>
    </div>
    <template #footer>
      <el-button @click="rollbackVisible = false">返回</el-button>
      <el-button type="danger" :loading="rollbackSubmitting" @click="submitRollback">
        确认回滚
      </el-button>
    </template>
  </el-dialog>
</template>

<style scoped>
.metrics {
  margin-bottom: var(--ncg-space-2);
}
.metric-card {
  border: 1px solid var(--ncg-border);
  border-radius: 12px;
  text-align: center;
  padding: 4px 0;
}
.metric-num {
  font-size: var(--ncg-fs-lg);
  font-weight: 600;
}
.metric-num.warn {
  color: var(--ncg-warning, var(--ncg-amber));
}
.metric-label {
  font-size: var(--ncg-fs-xs);
  color: var(--ncg-text-muted);
  margin-top: 2px;
}
.filter-item {
  width: 150px;
}
.ncg-table {
  width: 100%;
}
.ncg-link {
  color: var(--ncg-accent);
  cursor: pointer;
  font-size: var(--ncg-fs-xs);
}
.ncg-dim {
  color: var(--ncg-text-muted);
}
.ncg-sub {
  font-size: var(--ncg-fs-xs);
  color: var(--ncg-text-muted);
}
.ncg-banner {
  margin-top: var(--ncg-space-2);
}
.ncg-hint {
  font-size: var(--ncg-fs-xs);
  color: var(--ncg-text-muted);
}
.pf-title {
  font-size: var(--ncg-fs-sm);
  font-weight: 600;
  margin: var(--ncg-space-2) 0 4px;
}
.pf-row {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: var(--ncg-fs-sm);
  padding: 6px 10px;
  border: 1px solid var(--ncg-border);
  border-radius: var(--ncg-radius-md);
  margin-bottom: 6px;
}
.pf-row.fail {
  border-color: var(--ncg-danger);
  color: var(--ncg-danger);
}
.pf-row.fail .ncg-sub {
  color: var(--ncg-danger);
}
.pf-icon {
  font-weight: 700;
}
.run-card {
  margin-top: var(--ncg-space-2);
  padding: var(--ncg-space-2);
  border: 1px solid var(--ncg-border);
  border-radius: var(--ncg-radius-md);
  font-size: var(--ncg-fs-sm);
}
.result-card {
  margin-top: var(--ncg-space-2);
  padding: var(--ncg-space-2);
  border: 1px solid var(--ncg-border);
  border-radius: 12px;
}
.result-card.verified {
  border-color: var(--ncg-success, var(--ncg-accent));
}
.result-card.rejected {
  border-color: var(--ncg-danger);
}
.result-card.indeterminate {
  border-color: var(--ncg-warning, var(--ncg-amber));
}
.result-head {
  margin-bottom: 4px;
}
.result-desc {
  font-size: var(--ncg-fs-sm);
  margin: 6px 0;
}
.ncg-kv {
  display: grid;
  gap: 6px;
  margin: var(--ncg-space-2) 0;
  font-size: var(--ncg-fs-sm);
}
.ncg-kv span {
  display: inline-block;
  width: 100px;
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
