<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import MarkdownIt from 'markdown-it'
import DOMPurify from 'dompurify'
import {
  Star,
  Top,
  EditPen,
  Delete,
  Microphone,
  Connection,
  Refresh,
  DocumentCopy,
  Promotion,
  Position,
  MoreFilled,
  ArrowRight,
  ArrowDown,
  CircleCheckFilled,
  WarningFilled,
  CircleCloseFilled,
  Loading,
  Close,
} from '@element-plus/icons-vue'
import { isSuccessCode } from '../services/http'
import {
  controlHomeAgentTask,
  createHomeAgentTask,
  createHomeSession,
  createHomeSessionMessage,
  deleteHomeSession,
  getHomeAgentRuntimeOverview,
  getHomeAgentTaskArtifacts,
  getHomeOverview,
  getHomePublicOverview,
  getHomeSessionMessages,
  listHomeSessions,
  regenerateHomeSessionMessage,
  submitHomeMessageApproval,
  type HomeAgentRuntimeTaskControlAction,
  updateHomeSession,
  type HomeAgentRuntimeOverviewResult,
  type HomeAgentRuntimePreset,
  type HomeAgentRuntimeTaskSummary,
  type HomeMessage,
  type HomeAgentTrace,
  type HomeOverviewResult,
  type HomePublicOverviewResult,
  type HomeSession,
} from '../services/home'
import { polishCareerReport } from '../services/careerReport'
import {
  createParseProfileJob,
  getStudentProfile,
  getParseProfileJobStatus,
  getProfileAnalyzeJobStatus,
  saveStudentProfile,
  type AbilityScores,
  type ParseProfileJobCreateResult,
  type ParseProfileJobStatusResult,
  type ProfileAnalyzeJobStatusResult,
  type ProfileFormData,
  type SaveProfileResult,
} from '../services/studentProfile'
import { getToken } from '../utils/auth'
import {
  HOME_ASSISTANT_SESSION_REFRESH_EVENT,
  TASK_ORCHESTRATOR_STREAM_EVENT,
  emitJobRecommendationApply,
  openTaskOrchestrator,
  stashPendingJobRecommendationApply,
  type HomeAssistantSessionRefreshPayload,
  type TaskOrchestratorStreamPayload,
} from '../utils/globalAssistant'
import {
  getTaskTranscriptMessages,
  isTaskSession,
  mergeTaskTranscriptMessages,
} from '../utils/taskSession'
import HomeInterestSurveyBubble from '../components/HomeInterestSurveyBubble.vue'
import AssistantAbilityRadarCard from '../components/AssistantAbilityRadarCard.vue'
import AssistantJobGapCard from '../components/AssistantJobGapCard.vue'
import usersServedImage from '../assets/usersServed.png'
import pathsGeneratedImage from '../assets/pathsGenerated.png'
import reportsCompletedImage from '../assets/reportsCompleted.png'
import helpMatchedImage from '../assets/helpMatched.png'
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
    intent?: string
    reportId?: string
    scope?: Record<string, unknown> | null
    keyword?: string
    autoSelect?: boolean
    recommendedJobId?: string
  }
}

const router = useRouter()
const route = useRoute()
const loading = ref(false)
const sending = ref(false)
const parsingResume = ref(false)
const deletingSessionId = ref('')
const regeneratingMessageId = ref('')
const updatingSessionId = ref('')
const activeSessionId = ref('')
const draft = ref('')
const editingUserMessageId = ref('')
const editingUserMessageDraft = ref('')
const overview = ref<HomeOverviewResult | null>(null)
const publicOverview = ref<HomePublicOverviewResult | null>(null)
const sessions = ref<HomeSession[]>([])
const messages = ref<HomeMessage[]>([])
const chatScrollRef = ref<HTMLDivElement>()
const shouldStickToBottom = ref(true)
const inputRef = ref<HTMLTextAreaElement>()
const fileInputRef = ref<HTMLInputElement>()
const eventSourceRef = ref<EventSource | null>(null)
const selectedFiles = ref<File[]>([])
const traceExpandedByMessageId = ref<Record<string, boolean>>({})
const pendingApprovalsByMessageId = ref<Record<string, HomeMessageApprovalState>>({})
const approvalSubmittingByMessageId = ref<Record<string, boolean>>({})
const approvalAutoExecutedIds = new Set<string>()
const autoExecutedResumeActionMessageIds = new Set<string>()
const pendingResumeFileByMessageId = new Map<string, File>()
const syntheticFilePrompt = '请帮我处理这个文件'
const agentRuntimeOverview = ref<HomeAgentRuntimeOverviewResult | null>(null)
const agentRuntimeLoading = ref(false)
const creatingAgentTaskPresetId = ref('')
const controllingAgentTaskId = ref('')
const agentGoalDraft = ref('')
const selectedAgentPresetId = ref('')
const agentArtifactDialogVisible = ref(false)
const agentArtifactDialogTitle = ref('任务产物预览')
const agentArtifactDialogContent = ref('')
const agentArtifactDialogLoading = ref(false)
const injectedTaskArtifactIds = new Set<string>()
let agentRuntimePollTimer: ReturnType<typeof window.setInterval> | null = null
const markdownRenderer = new MarkdownIt({
  html: false,
  linkify: true,
  breaks: true,
})
markdownRenderer.enable(['table', 'strikethrough'])
markdownRenderer.renderer.rules.table_open = () => '<div class="md-table-scroll"><table>'
markdownRenderer.renderer.rules.table_close = () => '</table></div>'

const staticModules = [
  {
    key: 'studentProfile',
    title: '能力评估',
    route: '/student',
    desc: '深度解析学生简历，评估学生就业能力',
  },
  {
    key: 'jobExplore',
    title: '岗位探索',
    route: '/jobs',
    desc: '聚合岗位趋势要求，快速定位目标方向',
  },
  {
    key: 'careerPlan',
    title: '职业规划',
    route: '/match',
    desc: '生成人岗匹配结果，规划职业成长路径',
  },
  {
    key: 'careerReport',
    title: '生涯报告',
    route: '/report',
    desc: '整合关键分析结论，润色导出计划报告',
  },
]

const hasConversation = computed(() => messages.value.some(item => item.role === 'user'))
const latestAssistantMessageId = computed(() => {
  for (let index = messages.value.length - 1; index >= 0; index -= 1) {
    if (messages.value[index]?.role === 'assistant') {
      return String(messages.value[index].messageId || '')
    }
  }
  return ''
})
const quickPrompts = computed(() => overview.value?.assistantQuickPrompts || [])
const welcomeTitle = computed(() => overview.value?.greeting.title || '有什么我能帮你的吗？')
const welcomeSubtitle = computed(() => overview.value?.greeting.subtitle || '')
const agentRuntimePresets = computed(() => agentRuntimeOverview.value?.presets || [])
const agentRuntimeLatestTasks = computed(() => agentRuntimeOverview.value?.latestTasks || [])
const selectedAgentPreset = computed(() => {
  const presetId = String(selectedAgentPresetId.value || '').trim()
  if (presetId) {
    const target = agentRuntimePresets.value.find(item => item.presetId === presetId)
    if (target) return target
  }
  return agentRuntimePresets.value[0] || null
})

const metricCardImageMap: Record<string, string> = {
  usersServed: usersServedImage,
  pathsGenerated: pathsGeneratedImage,
  reportsCompleted: reportsCompletedImage,
  helpMatched: helpMatchedImage,
}

const metricCardRouteMap: Record<string, string> = {
  usersServed: '/student',
  pathsGenerated: '/match',
  reportsCompleted: '/report',
  helpMatched: '/match',
}

const metricCardTitleMap: Record<string, string> = {
  usersServed: '前往能力评估',
  pathsGenerated: '前往职业规划',
  reportsCompleted: '前往生涯报告',
  helpMatched: '前往职业规划',
}

function resolveMetricCardImage(key: string) {
  return metricCardImageMap[String(key || '')] || ''
}

function resolveMetricCardRoute(key: string) {
  return metricCardRouteMap[String(key || '')] || ''
}

function resolveMetricCardTitle(key: string, fallbackLabel: string) {
  return metricCardTitleMap[String(key || '')] || fallbackLabel
}

function handleMetricCardClick(key: string) {
  const targetRoute = resolveMetricCardRoute(key)
  if (!targetRoute) return
  router.push(targetRoute)
}

function isAgentRuntimeTaskActive(status: string) {
  const value = String(status || '')
  return value === 'queued' || value === 'in_progress' || value === 'paused'
}

function hasActiveAgentRuntimeTask() {
  return agentRuntimeLatestTasks.value.some(task => isAgentRuntimeTaskActive(task.status))
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
  if (value === 'completed') return 'agent-task-status-done'
  if (value === 'in_progress') return 'agent-task-status-running'
  if (value === 'queued') return 'agent-task-status-queued'
  if (value === 'paused') return 'agent-task-status-paused'
  if (value === 'rolled_back') return 'agent-task-status-rolled-back'
  if (value === 'cancelled' || value === 'failed') return 'agent-task-status-failed'
  return 'agent-task-status-queued'
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
  if (status === 'achieved') return 'agent-goal-conclusion-achieved'
  if (status === 'not_achieved') return 'agent-goal-conclusion-not-achieved'
  if (status === 'rolled_back') return 'agent-goal-conclusion-rolled-back'
  return 'agent-goal-conclusion-pending'
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

function resolveSelectedAgentPlanSteps() {
  return Array.isArray(selectedAgentPreset.value?.workflowPreview)
    ? selectedAgentPreset.value?.workflowPreview || []
    : []
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
      messageId: `hm_agent_artifact_${taskId}_${Date.now()}`,
      role: 'assistant',
      content: lines.join('\n'),
      status: 'succeeded',
      createdAt: new Date().toISOString(),
      actions: [],
      fileNames: [],
      agentTrace: null,
    })
    await scrollChatToBottom()
  } catch {
    // keep silent for polling path
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

function resolveAgentTaskProgress(task: HomeAgentRuntimeTaskSummary) {
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
      if (hasActiveAgentRuntimeTask()) {
        ensureAgentRuntimePolling()
      } else {
        stopAgentRuntimePolling()
      }
    }
  } catch (error) {
    if (!options?.silent) {
      ElMessage.error(error instanceof Error ? error.message : '任务编排概览加载失败')
    }
  } finally {
    if (!options?.silent) {
      agentRuntimeLoading.value = false
    }
  }
}

async function handleLaunchAgentTask(preset: HomeAgentRuntimePreset) {
  const presetId = String(preset?.presetId || '').trim()
  if (!presetId || creatingAgentTaskPresetId.value) return

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

  creatingAgentTaskPresetId.value = presetId
  try {
    const response = await createHomeAgentTask({
      presetId,
      prompt: goalText,
      pageContext: {
        routePath: '/',
        pageTitle: '首页',
        contextPrompt: welcomeSubtitle.value,
      },
    })
    const payload = response.data as ApiResponse<{ task: HomeAgentRuntimeTaskSummary }>
    if (!isSuccessCode(payload.code) || !payload.data?.task) {
      throw new Error(payload.msg || '任务创建失败')
    }

    messages.value.push({
      messageId: `hm_agent_goal_${Date.now()}`,
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
    await scrollChatToBottom()
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '任务启动失败')
  } finally {
    creatingAgentTaskPresetId.value = ''
  }
}

async function handleControlAgentTask(task: HomeAgentRuntimeTaskSummary, action: HomeAgentRuntimeTaskControlAction) {
  const taskId = String(task?.taskId || '').trim()
  if (!taskId || controllingAgentTaskId.value || !canControlAgentTask(task, action)) return

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

  controllingAgentTaskId.value = taskId
  try {
    const response = await controlHomeAgentTask(taskId, action)
    const payload = response.data as ApiResponse<{ task: HomeAgentRuntimeTaskSummary }>
    if (!isSuccessCode(payload.code) || !payload.data?.task) {
      throw new Error(payload.msg || '任务操作失败')
    }
    await refreshAgentRuntimeOverview({ silent: true })
    if (hasActiveAgentRuntimeTask()) {
      ensureAgentRuntimePolling()
    }
    ElMessage.success(`已执行：${controlLabel}`)
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '任务操作失败')
  } finally {
    controllingAgentTaskId.value = ''
  }
}

async function handlePreviewTaskArtifacts(task: HomeAgentRuntimeTaskSummary) {
  const taskId = String(task?.taskId || '').trim()
  if (!taskId || agentArtifactDialogLoading.value) return

  agentArtifactDialogLoading.value = true
  agentArtifactDialogTitle.value = `${task.title} · 产物预览`
  try {
    const response = await getHomeAgentTaskArtifacts(taskId)
    const payload = response.data as ApiResponse<{
      taskId: string
      status: string
      artifacts: Array<{ title: string; content: string }>
    }>
    if (!isSuccessCode(payload.code)) {
      throw new Error(payload.msg || '产物加载失败')
    }

    const artifacts = Array.isArray(payload.data?.artifacts) ? payload.data?.artifacts || [] : []
    if (!artifacts.length) {
      agentArtifactDialogContent.value = '暂无产物，请稍后查看。'
    } else {
      const markdown = artifacts.map((artifact) => `## ${artifact.title}\n\n${String(artifact.content || '').trim()}`).join('\n\n')
      agentArtifactDialogContent.value = markdown
    }
    agentArtifactDialogVisible.value = true
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '产物加载失败')
  } finally {
    agentArtifactDialogLoading.value = false
  }
}

function closeStream() {
  if (eventSourceRef.value) {
    eventSourceRef.value.close()
    eventSourceRef.value = null
  }
}

function parseSsePayload(raw: string) {
  try {
    return JSON.parse(raw) as Record<string, unknown>
  } catch {
    return null
  }
}

async function scrollChatToBottom() {
  await nextTick()
  if (!chatScrollRef.value) return
  if (!shouldStickToBottom.value) return
  chatScrollRef.value.scrollTop = chatScrollRef.value.scrollHeight
}

function forceStickToBottom() {
  shouldStickToBottom.value = true
}

function handleChatScroll() {
  const element = chatScrollRef.value
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

  // Determine effective status: prefer explicit param, otherwise try to find existing message
  let effectiveStatus = typeof status === 'string' && String(status).trim() ? String(status).trim() : ''
  if (!effectiveStatus) {
    const existing = messages.value.find(item => item.messageId === String(messageId || '').trim())
    if (existing && typeof existing.status === 'string') {
      effectiveStatus = String(existing.status || '').trim()
    }
  }

  // For task stream messages we only show result cards after the task finished
  if (isTaskStreamAssistantMessage(messageId)) {
    if (effectiveStatus !== 'succeeded') return null
    return card
  }

  // For non-task assistant messages only show cards when message is completed
  if (effectiveStatus && effectiveStatus !== 'succeeded') return null
  if (!actionList.length) return null

  const kind = String(card.kind || '').trim()
  const hasStudentRoute = actionList.some(action => String(action?.route || '').trim() === '/student')
  const hasMatchRoute = actionList.some(action => String(action?.route || '').trim() === '/match')
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

function extractApiPayload<T>(response: { data: ApiResponse<T> & { payload?: T } }) {
  const body = response.data as ApiResponse<T> & { payload?: T }
  return body.payload ?? body.data
}

function clampPollMs(value: unknown, fallback = 900) {
  const numeric = Number(value)
  const resolved = Number.isFinite(numeric) ? numeric : fallback
  return Math.max(1000, Math.min(2000, Math.round(resolved)))
}

function normalizeStringList(input: unknown) {
  if (!Array.isArray(input)) return [] as string[]
  return input.map(item => String(item || '').trim()).filter(Boolean)
}

function normalizeProfileForSave(parsedProfile?: Partial<ProfileFormData> | null) {
  const parsed = parsedProfile && typeof parsedProfile === 'object' ? parsedProfile : {}
  const basicInfoRaw = (parsed.basicInfo && typeof parsed.basicInfo === 'object' ? parsed.basicInfo : {}) as Partial<ProfileFormData['basicInfo']>
  const educationRaw = Array.isArray(parsed.education) ? parsed.education : []
  const workRaw = Array.isArray(parsed.workExperience) ? parsed.workExperience : []
  const certificatesRaw = Array.isArray(parsed.certificates) ? parsed.certificates : []

  const profile: ProfileFormData = {
    basicInfo: {
      name: String(basicInfoRaw.name || '张三'),
      gender: String(basicInfoRaw.gender || '男'),
      birthday: String(basicInfoRaw.birthday || '2003-06-01'),
      phone: String(basicInfoRaw.phone || '13800138000'),
      email: String(basicInfoRaw.email || 'zhangsan@example.com'),
      city: String(basicInfoRaw.city || '西安'),
      jobIntention: normalizeStringList(basicInfoRaw.jobIntention).length
        ? normalizeStringList(basicInfoRaw.jobIntention)
        : ['档案管理'],
    },
    education: educationRaw.length
      ? educationRaw.map(item => ({
          school: String(item?.school || ''),
          major: String(item?.major || ''),
          degree: String(item?.degree || ''),
          startDate: String(item?.startDate || ''),
          endDate: String(item?.endDate || ''),
          gpa: String(item?.gpa || ''),
        }))
      : [
          {
            school: 'XX大学',
            major: '信息管理与信息系统',
            degree: '本科',
            startDate: '2022-09',
            endDate: '2026-06',
            gpa: '3.6/4.0',
          },
        ],
    workExperience: workRaw.length
      ? workRaw.map(item => ({
          company: String(item?.company || item?.position || ''),
          role: String(item?.role || ''),
          startDate: String(item?.startDate || ''),
          endDate: String(item?.endDate || ''),
          description: String(item?.description || ''),
        }))
      : [
          {
            company: '某机构档案中心（实习）',
            role: '档案管理实习生',
            startDate: '2025-07',
            endDate: '2025-09',
            description: '参与档案分类、归档和信息录入，协助建立台账并提升检索效率。',
          },
        ],
    skills: normalizeStringList(parsed.skills).length
      ? normalizeStringList(parsed.skills)
      : ['档案分类', '文档管理', 'Excel', '流程协同'],
    certificates: certificatesRaw.length
      ? certificatesRaw.map((item) => {
          if (typeof item === 'string') {
            return {
              name: item,
              date: '',
              issuer: '',
            }
          }
          return {
            name: String(item?.name || ''),
            date: String(item?.date || ''),
            issuer: String(item?.issuer || ''),
          }
        })
      : [
          {
            name: '大学英语六级（CET-6）',
            date: '',
            issuer: '',
          },
        ],
    organizeExp: normalizeStringList(parsed.organizeExp),
    projects: normalizeStringList(parsed.projects),
    selfEvaluation: String(parsed.selfEvaluation || '').trim() || '具备较强的流程意识与执行力，能在档案管理场景中保持资料完整性、准确性和可追溯性，并持续优化协同效率。',
  }

  return profile
}

function normalizeScore(value: unknown) {
  const numeric = Number(value)
  if (!Number.isFinite(numeric)) return 0
  return Math.max(0, Math.min(100, Math.round(numeric)))
}

function buildAbilityCardFromScores(scores?: AbilityScores | null): HomeMessage['taskResultCard'] {
  if (!scores || typeof scores !== 'object') return null
  const labels: Record<keyof AbilityScores, string> = {
    professionalSkill: '专业技能',
    certificate: '证书能力',
    innovation: '创新能力',
    internalMotivation: '内驱动力',
    learning: '学习能力',
    stressTolerance: '抗压能力',
    communication: '沟通能力',
    internship: '实习能力',
    language: '语言能力',
    leadership: '领导能力',
    adaptability: '适应能力',
    execution: '执行能力',
  }
  const dimensions = Object.keys(labels) as Array<keyof AbilityScores>
  return {
    kind: 'resume_scores',
    title: '12维能力雷达评估',
    summary: '已完成简历解析与画像评估，以下为最新维度评分。',
    scores: dimensions.map((key) => ({
      dimension: labels[key],
      score: normalizeScore(scores[key]),
    })),
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
    agentTrace: trace,
    actions: nextActions,
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

function resolveSessionDisplayTitle(session: HomeSession) {
  return String(session.title || '').trim() || '未命名会话'
}

function resolveSessionPreview(session: HomeSession) {
  return session.lastMessagePreview || '暂无消息'
}

async function applyTaskTranscriptFallback(sessionId: string) {
  // 尝试从本地 transcript 恢复（不再仅限于当前 task session）
  const taskMessages = getTaskTranscriptMessages(sessionId)
  if (!taskMessages.length) return false
  console.info('[taskTranscript] 恢复本地 transcript', sessionId, taskMessages.length)
  activeSessionId.value = sessionId
  messages.value = taskMessages
  await scrollChatToBottom()
  return true
}

async function handleTaskOrchestratorStreamEvent(event: Event) {
  const payload = (event as CustomEvent<TaskOrchestratorStreamPayload>).detail
  if (!payload || typeof payload !== 'object') return

  const sessionId = String(payload.sessionId || '').trim()
  if (sessionId && payload.phase === 'start') {
    await refreshSessions().catch(() => {})
    if (activeSessionId.value !== sessionId) {
      const loaded = await loadSessionMessages(sessionId)
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

  await scrollChatToBottom()
}

async function handleHomeAssistantSessionRefreshEvent(event: Event) {
  const detail = (event as CustomEvent<HomeAssistantSessionRefreshPayload>).detail || {}
  await refreshSessions().catch(() => {})

  const sessionId = String(detail.sessionId || '').trim()
  const shouldFollowTaskSession = (detail.reason === 'task-created' || detail.reason === 'task-updated')
  if (shouldFollowTaskSession && sessionId && activeSessionId.value !== sessionId) {
    const loaded = await loadSessionMessages(sessionId)
    if (!loaded) {
      activeSessionId.value = sessionId
      messages.value = []
    }
  } else if (shouldFollowTaskSession && sessionId && activeSessionId.value === sessionId) {
    await loadSessionMessages(sessionId)
  }
}

function handleOpenTaskOrchestratorFromHome() {
  openTaskOrchestrator({
    routePath: route.path,
    pageTitle: String(route.meta.title || '首页'),
    source: 'home',
  })
}

function renderMarkdownContent(content: string) {
  const rendered = markdownRenderer.render(String(content || ''))
  return DOMPurify.sanitize(rendered)
}

function resolveTraceStepStatusLabel(status: string) {
  const value = String(status || '')
  if (value === 'succeeded' || value === 'completed') return '已完成'
  if (value === 'processing' || value === 'in_progress') return '进行中'
  if (value === 'failed') return '失败'
  return '待处理'
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

function resolveTraceStepStatusClass(status: string) {
  const value = String(status || '')
  if (value === 'succeeded' || value === 'completed') return 'trace-step-done'
  if (value === 'processing' || value === 'in_progress') return 'trace-step-running'
  if (value === 'failed') return 'trace-step-failed'
  return 'trace-step-pending'
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

function isTraceExpanded(messageId: string) {
  return Boolean(traceExpandedByMessageId.value[messageId])
}

function toggleTraceExpanded(messageId: string) {
  traceExpandedByMessageId.value = {
    ...traceExpandedByMessageId.value,
    [messageId]: !isTraceExpanded(messageId),
  }
}

function resolveTraceCollapsedText(trace: HomeAgentTrace) {
  const status = String(trace?.status || '')
  const activeStep = trace?.steps?.find(step => step.stepId === trace.activeStepId)
  if (status === 'processing') {
    return activeStep?.title || '正在思考中'
  }
  if (status === 'succeeded') {
    return '思考完成，点击展开查看完整过程'
  }
  if (status === 'failed') {
    return '思考中断，点击展开查看详情'
  }
  return '点击展开查看详情'
}

function resolveTraceElapsed(trace: HomeAgentTrace) {
  const startedAt = Date.parse(String(trace?.startedAt || ''))
  const finishedAt = Date.parse(String(trace?.finishedAt || ''))
  if (!Number.isFinite(startedAt)) return '用时未知'
  const endAt = Number.isFinite(finishedAt) ? finishedAt : Date.now()
  const seconds = Math.max(1, Math.round((endAt - startedAt) / 1000))
  return `用时${seconds}秒`
}

function isSyntheticUserFilePrompt(content: string) {
  return String(content || '').trim() === syntheticFilePrompt
}

async function handleCopyMessage(content: string) {
  const text = String(content || '').trim()
  if (!text) {
    ElMessage.warning('暂无可复制内容')
    return
  }
  try {
    await navigator.clipboard.writeText(text)
    ElMessage.success('已复制')
  } catch {
    ElMessage.error('复制失败，请手动复制')
  }
}

function getResumeFile(files: File[]) {
  const allowedExtensions = new Set(['pdf', 'jpg', 'jpeg', 'png', 'docx', 'doc'])
  return (
    files.find((file) => {
      const fileName = String(file.name || '')
      const ext = fileName.includes('.') ? fileName.slice(fileName.lastIndexOf('.') + 1).toLowerCase() : ''
      return allowedExtensions.has(ext)
    }) || null
  )
}

function triggerFilePicker() {
  fileInputRef.value?.click()
}

function handleFileChange(event: Event) {
  const input = event.target as HTMLInputElement
  const files = Array.from(input.files || [])
  selectedFiles.value = files.slice(0, 1)
}

function removeSelectedFile() {
  selectedFiles.value = []
  if (fileInputRef.value) fileInputRef.value.value = ''
}

function adjustInputHeight() {
  const textarea = inputRef.value
  if (!textarea) return
  textarea.style.height = 'auto'
  textarea.style.height = `${Math.min(140, Math.max(56, textarea.scrollHeight))}px`
}

async function refreshSessions() {
  const response = await listHomeSessions()
  const payload = response.data as ApiResponse<{ total: number; list: HomeSession[] }>
  if (isSuccessCode(payload.code) && payload.data) {
    sessions.value = Array.isArray(payload.data.list) ? payload.data.list.slice() : []
  }
}

async function loadSessionMessages(sessionId: string) {
  try {
    const response = await getHomeSessionMessages(sessionId)
    const payload = response.data as ApiResponse<{ sessionId: string; total: number; list: HomeMessage[] }>
    if (isSuccessCode(payload.code) && payload.data) {
      const normalizedList = payload.data.list.map(normalizeMessageTaskResultCard)
      // 如果本地存在任务 transcript，则合并本地记录；否则使用服务端返回
      const localTranscript = getTaskTranscriptMessages(sessionId)
      messages.value = Array.isArray(localTranscript) && localTranscript.length
        ? mergeTaskTranscriptMessages(sessionId, normalizedList)
        : normalizedList
      cancelEditUserMessage()
      activeSessionId.value = sessionId
      forceStickToBottom()
      await scrollChatToBottom()
      return true
    }
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
    } catch (error) {
      const message = String((error as { message?: string })?.message || '')
      if (message.includes('会话不存在')) {
        activeSessionId.value = ''
        messages.value = []
      } else {
        throw error
      }
    }

    if (activeSessionId.value) {
      return currentSessionId
    }
    activeSessionId.value = ''
    messages.value = []
  }

  const response = await createHomeSession()
  const payload = response.data as ApiResponse<{ sessionId: string; welcomeMessage: HomeMessage | null }>
  if (!isSuccessCode(payload.code) || !payload.data) {
    throw new Error(payload.msg || '创建会话失败')
  }
  activeSessionId.value = payload.data.sessionId
  messages.value = payload.data.welcomeMessage ? [normalizeMessageTaskResultCard(payload.data.welcomeMessage)] : []
  await refreshSessions()
  return activeSessionId.value
}

async function startSseStream(assistantMessageId: string, streamUrl: string) {
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

  source.addEventListener('delta', async (event) => {
    const payload = parseSsePayload((event as MessageEvent).data)
    if (!payload) return
    updateAssistantMessage(assistantMessageId, {
      content: String(payload.content || ''),
      status: 'processing',
    })
    await scrollChatToBottom()
  })

  source.addEventListener('task_update', async (event) => {
    const payload = parseSsePayload((event as MessageEvent).data)
    if (!payload) return
    const trace = payload?.trace as HomeAgentTrace | undefined
    if (!trace || !Array.isArray(trace.steps)) return

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
    const index = messages.value.findIndex(m => m.messageId === assistantMessageId)
    if (index < 0) return
    const current = messages.value[index]
    let content = String(current.content || '')
    // replace existing progress line if present
    if (/-\s*进度：/.test(content)) {
      content = content.replace(/-\s*进度：\[[\s\S]*?\]\s*\d+%/, `- 进度：${buildProgressBar(progress)}`)
    } else {
      content += `\n- 进度：${buildProgressBar(progress)}`
    }
    updateAssistantMessage(assistantMessageId, { content, status: 'processing' })
    await scrollChatToBottom()
  })

  source.addEventListener('done', async (event) => {
    streamFinished = true
    const payload = parseSsePayload((event as MessageEvent).data)
    const message = payload?.message as HomeMessage | undefined
    if (message) {
      updateAssistantMessage(assistantMessageId, {
        content: message.content,
        status: message.status,
        actions: message.actions || [],
        agentTrace: message.agentTrace || null,
        taskResultCard: sanitizeTaskResultCard(message.messageId, message.taskResultCard || null, message.actions || [], message.status),
      })

      const hasPendingResumeFile = pendingResumeFileByMessageId.has(assistantMessageId)
      const parseAction = Array.isArray(message.actions)
        ? message.actions.find(action => action?.type === 'navigate_and_parse_resume' || action?.intent === 'parse_resume')
        : null
      if (hasPendingResumeFile && parseAction && !autoExecutedResumeActionMessageIds.has(assistantMessageId)) {
        autoExecutedResumeActionMessageIds.add(assistantMessageId)
        await handleMessageAction(parseAction, assistantMessageId)
      }
    } else {
      updateAssistantMessage(assistantMessageId, { status: 'succeeded' })
    }
    if (regeneratingMessageId.value === assistantMessageId) {
      regeneratingMessageId.value = ''
    }
    safeCloseStream()
    await refreshSessions()
    await scrollChatToBottom()
  })

  source.addEventListener('trace', async (event) => {
    const payload = parseSsePayload((event as MessageEvent).data)
    const trace = payload?.trace as HomeAgentTrace | undefined
    if (!trace) return
    updateAssistantMessage(assistantMessageId, {
      agentTrace: trace,
      status: 'processing',
    })
    await scrollChatToBottom()
  })

  source.addEventListener('tool_call', async (event) => {
    const payload = parseSsePayload((event as MessageEvent).data)
    const trace = payload?.trace as HomeAgentTrace | undefined
    if (!trace) return
    updateAssistantMessage(assistantMessageId, {
      status: 'processing',
      agentTrace: trace,
    })
    await scrollChatToBottom()
  })

  source.addEventListener('tool_result', async (event) => {
    const payload = parseSsePayload((event as MessageEvent).data)
    const trace = payload?.trace as HomeAgentTrace | undefined
    if (!trace) return
    updateAssistantMessage(assistantMessageId, {
      status: 'processing',
      agentTrace: trace,
    })
    await scrollChatToBottom()
  })

  source.addEventListener('task_update', async (event) => {
    const payload = parseSsePayload((event as MessageEvent).data)
    const trace = payload?.trace as HomeAgentTrace | undefined
    if (!trace) return
    updateAssistantMessage(assistantMessageId, {
      status: 'processing',
      agentTrace: trace,
    })
    await scrollChatToBottom()
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
      [assistantMessageId]: {
        approvalId: String(approval.approvalId),
        title: String(approval.title || '请确认执行'),
        summary: String(approval.summary || '该操作需要先审批确认。'),
        status: 'pending',
        action: approval.action,
      },
    }
    updateAssistantMessage(assistantMessageId, { status: 'processing' })
    await scrollChatToBottom()
  })

  source.addEventListener('approval_received', async (event) => {
    const payload = parseSsePayload((event as MessageEvent).data)
    const approval = payload?.approval as {
      status?: string
      action?: HomeMessageApprovalState['action']
      approvalId?: string
    } | undefined
    const status = String(approval?.status || '').trim()
    if (!status) return

    const current = pendingApprovalsByMessageId.value[assistantMessageId]
    if (current) {
      const next = { ...pendingApprovalsByMessageId.value }
      if (status === 'approved') {
        delete next[assistantMessageId]
      } else {
        next[assistantMessageId] = {
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
      await handleMessageAction(approval.action, assistantMessageId, { skipApprovalPrompt: true })
    }
  })

  source.onerror = () => {
    if (streamFinished || streamClosedByClient) return
    if (source.readyState === EventSource.CONNECTING) return
    if (eventSourceRef.value !== source) return
    updateAssistantMessage(assistantMessageId, {
      status: 'failed',
      content: '连接中断，请重试发送消息。',
    })
    if (regeneratingMessageId.value === assistantMessageId) {
      regeneratingMessageId.value = ''
    }
    safeCloseStream()
  }
}

async function sendMessage(contentText: string) {
  const content = contentText.trim()
  if ((!content && !selectedFiles.value.length) || sending.value) return

  sending.value = true
  try {
    const sessionId = await ensureSession()
    const sentFileNames = selectedFiles.value.map(file => file.name)
    const resumeFile = getResumeFile(selectedFiles.value)
    const response = await createHomeSessionMessage(sessionId, content || syntheticFilePrompt, {
      hasResumeFile: Boolean(resumeFile),
      fileNames: sentFileNames,
      pageContext: {
        routePath: '/',
        pageTitle: '首页',
      },
      executionMode: 'auto',
      taskPolicy: {
        allowToolCall: true,
        maxSteps: 8,
        timeoutMs: 120000,
      },
    })

    const payload = response.data as ApiResponse<{
      sessionId: string
      userMessage: HomeMessage
      assistantMessage: HomeMessage
      stream: { protocol: 'sse'; url: string }
    }>

    if (!isSuccessCode(payload.code) || !payload.data) {
      throw new Error(payload.msg || '消息发送失败')
    }

    if (resumeFile) {
      pendingResumeFileByMessageId.set(payload.data.assistantMessage.messageId, resumeFile)
    }

    messages.value.push({
      ...payload.data.userMessage,
      fileNames: payload.data.userMessage.fileNames || sentFileNames,
    })
    // If a stream URL is provided, defer showing heavy result cards until the stream 'done' event.
    const assistantInitial = payload.data.stream && payload.data.stream.url
      ? { ...payload.data.assistantMessage, taskResultCard: null }
      : payload.data.assistantMessage
    messages.value.push(normalizeMessageTaskResultCard(assistantInitial))
    draft.value = ''
    removeSelectedFile()
    adjustInputHeight()
    forceStickToBottom()
    await scrollChatToBottom()
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

async function handleSend() {
  await sendMessage(draft.value)
}

async function handleQuickPrompt(prompt: string) {
  draft.value = prompt
  await sendMessage(prompt)
}

async function handleCreateNewSession() {
  closeStream()
  activeSessionId.value = ''
  messages.value = []
  draft.value = ''
  removeSelectedFile()
  await nextTick()
}

async function handleDeleteSession(sessionId: string) {
  if (!sessionId || deletingSessionId.value) return
  try {
    await ElMessageBox.confirm('确认删除这条历史对话吗？删除后不可恢复。', '删除会话', {
      confirmButtonText: '确认删除',
      cancelButtonText: '取消',
      type: 'warning',
    })
  } catch {
    return
  }

  deletingSessionId.value = sessionId
  try {
    const response = await deleteHomeSession(sessionId)
    const payload = response.data as ApiResponse<{ sessionId: string; deleted: boolean }>
    if (!isSuccessCode(payload.code)) {
      throw new Error(payload.msg || '删除会话失败')
    }

    await refreshSessions()
    if (activeSessionId.value === sessionId) {
      activeSessionId.value = ''
      messages.value = []
    }
    ElMessage.success('会话已删除')
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '删除失败')
  } finally {
    deletingSessionId.value = ''
  }
}

async function handleUpdateSession(sessionId: string, patch: { pinned?: boolean; favorited?: boolean; title?: string }, successMsg: string) {
  if (!sessionId || updatingSessionId.value) return
  updatingSessionId.value = sessionId
  try {
    const response = await updateHomeSession(sessionId, patch)
    const payload = response.data as ApiResponse<{
      sessionId: string
      title: string
      pinned: boolean
      favorited: boolean
      updatedAt: string
    }>
    if (!isSuccessCode(payload.code)) {
      throw new Error(payload.msg || '会话更新失败')
    }
    await refreshSessions()
    ElMessage.success(successMsg)
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '更新失败')
  } finally {
    updatingSessionId.value = ''
  }
}

async function handleRenameSession(session: HomeSession) {
  let title = ''
  try {
    const result = await ElMessageBox.prompt('请输入新的会话名称（最多60字）', '重命名会话', {
      confirmButtonText: '保存',
      cancelButtonText: '取消',
      inputValue: session.title,
      inputPlaceholder: '请输入会话名称',
      inputValidator: (value: string) => {
        const normalized = String(value || '').trim()
        if (!normalized) return '会话名称不能为空'
        if (normalized.length > 60) return '会话名称不能超过60字'
        return true
      },
    })
    title = String((result as { value?: string })?.value || '').trim().slice(0, 60)
  } catch {
    return
  }

  if (!title) {
    ElMessage.warning('会话名称不能为空')
    return
  }
  await handleUpdateSession(session.sessionId, { title }, '会话已重命名')
}

function handleSessionDropdownCommand(session: HomeSession, command: string | number | object) {
  return handleSessionCommand(session, String(command))
}

async function handleSessionCommand(session: HomeSession, command: string) {
  if (command === 'pin') {
    await handleUpdateSession(session.sessionId, { pinned: !session.pinned }, session.pinned ? '已取消置顶' : '已置顶')
    return
  }
  if (command === 'favorite') {
    await handleUpdateSession(session.sessionId, { favorited: !session.favorited }, session.favorited ? '已取消收藏' : '已收藏')
    return
  }
  if (command === 'rename') {
    await handleRenameSession(session)
    return
  }
  if (command === 'delete') {
    await handleDeleteSession(session.sessionId)
  }
}

async function handleRegenerateReply(messageId: string) {
  if (!activeSessionId.value || regeneratingMessageId.value || sending.value) return
  if (latestAssistantMessageId.value !== String(messageId || '')) return

  regeneratingMessageId.value = messageId
  try {
    const response = await regenerateHomeSessionMessage(activeSessionId.value, messageId)
    const payload = response.data as ApiResponse<{
      sessionId: string
      assistantMessage: HomeMessage
      prunedAfterCount?: number
      stream: { protocol: 'sse'; url: string }
    }>

    if (!isSuccessCode(payload.code) || !payload.data) {
      throw new Error(payload.msg || '重新生成失败')
    }

    await loadSessionMessages(activeSessionId.value)
    regeneratingMessageId.value = payload.data.assistantMessage.messageId
    await scrollChatToBottom()
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
      sessionId: string
      assistantMessage: HomeMessage
      prunedAfterCount?: number
      stream: { protocol: 'sse'; url: string }
    }>
    if (!isSuccessCode(payload.code) || !payload.data?.assistantMessage?.messageId) {
      throw new Error(payload.msg || '基于修改消息重新生成失败')
    }

    await loadSessionMessages(activeSessionId.value)
    cancelEditUserMessage()
    regeneratingMessageId.value = payload.data.assistantMessage.messageId
    await scrollChatToBottom()
    await startSseStream(payload.data.assistantMessage.messageId, payload.data.stream.url)
  } catch (error) {
    regeneratingMessageId.value = ''
    ElMessage.error(error instanceof Error ? error.message : '重新生成失败')
  }
}

async function runResumeParseThenNavigate(file: File, messageId: string) {
  parsingResume.value = true
  try {
    const createResponse = await createParseProfileJob(file)
    const createPayload = extractApiPayload<ParseProfileJobCreateResult>(createResponse as { data: ApiResponse<ParseProfileJobCreateResult> & { payload?: ParseProfileJobCreateResult } })
    const parseJobId = String(createPayload?.parseJobId || '').trim()

    let parsedProfile: Partial<ProfileFormData> | null = null
    if (parseJobId) {
      for (let index = 0; index < 15; index += 1) {
        const statusResponse = await getParseProfileJobStatus(parseJobId)
        const statusPayload = extractApiPayload<ParseProfileJobStatusResult>(statusResponse as { data: ApiResponse<ParseProfileJobStatusResult> & { payload?: ParseProfileJobStatusResult } })
        const status = String(statusPayload?.status || '').trim()
        if (status === 'succeeded') {
          parsedProfile = statusPayload?.result?.parsedProfile || null
          break
        }
        if (status === 'failed') {
          throw new Error('简历解析失败，请稍后重试')
        }
        await new Promise(resolve => window.setTimeout(resolve, clampPollMs(statusPayload?.pollAfterMs, 800)))
      }
    }

    const saveResponse = await saveStudentProfile(normalizeProfileForSave(parsedProfile), true)
    const savePayload = extractApiPayload<SaveProfileResult>(saveResponse as { data: ApiResponse<SaveProfileResult> & { payload?: SaveProfileResult } })
    const analyzeJobId = String(savePayload?.analyzeJobId || '').trim()

    if (analyzeJobId) {
      for (let index = 0; index < 20; index += 1) {
        const statusResponse = await getProfileAnalyzeJobStatus(analyzeJobId)
        const statusPayload = extractApiPayload<ProfileAnalyzeJobStatusResult>(statusResponse as { data: ApiResponse<ProfileAnalyzeJobStatusResult> & { payload?: ProfileAnalyzeJobStatusResult } })
        const status = String(statusPayload?.status || '').trim()
        if (status === 'succeeded') break
        if (status === 'failed') {
          throw new Error('能力评估失败，请稍后重试')
        }
        await new Promise(resolve => window.setTimeout(resolve, clampPollMs(statusPayload?.pollAfterMs, 900)))
      }
    }

    const profileResponse = await getStudentProfile()
    const profilePayload = extractApiPayload<{ scores?: { abilityScores?: AbilityScores } }>(profileResponse as { data: ApiResponse<{ scores?: { abilityScores?: AbilityScores } }> & { payload?: { scores?: { abilityScores?: AbilityScores } } } })
    const abilityCard = buildAbilityCardFromScores(profilePayload?.scores?.abilityScores || null)
    if (abilityCard) {
      updateAssistantMessage(messageId, {
        taskResultCard: abilityCard,
      })
    }

    await router.push('/student')
    ElMessage.success('已完成简历解析与能力评估，正在为你打开能力评估详情。')
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '简历解析失败，请稍后重试')
  } finally {
    parsingResume.value = false
  }
}

async function handleMessageAction(
  action: {
    type: 'navigate' | 'navigate_and_parse_resume' | 'one_click_polish' | 'apply_recommended_job' | string
    route: string
    intent?: string
    reportId?: string
    scope?: Record<string, unknown> | null
    keyword?: string
    autoSelect?: boolean
    recommendedJobId?: string
  },
  messageId: string,
  options?: {
    skipApprovalPrompt?: boolean
  },
) {
  if (action.type === 'one_click_polish') {
    if (!options?.skipApprovalPrompt) {
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
        await scrollChatToBottom()
      }
      return
    }

    const reportId = String(action.reportId || '').trim()
    if (!reportId) {
      ElMessage.warning('缺少报告ID，无法执行一键润色')
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
      const payload = response.data as ApiResponse<{
        polishJobId: string
        reportId: string
        status: 'processing'
        pollAfterMs?: number
      }>
      if (!isSuccessCode(payload.code) || !payload.data?.polishJobId) {
        throw new Error(payload.msg || '创建一键润色任务失败')
      }

      localStorage.setItem(
        'career_report_pending_polish_job',
        JSON.stringify({
          polishJobId: payload.data.polishJobId,
          reportId,
          createdAt: Date.now(),
          source: 'home-assistant-auto-fill',
        }),
      )
      ElMessage.success('已发起一键润色，任务处理中。润色完成后可点击“立即查看”')
    } catch (error) {
      ElMessage.error(error instanceof Error ? error.message : '一键润色失败')
    }
    return
  }

  if (action.type === 'navigate_and_parse_resume' || action.intent === 'parse_resume') {
    const resumeFile = pendingResumeFileByMessageId.get(messageId) || null
    pendingResumeFileByMessageId.delete(messageId)
    if (!resumeFile) {
      ElMessage.warning('未检测到可解析的简历文件，将先跳转能力评估页面')
      await router.push('/student')
      return
    }
    await runResumeParseThenNavigate(resumeFile, messageId)
    return
  }

  if (action.type === 'apply_recommended_job') {
    const keyword = String(action.keyword || '').trim() || '前端开发'
    const payload = {
      keyword,
      autoSelect: action.autoSelect !== false,
      recommendedJobId: String(action.recommendedJobId || '').trim() || undefined,
      source: 'home-assistant',
    }

    if (route.path === '/jobs') {
      emitJobRecommendationApply(payload)
      ElMessage.success(`已按“${keyword}”应用推荐岗位`)
      return
    }

    stashPendingJobRecommendationApply(payload)
    await router.push('/jobs')
    ElMessage.success('已跳转岗位探索并准备应用推荐岗位')
    return
  }

  await router.push(action.route)
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

async function initPage() {
  loading.value = true
  try {
    const [overviewRes, publicRes, runtimeRes] = await Promise.all([
      getHomeOverview(),
      getHomePublicOverview(),
      getHomeAgentRuntimeOverview().catch(() => null),
    ])

    const overviewPayload = overviewRes.data as ApiResponse<HomeOverviewResult>
    if (isSuccessCode(overviewPayload.code) && overviewPayload.data) {
      overview.value = overviewPayload.data
    }

    const publicPayload = publicRes.data as ApiResponse<HomePublicOverviewResult>
    if (isSuccessCode(publicPayload.code) && publicPayload.data) {
      publicOverview.value = publicPayload.data
    }

    if (runtimeRes) {
      const runtimePayload = runtimeRes.data as ApiResponse<HomeAgentRuntimeOverviewResult>
      if (isSuccessCode(runtimePayload.code) && runtimePayload.data) {
        agentRuntimeOverview.value = runtimePayload.data
        ensureSelectedAgentPreset()
        await maybeInjectCompletedTaskArtifacts(runtimePayload.data.latestTasks || [])
      }
    }

    await refreshSessions()
    if (hasActiveAgentRuntimeTask()) {
      ensureAgentRuntimePolling()
    }
  } finally {
    loading.value = false
  }
}

// 保留一组任务运行时函数以兼容后续回归调试入口，避免严格 noUnusedLocals 下的构建失败。
void [
  resolveAgentRuntimeTaskStatusLabel,
  resolveAgentRuntimeTaskStatusClass,
  resolveAgentGoalConclusionClass,
  resolveSelectedAgentPlanSteps,
  handleSelectAgentPreset,
  resolveAgentTaskProgress,
  handleLaunchAgentTask,
  handleControlAgentTask,
  handlePreviewTaskArtifacts,
  handleOpenTaskOrchestratorFromHome,
]

onMounted(async () => {
  window.addEventListener(TASK_ORCHESTRATOR_STREAM_EVENT, handleTaskOrchestratorStreamEvent as EventListener)
  window.addEventListener(HOME_ASSISTANT_SESSION_REFRESH_EVENT, handleHomeAssistantSessionRefreshEvent as EventListener)
  await initPage()
  const sessionId = String(route.query.sessionId || '').trim()
  if (sessionId) {
    const exists = sessions.value.some(item => item.sessionId === sessionId)
    if (exists) {
      await loadSessionMessages(sessionId)
    }
  }
  adjustInputHeight()
})

onBeforeUnmount(() => {
  closeStream()
  stopAgentRuntimePolling()
  window.removeEventListener(TASK_ORCHESTRATOR_STREAM_EVENT, handleTaskOrchestratorStreamEvent as EventListener)
  window.removeEventListener(HOME_ASSISTANT_SESSION_REFRESH_EVENT, handleHomeAssistantSessionRefreshEvent as EventListener)
})
</script>

<template>
  <section class="home-layout" v-loading="loading">
    <aside class="left-dock">
      <div class="brand-card">
        <div class="avatar-slot">
          <img :src="aiAvatarImage" alt="小光助手" class="avatar-image" />
        </div>
        <div>
          <p class="brand-title">职业规划智能体</p>
          <p class="brand-sub">小光助手</p>
        </div>
      </div>

      <button type="button" class="new-session-btn" @click="handleCreateNewSession">+ 新对话</button>

      <div class="left-card">
        <p class="left-title">模块导航</p>
        <button
          v-for="item in staticModules"
          :key="item.key"
          class="side-link"
          type="button"
          @click="router.push(item.route)"
        >
          <span class="side-link-title">{{ item.title }}</span>
          <span class="side-link-desc">{{ item.desc }}</span>
        </button>
      </div>

      <div class="left-card">
        <p class="left-title">历史对话</p>
        <div class="history-list">
          <div
            v-for="item in sessions"
            :key="item.sessionId"
            class="history-item"
            :class="[activeSessionId === item.sessionId ? 'is-active' : '']"
          >
            <button
              type="button"
              class="history-main"
              @click="loadSessionMessages(item.sessionId)"
            >
              <p class="line-clamp-1 text-sm font-medium">
                <span v-if="item.pinned" class="session-flag">📌</span>
                <span v-else-if="item.favorited" class="session-flag">⭐</span>
                {{ resolveSessionDisplayTitle(item) }}
              </p>
              <p class="line-clamp-1 text-xs text-slate-500">{{ resolveSessionPreview(item) }}</p>
            </button>

            <el-dropdown
              trigger="click"
              placement="bottom-end"
              @command="handleSessionDropdownCommand(item, $event)"
            >
              <button type="button" class="history-more" :disabled="updatingSessionId === item.sessionId || deletingSessionId === item.sessionId">⋯</button>
              <template #dropdown>
                <el-dropdown-menu>
                  <el-dropdown-item command="pin">
                    <el-icon><Top /></el-icon>
                    <span>{{ item.pinned ? '取消置顶' : '置顶' }}</span>
                  </el-dropdown-item>
                  <el-dropdown-item command="favorite">
                    <el-icon><Star /></el-icon>
                    <span>{{ item.favorited ? '取消收藏' : '收藏' }}</span>
                  </el-dropdown-item>
                  <el-dropdown-item command="rename">
                    <el-icon><EditPen /></el-icon>
                    <span>重命名</span>
                  </el-dropdown-item>
                  <el-dropdown-item command="delete" class="dropdown-delete">
                    <el-icon><Delete /></el-icon>
                    <span>删除</span>
                  </el-dropdown-item>
                </el-dropdown-menu>
              </template>
            </el-dropdown>
          </div>
          <p v-if="!sessions.length" class="text-xs text-slate-400">暂无历史对话</p>
        </div>
      </div>
    </aside>

    <main class="chat-stage">
      <div class="chat-shell">
        <section v-if="!hasConversation" class="hero-stage">
          <transition name="welcome-fade" appear>
            <div class="welcome-zone">
              <h2 class="welcome-title">{{ welcomeTitle }}</h2>
              <p v-if="welcomeSubtitle" class="welcome-subtitle">{{ welcomeSubtitle }}</p>
              <div class="prompt-tags">
                <button v-for="prompt in quickPrompts" :key="prompt" type="button" class="prompt-chip" @click="handleQuickPrompt(prompt)">
                  {{ prompt }}
                </button>
              </div>

            </div>
          </transition>

          <transition name="dashboard-fade">
            <div v-if="publicOverview" class="public-cards public-cards-overlay">
              <button
                v-for="item in publicOverview.overviewMetrics"
                :key="item.key"
                type="button"
                class="public-card public-card-btn"
                :title="resolveMetricCardRoute(item.key) ? resolveMetricCardTitle(item.key, item.label) : item.label"
                @click="handleMetricCardClick(item.key)"
              >
                <img
                  v-if="resolveMetricCardImage(item.key)"
                  :src="resolveMetricCardImage(item.key)"
                  :alt="item.label"
                  class="public-card-bg"
                />
                <div class="public-card-content">
                  <p class="text-xs text-slate-500">{{ item.label }}</p>
                  <p class="mt-1 text-xl font-semibold text-slate-900">{{ item.value }}<span class="ml-1 text-xs text-slate-500">{{ item.unit }}</span></p>
                  <p class="mt-1 text-xs text-emerald-600">{{ item.trend.period }} {{ item.trend.delta }}%</p>
                </div>
              </button>
            </div>
          </transition>
        </section>

        <section v-else ref="chatScrollRef" class="chat-list" @scroll.passive="handleChatScroll">
          <div
            v-for="item in messages"
            :key="item.messageId"
            class="message-row"
            :class="item.role === 'user' ? 'is-user' : 'is-ai'"
          >
            <div v-if="item.role === 'user'" class="user-bubble">
              <div class="user-message-stack">
                <div
                  v-if="item.fileNames?.length"
                  class="user-file-card"
                >
                  <span class="file-card-title">文件</span>
                  <span
                    v-for="(name, index) in item.fileNames"
                    :key="`${item.messageId}-file-${index}`"
                    class="file-card-name"
                  >
                    <el-icon><Connection /></el-icon>
                    {{ name }}
                  </span>
                </div>

                <template v-if="editingUserMessageId === item.messageId">
                  <div class="user-inline-editor">
                    <button type="button" class="user-inline-cancel" @click="cancelEditUserMessage" aria-label="取消编辑">
                      <el-icon><Close /></el-icon>
                    </button>
                    <input
                      v-model="editingUserMessageDraft"
                      class="user-inline-input"
                      placeholder="编辑消息后按回车重新生成"
                      @keydown.enter.exact.prevent="submitEditUserMessage(item.messageId)"
                    />
                    <button type="button" class="user-inline-send" @click="submitEditUserMessage(item.messageId)">
                      <el-icon><Top /></el-icon>
                    </button>
                  </div>
                </template>
                <template v-else>
                  <p v-if="!isSyntheticUserFilePrompt(item.content)" class="bubble-content">{{ item.content }}</p>
                  <div class="user-ops-row" aria-hidden="false">
                    <button
                      type="button"
                      class="icon-plain-btn"
                      title="复制"
                      @click="handleCopyMessage(item.content)"
                    >
                      <el-icon><DocumentCopy /></el-icon>
                    </button>
                    <button
                      type="button"
                      class="icon-plain-btn"
                      title="编辑"
                      @click="startEditUserMessage(item)"
                    >
                      <el-icon><EditPen /></el-icon>
                    </button>
                  </div>
                </template>
              </div>
            </div>

            <div v-else class="ai-message">
              <div v-if="item.agentTrace" class="trace-panel">
                <button
                  type="button"
                  class="trace-head"
                  @click="toggleTraceExpanded(item.messageId)"
                >
                  <span class="trace-title">已思考（{{ resolveTraceElapsed(item.agentTrace) }}）</span>
                  <div class="trace-head-meta">
                    <span class="trace-state">{{ item.agentTrace.status === 'succeeded' ? '完成' : item.agentTrace.status === 'failed' ? '失败' : '思考中' }}</span>
                    <span class="trace-collapse-indicator" v-if="isTraceExpanded(item.messageId)"><el-icon><ArrowDown /></el-icon></span>
                    <span class="trace-collapse-indicator" v-else><el-icon><ArrowRight /></el-icon></span>
                  </div>
                </button>

                <div v-if="!isTraceExpanded(item.messageId) && item.agentTrace.status === 'processing'" class="trace-collapsed is-running">
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

              <div class="ai-content markdown-body" v-html="renderMarkdownContent(item.content || '思考中...')"></div>

              <div v-if="pendingApprovalsByMessageId[item.messageId]?.status === 'pending'" class="ai-approval-card">
                <p class="ai-approval-title">{{ pendingApprovalsByMessageId[item.messageId].title }}</p>
                <p class="ai-approval-summary">{{ pendingApprovalsByMessageId[item.messageId].summary }}</p>
                <div class="ai-approval-actions">
                  <button
                    type="button"
                    class="quick-action-chip"
                    :disabled="Boolean(approvalSubmittingByMessageId[item.messageId])"
                    @click="handleApprovalDecision(item.messageId, 'approve')"
                  >
                    确认
                  </button>
                  <button
                    type="button"
                    class="quick-action-chip"
                    :disabled="Boolean(approvalSubmittingByMessageId[item.messageId])"
                    @click="handleApprovalDecision(item.messageId, 'reject')"
                  >
                    拒绝
                  </button>
                </div>
              </div>

              <div v-if="item.taskResultCard" class="task-result-inline-card">
                <div class="task-result-inline-head">
                  <p class="task-result-inline-title">{{ item.taskResultCard.title }}</p>
                  <p class="task-result-inline-summary">{{ item.taskResultCard.summary }}</p>
                </div>

                <AssistantAbilityRadarCard
                  v-if="item.taskResultCard.scores?.length && item.taskResultCard.scores.length >= 8"
                  :scores="item.taskResultCard.scores"
                  title="能力雷达（12维）"
                />

                <div v-if="item.taskResultCard.scores?.length" class="task-result-score-grid">
                  <div v-for="score in item.taskResultCard.scores" :key="`${item.messageId}-${score.dimension}`" class="task-result-score-item">
                    <div class="task-result-score-top">
                      <span>{{ score.dimension }}</span>
                      <span>{{ score.score }}</span>
                    </div>
                    <div class="task-result-score-track"><i :style="{ width: `${score.score}%` }"></i></div>
                  </div>
                </div>

                <div v-if="item.taskResultCard.match" class="task-result-match-card">
                  <p class="task-result-match-title">{{ item.taskResultCard.match.jobName }}</p>
                  <p class="task-result-match-meta">{{ item.taskResultCard.match.jobFamily }} · {{ item.taskResultCard.match.city }}</p>
                  <div class="task-result-match-scores">
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

                <div v-if="item.taskResultCard.pathStages?.length" class="task-result-path-list">
                  <div v-for="stage in item.taskResultCard.pathStages" :key="`${item.messageId}-${stage.stage}`" class="task-result-path-item">
                    <p class="task-result-path-stage">{{ stage.stage }}</p>
                    <p class="task-result-path-title">{{ stage.title }}</p>
                    <p class="task-result-path-detail">{{ stage.detail }}</p>
                  </div>
                </div>
              </div>

              <div v-if="item.status === 'succeeded' && item.actions?.length && pendingApprovalsByMessageId[item.messageId]?.status !== 'pending'" class="ai-quick-row">
                <button
                  v-for="(action, actionIndex) in item.actions || []"
                  :key="`${item.messageId}-${actionIndex}`"
                  type="button"
                  class="quick-action-chip"
                  :disabled="parsingResume"
                  @click="handleMessageAction(action, item.messageId)"
                >
                  <span>{{ action.label }}</span>
                  <el-icon><ArrowRight /></el-icon>
                </button>
              </div>

              <div class="ai-ops-row">
                <button
                  type="button"
                  class="icon-plain-btn"
                  title="复制"
                  @click="handleCopyMessage(item.content)"
                >
                  <el-icon><DocumentCopy /></el-icon>
                </button>

                <button
                  v-if="item.messageId === latestAssistantMessageId"
                  type="button"
                  class="icon-plain-btn"
                  title="重新生成"
                  :disabled="Boolean(regeneratingMessageId) || sending || parsingResume"
                  @click="handleRegenerateReply(item.messageId)"
                >
                  <el-icon><Refresh /></el-icon>
                </button>

                <button type="button" class="icon-plain-btn" title="朗读（预留）" disabled>
                  <el-icon><Microphone /></el-icon>
                </button>

                <button type="button" class="icon-plain-btn" title="分享（预留）" disabled>
                  <el-icon><Promotion /></el-icon>
                </button>

                <button type="button" class="icon-plain-btn" title="更多（预留）" disabled>
                  <el-icon><MoreFilled /></el-icon>
                </button>
              </div>
            </div>
          </div>
        </section>

        <section class="composer-wrap" :class="hasConversation ? 'composer-docked' : ''">
          <div v-if="selectedFiles.length" class="file-preview-row">
            <span class="file-pill">{{ selectedFiles[0].name }}</span>
            <button type="button" class="icon-btn" @click="removeSelectedFile">✕</button>
          </div>

          <div class="composer-panel">
            <textarea
              ref="inputRef"
              v-model="draft"
              class="chat-input"
              placeholder="发送消息（回车发送，Shift+回车换行）"
              @input="adjustInputHeight"
              @keydown.enter.exact.prevent="handleSend"
            />

            <div class="composer-tools">
              <input ref="fileInputRef" type="file" class="hidden" @change="handleFileChange" />
              <el-button type="button" class="tool-btn" :disabled="sending || parsingResume || Boolean(regeneratingMessageId)" @click="triggerFilePicker" title="上传文件" circle :icon="Connection" ></el-button>
              <el-button type="button" class="tool-btn" disabled title="麦克风（占位）" :icon="Microphone" circle></el-button>
              <button type="button" class="send-action-btn" :disabled="sending || parsingResume || Boolean(regeneratingMessageId)" @click="handleSend">
                <el-icon><Position /></el-icon>
                <span>发送</span>
              </button>
            </div>
          </div>
        </section>

        <p class="safe-note">以上内容均为AI生成，仅供参考和借鉴</p>
      </div>
    </main>

    <HomeInterestSurveyBubble />

    <el-dialog
      v-model="agentArtifactDialogVisible"
      :title="agentArtifactDialogTitle"
      width="760px"
      top="8vh"
      append-to-body
    >
      <div v-loading="agentArtifactDialogLoading" class="agent-artifact-dialog-body markdown-body" v-html="renderMarkdownContent(agentArtifactDialogContent)"></div>
    </el-dialog>
  </section>
</template>

<style scoped>
.home-layout {
  width: 100%;
  display: grid;
  grid-template-columns: 260px minmax(0, 1fr);
  gap: 0;
  height: calc(100vh - 56px);
  overflow: hidden;
  background: #f8fafc;
}

.left-dock {
  height: 100%;
  border-right: 1px solid rgb(226 232 240);
  background: #ffffff;
  padding: 14px 12px;
  overflow-y: auto;
}

.brand-card {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 6px 4px 12px;
}

.avatar-slot {
  width: 34px;
  height: 34px;
  border-radius: 50%;
  border: 1px solid #bfdbfe;
  background: linear-gradient(135deg, #dbeafe, #eff6ff);
  display: block;
  overflow: hidden;
}

.avatar-image {
  width: 100%;
  height: 100%;
  object-fit: cover;
  display: block;
}

.brand-title {
  font-size: 14px;
  font-weight: 700;
  color: #1e293b;
}

.brand-sub {
  font-size: 12px;
  color: #64748b;
}

.new-session-btn {
  width: 100%;
  border: 1px solid #bfdbfe;
  background: #eff6ff;
  color: #2563eb;
  border-radius: 10px;
  padding: 8px 10px;
  text-align: left;
  font-size: 14px;
  margin-bottom: 12px;
}

.left-card {
  border: 1px solid #e2e8f0;
  border-radius: 12px;
  background: #fff;
  padding: 10px;
  margin-bottom: 10px;
}

.left-title {
  font-size: 12px;
  color: #64748b;
  margin-bottom: 8px;
  font-weight: 600;
}

.side-link {
  width: 100%;
  text-align: left;
  border: 1px solid #e2e8f0;
  border-radius: 9px;
  background: #f8fafc;
  color: #334155;
  padding: 8px 9px;
  margin-bottom: 8px;
  display: flex;
  flex-direction: column;
  gap: 3px;
}

.side-link-title {
  font-size: 13px;
  font-weight: 600;
  color: #334155;
}

.side-link-desc {
  font-size: 11px;
  line-height: 1.4;
  color: #64748b;
}

.history-list {
  display: grid;
  gap: 8px;
}

.history-item {
  display: flex;
  align-items: center;
  gap: 6px;
  border: 1px solid #e2e8f0;
  border-radius: 10px;
  background: #f8fafc;
  padding: 6px;
}

.history-item.is-task-log {
  border-color: #bfdbfe;
  background: linear-gradient(135deg, #eff6ff, #e0f2fe);
}

.history-main {
  flex: 1;
  width: 100%;
  text-align: left;
  border: none;
  background: transparent;
  padding: 2px 4px;
}

.history-more {
  width: 26px;
  height: 26px;
  border: none;
  border-radius: 8px;
  background: #e2e8f0;
  color: #334155;
  font-size: 16px;
  display: grid;
  place-items: center;
  opacity: 0;
  transition: opacity 0.18s ease;
}

.history-task-clear-btn {
  border: 1px solid #93c5fd;
  background: #ffffff;
  color: #1d4ed8;
  border-radius: 9999px;
  font-size: 11px;
  padding: 2px 8px;
  white-space: nowrap;
}

.history-task-clear-btn:disabled {
  opacity: 0.6;
}

.history-item:hover .history-more,
.history-item:focus-within .history-more {
  opacity: 1;
}

.session-flag {
  margin-right: 4px;
}

.session-task-tag {
  margin-right: 6px;
  border: 1px solid #93c5fd;
  border-radius: 9999px;
  background: #dbeafe;
  color: #1e3a8a;
  font-size: 10px;
  line-height: 1;
  padding: 2px 6px;
}

.history-item.is-active {
  border-color: #93c5fd;
  background: #eff6ff;
}

.chat-stage {
  height: 100%;
  min-height: 0;
  display: flex;
  flex-direction: column;
  align-items: center;
  padding: 10px 24px 8px;
}

.chat-shell {
  width: min(980px, 100%);
  height: 100%;
  min-height: 0;
  display: flex;
  flex-direction: column;
}

.hero-stage {
  position: relative;
  flex: 1;
  min-height: 420px;
}

.public-cards {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 10px;
  margin-bottom: 16px;
}

.public-cards-overlay {
  position: absolute;
  inset: 0 0 auto 0;
  z-index: 2;
}

.public-card {
  position: relative;
  overflow: hidden;
  min-height: 116px;
  border: 1px solid #e2e8f0;
  border-radius: 12px;
  background: #fff;
  padding: 14px 12px;
}

.public-card-btn {
  width: 100%;
  text-align: left;
  border: 1px solid #e2e8f0;
  cursor: pointer;
  transition: transform 0.18s ease, box-shadow 0.18s ease, border-color 0.18s ease;
}

.public-card-btn:hover {
  transform: translateY(-1px);
  border-color: #bfdbfe;
  box-shadow: 0 10px 22px rgba(15, 23, 42, 0.08);
}

.public-card-btn:focus-visible {
  outline: 2px solid #93c5fd;
  outline-offset: 1px;
}

.public-card-bg {
  position: absolute;
  right: 6px;
  bottom: 6px;
  width: 54%;
  max-width: 98px;
  opacity: 0.48;
  pointer-events: none;
  user-select: none;
  object-fit: contain;
}

.public-card-content {
  position: relative;
  z-index: 1;
}

.welcome-zone {
  position: absolute;
  left: 50%;
  top: 42%;
  transform: translate(-50%, -50%);
  width: min(960px, 100%);
  z-index: 1;
  text-align: center;
}

.welcome-title {
  font-size: 42px;
  line-height: 1.2;
  color: #0f172a;
  font-weight: 700;
}

.welcome-subtitle {
  margin-top: 8px;
  font-size: 14px;
  color: #64748b;
}

.prompt-tags {
  margin-top: 20px;
  display: flex;
  flex-wrap: wrap;
  gap: 10px;
  justify-content: center;
}

.prompt-chip {
  border: 1px solid #e2e8f0;
  border-radius: 9999px;
  background: #f8fafc;
  padding: 8px 12px;
  font-size: 13px;
  color: #334155;
}

.agent-runtime-entry {
  margin-top: 18px;
  border-radius: 18px;
  border: 1px solid rgba(37, 99, 235, 0.2);
  background: linear-gradient(135deg, rgba(239, 246, 255, 0.92), rgba(224, 242, 254, 0.92));
  box-shadow: 0 12px 30px rgba(59, 130, 246, 0.12);
  padding: 18px;
  display: grid;
  gap: 10px;
}

.agent-runtime-entry-title {
  margin: 0;
  font-size: 15px;
  font-weight: 700;
  color: #1d4ed8;
}

.agent-runtime-entry-desc {
  margin: 0;
  font-size: 13px;
  line-height: 1.65;
  color: #334155;
}

.agent-runtime-entry-btn {
  justify-self: start;
  border: 1px solid rgba(37, 99, 235, 0.32);
  background: rgba(255, 255, 255, 0.82);
  color: #1d4ed8;
  border-radius: 9999px;
  font-size: 13px;
  font-weight: 600;
  padding: 7px 14px;
  transition: transform 0.2s ease, box-shadow 0.2s ease;
}

.agent-runtime-entry-btn:hover {
  transform: translateY(-1px);
  box-shadow: 0 8px 18px rgba(59, 130, 246, 0.18);
}

.agent-runtime-panel {
  margin: 18px auto 0;
  width: min(920px, 100%);
  border: 1px solid #dbeafe;
  border-radius: 14px;
  background: rgba(248, 251, 255, 0.92);
  padding: 12px;
  text-align: left;
  box-shadow: 0 12px 24px rgba(15, 23, 42, 0.06);
}

.agent-runtime-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
  margin-bottom: 10px;
}

.agent-runtime-title {
  font-size: 13px;
  font-weight: 700;
  color: #1e3a8a;
  margin: 0;
}

.agent-runtime-refresh {
  border: 1px solid #bfdbfe;
  background: #eff6ff;
  color: #1d4ed8;
  border-radius: 9999px;
  padding: 3px 10px;
  font-size: 12px;
}

.agent-runtime-goal-input {
  border: 1px solid #dbeafe;
  border-radius: 12px;
  background: #ffffff;
  padding: 8px 10px;
  margin-bottom: 10px;
}

.agent-runtime-goal-textarea {
  width: 100%;
  min-height: 64px;
  border: none;
  resize: vertical;
  outline: none;
  background: transparent;
  font-size: 12px;
  line-height: 1.55;
  color: #0f172a;
}

.agent-runtime-goal-meta {
  margin-top: 6px;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
  font-size: 11px;
  color: #64748b;
}

.agent-runtime-launch-btn {
  border: 1px solid #bfdbfe;
  border-radius: 9999px;
  background: #eff6ff;
  color: #1d4ed8;
  padding: 3px 10px;
  font-size: 12px;
}

.agent-runtime-preset-grid {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 8px;
}

.agent-runtime-preset {
  border: 1px solid #dbeafe;
  border-radius: 12px;
  background: #ffffff;
  padding: 9px 10px;
  text-align: left;
  transition: transform 0.16s ease, box-shadow 0.16s ease, border-color 0.16s ease;
}

.agent-runtime-preset:hover {
  transform: translateY(-1px);
  border-color: #93c5fd;
  box-shadow: 0 8px 18px rgba(15, 23, 42, 0.08);
}

.agent-runtime-preset:disabled {
  opacity: 0.65;
}

.agent-runtime-preset.is-active {
  border-color: #60a5fa;
  background: #eff6ff;
}

.agent-runtime-preset-title {
  margin: 0;
  font-size: 13px;
  font-weight: 600;
  color: #0f172a;
}

.agent-runtime-preset-goal {
  margin: 5px 0 0;
  font-size: 12px;
  line-height: 1.45;
  color: #475569;
}

.agent-runtime-preset-meta {
  margin: 7px 0 0;
  font-size: 11px;
  color: #64748b;
  display: inline-flex;
  align-items: center;
  gap: 8px;
}

.agent-runtime-preset-loading {
  color: #1d4ed8;
  font-weight: 600;
}

.agent-runtime-plan-card {
  margin-top: 10px;
  border: 1px solid #dbeafe;
  border-radius: 12px;
  background: #ffffff;
  padding: 9px 10px;
}

.agent-runtime-plan-title {
  margin: 0;
  font-size: 12px;
  font-weight: 700;
  color: #1e3a8a;
}

.agent-runtime-plan-expect {
  margin: 6px 0 0;
  font-size: 11px;
  color: #475569;
}

.agent-runtime-plan-steps {
  margin: 8px 0 0;
  padding-left: 18px;
  display: grid;
  gap: 5px;
}

.agent-runtime-plan-steps li {
  color: #334155;
  font-size: 11px;
}

.agent-runtime-plan-step-title {
  display: block;
  font-weight: 600;
}

.agent-runtime-plan-step-detail {
  display: block;
  color: #64748b;
  margin-top: 2px;
}

.agent-runtime-task-grid {
  margin-top: 10px;
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 8px;
}

.agent-runtime-task-card {
  border: 1px solid #e2e8f0;
  border-radius: 12px;
  background: #ffffff;
  padding: 8px 10px;
}

.agent-runtime-task-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
}

.agent-runtime-task-name {
  margin: 0;
  font-size: 12px;
  font-weight: 600;
  color: #0f172a;
}

.agent-runtime-task-status {
  font-size: 10px;
  border-radius: 9999px;
  padding: 2px 7px;
  border: 1px solid #cbd5e1;
}

.agent-task-status-running {
  color: #1d4ed8;
  border-color: #93c5fd;
  background: #eff6ff;
}

.agent-task-status-queued {
  color: #475569;
  border-color: #cbd5e1;
  background: #f8fafc;
}

.agent-task-status-paused {
  color: #92400e;
  border-color: #fcd34d;
  background: #fffbeb;
}

.agent-task-status-done {
  color: #166534;
  border-color: #86efac;
  background: #f0fdf4;
}

.agent-task-status-failed {
  color: #b91c1c;
  border-color: #fca5a5;
  background: #fef2f2;
}

.agent-task-status-rolled-back {
  color: #7c3aed;
  border-color: #c4b5fd;
  background: #f5f3ff;
}

.agent-runtime-task-step {
  margin: 6px 0 0;
  font-size: 11px;
  color: #475569;
  min-height: 17px;
}

.agent-runtime-task-progress-track {
  margin-top: 8px;
  width: 100%;
  height: 5px;
  border-radius: 9999px;
  background: #e2e8f0;
  overflow: hidden;
}

.agent-runtime-task-progress-track i {
  display: block;
  height: 100%;
  border-radius: inherit;
  background: linear-gradient(90deg, #2563eb, #3b82f6);
  transition: width 0.25s ease;
}

.agent-runtime-task-expect {
  margin: 8px 0 0;
  font-size: 11px;
  color: #64748b;
}

.agent-runtime-task-conclusion {
  margin-top: 7px;
  border-radius: 10px;
  border: 1px solid #dbeafe;
  background: #eff6ff;
  padding: 6px 8px;
  font-size: 11px;
  color: #334155;
  display: grid;
  gap: 4px;
}

.agent-runtime-task-conclusion-tag {
  font-weight: 700;
}

.agent-goal-conclusion-achieved {
  border-color: #86efac;
  background: #f0fdf4;
  color: #166534;
}

.agent-goal-conclusion-not-achieved {
  border-color: #fca5a5;
  background: #fef2f2;
  color: #b91c1c;
}

.agent-goal-conclusion-rolled-back {
  border-color: #c4b5fd;
  background: #f5f3ff;
  color: #7c3aed;
}

.agent-goal-conclusion-pending {
  border-color: #bfdbfe;
  background: #eff6ff;
  color: #1d4ed8;
}

.agent-runtime-task-actions {
  margin-top: 8px;
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
}

.agent-runtime-task-action {
  border: 1px solid #dbeafe;
  background: #eff6ff;
  color: #1d4ed8;
  border-radius: 9999px;
  padding: 2px 8px;
  font-size: 11px;
}

.agent-runtime-task-action.is-danger {
  border-color: #fecaca;
  background: #fef2f2;
  color: #b91c1c;
}

.agent-artifact-dialog-body {
  max-height: 62vh;
  overflow: auto;
  padding-right: 4px;
}

.chat-list {
  flex: 1;
  min-height: 0;
  overflow-y: auto;
  padding-right: 4px;
  display: flex;
  flex-direction: column;
  gap: 14px;
  -ms-overflow-style: none;
  scrollbar-width: none;
}

.chat-list::-webkit-scrollbar {
  width: 0;
  height: 0;
}

.message-row {
  width: 100%;
  display: flex;
}

.message-row.is-user {
  justify-content: flex-end;
}

.message-row.is-ai {
  justify-content: flex-start;
}

.ai-message {
  width: min(820px, 100%);
  max-width: 820px;
}

.trace-panel {
  margin-bottom: 10px;
  width: 100%;
  border: 1px solid #e2e8f0;
  background: #f8fafc;
  border-radius: 10px;
  padding: 8px 10px;
}

.trace-head {
  display: flex;
  align-items: center;
  justify-content: flex-start;
  gap: 8px;
  width: 100%;
  border: none;
  background: transparent;
  padding: 2px 0;
  cursor: pointer;
  text-align: left;
}

.trace-head-meta {
  display: inline-flex;
  align-items: center;
  gap: 6px;
}

.trace-title {
  font-size: 12px;
  color: #0f172a;
  font-weight: 600;
}

.trace-state {
  font-size: 12px;
  color: #475569;
}

.trace-collapse-indicator {
  font-size: 11px;
  color: #64748b;
}

.trace-collapsed {
  margin-top: 4px;
  border: 1px dashed #cbd5e1;
  border-radius: 8px;
  background: #ffffff;
  min-height: 26px;
  padding: 5px 8px;
  display: flex;
  align-items: center;
  justify-content: flex-start;
  gap: 10px;
}

.trace-collapsed.is-running {
  background: transparent;
}

.trace-collapsed-text {
  font-size: 12px;
  color: #475569;
  line-height: 1.4;
}

.trace-loading-dots {
  display: inline-flex;
  align-items: center;
  gap: 4px;
}

.trace-loading-dots i {
  width: 5px;
  height: 5px;
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

.trace-task-list {
  margin-top: 6px;
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
}

.trace-section {
  margin-top: 8px;
}

.trace-section-title {
  margin: 0 0 6px;
  font-size: 11px;
  font-weight: 700;
  color: #1e3a8a;
}

.trace-task-chip {
  font-size: 11px;
  border-radius: 9999px;
  padding: 3px 8px;
  border: 1px solid #cbd5e1;
  color: #475569;
  background: #fff;
  display: inline-flex;
  align-items: center;
  gap: 5px;
}

.trace-chip-icon {
  width: 13px;
  height: 13px;
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
  margin-top: 8px;
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
  font-size: 12px;
  font-weight: 600;
  color: #334155;
}

.trace-step-status {
  font-size: 11px;
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
  margin-top: 2px;
  font-size: 12px;
  color: #475569;
  line-height: 1.5;
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

.ai-name {
  font-size: 12px;
  color: #64748b;
  margin-bottom: 4px;
}

.ai-content {
  font-size: 14px;
  color: #1f2937;
  line-height: 1.6;
}

.markdown-body {
  line-height: 1.6;
  word-break: break-word;
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
  font-size: 22px;
}

.markdown-body :deep(h2) {
  font-size: 18px;
}

.markdown-body :deep(h3) {
  font-size: 16px;
}

.markdown-body :deep(h4) {
  font-size: 15px;
}

.markdown-body :deep(h1 + *),
.markdown-body :deep(h2 + *),
.markdown-body :deep(h3 + *),
.markdown-body :deep(h4 + *) {
  margin-top: 6px;
}

.markdown-body :deep(p + p) {
  margin-top: 6px;
}

.markdown-body :deep(code) {
  padding: 1px 6px;
  border-radius: 6px;
  background: #f1f5f9;
  color: #0f172a;
  font-size: 12px;
}

.markdown-body :deep(pre) {
  margin-top: 6px;
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
  margin: 6px 0 0;
  padding-left: 18px;
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
    font-size: 19px;
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
    padding-left: 16px;
  }

  .markdown-body :deep(th),
  .markdown-body :deep(td) {
    font-size: 12px;
    padding: 5px 6px;
    white-space: nowrap;
  }
}

.user-bubble {
  max-width: min(560px, 78%);
  background: transparent;
  border: none;
  padding: 0;
}

.user-message-stack {
  display: flex;
  flex-direction: column;
  align-items: flex-end;
  gap: 6px;
}

.user-file-card {
  min-width: 200px;
  max-width: min(460px, 78vw);
  border-radius: 16px;
  background: #e5e7eb;
  padding: 12px 14px;
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.file-card-title {
  font-size: 12px;
  color: #6b7280;
}

.file-card-name {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  color: #2563eb;
  font-size: 14px;
  word-break: break-all;
  text-decoration: underline;
  text-underline-offset: 2px;
  cursor: pointer;
}

.bubble-content {
  font-size: 14px;
  color: #1e293b;
  white-space: pre-wrap;
  margin: 0;
  max-width: min(560px, 78vw);
  background: #e5e7eb;
  border-radius: 14px;
  padding: 10px 12px;
}

.user-ops-row {
  min-height: 18px;
  display: flex;
  align-items: center;
  justify-content: flex-end;
  gap: 10px;
  opacity: 0;
  pointer-events: none;
  transition: opacity 0.16s ease;
}

.user-bubble:hover .user-ops-row,
.user-bubble:focus-within .user-ops-row {
  opacity: 1;
  pointer-events: auto;
}

.user-inline-editor {
  width: min(560px, 78vw);
  display: grid;
  grid-template-columns: auto 1fr auto;
  align-items: center;
  gap: 8px;
  border: 1px solid #3b82f6;
  background: #ffffff;
  border-radius: 14px;
  padding: 6px;
}

.user-inline-cancel {
  width: 32px;
  height: 32px;
  display: inline-grid;
  place-items: center;
  border: none;
  background: transparent;
  color: #64748b;
  font-size: 16px;
  padding: 0;
}

.user-inline-input {
  width: 100%;
  height: 42px;
  border-radius: 10px;
  border: none;
  background: transparent;
  color: #0f172a;
  outline: none;
  padding: 0 12px;
  font-size: 16px;
}

.user-inline-send {
  width: 34px;
  height: 34px;
  border: none;
  border-radius: 9999px;
  background: #2563eb;
  color: #fff;
  display: inline-grid;
  place-items: center;
}

.bubble-files {
  margin-top: 8px;
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
}

.bubble-file-pill {
  border: 1px solid #93c5fd;
  border-radius: 9999px;
  background: #eff6ff;
  color: #1d4ed8;
  font-size: 12px;
  line-height: 1;
  padding: 4px 8px;
}

.ai-ops-row {
  margin-top: 8px;
  display: flex;
  flex-wrap: wrap;
  gap: 12px;
}

.icon-plain-btn {
  border: none;
  background: transparent;
  color: #6b7280;
  padding: 0;
  width: 18px;
  height: 18px;
  display: inline-grid;
  place-items: center;
}

.icon-plain-btn:disabled {
  color: #cbd5e1;
}

.icon-plain-btn:hover:not(:disabled) {
  color: #334155;
}

.ai-quick-row {
  margin-top: 10px;
  display: flex;
  flex-direction: column;
  align-items: flex-start;
  gap: 8px;
}

.task-result-inline-card {
  margin-top: 10px;
  border: 1px solid #dbeafe;
  border-radius: 12px;
  background: #f8fbff;
  padding: 9px;
}

.task-result-inline-head {
  display: grid;
  gap: 4px;
}

.task-result-inline-title {
  margin: 0;
  font-size: 13px;
  font-weight: 700;
  color: #0f172a;
}

.task-result-inline-summary {
  margin: 0;
  font-size: 12px;
  color: #475569;
  line-height: 1.6;
}

.task-result-score-grid {
  margin-top: 8px;
  display: grid;
  gap: 6px;
}

.task-result-score-item {
  border: 1px solid #e2e8f0;
  border-radius: 8px;
  background: #ffffff;
  padding: 6px 8px;
}

.task-result-score-top {
  display: flex;
  justify-content: space-between;
  font-size: 11px;
  color: #334155;
}

.task-result-score-track {
  margin-top: 4px;
  height: 4px;
  border-radius: 9999px;
  background: #e2e8f0;
  overflow: hidden;
}

.task-result-score-track i {
  display: block;
  height: 100%;
  border-radius: inherit;
  background: linear-gradient(90deg, #2563eb, #3b82f6);
}

.task-result-match-card,
.task-result-path-item {
  margin-top: 8px;
  border: 1px solid #e2e8f0;
  border-radius: 8px;
  background: #ffffff;
  padding: 8px;
}

.task-result-match-title,
.task-result-path-stage {
  margin: 0;
  font-size: 12px;
  font-weight: 700;
  color: #0f172a;
}

.task-result-match-meta,
.task-result-path-title {
  margin: 4px 0 0;
  font-size: 11px;
  color: #475569;
}

.task-result-match-scores {
  margin-top: 6px;
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  font-size: 11px;
  color: #1d4ed8;
}

.task-result-path-list {
  margin-top: 6px;
  display: grid;
  gap: 6px;
}

.task-result-path-detail {
  margin: 4px 0 0;
  font-size: 11px;
  line-height: 1.5;
  color: #64748b;
}

.quick-action-chip {
  border: none;
  background: #f3f4f6;
  color: #374151;
  border-radius: 12px;
  padding: 8px 12px;
  font-size: 14px;
  display: inline-flex;
  align-items: center;
  gap: 8px;
}

.quick-action-chip:disabled {
  opacity: 0.6;
}

.composer-wrap {
  margin-top: auto;
  padding-top: 14px;
}

.composer-docked {
  position: sticky;
  bottom: 0;
  background: linear-gradient(to top, rgba(248, 250, 252, 0.98), rgba(248, 250, 252, 0.88));
  padding-bottom: 8px;
}

.file-preview-row {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 8px;
}

.file-pill {
  border: 1px solid #dbeafe;
  border-radius: 9999px;
  background: transparent;
  color: #2563eb;
  font-size: 12px;
  padding: 4px 10px;
  text-decoration: underline;
  text-underline-offset: 2px;
}

.composer-panel {
  border: 1px solid #e2e8f0;
  border-radius: 18px;
  background: white;
  box-shadow: 0 10px 28px rgba(15, 23, 42, 0.08);
  padding: 10px 12px;
}

.chat-input {
  width: 100%;
  min-height: 56px;
  max-height: 140px;
  resize: none;
  border: none;
  outline: none;
  background: transparent;
  font-size: 15px;
  color: #0f172a;
  line-height: 1.5;
}

.composer-tools {
  margin-top: 6px;
  display: flex;
  align-items: center;
  gap: 8px;
}

.tool-btn,
.icon-btn {
  width: 30px;
  height: 30px;
  border-radius: 50%;
  border: 1px solid #e2e8f0;
  background: #f8fafc;
  display: grid;
  place-items: center;
  font-size: 14px;
  color: #334155;
}

.tool-tip {
  margin-left: auto;
  font-size: 12px;
  color: #94a3b8;
}

.send-action-btn {
  margin-left: auto;
  border: none;
  background: transparent;
  color: #2563eb;
  font-size: 13px;
  padding: 0;
  display: inline-flex;
  align-items: center;
  gap: 4px;
}

.send-action-btn:disabled {
  color: #94a3b8;
}

.safe-note {
  text-align: center;
  margin-top: 6px;
  flex-shrink: 0;
  font-size: 12px;
  color: #94a3b8;
}

:deep(.dropdown-delete) {
  color: #ef4444;
}

.dashboard-fade-enter-active,
.dashboard-fade-leave-active {
  transition: all 0.35s ease;
}

.welcome-fade-enter-active,
.welcome-fade-leave-active {
  transition: opacity 0.45s ease, transform 0.45s ease;
}

.welcome-fade-enter-from,
.welcome-fade-leave-to {
  opacity: 0;
  transform: translate(-50%, -44%);
}

.welcome-fade-enter-to,
.welcome-fade-leave-from {
  opacity: 1;
  transform: translate(-50%, -50%);
}

.dashboard-fade-enter-from,
.dashboard-fade-leave-to {
  opacity: 0;
  transform: translateY(16px);
  max-height: 0;
}

.dashboard-fade-enter-to,
.dashboard-fade-leave-from {
  opacity: 1;
  transform: translateY(0);
  max-height: 260px;
}

@media (max-width: 1024px) {
  .home-layout {
    grid-template-columns: 1fr;
  }

  .left-dock {
    height: auto;
    border-right: none;
    border-bottom: 1px solid #e2e8f0;
  }

  .chat-stage {
    height: auto;
    min-height: calc(100vh - 220px);
    padding: 14px;
  }

  .public-cards {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }

  .agent-runtime-preset-grid,
  .agent-runtime-task-grid {
    grid-template-columns: 1fr;
  }

  .hero-stage {
    min-height: 360px;
  }

  .public-cards-overlay {
    position: static;
    margin-bottom: 14px;
  }

  .welcome-zone {
    position: static;
    transform: none;
    width: 100%;
    margin-top: 8px;
  }

  .welcome-title {
    font-size: 30px;
  }
}
</style>
