<script setup lang="ts">
/**
 * ChangeDetailView —— 变更详情（P-06，§3.5.3 状态迁移）
 * DRAFT：草稿编辑值 / 提交 / 取消（版本乐观锁 SYS-409-001）；
 * PENDING_APPROVAL：通过 / 驳回（原因≥10）/ 转交（提交人不可审批 AUTHZ-403-002）；
 * APPROVED|PENDING_RELEASE：执行发布（漂移 REL-412-001 / 影响面阻断时禁用）；
 * INDETERMINATE：进入对账（不自动重试 MUST-04）；RELEASED：回滚（ROLLBACK）。
 * 基线漂移展示三方 Diff：基线列 API 不下发逐项值 → 显示「—」（MUST-NOT-10 不臆造）。
 */
import { computed, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import 'element-plus/es/components/message/style/css'
import 'element-plus/es/components/message-box/style/css'
import 'element-plus/es/components/alert/style/css'
import 'element-plus/es/components/tabs/style/css'
import 'element-plus/es/components/tab-pane/style/css'
import 'element-plus/es/components/dialog/style/css'
import 'element-plus/es/components/input/style/css'
import 'element-plus/es/components/select/style/css'
import 'element-plus/es/components/option/style/css'
import 'element-plus/es/components/tag/style/css'
import 'element-plus/es/components/table/style/css'
import 'element-plus/es/components/table-column/style/css'
import 'element-plus/es/components/empty/style/css'
import 'element-plus/es/components/timeline/style/css'
import 'element-plus/es/components/timeline-item/style/css'
import 'element-plus/es/components/tooltip/style/css'
import { approvalApi, authApi, changeApi, releaseApi } from '@/api'
import type { ApiError } from '@/api/http'
import type { ChangeDetail, ConfigItem, ReleaseDetail, UserAccount } from '@/api/types'
import { useAuthStore } from '@/stores/auth'
import { useCatalogStore } from '@/stores/catalog'
import { useAppStore } from '@/stores/app'
import {
  useChangesStore,
  approveGuard,
  asList,
  buildItemDiffRows,
  buildContentDiffRows,
  buildDeleteDiffRows,
  changeTypeLabel,
  fmtTime,
  reasonInvalid,
  REASON_MIN,
  statusLabel,
  toUiError,
  type DiffRowInput,
  type UiError,
} from '@/stores/changes'
import PageShell from '@/components/PageShell.vue'
import ErrorState from '@/components/ErrorState.vue'
import DiffView from '@/components/DiffView.vue'
import StatusTag from './StatusTag.vue'
import ChangeValueEditor from './ChangeValueEditor.vue'

const route = useRoute()
const router = useRouter()
const auth = useAuthStore()
const catalog = useCatalogStore()
const appStore = useAppStore()
const store = useChangesStore()

const changeNo = computed(() => String(route.params.changeNo ?? ''))

const detail = computed(() => store.detail)
const actionError = ref<UiError | null>(null)
const activeTab = ref('overview')

/* ── 载入 ── */
onMounted(async () => {
  await store.fetchDetail(changeNo.value)
  void store.fetchImpact(changeNo.value)
  // 审批守卫需要应用负责人信息
  if (!catalog.apps.length) void catalog.fetchApps()
  // Diff 页签需要当前值（API 只给 items/content，当前值取映射现值）
  if (store.detail?.mappingId) {
    try {
      await catalog.openDetail(store.detail.mappingId)
    } catch {
      /* 当前值载入失败不阻断详情 */
    }
  }
})

/* ── 三方 Diff（基线列 API 未下发逐项值 → 「—」） ── */
const diffRows = computed<DiffRowInput[]>(() => {
  const d = detail.value
  if (!d) return []
  if (d.changeType === 'DELETE') return buildDeleteDiffRows(d.items ?? [])
  const current = catalog.contents[d.mappingId]
  if (d.items?.length) {
    const next = d.items.map((i: ConfigItem) => ({ key: i.key, value: i.value, sensitive: i.sensitive }))
    return buildItemDiffRows(current?.items ?? [], next).map((r) => ({ ...r, baseline: undefined }))
  }
  return buildContentDiffRows(current?.content, d.content ?? '', { key: 'content（整文件）' }).map((r) => ({
    ...r,
    baseline: undefined,
  }))
})

/* ── 审批守卫 ── */
const guard = computed(() => {
  const d = detail.value as (ChangeDetail & { createdBy?: string }) | null
  const app = catalog.apps.find((a) => a.appId === d?.appId)
  return approveGuard({
    createdBy: d?.createdBy,
    riskLevel: d?.riskLevel,
    appOwner: app?.owner,
  })
})

/* ── DRAFT 草稿编辑 ── */
const editing = ref(false)
const editValues = ref<Record<string, string>>({})
const editContent = ref('')
const editTitle = ref('')
const editReason = ref('')
const saving = ref(false)

function startEdit() {
  const d = detail.value
  if (!d) return
  editing.value = true
  editValues.value = {}
  editContent.value = d.content ?? ''
  editTitle.value = d.title
  editReason.value = d.reason
  actionError.value = null
}

async function saveEdit() {
  const d = detail.value
  if (!d) return
  if (!editTitle.value.trim()) {
    actionError.value = { message: '请填写变更标题' }
    return
  }
  if (editReason.value.trim().length && reasonInvalid(editReason.value)) {
    actionError.value = { code: 'CR-400-001', message: reasonInvalid(editReason.value) ?? '' }
    return
  }
  saving.value = true
  actionError.value = null
  try {
    const payload: Parameters<typeof changeApi.updateChange>[1] = {
      version: d.version,
      title: editTitle.value.trim(),
      reason: editReason.value.trim() || d.reason,
    }
    if (d.items?.length) {
      payload.items = d.items.map((i: ConfigItem) => ({
        key: i.key,
        value: editValues.value[i.key] !== undefined && editValues.value[i.key] !== '' ? editValues.value[i.key] : i.value,
      }))
    } else {
      payload.content = editContent.value
    }
    await changeApi.updateChange(d.changeNo, payload)
    ElMessage.success('草稿已保存')
    editing.value = false
    await store.fetchDetail(d.changeNo)
  } catch (e) {
    const err = e as ApiError
    if (err?.code === 'SYS-409-001') {
      actionError.value = { code: err.code, message: '版本冲突（SYS-409-001）：该单已被他人修改，请重新载入后再编辑', traceId: err.traceId }
    } else {
      actionError.value = toUiError(e)
    }
  } finally {
    saving.value = false
  }
}

/* ── 提交 / 取消 ── */
async function submitDraft() {
  const d = detail.value
  if (!d) return
  try {
    await ElMessageBox.confirm('提交后基线将冻结，进入审批流转。确认提交？', '提交审批', { type: 'warning' })
  } catch {
    return
  }
  actionError.value = null
  try {
    await changeApi.submitChange(d.changeNo)
    ElMessage.success('已提交审批')
    await store.fetchDetail(d.changeNo)
  } catch (e) {
    actionError.value = toUiError(e)
  }
}

async function cancelDraft() {
  const d = detail.value
  if (!d) return
  try {
    await ElMessageBox.confirm('取消后该变更单不再流转，草稿内容保留可查。确认取消？', '取消变更单', {
      type: 'warning',
      confirmButtonText: '确认取消',
      confirmButtonClass: 'el-button--danger',
    })
  } catch {
    return
  }
  actionError.value = null
  try {
    await changeApi.cancelChange(d.changeNo)
    ElMessage.success('变更单已取消')
    await store.fetchDetail(d.changeNo)
  } catch (e) {
    actionError.value = toUiError(e)
  }
}

/* ── 审批动作（通过 / 驳回 / 转交） ── */
const rejectVisible = ref(false)
const rejectReason = ref('')
const transferVisible = ref(false)
const transferTarget = ref('')
const transferReason = ref('')
const users = ref<UserAccount[]>([])
const usersLoaded = ref(false)

async function doApprove() {
  const d = detail.value
  if (!d) return
  try {
    await ElMessageBox.confirm('通过后变更单进入发布流程。确认通过？', '审批通过', { type: 'warning' })
  } catch {
    return
  }
  actionError.value = null
  try {
    await approvalApi.approveChange(d.changeNo)
    ElMessage.success('已通过')
    await store.fetchDetail(d.changeNo)
  } catch (e) {
    actionError.value = toUiError(e)
  }
}

const rejectReasonError = computed(() =>
  rejectReason.value.trim() ? reasonInvalid(rejectReason.value) : null,
)

async function doReject() {
  const d = detail.value
  if (!d) return
  const err = reasonInvalid(rejectReason.value)
  if (err) {
    actionError.value = { code: 'APR-422-001', message: err }
    return
  }
  actionError.value = null
  try {
    await approvalApi.rejectChange(d.changeNo, { reason: rejectReason.value.trim() })
    ElMessage.success('已驳回')
    rejectVisible.value = false
    rejectReason.value = ''
    await store.fetchDetail(d.changeNo)
  } catch (e) {
    actionError.value = toUiError(e)
  }
}

async function openTransfer() {
  transferVisible.value = true
  if (!usersLoaded.value) {
    try {
      const res = await authApi.listUsers()
      users.value = asList<UserAccount>(res)
      usersLoaded.value = true
    } catch {
      users.value = []
    }
  }
}

async function doTransfer() {
  const d = detail.value
  if (!d) return
  if (!transferTarget.value.trim()) {
    actionError.value = { message: '请填写转交对象' }
    return
  }
  actionError.value = null
  try {
    await approvalApi.transferChange(d.changeNo, {
      targetUserId: transferTarget.value.trim(),
      reason: transferReason.value.trim() || undefined,
    })
    ElMessage.success('已转交')
    transferVisible.value = false
    await store.fetchDetail(d.changeNo)
  } catch (e) {
    actionError.value = toUiError(e)
  }
}

/* ── 发布 / 回滚 ── */
const releaseResult = ref<ReleaseDetail | null>(null)
const releasing = ref(false)

async function doRelease() {
  const d = detail.value
  if (!d) return
  try {
    await ElMessageBox.confirm(
      `将向 ${appStore.envLabelOf(d.envId)} 环境发布 ${d.dataId}（版本 ${d.version}），发布后读回校验。确认执行？`,
      '执行发布',
      { type: 'warning' },
    )
  } catch {
    return
  }
  releasing.value = true
  actionError.value = null
  try {
    releaseResult.value = await releaseApi.releaseChange(d.changeNo)
    await store.fetchDetail(d.changeNo)
  } catch (e) {
    actionError.value = toUiError(e)
  } finally {
    releasing.value = false
  }
}

const rollbackVisible = ref(false)
const rollbackTarget = ref('')

async function doRollback() {
  const d = detail.value
  if (!d) return
  if (!rollbackTarget.value.trim()) {
    actionError.value = { message: '请填写回滚目标版本' }
    return
  }
  actionError.value = null
  try {
    await releaseApi.rollbackChange(d.changeNo, { targetVersion: rollbackTarget.value.trim() })
    ElMessage.success('回滚已执行')
    rollbackVisible.value = false
    await store.fetchDetail(d.changeNo)
  } catch (e) {
    actionError.value = toUiError(e)
  }
}

const releaseTone = (r: string) => (r === 'verified' ? 'volt' : r === 'rejected' ? 'red' : 'amber')
const releaseLabel = (r: string) =>
  r === 'verified' ? '已验证' : r === 'rejected' ? '已拒绝' : '不确定'

/* ── 状态机可达动作 ── */
const isDraft = computed(() => detail.value?.status === 'DRAFT')
const isPendingApproval = computed(() => detail.value?.status === 'PENDING_APPROVAL')
const canReleaseNow = computed(() =>
  ['APPROVED', 'PENDING_RELEASE'].includes(detail.value?.status ?? ''),
)
const isIndeterminate = computed(() => detail.value?.status === 'INDETERMINATE')
const isReleased = computed(() =>
  ['RELEASED', 'SUCCEEDED'].includes(detail.value?.status ?? ''),
)
const driftBlocked = computed(() => !!detail.value?.baselineDrift)
const impactBlocked = computed(() => store.impact?.blocked === true)
</script>

<template>
  <PageShell
    :title="detail ? `${detail.changeNo} · ${detail.title}` : '变更详情'"
    :description="detail ? `${changeTypeLabel(detail.changeType)} · ${detail.dataId} · ${appStore.envLabelOf(detail.envId)}` : ''"
    :loading="store.detailLoading"
    :error="store.detailError"
    @retry="store.fetchDetail(changeNo)"
  >
    <template #actions>
      <template v-if="detail">
        <el-button v-if="isDraft" @click="editing ? (editing = false) : startEdit()">
          {{ editing ? '退出编辑' : '编辑值' }}
        </el-button>
        <el-button v-if="isDraft && !editing" type="primary" @click="submitDraft">提交审批</el-button>
        <el-button v-if="isDraft && editing" type="primary" :loading="saving" @click="saveEdit">保存草稿</el-button>
        <el-button v-if="isDraft || isPendingApproval" type="danger" plain @click="cancelDraft">取消变更单</el-button>
        <template v-if="isPendingApproval">
          <el-button
            type="primary"
            :disabled="!guard.canApprove"
            :title="guard.why"
            @click="doApprove"
          >通过</el-button>
          <el-button type="danger" plain :disabled="!guard.canApprove" :title="guard.why" @click="rejectVisible = true">驳回</el-button>
          <el-button :disabled="!guard.canApprove" :title="guard.why" @click="openTransfer">转交</el-button>
        </template>
        <el-button
          v-if="canReleaseNow"
          type="primary"
          :disabled="driftBlocked || impactBlocked"
          :loading="releasing"
          @click="doRelease"
        >执行发布</el-button>
        <el-button v-if="isIndeterminate" @click="router.push({ name: 'releases' })">进入对账</el-button>
        <el-button v-if="isReleased" type="warning" plain @click="rollbackVisible = true">回滚</el-button>
      </template>
    </template>

    <template v-if="detail">
      <!-- 状态行 -->
      <div class="cd-head">
        <StatusTag :status="detail.status" />
        <StatusTag kind="risk" :risk="detail.riskLevel" />
        <el-tag size="small" effect="plain">{{ appStore.envLabelOf(detail.envId) }}</el-tag>
        <el-tag size="small" effect="plain">{{ changeTypeLabel(detail.changeType) }}</el-tag>
        <span class="ncg-muted">版本 {{ detail.version }}</span>
      </div>

      <!-- 基线漂移（MUST-05 / REL-412-001） -->
      <el-alert
        v-if="detail.baselineDrift"
        type="error"
        :closable="false"
        title="基线漂移（REL-412-001）"
        :description="`当前线上 MD5 ${detail.currentMd5 ?? '—'} 与冻结基线 ${detail.baselineMd5 ?? '—'} 不一致，发布入口已禁用；变更单退回待审批，请对照三方 Diff 重新评估。`"
      />

      <el-alert v-if="actionError" type="error" :closable="false" :title="actionError.message ?? '操作失败'"
        :description="actionError.code ? `错误码 ${actionError.code}${actionError.traceId ? ` · TraceId ${actionError.traceId}` : ''}` : ''" />

      <!-- 发布结果 -->
      <el-alert
        v-if="releaseResult"
        :type="releaseResult.result === 'verified' ? 'success' : releaseResult.result === 'rejected' ? 'error' : 'warning'"
        :closable="false"
        :title="`发布结果：${releaseLabel(releaseResult.result)}`"
        :description="`读回 MD5 ${releaseResult.readbackMd5 ?? '—'} · TraceId ${releaseResult.traceId ?? '—'}`"
      />

      <el-tabs v-model="activeTab">
        <!-- 概览 -->
        <el-tab-pane label="概览" name="overview">
          <div class="cd-kv">
            <div><span class="ncg-muted">变更单号</span><code class="ncg-mono">{{ detail.changeNo }}</code></div>
            <div><span class="ncg-muted">目标配置</span><code class="ncg-mono">{{ detail.dataId }}</code></div>
            <div><span class="ncg-muted">应用</span>{{ detail.appId }}</div>
            <div><span class="ncg-muted">环境</span>{{ appStore.envLabelOf(detail.envId) }}</div>
            <div><span class="ncg-muted">格式</span>{{ detail.format }}</div>
            <div><span class="ncg-muted">变更类型</span>{{ changeTypeLabel(detail.changeType) }}</div>
            <div><span class="ncg-muted">基线 MD5</span><code class="ncg-mono">{{ detail.baselineMd5 ?? '—' }}</code></div>
            <div><span class="ncg-muted">当前 MD5</span><code class="ncg-mono">{{ detail.currentMd5 ?? '—' }}</code></div>
            <div><span class="ncg-muted">创建时间</span>{{ fmtTime(detail.createdAt) }}</div>
            <div v-if="detail.plannedAt"><span class="ncg-muted">计划发布</span>{{ fmtTime(detail.plannedAt) }}</div>
            <div class="cd-kv__full"><span class="ncg-muted">变更原因</span>{{ detail.reason }}</div>
          </div>

          <div class="cd-timeline">
            <div class="cd-section-title">状态迁移（仅后端驱动）</div>
            <el-timeline>
              <el-timeline-item
                v-for="s in ['DRAFT', 'PENDING_APPROVAL', 'APPROVED', 'PENDING_RELEASE', 'RELEASED']"
                :key="s"
                :type="s === detail.status ? 'primary' : ''"
                :hollow="s !== detail.status"
              >
                {{ statusLabel(s) }}
                <el-tag v-if="s === detail.status" size="small" effect="plain">当前</el-tag>
              </el-timeline-item>
            </el-timeline>
            <div v-if="detail.status && !['DRAFT','PENDING_APPROVAL','APPROVED','PENDING_RELEASE','RELEASED'].includes(detail.status)"
              class="ncg-muted">
              当前状态：{{ statusLabel(detail.status) }}
            </div>
          </div>

          <!-- 影响摘要 -->
          <div v-if="store.impact" class="cd-impact-summary">
            <div class="cd-section-title">影响摘要</div>
            <div class="cd-impact-stats">
              <div class="cd-stat"><div class="cd-stat__label">订阅实例</div><div class="cd-stat__value">{{ store.impact.subscribers.length }}</div></div>
              <div class="cd-stat"><div class="cd-stat__label">未知影响</div><div class="cd-stat__value" :class="store.impact.unknownCount ? 'cd-stat__value--amber' : ''">{{ store.impact.unknownCount }}</div></div>
              <div class="cd-stat"><div class="cd-stat__label">风险等级</div><div class="cd-stat__value">{{ store.impact.riskLevel }}</div></div>
              <div class="cd-stat"><div class="cd-stat__label">发布阻断</div><div class="cd-stat__value" :class="store.impact.blocked ? 'cd-stat__value--red' : ''">{{ store.impact.blocked ? '是' : '否' }}</div></div>
            </div>
          </div>
        </el-tab-pane>

        <!-- Diff -->
        <el-tab-pane label="Diff" name="diff">
          <el-alert
            v-if="detail.baselineDrift"
            type="warning"
            :closable="false"
            title="三方 Diff：基线 / 当前 / 目标"
            description="基线逐项值 API 未下发（不臆造，MUST-NOT-10）：基线列显示「—」，逐项基线以 baselineMd5 冻结快照为准。"
          />
          <DiffView :rows="diffRows" :mode="detail.baselineDrift ? 'three' : 'pair'" />
        </el-tab-pane>

        <!-- 影响面 -->
        <el-tab-pane label="影响面" name="impact">
          <ErrorState
            v-if="store.impactError"
            :code="store.impactError.code"
            :message="store.impactError.message"
            :trace-id="store.impactError.traceId"
            @retry="store.fetchImpact(changeNo)"
          />
          <template v-else-if="store.impact">
            <el-alert
              v-if="store.impact.unknownCount > 0"
              type="warning"
              :closable="false"
              title="影响面未知（IMP-409-001）"
              description="存在未纳管实例，风险上调一级；高风险发布被阻断，请先纳管实例或经应用负责人评估。"
            />
            <el-table :data="store.impact.subscribers" size="small">
              <el-table-column label="实例" min-width="180">
                <template #default="{ row }"><code class="ncg-mono">{{ row.instanceId }}</code></template>
              </el-table-column>
              <el-table-column label="应用" min-width="120">
                <template #default="{ row }">{{ row.appName ?? '—' }}</template>
              </el-table-column>
              <el-table-column label="负责人" width="120">
                <template #default="{ row }">{{ row.owner ?? '—' }}</template>
              </el-table-column>
              <el-table-column label="纳管" width="120">
                <template #default="{ row }">
                  <el-tag v-if="row.unknown" size="small" type="warning" effect="plain">未知（检测盲区）</el-tag>
                  <el-tag v-else size="small" type="success" effect="plain">已纳管</el-tag>
                </template>
              </el-table-column>
            </el-table>
          </template>
        </el-tab-pane>

        <!-- 审批 -->
        <el-tab-pane label="审批" name="approval">
          <div class="cd-section-title">审批守卫（MUST-06 职责分离）</div>
          <el-alert
            v-if="!guard.canApprove"
            type="info"
            :closable="false"
            title="你当前不能审批该变更单"
            :description="guard.why ?? '提交人 / 发布人不得审批；高风险仅应用负责人或审计过的代理人可审批。'"
          />
          <div class="ncg-muted cd-approval-note">
            审批前请查看 Diff 与影响面页签。驳回必须填写原因（≥10 字，APR-422-001）。不设审批超时，不自动驳回。
          </div>
        </el-tab-pane>
      </el-tabs>

      <!-- 驳回弹窗 -->
      <el-dialog v-model="rejectVisible" title="驳回变更" width="min(520px, 94vw)">
        <el-form label-position="top">
          <el-form-item label="驳回原因" required :error="rejectReasonError ?? ''">
            <el-input v-model="rejectReason" type="textarea" :rows="4" maxlength="500" placeholder="不少于 10 个字；将写入审批记录" />
            <div class="ncg-muted cd-reason-count">{{ rejectReason.trim().length }} / {{ REASON_MIN }} 字起</div>
          </el-form-item>
        </el-form>
        <template #footer>
          <el-button @click="rejectVisible = false">取消</el-button>
          <el-button type="danger" :disabled="!!rejectReasonError || rejectReason.trim().length < REASON_MIN" @click="doReject">确认驳回</el-button>
        </template>
      </el-dialog>

      <!-- 转交弹窗 -->
      <el-dialog v-model="transferVisible" title="转交审批" width="min(520px, 94vw)">
        <el-form label-position="top">
          <el-form-item label="转交对象" required>
            <el-select
              v-if="users.length"
              v-model="transferTarget"
              filterable
              placeholder="选择用户"
            >
              <el-option
                v-for="u in users"
                :key="u.userId"
                :label="`${u.displayName}（${u.username}）`"
                :value="u.userId"
              />
            </el-select>
            <el-input v-else v-model="transferTarget" placeholder="填写目标用户 ID" />
          </el-form-item>
          <el-form-item label="转交说明（可选）">
            <el-input v-model="transferReason" type="textarea" :rows="3" maxlength="500" />
          </el-form-item>
        </el-form>
        <template #footer>
          <el-button @click="transferVisible = false">取消</el-button>
          <el-button type="primary" :disabled="!transferTarget.trim()" @click="doTransfer">确认转交</el-button>
        </template>
      </el-dialog>

      <!-- 回滚弹窗 -->
      <el-dialog v-model="rollbackVisible" title="回滚" width="min(520px, 94vw)">
        <el-alert type="warning" :closable="false" title="回滚将生成新版本发布记录（ROLLBACK）"
          description="目标版本为回滚到的配置版本号；执行前请确认影响面，回滚同样经过读回校验。" />
        <el-form label-position="top">
          <el-form-item label="回滚目标版本" required>
            <el-input v-model="rollbackTarget" placeholder="例如 3" />
          </el-form-item>
        </el-form>
        <template #footer>
          <el-button @click="rollbackVisible = false">取消</el-button>
          <el-button type="warning" :disabled="!rollbackTarget.trim()" @click="doRollback">执行回滚</el-button>
        </template>
      </el-dialog>
    </template>

    <!-- 草稿编辑态 -->
    <template v-if="editing && detail">
      <div class="cd-edit">
        <div class="cd-section-title">编辑草稿值（保存后版本递增）</div>
        <ChangeValueEditor
          :mode="detail.items?.length ? 'items' : 'content'"
          :items="detail.items ?? []"
          :values="editValues"
          :content="editContent"
          :disabled="saving"
          @update:values="editValues = $event"
          @update:content="editContent = $event"
        />
        <el-form label-position="top">
          <el-form-item label="变更标题">
            <el-input v-model="editTitle" maxlength="100" />
          </el-form-item>
          <el-form-item label="变更原因" :error="editReason.trim().length ? reasonInvalid(editReason) ?? '' : ''">
            <el-input v-model="editReason" type="textarea" :rows="3" maxlength="500" />
          </el-form-item>
        </el-form>
      </div>
    </template>
  </PageShell>
</template>

<style scoped>
.cd-head {
  display: flex;
  gap: 8px;
  align-items: center;
  flex-wrap: wrap;
  margin-bottom: var(--ncg-space-2);
}
.cd-kv {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 8px var(--ncg-space-3);
  font-size: var(--ncg-fs-sm);
}
.cd-kv > div {
  display: flex;
  gap: 8px;
}
.cd-kv .ncg-muted {
  min-width: 72px;
}
.cd-kv__full {
  grid-column: 1 / -1;
}
.cd-section-title {
  font-size: var(--ncg-fs-xs);
  color: var(--ncg-text-2);
  text-transform: uppercase;
  letter-spacing: 0.08em;
  margin: var(--ncg-space-2) 0 var(--ncg-space-1);
}
.cd-impact-stats {
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  gap: var(--ncg-space-2);
}
.cd-stat {
  border: 1px solid var(--ncg-line);
  border-radius: var(--ncg-radius-md);
  padding: var(--ncg-space-2);
}
.cd-stat__label {
  font-size: var(--ncg-fs-xs);
  color: var(--ncg-text-2);
}
.cd-stat__value {
  font-family: var(--ncg-font-mono);
  font-size: var(--ncg-fs-lg);
}
.cd-stat__value--amber { color: var(--ncg-amber); }
.cd-stat__value--red { color: var(--ncg-red); }
.cd-approval-note {
  font-size: var(--ncg-fs-xs);
  margin-top: var(--ncg-space-2);
}
.cd-reason-count {
  font-size: var(--ncg-fs-xs);
  margin-top: 4px;
}
.cd-edit {
  margin-top: var(--ncg-space-3);
  border-top: 1px solid var(--ncg-line);
  padding-top: var(--ncg-space-2);
}
</style>
