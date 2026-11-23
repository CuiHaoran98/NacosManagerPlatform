<script setup lang="ts">
/**
 * ApprovalsView —— 审批中心（P-07）
 * 通过 / 驳回（原因≥10 字，APR-422-001）/ 转交；提交人不可审批（AUTHZ-403-002）；
 * 高风险仅应用负责人或审计过的代理人（AUTHZ-403-003）。无权限动作不渲染。
 */
import { computed, onMounted, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import 'element-plus/es/components/message/style/css'
import 'element-plus/es/components/message-box/style/css'
import 'element-plus/es/components/alert/style/css'
import 'element-plus/es/components/dialog/style/css'
import 'element-plus/es/components/input/style/css'
import 'element-plus/es/components/select/style/css'
import 'element-plus/es/components/option/style/css'
import 'element-plus/es/components/table/style/css'
import 'element-plus/es/components/table-column/style/css'
import 'element-plus/es/components/tag/style/css'
import { approvalApi, authApi, changeApi } from '@/api'
import type { ChangeListItem, UserAccount } from '@/api/types'
import { useAuthStore } from '@/stores/auth'
import { useAppStore } from '@/stores/app'
import { useCatalogStore } from '@/stores/catalog'
import {
  asList,
  approveGuard,
  fmtTime,
  reasonInvalid,
  REASON_MIN,
  toUiError,
  type UiError,
} from '@/stores/changes'
import PageShell from '@/components/PageShell.vue'
import TableToolbar from '@/components/TableToolbar.vue'
import EmptyState from '@/components/EmptyState.vue'
import ErrorState from '@/components/ErrorState.vue'
import StatusTag from '../changes/StatusTag.vue'

const router = useRouter()
const auth = useAuthStore()
const appStore = useAppStore()
const catalog = useCatalogStore()

const keyword = ref('')
const list = ref<ChangeListItem[]>([])
const loading = ref(false)
const error = ref<UiError | null>(null)
const actionError = ref<UiError | null>(null)

async function load() {
  loading.value = true
  error.value = null
  try {
    const res = await changeApi.listChanges({
      status: 'PENDING_APPROVAL',
      keyword: keyword.value.trim() || undefined,
      page: 0,
      size: 100,
    })
    list.value = asList<ChangeListItem>(res)
  } catch (e) {
    error.value = toUiError(e)
  } finally {
    loading.value = false
  }
}

watch(keyword, () => void load())
onMounted(async () => {
  if (!catalog.apps.length) void catalog.fetchApps()
  await load()
})

/* ── 职责分离守卫（MUST-06） ── */
const meIds = computed(() => {
  const u = auth.user
  return u ? [u.userId, u.username, u.displayName] : []
})
const isMine = (createdBy: string) => meIds.value.includes(createdBy)

function guardOf(row: ChangeListItem) {
  const app = catalog.apps.find((a) => a.appId === row.appId)
  return approveGuard({
    createdBy: row.createdBy,
    riskLevel: row.riskLevel,
    appOwner: app?.owner,
  })
}

/* ── 通过 / 驳回 / 转交 ── */
const rejectVisible = ref(false)
const rejectRow = ref<ChangeListItem | null>(null)
const rejectReason = ref('')
const rejectReasonError = computed(() =>
  rejectReason.value.trim() ? reasonInvalid(rejectReason.value) : null,
)

const transferVisible = ref(false)
const transferRow = ref<ChangeListItem | null>(null)
const transferTarget = ref('')
const transferReason = ref('')
const users = ref<UserAccount[]>([])
const usersLoaded = ref(false)

async function doApprove(row: ChangeListItem) {
  try {
    await ElMessageBox.confirm(`通过后变更单 ${row.changeNo} 进入发布流程。确认通过？`, '审批通过', {
      type: 'warning',
    })
  } catch {
    return
  }
  actionError.value = null
  try {
    await approvalApi.approveChange(row.changeNo)
    ElMessage.success('已通过')
    await load()
  } catch (e) {
    actionError.value = toUiError(e)
  }
}

function openReject(row: ChangeListItem) {
  rejectRow.value = row
  rejectReason.value = ''
  rejectVisible.value = true
}

async function doReject() {
  if (!rejectRow.value) return
  const err = reasonInvalid(rejectReason.value)
  if (err) {
    actionError.value = { code: 'APR-422-001', message: err }
    return
  }
  actionError.value = null
  try {
    await approvalApi.rejectChange(rejectRow.value.changeNo, { reason: rejectReason.value.trim() })
    ElMessage.success('已驳回')
    rejectVisible.value = false
    await load()
  } catch (e) {
    actionError.value = toUiError(e)
  }
}

async function openTransfer(row: ChangeListItem) {
  transferRow.value = row
  transferTarget.value = ''
  transferReason.value = ''
  transferVisible.value = true
  if (!usersLoaded.value) {
    try {
      users.value = asList<UserAccount>(await authApi.listUsers())
      usersLoaded.value = true
    } catch {
      users.value = []
    }
  }
}

async function doTransfer() {
  if (!transferRow.value) return
  if (!transferTarget.value.trim()) {
    actionError.value = { message: '请填写转交对象' }
    return
  }
  actionError.value = null
  try {
    await approvalApi.transferChange(transferRow.value.changeNo, {
      targetUserId: transferTarget.value.trim(),
      reason: transferReason.value.trim() || undefined,
    })
    ElMessage.success('已转交')
    transferVisible.value = false
    await load()
  } catch (e) {
    actionError.value = toUiError(e)
  }
}

function openChange(changeNo: string) {
  router.push({ name: 'change-detail', params: { changeNo } })
}
</script>

<template>
  <PageShell title="审批中心" description="待审批变更单；提交人 / 发布人不得审批（MUST-06 职责分离）">
    <template #actions>
      <el-button :loading="loading" @click="load">刷新</el-button>
    </template>

    <TableToolbar v-model:search-value="keyword" search-placeholder="搜索变更单号 / 标题 / DataId" :loading="loading" @search="load">
      <template #actions>
        <span />
      </template>
    </TableToolbar>

    <!-- 职责分离说明卡 -->
    <el-alert type="info" :closable="false" title="职责分离检查（MUST-06）">
      <template #default>
        提交人不得审批自己的变更单；发布人不得审批，例外为发布人同时是应用负责人；高风险变更仅应用负责人或审计过的代理人可审批。
        审批前请查看完整 Diff、影响面、历史发布/回滚、提交人与风险等级；不设审批超时，不自动驳回。
      </template>
    </el-alert>

    <el-alert v-if="actionError" type="error" :closable="false" :title="actionError.message ?? '操作失败'"
      :description="actionError.code ? `错误码 ${actionError.code}${actionError.traceId ? ` · TraceId ${actionError.traceId}` : ''}` : ''" />

    <ErrorState v-if="error" :code="error.code" :message="error.message" :trace-id="error.traceId" @retry="load" />
    <EmptyState
      v-else-if="!list.length"
      reason="当前没有待审批的变更单"
      next-step="新的变更单提交后会出现在这里；提交人的单据不会出现在其自己的待办中"
    />
    <el-table v-else :data="list" size="small">
      <el-table-column label="变更单" min-width="240">
        <template #default="{ row }">
          <div class="apv-title">
            <code class="ncg-mono">{{ row.changeNo }}</code>
            <span>{{ row.title }}</span>
          </div>
        </template>
      </el-table-column>
      <el-table-column label="目标配置" min-width="160">
        <template #default="{ row }">
          <code class="ncg-mono">{{ row.dataId }}</code>
          <div class="ncg-muted ncg-truncate">{{ row.appName ?? row.appId }}</div>
        </template>
      </el-table-column>
      <el-table-column label="环境 / 风险" width="160">
        <template #default="{ row }">
          <el-tag size="small" effect="plain">{{ appStore.envLabelOf(row.envId) }}</el-tag>
          <StatusTag kind="risk" :risk="row.riskLevel" />
        </template>
      </el-table-column>
      <el-table-column label="提交人" width="170">
        <template #default="{ row }">
          {{ row.createdBy }}
          <el-tag v-if="isMine(row.createdBy)" size="small" type="info" effect="plain">你提交的，不能审批</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="创建时间" width="160">
        <template #default="{ row }">{{ fmtTime(row.createdAt) }}</template>
      </el-table-column>
      <el-table-column label="操作" width="270">
        <template #default="{ row }">
          <el-button size="small" text type="primary" @click="openChange(row.changeNo)">审阅 →</el-button>
          <template v-if="guardOf(row as ChangeListItem).canApprove">
            <el-button size="small" type="primary" plain @click="doApprove(row as ChangeListItem)">通过</el-button>
            <el-button size="small" type="danger" plain @click="openReject(row as ChangeListItem)">驳回</el-button>
            <el-button size="small" plain @click="openTransfer(row as ChangeListItem)">转交</el-button>
          </template>
          <el-tooltip v-else :content="guardOf(row as ChangeListItem).why ?? '职责分离：你不能审批该单'" placement="top">
            <span class="apv-guard-hint ncg-muted">不可审批</span>
          </el-tooltip>
        </template>
      </el-table-column>
    </el-table>

    <!-- 驳回弹窗 -->
    <el-dialog v-model="rejectVisible" title="驳回变更" width="min(520px, 94vw)">
      <div class="ncg-muted apv-dialog-note">
        变更单 {{ rejectRow?.changeNo }}：驳回必须填写原因（≥10 字，APR-422-001），将写入审批记录。
      </div>
      <el-form label-position="top">
        <el-form-item label="驳回原因" required :error="rejectReasonError ?? ''">
          <el-input v-model="rejectReason" type="textarea" :rows="4" maxlength="500" placeholder="不少于 10 个字" />
          <div class="ncg-muted apv-reason-count">{{ rejectReason.trim().length }} / {{ REASON_MIN }} 字起</div>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="rejectVisible = false">取消</el-button>
        <el-button
          type="danger"
          :disabled="!!rejectReasonError || rejectReason.trim().length < REASON_MIN"
          @click="doReject"
        >确认驳回</el-button>
      </template>
    </el-dialog>

    <!-- 转交弹窗 -->
    <el-dialog v-model="transferVisible" title="转交审批" width="min(520px, 94vw)">
      <div class="ncg-muted apv-dialog-note">转交对象将接替你成为该单审批人（AUTHZ-403-001）。</div>
      <el-form label-position="top">
        <el-form-item label="转交对象" required>
          <el-select v-if="users.length" v-model="transferTarget" filterable placeholder="选择用户">
            <el-option v-for="u in users" :key="u.userId" :label="`${u.displayName}（${u.username}）`" :value="u.userId" />
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
  </PageShell>
</template>

<style scoped>
.apv-title {
  display: flex;
  gap: 8px;
  align-items: baseline;
}
.apv-guard-hint {
  font-size: var(--ncg-fs-xs);
}
.apv-dialog-note {
  font-size: var(--ncg-fs-xs);
  margin-bottom: var(--ncg-space-1);
}
.apv-reason-count {
  font-size: var(--ncg-fs-xs);
  margin-top: 4px;
}
</style>
