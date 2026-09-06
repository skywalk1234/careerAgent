<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { ChatLineRound, Document, Plus, Promotion, Refresh, Timer } from '@element-plus/icons-vue'
import { renderMarkdown } from '../utils/markdown'
import { getToken } from '../utils/auth'
import { isSuccessCode } from '../services/http'
import { getStudentProfileList, type ResumeListItem } from '../services/studentProfile'
import { getFavoriteJobs, type FavoriteJobsResult } from '../services/jobGraph'
import {
  createInterviewSession,
  getInterviewSessionList,
  getInterviewSessionMessages,
  sendInterviewMessage,
  type InterviewSessionItem,
  type InterviewType,
} from '../services/mockInterview'

interface ApiResponse<T> {
  code: number
  msg: string
  data?: T
}

/** 会话/开场消息之外的流式内容（含渲染态），等价 interview_messages 一条记录 */
interface ChatMsg {
  localId: string
  role: 'user' | 'assistant'
  content: string
  renderState: 'sent' | 'streaming' | 'failed'
}

/* ---------- 面试类型展示配置（对齐 INTERVIEW_TYPES：technical / behavior / mixed） ---------- */

interface TypeMeta {
  type: InterviewType
  label: string
  desc: string
  chipClass: string
  dotClass: string
}

const TYPE_META: TypeMeta[] = [
  {
    type: 'technical',
    label: '技术面试',
    desc: '围绕简历技能栈与目标岗位，深挖技术细节与项目难点',
    chipClass: 'border-sky-200 bg-sky-50 text-sky-700',
    dotClass: 'bg-sky-500',
  },
  {
    type: 'behavior',
    label: '行为面试',
    desc: '通过项目与校园经历，考察沟通协作、抗压与自驱力',
    chipClass: 'border-violet-200 bg-violet-50 text-violet-700',
    dotClass: 'bg-violet-500',
  },
  {
    type: 'mixed',
    label: '综合面试',
    desc: '技术 + 行为混合提问，最接近真实校招面试节奏',
    chipClass: 'border-teal-200 bg-teal-50 text-teal-700',
    dotClass: 'bg-teal-500',
  },
]

const DEFAULT_TYPE: InterviewType = 'mixed'

function getTypeMeta(type: string): TypeMeta {
  const meta = TYPE_META.find(item => item.type === type)
  return (
    meta ?? {
      type: DEFAULT_TYPE,
      label: type || '综合面试',
      desc: '',
      chipClass: 'border-slate-200 bg-slate-50 text-slate-600',
      dotClass: 'bg-slate-400',
    }
  )
}

function friendlyToolLabel(toolName: string) {
  switch (toolName) {
    case 'get_student_profile':
      return '正在调取你的简历资料'
    case 'query_job_detail':
      return '正在查询目标岗位详情'
    case 'submit_mock_interview_report':
      return '正在生成面试总结报告'
    default:
      return `正在执行 ${toolName}`
  }
}

/* ---------- 基础状态 ---------- */

const router = useRouter()
const route = useRoute()

const AI_API_BASE = import.meta.env.VITE_AI_API_BASE_URL || 'http://127.0.0.1:8086'
const MAX_ROUNDS = 8 // 对齐后端 interview_max_questions，仅作文案提示

const sessions = ref<InterviewSessionItem[]>([])
const activeSessionId = ref('')
const messages = ref<ChatMsg[]>([])
const transcriptLoading = ref(false)
const streaming = ref(false)
const liveStepText = ref('')
const activeStreamRef = ref<EventSource | null>(null)
const inputText = ref('')

let localSeq = 0
function nextLocalId() {
  localSeq += 1
  return `local_${Date.now()}_${localSeq}`
}

const activeSession = computed(() => {
  if (!activeSessionId.value) return null
  return sessions.value.find(item => item.sessionId === activeSessionId.value) ?? null
})

const activeEnded = computed(() => activeSession.value?.status === 'ended')
const activeReportId = computed(() => activeSession.value?.reportId ?? null)
const answeredCount = computed(() => messages.value.filter(m => m.role === 'assistant' && m.content.trim()).length)

function formatDateTime(value?: string | null, withTime = true) {
  const text = String(value || '')
  if (!text) return ''
  return withTime ? text.replace('T', ' ').slice(0, 16) : text.replace('T', ' ').slice(0, 10)
}

function pickErrorMsg(error: unknown, fallback: string) {
  const err = error as { response?: { data?: { detail?: string; msg?: string } }; message?: string }
  const detail = err?.response?.data?.detail
  if (typeof detail === 'string' && detail) return detail
  const msg = err?.response?.data?.msg
  if (typeof msg === 'string' && msg) return msg
  return err?.message || fallback
}

/* ---------- 会话列表 / 切换 ---------- */

async function refreshSessions() {
  try {
    const response = await getInterviewSessionList()
    const payload = response.data as ApiResponse<{ total: number; list: InterviewSessionItem[] }>
    if (!isSuccessCode(payload.code) || !payload.data) {
      throw new Error(payload.msg || '面试场次加载失败')
    }
    sessions.value = payload.data.list || []
  } catch (error) {
    ElMessage.error(pickErrorMsg(error, '面试场次加载失败'))
  }
}

async function loadMessages(sessionId: string) {
  transcriptLoading.value = true
  messages.value = []
  try {
    const response = await getInterviewSessionMessages(sessionId)
    const payload = response.data as ApiResponse<{ sessionId: string; total: number; list: Array<{ messageId: string; role: string; content: string; status: string }> }>
    if (!isSuccessCode(payload.code) || !payload.data) {
      throw new Error(payload.msg || '聊天记录加载失败')
    }
    messages.value = (payload.data.list || []).map(item => ({
      localId: item.messageId,
      role: item.role === 'user' ? 'user' : 'assistant',
      content: item.content || '',
      renderState: 'sent',
    }))
    await scrollToBottom()
  } catch (error) {
    ElMessage.error(pickErrorMsg(error, '聊天记录加载失败'))
  } finally {
    transcriptLoading.value = false
  }
}

async function selectSession(sessionId: string) {
  closeActiveStream()
  streaming.value = false
  liveStepText.value = ''
  if (activeSessionId.value === sessionId && messages.value.length) return
  activeSessionId.value = sessionId
  await loadMessages(sessionId)
}

/** 新会话开场前先探测：本场是否已有开场白（防止页面刷新后重复开场触发后端 400） */
function hasTranscript() {
  return messages.value.some(m => m.role === 'assistant' && m.content.trim())
}

/* ---------- SSE 接收（事件协议对齐 chat.py：start/trace/tool_call/tool_result/delta/done/error） ---------- */

function closeActiveStream() {
  const source = activeStreamRef.value
  if (source) {
    activeStreamRef.value = null
    source.close()
  }
}

function parseSsePayload(raw: string) {
  try {
    return JSON.parse(raw) as Record<string, unknown>
  } catch {
    return null
  }
}

/** 打开一条 SSE 流并持续渲染到 target 气泡；返回后 events 期间 target.renderState 保持 streaming */
function openSse(streamUrl: string, target: ChatMsg) {
  closeActiveStream()
  streaming.value = true
  liveStepText.value = '正在连接面试官…'
  target.renderState = 'streaming'
  target.content = ''

  const connector = streamUrl.includes('?') ? '&' : '?'
  const source = new EventSource(`${AI_API_BASE}${streamUrl}${connector}token=${encodeURIComponent(getToken() || '')}`)
  activeStreamRef.value = source
  let streamFinished = false

  const safeClose = () => {
    streamFinished = true
    closeActiveStream()
    streaming.value = false
  }

  source.addEventListener('trace', (event) => {
    if (streamFinished || target.renderState !== 'streaming') return
    const payload = parseSsePayload((event as MessageEvent).data)
    const trace = payload?.trace as
      | { status?: string; steps?: Array<{ type?: string; status?: string; toolName?: string }> }
      | undefined
    const steps = Array.isArray(trace?.steps) ? trace.steps : []
    const step = steps[steps.length - 1]
    if (step?.type === 'tool' && String(step.status) === 'processing' && step.toolName) {
      liveStepText.value = `${friendlyToolLabel(step.toolName)}…`
    } else if (step?.type === 'thought' && String(step.status) === 'processing') {
      liveStepText.value = 'AI 面试官正在思考…'
    } else if (step?.type === 'tool' && String(step.status) === 'succeeded') {
      // 工具执行完毕、模型即将输出正文：把状态条切回通用文案
      liveStepText.value = '面试官正在组织语言…'
    }
  })

  source.addEventListener('tool_call', (event) => {
    if (streamFinished || target.renderState !== 'streaming') return
    const payload = parseSsePayload((event as MessageEvent).data)
    const toolName = String(payload?.tool || '')
    if (toolName) liveStepText.value = `${friendlyToolLabel(toolName)}…`
  })

  source.addEventListener('tool_result', (event) => {
    if (streamFinished || target.renderState !== 'streaming') return
    const payload = parseSsePayload((event as MessageEvent).data)
    if (String(payload?.tool || '') === 'submit_mock_interview_report') {
      liveStepText.value = '总结报告已生成，正在收尾…'
    } else {
      liveStepText.value = '面试官已掌握所需信息，正在组织提问…'
    }
  })

  source.addEventListener('delta', (event) => {
    if (streamFinished || target.renderState !== 'streaming') return
    const payload = parseSsePayload((event as MessageEvent).data)
    const content = String(payload?.content ?? payload?.delta ?? '')
    if (content) {
      target.content = content
      scrollToBottom()
    }
  })

  source.addEventListener('done', async (event) => {
    if (streamFinished) return
    const payload = parseSsePayload((event as MessageEvent).data)
    const message = payload?.message as { content?: string; status?: string } | undefined
    const finalContent = String(message?.content ?? '') || target.content
    target.content = finalContent || '（面试官未输出内容）'
    target.renderState = 'sent'
    safeClose()
    liveStepText.value = ''
    await scrollToBottom()

    await refreshSessions()
    if (activeSession.value?.status === 'ended' && activeSession.value?.reportId) {
      ElMessage.success('本场面试已结束，总结报告已生成')
      await scrollToBottom()
    }
  })

  source.addEventListener('error', () => {
    if (streamFinished) return
    if (source.readyState === EventSource.CONNECTING) return
    if (activeStreamRef.value !== source) return
    target.content = '连接中断，请点击右上角刷新重试。'
    target.renderState = 'failed'
    safeClose()
    liveStepText.value = ''
  })

  source.onopen = () => {
    if (source.readyState === EventSource.OPEN && !liveStepText.value) {
      liveStepText.value = '正在组织开场…'
    }
  }
}

/* ---------- 开场 / 提问 / 收尾 ---------- */

/** 开场白 SSE（GET start：面试官拉取材料 → 开场白 + 第 1 问） */
function startOpeningStream() {
  if (!activeSession.value || streaming.value || hasTranscript()) return
  const placeholder: ChatMsg = { localId: nextLocalId(), role: 'assistant', content: '', renderState: 'streaming' }
  messages.value = [placeholder]
  liveStepText.value = '正在组织开场…'
  openSse(`/users/me/interview/sessions/${encodeURIComponent(activeSessionId.value)}/start`, placeholder)
  scrollToBottom()
}

/** 发送一条用户消息（回答/表达结束均可走同一链路） */
async function sendAnswer(rawContent?: string) {
  const content = String(rawContent ?? inputText.value).trim()
  if (!content || !activeSession.value || streaming.value || activeEnded.value) return
  if (transcriptLoading.value) return

  const session = activeSession.value
  // 先置流式锁再发请求，避免 POST 返回前用户连按 Enter 造成重复发送
  streaming.value = true
  liveStepText.value = '正在提交你的回答…'
  inputText.value = ''
  const userMsg: ChatMsg = { localId: nextLocalId(), role: 'user', content, renderState: 'sent' }
  const assistantMsg: ChatMsg = { localId: nextLocalId(), role: 'assistant', content: '', renderState: 'streaming' }
  messages.value.push(userMsg)
  messages.value.push(assistantMsg)
  await scrollToBottom()

  try {
    const response = await sendInterviewMessage(session.sessionId, content)
    const payload = response.data as ApiResponse<{ sessionId: string; stream: { protocol: string; url: string } }>
    if (!isSuccessCode(payload.code) || !payload.data) {
      throw new Error(payload.msg || '发送失败')
    }
    openSse(payload.data.stream.url, assistantMsg)
  } catch (error) {
    const index = messages.value.indexOf(assistantMsg)
    if (index >= 0) messages.value.splice(index, 1)
    const userIndex = messages.value.indexOf(userMsg)
    if (userIndex >= 0) messages.value.splice(userIndex, 1)
    inputText.value = content
    streaming.value = false
    liveStepText.value = ''
    ElMessage.error(pickErrorMsg(error, '发送失败，请稍后重试'))
  }
}

/** 提前结束：把「结束面试」作为一条消息发给面试官，由其触发报告生成 */
async function handleFinishInterview() {
  if (!activeSession.value || streaming.value || activeEnded.value) return
  if (answeredCount.value < 1) {
    ElMessage.info('面试官还没有提问，先回答第一题吧')
    return
  }
  try {
    await ElMessageBox.confirm('将结束本场面试，AI 面试官会立即生成面试总结报告，确定结束吗？', '结束面试', {
      type: 'warning',
      confirmButtonText: '结束并生成报告',
      cancelButtonText: '再想想',
    })
  } catch {
    return
  }
  await sendAnswer('结束面试')
}

function handleSendClick() {
  if (streaming.value) {
    ElMessage.info('面试官正在回复，请稍候')
    return
  }
  sendAnswer()
}

function handleRefreshCurrent() {
  if (!activeSessionId.value) return
  closeActiveStream()
  streaming.value = false
  liveStepText.value = ''
  loadMessages(activeSessionId.value)
  refreshSessions()
}

function handleGoReports(reportId?: number | null) {
  const id = reportId ?? activeReportId.value
  if (id) {
    router.push({ path: '/interview/reports', query: { reportId: String(id) } })
  } else {
    router.push('/interview/reports')
  }
}

function onInputKeydown(event: KeyboardEvent) {
  if (event.key === 'Enter' && !event.shiftKey && !event.isComposing) {
    event.preventDefault()
    handleSendClick()
  }
}

/* ---------- 开场配置弹窗 ---------- */

const showCreateDialog = ref(false)
const createLoading = ref(false)
const resumeOptions = ref<ResumeListItem[]>([])
const resumesLoading = ref(false)
const draftType = ref<InterviewType>(DEFAULT_TYPE)
const draftProfileId = ref('')
const draftJobId = ref('')
const favoriteJobs = ref<FavoriteJobsResult['list']>([])
const favoriteJobsLoading = ref(false)
const selectedFavorite = computed(() => favoriteJobs.value.find(job => String(job.jobId) === draftJobId.value))

function resumeOptionTitle(item: ResumeListItem): string {
  const content = String(item.content ?? '')
  const line = content.split('\n').map(l => l.trim()).find(l => l.length > 0) ?? ''
  const title = line.replace(/^[#>*_\-\s]+/, '').trim()
  const text = title || line || '未命名简历'
  return text.length > 16 ? `${text.slice(0, 16)}…` : text
}

/** 收藏岗位的薪资展示：薪资面议 → “面议”，否则用归一化字符串；异常/缺失返回空串 */
function favoriteSalaryText(job: { salaryNormalized?: string; salaryNegotiable?: boolean }) {
  if (job.salaryNegotiable) return '面议'
  const normalized = String(job.salaryNormalized ?? '').trim()
  return normalized && !/unknown/i.test(normalized) ? normalized : ''
}

async function loadResumeOptions() {
  resumesLoading.value = true
  try {
    const response = await getStudentProfileList()
    const result = response.data as ApiResponse<ResumeListItem[]>
    if (!isSuccessCode(Number(result.code))) return
    const payload = ((result as unknown as { payload?: ResumeListItem[] }).payload ?? result.data) as ResumeListItem[]
    resumeOptions.value = Array.isArray(payload) ? payload.filter(item => item.profileId) : []
  } catch {
    resumeOptions.value = []
  } finally {
    resumesLoading.value = false
  }
}

async function loadFavoriteJobs() {
  favoriteJobsLoading.value = true
  try {
    const response = await getFavoriteJobs()
    const result = response.data as ApiResponse<FavoriteJobsResult>
    if (!isSuccessCode(Number(result.code))) return
    const payload = ((result as unknown as { payload?: FavoriteJobsResult }).payload ?? result.data) as FavoriteJobsResult
    favoriteJobs.value = payload?.list && Array.isArray(payload.list) ? payload.list : []
  } catch {
    favoriteJobs.value = []
  } finally {
    favoriteJobsLoading.value = false
  }
}

function openCreateDialog() {
  const queryType = String(route.query.type || '').toLowerCase()
  draftType.value = TYPE_META.some(item => item.type === queryType) ? (queryType as InterviewType) : DEFAULT_TYPE
  const queryProfileId = String(route.query.profileId || '').trim()
  draftProfileId.value = resumeOptions.value.some(item => String(item.profileId) === queryProfileId) ? queryProfileId : ''
  draftJobId.value = String(route.query.jobId || '').trim()
  showCreateDialog.value = true
  loadFavoriteJobs()
}

async function submitCreateSession() {
  if (!draftProfileId.value) {
    ElMessage.warning('请先选择一份简历作为面试提问依据')
    return
  }
  if (createLoading.value) return
  createLoading.value = true
  try {
    const response = await createInterviewSession({
      type: draftType.value,
      profileId: draftProfileId.value,
      jobId: draftJobId.value.trim() || undefined,
    })
    const payload = response.data as ApiResponse<{ sessionId: string; type: string; status: string; createdAt: string }>
    if (!isSuccessCode(payload.code) || !payload.data) {
      throw new Error(payload.msg || '创建失败')
    }
    const created = payload.data
    showCreateDialog.value = false
    // 本地先占位，避免多一次列表请求；开场结束后 refreshSessions 校准真实字段
    sessions.value = [
      {
        sessionId: created.sessionId,
        type: created.type,
        status: created.status,
        profileId: draftProfileId.value,
        jobId: draftJobId.value.trim() || null,
        createdAt: created.createdAt,
        reportId: null,
      },
      ...sessions.value,
    ]
    activeSessionId.value = created.sessionId
    messages.value = []
    startOpeningStream()
  } catch (error) {
    ElMessage.error(pickErrorMsg(error, '创建面试失败'))
  } finally {
    createLoading.value = false
  }
}

/* ---------- 初始化 ---------- */

async function initialize() {
  await refreshSessions()

  const querySessionId = String(route.query.sessionId || '').trim()
  const target = querySessionId
    ? sessions.value.find(item => item.sessionId === querySessionId)
    : sessions.value.find(item => item.status === 'active') ?? sessions.value[0] ?? null

  if (target) {
    activeSessionId.value = target.sessionId
    await loadMessages(target.sessionId)
    // 若本场开场流因刷新中断（无开场白），页面会显示「开始面试」按钮，由用户手动重开
  } else {
    openCreateDialog()
  }
}

function scrollToBottom() {
  nextTick(() => {
    const el = chatScrollRef.value
    if (el) el.scrollTop = el.scrollHeight
  })
}

const chatScrollRef = ref<HTMLElement | null>(null)

onMounted(async () => {
  await loadResumeOptions()
  await initialize()
})

onBeforeUnmount(() => {
  closeActiveStream()
})
</script>

<template>
  <section class="space-y-4 md:space-y-6">
    <div class="flex flex-col gap-1 rounded-xl border border-slate-200 bg-white p-4 md:flex-row md:items-center md:justify-between md:p-5">
      <div class="space-y-1">
        <h2 class="text-xl font-semibold text-slate-900 md:text-2xl">模拟面试专家</h2>
        <p class="text-sm text-slate-500">AI 面试官会依据你的简历与目标岗位连续提问约 8 题，结束后自动生成面试总结报告；回答中可以直接说“结束面试”提前收尾。</p>
      </div>
      <div class="flex items-center gap-2">
        <el-button plain :icon="Document" @click="handleGoReports()">面试记录</el-button>
        <el-button type="primary" :icon="Plus" @click="openCreateDialog">新开一场面试</el-button>
      </div>
    </div>

    <div class="grid grid-cols-1 gap-4 xl:grid-cols-[320px_1fr]">
      <!-- 左栏：场次列表 -->
      <el-card shadow="never" class="sticky top-[72px] h-fit self-start border border-slate-200 left-sticky-panel">
        <template #header>
          <div class="flex items-center justify-between">
            <span class="font-medium">我的面试</span>
            <el-button size="small" text circle :icon="Refresh" aria-label="刷新场次" @click="refreshSessions" />
          </div>
        </template>

        <div v-if="sessions.length" class="space-y-2">
          <div
            v-for="item in sessions"
            :key="item.sessionId"
            class="group flex cursor-pointer items-center gap-1 rounded-lg border py-2 pl-3 pr-2 transition"
            :class="activeSessionId === item.sessionId ? 'border-blue-400 bg-blue-50' : 'border-slate-200 bg-white hover:border-blue-300 hover:bg-blue-50'"
            @click="selectSession(item.sessionId)"
          >
            <div class="min-w-0 flex-1">
              <div class="flex items-center justify-between gap-2">
                <p class="flex min-w-0 items-center gap-1.5 text-sm font-medium text-slate-800">
                  <span class="h-1.5 w-1.5 shrink-0 rounded-full" :class="getTypeMeta(item.type).dotClass"></span>
                  <span class="truncate">{{ getTypeMeta(item.type).label }}</span>
                </p>
                <span v-if="item.status === 'active'" class="flex shrink-0 items-center gap-1 text-xs text-emerald-600">
                  <i class="pulse-dot inline-block h-1.5 w-1.5 rounded-full bg-emerald-500"></i>进行中
                </span>
                <span v-else class="shrink-0 text-xs text-slate-400">已结束</span>
              </div>
              <p class="mt-0.5 truncate text-xs text-slate-500">
                <template v-if="item.jobTitle">{{ formatDateTime(item.createdAt, false) }} · {{ item.jobTitle }}</template>
                <template v-else>{{ formatDateTime(item.createdAt, false) }}</template>
              </p>
            </div>
            <el-tag v-if="item.reportId" size="small" type="success" effect="light" class="shrink-0">有报告</el-tag>
          </div>
        </div>

        <div v-else class="rounded-lg border border-dashed border-slate-200 p-4 text-center text-sm text-slate-500">
          还没有面试场次。
        </div>

        <el-button class="mt-3 w-full" type="primary" plain :icon="Plus" @click="openCreateDialog">开始新面试</el-button>
      </el-card>

      <!-- 右栏：对话区 -->
      <div class="mock-chat-panel flex min-w-0 flex-col overflow-hidden rounded-xl border border-slate-200 bg-white">
        <!-- 空状态 -->
        <div v-if="!activeSession" class="flex flex-col items-center justify-center px-6 py-16 text-center">
          <div class="flex h-16 w-16 items-center justify-center rounded-2xl bg-gradient-to-br from-blue-500 to-indigo-500 text-white shadow-md shadow-blue-200">
            <el-icon :size="28"><ChatLineRound /></el-icon>
          </div>
          <h3 class="mt-4 text-lg font-semibold text-slate-900">和 AI 面试官来一场实战演练</h3>
          <p class="mt-1 max-w-md text-sm text-slate-500">
            选择一份简历（可附带目标岗位），面试官将按校招真实节奏连续提问，结束后生成逐题点评的总结报告。
          </p>
          <el-button class="mt-5" type="primary" :icon="Plus" @click="openCreateDialog">开始新面试</el-button>
        </div>

        <template v-else>
          <!-- 会话头 -->
          <div class="flex flex-wrap items-center gap-x-3 gap-y-2 border-b border-slate-100 bg-slate-50/70 px-4 py-3">
            <div class="flex h-9 w-9 items-center justify-center rounded-full bg-gradient-to-br from-blue-500 to-indigo-500 text-white shadow-sm">
              <el-icon :size="17"><ChatLineRound /></el-icon>
            </div>
            <div class="min-w-0 flex-1">
              <div class="flex items-center gap-2">
                <span class="text-sm font-semibold text-slate-800">AI 面试官</span>
                <span class="rounded border px-1.5 py-px text-xs" :class="getTypeMeta(activeSession.type).chipClass">
                  {{ getTypeMeta(activeSession.type).label }}
                </span>
                <span v-if="activeEnded" class="text-xs text-slate-400">本场已结束</span>
              </div>
              <p class="mt-0.5 truncate text-xs text-slate-500">
                <template v-if="activeSession.companyName || activeSession.jobTitle">
                  {{ activeSession.companyName }} · {{ activeSession.jobTitle }}
                </template>
                <template v-else-if="activeSession.jobId">已关联岗位，面试官正在围绕 JD 提问</template>
                <template v-else>未关联岗位，面试官将围绕简历通用提问</template>
              </p>
            </div>
            <div class="flex shrink-0 items-center gap-2">
              <el-button size="small" text circle :icon="Refresh" aria-label="刷新本场" @click="handleRefreshCurrent" />
              <el-button v-if="activeReportId" size="small" type="primary" plain :icon="Document" @click="handleGoReports()">
                查看总结报告
              </el-button>
              <el-button
                v-else-if="!activeEnded"
                size="small"
                type="danger"
                plain
                :disabled="streaming || answeredCount < 1"
                @click="handleFinishInterview"
              >
                结束面试
              </el-button>
            </div>
          </div>

          <!-- 流式状态条 -->
          <div
            v-if="streaming || activeEnded"
            class="flex items-center gap-2 border-b border-slate-100 bg-blue-50/60 px-4 py-1.5 text-xs text-blue-600"
          >
            <template v-if="streaming">
              <i class="pulse-dot inline-block h-1.5 w-1.5 rounded-full bg-blue-500"></i>
              <span>{{ liveStepText || 'AI 面试官正在回复…' }}</span>
            </template>
            <template v-else>
              <el-icon :size="13"><Timer /></el-icon>
              <span>本场面试已结束，报告已保存到「面试记录」，可以随时回看。</span>
            </template>
          </div>

          <!-- 消息区 -->
          <div
            ref="chatScrollRef"
            v-loading="transcriptLoading"
            class="chat-scroll flex-1 space-y-3 overflow-y-auto bg-slate-50/60 px-4 py-4 md:px-6"
          >
            <!-- 开场引导（无消息时） -->
            <div v-if="!hasTranscript()" class="flex justify-center py-6">
              <el-button type="primary" plain :loading="streaming" :disabled="streaming" @click="startOpeningStream">
                {{ streaming ? '面试官正在开场…' : '开始面试（开场白 + 第 1 问）' }}
              </el-button>
            </div>

            <div v-for="msg in messages" :key="msg.localId" class="flex gap-2.5" :class="msg.role === 'user' ? 'justify-end' : 'justify-start'">
              <div v-if="msg.role === 'assistant'" class="mt-1 flex h-7 w-7 shrink-0 items-center justify-center rounded-full bg-gradient-to-br from-blue-500 to-indigo-500 text-white">
                <el-icon :size="14"><ChatLineRound /></el-icon>
              </div>
              <div class="min-w-0 max-w-[85%] md:max-w-[78%]">
                <div
                  v-if="msg.role === 'assistant'"
                  class="rounded-2xl rounded-tl-sm border border-slate-200 bg-white px-4 py-2.5 shadow-sm chat-bubble-in"
                >
                  <!-- 等待首字：三点跳动 -->
                  <span v-if="msg.renderState === 'streaming' && !msg.content.trim()" class="msg-dots" aria-label="面试官正在输入">
                    <i></i><i></i><i></i>
                  </span>
                  <div v-else-if="msg.content.trim()" class="chat-md markdown-body" v-html="renderMarkdown(msg.content)"></div>
                  <span v-if="msg.renderState === 'streaming' && msg.content.trim()" class="stream-caret"></span>
                  <div v-if="msg.renderState === 'failed'" class="mt-1 flex items-center gap-2 text-xs text-red-500">
                    <span>回复中断</span>
                    <el-button size="small" text type="primary" @click="handleRefreshCurrent">刷新本场</el-button>
                  </div>
                </div>
                <div
                  v-else
                  class="chat-md whitespace-pre-wrap break-words rounded-2xl rounded-tr-sm bg-blue-600 px-4 py-2.5 text-sm text-white shadow-sm"
                >
                  {{ msg.content }}
                </div>
              </div>
            </div>

            <div v-if="transcriptLoading" class="flex justify-center py-2 text-xs text-slate-400">加载聊天记录…</div>
            <div v-else-if="!messages.length && !streaming" class="py-2 text-center text-xs text-slate-400">
              本场面试尚未开始，点击上方「开始面试」按钮，面试官确认材料后就会开场提问。
            </div>
          </div>

          <!-- 输入区 -->
          <div class="border-t border-slate-100 bg-white px-4 py-3">
            <template v-if="activeEnded">
              <div class="flex items-center justify-between rounded-lg bg-slate-50 px-3 py-2">
                <span class="text-xs text-slate-500">本场面试已结束并生成报告，无法继续提问。</span>
                <el-button size="small" type="primary" plain :icon="Document" @click="handleGoReports()">查看报告</el-button>
              </div>
            </template>
            <template v-else>
              <div class="flex items-end gap-2">
                <textarea
                  v-model="inputText"
                  class="max-h-32 min-h-[44px] flex-1 resize-none rounded-lg border border-slate-200 bg-slate-50 px-3 py-2.5 text-sm text-slate-800 outline-none transition placeholder:text-slate-400 focus:border-blue-400 focus:bg-white focus:ring-4 focus:ring-blue-100 disabled:cursor-not-allowed disabled:opacity-60"
                  :disabled="streaming || !activeSession || transcriptLoading || !hasTranscript()"
                  :placeholder="streaming ? '面试官正在回复，请稍候…' : !hasTranscript() ? '面试开场后才能发送回答' : answeredCount >= MAX_ROUNDS ? '已达提问上限，回复后将生成总结报告' : '输入你的回答，Enter 发送（Shift + Enter 换行）'"
                  rows="1"
                  @keydown="onInputKeydown"
                ></textarea>
                <el-button
                  type="primary"
                  circle
                  class="!h-11 !w-11 shrink-0"
                  :icon="Promotion"
                  :disabled="streaming || !inputText.trim() || transcriptLoading || activeEnded || !hasTranscript()"
                  aria-label="发送"
                  @click="handleSendClick"
                />
              </div>
              <div class="mt-1.5 flex items-center justify-between px-1 text-xs text-slate-400">
                <span>AI 面试官的回答仅用于练习，请勿当作真实录用依据</span>
                <span v-if="!streaming">
                  已提问 {{ answeredCount }} / 约 {{ MAX_ROUNDS }} 题<span v-if="answeredCount"> · 想说“结束面试”随时可以</span>
                </span>
              </div>
            </template>
          </div>
        </template>
      </div>
    </div>

    <!-- 新面试配置弹窗 -->
    <el-dialog v-model="showCreateDialog" title="开始一场模拟面试" width="560px" :close-on-click-modal="false" align-center>
      <div class="space-y-5">
        <div>
          <p class="mb-2 text-sm font-medium text-slate-700">面试类型</p>
          <div class="grid grid-cols-1 gap-2 sm:grid-cols-3">
            <button
              v-for="meta in TYPE_META"
              :key="meta.type"
              type="button"
              class="type-card rounded-xl border p-3 text-left transition"
              :class="draftType === meta.type ? 'type-card-active' : 'border-slate-200 bg-white hover:border-blue-300'"
              @click="draftType = meta.type"
            >
              <span class="flex items-center gap-1.5 text-sm font-medium text-slate-800">
                <i class="type-radio" :class="draftType === meta.type ? 'type-radio-on' : ''"></i>
                {{ meta.label }}
              </span>
              <span class="mt-1 block text-xs leading-relaxed text-slate-500">{{ meta.desc }}</span>
            </button>
          </div>
        </div>

        <div>
          <p class="mb-2 text-sm font-medium text-slate-700">
            选择简历 <span class="text-red-500">*</span>
            <span class="ml-1 font-normal text-slate-400">面试官将依据这份简历提问</span>
          </p>
          <el-select
            v-model="draftProfileId"
            class="w-full"
            filterable
            :loading="resumesLoading"
            placeholder="从你的简历中选择一份"
            no-data-text="暂未找到简历"
          >
            <el-option v-for="item in resumeOptions" :key="item.profileId" :label="resumeOptionTitle(item)" :value="item.profileId">
              <div class="flex items-center justify-between gap-3">
                <span class="truncate text-sm text-slate-700">{{ resumeOptionTitle(item) }}</span>
                <span v-if="item.updatedAt" class="shrink-0 text-xs text-slate-400">{{ formatDateTime(item.updatedAt, false) }}</span>
              </div>
            </el-option>
          </el-select>
          <div v-if="!resumesLoading && !resumeOptions.length" class="mt-1.5 text-xs text-slate-400">
            还没有可用简历，请先到
            <button type="button" class="text-blue-600 hover:underline" @click="router.push('/student')">能力评估</button>
            页上传/创建简历，再来模拟面试。
          </div>
        </div>

        <div>
          <p class="mb-2 text-sm font-medium text-slate-700">
            目标岗位 <span class="ml-1 font-normal text-slate-400">可选 —— 从下方收藏岗位中一键选择，也可手动填写</span>
          </p>
          <el-input v-model="draftJobId" placeholder="选择收藏岗位后会自动填入岗位 ID，也可直接粘贴" clearable>
            <template #prepend>岗位 ID</template>
          </el-input>

          <div class="mt-2.5">
            <div class="mb-1.5 flex items-center justify-between">
              <span class="text-xs font-medium text-slate-500">我的收藏岗位</span>
              <el-button text size="small" :icon="Refresh" :loading="favoriteJobsLoading" @click="loadFavoriteJobs">刷新</el-button>
            </div>

            <div class="fav-job-list">
              <div
                v-if="favoriteJobsLoading"
                class="rounded-lg border border-dashed border-slate-200 px-3 py-3 text-center text-xs text-slate-400"
              >
                正在加载收藏岗位…
              </div>
              <template v-else-if="favoriteJobs.length">
                <button
                  v-for="job in favoriteJobs"
                  :key="job.jobId"
                  type="button"
                  class="fav-job-item"
                  :class="selectedFavorite?.jobId === job.jobId ? 'fav-job-item-on' : ''"
                  @click="draftJobId = job.jobId"
                >
                  <span class="min-w-0 flex-1 text-left">
                    <span class="block truncate text-sm font-medium text-slate-800">{{ job.jobName }}</span>
                    <span class="mt-0.5 block truncate text-xs text-slate-500">
                      {{ job.companyName || '公司未知' }}<template v-if="job.city"> · {{ job.city }}</template
                      ><template v-if="favoriteSalaryText(job)"> · {{ favoriteSalaryText(job) }}</template>
                    </span>
                  </span>
                  <span v-if="selectedFavorite?.jobId === job.jobId" class="shrink-0 text-xs font-medium text-blue-600">已选 ✓</span>
                </button>
              </template>

              <div v-else class="rounded-lg border border-dashed border-slate-200 px-3 py-4 text-center text-xs text-slate-400">
                还没有收藏岗位。到
                <button type="button" class="text-blue-600 hover:underline" @click="router.push('/jobs')">岗位探索</button>
                页收藏心仪岗位后，可在这里一键带入岗位 ID。
              </div>
            </div>
          </div>

          <p class="mt-2 text-xs text-slate-400">留空也可以：面试官会围绕简历做通用提问；面试中报出“jobId:xxx”即可随时补充岗位。</p>
        </div>
      </div>
      <template #footer>
        <el-button @click="showCreateDialog = false">取消</el-button>
        <el-button type="primary" :loading="createLoading" :disabled="resumesLoading" @click="submitCreateSession">
          创建并开始面试
        </el-button>
      </template>
    </el-dialog>

    <el-backtop :right="20" :bottom="28" :visibility-height="420" />
  </section>
</template>

<style scoped>
/* 左栏场次列表：吸顶 + 隐藏滚动条（与 CareerPlanView 一致） */
.left-sticky-panel {
  max-height: calc(100vh - 96px);
  overflow-y: auto;
  scrollbar-width: none;
  -ms-overflow-style: none;
}

.left-sticky-panel::-webkit-scrollbar {
  display: none;
}

/* 对话区整体：桌面端给一个观感适中的高度，内部消息区滚动 */
.mock-chat-panel {
  height: min(72vh, 760px);
  min-height: 520px;
}

.chat-scroll {
  scrollbar-width: thin;
  scrollbar-color: #cbd5e1 transparent;
}

/* 进行中 / 流式状态 呼吸点 */
.pulse-dot {
  animation: pulse-dot 1.4s ease-in-out infinite;
}

@keyframes pulse-dot {
  0%,
  100% {
    opacity: 1;
  }
  50% {
    opacity: 0.25;
  }
}

/* 流式气泡首字前的三点跳动 */
.msg-dots i {
  display: inline-block;
  width: 6px;
  height: 6px;
  margin-right: 4px;
  border-radius: 9999px;
  background: #93c5fd;
  animation: dot-bounce 1.2s ease-in-out infinite;
}

.msg-dots i:nth-child(2) {
  animation-delay: 0.15s;
}

.msg-dots i:nth-child(3) {
  animation-delay: 0.3s;
}

@keyframes dot-bounce {
  0%,
  60%,
  100% {
    transform: translateY(0);
    opacity: 0.45;
  }
  30% {
    transform: translateY(-3px);
    opacity: 1;
  }
}

/* 流式输出末尾光标 */
.stream-caret {
  display: inline-block;
  width: 2px;
  height: 1.05em;
  margin-left: 2px;
  vertical-align: -0.15em;
  background: #3b82f6;
  animation: caret-blink 0.9s steps(2, start) infinite;
}

@keyframes caret-blink {
  to {
    visibility: hidden;
  }
}

/* 弹窗：面试类型卡片选择 */
.type-card {
  cursor: pointer;
}

.type-card-active {
  border-color: #93c5fd;
  background: #eff6ff;
  box-shadow: 0 0 0 3px rgba(59, 130, 246, 0.1);
}

.type-radio {
  position: relative;
  display: inline-block;
  width: 14px;
  height: 14px;
  border: 2px solid #cbd5e1;
  border-radius: 9999px;
  transition: border-color 150ms ease;
}

.type-radio-on {
  border-color: #2563eb;
}

.type-radio-on::after {
  content: '';
  position: absolute;
  inset: 2px;
  border-radius: 9999px;
  background: #2563eb;
}

/* 面试官气泡轻微浮起 */
.chat-bubble-in {
  transition: box-shadow 150ms ease;
}

/* 弹窗：收藏岗位选择列表 */
.fav-job-list {
  display: flex;
  flex-direction: column;
  gap: 6px;
  max-height: 216px;
  min-height: 44px;
  overflow-y: auto;
  scrollbar-width: thin;
  scrollbar-color: #cbd5e1 transparent;
}

.fav-job-item {
  display: flex;
  align-items: center;
  gap: 8px;
  width: 100%;
  border: 1px solid #e2e8f0;
  border-radius: 8px;
  background: #fff;
  padding: 7px 10px;
  cursor: pointer;
  transition:
    border-color 150ms ease,
    background-color 150ms ease,
    box-shadow 150ms ease;
}

.fav-job-item:hover {
  border-color: #93c5fd;
  background: #eff6ff;
}

.fav-job-item-on {
  border-color: #93c5fd;
  background: #eff6ff;
  box-shadow: 0 0 0 2px rgba(59, 130, 246, 0.08);
}
</style>
