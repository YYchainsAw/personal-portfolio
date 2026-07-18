import { expect, it, vi } from 'vitest'
import { createPublicContentStore } from '@/stores/publicContent'
import { homeInitialPayload, zhCatalogEnvelope, zhSiteEnvelope } from '../fixtures/publicSnapshots'

it('reuses matching HOME bootstrap with zero first-load GETs', async () => {
  const api = { getSite: vi.fn(), getProjects: vi.fn(), getProject: vi.fn() }
  const store = createPublicContentStore(api, homeInitialPayload)
  const result = await store.loadHome('zh-CN')
  expect(result.site).toBe(homeInitialPayload.site)
  expect(api.getSite).not.toHaveBeenCalled()
  expect(api.getProjects).not.toHaveBeenCalled()
})

it('discards a mismatch once and caches a successful API route', async () => {
  const api = { getSite: vi.fn().mockResolvedValue(zhSiteEnvelope), getProjects: vi.fn().mockResolvedValue(zhCatalogEnvelope), getProject: vi.fn() }
  const store = createPublicContentStore(api, homeInitialPayload)
  await store.loadHome('en')
  await store.loadHome('en')
  expect(api.getSite).toHaveBeenCalledTimes(1)
  expect(api.getProjects).toHaveBeenCalledTimes(1)
})
