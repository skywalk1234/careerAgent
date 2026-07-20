<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import type { UploadFile, UploadFiles, UploadInstance } from 'element-plus'
import { Delete, Download, Link, UploadFilled } from '@element-plus/icons-vue'
import { useAdminConsole } from '../../composables/useAdminConsole'

const {
  importForm,
  importSelectedFileMeta,
  cleanupForm,
  importRunning,
  edgeRunning,
  cleanupRunning,
  importProgress,
  edgeProgress,
  cleanupProgress,
  jobRows,
  setImportUploadFile,
  clearImportUploadFile,
  startImportJobs,
  startBuildEdges,
  startCleanupExpiredJobs,
  previewCleanupExpiredJobs,
  createManualGovernanceEdge,
} = useAdminConsole()

const importUploadRef = ref<UploadInstance | null>(null)

type GovernancePanelKey = 'import' | 'edge' | 'cleanup'

interface ImportSampleJob {
  jobId: string
  jobName: string
  level: string
  city: string
  source: string
}

interface ImportCategoryTemplate {
  key: string
  category: string
  ratio: number
  sampleJobs: ImportSampleJob[]
}

interface ImportCategorySummary {
  key: string
  category: string
  jobCount: number
  sampleJobs: ImportSampleJob[]
}

interface ImportSnapshot {
  batchName: string
  sourceFile: string
  importedRows: number
  importedAt: string
  categories: ImportCategorySummary[]
}

interface EdgePromotionPath {
  id: string
  pathLabel: string
  similarity: number
  sampleCount: number
}

interface EdgeTransitionRelation {
  id: string
  source: string
  target: string
  similarity: number
}

interface ManualEdgeRelation {
  id: string
  sourceJobId: string
  targetJobId: string
  sourceJobName: string
  targetJobName: string
  relationType: 'promotion' | 'transition'
  reason: string
  similarity: number
  createdAt: string
  source?: string
}

interface EdgeSnapshot {
  builtAt: string
  promotionPaths: EdgePromotionPath[]
  transitionRelations: EdgeTransitionRelation[]
  manualRelations: ManualEdgeRelation[]
}

interface CleanupRemovedJob {
  jobId: string
  jobName: string
  city: string
  level: string
  source: string
}

interface CleanupSnapshot {
  cleanedAt: string
  expireDays: number
  removedJobs: CleanupRemovedJob[]
}

interface GovernanceLogPayload {
  importSnapshot?: ImportSnapshot
  edgeSnapshot?: EdgeSnapshot
  cleanupSnapshot?: CleanupSnapshot
}

interface GovernanceLogItem {
  id: string
  type: GovernancePanelKey
  status: 'running' | 'succeeded' | 'failed'
  title: string
  summary: string
  time: string
  payload?: GovernanceLogPayload
}

interface ManualEdgeFormState {
  sourceJobId: string
  targetJobId: string
  relationType: 'promotion' | 'transition'
  reason: string
}

interface CleanupPreviewMeta {
  generatedAt: string
  expireDays: number
  fallback: boolean
}

const PANEL_OPTIONS: Array<{ key: GovernancePanelKey; label: string; description: string }> = [
  {
    key: 'import',
    label: '岗位导入任务',
    description: '上传并导入岗位数据，生成类别统计与明细。',
  },
  {
    key: 'edge',
    label: '关系边构建任务',
    description: '基于新导入岗位生成晋升与换岗关系。',
  },
  {
    key: 'cleanup',
    label: '过期岗位清理任务',
    description: '按过期阈值清理岗位并保留清理明细。',
  },
]

const IMPORT_CATEGORY_TEMPLATES: ImportCategoryTemplate[] = [
  {
    key: 'java-backend',
    category: 'Java后端开发',
    ratio: 0.34,
    sampleJobs: [
      {
        jobId: 'cj_backend_Java__2',
        jobName: 'Java_初级软件开发工程师',
        level: '初级',
        city: '西安',
        source: 'newData/step2',
      },
      {
        jobId: 'cj_backend_Java__3',
        jobName: 'Java_中高级后端开发工程师',
        level: '中高级',
        city: '杭州',
        source: 'newData/step2',
      },
      {
        jobId: 'cj_backend_Java',
        jobName: 'Java_云原生与分布式架构工程师',
        level: '高级',
        city: '上海',
        source: 'newData/step2',
      },
    ],
  },
  {
    key: 'frontend',
    category: '前端开发',
    ratio: 0.26,
    sampleJobs: [
      {
        jobId: 'cj_frontend_Junior',
        jobName: '前端开发_初级前端开发工程师',
        level: '初级',
        city: '西安',
        source: 'newData/step2',
      },
      {
        jobId: 'cj_frontend_Middle',
        jobName: '前端开发_中高级前端开发工程师',
        level: '中高级',
        city: '杭州',
        source: 'newData/step2',
      },
      {
        jobId: 'cj_frontend_Arch',
        jobName: '前端开发_前端架构工程师',
        level: '高级',
        city: '北京',
        source: 'newData/step2',
      },
    ],
  },
  {
    key: 'cpp',
    category: 'C/C++开发',
    ratio: 0.2,
    sampleJobs: [
      {
        jobId: 'cj_cpp_Junior',
        jobName: 'C/C++_初级开发工程师',
        level: '初级',
        city: '成都',
        source: 'newData/step2',
      },
      {
        jobId: 'cj_cpp_Middle',
        jobName: 'C/C++_中高级系统开发工程师',
        level: '中高级',
        city: '西安',
        source: 'newData/step2',
      },
      {
        jobId: 'cj_cpp_Arch',
        jobName: 'C/C++_架构开发工程师',
        level: '高级',
        city: '深圳',
        source: 'newData/step2',
      },
    ],
  },
  {
    key: 'data',
    category: '数据与算法',
    ratio: 0.2,
    sampleJobs: [
      {
        jobId: 'cj_data_Analyst',
        jobName: '数据分析工程师',
        level: '初级',
        city: '杭州',
        source: 'newData/step2',
      },
      {
        jobId: 'cj_data_Algo',
        jobName: '算法工程师',
        level: '中高级',
        city: '上海',
        source: 'newData/step2',
      },
      {
        jobId: 'cj_data_Platform',
        jobName: '数据平台工程师',
        level: '高级',
        city: '北京',
        source: 'newData/step2',
      },
    ],
  },
]

const FALLBACK_CLEANUP_JOBS: CleanupRemovedJob[] = [
  {
    jobId: 'j_expired_001',
    jobName: '旧版客户端维护工程师',
    city: '西安',
    level: '初级',
    source: 'legacy-cache',
  },
  {
    jobId: 'j_expired_002',
    jobName: '传统运维支持工程师',
    city: '成都',
    level: '中级',
    source: 'legacy-cache',
  },
]

const activePanel = ref<GovernancePanelKey>('import')
const panelTranslateIndex = computed(() => {
  const index = PANEL_OPTIONS.findIndex(item => item.key === activePanel.value)
  return index < 0 ? 0 : index
})

const importSnapshot = ref<ImportSnapshot | null>(null)
const edgeSnapshot = ref<EdgeSnapshot | null>(null)
const cleanupSnapshot = ref<CleanupSnapshot | null>(null)

const categoryDetailVisible = ref(false)
const activeCategoryDetail = ref<ImportCategorySummary | null>(null)

const governanceLogs = ref<GovernanceLogItem[]>([])
const recentLogs = computed(() => governanceLogs.value.slice(0, 20))

const logDetailVisible = ref(false)
const activeLog = ref<GovernanceLogItem | null>(null)

const pendingImportContext = ref<{ batchName: string; sourceFile: string; rows: number } | null>(null)
const pendingEdgeContext = ref<{ startedAt: string } | null>(null)
const pendingCleanupContext = ref<{ expireDays: number; removedCandidates: CleanupRemovedJob[] } | null>(null)

const manualEdgeSubmitting = ref(false)
const manualEdgeForm = ref<ManualEdgeFormState>({
  sourceJobId: '',
  targetJobId: '',
  relationType: 'promotion',
  reason: '',
})

const cleanupPreviewLoading = ref(false)
const cleanupPreviewRows = ref<CleanupRemovedJob[]>([])
const cleanupPreviewMeta = ref<CleanupPreviewMeta | null>(null)

const edgeActionDisabled = computed(() => !importSnapshot.value || importRunning.value || edgeRunning.value)
const edgeActionHint = computed(() => {
  if (importRunning.value) return '岗位导入执行中，请等待导入完成。'
  if (!importSnapshot.value) return '请先完成岗位导入，再执行关系边构建。'
  return '导入完成，可执行关系边构建。'
})

const importSummaryRows = computed(() => {
  const summary = importSnapshot.value
  if (!summary) return [] as ImportCategorySummary[]
  return [...summary.categories].sort((a, b) => b.jobCount - a.jobCount)
})

const relationJobOptions = computed(() => {
  return jobRows.value.map(item => ({
    value: item.jobId,
    label: `${item.jobName}（${resolveLevelText(item.level)} · ${item.city}）`,
  }))
})

function nowDateTimeText() {
  return new Date().toLocaleString('zh-CN', { hour12: false })
}

function pushGovernanceLog(
  type: GovernancePanelKey,
  status: GovernanceLogItem['status'],
  title: string,
  summary: string,
  payload?: GovernanceLogPayload,
) {
  governanceLogs.value.unshift({
    id: `${type}_${Date.now()}_${Math.random().toString(16).slice(2)}`,
    type,
    status,
    title,
    summary,
    time: nowDateTimeText(),
    payload,
  })
}

function resolveStatusTagType(status: GovernanceLogItem['status']) {
  if (status === 'running') return 'warning'
  if (status === 'failed') return 'danger'
  return 'success'
}

function resolveStatusText(status: GovernanceLogItem['status']) {
  if (status === 'running') return '进行中'
  if (status === 'failed') return '失败'
  return '成功'
}

function resolveLogTypeText(type: GovernancePanelKey) {
  if (type === 'import') return '岗位导入'
  if (type === 'edge') return '关系边构建'
  return '过期清理'
}

function resolveLevelText(level: string) {
  const normalized = String(level || '').trim().toLowerCase()
  if (normalized === 'junior') return '初级'
  if (normalized === 'mid' || normalized === 'middle') return '中级'
  if (normalized === 'lead') return '资深'
  return '高级'
}

function resetUploadState() {
  importUploadRef.value?.clearFiles()
  clearImportUploadFile()
}

function buildImportSnapshot(batchName: string, sourceFile: string, rows: number) {
  const totalRows = Math.max(100, Math.round(Number(rows || 0)))
  let assigned = 0

  const categories = IMPORT_CATEGORY_TEMPLATES.map((item, index) => {
    if (index === IMPORT_CATEGORY_TEMPLATES.length - 1) {
      const remaining = Math.max(0, totalRows - assigned)
      return {
        key: item.key,
        category: item.category,
        jobCount: remaining,
        sampleJobs: item.sampleJobs,
      }
    }

    const count = Math.max(1, Math.round(totalRows * item.ratio))
    assigned += count
    return {
      key: item.key,
      category: item.category,
      jobCount: count,
      sampleJobs: item.sampleJobs,
    }
  })

  return {
    batchName,
    sourceFile,
    importedRows: totalRows,
    importedAt: nowDateTimeText(),
    categories,
  }
}

function buildEdgeSnapshot(): EdgeSnapshot {
  return {
    builtAt: nowDateTimeText(),
    promotionPaths: [
      {
        id: 'pp_001',
        pathLabel: 'Java_初级软件开发工程师 -> Java_中高级后端开发工程师 -> Java_云原生与分布式架构工程师',
        similarity: 0.867,
        sampleCount: 326,
      },
      {
        id: 'pp_002',
        pathLabel: '前端开发_初级前端开发工程师 -> 前端开发_中高级前端开发工程师 -> 前端开发_前端架构工程师',
        similarity: 0.823,
        sampleCount: 241,
      },
      {
        id: 'pp_003',
        pathLabel: 'C/C++_初级开发工程师 -> C/C++_中高级系统开发工程师 -> C/C++_架构开发工程师',
        similarity: 0.801,
        sampleCount: 198,
      },
    ],
    transitionRelations: [
      {
        id: 'tr_001',
        source: 'Java_中高级后端开发工程师',
        target: 'Go后端开发工程师',
        similarity: 0.742,
      },
      {
        id: 'tr_002',
        source: '前端开发_中高级前端开发工程师',
        target: '全栈开发工程师',
        similarity: 0.731,
      },
      {
        id: 'tr_003',
        source: 'C/C++_中高级系统开发工程师',
        target: '嵌入式开发工程师',
        similarity: 0.768,
      },
      {
        id: 'tr_004',
        source: '数据分析工程师',
        target: '商业分析师',
        similarity: 0.716,
      },
    ],
    manualRelations: [],
  }
}

function buildCleanupSnapshot(expireDays: number, removedCandidates: CleanupRemovedJob[]) {
  const removedJobs = removedCandidates.length ? removedCandidates : FALLBACK_CLEANUP_JOBS
  return {
    cleanedAt: nowDateTimeText(),
    expireDays,
    removedJobs,
  }
}

function formatSampleJobNames(sampleJobs: ImportSampleJob[], limit?: number) {
  const rows = typeof limit === 'number' ? sampleJobs.slice(0, limit) : sampleJobs
  return rows.map(item => item.jobName).join(' / ')
}

function handleImportFileChange(file: UploadFile) {
  const raw = file.raw as File | undefined
  if (!raw) {
    ElMessage.error('读取上传文件失败，请重试')
    resetUploadState()
    return
  }

  const result = setImportUploadFile(raw)
  if (!result.ok) {
    ElMessage.warning(result.message)
    resetUploadState()
    return
  }

  ElMessage.success(result.message)
}

function handleImportFileExceed(files: File[] | UploadFiles) {
  const list = Array.isArray(files) ? files : []
  const latest = list[list.length - 1]
  if (!latest) return

  const raw = (latest as UploadFile).raw as File | undefined
  const candidate = raw || (latest as File)
  if (!(candidate instanceof File)) {
    ElMessage.warning('读取上传文件失败，请重新选择')
    resetUploadState()
    return
  }

  importUploadRef.value?.clearFiles()
  const result = setImportUploadFile(candidate)
  if (!result.ok) {
    ElMessage.warning(result.message)
    resetUploadState()
    return
  }

  ElMessage.success(`已切换导入文件：${candidate.name}`)
}

function handleClearImportFile() {
  resetUploadState()
  ElMessage.info('已移除待导入文件')
}

function handleImportJobs() {
  const result = startImportJobs()
  if (!result?.ok) {
    ElMessage.warning(result?.message || '岗位导入任务创建失败')
    return
  }

  const sourceFile = importSelectedFileMeta.value?.name || importForm.sourceFile || 'unknown-file'
  pendingImportContext.value = {
    batchName: String(importForm.batchName || '').trim() || `岗位导入批次-${Date.now()}`,
    sourceFile,
    rows: Math.max(100, Number(importForm.rows || 0)),
  }

  pushGovernanceLog(
    'import',
    'running',
    '岗位导入任务已启动',
    `批次 ${pendingImportContext.value.batchName} 正在导入，来源文件：${sourceFile}`,
  )
  ElMessage.success('岗位导入任务已创建')
}

function handleBuildEdges() {
  if (!importSnapshot.value) {
    ElMessage.warning('请先完成岗位导入，再执行关系边构建')
    return
  }
  if (importRunning.value) {
    ElMessage.warning('岗位导入进行中，请稍后执行关系边构建')
    return
  }
  if (edgeRunning.value) {
    ElMessage.warning('关系边构建任务进行中，请稍后查看结果')
    return
  }

  pendingEdgeContext.value = {
    startedAt: nowDateTimeText(),
  }

  pushGovernanceLog(
    'edge',
    'running',
    '关系边构建任务已启动',
    `基于最近导入批次 ${importSnapshot.value.batchName} 执行关系构建。`,
  )

  startBuildEdges()
  if (!edgeRunning.value) {
    pendingEdgeContext.value = null
    return
  }

  ElMessage.success('关系边构建任务已创建')
}

function mapManualEdgeFromApi(raw: {
  id: string
  sourceJobId: string
  targetJobId: string
  sourceJobName: string
  targetJobName: string
  relationType: 'promotion' | 'transition'
  reason: string
  similarity: number
  createdAt: string
  source?: string
}): ManualEdgeRelation {
  return {
    id: String(raw.id || `manual_edge_${Date.now()}`),
    sourceJobId: String(raw.sourceJobId || '').trim(),
    targetJobId: String(raw.targetJobId || '').trim(),
    sourceJobName: String(raw.sourceJobName || '').trim(),
    targetJobName: String(raw.targetJobName || '').trim(),
    relationType: raw.relationType === 'promotion' ? 'promotion' : 'transition',
    reason: String(raw.reason || '').trim(),
    similarity: Number(raw.similarity || 0),
    createdAt: String(raw.createdAt || nowDateTimeText()),
    source: String(raw.source || 'admin-manual').trim(),
  }
}

async function handleCreateManualEdge() {
  const payload = {
    sourceJobId: String(manualEdgeForm.value.sourceJobId || '').trim(),
    targetJobId: String(manualEdgeForm.value.targetJobId || '').trim(),
    relationType: manualEdgeForm.value.relationType,
    reason: String(manualEdgeForm.value.reason || '').trim(),
  }

  if (!payload.sourceJobId || !payload.targetJobId) {
    ElMessage.warning('请选择起点岗位和目标岗位')
    return
  }
  if (payload.sourceJobId === payload.targetJobId) {
    ElMessage.warning('起点岗位与目标岗位不能相同')
    return
  }
  if (payload.reason.length < 4) {
    ElMessage.warning('请至少填写4个字的关系构建理由')
    return
  }

  manualEdgeSubmitting.value = true
  try {
    const result = await createManualGovernanceEdge(payload)
    if (!result.ok || !result.data?.edge) {
      ElMessage.warning(result.message || '手动关系边创建失败')
      return
    }

    const manualRelation = mapManualEdgeFromApi(result.data.edge)
    const current = edgeSnapshot.value
    if (!current) {
      edgeSnapshot.value = {
        builtAt: nowDateTimeText(),
        promotionPaths: [],
        transitionRelations: [],
        manualRelations: [manualRelation],
      }
    } else {
      const existing = current.manualRelations.filter(item => item.id !== manualRelation.id)
      current.manualRelations = [manualRelation, ...existing]
      current.builtAt = nowDateTimeText()
    }

    pushGovernanceLog(
      'edge',
      'succeeded',
      '手动关系边创建完成',
      `${manualRelation.sourceJobName} -> ${manualRelation.targetJobName}（${manualRelation.relationType === 'promotion' ? '晋升' : '换岗'}）`,
      { edgeSnapshot: edgeSnapshot.value || undefined },
    )

    manualEdgeForm.value.targetJobId = ''
    manualEdgeForm.value.reason = ''
    ElMessage.success('手动关系边创建成功')
  } finally {
    manualEdgeSubmitting.value = false
  }
}

async function handlePreviewCleanup() {
  const expireDays = Math.max(1, Number(cleanupForm.expireDays || 0))
  cleanupPreviewLoading.value = true

  try {
    const result = await previewCleanupExpiredJobs(expireDays)
    cleanupPreviewRows.value = result.list.map(item => ({
      jobId: item.jobId,
      jobName: item.jobName,
      city: item.city,
      level: item.level,
      source: item.source,
    }))
    cleanupPreviewMeta.value = {
      generatedAt: nowDateTimeText(),
      expireDays: Number(result.expireDays || expireDays),
      fallback: Boolean(result.fallback),
    }

    ElMessage.success(`共 ${cleanupPreviewRows.value.length} 条待清理`)
  } finally {
    cleanupPreviewLoading.value = false
  }
}

function handleCleanupJobs() {
  if (cleanupRunning.value) {
    ElMessage.warning('清理任务进行中，请稍后再试')
    return
  }

  const expireDays = Math.max(1, Number(cleanupForm.expireDays || 0))
  const previewMatched = cleanupPreviewMeta.value
    && cleanupPreviewMeta.value.expireDays === expireDays
    && cleanupPreviewRows.value.length > 0

  const removedCandidates = previewMatched
    ? cleanupPreviewRows.value.map(item => ({
      jobId: item.jobId,
      jobName: item.jobName,
      city: item.city,
      level: item.level,
      source: item.source,
    }))
    : jobRows.value
      .filter(item => String(item.status || '').toLowerCase() === 'expired')
      .map(item => ({
        jobId: item.jobId,
        jobName: item.jobName,
        city: item.city,
        level: item.level,
        source: item.source,
      }))

  pendingCleanupContext.value = {
    expireDays,
    removedCandidates,
  }

  pushGovernanceLog(
    'cleanup',
    'running',
    '过期岗位清理任务已启动',
    `按超过 ${pendingCleanupContext.value.expireDays} 天未更新规则开始清理。`,
  )

  startCleanupExpiredJobs()
  if (!cleanupRunning.value) {
    pendingCleanupContext.value = null
    return
  }

  ElMessage.success('过期岗位清理任务已创建')
}

function openCategoryDetail(record: ImportCategorySummary) {
  activeCategoryDetail.value = record
  categoryDetailVisible.value = true
}

function openLogDetail(log: GovernanceLogItem) {
  activeLog.value = log
  logDetailVisible.value = true
}

watch(importRunning, (running, wasRunning) => {
  if (wasRunning && !running && pendingImportContext.value) {
    const context = pendingImportContext.value
    const snapshot = buildImportSnapshot(context.batchName, context.sourceFile, context.rows)
    importSnapshot.value = snapshot
    edgeSnapshot.value = null
    pendingImportContext.value = null

    pushGovernanceLog(
      'import',
      'succeeded',
      '岗位导入任务完成',
      `批次 ${snapshot.batchName} 导入完成，共 ${snapshot.importedRows} 条岗位。`,
      { importSnapshot: snapshot },
    )
  }
})

watch(edgeRunning, (running, wasRunning) => {
  if (wasRunning && !running && pendingEdgeContext.value) {
    const existingManualRelations = edgeSnapshot.value?.manualRelations || []
    const snapshot = buildEdgeSnapshot()
    edgeSnapshot.value = {
      ...snapshot,
      manualRelations: [...existingManualRelations],
    }
    pendingEdgeContext.value = null

    pushGovernanceLog(
      'edge',
      'succeeded',
      '关系边构建任务完成',
      `构建完成：晋升路径 ${snapshot.promotionPaths.length} 条，换岗关系 ${snapshot.transitionRelations.length} 条。`,
      { edgeSnapshot: snapshot },
    )
  }
})

watch(cleanupRunning, (running, wasRunning) => {
  if (wasRunning && !running && pendingCleanupContext.value) {
    const context = pendingCleanupContext.value
    const snapshot = buildCleanupSnapshot(context.expireDays, context.removedCandidates)
    cleanupSnapshot.value = snapshot
    pendingCleanupContext.value = null
    cleanupPreviewRows.value = []
    cleanupPreviewMeta.value = null

    pushGovernanceLog(
      'cleanup',
      'succeeded',
      '过期岗位清理任务完成',
      `清理完成：移除 ${snapshot.removedJobs.length} 条过期岗位。`,
      { cleanupSnapshot: snapshot },
    )
  }
})
</script>

<template>
  <div class="space-y-4">
    <section class="grid gap-4 xl:grid-cols-12">
      <div class="xl:col-span-8">
        <el-card shadow="never" class="!rounded-2xl !border-slate-200">
          <template #header>
            <div class="flex flex-wrap items-center justify-between gap-3">
              <div>
                <p class="text-base font-semibold text-slate-900">岗位治理任务中心</p>
              </div>
            </div>
          </template>

          <div class="space-y-4">
            <div class="rounded-xl border border-slate-200 bg-slate-50 p-2">
              <div class="grid gap-2 md:grid-cols-3">
                <button
                  v-for="item in PANEL_OPTIONS"
                  :key="item.key"
                  type="button"
                  class="rounded-lg border px-3 py-2 text-left transition-colors"
                  :class="item.key === activePanel
                    ? 'border-slate-900 bg-white text-slate-900'
                    : 'border-slate-200 bg-slate-50 text-slate-600 hover:bg-white'"
                  @click="activePanel = item.key"
                >
                  <p class="text-sm font-semibold">{{ item.label }}</p>
                  <p class="mt-1 text-xs text-slate-500">{{ item.description }}</p>
                </button>
              </div>
            </div>

            <div class="overflow-hidden rounded-xl border border-slate-200 bg-slate-50">
              <div
                class="flex transition-transform duration-300 ease-out"
                :style="{ transform: `translateX(-${panelTranslateIndex * 100}%)` }"
              >
                <section class="w-full shrink-0 space-y-4 p-5">
                  <div class="flex items-center justify-between">
                    <div>
                      <p class="text-sm font-semibold text-slate-900">岗位导入任务</p>
                      <p class="mt-1 text-xs text-slate-500">导入完成后自动生成类别与岗位数量摘要</p>
                    </div>
                    <el-tag type="primary">批处理</el-tag>
                  </div>

                  <div class="grid gap-4 lg:grid-cols-2">
                    <div class="space-y-3 rounded-xl border border-slate-200 bg-white p-4">
                      <p class="text-sm font-medium text-slate-800">任务参数</p>
                      <el-input v-model="importForm.batchName" size="small" placeholder="批次名称" />

                      <el-upload
                        ref="importUploadRef"
                        drag
                        :auto-upload="false"
                        :show-file-list="false"
                        :limit="1"
                        accept=".xls,.xlsx,.csv"
                        @change="handleImportFileChange"
                        @exceed="handleImportFileExceed"
                      >
                        <el-icon class="el-icon--upload"><UploadFilled /></el-icon>
                        <div class="el-upload__text">拖拽文件到此处，或点击上传</div>
                        <template #tip>
                          <div class="text-xs text-slate-500">支持 .xls / .xlsx / .csv</div>
                        </template>
                      </el-upload>

                      <div class="rounded-lg border border-slate-200 bg-slate-50 px-3 py-2">
                        <p class="text-xs text-slate-500">当前文件</p>
                        <div v-if="importSelectedFileMeta" class="mt-1 flex items-center justify-between gap-2">
                          <div class="min-w-0">
                            <p class="truncate text-sm font-medium text-slate-800">{{ importSelectedFileMeta.name }}</p>
                            <p class="text-xs text-slate-500">
                              {{ importSelectedFileMeta.sizeLabel }} · {{ (importSelectedFileMeta.ext || 'unknown').toUpperCase() }}
                            </p>
                          </div>
                          <el-button link type="danger" @click="handleClearImportFile">移除</el-button>
                        </div>
                        <p v-else class="mt-1 text-xs text-slate-400">尚未上传导入文件</p>
                      </div>

                      <el-input v-model="importForm.sourceFile" size="small" placeholder="导入文件名" readonly />
                      <el-input-number
                        v-model="importForm.rows"
                        :min="100"
                        :max="50000"
                        :step="100"
                        size="small"
                        controls-position="right"
                      />
                    </div>

                    <div class="space-y-3 rounded-xl border border-slate-200 bg-white p-4">
                      <p class="text-sm font-medium text-slate-800">一键构建关系</p>
                      <el-button type="primary" :loading="importRunning" @click="handleImportJobs">
                        <el-icon class="mr-1"><Download /></el-icon>
                        启动岗位导入
                      </el-button>
                      <el-progress :percentage="importProgress" :status="importRunning ? '' : 'success'" :stroke-width="8" />
                      <p class="text-xs text-slate-500">导入完成后自动生成类别统计与样例岗位详情。</p>
                    </div>
                  </div>

                  <div class="rounded-xl border border-slate-200 bg-white p-4">
                    <div class="mb-3 flex items-center justify-between">
                      <p class="text-sm font-semibold text-slate-900">导入结果摘要</p>
                      <span v-if="importSnapshot" class="text-xs text-slate-500">导入时间：{{ importSnapshot.importedAt }}</span>
                    </div>

                    <el-empty v-if="!importSnapshot" description="尚未完成导入任务" :image-size="64" />

                    <div v-else class="space-y-3">
                      <div class="grid gap-3 md:grid-cols-3">
                        <div class="rounded-lg border border-slate-200 bg-slate-50 px-3 py-2">
                          <p class="text-xs text-slate-500">导入批次</p>
                          <p class="mt-1 text-sm font-semibold text-slate-900">{{ importSnapshot.batchName }}</p>
                        </div>
                        <div class="rounded-lg border border-slate-200 bg-slate-50 px-3 py-2">
                          <p class="text-xs text-slate-500">导入岗位总数</p>
                          <p class="mt-1 text-sm font-semibold text-slate-900">{{ importSnapshot.importedRows }}</p>
                        </div>
                        <div class="rounded-lg border border-slate-200 bg-slate-50 px-3 py-2">
                          <p class="text-xs text-slate-500">导入来源</p>
                          <p class="mt-1 truncate text-sm font-semibold text-slate-900">{{ importSnapshot.sourceFile }}</p>
                        </div>
                      </div>

                      <el-table :data="importSummaryRows" size="small" border>
                        <el-table-column prop="category" label="岗位类别" min-width="160" />
                        <el-table-column prop="jobCount" label="岗位数量" width="120" align="right" />
                        <el-table-column label="样例岗位" min-width="260">
                          <template #default="scope">
                            <span class="text-xs text-slate-600">
                              {{ formatSampleJobNames(scope.row.sampleJobs, 2) }}
                            </span>
                          </template>
                        </el-table-column>
                        <el-table-column label="操作" width="120" align="center">
                          <template #default="scope">
                            <el-button link type="primary" @click="openCategoryDetail(scope.row)">查看详情</el-button>
                          </template>
                        </el-table-column>
                      </el-table>
                    </div>
                  </div>
                </section>

                <section class="w-full shrink-0 space-y-4 p-5">
                  <div class="flex items-center justify-between">
                    <div>
                      <p class="text-sm font-semibold text-slate-900">关系边构建任务</p>
                      <p class="mt-1 text-xs text-slate-500">仅允许在导入新岗位后执行</p>
                    </div>
                    <el-tag type="success">图谱计算</el-tag>
                  </div>

                  <el-alert
                    :title="edgeActionHint"
                    :type="importSnapshot ? 'success' : 'warning'"
                    :closable="false"
                    show-icon
                  />

                  <div class="space-y-3 rounded-xl border border-slate-200 bg-white p-4">
                    <p class="text-sm font-medium text-slate-800">一键构建关系</p>
                    <el-button type="success" :disabled="edgeActionDisabled" :loading="edgeRunning" @click="handleBuildEdges">
                      <el-icon class="mr-1"><Link /></el-icon>
                      启动构建服务
                    </el-button>
                    <el-progress :percentage="edgeProgress" :status="edgeRunning ? '' : 'success'" :stroke-width="8" />
                  </div>

                  <div class="space-y-3 rounded-xl border border-slate-200 bg-white p-4">
                    <div class="flex items-center justify-between">
                      <p class="text-sm font-medium text-slate-800">手动新建关系边</p>
                      <el-tag type="info">人工维护</el-tag>
                    </div>

                    <div class="grid gap-3 lg:grid-cols-2">
                      <el-select-v2
                        v-model="manualEdgeForm.sourceJobId"
                        :options="relationJobOptions"
                        filterable
                        clearable
                        placeholder="选择起点岗位"
                      />
                      <el-select-v2
                        v-model="manualEdgeForm.targetJobId"
                        :options="relationJobOptions"
                        filterable
                        clearable
                        placeholder="选择目标岗位"
                      />
                    </div>

                    <div class="grid gap-3 lg:grid-cols-2">
                      <el-radio-group v-model="manualEdgeForm.relationType" size="small">
                        <el-radio-button label="promotion">晋升</el-radio-button>
                        <el-radio-button label="transition">换岗</el-radio-button>
                      </el-radio-group>
                      <div class="text-right text-xs text-slate-500">
                        晋升关系会校验为“低级到高级”
                      </div>
                    </div>

                    <el-input
                      v-model="manualEdgeForm.reason"
                      type="textarea"
                      :rows="3"
                      maxlength="200"
                      show-word-limit
                      placeholder="请填写关系构建理由（4~200字）"
                    />

                    <el-button type="primary" :loading="manualEdgeSubmitting" @click="handleCreateManualEdge">
                      确认新建关系边
                    </el-button>
                  </div>

                  <div class="space-y-3 rounded-xl border border-slate-200 bg-white p-4">
                    <div class="flex items-center justify-between">
                      <p class="text-sm font-semibold text-slate-900">构建结果</p>
                      <span v-if="edgeSnapshot" class="text-xs text-slate-500">构建时间：{{ edgeSnapshot.builtAt }}</span>
                    </div>

                    <el-empty v-if="!edgeSnapshot" description="尚未执行关系边构建" :image-size="64" />

                    <div v-else class="space-y-4">
                      <div>
                        <p class="mb-2 text-xs font-medium text-slate-500">晋升岗位路径</p>
                        <el-table :data="edgeSnapshot.promotionPaths" size="small" border>
                          <el-table-column prop="pathLabel" label="路径" min-width="420" />
                          <el-table-column label="平均相似度" width="130" align="right">
                            <template #default="scope">
                              {{ Number(scope.row.similarity || 0).toFixed(3) }}
                            </template>
                          </el-table-column>
                          <el-table-column prop="sampleCount" label="样本量" width="110" align="right" />
                        </el-table>
                      </div>

                      <div>
                        <p class="mb-2 text-xs font-medium text-slate-500">换岗关系（含相似度）</p>
                        <el-table :data="edgeSnapshot.transitionRelations" size="small" border>
                          <el-table-column prop="source" label="起点岗位" min-width="200" />
                          <el-table-column prop="target" label="目标岗位" min-width="200" />
                          <el-table-column label="相似度" width="120" align="right">
                            <template #default="scope">
                              {{ Number(scope.row.similarity || 0).toFixed(3) }}
                            </template>
                          </el-table-column>
                        </el-table>
                      </div>

                      <div>
                        <p class="mb-2 text-xs font-medium text-slate-500">手动维护关系边</p>
                        <el-table :data="edgeSnapshot.manualRelations" size="small" border>
                          <el-table-column prop="sourceJobName" label="起点岗位" min-width="200" />
                          <el-table-column prop="targetJobName" label="目标岗位" min-width="200" />
                          <el-table-column label="关系类型" width="100" align="center">
                            <template #default="scope">
                              <el-tag :type="scope.row.relationType === 'promotion' ? 'success' : 'info'" size="small">
                                {{ scope.row.relationType === 'promotion' ? '晋升' : '换岗' }}
                              </el-tag>
                            </template>
                          </el-table-column>
                          <el-table-column label="相似度" width="120" align="right">
                            <template #default="scope">
                              {{ Number(scope.row.similarity || 0).toFixed(3) }}
                            </template>
                          </el-table-column>
                          <el-table-column prop="reason" label="构建理由" min-width="240" />
                        </el-table>
                        <p v-if="!edgeSnapshot.manualRelations.length" class="mt-2 text-xs text-slate-400">暂无手动维护关系边</p>
                      </div>
                    </div>
                  </div>
                </section>

                <section class="w-full shrink-0 space-y-4 p-5">
                  <div class="flex items-center justify-between">
                    <div>
                      <p class="text-sm font-semibold text-slate-900">过期岗位清理任务</p>
                      <p class="mt-1 text-xs text-slate-500">执行后展示被清理岗位明细</p>
                    </div>
                    <el-tag type="warning">周期任务</el-tag>
                  </div>

                  <div class="space-y-3 rounded-xl border border-slate-200 bg-white p-4">
                    <div class="flex flex-wrap items-center gap-3">
                      <span class="text-sm text-slate-600">过期阈值（天）</span>
                      <el-input-number
                        v-model="cleanupForm.expireDays"
                        :min="7"
                        :max="180"
                        controls-position="right"
                        size="small"
                      />
                      <el-button :loading="cleanupPreviewLoading" @click="handlePreviewCleanup">
                        待清理岗位
                      </el-button>
                      <el-button type="danger" :loading="cleanupRunning" @click="handleCleanupJobs">
                        <el-icon class="mr-1"><Delete /></el-icon>
                        启动清理任务
                      </el-button>
                    </div>
                    <el-progress :percentage="cleanupProgress" :status="cleanupRunning ? '' : 'success'" :stroke-width="8" />
                  </div>

                  <div class="space-y-3 rounded-xl border border-slate-200 bg-white p-4">
                    <div class="flex items-center justify-between">
                      <p class="text-sm font-semibold text-slate-900">清理预览</p>
                      <span v-if="cleanupPreviewMeta" class="text-xs text-slate-500">生成时间：{{ cleanupPreviewMeta.generatedAt }}</span>
                    </div>

                    <el-empty v-if="!cleanupPreviewRows.length" description="暂无内容" :image-size="64" />

                    <div v-else class="space-y-2">
                      <p class="text-xs text-slate-500">
                        预览规则：超过 {{ cleanupPreviewMeta?.expireDays || cleanupForm.expireDays }} 天未更新岗位，预计清理 {{ cleanupPreviewRows.length }} 条。
                        <span v-if="cleanupPreviewMeta?.fallback"></span>
                      </p>
                      <el-table :data="cleanupPreviewRows" size="small" border>
                        <el-table-column prop="jobId" label="岗位ID" min-width="140" />
                        <el-table-column prop="jobName" label="岗位名称" min-width="220" />
                        <el-table-column prop="city" label="城市" width="100" />
                        <el-table-column label="等级" width="90">
                          <template #default="scope">{{ resolveLevelText(scope.row.level) }}</template>
                        </el-table-column>
                        <el-table-column prop="source" label="来源" min-width="140" />
                      </el-table>
                    </div>
                  </div>

                  <div class="space-y-3 rounded-xl border border-slate-200 bg-white p-4">
                    <div class="flex items-center justify-between">
                      <p class="text-sm font-semibold text-slate-900">清理结果明细</p>
                      <span v-if="cleanupSnapshot" class="text-xs text-slate-500">执行时间：{{ cleanupSnapshot.cleanedAt }}</span>
                    </div>

                    <el-empty v-if="!cleanupSnapshot" description="尚未执行清理任务" :image-size="64" />

                    <div v-else class="space-y-2">
                      <p class="text-xs text-slate-500">
                        清理规则：超过 {{ cleanupSnapshot.expireDays }} 天未更新岗位，共清理 {{ cleanupSnapshot.removedJobs.length }} 条。
                      </p>
                      <el-table :data="cleanupSnapshot.removedJobs" size="small" border>
                        <el-table-column prop="jobId" label="岗位ID" min-width="150" />
                        <el-table-column prop="jobName" label="岗位名称" min-width="200" />
                        <el-table-column prop="city" label="城市" width="100" />
                        <el-table-column label="等级" width="90">
                          <template #default="scope">{{ resolveLevelText(scope.row.level) }}</template>
                        </el-table-column>
                        <el-table-column prop="source" label="来源" min-width="150" />
                      </el-table>
                    </div>
                  </div>
                </section>
              </div>
            </div>
          </div>
        </el-card>
      </div>

      <div class="xl:col-span-4">
        <el-card shadow="never" class="!h-full !rounded-2xl !border-slate-200">
          <template #header>
            <div class="flex items-center justify-between">
              <span class="font-semibold text-slate-900">任务执行日志</span>
              <span class="text-xs text-slate-500">最近 {{ recentLogs.length }} 条</span>
            </div>
          </template>

          <div class="max-h-[640px] space-y-2 overflow-auto pr-1">
            <button
              v-for="log in recentLogs"
              :key="log.id"
              type="button"
              class="w-full rounded-lg border border-slate-200 bg-slate-50 px-3 py-3 text-left transition-colors hover:border-slate-300 hover:bg-white"
              @click="openLogDetail(log)"
            >
              <div class="flex items-center justify-between gap-2">
                <div class="min-w-0">
                  <p class="truncate text-sm font-medium text-slate-900">{{ log.title }}</p>
                  <p class="mt-0.5 text-xs text-slate-500">{{ resolveLogTypeText(log.type) }} · {{ log.time }}</p>
                </div>
                <el-tag :type="resolveStatusTagType(log.status)" size="small">{{ resolveStatusText(log.status) }}</el-tag>
              </div>
              <p class="mt-2 text-xs text-slate-600">{{ log.summary }}</p>
              <p class="mt-1 text-[11px] text-slate-400">点击查看具体执行情况</p>
            </button>
            <el-empty v-if="!recentLogs.length" description="暂无日志" :image-size="64" />
          </div>
        </el-card>
      </div>
    </section>

    <el-drawer
      v-model="categoryDetailVisible"
      :title="activeCategoryDetail ? `${activeCategoryDetail.category} · 岗位详情` : '岗位类别详情'"
      size="44%"
    >
      <div v-if="activeCategoryDetail" class="space-y-3">
        <div class="rounded-lg border border-slate-200 bg-slate-50 px-3 py-2 text-xs text-slate-600">
          岗位数量：{{ activeCategoryDetail.jobCount }}
        </div>
        <el-table :data="activeCategoryDetail.sampleJobs" size="small" border>
          <el-table-column prop="jobId" label="岗位ID" min-width="140" />
          <el-table-column prop="jobName" label="岗位名称" min-width="220" />
          <el-table-column prop="level" label="等级" width="90" />
          <el-table-column prop="city" label="城市" width="90" />
          <el-table-column prop="source" label="来源" min-width="120" />
        </el-table>
      </div>
      <el-empty v-else description="未选择类别" :image-size="64" />
    </el-drawer>

    <el-dialog v-model="logDetailVisible" width="900px" destroy-on-close>
      <template #header>
        <div class="flex items-center justify-between pr-8">
          <div>
            <p class="text-base font-semibold text-slate-900">{{ activeLog?.title || '执行详情' }}</p>
            <p class="mt-1 text-xs text-slate-500">{{ activeLog?.time || '-' }}</p>
          </div>
          <el-tag v-if="activeLog" :type="resolveStatusTagType(activeLog.status)">{{ resolveStatusText(activeLog.status) }}</el-tag>
        </div>
      </template>

      <div v-if="activeLog" class="space-y-4">
        <div class="rounded-lg border border-slate-200 bg-slate-50 px-4 py-3">
          <p class="text-xs text-slate-500">任务类型</p>
          <p class="mt-1 text-sm font-medium text-slate-900">{{ resolveLogTypeText(activeLog.type) }}</p>
          <p class="mt-2 text-xs text-slate-600">{{ activeLog.summary }}</p>
        </div>

        <div v-if="activeLog.payload?.importSnapshot" class="space-y-3">
          <p class="text-sm font-semibold text-slate-900">导入结果详情</p>
          <el-descriptions :column="3" border size="small">
            <el-descriptions-item label="批次">{{ activeLog.payload.importSnapshot.batchName }}</el-descriptions-item>
            <el-descriptions-item label="导入岗位数">{{ activeLog.payload.importSnapshot.importedRows }}</el-descriptions-item>
            <el-descriptions-item label="导入时间">{{ activeLog.payload.importSnapshot.importedAt }}</el-descriptions-item>
          </el-descriptions>
          <el-table :data="activeLog.payload.importSnapshot.categories" size="small" border>
            <el-table-column prop="category" label="岗位类别" min-width="160" />
            <el-table-column prop="jobCount" label="岗位数量" width="120" align="right" />
            <el-table-column label="样例岗位" min-width="280">
              <template #default="scope">
                <span class="text-xs text-slate-600">
                  {{ formatSampleJobNames(scope.row.sampleJobs) }}
                </span>
              </template>
            </el-table-column>
          </el-table>
        </div>

        <div v-if="activeLog.payload?.edgeSnapshot" class="space-y-3">
          <p class="text-sm font-semibold text-slate-900">关系构建详情</p>
          <el-descriptions :column="2" border size="small">
            <el-descriptions-item label="构建时间">{{ activeLog.payload.edgeSnapshot.builtAt }}</el-descriptions-item>
            <el-descriptions-item label="晋升路径数量">{{ activeLog.payload.edgeSnapshot.promotionPaths.length }}</el-descriptions-item>
          </el-descriptions>
          <el-table :data="activeLog.payload.edgeSnapshot.promotionPaths" size="small" border>
            <el-table-column prop="pathLabel" label="晋升路径" min-width="480" />
            <el-table-column label="平均相似度" width="120" align="right">
              <template #default="scope">{{ Number(scope.row.similarity || 0).toFixed(3) }}</template>
            </el-table-column>
          </el-table>
          <el-table :data="activeLog.payload.edgeSnapshot.transitionRelations" size="small" border>
            <el-table-column prop="source" label="换岗起点" min-width="220" />
            <el-table-column prop="target" label="换岗目标" min-width="220" />
            <el-table-column label="相似度" width="120" align="right">
              <template #default="scope">{{ Number(scope.row.similarity || 0).toFixed(3) }}</template>
            </el-table-column>
          </el-table>
          <el-table :data="activeLog.payload.edgeSnapshot.manualRelations || []" size="small" border>
            <el-table-column prop="sourceJobName" label="手动关系起点" min-width="220" />
            <el-table-column prop="targetJobName" label="手动关系目标" min-width="220" />
            <el-table-column label="关系类型" width="100" align="center">
              <template #default="scope">
                <el-tag :type="scope.row.relationType === 'promotion' ? 'success' : 'info'" size="small">
                  {{ scope.row.relationType === 'promotion' ? '晋升' : '换岗' }}
                </el-tag>
              </template>
            </el-table-column>
            <el-table-column prop="reason" label="构建理由" min-width="240" />
          </el-table>
        </div>

        <div v-if="activeLog.payload?.cleanupSnapshot" class="space-y-3">
          <p class="text-sm font-semibold text-slate-900">清理明细</p>
          <el-descriptions :column="3" border size="small">
            <el-descriptions-item label="清理时间">{{ activeLog.payload.cleanupSnapshot.cleanedAt }}</el-descriptions-item>
            <el-descriptions-item label="过期阈值">{{ activeLog.payload.cleanupSnapshot.expireDays }} 天</el-descriptions-item>
            <el-descriptions-item label="清理数量">{{ activeLog.payload.cleanupSnapshot.removedJobs.length }}</el-descriptions-item>
          </el-descriptions>
          <el-table :data="activeLog.payload.cleanupSnapshot.removedJobs" size="small" border>
            <el-table-column prop="jobId" label="岗位ID" min-width="140" />
            <el-table-column prop="jobName" label="岗位名称" min-width="220" />
            <el-table-column prop="city" label="城市" width="90" />
            <el-table-column label="等级" width="90">
              <template #default="scope">{{ resolveLevelText(scope.row.level) }}</template>
            </el-table-column>
            <el-table-column prop="source" label="来源" min-width="140" />
          </el-table>
        </div>
      </div>
    </el-dialog>
  </div>
</template>
