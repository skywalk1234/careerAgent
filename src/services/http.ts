import axios from 'axios'
import { ElMessage } from 'element-plus'
import router from '../router'
import { clearAuthStorage, getToken } from '../utils/auth'

const PRE_INTENT_ENCOURAGE_TIP_KEY = 'home_pre_intent_login_tip_shown'

function tryParseRequestData(raw: unknown) {
  if (!raw) return null
  if (typeof raw === 'object') return raw as Record<string, unknown>
  if (typeof raw !== 'string') return null
  try {
    return JSON.parse(raw) as Record<string, unknown>
  } catch {
    return null
  }
}

function shouldShowPreIntentEncourageTip(status: number, msg: unknown, error: any) {
  if (status !== 400) return false
  if (String(msg || '') !== '请至少填写一项可用于缩小推荐范围的测评内容') return false

  const requestUrl = String(error?.config?.url || '')
  if (!requestUrl.includes('/users/me/match/recommendations/pre-intent')) return false

  const requestData = tryParseRequestData(error?.config?.data)
  return String(requestData?.action || '').toLowerCase() === 'status'
}

function showPreIntentEncourageTipOnce() {
  try {
    if (sessionStorage.getItem(PRE_INTENT_ENCOURAGE_TIP_KEY) === '1') return
    sessionStorage.setItem(PRE_INTENT_ENCOURAGE_TIP_KEY, '1')
  } catch {
    // Ignore storage errors in private mode or restricted environments.
  }

  ElMessage.success({
    message: '登录成功，可选做职业兴趣测评',
    showClose: true,
  })
}

export function getResponseCode(rawCode: unknown, fallbackStatus = 200) {
  const parsed = Number(rawCode)
  return Number.isFinite(parsed) ? parsed : fallbackStatus
}

export function isSuccessCode(code: number) {
  return code >= 200 && code < 300
}

export function isConflictCode(code: number) {
  return code === 409
}

export const http = axios.create({
  baseURL: '/api',
  timeout: 15000,
  headers: {
    'Content-Type': 'application/json',
    token: '',
  },
})

http.interceptors.request.use((config) => {
  const token = getToken()
  if (token) {
    config.headers.token = token
    config.headers.Authorization = `Bearer ${token}`
  }
  return config
})

http.interceptors.response.use(
  (response) => {
    const status = Number(response?.status || 200)
    try {
      if (response?.data) {
        const { msg, data } = response.data
        if ((data === undefined || data === null) && msg && typeof msg === 'object') {
          response.data.data = msg
        }

        const codeFromPayload = getResponseCode(response.data.code, status)
        response.data.httpCode = codeFromPayload
        response.data.code = codeFromPayload

        response.data.payload =
          response.data.data !== undefined
            ? response.data.data
            : response.data.msg && typeof response.data.msg === 'object'
              ? response.data.msg
              : undefined
      }
    } catch (error) {
      console.warn('http response normalize failed', error)
    }

    if (status === 401 || status === 403 || response.data?.httpCode === 401 || response.data?.httpCode === 403) {
      clearAuthStorage()
      ElMessage.error({
        message: '登录已过期，请重新登录',
        showClose: true,
      })
      router.replace('/auth')
      return Promise.reject({ message: '登录已过期', response })
    }

    return response
  },
  (error) => {
    const status = Number(error.response?.status || 0)
    if (error.response?.data) {
      const { msg } = error.response.data
      const code = getResponseCode(error.response.data.code, status)
      if (status === 401 || status === 403 || code === 401 || code === 403 || msg === 'NOT_LOGIN') {
        clearAuthStorage()
        ElMessage.error({ message: '未登录或登录失效，请重新登录', showClose: true })
        router.replace('/auth')
      } else if (shouldShowPreIntentEncourageTip(status, msg, error)) {
        showPreIntentEncourageTipOnce()
      } else if (typeof msg === 'string' && msg) {
        ElMessage.error({ message: msg, showClose: true })
      }
    } else {
      ElMessage.error({
        message: '网络异常，请检查网络连接',
        showClose: true,
      })
    }

    return Promise.reject(error)
  },
)
