<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import { onBeforeRouteLeave, useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { WarningFilled } from '@element-plus/icons-vue'
import * as echarts from 'echarts'
import reportEmptyImage from '../assets/report.png'
import {
  checkCareerReportCompleteness,
  createCareerReportExportJob,
  createCareerReportGenerateJob,
  getCareerReportDetail,
  getCareerReportExportJobStatus,
  getCareerReportGenerateJobStatus,
  getCareerPathDetail,
  getCareerReportPolishJobStatus,
  getCareerReportList,
  getLatestCareerPathSummary,
  getCareerReportPathOptions,
  getLatestCareerReport,
  polishCareerReport,
  updateCareerReport,
  type CareerPathDetailResult,
  type CareerReportDetail,
  type CareerReportPathOption,
} from '../services/careerReport'
import { getJobDetail, type JobDetailResult } from '../services/jobGraph'
import { isConflictCode, isSuccessCode } from '../services/http'
import { CAREER_REPORT_REFRESH_EVENT, openGlobalAssistant, type CareerReportRefreshPayload } from '../utils/globalAssistant'

interface ApiResponse<T> {
  code: number
  msg: string
  data?: T
}

type CareerPathNodeLite = {
  id: string
  jobId: string
  jobName: string
  stage: string
}

const router = useRouter()
const route = useRoute()
const initLoading = ref(false)
const generating = ref(false)
const polishing = ref(false)
const polishingJobId = ref('')
const polishMode = ref(false)
const polishingWithAi = ref(false)
const checking = ref(false)
const saving = ref(false)
const exporting = ref(false)
const isEditMode = ref(false)
const editSnapshot = ref('')
const actionPanelCollapsed = ref(false)
const polishTone = ref('professional')
const polishTargetReader = ref('校招面试官')

const polishSectionFieldMap: Record<string, string> = {
  executiveSummary: '执行摘要',
  currentAssessment: '现状评估',
  targetAnalysis: '目标岗位分析',
  pathStrategy: '路径策略',
  stagePlan: '阶段计划',
  riskControl: '风险与对策',
  resourceRecommendations: '资源建议',
  reviewMechanism: '复盘机制',
}

const polishFocusDraft = reactive<Record<string, { selected: boolean; instruction: string }>>({
  executiveSummary: { selected: false, instruction: '' },
  currentAssessment: { selected: false, instruction: '' },
  targetAnalysis: { selected: false, instruction: '' },
  pathStrategy: { selected: true, instruction: '' },
  stagePlan: { selected: true, instruction: '' },
  riskControl: { selected: false, instruction: '' },
  resourceRecommendations: { selected: false, instruction: '' },
  reviewMechanism: { selected: false, instruction: '' },
})

const selectedPathId = ref('')
const reportTitleInput = ref('')
const exportFormat = ref<'pdf' | 'docx' | 'markdown'>('pdf')
const CLOUD_EXPORT_ENABLED = false

const stageAdjustDialogVisible = ref(false)
const stageAdjustSaving = ref(false)
const stageAdjustTargetIndex = ref(-1)
const stageAdjustDraft = reactive({
  stageLabel: '',
  cycle: '',
  goals: [] as string[],
  tasks: [] as string[],
  deliverables: [] as string[],
  newGoal: '',
  newTask: '',
  newDeliverable: '',
})

const pathOptions = ref<CareerReportPathOption[]>([])
const pathNodesByPathId = ref<Record<string, CareerPathNodeLite[]>>({})
const reportList = ref<Array<{
  reportId: string
  reportTitle: string
  status: string
  pathRef: { pathId: string; pathName: string; targetJobName: string }
  version: number
  generatedAt: string
  updatedAt: string
}>>([])
const currentReport = ref<CareerReportDetail | null>(null)
const currentPathJobDetails = ref<JobDetailResult[]>([])

const completenessResult = ref<{
  score: number
  passed: boolean
  dimensions: Array<{ key: string; label: string; score: number; issues: string[] }>
  suggestions: string[]
} | null>(null)

const exportResult = ref<{
  reportId: string
  format: string
  fileName: string
  downloadUrl: string
  expiresAt: string
  renderMeta?: {
    source: string
    renderEngine: string
    styleProfile: string
    frontendStyleDependent: boolean
  }
} | null>(null)
const reportEmptyImageUrl = reportEmptyImage

const editableSections = reactive({
  executiveSummary: '',
  currentAssessment: '',
  targetAnalysis: '',
  pathStrategy: '',
  reviewCadence: '',
  reviewAdjustmentRule: '',
})

const coreMetricChartRef = ref<HTMLDivElement>()
const abilityRadarChartRef = ref<HTMLDivElement>()
let coreMetricChart: echarts.ECharts | null = null
let abilityRadarChart: echarts.ECharts | null = null

const canGenerate = computed(() => Boolean(selectedPathId.value) && !generating.value)
const hasPathOptions = computed(() => pathOptions.value.length > 0)
const showNoReportCenterPlaceholder = computed(() => {
  return !initLoading.value && hasPathOptions.value && !currentReport.value && reportList.value.length === 0
})

const stageMilestones = computed(() => currentReport.value?.reportSections.stagePlan.milestones || [])
const reportRisks = computed(() => currentReport.value?.reportSections.riskControl.items || [])
const reportMetrics = computed(() => currentReport.value?.evaluationSnapshot.summaryMetrics || [])
const abilityCompareDimensions = computed(() => currentReport.value?.evaluationSnapshot.abilityComparison?.dimensions || [])
const resourceCourses = computed(() => currentReport.value?.reportSections.resourceRecommendations.courses || [])
const resourceCommunities = computed(() => currentReport.value?.reportSections.resourceRecommendations.communities || [])
const resourceCertifications = computed(() => currentReport.value?.reportSections.resourceRecommendations.certifications || [])
const sectionTitles = computed(() => ({
  executiveSummary: currentReport.value?.reportSections.executiveSummary?.title || '执行摘要',
  currentAssessment: currentReport.value?.reportSections.currentAssessment?.title || '现状评估',
  targetAnalysis: currentReport.value?.reportSections.targetAnalysis?.title || '目标岗位分析',
  pathStrategy: currentReport.value?.reportSections.pathStrategy?.title || '路径策略',
  stagePlan: currentReport.value?.reportSections.stagePlan?.title || '阶段计划',
  riskControl: currentReport.value?.reportSections.riskControl?.title || '风险与对策',
  resourceRecommendations: currentReport.value?.reportSections.resourceRecommendations?.title || '学习资源建议',
  reviewMechanism: currentReport.value?.reportSections.reviewMechanism?.title || '复盘建议',
}))

const hasUnsavedChanges = computed(() => {
  if (!isEditMode.value || !currentReport.value) return false
  return buildEditSnapshot() !== editSnapshot.value
})

const metricExplainMap: Record<string, string> = {
  feasibilityScore: '路径可行性：基于路径边相似度、跨度与结构复杂度评估路径可执行程度。',
  readinessScore: '目标就绪度：基于你当前能力与目标岗位要求的匹配程度评估。',
  recommendationScore: '综合推荐度：综合可行性与就绪度后的总体建议分。',
  avgSimilarity: '路径相似度：路径各相邻岗位能力相似度均值，越高过渡越平滑。',
  targetMatch: '岗位匹配度：你与目标岗位的整体匹配水平。',
}

function getSummaryMetricValue(key: string) {
  const item = reportMetrics.value.find(metric => String(metric.key) === key)
  return Number(item?.value || 0)
}

const coreScoreBars = computed(() => ([
  { key: 'feasibilityScore', label: '可行性', value: Number(currentReport.value?.evaluationSnapshot.feasibilityScore || 0) },
  { key: 'readinessScore', label: '就绪度', value: Number(currentReport.value?.evaluationSnapshot.readinessScore || 0) },
  { key: 'recommendationScore', label: '推荐度', value: Number(currentReport.value?.evaluationSnapshot.recommendationScore || 0) },
  { key: 'avgSimilarity', label: '路径相似度', value: getSummaryMetricValue('avgSimilarity') },
  { key: 'targetMatch', label: '岗位匹配度', value: getSummaryMetricValue('targetMatch') },
]))

function stagePlanCardStyle(stageLabel: string) {
  const label = String(stageLabel || '')
  if (label.includes('过渡')) {
    return {
      background: 'linear-gradient(135deg, rgba(248,250,252,0.95), rgba(255,255,255,0.95))',
      borderColor: 'rgba(148,163,184,0.28)',
    }
  }
  if (label.includes('晋升')) {
    return {
      background: 'linear-gradient(135deg, rgba(239,246,255,0.95), rgba(255,255,255,0.95))',
      borderColor: 'rgba(96,165,250,0.35)',
    }
  }
  if (label.includes('换岗')) {
    return {
      background: 'linear-gradient(135deg, rgba(254,252,232,0.95), rgba(255,255,255,0.95))',
      borderColor: 'rgba(250,204,21,0.35)',
    }
  }
  return {
    background: 'linear-gradient(135deg, rgba(240,253,244,0.95), rgba(255,255,255,0.95))',
    borderColor: 'rgba(74,222,128,0.35)',
  }
}

function addStageAdjustItem(field: 'goals' | 'tasks' | 'deliverables') {
  stageAdjustDraft[field].push('')
}

function removeStageAdjustItem(field: 'goals' | 'tasks' | 'deliverables', index: number) {
  if (index < 0 || index >= stageAdjustDraft[field].length) return
  stageAdjustDraft[field].splice(index, 1)
}

function openStageAdjustDialog(item: {
  stageLabel: string
  cycle: string
  goals: string[]
  tasks: string[]
  deliverables: string[]
}, index: number) {
  if (!currentReport.value) {
    ElMessage.warning('暂无可调整的报告内容')
    return
  }
  if (isEditMode.value) {
    ElMessage.warning('请先保存或取消当前编辑，再调整阶段计划')
    return
  }

  stageAdjustTargetIndex.value = index
  stageAdjustDraft.stageLabel = String(item.stageLabel || '').trim()
  stageAdjustDraft.cycle = String(item.cycle || '').trim()
  stageAdjustDraft.goals = Array.isArray(item.goals) ? item.goals.map(goal => String(goal || '').trim()).filter(Boolean) : []
  stageAdjustDraft.tasks = Array.isArray(item.tasks) ? item.tasks.map(task => String(task || '').trim()).filter(Boolean) : []
  stageAdjustDraft.deliverables = Array.isArray(item.deliverables)
    ? item.deliverables.map(deliverable => String(deliverable || '').trim()).filter(Boolean)
    : []
  if (!stageAdjustDraft.goals.length) stageAdjustDraft.goals = ['']
  if (!stageAdjustDraft.tasks.length) stageAdjustDraft.tasks = ['']
  if (!stageAdjustDraft.deliverables.length) stageAdjustDraft.deliverables = ['']
  stageAdjustDraft.newGoal = ''
  stageAdjustDraft.newTask = ''
  stageAdjustDraft.newDeliverable = ''
  stageAdjustDialogVisible.value = true
}

async function handleSaveStageAdjust() {
  if (!currentReport.value) {
    ElMessage.warning('暂无可保存的报告')
    return
  }
  const targetIndex = Number(stageAdjustTargetIndex.value)
  if (!Number.isInteger(targetIndex) || targetIndex < 0) {
    ElMessage.warning('未识别到要调整的阶段')
    return
  }

  const nextMilestone = {
    stageLabel: stageAdjustDraft.stageLabel.trim() || '阶段计划',
    cycle: stageAdjustDraft.cycle.trim() || '待补充',
    goals: stageAdjustDraft.goals.map(item => String(item || '').trim()).filter(Boolean),
    tasks: stageAdjustDraft.tasks.map(item => String(item || '').trim()).filter(Boolean),
    deliverables: stageAdjustDraft.deliverables.map(item => String(item || '').trim()).filter(Boolean),
  }

  if (!nextMilestone.goals.length || !nextMilestone.tasks.length || !nextMilestone.deliverables.length) {
    ElMessage.warning('目标、任务、交付物至少各填写一项')
    return
  }

  const milestones = currentReport.value.reportSections.stagePlan.milestones || []
  if (!milestones[targetIndex]) {
    ElMessage.warning('阶段索引已失效，请重试')
    return
  }

  stageAdjustSaving.value = true
  try {
    const nextMilestones = milestones.map((item, index) => (index === targetIndex ? nextMilestone : item))
    const nextSections = {
      ...currentReport.value.reportSections,
      stagePlan: {
        ...currentReport.value.reportSections.stagePlan,
        milestones: nextMilestones,
      },
    }

    const response = await updateCareerReport(currentReport.value.reportId, {
      reportTitle: currentReport.value.reportTitle,
      status: currentReport.value.status,
      reportSections: nextSections,
    })
    const payload = response.data as ApiResponse<{ reportId: string }>
    if (!isSuccessCode(payload.code)) {
      throw new Error(payload.msg || '阶段调整保存失败')
    }

    await loadReportDetail(currentReport.value.reportId)
    await refreshReportList()
    await nextTick()
    renderCharts()
    stageAdjustDialogVisible.value = false
    ElMessage.success('阶段计划已更新')
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '阶段调整保存失败')
  } finally {
    stageAdjustSaving.value = false
  }
}

const currentPathNodes = computed(() => {
  const reportPathId = String(currentReport.value?.pathRef.pathId || '').trim()
  if (reportPathId) {
    return pathNodesByPathId.value[reportPathId] || []
  }

  const pickedPathId = String(selectedPathId.value || '').trim()
  if (!pickedPathId) return []
  return pathNodesByPathId.value[pickedPathId] || []
})

const heroBackgroundStyle = computed(() => {
  const company = currentPathJobDetails.value[currentPathJobDetails.value.length - 1]?.companyName || 'career'
  const encoded = encodeURIComponent(`${company} office`) 
  return {
    backgroundImage: `linear-gradient(to right, rgba(255,255,255,0.92), rgba(255,255,255,0.78)), url(https://source.unsplash.com/1600x900/?${encoded})`,
    backgroundSize: 'cover',
    backgroundPosition: 'center',
  }
})

async function ensureCareerPathDetailLoaded(pathId: string) {
  const normalizedPathId = String(pathId || '').trim()
  if (!normalizedPathId) return
  if (pathNodesByPathId.value[normalizedPathId]?.length) return

  let payload: ApiResponse<CareerPathDetailResult> | null = null
  try {
    const response = await getCareerPathDetail({ pathId: normalizedPathId })
    payload = response.data as ApiResponse<CareerPathDetailResult>
  } catch {
    return
  }

  if (!payload || !isSuccessCode(Number(payload.code))) {
    return
  }

  const detail = payload.data
  if (detail?.source !== 'saved' || !detail.savedPath) {
    return
  }

  const nodes = Array.isArray(detail.savedPath.pathNodes)
    ? detail.savedPath.pathNodes.map(item => ({
        id: String(item.id || ''),
        jobId: String(item.jobId || ''),
        jobName: String(item.jobName || ''),
        stage: String(item.stage || ''),
      }))
    : []

  pathNodesByPathId.value = {
    ...pathNodesByPathId.value,
    [normalizedPathId]: nodes,
  }
}

function syncEditableSections(report: CareerReportDetail | null) {
  editableSections.executiveSummary = report?.reportSections.executiveSummary.content || ''
  editableSections.currentAssessment = report?.reportSections.currentAssessment.content || ''
  editableSections.targetAnalysis = report?.reportSections.targetAnalysis.content || ''
  editableSections.pathStrategy = report?.reportSections.pathStrategy.content || ''
  editableSections.reviewCadence = report?.reportSections.reviewMechanism.cadence || ''
  editableSections.reviewAdjustmentRule = report?.reportSections.reviewMechanism.adjustmentRule || ''
}

function buildEditSnapshot() {
  return JSON.stringify({
    reportId: currentReport.value?.reportId || '',
    reportTitle: reportTitleInput.value,
    executiveSummary: editableSections.executiveSummary,
    currentAssessment: editableSections.currentAssessment,
    targetAnalysis: editableSections.targetAnalysis,
    pathStrategy: editableSections.pathStrategy,
    reviewCadence: editableSections.reviewCadence,
    reviewAdjustmentRule: editableSections.reviewAdjustmentRule,
  })
}

function markEditSnapshot() {
  editSnapshot.value = buildEditSnapshot()
}

function startEditing() {
  if (!currentReport.value) {
    ElMessage.warning('暂无可编辑报告')
    return
  }
  isEditMode.value = true
  markEditSnapshot()
}

function resetPolishDraft() {
  Object.keys(polishFocusDraft).forEach((key) => {
    polishFocusDraft[key].selected = ['pathStrategy', 'stagePlan'].includes(key)
    polishFocusDraft[key].instruction = ''
  })
}

function enterPolishMode() {
  if (!currentReport.value) {
    ElMessage.warning('暂无可润色报告')
    return
  }
  if (isEditMode.value) {
    ElMessage.warning('请先保存或取消编辑，再执行智能润色')
    return
  }
  resetPolishDraft()
  polishMode.value = true
}

function exitPolishMode() {
  polishMode.value = false
  polishingJobId.value = ''
}

function setSectionSelected(sectionKey: string, selected: boolean) {
  if (!polishFocusDraft[sectionKey]) return
  polishFocusDraft[sectionKey].selected = Boolean(selected)
}

const selectedPolishSections = computed(() => {
  return Object.entries(polishFocusDraft)
    .filter(([, cfg]) => cfg.selected)
    .map(([key, cfg]) => ({
      key,
      label: sectionTitles.value[key as keyof typeof sectionTitles.value] || polishSectionFieldMap[key] || key,
      instruction: String(cfg.instruction || '').trim(),
    }))
})

const polishPanelSections = computed(() => selectedPolishSections.value)

function buildPolishFocusPayload() {
  const focusSections: Record<string, { instruction?: string }> = {}
  selectedPolishSections.value.forEach((item) => {
    focusSections[item.key] = {
      instruction: item.instruction || undefined,
    }
  })
  return focusSections
}

function collapseActionPanel() {
  actionPanelCollapsed.value = true
}

function expandActionPanel() {
  actionPanelCollapsed.value = false
}

async function cancelEditing() {
  if (!currentReport.value) return

  if (hasUnsavedChanges.value) {
    try {
      await ElMessageBox.confirm('当前编辑内容尚未保存，是否放弃修改？', '提示', {
        type: 'warning',
        confirmButtonText: '放弃修改',
        cancelButtonText: '继续编辑',
        lockScroll: false,
      })
    } catch {
      return
    }
  }

  reportTitleInput.value = currentReport.value.reportTitle
  syncEditableSections(currentReport.value)
  isEditMode.value = false
  markEditSnapshot()
}

function handleBeforeUnload(event: BeforeUnloadEvent) {
  if (!hasUnsavedChanges.value) return
  event.preventDefault()
  event.returnValue = ''
}

function getErrorCode(error: unknown) {
  const response = (error as { response?: { data?: { code?: number }; status?: number } })?.response
  return Number(response?.data?.code || response?.status || 0)
}

function getErrorData(error: unknown) {
  return ((error as { response?: { data?: { data?: Record<string, unknown> } } })?.response?.data?.data || null) as
    | Record<string, unknown>
    | null
}

function clampPollMs(value: unknown, fallback = 800) {
  const numeric = Number(value)
  const resolved = Number.isFinite(numeric) ? numeric : fallback
  return Math.max(1000, Math.min(2000, Math.round(resolved)))
}

function resolvePolishedSectionLabels(polishedSections: unknown) {
  if (!polishedSections || typeof polishedSections !== 'object') return [] as string[]
  return Object.keys(polishedSections as Record<string, unknown>)
    .map((key) => sectionTitles.value[key as keyof typeof sectionTitles.value] || polishSectionFieldMap[key] || key)
    .filter(Boolean)
}

async function loadReportDetail(reportId: string) {
  const response = await getCareerReportDetail(reportId)
  const payload = response.data as ApiResponse<CareerReportDetail>
  if (!isSuccessCode(payload.code) || !payload.data) return
  currentReport.value = payload.data
  reportTitleInput.value = payload.data.reportTitle
  syncEditableSections(payload.data)
  isEditMode.value = false
  markEditSnapshot()
}

async function refreshReportContent(preferredReportId = '') {
  const targetReportId = String(preferredReportId || '').trim()
    || String(currentReport.value?.reportId || '').trim()
    || String(route.query.reportId || '').trim()

  if (targetReportId) {
    await loadReportDetail(targetReportId)
    await refreshReportList()
    return
  }

  const latestRes = await getLatestCareerReport()
  const latestPayload = latestRes.data as ApiResponse<{
    hasReport: boolean
    latestReportId: string | null
    report: CareerReportDetail | null
  }>
  if (isSuccessCode(latestPayload.code) && latestPayload.data?.report) {
    currentReport.value = latestPayload.data.report
    reportTitleInput.value = latestPayload.data.report.reportTitle
    syncEditableSections(latestPayload.data.report)
    isEditMode.value = false
    markEditSnapshot()
  }
  await refreshReportList()
}

function handleCareerReportRefreshEvent(event: Event) {
  const customEvent = event as CustomEvent<CareerReportRefreshPayload>
  const reportId = String(customEvent.detail?.reportId || '').trim()
  const refreshReason = String(customEvent.detail?.reason || '').trim().toLowerCase()

  if (refreshReason === 'polish-completed') {
    if (polishMode.value) {
      exitPolishMode()
      resetPolishDraft()
    }
    polishing.value = false
  }

  refreshReportContent(reportId)
    .then(async () => {
      completenessResult.value = null
      exportResult.value = null
      await nextTick()
      renderCharts()
    })
    .catch(() => {
      ElMessage.warning('报告刷新失败，请稍后重试')
    })
}

async function loadPathJobDetails() {
  const targetPathId = String(currentReport.value?.pathRef.pathId || selectedPathId.value || '').trim()
  if (targetPathId) {
    await ensureCareerPathDetailLoaded(targetPathId)
  }

  const nodes = currentPathNodes.value
  if (!nodes.length) {
    currentPathJobDetails.value = []
    return
  }

  const details = await Promise.all(
    nodes.map(async (node) => {
      try {
        const response = await getJobDetail(node.jobId)
        const payload = response.data as ApiResponse<JobDetailResult>
        if (isSuccessCode(payload.code) && payload.data) {
          return payload.data
        }
      } catch {
        return null
      }
      return null
    }),
  )

  currentPathJobDetails.value = details.filter(Boolean) as JobDetailResult[]
}

function disposeCharts() {
  if (coreMetricChart) {
    coreMetricChart.dispose()
    coreMetricChart = null
  }
  if (abilityRadarChart) {
    abilityRadarChart.dispose()
    abilityRadarChart = null
  }
}

function getBarColorByScore(score: number) {
  if (score >= 90) return '#22c55e'
  if (score >= 75) return '#3b82f6'
  if (score >= 60) return '#f59e0b'
  return '#ef4444'
}

function displaySalaryText(payload: { salaryNormalized?: string; salaryNegotiable?: boolean } | null | undefined) {
  if (payload?.salaryNegotiable) return '面谈'
  const normalized = String(payload?.salaryNormalized || '').trim()
  if (normalized) return normalized
  return '薪资待补充'
}

function renderCharts() {
  if (!currentReport.value || !coreMetricChartRef.value || !abilityRadarChartRef.value) return

  if (!coreMetricChart) {
    coreMetricChart = echarts.init(coreMetricChartRef.value)
  }
  if (!abilityRadarChart) {
    abilityRadarChart = echarts.init(abilityRadarChartRef.value)
  }

  coreMetricChart.setOption({
    grid: { left: 20, right: 20, top: 30, bottom: 20, containLabel: true },
    tooltip: { trigger: 'axis' },
    xAxis: {
      type: 'category',
      axisLabel: { interval: 0, rotate: 20 },
      data: coreScoreBars.value.map(item => item.label),
    },
    yAxis: {
      type: 'value',
      min: 0,
      max: 100,
    },
    series: [
      {
        type: 'bar',
        data: coreScoreBars.value.map(item => item.value),
        barWidth: 26,
        itemStyle: {
          color: (params: { value: number }) => getBarColorByScore(Number(params.value || 0)),
          borderRadius: [6, 6, 0, 0],
        },
      },
    ],
  })

  const abilityList = abilityCompareDimensions.value
  abilityRadarChart.setOption({
    tooltip: { trigger: 'item' },
    legend: {
      bottom: 6,
      data: ['我的能力', '岗位要求'],
    },
    radar: {
      center: ['50%', '44%'],
      radius: 72,
      indicator: abilityList.map(item => ({
        name: item.label,
        max: 100,
      })),
    },
    series: [
      {
        type: 'radar',
        data: [
          {
            name: '我的能力',
            value: abilityList.map(item => Number(item.studentScore || 0)),
            areaStyle: { opacity: 0.22 },
          },
          {
            name: '岗位要求',
            value: abilityList.map(item => Number(item.targetRequiredScore || 0)),
            areaStyle: { opacity: 0.1 },
          },
        ],
      },
    ],
  })
}

async function loadInitial() {
  initLoading.value = true
  try {
    const [pathRes, latestPathRes, listRes, latestRes] = await Promise.all([
      getCareerReportPathOptions(),
      getLatestCareerPathSummary(),
      getCareerReportList(),
      getLatestCareerReport(),
    ])

    const pathPayload = pathRes.data as ApiResponse<{
      total: number
      list: CareerReportPathOption[]
    }>
    const latestPathPayload = latestPathRes.data as ApiResponse<{
      hasPath: boolean
      latestPath: { pathId: string } | null
      draft: unknown
      updatedAt: string | null
    }>
    if (isSuccessCode(pathPayload.code) && pathPayload.data) {
      pathOptions.value = pathPayload.data.list
      const latestPathId = latestPathPayload.data?.latestPath?.pathId || ''
      selectedPathId.value =
        pathPayload.data.list.find(item => item.pathId === latestPathId)?.pathId ||
        pathPayload.data.list[0]?.pathId ||
        ''

      if (selectedPathId.value) {
        await ensureCareerPathDetailLoaded(selectedPathId.value)
      }
    }

    const listPayload = listRes.data as ApiResponse<{
      total: number
      latestReportId: string | null
      list: typeof reportList.value
    }>
    if (isSuccessCode(listPayload.code) && listPayload.data) {
      reportList.value = listPayload.data.list
    }

    const latestPayload = latestRes.data as ApiResponse<{
      hasReport: boolean
      latestReportId: string | null
      report: CareerReportDetail | null
    }>
    if (isSuccessCode(latestPayload.code) && latestPayload.data?.report) {
      currentReport.value = latestPayload.data.report
      reportTitleInput.value = latestPayload.data.report.reportTitle
      syncEditableSections(latestPayload.data.report)
      isEditMode.value = false
      markEditSnapshot()

      if (latestPayload.data.report.pathRef?.pathId) {
        await ensureCareerPathDetailLoaded(latestPayload.data.report.pathRef.pathId)
      }
    }
  } finally {
    initLoading.value = false
  }
}

async function refreshReportList() {
  const response = await getCareerReportList()
  const payload = response.data as ApiResponse<{
    total: number
    latestReportId: string | null
    list: typeof reportList.value
  }>
  if (isSuccessCode(payload.code) && payload.data) {
    reportList.value = payload.data.list
  }
}

async function pollGenerateJob(reportJobId: string) {
  const maxPoll = 45
  for (let i = 0; i < maxPoll; i += 1) {
    const response = await getCareerReportGenerateJobStatus(reportJobId)
    const payload = response.data as ApiResponse<{
      status: 'processing' | 'succeeded' | 'failed'
      pollAfterMs?: number
      result?: { report: CareerReportDetail }
    }>

    if (!isSuccessCode(payload.code) || !payload.data) {
      throw new Error(payload.msg || '报告生成失败')
    }

    if (payload.data.status === 'succeeded' && payload.data.result?.report) {
      currentReport.value = payload.data.result.report
      reportTitleInput.value = payload.data.result.report.reportTitle
      syncEditableSections(payload.data.result.report)
      isEditMode.value = false
      markEditSnapshot()
      await refreshReportList()
      ElMessage.success('报告生成完成')
      return
    }

    if (payload.data.status === 'failed') {
      throw new Error(payload.msg || '报告生成失败')
    }

    const waitMs = clampPollMs(payload.data.pollAfterMs, 2000)
    await new Promise(resolve => setTimeout(resolve, waitMs))
  }

  throw new Error('报告生成超时，请稍后重试')
}

async function requestGenerate(overwriteSamePathReport = false) {
  const response = await createCareerReportGenerateJob({
    pathId: selectedPathId.value,
    reportTitle: reportTitleInput.value.trim() || undefined,
    templateVersion: 'v1.0',
    overwriteLatest: true,
    overwriteSamePathReport,
  })
  const payload = response.data as ApiResponse<{ reportJobId: string }>
  if (!isSuccessCode(payload.code) || !payload.data?.reportJobId) {
    throw new Error(payload.msg || '创建生成任务失败')
  }
  await pollGenerateJob(payload.data.reportJobId)
}

async function handleGenerateReport() {
  if (!selectedPathId.value) {
    ElMessage.warning('请先选择职业路径')
    return
  }

  generating.value = true
  completenessResult.value = null
  exportResult.value = null
  try {
    await requestGenerate(false)
  } catch (error) {
    const code = getErrorCode(error)
    if (isConflictCode(code)) {
      const conflictData = getErrorData(error)
      try {
        await ElMessageBox.confirm(
          `该职业路径已存在生涯报告（${String(conflictData?.existingReport ? (conflictData.existingReport as Record<string, unknown>).reportTitle || '未命名报告' : '历史报告')}），是否重新生成并覆盖原报告内容？`,
          '报告已存在',
          {
            type: 'warning',
            confirmButtonText: '重新生成并覆盖',
            cancelButtonText: '取消',
            lockScroll: false,
          },
        )
        await requestGenerate(true)
      } catch {
        ElMessage.info('已取消重新生成')
      }
    } else {
      ElMessage.error(error instanceof Error ? error.message : '报告生成失败')
    }
  } finally {
    generating.value = false
  }
}

async function handleOpenReport(reportId: string) {
  await loadReportDetail(reportId)
  completenessResult.value = null
  exportResult.value = null
}

async function handleSaveReport() {
  if (!currentReport.value) {
    ElMessage.warning('暂无可保存报告')
    return
  }
  if (!isEditMode.value) {
    ElMessage.warning('请先进入编辑态再保存')
    return
  }

  saving.value = true
  try {
    const nextSections = {
      ...currentReport.value.reportSections,
      executiveSummary: {
        ...currentReport.value.reportSections.executiveSummary,
        content: editableSections.executiveSummary,
      },
      currentAssessment: {
        ...currentReport.value.reportSections.currentAssessment,
        content: editableSections.currentAssessment,
      },
      targetAnalysis: {
        ...currentReport.value.reportSections.targetAnalysis,
        content: editableSections.targetAnalysis,
      },
      pathStrategy: {
        ...currentReport.value.reportSections.pathStrategy,
        content: editableSections.pathStrategy,
      },
      reviewMechanism: {
        ...currentReport.value.reportSections.reviewMechanism,
        cadence: editableSections.reviewCadence,
        adjustmentRule: editableSections.reviewAdjustmentRule,
      },
    }

    const response = await updateCareerReport(currentReport.value.reportId, {
      reportTitle: reportTitleInput.value.trim() || currentReport.value.reportTitle,
      status: currentReport.value.status,
      reportSections: nextSections,
    })

    const payload = response.data as ApiResponse<{ reportId: string }>
    if (!isSuccessCode(payload.code)) {
      throw new Error(payload.msg || '保存失败')
    }

    await loadReportDetail(currentReport.value.reportId)
    await refreshReportList()
    isEditMode.value = false
    markEditSnapshot()
    ElMessage.success('报告已保存')
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '保存失败')
  } finally {
    saving.value = false
  }
}

async function handlePolishReport() {
  if (polishing.value) return
  if (polishMode.value) {
    const hasUnsavedInstruction = Object.values(polishFocusDraft).some(item => String(item?.instruction || '').trim().length > 0)
    if (hasUnsavedInstruction) {
      try {
        await ElMessageBox.confirm('当前已填写润色说明但尚未提交，退出后将丢失，是否继续退出？', '退出润色确认', {
          type: 'warning',
          confirmButtonText: '继续退出',
          cancelButtonText: '继续编辑',
          lockScroll: false,
        })
      } catch {
        return
      }
    }
    exitPolishMode()
    return
  }
  enterPolishMode()
}

async function pollPolishJob(polishJobId: string) {
  const maxPoll = 80
  for (let i = 0; i < maxPoll; i += 1) {
    const response = await getCareerReportPolishJobStatus(polishJobId)
    const payload = response.data as ApiResponse<{
      reportId?: string
      status: 'processing' | 'succeeded' | 'failed'
      pollAfterMs?: number
      result?: {
        reportId?: string
        version?: number
        updatedAt?: string
        report?: CareerReportDetail
        polishedSections?: Record<string, unknown>
      }
    }>

    if (!isSuccessCode(payload.code) || !payload.data) {
      throw new Error(payload.msg || '润色失败')
    }

    if (payload.data.status === 'succeeded') {
      localStorage.removeItem('career_report_pending_polish_job')
      const polishedLabels = resolvePolishedSectionLabels(payload.data.result?.polishedSections)
      const successMessage = polishedLabels.length
        ? `智能润色完成：${polishedLabels.join('、')}`
        : '智能润色完成'
      const polishedReport = payload.data.result?.report
      if (polishedReport) {
        currentReport.value = polishedReport
        reportTitleInput.value = polishedReport.reportTitle
        syncEditableSections(polishedReport)
        isEditMode.value = false
        markEditSnapshot()
        await refreshReportList()
        ElMessage.success(successMessage)
        return
      }

      const nextReportId = String(
        payload.data.result?.reportId || payload.data.reportId || currentReport.value?.reportId || '',
      ).trim()
      if (!nextReportId) {
        throw new Error('润色完成但未返回报告信息')
      }

      await loadReportDetail(nextReportId)
      await refreshReportList()
      ElMessage.success(successMessage)
      return
    }

    if (payload.data.status === 'failed') {
      localStorage.removeItem('career_report_pending_polish_job')
      throw new Error(payload.msg || '润色失败')
    }

    const waitMs = clampPollMs(payload.data.pollAfterMs, 1500)
    await new Promise(resolve => setTimeout(resolve, waitMs))
  }
  throw new Error('润色超时，请稍后重试')
}

async function handleSubmitPolish() {
  if (!currentReport.value) return
  const focusSections = buildPolishFocusPayload()
  if (!Object.keys(focusSections).length) {
    ElMessage.warning('请至少选择一个需要润色的模块')
    return
  }

  polishing.value = true
  try {
    const response = await polishCareerReport(currentReport.value.reportId, {
      tone: polishTone.value,
      targetReader: polishTargetReader.value,
      focusSections,
    })

    const payload = response.data as ApiResponse<{
      polishJobId: string
      reportId?: string
      status: 'processing'
      pollAfterMs?: number
    }>
    if (!isSuccessCode(payload.code) || !payload.data?.polishJobId) {
      throw new Error(payload.msg || '创建润色任务失败')
    }

    polishingJobId.value = payload.data.polishJobId
    localStorage.setItem(
      'career_report_pending_polish_job',
      JSON.stringify({
        polishJobId: payload.data.polishJobId,
        reportId: payload.data.reportId || currentReport.value.reportId,
        createdAt: Date.now(),
        source: 'report-panel',
      }),
    )

    await pollPolishJob(payload.data.polishJobId)
    exitPolishMode()
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '润色失败')
  } finally {
    polishing.value = false
  }
}

async function handleGotoHomeWithReport() {
  if (!currentReport.value) {
    ElMessage.warning('暂无可用报告')
    return
  }

  polishingWithAi.value = true
  try {
    const reportId = String(currentReport.value.reportId || '').trim()
    const reportTitle = String(currentReport.value.reportTitle || '当前报告').trim()
    if (!reportId) {
      throw new Error('报告ID缺失')
    }

    openGlobalAssistant({
      routePath: '/report',
      pageTitle: '生涯报告',
      contextPrompt: '请基于当前报告做评估并给出可执行建议。',
      initialMessage: `请帮我评价和润色这份报告，给出指导建议。报告ID：${reportId}，报告标题：${reportTitle}`,
      autoSendInitialMessage: true,
      data: {
        reportId,
        reportTitle,
      },
    })
    ElMessage.success('已在AI助手中创建报告会话并开始分析')
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '创建会话失败')
  } finally {
    polishingWithAi.value = false
  }
}

async function handleCheckCompleteness() {
  if (!currentReport.value) {
    ElMessage.warning('暂无可检查报告')
    return
  }
  if (isEditMode.value) {
    ElMessage.warning('请先保存或取消编辑，再执行完整性检查')
    return
  }

  checking.value = true
  try {
    const response = await checkCareerReportCompleteness(currentReport.value.reportId)
    const payload = response.data as ApiResponse<{
      reportId: string
      score: number
      passed: boolean
      dimensions: Array<{ key: string; label: string; score: number; issues: string[] }>
      suggestions: string[]
    }>

    if (!isSuccessCode(payload.code) || !payload.data) {
      throw new Error(payload.msg || '检查失败')
    }

    completenessResult.value = {
      score: payload.data.score,
      passed: payload.data.passed,
      dimensions: payload.data.dimensions,
      suggestions: payload.data.suggestions,
    }
    ElMessage.success('完整性检查完成')
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '检查失败')
  } finally {
    checking.value = false
  }
}

async function pollExportJob(exportJobId: string) {
  const maxPoll = 45
  for (let i = 0; i < maxPoll; i += 1) {
    const response = await getCareerReportExportJobStatus(exportJobId)
    const payload = response.data as ApiResponse<{
      status: 'processing' | 'succeeded' | 'failed'
      pollAfterMs?: number
      result?: {
        reportId: string
        format: string
        fileName: string
        downloadUrl: string
        expiresAt: string
        renderMeta?: {
          source: string
          renderEngine: string
          styleProfile: string
          frontendStyleDependent: boolean
        }
      }
    }>

    if (!isSuccessCode(payload.code) || !payload.data) {
      throw new Error(payload.msg || '导出失败')
    }

    if (payload.data.status === 'succeeded' && payload.data.result) {
      exportResult.value = payload.data.result
      ElMessage.success('导出完成')
      return
    }

    if (payload.data.status === 'failed') {
      throw new Error(payload.msg || '导出失败')
    }

    const waitMs = clampPollMs(payload.data.pollAfterMs, 1500)
    await new Promise(resolve => setTimeout(resolve, waitMs))
  }

  throw new Error('导出超时，请稍后重试')
}

async function handleExportReport() {
  if (!currentReport.value) {
    ElMessage.warning('暂无可导出报告')
    return
  }
  if (isEditMode.value) {
    ElMessage.warning('请先保存或取消编辑，再导出报告')
    return
  }

  if (!CLOUD_EXPORT_ENABLED) {
    exportResult.value = null
    ElMessage.error('为避免占用服务器资源，云端部署不提供报告导出功能')
    return
  }

  exporting.value = true
  exportResult.value = null
  try {
    const response = await createCareerReportExportJob(currentReport.value.reportId, {
      format: exportFormat.value,
      includeCover: true,
      includeTimestamp: true,
      pdfTemplate: {
        cover: {
          enabled: true,
          title: '大学生职业生涯发展报告',
          subtitle: 'Career Development Report',
        },
        header: {
          enabled: true,
          text: '大学生职业生涯发展报告',
        },
        footer: {
          enabled: true,
          text: '由微光职引-大学生职业规划智能体生成',
        },
        pagination: {
          enabled: true,
          format: '第 {{page}} 页 / 共 {{total}} 页',
        },
      },
    })
    const payload = response.data as ApiResponse<{ exportJobId: string }>
    if (!isSuccessCode(payload.code) || !payload.data?.exportJobId) {
      throw new Error(payload.msg || '创建导出任务失败')
    }

    await pollExportJob(payload.data.exportJobId)
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '导出失败')
  } finally {
    exporting.value = false
  }
}

watch(
  () => currentReport.value?.reportId,
  async () => {
    await loadPathJobDetails()
    await nextTick()
    renderCharts()
  },
)

watch(
  () => selectedPathId.value,
  async (next, prev) => {
    if (!next || next === prev) return
    await ensureCareerPathDetailLoaded(next)
  },
)

watch(
  () => currentPathNodes.value.map(item => item.jobId).join(','),
  async () => {
    await loadPathJobDetails()
  },
)

watch(
  () => `${coreScoreBars.value.map(item => `${item.key}:${item.value}`).join('|')}|${abilityCompareDimensions.value.map(item => `${item.key}:${item.studentScore}:${item.targetRequiredScore}`).join('|')}`,
  async () => {
    await nextTick()
    renderCharts()
  },
)

watch(
  () => String(route.query.reportId || '').trim(),
  async (next, prev) => {
    if (!next || next === prev) return
    await loadReportDetail(next)
    completenessResult.value = null
    exportResult.value = null
  },
)

onMounted(async () => {
  await loadInitial()
  const reportIdFromQuery = String(route.query.reportId || '').trim()
  if (reportIdFromQuery) {
    await loadReportDetail(reportIdFromQuery)
  }
  await loadPathJobDetails()
  await nextTick()
  renderCharts()
  window.addEventListener('resize', renderCharts)
  window.addEventListener('beforeunload', handleBeforeUnload)
  window.addEventListener(CAREER_REPORT_REFRESH_EVENT, handleCareerReportRefreshEvent as EventListener)
})

onBeforeUnmount(() => {
  disposeCharts()
  window.removeEventListener('resize', renderCharts)
  window.removeEventListener('beforeunload', handleBeforeUnload)
  window.removeEventListener(CAREER_REPORT_REFRESH_EVENT, handleCareerReportRefreshEvent as EventListener)
})

onBeforeRouteLeave(async () => {
  if (!hasUnsavedChanges.value) return true
  try {
    await ElMessageBox.confirm('当前页面有未保存编辑内容，离开后将丢失，是否继续离开？', '未保存提示', {
      type: 'warning',
      confirmButtonText: '离开页面',
      cancelButtonText: '留在当前页',
      lockScroll: false,
    })
    return true
  } catch {
    return false
  }
})
</script>

<template>
  <section class="space-y-4 md:space-y-6" v-loading="initLoading">
    <div class="flex flex-col gap-3 rounded-xl border border-slate-200 bg-white p-4 md:flex-row md:items-end md:justify-between md:p-5">
      <div class="space-y-2">
        <h2 class="text-xl font-semibold text-slate-900 md:text-2xl">生涯报告与计划导出</h2>
        <p class="text-sm text-slate-500">支持路径选择、报告生成、A4结构化编辑、智能润色、完整性检查与导出。</p>
      </div>

      <div class="grid w-full grid-cols-1 gap-2 md:w-auto md:min-w-[560px] md:grid-cols-[240px_1fr_auto]">
        <el-select v-model="selectedPathId" placeholder="选择职业路径" filterable :disabled="!hasPathOptions">
          <el-option
            v-for="item in pathOptions"
            :key="item.pathId"
            :value="item.pathId"
            :label="`${item.pathName}（目标：${item.targetJobName || '未命名'}，${item.pathNodeCount || 0}节点）`"
          />
        </el-select>
        <el-input v-model="reportTitleInput" placeholder="报告标题（可选）" clearable />
        <el-button type="primary" :disabled="!canGenerate" :loading="generating" @click="handleGenerateReport">生成报告</el-button>
      </div>
    </div>

    <el-card v-if="!hasPathOptions" shadow="never" class="border border-dashed border-slate-300">
      <el-empty class="report-empty" :image="reportEmptyImageUrl" description="尚无生成生涯报告，请选择一条路径执行。">
        <el-button type="primary" @click="router.push('/match')">前往职业规划</el-button>
      </el-empty>
    </el-card>

    <div class="grid grid-cols-1 gap-4 xl:grid-cols-[300px_1fr]">
      <transition name="left-panel-switch" mode="out-in">
        <el-card v-if="!polishMode" key="report-list" shadow="never" class="sticky top-[72px] h-fit self-start border border-slate-200 left-sticky-panel">
          <template #header>
            <div class="flex items-center justify-between">
              <span class="font-medium">报告列表</span>
              <span class="text-xs text-slate-500">共 {{ reportList.length }} 条</span>
            </div>
          </template>

          <div v-if="!reportList.length" class="rounded-lg border border-dashed border-slate-200 p-4 text-center text-sm text-slate-500">
            暂无报告，选择路径后可生成。
          </div>

          <div v-else class="space-y-2">
            <button
              v-for="item in reportList"
              :key="item.reportId"
              type="button"
              class="w-full rounded-lg border px-3 py-2 text-left transition hover:border-blue-300 hover:bg-blue-50"
              :class="currentReport?.reportId === item.reportId ? 'border-blue-400 bg-blue-50' : 'border-slate-200 bg-white'"
              @click="handleOpenReport(item.reportId)"
            >
              <p class="line-clamp-1 text-sm font-medium text-slate-800">{{ item.reportTitle }}</p>
              <p class="mt-1 text-xs text-slate-500">{{ item.pathRef.pathName }} · V{{ item.version }}</p>
            </button>
          </div>
        </el-card>

        <el-card v-else key="polish-workbench" shadow="never" class="sticky top-[72px] h-fit self-start border border-indigo-200 bg-indigo-50/40 left-sticky-panel polish-workbench">
          <template #header>
            <div class="flex items-center justify-between gap-2">
              <span class="font-medium text-indigo-900">智能润色</span>
              <el-tag size="small" type="primary" effect="plain">{{ polishPanelSections.length }} 个模块</el-tag>
            </div>
          </template>

          <div class="space-y-3 polish-workbench-scroll">
            <div class="rounded-lg border border-indigo-200 bg-white p-3">
              <p class="text-xs font-medium text-slate-500">润色语气</p>
              <el-select v-model="polishTone" class="mt-1 w-full">
                <el-option label="专业严谨" value="professional" />
                <el-option label="简洁清晰" value="concise" />
                <el-option label="积极鼓励" value="encouraging" />
              </el-select>
            </div>

            <div class="rounded-lg border border-indigo-200 bg-white p-3">
              <p class="text-xs font-medium text-slate-500">目标读者</p>
              <el-input v-model="polishTargetReader" class="mt-1" placeholder="如：校招面试官" />
            </div>

            <div class="rounded-lg border border-indigo-200 bg-white p-3">
              <p class="text-xs font-medium text-slate-500">已选模块与润色说明</p>
              <div v-if="!polishPanelSections.length" class="mt-2 text-xs text-slate-500">请在右侧报告标题处勾选要润色的模块。</div>
              <div v-else class="mt-2 space-y-2">
                <div v-for="item in polishPanelSections" :key="item.key" class="rounded-md border border-slate-200 bg-slate-50 p-2">
                  <p class="text-xs font-medium text-slate-700">{{ item.label }}</p>
                  <el-input
                    v-model="polishFocusDraft[item.key].instruction"
                    class="mt-1"
                    type="textarea"
                    :rows="2"
                    placeholder="可选：描述你希望AI如何修改该模块"
                  />
                </div>
              </div>
            </div>

            <div class="rounded-lg border border-indigo-200 bg-white p-3">
              <p class="text-xs text-slate-500">想先让AI给你建议？可直接在当前页面唤起AI助手创建报告会话。</p>
              <el-button class="mt-2" type="primary" size="small" plain :loading="polishingWithAi" @click="handleGotoHomeWithReport">让AI评估并给建议</el-button>
            </div>

            <div class="flex items-center justify-center gap-2">
              <el-button type="primary" :loading="polishing" @click="handleSubmitPolish">保存并开始智能润色</el-button>
            </div>
          </div>
        </el-card>
      </transition>

      <div class="space-y-4">
        <el-card v-if="showNoReportCenterPlaceholder" shadow="never" class="border border-dashed border-slate-300">
          <el-empty
            class="report-empty"
            :image="reportEmptyImageUrl"
            description="尚无生成生涯报告，请选择一条路径执行。"
          >
            <el-button type="primary" :disabled="!canGenerate" :loading="generating" @click="handleGenerateReport">生成报告</el-button>
          </el-empty>
        </el-card>

        <transition name="action-panel-switch" mode="out-in">
          <div
            v-if="currentReport && !actionPanelCollapsed"
            key="expanded"
            class="sticky top-[72px] z-20 rounded-xl border border-slate-200 bg-white/95 p-4 backdrop-blur action-panel-expanded"
          >
            <div class="flex items-start justify-between gap-3">
              <div class="flex flex-wrap items-center gap-2">
                <el-button v-if="!isEditMode" type="primary" @click="startEditing">进入编辑</el-button>
                <template v-else>
                  <el-button type="primary" :loading="saving" @click="handleSaveReport">保存编辑</el-button>
                  <el-button @click="cancelEditing">取消编辑</el-button>
                </template>
                <el-button type="primary" plain :loading="polishing" :disabled="isEditMode" @click="handlePolishReport">{{ polishMode ? '退出润色' : '智能润色' }}</el-button>
                <el-button :loading="checking" :disabled="isEditMode || polishMode" @click="handleCheckCompleteness">完整性检查</el-button>
                <template v-if="!isEditMode">
                  <el-select v-model="exportFormat" style="width: 100px; margin-left: 18rem;" :disabled="polishMode">
                    <el-option label="PDF" value="pdf" />
                    <el-option label="DOCX" value="docx" />
                    <el-option label="Markdown" value="markdown" />
                  </el-select>
                  <el-button type="success" :loading="exporting" :disabled="polishMode" @click="handleExportReport">导出报告</el-button>
                </template>
              </div>

              <button type="button" class="panel-toggle-btn" title="收起操作面板" @click="collapseActionPanel"><</button>
            </div>

            <p v-if="isEditMode" class="mt-2 text-xs text-amber-600">已开始编辑：请保存或取消后再执行润色/检查/导出。</p>
          </div>

          <div
            v-else-if="currentReport"
            key="collapsed"
            class="sticky top-[72px] z-20 h-0 action-panel-collapsed"
          >
            <div class="flex justify-end">
              <button type="button" class="panel-expand-btn" title="展开操作面板" @click="expandActionPanel">···</button>
            </div>
          </div>
        </transition>

        <el-card v-if="completenessResult" shadow="never" class="border border-slate-200">
          <template #header>
            <div class="flex items-center justify-between">
              <span class="font-medium">完整性检查结果</span>
              <el-tag :type="completenessResult.passed ? 'success' : 'warning'">
                {{ completenessResult.passed ? '通过' : '待完善' }} · {{ completenessResult.score }}分
              </el-tag>
            </div>
          </template>

          <div class="grid grid-cols-1 gap-3 md:grid-cols-3">
            <div v-for="item in completenessResult.dimensions" :key="item.key" class="rounded-lg border border-slate-200 p-3">
              <p class="text-sm font-medium text-slate-900">{{ item.label }}</p>
              <p class="mt-1 text-lg font-semibold text-slate-900">{{ item.score }}</p>
              <ul class="mt-2 list-disc space-y-1 pl-4 text-xs text-slate-600">
                <li v-for="issue in item.issues" :key="issue">{{ issue }}</li>
              </ul>
            </div>
          </div>
        </el-card>

        <div v-if="currentReport" class="report-a4-shell">
          <article class="report-a4-page">
            <header class="rounded-xl border border-slate-200 p-5 report-hero" :style="heroBackgroundStyle">
              <p class="text-xs uppercase tracking-wider text-slate-500">Career Development Report</p>
              <h3 class="mt-2 text-2xl font-bold text-slate-900">{{ currentReport.reportTitle }}</h3>
              <p class="mt-2 text-sm text-slate-600">
                路径：{{ currentReport.pathRef.pathName }} · 目标岗位：{{ currentReport.pathRef.targetJobName }} · 版本 V{{ currentReport.editingMeta.version }}
              </p>
            </header>

            <section class="mt-4 grid grid-cols-1 gap-3 md:grid-cols-2">
              <div class="rounded-xl border border-slate-200 p-4">
                <p class="mb-2 text-sm font-semibold text-slate-800">核心指标柱状图</p>
                <div ref="coreMetricChartRef" class="h-[240px] w-full" />
              </div>
              <div class="rounded-xl border border-slate-200 p-4">
                <p class="text-sm font-semibold text-slate-800">就业能力与岗位要求</p>
                <div ref="abilityRadarChartRef" class="h-[240px] w-full" />
              </div>
            </section>

            <section class="mt-3 rounded-xl border border-slate-200 bg-slate-50 p-3">
              <p class="text-xs font-medium text-slate-600">指标说明</p>
              <div class="mt-2 grid grid-cols-1 gap-1 md:grid-cols-2">
                <p
                  v-for="item in coreScoreBars"
                  :key="item.key"
                  class="text-xs text-slate-500"
                >
                  {{ item.label }}：{{ metricExplainMap[item.key] || '指标说明待补充' }}
                </p>
              </div>
            </section>

            <section class="mt-4 rounded-xl border border-slate-200 p-4">
              <h4 class="text-base font-semibold text-slate-900">路径岗位与目标岗位信息</h4>
              <div class="mt-3 grid grid-cols-1 gap-3 md:grid-cols-2">
                <div
                  v-for="(job, index) in currentPathJobDetails"
                  :key="job.jobId"
                  class="rounded-xl border border-slate-200 bg-white/90 p-4 backdrop-blur-sm report-job-card"
                  :style="{ backgroundImage: `linear-gradient(to right, rgba(255,255,255,0.92), rgba(255,255,255,0.82)), url(https://source.unsplash.com/1200x800/?${encodeURIComponent(job.companyName + ' building')})` }"
                >
                  <div class="flex items-center justify-between gap-3">
                    <p class="text-sm font-semibold text-slate-900">{{ index + 1 }}. {{ job.jobName }}</p>
                    <el-tag size="small">{{ job.level }}</el-tag>
                  </div>
                  <p class="mt-1 text-xs text-slate-600">{{ job.companyName }} · {{ job.city }}{{ job.district ? `-${job.district}` : '' }} · {{ displaySalaryText(job) }}</p>
                  <p class="mt-1 text-xs text-slate-500">{{ job.updatedAtRaw || '发布日期未知' }} · {{ job.sourceSite || '来源待补充' }}</p>
                  <p class="mt-1 text-xs text-slate-500">{{ (job.industryTags || []).join(' / ') || '行业标签待补充' }}</p>
                  <p class="mt-1 text-xs text-slate-500">{{ job.companyType || '公司类型待补充' }} · {{ job.companySize || '公司规模待补充' }}</p>
                  <p class="mt-2 text-sm text-slate-700 line-clamp-3">岗位描述：{{ job.jobDescription }}</p>
                </div>
              </div>
            </section>

            <section class="mt-4 rounded-xl border border-slate-200 p-4">
              <h4 class="text-base font-semibold text-slate-900">报告分析</h4>
              <div class="mt-3 space-y-3">
                <p class="flex items-center gap-2 text-xs font-medium text-slate-500">
                  <el-checkbox v-if="polishMode" :model-value="polishFocusDraft.executiveSummary.selected" @change="setSectionSelected('executiveSummary', Boolean($event))" />
                  <span>{{ sectionTitles.executiveSummary }}</span>
                </p>
                <el-input v-model="editableSections.executiveSummary" type="textarea" :rows="3" placeholder="执行摘要" :disabled="!isEditMode" />
                <p class="flex items-center gap-2 text-xs font-medium text-slate-500">
                  <el-checkbox v-if="polishMode" :model-value="polishFocusDraft.currentAssessment.selected" @change="setSectionSelected('currentAssessment', Boolean($event))" />
                  <span>{{ sectionTitles.currentAssessment }}</span>
                </p>
                <el-input v-model="editableSections.currentAssessment" type="textarea" :rows="4" placeholder="现状评估" :disabled="!isEditMode" />
                <p class="flex items-center gap-2 text-xs font-medium text-slate-500">
                  <el-checkbox v-if="polishMode" :model-value="polishFocusDraft.targetAnalysis.selected" @change="setSectionSelected('targetAnalysis', Boolean($event))" />
                  <span>{{ sectionTitles.targetAnalysis }}</span>
                </p>
                <el-input v-model="editableSections.targetAnalysis" type="textarea" :rows="4" placeholder="目标分析" :disabled="!isEditMode" />
                <p class="flex items-center gap-2 text-xs font-medium text-slate-500">
                  <el-checkbox v-if="polishMode" :model-value="polishFocusDraft.pathStrategy.selected" @change="setSectionSelected('pathStrategy', Boolean($event))" />
                  <span>{{ sectionTitles.pathStrategy }}</span>
                </p>
                <el-input v-model="editableSections.pathStrategy" type="textarea" :rows="4" placeholder="路径策略" :disabled="!isEditMode" />
              </div>
            </section>

            <section class="mt-4 rounded-xl border border-slate-200 p-4">
              <h4 class="flex items-center gap-2 text-base font-semibold text-slate-900">
                <el-checkbox v-if="polishMode" :model-value="polishFocusDraft.stagePlan.selected" @change="setSectionSelected('stagePlan', Boolean($event))" />
                <span>{{ sectionTitles.stagePlan }}</span>
              </h4>
              <div class="mt-3 space-y-3">
                <el-card
                  v-for="(item, index) in stageMilestones"
                  :key="`${item.stageLabel}-${index}`"
                  shadow="never"
                  class="border border-slate-200"
                  :style="stagePlanCardStyle(item.stageLabel)"
                >
                  <template #header>
                    <div class="flex items-center justify-between">
                      <span class="font-medium">{{ item.stageLabel }}</span>
                      <div class="flex items-center gap-2">
                        <span class="text-xs text-slate-500">{{ item.cycle }}</span>
                        <el-button text type="primary" size="small" @click="openStageAdjustDialog(item, index)">调整</el-button>
                      </div>
                    </div>
                  </template>

                  <div class="grid grid-cols-1 gap-3 md:grid-cols-3">
                    <div>
                      <p class="mb-1 text-xs font-medium text-slate-500">目标</p>
                      <ul class="list-disc space-y-1 pl-4 text-sm text-slate-700">
                        <li v-for="goal in item.goals" :key="goal">{{ goal }}</li>
                      </ul>
                    </div>
                    <div>
                      <p class="mb-1 text-xs font-medium text-slate-500">任务</p>
                      <ul class="list-disc space-y-1 pl-4 text-sm text-slate-700">
                        <li v-for="task in item.tasks" :key="task">{{ task }}</li>
                      </ul>
                    </div>
                    <div>
                      <p class="mb-1 text-xs font-medium text-slate-500">交付物</p>
                      <ul class="list-disc space-y-1 pl-4 text-sm text-slate-700">
                        <li v-for="deliver in item.deliverables" :key="deliver">{{ deliver }}</li>
                      </ul>
                    </div>
                  </div>
                </el-card>
              </div>
            </section>

            <section class="mt-4 grid grid-cols-1 gap-3 md:grid-cols-2">
              <el-card shadow="never" class="border border-rose-200 bg-gradient-to-br from-rose-50 via-white to-white-50">
                <template #header>
                  <div class="flex items-center gap-2">
                    <el-icon class="text-rose-500"><WarningFilled /></el-icon>
                    <el-checkbox v-if="polishMode" :model-value="polishFocusDraft.riskControl.selected" @change="setSectionSelected('riskControl', Boolean($event))" />
                    <span class="font-medium text-rose-700">{{ sectionTitles.riskControl }}</span>
                  </div>
                </template>
                <div class="space-y-2 text-sm text-slate-700">
                  <p v-for="(item, index) in reportRisks" :key="`${item.risk}-${index}`">
                    <span class="font-medium text-slate-900">风险：</span>{{ item.risk }}；<span class="font-medium text-slate-900">对策：</span>{{ item.mitigation }}
                  </p>
                </div>
              </el-card>

              <el-card shadow="never" class="border border-slate-200">
                <template #header>
                  <div class="flex items-center gap-2">
                    <el-checkbox v-if="polishMode" :model-value="polishFocusDraft.reviewMechanism.selected" @change="setSectionSelected('reviewMechanism', Boolean($event))" />
                    <span class="font-medium">{{ sectionTitles.reviewMechanism }}</span>
                  </div>
                </template>
                <div class="space-y-2">
                  <el-input v-model="editableSections.reviewCadence" placeholder="复盘节奏" :disabled="!isEditMode" />
                  <el-input v-model="editableSections.reviewAdjustmentRule" type="textarea" :rows="3" placeholder="调整规则" :disabled="!isEditMode" />
                </div>
              </el-card>
            </section>

            <section class="mt-4 rounded-xl border border-slate-200 p-4">
              <h4 class="flex items-center gap-2 text-base font-semibold text-slate-900">
                <el-checkbox v-if="polishMode" :model-value="polishFocusDraft.resourceRecommendations.selected" @change="setSectionSelected('resourceRecommendations', Boolean($event))" />
                <span>{{ sectionTitles.resourceRecommendations }}</span>
              </h4>
              <div class="mt-3 grid grid-cols-1 gap-3 md:grid-cols-3">
                <div class="rounded-lg bg-slate-50 p-3">
                  <p class="mb-2 text-xs font-medium text-slate-500">课程</p>
                  <p v-for="item in resourceCourses" :key="item.name" class="mb-1 text-sm text-slate-700">{{ item.name }}</p>
                </div>
                <div class="rounded-lg bg-slate-50 p-3">
                  <p class="mb-2 text-xs font-medium text-slate-500">社区</p>
                  <p v-for="item in resourceCommunities" :key="item.name" class="mb-1 text-sm text-slate-700">{{ item.name }}</p>
                </div>
                <div class="rounded-lg bg-slate-50 p-3">
                  <p class="mb-2 text-xs font-medium text-slate-500">认证</p>
                  <p v-for="item in resourceCertifications" :key="item.name" class="mb-1 text-sm text-slate-700">{{ item.name }}</p>
                </div>
              </div>
            </section>
          </article>
        </div>

        <el-card v-else-if="!showNoReportCenterPlaceholder" shadow="never" class="border border-dashed border-slate-300">
          <div class="py-8 text-center text-sm text-slate-500">请选择左侧报告，或先从上方生成新的生涯报告。</div>
        </el-card>

        <el-card v-if="exportResult" shadow="never" class="border border-green-200 bg-green-50">
          <template #header>
            <span class="font-medium text-green-800">导出结果</span>
          </template>
          <div class="space-y-2 text-sm text-green-900">
            <p><span class="font-medium">文件名：</span>{{ exportResult.fileName }}</p>
            <p><span class="font-medium">格式：</span>{{ exportResult.format }}</p>
            <p><span class="font-medium">过期时间：</span>{{ exportResult.expiresAt }}</p>
            <p v-if="exportResult.renderMeta" class="text-xs text-green-800">
              渲染引擎：{{ exportResult.renderMeta.renderEngine }}，数据源：{{ exportResult.renderMeta.source }}
            </p>
            <a :href="exportResult.downloadUrl" target="_blank" rel="noreferrer" class="inline-flex rounded border border-green-500 px-3 py-1 text-green-700 hover:bg-green-100">
              下载导出文件
            </a>
          </div>
        </el-card>

      </div>
    </div>

    <el-backtop :right="20" :bottom="28" :visibility-height="420" />

    <el-dialog
      v-model="stageAdjustDialogVisible"
      title="调整阶段计划"
      width="720px"
      append-to-body
      :lock-scroll="false"
      class="stage-adjust-dialog"
    >
      <div class="stage-adjust-form">
        <div class="grid grid-cols-1 gap-3 md:grid-cols-2">
          <el-input v-model="stageAdjustDraft.stageLabel" placeholder="阶段标题" />
          <el-input v-model="stageAdjustDraft.cycle" placeholder="阶段周期，如 0-1个月" />
        </div>

        <div class="mt-3 space-y-3">
          <div>
            <p class="mb-1 text-xs font-medium text-slate-500">阶段目标</p>
            <div class="stage-adjust-list-editor">
              <ul class="stage-adjust-list">
                <li v-for="(_, idx) in stageAdjustDraft.goals" :key="`goal-${idx}`" class="stage-adjust-item">
                  <span class="stage-adjust-bullet">•</span>
                  <el-input v-model="stageAdjustDraft.goals[idx]" placeholder="请输入目标" />
                  <el-button text type="danger" @click="removeStageAdjustItem('goals', idx)">删除</el-button>
                </li>
              </ul>
              <div class="stage-adjust-add-row">
                <el-button type="primary" plain @click="addStageAdjustItem('goals')">新增目标</el-button>
              </div>
            </div>
          </div>
          <div>
            <p class="mb-1 text-xs font-medium text-slate-500">关键任务</p>
            <div class="stage-adjust-list-editor">
              <ul class="stage-adjust-list">
                <li v-for="(_, idx) in stageAdjustDraft.tasks" :key="`task-${idx}`" class="stage-adjust-item">
                  <span class="stage-adjust-bullet">•</span>
                  <el-input v-model="stageAdjustDraft.tasks[idx]" placeholder="请输入任务" />
                  <el-button text type="danger" @click="removeStageAdjustItem('tasks', idx)">删除</el-button>
                </li>
              </ul>
              <div class="stage-adjust-add-row">
                <el-button type="primary" plain @click="addStageAdjustItem('tasks')">新增任务</el-button>
              </div>
            </div>
          </div>
          <div>
            <p class="mb-1 text-xs font-medium text-slate-500">阶段交付物</p>
            <div class="stage-adjust-list-editor">
              <ul class="stage-adjust-list">
                <li v-for="(_, idx) in stageAdjustDraft.deliverables" :key="`deliverable-${idx}`" class="stage-adjust-item">
                  <span class="stage-adjust-bullet">•</span>
                  <el-input v-model="stageAdjustDraft.deliverables[idx]" placeholder="请输入交付物" />
                  <el-button text type="danger" @click="removeStageAdjustItem('deliverables', idx)">删除</el-button>
                </li>
              </ul>
              <div class="stage-adjust-add-row">
                <el-button type="primary" plain @click="addStageAdjustItem('deliverables')">新增交付物</el-button>
              </div>
            </div>
          </div>
        </div>
      </div>

      <template #footer>
        <div class="flex items-center justify-end gap-2">
          <el-button @click="stageAdjustDialogVisible = false">取消</el-button>
          <el-button type="primary" :loading="stageAdjustSaving" @click="handleSaveStageAdjust">保存</el-button>
        </div>
      </template>
    </el-dialog>
  </section>
</template>

<style scoped>
.report-a4-shell {
  width: 100%;
  overflow-x: auto;
  scrollbar-width: none;
  -ms-overflow-style: none;
}

.report-a4-shell::-webkit-scrollbar {
  display: none;
}

.left-sticky-panel {
  max-height: calc(100vh - 96px);
  overflow: hidden;
  scrollbar-width: none;
  -ms-overflow-style: none;
}

.left-sticky-panel::-webkit-scrollbar {
  display: none;
}

.polish-workbench-scroll {
  max-height: calc(100vh - 180px);
  overflow-y: auto;
  padding-right: 4px;
  scrollbar-width: none;
  -ms-overflow-style: none;
}

.polish-workbench-scroll::-webkit-scrollbar {
  display: none;
}

.left-panel-switch-enter-active,
.left-panel-switch-leave-active {
  transition: opacity 0.2s ease, transform 0.2s ease;
}

.left-panel-switch-enter-from,
.left-panel-switch-leave-to {
  opacity: 0;
  transform: translateX(-10px);
}

.report-a4-page {
  width: min(100%, 860px);
  min-height: 1123px;
  margin: 0 auto;
  border: 1px solid rgb(226 232 240);
  border-radius: 16px;
  padding: 20px;
  background:
    radial-gradient(circle at 12% 18%, rgba(191, 219, 254, 0.18), transparent 35%),
    radial-gradient(circle at 86% 12%, rgba(167, 243, 208, 0.14), transparent 30%),
    radial-gradient(circle at 82% 84%, rgba(251, 191, 36, 0.08), transparent 26%),
    linear-gradient(180deg, rgba(248, 250, 252, 0.7), rgba(255, 255, 255, 0.95));
}

.report-hero {
  background-repeat: no-repeat;
}

.report-job-card {
  background-size: cover;
  background-position: center;
}

.action-panel-expanded {
  transform-origin: right center;
}

.action-panel-collapsed {
  pointer-events: none;
}

.action-panel-collapsed .panel-expand-btn {
  pointer-events: auto;
}

.panel-toggle-btn,
.panel-expand-btn {
  width: 30px;
  height: 30px;
  border: 1px solid rgb(226 232 240);
  border-radius: 9999px;
  background: rgb(248 250 252 / 95%);
  color: rgb(71 85 105);
  font-size: 16px;
  line-height: 1;
}

.panel-expand-btn {
  margin-top: 4px;
  box-shadow: 0 4px 14px rgb(15 23 42 / 12%);
}

.action-panel-switch-enter-active,
.action-panel-switch-leave-active {
  transition: opacity 0.24s ease, transform 0.24s ease;
}

.action-panel-switch-enter-from,
.action-panel-switch-leave-to {
  opacity: 0;
  transform: translateX(14px) scaleX(0.96);
}

.action-panel-switch-enter-to,
.action-panel-switch-leave-from {
  opacity: 1;
  transform: translateX(0) scaleX(1);
}

:deep(.el-input.is-disabled .el-input__inner),
:deep(.el-textarea.is-disabled .el-textarea__inner),
:deep(.el-select .el-input.is-disabled .el-input__inner) {
  color: rgb(30 41 59);
  -webkit-text-fill-color: rgb(30 41 59);
  opacity: 1;
}

:deep(.el-input.is-disabled .el-input__inner::placeholder),
:deep(.el-textarea.is-disabled .el-textarea__inner::placeholder) {
  color: rgb(148 163 184);
  -webkit-text-fill-color: rgb(148 163 184);
}

.report-empty :deep(.el-empty__image img) {
  opacity: 0.2;
}

:deep(.stage-adjust-dialog .el-dialog) {
  border-radius: 16px;
  overflow: hidden;
}

:deep(.stage-adjust-dialog .el-dialog__header) {
  padding-bottom: 10px;
}

:deep(.stage-adjust-dialog .el-dialog__body) {
  background: linear-gradient(180deg, #f8fafc 0%, #ffffff 100%);
}

:deep(.stage-adjust-dialog .el-input__wrapper),
:deep(.stage-adjust-dialog .el-textarea__inner) {
  background-color: transparent;
  box-shadow: 0 0 0 1px rgb(203 213 225) inset;
}

:deep(.stage-adjust-dialog .el-textarea__inner) {
  resize: none;
}

.stage-adjust-list-editor {
  border: 1px solid rgb(226 232 240);
  border-radius: 10px;
  padding: 10px;
  background: linear-gradient(180deg, rgba(248, 250, 252, 0.8), rgba(255, 255, 255, 0.9));
}

.stage-adjust-add-row {
  display: flex;
  align-items: center;
  justify-content: flex-end;
  margin-top: 10px;
}

.stage-adjust-list {
  margin: 0;
  padding: 0;
  list-style: none;
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.stage-adjust-item {
  display: grid;
  grid-template-columns: 14px 1fr auto;
  align-items: center;
  gap: 8px;
}

.stage-adjust-bullet {
  color: rgb(100 116 139);
  font-size: 14px;
  line-height: 1;
}

.stage-adjust-empty {
  margin: 0;
  color: rgb(148 163 184);
  font-size: 12px;
}
</style>
