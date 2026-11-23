<script setup lang="ts">
/**
 * 管理与设置 —— P-14 账号与授权 / P-15 实例与环境 / P-16 通知绑定
 * （契约 §2.5(36-40,47-48)、详细设计 §3.5.2 管理 / 原型 admin 视图）
 * 能力：账号列表与新建、授权列表与新建（app × env × 权限点）、实例列表与连接测试、
 *       通知绑定保存（PUT upsert）与解绑（DELETE 幂等）。
 * 敏感红线（MUST-NOT-05）：通知目标（Webhook）恒脱敏展示，保存时重新输入，不回显旧明文。
 * 权限（§9）：全部动作需 ADMIN 权限点；无权限不渲染动作，仅说明所需权限点。
 * 契约 48 端点无“禁用账号”能力，故不渲染禁用动作。
 */
import { computed, onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import type { FormInstance, FormRules } from 'element-plus'
import 'element-plus/es/components/message/style/css'
import { useAdminStore, bindingKey } from '@/stores/admin'
import { useAuthStore } from '@/stores/auth'
import { useAppStore } from '@/stores/app'
import type { EnvId, NotifyBinding, SaveNotifyBindingRequest } from '@/api/types'

const store = useAdminStore()
const auth = useAuthStore()
const appStore = useAppStore()

const canAdmin = computed(() => auth.can('ADMIN'))
const tab = ref('users')

/** 时间统一按 Asia/Shanghai 展示（§6.1） */
const timeFmt = new Intl.DateTimeFormat('zh-CN', {
  timeZone: 'Asia/Shanghai', year: 'numeric', month: '2-digit', day: '2-digit',
  hour: '2-digit', minute: '2-digit', second: '2-digit', hour12: false,
})
function fmtTime(iso: string): string {
  if (!iso) return '—'
  const d = new Date(iso)
  return Number.isNaN(d.getTime()) ? iso : timeFmt.format(d).replace(/\//g, '-')
}

function envLabel(envId: string): string {
  return appStore.envLabelOf(envId)
}

/** 10 个权限点（§9），用于授权对话框与列表标签 */
const PERMISSION_OPTIONS = [
  { value: 'VIEW', label: '查看配置' },
  { value: 'EDIT', label: '编辑变更' },
  { value: 'SUBMIT', label: '提交变更' },
  { value: 'APPROVE', label: '审批' },
  { value: 'PUBLISH', label: '发布' },
  { value: 'ROLLBACK', label: '回滚' },
  { value: 'VIEW_SENSITIVE', label: '查看敏感值' },
  { value: 'VIEW_AUDIT', label: '查看审计' },
  { value: 'EXPORT_AUDIT', label: '导出审计' },
  { value: 'ADMIN', label: '管理' },
]

/** 建号角色建议（文档未固定枚举，支持自定义输入） */
const ROLE_SUGGESTIONS = ['应用负责人', '编辑者', '发布人', '审批人', '审计员', '管理员']

/** 用户在授权表中的范围摘要（app × env） */
function grantScopeOf(userId: string): string[] {
  const scope = store.grants.filter((g) => g.userId === userId).map((g) => `${g.appId} · ${appStore.envLabelOf(g.envId)}`)
  return [...new Set(scope)]
}

function permissionLabel(code: string): string {
  return PERMISSION_OPTIONS.find((p) => p.value === code)?.label ?? code
}

// ---- P-14 新建账号 ----
const userDialogVisible = ref(false)
const userFormRef = ref<FormInstance | null>(null)
const userForm = ref({ username: '', displayName: '', password: '', roles: [] as string[] })
const userRules: FormRules = {
  username: [{ required: true, message: '请输入账号', trigger: 'blur' }],
  displayName: [{ required: true, message: '请输入显示名', trigger: 'blur' }],
  password: [
    { required: true, message: '请输入初始密码', trigger: 'blur' },
    { min: 12, message: '密码至少 12 位（§9.1）', trigger: 'blur' },
  ],
}

function openUserDialog() {
  userForm.value = { username: '', displayName: '', password: '', roles: [] }
  userDialogVisible.value = true
}

async function submitUser() {
  const valid = await userFormRef.value?.validate().catch(() => false)
  if (valid !== true) return
  try {
    await store.createUser({
      username: userForm.value.username.trim(),
      displayName: userForm.value.displayName.trim(),
      password: userForm.value.password,
      roles: userForm.value.roles,
    })
    userDialogVisible.value = false
    ElMessage.success('账号已创建（密码以 Argon2id 存储，服务端不落明文）')
  } catch {
    // 全局拦截器已按 code 提示（AUTHZ-403-001 等），保留已填内容
  }
}

// ---- P-14 新建授权 ----
const grantDialogVisible = ref(false)
const grantFormRef = ref<FormInstance | null>(null)
const grantForm = ref({ userId: '', appId: '', envId: '' as EnvId, permission: 'VIEW' })
const grantRules: FormRules = {
  userId: [{ required: true, message: '请选择用户', trigger: 'change' }],
  appId: [{ required: true, message: '请选择应用', trigger: 'change' }],
  envId: [{ required: true, message: '请选择环境', trigger: 'change' }],
  permission: [{ required: true, message: '请选择权限点', trigger: 'change' }],
}

function openGrantDialog() {
  grantForm.value = { userId: '', appId: '', envId: '' as EnvId, permission: 'VIEW' }
  grantDialogVisible.value = true
}

async function submitGrant() {
  const valid = await grantFormRef.value?.validate().catch(() => false)
  if (valid !== true) return
  try {
    await store.createGrant({
      userId: grantForm.value.userId,
      appId: grantForm.value.appId,
      envId: grantForm.value.envId,
      permission: grantForm.value.permission,
    })
    grantDialogVisible.value = false
    ElMessage.success('授权已生效（范围：应用 × 环境 × 权限点）')
  } catch {
    // AUTHZ-403-001 / CR-400-001 由全局拦截器提示
  }
}

// ---- P-15 连接测试（写操作三段式：预检→执行→读回） ----
async function runTest(id: string) {
  try {
    const result = await store.testInstance(id)
    if (result.ok) {
      ElMessage.success(`实例连接正常${result.latencyMs != null ? `（${result.latencyMs}ms）` : ''}`)
    }
  } catch {
    // CON-502-001 等由全局拦截器提示，结果仍由读回状态展示
  }
}

function healthClass(health: string): string {
  if (health === 'UP') return 'ncg-health--up'
  if (health === 'DEGRADED') return 'ncg-health--degraded'
  return 'ncg-health--down'
}

function healthText(health: string): string {
  if (health === 'UP') return '正常'
  if (health === 'DEGRADED') return '降级'
  return '不可用'
}

// ---- P-16 通知绑定（保存 / 解绑） ----
const bindingDialogVisible = ref(false)
const bindingFormRef = ref<FormInstance | null>(null)
const bindingEditMode = ref(false)
const bindingForm = ref({ appId: '', envId: '' as EnvId, channel: 'WECOM', target: '' })
const bindingRules = computed<FormRules>(() => ({
  appId: [{ required: true, message: '请选择应用', trigger: 'change' }],
  envId: [{ required: true, message: '请选择环境', trigger: 'change' }],
  channel: [{ required: true, message: '请选择通知渠道', trigger: 'change' }],
  target: [
    {
      required: bindingForm.value.channel !== 'IN_APP',
      message: '企业微信 Webhook 地址必填（敏感值不回显，保存需重新填写）',
      trigger: 'blur',
    },
  ],
}))

function openBindingDialog(row?: unknown) {
  // el-table DefaultRow 回调形参先收窄再进入对话框（t3 同款写法）
  const binding = (row ?? null) as NotifyBinding | null
  bindingEditMode.value = !!binding
  bindingForm.value = binding
    ? { appId: binding.appId, envId: binding.envId, channel: binding.channel, target: '' }
    : { appId: '', envId: '' as EnvId, channel: 'WECOM', target: '' }
  bindingDialogVisible.value = true
}

async function submitBinding() {
  const valid = await bindingFormRef.value?.validate().catch(() => false)
  if (valid !== true) return
  const { appId, envId, channel, target } = bindingForm.value
  const data: SaveNotifyBindingRequest = { channel, target: target.trim() }
  try {
    await store.saveBinding(appId, envId, data)
    bindingDialogVisible.value = false
    ElMessage.success('通知绑定已保存（目标加密存储、界面脱敏展示）')
  } catch {
    // CR-400-001 / SYS-404-001 由全局拦截器提示，保留已填内容
  }
}

const unbindDialogVisible = ref(false)
const pendingUnbind = ref<NotifyBinding | null>(null)

function openUnbindDialog(row: unknown) {
  // el-table DefaultRow 回调形参先收窄（t3 同款写法）
  pendingUnbind.value = row as NotifyBinding
  unbindDialogVisible.value = true
}

async function confirmUnbind() {
  if (!pendingUnbind.value) return
  const { appId, envId } = pendingUnbind.value
  unbindDialogVisible.value = false
  try {
    await store.deleteBinding(appId, envId)
    ElMessage.success('通知绑定已解除（接口幂等）')
    pendingUnbind.value = null
  } catch {
    // SYS-404-001 等由全局拦截器提示
  }
}

onMounted(() => {
  if (canAdmin.value) void store.init()
})
</script>

<template>
  <PageShell
    title="管理与设置"
    description="自建账号与“应用 × 环境”授权；实例与环境一一绑定；通知绑定按应用配置。敏感值全程脱敏。"
    :loading="store.usersLoading && store.users.length === 0"
  >
    <!-- 无权限（§3.5.4）：不渲染动作，说明所需权限点 -->
    <el-alert
      v-if="!canAdmin"
      type="info"
      :closable="false"
      title="当前账号缺少 ADMIN 权限点"
      description="账号与授权、实例与环境、通知绑定的查看与操作均需要 ADMIN 权限点。请联系平台管理员授权后再访问本页。"
    />

    <el-tabs v-else v-model="tab" class="ncg-admin__tabs">
      <!-- P-14 账号与授权 -->
      <el-tab-pane label="账号与授权" name="users">
        <TableToolbar search-placeholder="账号 / 显示名（本地过滤）" :loading="store.usersLoading" @refresh="store.fetchUsers()">
          <template #actions>
            <el-button type="primary" @click="openUserDialog">新建账号</el-button>
            <el-button @click="openGrantDialog">新建授权</el-button>
          </template>
        </TableToolbar>

        <h3 class="ncg-admin__section-title">账号</h3>
        <SkeletonBlock v-if="store.usersLoading && store.users.length === 0" type="table" :rows="4" />
        <ErrorState
          v-else-if="store.usersError"
          :code="store.usersError.code"
          :message="store.usersError.message"
          :trace-id="store.usersError.traceId"
          suggestion="账号列表拉取失败，可重试。"
          @retry="store.fetchUsers()"
        />
        <template v-else>
          <el-table :data="store.users" row-key="userId" v-loading="store.usersLoading">
            <el-table-column label="账号" min-width="200">
              <template #default="{ row }">
                <div class="ncg-admin__user-cell">
                  <span class="ncg-admin__user-name">{{ row.displayName }}</span>
                  <code class="ncg-mono ncg-muted">{{ row.username }}</code>
                </div>
              </template>
            </el-table-column>
            <el-table-column label="角色" min-width="220">
              <template #default="{ row }">
                <el-tag v-for="role in row.roles" :key="role" size="small" class="ncg-admin__tag">{{ role }}</el-tag>
                <span v-if="!row.roles || row.roles.length === 0" class="ncg-muted">未分配</span>
              </template>
            </el-table-column>
            <el-table-column label="授权范围（应用 · 环境）" min-width="240">
              <template #default="{ row }">
                <template v-if="grantScopeOf(row.userId).length > 0">
                  <el-tag
                    v-for="scope in grantScopeOf(row.userId).slice(0, 3)"
                    :key="scope"
                    size="small"
                    type="info"
                    effect="plain"
                    class="ncg-admin__tag"
                  >
                    {{ scope }}
                  </el-tag>
                  <span v-if="grantScopeOf(row.userId).length > 3" class="ncg-muted">
                    +{{ grantScopeOf(row.userId).length - 3 }}
                  </span>
                </template>
                <span v-else class="ncg-muted">无授权</span>
              </template>
            </el-table-column>
            <el-table-column label="状态" width="110">
              <template #default="{ row }">
                <el-tag :type="row.enabled ? 'success' : 'danger'" size="small" round>
                  {{ row.enabled ? '正常' : '已禁用' }}
                </el-tag>
              </template>
            </el-table-column>
            <el-table-column label="创建时间" width="170">
              <template #default="{ row }">
                <span class="ncg-data ncg-admin__time">{{ fmtTime(row.createdAt) }}</span>
              </template>
            </el-table-column>
          </el-table>
          <EmptyState
            v-if="!store.usersLoading && store.users.length === 0"
            reason="尚未创建任何自建账号"
            next-step="点击“新建账号”创建；密码最小 12 位，由服务端以 Argon2id 存储。"
            action-text="新建账号"
            @action="openUserDialog"
          />
        </template>

        <h3 class="ncg-admin__section-title">授权（应用 × 环境 × 权限点）</h3>
        <SkeletonBlock v-if="store.grantsLoading && store.grants.length === 0" type="table" :rows="3" />
        <ErrorState
          v-else-if="store.grantsError"
          :code="store.grantsError.code"
          :message="store.grantsError.message"
          :trace-id="store.grantsError.traceId"
          suggestion="授权列表拉取失败，可重试。"
          @retry="store.fetchGrants()"
        />
        <template v-else>
          <el-table :data="store.grants" row-key="grantId" v-loading="store.grantsLoading">
            <el-table-column label="用户" min-width="180">
              <template #default="{ row }">
                <span>{{ row.username }}</span>
              </template>
            </el-table-column>
            <el-table-column label="应用" min-width="160">
              <template #default="{ row }">
                <code class="ncg-mono">{{ row.appId }}</code>
              </template>
            </el-table-column>
            <el-table-column label="环境" width="140">
              <template #default="{ row }">
                <span>{{ envLabel(row.envId) }}</span>
              </template>
            </el-table-column>
            <el-table-column label="权限点" width="160">
              <template #default="{ row }">
                <el-tag size="small" type="warning" effect="plain">{{ permissionLabel(row.permission) }}</el-tag>
                <code class="ncg-mono ncg-muted ncg-admin__perm-code">{{ row.permission }}</code>
              </template>
            </el-table-column>
            <el-table-column label="授权人" width="140">
              <template #default="{ row }">
                <code class="ncg-mono ncg-muted">{{ row.grantedBy }}</code>
              </template>
            </el-table-column>
            <el-table-column label="授权时间" width="170">
              <template #default="{ row }">
                <span class="ncg-data ncg-admin__time">{{ fmtTime(row.grantedAt) }}</span>
              </template>
            </el-table-column>
          </el-table>
          <EmptyState
            v-if="!store.grantsLoading && store.grants.length === 0"
            reason="尚无任何授权记录"
            next-step="点击“新建授权”，按 应用 × 环境 × 权限点 授予最小必要权限。"
            action-text="新建授权"
            @action="openGrantDialog"
          />
        </template>
      </el-tab-pane>

      <!-- P-15 实例与环境 -->
      <el-tab-pane label="实例与环境" name="instances">
        <el-alert
          type="info"
          :closable="false"
          title="实例与环境一一绑定"
          description="每环境一实例；健康状态由后端探测给出，不健康实例将阻断写操作。连接测试结果即时回显。"
          class="ncg-admin__banner"
        />

        <TableToolbar search-placeholder="实例 / 端点（本地过滤）" :loading="store.instancesLoading" @refresh="store.fetchInstances()" />

        <SkeletonBlock v-if="store.instancesLoading && store.instances.length === 0" type="card" :rows="3" />
        <ErrorState
          v-else-if="store.instancesError"
          :code="store.instancesError.code"
          :message="store.instancesError.message"
          :trace-id="store.instancesError.traceId"
          suggestion="实例列表拉取失败，可重试；写操作依赖健康状态，读回为准。"
          @retry="store.fetchInstances()"
        />
        <EmptyState
          v-else-if="store.instances.length === 0"
          reason="尚未登记任何 Nacos 实例"
          next-step="实例由服务端注册与探测；如为空请联系平台管理员检查实例登记。"
        />
        <div v-else class="ncg-admin__instance-grid">
          <section v-for="inst in store.instances" :key="inst.instanceId" class="ncg-admin__instance-card">
            <header class="ncg-admin__instance-head">
              <span class="ncg-admin__instance-env">{{ envLabel(inst.envId) }}</span>
              <span class="ncg-health" :class="healthClass(inst.health)">{{ healthText(inst.health) }}</span>
            </header>
            <code class="ncg-mono ncg-admin__instance-endpoint" :title="inst.endpoint">{{ inst.endpoint }}</code>
            <div class="ncg-muted ncg-admin__instance-meta">
              <code class="ncg-mono">{{ inst.instanceId }}</code>
              <span>探测于 {{ fmtTime(inst.lastCheckedAt) }}</span>
            </div>
            <div class="ncg-admin__instance-foot">
              <el-button
                size="small"
                type="primary"
                plain
                :loading="store.testingId === inst.instanceId"
                @click="runTest(inst.instanceId)"
              >
                测试连接
              </el-button>
              <span
                v-if="store.testResults[inst.instanceId]"
                class="ncg-admin__test-result"
                :class="store.testResults[inst.instanceId].ok ? 'ncg-admin__test-result--ok' : 'ncg-admin__test-result--bad'"
              >
                <template v-if="store.testResults[inst.instanceId].ok">
                  连接正常{{ store.testResults[inst.instanceId].latencyMs != null ? ` · ${store.testResults[inst.instanceId].latencyMs}ms` : '' }}
                  · {{ fmtTime(store.testResults[inst.instanceId].testedAt) }}
                </template>
                <template v-else>
                  连接失败：{{ store.testResults[inst.instanceId].message || '实例不可达（CON-502-001）' }}
                </template>
              </span>
            </div>
          </section>
        </div>
      </el-tab-pane>

      <!-- P-16 通知绑定 -->
      <el-tab-pane label="通知绑定" name="bindings">
        <el-alert
          type="info"
          :closable="false"
          title="通知失败不阻塞业务"
          description="通知发送失败不阻塞业务流程，连续失败重试 3 次后只写审计。通知内容不含敏感明文；Webhook 目标加密存储、界面恒脱敏展示（MUST-NOT-05）。"
          class="ncg-admin__banner"
        />

        <TableToolbar search-placeholder="应用 / 环境（本地过滤）" :loading="store.bindingsLoading" @refresh="store.fetchBindings()">
          <template #actions>
            <el-button type="primary" @click="openBindingDialog()">新增绑定</el-button>
          </template>
        </TableToolbar>

        <SkeletonBlock v-if="store.bindingsLoading && store.bindings.length === 0" type="table" :rows="3" />
        <ErrorState
          v-else-if="store.bindingsError"
          :code="store.bindingsError.code"
          :message="store.bindingsError.message"
          :trace-id="store.bindingsError.traceId"
          suggestion="通知绑定列表拉取失败，可重试。"
          @retry="store.fetchBindings()"
        />
        <template v-else>
          <el-table :data="store.bindings" :row-key="bindingKey" v-loading="store.bindingsLoading">
            <el-table-column label="应用" min-width="180">
              <template #default="{ row }">
                <code class="ncg-mono">{{ row.appId }}</code>
              </template>
            </el-table-column>
            <el-table-column label="环境" width="140">
              <template #default="{ row }">
                <span>{{ envLabel(row.envId) }}</span>
              </template>
            </el-table-column>
            <el-table-column label="渠道" width="140">
              <template #default="{ row }">
                <el-tag size="small" effect="plain">{{ row.channel === 'IN_APP' ? '站内信' : '企业微信' }}</el-tag>
              </template>
            </el-table-column>
            <el-table-column label="目标" min-width="180">
              <template #default>
                <!-- MUST-NOT-05：目标（Webhook 密文）恒脱敏，不回显任何明文 -->
                <code class="ncg-data ncg-admin__masked">●●●●●●●●</code>
                <span class="ncg-muted ncg-admin__masked-note">已脱敏</span>
              </template>
            </el-table-column>
            <el-table-column label="更新时间" width="170">
              <template #default="{ row }">
                <span class="ncg-data ncg-admin__time">{{ fmtTime(row.updatedAt) }}</span>
              </template>
            </el-table-column>
            <el-table-column label="操作" width="180" fixed="right">
              <template #default="{ row }">
                <el-button link type="primary" size="small" @click="openBindingDialog(row)">重新保存</el-button>
                <el-button
                  link
                  type="danger"
                  size="small"
                  :loading="store.deletingBindingKey === `${row.appId}::${row.envId}`"
                  @click="openUnbindDialog(row)"
                >
                  解绑
                </el-button>
              </template>
            </el-table-column>
          </el-table>
          <EmptyState
            v-if="!store.bindingsLoading && store.bindings.length === 0"
            reason="尚未配置任何通知绑定"
            next-step="点击“新增绑定”，按 应用 × 环境 绑定企业微信 Webhook 或站内信；目标加密存储、界面脱敏。"
            action-text="新增绑定"
            @action="openBindingDialog()"
          />
        </template>
      </el-tab-pane>
    </el-tabs>

    <!-- 新建账号（P-14） -->
    <el-dialog v-model="userDialogVisible" title="新建账号" width="460px">
      <el-form ref="userFormRef" :model="userForm" :rules="userRules" label-position="top">
        <el-form-item label="账号" prop="username">
          <el-input v-model="userForm.username" placeholder="登录名，全局唯一" autocomplete="off" />
        </el-form-item>
        <el-form-item label="显示名" prop="displayName">
          <el-input v-model="userForm.displayName" placeholder="姓名或花名" autocomplete="off" />
        </el-form-item>
        <el-form-item label="初始密码" prop="password">
          <el-input
            v-model="userForm.password"
            type="password"
            show-password
            placeholder="至少 12 位"
            autocomplete="new-password"
          />
        </el-form-item>
        <el-form-item label="角色">
          <el-select
            v-model="userForm.roles"
            multiple
            filterable
            allow-create
            default-first-option
            placeholder="选择或输入角色"
            class="ncg-admin__full"
          >
            <el-option v-for="role in ROLE_SUGGESTIONS" :key="role" :value="role" :label="role" />
          </el-select>
        </el-form-item>
      </el-form>
      <el-alert
        type="info"
        :closable="false"
        title="密码与角色"
        description="密码最小 12 位，由服务端以 Argon2id 存储，不落明文（§9.1）；角色仅作人员组织标签，实际权限以“授权”为准。"
      />
      <template #footer>
        <el-button @click="userDialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="store.submittingUser" @click="submitUser">创建账号</el-button>
      </template>
    </el-dialog>

    <!-- 新建授权（P-14） -->
    <el-dialog v-model="grantDialogVisible" title="新建授权" width="460px">
      <el-form ref="grantFormRef" :model="grantForm" :rules="grantRules" label-position="top">
        <el-form-item label="用户" prop="userId">
          <el-select v-model="grantForm.userId" filterable placeholder="选择用户" class="ncg-admin__full">
            <el-option v-for="opt in store.userOptions" :key="opt.value" :value="opt.value" :label="opt.label" />
          </el-select>
        </el-form-item>
        <el-form-item label="应用" prop="appId">
          <el-select v-model="grantForm.appId" filterable placeholder="选择应用" class="ncg-admin__full">
            <el-option v-for="opt in store.appOptions" :key="opt.value" :value="opt.value" :label="opt.label" />
          </el-select>
        </el-form-item>
        <el-form-item label="环境" prop="envId">
          <el-select v-model="grantForm.envId" placeholder="选择环境" class="ncg-admin__full">
            <el-option v-for="opt in appStore.envIdOptions" :key="opt.value" :value="opt.value" :label="opt.label" />
          </el-select>
        </el-form-item>
        <el-form-item label="权限点" prop="permission">
          <el-select v-model="grantForm.permission" placeholder="选择权限点" class="ncg-admin__full">
            <el-option v-for="opt in PERMISSION_OPTIONS" :key="opt.value" :value="opt.value" :label="`${opt.label}（${opt.value}）`" />
          </el-select>
        </el-form-item>
      </el-form>
      <el-alert
        type="info"
        :closable="false"
        title="最小必要授权"
        description="授权范围 = 应用 × 环境 × 权限点（§9）；敏感值查看（VIEW_SENSITIVE）与审计导出（EXPORT_AUDIT）会写审计，请谨慎授予。"
      />
      <template #footer>
        <el-button @click="grantDialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="store.submittingGrant" @click="submitGrant">授予</el-button>
      </template>
    </el-dialog>

    <!-- 保存通知绑定（P-16） -->
    <el-dialog v-model="bindingDialogVisible" :title="bindingEditMode ? '重新保存通知绑定' : '新增通知绑定'" width="480px">
      <el-form ref="bindingFormRef" :model="bindingForm" :rules="bindingRules" label-position="top">
        <el-form-item label="应用" prop="appId">
          <el-select
            v-model="bindingForm.appId"
            filterable
            placeholder="选择应用"
            class="ncg-admin__full"
            :disabled="bindingEditMode"
          >
            <el-option v-for="opt in store.appOptions" :key="opt.value" :value="opt.value" :label="opt.label" />
          </el-select>
        </el-form-item>
        <el-form-item label="环境" prop="envId">
          <el-select v-model="bindingForm.envId" placeholder="选择环境" class="ncg-admin__full" :disabled="bindingEditMode">
            <el-option v-for="opt in appStore.envIdOptions" :key="opt.value" :value="opt.value" :label="opt.label" />
          </el-select>
        </el-form-item>
        <el-form-item label="通知渠道" prop="channel">
          <el-radio-group v-model="bindingForm.channel">
            <el-radio-button value="WECOM">企业微信 Webhook</el-radio-button>
            <el-radio-button value="IN_APP">站内信</el-radio-button>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="目标地址" prop="target">
          <el-input
            v-model="bindingForm.target"
            :placeholder="bindingForm.channel === 'WECOM' ? '企业微信 Webhook 地址' : '站内信可留空'"
            autocomplete="off"
          />
        </el-form-item>
      </el-form>
      <el-alert
        type="warning"
        :closable="false"
        :title="bindingEditMode ? '敏感值不回显' : '目标加密存储'"
        description="Webhook 目标以密文存储，界面恒脱敏展示（MUST-NOT-05）；保存需重新填写目标地址，接口为 upsert（同应用 × 环境 + 渠道覆盖旧绑定）。"
      />
      <template #footer>
        <el-button @click="bindingDialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="store.savingBinding" @click="submitBinding">保存绑定</el-button>
      </template>
    </el-dialog>

    <!-- 解绑确认（二次确认 → 执行 → 读回） -->
    <el-dialog v-model="unbindDialogVisible" title="解除通知绑定" width="420px">
      <el-descriptions v-if="pendingUnbind" :column="1" border size="small">
        <el-descriptions-item label="应用">
          <code class="ncg-mono">{{ pendingUnbind.appId }}</code>
        </el-descriptions-item>
        <el-descriptions-item label="环境">{{ envLabel(pendingUnbind.envId) }}</el-descriptions-item>
        <el-descriptions-item label="渠道">
          {{ pendingUnbind.channel === 'IN_APP' ? '站内信' : '企业微信 Webhook' }}
        </el-descriptions-item>
        <el-descriptions-item label="目标">
          <code class="ncg-data">●●●●●●●●</code>
        </el-descriptions-item>
      </el-descriptions>
      <el-alert
        type="warning"
        :closable="false"
        title="确认解绑？"
        description="解绑后该应用 × 环境的此渠道通知将停止发送；操作幂等，重复解绑不会报错。"
        class="ncg-admin__confirm-alert"
      />
      <template #footer>
        <el-button @click="unbindDialogVisible = false">保留绑定</el-button>
        <el-button type="danger" :loading="!!store.deletingBindingKey" @click="confirmUnbind">确认解绑</el-button>
      </template>
    </el-dialog>
  </PageShell>
</template>

<style scoped>
.ncg-admin__tabs {
  width: 100%;
}

.ncg-admin__banner {
  margin-bottom: var(--ncg-space-3);
}

.ncg-admin__section-title {
  font-family: var(--ncg-font-display);
  font-size: var(--ncg-fs-md);
  font-weight: 600;
  color: var(--ncg-text-1);
  margin: var(--ncg-space-4) 0 var(--ncg-space-2);
}

.ncg-admin__user-cell {
  display: flex;
  flex-direction: column;
  gap: 2px;
}

.ncg-admin__user-name {
  font-weight: 600;
  color: var(--ncg-text-1);
}

.ncg-admin__tag {
  margin-right: 6px;
  margin-bottom: 2px;
}

.ncg-admin__perm-code {
  margin-left: 6px;
  font-size: var(--ncg-fs-xs);
}

.ncg-admin__time {
  font-size: var(--ncg-fs-xs);
  white-space: nowrap;
}

.ncg-admin__instance-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(300px, 1fr));
  gap: var(--ncg-space-3);
  margin-top: var(--ncg-space-2);
}

.ncg-admin__instance-card {
  background: var(--ncg-panel);
  border: 1px solid var(--ncg-line);
  border-radius: var(--ncg-radius-lg);
  padding: var(--ncg-space-3);
  display: flex;
  flex-direction: column;
  gap: var(--ncg-space-1);
}

.ncg-admin__instance-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--ncg-space-2);
}

.ncg-admin__instance-env {
  font-family: var(--ncg-font-display);
  font-weight: 600;
  font-size: var(--ncg-fs-sm);
  color: var(--ncg-text-1);
}

.ncg-health {
  display: inline-flex;
  align-items: center;
  padding: 2px 10px;
  border-radius: 999px;
  font-family: var(--ncg-font-display);
  font-size: var(--ncg-fs-xs);
  font-weight: 600;
}

.ncg-health--up {
  background: var(--ncg-volt-bg);
  color: var(--ncg-volt-deep);
}

.ncg-health--degraded {
  background: var(--ncg-amber-bg);
  color: var(--ncg-amber);
}

.ncg-health--down {
  background: var(--ncg-red-bg);
  color: var(--ncg-red);
}

.ncg-admin__instance-endpoint {
  font-family: var(--ncg-font-data);
  font-size: var(--ncg-fs-sm);
  color: var(--ncg-text-1);
  word-break: break-all;
}

.ncg-admin__instance-meta {
  display: flex;
  gap: var(--ncg-space-2);
  font-size: var(--ncg-fs-xs);
  flex-wrap: wrap;
}

.ncg-admin__instance-foot {
  display: flex;
  align-items: center;
  gap: var(--ncg-space-2);
  flex-wrap: wrap;
  margin-top: 6px;
}

.ncg-admin__test-result {
  font-size: var(--ncg-fs-xs);
  font-family: var(--ncg-font-data);
}

.ncg-admin__test-result--ok {
  color: var(--ncg-volt-deep);
}

.ncg-admin__test-result--bad {
  color: var(--ncg-red);
}

.ncg-admin__masked {
  letter-spacing: 2px;
  color: var(--ncg-text-2);
}

.ncg-admin__masked-note {
  margin-left: 8px;
  font-size: var(--ncg-fs-xs);
}

.ncg-admin__full {
  width: 100%;
}

.ncg-admin__confirm-alert {
  margin-top: var(--ncg-space-2);
}
</style>
