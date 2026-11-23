<script setup lang="ts">
/**
 * AppLayout —— 布局壳（详细设计 §3.5.2）
 * 侧边导航：11 页按 治理/发布/监督/管理 分组，权限驱动渲染（无权限的项不渲染）
 * 顶栏：当前用户 / 角色 / 退出 + 只读实例状态
 */
import { computed, onMounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessageBox } from 'element-plus'
import 'element-plus/es/components/message-box/style/css'
import 'element-plus/es/components/avatar/style/css'
import 'element-plus/es/components/tooltip/style/css'
import 'element-plus/es/components/tag/style/css'
import 'element-plus/es/components/button/style/css'
import { NAV_GROUPS } from '@/router/nav'
import { useAuthStore } from '@/stores/auth'
import { useAppStore } from '@/stores/app'

const route = useRoute()
const router = useRouter()
const auth = useAuthStore()
const app = useAppStore()

/** 环境清单（envId↔envCode↔displayName）全局加载一次；失败静默，getters 回退 */
onMounted(() => {
  void app.loadEnvs()
})

/** 权限驱动渲染：没有所需权限点的导航项不出现 */
const visibleGroups = computed(() =>
  NAV_GROUPS.map((g) => ({
    ...g,
    items: g.items.filter((item) => !item.perm || auth.can(item.perm)),
  })).filter((g) => g.items.length > 0),
)

async function handleLogout() {
  try {
    await ElMessageBox.confirm('确认退出登录？', '退出', { type: 'warning' })
  } catch {
    return
  }
  await auth.logout()
  router.push({ name: 'login' })
}
</script>

<template>
  <div class="ncg-layout">
    <aside class="ncg-sidebar" :class="{ 'ncg-sidebar--collapsed': app.sidebarCollapsed }">
      <div class="ncg-sidebar__brand">
        <span class="ncg-sidebar__logo" aria-hidden="true">◆</span>
        <span v-if="!app.sidebarCollapsed" class="ncg-sidebar__name">NCG 配置治理</span>
      </div>
      <nav class="ncg-sidebar__nav" aria-label="主导航">
        <div v-for="group in visibleGroups" :key="group.group" class="ncg-nav-group">
          <div v-if="!app.sidebarCollapsed" class="ncg-nav-group__label">{{ group.group }}</div>
          <router-link
            v-for="item in group.items"
            :key="item.name"
            :to="item.path"
            class="ncg-nav-item"
            :class="{ 'ncg-nav-item--active': route.name === item.name }"
            :title="item.title"
          >
            <span class="ncg-nav-item__dot" aria-hidden="true" />
            <span v-if="!app.sidebarCollapsed" class="ncg-nav-item__title">{{ item.title }}</span>
          </router-link>
        </div>
      </nav>
    </aside>

    <div class="ncg-main">
      <header class="ncg-topbar">
        <button
          class="ncg-topbar__toggle"
          :aria-label="app.sidebarCollapsed ? '展开侧边栏' : '收起侧边栏'"
          @click="app.toggleSidebar()"
        >
          ☰
        </button>
        <div class="ncg-topbar__env">
          <el-select
            :model-value="app.env"
            size="small"
            class="ncg-topbar__env-select"
            aria-label="切换环境"
            @change="(v: string) => app.setEnv(v as 'dev' | 'staging' | 'prod')"
          >
            <el-option v-for="opt in app.envOptions" :key="opt.value" :label="opt.label" :value="opt.value" />
          </el-select>
          <el-tag v-if="!app.instanceAvailable" type="warning" size="small">实例不可用 · 只读</el-tag>
        </div>
        <div class="ncg-topbar__user">
          <template v-if="auth.user">
            <el-tooltip :content="`权限点：${auth.permissions.join('、') || '无'}`" placement="bottom">
              <div class="ncg-topbar__identity">
                <el-avatar :size="28">{{ auth.user.displayName?.charAt(0) ?? 'U' }}</el-avatar>
                <div class="ncg-topbar__names">
                  <span class="ncg-topbar__display">{{ auth.user.displayName }}</span>
                  <span class="ncg-topbar__roles ncg-muted">{{ auth.roles.join(' · ') || '无角色' }}</span>
                </div>
              </div>
            </el-tooltip>
            <el-button size="small" @click="handleLogout">退出登录</el-button>
          </template>
        </div>
      </header>

      <main class="ncg-content">
        <router-view v-slot="{ Component }">
          <transition name="fade-slide" mode="out-in">
            <component :is="Component" />
          </transition>
        </router-view>
      </main>
    </div>
  </div>
</template>

<style scoped>
.ncg-layout {
  display: flex;
  min-height: 100vh;
}

/* ── 侧边导航 ─────────────────────────────── */
.ncg-sidebar {
  width: var(--ncg-layout-sidebar);
  flex-shrink: 0;
  display: flex;
  flex-direction: column;
  background: var(--ncg-ink);
  border-right: 1px solid var(--ncg-line);
  transition: width var(--ncg-dur-base) var(--ncg-ease);
}
.ncg-sidebar--collapsed {
  width: 56px;
}
.ncg-sidebar__brand {
  display: flex;
  align-items: center;
  gap: var(--ncg-space-1);
  height: var(--ncg-layout-topbar);
  padding: 0 var(--ncg-space-2);
  border-bottom: 1px solid var(--ncg-line);
}
.ncg-sidebar__logo {
  color: var(--ncg-volt);
  font-size: var(--ncg-fs-lg);
}
.ncg-sidebar__name {
  font-family: var(--ncg-font-display);
  font-weight: 600;
  font-size: var(--ncg-fs-md);
  white-space: nowrap;
}
.ncg-sidebar__nav {
  flex: 1;
  overflow-y: auto;
  padding: var(--ncg-space-2) var(--ncg-space-1);
  display: flex;
  flex-direction: column;
  gap: var(--ncg-space-2);
}
.ncg-nav-group__label {
  padding: 0 var(--ncg-space-1);
  margin-bottom: 4px;
  font-size: var(--ncg-fs-xs);
  color: var(--ncg-text-3);
  letter-spacing: 0.08em;
}
.ncg-nav-item {
  display: flex;
  align-items: center;
  gap: var(--ncg-space-1);
  padding: 8px var(--ncg-space-1);
  border-radius: var(--ncg-radius-sm);
  color: var(--ncg-text-2);
  font-size: var(--ncg-fs-sm);
  text-decoration: none;
  transition:
    color var(--ncg-dur-micro) var(--ncg-ease),
    background var(--ncg-dur-micro) var(--ncg-ease);
}
.ncg-nav-item:hover {
  color: var(--ncg-text-1);
  background: var(--ncg-panel);
}
.ncg-nav-item--active {
  color: var(--ncg-volt);
  background: var(--ncg-volt-bg);
}
.ncg-nav-item__dot {
  width: 5px;
  height: 5px;
  border-radius: 50%;
  background: currentColor;
  flex-shrink: 0;
}
.ncg-nav-item__title {
  white-space: nowrap;
}

/* ── 主区 ─────────────────────────────── */
.ncg-main {
  flex: 1;
  display: flex;
  flex-direction: column;
  min-width: 0;
}
.ncg-topbar {
  display: flex;
  align-items: center;
  gap: var(--ncg-space-2);
  height: var(--ncg-layout-topbar);
  padding: 0 var(--ncg-space-3);
  border-bottom: 1px solid var(--ncg-line);
  background: oklch(17% 0.012 265 / 0.8);
  position: sticky;
  top: 0;
  backdrop-filter: blur(8px);
}
.ncg-topbar__toggle {
  background: none;
  border: none;
  color: var(--ncg-text-2);
  font-size: var(--ncg-fs-md);
  cursor: pointer;
  padding: 4px;
}
.ncg-topbar__toggle:hover {
  color: var(--ncg-text-1);
}
.ncg-topbar__env {
  display: flex;
  align-items: center;
  gap: var(--ncg-space-1);
}
.ncg-topbar__env-select {
  width: 132px;
}
.ncg-topbar__user {
  margin-left: auto;
  display: flex;
  align-items: center;
  gap: var(--ncg-space-2);
}
.ncg-topbar__identity {
  display: flex;
  align-items: center;
  gap: var(--ncg-space-1);
  cursor: default;
}
.ncg-topbar__names {
  display: flex;
  flex-direction: column;
  line-height: 1.25;
}
.ncg-topbar__display {
  font-size: var(--ncg-fs-sm);
  color: var(--ncg-text-1);
}
.ncg-topbar__roles {
  font-size: var(--ncg-fs-xs);
}

.ncg-content {
  flex: 1;
  padding: var(--ncg-space-3) var(--ncg-space-4) var(--ncg-space-6);
  max-width: var(--ncg-content-max);
  width: 100%;
}
</style>
