import { flushPromises, mount } from '@vue/test-utils'
import { defineComponent, reactive } from 'vue'
import { createMemoryHistory, createRouter } from 'vue-router'
import { describe, expect, it, vi } from 'vitest'

import { ApiProblem } from '@/types/api'

import LoginView from './LoginView.vue'

describe('LoginView', () => {
  it('uses password-manager hints, blocks duplicate submits, and clears the password', async () => {
    let resolveLogin!: () => void
    const login = vi.fn().mockReturnValue(
      new Promise<void>((resolve) => {
        resolveLogin = resolve
      }),
    )
    const onChallenge = vi.fn().mockResolvedValue(undefined)
    const wrapper = mount(LoginView, { props: { session: { login }, onChallenge } })

    const username = wrapper.get<HTMLInputElement>('input[autocomplete="username"]')
    const password = wrapper.get<HTMLInputElement>('input[autocomplete="current-password"]')
    await username.setValue('admin')
    await password.setValue('secret')
    await wrapper.get('form').trigger('submit')
    await wrapper.get('form').trigger('submit')

    expect(login).toHaveBeenCalledOnce()
    expect(login).toHaveBeenCalledWith('admin', 'secret')
    expect(wrapper.get('button[type="submit"]').attributes('disabled')).toBeDefined()

    resolveLogin()
    await flushPromises()

    expect(onChallenge).toHaveBeenCalledOnce()
    expect(password.element.value).toBe('')
    expect(wrapper.get('form').attributes('aria-busy')).toBe('false')
  })

  it('renders only the safe problem title and trace id', async () => {
    const problem = new ApiProblem({
      type: 'unauthorized',
      title: '<img src=x onerror=alert(1)>用户名或密码错误',
      status: 401,
      code: 'AUTHENTICATION_FAILED',
      traceId: 'safe-trace',
    })
    problem.stack = 'postgres password and filesystem path must stay hidden'
    const wrapper = mount(LoginView, {
      props: {
        session: { login: vi.fn().mockRejectedValue(problem) },
        onChallenge: vi.fn(),
      },
    })
    const password = wrapper.get<HTMLInputElement>('input[autocomplete="current-password"]')
    await wrapper.get('input[autocomplete="username"]').setValue('admin')
    await password.setValue('wrong-secret')

    await wrapper.get('form').trigger('submit')
    await flushPromises()

    const alert = wrapper.get('[role="alert"]')
    expect(alert.text()).toContain('用户名或密码错误')
    expect(alert.text()).toContain('safe-trace')
    expect(alert.text()).not.toContain('AUTHENTICATION_FAILED')
    expect(alert.text()).not.toContain('postgres')
    expect(alert.find('img').exists()).toBe(false)
    expect(password.element.value).toBe('')
  })

  it('preserves a sanitized deep link in the default TOTP navigation', async () => {
    const Stub = defineComponent({ template: '<div />' })
    const router = createRouter({
      history: createMemoryHistory('/'),
      routes: [
        { path: '/admin/login', name: 'login', component: Stub },
        { path: '/admin/totp', name: 'totp', component: Stub },
      ],
    })
    await router.push('/admin/login?redirect=/admin/projects/p1?locale=en%23summary')
    await router.isReady()
    const wrapper = mount(LoginView, {
      props: { session: { login: vi.fn().mockResolvedValue(undefined) } },
      global: { plugins: [router] },
    })
    await wrapper.get('input[autocomplete="username"]').setValue('admin')
    await wrapper.get('input[autocomplete="current-password"]').setValue('secret')

    await wrapper.get('form').trigger('submit')
    await flushPromises()

    expect(router.currentRoute.value.name).toBe('totp')
    expect(router.currentRoute.value.query.redirect).toBe(
      '/admin/projects/p1?locale=en#summary',
    )
  })

  it('retries navigation without submitting the password twice', async () => {
    const login = vi.fn().mockResolvedValue(undefined)
    const onChallenge = vi
      .fn()
      .mockRejectedValueOnce(new Error('lazy route failed'))
      .mockResolvedValueOnce(undefined)
    const wrapper = mount(LoginView, { props: { session: { login }, onChallenge } })
    const password = wrapper.get<HTMLInputElement>('input[autocomplete="current-password"]')
    await wrapper.get('input[autocomplete="username"]').setValue('admin')
    await password.setValue('secret')

    await wrapper.get('form').trigger('submit')
    await flushPromises()

    expect(login).toHaveBeenCalledOnce()
    expect(onChallenge).toHaveBeenCalledOnce()
    expect(password.element.value).toBe('')
    expect(wrapper.get('[role="alert"]').text()).toContain(
      '密码验证已完成，但暂时无法进入二次验证，请重试',
    )

    await wrapper.get('form').trigger('submit')
    await flushPromises()

    expect(login).toHaveBeenCalledOnce()
    expect(onChallenge).toHaveBeenCalledTimes(2)
  })

  it('unlocks the credentials when a stranded second-factor challenge expires', async () => {
    vi.useFakeTimers({ toFake: ['Date', 'setTimeout', 'clearTimeout'] })
    vi.setSystemTime(new Date('2026-07-18T00:00:00Z'))
    const state = reactive({
      phase: 'ANONYMOUS',
      secondFactorExpiresAt: null as string | null,
    })
    const login = vi.fn(async () => {
      state.phase = 'TOTP_REQUIRED'
      state.secondFactorExpiresAt = '2026-07-18T00:00:01Z'
    })
    const Stub = defineComponent({ template: '<div />' })
    const router = createRouter({
      history: createMemoryHistory('/'),
      routes: [
        { path: '/admin/login', name: 'login', component: Stub },
        {
          path: '/admin/totp',
          name: 'totp',
          component: () => Promise.reject(new Error('TOTP chunk unavailable')),
        },
      ],
    })
    const removeRouterErrorHandler = router.onError(() => undefined)
    await router.push('/admin/login?redirect=/admin/site')
    await router.isReady()
    const wrapper = mount(LoginView, {
      props: { session: { state, login } },
      global: { plugins: [router] },
    })

    try {
      await wrapper.get('input[autocomplete="username"]').setValue('admin')
      await wrapper.get('input[autocomplete="current-password"]').setValue('secret')
      await wrapper.get('form').trigger('submit')
      await flushPromises()

      expect(login).toHaveBeenCalledOnce()
      expect(wrapper.get('[role="alert"]').text()).toContain(
        '密码验证已完成，但暂时无法进入二次验证，请重试',
      )
      expect(
        wrapper.get('input[autocomplete="current-password"]').attributes('disabled'),
      ).toBeDefined()

      await vi.advanceTimersByTimeAsync(1_000)
      await flushPromises()

      expect(wrapper.get('[role="alert"]').text()).toContain('二次验证已失效，请重新输入密码')
      expect(
        wrapper.get('input[autocomplete="current-password"]').attributes('disabled'),
      ).toBeUndefined()
      expect(wrapper.get('input[autocomplete="username"]').attributes('disabled')).toBeUndefined()
      expect(login).toHaveBeenCalledOnce()
    } finally {
      wrapper.unmount()
      removeRouterErrorHandler()
      vi.useRealTimers()
    }
  })
})
