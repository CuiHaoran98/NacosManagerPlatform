<script setup lang="ts">
/**
 * StatusTag —— 状态/风险标签（设计 §3.5.5：圆点 + 文本 + 颜色，不单靠颜色表达状态）
 */
import { computed } from 'vue'
import { statusLabel, statusTone, riskLabel, riskTone } from '@/stores/changes'

const props = withDefaults(
  defineProps<{
    status?: string
    risk?: string
    kind?: 'status' | 'risk'
  }>(),
  { kind: 'status' },
)

const label = computed(() => (props.kind === 'risk' ? riskLabel(props.risk) : statusLabel(props.status)))
const tone = computed(() => (props.kind === 'risk' ? riskTone(props.risk) : statusTone(props.status)))
</script>

<template>
  <span class="ncg-status" :class="`ncg-status--${tone}`">
    <span class="ncg-status__dot" aria-hidden="true" />
    {{ label }}
  </span>
</template>

<style scoped>
.ncg-status {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  font-size: var(--ncg-fs-sm);
  white-space: nowrap;
}
.ncg-status__dot {
  width: 7px;
  height: 7px;
  border-radius: 50%;
  background: currentColor;
  flex-shrink: 0;
}
.ncg-status--volt {
  color: var(--ncg-volt);
}
.ncg-status--amber {
  color: var(--ncg-amber);
}
.ncg-status--red {
  color: var(--ncg-red);
}
.ncg-status--cyan {
  color: var(--ncg-cyan);
}
.ncg-status--mute {
  color: var(--ncg-text-2);
}
</style>
