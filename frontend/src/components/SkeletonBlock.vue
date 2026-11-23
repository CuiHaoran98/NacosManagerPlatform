<script setup lang="ts">
/**
 * SkeletonBlock —— 加载骨架（详细设计 §3.5.4：骨架屏保持布局稳定，不用全屏 spinner）
 * shimmer 动画在 prefers-reduced-motion 下自动降级（tokens.css 全局处理）
 */
withDefaults(
  defineProps<{
    rows?: number
    type?: 'table' | 'text' | 'card'
  }>(),
  { rows: 5, type: 'table' },
)
</script>

<template>
  <div class="ncg-skeleton" :class="`ncg-skeleton--${type}`" role="status" aria-live="polite" aria-label="加载中">
    <template v-if="type === 'table'">
      <div class="ncg-skeleton__head ncg-skeleton__bar" />
      <div v-for="i in rows" :key="i" class="ncg-skeleton__row">
        <div class="ncg-skeleton__bar" />
        <div class="ncg-skeleton__bar ncg-skeleton__bar--short" />
        <div class="ncg-skeleton__bar ncg-skeleton__bar--mid" />
      </div>
    </template>
    <template v-else-if="type === 'card'">
      <div v-for="i in rows" :key="i" class="ncg-skeleton__card">
        <div class="ncg-skeleton__bar ncg-skeleton__bar--short" />
        <div class="ncg-skeleton__bar" />
      </div>
    </template>
    <template v-else>
      <div v-for="i in rows" :key="i" class="ncg-skeleton__bar" :class="{ 'ncg-skeleton__bar--mid': i % 3 === 0 }" />
    </template>
    <span class="ncg-sr-only">内容加载中</span>
  </div>
</template>

<style scoped>
.ncg-skeleton {
  display: flex;
  flex-direction: column;
  gap: var(--ncg-space-1);
  padding: var(--ncg-space-2) 0;
}
.ncg-skeleton__row {
  display: grid;
  grid-template-columns: 2fr 1fr 1.4fr;
  gap: var(--ncg-space-2);
  align-items: center;
}
.ncg-skeleton__card {
  display: flex;
  flex-direction: column;
  gap: var(--ncg-space-1);
  padding: var(--ncg-space-2);
  border: 1px solid var(--ncg-line);
  border-radius: var(--ncg-radius-md);
}
.ncg-skeleton__bar {
  height: 14px;
  border-radius: var(--ncg-radius-sm);
  background: linear-gradient(
    90deg,
    var(--ncg-panel-2) 0%,
    oklch(27% 0.02 265) 50%,
    var(--ncg-panel-2) 100%
  );
  background-size: 200% 100%;
  animation: ncg-shimmer 1.4s var(--ncg-ease) infinite;
}
.ncg-skeleton__bar--short { width: 40%; }
.ncg-skeleton__bar--mid { width: 70%; }
.ncg-skeleton__head { height: 20px; margin-bottom: var(--ncg-space-1); }

@keyframes ncg-shimmer {
  from { background-position: 200% 0; }
  to { background-position: -200% 0; }
}

/* 循环动画仅用于加载指示；reduced-motion 下静止 */
@media (prefers-reduced-motion: reduce) {
  .ncg-skeleton__bar { animation: none; }
}

.ncg-sr-only {
  position: absolute;
  width: 1px;
  height: 1px;
  overflow: hidden;
  clip: rect(0 0 0 0);
}
</style>
