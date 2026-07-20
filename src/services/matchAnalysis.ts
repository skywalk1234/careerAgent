import { http } from './http'
import type { CompetencyKey } from '../types/domain'

export type MatchSource = 'auto' | 'favorite' | 'manual' | 'history'

export interface MatchDimensionScores {
  basicRequirement: number
  professionalSkill: number
  professionalLiteracy: number
  developmentPotential: number
}

export interface MatchSuggestion {
  dimension: CompetencyKey
  priority: 'high' | 'medium' | 'low' | string
  advice: string
}

export interface MatchAnalysisPayload {
  overallScore: number
  professionalSkillMatchRate?: number
  dimensionScores: MatchDimensionScores
  studentAbilityScores: Record<CompetencyKey, number>
  jobAbilityScores: Record<CompetencyKey, number>
  dimensionAnalysis?: Partial<
    Record<
      keyof MatchDimensionScores,
      {
        label: string
        score: number
        expectedScore: number
        reason: string
        confidence?: 'low' | 'medium' | 'high' | string
      }
    >
  >
  jobExpectedScores?: Partial<Record<keyof MatchDimensionScores, number>>
  matchTags: string[]
  improvementSuggestions: MatchSuggestion[]
}

export interface MatchRecordDetail {
  recordId: string
  source: MatchSource | string
  sourceMeta?: Record<string, unknown>
  job: {
    jobId: string
    jobName: string
    companyName: string
    city: string
    industryTags: string[]
    educationRequirement?: string
    salaryNegotiable?: boolean
    salaryNormalized?: string
    updatedAtRaw?: string
    updatedAtNormalized?: string | null
    level: string
  }
  match: MatchAnalysisPayload
  analysis?: MatchAnalysisPayload
  createdAt: string
  updatedAt: string
  pinned: boolean
}

export interface MatchHistorySummary {
  recordId: string
  source: MatchSource | string
  pinned: boolean
  jobId: string
  jobName: string
  companyName: string
  city?: string
  educationRequirement?: string
  salaryNegotiable?: boolean
  salaryNormalized?: string
  updatedAtRaw?: string
  updatedAtNormalized?: string | null
  overallScore: number
  matchTags: string[]
  createdAt: string
  updatedAt: string
}

export interface MatchRecommendationsResult {
  recommendationStatus?: 'none' | 'processing' | 'succeeded' | string
  pollAfterMs?: number
  reason?: string
  action?: {
    text: string
    route: string
  }
  recommendedAt?: string
  bestMatch: {
    jobId: string
    jobName: string
    companyName: string
    city: string
    district?: string
    industryTags?: string[]
    educationRequirement?: string
    salaryNegotiable?: boolean
    salaryNormalized?: string
    updatedAtRaw?: string
    updatedAtNormalized?: string | null
    level: string
    overallScore: number
    professionalSkillMatchRate?: number
    matchTags: string[]
    dimensionScores?: MatchDimensionScores
  } | null
  otherRecommendations: Array<{
    jobId: string
    jobName: string
    companyName: string
    city: string
    district?: string
    industryTags?: string[]
    educationRequirement?: string
    salaryNegotiable?: boolean
    salaryNormalized?: string
    updatedAtRaw?: string
    updatedAtNormalized?: string | null
    level: string
    overallScore: number
    professionalSkillMatchRate?: number
    matchTags: string[]
  }>
}

export interface RefineRecommendationScope {
  preferredJobIds?: string[]
  preferredJobKeywords?: string[]
  cityIntents?: string[]
  benefits?: string[]
  salaryRange?: {
    min: number
    max: number
  } | null
  includeSimilarJobs?: boolean
}

export interface RefineRecommendationCreateResult {
  recommendationStatus: 'processing' | 'succeeded' | 'failed' | string
  pollAfterMs?: number
  topN?: number
  scope?: RefineRecommendationScope | null
}

export interface PreRecommendationIntentSurveyPayload {
  preferredJobKeywords?: string[]
  cityIntents?: string[]
  salaryExpectation?: string
  benefits?: string[]
  workStyle?: string
  note?: string
}

export interface PreRecommendationIntentState {
  status: 'none' | 'submitted' | 'skipped' | string
  source?: string
  updatedAt?: string | null
  survey?: PreRecommendationIntentSurveyPayload | null
  scope?: RefineRecommendationScope | null
  recommendationStatus?: 'pending_profile' | 'processing' | 'succeeded' | string
  pollAfterMs?: number
  effectiveTopN?: number
}

export interface MatchHistoryResult {
  total: number
  pinnedRecordId: string | null
  list: MatchHistorySummary[]
}

export interface PathNode {
  id: string
  jobId: string
  jobName?: string
  level?: string
  stage: 'start' | 'milestone' | 'target' | string
}

export interface PathEdge {
  source: string
  target: string
  relationType: 'promotion' | 'transition' | string
  similarity: number
  difficulty: 'low' | 'medium' | 'high' | string
  reason: string
  inferred?: boolean
}

export interface PathMetric {
  key: string
  label: string
  value: number
}

export interface PathEvaluation {
  level: 'light' | 'deep' | string
  feasibilityScore: number
  readinessScore: number
  recommendationScore: number
  riskAlerts: string[]
  aiCommentary: string
  stagePlans?: Array<{
    stage: 'transitionPhase' | 'promotionPhase' | string
    stageLabel: string
    cycle: string
    goals: string[]
    suggestedTasks: Array<{
      title: string
      linkText?: string
      linkUrl?: string
    }>
    metrics?: string[]
  }>
  summaryMetrics: PathMetric[]
  metrics?: PathMetric[]
}

export interface PathDraftResult {
  draftId: string
  mode: 'conservative' | 'balanced' | 'aggressive' | 'manual' | string
  generatedAt: string
  pathNodes: PathNode[]
  pathEdges: PathEdge[]
  evaluation: PathEvaluation
}

export interface SavedPathResult {
  pathId: string
  pathName: string
  pathNodes: PathNode[]
  pathEdges: PathEdge[]
  evaluation: PathEvaluation
  createdAt: string
  updatedAt: string
}

export interface SavedPathSummary {
  pathId: string
  pathName: string
  pathNodeCount: number
  targetJobName: string
  feasibilityScore: number
  updatedAt: string
}

export interface DraftPathSummary {
  draftId: string
}

export interface LatestSavedPathSummary {
  pathId: string
  pathName: string
}

export interface LatestPathResult {
  hasPath: boolean
  latestPath: LatestSavedPathSummary | null
  draft: DraftPathSummary | null
  updatedAt: string | null
}

export interface SavedPathListResult {
  total: number
  list: SavedPathSummary[]
}

export interface CareerPathDetailResult {
  source: 'saved' | 'draft' | string
  savedPath?: SavedPathResult
  draft?: PathDraftResult
}

export interface AutoPlanJobCreateResult {
  autoPlanJobId: string
  status: 'processing'
  pollAfterMs?: number
}

export interface AutoPlanJobStatusResult {
  autoPlanJobId: string
  status: 'processing' | 'succeeded' | 'failed'
  progress?: number
  pollAfterMs?: number
  result?: {
    autoSaved: boolean
    savedPath: SavedPathResult
    draft: PathDraftResult
  }
}

export interface SavePathJobCreateResult {
  saveJobId: string
  status: 'processing'
  pollAfterMs?: number
}

export interface SavePathJobStatusResult {
  saveJobId: string
  status: 'processing' | 'succeeded' | 'failed'
  progress?: number
  pollAfterMs?: number
  result?: {
    savedPath: SavedPathResult
  }
}

export function getMatchRecommendations(topN = 5) {
  return http.get('/users/me/match/recommendations', { params: { topN } })
}

export function refineMatchRecommendations(payload: {
  topN?: number
  scope?: RefineRecommendationScope | null
}) {
  return http.post('/users/me/match/recommendations/refine', payload)
}

export function getPreRecommendationIntentProfile() {
  return http.post('/users/me/match/recommendations/pre-intent', {
    action: 'status',
  })
}

export function savePreRecommendationIntentProfile(payload: {
  topN?: number
  survey?: PreRecommendationIntentSurveyPayload | null
}) {
  return http.post('/users/me/match/recommendations/pre-intent', payload)
}

export function analyzeMatch(payload: {
  jobId: string
  source?: MatchSource | string
  saveToHistory?: boolean
  overwriteSameJob?: boolean
  sourceMeta?: Record<string, unknown>
}) {
  return http.post('/users/me/match/analyze', payload)
}

export function getMatchHistory() {
  return http.get('/users/me/match/history')
}

export function getMatchHistoryDetail(recordId: string) {
  return http.get(`/users/me/match/history/${recordId}`)
}

export function pinMatchRecord(recordId: string) {
  return http.post(`/users/me/match/history/${recordId}/pin`)
}

export function unpinMatchRecord(recordId: string) {
  return http.post(`/users/me/match/history/${recordId}/unpin`)
}

export function deleteMatchHistoryRecord(recordId: string) {
  return http.delete(`/users/me/match/history/${recordId}`)
}

export function autoPlanCareerPath(payload: {
  targetJobId: string
  mode?: 'conservative' | 'balanced' | 'aggressive' | string
}) {
  return http.post('/users/me/career-path/auto-plan', payload)
}

export function getAutoPlanCareerPathJobStatus(autoPlanJobId: string) {
  return http.get(`/users/me/career-path/auto-plan-jobs/${encodeURIComponent(autoPlanJobId)}`)
}

export function evaluateCareerPathRealtime(payload: {
  pathNodes: PathNode[]
}) {
  return http.post('/users/me/career-path/realtime-evaluate', payload)
}

export function saveCareerPath(payload: {
  pathName: string
  pathNodes: PathNode[]
}) {
  return http.post('/users/me/career-path/save', payload)
}

export function getSaveCareerPathJobStatus(saveJobId: string) {
  return http.get(`/users/me/career-path/save-jobs/${encodeURIComponent(saveJobId)}`)
}

export function getLatestCareerPath() {
  return http.get('/users/me/career-path/latest')
}

export function getCareerPathDetail(payload: {
  pathId?: string
  draftId?: string
}) {
  const params: Record<string, string> = {}
  if (payload.pathId) {
    params.pathId = payload.pathId
  }
  if (payload.draftId) {
    params.draftId = payload.draftId
  }
  return http.get('/users/me/career-path/detail', { params })
}

export function resetCareerPathDraft() {
  return http.post('/users/me/career-path/reset-draft')
}

export function getSavedCareerPathList() {
  return http.get('/users/me/career-path/saved-list')
}

export function deleteSavedCareerPath(pathId: string) {
  return http.delete(`/users/me/career-path/saved-list/${pathId}`)
}
