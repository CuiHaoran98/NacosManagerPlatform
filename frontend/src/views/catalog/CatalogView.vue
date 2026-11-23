<script setup lang="ts">
/**
 * CatalogView —— 配置目录（P-03）
 * 应用树（按负责人分组）+ 配置表（多选）+ 详情抽屉。
 * DataId=配置文件，items=文件内 key 按项展示当前值；format 标签 PROPERTIES/YAML/JSON/TEXT；
 * 敏感项默认脱敏，按 itemKey+reason 查看明文并在 30 秒后重新脱敏（SensitiveValue 组件）；
 * 未知格式只读并禁发起变更（MUST-NOT-11 / CR-415-001）；
 * 浏览态 parseError 只读展示不阻断（MUST-15）；多选发起变更走批量（§3.5.3）。
 */
import { computed, onMounted, ref } from 'vue'
import { useAppStore } from '@/stores/app'
import { useAuthStore } from '@/stores/auth'
import { useCatalogStore, formatLabel, formatTone, isFormatKnown, FORMAT_READONLY_HINT, contentStats, parseErrorText } from '@/stores/catalog'
import PageShell from '@/components/PageShell.vue'
import EmptyState from '@/components/EmptyState.vue'
import ErrorState from '@/components/ErrorState.vue'
import SensitiveValue from '@/components/SensitiveValue.vue'
import ChangeCreateDialog from '../changes/ChangeCreateDialog.vue'
import BatchChangeDialog from './BatchChangeDialog.vue'
import type { ConfigMapping } from '@/api/types'

const appStore = useAppStore()
const auth = useAuthStore()
const catalog = useCatalogStore()

const onlyCurrentEnv = ref(true)
const keyword = ref('')

/* ── 应用树：按负责人分组 ── */
interface TreeNode { id: string; label: string; children?: TreeNode[]; appId?: string }
const treeData = computed<TreeNode[]>(() => {
  const groups = new Map<string, TreeNode>()
  for (const a of catalog.apps) {
    const owner = a.owner || '未指定负责人'
    if (!groups.has(owner)) groups.set(owner, { id: `owner:${owner}`, label: owner, children: [] })
    groups.get(owner)!.children!.push({ id: a.appId, label: a.appName, appId: a.appId })
  }
  return [...groups.values()]
})

const treeRef = ref()
function onTreeSelect(node: TreeNode) {
  if (node.appId) void catalog.selectApp(node.appId)
}

/* ── 配置表 ── */
const rows = computed(() => {
  let list = catalog.configs
  if (onlyCurrentEnv.value) list = list.filter((m) => m.envId === appStore.currentEnvId)
  if (keyword.value.trim()) {
    const kw = keyword.value.trim().toLowerCase()
    list = list.filter((m) => m.dataId.toLowerCase().includes(kw))
  }
  return list
})

const canEdit = computed(() => auth.can('EDIT') && !appStore.readOnly)

function statsOf(m: ConfigMapping) {
  return contentStats(catalog.contents[m.mappingId])
}

function onSelectionChange(selection: ConfigMapping[]) {
  catalog.setSelection(selection.map((m) => m.mappingId))
}

/* ── 详情抽屉 ── */
const createVisible = ref(false)
const createPreset = ref<{ appId: string; envId: string; mappingId: string; dataId?: string } | null>(null)

function openCreate(m: ConfigMapping) {
  if (!isFormatKnown(m.format)) return
  createPreset.value = { appId: m.appId, envId: m.envId, mappingId: m.mappingId, dataId: m.dataId }
  createVisible.value = true
}

/* ── 批量发起 ── */
const batchVisible = ref(false)

onMounted(async () => {
  await catalog.fetchApps()
  if (catalog.apps.length && !catalog.selectedAppId) void catalog.selectApp(catalog.apps[0].appId)
})

function fmtTimeShort(iso?: string) {
  return iso ? iso.replace('T', ' ').replace(/\..*$/, '') : '—'
}
</script>

<template>
  <PageShell title="配置目录" description="应用 → 配置文件（DataId）→ 配置项；敏感值默认脱敏，查看写入审计">
    <template #actions>
      <el-switch v-model="onlyCurrentEnv" active-text="仅看当前环境" />
      <el-button
        type="primary"
        :disabled="!catalog.selectedIds.length || !canEdit"
        @click="batchVisible = true"
      >
        批量发起变更（{{ catalog.selectedIds.length }}）
      </el-button>
    </template>

    <div class="cat-layout">
      <!-- 左：应用树 -->
      <aside class="cat-tree">
        <div class="cat-tree__title">应用</div>
        <ErrorState
          v-if="catalog.appsError"
          :code="catalog.appsError.code"
          :message="catalog.appsError.message"
          :trace-id="catalog.appsError.traceId"
          @retry="catalog.fetchApps()"
        />
        <el-tree
          v-else
          ref="treeRef"
          :data="treeData"
          node-key="id"
          default-expand-all
          highlight-current
          :expand-on-click-node="false"
          @node-click="onTreeSelect"
        >
          <template #default="{ data }">
            <span class="cat-tree__node" :class="{ 'cat-tree__node--app': !!data.appId }">{{ data.label }}</span>
          </template>
        </el-tree>
      </aside>

      <!-- 右：配置表 -->
      <section class="cat-main">
        <div class="cat-main__toolbar">
          <el-input v-model="keyword" placeholder="按 DataId 过滤" clearable style="max-width: 280px" />
          <span class="ncg-muted">
            当前环境：{{ appStore.envLabel }}{{ onlyCurrentEnv ? '' : '（显示全部环境）' }}
          </span>
        </div>

        <ErrorState
          v-if="catalog.configsError"
          :code="catalog.configsError.code"
          :message="catalog.configsError.message"
          :trace-id="catalog.configsError.traceId"
          @retry="catalog.selectedAppId && catalog.selectApp(catalog.selectedAppId)"
        />
        <EmptyState
          v-else-if="!catalog.selectedAppId"
          reason="未选择应用"
          next-step="从左侧应用树选择一个应用，查看其纳管配置文件"
        />
        <EmptyState
          v-else-if="!rows.length"
          reason="该应用在当前筛选下没有纳管配置"
          next-step="关闭「仅看当前环境」或清除 DataId 过滤再试"
        />
        <el-table
          v-else
          :data="rows"
          size="small"
          row-key="mappingId"
          @selection-change="onSelectionChange"
        >
          <el-table-column type="selection" width="40" :selectable="(r: ConfigMapping) => isFormatKnown(r.format)" />
          <el-table-column label="DataId（配置文件）" min-width="220">
            <template #default="{ row }">
              <code class="ncg-mono">{{ row.dataId }}</code>
              <el-tag v-if="!isFormatKnown(row.format)" size="small" type="warning" effect="plain">未知格式</el-tag>
            </template>
          </el-table-column>
          <el-table-column label="格式" width="110">
            <template #default="{ row }">
              <span class="cat-fmt" :class="`cat-fmt--${formatTone(row.format)}`">{{ formatLabel(row.format) }}</span>
            </template>
          </el-table-column>
          <el-table-column label="配置项 / 敏感" width="120">
            <template #default="{ row }">
              <template v-if="catalog.contents[row.mappingId]">
                {{ statsOf(row as ConfigMapping).itemCount }} 项
                <el-tag v-if="statsOf(row as ConfigMapping).sensitiveCount" size="small" type="danger" effect="plain">
                  敏感 {{ statsOf(row as ConfigMapping).sensitiveCount }}
                </el-tag>
              </template>
              <span v-else class="ncg-muted">—</span>
            </template>
          </el-table-column>
          <el-table-column label="环境" width="90">
            <template #default="{ row }">
              <el-tag size="small" effect="plain">{{ appStore.envLabelOf(row.envId) }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column label="MD5" min-width="140">
            <template #default="{ row }"><code class="ncg-mono ncg-truncate">{{ row.md5 }}</code></template>
          </el-table-column>
          <el-table-column label="采集时间" width="160">
            <template #default="{ row }">{{ fmtTimeShort(row.collectedAt) }}</template>
          </el-table-column>
          <el-table-column label="操作" width="170">
            <template #default="{ row }">
              <el-button size="small" text type="primary" @click="catalog.openDetail(row.mappingId)">查看详情</el-button>
              <el-tooltip
                :disabled="isFormatKnown(row.format)"
                :content="FORMAT_READONLY_HINT"
                placement="top"
              >
                <span>
                  <el-button
                    size="small"
                    text
                    type="primary"
                    :disabled="!isFormatKnown(row.format) || !canEdit"
                    @click="openCreate(row as ConfigMapping)"
                  >
                    发起变更
                  </el-button>
                </span>
              </el-tooltip>
            </template>
          </el-table-column>
        </el-table>
      </section>
    </div>

    <!-- 详情抽屉 -->
    <el-drawer
      :model-value="!!catalog.detail || catalog.detailLoading"
      :title="catalog.detail?.dataId ?? '配置详情'"
      size="min(560px, 94vw)"
      @update:model-value="(v: boolean) => !v && catalog.closeDetail()"
    >
      <ErrorState
        v-if="catalog.detailError"
        :code="catalog.detailError.code"
        :message="catalog.detailError.message"
        :trace-id="catalog.detailError.traceId"
        @retry="catalog.detail && catalog.openDetail(catalog.detail.mappingId)"
      />
      <template v-else-if="catalog.detail">
        <div class="cat-detail__meta">
          <div><span class="ncg-muted">DataId</span> <code class="ncg-mono">{{ catalog.detail.dataId }}</code></div>
          <div>
            <span class="ncg-muted">格式</span>
            <span class="cat-fmt" :class="`cat-fmt--${formatTone(catalog.detail.format)}`">{{ formatLabel(catalog.detail.format) }}</span>
          </div>
          <div><span class="ncg-muted">MD5</span> <code class="ncg-mono">{{ catalog.detail.md5 }}</code></div>
          <div><span class="ncg-muted">采集时间</span> {{ fmtTimeShort(catalog.detail.collectedAt) }}</div>
        </div>

        <!-- 浏览态 parseError：只读展示不阻断（MUST-15） -->
        <el-alert
          v-if="catalog.detail.parseError"
          type="warning"
          :closable="false"
          title="该文件解析失败，当前只读展示"
          :description="parseErrorText(catalog.detail.parseError)"
        />

        <template v-if="catalog.detail.items?.length">
          <div class="cat-detail__section-title">配置项（{{ catalog.detail.items.length }}）</div>
          <div class="cat-detail__items">
            <div v-for="item in catalog.detail.items" :key="item.key" class="cat-detail__item">
              <code class="ncg-mono cat-detail__key">{{ item.key }}</code>
              <span v-if="item.sensitive" class="cat-detail__value">
                <SensitiveValue :mapping-id="catalog.detail.mappingId" :item-key="item.key" />
              </span>
              <span v-else class="ncg-mono cat-detail__value ncg-truncate">{{ item.value }}</span>
            </div>
          </div>
        </template>

        <template v-if="catalog.detail.content">
          <div class="cat-detail__section-title">文件内容（TEXT / 整文件）</div>
          <pre class="cat-detail__content ncg-mono">{{ catalog.detail.content }}</pre>
        </template>

        <div class="cat-detail__footer">
          <el-tooltip
            :disabled="isFormatKnown(catalog.detail.format)"
            :content="FORMAT_READONLY_HINT"
            placement="top"
          >
            <span>
              <el-button
                type="primary"
                :disabled="!isFormatKnown(catalog.detail.format) || !canEdit"
                @click="openCreate({ ...catalog.detail } as ConfigMapping)"
              >
                发起变更
              </el-button>
            </span>
          </el-tooltip>
          <span v-if="!isFormatKnown(catalog.detail.format)" class="ncg-muted cat-detail__hint">
            {{ FORMAT_READONLY_HINT }}（MUST-NOT-11）
          </span>
        </div>
      </template>
    </el-drawer>

    <ChangeCreateDialog v-model:visible="createVisible" :preset="createPreset" @created="catalog.closeDetail()" />
    <BatchChangeDialog
      v-model:visible="batchVisible"
      :mapping-ids="catalog.selectedIds"
      @done="catalog.setSelection([])"
    />
  </PageShell>
</template>

<style scoped>
.cat-layout {
  display: grid;
  grid-template-columns: 240px 1fr;
  gap: var(--ncg-space-2);
  align-items: start;
}
@media (max-width: 900px) {
  .cat-layout { grid-template-columns: 1fr; }
}
.cat-tree {
  background: var(--ncg-panel);
  border: 1px solid var(--ncg-line);
  border-radius: var(--ncg-radius-md);
  padding: var(--ncg-space-2);
  max-height: 70vh;
  overflow: auto;
}
.cat-tree__title {
  font-size: var(--ncg-fs-xs);
  color: var(--ncg-text-2);
  margin-bottom: 8px;
  text-transform: uppercase;
  letter-spacing: 0.08em;
}
.cat-tree__node--app {
  font-family: var(--ncg-font-mono);
  font-size: var(--ncg-fs-xs);
}
.cat-main__toolbar {
  display: flex;
  align-items: center;
  gap: var(--ncg-space-2);
  margin-bottom: var(--ncg-space-1);
}
.cat-fmt {
  font-family: var(--ncg-font-mono);
  font-size: var(--ncg-fs-xs);
  padding: 2px 8px;
  border-radius: 999px;
  border: 1px solid var(--ncg-line);
}
.cat-fmt--amber { color: var(--ncg-amber); border-color: color-mix(in oklch, var(--ncg-amber) 40%, transparent); }
.cat-fmt--cyan { color: var(--ncg-cyan); border-color: color-mix(in oklch, var(--ncg-cyan) 40%, transparent); }
.cat-fmt--volt { color: var(--ncg-volt); border-color: color-mix(in oklch, var(--ncg-volt) 40%, transparent); }
.cat-fmt--mute { color: var(--ncg-text-2); }
.cat-detail__meta {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 8px;
  font-size: var(--ncg-fs-xs);
  margin-bottom: var(--ncg-space-2);
}
.cat-detail__section-title {
  font-size: var(--ncg-fs-xs);
  color: var(--ncg-text-2);
  margin: var(--ncg-space-2) 0 8px;
  text-transform: uppercase;
  letter-spacing: 0.08em;
}
.cat-detail__items {
  display: flex;
  flex-direction: column;
  gap: 6px;
}
.cat-detail__item {
  display: grid;
  grid-template-columns: minmax(120px, 1fr) minmax(140px, 1.4fr);
  gap: 8px;
  align-items: center;
  padding: 6px 8px;
  border: 1px solid var(--ncg-line);
  border-radius: var(--ncg-radius-sm);
  font-size: var(--ncg-fs-xs);
}
.cat-detail__key {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  color: var(--ncg-text-2);
}
.cat-detail__value {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.cat-detail__content {
  background: color-mix(in oklch, var(--ncg-ink) 60%, var(--ncg-panel));
  border: 1px solid var(--ncg-line);
  border-radius: var(--ncg-radius-sm);
  padding: var(--ncg-space-2);
  font-size: var(--ncg-fs-xs);
  overflow: auto;
  max-height: 40vh;
}
.cat-detail__footer {
  margin-top: var(--ncg-space-3);
  display: flex;
  align-items: center;
  gap: var(--ncg-space-2);
}
.cat-detail__hint {
  font-size: var(--ncg-fs-xs);
}
</style>
