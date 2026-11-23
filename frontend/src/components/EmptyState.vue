<script setup lang="ts">
/**
 * EmptyState —— 空态（详细设计 §3.5.4：说明为什么为空 + 下一步动作；
 * 不把“无数据”显示成“正常”）
 */
withDefaults(
  defineProps<{
    /** 为什么为空（原因） */
    reason?: string
    /** 下一步动作建议 */
    nextStep?: string
    actionText?: string
  }>(),
  {
    reason: '当前筛选条件下没有数据',
    nextStep: '调整筛选条件或清除关键字后重试',
    actionText: '',
  },
)

const emit = defineEmits<{ action: [] }>()
</script>

<template>
  <div class="ncg-empty" role="status">
    <div class="ncg-empty__icon" aria-hidden="true">
      <svg width="40" height="40" viewBox="0 0 40 40" fill="none">
        <rect x="6" y="10" width="28" height="20" rx="4" stroke="currentColor" stroke-width="1.5" />
        <path d="M13 18h14M13 24h8" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" />
      </svg>
    </div>
    <p class="ncg-empty__reason">{{ reason }}</p>
    <p class="ncg-empty__next">{{ nextStep }}</p>
    <slot name="action">
      <el-button v-if="actionText" type="primary" plain @click="emit('action')">{{ actionText }}</el-button>
    </slot>
  </div>
</template>

<style scoped>
.ncg-empty {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: var(--ncg-space-1);
  padding: var(--ncg-space-6) var(--ncg-space-2);
  text-align: center;
}
.ncg-empty__icon {
  color: var(--ncg-text-3);
  margin-bottom: var(--ncg-space-1);
}
.ncg-empty__reason {
  color: var(--ncg-text-1);
  font-size: var(--ncg-fs-md);
  font-weight: 500;
}
.ncg-empty__next {
  color: var(--ncg-text-3);
  font-size: var(--ncg-fs-sm);
}
</style>
