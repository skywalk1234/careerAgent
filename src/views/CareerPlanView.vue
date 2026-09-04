<script setup lang="ts">
import { computed, nextTick, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Check, Delete, EditPen } from '@element-plus/icons-vue'
import MarkdownIt from 'markdown-it'
import DOMPurify from 'dompurify'
import { htmlToMarkdown } from '../utils/htmlToMarkdown'
import {
  deleteCareerPlan,
  getCareerPlanDetail,
  getCareerPlanList,
  updateCareerPlanContent,
  type CareerPlanDetail,
  type CareerPlanListItem,
} from '../services/careerPlan'
import { isSuccessCode } from '../services/http'

interface ApiResponse<T> {
  code: number
  msg: string
  data?: T
}

const router = useRouter()
const route = useRoute()

const markdownRenderer = new MarkdownIt({ html: false, linkify: true, breaks: true })
markdownRenderer.enable(['table', 'strikethrough'])
markdownRenderer.renderer.rules.table_open = () => '<div class="md-table-scroll"><table>'
markdownRenderer.renderer.rules.table_close = () => '</table></div>'

const initLoading = ref(false)
const detailLoading = ref(false)
const planList = ref<CareerPlanListItem[]>([])
const currentPlanId = ref<number | ''>('')
const currentPlan = ref<CareerPlanDetail | null>(null)
const editingPlan = ref(false)
const savingPlan = ref(false)
const deletingId = ref<number | ''>('')
const planEditorRef = ref<HTMLElement | null>(null)

const currentPlanHtml = computed(() => {
  const content = String(currentPlan.value?.content || '').trim()
  if (!content) return ''
  return DOMPurify.sanitize(markdownRenderer.render(content))
})

function isActivePlan(item: { status?: string } | null | undefined) {
  return String(item?.status || '') === 'active'
}

function formatDateTime(value?: string | null) {
  const text = String(value || '')
  if (!text) return ''
  return text.replace('T', ' ').slice(0, 16)
}

function pickTargetPlanId(preferred?: string | number): number {
  const preferredText = String(preferred ?? '').trim()
  if (preferredText) {
    const byPreferred = planList.value.find(item => String(item.id) === preferredText)
    if (byPreferred) return byPreferred.id
  }
  const active = planList.value.find(item => isActivePlan(item))
  if (active) return active.id
  const first = planList.value[0]
  return first ? first.id : 0
}

async function loadDetail(planId: number) {
  detailLoading.value = true
  currentPlan.value = null
  try {
    const response = await getCareerPlanDetail(planId)
    const payload = response.data as ApiResponse<CareerPlanDetail>
    if (!isSuccessCode(payload.code) || !payload.data) {
      throw new Error(payload.msg || '方案加载失败')
    }
    currentPlan.value = payload.data
  } catch (error) {
    currentPlanId.value = ''
    ElMessage.error(error instanceof Error ? error.message : '方案加载失败')
  } finally {
    detailLoading.value = false
  }
}

async function openPlan(planId: number) {
  if (!planList.value.some(item => item.id === planId)) return
  if (currentPlanId.value === planId && currentPlan.value) return
  if (editingPlan.value) {
    try {
      await ElMessageBox.confirm('当前有未保存的修改，切换后将丢弃，是否继续？', '切换方案', {
        type: 'warning',
        confirmButtonText: '继续切换',
        cancelButtonText: '留下继续编辑',
      })
    } catch {
      return
    }
    editingPlan.value = false
  }
  currentPlanId.value = planId
  await loadDetail(planId)
}

/** 进入修改模式：把渲染后的 markdown（HTML）灌进 contenteditable，用户直接改成品效果 */
function openPlanEditor() {
  if (!currentPlan.value || editingPlan.value) return
  editingPlan.value = true
  nextTick(() => {
    const el = planEditorRef.value
    if (!el) return
    el.innerHTML = DOMPurify.sanitize(markdownRenderer.render(String(currentPlan.value?.content || '')))
    el.focus()
    // 光标移到末尾，方便直接续写
    const range = document.createRange()
    range.selectNodeContents(el)
    range.collapse(false)
    const selection = window.getSelection()
    selection?.removeAllRanges()
    selection?.addRange(range)
  })
}

function cancelPlanEdit() {
  editingPlan.value = false
}

function runExecCommand(command: string, value?: string) {
  planEditorRef.value?.focus()
  document.execCommand(command, false, value || undefined)
}

/** 保存修改：编辑后的 HTML → 清洗 → 转回 markdown → PUT；正文始终以 markdown 落库 */
async function savePlanEdit() {
  const el = planEditorRef.value
  if (!el || !currentPlan.value || savingPlan.value) return
  const markdown = htmlToMarkdown(DOMPurify.sanitize(el.innerHTML)).trim()
  if (!markdown) {
    ElMessage.warning('方案内容不能为空')
    return
  }
  savingPlan.value = true
  try {
    const response = await updateCareerPlanContent(currentPlan.value.id, markdown)
    const payload = response.data as ApiResponse<CareerPlanDetail>
    if (!isSuccessCode(payload.code) || !payload.data) {
      ElMessage.error(payload.msg || '保存失败')
      return
    }
    currentPlan.value = payload.data
    editingPlan.value = false
    await refreshList() // 标题可能随正文首个标题更新，同步列表
    ElMessage.success('方案已更新')
  } catch {
    ElMessage.error('保存失败，请稍后重试')
  } finally {
    savingPlan.value = false
  }
}

/** 删除方案（物理删除）；若删的是当前 active，服务端自动把最新 archived 提升为 active */
async function handleDeletePlan(item: CareerPlanListItem) {
  if (deletingId.value) return
  try {
    await ElMessageBox.confirm(`确定删除「${item.title}」吗？删除后不可恢复。`, '删除方案', {
      type: 'warning',
      confirmButtonText: '删除',
      cancelButtonText: '取消',
    })
  } catch {
    return
  }
  deletingId.value = item.id
  try {
    const response = await deleteCareerPlan(item.id)
    const payload = response.data as ApiResponse<null>
    if (!isSuccessCode(payload.code)) {
      ElMessage.error(payload.msg || '删除失败')
      return
    }
    ElMessage.success('方案已删除')
    if (editingPlan.value) editingPlan.value = false
    await refreshList()
    // 若删的是当前打开的那份（或当前打开的那份已不在列表），回落到当前方案/最新一份
    if (currentPlanId.value === item.id || !planList.value.some(p => p.id === currentPlanId.value)) {
      currentPlan.value = null
      currentPlanId.value = ''
      const target = pickTargetPlanId()
      if (target) {
        currentPlanId.value = target
        await loadDetail(target)
      }
    }
  } catch {
    ElMessage.error('删除失败，请稍后重试')
  } finally {
    deletingId.value = ''
  }
}

async function refreshList() {
  try {
    const response = await getCareerPlanList()
    const payload = response.data as ApiResponse<{ total: number; list: CareerPlanListItem[] }>
    if (!isSuccessCode(payload.code) || !payload.data) {
      throw new Error(payload.msg || '方案列表加载失败')
    }
    planList.value = payload.data.list || []
  } catch (error) {
    planList.value = []
    ElMessage.error(error instanceof Error ? error.message : '方案列表加载失败')
  }
}

async function loadInitial() {
  initLoading.value = true
  try {
    await refreshList()
    const queryPlanId = String(route.query.planId || '').trim()
    const target = pickTargetPlanId(queryPlanId)
    if (target) {
      currentPlanId.value = target
      await loadDetail(target)
    }
  } finally {
    initLoading.value = false
  }
}

watch(
  () => route.query.planId,
  async (next) => {
    const planIdText = String(next || '').trim()
    const planId = Number(planIdText)
    if (!Number.isFinite(planId)) return
    if (!planList.value.some(item => item.id === planId)) return
    if (currentPlanId.value === planId && currentPlan.value) return
    currentPlanId.value = planId
    await loadDetail(planId)
  },
)

onMounted(loadInitial)
</script>

<template>
  <section class="space-y-4 md:space-y-6" v-loading="initLoading">
    <div class="flex flex-col gap-1 rounded-xl border border-slate-200 bg-white p-4 md:flex-row md:items-center md:justify-between md:p-5">
      <div class="space-y-1">
        <h2 class="text-xl font-semibold text-slate-900 md:text-2xl">计划与行动方案</h2>
        <p class="text-sm text-slate-500">由 AI 职业规划专家生成的分阶段行动方案；再次规划会自动归档旧版，历史版本保留可回看成长轨迹。</p>
      </div>
      <el-button plain @click="router.push('/')">去首页让 AI 规划</el-button>
    </div>

    <div class="grid grid-cols-1 gap-4 xl:grid-cols-[320px_1fr]">
      <el-card shadow="never" class="sticky top-[72px] h-fit self-start border border-slate-200 left-sticky-panel">
        <template #header>
          <div class="flex items-center justify-between">
            <span class="font-medium">方案列表</span>
            <span class="text-xs text-slate-500">共 {{ planList.length }} 份</span>
          </div>
        </template>

        <div v-if="planList.length" class="space-y-2">
          <div
            v-for="item in planList"
            :key="item.id"
            class="group flex items-center gap-1 rounded-lg border py-1 pl-1 pr-1 transition"
            :class="currentPlanId === item.id ? 'border-blue-400 bg-blue-50' : 'border-slate-200 bg-white hover:border-blue-300 hover:bg-blue-50'"
          >
            <button
              type="button"
              class="min-w-0 flex-1 rounded-md px-2 py-1 text-left"
              @click="openPlan(item.id)"
            >
              <div class="flex items-center justify-between gap-2">
                <p class="line-clamp-1 text-sm font-medium text-slate-800">{{ item.title }}</p>
                <el-tag v-if="isActivePlan(item)" size="small" type="success" effect="light">当前</el-tag>
                <el-tag v-else size="small" type="info" effect="plain">历史</el-tag>
              </div>
              <p class="mt-1 text-xs text-slate-500">{{ formatDateTime(item.createdAt) }} 生成</p>
            </button>
            <el-button
              class="shrink-0"
              size="small"
              text
              type="danger"
              :icon="Delete"
              :loading="deletingId === item.id"
              aria-label="删除该方案"
              @click.stop="handleDeletePlan(item)"
            />
          </div>
        </div>

        <div v-else class="rounded-lg border border-dashed border-slate-200 p-4 text-center text-sm text-slate-500">
          暂无方案。到首页与 AI 职业规划助手对话，即可生成行动方案。
        </div>
      </el-card>

      <div class="min-w-0 space-y-4">
        <el-card v-if="!planList.length" shadow="never" class="border border-dashed border-slate-300">
          <el-empty description="还没有行动方案，先让 AI 职业规划专家为你规划吧">
            <el-button type="primary" @click="router.push('/')">去首页找 AI 规划助手</el-button>
          </el-empty>
        </el-card>

        <el-card v-else-if="!currentPlan" shadow="never" class="border border-dashed border-slate-300">
          <div class="py-12 text-center text-sm text-slate-500">
            <span v-if="detailLoading">正在加载方案正文…</span>
            <span v-else>请从左侧选择一份方案查看全文。</span>
          </div>
        </el-card>

        <el-card v-else shadow="never" class="border border-slate-200" :class="{ 'plan-editing-card': editingPlan }">
          <template #header>
            <div class="space-y-2">
              <div class="flex items-center justify-between gap-3">
                <div class="flex min-w-0 items-center gap-2">
                  <el-tag v-if="isActivePlan(currentPlan)" size="small" type="success" effect="light">当前方案</el-tag>
                  <el-tag v-else size="small" type="info" effect="plain">历史版本</el-tag>
                  <h3 class="min-w-0 truncate text-base font-semibold text-slate-900">{{ currentPlan.title }}</h3>
                </div>
                <span class="shrink-0 text-xs text-slate-500">{{ formatDateTime(currentPlan.createdAt) }} 生成</span>
              </div>

              <div class="flex items-center justify-end gap-2 border-t border-slate-100 pt-2">
                <template v-if="!editingPlan">
                  <el-button size="small" type="primary" plain :icon="EditPen" @click="openPlanEditor">修改</el-button>
                  <el-button
                    size="small"
                    type="danger"
                    plain
                    :icon="Delete"
                    :loading="deletingId === currentPlan.id"
                    @click="handleDeletePlan(currentPlan)"
                  >
                    删除
                  </el-button>
                </template>
                <template v-else>
                  <span class="mr-auto text-xs text-slate-400">修改模式</span>
                  <el-button size="small" @click="cancelPlanEdit">取消</el-button>
                  <el-button size="small" type="primary" :icon="Check" :loading="savingPlan" @click="savePlanEdit">保存并更新</el-button>
                </template>
              </div>
            </div>
          </template>

          <div v-if="editingPlan">
            <div class="mb-2 flex flex-wrap items-center gap-1 rounded-lg border border-slate-200 bg-slate-50 p-1 select-none">
              <button type="button" class="plan-tool-btn font-semibold" @mousedown.prevent="runExecCommand('bold')">加粗</button>
              <button type="button" class="plan-tool-btn italic" @mousedown.prevent="runExecCommand('italic')">斜体</button>
              <button type="button" class="plan-tool-btn" @mousedown.prevent="runExecCommand('strikeThrough')">删除线</button>
              <span class="mx-1 h-4 w-px bg-slate-200"></span>
              <button type="button" class="plan-tool-btn" @mousedown.prevent="runExecCommand('formatBlock', 'h2')">标题2</button>
              <button type="button" class="plan-tool-btn" @mousedown.prevent="runExecCommand('formatBlock', 'h3')">标题3</button>
              <button type="button" class="plan-tool-btn" @mousedown.prevent="runExecCommand('formatBlock', 'p')">正文</button>
              <span class="mx-1 h-4 w-px bg-slate-200"></span>
              <button type="button" class="plan-tool-btn" @mousedown.prevent="runExecCommand('insertUnorderedList')">无序列表</button>
              <button type="button" class="plan-tool-btn" @mousedown.prevent="runExecCommand('insertOrderedList')">有序列表</button>
              <button type="button" class="plan-tool-btn" @mousedown.prevent="runExecCommand('formatBlock', 'blockquote')">引用</button>
              <span class="mx-1 h-4 w-px bg-slate-200"></span>
              <button type="button" class="plan-tool-btn" @mousedown.prevent="runExecCommand('undo')">撤销</button>
              <button type="button" class="plan-tool-btn" @mousedown.prevent="runExecCommand('redo')">重做</button>
            </div>

            <div
              ref="planEditorRef"
              class="plan-editor markdown-body"
              contenteditable="true"
              spellcheck="false"
            ></div>
            <p class="mt-2 text-xs text-slate-400">直接在渲染效果上修改；保存后自动转回 Markdown 排版，请尽量保留标题层级与列表结构。</p>
          </div>

          <div v-else class="plan-markdown markdown-body" v-loading="detailLoading" v-html="currentPlanHtml"></div>
        </el-card>
      </div>
    </div>

    <el-backtop :right="20" :bottom="28" :visibility-height="420" />
  </section>
</template>

<style scoped>
.left-sticky-panel {
  max-height: calc(100vh - 96px);
  overflow-y: auto;
  scrollbar-width: none;
  -ms-overflow-style: none;
}

.left-sticky-panel::-webkit-scrollbar {
  display: none;
}

.plan-markdown {
  min-height: 120px;
}

.plan-tool-btn {
  border-radius: 6px;
  padding: 2px 8px;
  font-size: 12px;
  line-height: 1.6;
  color: #334155;
  background: transparent;
  transition: background-color 120ms ease;
}

.plan-tool-btn:hover {
  background-color: #e2e8f0;
}

.plan-tool-btn:active {
  background-color: #cbd5e1;
}

.plan-editing-card {
  border-color: #bfdbfe;
}

.plan-editor {
  min-height: 320px;
  padding: 8px 12px;
  border: 1px dashed #93c5fd;
  border-radius: 10px;
  background: #fff;
  outline: none;
  transition: border-color 160ms ease, box-shadow 160ms ease;
}

.plan-editor:focus {
  border-color: #3b82f6;
  border-style: solid;
  box-shadow: 0 0 0 3px rgba(59, 130, 246, 0.12);
}

/* 编辑态段落给一点呼吸感（预览态 .markdown-body 的 p 是 margin:0，这里覆盖）
   注意必须写在 .markdown-body :deep(p) 规则之后才能覆盖到 */
.plan-editor :deep(p) {
  margin: 0.25em 0;
}

.markdown-body {
  line-height: 1.7;
  color: #1f2937;
  word-break: break-word;
  font-size: 14px;
}

.markdown-body :deep(p) {
  margin: 0;
}

.markdown-body :deep(h1),
.markdown-body :deep(h2),
.markdown-body :deep(h3),
.markdown-body :deep(h4) {
  margin: 0;
  font-weight: 700;
  color: #0f172a;
  line-height: 1.35;
}

.markdown-body :deep(h1) {
  font-size: 24px;
}

.markdown-body :deep(h2) {
  font-size: 19px;
  margin-top: 20px;
  padding-bottom: 8px;
  border-bottom: 1px solid #eef2f7;
}

.markdown-body :deep(h3) {
  font-size: 16px;
  margin-top: 16px;
}

.markdown-body :deep(h4) {
  font-size: 15px;
  margin-top: 14px;
}

.markdown-body :deep(h1 + *),
.markdown-body :deep(h3 + *),
.markdown-body :deep(h4 + *) {
  margin-top: 8px;
}

.markdown-body :deep(h2 + *) {
  margin-top: 8px;
}

.markdown-body :deep(p + p) {
  margin-top: 8px;
}

.markdown-body :deep(code) {
  padding: 1px 6px;
  border-radius: 6px;
  background: #f1f5f9;
  color: #0f172a;
  font-size: 12px;
}

.markdown-body :deep(pre) {
  margin-top: 8px;
  padding: 10px;
  border-radius: 10px;
  background: #f8fafc;
  border: 1px solid #e2e8f0;
  overflow-x: auto;
}

.markdown-body :deep(pre code) {
  background: transparent;
  padding: 0;
}

.markdown-body :deep(ul),
.markdown-body :deep(ol) {
  margin: 8px 0 0;
  padding-left: 20px;
}

.markdown-body :deep(ul) {
  list-style: disc;
}

.markdown-body :deep(ol) {
  list-style: decimal;
}

.markdown-body :deep(li) {
  margin: 3px 0;
}

.markdown-body :deep(.md-table-scroll) {
  margin-top: 8px;
  overflow-x: auto;
  overflow-y: hidden;
  max-width: 100%;
  -webkit-overflow-scrolling: touch;
}

.markdown-body :deep(table) {
  width: max-content;
  min-width: 100%;
  border-collapse: collapse;
  margin-top: 0;
  border: 1px solid #dbe5f0;
  border-radius: 8px;
  overflow: hidden;
  font-size: 13px;
}

.markdown-body :deep(th),
.markdown-body :deep(td) {
  border: 1px solid #dbe5f0;
  padding: 6px 8px;
  text-align: left;
  vertical-align: top;
}

.markdown-body :deep(th) {
  background: #f8fafc;
  color: #0f172a;
  font-weight: 600;
}

.markdown-body :deep(blockquote) {
  margin: 8px 0 0;
  padding: 8px 10px;
  border-left: 3px solid #93c5fd;
  background: #f8fbff;
  color: #334155;
}

.markdown-body :deep(a) {
  color: #2563eb;
  text-decoration: underline;
}

@media (max-width: 768px) {
  .markdown-body :deep(h1) {
    font-size: 20px;
  }

  .markdown-body :deep(h2) {
    font-size: 17px;
  }

  .markdown-body :deep(h3) {
    font-size: 15px;
  }

  .markdown-body :deep(h4) {
    font-size: 14px;
  }

  .markdown-body :deep(pre) {
    padding: 8px;
  }

  .markdown-body :deep(ul),
  .markdown-body :deep(ol) {
    padding-left: 18px;
  }

  .markdown-body :deep(th),
  .markdown-body :deep(td) {
    font-size: 12px;
    padding: 5px 6px;
    white-space: nowrap;
  }
}
</style>
