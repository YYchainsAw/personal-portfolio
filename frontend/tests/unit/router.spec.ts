import { createMemoryHistory } from 'vue-router'
import { describe, expect, it } from 'vitest'
import { createPublicRouter } from '@/router'
import { localeRouteLocation } from '@/composables/useLocale'

describe('localized public routes', () => {
  it.each([['/zh-CN', 'home'], ['/en', 'home'], ['/en/projects/ue-study', 'project'], ['/zh-CN/privacy', 'privacy']])('matches %s', async (path, name) => {
    const router = createPublicRouter(createMemoryHistory())
    await router.push(path); await router.isReady()
    expect(router.currentRoute.value.name).toBe(name)
  })
  it('redirects root and rejects an unsupported locale', async () => {
    const router = createPublicRouter(createMemoryHistory())
    await router.push('/'); await router.isReady(); expect(router.currentRoute.value.fullPath).toBe('/zh-CN')
    await router.push('/fr'); expect(router.currentRoute.value.name).toBe('not-found')
    await router.push('/en/bogus'); expect(router.currentRoute.value.name).toBe('not-found')
  })
  it('preserves project semantics when switching language', () => {
    expect(localeRouteLocation({ name: 'project', params: { locale: 'zh-CN', slug: 'ue-study' } } as never, 'en'))
      .toEqual({ name: 'project', params: { locale: 'en', slug: 'ue-study' } })
  })
})
