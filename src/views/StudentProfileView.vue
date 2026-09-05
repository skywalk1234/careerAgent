<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, reactive, ref } from 'vue'
import { onBeforeRouteLeave } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Check, EditPen, Plus, Refresh, Upload } from '@element-plus/icons-vue'
import MarkdownIt from 'markdown-it'
import DOMPurify from 'dompurify'
import { htmlToMarkdown } from '../utils/htmlToMarkdown'
import {
  createParseProfileJob,
  getStudentProfile,
  getStudentProfileList,
  parseImageResume,
  saveStudentProfile,
  type GetProfileResult,
  type ParseJobCreated,
  type ProfileFormData,
  type ResumeListItem,
  type SaveProfileResult,
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

const appStore = useAppStore()
const initLoading = ref(false)
const parseLoading = ref(false)
const saveLoading = ref(false)
const refreshLoading = ref(false)
const listLoading = ref(false)
const isEditing = ref(false)
const updatedAt = ref('')
const savedSnapshot = ref('')
const resumeList = ref<ResumeListItem[]>([])
const activeResumeId = ref('')
let isPageActive = true
const fileInputRef = ref<HTMLInputElement>()
// WYSIWYG 编辑（直接在渲染后的 markdown 上改）：resumeEditorRef 为 contenteditable，
// resumeEditorDirty 标记是否真正输入过，避免“进入编辑未改动”被误判为有未保存修改
const resumeEditorRef = ref<HTMLElement | null>(null)
const resumeEditorDirty = ref(false)

const profile = reactive<ProfileFormData>(createDefaultProfile())

// markdown 渲染（简历已改为 markdown 原文存储，html 关闭 + DOMPurify 清洗以防御 XSS）
const markdownRenderer = new MarkdownIt({ html: false, linkify: true, breaks: true })
markdownRenderer.enable(['table', 'strikethrough'])
markdownRenderer.renderer.rules.table_open = () => '<div class="md-table-scroll"><table>'
markdownRenderer.renderer.rules.table_close = () => '</table></div>'
const resumeHtml = computed(() => DOMPurify.sanitize(markdownRenderer.render(profile.content || '')))

const hasUnsavedChanges = computed(() => {
  // 编辑态：以编辑器是否真正输入过为准（未输入过时，HTML→markdown 归一化差异不算改动）
  if (isEditing.value) return resumeEditorDirty.value
  return JSON.stringify(normalizeProfile(profile)) !== savedSnapshot.value
})

const actionButtonText = computed(() => {
  if (isEditing.value) return '保存并分析'
  return '手动编辑'
})

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

function createEducationItem() {
  return {
    school: '',
    major: '',
    degree: '',
    startDate: '',
    endDate: '',
    gpa: '',
  }
}

function createWorkItem() {
  return {
    company: '',
    role: '',
    startDate: '',
    endDate: '',
    description: '',
  }
}

function createCertificateItem() {
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

function normalizeCertificateItem(input: unknown) {
  if (typeof input === 'string') {
    return {
      name: input,
      date: '',
      issuer: '',
    }
  }

  const raw = (input as Partial<{ name: string; date: string; issuer: string }>)
  return {
    name: '',
    date: normalizeMonthString(raw?.date),
    issuer: '',
    ...raw,
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
            const merged = { ...createWorkItem(), ...(item as Partial<ReturnType<typeof createWorkItem>>) }
            merged.startDate = normalizeMonthString(merged.startDate)
            merged.endDate = normalizeMonthString(merged.endDate)
            if (!merged.role && typeof (merged as { position?: string }).position === 'string') {
              merged.role = (merged as { position?: string }).position as string
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

// ---------- 多简历列表 ----------
function genResumeId(): string {
  if (typeof crypto !== 'undefined' && typeof crypto.randomUUID === 'function') {
    return crypto.randomUUID()
  }
  return `resume-${Date.now()}-${Math.random().toString(36).slice(2, 10)}`
}

// 列表标题 = 简历第一行文本（去掉前导 markdown 标记）
function firstLineTitle(content?: string): string {
  const line = String(content ?? '').split('\n').map((l) => l.trim()).find((l) => l.length > 0) ?? ''
  const text = line.replace(/^[#>*_\-\s]+/, '').trim()
  return text || '未命名简历'
}

function getResumeTitle(item: ResumeListItem): string {
  const fromContent = firstLineTitle(item.content)
  if (fromContent !== '未命名简历') return fromContent
  return String(item.title || '未命名简历').trim() || '未命名简历'
}

function formatListTime(value?: string | null): string {
  const text = String(value || '').trim()
  if (!text) return ''
  const date = new Date(text)
  if (Number.isNaN(date.getTime())) return text.replace('T', ' ').slice(0, 16)
  const pad = (n: number) => String(n).padStart(2, '0')
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())} ${pad(date.getHours())}:${pad(date.getMinutes())}`
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
      // 没有任何简历：创建一个空白项（含新生成的 profileId，保存时才落库）
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
  resumeEditorDirty.value = false
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
  enterEditing()
}

// 保存成功后，用当前 profile 的内容刷新对应列表项的标题/内容
function refreshResumeListTab() {
  const pid = profile.profileId || ''
  if (!pid) return
  const title = firstLineTitle(profile.content)
  const idx = resumeList.value.findIndex((it) => it.profileId === pid)
  if (idx >= 0) {
    resumeList.value[idx] = { ...resumeList.value[idx], title, content: profile.content ?? '' }
  } else {
    resumeList.value.push({ profileId: pid, title, content: profile.content ?? '' })
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
  try {
    // 简历内容来自列表（多简历）；同时同步一次后端画像快照到 store，供其它页面即时读取
    await loadResumeList(true)
    const payload = await appStore.ensureProfileSnapshot(true)
    updatedAt.value = payload?.updatedAt ?? ''
    isEditing.value = false
    resumeEditorDirty.value = false
    savedSnapshot.value = JSON.stringify(normalizeProfile(profile))
  } catch {
    ElMessage.error('获取学生画像失败')
  } finally {
    initLoading.value = false
  }
}

async function saveProfile() {
  if (saveLoading.value) return
  if (!profile.content?.trim()) {
    ElMessage.warning('请填写简历内容')
    return
  }

  saveLoading.value = true
  try {
    const response = await saveStudentProfile(normalizeProfile(profile))
    const result = response.data as ApiResponse<SaveProfileResult>
    if (!isSuccessCode(Number(result.code))) {
      ElMessage.error(result.msg || '保存失败')
      return
    }

    const payload = extractPayload<SaveProfileResult>(response as { data: ApiResponse<SaveProfileResult> })
    // 后端回显前端生成的简历id，更新到当前简历，并刷新对应列表项
    const savedProfileId = payload?.profileId ?? ''
    if (payload?.profileId) {
      profile.profileId = payload.profileId
    }
    updatedAt.value = payload?.updatedAt ?? ''
    isEditing.value = false
    resumeEditorDirty.value = false
    refreshResumeListTab()
    savedSnapshot.value = JSON.stringify(normalizeProfile(profile))

    // 同步应用级画像快照，供 /match 岗位推荐、任务编排器、AI 助手等页面即时读取
    appStore.setProfileSnapshot({
      hasProfile: true,
      profileId: savedProfileId || null,
      profile: normalizeProfile(profile),
      scores: payload?.scores ?? null,
      evidence: payload?.evidence ?? {},
      improvementSuggestions: payload?.improvementSuggestions ?? [],
      updatedAt: updatedAt.value || null,
    })
    ElMessage.success('保存成功')
    return true
  } catch {
    ElMessage.error('保存失败，请稍后重试')
    return false
  } finally {
    saveLoading.value = false
  }
}

// ---------- WYSIWYG 修改模式（直接在渲染后的 markdown 上编辑） ----------
/** 进入修改模式：把渲染后的简历 HTML 灌进 contenteditable，用户直接改成品效果 */
function enterEditing() {
  isEditing.value = true
  resumeEditorDirty.value = false
  nextTick(() => {
    const el = resumeEditorRef.value
    if (!el) return
    el.innerHTML = DOMPurify.sanitize(markdownRenderer.render(profile.content || ''))
    el.focus()
    // 光标放到末尾，方便直接续写
    const range = document.createRange()
    range.selectNodeContents(el)
    range.collapse(false)
    const selection = window.getSelection()
    selection?.removeAllRanges()
    selection?.addRange(range)
  })
}

/** 编辑区每次 input：标记已改动，并把当前 HTML 转回 markdown 同步到 profile.content（供变更判断/保存） */
function onEditorInput() {
  resumeEditorDirty.value = true
  const el = resumeEditorRef.value
  if (el) profile.content = htmlToMarkdown(el.innerHTML).trim()
}

/** 保存前：取编辑器最新 HTML，清洗后转回 markdown 覆盖 content（正文以 markdown 落库） */
function flushEditorContent() {
  const el = resumeEditorRef.value
  if (el) profile.content = htmlToMarkdown(DOMPurify.sanitize(el.innerHTML)).trim()
}

/** 工具栏命令：先聚焦编辑区再执行，避免点击工具栏把焦点/选区弄丢 */
function runResumeExecCommand(command: string, value?: string) {
  resumeEditorRef.value?.focus()
  document.execCommand(command, false, value || undefined)
}

async function handlePrimaryAction() {
  if (!isEditing.value) {
    enterEditing()
    return
  }

  // 没真正输入过：直接退出，不做 HTML→markdown 回写（避免归一化差异污染 content）
  if (!hasUnsavedChanges.value) {
    resumeEditorDirty.value = false
    isEditing.value = false
    ElMessage.info('未检测到内容变更，已取消编辑')
    return
  }

  // 编辑内容(HTML) → markdown，供保存使用
  flushEditorContent()

  if (!profile.content?.trim()) {
    ElMessage.warning('请填写简历内容')
    return
  }

  await saveProfile()
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
  resumeEditorDirty.value = false
  ElMessage.info('已取消编辑并恢复到上次保存内容')
}

async function handleTaskOrchestratorRouteRefreshEvent(event: Event) {
  const detail = (event as CustomEvent<TaskOrchestratorRouteRefreshPayload>).detail || {}
  if (String(detail.routePath || '').trim() !== '/student') return
  if (!isPageActive) return
  if (parseLoading.value || saveLoading.value) return
  if (isEditing.value && hasUnsavedChanges.value) return

  await loadProfile().catch(() => {})
}

// 手动刷新：重新请求所有简历（尽量保留当前选中的那份）并刷新画像快照
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
      // 没有任何简历：创建一个空白项（保存时才落库）
      const blank: ResumeListItem = { profileId: genResumeId(), title: '未命名简历', content: '' }
      resumeList.value = [blank]
      applyProfileContent(blank)
    } else {
      // 当前选中的简历还在则保留，否则回落到第一份
      const keep = list.find((it) => it.profileId && it.profileId === prevId) ?? list[0]
      applyProfileContent(keep)
    }

    // ② 重新拉取画像快照（force=true 跳过 store 缓存），刷新最近保存时间
    const snapshot = await appStore.ensureProfileSnapshot(true)
    updatedAt.value = snapshot?.updatedAt ?? ''
    isEditing.value = false
    resumeEditorDirty.value = false
    savedSnapshot.value = JSON.stringify(normalizeProfile(profile))
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
  window.addEventListener(TASK_ORCHESTRATOR_ROUTE_REFRESH_EVENT, handleTaskOrchestratorRouteRefreshEvent as EventListener)
  void loadProfile()
})

onBeforeUnmount(() => {
  isPageActive = false
  window.removeEventListener(TASK_ORCHESTRATOR_ROUTE_REFRESH_EVENT, handleTaskOrchestratorRouteRefreshEvent as EventListener)
})
</script>

<template>
  <section class="space-y-4 pb-24 lg:pb-0" v-loading="initLoading">
    <div class="flex flex-col gap-3 rounded-2xl border border-slate-200 bg-white p-4 shadow-sm md:flex-row md:items-center md:justify-between md:p-5">
      <div>
        <h2 class="text-lg font-semibold text-slate-900 md:text-2xl">个人就业能力分析</h2>
        <p class="mt-1 text-xs text-slate-500 md:text-sm">上传文件或自行录入简历，使用大模型拆解、分析，形成就业能力画像，供 AI 助手与职业规划流程使用。</p>
      </div>
      <div class="flex flex-wrap items-center gap-2 text-xs text-slate-500">
        <span v-if="formattedUpdatedAt" class="rounded-full bg-slate-100 px-3 py-1">最近保存：{{ formattedUpdatedAt }}</span>
        <el-button size="small" type="primary" plain :loading="refreshLoading" :icon="Refresh" @click="handleRefreshData">刷新</el-button>
      </div>
    </div>

    <div class="grid grid-cols-1 gap-4 xl:grid-cols-[minmax(0,1fr)_320px]">
      <!-- 左：选中的简历正文预览/编辑 -->
      <el-card class="resume-card min-w-0" shadow="never">
        <div v-loading="parseLoading" class="space-y-5" element-loading-text="正在上传并解析简历..." element-loading-background="rgba(255,255,255,0)">
          <header class="flex flex-col gap-4 border-b border-slate-200 pb-4 md:flex-row md:items-start md:justify-between">
            <div class="min-w-0">
              <div class="flex flex-wrap items-center gap-2">
                <h3 class="text-base font-semibold text-slate-900">我的简历</h3>
                <el-tag v-if="isEditing" size="small" type="warning" effect="light">编辑中</el-tag>
                <span class="text-xs text-[#6a8a9b]">Personal Resume · 细心从每一个细节开始</span>
              </div>
              <div class="mt-3 flex flex-wrap items-center gap-2">
                <el-button
                  size="small"
                  type="primary"
                  :icon="isEditing ? Check : EditPen"
                  :loading="saveLoading"
                  @click="handlePrimaryAction"
                >
                  {{ actionButtonText }}
                </el-button>
                <el-button v-if="isEditing" size="small" @click="cancelEditing">取消</el-button>
              </div>
            </div>
            <div class="flex flex-col items-start gap-2 md:items-end">
              <el-button type="primary" plain :icon="Upload" :loading="parseLoading" @click="handleUploadChange">上传简历并解析</el-button>
              <input ref="fileInputRef" type="file" class="hidden" accept=".pdf,.jpg,.jpeg,.png,.gif,.bmp,.webp,application/pdf,image/*" @change="handleFileSelected" />
              <p class="text-xs text-slate-500">支持 PDF / 图片简历（jpg、png、webp），解析后自动填充到简历中</p>
            </div>
          </header>

          <div v-if="isEditing">
            <div class="mb-2 flex flex-wrap items-center gap-1 rounded-lg border border-slate-200 bg-slate-50 p-1 select-none">
              <button type="button" class="resume-tool-btn font-semibold" @mousedown.prevent="runResumeExecCommand('bold')">加粗</button>
              <button type="button" class="resume-tool-btn italic" @mousedown.prevent="runResumeExecCommand('italic')">斜体</button>
              <button type="button" class="resume-tool-btn" @mousedown.prevent="runResumeExecCommand('strikeThrough')">删除线</button>
              <span class="mx-1 h-4 w-px bg-slate-200"></span>
              <button type="button" class="resume-tool-btn" @mousedown.prevent="runResumeExecCommand('formatBlock', 'h2')">标题2</button>
              <button type="button" class="resume-tool-btn" @mousedown.prevent="runResumeExecCommand('formatBlock', 'h3')">标题3</button>
              <button type="button" class="resume-tool-btn" @mousedown.prevent="runResumeExecCommand('formatBlock', 'p')">正文</button>
              <span class="mx-1 h-4 w-px bg-slate-200"></span>
              <button type="button" class="resume-tool-btn" @mousedown.prevent="runResumeExecCommand('insertUnorderedList')">无序列表</button>
              <button type="button" class="resume-tool-btn" @mousedown.prevent="runResumeExecCommand('insertOrderedList')">有序列表</button>
              <button type="button" class="resume-tool-btn" @mousedown.prevent="runResumeExecCommand('formatBlock', 'blockquote')">引用</button>
              <span class="mx-1 h-4 w-px bg-slate-200"></span>
              <button type="button" class="resume-tool-btn" @mousedown.prevent="runResumeExecCommand('undo')">撤销</button>
              <button type="button" class="resume-tool-btn" @mousedown.prevent="runResumeExecCommand('redo')">重做</button>
            </div>

            <div
              ref="resumeEditorRef"
              class="resume-editor-content resume-markdown"
              contenteditable="true"
              spellcheck="false"
              data-placeholder="点此开始输入简历正文…"
              @input="onEditorInput"
            ></div>
            <p class="mt-2 text-xs text-slate-400">直接在简历渲染效果上修改；保存后按 Markdown 排版存入并重新分析，请尽量保留标题层级与列表结构。</p>
          </div>
          <div v-else-if="profile.content" class="resume-markdown" v-html="resumeHtml"></div>
          <el-empty v-else description="还没有简历，点「手动编辑」直接填写，或上传简历自动解析" :image-size="80" />
        </div>
      </el-card>

      <!-- 右：简历列表 -->
      <div class="min-w-0 xl:sticky xl:top-[72px] xl:self-start">
        <el-card class="resume-list-card" shadow="never" v-loading="listLoading">
          <template #header>
            <div class="flex items-center justify-between">
              <span class="font-medium">简历列表</span>
              <span class="text-xs text-slate-500">共 {{ resumeList.length }} 份</span>
            </div>
          </template>

          <div v-if="resumeList.length" class="resume-list-scroll space-y-2">
            <button
              v-for="(item, index) in resumeList"
              :key="item.profileId || `new-${index}`"
              type="button"
              class="w-full rounded-lg border px-3 py-2 text-left transition hover:border-blue-300 hover:bg-blue-50"
              :class="item.profileId === activeResumeId ? 'border-blue-400 bg-blue-50' : 'border-slate-200 bg-white'"
              @click="selectResume(item)"
            >
              <div class="flex items-center justify-between gap-2">
                <p class="line-clamp-1 text-sm font-medium text-slate-800">{{ getResumeTitle(item) }}</p>
                <el-tag v-if="item.profileId === activeResumeId" size="small" type="success" effect="light">当前</el-tag>
              </div>
              <p v-if="item.updatedAt" class="mt-1 text-xs text-slate-500">{{ formatListTime(item.updatedAt) }} 更新</p>
            </button>
          </div>
          <div v-else class="rounded-lg border border-dashed border-slate-200 p-4 text-center text-sm text-slate-500">
            暂无简历
          </div>

          <el-button class="mt-3 w-full" type="primary" plain :icon="Plus" @click="createNewResume">新建简历</el-button>
        </el-card>
      </div>
    </div>

    <div v-if="isEditing" class="fixed inset-x-0 bottom-0 z-30 flex gap-2 border-t bg-white/95 p-3 backdrop-blur lg:hidden">
      <el-button class="flex-1" @click="cancelEditing">取消</el-button>
      <el-button class="flex-1" type="primary" :loading="saveLoading" @click="handlePrimaryAction">保存并分析</el-button>
    </div>

    <el-backtop :right="24" :bottom="96" />
  </section>
</template>

<style scoped>
.resume-card {
  border: 1px solid #c5d3db;
  background: linear-gradient(180deg, #fcfdff 0%, #f9fbfc 100%);
}

.resume-markdown {
  line-height: 1.7;
  color: #1f2937;
  font-size: 14px;
  word-break: break-word;
}

.resume-markdown :deep(p) {
  margin: 0;
}

.resume-markdown :deep(h1),
.resume-markdown :deep(h2),
.resume-markdown :deep(h3),
.resume-markdown :deep(h4) {
  margin: 0;
  font-weight: 700;
  color: #0f172a;
  line-height: 1.35;
}

.resume-markdown :deep(h1) {
  font-size: 24px;
}

.resume-markdown :deep(h2) {
  font-size: 19px;
  margin-top: 20px;
  padding-bottom: 8px;
  border-bottom: 1px solid #eef2f7;
}

.resume-markdown :deep(h3) {
  font-size: 16px;
  margin-top: 16px;
}

.resume-markdown :deep(h4) {
  font-size: 15px;
  margin-top: 14px;
}

.resume-markdown :deep(h1 + *),
.resume-markdown :deep(h2 + *),
.resume-markdown :deep(h3 + *),
.resume-markdown :deep(h4 + *) {
  margin-top: 8px;
}

.resume-markdown :deep(p + p) {
  margin-top: 8px;
}

.resume-markdown :deep(h1:first-child) {
  margin-top: 0;
}

.resume-markdown :deep(strong) {
  color: #0f172a;
  font-weight: 700;
}

.resume-markdown :deep(code) {
  padding: 1px 6px;
  border-radius: 6px;
  background: #f1f5f9;
  color: #0f172a;
  font-size: 12px;
}

.resume-markdown :deep(pre) {
  margin-top: 8px;
  padding: 10px;
  border-radius: 10px;
  background: #f8fafc;
  border: 1px solid #e2e8f0;
  overflow-x: auto;
}

.resume-markdown :deep(pre code) {
  background: transparent;
  padding: 0;
}

.resume-markdown :deep(ul),
.resume-markdown :deep(ol) {
  margin: 8px 0 0;
  padding-left: 20px;
}

.resume-markdown :deep(ul) {
  list-style: disc;
}

.resume-markdown :deep(ol) {
  list-style: decimal;
}

.resume-markdown :deep(li) {
  margin: 3px 0;
}

.resume-markdown :deep(.md-table-scroll) {
  margin-top: 8px;
  overflow-x: auto;
  overflow-y: hidden;
  max-width: 100%;
  -webkit-overflow-scrolling: touch;
}

.resume-markdown :deep(table) {
  width: max-content;
  min-width: 100%;
  border-collapse: collapse;
  margin-top: 0;
  border: 1px solid #dbe5f0;
  border-radius: 8px;
  overflow: hidden;
  font-size: 13px;
}

.resume-markdown :deep(th),
.resume-markdown :deep(td) {
  border: 1px solid #dbe5f0;
  padding: 6px 8px;
  text-align: left;
  vertical-align: top;
}

.resume-markdown :deep(th) {
  background: #f8fafc;
  color: #0f172a;
  font-weight: 600;
}

.resume-markdown :deep(blockquote) {
  margin: 8px 0 0;
  padding: 8px 10px;
  border-left: 3px solid #93c5fd;
  background: #f8fbff;
  color: #334155;
}

.resume-markdown :deep(hr) {
  margin: 14px 0;
  border: none;
  border-top: 1px solid #e2e8f0;
}

.resume-markdown :deep(a) {
  color: #2563eb;
  text-decoration: underline;
}

.resume-list-scroll {
  max-height: calc(100vh - 220px);
  overflow-y: auto;
  scrollbar-width: none;
  -ms-overflow-style: none;
}

.resume-list-scroll::-webkit-scrollbar {
  display: none;
}

/* ---- WYSIWYG 编辑区（复用 .resume-markdown 的排版，以下为其补充样式） ---- */
.resume-editor-content {
  min-height: 320px;
  padding: 8px 12px;
  border: 1px dashed #93c5fd;
  border-radius: 10px;
  background: #fff;
  outline: none;
  transition: border-color 160ms ease, box-shadow 160ms ease;
}

.resume-editor-content:focus {
  border-color: #3b82f6;
  border-style: solid;
  box-shadow: 0 0 0 3px rgba(59, 130, 246, 0.12);
}

.resume-editor-content:empty::before {
  content: attr(data-placeholder);
  color: #94a3b8;
  pointer-events: none;
}

/* 编辑态段落给一点呼吸感（预览态 .resume-markdown 的 p 是 margin:0，这里覆盖。
   注意必须写在 .resume-markdown :deep(p) 规则之后才能覆盖到） */
.resume-editor-content :deep(p) {
  margin: 0.25em 0;
}

.resume-tool-btn {
  border-radius: 6px;
  padding: 2px 8px;
  font-size: 12px;
  line-height: 1.6;
  color: #334155;
  background: transparent;
  transition: background-color 120ms ease;
}

.resume-tool-btn:hover {
  background-color: #e2e8f0;
}

.resume-tool-btn:active {
  background-color: #cbd5e1;
}
</style>
