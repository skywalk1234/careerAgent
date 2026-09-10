import { defineStore } from 'pinia'
import type { MatchRecommendationsResult } from '../services/matchAnalysis'

/**
 * 岗位推荐结果缓存：让推荐结果在页面间切换时不再丢失。
 *
 * 用 sessionStorage 而非 localStorage —— 切换路由、刷新浏览器都还在，
 * 关掉标签页就清空，避免下次打开时看到很久以前的推荐。
 * 写法对齐 stores/app.ts 的画像缓存（带版本号的 key + 时间戳 + 防御式解析）。
 */
const RECOMMEND_CACHE_KEY = 'career-job-recommend-cache-v1'

type RecommendCachePayload = {
  userId: string
  result: MatchRecommendationsResult
  cachedAt: string
}

/** 只保留这两个字段：/jobs/recommend/specific 的返回就只有它们（见 matchAnalysis.ts 的归一化逻辑） */
function normalizeResult(result: MatchRecommendationsResult | null | undefined): MatchRecommendationsResult | null {
  if (!result || (!result.bestMatch && !Array.isArray(result.otherRecommendations))) return null
  return {
    bestMatch: result.bestMatch ?? null,
    otherRecommendations: Array.isArray(result.otherRecommendations) ? result.otherRecommendations : [],
  }
}

/** 空结果（既没有 bestMatch 也没有备选）不缓存：存了只会让下次进来看到一片空白 */
function hasRecommendations(result: MatchRecommendationsResult): boolean {
  return Boolean(result.bestMatch) || result.otherRecommendations.length > 0
}

function readPersistedRecommendCache(): RecommendCachePayload | null {
  try {
    const raw = sessionStorage.getItem(RECOMMEND_CACHE_KEY)
    if (!raw) return null
    const parsed = JSON.parse(raw) as Partial<RecommendCachePayload>
    const result = normalizeResult(parsed.result)
    if (!result) return null
    return {
      userId: typeof parsed.userId === 'string' ? parsed.userId : '',
      result,
      cachedAt: typeof parsed.cachedAt === 'string' ? parsed.cachedAt : '',
    }
  } catch {
    return null
  }
}

export const useJobRecommendStore = defineStore('jobRecommend', {
  state: () => ({
    result: null as MatchRecommendationsResult | null,
    cachedAt: null as string | null,
    cachedUserId: '',
  }),
  actions: {
    /**
     * 进入页面时恢复缓存。缓存所属 userId 与当前用户不一致时丢弃，避免串号。
     * 两者都为空串时不判定为不一致（空串是「尚未取到用户」的默认值，不是另一个用户）。
     */
    hydrate(userId: string) {
      const cached = readPersistedRecommendCache()
      if (!cached || !hasRecommendations(cached.result)) return
      if (cached.userId && userId && cached.userId !== userId) {
        this.clear()
        return
      }
      this.result = cached.result
      this.cachedAt = cached.cachedAt
      this.cachedUserId = cached.userId
    },
    save(userId: string, result: MatchRecommendationsResult | null | undefined) {
      const normalized = normalizeResult(result)
      if (!normalized || !hasRecommendations(normalized)) return
      const payload: RecommendCachePayload = {
        userId,
        result: normalized,
        cachedAt: new Date().toISOString(),
      }
      this.result = normalized
      this.cachedAt = payload.cachedAt
      this.cachedUserId = userId
      try {
        sessionStorage.setItem(RECOMMEND_CACHE_KEY, JSON.stringify(payload))
      } catch {
        // 存储不可用（隐私模式 / 超额）时降级为仅内存，本次会话内仍可正常使用
      }
    },
    clear() {
      this.result = null
      this.cachedAt = null
      this.cachedUserId = ''
      try {
        sessionStorage.removeItem(RECOMMEND_CACHE_KEY)
      } catch {
        // ignore
      }
    },
  },
})
