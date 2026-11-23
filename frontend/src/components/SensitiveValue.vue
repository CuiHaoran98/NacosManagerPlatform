<script setup lang="ts">
/**
 * SensitiveValue —— 敏感值脱敏展示（详细设计 §3.5.4 / rule.md 敏感值红线）
 * 默认脱敏；查看需填写原因（≥10 字）→ POST /config/view → 30 秒后自动再脱敏
 * 明文不落盘、不进 URL
 */
import { computed, onBeforeUnmount, ref } from 'vue'
import { ElMessage } from 'element-plus'
import 'element-plus/es/components/message/style/css'
import 'element-plus/es/components/dialog/style/css'
import 'element-plus/es/components/input/style/css'
import 'element-plus/es/components/button/style/css'
import { viewSensitive } from '@/api/modules/catalog'
import type { ApiError } from '@/api/http'

const props = defineProps<{
  mappingId: string
  itemKey: string
  /** 拿到明文后的回调（如写入编辑器草稿） */
  autoMaskSeconds?: number
}>()

const MASK = '●●●●●●●●'
const REVEAL_DURATION = 30

const revealed = ref(false)
const plaintext = ref('')
const dialogVisible = ref(false)
const reason = ref('')
const loading = ref(false)
const countdown = ref(0)
let timer: number | undefined

const reasonValid = computed(() => reason.value.trim().length >= 10)

function openDialog() {
  if (revealed.value) {
    reMask()
    return
  }
  reason.value = ''
  dialogVisible.value = true
}

async function confirmReveal() {
  if (!reasonValid.value) return
  loading.value = true
  try {
    const res = await viewSensitive(props.mappingId, {
      itemKey: props.itemKey,
      reason: reason.value.trim(),
    })
    plaintext.value = res.value
    revealed.value = true
    dialogVisible.value = false
    startCountdown()
    ElMessage.success(`已显示明文，${REVEAL_DURATION} 秒后自动脱敏`)
  } catch (e) {
    const err = e as ApiError
    // 保留用户输入，仅提示错误
    ElMessage.error(err?.message ?? '查看失败，请检查原因填写与权限')
  } finally {
    loading.value = false
  }
}

function startCountdown() {
  stopTimer()
  countdown.value = props.autoMaskSeconds ?? REVEAL_DURATION
  timer = window.setInterval(() => {
    countdown.value -= 1
    if (countdown.value <= 0) reMask()
  }, 1000)
}

function stopTimer() {
  if (timer !== undefined) {
    window.clearInterval(timer)
    timer = undefined
  }
}

function reMask() {
  stopTimer()
  revealed.value = false
  plaintext.value = ''
}

onBeforeUnmount(stopTimer)
</script>

<template>
  <span class="ncg-sensitive">
    <code class="ncg-data ncg-sensitive__value">{{ revealed ? plaintext : MASK }}</code>
    <el-button size="small" text type="primary" :title="revealed ? '重新脱敏' : '查看明文'" @click="openDialog">
      {{ revealed ? `脱敏 (${countdown}s)` : '查看' }}
    </el-button>

    <el-dialog v-model="dialogVisible" title="查看敏感值" width="480px" append-to-body>
      <p class="ncg-sensitive__hint">
        查看行为将记入审计（操作者、键名、原因、时间）。请输入查看原因（至少 10 个字）：
      </p>
      <el-input
        v-model="reason"
        type="textarea"
        :rows="3"
        maxlength="200"
        show-word-limit
        placeholder="例如：排查 prod 下单链路 DB_PASSWORD 连接失败"
      />
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" :disabled="!reasonValid" :loading="loading" @click="confirmReveal">
          确认查看
        </el-button>
      </template>
    </el-dialog>
  </span>
</template>

<style scoped>
.ncg-sensitive {
  display: inline-flex;
  align-items: center;
  gap: 4px;
}
.ncg-sensitive__value {
  font-size: var(--ncg-fs-sm);
  color: var(--ncg-text-2);
}
.ncg-sensitive__hint {
  color: var(--ncg-text-2);
  font-size: var(--ncg-fs-sm);
  margin-bottom: var(--ncg-space-1);
}
</style>
