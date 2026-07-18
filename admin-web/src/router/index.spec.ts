import { flushPromises, mount } from '@vue/test-utils'
import { defineComponent, nextTick, reactive } from 'vue'
import { createMemoryHistory, RouterView, type Router } from 'vue-router'
import { afterEach, describe, expect, it, vi } from 'vitest'

import { siteApi } from '@/api/siteApi'
import { publishingApi } from '@/api/publishingApi'
import { createSessionStore } from '@/stores/session'
import { createSiteFixture } from '@/tests/fixtures/siteWorkspace'
import { SITE_ID } from '@/types/publishing'

import { createAdminRouter, disposeAdminRouter, sanitizeAdminRedirect } from './index'

type GuardPhase = 'UNKNOWN' | 'ANONYMOUS' | 'TOTP_REQUIRED' | 'AUTHENTICATED'
const routers: Router[] = []

function createTestRouter(session: Parameters<typeof createAdminRouter>[0]): Router {
  const router = createAdminRouter(session, createMemoryHistory('/'))
  routers.push(router)
  return router
}

afterEach(() => {
  for (const router of routers.splice(0)) disposeAdminRouter(router)
  vi.restoreAllMocks()
})

function createGuardSession(initial: GuardPhase, bootstrapped: GuardPhase = initial) {
  const state = reactive({
    phase: initial,
    secondFactorExpiresAt:
      initial === 'TOTP_REQUIRED' ? '2999-01-01T00:00:00Z' : null,
  })
  return {
    state,
    bootstrap: vi.fn(async () => {
      state.phase = bootstrapped
      state.secondFactorExpiresAt =
        bootstrapped === 'TOTP_REQUIRED' ? '2999-01-01T00:00:00Z' : null
      return bootstrapped
    }),
    invalidate: vi.fn(() => {
      state.phase = 'ANONYMOUS'
      state.secondFactorExpiresAt = null
    }),
  }
}

describe('admin route guard', () => {
  it('redirects an anonymous dashboard request to login', async () => {
    const session = createGuardSession('UNKNOWN', 'ANONYMOUS')
    const router = createTestRouter(session)

    await router.push('/admin/projects/demo?locale=en#summary')
    await router.isReady()

    expect(session.bootstrap).toHaveBeenCalledOnce()
    expect(router.currentRoute.value.name).toBe('login')
    expect(router.currentRoute.value.query.redirect).toBe(
      '/admin/projects/demo?locale=en#summary',
    )
  })

  it('continues an active password challenge at the TOTP route', async () => {
    const session = createGuardSession('TOTP_REQUIRED')
    const router = createTestRouter(session)

    await router.push('/admin/projects')

    expect(router.currentRoute.value.name).toBe('totp')
    expect(router.currentRoute.value.query.redirect).toBe('/admin/projects')
  })

  it('continues an active challenge when the login route is revisited', async () => {
    const session = createGuardSession('TOTP_REQUIRED')
    const router = createTestRouter(session)

    await router.push('/admin/login?redirect=/admin/site')

    expect(router.currentRoute.value.name).toBe('totp')
    expect(router.currentRoute.value.query.redirect).toBe('/admin/site')
  })

  it('rejects the TOTP route when no challenge is active', async () => {
    const session = createGuardSession('ANONYMOUS')
    const router = createTestRouter(session)

    await router.push('/admin/totp?redirect=/admin/projects')

    expect(router.currentRoute.value.name).toBe('login')
    expect(router.currentRoute.value.query.redirect).toBe('/admin/projects')
  })

  it('invalidates an expired local challenge before routing', async () => {
    const session = createGuardSession('TOTP_REQUIRED')
    session.state.secondFactorExpiresAt = '2000-01-01T00:00:00Z'
    const router = createTestRouter(session)

    await router.push('/admin/totp?redirect=/admin/projects')

    expect(session.invalidate).toHaveBeenCalledOnce()
    expect(router.currentRoute.value.name).toBe('login')
    expect(router.currentRoute.value.query.redirect).toBe('/admin/projects')
  })

  it('still renders login when bootstrap cannot reach the server', async () => {
    const session = createGuardSession('UNKNOWN')
    session.bootstrap.mockRejectedValueOnce(new Error('network unavailable'))
    const router = createTestRouter(session)

    await router.push('/admin/login?redirect=/admin/settings')

    expect(router.currentRoute.value.name).toBe('login')
    expect(router.currentRoute.value.query.redirect).toBe('/admin/settings')
  })

  it('recovers a protected direct navigation from repeated bootstrap failure', async () => {
    const session = createGuardSession('UNKNOWN')
    session.bootstrap.mockRejectedValue(new Error('network unavailable'))
    const router = createTestRouter(session)

    await router.push('/admin/settings')

    expect(router.currentRoute.value.name).toBe('login')
    expect(router.currentRoute.value.query.redirect).toBe('/admin/settings')
    expect(session.bootstrap).toHaveBeenCalledTimes(2)
  })

  it('redirects authenticated visitors away from auth routes', async () => {
    const session = createGuardSession('AUTHENTICATED')
    const router = createTestRouter(session)

    await router.push('/admin/login')

    expect(router.currentRoute.value.name).toBe('dashboard')
    expect(router.resolve({ name: 'dashboard' }).href).toBe('/admin/dashboard')
  })

  it('returns to login when a live protected session is invalidated', async () => {
    const session = createSessionStore({
      getMe: vi.fn().mockResolvedValue({
        id: '47c9505c-8134-4fd1-aa0c-32d15c52dba5',
        username: 'admin',
      }),
      ensureCsrf: vi.fn(),
      passwordStage: vi.fn(),
      secondFactor: vi.fn(),
      logout: vi.fn(),
    })
    await session.bootstrap()
    const router = createTestRouter(session)
    await router.push('/admin/settings')

    session.invalidate()
    await nextTick()
    await flushPromises()

    expect(router.currentRoute.value.name).toBe('login')
    expect(router.currentRoute.value.query.redirect).toBe('/admin/settings')
  })

  it('registers the complete named-route and parameter contract', () => {
    const router = createTestRouter(createGuardSession('AUTHENTICATED'))
    for (const name of [
      'login',
      'totp',
      'dashboard',
      'site',
      'projects',
      'project-new',
      'project-edit',
      'media',
      'publishing-history',
      'messages',
      'analytics',
      'settings',
      'admin-not-found',
    ]) {
      expect(router.hasRoute(name)).toBe(true)
    }
    expect(router.resolve({ name: 'project-edit', params: { projectId: 'p1' } }).href).toBe(
      '/admin/projects/p1',
    )
    expect(
      router.resolve({
        name: 'publishing-history',
        params: { aggregateType: 'PROJECT', aggregateId: 'p1' },
      }).href,
    ).toBe('/admin/publishing/PROJECT/p1/history')
  })

  it('routes the project catalog, static create path, and stable edit UUID to real views', () => {
    const router = createTestRouter(createGuardSession('AUTHENTICATED'))

    const catalog = router.resolve('/admin/projects')
    const create = router.resolve('/admin/projects/new')
    const edit = router.resolve(`/admin/projects/20000000-0000-4000-8000-000000000001`)

    expect(catalog.name).toBe('projects')
    expect(create.name).toBe('project-new')
    expect(edit.name).toBe('project-edit')
    expect(create.matched.at(-1)?.path).toBe('/admin/projects/new')
    expect(edit.params.projectId).toBe('20000000-0000-4000-8000-000000000001')
  })

  it('keeps the admin root and not-found destination behind authentication', async () => {
    const authenticatedRouter = createTestRouter(createGuardSession('AUTHENTICATED'))
    await authenticatedRouter.push('/admin')
    expect(authenticatedRouter.currentRoute.value.name).toBe('dashboard')
    await authenticatedRouter.push('/admin/does-not-exist')
    expect(authenticatedRouter.currentRoute.value.name).toBe('admin-not-found')

    const anonymousRouter = createTestRouter(createGuardSession('ANONYMOUS'))
    await anonymousRouter.push('/admin/does-not-exist')
    expect(anonymousRouter.currentRoute.value.name).toBe('login')
    expect(anonymousRouter.currentRoute.value.query.redirect).toBe(
      '/admin/does-not-exist',
    )
  })

  it('provides one main landmark around dashboard and the complete SITE editor', async () => {
    vi.spyOn(siteApi, 'get').mockResolvedValue(createSiteFixture())
    vi.spyOn(publishingApi, 'state').mockResolvedValue({
      aggregateType: 'SITE',
      aggregateId: SITE_ID,
      status: 'UNPUBLISHED',
      version: 0,
      currentRevisionId: null,
      publishedAt: null,
      projectIdsInOrder: [],
    })
    const router = createTestRouter(createGuardSession('AUTHENTICATED'))
    await router.push('/admin/dashboard')
    await router.isReady()
    const Host = defineComponent({
      components: { RouterView },
      template: '<RouterView />',
    })
    const wrapper = mount(Host, { global: { plugins: [router] } })
    await flushPromises()

    expect(wrapper.findAll('main')).toHaveLength(1)
    expect(
      wrapper.get('main#admin-main > section[aria-labelledby="dashboard-title"]').element.tagName,
    ).toBe('SECTION')

    await router.push('/admin/site')
    await flushPromises()

    expect(wrapper.findAll('main')).toHaveLength(1)
    expect(
      wrapper.get('main#admin-main > section[aria-labelledby="site-editor-title"]').element
        .tagName,
    ).toBe('SECTION')
    expect(wrapper.text()).toContain('双语简历')

    wrapper.unmount()
  })
})

describe('sanitizeAdminRedirect', () => {
  it('keeps only non-auth same-origin admin destinations', () => {
    expect(sanitizeAdminRedirect('/admin/projects/42?tab=content#editor')).toBe(
      '/admin/projects/42?tab=content#editor',
    )
    for (const unsafe of [
      'https://evil.example/admin',
      '//evil.example/admin',
      '/\\evil.example/admin',
      '/\t/evil.example/admin',
      '/public',
      '/admin/login',
      '/admin/login/',
      '/admin/%6cogin',
      '/admin/totp',
      '/admin/totp/',
      '/admin/%74otp',
      '/admin//evil',
      '/admin/%2f/evil',
      '/admin/%5cevil',
      '/admin/../../outside',
      '/admin/%2e%2e/%2e%2e/outside',
      `/admin/${'x'.repeat(2100)}`,
      ['/admin/projects'],
      null,
    ]) {
      expect(sanitizeAdminRedirect(unsafe)).toBe('/admin/dashboard')
    }
  })
})
