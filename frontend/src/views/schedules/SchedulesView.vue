<script setup lang="ts">
/**
 * 定时任务（P-10，契约 #24 定时设置 / #25 取消定时）
 * 审批通过后可按计划发布；到点必须重新校验，失败转回“待发布”并告警，不自动补发（SCH-409-002）。
 * 危险动作二次确认：取消定时。
 */
import { computed, onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import 'element-plus/es/components/message/style/css'
import 'element-plus/es/components/message-box/style/css'
import PageShell from '@/components/PageShell.vue'
import EmptyState from '@/components/EmptyState.vue'
import { useAppStore } from '@/stores/app'
import { useAuthStore } from '@/stores/auth'
import { useSchedulesStore, toErrorState, fmtTime, type ErrorState, type ScheduleRow } from '@/stores/schedules'
import { changeApi } from '@/api'
import type { ApiError } from '@/api/http'
import type { ChangeListItem } from '@/api/types'

const router = useRouter()
const store = useSchedulesStore()
const auth = useAuthStore()
const app = useAppStore()

/** 权限驱动渲染：无权限不渲染动作；只读降级（SYS-423-001）写按钮全部禁用 */
const canSchedule = computed(() => auth.can('PUBLISH'))

const STATUS_META: Record<string, { label: string; type: 'warning' | 'success' | 'info' | 'danger' }> = {
  ACTIVE: { label: '等待到点', type: 'warning' },
  EXECUTED: { label: '已发布', type: 'success' },
  CANCELED: { label: '已取消', type: 'info' },
  REVIEW_FAILED: { label: '到点复核失败', type: 'danger' },
}

function statusOf(status: string) {
  return STATUS_META[status] ?? { label: status, type: 'info' as const }
}

/** 错误文案按 code 分支（§4.2 / §6.6），不解析 message */
const CODE_COPY: Record<string, string> = {
  'SCH-409-001': '定时任务状态不允许操作，请刷新任务状态后重试',
  'SCH-409-002': '到点复核失败，任务已转回“待发布”并告警；系统不自动补发',
  'SYS-404-001': '变更单不存在或已删除',
  'SYS-500-001': '请求失败，请稍后重试',
}

function copyOf(e: ErrorState): string {
  return CODE_COPY[e.code] ?? e.message
}

/** 距执行提示（展示层，随每次渲染计算） */
function relTime(iso: string): string {
  const t = new Date(iso).getTime()
  if (Number.isNaN(t)) return ''
  const diff = t - Date.now()
  const abs = Math.abs(diff)
  const h = Math.floor(abs / 3_600_000)
  const m = Math.floor((abs % 3_600_000) / 60_000)
  const span = h > 0 ? `${h} 小时 ${m} 分` : `${m} 分`
  return diff > 0 ? `还有 ${span}` : `已过 ${span}`
}

function goChange(changeNo: string) {
  router.push({ name: 'change-detail', params: { changeNo } })
}

/* ---------- 定时设置弹窗 ---------- */

const scheduleVisible = ref(false)
const scheduleSubmitting = ref(false)
const dialogError = ref<ErrorState | null>(null)
const approvedChanges = ref<ChangeListItem[]>([])
const form = reactive<{ changeNo: string; plannedAt: string }>({ changeNo: '', plannedAt: '' })

async function openSchedule(pre?: unknown) {
  const r = pre as ScheduleRow | undefined
  dialogError.value = null
  form.changeNo = r?.changeNo ?? ''
  form.plannedAt = ''
  scheduleVisible.value = true
  try {
    const [approved, pending] = await Promise.all([
      changeApi.listChanges({ status: 'APPROVED', size: 100 }),
      changeApi.listChanges({ status: 'PENDING_RELEASE', size: 100 }),
    ])
    const merged = [...approved, ...pending]
    // 预填的变更单不在候选里时补一个选项，避免选择框显示裸值
    if (form.changeNo && !merged.some((c) => c.changeNo === form.changeNo)) {
      merged.unshift({
        changeNo: form.changeNo,
        title: r?.title ?? '',
        appId: '',
        envId: r?.envId ?? '',
        dataId: '',
        changeType: 'UPDATE',
        status: 'PENDING_RELEASE',
        riskLevel: 'LOW',
        createdBy: '',
        createdAt: '',
      })
    }
    approvedChanges.value = merged
  } catch {
    // 候选加载失败不清空已填内容（§3.5.4）
    approvedChanges.value = []
  }
}

async function submitSchedule() {
  dialogError.value = null
  if (!form.changeNo) {
    dialogError.value = { code: 'CR-400-001', message: '请选择要定时发布的变更单', traceId: '' }
    return
  }
  if (!form.plannedAt) {
    dialogError.value = { code: 'CR-400-001', message: '请选择计划发布时间', traceId: '' }
    return
  }
  const at = new Date(form.plannedAt)
  if (Number.isNaN(at.getTime()) || at.getTime() <= Date.now()) {
    dialogError.value = { code: 'CR-400-001', message: '计划发布时间必须晚于当前时间', traceId: '' }
    return
  }
  const hint = approvedChanges.value.find((c) => c.changeNo === form.changeNo)
  scheduleSubmitting.value = true
  try {
    await store.schedule(form.changeNo, at.toISOString(), { title: hint?.title, envId: hint?.envId })
    ElMessage.success('定时已设置：到点将重新校验后再执行')
    scheduleVisible.value = false
  } catch (e) {
    // 保留已填内容（§3.5.4）；错误按 code 分支
    dialogError.value = toErrorState(e)
  } finally {
    scheduleSubmitting.value = false
  }
}

/* ---------- 取消定时（危险动作二次确认） ---------- */

async function onCancel(row: unknown) {
  const r = row as ScheduleRow
  try {
    await ElMessageBox.confirm(
      `确认取消 ${r.changeNo} 的定时发布？取消后不再执行，变更单回到“待发布”。`,
      '取消定时发布',
      {
        type: 'warning',
        confirmButtonText: '确认取消',
        cancelButtonText: '返回',
        confirmButtonClass: 'el-button--danger',
      },
    )
  } catch {
    return
  }
  try {
    await store.cancel(r.changeNo)
    ElMessage.success('定时已取消，变更单回到“待发布”')
  } catch (e) {
    const err = e as ApiError
    ElMessage.error(CODE_COPY[err.code] ?? '取消失败，请稍后重试')
  }
}

/* ---------- 复核失败原因 ---------- */

const reasonVisible = ref(false)
const reasonRow = ref<ScheduleRow | null>(null)

function showReason(row: unknown) {
  reasonRow.value = row as ScheduleRow
  reasonVisible.value = true
}

onMounted(() => {
  store.fetch()
})
</script>

<template>
  <PageShell
    title="定时任务"
    description="审批通过后可按计划发布。到点必须重新校验；失败转回“待发布”并告警，不自动补发。"
    :loading="store.loading"
    :error="store.error"
    @retry="store.fetch()"
  >
    <template #actions>
      <span class="tz-chip" title="时间按 Asia/Shanghai 展示">ASIA/SHANGHAI</span>
      <el-button v-if="canSchedule" type="primary" :disabled="app.readOnly" @click="openSchedule()">
        定时设置
      </el-button>
    </template>

    <el-alert type="info" :closable="false" class="ncg-banner">
      <template #title>
        到点复核失败 SCH-409-002：基线已变化或实例不可用时不执行写入，任务转回“待发布”并告警；恢复后不自动补发。
      </template>
    </el-alert>

    <el-table v-if="store.rows.length" :data="store.rows" class="ncg-table">
      <el-table-column label="变更单" min-width="240">
        <template #default="{ row }">
          <a class="ncg-link mono" @click.prevent="goChange(row.changeNo)">{{ row.changeNo }}</a>
          <div class="ncg-sub">{{ row.title || '—' }}</div>
        </template>
      </el-table-column>
      <el-table-column label="环境" width="110">
        <template #default="{ row }">
          <span class="mono ncg-dim">{{ row.envId ? app.envLabelOf(row.envId) : '—' }}</span>
        </template>
      </el-table-column>
      <el-table-column label="计划时间（Asia/Shanghai）" min-width="180">
        <template #default="{ row }">
          <div class="mono">{{ fmtTime(row.plannedAt) }}</div>
          <div v-if="row.status === 'ACTIVE'" class="ncg-sub">{{ relTime(row.plannedAt) }}</div>
        </template>
      </el-table-column>
      <el-table-column label="状态" width="140">
        <template #default="{ row }">
          <el-tag :type="statusOf(row.status).type" effect="plain" round>
            {{ statusOf(row.status).label }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="复核失败原因" min-width="160">
        <template #default="{ row }">
          <el-button v-if="row.reviewReason" link type="danger" @click="showReason(row)">
            查看原因
          </el-button>
          <span v-else class="ncg-dim">—</span>
        </template>
      </el-table-column>
      <el-table-column label="操作" width="150" fixed="right">
        <template #default="{ row }">
          <template v-if="canSchedule && row.status === 'ACTIVE'">
            <el-button link type="danger" :disabled="app.readOnly" @click="onCancel(row)">
              取消定时
            </el-button>
          </template>
          <el-button
            v-else-if="canSchedule && row.status === 'REVIEW_FAILED'"
            link
            type="primary"
            :disabled="app.readOnly"
            @click="openSchedule(row)"
          >
            重新定时
          </el-button>
          <span v-else class="ncg-dim">—</span>
        </template>
      </el-table-column>
    </el-table>

    <EmptyState
      v-else
      reason="暂无定时任务"
      next-step="审批通过的变更单可在变更详情设置定时发布；到点前可在此取消。"
    />
  </PageShell>

  <!-- 定时设置 -->
  <el-dialog v-model="scheduleVisible" title="定时设置" width="520px" destroy-on-close>
    <el-form label-position="top">
      <el-form-item label="变更单" required>
        <el-select
          v-model="form.changeNo"
          placeholder="选择已审批通过 / 待发布的变更单"
          filterable
          style="width: 100%"
        >
          <el-option
            v-for="c in approvedChanges"
            :key="c.changeNo"
            :label="`${c.changeNo} · ${c.title || c.status}`"
            :value="c.changeNo"
          />
        </el-select>
      </el-form-item>
      <el-form-item label="计划发布时间（Asia/Shanghai）" required>
        <el-date-picker
          v-model="form.plannedAt"
          type="datetime"
          placeholder="选择到点执行时间"
          value-format="YYYY-MM-DD HH:mm:ss"
          style="width: 100%"
        />
      </el-form-item>
    </el-form>
    <div class="ncg-hint">
      到点后先重新校验基线与实例状态，再写入并读回；失败转回“待发布”并告警，不自动补发。
    </div>
    <div v-if="dialogError" class="ncg-form-error" role="alert">
      <div>
        <b class="mono">{{ dialogError.code }}</b> {{ copyOf(dialogError) }}
      </div>
      <div v-if="dialogError.traceId" class="ncg-sub mono">TraceId {{ dialogError.traceId }}</div>
    </div>
    <template #footer>
      <el-button @click="scheduleVisible = false">取消</el-button>
      <el-button type="primary" :loading="scheduleSubmitting" @click="submitSchedule">
        确认定时
      </el-button>
    </template>
  </el-dialog>

  <!-- 复核失败原因 -->
  <el-dialog v-model="reasonVisible" title="到点复核失败原因" width="520px">
    <el-alert type="warning" :closable="false" class="ncg-banner">
      <template #title>
        SCH-409-002：到点复核失败时系统不执行写入，任务转回“待发布”并告警；恢复后不自动补发。
      </template>
    </el-alert>
    <div class="ncg-kv">
      <div><span>变更单</span><b class="mono">{{ reasonRow?.changeNo }}</b></div>
      <div><span>计划时间</span><b class="mono">{{ fmtTime(reasonRow?.plannedAt) }}</b></div>
      <div>
        <span>失败原因</span><b>{{ reasonRow?.reviewReason || '—' }}</b>
      </div>
    </div>
    <template #footer>
      <el-button type="primary" @click="reasonVisible = false">关闭提示</el-button>
    </template>
  </el-dialog>
</template>

<style scoped>
.tz-chip {
  font-family: var(--ncg-font-data);
  font-size: var(--ncg-fs-xs);
  letter-spacing: 0.08em;
  color: var(--ncg-text-muted);
  border: 1px solid var(--ncg-border);
  border-radius: 999px;
  padding: 2px 10px;
}
.ncg-banner {
  margin-bottom: var(--ncg-space-2);
}
.ncg-table {
  width: 100%;
}
.ncg-link {
  color: var(--ncg-accent);
  cursor: pointer;
  font-size: var(--ncg-fs-xs);
}
.ncg-sub {
  font-size: var(--ncg-fs-xs);
  color: var(--ncg-text-muted);
  margin-top: 2px;
}
.ncg-dim {
  color: var(--ncg-text-muted);
}
.ncg-hint {
  font-size: var(--ncg-fs-xs);
  color: var(--ncg-text-muted);
  line-height: 1.6;
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
  gap: 8px;
  margin-top: var(--ncg-space-2);
  font-size: var(--ncg-fs-sm);
}
.ncg-kv span {
  display: inline-block;
  width: 90px;
  color: var(--ncg-text-muted);
}
</style>
