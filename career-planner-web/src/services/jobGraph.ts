import { http } from './http'
import type { CompetencyKey } from '../types/domain'

/**
 * 岗位探索链路的数据源已从 ES 的 jobs_index 换成 pgvector 的 job_detail_vector
 * （写入方是 ai-service-py 的 BOSS 直聘爬虫）。选项与字段只覆盖爬虫真实采集到的内容，
 * 原来的 industryTags / level / companyType / companySize / district 新表里没有，已去掉。
 */
export interface JobFilterOptions {
  /** 向量库里 DISTINCT 出来的城市 */
  cities: string[]
  educationRequirements?: string[]
  /** 经验要求，如 3-5年 */
  exps?: string[]
  /** 薪资档，如 15-30K */
  salaryTiers?: string[]
}

export interface JobListItem {
  /** job_key（表上唯一键） */
  jobId: string
  /** BOSS 招聘帖 id，形如 boss:8a3f1c... */
  bossJobId?: string | null
  jobName: string
  categoryName?: string
  companyName: string
  city: string
  /** 岗位分类标签，由爬虫 cat_rules 自动打，如 ["大模型/LLM"] */
  cats?: string[]
  /** 原始薪资文本，如 20-35K·15薪 */
  salaryText?: string | null
  salaryMin?: number | null
  salaryMax?: number | null
  salaryUnit?: string | null
  /** 月平均薪资，单位 K（薪资区间筛选/排序用的就是它） */
  avg?: number | null
  /** 薪资档：15-30K / 30-50K / 50K+ ... */
  tier?: string | null
  exp?: string | null
  edu?: string | null
  source?: string | null
  sourceSite?: string | null
  sourceUrl?: string | null
  updatedAtRaw?: string | null
  /** 最近一次采集到该岗位的时间（ISO 8601 带时区） */
  lastSeen?: string | null
  firstSeen?: string | null
  isNew?: boolean
  /** 是否已向量化、可被 RAG 检索 */
  vectorReady?: boolean
}

export interface JobListQuery {
  keyword?: string
  city?: string
  /** 月平均薪资下限，单位 K */
  salaryMin?: number
  /** 月平均薪资上限，单位 K */
  salaryMax?: number
  salaryTier?: string[]
  exp?: string[]
  edu?: string[]
  sortBy?: 'salary' | 'updatedAt' | 'createdAt' | string
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

/** 详情 = 列表字段 + JD 正文 */
export interface JobDetailResult extends JobListItem {
  jobDescription: string
  /**
   * 以下三项是 ES 时代的遗留字段，爬虫采集的数据里没有。
   * 详情面板会在这三项都为空时整块隐藏（见 JobGraphView 的 hasDetailAbilityData）。
   */
  abilityRequirements?: Record<CompetencyKey, number>
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
    edu?: string
    salaryText?: string
    updatedAtRaw?: string
    favoritedAt: string
  }>
}

export interface CustomFavoriteJobRequest {
  title: string
  company?: string
  city?: string
  salary?: string
  salaryMin?: number
  salaryMax?: number
  salaryUnit?: string
  avg?: number
  tier?: string
  exp?: string
  edu?: string
  categories?: string[]
  keywords?: string[]
  url?: string
  content?: string
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

export function createCustomFavoriteJob(data: CustomFavoriteJobRequest) {
  return http.post('/users/me/favorite-jobs/custom', data)
}

export function removeFavoriteJob(jobId: string) {
  return http.delete(`/users/me/favorite-jobs/${jobId}`)
}
