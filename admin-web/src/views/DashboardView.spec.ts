import { flushPromises, mount } from '@vue/test-utils'
import { defineComponent, h, type PropType } from 'vue'
import { beforeEach, describe, expect, it, vi } from 'vitest'

import DashboardView from './DashboardView.vue'

const httpCalls = vi.hoisted(() => ({
  request: vi.fn().mockResolvedValue({ data: {} }),
  get: vi.fn().mockResolvedValue({ data: {} }),
  post: vi.fn().mockResolvedValue({ data: {} }),
  put: vi.fn().mockResolvedValue({ data: {} }),
  patch: vi.fn().mockResolvedValue({ data: {} }),
  delete: vi.fn().mockResolvedValue({ data: {} }),
}))

const sessionCalls = vi.hoisted(() => ({
  bootstrap: vi.fn().mockResolvedValue('AUTHENTICATED'),
  login: vi.fn().mockResolvedValue(undefined),
  verifySecondFactor: vi.fn().mockResolvedValue(undefined),
  logout: vi.fn().mockResolvedValue(undefined),
  invalidate: vi.fn(),
}))

vi.mock('@/api/http', () => ({
  http: Object.assign(httpCalls.request, {
    get: httpCalls.get,
    post: httpCalls.post,
    put: httpCalls.put,
    patch: httpCalls.patch,
    delete: httpCalls.delete,
  }),
}))

vi.mock('@/stores/sessionInstance', () => ({
  sessionStore: {
    state: {
      phase: 'AUTHENTICATED',
      user: { id: '47c9505c-8134-4fd1-aa0c-32d15c52dba5', username: 'admin' },
      secondFactorExpiresAt: null,
    },
    ...sessionCalls,
  },
}))

const destinations = [
  { title: '站点内容', route: 'site', path: '/admin/site' },
  { title: '项目', route: 'projects', path: '/admin/projects' },
  { title: '媒体库', route: 'media', path: '/admin/media' },
  { title: '留言', route: 'messages', path: '/admin/messages' },
  { title: '访问统计', route: 'analytics', path: '/admin/analytics' },
  { title: '安全与运维', route: 'settings', path: '/admin/settings' },
] as const

type LinkTarget = string | { readonly name?: unknown }

const RouterLinkProbe = defineComponent({
  name: 'RouterLink',
  inheritAttrs: false,
  props: {
    to: { type: [String, Object] as PropType<LinkTarget>, required: true },
  },
  setup(props, { attrs, slots }) {
    return () => {
      const routeName =
        typeof props.to === 'object' && typeof props.to.name === 'string' ? props.to.name : ''
      const href = destinations.find(({ route }) => route === routeName)?.path ?? '#invalid-route'
      return h(
        'a',
        { ...attrs, href, 'data-route-name': routeName },
        slots.default?.(),
      )
    }
  },
})

function mountDashboard() {
  return mount(DashboardView, {
    global: { stubs: { RouterLink: RouterLinkProbe } },
  })
}

type DashboardWrapper = ReturnType<typeof mountDashboard>

function cardFor(wrapper: DashboardWrapper, title: string) {
  return wrapper
    .findAll('article')
    .find((card) => card.find('h2').exists() && card.get('h2').text() === title)
}

describe('DashboardView', () => {
  beforeEach(() => vi.clearAllMocks())

  it('renders six semantic administration cards under one page title', () => {
    const wrapper = mountDashboard()

    expect(wrapper.findAll('h1')).toHaveLength(1)
    expect(wrapper.get('h1').text()).toBe('仪表盘')
    expect(wrapper.findAll('article')).toHaveLength(6)

    for (const destination of destinations) {
      const card = cardFor(wrapper, destination.title)
      expect(card, `${destination.title} card`).toBeDefined()
      if (card === undefined) throw new Error(`${destination.title} card is missing`)
      const description = card.findAll('p').map((paragraph) => paragraph.text().trim()).join(' ')
      expect(description).toBeTruthy()
      expect(description.length).toBeLessThanOrEqual(120)
      expect(card.findAll('a')).toHaveLength(1)
    }

    wrapper.unmount()
  })

  it('uses the exact named routes through native keyboard-reachable links', () => {
    const wrapper = mountDashboard()

    for (const destination of destinations) {
      const card = cardFor(wrapper, destination.title)
      expect(card, `${destination.title} card`).toBeDefined()
      if (card === undefined) throw new Error(`${destination.title} card is missing`)
      const link = card.get<HTMLAnchorElement>('a')

      expect(link.element.tagName).toBe('A')
      expect(link.attributes('data-route-name')).toBe(destination.route)
      expect(link.attributes('href')).toBe(destination.path)
      expect(link.attributes('tabindex')).not.toBe('-1')
      expect(link.attributes('aria-disabled')).not.toBe('true')
      expect(link.text().trim()).not.toBe('')
    }

    wrapper.unmount()
  })

  it('makes no API, HTTP, or session request and renders no fabricated metrics', async () => {
    const fetchCall = vi.fn().mockResolvedValue({})
    vi.stubGlobal('fetch', fetchCall)
    const xhrSend = vi.spyOn(XMLHttpRequest.prototype, 'send').mockImplementation(() => undefined)
    const wrapper = mountDashboard()

    try {
      await flushPromises()

      for (const call of Object.values(httpCalls)) expect(call).not.toHaveBeenCalled()
      for (const call of Object.values(sessionCalls)) expect(call).not.toHaveBeenCalled()
      expect(fetchCall).not.toHaveBeenCalled()
      expect(xhrSend).not.toHaveBeenCalled()

      expect(wrapper.find('[data-dashboard-metric], [data-kpi], [data-summary-count]').exists()).toBe(
        false,
      )
      expect(wrapper.findAll('data, meter, progress, output')).toHaveLength(0)
      expect(wrapper.text()).not.toMatch(
        /(?:今日访问|本周访问|本月访问|总访问量|未读留言|待处理留言|审计事件|维护任务|活跃会话|发布成功率|系统可用率)\s*[:：]?\s*\d+(?:\.\d+)?%?/u,
      )
      expect(wrapper.text()).not.toMatch(/系统(?:正常|异常)|服务(?:正常|异常)|存储(?:正常|告警)/u)
    } finally {
      wrapper.unmount()
      xhrSend.mockRestore()
      vi.unstubAllGlobals()
    }
  })
})
