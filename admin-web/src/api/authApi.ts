import type { AxiosInstance, AxiosResponse } from 'axios'

import { ApiProblem } from '@/types/api'
import type {
  CsrfResponse,
  MeResponse,
  PasswordStageRequest,
  PasswordStageResponse,
  SecondFactorMethod,
  SecondFactorRequest,
} from '@/types/auth'
import type { AuthPort } from '@/stores/session'

import { http } from './http'

const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i
const INSTANT = /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d{1,9})?Z$/

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value)
}

function invalidServerResponse(): ApiProblem {
  return new ApiProblem({
    type: 'invalid_server_response',
    title: '服务器响应无效',
    status: 0,
    code: 'INVALID_SERVER_RESPONSE',
    traceId: 'client',
  })
}

function requireMe(value: unknown): MeResponse {
  if (
    !isRecord(value) ||
    typeof value.id !== 'string' ||
    !UUID.test(value.id) ||
    typeof value.username !== 'string' ||
    value.username.trim() !== value.username ||
    value.username.length === 0 ||
    value.username.length > 64
  ) {
    throw invalidServerResponse()
  }
  return { id: value.id, username: value.username }
}

function requirePasswordStage(value: unknown): PasswordStageResponse {
  if (
    !isRecord(value) ||
    value.next !== 'SECOND_FACTOR' ||
    typeof value.expiresAt !== 'string' ||
    !INSTANT.test(value.expiresAt) ||
    !Number.isFinite(Date.parse(value.expiresAt))
  ) {
    throw invalidServerResponse()
  }
  return { next: 'SECOND_FACTOR', expiresAt: value.expiresAt }
}

function requireCsrf(value: unknown): void {
  if (
    !isRecord(value) ||
    value.headerName !== 'X-XSRF-TOKEN' ||
    value.parameterName !== '_csrf' ||
    typeof value.token !== 'string' ||
    value.token.length === 0 ||
    value.token.length > 4096
  ) {
    throw invalidServerResponse()
  }
}

export function createAuthApi(client: AxiosInstance): AuthPort {
  return {
    async getMe() {
      const response = await client.get<unknown>('/api/admin/auth/me')
      return requireMe(response.data)
    },
    async ensureCsrf() {
      const response = await client.get<unknown>('/api/admin/auth/csrf')
      requireCsrf(response.data)
    },
    async passwordStage(username, password) {
      const body: PasswordStageRequest = { username, password }
      const response = await client.post<
        unknown,
        AxiosResponse<unknown>,
        PasswordStageRequest
      >('/api/admin/auth/password', body)
      return requirePasswordStage(response.data)
    },
    async secondFactor(method: SecondFactorMethod, code: string) {
      const body: SecondFactorRequest = { method, code }
      const response = await client.post<unknown, AxiosResponse<unknown>, SecondFactorRequest>(
        '/api/admin/auth/second-factor',
        body,
      )
      return requireMe(response.data)
    },
    async logout() {
      await client.post<void>('/api/admin/auth/logout')
    },
  }
}

export const authApi = createAuthApi(http)
