import { computed, reactive, ref } from 'vue'
import { getToken } from '../utils/auth'

interface ApiResponse<T> {
  code: number
  msg: string
  data?: T
}

export interface HomePublicOverviewResult {
  overviewMetrics: Array<{
    key: string
    label: string
    value: number
    unit: string
    trend: {
      direction: 'up' | 'down' | 'flat'
      delta: number
      period: string
    }
  }>
  charts: {
    matchScoreTrend: Array<{ date: string; value: number }>
    pathTypeDistribution: Array<{ type: string; count: number }>
    pipelineFunnel: Array<{ stage: string; count: number }>
  }
  updatedAt: string
}

interface AbilityScores {
  professionalSkill: number
  certificate: number
  innovation: number
  internalMotivation: number
  learning: number
  stressTolerance: number
  communication: number
  internship: number
  language: number
  leadership: number
  adaptability: number
  execution: number
}

export interface ProfileAggregateResult {
  sampleSize: number
  updatedAt: string
  averageScores: AbilityScores
  averageProgress: {
    completenessScore: number
    competitivenessScore: number
  }
}

export interface AdminJobItem {
  jobId: string
  jobName: string
  city: string
  level: 'junior' | 'mid' | 'middle' | 'senior' | 'lead'
  updatedAt: string
  status: 'active' | 'expiring' | 'expired'
  source: string
}

export interface OperationLogItem {
  id: string
  type: 'import' | 'edge' | 'cleanup' | 'sync'
  status: 'running' | 'succeeded' | 'failed'
  content: string
  time: string
}

interface AdminJobsSummaryResult {
  totalJobs: number
  taxonomyJobCount?: number
  sourceType?: string
  sourceFile?: string
  sourceUpdatedAt?: string
  updatedAt?: string
}

interface AdminJobsListResult {
  total: number
  sourceType?: string
  sourceFile?: string
  expireDays?: number
  list: Array<{
    jobId: string
    jobName: string
    city: string
    level: string
    updatedAt: string
    updatedAtNormalized?: string
    status: string
    source: string
  }>
}

interface GovernanceCleanupPreviewResult {
  expireDays: number
  generatedAt?: string
  sourceType?: string
  total: number
  list: Array<{
    jobId: string
    jobName: string
    city: string
    level: string
    updatedAt: string
    updatedAtNormalized?: string
    status: string
    source: string
  }>
}

interface GovernanceManualEdgePayload {
  sourceJobId: string
  targetJobId: string
  relationType: 'promotion' | 'transition'
  reason: string
}

interface GovernanceManualEdgeResult {
  edge: {
    id: string
    sourceJobId: string
    targetJobId: string
    sourceJobName: string
    targetJobName: string
    relationType: 'promotion' | 'transition'
    reason: string
    similarity: number
    difficulty?: string
    createdAt: string
    source?: string
  }
  totalManualEdges: number
}

interface ApiRequestOptions {
  method?: 'GET' | 'POST'
  body?: unknown
}

const DEFAULT_ADMIN_JOB_ROWS: AdminJobItem[] = [
  { jobId: 'j_20001', jobName: '前端开发工程师', city: '西安', level: 'junior', updatedAt: '2026-02-20 10:12', status: 'active', source: 'BOSS直聘' },
  { jobId: 'j_20002', jobName: '后端开发工程师', city: '西安', level: 'middle', updatedAt: '2026-02-18 21:34', status: 'active', source: '智联招聘' },
  { jobId: 'j_20003', jobName: '算法工程师', city: '上海', level: 'senior', updatedAt: '2026-02-16 17:09', status: 'expiring', source: '拉勾' },
  { jobId: 'j_20004', jobName: '测试开发工程师', city: '成都', level: 'junior', updatedAt: '2025-12-11 08:46', status: 'expired', source: 'BOSS直聘' },
  { jobId: 'j_20005', jobName: '数据分析师', city: '杭州', level: 'middle', updatedAt: '2026-02-21 09:10', status: 'active', source: '猎聘' },
  { jobId: 'j_20006', jobName: '大模型应用工程师', city: '北京', level: 'middle', updatedAt: '2026-01-28 14:28', status: 'expiring', source: '智联招聘' },
]

const DEFAULT_CLEANUP_PREVIEW_ROWS: AdminJobItem[] = [
  { jobId: 'j_expired_001', jobName: '旧版客户端维护工程师', city: '西安', level: 'junior', updatedAt: '2025-12-11', status: 'expired', source: 'legacy-cache' },
  { jobId: 'j_expired_002', jobName: '传统运维支持工程师', city: '成都', level: 'middle', updatedAt: '2025-11-26', status: 'expired', source: 'legacy-cache' },
]

const refreshLoading = ref(false)
const importRunning = ref(false)
const edgeRunning = ref(false)
const cleanupRunning = ref(false)

const importProgress = ref(0)
const edgeProgress = ref(0)
const cleanupProgress = ref(0)

const selectedImportFile = ref<File | null>(null)
const detailedJobTotal = ref<number | null>(null)
const detailedJobSource = ref('')
const importedJobDelta = ref(0)

const importForm = reactive({
  batchName: `春招岗位批次-${new Date().getMonth() + 1}`,
  sourceFile: '',
  rows: 1200,
})

const cleanupForm = reactive({
  expireDays: 45,
})

const keyword = ref('')
const statusFilter = ref<'all' | 'active' | 'expiring' | 'expired'>('all')

const operationLogs = ref<OperationLogItem[]>([])

const jobRows = ref<AdminJobItem[]>([...DEFAULT_ADMIN_JOB_ROWS])

const publicOverview = ref<HomePublicOverviewResult | null>(null)
const profileAggregate = ref<ProfileAggregateResult | null>(null)
const dataSourceHint = ref('')
const dashboardInitialized = ref(false)

const metricCards = computed(() => {
  const list = publicOverview.value?.overviewMetrics || []
  return list.slice(0, 4)
})

const allowedImportFileExt = new Set(['xls', 'xlsx', 'csv'])
const maxImportFileSizeBytes = 20 * 1024 * 1024

function resolveFileExt(fileName: string) {
  const text = String(fileName || '').trim()
  if (!text.includes('.')) return ''
  return text.slice(text.lastIndexOf('.') + 1).toLowerCase()
}

function formatFileSize(size: number) {
  const bytes = Number(size || 0)
  if (!Number.isFinite(bytes) || bytes <= 0) return '0 B'
  if (bytes >= 1024 * 1024) {
    return `${(bytes / (1024 * 1024)).toFixed(2)} MB`
  }
  if (bytes >= 1024) {
    return `${(bytes / 1024).toFixed(1)} KB`
  }
  return `${Math.round(bytes)} B`
}

const importSelectedFileMeta = computed(() => {
  const file = selectedImportFile.value
  if (!file) return null
  return {
    name: file.name,
    ext: resolveFileExt(file.name),
    size: file.size,
    sizeLabel: formatFileSize(file.size),
  }
})

const jobCountSourceLabel = computed(() => {
  const source = String(detailedJobSource.value || '').trim()
  if (!source) return '岗位总量来源：岗位库样例数据'
  return `岗位总量来源：${source}`
})

const jobCountSummary = computed(() => {
  const rows = jobRows.value
  const rowTotal = rows.length
  const rowActive = rows.filter(item => item.status === 'active').length
  const rowExpiring = rows.filter(item => item.status === 'expiring').length
  const rowExpired = rows.filter(item => item.status === 'expired').length

  const baseTotal = Number(detailedJobTotal.value || 0) > 0
    ? Number(detailedJobTotal.value || 0)
    : rowTotal
  const importDelta = Math.max(0, Number(importedJobDelta.value || 0))

  if (baseTotal === rowTotal && importDelta === 0) {
    return {
      total: rowTotal,
      active: rowActive,
      expiring: rowExpiring,
      expired: rowExpired,
    }
  }

  if (!rowTotal) {
    return {
      total: baseTotal + importDelta,
      active: baseTotal + importDelta,
      expiring: 0,
      expired: 0,
    }
  }

  const baselineActive = Math.round((rowActive / rowTotal) * baseTotal)
  const baselineExpiring = Math.round((rowExpiring / rowTotal) * baseTotal)

  const total = Math.max(0, baseTotal + importDelta)
  let active = Math.max(0, baselineActive + importDelta)
  let expiring = Math.max(0, baselineExpiring)
  if (active > total) active = total
  if (active + expiring > total) {
    expiring = Math.max(0, total - active)
  }
  const expired = Math.max(0, total - active - expiring)

  return {
    total,
    active,
    expiring,
    expired,
  }
})

const filteredJobs = computed(() => {
  const text = keyword.value.trim().toLowerCase()
  return jobRows.value.filter((item) => {
    const matchStatus = statusFilter.value === 'all' || item.status === statusFilter.value
    if (!matchStatus) return false
    if (!text) return true
    return [item.jobName, item.city, item.source, item.jobId]
      .join(' ')
      .toLowerCase()
      .includes(text)
  })
})

const completionRatio = computed(() => {
  const done = operationLogs.value.filter(item => item.status === 'succeeded').length
  const total = operationLogs.value.length
  if (!total) return 100
  return Math.max(1, Math.round((done / total) * 100))
})

let importTimer: ReturnType<typeof setInterval> | null = null
let edgeTimer: ReturnType<typeof setInterval> | null = null
let cleanupTimer: ReturnType<typeof setInterval> | null = null

function nowTime() {
  return new Date().toLocaleTimeString('zh-CN', { hour12: false })
}

function clearTimer(timer: ReturnType<typeof setInterval> | null) {
  if (!timer) return null
  clearInterval(timer)
  return null
}

function addLog(type: OperationLogItem['type'], status: OperationLogItem['status'], content: string) {
  operationLogs.value.unshift({
    id: `${type}_${Date.now()}_${Math.random().toString(16).slice(2)}`,
    type,
    status,
    content,
    time: nowTime(),
  })
}

function resolveLevelLabel(level: AdminJobItem['level']) {
  const normalized = String(level || '').toLowerCase().trim()
  if (normalized === 'junior') return '初级'
  if (normalized === 'middle' || normalized === 'mid') return '中级'
  if (normalized === 'lead') return '资深'
  return '高级'
}

function resolveStatusTagType(status: AdminJobItem['status']) {
  if (status === 'active') return 'success'
  if (status === 'expiring') return 'warning'
  return 'danger'
}

function resolveStatusLabel(status: AdminJobItem['status']) {
  if (status === 'active') return '有效'
  if (status === 'expiring') return '即将过期'
  return '已过期'
}

function normalizeAdminJobLevel(level: unknown): AdminJobItem['level'] {
  const normalized = String(level || '').trim().toLowerCase()
  if (normalized === 'junior') return 'junior'
  if (normalized === 'mid') return 'mid'
  if (normalized === 'middle') return 'middle'
  if (normalized === 'lead') return 'lead'
  return 'senior'
}

function normalizeAdminJobStatus(status: unknown): AdminJobItem['status'] {
  const normalized = String(status || '').trim().toLowerCase()
  if (normalized === 'expiring') return 'expiring'
  if (normalized === 'expired') return 'expired'
  return 'active'
}

function mapAdminJobItemFromApi(raw: {
  jobId: string
  jobName: string
  city: string
  level: string
  updatedAt: string
  status: string
  source: string
}): AdminJobItem | null {
  const jobId = String(raw?.jobId || '').trim()
  const jobName = String(raw?.jobName || '').trim()
  if (!jobId || !jobName) return null

  return {
    jobId,
    jobName,
    city: String(raw?.city || '').trim() || '未知',
    level: normalizeAdminJobLevel(raw?.level),
    updatedAt: String(raw?.updatedAt || '').trim() || '未知',
    status: normalizeAdminJobStatus(raw?.status),
    source: String(raw?.source || '').trim() || 'newData/runtime',
  }
}

function mapAdminJobsFromApi(list: Array<{
  jobId: string
  jobName: string
  city: string
  level: string
  updatedAt: string
  status: string
  source: string
}>): AdminJobItem[] {
  const rows: AdminJobItem[] = []
  const seen = new Set<string>()

  list.forEach((item) => {
    const normalized = mapAdminJobItemFromApi(item)
    if (!normalized) return
    if (seen.has(normalized.jobId)) return
    seen.add(normalized.jobId)
    rows.push(normalized)
  })

  return rows
}

function buildApiUrl(pathname: string) {
  const base = String(import.meta.env.VITE_API_BASE_URL || '/api').replace(/\/$/, '')
  if (/^https?:\/\//.test(base)) {
    return `${base}${pathname}`
  }
  if (base.startsWith('/')) {
    return `${base}${pathname}`
  }
  return `/${base}${pathname}`
}

async function requestApi<T>(pathname: string, withToken = false, options?: ApiRequestOptions) {
  const headers: Record<string, string> = {
    'Content-Type': 'application/json',
  }

  if (withToken) {
    const token = getToken()
    if (token) {
      headers.token = token
      headers.Authorization = `Bearer ${token}`
    }
  }

  const method = String(options?.method || 'GET').toUpperCase() as 'GET' | 'POST'
  const response = await fetch(buildApiUrl(pathname), {
    method,
    headers,
    body: method === 'GET' ? undefined : JSON.stringify(options?.body || {}),
  })

  const payload = (await response.json()) as ApiResponse<T>
  if (!response.ok || Number(payload.code) >= 400) {
    throw new Error(String(payload.msg || '请求失败'))
  }
  return payload.data as T
}

function fallbackPublicOverview(): HomePublicOverviewResult {
  return {
    overviewMetrics: [
      {
        key: 'usersServed',
        label: '累计服务用户',
        value: 12860,
        unit: '人',
        trend: { direction: 'up', delta: 12.8, period: '近7天' },
      },
      {
        key: 'pathsGenerated',
        label: '协助规划职业路径数',
        value: 32450,
        unit: '条',
        trend: { direction: 'up', delta: 9.4, period: '近7天' },
      },
      {
        key: 'reportsCompleted',
        label: '已生成生涯报告',
        value: 7430,
        unit: '份',
        trend: { direction: 'up', delta: 10.1, period: '近7天' },
      },
      {
        key: 'helpMatched',
        label: '完成人岗匹配',
        value: 9168,
        unit: '次',
        trend: { direction: 'up', delta: 7.2, period: '近7天' },
      },
    ],
    charts: {
      matchScoreTrend: [
        { date: '02-15', value: 72 },
        { date: '02-16', value: 73 },
        { date: '02-17', value: 74 },
        { date: '02-18', value: 75 },
        { date: '02-19', value: 76 },
        { date: '02-20', value: 77 },
        { date: '02-21', value: 78 },
      ],
      pathTypeDistribution: [
        { type: '技术深耕', count: 42 },
        { type: '跨域转型', count: 26 },
        { type: '管理发展', count: 18 },
      ],
      pipelineFunnel: [
        { stage: '上传简历', count: 1000 },
        { stage: '完成画像', count: 870 },
        { stage: '完成匹配', count: 710 },
        { stage: '生成报告', count: 560 },
      ],
    },
    updatedAt: new Date().toISOString(),
  }
}

function fallbackAggregate(): ProfileAggregateResult {
  return {
    sampleSize: 4287,
    updatedAt: new Date().toISOString(),
    averageScores: {
      professionalSkill: 71,
      certificate: 58,
      innovation: 66,
      internalMotivation: 70,
      learning: 74,
      stressTolerance: 69,
      communication: 72,
      internship: 64,
      language: 63,
      leadership: 57,
      adaptability: 68,
      execution: 65,
    },
    averageProgress: {
      completenessScore: 68,
      competitivenessScore: 70,
    },
  }
}

async function refreshDashboard() {
  if (refreshLoading.value) return
  refreshLoading.value = true
  dataSourceHint.value = ''

  try {
    const publicPromise = requestApi<HomePublicOverviewResult>('/analytics/home/overview-public', false)
    const aggregatePromise = requestApi<ProfileAggregateResult>('/analytics/student-profiles/aggregate', false)
    const jobsSummaryPromise = requestApi<AdminJobsSummaryResult>('/admin/jobs/summary', false)
    const jobsListPromise = requestApi<AdminJobsListResult>('/admin/jobs/list?limit=12000&expireDays=45', false)
    const [publicResult, aggregateResult, jobsSummaryResult, jobsListResult] = await Promise.allSettled([
      publicPromise,
      aggregatePromise,
      jobsSummaryPromise,
      jobsListPromise,
    ])

    if (publicResult.status === 'fulfilled') {
      publicOverview.value = publicResult.value
    } else {
      publicOverview.value = fallbackPublicOverview()
      dataSourceHint.value = '首页看板接口调用异常，已加载备用数据。'
    }

    if (aggregateResult.status === 'fulfilled') {
      profileAggregate.value = aggregateResult.value
    } else {
      profileAggregate.value = fallbackAggregate()
      dataSourceHint.value = dataSourceHint.value
        ? `${dataSourceHint.value} 学生画像聚合接口访问受限，已加载备用数据。`
        : '学生画像聚合接口访问受限，已加载备用数据。'
    }

    if (jobsSummaryResult.status === 'fulfilled') {
      const totalJobs = Number(jobsSummaryResult.value?.totalJobs || 0)
      detailedJobTotal.value = totalJobs > 0 ? totalJobs : null
      detailedJobSource.value = String(jobsSummaryResult.value?.sourceFile || '').trim()
    } else {
      detailedJobTotal.value = null
      detailedJobSource.value = ''
      dataSourceHint.value = dataSourceHint.value
        ? `${dataSourceHint.value} 岗位明细库统计接口访问受限，岗位总量已回退为样例数据。`
        : '岗位明细库统计接口访问受限，岗位总量已回退为样例数据。'
    }

    if (jobsListResult.status === 'fulfilled') {
      const rows = mapAdminJobsFromApi(Array.isArray(jobsListResult.value?.list) ? jobsListResult.value.list : [])
      if (rows.length) {
        jobRows.value = rows
      } else {
        jobRows.value = [...DEFAULT_ADMIN_JOB_ROWS]
        dataSourceHint.value = dataSourceHint.value
          ? `${dataSourceHint.value} 管理岗位列表为空，已回退为样例数据。`
          : '管理岗位列表为空，已回退为样例数据。'
      }
    } else {
      jobRows.value = [...DEFAULT_ADMIN_JOB_ROWS]
      dataSourceHint.value = dataSourceHint.value
        ? `${dataSourceHint.value} 管理岗位列表接口访问失败，已回退为样例数据。`
        : '管理岗位列表接口访问失败，已回退为样例数据。'
    }

    addLog('sync', 'succeeded', '用户使用看板数据已刷新')
  } catch {
    publicOverview.value = fallbackPublicOverview()
    profileAggregate.value = fallbackAggregate()
    detailedJobTotal.value = null
    detailedJobSource.value = ''
    jobRows.value = [...DEFAULT_ADMIN_JOB_ROWS]
    dataSourceHint.value = '看板数据刷新异常，已加载备用数据。'
    addLog('sync', 'failed', '看板数据刷新失败，已切换备用数据')
  } finally {
    refreshLoading.value = false
    dashboardInitialized.value = true
  }
}

async function initAdminConsole() {
  if (dashboardInitialized.value || refreshLoading.value) return
  await refreshDashboard()
}

function clearImportUploadFile() {
  selectedImportFile.value = null
  importForm.sourceFile = ''
}

function setImportUploadFile(file: File | null) {
  if (!file) {
    clearImportUploadFile()
    return {
      ok: false,
      message: '未检测到可上传的文件',
    }
  }

  const ext = resolveFileExt(file.name)
  if (!allowedImportFileExt.has(ext)) {
    return {
      ok: false,
      message: '仅支持 .xls / .xlsx / .csv 文件',
    }
  }

  if (Number(file.size || 0) > maxImportFileSizeBytes) {
    return {
      ok: false,
      message: `文件体积为 ${formatFileSize(file.size)}，请控制在 20 MB 以内`,
    }
  }

  selectedImportFile.value = file
  importForm.sourceFile = file.name
  return {
    ok: true,
    message: `已选择导入文件：${file.name}`,
  }
}

function startImportJobs() {
  if (importRunning.value) {
    return {
      ok: false,
      message: '岗位导入任务正在进行中，请稍后再试',
    }
  }

  const file = selectedImportFile.value
  if (!file) {
    addLog('import', 'failed', '导入失败：未上传岗位数据文件')
    return {
      ok: false,
      message: '请先上传岗位数据文件（支持 .xls / .xlsx / .csv）',
    }
  }

  const ext = resolveFileExt(file.name)
  if (!allowedImportFileExt.has(ext)) {
    addLog('import', 'failed', `导入失败：文件格式不支持（${file.name}）`)
    return {
      ok: false,
      message: '文件格式不支持，请上传 .xls / .xlsx / .csv 文件',
    }
  }

  if (Number(file.size || 0) > maxImportFileSizeBytes) {
    addLog('import', 'failed', `导入失败：文件体积超过限制（${file.name}）`)
    return {
      ok: false,
      message: `文件体积超限（${formatFileSize(file.size)}），请控制在 20 MB 以内`,
    }
  }

  importRunning.value = true
  importProgress.value = 0
  addLog('import', 'running', `开始导入岗位批次：${importForm.batchName}（${file.name}）`)

  importTimer = clearTimer(importTimer)
  importTimer = setInterval(() => {
    importProgress.value = Math.min(100, importProgress.value + 12)

    if (importProgress.value >= 100) {
      importRunning.value = false
      importTimer = clearTimer(importTimer)

      const nextId = `j_${String(20006 + jobRows.value.length + 1)}`
      jobRows.value.unshift({
        jobId: nextId,
        jobName: '智能体应用工程师',
        city: '深圳',
        level: 'middle',
        updatedAt: nowTime(),
        status: 'active',
        source: importForm.sourceFile,
      })

      importedJobDelta.value += Math.max(0, Number(importForm.rows || 0))

      addLog('import', 'succeeded', `岗位导入完成（${file.name}），新增 ${importForm.rows} 条数据`)
    }
  }, 300)

  return {
    ok: true,
    message: '岗位导入任务已创建',
  }
}

function startBuildEdges() {
  if (edgeRunning.value || importRunning.value) return
  edgeRunning.value = true
  edgeProgress.value = 0
  addLog('edge', 'running', '开始执行岗位关系边构建任务')

  edgeTimer = clearTimer(edgeTimer)
  edgeTimer = setInterval(() => {
    edgeProgress.value = Math.min(100, edgeProgress.value + 10)
    if (edgeProgress.value >= 100) {
      edgeRunning.value = false
      edgeTimer = clearTimer(edgeTimer)
      addLog('edge', 'succeeded', '关系边构建完成，新增 3562 条边')
    }
  }, 340)
}

function startCleanupExpiredJobs() {
  if (cleanupRunning.value) return
  cleanupRunning.value = true
  cleanupProgress.value = 0
  addLog('cleanup', 'running', `开始清理超过 ${cleanupForm.expireDays} 天未更新岗位`)

  cleanupTimer = clearTimer(cleanupTimer)
  cleanupTimer = setInterval(() => {
    cleanupProgress.value = Math.min(100, cleanupProgress.value + 16)
    if (cleanupProgress.value >= 100) {
      cleanupRunning.value = false
      cleanupTimer = clearTimer(cleanupTimer)

      const before = jobRows.value.length
      jobRows.value = jobRows.value.filter(item => item.status !== 'expired')
      const cleaned = before - jobRows.value.length

      addLog('cleanup', 'succeeded', `过期岗位清理完成，移除 ${cleaned} 条记录`)
    }
  }, 280)
}

async function previewCleanupExpiredJobs(expireDays: number) {
  const normalizedDays = Math.max(1, Math.round(Number(expireDays || cleanupForm.expireDays || 45)))

  try {
    const response = await requestApi<GovernanceCleanupPreviewResult>(
      `/admin/governance/cleanup-preview?expireDays=${normalizedDays}&limit=500`,
      false,
    )

    const list = mapAdminJobsFromApi(Array.isArray(response?.list) ? response.list : [])
    if (list.length) {
      return {
        ok: true,
        fallback: false,
        expireDays: Number(response?.expireDays || normalizedDays),
        generatedAt: String(response?.generatedAt || new Date().toISOString()),
        list,
      }
    }
  } catch {
    // Ignore and use fallback preview list.
  }

  const localExpired = jobRows.value.filter(item => item.status === 'expired')
  const fallbackRows = localExpired.length ? localExpired : [...DEFAULT_CLEANUP_PREVIEW_ROWS]

  return {
    ok: true,
    fallback: true,
    expireDays: normalizedDays,
    generatedAt: new Date().toISOString(),
    list: fallbackRows,
  }
}

async function createManualGovernanceEdge(payload: GovernanceManualEdgePayload) {
  const sourceJobId = String(payload?.sourceJobId || '').trim()
  const targetJobId = String(payload?.targetJobId || '').trim()
  const relationType = payload?.relationType === 'promotion' ? 'promotion' : 'transition'
  const reason = String(payload?.reason || '').trim()

  if (!sourceJobId || !targetJobId) {
    return {
      ok: false,
      message: '请选择起点岗位与目标岗位',
    }
  }
  if (sourceJobId === targetJobId) {
    return {
      ok: false,
      message: '起点岗位与目标岗位不能相同',
    }
  }
  if (reason.length < 4) {
    return {
      ok: false,
      message: '请至少填写4个字的关系构建理由',
    }
  }

  try {
    const response = await requestApi<GovernanceManualEdgeResult>(
      '/admin/governance/edges/manual',
      false,
      {
        method: 'POST',
        body: {
          sourceJobId,
          targetJobId,
          relationType,
          reason,
        },
      },
    )

    addLog('edge', 'succeeded', `手动关系边创建成功：${response.edge.sourceJobName} -> ${response.edge.targetJobName}`)

    return {
      ok: true,
      data: response,
    }
  } catch (error) {
    const message = error instanceof Error ? error.message : '手动关系边创建失败'
    addLog('edge', 'failed', `手动关系边创建失败：${message}`)
    return {
      ok: false,
      message,
    }
  }
}

function resetJobFilter() {
  keyword.value = ''
  statusFilter.value = 'all'
}

function disposeAdminConsole() {
  importTimer = clearTimer(importTimer)
  edgeTimer = clearTimer(edgeTimer)
  cleanupTimer = clearTimer(cleanupTimer)
}

export function useAdminConsole() {
  return {
    refreshLoading,
    importRunning,
    edgeRunning,
    cleanupRunning,
    importProgress,
    edgeProgress,
    cleanupProgress,
    importForm,
    importSelectedFileMeta,
    cleanupForm,
    keyword,
    statusFilter,
    operationLogs,
    jobRows,
    publicOverview,
    profileAggregate,
    dataSourceHint,
    metricCards,
    jobCountSourceLabel,
    jobCountSummary,
    filteredJobs,
    completionRatio,
    resolveLevelLabel,
    resolveStatusTagType,
    resolveStatusLabel,
    refreshDashboard,
    initAdminConsole,
    setImportUploadFile,
    clearImportUploadFile,
    startImportJobs,
    startBuildEdges,
    startCleanupExpiredJobs,
    previewCleanupExpiredJobs,
    createManualGovernanceEdge,
    resetJobFilter,
    disposeAdminConsole,
  }
}
