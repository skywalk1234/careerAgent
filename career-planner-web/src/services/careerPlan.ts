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

/** 修改行动方案正文（content 为 markdown 全文；前端 WYSIWYG 编辑后先由 htmlToMarkdown 转回 markdown） */
export function updateCareerPlanContent(planId: number | string, content: string) {
  return http.put(`/users/me/plans/${encodeURIComponent(String(planId))}`, { content })
}

/** 删除行动方案（物理删除；若删当前 active，服务端自动把最新 archived 提升为 active） */
export function deleteCareerPlan(planId: number | string) {
  return http.delete(`/users/me/plans/${encodeURIComponent(String(planId))}`)
}
