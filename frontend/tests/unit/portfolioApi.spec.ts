import { expect, it, vi } from 'vitest'
import { createPortfolioApi, PublicApiProblem } from '@/services/portfolioApi'
import { enProjectEnvelope } from '../fixtures/publicSnapshots'

it('uses exact locale/slug URL and same-origin revalidation options', async () => {
  const fetcher = vi.fn().mockResolvedValue(new Response(JSON.stringify(enProjectEnvelope), { status: 200, headers: { 'Content-Type': 'application/json' } }))
  await createPortfolioApi(fetcher).getProject('en', 'ue-study')
  expect(fetcher).toHaveBeenCalledWith('/api/public/projects/ue-study?locale=en', expect.objectContaining({ method: 'GET', credentials: 'same-origin', cache: 'no-cache' }))
})

it('preserves only a validated safe problem', async () => {
  const body = { type: 'not_found', title: 'Missing', status: 404, code: 'PROJECT_NOT_FOUND', traceId: 'trace-1' }
  const fetcher = vi.fn().mockImplementation(async () => new Response(JSON.stringify(body), { status: 404, headers: { 'Content-Type': 'application/problem+json' } }))
  await expect(createPortfolioApi(fetcher).getProject('en', 'missing')).rejects.toEqual(expect.any(PublicApiProblem))
  await expect(createPortfolioApi(fetcher).getProject('en', 'missing')).rejects.toMatchObject({ body })
})
