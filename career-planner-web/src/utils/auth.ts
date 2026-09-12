const TOKEN_KEY = 'career-token'
const USER_KEY = 'career-user'

export function getToken() {
  return localStorage.getItem(TOKEN_KEY)
}

export function setToken(token: string) {
  localStorage.setItem(TOKEN_KEY, token)
}

export function removeToken() {
  localStorage.removeItem(TOKEN_KEY)
}

export function setUser(user: unknown) {
  localStorage.setItem(USER_KEY, JSON.stringify(user ?? {}))
}

export function getUser<T = Record<string, unknown>>() {
  const raw = localStorage.getItem(USER_KEY)
  if (!raw) return null
  try {
    return JSON.parse(raw) as T
  } catch {
    return null
  }
}

export function clearAuthStorage() {
  removeToken()
  localStorage.removeItem(USER_KEY)
}

function parseJwtPayload(token: string) {
  try {
    const payload = token.split('.')[1]
    if (!payload) return null
    const base64 = payload.replace(/-/g, '+').replace(/_/g, '/')
    const json = decodeURIComponent(
      atob(base64)
        .split('')
        .map((char) => `%${`00${char.charCodeAt(0).toString(16)}`.slice(-2)}`)
        .join(''),
    )
    return JSON.parse(json) as { exp?: number }
  } catch {
    return null
  }
}

export function isTokenExpired(token: string) {
  const payload = parseJwtPayload(token)
  if (!payload?.exp) return false
  return payload.exp * 1000 <= Date.now()
}
