// Calls to the BFF only (same origin). Writes carry the CSRF token from the XSRF-TOKEN cookie.

export type FieldError = { field: string; code: string }

export class ApiError extends Error {
  constructor(
    public status: number,
    public code: string,
    public fieldErrors: FieldError[] = [],
    public retryAfterSeconds?: number,
    public attemptsRemaining?: number,
  ) {
    super(code)
  }
}

function csrfToken(): string {
  const match = document.cookie.match(/(?:^|;\s*)XSRF-TOKEN=([^;]+)/)
  return match ? decodeURIComponent(match[1]) : ''
}

export async function api<T>(method: string, path: string, body?: unknown, headers: Record<string, string> = {}): Promise<{ data: T; etag?: string }> {
  const init: RequestInit = { method, credentials: 'same-origin', headers: { Accept: 'application/json', ...headers } }
  if (method !== 'GET') {
    ;(init.headers as Record<string, string>)['X-XSRF-TOKEN'] = csrfToken()
  }
  if (body !== undefined) {
    ;(init.headers as Record<string, string>)['Content-Type'] = 'application/json'
    init.body = JSON.stringify(body)
  }
  let res: Response
  try {
    res = await fetch(path, init)
  } catch {
    throw new ApiError(0, 'NETWORK')
  }
  const text = await res.text()
  const json = text ? JSON.parse(text) : {}
  if (!res.ok) {
    const retry = res.headers.get('Retry-After')
    throw new ApiError(res.status, json.errorCode ?? (res.status === 401 ? 'SIGNED_OUT' : 'ERROR'), json.fieldErrors ?? [],
      retry ? Number(retry) : json.retryAfterSeconds, json.attemptsRemaining)
  }
  return { data: json as T, etag: res.headers.get('ETag') ?? undefined }
}

export type Session = {
  authenticated: boolean
  maskedMobile?: string
  idleTimeoutSeconds?: number
  brand: string
  noticeVersion: string
  mitcVersion: string
  polling: { scheduleSeconds: number[]; maxSeconds: number }
}

export type Draft = {
  answers: Record<string, Record<string, unknown>>
  sectionsDone: string[]
  complete: boolean
  rowVersion: number
  expiresAt: string
}

export type Status = {
  applicationRef: string
  customerStatus: 'SUBMITTED' | 'IN_PROGRESS' | 'UNDER_REVIEW' | 'APPROVED' | 'DECLINED' | 'WITHDRAWN' | 'CLOSED'
  nextAction: string
  canWithdraw: boolean
  updatedAt: string
}

export type Card = { code: string; name: string; joiningFee: number; annualFee: number; benefit: string }
