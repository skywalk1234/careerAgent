<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Calendar, ChatDotRound, ChatLineRound, Close, Refresh, Promotion, ArrowRight, CircleCheckFilled, WarningFilled, CircleCloseFilled, Loading, Operation, Plus, Document, Star } from '@element-plus/icons-vue'
import MarkdownIt from 'markdown-it'
import DOMPurify from 'dompurify'
import { isSuccessCode } from '../services/http'
import {
  controlHomeAgentTask,
  createHomeAgentTask,
  createHomeSession,
  createHomeSessionMessage,
  getHomeAgentRuntimeOverview,
  getHomeAgentTaskArtifacts,
  getHomeOverview,
  getHomeSessionMessages,
  listHomeSessions,
  regenerateHomeSessionMessage,
  submitHomeMessageApproval,
  type HomeAgentRuntimeOverviewResult,
  type HomeAgentRuntimePreset,
  type HomeAgentRuntimeTaskControlAction,
  type HomeAgentRuntimeTaskSummary,
  type HomeMessage,
  type HomeAgentTrace,
  type HomeSession,
  type HomePageContext,
} from '../services/home'
import { getStudentProfileList, type ResumeListItem } from '../services/studentProfile'
import { polishCareerReport } from '../services/careerReport'
import { getCareerPlanList, type CareerPlanListItem } from '../services/careerPlan'
import { getInterviewReportList, type InterviewReportListItem } from '../services/mockInterview'
import { getMatchRecommendations, refineMatchRecommendations, type MatchRecommendationsResult } from '../services/matchAnalysis'
import { getFavoriteJobs, type FavoriteJobsResult } from '../services/jobGraph'
import { getToken } from '../utils/auth'
import {
  GLOBAL_ASSISTANT_OPEN_EVENT,
  HOME_ASSISTANT_SESSION_REFRESH_EVENT,
  TASK_ORCHESTRATOR_STREAM_EVENT,
  emitJobRecommendationApply,
  emitMatchRecommendationRefresh,
  openTaskOrchestrator,
  stashPendingJobRecommendationApply,
  type GlobalAssistantContextPayload,
  type HomeAssistantSessionRefreshPayload,
  type TaskOrchestratorStreamPayload,
} from '../utils/globalAssistant'
import { getTaskTranscriptMessages, mergeTaskTranscriptMessages } from '../utils/taskSession'
import AssistantAbilityRadarCard from './AssistantAbilityRadarCard.vue'
import AssistantJobGapCard from './AssistantJobGapCard.vue'
import aiAvatarImage from '../assets/aiAvatar.png'
import thoughtIcon from '../assets/aiIcon/thought.svg'
import toolIcon from '../assets/aiIcon/tool.svg'
import taskIcon from '../assets/aiIcon/task.svg'
import planIcon from '../assets/aiIcon/plan.svg'

interface ApiResponse<T> {
  code: number
  msg: string
  data?: T
}

interface HomeMessageApprovalState {
  approvalId: string
  title: string
  summary: string
  status: 'pending' | 'approved' | 'rejected'
  action?: {
    type: string
    route: string
    reportId?: string
    scope?: Record<string, unknown> | null
    keyword?: string
    autoSelect?: boolean
    recommendedJobId?: string
  }
}

const router = useRouter()
const route = useRoute()
const markdownRenderer = new MarkdownIt({ html: false, linkify: true, breaks: true })
markdownRenderer.enable(['table', 'strikethrough'])
markdownRenderer.renderer.rules.table_open = () => '<div class="md-table-scroll"><table>'
markdownRenderer.renderer.rules.table_close = () => '</table></div>'

const visible = ref(false)
const loading = ref(false)
const sending = ref(false)
const regeneratingMessageId = ref('')
const activeSessionId = ref('')
const sessions = ref<HomeSession[]>([])
const messages = ref<HomeMessage[]>([])
const draft = ref('')
const editingUserMessageId = ref('')
const editingUserMessageDraft = ref('')
const quickPrompts = ref<string[]>([])
const eventSourceRef = ref<EventSource | null>(null)
const chatListRef = ref<HTMLDivElement>()
const composerInputRef = ref<HTMLTextAreaElement>()
const resumeOptions = ref<ResumeListItem[]>([])
const resumePickerVisible = ref(false)
let resumePickerHideTimer: ReturnType<typeof setTimeout> | null = null
const favoriteJobOptions = ref<FavoriteJobsResult['list']>([])
const favoriteJobPickerVisible = ref(false)
const favoriteJobLoading = ref(false)
let favoriteJobPickerHideTimer: ReturnType<typeof setTimeout> | null = null
const shouldStickToBottom = ref(true)

const planOptions = ref<CareerPlanListItem[]>([])
const planPickerVisible = ref(false)
let planPickerHideTimer: ReturnType<typeof setTimeout> | null = null

const reportOptions = ref<InterviewReportListItem[]>([])
const reportPickerVisible = ref(false)
let reportPickerHideTimer: ReturnType<typeof setTimeout> | null = null
const panelRef = ref<HTMLDivElement>()

const panelX = ref(Math.max(16, window.innerWidth - 500))
const panelY = ref(Math.max(56, window.innerHeight * 0.18))
const panelWidth = ref(500)
const panelHeight = ref(700)
const dragging = ref(false)

const traceExpandedByMessageId = ref<Record<string, boolean>>({})
const pendingApprovalsByMessageId = ref<Record<string, HomeMessageApprovalState>>({})
const approvalSubmittingByMessageId = ref<Record<string, boolean>>({})
const approvalAutoExecutedIds = new Set<string>()
const incomingContext = ref<GlobalAssistantContextPayload | null>(null)
const pendingInitialMessage = ref('')
const agentRuntimeOverview = ref<HomeAgentRuntimeOverviewResult | null>(null)
const agentRuntimeLoading = ref(false)
const launchingAgentPresetId = ref('')
const controllingAgentTaskId = ref('')
const agentGoalDraft = ref('')
const selectedAgentPresetId = ref('')
const injectedTaskArtifactIds = new Set<string>()
let agentRuntimePollTimer: ReturnType<typeof window.setInterval> | null = null
let activeResizeCleanup: (() => void) | null = null

const agentRuntimePresets = computed(() => agentRuntimeOverview.value?.presets || [])
const agentRuntimeTasks = computed(() => agentRuntimeOverview.value?.latestTasks || [])
const latestAssistantMessageId = computed(() => {
  for (let index = messages.value.length - 1; index >= 0; index -= 1) {
    if (messages.value[index]?.role === 'assistant') {
      return String(messages.value[index].messageId || '')
    }
  }
  return ''
})
const showAssistantIntroduction = computed(() => !sending.value && !messages.value.some(item => item.role === 'user'))
const selectedAgentPreset = computed(() => {
  const presetId = String(selectedAgentPresetId.value || '').trim()
  if (presetId) {
    const target = agentRuntimePresets.value.find(item => item.presetId === presetId)
    if (target) return target
  }
  return agentRuntimePresets.value[0] || null
})

const panelStyle = computed(() => ({
  left: `${panelX.value}px`,
  top: `${panelY.value}px`,
  width: `${panelWidth.value}px`,
  height: `${panelHeight.value}px`,
}))

function isAgentRuntimeTaskActive(status: string) {
  const value = String(status || '')
  return value === 'queued' || value === 'in_progress' || value === 'paused'
}

function hasAgentRuntimeActiveTask() {
  return agentRuntimeTasks.value.some(task => isAgentRuntimeTaskActive(task.status))
}

function stopAgentRuntimePolling() {
  if (agentRuntimePollTimer !== null) {
    window.clearInterval(agentRuntimePollTimer)
    agentRuntimePollTimer = null
  }
}

function ensureAgentRuntimePolling() {
  if (agentRuntimePollTimer !== null) return
  agentRuntimePollTimer = window.setInterval(() => {
    refreshAgentRuntimeOverview({ silent: true }).catch(() => {})
  }, 1400)
}

function resolveAgentRuntimeTaskStatusLabel(status: string) {
  const value = String(status || '')
  if (value === 'completed') return '已完成'
  if (value === 'in_progress') return '执行中'
  if (value === 'queued') return '排队中'
  if (value === 'paused') return '已暂停'
  if (value === 'rolled_back') return '已回滚'
  if (value === 'cancelled') return '已取消'
  if (value === 'failed') return '失败'
  return '待处理'
}

function resolveAgentRuntimeTaskStatusClass(status: string) {
  const value = String(status || '')
  if (value === 'completed') return 'assistant-agent-status-done'
  if (value === 'in_progress') return 'assistant-agent-status-running'
  if (value === 'queued') return 'assistant-agent-status-queued'
  if (value === 'paused') return 'assistant-agent-status-paused'
  if (value === 'rolled_back') return 'assistant-agent-status-rolled-back'
  if (value === 'cancelled' || value === 'failed') return 'assistant-agent-status-failed'
  return 'assistant-agent-status-queued'
}

function resolveAgentGoalConclusionLabel(task: HomeAgentRuntimeTaskSummary) {
  const status = String(task?.goalConclusion?.status || '')
  if (status === 'achieved') return '目标达成'
  if (status === 'not_achieved') return '目标未达成'
  if (status === 'rolled_back') return '已回滚'
  return '执行中'
}

function resolveAgentGoalConclusionClass(task: HomeAgentRuntimeTaskSummary) {
  const status = String(task?.goalConclusion?.status || '')
  if (status === 'achieved') return 'assistant-agent-goal-achieved'
  if (status === 'not_achieved') return 'assistant-agent-goal-not-achieved'
  if (status === 'rolled_back') return 'assistant-agent-goal-rolled-back'
  return 'assistant-agent-goal-pending'
}

function resolveAgentTaskControlDescriptor(task: HomeAgentRuntimeTaskSummary, action: HomeAgentRuntimeTaskControlAction) {
  const descriptors = Array.isArray(task?.controlActions) ? task.controlActions : []
  return descriptors.find(item => String(item.action || '') === action) || null
}

function canControlAgentTask(task: HomeAgentRuntimeTaskSummary, action: HomeAgentRuntimeTaskControlAction) {
  const descriptor = resolveAgentTaskControlDescriptor(task, action)
  return Boolean(descriptor?.enabled)
}

function resolveAgentTaskControlExpectedOutcome(task: HomeAgentRuntimeTaskSummary, action: HomeAgentRuntimeTaskControlAction) {
  const descriptor = resolveAgentTaskControlDescriptor(task, action)
  return String(descriptor?.expectedOutcome || '').trim() || '任务状态将被更新。'
}

function resolveAgentControlLabel(action: HomeAgentRuntimeTaskControlAction) {
  if (action === 'pause') return '暂停'
  if (action === 'resume') return '继续'
  if (action === 'retry') return '重试'
  if (action === 'rollback') return '回滚'
  if (action === 'cancel') return '取消'
  return '执行操作'
}

function ensureSelectedAgentPreset() {
  if (!selectedAgentPresetId.value && selectedAgentPreset.value?.presetId) {
    selectedAgentPresetId.value = selectedAgentPreset.value.presetId
  }
  if (!agentGoalDraft.value.trim() && selectedAgentPreset.value?.suggestedPrompt) {
    agentGoalDraft.value = selectedAgentPreset.value.suggestedPrompt
  }
}

function handleSelectAgentPreset(presetId: string) {
  selectedAgentPresetId.value = String(presetId || '').trim()
  if (selectedAgentPreset.value?.suggestedPrompt) {
    agentGoalDraft.value = selectedAgentPreset.value.suggestedPrompt
  }
}

function resolveSelectedAgentPlanSteps() {
  return Array.isArray(selectedAgentPreset.value?.workflowPreview)
    ? selectedAgentPreset.value?.workflowPreview || []
    : []
}

async function appendTaskArtifactsToConversation(task: HomeAgentRuntimeTaskSummary) {
  const taskId = String(task?.taskId || '').trim()
  if (!taskId || injectedTaskArtifactIds.has(taskId)) return
  injectedTaskArtifactIds.add(taskId)

  try {
    const response = await getHomeAgentTaskArtifacts(taskId)
    const payload = response.data as ApiResponse<{
      taskId: string
      status: string
      artifacts: Array<{ title: string; content: string }>
    }>
    if (!isSuccessCode(payload.code)) return

    const artifacts = Array.isArray(payload.data?.artifacts) ? payload.data?.artifacts || [] : []
    if (!artifacts.length) return

    const lines: string[] = []
    lines.push(`## 任务完成：${task.title}`)
    lines.push(`- 目标结论：${resolveAgentGoalConclusionLabel(task)}`)
    lines.push(`- 结论说明：${String(task.goalConclusion?.summary || '已生成执行产物。')}`)
    artifacts.slice(0, 2).forEach((artifact) => {
      lines.push('')
      lines.push(`### ${artifact.title}`)
      lines.push(String(artifact.content || '').trim())
    })

    messages.value.push({
      messageId: `gaw_agent_artifact_${taskId}_${Date.now()}`,
      role: 'assistant',
      content: lines.join('\n'),
      status: 'succeeded',
      createdAt: new Date().toISOString(),
      actions: [],
      fileNames: [],
      agentTrace: null,
    })
    await scrollToBottom()
  } catch {
    // ignore silent polling errors
  }
}

async function maybeInjectCompletedTaskArtifacts(taskList: HomeAgentRuntimeTaskSummary[]) {
  const candidates = (Array.isArray(taskList) ? taskList : []).filter(task => {
    const status = String(task?.status || '')
    return status === 'completed' || status === 'rolled_back'
  })
  for (const task of candidates) {
    await appendTaskArtifactsToConversation(task)
  }
}

function resolveAgentRuntimeTaskProgress(task: HomeAgentRuntimeTaskSummary) {
  const value = Number(task?.progress || 0)
  if (!Number.isFinite(value)) return 0
  return Math.min(100, Math.max(0, Math.round(value)))
}

async function refreshAgentRuntimeOverview(options?: { silent?: boolean }) {
  if (!options?.silent) {
    agentRuntimeLoading.value = true
  }
  try {
    const response = await getHomeAgentRuntimeOverview()
    const payload = response.data as ApiResponse<HomeAgentRuntimeOverviewResult>
    if (isSuccessCode(payload.code) && payload.data) {
      agentRuntimeOverview.value = payload.data
      ensureSelectedAgentPreset()
      await maybeInjectCompletedTaskArtifacts(payload.data.latestTasks || [])
      if (hasAgentRuntimeActiveTask()) {
        ensureAgentRuntimePolling()
      } else {
        stopAgentRuntimePolling()
      }
    }
  } catch (error) {
    if (!options?.silent) {
      ElMessage.error(error instanceof Error ? error.message : '任务编排数据加载失败')
    }
  } finally {
    if (!options?.silent) {
      agentRuntimeLoading.value = false
    }
  }
}

async function handleLaunchAgentRuntimeTask(preset: HomeAgentRuntimePreset) {
  const presetId = String(preset?.presetId || '').trim()
  if (!presetId || launchingAgentPresetId.value) return

  const goalText = String(agentGoalDraft.value || '').trim() || String(preset?.suggestedPrompt || '').trim()
  if (!goalText) {
    ElMessage.warning('请先输入本轮目标')
    return
  }

  const expectedOutcome = String(preset?.expectedOutcome || '').trim() || '任务将生成结构化执行建议与产物。'
  try {
    await ElMessageBox.confirm(`目标：${goalText}\n\n预期结果：${expectedOutcome}`, `启动任务：${preset.title}`, {
      confirmButtonText: '确认启动',
      cancelButtonText: '取消',
      type: 'info',
    })
  } catch {
    return
  }

  launchingAgentPresetId.value = presetId
  try {
    const response = await createHomeAgentTask({
      presetId,
      prompt: goalText,
      pageContext: buildCurrentPageContext(incomingContext.value),
    })
    const payload = response.data as ApiResponse<{ task: HomeAgentRuntimeTaskSummary }>
    if (!isSuccessCode(payload.code) || !payload.data?.task) {
      throw new Error(payload.msg || '任务启动失败')
    }

    messages.value.push({
      messageId: `gaw_agent_goal_${Date.now()}`,
      role: 'user',
      content: `任务目标：${goalText}`,
      status: 'succeeded',
      createdAt: new Date().toISOString(),
      actions: [],
      fileNames: [],
      agentTrace: null,
    })

    await refreshAgentRuntimeOverview({ silent: true })
    ensureAgentRuntimePolling()
    ElMessage.success(`已启动任务：${preset.title}`)
    await scrollToBottom()
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '任务启动失败')
  } finally {
    launchingAgentPresetId.value = ''
  }
}

async function handleControlAgentTask(taskId: string, action: HomeAgentRuntimeTaskControlAction) {
  const normalizedTaskId = String(taskId || '').trim()
  const task = agentRuntimeTasks.value.find(item => item.taskId === normalizedTaskId) || null
  if (!normalizedTaskId || !task || controllingAgentTaskId.value || !canControlAgentTask(task, action)) return

  const controlLabel = resolveAgentControlLabel(action)
  const expectedOutcome = resolveAgentTaskControlExpectedOutcome(task, action)
  try {
    await ElMessageBox.confirm(`预期结果：${expectedOutcome}`, `确认${controlLabel}`, {
      confirmButtonText: `确认${controlLabel}`,
      cancelButtonText: '取消',
      type: 'warning',
    })
  } catch {
    return
  }

  controllingAgentTaskId.value = normalizedTaskId
  try {
    const response = await controlHomeAgentTask(normalizedTaskId, action)
    const payload = response.data as ApiResponse<{ task: HomeAgentRuntimeTaskSummary }>
    if (!isSuccessCode(payload.code) || !payload.data?.task) {
      throw new Error(payload.msg || '任务控制失败')
    }
    await refreshAgentRuntimeOverview({ silent: true })
    if (hasAgentRuntimeActiveTask()) {
      ensureAgentRuntimePolling()
    }
    ElMessage.success(`已执行：${controlLabel}`)
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '任务控制失败')
  } finally {
    controllingAgentTaskId.value = ''
  }
}

// 保留兼容任务运行时旧入口的函数引用，避免严格 noUnusedLocals 构建失败。
void [
  resolveAgentRuntimeTaskStatusLabel,
  resolveAgentRuntimeTaskStatusClass,
  resolveAgentGoalConclusionClass,
  handleSelectAgentPreset,
  resolveSelectedAgentPlanSteps,
  resolveAgentRuntimeTaskProgress,
  handleLaunchAgentRuntimeTask,
  handleControlAgentTask,
]

function clampPanelPosition() {
  const maxX = Math.max(12, window.innerWidth - panelWidth.value - 12)
  const maxY = Math.max(12, window.innerHeight - panelHeight.value - 12)
  panelX.value = Math.min(Math.max(12, panelX.value), maxX)
  panelY.value = Math.min(Math.max(12, panelY.value), maxY)
}

type ResizeDirection = 'n' | 'ne' | 'e' | 'se' | 's' | 'sw' | 'w' | 'nw'
const resizeDirections: ResizeDirection[] = ['n', 'ne', 'e', 'se', 's', 'sw', 'w', 'nw']

function stopPanelResize() {
  activeResizeCleanup?.()
  activeResizeCleanup = null
}

function startPanelResize(direction: ResizeDirection, event: MouseEvent) {
  const panel = panelRef.value
  if (!panel) return

  stopPanelResize()
  const rect = panel.getBoundingClientRect()
  const startX = event.clientX
  const startY = event.clientY
  const startLeft = rect.left
  const startTop = rect.top
  const startRight = rect.right
  const startBottom = rect.bottom
  const minWidth = Math.min(400, window.innerWidth - 24)
  const minHeight = Math.min(420, window.innerHeight - 24)
  const previousCursor = document.body.style.cursor
  const previousUserSelect = document.body.style.userSelect
  document.body.style.cursor = getComputedStyle(event.currentTarget as HTMLElement).cursor
  document.body.style.userSelect = 'none'

  const onMove = (moveEvent: MouseEvent) => {
    const deltaX = moveEvent.clientX - startX
    const deltaY = moveEvent.clientY - startY

    if (direction.includes('e')) {
      panelWidth.value = Math.max(minWidth, Math.min(window.innerWidth - startLeft - 12, rect.width + deltaX))
    }
    if (direction.includes('s')) {
      panelHeight.value = Math.max(minHeight, Math.min(window.innerHeight - startTop - 12, rect.height + deltaY))
    }
    if (direction.includes('w')) {
      const nextLeft = Math.max(12, Math.min(startRight - minWidth, startLeft + deltaX))
      panelX.value = nextLeft
      panelWidth.value = startRight - nextLeft
    }
    if (direction.includes('n')) {
      const nextTop = Math.max(12, Math.min(startBottom - minHeight, startTop + deltaY))
      panelY.value = nextTop
      panelHeight.value = startBottom - nextTop
    }
  }

  const cleanup = () => {
    window.removeEventListener('mousemove', onMove)
    window.removeEventListener('mouseup', stopPanelResize)
    document.body.style.cursor = previousCursor
    document.body.style.userSelect = previousUserSelect
  }
  activeResizeCleanup = cleanup
  window.addEventListener('mousemove', onMove)
  window.addEventListener('mouseup', stopPanelResize)
}

function renderMarkdownContent(content: string) {
  const rendered = markdownRenderer.render(String(content || ''))
  return DOMPurify.sanitize(rendered)
}

// 流式输出期间用纯文本渲染（避免每个 token 都做 markdown+DOMPurify 全量渲染），
// 等消息成功后（done）再渲染完整 markdown。
function renderAssistantContent(message: HomeMessage) {
  if (message.status === 'processing') {
    return String(message.content || '').replace(/</g, '&lt;').replace(/>/g, '&gt;')
  }
  return renderMarkdownContent(message.content || '')
}

function parseSsePayload(raw: string) {
  try {
    return JSON.parse(raw) as Record<string, unknown>
  } catch {
    return null
  }
}

function closeStream() {
  if (eventSourceRef.value) {
    eventSourceRef.value.close()
    eventSourceRef.value = null
  }
}

async function scrollToBottom() {
  await nextTick()
  if (!chatListRef.value) return
  if (!shouldStickToBottom.value) return
  chatListRef.value.scrollTop = chatListRef.value.scrollHeight
}

function forceStickToBottom() {
  shouldStickToBottom.value = true
}

function handleChatListScroll() {
  const element = chatListRef.value
  if (!element) return
  const distance = element.scrollHeight - (element.scrollTop + element.clientHeight)
  shouldStickToBottom.value = distance <= 72
}

function updateAssistantMessage(messageId: string, patch: Partial<HomeMessage>) {
  const index = messages.value.findIndex(item => item.messageId === messageId)
  if (index < 0) return
  const merged = {
    ...messages.value[index],
    ...patch,
  }
  merged.taskResultCard = sanitizeTaskResultCard(merged.messageId, merged.taskResultCard || null, merged.actions || [], merged.status)
  messages.value[index] = merged
}

function isTaskStreamAssistantMessage(messageId: string) {
  return String(messageId || '').startsWith('task_assistant_')
}

function sanitizeTaskResultCard(
  messageId: string,
  card: HomeMessage['taskResultCard'] | null | undefined,
  actions: HomeMessage['actions'] | null | undefined,
  status?: string,
) {
  if (!card || typeof card !== 'object') return null

  const actionList = Array.isArray(actions) ? actions : []

  // Prefer explicit status, fallback to existing message status if available
  let effectiveStatus = typeof status === 'string' && String(status).trim() ? String(status).trim() : ''
  if (!effectiveStatus) {
    const existing = messages.value.find(item => item.messageId === String(messageId || '').trim())
    if (existing && typeof existing.status === 'string') {
      effectiveStatus = String(existing.status || '').trim()
    }
  }

  if (isTaskStreamAssistantMessage(messageId)) {
    if (effectiveStatus !== 'succeeded') return null
    return card
  }

  if (effectiveStatus && effectiveStatus !== 'succeeded') return null
  if (!actionList.length) return null

  const kind = String(card.kind || '').trim()
  const hasStudentRoute = actionList.some(action => String(action?.route || '').trim() === '/student')
  const hasMatchRoute = actionList.some(action => ['/match', '/jobs', '/report'].includes(String(action?.route || '').trim()))
  const hasParseResumeAction = actionList.some(
    action => action?.type === 'navigate_and_parse_resume' || action?.intent === 'parse_resume',
  )
  const hasMatchAction = actionList.some(
    action => action?.type === 'apply_recommended_job' || action?.type === 'refine_match_recommendations',
  )

  if (kind === 'resume_scores') {
    return hasParseResumeAction || hasStudentRoute ? card : null
  }
  if (kind === 'job_match') {
    return hasMatchAction || hasMatchRoute ? card : null
  }
  if (kind === 'path_plan') {
    return hasMatchRoute ? card : null
  }
  if (kind === 'generic') {
    return hasStudentRoute || hasMatchRoute ? card : null
  }

  return null
}

function normalizeMessageTaskResultCard(message: HomeMessage) {
  return {
    ...message,
    taskResultCard: sanitizeTaskResultCard(message.messageId, message.taskResultCard || null, message.actions || [], message.status),
  }
}

function upsertTaskUserMessage(messageId: string, content: string) {
  const normalizedId = String(messageId || '').trim()
  const text = String(content || '').trim()
  if (!normalizedId || !text) return
  const existing = messages.value.find(item => item.messageId === normalizedId)
  if (existing) return

  messages.value.push({
    messageId: normalizedId,
    role: 'user',
    content: text,
    status: 'succeeded',
    createdAt: new Date().toISOString(),
    actions: [],
    fileNames: [],
    agentTrace: null,
  })
}

function upsertTaskAssistantMessage(payload: TaskOrchestratorStreamPayload) {
  const assistantMessageId = String(payload.assistantMessageId || '').trim()
  if (!assistantMessageId) return

  const existingIndex = messages.value.findIndex(item => item.messageId === assistantMessageId)
  const trace = payload.agentTrace && typeof payload.agentTrace === 'object'
    ? (payload.agentTrace as unknown as HomeAgentTrace)
    : null
  const status = payload.assistantStatus === 'failed'
    ? 'failed'
    : payload.assistantStatus === 'succeeded'
      ? 'succeeded'
      : 'processing'
  const nextActions = Array.isArray(payload.actions)
    ? payload.actions
    : existingIndex >= 0
      ? messages.value[existingIndex].actions || []
      : []
  const nextTaskResultCard = payload.taskResultCard || (existingIndex >= 0 ? messages.value[existingIndex].taskResultCard || null : null)

  const nextMessage: HomeMessage = {
    messageId: assistantMessageId,
    role: 'assistant',
    content: String(payload.assistantText || ''),
    status,
    createdAt: existingIndex >= 0 ? messages.value[existingIndex].createdAt : new Date().toISOString(),
    actions: nextActions,
    fileNames: existingIndex >= 0 ? messages.value[existingIndex].fileNames || [] : [],
    agentTrace: trace,
    taskResultCard: sanitizeTaskResultCard(assistantMessageId, nextTaskResultCard, nextActions, status),
  }

  if (existingIndex >= 0) {
    messages.value[existingIndex] = {
      ...messages.value[existingIndex],
      ...nextMessage,
    }
  } else {
    messages.value.push(nextMessage)
  }
}

async function applyTaskTranscriptFallback(sessionId: string) {
  // 尝试从本地 transcript 恢复（不再仅限于当前 task session）
  const taskMessages = getTaskTranscriptMessages(sessionId)
  if (!taskMessages.length) return false
  console.info('[taskTranscript] GlobalAssistant 恢复本地 transcript', sessionId, taskMessages.length)
  activeSessionId.value = sessionId
  messages.value = taskMessages
  if (visible.value) {
    await scrollToBottom()
  }
  return true
}

async function handleTaskOrchestratorStreamEvent(event: Event) {
  const payload = (event as CustomEvent<TaskOrchestratorStreamPayload>).detail
  if (!payload || typeof payload !== 'object') return

  const sessionId = String(payload.sessionId || '').trim()
  if (sessionId && payload.phase === 'start') {
    await refreshSessions().catch(() => {})
    if (activeSessionId.value !== sessionId) {
      const loaded = await loadMessages(sessionId)
      if (!loaded) {
        activeSessionId.value = sessionId
        messages.value = []
      }
    }
  }

  if (sessionId && activeSessionId.value && activeSessionId.value !== sessionId) return

  upsertTaskUserMessage(String(payload.userMessageId || ''), String(payload.userText || ''))
  upsertTaskAssistantMessage(payload)
  if (payload.phase === 'done') {
    const messageId = String(payload.assistantMessageId || '').trim()
    const polishAction = Array.isArray(payload.actions)
      ? payload.actions.find(action => action?.type === 'one_click_polish')
      : null
    const currentApproval = pendingApprovalsByMessageId.value[messageId]
    if (messageId && polishAction && !currentApproval) {
      pendingApprovalsByMessageId.value = {
        ...pendingApprovalsByMessageId.value,
        [messageId]: {
          approvalId: `local_${messageId}`,
          title: '确认执行报告润色',
          summary: '将根据当前建议自动填写参数并发起报告润色任务。',
          status: 'pending',
          action: polishAction,
        },
      }
    }
  }
  if (visible.value) {
    await scrollToBottom()
  }
}

async function handleHomeAssistantSessionRefreshEvent(event: Event) {
  const detail = (event as CustomEvent<HomeAssistantSessionRefreshPayload>).detail || {}
  await refreshSessions().catch(() => {})

  const sessionId = String(detail.sessionId || '').trim()
  const shouldFollowTaskSession = (detail.reason === 'task-created' || detail.reason === 'task-updated')
  if (shouldFollowTaskSession && sessionId && activeSessionId.value !== sessionId) {
    const loaded = await loadMessages(sessionId)
    if (!loaded) {
      activeSessionId.value = sessionId
      messages.value = []
    }
  } else if (shouldFollowTaskSession && sessionId && activeSessionId.value === sessionId) {
    await loadMessages(sessionId)
  }
}

function openTaskOrchestratorFromAssistant() {
  const panelRect = panelRef.value?.getBoundingClientRect() || null
  const preferredX = panelRect ? panelRect.left - 420 : panelX.value - 420
  const fallbackX = panelRect ? panelRect.right + 12 : panelX.value + panelWidth.value + 12
  const canPlaceLeft = preferredX >= 12
  const targetX = canPlaceLeft ? preferredX : fallbackX
  const targetY = panelRect ? panelRect.top + 48 : panelY.value + 48

  openTaskOrchestrator({
    routePath: route.path,
    pageTitle: String(route.meta.title || ''),
    initialGoal: String(draft.value || '').trim() || undefined,
    source: 'global-assistant',
    anchorX: targetX,
    anchorY: targetY,
  })
}

function isTraceExpanded(messageId: string) {
  return Boolean(traceExpandedByMessageId.value[messageId])
}

function toggleTraceExpanded(messageId: string) {
  traceExpandedByMessageId.value = {
    ...traceExpandedByMessageId.value,
    [messageId]: !isTraceExpanded(messageId),
  }
}

function resolveTraceStepStatusLabel(status: string) {
  const value = String(status || '')
  if (value === 'succeeded' || value === 'completed') return '已完成'
  if (value === 'processing' || value === 'in_progress') return '进行中'
  if (value === 'failed') return '失败'
  return '待处理'
}

function resolveTraceCollapsedText(trace: HomeAgentTrace) {
  const status = String(trace?.status || '')
  const activeStep = trace?.steps?.find(step => step.stepId === trace.activeStepId)
  if (status === 'processing') return activeStep?.title || '正在思考中'
  if (status === 'succeeded') return '思考完成，点击展开查看过程'
  if (status === 'failed') return '思考中断，点击展开查看详情'
  return '点击展开查看过程'
}

function resolveTraceElapsed(trace: HomeAgentTrace) {
  const startedAt = Date.parse(String(trace?.startedAt || ''))
  const finishedAt = Date.parse(String(trace?.finishedAt || ''))
  if (!Number.isFinite(startedAt)) return '用时未知'
  const endAt = Number.isFinite(finishedAt) ? finishedAt : Date.now()
  const seconds = Math.max(1, Math.round((endAt - startedAt) / 1000))
  return `用时${seconds}秒`
}

function resolveTraceTypeIcon(type: string) {
  const value = String(type || '').toLowerCase()
  if (value === 'thought') return thoughtIcon
  if (value === 'tool') return toolIcon
  if (value === 'task') return taskIcon
  if (value === 'plan') return planIcon
  return planIcon
}

function resolveTraceTypeLabel(type: string) {
  const value = String(type || '').toLowerCase()
  if (value === 'thought') return 'Thought'
  if (value === 'tool') return 'Tool'
  if (value === 'task') return 'Task'
  if (value === 'plan') return 'Plan'
  return 'Plan'
}

function resolveTraceTypeClass(type: string) {
  const value = String(type || '').toLowerCase()
  if (value === 'thought') return 'trace-type-thought'
  if (value === 'tool') return 'trace-type-tool'
  if (value === 'task') return 'trace-type-task'
  return 'trace-type-plan'
}

function resolveTraceStatusIcon(status: string) {
  const value = String(status || '')
  if (value === 'succeeded' || value === 'completed') return CircleCheckFilled
  if (value === 'failed') return CircleCloseFilled
  if (value === 'processing' || value === 'in_progress') return Loading
  return WarningFilled
}

function resolveTracePlanSteps(trace: HomeAgentTrace) {
  return (Array.isArray(trace?.steps) ? trace.steps : []).filter(step => String(step?.type || '').toLowerCase() !== 'tool')
}

function resolveTraceToolSteps(trace: HomeAgentTrace) {
  return (Array.isArray(trace?.steps) ? trace.steps : []).filter(step => String(step?.type || '').toLowerCase() === 'tool')
}

function resolveTraceToolExecutionResult(step: HomeAgentTrace['steps'][number]) {
  return String(step?.outputSummary || step?.detail || step?.inputSummary || '').trim() || '执行中，结果待返回。'
}

function resolveTraceStepStatusClass(status: string) {
  const value = String(status || '')
  if (value === 'succeeded' || value === 'completed') return 'trace-step-done'
  if (value === 'processing' || value === 'in_progress') return 'trace-step-running'
  if (value === 'failed') return 'trace-step-failed'
  return 'trace-step-pending'
}

function resolveTraceTaskStatusLabel(status: string) {
  const value = String(status || '')
  if (value === 'completed') return '已完成'
  if (value === 'in_progress') return '进行中'
  if (value === 'failed') return '失败'
  return '待处理'
}

function resolveTraceTaskStatusClass(status: string) {
  const value = String(status || '')
  if (value === 'completed') return 'trace-task-done'
  if (value === 'in_progress') return 'trace-task-running'
  if (value === 'failed') return 'trace-task-failed'
  return 'trace-task-pending'
}

function buildDefaultContextPrompt(path: string) {
  if (path === '/jobs') return '您正在查看岗位图谱，需要我详细解释每个阶段的技能要求吗？'
  if (path === '/report') return '这份报告显示您与目标岗位有一定匹配度，想了解如何提升吗？'
  if (path === '/student') return '上传简历后我可以帮您分析能力短板，并给出补齐建议。'
  return '我可以结合当前页面给你更精准的建议。'
}

function buildCurrentPageContext(payload?: GlobalAssistantContextPayload | null): HomePageContext {
  const currentPath = String(payload?.routePath || route.path || '/').trim() || '/'
  const pageTitle = String(payload?.pageTitle || route.meta.title || '').trim()
  const contextPrompt = String(payload?.contextPrompt || buildDefaultContextPrompt(currentPath)).trim()
  const data = payload?.data && typeof payload.data === 'object' ? payload.data : {}

  return {
    routePath: currentPath,
    pageTitle,
    contextPrompt,
    data,
  }
}

function formatSessionTitle(session: HomeSession) {
  const text = String(session.title || '').trim()
  if (!text) return '未命名会话'
  return text.length > 10 ? `${text.slice(0, 10)}...` : text
}

// 把岗位添加到对话：输入框直接展示 jobId:<id>，发送时原样发送
function addPendingJobRef(job: { jobId: string; jobName: string }) {
  const jobId = String(job?.jobId || '').trim()
  const jobName = String(job?.jobName || '').trim()
  if (!jobId) return

  const jobToken = `jobId:${jobId}`
  const nextDraft = String(draft.value || '')
  draft.value = nextDraft.includes(jobToken)
    ? nextDraft
    : nextDraft.trim()
      ? `${nextDraft.trim()} ${jobToken}`
      : jobToken
  ElMessage.success(`已添加岗位${jobName ? `「${jobName}」` : ''}到对话`)
  nextTick(() => composerInputRef.value?.focus())
}

async function tryAutoSendPendingInitialMessage() {
  const shouldAutoSendInitial = Boolean(incomingContext.value?.autoSendInitialMessage)
  const initialMessage = String(pendingInitialMessage.value || '').trim()
  if (!shouldAutoSendInitial || !initialMessage) return
  pendingInitialMessage.value = ''
  await sendMessage(initialMessage, incomingContext.value)
}

async function refreshSessions() {
  const response = await listHomeSessions()
  const payload = response.data as ApiResponse<{ total: number; list: HomeSession[] }>
  if (isSuccessCode(payload.code) && payload.data) {
    sessions.value = Array.isArray(payload.data.list) ? payload.data.list.slice() : []
  }
}

async function loadMessages(sessionId: string) {
  try {
    const response = await getHomeSessionMessages(sessionId)
    const payload = response.data as ApiResponse<{ sessionId: string; total: number; list: HomeMessage[] }>
    if (!isSuccessCode(payload.code) || !payload.data) {
      const restored = await applyTaskTranscriptFallback(sessionId)
      return restored
    }

    const normalizedList = payload.data.list.map(normalizeMessageTaskResultCard)
    // 若本地存在任务 transcript，则合并本地记录恢复流式输出历史
    const localTranscript = getTaskTranscriptMessages(sessionId)
    messages.value = Array.isArray(localTranscript) && localTranscript.length
      ? mergeTaskTranscriptMessages(sessionId, normalizedList)
      : normalizedList
    cancelEditUserMessage()
    activeSessionId.value = sessionId
    forceStickToBottom()
    await scrollToBottom()
    return true
  } catch (error) {
    const message = String((error as { message?: string })?.message || '')
    if (message.includes('会话不存在')) {
      activeSessionId.value = ''
    }
  }

  const restored = await applyTaskTranscriptFallback(sessionId)
  return restored
}

async function ensureSession() {
  const currentSessionId = String(activeSessionId.value || '').trim()
  if (currentSessionId) {
    try {
      await getHomeSessionMessages(currentSessionId)
      return currentSessionId
    } catch (error) {
      const message = String((error as { message?: string })?.message || '')
      if (!message.includes('会话不存在')) {
        throw error
      }
      activeSessionId.value = ''
      messages.value = []
    }
  }
  await refreshSessions()
  if (sessions.value.length) {
    await loadMessages(sessions.value[0].sessionId)
    return sessions.value[0].sessionId
  }
  const response = await createHomeSession()
  const payload = response.data as ApiResponse<{ sessionId: string }>
  if (!isSuccessCode(payload.code) || !payload.data?.sessionId) {
    throw new Error(payload.msg || '创建会话失败')
  }
  activeSessionId.value = payload.data.sessionId
  messages.value = []
  await refreshSessions()
  return activeSessionId.value
}

async function handleNewSession() {
  if (sending.value || Boolean(regeneratingMessageId.value)) return
  closeStream()
  try {
    const response = await createHomeSession()
    const payload = response.data as ApiResponse<{ sessionId: string; welcomeMessage: HomeMessage | null }>
    if (!isSuccessCode(payload.code) || !payload.data?.sessionId) {
      throw new Error(payload.msg || '创建会话失败')
    }

    activeSessionId.value = payload.data.sessionId
    messages.value = payload.data.welcomeMessage ? [payload.data.welcomeMessage] : []
    traceExpandedByMessageId.value = {}
    pendingApprovalsByMessageId.value = {}
    cancelEditUserMessage()
    draft.value = ''
    forceStickToBottom()
    await refreshSessions()
    await scrollToBottom()
    nextTick(() => composerInputRef.value?.focus())
    ElMessage.success('已创建新会话')
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '创建会话失败')
  }
}

async function startSseStream(messageId: string, streamUrl: string) {
  closeStream()

  const token = getToken() || ''
  // AI 服务直连 8086（Python），可用 VITE_AI_API_BASE_URL 覆盖
  const apiBase = import.meta.env.VITE_AI_API_BASE_URL || 'http://127.0.0.1:8086'
  const connector = streamUrl.includes('?') ? '&' : '?'
  const fullUrl = `${apiBase}${streamUrl}${connector}token=${encodeURIComponent(token)}`

  const source = new EventSource(fullUrl)
  eventSourceRef.value = source
  let streamFinished = false
  let streamClosedByClient = false
  const safeCloseStream = () => {
    streamClosedByClient = true
    if (eventSourceRef.value === source) {
      closeStream()
    }
  }

  source.addEventListener('trace', async (event) => {
    const payload = parseSsePayload((event as MessageEvent).data)
    const trace = payload?.trace as HomeAgentTrace | undefined
    if (!trace) return

    // 若正在流式输出「预回答/思考内容」，自动展开思考面板，让用户直接看到
    const activeStep = Array.isArray(trace.steps)
      ? trace.steps.find(step => step.stepId === trace.activeStepId)
      : undefined
    if (
      activeStep
      && String(activeStep.type || '').toLowerCase() === 'thought'
      && String(activeStep.detail || '').trim()
    ) {
      traceExpandedByMessageId.value = {
        ...traceExpandedByMessageId.value,
        [messageId]: true,
      }
    }

    updateAssistantMessage(messageId, {
      status: 'processing',
      agentTrace: trace,
    })
    await scrollToBottom()
  })

  // 流式渲染节流：delta 事件高频到达时，用 rAF 合帧统一提交
  let pendingDeltaContent: string | null = null
  let deltaRafId = 0
  const flushDelta = () => {
    deltaRafId = 0
    if (pendingDeltaContent === null) return
    updateAssistantMessage(messageId, {
      status: 'processing',
      content: pendingDeltaContent,
    })
    pendingDeltaContent = null
    scrollToBottom()
  }

  source.addEventListener('delta', (event) => {
    const payload = parseSsePayload((event as MessageEvent).data)
    if (!payload) return
    pendingDeltaContent = String(payload.content || '')
    if (!deltaRafId) {
      deltaRafId = requestAnimationFrame(flushDelta)
    }
  })

  source.addEventListener('tool_call', async (event) => {
    const payload = parseSsePayload((event as MessageEvent).data)
    const trace = payload?.trace as HomeAgentTrace | undefined
    if (!trace) return
    updateAssistantMessage(messageId, {
      status: 'processing',
      agentTrace: trace,
    })
    await scrollToBottom()
  })

  source.addEventListener('tool_result', async (event) => {
    const payload = parseSsePayload((event as MessageEvent).data)
    const trace = payload?.trace as HomeAgentTrace | undefined
    if (!trace) return
    updateAssistantMessage(messageId, {
      status: 'processing',
      agentTrace: trace,
    })
    await scrollToBottom()
  })

  source.addEventListener('task_update', async (event) => {
    const payload = parseSsePayload((event as MessageEvent).data)
    const trace = payload?.trace as HomeAgentTrace | undefined
    if (!trace) return

    const computeProgressFromTrace = (t: HomeAgentTrace | null | undefined) => {
      if (!t || !Array.isArray(t.steps) || !t.steps.length) return 0
      const steps = t.steps
      const total = steps.length
      const succeeded = steps.filter(s => String(s?.status || '').toLowerCase() === 'succeeded' || String(s?.status || '').toLowerCase() === 'completed').length
      const processing = steps.filter(s => String(s?.status || '').toLowerCase() === 'processing' || String(s?.status || '').toLowerCase() === 'in_progress').length
      const value = Math.round(((succeeded + processing * 0.5) / total) * 100)
      return Math.max(0, Math.min(100, value))
    }

    const buildProgressBar = (progress: number) => {
      const normalized = Math.max(0, Math.min(100, Math.round(progress || 0)))
      const total = 18
      const filled = Math.max(0, Math.min(total, Math.round((normalized / 100) * total)))
      return `[${'#'.repeat(filled)}${'-'.repeat(total - filled)}] ${normalized}%`
    }

    const progress = computeProgressFromTrace(trace)
    updateAssistantMessage(messageId, {
      status: 'processing',
      agentTrace: trace,
    })

    const index = messages.value.findIndex(m => m.messageId === messageId)
    if (index >= 0) {
      const current = messages.value[index]
      let content = String(current.content || '')
      if (/-\s*进度：/.test(content)) {
        content = content.replace(/-\s*进度：\[[\s\S]*?\]\s*\d+%/, `- 进度：${buildProgressBar(progress)}`)
      } else {
        content += `\n- 进度：${buildProgressBar(progress)}`
      }
      updateAssistantMessage(messageId, { content, status: 'processing' })
    }

    await scrollToBottom()
  })

  source.addEventListener('approval_required', async (event) => {
    const payload = parseSsePayload((event as MessageEvent).data)
    const approval = payload?.approval as {
      approvalId?: string
      title?: string
      summary?: string
      action?: HomeMessageApprovalState['action']
    } | undefined
    if (!approval?.approvalId) return

    pendingApprovalsByMessageId.value = {
      ...pendingApprovalsByMessageId.value,
      [messageId]: {
        approvalId: String(approval.approvalId),
        title: String(approval.title || '请确认执行'),
        summary: String(approval.summary || '该操作需要先审批确认。'),
        status: 'pending',
        action: approval.action,
      },
    }
    updateAssistantMessage(messageId, { status: 'processing' })
    await scrollToBottom()
  })

  source.addEventListener('approval_received', async (event) => {
    const payload = parseSsePayload((event as MessageEvent).data)
    const approval = payload?.approval as {
      approvalId?: string
      status?: string
      action?: HomeMessageApprovalState['action']
    } | undefined
    const status = String(approval?.status || '').trim()
    if (!status) return

    const current = pendingApprovalsByMessageId.value[messageId]
    if (current) {
      const next = { ...pendingApprovalsByMessageId.value }
      if (status === 'approved') {
        delete next[messageId]
      } else {
        next[messageId] = {
          ...current,
          status: status === 'rejected' ? 'rejected' : current.status,
        }
      }
      pendingApprovalsByMessageId.value = next
    }

    if (status === 'approved' && approval?.action?.type === 'one_click_polish') {
      const approvalId = String(approval.approvalId || '')
      if (approvalId && approvalAutoExecutedIds.has(approvalId)) return
      if (approvalId) approvalAutoExecutedIds.add(approvalId)
      await handleMessageAction(approval.action, messageId, { skipApprovalPrompt: true })
    }
  })

  source.addEventListener('done', async (event) => {
    streamFinished = true
    if (deltaRafId) {
      cancelAnimationFrame(deltaRafId)
      deltaRafId = 0
    }
    flushDelta()

    const payload = parseSsePayload((event as MessageEvent).data)
    const message = payload?.message as HomeMessage | undefined
    if (message) {
      updateAssistantMessage(messageId, {
        status: message.status,
        content: message.content,
        actions: message.actions || [],
        agentTrace: message.agentTrace || null,
        taskResultCard: sanitizeTaskResultCard(message.messageId, message.taskResultCard || null, message.actions || [], message.status),
      })
    } else {
      updateAssistantMessage(messageId, { status: 'succeeded' })
    }
    safeCloseStream()
    await refreshSessions()
    await scrollToBottom()
  })

  source.onerror = () => {
    if (streamFinished || streamClosedByClient) return
    if (source.readyState === EventSource.CONNECTING) return
    if (eventSourceRef.value !== source) return
    updateAssistantMessage(messageId, {
      status: 'failed',
      content: '连接中断，请重试发送。',
    })
    safeCloseStream()
  }
}

// ---------- 选择简历（多简历，把 profileId 塞进输入框） ----------
function resumeOptionTitle(item: ResumeListItem): string {
  const content = String(item.content ?? '')
  const line = content.split('\n').map((l) => l.trim()).find((l) => l.length > 0) ?? ''
  const title = line.replace(/^[#>*_\-\s]+/, '').trim()
  const text = title || line || '未命名简历'
  return text.length > 14 ? `${text.slice(0, 14)}…` : text
}

async function loadResumeOptions() {
  try {
    const response = await getStudentProfileList()
    const result = response.data as ApiResponse<ResumeListItem[]>
    if (!isSuccessCode(Number(result.code))) return
    const payload = (result as unknown as { payload?: ResumeListItem[] }).payload ?? result.data
    resumeOptions.value = Array.isArray(payload) ? payload : []
  } catch {
    resumeOptions.value = []
  }
}

function openResumePicker() {
  if (resumePickerHideTimer) {
    clearTimeout(resumePickerHideTimer)
    resumePickerHideTimer = null
  }
  if (resumeOptions.value.length === 0) {
    void loadResumeOptions()
  }
  resumePickerVisible.value = true
}

function closeResumePicker() {
  if (resumePickerHideTimer) clearTimeout(resumePickerHideTimer)
  resumePickerHideTimer = setTimeout(() => {
    resumePickerVisible.value = false
  }, 150)
}

function pickResume(item: ResumeListItem) {
  const pid = String(item.profileId ?? '').trim()
  if (!pid) {
    ElMessage.warning('该简历暂无 id，请先保存')
    return
  }
  const token = `profileId:${pid}`
  const nextDraft = String(draft.value || '')
  draft.value = nextDraft.includes(token)
    ? nextDraft
    : nextDraft.trim() ? `${nextDraft.trim()} ${token}` : token
  resumePickerVisible.value = false
  nextTick(() => composerInputRef.value?.focus())
}

// ---------- 输入框自动增高（默认约两行，随行数增长，最多五行） ----------
// 与 .assistant-input 样式的 line-height(20px)/纵向 padding(8px) 保持一致
const COMPOSER_LINE_HEIGHT = 20
const COMPOSER_MIN_LINES = 2
const COMPOSER_MAX_LINES = 5
const COMPOSER_VERTICAL_PADDING = 8
const COMPOSER_MIN_HEIGHT = COMPOSER_MIN_LINES * COMPOSER_LINE_HEIGHT + COMPOSER_VERTICAL_PADDING
const COMPOSER_MAX_HEIGHT = COMPOSER_MAX_LINES * COMPOSER_LINE_HEIGHT + COMPOSER_VERTICAL_PADDING

function autoResizeComposerInput() {
  const el = composerInputRef.value
  if (!el) return
  el.style.height = 'auto'
  const target = Math.max(COMPOSER_MIN_HEIGHT, Math.min(el.scrollHeight, COMPOSER_MAX_HEIGHT))
  el.style.height = `${target}px`
}

// 输入中实时跟随（@input 兜底），程序化设置 draft 后经 nextTick 重新测量
watch(draft, () => {
  nextTick(() => autoResizeComposerInput())
})

// ---------- 规划方案悬浮列表（把 planId 塞进输入框） ----------
function planOptionTitle(item: CareerPlanListItem): string {
  const base = String(item?.title || '').trim() || '未命名方案'
  const text = item?.status === 'active' ? `${base}（当前）` : base
  return text.length > 22 ? `${text.slice(0, 22)}…` : text
}

async function loadPlanOptions() {
  try {
    const response = await getCareerPlanList()
    const payload = response.data as ApiResponse<{ total: number; list: CareerPlanListItem[] }>
    planOptions.value = isSuccessCode(payload.code) && payload.data
      ? (payload.data.list || [])
      : []
  } catch {
    planOptions.value = []
  }
}

function openPlanPicker() {
  if (planPickerHideTimer) {
    clearTimeout(planPickerHideTimer)
    planPickerHideTimer = null
  }
  if (planOptions.value.length === 0) {
    void loadPlanOptions()
  }
  planPickerVisible.value = true
}

function closePlanPicker() {
  if (planPickerHideTimer) clearTimeout(planPickerHideTimer)
  planPickerHideTimer = setTimeout(() => {
    planPickerVisible.value = false
  }, 150)
}

function pickPlan(item: CareerPlanListItem) {
  const planId = String(item?.id ?? '').trim()
  if (!planId) return
  const token = `planId:${planId}`
  const nextDraft = String(draft.value || '')
  draft.value = nextDraft.includes(token)
    ? nextDraft
    : nextDraft.trim() ? `${nextDraft.trim()} ${token}` : token
  planPickerVisible.value = false
  nextTick(() => composerInputRef.value?.focus())
}

// ---------- 面试记录悬浮列表（把 reportId 塞进输入框） ----------
function reportOptionTitle(item: InterviewReportListItem): string {
  const title = String(item?.title || '').trim()
  const context = [item?.companyName, item?.jobName].filter(Boolean).join(' · ')
  const base = title || context || '面试总结'
  const text = context && context !== title ? `${base} · ${context}` : base
  return text.length > 26 ? `${text.slice(0, 26)}…` : text
}

async function loadReportOptions() {
  try {
    const response = await getInterviewReportList()
    const payload = response.data as ApiResponse<{ total: number; list: InterviewReportListItem[] }>
    reportOptions.value = isSuccessCode(payload.code) && payload.data
      ? (payload.data.list || [])
      : []
  } catch {
    reportOptions.value = []
  }
}

function openReportPicker() {
  if (reportPickerHideTimer) {
    clearTimeout(reportPickerHideTimer)
    reportPickerHideTimer = null
  }
  if (reportOptions.value.length === 0) {
    void loadReportOptions()
  }
  reportPickerVisible.value = true
}

function closeReportPicker() {
  if (reportPickerHideTimer) clearTimeout(reportPickerHideTimer)
  reportPickerHideTimer = setTimeout(() => {
    reportPickerVisible.value = false
  }, 150)
}

function pickReport(item: InterviewReportListItem) {
  const reportId = String(item?.id ?? '').trim()
  if (!reportId) return
  const token = `reportId:${reportId}`
  const nextDraft = String(draft.value || '')
  draft.value = nextDraft.includes(token)
    ? nextDraft
    : nextDraft.trim() ? `${nextDraft.trim()} ${token}` : token
  reportPickerVisible.value = false
  nextTick(() => composerInputRef.value?.focus())
}

async function sendMessage(content: string, contextPayload?: GlobalAssistantContextPayload | null) {
  const text = String(content || '').trim()
  if (!text || sending.value) return

  sending.value = true
  try {
    const sessionId = await ensureSession()
    const pageContext = buildCurrentPageContext(contextPayload || incomingContext.value)

    // If on jobs page and user asks for recommending jobs with casual phrasing,
    // normalize to the canonical trigger so server returns the quick-filter action.
    let sendText = text
    try {
      const routePath = String(pageContext.routePath || '').trim()
      if (routePath === '/jobs' && /推荐/.test(text) && /岗位/.test(text)) {
        sendText = '帮我推荐一个岗位'
      }
    } catch {
      // ignore
    }

    const response = await createHomeSessionMessage(sessionId, sendText, {
      pageContext,
      executionMode: 'auto',
      taskPolicy: {
        allowToolCall: true,
        maxSteps: 8,
        timeoutMs: 120000,
      },
    })

    const payload = response.data as ApiResponse<{
      userMessage: HomeMessage
      assistantMessage: HomeMessage
      stream: { protocol: 'sse'; url: string }
    }>

    if (!isSuccessCode(payload.code) || !payload.data) {
      throw new Error(payload.msg || '发送失败')
    }

    // Show original user text in the chat while server processes a canonicalized query if we changed it.
    messages.value.push({ ...payload.data.userMessage, content: text })

    const assistantInitial = payload.data.stream && payload.data.stream.url
      ? { ...payload.data.assistantMessage, taskResultCard: null }
      : payload.data.assistantMessage
    messages.value.push(normalizeMessageTaskResultCard(assistantInitial))
    draft.value = ''
    forceStickToBottom()
    await scrollToBottom()
    await startSseStream(payload.data.assistantMessage.messageId, payload.data.stream.url)
  } catch (error) {
    const message = error instanceof Error ? error.message : '发送失败'
    if (message.includes('会话不存在')) {
      activeSessionId.value = ''
      messages.value = []
      await refreshSessions().catch(() => {})
    }
    ElMessage.error(message)
  } finally {
    sending.value = false
  }
}

async function handleRegenerate(messageId: string) {
  if (!activeSessionId.value || sending.value || Boolean(regeneratingMessageId.value)) return
  if (latestAssistantMessageId.value !== String(messageId || '')) return

  regeneratingMessageId.value = messageId
  try {
    const response = await regenerateHomeSessionMessage(activeSessionId.value, messageId)
    const payload = response.data as ApiResponse<{
      assistantMessage: HomeMessage
      prunedAfterCount?: number
      stream: { protocol: 'sse'; url: string }
    }>
    if (!isSuccessCode(payload.code) || !payload.data) {
      throw new Error(payload.msg || '重新生成失败')
    }

    await loadMessages(activeSessionId.value)
    regeneratingMessageId.value = payload.data.assistantMessage.messageId
    await startSseStream(payload.data.assistantMessage.messageId, payload.data.stream.url)
  } catch (error) {
    regeneratingMessageId.value = ''
    ElMessage.error(error instanceof Error ? error.message : '重新生成失败')
  }
}

function startEditUserMessage(message: HomeMessage) {
  if (message.role !== 'user') return
  if (sending.value || Boolean(regeneratingMessageId.value)) return
  editingUserMessageId.value = String(message.messageId || '')
  editingUserMessageDraft.value = String(message.content || '')
}

function cancelEditUserMessage() {
  editingUserMessageId.value = ''
  editingUserMessageDraft.value = ''
}

async function submitEditUserMessage(messageId: string) {
  if (!activeSessionId.value || sending.value || Boolean(regeneratingMessageId.value)) return

  const targetMessageId = String(messageId || '').trim()
  const nextContent = String(editingUserMessageDraft.value || '').trim()
  if (!targetMessageId) return
  if (!nextContent) {
    ElMessage.warning('请输入修改后的消息内容')
    return
  }

  regeneratingMessageId.value = targetMessageId
  try {
    const response = await regenerateHomeSessionMessage(activeSessionId.value, targetMessageId, {
      content: nextContent,
    })
    const payload = response.data as ApiResponse<{
      assistantMessage: HomeMessage
      prunedAfterCount?: number
      stream: { protocol: 'sse'; url: string }
    }>
    if (!isSuccessCode(payload.code) || !payload.data?.assistantMessage?.messageId) {
      throw new Error(payload.msg || '重新生成失败')
    }

    await loadMessages(activeSessionId.value)
    cancelEditUserMessage()
    regeneratingMessageId.value = payload.data.assistantMessage.messageId
    await startSseStream(payload.data.assistantMessage.messageId, payload.data.stream.url)
  } catch (error) {
    regeneratingMessageId.value = ''
    ElMessage.error(error instanceof Error ? error.message : '重新生成失败')
  }
}

async function handleMessageAction(action: {
  type: string
  route: string
  reportId?: string
  scope?: Record<string, unknown> | null
  keyword?: string
  autoSelect?: boolean
  recommendedJobId?: string
}, messageId = '', options?: { skipApprovalPrompt?: boolean }) {
  if (action.type === 'refine_match_recommendations') {
    try {
      emitMatchRecommendationRefresh({
        status: 'started',
        message: '已发起细化匹配，正在刷新推荐结果…',
      })

      const refineResponse = await refineMatchRecommendations({
        topN: 6,
        scope: (action as { scope?: Record<string, unknown> | null }).scope || null,
      })
      const refinePayload = refineResponse.data as ApiResponse<{ recommendationStatus: string; pollAfterMs?: number }>
      if (!isSuccessCode(refinePayload.code)) {
        throw new Error(refinePayload.msg || '发起细化匹配失败')
      }

      let finished = false
      let attempts = 0
      const maxAttempts = 10
      const wait = (ms: number) => new Promise(resolve => setTimeout(resolve, ms))

      while (!finished && attempts < maxAttempts) {
        attempts += 1
        const pollResponse = await getMatchRecommendations(6)
        const pollPayload = pollResponse.data as ApiResponse<MatchRecommendationsResult>
        if (!isSuccessCode(pollPayload.code) || !pollPayload.data) {
          throw new Error(pollPayload.msg || '获取匹配结果失败')
        }

        const status = String(pollPayload.data.recommendationStatus || '')
        if (status === 'succeeded') {
          finished = true
          emitMatchRecommendationRefresh({
            status: 'succeeded',
            message: '细化匹配已完成，推荐结果已更新。',
          })
          break
        }

        emitMatchRecommendationRefresh({
          status: 'processing',
          message: '细化匹配处理中，推荐结果即将更新。',
        })

        const pollAfterMs = Math.max(1000, Math.min(2000, Number(pollPayload.data.pollAfterMs || 1500)))
        await wait(pollAfterMs)
      }

      if (!finished) {
        emitMatchRecommendationRefresh({
          status: 'processing',
          message: '细化匹配仍在处理中，请稍后查看最新推荐。',
        })
      }

      messages.value.push({
        messageId: `hm_local_${Date.now()}`,
        role: 'assistant',
        content: finished
          ? '已完成按意愿细化后的重新匹配，推荐结果已更新。你可以在岗位探索页的岗位推荐中查看。'
          : '已触发细化匹配，结果仍在处理中。请稍后在岗位探索页的岗位推荐中查看。',
        status: 'succeeded',
        createdAt: new Date().toISOString(),
        actions: [
          {
            type: 'navigate',
            label: '打开岗位探索页查看',
            route: '/jobs',
          },
        ],
      })
      await scrollToBottom()
    } catch (error) {
      emitMatchRecommendationRefresh({
        status: 'failed',
        message: error instanceof Error ? error.message : '细化匹配失败，请稍后重试',
      })
      ElMessage.error(error instanceof Error ? error.message : '细化匹配失败')
    }
    return
  }

  if (action.type === 'apply_recommended_job') {
    const keyword = String(action.keyword || '').trim() || '前端开发'
    const payload = {
      keyword,
      autoSelect: action.autoSelect !== false,
      recommendedJobId: String(action.recommendedJobId || '').trim() || undefined,
      source: 'global-assistant',
    }

    if (route.path === '/jobs') {
      emitJobRecommendationApply(payload)
      ElMessage.success(`已按“${keyword}”执行岗位筛选并自动选择`)
      return
    }

    stashPendingJobRecommendationApply(payload)
    await router.push('/jobs')
    ElMessage.success('已前往岗位探索页应用推荐岗位')
    return
  }

  if (action.type === 'one_click_polish') {
    if (!options?.skipApprovalPrompt && messageId) {
      const current = pendingApprovalsByMessageId.value[messageId]
      if (!current || current.status !== 'pending') {
        pendingApprovalsByMessageId.value = {
          ...pendingApprovalsByMessageId.value,
          [messageId]: {
            approvalId: `local_${messageId}`,
            title: '确认执行报告润色',
            summary: '将根据当前建议自动填写参数并发起报告润色任务。',
            status: 'pending',
            action,
          },
        }
        await scrollToBottom()
      }
      return
    }

    const reportId = String(action.reportId || '').trim()
    if (!reportId) {
      ElMessage.warning('缺少报告ID')
      return
    }
    const scope = action.scope && typeof action.scope === 'object' ? action.scope : null
    const polishPayload = scope && typeof (scope as Record<string, unknown>).polishPayload === 'object'
      ? ((scope as Record<string, unknown>).polishPayload as {
        tone?: string
        targetReader?: string
        focusSections?: Record<string, { instruction?: string }>
      })
      : null

    const nextPayload = {
      tone: String(polishPayload?.tone || 'encouraging'),
      targetReader: String(polishPayload?.targetReader || '个人规划'),
      focusSections: polishPayload?.focusSections && typeof polishPayload.focusSections === 'object'
        ? polishPayload.focusSections
        : {
            currentAssessment: { instruction: '补充当前优势与短板的证据描述。' },
            targetAnalysis: { instruction: '补齐岗位能力要求与个人差距映射。' },
            stagePlan: { instruction: '明确阶段目标、任务、交付物与验收标准。' },
          },
    }
    try {
      const response = await polishCareerReport(reportId, nextPayload)
      const payload = response.data as ApiResponse<{ polishJobId: string }>
      if (!isSuccessCode(payload.code) || !payload.data?.polishJobId) {
        throw new Error(payload.msg || '发起润色失败')
      }
      localStorage.setItem(
        'career_report_pending_polish_job',
        JSON.stringify({ polishJobId: payload.data.polishJobId, reportId, createdAt: Date.now(), source: 'global-assistant-auto-fill' }),
      )
      ElMessage.success('已发起一键润色，任务处理中。润色完成后可点击“立即查看”')
    } catch (error) {
      ElMessage.error(error instanceof Error ? error.message : '发起润色失败')
    }
    return
  }

  if (action.route) {
    await router.push(action.route)
  }
}

async function handleApprovalDecision(messageId: string, decision: 'approve' | 'reject') {
  const approval = pendingApprovalsByMessageId.value[messageId]
  if (!approval || approvalSubmittingByMessageId.value[messageId]) return

  if (String(approval.approvalId || '').startsWith('local_')) {
    if (decision === 'reject') {
      pendingApprovalsByMessageId.value = {
        ...pendingApprovalsByMessageId.value,
        [messageId]: {
          ...approval,
          status: 'rejected',
        },
      }
      ElMessage.success('已拒绝执行')
      return
    }

    const next = { ...pendingApprovalsByMessageId.value }
    delete next[messageId]
    pendingApprovalsByMessageId.value = next
    if (approval.action) {
      await handleMessageAction(approval.action, messageId, { skipApprovalPrompt: true })
    }
    ElMessage.success('已确认执行，任务继续处理中')
    return
  }

  approvalSubmittingByMessageId.value = {
    ...approvalSubmittingByMessageId.value,
    [messageId]: true,
  }

  try {
    const response = await submitHomeMessageApproval(messageId, { decision })
    const payload = response.data as ApiResponse<{ status?: string }>
    if (!isSuccessCode(payload.code)) {
      throw new Error(payload.msg || '审批提交失败')
    }

    if (decision === 'reject') {
      pendingApprovalsByMessageId.value = {
        ...pendingApprovalsByMessageId.value,
        [messageId]: {
          ...approval,
          status: 'rejected',
        },
      }
    }
    ElMessage.success(decision === 'approve' ? '已同意执行，任务继续处理中' : '已拒绝执行')
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '审批提交失败')
  } finally {
    const next = { ...approvalSubmittingByMessageId.value }
    delete next[messageId]
    approvalSubmittingByMessageId.value = next
  }
}

async function initializeWidget() {
  loading.value = true
  try {
    await refreshSessions()
    await refreshAgentRuntimeOverview({ silent: true })
    const overviewRes = await getHomeOverview()
    const overviewPayload = overviewRes.data as ApiResponse<{ assistantQuickPrompts?: string[] }>
    quickPrompts.value = overviewPayload.data?.assistantQuickPrompts || [
      '帮我分析当前页面可以先做什么',
      '如何提升岗位匹配度？',
      '下一步最值得做什么？',
    ]

    if (activeSessionId.value && sessions.value.some(item => item.sessionId === activeSessionId.value)) {
      await loadMessages(activeSessionId.value)
    }
  } finally {
    loading.value = false
  }
}

function openWidget(payload?: GlobalAssistantContextPayload | null) {
  const alreadyVisible = visible.value
  incomingContext.value = payload || null
  pendingInitialMessage.value = String(payload?.initialMessage || '').trim()
  if (payload?.pendingJob?.jobId && payload?.pendingJob?.jobName) {
    addPendingJobRef(payload.pendingJob)
  }
  visible.value = true

  if (alreadyVisible) {
    tryAutoSendPendingInitialMessage().catch(() => {})
  }

  nextTick(() => {
    clampPanelPosition()
  })
}

async function loadFavoriteJobOptions() {
  favoriteJobLoading.value = true
  try {
    const response = await getFavoriteJobs()
    const result = response.data as ApiResponse<FavoriteJobsResult>
    const payload = (result as unknown as { payload?: FavoriteJobsResult }).payload ?? result.data
    favoriteJobOptions.value = isSuccessCode(Number(result.code)) && payload?.list
      ? payload.list
      : []
  } catch {
    favoriteJobOptions.value = []
  } finally {
    favoriteJobLoading.value = false
  }
}

function openFavoriteJobPicker() {
  if (favoriteJobPickerHideTimer) {
    clearTimeout(favoriteJobPickerHideTimer)
    favoriteJobPickerHideTimer = null
  }
  favoriteJobPickerVisible.value = true
  void loadFavoriteJobOptions()
}

function closeFavoriteJobPicker() {
  if (favoriteJobPickerHideTimer) clearTimeout(favoriteJobPickerHideTimer)
  favoriteJobPickerHideTimer = setTimeout(() => {
    favoriteJobPickerVisible.value = false
  }, 150)
}

function pickFavoriteJob(item: FavoriteJobsResult['list'][number]) {
  addPendingJobRef({
    jobId: String(item.jobId || ''),
    jobName: String(item.jobName || '收藏岗位'),
  })
  favoriteJobPickerVisible.value = false
}

function closeWidget() {
  stopPanelResize()
  visible.value = false
}

function onOpenEvent(event: Event) {
  const custom = event as CustomEvent<GlobalAssistantContextPayload>
  openWidget(custom.detail || null)
}

function handleDragStart(event: MouseEvent) {
  if (!panelRef.value) return
  dragging.value = true
  const startX = event.clientX
  const startY = event.clientY
  const startPanelX = panelX.value
  const startPanelY = panelY.value

  const onMove = (moveEvent: MouseEvent) => {
    if (!dragging.value) return
    panelX.value = startPanelX + (moveEvent.clientX - startX)
    panelY.value = startPanelY + (moveEvent.clientY - startY)
    clampPanelPosition()
  }

  const onUp = () => {
    dragging.value = false
    window.removeEventListener('mousemove', onMove)
    window.removeEventListener('mouseup', onUp)
  }

  window.addEventListener('mousemove', onMove)
  window.addEventListener('mouseup', onUp)
}

watch(visible, (value) => {
  if (value) {
    initializeWidget()
      .then(async () => {
        nextTick(() => {
          autoResizeComposerInput()
        })
        await tryAutoSendPendingInitialMessage()
      })
      .catch(() => {})
  } else {
    stopPanelResize()
    closeStream()
    stopAgentRuntimePolling()
  }
})

watch(() => route.path, (path) => {
  if (!visible.value) return
  incomingContext.value = {
    routePath: path,
    pageTitle: String(route.meta.title || ''),
    contextPrompt: buildDefaultContextPrompt(path),
    autoSendPrompt: false,
  }
})

onMounted(() => {
  window.addEventListener(GLOBAL_ASSISTANT_OPEN_EVENT, onOpenEvent as EventListener)
  window.addEventListener(TASK_ORCHESTRATOR_STREAM_EVENT, handleTaskOrchestratorStreamEvent as EventListener)
  window.addEventListener(HOME_ASSISTANT_SESSION_REFRESH_EVENT, handleHomeAssistantSessionRefreshEvent as EventListener)
  window.addEventListener('resize', clampPanelPosition)

})

onBeforeUnmount(() => {
  stopPanelResize()
  closeStream()
  stopAgentRuntimePolling()
  window.removeEventListener(GLOBAL_ASSISTANT_OPEN_EVENT, onOpenEvent as EventListener)
  window.removeEventListener(TASK_ORCHESTRATOR_STREAM_EVENT, handleTaskOrchestratorStreamEvent as EventListener)
  window.removeEventListener(HOME_ASSISTANT_SESSION_REFRESH_EVENT, handleHomeAssistantSessionRefreshEvent as EventListener)
  window.removeEventListener('resize', clampPanelPosition)
})
</script>

<template>
  <button v-if="!visible" class="assistant-fab" type="button" @click="openWidget()" title="打开AI助手">
    <el-icon><ChatDotRound /></el-icon>
  </button>

  <transition name="assistant-float">
    <div v-if="visible" ref="panelRef" class="assistant-panel" :style="panelStyle">
      <span
        v-for="direction in resizeDirections"
        :key="direction"
        class="assistant-resize-handle"
        :class="`is-${direction}`"
        @mousedown.stop.prevent="startPanelResize(direction, $event)"
      ></span>
      <header class="assistant-head" @mousedown.prevent="handleDragStart">
        <div class="assistant-head-left">
          <img :src="aiAvatarImage" class="assistant-logo" alt="AI助手头像" />
          <div>
            <div class="assistant-title">智能对话助手</div>
            <div class="assistant-sub">当前页面上下文已接入</div>
          </div>
        </div>
        <div class="assistant-head-actions">
          <button class="head-btn head-task-btn" type="button" @click.stop="openTaskOrchestratorFromAssistant" title="打开任务编排">
            <el-icon><Operation /></el-icon>
          </button>
          <button class="head-btn" type="button" @click.stop="closeWidget">
            <el-icon><Close /></el-icon>
          </button>
        </div>
      </header>

      <div class="assistant-body" v-loading="loading">
        <div class="assistant-sessions">
          <button
            v-for="item in sessions.slice(0, 6)"
            :key="item.sessionId"
            class="session-chip"
            :class="[activeSessionId === item.sessionId ? 'is-active' : '']"
            type="button"
            @click="loadMessages(item.sessionId)"
            :title="item.title"
          >
            {{ formatSessionTitle(item) }}
          </button>
          <button
            class="session-new-btn"
            type="button"
            :disabled="sending || Boolean(regeneratingMessageId)"
            @click="handleNewSession"
            title="新建会话"
          >
            <el-icon><Plus /></el-icon>
            新会话
          </button>
        </div>

        <div v-if="quickPrompts.length" class="assistant-quick-row">
          <button
            v-for="prompt in quickPrompts.slice(0, 3)"
            :key="prompt"
            class="assistant-quick-chip"
            type="button"
            @click="sendMessage(prompt)"
          >
            {{ prompt }}
          </button>
        </div>

        <div v-if="showAssistantIntroduction" class="assistant-introduction">
          <div class="assistant-introduction-title">我是微光职引小助手，我可以帮你做这些事：</div>
          <div class="assistant-introduction-item">
            <strong>简历画像分析</strong>
            <span>读取你的简历，梳理你的技能、经历、优势与短板</span>
          </div>
          <div class="assistant-introduction-item">
            <strong>岗位匹配诊断</strong>
            <span>结合你想投的岗位（JD），给出匹配度分析和差距项</span>
          </div>
          <div class="assistant-introduction-item">
            <strong>求职策略建议</strong>
            <span>简历修改方向、投递节奏、面试准备重点等</span>
          </div>
        </div>

        <div ref="chatListRef" class="assistant-chat-list" @scroll.passive="handleChatListScroll">
          <div
            v-for="item in messages"
            :key="item.messageId"
            class="assistant-message-row"
            :class="item.role === 'user' ? 'is-user' : 'is-ai'"
          >
            <div v-if="item.role === 'user'" class="assistant-user-bubble" @dblclick="startEditUserMessage(item)">
              <template v-if="editingUserMessageId === item.messageId">
                <div class="assistant-user-edit-wrap">
                  <textarea
                    v-model="editingUserMessageDraft"
                    class="assistant-user-edit-input"
                    placeholder="编辑消息后按回车重新生成，Shift+回车换行"
                    @keydown.enter.exact.prevent="submitEditUserMessage(item.messageId)"
                  />
                  <div class="assistant-user-edit-actions">
                    <button class="assistant-user-edit-btn" type="button" @click="cancelEditUserMessage">取消</button>
                    <button class="assistant-user-edit-btn assistant-user-edit-btn--primary" type="button" @click="submitEditUserMessage(item.messageId)">确认并重生</button>
                  </div>
                </div>
              </template>
              <template v-else>{{ item.content }}</template>
            </div>
            <div v-else class="assistant-ai-card">
              <div v-if="item.agentTrace" class="trace-panel">
                <button type="button" class="trace-head" @click="toggleTraceExpanded(item.messageId)">
                  <span class="trace-title">已思考（{{ resolveTraceElapsed(item.agentTrace) }}）</span>
                  <span class="trace-state">{{ item.agentTrace.status === 'succeeded' ? '完成' : item.agentTrace.status === 'failed' ? '失败' : '思考中' }}</span>
                  <span class="trace-collapse-indicator">{{ isTraceExpanded(item.messageId) ? '收起' : '展开' }}</span>
                </button>

                <div
                  v-if="!isTraceExpanded(item.messageId)"
                  class="trace-collapsed"
                  :class="item.agentTrace.status === 'processing' ? 'is-running' : ''"
                >
                  <span class="trace-collapsed-text">{{ resolveTraceCollapsedText(item.agentTrace) }}</span>
                  <span v-if="item.agentTrace.status === 'processing'" class="trace-loading-dots" aria-hidden="true">
                    <i></i><i></i><i></i>
                  </span>
                </div>

                <div v-if="isTraceExpanded(item.messageId)">
                  <div v-if="item.agentTrace.tasks?.length" class="trace-task-list">
                    <span
                      v-for="task in item.agentTrace.tasks"
                      :key="`${item.messageId}-${task.taskId}`"
                      class="trace-task-chip"
                      :class="resolveTraceTaskStatusClass(task.status)"
                    >
                      <img :src="planIcon" class="trace-chip-icon" alt="task" />
                      <span>{{ task.title }} · {{ resolveTraceTaskStatusLabel(task.status) }}</span>
                      <el-icon class="trace-status-icon"><component :is="resolveTraceStatusIcon(task.status)" /></el-icon>
                    </span>
                  </div>

                  <div v-if="resolveTracePlanSteps(item.agentTrace).length" class="trace-section">
                    <p class="trace-section-title">计划步骤</p>
                    <div class="trace-steps">
                      <div
                        v-for="step in resolveTracePlanSteps(item.agentTrace)"
                        :key="`${item.messageId}-${step.stepId}`"
                        class="trace-step"
                        :class="resolveTraceStepStatusClass(step.status)"
                      >
                        <div class="trace-step-title-row">
                          <div class="trace-step-title-main">
                            <span class="trace-type-chip" :class="resolveTraceTypeClass(step.type)">
                              <img :src="resolveTraceTypeIcon(step.type)" class="trace-chip-icon" alt="type" />
                              <span>{{ resolveTraceTypeLabel(step.type) }}</span>
                            </span>
                            <span class="trace-step-title">{{ step.title }}</span>
                          </div>
                          <span class="trace-step-status">
                            <el-icon class="trace-status-icon"><component :is="resolveTraceStatusIcon(step.status)" /></el-icon>
                            {{ resolveTraceStepStatusLabel(step.status) }}
                          </span>
                        </div>
                        <p v-if="step.detail" class="trace-step-detail">{{ step.detail }}</p>
                      </div>
                    </div>
                  </div>

                  <div v-if="resolveTraceToolSteps(item.agentTrace).length" class="trace-section">
                    <p class="trace-section-title">工具执行结果</p>
                    <div class="trace-steps">
                      <div
                        v-for="step in resolveTraceToolSteps(item.agentTrace)"
                        :key="`${item.messageId}-${step.stepId}`"
                        class="trace-step trace-step-tool-run"
                        :class="resolveTraceStepStatusClass(step.status)"
                      >
                        <div class="trace-step-title-row">
                          <div class="trace-step-title-main">
                            <span class="trace-type-chip trace-type-tool">
                              <img :src="toolIcon" class="trace-chip-icon" alt="tool" />
                              <span>Tool</span>
                            </span>
                            <span class="trace-step-title">{{ step.title }}</span>
                          </div>
                          <span class="trace-step-status">
                            <el-icon class="trace-status-icon"><component :is="resolveTraceStatusIcon(step.status)" /></el-icon>
                            {{ resolveTraceStepStatusLabel(step.status) }}
                          </span>
                        </div>
                        <p class="trace-step-detail">{{ resolveTraceToolExecutionResult(step) }}</p>
                      </div>
                    </div>
                  </div>
                </div>
              </div>

              <div class="assistant-ai-content markdown-body" v-html="renderAssistantContent(item)"></div>

              <div v-if="pendingApprovalsByMessageId[item.messageId]?.status === 'pending'" class="assistant-approval-card">
                <p class="assistant-approval-title">{{ pendingApprovalsByMessageId[item.messageId].title }}</p>
                <p class="assistant-approval-summary">{{ pendingApprovalsByMessageId[item.messageId].summary }}</p>
                <div class="assistant-approval-actions">
                  <button
                    type="button"
                    class="assistant-action-chip"
                    :disabled="Boolean(approvalSubmittingByMessageId[item.messageId])"
                    @click="handleApprovalDecision(item.messageId, 'approve')"
                  >
                    确认
                  </button>
                  <button
                    type="button"
                    class="assistant-action-chip"
                    :disabled="Boolean(approvalSubmittingByMessageId[item.messageId])"
                    @click="handleApprovalDecision(item.messageId, 'reject')"
                  >
                    拒绝
                  </button>
                </div>
              </div>

              <div v-if="item.taskResultCard" class="assistant-task-result-card">
                <div class="assistant-task-result-head">
                  <p class="assistant-task-result-title">{{ item.taskResultCard.title }}</p>
                  <p class="assistant-task-result-summary">{{ item.taskResultCard.summary }}</p>
                </div>

                <AssistantAbilityRadarCard
                  v-if="item.taskResultCard.scores?.length && item.taskResultCard.scores.length >= 8"
                  :scores="item.taskResultCard.scores"
                  title="能力雷达（12维）"
                />

                <div v-if="item.taskResultCard.scores?.length" class="assistant-task-score-grid">
                  <div v-for="score in item.taskResultCard.scores" :key="`${item.messageId}-${score.dimension}`" class="assistant-task-score-item">
                    <div class="assistant-task-score-top">
                      <span>{{ score.dimension }}</span>
                      <span>{{ score.score }}</span>
                    </div>
                    <div class="assistant-task-score-track"><i :style="{ width: `${score.score}%` }"></i></div>
                  </div>
                </div>

                <div v-if="item.taskResultCard.match" class="assistant-task-match-card">
                  <p class="assistant-task-match-title">{{ item.taskResultCard.match.jobName }}</p>
                  <p class="assistant-task-match-meta">{{ item.taskResultCard.match.jobFamily }} · {{ item.taskResultCard.match.city }}</p>
                  <div class="assistant-task-match-scores">
                    <span>综合 {{ item.taskResultCard.match.overallScore }}</span>
                    <span>技能 {{ item.taskResultCard.match.skillScore }}</span>
                    <span>意愿 {{ item.taskResultCard.match.intentScore }}</span>
                    <span>成长 {{ item.taskResultCard.match.growthScore }}</span>
                  </div>
                </div>

                <AssistantJobGapCard
                  v-if="item.taskResultCard.gapItems?.length"
                  :items="item.taskResultCard.gapItems"
                  title="画像与岗位差距"
                />

                <div v-if="item.taskResultCard.pathStages?.length" class="assistant-task-path-list">
                  <div v-for="stage in item.taskResultCard.pathStages" :key="`${item.messageId}-${stage.stage}`" class="assistant-task-path-item">
                    <p class="assistant-task-path-stage">{{ stage.stage }}</p>
                    <p class="assistant-task-path-title">{{ stage.title }}</p>
                    <p class="assistant-task-path-detail">{{ stage.detail }}</p>
                  </div>
                </div>
              </div>

              <div v-if="item.status === 'succeeded' && item.actions?.length && pendingApprovalsByMessageId[item.messageId]?.status !== 'pending'" class="assistant-action-row">
                <button
                  v-for="(action, actionIndex) in item.actions"
                  :key="`${item.messageId}-${actionIndex}`"
                  type="button"
                  class="assistant-action-chip"
                  @click="handleMessageAction(action)"
                >
                  <span>{{ action.label }}</span>
                  <el-icon><ArrowRight /></el-icon>
                </button>
              </div>
              <div class="assistant-ai-ops">
                <button
                  v-if="item.messageId === latestAssistantMessageId"
                  class="icon-btn"
                  type="button"
                  :disabled="sending || Boolean(regeneratingMessageId)"
                  @click="handleRegenerate(item.messageId)"
                >
                  <el-icon><Refresh /></el-icon>
                </button>
              </div>
            </div>
          </div>
        </div>

        <div class="assistant-composer">
          <div class="assistant-composer-tools">
            <div class="assistant-resume-picker" @mouseenter="openResumePicker" @mouseleave="closeResumePicker">
              <button type="button" class="assistant-resume-btn">
                <el-icon><Document /></el-icon>
                <span>选择简历</span>
              </button>
              <div v-if="resumePickerVisible" class="assistant-resume-dropdown">
                <p v-if="resumeOptions.length === 0" class="assistant-resume-empty">暂无简历，请先到「学生画像」页创建</p>
                <button
                  v-for="item in resumeOptions"
                  :key="`${String(item.profileId)}-${item.title}`"
                  type="button"
                  class="assistant-resume-item"
                  :title="resumeOptionTitle(item)"
                  @click="pickResume(item)"
                >
                  {{ resumeOptionTitle(item) }}
                </button>
              </div>
            </div>
            <div class="assistant-picker" @mouseenter="openFavoriteJobPicker" @mouseleave="closeFavoriteJobPicker">
              <button type="button" class="assistant-tool-btn">
                <el-icon><Star /></el-icon>
                <span>岗位收藏</span>
              </button>
              <div v-if="favoriteJobPickerVisible" class="assistant-picker-dropdown assistant-favorite-job-dropdown">
                <p v-if="favoriteJobLoading" class="assistant-picker-empty">正在查询收藏岗位...</p>
                <p v-else-if="favoriteJobOptions.length === 0" class="assistant-picker-empty">暂无收藏岗位，可先到岗位探索页收藏</p>
                <button
                  v-for="item in favoriteJobOptions"
                  :key="`favorite-job-${item.jobId}`"
                  type="button"
                  class="assistant-picker-item assistant-favorite-job-item"
                  :title="`${item.jobName} · ${item.companyName || '公司待补充'}`"
                  @click="pickFavoriteJob(item)"
                >
                  <span>{{ item.jobName }}</span>
                  <small>{{ item.companyName || '公司待补充' }}</small>
                </button>
              </div>
            </div>
            <div class="assistant-picker" @mouseenter="openPlanPicker" @mouseleave="closePlanPicker">
              <button type="button" class="assistant-tool-btn">
                <el-icon><Calendar /></el-icon>
                <span>我的规划方案</span>
              </button>
              <div v-if="planPickerVisible" class="assistant-picker-dropdown">
                <p v-if="planOptions.length === 0" class="assistant-picker-empty">暂无方案，可先到首页让 AI 规划一份</p>
                <button
                  v-for="item in planOptions"
                  :key="`plan-${item.id}`"
                  type="button"
                  class="assistant-picker-item"
                  :title="planOptionTitle(item)"
                  @click="pickPlan(item)"
                >
                  {{ planOptionTitle(item) }}
                </button>
              </div>
            </div>
            <div class="assistant-picker" @mouseenter="openReportPicker" @mouseleave="closeReportPicker">
              <button type="button" class="assistant-tool-btn">
                <el-icon><ChatLineRound /></el-icon>
                <span>面试记录</span>
              </button>
              <div v-if="reportPickerVisible" class="assistant-picker-dropdown">
                <p v-if="reportOptions.length === 0" class="assistant-picker-empty">暂无面试记录，完成模拟面试后自动生成</p>
                <button
                  v-for="item in reportOptions"
                  :key="`report-${item.id}`"
                  type="button"
                  class="assistant-picker-item"
                  :title="reportOptionTitle(item)"
                  @click="pickReport(item)"
                >
                  {{ reportOptionTitle(item) }}
                </button>
              </div>
            </div>
          </div>
          <textarea
            ref="composerInputRef"
            v-model="draft"
            class="assistant-input"
            placeholder="输入你的问题（回车发送）"
            @input="autoResizeComposerInput"
            @keydown.enter.exact.prevent="sendMessage(draft)"
          />
          <button class="assistant-send" type="button" :disabled="sending || Boolean(regeneratingMessageId)" @click="sendMessage(draft)">
            <el-icon><Promotion /></el-icon>
            发送
          </button>
        </div>
      </div>
    </div>
  </transition>
</template>

<style scoped>
.assistant-fab {
  position: fixed;
  right: 22px;
  top: 55%;
  transform: translateY(-50%);
  width: 54px;
  height: 54px;
  border-radius: 50%;
  border: 1px solid #bfdbfe;
  background: linear-gradient(135deg, #2563eb, #1d4ed8);
  color: #fff;
  z-index: 1950;
  box-shadow: 0 12px 26px rgba(29, 78, 216, 0.35);
}

.assistant-panel {
  position: fixed;
  z-index: 1951;
  border: 1px solid #dbeafe;
  border-radius: 16px;
  background: #ffffff;
  box-shadow: 0 22px 42px rgba(15, 23, 42, 0.16);
  display: flex;
  flex-direction: column;
  overflow: hidden;
  min-width: 400px;
  min-height: 420px;
}

.assistant-resize-handle {
  position: absolute;
  z-index: 20;
}

.assistant-resize-handle.is-n,
.assistant-resize-handle.is-s {
  left: 12px;
  right: 12px;
  height: 8px;
  cursor: ns-resize;
}

.assistant-resize-handle.is-n { top: 0; }
.assistant-resize-handle.is-s { bottom: 0; }

.assistant-resize-handle.is-e,
.assistant-resize-handle.is-w {
  top: 12px;
  bottom: 12px;
  width: 8px;
  cursor: ew-resize;
}

.assistant-resize-handle.is-e { right: 0; }
.assistant-resize-handle.is-w { left: 0; }

.assistant-resize-handle.is-ne,
.assistant-resize-handle.is-se,
.assistant-resize-handle.is-sw,
.assistant-resize-handle.is-nw {
  width: 14px;
  height: 14px;
}

.assistant-resize-handle.is-ne { top: 0; right: 0; cursor: nesw-resize; }
.assistant-resize-handle.is-se { right: 0; bottom: 0; cursor: nwse-resize; }
.assistant-resize-handle.is-sw { bottom: 0; left: 0; cursor: nesw-resize; }
.assistant-resize-handle.is-nw { top: 0; left: 0; cursor: nwse-resize; }

.assistant-head {
  height: 56px;
  border-bottom: 1px solid #e2e8f0;
  padding: 0 10px;
  display: flex;
  align-items: center;
  justify-content: space-between;
  background: #f8fbff;
  cursor: move;
  user-select: none;
}

.assistant-head-left {
  display: flex;
  align-items: center;
  gap: 8px;
}

.assistant-logo {
  width: 28px;
  height: 28px;
  border-radius: 8px;
  border: 1px solid #bfdbfe;
  background: #eff6ff;
  object-fit: cover;
  display: block;
}

.assistant-title {
  font-size: 13px;
  color: #0f172a;
  font-weight: 700;
}

.assistant-sub {
  font-size: 11px;
  color: #64748b;
}

.assistant-head-actions {
  display: flex;
  align-items: center;
  gap: 6px;
}

.head-btn {
  width: 26px;
  height: 26px;
  border-radius: 8px;
  border: 1px solid #dbeafe;
  color: #334155;
  background: #fff;
}

.head-task-btn {
  color: #1d4ed8;
  border-color: #bfdbfe;
  background: #eff6ff;
}

.assistant-body {
  flex: 1;
  min-height: 0;
  display: flex;
  flex-direction: column;
  padding: 8px;
  background: #f8fafc;
}

.assistant-sessions {
  display: flex;
  gap: 6px;
  overflow-x: auto;
  padding-bottom: 6px;
}

.session-chip {
  border: 1px solid #dbeafe;
  background: #ffffff;
  color: #1e3a8a;
  font-size: 11px;
  border-radius: 8px;
  padding: 5px 8px;
  white-space: nowrap;
  max-width: 112px;
  overflow: hidden;
  text-overflow: ellipsis;
  transition: transform 0.14s ease, box-shadow 0.14s ease, background-color 0.14s ease;
}

.session-chip:hover {
  background: #f8fbff;
}

.session-chip:active {
  transform: translateY(1px) scale(0.99);
}

.session-chip.is-active {
  background: #eff6ff;
  border-color: #93c5fd;
}

.session-chip.is-task-log {
  border-color: #93c5fd;
  background: linear-gradient(135deg, #eff6ff, #e0f2fe);
}

.session-new-btn {
  display: inline-flex;
  align-items: center;
  gap: 3px;
  border: 1px dashed #93c5fd;
  background: #eff6ff;
  color: #1d4ed8;
  font-size: 11px;
  border-radius: 8px;
  padding: 5px 9px;
  white-space: nowrap;
  cursor: pointer;
  transition: transform 0.14s ease, box-shadow 0.14s ease, background-color 0.14s ease;
}

.session-new-btn:hover {
  background: #e0ebff;
  box-shadow: 0 4px 12px rgba(29, 78, 216, 0.12);
}

.session-new-btn:active {
  transform: translateY(1px) scale(0.98);
}

.session-new-btn:disabled {
  opacity: 0.55;
  cursor: not-allowed;
}

.assistant-quick-row {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  margin-bottom: 6px;
}

.assistant-quick-chip {
  border: 1px solid #e2e8f0;
  border-radius: 9999px;
  font-size: 11px;
  color: #334155;
  background: #fff;
  padding: 3px 8px;
  transition: transform 0.14s ease, box-shadow 0.14s ease, background-color 0.14s ease;
}

.assistant-quick-chip:hover {
  background: #f8fafc;
  box-shadow: 0 4px 12px rgba(15, 23, 42, 0.08);
}

.assistant-quick-chip:active {
  transform: translateY(1px) scale(0.98);
  background: #eef2ff;
}

.assistant-introduction {
  margin-bottom: 6px;
  border: 1px solid #dbeafe;
  border-radius: 10px;
  background: linear-gradient(135deg, #f8fbff, #eff6ff);
  padding: 10px;
}

.assistant-introduction-title {
  font-size: 12px;
  color: #1e3a8a;
  font-weight: 700;
  line-height: 1.5;
}

.assistant-introduction-item {
  display: grid;
  grid-template-columns: max-content 1fr;
  gap: 5px;
  margin-top: 7px;
  font-size: 11px;
  line-height: 1.5;
  color: #334155;
}

.assistant-introduction-item strong {
  color: #1d4ed8;
  white-space: nowrap;
}

.assistant-task-entry {
  margin-bottom: 6px;
  border: 1px solid #dbeafe;
  border-radius: 10px;
  background: linear-gradient(135deg, #f8fbff, #eef6ff);
  padding: 9px;
  display: grid;
  gap: 7px;
}

.assistant-task-entry-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
}

.assistant-task-entry-title {
  font-size: 11px;
  color: #1e3a8a;
  font-weight: 700;
}

.assistant-task-entry-refresh {
  border: 1px solid #bfdbfe;
  border-radius: 9999px;
  background: #eff6ff;
  color: #1d4ed8;
  font-size: 10px;
  padding: 1px 8px;
}

.assistant-task-entry-desc {
  margin: 0;
  font-size: 11px;
  line-height: 1.55;
  color: #334155;
}

.assistant-task-entry-btn {
  justify-self: start;
  border: 1px solid #bfdbfe;
  border-radius: 9999px;
  background: #ffffff;
  color: #1d4ed8;
  font-size: 11px;
  padding: 3px 10px;
  font-weight: 600;
}

.assistant-agent-runtime {
  margin-bottom: 6px;
  border: 1px solid #dbeafe;
  border-radius: 10px;
  background: #f8fbff;
  padding: 8px 9px;
}

.assistant-agent-runtime-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
}

.assistant-agent-runtime-title {
  font-size: 11px;
  color: #1e3a8a;
  font-weight: 700;
}

.assistant-agent-runtime-refresh {
  border: 1px solid #bfdbfe;
  background: #eff6ff;
  color: #1d4ed8;
  border-radius: 9999px;
  padding: 1px 8px;
  font-size: 11px;
}

.assistant-agent-goal-input {
  margin-top: 7px;
  border: 1px solid #dbeafe;
  border-radius: 10px;
  background: #ffffff;
  padding: 6px 7px;
}

.assistant-agent-goal-textarea {
  width: 100%;
  min-height: 54px;
  border: none;
  resize: vertical;
  outline: none;
  font-size: 11px;
  line-height: 1.5;
  color: #0f172a;
  background: transparent;
}

.assistant-agent-goal-meta {
  margin-top: 5px;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  font-size: 10px;
  color: #64748b;
}

.assistant-agent-goal-launch {
  border: 1px solid #bfdbfe;
  border-radius: 9999px;
  background: #eff6ff;
  color: #1d4ed8;
  font-size: 10px;
  padding: 1px 8px;
}

.assistant-agent-runtime-preset-row {
  margin-top: 7px;
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
}

.assistant-agent-runtime-preset {
  border: 1px solid #dbeafe;
  border-radius: 10px;
  background: #ffffff;
  padding: 5px 7px;
  display: inline-flex;
  flex-direction: column;
  gap: 3px;
  min-width: 118px;
  text-align: left;
  transition: transform 0.14s ease, box-shadow 0.14s ease;
}

.assistant-agent-runtime-preset:hover {
  transform: translateY(-1px);
  box-shadow: 0 6px 14px rgba(15, 23, 42, 0.08);
}

.assistant-agent-runtime-preset:disabled {
  opacity: 0.65;
}

.assistant-agent-runtime-preset.is-active {
  border-color: #60a5fa;
  background: #eff6ff;
}

.assistant-agent-runtime-preset-title {
  font-size: 11px;
  font-weight: 600;
  color: #0f172a;
}

.assistant-agent-runtime-preset-meta {
  font-size: 10px;
  color: #64748b;
  display: inline-flex;
  align-items: center;
  gap: 6px;
}

.assistant-agent-plan-card {
  margin-top: 8px;
  border: 1px solid #dbeafe;
  border-radius: 10px;
  background: #ffffff;
  padding: 6px 7px;
}

.assistant-agent-plan-title {
  margin: 0;
  font-size: 11px;
  font-weight: 700;
  color: #1e3a8a;
}

.assistant-agent-plan-expect {
  margin: 4px 0 0;
  font-size: 10px;
  color: #475569;
}

.assistant-agent-plan-list {
  margin: 6px 0 0;
  padding-left: 16px;
  display: grid;
  gap: 4px;
}

.assistant-agent-plan-list li {
  color: #334155;
  font-size: 10px;
}

.assistant-agent-plan-step-title {
  display: block;
  font-weight: 600;
}

.assistant-agent-plan-step-detail {
  display: block;
  color: #64748b;
  margin-top: 1px;
}

.assistant-agent-runtime-task-list {
  margin-top: 8px;
  display: grid;
  gap: 6px;
}

.assistant-agent-runtime-task {
  border: 1px solid #e2e8f0;
  border-radius: 10px;
  background: #ffffff;
  padding: 6px 7px;
}

.assistant-agent-runtime-task-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
}

.assistant-agent-runtime-task-name {
  font-size: 11px;
  font-weight: 600;
  color: #0f172a;
}

.assistant-agent-runtime-task-status {
  font-size: 10px;
  border: 1px solid #cbd5e1;
  border-radius: 9999px;
  padding: 1px 6px;
}

.assistant-agent-status-running {
  color: #1d4ed8;
  border-color: #93c5fd;
  background: #eff6ff;
}

.assistant-agent-status-queued {
  color: #475569;
  border-color: #cbd5e1;
  background: #f8fafc;
}

.assistant-agent-status-paused {
  color: #92400e;
  border-color: #fcd34d;
  background: #fffbeb;
}

.assistant-agent-status-done {
  color: #166534;
  border-color: #86efac;
  background: #f0fdf4;
}

.assistant-agent-status-failed {
  color: #b91c1c;
  border-color: #fca5a5;
  background: #fef2f2;
}

.assistant-agent-status-rolled-back {
  color: #7c3aed;
  border-color: #c4b5fd;
  background: #f5f3ff;
}

.assistant-agent-runtime-task-step {
  margin: 4px 0 0;
  font-size: 10px;
  color: #475569;
}

.assistant-agent-runtime-task-expect {
  margin: 5px 0 0;
  font-size: 10px;
  color: #64748b;
}

.assistant-agent-runtime-goal {
  margin-top: 5px;
  border: 1px solid #dbeafe;
  border-radius: 8px;
  background: #eff6ff;
  padding: 4px 6px;
  display: grid;
  gap: 3px;
  font-size: 10px;
  color: #334155;
}

.assistant-agent-runtime-goal-tag {
  font-weight: 700;
}

.assistant-agent-goal-achieved {
  border-color: #86efac;
  background: #f0fdf4;
  color: #166534;
}

.assistant-agent-goal-not-achieved {
  border-color: #fca5a5;
  background: #fef2f2;
  color: #b91c1c;
}

.assistant-agent-goal-rolled-back {
  border-color: #c4b5fd;
  background: #f5f3ff;
  color: #7c3aed;
}

.assistant-agent-goal-pending {
  border-color: #bfdbfe;
  background: #eff6ff;
  color: #1d4ed8;
}

.assistant-agent-runtime-progress {
  margin-top: 5px;
  width: 100%;
  height: 4px;
  border-radius: 9999px;
  background: #e2e8f0;
  overflow: hidden;
}

.assistant-agent-runtime-progress i {
  display: block;
  height: 100%;
  border-radius: inherit;
  background: linear-gradient(90deg, #2563eb, #3b82f6);
  transition: width 0.25s ease;
}

.assistant-agent-runtime-task-actions {
  margin-top: 6px;
  display: flex;
  flex-wrap: wrap;
  gap: 5px;
}

.assistant-agent-runtime-task-action {
  border: 1px solid #dbeafe;
  background: #eff6ff;
  color: #1d4ed8;
  border-radius: 9999px;
  font-size: 10px;
  padding: 1px 7px;
}

.assistant-agent-runtime-task-action.is-danger {
  border-color: #fecaca;
  background: #fef2f2;
  color: #b91c1c;
}

.assistant-chat-list {
  flex: 1;
  min-height: 0;
  overflow-y: auto;
  display: flex;
  flex-direction: column;
  gap: 8px;
  padding: 3px;
}

.assistant-message-row {
  display: flex;
}

.assistant-message-row.is-user {
  justify-content: flex-end;
}

.assistant-user-bubble {
  max-width: 82%;
  border-radius: 12px;
  background: #e2e8f0;
  color: #1e293b;
  font-size: 12px;
  line-height: 1.5;
  padding: 7px 9px;
  white-space: pre-wrap;
}

.assistant-user-edit-wrap {
  width: min(420px, 82vw);
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.assistant-user-edit-input {
  width: 100%;
  min-height: 72px;
  border: none;
  outline: none;
  resize: vertical;
  border-radius: 10px;
  background: #f1f5f9;
  color: #0f172a;
  padding: 8px;
  font-size: 12px;
  line-height: 1.5;
}

.assistant-user-edit-actions {
  display: flex;
  justify-content: flex-end;
  gap: 8px;
}

.assistant-user-edit-btn {
  border: 1px solid #cbd5e1;
  border-radius: 9999px;
  background: #fff;
  color: #475569;
  font-size: 11px;
  line-height: 1;
  padding: 4px 8px;
}

.assistant-user-edit-btn--primary {
  border-color: #93c5fd;
  background: #eff6ff;
  color: #1d4ed8;
}

.assistant-ai-card {
  width: 100%;
}

.assistant-ai-content {
  font-size: 12px;
  line-height: 1.6;
  color: #1f2937;
}

.assistant-action-row {
  margin-top: 6px;
  display: flex;
  flex-direction: column;
  align-items: flex-start;
  gap: 6px;
}

.assistant-task-result-card {
  margin-top: 8px;
  border: 1px solid #dbeafe;
  border-radius: 10px;
  background: #f8fbff;
  padding: 8px;
}

.assistant-task-result-head {
  display: grid;
  gap: 4px;
}

.assistant-task-result-title {
  margin: 0;
  font-size: 12px;
  font-weight: 700;
  color: #0f172a;
}

.assistant-task-result-summary {
  margin: 0;
  font-size: 11px;
  color: #475569;
  line-height: 1.5;
}

.assistant-task-score-grid {
  margin-top: 6px;
  display: grid;
  gap: 5px;
}

.assistant-task-score-item {
  border: 1px solid #e2e8f0;
  border-radius: 8px;
  background: #ffffff;
  padding: 5px 6px;
}

.assistant-task-score-top {
  display: flex;
  justify-content: space-between;
  font-size: 10px;
  color: #334155;
}

.assistant-task-score-track {
  margin-top: 4px;
  height: 4px;
  border-radius: 9999px;
  background: #e2e8f0;
  overflow: hidden;
}

.assistant-task-score-track i {
  display: block;
  height: 100%;
  border-radius: inherit;
  background: linear-gradient(90deg, #2563eb, #3b82f6);
}

.assistant-task-match-card,
.assistant-task-path-item {
  margin-top: 6px;
  border: 1px solid #e2e8f0;
  border-radius: 8px;
  background: #ffffff;
  padding: 7px;
}

.assistant-task-match-title,
.assistant-task-path-stage {
  margin: 0;
  font-size: 11px;
  font-weight: 700;
  color: #0f172a;
}

.assistant-task-match-meta,
.assistant-task-path-title {
  margin: 3px 0 0;
  font-size: 10px;
  color: #475569;
}

.assistant-task-match-scores {
  margin-top: 6px;
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  font-size: 10px;
  color: #1d4ed8;
}

.assistant-task-path-list {
  margin-top: 6px;
  display: grid;
  gap: 6px;
}

.assistant-task-path-detail {
  margin: 3px 0 0;
  font-size: 10px;
  color: #64748b;
  line-height: 1.45;
}

.assistant-action-chip {
  border: none;
  background: #f1f5f9;
  color: #334155;
  border-radius: 10px;
  padding: 6px 9px;
  display: inline-flex;
  align-items: center;
  gap: 6px;
  font-size: 12px;
  transition: transform 0.14s ease, box-shadow 0.14s ease, background-color 0.14s ease;
}

.assistant-action-chip:hover {
  background: #eaf1ff;
  box-shadow: 0 4px 10px rgba(15, 23, 42, 0.08);
}

.assistant-action-chip:active {
  transform: translateY(1px) scale(0.98);
  background: #dbeafe;
}

.assistant-ai-ops {
  margin-top: 6px;
}

.icon-btn {
  border: none;
  background: transparent;
  color: #64748b;
  padding: 0;
  width: 18px;
  height: 18px;
}

.icon-btn:disabled {
  color: #cbd5e1;
  cursor: not-allowed;
}

.assistant-composer {
  margin-top: 8px;
  border: 1px solid #dbeafe;
  border-radius: 12px;
  background: #fff;
  padding: 8px;
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.assistant-composer-tools {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 6px 8px;
}

.assistant-resume-picker,
.assistant-picker {
  position: relative;
  align-self: flex-start;
}

.assistant-resume-btn,
.assistant-tool-btn {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  flex: 0 0 auto;
  white-space: nowrap;
  border: 1px solid #dbeafe;
  border-radius: 9999px;
  background: #f8fbff;
  color: #3d6c85;
  font-size: 12px;
  padding: 4px 12px;
  cursor: pointer;
  transition: all 160ms ease;
}

.assistant-resume-btn:hover,
.assistant-tool-btn:hover {
  border-color: #3d6c85;
  background: #eef6fa;
}

.assistant-resume-dropdown,
.assistant-picker-dropdown {
  position: absolute;
  left: 0;
  bottom: calc(100% + 6px);
  z-index: 60;
  min-width: 220px;
  max-height: 260px;
  overflow-y: auto;
  border: 1px solid #dbeafe;
  border-radius: 10px;
  background: #fff;
  box-shadow: 0 12px 28px rgba(15, 23, 42, 0.14);
  padding: 6px;
}

.assistant-picker-dropdown {
  left: auto;
  right: 0;
}

.assistant-resume-empty,
.assistant-picker-empty {
  margin: 0;
  padding: 8px 10px;
  font-size: 12px;
  color: #94a3b8;
}

.assistant-resume-item,
.assistant-picker-item {
  display: block;
  width: 100%;
  text-align: left;
  border: none;
  background: transparent;
  color: #334155;
  font-size: 12px;
  padding: 7px 10px;
  border-radius: 7px;
  cursor: pointer;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.assistant-resume-item:hover,
.assistant-picker-item:hover {
  background: #eef6fa;
  color: #1d4ed8;
}

.assistant-favorite-job-dropdown {
  right: auto;
  left: 0;
  width: 260px;
  min-width: 0;
  max-width: calc(100vw - 48px);
}

.assistant-favorite-job-item {
  display: flex;
  flex-direction: column;
  gap: 2px;
}

.assistant-favorite-job-item small {
  color: #94a3b8;
  font-size: 11px;
}

.assistant-input {
  width: 100%;
  box-sizing: border-box;
  height: 48px; /* 初始约两行：2 × 20px 行高 + 8px 纵向 padding */
  min-height: 48px;
  max-height: 108px; /* 上限五行 */
  border: none;
  outline: none;
  resize: none;
  overflow-y: auto;
  font-size: 12px;
  line-height: 20px;
  padding: 4px 2px;
  color: #0f172a;
  background: transparent;
}

.assistant-send {
  align-self: flex-end;
  border: 1px solid #bfdbfe;
  border-radius: 9999px;
  background: #eff6ff;
  color: #1d4ed8;
  font-size: 12px;
  padding: 5px 12px;
  display: inline-flex;
  align-items: center;
  gap: 6px;
}

.trace-panel {
  margin-bottom: 8px;
  width: 100%;
  border: 1px solid #e2e8f0;
  background: #f8fafc;
  border-radius: 10px;
  padding: 7px 9px;
}

.trace-head {
  width: 100%;
  border: none;
  background: transparent;
  padding: 0;
  display: flex;
  align-items: center;
  justify-content: flex-start;
  gap: 6px;
  cursor: pointer;
}

.trace-title {
  font-size: 11px;
  color: #0f172a;
  font-weight: 600;
}

.trace-collapse-indicator {
  font-size: 11px;
  color: #64748b;
}

.trace-state {
  font-size: 11px;
  color: #64748b;
}

.trace-collapsed {
  margin-top: 4px;
  border: 1px dashed #cbd5e1;
  border-radius: 8px;
  background: #ffffff;
  min-height: 24px;
  padding: 4px 7px;
  display: flex;
  align-items: center;
  justify-content: flex-start;
  gap: 8px;
}

.trace-collapsed.is-running {
  background: transparent;
}

.trace-collapsed-text {
  font-size: 11px;
  color: #334155;
}

.trace-loading-dots {
  display: inline-flex;
  align-items: center;
  gap: 3px;
}

.trace-loading-dots i {
  width: 4px;
  height: 4px;
  border-radius: 50%;
  background: #2563eb;
  opacity: 0.35;
  animation: traceDotPulse 1.2s ease-in-out infinite;
}

.trace-loading-dots i:nth-child(2) {
  animation-delay: 0.2s;
}

.trace-loading-dots i:nth-child(3) {
  animation-delay: 0.4s;
}

.trace-task-list {
  margin-top: 6px;
  display: flex;
  flex-wrap: wrap;
  gap: 5px;
}

.trace-section {
  margin-top: 7px;
}

.trace-section-title {
  margin: 0 0 5px;
  font-size: 10px;
  font-weight: 700;
  color: #1e3a8a;
}

.trace-task-chip {
  font-size: 10px;
  border-radius: 9999px;
  padding: 2px 7px;
  border: 1px solid #cbd5e1;
  color: #475569;
  background: #fff;
  display: inline-flex;
  align-items: center;
  gap: 5px;
}

.trace-chip-icon {
  width: 12px;
  height: 12px;
  object-fit: contain;
  flex: 0 0 auto;
}

.trace-task-pending {
  color: #64748b;
  border-color: #cbd5e1;
}

.trace-task-running {
  color: #1d4ed8;
  border-color: #93c5fd;
  background: #eff6ff;
}

.trace-task-done {
  color: #166534;
  border-color: #86efac;
  background: #f0fdf4;
}

.trace-task-failed {
  color: #b91c1c;
  border-color: #fca5a5;
  background: #fef2f2;
}

.trace-steps {
  margin-top: 7px;
  display: grid;
  gap: 6px;
}

.trace-step {
  border-radius: 8px;
  border: 1px solid #e2e8f0;
  background: #ffffff;
  padding: 6px 8px;
  position: relative;
}

.trace-step-tool-run {
  border-style: dashed;
}

.trace-step-title-row {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 8px;
}

.trace-step-title-main {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  min-width: 0;
}

.trace-type-chip {
  border-radius: 9999px;
  padding: 2px 6px;
  display: inline-flex;
  align-items: center;
  gap: 4px;
  font-size: 10px;
  color: #475569;
  border: 1px solid #dbeafe;
  background: #f8fafc;
}

.trace-type-thought {
  background: #f8fbff;
  border-color: #dbeafe;
}

.trace-type-tool {
  background: #f8fcfb;
  border-color: #d1fae5;
}

.trace-type-task {
  background: #fffbf5;
  border-color: #fde68a;
}

.trace-type-plan {
  background: #faf7ff;
  border-color: #e9d5ff;
}

.trace-step-title {
  font-size: 11px;
  font-weight: 600;
  color: #0f172a;
}

.trace-step-status {
  font-size: 10px;
  color: #64748b;
  display: inline-flex;
  align-items: center;
  gap: 4px;
  white-space: nowrap;
}

.trace-status-icon {
  font-size: 12px;
}

.trace-step-detail,
.trace-step-tool,
.trace-step-sub {
  margin-top: 3px;
  font-size: 11px;
  color: #475569;
  line-height: 1.45;
  white-space: pre-line;
}

.trace-step-running {
  border-color: #dbeafe;
  background: #f8fbff;
}

.trace-step-done {
  border-color: #d1fae5;
  background: #f8fcfb;
}

.trace-step-failed {
  border-color: #fecaca;
  background: #fff7f7;
}

.markdown-body :deep(p) {
  margin: 0;
}

.markdown-body {
  line-height: 1.65;
  word-break: break-word;
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
  font-size: 18px;
}

.markdown-body :deep(h2) {
  font-size: 16px;
}

.markdown-body :deep(h3) {
  font-size: 14px;
}

.markdown-body :deep(h4) {
  font-size: 13px;
}

.markdown-body :deep(h1 + *),
.markdown-body :deep(h2 + *),
.markdown-body :deep(h3 + *),
.markdown-body :deep(h4 + *) {
  margin-top: 5px;
}

.markdown-body :deep(p + p) {
  margin-top: 5px;
}

.markdown-body :deep(code) {
  padding: 1px 5px;
  border-radius: 6px;
  background: #f1f5f9;
  color: #0f172a;
  font-size: 11px;
}

.markdown-body :deep(pre) {
  margin-top: 5px;
  padding: 8px;
  border-radius: 8px;
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
  margin: 5px 0 0;
  padding-left: 16px;
}

.markdown-body :deep(ul) {
  list-style: disc;
}

.markdown-body :deep(ol) {
  list-style: decimal;
}

.markdown-body :deep(li) {
  margin: 2px 0;
}

.markdown-body :deep(.md-table-scroll) {
  margin-top: 6px;
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
  font-size: 12px;
}

.markdown-body :deep(th),
.markdown-body :deep(td) {
  border: 1px solid #dbe5f0;
  padding: 5px 6px;
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
  padding: 6px 8px;
  border-left: 3px solid #93c5fd;
  background: #f8fbff;
  color: #334155;
}

@media (max-width: 768px) {
  .markdown-body :deep(h1) {
    font-size: 16px;
  }

  .markdown-body :deep(h2) {
    font-size: 15px;
  }

  .markdown-body :deep(h3) {
    font-size: 14px;
  }

  .markdown-body :deep(h4) {
    font-size: 13px;
  }

  .markdown-body :deep(pre) {
    padding: 7px;
  }

  .markdown-body :deep(ul),
  .markdown-body :deep(ol) {
    padding-left: 15px;
  }

  .markdown-body :deep(th),
  .markdown-body :deep(td) {
    padding: 4px 6px;
    white-space: nowrap;
  }
}

.assistant-float-enter-active,
.assistant-float-leave-active {
  transition: opacity 0.24s ease, transform 0.24s ease;
}

.assistant-float-enter-from,
.assistant-float-leave-to {
  opacity: 0;
  transform: translateY(12px);
}

@keyframes traceDotPulse {
  0%,
  80%,
  100% {
    transform: scale(0.85);
    opacity: 0.35;
  }
  40% {
    transform: scale(1);
    opacity: 1;
  }
}
</style>
