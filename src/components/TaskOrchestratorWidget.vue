<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  ArrowLeft,
  CircleCheckFilled,
  Close,
  Delete,
  Loading,
  Operation,
  Paperclip,
  Plus,
  Promotion,
  Refresh,
  VideoPause,
  VideoPlay,
  WarningFilled,
} from '@element-plus/icons-vue'
import {
  controlHomeAgentTask,
  createHomeAgentTask,
  createHomeSession,
  getHomeAgentRuntimeOverview,
  updateHomeSession,
  type HomeAgentRuntimeOverviewResult,
  type HomeAgentRuntimePreset,
  type HomeAgentRuntimeTaskControlAction,
  type HomeAgentRuntimeTaskDetail,
  type HomeAgentRuntimeTaskStep,
  type HomeMessage,
} from '../services/home'
import { isSuccessCode } from '../services/http'
import { createCareerReportGenerateJob, getCareerReportGenerateJobStatus, getCareerReportList } from '../services/careerReport'
import {
  autoPlanCareerPath,
  getAutoPlanCareerPathJobStatus,
  getCareerPathDetail,
  getLatestCareerPath,
  getMatchRecommendations,
  refineMatchRecommendations,
  type AutoPlanJobCreateResult,
  type AutoPlanJobStatusResult,
  type CareerPathDetailResult,
  type LatestPathResult,
  type MatchRecommendationsResult,
} from '../services/matchAnalysis'
import {
  createParseProfileJob,
  getParseProfileJobStatus,
  getProfileAnalyzeJobStatus,
  getStudentProfile,
  saveStudentProfile,
  type AbilityScores,
  type GetProfileResult,
  type ParseProfileJobCreateResult,
  type ParseProfileJobStatusResult,
  type ProfileAnalyzeJobStatusResult,
  type ProfileFormData,
  type SaveProfileResult,
} from '../services/studentProfile'
import { getToken } from '../utils/auth'
import {
  TASK_ORCHESTRATOR_OPEN_EVENT,
  emitCareerReportRefresh,
  emitHomeAssistantSessionRefresh,
  emitTaskOrchestratorRouteRefresh,
  emitTaskOrchestratorStream,
  type TaskOrchestratorActionPayload,
  type TaskOrchestratorOpenPayload,
  type TaskOrchestratorResultCardPayload,
} from '../utils/globalAssistant'
import {
  clearTaskTranscript,
  setTaskSessionId,
  upsertTaskTranscriptMessage,
} from '../utils/taskSession'

interface ApiResponse<T> {
  code: number
  msg: string
  data?: T
}

interface TaskStreamPayload {
  type: 'task_snapshot' | 'task_update' | 'task_done' | string
  task?: HomeAgentRuntimeTaskDetail
}

type DemoIntent = 'resume_analysis' | 'job_match' | 'path_plan' | 'generic'

interface GoalQueueItem {
  itemId: string
  goalText: string
  intent: DemoIntent
  source: 'manual' | 'quick' | 'demo'
  presetId?: string
  resumeFileName?: string
}

interface DemoScoreItem {
  dimension: string
  score: number
}

interface DemoMatchResult {
  jobId?: string
  jobName: string
  jobFamily: string
  city: string
  overallScore: number
  skillScore: number
  intentScore: number
  growthScore: number
}

interface DemoPathStage {
  stage: string
  title: string
  detail: string
}

interface DemoResultPayload {
  intent: DemoIntent
  title: string
  summary: string
  jumpRoute: '/student' | '/match' | '/report' | ''
  jumpLabel: string
  scores?: DemoScoreItem[]
  match?: DemoMatchResult
  pathStages?: DemoPathStage[]
  actions?: TaskOrchestratorActionPayload[]
}

const TASK_EXECUTION_FAILURE_MSG = '请求失败，请检查网络'
const RESUME_MAX_FILE_SIZE = 10 * 1024 * 1024
const RESUME_ALLOWED_EXTENSIONS = ['pdf', 'jpg', 'jpeg', 'png', 'docx', 'doc']
const RESUME_FILE_ACCEPT = '.pdf,.jpg,.jpeg,.png,.docx,.doc'

const ABILITY_LABEL_MAP: Record<keyof AbilityScores, string> = {
  professionalSkill: '专业技能',
  certificate: '证书能力',
  innovation: '创新能力',
  internalMotivation: '内驱动力',
  learning: '学习能力',
  stressTolerance: '抗压能力',
  communication: '沟通表达',
  internship: '实习经验',
  language: '语言能力',
  leadership: '领导力',
  adaptability: '适应能力',
  execution: '执行稳定性',
}

const ABILITY_ORDER = Object.keys(ABILITY_LABEL_MAP) as Array<keyof AbilityScores>

const route = useRoute()
const router = useRouter()

const visible = ref(false)
const loading = ref(false)
const creatingTask = ref(false)
const controllingTask = ref(false)
const panelRef = ref<HTMLDivElement>()
const goalDraft = ref('')
const selectedPresetId = ref('')
const selectedFiles = ref<File[]>([])
const selectedReportId = ref('')
const fileInputRef = ref<HTMLInputElement>()
const queueFileInputRef = ref<HTMLInputElement>()
const availableReports = ref<Array<{ reportId: string; reportTitle: string }>>([])
const overview = ref<HomeAgentRuntimeOverviewResult | null>(null)
const taskDetail = ref<HomeAgentRuntimeTaskDetail | null>(null)
const linkedSessionId = ref('')
const openPayload = ref<TaskOrchestratorOpenPayload | null>(null)
const dragging = ref(false)
const latestDemoResult = ref<DemoResultPayload | null>(null)

const goalQueue = ref<GoalQueueItem[]>([])
const queueRunning = ref(false)
const queueStatusByItemId = ref<Record<string, 'pending' | 'running' | 'done' | 'failed'>>({})
const taskIntentByTaskId = ref<Record<string, DemoIntent>>({})
const taskQueueItemByTaskId = ref<Record<string, string>>({})
const taskResultByTaskId = ref<Record<string, DemoResultPayload>>({})
const queueResumeFileByItemId = ref<Record<string, File>>({})
const taskResumeFileByTaskId = ref<Record<string, File>>({})
const queueUploadTargetItemId = ref('')
const queueStartMessageSent = ref(false)

const panelX = ref(Math.max(16, window.innerWidth - 560))
const panelY = ref(Math.max(56, window.innerHeight * 0.18))
const panelWidth = ref(560)
const panelHeight = ref(720)
const resizingByHandle = ref(false)

const taskStreamRef = ref<EventSource | null>(null)
let panelResizeObserver: ResizeObserver | null = null

const isHomeRoute = computed(() => route.path === '/')
const presets = computed(() => overview.value?.presets || [])
const selectedPreset = computed(() => {
  const presetId = String(selectedPresetId.value || '').trim()
  if (!presetId) return null
  return presets.value.find(item => item.presetId === presetId) || null
})
const inferredIntent = computed(() => inferIntent(goalDraft.value, selectedFiles.value.length > 0))
const autoPlan = computed(() => buildAutoPlan(inferredIntent.value, goalDraft.value, selectedFiles.value.map(item => item.name)))
const planSteps = computed(() => taskDetail.value?.planSteps || taskDetail.value?.steps?.filter(step => String(step.type || '').toLowerCase() !== 'tool') || [])
const toolExecutions = computed(() => taskDetail.value?.toolExecutions || [])
const runtimeTaskTitle = computed(() => {
  const task = taskDetail.value
  if (!task) return ''
  const intent = taskIntentByTaskId.value[task.taskId] || inferIntent(task.goalInput || task.prompt || '', false)
  return resolveIntentMessageTitle(intent)
})
const runtimePlanSteps = computed(() => {
  const task = taskDetail.value
  const steps = planSteps.value
  if (!task || !steps.length) return steps

  const intent = taskIntentByTaskId.value[task.taskId] || inferIntent(task.goalInput || task.prompt || '', false)
  if (intent === 'generic') return steps

  const localPlan = buildAutoPlan(intent, task.goalInput || task.prompt || '', [])
  return steps.map((step, index) => {
    const localStep = localPlan[index]
    if (!localStep) return step
    return {
      ...step,
      title: localStep.title,
      detail: localStep.detail,
      expectedResult: localStep.expectedResult,
    }
  })
})
const runtimeGoalConclusionText = computed(() => {
  const task = taskDetail.value
  if (!task) return '执行中'

  const intent = taskIntentByTaskId.value[task.taskId] || inferIntent(task.goalInput || task.prompt || '', false)
  const status = String(task.status || '')
  if (status === 'completed') {
    return latestDemoResult.value?.summary || '任务已完成并同步结果卡。'
  }
  if (status === 'failed' || status === 'cancelled' || status === 'rolled_back') {
    return task.goalConclusion?.summary || '任务未完成，可按需重试。'
  }
  return resolveIntentProgressHint(intent, 'update')
})
const queueItemsWithStatus = computed(() => {
  return goalQueue.value.map(item => ({
    ...item,
    status: queueStatusByItemId.value[item.itemId] || 'pending',
  }))
})
const hasPendingQueueItems = computed(() => queueItemsWithStatus.value.some(item => item.status === 'pending'))
const hasAvailableReports = computed(() => availableReports.value.length > 0)
const queueProgressMeta = computed(() => {
  const total = goalQueue.value.length
  if (!total) return '暂无任务'

  const doneCount = queueItemsWithStatus.value.filter(item => item.status === 'done').length
  const runningItem = queueItemsWithStatus.value.find(item => item.status === 'running')
  if (runningItem) {
    const index = goalQueue.value.findIndex(item => item.itemId === runningItem.itemId)
    return `进行中 ${Math.max(1, index + 1)}/${total}`
  }
  if (queueRunning.value) {
    return `执行中 ${doneCount}/${total}`
  }
  return `${total}项待执行`
})
const showGoalReportSelector = computed(() => isReportPolishLikeGoal(goalDraft.value))
const displayTaskProgress = computed(() => {
  const task = taskDetail.value
  if (!task) return 0

  const status = String(task.status || '')
  if (status === 'completed') return 100
  if (status === 'failed' || status === 'cancelled' || status === 'rolled_back') {
    return Math.max(0, Math.min(100, Math.round(Number(task.progress || 0))))
  }

  const rawProgress = Number(task.progress)
  if (Number.isFinite(rawProgress) && rawProgress >= 0 && rawProgress <= 100) {
    return Math.max(0, Math.min(99, Math.round(rawProgress)))
  }

  const steps = planSteps.value
  if (!steps.length) return 0

  const completedCount = steps.filter((step) => {
    const stepStatus = String(step.status || '')
    return stepStatus === 'succeeded' || stepStatus === 'completed'
  }).length
  const runningCount = steps.filter((step) => {
    const stepStatus = String(step.status || '')
    return stepStatus === 'processing' || stepStatus === 'in_progress'
  }).length
  const progressBySteps = (completedCount + (runningCount > 0 ? 0.5 : 0)) / steps.length
  return Math.max(0, Math.min(99, Math.round(progressBySteps * 100)))
})

const panelStyle = computed(() => ({
  left: `${panelX.value}px`,
  top: `${panelY.value}px`,
  width: `${panelWidth.value}px`,
  height: `${panelHeight.value}px`,
}))

function inferIntent(goalText: string, hasFile = false): DemoIntent {
  const text = String(goalText || '').trim().toLowerCase()
  if (!text && hasFile) return 'resume_analysis'

  if (hasFile || /(简历|能力评估|能力画像|评分|解析简历|分析简历)/.test(text)) {
    return 'resume_analysis'
  }
  if (/(推荐|匹配|岗位|职位|意愿|求职)/.test(text)) {
    return 'job_match'
  }
  if (/(职业路径|路径规划|发展路径|成长路线|规划路径|职业规划)/.test(text)) {
    return 'path_plan'
  }
  return 'generic'
}

function isReportPolishLikeGoal(goalText: string) {
  const text = String(goalText || '').trim().toLowerCase()
  if (!text) return false
  return /(报告|润色|polish|优化报告|生涯报告|报告优化)/.test(text)
}

function resolveIntentLabel(intent: DemoIntent) {
  if (intent === 'resume_analysis') return '解析并评估简历'
  if (intent === 'job_match') return '人岗推荐与匹配'
  if (intent === 'path_plan') return '规划职业路径'
  return '自动拆解执行'
}

function isPresetDisabled(preset: HomeAgentRuntimePreset) {
  if (preset.presetId === 'report_polish_approval') {
    return !hasAvailableReports.value
  }
  return false
}

function resolvePresetDisabledHint(preset: HomeAgentRuntimePreset) {
  if (preset.presetId === 'report_polish_approval' && !hasAvailableReports.value) {
    return '暂无可用报告，请先在生涯报告页生成报告。'
  }
  return ''
}

function resolveIntentPresetId(intent: DemoIntent) {
  if (intent === 'resume_analysis') return 'resume_parse_eval'
  if (intent === 'job_match') return 'report_polish_approval'
  if (intent === 'path_plan') return 'report_polish_approval'
  return ''
}

function buildAutoPlan(intent: DemoIntent, goalText: string, fileNames: string[]) {
  const goal = String(goalText || '').trim()
  const fileHint = fileNames.length ? `已附带文件：${fileNames[0]}` : '未附带文件，可继续上传简历后再执行。'

  if (intent === 'resume_analysis') {
    return [
      {
        stepId: 'auto_resume_1',
        type: 'tool',
        title: '读取简历与用户画像',
        detail: fileHint,
        expectedResult: '抽取基础信息、经历亮点与关键证据。',
        toolName: 'resume_parser',
        status: 'pending',
      },
      {
        stepId: 'auto_resume_2',
        type: 'thought',
        title: '生成12维能力评分',
        detail: '围绕学习、项目、工程、表达、协作等维度综合评分。',
        expectedResult: '输出可视化评分与优势/短板摘要。',
        toolName: '',
        status: 'pending',
      },
      {
        stepId: 'auto_resume_3',
        type: 'plan',
        title: '同步能力评估页并给出跳转建议',
        detail: goal || '根据简历分析结果生成后续行动建议。',
        expectedResult: '提示跳转/student查看完整画像和雷达图。',
        toolName: '',
        status: 'pending',
      },
    ] as HomeAgentRuntimeTaskStep[]
  }

  if (intent === 'job_match') {
    return [
      {
        stepId: 'auto_match_1',
        type: 'tool',
        title: '汇总兴趣测评与求职意愿',
        detail: '融合职业兴趣小测、用户画像、城市与薪资偏好。',
        expectedResult: '形成推荐约束与排序规则。',
        toolName: 'intent_merge',
        status: 'pending',
      },
      {
        stepId: 'auto_match_2',
        type: 'task',
        title: '执行推荐与匹配计算',
        detail: goal || '对候选岗位进行综合匹配度计算。',
        expectedResult: '输出匹配度最高岗位及分项评分。',
        toolName: '',
        status: 'pending',
      },
      {
        stepId: 'auto_match_3',
        type: 'plan',
        title: '生成岗位冲刺建议并提示跳转',
        detail: '按匹配结果给出行动建议。',
        expectedResult: '提示跳转/match查看详细匹配过程。',
        toolName: '',
        status: 'pending',
      },
    ] as HomeAgentRuntimeTaskStep[]
  }

  if (intent === 'path_plan') {
    return [
      {
        stepId: 'auto_path_1',
        type: 'tool',
        title: '读取最高匹配岗位与历史路径',
        detail: '以最高匹配岗位作为路径规划锚点。',
        expectedResult: '确定路径起点、目标岗位与阶段节点。',
        toolName: 'path_anchor_resolver',
        status: 'pending',
      },
      {
        stepId: 'auto_path_2',
        type: 'task',
        title: '生成阶段式职业路径',
        detail: goal || '输出阶段任务、里程碑和能力证据要求。',
        expectedResult: '形成可执行职业路径草案。',
        toolName: '',
        status: 'pending',
      },
      {
        stepId: 'auto_path_3',
        type: 'plan',
        title: '展示AI建议详情并提示跳转',
        detail: '输出路径说明与下一步动作。',
        expectedResult: '提示跳转/match查看路径图与详细建议。',
        toolName: '',
        status: 'pending',
      },
    ] as HomeAgentRuntimeTaskStep[]
  }

  return [
    {
      stepId: 'auto_generic_1',
      type: 'thought',
      title: '理解并拆解目标',
      detail: goal || '根据输入目标自动拆解执行步骤。',
      expectedResult: '形成清晰可执行的任务分解。',
      toolName: '',
      status: 'pending',
    },
    {
      stepId: 'auto_generic_2',
      type: 'tool',
      title: '执行关键工具链',
      detail: '按拆解结果调用对应工具与接口。',
      expectedResult: '返回关键中间结果与进度状态。',
      toolName: 'tool_orchestrator',
      status: 'pending',
    },
    {
      stepId: 'auto_generic_3',
      type: 'plan',
      title: '输出行动清单与结论',
      detail: '聚合结果，生成最终建议。',
      expectedResult: '输出可执行结论与下一步建议。',
      toolName: '',
      status: 'pending',
    },
  ] as HomeAgentRuntimeTaskStep[]
}

function normalizeScore(value: unknown, fallback = 0) {
  const numeric = Number(value)
  if (!Number.isFinite(numeric)) return fallback
  return Math.max(0, Math.min(100, Math.round(numeric)))
}

function extractApiPayload<T>(response: { data: ApiResponse<T> & { payload?: T } }) {
  const body = response.data as ApiResponse<T> & { payload?: T }
  return body.payload ?? body.data
}

function sleepMs(ms: number) {
  return new Promise((resolve) => window.setTimeout(resolve, Math.max(0, ms)))
}

function clampPollMs(value: unknown, fallback = 800) {
  const numeric = Number(value)
  const resolved = Number.isFinite(numeric) ? numeric : fallback
  return Math.max(1000, Math.min(2000, Math.round(resolved)))
}

function shouldForceTaskExecutionFailure() {
  try {
    return window.localStorage.getItem('debug.forceTaskExecutionFailure') === 'true'
  } catch {
    return false
  }
}

function normalizeStringList(input: unknown) {
  if (!Array.isArray(input)) return [] as string[]
  return input
    .map((item) => String(item || '').trim())
    .filter(Boolean)
}

function buildMarkdownProgressBar(progress: number) {
  const normalized = normalizeScore(progress, 0)
  const total = 18
  const filled = Math.max(0, Math.min(total, Math.round((normalized / 100) * total)))
  return `[${'#'.repeat(filled)}${'-'.repeat(total - filled)}] ${normalized}%`
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

function setQueueItemResumeFile(itemId: string, file: File | null) {
  if (!itemId) return

  const nextMap = { ...queueResumeFileByItemId.value }
  if (file) {
    nextMap[itemId] = file
  } else {
    delete nextMap[itemId]
  }
  queueResumeFileByItemId.value = nextMap

  goalQueue.value = goalQueue.value.map(item => {
    if (item.itemId !== itemId) return item
    return {
      ...item,
      resumeFileName: file ? file.name : '',
    }
  })
}

function triggerQueueFilePicker(itemId: string) {
  if (queueRunning.value) return
  queueUploadTargetItemId.value = String(itemId || '').trim()
  queueFileInputRef.value?.click()
}

function handlePresetUploadResume() {
  triggerFilePicker()
}

function handleQueueFileChange(event: Event) {
  const targetItemId = String(queueUploadTargetItemId.value || '').trim()
  const input = event.target as HTMLInputElement
  const candidate = Array.from(input.files || [])[0] || null
  const file = candidate && validateResumeFile(candidate, 'queue') ? candidate : null

  if (targetItemId) {
    setQueueItemResumeFile(targetItemId, file)
  }

  if (input) {
    input.value = ''
  }
  queueUploadTargetItemId.value = ''
}

async function ensureResumeProfileReady(taskId: string) {
  const file = taskResumeFileByTaskId.value[taskId] || null
  if (!file) return false

  try {
    const createResponse = await createParseProfileJob(file)
    const createPayload = extractApiPayload<ParseProfileJobCreateResult>(createResponse as { data: ApiResponse<ParseProfileJobCreateResult> & { payload?: ParseProfileJobCreateResult } })
    const parseJobId = String(createPayload?.parseJobId || '').trim()

    let parsedProfile: Partial<ProfileFormData> | null = null
    if (parseJobId) {
      for (let i = 0; i < 12; i += 1) {
        const statusResponse = await getParseProfileJobStatus(parseJobId)
        const statusPayload = extractApiPayload<ParseProfileJobStatusResult>(statusResponse as { data: ApiResponse<ParseProfileJobStatusResult> & { payload?: ParseProfileJobStatusResult } })
        const status = String(statusPayload?.status || '').trim()
        if (status === 'succeeded') {
          parsedProfile = (statusPayload?.result?.parsedProfile || null) as Partial<ProfileFormData> | null
          break
        }
        if (status === 'failed') {
          break
        }
        await sleepMs(clampPollMs(statusPayload?.pollAfterMs, 800))
      }
    }

    const normalizedProfile = normalizeProfileForSave(parsedProfile)
    const saveResponse = await saveStudentProfile(normalizedProfile, true)
    const savePayload = extractApiPayload<SaveProfileResult>(saveResponse as { data: ApiResponse<SaveProfileResult> & { payload?: SaveProfileResult } })
    const analyzeJobId = String(savePayload?.analyzeJobId || '').trim()
    if (!analyzeJobId) return true

    for (let i = 0; i < 16; i += 1) {
      const jobResponse = await getProfileAnalyzeJobStatus(analyzeJobId)
      const jobPayload = extractApiPayload<ProfileAnalyzeJobStatusResult>(jobResponse as { data: ApiResponse<ProfileAnalyzeJobStatusResult> & { payload?: ProfileAnalyzeJobStatusResult } })
      const status = String(jobPayload?.status || '').trim()
      if (status === 'succeeded') return true
      if (status === 'failed') return false
      await sleepMs(clampPollMs(jobPayload?.pollAfterMs, 900))
    }

    return false
  } catch {
    return false
  }
}

function resolveTaskResultKind(intent: DemoIntent): TaskOrchestratorResultCardPayload['kind'] {
  if (intent === 'resume_analysis') return 'resume_scores'
  if (intent === 'job_match') return 'job_match'
  if (intent === 'path_plan') return 'path_plan'
  return 'generic'
}

function buildTaskResultCardPayload(result: DemoResultPayload): TaskOrchestratorResultCardPayload {
  return {
    kind: resolveTaskResultKind(result.intent),
    title: result.title,
    summary: result.summary,
    scores: result.scores,
    match: result.match,
    pathStages: result.pathStages,
  }
}

function buildReportPolishApprovalResult(goalText = ''): DemoResultPayload | null {
  if (!isReportPolishLikeGoal(goalText)) return null

  const selectedReport = availableReports.value.find(item => item.reportId === selectedReportId.value)
    || availableReports.value[0]
    || null
  const reportId = String(selectedReport?.reportId || selectedReportId.value || '').trim()
  const reportTitle = String(selectedReport?.reportTitle || '').trim() || '当前生涯报告'

  const actions: TaskOrchestratorActionPayload[] = [
    {
      type: 'navigate',
      label: '打开生涯报告',
      route: '/report',
      intent: 'generic',
    },
  ]

  if (reportId) {
    actions.unshift({
      type: 'one_click_polish',
      label: '确认并执行一键润色',
      route: '/report',
      reportId,
      intent: 'generic',
      scope: {
        polishPayload: {
          description: '按AI评估建议自动润色核心章节',
          tone: 'encouraging',
          targetReader: '个人规划',
        },
      },
    })
  }

  return {
    intent: 'generic',
    title: '报告润色待审批',
    summary: reportId
      ? `已识别报告润色任务：${reportTitle}。请在正文下方审批按钮中确认后执行。`
      : '已识别报告润色任务，但当前缺少可用报告，请先进入报告页生成后再继续。',
    jumpRoute: '/report',
    jumpLabel: '打开报告页',
    actions,
  }
}

function buildFallbackDemoResult(intent: DemoIntent, goalText = ''): DemoResultPayload | null {
  if (intent === 'resume_analysis') {
    const scores: DemoScoreItem[] = [
      { dimension: '专业技能', score: 84 },
      { dimension: '证书能力', score: 79 },
      { dimension: '创新能力', score: 81 },
      { dimension: '内驱动力', score: 76 },
      { dimension: '学习能力', score: 88 },
      { dimension: '抗压能力', score: 74 },
      { dimension: '沟通表达', score: 83 },
      { dimension: '实习经验', score: 72 },
      { dimension: '语言能力', score: 77 },
      { dimension: '领导力', score: 70 },
      { dimension: '适应能力', score: 80 },
      { dimension: '执行稳定性', score: 82 },
    ]
    return {
      intent,
      title: '简历解析与12维能力评分',
      summary: '简历解析任务已完成，正在同步简历与画像评分数据。同步完成后可直接查看能力评估详情。',
      jumpRoute: '',
      jumpLabel: '',
      scores,
      actions: [],
    }
  }

  if (intent === 'job_match') {
    return {
      intent,
      title: '推荐与匹配结果',
      summary: '已完成推荐与匹配流程，已生成可视化匹配卡片，可继续查看职业规划详情。',
      jumpRoute: '/match',
      jumpLabel: '前往职业规划',
      match: {
        jobName: '前端开发工程师',
        jobFamily: 'Web应用开发',
        city: '上海',
        overallScore: 87,
        skillScore: 84,
        intentScore: 86,
        growthScore: 85,
      },
      actions: [
        {
          type: 'navigate',
          label: '查看匹配详情',
          route: '/match',
          intent: 'job_match',
        },
      ],
    }
  }

  if (intent === 'path_plan') {
    return {
      intent,
      title: 'AI职业路径规划',
      summary: '已完成路径规划流程，已生成阶段卡片，可在职业规划页继续查看路径图。',
      jumpRoute: '/match',
      jumpLabel: '查看详情',
      pathStages: [
        {
          stage: '阶段一（0-3个月）',
          title: '基础能力固化',
          detail: '补齐核心技能栈并完成2个可展示项目。',
        },
        {
          stage: '阶段二（4-6个月）',
          title: '场景能力拓展',
          detail: '承担复杂模块，形成性能优化与协作经验。',
        },
        {
          stage: '阶段三（7-12个月）',
          title: '岗位能力跃迁',
          detail: '沉淀端到端交付能力并冲刺目标岗位。',
        },
      ],
      actions: [
        {
          type: 'navigate',
          label: '查看职业路径详情',
          route: '/match',
          intent: 'path_plan',
        },
      ],
    }
  }

  const reportPolishResult = buildReportPolishApprovalResult(goalText)
  if (reportPolishResult) {
    return reportPolishResult
  }

  return {
    intent: 'generic',
    title: '任务执行结果',
    summary: '任务执行完成，已生成结论摘要。',
    jumpRoute: '',
    jumpLabel: '',
    actions: [],
  }
}

async function fetchRecommendationUntilSucceeded(topN = 6, maxRetry = 12) {
  for (let index = 0; index < maxRetry; index += 1) {
    const response = await getMatchRecommendations(topN)
    const data = extractApiPayload<MatchRecommendationsResult>(response as { data: ApiResponse<MatchRecommendationsResult> & { payload?: MatchRecommendationsResult } })
    const status = String(data?.recommendationStatus || '').trim()
    if (status === 'succeeded' && data?.bestMatch) {
      return data
    }
    if (status === 'failed') {
      return null
    }
    await sleepMs(clampPollMs(data?.pollAfterMs, 1200))
  }
  return null
}

async function ensureDemoRefinedRecommendations() {
  try {
    await refineMatchRecommendations({
      topN: 6,
      scope: {
        preferredJobKeywords: ['档案管理'],
        cityIntents: ['西安', '杭州'],
        benefits: ['双休', '五险一金'],
        salaryRange: {
          min: 8,
          max: 16,
        },
        includeSimilarJobs: true,
      },
    })
  } catch {
    // Ignore refine errors and continue with recommendation polling fallback.
  }

  return fetchRecommendationUntilSucceeded(6)
}

async function ensureDemoCareerPathByRecommendation(bestJobId: string) {
  const targetJobId = String(bestJobId || '').trim()
  if (!targetJobId) return false

  try {
    const createResponse = await autoPlanCareerPath({
      targetJobId,
      mode: 'balanced',
    })
    const createPayload = extractApiPayload<AutoPlanJobCreateResult>(createResponse as { data: ApiResponse<AutoPlanJobCreateResult> & { payload?: AutoPlanJobCreateResult } })
    const autoPlanJobId = String(createPayload?.autoPlanJobId || '').trim()
    if (!autoPlanJobId) return false

    for (let index = 0; index < 14; index += 1) {
      const statusResponse = await getAutoPlanCareerPathJobStatus(autoPlanJobId)
      const statusPayload = extractApiPayload<AutoPlanJobStatusResult>(statusResponse as { data: ApiResponse<AutoPlanJobStatusResult> & { payload?: AutoPlanJobStatusResult } })
      const status = String(statusPayload?.status || '').trim()
      if (status === 'succeeded') return true
      if (status === 'failed') return false
      await sleepMs(clampPollMs(statusPayload?.pollAfterMs, 1200))
    }

    return false
  } catch {
    return false
  }
}

async function ensureCareerReportFromLatestPath() {
  try {
    const latestPathResponse = await getLatestCareerPath()
    const latestPathData = extractApiPayload<LatestPathResult>(latestPathResponse as { data: ApiResponse<LatestPathResult> & { payload?: LatestPathResult } })
    const pathId = String(latestPathData?.latestPath?.pathId || '').trim()
    if (!pathId) return false

    const createResponse = await createCareerReportGenerateJob({
      pathId,
      overwriteLatest: true,
      overwriteSamePathReport: true,
    })
    const createPayload = extractApiPayload<{ reportJobId: string; status: string; pollAfterMs?: number }>(createResponse as { data: ApiResponse<{ reportJobId: string; status: string; pollAfterMs?: number }> & { payload?: { reportJobId: string; status: string; pollAfterMs?: number } } })
    const reportJobId = String(createPayload?.reportJobId || '').trim()
    if (!reportJobId) return false

    for (let index = 0; index < 14; index += 1) {
      const statusResponse = await getCareerReportGenerateJobStatus(reportJobId)
      const statusPayload = extractApiPayload<{
        reportJobId: string
        status: 'processing' | 'succeeded' | 'failed' | string
        pollAfterMs?: number
        result?: {
          report?: {
            reportId: string
          }
        }
      }>(statusResponse as {
        data: ApiResponse<{
          reportJobId: string
          status: 'processing' | 'succeeded' | 'failed' | string
          pollAfterMs?: number
          result?: {
            report?: {
              reportId: string
            }
          }
        }> & {
          payload?: {
            reportJobId: string
            status: 'processing' | 'succeeded' | 'failed' | string
            pollAfterMs?: number
            result?: {
              report?: {
                reportId: string
              }
            }
          }
        }
      })

      const status = String(statusPayload?.status || '').trim()
      if (status === 'succeeded') {
        emitCareerReportRefresh({
          reason: 'task-generated',
          reportId: String(statusPayload?.result?.report?.reportId || '').trim() || undefined,
        })
        return true
      }
      if (status === 'failed') {
        return false
      }

      await sleepMs(clampPollMs(statusPayload?.pollAfterMs, 1200))
    }

    return false
  } catch {
    return false
  }
}

async function buildLiveResumeResult() {
  try {
    const response = await getStudentProfile()
    const data = extractApiPayload<GetProfileResult>(response as { data: ApiResponse<GetProfileResult> & { payload?: GetProfileResult } })
    if (!data?.hasProfile || !data?.scores?.abilityScores) return null

    const scoreMap = data.scores.abilityScores
    const scores: DemoScoreItem[] = ABILITY_ORDER.map((key) => ({
      dimension: ABILITY_LABEL_MAP[key],
      score: normalizeScore(scoreMap[key]),
    }))
    const top3 = scores.slice().sort((left, right) => right.score - left.score).slice(0, 3)

    return {
      intent: 'resume_analysis' as const,
      title: '简历解析与12维能力评分',
      summary: top3.length
        ? `已同步最新能力评估，当前优势维度：${top3.map(item => `${item.dimension}${item.score}`).join(' / ')}。`
        : '已同步最新能力评估结果，可查看完整维度评分。',
      jumpRoute: '/student' as const,
      jumpLabel: '查看详情',
      scores,
      actions: [
        {
          type: 'navigate',
          label: '查看能力评估详情',
          route: '/student',
          intent: 'resume_analysis',
        },
      ],
    }
  } catch {
    return null
  }
}

async function buildLiveJobMatchResult() {
  try {
    const data = await ensureDemoRefinedRecommendations()
    if (!data?.bestMatch) return null

    const best = data.bestMatch
    const dimensionScores = best.dimensionScores
    const overallScore = normalizeScore(best.overallScore)
    const skillScore = normalizeScore(dimensionScores?.professionalSkill ?? best.professionalSkillMatchRate ?? overallScore, overallScore)
    const intentScore = normalizeScore(dimensionScores?.basicRequirement ?? overallScore, overallScore)
    const growthScore = normalizeScore(dimensionScores?.developmentPotential ?? overallScore, overallScore)
    const jobFamily = Array.isArray(best.industryTags) && best.industryTags.length
      ? String(best.industryTags[0] || '')
      : String(best.level || '岗位推荐')

    return {
      intent: 'job_match' as const,
      title: '推荐与匹配结果',
      summary: `已同步最新匹配结果，当前最高匹配岗位为“${best.jobName}”，综合匹配度${overallScore}。`,
      jumpRoute: '/match' as const,
      jumpLabel: '前往职业规划',
      match: {
        jobId: best.jobId,
        jobName: best.jobName,
        jobFamily: jobFamily || '岗位推荐',
        city: best.city || '不限',
        overallScore,
        skillScore,
        intentScore,
        growthScore,
      },
      actions: [
        {
          type: 'navigate',
          label: '查看匹配详情',
          route: '/match',
          intent: 'job_match',
        },
        {
          type: 'apply_recommended_job',
          label: `应用推荐岗位：${best.jobName}`,
          route: '/jobs',
          keyword: best.jobName,
          autoSelect: true,
          recommendedJobId: best.jobId,
          intent: 'job_match',
        },
      ],
    }
  } catch {
    return null
  }
}

function buildPathStagesFromDetail(detail: CareerPathDetailResult) {
  const source = detail.savedPath || detail.draft
  if (!source) return [] as DemoPathStage[]

  const evaluation = source.evaluation
  const stagePlans = Array.isArray(evaluation?.stagePlans) ? evaluation.stagePlans : []
  const normalizedByPlan = stagePlans.slice(0, 3).map((stage, index) => {
    const goals = Array.isArray(stage.goals) ? stage.goals.filter(Boolean) : []
    const tasks = Array.isArray(stage.suggestedTasks)
      ? stage.suggestedTasks.map(item => String(item?.title || '').trim()).filter(Boolean)
      : []
    const detailParts = [
      String(stage.cycle || '').trim() ? `周期：${String(stage.cycle || '').trim()}` : '',
      goals.length ? `目标：${goals.slice(0, 2).join('；')}` : '',
      tasks.length ? `任务：${tasks.slice(0, 2).join('；')}` : '',
    ].filter(Boolean)

    return {
      stage: String(stage.stageLabel || stage.stage || `阶段${index + 1}`),
      title: goals[0] || String(stage.stageLabel || `阶段${index + 1}`),
      detail: detailParts.join('；') || '已生成阶段建议。',
    }
  })
  if (normalizedByPlan.length) return normalizedByPlan

  const nodes = Array.isArray(source.pathNodes) ? source.pathNodes : []
  return nodes.slice(0, 3).map((node, index) => {
    const nodeName = String(node?.jobName || node?.jobId || '').trim() || `路径节点${index + 1}`
    return {
      stage: `阶段${index + 1}`,
      title: nodeName,
      detail: `围绕“${nodeName}”推进能力积累与岗位跃迁。`,
    }
  })
}

async function buildLivePathPlanResult() {
  try {
    const recommendation = await ensureDemoRefinedRecommendations()
    const bestJobId = String(recommendation?.bestMatch?.jobId || '').trim()
    if (bestJobId) {
      await ensureDemoCareerPathByRecommendation(bestJobId)
    }

    const reportReady = await ensureCareerReportFromLatestPath()

    const latestResponse = await getLatestCareerPath()
    const latestData = extractApiPayload<LatestPathResult>(latestResponse as { data: ApiResponse<LatestPathResult> & { payload?: LatestPathResult } })
    if (!latestData) return null

    const pathId = String(latestData.latestPath?.pathId || '').trim()
    const draftId = String(latestData.draft?.draftId || '').trim()
    if (!pathId && !draftId) return null

    const detailResponse = await getCareerPathDetail(pathId ? { pathId } : { draftId })
    const detailData = extractApiPayload<CareerPathDetailResult>(detailResponse as { data: ApiResponse<CareerPathDetailResult> & { payload?: CareerPathDetailResult } })
    if (!detailData) return null

    const source = detailData.savedPath || detailData.draft
    const evaluation = source?.evaluation
    const feasibilityScore = normalizeScore(evaluation?.feasibilityScore)
    const readinessScore = normalizeScore(evaluation?.readinessScore)
    const recommendationScore = normalizeScore(evaluation?.recommendationScore)
    const pathStages = buildPathStagesFromDetail(detailData)
    const pathName = String(detailData.savedPath?.pathName || '职业路径草案')

    return {
      intent: 'path_plan' as const,
      title: 'AI职业路径规划',
      summary: `已同步“${pathName}”路径评估：可行性${feasibilityScore}，就绪度${readinessScore}，推荐度${recommendationScore}。`,
      jumpRoute: '/match' as const,
      jumpLabel: '查看路径详情',
      pathStages,
      actions: [
        {
          type: 'navigate',
          label: '查看职业路径详情',
          route: '/match',
          intent: 'path_plan',
        },
        ...(reportReady
          ? [
              {
                type: 'navigate',
                label: '查看生涯报告',
                route: '/report',
                intent: 'path_plan',
              } as TaskOrchestratorActionPayload,
            ]
          : []),
      ],
    }
  } catch {
    return null
  }
}

async function buildLiveDemoResult(intent: DemoIntent, goalText = '') {
  if (intent === 'resume_analysis') return buildLiveResumeResult()
  if (intent === 'job_match') return buildLiveJobMatchResult()
  if (intent === 'path_plan') return buildLivePathPlanResult()
  const reportPolishResult = buildReportPolishApprovalResult(goalText)
  if (reportPolishResult) return reportPolishResult
  return null
}

function resolveIntentMessageTitle(intent: DemoIntent) {
  if (intent === 'resume_analysis') return '简历解析与能力评估'
  if (intent === 'job_match') return '岗位匹配推荐'
  if (intent === 'path_plan') return '职业路径规划'
  return '任务执行'
}

function resolveIntentProgressHint(intent: DemoIntent, phase: 'start' | 'update' | 'done') {
  if (phase === 'done') return '结果已生成，正在同步结果卡片。'
  if (intent === 'resume_analysis') return '正在解析简历内容并更新能力评估。'
  if (intent === 'job_match') return '正在执行岗位匹配与推荐排序。'
  if (intent === 'path_plan') return '正在生成阶段式职业路径与里程碑。'
  if (isReportPolishLikeGoal(taskDetail.value?.goalInput || taskDetail.value?.prompt || '')) {
    return '正在等待审批确认，确认后将继续执行报告润色。'
  }
  return '正在拆解目标并推进任务执行。'
}

function buildAssistantContent(
  task: HomeAgentRuntimeTaskDetail | null,
  intent: DemoIntent,
  phase: 'start' | 'update' | 'done',
  resultOverride?: DemoResultPayload | null,
) {
  if (!task) return '任务处理中...'
  const lines: string[] = []
  lines.push(`## ${resolveIntentMessageTitle(intent)}`)
  lines.push(`- 状态：${resolveTaskStatusLabel(task.status)}`)
  lines.push(`- 进度：${buildMarkdownProgressBar(task.progress)}`)
  lines.push(`- 任务类型：${resolveIntentLabel(intent)}`)
  lines.push(`- 执行说明：${resolveIntentProgressHint(intent, phase)}`)

  if (phase === 'done') {
    const demo = resultOverride || taskResultByTaskId.value[task.taskId] || buildFallbackDemoResult(intent, task.goalInput || task.prompt || '')
    if (demo) {
      lines.push('')
      lines.push(`### ${demo.title}`)
      lines.push(demo.summary)
      if (demo.intent === 'resume_analysis' && demo.scores?.length) {
        const top3 = demo.scores.slice().sort((a, b) => b.score - a.score).slice(0, 3)
        lines.push(`- Top3能力维度：${top3.map(item => `${item.dimension} ${item.score}`).join(' / ')}`)
      }
      if (demo.intent === 'job_match' && demo.match) {
        lines.push(`- 最高匹配岗位：${demo.match.jobName}（综合匹配度 ${demo.match.overallScore}）`)
      }
      if (demo.intent === 'path_plan' && demo.pathStages?.length) {
        lines.push(`- 路径阶段：${demo.pathStages.map(item => item.stage).join(' → ')}`)
      }
      if (Array.isArray(demo.actions) && demo.actions.length) {
        lines.push('- 已同步可视化结果卡，可使用下方快捷操作继续下一步。')
      } else {
        lines.push('- 数据同步中，完成后将自动刷新可视化结果卡与快捷操作。')
      }
    }
  } else {
    lines.push(`- 目标：${String(task.goalInput || task.prompt || '').trim() || '按任务计划执行中'}`)
  }

  return lines.join('\n')
}

function parseSsePayload(raw: string) {
  try {
    return JSON.parse(raw) as TaskStreamPayload
  } catch {
    return null
  }
}

function closeTaskStream() {
  if (taskStreamRef.value) {
    taskStreamRef.value.close()
    taskStreamRef.value = null
  }
}

function clampPanelPosition() {
  const maxX = Math.max(12, window.innerWidth - panelWidth.value - 12)
  const maxY = Math.max(12, window.innerHeight - panelHeight.value - 12)
  panelX.value = Math.min(Math.max(12, panelX.value), maxX)
  panelY.value = Math.min(Math.max(12, panelY.value), maxY)
}

function disconnectPanelResizeObserver() {
  if (panelResizeObserver) {
    panelResizeObserver.disconnect()
    panelResizeObserver = null
  }
}

function syncPanelSizeFromDom() {
  const panel = panelRef.value
  if (!panel) return

  const rect = panel.getBoundingClientRect()
  const nextWidth = Math.round(rect.width)
  const nextHeight = Math.round(rect.height)

  if (Number.isFinite(nextWidth) && nextWidth > 0) {
    panelWidth.value = nextWidth
  }
  if (Number.isFinite(nextHeight) && nextHeight > 0) {
    panelHeight.value = nextHeight
  }
}

function isResizeHandlePointer(event: MouseEvent) {
  const panel = panelRef.value
  if (!panel) return false

  const rect = panel.getBoundingClientRect()
  const offsetRight = rect.right - event.clientX
  const offsetBottom = rect.bottom - event.clientY
  const HANDLE_SIZE = 28

  return offsetRight >= 0
    && offsetBottom >= 0
    && offsetRight <= HANDLE_SIZE
    && offsetBottom <= HANDLE_SIZE
}

function handlePanelMouseDown(event: MouseEvent) {
  if (!isResizeHandlePointer(event)) return
  resizingByHandle.value = true

  const onMouseUp = () => {
    resizingByHandle.value = false
    syncPanelSizeFromDom()
    clampPanelPosition()
  }

  window.addEventListener('mouseup', onMouseUp, { once: true })
}

function ensurePanelResizeObserver() {
  if (!panelRef.value || typeof ResizeObserver === 'undefined') return
  if (panelResizeObserver) return

  panelResizeObserver = new ResizeObserver((entries) => {
    if (!resizingByHandle.value) return

    const entry = entries[0]
    if (!entry) return
    const nextWidth = Math.round(entry.contentRect.width)
    const nextHeight = Math.round(entry.contentRect.height)

    if (Number.isFinite(nextWidth) && Math.abs(nextWidth - panelWidth.value) > 1) {
      panelWidth.value = nextWidth
    }
    if (Number.isFinite(nextHeight) && Math.abs(nextHeight - panelHeight.value) > 1) {
      panelHeight.value = nextHeight
    }
    clampPanelPosition()
  })

  panelResizeObserver.observe(panelRef.value)
}

function normalizeTraceStatus(task: HomeAgentRuntimeTaskDetail | null) {
  const status = String(task?.status || '')
  if (status === 'completed') return 'succeeded'
  if (status === 'failed' || status === 'cancelled') return 'failed'
  if (status === 'rolled_back') return 'failed'
  return 'processing'
}

function normalizeTraceStepStatus(status: string) {
  const value = String(status || '')
  if (value === 'succeeded' || value === 'completed') return 'succeeded'
  if (value === 'failed') return 'failed'
  if (value === 'processing' || value === 'in_progress') return 'processing'
  if (value === 'rolled_back') return 'failed'
  return 'pending'
}

function buildTraceFromTask(task: HomeAgentRuntimeTaskDetail | null) {
  const taskData = task
  if (!taskData) return null

  const planTraceSteps = (taskData.planSteps || []).map(step => ({
    stepId: `plan_${step.stepId}`,
    type: step.type,
    title: step.title,
    status: normalizeTraceStepStatus(step.status),
    detail: step.expectedResult || step.detail,
  }))

  const toolTraceSteps = (taskData.toolExecutions || []).map(run => ({
    stepId: `tool_${run.runId}`,
    type: 'tool',
    title: run.toolName,
    status: normalizeTraceStepStatus(run.status),
    detail: run.outputSummary || run.inputSummary,
    toolName: run.toolName,
    inputSummary: run.inputSummary,
    outputSummary: run.outputSummary,
  }))

  const allSteps = [...planTraceSteps, ...toolTraceSteps]
  const activeStep = allSteps.find(step => step.status === 'processing') || null

  return {
    traceVersion: 'task-orchestrator-v2',
    mode: 'task_orchestration',
    status: normalizeTraceStatus(taskData),
    startedAt: taskData.createdAt,
    finishedAt: taskData.finishedAt || null,
    activeStepId: activeStep?.stepId || null,
    steps: allSteps,
    tasks: (taskData.planSteps || []).map(step => ({
      taskId: step.stepId,
      title: step.title,
      status: step.status,
    })),
  }
}

function resolveTaskStatusLabel(status: string) {
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

function resolveTaskStatusClass(status: string) {
  const value = String(status || '')
  if (value === 'completed') return 'task-status-done'
  if (value === 'in_progress') return 'task-status-running'
  if (value === 'queued') return 'task-status-queued'
  if (value === 'paused') return 'task-status-paused'
  if (value === 'rolled_back') return 'task-status-rolled-back'
  if (value === 'cancelled' || value === 'failed') return 'task-status-failed'
  return 'task-status-queued'
}

function resolveGoalConclusionLabel() {
  const status = String(taskDetail.value?.goalConclusion?.status || '')
  if (status === 'achieved') return '目标达成'
  if (status === 'not_achieved') return '目标未达成'
  if (status === 'rolled_back') return '已回滚'
  return '执行中'
}

function resolveGoalConclusionClass() {
  const status = String(taskDetail.value?.goalConclusion?.status || '')
  if (status === 'achieved') return 'goal-conclusion-achieved'
  if (status === 'not_achieved') return 'goal-conclusion-not-achieved'
  if (status === 'rolled_back') return 'goal-conclusion-rolled-back'
  return 'goal-conclusion-pending'
}

function resolveStepStatusLabel(status: string) {
  const value = String(status || '')
  if (value === 'succeeded' || value === 'completed') return '已完成'
  if (value === 'processing' || value === 'in_progress') return '进行中'
  if (value === 'failed') return '失败'
  if (value === 'rolled_back') return '已回滚'
  return '待执行'
}

function resolveStepStatusIcon(status: string) {
  const value = String(status || '')
  if (value === 'succeeded' || value === 'completed') return CircleCheckFilled
  if (value === 'processing' || value === 'in_progress') return Loading
  return WarningFilled
}

function resolveQueueStatusLabel(status: string) {
  const value = String(status || '')
  if (value === 'running') return '执行中'
  if (value === 'done') return '已完成'
  if (value === 'failed') return '失败'
  return '待执行'
}

function resolveQueueStatusClass(status: string) {
  const value = String(status || '')
  if (value === 'running') return 'task-queue-running'
  if (value === 'done') return 'task-queue-done'
  if (value === 'failed') return 'task-queue-failed'
  return 'task-queue-pending'
}

function reconcileSelectedPreset() {
  if (!selectedPresetId.value) return
  const exists = presets.value.some(item => item.presetId === selectedPresetId.value)
  if (!exists) {
    selectedPresetId.value = ''
  }
}

function pushRouteRefreshEvent(intent: DemoIntent) {
  if (intent === 'resume_analysis' && route.path === '/student') {
    emitTaskOrchestratorRouteRefresh({ routePath: '/student', intent })
    return
  }
  if ((intent === 'job_match' || intent === 'path_plan') && route.path === '/match') {
    emitTaskOrchestratorRouteRefresh({ routePath: '/match', intent })
  }
}

async function loadAvailableReports() {
  try {
    const response = await getCareerReportList()
    const payload = response.data as ApiResponse<{
      list?: Array<{ reportId?: string; reportTitle?: string }>
    }>
    if (!isSuccessCode(payload.code)) {
      availableReports.value = []
      selectedReportId.value = ''
      return
    }

    const list = Array.isArray(payload.data?.list)
      ? payload.data.list
          .map(item => ({
            reportId: String(item?.reportId || '').trim(),
            reportTitle: String(item?.reportTitle || '').trim() || '未命名报告',
          }))
          .filter(item => item.reportId)
      : []

    availableReports.value = list
    if (!list.length) {
      selectedReportId.value = ''
      return
    }

    if (!list.some(item => item.reportId === selectedReportId.value)) {
      selectedReportId.value = list[0].reportId
    }
  } catch {
    availableReports.value = []
    selectedReportId.value = ''
  }
}

async function loadOverview(silent = false) {
  if (!silent) {
    loading.value = true
  }
  try {
    const [overviewResponse] = await Promise.all([
      getHomeAgentRuntimeOverview(),
      loadAvailableReports(),
    ])
    const payload = overviewResponse.data as ApiResponse<HomeAgentRuntimeOverviewResult>
    if (isSuccessCode(payload.code) && payload.data) {
      overview.value = payload.data
      reconcileSelectedPreset()
    }
  } catch (error) {
    if (!silent) {
      ElMessage.error(error instanceof Error ? error.message : '任务编排数据加载失败')
    }
  } finally {
    if (!silent) {
      loading.value = false
    }
  }
}

function openTaskWindow(payload?: TaskOrchestratorOpenPayload | null) {
  openPayload.value = payload || null
  visible.value = true
  const initialGoal = String(payload?.initialGoal || '').trim()
  if (initialGoal) {
    goalDraft.value = initialGoal
  }

  if (payload?.autoLaunch && initialGoal && !creatingTask.value) {
    const intent = inferIntent(initialGoal, false)
    void launchTaskByGoal({
      goalText: initialGoal,
      intent,
      presetId: resolveIntentPresetId(intent),
      source: 'quick',
      skipConfirm: true,
    })
  }

  const anchorX = Number(payload?.anchorX)
  const anchorY = Number(payload?.anchorY)
  if (Number.isFinite(anchorX) && Number.isFinite(anchorY)) {
    panelX.value = Math.round(anchorX)
    panelY.value = Math.round(anchorY)
    clampPanelPosition()
  }

  nextTick(() => {
    ensurePanelResizeObserver()
    clampPanelPosition()
  })

  loadOverview(true).catch(() => {})
}

function onTaskOpenEvent(event: Event) {
  const custom = event as CustomEvent<TaskOrchestratorOpenPayload>
  openTaskWindow(custom.detail || null)
}

function handleOpenFromBubble() {
  openTaskWindow({
    routePath: route.path,
    pageTitle: String(route.meta.title || ''),
    source: 'home',
  })
}

function handleClosePanel() {
  resizingByHandle.value = false
  disconnectPanelResizeObserver()
  visible.value = false
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

function triggerFilePicker() {
  fileInputRef.value?.click()
}

function validateResumeFile(file: File, source: 'panel' | 'queue' = 'panel') {
  const extension = String(file.name || '').split('.').pop()?.toLowerCase() || ''
  if (!RESUME_ALLOWED_EXTENSIONS.includes(extension)) {
    ElMessage.error('仅支持pdf/jpg/jpeg/png/docx/doc格式')
    return false
  }
  if (file.size > RESUME_MAX_FILE_SIZE) {
    const prefix = source === 'queue' ? '队列附件' : '简历文件'
    ElMessage.error(`${prefix}大小不能超过10MB`)
    return false
  }
  return true
}

function handleFileChange(event: Event) {
  const input = event.target as HTMLInputElement
  const file = Array.from(input.files || [])[0] || null
  if (!file) {
    selectedFiles.value = []
    return
  }

  if (!validateResumeFile(file, 'panel')) {
    selectedFiles.value = []
    if (input) {
      input.value = ''
    }
    return
  }

  selectedFiles.value = [file]
}

function removeSelectedFile() {
  selectedFiles.value = []
  if (fileInputRef.value) {
    fileInputRef.value.value = ''
  }
}

function resolveControlDescriptor(action: HomeAgentRuntimeTaskControlAction) {
  const descriptors = Array.isArray(taskDetail.value?.controlActions) ? taskDetail.value?.controlActions : []
  return descriptors.find(item => String(item.action || '') === action) || null
}

function canControl(action: HomeAgentRuntimeTaskControlAction) {
  const descriptor = resolveControlDescriptor(action)
  return Boolean(descriptor?.enabled)
}

function controlExpectedOutcome(action: HomeAgentRuntimeTaskControlAction) {
  return String(resolveControlDescriptor(action)?.expectedOutcome || '').trim() || '任务状态将更新。'
}

function controlActionLabel(action: HomeAgentRuntimeTaskControlAction) {
  if (action === 'pause') return '暂停'
  if (action === 'resume') return '继续'
  if (action === 'retry') return '重试'
  if (action === 'rollback') return '回滚'
  if (action === 'cancel') return '取消'
  return '执行'
}

function markQueueItemStatus(itemId: string, status: 'pending' | 'running' | 'done' | 'failed') {
  if (!itemId) return
  queueStatusByItemId.value = {
    ...queueStatusByItemId.value,
    [itemId]: status,
  }
}

function resolveNextPendingQueueItem() {
  return goalQueue.value.find(item => (queueStatusByItemId.value[item.itemId] || 'pending') === 'pending') || null
}

function upsertSessionTaskMessages(
  phase: 'start' | 'update' | 'done',
  task: HomeAgentRuntimeTaskDetail,
  userText: string | undefined,
  assistantText: string,
  assistantStatus: 'processing' | 'succeeded' | 'failed',
  actions?: TaskOrchestratorActionPayload[],
  taskResultCard?: TaskOrchestratorResultCardPayload | null,
) {
  const sessionId = String(linkedSessionId.value || '').trim()
  if (!sessionId) return

  let startUserCreatedAt = ''
  if (phase === 'start' && userText) {
    startUserCreatedAt = new Date().toISOString()
    const userMessage: HomeMessage = {
      messageId: `task_user_${task.taskId}`,
      role: 'user',
      content: userText,
      status: 'succeeded',
      createdAt: startUserCreatedAt,
      fileNames: [],
      actions: [],
      agentTrace: null,
    }
    upsertTaskTranscriptMessage(sessionId, userMessage)
  }

  const assistantCreatedAt = phase === 'start' && startUserCreatedAt
    ? new Date(Date.parse(startUserCreatedAt) + 1).toISOString()
    : String(task.updatedAt || new Date().toISOString())

  const assistantMessage: HomeMessage = {
    messageId: `task_assistant_${task.taskId}`,
    role: 'assistant',
    content: assistantText,
    status: assistantStatus,
    createdAt: assistantCreatedAt,
    fileNames: [],
    actions: Array.isArray(actions) ? actions : [],
    taskResultCard: taskResultCard || null,
    agentTrace: buildTraceFromTask(task),
  }
  upsertTaskTranscriptMessage(sessionId, assistantMessage)
}

function emitTaskStreamMessage(phase: 'start' | 'update' | 'done', task: HomeAgentRuntimeTaskDetail, userText?: string) {
  const intent = taskIntentByTaskId.value[task.taskId] || inferIntent(task.goalInput || task.prompt || '', false)
  const result = phase === 'done' ? taskResultByTaskId.value[task.taskId] || null : null
  const assistantText = buildAssistantContent(task, intent, phase, result)
  const actions = result?.actions || []
  const taskResultCard = result ? buildTaskResultCardPayload(result) : null
  const assistantStatus = phase === 'done' && (task.status === 'failed' || task.status === 'cancelled')
    ? 'failed'
    : phase === 'done'
      ? 'succeeded'
      : 'processing'

  upsertSessionTaskMessages(phase, task, userText, assistantText, assistantStatus, actions, taskResultCard)

  const payload: {
    taskId: string
    sessionId?: string
    phase: 'start' | 'update' | 'done'
    userMessageId: string
    userText?: string
    assistantMessageId: string
    assistantText: string
    assistantStatus: 'processing' | 'succeeded' | 'failed'
    actions?: TaskOrchestratorActionPayload[]
    taskResultCard?: TaskOrchestratorResultCardPayload | null
    agentTrace: Record<string, unknown> | null
  } = {
    taskId: task.taskId,
    sessionId: linkedSessionId.value || undefined,
    phase,
    userMessageId: `task_user_${task.taskId}`,
    userText,
    assistantMessageId: `task_assistant_${task.taskId}`,
    assistantText,
    assistantStatus,
    agentTrace: buildTraceFromTask(task) as unknown as Record<string, unknown>,
  }

  if (actions.length) {
    payload.actions = actions
  }
  if (taskResultCard) {
    payload.taskResultCard = taskResultCard
  }

  emitTaskOrchestratorStream(payload)
}

async function ensureLinkedSession(goalText = '', options: { reuseExisting?: boolean } = {}) {
  const currentSessionId = String(linkedSessionId.value || '').trim()
  if (options.reuseExisting && currentSessionId) {
    setTaskSessionId(currentSessionId)
    return currentSessionId
  }

  const createResponse = await createHomeSession()
  const createPayload = createResponse.data as ApiResponse<{ sessionId: string }>
  if (!isSuccessCode(createPayload.code) || !createPayload.data?.sessionId) {
    throw new Error(createPayload.msg || '创建会话失败')
  }

  const sessionId = String(createPayload.data.sessionId)
  linkedSessionId.value = sessionId
  setTaskSessionId(sessionId)
  clearTaskTranscript(sessionId)
  const normalizedGoal = String(goalText || '').trim()
  const sessionTitle = normalizedGoal
    ? `任务编排 · ${normalizedGoal.slice(0, 20)}`
    : '任务编排会话'
  await updateHomeSession(sessionId, { title: sessionTitle })

  emitHomeAssistantSessionRefresh({
    sessionId,
    reason: 'task-created',
  })

  return sessionId
}

async function launchTaskByGoal(payload: {
  goalText: string
  intent: DemoIntent
  presetId?: string
  source: 'manual' | 'quick' | 'queue'
  queueItemId?: string
  resumeFile?: File | null
  skipConfirm?: boolean
  confirmTitle?: string
}) {
  if (creatingTask.value) return false

  const goalText = String(payload.goalText || '').trim()
  if (!goalText) {
    ElMessage.warning('请先输入任务目标')
    return false
  }

  const intent = payload.intent
  const resumeFile = payload.resumeFile || null
  if (intent === 'resume_analysis' && !resumeFile) {
    ElMessage.warning(payload.source === 'queue' ? '该队列任务缺少简历文件，请先在该任务右侧上传后再执行。' : '简历解析任务需要上传简历文件。')
    return false
  }
  if (intent === 'resume_analysis' && resumeFile && !validateResumeFile(resumeFile, payload.source === 'queue' ? 'queue' : 'panel')) {
    return false
  }

  if (queueRunning.value && payload.source !== 'queue') {
    try {
      await ElMessageBox.confirm('当前已有任务序列在执行，是否插入到运行队列中？', '队列执行中', {
        confirmButtonText: '确认插入',
        cancelButtonText: '取消',
        type: 'warning',
        lockScroll: false,
      })
    } catch {
      return false
    }

    const itemId = `goal_${Date.now()}_${Math.random().toString(36).slice(2, 8)}`
    goalQueue.value = [
      ...goalQueue.value,
      {
        itemId,
        goalText,
        intent,
        source: payload.source === 'quick' ? 'quick' : 'manual',
        presetId: String(payload.presetId || '').trim() || resolveIntentPresetId(intent),
        resumeFileName: resumeFile?.name || '',
      },
    ]
    markQueueItemStatus(itemId, 'pending')
    if (resumeFile) {
      setQueueItemResumeFile(itemId, resumeFile)
    }

    ElMessage.success('已插入当前运行队列末尾，将在现有任务完成后自动执行。')
    return true
  }

  const fileNamesForTask = resumeFile ? [resumeFile.name] : []
  const expectedOutcome = buildFallbackDemoResult(intent, goalText)?.summary || '任务将产出执行结论和行动清单。'
  if (!payload.skipConfirm) {
    try {
      await ElMessageBox.confirm(`目标：${goalText}\n\n预期结果：${expectedOutcome}`, payload.confirmTitle || '确认启动任务', {
        confirmButtonText: '确认启动',
        cancelButtonText: '取消',
        type: 'info',
        lockScroll: false,
      })
    } catch {
      return false
    }
  }

  creatingTask.value = true
  try {
    if (shouldForceTaskExecutionFailure()) {
      throw new Error(TASK_EXECUTION_FAILURE_MSG)
    }

    const sessionId = await ensureLinkedSession(goalText, { reuseExisting: payload.source === 'queue' })
    const response = await createHomeAgentTask({
      presetId: String(payload.presetId || '').trim(),
      prompt: goalText,
      pageContext: {
        routePath: route.path,
        pageTitle: String(route.meta.title || ''),
        contextPrompt: String(openPayload.value?.initialGoal || ''),
        data: {
          sessionId,
          fileNames: fileNamesForTask,
          intent,
          source: payload.source,
          queueItemId: payload.queueItemId || '',
        },
      },
    })

    const resultPayload = response.data as ApiResponse<{ task: HomeAgentRuntimeTaskDetail }>
    if (!isSuccessCode(resultPayload.code) || !resultPayload.data?.task) {
      throw new Error(resultPayload.msg || '任务启动失败')
    }

    const task = resultPayload.data.task
    taskDetail.value = task
    latestDemoResult.value = null
    taskIntentByTaskId.value = {
      ...taskIntentByTaskId.value,
      [task.taskId]: intent,
    }

    if (payload.queueItemId) {
      taskQueueItemByTaskId.value = {
        ...taskQueueItemByTaskId.value,
        [task.taskId]: payload.queueItemId,
      }
      markQueueItemStatus(payload.queueItemId, 'running')
    }

    if (resumeFile) {
      taskResumeFileByTaskId.value = {
        ...taskResumeFileByTaskId.value,
        [task.taskId]: resumeFile,
      }
    }

    let startUserText: string | undefined
    if (payload.source === 'queue') {
      if (!queueStartMessageSent.value) {
        startUserText = `任务目标：${goalText}`
        queueStartMessageSent.value = true
      }
    } else {
      startUserText = `任务目标：${goalText}`
    }

    emitTaskStreamMessage('start', task, startUserText)
    await startTaskEventStream(task)
    await loadOverview(true)
    ElMessage.success(`已启动任务：${resolveIntentLabel(intent)}`)
    return true
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '任务启动失败')
    if (payload.queueItemId) {
      markQueueItemStatus(payload.queueItemId, 'failed')
    }
    return false
  } finally {
    creatingTask.value = false
  }
}

async function handleLaunchTask() {
  const goalText = String(goalDraft.value || '').trim()
  const intent = inferIntent(goalText, selectedFiles.value.length > 0)
  const presetId = resolveIntentPresetId(intent)
  const resumeFile = selectedFiles.value[0] || null

  const started = await launchTaskByGoal({
    goalText,
    intent,
    presetId,
    source: 'manual',
    resumeFile,
    confirmTitle: `启动任务：${resolveIntentLabel(intent)}`,
  })
  if (started) {
    goalDraft.value = ''
  }
}

async function handleQuickLaunchPreset(preset: HomeAgentRuntimePreset) {
  if (preset.presetId === 'resume_parse_eval' && !selectedFiles.value[0]) {
    ElMessage.warning('请先上传简历文件，再发起“解析并评估简历”任务。')
    return
  }

  if (preset.presetId === 'report_polish_approval') {
    if (!hasAvailableReports.value) {
      ElMessage.warning('当前没有可用报告，请先在报告页生成报告。')
      return
    }
    if (!selectedReportId.value) {
      ElMessage.warning('请选择一份报告后再发起润色任务。')
      return
    }
  }

  const goalText = String(preset.suggestedPrompt || preset.goal || '').trim()
  if (!goalText) return

  const reportSuffix = preset.presetId === 'report_polish_approval' && selectedReportId.value
    ? `（报告ID：${selectedReportId.value}）`
    : ''
  await launchTaskByGoal({
    goalText: `${goalText}${reportSuffix}`,
    intent: inferIntent(goalText, false),
    presetId: preset.presetId,
    source: 'quick',
    resumeFile: selectedFiles.value[0] || null,
    confirmTitle: `快捷发起：${preset.title}`,
  })
}

function handleAddGoalToQueue() {
  const goalText = String(goalDraft.value || '').trim()
  if (!goalText) {
    ElMessage.warning('请先输入一个目标，再添加到顺序队列')
    return
  }

  const itemId = `goal_${Date.now()}_${Math.random().toString(36).slice(2, 8)}`
  const intent = inferIntent(goalText, selectedFiles.value.length > 0)
  const resumeFile = selectedFiles.value[0] || null
  goalQueue.value = [
    ...goalQueue.value,
    {
      itemId,
      goalText,
      intent,
      source: 'manual',
      presetId: resolveIntentPresetId(intent),
      resumeFileName: resumeFile?.name || '',
    },
  ]
  if (resumeFile) {
    setQueueItemResumeFile(itemId, resumeFile)
  }
  markQueueItemStatus(itemId, 'pending')
  goalDraft.value = ''
  ElMessage.success('已加入顺序队列')
}

function handleAddDemoPipelineQueue() {
  if (queueRunning.value) return

  const demoItems: GoalQueueItem[] = [
    {
      itemId: `goal_${Date.now()}_${Math.random().toString(36).slice(2, 8)}`,
      goalText: '这是我的简历，请先解析并完成12维能力评估。',
      intent: 'resume_analysis',
      source: 'demo',
      presetId: 'resume_parse_eval',
      resumeFileName: selectedFiles.value[0]?.name || '',
    },
    {
      itemId: `goal_${Date.now()}_${Math.random().toString(36).slice(2, 8)}`,
      goalText: '基于能力评估结果，帮我推荐并匹配最适合的岗位。',
      intent: 'job_match',
      source: 'demo',
      presetId: 'report_polish_approval',
    },
    {
      itemId: `goal_${Date.now()}_${Math.random().toString(36).slice(2, 8)}`,
      goalText: '基于当前最佳匹配岗位，生成职业路径规划与阶段建议。',
      intent: 'path_plan',
      source: 'demo',
      presetId: 'report_polish_approval',
    },
  ]

  goalQueue.value = [...goalQueue.value, ...demoItems]
  demoItems.forEach((item, index) => {
    markQueueItemStatus(item.itemId, 'pending')
    if (index === 0 && selectedFiles.value[0]) {
      setQueueItemResumeFile(item.itemId, selectedFiles.value[0])
    }
  })

  ElMessage.success('已添加演示三连任务到顺序队列')
}

function handleRemoveQueueItem(itemId: string) {
  if (!itemId || queueRunning.value) return
  goalQueue.value = goalQueue.value.filter(item => item.itemId !== itemId)
  const next = { ...queueStatusByItemId.value }
  delete next[itemId]
  queueStatusByItemId.value = next
  setQueueItemResumeFile(itemId, null)
}

function handleClearQueue() {
  if (queueRunning.value) return
  goalQueue.value = []
  queueStatusByItemId.value = {}
  queueResumeFileByItemId.value = {}
  queueStartMessageSent.value = false
}

async function launchNextQueueItem() {
  const nextItem = resolveNextPendingQueueItem()
  if (!nextItem) {
    queueRunning.value = false
    queueStartMessageSent.value = false
    ElMessage.success('顺序队列执行完成')
    return
  }

  const started = await launchTaskByGoal({
    goalText: nextItem.goalText,
    intent: nextItem.intent,
    presetId: nextItem.presetId,
    source: 'queue',
    queueItemId: nextItem.itemId,
    resumeFile: queueResumeFileByItemId.value[nextItem.itemId] || null,
    skipConfirm: true,
  })

  if (!started) {
    markQueueItemStatus(nextItem.itemId, 'failed')
    queueRunning.value = false
    queueStartMessageSent.value = false
    ElMessage.error(`顺序执行已中断：${TASK_EXECUTION_FAILURE_MSG}`)
    return
  }
}

async function handleStartQueue() {
  if (queueRunning.value || !goalQueue.value.length) return

  try {
    await ElMessageBox.confirm(`即将顺序执行 ${goalQueue.value.length} 个任务目标。执行中会自动串行推进。`, '开始顺序执行', {
      confirmButtonText: '开始执行',
      cancelButtonText: '取消',
      type: 'info',
      lockScroll: false,
    })
  } catch {
    return
  }

  queueRunning.value = true
  queueStartMessageSent.value = false
  linkedSessionId.value = ''
  const resetStatus: Record<string, 'pending' | 'running' | 'done' | 'failed'> = {}
  goalQueue.value.forEach(item => {
    resetStatus[item.itemId] = 'pending'
  })
  queueStatusByItemId.value = resetStatus
  await launchNextQueueItem()
}

async function hydrateTaskResultForTask(task: HomeAgentRuntimeTaskDetail, intent: DemoIntent) {
  if (String(task.status || '') !== 'completed') return

  if (intent === 'resume_analysis') {
    await ensureResumeProfileReady(task.taskId)
  }

  const liveResult = await buildLiveDemoResult(intent, task.goalInput || task.prompt || '')
  if (!liveResult) return

  taskResultByTaskId.value = {
    ...taskResultByTaskId.value,
    [task.taskId]: liveResult,
  }

  if (taskDetail.value?.taskId === task.taskId) {
    latestDemoResult.value = liveResult
  }

  const assistantText = buildAssistantContent(task, intent, 'done', liveResult)
  const actions = liveResult.actions || []
  const taskResultCard = buildTaskResultCardPayload(liveResult)

  upsertSessionTaskMessages('done', task, undefined, assistantText, 'succeeded', actions, taskResultCard)

  emitTaskOrchestratorStream({
    taskId: task.taskId,
    sessionId: linkedSessionId.value || undefined,
    phase: 'done',
    assistantMessageId: `task_assistant_${task.taskId}`,
    assistantText,
    assistantStatus: 'succeeded',
    actions,
    taskResultCard,
    agentTrace: buildTraceFromTask(task) as unknown as Record<string, unknown>,
  })
}

function handleTaskFinished(task: HomeAgentRuntimeTaskDetail) {
  const intent = taskIntentByTaskId.value[task.taskId] || inferIntent(task.goalInput || task.prompt || '', false)
  const taskStatus = String(task.status || '')
  const completed = taskStatus === 'completed'
  let syncPromise: Promise<void> = Promise.resolve()

  if (completed) {
    latestDemoResult.value = null
    pushRouteRefreshEvent(intent)
    syncPromise = hydrateTaskResultForTask(task, intent)
  } else {
    latestDemoResult.value = null
  }

  const queueItemId = taskQueueItemByTaskId.value[task.taskId] || ''
  if (queueItemId) {
    if (task.status === 'failed' || task.status === 'cancelled') {
      markQueueItemStatus(queueItemId, 'failed')
    } else {
      markQueueItemStatus(queueItemId, 'done')
    }
  }

  return syncPromise.catch(() => {})
}

async function handleTaskControl(action: HomeAgentRuntimeTaskControlAction) {
  const task = taskDetail.value
  if (!task || controllingTask.value || !canControl(action)) return

  const label = controlActionLabel(action)
  const expectedOutcome = controlExpectedOutcome(action)
  try {
    await ElMessageBox.confirm(`预期结果：${expectedOutcome}`, `确认${label}`, {
      confirmButtonText: `确认${label}`,
      cancelButtonText: '取消',
      type: 'warning',
      lockScroll: false,
    })
  } catch {
    return
  }

  controllingTask.value = true
  try {
    const response = await controlHomeAgentTask(task.taskId, action)
    const payload = response.data as ApiResponse<{ task: HomeAgentRuntimeTaskDetail }>
    if (!isSuccessCode(payload.code) || !payload.data?.task) {
      throw new Error(payload.msg || '任务操作失败')
    }

    taskDetail.value = payload.data.task
    const phase = String(payload.data.task.status || '') === 'completed' || String(payload.data.task.status || '') === 'rolled_back'
      ? 'done'
      : 'update'

    let finishSyncPromise: Promise<void> = Promise.resolve()
    if (phase === 'done') {
      finishSyncPromise = handleTaskFinished(payload.data.task)
    }

    emitTaskStreamMessage(phase, payload.data.task)
    if (phase === 'done') {
      await finishSyncPromise
      emitHomeAssistantSessionRefresh({
        sessionId: linkedSessionId.value || undefined,
        reason: 'task-finished',
      })
      if (queueRunning.value) {
        launchNextQueueItem().catch(() => {})
      }
    }

    await loadOverview(true)
    ElMessage.success(`已执行：${label}`)
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '任务操作失败')
  } finally {
    controllingTask.value = false
  }
}

async function startTaskEventStream(task: HomeAgentRuntimeTaskDetail) {
  closeTaskStream()

  const token = getToken() || ''
  // AI 服务直连 8086（Python），可用 VITE_AI_API_BASE_URL 覆盖
  const apiBase = import.meta.env.VITE_AI_API_BASE_URL || 'http://127.0.0.1:8086'
  const connector = task.stream.url.includes('?') ? '&' : '?'
  const fullUrl = `${apiBase}${task.stream.url}${connector}token=${encodeURIComponent(token)}`

  const source = new EventSource(fullUrl)
  taskStreamRef.value = source

  source.addEventListener('task_snapshot', (event) => {
    const payload = parseSsePayload((event as MessageEvent).data)
    if (!payload?.task) return
    taskDetail.value = payload.task
    emitTaskStreamMessage('update', payload.task)
  })

  source.addEventListener('task_update', (event) => {
    const payload = parseSsePayload((event as MessageEvent).data)
    if (!payload?.task) return
    taskDetail.value = payload.task
    emitTaskStreamMessage('update', payload.task)
  })

  source.addEventListener('task_done', (event) => {
    const payload = parseSsePayload((event as MessageEvent).data)
    if (!payload?.task) return
    taskDetail.value = payload.task
    ;(async () => {
      const finishSyncPromise = handleTaskFinished(payload.task as HomeAgentRuntimeTaskDetail)
      emitTaskStreamMessage('done', payload.task as HomeAgentRuntimeTaskDetail)
      await finishSyncPromise
      emitHomeAssistantSessionRefresh({
        sessionId: linkedSessionId.value || undefined,
        reason: 'task-finished',
      })
      if (queueRunning.value) {
        launchNextQueueItem().catch(() => {})
      }
      closeTaskStream()
    })().catch(() => {
      closeTaskStream()
    })
  })

  source.onerror = () => {
    closeTaskStream()
  }
}

async function handleDemoJump() {
  const target = latestDemoResult.value?.jumpRoute || ''
  if (!target) return
  await router.push(target)
}

onMounted(() => {
  window.addEventListener(TASK_ORCHESTRATOR_OPEN_EVENT, onTaskOpenEvent as EventListener)
  window.addEventListener('resize', clampPanelPosition)

  if (visible.value) {
    nextTick(() => {
      ensurePanelResizeObserver()
    })
  }

  if (isHomeRoute.value) {
    loadOverview(true).catch(() => {})
  }
})

onBeforeUnmount(() => {
  disconnectPanelResizeObserver()
  closeTaskStream()
  window.removeEventListener(TASK_ORCHESTRATOR_OPEN_EVENT, onTaskOpenEvent as EventListener)
  window.removeEventListener('resize', clampPanelPosition)
})
</script>

<template>
  <button
    v-if="isHomeRoute && !visible"
    type="button"
    class="task-launcher-bubble"
    title="打开任务编排"
    @click="handleOpenFromBubble"
  >
    <el-icon class="task-launcher-icon"><Operation /></el-icon>
  </button>

  <transition name="task-float">
    <div v-if="visible" ref="panelRef" class="task-panel" :style="panelStyle" @mousedown="handlePanelMouseDown">
      <header class="task-head" @mousedown.prevent="handleDragStart">
        <div class="task-head-left">
          <el-icon class="task-head-icon"><Operation /></el-icon>
          <div>
            <p class="task-head-title">任务编排</p>
            <p class="task-head-sub">智能体围绕输入目标，自动调用工具与执行</p>
          </div>
        </div>
        <div class="task-head-actions">
          <button type="button" class="task-head-btn" @click.stop="loadOverview()" :disabled="loading">
            <el-icon><Refresh /></el-icon>
          </button>
          <button type="button" class="task-head-btn" @click.stop="handleClosePanel">
            <el-icon><Close /></el-icon>
          </button>
        </div>
      </header>

      <div class="task-body" v-loading="loading">
        <section class="task-goal-card">
          <div class="task-goal-top">
            <p class="task-section-title">目标输入</p>
            <button type="button" class="task-file-btn" @click="triggerFilePicker">
              <el-icon><Paperclip /></el-icon>
              <span>附带文件</span>
            </button>
            <input ref="fileInputRef" type="file" :accept="RESUME_FILE_ACCEPT" class="hidden" @change="handleFileChange" />
            <input ref="queueFileInputRef" type="file" :accept="RESUME_FILE_ACCEPT" class="hidden" @change="handleQueueFileChange" />
          </div>

          <textarea
            v-model="goalDraft"
            class="task-goal-input"
            placeholder="例如：这是我的简历，请你帮我分析一下；请你帮我推荐和匹配岗位；帮我规划一下职业路径。"
          />

          <div v-if="selectedFiles.length" class="task-file-list">
            <span class="task-file-chip">{{ selectedFiles[0].name }}</span>
            <button type="button" class="task-file-remove" @click="removeSelectedFile">移除</button>
          </div>

          <div v-if="showGoalReportSelector" class="task-report-select-block">
            <p class="task-report-select-label">报告润色任务选择</p>
            <select v-model="selectedReportId" class="task-report-select" :disabled="!hasAvailableReports">
              <option value="">{{ hasAvailableReports ? '请选择报告' : '暂无可用报告，请先生成报告' }}</option>
              <option v-for="report in availableReports" :key="report.reportId" :value="report.reportId">
                {{ report.reportTitle }}（{{ report.reportId }}）
              </option>
            </select>
          </div>

          <div class="task-auto-plan-card" v-if="goalDraft.trim()">
            <p class="task-plan-title">自动计划（{{ resolveIntentLabel(inferredIntent) }}）</p>
            <p class="task-plan-expect">系统会先拆解你的目标，再按任务执行并输出阶段结果。</p>
            <ol class="task-plan-list">
              <li v-for="step in autoPlan" :key="step.stepId">
                <span class="task-plan-step-title">{{ step.title }}</span>
                <span class="task-plan-step-detail">{{ step.expectedResult || step.detail }}</span>
              </li>
            </ol>
          </div>

          <div class="task-goal-actions">
            <button type="button" class="task-secondary-btn" :disabled="creatingTask || !goalDraft.trim()" @click="handleAddGoalToQueue">
              <el-icon><Plus /></el-icon>
              <span>添加到顺序队列</span>
            </button>
            <button type="button" class="task-secondary-btn" :disabled="queueRunning" @click="handleAddDemoPipelineQueue">
              <el-icon><Plus /></el-icon>
              <span>一键添加演示三连任务</span>
            </button>
          </div>

          <button
            v-if="!goalQueue.length"
            type="button"
            class="task-launch-btn"
            :disabled="creatingTask || !goalDraft.trim()"
            @click="handleLaunchTask"
          >
            <el-icon><Operation /></el-icon>
            <span>{{ creatingTask ? '启动中...' : '启动任务' }}</span>
          </button>

          <div v-if="goalQueue.length" class="task-queue-card">
            <div class="task-queue-head">
              <p class="task-queue-title">多任务顺序执行</p>
              <span class="task-queue-meta">{{ queueProgressMeta }}</span>
            </div>
            <ul class="task-queue-list">
              <li v-for="item in queueItemsWithStatus" :key="item.itemId" class="task-queue-item">
                <span class="task-queue-badge" :class="resolveQueueStatusClass(item.status)">{{ resolveQueueStatusLabel(item.status) }}</span>
                <div class="task-queue-main">
                  <p class="task-queue-goal">{{ item.goalText }}</p>
                  <p class="task-queue-intent">{{ resolveIntentLabel(item.intent) }}</p>
                  <p v-if="item.resumeFileName" class="task-queue-file">附件：{{ item.resumeFileName }}</p>
                  <p v-else-if="item.intent === 'resume_analysis'" class="task-queue-file task-queue-file-missing">简历解析任务需上传文件</p>
                </div>
                <div class="task-queue-actions-right">
                  <button type="button" class="task-queue-upload" :disabled="queueRunning" @click="triggerQueueFilePicker(item.itemId)" title="上传该任务附件">
                    <el-icon><Paperclip /></el-icon>
                  </button>
                  <button type="button" class="task-queue-remove" :disabled="queueRunning" @click="handleRemoveQueueItem(item.itemId)">
                    <el-icon><Delete /></el-icon>
                  </button>
                </div>
              </li>
            </ul>
            <div class="task-queue-actions">
              <button type="button" class="task-secondary-btn task-queue-start-btn" :disabled="queueRunning || !hasPendingQueueItems" @click="handleStartQueue">
                <el-icon><Operation /></el-icon>
                <span>顺序执行</span>
              </button>
              <button type="button" class="task-secondary-btn" :disabled="queueRunning" @click="handleClearQueue">
                <span>清空队列</span>
              </button>
            </div>
          </div>

          <div class="task-preset-grid">
            <article
              v-for="preset in presets.slice(0, 4)"
              :key="preset.presetId"
              class="task-preset-chip"
              :class="[
                selectedPresetId === preset.presetId ? 'is-active' : '',
                isPresetDisabled(preset) ? 'is-disabled' : '',
              ]"
              @click="!isPresetDisabled(preset) && (selectedPresetId = selectedPresetId === preset.presetId ? '' : preset.presetId)"
            >
              <div class="task-preset-row">
                <span class="task-preset-title">{{ preset.title }}</span>
                <button
                  type="button"
                  class="task-preset-run"
                  :disabled="isPresetDisabled(preset)"
                  title="快捷发起任务"
                  @click.stop="handleQuickLaunchPreset(preset)"
                >
                  <el-icon><Promotion /></el-icon>
                </button>
              </div>
              <span class="task-preset-meta">快捷发起任务</span>
              <div v-if="preset.presetId === 'resume_parse_eval'" class="task-preset-extra" @click.stop>
                <button type="button" class="task-preset-upload" :disabled="queueRunning" @click="handlePresetUploadResume">
                  <el-icon><Paperclip /></el-icon>
                  <span>{{ selectedFiles.length ? '更换简历' : '上传简历' }}</span>
                </button>
                <span v-if="selectedFiles.length" class="task-preset-extra-text">{{ selectedFiles[0].name }}</span>
                <span v-else class="task-preset-extra-text task-preset-extra-missing">未选择简历文件</span>
              </div>
              <div v-else-if="preset.presetId === 'report_polish_approval'" class="task-preset-extra" @click.stop>
                <select v-model="selectedReportId" class="task-report-select" :disabled="!hasAvailableReports">
                  <option value="">{{ hasAvailableReports ? '请选择报告' : '暂无可用报告，请先生成报告' }}</option>
                  <option v-for="report in availableReports" :key="report.reportId" :value="report.reportId">
                    {{ report.reportTitle }}（{{ report.reportId }}）
                  </option>
                </select>
              </div>
              <span v-if="resolvePresetDisabledHint(preset)" class="task-preset-hint">{{ resolvePresetDisabledHint(preset) }}</span>
            </article>
          </div>

          <div v-if="selectedPreset" class="task-plan-card">
            <p class="task-plan-title">计划卡片：{{ selectedPreset.title }}</p>
            <p class="task-plan-expect">预期结果：{{ selectedPreset.expectedOutcome }}</p>
            <ol class="task-plan-list">
              <li v-for="step in selectedPreset.workflowPreview" :key="`${selectedPreset.presetId}-${step.stepId}`">
                <span class="task-plan-step-title">{{ step.title }}</span>
                <span class="task-plan-step-detail">{{ step.expectedResult || step.detail }}</span>
              </li>
            </ol>
          </div>
        </section>

        <section v-if="taskDetail" class="task-runtime-card">
          <div class="task-runtime-head">
            <div>
              <p class="task-runtime-title">{{ runtimeTaskTitle || taskDetail.title }}</p>
              <p class="task-runtime-sub">状态：<span :class="resolveTaskStatusClass(taskDetail.status)">{{ resolveTaskStatusLabel(taskDetail.status) }}</span></p>
            </div>
            <div class="task-runtime-right">
              <p class="task-runtime-progress">{{ displayTaskProgress }}%</p>
            </div>
          </div>

          <div class="task-progress-track">
            <i :style="{ width: `${displayTaskProgress}%` }"></i>
          </div>

          <div class="task-goal-conclusion" :class="resolveGoalConclusionClass()">
            <span class="task-goal-conclusion-tag">{{ resolveGoalConclusionLabel() }}</span>
            <span>{{ runtimeGoalConclusionText }}</span>
          </div>

          <div class="task-control-row">
            <button
              v-if="canControl('pause')"
              type="button"
              class="task-control-btn"
              :disabled="controllingTask"
              :title="controlExpectedOutcome('pause')"
              @click="handleTaskControl('pause')"
            >
              <el-icon><VideoPause /></el-icon>
              <span>暂停</span>
            </button>
            <button
              v-if="canControl('resume')"
              type="button"
              class="task-control-btn"
              :disabled="controllingTask"
              :title="controlExpectedOutcome('resume')"
              @click="handleTaskControl('resume')"
            >
              <el-icon><VideoPlay /></el-icon>
              <span>继续</span>
            </button>
            <button
              v-if="canControl('retry')"
              type="button"
              class="task-control-btn"
              :disabled="controllingTask"
              :title="controlExpectedOutcome('retry')"
              @click="handleTaskControl('retry')"
            >
              <el-icon><Refresh /></el-icon>
              <span>重试</span>
            </button>
            <button
              v-if="canControl('rollback')"
              type="button"
              class="task-control-btn"
              :disabled="controllingTask"
              :title="controlExpectedOutcome('rollback')"
              @click="handleTaskControl('rollback')"
            >
              <el-icon><ArrowLeft /></el-icon>
              <span>回滚</span>
            </button>
            <button
              v-if="canControl('cancel')"
              type="button"
              class="task-control-btn is-danger"
              :disabled="controllingTask"
              :title="controlExpectedOutcome('cancel')"
              @click="handleTaskControl('cancel')"
            >
              <span>取消</span>
            </button>
          </div>

          <div class="task-todo-shell">
            <p class="task-todo-title">执行步骤</p>
            <ul class="task-todo-list">
              <li v-for="step in runtimePlanSteps" :key="`plan-${step.stepId}`" class="task-todo-item">
                <el-icon class="task-todo-icon"><component :is="resolveStepStatusIcon(step.status)" /></el-icon>
                <div class="task-todo-content">
                  <p class="task-todo-name">{{ step.title }}</p>
                  <p class="task-todo-sub">{{ step.expectedResult || step.detail }}</p>
                </div>
                <span class="task-todo-status">{{ resolveStepStatusLabel(step.status) }}</span>
              </li>
            </ul>
          </div>

          <div class="task-tool-shell">
            <p class="task-todo-title">工具调用结果</p>
            <ul class="task-todo-list">
              <li v-for="run in toolExecutions" :key="run.runId" class="task-todo-item tool-item">
                <el-icon class="task-todo-icon"><component :is="resolveStepStatusIcon(run.status)" /></el-icon>
                <div class="task-todo-content">
                  <p class="task-todo-name">{{ run.toolName }}</p>
                  <p class="task-todo-sub">{{ run.outputSummary || run.inputSummary || '等待执行' }}</p>
                </div>
                <span class="task-todo-status">{{ resolveStepStatusLabel(run.status) }}</span>
              </li>
            </ul>
          </div>
        </section>

        <section v-if="latestDemoResult" class="task-demo-card">
          <div class="task-demo-head">
            <div>
              <p class="task-demo-title">{{ latestDemoResult.title }}</p>
              <p class="task-demo-summary">{{ latestDemoResult.summary }}</p>
            </div>
            <button v-if="latestDemoResult.jumpRoute" type="button" class="task-demo-jump" @click="handleDemoJump">
              {{ latestDemoResult.jumpLabel }}
            </button>
          </div>

          <div v-if="latestDemoResult.scores?.length" class="task-score-grid">
            <div v-for="item in latestDemoResult.scores" :key="item.dimension" class="task-score-item">
              <div class="task-score-top">
                <span>{{ item.dimension }}</span>
                <span>{{ item.score }}</span>
              </div>
              <div class="task-score-track"><i :style="{ width: `${item.score}%` }"></i></div>
            </div>
          </div>

          <div v-if="latestDemoResult.match" class="task-match-card">
            <p class="task-match-name">{{ latestDemoResult.match.jobName }}</p>
            <p class="task-match-meta">{{ latestDemoResult.match.jobFamily }} · {{ latestDemoResult.match.city }}</p>
            <div class="task-match-scores">
              <span>综合 {{ latestDemoResult.match.overallScore }}</span>
              <span>技能 {{ latestDemoResult.match.skillScore }}</span>
              <span>意愿 {{ latestDemoResult.match.intentScore }}</span>
              <span>成长 {{ latestDemoResult.match.growthScore }}</span>
            </div>
          </div>

          <div v-if="latestDemoResult.pathStages?.length" class="task-path-list">
            <div v-for="stage in latestDemoResult.pathStages" :key="stage.stage" class="task-path-item">
              <p class="task-path-stage">{{ stage.stage }}</p>
              <p class="task-path-title">{{ stage.title }}</p>
              <p class="task-path-detail">{{ stage.detail }}</p>
            </div>
          </div>
        </section>
      </div>
    </div>
  </transition>
</template>

<style scoped>
.task-launcher-bubble {
  position: fixed;
  right: 16px;
  top: calc(34% + 72px);
  z-index: 1952;
  width: 56px;
  height: 56px;
  border: 1px solid #60a5fa;
  border-radius: 50%;
  background: #e0efff;
  color: #1d4ed8;
  display: grid;
  place-items: center;
  box-shadow: 0 8px 16px rgba(59, 130, 246, 0.22);
  cursor: pointer;
  transition: transform 0.18s ease, box-shadow 0.18s ease;
}

.task-launcher-bubble:hover {
  transform: translateY(-1px);
  box-shadow: 0 10px 20px rgba(59, 130, 246, 0.28);
}

.task-launcher-icon {
  font-size: 22px;
}

.task-panel {
  position: fixed;
  z-index: 1953;
  border: 1px solid #dbeafe;
  border-radius: 16px;
  background: #ffffff;
  box-shadow: 0 22px 42px rgba(15, 23, 42, 0.16);
  display: flex;
  flex-direction: column;
  overflow: hidden;
  resize: both;
  min-width: 340px;
  min-height: 480px;
}

.task-head {
  height: 54px;
  border-bottom: 1px solid #e2e8f0;
  padding: 0 10px;
  display: flex;
  align-items: center;
  justify-content: space-between;
  background: #f8fbff;
  cursor: move;
  user-select: none;
}

.task-head-left {
  display: flex;
  align-items: center;
  gap: 8px;
}

.task-head-icon {
  width: 26px;
  height: 26px;
  border-radius: 8px;
  border: 1px solid #bfdbfe;
  background: #eff6ff;
  display: grid;
  place-items: center;
  color: #2563eb;
}

.task-head-title {
  margin: 0;
  font-size: 13px;
  color: #0f172a;
  font-weight: 700;
}

.task-head-sub {
  margin: 2px 0 0;
  font-size: 11px;
  color: #64748b;
}

.task-head-actions {
  display: flex;
  align-items: center;
  gap: 6px;
}

.task-head-btn {
  width: 26px;
  height: 26px;
  border-radius: 8px;
  border: 1px solid #dbeafe;
  background: #fff;
  color: #334155;
}

.task-body {
  flex: 1;
  min-height: 0;
  overflow-y: auto;
  padding: 10px;
  background: #f8fafc;
  display: grid;
  gap: 10px;
}

.task-goal-card,
.task-runtime-card,
.task-demo-card {
  border: 1px solid #e2e8f0;
  border-radius: 12px;
  background: #ffffff;
  padding: 10px;
}

.task-goal-top {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
}

.task-section-title {
  margin: 0;
  font-size: 12px;
  color: #1e3a8a;
  font-weight: 700;
}

.task-file-btn {
  border: 1px solid #dbeafe;
  border-radius: 9999px;
  background: #eff6ff;
  color: #1d4ed8;
  font-size: 11px;
  padding: 2px 8px;
  display: inline-flex;
  align-items: center;
  gap: 4px;
}

.task-goal-input {
  margin-top: 8px;
  width: 100%;
  min-height: 72px;
  border: 1px solid #dbeafe;
  border-radius: 10px;
  resize: vertical;
  background: #f8fbff;
  padding: 8px;
  font-size: 12px;
  line-height: 1.5;
  color: #0f172a;
  outline: none;
}

.task-file-list {
  margin-top: 8px;
  display: flex;
  align-items: center;
  gap: 8px;
}

.task-report-select-block {
  margin-top: 8px;
  display: grid;
  gap: 6px;
}

.task-report-select-label {
  margin: 0;
  font-size: 11px;
  color: #475569;
}

.task-report-select {
  width: 100%;
  border: 1px solid #dbeafe;
  border-radius: 10px;
  background: #f8fbff;
  color: #0f172a;
  font-size: 12px;
  padding: 7px 8px;
}

.task-file-chip {
  border: 1px solid #bfdbfe;
  border-radius: 9999px;
  background: #eff6ff;
  color: #1d4ed8;
  font-size: 11px;
  padding: 2px 8px;
}

.task-file-remove {
  border: none;
  background: transparent;
  color: #64748b;
  font-size: 11px;
}

.task-goal-actions {
  margin-top: 8px;
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
}

.task-secondary-btn {
  border: 1px solid #dbeafe;
  border-radius: 9999px;
  background: #eff6ff;
  color: #1d4ed8;
  font-size: 11px;
  padding: 2px 9px;
  display: inline-flex;
  align-items: center;
  gap: 4px;
}

.task-queue-start-btn {
  border-color: #1d4ed8;
  background: linear-gradient(135deg, #2563eb, #1d4ed8);
  color: #ffffff;
  font-weight: 700;
  box-shadow: 0 8px 16px rgba(29, 78, 216, 0.26);
}

.task-queue-start-btn:disabled {
  border-color: #cbd5e1;
  background: #cbd5e1;
  color: #f8fafc;
  box-shadow: none;
}

.task-auto-plan-card,
.task-plan-card,
.task-queue-card {
  margin-top: 8px;
  border: 1px solid #dbeafe;
  border-radius: 10px;
  background: #f8fbff;
  padding: 8px;
}

.task-plan-title,
.task-queue-title {
  margin: 0;
  font-size: 11px;
  font-weight: 700;
  color: #1e3a8a;
}

.task-plan-expect,
.task-queue-meta {
  margin: 4px 0 0;
  font-size: 10px;
  color: #475569;
}

.task-plan-list {
  margin: 6px 0 0;
  padding-left: 16px;
  display: grid;
  gap: 4px;
}

.task-plan-list li {
  color: #334155;
  font-size: 10px;
}

.task-plan-step-title {
  display: block;
  font-weight: 600;
}

.task-plan-step-detail {
  display: block;
  color: #64748b;
  margin-top: 1px;
}

.task-launch-btn {
  margin-top: 10px;
  width: 100%;
  border: 1px solid #bfdbfe;
  border-radius: 10px;
  background: #eff6ff;
  color: #1d4ed8;
  font-size: 12px;
  font-weight: 600;
  padding: 8px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: 6px;
}

.task-queue-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
}

.task-queue-list {
  margin: 6px 0 0;
  padding: 0;
  list-style: none;
  display: grid;
  gap: 6px;
}

.task-queue-item {
  border: 1px dashed #bfdbfe;
  border-radius: 8px;
  background: #fff;
  padding: 5px 6px;
  display: grid;
  grid-template-columns: auto minmax(0, 1fr) auto;
  gap: 6px;
  align-items: center;
}

.task-queue-badge {
  font-size: 10px;
  border-radius: 9999px;
  padding: 1px 6px;
}

.task-queue-pending {
  color: #475569;
  background: #f1f5f9;
}

.task-queue-running {
  color: #1d4ed8;
  background: #dbeafe;
}

.task-queue-done {
  color: #166534;
  background: #dcfce7;
}

.task-queue-failed {
  color: #b91c1c;
  background: #fee2e2;
}

.task-queue-main {
  min-width: 0;
}

.task-queue-goal {
  margin: 0;
  font-size: 11px;
  color: #0f172a;
  font-weight: 600;
}

.task-queue-intent {
  margin: 2px 0 0;
  font-size: 10px;
  color: #64748b;
}

.task-queue-file {
  margin: 3px 0 0;
  font-size: 10px;
  color: #1d4ed8;
}

.task-queue-file-missing {
  color: #b45309;
}

.task-queue-actions-right {
  display: inline-flex;
  align-items: center;
  gap: 4px;
}

.task-queue-upload {
  border: 1px solid #bfdbfe;
  border-radius: 6px;
  background: #eff6ff;
  color: #1d4ed8;
  width: 22px;
  height: 22px;
  display: grid;
  place-items: center;
}

.task-queue-remove {
  border: none;
  background: transparent;
  color: #64748b;
}

.task-queue-actions {
  margin-top: 6px;
  display: flex;
  gap: 6px;
}

.task-preset-grid {
  margin-top: 8px;
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 6px;
}

.task-preset-chip {
  border: 1px solid #dbeafe;
  border-radius: 10px;
  background: #fff;
  text-align: left;
  padding: 6px 7px;
  display: flex;
  flex-direction: column;
  gap: 3px;
}

.task-preset-chip.is-active {
  border-color: #60a5fa;
  background: #eff6ff;
}

.task-preset-chip.is-disabled {
  opacity: 0.62;
  cursor: not-allowed;
}

.task-preset-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 6px;
}

.task-preset-title {
  font-size: 11px;
  color: #0f172a;
  font-weight: 600;
}

.task-preset-meta {
  font-size: 10px;
  color: #64748b;
}

.task-preset-hint {
  font-size: 10px;
  color: #b45309;
}

.task-preset-extra {
  margin-top: 4px;
  display: grid;
  gap: 4px;
}

.task-preset-upload {
  border: 1px solid #bfdbfe;
  border-radius: 8px;
  background: #eff6ff;
  color: #1d4ed8;
  font-size: 10px;
  padding: 3px 7px;
  display: inline-flex;
  align-items: center;
  gap: 4px;
  width: fit-content;
}

.task-preset-extra-text {
  font-size: 10px;
  color: #475569;
  line-height: 1.35;
  word-break: break-all;
}

.task-preset-extra-missing {
  color: #b45309;
}

.task-preset-run {
  width: 20px;
  height: 20px;
  border-radius: 6px;
  border: 1px solid #bfdbfe;
  background: #eff6ff;
  color: #1d4ed8;
  display: grid;
  place-items: center;
}

.task-runtime-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 8px;
}

.task-runtime-title {
  margin: 0;
  font-size: 12px;
  font-weight: 700;
  color: #0f172a;
}

.task-runtime-sub {
  margin: 4px 0 0;
  font-size: 10px;
  color: #64748b;
}

.task-runtime-right {
  display: grid;
  justify-items: end;
  gap: 4px;
}

.task-runtime-progress {
  margin: 0;
  font-size: 12px;
  color: #1d4ed8;
  font-weight: 700;
}

.task-progress-track {
  margin-top: 8px;
  width: 100%;
  height: 5px;
  border-radius: 9999px;
  background: #e2e8f0;
  overflow: hidden;
}

.task-progress-track i {
  display: block;
  height: 100%;
  border-radius: inherit;
  background: linear-gradient(90deg, #2563eb, #3b82f6);
  transition: width 0.25s ease;
}

.task-goal-conclusion {
  margin-top: 8px;
  border: 1px solid #dbeafe;
  border-radius: 10px;
  background: #eff6ff;
  padding: 6px 8px;
  display: grid;
  gap: 3px;
  font-size: 10px;
  color: #334155;
}

.task-goal-conclusion-tag {
  font-weight: 700;
}

.goal-conclusion-achieved {
  border-color: #86efac;
  background: #f0fdf4;
  color: #166534;
}

.goal-conclusion-not-achieved {
  border-color: #fca5a5;
  background: #fef2f2;
  color: #b91c1c;
}

.goal-conclusion-rolled-back {
  border-color: #c4b5fd;
  background: #f5f3ff;
  color: #7c3aed;
}

.goal-conclusion-pending {
  border-color: #bfdbfe;
  background: #eff6ff;
  color: #1d4ed8;
}

.task-control-row {
  margin-top: 8px;
  display: flex;
  flex-wrap: wrap;
  gap: 5px;
}

.task-control-btn {
  border: 1px solid #dbeafe;
  background: #eff6ff;
  color: #1d4ed8;
  border-radius: 9999px;
  font-size: 10px;
  padding: 2px 8px;
  display: inline-flex;
  align-items: center;
  gap: 4px;
}

.task-control-btn.is-danger {
  border-color: #fecaca;
  background: #fef2f2;
  color: #b91c1c;
}

.task-todo-shell,
.task-tool-shell {
  margin-top: 10px;
}

.task-todo-title {
  margin: 0 0 5px;
  font-size: 11px;
  color: #1e3a8a;
  font-weight: 700;
}

.task-todo-list {
  margin: 0;
  padding: 0;
  list-style: none;
  display: grid;
  gap: 6px;
}

.task-todo-item {
  border: 1px solid #e2e8f0;
  border-radius: 9px;
  background: #f8fafc;
  padding: 6px;
  display: grid;
  grid-template-columns: auto minmax(0, 1fr) auto;
  gap: 6px;
  align-items: start;
}

.task-todo-icon {
  font-size: 13px;
  color: #2563eb;
  margin-top: 1px;
}

.task-todo-content {
  min-width: 0;
}

.task-todo-name {
  margin: 0;
  font-size: 11px;
  font-weight: 600;
  color: #0f172a;
}

.task-todo-sub {
  margin: 3px 0 0;
  font-size: 10px;
  color: #64748b;
  line-height: 1.45;
}

.task-todo-status {
  font-size: 10px;
  color: #64748b;
}

.tool-item {
  border-style: dashed;
}

.task-demo-head {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
  gap: 8px;
}

.task-demo-title {
  margin: 0;
  font-size: 12px;
  font-weight: 700;
  color: #0f172a;
}

.task-demo-summary {
  margin: 4px 0 0;
  font-size: 10px;
  color: #475569;
  line-height: 1.5;
}

.task-demo-jump {
  border: 1px solid #bfdbfe;
  border-radius: 9999px;
  background: #eff6ff;
  color: #1d4ed8;
  font-size: 10px;
  padding: 2px 8px;
}

.task-score-grid {
  margin-top: 8px;
  display: grid;
  gap: 6px;
}

.task-score-item {
  border: 1px solid #e2e8f0;
  border-radius: 8px;
  padding: 5px 6px;
  background: #f8fafc;
}

.task-score-top {
  display: flex;
  justify-content: space-between;
  font-size: 10px;
  color: #334155;
}

.task-score-track {
  margin-top: 4px;
  height: 4px;
  border-radius: 9999px;
  background: #e2e8f0;
  overflow: hidden;
}

.task-score-track i {
  display: block;
  height: 100%;
  border-radius: inherit;
  background: linear-gradient(90deg, #2563eb, #3b82f6);
}

.task-match-card,
.task-path-item {
  margin-top: 8px;
  border: 1px solid #e2e8f0;
  border-radius: 8px;
  background: #f8fafc;
  padding: 8px;
}

.task-match-name,
.task-path-stage {
  margin: 0;
  font-size: 11px;
  font-weight: 700;
  color: #0f172a;
}

.task-match-meta,
.task-path-title {
  margin: 3px 0 0;
  font-size: 10px;
  color: #475569;
}

.task-match-scores {
  margin-top: 6px;
  display: flex;
  gap: 6px;
  flex-wrap: wrap;
  font-size: 10px;
  color: #1d4ed8;
}

.task-path-detail {
  margin: 3px 0 0;
  font-size: 10px;
  color: #64748b;
}

.task-path-list {
  margin-top: 6px;
  display: grid;
  gap: 6px;
}

.task-status-running {
  color: #1d4ed8;
}

.task-status-queued {
  color: #475569;
}

.task-status-paused {
  color: #92400e;
}

.task-status-done {
  color: #166534;
}

.task-status-failed {
  color: #b91c1c;
}

.task-status-rolled-back {
  color: #7c3aed;
}

.task-float-enter-active,
.task-float-leave-active {
  transition: opacity 0.22s ease, transform 0.22s ease;
}

.task-float-enter-from,
.task-float-leave-to {
  opacity: 0;
  transform: translateY(12px);
}

@media (max-width: 900px) {
  .task-launcher-bubble {
    right: 10px;
    top: auto;
    bottom: 48px;
    width: 50px;
    height: 50px;
  }

  .task-launcher-icon {
    font-size: 20px;
  }

  .task-panel {
    min-width: 320px;
    min-height: 420px;
  }
}
</style>
