import { shallowRef } from 'vue'

import { ApiProblem } from '@/types/api'
import type { MeResponse, PasswordStageResponse, SecondFactorMethod } from '@/types/auth'

export interface AuthPort {
  getMe(): Promise<MeResponse>
  ensureCsrf(): Promise<void>
  passwordStage(username: string, password: string): Promise<PasswordStageResponse>
  secondFactor(method: SecondFactorMethod, code: string): Promise<MeResponse>
  logout(): Promise<void>
}

export type SessionPhase = 'UNKNOWN' | 'ANONYMOUS' | 'TOTP_REQUIRED' | 'AUTHENTICATED'

export type SessionState =
  | { readonly phase: 'UNKNOWN'; readonly user: null; readonly secondFactorExpiresAt: null }
  | { readonly phase: 'ANONYMOUS'; readonly user: null; readonly secondFactorExpiresAt: null }
  | {
      readonly phase: 'TOTP_REQUIRED'
      readonly user: null
      readonly secondFactorExpiresAt: string
    }
  | {
      readonly phase: 'AUTHENTICATED'
      readonly user: MeResponse
      readonly secondFactorExpiresAt: null
    }

export interface SessionStore {
  readonly state: Readonly<SessionState>
  bootstrap(): Promise<SessionPhase>
  login(username: string, password: string): Promise<void>
  verifySecondFactor(method: SecondFactorMethod, code: string): Promise<void>
  logout(): Promise<void>
  invalidate(): void
}

function isAuthenticationRequired(cause: unknown): cause is ApiProblem {
  return (
    cause instanceof ApiProblem &&
    cause.body.status === 401 &&
    cause.body.code === 'AUTHENTICATION_REQUIRED'
  )
}

function sessionChangedProblem(): ApiProblem {
  return new ApiProblem({
    type: 'client_session_changed',
    title: '会话状态已更新，请重试',
    status: 0,
    code: 'SESSION_STATE_CHANGED',
    traceId: 'client',
  })
}

function authenticationOperationInProgressProblem(): ApiProblem {
  return new ApiProblem({
    type: 'client_authentication_operation_in_progress',
    title: '认证请求正在处理中',
    status: 0,
    code: 'AUTHENTICATION_OPERATION_IN_PROGRESS',
    traceId: 'client',
  })
}

function secondFactorNotActiveProblem(): ApiProblem {
  return new ApiProblem({
    type: 'client_second_factor_not_active',
    title: '二次验证已失效，请重新登录',
    status: 0,
    code: 'SECOND_FACTOR_NOT_ACTIVE',
    traceId: 'client',
  })
}

function freezeState(next: SessionState): SessionState {
  if (next.phase === 'AUTHENTICATED') {
    return Object.freeze({ ...next, user: Object.freeze({ ...next.user }) })
  }
  return Object.freeze({ ...next })
}

export function createSessionStore(port: AuthPort): SessionStore {
  const current = shallowRef<SessionState>(freezeState({
    phase: 'UNKNOWN',
    user: null,
    secondFactorExpiresAt: null,
  }))
  let generation = 0
  let bootstrapInFlight: Promise<SessionPhase> | null = null
  let authenticationMutationInFlight = false

  function transition(next: SessionState): void {
    current.value = freezeState(next)
  }

  function beginOperation(): number {
    generation += 1
    return generation
  }

  function requireCurrent(operation: number): void {
    if (operation !== generation) throw sessionChangedProblem()
  }

  function invalidate(): void {
    beginOperation()
    transition({ phase: 'ANONYMOUS', user: null, secondFactorExpiresAt: null })
  }

  async function runAuthenticationMutation<T>(operation: () => Promise<T>): Promise<T> {
    if (authenticationMutationInFlight) throw authenticationOperationInProgressProblem()
    authenticationMutationInFlight = true
    try {
      return await operation()
    } finally {
      authenticationMutationInFlight = false
    }
  }

  function bootstrap(): Promise<SessionPhase> {
    if (current.value.phase !== 'UNKNOWN') return Promise.resolve(current.value.phase)
    if (bootstrapInFlight !== null) return bootstrapInFlight

    const operation = beginOperation()
    const task = (async (): Promise<SessionPhase> => {
      try {
        const user = await port.getMe()
        if (operation === generation) {
          transition({
            phase: 'AUTHENTICATED',
            user,
            secondFactorExpiresAt: null,
          })
        }
      } catch (cause) {
        if (operation !== generation) return current.value.phase
        if (!isAuthenticationRequired(cause)) throw cause
        transition({ phase: 'ANONYMOUS', user: null, secondFactorExpiresAt: null })
      }
      return current.value.phase
    })()

    bootstrapInFlight = task
    const clear = () => {
      if (bootstrapInFlight === task) bootstrapInFlight = null
    }
    task.then(clear, clear)
    return task
  }

  function login(username: string, password: string): Promise<void> {
    return runAuthenticationMutation(async () => {
      const operation = beginOperation()
      transition({ phase: 'ANONYMOUS', user: null, secondFactorExpiresAt: null })
      await port.ensureCsrf()
      requireCurrent(operation)
      const response = await port.passwordStage(username, password)
      requireCurrent(operation)
      transition({
        phase: 'TOTP_REQUIRED',
        user: null,
        secondFactorExpiresAt: response.expiresAt,
      })
    })
  }

  function verifySecondFactor(method: SecondFactorMethod, code: string): Promise<void> {
    return runAuthenticationMutation(async () => {
      if (current.value.phase !== 'TOTP_REQUIRED') throw secondFactorNotActiveProblem()
      const operation = beginOperation()
      const user = await port.secondFactor(method, code)
      requireCurrent(operation)
      transition({ phase: 'AUTHENTICATED', user, secondFactorExpiresAt: null })
    })
  }

  function logout(): Promise<void> {
    return runAuthenticationMutation(async () => {
      const operation = beginOperation()
      try {
        await port.logout()
      } catch (cause) {
        if (isAuthenticationRequired(cause)) {
          invalidate()
          return
        }
        throw cause
      }
      requireCurrent(operation)
      transition({ phase: 'ANONYMOUS', user: null, secondFactorExpiresAt: null })
    })
  }

  return Object.freeze({
    get state() {
      return current.value
    },
    bootstrap,
    login,
    verifySecondFactor,
    logout,
    invalidate,
  })
}
