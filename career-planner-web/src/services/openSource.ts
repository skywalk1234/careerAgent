import { http } from './http'

export type OpenSourceProvider = 'github' | 'gitee'

export interface OpenSourceAuthUrlResult {
  provider: OpenSourceProvider
  state: string
  authUrl: string
}

export interface ContributionPoint {
  date: string
  count: number
}

export interface LanguageStat {
  name: string
  value: number
}

export interface BonusDetail {
  dimension: string
  delta: number
  reason: string
}

export interface OpenSourceSummaryResult {
  authorized: boolean
  provider?: OpenSourceProvider
  accountName?: string
  profileUrl?: string
  authorizedAt?: string
  contributionHeatmap?: {
    days: ContributionPoint[]
  }
  languageStats?: LanguageStat[]
  bonusDetails?: BonusDetail[]
  totalBonus?: number
  note?: string
}

export interface OpenSourcePendingResult {
  pending: boolean
  provider?: OpenSourceProvider
  summary?: OpenSourceSummaryResult
  expiresAt?: number
}

export function getOpenSourceAuthUrl(provider: OpenSourceProvider, returnUrl?: string) {
  return http.get('/users/me/open-source/auth-url', {
    params: { provider, returnUrl },
  })
}

export function callbackOpenSourceAuth(provider: OpenSourceProvider, state: string, code: string) {
  return http.get('/users/me/open-source/callback', {
    params: { provider, state, code },
  })
}

export function getOpenSourcePending() {
  return http.get('/users/me/open-source/pending')
}

export function confirmOpenSourcePending() {
  return http.post('/users/me/open-source/pending/confirm')
}

export function cancelOpenSourcePending() {
  return http.post('/users/me/open-source/pending/cancel')
}

export function getOpenSourceSummary() {
  return http.get('/users/me/open-source/summary')
}

export function unbindOpenSource() {
  return http.post('/users/me/open-source/unbind')
}
