<script setup lang="ts">
/**
 * DiffView —— 配置差异展示（详细设计 §3.5.4）
 * 三种状态：added 新增（cyan）/ modified 修改（amber）/ deleted 删除（red）
 * mode='pair' 双列（当前值→目标值）；mode='three' 三列（基线/当前/目标，用于基线漂移）
 * 敏感值默认脱敏显示（sensitiveKeys 命中的 key 不显示明文）
 */
import { computed } from 'vue'

export interface DiffRow {
  key: string
  state: 'added' | 'modified' | 'deleted' | 'unchanged'
  before?: string
  after?: string
  /** 三列模式：基线值 */
  baseline?: string
  /** 是否敏感值（优先于 sensitiveKeys） */
  sensitive?: boolean
}

const props = withDefaults(
  defineProps<{
    rows: DiffRow[]
    mode?: 'pair' | 'three'
    /** 需要脱敏的 key 列表（精确匹配） */
    sensitiveKeys?: string[]
    /** 是否显示未变化行 */
    showUnchanged?: boolean
  }>(),
  {
    mode: 'pair',
    sensitiveKeys: () => [],
    showUnchanged: false,
  },
)

const MASK = '●●●●●●●●'

const visibleRows = computed(() =>
  props.showUnchanged ? props.rows : props.rows.filter((r) => r.state !== 'unchanged'),
)

const stateLabel: Record<DiffRow['state'], string> = {
  added: '新增',
  modified: '修改',
  deleted: '删除',
  unchanged: '未变化',
}

function mask(row: DiffRow, value: string | undefined): string {
  if (value === undefined) return ''
  if (row.sensitive || props.sensitiveKeys.includes(row.key)) return MASK
  return value
}
</script>

<template>
  <div class="ncg-diff">
    <div v-if="visibleRows.length === 0" class="ncg-diff__empty ncg-muted">无差异项</div>
    <div
      v-for="row in visibleRows"
      :key="row.key"
      class="ncg-diff__row"
      :class="`ncg-diff__row--${row.state}`"
    >
      <div class="ncg-diff__head">
        <span class="ncg-diff__badge" :class="`ncg-diff__badge--${row.state}`">{{ stateLabel[row.state] }}</span>
        <code class="ncg-diff__key ncg-data">{{ row.key }}</code>
        <span v-if="row.sensitive || sensitiveKeys.includes(row.key)" class="ncg-diff__sensitive ncg-data">敏感</span>
      </div>
      <div class="ncg-diff__cols" :class="`ncg-diff__cols--${mode}`">
        <div v-if="mode === 'three'" class="ncg-diff__cell">
          <div class="ncg-diff__cell-label ncg-muted">基线</div>
          <pre class="ncg-diff__value ncg-data">{{ mask(row, row.baseline) || '—' }}</pre>
        </div>
        <div class="ncg-diff__cell">
          <div class="ncg-diff__cell-label ncg-muted">{{ mode === 'three' ? '当前' : '当前值' }}</div>
          <pre class="ncg-diff__value ncg-data">{{ mask(row, row.before) || '—' }}</pre>
        </div>
        <div class="ncg-diff__cell">
          <div class="ncg-diff__cell-label ncg-muted">{{ mode === 'three' ? '目标' : '目标值' }}</div>
          <pre class="ncg-diff__value ncg-data">{{ mask(row, row.after) || '—' }}</pre>
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.ncg-diff {
  display: flex;
  flex-direction: column;
  gap: var(--ncg-space-1);
}
.ncg-diff__empty {
  padding: var(--ncg-space-3);
  text-align: center;
  font-size: var(--ncg-fs-sm);
}
.ncg-diff__row {
  border: 1px solid var(--ncg-line);
  border-radius: var(--ncg-radius-md);
  overflow: hidden;
}
.ncg-diff__row--added { border-left: 3px solid var(--ncg-cyan); }
.ncg-diff__row--modified { border-left: 3px solid var(--ncg-amber); }
.ncg-diff__row--deleted { border-left: 3px solid var(--ncg-red); }

.ncg-diff__head {
  display: flex;
  align-items: center;
  gap: var(--ncg-space-1);
  padding: var(--ncg-space-1) var(--ncg-space-2);
  background: var(--ncg-panel-2);
}
.ncg-diff__badge {
  padding: 1px 8px;
  border-radius: var(--ncg-radius-sm);
  font-size: var(--ncg-fs-xs);
}
.ncg-diff__badge--added { background: var(--ncg-cyan-bg); color: var(--ncg-cyan); }
.ncg-diff__badge--modified { background: var(--ncg-amber-bg); color: var(--ncg-amber); }
.ncg-diff__badge--deleted { background: var(--ncg-red-bg); color: var(--ncg-red); }
.ncg-diff__badge--unchanged { background: var(--ncg-panel); color: var(--ncg-text-3); }
.ncg-diff__key { font-size: var(--ncg-fs-sm); color: var(--ncg-text-1); }
.ncg-diff__sensitive {
  margin-left: auto;
  font-size: var(--ncg-fs-xs);
  color: var(--ncg-amber);
}

.ncg-diff__cols {
  display: grid;
  gap: 1px;
  background: var(--ncg-line);
}
.ncg-diff__cols--pair { grid-template-columns: 1fr 1fr; }
.ncg-diff__cols--three { grid-template-columns: 1fr 1fr 1fr; }
.ncg-diff__cell {
  background: var(--ncg-panel);
  padding: var(--ncg-space-1) var(--ncg-space-2);
}
.ncg-diff__cell-label {
  font-size: var(--ncg-fs-xs);
  margin-bottom: 2px;
}
.ncg-diff__value {
  margin: 0;
  white-space: pre-wrap;
  word-break: break-all;
  font-size: var(--ncg-fs-sm);
  color: var(--ncg-text-2);
}
</style>
