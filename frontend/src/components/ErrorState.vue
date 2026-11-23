<script setup lang="ts">
/**
 * ErrorState —— 请求失败态（详细设计 §3.5.4：错误码 + 可读文案 + TraceId + 建议动作；
 * 保留用户已填内容，不清空表单）
 */
import { computed } from 'vue'
import { ElMessage } from 'element-plus'
import 'element-plus/es/components/message/style/css'

const props = withDefaults(
  defineProps<{
    code?: string
    message?: string
    traceId?: string
    /** 建议动作文案 */
    suggestion?: string
  }>(),
  {
    code: 'SYS-500-001',
    message: '请求失败，请稍后重试',
    traceId: '',
    suggestion: '稍后重试；若持续失败，请把错误码与 TraceId 提供给平台管理员',
  },
)

const emit = defineEmits<{ retry: [] }>()

async function copyTraceId() {
  try {
    await navigator.clipboard.writeText(props.traceId)
    ElMessage.success('TraceId 已复制')
  } catch {
    ElMessage.warning('复制失败，请手动选择 TraceId')
  }
}
</script>

<template>
  <div class="ncg-error" role="alert">
    <div class="ncg-error__badge ncg-data">{{ code }}</div>
    <p class="ncg-error__msg">{{ message }}</p>
    <p class="ncg-error__hint">{{ suggestion }}</p>
    <div v-if="traceId" class="ncg-error__trace">
      <span class="ncg-muted">TraceId</span>
      <code class="ncg-data ncg-error__trace-id">{{ traceId }}</code>
      <el-button size="small" text type="primary" @click="copyTraceId">复制</el-button>
    </div>
    <div class="ncg-error__actions">
      <slot name="actions">
        <el-button type="primary" plain @click="emit('retry')">重新加载</el-button>
      </slot>
    </div>
  </div>
</template>

<style scoped>
.ncg-error {
  display: flex;
  flex-direction: column;
  gap: var(--ncg-space-1);
  padding: var(--ncg-space-4);
  border: 1px solid oklch(42% 0.09 28 / 0.5);
  border-radius: var(--ncg-radius-md);
  background: var(--ncg-red-bg);
}
.ncg-error__badge {
  align-self: flex-start;
  padding: 2px var(--ncg-space-1);
  border-radius: var(--ncg-radius-sm);
  background: oklch(30% 0.08 28);
  color: var(--ncg-red);
  font-size: var(--ncg-fs-xs);
}
.ncg-error__msg {
  color: var(--ncg-text-1);
  font-size: var(--ncg-fs-md);
  font-weight: 500;
}
.ncg-error__hint {
  color: var(--ncg-text-2);
  font-size: var(--ncg-fs-sm);
}
.ncg-error__trace {
  display: flex;
  align-items: center;
  gap: var(--ncg-space-1);
  font-size: var(--ncg-fs-xs);
}
.ncg-error__trace-id {
  word-break: break-all;
  color: var(--ncg-text-2);
}
.ncg-error__actions {
  margin-top: var(--ncg-space-1);
}
</style>
