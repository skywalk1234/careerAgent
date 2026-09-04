import { http } from './http'

export interface CareerPlanListItem {
  id: number
  title: string
  status: 'active' | 'archived' | string
  createdAt: string
}

export interface CareerPlanDetail extends CareerPlanListItem {
  content: string
  supersedesPlanId: number | null
}

/** 行动方案列表（不含正文，按时间倒序；当前方案 = 最新一条 active） */
export function getCareerPlanList() {
  return http.get('/users/me/plans')
}

/** 行动方案详情（含 content markdown 全文），越权返回 404 */
export function getCareerPlanDetail(planId: number | string) {
  return http.get(`/users/me/plans/${encodeURIComponent(String(planId))}`)
}
