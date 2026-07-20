import { http } from './http'

export type ParseMode = 'standard' | 'strict'

export interface BasicInfo {
  name: string
  gender: string
  birthday: string
  phone: string
  email: string
  city: string
  jobIntention: string[]
}

export interface EducationItem {
  school: string
  major: string
  degree: string
  startDate: string
  endDate: string
  gpa: string
}

export interface WorkExperienceItem {
  company: string
  role: string
  startDate: string
  endDate: string
  description: string
  position?: string
}

export interface CertificateItem {
  name: string
  date: string
  issuer: string
}

export interface ProfileFormData {
  basicInfo: BasicInfo
  education: EducationItem[]
  workExperience: WorkExperienceItem[]
  skills: string[]
  certificates: CertificateItem[]
  organizeExp: string[]
  projects: string[]
  selfEvaluation: string
}

export interface AbilityScores {
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

export interface ScoreData {
  completenessScore: number
  competitivenessScore: number
  abilityScores: AbilityScores
  bonusByDimension: AbilityScores
}

export interface ImprovementSuggestion {
  dimension: keyof AbilityScores
  priority: 'high' | 'medium' | 'low' | string
  advice: string
}

export interface SaveProfileResult {
  profileId: string
  updatedAt: string
  scores?: ScoreData | null
  analysisStatus?: 'processing' | 'succeeded' | 'failed'
  analyzeJobId?: string
  evidence?: Partial<Record<keyof AbilityScores, string[]>>
  improvementSuggestions?: ImprovementSuggestion[]
}

export interface ProfileAnalyzeJobStatusResult {
  analyzeJobId: string
  status: 'processing' | 'succeeded' | 'failed'
  progress?: number
  pollAfterMs?: number
}

export interface ParseProfileResult {
  parsedProfile: Partial<ProfileFormData>
  missingFields: string[]
  sourceMeta?: {
    fileName: string
    fileType: string
  }
}

export interface ParseProfileJobCreateResult {
  parseJobId: string
  status: 'processing'
  pollAfterMs?: number
}

export interface ParseProfileJobStatusResult {
  parseJobId: string
  status: 'processing' | 'succeeded' | 'failed'
  progress?: number
  pollAfterMs?: number
  result?: ParseProfileResult
}

export interface GetProfileResult {
  hasProfile: boolean
  profileId: string | null
  profile: ProfileFormData | null
  scores: ScoreData | null
  evidence?: Partial<Record<keyof AbilityScores, string[]>>
  improvementSuggestions?: ImprovementSuggestion[]
  updatedAt: string | null
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

export function createParseProfileJob(file: File, parseMode: ParseMode = 'standard') {
  const formData = new FormData()
  formData.append('file', file)
  formData.append('parseMode', parseMode)

  return http.post('/users/me/profile/parse-jobs', formData, {
    headers: {
      'Content-Type': 'multipart/form-data',
    },
  })
}

export function getParseProfileJobStatus(parseJobId: string) {
  return http.get(`/users/me/profile/parse-jobs/${encodeURIComponent(parseJobId)}`)
}

export function saveStudentProfile(profile: ProfileFormData, returnEvidence = true) {
  return http.post('/users/me/profile', {
    profile,
    analyzeOptions: {
      returnEvidence,
    },
  })
}

export function getProfileAnalyzeJobStatus(analyzeJobId: string) {
  return http.get(`/users/me/profile/analyze-jobs/${encodeURIComponent(analyzeJobId)}`)
}

export function getStudentProfile() {
  return http.get('/users/me/profile')
}

export function getStudentProfileAggregate() {
  return http.get('/analytics/student-profiles/aggregate')
}
