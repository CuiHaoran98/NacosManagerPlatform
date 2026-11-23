<script setup lang="ts">
/**
 * ChangesView —— 变更单列表（P-04，契约 §2.1(16)）
 * 筛选 status/keyword/envId；行内基线漂移 pill（MUST-05）；新建变更入口（EDIT 权限）。
 */
import { onMounted, reactive, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { changeApi } from '@/api'
import type { ChangeListItem } from '@/api/types'
import { useAppStore } from '@/stores/app'
import { useAuthStore } from '@/stores/auth'
import {
  asList,
  fmtTime,
  changeTypeLabel,
  type UiError,
  toUiError,
} from '@/stores/changes'
import { useChangesStore } from '@/stores/changes'
import PageShell from '@/components/PageShell.vue'
import TableToolbar from '@/components/TableToolbar.vue'
import EmptyState from '@/components/EmptyState.vue'
import ErrorState from '@/components/ErrorState.vue'
import StatusTag from './StatusTag.vue'
import ChangeCreateDialog from './ChangeCreateDialog.vue'

const router = useRouter()
const appStore = useAppStore()
const auth = useAuthStore()
const store = useChangesStore()

const filters = reactive<{ keyword: string; status: string; envId: string }>({
  keyword: '',
  status: '',
  envId: '',
})

const list = ref<ChangeListItem[]>([])
const loading = ref(false)
const error = ref<UiError | null>(null)

async function load() {
  loading.value = true
  error.value = null
  try {
    const res = await changeApi.listChanges({
      keyword: filters.keyword.trim() || undefined,
      status: filters.status || undefined,
      envId: filters.envId || undefined,
      page: 0,
      size: 100,
    })
    list.value = asList<ChangeListItem>(res)
    store.list = list.value
    store.listError = null
  } catch (e) {
    error.value = toUiError(e)
    store.listError = error.value
  } finally {
    loading.value = false
    store.listLoading = loading.value
  }
}

watch(filters, () => void load(), { deep: true })
onMounted(load)

const canCreate = () => auth.can('EDIT') && !appStore.readOnly
const createVisible = ref(false)

function open(changeNo: string) {
  router.push({ name: 'change-detail', params: { changeNo } })
}
</script>

<template>
  <PageShell title="变更单" description="草稿 → 待审批 → 已通过 → 发布 → 终态；状态只由后端给">
    <template #actions>
      <el-button type="primary" :disabled="!canCreate()" @click="createVisible = true">＋ 新建变更</el-button>
    </template>

    <TableToolbar v-model:search-value="filters.keyword" search-placeholder="搜索变更单号 / 标题 / DataId" :loading="loading" @search="load" @refresh="load">
      <template #filters>
        <el-select v-model="filters.status" placeholder="全部状态" clearable style="width: 160px">
          <el-option label="草稿" value="DRAFT" />
          <el-option label="待审批" value="PENDING_APPROVAL" />
          <el-option label="已通过" value="APPROVED" />
          <el-option label="待发布" value="PENDING_RELEASE" />
          <el-option label="已定时" value="SCHEDULED" />
          <el-option label="已发布" value="RELEASED" />
          <el-option label="已驳回" value="REJECTED" />
          <el-option label="已取消" value="CANCELED" />
          <el-option label="结果不确定" value="INDETERMINATE" />
        </el-select>
        <el-select v-model="filters.envId" placeholder="全部环境" clearable style="width: 130px">
          <el-option v-for="e in appStore.envIdOptions" :key="e.value" :label="e.label" :value="e.value" />
        </el-select>
      </template>
    </TableToolbar>

    <ErrorState
      v-if="error"
      :code="error.code"
      :message="error.message"
      :trace-id="error.traceId"
      @retry="load"
    />
    <EmptyState
      v-else-if="!list.length"
      reason="没有符合条件的变更单"
      next-step="调整筛选条件，或从配置目录选择配置发起变更"
    />
    <el-table v-else :data="list" size="small" @row-click="(r: ChangeListItem) => open(r.changeNo)">
      <el-table-column label="变更单" min-width="260">
        <template #default="{ row }">
          <div class="chg-row-title">
            <code class="ncg-mono">{{ row.changeNo }}</code>
            <span>{{ row.title }}</span>
            <el-tag size="small" effect="plain">{{ changeTypeLabel(row.changeType) }}</el-tag>
          </div>
        </template>
      </el-table-column>
      <el-table-column label="目标配置" min-width="180">
        <template #default="{ row }">
          <code class="ncg-mono">{{ row.dataId }}</code>
          <div class="ncg-muted ncg-truncate">{{ row.appName ?? row.appId }}</div>
        </template>
      </el-table-column>
      <el-table-column label="环境" width="90">
        <template #default="{ row }"><el-tag size="small" effect="plain">{{ appStore.envLabelOf(row.envId) }}</el-tag></template>
      </el-table-column>
      <el-table-column label="状态" width="150">
        <template #default="{ row }">
          <div class="chg-status">
            <StatusTag :status="row.status" />
            <el-tag v-if="row.baselineDrift" size="small" type="danger" effect="plain">基线漂移</el-tag>
          </div>
        </template>
      </el-table-column>
      <el-table-column label="风险" width="100">
        <template #default="{ row }"><StatusTag kind="risk" :risk="row.riskLevel" /></template>
      </el-table-column>
      <el-table-column label="提交人" width="110">
        <template #default="{ row }">{{ row.createdBy }}</template>
      </el-table-column>
      <el-table-column label="计划发布" width="160">
        <template #default="{ row }">{{ row.plannedAt ? fmtTime(row.plannedAt) : '—' }}</template>
      </el-table-column>
      <el-table-column label="创建时间" width="160">
        <template #default="{ row }">{{ fmtTime(row.createdAt) }}</template>
      </el-table-column>
    </el-table>

    <ChangeCreateDialog v-model:visible="createVisible" @created="(no: string) => open(no)" />
  </PageShell>
</template>

<style scoped>
.chg-row-title {
  display: flex;
  gap: 8px;
  align-items: baseline;
}
.chg-status {
  display: flex;
  gap: 6px;
  align-items: center;
  flex-wrap: wrap;
}
</style>
