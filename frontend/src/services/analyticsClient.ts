import { publicCsrfClient, type CsrfTokenProvider } from './csrfClient'
import {
  analyticsEventTypes,
  analyticsPageKeys,
  PUBLIC_INTERACTION_ENDPOINTS,
  type AnalyticsEvent,
  type AnalyticsEventBatch,
  type AnalyticsEventType,
  type AnalyticsPageKey,
} from '@/types/interactions'
import type { Locale } from '@/types/public'
import { isLocale } from '@/types/public'

export const VISITOR_KEY = 'portfolio.analytics.visitor.v1'
export const SESSION_KEY = 'portfolio.analytics.session.v1'
export const VISITOR_MAX_AGE = 30 * 24 * 60 * 60 * 1000
export const SESSION_MAX_IDLE = 30 * 60 * 1000
export const MAX_ANALYTICS_BATCH = 20
export const MAX_ANALYTICS_BYTES = 32 * 1024

interface VisitorRecord { version: 1; id: string; createdAt: number }
interface SessionRecord { version: 1; id: string; lastActivityAt: number }
export interface AnalyticsTrackInput {
  type: AnalyticsEventType
  pageKey: AnalyticsPageKey
  projectId: string | null
  referrer?: string | null
  locale: Locale
}

export interface AnalyticsClientOptions {
  fetchImpl?: typeof fetch
  csrf?: CsrfTokenProvider
  local?: Storage
  session?: Storage
  cryptoImpl?: Crypto
  now?: () => number
  allowed: () => boolean
  dnt?: () => boolean
  delay?: (milliseconds: number) => Promise<void>
}

const ID_PATTERN = /^[A-Za-z0-9_-]{22}$/u
const UUID_PATTERN = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/u
const validId = (value: unknown): value is string => typeof value === 'string' && ID_PATTERN.test(value)

function readRecord<T extends object>(storage: Storage, key: string): T | null {
  try {
    const raw = storage.getItem(key)
    if (raw === null) return null
    const value: unknown = JSON.parse(raw)
    if (value && typeof value === 'object') return value as T
    storage.removeItem(key)
    return null
  } catch { return null }
}

function randomId(cryptoImpl: Crypto): string {
  const bytes = cryptoImpl.getRandomValues(new Uint8Array(16))
  let binary = ''
  bytes.forEach((byte) => { binary += String.fromCharCode(byte) })
  return btoa(binary).replaceAll('+', '-').replaceAll('/', '_').replace(/=+$/u, '')
}

function validReferrer(referrer: unknown): referrer is string | null {
  if (referrer === null) return true
  if (typeof referrer !== 'string' || referrer.length > 2048 || referrer !== referrer.trim()) return false
  try {
    const url = new URL(referrer)
    return (url.protocol === 'http:' || url.protocol === 'https:') && !url.username && !url.password
  } catch { return false }
}

export function validAnalyticsShape(input: AnalyticsTrackInput): boolean {
  if (!analyticsEventTypes.includes(input.type) || !analyticsPageKeys.includes(input.pageKey)) return false
  if (!isLocale(input.locale)) return false
  if (input.projectId !== null && (typeof input.projectId !== 'string' || !UUID_PATTERN.test(input.projectId))) return false
  if (!validReferrer(input.referrer ?? null)) return false
  const projectDetail = input.pageKey === 'PROJECT_DETAIL'
  const hasProject = input.projectId !== null
  switch (input.type) {
    case 'PROJECT_VIEW':
    case 'DEMO_DOWNLOAD': return projectDetail && hasProject
    case 'RESUME_DOWNLOAD': return !hasProject
    case 'PAGE_VIEW':
    case 'OUTBOUND_CLICK': return projectDetail === hasProject
  }
}

export class AnalyticsClient {
  private readonly fetchImpl: typeof fetch
  private readonly csrf: CsrfTokenProvider
  private readonly local: Storage
  private readonly session: Storage
  private readonly cryptoImpl: Crypto
  private readonly now: () => number
  private readonly allowed: () => boolean
  private readonly dnt: () => boolean
  private readonly delay: (milliseconds: number) => Promise<void>
  private queue: AnalyticsEvent[] = []
  private timer: ReturnType<typeof setTimeout> | null = null
  private controller: AbortController | null = null
  private draining: Promise<void> | null = null
  private destroyed = false

  constructor(options: AnalyticsClientOptions) {
    this.fetchImpl = options.fetchImpl || fetch
    this.csrf = options.csrf || publicCsrfClient
    this.local = options.local || localStorage
    this.session = options.session || sessionStorage
    this.cryptoImpl = options.cryptoImpl || crypto
    this.now = options.now || Date.now
    this.allowed = options.allowed
    this.dnt = options.dnt || (() => navigator.doNotTrack === '1')
    this.delay = options.delay || ((milliseconds) => new Promise((resolve) => setTimeout(resolve, milliseconds)))
    this.identifiers()
  }

  private privacyAllows() { return !this.destroyed && !this.dnt() && this.allowed() }

  private identifiers(): { visitorId: string; sessionId: string } {
    if (!this.privacyAllows()) throw new Error('Analytics disabled')
    const now = this.now()
    let visitor = readRecord<VisitorRecord>(this.local, VISITOR_KEY)
    const visitorAge = visitor ? now - visitor.createdAt : Number.NaN
    if (!visitor || visitor.version !== 1 || !validId(visitor.id) || !Number.isFinite(visitor.createdAt) || visitorAge < 0 || visitorAge >= VISITOR_MAX_AGE) {
      visitor = { version: 1, id: randomId(this.cryptoImpl), createdAt: now }
      this.local.setItem(VISITOR_KEY, JSON.stringify(visitor))
    }
    let session = readRecord<SessionRecord>(this.session, SESSION_KEY)
    const sessionIdle = session ? now - session.lastActivityAt : Number.NaN
    if (!session || session.version !== 1 || !validId(session.id) || !Number.isFinite(session.lastActivityAt) || sessionIdle < 0 || sessionIdle >= SESSION_MAX_IDLE) {
      session = { version: 1, id: randomId(this.cryptoImpl), lastActivityAt: now }
      this.session.setItem(SESSION_KEY, JSON.stringify(session))
    }
    return { visitorId: visitor.id, sessionId: session.id }
  }

  track(input: AnalyticsTrackInput) {
    if (!this.privacyAllows()) { this.suppress(); return }
    if (!validAnalyticsShape(input)) return
    let ids
    try { ids = this.identifiers() } catch { this.suppress(); return }
    const event: AnalyticsEvent = {
      eventId: this.cryptoImpl.randomUUID(), type: input.type, pageKey: input.pageKey,
      projectId: input.projectId, referrer: input.referrer ?? null, locale: input.locale,
    }
    const size = (events: AnalyticsEvent[]) => new TextEncoder().encode(JSON.stringify({ analyticsConsent: true, ...ids, events })).byteLength
    if (size([event]) > MAX_ANALYTICS_BYTES) return
    if (this.queue.length && (this.queue.length >= MAX_ANALYTICS_BATCH || size([...this.queue, event]) > MAX_ANALYTICS_BYTES)) void this.flush()
    this.queue.push(event)
    try {
      const session = readRecord<SessionRecord>(this.session, SESSION_KEY)
      if (session && validId(session.id)) this.session.setItem(SESSION_KEY, JSON.stringify({ ...session, lastActivityAt: this.now() }))
    } catch { this.suppress(); return }
    if (this.queue.length >= MAX_ANALYTICS_BATCH) void this.flush()
    else this.scheduleFlush()
  }

  private scheduleFlush() {
    if (this.timer || this.destroyed) return
    this.timer = setTimeout(() => { this.timer = null; void this.flush() }, 2000)
  }

  flush(keepalive = false): Promise<void> {
    if (this.timer) { clearTimeout(this.timer); this.timer = null }
    if (!this.privacyAllows()) { this.suppress(); return Promise.resolve() }
    if (this.draining) return this.draining
    if (!this.queue.length) return Promise.resolve()
    this.draining = this.drain(keepalive).finally(() => {
      this.draining = null
      if (this.queue.length && this.privacyAllows()) this.scheduleFlush()
    })
    return this.draining
  }

  private async drain(keepalive: boolean) {
    while (this.queue.length && this.privacyAllows()) {
      let ids
      try { ids = this.identifiers() } catch { this.suppress(); return }
      const events: AnalyticsEvent[] = []
      while (this.queue.length && events.length < MAX_ANALYTICS_BATCH) {
        const next = this.queue[0]!
        const candidate = { analyticsConsent: true as const, ...ids, events: [...events, next] }
        if (new TextEncoder().encode(JSON.stringify(candidate)).byteLength > MAX_ANALYTICS_BYTES) break
        events.push(this.queue.shift()!)
      }
      if (!events.length) { this.queue.shift(); continue }
      await this.sendBatch({ analyticsConsent: true, ...ids, events }, keepalive)
    }
    if (!this.privacyAllows()) this.suppress()
  }

  private async sendBatch(payload: AnalyticsEventBatch, keepalive: boolean) {
    for (let attempt = 0; attempt < 3; attempt += 1) {
      if (!this.privacyAllows()) { this.suppress(); return }
      let token
      try { token = await this.csrf.ensureToken() } catch {
        if (attempt < 2) { await this.delay(attempt === 0 ? 1000 : 3000); continue }
        return
      }
      if (!this.privacyAllows()) { this.suppress(); return }
      this.controller = new AbortController()
      try {
        // Native `window.fetch` must not be invoked with the AnalyticsClient as
        // its receiver. Copy it to a local before calling so the browser uses
        // the normal function-call semantics (and injected test fetches still
        // behave exactly the same).
        const fetchImpl = this.fetchImpl
        const response = await fetchImpl(PUBLIC_INTERACTION_ENDPOINTS.events, {
          method: 'POST', credentials: 'same-origin', keepalive, signal: this.controller.signal,
          headers: { Accept: 'application/json', 'Content-Type': 'application/json', [token.headerName]: token.token },
          body: JSON.stringify(payload),
        })
        if (response.status === 204) return
        let csrfInvalid = false
        if (response.status === 403) {
          try { const body: unknown = await response.json(); csrfInvalid = !!body && typeof body === 'object' && (body as Record<string, unknown>).code === 'CSRF_INVALID' } catch { /* discard unknown 403 */ }
        }
        const retryable = response.status === 429 || response.status >= 500 || csrfInvalid
        if (csrfInvalid) this.csrf.invalidate()
        if (!retryable || attempt === 2) return
      } catch {
        if (this.destroyed || attempt === 2) return
      } finally { this.controller = null }
      await this.delay(attempt === 0 ? 1000 : 3000)
    }
  }

  private suppress() {
    if (this.timer) clearTimeout(this.timer)
    this.timer = null
    this.queue = []
    this.clearIdentifiers()
  }

  clearIdentifiers() {
    try { this.local.removeItem(VISITOR_KEY) } catch { /* fail closed */ }
    try { this.session.removeItem(SESSION_KEY) } catch { /* fail closed */ }
  }

  destroy() {
    this.destroyed = true
    this.suppress()
    this.controller?.abort()
    this.controller = null
  }
}
