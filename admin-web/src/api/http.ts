import axios, { type InternalAxiosRequestConfig } from 'axios'

import { ApiProblem, type ApiProblemBody, type FieldErrors } from '@/types/api'

const MAX_PROBLEM_TYPE_LENGTH = 256
const MAX_PROBLEM_TITLE_LENGTH = 512
const MAX_PROBLEM_CODE_LENGTH = 128
const MAX_TRACE_ID_LENGTH = 128
const MAX_FIELD_ERRORS = 100
const MAX_FIELD_NAME_LENGTH = 128
const MAX_FIELD_ERROR_LENGTH = 512
const MAX_RETRY_AFTER_SECONDS = 86_400

type AuthenticationRequiredHandler = () => void

const authenticationRequiredHandlers = new Set<AuthenticationRequiredHandler>()

export const http = axios.create({
  baseURL: '/',
  allowAbsoluteUrls: false,
  withCredentials: true,
  xsrfCookieName: 'XSRF-TOKEN',
  xsrfHeaderName: 'X-XSRF-TOKEN',
  timeout: 15_000,
})

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value)
}

function isBoundedText(value: unknown, maximumLength: number): value is string {
  return typeof value === 'string' && value.trim().length > 0 && value.length <= maximumLength
}

function normalizeFieldErrors(value: unknown): FieldErrors | undefined | null {
  if (value === undefined) return undefined
  if (!isRecord(value)) return null
  const entries = Object.entries(value)
  if (entries.length > MAX_FIELD_ERRORS) return null
  const safe: Record<string, string> = {}
  for (const [field, message] of entries) {
    if (
      !isBoundedText(field, MAX_FIELD_NAME_LENGTH) ||
      !isBoundedText(message, MAX_FIELD_ERROR_LENGTH)
    ) {
      return null
    }
    safe[field] = message
  }
  return safe
}

function normalizeApiProblemBody(value: unknown, responseStatus: unknown): ApiProblemBody | null {
  if (!isRecord(value)) return null
  if (
    !Number.isInteger(responseStatus) ||
    !Number.isInteger(value.status) ||
    value.status !== responseStatus ||
    (value.status as number) < 400 ||
    (value.status as number) > 599 ||
    !isBoundedText(value.type, MAX_PROBLEM_TYPE_LENGTH) ||
    !isBoundedText(value.title, MAX_PROBLEM_TITLE_LENGTH) ||
    !isBoundedText(value.code, MAX_PROBLEM_CODE_LENGTH) ||
    !isBoundedText(value.traceId, MAX_TRACE_ID_LENGTH)
  ) {
    return null
  }

  const fieldErrors = normalizeFieldErrors(value.fieldErrors)
  if (fieldErrors === null) return null

  const retryAfterSeconds =
    Number.isInteger(value.retryAfterSeconds) &&
    (value.retryAfterSeconds as number) > 0 &&
    (value.retryAfterSeconds as number) <= MAX_RETRY_AFTER_SECONDS
      ? (value.retryAfterSeconds as number)
      : undefined

  return {
    type: value.type,
    title: value.title,
    status: value.status as number,
    code: value.code,
    traceId: value.traceId,
    ...(fieldErrors === undefined ? {} : { fieldErrors }),
    ...(retryAfterSeconds === undefined ? {} : { retryAfterSeconds }),
  }
}

function networkProblem(): ApiProblem {
  return new ApiProblem({
    type: 'network_error',
    title: '无法连接服务器',
    status: 0,
    code: 'NETWORK_ERROR',
    traceId: 'client',
  })
}

function crossOriginProblem(): ApiProblem {
  return new ApiProblem({
    type: 'client_security_error',
    title: '已阻止非同源请求',
    status: 0,
    code: 'CROSS_ORIGIN_REQUEST_BLOCKED',
    traceId: 'client',
  })
}

function isStrictSameOriginPath(value: string): boolean {
  return /^\/(?!\/)[^\\\u0000-\u001f\u007f]*$/.test(value)
}

function enforcePathOnlyRequest(config: InternalAxiosRequestConfig): InternalAxiosRequestConfig {
  if (
    typeof config.url !== 'string' ||
    !isStrictSameOriginPath(config.url) ||
    config.baseURL !== '/'
  ) {
    throw crossOriginProblem()
  }
  return config
}

function parseRetryAfter(headers: unknown): number | undefined {
  let value: unknown
  if (isRecord(headers) && typeof headers.get === 'function') {
    try {
      value = (headers.get as (name: string) => unknown)('retry-after')
    } catch {
      return undefined
    }
  } else if (isRecord(headers)) {
    value = headers['retry-after'] ?? headers['Retry-After']
  }
  if (typeof value !== 'string' || !/^[1-9][0-9]{0,5}$/.test(value)) return undefined
  const seconds = Number(value)
  return seconds <= MAX_RETRY_AFTER_SECONDS ? seconds : undefined
}

export function toApiProblem(error: unknown): ApiProblem {
  if (error instanceof ApiProblem) return error
  if (axios.isAxiosError(error)) {
    const body = normalizeApiProblemBody(error.response?.data, error.response?.status)
    if (body !== null) {
      const retryAfterSeconds = body.retryAfterSeconds ?? parseRetryAfter(error.response?.headers)
      return new ApiProblem({
        ...body,
        ...(retryAfterSeconds === undefined ? {} : { retryAfterSeconds }),
      })
    }
  }
  return networkProblem()
}

export function onAuthenticationRequired(handler: AuthenticationRequiredHandler): () => void {
  authenticationRequiredHandlers.add(handler)
  return () => authenticationRequiredHandlers.delete(handler)
}

http.interceptors.request.use(enforcePathOnlyRequest)

http.interceptors.response.use(
  (response) => response,
  (error: unknown) => {
    const problem = toApiProblem(error)
    if (problem.body.status === 401 && problem.body.code === 'AUTHENTICATION_REQUIRED') {
      for (const handler of [...authenticationRequiredHandlers]) {
        try {
          handler()
        } catch {
          // Session invalidation must never replace the original safe API problem.
        }
      }
    }
    return Promise.reject(problem)
  },
)
