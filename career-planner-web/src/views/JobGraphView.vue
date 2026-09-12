<script setup lang="ts">
import { computed, defineAsyncComponent, nextTick, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, type FormInstance, type FormRules } from 'element-plus'
import { ArrowDown, ArrowUp, MagicStick, Plus, Refresh, Star, StarFilled, Search } from '@element-plus/icons-vue'
import * as echarts from 'echarts'
import G6 from '@antv/g6'
import jobsImage from '../assets/jobs.png'
import { ABILITY_GROUPS, ABILITY_LABELS, type CompetencyKey } from '../types/domain'
import {
  addFavoriteJob,
  createCustomFavoriteJob,
  getFavoriteJobs,
  getJobDetail,
  getJobFilters,
  getJobGraph,
  getJobList,
  removeFavoriteJob,
  type FavoriteJobsResult,
  type CustomFavoriteJobRequest,
  type JobDetailResult,
  type JobFilterOptions,
  type JobGraphResult,
  type JobListItem,
  type JobListResult,
} from '../services/jobGraph'
import { getMatchRecommendationsFromPython } from '../services/matchAnalysis'
import { useAppStore } from '../stores/app'
import { useJobRecommendStore } from '../stores/jobRecommend'
import {
  JOB_RECOMMENDATION_APPLY_EVENT,
  consumePendingJobRecommendationApply,
  openGlobalAssistant,
  type JobRecommendationApplyPayload,
} from '../utils/globalAssistant'

interface ApiResponse<T> {
  code: number
  msg: string
  data?: T
  payload?: T
}

interface NormalizedGraphEdge {
  source: string
  target: string
  relationType: 'promotion' | 'transition'
  similarity: number
  difficulty: string
  reason: string
}

interface DisplayGraphEdge extends NormalizedGraphEdge {
  isBidirectional: boolean
}

const isDesktop = ref(window.innerWidth >= 1024)
const router = useRouter()
const appStore = useAppStore()
const jobRecommendStore = useJobRecommendStore()
const listCollapsed = ref(false)
const listViewMode = ref<'all' | 'favorites' | 'recommend'>('all')
const combinedPanelView = ref<'list' | 'graph'>('list')
const rightPanel = ref<'detail' | 'paths' | 'recommend'>('detail')
const filterLoading = ref(false)
const listLoading = ref(false)
const recommendLoading = ref(false)
// 推荐结果放在 store 里（sessionStorage 持久化），切页面回来还在；想更新点「智能推荐岗位」
const matchRecommendations = computed(() => jobRecommendStore.result)
const graphLoading = ref(false)
const detailLoading = ref(false)
const favoriteLoading = ref(false)
const customJobDialogVisible = ref(false)
const customJobSubmitting = ref(false)
const customJobFormRef = ref<FormInstance>()
const createEmptyCustomJobForm = (): CustomFavoriteJobRequest => ({
  title: '',
  company: '',
  city: '',
  salary: '',
  salaryMin: undefined,
  salaryMax: undefined,
  salaryUnit: 'K/月',
  avg: undefined,
  tier: '',
  exp: '',
  edu: '',
  categories: [],
  keywords: [],
  url: '',
  content: '',
})
const customJobForm = reactive<CustomFavoriteJobRequest>(createEmptyCustomJobForm())
const customJobRules: FormRules<CustomFavoriteJobRequest> = {
  title: [
    { required: true, message: '请填写岗位名称', trigger: 'blur' },
    { min: 2, max: 100, message: '岗位名称长度应为 2-100 个字符', trigger: 'blur' },
  ],
  url: [{ type: 'url', message: '请输入完整的网址，例如 https://example.com/job', trigger: 'blur' }],
}

// 筛选选项全部来自后端 /jobs/filters（向量库 DISTINCT），前端不写死
const filterOptions = ref<JobFilterOptions>({
  cities: [],
  educationRequirements: [],
  exps: [],
  salaryTiers: [],
})

// 筛选条件对齐后端 JobVectorFilter：薪资走区间（比 avg），经验/学历/薪资档是多选
const query = reactive({
  keyword: '',
  city: '',
  salaryMin: undefined as number | undefined,
  salaryMax: undefined as number | undefined,
  salaryTier: [] as string[],
  exp: [] as string[],
  edu: [] as string[],
  sortBy: undefined as string | undefined,
  sortOrder: undefined as 'asc' | 'desc' | undefined,
  page: 1,
  pageSize: 12,
})
const latestPublishedFirst = ref(false)

type JobQuerySnapshot = {
  keyword: string
  city: string
  salaryMin: number | undefined
  salaryMax: number | undefined
  salaryTier: string[]
  exp: string[]
  edu: string[]
  sortBy: string | undefined
  sortOrder: 'asc' | 'desc' | undefined
  page: number
  pageSize: number
}

const lastSuccessfulQuery = ref<JobQuerySnapshot | null>(null)

// 多选条件做快照比较时按排序后的拼接串比，避免数组引用比较永远不等
function snapshotListKey(values: string[] | undefined) {
  return [...(values || [])].sort().join('')
}

function createJobQuerySnapshot(): JobQuerySnapshot {
  return {
    keyword: query.keyword,
    city: query.city,
    salaryMin: query.salaryMin,
    salaryMax: query.salaryMax,
    salaryTier: [...query.salaryTier],
    exp: [...query.exp],
    edu: [...query.edu],
    sortBy: query.sortBy,
    sortOrder: query.sortOrder,
    page: query.page,
    pageSize: query.pageSize,
  }
}

function applyJobQuerySnapshot(snapshot: JobQuerySnapshot) {
  Object.assign(query, {
    keyword: snapshot.keyword,
    city: snapshot.city,
    salaryMin: snapshot.salaryMin,
    salaryMax: snapshot.salaryMax,
    salaryTier: [...snapshot.salaryTier],
    exp: [...snapshot.exp],
    edu: [...snapshot.edu],
    sortBy: snapshot.sortBy,
    sortOrder: snapshot.sortOrder,
    page: snapshot.page,
    pageSize: snapshot.pageSize,
  })
  latestPublishedFirst.value = snapshot.sortBy === 'updatedAt' && snapshot.sortOrder === 'desc'
}

function isSameJobQuerySnapshot(left: JobQuerySnapshot, right: JobQuerySnapshot) {
  return left.keyword === right.keyword
    && left.city === right.city
    && left.salaryMin === right.salaryMin
    && left.salaryMax === right.salaryMax
    && snapshotListKey(left.salaryTier) === snapshotListKey(right.salaryTier)
    && snapshotListKey(left.exp) === snapshotListKey(right.exp)
    && snapshotListKey(left.edu) === snapshotListKey(right.edu)
    && left.sortBy === right.sortBy
    && left.sortOrder === right.sortOrder
    && left.page === right.page
    && left.pageSize === right.pageSize
}

const jobs = ref<JobListItem[]>([])
const total = ref(0)
const selectedJobId = ref('')
const selectedCategoryName = ref('')
const selectedDetail = ref<JobDetailResult | null>(null)
const graphData = ref<JobGraphResult | null>(null)
const favoriteJobIds = ref<string[]>([])
const favoriteJobsList = ref<FavoriteJobsResult['list']>([])
const jobDetailMap = ref<Record<string, JobDetailResult>>({})

const graphRelationView = ref<'all' | 'promotion' | 'transition'>('all')
const graphLegendVisible = ref(false)
const graphRef = ref<HTMLDivElement>()
const radarRef = ref<HTMLDivElement>()
let g6Graph: any = null
let g6Rendered = false
let radarChart: echarts.ECharts | null = null
let radarHostEl: HTMLDivElement | null = null
let renderRetryTimer: ReturnType<typeof setTimeout> | null = null
let radarRetryTimer: ReturnType<typeof setTimeout> | null = null
let graphResizeObserver: ResizeObserver | null = null
let graphNeedsReinit = false
const hasGraphRendered = ref(false)
let graphCenterTimers: Array<ReturnType<typeof setTimeout>> = []
let graphTransitionTimers: Array<ReturnType<typeof setTimeout>> = []
let suppressGraphResizeObserver = false
let graphViewAlive = true
const graphInitError = ref('')
const graphTransitioning = ref(false)
const graphHoverTip = reactive({
  visible: false,
  x: 0,
  y: 0,
  jobId: '',
  title: '',
  lines: [] as string[],
  tagText: '',
  tagTone: '',
})
const jobsDetailBackgroundImage = jobsImage
const profileLoaded = ref(false)
const hasAnalyzedProfile = ref(false)
const userAbilityScores = ref<Partial<Record<CompetencyKey, number>> | null>(null)
const jobMapDialogVisible = ref(false)
const JobMapExplorerDialog = defineAsyncComponent(() => import('../components/JobMapExplorerDialog.vue'))

const groupPalette: Record<string, string> = {
  basicRequirement: '#2563eb',
  professionalLiteracy: '#059669',
  developmentPotential: '#7c3aed',
}

const DIMENSION_DETAIL_FALLBACK = '暂无岗位要求说明'
const graphLoadingText = computed(() => (graphTransitioning.value ? '更新中...' : '更新中...'))

function extractPayload<T>(response: { data: ApiResponse<T> }): T | undefined {
  return response.data.payload ?? response.data.data
}

function resolveUnifiedJobName(payload: { jobName?: unknown; categoryName?: unknown } | null | undefined) {
  const name = String(payload?.jobName || payload?.categoryName || '').trim()
  return name
}

function resolveGraphCategoryName(payload: { jobName?: unknown; categoryName?: unknown } | null | undefined) {
  const category = String(payload?.categoryName || payload?.jobName || '').trim()
  return category
}

function normalizeTextList(values: unknown) {
  if (!Array.isArray(values)) return [] as string[]
  const seen = new Set<string>()
  const list: string[] = []
  values.forEach((item) => {
    const text = String(item || '').trim()
    if (!text || seen.has(text)) return
    seen.add(text)
    list.push(text)
  })
  return list
}

function normalizeJobListItem(item: JobListItem | null | undefined): JobListItem | null {
  if (!item) return null
  const unifiedName = resolveUnifiedJobName(item)
  const graphCategoryName = resolveGraphCategoryName(item)
  return {
    ...item,
    jobId: String(item.jobId || '').trim(),
    jobName: unifiedName || '未知岗位',
    categoryName: graphCategoryName || undefined,
    companyName: String(item.companyName || '').trim() || '未知公司',
    city: String(item.city || '').trim() || '未知城市',
    cats: normalizeTextList(item.cats),
    salaryText: String(item.salaryText || '').trim() || undefined,
    tier: String(item.tier || '').trim() || undefined,
    exp: String(item.exp || '').trim() || undefined,
    edu: String(item.edu || '').trim() || undefined,
  }
}

function normalizeJobDetailPayload(payload: JobDetailResult | null | undefined): JobDetailResult | null {
  if (!payload) return null
  const unifiedName = resolveUnifiedJobName(payload)
  const graphCategoryName = resolveGraphCategoryName(payload)
  return {
    ...payload,
    jobName: unifiedName || '未知岗位',
    categoryName: graphCategoryName || undefined,
    companyName: String(payload.companyName || '').trim() || '未知公司',
    city: String(payload.city || '').trim() || '未知城市',
    cats: normalizeTextList(payload.cats),
    salaryText: String(payload.salaryText || '').trim() || undefined,
    tier: String(payload.tier || '').trim() || undefined,
    exp: String(payload.exp || '').trim() || undefined,
    edu: String(payload.edu || '').trim() || undefined,
    jobDescription: String(payload.jobDescription || '').trim() || '岗位描述待补充',
  }
}

function normalizeJobGraphPayload(payload: JobGraphResult | null | undefined): JobGraphResult | null {
  if (!payload) return null
  const sourceNodes = Array.isArray(payload.nodes) ? payload.nodes : []
  const normalizedNodes: JobGraphResult['nodes'] = []

  sourceNodes.forEach((node) => {
    const unifiedName = resolveUnifiedJobName(node)
    if (!unifiedName) return
    normalizedNodes.push({
      jobName: unifiedName,
      industryTags: Array.isArray(node.industryTags) ? node.industryTags.map(tag => String(tag || '').trim()).filter(Boolean) : [],
      jobDescription: String(node.jobDescription || '').trim() || '岗位职责待补充',
    })
  })

  const nodeNameSet = new Set(normalizedNodes.map(node => node.jobName))
  const normalizedEdges = (Array.isArray(payload.edges) ? payload.edges : [])
    .map((edge) => ({
      ...edge,
      source: String(edge.source || '').trim(),
      target: String(edge.target || '').trim(),
      relationType: String(edge.relationType || 'transition').trim(),
      similarity: Number(edge.similarity || 0),
      difficulty: String(edge.difficulty || 'medium').trim(),
      reason: String(edge.reason || '岗位关系').trim(),
    }))
    .filter(edge => edge.source && edge.target && nodeNameSet.has(edge.source) && nodeNameSet.has(edge.target))

  return {
    nodes: normalizedNodes,
    edges: normalizedEdges,
  }
}

function normalizeJobLevel(level: string) {
  const value = String(level || '').toLowerCase().trim()
  if (!value) return 'middle'
  if (value === 'mid' || value === 'middle' || value === '中级') return 'middle'
  if (value === 'junior' || value === '初级' || value === '初阶') return 'junior'
  if (value === 'senior' || value === '高级') return 'senior'
  if (value === 'lead' || value === '资深' || value === '专家' || value === 'principal') return 'lead'
  return value
}

function getNodeColor(level: string) {
  const normalized = normalizeJobLevel(level)
  if (normalized === 'junior') return 'r(0.5,0.5,0.55) 0:#ffffff 0.62:#eff6ff 1:#dbeafe'
  if (normalized === 'middle') return 'r(0.5,0.5,0.55) 0:#60a5fa 0.68:#3b82f6 1:#1d4ed8'
  if (normalized === 'senior') return 'r(0.5,0.5,0.55) 0:#a78bfa 0.7:#8b5cf6 1:#6d28d9'
  if (normalized === 'lead') return 'r(0.5,0.5,0.55) 0:#7c3aed 0.7:#5b21b6 1:#4c1d95'
  return 'r(0.5,0.5,0.55) 0:#7c3aed 0.7:#5b21b6 1:#4c1d95'
}

function getNodeStrokeColor(level: string) {
  const normalized = normalizeJobLevel(level)
  if (normalized === 'junior') return '#3b82f6'
  if (normalized === 'middle') return '#2563eb'
  if (normalized === 'senior') return '#6d28d9'
  if (normalized === 'lead') return '#4c1d95'
  return '#4c1d95'
}

function getSeedNodeGlowColor(level: string) {
  const normalized = normalizeJobLevel(level)
  if (normalized === 'senior') return '#8b5cf6'
  if (normalized === 'lead') return '#5b21b6'
  return '#60a5fa'
}

function getNodeLabelColor(level: string) {
  if (normalizeJobLevel(level) === 'junior') return '#1d4ed8'
  return '#ffffff'
}

function getNodeShortLabel(jobName: string) {
  const normalized = (jobName || '').replace(/\s+/g, '')
  if (!normalized) return ''
  if (normalized.length <= 4) return normalized
  return normalized.slice(0, 4)
}

function getEdgeColor(similarity: number, relationType: string) {
  const normalizedRelationType = normalizeRelationType(relationType)
  if (normalizedRelationType === 'transition') {
    const tier = getTransitionSimilarityTier(similarity)
    if (tier === 'high') return '#2563eb'
    if (tier === 'mid') return '#60a5fa'
    return '#94a3b8'
  }

  if (similarity >= 0.8) return '#2563eb'
  if (similarity >= 0.65) return '#60a5fa'
  return '#94a3b8'
}

function getTransitionSimilarityTier(similarity: number) {
  if (similarity >= 0.58) return 'high'
  if (similarity >= 0.48) return 'mid'
  return 'low'
}

function getSimilarityTagMeta(similarity: number, relationType: string) {
  const normalizedRelationType = normalizeRelationType(relationType)
  if (normalizedRelationType === 'transition') {
    const tier = getTransitionSimilarityTier(similarity)
    if (tier === 'high') return { text: '高相似度', tone: 'sim-high' }
    if (tier === 'mid') return { text: '中相似度', tone: 'sim-mid' }
    return { text: '低相似度', tone: 'sim-low' }
  }

  if (similarity >= 0.8) {
    return { text: '高相似度', tone: 'sim-high' }
  }
  if (similarity >= 0.65) {
    return { text: '中相似度', tone: 'sim-mid' }
  }
  return { text: '低相似度', tone: 'sim-low' }
}

function getLevelTagMeta(level: string) {
  const normalized = normalizeJobLevel(level)
  if (normalized === 'lead') {
    return { text: '资深', tone: 'level-lead' }
  }
  if (normalized === 'senior') {
    return { text: '高', tone: 'level-senior' }
  }
  if (normalized === 'middle') {
    return { text: '中', tone: 'level-middle' }
  }
  return { text: '低', tone: 'level-junior' }
}

function getGraphVisualCenterY(height: number) {
  return Math.round(height * 0.56)
}

function isGraphLevel(value: string): value is 'junior' | 'middle' | 'senior' | 'lead' {
  return value === 'junior' || value === 'middle' || value === 'senior' || value === 'lead'
}

function resolveSeedVisualLevel(_seedNodeId: string, fallbackLevel: string) {
  // 岗位等级（level）是 ES 时代的字段，pgvector 的 job_detail_vector 里没有，
  // 所以这里不再尝试从详情/列表反推等级，直接用图谱节点自身的等级。
  // 保留 isGraphLevel 守卫，避免脏值进到配色逻辑。
  const fallback = normalizeJobLevel(fallbackLevel)
  return isGraphLevel(fallback) ? fallback : 'middle'
}

function normalizeRelationType(relationType: string) {
  return String(relationType || '').toLowerCase().trim() === 'promotion' ? 'promotion' : 'transition'
}

function resolveCareerLevelRank(jobName: string) {
  const text = String(jobName || '').toLowerCase().trim()
  if (!text) return null

  const rankRules: Array<{ pattern: RegExp; rank: number }> = [
    { pattern: /实习|助理|intern/, rank: 0 },
    { pattern: /初级|junior/, rank: 1 },
    { pattern: /中高级/, rank: 3 },
    { pattern: /中级/, rank: 2 },
    { pattern: /高级/, rank: 4 },
    { pattern: /资深|senior/, rank: 5 },
    { pattern: /专家|expert/, rank: 6 },
    { pattern: /架构|architect/, rank: 7 },
    { pattern: /负责人|lead|leader/, rank: 8 },
    { pattern: /总监|director/, rank: 9 },
  ]

  for (const rule of rankRules) {
    if (rule.pattern.test(text)) return rule.rank
  }

  return null
}

function isForwardPromotionEdge(source: string, target: string) {
  const sourceRank = resolveCareerLevelRank(source)
  const targetRank = resolveCareerLevelRank(target)
  if (sourceRank == null || targetRank == null) return true
  return targetRank > sourceRank
}

function resolveGraphNodeLevel(jobName: string): 'junior' | 'middle' | 'senior' | 'lead' {
  const rank = resolveCareerLevelRank(jobName)
  if (rank == null) return 'middle'
  if (rank <= 1) return 'junior'
  if (rank <= 2) return 'middle'
  if (rank <= 4) return 'senior'
  return 'lead'
}

function isEdgeVisibleInCurrentView(relationType: string) {
  const normalized = normalizeRelationType(relationType)
  if (graphRelationView.value === 'promotion') return normalized === 'promotion'
  if (graphRelationView.value === 'transition') return normalized === 'transition'
  return true
}

function getEdgeBaseOpacity(relationType: string) {
  return normalizeRelationType(relationType) === 'promotion' ? 0.98 : 0.42
}

function getEdgeLabelOpacity(relationType: string) {
  return normalizeRelationType(relationType) === 'promotion' ? 0 : 0.72
}

function getEdgeLineWidth(relationType: string, similarity: number) {
  const base = Math.max(2, Math.round((similarity || 0.5) * 4))
  if (normalizeRelationType(relationType) === 'promotion') return Math.max(4, base + 1)
  return Math.max(2, base - 1)
}

function getEdgeDash(relationType: string) {
  return normalizeRelationType(relationType) === 'transition' ? [8, 6] : undefined
}

function getEdgeStrokeColor(relationType: string, similarity: number) {
  const normalizedRelationType = normalizeRelationType(relationType)
  if (normalizedRelationType === 'promotion') return '#2563eb'
  return getEdgeColor(similarity, relationType)
}

function joinEdgeReasons(reasonSet: Iterable<string>) {
  const list = Array.from(reasonSet)
    .map(reason => String(reason || '').trim())
    .filter(Boolean)
  return list.join('；') || '岗位关系'
}

function buildDisplayGraphEdges(edges: NormalizedGraphEdge[]): DisplayGraphEdge[] {
  type DirectedAggregate = {
    source: string
    target: string
    relationType: 'promotion' | 'transition'
    similarity: number
    difficulty: string
    reasons: Set<string>
  }

  const directedMap = new Map<string, DirectedAggregate>()
  edges.forEach((edge) => {
    const source = String(edge.source || '').trim()
    const target = String(edge.target || '').trim()
    if (!source || !target || source === target) return

    const relationType = normalizeRelationType(edge.relationType) as 'promotion' | 'transition'
    if (relationType === 'promotion' && !isForwardPromotionEdge(source, target)) return

    const similarity = Number(edge.similarity || 0)
    const difficulty = String(edge.difficulty || 'medium').trim() || 'medium'
    const reason = String(edge.reason || '').trim() || '岗位关系'
    const key = `${source}-->${target}`
    const existed = directedMap.get(key)

    if (!existed) {
      directedMap.set(key, {
        source,
        target,
        relationType,
        similarity,
        difficulty,
        reasons: new Set([reason]),
      })
      return
    }

    existed.similarity = Math.max(existed.similarity, similarity)
    existed.relationType = existed.relationType === 'promotion' || relationType === 'promotion'
      ? 'promotion'
      : 'transition'
    if (!existed.difficulty) existed.difficulty = difficulty
    existed.reasons.add(reason)
  })

  const pairMap = new Map<string, DirectedAggregate[]>()
  directedMap.forEach((edge) => {
    const pairKey = edge.source < edge.target
      ? `${edge.source}<->${edge.target}`
      : `${edge.target}<->${edge.source}`
    const list = pairMap.get(pairKey) || []
    list.push(edge)
    pairMap.set(pairKey, list)
  })

  const mergedEdges: DisplayGraphEdge[] = []
  pairMap.forEach((group) => {
    if (group.length === 2 && group[0].source === group[1].target && group[0].target === group[1].source) {
      const promotionEdges = group.filter(edge => edge.relationType === 'promotion')
      if (promotionEdges.length) {
        const forwardPromotionEdges = promotionEdges.filter(edge => isForwardPromotionEdge(edge.source, edge.target))
        const candidates = forwardPromotionEdges.length ? forwardPromotionEdges : promotionEdges
        const kept = [...candidates].sort((a, b) => {
          const similarityDiff = Number(b.similarity || 0) - Number(a.similarity || 0)
          if (similarityDiff !== 0) return similarityDiff
          const sourceDiff = a.source.localeCompare(b.source)
          if (sourceDiff !== 0) return sourceDiff
          return a.target.localeCompare(b.target)
        })[0]

        mergedEdges.push({
          source: kept.source,
          target: kept.target,
          relationType: 'promotion',
          similarity: kept.similarity,
          difficulty: kept.difficulty || 'medium',
          reason: joinEdgeReasons(kept.reasons),
          isBidirectional: false,
        })
        return
      }

      const left = group[0].source < group[0].target ? group[0] : group[1]
      const right = left === group[0] ? group[1] : group[0]
      const reasons = new Set<string>([...left.reasons, ...right.reasons])
      mergedEdges.push({
        source: left.source,
        target: left.target,
        relationType: 'transition',
        similarity: Math.max(left.similarity, right.similarity),
        difficulty: left.difficulty || right.difficulty || 'medium',
        reason: joinEdgeReasons(reasons),
        isBidirectional: true,
      })
      return
    }

    group.forEach((edge) => {
      mergedEdges.push({
        source: edge.source,
        target: edge.target,
        relationType: edge.relationType,
        similarity: edge.similarity,
        difficulty: edge.difficulty,
        reason: joinEdgeReasons(edge.reasons),
        isBidirectional: false,
      })
    })
  })

  return mergedEdges
}

function resolveModelOpacity(model: any, fallback = 1) {
  const value = Number(model?.style?.opacity)
  if (Number.isFinite(value)) return value
  return fallback
}

function animateGraphOpacityBatch(
  targets: Array<{
    item: any
    from: number
    to: number
    apply: (item: any, opacity: number, progress: number) => void
  }>,
  duration = 220,
) {
  if (!targets.length || duration <= 0) return Promise.resolve()

  const easeInOutCubic = (t: number) => (t < 0.5 ? 4 * t * t * t : 1 - Math.pow(-2 * t + 2, 3) / 2)

  return new Promise<void>((resolve) => {
    const startedAt = typeof performance !== 'undefined' ? performance.now() : Date.now()

    const tick = () => {
      if (!graphViewAlive || !g6Graph) {
        resolve()
        return
      }

      const now = typeof performance !== 'undefined' ? performance.now() : Date.now()
      const raw = Math.min(1, Math.max(0, (now - startedAt) / duration))
      const progress = easeInOutCubic(raw)

      targets.forEach((target) => {
        const opacity = target.from + (target.to - target.from) * progress
        try {
          target.apply(target.item, opacity, progress)
        } catch {
          // Ignore per-item animation errors.
        }
      })

      if (raw >= 1) {
        resolve()
        return
      }

      requestAnimationFrame(tick)
    }

    requestAnimationFrame(tick)
  })
}

const isSelectedFavorited = computed(() => {
  if (!selectedJobId.value) return false
  return favoriteJobIds.value.includes(selectedJobId.value)
})

const otherRecommendations = computed(() => {
  if (!selectedJobId.value) return jobs.value.slice(0, 5)
  return jobs.value.filter(item => item.jobId !== selectedJobId.value).slice(0, 5)
})

const listTotal = computed(() => {
  if (listViewMode.value === 'favorites') return favoriteJobsList.value.length
  if (listViewMode.value === 'recommend') return recommendedJobList.value.length
  return total.value
})

interface RecommendedJobItem {
  jobId: string
  jobName: string
  companyName: string
  city: string
  district?: string
  level: string
  overallScore: number
  matchTags: string[]
  isBestMatch: boolean
  salaryText?: string
  updatedAtRaw?: string
  // AI 排序理由（Python 侧 /jobs/recommend/specific 返回），推荐卡片的主文案
  reason?: string
}

const recommendedJobList = computed<RecommendedJobItem[]>(() => {
  const result = matchRecommendations.value
  if (!result) return []
  const list: RecommendedJobItem[] = []
  if (result.bestMatch) {
    list.push({ ...result.bestMatch, isBestMatch: true, salaryText: recommendationSalaryText(result.bestMatch) })
  }
  ;(result.otherRecommendations || []).forEach(item => {
    list.push({ ...item, isBestMatch: false, salaryText: recommendationSalaryText(item) })
  })
  return list
})

function recommendationSalaryText(item: { salaryNegotiable?: boolean; salaryNormalized?: string } | null | undefined) {
  if (item?.salaryNegotiable) return '面谈'
  const normalized = String(item?.salaryNormalized || '').trim()
  if (normalized) return normalized
  return '薪资待补充'
}

async function fetchRecommendedJobs() {
  if (recommendLoading.value) return

  if (appStore.hasProfile !== true) {
    try {
      await appStore.ensureProfileSnapshot(true)
    } catch {
      ElMessage.error('获取画像状态失败，请稍后重试')
      return
    }
  }

  if (appStore.hasProfile === false) {
    ElMessage.warning('请先完成能力评估后再进行岗位推荐')
    return
  }

  recommendLoading.value = true
  try {
    const result = await getMatchRecommendationsFromPython({
      userId: appStore.currentStudentId,
      profile: appStore.profileSnapshot,
      intent: {},
    })
    if (!result.ok) {
      ElMessage.error(result.message || '获取推荐失败')
      return
    }
    jobRecommendStore.save(appStore.currentStudentId, result.data)
    if (!recommendedJobList.value.length) {
      ElMessage.warning('暂未匹配到推荐岗位，请稍后重试')
    }
  } finally {
    recommendLoading.value = false
  }
}

const detailGroups = computed(() => {
  if (!selectedDetail.value) return []
  return ABILITY_GROUPS.map((group) => ({
    ...group,
    color: groupPalette[group.key] || '#334155',
    items: group.dimensions.map((dimension) => ({
      key: dimension,
      label: ABILITY_LABELS[dimension],
      score: selectedDetail.value?.abilityRequirements?.[dimension] ?? 0,
      detail: selectedDetail.value?.dimensionDetails?.[dimension] || DIMENSION_DETAIL_FALLBACK,
    })),
  }))
})

const jobDescriptionExpanded = ref(false)
const keySkillsExpanded = ref(false)
const KEY_SKILL_PREVIEW_LIMIT = 6
const rawJobDescriptionText = computed(() => String(selectedDetail.value?.jobDescription || '').trim())
const hasShortJobDescription = computed(() => {
  return rawJobDescriptionText.value.length > 0 && rawJobDescriptionText.value.length < 10
})
const jobDescriptionText = computed(() => {
  if (hasShortJobDescription.value) {
    return '当前没有明确职责描述，详情可点击查看官网'
  }
  const text = rawJobDescriptionText.value
  return text || '岗位描述待补充'
})
const canToggleJobDescription = computed(() => !hasShortJobDescription.value && jobDescriptionText.value.length > 80)
const keySkillHardList = computed(() => normalizeTextList(selectedDetail.value?.keySkills?.hardSkills || []))
const keySkillToolList = computed(() => normalizeTextList(selectedDetail.value?.keySkills?.tools || []))
const hasKeySkills = computed(() => keySkillHardList.value.length > 0 || keySkillToolList.value.length > 0)
const canToggleKeySkills = computed(() => {
  return keySkillHardList.value.length > KEY_SKILL_PREVIEW_LIMIT || keySkillToolList.value.length > KEY_SKILL_PREVIEW_LIMIT
})
const visibleHardSkills = computed(() => {
  if (keySkillsExpanded.value) return keySkillHardList.value
  return keySkillHardList.value.slice(0, KEY_SKILL_PREVIEW_LIMIT)
})
const visibleToolSkills = computed(() => {
  if (keySkillsExpanded.value) return keySkillToolList.value
  return keySkillToolList.value.slice(0, KEY_SKILL_PREVIEW_LIMIT)
})
const hiddenKeySkillsCount = computed(() => {
  if (keySkillsExpanded.value) return 0
  const hiddenHard = Math.max(0, keySkillHardList.value.length - visibleHardSkills.value.length)
  const hiddenTools = Math.max(0, keySkillToolList.value.length - visibleToolSkills.value.length)
  return hiddenHard + hiddenTools
})
const hasHiddenKeySkills = computed(() => hiddenKeySkillsCount.value > 0)

/**
 * 能力雷达图 + 知识点卡片 + 能力项明细，都是 ES 时代靠人工录入的字段。
 * 爬虫采集的数据里没有这些，两块整体隐藏，避免渲染出一张全 0 的雷达图和空标签。
 */
const hasDetailAbilityData = computed(() => {
  const detail = selectedDetail.value
  if (!detail) return false
  const hasAbility = Object.values(detail.abilityRequirements || {}).some((score) => Number(score) > 0)
  return hasAbility || hasKeySkills.value
})

const graphPathSummary = computed(() => {
  const normalizedGraph = buildNormalizedGraphModel(graphData.value)
  const seed = String(selectedCategoryName.value || resolveGraphCategoryName(selectedDetail.value) || '').trim()
  if (!seed || !normalizedGraph.nodes.length) {
    return {
      seed,
      promotionPath: [] as string[],
      transitionPaths: [] as Array<{
        targetJobName: string
        similarity: number
        difficulty: string
        reason: string
      }>,
    }
  }

  const nodeIdSet = new Set(normalizedGraph.nodes.map(node => node.id))
  if (!nodeIdSet.has(seed)) {
    return {
      seed,
      promotionPath: [] as string[],
      transitionPaths: [] as Array<{
        targetJobName: string
        similarity: number
        difficulty: string
        reason: string
      }>,
    }
  }

  const promotionBySource = new Map<string, Array<(typeof normalizedGraph.edges)[number]>>()
  const directTransitionEdges: Array<(typeof normalizedGraph.edges)[number]> = []
  normalizedGraph.edges.forEach((edge) => {
    const relationType = normalizeRelationType(edge.relationType)
    if (relationType === 'promotion') {
      const list = promotionBySource.get(edge.source) || []
      list.push(edge)
      promotionBySource.set(edge.source, list)
      return
    }
    if (edge.source === seed) {
      directTransitionEdges.push(edge)
    }
  })

  const promotionPath = [seed]
  const visited = new Set<string>([seed])
  let current = seed
  let guard = 0
  while (guard < 8) {
    const candidates = (promotionBySource.get(current) || [])
      .filter(edge => !visited.has(edge.target))
      .sort((a, b) => Number(b.similarity || 0) - Number(a.similarity || 0))
    if (!candidates.length) break
    const nextTarget = String(candidates[0].target || '').trim()
    if (!nextTarget) break
    promotionPath.push(nextTarget)
    visited.add(nextTarget)
    current = nextTarget
    guard += 1
  }

  const transitionPaths = directTransitionEdges
    .map(edge => ({
      targetJobName: String(edge.target || '').trim() || '未知岗位',
      similarity: Number(edge.similarity || 0),
      difficulty: String(edge.difficulty || 'medium').trim() || 'medium',
      reason: String(edge.reason || '岗位关系').trim() || '岗位关系',
    }))
    .sort((a, b) => b.similarity - a.similarity)
    .slice(0, 6)

  return {
    seed,
    promotionPath,
    transitionPaths,
  }
})

function graphPathDifficultyLabel(value: string) {
  const normalized = String(value || '').trim().toLowerCase()
  if (normalized === 'low') return '低难度'
  if (normalized === 'medium') return '中难度'
  if (normalized === 'high') return '高难度'
  return '中难度'
}

function graphPathDifficultyTagType(value: string) {
  const normalized = String(value || '').trim().toLowerCase()
  if (normalized === 'low') return 'success'
  if (normalized === 'high') return 'danger'
  return 'warning'
}

function graphPathSimilarityText(value: number) {
  const numeric = Number(value)
  if (!Number.isFinite(numeric) || numeric <= 0) return '--'
  return `${Math.round(numeric * 100)}%`
}

const activeDetailGroupKey = ref<string>('basicRequirement')

const activeDetailGroup = computed(() => {
  const groups = detailGroups.value
  if (!groups.length) return null
  return groups.find(group => group.key === activeDetailGroupKey.value) || groups[0]
})

async function fetchFilters() {
  filterLoading.value = true
  try {
    const response = await getJobFilters()
    const payload = extractPayload<JobFilterOptions>(response)
    if (payload) filterOptions.value = payload
  } finally {
    filterLoading.value = false
  }
}

async function fetchFavorites() {
  favoriteLoading.value = true
  try {
    const response = await getFavoriteJobs()
    const payload = extractPayload<FavoriteJobsResult>(response)
    favoriteJobsList.value = payload?.list ?? []
    favoriteJobIds.value = payload?.list?.map(item => item.jobId) ?? []
  } finally {
    favoriteLoading.value = false
  }
}

async function fetchUserProfileSummary() {
  profileLoaded.value = false
  try {
    const payload = await appStore.ensureProfileSnapshot(false)
    const hasProfile = appStore.hasProfile === true
    const abilityScores = payload?.scores?.abilityScores ?? null
    hasAnalyzedProfile.value = hasProfile && Boolean(abilityScores)
    userAbilityScores.value = hasAnalyzedProfile.value ? abilityScores : null
  } catch {
    hasAnalyzedProfile.value = false
    userAbilityScores.value = null
  } finally {
    profileLoaded.value = true
  }
}

async function fetchJobs(
  resetPage = false,
  options: { disableAutoSelect?: boolean; keepSelectionOnEmpty?: boolean; allowEmptyRollback?: boolean } = {},
) {
  resetGraphRelationView()
  if (resetPage) query.page = 1
  const requestQuery = createJobQuerySnapshot()
  const previousSuccessfulQuery = lastSuccessfulQuery.value
  listLoading.value = true
  try {
    const response = await getJobList({ ...requestQuery })
    const payload = extractPayload<JobListResult>(response)
    const normalizedList = Array.isArray(payload?.list)
      ? payload.list.map(item => normalizeJobListItem(item)).filter((item): item is JobListItem => Boolean(item))
      : []

    const canRollbackToPreviousQuery = previousSuccessfulQuery
      ? !isSameJobQuerySnapshot(requestQuery, previousSuccessfulQuery)
      : false

    const shouldRollbackToPreviousQuery = !normalizedList.length
      && options.allowEmptyRollback !== false
      && resetPage
      && !options.disableAutoSelect
      && !options.keepSelectionOnEmpty
      && canRollbackToPreviousQuery

    if (shouldRollbackToPreviousQuery && previousSuccessfulQuery) {
      applyJobQuerySnapshot(previousSuccessfulQuery)
      ElMessage.info('未获取到目标岗位，可调整筛选项')
      return
    }

    jobs.value = normalizedList
    total.value = payload?.total ?? 0

    if (jobs.value.length) {
      lastSuccessfulQuery.value = requestQuery
    }

    const hasSelectedInList = jobs.value.some(item => item.jobId === selectedJobId.value)
    if (!options.disableAutoSelect && jobs.value.length && (!selectedJobId.value || !hasSelectedInList)) {
      await selectJob(jobs.value[0].jobId)
    } else if (!jobs.value.length) {
      if (!options.keepSelectionOnEmpty) {
        selectedJobId.value = ''
        selectedCategoryName.value = ''
        selectedDetail.value = null
        graphData.value = { nodes: [], edges: [] }
        await nextTick()
        scheduleRenderGraph()
      }
    }
  } finally {
    listLoading.value = false
  }
}

function onPageChange(page: number) {
  if (listViewMode.value === 'favorites') return
  query.page = page
  fetchJobs(false)
}

function onPageSizeChange(size: number) {
  if (listViewMode.value === 'favorites') return
  query.pageSize = size
  query.page = 1
  fetchJobs(false)
}

async function fetchGraph(categoryName?: string) {
  const normalizedCategoryName = String(categoryName || '').trim()
  if (!normalizedCategoryName) {
    graphData.value = { nodes: [], edges: [] }
    await nextTick()
    scheduleRenderGraph()
    return
  }

  graphLoading.value = true
  try {
    const nextGraph = await requestGraphPayloadByCategoryName(normalizedCategoryName)
    graphData.value = nextGraph
    graphNeedsReinit = !g6Graph
    await nextTick()
    scheduleRenderGraph()
  } finally {
    graphLoading.value = false
  }
}

async function requestGraphPayloadByCategoryName(categoryName: string): Promise<JobGraphResult> {
  const normalizedCategoryName = String(categoryName || '').trim()
  if (!normalizedCategoryName) {
    return { nodes: [], edges: [] }
  }

  const response = await getJobGraph({
    categoryName: normalizedCategoryName,
  })
  const payload = extractPayload<JobGraphResult>(response)
  const normalizedPayload = normalizeJobGraphPayload(payload)
  const hasValidGraph = !!normalizedPayload && Array.isArray(normalizedPayload.nodes) && Array.isArray(normalizedPayload.edges)
  if (!hasValidGraph) {
    return { nodes: [], edges: [] }
  }
  return normalizedPayload
}

async function fetchDetail(jobId: string) {
  detailLoading.value = true
  try {
    const response = await getJobDetail(jobId)
    const payload = extractPayload<JobDetailResult>(response)
    const normalizedDetail = normalizeJobDetailPayload(payload)
    selectedDetail.value = normalizedDetail
    const graphCategoryName = resolveGraphCategoryName(normalizedDetail)
    if (graphCategoryName) {
      selectedCategoryName.value = graphCategoryName
    }
    if (normalizedDetail?.jobId) jobDetailMap.value[normalizedDetail.jobId] = normalizedDetail
    await nextTick()
    renderRadar()
  } finally {
    detailLoading.value = false
  }
}

function resolveCurrentList() {
  return listViewMode.value === 'favorites' ? (favoriteJobsList.value as Array<Record<string, any>>) : (jobs.value as Array<Record<string, any>>)
}

function resolveCategoryNameForJobId(jobId: string) {
  const detailCategoryName = resolveGraphCategoryName(selectedDetail.value)
  if (selectedDetail.value?.jobId === jobId && detailCategoryName) return detailCategoryName

  const fromCurrentList = resolveCurrentList().find(item => String(item?.jobId || '') === jobId)
  const currentCategoryName = resolveGraphCategoryName(fromCurrentList)
  if (currentCategoryName) return currentCategoryName

  const fromJobsList = jobs.value.find(item => item.jobId === jobId)
  const jobsCategoryName = resolveGraphCategoryName(fromJobsList)
  if (jobsCategoryName) return jobsCategoryName

  const detail = jobDetailMap.value[jobId]
  return resolveGraphCategoryName(detail)
}

async function selectJob(jobId: string, options: { skipGraph?: boolean } = {}) {
  resetGraphRelationView()
  selectedJobId.value = jobId
  rightPanel.value = 'detail'
  await fetchDetail(jobId)
  const categoryName = resolveCategoryNameForJobId(jobId)
  selectedCategoryName.value = categoryName
  if (isDesktop.value && !options.skipGraph) {
    await fetchGraph(categoryName)
  }
}

async function refreshAll() {
  await Promise.all([fetchFilters(), fetchFavorites()])
  await fetchJobs(true)
}

async function syncListForMapSelectedJob(jobId: string) {
  listViewMode.value = 'all'

  const detail = selectedDetail.value
  const primaryKeyword = String(detail?.jobName || '').trim()
  const backupKeyword = String(detail?.categoryName || '').trim()
  const keywords = Array.from(new Set([primaryKeyword, backupKeyword].filter(Boolean)))

  const runKeywordSearch = async (keyword: string) => {
    Object.assign(query, {
      keyword,
      city: '',
      salaryMin: undefined,
      salaryMax: undefined,
      salaryTier: [],
      exp: [],
      edu: [],
      page: 1,
    })
    await fetchJobs(true, { disableAutoSelect: true, keepSelectionOnEmpty: true })
    return jobs.value.some(item => item.jobId === jobId)
  }

  if (!keywords.length) {
    await fetchJobs(true, { disableAutoSelect: true, keepSelectionOnEmpty: true })
    return
  }

  for (const keyword of keywords) {
    const hit = await runKeywordSearch(keyword)
    if (hit) return
  }

  if (query.keyword !== keywords[0]) {
    await runKeywordSearch(keywords[0])
  }

  ElMessage.info('图谱与详情已更新')
}

async function handleMapJobSelect(payload: { jobId: string }) {
  const jobId = String(payload?.jobId || '').trim()
  if (!jobId) return
  jobMapDialogVisible.value = false
  await selectJob(jobId)
  await syncListForMapSelectedJob(jobId)
}

async function toggleFavorite() {
  if (!selectedJobId.value) return
  favoriteLoading.value = true
  try {
    if (isSelectedFavorited.value) {
      await removeFavoriteJob(selectedJobId.value)
      ElMessage.success('已取消收藏')
    } else {
      await addFavoriteJob(selectedJobId.value)
      ElMessage.success('收藏成功')
    }
    await fetchFavorites()
  } finally {
    favoriteLoading.value = false
  }
}

async function toggleFavoriteByJob(jobId: string) {
  favoriteLoading.value = true
  try {
    if (favoriteJobIds.value.includes(jobId)) {
      await removeFavoriteJob(jobId)
      ElMessage.success('已取消收藏')
    } else {
      await addFavoriteJob(jobId)
      ElMessage.success('收藏成功')
    }
    await fetchFavorites()
  } finally {
    favoriteLoading.value = false
  }
}

function addSelectedJobToConversation() {
  const jobId = String(selectedDetail.value?.jobId || selectedJobId.value || '').trim()
  if (!jobId) return
  openGlobalAssistant({
    routePath: '/jobs',
    pageTitle: '岗位探索',
    contextPrompt: '请结合我添加的岗位信息进行分析。',
    pendingJob: {
      jobId,
      jobName: String(selectedDetail.value?.jobName || '当前岗位'),
    },
  })
}

function openCustomJobDialog() {
  Object.assign(customJobForm, createEmptyCustomJobForm())
  customJobDialogVisible.value = true
  nextTick(() => customJobFormRef.value?.clearValidate())
}

async function submitCustomJob() {
  if (!customJobFormRef.value || customJobSubmitting.value) return
  const valid = await customJobFormRef.value.validate().catch(() => false)
  if (!valid) return
  if (
    customJobForm.salaryMin != null
    && customJobForm.salaryMax != null
    && customJobForm.salaryMin > customJobForm.salaryMax
  ) {
    ElMessage.warning('最低薪资不能高于最高薪资')
    return
  }

  const salaryMin = customJobForm.salaryMin
  const salaryMax = customJobForm.salaryMax
  const avg = customJobForm.avg ?? (
    salaryMin != null && salaryMax != null
      ? Number(((salaryMin + salaryMax) / 2).toFixed(2))
      : salaryMin ?? salaryMax
  )
  customJobSubmitting.value = true
  try {
    const response = await createCustomFavoriteJob({
      ...customJobForm,
      avg,
      categories: normalizeTextList(customJobForm.categories),
      keywords: normalizeTextList(customJobForm.keywords),
    })
    const payload = response.data as ApiResponse<JobDetailResult>
    const created = normalizeJobDetailPayload(payload.payload ?? payload.data)
    if (payload.code < 200 || payload.code >= 300 || !created?.jobId) {
      throw new Error(payload.msg || '岗位新增失败')
    }
    customJobDialogVisible.value = false
    await fetchFavorites()
    selectedJobId.value = created.jobId
    selectedDetail.value = created
    selectedCategoryName.value = resolveGraphCategoryName(created)
    jobDetailMap.value[created.jobId] = created
    ElMessage.success('岗位已新增到我的收藏')
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '岗位新增失败')
  } finally {
    customJobSubmitting.value = false
  }
}

function resolveJobRowClass({ row }: { row: JobListItem }) {
  return row.jobId === selectedJobId.value ? 'job-row-selected' : ''
}

function displaySalary(row: {
  salaryText?: string | null
  salaryMin?: number | null
  salaryMax?: number | null
  salaryUnit?: string | null
} | null | undefined) {
  const text = String(row?.salaryText || '').trim()
  if (text && !/unknown/i.test(text)) return text
  // 个别来源没有整体薪资文本，用解析出的区间兜底
  const min = row?.salaryMin
  const max = row?.salaryMax
  if (min != null && max != null) {
    return `${min}-${max}${String(row?.salaryUnit || '').trim()}`
  }
  return '薪资待补充'
}

function educationTagText(payload: { edu?: string | null } | null | undefined) {
  const text = String(payload?.edu || '').trim()
  return text || '学历待补充'
}

function displayCompanyText(payload: { jobId: string; companyName?: string } | null | undefined) {
  const text = String(payload?.companyName || '').trim()
  return text || '公司待补充'
}

function parsePublishedDate(payload: any) {
  // 爬虫侧字段：lastSeen 是最近一次采集时间（ISO 8601 带时区）
  const lastSeen = String(payload?.lastSeen || '').trim()
  if (lastSeen) {
    const date = new Date(lastSeen)
    if (!Number.isNaN(date.getTime())) return date
  }
  const raw = String(payload?.updatedAtRaw || '').trim()
  // 爬虫写的 updatedAtRaw 是 ISO 日期（2026-09-09）
  const isoMatched = raw.match(/^(\d{4})-(\d{2})-(\d{2})$/)
  if (isoMatched) {
    const date = new Date(Number(isoMatched[1]), Number(isoMatched[2]) - 1, Number(isoMatched[3]))
    return Number.isNaN(date.getTime()) ? null : date
  }
  const matched = raw.match(/^(\d{1,2})月(\d{1,2})日$/)
  if (!matched) return null
  const month = Number(matched[1])
  const day = Number(matched[2])
  if (!month || !day) return null
  const now = new Date()
  let year = now.getFullYear()
  let date = new Date(year, month - 1, day)
  if (date.getTime() > now.getTime() + 24 * 60 * 60 * 1000) {
    year -= 1
    date = new Date(year, month - 1, day)
  }
  if (Number.isNaN(date.getTime())) return null
  return date
}

function publishDaysAgo(payload: any) {
  const date = parsePublishedDate(payload)
  if (!date) return null
  const now = new Date()
  const ms = now.getTime() - date.getTime()
  return ms < 0 ? 0 : Math.floor(ms / (24 * 60 * 60 * 1000))
}

function publishDateText(payload: any) {
  const date = parsePublishedDate(payload)
  if (!date) return '发布日期未知'
  const year = date.getFullYear()
  const month = String(date.getMonth() + 1).padStart(2, '0')
  const day = String(date.getDate()).padStart(2, '0')
  return `${year}年${month}月${day}日`
}

function publishTagType(payload: any) {
  const days = publishDaysAgo(payload)
  if (days === null) return 'info'
  if (days > 365) return 'danger'
  if (days > 180) return 'warning'
  if (days > 30) return 'info'
  return 'success'
}

function onLatestSortToggle(checked: boolean | string | number) {
  const enabled = Boolean(checked)
  latestPublishedFirst.value = enabled
  query.sortBy = enabled ? 'updatedAt' : undefined
  query.sortOrder = enabled ? 'desc' : undefined
  fetchJobs(true)
}

function toggleGraphRelationView(view: 'promotion' | 'transition') {
  graphRelationView.value = graphRelationView.value === view ? 'all' : view
}

function resetGraphRelationView() {
  if (graphRelationView.value !== 'all') {
    graphRelationView.value = 'all'
  }
}

function toggleGraphLegendVisible() {
  graphLegendVisible.value = !graphLegendVisible.value
}

function resetFilters() {
  Object.assign(query, {
    keyword: '',
    city: '',
    salaryMin: undefined,
    salaryMax: undefined,
    salaryTier: [],
    exp: [],
    edu: [],
    sortBy: undefined,
    sortOrder: undefined,
    page: 1,
    pageSize: 12,
  })
  latestPublishedFirst.value = false
  fetchJobs(true)
}

function normalizeKeywordText(value: string) {
  return String(value || '').replace(/\s+/g, '').toLowerCase()
}

async function applyAssistantJobRecommendation(payload: JobRecommendationApplyPayload | null | undefined) {
  const keyword = String(payload?.keyword || '').trim()
  if (!keyword) return

  listViewMode.value = 'all'
  Object.assign(query, {
    keyword,
    city: '',
    salaryMin: undefined,
    salaryMax: undefined,
    salaryTier: [],
    exp: [],
    edu: [],
    sortBy: undefined,
    sortOrder: undefined,
    page: 1,
  })
  latestPublishedFirst.value = false

  await fetchJobs(true, { allowEmptyRollback: false })

  if (!jobs.value.length) {
    ElMessage.warning(`未找到“${keyword}”相关岗位，请调整筛选条件后重试`)
    return
  }

  if (payload?.autoSelect === false) {
    ElMessage.success(`已按“${keyword}”完成筛选`)
    return
  }

  const recommendedJobId = String(payload?.recommendedJobId || '').trim()
  const normalizedKeyword = normalizeKeywordText(keyword)
  const keywordHit = jobs.value.find((item) => normalizeKeywordText(item.jobName).includes(normalizedKeyword))
  const exactById = recommendedJobId ? jobs.value.find(item => item.jobId === recommendedJobId) : null
  const target = exactById || keywordHit || jobs.value[0]
  if (!target) {
    ElMessage.warning(`“${keyword}”筛选成功，但未找到可自动选择的岗位`)
    return
  }

  await selectJob(target.jobId)
  ElMessage.success(`已自动选择推荐岗位：${target.jobName}`)
}

async function applyGraphNodeSearchAndSelection(keyword: string) {
  const normalizedKeyword = String(keyword || '').trim()
  if (!normalizedKeyword) return

  listViewMode.value = 'all'
  Object.assign(query, {
    keyword: normalizedKeyword,
    city: '',
    salaryMin: undefined,
    salaryMax: undefined,
    salaryTier: [],
    exp: [],
    edu: [],
    sortBy: undefined,
    sortOrder: undefined,
    page: 1,
  })
  latestPublishedFirst.value = false

  await fetchJobs(true, { disableAutoSelect: true })

  if (!jobs.value.length) {
    ElMessage.warning(`未找到“${normalizedKeyword}”相关岗位，请调整筛选条件后重试`)
    return
  }

  await selectJob(jobs.value[0].jobId, { skipGraph: true })
}

async function handleGraphNodeClick(nodeId: string, nodeItem: any) {
  resetGraphRelationView()
  const [transitionResult, searchResult] = await Promise.allSettled([
    transitionGraphFromNodeClick(nodeId, nodeItem),
    applyGraphNodeSearchAndSelection(nodeId),
  ])

  if (transitionResult.status === 'rejected') {
    throw transitionResult.reason
  }

  if (searchResult.status === 'rejected') {
    console.warn('Graph node click search sync failed:', searchResult.reason)
  }
}

function handleJobRecommendationApplyEvent(event: Event) {
  const customEvent = event as CustomEvent<JobRecommendationApplyPayload>
  applyAssistantJobRecommendation(customEvent.detail)
    .catch(() => {
      ElMessage.warning('推荐岗位应用失败，请手动筛选重试')
    })
}

function detailMetaLine(detail: JobDetailResult | null) {
  if (!detail) return '-'
  const updated = publishDateText(detail)
  return `发布日期：${updated}`
}

function resolveSourceHref(detail: JobDetailResult | null) {
  const href = String(detail?.sourceUrl || '').trim()
  if (!href) return ''
  if (/^https?:\/\//i.test(href)) return href
  return ''
}

function resolveSourceSiteText(detail: JobDetailResult | null) {
  const sourceSite = String(detail?.sourceSite || '').trim()
  if (sourceSite) return sourceSite
  const href = resolveSourceHref(detail)
  if (!href) return '未知来源'
  try {
    const hostname = new URL(href).hostname.replace(/^www\./i, '').trim()
    return hostname || '未知来源'
  } catch {
    return '未知来源'
  }
}

function resetDetailTextExpandedState() {
  jobDescriptionExpanded.value = false
  keySkillsExpanded.value = false
}

function salaryMetaLine(detail: JobDetailResult | null) {
  if (!detail) return '-'
  const text = String(detail.salaryText || '').trim()
  if (text && !/unknown/i.test(text)) {
    const avg = detail.avg
    // avg 是爬虫按薪资区间+年终月数折算出的月均值，直接展示让区间含义更明确
    return avg != null && avg > 0 ? `薪资：${text}（月均约 ${avg}K）` : `薪资：${text}`
  }
  return '薪资信息待补充'
}

function ensureGraphReady() {
  if (!isDesktop.value || !graphRef.value || !graphData.value) return false
  const width = graphRef.value.clientWidth
  const height = graphRef.value.clientHeight
  if (!width || !height || width < 120 || height < 120) return false
  return true
}

function scheduleRenderGraph(retry = 8) {
  if (!graphViewAlive) return
  if (renderRetryTimer) {
    clearTimeout(renderRetryTimer)
    renderRetryTimer = null
  }

  if (ensureGraphReady()) {
    renderGraph()
    return
  }

  if (retry <= 0) return
  renderRetryTimer = setTimeout(() => {
    if (!graphViewAlive) return
    scheduleRenderGraph(retry - 1)
  }, 120)
}

function teardownG6Graph() {
  if (!g6Graph) return
  try {
    if (typeof g6Graph.stopLayout === 'function') {
      g6Graph.stopLayout()
    }
  } catch {
    // ignore layout stop errors during teardown
  }
  try {
    g6Graph.destroy()
  } catch {
    // ignore destroy errors during teardown
  }
  g6Graph = null
  g6Rendered = false
}

function hideGraphHoverTip() {
  graphHoverTip.visible = false
  graphHoverTip.jobId = ''
  graphHoverTip.title = ''
  graphHoverTip.lines = []
  graphHoverTip.tagText = ''
  graphHoverTip.tagTone = ''
}

function updateHoverTipPosition(canvasX: number, canvasY: number, offset = 18) {
  if (!graphRef.value) {
    graphHoverTip.x = canvasX + offset
    graphHoverTip.y = canvasY + offset
    return
  }
  const width = graphRef.value.clientWidth
  const height = graphRef.value.clientHeight
  const tipWidth = 260
  const tipHeight = 150
  const maxX = Math.max(8, width - tipWidth - 8)
  const maxY = Math.max(8, height - tipHeight - 8)
  graphHoverTip.x = Math.min(Math.max(8, canvasX + offset), maxX)
  graphHoverTip.y = Math.min(Math.max(8, canvasY + offset), maxY)
}

function restoreGraphVisibility() {
  if (!g6Graph) return
  g6Graph.getNodes().forEach((node: any) => {
    g6Graph.updateItem(node, {
      style: { opacity: 1 },
      labelCfg: { style: { opacity: 1 } },
    })
  })
  g6Graph.getEdges().forEach((edge: any) => {
    const model = edge.getModel?.() || {}
    const relationType = normalizeRelationType(String(model.relationType || 'transition'))
    g6Graph.updateItem(edge, {
      style: { opacity: getEdgeBaseOpacity(relationType) },
      labelCfg: { style: { opacity: getEdgeLabelOpacity(relationType) } },
    })
  })
}

function applyGraphNodeNeighborhoodFocus(nodeId: string) {
  if (!g6Graph || !nodeId) return
  const neighborhood = new Set<string>([nodeId])

  g6Graph.getEdges().forEach((edge: any) => {
    const model = edge.getModel?.() || {}
    const sourceId = String(model.source || '')
    const targetId = String(model.target || '')
    const isNeighborEdge = sourceId === nodeId || targetId === nodeId
    if (isNeighborEdge) {
      neighborhood.add(sourceId)
      neighborhood.add(targetId)
    }

    g6Graph.updateItem(edge, {
      style: {
        opacity: isNeighborEdge ? 1 : 0.2,
      },
      labelCfg: {
        style: {
          opacity: isNeighborEdge ? 1 : 0.2,
        },
      },
    })
  })

  g6Graph.getNodes().forEach((node: any) => {
    const model = node.getModel?.() || {}
    const id = String(model.id || '')
    const isNeighborNode = neighborhood.has(id)

    g6Graph.updateItem(node, {
      style: {
        opacity: isNeighborNode ? 1 : 0.2,
      },
      labelCfg: {
        style: {
          opacity: isNeighborNode ? 1 : 0.2,
        },
      },
    })
  })
}

function applyGraphEdgeFocus(sourceId: string, targetId: string) {
  if (!g6Graph || !sourceId || !targetId) return
  const neighborhood = new Set<string>([sourceId, targetId])

  g6Graph.getEdges().forEach((edge: any) => {
    const model = edge.getModel?.() || {}
    const source = String(model.source || '')
    const target = String(model.target || '')
    const isBidirectional = Boolean(model.isBidirectional)
    const isTargetEdge = (source === sourceId && target === targetId)
      || (isBidirectional && source === targetId && target === sourceId)

    g6Graph.updateItem(edge, {
      style: {
        opacity: isTargetEdge ? 1 : 0.2,
      },
      labelCfg: {
        style: {
          opacity: isTargetEdge ? 1 : 0.2,
        },
      },
    })
  })

  g6Graph.getNodes().forEach((node: any) => {
    const model = node.getModel?.() || {}
    const id = String(model.id || '')
    const isNeighborNode = neighborhood.has(id)

    g6Graph.updateItem(node, {
      style: {
        opacity: isNeighborNode ? 1 : 0.2,
      },
      labelCfg: {
        style: {
          opacity: isNeighborNode ? 1 : 0.2,
        },
      },
    })
  })
}

function clearGraphTransitionTimers() {
  graphTransitionTimers.forEach(timer => clearTimeout(timer))
  graphTransitionTimers = []
}

function resolveGraphSeedTargetPosition(graph: JobGraphResult | null, seedId: string): { x: number; y: number } | null {
  if (!graphRef.value || !seedId) return null

  const width = graphRef.value.clientWidth
  const height = graphRef.value.clientHeight
  if (!width || !height) return null

  const normalizedGraph = buildNormalizedGraphModel(graph)
  const normalizedNodes = normalizedGraph.nodes
  const normalizedEdges = normalizedGraph.edges
  if (!normalizedNodes.length) {
    return {
      x: Math.round(width / 2),
      y: getGraphVisualCenterY(height),
    }
  }

  const visibleEdges = normalizedEdges.filter(edge => isEdgeVisibleInCurrentView(edge.relationType))
  const visibleNodeIds = new Set<string>([seedId])
  visibleEdges.forEach((edge) => {
    visibleNodeIds.add(edge.source)
    visibleNodeIds.add(edge.target)
  })
  if (!visibleNodeIds.size && normalizedNodes.length) {
    visibleNodeIds.add(normalizedNodes[0].id)
  }

  const visibleNodes = normalizedNodes.filter(node => visibleNodeIds.has(node.id))
  const isTwoNodeGraph = visibleNodes.length === 2
  const manualNodePosition = buildFocusLayeredPositionMap(
    visibleNodes,
    visibleEdges.map(edge => ({
      source: edge.source,
      target: edge.target,
      relationType: normalizeRelationType(edge.relationType),
    })),
    seedId,
    width,
    height,
  )

  if (!manualNodePosition.size && isTwoNodeGraph) {
    const otherNode = visibleNodes.find(node => node.id !== seedId)
    const horizontalGap = Math.max(240, Math.min(360, Math.round(width * 0.32)))
    const centerX = Math.round(width / 2)
    const centerY = getGraphVisualCenterY(height)

    manualNodePosition.set(seedId, {
      x: centerX - Math.round(horizontalGap / 2),
      y: centerY,
      fx: centerX - Math.round(horizontalGap / 2),
      fy: centerY,
    })

    if (otherNode) {
      manualNodePosition.set(otherNode.id, {
        x: centerX + Math.round(horizontalGap / 2),
        y: centerY,
        fx: centerX + Math.round(horizontalGap / 2),
        fy: centerY,
      })
    }
  }

  const seedPosition = manualNodePosition.get(seedId)
  if (seedPosition) {
    return { x: seedPosition.x, y: seedPosition.y }
  }

  return {
    x: Math.round(width / 2),
    y: getGraphVisualCenterY(height),
  }
}

function moveGraphNodeToPosition(nodeItem: any, targetPosition?: { x: number; y: number } | null) {
  if (!g6Graph || !graphRef.value || !nodeItem) return Promise.resolve()

  const centerX = Math.round(graphRef.value.clientWidth / 2)
  const centerY = getGraphVisualCenterY(graphRef.value.clientHeight)
  const targetX = Number(targetPosition?.x ?? centerX)
  const targetY = Number(targetPosition?.y ?? centerY)
  const model = nodeItem.getModel?.() || {}
  const currentX = Number(model.x || centerX)
  const currentY = Number(model.y || centerY)

  const distance = Math.hypot(targetX - currentX, targetY - currentY)
  if (!Number.isFinite(distance) || distance < 1) {
    try {
      g6Graph.updateItem(nodeItem, {
        x: targetX,
        y: targetY,
        fx: targetX,
        fy: targetY,
      })
    } catch {
      // Ignore update fallback errors.
    }
    return Promise.resolve()
  }

  const duration = 320
  const easeInOutCubic = (t: number) => (t < 0.5 ? 4 * t * t * t : 1 - Math.pow(-2 * t + 2, 3) / 2)

  return new Promise<void>((resolve) => {
    const startedAt = typeof performance !== 'undefined' ? performance.now() : Date.now()

    const tick = () => {
      if (!graphViewAlive || !g6Graph) {
        resolve()
        return
      }

      const now = typeof performance !== 'undefined' ? performance.now() : Date.now()
      const progress = Math.min(1, Math.max(0, (now - startedAt) / duration))
      const eased = easeInOutCubic(progress)
      const x = currentX + (targetX - currentX) * eased
      const y = currentY + (targetY - currentY) * eased

      try {
        g6Graph.updateItem(nodeItem, {
          x,
          y,
          fx: x,
          fy: y,
        })
      } catch {
        resolve()
        return
      }

      if (progress >= 1) {
        resolve()
        return
      }

      requestAnimationFrame(tick)
    }

    requestAnimationFrame(tick)
  })
}

async function fadeOutGraphExceptSeed(seedId: string) {
  if (!g6Graph || !seedId) return

  const animationTargets: Array<{
    item: any
    from: number
    to: number
    apply: (item: any, opacity: number, progress: number) => void
  }> = []

  g6Graph.getNodes().forEach((node: any) => {
    const model = node.getModel?.() || {}
    const nodeId = String(model.id || '')
    const isSeed = nodeId === seedId
    if (isSeed) {
      g6Graph.updateItem(node, {
        style: { opacity: 1 },
        labelCfg: { style: { opacity: 1 } },
      })
      return
    }

    const from = resolveModelOpacity(model, 1)
    animationTargets.push({
      item: node,
      from,
      to: 0,
      apply: (item, opacity) => {
        g6Graph.updateItem(item, {
          style: { opacity },
          labelCfg: { style: { opacity } },
        })
      },
    })
  })

  g6Graph.getEdges().forEach((edge: any) => {
    const model = edge.getModel?.() || {}
    const relationType = normalizeRelationType(String(model.relationType || 'transition'))
    const fromLine = resolveModelOpacity(model, getEdgeBaseOpacity(relationType))
    const fromLabel = Number(model?.labelCfg?.style?.opacity)
    animationTargets.push({
      item: edge,
      from: fromLine,
      to: 0,
      apply: (item, opacity, progress) => {
        const labelOpacity = Number.isFinite(fromLabel) ? Math.max(0, fromLabel * (1 - progress)) : opacity
        g6Graph.updateItem(item, {
          style: { opacity },
          labelCfg: { style: { opacity: labelOpacity } },
        })
      },
    })
  })

  await animateGraphOpacityBatch(animationTargets, 180)
}

async function revealGraphFromSeed(seedId: string) {
  if (!g6Graph || !seedId) return

  const neighbors = new Set<string>([seedId])
  const nearTargets: Array<{
    item: any
    from: number
    to: number
    apply: (item: any, opacity: number, progress: number) => void
  }> = []
  const farTargets: Array<{
    item: any
    from: number
    to: number
    apply: (item: any, opacity: number, progress: number) => void
  }> = []

  g6Graph.getEdges().forEach((edge: any) => {
    const model = edge.getModel?.() || {}
    const sourceId = String(model.source || '')
    const targetId = String(model.target || '')
    const relationType = normalizeRelationType(String(model.relationType || 'transition'))
    const targetLineOpacity = getEdgeBaseOpacity(relationType)
    const targetLabelOpacity = getEdgeLabelOpacity(relationType)
    const isNeighborEdge = sourceId === seedId || targetId === seedId

    if (sourceId === seedId || targetId === seedId) {
      neighbors.add(sourceId)
      neighbors.add(targetId)
    }

    g6Graph.updateItem(edge, {
      style: { opacity: 0 },
      labelCfg: { style: { opacity: 0 } },
    })

    const targetList = isNeighborEdge ? nearTargets : farTargets
    targetList.push({
      item: edge,
      from: 0,
      to: targetLineOpacity,
      apply: (item, opacity, progress) => {
        g6Graph.updateItem(item, {
          style: { opacity },
          labelCfg: {
            style: {
              opacity: targetLabelOpacity * progress,
            },
          },
        })
      },
    })
  })

  g6Graph.getNodes().forEach((node: any) => {
    const model = node.getModel?.() || {}
    const nodeId = String(model.id || '')
    const isSeed = nodeId === seedId
    g6Graph.updateItem(node, {
      style: {
        opacity: isSeed ? 1 : 0,
      },
      labelCfg: {
        style: {
          opacity: isSeed ? 1 : 0,
        },
      },
    })

    if (!isSeed) {
      const targetList = neighbors.has(nodeId) ? nearTargets : farTargets
      targetList.push({
        item: node,
        from: 0,
        to: 1,
        apply: (item, opacity) => {
          g6Graph.updateItem(item, {
            style: { opacity },
            labelCfg: { style: { opacity } },
          })
        },
      })
    }
  })

  await animateGraphOpacityBatch(nearTargets, 180)
  await animateGraphOpacityBatch(farTargets, 220)

  if (!g6Graph || !graphViewAlive) return
  restoreGraphVisibility()
}

async function transitionGraphFromNodeClick(seedId: string, nodeItem: any) {
  if (!isDesktop.value || !seedId) return
  if (graphTransitioning.value) return

  graphTransitioning.value = true
  clearGraphTransitionTimers()
  selectedCategoryName.value = seedId

  graphLoading.value = true
  let nextGraph: JobGraphResult = { nodes: [], edges: [] }
  try {
    nextGraph = await requestGraphPayloadByCategoryName(seedId)
  } finally {
    graphLoading.value = false
  }

  const targetPosition = resolveGraphSeedTargetPosition(nextGraph, seedId)

  try {
    if (g6Graph) {
      const selectedNode = g6Graph.findById(seedId)
      const movingNode = selectedNode || nodeItem
      await moveGraphNodeToPosition(movingNode, targetPosition)
      await fadeOutGraphExceptSeed(seedId)
    }

    graphData.value = nextGraph
    graphNeedsReinit = !g6Graph
    await nextTick()
    scheduleRenderGraph()
    await nextTick()
    await revealGraphFromSeed(seedId)
  } finally {
    const doneTimer = setTimeout(() => {
      graphTransitioning.value = false
    }, 120)
    graphTransitionTimers.push(doneTimer)
  }
}

function buildOverviewRingPositionMap(
  nodes: Array<{ id: string; level: string }>,
  width: number,
  height: number,
) {
  const positionMap = new Map<string, { x: number; y: number; fx?: number; fy?: number }>()
  if (!nodes.length) return positionMap

  const centerX = Math.round(width / 2)
  const centerY = getGraphVisualCenterY(height)
  const xMin = 64
  const xMax = Math.max(xMin + 24, width - 64)
  const yMin = 72
  const yMax = Math.max(yMin + 24, height - 72)
  const clamp = (value: number, min: number, max: number) => Math.min(Math.max(value, min), max)

  const levelOrder = ['junior', 'middle', 'senior', 'lead', 'other']
  const levelBuckets = new Map<string, Array<{ id: string; level: string }>>()
  nodes.forEach((node) => {
    const normalized = normalizeJobLevel(node.level)
    const key = levelOrder.includes(normalized) ? normalized : 'other'
    const list = levelBuckets.get(key) || []
    list.push(node)
    levelBuckets.set(key, list)
  })

  const orderedGroups = levelOrder
    .map(key => levelBuckets.get(key) || [])
    .filter(group => group.length > 0)

  const minCanvas = Math.min(width, height)
  const maxRadius = Math.max(110, Math.round(minCanvas * 0.44))
  const startRadius = Math.max(72, Math.min(120, Math.round(minCanvas * 0.18)))
  const ringGap = Math.max(68, Math.min(102, Math.round(minCanvas * 0.16)))
  const minArcDistance = 84
  let ringIndex = 0

  orderedGroups.forEach((group) => {
    let cursor = 0
    while (cursor < group.length) {
      const radius = Math.min(maxRadius, startRadius + ringIndex * ringGap)
      const capacity = Math.max(4, Math.floor((2 * Math.PI * radius) / minArcDistance))
      const slice = group.slice(cursor, cursor + capacity)
      const angleOffset = (ringIndex % 2) * 0.42

      slice.forEach((node, index) => {
        const angle = angleOffset + (2 * Math.PI * index) / slice.length
        const x = clamp(Math.round(centerX + Math.cos(angle) * radius), xMin, xMax)
        const y = clamp(Math.round(centerY + Math.sin(angle) * radius), yMin, yMax)
        positionMap.set(node.id, { x, y, fx: x, fy: y })
      })

      cursor += slice.length
      ringIndex += 1
    }
  })

  return positionMap
}

function buildFocusLayeredPositionMap(
  nodes: Array<{ id: string }>,
  edges: Array<{ source: string; target: string; relationType: string }>,
  seedId: string,
  width: number,
  height: number,
) {
  const positionMap = new Map<string, { x: number; y: number; fx?: number; fy?: number }>()
  if (!seedId) return positionMap

  const nodeIdSet = new Set(nodes.map(node => node.id))
  if (!nodeIdSet.has(seedId)) return positionMap

  const promotionBySource = new Map<string, Array<{ source: string; target: string }>>()
  const transitionBySource = new Map<string, Array<{ source: string; target: string }>>()

  edges.forEach((edge) => {
    if (!nodeIdSet.has(edge.source) || !nodeIdSet.has(edge.target)) return
    const relationType = normalizeRelationType(edge.relationType)
    if (relationType === 'promotion') {
      const list = promotionBySource.get(edge.source) || []
      list.push({ source: edge.source, target: edge.target })
      promotionBySource.set(edge.source, list)
      return
    }
    const list = transitionBySource.get(edge.source) || []
    list.push({ source: edge.source, target: edge.target })
    transitionBySource.set(edge.source, list)
  })

  const promotionChain = [seedId]
  let currentId = seedId
  let guard = 0
  while (guard < 8) {
    const next = (promotionBySource.get(currentId) || []).find(item => !promotionChain.includes(item.target))
    if (!next) break
    promotionChain.push(next.target)
    currentId = next.target
    guard += 1
  }

  const occupiedPoints: Array<{ x: number; y: number }> = []
  const baseMinDistance = Math.max(72, Math.min(120, Math.round(Math.min(width, height) * 0.2)))
  const xMin = 72
  const xMax = Math.max(xMin + 40, width - 72)
  const yMin = 88
  const yMax = Math.max(yMin + 40, height - 88)

  const clamp = (value: number, min: number, max: number) => Math.min(Math.max(value, min), max)

  const isConflict = (x: number, y: number, minDistance: number) => occupiedPoints.some((point) => {
    const dx = point.x - x
    const dy = point.y - y
    return (dx * dx + dy * dy) < minDistance * minDistance
  })

  const buildCandidatePoints = (baseX: number, baseY: number) => {
    const candidates: Array<{ x: number; y: number }> = [{ x: baseX, y: baseY }]
    const angleList = [0, 45, 90, 135, 180, 225, 270, 315]

    for (let ring = 1; ring <= 8; ring += 1) {
      const radiusX = ring * 44
      const radiusY = ring * 28
      angleList.forEach((angleDeg) => {
        const angle = (angleDeg * Math.PI) / 180
        const x = clamp(Math.round(baseX + Math.cos(angle) * radiusX), xMin, xMax)
        const y = clamp(Math.round(baseY + Math.sin(angle) * radiusY), yMin, yMax)
        candidates.push({ x, y })
      })
    }

    return candidates
  }

  const allocateLockedPoint = (rawX: number, rawY: number) => {
    const baseX = clamp(Math.round(rawX), xMin, xMax)
    const baseY = clamp(Math.round(rawY), yMin, yMax)

    const candidatePoints = buildCandidatePoints(baseX, baseY)
    const minDistanceAttempts = [
      baseMinDistance,
      Math.max(64, Math.round(baseMinDistance * 0.9)),
      Math.max(56, Math.round(baseMinDistance * 0.82)),
      52,
    ]

    for (const attemptDistance of minDistanceAttempts) {
      for (const candidate of candidatePoints) {
        if (!isConflict(candidate.x, candidate.y, attemptDistance)) {
          occupiedPoints.push({ x: candidate.x, y: candidate.y })
          return { x: candidate.x, y: candidate.y, fx: candidate.x, fy: candidate.y }
        }
      }
    }

    // Last-resort fallback: avoid exact coordinate duplication even in very narrow canvases.
    let fallbackX = baseX
    let fallbackY = baseY
    let guard = 0
    while (occupiedPoints.some(point => point.x === fallbackX && point.y === fallbackY) && guard < 40) {
      guard += 1
      const direction = guard % 2 === 0 ? 1 : -1
      const lane = Math.ceil(guard / 2)
      fallbackX = clamp(baseX + direction * lane * 8, xMin, xMax)
      fallbackY = clamp(baseY + direction * lane * 6, yMin, yMax)
    }

    occupiedPoints.push({ x: fallbackX, y: fallbackY })
    return { x: fallbackX, y: fallbackY, fx: fallbackX, fy: fallbackY }
  }

  const centerX = Math.round(width / 2)
  const verticalGap = Math.max(140, Math.min(200, Math.round(height / Math.max(4, promotionChain.length + 1))))
  const centerY = getGraphVisualCenterY(height)
  const startY = Math.max(88, Math.round(centerY - ((promotionChain.length - 1) * verticalGap) / 2))
  promotionChain.forEach((jobId, index) => {
    const y = startY + index * verticalGap
    positionMap.set(jobId, allocateLockedPoint(centerX, y))
  })

  const horizontalGap = Math.max(170, Math.min(260, Math.round(width * 0.2)))
  const branchYOffset = Math.max(44, Math.min(88, Math.round(verticalGap * 0.42)))
  promotionChain.forEach((sourceId, sourceIndex) => {
    const baseY = startY + sourceIndex * verticalGap
    const transitionTargets = (transitionBySource.get(sourceId) || [])
      .map(item => item.target)
      .filter(targetId => !promotionChain.includes(targetId))

    transitionTargets.forEach((targetId, index) => {
      if (positionMap.has(targetId)) return
      const side = index % 2 === 0 ? -1 : 1
      const lane = Math.floor(index / 2) + 1
      const x = centerX + side * horizontalGap * lane
      const y = baseY + (index === 2 ? branchYOffset : (index === 0 ? -Math.round(branchYOffset * 0.24) : Math.round(branchYOffset * 0.24)))
      positionMap.set(targetId, allocateLockedPoint(x, y))
    })
  })

  const fallbackNodeIds = nodes.map(node => node.id).filter(id => !positionMap.has(id))
  fallbackNodeIds.forEach((jobId, index) => {
    const side = index % 2 === 0 ? -1 : 1
    const lane = Math.floor(index / 2) + 1
    const x = centerX + side * horizontalGap * lane
    const y = Math.round(height * 0.78) + (index % 3) * 22
    positionMap.set(jobId, allocateLockedPoint(x, y))
  })

  return positionMap
}

function clearGraphCenterTimers() {
  graphCenterTimers.forEach(timer => clearTimeout(timer))
  graphCenterTimers = []
}

function keepGraphCentered() {
  if (!graphViewAlive || !g6Graph || !graphRef.value) return
  const width = graphRef.value.clientWidth
  const height = graphRef.value.clientHeight
  if (!width || !height) return

  const recenter = () => {
    if (!graphViewAlive || !g6Graph || !graphRef.value) return
    const w = graphRef.value.clientWidth
    const h = graphRef.value.clientHeight
    if (!w || !h) return
    suppressGraphResizeObserver = true
    g6Graph.changeSize(w, h)
    g6Graph.fitView(24)
    if (g6Graph.fitCenter) g6Graph.fitCenter()
    setTimeout(() => {
      suppressGraphResizeObserver = false
    }, 0)
  }

  clearGraphCenterTimers()
  recenter()
  graphCenterTimers.push(setTimeout(recenter, 90))
}

function buildNormalizedGraphModel(graph: JobGraphResult | null) {
  const nodes = (graph?.nodes || [])
    .map((node) => {
      const jobName = resolveUnifiedJobName(node)
      if (!jobName) return null
      return {
        id: jobName,
        jobName,
        level: resolveGraphNodeLevel(jobName),
        industryTags: Array.isArray(node?.industryTags) ? node.industryTags : [],
        jobDescription: String(node?.jobDescription || '').trim(),
      }
    })
    .filter((item): item is { id: string; jobName: string; level: 'junior' | 'middle' | 'senior' | 'lead'; industryTags: string[]; jobDescription: string } => Boolean(item))

  const nodeIdSet = new Set(nodes.map(item => item.id))
  const edges = (graph?.edges || [])
    .map((edge) => ({
      source: String(edge?.source || '').trim(),
      target: String(edge?.target || '').trim(),
      relationType: normalizeRelationType(String(edge?.relationType || 'transition')),
      similarity: Number(edge?.similarity || 0),
      difficulty: String(edge?.difficulty || 'medium'),
      reason: String(edge?.reason || '岗位关系'),
    }))
    .filter((edge) => edge.source && edge.target && nodeIdSet.has(edge.source) && nodeIdSet.has(edge.target))

  return { nodes, edges }
}

function renderGraph() {
  if (!graphViewAlive) return
  if (!ensureGraphReady()) return
  const width = graphRef.value!.clientWidth
  const height = graphRef.value!.clientHeight
  const normalizedGraph = buildNormalizedGraphModel(graphData.value)
  const normalizedNodes = normalizedGraph.nodes
  const normalizedEdges = normalizedGraph.edges
  const usePresetLayout = true

  if (graphNeedsReinit && g6Graph) {
    hideGraphHoverTip()
    teardownG6Graph()
    graphNeedsReinit = false
  }

  if (!g6Graph) {
    try {
      g6Graph = new G6.Graph({
        container: graphRef.value!,
        width,
        height,
        fitView: true,
        fitViewPadding: 20,
        modes: {
          default: ['drag-canvas', 'zoom-canvas', 'drag-node'],
        },
        layout: usePresetLayout
          ? {
              type: 'preset',
            }
          : {
              type: 'force',
              center: [width / 2, getGraphVisualCenterY(height)],
              preventOverlap: true,
              nodeSize: 42,
              nodeSpacing: 18,
              nodeStrength: -280,
              edgeStrength: 0.15,
              linkDistance: 200,
            },
        defaultNode: {
          type: 'circle',
          size: 38,
          labelCfg: {
            style: {
              fill: '#ffffff',
              fontSize: 12,
              fontWeight: 700,
              stroke: '#1e293b',
              lineWidth: 2,
            },
          },
          style: {
            lineWidth: 1,
            stroke: '#0f172a',
            fill: '#e2e8f0',
            shadowColor: 'transparent',
            shadowBlur: 0,
          },
        },
        defaultEdge: {
          type: 'line',
          style: {
            lineWidth: 2,
            lineAppendWidth: 14,
            endArrow: true,
            opacity: 0.92,
          },
        },
        nodeStateStyles: {
          hover: {
            lineWidth: 3,
            shadowBlur: 14,
          },
          selected: {
            lineWidth: 4,
            shadowBlur: 20,
          },
          active: {
            lineWidth: 4,
            shadowBlur: 24,
          },
        },
        edgeStateStyles: {
          hover: {
            lineWidth: 3,
            opacity: 1,
          },
        },
      })
      graphInitError.value = ''
    } catch (error) {
      graphInitError.value = '图谱初始化失败，请刷新页面重试'
      console.error('G6 init failed:', error)
      return
    }

    g6Graph.on('node:mouseenter', (evt: any) => {
      if (!evt.item) return
      g6Graph.setItemState(evt.item, 'hover', true)
      const model = evt.item.getModel?.() || {}
      const nodeId = String(model.id || '')
      applyGraphNodeNeighborhoodFocus(nodeId)
      const liveGraph = buildNormalizedGraphModel(graphData.value)
      const graphNode = liveGraph.nodes.find(item => item.id === nodeId)
      const hoverLevel = String(model.visualLevel || model.level || graphNode?.level || 'middle')
      const levelMeta = getLevelTagMeta(hoverLevel)
      const industryTags = Array.isArray(model.industryTags) && model.industryTags.length
        ? model.industryTags
        : (graphNode?.industryTags || [])
      const jobDescription = String(model.jobDescription || graphNode?.jobDescription || '暂无岗位职责描述').trim() || '暂无岗位职责描述'
      graphHoverTip.visible = true
      updateHoverTipPosition(evt.canvasX || 0, evt.canvasY || 0, 18)
      graphHoverTip.jobId = nodeId
      graphHoverTip.title = String(model.jobName || graphNode?.jobName || nodeId)
      graphHoverTip.tagText = `级别：${levelMeta.text}`
      graphHoverTip.tagTone = levelMeta.tone
      graphHoverTip.lines = [
        `行业：${industryTags.join('、') || '-'}`,
        `职责：${jobDescription}`,
      ]
    })
    g6Graph.on('node:mousemove', (evt: any) => {
      if (!graphHoverTip.visible) return
      updateHoverTipPosition(evt.canvasX || 0, evt.canvasY || 0, 18)
    })
    g6Graph.on('node:mouseleave', (evt: any) => {
      if (evt.item) {
        g6Graph.setItemState(evt.item, 'hover', false)
        g6Graph.setItemState(evt.item, 'active', false)
      }
      restoreGraphVisibility()
      hideGraphHoverTip()
    })
    g6Graph.on('node:mousedown', (evt: any) => {
      if (evt.item) g6Graph.setItemState(evt.item, 'active', true)
    })
    g6Graph.on('node:mouseup', (evt: any) => {
      if (evt.item) g6Graph.setItemState(evt.item, 'active', false)
    })
    g6Graph.on('edge:mouseenter', (evt: any) => {
      if (!evt.item) return
      g6Graph.setItemState(evt.item, 'hover', true)
      const model = evt.item.getModel?.() || {}
      const sourceId = String(model.source || '')
      const targetId = String(model.target || '')
      applyGraphEdgeFocus(sourceId, targetId)
      graphHoverTip.visible = true
      updateHoverTipPosition(evt.canvasX || 0, evt.canvasY || 0, 12)
      const reasonText = String(model.reason || '岗位关系')
      const reasonLines = reasonText.split('；').map((line: string) => line.trim()).filter(Boolean)
      const similarityMeta = getSimilarityTagMeta(Number(model.similarity || 0), String(model.relationType || 'transition'))
      graphHoverTip.title = '关系说明'
      graphHoverTip.tagText = similarityMeta.text
      graphHoverTip.tagTone = similarityMeta.tone
      graphHoverTip.lines = reasonLines.length ? reasonLines : [reasonText]
    })
    g6Graph.on('edge:mousemove', (evt: any) => {
      if (!graphHoverTip.visible) return
      updateHoverTipPosition(evt.canvasX || 0, evt.canvasY || 0, 12)
    })
    g6Graph.on('edge:mouseleave', (evt: any) => {
      if (evt.item) g6Graph.setItemState(evt.item, 'hover', false)
      restoreGraphVisibility()
      hideGraphHoverTip()
    })
    g6Graph.on('canvas:drag', hideGraphHoverTip)
    g6Graph.on('canvas:click', hideGraphHoverTip)
    g6Graph.on('node:click', (evt: any) => {
      const id = evt.item?.getID?.()
      if (!id) return
      const nodeId = String(id)
      handleGraphNodeClick(nodeId, evt.item).catch(() => {
        graphTransitioning.value = false
        ElMessage.warning('图谱刷新失败，请稍后重试')
      })
    })
  }

  const seedId = selectedCategoryName.value || normalizedNodes[0]?.id || ''
  const visibleEdges = normalizedEdges.filter(edge => isEdgeVisibleInCurrentView(edge.relationType))
  const displayEdges = buildDisplayGraphEdges(visibleEdges as NormalizedGraphEdge[])
  const visibleNodeIds = new Set<string>()
  if (seedId) visibleNodeIds.add(seedId)
  visibleEdges.forEach((edge) => {
    visibleNodeIds.add(edge.source)
    visibleNodeIds.add(edge.target)
  })
  if (!visibleNodeIds.size && normalizedNodes.length) {
    visibleNodeIds.add(normalizedNodes[0].id)
  }
  const visibleNodes = normalizedNodes.filter(node => visibleNodeIds.has(node.id))
  const isTwoNodeGraph = visibleNodes.length === 2
  const manualNodePosition = seedId
    ? buildFocusLayeredPositionMap(
        visibleNodes,
        visibleEdges.map(edge => ({
          source: edge.source,
          target: edge.target,
          relationType: normalizeRelationType(edge.relationType),
        })),
        seedId,
        width,
        height,
      )
    : normalizedNodes.length
      ? buildOverviewRingPositionMap(
          visibleNodes.map(node => ({ id: node.id, level: node.level })),
          width,
          height,
        )
      : new Map<string, { x: number; y: number; fx?: number; fy?: number }>()

  if (!manualNodePosition.size && isTwoNodeGraph && seedId) {
    const otherNode = visibleNodes.find(node => node.id !== seedId)
    const horizontalGap = Math.max(240, Math.min(360, Math.round(width * 0.32)))
    const centerX = Math.round(width / 2)
    const centerY = getGraphVisualCenterY(height)

    manualNodePosition.set(seedId, {
      x: centerX - Math.round(horizontalGap / 2),
      y: centerY,
      fx: centerX - Math.round(horizontalGap / 2),
      fy: centerY,
    })

    if (otherNode) {
      manualNodePosition.set(otherNode.id, {
        x: centerX + Math.round(horizontalGap / 2),
        y: centerY,
        fx: centerX + Math.round(horizontalGap / 2),
        fy: centerY,
      })
    }
  }

  const data = {
    nodes: visibleNodes.map((node) => {
      const normalizedLevel = normalizeJobLevel(node.level)
      const isSeedNode = Boolean(seedId && node.id === seedId)
      const visualLevel = isSeedNode ? resolveSeedVisualLevel(node.id, normalizedLevel) : normalizedLevel
      const seedStroke = getNodeStrokeColor(visualLevel)
      const seedGlow = getSeedNodeGlowColor(visualLevel)
      const graphCenterY = getGraphVisualCenterY(height)
      return {
        id: node.id,
        jobName: node.jobName,
        level: normalizedLevel,
        visualLevel,
        industryTags: node.industryTags,
        jobDescription: node.jobDescription,
        label: getNodeShortLabel(node.jobName),
        size: isSeedNode ? 40 : 34,
        x: manualNodePosition.get(node.id)?.x,
        y: manualNodePosition.get(node.id)?.y,
        fx: manualNodePosition.get(node.id)?.fx ?? (isSeedNode ? width / 2 : undefined),
        fy: manualNodePosition.get(node.id)?.fy ?? (isSeedNode ? graphCenterY : undefined),
        style: {
          fill: isSeedNode ? '#dbeafe' : getNodeColor(visualLevel),
          stroke: isSeedNode ? seedStroke : getNodeStrokeColor(normalizedLevel),
          opacity: 1,
          lineWidth: isSeedNode ? 4 : 2,
          shadowColor: isSeedNode ? seedGlow : 'transparent',
          shadowBlur: isSeedNode ? 20 : 0,
        },
        labelCfg: {
          style: {
            fill: isSeedNode ? '#ffffff' : getNodeLabelColor(normalizedLevel),
            opacity: 1,
            fontWeight: isSeedNode ? 800 : 700,
            fontSize: isSeedNode ? 11 : 9,
            stroke: isSeedNode ? '#0f172a' : (normalizedLevel === 'junior' ? '#dbeafe' : '#0f172a'),
            lineWidth: isSeedNode ? 2 : (normalizedLevel === 'junior' ? 1 : 2),
            shadowColor: 'transparent',
            shadowBlur: 0,
          },
        },
      }
    }),
    edges: displayEdges.map((edge) => {
      const relationType = normalizeRelationType(edge.relationType)
      return {
        id: edge.isBidirectional
          ? `${edge.source}<->${edge.target}:${relationType}`
          : `${edge.source}->${edge.target}:${relationType}`,
        source: edge.source,
        target: edge.target,
        relationType,
        reason: edge.reason,
        similarity: Number(edge.similarity || 0),
        isBidirectional: edge.isBidirectional,
        label: '',
        labelCfg: {
          autoRotate: !edge.isBidirectional,
          style: {
            fontSize: 10,
            opacity: 0,
            fill: relationType === 'promotion' ? '#334155' : '#64748b',
            background: undefined,
          },
        },
        style: {
          stroke: getEdgeStrokeColor(relationType, edge.similarity || 0),
          opacity: getEdgeBaseOpacity(relationType),
          lineDash: getEdgeDash(relationType),
          lineWidth: getEdgeLineWidth(relationType, edge.similarity || 0.5),
          lineAppendWidth: 14,
          startArrow: edge.isBidirectional,
          endArrow: true,
        },
      }
    }),
  }

  if (!g6Rendered) {
    g6Graph.data(data)
    const renderResult = g6Graph.render()
    if (renderResult && typeof renderResult.catch === 'function') {
      renderResult.catch((error: unknown) => {
        console.warn('G6 render interrupted:', error)
      })
    }
    g6Rendered = true
    hasGraphRendered.value = true
  } else {
    const changeResult = g6Graph.changeData(data)
    if (changeResult && typeof changeResult.catch === 'function') {
      changeResult.catch((error: unknown) => {
        console.warn('G6 changeData interrupted:', error)
      })
    }
  }
  restoreGraphVisibility()
  if (seedId && !manualNodePosition.size) {
    const seedNode = g6Graph.findById(seedId)
    if (seedNode) {
      const graphCenterY = getGraphVisualCenterY(height)
      g6Graph.updateItem(seedNode, {
        fx: width / 2,
        fy: graphCenterY,
      })
    }
  }
  g6Graph.getNodes().forEach((node: any) => g6Graph.clearItemStates(node))
  keepGraphCentered()
}

function renderRadar() {
  if (!radarRef.value || !selectedDetail.value || rightPanel.value !== 'detail') return
  if (!radarRef.value.clientWidth || !radarRef.value.clientHeight) return
  if (radarChart && radarHostEl && radarHostEl !== radarRef.value) {
    radarChart.dispose()
    radarChart = null
    radarHostEl = null
  }
  if (!radarChart) {
    radarChart = echarts.init(radarRef.value)
    radarHostEl = radarRef.value
  }

  const indicators = ABILITY_GROUPS.flatMap(group => group.dimensions).map((key: CompetencyKey) => ({
    name: ABILITY_LABELS[key],
    max: 100,
  }))
  const jobValues = ABILITY_GROUPS.flatMap(group => group.dimensions).map((key: CompetencyKey) => selectedDetail.value?.abilityRequirements?.[key] ?? 0)
  const hasUserSeries = hasAnalyzedProfile.value && Boolean(userAbilityScores.value)
  const userValues = hasUserSeries
    ? ABILITY_GROUPS.flatMap(group => group.dimensions).map((key: CompetencyKey) => Number(userAbilityScores.value?.[key] ?? 0))
    : []

  const radarData: Array<Record<string, unknown>> = [
    {
      value: jobValues,
      name: '岗位能力要求',
      lineStyle: { width: 2, color: '#2563eb' },
      areaStyle: { opacity: 0.18, color: '#60a5fa' },
      itemStyle: { color: '#2563eb' },
      symbol: 'circle',
      symbolSize: 4,
    },
  ]

  if (hasUserSeries) {
    radarData.push({
      value: userValues,
      name: '我的就业能力',
      lineStyle: { width: 2, color: '#16a34a' },
      areaStyle: { opacity: 0.08, color: '#22c55e' },
      itemStyle: { color: '#16a34a' },
      symbol: 'circle',
      symbolSize: 4,
    })
  }

  radarChart.setOption({
    tooltip: { trigger: 'item' },
    legend: hasUserSeries
      ? {
          left: 'center',
          bottom: 0,
          data: ['岗位能力要求', '我的就业能力'],
          selected: {
            岗位能力要求: true,
            我的就业能力: false,
          },
        }
      : undefined,
    radar: {
      radius: hasUserSeries ? '58%' : '66%',
      indicator: indicators,
      splitNumber: 5,
    },
    series: [
      {
        type: 'radar',
        areaStyle: {
          opacity: 0.2,
        },
        lineStyle: {
          width: 2,
        },
        data: radarData,
      },
    ],
  })
}

function scheduleRenderRadar(retry = 8) {
  if (radarRetryTimer) {
    clearTimeout(radarRetryTimer)
    radarRetryTimer = null
  }

  if (!radarRef.value || !selectedDetail.value || rightPanel.value !== 'detail') return
  if (radarRef.value.clientWidth > 0 && radarRef.value.clientHeight > 0) {
    renderRadar()
    return
  }

  if (retry <= 0) return
  radarRetryTimer = setTimeout(() => {
    scheduleRenderRadar(retry - 1)
  }, 90)
}

function scheduleRenderRadarByFrame(retry = 24) {
  if (retry <= 0) return
  requestAnimationFrame(() => {
    if (!radarRef.value || !selectedDetail.value || rightPanel.value !== 'detail') return
    if (radarRef.value.clientWidth > 0 && radarRef.value.clientHeight > 0) {
      renderRadar()
      return
    }
    scheduleRenderRadarByFrame(retry - 1)
  })
}

function onRightPanelAfterEnter() {
  if (rightPanel.value !== 'detail') return
  scheduleRenderRadar()
  scheduleRenderRadarByFrame()
}

function onResize() {
  isDesktop.value = window.innerWidth >= 1024
  if (graphViewAlive && g6Graph && graphRef.value) {
    const width = graphRef.value.clientWidth
    const height = graphRef.value.clientHeight
    if (width && height) {
      g6Graph.changeSize(width, height)
      g6Graph.fitView(24)
    }
  }
  radarChart?.resize()
}

watch(
  () => selectedDetail.value?.jobId,
  async () => {
    activeDetailGroupKey.value = 'basicRequirement'
    resetDetailTextExpandedState()
    await nextTick()
    scheduleRenderRadar()
    scheduleRenderRadarByFrame()
  },
)

watch(
  () => rightPanel.value,
  async (value) => {
    await nextTick()
    if (value === 'detail') {
      scheduleRenderRadar()
      scheduleRenderRadarByFrame()
    }
  },
)

watch(
  () => listCollapsed.value,
  async () => {
    await nextTick()
    setTimeout(() => {
      if (isDesktop.value) scheduleRenderGraph()
    }, 280)
  },
)

watch(
  () => combinedPanelView.value,
  async (value) => {
    await nextTick()
    if (value === 'graph' && isDesktop.value) scheduleRenderGraph()
  },
)

watch(
  () => graphRelationView.value,
  async () => {
    await nextTick()
    if (isDesktop.value) scheduleRenderGraph()
  },
)

watch(
  () => isDesktop.value,
  async () => {
    if (!isDesktop.value && combinedPanelView.value === 'graph') {
      combinedPanelView.value = 'list'
    }
    await nextTick()
    if (isDesktop.value && combinedPanelView.value === 'graph') scheduleRenderGraph()
  },
)

watch(
  () => listViewMode.value,
  async (mode) => {
    if (mode === 'recommend') return
    if (mode === 'favorites') {
      await fetchFavorites()
      return
    }
    await fetchJobs(false)
  },
)

onMounted(async () => {
  graphViewAlive = true
  // 先恢复上次的推荐结果（不自动重推，想更新点「智能推荐岗位」）
  jobRecommendStore.hydrate(appStore.currentStudentId)
  window.addEventListener('resize', onResize)
  window.addEventListener(JOB_RECOMMENDATION_APPLY_EVENT, handleJobRecommendationApplyEvent as EventListener)
  await Promise.all([refreshAll(), fetchUserProfileSummary()])

  const pendingRecommendation = consumePendingJobRecommendationApply()
  if (pendingRecommendation) {
    await applyAssistantJobRecommendation(pendingRecommendation)
  }

  scheduleRenderGraph()

  if (typeof ResizeObserver !== 'undefined') {
    graphResizeObserver = new ResizeObserver(() => {
      if (suppressGraphResizeObserver) return
      if (!isDesktop.value) return
      scheduleRenderGraph(3)
    })
    if (graphRef.value) {
      graphResizeObserver.observe(graphRef.value)
    }
  }
})

onBeforeUnmount(() => {
  graphViewAlive = false
  window.removeEventListener('resize', onResize)
  window.removeEventListener(JOB_RECOMMENDATION_APPLY_EVENT, handleJobRecommendationApplyEvent as EventListener)
  if (renderRetryTimer) clearTimeout(renderRetryTimer)
  if (radarRetryTimer) clearTimeout(radarRetryTimer)
  clearGraphCenterTimers()
  clearGraphTransitionTimers()
  graphResizeObserver?.disconnect()
  graphResizeObserver = null
  hideGraphHoverTip()
  teardownG6Graph()
  radarChart?.dispose()
  radarHostEl = null
})
</script>

<template>
  <section class="space-y-4">
    <div class="flex flex-col gap-3 lg:flex-row lg:items-center lg:justify-between">
      <div>
        <h2 class="text-lg font-semibold md:text-2xl">岗位探索</h2>
        <p class="text-xs text-slate-500 md:text-sm">查询岗位信息，可点击添加到对话让小助手帮你分析</p>
      </div>
      <div class="flex gap-2">
        <el-button :icon="Refresh" :loading="filterLoading || listLoading || graphLoading" @click="refreshAll">刷新</el-button>
      </div>
    </div>

    <el-card shadow="never">
      <div class="grid grid-cols-1 gap-3 md:grid-cols-2 xl:grid-cols-7">
        <el-input
          v-model="query.keyword"
          clearable
          placeholder="岗位/公司/职责关键词"
          @keyup.enter="fetchJobs(true)"
        />
        <el-select v-model="query.city" clearable filterable placeholder="城市" :loading="filterLoading">
          <el-option v-for="city in filterOptions.cities" :key="city" :label="city" :value="city" />
        </el-select>
        <div class="flex items-center gap-1">
          <el-input-number
            v-model="query.salaryMin"
            :min="0"
            :max="999"
            :controls="false"
            placeholder="最低月均K"
            class="w-full"
          />
          <span class="text-slate-400">-</span>
          <el-input-number
            v-model="query.salaryMax"
            :min="0"
            :max="999"
            :controls="false"
            placeholder="最高月均K"
            class="w-full"
          />
        </div>
        <el-select v-model="query.salaryTier" multiple collapse-tags clearable placeholder="薪资档" :loading="filterLoading">
          <el-option v-for="tier in filterOptions.salaryTiers || []" :key="tier" :label="tier" :value="tier" />
        </el-select>
        <el-select v-model="query.exp" multiple collapse-tags clearable placeholder="经验要求" :loading="filterLoading">
          <el-option v-for="item in filterOptions.exps || []" :key="item" :label="item" :value="item" />
        </el-select>
        <el-select v-model="query.edu" multiple collapse-tags clearable placeholder="学历要求" :loading="filterLoading">
          <el-option
            v-for="education in filterOptions.educationRequirements || []"
            :key="education"
            :label="education"
            :value="education"
          />
        </el-select>
      </div>
      <div class="mt-3 flex flex-wrap items-center justify-between gap-2 custom-style">
        <div class="flex flex-wrap gap-2">
          <el-button type="primary" :icon="Search" @click="fetchJobs(true)">筛选</el-button>
          <el-button @click="resetFilters">重置</el-button>
        </div>
        <p class="w-full text-xs text-slate-500">找不到心仪的岗位？可到岗位采集页面获取网上更多的岗位信息</p>
      </div>
    </el-card>

    <div class="grid grid-cols-1 gap-4 lg:grid-cols-5">
      <div class="space-y-4 lg:col-span-3">
        <el-card shadow="never" class="job-combined-card">
          <template #header>
            <div class="flex items-center justify-between">
              <div class="flex items-center gap-2">
                <span class="font-medium">岗位列表</span>
                <el-button
                  v-if="combinedPanelView === 'graph'"
                  text
                  size="small"
                  class="panel-legend-toggle-btn"
                  @click="toggleGraphLegendVisible"
                >
                  图例
                </el-button>
              </div>
              <div v-if="combinedPanelView === 'list'" class="job-list-toolbar">
                <div class="job-list-tabs" role="tablist" aria-label="岗位列表分类">
                  <button
                    type="button"
                    role="tab"
                    :aria-selected="listViewMode === 'all'"
                    class="job-list-tab"
                    :class="{ 'job-list-tab--active': listViewMode === 'all' }"
                    @click="listViewMode = 'all'"
                  >全部岗位</button>
                  <button
                    type="button"
                    role="tab"
                    :aria-selected="listViewMode === 'favorites'"
                    class="job-list-tab"
                    :class="{ 'job-list-tab--active': listViewMode === 'favorites' }"
                    @click="listViewMode = 'favorites'"
                  >我的收藏</button>
                  <button
                    type="button"
                    role="tab"
                    :aria-selected="listViewMode === 'recommend'"
                    class="job-list-tab"
                    :class="{ 'job-list-tab--active': listViewMode === 'recommend' }"
                    @click="listViewMode = 'recommend'"
                  >岗位推荐</button>
                </div>
                <el-checkbox
                  v-model="latestPublishedFirst"
                  :class="{ 'job-list-sort-placeholder': listViewMode === 'recommend' }"
                  :disabled="listViewMode === 'recommend'"
                  @change="onLatestSortToggle"
                >按最新发布时间排序</el-checkbox>
                <span class="text-xs text-slate-500">共 {{ listTotal }} 条</span>
                <el-button text :icon="listCollapsed ? ArrowDown : ArrowUp" @click="listCollapsed = !listCollapsed">
                  {{ listCollapsed ? '展开' : '收起' }}
                </el-button>
              </div>
              <div v-else class="flex flex-wrap items-center justify-end gap-2">
                <el-button
                  size="small"
                  :type="graphRelationView === 'promotion' ? 'primary' : 'default'"
                  @click="toggleGraphRelationView('promotion')"
                >
                  晋升主干
                </el-button>
                <el-button
                  size="small"
                  :type="graphRelationView === 'transition' ? 'primary' : 'default'"
                  @click="toggleGraphRelationView('transition')"
                >
                  换岗机会
                </el-button>
              </div>
            </div>
          </template>

          <div class="combined-panel-stack">
            <div
              class="combined-panel-pane combined-panel-pane--list"
              :class="combinedPanelView === 'list' ? 'combined-panel-pane--active' : 'combined-panel-pane--inactive combined-panel-pane--to-left'"
            >
              <Transition name="list-collapse">
                <div v-show="!listCollapsed">
              <template v-if="listViewMode === 'recommend'">
                <div class="recommend-panel">
                  <div class="flex flex-wrap items-center justify-between gap-2">
                    <div class="text-xs text-slate-500">
                      基于你的能力画像与求职意愿，AI 从岗位库中智能筛选最匹配的岗位。
                    </div>
                    <el-button
                      type="primary"
                      :icon="MagicStick"
                      :loading="recommendLoading"
                      @click="fetchRecommendedJobs"
                    >
                      智能推荐岗位
                    </el-button>
                  </div>

                  <div v-loading="recommendLoading" class="mt-3 min-h-[200px]">
                    <template v-if="recommendedJobList.length">
                      <div
                        v-for="item in recommendedJobList"
                        :key="item.jobId"
                        class="recommend-job-card"
                        :class="{ 'recommend-job-card--selected': item.jobId === selectedJobId }"
                        @click="item.jobId && selectJob(item.jobId)"
                      >
                        <div class="flex items-center gap-2">
                          <span class="font-medium text-slate-800">{{ item.jobName }}</span>
                          <el-tag v-if="item.isBestMatch" type="danger" size="small" effect="dark">最佳匹配</el-tag>
                          <el-tag v-if="item.level" size="small" effect="plain">{{ item.level }}</el-tag>
                        </div>
                        <div class="mt-1 text-xs text-slate-500">
                          {{ item.companyName }} · {{ item.city }} · {{ item.salaryText }}
                        </div>
                        <div v-if="item.reason" class="recommend-job-reason">{{ item.reason }}</div>
                      </div>
                    </template>

                    <el-empty
                      v-else
                      :description="recommendLoading ? '正在生成推荐结果...' : '点击「智能推荐岗位」获取为你定制的岗位推荐'"
                    />
                  </div>
                </div>
              </template>

              <template v-else>
              <div v-if="listViewMode === 'favorites'" class="mb-3 flex items-center justify-between rounded-lg border border-blue-100 bg-blue-50/60 px-3 py-2">
                <span class="text-xs text-slate-500">没有找到现成岗位时，可手动录入并直接收藏。</span>
                <el-button type="primary" size="small" :icon="Plus" @click="openCustomJobDialog">自己添加岗位信息</el-button>
              </div>
              <el-table
                v-if="isDesktop"
                v-loading="listViewMode === 'favorites' ? favoriteLoading : listLoading"
                :data="listViewMode === 'favorites' ? favoriteJobsList : jobs"
                size="small"
                :row-class-name="resolveJobRowClass"
                class="job-list-table"
                @row-click="(row: JobListItem) => selectJob(row.jobId)"
              >
                <el-table-column prop="jobName" label="岗位" width="100" />
                <el-table-column prop="city" label="城市" width="70" />
                <el-table-column label="公司" min-width="140">
                  <template #default="scope">
                    <span class="text-slate-700">{{ displayCompanyText(scope.row) }}</span>
                  </template>
                </el-table-column>
                <el-table-column label="薪资" width="90">
                  <template #default="scope">
                    {{ displaySalary(scope.row) }}
                  </template>
                </el-table-column>
                <el-table-column label="更新时间" width="125">
                  <template #default="scope">
                    <el-tag size="small" :type="publishTagType(scope.row)">{{ publishDateText(scope.row) }}</el-tag>
                  </template>
                </el-table-column>
                <el-table-column label="操作" width="90" fixed="right">
                  <template #default="scope">
                    <el-button
                      size="small"
                      :type="favoriteJobIds.includes(scope.row.jobId) ? 'warning' : 'default'"
                      @click.stop="toggleFavoriteByJob(scope.row.jobId)"
                    >
                      {{ favoriteJobIds.includes(scope.row.jobId) ? '已收藏' : '收藏' }}
                    </el-button>
                  </template>
                </el-table-column>
              </el-table>

              <div v-else class="space-y-2">
                <el-skeleton :loading="listViewMode === 'favorites' ? favoriteLoading : listLoading" animated :rows="4">
                  <div
                    v-for="item in (listViewMode === 'favorites' ? favoriteJobsList : jobs)"
                    :key="item.jobId"
                    class="w-full rounded-lg border px-3 py-3 text-left transition"
                    :class="item.jobId === selectedJobId ? 'border-blue-500 bg-blue-50' : 'border-slate-200 bg-white'"
                    @click="selectJob(item.jobId)"
                  >
                    <div class="flex items-center justify-between">
                      <div class="font-medium text-slate-800">{{ item.jobName }}</div>
                      <el-tag size="small" :type="publishTagType(item)">{{ publishDateText(item) }}</el-tag>
                    </div>
                    <div class="mt-1 text-xs text-slate-500">{{ item.city }} · {{ displaySalary(item) }} · {{ publishDateText(item) }}</div>
                    <div class="mt-1 text-xs text-slate-500">公司：{{ displayCompanyText(item) }}</div>
                    <div class="mt-2 flex items-center gap-3">
                      <el-button
                        round
                        size="small"
                        :type="favoriteJobIds.includes(item.jobId) ? 'warning' : 'default'"
                        @click.stop="toggleFavoriteByJob(item.jobId)"
                      >
                        {{ favoriteJobIds.includes(item.jobId) ? '已收藏' : '收藏' }}
                      </el-button>
                    </div>
                  </div>
                </el-skeleton>
              </div>

              <div v-if="listViewMode === 'all'" class="mt-3 flex justify-end">
                <el-pagination
                  v-model:current-page="query.page"
                  v-model:page-size="query.pageSize"
                  layout="prev, pager, next, sizes"
                  :page-sizes="[12, 16, 20]"
                  :total="total"
                  size="small"
                  @current-change="onPageChange"
                  @size-change="onPageSizeChange"
                />
              </div>
              </template>
                </div>
              </Transition>
            </div>

            <div
              class="combined-panel-pane combined-panel-pane--graph graph-panel-wrapper"
              :class="combinedPanelView === 'graph' ? 'combined-panel-pane--active' : 'combined-panel-pane--inactive combined-panel-pane--to-right'"
            >
              <div v-if="isDesktop" class="graph-shell">
            <div v-show="graphLegendVisible" class="graph-legend">
              <div class="legend-row"><span class="legend-dot legend-junior" />初级岗位</div>
              <div class="legend-row"><span class="legend-dot legend-middle" />中级岗位</div>
              <div class="legend-row"><span class="legend-dot legend-senior" />高级岗位</div>
              <div class="legend-row"><span class="legend-dot legend-lead" />资深岗位</div>
              <div class="legend-row"><span class="legend-line legend-promotion" />晋升路径</div>
              <div class="legend-row"><span class="legend-line legend-transition" />换岗路径</div>
              <div class="legend-row"><span class="legend-line legend-sim-high" />高相似度</div>
              <div class="legend-row"><span class="legend-line legend-sim-mid" />中相似度</div>
              <div class="legend-row"><span class="legend-line legend-sim-low" />低相似度</div>
            </div>
            <div
              v-if="graphHoverTip.visible"
              class="graph-hover-tip"
              :style="{ left: `${graphHoverTip.x}px`, top: `${graphHoverTip.y}px` }"
            >
              <div class="g6-tooltip-title-row">
                <div class="g6-tooltip-title">{{ graphHoverTip.title }}</div>
                <span
                  v-if="graphHoverTip.tagText"
                  class="g6-tooltip-badge"
                  :class="`g6-tooltip-badge--${graphHoverTip.tagTone}`"
                >
                  {{ graphHoverTip.tagText }}
                </span>
              </div>
              <div v-for="(line, index) in graphHoverTip.lines" :key="index" class="g6-tooltip-line">{{ line }}</div>
            </div>
            <div ref="graphRef" class="graph-canvas w-full rounded-xl bg-slate-50" />
            <div v-if="graphLoading || graphTransitioning" class="graph-loading-mask">
              <el-tag type="info" effect="dark">{{ graphLoadingText }}</el-tag>
            </div>
            <el-alert
              v-if="graphInitError"
              class="mt-2"
              type="error"
              :closable="false"
              :title="graphInitError"
            />
              </div>
              <el-empty v-else description="关系图谱仅支持桌面端查看" />
            </div>
          </div>
        </el-card>
      </div>

      <div class="space-y-4 lg:col-span-2">
        <el-card shadow="never">
          <template #header>
            <div class="flex items-center justify-between">
              <div class="flex items-center gap-2">
                <h3 class="text-base font-semibold text-slate-800">岗位详情</h3>
              </div>
              <div class="flex items-center gap-2">
                <el-button type="primary" plain size="small" :disabled="!selectedDetail" @click="addSelectedJobToConversation">添加到对话</el-button>
                <el-button text :icon="isSelectedFavorited ? StarFilled : Star" :loading="favoriteLoading" @click="toggleFavorite">
                  {{ isSelectedFavorited ? '已收藏' : '收藏岗位' }}
                </el-button>
              </div>
            </div>
          </template>

          <Transition name="panel-slide" mode="out-in" @after-enter="onRightPanelAfterEnter">
            <div :key="rightPanel" class="min-h-[640px]">
              <template v-if="rightPanel === 'detail'">
                <div v-loading="detailLoading">
                  <template v-if="selectedDetail">
                    <div class="space-y-3 text-sm">
                      <div class="job-detail-hero">
                        <img :src="jobsDetailBackgroundImage" alt="岗位详情背景" class="job-detail-hero-bg" />
                        <div class="text-base font-semibold text-slate-900">{{ selectedDetail.jobName }}</div>
                        <div class="mt-1 text-slate-500">
                          {{ selectedDetail.companyName }} · {{ selectedDetail.city }} · {{ displaySalary(selectedDetail) }}
                        </div>
                        <div class="mt-1 text-xs text-slate-500">
                          {{ detailMetaLine(selectedDetail) }} · 来源：
                          <a
                            v-if="resolveSourceHref(selectedDetail)"
                            :href="resolveSourceHref(selectedDetail)"
                            target="_blank"
                            rel="noopener noreferrer"
                            class="inline-flex items-center gap-1 text-blue-600 hover:text-blue-700 hover:underline"
                          >
                            {{ resolveSourceSiteText(selectedDetail) }}
                            <span aria-hidden="true">↗</span>
                          </a>
                          <span v-else>{{ resolveSourceSiteText(selectedDetail) }}</span>
                        </div>
                        <div class="mt-1 text-xs text-slate-500">{{ salaryMetaLine(selectedDetail) }}</div>
                      </div>
                      <div class="flex flex-wrap gap-2">
                        <el-tag effect="plain">学历要求：{{ educationTagText(selectedDetail) }}</el-tag>
                        <el-tag v-if="selectedDetail.exp" effect="plain">经验：{{ selectedDetail.exp }}</el-tag>
                        <el-tag v-if="selectedDetail.tier" type="success">{{ selectedDetail.tier }}</el-tag>
                        <el-tag v-for="tag in selectedDetail.cats || []" :key="tag">{{ tag }}</el-tag>
                        <el-tag :type="publishTagType(selectedDetail)">{{ publishDateText(selectedDetail) }}</el-tag>
                      </div>
                      <div class="rounded-lg bg-slate-50 p-3 text-slate-600 leading-6">
                        <div class="mb-1 flex items-center justify-between gap-2">
                          <div class="text-sm font-medium text-slate-700">岗位描述</div>
                          <el-button
                            v-if="canToggleJobDescription"
                            text
                            size="small"
                            class="detail-text-toggle"
                            @click="jobDescriptionExpanded = !jobDescriptionExpanded"
                          >
                            {{ jobDescriptionExpanded ? '缩略' : '详情' }}
                          </el-button>
                        </div>
                        <p
                          class="detail-rich-text"
                          :class="jobDescriptionExpanded ? 'detail-rich-text--expanded' : 'detail-rich-text--collapsed'"
                        >
                          {{ jobDescriptionText }}
                        </p>
                        <div v-if="hasShortJobDescription" class="mt-2 text-xs text-slate-500">
                          <a
                            v-if="resolveSourceHref(selectedDetail)"
                            :href="resolveSourceHref(selectedDetail)"
                            target="_blank"
                            rel="noopener noreferrer"
                            class="inline-flex items-center gap-1 text-blue-600 hover:text-blue-700 hover:underline"
                          >
                            官网：{{ resolveSourceSiteText(selectedDetail) }}
                            <span aria-hidden="true">↗</span>
                          </a>
                          <span v-else>当前岗位暂无可跳转的官网链接</span>
                        </div>
                      </div>

                    </div>

                    <!-- 能力雷达 / 知识点 / 能力要求三块都来自 ES 时代的字段，爬虫数据里没有，
                         整块隐藏；一旦数据补上（或详情走的是 ES 回退）就自动出现 -->
                    <template v-if="hasDetailAbilityData">
                    <div class="mt-4 space-y-2">
                      <div class="h-[260px]" ref="radarRef" />
                      <div v-if="profileLoaded && !hasAnalyzedProfile" class="flex justify-end">
                        <el-button type="primary" plain size="small" @click="router.push('/student')">前往分析就业能力</el-button>
                      </div>

                      <div class="job-key-skills-card">
                        <div class="mb-2 flex items-center justify-between gap-2">
                          <div class="text-sm font-medium text-slate-700">要求掌握知识点和工具</div>
                          <el-button
                            v-if="canToggleKeySkills"
                            text
                            size="small"
                            class="detail-text-toggle"
                            @click="keySkillsExpanded = !keySkillsExpanded"
                          >
                            {{ keySkillsExpanded ? '收起' : '展开' }}
                          </el-button>
                        </div>

                        <div
                          class="job-key-skills-body"
                          :class="keySkillsExpanded ? 'job-key-skills-body--expanded' : 'job-key-skills-body--collapsed'"
                        >
                          <template v-if="hasKeySkills">
                            <div v-if="visibleHardSkills.length" class="key-skill-group">
                              <div class="key-skill-tags">
                                <el-tag
                                  v-for="item in visibleHardSkills"
                                  :key="`hard_${item}`"
                                  type="info"
                                  effect="plain"
                                >
                                  {{ item }}
                                </el-tag>
                              </div>
                            </div>

                            <div v-if="visibleToolSkills.length" class="key-skill-group">
                              <div class="key-skill-tags">
                                <el-tag
                                  v-for="item in visibleToolSkills"
                                  :key="`tool_${item}`"
                                  type="success"
                                  effect="plain"
                                >
                                  {{ item }}
                                </el-tag>
                              </div>
                            </div>

                            <div v-if="hasHiddenKeySkills" class="key-skill-more-tip">
                              还有 {{ hiddenKeySkillsCount }} 项技能要求，点击展开查看
                            </div>
                          </template>

                          <div v-else class="text-xs text-slate-400">暂无关键技能要求</div>
                        </div>
                      </div>
                    </div>

                    <el-divider>岗位能力要求</el-divider>
                    <div class="space-y-3">
                      <div class="ability-group-switch">
                        <el-button
                          v-for="group in detailGroups"
                          :key="group.key"
                          size="small"
                          :color="group.color"
                          :plain="activeDetailGroupKey !== group.key"
                          @click="activeDetailGroupKey = group.key"
                        >
                          {{ group.label }}
                        </el-button>
                      </div>

                      <Transition name="ability-panel-slide" mode="out-in">
                        <div v-if="activeDetailGroup" :key="activeDetailGroup.key" class="rounded-lg border border-slate-200 p-3">
                          <div class="mb-2 flex items-center gap-2">
                            <span class="h-2 w-2 rounded-full" :style="{ backgroundColor: activeDetailGroup.color }" />
                            <span class="text-sm font-semibold" :style="{ color: activeDetailGroup.color }">{{ activeDetailGroup.label }}</span>
                          </div>
                          <div class="space-y-2">
                            <div v-for="item in activeDetailGroup.items" :key="item.key" class="rounded-md bg-slate-50 p-2">
                              <div class="mb-1 flex items-center justify-between text-xs">
                                <span class="font-medium text-slate-700">{{ item.label }}</span>
                                <span class="font-semibold" :style="{ color: activeDetailGroup.color }">{{ item.score }}</span>
                              </div>
                              <el-progress :show-text="false" :percentage="item.score" :color="activeDetailGroup.color" :stroke-width="6" />
                              <div class="mt-1 text-[11px] text-slate-500">{{ item.detail }}</div>
                            </div>
                          </div>
                        </div>
                      </Transition>
                    </div>
                    </template>
                  </template>
                  <el-empty v-else description="请选择岗位" />
                </div>
              </template>

              <template v-else-if="rightPanel === 'paths'">
                <div class="graph-path-card rounded-lg border border-slate-200 bg-white p-3">
                  <div class="flex items-center justify-between gap-2">
                    <div class="text-sm font-semibold text-slate-700">发展路径（图谱推导）</div>
                    <el-tag size="small" type="info">焦点：{{ graphPathSummary.seed || '-' }}</el-tag>
                  </div>

                  <div class="mt-2">
                    <div class="text-xs font-medium text-slate-500">晋升主干</div>
                    <div v-if="graphPathSummary.promotionPath.length > 1" class="graph-path-chain mt-1">
                      <template v-for="(item, index) in graphPathSummary.promotionPath" :key="`${item}_${index}`">
                        <span class="graph-path-node">{{ item }}</span>
                        <span v-if="index < graphPathSummary.promotionPath.length - 1" class="graph-path-arrow">→</span>
                      </template>
                    </div>
                    <div v-else class="mt-1 text-xs text-slate-400">当前焦点暂无明确晋升链路</div>
                  </div>

                  <div class="mt-3">
                    <div class="text-xs font-medium text-slate-500">换岗机会</div>
                    <div v-if="graphPathSummary.transitionPaths.length" class="mt-2 space-y-2">
                      <div
                        v-for="item in graphPathSummary.transitionPaths"
                        :key="`${graphPathSummary.seed}_${item.targetJobName}_${item.reason}`"
                        class="rounded-md border border-slate-100 bg-slate-50 px-2 py-2"
                      >
                        <div class="flex items-center justify-between gap-2">
                          <span class="text-xs font-medium text-slate-700">{{ item.targetJobName }}</span>
                          <div class="flex items-center gap-1">
                            <el-tag size="small" effect="plain">相似度 {{ graphPathSimilarityText(item.similarity) }}</el-tag>
                            <el-tag size="small" :type="graphPathDifficultyTagType(item.difficulty)">
                              {{ graphPathDifficultyLabel(item.difficulty) }}
                            </el-tag>
                          </div>
                        </div>
                        <div class="mt-1 text-[11px] leading-5 text-slate-500">{{ item.reason }}</div>
                      </div>
                    </div>
                    <div v-else class="mt-1 text-xs text-slate-400">当前焦点暂无可展示的换岗机会</div>
                  </div>
                </div>
              </template>

              <template v-else>
                <div class="space-y-2">
                  <button
                    v-for="item in otherRecommendations"
                    :key="item.jobId"
                    class="w-full rounded-lg border border-slate-200 px-3 py-2 text-left transition hover:bg-slate-50"
                    @click="selectJob(item.jobId)"
                  >
                    <div class="flex items-center justify-between">
                      <span class="text-sm font-medium text-slate-700">{{ item.jobName }}</span>
                      <el-tag size="small" :type="publishTagType(item)">{{ publishDateText(item) }}</el-tag>
                    </div>
                    <div class="mt-1 text-xs text-slate-500">{{ item.city }} · {{ displaySalary(item) }}</div>
                    <div v-if="item.edu || item.exp" class="mt-1 text-xs text-slate-400">
                      {{ [item.edu, item.exp].filter(Boolean).join(' · ') }}
                    </div>
                  </button>
                </div>
              </template>
            </div>
          </Transition>
        </el-card>
      </div>
    </div>

    <JobMapExplorerDialog
      v-if="jobMapDialogVisible"
      v-model="jobMapDialogVisible"
      @select-job="handleMapJobSelect"
    />

    <el-dialog
      v-model="customJobDialogVisible"
      title="自己添加岗位信息"
      width="min(760px, 94vw)"
      top="5vh"
      destroy-on-close
      :close-on-click-modal="false"
    >
      <p class="mb-4 text-xs leading-5 text-slate-500">填写后将先保存到岗位库，再自动加入“我的收藏”。岗位名称为必填项，其余信息越完整，小助手分析时越准确。</p>
      <el-form ref="customJobFormRef" :model="customJobForm" :rules="customJobRules" label-position="top">
        <div class="grid grid-cols-1 gap-x-4 md:grid-cols-2">
          <el-form-item label="岗位名称" prop="title">
            <el-input v-model="customJobForm.title" maxlength="100" show-word-limit placeholder="例如：Java 后端开发工程师" />
          </el-form-item>
          <el-form-item label="公司名称">
            <el-input v-model="customJobForm.company" maxlength="100" placeholder="请输入公司名称" />
          </el-form-item>
          <el-form-item label="工作城市">
            <el-input v-model="customJobForm.city" maxlength="50" placeholder="例如：杭州" />
          </el-form-item>
          <el-form-item label="原始薪资描述">
            <el-input v-model="customJobForm.salary" maxlength="50" placeholder="例如：15-25K·14薪" />
          </el-form-item>
          <el-form-item label="最低月薪（K）">
            <el-input-number v-model="customJobForm.salaryMin" :min="0" :max="999" :precision="1" class="!w-full" placeholder="最低月薪" />
          </el-form-item>
          <el-form-item label="最高月薪（K）">
            <el-input-number v-model="customJobForm.salaryMax" :min="0" :max="999" :precision="1" class="!w-full" placeholder="最高月薪" />
          </el-form-item>
          <el-form-item label="薪资单位">
            <el-select v-model="customJobForm.salaryUnit" allow-create filterable class="w-full" placeholder="选择或输入单位">
              <el-option label="K/月" value="K/月" />
              <el-option label="元/月" value="元/月" />
              <el-option label="元/天" value="元/天" />
              <el-option label="元/时" value="元/时" />
            </el-select>
          </el-form-item>
          <el-form-item label="薪资档位">
            <el-input v-model="customJobForm.tier" maxlength="30" placeholder="例如：15-30K" />
          </el-form-item>
          <el-form-item label="经验要求">
            <el-input v-model="customJobForm.exp" maxlength="50" placeholder="例如：1-3年 / 经验不限" />
          </el-form-item>
          <el-form-item label="学历要求">
            <el-input v-model="customJobForm.edu" maxlength="50" placeholder="例如：本科 / 学历不限" />
          </el-form-item>
          <el-form-item label="岗位分类标签">
            <el-input-tag v-model="customJobForm.categories" placeholder="输入标签后回车，例如 AI Agent" />
          </el-form-item>
          <el-form-item label="搜索关键词">
            <el-input-tag v-model="customJobForm.keywords" placeholder="输入关键词后回车" />
          </el-form-item>
        </div>
        <el-form-item label="岗位链接" prop="url">
          <el-input v-model="customJobForm.url" maxlength="500" placeholder="https://example.com/job" />
        </el-form-item>
        <el-form-item label="岗位职责与任职要求">
          <el-input
            v-model="customJobForm.content"
            type="textarea"
            :rows="7"
            maxlength="8000"
            show-word-limit
            placeholder="粘贴岗位 JD，包括岗位职责、任职要求、技能要求等信息"
          />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button :disabled="customJobSubmitting" @click="customJobDialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="customJobSubmitting" @click="submitCustomJob">保存并收藏</el-button>
      </template>
    </el-dialog>
  </section>
</template>

<style scoped>
.g6-tooltip-title-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  margin-bottom: 4px;
}

.g6-tooltip-title {
  font-size: 12px;
  font-weight: 600;
  color: #0f172a;
}

.g6-tooltip-line {
  font-size: 11px;
  color: #475569;
  line-height: 1.5;
}

.g6-tooltip-badge {
  display: inline-flex;
  align-items: center;
  border-radius: 999px;
  border: 1px solid transparent;
  padding: 1px 8px;
  font-size: 10px;
  font-weight: 600;
  line-height: 1.4;
  white-space: nowrap;
}

.g6-tooltip-badge--sim-high {
  color: #1d4ed8;
  background: #dbeafe;
  border-color: #93c5fd;
}

.g6-tooltip-badge--sim-mid {
  color: #0369a1;
  background: #e0f2fe;
  border-color: #7dd3fc;
}

.g6-tooltip-badge--sim-low {
  color: #334155;
  background: #e2e8f0;
  border-color: #94a3b8;
}

.g6-tooltip-badge--level-junior {
  color: #334155;
  background: #f1f5f9;
  border-color: #cbd5e1;
}

.g6-tooltip-badge--level-middle {
  color: #1d4ed8;
  background: #dbeafe;
  border-color: #93c5fd;
}

.g6-tooltip-badge--level-senior {
  color: #5b21b6;
  background: #ede9fe;
  border-color: #c4b5fd;
}

.g6-tooltip-badge--level-lead {
  color: #6d28d9;
  background: #f5f3ff;
  border-color: #ddd6fe;
}

.graph-shell {
  position: relative;
  min-height: 740px;
  overflow: hidden;
}

.graph-panel-wrapper {
  min-height: 740px;
}

.graph-canvas {
  height: 740px;
  min-height: 740px;
}

.graph-hover-tip {
  position: absolute;
  z-index: 20;
  pointer-events: none;
  width: 260px;
  max-width: 260px;
  border-radius: 8px;
  border: 1px solid #dbe3ee;
  background: rgba(255, 255, 255, 0.95);
  padding: 8px 10px;
  box-shadow: 0 6px 20px rgba(15, 23, 42, 0.12);
  word-break: break-word;
}

.graph-loading-mask {
  position: absolute;
  inset: 0;
  display: flex;
  align-items: flex-start;
  justify-content: flex-end;
  padding: 10px;
  pointer-events: none;
  z-index: 8;
}

.graph-legend {
  position: absolute;
  z-index: 5;
  top: 8px;
  left: 8px;
  border: 1px solid #dbe3ee;
  border-radius: 8px;
  background: rgba(255, 255, 255, 0.92);
  backdrop-filter: blur(4px);
  padding: 8px;
  display: grid;
  gap: 4px;
}

.legend-row {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 11px;
  color: #334155;
}

.legend-dot {
  width: 10px;
  height: 10px;
  border-radius: 999px;
  border: 1px solid #0f172a;
}

.legend-junior {
  background: #ffffff;
  border-color: #3b82f6;
}

.legend-middle {
  background: #3b82f6;
  border-color: #2563eb;
}

.legend-senior {
  background: #8b5cf6;
  border-color: #6d28d9;
}

.legend-lead {
  background: #5b21b6;
  border-color: #4c1d95;
}

.legend-line {
  width: 18px;
  border-top: 2px solid #64748b;
}

.legend-promotion { border-top-style: solid; }
.legend-transition { border-top-style: dashed; }
.legend-sim-high { border-top-color: #2563eb; }
.legend-sim-mid { border-top-color: #60a5fa; }
.legend-sim-low { border-top-color: #94a3b8; }

.panel-switch-icon-btn {
  color: #475569;
}

.panel-legend-toggle-btn {
  color: #64748b;
  padding: 0 6px;
}

.combined-panel-stack {
  display: grid;
  min-height: 740px;
}

.combined-panel-pane {
  grid-area: 1 / 1;
  transition: opacity 260ms ease, transform 260ms ease;
  will-change: opacity, transform;
}

.combined-panel-pane--active {
  opacity: 1;
  transform: translateX(0);
  pointer-events: auto;
  z-index: 2;
}

.combined-panel-pane--inactive {
  opacity: 0;
  pointer-events: none;
  z-index: 1;
}

.combined-panel-pane--to-left {
  transform: translateX(-16px);
}

.combined-panel-pane--to-right {
  transform: translateX(16px);
}

.job-combined-card {
  position: static;
}

.job-detail-hero {
  position: relative;
  overflow: hidden;
  border-radius: 12px;
  border: 1px solid rgb(226 232 240);
  background: #fff;
  padding: 12px;
}

.job-detail-hero-bg {
  position: absolute;
  top: 0;
  right: 0;
  width: min(48%, 180px);
  height: auto;
  object-fit: contain;
  opacity: 0.18;
  pointer-events: none;
  user-select: none;
  -webkit-mask-image: linear-gradient(to bottom left, rgba(0, 0, 0, 1) 0%, rgba(0, 0, 0, 0) 85%);
  mask-image: linear-gradient(to bottom left, rgba(0, 0, 0, 1) 0%, rgba(0, 0, 0, 0) 85%);
}

.detail-rich-text {
  margin: 0;
  white-space: pre-line;
  line-height: 1.72;
  overflow: hidden;
  transition: max-height 260ms ease, opacity 220ms ease, transform 220ms ease;
}

.detail-rich-text--collapsed {
  display: -webkit-box;
  -webkit-box-orient: vertical;
  line-clamp: 3;
  -webkit-line-clamp: 3;
  max-height: calc(1.72em * 3);
  opacity: 0.9;
}

.detail-rich-text--expanded {
  display: block;
  max-height: 36em;
  opacity: 1;
}

.detail-text-toggle {
  color: #555555a0;
  padding: 0;
  min-height: 0;
}

.job-key-skills-card {
  border: 1px solid #e2e8f0;
  border-radius: 12px;
  background: linear-gradient(135deg, #f8fafc 0%, #ffffff 100%);
  padding: 12px;
}

.job-key-skills-body {
  overflow: hidden;
  transition: max-height 280ms ease, opacity 220ms ease;
}

.job-key-skills-body--collapsed {
  max-height: 160px;
  opacity: 0.95;
}

.job-key-skills-body--expanded {
  max-height: 560px;
  opacity: 1;
}

.key-skill-group + .key-skill-group {
  margin-top: 10px;
}

.key-skill-group-title {
  margin-bottom: 6px;
  font-size: 12px;
  font-weight: 600;
  color: #475569;
}

.key-skill-tags {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
}

.key-skill-more-tip {
  margin-top: 8px;
  font-size: 11px;
  color: #64748b;
}

.panel-slide-enter-active,
.panel-slide-leave-active {
  transition: all 260ms ease;
}

.panel-slide-enter-from {
  opacity: 0;
  transform: translateX(20px);
}

.panel-slide-leave-to {
  opacity: 0;
  transform: translateX(-20px);
}

.list-collapse-enter-active,
.list-collapse-leave-active {
  transition: all 280ms ease;
  overflow: hidden;
}

.list-collapse-enter-from,
.list-collapse-leave-to {
  opacity: 0;
  max-height: 0;
}

.list-collapse-enter-to,
.list-collapse-leave-from {
  opacity: 1;
  max-height: 1000px;
}

.ability-group-switch {
  display: flex;
  flex-wrap: wrap;
  justify-content: center;
  align-items: center;
  gap: 8px;
}

.ability-panel-slide-enter-active,
.ability-panel-slide-leave-active {
  transition: opacity 220ms ease, transform 220ms ease;
}

.ability-panel-slide-enter-from {
  opacity: 0;
  transform: translateX(14px);
}

.ability-panel-slide-leave-to {
  opacity: 0;
  transform: translateX(-14px);
}

:deep(.job-row-selected > td) {
  background: #eff6ff !important;
}

:deep(.el-table__body tr.job-row-selected:hover > td) {
  background: #dbeafe !important;
}

@media (max-width: 1440px) {
  .combined-panel-stack {
    min-height: 660px;
  }

  .graph-shell {
    min-height: 660px;
  }

  .graph-canvas {
    height: 660px;
    min-height: 660px;
  }

  .graph-panel-wrapper {
    min-height: 660px;
  }
}

@media (min-width: 1024px) {
  .job-combined-card {
    position: sticky;
    top: 72px;
  }
}

.job-list-toolbar {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  justify-content: flex-end;
  gap: 8px 14px;
}

.job-list-tabs {
  display: inline-flex;
  align-items: flex-end;
  gap: 20px;
  margin-right: 6px;
}

.job-list-sort-placeholder {
  visibility: hidden;
  pointer-events: none;
}

.job-list-tab {
  position: relative;
  border: 0;
  background: transparent;
  padding: 4px 1px 9px;
  color: #64748b;
  font-size: 15px;
  font-weight: 500;
  cursor: pointer;
  transition: color 160ms ease;
}

.job-list-tab::after {
  position: absolute;
  right: 0;
  bottom: 0;
  left: 0;
  height: 3px;
  border-radius: 999px;
  background: #2563eb;
  content: '';
  opacity: 0;
  transform: scaleX(0.45);
  transition: opacity 160ms ease, transform 160ms ease;
}

.job-list-tab:hover,
.job-list-tab--active {
  color: #1d4ed8;
}

.job-list-tab--active::after {
  opacity: 1;
  transform: scaleX(1);
}

:deep(.job-list-table .el-table__body tr) {
  cursor: pointer;
}

.recommend-job-card {
  cursor: pointer;
  border-radius: 8px;
  border: 1px solid #e2e8f0;
  background: #fff;
  padding: 10px 12px;
  transition: border-color 0.2s ease, box-shadow 0.2s ease;
}

.recommend-job-card + .recommend-job-card {
  margin-top: 8px;
}

.recommend-job-card:hover {
  border-color: #60a5fa;
  box-shadow: 0 4px 12px rgba(59, 130, 246, 0.12);
}

.recommend-job-card--selected {
  border-color: #3b82f6;
  background: #eff6ff;
}

.recommend-job-reason {
  margin-top: 8px;
  padding: 6px 8px;
  border-radius: 6px;
  border-left: 2px solid #93c5fd;
  background: #f8fafc;
  color: #475569;
  font-size: 12px;
  line-height: 1.6;
}

.recommend-job-card--selected .recommend-job-reason {
  background: rgba(255, 255, 255, 0.7);
}
</style>
