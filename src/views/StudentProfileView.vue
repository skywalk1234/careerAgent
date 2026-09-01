<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import { onBeforeRouteLeave } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Check, EditPen, Refresh } from '@element-plus/icons-vue'
import MarkdownIt from 'markdown-it'
import * as echarts from 'echarts'
import WordCloudChart from '../components/WordCloudChart.vue'
import OpenSourceBonusCard from '../components/OpenSourceBonusCard.vue'
import studentEmptyImage from '../assets/student.png'
import {
  createParseProfileJob,
  getStudentProfileAggregate,
  getStudentProfile,
  getStudentProfileList,
  parseImageResume,
  saveStudentProfile,
  type AbilityScores,
  type CertificateItem,
  type EducationItem,
  type GetProfileResult,
  type ImprovementSuggestion,
  type ParseJobCreated,
  type ProfileAggregateResult,
  type ProfileFormData,
  type ResumeListItem,
  type SaveProfileResult,
  type ScoreData,
  type WorkExperienceItem,
} from '../services/studentProfile'
import { isSuccessCode } from '../services/http'
import {
  TASK_ORCHESTRATOR_ROUTE_REFRESH_EVENT,
  type TaskOrchestratorRouteRefreshPayload,
} from '../utils/globalAssistant'
import { useAppStore } from '../stores/app'

interface ApiResponse<T> {
  code: number
  msg: string
  data?: T
  payload?: T
}

const formRef = ref()
const appStore = useAppStore()
const initLoading = ref(false)
const parseLoading = ref(false)
const saveLoading = ref(false)
const scorePanelLoading = ref(false)
const suggestionPanelLoading = ref(false)
const aggregateLoading = ref(false)
const isEditing = ref(false)
const isDesktop = ref(window.innerWidth >= 1024)
const viewMode = ref<'resume' | 'insight'>('resume')
const hasServerProfile = ref(false)
const updatedAt = ref('')
const profileId = ref('')
const savedSnapshot = ref('')
const resumeList = ref<ResumeListItem[]>([])
const activeResumeId = ref('')
const listLoading = ref(false)
const refreshLoading = ref(false)
let highlightTimer: ReturnType<typeof setTimeout> | null = null

const showTour = ref(false)
const tourSeenStorageKey = 'student_profile_tour_seen'
const tourEditBtnRef = ref<HTMLElement>()
const tourUploadRef = ref<HTMLElement>()
const tourScoreRef = ref<HTMLElement>()
let isPageActive = true
const studentEmptyImageUrl = studentEmptyImage
const fileInputRef = ref<HTMLInputElement>()

const scoreData = ref<ScoreData | null>(null)
const aggregateData = ref<ProfileAggregateResult | null>(null)
const suggestions = ref<ImprovementSuggestion[]>([])
const evidenceMap = ref<Partial<Record<keyof AbilityScores, string[]>>>({})
const radarRef = ref<HTMLDivElement>()
const progressGaugeRef = ref<HTMLDivElement>()
let radarChart: echarts.ECharts | null = null
let progressGaugeChart: echarts.ECharts | null = null
let chartResizeTimer: ReturnType<typeof setTimeout> | null = null

const profile = reactive<ProfileFormData>(createDefaultProfile())

// markdown 渲染（简历已改为 markdown 原文存储，html 转义以防御 XSS）
const markdownRenderer = new MarkdownIt({ html: false, linkify: true })
const resumeHtml = computed(() => markdownRenderer.render(profile.content || ''))

const sectionMeta = {
  basicInfo: '基本信息',
  education: '教育背景',
  workExperience: '工作经验',
  skills: '技能特长',
  certificates: '荣誉证书',
  organizeExp: '社团/组织经历',
  projects: '项目经历',
  selfEvaluation: '自我评价',
}

const abilityLabelMap: Record<keyof AbilityScores, string> = {
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

const abilityDescriptionMap: Record<keyof AbilityScores, string> = {
  professionalSkill: '专业技能：与目标岗位相关的技术能力与实践深度',
  certificate: '证书能力：岗位相关证书与资格认证的匹配程度',
  innovation: '创新能力：提出新方案、优化流程与创意实践能力',
  internalMotivation: '内驱动力：自我驱动、目标坚持与持续投入能力',
  learning: '学习能力：新知识吸收速度与持续学习能力',
  stressTolerance: '抗压能力：在压力与不确定场景下稳定输出能力',
  communication: '沟通能力：表达协同、跨角色合作与反馈能力',
  internship: '实习能力：实习/项目岗位适配度与产出质量',
  language: '语言能力：英语/外语沟通、阅读技术资料与国际协作能力',
  leadership: '领导能力：带领团队、组织活动与推动任务落地的经验',
  adaptability: '适应能力：面对新环境、新技术的快速适配能力',
  execution: '执行能力：任务拆解、按计划推进与结果交付能力',
}

const abilityCategoryConfig: Array<{ key: string; label: string; dimensions: Array<keyof AbilityScores> }> = [
  {
    key: 'basic',
    label: '基础要求类',
    dimensions: ['learning', 'communication', 'adaptability', 'execution'],
  },
  {
    key: 'literacy',
    label: '职业技能和素养类',
    dimensions: ['professionalSkill', 'certificate', 'internship', 'language'],
  },
  {
    key: 'potential',
    label: '发展潜力类',
    dimensions: ['innovation', 'internalMotivation', 'stressTolerance', 'leadership'],
  },
]

function getScoreColor(score: number) {
  if (score < 50) return '#ef4444'
  if (score < 65) return '#f59e0b'
  if (score < 80) return '#3b82f6'
  return '#22c55e'
}

function createEmptyBonusByDimension(): AbilityScores {
  return {
    professionalSkill: 0,
    certificate: 0,
    innovation: 0,
    internalMotivation: 0,
    learning: 0,
    stressTolerance: 0,
    communication: 0,
    internship: 0,
    language: 0,
    leadership: 0,
    adaptability: 0,
    execution: 0,
  }
}

function getBonusScoreByDimension(key: keyof AbilityScores) {
  const bonus = scoreData.value?.bonusByDimension ?? createEmptyBonusByDimension()
  return bonus[key] ?? 0
}

function getFinalAbilityScore(key: keyof AbilityScores) {
  const base = scoreData.value?.abilityScores?.[key] ?? 0
  const bonus = getBonusScoreByDimension(key)
  return Math.min(100, Math.max(0, base + bonus))
}

const abilityList = computed(() => {
  const scores = scoreData.value?.abilityScores
  if (!scores) return []
  return (Object.keys(abilityLabelMap) as Array<keyof AbilityScores>).map((key) => ({
    key,
    label: abilityLabelMap[key],
    description: abilityDescriptionMap[key],
    baseScore: scores[key] ?? 0,
    bonusScore: getBonusScoreByDimension(key),
    score: getFinalAbilityScore(key),
    progressColor: getScoreColor(getFinalAbilityScore(key)),
    evidence: evidenceMap.value[key] ?? [],
  }))
})

const abilitySections = computed(() => {
  return abilityCategoryConfig.map((group) => ({
    ...group,
    abilities: abilityList.value.filter((ability) => group.dimensions.includes(ability.key)),
  }))
})


const hasUnsavedChanges = computed(() => {
  return JSON.stringify(normalizeProfile(profile)) !== savedSnapshot.value
})

const actionButtonText = computed(() => {
  if (isEditing.value) return '保存并分析'
  return '手动编辑'
})

const detailButtonText = computed(() => (viewMode.value === 'insight' ? '返回' : '详情'))
const showDetailButton = computed(() => Boolean(isDesktop.value && hasServerProfile.value && scoreData.value))
const insightChartVisible = computed(() => viewMode.value === 'insight')

const formattedUpdatedAt = computed(() => {
  if (!updatedAt.value) return ''
  const date = new Date(updatedAt.value)
  if (Number.isNaN(date.getTime())) return updatedAt.value
  return new Intl.DateTimeFormat('zh-CN', {
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
    second: '2-digit',
    hour12: false,
  }).format(date)
})

const keywordTerms = computed(() => {
  const terms = new Set<string>()
  profile.skills.forEach((item) => item.trim() && terms.add(item.trim()))
  profile.workExperience.forEach((item) => {
    item.role.trim() && terms.add(item.role.trim())
    item.company.trim() && terms.add(item.company.trim())
  })
  profile.certificates.forEach((item) => item.name.trim() && terms.add(item.name.trim()))
  profile.organizeExp.forEach((item) => item.trim() && terms.add(item.trim()))
  profile.projects.forEach((item) => item.trim() && terms.add(item.trim()))
  profile.education.forEach((item) => {
    item.school.trim() && terms.add(item.school.trim())
    item.major.trim() && terms.add(item.major.trim())
  })
  profile.basicInfo.jobIntention.forEach((item) => item.trim() && terms.add(item.trim()))
  if (profile.basicInfo.city.trim()) terms.add(profile.basicInfo.city.trim())
  return Array.from(terms).slice(0, 40)
})

const sliderTrackStyle = computed(() => {
  if (!isDesktop.value) {
    return {
      width: '100%',
      transform: 'translateX(0)',
    }
  }

  return {
    width: '200%',
    transform: viewMode.value === 'insight' ? 'translateX(-50%)' : 'translateX(0)',
  }
})

const sliderPanelStyle = computed(() => ({
  width: isDesktop.value ? '50%' : '100%',
}))

const scoreAsideClass = computed(() => ({
  'xl:sticky xl:top-20 xl:self-start': viewMode.value === 'resume',
}))

function createDefaultProfile(): ProfileFormData {
  return {
    content: '',
    profileId: '',
    basicInfo: {
      name: '',
      gender: '',
      birthday: '',
      phone: '',
      email: '',
      city: '',
      jobIntention: [],
    },
    education: [createEducationItem()],
    workExperience: [createWorkItem()],
    skills: [],
    certificates: [createCertificateItem()],
    organizeExp: [],
    projects: [],
    selfEvaluation: '',
  }
}

function createEducationItem(): EducationItem {
  return {
    school: '',
    major: '',
    degree: '',
    startDate: '',
    endDate: '',
    gpa: '',
  }
}

function createWorkItem(): WorkExperienceItem {
  return {
    company: '',
    role: '',
    startDate: '',
    endDate: '',
    description: '',
  }
}

function createCertificateItem(): CertificateItem {
  return {
    name: '',
    date: '',
    issuer: '',
  }
}

function normalizeStringList(input: unknown): string[] {
  if (!Array.isArray(input)) return []
  return input
    .map((item) => String(item ?? '').trim())
    .filter(Boolean)
}

function normalizeCertificateItem(input: unknown): CertificateItem {
  if (typeof input === 'string') {
    return {
      name: input,
      date: '',
      issuer: '',
    }
  }

  const raw = (input as Partial<CertificateItem>)
  return {
    ...createCertificateItem(),
    ...raw,
    date: normalizeMonthString(raw?.date),
  }
}

function normalizeMonthString(input: unknown): string {
  const value = String(input ?? '').trim()
  if (!value) return ''

  const matched = value.match(/^(\d{4})-(\d{2})(?:-(\d{2}))?$/)
  if (matched) {
    return `${matched[1]}-${matched[2]}`
  }

  return value
}

function normalizeDayString(input: unknown): string {
  const value = String(input ?? '').trim()
  if (!value) return ''

  const dayMatched = value.match(/^(\d{4})-(\d{2})-(\d{2})$/)
  if (dayMatched) return value

  const monthMatched = value.match(/^(\d{4})-(\d{2})$/)
  if (monthMatched) return `${monthMatched[1]}-${monthMatched[2]}-01`

  return value
}

function extractPayload<T>(response: { data: ApiResponse<T> }): T | undefined {
  return response.data.payload ?? response.data.data
}

function normalizeProfile(input?: Partial<ProfileFormData> | null): ProfileFormData {
  const defaultProfile = createDefaultProfile()
  if (!input) return defaultProfile

  const basicInfo = (input.basicInfo ?? {}) as Partial<ProfileFormData['basicInfo']> & { jobIntention?: unknown }
  const normalizedJobIntention = normalizeStringList(basicInfo.jobIntention)
  const fallbackJobIntention =
    typeof basicInfo.jobIntention === 'string' ? normalizeStringList([basicInfo.jobIntention]) : []

  return {
    content: input.content ?? defaultProfile.content,
    profileId: String(input.profileId ?? '').trim(),
    basicInfo: {
      ...defaultProfile.basicInfo,
      ...basicInfo,
      birthday: normalizeDayString(basicInfo.birthday),
      jobIntention: normalizedJobIntention.length ? normalizedJobIntention : fallbackJobIntention,
    },
    education:
      input.education && input.education.length
        ? input.education.map((item) => ({
            ...createEducationItem(),
            ...item,
            startDate: normalizeMonthString(item.startDate),
            endDate: normalizeMonthString(item.endDate),
          }))
        : defaultProfile.education,
    workExperience:
      input.workExperience && input.workExperience.length
        ? input.workExperience.map((item) => {
            const merged = { ...createWorkItem(), ...(item as Partial<WorkExperienceItem>) }
            merged.startDate = normalizeMonthString(merged.startDate)
            merged.endDate = normalizeMonthString(merged.endDate)
            if (!merged.role && typeof merged.position === 'string') {
              merged.role = merged.position
            }
            return merged
          })
        : defaultProfile.workExperience,
    skills: Array.isArray(input.skills) ? input.skills.filter(Boolean) : [],
    certificates:
      input.certificates && input.certificates.length
        ? input.certificates.map((item) => normalizeCertificateItem(item))
        : defaultProfile.certificates,
    organizeExp: normalizeStringList((input as { organizeExp?: unknown }).organizeExp),
    projects: normalizeStringList((input as { projects?: unknown }).projects),
    selfEvaluation: input.selfEvaluation ?? '',
  }
}

function replaceProfileData(next: ProfileFormData) {
  profile.content = next.content ?? ''
  profile.profileId = next.profileId ?? ''
  profile.basicInfo = { ...next.basicInfo }
  profile.education = next.education.map((item) => ({ ...item }))
  profile.workExperience = next.workExperience.map((item) => ({ ...item }))
  profile.skills = [...next.skills]
  profile.certificates = next.certificates.map((item) => ({ ...item }))
  profile.organizeExp = [...next.organizeExp]
  profile.projects = [...next.projects]
  profile.selfEvaluation = next.selfEvaluation
}

// ---------- 多简历标签页 ----------
function genResumeId(): string {
  if (typeof crypto !== 'undefined' && typeof crypto.randomUUID === 'function') {
    return crypto.randomUUID()
  }
  return `resume-${Date.now()}-${Math.random().toString(36).slice(2, 10)}`
}

// 标签标题 = 简历第一行文本（去掉前导 markdown 标记，截断避免过长）
function extractResumeTitle(content?: string): string {
  const line = String(content ?? '').split('\n').map((l) => l.trim()).find((l) => l.length > 0) ?? ''
  const title = line.replace(/^[#>*_\-\s]+/, '').trim()
  const text = title || line || '未命名简历'
  return text.length > 12 ? `${text.slice(0, 12)}…` : text
}

function getResumeTitle(item: ResumeListItem): string {
  return extractResumeTitle(item.content)
}

function applyProfileContent(item: ResumeListItem) {
  const next = normalizeProfile({ profileId: item.profileId ?? undefined, content: item.content ?? '' })
  replaceProfileData(next)
  activeResumeId.value = item.profileId ?? ''
  savedSnapshot.value = JSON.stringify(normalizeProfile(profile))
}

async function loadResumeList(selectFirst = true) {
  listLoading.value = true
  try {
    const response = await getStudentProfileList()
    const result = response.data as ApiResponse<ResumeListItem[]>
    if (!isSuccessCode(Number(result.code))) return
    const payload = extractPayload<ResumeListItem[]>(response as { data: ApiResponse<ResumeListItem[]> })
    const list = Array.isArray(payload) ? payload : []
    resumeList.value = list

    if (list.length === 0) {
      // 没有任何简历：创建一个空白标签（含新生成的 profileId，保存时才落库）
      const blank: ResumeListItem = { profileId: genResumeId(), title: '未命名简历', content: '' }
      resumeList.value = [blank]
      applyProfileContent(blank)
      return
    }

    if (selectFirst) {
      applyProfileContent(list[0])
    }
  } finally {
    listLoading.value = false
  }
}

async function selectResume(item: ResumeListItem) {
  const pid = item.profileId ?? ''
  if (pid && pid === activeResumeId.value) return
  if (isEditing.value && hasUnsavedChanges.value) {
    try {
      await ElMessageBox.confirm('当前简历有未保存的修改，切换后将丢失，是否继续？', '切换简历', {
        type: 'warning',
        confirmButtonText: '继续切换',
        cancelButtonText: '留下继续编辑',
      })
    } catch {
      return
    }
  }
  applyProfileContent(item)
  isEditing.value = false
}

async function createNewResume() {
  if (isEditing.value && hasUnsavedChanges.value) {
    try {
      await ElMessageBox.confirm('当前简历有未保存的修改，新建简历后将丢失，是否继续？', '新建简历', {
        type: 'warning',
        confirmButtonText: '继续',
        cancelButtonText: '取消',
      })
    } catch {
      return
    }
  }
  const blank: ResumeListItem = { profileId: genResumeId(), title: '未命名简历', content: '' }
  resumeList.value = [...resumeList.value, blank]
  applyProfileContent(blank)
  isEditing.value = true
}

// 保存成功后，用当前 profile 的内容刷新对应标签的标题/内容
function refreshResumeListTab() {
  const pid = profile.profileId || ''
  if (!pid) return
  const title = extractResumeTitle(profile.content)
  const idx = resumeList.value.findIndex((it) => it.profileId === pid)
  if (idx >= 0) {
    resumeList.value[idx] = { ...resumeList.value[idx], title, content: profile.content ?? '' }
  } else {
    resumeList.value.push({ profileId: pid, title, content: profile.content ?? '' })
  }
}

function updateViewportMode() {
  isDesktop.value = window.innerWidth >= 1024
  if (!isDesktop.value) {
    viewMode.value = 'resume'
  }
}

function disposeRadarChart() {
  if (radarChart) {
    radarChart.dispose()
    radarChart = null
  }
}

function disposeRingCharts() {
  if (progressGaugeChart) {
    progressGaugeChart.dispose()
    progressGaugeChart = null
  }
}

function resizeInsightChartsDebounced() {
  if (chartResizeTimer) {
    clearTimeout(chartResizeTimer)
  }
  chartResizeTimer = setTimeout(() => {
    radarChart?.resize()
    progressGaugeChart?.resize()
  }, 160)
}

function renderRadarChart() {
  if (!isDesktop.value || viewMode.value !== 'insight') return
  if (!radarRef.value || !scoreData.value || !aggregateData.value) return

  if (!radarChart) {
    radarChart = echarts.init(radarRef.value)
  }

  const average = aggregateData.value.averageScores
  const indicators = [
    { name: '专业技能', max: 100 },
    { name: '证书能力', max: 100 },
    { name: '创新能力', max: 100 },
    { name: '内驱动力', max: 100 },
    { name: '学习能力', max: 100 },
    { name: '抗压能力', max: 100 },
    { name: '沟通能力', max: 100 },
    { name: '实习能力', max: 100 },
    { name: '语言能力', max: 100 },
    { name: '领导能力', max: 100 },
    { name: '适应能力', max: 100 },
    { name: '执行能力', max: 100 },
  ]

  const dimensionOrder: Array<keyof AbilityScores> = [
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
  ]

  const personalValues = dimensionOrder.map((key) => getFinalAbilityScore(key))
  const averageValues = dimensionOrder.map((key) => average[key] ?? 0)

  radarChart.setOption({
    tooltip: {
      trigger: 'item',
      formatter: (params: any) => {
        const values = Array.isArray(params?.value) ? params.value : []
        if (!values.length) return ''
        const title = params?.name || params?.seriesName || '雷达图'
        const rows = indicators
          .map((indicator, index) => `${indicator.name}：${values[index] ?? '-'}`)
          .join('<br/>')
        return `${title}<br/>${rows}`
      },
    },
    legend: {
      top: 5,
      data: ['个人画像', '同群体平均'],
    },
    radar: {
      center: ['50%', '55%'],
      radius: '58%',
      indicator: indicators,
      splitNumber: 5,
    },
    series: [
      {
        animation: true,
        animationDuration: 1200,        // 1.2秒入场动画
        animationEasing: 'elasticOut',   // 弹性效果，更生动
        animationDelay: 200,    // 0.2秒后开始动画
        type: 'radar',
        symbol: 'circle',
        symbolSize: 6,
        lineStyle: {
          width: 2,
        },
        emphasis: {
          focus: 'series',
        },
        data: [
          {
            value: personalValues,
            name: '个人画像',
            areaStyle: { opacity: 0.18 },
          },
          {
            value: averageValues,
            name: '同群体平均',
            areaStyle: { opacity: 0.1 },
          },
        ],
      },
    ],
  })
}

function buildProgressGaugeOption(completenessPersonal: number, completenessAverage: number, competitivenessPersonal: number, competitivenessAverage: number) {
  return {
    tooltip: {
      trigger: 'item',
      formatter: (params: any) => `${params.name}：${params.value}`,
    },
    series: [
      {
        animation: true,
        animationDuration: 1500,
        animationEasing: 'cubicOut',
        animationEasingUpdate: 'cubicInOut',
        type: 'gauge',
        startAngle: 210,
        endAngle: -30,
        min: 0,
        max: 100,
        splitNumber: 10,
        center: ['50%', '51%'],
        radius: '83%',
        anchor: {
          show: true,
          showAbove: true,
          size: 12,
          itemStyle: {
            color: '#facc15',
          },
        },
        pointer: {
          show: true,
          width: 3,
          length: '94%',
          offsetCenter: [0, '8%'],
        },
        progress: {
          show: true,
          overlap: true,
          roundCap: true,
          clip: true,
          itemStyle: {
            borderWidth: 0,
          },
        },
        axisLine: {
          roundCap: true,
          lineStyle: {
            width: 10,
            color: [[1, '#e2e8f0']],
          },
        },
        axisTick: {
          distance: -12,
          splitNumber: 4,
          lineStyle: {
            width: 1,
            color: '#9ca3af',
          },
        },
        splitLine: {
          distance: -12,
          length: 10,
          lineStyle: {
            width: 2,
            color: '#6b7280',
          },
        },
        axisLabel: {
          distance: -34,
          color: '#4b5563',
          fontSize: 11,
        },
        title: {
          fontSize: 11,
          fontWeight: 600,
          color: '#334155',
        },
        detail: {
          width: 56,
          height: 16,
          fontSize: 12,
          fontWeight: 700,
          color: '#ffffff',
          borderRadius: 8,
          padding: [2, 8],
          formatter: '{value}%',
          backgroundColor: 'auto',
          valueAnimation: true,
        },
        data: [
          {
            value: completenessPersonal,
            name: '完整度（个人）',
            title: { offsetCenter: ['-42%', '48%'] },
            detail: { offsetCenter: ['-42%', '59%'] },
            itemStyle: { color: '#15803d' },
          },
          {
            value: completenessAverage,
            name: '完整度（平均）',
            title: { offsetCenter: ['42%', '48%'] },
            detail: { offsetCenter: ['42%', '59%'] },
            itemStyle: { color: '#4ade80' },
          },
          {
            value: competitivenessPersonal,
            name: '竞争力（个人）',
            title: { offsetCenter: ['-42%', '72%'] },
            detail: { offsetCenter: ['-42%', '83%'] },
            itemStyle: { color: '#1e3a8a' },
          },
          {
            value: competitivenessAverage,
            name: '竞争力（平均）',
            title: { offsetCenter: ['42%', '72%'] },
            detail: { offsetCenter: ['42%', '83%'] },
            itemStyle: { color: '#60a5fa' },
          },
        ],
      },
    ],
  }
}

function renderRingCharts() {
  if (!isDesktop.value || viewMode.value !== 'insight') return
  if (!scoreData.value || !aggregateData.value) return

  if (progressGaugeRef.value) {
    if (!progressGaugeChart) progressGaugeChart = echarts.init(progressGaugeRef.value)
    const finalOption = buildProgressGaugeOption(
      scoreData.value.completenessScore,
      aggregateData.value.averageProgress.completenessScore,
      scoreData.value.competitivenessScore,
      aggregateData.value.averageProgress.competitivenessScore,
    )
    progressGaugeChart.setOption(finalOption, true)
  }
}

async function fetchAggregateData(force = false) {
  if (!isDesktop.value) return
  if (!force && aggregateData.value) return

  aggregateLoading.value = true
  try {
    const response = await getStudentProfileAggregate()
    const result = response.data as ApiResponse<ProfileAggregateResult>
    if (!isSuccessCode(Number(result.code))) return
    const payload = extractPayload<ProfileAggregateResult>(response as { data: ApiResponse<ProfileAggregateResult> })
    aggregateData.value = payload ?? null
  } finally {
    aggregateLoading.value = false
  }
}

function handleUploadChange() {
  fileInputRef.value?.click()
}

async function handleFileSelected(event: Event) {
  const input = event.target as HTMLInputElement
  const file = input.files?.[0]
  input.value = ''
  if (!file) return
  await uploadAndParseFile(file)
}

async function uploadAndParseFile(file: File) {
  const isImage = file.type.startsWith('image/')
  parseLoading.value = true
  try {
    // PDF 走解析接口，图片走视觉解析接口
    const response = isImage ? await parseImageResume(file) : await createParseProfileJob(file)
    const result = response.data as ApiResponse<ParseJobCreated>
    if (!isSuccessCode(Number(result.code))) {
      ElMessage.error(result.msg || '上传失败')
      return
    }

    ElMessage.success('上传成功，正在解析简历，请稍候...')
    const payload = extractPayload<ParseJobCreated>(response as { data: ApiResponse<ParseJobCreated> })
    await waitForProfileParsed(payload?.pollAfterMs ?? 2000)
    await loadProfile()
  } catch {
    ElMessage.error('上传解析失败，请检查网络后重试')
  } finally {
    parseLoading.value = false
  }
}

async function waitForProfileParsed(pollAfterMs: number, maxRounds = 30) {
  for (let index = 0; index < maxRounds; index += 1) {
    if (!isPageActive) return
    await new Promise((resolve) => setTimeout(resolve, Math.max(1000, Math.min(3000, pollAfterMs))))
    try {
      const response = await getStudentProfile()
      const payload = extractPayload<GetProfileResult>(response as { data: ApiResponse<GetProfileResult> })
      if (payload?.hasProfile && payload.profile) return
    } catch {
      return
    }
  }
}

async function loadProfile() {
  initLoading.value = true
  scorePanelLoading.value = true
  suggestionPanelLoading.value = true
  try {
    // 简历内容来自标签列表（多简历）；评分/证据/建议来自单份快照（用户级）
    await loadResumeList(true)
    const payload = await appStore.ensureProfileSnapshot(true)
    hasServerProfile.value = Boolean(payload?.hasProfile)
    if (!payload?.hasProfile || !payload.profile) {
      isEditing.value = false
      savedSnapshot.value = JSON.stringify(normalizeProfile(profile))
      scoreData.value = null
      suggestions.value = []
      evidenceMap.value = {}
      if (!localStorage.getItem(tourSeenStorageKey)) {
        nextTick(() => {
          showTour.value = true
        })
      }
      return
    }

    scoreData.value = payload.scores
    evidenceMap.value = payload.evidence ?? {}
    suggestions.value = payload.improvementSuggestions ?? []
    updatedAt.value = payload.updatedAt ?? ''
    profileId.value = payload.profileId ?? ''
    isEditing.value = false
    savedSnapshot.value = JSON.stringify(normalizeProfile(profile))

    // 已取消自动评分：没有历史评分就停留在简历视图，不再轮询评分结果
    if (!payload.scores) {
      viewMode.value = 'resume'
      return
    }

    await fetchAggregateData(false)
    viewMode.value = isDesktop.value ? 'insight' : 'resume'
    if (viewMode.value === 'insight') {
      nextTick(() => {
        renderRadarChart()
        renderRingCharts()
        resizeInsightChartsDebounced()
      })
    }
  } catch {
    ElMessage.error('获取学生画像失败')
  } finally {
    initLoading.value = false
    scorePanelLoading.value = false
    suggestionPanelLoading.value = false
  }
}

async function refreshScoreResult(maxRetry = 3) {
  scorePanelLoading.value = true
  suggestionPanelLoading.value = true
  try {
    for (let i = 0; i < maxRetry; i += 1) {
      const response = await getStudentProfile()
      const result = response.data as ApiResponse<GetProfileResult>
      if (!isSuccessCode(Number(result.code))) break
      const payload = extractPayload<GetProfileResult>(response as { data: ApiResponse<GetProfileResult> })
      if (payload) {
        appStore.setProfileSnapshot(payload)
      }
      if (payload?.scores) {
        scoreData.value = payload.scores
        evidenceMap.value = payload.evidence ?? {}
        suggestions.value = payload.improvementSuggestions ?? []
        updatedAt.value = payload.updatedAt ?? updatedAt.value
        return true
      }
      await new Promise((resolve) => setTimeout(resolve, 700))
    }
    return false
  } catch {
    return false
  } finally {
    scorePanelLoading.value = false
    suggestionPanelLoading.value = false
  }
}

async function refreshProfileAfterOpenSourceChange() {
  if (!hasServerProfile.value) return
  const refreshed = await refreshScoreResult(5)
  if (!refreshed) {
    await loadProfile()
    return
  }
  if (viewMode.value === 'insight') {
    nextTick(() => {
      renderRadarChart()
      renderRingCharts()
      resizeInsightChartsDebounced()
    })
  }
}

async function handleOpenSourceAuthorized() {
  await refreshProfileAfterOpenSourceChange()
}

async function handleOpenSourceUnbound() {
  await refreshProfileAfterOpenSourceChange()
}

async function saveProfile() {
  if (saveLoading.value) return
  if (!profile.content?.trim()) {
    ElMessage.warning('请填写简历内容')
    return
  }

  saveLoading.value = true
  scorePanelLoading.value = true
  suggestionPanelLoading.value = true
  try {
    const response = await saveStudentProfile(normalizeProfile(profile))
    const result = response.data as ApiResponse<SaveProfileResult>
    if (!isSuccessCode(Number(result.code))) {
      ElMessage.error(result.msg || '保存失败')
      return
    }

    const payload = extractPayload<SaveProfileResult>(response as { data: ApiResponse<SaveProfileResult> })
    profileId.value = payload?.profileId ?? ''
    // 后端回显前端生成的简历id，更新到当前简历，并刷新对应标签
    if (payload?.profileId) {
      profile.profileId = payload.profileId
    }
    updatedAt.value = payload?.updatedAt ?? ''
    hasServerProfile.value = true
    isEditing.value = false
    refreshResumeListTab()
    savedSnapshot.value = JSON.stringify(normalizeProfile(profile))

    if (payload?.scores) {
      scoreData.value = payload.scores
      evidenceMap.value = payload?.evidence ?? {}
      suggestions.value = payload?.improvementSuggestions ?? []
    } else {
      // 后端已取消自动评分，保存后不再轮询评分结果，评分面板保持空态
      scoreData.value = null
      suggestions.value = []
      evidenceMap.value = {}
    }

    await fetchAggregateData(true)
    appStore.setProfileSnapshot({
      hasProfile: true,
      profileId: profileId.value || null,
      profile: normalizeProfile(profile),
      scores: scoreData.value ?? null,
      evidence: evidenceMap.value,
      improvementSuggestions: suggestions.value,
      updatedAt: updatedAt.value || null,
    })
    ElMessage.success('保存成功')
    return true
  } catch {
    ElMessage.error('保存失败，请稍后重试')
    return false
  } finally {
    saveLoading.value = false
    scorePanelLoading.value = false
    suggestionPanelLoading.value = false
  }
}

async function handlePrimaryAction() {
  if (!isEditing.value) {
    isEditing.value = true
    return
  }

  if (!hasUnsavedChanges.value) {
    isEditing.value = false
    ElMessage.info('未检测到内容变更，已取消编辑')
    return
  }

  await saveProfile()
}

async function handleToggleDetail() {
  if (!showDetailButton.value) return
  if (viewMode.value === 'resume' && isEditing.value) {
    ElMessage.warning('请先保存当前正在编辑的内容')
    return
  }

  if (viewMode.value === 'resume') {
    await fetchAggregateData(false)
    viewMode.value = 'insight'
    nextTick(() => {
      renderRadarChart()
      renderRingCharts()
      resizeInsightChartsDebounced()
    })
    return
  }

  viewMode.value = 'resume'
}

function cancelEditing() {
  let fallback: ProfileFormData | null = null
  try {
    fallback = normalizeProfile(JSON.parse(savedSnapshot.value) as ProfileFormData)
  } catch {
    fallback = normalizeProfile(profile)
  }
  replaceProfileData(fallback)
  isEditing.value = false
  ElMessage.info('已取消编辑并恢复到上次保存内容')
}

function getTourEditTarget() {
  return tourEditBtnRef.value ?? null
}

function getTourUploadTarget() {
  return tourUploadRef.value ?? null
}

function getTourScoreTarget() {
  return tourScoreRef.value ?? null
}

function handleSuggestionClick(dimension: string) {
  const map: Record<string, keyof typeof sectionMeta> = {
    professionalSkill: 'skills',
    certificate: 'certificates',
    innovation: 'selfEvaluation',
    internalMotivation: 'selfEvaluation',
    learning: 'selfEvaluation',
    stressTolerance: 'selfEvaluation',
    communication: 'selfEvaluation',
    internship: 'workExperience',
    language: 'certificates',
    leadership: 'workExperience',
    adaptability: 'selfEvaluation',
    execution: 'workExperience',
  }

  const section = map[dimension]
  if (!section) return
  const target = document.getElementById(`section-${section}`)
  if (target) {
    target.scrollIntoView({ behavior: 'smooth', block: 'start' })
  }
}

function handleTourClose() {
  showTour.value = false
  localStorage.setItem(tourSeenStorageKey, '1')
}

async function handleTaskOrchestratorRouteRefreshEvent(event: Event) {
  const detail = (event as CustomEvent<TaskOrchestratorRouteRefreshPayload>).detail || {}
  if (String(detail.routePath || '').trim() !== '/student') return
  if (!isPageActive) return
  if (parseLoading.value || saveLoading.value) return
  if (isEditing.value && hasUnsavedChanges.value) return

  await loadProfile().catch(() => {})
}

// 手动刷新：重新请求所有简历（尽量保留当前选中的那份）与评分/建议快照，无需刷新整个网页
async function handleRefreshData() {
  if (parseLoading.value || saveLoading.value) {
    ElMessage.info('正在上传或保存，请稍后再刷新')
    return
  }
  if (isEditing.value && hasUnsavedChanges.value) {
    ElMessage.warning('当前有未保存的修改，请先保存或取消后再刷新')
    return
  }
  if (refreshLoading.value) return
  refreshLoading.value = true
  try {
    // ① 重新拉取简历列表
    const prevId = activeResumeId.value
    const response = await getStudentProfileList()
    const result = response.data as ApiResponse<ResumeListItem[]>
    if (!isSuccessCode(Number(result.code))) {
      ElMessage.error(result.msg || '刷新简历列表失败')
      return
    }
    const payload = extractPayload<ResumeListItem[]>(response as { data: ApiResponse<ResumeListItem[]> })
    const list = Array.isArray(payload) ? payload : []
    resumeList.value = list

    if (list.length === 0) {
      // 没有任何简历：创建一个空白标签（保存时才落库）
      const blank: ResumeListItem = { profileId: genResumeId(), title: '未命名简历', content: '' }
      resumeList.value = [blank]
      applyProfileContent(blank)
    } else {
      // 当前选中的简历还在则保留，否则回落到第一份
      const keep = list.find((it) => it.profileId && it.profileId === prevId) ?? list[0]
      applyProfileContent(keep)
    }

    // ② 重新拉取评分/证据/改进建议（force=true 跳过 store 缓存）
    const snapshot = await appStore.ensureProfileSnapshot(true)
    hasServerProfile.value = Boolean(snapshot?.hasProfile)
    if (snapshot?.hasProfile && snapshot.profile) {
      scoreData.value = snapshot.scores
      evidenceMap.value = snapshot.evidence ?? {}
      suggestions.value = snapshot.improvementSuggestions ?? []
      updatedAt.value = snapshot.updatedAt ?? ''
      profileId.value = snapshot.profileId ?? ''
      isEditing.value = false
      savedSnapshot.value = JSON.stringify(normalizeProfile(profile))
      if (isDesktop.value && viewMode.value === 'insight' && snapshot.scores) {
        await fetchAggregateData(true)
        nextTick(() => {
          renderRadarChart()
          renderRingCharts()
          resizeInsightChartsDebounced()
        })
      }
    } else {
      isEditing.value = false
      scoreData.value = null
      suggestions.value = []
      evidenceMap.value = {}
      savedSnapshot.value = JSON.stringify(normalizeProfile(profile))
    }
    ElMessage.success('已刷新简历数据')
  } catch {
    ElMessage.error('刷新失败，请稍后重试')
  } finally {
    refreshLoading.value = false
  }
}

onBeforeRouteLeave(async () => {
  if (saveLoading.value) return true
  if (!isEditing.value || !hasUnsavedChanges.value) return true
  try {
    await ElMessageBox.confirm('当前有未保存修改，离开页面将丢失，是否继续离开？', '离开确认', {
      type: 'warning',
      confirmButtonText: '离开',
      cancelButtonText: '留下继续编辑',
    })
    return true
  } catch {
    return false
  }
})

onMounted(() => {
  isPageActive = true
  updateViewportMode()
  window.addEventListener('resize', updateViewportMode)
  window.addEventListener('resize', resizeInsightChartsDebounced)
  window.addEventListener(TASK_ORCHESTRATOR_ROUTE_REFRESH_EVENT, handleTaskOrchestratorRouteRefreshEvent as EventListener)
  void loadProfile()
})

onBeforeUnmount(() => {
  isPageActive = false
  window.removeEventListener('resize', updateViewportMode)
  window.removeEventListener('resize', resizeInsightChartsDebounced)
  window.removeEventListener(TASK_ORCHESTRATOR_ROUTE_REFRESH_EVENT, handleTaskOrchestratorRouteRefreshEvent as EventListener)
  if (chartResizeTimer) {
    clearTimeout(chartResizeTimer)
  }
  if (highlightTimer) {
    clearTimeout(highlightTimer)
  }
  disposeRadarChart()
  disposeRingCharts()
})

watch([viewMode, scoreData, aggregateData, isDesktop], () => {
  if (viewMode.value === 'insight' && isDesktop.value && scoreData.value && aggregateData.value) {
    nextTick(() => {
      renderRadarChart()
      renderRingCharts()
      resizeInsightChartsDebounced()
    })
  } else if (viewMode.value !== 'insight') {
    disposeRadarChart()
    disposeRingCharts()
  }
})
</script>

<template>
  <section class="space-y-4 pb-24 lg:pb-0" v-loading="initLoading">
    <div class="flex flex-col gap-3 rounded-2xl border border-slate-200 bg-white p-4 shadow-sm md:flex-row md:items-center md:justify-between md:p-5">
      <div>
        <h2 class="text-lg font-semibold text-slate-900 md:text-2xl">个人就业能力分析</h2>
        <p class="mt-1 text-xs text-slate-500 md:text-sm">通过上传文件或自行录入简历，使用大模型拆解、分析，对学生就业能力进行完整度、竞争力评分。</p>
      </div>
      <div class="flex flex-wrap items-center gap-2 text-xs text-slate-500">
        <span v-if="formattedUpdatedAt" class="rounded-full bg-slate-100 px-3 py-1">最近保存：{{ formattedUpdatedAt }}</span>
        <el-button size="small" type="primary" plain :loading="refreshLoading" :icon="Refresh" @click="handleRefreshData">刷新</el-button>
      </div>
    </div>

    <div class="grid gap-4 xl:grid-cols-[minmax(0,7fr)_minmax(320px,3fr)]">
      <div class="left-slider overflow-hidden">
        <div class="left-slider-track" :style="sliderTrackStyle">
          <div class="left-slider-panel" :style="sliderPanelStyle">
            <el-card class="resume-card !overflow-visible" shadow="never">
              <div class="space-y-6 parse-loading-wrap" v-loading="parseLoading" element-loading-text="正在上传并解析简历..." element-loading-background="rgba(255,255,255,0)">
                <header class="resume-header border-b border-[#9db2c0] pb-5">
                  <div class="flex flex-col gap-4 md:flex-row md:items-start md:justify-between">
                    <div>
                      <div class="flex flex-wrap items-center gap-2">
                        <div class="resume-tabs flex items-end gap-1" v-loading="listLoading">
                          <button
                            v-for="(item, index) in resumeList"
                            :key="item.profileId || `new-${index}`"
                            type="button"
                            class="resume-tab"
                            :class="{ 'resume-tab-active': item.profileId === activeResumeId }"
                            :title="getResumeTitle(item)"
                            @click="selectResume(item)"
                          >
                            {{ getResumeTitle(item) }}
                          </button>
                          <button type="button" class="resume-tab-new" title="新建简历" @click="createNewResume">＋</button>
                        </div>
                        <div ref="tourEditBtnRef">
                          <el-button
                            size="small"
                            type="primary"
                            :icon="isEditing ? Check : EditPen"
                            :loading="saveLoading"
                            @click="handlePrimaryAction"
                          >
                            {{ actionButtonText }}
                          </el-button>
                        </div>
                        <el-button v-if="isEditing" size="small" @click="cancelEditing">取消</el-button>
                      </div>
                      <p class="mt-1 text-sm text-[#6a8a9b]">Personal Resume · 细心从每一个细节开始</p>
                    </div>
                    <div class="flex flex-col items-start gap-2 md:items-end">
                      <div ref="tourUploadRef">
                        <el-button type="primary" plain :loading="parseLoading" @click="handleUploadChange">上传简历并解析</el-button>
                        <input ref="fileInputRef" type="file" class="hidden" accept=".pdf,.jpg,.jpeg,.png,.gif,.bmp,.webp,application/pdf,image/*" @change="handleFileSelected" />
                      </div>
                      <p class="text-xs text-slate-500">支持 PDF / 图片简历（jpg、png、webp），解析后自动填充到简历中</p>
                    </div>
                  </div>
                </header>

                <el-form ref="formRef" :model="profile" label-position="top" :disabled="!isEditing || parseLoading || saveLoading" class="space-y-5">
            <section id="section-basicInfo" class="resume-section">
              <div class="resume-title">基本信息</div>
              <el-input
                v-if="isEditing"
                v-model="profile.content"
                type="textarea"
                :rows="18"
                class="resume-editor mt-4"
                placeholder="请输入简历内容（支持 Markdown 语法）"
              />
              <div v-else-if="profile.content" class="resume-markdown mt-4" v-html="resumeHtml"></div>
              <el-empty v-else description="还没有简历" :image-size="80" />
            </section>

                  <div v-if="isEditing" class="hidden items-center justify-end gap-2 border-t border-slate-200 pt-4 lg:flex">
                    <el-button @click="cancelEditing">取消</el-button>
                    <el-button type="primary" :loading="saveLoading" @click="handlePrimaryAction">保存并分析</el-button>
                  </div>
                </el-form>
              </div>
            </el-card>
          </div>

          <div v-if="isDesktop" class="left-slider-panel" :style="sliderPanelStyle">
            <el-card class="insight-card" :class="{ 'insight-chart-enter': insightChartVisible }" shadow="never">
              <template #header>
                <div class="flex items-center justify-between">
                  <div>
                    <p class="text-base font-semibold text-slate-800">综合评价</p>
                  </div>
                  <el-button type="primary" plain @click="handleToggleDetail">返回查看简历</el-button>
                </div>
              </template>

              <div class="space-y-4">
                <div class="grid gap-4 lg:grid-cols-2">
                  <el-card shadow="never">
                    <template #header>
                      <span class="font-medium">综合进度得分环</span>
                    </template>
                    <div v-loading="aggregateLoading" class="w-full">
                      <div ref="progressGaugeRef" class="h-[380px] w-full"></div>
                    </div>
                  </el-card>

                  <el-card shadow="never">
                    <template #header>
                      <span class="font-medium">就业能力雷达图</span>
                    </template>
                    <div v-loading="aggregateLoading" class="min-h-[300px]">
                      <div ref="radarRef" class="h-[380px] w-full"></div>
                    </div>
                  </el-card>
                </div>

                <el-card shadow="never">
                  <div class="word-cloud-box">
                    <WordCloudChart v-if="keywordTerms.length" :words="keywordTerms" />
                    <el-empty v-else class="student-empty" :image="studentEmptyImageUrl" description="暂无关键词" :image-size="70" />
                  </div>
                </el-card>

                <el-card shadow="never">
                  <template #header>
                    <span class="font-medium">开源平台授权与能力加分</span>
                  </template>
                  <OpenSourceBonusCard @authorized="handleOpenSourceAuthorized" @unbound="handleOpenSourceUnbound" />
                </el-card>
              </div>
            </el-card>
          </div>
        </div>
      </div>

      <aside ref="tourScoreRef" class="space-y-4" :class="scoreAsideClass">
        <el-card shadow="never">
          <template #header>
            <div class="flex items-center justify-between">
              <span class="font-medium">评分概览</span>
              <div class="flex items-center gap-2">
                <el-button v-if="showDetailButton" class="hidden lg:inline-flex" type="primary" text @click="handleToggleDetail">{{ detailButtonText }}</el-button>
                <el-tag v-if="profileId" type="success">已保存</el-tag>
                <el-tag v-else type="info">未保存</el-tag>
              </div>
            </div>
          </template>

          <div v-loading="scorePanelLoading" class="min-h-[180px]">
            <div v-if="scoreData" class="space-y-3">
            <div class="grid grid-cols-2 gap-3">
              <div class="rounded-lg bg-slate-50 p-3 text-center">
                <p class="text-xs text-slate-500">完整度</p>
                <p class="text-2xl font-semibold text-[#3d6c85]">{{ scoreData.completenessScore }}</p>
              </div>
              <div class="rounded-lg bg-slate-50 p-3 text-center">
                <p class="text-xs text-slate-500">竞争力</p>
                <p class="text-2xl font-semibold text-[#3d6c85]">{{ scoreData.competitivenessScore }}</p>
              </div>
            </div>

            <div class="space-y-3">
              <div
                v-for="section in abilitySections"
                :key="section.key"
                class="rounded-lg border border-slate-200 p-2"
              >
                <p class="mb-2 text-xs font-semibold tracking-wide text-slate-500">{{ section.label }}</p>
                <div class="space-y-2">
                  <div v-for="ability in section.abilities" :key="ability.key" class="space-y-1 cursor-pointer rounded-md p-1 transition hover:bg-slate-50" @click="handleSuggestionClick(ability.key)">
                    <div class="flex items-center justify-between text-sm">
                      <el-tooltip :content="ability.description" placement="top" effect="dark">
                        <span class="text-slate-600">{{ ability.label }}</span>
                      </el-tooltip>
                      <span class="font-medium" :style="{ color: ability.progressColor }">
                        {{ ability.score }}
                        <span v-if="ability.bonusScore > 0" class="ml-1 text-xs text-emerald-600">({{ ability.baseScore }}+{{ ability.bonusScore }})</span>
                      </span>
                    </div>
                    <el-progress :show-text="false" :percentage="ability.score" :stroke-width="8" :color="ability.progressColor" />
                    <p v-if="ability.evidence.length" class="text-xs text-slate-500">{{ ability.evidence.slice(0, 2).join('；') }}</p>
                  </div>
                </div>
              </div>
            </div>
            </div>

            <el-empty v-else class="student-empty" :image="studentEmptyImageUrl" description="暂无评分数据" :image-size="80" />
          </div>
        </el-card>

        <el-card shadow="never">
          <template #header>
            <span class="font-medium">优先改进建议</span>
          </template>

          <div v-loading="suggestionPanelLoading" class="min-h-[120px]">
            <div v-if="suggestions.length" class="space-y-3">
            <div v-for="(item, index) in suggestions" :key="`${item.dimension}-${index}`" class="cursor-pointer rounded-lg border border-slate-200 p-3 transition hover:border-slate-300 hover:bg-slate-50" @click="handleSuggestionClick(item.dimension)">
              <div class="mb-1 flex items-center justify-between">
                <span class="text-sm font-medium text-slate-700">{{ abilityLabelMap[item.dimension as keyof AbilityScores] || item.dimension }}</span>
                <el-tag :type="item.priority === 'high' ? 'danger' : item.priority === 'medium' ? 'warning' : 'info'" size="small">
                  {{ item.priority === 'high' ? '高优先级' : item.priority === 'medium' ? '中优先级' : '低优先级' }}
                </el-tag>
              </div>
              <p class="text-sm text-slate-600">{{ item.advice }}</p>
            </div>
            </div>
            <el-empty v-else class="student-empty" :image="studentEmptyImageUrl" description="暂无改进建议" :image-size="80" />
          </div>
        </el-card>

        <el-card v-if="!hasServerProfile" shadow="never">
          <p class="text-sm text-slate-600">你还没有保存过画像，建议先上传简历，或点击“手动编辑”填写简历内容（支持 Markdown），再点击“保存并分析”。</p>
        </el-card>
      </aside>
    </div>

    <div v-if="isEditing" class="fixed inset-x-0 bottom-0 z-30 border-t bg-white/95 p-3 backdrop-blur lg:hidden">
      <el-button class="w-full" type="primary" :loading="saveLoading" @click="handlePrimaryAction">保存并分析</el-button>
    </div>

    <el-backtop :right="24" :bottom="96" />

    <el-tour v-model="showTour" @close="handleTourClose">
      <el-tour-step title="开始填写简历" description="先点击编辑，进入可填写状态。" :target="getTourEditTarget" />
      <el-tour-step title="上传自动解析" description="支持 PDF / 图片简历（jpg、png、webp），解析后自动填充到简历中。" :target="getTourUploadTarget" />
      <el-tour-step title="查看评分建议" description="保存分析后，在右侧查看评分、证据与改进建议。" :target="getTourScoreTarget" />
    </el-tour>
  </section>
</template>

<style scoped>
.resume-card {
  border: 1px solid #c5d3db;
  background: linear-gradient(180deg, #fcfdff 0%, #f9fbfc 100%);
}

.insight-card {
  border: 1px solid #dbe4ea;
  background: #f8fafc;
  opacity: 0.88;
  transition: opacity 320ms ease;
}

.insight-card.insight-chart-enter {
  opacity: 1;
}

.left-slider-track {
  display: flex;
  align-items: flex-start;
  transition: transform 420ms ease;
  will-change: transform;
}

.left-slider-panel {
  flex: 0 0 auto;
  min-width: 0;
}

.resume-header {
  position: relative;
}

.resume-tabs {
  display: inline-flex;
  align-items: flex-end;
  gap: 2px;
}

.resume-tab {
  position: relative;
  max-width: 168px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  padding: 7px 14px;
  font-size: 13px;
  line-height: 1.4;
  color: #64748b;
  background: #eef2f5;
  border: 1px solid #cbd5e1;
  border-bottom: none;
  border-radius: 10px 10px 0 0;
  cursor: pointer;
  transition: all 160ms ease;
}

.resume-tab:hover {
  color: #3d6c85;
  background: #e4eaf0;
}

.resume-tab-active {
  color: #fff;
  font-weight: 600;
  background: linear-gradient(180deg, #3f6f88 0%, #547f96 100%);
  border-color: #3f6f88;
}

.resume-tab-new {
  width: 32px;
  height: 32px;
  margin-left: 2px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  border-radius: 8px 8px 0 0;
  border: 1px dashed #cbd5e1;
  border-bottom: none;
  color: #94a3b8;
  background: transparent;
  font-size: 16px;
  line-height: 1;
  cursor: pointer;
  transition: all 160ms ease;
}

.resume-tab-new:hover {
  color: #3d6c85;
  border-color: #3d6c85;
  background: #f0f7fa;
}

.resume-section {
  border: 1px solid #d5e0e6;
  border-radius: 10px;
  padding: 16px;
  background: #ffffff;
}

.resume-title {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  margin-top: -30px;
  margin-bottom: 8px;
  padding: 6px 14px;
  border-radius: 0 8px 8px 0;
  background: linear-gradient(90deg, #3f6f88 0%, #547f96 100%);
  color: #fff;
  font-weight: 600;
}

.resume-rate {
  font-size: 12px;
  font-weight: 400;
  color: #dce8ee;
}

.resume-markdown {
  line-height: 1.8;
  color: rgb(51 65 85);
  font-size: 14px;
  word-break: break-word;
}

.resume-markdown h1,
.resume-markdown h2,
.resume-markdown h3,
.resume-markdown h4 {
  margin: 16px 0 8px;
  font-weight: 600;
  color: rgb(30 41 59);
}

.resume-markdown h1:first-child,
.resume-markdown h2:first-child,
.resume-markdown h3:first-child {
  margin-top: 0;
}

.resume-markdown p {
  margin: 6px 0;
}

.resume-markdown ul,
.resume-markdown ol {
  margin: 6px 0;
  padding-left: 22px;
}

.resume-markdown li {
  margin: 3px 0;
}

.resume-markdown strong {
  color: rgb(30 41 59);
}

.resume-markdown code {
  padding: 2px 5px;
  border-radius: 4px;
  background: rgb(241 245 249);
  font-size: 13px;
}

.resume-markdown blockquote {
  margin: 8px 0;
  padding: 4px 12px;
  border-left: 3px solid #3f6f88;
  color: rgb(71 85 105);
  background: rgb(248 250 252);
}

.resume-markdown hr {
  margin: 14px 0;
  border: none;
  border-top: 1px solid rgb(226 232 240);
}

.resume-markdown a {
  color: #3f6f88;
}

.resume-editor {
  font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
  line-height: 1.7;
}

.auto-fill-highlight {
  box-shadow: 0 0 0 2px rgba(34, 197, 94, 0.28) inset;
  background: rgba(240, 253, 244, 0.7);
  transition: all 0.5s ease;
}

.word-cloud-box {
  min-height: 260px;
  border-radius: 10px;
  background: transparent;
}

:deep(.el-card__body .echarts) {
  width: 100%;
}

:deep(.el-input.is-disabled .el-input__inner),
:deep(.el-textarea.is-disabled .el-textarea__inner),
:deep(.el-select .el-input.is-disabled .el-input__inner),
:deep(.el-date-editor.is-disabled .el-input__inner) {
  color: rgb(30 41 59);
  -webkit-text-fill-color: rgb(30 41 59);
  opacity: 1;
}

:deep(.el-input.is-disabled .el-input__inner::placeholder),
:deep(.el-textarea.is-disabled .el-textarea__inner::placeholder) {
  color: rgb(148 163 184);
  -webkit-text-fill-color: rgb(148 163 184);
}

.student-empty :deep(.el-empty__image img) {
  opacity: 0.2;
}
</style>
