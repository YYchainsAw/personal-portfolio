import { expect, it, vi } from 'vitest'
import { ContactSubmissionError, createContactApi } from '@/services/contactApi'
import type { ContactRequest } from '@/types/interactions'

const request: ContactRequest = { name: 'Tester', email: 'test@example.test', subject: 'Hello', message: 'Synthetic message', website: '', privacyAccepted: true }

it('acquires CSRF first and sends exactly six contact keys under the returned header', async () => {
  const order: string[] = []
  const csrf = { ensureToken: vi.fn(async () => { order.push('csrf'); return { headerName: 'X-CSRF', parameterName: '_csrf', token: 'token' } }), invalidate: vi.fn() }
  const fetcher = vi.fn(async (_url: string, init?: RequestInit) => {
    order.push('post')
    expect(Object.keys(JSON.parse(String(init?.body))).sort()).toEqual(['email', 'message', 'name', 'privacyAccepted', 'subject', 'website'])
    const headers = init?.headers as Record<string, string> | undefined
    expect(headers?.['X-CSRF']).toBe('token')
    return new Response('{"accepted":true}', { status: 202 })
  }) as unknown as typeof fetch
  await expect(createContactApi(fetcher, csrf).submit(request)).resolves.toEqual({ accepted: true })
  expect(order).toEqual(['csrf', 'post'])
})

it('does not post without a token and invalidates without replay on CSRF_INVALID', async () => {
  const missing = { ensureToken: vi.fn().mockRejectedValue(new Error('no')), invalidate: vi.fn() }
  const fetcher = vi.fn()
  await expect(createContactApi(fetcher, missing).submit(request)).rejects.toMatchObject({ kind: 'retryable' })
  expect(fetcher).not.toHaveBeenCalled()

  const csrf = { ensureToken: vi.fn().mockResolvedValue({ headerName: 'X', parameterName: '_csrf', token: 't' }), invalidate: vi.fn() }
  const forbidden = vi.fn().mockResolvedValue(new Response('{"code":"CSRF_INVALID"}', { status: 403 }))
  await expect(createContactApi(forbidden, csrf).submit(request)).rejects.toEqual(expect.any(ContactSubmissionError))
  expect(csrf.invalidate).toHaveBeenCalledTimes(1); expect(forbidden).toHaveBeenCalledTimes(1)
})

it('rejects an expanded success receipt as a protocol error', async () => {
  const csrf = { ensureToken: vi.fn().mockResolvedValue({ headerName: 'X', parameterName: '_csrf', token: 't' }), invalidate: vi.fn() }
  const fetcher = vi.fn().mockResolvedValue(new Response('{"accepted":true,"messageId":"forbidden"}', { status: 202 }))
  await expect(createContactApi(fetcher, csrf).submit(request)).rejects.toMatchObject({ kind: 'malformed' })
})

it.each([
  [422, '{"code":"VALIDATION_ERROR","fieldErrors":{"email":"invalid","unknown":"hidden"}}', 'validation'],
  [400, '{"code":"MALFORMED_REQUEST"}', 'malformed'],
  [429, '{"code":"RATE_LIMITED"}', 'rate-limited'],
  [500, '{"code":"SERVER_ERROR"}', 'retryable'],
] as const)('classifies HTTP %s without echoing the response', async (status, body, kind) => {
  const csrf = { ensureToken: vi.fn().mockResolvedValue({ headerName: 'X', parameterName: '_csrf', token: 't' }), invalidate: vi.fn() }
  const fetcher = vi.fn().mockResolvedValue(new Response(body, { status, headers: status === 429 ? { 'Retry-After': '99999' } : {} }))
  const error = await createContactApi(fetcher, csrf).submit(request).catch((cause: unknown) => cause)
  expect(error).toMatchObject({ kind })
  expect(String(error)).not.toContain(body)
  if (status === 422) expect(error.fieldErrors).toEqual({ email: 'invalid' })
  if (status === 429) expect(error.retryAfterSeconds).toBe(3600)
})
