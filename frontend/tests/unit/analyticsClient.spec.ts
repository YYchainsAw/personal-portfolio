import { expect, it, vi } from 'vitest'
import {
  AnalyticsClient, MAX_ANALYTICS_BYTES, SESSION_KEY, SESSION_MAX_IDLE,
  validAnalyticsShape, VISITOR_KEY, VISITOR_MAX_AGE,
} from '@/services/analyticsClient'

function deterministicCrypto() {
  let uuid = 0
  return {
    getRandomValues<T extends ArrayBufferView | null>(array: T): T {
      if (array instanceof Uint8Array) array.forEach((_value, index) => { array[index] = index + 1 })
      return array
    },
    randomUUID: () => `00000000-0000-4000-8000-${String(++uuid).padStart(12, '0')}`,
  } as unknown as Crypto
}

it('creates 128-bit rotating IDs only when allowed and sends an exact CSRF-protected batch', async () => {
  const csrf = { ensureToken: vi.fn().mockResolvedValue({ headerName: 'X-CSRF', parameterName: '_csrf', token: 'token' }), invalidate: vi.fn() }
  const fetcher = vi.fn().mockResolvedValue(new Response(null, { status: 204 }))
  const client = new AnalyticsClient({ allowed: () => true, dnt: () => false, csrf, fetchImpl: fetcher, cryptoImpl: deterministicCrypto(), now: () => 1000 })
  expect(JSON.parse(localStorage.getItem(VISITOR_KEY) || '{}').id).toMatch(/^[A-Za-z0-9_-]{22}$/)
  expect(JSON.parse(sessionStorage.getItem(SESSION_KEY) || '{}').id).toMatch(/^[A-Za-z0-9_-]{22}$/)
  client.track({ type: 'PAGE_VIEW', pageKey: 'HOME', projectId: null, locale: 'en' })
  await client.flush()
  expect(csrf.ensureToken).toHaveBeenCalledTimes(1)
  const [, init] = fetcher.mock.calls[0]!
  expect((init.headers as Record<string, string>)['X-CSRF']).toBe('token')
  const body = JSON.parse(String(init.body))
  expect(Object.keys(body).sort()).toEqual(['analyticsConsent', 'events', 'sessionId', 'visitorId'])
  expect(body.events).toHaveLength(1)
})

it('drops invalid allowlist/project combinations locally', async () => {
  const csrf = { ensureToken: vi.fn(), invalidate: vi.fn() }
  const fetcher = vi.fn()
  const client = new AnalyticsClient({ allowed: () => true, dnt: () => false, csrf, fetchImpl: fetcher, cryptoImpl: deterministicCrypto() })
  client.track({ type: 'PROJECT_VIEW', pageKey: 'PROJECT_DETAIL', projectId: null, locale: 'en' })
  await client.flush()
  expect(csrf.ensureToken).not.toHaveBeenCalled(); expect(fetcher).not.toHaveBeenCalled()
})

it('drops a runtime locale outside the exact published locale set', () => {
  expect(validAnalyticsShape({
    type: 'PAGE_VIEW', pageKey: 'HOME', projectId: null, locale: 'fr',
  } as never)).toBe(false)
})

it.each([
  [{ type: 'PROJECT_VIEW', pageKey: 'PROJECT_DETAIL', projectId: '00000000-0000-4000-8000-000000000001', locale: 'en' }, true],
  [{ type: 'DEMO_DOWNLOAD', pageKey: 'PROJECT_DETAIL', projectId: '00000000-0000-4000-8000-000000000001', locale: 'en' }, true],
  [{ type: 'RESUME_DOWNLOAD', pageKey: 'HOME', projectId: null, locale: 'en' }, true],
  [{ type: 'PAGE_VIEW', pageKey: 'HOME', projectId: null, locale: 'en' }, true],
  [{ type: 'OUTBOUND_CLICK', pageKey: 'PROJECT_DETAIL', projectId: '00000000-0000-4000-8000-000000000001', locale: 'en' }, true],
  [{ type: 'DEMO_DOWNLOAD', pageKey: 'HOME', projectId: null, locale: 'en' }, false],
  [{ type: 'PAGE_VIEW', pageKey: 'HOME', projectId: '00000000-0000-4000-8000-000000000001', locale: 'en' }, false],
  [{ type: 'RESUME_DOWNLOAD', pageKey: 'HOME', projectId: '00000000-0000-4000-8000-000000000001', locale: 'en' }, false],
  [{ type: 'OUTBOUND_CLICK', pageKey: 'PROJECT_DETAIL', projectId: null, locale: 'en' }, false],
])('mirrors the backend event/project shape for %#', (input, expected) => {
  expect(validAnalyticsShape(input as never)).toBe(expected)
})

it('rotates visitor and session identifiers at the exact boundaries', () => {
  const visitor = 'AAAAAAAAAAAAAAAAAAAAAA', session = 'BBBBBBBBBBBBBBBBBBBBBB'
  let now = VISITOR_MAX_AGE
  localStorage.setItem(VISITOR_KEY, JSON.stringify({ version: 1, id: visitor, createdAt: 1 }))
  sessionStorage.setItem(SESSION_KEY, JSON.stringify({ version: 1, id: session, lastActivityAt: now - SESSION_MAX_IDLE + 1 }))
  new AnalyticsClient({ allowed: () => true, dnt: () => false, cryptoImpl: deterministicCrypto(), now: () => now })
  expect(JSON.parse(localStorage.getItem(VISITOR_KEY)!).id).toBe(visitor)
  expect(JSON.parse(sessionStorage.getItem(SESSION_KEY)!).id).toBe(session)

  now += 1
  new AnalyticsClient({ allowed: () => true, dnt: () => false, cryptoImpl: deterministicCrypto(), now: () => now })
  expect(JSON.parse(localStorage.getItem(VISITOR_KEY)!).id).not.toBe(visitor)

  localStorage.setItem(VISITOR_KEY, JSON.stringify({ version: 1, id: visitor, createdAt: now }))
  sessionStorage.setItem(SESSION_KEY, JSON.stringify({ version: 1, id: session, lastActivityAt: now - SESSION_MAX_IDLE }))
  new AnalyticsClient({ allowed: () => true, dnt: () => false, cryptoImpl: deterministicCrypto(), now: () => now })
  expect(JSON.parse(sessionStorage.getItem(SESSION_KEY)!).id).not.toBe(session)
})

it('rejects future-dated identifier records instead of extending their lifetime', () => {
  const visitor = 'AAAAAAAAAAAAAAAAAAAAAA', session = 'BBBBBBBBBBBBBBBBBBBBBB'
  const now = 10_000
  localStorage.setItem(VISITOR_KEY, JSON.stringify({ version: 1, id: visitor, createdAt: now + 1 }))
  sessionStorage.setItem(SESSION_KEY, JSON.stringify({ version: 1, id: session, lastActivityAt: now + 1 }))

  new AnalyticsClient({ allowed: () => true, dnt: () => false, cryptoImpl: deterministicCrypto(), now: () => now })

  expect(JSON.parse(localStorage.getItem(VISITOR_KEY)!).id).not.toBe(visitor)
  expect(JSON.parse(sessionStorage.getItem(SESSION_KEY)!).id).not.toBe(session)
})

it('splits consented traffic into batches of at most 20 and 32 KiB', async () => {
  const csrf = { ensureToken: vi.fn().mockResolvedValue({ headerName: 'X', parameterName: '_csrf', token: 't' }), invalidate: vi.fn() }
  const fetcher = vi.fn().mockImplementation(async () => new Response(null, { status: 204 }))
  const client = new AnalyticsClient({ allowed: () => true, dnt: () => false, csrf, fetchImpl: fetcher, cryptoImpl: deterministicCrypto() })
  const referrer = `https://example.com/${'x'.repeat(1800)}`
  for (let index = 0; index < 20; index += 1) client.track({ type: 'PAGE_VIEW', pageKey: 'HOME', projectId: null, locale: 'en', referrer })
  await client.flush()
  const bodies = fetcher.mock.calls.map((call) => String(call[1]?.body))
  expect(bodies.reduce((total, body) => total + JSON.parse(body).events.length, 0)).toBe(20)
  expect(bodies.every((body) => JSON.parse(body).events.length <= 20 && new TextEncoder().encode(body).byteLength <= MAX_ANALYTICS_BYTES)).toBe(true)
})

it('bounds token retries and only invalidates an exact CSRF_INVALID 403', async () => {
  const delay = vi.fn().mockResolvedValue(undefined)
  const missing = { ensureToken: vi.fn().mockRejectedValue(new Error('no')), invalidate: vi.fn() }
  const noPost = vi.fn()
  const first = new AnalyticsClient({ allowed: () => true, dnt: () => false, csrf: missing, fetchImpl: noPost, cryptoImpl: deterministicCrypto(), delay })
  first.track({ type: 'PAGE_VIEW', pageKey: 'HOME', projectId: null, locale: 'en' }); await first.flush()
  expect(missing.ensureToken).toHaveBeenCalledTimes(3); expect(noPost).not.toHaveBeenCalled(); expect(delay).toHaveBeenCalledTimes(2)

  const csrf = { ensureToken: vi.fn().mockResolvedValue({ headerName: 'X', parameterName: '_csrf', token: 't' }), invalidate: vi.fn() }
  const forbidden = vi.fn().mockImplementation(async () => new Response('{"code":"OTHER_FORBIDDEN"}', { status: 403 }))
  const second = new AnalyticsClient({ allowed: () => true, dnt: () => false, csrf, fetchImpl: forbidden, cryptoImpl: deterministicCrypto(), delay })
  second.track({ type: 'PAGE_VIEW', pageKey: 'HOME', projectId: null, locale: 'en' }); await second.flush()
  expect(forbidden).toHaveBeenCalledTimes(1); expect(csrf.invalidate).not.toHaveBeenCalled()
})

it('clears identifiers and performs no token acquisition when DNT becomes active', async () => {
  let dnt = false
  const csrf = { ensureToken: vi.fn(), invalidate: vi.fn() }
  const client = new AnalyticsClient({ allowed: () => true, dnt: () => dnt, csrf, fetchImpl: vi.fn(), cryptoImpl: deterministicCrypto() })
  expect(localStorage.getItem(VISITOR_KEY)).not.toBeNull()
  dnt = true
  client.track({ type: 'PAGE_VIEW', pageKey: 'HOME', projectId: null, locale: 'en' })
  await client.flush()
  expect(localStorage.getItem(VISITOR_KEY)).toBeNull(); expect(sessionStorage.getItem(SESSION_KEY)).toBeNull()
  expect(csrf.ensureToken).not.toHaveBeenCalled()
})
