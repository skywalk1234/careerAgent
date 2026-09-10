<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { ArrowDown, ArrowUp, ChatDotRound, Delete, Position, Rank, Star, StarFilled } from '@element-plus/icons-vue'
import G6 from '@antv/g6'
import * as echarts from 'echarts'
import matchEmptyImage from '../assets/match.png'
import {
  analyzeMatch,
  autoPlanCareerPath,
  getAutoPlanCareerPathJobStatus,
  deleteMatchHistoryRecord,
  evaluateCareerPathRealtime,
  getSaveCareerPathJobStatus,
  getSavedCareerPathList,
  getLatestCareerPath,
  getCareerPathDetail,
  getMatchHistory,
  getMatchHistoryDetail,
  getMatchRecommendationsFromPython,
  pinMatchRecord,
  resetCareerPathDraft,
  saveCareerPath,
  unpinMatchRecord,
  deleteSavedCareerPath,
  type LatestPathResult,
  type MatchHistoryResult,
  type MatchRecordDetail,
  type MatchRecommendationsResult,
  type AutoPlanJobCreateResult,
  type AutoPlanJobStatusResult,
  type CareerPathDetailResult,
  type PathDraftResult,
  type PathEdge,
  type PathEvaluation,
  type PathNode,
  type SavePathJobCreateResult,
  type SavePathJobStatusResult,
  type SavedPathListResult,
  type SavedPathResult,
  type SavedPathSummary,
} from '../services/matchAnalysis'
import {
  addFavoriteJob,
  getFavoriteJobs,
  getJobDetail,
  getJobList,
  removeFavoriteJob,
  type FavoriteJobsResult,
  type JobDetailResult,
  type JobListItem,
  type JobListResult,
} from '../services/jobGraph'
import { isConflictCode, isSuccessCode } from '../services/http'
import {
  MATCH_RECOMMENDATION_REFRESH_EVENT,
  TASK_ORCHESTRATOR_ROUTE_REFRESH_EVENT,
  openGlobalAssistant,
  type MatchRecommendationRefreshPayload,
  type TaskOrchestratorRouteRefreshPayload,
} from '../utils/globalAssistant'
import { useAppStore } from '../stores/app'

interface ApiResponse<T> {
  code: number
  msg: string
  data?: T
  payload?: T
}

const router = useRouter()
const appStore = useAppStore()
const matchEmptyImageUrl = matchEmptyImage
const isDesktop = ref(window.innerWidth >= 1024)

const initLoading = ref(false)
const recommendLoading = ref(false)
const matchLoading = ref(false)
const historyLoading = ref(false)
const favoriteLoading = ref(false)
const favoriteActionLoading = ref(false)
const pathLoading = ref(false)
const pathSaving = ref(false)
const evaluateLoading = ref(false)
const startPlanModeDialogVisible = ref(false)

const recommendations = ref<MatchRecommendationsResult | null>(null)
const favorites = ref<FavoriteJobsResult['list']>([])
const jobPool = ref<JobListItem[]>([])
const manualExploreKeyword = ref('')
const manualExploreLoading = ref(false)
const manualExplorePage = ref(1)
const manualExploreTotal = ref(0)
const manualExplorePageSize = 8
const historyList = ref<MatchHistoryResult['list']>([])
const favoritePage = ref(1)
const favoritePageSize = 8
const historyPage = ref(1)
const historyPageSize = 5
const currentMatchRecord = ref<MatchRecordDetail | null>(null)
const activeRightPanel = ref<'recommend' | 'favorite' | 'history'>('recommend')

const currentPathDraft = ref<PathDraftResult | null>(null)
const currentPathDisplayName = ref('')
const pathNodes = ref<PathNode[]>([])
const pathEdges = ref<PathEdge[]>([])
const pathEvaluation = ref<PathEvaluation | null>(null)
const undoStack = ref<Array<{ nodes: PathNode[]; edges: PathEdge[] }>>([])
const redoStack = ref<Array<{ nodes: PathNode[]; edges: PathEdge[] }>>([])

const nodeDetailDrawerVisible = ref(false)
const nodeDetailLoading = ref(false)
const nodeDetail = ref<JobDetailResult | null>(null)
const nodeDetailTitle = ref('岗位详情')
// 收藏岗位详情：点击收藏岗位时直接按 jobId 查询岗位信息，不再走 match/analyze
const jobDetail = ref<JobDetailResult | null>(null)

const metricRadarRef = ref<HTMLDivElement>()
const metricTrendRef = ref<HTMLDivElement>()
const matchAbilityRadarRef = ref<HTMLDivElement>()
let metricRadarChart: echarts.ECharts | null = null
let metricTrendChart: echarts.ECharts | null = null
let metricRenderTimer: ReturnType<typeof setTimeout> | null = null
let matchAbilityRadarChart: echarts.ECharts | null = null
let matchAbilityRadarRenderTimer: ReturnType<typeof setTimeout> | null = null

const START_ANCHOR_JOB_ID = '__start__'
const START_ANCHOR_NODE: PathNode = {
  id: 'pn_anchor_start',
  jobId: START_ANCHOR_JOB_ID,
  jobName: '职业起点',
  stage: 'start',
}

const graphRef = ref<HTMLDivElement>()
let g6Graph: any = null
const PATH_GRAPH_NODE_TYPE = 'career-path-node'
let pathGraphNodeRegistered = false
let hasGraphRendered = false
let graphNodeCount = 0
let forceGraphFitOnNextRender = false
const graphNodePositionCache = new Map<string, { x: number; y: number }>()
let evaluateDebounceTimer: ReturnType<typeof setTimeout> | null = null
let evaluateRequestId = 0
const PATH_NODE_DRAG_ACTIVATE_THRESHOLD = 4
let pathNodeLongPressCandidateId = ''
let pathNodePointerPressed = false
let pathNodeDragActiveId = ''
let pathNodePressStartX = 0
let pathNodePressStartY = 0
let pathNodeLastPointerX = 0
let pathNodeLastPointerY = 0
let pathNodeDragPointerOffsetX = 0
let pathNodeDragPointerOffsetY = 0
let pathNodeDragPreviewNodes: PathNode[] | null = null
const pathDragPreviewEdgeIds = new Set<string>()
let suppressNodeClickOnce = false
const draggingIndex = ref<number | null>(null)
const graphHoverTip = ref({
  visible: false,
  x: 0,
  y: 0,
  title: '',
  lines: [] as string[],
})
const evaluationToastVisible = ref(false)
const evaluationToastText = ref('')
let evaluationToastTimer: ReturnType<typeof setTimeout> | null = null
const savedPathDialogVisible = ref(false)
const savedPathListLoading = ref(false)
const savedPathList = ref<SavedPathSummary[]>([])
const savedPathDeletingId = ref('')
const deleteSavedPathConfirmVisible = ref(false)
const pendingDeleteSavedPath = ref<SavedPathSummary | null>(null)
const applySavedPathConfirmVisible = ref(false)
const pendingApplySavedPath = ref<SavedPathSummary | null>(null)
const stagePlanDetailVisible = ref(false)
const matchDetailPage = ref<'score' | 'gap'>('score')
const refineIntentDialogVisible = ref(false)
const refineIntentSubmitting = ref(false)
const refineIntentForm = ref({
  preferredJobs: [] as string[],
  cities: [] as string[],
  salaryRange: '',
  benefits: [] as string[],
  description: '',
})

interface PathApplyOptions {
  relayoutGraph?: boolean
  pathName?: string
}

const refineBenefitSuggestions = ['双休', '五险一金', '弹性办公', '带薪年假', '住房补贴', '餐补']

const refinePreferredJobOptions = computed(() => {
  const options = [] as string[]
  const pushValue = (value?: string | null) => {
    const text = String(value || '').trim()
    if (!text) return
    if (options.includes(text)) return
    options.push(text)
  }

  pushValue(bestMatch.value?.jobName)
  ;(recommendations.value?.otherRecommendations || []).forEach(item => pushValue(item.jobName))
  favorites.value.forEach(item => pushValue(item.jobName))
  return options
})

const refineCityOptions = computed(() => {
  const options = [] as string[]
  const pushValue = (value?: string | null) => {
    const text = String(value || '').trim()
    if (!text) return
    if (options.includes(text)) return
    options.push(text)
  }

  pushValue(bestMatch.value?.city)
  ;(recommendations.value?.otherRecommendations || []).forEach(item => pushValue(item.city))
  favorites.value.forEach(item => pushValue(item.city))
  return options
})

function normalizeTagList(values: string[]) {
  return values
    .map(item => String(item || '').trim())
    .filter(Boolean)
    .filter((item, index, arr) => arr.indexOf(item) === index)
}

/** 把细化意愿表单映射为 Python 端点嵌入用的「求职意愿」对象（字段名对齐 Java filter） */
function buildRecommendIntent(): Record<string, unknown> {
  const form = refineIntentForm.value
  const intent: Record<string, unknown> = {}
  const preferredJobs = normalizeTagList(form.preferredJobs)
  const cities = normalizeTagList(form.cities)
  const benefits = normalizeTagList(form.benefits)
  if (preferredJobs.length) intent.preferredJobKeywords = preferredJobs
  if (cities.length) intent.cityIntents = cities
  const salaryRange = String(form.salaryRange || '').trim()
  if (salaryRange) intent.salaryRange = salaryRange
  if (benefits.length) intent.benefits = benefits
  const description = String(form.description || '').trim()
  if (description) intent.note = description
  return intent
}

async function handleRecommendationRefreshEvent(detail: MatchRecommendationRefreshPayload) {
  const status = String(detail?.status || '')
  if (status === 'started' || status === 'processing' || status === 'succeeded') {
    await fetchRecommendations(true)
  }

  if (status === 'succeeded' && detail?.message) {
    ElMessage.success(detail.message)
  }

  if (status === 'failed') {
    ElMessage.error(detail?.message || '细化匹配失败，请稍后重试')
  }
}

function onRecommendationRefreshEvent(event: Event) {
  const customEvent = event as CustomEvent<MatchRecommendationRefreshPayload>
  const detail = customEvent.detail || { status: 'processing' as const }
  handleRecommendationRefreshEvent(detail).catch(() => {})
}

async function handleTaskOrchestratorRouteRefreshEvent(detail: TaskOrchestratorRouteRefreshPayload) {
  if (String(detail?.routePath || '').trim() !== '/match') return

  await Promise.all([
    fetchRecommendations(true).catch(() => {}),
    fetchMatchHistory(false).catch(() => {}),
    fetchLatestPath().catch(() => {}),
  ])
}

function onTaskOrchestratorRouteRefreshEvent(event: Event) {
  const customEvent = event as CustomEvent<TaskOrchestratorRouteRefreshPayload>
  handleTaskOrchestratorRouteRefreshEvent(customEvent.detail || {}).catch(() => {})
}

const selectedTargetJobId = ref('')
const autoPlanMode = ref<'balanced' | 'conservative' | 'aggressive'>('balanced')
const pendingPlanJobId = ref('')
const sandboxCardRef = ref<HTMLElement | null>(null)

const dimensionLabelMap: Record<string, string> = {
  basicRequirement: '基础要求',
  professionalSkill: '职业技能',
  professionalLiteracy: '职业素养',
  developmentPotential: '发展潜力',
}

const abilityLabelMap: Record<string, string> = {
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

const MATCH_ABILITY_DIMENSIONS = [
  'professionalSkill',
  'certificate',
  'innovation',
  'internalMotivation',
  'learning',
  'stressTolerance',
  'communication',
  'internship',
  'language',
  'leadership',
  'adaptability',
  'execution',
] as const

const priorityLabelMap: Record<string, string> = {
  high: '高优先级',
  medium: '中优先级',
  low: '低优先级',
}

const hasProfile = computed(() => appStore.hasProfile !== false)
const hasConfirmedProfile = computed(() => appStore.hasProfile === true)
const isRecommendationProcessing = computed(() => {
  return hasConfirmedProfile.value && recommendations.value?.recommendationStatus === 'processing'
})
const recommendationProcessingReason = computed(() => {
  return recommendations.value?.reason || '人岗匹配分析中，请稍候。'
})

const bestMatch = computed(() => recommendations.value?.bestMatch ?? null)

const recommendationOptions = computed(() => {
  const options = [] as Array<{ jobId: string; label: string }>
  if (bestMatch.value) {
    options.push({
      jobId: bestMatch.value.jobId,
      label: `${bestMatch.value.jobName}（推荐）`,
    })
  }
  recommendations.value?.otherRecommendations?.forEach((item) => {
    options.push({
      jobId: item.jobId,
      label: `${item.jobName}（${item.city}）`,
    })
  })
  favorites.value.forEach((item) => {
    if (!options.some(opt => opt.jobId === item.jobId)) {
      options.push({
        jobId: item.jobId,
        label: `${item.jobName}（收藏）`,
      })
    }
  })
  return options
})

const manualDefaultOptions = computed(() => {
  const options = [] as Array<{ value: string; label: string }>

  const appendOption = (jobId: string, label: string) => {
    if (!jobId) return
    if (options.some(item => item.value === jobId)) return
    options.push({ value: jobId, label })
  }

  if (bestMatch.value) {
    appendOption(bestMatch.value.jobId, `${bestMatch.value.jobName}（推荐）`)
  }

  ;(recommendations.value?.otherRecommendations || []).forEach((item) => {
    appendOption(item.jobId, `${item.jobName}（推荐）`)
  })

  favorites.value.forEach((item) => {
    appendOption(item.jobId, `${item.jobName}（收藏）`)
  })

  return options
})

const manualExplorePageCount = computed(() => {
  if (!manualExploreTotal.value) return 1
  return Math.max(1, Math.ceil(manualExploreTotal.value / manualExplorePageSize))
})

const canSavePath = computed(() => getBackendPathNodes().length >= 2)
const canUndo = computed(() => undoStack.value.length > 0)
const canRedo = computed(() => redoStack.value.length > 0)
const currentMatchJobId = computed(() => currentMatchRecord.value?.job?.jobId || '')
const isCurrentMatchFavorited = computed(() => {
  if (!currentMatchJobId.value) return false
  return favorites.value.some(item => item.jobId === currentMatchJobId.value)
})

const historyPrimaryRecord = computed(() => {
  return historyList.value.find(item => item.pinned) || historyList.value[0] || null
})
const sortedHistoryList = computed(() => {
  return [...historyList.value].sort((a, b) => {
    if (a.pinned && !b.pinned) return -1
    if (!a.pinned && b.pinned) return 1
    return new Date(b.updatedAt).getTime() - new Date(a.updatedAt).getTime()
  })
})
const favoritePageCount = computed(() => {
  if (!favorites.value.length) return 1
  return Math.max(1, Math.ceil(favorites.value.length / favoritePageSize))
})
const pagedFavoriteList = computed(() => {
  const start = (favoritePage.value - 1) * favoritePageSize
  return favorites.value.slice(start, start + favoritePageSize)
})
const historyPageCount = computed(() => {
  if (!sortedHistoryList.value.length) return 1
  return Math.max(1, Math.ceil(sortedHistoryList.value.length / historyPageSize))
})
const pagedHistoryList = computed(() => {
  const start = (historyPage.value - 1) * historyPageSize
  return sortedHistoryList.value.slice(start, start + historyPageSize)
})
const canShowStagePlanDetail = computed(() => {
  return pathEvaluation.value?.level === 'deep' && Boolean(pathEvaluation.value?.stagePlans?.length)
})

const hasAnyRecommendation = computed(() => {
  return Boolean(bestMatch.value) || (recommendations.value?.otherRecommendations?.length || 0) > 0
})

const matchDimensionTable = computed(() => {
  const scores = currentMatchRecord.value?.match.dimensionScores
  if (!scores) return []
  return [
    { key: 'basicRequirement', label: dimensionLabelMap.basicRequirement, score: scores.basicRequirement },
    { key: 'professionalSkill', label: dimensionLabelMap.professionalSkill, score: scores.professionalSkill },
    { key: 'professionalLiteracy', label: dimensionLabelMap.professionalLiteracy, score: scores.professionalLiteracy },
    { key: 'developmentPotential', label: dimensionLabelMap.developmentPotential, score: scores.developmentPotential },
  ]
})

const dimensionExplainList = computed(() => {
  const analysis = currentMatchRecord.value?.match.dimensionAnalysis
  if (!analysis) {
    return matchDimensionTable.value.map(item => ({
      ...item,
      expectedScore: item.score,
      reason: '当前维度说明待补充',
    }))
  }

  return matchDimensionTable.value.map((item) => {
    const detail = analysis[item.key as keyof typeof analysis]
    return {
      ...item,
      expectedScore: Number(detail?.expectedScore ?? 0),
      reason: String(detail?.reason || '暂无维度解释说明'),
    }
  })
})

const matchAbilityRadarSeries = computed(() => {
  const match = currentMatchRecord.value?.match
  const studentScores = match?.studentAbilityScores
  const jobScores = match?.jobAbilityScores
  if (!studentScores || !jobScores) {
    return null
  }

  const student = MATCH_ABILITY_DIMENSIONS.map((key) => Number(studentScores[key] ?? 0))
  const job = MATCH_ABILITY_DIMENSIONS.map((key) => Number(jobScores[key] ?? 0))
  return { student, job }
})

const matchAbilityRadarIndicators = computed(() => {
  return MATCH_ABILITY_DIMENSIONS.map((key) => ({
    name: abilityLabelMap[key] || key,
    max: 100,
  }))
})

const topSuggestions = computed(() => currentMatchRecord.value?.match.improvementSuggestions ?? [])

const displaySuggestions = computed(() => {
  return topSuggestions.value.map((item) => ({
    ...item,
    displayDimension: abilityLabelMap[item.dimension] || item.dimension,
    displayPriority: priorityLabelMap[item.priority] || item.priority,
  }))
})

function normalizeProgressScore(score: number) {
  return Math.max(0, Math.min(100, Number(score || 0)))
}

function clonePathState() {
  return {
    nodes: JSON.parse(JSON.stringify(pathNodes.value)) as PathNode[],
    edges: JSON.parse(JSON.stringify(pathEdges.value)) as PathEdge[],
  }
}

function pushUndoSnapshot() {
  undoStack.value.push(clonePathState())
  if (undoStack.value.length > 30) {
    undoStack.value.shift()
  }
}

function clearRedoStack() {
  redoStack.value = []
}

function applyPathSnapshot(snapshot: { nodes: PathNode[]; edges: PathEdge[] }, triggerEvaluate = true) {
  pathNodes.value = snapshot.nodes
  pathEdges.value = snapshot.edges
  if (triggerEvaluate) {
    queueRealtimeEvaluate()
  }
}

function handleUndoPathEdit() {
  if (!canUndo.value) return
  const snapshot = undoStack.value.pop()
  if (!snapshot) return
  redoStack.value.push(clonePathState())
  applyPathSnapshot(snapshot, true)
}

function handleRedoPathEdit() {
  if (!canRedo.value) return
  const snapshot = redoStack.value.pop()
  if (!snapshot) return
  undoStack.value.push(clonePathState())
  applyPathSnapshot(snapshot, true)
}

function handleOpenGlobalAssistantForRefine() {
  if (!hasAnyRecommendation.value) return
  refineIntentDialogVisible.value = true
}

function resetRefineIntentForm() {
  refineIntentForm.value = {
    preferredJobs: [],
    cities: [],
    salaryRange: '',
    benefits: [],
    description: '',
  }
}

async function handleSubmitRefineIntent() {
  if (refineIntentSubmitting.value) return
  const description = String(refineIntentForm.value.description || '').trim()
  if (!description) {
    ElMessage.warning('请至少填写整体意愿描述')
    return
  }

  refineIntentSubmitting.value = true
  const recommendationNames = [
    bestMatch.value?.jobName,
    ...(recommendations.value?.otherRecommendations || []).slice(0, 3).map(item => item.jobName),
  ].filter(Boolean)

  const preferredJobs = normalizeTagList(refineIntentForm.value.preferredJobs || [])
  const cities = normalizeTagList(refineIntentForm.value.cities || [])
  const salaryRange = String(refineIntentForm.value.salaryRange || '').trim()
  const benefits = normalizeTagList(refineIntentForm.value.benefits || [])
  const promptLines = [
    '我对当前匹配结果不满意，请帮我先梳理意愿并给建议，然后我会按建议重新匹配。',
    `意愿描述：${description}`,
    preferredJobs.length ? `期望岗位：${preferredJobs.join('、')}` : '',
    cities.length ? `意向城市：${cities.join('、')}` : '',
    salaryRange ? `可接受薪资范围：${salaryRange}` : '',
    benefits.length ? `福利偏好：${benefits.join('、')}` : '',
  ].filter(Boolean)

  openGlobalAssistant({
    routePath: '/match',
    pageTitle: '职业规划',
    contextPrompt: '请先基于我的意愿做建议，随后给我“按当前意愿重新匹配”的快捷动作。',
    initialMessage: promptLines.join('\n'),
    autoSendInitialMessage: true,
    data: {
      bestMatchJobId: bestMatch.value?.jobId || '',
      bestMatchJobName: bestMatch.value?.jobName || '',
      recommendationNames,
      recommendationStatus: recommendations.value?.recommendationStatus || 'none',
      userIntent: {
        description,
        preferredJobs,
        cities,
        salaryRange,
        benefits,
      },
    },
  })

  refineIntentDialogVisible.value = false
  resetRefineIntentForm()
  refineIntentSubmitting.value = false
}

function extractPayload<T>(response: { data: ApiResponse<T> }): T | undefined {
  return response.data.payload ?? response.data.data
}

function clampPollMs(value: unknown, fallback = 800) {
  const numeric = Number(value)
  const resolved = Number.isFinite(numeric) ? numeric : fallback
  return Math.max(1000, Math.min(2000, Math.round(resolved)))
}

function normalizeMatchRecordDetail(record: MatchRecordDetail | null | undefined): MatchRecordDetail | null {
  if (!record) return null
  const source = record as MatchRecordDetail & { analysis?: MatchRecordDetail['match']; match?: MatchRecordDetail['match'] }
  const normalizedAnalysis = source.analysis || source.match
  if (!normalizedAnalysis) return null

  return {
    ...source,
    match: normalizedAnalysis,
    analysis: normalizedAnalysis,
  }
}

function formatTime(value: string | null | undefined) {
  if (!value) return '--'
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) return value
  return new Intl.DateTimeFormat('zh-CN', {
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
    hour12: false,
  }).format(date)
}

function suggestionType(priority: string) {
  if (priority === 'high') return 'danger'
  if (priority === 'medium') return 'warning'
  return 'info'
}

function suggestionCardStyle(priority: string) {
  if (priority === 'high') {
    return {
      background: 'linear-gradient(135deg, rgba(254,242,242,0.95), rgba(255,255,255,0.95))',
      borderColor: 'rgba(248,113,113,0.35)',
    }
  }

  return {
    background: 'linear-gradient(135deg, rgba(248,250,252,0.95), rgba(255,255,255,0.95))',
    borderColor: 'rgba(148,163,184,0.25)',
  }
}

function scoreColor(score: number) {
  if (score >= 85) return '#16a34a'
  if (score >= 75) return '#2563eb'
  if (score >= 65) return '#d97706'
  return '#dc2626'
}

function scoreSoftCardStyle(score: number) {
  if (score >= 85) {
    return {
      background: 'linear-gradient(135deg, rgba(236,253,245,0.88), rgba(255,255,255,0.95))',
      borderColor: 'rgba(34,197,94,0.28)',
    }
  }
  if (score >= 75) {
    return {
      background: 'linear-gradient(135deg, rgba(239,246,255,0.9), rgba(255,255,255,0.95))',
      borderColor: 'rgba(59,130,246,0.25)',
    }
  }
  if (score >= 65) {
    return {
      background: 'linear-gradient(135deg, rgba(255,251,235,0.92), rgba(255,255,255,0.95))',
      borderColor: 'rgba(245,158,11,0.28)',
    }
  }
  return {
    background: 'linear-gradient(135deg, rgba(254,242,242,0.9), rgba(255,255,255,0.95))',
    borderColor: 'rgba(239,68,68,0.25)',
  }
}

function progressGradientColor(score: number) {
  if (score >= 85) return '#22c55e'
  if (score >= 75) return '#3b82f6'
  if (score >= 65) return '#f59e0b'
  return '#ef4444'
}

function stagePlanCardStyle(stage: string, stageLabel?: string) {
  const label = String(stageLabel || '')
  const isTransitionStage = stage === 'transitionPhase' && !label.includes('换岗')
  const isPromotionStage = stage === 'promotionPhase' || label.includes('晋升')
  const isSwitchStage = label.includes('换岗')

  if (isTransitionStage) {
    return {
      background: 'linear-gradient(135deg, rgba(248,250,252,0.95), rgba(255,255,255,0.95))',
      borderColor: 'rgba(148,163,184,0.28)',
    }
  }

  if (isPromotionStage) {
    return {
      background: 'linear-gradient(135deg, rgba(239,246,255,0.95), rgba(255,255,255,0.95))',
      borderColor: 'rgba(96,165,250,0.35)',
    }
  }

  if (isSwitchStage) {
    return {
      background: 'linear-gradient(135deg, rgba(254,252,232,0.95), rgba(255,255,255,0.95))',
      borderColor: 'rgba(250,204,21,0.35)',
    }
  }

  if (stage === 'shortTerm') {
    return {
      background: 'linear-gradient(135deg, rgba(239,246,255,0.95), rgba(255,255,255,0.95))',
      borderColor: 'rgba(96,165,250,0.35)',
    }
  }
  if (stage === 'midTerm') {
    return {
      background: 'linear-gradient(135deg, rgba(254,252,232,0.95), rgba(255,255,255,0.95))',
      borderColor: 'rgba(250,204,21,0.35)',
    }
  }
  if (stage === 'longTerm') {
    return {
      background: 'linear-gradient(135deg, rgba(240,253,244,0.95), rgba(255,255,255,0.95))',
      borderColor: 'rgba(74,222,128,0.35)',
    }
  }
  return {
    background: 'linear-gradient(135deg, rgba(248,250,252,0.95), rgba(255,255,255,0.95))',
    borderColor: 'rgba(148,163,184,0.28)',
  }
}

function publishDateText(payload: { updatedAtRaw?: string; updatedAtNormalized?: string | null } | null | undefined) {
  const raw = String(payload?.updatedAtRaw || '').trim()
  if (raw) return raw
  const normalized = String(payload?.updatedAtNormalized || '').trim()
  if (!normalized) return '发布日期未知'
  const date = new Date(normalized)
  if (Number.isNaN(date.getTime())) return '发布日期未知'
  return `${date.getMonth() + 1}月${date.getDate()}日`
}

/**
 * 薪资展示。
 * 岗位探索/收藏链路的数据源是 pgvector 的 job_detail_vector，薪资字段是原始文本 salaryText；
 * 匹配分析自己的接口（match/analyze 等）仍是老结构，字段是 salaryNormalized / salaryNegotiable。
 * 两种都兼容，优先用 salaryText。
 */
function displaySalaryText(payload: {
  salaryText?: string | null
  salaryNormalized?: string
  salaryNegotiable?: boolean
} | null | undefined) {
  const text = String(payload?.salaryText || '').trim()
  if (text) return text
  if (payload?.salaryNegotiable) return '面谈'
  const normalized = String(payload?.salaryNormalized || '').trim()
  if (normalized) return normalized
  return '薪资待补充'
}

function formatPercentScore(value: number | null | undefined) {
  const score = Number(value)
  if (!Number.isFinite(score)) return '--'
  return `${Math.max(0, Math.min(100, Math.round(score)))}%`
}

function disposeMetricCharts() {
  if (metricRenderTimer) {
    clearTimeout(metricRenderTimer)
    metricRenderTimer = null
  }
  metricRadarChart?.dispose()
  metricTrendChart?.dispose()
  metricRadarChart = null
  metricTrendChart = null
}

function scheduleMetricChartsRender(delay = 320) {
  if (metricRenderTimer) {
    clearTimeout(metricRenderTimer)
    metricRenderTimer = null
  }
  metricRenderTimer = setTimeout(() => {
    metricRenderTimer = null
    nextTick(() => {
      const rendered = renderMetricCharts()
      if (!rendered) {
        scheduleMetricChartsRender(180)
        return
      }
      metricRadarChart?.resize()
      metricTrendChart?.resize()
    })
  }, delay)
}

function disposeMatchAbilityRadarChart() {
  if (matchAbilityRadarRenderTimer) {
    clearTimeout(matchAbilityRadarRenderTimer)
    matchAbilityRadarRenderTimer = null
  }
  matchAbilityRadarChart?.dispose()
  matchAbilityRadarChart = null
}

function renderMatchAbilityRadar(forceRecreate = false) {
  if (!matchAbilityRadarRef.value || !matchAbilityRadarSeries.value) return false
  if (matchAbilityRadarRef.value.clientWidth <= 0 || matchAbilityRadarRef.value.clientHeight <= 0) {
    return false
  }

  if (forceRecreate) {
    matchAbilityRadarChart?.dispose()
    matchAbilityRadarChart = null
  }

  if (!matchAbilityRadarChart) {
    matchAbilityRadarChart = echarts.init(matchAbilityRadarRef.value)
  }

  matchAbilityRadarChart.setOption({
    animationDuration: 420,
    tooltip: {
      trigger: 'item',
    },
    legend: {
      bottom: 0,
      itemWidth: 10,
      itemHeight: 10,
      textStyle: {
        color: '#475569',
      },
      data: ['学生能力', '岗位要求'],
    },
    radar: {
      radius: '63%',
      center: ['50%', '45%'],
      splitNumber: 5,
      indicator: matchAbilityRadarIndicators.value,
      axisName: {
        color: '#475569',
        fontSize: 11,
      },
      splitLine: {
        lineStyle: {
          color: '#e2e8f0',
        },
      },
      splitArea: {
        areaStyle: {
          color: ['#ffffff', '#f8fafc'],
        },
      },
      axisLine: {
        lineStyle: {
          color: '#cbd5e1',
        },
      },
    },
    series: [
      {
        type: 'radar',
        symbol: 'circle',
        symbolSize: 4,
        lineStyle: {
          width: 2,
        },
        data: [
          {
            value: matchAbilityRadarSeries.value.student,
            name: '学生能力',
            itemStyle: { color: '#2563eb' },
            lineStyle: { color: '#2563eb' },
            areaStyle: { color: 'rgba(37,99,235,0.20)' },
          },
          {
            value: matchAbilityRadarSeries.value.job,
            name: '岗位要求',
            itemStyle: { color: '#f59e0b' },
            lineStyle: { color: '#f59e0b' },
            areaStyle: { color: 'rgba(245,158,11,0.18)' },
          },
        ],
      },
    ],
  })

  matchAbilityRadarChart.resize()
  return true
}

function scheduleMatchAbilityRadarRender(delay = 60, forceRecreate = false) {
  if (matchAbilityRadarRenderTimer) {
    clearTimeout(matchAbilityRadarRenderTimer)
    matchAbilityRadarRenderTimer = null
  }

  matchAbilityRadarRenderTimer = setTimeout(() => {
    matchAbilityRadarRenderTimer = null
    nextTick(() => {
      const rendered = renderMatchAbilityRadar(forceRecreate)
      if (!rendered) {
        scheduleMatchAbilityRadarRender(160, forceRecreate)
      }
    })
  }, delay)
}

function toggleMatchDetailPage() {
  matchDetailPage.value = matchDetailPage.value === 'score' ? 'gap' : 'score'
  if (matchDetailPage.value === 'gap') {
    scheduleMatchAbilityRadarRender(30, true)
    return
  }
  disposeMatchAbilityRadarChart()
}

function evaluationToastPalette(score: number) {
  if (score >= 80) {
    return {
      type: 'success' as const,
      bg: 'rgba(236,253,245,0.95)',
      border: 'rgba(34,197,94,0.35)',
      text: '#166534',
    }
  }
  if (score >= 65) {
    return {
      type: 'warning' as const,
      bg: 'rgba(255,251,235,0.95)',
      border: 'rgba(245,158,11,0.35)',
      text: '#92400e',
    }
  }
  return {
    type: 'danger' as const,
    bg: 'rgba(254,242,242,0.95)',
    border: 'rgba(239,68,68,0.35)',
    text: '#991b1b',
  }
}

function showEvaluationToast() {
  if (!pathEvaluation.value) return
  const feasibility = Number(pathEvaluation.value.feasibilityScore || 0)
  const readiness = Number(pathEvaluation.value.readinessScore || 0)
  const recommendation = Number(pathEvaluation.value.recommendationScore || 0)
  evaluationToastText.value = `评估已更新：可行性 ${feasibility}，就绪度 ${readiness}，推荐度 ${recommendation}`
  evaluationToastVisible.value = true
  if (evaluationToastTimer) {
    clearTimeout(evaluationToastTimer)
    evaluationToastTimer = null
  }
  evaluationToastTimer = setTimeout(() => {
    evaluationToastVisible.value = false
    evaluationToastTimer = null
  }, 60000)
}

const evaluationSummaryMetrics = computed(() => {
  const metrics = pathEvaluation.value?.summaryMetrics
  if (Array.isArray(metrics)) return metrics
  return pathEvaluation.value?.metrics ?? []
})

const evaluationToastExtraMetrics = computed(() => {
  const keySet = new Set(['avgSimilarity', 'targetMatch'])
  return evaluationSummaryMetrics.value.filter(item => keySet.has(String(item.key || '')))
})

const evaluationMetricDescriptions: Record<string, string> = {
  feasibilityScore: '可行性：基于路径边相似度、路径跨度和结构复杂度评估执行难度。',
  readinessScore: '就绪度：基于你当前能力与目标岗位要求的匹配程度评估。',
  recommendationScore: '推荐度：综合可行性和就绪度得到的总体建议分。',
}

function getEvaluationMetricDescription(key: string) {
  return evaluationMetricDescriptions[key] || ''
}

function closeEvaluationToast() {
  evaluationToastVisible.value = false
  if (evaluationToastTimer) {
    clearTimeout(evaluationToastTimer)
    evaluationToastTimer = null
  }
}

function openExternalLink(url?: string) {
  if (!url) return
  globalThis.window?.open(url, '_blank', 'noopener,noreferrer')
}

function formatJobTag(tag: string) {
  const raw = String(tag || '').trim()
  if (!raw) return ''
  return raw.startsWith('岗位标签：') ? raw.replace(/^岗位标签：/, '') : raw
}

function getTagType(tag: string): 'danger' | 'warning' | 'info' | 'success' {
  const normalized = formatJobTag(tag)
  if (normalized === '热门') return 'danger'
  if (normalized === '新兴') return 'warning'
  if (normalized === '饱和') return 'info'
  return 'success'
}

function shortNodeLabel(name: string) {
  const normalized = String(name || '').replace(/\s+/g, '')
  if (!normalized) return ''
  return normalized.length <= 4 ? normalized : normalized.slice(0, 4)
}

function ensurePathGraphNodeRegistered() {
  if (pathGraphNodeRegistered) return
  G6.registerNode(
    PATH_GRAPH_NODE_TYPE,
    {
      draw(cfg: any, group: any) {
        const style = cfg?.style || {}
        const size = Array.isArray(cfg?.size) ? Number(cfg.size[0] || 46) : Number(cfg?.size || 46)
        const radius = Math.max(14, size / 2)
        const keyShape = group.addShape('circle', {
          attrs: {
            x: 0,
            y: 0,
            r: radius,
            fill: style.fill || '#2563eb',
            stroke: style.stroke || '#0f172a',
            lineWidth: Number(style.lineWidth || 1.5),
            shadowColor: style.shadowColor || undefined,
            shadowBlur: Number(style.shadowBlur || 0),
          },
          name: 'key-shape',
        })

        group.addShape('text', {
          attrs: {
            x: 0,
            y: 0,
            text: String(cfg?.shortLabel || ''),
            fill: '#ffffff',
            fontSize: 11,
            fontWeight: 600,
            textAlign: 'center',
            textBaseline: 'middle',
          },
          name: 'short-label',
        })

        if (!cfg?.isAnchor) {
          group.addShape('text', {
            attrs: {
              x: 0,
              y: radius + 12,
              text: String(cfg?.fullLabel || ''),
              fill: '#334155',
              fontSize: 11,
              fontWeight: 500,
              textAlign: 'center',
              textBaseline: 'top',
            },
            name: 'full-label',
          })
        }

        return keyShape
      },
      update(cfg: any, item: any) {
        const group = item?.getContainer?.()
        if (!group) return

        const style = cfg?.style || {}
        const size = Array.isArray(cfg?.size) ? Number(cfg.size[0] || 46) : Number(cfg?.size || 46)
        const radius = Math.max(14, size / 2)

        const keyShape = group.find((shape: any) => shape?.get?.('name') === 'key-shape')
        if (keyShape) {
          keyShape.attr({
            r: radius,
            fill: style.fill || '#2563eb',
            stroke: style.stroke || '#0f172a',
            lineWidth: Number(style.lineWidth || 1.5),
            shadowColor: style.shadowColor || undefined,
            shadowBlur: Number(style.shadowBlur || 0),
          })
        }

        const shortLabelShape = group.find((shape: any) => shape?.get?.('name') === 'short-label')
        if (shortLabelShape) {
          shortLabelShape.attr({
            text: String(cfg?.shortLabel || ''),
          })
        }

        const fullLabelShape = group.find((shape: any) => shape?.get?.('name') === 'full-label')
        if (cfg?.isAnchor) {
          if (fullLabelShape?.remove) {
            fullLabelShape.remove()
          }
          return
        }

        if (fullLabelShape) {
          fullLabelShape.attr({
            y: radius + 12,
            text: String(cfg?.fullLabel || ''),
          })
          return
        }

        group.addShape('text', {
          attrs: {
            x: 0,
            y: radius + 12,
            text: String(cfg?.fullLabel || ''),
            fill: '#334155',
            fontSize: 11,
            fontWeight: 500,
            textAlign: 'center',
            textBaseline: 'top',
          },
          name: 'full-label',
        })
      },
    },
    'single-node',
  )
  pathGraphNodeRegistered = true
}

function similarityLabel(similarity: number) {
  if (similarity >= 0.8) return '高'
  if (similarity >= 0.65) return '中'
  return '低'
}

function updateGraphHoverTipPosition(canvasX: number, canvasY: number, offset = 14) {
  if (!graphRef.value) return
  const width = graphRef.value.clientWidth
  const height = graphRef.value.clientHeight
  const tipWidth = 270
  const tipHeight = 94
  graphHoverTip.value.x = Math.min(Math.max(8, canvasX + offset), Math.max(8, width - tipWidth - 8))
  graphHoverTip.value.y = Math.min(Math.max(8, canvasY + offset), Math.max(8, height - tipHeight - 8))
}

function hideGraphHoverTip() {
  graphHoverTip.value.visible = false
}

function resolveGraphPointFromCanvas(canvasX: number, canvasY: number) {
  const fallback = {
    x: Number(canvasX || 0),
    y: Number(canvasY || 0),
  }
  if (!g6Graph?.getPointByCanvas) return fallback

  const point = g6Graph.getPointByCanvas(fallback.x, fallback.y)
  const x = Number(point?.x)
  const y = Number(point?.y)
  if (!Number.isFinite(x) || !Number.isFinite(y)) return fallback
  return { x, y }
}

function resolveGraphPointFromEvent(evt: any) {
  const x = Number(evt?.x)
  const y = Number(evt?.y)
  if (Number.isFinite(x) && Number.isFinite(y)) {
    return { x, y }
  }
  return resolveGraphPointFromCanvas(Number(evt?.canvasX || 0), Number(evt?.canvasY || 0))
}

function clearPathNodeLongPressTimer() {
  // Drag enters directly on pointer move; no long-press timer is needed.
}

function findPathNodeByGraphNodeId(nodeId: string) {
  return pathNodes.value.find(item => item.id === nodeId) || null
}

function buildPathDragPreviewEdgeId(sourceNodeId: string, targetNodeId: string) {
  return `__path_drag_preview__${sourceNodeId}__${targetNodeId}`
}

function clearPathDragPreviewEdges() {
  if (!g6Graph) {
    pathDragPreviewEdgeIds.clear()
    pathNodeDragPreviewNodes = null
    return
  }

  pathDragPreviewEdgeIds.forEach((edgeId) => {
    const edgeItem = g6Graph.findById(edgeId)
    if (edgeItem) {
      g6Graph.removeItem(edgeItem)
    }
  })
  pathDragPreviewEdgeIds.clear()
  pathNodeDragPreviewNodes = null
}

function buildPathDragPreviewEdgeModels(orderedNodes: PathNode[]) {
  const edges: Array<Record<string, unknown>> = []
  for (let index = 0; index < orderedNodes.length - 1; index += 1) {
    const sourceNode = orderedNodes[index]
    const targetNode = orderedNodes[index + 1]
    if (!sourceNode?.id || !targetNode?.id) continue
    const isStartEdge = sourceNode.jobId === START_ANCHOR_JOB_ID

    edges.push({
      id: buildPathDragPreviewEdgeId(sourceNode.id, targetNode.id),
      source: sourceNode.id,
      target: targetNode.id,
      label: isStartEdge ? '起点候选' : '候选',
      capture: false,
      style: {
        lineWidth: isStartEdge ? 3.2 : 2.6,
        stroke: isStartEdge ? 'rgba(14,165,233,0.98)' : 'rgba(56,189,248,0.92)',
        endArrow: true,
        lineDash: isStartEdge ? [4, 3] : [8, 5],
        shadowColor: isStartEdge ? 'rgba(14,165,233,0.45)' : 'rgba(56,189,248,0.4)',
        shadowBlur: 10,
        opacity: 0.96,
      },
      labelCfg: {
        autoRotate: true,
        style: {
          fill: '#0c4a6e',
          fontSize: 10,
          fontWeight: 600,
          background: {
            fill: 'rgba(240,249,255,0.9)',
            padding: [2, 4, 2, 4],
            radius: 4,
          },
        },
      },
    })
  }

  return edges
}

function syncPathDragPreviewEdges(orderedNodes: PathNode[]) {
  if (!g6Graph) return
  const edgeModels = buildPathDragPreviewEdgeModels(orderedNodes)
  const nextEdgeIds = new Set(edgeModels.map(model => String(model.id || '')))

  pathDragPreviewEdgeIds.forEach((edgeId) => {
    if (nextEdgeIds.has(edgeId)) return
    const edgeItem = g6Graph.findById(edgeId)
    if (edgeItem) {
      g6Graph.removeItem(edgeItem)
    }
  })

  edgeModels.forEach((model) => {
    const edgeId = String(model.id || '')
    if (!edgeId) return
    const existing = g6Graph.findById(edgeId)
    if (existing) {
      g6Graph.updateItem(existing, {
        style: model.style,
        label: model.label,
        labelCfg: model.labelCfg,
      })
      return
    }
    g6Graph.addItem('edge', model)
  })

  pathDragPreviewEdgeIds.clear()
  nextEdgeIds.forEach(edgeId => pathDragPreviewEdgeIds.add(edgeId))
}

function updatePathDragPreview() {
  if (!pathNodeDragActiveId) return
  const positionSnapshot = buildPathNodePositionSnapshot()
  const orderedNodes = buildNearestNeighborPathNodes(positionSnapshot)
  pathNodeDragPreviewNodes = orderedNodes
  syncPathDragPreviewEdges(orderedNodes)
}

function applyPathGraphDragVisualState(activeNodeId: string) {
  if (!g6Graph || !activeNodeId) return
  g6Graph.getEdges().forEach((edge: any) => {
    g6Graph.updateItem(edge, {
      style: {
        opacity: 0,
      },
      labelCfg: {
        style: {
          opacity: 0,
        },
      },
    })
  })
  g6Graph.getNodes().forEach((node: any) => {
    const model = node.getModel?.() || {}
    const id = String(model.id || '')
    const isActive = id === activeNodeId
    g6Graph.updateItem(node, {
      style: {
        opacity: isActive ? 1 : 0.18,
        lineWidth: isActive ? 3.2 : 1.2,
        shadowColor: isActive ? 'rgba(59,130,246,0.95)' : 'rgba(148,163,184,0.22)',
        shadowBlur: isActive ? 30 : 0,
      },
      labelCfg: {
        style: {
          opacity: isActive ? 1 : 0.18,
        },
      },
    })
  })
}

function restorePathGraphVisualState() {
  if (!g6Graph) return
  clearPathDragPreviewEdges()
  g6Graph.getNodes().forEach((node: any) => {
    g6Graph.updateItem(node, {
      style: {
        opacity: 1,
        lineWidth: 1.5,
      },
      labelCfg: {
        style: {
          opacity: 1,
        },
      },
    })
  })
  g6Graph.getEdges().forEach((edge: any) => {
    g6Graph.updateItem(edge, {
      style: {
        opacity: 1,
      },
      labelCfg: {
        style: {
          opacity: 1,
        },
      },
    })
  })
}

function updatePathDraggedNodePosition(pointerGraphX: number, pointerGraphY: number) {
  if (!g6Graph || !pathNodeDragActiveId) return
  const nodeItem = g6Graph.findById(pathNodeDragActiveId)
  if (!nodeItem) return

  const width = Math.max(120, Number(graphRef.value?.clientWidth || 0))
  const height = Math.max(120, Number(graphRef.value?.clientHeight || 0))
  const targetX = Number(pointerGraphX || 0) + Number(pathNodeDragPointerOffsetX || 0)
  const targetY = Number(pointerGraphY || 0) + Number(pathNodeDragPointerOffsetY || 0)

  const canvasTopLeft = resolveGraphPointFromCanvas(0, 0)
  const canvasBottomRight = resolveGraphPointFromCanvas(width, height)
  const minX = Math.min(canvasTopLeft.x, canvasBottomRight.x) + 34
  const maxX = Math.max(canvasTopLeft.x, canvasBottomRight.x) - 34
  const minY = Math.min(canvasTopLeft.y, canvasBottomRight.y) + 34
  const maxY = Math.max(canvasTopLeft.y, canvasBottomRight.y) - 34

  const clampedX = maxX > minX
    ? Math.min(Math.max(minX, targetX), maxX)
    : targetX
  const clampedY = maxY > minY
    ? Math.min(Math.max(minY, targetY), maxY)
    : targetY

  g6Graph.updateItem(nodeItem, {
    x: clampedX,
    y: clampedY,
    style: {
      opacity: 1,
      lineWidth: 3.2,
      shadowColor: 'rgba(59,130,246,0.95)',
      shadowBlur: 32,
    },
  })
  graphNodePositionCache.set(pathNodeDragActiveId, { x: clampedX, y: clampedY })
  updatePathDragPreview()
}

function buildPathNodePositionSnapshot() {
  const snapshot = new Map<string, { x: number; y: number }>()
  const nodes = pathNodes.value
  const width = graphRef.value?.clientWidth || 800
  const centerY = 180
  const gap = Math.max(120, Math.floor((width - 160) / Math.max(1, nodes.length - 1)))

  nodes.forEach((node, index) => {
    const cached = graphNodePositionCache.get(node.id)
    snapshot.set(node.id, {
      x: cached?.x ?? (80 + index * gap),
      y: cached?.y ?? centerY,
    })
  })

  return snapshot
}

function buildNearestNeighborPathNodes(
  positionSnapshot: Map<string, { x: number; y: number }>,
) {
  const anchorNode = pathNodes.value.find(item => isStartAnchorNode(item)) || null
  const candidates = pathNodes.value.filter(item => !isStartAnchorNode(item))
  if (!anchorNode) {
    return ensureStartAnchor(normalizePathNodeStages(candidates.map(item => ({ ...item }))))
  }
  if (candidates.length <= 1) {
    return ensureStartAnchor(normalizePathNodeStages(candidates.map(item => ({ ...item }))))
  }

  const originalOrder = new Map(pathNodes.value.map((item, index) => [item.id, index]))
  const remaining = candidates.map(item => ({ ...item }))
  const ordered: PathNode[] = []
  let currentId = anchorNode.id

  while (remaining.length) {
    const currentPos = positionSnapshot.get(currentId) || { x: 0, y: 0 }
    let bestIndex = 0
    let bestDistance = Number.POSITIVE_INFINITY

    remaining.forEach((node, index) => {
      const nodePos = positionSnapshot.get(node.id) || { x: 0, y: 0 }
      const dx = nodePos.x - currentPos.x
      const dy = nodePos.y - currentPos.y
      const distance = Math.hypot(dx, dy)

      if (distance + 1e-6 < bestDistance) {
        bestDistance = distance
        bestIndex = index
        return
      }

      if (Math.abs(distance - bestDistance) <= 1e-6) {
        const currentOrder = Number(originalOrder.get(node.id) ?? Number.MAX_SAFE_INTEGER)
        const bestOrder = Number(originalOrder.get(remaining[bestIndex]?.id || '') ?? Number.MAX_SAFE_INTEGER)
        if (currentOrder < bestOrder) {
          bestIndex = index
        }
      }
    })

    const [nextNode] = remaining.splice(bestIndex, 1)
    if (!nextNode) break
    ordered.push(nextNode)
    currentId = nextNode.id
  }

  return ensureStartAnchor(normalizePathNodeStages(ordered))
}

function buildPathJobSequenceSignature(nodes: PathNode[]) {
  return nodes.map(item => item.jobId).join('>')
}

function shouldActivatePathNodeDragByMove(pointerGraphX: number, pointerGraphY: number) {
  const dx = pointerGraphX - pathNodePressStartX
  const dy = pointerGraphY - pathNodePressStartY
  return Math.hypot(dx, dy) >= PATH_NODE_DRAG_ACTIVATE_THRESHOLD
}

function activatePathNodeDrag(nodeId: string, pointerGraphX: number, pointerGraphY: number) {
  if (!nodeId || pathNodeDragActiveId || !g6Graph) return
  const nodeItem = g6Graph.findById(nodeId)
  if (!nodeItem) return

  const model = nodeItem.getModel?.()
  const nodeX = Number(model?.x)
  const nodeY = Number(model?.y)
  pathNodeDragPointerOffsetX = Number.isFinite(nodeX) ? nodeX - pointerGraphX : 0
  pathNodeDragPointerOffsetY = Number.isFinite(nodeY) ? nodeY - pointerGraphY : 0

  pathNodeDragActiveId = nodeId
  suppressNodeClickOnce = true
  hideGraphHoverTip()
  applyPathGraphDragVisualState(pathNodeDragActiveId)
  updatePathDraggedNodePosition(pointerGraphX, pointerGraphY)
  g6Graph?.get('canvas')?.set?.('cursor', 'grabbing')
}

function startPathNodeLongPress(nodeId: string, pointerGraphX: number, pointerGraphY: number) {
  pathNodeLongPressCandidateId = nodeId
  pathNodePointerPressed = true
  pathNodePressStartX = pointerGraphX
  pathNodePressStartY = pointerGraphY
  pathNodeLastPointerX = pointerGraphX
  pathNodeLastPointerY = pointerGraphY
  clearPathNodeLongPressTimer()
}

function resetPathNodePressState() {
  pathNodePointerPressed = false
  pathNodeLongPressCandidateId = ''
  pathNodePressStartX = 0
  pathNodePressStartY = 0
  pathNodeLastPointerX = 0
  pathNodeLastPointerY = 0
  pathNodeDragPointerOffsetX = 0
  pathNodeDragPointerOffsetY = 0
}

function finishPathNodeDrag() {
  if (!pathNodeDragActiveId) {
    resetPathNodePressState()
    clearPathNodeLongPressTimer()
    return
  }

  syncGraphNodePositionCache()
  const nextNodes = (pathNodeDragPreviewNodes || []).length
    ? (pathNodeDragPreviewNodes || []).map(node => ({ ...node }))
    : buildNearestNeighborPathNodes(buildPathNodePositionSnapshot())
  const previousSequence = buildPathJobSequenceSignature(pathNodes.value)
  const nextSequence = buildPathJobSequenceSignature(nextNodes)
  const pathChanged = previousSequence !== nextSequence
  if (pathChanged) {
    pushUndoSnapshot()
    clearRedoStack()
    pathNodes.value = nextNodes
    pathEdges.value = buildFallbackEdges(pathNodes.value)
    queueRealtimeEvaluate()
  }

  restorePathGraphVisualState()
  pathNodeDragActiveId = ''
  pathNodeDragPointerOffsetX = 0
  pathNodeDragPointerOffsetY = 0
  resetPathNodePressState()
  clearPathNodeLongPressTimer()
  g6Graph?.get('canvas')?.set?.('cursor', 'default')
}

function releasePathNodePointer() {
  if (pathNodeDragActiveId) {
    finishPathNodeDrag()
    return
  }
  clearPathNodeLongPressTimer()
  resetPathNodePressState()
}

function handleGlobalPointerUp() {
  releasePathNodePointer()
}

function isStartAnchorNode(node: PathNode) {
  return node.jobId === START_ANCHOR_JOB_ID
}

function ensureStartAnchor(nodes: PathNode[]) {
  const withoutAnchor = nodes.filter(item => !isStartAnchorNode(item))
  return [
    { ...START_ANCHOR_NODE },
    ...withoutAnchor,
  ]
}

function getBackendPathNodes() {
  return pathNodes.value.filter(item => !isStartAnchorNode(item))
}

function normalizePathEdgesByNodeId(nodes: PathNode[], edges: PathEdge[]) {
  const nodeIdSet = new Set(nodes.map(node => String(node.id || '').trim()).filter(Boolean))
  const nodeIdByJobId = new Map<string, string>()

  nodes.forEach((node) => {
    const nodeId = String(node.id || '').trim()
    const jobId = String(node.jobId || '').trim()
    if (!nodeId || !jobId) return
    nodeIdByJobId.set(jobId, nodeId)
  })

  return (edges || [])
    .map((edge) => {
      const sourceRaw = String(edge?.source || '').trim()
      const targetRaw = String(edge?.target || '').trim()

      const source = nodeIdSet.has(sourceRaw) ? sourceRaw : (nodeIdByJobId.get(sourceRaw) || '')
      const target = nodeIdSet.has(targetRaw) ? targetRaw : (nodeIdByJobId.get(targetRaw) || '')
      if (!source || !target) return null

      return {
        ...edge,
        source,
        target,
      }
    })
    .filter(Boolean) as PathEdge[]
}

function resolvePathNodeLabelByEdgeNodeId(nodeId: string) {
  const normalized = String(nodeId || '').trim()
  if (!normalized) return ''

  const node = pathNodes.value.find(item => String(item.id || '').trim() === normalized)
  if (node) {
    return String(node.jobName || '').trim() || resolveJobName(node.jobId)
  }
  return normalized
}

function isInferredPathEdge(edge: Pick<PathEdge, 'relationType' | 'reason'> & { inferred?: boolean }) {
  if (edge.relationType === 'start') return false
  if (edge.inferred) return true
  const reason = String(edge.reason || '')
  return reason.includes('自动补全') || reason.includes('等待实时评估')
}

function withStartEdge(nodes: PathNode[], edges: PathEdge[]) {
  const normalizedEdges = normalizePathEdgesByNodeId(nodes, edges)
  const realNodes = nodes.filter(item => !isStartAnchorNode(item))
  if (!realNodes.length) return normalizedEdges
  const firstReal = realNodes[0]
  const hasStart = normalizedEdges.some(item => item.source === START_ANCHOR_NODE.id && item.target === firstReal.id)
  if (hasStart) return normalizedEdges
  return [
    {
      source: START_ANCHOR_NODE.id,
      target: firstReal.id,
      relationType: 'start',
      similarity: 1,
      difficulty: 'low',
      reason: '从职业起点进入第一目标岗位',
      inferred: false,
    },
    ...normalizedEdges,
  ]
}

function resolveJobName(jobId: string) {
  const fromPool = jobPool.value.find(item => item.jobId === jobId)?.jobName
  if (fromPool) return fromPool

  const fromFavorite = favorites.value.find(item => item.jobId === jobId)?.jobName
  if (fromFavorite) return fromFavorite

  if (bestMatch.value?.jobId === jobId) return bestMatch.value.jobName

  const fromRecommendation = (recommendations.value?.otherRecommendations || []).find(item => item.jobId === jobId)?.jobName
  if (fromRecommendation) return fromRecommendation

  return jobId
}

function buildFallbackEdges(nodes: PathNode[]) {
  const list: PathEdge[] = []
  for (let i = 0; i < nodes.length - 1; i += 1) {
    const source = nodes[i]
    const target = nodes[i + 1]
    const isStartEdge = source.id === START_ANCHOR_NODE.id || source.jobId === START_ANCHOR_JOB_ID
    list.push({
      source: source.id,
      target: target.id,
      relationType: isStartEdge ? 'start' : 'transition',
      similarity: isStartEdge ? 1 : 0.62,
      difficulty: isStartEdge ? 'low' : 'medium',
      reason: isStartEdge ? '从职业起点进入第一目标岗位' : '等待实时评估更新关系说明',
      inferred: !isStartEdge,
    })
  }
  return list
}

function normalizePathNodeStages(nodes: PathNode[]) {
  return nodes.map((item, index, arr) => {
    if (index === 0) return { ...item, stage: 'start' }
    if (index === arr.length - 1) return { ...item, stage: 'target' }
    return { ...item, stage: 'milestone' }
  })
}

async function fetchRecommendations(silentWhenNoProfile = false) {
  if (appStore.hasProfile !== true) {
    try {
      await appStore.ensureProfileSnapshot(true)
    } catch {
      if (!silentWhenNoProfile) {
        ElMessage.error('获取画像状态失败，请稍后重试')
      }
      return
    }
  }

  if (appStore.hasProfile === false) {
    recommendations.value = null
    if (!silentWhenNoProfile) {
      ElMessage.warning('请先完成能力评估后再进行岗位推荐')
    }
    return
  }

  recommendLoading.value = true
  try {
    const result = await getMatchRecommendationsFromPython({
      userId: appStore.currentStudentId,
      profile: appStore.profileSnapshot,
      intent: buildRecommendIntent(),
    })
    if (!result.ok) {
      recommendations.value = null
      if (!silentWhenNoProfile) {
        ElMessage.error(result.message || '获取推荐失败')
      }
      return
    }
    recommendations.value = result.data ?? null
    if (recommendations.value?.bestMatch && !selectedTargetJobId.value) {
      selectedTargetJobId.value = recommendations.value.bestMatch.jobId
    }
  } finally {
    recommendLoading.value = false
  }
}

async function fetchFavorites() {
  favoriteLoading.value = true
  try {
    const response = await getFavoriteJobs()
    const payload = extractPayload<FavoriteJobsResult>(response as { data: ApiResponse<FavoriteJobsResult> })
    favorites.value = payload?.list ?? []
  } finally {
    favoriteLoading.value = false
  }
}

async function fetchManualExploreJobs(page = 1) {
  manualExploreLoading.value = true
  try {
    const response = await getJobList({
      page,
      pageSize: manualExplorePageSize,
      keyword: String(manualExploreKeyword.value || '').trim() || undefined,
      sortBy: 'updatedAt',
      sortOrder: 'desc',
    })
    const payload = extractPayload<JobListResult>(response as { data: ApiResponse<JobListResult> })
    manualExplorePage.value = Number(payload?.page || page)
    manualExploreTotal.value = Number(payload?.total || 0)
    jobPool.value = payload?.list ?? []
  } finally {
    manualExploreLoading.value = false
  }
}

function handleSearchManualJobs() {
  fetchManualExploreJobs(1).catch(() => {
    ElMessage.error('搜索岗位失败，请稍后重试')
  })
}

function changeManualExplorePage(step: number) {
  const nextPage = manualExplorePage.value + step
  if (nextPage < 1 || nextPage > manualExplorePageCount.value) return
  fetchManualExploreJobs(nextPage).catch(() => {
    ElMessage.error('分页加载失败，请稍后重试')
  })
}

async function fetchMatchHistory(autoSelectPreferred = true) {
  historyLoading.value = true
  try {
    const response = await getMatchHistory()
    const result = response.data as ApiResponse<MatchHistoryResult>
    if (!isSuccessCode(Number(result.code))) return
    const payload = extractPayload<MatchHistoryResult>(response as { data: ApiResponse<MatchHistoryResult> })
    historyList.value = payload?.list ?? []

    const preferred = payload?.list?.find(item => item.recordId === payload.pinnedRecordId) || payload?.list?.[0]
    if (preferred && autoSelectPreferred && !currentMatchRecord.value) {
      await loadMatchDetail(preferred.recordId)
    }
  } finally {
    historyLoading.value = false
  }
}

async function loadMatchDetail(recordId: string) {
  jobDetail.value = null
  matchLoading.value = true
  try {
    const response = await getMatchHistoryDetail(recordId)
    const result = response.data as ApiResponse<MatchRecordDetail>
    if (!isSuccessCode(Number(result.code))) {
      ElMessage.error(result.msg || '读取匹配详情失败')
      return
    }
    const payload = extractPayload<MatchRecordDetail>(response as { data: ApiResponse<MatchRecordDetail> })
    currentMatchRecord.value = normalizeMatchRecordDetail(payload)
  } finally {
    matchLoading.value = false
  }
}

// 点击收藏岗位：直接按 jobId 查询岗位详细信息并展示，不再调用 match/analyze
async function loadJobDetail(jobId: string) {
  if (!jobId) return
  favoriteLoading.value = true
  try {
    const response = await getJobDetail(jobId)
    const payload = extractPayload<JobDetailResult>(response as { data: ApiResponse<JobDetailResult> })
    if (!payload) {
      ElMessage.error('获取岗位详情失败')
      return
    }
    jobDetail.value = payload
    currentMatchRecord.value = null
  } finally {
    favoriteLoading.value = false
  }
}

// 把当前展示的岗位添加到智能小助手对话：输入框展示岗位名，发送时底层转为 jobId:<id>
function handleAddJobToAssistant() {
  const detail = jobDetail.value
  if (!detail) return
  openGlobalAssistant({
    routePath: '/match',
    pageTitle: '职业规划',
    contextPrompt: '已把岗位添加到对话，请结合该岗位给出建议。',
    pendingJob: {
      jobId: String(detail.jobId || '').trim(),
      jobName: String(detail.jobName || '').trim() || '岗位',
    },
  })
}

function resolveRecommendationMetaForAnalyze(jobId: string) {
  const normalizedJobId = String(jobId || '').trim()
  if (!normalizedJobId) return null

  const best = recommendations.value?.bestMatch
  if (best && String(best.jobId || '').trim() === normalizedJobId) {
    return {
      recommendedOverallScore: Number(best.overallScore || 0),
      recommendedDimensionScores: best.dimensionScores || null,
      recommendationStatus: recommendations.value?.recommendationStatus || 'succeeded',
    }
  }

  const other = (recommendations.value?.otherRecommendations || [])
    .find(item => String(item.jobId || '').trim() === normalizedJobId)

  if (!other) return null
  return {
    recommendedOverallScore: Number(other.overallScore || 0),
    recommendedDimensionScores: null,
    recommendationStatus: recommendations.value?.recommendationStatus || 'succeeded',
  }
}

async function runMatchAnalyze(jobId: string, source: 'auto' | 'favorite' | 'manual') {
  jobDetail.value = null
  const existing = historyList.value.find(item => item.jobId === jobId)
  let overwriteSameJob = false
  const recommendationMeta = source === 'auto' ? resolveRecommendationMetaForAnalyze(jobId) : null

  if (existing) {
    try {
      await ElMessageBox.confirm(
        '已有该岗位匹配推荐，继续匹配将覆盖原记录。是否继续？',
        '提示',
        {
          confirmButtonText: '继续匹配',
          cancelButtonText: '取消并查看已有记录',
          type: 'warning',
          lockScroll: false,
        },
      )
      overwriteSameJob = true
    } catch {
      await loadMatchDetail(existing.recordId)
      return
    }
  }

  matchLoading.value = true
  try {
    const response = await analyzeMatch({
      jobId,
      source,
      saveToHistory: true,
      overwriteSameJob,
      sourceMeta: {
        from: 'match-analysis-view',
        ...(recommendationMeta
          ? {
              recommendedOverallScore: recommendationMeta.recommendedOverallScore,
              recommendedDimensionScores: recommendationMeta.recommendedDimensionScores,
              recommendationStatus: recommendationMeta.recommendationStatus,
            }
          : {}),
      },
    })

    const result = response.data as ApiResponse<MatchRecordDetail | { existingRecord?: MatchRecordDetail }>

    if (isConflictCode(Number(result.code))) {
      const payload = extractPayload<{ existingRecord?: MatchRecordDetail }>(response as { data: ApiResponse<{ existingRecord?: MatchRecordDetail }> })
      if (payload?.existingRecord) {
        currentMatchRecord.value = normalizeMatchRecordDetail(payload.existingRecord)
      }
      ElMessage.info('已展示该岗位现有匹配记录')
      await fetchMatchHistory(false)
      return
    }

    if (!isSuccessCode(Number(result.code))) {
      ElMessage.error(result.msg || '匹配失败')
      return
    }
    const payload = extractPayload<MatchRecordDetail>(response as { data: ApiResponse<MatchRecordDetail> })
    currentMatchRecord.value = normalizeMatchRecordDetail(payload)
    await fetchMatchHistory(false)
    ElMessage.success('匹配分析已更新')
  } finally {
    matchLoading.value = false
  }
}

async function handlePinRecord(recordId: string) {
  const target = historyList.value.find(item => item.recordId === recordId)
  if (!target) return

  if (target.pinned) {
    const response = await unpinMatchRecord(recordId)
    const result = response.data as ApiResponse<{ pinnedRecordId: null }>
    if (!isSuccessCode(Number(result.code))) {
      ElMessage.error(result.msg || '取消置顶失败')
      return
    }
    historyList.value = historyList.value.map(item => ({ ...item, pinned: false }))
    ElMessage.success('已取消置顶')
    return
  }

  const response = await pinMatchRecord(recordId)
  const result = response.data as ApiResponse<{ pinnedRecordId: string }>
  if (!isSuccessCode(Number(result.code))) {
    ElMessage.error(result.msg || '置顶失败')
    return
  }
  historyList.value = historyList.value.map(item => ({ ...item, pinned: item.recordId === recordId }))
  ElMessage.success('已置顶该记录')
}

async function handleToggleCurrentJobFavorite() {
  const jobId = currentMatchJobId.value
  if (!jobId) {
    ElMessage.warning('当前没有可收藏的岗位')
    return
  }

  favoriteActionLoading.value = true
  try {
    if (isCurrentMatchFavorited.value) {
      const response = await removeFavoriteJob(jobId)
      const result = response.data as ApiResponse<null>
      if (!isSuccessCode(Number(result.code))) {
        ElMessage.error(result.msg || '取消收藏失败')
        return
      }
      await fetchFavorites()
      ElMessage.success('已取消收藏')
      return
    }

    const response = await addFavoriteJob(jobId)
    const result = response.data as ApiResponse<null>
    if (!isSuccessCode(Number(result.code))) {
      ElMessage.error(result.msg || '收藏失败')
      return
    }
    await fetchFavorites()
    ElMessage.success('已加入收藏')
  } finally {
    favoriteActionLoading.value = false
  }
}

async function handleDeleteRecord(recordId: string) {
  try {
    await ElMessageBox.confirm('删除后不可恢复，是否继续？', '删除匹配记录', {
      confirmButtonText: '删除',
      cancelButtonText: '取消',
      type: 'warning',
      lockScroll: false,
    })
  } catch {
    return
  }

  const response = await deleteMatchHistoryRecord(recordId)
  const result = response.data as ApiResponse<null>
  if (!isSuccessCode(Number(result.code))) {
    ElMessage.error(result.msg || '删除失败')
    return
  }

  const deletingCurrent = currentMatchRecord.value?.recordId === recordId
  await fetchMatchHistory(false)
  if (deletingCurrent) {
    const fallback = historyPrimaryRecord.value || historyList.value[0] || null
    if (fallback) {
      await loadMatchDetail(fallback.recordId)
    } else {
      currentMatchRecord.value = null
    }
  }
  ElMessage.success('匹配记录已删除')
}

function requestPathGraphRelayout() {
  clearPathDragPreviewEdges()
  graphNodePositionCache.clear()
  forceGraphFitOnNextRender = true
  hideGraphHoverTip()
  clearPathNodeLongPressTimer()
  resetPathNodePressState()
  pathNodeDragActiveId = ''
  suppressNodeClickOnce = false
  g6Graph?.get('canvas')?.set?.('cursor', 'default')
}

function applyPathDraft(payload: PathDraftResult | null, options: PathApplyOptions = {}) {
  if (options.relayoutGraph) {
    requestPathGraphRelayout()
  }
  currentPathDraft.value = payload
  currentPathDisplayName.value = payload ? String(options.pathName || '路径草稿').trim() : ''
  pathNodes.value = ensureStartAnchor(payload?.pathNodes ?? [])
  pathEdges.value = withStartEdge(pathNodes.value, payload?.pathEdges ?? buildFallbackEdges(pathNodes.value))
  pathEvaluation.value = payload?.evaluation ?? null
  stagePlanDetailVisible.value = false
}

function applySavedPathToSandboxState(savedPath: SavedPathResult | null, options: PathApplyOptions = {}) {
  if (options.relayoutGraph) {
    requestPathGraphRelayout()
  }
  currentPathDisplayName.value = savedPath ? String(options.pathName || savedPath.pathName || '').trim() : ''
  pathNodes.value = ensureStartAnchor(savedPath?.pathNodes ?? [])
  pathEdges.value = withStartEdge(pathNodes.value, savedPath?.pathEdges ?? buildFallbackEdges(pathNodes.value))
  pathEvaluation.value = savedPath?.evaluation ?? null
  currentPathDraft.value = null
  stagePlanDetailVisible.value = false
}

function clearSandboxPathState(options: PathApplyOptions = {}) {
  if (options.relayoutGraph) {
    requestPathGraphRelayout()
  }
  pathNodes.value = ensureStartAnchor([])
  pathEdges.value = buildFallbackEdges(pathNodes.value)
  pathEvaluation.value = null
  currentPathDraft.value = null
  currentPathDisplayName.value = ''
  stagePlanDetailVisible.value = false
}

async function loadCareerPathDetail(payload: {
  pathId?: string
  draftId?: string
}) {
  const response = await getCareerPathDetail(payload)
  const result = response.data as ApiResponse<CareerPathDetailResult>
  if (!isSuccessCode(Number(result.code))) {
    throw new Error(result.msg || '读取路径详情失败')
  }
  return extractPayload<CareerPathDetailResult>(response as { data: ApiResponse<CareerPathDetailResult> }) || null
}

async function fetchLatestPath() {
  pathLoading.value = true
  try {
    const response = await getLatestCareerPath()
    const result = response.data as ApiResponse<LatestPathResult>
    if (!isSuccessCode(Number(result.code))) return
    const payload = extractPayload<LatestPathResult>(response as { data: ApiResponse<LatestPathResult> })
    if (payload?.latestPath?.pathId) {
      const detail = await loadCareerPathDetail({ pathId: payload.latestPath.pathId }).catch((error) => {
        ElMessage.error(error instanceof Error ? error.message : '读取最新路径详情失败')
        return null
      })
      if (detail?.source === 'saved' && detail.savedPath) {
        applySavedPathToSandboxState(detail.savedPath, {
          relayoutGraph: true,
          pathName: payload.latestPath?.pathName || detail.savedPath.pathName,
        })
        return
      }
      ElMessage.warning('最新路径详情不存在，已回退为空沙盘')
      clearSandboxPathState({ relayoutGraph: true })
      return
    }

    if (payload?.draft?.draftId) {
      const detail = await loadCareerPathDetail({ draftId: payload.draft.draftId }).catch((error) => {
        ElMessage.error(error instanceof Error ? error.message : '读取草稿详情失败')
        return null
      })
      if (detail?.source === 'draft' && detail.draft) {
        applyPathDraft(detail.draft, { relayoutGraph: true, pathName: '路径草稿' })
        return
      }
      ElMessage.warning('草稿详情不存在，已回退为空沙盘')
      clearSandboxPathState({ relayoutGraph: true })
      return
    }

    clearSandboxPathState({ relayoutGraph: true })
  } finally {
    pathLoading.value = false
  }
}

async function handleAutoPlan() {
  if (!selectedTargetJobId.value) {
    ElMessage.warning('请先选择目标岗位')
    return
  }
  pathLoading.value = true
  try {
    pushUndoSnapshot()
    clearRedoStack()
    const createResponse = await autoPlanCareerPath({
      targetJobId: selectedTargetJobId.value,
      mode: autoPlanMode.value,
    })
    const createResult = createResponse.data as ApiResponse<AutoPlanJobCreateResult>
    if (!isSuccessCode(Number(createResult.code))) {
      ElMessage.error(createResult.msg || '自动规划任务创建失败')
      return
    }

    const created = extractPayload<AutoPlanJobCreateResult>(createResponse as { data: ApiResponse<AutoPlanJobCreateResult> })
    if (!created?.autoPlanJobId) {
      ElMessage.error('自动规划任务创建失败：缺少任务ID')
      return
    }

    const completed = await pollAutoPlanJob(created.autoPlanJobId, created.pollAfterMs ?? 2000)
    if (completed?.result?.savedPath) {
      applySavedPathToSandboxState(completed.result.savedPath, {
        relayoutGraph: true,
        pathName: completed.result.savedPath.pathName,
      })
    } else {
      applyPathDraft(completed?.result?.draft ?? null, { relayoutGraph: true, pathName: '路径草稿' })
    }
    await fetchLatestPath()
    scrollToSandboxCard()
    ElMessage.success(completed?.result?.savedPath?.pathName ? `AI自动规划并保存成功：${completed.result.savedPath.pathName}` : 'AI自动规划并保存成功')
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '自动规划失败，请稍后重试')
  } finally {
    pathLoading.value = false
  }
}

async function pollAutoPlanJob(autoPlanJobId: string, initialInterval = 2000, maxRetry = 30) {
  let intervalMs = clampPollMs(initialInterval, 2000)

  for (let index = 0; index < maxRetry; index += 1) {
    const response = await getAutoPlanCareerPathJobStatus(autoPlanJobId)
    const result = response.data as ApiResponse<AutoPlanJobStatusResult>
    if (!isSuccessCode(Number(result.code))) {
      throw new Error(result.msg || '自动规划任务状态查询失败')
    }

    const payload = extractPayload<AutoPlanJobStatusResult>(response as { data: ApiResponse<AutoPlanJobStatusResult> })
    if (!payload) {
      throw new Error('自动规划任务状态返回为空')
    }

    if (payload.status === 'succeeded') {
      return payload
    }

    if (payload.status === 'failed') {
      throw new Error(result.msg || '自动规划失败')
    }

    intervalMs = payload.pollAfterMs ? clampPollMs(payload.pollAfterMs, intervalMs) : intervalMs
    await new Promise((resolve) => setTimeout(resolve, intervalMs))
  }

  throw new Error('自动规划超时，请稍后重试')
}

function scrollToSandboxCard() {
  nextTick(() => {
    sandboxCardRef.value?.scrollIntoView({ behavior: 'smooth', block: 'start' })
  })
}

function getCurrentMatchTarget() {
  const record = currentMatchRecord.value
  if (!record) return null
  return {
    jobId: record.job.jobId,
    jobName: record.job.jobName,
    score: Number(record.match.overallScore || 0),
  }
}

async function handleStartCareerPathPlanning() {
  const target = getCurrentMatchTarget()
  if (!target) {
    ElMessage.warning('请先生成匹配报告后再开始职业路径规划')
    return
  }

  const extraRisk = target.score < 70 ? '当前匹配度偏低，建议优先补齐短板后再执行进阶路径。\n\n' : ''
  try {
    await ElMessageBox.confirm(
      `${extraRisk}即将基于当前岗位「${target.jobName}」生成职业路径规划并保存，是否继续？`,
      '开始职业路径规划',
      {
        confirmButtonText: '继续',
        cancelButtonText: '取消',
        type: target.score < 70 ? 'warning' : 'info',
        lockScroll: false,
      },
    )
  } catch {
    return
  }

  pendingPlanJobId.value = target.jobId
  autoPlanMode.value = target.score < 70 ? 'conservative' : 'balanced'
  startPlanModeDialogVisible.value = true
}

async function submitStartPlanMode() {
  if (!pendingPlanJobId.value) {
    startPlanModeDialogVisible.value = false
    return
  }

  selectedTargetJobId.value = pendingPlanJobId.value
  startPlanModeDialogVisible.value = false
  scrollToSandboxCard()
  await handleAutoPlan()
  scrollToSandboxCard()
}

function addNodeByJobId(jobId: string) {
  if (!jobId) return
  if (pathNodes.value.some(node => node.jobId === jobId)) {
    ElMessage.info('该岗位已在当前路径中')
    return
  }
  const jobName = resolveJobName(jobId)
  const normalizeJobName = (name: string) => String(name || '').trim().toLowerCase().replace(/[\s_\-]+/g, '')
  const normalizedTargetName = normalizeJobName(jobName)
  if (
    normalizedTargetName
    && pathNodes.value.some(node => normalizeJobName(node.jobName || '') === normalizedTargetName)
  ) {
    ElMessage.warning('同名岗位已在当前路径中，请勿重复添加')
    return
  }
  pushUndoSnapshot()
  clearRedoStack()
  const nextNode: PathNode = {
    id: `pn_local_${Date.now()}_${Math.random().toString(36).slice(2, 6)}`,
    jobId,
    jobName,
    stage: 'milestone',
  }

  const withoutAnchor = pathNodes.value.filter(item => !isStartAnchorNode(item))
  const list = ensureStartAnchor(normalizePathNodeStages([...withoutAnchor, nextNode]))
  pathNodes.value = list
  pathEdges.value = buildFallbackEdges(pathNodes.value)
  queueRealtimeEvaluate()
}

function removePathNode(index: number) {
  if (index === 0) {
    ElMessage.warning('职业起点为固定节点，不能删除')
    return
  }
  pushUndoSnapshot()
  clearRedoStack()
  const list = pathNodes.value.filter((_, idx) => idx !== index)
  const withoutAnchor = list.filter(item => !isStartAnchorNode(item))
  pathNodes.value = ensureStartAnchor(normalizePathNodeStages(withoutAnchor))
  pathEdges.value = buildFallbackEdges(pathNodes.value)
  queueRealtimeEvaluate()
}

function movePathNode(fromIndex: number, toIndex: number) {
  if (fromIndex === 0 || toIndex === 0) {
    ElMessage.warning('职业起点为固定节点，不能参与排序')
    return
  }
  if (fromIndex === toIndex) return
  if (fromIndex < 0 || toIndex < 0) return
  if (fromIndex >= pathNodes.value.length || toIndex >= pathNodes.value.length) return

  pushUndoSnapshot()
  clearRedoStack()
  const list = [...pathNodes.value]
  const [moved] = list.splice(fromIndex, 1)
  if (!moved) return
  list.splice(toIndex, 0, moved)
  const withoutAnchor = list.filter(item => !isStartAnchorNode(item))
  pathNodes.value = ensureStartAnchor(normalizePathNodeStages(withoutAnchor))
  pathEdges.value = buildFallbackEdges(pathNodes.value)
  queueRealtimeEvaluate()
}

function handleDragStart(index: number) {
  draggingIndex.value = index
}

function handleDrop(index: number) {
  if (draggingIndex.value === null) return
  movePathNode(draggingIndex.value, index)
  draggingIndex.value = null
}

function handleDragEnd() {
  draggingIndex.value = null
}

function queueRealtimeEvaluate() {
  if (evaluateDebounceTimer) {
    clearTimeout(evaluateDebounceTimer)
    evaluateDebounceTimer = null
  }
  if (!getBackendPathNodes().length) {
    pathEvaluation.value = null
    return
  }
  evaluateDebounceTimer = setTimeout(() => {
    handleRealtimeEvaluate().catch(() => {})
  }, 650)
}

async function handleRealtimeEvaluate() {
  const requestId = ++evaluateRequestId
  evaluateLoading.value = true
  try {
    const backendPathNodes = getBackendPathNodes()
    if (!backendPathNodes.length) {
      pathEvaluation.value = null
      return
    }

    const response = await evaluateCareerPathRealtime({
      pathNodes: backendPathNodes,
    })
    const result = response.data as ApiResponse<PathDraftResult>
    if (!isSuccessCode(Number(result.code))) {
      ElMessage.error(result.msg || '实时评估失败')
      return
    }
    if (requestId !== evaluateRequestId) return
    const payload = extractPayload<PathDraftResult>(response as { data: ApiResponse<PathDraftResult> })
    if (!payload) return
    applyPathDraft(payload)
  } finally {
    if (requestId === evaluateRequestId) {
      evaluateLoading.value = false
    }
  }
}

async function handleSavePath() {
  if (!canSavePath.value) {
    ElMessage.warning('请至少保留2个路径节点后再保存')
    return
  }
  const prompt = await ElMessageBox.prompt('请输入路径名称', '保存路径', {
    confirmButtonText: '保存',
    cancelButtonText: '取消',
    inputValue: '我的职业路径方案',
    inputPattern: /^.{2,30}$/,
    inputErrorMessage: '名称长度需为2~30个字符',
    lockScroll: false,
  }).catch(() => null)

  const pathName =
    prompt && typeof prompt === 'object' && 'value' in prompt
      ? String(prompt.value || '').trim()
      : ''

  if (!pathName) return

  pathSaving.value = true
  try {
    const backendPathNodes = getBackendPathNodes()
    const createResponse = await saveCareerPath({
      pathName,
      pathNodes: backendPathNodes,
    })
    const createResult = createResponse.data as ApiResponse<SavePathJobCreateResult>
    if (!isSuccessCode(Number(createResult.code))) {
      ElMessage.error(createResult.msg || '保存任务创建失败')
      return
    }

    const created = extractPayload<SavePathJobCreateResult>(createResponse as { data: ApiResponse<SavePathJobCreateResult> })
    if (!created?.saveJobId) {
      ElMessage.error('保存任务创建失败：缺少任务ID')
      return
    }

    const completed = await pollSavePathJob(created.saveJobId, created.pollAfterMs ?? 2000)
    await fetchLatestPath()
    ElMessage.success(completed?.result?.savedPath?.pathName ? `职业路径已保存：${completed.result.savedPath.pathName}` : '职业路径已保存，并完成深度评估')
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '保存失败，请稍后重试')
  } finally {
    pathSaving.value = false
  }
}

async function pollSavePathJob(saveJobId: string, initialInterval = 2000, maxRetry = 30) {
  let intervalMs = clampPollMs(initialInterval, 2000)

  for (let index = 0; index < maxRetry; index += 1) {
    const response = await getSaveCareerPathJobStatus(saveJobId)
    const result = response.data as ApiResponse<SavePathJobStatusResult>
    if (!isSuccessCode(Number(result.code))) {
      throw new Error(result.msg || '保存任务状态查询失败')
    }

    const payload = extractPayload<SavePathJobStatusResult>(response as { data: ApiResponse<SavePathJobStatusResult> })
    if (!payload) {
      throw new Error('保存任务状态返回为空')
    }

    if (payload.status === 'succeeded') {
      return payload
    }

    if (payload.status === 'failed') {
      throw new Error(result.msg || '保存失败')
    }

    intervalMs = payload.pollAfterMs ? clampPollMs(payload.pollAfterMs, intervalMs) : intervalMs
    await new Promise((resolve) => setTimeout(resolve, intervalMs))
  }

  throw new Error('保存超时，请稍后重试')
}

async function handleClearDraftPath() {
  try {
    await ElMessageBox.confirm('将清空当前沙盘草稿。是否继续？', '清空草稿', {
      confirmButtonText: '确认清空',
      cancelButtonText: '取消',
      type: 'warning',
      lockScroll: false,
    })
  } catch {
    return
  }

  pathLoading.value = true
  try {
    await resetCareerPathDraft()
    clearSandboxPathState({ relayoutGraph: true })
    undoStack.value = []
    redoStack.value = []
    ElMessage.success('草稿已清空')
  } finally {
    pathLoading.value = false
  }
}

async function openSavedPathDialog() {
  savedPathDialogVisible.value = true
  historyPage.value = 1
  savedPathListLoading.value = true
  try {
    const response = await getSavedCareerPathList()
    const result = response.data as ApiResponse<SavedPathListResult>
    if (!isSuccessCode(Number(result.code))) {
      ElMessage.error(result.msg || '读取保存列表失败')
      savedPathList.value = []
      return
    }
    const payload = extractPayload<SavedPathListResult>(response as { data: ApiResponse<SavedPathListResult> })
    savedPathList.value = payload?.list ?? []
  } finally {
    savedPathListLoading.value = false
  }
}

function requestApplySavedPath(item: SavedPathSummary) {
  pendingApplySavedPath.value = item
  applySavedPathConfirmVisible.value = true
}

async function applySavedPathToSandbox() {
  const item = pendingApplySavedPath.value
  if (!item) return

  const detail = await loadCareerPathDetail({ pathId: item.pathId }).catch((error) => {
    ElMessage.error(error instanceof Error ? error.message : '读取保存路径详情失败')
    return null
  })
  if (!detail || detail.source !== 'saved' || !detail.savedPath) {
    return
  }

  pushUndoSnapshot()
  clearRedoStack()
  await resetCareerPathDraft()

  applySavedPathToSandboxState(detail.savedPath, { relayoutGraph: true, pathName: item.pathName })
  selectedTargetJobId.value = detail.savedPath.pathNodes?.[detail.savedPath.pathNodes.length - 1]?.jobId || selectedTargetJobId.value
  applySavedPathConfirmVisible.value = false
  pendingApplySavedPath.value = null
  savedPathDialogVisible.value = false
  ElMessage.success(`已应用保存路径：${item.pathName}`)
}

function cancelApplySavedPath() {
  applySavedPathConfirmVisible.value = false
  pendingApplySavedPath.value = null
}

function switchRightPanel(panel: 'recommend' | 'favorite' | 'history') {
  activeRightPanel.value = panel
  if (panel === 'history') {
    historyPage.value = 1
    return
  }
  if (panel === 'favorite') {
    favoritePage.value = 1
  }
}

function changeHistoryPage(step: number) {
  const next = historyPage.value + step
  if (next < 1 || next > historyPageCount.value) return
  historyPage.value = next
}

function changeFavoritePage(step: number) {
  const next = favoritePage.value + step
  if (next < 1 || next > favoritePageCount.value) return
  favoritePage.value = next
}

function requestDeleteSavedPath(item: SavedPathSummary) {
  pendingDeleteSavedPath.value = item
  deleteSavedPathConfirmVisible.value = true
}

function cancelDeleteSavedPath() {
  deleteSavedPathConfirmVisible.value = false
  pendingDeleteSavedPath.value = null
}

async function confirmDeleteSavedPath() {
  const item = pendingDeleteSavedPath.value
  if (!item) {
    cancelDeleteSavedPath()
    return
  }

  savedPathDeletingId.value = item.pathId
  try {
    const response = await deleteSavedCareerPath(item.pathId)
    const result = response.data as ApiResponse<{ pathId: string }>
    if (!isSuccessCode(Number(result.code))) {
      ElMessage.error(result.msg || '删除失败')
      return
    }
    savedPathList.value = savedPathList.value.filter(path => path.pathId !== item.pathId)
    await fetchLatestPath()
    ElMessage.success('保存路径已删除')
    cancelDeleteSavedPath()
  } finally {
    savedPathDeletingId.value = ''
  }
}

function buildGraphData() {
  const nodes = pathNodes.value
  const nodeIdSet = new Set(nodes.map(item => String(item.id || '').trim()).filter(Boolean))

  const width = graphRef.value?.clientWidth || 800
  const centerY = 180
  const gap = Math.max(120, Math.floor((width - 160) / Math.max(1, nodes.length - 1)))

  const graphNodes = nodes.map((item, index) => {
    const isAnchor = item.jobId === START_ANCHOR_JOB_ID
    const fullLabel = String(item.jobName || resolveJobName(item.jobId) || '').trim()
    const color = isAnchor ? '#0f766e' : item.stage === 'target' ? '#7c3aed' : '#2563eb'
    const cached = graphNodePositionCache.get(item.id)
    return {
      id: item.id,
      shortLabel: shortNodeLabel(item.jobName || resolveJobName(item.jobId)),
      fullLabel,
      isAnchor,
      x: cached?.x ?? (80 + index * gap),
      y: cached?.y ?? centerY,
      size: isAnchor ? 58 : item.stage === 'target' ? 54 : 46,
      style: {
        fill: color,
        stroke: isAnchor ? '#14b8a6' : '#0f172a',
        lineWidth: 1.5,
        shadowColor: isAnchor ? 'rgba(20,184,166,0.48)' : 'rgba(59,130,246,0.12)',
        shadowBlur: isAnchor ? 18 : 6,
      },
    }
  })

  const graphEdges = pathEdges.value
    .map((item) => {
      const sourceNodeId = String(item.source || '').trim()
      const targetNodeId = String(item.target || '').trim()
      if (!nodeIdSet.has(sourceNodeId) || !nodeIdSet.has(targetNodeId)) return null
      const inferred = isInferredPathEdge(item)
      return {
        source: sourceNodeId,
        target: targetNodeId,
        label: inferred ? '推演' : item.relationType === 'promotion' ? '晋升' : item.relationType === 'start' ? '起点' : '换岗',
        relationType: item.relationType,
        similarity: item.similarity,
        reason: item.reason,
        inferred,
        style: {
          lineWidth: 2,
          stroke: inferred ? '#ef4444' : item.relationType === 'promotion' ? '#22c55e' : item.relationType === 'start' ? '#0ea5e9' : '#f59e0b',
          endArrow: true,
          lineDash: inferred ? [3, 3] : item.relationType === 'transition' ? [5, 3] : undefined,
        },
        labelCfg: {
          autoRotate: true,
          style: {
            fill: '#475569',
            fontSize: 11,
            background: {
              fill: '#ffffff',
              padding: [2, 4, 2, 4],
              radius: 4,
            },
          },
        },
      }
    })
    .filter(Boolean)

  return {
    nodes: graphNodes,
    edges: graphEdges,
  }
}

function syncGraphNodePositionCache() {
  if (!g6Graph) return
  const nodes = g6Graph.getNodes?.() ?? []
  nodes.forEach((node: any) => {
    const model = node.getModel?.()
    const id = String(model?.id || '')
    const x = Number(model?.x)
    const y = Number(model?.y)
    if (!id || !Number.isFinite(x) || !Number.isFinite(y)) return
    graphNodePositionCache.set(id, { x, y })
  })
}

async function openNodeDetailByNodeId(nodeId: string) {
  const node = pathNodes.value.find(item => item.id === nodeId)
  if (!node) return

  nodeDetailDrawerVisible.value = true
  if (isStartAnchorNode(node)) {
    nodeDetailLoading.value = false
    nodeDetail.value = null
    nodeDetailTitle.value = '职业起点说明'
    return
  }

  nodeDetailLoading.value = true
  nodeDetailTitle.value = node.jobName || '岗位详情'
  try {
    const response = await getJobDetail(node.jobId)
    const result = response.data as ApiResponse<JobDetailResult>
    if (!isSuccessCode(Number(result.code))) {
      ElMessage.error(result.msg || '获取岗位详情失败')
      nodeDetail.value = null
      return
    }
    nodeDetail.value = extractPayload<JobDetailResult>(response as { data: ApiResponse<JobDetailResult> }) ?? null
  } finally {
    nodeDetailLoading.value = false
  }
}

function renderMetricCharts() {
  if (!pathEvaluation.value) return false
  if (!metricRadarRef.value || !metricTrendRef.value) return false
  if (
    metricRadarRef.value.clientWidth <= 0
    || metricRadarRef.value.clientHeight <= 0
    || metricTrendRef.value.clientWidth <= 0
    || metricTrendRef.value.clientHeight <= 0
  ) {
    return false
  }

  if (metricRadarRef.value) {
    if (!metricRadarChart) metricRadarChart = echarts.init(metricRadarRef.value)
    metricRadarChart.setOption({
      animationDuration: 400,
      radar: {
        radius: '64%',
        splitNumber: 4,
        axisName: { color: '#475569', fontSize: 10 },
        splitLine: { lineStyle: { color: '#e2e8f0' } },
        splitArea: { areaStyle: { color: ['#f8fafc', '#ffffff'] } },
        indicator: [
          { name: '可行性', max: 100 },
          { name: '就绪度', max: 100 },
          { name: '推荐度', max: 100 },
        ],
      },
      series: [
        {
          type: 'radar',
          areaStyle: { color: 'rgba(59,130,246,0.15)' },
          lineStyle: { color: '#2563eb', width: 2 },
          symbol: 'circle',
          symbolSize: 5,
          data: [
            {
              value: [
                pathEvaluation.value.feasibilityScore,
                pathEvaluation.value.readinessScore,
                pathEvaluation.value.recommendationScore,
              ],
              name: '当前评估',
            },
          ],
        },
      ],
    })
  }

  if (metricTrendRef.value) {
    if (!metricTrendChart) metricTrendChart = echarts.init(metricTrendRef.value)
    const base = Math.max(35, pathEvaluation.value.recommendationScore - 24)
    const trendData = [base, base + 8, base + 14, pathEvaluation.value.recommendationScore]
    metricTrendChart.setOption({
      grid: { left: 18, right: 12, top: 10, bottom: 18 },
      xAxis: {
        type: 'category',
        data: ['起点', '阶段1', '阶段2', '当前'],
        axisLabel: { color: '#64748b', fontSize: 10 },
        axisLine: { lineStyle: { color: '#cbd5e1' } },
      },
      yAxis: {
        type: 'value',
        min: 0,
        max: 100,
        axisLabel: { color: '#94a3b8', fontSize: 10 },
        splitLine: { lineStyle: { color: '#eef2f7' } },
      },
      series: [
        {
          type: 'line',
          data: trendData,
          smooth: true,
          symbol: 'circle',
          symbolSize: 5,
          lineStyle: { color: '#0ea5e9', width: 2 },
          areaStyle: {
            color: {
              type: 'linear',
              x: 0,
              y: 0,
              x2: 0,
              y2: 1,
              colorStops: [
                { offset: 0, color: 'rgba(14,165,233,0.26)' },
                { offset: 1, color: 'rgba(14,165,233,0.04)' },
              ],
            },
          },
        },
      ],
      tooltip: {
        trigger: 'axis',
      },
    })
  }

  return true
}

function renderPathGraph() {
  if (!graphRef.value) return
  const width = graphRef.value.clientWidth
  const height = graphRef.value.clientHeight
  if (!width || !height) return

  if (!g6Graph) {
    ensurePathGraphNodeRegistered()
    g6Graph = new G6.Graph({
      container: graphRef.value,
      width,
      height,
      fitView: true,
      fitViewPadding: 24,
      animate: true,
      animateCfg: {
        duration: 380,
        easing: 'easeCubic',
      },
      modes: {
        default: ['drag-canvas', 'zoom-canvas'],
      },
      defaultNode: {
        type: PATH_GRAPH_NODE_TYPE,
      },
      defaultEdge: {
        type: 'line',
      },
    })

    g6Graph.on('node:mouseenter', (evt: any) => {
      if (pathNodeDragActiveId) return
      const model = evt.item?.getModel?.()
      if (!model) return
      g6Graph.get('canvas')?.set?.('cursor', 'pointer')
      graphHoverTip.value.visible = true
      updateGraphHoverTipPosition(evt.canvasX || 0, evt.canvasY || 0)
      const fullName = pathNodes.value.find(item => item.id === model.id)?.jobName || model.fullLabel || model.shortLabel
      graphHoverTip.value.title = String(fullName || '节点')
      graphHoverTip.value.lines = [`阶段：${pathNodes.value.find(item => item.id === model.id)?.stage == "target" ? "目标阶段" 
        : pathNodes.value.find(item => item.id === model.id)?.stage == "milestone" ? "里程" 
        : pathNodes.value.find(item => item.id === model.id)?.stage == "start" ? "开始" 
        : "-"}`]
    })

    g6Graph.on('node:mousemove', (evt: any) => {
      const graphPoint = resolveGraphPointFromEvent(evt)
      pathNodeLastPointerX = graphPoint.x
      pathNodeLastPointerY = graphPoint.y

      if (
        !pathNodeDragActiveId
        && pathNodePointerPressed
        && pathNodeLongPressCandidateId
        && shouldActivatePathNodeDragByMove(pathNodeLastPointerX, pathNodeLastPointerY)
      ) {
        activatePathNodeDrag(pathNodeLongPressCandidateId, pathNodeLastPointerX, pathNodeLastPointerY)
      }

      if (pathNodeDragActiveId) {
        updatePathDraggedNodePosition(pathNodeLastPointerX, pathNodeLastPointerY)
        return
      }

      if (!graphHoverTip.value.visible) return
      updateGraphHoverTipPosition(evt.canvasX || 0, evt.canvasY || 0)
    })

    g6Graph.on('node:mouseleave', () => {
      if (pathNodeDragActiveId) return
      g6Graph.get('canvas')?.set?.('cursor', 'default')
      hideGraphHoverTip()
    })

    g6Graph.on('node:mousedown', (evt: any) => {
      const button = Number(evt?.originalEvent?.button ?? 0)
      if (button !== 0) return
      const nodeId = String(evt.item?.getID?.() || '')
      const node = findPathNodeByGraphNodeId(nodeId)
      if (!node) {
        clearPathNodeLongPressTimer()
        return
      }

      const graphPoint = resolveGraphPointFromEvent(evt)
      startPathNodeLongPress(nodeId, graphPoint.x, graphPoint.y)
    })

    g6Graph.on('node:mouseup', () => {
      releasePathNodePointer()
    })

    g6Graph.on('node:click', (evt: any) => {
      if (pathNodeDragActiveId) return
      if (suppressNodeClickOnce) {
        suppressNodeClickOnce = false
        return
      }
      const nodeId = String(evt.item?.getID?.() || '')
      if (!nodeId) return
      openNodeDetailByNodeId(nodeId).catch(() => {})
    })

    g6Graph.on('edge:mouseenter', (evt: any) => {
      if (pathNodeDragActiveId) return
      const model = evt.item?.getModel?.()
      if (!model) return
      g6Graph.get('canvas')?.set?.('cursor', 'pointer')
      const similarity = Number(model.similarity || 0)
      const inferred = Boolean(model.inferred)
      graphHoverTip.value.visible = true
      updateGraphHoverTipPosition(evt.canvasX || 0, evt.canvasY || 0)
      graphHoverTip.value.title = '路径关系说明'
      graphHoverTip.value.lines = [
        `关系类型：${inferred ? '推演关系（图谱中无直接关系）' : model.relationType === 'promotion' ? '晋升关系' : model.relationType === 'start' ? '起点关系' : '换岗关系'}`,
        `相似度：${similarityLabel(similarity)}（${Math.round(similarity * 100)}）`,
        `说明：${String(model.reason || '暂无说明')}`,
      ]
    })

    g6Graph.on('edge:mousemove', (evt: any) => {
      if (pathNodeDragActiveId) return
      if (!graphHoverTip.value.visible) return
      updateGraphHoverTipPosition(evt.canvasX || 0, evt.canvasY || 0)
    })

    g6Graph.on('edge:mouseleave', () => {
      if (pathNodeDragActiveId) return
      g6Graph.get('canvas')?.set?.('cursor', 'default')
      hideGraphHoverTip()
    })

    g6Graph.on('canvas:mousemove', (evt: any) => {
      const graphPoint = resolveGraphPointFromEvent(evt)
      pathNodeLastPointerX = graphPoint.x
      pathNodeLastPointerY = graphPoint.y

      if (
        !pathNodeDragActiveId
        && pathNodePointerPressed
        && pathNodeLongPressCandidateId
        && shouldActivatePathNodeDragByMove(pathNodeLastPointerX, pathNodeLastPointerY)
      ) {
        activatePathNodeDrag(pathNodeLongPressCandidateId, pathNodeLastPointerX, pathNodeLastPointerY)
      }

      if (pathNodeDragActiveId) {
        updatePathDraggedNodePosition(pathNodeLastPointerX, pathNodeLastPointerY)
        return
      }
    })

    g6Graph.on('canvas:mouseup', () => {
      releasePathNodePointer()
    })

    g6Graph.on('canvas:drag', () => {
      if (pathNodeDragActiveId) return
      g6Graph.get('canvas')?.set?.('cursor', 'default')
      hideGraphHoverTip()
    })
    g6Graph.on('canvas:click', () => {
      if (pathNodeDragActiveId) return
      suppressNodeClickOnce = false
      g6Graph.get('canvas')?.set?.('cursor', 'default')
      hideGraphHoverTip()
    })
  } else {
    if (!forceGraphFitOnNextRender) {
      syncGraphNodePositionCache()
    }
    g6Graph.changeSize(width, height)
  }

  const data = buildGraphData()
  if (!hasGraphRendered) {
    g6Graph.data(data)
    g6Graph.render()
    g6Graph.fitView(24)
    forceGraphFitOnNextRender = false
    hasGraphRendered = true
  } else if (typeof g6Graph.changeData === 'function') {
    g6Graph.changeData(data)
    if (graphNodeCount !== data.nodes.length || forceGraphFitOnNextRender) {
      g6Graph.fitView(24)
      forceGraphFitOnNextRender = false
    }
  } else {
    g6Graph.data(data)
    g6Graph.render()
    if (forceGraphFitOnNextRender) {
      g6Graph.fitView(24)
      forceGraphFitOnNextRender = false
    }
  }

  graphNodeCount = data.nodes.length
  syncGraphNodePositionCache()
}

function handleResize() {
  isDesktop.value = window.innerWidth >= 1024
  renderPathGraph()
  metricRadarChart?.resize()
  metricTrendChart?.resize()
  matchAbilityRadarChart?.resize()
}

async function initialize() {
  initLoading.value = true
  try {
    await appStore.ensureProfileSnapshot(true)
    if (appStore.hasProfile === false) {
      recommendations.value = null
      currentMatchRecord.value = null
      historyList.value = []
      currentPathDraft.value = null
      return
    }

    await Promise.all([
      fetchFavorites(),
      fetchMatchHistory(),
      fetchLatestPath(),
    ])
  } catch {
    ElMessage.error('页面初始化失败，请刷新重试')
  } finally {
    initLoading.value = false
    await nextTick()
    renderPathGraph()
  }
}

onMounted(() => {
  pathNodes.value = ensureStartAnchor([])
  pathEdges.value = buildFallbackEdges(pathNodes.value)
  initialize().catch(() => {})
  window.addEventListener('resize', handleResize)
  window.addEventListener('mouseup', handleGlobalPointerUp)
  window.addEventListener(MATCH_RECOMMENDATION_REFRESH_EVENT, onRecommendationRefreshEvent as EventListener)
  window.addEventListener(TASK_ORCHESTRATOR_ROUTE_REFRESH_EVENT, onTaskOrchestratorRouteRefreshEvent as EventListener)
})

watch(
  () => [pathNodes.value, pathEdges.value],
  () => {
    nextTick(() => {
      renderPathGraph()
    })
  },
  { deep: true },
)

watch(
  () => pathEvaluation.value,
  () => {
    showEvaluationToast()
    if (!stagePlanDetailVisible.value) {
      scheduleMetricChartsRender(80)
    }
  },
  { deep: true },
)

watch(
  () => pathEvaluation.value?.level,
  () => {
    if (!canShowStagePlanDetail.value) {
      stagePlanDetailVisible.value = false
    }
  }
)

watch(
  () => stagePlanDetailVisible.value,
  (visible) => {
    if (visible) {
      disposeMetricCharts()
      return
    }
    scheduleMetricChartsRender(320)
  }
)

watch(
  () => sortedHistoryList.value.length,
  () => {
    if (historyPage.value > historyPageCount.value) {
      historyPage.value = historyPageCount.value
    }
  }
)

watch(
  () => favorites.value.length,
  () => {
    if (favoritePage.value > favoritePageCount.value) {
      favoritePage.value = favoritePageCount.value
    }
  }
)

watch(
  () => currentMatchRecord.value?.recordId,
  () => {
    matchDetailPage.value = 'score'
    disposeMatchAbilityRadarChart()
  }
)

watch(
  () => matchDetailPage.value,
  (page) => {
    if (page === 'gap') {
      scheduleMatchAbilityRadarRender(30, true)
      return
    }
    disposeMatchAbilityRadarChart()
  }
)

watch(
  () => matchAbilityRadarSeries.value,
  () => {
    if (matchDetailPage.value === 'gap') {
      scheduleMatchAbilityRadarRender(30, true)
    }
  },
  { deep: true }
)

onBeforeUnmount(() => {
  if (evaluateDebounceTimer) {
    clearTimeout(evaluateDebounceTimer)
    evaluateDebounceTimer = null
  }
  clearPathDragPreviewEdges()
  clearPathNodeLongPressTimer()
  pathNodeDragActiveId = ''
  resetPathNodePressState()
  suppressNodeClickOnce = false
  window.removeEventListener('resize', handleResize)
  window.removeEventListener('mouseup', handleGlobalPointerUp)
  if (g6Graph) {
    g6Graph.destroy()
    g6Graph = null
  }
  hasGraphRendered = false
  graphNodeCount = 0
  forceGraphFitOnNextRender = false
  graphNodePositionCache.clear()
  disposeMetricCharts()
  disposeMatchAbilityRadarChart()
  if (evaluationToastTimer) {
    clearTimeout(evaluationToastTimer)
    evaluationToastTimer = null
  }
  if (metricRenderTimer) {
    clearTimeout(metricRenderTimer)
    metricRenderTimer = null
  }
  window.removeEventListener(MATCH_RECOMMENDATION_REFRESH_EVENT, onRecommendationRefreshEvent as EventListener)
  window.removeEventListener(TASK_ORCHESTRATOR_ROUTE_REFRESH_EVENT, onTaskOrchestratorRouteRefreshEvent as EventListener)
})
</script>

<template>
  <section class="space-y-4 md:space-y-5" v-loading="initLoading">
    <div class="flex flex-col gap-2 md:flex-row md:items-end md:justify-between">
      <div>
        <h2 class="text-lg font-semibold md:text-2xl">人岗匹配 · 职业规划</h2>
        <p class="mt-1 text-xs text-slate-500 md:text-sm">人岗智能匹配，动手完成职业路径规划，AI实时评估</p>
      </div>
      <div class="flex items-center gap-2">
        <el-button :loading="recommendLoading" :disabled="!hasProfile" @click="fetchRecommendations">刷新推荐</el-button>
      </div>
    </div>

    <el-alert
      v-if="!hasProfile"
      type="warning"
      :closable="false"
      show-icon
      title="尚未完成能力评估，暂无法生成精准岗位匹配"
      class="mb-1"
    >
      <template #default>
        <div class="mt-2 flex items-center gap-2">
          <span class="text-xs text-slate-600">请先完成学生就业能力分析后再进行职业规划。</span>
          <el-button size="small" type="primary" @click="router.push('/student')">前往能力评估</el-button>
        </div>
      </template>
    </el-alert>

    <el-alert
      v-else-if="isRecommendationProcessing"
      type="info"
      :closable="false"
      show-icon
      title="人岗匹配分析中，请稍候"
      class="mb-1"
    >
      <template #default>
        <div class="mt-2 text-xs text-slate-600">{{ recommendationProcessingReason }}</div>
      </template>
    </el-alert>

    <div class="grid grid-cols-1 gap-4 xl:grid-cols-12">
      <div class="space-y-4 xl:col-span-8">
        <el-card shadow="never">
          <template #header>
            <div class="flex flex-wrap items-center justify-between gap-2">
              <span class="font-medium">人岗匹配·AI分析</span>
              <div class="flex items-center gap-2">
                <el-button
                  size="small"
                  type="primary"
                  :disabled="!bestMatch || isRecommendationProcessing"
                  :loading="matchLoading"
                  @click="bestMatch && runMatchAnalyze(bestMatch.jobId, 'auto')"
                >
                  一键匹配
                </el-button>
              </div>
            </div>
          </template>

          <div v-loading="matchLoading || favoriteLoading" class="space-y-4">
            <!-- 收藏岗位详情（点击收藏岗位时直接展示，不再调用 match/analyze） -->
            <template v-if="jobDetail">
              <div class="rounded-xl border border-slate-200 bg-slate-50 p-4">
                <div class="flex flex-col gap-3 md:flex-row md:items-center md:justify-between">
                  <div>
                    <div class="flex flex-wrap items-center gap-2">
                      <div class="text-base font-semibold text-slate-900">{{ jobDetail.jobName }}</div>
                      <el-tag v-if="jobDetail.edu" size="small" type="info" effect="plain">{{ jobDetail.edu }}</el-tag>
                      <el-tag v-if="jobDetail.exp" size="small" type="info" effect="plain">{{ jobDetail.exp }}</el-tag>
                    </div>
                    <div class="mt-1 text-xs text-slate-500">
                      {{ jobDetail.companyName }} · {{ jobDetail.city }} · {{ displaySalaryText(jobDetail) }}
                    </div>
                    <div class="mt-2 flex flex-wrap gap-2">
                      <el-tag v-if="jobDetail.tier" size="small" type="success" effect="plain">{{ jobDetail.tier }}</el-tag>
                      <el-tag v-for="tag in jobDetail.cats || []" :key="tag" size="small" effect="plain">{{ tag }}</el-tag>
                    </div>
                  </div>
                  <el-button size="small" type="primary" plain :icon="ChatDotRound" @click="handleAddJobToAssistant">
                    添加到对话
                  </el-button>
                </div>
              </div>

              <div class="grid grid-cols-1 gap-3 lg:grid-cols-2">
                <div class="rounded-lg border border-slate-200 p-3">
                  <div class="mb-2 text-sm font-medium text-slate-700">岗位描述</div>
                  <p class="whitespace-pre-wrap text-sm leading-6 text-slate-600">{{ jobDetail.jobDescription }}</p>
                </div>
                <div class="rounded-lg border border-slate-200 p-3">
                  <div class="mb-2 text-sm font-medium text-slate-700">能力要求</div>
                  <div v-if="jobDetail.abilityRequirements" class="space-y-1.5">
                    <div
                      v-for="(score, key) in jobDetail.abilityRequirements"
                      :key="key"
                      class="flex items-center gap-2 text-xs"
                    >
                      <span class="w-16 shrink-0 text-slate-600">{{ abilityLabelMap[key] || key }}</span>
                      <el-progress class="flex-1" :percentage="Number(score) || 0" :show-text="false" :stroke-width="6" />
                      <span class="w-8 shrink-0 text-right font-medium text-slate-700">{{ score }}</span>
                    </div>
                  </div>
                  <div v-if="jobDetail.keySkills?.hardSkills?.length" class="mt-3">
                    <div class="mb-1 text-xs font-medium text-slate-500">核心技能</div>
                    <div class="flex flex-wrap gap-1.5">
                      <el-tag v-for="skill in jobDetail.keySkills.hardSkills" :key="skill" size="small" effect="plain">{{ skill }}</el-tag>
                    </div>
                  </div>
                </div>
              </div>
            </template>

            <template v-else-if="currentMatchRecord">
              <div class="rounded-xl border border-slate-200 bg-slate-50 p-4">
                <div class="flex flex-col gap-3 md:flex-row md:items-center md:justify-between">
                  <div>
                    <div class="flex flex-wrap items-center gap-2">
                      <div class="text-base font-semibold text-slate-900">{{ currentMatchRecord.job.jobName }}</div>
                      <el-tag size="small" type="success" effect="plain">
                        专业技能匹配率：{{ formatPercentScore(currentMatchRecord.match.professionalSkillMatchRate) }}
                      </el-tag>
                    </div>
                    <div class="mt-1 text-xs text-slate-500">
                      {{ currentMatchRecord.job.companyName }} · {{ currentMatchRecord.job.city }} · {{ displaySalaryText(currentMatchRecord.job) }}
                    </div>
                    <div class="mt-2 flex flex-wrap gap-2">
                      <el-tag
                        v-for="tag in currentMatchRecord.match.matchTags"
                        :key="tag"
                        size="small"
                        :type="getTagType(tag)"
                      >
                        {{ formatJobTag(tag) }}
                      </el-tag>
                    </div>
                  </div>
                  <div class="text-right">
                    <div class="text-xs text-slate-500">综合匹配度</div>
                    <div
                      class="text-3xl font-bold"
                      :style="{ color: scoreColor(currentMatchRecord.match.overallScore) }"
                    >
                      {{ currentMatchRecord.match.overallScore }}
                    </div>
                    <div class="text-xs text-slate-500">更新时间：{{ formatTime(currentMatchRecord.updatedAt) }}</div>
                      <div class="mt-2 flex items-center justify-end gap-2">
                        <el-button
                          size="small"
                          :loading="favoriteActionLoading"
                          :icon="isCurrentMatchFavorited ? StarFilled : Star"
                          :type="isCurrentMatchFavorited ? 'warning' : 'default'"
                          @click="handleToggleCurrentJobFavorite"
                        >
                          {{ isCurrentMatchFavorited ? '已收藏' : '收藏岗位' }}
                        </el-button>
                        <el-button
                          size="small"
                          type="primary"
                          :icon="Position"
                          @click="handleStartCareerPathPlanning"
                        >
                          开始职业路径规划
                        </el-button>
                      </div>
                  </div>
                </div>
              </div>

              <div class="grid grid-cols-1 gap-3 lg:grid-cols-2">
                <div class="rounded-lg border border-slate-200 p-3">
                  <div class="mb-2 flex items-center justify-between gap-2">
                    <div class="text-sm font-medium text-slate-700">评估详情</div>
                    <el-button
                      size="small"
                      @click="toggleMatchDetailPage"
                    >
                      {{ matchDetailPage === 'score' ? '查看详细差距' : '返回评分概览' }}
                    </el-button>
                  </div>

                  <div v-if="matchDetailPage === 'score'" class="space-y-3">
                    <div v-for="item in dimensionExplainList" :key="item.key">
                      <div class="mb-1 flex items-center justify-between text-xs">
                        <span class="text-slate-600">{{ item.label }}</span>
                        <span class="font-semibold text-slate-800">{{ item.score }} / 期望 {{ item.expectedScore }}</span>
                      </div>
                      <el-progress :show-text="false" :percentage="normalizeProgressScore(item.score)" :stroke-width="8" :color="progressGradientColor(item.score)" />
                      <div class="mt-1 text-[11px] leading-5 text-slate-500">{{ item.reason }}</div>
                    </div>
                  </div>

                  <div v-else class="space-y-2">
                    <div class="text-xs text-slate-500">学生就业能力与目标岗位要求对比</div>
                    <div v-if="matchAbilityRadarSeries" ref="matchAbilityRadarRef" class="h-[360px] w-full" />
                    <el-empty v-else description="暂无12维评分数据" :image-size="54" />
                  </div>
                </div>

                <div class="rounded-lg border border-slate-200 p-3">
                  <div class="mb-2 text-sm font-medium text-slate-700">优先改进建议</div>
                  <div class="space-y-2">
                    <div
                      v-for="item in displaySuggestions"
                      :key="`${item.dimension}-${item.advice}`"
                      class="rounded-md border p-2"
                      :style="suggestionCardStyle(item.priority)"
                    >
                      <div class="mb-1 flex items-center justify-between">
                        <span class="text-xs font-medium text-slate-700">{{ item.displayDimension }}</span>
                        <el-tag size="small" :type="suggestionType(item.priority)">{{ item.displayPriority }}</el-tag>
                      </div>
                      <div class="text-xs leading-5 text-slate-600">{{ item.advice }}</div>
                    </div>
                  </div>
                </div>
              </div>
            </template>

            <el-empty
              v-else
              class="match-detail-empty"
              :image="matchEmptyImageUrl"
              description="尚未生成匹配报告，可使用AI一键匹配或从收藏岗位手动匹配"
            />
          </div>
        </el-card>

      </div>

      <div class="space-y-4 xl:col-span-4">
        <el-card shadow="never">
          <template #header>
            <div class="flex flex-wrap items-center justify-between gap-2">
              <div class="flex items-center gap-1">
                <el-button size="small" :type="activeRightPanel === 'recommend' ? 'primary' : 'default'" @click="switchRightPanel('recommend')">推荐岗位</el-button>
                <el-button size="small" :type="activeRightPanel === 'favorite' ? 'primary' : 'default'" @click="switchRightPanel('favorite')">我的收藏</el-button>
                <el-button size="small" :type="activeRightPanel === 'history' ? 'primary' : 'default'" @click="switchRightPanel('history')">历史匹配</el-button>
              </div>
            </div>
          </template>
          <div v-if="activeRightPanel === 'recommend'" v-loading="recommendLoading" class="space-y-3">
            <el-empty
              v-if="isRecommendationProcessing"
              description="人岗匹配分析中，请稍候，结果将自动刷新"
              :image-size="58"
            />
            <template v-if="bestMatch">
              <div class="cursor-pointer rounded-lg border border-emerald-200 bg-emerald-50 p-3 transition hover:brightness-[0.99]" @click="loadJobDetail(bestMatch.jobId)">
                <div class="text-sm font-semibold text-emerald-700">{{ bestMatch.jobName }}</div>
                <div class="mt-1 text-xs text-slate-600">{{ bestMatch.companyName }} · {{ bestMatch.city }}</div>
                <div class="mt-1 text-xs text-slate-500">{{ displaySalaryText(bestMatch) }} · {{ publishDateText(bestMatch) }}</div>
                <div class="mt-2 flex items-center justify-between">
                  <span class="text-xs text-slate-500">匹配度</span>
                  <span class="text-base font-bold text-emerald-700">{{ bestMatch.overallScore }}</span>
                </div>
                <el-button class="mt-2 w-full" type="success" size="small" :disabled="isRecommendationProcessing" @click.stop="runMatchAnalyze(bestMatch.jobId, 'auto')">使用该推荐</el-button>
              </div>
            </template>

            <div class="space-y-2">
              <div class="text-xs font-medium text-slate-500">其他推荐</div>
              <button
                v-for="item in recommendations?.otherRecommendations || []"
                :key="item.jobId"
                class="w-full rounded-md border px-3 py-2 text-left transition hover:brightness-[0.99]"
                :style="scoreSoftCardStyle(item.overallScore)"
                @click="loadJobDetail(item.jobId)"
              >
                <div class="flex items-center justify-between">
                  <div class="flex items-center gap-2">
                    <span class="text-sm text-slate-700">{{ item.jobName }}</span>
                    <el-tag size="small" type="info">{{ publishDateText(item) }}</el-tag>
                  </div>
                  <span class="text-xs font-semibold" :style="{ color: scoreColor(item.overallScore) }">{{ item.overallScore }}</span>
                </div>
                <div class="mt-1 text-xs text-slate-500">{{ item.city }} · {{ displaySalaryText(item) }}</div>
              </button>
            </div>
            <div v-if="hasAnyRecommendation" class="rounded-md border border-blue-100 bg-blue-50/70 p-3">
              <div class="text-xs text-slate-600">推荐结果不符合预期？可以让AI帮你细化匹配范围后重新分析。</div>
              <el-button class="mt-2 w-full" size="small" type="primary" plain @click="handleOpenGlobalAssistantForRefine">
                匹配结果不满意？
              </el-button>
            </div>
            <el-empty v-if="!bestMatch && !(recommendations?.otherRecommendations || []).length" description="暂无推荐岗位" :image-size="58" />
          </div>

          <div v-else-if="activeRightPanel === 'favorite'" v-loading="favoriteLoading" class="space-y-2">
            <button
              v-for="item in pagedFavoriteList"
              :key="item.jobId"
              class="w-full rounded-md border border-slate-200 px-3 py-2 text-left hover:bg-slate-50"
              @click="loadJobDetail(item.jobId)"
            >
              <div class="text-sm font-medium text-slate-800">{{ item.jobName }}</div>
              <div class="mt-1 text-xs text-slate-500">{{ item.city }} · {{ displaySalaryText(item) }}</div>
            </button>
            <div v-if="favoritePageCount > 1" class="flex items-center justify-end gap-2 pt-1 text-xs text-slate-500">
              <el-button text size="small" :disabled="favoritePage <= 1" @click="changeFavoritePage(-1)">上一页</el-button>
              <span>{{ favoritePage }} / {{ favoritePageCount }}</span>
              <el-button text size="small" :disabled="favoritePage >= favoritePageCount" @click="changeFavoritePage(1)">下一页</el-button>
            </div>
            <el-empty v-if="!favorites.length" description="暂无收藏岗位" :image-size="58" />
          </div>

          <div v-else v-loading="historyLoading" class="space-y-2">
            <button
              v-for="item in pagedHistoryList"
              :key="item.recordId"
              class="w-full rounded-md border px-3 py-2 text-left transition"
              :class="item.recordId === currentMatchRecord?.recordId ? 'border-blue-300 bg-blue-50/70' : 'border-slate-200 hover:bg-slate-50/70'"
              :style="scoreSoftCardStyle(item.overallScore)"
              @click="loadMatchDetail(item.recordId)"
            >
              <div class="flex items-center justify-between gap-2">
                <div class="flex items-center gap-2">
                  <span class="text-sm text-slate-700">{{ item.jobName }}</span>
                  <el-tag v-if="item.pinned" type="success" size="small">置顶</el-tag>
                </div>
                <span class="text-xs font-semibold" :style="{ color: scoreColor(item.overallScore) }">{{ item.overallScore }}</span>
              </div>
              <div class="mt-1 text-xs text-slate-500">{{ item.city || '-' }} · {{ displaySalaryText(item) }}</div>
              <div class="mt-1 text-[11px] text-slate-500">发布日期：{{ publishDateText(item) }}</div>
              <div class="mt-1 text-[11px] text-slate-500">匹配时间：{{ formatTime(item.updatedAt) }}</div>
              <div class="mt-2 flex items-center gap-2">
                <el-button text size="small" @click.stop="handlePinRecord(item.recordId)">{{ item.pinned ? '取消置顶' : '置顶' }}</el-button>
                <el-button text size="small" type="danger" :icon="Delete" @click.stop="handleDeleteRecord(item.recordId)">删除</el-button>
              </div>
            </button>
            <div v-if="historyPageCount > 1" class="flex items-center justify-end gap-2 pt-1 text-xs text-slate-500">
              <el-button text size="small" :disabled="historyPage <= 1" @click="changeHistoryPage(-1)">上一页</el-button>
              <span>{{ historyPage }} / {{ historyPageCount }}</span>
              <el-button text size="small" :disabled="historyPage >= historyPageCount" @click="changeHistoryPage(1)">下一页</el-button>
            </div>
            <el-empty v-if="!sortedHistoryList.length" description="暂无匹配历史" :image-size="58" />
          </div>
        </el-card>
      </div>
    </div>

    <el-card ref="sandboxCardRef" shadow="never">
      <template #header>
        <div class="flex flex-wrap items-center justify-between gap-2">
          <div>

          <el-dialog v-model="refineIntentDialogVisible" title="细化匹配意愿" width="520px" destroy-on-close append-to-body :lock-scroll="false">
            <el-form label-position="top">
              <el-form-item label="期望岗位（Tag添加，可多选）">
                <el-select
                  v-model="refineIntentForm.preferredJobs"
                  multiple
                  filterable
                  allow-create
                  default-first-option
                  clearable
                  class="w-full"
                  placeholder="输入并回车添加，例如：产品经理"
                >
                  <el-option
                    v-for="item in refinePreferredJobOptions"
                    :key="item"
                    :label="item"
                    :value="item"
                  />
                </el-select>
              </el-form-item>
              <el-form-item label="意向城市（Tag添加，可多选）">
                <el-select
                  v-model="refineIntentForm.cities"
                  multiple
                  filterable
                  allow-create
                  default-first-option
                  clearable
                  class="w-full"
                  placeholder="输入并回车添加，例如：西安"
                >
                  <el-option
                    v-for="item in refineCityOptions"
                    :key="item"
                    :label="item"
                    :value="item"
                  />
                </el-select>
              </el-form-item>
              <el-form-item label="可接受薪资范围">
                <el-input v-model="refineIntentForm.salaryRange" placeholder="例如：10-18k" />
              </el-form-item>
              <el-form-item label="福利偏好（Tag添加，可多选）">
                <el-select
                  v-model="refineIntentForm.benefits"
                  multiple
                  filterable
                  allow-create
                  default-first-option
                  clearable
                  class="w-full"
                  placeholder="输入并回车添加，例如：双休"
                >
                  <el-option
                    v-for="item in refineBenefitSuggestions"
                    :key="item"
                    :label="item"
                    :value="item"
                  />
                </el-select>
              </el-form-item>
              <el-form-item label="整体意愿描述（必填）">
                <el-input
                  v-model="refineIntentForm.description"
                  type="textarea"
                  :rows="4"
                  placeholder="请描述你对岗位方向、成长路径、工作节奏等偏好"
                />
              </el-form-item>
            </el-form>
            <template #footer>
              <div class="flex justify-end gap-2">
                <el-button @click="refineIntentDialogVisible = false">取消</el-button>
                <el-button type="primary" :loading="refineIntentSubmitting" @click="handleSubmitRefineIntent">提交并咨询AI</el-button>
              </div>
            </template>
          </el-dialog>
            <div class="font-medium">职业路径沙盘规划
              <el-tag v-if="currentPathDraft" type="warning" size="small">草稿</el-tag>
              <el-tag v-if="currentPathDisplayName" type="info" size="small">{{ currentPathDisplayName }}</el-tag>
            </div>
            <div class="text-xs text-slate-500">支持AI自动规划与手动增删节点，AI实时评估，保存后查看路径生涯规划</div>
          </div>
          <div class="flex items-center gap-2">
            <el-button size="small" :disabled="!canUndo" @click="handleUndoPathEdit">撤销</el-button>
            <el-button size="small" :disabled="!canRedo" @click="handleRedoPathEdit">恢复</el-button>
            <el-button :loading="savedPathListLoading" @click="openSavedPathDialog">查看保存列表</el-button>
            <el-button :loading="pathLoading" type="danger" plain @click="handleClearDraftPath">清空草稿</el-button>
            <el-button type="primary" :loading="pathSaving" :disabled="!canSavePath" @click="handleSavePath">保存路径</el-button>
          </div>
        </div>
      </template>

      <div class="grid grid-cols-1 gap-4 lg:grid-cols-12">
        <div class="space-y-3 lg:col-span-4">
          <div class="rounded-lg border border-slate-200 p-3">
            <div class="mb-2 text-sm font-medium text-slate-700">AI规划</div>
            <el-select v-model="selectedTargetJobId" placeholder="选择目标岗位" class="w-full">
              <el-option v-for="item in recommendationOptions" :key="item.jobId" :label="item.label" :value="item.jobId" />
            </el-select>
            <el-radio-group v-model="autoPlanMode" class="mt-2" size="small">
              <el-radio-button label="balanced">平衡</el-radio-button>
              <el-radio-button label="conservative">稳健</el-radio-button>
              <el-radio-button label="aggressive">进阶</el-radio-button>
            </el-radio-group>
            <el-button class="mt-3 w-full" type="primary" :loading="pathLoading" @click="handleAutoPlan">一键生成路径</el-button>
          </div>

          <div class="rounded-lg border border-slate-200 p-3">
            <div class="mb-2 text-sm font-medium text-slate-700">手动添加节点</div>
            <el-select placeholder="添加岗位到路径" class="w-full" @change="addNodeByJobId">
              <el-option v-for="item in manualDefaultOptions" :key="item.value" :label="item.label" :value="item.value" />
            </el-select>
            <div class="mt-2 text-[11px] text-slate-500">默认展示推荐岗位与收藏岗位，更多岗位可搜索并分页添加</div>
            <div class="mt-3 rounded-md border border-slate-200 bg-slate-50 p-2">
              <div class="mb-2 flex items-center gap-2">
                <el-input
                  v-model="manualExploreKeyword"
                  size="small"
                  placeholder="输入岗位关键词搜索更多岗位"
                  @keyup.enter="handleSearchManualJobs"
                />
                <el-button size="small" :loading="manualExploreLoading" @click="handleSearchManualJobs">搜索</el-button>
              </div>

              <div v-loading="manualExploreLoading" class="space-y-1">
                <template v-if="jobPool.length">
                  <div
                    v-for="item in jobPool"
                    :key="item.jobId"
                    class="flex items-center justify-between rounded border border-slate-200 bg-white px-2 py-1.5"
                  >
                    <div class="min-w-0 pr-2">
                      <div class="truncate text-xs font-medium text-slate-700">{{ item.jobName }}</div>
                      <div class="truncate text-[11px] text-slate-500">{{ item.city }} · {{ displaySalaryText(item) }}</div>
                    </div>
                    <el-button size="small" text type="primary" @click="addNodeByJobId(item.jobId)">添加</el-button>
                  </div>
                </template>
                <el-empty v-else description="输入关键词后搜索岗位" :image-size="44" />
              </div>

              <div v-if="manualExploreTotal > manualExplorePageSize" class="mt-2 flex items-center justify-end gap-2 text-[11px] text-slate-500">
                <el-button text size="small" :disabled="manualExplorePage <= 1 || manualExploreLoading" @click="changeManualExplorePage(-1)">上一页</el-button>
                <span>{{ manualExplorePage }} / {{ manualExplorePageCount }}</span>
                <el-button text size="small" :disabled="manualExplorePage >= manualExplorePageCount || manualExploreLoading" @click="changeManualExplorePage(1)">下一页</el-button>
              </div>
            </div>
            <div class="mt-2 text-[11px] text-slate-500">支持画布直接拖拽非起点节点重排，也可点击上下箭头调整顺序</div>
            <div class="mt-3 max-h-[320px] space-y-2 overflow-auto pr-1">
              <div
                v-for="(item, index) in pathNodes"
                :key="item.id"
                class="flex items-center justify-between rounded-md border px-2 py-2 transition"
                :class="draggingIndex === index ? 'border-blue-400 bg-blue-50' : 'border-slate-200 bg-slate-50'"
                :draggable="!isStartAnchorNode(item)"
                @dragstart="handleDragStart(index)"
                @dragover.prevent
                @drop.prevent="handleDrop(index)"
                @dragend="handleDragEnd"
              >
                <div class="min-w-0">
                  <div class="text-sm font-medium text-slate-700">{{ item.jobName || resolveJobName(item.jobId) }}</div>
                  <div class="text-xs text-slate-500">{{ isStartAnchorNode(item) ? '固定起点' : item.stage }}</div>
                </div>
                <div class="ml-2 flex items-center gap-1">
                  <el-button
                    text
                    size="small"
                    :icon="Rank"
                    :disabled="!isDesktop || isStartAnchorNode(item)"
                    title="拖拽排序"
                  />
                  <el-button
                    text
                    size="small"
                    :icon="ArrowUp"
                    :disabled="index === 0 || isStartAnchorNode(item)"
                    @click="movePathNode(index, index - 1)"
                  />
                  <el-button
                    text
                    size="small"
                    :icon="ArrowDown"
                    :disabled="index === pathNodes.length - 1 || isStartAnchorNode(item)"
                    @click="movePathNode(index, index + 1)"
                  />
                  <el-button text type="danger" size="small" :disabled="isStartAnchorNode(item)" @click="removePathNode(index)">删除</el-button>
                </div>
              </div>
            </div>
          </div>
        </div>

        <div class="space-y-3 lg:col-span-8">
          <div class="relative h-[300px] rounded-xl border border-slate-200 bg-slate-50 md:h-[360px]">
            <div class="pointer-events-none absolute inset-0 rounded-xl opacity-[0.32]" style="background-image: radial-gradient(circle at 1px 1px, rgba(148,163,184,0.26) 1px, transparent 0); background-size: 16px 16px" />
            <div class="absolute left-3 top-3 z-10">
            </div>
            <transition name="eval-toast">
              <div
                v-if="evaluationToastVisible"
                class="absolute left-1/2 top-3 z-30 min-w-[320px] max-w-[92%] -translate-x-1/2 rounded-lg border px-3 py-2 text-xs shadow-md"
                :style="{
                  background: evaluationToastPalette(pathEvaluation?.feasibilityScore || 0).bg,
                  borderColor: evaluationToastPalette(pathEvaluation?.feasibilityScore || 0).border,
                  color: evaluationToastPalette(pathEvaluation?.feasibilityScore || 0).text,
                }"
              >
                <div class="flex items-start justify-between gap-2">
                  <div class="font-semibold leading-5">{{ evaluationToastText }}</div>
                  <button class="rounded px-1 text-sm leading-none opacity-70 transition hover:opacity-100" @click="closeEvaluationToast">×</button>
                </div>
                <div v-if="evaluationToastExtraMetrics.length" class="mt-2 grid grid-cols-1 gap-1 sm:grid-cols-2">
                  <div
                    v-for="item in evaluationToastExtraMetrics"
                    :key="item.key"
                    class="rounded border border-current/20 bg-white/55 px-2 py-1"
                  >
                    <div class="text-[10px] leading-4 opacity-80">{{ item.label }}</div>
                    <div class="text-[12px] font-semibold leading-4">{{ item.value }}</div>
                    <div class="mt-0.5 text-[10px] leading-4 opacity-80">{{ getEvaluationMetricDescription(String(item.key || '')) }}</div>
                  </div>
                </div>
              </div>
            </transition>
            <div
              v-if="graphHoverTip.visible"
              class="pointer-events-none absolute z-20 w-[270px] rounded-lg border border-slate-200 bg-white/95 px-3 py-2 shadow-lg backdrop-blur"
              :style="{ left: `${graphHoverTip.x}px`, top: `${graphHoverTip.y}px` }"
            >
              <div class="text-xs font-semibold text-slate-800">{{ graphHoverTip.title }}</div>
              <div v-for="line in graphHoverTip.lines" :key="line" class="mt-1 text-[11px] leading-5 text-slate-600">{{ line }}</div>
            </div>
            <div ref="graphRef" class="h-full w-full" />
            <div v-if="evaluateLoading" class="absolute right-3 top-3 z-10">
              <el-tag type="info">实时评估中...</el-tag>
            </div>
            <div v-if="pathLoading" class="absolute right-3 top-12 z-10">
              <el-tag>路径计算中...</el-tag>
            </div>
          </div>

          <div v-if="pathEdges.length" class="rounded-lg border border-slate-200 bg-white p-3 md:hidden">
            <div class="mb-2 text-xs font-medium text-slate-500">当前路径关系</div>
            <div class="space-y-1 text-xs text-slate-600">
              <div v-for="edge in pathEdges" :key="`${edge.source}-${edge.target}`">
                {{ resolvePathNodeLabelByEdgeNodeId(edge.source) }} → {{ resolvePathNodeLabelByEdgeNodeId(edge.target) }}（{{ isInferredPathEdge(edge) ? '推演' : edge.relationType === 'promotion' ? '晋升' : edge.relationType === 'start' ? '起点' : '换岗' }}）
              </div>
            </div>
          </div>

          <div class="grid grid-cols-1 gap-3 md:grid-cols-3" v-if="pathEvaluation">
            <el-card shadow="never" class="!border-slate-200">
                <div class="text-xs text-slate-500 cursor-help">可行性</div>
              <div class="mt-1 text-2xl font-bold" :style="{ color: scoreColor(pathEvaluation.feasibilityScore) }">{{ pathEvaluation.feasibilityScore }}</div>
              <div class="mt-1 text-[11px] text-slate-400">{{ getEvaluationMetricDescription('feasibilityScore') }}</div>
            </el-card>
            <el-card shadow="never" class="!border-slate-200">
                <div class="text-xs text-slate-500 cursor-help">就绪度</div>
              <div class="mt-1 text-2xl font-bold" :style="{ color: scoreColor(pathEvaluation.readinessScore) }">{{ pathEvaluation.readinessScore }}</div>
              <div class="mt-1 text-[11px] text-slate-400">{{ getEvaluationMetricDescription('readinessScore') }}</div>
            </el-card>
            <el-card shadow="never" class="!border-slate-200">
                <div class="text-xs text-slate-500 cursor-help">推荐度</div>
              <div class="mt-1 text-2xl font-bold" :style="{ color: scoreColor(pathEvaluation.recommendationScore) }">{{ pathEvaluation.recommendationScore }}</div>
              <div class="mt-1 text-[11px] text-slate-400">{{ getEvaluationMetricDescription('recommendationScore') }}</div>
            </el-card>
          </div>

          <div v-if="pathEvaluation" class="space-y-2">
            <div class="flex items-center justify-between text-xs text-slate-500">
              <span>{{ stagePlanDetailVisible ? 'AI建议详情' : '评估图表' }}</span>
              <el-button
                v-if="canShowStagePlanDetail"
                size="small"
                type="primary"
                plain
                @click="stagePlanDetailVisible = !stagePlanDetailVisible"
              >
                {{ stagePlanDetailVisible ? '返回图表' : '详情' }}
              </el-button>
            </div>

            <transition name="eval-panel-slide" mode="out-in">
              <div v-if="!stagePlanDetailVisible" key="charts" class="grid grid-cols-1 gap-3 md:grid-cols-2">
                <div class="rounded-lg border border-slate-200 bg-white p-3">
                  <div class="mb-2 text-xs font-medium text-slate-500">评估雷达</div>
                  <div ref="metricRadarRef" class="h-[180px] w-full" />
                </div>
                <div class="rounded-lg border border-slate-200 bg-white p-3">
                  <div class="mb-2 text-xs font-medium text-slate-500">路径推荐度</div>
                  <div ref="metricTrendRef" class="h-[180px] w-full" />
                </div>
              </div>

              <div v-else key="stage-plan" class="rounded-lg border border-slate-200 bg-white p-3">
                <div class="space-y-3">
                  <div
                    v-for="(plan, idx) in pathEvaluation.stagePlans || []"
                    :key="`${plan.stage}-${idx}`"
                    class="rounded-lg border p-3"
                    :style="stagePlanCardStyle(plan.stage, plan.stageLabel)"
                  >
                    <div class="flex items-center justify-between">
                      <div class="text-sm font-semibold text-slate-800">
                        {{ plan.stageLabel || (plan.stage === 'shortTerm' ? '短期阶段' : plan.stage === 'midTerm' ? '中期阶段' : plan.stage === 'longTerm' ? '长期阶段' : plan.stage) }}
                      </div>
                      <el-tag size="small" type="info">{{ plan.cycle || '阶段周期待定' }}</el-tag>
                    </div>
                    <div class="mt-2 text-xs text-slate-500">目标</div>
                    <ul class="mt-1 list-disc space-y-1 pl-5 text-sm text-slate-700">
                      <li v-for="goal in plan.goals || []" :key="goal">{{ goal }}</li>
                    </ul>
                    <div class="mt-2 text-xs text-slate-500">建议任务</div>
                    <div class="mt-1 space-y-2 text-sm text-slate-700">
                      <div
                        v-for="(task, taskIndex) in plan.suggestedTasks || []"
                        :key="`${task.title}-${taskIndex}`"
                        class="flex items-center justify-between gap-2 rounded-md border border-slate-200 bg-slate-50 px-2 py-1"
                      >
                        <span>{{ task.title }}</span>
                        <el-button
                          v-if="task.linkUrl"
                          size="small"
                          type="primary"
                          text
                          @click="openExternalLink(task.linkUrl)"
                        >
                          {{ task.linkText || '跳转' }}
                        </el-button>
                      </div>
                    </div>
                    <div v-if="plan.metrics?.length" class="mt-2 text-xs text-slate-500">指标</div>
                    <ul v-if="plan.metrics?.length" class="mt-1 list-disc space-y-1 pl-5 text-sm text-slate-700">
                      <li v-for="metric in plan.metrics" :key="metric">{{ metric }}</li>
                    </ul>
                  </div>
                </div>
              </div>
            </transition>
          </div>

          <div class="rounded-lg border border-slate-200 p-3" v-if="pathEvaluation">
            <div class="text-sm font-medium text-slate-700">AI简评</div>
            <div class="mt-1 text-sm leading-6 text-slate-600">{{ pathEvaluation.aiCommentary }}</div>
            <div class="mt-2 space-y-1 text-xs text-rose-600" v-if="pathEvaluation.riskAlerts?.length">
              <div v-for="item in pathEvaluation.riskAlerts" :key="item">- {{ item }}</div>
            </div>
          </div>
        </div>
      </div>
    </el-card>

    <el-drawer
      v-model="nodeDetailDrawerVisible"
      :title="nodeDetailTitle"
      size="420px"
      :with-header="true"
      append-to-body
      :z-index="3200"
    >
      <div v-loading="nodeDetailLoading" class="space-y-3">
        <template v-if="nodeDetail">
          <div class="rounded-lg border border-slate-200 bg-slate-50 p-3 text-sm">
            <div class="font-semibold text-slate-800">{{ nodeDetail.jobName }}</div>
            <div class="mt-1 text-xs text-slate-500">{{ nodeDetail.companyName }} · {{ nodeDetail.city }} · {{ displaySalaryText(nodeDetail) }}</div>
            <div class="mt-2 flex flex-wrap gap-2">
              <el-tag v-for="tag in nodeDetail.cats || []" :key="tag">{{ tag }}</el-tag>
              <el-tag v-if="nodeDetail.edu" type="info">{{ nodeDetail.edu }}</el-tag>
              <el-tag v-if="nodeDetail.exp" type="info">{{ nodeDetail.exp }}</el-tag>
              <el-tag v-if="nodeDetail.tier" type="success">{{ nodeDetail.tier }}</el-tag>
              <el-tag type="info">{{ nodeDetail.updatedAtRaw || '发布日期未知' }}</el-tag>
            </div>
          </div>

          <div class="rounded-lg border border-slate-200 p-3 text-sm leading-6 text-slate-600">
            <div class="mb-1 text-sm font-medium text-slate-700">岗位描述</div>
            {{ nodeDetail.jobDescription }}
          </div>

          <!-- 能力要求是 ES 时代的字段，pgvector 的 job_detail_vector 里没有，无数据时整块隐藏 -->
          <div v-if="Object.keys(nodeDetail.abilityRequirements || {}).length" class="rounded-lg border border-slate-200 p-3">
            <div class="mb-2 text-sm font-medium text-slate-700">岗位能力要求（Top5）</div>
            <div class="space-y-2">
              <div
                v-for="item in Object.entries(nodeDetail.abilityRequirements || {}).sort((a,b)=>Number(b[1])-Number(a[1])).slice(0,5)"
                :key="item[0]"
              >
                <div class="mb-1 flex items-center justify-between text-xs">
                  <span class="text-slate-600">{{ abilityLabelMap[item[0]] || item[0] }}</span>
                  <span class="font-semibold text-slate-700">{{ item[1] }}</span>
                </div>
                <el-progress :show-text="false" :percentage="Number(item[1])" :stroke-width="7" :color="progressGradientColor(Number(item[1]))" />
              </div>
            </div>
          </div>
        </template>

        <template v-else>
          <div class="rounded-lg border border-emerald-200 bg-emerald-50 p-3 text-sm text-emerald-800">
            当前节点为固定“职业起点”，表示用户当前阶段（在校/待就业）出发点。
          </div>
          <div class="rounded-lg border border-slate-200 p-3 text-sm text-slate-600 leading-6">
            你可以从这里添加目标岗位节点，系统会根据岗位关联关系与能力匹配度，评估路径可行性与推荐度。
          </div>
        </template>
      </div>
    </el-drawer>

    <el-dialog
      v-model="startPlanModeDialogVisible"
      title="选择路径规划模式"
      width="420px"
      append-to-body
      :lock-scroll="false"
      :z-index="3200"
      :close-on-click-modal="false"
      destroy-on-close
    >
      <div class="space-y-3 text-sm text-slate-600">
        <p>将基于当前匹配岗位生成职业路径规划并保存，请选择本次规划模式：</p>
        <el-radio-group v-model="autoPlanMode" class="w-full">
          <div class="space-y-2 rounded-lg border border-slate-200 bg-slate-50 p-3">
            <el-radio label="conservative">稳健模式：小步迭代，优先降低跳转风险</el-radio>
            <el-radio label="balanced">平衡模式：兼顾成长速度与可行性</el-radio>
            <el-radio label="aggressive">进阶模式：提升速度更快，但挑战更高</el-radio>
          </div>
        </el-radio-group>
      </div>
      <template #footer>
        <div class="flex items-center justify-end gap-2">
          <el-button @click="startPlanModeDialogVisible = false">取消</el-button>
          <el-button type="primary" :loading="pathLoading" @click="submitStartPlanMode">开始生成</el-button>
        </div>
      </template>
    </el-dialog>

    <el-dialog
      v-model="savedPathDialogVisible"
      title="保存路径列表"
      width="640px"
      append-to-body
      :lock-scroll="false"
      :z-index="3200"
      destroy-on-close
    >
      <div v-loading="savedPathListLoading" class="max-h-[420px] overflow-auto pr-1">
        <div v-if="savedPathList.length" class="space-y-2">
          <div
            v-for="item in savedPathList"
            :key="item.pathId"
            class="rounded-lg border border-slate-200 bg-white p-3"
          >
            <div class="flex flex-wrap items-center justify-between gap-2">
              <div>
                <div class="text-sm font-semibold text-slate-800">{{ item.pathName }}</div>
                <div class="mt-1 text-xs text-slate-500">
                  节点数：{{ item.pathNodeCount || 0 }} · 更新时间：{{ formatTime(item.updatedAt) }}
                </div>
                <div v-if="item.targetJobName" class="mt-1 text-[11px] text-slate-500">目标岗位：{{ item.targetJobName }}</div>
              </div>
              <div class="flex items-center gap-2">
                <el-tag size="small" :type="(item.feasibilityScore || 0) >= 80 ? 'success' : (item.feasibilityScore || 0) >= 65 ? 'warning' : 'danger'">
                  可行性 {{ item.feasibilityScore || 0 }}
                </el-tag>
                <el-button size="small" type="primary" @click="requestApplySavedPath(item)">应用</el-button>
                <el-button size="small" type="danger" :loading="savedPathDeletingId === item.pathId" @click="requestDeleteSavedPath(item)">删除</el-button>
              </div>
            </div>
          </div>
        </div>
        <el-empty v-else description="暂无已保存路径" />
      </div>
      <template #footer>
        <div class="flex items-center justify-end">
          <el-button @click="savedPathDialogVisible = false">关闭</el-button>
        </div>
      </template>
    </el-dialog>

    <el-dialog
      v-model="applySavedPathConfirmVisible"
      title="应用保存路径"
      width="420px"
      append-to-body
      :lock-scroll="false"
      :z-index="3600"
      :close-on-click-modal="false"
      destroy-on-close
    >
      <div class="text-sm text-slate-600">
        此操作会覆盖当前草稿，是否应用「{{ pendingApplySavedPath?.pathName || '所选路径' }}」？
      </div>
      <template #footer>
        <div class="flex items-center justify-end gap-2">
          <el-button @click="cancelApplySavedPath">取消</el-button>
          <el-button type="primary" @click="applySavedPathToSandbox">确认覆盖</el-button>
        </div>
      </template>
    </el-dialog>

    <el-dialog
      v-model="deleteSavedPathConfirmVisible"
      title="删除保存路径"
      width="420px"
      append-to-body
      :lock-scroll="false"
      :z-index="3800"
      :close-on-click-modal="false"
      destroy-on-close
    >
      <div class="text-sm text-slate-600">
        删除保存路径「{{ pendingDeleteSavedPath?.pathName || '所选路径' }}」后不可恢复，是否继续？
      </div>
      <template #footer>
        <div class="flex items-center justify-end gap-2">
          <el-button @click="cancelDeleteSavedPath">取消</el-button>
          <el-button type="danger" :loading="Boolean(pendingDeleteSavedPath?.pathId) && savedPathDeletingId === pendingDeleteSavedPath?.pathId" @click="confirmDeleteSavedPath">删除</el-button>
        </div>
      </template>
    </el-dialog>
  </section>
</template>

<style scoped>
.eval-toast-enter-active,
.eval-toast-leave-active {
  transition: all 220ms ease;
}

.eval-toast-enter-from,
.eval-toast-leave-to {
  opacity: 0;
  transform: translate(-50%, -6px);
}

.eval-toast-enter-to,
.eval-toast-leave-from {
  opacity: 1;
  transform: translate(-50%, 0);
}

.eval-panel-slide-enter-active,
.eval-panel-slide-leave-active {
  transition: all 260ms ease;
}

.eval-panel-slide-enter-from {
  opacity: 0;
  transform: translateX(20px);
}

.eval-panel-slide-leave-to {
  opacity: 0;
  transform: translateX(-20px);
}

.match-detail-empty :deep(.el-empty__image img) {
  opacity: 0.2;
}
</style>
