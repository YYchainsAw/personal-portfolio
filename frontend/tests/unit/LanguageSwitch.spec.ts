import { flushPromises, mount } from '@vue/test-utils'
import { createMemoryHistory } from 'vue-router'
import { expect, it } from 'vitest'
import LanguageSwitch from '@/components/common/LanguageSwitch.vue'
import { createPublicRouter } from '@/router'

it('preserves a project slug and exposes route-authoritative pressed state', async () => {
  const router = createPublicRouter(createMemoryHistory())
  await router.push('/zh-CN/projects/ue-study'); await router.isReady()
  const wrapper = mount(LanguageSwitch, { global: { plugins: [router] } })
  expect(wrapper.get('button[lang="zh-CN"]').attributes('aria-pressed')).toBe('true')
  await wrapper.get('button[lang="en"]').trigger('click'); await flushPromises()
  expect(router.currentRoute.value.fullPath).toBe('/en/projects/ue-study')
})

it('uses the supported path prefix on an English 404', async () => {
  const router = createPublicRouter(createMemoryHistory())
  await router.push('/en/bogus'); await router.isReady()
  const wrapper = mount(LanguageSwitch, { global: { plugins: [router] } })
  expect(wrapper.get('button[lang="en"]').attributes('aria-pressed')).toBe('true')
})

it('preserves the privacy route while switching in both directions', async () => {
  const router = createPublicRouter(createMemoryHistory())
  await router.push('/zh-CN/privacy')
  await router.isReady()
  const wrapper = mount(LanguageSwitch, { global: { plugins: [router] } })

  await wrapper.get('button[lang="en"]').trigger('click')
  await flushPromises()
  expect(router.currentRoute.value.fullPath).toBe('/en/privacy')

  await wrapper.get('button[lang="zh-CN"]').trigger('click')
  await flushPromises()
  expect(router.currentRoute.value.fullPath).toBe('/zh-CN/privacy')
})
