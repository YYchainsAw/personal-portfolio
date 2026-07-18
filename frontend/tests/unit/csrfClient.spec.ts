import { expect, it, vi } from 'vitest'
import { createCsrfClient } from '@/services/csrfClient'

it('coalesces and caches one exact no-store token acquisition in memory', async () => {
  const fetcher = vi.fn().mockResolvedValue(new Response(JSON.stringify({ headerName: 'X-XSRF-TOKEN', parameterName: '_csrf', token: 'synthetic' }), { status: 200 }))
  const client = createCsrfClient(fetcher)
  const [one, two] = await Promise.all([client.ensureToken(), client.ensureToken()])
  expect(one).toEqual(two)
  expect(fetcher).toHaveBeenCalledTimes(1)
  expect(fetcher).toHaveBeenCalledWith('/api/admin/auth/csrf', { method: 'GET', credentials: 'same-origin', cache: 'no-store', headers: { Accept: 'application/json' } })
  await client.ensureToken(); expect(fetcher).toHaveBeenCalledTimes(1)
})

it('rejects incomplete tokens without leaking the body and retries later', async () => {
  const fetcher = vi.fn().mockResolvedValueOnce(new Response(JSON.stringify({ token: 'secret' }), { status: 200 })).mockResolvedValueOnce(new Response(JSON.stringify({ headerName: 'X', parameterName: '_csrf', token: 'ok' }), { status: 200 }))
  const client = createCsrfClient(fetcher)
  await expect(client.ensureToken()).rejects.not.toThrow('secret')
  await expect(client.ensureToken()).resolves.toMatchObject({ token: 'ok' })
  expect(fetcher).toHaveBeenCalledTimes(2)
})

it('rejects a token response with unknown fields', async () => {
  const fetcher = vi.fn().mockResolvedValue(new Response(JSON.stringify({
    headerName: 'X-XSRF-TOKEN', parameterName: '_csrf', token: 'synthetic', unexpected: 'discard-me',
  }), { status: 200 }))
  await expect(createCsrfClient(fetcher).ensureToken()).rejects.toThrow('CSRF token unavailable')
})
