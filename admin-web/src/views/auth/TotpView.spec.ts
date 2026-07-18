import { flushPromises, mount } from '@vue/test-utils'
import { defineComponent } from 'vue'
import { createMemoryHistory, createRouter } from 'vue-router'
import { describe, expect, it, vi } from 'vitest'

import { ApiProblem } from '@/types/api'

import TotpView from './TotpView.vue'

describe('TotpView', () => {
  it('submits exactly six TOTP digits and clears the code', async () => {
    const verifySecondFactor = vi.fn().mockResolvedValue(undefined)
    const onAuthenticated = vi.fn().mockResolvedValue(undefined)
    const onRestart = vi.fn().mockResolvedValue(undefined)
    const wrapper = mount(TotpView, {
      props: {
        session: { verifySecondFactor, invalidate: vi.fn() },
        onAuthenticated,
        onRestart,
      },
    })
    const code = wrapper.get<HTMLInputElement>('input[autocomplete="one-time-code"]')

    expect(code.attributes('type')).toBe('text')
    expect(code.attributes('inputmode')).toBe('numeric')
    expect(code.attributes('maxlength')).toBe('6')
    await code.setValue('012345')
    await wrapper.get('form').trigger('submit')
    await flushPromises()

    expect(verifySecondFactor).toHaveBeenCalledWith('TOTP', '012345')
    expect(onAuthenticated).toHaveBeenCalledOnce()
    expect(code.element.value).toBe('')
  })

  it('rejects malformed TOTP locally', async () => {
    const verifySecondFactor = vi.fn()
    const wrapper = mount(TotpView, {
      props: {
        session: { verifySecondFactor, invalidate: vi.fn() },
        onAuthenticated: vi.fn(),
        onRestart: vi.fn(),
      },
    })

    await wrapper.get('input[autocomplete="one-time-code"]').setValue('12a456')
    await wrapper.get('form').trigger('submit')

    expect(verifySecondFactor).not.toHaveBeenCalled()
    expect(wrapper.get('[role="alert"]').text()).toContain('请输入 6 位数字验证码')

    await wrapper.get('input[autocomplete="one-time-code"]').setValue(' 123456 ')
    await wrapper.get('form').trigger('submit')

    expect(verifySecondFactor).not.toHaveBeenCalled()
  })

  it('rejects ambiguous recovery-code characters locally', async () => {
    const verifySecondFactor = vi.fn()
    const wrapper = mount(TotpView, {
      props: {
        session: { verifySecondFactor, invalidate: vi.fn() },
        onAuthenticated: vi.fn(),
        onRestart: vi.fn(),
      },
    })

    await wrapper.get('[data-recovery-mode]').trigger('click')
    await wrapper.get('input[autocomplete="off"]').setValue('ABCI-EFGH-JK10')
    await wrapper.get('form').trigger('submit')

    expect(verifySecondFactor).not.toHaveBeenCalled()
    expect(wrapper.get('[role="alert"]').text()).toContain('恢复码格式不正确')
  })

  it('normalizes and submits the exact recovery-code format', async () => {
    const verifySecondFactor = vi.fn().mockResolvedValue(undefined)
    const wrapper = mount(TotpView, {
      props: {
        session: { verifySecondFactor, invalidate: vi.fn() },
        onAuthenticated: vi.fn(),
        onRestart: vi.fn(),
      },
    })

    await wrapper.get('[data-recovery-mode]').trigger('click')
    const code = wrapper.get<HTMLInputElement>('input[autocomplete="off"]')
    expect(code.attributes('maxlength')).toBe('32')
    await code.setValue(' abcd-efgh-jklm ')
    await wrapper.get('form').trigger('submit')
    await flushPromises()

    expect(verifySecondFactor).toHaveBeenCalledWith('RECOVERY_CODE', 'ABCD-EFGH-JKLM')
    expect(code.element.value).toBe('')
  })

  it('blocks duplicate verification and renders only safe server errors', async () => {
    let rejectVerification!: (reason: unknown) => void
    const verifySecondFactor = vi.fn().mockReturnValue(
      new Promise<void>((_resolve, reject) => {
        rejectVerification = reject
      }),
    )
    const wrapper = mount(TotpView, {
      props: {
        session: { verifySecondFactor, invalidate: vi.fn() },
        onAuthenticated: vi.fn(),
        onRestart: vi.fn(),
      },
    })
    const code = wrapper.get<HTMLInputElement>('input[autocomplete="one-time-code"]')
    await code.setValue('123456')
    await wrapper.get('form').trigger('submit')
    await wrapper.get('form').trigger('submit')
    expect(verifySecondFactor).toHaveBeenCalledOnce()

    const problem = new ApiProblem({
      type: 'unauthorized',
      title: '验证码错误或已失效',
      status: 401,
      code: 'AUTHENTICATION_FAILED',
      traceId: 'factor-trace',
    })
    problem.stack = 'TOTP secret must stay hidden'
    rejectVerification(problem)
    await flushPromises()

    const alert = wrapper.get('[role="alert"]')
    expect(alert.text()).toContain('验证码错误或已失效')
    expect(alert.text()).toContain('factor-trace')
    expect(alert.text()).not.toContain('AUTHENTICATION_FAILED')
    expect(alert.text()).not.toContain('TOTP secret')
    expect(code.element.value).toBe('')
  })

  it('invalidates an expired challenge without sending a verification request', async () => {
    const verifySecondFactor = vi.fn()
    const invalidate = vi.fn()
    const wrapper = mount(TotpView, {
      props: {
        session: {
          state: {
            phase: 'TOTP_REQUIRED',
            secondFactorExpiresAt: '2000-01-01T00:00:00Z',
          },
          verifySecondFactor,
          invalidate,
        },
        onAuthenticated: vi.fn(),
        onRestart: vi.fn(),
      },
    })
    await wrapper.get('input[autocomplete="one-time-code"]').setValue('123456')

    await wrapper.get('form').trigger('submit')

    expect(verifySecondFactor).not.toHaveBeenCalled()
    expect(invalidate).toHaveBeenCalledOnce()
    expect(wrapper.get('[role="alert"]').text()).toContain('二次验证已失效')
    expect(wrapper.get('[data-recovery-mode]').attributes('disabled')).toBeDefined()
    await wrapper.get('[data-recovery-mode]').trigger('click')
    expect(wrapper.get('[role="alert"]').text()).toContain('二次验证已失效')
  })

  it('returns to the sanitized deep link after default verification', async () => {
    const Stub = defineComponent({ template: '<div />' })
    const router = createRouter({
      history: createMemoryHistory('/'),
      routes: [
        { path: '/admin/totp', name: 'totp', component: Stub },
        { path: '/admin/dashboard', name: 'dashboard', component: Stub },
        { path: '/admin/projects/:projectId', name: 'project-edit', component: Stub },
      ],
    })
    await router.push('/admin/totp?redirect=/admin/projects/p1?locale=en%23summary')
    await router.isReady()
    const wrapper = mount(TotpView, {
      props: {
        session: {
          verifySecondFactor: vi.fn().mockResolvedValue(undefined),
          invalidate: vi.fn(),
        },
      },
      global: { plugins: [router] },
    })
    await wrapper.get('input[autocomplete="one-time-code"]').setValue('012345')

    await wrapper.get('form').trigger('submit')
    await flushPromises()

    expect(router.currentRoute.value.fullPath).toBe(
      '/admin/projects/p1?locale=en#summary',
    )
  })

  it('retries post-authentication navigation without verifying twice', async () => {
    const verifySecondFactor = vi.fn().mockResolvedValue(undefined)
    const invalidate = vi.fn()
    const onAuthenticated = vi
      .fn()
      .mockRejectedValueOnce(new Error('lazy route failed'))
      .mockResolvedValueOnce(undefined)
    const wrapper = mount(TotpView, {
      props: {
        session: { verifySecondFactor, invalidate },
        onAuthenticated,
        onRestart: vi.fn(),
      },
    })
    const code = wrapper.get<HTMLInputElement>('input[autocomplete="one-time-code"]')
    await code.setValue('012345')

    await wrapper.get('form').trigger('submit')
    await flushPromises()

    expect(verifySecondFactor).toHaveBeenCalledOnce()
    expect(onAuthenticated).toHaveBeenCalledOnce()
    expect(code.element.value).toBe('')
    expect(invalidate).not.toHaveBeenCalled()
    expect(wrapper.find('[data-restart-login]').exists()).toBe(false)
    expect(wrapper.get('[role="alert"]').text()).toContain(
      '验证已完成，但暂时无法进入后台，请重试',
    )

    await wrapper.get('form').trigger('submit')
    await flushPromises()

    expect(verifySecondFactor).toHaveBeenCalledOnce()
    expect(onAuthenticated).toHaveBeenCalledTimes(2)
    expect(invalidate).not.toHaveBeenCalled()
  })

  it('does not invalidate a verification that succeeds at the expiry deadline', async () => {
    vi.useFakeTimers({ toFake: ['Date', 'setTimeout', 'clearTimeout'] })
    vi.setSystemTime(new Date('2026-07-18T00:00:00Z'))
    let resolveVerification!: () => void
    const verifySecondFactor = vi.fn().mockReturnValue(
      new Promise<void>((resolve) => {
        resolveVerification = resolve
      }),
    )
    const invalidate = vi.fn()
    const onAuthenticated = vi.fn().mockResolvedValue(undefined)
    const wrapper = mount(TotpView, {
      props: {
        session: {
          state: {
            phase: 'TOTP_REQUIRED',
            secondFactorExpiresAt: '2026-07-18T00:00:01Z',
          },
          verifySecondFactor,
          invalidate,
        },
        onAuthenticated,
        onRestart: vi.fn(),
      },
    })

    try {
      await wrapper.get('input[autocomplete="one-time-code"]').setValue('012345')
      await wrapper.get('form').trigger('submit')
      await vi.advanceTimersByTimeAsync(1_000)

      expect(invalidate).not.toHaveBeenCalled()
      expect(wrapper.get('[data-restart-login]').attributes('disabled')).toBeDefined()
      await wrapper.get('[data-restart-login]').trigger('click')
      expect(invalidate).not.toHaveBeenCalled()

      resolveVerification()
      await flushPromises()

      expect(onAuthenticated).toHaveBeenCalledOnce()
      expect(invalidate).not.toHaveBeenCalled()
    } finally {
      wrapper.unmount()
      vi.useRealTimers()
    }
  })

  it('invalidates a verification that fails after crossing the expiry deadline', async () => {
    vi.useFakeTimers({ toFake: ['Date', 'setTimeout', 'clearTimeout'] })
    vi.setSystemTime(new Date('2026-07-18T00:00:00Z'))
    let rejectVerification!: (cause: unknown) => void
    const verifySecondFactor = vi.fn().mockReturnValue(
      new Promise<void>((_resolve, reject) => {
        rejectVerification = reject
      }),
    )
    const invalidate = vi.fn()
    const onAuthenticated = vi.fn()
    const wrapper = mount(TotpView, {
      props: {
        session: {
          state: {
            phase: 'TOTP_REQUIRED',
            secondFactorExpiresAt: '2026-07-18T00:00:01Z',
          },
          verifySecondFactor,
          invalidate,
        },
        onAuthenticated,
        onRestart: vi.fn(),
      },
    })

    try {
      await wrapper.get('input[autocomplete="one-time-code"]').setValue('012345')
      await wrapper.get('form').trigger('submit')
      await vi.advanceTimersByTimeAsync(1_000)
      rejectVerification(
        new ApiProblem({
          type: 'unauthorized',
          title: '旧的服务端错误',
          status: 401,
          code: 'AUTHENTICATION_FAILED',
          traceId: 'expired-request',
        }),
      )
      await flushPromises()

      expect(invalidate).toHaveBeenCalledOnce()
      expect(onAuthenticated).not.toHaveBeenCalled()
      expect(wrapper.get('[role="alert"]').text()).toContain('二次验证已失效')
      expect(wrapper.get('[role="alert"]').text()).not.toContain('旧的服务端错误')
    } finally {
      wrapper.unmount()
      vi.useRealTimers()
    }
  })

  it('uses the default restart route when only authentication completion is injected', async () => {
    const Stub = defineComponent({ template: '<div />' })
    const router = createRouter({
      history: createMemoryHistory('/'),
      routes: [
        { path: '/admin/login', name: 'login', component: Stub },
        { path: '/admin/totp', name: 'totp', component: Stub },
      ],
    })
    await router.push('/admin/totp?redirect=/admin/site?locale=zh-CN%23content')
    await router.isReady()
    const invalidate = vi.fn()
    const wrapper = mount(TotpView, {
      props: {
        session: { verifySecondFactor: vi.fn(), invalidate },
        onAuthenticated: vi.fn(),
      },
      global: { plugins: [router] },
    })

    await wrapper.get('[data-restart-login]').trigger('click')
    await flushPromises()

    expect(invalidate).toHaveBeenCalledOnce()
    expect(router.currentRoute.value.name).toBe('login')
    expect(router.currentRoute.value.query.redirect).toBe('/admin/site?locale=zh-CN#content')
  })
})
