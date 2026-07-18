import { watch } from 'vue'
import { beforeEach, describe, expect, it, vi } from 'vitest'

import { ApiProblem } from '@/types/api'

import { createSessionStore, type AuthPort } from './session'

function createPort(overrides: Partial<AuthPort> = {}): AuthPort {
  return {
    getMe: vi.fn().mockRejectedValue(
      new ApiProblem({
        type: 'unauthorized',
        title: '需要登录',
        status: 401,
        code: 'AUTHENTICATION_REQUIRED',
        traceId: 't0',
      }),
    ),
    ensureCsrf: vi.fn().mockResolvedValue(undefined),
    passwordStage: vi.fn().mockResolvedValue({
      next: 'SECOND_FACTOR',
      expiresAt: '2026-07-14T12:00:00Z',
    }),
    secondFactor: vi.fn().mockResolvedValue({
      id: '47c9505c-8134-4fd1-aa0c-32d15c52dba5',
      username: 'admin',
    }),
    logout: vi.fn().mockResolvedValue(undefined),
    ...overrides,
  }
}

describe('session store', () => {
  beforeEach(() => {
    localStorage.clear()
    sessionStorage.clear()
  })

  it('keeps only the transient second-factor expiry after password login', async () => {
    const port = createPort()
    const store = createSessionStore(port)
    const storageWrite = vi.spyOn(Storage.prototype, 'setItem')

    await store.login('admin', 'secret')

    expect(port.ensureCsrf).toHaveBeenCalledOnce()
    expect(port.passwordStage).toHaveBeenCalledWith('admin', 'secret')
    const csrfOrder = vi.mocked(port.ensureCsrf).mock.invocationCallOrder[0]
    const passwordOrder = vi.mocked(port.passwordStage).mock.invocationCallOrder[0]
    expect(csrfOrder).toBeDefined()
    expect(passwordOrder).toBeDefined()
    expect(csrfOrder as number).toBeLessThan(passwordOrder as number)
    expect(store.state.phase).toBe('TOTP_REQUIRED')
    expect(store.state.user).toBeNull()
    expect(store.state.secondFactorExpiresAt).toBe('2026-07-14T12:00:00Z')
    expect(localStorage.length).toBe(0)
    expect(sessionStorage.length).toBe(0)
    expect(storageWrite).not.toHaveBeenCalled()
    expect(JSON.stringify(store)).not.toContain('secret')
  })

  it('bootstraps a valid administrator and clears stale challenge state', async () => {
    const port = createPort({
      getMe: vi.fn().mockResolvedValue({
        id: '47c9505c-8134-4fd1-aa0c-32d15c52dba5',
        username: 'admin',
      }),
    })
    const store = createSessionStore(port)

    await expect(store.bootstrap()).resolves.toBe('AUTHENTICATED')
    expect(store.state).toMatchObject({
      phase: 'AUTHENTICATED',
      user: { username: 'admin' },
      secondFactorExpiresAt: null,
    })
  })

  it('rethrows non-session 401 problems without forging anonymous state', async () => {
    const authenticationFailed = new ApiProblem({
      type: 'unauthorized',
      title: '请求未完成',
      status: 401,
      code: 'AUTHENTICATION_FAILED',
      traceId: 't1',
    })
    const store = createSessionStore(
      createPort({ getMe: vi.fn().mockRejectedValue(authenticationFailed) }),
    )

    await expect(store.bootstrap()).rejects.toBe(authenticationFailed)
    expect(store.state.phase).toBe('UNKNOWN')
  })

  it('maps only an authentication-required bootstrap response to anonymous', async () => {
    const store = createSessionStore(createPort())

    await expect(store.bootstrap()).resolves.toBe('ANONYMOUS')
    expect(store.state).toMatchObject({
      phase: 'ANONYMOUS',
      user: null,
      secondFactorExpiresAt: null,
    })
  })

  it('finishes the active second-factor stage without persisting secrets', async () => {
    const port = createPort()
    const store = createSessionStore(port)
    const storageWrite = vi.spyOn(Storage.prototype, 'setItem')
    await store.login('admin', 'secret')

    await store.verifySecondFactor('TOTP', '123456')

    expect(port.secondFactor).toHaveBeenCalledWith('TOTP', '123456')
    expect(store.state).toMatchObject({
      phase: 'AUTHENTICATED',
      user: { id: '47c9505c-8134-4fd1-aa0c-32d15c52dba5', username: 'admin' },
      secondFactorExpiresAt: null,
    })
    expect(localStorage.length).toBe(0)
    expect(sessionStorage.length).toBe(0)
    expect(storageWrite).not.toHaveBeenCalled()
    expect(JSON.stringify(store)).not.toContain('123456')
  })

  it('rejects second-factor verification without an active challenge', async () => {
    const port = createPort()
    const store = createSessionStore(port)

    await expect(store.verifySecondFactor('TOTP', '123456')).rejects.toMatchObject({
      body: { code: 'SECOND_FACTOR_NOT_ACTIVE' },
    })
    expect(port.secondFactor).not.toHaveBeenCalled()
  })

  it('abandons an old challenge before starting a new password attempt', async () => {
    const port = createPort()
    const store = createSessionStore(port)
    await store.login('admin', 'first-password')
    vi.mocked(port.passwordStage).mockRejectedValueOnce(
      new ApiProblem({
        type: 'unauthorized',
        title: '请求未完成',
        status: 401,
        code: 'AUTHENTICATION_FAILED',
        traceId: 't2',
      }),
    )

    await expect(store.login('admin', 'wrong-password')).rejects.toMatchObject({
      body: { code: 'AUTHENTICATION_FAILED' },
    })
    expect(store.state).toMatchObject({
      phase: 'ANONYMOUS',
      user: null,
      secondFactorExpiresAt: null,
    })
  })

  it('does not let a late bootstrap response undo logout', async () => {
    let resolveMe!: (user: { id: string; username: string }) => void
    const port = createPort({
      getMe: vi.fn().mockReturnValue(
        new Promise((resolve) => {
          resolveMe = resolve
        }),
      ),
    })
    const store = createSessionStore(port)

    const bootstrap = store.bootstrap()
    await store.logout()
    resolveMe({ id: '47c9505c-8134-4fd1-aa0c-32d15c52dba5', username: 'admin' })
    await bootstrap

    expect(store.state.phase).toBe('ANONYMOUS')
    expect(store.state.user).toBeNull()
  })

  it('publishes only atomic snapshots that satisfy the phase invariants', async () => {
    const store = createSessionStore(createPort())
    const snapshots: SessionStateSnapshot[] = []
    const stop = watch(
      () => store.state,
      (state) => snapshots.push({ ...state }),
      { flush: 'sync' },
    )

    await store.login('admin', 'secret')
    await store.verifySecondFactor('TOTP', '123456')
    stop()

    expect(snapshots).toHaveLength(3)
    expect(snapshots.every(isValidSessionSnapshot)).toBe(true)
  })

  it('dispatches only one second-factor request at a time', async () => {
    let resolveSecondFactor!: (user: { id: string; username: string }) => void
    const port = createPort({
      secondFactor: vi.fn().mockReturnValue(
        new Promise((resolve) => {
          resolveSecondFactor = resolve
        }),
      ),
    })
    const store = createSessionStore(port)
    await store.login('admin', 'secret')

    const first = store.verifySecondFactor('TOTP', '123456')
    await expect(store.verifySecondFactor('TOTP', '123456')).rejects.toMatchObject({
      body: { code: 'AUTHENTICATION_OPERATION_IN_PROGRESS' },
    })
    expect(port.secondFactor).toHaveBeenCalledOnce()
    resolveSecondFactor({
      id: '47c9505c-8134-4fd1-aa0c-32d15c52dba5',
      username: 'admin',
    })
    await first
    expect(store.state.phase).toBe('AUTHENTICATED')
  })

  it('invalidates locally and idempotently', () => {
    const store = createSessionStore(createPort())

    store.invalidate()
    store.invalidate()

    expect(store.state).toMatchObject({
      phase: 'ANONYMOUS',
      user: null,
      secondFactorExpiresAt: null,
    })
  })
})

type SessionStateSnapshot = {
  phase: string
  user: { id: string; username: string } | null
  secondFactorExpiresAt: string | null
}

function isValidSessionSnapshot(state: SessionStateSnapshot): boolean {
  if (state.phase === 'AUTHENTICATED') {
    return state.user !== null && state.secondFactorExpiresAt === null
  }
  if (state.phase === 'TOTP_REQUIRED') {
    return state.user === null && state.secondFactorExpiresAt !== null
  }
  return state.user === null && state.secondFactorExpiresAt === null
}
