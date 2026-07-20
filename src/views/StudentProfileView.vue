<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import { onBeforeRouteLeave } from 'vue-router'
import { ElMessage, ElMessageBox, ElNotification } from 'element-plus'
import { Check, EditPen, Plus } from '@element-plus/icons-vue'
import * as echarts from 'echarts'
import WordCloudChart from '../components/WordCloudChart.vue'
import OpenSourceBonusCard from '../components/OpenSourceBonusCard.vue'
import studentEmptyImage from '../assets/student.png'
import {
  getProfileAnalyzeJobStatus,
  getStudentProfileAggregate,
  getStudentProfile,
  saveStudentProfile,
  type AbilityScores,
  type CertificateItem,
  type EducationItem,
  type GetProfileResult,
  type ImprovementSuggestion,
  type ProfileAnalyzeJobStatusResult,
  type ProfileAggregateResult,
  type ProfileFormData,
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

interface DemoParsedPayload {
  parsedProfile: Partial<ProfileFormData>
  missingFields: string[]
}

const formRef = ref()
const appStore = useAppStore()
const initLoading = ref(false)
const parseLoading = ref(false)
const saveLoading = ref(false)
const scorePanelLoading = ref(false)
const suggestionPanelLoading = ref(false)
const analysisPending = ref(false)
const aggregateLoading = ref(false)
const isEditing = ref(false)
const isDesktop = ref(window.innerWidth >= 1024)
const viewMode = ref<'resume' | 'insight'>('resume')
const hasServerProfile = ref(false)
const updatedAt = ref('')
const profileId = ref('')
const missingFields = ref<string[]>([])
const savedSnapshot = ref('')
const autoFilledFields = ref<string[]>([])
let highlightTimer: ReturnType<typeof setTimeout> | null = null

const showTour = ref(false)
const tourSeenStorageKey = 'student_profile_tour_seen'
const tourEditBtnRef = ref<HTMLElement>()
const tourUploadRef = ref<HTMLElement>()
const tourScoreRef = ref<HTMLElement>()
let isPageActive = true
const studentEmptyImageUrl = studentEmptyImage
const demoUploadDialogVisible = ref(false)

const DEMO_PARSED_PAYLOAD: DemoParsedPayload = {
  parsedProfile: {
    basicInfo: {
      name: '王晨曦',
      gender: 'female',
      birthday: '2003-10-12',
      phone: '13912345678',
      email: 'chenxi.wang@example.com',
      city: '杭州',
      jobIntention: ['Java_初级软件开发工程师'],
    },
    education: [
      {
        school: '虚拟大学',
        major: '软件工程',
        degree: '本科',
        startDate: '2021-09',
        endDate: '2025-06',
        gpa: '3.84/4.00',
      },
    ],
    workExperience: [
      {
        company: '杭州云桥科技有限公司',
        role: 'Java后端开发实习生',
        startDate: '2024-07',
        endDate: '2024-12',
        description: '参与校园招聘系统微服务开发，负责用户中心、权限模块与日志链路，推动接口平均响应时间下降约23%。',
      },
      {
        company: '校企联合项目组',
        role: '后端开发负责人',
        startDate: '2024-03',
        endDate: '2024-06',
        description: '主导教学资源平台后端设计，完成鉴权、题库、学习记录与报表模块，交付后稳定支撑千人级并发演示。',
      },
    ],
    skills: ['Java', 'Spring Boot', 'Spring Cloud', 'MySQL', 'Redis', 'RabbitMQ', 'Docker', 'Git', 'JUnit', 'MyBatis-Plus'],
    certificates: [
      {
        name: '大学英语六级（CET-6）',
        date: '2023-12',
        issuer: '教育部考试中心',
      },
      {
        name: '软考-软件设计师',
        date: '2024-11',
        issuer: '工业和信息化部教育与考试中心',
      },
    ],
    organizeExp: [
      '担任学院技术协会副会长，组织8场后端技术分享与2次校内黑客松。',
      '担任班级学习委员，搭建学习进度看板并推动结对评审机制。',
    ],
    projects: [
      '校园就业服务平台：基于Spring Boot + Vue3，实现岗位检索、简历投递、进度跟踪与消息中心，负责后端核心模块设计与联调。',
      '智能问答知识库服务：使用向量检索与缓存分层，完成问答召回与重排接口，平均响应时间控制在400ms内。',
      '课程实验管理系统：引入Docker化部署与CI脚本，缩短测试环境搭建时间约60%。',
    ],
    selfEvaluation: '我具备扎实的Java后端开发基础和稳定交付能力，能够围绕业务目标完成需求拆解、接口设计、编码测试与性能优化闭环。擅长在团队协作中主动推进事项、沉淀规范，并通过数据指标驱动持续改进，当前目标是成长为能够独立负责模块的Java初级软件开发工程师。',
  },
  missingFields: [],
}

const scoreData = ref<ScoreData | null>(null)
const aggregateData = ref<ProfileAggregateResult | null>(null)
const suggestions = ref<ImprovementSuggestion[]>([])
const evidenceMap = ref<Partial<Record<keyof AbilityScores, string[]>>>({})
const radarRef = ref<HTMLDivElement>()
const progressGaugeRef = ref<HTMLDivElement>()
let radarChart: echarts.ECharts | null = null
let progressGaugeChart: echarts.ECharts | null = null
let chartResizeTimer: ReturnType<typeof setTimeout> | null = null

const skillInput = ref('')
const jobIntentionInput = ref('')
const organizeExpInput = ref('')
const projectInput = ref('')

const profile = reactive<ProfileFormData>(createDefaultProfile())

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

const rules = {
  basicInfo: {
    name: [{ required: true, message: '请输入姓名', trigger: 'blur' }],
    phone: [
      { required: true, message: '请输入手机号', trigger: 'blur' },
      { pattern: /^1\d{10}$/, message: '手机号格式不正确', trigger: 'blur' },
    ],
    email: [{ type: 'email', message: '邮箱格式不正确', trigger: 'blur' }],
    jobIntention: [
      {
        trigger: 'change',
        validator: (_rule: unknown, value: string[], callback: (error?: Error) => void) => {
          if (Array.isArray(value) && value.some((item) => item.trim())) {
            callback()
            return
          }
          callback(new Error('请至少填写1个求职岗位'))
        },
      },
    ],
  },
  selfEvaluation: [
    { required: true, message: '请填写自我评价', trigger: 'blur' },
    { min: 20, max: 500, message: '建议20~500字', trigger: 'blur' },
  ],
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


const requiredCompletion = computed(() => {
  const checks = [
    Boolean(profile.basicInfo.name.trim()),
    /^1\d{10}$/.test(profile.basicInfo.phone.trim()),
    profile.basicInfo.jobIntention.some((item) => item.trim()),
    profile.education.some((item) => item.school.trim() || item.major.trim() || item.degree.trim()),
    profile.skills.length > 0,
    profile.selfEvaluation.trim().length >= 20,
  ]
  const done = checks.filter(Boolean).length
  return Math.round((done / checks.length) * 100)
})

const richnessCompletion = computed(() => {
  const checks = [
    Boolean(profile.basicInfo.gender.trim()),
    Boolean(profile.basicInfo.birthday.trim()),
    Boolean(profile.basicInfo.email.trim()),
    Boolean(profile.basicInfo.city.trim()),
    profile.workExperience.some((item) => item.company.trim() || item.role.trim() || item.description.trim()),
    profile.certificates.some((item) => item.name.trim() || item.issuer.trim()),
    profile.organizeExp.some((item) => item.trim()),
    profile.projects.some((item) => item.trim()),
  ]
  const done = checks.filter(Boolean).length
  return Math.round((done / checks.length) * 100)
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

const sectionCompletion = computed(() => {
  const educationFilled = profile.education.filter((item) => item.school || item.major || item.degree || item.startDate || item.endDate || item.gpa)
  const workFilled = profile.workExperience.filter((item) => item.company || item.role || item.startDate || item.endDate || item.description)
  const certificateFilled = profile.certificates.filter((item) => item.name || item.date || item.issuer)

  return {
    basicInfo: countFilled([
      profile.basicInfo.name,
      profile.basicInfo.gender,
      profile.basicInfo.birthday,
      profile.basicInfo.phone,
      profile.basicInfo.email,
      profile.basicInfo.city,
      profile.basicInfo.jobIntention.join('、'),
    ], 7),
    education: educationFilled.length ? listCompletion(educationFilled, ['school', 'major', 'degree', 'startDate', 'endDate']) : 0,
    workExperience: workFilled.length ? listCompletion(workFilled, ['company', 'role', 'startDate', 'endDate', 'description']) : 0,
    skills: profile.skills.length > 0 ? 100 : 0,
    certificates: certificateFilled.length ? listCompletion(certificateFilled, ['name', 'date', 'issuer']) : 0,
    organizeExp: profile.organizeExp.length > 0 ? 100 : 0,
    projects: profile.projects.length > 0 ? 100 : 0,
    selfEvaluation: profile.selfEvaluation.trim().length >= 20 ? 100 : 0,
  }
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

function countFilled(values: string[], total: number) {
  const filled = values.filter((item) => item.trim()).length
  return Math.round((filled / total) * 100)
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

function listCompletion<T extends Record<string, string>>(items: T[], fields: Array<keyof T>) {
  if (!items.length) return 0
  const values = items.map((item) => {
    const done = fields.filter((field) => String(item[field] ?? '').trim()).length
    return Math.round((done / fields.length) * 100)
  })
  return Math.round(values.reduce((sum, item) => sum + item, 0) / values.length)
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
  profile.basicInfo = { ...next.basicInfo }
  profile.education = next.education.map((item) => ({ ...item }))
  profile.workExperience = next.workExperience.map((item) => ({ ...item }))
  profile.skills = [...next.skills]
  profile.certificates = next.certificates.map((item) => ({ ...item }))
  profile.organizeExp = [...next.organizeExp]
  profile.projects = [...next.projects]
  profile.selfEvaluation = next.selfEvaluation
}

function mergePreferUserInput(target: ProfileFormData, parsed: Partial<ProfileFormData>) {
  const mergeResult = {
    overwritten: 0,
    unchanged: 0,
    filledPaths: [] as string[],
  }

  const mergeAndCount = (current: string, next: string | undefined, fieldPath: string) => {
    if (!next?.trim()) return current
    if (current.trim() === next.trim()) {
      mergeResult.unchanged += 1
      return current
    }
    mergeResult.overwritten += 1
    mergeResult.filledPaths.push(fieldPath)
    return next
  }

  if (parsed.basicInfo) {
    target.basicInfo.name = mergeAndCount(target.basicInfo.name, parsed.basicInfo.name, 'basicInfo.name')
    target.basicInfo.gender = mergeAndCount(target.basicInfo.gender, parsed.basicInfo.gender, 'basicInfo.gender')
    target.basicInfo.birthday = mergeAndCount(
      target.basicInfo.birthday,
      normalizeDayString(parsed.basicInfo.birthday),
      'basicInfo.birthday',
    )
    target.basicInfo.phone = mergeAndCount(target.basicInfo.phone, parsed.basicInfo.phone, 'basicInfo.phone')
    target.basicInfo.email = mergeAndCount(target.basicInfo.email, parsed.basicInfo.email, 'basicInfo.email')
    target.basicInfo.city = mergeAndCount(target.basicInfo.city, parsed.basicInfo.city, 'basicInfo.city')
    const parsedJobIntention = normalizeStringList((parsed.basicInfo as { jobIntention?: unknown }).jobIntention)
    if (parsedJobIntention.length) {
      const currentJobIntention = normalizeStringList(target.basicInfo.jobIntention)
      if (JSON.stringify(currentJobIntention) === JSON.stringify(parsedJobIntention)) {
        mergeResult.unchanged += 1
      } else {
        target.basicInfo.jobIntention = parsedJobIntention
        mergeResult.overwritten += 1
        mergeResult.filledPaths.push('basicInfo.jobIntention')
      }
    }
  }

  if (parsed.education && parsed.education.length) {
    target.education = normalizeProfile({ education: parsed.education }).education
    mergeResult.overwritten += parsed.education.length
    mergeResult.filledPaths.push('education')
  }

  if (parsed.workExperience && parsed.workExperience.length) {
    target.workExperience = normalizeProfile({ workExperience: parsed.workExperience }).workExperience
    mergeResult.overwritten += parsed.workExperience.length
    mergeResult.filledPaths.push('workExperience')
  }

  if (parsed.certificates && parsed.certificates.length) {
    target.certificates = normalizeProfile({ certificates: parsed.certificates as unknown as CertificateItem[] }).certificates
    mergeResult.overwritten += parsed.certificates.length
    mergeResult.filledPaths.push('certificates')
  }

  const parsedOrganizeExp = normalizeStringList((parsed as { organizeExp?: unknown }).organizeExp)
  if (parsedOrganizeExp.length) {
    target.organizeExp = parsedOrganizeExp
    mergeResult.overwritten += parsedOrganizeExp.length
    mergeResult.filledPaths.push('organizeExp')
  }

  const parsedProjects = normalizeStringList((parsed as { projects?: unknown }).projects)
  if (parsedProjects.length) {
    target.projects = parsedProjects
    mergeResult.overwritten += parsedProjects.length
    mergeResult.filledPaths.push('projects')
  }

  if (parsed.skills && parsed.skills.length) {
    target.skills = [...new Set(parsed.skills)]
    mergeResult.overwritten += parsed.skills.length
    mergeResult.filledPaths.push('skills')
  }

  target.selfEvaluation = mergeAndCount(target.selfEvaluation, parsed.selfEvaluation, 'selfEvaluation')

  return mergeResult
}

function markAutoFilled(paths: string[]) {
  autoFilledFields.value = [...new Set(paths)]
  if (highlightTimer) clearTimeout(highlightTimer)
  if (autoFilledFields.value.length) {
    highlightTimer = setTimeout(() => {
      autoFilledFields.value = []
    }, 10000)
  }
}

function applyParsedPayloadToForm(parsedPayload: DemoParsedPayload, options?: { silentSuccess?: boolean }) {
  if (!parsedPayload.parsedProfile) return
  if (!isEditing.value) {
    isEditing.value = true
  }
  viewMode.value = 'resume'

  const mergeResult = mergePreferUserInput(profile, parsedPayload.parsedProfile)
  markAutoFilled(mergeResult.filledPaths)
  missingFields.value = parsedPayload.missingFields ?? []
  if (!options?.silentSuccess) {
    ElMessage.success(`演示数据已填入：覆盖/填充${mergeResult.overwritten}项，未变化${mergeResult.unchanged}项`)
  }
}

function isHighlighted(path: string) {
  return autoFilledFields.value.includes(path)
}

function addSkill() {
  const value = skillInput.value.trim()
  if (!value) return
  if (profile.skills.includes(value)) {
    ElMessage.warning('技能已存在')
    return
  }
  profile.skills.push(value)
  skillInput.value = ''
}

function removeSkill(skill: string) {
  profile.skills = profile.skills.filter((item) => item !== skill)
}

function addJobIntention() {
  const value = jobIntentionInput.value.trim()
  if (!value) return
  if (profile.basicInfo.jobIntention.includes(value)) {
    ElMessage.warning('求职岗位已存在')
    return
  }
  profile.basicInfo.jobIntention.push(value)
  jobIntentionInput.value = ''
}

function removeJobIntention(jobIntention: string) {
  profile.basicInfo.jobIntention = profile.basicInfo.jobIntention.filter((item) => item !== jobIntention)
}

function addOrganizeExp() {
  const value = organizeExpInput.value.trim()
  if (!value) return
  if (profile.organizeExp.includes(value)) {
    ElMessage.warning('该社团/组织经历已存在')
    return
  }
  profile.organizeExp.push(value)
  organizeExpInput.value = ''
}

function removeOrganizeExp(item: string) {
  profile.organizeExp = profile.organizeExp.filter((value) => value !== item)
}

function addProjectExp() {
  const value = projectInput.value.trim()
  if (!value) return
  if (profile.projects.includes(value)) {
    ElMessage.warning('该项目经历已存在')
    return
  }
  profile.projects.push(value)
  projectInput.value = ''
}

function removeProjectExp(item: string) {
  profile.projects = profile.projects.filter((value) => value !== item)
}

function addEducation() {
  profile.education.push(createEducationItem())
}

function removeEducation(index: number) {
  if (profile.education.length === 1) {
    profile.education[0] = createEducationItem()
    return
  }
  profile.education.splice(index, 1)
}

function addWorkExperience() {
  profile.workExperience.push(createWorkItem())
}

function removeWorkExperience(index: number) {
  if (profile.workExperience.length === 1) {
    profile.workExperience[0] = createWorkItem()
    return
  }
  profile.workExperience.splice(index, 1)
}

function addCertificate() {
  profile.certificates.push(createCertificateItem())
}

function removeCertificate(index: number) {
  if (profile.certificates.length === 1) {
    profile.certificates[0] = createCertificateItem()
    return
  }
  profile.certificates.splice(index, 1)
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
  demoUploadDialogVisible.value = true
}

function applyDemoProfileData() {
  if (!isEditing.value) {
    isEditing.value = true
  }

  parseLoading.value = true
  try {
    applyParsedPayloadToForm(DEMO_PARSED_PAYLOAD)
    demoUploadDialogVisible.value = false
  } catch {
    ElNotification({
      title: '填充失败',
      message: '请稍后重试',
      type: 'error',
    })
  } finally {
    parseLoading.value = false
  }
}

async function loadProfile() {
  initLoading.value = true
  scorePanelLoading.value = true
  suggestionPanelLoading.value = true
  try {
    const payload = await appStore.ensureProfileSnapshot(true)
    hasServerProfile.value = Boolean(payload?.hasProfile)
    if (!payload?.hasProfile || !payload.profile) {
      isEditing.value = true
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

    replaceProfileData(normalizeProfile(payload.profile))
    scoreData.value = payload.scores
    evidenceMap.value = payload.evidence ?? {}
    suggestions.value = payload.improvementSuggestions ?? []
    updatedAt.value = payload.updatedAt ?? ''
    profileId.value = payload.profileId ?? ''
    isEditing.value = false
    savedSnapshot.value = JSON.stringify(normalizeProfile(profile))

    if (!payload.scores) {
      analysisPending.value = true
      viewMode.value = 'resume'
      void pollProfileResultWhilePending()
      return
    }

    analysisPending.value = false
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
        analysisPending.value = false
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

async function pollProfileResultWhilePending(maxRound = 6) {
  for (let index = 0; index < maxRound; index += 1) {
    if (!isPageActive || !analysisPending.value) return

    const refreshed = await refreshScoreResult(4)
    if (!isPageActive || !analysisPending.value) return

    if (refreshed) {
      if (isDesktop.value && !isEditing.value) {
        await fetchAggregateData(false)
        viewMode.value = 'insight'
        nextTick(() => {
          renderRadarChart()
          renderRingCharts()
          resizeInsightChartsDebounced()
        })
      }
      return
    }

    await new Promise((resolve) => setTimeout(resolve, 800))
  }
}

async function pollProfileAnalyzeJob(analyzeJobId: string, initialInterval = 1000, maxRetry = 50) {
  let intervalMs = Math.max(1000, Math.min(2000, initialInterval))

  for (let index = 0; index < maxRetry; index += 1) {
    const response = await getProfileAnalyzeJobStatus(analyzeJobId)
    const result = response.data as ApiResponse<ProfileAnalyzeJobStatusResult>
    if (!isSuccessCode(Number(result.code))) {
      throw new Error(result.msg || '分析任务状态查询失败')
    }

    const payload = extractPayload<ProfileAnalyzeJobStatusResult>(response as { data: ApiResponse<ProfileAnalyzeJobStatusResult> })
    if (!payload) {
      throw new Error('分析任务状态返回为空')
    }

    if (payload.status === 'succeeded') {
      return true
    }

    if (payload.status === 'failed') {
      throw new Error('分析失败')
    }

    intervalMs = payload.pollAfterMs ? Math.max(1000, Math.min(2000, payload.pollAfterMs)) : intervalMs
    await new Promise((resolve) => setTimeout(resolve, intervalMs))
  }

  throw new Error('分析超时，请稍后刷新查看')
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
  if (!formRef.value) return

  try {
    await formRef.value.validate()
  } catch {
    ElMessage.warning('请先完善必填项后再保存')
    return
  }

  if (!profile.skills.length) {
    ElMessage.warning('请至少填写1项技能特长')
    return
  }

  if (!profile.basicInfo.jobIntention.length) {
    ElMessage.warning('请至少填写1个求职岗位')
    return
  }

  if (!profile.education.some((item) => item.school || item.major || item.degree)) {
    ElMessage.warning('请至少填写1条教育背景')
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
    updatedAt.value = payload?.updatedAt ?? ''
    hasServerProfile.value = true
    isEditing.value = false
    savedSnapshot.value = JSON.stringify(normalizeProfile(profile))

    if (payload?.scores) {
      scoreData.value = payload.scores
      evidenceMap.value = payload?.evidence ?? {}
      suggestions.value = payload?.improvementSuggestions ?? []
    } else {
      if (payload?.analyzeJobId) {
        await pollProfileAnalyzeJob(payload.analyzeJobId)
      }

      const refreshed = await refreshScoreResult(6)
      if (!refreshed) {
        scoreData.value = null
        suggestions.value = []
        evidenceMap.value = {}
        ElMessage.warning('画像分析仍在处理中，请稍后刷新查看')
      }
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
    ElMessage.success(payload?.scores ? '保存成功，开始分析' : '保存成功，分析完成')
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
  missingFields.value = []
  autoFilledFields.value = []
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
        <span class="rounded-full bg-slate-100 px-3 py-1">必填完成度 {{ requiredCompletion }}%</span>
        <span class="rounded-full bg-slate-100 px-3 py-1">内容丰富度 {{ richnessCompletion }}%</span>
        <span v-if="formattedUpdatedAt" class="rounded-full bg-slate-100 px-3 py-1">最近保存：{{ formattedUpdatedAt }}</span>
      </div>
    </div>

    <div class="grid gap-4 xl:grid-cols-[minmax(0,7fr)_minmax(320px,3fr)]">
      <div class="left-slider overflow-hidden">
        <div class="left-slider-track" :style="sliderTrackStyle">
          <div class="left-slider-panel" :style="sliderPanelStyle">
            <el-card class="resume-card !overflow-visible" shadow="never">
              <div class="space-y-6 parse-loading-wrap" v-loading="parseLoading" element-loading-text="正在填入演示数据..." element-loading-background="rgba(255,255,255,0)">
                <header class="resume-header border-b border-[#9db2c0] pb-5">
                  <div class="flex flex-col gap-4 md:flex-row md:items-start md:justify-between">
                    <div>
                      <div class="flex flex-wrap items-center gap-2">
                        <h3 class="text-3xl font-semibold tracking-wide text-[#3d6c85]">个人简历</h3>
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
                      </div>
                      <p class="text-xs text-slate-500 hover:text-slate-700">演示模式：可一键填入完整示例简历</p>
                    </div>
                  </div>
                </header>

                <el-alert
                  v-if="missingFields.length && isEditing"
                  type="warning"
                  show-icon
                  :closable="false"
                  title="以下字段识别不完整，请手动补全"
                  :description="missingFields.map((field) => sectionMeta[field as keyof typeof sectionMeta] || field).join('、')"
                />

                <el-form ref="formRef" :model="profile" label-position="top" :disabled="!isEditing || parseLoading || saveLoading" class="space-y-5">
            <section id="section-basicInfo" class="resume-section" :class="{ 'auto-fill-highlight': isHighlighted('basicInfo') }">
              <div class="resume-title">基本信息 <span class="resume-rate">完成度 {{ sectionCompletion.basicInfo }}%</span></div>
              <div class="mt-4 grid gap-3 md:grid-cols-2">
                <el-form-item label="姓名" prop="basicInfo.name" :rules="rules.basicInfo.name" :class="{ 'auto-fill-highlight': isHighlighted('basicInfo.name') }"><el-input v-model="profile.basicInfo.name" placeholder="请输入姓名" /></el-form-item>
                <el-form-item label="性别" :class="{ 'auto-fill-highlight': isHighlighted('basicInfo.gender') }"><el-select v-model="profile.basicInfo.gender" placeholder="请选择"><el-option label="男" value="male" /><el-option label="女" value="female" /></el-select></el-form-item>
                <el-form-item label="出生日期" :class="{ 'auto-fill-highlight': isHighlighted('basicInfo.birthday') }"><el-date-picker v-model="profile.basicInfo.birthday" type="date" value-format="YYYY-MM-DD" placeholder="请选择日期" class="w-full" /></el-form-item>
                <el-form-item label="电话" prop="basicInfo.phone" :rules="rules.basicInfo.phone" :class="{ 'auto-fill-highlight': isHighlighted('basicInfo.phone') }"><el-input v-model="profile.basicInfo.phone" placeholder="11位手机号" /></el-form-item>
                <el-form-item label="邮箱" prop="basicInfo.email" :rules="rules.basicInfo.email" :class="{ 'auto-fill-highlight': isHighlighted('basicInfo.email') }"><el-input v-model="profile.basicInfo.email" placeholder="请输入邮箱" /></el-form-item>
                <el-form-item
                  label="求职岗位（可多选）"
                  prop="basicInfo.jobIntention"
                  :rules="rules.basicInfo.jobIntention"
                  :class="{ 'auto-fill-highlight': isHighlighted('basicInfo.jobIntention') }"
                >
                  <div class="w-full space-y-2">
                    <div class="flex flex-wrap gap-2">
                      <el-tag
                        v-for="item in profile.basicInfo.jobIntention"
                        :key="item"
                        :closable="isEditing"
                        @close="removeJobIntention(item)"
                      >
                        {{ item }}
                      </el-tag>
                      <span v-if="!profile.basicInfo.jobIntention.length" class="text-sm text-slate-400">暂未添加求职岗位</span>
                    </div>
                    <div class="flex flex-col gap-2 sm:flex-row">
                      <el-input
                        v-model="jobIntentionInput"
                        maxlength="40"
                        placeholder="如：前端开发工程师"
                        @keyup.enter="addJobIntention"
                      />
                      <el-button plain @click="addJobIntention">添加岗位</el-button>
                    </div>
                  </div>
                </el-form-item>
                <el-form-item label="现居城市" class="md:col-span-2" :class="{ 'auto-fill-highlight': isHighlighted('basicInfo.city') }"><el-input v-model="profile.basicInfo.city" placeholder="请输入城市" /></el-form-item>
              </div>
            </section>

            <section id="section-education" class="resume-section" :class="{ 'auto-fill-highlight': isHighlighted('education') }">
              <div class="resume-title">教育背景 <span class="resume-rate">完成度 {{ sectionCompletion.education }}%</span></div>
              <div class="mt-4 space-y-4">
                <div v-for="(item, index) in profile.education" :key="`education-${index}`" class="rounded-lg border border-slate-200 p-3">
                  <div class="mb-2 flex items-center justify-between">
                    <span class="text-sm font-medium text-slate-700">教育经历 {{ index + 1 }}</span>
                    <el-button text type="danger" @click="removeEducation(index)">删除</el-button>
                  </div>
                  <div class="grid gap-3 md:grid-cols-2">
                    <el-input v-model="item.school" placeholder="学校" />
                    <el-input v-model="item.major" placeholder="专业" />
                    <el-input v-model="item.degree" placeholder="学历（本科/硕士）" />
                    <el-input v-model="item.gpa" placeholder="成绩/GPA（可选）" />
                    <el-date-picker v-model="item.startDate" type="month" value-format="YYYY-MM" placeholder="开始时间" class="w-full" />
                    <el-date-picker v-model="item.endDate" type="month" value-format="YYYY-MM" placeholder="结束时间" class="w-full" />
                  </div>
                </div>
                <el-button plain @click="addEducation"><el-icon class="mr-1"><Plus /></el-icon>添加教育经历</el-button>
              </div>
            </section>

            <section id="section-workExperience" class="resume-section" :class="{ 'auto-fill-highlight': isHighlighted('workExperience') }">
              <div class="resume-title">工作经验 <span class="resume-rate">完成度 {{ sectionCompletion.workExperience }}%</span></div>
              <div class="mt-4 space-y-4">
                <div v-for="(item, index) in profile.workExperience" :key="`work-${index}`" class="rounded-lg border border-slate-200 p-3">
                  <div class="mb-2 flex items-center justify-between">
                    <span class="text-sm font-medium text-slate-700">经历 {{ index + 1 }}</span>
                    <el-button text type="danger" @click="removeWorkExperience(index)">删除</el-button>
                  </div>
                  <div class="grid gap-3 md:grid-cols-2">
                    <el-input v-model="item.company" placeholder="公司" />
                    <el-input v-model="item.role" placeholder="岗位/在项目中承担的职责" />
                    <el-date-picker v-model="item.startDate" type="month" value-format="YYYY-MM" placeholder="开始时间" class="w-full" />
                    <el-date-picker v-model="item.endDate" type="month" value-format="YYYY-MM" placeholder="结束时间" class="w-full" />
                    <el-input
                      v-model="item.description"
                      type="textarea"
                      :rows="3"
                      maxlength="300"
                      show-word-limit
                      placeholder="描述职责、项目、成果"
                      class="md:col-span-2"
                    />
                  </div>
                </div>
                <el-button plain @click="addWorkExperience"><el-icon class="mr-1"><Plus /></el-icon>添加工作/实习经历</el-button>
              </div>
            </section>

            <section id="section-skills" class="resume-section" :class="{ 'auto-fill-highlight': isHighlighted('skills') }">
              <div class="resume-title">技能特长 <span class="resume-rate">完成度 {{ sectionCompletion.skills }}%</span></div>
              <div class="mt-4 space-y-3">
                <div class="flex flex-wrap gap-2">
                  <el-tag v-for="skill in profile.skills" :key="skill" :closable="isEditing" @close="removeSkill(skill)">{{ skill }}</el-tag>
                  <span v-if="!profile.skills.length" class="text-sm text-slate-400">暂未添加技能</span>
                </div>
                <div class="flex flex-col gap-2 sm:flex-row">
                  <el-input v-model="skillInput" maxlength="30" placeholder="输入技能后回车或点击添加" @keyup.enter="addSkill" />
                  <el-button plain @click="addSkill">添加技能</el-button>
                </div>
              </div>
            </section>

            <section id="section-certificates" class="resume-section" :class="{ 'auto-fill-highlight': isHighlighted('certificates') }">
              <div class="resume-title">荣誉证书 <span class="resume-rate">完成度 {{ sectionCompletion.certificates }}%</span></div>
              <div class="mt-4 space-y-4">
                <div v-for="(item, index) in profile.certificates" :key="`certificate-${index}`" class="rounded-lg border border-slate-200 p-3">
                  <div class="mb-2 flex items-center justify-between">
                    <span class="text-sm font-medium text-slate-700">证书 {{ index + 1 }}</span>
                    <el-button text type="danger" @click="removeCertificate(index)">删除</el-button>
                  </div>
                  <div class="grid gap-3 md:grid-cols-3">
                    <el-input v-model="item.name" placeholder="证书名称" />
                    <el-date-picker v-model="item.date" type="month" value-format="YYYY-MM" placeholder="获得日期" class="w-full" />
                    <el-input v-model="item.issuer" placeholder="颁发机构" />
                  </div>
                </div>
                <el-button plain @click="addCertificate"><el-icon class="mr-1"><Plus /></el-icon>添加证书</el-button>
              </div>
            </section>

            <section id="section-organizeExp" class="resume-section" :class="{ 'auto-fill-highlight': isHighlighted('organizeExp') }">
              <div class="resume-title">社团/组织经历 <span class="resume-rate">完成度 {{ sectionCompletion.organizeExp }}%</span></div>
              <div class="mt-4 space-y-3">
                <div class="space-y-2">
                  <div
                    v-for="(item, index) in profile.organizeExp"
                    :key="`organize-${index}-${item}`"
                    class="rounded-lg border border-slate-200 bg-slate-50 px-3 py-2"
                  >
                    <div class="flex items-start justify-between gap-2">
                      <div class="space-y-1">
                        <p class="text-xs font-medium text-slate-500">组织经历 {{ index + 1 }}</p>
                        <p class="text-sm leading-6 whitespace-pre-wrap break-words text-slate-700">{{ item }}</p>
                      </div>
                      <el-button
                        v-if="isEditing"
                        text
                        type="danger"
                        class="shrink-0"
                        @click="removeOrganizeExp(item)"
                      >删除</el-button>
                    </div>
                  </div>
                  <span v-if="!profile.organizeExp.length" class="text-sm text-slate-400">暂未添加社团/组织经历</span>
                </div>
                <div class="flex flex-col gap-2 sm:flex-row sm:items-start">
                  <el-input
                    v-model="organizeExpInput"
                    type="textarea"
                    :rows="2"
                    maxlength="500"
                    show-word-limit
                    placeholder="输入社团/组织经历（支持多行），按 Ctrl+Enter 或点击添加"
                    @keydown.ctrl.enter="addOrganizeExp"
                  />
                  <el-button plain @click="addOrganizeExp">添加经历</el-button>
                </div>
              </div>
            </section>

            <section id="section-projects" class="resume-section" :class="{ 'auto-fill-highlight': isHighlighted('projects') }">
              <div class="resume-title">项目经历 <span class="resume-rate">完成度 {{ sectionCompletion.projects }}%</span></div>
              <div class="mt-4 space-y-3">
                <div class="space-y-2">
                  <div
                    v-for="(item, index) in profile.projects"
                    :key="`project-${index}-${item}`"
                    class="rounded-lg border border-slate-200 bg-slate-50 px-3 py-2"
                  >
                    <div class="flex items-start justify-between gap-2">
                      <div class="space-y-1">
                        <p class="text-xs font-medium text-slate-500">项目经历 {{ index + 1 }}</p>
                        <p class="text-sm leading-6 whitespace-pre-wrap break-words text-slate-700">{{ item }}</p>
                      </div>
                      <el-button
                        v-if="isEditing"
                        text
                        type="danger"
                        class="shrink-0"
                        @click="removeProjectExp(item)"
                      >删除</el-button>
                    </div>
                  </div>
                  <span v-if="!profile.projects.length" class="text-sm text-slate-400">暂未添加项目经历</span>
                </div>
                <div class="flex flex-col gap-2 sm:flex-row sm:items-start">
                  <el-input
                    v-model="projectInput"
                    type="textarea"
                    :rows="3"
                    maxlength="500"
                    show-word-limit
                    placeholder="输入项目经历（支持多行），按 Ctrl+Enter 或点击添加"
                    @keydown.ctrl.enter="addProjectExp"
                  />
                  <el-button plain @click="addProjectExp">添加项目</el-button>
                </div>
              </div>
            </section>

            <section id="section-selfEvaluation" class="resume-section" :class="{ 'auto-fill-highlight': isHighlighted('selfEvaluation') }">
              <div class="resume-title">自我评价 <span class="resume-rate">完成度 {{ sectionCompletion.selfEvaluation }}%</span></div>
              <div class="mt-4">
                <el-form-item prop="selfEvaluation" :rules="rules.selfEvaluation" class="mb-0">
                  <el-input
                    v-model="profile.selfEvaluation"
                    type="textarea"
                    :rows="5"
                    maxlength="500"
                    show-word-limit
                    placeholder="建议描述：优势能力、项目实践、协作沟通、职业目标"
                  />
                </el-form-item>
              </div>
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

                <el-dialog
                  v-model="demoUploadDialogVisible"
                  title="上传简历并解析"
                  width="520px"
                  append-to-body
                  :close-on-click-modal="false"
                  :lock-scroll="false"
                >
                  <p class="text-sm leading-7 text-slate-600">当前为云端展示流程，暂不提供大模型解析与评估服务。</p>
                  <template #footer>
                    <div class="flex justify-end gap-2">
                      <el-button @click="demoUploadDialogVisible = false">返回</el-button>
                      <el-button type="primary" :loading="parseLoading" @click="applyDemoProfileData">一键填入演示数据</el-button>
                    </div>
                  </template>
                </el-dialog>
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

          <div v-loading="scorePanelLoading || analysisPending" class="min-h-[180px]">
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

            <el-empty v-else class="student-empty" :image="studentEmptyImageUrl" :description="analysisPending ? '画像分析中，请稍候...' : '保存并分析后显示评分'" :image-size="80" />
          </div>
        </el-card>

        <el-card shadow="never">
          <template #header>
            <span class="font-medium">优先改进建议</span>
          </template>

          <div v-loading="suggestionPanelLoading || analysisPending" class="min-h-[120px]">
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
            <el-empty v-else class="student-empty" :image="studentEmptyImageUrl" :description="analysisPending ? '画像分析中，请稍候...' : '暂无建议，先完成分析'" :image-size="80" />
          </div>
        </el-card>

        <el-card v-if="!hasServerProfile" shadow="never">
          <p class="text-sm text-slate-600">你还没有保存过画像，建议先上传简历并完善字段，再点击“保存并分析”。</p>
        </el-card>
      </aside>
    </div>

    <div v-if="isEditing" class="fixed inset-x-0 bottom-0 z-30 border-t bg-white/95 p-3 backdrop-blur lg:hidden">
      <el-button class="w-full" type="primary" :loading="saveLoading" @click="handlePrimaryAction">保存并分析</el-button>
    </div>

    <el-backtop :right="24" :bottom="96" />

    <el-tour v-model="showTour" @close="handleTourClose">
      <el-tour-step title="开始填写简历" description="先点击编辑，进入可填写状态。" :target="getTourEditTarget" />
      <el-tour-step title="上传自动解析" description="支持pdf/jpg/jpeg/png/docx/doc，系统会自动回填可识别字段。" :target="getTourUploadTarget" />
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
