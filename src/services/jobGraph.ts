import { http } from './http'
import type { CompetencyKey } from '../types/domain'

export interface JobFilterOptions {
  jobNames?: string[]
  cities: string[]
  districts?: string[]
  industryTags: string[]
  levels: Array<'junior' | 'mid' | 'middle' | 'senior' | 'lead' | string>
  companySizes?: string[]
  companyTypes?: string[]
  educationRequirements?: string[]
  salaryUnits?: string[]
  salaryMonths?: number[]
}

export interface JobListItem {
  jobId: string
  jobName: string
  categoryName?: string
  jobCode: string
  companyName: string
  city: string
  district?: string
  industryTags: string[]
  educationRequirement?: string
  salaryMin?: number | null
  salaryMax?: number | null
  salaryUnit?: string
  salaryMonths?: number | null
  salaryNegotiable?: boolean
  salaryNormalized?: string
  level: 'junior' | 'mid' | 'middle' | 'senior' | 'lead' | string
  updatedAtRaw?: string
  updatedAtNormalized?: string | null
  sourceSite?: string
  companySize?: string
  companyType?: string
}

export interface JobListQuery {
  keyword?: string
  city?: string
  industryTag?: string
  educationRequirement?: string
  level?: string
  companySize?: string
  companyType?: string
  sortBy?: 'salary' | 'updatedAt' | string
  sortOrder?: 'asc' | 'desc' | string
  page?: number
  pageSize?: number
}

export interface JobListResult {
  total: number
  page: number
  pageSize: number
  list: JobListItem[]
}

export interface JobGraphNode {
  jobName: string
  industryTags: string[]
  jobDescription?: string
}

export interface JobGraphEdge {
  source: string
  target: string
  relationType: 'promotion' | 'transition' | string
  similarity: number
  difficulty: 'low' | 'medium' | 'high' | string
  reason: string
}

export interface JobGraphResult {
  nodes: JobGraphNode[]
  edges: JobGraphEdge[]
}

export interface JobDetailResult {
  jobId: string
  jobName: string
  categoryName?: string
  jobCode: string
  companyName: string
  city: string
  district?: string
  industryTags: string[]
  educationRequirement?: string
  salaryMin?: number | null
  salaryMax?: number | null
  salaryUnit?: string
  salaryMonths?: number | null
  salaryNegotiable?: boolean
  salaryNormalized?: string
  updatedAtRaw?: string
  updatedAtNormalized?: string | null
  sourceUrl?: string
  sourceSite?: string
  companySize: string
  companyType: string
  level: 'junior' | 'mid' | 'middle' | 'senior' | 'lead' | string
  jobDescription: string
  companyBrief?: string
  companyDescription?: string
  abilityRequirements: Record<CompetencyKey, number>
  dimensionDetails?: Partial<Record<CompetencyKey, string>>
  keySkills?: {
    hardSkills?: string[]
    tools?: string[]
  }
}

export interface FavoriteJobsResult {
  total: number
  list: Array<{
    jobId: string
    jobName: string
    companyName?: string
    city: string
    educationRequirement?: string
    salaryNegotiable?: boolean
    salaryNormalized?: string
    updatedAtRaw?: string
    updatedAtNormalized?: string | null
    favoritedAt: string
  }>
}

export function getJobFilters() {
  return http.get('/jobs/filters')
}

export function getJobList(params: JobListQuery) {
  return http.post('/jobs/search', params)
}

export function getJobGraph(params: {
  categoryName: string
}) {
  return http.get('/jobs/graph', { params })
}

export function getJobDetail(jobId: string) {
  return http.get(`/jobs/${jobId}`)
}

export function getFavoriteJobs() {
  return http.get('/users/me/favorite-jobs')
}

export function addFavoriteJob(jobId: string) {
  return http.post('/users/me/favorite-jobs', { jobId })
}

export function removeFavoriteJob(jobId: string) {
  return http.delete(`/users/me/favorite-jobs/${jobId}`)
}
