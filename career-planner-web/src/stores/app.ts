import { defineStore } from 'pinia'
import { getStudentProfile, type GetProfileResult } from '../services/studentProfile'

interface ApiResponse<T> {
  code: number
  msg: string
  data?: T
  payload?: T
}

const PROFILE_CACHE_KEY = 'career-profile-cache-v1'

type ProfileCachePayload = {
  profileFetched: boolean
  hasProfile: boolean | null
  profileSnapshot: GetProfileResult | null
  profileCachedAt: string | null
}

let profileSnapshotPromise: Promise<GetProfileResult | null> | null = null

function extractPayload<T>(response: { data: ApiResponse<T> }): T | undefined {
  return response.data.payload ?? response.data.data
}

function readPersistedProfileCache(): ProfileCachePayload {
  try {
    const raw = localStorage.getItem(PROFILE_CACHE_KEY)
    if (!raw) {
      return {
        profileFetched: false,
        hasProfile: null,
        profileSnapshot: null,
        profileCachedAt: null,
      }
    }
    const parsed = JSON.parse(raw) as Partial<ProfileCachePayload>
    return {
      profileFetched: Boolean(parsed.profileFetched),
      hasProfile: typeof parsed.hasProfile === 'boolean' ? parsed.hasProfile : null,
      profileSnapshot: parsed.profileSnapshot ?? null,
      profileCachedAt: typeof parsed.profileCachedAt === 'string' ? parsed.profileCachedAt : null,
    }
  } catch {
    return {
      profileFetched: false,
      hasProfile: null,
      profileSnapshot: null,
      profileCachedAt: null,
    }
  }
}

export const useAppStore = defineStore('app', {
  state: () => ({
    currentStudentId: '',
    currentTargetRole: '',
    mobileMenuOpen: false,
    profileFetched: false,
    hasProfile: null as boolean | null,
    profileSnapshot: null as GetProfileResult | null,
    profileCachedAt: null as string | null,
  }),
  actions: {
    hydrateProfileCacheFromStorage() {
      const cached = readPersistedProfileCache()
      this.profileFetched = cached.profileFetched
      this.hasProfile = cached.hasProfile
      this.profileSnapshot = cached.profileSnapshot
      this.profileCachedAt = cached.profileCachedAt
    },
    persistProfileCache() {
      const payload: ProfileCachePayload = {
        profileFetched: this.profileFetched,
        hasProfile: this.hasProfile,
        profileSnapshot: this.profileSnapshot,
        profileCachedAt: this.profileCachedAt,
      }
      localStorage.setItem(PROFILE_CACHE_KEY, JSON.stringify(payload))
    },
    setProfileSnapshot(payload: GetProfileResult | null) {
      this.profileSnapshot = payload
      this.hasProfile = Boolean(payload?.hasProfile)
      this.profileFetched = true
      this.profileCachedAt = new Date().toISOString()
      this.persistProfileCache()
    },
    clearProfileSnapshot() {
      this.profileFetched = false
      this.hasProfile = null
      this.profileSnapshot = null
      this.profileCachedAt = null
      profileSnapshotPromise = null
      localStorage.removeItem(PROFILE_CACHE_KEY)
    },
    async ensureProfileSnapshot(force = false) {
      if (!force && this.profileFetched && this.hasProfile !== null) {
        return this.profileSnapshot
      }
      if (!force && profileSnapshotPromise) {
        return profileSnapshotPromise
      }

      profileSnapshotPromise = (async () => {
        const response = await getStudentProfile()
        const payload = extractPayload<GetProfileResult>(response as { data: ApiResponse<GetProfileResult> }) ?? null
        this.setProfileSnapshot(payload)
        return this.profileSnapshot
      })()

      try {
        return await profileSnapshotPromise
      } finally {
        profileSnapshotPromise = null
      }
    },
    setStudentId(studentId: string) {
      this.currentStudentId = studentId
    },
    setTargetRole(role: string) {
      this.currentTargetRole = role
    },
    toggleMobileMenu() {
      this.mobileMenuOpen = !this.mobileMenuOpen
    },
  },
})
