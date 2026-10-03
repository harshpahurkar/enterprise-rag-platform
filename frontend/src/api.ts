export const ROLES = ['ADMIN', 'HR', 'FINANCE', 'LEGAL', 'ENGINEERING', 'EMPLOYEE'] as const

export interface Session {
  token: string
  username: string
  roles: string[]
}

/** A seeded demo account. Served only under the backend's demo profile, and never with a password. */
export interface DemoAccount {
  username: string
  roles: string[]
}

export interface DocumentInfo {
  id: number
  title: string
  filename: string
  contentType: string | null
  allowedRoles: string[]
  uploadedBy: string
  createdAt: string
  chunkCount: number
}

export interface Chunk {
  chunkId: number
  documentId: number
  title: string
  chunkIndex: number
  content: string
  score: number
}

export interface Source extends Chunk {
  n: number
}

export interface SearchResponse {
  results: Chunk[]
  retrievalMs: number
}

export interface AskResponse {
  answer: string
  refused: boolean
  sources: Source[]
  retrievalMs: number
  generationMs: number
}

const SESSION_KEY = 'erp.session'
const LOGIN_PATH = '/api/auth/login'
export const SESSION_EXPIRED_EVENT = 'erp:session-expired'

export function loadSession(): Session | null {
  try {
    const raw = sessionStorage.getItem(SESSION_KEY)
    return raw ? (JSON.parse(raw) as Session) : null
  } catch {
    return null
  }
}

export function saveSession(session: Session) {
  sessionStorage.setItem(SESSION_KEY, JSON.stringify(session))
}

export function clearSession() {
  sessionStorage.removeItem(SESSION_KEY)
}

export class ApiError extends Error {
  readonly status: number

  constructor(status: number, message: string) {
    super(message)
    this.status = status
  }
}

const UNREACHABLE = 'Cannot reach the server. Check that the backend is running, then try again.'

export async function request<T>(path: string, init?: RequestInit): Promise<T> {
  // Headers built separately and passed last, so a caller's headers never drop Content-Type or the token.
  const headers = new Headers(init?.headers)
  if (!(init?.body instanceof FormData) && !headers.has('Content-Type')) {
    headers.set('Content-Type', 'application/json')
  }
  const token = loadSession()?.token
  if (token && path !== LOGIN_PATH) headers.set('Authorization', `Bearer ${token}`)

  let response: Response
  try {
    response = await fetch(path, { ...init, headers })
  } catch {
    throw new ApiError(0, UNREACHABLE)
  }

  if (response.status === 401 && path !== LOGIN_PATH) {
    clearSession()
    window.dispatchEvent(new Event(SESSION_EXPIRED_EVENT))
  }

  if (!response.ok) {
    if (response.status === 429) {
      const seconds = Number(response.headers.get('Retry-After'))
      throw new ApiError(429, Number.isFinite(seconds) && seconds > 0 ? `Too many requests. Try again in ${seconds} seconds.` : 'Too many requests. Try again shortly.')
    }
    const body = await response.text()
    // An empty 502-504 comes from a proxy whose backend is down.
    if (!body && response.status >= 502 && response.status <= 504) throw new ApiError(response.status, UNREACHABLE)
    let parsed: { detail?: unknown; error?: unknown; title?: unknown } | null = null
    try {
      parsed = JSON.parse(body) as { detail?: unknown; error?: unknown; title?: unknown }
    } catch {
      parsed = null
    }
    const detail = parsed?.detail ?? parsed?.error ?? parsed?.title
    if (typeof detail === 'string') throw new ApiError(response.status, detail)
    if (Array.isArray(detail)) {
      throw new ApiError(response.status, detail.map((item) => JSON.stringify(item)).join('; '))
    }
    throw new ApiError(response.status, body || `${response.status} ${response.statusText}`)
  }

  // 204 and any other empty body resolve to undefined.
  const text = await response.text()
  return (text ? JSON.parse(text) : undefined) as T
}

const json = (method: string, body: unknown): RequestInit => ({ method, body: JSON.stringify(body) })

export const api = {
  login: (username: string, password: string) => request<Session>(LOGIN_PATH, json('POST', { username, password })),
  demoAccounts: () => request<DemoAccount[]>('/api/auth/demo-accounts'),
  documents: () => request<DocumentInfo[]>('/api/documents'),
  upload: (form: FormData) => request<DocumentInfo>('/api/documents', { method: 'POST', body: form }),
  deleteDocument: (id: number) => request<void>(`/api/documents/${id}`, { method: 'DELETE' }),
  search: (query: string) => request<SearchResponse>('/api/search', json('POST', { query })),
  ask: (question: string) => request<AskResponse>('/api/ask', json('POST', { question })),
}
