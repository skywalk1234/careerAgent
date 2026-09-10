import { aiHttp } from './http'

/**
 * BOSS 直聘岗位采集 —— 前端 aiHttp 直连 ai-service-py（8086，不经过 Java 网关）。
 * 对齐 app/routers/crawl.py：统一前缀 /users/me/crawl，鉴权走 JWT，响应 {code, msg, data}。
 * 采集为后台异步任务：POST /start 提交后轮询 /status，任务完成后由 /status 回带统计结果。
 */

export type CrawlStatus = 'idle' | 'running' | 'stopping' | 'done' | 'error'

export interface CrawlStats {
  /** 爬虫原始抓取条数 */
  raw?: number
  /** 清洗后条数（标题命中关键词、未命中黑名单；自动打分类标签） */
  cleaned?: number
  /** 新增入库条数（jobs.is_new=1） */
  inserted?: number
  /** 已存在刷新条数 */
  updated?: number
  /** 清洗丢弃条数 */
  skipped?: number
}

export interface CrawlStatusSnapshot {
  runId?: string | null
  /** idle / running / stopping / done / error */
  status?: CrawlStatus | string
  /** 已完成的「关键词 × 城市」组合数 */
  comboIndex?: number | null
  /** 组合总数 = len(keywords) × len(cities) */
  totalCombos?: number | null
  currentKeyword?: string | null
  currentCity?: string | null
  jobsSoFar?: number
  startedAt?: string | null
  finishedAt?: string | null
  /** done/error 后带统计或原因 */
  stats?: CrawlStats | null
  dbFile?: string | null
  message?: string | null
}

export interface CrawlStartPayload {
  /** 岗位搜索词，标题需包含该词才会保留；为空则用服务端配置默认 */
  keywords?: string[]
  /** 意向城市名（后端解析为城市码）；为空则用服务端配置默认 */
  cities?: string[]
  /** BOSS 站内二级筛选 code，如 { salary: '406', degree: '203' }；值为数字字符串 */
  searchFilters?: Record<string, string>
  /** 每 关键词×城市 组合的新岗位目标数，达到即停（默认 20） */
  newJobTarget?: number
  /** 每组合最多浏览岗位数上限（默认 100） */
  maxJobs?: number
  /** 是否无头采集（缺省取配置 crawler_headless，默认 true） */
  headless?: boolean
}

export interface ApiResponse<T> {
  code: number
  msg: string
  data?: T | null
}

/** 支持的城市码表 {城市名: 城市码}，前端筛选项直接渲染 */
export function getCrawlSupportedCities() {
  return aiHttp.get<ApiResponse<Record<string, string>>>('/users/me/crawl/cities')
}

/** 提交一次采集任务（立即返回，后台异步执行）；已有任务在跑时返回 409 */
export function submitCrawlTask(payload: CrawlStartPayload) {
  return aiHttp.post<ApiResponse<CrawlStatusSnapshot>>('/users/me/crawl/start', payload)
}

/** 查询当前采集任务状态（无任务时 status=idle） */
export function queryCrawlStatus() {
  return aiHttp.get<ApiResponse<CrawlStatusSnapshot>>('/users/me/crawl/status')
}

/** 取最近一次采集结果（含清洗/入库统计）；暂无记录返回 404 */
export function fetchCrawlResult() {
  return aiHttp.get<ApiResponse<CrawlStatusSnapshot>>('/users/me/crawl/result')
}

/** 请求优雅停止当前任务（无运行任务返回 400） */
export function stopCrawlTask() {
  return aiHttp.post<ApiResponse<CrawlStatusSnapshot>>('/users/me/crawl/stop')
}
