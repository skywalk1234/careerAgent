export interface GlobalAssistantContextPayload {
  routePath?: string
  pageTitle?: string
  contextPrompt?: string
  data?: Record<string, unknown>
  autoSendPrompt?: boolean
  initialMessage?: string
  autoSendInitialMessage?: boolean
}

export const GLOBAL_ASSISTANT_OPEN_EVENT = 'global-assistant-open'
export const TASK_ORCHESTRATOR_OPEN_EVENT = 'task-orchestrator-open'
export const TASK_ORCHESTRATOR_STREAM_EVENT = 'task-orchestrator-stream'
export const TASK_ORCHESTRATOR_ROUTE_REFRESH_EVENT = 'task-orchestrator-route-refresh'
export const HOME_ASSISTANT_SESSION_REFRESH_EVENT = 'home-assistant-session-refresh'
export const MATCH_RECOMMENDATION_REFRESH_EVENT = 'match-recommendation-refresh'
export const CAREER_REPORT_REFRESH_EVENT = 'career-report-refresh'
export const JOB_RECOMMENDATION_APPLY_EVENT = 'job-recommendation-apply'

const JOB_RECOMMENDATION_PENDING_STORAGE_KEY = 'job-recommendation-apply-pending'

export interface MatchRecommendationRefreshPayload {
  status: 'started' | 'processing' | 'succeeded' | 'failed'
  message?: string
}

export interface CareerReportRefreshPayload {
  reportId?: string
  reason?: string
}

export interface JobRecommendationApplyPayload {
  keyword?: string
  autoSelect?: boolean
  recommendedJobId?: string
  source?: string
}

export interface TaskOrchestratorOpenPayload {
  routePath?: string
  pageTitle?: string
  initialGoal?: string
  autoLaunch?: boolean
  source?: 'home' | 'global-assistant' | 'unknown'
  anchorX?: number
  anchorY?: number
}

export interface TaskOrchestratorActionPayload {
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
}

export interface TaskOrchestratorResultCardPayload {
  kind: 'resume_scores' | 'job_match' | 'path_plan' | 'generic'
  title: string
  summary: string
  scores?: Array<{ dimension: string; score: number }>
  match?: {
    jobName: string
    jobFamily: string
    city: string
    overallScore: number
    skillScore: number
    intentScore: number
    growthScore: number
  }
  pathStages?: Array<{ stage: string; title: string; detail: string }>
}

export interface TaskOrchestratorStreamPayload {
  taskId: string
  sessionId?: string
  phase: 'start' | 'update' | 'done'
  userMessageId?: string
  userText?: string
  assistantMessageId: string
  assistantText: string
  assistantStatus: 'processing' | 'succeeded' | 'failed'
  actions?: TaskOrchestratorActionPayload[]
  taskResultCard?: TaskOrchestratorResultCardPayload | null
  agentTrace?: Record<string, unknown> | null
}

export interface TaskOrchestratorRouteRefreshPayload {
  routePath?: '/student' | '/match' | string
  intent?: 'resume_analysis' | 'job_match' | 'path_plan' | 'generic' | string
}

export interface HomeAssistantSessionRefreshPayload {
  sessionId?: string
  reason?: 'task-created' | 'task-updated' | 'task-finished' | string
}

export function openGlobalAssistant(payload?: GlobalAssistantContextPayload) {
  window.dispatchEvent(
    new CustomEvent<GlobalAssistantContextPayload>(GLOBAL_ASSISTANT_OPEN_EVENT, {
      detail: payload || {},
    }),
  )
}

export function openTaskOrchestrator(payload?: TaskOrchestratorOpenPayload) {
  window.dispatchEvent(
    new CustomEvent<TaskOrchestratorOpenPayload>(TASK_ORCHESTRATOR_OPEN_EVENT, {
      detail: payload || {},
    }),
  )
}

export function emitTaskOrchestratorStream(payload: TaskOrchestratorStreamPayload) {
  window.dispatchEvent(
    new CustomEvent<TaskOrchestratorStreamPayload>(TASK_ORCHESTRATOR_STREAM_EVENT, {
      detail: payload,
    }),
  )
}

export function emitTaskOrchestratorRouteRefresh(payload: TaskOrchestratorRouteRefreshPayload) {
  window.dispatchEvent(
    new CustomEvent<TaskOrchestratorRouteRefreshPayload>(TASK_ORCHESTRATOR_ROUTE_REFRESH_EVENT, {
      detail: payload || {},
    }),
  )
}

export function emitHomeAssistantSessionRefresh(payload: HomeAssistantSessionRefreshPayload = {}) {
  window.dispatchEvent(
    new CustomEvent<HomeAssistantSessionRefreshPayload>(HOME_ASSISTANT_SESSION_REFRESH_EVENT, {
      detail: payload,
    }),
  )
}

export function emitMatchRecommendationRefresh(payload: MatchRecommendationRefreshPayload) {
  window.dispatchEvent(
    new CustomEvent<MatchRecommendationRefreshPayload>(MATCH_RECOMMENDATION_REFRESH_EVENT, {
      detail: payload,
    }),
  )
}

export function emitCareerReportRefresh(payload: CareerReportRefreshPayload = {}) {
  window.dispatchEvent(
    new CustomEvent<CareerReportRefreshPayload>(CAREER_REPORT_REFRESH_EVENT, {
      detail: payload,
    }),
  )
}

export function emitJobRecommendationApply(payload: JobRecommendationApplyPayload = {}) {
  window.dispatchEvent(
    new CustomEvent<JobRecommendationApplyPayload>(JOB_RECOMMENDATION_APPLY_EVENT, {
      detail: payload,
    }),
  )
}

export function stashPendingJobRecommendationApply(payload: JobRecommendationApplyPayload = {}) {
  try {
    sessionStorage.setItem(JOB_RECOMMENDATION_PENDING_STORAGE_KEY, JSON.stringify(payload || {}))
  } catch {
    // Ignore storage errors (e.g., private mode) and rely on live events.
  }
}

export function consumePendingJobRecommendationApply() {
  try {
    const raw = sessionStorage.getItem(JOB_RECOMMENDATION_PENDING_STORAGE_KEY)
    if (!raw) return null
    sessionStorage.removeItem(JOB_RECOMMENDATION_PENDING_STORAGE_KEY)
    const parsed = JSON.parse(raw) as JobRecommendationApplyPayload
    if (!parsed || typeof parsed !== 'object') return null
    return parsed
  } catch {
    return null
  }
}
