<script setup lang="ts">
/**
 * 回收站（P-12，契约 #45 列表 / #46 恢复）
 * 软删除保留 30 天（RB-410-001 超期不可恢复）；恢复必须写 TongNCS 并读回；
 * 同名冲突（RB-409-001）只允许“覆盖并重新审批”或“放弃”，禁止静默覆盖。
 */
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import 'element-plus/es/components/message/style/css'
import PageShell from '@/components/PageShell.vue'
import EmptyState from '@/components/EmptyState.vue'
import TableToolbar from '@/components/TableToolbar.vue'
import { useAppStore } from '@/stores/app'
import { useAuthStore } from '@/stores/auth'
import { useRecycleBinStore, daysLeft } from '@/stores/recycle-bin'
import { toErrorState, fmtTime, type ErrorState } from '@/stores/schedules'
import type { RecycleBinItem } from '@/api/types'

const store = useRecycleBinStore()
const auth = useAuthStore()
const app = useAppStore()

const canRestore = computed(() => auth.can('PUBLISH'))

/** 错误文案按 code 分支（§4.2 / §6.6），不解析 message */
const CODE_COPY: Record<string, string> = {
  'RB-409-001': '目标环境已存在同名配置：请选择“覆盖并重新审批”或“放弃”',
  'RB-410-001': '超过 30 天保留期，不可恢复',
  'REL-503-001': '实例不可用或连接失败，请检查 TongNCS 实例后重试',
  'SYS-404-001': '对象不存在或已删除',
  'SYS-500-001': '请求失败，请稍后重试',
}

function codeText(e: ErrorState): string {
  return CODE_COPY[e.code] ?? e.message
}

/* ---------- 筛选（page 从 0 开始） ---------- */

const filters = reactive<{ appId: string; envId: string; keyword: string }>({
  appId: '',
  envId: '',
  keyword: '',
})

function applyFilters() {
  store.filters = {
    ...store.filters,
    appId: filters.appId.trim() || undefined,
    envId: filters.envId.trim() || undefined,
    keyword: filters.keyword.trim() || undefined,
    page: 0,
  }
  store.fetch()
}

function onPageChange(p: number) {
  store.filters = { ...store.filters, page: p - 1 } // el-pagination 从 1 起，接口 page 从 0 起
  store.fetch()
}

/* ---------- 恢复流程：确认恢复 → [同名冲突?] → 覆盖并重新审批 / 放弃 ---------- */

const confirmVisible = ref(false)
const confirmTarget = ref<RecycleBinItem | null>(null)
const actionError = ref<ErrorState | null>(null)

const conflictVisible = ref(false)
const conflictTarget = ref<RecycleBinItem | null>(null)

function openConfirm(item: RecycleBinItem) {
  actionError.value = null
  confirmTarget.value = item
  confirmVisible.value = true
}

async function submitRestore() {
  const item = confirmTarget.value
  if (!item) return
  actionError.value = null
  try {
    // 首次调用绝不静默覆盖：onConflict='ABANDON'
    const resp = await store.restore(item.itemId, 'ABANDON')
    if (resp.conflict) {
      confirmVisible.value = false
      conflictTarget.value = item
      conflictVisible.value = true
      return
    }
    ElMessage.success(
      `已恢复 ${item.dataId} 到 ${app.envLabelOf(item.envId)}：已写入 TongNCS 并读回校验${
        resp.changeNo ? `，发布记录 ${resp.changeNo}` : ''
      }`,
    )
    confirmVisible.value = false
    store.fetch()
  } catch (e) {
    const err = toErrorState(e)
    if (err.code === 'RB-409-001') {
      // 同名冲突：只给“覆盖并重新审批 / 放弃”两个选项
      confirmVisible.value = false
      conflictTarget.value = item
      conflictVisible.value = true
      return
    }
    actionError.value = err // RB-410-001 / REL-503-001 等，保留弹窗与上下文
  }
}

async function submitOverride() {
  const item = conflictTarget.value
  if (!item) return
  actionError.value = null
  try {
    const resp = await store.restore(item.itemId, 'OVERRIDE_AND_REAPPROVE')
    ElMessage.success(
      `已创建覆盖变更单${resp.changeNo ? ` ${resp.changeNo}` : ''}，需重新审批后才能恢复`,
    )
    conflictVisible.value = false
    store.fetch()
  } catch (e) {
    actionError.value = toErrorState(e)
    conflictVisible.value = false
  }
}

function abandon() {
  conflictVisible.value = false
  ElMessage.info('已放弃恢复，目标环境内容不变')
}

onMounted(() => {
  store.fetch()
})
</script>

<template>
  <PageShell
    title="回收站"
    description="删除为软删除，保留 30 天。恢复必须写 TongNCS 并读回；同名冲突只能“覆盖并重新审批”或放弃。"
    :loading="store.loading"
    :error="store.error"
    @retry="store.fetch()"
  >
    <TableToolbar
      v-model:search-value="filters.keyword"
      search-placeholder="按 DataId / AppId 搜索"
      :loading="store.loading"
      @search="applyFilters"
      @refresh="applyFilters"
    >
      <template #filters>
        <el-input
          v-model="filters.appId"
          placeholder="AppId（可选）"
          class="filter-item mono"
          @change="applyFilters"
        />
        <el-select
          v-model="filters.envId"
          placeholder="环境（全部）"
          clearable
          class="filter-item"
          @change="applyFilters"
        >
          <el-option v-for="e in app.envIdOptions" :key="e.value" :label="e.label" :value="e.value" />
        </el-select>
      </template>
    </TableToolbar>

    <template v-if="store.items.length">
      <el-row :gutter="12">
        <el-col v-for="it in store.items" :key="it.itemId" :span="8">
          <el-card shadow="never" class="rb-card">
            <div class="rb-head">
              <span class="rb-label mono">{{ it.appId }} · {{ app.envLabelOf(it.envId) }}</span>
              <el-tag
                :type="daysLeft(it.expireAt) > 7 ? 'info' : 'danger'"
                effect="plain"
                round
                size="small"
              >
                剩 {{ daysLeft(it.expireAt) }} 天
              </el-tag>
            </div>
            <div class="rb-dataid mono">{{ it.dataId }}</div>
            <div class="ncg-sub">
              删除于 {{ fmtTime(it.deletedAt) }} · by {{ it.deletedBy }}
            </div>
            <div class="rb-foot">
              <el-tag v-if="daysLeft(it.expireAt) <= 0" type="danger" effect="plain" round size="small">
                已过期 · RB-410-001 不可恢复
              </el-tag>
              <span v-else class="ncg-sub">保留至 {{ fmtTime(it.expireAt) }}</span>
              <el-button
                v-if="canRestore"
                type="primary"
                size="small"
                :disabled="app.readOnly || daysLeft(it.expireAt) <= 0 || !!store.restoring[it.itemId]"
                :loading="!!store.restoring[it.itemId]"
                @click="openConfirm(it)"
              >
                恢复
              </el-button>
            </div>
          </el-card>
        </el-col>
      </el-row>

      <el-pagination
        class="ncg-pager"
        layout="total, prev, pager, next"
        :total="store.total"
        :page-size="store.filters.size ?? 20"
        :current-page="(store.filters.page ?? 0) + 1"
        @current-change="onPageChange"
      />
    </template>

    <EmptyState
      v-else
      reason="回收站为空"
      next-step="删除的配置会保留 30 天，可在此恢复并写入 TongNCS 读回校验；超期自动清理。"
    />
  </PageShell>

  <!-- 确认恢复（写入 + 读回三段式） -->
  <el-dialog v-model="confirmVisible" title="确认恢复" width="520px">
    <div class="confirm-copy">
      将写入 {{ app.envLabelOf(confirmTarget?.envId ?? '') }} 的 TongNCS 并立即读回校验，生成新的发布记录。
    </div>
    <div class="ncg-kv">
      <div><span>配置</span><b class="mono">{{ confirmTarget?.dataId }}</b></div>
      <div>
        <span>剩余保留</span><b>{{ confirmTarget ? daysLeft(confirmTarget.expireAt) : '—' }} 天</b>
      </div>
    </div>
    <div v-if="actionError" class="ncg-form-error" role="alert">
      <div><b class="mono">{{ actionError.code }}</b> {{ codeText(actionError) }}</div>
      <div v-if="actionError.traceId" class="ncg-sub mono">TraceId {{ actionError.traceId }}</div>
    </div>
    <template #footer>
      <el-button @click="confirmVisible = false">取消</el-button>
      <el-button
        type="primary"
        :loading="!!(confirmTarget && store.restoring[confirmTarget.itemId])"
        @click="submitRestore"
      >
        确认恢复
      </el-button>
    </template>
  </el-dialog>

  <!-- 同名冲突：覆盖并重新审批 / 放弃（禁止静默覆盖） -->
  <el-dialog v-model="conflictVisible" title="恢复冲突：同名配置已存在" width="520px">
    <el-alert type="warning" :closable="false" class="ncg-banner">
      <template #title>
        不能静默覆盖 RB-409-001：目标环境已存在同名配置。请选择覆盖并重新审批，或放弃恢复。
      </template>
    </el-alert>
    <div class="ncg-kv">
      <div><span>待恢复</span><b class="mono">{{ conflictTarget?.dataId }}</b></div>
      <div><span>环境</span><b class="mono">{{ app.envLabelOf(conflictTarget?.envId ?? '') }}</b></div>
      <div><span>已存在</span><b>同名配置 · 当前生效</b></div>
      <div>
        <span>剩余保留</span><b>{{ conflictTarget ? daysLeft(conflictTarget.expireAt) : '—' }} 天</b>
      </div>
    </div>
    <div v-if="actionError" class="ncg-form-error" role="alert">
      <div><b class="mono">{{ actionError.code }}</b> {{ codeText(actionError) }}</div>
      <div v-if="actionError.traceId" class="ncg-sub mono">TraceId {{ actionError.traceId }}</div>
    </div>
    <template #footer>
      <el-button @click="abandon">放弃恢复</el-button>
      <el-button type="primary" @click="submitOverride">覆盖并重新审批</el-button>
    </template>
  </el-dialog>
</template>

<style scoped>
.filter-item {
  width: 150px;
}
.rb-card {
  border: 1px solid var(--ncg-border);
  border-radius: 12px;
  margin-bottom: var(--ncg-space-2);
}
.rb-head {
  display: flex;
  justify-content: space-between;
  align-items: center;
}
.rb-label {
  font-size: var(--ncg-fs-xs);
  letter-spacing: 0.06em;
  color: var(--ncg-text-muted);
}
.rb-dataid {
  font-size: var(--ncg-fs-base);
  font-weight: 600;
  margin: 6px 0 2px;
  word-break: break-all;
}
.rb-foot {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-top: var(--ncg-space-2);
}
.ncg-sub {
  font-size: var(--ncg-fs-xs);
  color: var(--ncg-text-muted);
}
.ncg-pager {
  margin-top: var(--ncg-space-2);
  justify-content: flex-end;
}
.confirm-copy {
  font-size: var(--ncg-fs-sm);
  line-height: 1.7;
}
.ncg-banner {
  margin-bottom: var(--ncg-space-2);
}
.ncg-kv {
  display: grid;
  gap: 6px;
  margin: var(--ncg-space-2) 0;
  font-size: var(--ncg-fs-sm);
}
.ncg-kv span {
  display: inline-block;
  width: 80px;
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
