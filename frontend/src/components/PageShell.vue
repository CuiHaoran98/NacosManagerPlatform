<script setup lang="ts">
/**
 * PageShell —— 页面壳（标题 + 描述 + 操作区 + 内容区）
 * 所有业务页面统一使用，保证信息架构与留白一致
 */
withDefaults(
  defineProps<{
    title: string
    description?: string
    /** 整页加载骨架 */
    loading?: boolean
    /** 整页错误 */
    error?: { code?: string; message?: string; traceId?: string } | null
  }>(),
  {
    description: '',
    loading: false,
    error: null,
  },
)

const emit = defineEmits<{ retry: [] }>()
</script>

<template>
  <div class="ncg-page">
    <header class="ncg-page__header">
      <div class="ncg-page__titles">
        <h1 class="ncg-page__title">{{ title }}</h1>
        <p v-if="description" class="ncg-page__desc">{{ description }}</p>
      </div>
      <div class="ncg-page__actions">
        <slot name="actions" />
      </div>
    </header>

    <SkeletonBlock v-if="loading" :rows="6" type="table" />

    <ErrorState
      v-else-if="error"
      :code="error.code"
      :message="error.message"
      :trace-id="error.traceId"
      @retry="emit('retry')"
    />

    <slot v-else />
  </div>
</template>

<style scoped>
.ncg-page {
  display: flex;
  flex-direction: column;
  gap: var(--ncg-space-3);
}
.ncg-page__header {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: var(--ncg-space-2);
  flex-wrap: wrap;
}
.ncg-page__title {
  font-family: var(--ncg-font-display);
  font-size: var(--ncg-fs-xl);
  font-weight: 600;
  letter-spacing: -0.01em;
}
.ncg-page__desc {
  margin-top: 4px;
  color: var(--ncg-text-3);
  font-size: var(--ncg-fs-sm);
  max-width: var(--ncg-measure);
}
.ncg-page__actions {
  display: flex;
  align-items: center;
  gap: var(--ncg-space-1);
}
</style>
