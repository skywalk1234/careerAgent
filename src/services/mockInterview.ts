import { aiHttp, http } from './http'

/* ============ 类型（对齐 ai-service-py app/schemas.py 面试段 + career-service 报告接口） ============ */

export type InterviewType = 'technical' | 'behavior' | 'mixed'

export interface InterviewStreamConfig {
  protocol: string
  url: string
}

/** 面试会话列表项（GET /users/me/interview/sessions，驼峰字段） */
export interface InterviewSessionItem {
  sessionId: string
  type: InterviewType | string
  status: 'active' | 'ended' | string
  jobId?: string | null
  jobTitle?: string | null
  companyName?: string | null
  profileId?: string | null
  reportId?: number | null
  createdAt: string
  endedAt?: string | null
}

/** 面试问答消息（interview_messages，一问一答原文） */
export interface InterviewMessageItem {
  messageId: string
  role: 'user' | 'assistant' | string
  content: string
  status: string
  createdAt: string
}

/** 报告列表项（Java GET /users/me/interview-reports，不含 content） */
export interface InterviewReportListItem {
  id: number
  title: string
  jobId?: string | null
  jobName?: string | null
  companyName?: string | null
  createdAt: string
}

/** 报告详情（Java GET /users/me/interview-reports/{id}，含 content markdown 全文） */
export interface InterviewReportDetail extends InterviewReportListItem {
  content: string
  sessionId?: string | null
}

/* ============ 面试会话（Python ai-service-py 8086，前端直连） ============ */

export function createInterviewSession(body: {
  type: InterviewType
  profileId?: string
  jobId?: string
}) {
  return aiHttp.post('/users/me/interview/sessions', body)
}

export function getInterviewSessionList() {
  return aiHttp.get('/users/me/interview/sessions')
}

export function getInterviewSessionMessages(sessionId: string) {
  return aiHttp.get(`/users/me/interview/sessions/${encodeURIComponent(sessionId)}/messages`)
}

/** 发送用户回答；成功后用返回的 stream.url 连 SSE 收面试官回复 */
export function sendInterviewMessage(sessionId: string, content: string) {
  return aiHttp.post(`/users/me/interview/sessions/${encodeURIComponent(sessionId)}/messages`, {
    content,
  })
}

/* ============ 面试总结报告（Java career-service，经网关 /api） ============ */

export function getInterviewReportList() {
  return http.get('/users/me/interview-reports')
}

export function getInterviewReportDetail(reportId: number | string) {
  return http.get(`/users/me/interview-reports/${encodeURIComponent(String(reportId))}`)
}

export function deleteInterviewReport(reportId: number | string) {
  return http.delete(`/users/me/interview-reports/${encodeURIComponent(String(reportId))}`)
}
