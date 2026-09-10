<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { CircleClose, Download, Loading, Promotion, Refresh } from '@element-plus/icons-vue'
import { isSuccessCode } from '../services/http'
import {
  getCrawlSupportedCities,
  queryCrawlStatus,
  stopCrawlTask,
  submitCrawlTask,
  type ApiResponse,
  type CrawlStatusSnapshot,
} from '../services/jobCrawl'

/* I love coding */

/* ============ 常量与码表（对齐 BOSS直聘爬取岗位接口文档） ============ */

/** 服务端配置默认关键词（keywords.json），作为快捷项预填/提示 */
const DEFAULT_KEYWORDS = ['AI应用开发工程师', 'Agent应用开发工程师']

/** 内置 20 城回退表（接口不可达时仍可渲染筛选项；真实支持集以后端为准） */
const FALLBACK_CITY_NAMES = [
  '北京', '上海', '广州', '深圳', '杭州', '成都', '南京', '武汉', '苏州', '西安',
  '长沙', '合肥', '郑州', '重庆', '厦门', '天津', '济南', '青岛', '大连', '福州',
]

interface FilterOption {
  value: string
  label: string
}

interface FilterGroup {
  key: string
  label: string
  placeholder: string
  options: FilterOption[]
}

const FILTER_GROUPS: FilterGroup[] = [
  {
    key: 'salary',
    label: '薪资',
    placeholder: '薪资不限',
    options: [
      { value: '403', label: '3-5K' },
      { value: '404', label: '5-10K' },
      { value: '405', label: '10-20K' },
      { value: '406', label: '20-50K' },
      { value: '407', label: '50K以上' },
    ],
  },
  {
    key: 'experience',
    label: '经验',
    placeholder: '经验不限',
    options: [
      { value: '103', label: '1年以内' },
      { value: '104', label: '1-3年' },
      { value: '105', label: '3-5年' },
      { value: '106', label: '5-10年' },
      { value: '107', label: '10年以上' },
    ],
  },
  {
    key: 'degree',
    label: '学历',
    placeholder: '学历不限',
    options: [
      { value: '203', label: '本科' },
      { value: '204', label: '硕士' },
      { value: '205', label: '博士' },
    ],
  },
  {
    key: 'jobType',
    label: '求职类型',
    placeholder: '类型不限',
    options: [
      { value: '1901', label: '全职' },
      { value: '1903', label: '兼职' },
    ],
  },
  {
    key: 'scale',
    label: '公司规模',
    placeholder: '规模不限',
    options: [
      { value: '301', label: '0-20人' },
      { value: '302', label: '20-99人' },
      { value: '303', label: '100-499人' },
      { value: '304', label: '500-999人' },
      { value: '305', label: '1000-9999人' },
      { value: '306', label: '10000人以上' },
    ],
  },
  {
    key: 'stage',
    label: '融资阶段',
    placeholder: '阶段不限',
    options: [
      { value: '801', label: '未融资' },
      { value: '802', label: '天使轮' },
      { value: '803', label: 'A轮' },
      { value: '804', label: 'B轮' },
      { value: '805', label: 'C轮' },
      { value: '806', label: 'D轮及以上' },
      { value: '807', label: '已上市' },
    ],
  },
]

/** 任务状态展示元信息 */
const STATUS_META: Record<
  string,
  { label: string; dot: string; pill: string; desc: string }
> = {
  idle: {
    label: '空闲',
    dot: 'bg-slate-300',
    pill: 'border-slate-200 bg-slate-50 text-slate-500',
    desc: '当前没有运行中的采集任务，配置上方参数即可发起。',
  },
  running: {
    label: '采集中',
    dot: 'bg-sky-500',
    pill: 'border-sky-200 bg-sky-50 text-sky-600',
    desc: '任务正在后台执行，页面每 3 秒自动刷新一次进度。',
  },
  stopping: {
    label: '正在停止',
    dot: 'bg-amber-500',
    pill: 'border-amber-200 bg-amber-50 text-amber-600',
    desc: '停止请求已发出，正在保存已采集数据，稍候自动完成。',
  },
  done: {
    label: '已完成',
    dot: 'bg-emerald-500',
    pill: 'border-emerald-200 bg-emerald-50 text-emerald-600',
    desc: '任务已完成，采集统计见右侧卡片。',
  },
  error: {
    label: '采集失败',
    dot: 'bg-rose-500',
    pill: 'border-rose-200 bg-rose-50 text-rose-600',
    desc: '任务执行出错，具体原因见提示框。',
  },
}

/* ============ 表单状态 ============ */

interface CrawlForm {
  keywords: string[]
  cities: string[]
  filters: Record<string, string>
  newJobTarget: number
  maxJobs: number
  headless: boolean
}

const DEFAULT_TARGET = 20
const DEFAULT_MAX_JOBS = 100

const form = ref<CrawlForm>({
  keywords: [...DEFAULT_KEYWORDS],
  cities: [],
  filters: {},
  newJobTarget: DEFAULT_TARGET,
  maxJobs: DEFAULT_MAX_JOBS,
  headless: true,
})

const cityNames = ref<string[]>([...FALLBACK_CITY_NAMES])
const initLoading = ref(true)
const citiesLoading = ref(false)
const refreshing = ref(false)
const submitting = ref(false)
const stopping = ref(false)

/* ============ 任务快照与轮询 ============ */

const snapshot = ref<CrawlStatusSnapshot | null>(null)
const nowMs = ref(Date.now())
let previousStatusRef = ''
let statusTimer: number | null = null
let clockTimer: number | null = null

const statusText = computed<string>(() => {
  const status = String(snapshot.value?.status || 'idle')
  return STATUS_META[status] ? status : 'idle'
})

const statusMeta = computed(() => STATUS_META[statusText.value] || STATUS_META.idle)

/** 是否有任务正在运行（不可重复提交 / 允许停止） */
const busy = computed(() => statusText.value === 'running' || statusText.value === 'stopping')

const isStopping = computed(() => statusText.value === 'stopping')
const isDone = computed(() => statusText.value === 'done')
const isError = computed(() => statusText.value === 'error')
const isIdle = computed(() => statusText.value === 'idle')

const comboIndex = computed(() => Number(snapshot.value?.comboIndex || 0))
const totalCombos = computed(() => Number(snapshot.value?.totalCombos || 0))
const jobsSoFar = computed(() => Number(snapshot.value?.jobsSoFar || 0))

const progressPercent = computed(() => {
  if (isDone.value) return 100
  if (!totalCombos.value) return 0
  return Math.min(100, Math.round((comboIndex.value / totalCombos.value) * 100))
})

const progressStatus = computed(() => {
  if (isDone.value) return 'success'
  if (isError.value) return 'exception'
  return ''
})

/** 正在采集的「关键词 · 城市」组合描述 */
const currentUnitText = computed(() => {
  const keyword = String(snapshot.value?.currentKeyword || '')
  const city = String(snapshot.value?.currentCity || '')
  if (keyword || city) return `${keyword} · ${city}`.replace(/^ ·/, '').replace(/ ·$/, '')
  return '正在启动浏览器并校验 BOSS 登录态…'
})

const startedMs = computed(() => parseMs(snapshot.value?.startedAt))

const elapsedText = computed(() => {
  if (!startedMs.value) return ''
  const seconds = Math.max(0, Math.floor((nowMs.value - startedMs.value) / 1000))
  const h = Math.floor(seconds / 3600)
  const m = Math.floor((seconds % 3600) / 60)
  const s = seconds % 60
  if (h > 0) return `${h} 小时 ${m} 分`
  if (m > 0) return `${m} 分 ${s} 秒`
  return `${s} 秒`
})

const statsTiles = computed(() => {
  const stats = snapshot.value?.stats || {}
  const num = (v: unknown) => (Number.isFinite(Number(v)) ? Number(v) : 0)
  return [
    { key: 'raw', label: '原始抓取', value: num(stats.raw), text: 'text-sky-600', icon: '' },
    { key: 'cleaned', label: '清洗通过', value: num(stats.cleaned), text: 'text-indigo-600', icon: '' },
    { key: 'inserted', label: '新增入库', value: num(stats.inserted), text: 'text-emerald-600', icon: '' },
    { key: 'updated', label: '刷新更新', value: num(stats.updated), text: 'text-amber-600', icon: '' },
    { key: 'skipped', label: '清洗跳过', value: num(stats.skipped), text: 'text-slate-500', icon: '' },
  ]
})

const dbFileName = computed(() => {
  const file = String(snapshot.value?.dbFile || '')
  if (!file) return ''
  const parts = file.split(/[\\/]/)
  return parts[parts.length - 1] || file
})

/* ============ 工具函数 ============ */

function parseMs(value?: string | null): number | null {
  if (!value) return null
  const time = Date.parse(value)
  return Number.isFinite(time) ? time : null
}

function pad2(value: number) {
  return String(value).padStart(2, '0')
}

function formatClock(value?: string | null, withDate = true) {
  const ms = parseMs(value)
  if (!ms) return '--'
  const date = new Date(ms)
  const time = `${pad2(date.getHours())}:${pad2(date.getMinutes())}`
  if (!withDate) return time
  return `${date.getFullYear()}-${pad2(date.getMonth() + 1)}-${pad2(date.getDate())} ${time}`
}

function applySnapshot(data: CrawlStatusSnapshot) {
  const previous = previousStatusRef
  snapshot.value = data
  const next = String(data.status || 'idle')

  if (next === 'running' || next === 'stopping') {
    startPolling()
    startClock()
  } else {
    stopPolling()
    stopClock()
  }

  // 只在状态发生迁移（running/stopping → done/error）时给出一次提示，避免重复轮询刷屏
  const wasActive = previous === 'running' || previous === 'stopping'
  if (wasActive && next === 'done') {
    ElMessage.success({ message: data.message || '采集任务已完成', duration: 4500 })
  } else if (wasActive && next === 'error') {
    ElMessage.error({ message: data.message || '采集任务执行失败', duration: 6000 })
  }
  previousStatusRef = next
}

function startPolling() {
  if (statusTimer !== null) return
  statusTimer = window.setInterval(refreshStatus, 3000)
}

function stopPolling() {
  if (statusTimer !== null) {
    window.clearInterval(statusTimer)
    statusTimer = null
  }
}

function startClock() {
  if (clockTimer !== null) return
  nowMs.value = Date.now()
  clockTimer = window.setInterval(() => {
    nowMs.value = Date.now()
  }, 1000)
}

function stopClock() {
  if (clockTimer !== null) {
    window.clearInterval(clockTimer)
    clockTimer = null
  }
}

/* ============ 数据加载 ============ */

async function loadCities() {
  citiesLoading.value = true
  try {
    const response = await getCrawlSupportedCities()
    const payload = response.data as ApiResponse<Record<string, string>>
    if (isSuccessCode(Number(payload.code)) && payload.data) {
      const names = Object.keys(payload.data)
      if (names.length) cityNames.value = names
    }
  } catch {
    // 服务不可达时保留内置 20 城，保证表单可用（提交后仍以后端校验为准）
  } finally {
    citiesLoading.value = false
  }
}

async function refreshStatus() {
  if (refreshing.value) return
  try {
    const response = await queryCrawlStatus()
    const payload = response.data as ApiResponse<CrawlStatusSnapshot>
    if (isSuccessCode(Number(payload.code)) && payload.data) {
      applySnapshot(payload.data)
    }
  } catch {
    // 具体错误由 http 拦截器统一提示
  }
}

async function handleRefresh() {
  refreshing.value = true
  try {
    await refreshStatus()
  } finally {
    refreshing.value = false
  }
}

/* ============ 业务操作 ============ */

function toggleQuickKeyword(keyword: string) {
  const clean = keyword.trim()
  if (!clean) return
  const index = form.value.keywords.indexOf(clean)
  if (index >= 0) {
    form.value.keywords.splice(index, 1)
    return
  }
  form.value.keywords.push(clean)
}

function isQuickKeywordActive(keyword: string) {
  return form.value.keywords.includes(keyword)
}

function buildSearchFilters(): Record<string, string> {
  const filters: Record<string, string> = {}
  Object.entries(form.value.filters).forEach(([key, value]) => {
    const code = String(value || '').trim()
    // 只传数字 code，忽略空值 / "0"（对齐后端忽略规则）
    if (/^[1-9]\d*$/.test(code)) filters[key] = code
  })
  return filters
}

function buildPayload() {
  return {
    keywords: form.value.keywords.map(item => item.trim()).filter(Boolean),
    cities: form.value.cities,
    searchFilters: buildSearchFilters(),
    newJobTarget: form.value.newJobTarget,
    maxJobs: form.value.maxJobs,
    headless: form.value.headless,
  }
}

async function handleStart() {
  if (busy.value || submitting.value) return
  const keywords = form.value.keywords.map(item => item.trim()).filter(Boolean)
  if (!keywords.length) {
    ElMessage.warning('请至少填写一个岗位搜索关键词，或点击下方快捷词')
    return
  }

  const hint = form.value.cities.length
    ? `关键词 ${keywords.length} 个 × 城市 ${form.value.cities.length} 个，将组合成 ${keywords.length * form.value.cities.length} 个采集任务依次执行，确认开始？`
    : `关键词 ${keywords.length} 个；未指定城市，将使用服务端默认城市集，确认开始？`
  try {
    await ElMessageBox.confirm(hint, '确认开始采集？', {
      confirmButtonText: '开始采集',
      cancelButtonText: '再想想',
      type: 'warning',
      lockScroll: false,
    })
  } catch {
    return
  }

  submitting.value = true
  try {
    const response = await submitCrawlTask(buildPayload())
    const payload = response.data as ApiResponse<CrawlStatusSnapshot>
    if (!isSuccessCode(Number(payload.code)) || !payload.data) {
      throw new Error(payload.msg || '提交失败')
    }
    applySnapshot(payload.data)
    ElMessage.success(`已提交采集任务（${payload.data.runId || ''}），将在后台自动执行`)
  } catch {
    // 409 / 400 / 网络异常等错误由 http 拦截器统一提示
  } finally {
    submitting.value = false
  }
}

async function handleStop() {
  if (!busy.value || stopping.value) return
  try {
    await ElMessageBox.confirm('已抓到的完整详情岗位仍会正常清洗入库，是否立即停止？', '停止采集', {
      confirmButtonText: '停止采集',
      cancelButtonText: '继续采集',
      type: 'warning',
      lockScroll: false,
    })
  } catch {
    return
  }

  stopping.value = true
  try {
    const response = await stopCrawlTask()
    const payload = response.data as ApiResponse<CrawlStatusSnapshot>
    if (isSuccessCode(Number(payload.code)) && payload.data) {
      applySnapshot(payload.data)
      ElMessage.info('已发出停止请求，正在保存已采集数据…')
    }
  } catch {
    // 400（无任务）等错误由 http 拦截器统一提示
  } finally {
    stopping.value = false
  }
}

function handleClearCities() {
  form.value.cities = []
}

/* ============ 生命周期 ============ */

onMounted(async () => {
  initLoading.value = true
  try {
    await Promise.all([loadCities(), refreshStatus()])
  } finally {
    initLoading.value = false
  }
})

onBeforeUnmount(() => {
  stopPolling()
  stopClock()
})
</script>

<template>
  <section class="space-y-4 pb-24 lg:pb-0" v-loading="initLoading" element-loading-text="正在加载采集任务状态…">
    <!-- 页头 -->
    <div class="flex flex-col gap-3 rounded-2xl border border-slate-200 bg-white p-4 shadow-sm md:flex-row md:items-center md:justify-between md:p-5">
      <div class="flex min-w-0 items-start gap-3">
        <div class="mt-0.5 hidden h-11 w-11 shrink-0 items-center justify-center rounded-xl bg-gradient-to-br from-blue-500 to-indigo-600 text-white shadow-sm sm:flex">
          <el-icon :size="22"><Download /></el-icon>
        </div>
        <div class="min-w-0">
          <h2 class="text-lg font-semibold text-slate-900 md:text-2xl">BOSS 岗位采集</h2>
          <p class="mt-1 text-xs leading-relaxed text-slate-500 md:text-sm">
            按「关键词 × 城市」组合自动采集 BOSS 直聘真实岗位：后台爬取 → 清洗打标 → 写入本地 SQLite，全程异步执行、可随时查看进度与统计结果。
          </p>
        </div>
      </div>
      <div class="flex shrink-0 flex-wrap items-center gap-2">
        <span
          class="inline-flex items-center gap-1.5 rounded-full border px-3 py-1 text-xs font-medium"
          :class="statusMeta.pill"
        >
          <span class="h-1.5 w-1.5 rounded-full" :class="[statusMeta.dot, busy || isStopping ? 'animate-pulse' : '']"></span>
          {{ statusMeta.label }}
        </span>
        <el-button size="small" :icon="Refresh" :loading="refreshing" @click="handleRefresh">刷新状态</el-button>
      </div>
    </div>

    <div class="grid grid-cols-1 gap-4 xl:grid-cols-[minmax(0,1fr)_360px]">
      <!-- ============ 左：采集参数配置 ============ -->
      <div class="rounded-2xl border border-slate-200 bg-white p-5 shadow-sm">
        <header class="flex items-center justify-between border-b border-slate-100 pb-3">
          <div class="flex items-center gap-2">
            <span class="h-4 w-1 rounded-full bg-blue-500"></span>
            <h3 class="text-base font-semibold text-slate-800">采集参数</h3>
          </div>
          <span class="text-xs text-slate-400">带 * 为建议填写，其余留空自动取服务端默认</span>
        </header>

        <div class="mt-5 grid grid-cols-1 gap-x-6 gap-y-5 md:grid-cols-2">
          <!-- 搜索关键词 -->
          <div>
            <p class="mb-1.5 flex items-center text-sm font-medium text-slate-600">
              岗位搜索关键词 <span class="ml-0.5 text-rose-500">*</span>
            </p>
            <el-select
              v-model="form.keywords"
              multiple
              filterable
              allow-create
              default-first-option
              :reserve-keyword="false"
              collapse-tags
              collapse-tags-tooltip
              :max-collapse-tags="2"
              placeholder="输入关键词后回车添加"
              class="w-full"
            >
              <el-option v-for="keyword in DEFAULT_KEYWORDS" :key="keyword" :label="keyword" :value="keyword" />
            </el-select>
            <div class="mt-1.5 flex flex-wrap items-center gap-1.5">
              <span class="text-xs text-slate-400">快捷词：</span>
              <button
                v-for="keyword in DEFAULT_KEYWORDS"
                :key="keyword"
                type="button"
                :title="isQuickKeywordActive(keyword) ? '点击移除' : '点击添加'"
                class="rounded-full border px-2 py-0.5 text-xs transition"
                :class="
                  isQuickKeywordActive(keyword)
                    ? 'border-blue-300 bg-blue-50 text-blue-600'
                    : 'border-slate-200 bg-slate-50 text-slate-500 hover:border-blue-300 hover:bg-blue-50 hover:text-blue-600'
                "
                @click="toggleQuickKeyword(keyword)"
              >
                {{ keyword }}
              </button>
            </div>
            <p class="mt-1.5 text-xs text-slate-400">标题需包含该词才会保留，词越像完整岗位名越准确。</p>
          </div>

          <!-- 意向城市 -->
          <div>
            <p class="mb-1.5 flex items-center justify-between text-sm font-medium text-slate-600">
              意向城市
              <button v-if="form.cities.length" type="button" class="text-xs font-normal text-blue-500 hover:underline" @click="handleClearCities">
                清空（用默认城市）
              </button>
            </p>
            <el-select
              v-model="form.cities"
              multiple
              filterable
              collapse-tags
              collapse-tags-tooltip
              :max-collapse-tags="2"
              :loading="citiesLoading"
              placeholder="选择目标城市（可多选）"
              class="w-full"
            >
              <el-option v-for="name in cityNames" :key="name" :label="name" :value="name" />
            </el-select>
            <p class="mt-1.5 text-xs text-slate-400">
              未选择时使用服务端默认城市集；已选 {{ form.cities.length }} 城，将逐城组合采集。
            </p>
          </div>
        </div>

        <!-- 二级筛选 -->
        <div class="mt-6 border-t border-slate-100 pt-5">
          <div class="mb-3 flex items-center gap-2">
            <span class="h-4 w-1 rounded-full bg-indigo-500"></span>
            <h4 class="text-sm font-semibold text-slate-700">二级筛选</h4>
            <span class="text-xs text-slate-400">BOSS 站内筛选条件，留空表示不限制</span>
          </div>
          <div class="grid grid-cols-2 gap-x-4 gap-y-4 md:grid-cols-3">
            <div v-for="group in FILTER_GROUPS" :key="group.key">
              <p class="mb-1.5 text-xs font-medium text-slate-500">{{ group.label }}</p>
              <el-select v-model="form.filters[group.key]" clearable filterable :placeholder="group.placeholder" class="w-full">
                <el-option v-for="option in group.options" :key="option.value" :label="option.label" :value="option.value" />
              </el-select>
            </div>
          </div>
        </div>

        <!-- 采集上限 -->
        <div class="mt-6 border-t border-slate-100 pt-5">
          <div class="mb-3 flex items-center gap-2">
            <span class="h-4 w-1 rounded-full bg-emerald-500"></span>
            <h4 class="text-sm font-semibold text-slate-700">采集数量与模式</h4>
          </div>
          <div class="grid grid-cols-1 gap-x-6 gap-y-5 sm:grid-cols-3">
            <div>
              <p class="mb-1.5 text-xs font-medium text-slate-500">每组合新增目标（条）</p>
              <el-input-number v-model="form.newJobTarget" :min="1" :max="500" :step="5" controls-position="right" class="!w-full" />
              <p class="mt-1.5 text-xs leading-relaxed text-slate-400">单个「关键词 × 城市」组合新增达到该数即停，默认 20。</p>
            </div>
            <div>
              <p class="mb-1.5 text-xs font-medium text-slate-500">每组合浏览上限（条）</p>
              <el-input-number v-model="form.maxJobs" :min="1" :max="500" :step="10" controls-position="right" class="!w-full" />
              <p class="mt-1.5 text-xs leading-relaxed text-slate-400">每组合最多浏览岗位数，防止触发安全验证，默认 100。</p>
            </div>
            <div>
              <p class="mb-1.5 text-xs font-medium text-slate-500">无头模式</p>
              <div class="flex h-8 items-center gap-2">
                <el-switch v-model="form.headless" />
                <span class="text-sm text-slate-600">{{ form.headless ? '开启' : '关闭' }}</span>
              </div>
              <p class="mt-1.5 text-xs leading-relaxed text-slate-400">开启后不弹出浏览器窗口；服务端校验登录态时会短暂闪现一次，属正常现象。</p>
            </div>
          </div>
        </div>

        <!-- 操作区 -->
        <div class="mt-6 flex flex-col gap-3 border-t border-slate-100 pt-4 sm:flex-row sm:items-center sm:justify-between">
          <p class="flex items-start gap-1.5 text-xs leading-relaxed text-slate-400">
            <span class="mt-0.5 inline-flex h-4 w-4 shrink-0 items-center justify-center rounded-full bg-amber-50 text-amber-500">!</span>
            <span>同一时刻仅允许一个采集任务；需本机 Chrome 已登录 BOSS 直聘 profile（Cookie 复用），登录态失效时任务会以失败结束。</span>
          </p>
          <div class="flex shrink-0 items-center gap-2">
            <el-button :disabled="!busy" type="danger" plain :icon="CircleClose" :loading="stopping" @click="handleStop">
              {{ isStopping ? '正在停止…' : '停止采集' }}
            </el-button>
            <el-button :disabled="busy" type="primary" :icon="Promotion" :loading="submitting" @click="handleStart">
              {{ busy ? '采集中，请稍候' : '开始采集' }}
            </el-button>
          </div>
        </div>
      </div>

      <!-- ============ 右列：运行状态 + 使用须知 ============ -->
      <div class="space-y-4">
        <!-- 运行状态卡 -->
        <div class="rounded-2xl border border-slate-200 bg-white p-5 shadow-sm">
          <header class="flex items-center justify-between">
            <div class="flex items-center gap-2">
              <span class="h-4 w-1 rounded-full" :class="{ 'bg-sky-500': busy, 'bg-emerald-500': isDone, 'bg-rose-500': isError, 'bg-slate-300': isIdle, 'bg-amber-500': isStopping }"></span>
              <h3 class="text-base font-semibold text-slate-800">运行进度与结果</h3>
            </div>
            <span v-if="snapshot?.runId" class="font-mono text-[11px] text-slate-400">#{{ snapshot.runId }}</span>
          </header>

          <!-- 空闲态 -->
          <div v-if="isIdle || !snapshot" class="mt-4">
            <div class="flex flex-col items-center rounded-xl border border-dashed border-slate-200 bg-slate-50/60 px-4 py-8 text-center">
              <el-icon :size="36" class="text-slate-300"><Download /></el-icon>
              <p class="mt-3 text-sm font-medium text-slate-600">暂无采集任务</p>
              <p class="mt-1 max-w-[240px] text-xs leading-relaxed text-slate-400">
                {{ statusMeta.desc }}
              </p>
            </div>
          </div>

          <!-- 运行 / 停止中 -->
          <template v-else-if="busy">
            <div class="mt-4">
              <div class="flex items-center justify-between text-sm">
                <span class="flex items-center gap-1.5 font-medium text-slate-700">
                  <el-icon v-if="busy && !isStopping" class="animate-spin text-sky-500"><Loading /></el-icon>
                  <span :class="isStopping ? 'text-amber-600' : 'text-sky-600'">{{ statusMeta.label }}</span>
                </span>
                <span class="text-xs text-slate-400">{{ comboIndex }} / {{ totalCombos }} 组合</span>
              </div>
              <el-progress class="mt-3" :percentage="progressPercent" :status="progressStatus" :stroke-width="10" :show-text="false" />
              <div class="mt-3 grid grid-cols-2 gap-2">
                <div class="rounded-xl bg-slate-50 px-3 py-2.5">
                  <p class="text-xs text-slate-400">正在采集</p>
                  <p class="mt-0.5 line-clamp-1 text-sm font-medium text-slate-700" :title="currentUnitText">{{ currentUnitText }}</p>
                </div>
                <div class="rounded-xl bg-slate-50 px-3 py-2.5">
                  <p class="text-xs text-slate-400">已抓岗位</p>
                  <p class="mt-0.5 text-sm font-semibold text-slate-700">{{ jobsSoFar }} <span class="text-xs font-normal text-slate-400">条</span></p>
                </div>
                <div class="rounded-xl bg-slate-50 px-3 py-2.5">
                  <p class="text-xs text-slate-400">运行时长</p>
                  <p class="mt-0.5 text-sm font-medium text-slate-700">{{ elapsedText || '--' }}</p>
                </div>
                <div class="rounded-xl bg-slate-50 px-3 py-2.5">
                  <p class="text-xs text-slate-400">开始于</p>
                  <p class="mt-0.5 text-sm font-medium text-slate-700">{{ formatClock(snapshot?.startedAt) }}</p>
                </div>
              </div>
              <p v-if="snapshot?.message" class="mt-3 rounded-lg bg-amber-50 px-3 py-2 text-xs leading-relaxed text-amber-600">
                {{ snapshot.message }}
              </p>
            </div>
          </template>

          <!-- 已完成 -->
          <template v-else-if="isDone">
            <div class="mt-4">
              <div class="flex items-center gap-2 rounded-xl bg-emerald-50 px-3 py-2.5 text-sm text-emerald-700">
                <span class="h-2 w-2 rounded-full bg-emerald-500"></span>
                <span class="font-medium">{{ snapshot?.message || '采集已完成' }}</span>
              </div>

              <div v-if="snapshot?.stats" class="mt-3 grid grid-cols-3 gap-2">
                <div
                  v-for="tile in statsTiles"
                  :key="tile.key"
                  class="flex flex-col items-center rounded-xl border border-slate-100 bg-slate-50/70 px-2 py-3"
                >
                  <p class="text-xl font-bold" :class="tile.text">{{ tile.value }}</p>
                  <p class="mt-0.5 text-[11px] text-slate-400">{{ tile.label }}</p>
                </div>
              </div>

              <div class="mt-3 space-y-1 rounded-xl bg-slate-50 px-3 py-2.5 text-xs text-slate-500">
                <p class="flex justify-between gap-2"><span>完成组合</span><span>{{ comboIndex }} / {{ totalCombos }}</span></p>
                <p class="flex justify-between gap-2"><span>完成于</span><span>{{ formatClock(snapshot?.finishedAt) }}</span></p>
                <p v-if="dbFileName" class="flex justify-between gap-2"><span>数据落库</span><span class="max-w-[180px] truncate">{{ dbFileName }}</span></p>
              </div>
            </div>
          </template>

          <!-- 失败 -->
          <template v-else>
            <div class="mt-4 rounded-xl border border-rose-100 bg-rose-50 px-4 py-3">
              <div class="flex items-start gap-2 text-rose-600">
                <el-icon class="mt-0.5" :size="16"><CircleClose /></el-icon>
                <div class="min-w-0">
                  <p class="text-sm font-medium">采集执行失败</p>
                  <p v-if="snapshot?.message" class="mt-1 whitespace-pre-line text-xs leading-relaxed text-rose-500">{{ snapshot.message }}</p>
                </div>
              </div>
              <p class="mt-3 text-xs text-slate-400">完成于 {{ formatClock(snapshot?.finishedAt) }}</p>
            </div>
          </template>
        </div>

        <!-- 使用须知 -->
        <div class="rounded-2xl border border-slate-200 bg-white p-5 shadow-sm">
          <div class="flex items-center gap-2">
            <span class="h-4 w-1 rounded-full bg-amber-400"></span>
            <h3 class="text-sm font-semibold text-slate-700">采集须知</h3>
          </div>
          <ul class="mt-3 space-y-2.5 text-xs leading-relaxed text-slate-500">
            <li class="flex gap-2">
              <span class="mt-1.5 h-1 w-1 shrink-0 rounded-full bg-slate-300"></span>
              前置条件：本机装有 Chrome，且 profile 已登录 BOSS 直聘（首次可跑 <code class="rounded bg-slate-100 px-1 text-[11px]">run.py --login</code> 登录一次）。
            </li>
            <li class="flex gap-2">
              <span class="mt-1.5 h-1 w-1 shrink-0 rounded-full bg-slate-300"></span>
              单实例互斥：同一时刻仅允许一个任务，重复提交会返回 409，请先停止或等待完成。
            </li>
            <li class="flex gap-2">
              <span class="mt-1.5 h-1 w-1 shrink-0 rounded-full bg-slate-300"></span>
              数据仅写入本地 SQLite（不入岗位推荐向量库）；如需被推荐检索需另行做向量入库。
            </li>
            <li class="flex gap-2">
              <span class="mt-1.5 h-1 w-1 shrink-0 rounded-full bg-slate-300"></span>
              爬虫内置随机延迟与反检测，请勿并发 / 高频采集，以免触发 BOSS 安全验证。
            </li>
          </ul>
        </div>
      </div>
    </div>
  </section>
</template>

<style scoped>
.line-clamp-1 {
  display: -webkit-box;
  -webkit-line-clamp: 1;
  -webkit-box-orient: vertical;
  overflow: hidden;
}
</style>
