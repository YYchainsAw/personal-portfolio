import type { AxiosError } from 'axios'
import { describe, expect, it, vi } from 'vitest'

import { ApiProblem } from '@/types/api'

import { http, onAuthenticationRequired, toApiProblem } from './http'

describe('http', () => {
  it('uses same-origin credentials and Spring CSRF names', () => {
    expect(http.defaults.baseURL).toBe('/')
    expect(http.defaults.allowAbsoluteUrls).toBe(false)
    expect(http.defaults.withCredentials).toBe(true)
    expect(http.defaults.withXSRFToken).toBeUndefined()
    expect(http.defaults.xsrfCookieName).toBe('XSRF-TOKEN')
    expect(http.defaults.xsrfHeaderName).toBe('X-XSRF-TOKEN')
    expect(http.defaults.timeout).toBe(15_000)
  })

  it('preserves a safe problem body returned by the server', () => {
    const error = {
      isAxiosError: true,
      response: {
        status: 409,
        data: {
          type: 'conflict',
          title: '版本冲突',
          status: 409,
          code: 'VERSION_CONFLICT',
          traceId: '01ABC',
          fieldErrors: { version: '内容已更新' },
          detail: 'internal detail that is not part of the client contract',
          instance: '/internal/path',
        },
      },
    } as AxiosError

    const problem = toApiProblem(error)

    expect(problem).toBeInstanceOf(ApiProblem)
    expect(problem.body).toEqual({
      type: 'conflict',
      title: '版本冲突',
      status: 409,
      code: 'VERSION_CONFLICT',
      traceId: '01ABC',
      fieldErrors: { version: '内容已更新' },
    })
    expect(problem.body).not.toHaveProperty('detail')
    expect(problem.body).not.toHaveProperty('instance')
  })

  it('uses a bounded client problem for malformed or non-Axios failures', () => {
    const malformed = {
      isAxiosError: true,
      message: 'postgres password leaked here',
      response: { status: 500, data: { status: 500, code: 'RAW_INTERNAL_ERROR' } },
    } as AxiosError

    expect(toApiProblem(malformed).body).toEqual({
      type: 'network_error',
      title: '无法连接服务器',
      status: 0,
      code: 'NETWORK_ERROR',
      traceId: 'client',
    })
    expect(toApiProblem(new Error('filesystem path leaked here')).message).toBe('无法连接服务器')
  })

  it('does not wrap an existing ApiProblem', () => {
    const original = new ApiProblem({
      type: 'unauthorized',
      title: '需要登录',
      status: 401,
      code: 'AUTHENTICATION_REQUIRED',
      traceId: 't0',
    })

    expect(toApiProblem(original)).toBe(original)
  })

  it('rejects absolute and protocol-relative request URLs before dispatch', async () => {
    const adapter = vi.fn()
    const unsafeUrls = [
      'https://evil.example/api/admin/auth/me',
      '//evil.example/api/admin/auth/me',
      '\\evil.example/api/admin/auth/me',
      '/\\evil.example/api/admin/auth/me',
      '/\t/evil.example/api/admin/auth/me',
      '/\r/evil.example/api/admin/auth/me',
      '/\n/evil.example/api/admin/auth/me',
    ]

    for (const url of unsafeUrls) {
      await expect(http.get(url, { adapter })).rejects.toMatchObject({
        body: { code: 'CROSS_ORIGIN_REQUEST_BLOCKED' },
      })
    }
    await expect(
      http.get('/api/admin/auth/me', { baseURL: 'https://evil.example', adapter }),
    ).rejects.toMatchObject({ body: { code: 'CROSS_ORIGIN_REQUEST_BLOCKED' } })
    expect(adapter).not.toHaveBeenCalled()
  })

  it('uses a bounded Retry-After header when a safe 429 body has no delay', () => {
    const problem = toApiProblem({
      isAxiosError: true,
      response: {
        status: 429,
        headers: { 'Retry-After': '2' },
        data: {
          type: 'urn:portfolio:problem:rate_limited',
          title: 'Too many requests',
          status: 429,
          code: 'RATE_LIMITED',
          traceId: '0123456789abcdef0123456789abcdef',
          fieldErrors: {},
        },
      },
    } as unknown as AxiosError)

    expect(problem.body.retryAfterSeconds).toBe(2)
  })

  it('notifies subscribers only for the exact session-expired problem', async () => {
    const handler = vi.fn()
    const unsubscribe = onAuthenticationRequired(handler)
    const authenticationRequired = {
      isAxiosError: true,
      response: {
        status: 401,
        data: {
          type: 'urn:portfolio:problem:authentication_required',
          title: 'Authentication required',
          status: 401,
          code: 'AUTHENTICATION_REQUIRED',
          traceId: '0123456789abcdef0123456789abcdef',
          fieldErrors: {},
        },
      },
    } as AxiosError

    try {
      await expect(
        http.get('/api/admin/site/workspace', {
          adapter: vi.fn().mockRejectedValue(authenticationRequired),
        }),
      ).rejects.toMatchObject({ body: { code: 'AUTHENTICATION_REQUIRED' } })
      expect(handler).toHaveBeenCalledOnce()

      const nonSessionProblems = [
        {
          status: 401,
          code: 'AUTHENTICATION_FAILED',
          type: 'urn:portfolio:problem:authentication_failed',
        },
        {
          status: 403,
          code: 'AUTHENTICATION_REQUIRED',
          type: 'urn:portfolio:problem:authentication_required',
        },
      ]
      for (const body of nonSessionProblems) {
        await expect(
          http.get('/api/admin/site/workspace', {
            adapter: vi.fn().mockRejectedValue({
              isAxiosError: true,
              response: {
                status: body.status,
                data: {
                  ...body,
                  title: 'Request could not be processed',
                  traceId: '0123456789abcdef0123456789abcdef',
                  fieldErrors: {},
                },
              },
            }),
          }),
        ).rejects.toBeInstanceOf(ApiProblem)
      }
      expect(handler).toHaveBeenCalledOnce()
    } finally {
      unsubscribe()
    }
  })
})
