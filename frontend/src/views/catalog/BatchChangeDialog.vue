<script setup lang="ts">
/**
 * BatchChangeDialog —— 多选批量发起变更（§3.5.3）
 * 每个 DataId 生成一个 ChangeRequest 子草稿；同 DataId 多 key 合并一子项；
 * 逐项填值（留空=不变），未填阻断；敏感项必须重新输入新值不回显旧明文。
 * 错误：CR-409-001 顶部提示整批拒绝、BAT-413-001 弹窗内联、CR-400-001 内联。
 */
import { computed, reactive, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import 'element-plus/es/components/message/style/css'
import 'element-plus/es/components/alert/style/css'
import 'element-plus/es/components/dialog/style/css'
import 'element-plus/es/components/input/style/css'
import 'element-plus/es/components/button/style/css'
import 'element-plus/es/components/tag/style/css'
import 'element-plus/es/components/empty/style/css'
import { batchApi, catalogApi } from '@/api'
import type { ApiError } from '@/api/http'
import type { ConfigContent } from '@/api/types'
import { reasonInvalid, REASON_MIN, type UiError, toUiError } from '@/stores/changes'
import { formatLabel, isFormatKnown } from '@/stores/catalog'

const props = withDefaults(defineProps<{ visible: boolean; mappingIds: string[] }>(), {
  mappingIds: () => [],
})
const emit = defineEmits<{ 'update:visible': [v: boolean]; done: [] }>()

const router = useRouter()

const BATCH_MAX = 50

const loading = ref(false)
const contents = ref<Record<string, ConfigContent>>({})
/** mappingId → { itemKey: newValue } */
const values = reactive<Record<string, Record<string, string>>>({})
const title = ref('')
const reason = ref('')
const submitting = ref(false)
const batchError = ref<UiError | null>(null)
const blocked409 = ref<{ changeNo?: string } | null>(null)

const entries = computed(() =>
  props.mappingIds.map((id) => ({ id, content: contents.value[id] })).filter((e) => e.content),
)

async function loadContents() {
  loading.value = true
  batchError.value = null
  blocked409.value = null
  const map: Record<string, ConfigContent> = {}
  await Promise.all(
    props.mappingIds.map(async (id) => {
      try {
        map[id] = await catalogApi.getConfig(id)
      } catch (e) {
        // 单个失败不阻断整批展示
        const err = e as ApiError
        map[id] = {
          mappingId: id,
          dataId: id,
          format: 'TEXT',
          md5: '',
          collectedAt: '',
          items: [],
          parseError: undefined,
        }
        void err
      }
    }),
  )
  contents.value = map
  for (const id of props.mappingIds) values[id] = {}
  loading.value = false
}

watch(
  () => props.visible,
  async (v) => {
    if (!v) return
    title.value = ''
    reason.value = ''
    await loadContents()
  },
)

const changedCount = (mappingId: string) =>
  Object.values(values[mappingId] ?? {}).filter((v) => v.trim() !== '').length

const totalChanged = computed(() =>
  props.mappingIds.reduce((sum, id) => sum + changedCount(id), 0),
)

const reasonError = computed(() => (reason.value ? reasonInvalid(reason.value) : null))
const overLimit = computed(() => props.mappingIds.length > BATCH_MAX)

async function submit() {
  batchError.value = null
  if (overLimit.value) {
    batchError.value = {
      code: 'BAT-413-001',
      message: `批量数量超过上限（${BATCH_MAX}）：当前选中 ${props.mappingIds.length} 个 DataId，请分批提交`,
    }
    return
  }
  if (!title.value.trim()) {
    batchError.value = { message: '请填写变更标题' }
    return
  }
  const reasonErr = reasonInvalid(reason.value)
  if (reasonErr) {
    batchError.value = { code: 'CR-400-001', message: reasonErr }
    return
  }
  if (totalChanged.value === 0) {
    batchError.value = { code: 'CR-400-001', message: '未填任何新值：请至少为一个配置项填写新值' }
    return
  }
  submitting.value = true
  try {
    const items = props.mappingIds
      .filter((id) => changedCount(id) > 0)
      .map((id) => ({
        mappingId: id,
        items: Object.entries(values[id] ?? {})
          .filter(([, v]) => v.trim() !== '')
          .map(([key, value]) => ({ key, value })),
      }))
    const res = await batchApi.createBatch({
      title: title.value.trim(),
      reason: reason.value.trim(),
      items,
    })
    ElMessage.success(`批量变更已创建：${res.batchId}（${res.childCount} 个子草稿）`)
    emit('done')
    close()
    router.push({ name: 'batches' })
  } catch (e) {
    const err = e as ApiError
    if (err?.code === 'CR-409-001') {
      blocked409.value = { changeNo: (err.details as { changeNo?: string } | undefined)?.changeNo }
    }
    batchError.value = toUiError(e)
  } finally {
    submitting.value = false
  }
}

function close() {
  emit('update:visible', false)
}
</script>

<template>
  <el-dialog
    :model-value="visible"
    title="批量发起变更"
    width="min(920px, 96vw)"
    top="5vh"
    destroy-on-close
    @update:model-value="close"
  >
    <div class="ncg-batch">
      <el-alert type="info" :closable="false" title="一次批量 = 每个 DataId 一个子草稿">
        <template #default>
          同一 DataId 的多个 key 合并进一个子项；逐项填写新值（留空 = 不变）；敏感项必须重新输入新值，不回显旧明文。
        </template>
      </el-alert>

      <el-alert
        v-if="blocked409"
        type="warning"
        :closable="false"
        title="所选配置已有进行中变更单（CR-409-001）"
        :description="`变更单 ${blocked409.changeNo ?? ''} 正在流转，整批已拒绝；请处理该单或调整选择后重试。`"
      />

      <el-alert
        v-if="overLimit"
        type="warning"
        :closable="false"
        :title="`批量数量超过上限（${BATCH_MAX}）`"
        :description="`当前选中 ${props.mappingIds.length} 个 DataId，请分批提交（BAT-413-001）。`"
      />

      <template v-if="loading">
        <div class="ncg-muted">正在载入各 DataId 当前值…</div>
      </template>
      <el-empty v-else-if="!entries.length" description="没有可编辑的配置项（所选 DataId 可能为未知格式或空文件）">
        <p class="ncg-batch__empty-next">下一步：取消本对话框，改选已纳管（.properties/.yml/.yaml/.json）的配置文件再试。</p>
      </el-empty>

      <div v-else class="ncg-batch__list">
        <div v-for="e in entries" :key="e.id" class="ncg-batch__card">
          <div class="ncg-batch__card-head">
            <code class="ncg-mono">{{ e.content!.dataId }}</code>
            <el-tag size="small" effect="plain">{{ formatLabel(e.content!.format) }}</el-tag>
            <el-tag v-if="!isFormatKnown(e.content!.format)" size="small" type="warning" effect="plain">
              未知格式只读（CR-415-001），不会产生子项
            </el-tag>
            <span class="ncg-muted">已填 {{ changedCount(e.id) }} 项</span>
          </div>
          <div v-if="e.content!.items?.length" class="ncg-batch__rows">
            <div v-for="item in e.content!.items" :key="item.key" class="ncg-batch__row">
              <code class="ncg-mono ncg-batch__key">{{ item.key }}</code>
              <span class="ncg-mono ncg-batch__current ncg-truncate">
                {{ item.sensitive ? '●●●●●●●●' : item.value }}
              </span>
              <el-input
                v-model="values[e.id][item.key]"
                size="small"
                clearable
                :placeholder="item.sensitive ? '输入新值（当前脱敏）' : '输入新值（留空不变）'"
              />
            </div>
          </div>
          <div v-else class="ncg-muted ncg-batch__text-note">
            {{ e.content!.content ? 'TEXT/整文件内容：批量按项不适用，请单击「发起变更」整文件替换' : '该文件没有可解析的配置项' }}
          </div>
        </div>
      </div>

      <el-form label-position="top">
        <el-form-item label="变更标题" required>
          <el-input v-model="title" maxlength="100" placeholder="一句话说明本批变更" />
        </el-form-item>
        <el-form-item label="变更原因" required :error="reasonError ?? ''">
          <el-input
            v-model="reason"
            type="textarea"
            :rows="3"
            maxlength="500"
            placeholder="不少于 10 个字；将写入审计记录"
          />
          <div class="ncg-muted ncg-batch__reason-count">{{ reason.trim().length }} / {{ REASON_MIN }} 字起</div>
        </el-form-item>
      </el-form>

      <el-alert
        v-if="batchError"
        type="error"
        :closable="false"
        :title="batchError.message ?? '提交失败'"
        :description="batchError.code ? `错误码 ${batchError.code}${batchError.traceId ? ` · TraceId ${batchError.traceId}` : ''}` : ''"
      />
    </div>

    <template #footer>
      <el-button @click="close">取消</el-button>
      <el-button type="primary" :loading="submitting" :disabled="overLimit" @click="submit">
        创建批量草稿（{{ totalChanged }} 项）
      </el-button>
    </template>
  </el-dialog>
</template>

<style scoped>
.ncg-batch {
  display: flex;
  flex-direction: column;
  gap: var(--ncg-space-2);
}
.ncg-batch__list {
  display: flex;
  flex-direction: column;
  gap: var(--ncg-space-2);
  max-height: 46vh;
  overflow: auto;
}
.ncg-batch__card {
  border: 1px solid var(--ncg-line);
  border-radius: var(--ncg-radius-md);
  padding: var(--ncg-space-2);
}
.ncg-batch__card-head {
  display: flex;
  gap: 8px;
  align-items: center;
  flex-wrap: wrap;
  margin-bottom: 8px;
  font-size: var(--ncg-fs-xs);
}
.ncg-batch__rows {
  display: flex;
  flex-direction: column;
  gap: 6px;
}
.ncg-batch__row {
  display: grid;
  grid-template-columns: minmax(120px, 1fr) minmax(100px, 1fr) minmax(160px, 1.2fr);
  gap: 8px;
  align-items: center;
  font-size: var(--ncg-fs-xs);
}
.ncg-batch__key {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.ncg-batch__current {
  color: var(--ncg-text-2);
}
.ncg-batch__text-note {
  font-size: var(--ncg-fs-xs);
}
.ncg-batch__reason-count {
  font-size: var(--ncg-fs-xs);
  margin-top: 4px;
}
.ncg-batch__empty-next {
  margin: var(--ncg-space-1) auto 0;
  max-width: var(--ncg-measure);
  font-size: var(--ncg-fs-sm);
  color: var(--ncg-text-3);
}
</style>
