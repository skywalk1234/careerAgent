import type { HomeMessage } from '../services/home'

const TASK_SESSION_ID_STORAGE_KEY = 'home_task_orchestrator_session_id'
const TASK_TRANSCRIPT_STORAGE_KEY = 'home_task_orchestrator_transcript'

interface TaskTranscriptStore {
  [sessionId: string]: HomeMessage[]
}

function safeParseJson<T>(raw: string | null, fallback: T): T {
  if (!raw) return fallback
  try {
    const parsed = JSON.parse(raw) as T
    if (parsed && typeof parsed === 'object') {
      return parsed
    }
    return fallback
  } catch {
    return fallback
  }
}

function readTranscriptStore(): TaskTranscriptStore {
  const raw = localStorage.getItem(TASK_TRANSCRIPT_STORAGE_KEY)
  const parsed = safeParseJson<TaskTranscriptStore>(raw, {})
  if (!parsed || typeof parsed !== 'object') {
    return {}
  }
  return parsed
}

function writeTranscriptStore(store: TaskTranscriptStore) {
  localStorage.setItem(TASK_TRANSCRIPT_STORAGE_KEY, JSON.stringify(store))
}

function normalizeMessage(input: HomeMessage): HomeMessage {
  return {
    messageId: String(input.messageId || '').trim(),
    role: input.role === 'user' ? 'user' : 'assistant',
    content: String(input.content || ''),
    status: input.status === 'failed' ? 'failed' : input.status === 'processing' ? 'processing' : 'succeeded',
    createdAt: String(input.createdAt || new Date().toISOString()),
    fileNames: Array.isArray(input.fileNames) ? input.fileNames : [],
    actions: Array.isArray(input.actions) ? input.actions : [],
    agentTrace: input.agentTrace || null,
    taskResultCard: input.taskResultCard || null,
  }
}

function sortMessagesByCreatedAt(list: HomeMessage[]) {
  return list.slice().sort((a, b) => {
    const left = Date.parse(String(a.createdAt || ''))
    const right = Date.parse(String(b.createdAt || ''))
    if (!Number.isFinite(left) && !Number.isFinite(right)) return 0
    if (!Number.isFinite(left)) return -1
    if (!Number.isFinite(right)) return 1
    return left - right
  })
}

export function getTaskSessionId() {
  return String(localStorage.getItem(TASK_SESSION_ID_STORAGE_KEY) || '').trim()
}

export function setTaskSessionId(sessionId: string) {
  const normalized = String(sessionId || '').trim()
  if (!normalized) return
  localStorage.setItem(TASK_SESSION_ID_STORAGE_KEY, normalized)
}

export function clearTaskSessionId() {
  localStorage.removeItem(TASK_SESSION_ID_STORAGE_KEY)
}

export function isTaskSession(sessionId: string) {
  const current = getTaskSessionId()
  const normalized = String(sessionId || '').trim()
  return Boolean(current && normalized && current === normalized)
}

export function getTaskTranscriptMessages(sessionId: string) {
  const normalized = String(sessionId || '').trim()
  if (!normalized) return []

  const store = readTranscriptStore()
  const list = Array.isArray(store[normalized]) ? store[normalized] : []
  return sortMessagesByCreatedAt(list.map(normalizeMessage))
}

export function upsertTaskTranscriptMessage(sessionId: string, message: HomeMessage) {
  const normalizedSessionId = String(sessionId || '').trim()
  if (!normalizedSessionId) return

  const normalizedMessage = normalizeMessage(message)
  if (!normalizedMessage.messageId) return

  const store = readTranscriptStore()
  const list = Array.isArray(store[normalizedSessionId]) ? store[normalizedSessionId].map(normalizeMessage) : []
  const index = list.findIndex(item => item.messageId === normalizedMessage.messageId)
  if (index >= 0) {
    list[index] = {
      ...list[index],
      ...normalizedMessage,
      // Keep original createdAt stable to avoid timeline order jumps after updates.
      createdAt: list[index].createdAt || normalizedMessage.createdAt,
    }
  } else {
    list.push(normalizedMessage)
  }

  store[normalizedSessionId] = sortMessagesByCreatedAt(list)
  writeTranscriptStore(store)
}

export function mergeTaskTranscriptMessages(sessionId: string, sourceMessages: HomeMessage[]) {
  const normalizedSessionId = String(sessionId || '').trim()
  const source = Array.isArray(sourceMessages) ? sourceMessages.map(normalizeMessage) : []
  if (!normalizedSessionId) return source

  const transcript = getTaskTranscriptMessages(normalizedSessionId)
  if (!transcript.length) return source

  const byId = new Map<string, HomeMessage>()
  source.forEach(item => {
    if (!item.messageId) return
    byId.set(item.messageId, item)
  })
  transcript.forEach(item => {
    if (!item.messageId) return
    if (byId.has(item.messageId)) {
      byId.set(item.messageId, {
        ...byId.get(item.messageId),
        ...item,
      } as HomeMessage)
      return
    }
    byId.set(item.messageId, item)
  })

  return sortMessagesByCreatedAt(Array.from(byId.values()))
}

export function clearTaskTranscript(sessionId: string) {
  const normalizedSessionId = String(sessionId || '').trim()
  if (!normalizedSessionId) return
  const store = readTranscriptStore()
  if (!Object.prototype.hasOwnProperty.call(store, normalizedSessionId)) return
  delete store[normalizedSessionId]
  writeTranscriptStore(store)
}
