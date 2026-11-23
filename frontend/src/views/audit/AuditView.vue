<script setup lang="ts">
/**
 * 审计与检测 —— P-13（契约 §2.5(41-44)、详细设计 §3.5.2 监督 / 原型 audit 视图）
 * 能力：审计流查询（关键词/事件类型/操作人/时间范围 + 分页）、导出（EXPORT_AUDIT）、
 *       哈希链校验（前端一致性核对，范围=当前查询结果）、直连变更告警处置（幂等关闭）。
 * 敏感红线（MUST-NOT-05）：审计事件仅含脱敏快照，导出内容不含敏感明文，页面不渲染任何敏感值。
 * 权限（§9）：查询/处置需 VIEW_AUDIT，导出按钮需 EXPORT_AUDIT（无权限动作不渲染）。
 */
import { computed, onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import 'element-plus/es/components/message/style/css'
import { useAuditStore, type ChainCheckResult } from '@/stores/audit'
import { useAuthStore } from '@/stores/auth'
import type { AuditEvent } from '@/api/types'

const store = useAuditStore()
const auth = useAuthStore()

const canView = computed(() => auth.can('VIEW_AUDIT'))
const canExport = computed(() => auth.can('EXPORT_AUDIT'))

/** 动作枚举（§10.1 固定枚举）——筛选下拉与表格标签共用 */
const EVENT_TYPE_OPTIONS = [
  { group: '变更', items: [
    { value: 'CHANGE_CREATE', label: '创建配置' },
    { value: 'CHANGE_UPDATE', label: '更新配置' },
    { value: 'CHANGE_DELETE', label: '删除配置' },
    { value: 'CHANGE_SUBMIT', label: '提交变更' },
  ] },
  { group: '审批', items: [
    { value: 'APPROVAL_APPROVE', label: '审批通过' },
    { value: 'APPROVAL_REJECT', label: '审批驳回' },
    { value: 'APPROVAL_TRANSFER', label: '审批转交' },
  ] },
  { group: '发布', items: [
    { value: 'RELEASE_INTENT', label: '发布意图' },
    { value: 'RELEASE_RESULT', label: '发布结果' },
  ] },
  { group: '检测与审计', items: [
    { value: 'DIRECT_CHANGE', label: '直连变更' },
    { value: 'CONFIG_SENSITIVE_VIEW', label: '敏感值查看' },
    { value: 'AUDIT_EXPORT', label: '审计导出' },
    { value: 'CHAIN_VERIFY', label: '链校验' },
  ] },
]

const EVENT_TYPE_LABEL: Record<string, string> = Object.fromEntries(
  EVENT_TYPE_OPTIONS.flatMap((g) => g.items.map((i) => [i.value, i.label])),
)

function eventTypeTagType(code: string): 'success' | 'warning' | 'info' | 'danger' | 'primary' | undefined {
  if (code.startsWith('CHANGE_')) return undefined
  if (code.startsWith('APPROVAL_')) return 'warning'
  if (code.startsWith('RELEASE_')) return 'primary'
  if (code === 'DIRECT_CHANGE') return 'danger'
  if (code === 'CONFIG_SENSITIVE_VIEW') return 'warning'
  return 'info'
}

/** 时间统一按 Asia/Shanghai 展示（§6.1），接口保留 ISO-8601 */
const timeFmt = new Intl.DateTimeFormat('zh-CN', {
  timeZone: 'Asia/Shanghai', year: 'numeric', month: '2-digit', day: '2-digit',
  hour: '2-digit', minute: '2-digit', second: '2-digit', hour12: false,
})
function fmtTime(iso: string): string {
  if (!iso) return '—'
  const d = new Date(iso)
  return Number.isNaN(d.getTime()) ? iso : timeFmt.format(d).replace(/\//g, '-')
}

/** 时间范围选择器的墙钟时间视为 Asia/Shanghai（UTC+8 无夏令时），转 ISO-8601 */
function toIsoShanghai(v: string): string {
  return v ? `${v}+08:00` : ''
}

function shortHash(hash: string): string {
  return hash && hash.length > 12 ? `${hash.slice(0, 6)}…${hash.slice(-4)}` : hash || '—'
}

async function copyText(text: string, label: string) {
  try {
    await navigator.clipboard.writeText(text)
    ElMessage.success(`${label}已复制`)
  } catch {
    ElMessage.error('复制失败，请手动选择复制')
  }
}

// ---- 筛选 ----
const keyword = ref('')
const eventType = ref('')
const actor = ref('')
const range = ref<[string, string] | ''>('')

async function applyFilters() {
  const [from, to] = range.value && range.value.length === 2 ? range.value : ['', '']
  await store.applyFilter({
    keyword: keyword.value,
    eventType: eventType.value,
    actor: actor.value,
    from: toIsoShanghai(from),
    to: toIsoShanghai(to),
  })
}

async function resetFilters() {
  keyword.value = ''
  eventType.value = ''
  actor.value = ''
  range.value = ''
  await applyFilters()
}

const onPageChange = (p: number) => { void store.setPage(p - 1) }
const onSizeChange = (s: number) => { void store.setSize(s) }

// ---- 哈希链校验 ----
const chainStatusText: Record<string, string> = {
  idle: '尚未校验',
  ok: '链完整',
  broken: '发现断链',
}
async function runChainCheck() {
  if (store.events.length === 0) {
    ElMessage.info('当前查询结果为空，暂无可校验的事件')
    return
  }
  const result: ChainCheckResult = store.verifyChain()
  if (result.status === 'ok') {
    ElMessage.success(`校验通过：${result.checked} 条事件链接一致`)
  } else {
    ElMessage.warning(`发现断链或哈希格式异常 ${result.breaks} 条，请展开事件详情核对`)
  }
}

// ---- 导出（EXPORT_AUDIT；导出内容不含敏感明文） ----
const exportDialogVisible = ref(false)
const exportFormat = ref<'CSV' | 'JSON'>('CSV')

async function confirmExport() {
  try {
    const blob = await store.exportEvents(exportFormat.value)
    const url = URL.createObjectURL(blob)
    const a = document.createElement('a')
    a.href = url
    a.download = `ncg-audit-events-${new Date().toISOString().slice(0, 10)}.${exportFormat.value.toLowerCase()}`
    document.body.appendChild(a)
    a.click()
    a.remove()
    URL.revokeObjectURL(url)
    exportDialogVisible.value = false
    ElMessage.success('审计导出已开始下载（内容仅含脱敏快照）')
  } catch {
    // 错误体已由全局拦截器按 code 提示（AUD-500-001 等），此处保留用户已填内容
  }
}

// ---- 直连告警处置（二次确认 → 执行 → 读回） ----
const closeDialogVisible = ref(false)
const pendingClose = ref<{ id: string; dataId: string; instanceId: string; detectedAt: string } | null>(null)

function openCloseDialog(item: { id: string; dataId: string; instanceId: string; detectedAt: string }) {
  pendingClose.value = item
  closeDialogVisible.value = true
}

async function confirmClose() {
  if (!pendingClose.value) return
  const id = pendingClose.value.id
  closeDialogVisible.value = false
  try {
    await store.closeDirectChange(id)
    ElMessage.success('告警已处置并写入审计（幂等）')
    pendingClose.value = null
  } catch {
    // 全局拦截器已提示（SYS-404-001 等），保留列表现状
  }
}

// ---- 事件详情 ----
const detailVisible = ref(false)
const detailIndex = ref(-1)
const detailEvent = computed(() => (detailIndex.value >= 0 ? store.events[detailIndex.value] : null))

function openDetail(index: number) {
  detailIndex.value = index
  detailVisible.value = true
}

/**
 * el-table row-click 实际签名为 (row, column | null, event)——无行号参数；
 * 这里形参按 unknown 接收后收窄，按 eventId 定位行号（t3 同款写法）。
 */
function onRowClick(row: unknown) {
  const event = row as AuditEvent
  const index = store.events.findIndex((e) => e.eventId === event?.eventId)
  if (index >= 0) openDetail(index)
}

/** 与展示顺序中的上一条做链接一致性提示（完整结论以“校验哈希链”为准） */
function linkState(index: number): { ok: boolean; note: string } {
  const cur = store.events[index]
  const prev = store.events[index - 1]
  if (!cur) return { ok: true, note: '—' }
  if (!prev) return { ok: true, note: '当前页首条，链关系以校验结果为准' }
  return cur.prevHash === prev.hash
    ? { ok: true, note: '与上一条链接一致' }
    : { ok: false, note: '与上一条链接不一致（或跨页断开）' }
}

onMounted(() => {
  if (canView.value) void store.init()
})
</script>

<template>
  <PageShell
    title="审计与检测"
    description="审计只追加、不可篡改、永久保留；按日哈希链。直连修改必须在 5 分钟内发现并通知负责人。"
    :loading="store.loading && store.events.length === 0"
  >
    <template #actions>
      <el-button :disabled="!canExport || store.exporting" :loading="store.exporting" @click="exportDialogVisible = true">
        导出审计
      </el-button>
      <el-button type="primary" plain :loading="store.chainChecking" @click="runChainCheck">校验哈希链</el-button>
    </template>

    <!-- 无权限：不渲染动作，仅说明所需权限点（§3.5.4） -->
    <el-alert
      v-if="!canView"
      type="info"
      :closable="false"
      title="当前账号缺少 VIEW_AUDIT 权限点"
      description="审计流查询与直连告警处置需要 VIEW_AUDIT；导出需要 EXPORT_AUDIT。请联系管理员在“管理与设置”中授权（应用 × 环境范围）。"
    />

    <template v-else>
      <!-- 顶部：直连告警 + 哈希链 -->
      <div class="ncg-audit__top">
        <section class="ncg-audit__panel">
          <header class="ncg-audit__panel-head">
            <h2 class="ncg-audit__panel-title">直连变更告警</h2>
            <el-tag v-if="store.openDirectCount > 0" type="danger" size="small" effect="dark" round>
              未处置 {{ store.openDirectCount }}
            </el-tag>
          </header>

          <el-radio-group v-model="store.directStatus" size="small" class="ncg-audit__direct-filter">
            <el-radio-button value="">全部</el-radio-button>
            <el-radio-button value="OPEN">未处置</el-radio-button>
            <el-radio-button value="CLOSED">已处置</el-radio-button>
          </el-radio-group>

          <SkeletonBlock v-if="store.directLoading && store.directChanges.length === 0" type="card" :rows="2" />
          <ErrorState
            v-else-if="store.directError"
            :code="store.directError.code"
            :message="store.directError.message"
            :trace-id="store.directError.traceId"
            suggestion="直连检测数据拉取失败，可重试；检测盲区期间不得推断为“无变更”。"
            @retry="store.fetchDirectChanges()"
          />
          <EmptyState
            v-else-if="store.visibleDirectChanges.length === 0"
            reason="当前没有符合条件的直连告警"
            next-step="直连修改会在 5 分钟内被发现并列出；检测中断时段标注为“盲区”，不代表无变更。"
          />
          <ul v-else class="ncg-audit__direct-list">
            <li v-for="item in store.visibleDirectChanges" :key="item.id" class="ncg-audit__direct-item">
              <div class="ncg-audit__direct-main">
                <div class="ncg-audit__direct-title">
                  <code class="ncg-data ncg-audit__dataid">{{ item.dataId }}</code>
                  <el-tag :type="item.status === 'OPEN' ? 'danger' : 'info'" size="small" round>
                    {{ item.status === 'OPEN' ? '未处置' : '已处置' }}
                  </el-tag>
                  <el-tag v-if="!item.matched" type="warning" size="small" round>未匹配 NCG 记录</el-tag>
                </div>
                <div class="ncg-muted ncg-audit__direct-meta">
                  <code class="ncg-mono">{{ item.instanceId }}</code>
                  <span>发现于 {{ fmtTime(item.detectedAt) }}</span>
                </div>
              </div>
              <el-button
                v-if="item.status === 'OPEN'"
                type="danger"
                plain
                size="small"
                :loading="store.closingId === item.id"
                @click="openCloseDialog(item)"
              >
                处置告警
              </el-button>
            </li>
          </ul>
        </section>

        <section class="ncg-audit__panel">
          <header class="ncg-audit__panel-head">
            <h2 class="ncg-audit__panel-title">哈希链</h2>
            <el-tag size="small" effect="plain" type="info">SHA-256</el-tag>
          </header>

          <dl class="ncg-audit__chain-grid">
            <dt class="ncg-muted">链头（最新事件）</dt>
            <dd>
              <code class="ncg-data" :title="store.chainResult?.headHash || '尚未校验'">
                {{ store.chainResult ? shortHash(store.chainResult.headHash) : '—' }}
              </code>
            </dd>
            <dt class="ncg-muted">校验范围</dt>
            <dd class="ncg-audit__chain-scope">
              当前查询结果已加载 {{ store.events.length }} 条{{ store.total > store.events.length ? ` / 共 ${store.total} 条` : '' }}
              （按日分链，未加载部分不在范围内）
            </dd>
            <dt class="ncg-muted">断链 / 格式异常</dt>
            <dd>
              <span class="ncg-data">{{ store.chainResult ? store.chainResult.breaks : '—' }}</span> 条
            </dd>
          </dl>

          <div class="ncg-audit__chain-foot">
            <span
              class="ncg-pill"
              :class="{
                'ncg-pill--ok': store.chainResult?.status === 'ok',
                'ncg-pill--bad': store.chainResult?.status === 'broken',
                'ncg-pill--idle': !store.chainResult || store.chainResult.status === 'idle',
              }"
            >
              {{ chainStatusText[store.chainResult?.status ?? 'idle'] }}
            </span>
            <el-button size="small" type="primary" plain :loading="store.chainChecking" @click="runChainCheck">
              校验当前结果
            </el-button>
          </div>
          <p class="ncg-muted ncg-audit__chain-note">
            校验内容：每条事件 prevHash 必须等于上一条 hash，且均为 64 位十六进制（append-only 链式结构）。结论仅覆盖已加载事件。
          </p>
        </section>
      </div>

      <!-- 筛选工具条 -->
      <TableToolbar
        v-model:search-value="keyword"
        search-placeholder="搜索对象 / 事件（keyword）"
        :loading="store.loading"
        @search="applyFilters"
        @refresh="store.fetchEvents()"
      >
        <template #filters>
          <el-select
            v-model="eventType"
            clearable
            filterable
            placeholder="事件类型"
            class="ncg-audit__select"
            @change="applyFilters"
          >
            <el-option-group v-for="group in EVENT_TYPE_OPTIONS" :key="group.group" :label="group.group">
              <el-option v-for="opt in group.items" :key="opt.value" :value="opt.value" :label="`${opt.label} ${opt.value}`" />
            </el-option-group>
          </el-select>
          <el-input
            v-model="actor"
            placeholder="操作人"
            clearable
            class="ncg-audit__actor"
            @change="applyFilters"
            @keyup.enter="applyFilters"
          />
          <el-date-picker
            v-model="range"
            type="datetimerange"
            range-separator="至"
            start-placeholder="开始时间"
            end-placeholder="结束时间"
            value-format="YYYY-MM-DDTHH:mm:ss"
            class="ncg-audit__range"
            @change="applyFilters"
          />
          <el-button @click="resetFilters">重置筛选</el-button>
        </template>
      </TableToolbar>

      <!-- 表格加载失败：保留已填筛选（§3.5.4），内联错误态 -->
      <ErrorState
        v-if="store.error"
        :code="store.error.code"
        :message="store.error.message"
        :trace-id="store.error.traceId"
        suggestion="审计流查询失败；筛选条件已保留，可直接重试。"
        @retry="store.fetchEvents()"
      />
      <template v-else>
        <el-table
          :data="store.events"
          v-loading="store.loading"
          row-key="eventId"
          class="ncg-audit__table"
          @row-click="onRowClick"
        >
          <el-table-column label="时间" width="170">
            <template #default="{ row }">
              <span class="ncg-data ncg-audit__time">{{ fmtTime(row.createdAt) }}</span>
            </template>
          </el-table-column>
          <el-table-column label="事件" width="180">
            <template #default="{ row }">
              <el-tag :type="eventTypeTagType(row.eventType)" size="small" effect="light">
                {{ EVENT_TYPE_LABEL[row.eventType] ?? row.eventType }}
              </el-tag>
              <code class="ncg-mono ncg-muted ncg-audit__event-code">{{ row.eventType }}</code>
            </template>
          </el-table-column>
          <el-table-column label="操作者" width="160">
            <template #default="{ row }">
              <span class="ncg-mono ncg-truncate ncg-audit__cell">{{ row.actor }}</span>
            </template>
          </el-table-column>
          <el-table-column label="对象">
            <template #default="{ row }">
              <span class="ncg-mono ncg-truncate ncg-audit__cell" :title="row.target">{{ row.target }}</span>
            </template>
          </el-table-column>
          <el-table-column label="TraceId" width="200">
            <template #default="{ row }">
              <code class="ncg-mono ncg-audit__trace">{{ row.traceId }}</code>
            </template>
          </el-table-column>
          <el-table-column label="哈希链" width="150">
            <template #default="{ row }">
              <code class="ncg-data" :title="row.hash">{{ shortHash(row.hash) }}</code>
            </template>
          </el-table-column>
        </el-table>

        <EmptyState
          v-if="!store.loading && store.events.length === 0"
          reason="当前筛选条件下没有审计事件"
          next-step="放宽时间范围或清空筛选后重试；审计只追加，历史事件永久保留。"
          action-text="重置筛选"
          @action="resetFilters"
        />

        <el-pagination
          v-if="store.total > 0"
          background
          layout="total, sizes, prev, pager, next"
          :total="store.total"
          :page-sizes="[20, 50, 100]"
          :page-size="store.size"
          :current-page="store.page + 1"
          class="ncg-audit__pager"
          @current-change="onPageChange"
          @size-change="onSizeChange"
        />
      </template>

      <!-- 检测盲区（§7.11）：标注盲区，不得推断为“无变更” -->
      <el-alert
        type="info"
        :closable="false"
        title="检测盲区"
        description="TongNCS 历史不足或检测中断的时段标注为“盲区”，不得推断为“无变更”；直连修改须在 5 分钟内被发现并通知负责人。审计与导出内容仅含脱敏快照（MUST-NOT-05），不含敏感明文。"
        class="ncg-audit__blind"
      />
    </template>

    <!-- 导出对话框（EXPORT_AUDIT） -->
    <el-dialog v-model="exportDialogVisible" title="导出审计事件" width="480px">
      <el-form label-position="top">
        <el-form-item label="导出格式">
          <el-radio-group v-model="exportFormat">
            <el-radio-button value="CSV">CSV</el-radio-button>
            <el-radio-button value="JSON">JSON</el-radio-button>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="导出范围">
          <p class="ncg-muted ncg-audit__export-scope">
            与当前筛选一致：{{ keyword || '关键词不限' }} · {{ eventType || '事件类型不限' }} ·
            {{ actor || '操作人不限' }} · {{ range && range.length === 2 ? `${range[0]} ~ ${range[1]}` : '时间不限' }}
          </p>
        </el-form-item>
      </el-form>
      <el-alert
        type="info"
        :closable="false"
        title="导出内容不含敏感明文"
        description="列表与导出统一脱敏（MUST-NOT-05）；大批量导出由服务端处理，超过批量上限会返回 AUD-500-001 及具体限制提示。"
      />
      <template #footer>
        <el-button @click="exportDialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="store.exporting" @click="confirmExport">导出 {{ exportFormat }}</el-button>
      </template>
    </el-dialog>

    <!-- 直连告警处置确认（二次确认 → 执行 → 读回） -->
    <el-dialog v-model="closeDialogVisible" title="处置直连告警" width="440px">
      <el-descriptions v-if="pendingClose" :column="1" border size="small">
        <el-descriptions-item label="DataId">
          <code class="ncg-data">{{ pendingClose.dataId }}</code>
        </el-descriptions-item>
        <el-descriptions-item label="实例">
          <code class="ncg-mono">{{ pendingClose.instanceId }}</code>
        </el-descriptions-item>
        <el-descriptions-item label="发现时间">{{ fmtTime(pendingClose.detectedAt) }}</el-descriptions-item>
      </el-descriptions>
      <el-alert
        type="warning"
        :closable="false"
        title="确认处置并关闭该告警？"
        description="处置表示已知悉该直连变更，告警状态变为“已处置”并写入审计事件（接口幂等，可重复处置）。"
        class="ncg-audit__confirm-alert"
      />
      <template #footer>
        <el-button @click="closeDialogVisible = false">稍后处理</el-button>
        <el-button type="danger" :loading="!!store.closingId" @click="confirmClose">确认处置</el-button>
      </template>
    </el-dialog>

    <!-- 事件详情 -->
    <el-dialog v-model="detailVisible" title="审计事件详情" width="620px">
      <el-descriptions v-if="detailEvent" :column="1" border size="small">
        <el-descriptions-item label="事件 ID">
          <code class="ncg-data">{{ detailEvent.eventId }}</code>
        </el-descriptions-item>
        <el-descriptions-item label="事件类型">
          <el-tag :type="eventTypeTagType(detailEvent.eventType)" size="small">
            {{ EVENT_TYPE_LABEL[detailEvent.eventType] ?? detailEvent.eventType }}
          </el-tag>
          <code class="ncg-mono ncg-muted ncg-audit__event-code">{{ detailEvent.eventType }}</code>
        </el-descriptions-item>
        <el-descriptions-item label="操作者">
          <code class="ncg-mono">{{ detailEvent.actor }}</code>
        </el-descriptions-item>
        <el-descriptions-item label="对象">
          <code class="ncg-mono ncg-audit__wrap">{{ detailEvent.target }}</code>
        </el-descriptions-item>
        <el-descriptions-item label="时间">{{ fmtTime(detailEvent.createdAt) }}</el-descriptions-item>
        <el-descriptions-item label="TraceId">
          <code class="ncg-mono ncg-audit__wrap">{{ detailEvent.traceId }}</code>
          <el-button link type="primary" size="small" @click="copyText(detailEvent.traceId, 'TraceId')">复制</el-button>
        </el-descriptions-item>
        <el-descriptions-item label="事件哈希（event_hash）">
          <code class="ncg-data ncg-audit__wrap">{{ detailEvent.hash }}</code>
          <el-button link type="primary" size="small" @click="copyText(detailEvent.hash, '事件哈希')">复制</el-button>
        </el-descriptions-item>
        <el-descriptions-item label="上一条哈希（prev_hash）">
          <code class="ncg-data ncg-audit__wrap">{{ detailEvent.prevHash }}</code>
          <el-button link type="primary" size="small" @click="copyText(detailEvent.prevHash, '上一条哈希')">复制</el-button>
        </el-descriptions-item>
        <el-descriptions-item label="链接状态">
          <el-tag :type="linkState(detailIndex).ok ? 'success' : 'warning'" size="small">
            {{ linkState(detailIndex).note }}
          </el-tag>
        </el-descriptions-item>
      </el-descriptions>
      <el-alert
        type="info"
        :closable="false"
        title="审计只含脱敏快照"
        description="审计事件记录的是变更前后的脱敏快照（before_masked / after_masked），不含敏感明文（MUST-NOT-05）。"
        class="ncg-audit__confirm-alert"
      />
      <template #footer>
        <el-button type="primary" @click="detailVisible = false">关闭</el-button>
      </template>
    </el-dialog>
  </PageShell>
</template>

<style scoped>
.ncg-audit__top {
  display: grid;
  grid-template-columns: 1.15fr 1fr;
  gap: var(--ncg-space-3);
  margin-bottom: var(--ncg-space-4);
}

@media (max-width: 1100px) {
  .ncg-audit__top {
    grid-template-columns: 1fr;
  }
}

.ncg-audit__panel {
  background: var(--ncg-panel);
  border: 1px solid var(--ncg-line);
  border-radius: var(--ncg-radius-lg);
  padding: var(--ncg-space-3) var(--ncg-space-4);
}

.ncg-audit__panel-head {
  display: flex;
  align-items: center;
  gap: var(--ncg-space-2);
  margin-bottom: var(--ncg-space-2);
}

.ncg-audit__panel-title {
  font-family: var(--ncg-font-display);
  font-size: var(--ncg-fs-md);
  font-weight: 600;
  color: var(--ncg-text-1);
  margin: 0;
}

.ncg-audit__direct-filter {
  margin-bottom: var(--ncg-space-2);
}

.ncg-audit__direct-list {
  list-style: none;
  margin: 0;
  padding: 0;
  display: flex;
  flex-direction: column;
  gap: var(--ncg-space-2);
  max-height: 320px;
  overflow-y: auto;
}

.ncg-audit__direct-item {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--ncg-space-2);
  padding: var(--ncg-space-2) var(--ncg-space-3);
  border: 1px solid var(--ncg-line);
  border-left: 3px solid var(--ncg-red);
  border-radius: var(--ncg-radius-md);
  background: var(--ncg-red-bg);
}

.ncg-audit__direct-main {
  min-width: 0;
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.ncg-audit__direct-title {
  display: flex;
  align-items: center;
  gap: var(--ncg-space-1);
  flex-wrap: wrap;
}

.ncg-audit__dataid {
  font-family: var(--ncg-font-data);
  font-size: var(--ncg-fs-sm);
  color: var(--ncg-text-1);
  font-weight: 600;
}

.ncg-audit__direct-meta {
  display: flex;
  gap: var(--ncg-space-2);
  font-size: var(--ncg-fs-xs);
  flex-wrap: wrap;
}

.ncg-audit__chain-grid {
  display: grid;
  grid-template-columns: auto 1fr;
  gap: 6px var(--ncg-space-2);
  margin: 0 0 var(--ncg-space-2);
  font-size: var(--ncg-fs-sm);
}

.ncg-audit__chain-grid dt {
  white-space: nowrap;
}

.ncg-audit__chain-grid dd {
  margin: 0;
  color: var(--ncg-text-1);
  min-width: 0;
}

.ncg-audit__chain-scope {
  font-size: var(--ncg-fs-xs);
  color: var(--ncg-text-2);
  line-height: 1.5;
}

.ncg-audit__chain-foot {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--ncg-space-2);
  margin-bottom: var(--ncg-space-1);
}

.ncg-audit__chain-note {
  font-size: var(--ncg-fs-xs);
  line-height: 1.5;
  margin: 0;
}

.ncg-pill {
  display: inline-flex;
  align-items: center;
  padding: 2px 10px;
  border-radius: 999px;
  font-family: var(--ncg-font-display);
  font-size: var(--ncg-fs-xs);
  font-weight: 600;
  background: var(--ncg-cyan-bg);
  color: var(--ncg-cyan);
}

.ncg-pill--ok {
  background: var(--ncg-volt-bg);
  color: var(--ncg-volt-deep);
}

.ncg-pill--bad {
  background: var(--ncg-red-bg);
  color: var(--ncg-red);
}

.ncg-pill--idle {
  background: var(--ncg-amber-bg);
  color: var(--ncg-amber);
}

.ncg-audit__select {
  width: 220px;
}

.ncg-audit__actor {
  width: 160px;
}

.ncg-audit__range {
  width: 360px;
}

.ncg-audit__table {
  width: 100%;
  cursor: pointer;
}

.ncg-audit__cell {
  display: inline-block;
  max-width: 320px;
  vertical-align: bottom;
}

.ncg-audit__time {
  font-size: var(--ncg-fs-xs);
  white-space: nowrap;
}

.ncg-audit__event-code {
  margin-left: 6px;
  font-size: var(--ncg-fs-xs);
}

.ncg-audit__trace {
  font-size: var(--ncg-fs-xs);
  color: var(--ncg-text-2);
}

.ncg-audit__wrap {
  word-break: break-all;
  font-size: var(--ncg-fs-xs);
}

.ncg-audit__pager {
  margin-top: var(--ncg-space-3);
  justify-content: flex-end;
}

.ncg-audit__blind {
  margin-top: var(--ncg-space-3);
}

.ncg-audit__export-scope {
  margin: 0;
  line-height: 1.6;
}

.ncg-audit__confirm-alert {
  margin-top: var(--ncg-space-2);
}
</style>
