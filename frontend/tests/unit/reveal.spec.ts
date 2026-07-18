import { mount } from '@vue/test-utils'
import { expect, it, vi } from 'vitest'
import { revealDirective } from '@/directives/reveal'

it('observes content inserted asynchronously and disconnects on removal', async () => {
  const observe = vi.fn(), disconnect = vi.fn()
  class ObserverMock { observe = observe; disconnect = disconnect }
  vi.stubGlobal('IntersectionObserver', ObserverMock)
  const wrapper = mount({ data: () => ({ shown: false }), template: '<div><p v-if="shown" v-reveal>late</p></div>' }, { global: { directives: { reveal: revealDirective } } })
  await wrapper.setData({ shown: true }); expect(observe).toHaveBeenCalledWith(wrapper.get('p').element)
  await wrapper.setData({ shown: false }); expect(disconnect).toHaveBeenCalled()
})

it('shows content immediately for reduced motion without an observer', () => {
  vi.stubGlobal('matchMedia', () => ({ matches: true }))
  const observer = vi.fn(); vi.stubGlobal('IntersectionObserver', observer)
  const wrapper = mount({ template: '<p v-reveal>visible</p>' }, { global: { directives: { reveal: revealDirective } } })
  expect(wrapper.get('p').classes()).toContain('is-visible'); expect(observer).not.toHaveBeenCalled()
})
