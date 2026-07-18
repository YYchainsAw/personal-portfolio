import { afterEach, describe, expect, it, vi } from 'vitest'

import { authApi } from './authApi'
import { http } from './http'

const admin = { id: '47c9505c-8134-4fd1-aa0c-32d15c52dba5', username: 'admin' }

describe('authApi', () => {
  afterEach(() => {
    vi.restoreAllMocks()
  })

  it('unwraps the safe GET auth responses', async () => {
    const get = vi.spyOn(http, 'get')
    get
      .mockResolvedValueOnce({ data: admin } as never)
      .mockResolvedValueOnce({
        data: {
          headerName: 'X-XSRF-TOKEN',
          parameterName: '_csrf',
          token: 'csrf-token',
        },
      } as never)

    await expect(authApi.getMe()).resolves.toEqual(admin)
    await expect(authApi.ensureCsrf()).resolves.toBeUndefined()
    expect(get).toHaveBeenNthCalledWith(1, '/api/admin/auth/me')
    expect(get).toHaveBeenNthCalledWith(2, '/api/admin/auth/csrf')
  })

  it('sends exact password, second-factor, and logout requests', async () => {
    const post = vi.spyOn(http, 'post')
    post
      .mockResolvedValueOnce({
        data: { next: 'SECOND_FACTOR', expiresAt: '2026-07-14T12:00:00Z' },
      } as never)
      .mockResolvedValueOnce({ data: admin } as never)
      .mockResolvedValueOnce({ data: undefined } as never)

    await expect(authApi.passwordStage('admin', 'secret')).resolves.toEqual({
      next: 'SECOND_FACTOR',
      expiresAt: '2026-07-14T12:00:00Z',
    })
    await expect(authApi.secondFactor('RECOVERY_CODE', 'recovery-code')).resolves.toEqual({
      id: admin.id,
      username: 'admin',
    })
    await expect(authApi.logout()).resolves.toBeUndefined()

    expect(post).toHaveBeenNthCalledWith(1, '/api/admin/auth/password', {
      username: 'admin',
      password: 'secret',
    })
    expect(post).toHaveBeenNthCalledWith(2, '/api/admin/auth/second-factor', {
      method: 'RECOVERY_CODE',
      code: 'recovery-code',
    })
    expect(post).toHaveBeenNthCalledWith(3, '/api/admin/auth/logout')
  })

  it('rejects malformed successful responses instead of corrupting session state', async () => {
    vi.spyOn(http, 'get').mockResolvedValueOnce({
      data: { id: 'not-a-uuid', username: '' },
    } as never)

    await expect(authApi.getMe()).rejects.toMatchObject({
      body: { code: 'INVALID_SERVER_RESPONSE' },
    })
  })
})
