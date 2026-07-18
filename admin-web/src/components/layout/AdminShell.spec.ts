import { flushPromises, mount } from '@vue/test-utils'
import { defineComponent, reactive, watch } from 'vue'
import { createMemoryHistory, createRouter } from 'vue-router'
import { afterEach, describe, expect, it, vi } from 'vitest'

import { ApiProblem } from '@/types/api'

import AdminShell from './AdminShell.vue'

const navigation = [
  { name: 'dashboard', label: '仪表盘', path: '/admin/dashboard' },
  { name: 'site', label: '站点内容', path: '/admin/site' },
  { name: 'projects', label: '项目', path: '/admin/projects' },
  { name: 'media', label: '媒体库', path: '/admin/media' },
  { name: 'messages', label: '留言', path: '/admin/messages' },
  { name: 'analytics', label: '访问统计', path: '/admin/analytics' },
  { name: 'settings', label: '设置', path: '/admin/settings' },
] as const

const mountedWrappers: Array<{ unmount(): void }> = []

afterEach(() => {
  for (const wrapper of mountedWrappers.splice(0)) wrapper.unmount()
})

async function mountShell(onLogout: () => Promise<void>, username = 'yi-jiaxuan') {
  const RouteStub = defineComponent({
    template: '<section aria-label="当前管理页面" data-route-content />',
  })
  const router = createRouter({
    history: createMemoryHistory('/'),
    routes: [
      { path: '/admin/login', name: 'login', component: RouteStub },
      ...navigation.map(({ name, path }) => ({ name, path, component: RouteStub })),
      { path: '/admin/projects/new', name: 'project-new', component: RouteStub },
      { path: '/admin/projects/:projectId', name: 'project-edit', component: RouteStub },
      {
        path: '/admin/publishing/:aggregateType/:aggregateId/history',
        name: 'publishing-history',
        component: RouteStub,
      },
    ],
  })
  await router.push({ name: 'dashboard' })
  await router.isReady()

  const wrapper = mount(AdminShell, {
    props: { username, onLogout },
    global: { plugins: [router] },
  })
  mountedWrappers.push(wrapper)
  await flushPromises()
  return { router, wrapper }
}

describe('AdminShell', () => {
  it('links every approved named administration destination', async () => {
    const { wrapper } = await mountShell(async () => undefined)
    const links = wrapper.get('nav[aria-label="后台导航"]').findAll('a')

    expect(links.map((link) => link.text())).toEqual(navigation.map(({ label }) => label))
    expect(links.map((link) => link.attributes('href'))).toEqual(
      navigation.map(({ path }) => path),
    )
  })

  it('provides one main landmark, a first-focus skip link, and the administrator identity', async () => {
    const { wrapper } = await mountShell(async () => undefined, 'yychainsaw-admin')
    const allLinks = wrapper.findAll('a')
    const skipLink = wrapper.get('a[href="#admin-main"]')
    const main = wrapper.get('main#admin-main')

    expect(allLinks[0]?.element).toBe(skipLink.element)
    expect(skipLink.text()).toBe('跳到主要内容')
    expect(wrapper.findAll('main')).toHaveLength(1)
    expect(main.attributes('tabindex')).toBe('-1')
    expect(main.get('[data-route-content]').attributes('aria-label')).toBe('当前管理页面')
    expect(wrapper.get('aside').text()).toContain('yychainsaw-admin')
    expect(wrapper.get('button[type="button"]').text()).toBe('安全退出')
  })

  it('marks project and publication sub-workspaces as their owning navigation area', async () => {
    const { router, wrapper } = await mountShell(async () => undefined)
    const linkFor = (label: string) =>
      wrapper
        .get('nav[aria-label="后台导航"]')
        .findAll('a')
        .find((link) => link.text() === label)

    await router.push({ name: 'project-edit', params: { projectId: 'p1' } })
    await flushPromises()
    expect(linkFor('项目')?.attributes('aria-current')).toBe('page')

    await router.push({
      name: 'publishing-history',
      params: { aggregateType: 'SITE', aggregateId: 'site' },
    })
    await flushPromises()
    expect(linkFor('站点内容')?.attributes('aria-current')).toBe('page')
    expect(linkFor('项目')?.attributes('aria-current')).toBeUndefined()

    await router.push({
      name: 'publishing-history',
      params: { aggregateType: 'PROJECT', aggregateId: 'p1' },
    })
    await flushPromises()
    expect(linkFor('项目')?.attributes('aria-current')).toBe('page')
  })

  it('submits logout once while pending and navigates to login only after success', async () => {
    let resolveLogout!: () => void
    const onLogout = vi.fn(
      () =>
        new Promise<void>((resolve) => {
          resolveLogout = resolve
        }),
    )
    const { router, wrapper } = await mountShell(onLogout)
    const logout = wrapper.get('button[type="button"]')

    await logout.trigger('click')
    await logout.trigger('click')

    expect(onLogout).toHaveBeenCalledOnce()
    expect(logout.attributes('disabled')).toBeDefined()
    expect(router.currentRoute.value.name).toBe('dashboard')

    resolveLogout()
    await flushPromises()

    expect(router.currentRoute.value.name).toBe('login')
    expect(logout.attributes('disabled')).toBeUndefined()
  })

  it('renders only a safe logout error and does not navigate after failure', async () => {
    const failure = new ApiProblem({
      type: 'service_unavailable',
      title: '<img src=x onerror=alert(1)>退出失败，请重试',
      status: 503,
      code: 'LOGOUT_FAILED',
      traceId: 'logout-trace',
    })
    failure.stack = 'session id, database password, and filesystem path must stay hidden'
    const onLogout = vi.fn().mockRejectedValue(failure)
    const { router, wrapper } = await mountShell(onLogout)
    const replace = vi.spyOn(router, 'replace')

    await wrapper.get('button[type="button"]').trigger('click')
    await flushPromises()

    const alert = wrapper.get('[role="alert"]')
    expect(onLogout).toHaveBeenCalledOnce()
    expect(router.currentRoute.value.name).toBe('dashboard')
    expect(replace).not.toHaveBeenCalled()
    expect(alert.text()).toContain('退出失败，请重试')
    expect(alert.text()).toContain('logout-trace')
    expect(alert.text()).not.toContain('LOGOUT_FAILED')
    expect(alert.text()).not.toContain('session id')
    expect(alert.find('img').exists()).toBe(false)
    expect(wrapper.get('button[type="button"]').attributes('disabled')).toBeUndefined()
  })

  it('removes protected content before retrying a failed post-logout navigation', async () => {
    const onLogout = vi.fn().mockResolvedValue(undefined)
    const { router, wrapper } = await mountShell(onLogout)
    const originalReplace = router.replace.bind(router)
    const replace = vi.spyOn(router, 'replace')
    replace.mockRejectedValueOnce(new Error('login chunk unavailable'))
    replace.mockImplementation(originalReplace)

    await wrapper.get('button[type="button"]').trigger('click')
    await flushPromises()

    expect(onLogout).toHaveBeenCalledOnce()
    expect(router.currentRoute.value.name).toBe('dashboard')
    expect(wrapper.find('nav[aria-label="后台导航"]').exists()).toBe(false)
    expect(wrapper.find('[data-route-content]').exists()).toBe(false)
    expect(wrapper.get('main#admin-main').text()).toContain('已安全退出')
    expect(wrapper.get('[role="alert"]').text()).toContain(
      '已安全退出，但暂时无法返回登录页，请重试',
    )
    expect(wrapper.get('[role="alert"]').text()).not.toContain('login chunk unavailable')
    expect(wrapper.get('button[type="button"]').text()).toBe('返回登录页')

    await wrapper.get('button[type="button"]').trigger('click')
    await flushPromises()

    expect(onLogout).toHaveBeenCalledOnce()
    expect(router.currentRoute.value.name).toBe('login')
  })

  it('settles concurrent shell and session-expiry navigation after production-style logout', async () => {
    const state = reactive({ phase: 'AUTHENTICATED' })
    const onLogout = vi.fn(async () => {
      state.phase = 'ANONYMOUS'
    })
    const { router, wrapper } = await mountShell(onLogout)
    const replace = vi.spyOn(router, 'replace')
    const stop = watch(
      () => state.phase,
      (phase) => {
        if (phase === 'ANONYMOUS') {
          void router.replace({ name: 'login' }).catch(() => undefined)
        }
      },
      { flush: 'sync' },
    )

    try {
      await wrapper.get('button[type="button"]').trigger('click')
      await flushPromises()

      expect(onLogout).toHaveBeenCalledOnce()
      expect(replace).toHaveBeenCalled()
      expect(router.currentRoute.value.name).toBe('login')
      expect(wrapper.find('[data-route-content]').exists()).toBe(false)
      expect(wrapper.find('[role="alert"]').exists()).toBe(false)
    } finally {
      stop()
    }
  })
})
