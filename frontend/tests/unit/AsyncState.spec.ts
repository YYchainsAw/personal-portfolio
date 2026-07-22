import { mount } from '@vue/test-utils'
import { describe, expect, it } from 'vitest'
import AsyncState from '@/components/common/AsyncState.vue'

describe('AsyncState', () => {
  it('announces loading without exposing the error action', () => {
    const wrapper = mount(AsyncState, {
      props: { locale: 'en', state: 'loading' },
      slots: { default: '<p data-ready>Ready content</p>' },
    })

    expect(wrapper.get('[role="status"]').text()).toContain('Loading published content')
    expect(wrapper.find('[role="alert"]').exists()).toBe(false)
    expect(wrapper.find('[data-ready]').exists()).toBe(false)
  })

  it('keeps the trace ID inside the alert and emits an explicit retry', async () => {
    const wrapper = mount(AsyncState, {
      props: { locale: 'en', state: 'error', traceId: 'synthetic-500' },
    })

    expect(wrapper.get('[role="alert"]').text()).toContain('synthetic-500')
    await wrapper.get('button').trigger('click')
    expect(wrapper.emitted('retry')).toHaveLength(1)
  })

  it('never presents the client fallback as a server trace ID', () => {
    const wrapper = mount(AsyncState, {
      props: { locale: 'zh-CN', state: 'error', traceId: 'client' },
    })

    expect(wrapper.get('[role="alert"]').text()).toContain('暂时无法加载此内容')
    expect(wrapper.text()).not.toContain('Trace ID')
  })

  it('renders only the ready slot after data is available', () => {
    const wrapper = mount(AsyncState, {
      props: { locale: 'en', state: 'ready' },
      slots: { default: '<article data-ready>Ready content</article>' },
    })

    expect(wrapper.get('[data-ready]').text()).toBe('Ready content')
    expect(wrapper.find('#main-content').exists()).toBe(false)
  })
})
