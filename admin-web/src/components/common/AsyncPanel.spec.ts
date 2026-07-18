import { flushPromises, mount } from '@vue/test-utils'
import { describe, expect, it, vi } from 'vitest'

import AsyncPanel from './AsyncPanel.vue'

describe('AsyncPanel', () => {
  it('renders loading, error, empty, and content as four mutually exclusive states', async () => {
    const wrapper = mount(AsyncPanel, {
      props: {
        loading: true,
        errorTitle: '加载失败',
        traceId: 'trace-state',
        empty: true,
        onRetry: vi.fn(),
      },
      slots: {
        empty: '<p data-test="empty-state">暂无内容</p>',
        default: '<article data-test="content-state">已加载内容</article>',
      },
    })

    const visibleStates = () => ({
      loading: wrapper.find('[role="status"]').exists(),
      error: wrapper.find('[role="alert"]').exists(),
      empty: wrapper.find('[data-test="empty-state"]').exists(),
      content: wrapper.find('[data-test="content-state"]').exists(),
    })

    expect(visibleStates()).toEqual({
      loading: true,
      error: false,
      empty: false,
      content: false,
    })
    const loading = wrapper.get('[role="status"]')
    expect(loading.text()).toContain('正在加载')
    expect(loading.attributes('aria-live')).toBe('polite')
    expect(wrapper.attributes('aria-busy')).toBe('true')

    await wrapper.setProps({ loading: false })
    expect(visibleStates()).toEqual({
      loading: false,
      error: true,
      empty: false,
      content: false,
    })

    await wrapper.setProps({ errorTitle: undefined, traceId: undefined })
    expect(visibleStates()).toEqual({
      loading: false,
      error: false,
      empty: true,
      content: false,
    })

    await wrapper.setProps({ empty: false })
    expect(visibleStates()).toEqual({
      loading: false,
      error: false,
      empty: false,
      content: true,
    })
    expect(wrapper.attributes('aria-busy')).toBe('false')
  })

  it('renders only the supplied safe error title and trace id', () => {
    const wrapper = mount(AsyncPanel, {
      props: {
        errorTitle: '<img src=x onerror=alert(1)>服务暂时不可用',
        traceId: 'safe-trace-42',
        onRetry: vi.fn(),
      },
      slots: {
        empty: '<p data-test="empty-state">不应出现的空状态</p>',
        default: '<p data-test="content-state">INTERNAL_STACK_AND_SQL</p>',
      },
    })

    const alert = wrapper.get('[role="alert"]')
    expect(alert.text()).toContain('服务暂时不可用')
    expect(alert.text()).toContain('请求编号：safe-trace-42')
    expect(alert.find('img').exists()).toBe(false)
    expect(wrapper.text()).not.toContain('INTERNAL_STACK_AND_SQL')
    expect(wrapper.find('[data-test="empty-state"]').exists()).toBe(false)

    const retry = wrapper.get('button')
    expect(retry.attributes('type')).toBe('button')
    expect(retry.text()).toContain('重试')
  })

  it('disables retry while it is running and prevents duplicate requests', async () => {
    let resolveRetry!: () => void
    const onRetry = vi.fn(
      () =>
        new Promise<void>((resolve) => {
          resolveRetry = resolve
        }),
    )
    const wrapper = mount(AsyncPanel, {
      props: {
        errorTitle: '加载失败',
        traceId: 'retry-trace',
        onRetry,
      },
    })

    await wrapper.get('button').trigger('click')
    await wrapper.get('button').trigger('click')

    expect(onRetry).toHaveBeenCalledOnce()
    expect(wrapper.get('button').attributes('disabled')).toBeDefined()
    expect(wrapper.attributes('aria-busy')).toBe('true')
    expect(wrapper.get('[role="alert"]').text()).toContain('加载失败')

    resolveRetry()
    await flushPromises()

    expect(wrapper.get('button').attributes('disabled')).toBeUndefined()
    expect(wrapper.attributes('aria-busy')).toBe('false')
  })

  it('uses the named empty slot and the default content slot in their owning states', async () => {
    const wrapper = mount(AsyncPanel, {
      props: { empty: true },
      slots: {
        empty: '<p data-test="empty-state">还没有可显示的内容</p>',
        default: '<article data-test="content-state">管理内容</article>',
      },
    })

    expect(wrapper.get('[data-test="empty-state"]').text()).toBe('还没有可显示的内容')
    expect(wrapper.find('[data-test="content-state"]').exists()).toBe(false)
    expect(wrapper.find('[role="status"]').exists()).toBe(false)
    expect(wrapper.find('[role="alert"]').exists()).toBe(false)

    await wrapper.setProps({ empty: false })

    expect(wrapper.get('[data-test="content-state"]').text()).toBe('管理内容')
    expect(wrapper.find('[data-test="empty-state"]').exists()).toBe(false)
    expect(wrapper.find('[role="status"]').exists()).toBe(false)
    expect(wrapper.find('[role="alert"]').exists()).toBe(false)
  })

  it('retains the safe error and unlocks retry after a rejected callback', async () => {
    const onRetry = vi.fn().mockRejectedValue(new Error('internal retry detail'))
    const wrapper = mount(AsyncPanel, {
      props: {
        errorTitle: '仍然无法加载',
        traceId: 'retry-rejected',
        onRetry,
      },
    })

    await wrapper.get('button').trigger('click')
    await flushPromises()

    expect(onRetry).toHaveBeenCalledOnce()
    expect(wrapper.get('[role="alert"]').text()).toContain('仍然无法加载')
    expect(wrapper.get('[role="alert"]').text()).toContain('retry-rejected')
    expect(wrapper.get('[role="alert"]').text()).not.toContain('internal retry detail')
    expect(wrapper.get('button').attributes('disabled')).toBeUndefined()
    expect(wrapper.attributes('aria-busy')).toBe('false')
  })

  it('settles a pending retry after unmount without warnings or duplicate calls', async () => {
    let resolveRetry!: () => void
    const onRetry = vi.fn(
      () =>
        new Promise<void>((resolve) => {
          resolveRetry = resolve
        }),
    )
    const warn = vi.spyOn(console, 'warn').mockImplementation(() => undefined)
    const error = vi.spyOn(console, 'error').mockImplementation(() => undefined)
    const wrapper = mount(AsyncPanel, {
      props: {
        errorTitle: '加载失败',
        traceId: 'retry-unmount',
        onRetry,
      },
    })

    try {
      await wrapper.get('button').trigger('click')
      wrapper.unmount()
      resolveRetry()
      await flushPromises()

      expect(onRetry).toHaveBeenCalledOnce()
      expect(warn).not.toHaveBeenCalled()
      expect(error).not.toHaveBeenCalled()
    } finally {
      warn.mockRestore()
      error.mockRestore()
    }
  })
})
