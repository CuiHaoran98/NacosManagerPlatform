<script setup lang="ts">
/**
 * 登录页 —— 入口守卫失败后来到这里；POST /auth/login
 * 成功后跳 redirect 或 /workbench；错误就地提示（401 不触发全局跳转）
 */
import { reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import 'element-plus/es/components/form/style/css'
import 'element-plus/es/components/input/style/css'
import 'element-plus/es/components/button/style/css'
import 'element-plus/es/components/alert/style/css'
import { useAuthStore } from '@/stores/auth'
import type { ApiError } from '@/api/http'

const route = useRoute()
const router = useRouter()
const auth = useAuthStore()

const form = reactive({ username: '', password: '' })
const loading = ref(false)
const errorMsg = ref('')
const errorCode = ref('')
const errorTraceId = ref('')

async function handleLogin() {
  if (!form.username || !form.password) {
    errorMsg.value = '请输入用户名与密码'
    errorCode.value = ''
    errorTraceId.value = ''
    return
  }
  loading.value = true
  errorMsg.value = ''
  errorCode.value = ''
  errorTraceId.value = ''
  try {
    await auth.login({ username: form.username, password: form.password })
    const redirect = (route.query.redirect as string) || '/workbench'
    router.push(redirect)
  } catch (e) {
    const err = e as ApiError
    errorMsg.value = err?.message ?? '登录失败，请稍后重试'
    errorCode.value = err?.code ?? ''
    errorTraceId.value = err?.traceId ?? ''
  } finally {
    loading.value = false
  }
}
</script>

<template>
  <div class="ncg-login">
    <div class="ncg-login__panel">
      <div class="ncg-login__brand">
        <span class="ncg-login__logo" aria-hidden="true">◆</span>
        <h1 class="ncg-login__title">NCG 配置治理平台</h1>
        <p class="ncg-login__sub">统一配置变更 · 审批 · 发布 · 审计</p>
      </div>

      <el-form label-position="top" @submit.prevent="handleLogin">
        <el-form-item label="用户名">
          <el-input v-model="form.username" placeholder="用户名" autocomplete="username" @keyup.enter="handleLogin" />
        </el-form-item>
        <el-form-item label="密码">
          <el-input
            v-model="form.password"
            type="password"
            placeholder="密码"
            show-password
            autocomplete="current-password"
            @keyup.enter="handleLogin"
          />
        </el-form-item>

        <el-alert v-if="errorMsg" :title="errorMsg" type="error" :closable="false" show-icon class="ncg-login__err">
          <template v-if="errorCode || errorTraceId" #default>
            <span class="ncg-login__err-meta ncg-mono">
              {{ errorCode ? `错误码 ${errorCode}` : '' }}{{ errorTraceId ? ` · TraceId ${errorTraceId}` : '' }}
            </span>
          </template>
        </el-alert>

        <el-button type="primary" native-type="submit" :loading="loading" class="ncg-login__submit">
          登录
        </el-button>
      </el-form>
    </div>
  </div>
</template>

<style scoped>
.ncg-login {
  min-height: 100vh;
  display: flex;
  align-items: center;
  justify-content: center;
  background: var(--ncg-ink);
  padding: var(--ncg-space-2);
}
.ncg-login__panel {
  width: 400px;
  padding: var(--ncg-space-4);
  background: var(--ncg-panel);
  border: 1px solid var(--ncg-line);
  border-radius: var(--ncg-radius-lg);
}
.ncg-login__brand {
  text-align: center;
  margin-bottom: var(--ncg-space-3);
}
.ncg-login__logo {
  color: var(--ncg-volt);
  font-size: var(--ncg-fs-xl);
}
.ncg-login__title {
  font-family: var(--ncg-font-display);
  font-size: var(--ncg-fs-lg);
  font-weight: 600;
  margin-top: var(--ncg-space-1);
}
.ncg-login__sub {
  color: var(--ncg-text-3);
  font-size: var(--ncg-fs-sm);
  margin-top: 4px;
}
.ncg-login__err {
  margin-bottom: var(--ncg-space-2);
}
.ncg-login__err-meta {
  font-size: var(--ncg-fs-xs);
  opacity: 0.85;
}
.ncg-login__submit {
  width: 100%;
}
</style>
