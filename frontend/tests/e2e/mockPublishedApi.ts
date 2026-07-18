import type { Page, Request, Route } from '@playwright/test'
import { card, project, site } from '../fixtures/publicSnapshots'
import type { Locale } from '../../src/types/public'

const CSRF_HEADER = 'X-SYNTHETIC-CSRF'
const CSRF_TOKEN = 'synthetic-token-never-log'
const eventTypes = new Set(['PAGE_VIEW', 'PROJECT_VIEW', 'RESUME_DOWNLOAD', 'DEMO_DOWNLOAD', 'OUTBOUND_CLICK'])
const pageKeys = new Set(['HOME', 'ABOUT', 'WORK', 'ROADMAP', 'CONTACT', 'PRIVACY', 'PROJECT_DETAIL'])
const eventKeys = ['eventId', 'locale', 'pageKey', 'projectId', 'referrer', 'type']
const batchKeys = ['analyticsConsent', 'events', 'sessionId', 'visitorId']
const contactKeys = ['email', 'message', 'name', 'privacyAccepted', 'subject', 'website']
const PROJECT_ID = '00000000-0000-0000-0000-000000000001'
const uuid = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/u
const uuidV4 = /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/u
const browserId = /^[A-Za-z0-9_-]{22}$/u
const tinyPng = Buffer.from('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII=', 'base64')

export interface PublishedApiOptions {
  contactStatuses?: number[]
  eventStatuses?: number[]
  csrfStatuses?: Array<'valid' | 'malformed' | 'error'>
  csrfMode?: 'valid' | 'malformed' | 'error'
  failSiteOnce?: boolean
  failProjectSlugOnce?: string
  emptyCatalog?: boolean
  noHeroMedia?: boolean
  delayProjectSlug?: string
  expectedAnalyticsLocale?: Locale
  delayCsrfMs?: number
}

export interface PublishedApiState {
  publicGets: number
  csrfGets: number
  contactPosts: number
  eventPosts: number
  headRequests: number
  order: string[]
  contactContracts: boolean[]
  eventContracts: boolean[]
  eventBatchSizes: number[]
  eventCounts: Record<string, number>
  supersededAbort: boolean
  sawDelayedProjectRequest: boolean
  flags: {
    sawHomeView: boolean
    sawProjectView: boolean
    sawResumeDownload: boolean
    sawDemoDownload: boolean
    sawContactOutbound: boolean
    sawProjectOutbound: boolean
  }
}

function exactKeys(value: unknown, keys: string[]) {
  return !!value && typeof value === 'object' && !Array.isArray(value)
    && Object.keys(value as Record<string, unknown>).sort().join('|') === [...keys].sort().join('|')
}

function validEventShape(value: unknown, expectedLocale: Locale) {
  if (!exactKeys(value, eventKeys)) return false
  const event = value as Record<string, unknown>
  const hasProject = typeof event.projectId === 'string' && uuid.test(event.projectId)
  const projectDetail = event.pageKey === 'PROJECT_DETAIL'
  const referrerValid = event.referrer === null || (typeof event.referrer === 'string' && /^https?:\/\//u.test(event.referrer))
  const relationValid = event.type === 'PROJECT_VIEW' || event.type === 'DEMO_DOWNLOAD'
    ? projectDetail && hasProject && event.projectId === PROJECT_ID
    : event.type === 'RESUME_DOWNLOAD'
      ? event.pageKey === 'HOME' && !hasProject
      : projectDetail
        ? hasProject && event.projectId === PROJECT_ID
        : !hasProject
  return typeof event.eventId === 'string' && uuidV4.test(event.eventId)
    && eventTypes.has(String(event.type)) && pageKeys.has(String(event.pageKey))
    && event.locale === expectedLocale && referrerValid && relationValid
}

function statusProblem(status: number) {
  return {
    type: 'synthetic_error', title: 'Synthetic safe error', status,
    code: status === 404 ? 'PROJECT_NOT_FOUND' : status === 422 ? 'VALIDATION_ERROR' : status === 400 ? 'MALFORMED_REQUEST' : status === 403 ? 'CSRF_INVALID' : 'SYNTHETIC_FAILURE',
    traceId: `synthetic-${status}`,
    ...(status === 422 ? { fieldErrors: { email: 'Synthetic email validation error' } } : {}),
  }
}

function localeFrom(request: Request): Locale {
  return new URL(request.url()).searchParams.get('locale') === 'en' ? 'en' : 'zh-CN'
}

export async function installPublishedApi(page: Page, options: PublishedApiOptions = {}) {
  const state: PublishedApiState = {
    publicGets: 0, csrfGets: 0, contactPosts: 0, eventPosts: 0, headRequests: 0,
    order: [], contactContracts: [], eventContracts: [], eventBatchSizes: [], eventCounts: {},
    supersededAbort: false, sawDelayedProjectRequest: false,
    flags: {
      sawHomeView: false, sawProjectView: false, sawResumeDownload: false,
      sawDemoDownload: false, sawContactOutbound: false, sawProjectOutbound: false,
    },
  }
  const contactStatuses = [...(options.contactStatuses ?? [202])]
  const eventStatuses = [...(options.eventStatuses ?? [204])]
  const csrfStatuses = [...(options.csrfStatuses ?? [])]
  let siteFailures = options.failSiteOnce ? 1 : 0
  let projectFailure = options.failProjectSlugOnce
  const context = page.context()

  page.on('requestfailed', (request) => {
    if (options.delayProjectSlug && new URL(request.url()).pathname.endsWith(`/projects/${options.delayProjectSlug}`)) {
      state.supersededAbort = true
    }
  })

  await context.route('https://www.youtube.com/**', (route) => route.fulfill({ contentType: 'text/html', body: '<!doctype html><title>synthetic video</title>' }))
  await context.route('https://example.com/**', (route) => route.fulfill({ contentType: 'text/html', body: '<!doctype html><title>synthetic external page</title>' }))
  await context.route('**/api/**', async (route: Route) => {
    const request = route.request()
    const url = new URL(request.url())
    const path = url.pathname
    const method = request.method()
    if (method === 'HEAD') state.headRequests += 1

    if (path.startsWith('/api/public/media/')) {
      if (path.endsWith('/document')) {
        state.order.push(`${method} ${path} 200`)
        await route.fulfill({ status: 200, contentType: 'application/zip', body: Buffer.from('synthetic-download') })
      } else {
        await route.fulfill({ status: 200, contentType: 'image/png', body: tinyPng })
      }
      return
    }

    if (method === 'GET' && path === '/api/admin/auth/csrf') {
      state.csrfGets += 1
      if (options.delayCsrfMs) await new Promise((resolve) => setTimeout(resolve, options.delayCsrfMs))
      const csrfMode = csrfStatuses.shift() ?? options.csrfMode ?? 'valid'
      if (csrfMode === 'error') {
        state.order.push('GET /api/admin/auth/csrf 503')
        await route.fulfill({ status: 503, contentType: 'application/problem+json', body: JSON.stringify(statusProblem(503)) })
      } else if (csrfMode === 'malformed') {
        state.order.push('GET /api/admin/auth/csrf 200-malformed')
        await route.fulfill({ status: 200, headers: { 'Cache-Control': 'no-store' }, contentType: 'application/json', body: JSON.stringify({ headerName: '', parameterName: '_csrf', token: '' }) })
      } else {
        state.order.push('GET /api/admin/auth/csrf 200')
        await route.fulfill({ status: 200, headers: { 'Cache-Control': 'no-store' }, contentType: 'application/json', body: JSON.stringify({ headerName: CSRF_HEADER, parameterName: '_csrf', token: CSRF_TOKEN }) })
      }
      return
    }

    if (method === 'POST' && path === '/api/public/contact') {
      state.contactPosts += 1
      let body: unknown = null
      try { body = request.postDataJSON() } catch { /* invalid JSON is a failed contract */ }
      const valid = request.headers()[CSRF_HEADER.toLowerCase()] === CSRF_TOKEN
        && request.headers().accept === 'application/json'
        && request.headers()['content-type']?.startsWith('application/json') === true
        && exactKeys(body, contactKeys)
        && (body as Record<string, unknown>).privacyAccepted === true
        && (body as Record<string, unknown>).website === ''
      state.contactContracts.push(valid)
      const status = contactStatuses.shift() ?? 202
      state.order.push(`POST /api/public/contact ${status}`)
      if (status === 202) await route.fulfill({ status, contentType: 'application/json', body: JSON.stringify({ accepted: true }) })
      else await route.fulfill({ status, headers: status === 429 ? { 'Retry-After': '60' } : {}, contentType: 'application/problem+json', body: JSON.stringify(statusProblem(status)) })
      return
    }

    if (method === 'POST' && path === '/api/public/events') {
      state.eventPosts += 1
      let body: unknown = null
      try { body = request.postDataJSON() } catch { /* invalid JSON is a failed contract */ }
      const batch = body as Record<string, unknown>
      const events = Array.isArray(batch?.events) ? batch.events : []
      const valid = request.headers()[CSRF_HEADER.toLowerCase()] === CSRF_TOKEN
        && request.headers().accept === 'application/json'
        && request.headers()['content-type']?.startsWith('application/json') === true
        && exactKeys(body, batchKeys) && batch.analyticsConsent === true
        && typeof batch.visitorId === 'string' && browserId.test(batch.visitorId)
        && typeof batch.sessionId === 'string' && browserId.test(batch.sessionId)
        && events.length >= 1 && events.length <= 20
        && events.every((event) => validEventShape(event, options.expectedAnalyticsLocale ?? 'en'))
        && Buffer.byteLength(request.postData() ?? '', 'utf8') <= 32 * 1024
      state.eventContracts.push(valid)
      state.eventBatchSizes.push(events.length)
      for (const item of events) {
        const event = item as Record<string, unknown>
        state.eventCounts[String(event.type)] = (state.eventCounts[String(event.type)] ?? 0) + 1
        if (event.type === 'PAGE_VIEW' && event.pageKey === 'HOME') state.flags.sawHomeView = true
        if (event.type === 'PROJECT_VIEW' && event.pageKey === 'PROJECT_DETAIL') state.flags.sawProjectView = true
        if (event.type === 'RESUME_DOWNLOAD') state.flags.sawResumeDownload = true
        if (event.type === 'DEMO_DOWNLOAD') state.flags.sawDemoDownload = true
        if (event.type === 'OUTBOUND_CLICK' && event.pageKey === 'CONTACT') state.flags.sawContactOutbound = true
        if (event.type === 'OUTBOUND_CLICK' && event.pageKey === 'PROJECT_DETAIL') state.flags.sawProjectOutbound = true
      }
      const status = eventStatuses.shift() ?? 204
      state.order.push(`POST /api/public/events ${status}`)
      if (status === 204) await route.fulfill({ status })
      else await route.fulfill({ status, contentType: 'application/problem+json', body: JSON.stringify(statusProblem(status)) })
      return
    }

    if (method === 'GET' && path === '/api/public/site') {
      state.publicGets += 1
      if (siteFailures > 0) {
        siteFailures -= 1
        state.order.push('GET /api/public/site 500')
        await route.fulfill({ status: 500, contentType: 'application/problem+json', body: JSON.stringify(statusProblem(500)) })
        return
      }
      const locale = localeFrom(request)
      const data = site(locale)
      if (options.noHeroMedia) data.hero.media = null
      state.order.push('GET /api/public/site 200')
      await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ revisionVersion: 2, checksum: 'a'.repeat(64), data }) })
      return
    }

    if (method === 'GET' && path === '/api/public/projects') {
      state.publicGets += 1
      const locale = localeFrom(request)
      state.order.push('GET /api/public/projects 200')
      await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ revisionVersion: 2, checksum: 'b'.repeat(64), data: options.emptyCatalog ? [] : [card(locale)] }) })
      return
    }

    if (method === 'GET' && path.startsWith('/api/public/projects/')) {
      state.publicGets += 1
      const slug = decodeURIComponent(path.slice('/api/public/projects/'.length))
      if (slug === options.delayProjectSlug) {
        state.sawDelayedProjectRequest = true
        await new Promise((resolve) => setTimeout(resolve, 750))
      }
      if (slug === 'missing') {
        state.order.push('GET /api/public/projects/missing 404')
        await route.fulfill({ status: 404, contentType: 'application/problem+json', body: JSON.stringify(statusProblem(404)) })
        return
      }
      if (slug === projectFailure) {
        projectFailure = undefined
        state.order.push(`GET /api/public/projects/${slug} 500`)
        await route.fulfill({ status: 500, contentType: 'application/problem+json', body: JSON.stringify(statusProblem(500)) })
        return
      }
      const locale = localeFrom(request)
      const data = project(locale)
      data.slug = slug
      state.order.push(`GET /api/public/projects/${slug} 200`)
      try {
        await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ revisionVersion: 2, checksum: 'c'.repeat(64), data }) })
      } catch {
        if (slug === options.delayProjectSlug) state.supersededAbort = true
      }
      return
    }

    state.order.push(`${method} ${path} 404`)
    await route.fulfill({ status: 404, contentType: 'application/problem+json', body: JSON.stringify(statusProblem(404)) })
  })

  return state
}
