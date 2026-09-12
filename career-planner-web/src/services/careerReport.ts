import { http } from './http'

export interface CareerReportPathOption {
  pathId: string
  pathName: string
  pathNodeCount: number
  targetJobName: string
  feasibilityScore: number
  updatedAt: string
}

export interface CareerPathDetailNode {
  id: string
  jobId: string
  jobName: string
  stage: string
}

export interface CareerPathDetailResult {
  source: 'saved' | 'draft' | string
  savedPath?: {
    pathId: string
    pathName: string
    pathNodes: CareerPathDetailNode[]
    createdAt: string
    updatedAt: string
  }
  draft?: {
    draftId: string
    pathNodes: CareerPathDetailNode[]
    generatedAt: string
  }
}

export interface CareerReportSections {
  executiveSummary: { title: string; content: string }
  currentAssessment: { title: string; content: string }
  targetAnalysis: { title: string; content: string }
  pathStrategy: { title: string; content: string }
  stagePlan: {
    title: string
    milestones: Array<{
      stageLabel: string
      cycle: string
      goals: string[]
      tasks: string[]
      deliverables: string[]
    }>
  }
  riskControl: {
    title: string
    items: Array<{
      risk: string
      impact: string
      mitigation: string
      owner: string
    }>
  }
  resourceRecommendations: {
    title: string
    courses: Array<{ name: string; provider: string; url: string }>
    communities: Array<{ name: string; url: string }>
    certifications: Array<{ name: string; reason: string }>
  }
  reviewMechanism: {
    title: string
    cadence: string
    checkpoints: string[]
    adjustmentRule: string
  }
}

export interface CareerReportDetail {
  reportId: string
  reportTitle: string
  templateVersion: string
  status: 'draft' | 'final' | string
  pathRef: {
    pathId: string
    pathName: string
    pathNodeCount: number
    targetJobId: string
    targetJobName: string
  }
  profileSnapshot: {
    profileId: string | null
    name: string
    major: string
    city: string
    jobIntention: string[]
    completenessScore: number
    competitivenessScore: number
  }
  evaluationSnapshot: {
    feasibilityScore: number
    readinessScore: number
    recommendationScore: number
    riskAlerts: string[]
    summaryMetrics: Array<{ key: string; label: string; value: number }>
    abilityComparison?: {
      dimensions: Array<{
        key: string
        label: string
        studentScore: number
        targetRequiredScore: number
      }>
    }
  }
  reportSections: CareerReportSections
  editingMeta: {
    version: number
    lastEditedAt: string
    lastEditedBy: string
  }
  generatedAt: string
  updatedAt: string
}

export function getCareerReportPathOptions() {
  return http.get('/users/me/career-path/saved-list')
}

export function getLatestCareerPathSummary() {
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

export function createCareerReportGenerateJob(payload: {
  pathId: string
  reportTitle?: string
  templateVersion?: string
  overwriteLatest?: boolean
  overwriteSamePathReport?: boolean
}) {
  return http.post('/users/me/career-report/generate', payload)
}

export function getCareerReportGenerateJobStatus(reportJobId: string) {
  return http.get(`/users/me/career-report/generate-jobs/${encodeURIComponent(reportJobId)}`)
}

export function getCareerReportList() {
  return http.get('/users/me/career-report/list')
}

export function getLatestCareerReport() {
  return http.get('/users/me/career-report/latest')
}

export function getCareerReportDetail(reportId: string) {
  return http.get(`/users/me/career-report/${encodeURIComponent(reportId)}`)
}

export function updateCareerReport(reportId: string, payload: {
  reportTitle: string
  status: 'draft' | 'final' | string
  reportSections: CareerReportSections
}) {
  return http.put(`/users/me/career-report/${encodeURIComponent(reportId)}`, payload)
}

export function polishCareerReport(reportId: string, payload?: {
  tone?: string
  targetReader?: string
  focusSections?: Record<string, { instruction?: string }>
}) {
  return http.post(`/users/me/career-report/${encodeURIComponent(reportId)}/polish`, payload || {})
}

export function getCareerReportPolishJobStatus(polishJobId: string) {
  return http.get(`/users/me/career-report/polish-jobs/${encodeURIComponent(polishJobId)}`)
}

export function checkCareerReportCompleteness(reportId: string) {
  return http.post(`/users/me/career-report/${encodeURIComponent(reportId)}/completeness-check`)
}

export function createCareerReportExportJob(reportId: string, payload: {
  format: 'pdf' | 'docx' | 'markdown'
  includeCover?: boolean
  includeTimestamp?: boolean
  pdfTemplate?: {
    cover?: { enabled?: boolean; title?: string; subtitle?: string }
    header?: { enabled?: boolean; text?: string }
    footer?: { enabled?: boolean; text?: string }
    pagination?: { enabled?: boolean; format?: string }
  }
}) {
  return http.post(`/users/me/career-report/${encodeURIComponent(reportId)}/export`, payload)
}

export function getCareerReportExportJobStatus(exportJobId: string) {
  return http.get(`/users/me/career-report/export-jobs/${encodeURIComponent(exportJobId)}`)
}
