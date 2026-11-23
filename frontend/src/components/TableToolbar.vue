<script setup lang="ts">
/**
 * TableToolbar —— 表格工具条（搜索 + 筛选 + 操作 + 刷新）
 * 各模块列表页统一使用；筛选条件变化由页面自行触发查询
 */
withDefaults(
  defineProps<{
    searchValue?: string
    searchPlaceholder?: string
    /** 查询中（用于按钮 loading 态） */
    loading?: boolean
  }>(),
  {
    searchValue: '',
    searchPlaceholder: '搜索关键字',
    loading: false,
  },
)

const emit = defineEmits<{
  'update:searchValue': [value: string]
  search: []
  refresh: []
}>()
</script>

<template>
  <div class="ncg-toolbar">
    <div class="ncg-toolbar__left">
      <el-input
        class="ncg-toolbar__search"
        :model-value="searchValue"
        :placeholder="searchPlaceholder"
        clearable
        @update:model-value="emit('update:searchValue', $event)"
        @keyup.enter="emit('search')"
      >
        <template #prefix>
          <svg width="14" height="14" viewBox="0 0 14 14" fill="none" aria-hidden="true">
            <circle cx="6" cy="6" r="4.25" stroke="currentColor" stroke-width="1.4" />
            <path d="M9.5 9.5L12.5 12.5" stroke="currentColor" stroke-width="1.4" stroke-linecap="round" />
          </svg>
        </template>
      </el-input>
      <!-- 筛选控件槽（下拉、日期等） -->
      <slot name="filters" />
    </div>
    <div class="ncg-toolbar__right">
      <slot name="actions" />
      <el-button :loading="loading" title="刷新列表" aria-label="刷新列表" @click="emit('refresh')">
        刷新
      </el-button>
    </div>
  </div>
</template>

<style scoped>
.ncg-toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--ncg-space-2);
  flex-wrap: wrap;
  padding: var(--ncg-space-2) 0;
}
.ncg-toolbar__left,
.ncg-toolbar__right {
  display: flex;
  align-items: center;
  gap: var(--ncg-space-1);
  flex-wrap: wrap;
}
.ncg-toolbar__search {
  width: 280px;
}
</style>
