import { afterEach, describe, expect, it, vi } from 'vitest'

import { http } from './http'
import { settingsApi } from './settingsApi'

const uuid = (value: number): string =>
  `10000000-0000-0000-0000-${value.toString().padStart(12, '0')}`

const reauthentication = {
  currentPassword: 'current-secret',
  currentTotp: '123456',
}

const provisioningUri =
  'otpauth://totp/Portfolio:admin?secret=ABCDEFGHIJKLMNOP234567&issuer=Portfolio&algorithm=SHA1&digits=6&period=30'

const letterUuid = 'abcdefab-cdef-abcd-efab-cdefabcdefab'

function recoveryCodes(): string[] {
  return [...'23456789AB'].map((suffix) => `ABCD-EFGH-JKL${suffix}`)
}

function activeSession(value: number, overrides: Record<string, unknown> = {}) {
  return {
    id: uuid(value),
    status: 'ACTIVE',
    createdAt: `2026-07-18T10:00:${value.toString().padStart(2, '0')}Z`,
    lastAccessMillis: 1_752_832_800_000 + value,
    clientSummary: 'Chrome 140 / Windows',
    current: value === 2,
    ...overrides,
  }
}

function terminalSession(value: number, overrides: Record<string, unknown> = {}) {
  return {
    id: uuid(value),
    status: 'REVOKED',
    createdAt: `2026-07-18T09:00:${value.toString().padStart(2, '0')}Z`,
    endedAt: `2026-07-18T10:00:${value.toString().padStart(2, '0')}Z`,
    lastAccessMillis: 1_752_832_700_000 + value,
    clientSummary: 'Firefox 141 / Linux',
    reason: 'ADMIN_REQUEST',
    current: false,
    ...overrides,
  }
}

function auditItem(value: number, overrides: Record<string, unknown> = {}) {
  return {
    id: uuid(value),
    actorAdminId: uuid(900),
    action: 'ADMIN_PASSWORD_CHANGED',
    targetType: 'ADMIN',
    targetId: uuid(900),
    outcome: 'SUCCESS',
    traceId: `trace-${value}`,
    metadata: { method: 'TOTP', revokedOtherSessions: '2' },
    timestamp: `2026-07-18T10:00:${value.toString().padStart(2, '0')}.123456Z`,
    ...overrides,
  }
}

function maintenance(
  type: string,
  overrides: Record<string, unknown> = {},
): Record<string, unknown> {
  return {
    type,
    status: 'SUCCEEDED',
    startedAt: '2026-07-18T09:00:00Z',
    finishedAt: '2026-07-18T09:03:00.123456Z',
    artifactChecksum: 'a'.repeat(64),
    errorCategory: null,
    ...overrides,
  }
}

function operations(overrides: Record<string, unknown> = {}) {
  return {
    databaseBackup: maintenance('DATABASE_BACKUP'),
    mediaBackup: maintenance('MEDIA_BACKUP'),
    analyticsAggregation: maintenance('ANALYTICS_AGGREGATE', {
      artifactChecksum: null,
    }),
    contactRetention: maintenance('CONTACT_RETENTION', { artifactChecksum: null }),
    mediaCleanup: maintenance('MEDIA_CLEANUP_SCAN', { artifactChecksum: null }),
    deployment: maintenance('DEPLOYMENT'),
    restoreDrill: maintenance('RESTORE_DRILL'),
    serverTime: '2026-07-18T10:00:00.123456789Z',
    ...overrides,
  }
}

describe('settingsApi security contracts', () => {
  afterEach(() => {
    vi.restoreAllMocks()
  })

  it('sends an exact defensive password body and requires empty 204', async () => {
    const body = {
      ...reauthentication,
      newPassword: 'New-Portfolio-Secret-47!',
    }
    const post = vi
      .spyOn(http, 'post')
      .mockResolvedValue({ status: 204, data: undefined } as never)

    await expect(settingsApi.changePassword(body)).resolves.toBeUndefined()

    expect(post).toHaveBeenCalledOnce()
    expect(post).toHaveBeenCalledWith('/api/admin/security/password', body)
    expect(post.mock.calls[0]?.[1]).not.toBe(body)
  })

  it.each([
    { status: 200, data: undefined },
    { status: 204, data: {} },
    { status: 204, data: 'unexpected' },
  ])('rejects a non-empty or non-204 password success %#', async (response) => {
    vi.spyOn(http, 'post').mockResolvedValue(response as never)
    await expect(
      settingsApi.changePassword({
        ...reauthentication,
        newPassword: 'New-Portfolio-Secret-47!',
      }),
    ).rejects.toMatchObject({ body: { code: 'INVALID_SETTINGS_RESPONSE' } })
  })

  it('starts enrollment with the exact body and returns a defensive in-memory object', async () => {
    const response = {
      enrollmentId: uuid(20),
      provisioningUri,
      expiresAt: '2026-07-18T10:10:00Z',
    }
    const post = vi
      .spyOn(http, 'post')
      .mockResolvedValue({ status: 200, data: response } as never)

    const result = await settingsApi.beginTotpEnrollment(reauthentication)

    expect(post).toHaveBeenCalledWith(
      '/api/admin/security/totp/enrollment',
      reauthentication,
    )
    expect(post.mock.calls[0]?.[1]).not.toBe(reauthentication)
    expect(result).toEqual(response)
    expect(result).not.toBe(response)
  })

  it('confirms enrollment and regenerates recovery codes with exact defensive bodies', async () => {
    const codes = recoveryCodes()
    const confirmation = { enrollmentId: uuid(20), newTotp: '654321' }
    const post = vi.spyOn(http, 'post')
    post
      .mockResolvedValueOnce({ status: 200, data: { recoveryCodes: codes } } as never)
      .mockResolvedValueOnce({ status: 200, data: { recoveryCodes: codes } } as never)

    const confirmed = await settingsApi.confirmTotp(confirmation)
    const regenerated = await settingsApi.regenerateRecoveryCodes(reauthentication)

    expect(post).toHaveBeenNthCalledWith(
      1,
      '/api/admin/security/totp/confirm',
      confirmation,
    )
    expect(post).toHaveBeenNthCalledWith(
      2,
      '/api/admin/security/recovery-codes/regenerate',
      reauthentication,
    )
    expect(post.mock.calls[0]?.[1]).not.toBe(confirmation)
    expect(post.mock.calls[1]?.[1]).not.toBe(reauthentication)
    expect(confirmed.recoveryCodes).toEqual(codes)
    expect(regenerated.recoveryCodes).toEqual(codes)
    expect(confirmed.recoveryCodes).not.toBe(codes)
    expect(regenerated.recoveryCodes).not.toBe(codes)
    expect(confirmed).not.toBe(regenerated)
  })

  it.each([
    { ...reauthentication, currentTotp: '12345' },
    { ...reauthentication, currentPassword: '' },
    { ...reauthentication, extra: 'secret' },
    { ...reauthentication, currentPassword: '\ud800' },
  ])('rejects invalid reauthentication before transport %#', async (body) => {
    const post = vi.spyOn(http, 'post')
    await expect(settingsApi.beginTotpEnrollment(body as never)).rejects.toThrow(TypeError)
    expect(post).not.toHaveBeenCalled()
  })

  it.each([
    { enrollmentId: letterUuid.toUpperCase(), newTotp: '123456' },
    { enrollmentId: uuid(20), newTotp: '12345x' },
    { enrollmentId: uuid(20), newTotp: '123456', confirmation: '123456' },
  ])('rejects an invalid TOTP confirmation before transport %#', async (body) => {
    const post = vi.spyOn(http, 'post')
    await expect(settingsApi.confirmTotp(body as never)).rejects.toThrow(TypeError)
    expect(post).not.toHaveBeenCalled()
  })

  it.each([
    { provisioningUri: 'https://example.com/qr' },
    { provisioningUri: `${provisioningUri}&secret=SECONDSECRET2345` },
    { expiresAt: '2026-07-18T10:10:00.000Z' },
    { enrollmentId: letterUuid.toUpperCase() },
    { qrCodeUrl: 'https://example.com/secret.png' },
  ])('rejects a malformed enrollment success %#', async (override) => {
    vi.spyOn(http, 'post').mockResolvedValue({
      status: 200,
      data: {
        enrollmentId: uuid(20),
        provisioningUri,
        expiresAt: '2026-07-18T10:10:00Z',
        ...override,
      },
    } as never)

    await expect(
      settingsApi.beginTotpEnrollment(reauthentication),
    ).rejects.toMatchObject({ body: { code: 'INVALID_SETTINGS_RESPONSE' } })
  })

  it.each([
    { recoveryCodes: recoveryCodes().slice(0, 9) },
    { recoveryCodes: recoveryCodes().map((value) => value.toLowerCase()) },
    { recoveryCodes: recoveryCodes().map((value, index) => (index === 9 ? 'ABCD-EFGH-JKL2' : value)) },
    { recoveryCodes: recoveryCodes(), secret: 'must-not-pass' },
  ])('rejects malformed one-time recovery material %#', async (data) => {
    vi.spyOn(http, 'post').mockResolvedValue({ status: 200, data } as never)
    await expect(
      settingsApi.regenerateRecoveryCodes(reauthentication),
    ).rejects.toMatchObject({ body: { code: 'INVALID_SETTINGS_RESPONSE' } })
  })

  it('never retries a failed credential mutation', async () => {
    const failure = new Error('network failed')
    const post = vi.spyOn(http, 'post').mockRejectedValue(failure)
    await expect(
      settingsApi.regenerateRecoveryCodes(reauthentication),
    ).rejects.toBe(failure)
    expect(post).toHaveBeenCalledOnce()
  })
})

describe('settingsApi sessions', () => {
  afterEach(() => {
    vi.restoreAllMocks()
  })

  it('lists sorted sessions and normalizes Jackson-omitted active nulls', async () => {
    const response = [activeSession(2), terminalSession(1)]
    const get = vi
      .spyOn(http, 'get')
      .mockResolvedValue({ status: 200, data: response } as never)

    const result = await settingsApi.listSessions()

    expect(get).toHaveBeenCalledOnce()
    expect(get).toHaveBeenCalledWith('/api/admin/security/sessions')
    expect(result).toEqual([
      { ...response[0], endedAt: null, reason: null },
      response[1],
    ])
    expect(result).not.toBe(response)
    expect(result[0]).not.toBe(response[0])
  })

  it.each([
    { data: [activeSession(2, { endedAt: '2026-07-18T10:01:00Z' })] },
    { data: [terminalSession(1, { endedAt: undefined })] },
    { data: [terminalSession(1, { current: true })] },
    { data: [activeSession(2, { id: letterUuid.toUpperCase() })] },
    { data: [activeSession(2, { createdAt: '2026-07-18T10:00:02.000Z' })] },
    { data: [activeSession(2, { rawUserAgent: 'secret' })] },
    { data: [activeSession(2, { lastAccessMillis: Number.MAX_SAFE_INTEGER })] },
    { data: [activeSession(2), activeSession(1, { current: true })] },
    { data: [terminalSession(1), activeSession(2)] },
  ])('rejects a malformed session list %#', async ({ data }) => {
    vi.spyOn(http, 'get').mockResolvedValue({ status: 200, data } as never)
    await expect(settingsApi.listSessions()).rejects.toMatchObject({
      body: { code: 'INVALID_SETTINGS_RESPONSE' },
    })
  })

  it('revokes only a canonical encoded UUID and requires an empty 204', async () => {
    const post = vi
      .spyOn(http, 'post')
      .mockResolvedValue({ status: 204, data: null } as never)

    await expect(settingsApi.revokeSession(uuid(44))).resolves.toBeUndefined()

    expect(post).toHaveBeenCalledWith(
      `/api/admin/security/sessions/${uuid(44)}/revoke`,
    )
  })

  it.each(['', 'not-a-uuid', letterUuid.toUpperCase(), `${uuid(44)}/revoke`])(
    'rejects unsafe session id %s before transport',
    async (id) => {
      const post = vi.spyOn(http, 'post')
      await expect(settingsApi.revokeSession(id)).rejects.toThrow(TypeError)
      expect(post).not.toHaveBeenCalled()
    },
  )

  it('does not retry uncertain revocation and rejects a body on 204', async () => {
    const post = vi.spyOn(http, 'post')
    post.mockRejectedValueOnce(new Error('timeout'))
    await expect(settingsApi.revokeSession(uuid(44))).rejects.toThrow('timeout')
    expect(post).toHaveBeenCalledOnce()

    post.mockReset().mockResolvedValue({ status: 204, data: { revoked: true } } as never)
    await expect(settingsApi.revokeSession(uuid(44))).rejects.toMatchObject({
      body: { code: 'INVALID_SETTINGS_RESPONSE' },
    })
    expect(post).toHaveBeenCalledOnce()
  })
})

describe('settingsApi audit history', () => {
  afterEach(() => {
    vi.restoreAllMocks()
  })

  it('sends exact filters and normalizes omitted nullable audit fields', async () => {
    const response = {
      items: [
        auditItem(2),
        auditItem(1, {
          actorAdminId: undefined,
          targetId: undefined,
          metadata: { createdDate: '2026-07-18' },
        }),
      ],
    }
    const get = vi
      .spyOn(http, 'get')
      .mockResolvedValue({ status: 200, data: response } as never)
    const query = {
      cursor: '   ',
      action: '  ADMIN_PASSWORD_CHANGED  ',
      outcome: 'SUCCESS' as const,
      from: ' 2026-07-18T09:00:00Z ',
      to: '2026-07-18T11:00:00.123456Z',
      limit: 50,
    }

    const result = await settingsApi.getAudit(query)

    expect(get).toHaveBeenCalledWith('/api/admin/audit', {
      params: {
        action: 'ADMIN_PASSWORD_CHANGED',
        outcome: 'SUCCESS',
        from: '2026-07-18T09:00:00Z',
        to: '2026-07-18T11:00:00.123456Z',
        limit: 50,
      },
    })
    expect(result.nextCursor).toBeNull()
    expect(result.items[1]).toMatchObject({ actorAdminId: null, targetId: null })
    expect(result.items).not.toBe(response.items)
    expect(result.items[0]?.metadata).not.toBe(response.items[0]?.metadata)
  })

  it('preserves an opaque cursor and accepts a canonical sub-millisecond range', async () => {
    const cursor = 'A'.repeat(86)
    const get = vi
      .spyOn(http, 'get')
      .mockResolvedValue({ status: 200, data: { items: [] } } as never)

    await settingsApi.getAudit({
      cursor,
      from: '2026-07-18T10:00:00.000001Z',
      to: '2026-07-18T10:00:00.000002Z',
      limit: 1,
    })

    expect(get).toHaveBeenCalledWith('/api/admin/audit', {
      params: {
        cursor,
        from: '2026-07-18T10:00:00.000001Z',
        to: '2026-07-18T10:00:00.000002Z',
        limit: 1,
      },
    })
  })

  it.each([
    { limit: 0 },
    { limit: 101 },
    { limit: 1.5 },
    { cursor: 'A'.repeat(87) },
    { cursor: 'bad=cursor' },
    { action: 'lowercase' },
    { outcome: 'UNKNOWN' },
    { from: '2026-07-18T10:00:00.000Z' },
    { from: '2026-07-18T10:00:00.123456789Z' },
    { from: '2026-07-18T10:00:00Z', to: '2026-07-18T10:00:00Z' },
    { secret: 'must-not-pass' },
  ])('rejects an invalid audit query before transport %#', async (query) => {
    const get = vi.spyOn(http, 'get')
    await expect(settingsApi.getAudit(query as never)).rejects.toThrow()
    expect(get).not.toHaveBeenCalled()
  })

  it.each([
    { items: [auditItem(1, { actorAdminId: letterUuid.toUpperCase() })] },
    { items: [auditItem(1, { timestamp: '2026-07-18T10:00:01.000Z' })] },
    { items: [auditItem(1, { metadata: { password: 'secret' } })] },
    { items: [auditItem(1, { metadata: { method: 'safe\nunsafe' } })] },
    { items: [auditItem(1, { rawMetadata: '{}' })] },
    { items: [auditItem(1), auditItem(2)] },
    { items: [auditItem(1)], nextCursor: 'bad=cursor' },
    { items: [], nextCursor: 'opaque' },
    { items: [], databasePassword: 'secret' },
  ])('rejects malformed or secret-bearing audit responses %#', async (data) => {
    vi.spyOn(http, 'get').mockResolvedValue({ status: 200, data } as never)
    await expect(settingsApi.getAudit({ limit: 1 })).rejects.toMatchObject({
      body: { code: 'INVALID_SETTINGS_RESPONSE' },
    })
  })

  it('requires nextCursor pages to contain the requested limit', async () => {
    vi.spyOn(http, 'get').mockResolvedValue({
      status: 200,
      data: { items: [auditItem(2), auditItem(1)], nextCursor: 'opaque-next' },
    } as never)
    await expect(settingsApi.getAudit({ limit: 2 })).resolves.toMatchObject({
      nextCursor: 'opaque-next',
    })
  })
})

describe('settingsApi operations status', () => {
  afterEach(() => {
    vi.restoreAllMocks()
  })

  it('gets the sole fixed operations endpoint and returns defensive views', async () => {
    const response = operations()
    const get = vi
      .spyOn(http, 'get')
      .mockResolvedValue({ status: 200, data: response } as never)

    const result = await settingsApi.getOperations()

    expect(get).toHaveBeenCalledOnce()
    expect(get).toHaveBeenCalledWith('/api/admin/system/operations')
    expect(result).toEqual(response)
    expect(result).not.toBe(response)
    expect(result.databaseBackup).not.toBe(response.databaseBackup)
  })

  it('rejects a missing operations root key even when the omitted value would be null', async () => {
    const { restoreDrill: _omitted, ...response } = operations({ restoreDrill: null })
    vi.spyOn(http, 'get').mockResolvedValue({ status: 200, data: response } as never)

    await expect(settingsApi.getOperations()).rejects.toMatchObject({
      body: { code: 'INVALID_SETTINGS_RESPONSE' },
    })
  })

  it('rejects a missing nested maintenance key even when the omitted value would be null', async () => {
    const complete = maintenance('DATABASE_BACKUP', {
      status: 'RUNNING',
      finishedAt: null,
      artifactChecksum: null,
      errorCategory: null,
    })
    const { artifactChecksum: _omitted, ...incomplete } = complete
    const response = operations({ databaseBackup: incomplete })
    vi.spyOn(http, 'get').mockResolvedValue({ status: 200, data: response } as never)

    await expect(settingsApi.getOperations()).rejects.toMatchObject({
      body: { code: 'INVALID_SETTINGS_RESPONSE' },
    })
  })

  it('accepts only the exact mapped error category for a failed run', async () => {
    vi.spyOn(http, 'get').mockResolvedValue({
      status: 200,
      data: operations({
        mediaCleanup: maintenance('MEDIA_CLEANUP_SCAN', {
          status: 'FAILED',
          artifactChecksum: null,
          errorCategory: 'MEDIA_CLEANUP_FAILED',
        }),
      }),
    } as never)

    await expect(settingsApi.getOperations()).resolves.toMatchObject({
      mediaCleanup: {
        type: 'MEDIA_CLEANUP_SCAN',
        status: 'FAILED',
        errorCategory: 'MEDIA_CLEANUP_FAILED',
      },
    })
  })

  it.each([
    operations({ databasePassword: 'secret' }),
    operations({ serverTime: '2026-07-18T10:00:00.000Z' }),
    operations({ databaseBackup: maintenance('MEDIA_BACKUP') }),
    operations({ databaseBackup: maintenance('DATABASE_BACKUP', { status: 'UNKNOWN' }) }),
    operations({ databaseBackup: maintenance('DATABASE_BACKUP', { finishedAt: undefined }) }),
    operations({ databaseBackup: maintenance('DATABASE_BACKUP', { artifactChecksum: 'A'.repeat(64) }) }),
    operations({ databaseBackup: maintenance('DATABASE_BACKUP', { errorCategory: 'DATABASE_BACKUP_FAILED' }) }),
    operations({
      databaseBackup: maintenance('DATABASE_BACKUP', {
        status: 'FAILED',
        errorCategory: 'INTERNAL_EXCEPTION',
      }),
    }),
    operations({
      databaseBackup: maintenance('DATABASE_BACKUP', {
        bucket: 'private-bucket',
      }),
    }),
  ])('rejects malformed or secret-bearing operations responses %#', async (data) => {
    vi.spyOn(http, 'get').mockResolvedValue({ status: 200, data } as never)
    await expect(settingsApi.getOperations()).rejects.toMatchObject({
      body: { code: 'INVALID_SETTINGS_RESPONSE' },
    })
  })

  it('does not retry an operations read failure', async () => {
    const get = vi.spyOn(http, 'get').mockRejectedValue(new Error('offline'))
    await expect(settingsApi.getOperations()).rejects.toThrow('offline')
    expect(get).toHaveBeenCalledOnce()
  })
})

describe('settingsApi successful body status contracts', () => {
  afterEach(() => {
    vi.restoreAllMocks()
  })

  it.each([201, 202])(
    'rejects status %i for every body-returning endpoint even when each body is valid',
    async (status) => {
      const codes = recoveryCodes()
      const post = vi.spyOn(http, 'post')
      post
        .mockResolvedValueOnce({
          status,
          data: {
            enrollmentId: uuid(20),
            provisioningUri,
            expiresAt: '2026-07-18T10:10:00Z',
          },
        } as never)
        .mockResolvedValueOnce({ status, data: { recoveryCodes: codes } } as never)
        .mockResolvedValueOnce({ status, data: { recoveryCodes: codes } } as never)

      await expect(
        settingsApi.beginTotpEnrollment(reauthentication),
      ).rejects.toMatchObject({ body: { code: 'INVALID_SETTINGS_RESPONSE' } })
      await expect(
        settingsApi.confirmTotp({ enrollmentId: uuid(20), newTotp: '654321' }),
      ).rejects.toMatchObject({ body: { code: 'INVALID_SETTINGS_RESPONSE' } })
      await expect(
        settingsApi.regenerateRecoveryCodes(reauthentication),
      ).rejects.toMatchObject({ body: { code: 'INVALID_SETTINGS_RESPONSE' } })

      const get = vi.spyOn(http, 'get')
      get
        .mockResolvedValueOnce({
          status,
          data: [activeSession(2), terminalSession(1)],
        } as never)
        .mockResolvedValueOnce({ status, data: { items: [] } } as never)
        .mockResolvedValueOnce({ status, data: operations() } as never)

      await expect(settingsApi.listSessions()).rejects.toMatchObject({
        body: { code: 'INVALID_SETTINGS_RESPONSE' },
      })
      await expect(settingsApi.getAudit()).rejects.toMatchObject({
        body: { code: 'INVALID_SETTINGS_RESPONSE' },
      })
      await expect(settingsApi.getOperations()).rejects.toMatchObject({
        body: { code: 'INVALID_SETTINGS_RESPONSE' },
      })

      expect(post).toHaveBeenCalledTimes(3)
      expect(get).toHaveBeenCalledTimes(3)
    },
  )
})
