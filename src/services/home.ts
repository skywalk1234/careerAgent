import { http } from './http'

export interface ApiResponse<T> {
  code: number
  msg: string
  data?: T
}

export interface HomeOverviewResult {
  greeting: {
    title: string
    subtitle: string
  }
  assistantQuickPrompts: string[]
  personalSnapshot: {
    profileCompleteness: number
    competitivenessScore: number
    latestMatchScore: number
    savedPathCount: number
    reportCount: number
  }
  dataSources?: {
    publicDashboardApi?: string
  }
  latestWork: {
    profileUpdatedAt: string | null
    latestMatchRecordId: string | null
    latestPathId: string | null
    latestReportId: string | null
  }
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

export interface HomeSession {
  sessionId: string
  title: string
  lastMessagePreview: string
  pinned?: boolean
  favorited?: boolean
  updatedAt: string
}

export interface HomeAgentTraceStep {
  stepId: string
  type: 'thought' | 'tool' | 'task' | string
  title: string
  status: 'pending' | 'processing' | 'succeeded' | 'failed' | string
  detail?: string
  toolName?: string
  toolParams?: Record<string, unknown> | null
  inputSummary?: string
  outputSummary?: string
}

export interface HomeAgentTaskItem {
  taskId: string
  title: string
  status: 'pending' | 'in_progress' | 'completed' | 'failed' | string
}

export interface HomeAgentTrace {
  traceVersion?: string
  mode?: string
  status: 'processing' | 'succeeded' | 'failed' | string
  startedAt?: string
  finishedAt?: string | null
  activeStepId?: string | null
  steps: HomeAgentTraceStep[]
  tasks?: HomeAgentTaskItem[]
}

export interface HomeMessage {
  messageId: string
  role: 'assistant' | 'user'
  content: string
  status: 'processing' | 'succeeded' | 'failed'
  createdAt: string
  fileNames?: string[]
  agentTrace?: HomeAgentTrace | null
  actions?: Array<{
    type: 'navigate' | 'navigate_and_parse_resume' | 'one_click_polish' | 'refine_match_recommendations' | 'apply_recommended_job' | string
    label: string
    route: string
    intent?: string
    reportId?: string
    scope?: Record<string, unknown> | null
    polishPayload?: {
      description?: string
      tone?: string
      targetReader?: string
      focusSections?: Record<string, { instruction?: string }>
      modules?: Array<{
        sectionKey?: string
        title?: string
        reason?: string
        instruction?: string
        suggestedContent?: string
      }>
    } | null
    keyword?: string
    autoSelect?: boolean
    recommendedJobId?: string
  }>
  taskResultCard?: HomeTaskResultCard | null
}

export interface HomeTaskResultScoreItem {
  dimension: string
  score: number
}

export interface HomeTaskResultMatchCard {
  jobName: string
  jobFamily: string
  city: string
  overallScore: number
  skillScore: number
  intentScore: number
  growthScore: number
}

export interface HomeTaskResultGapItem {
  dimension: string
  currentScore: number
  targetScore: number
  gap: number
  direction?: 'ahead' | 'behind' | string
}

export interface HomeTaskResultPathStageCard {
  stage: string
  title: string
  detail: string
}

export interface HomeTaskResultCard {
  kind: 'resume_scores' | 'job_match' | 'path_plan' | 'generic'
  title: string
  summary: string
  scores?: HomeTaskResultScoreItem[]
  match?: HomeTaskResultMatchCard
  gapItems?: HomeTaskResultGapItem[]
  pathStages?: HomeTaskResultPathStageCard[]
}

export interface HomeAssistantRunMeta {
  mode?: 'chat' | 'task' | string
  taskId?: string
  status?: string
  artifacts?: Array<Record<string, unknown>>
}

export interface HomeAssistantMessageApprovalPayload {
  decision: 'approve' | 'reject'
  comment?: string
}

export interface HomeAssistantMessageApprovalResult {
  messageId: string
  approvalId: string
  status: 'approved' | 'rejected' | string
  decidedAt: string
}

export interface HomePageContext {
  routePath?: string
  pageTitle?: string
  contextPrompt?: string
  data?: Record<string, unknown>
}

export interface HomeAgentRuntimePreset {
  presetId: string
  title: string
  goal: string
  expectedOutcome: string
  suggestedPrompt: string
  estimatedSeconds: number
  tags: string[]
  workflowPreview: HomeAgentRuntimeTaskStep[]
}

export interface HomeAgentRuntimeTool {
  toolId: string
  name: string
  description: string
}

export interface HomeAgentRuntimeTaskSummary {
  taskId: string
  presetId: string
  title: string
  goal: string
  expectedOutcome: string
  attempt: number
  status: 'queued' | 'in_progress' | 'paused' | 'completed' | 'failed' | 'cancelled' | string
  progress: number
  currentStepTitle: string
  goalConclusion: HomeAgentRuntimeGoalConclusion
  controlActions: HomeAgentRuntimeTaskControlActionDescriptor[]
  createdAt: string
  updatedAt: string
  finishedAt: string | null
  estimatedSeconds: number
}

export interface HomeAgentRuntimeTaskStep {
  stepId: string
  type: string
  title: string
  detail: string
  toolName: string
  expectedResult: string
  status: 'pending' | 'processing' | 'succeeded' | 'failed' | string
}

export interface HomeAgentRuntimeGoalConclusion {
  status: 'pending' | 'achieved' | 'not_achieved' | 'rolled_back' | string
  reached: boolean
  summary: string
}

export interface HomeAgentRuntimeTaskToolExecution {
  runId: string
  stepId: string
  toolName: string
  inputSummary: string
  outputSummary: string
  status: 'pending' | 'processing' | 'succeeded' | 'failed' | 'cancelled' | 'rolled_back' | string
  executedAt: string | null
  updatedAt: string
}

export interface HomeAgentRuntimeTaskControlActionDescriptor {
  action: 'pause' | 'resume' | 'cancel' | 'retry' | 'rollback' | string
  label: string
  expectedOutcome: string
  enabled: boolean
}

export interface HomeAgentRuntimeTaskArtifact {
  artifactId: string
  artifactType: string
  title: string
  content: string
  createdAt: string
}

export interface HomeAgentRuntimeTaskDetail extends HomeAgentRuntimeTaskSummary {
  prompt: string
  goalInput: string
  pageContext: HomePageContext
  steps: HomeAgentRuntimeTaskStep[]
  planSteps: HomeAgentRuntimeTaskStep[]
  toolSteps: HomeAgentRuntimeTaskStep[]
  toolExecutions: HomeAgentRuntimeTaskToolExecution[]
  artifacts: HomeAgentRuntimeTaskArtifact[]
  stream: {
    protocol: 'sse'
    url: string
  }
}

export interface HomeAgentRuntimeOverviewResult {
  version: string
  updatedAt: string
  presets: HomeAgentRuntimePreset[]
  toolCatalog: HomeAgentRuntimeTool[]
  latestTasks: HomeAgentRuntimeTaskSummary[]
}

export type HomeAgentRuntimeTaskControlAction = 'pause' | 'resume' | 'cancel' | 'retry' | 'rollback'

export async function getHomeOverview() {
  return http.get<ApiResponse<HomeOverviewResult>>('/users/me/home/overview')
}

export async function getHomePublicOverview() {
  return http.get<ApiResponse<HomePublicOverviewResult>>('/analytics/home/overview-public')
}

export async function createHomeSession() {
  return http.post<ApiResponse<{
    sessionId: string
    createdAt: string
    welcomeMessage: HomeMessage | null
  }>>('/users/me/home/assistant/sessions', {
    scene: 'home',
    temperature: 0.3,
  })
}

export async function listHomeSessions() {
  return http.get<ApiResponse<{
    total: number
    list: HomeSession[]
  }>>('/users/me/home/assistant/sessions')
}

export async function deleteHomeSession(sessionId: string) {
  return http.delete<ApiResponse<{
    sessionId: string
    deleted: boolean
  }>>(`/users/me/home/assistant/sessions/${sessionId}`)
}

export async function updateHomeSession(sessionId: string, payload: {
  pinned?: boolean
  favorited?: boolean
  title?: string
}) {
  return http.patch<ApiResponse<{
    sessionId: string
    title: string
    pinned: boolean
    favorited: boolean
    updatedAt: string
  }>>(`/users/me/home/assistant/sessions/${sessionId}`, payload)
}

export async function getHomeSessionMessages(sessionId: string) {
  return http.get<ApiResponse<{
    sessionId: string
    total: number
    list: HomeMessage[]
  }>>(`/users/me/home/assistant/sessions/${sessionId}/messages`)
}

export async function createHomeSessionMessage(
  sessionId: string,
  content: string,
  options?: {
    hasResumeFile?: boolean
    fileNames?: string[]
    pageContext?: HomePageContext | null
    executionMode?: 'auto' | 'chat' | 'task'
    goal?: string
    taskPolicy?: {
      allowToolCall?: boolean
      maxSteps?: number
      timeoutMs?: number
    } | null
    toolContext?: {
      localFileToken?: string
      fileNames?: string[]
    } | null
  },
) {
  return http.post<ApiResponse<{
    sessionId: string
    userMessage: HomeMessage
    assistantMessage: HomeMessage
    stream: {
      protocol: 'sse'
      url: string
    }
  }>>(`/users/me/home/assistant/sessions/${sessionId}/messages`, {
    content,
    temperature: 0.4,
    traceId: `trace_home_${Date.now()}`,
    fileContext: options
      ? {
        hasResumeFile: Boolean(options.hasResumeFile),
        fileNames: Array.isArray(options.fileNames) ? options.fileNames : [],
      }
      : null,
    pageContext: options?.pageContext || null,
    executionMode: options?.executionMode || 'auto',
    goal: String(options?.goal || '').trim(),
    taskPolicy: options?.taskPolicy || null,
    toolContext: options?.toolContext || null,
  })
}

export async function regenerateHomeSessionMessage(
  sessionId: string,
  messageId: string,
  payload?: {
    content?: string
  },
) {
  return http.post<ApiResponse<{
    sessionId: string
    assistantMessage: HomeMessage
    prunedAfterCount?: number
    stream: {
      protocol: 'sse'
      url: string
    }
  }>>(`/users/me/home/assistant/sessions/${sessionId}/messages/${messageId}/regenerate`, payload || {})
}

export async function submitHomeMessageApproval(
  messageId: string,
  payload: HomeAssistantMessageApprovalPayload,
) {
  return http.post<ApiResponse<HomeAssistantMessageApprovalResult>>(
    `/users/me/home/assistant/message/${encodeURIComponent(messageId)}/approvals`,
    payload,
  )
}

export async function getHomeAgentRuntimeOverview() {
  return http.get<ApiResponse<HomeAgentRuntimeOverviewResult>>('/users/me/home/assistant/agent/overview')
}

export async function createHomeAgentTask(payload: {
  prompt?: string
  presetId?: string
  pageContext?: HomePageContext | null
}) {
  return http.post<ApiResponse<{
    task: HomeAgentRuntimeTaskDetail
    pollAfterMs?: number
  }>>('/users/me/home/assistant/agent/tasks', {
    prompt: payload.prompt || '',
    presetId: payload.presetId || '',
    pageContext: payload.pageContext || null,
  })
}

export async function controlHomeAgentTask(taskId: string, action: HomeAgentRuntimeTaskControlAction) {
  return http.post<ApiResponse<{
    task: HomeAgentRuntimeTaskDetail
  }>>(`/users/me/home/assistant/agent/tasks/${taskId}/control`, {
    action,
  })
}

export async function getHomeAgentTaskArtifacts(taskId: string) {
  return http.get<ApiResponse<{
    taskId: string
    status: string
    artifacts: HomeAgentRuntimeTaskArtifact[]
  }>>(`/users/me/home/assistant/agent/tasks/${taskId}/artifacts`)
}
