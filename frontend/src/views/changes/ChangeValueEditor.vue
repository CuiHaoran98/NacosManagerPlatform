<script setup lang="ts">
/**
 * ChangeValueEditor —— 变更内容编辑器（P-05 新建变更 / P-06 草稿编辑 共用）
 * - items 模式：结构化格式（PROPERTIES/YAML/JSON）按项填值，留空=不变，敏感项不回显旧明文
 * - content 模式：CREATE / TEXT / 整文件替换（items[] 与 content 互斥，契约 §1.3）
 * - parseError：展示行号（可得列号）+ 错误行高亮 + 保留草稿，浏览不阻断、提交阻断（MUST-15 / CR-422-001）
 */
import { computed } from 'vue'
import type { ConfigItem, ParseError } from '@/api/types'
import { parseErrorText } from '@/stores/catalog'

const props = withDefaults(
  defineProps<{
    mode: 'items' | 'content'
    items: ConfigItem[]
    values: Record<string, string>
    content: string
    disabled?: boolean
    parseError?: ParseError | null
  }>(),
  { disabled: false, parseError: null },
)

const emit = defineEmits<{
  'update:values': [values: Record<string, string>]
  'update:content': [content: string]
}>()

const MASK = '●●●●●●●●'

function isSensitive(key: string): boolean {
  return props.items.find((i) => i.key === key)?.sensitive ?? false
}

function currentValue(item: ConfigItem): string {
  return item.sensitive ? MASK : item.value
}

function setNewValue(key: string, v: string) {
  const next: Record<string, string> = { ...props.values }
  if (v === '') delete next[key]
  else next[key] = v
  emit('update:values', next)
}

const changedCount = computed(() => Object.keys(props.values).length)

const contentLines = computed(() => (props.content ?? '').split('\n'))
</script>

<template>
  <div class="ncg-editor">
    <!-- 解析失败：行号（列号）+ 高亮 + 只读提示 -->
    <el-alert
      v-if="parseError"
      type="error"
      :closable="false"
      class="ncg-editor__parse-error"
      :title="parseErrorText(parseError)"
      description="解析失败不阻断浏览与草稿保留，但提交将被阻断（CR-422-001）；可切换整文件替换修正内容。"
    />

    <!-- ── items 模式：按配置项填值 ── -->
    <template v-if="mode === 'items'">
      <div v-if="items.length === 0" class="ncg-editor__empty ncg-muted">
        当前无可解析的配置项（内容解析失败或为空），请使用整文件替换。
      </div>
      <div v-else class="ncg-editor__table">
        <div class="ncg-editor__row ncg-editor__row--head ncg-muted">
          <span>配置项 key</span>
          <span>当前值</span>
          <span>新值</span>
        </div>
        <div v-for="item in items" :key="item.key" class="ncg-editor__row">
          <span class="ncg-editor__key ncg-mono">
            {{ item.key }}
            <em v-if="item.sensitive" class="ncg-editor__sensitive-tag">敏感</em>
          </span>
          <span class="ncg-editor__current ncg-mono" :title="currentValue(item)">{{ currentValue(item) }}</span>
          <el-input
            :model-value="values[item.key] ?? ''"
            :disabled="disabled"
            clearable
            :placeholder="item.sensitive ? '输入新值（当前脱敏）' : '输入新值（留空不变）'"
            @update:model-value="setNewValue(item.key, $event)"
          />
        </div>
      </div>
      <div class="ncg-editor__summary ncg-muted">已修改 {{ changedCount }} 项；提交前可在下方预览 Diff。</div>
    </template>

    <!-- ── content 模式：整文件 ── -->
    <template v-else>
      <el-input
        type="textarea"
        :rows="14"
        :model-value="content"
        :disabled="disabled"
        placeholder="粘贴完整配置文件内容（整文件替换）"
        @update:model-value="emit('update:content', $event)"
      />
      <!-- 解析失败行高亮（保留草稿） -->
      <div v-if="parseError && contentLines.length" class="ncg-editor__lines">
        <div class="ncg-editor__lines-title ncg-muted">内容行预览（错误行高亮）</div>
        <ol class="ncg-editor__line-list ncg-mono">
          <li
            v-for="(line, idx) in contentLines"
            :key="idx"
            class="ncg-editor__line"
            :class="{ 'ncg-editor__line--error': idx + 1 === parseError.line }"
          >
            {{ line || ' ' }}
          </li>
        </ol>
      </div>
    </template>
  </div>
</template>

<style scoped>
.ncg-editor {
  display: flex;
  flex-direction: column;
  gap: var(--ncg-space-2);
}
.ncg-editor__parse-error {
  background: var(--ncg-red-bg);
  border: 1px solid var(--ncg-red);
}
.ncg-editor__table {
  border: 1px solid var(--ncg-line);
  border-radius: var(--ncg-radius-md);
  overflow: hidden;
}
.ncg-editor__row {
  display: grid;
  grid-template-columns: minmax(140px, 1.2fr) minmax(120px, 1fr) minmax(160px, 1.2fr);
  gap: var(--ncg-space-1);
  align-items: center;
  padding: 8px var(--ncg-space-2);
  border-top: 1px solid var(--ncg-line);
}
.ncg-editor__row--head {
  border-top: none;
  background: var(--ncg-panel-2);
  font-size: var(--ncg-fs-xs);
}
.ncg-editor__key {
  font-size: var(--ncg-fs-sm);
  word-break: break-all;
}
.ncg-editor__sensitive-tag {
  margin-left: 6px;
  font-style: normal;
  font-size: var(--ncg-fs-xs);
  color: var(--ncg-amber);
  border: 1px solid var(--ncg-amber);
  border-radius: var(--ncg-radius-sm);
  padding: 0 4px;
}
.ncg-editor__current {
  font-size: var(--ncg-fs-sm);
  color: var(--ncg-text-2);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.ncg-editor__summary {
  font-size: var(--ncg-fs-xs);
}
.ncg-editor__lines {
  border: 1px solid var(--ncg-line);
  border-radius: var(--ncg-radius-md);
  padding: var(--ncg-space-1) var(--ncg-space-2);
}
.ncg-editor__lines-title {
  font-size: var(--ncg-fs-xs);
  margin-bottom: 4px;
}
.ncg-editor__line-list {
  margin: 0;
  padding-left: 2em;
  font-size: var(--ncg-fs-sm);
}
.ncg-editor__line {
  color: var(--ncg-text-2);
  white-space: pre-wrap;
}
.ncg-editor__line--error {
  background: var(--ncg-red-bg);
  color: var(--ncg-red);
}
</style>
