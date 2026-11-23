<script setup lang="ts">
/**
 * WorkbenchView —— 工作台（P-02）
 * 三块：待我审批、直连告警、最近发布（原型 doc/ncg-frontend-prototype.html 工作台）。
 * 状态只由后端给（§3.5.1）；「你提交的，不能审批」职责分离（MUST-06）。
 */
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { changeApi, releaseApi, auditApi } from '@/api'
import type { ChangeListItem, DirectChangeEvent, ReleaseRecord } from '@/api/types'
import { useAuthStore } from '@/stores/auth'
import { useAppStore } from '@/stores/app'
import {
  asList,
  fmtTime,
  ONGOING_STATUSES,
  type UiError,
  toUiError,
} from '@/stores/changes'
import PageShell from '@/components/PageShell.vue'
import EmptyState from '@/components/EmptyState.vue'
import ErrorState from '@/components/ErrorState.vue'
import StatusTag from '../changes/StatusTag.vue'

const router = useRouter()
const auth = useAuthStore()
const appStore = useAppStore()

const loading = ref(true)
const pendingList = ref<ChangeListItem[]>([])
const ongoingCount = ref(0)
const directEvents = ref<DirectChangeEvent[]>([])
const releaseList = ref<ReleaseRecord[]>([])
const errors = ref<{ pending: UiError | null; direct: UiError | null; release: UiError | null }>({
  pending: null,
  direct: null,
  release: null,
})

const meIds = computed(() => {
  const u = auth.user
  return u ? [u.userId, u.username, u.displayName] : []
})
const isMine = (createdBy: string) => meIds.value.includes(createdBy)

const pendingCount = computed(() => pendingList.value.length)
const directOpenCount = computed(() => directEvents.value.filter((d) => d.status === 'OPEN').length)

const releaseTone = (result: string) =>
  result === 'verified' ? 'volt' : result === 'rejected' ? 'red' : 'amber'
const releaseLabel = (result: string) =>
  result === 'verified' ? '已验证' : result === 'rejected' ? '已拒绝' : '不确定'

async function load() {
  loading.value = true
  await appStore.loadEnvs()
  const envId = appStore.currentEnvId
  const [pending, direct, releases, ongoing] = await Promise.allSettled([
    changeApi.listChanges({ status: 'PENDING_APPROVAL', envId, page: 0, size: 20 }),
    auditApi.listDirectChanges({}),
    releaseApi.listReleases({ envId, page: 0, size: 5 }),
    changeApi.listChanges({ envId, page: 0, size: 100 }),
  ])
  if (pending.status === 'fulfilled') {
    pendingList.value = asList<ChangeListItem>(pending.value)
    errors.value.pending = null
  } else {
    errors.value.pending = toUiError(pending.reason)
  }
  if (direct.status === 'fulfilled') {
    directEvents.value = asList<DirectChangeEvent>(direct.value)
    errors.value.direct = null
  } else {
    errors.value.direct = toUiError(direct.reason)
  }
  if (releases.status === 'fulfilled') {
    releaseList.value = asList<ReleaseRecord>(releases.value)
    errors.value.release = null
  } else {
    errors.value.release = toUiError(releases.reason)
  }
  ongoingCount.value =
    ongoing.status === 'fulfilled'
      ? asList<ChangeListItem>(ongoing.value).filter((c) => ONGOING_STATUSES.includes(c.status)).length
      : 0
  loading.value = false
}

onMounted(load)

function openChange(changeNo: string) {
  router.push({ name: 'change-detail', params: { changeNo } })
}
function gotoAudit() {
  router.push({ name: 'audit' })
}
</script>

<template>
  <PageShell title="工作台" description="待我审批 · 直连告警 · 最近发布（只显示当前环境）" :loading="loading" @retry="load">
    <!-- 指标带 -->
    <div class="wb-metrics">
      <div class="wb-metric wb-metric--volt">
        <div class="wb-metric__label">待我审批</div>
        <div class="wb-metric__value">{{ pendingCount }}</div>
      </div>
      <div class="wb-metric wb-metric--amber">
        <div class="wb-metric__label">进行中变更</div>
        <div class="wb-metric__value">{{ ongoingCount }}</div>
      </div>
      <div class="wb-metric wb-metric--red">
        <div class="wb-metric__label">直连告警（未闭环）</div>
        <div class="wb-metric__value">{{ directOpenCount }}</div>
      </div>
      <div class="wb-metric wb-metric--cyan">
        <div class="wb-metric__label">最近发布</div>
        <div class="wb-metric__value">{{ releaseList.length }}</div>
      </div>
    </div>

    <!-- 待我审批 -->
    <section class="wb-section">
      <header class="wb-section__head">
        <h2>待我审批</h2>
        <el-button text type="primary" @click="router.push({ name: 'approvals' })">进入审批中心</el-button>
      </header>
      <ErrorState v-if="errors.pending" :code="errors.pending.code" :message="errors.pending.message" :trace-id="errors.pending.traceId" @retry="load" />
      <EmptyState
        v-else-if="!pendingList.length"
        reason="当前环境没有待你审批的变更单"
        next-step="在配置目录或变更单页发起变更后，审批请求会出现在这里"
      />
      <el-table v-else :data="pendingList" size="small" @row-click="(r: ChangeListItem) => openChange(r.changeNo)">
        <el-table-column label="变更单" min-width="240">
          <template #default="{ row }">
            <div class="wb-change-title">
              <code class="ncg-mono">{{ row.changeNo }}</code>
              <span>{{ row.title }}</span>
            </div>
          </template>
        </el-table-column>
        <el-table-column label="目标配置" min-width="180">
          <template #default="{ row }">
            <code class="ncg-mono">{{ row.dataId }}</code>
            <div class="ncg-muted ncg-truncate">{{ row.appName ?? row.appId }}</div>
          </template>
        </el-table-column>
        <el-table-column label="风险" width="110">
          <template #default="{ row }"><StatusTag kind="risk" :risk="row.riskLevel" /></template>
        </el-table-column>
        <el-table-column label="提交人" width="140">
          <template #default="{ row }">
            {{ row.createdBy }}
            <el-tag v-if="isMine(row.createdBy)" size="small" type="info" effect="plain">你提交的，不能审批</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="创建时间" width="160">
          <template #default="{ row }">{{ fmtTime(row.createdAt) }}</template>
        </el-table-column>
        <el-table-column label="操作" width="90">
          <template #default="{ row }">
            <el-button size="small" text type="primary" @click.stop="openChange(row.changeNo)">审阅 →</el-button>
          </template>
        </el-table-column>
      </el-table>
    </section>

    <!-- 直连告警 -->
    <section class="wb-section">
      <header class="wb-section__head">
        <h2>直连告警</h2>
        <el-button text type="primary" @click="gotoAudit">查看审计与检测</el-button>
      </header>
      <ErrorState v-if="errors.direct" :code="errors.direct.code" :message="errors.direct.message" :trace-id="errors.direct.traceId" @retry="load" />
      <EmptyState
        v-else-if="!directEvents.length"
        reason="暂无绕过平台的直连变更检测事件"
        next-step="平台持续比对 Nacos 实例配置指纹，出现漂移会在此告警"
      />
      <div v-else class="wb-alerts">
        <div v-for="d in directEvents" :key="d.id" class="wb-alert">
          <div class="wb-alert__head">
            <StatusTag :status="d.status" />
            <el-tag v-if="!d.matched" size="small" type="danger" effect="plain">检测盲区（未匹配应用/负责人）</el-tag>
            <code class="ncg-mono">{{ d.dataId }}</code>
          </div>
          <div class="wb-alert__meta ncg-muted">
            实例 <code class="ncg-mono">{{ d.instanceId }}</code> · 检测时间 {{ fmtTime(d.detectedAt) }}
          </div>
          <el-button size="small" text type="primary" @click="gotoAudit">查看证据 →</el-button>
        </div>
      </div>
    </section>

    <!-- 最近发布 -->
    <section class="wb-section">
      <header class="wb-section__head">
        <h2>最近发布</h2>
        <el-button text type="primary" @click="router.push({ name: 'releases' })">进入发布控制台</el-button>
      </header>
      <ErrorState v-if="errors.release" :code="errors.release.code" :message="errors.release.message" :trace-id="errors.release.traceId" @retry="load" />
      <EmptyState
        v-else-if="!releaseList.length"
        reason="当前环境还没有发布记录"
        next-step="变更单通过审批后即可发布，发布结果按已验证/已拒绝/不确定三态展示"
      />
      <el-table v-else :data="releaseList" size="small">
        <el-table-column label="变更单" min-width="200">
          <template #default="{ row }"><code class="ncg-mono">{{ row.changeNo }}</code></template>
        </el-table-column>
        <el-table-column label="结果" width="120">
          <template #default="{ row }">
            <span class="wb-pill" :class="`wb-pill--${releaseTone(row.result)}`">
              <i class="ncg-status__dot" />{{ releaseLabel(row.result) }}
            </span>
          </template>
        </el-table-column>
        <el-table-column label="读回 MD5" min-width="160">
          <template #default="{ row }">
            <code v-if="row.readbackMd5" class="ncg-mono">{{ row.readbackMd5 }}</code>
            <span v-else class="ncg-muted">—</span>
          </template>
        </el-table-column>
        <el-table-column label="发布人" width="120">
          <template #default="{ row }">{{ row.releasedBy }}</template>
        </el-table-column>
        <el-table-column label="时间" width="160">
          <template #default="{ row }">{{ fmtTime(row.releasedAt) }}</template>
        </el-table-column>
        <el-table-column label="TraceId" min-width="160">
          <template #default="{ row }">
            <code v-if="row.traceId" class="ncg-mono ncg-truncate">{{ row.traceId }}</code>
            <span v-else class="ncg-muted">—</span>
          </template>
        </el-table-column>
      </el-table>
    </section>
  </PageShell>
</template>

<style scoped>
.wb-metrics {
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  gap: var(--ncg-space-2);
}
@media (max-width: 900px) {
  .wb-metrics { grid-template-columns: repeat(2, 1fr); }
}
.wb-metric {
  background: var(--ncg-panel);
  border: 1px solid var(--ncg-line);
  border-radius: var(--ncg-radius-md);
  padding: var(--ncg-space-2) var(--ncg-space-3);
}
.wb-metric__label {
  font-size: var(--ncg-fs-xs);
  color: var(--ncg-text-2);
}
.wb-metric__value {
  font-family: var(--ncg-font-mono);
  font-size: var(--ncg-fs-xl);
  line-height: 1.2;
}
.wb-metric--volt .wb-metric__value { color: var(--ncg-volt); }
.wb-metric--amber .wb-metric__value { color: var(--ncg-amber); }
.wb-metric--red .wb-metric__value { color: var(--ncg-red); }
.wb-metric--cyan .wb-metric__value { color: var(--ncg-cyan); }

.wb-section {
  margin-top: var(--ncg-space-3);
}
.wb-section__head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: var(--ncg-space-1);
}
.wb-section__head h2 {
  font-size: var(--ncg-fs-lg);
  margin: 0;
}
.wb-change-title {
  display: flex;
  gap: 8px;
  align-items: baseline;
}
.wb-alerts {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(320px, 1fr));
  gap: var(--ncg-space-2);
}
.wb-alert {
  border: 1px solid var(--ncg-line);
  border-left: 3px solid var(--ncg-red);
  border-radius: var(--ncg-radius-md);
  padding: var(--ncg-space-2);
  display: flex;
  flex-direction: column;
  gap: 8px;
  align-items: flex-start;
}
.wb-alert__head {
  display: flex;
  gap: 8px;
  align-items: center;
  flex-wrap: wrap;
}
.wb-alert__meta {
  font-size: var(--ncg-fs-xs);
}
.wb-pill {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  font-size: var(--ncg-fs-xs);
}
.wb-pill--volt { color: var(--ncg-volt); }
.wb-pill--red { color: var(--ncg-red); }
.wb-pill--amber { color: var(--ncg-amber); }
</style>
