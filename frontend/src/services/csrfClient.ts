export const CSRF_ENDPOINT = '/api/admin/auth/csrf'

export interface CsrfResponse {
  headerName: string
  parameterName: string
  token: string
}

export interface CsrfTokenProvider {
  ensureToken(): Promise<CsrfResponse>
  invalidate(): void
}

const isCsrfResponse = (value: unknown): value is CsrfResponse => {
  if (!value || typeof value !== 'object') return false
  const item = value as Record<string, unknown>
  const keys = ['headerName', 'parameterName', 'token']
  return Object.keys(item).sort().join('|') === keys.sort().join('|')
    && keys.every((key) => typeof item[key] === 'string' && (item[key] as string).trim().length > 0)
}

export function createCsrfClient(fetchImpl: typeof fetch = fetch): CsrfTokenProvider {
  let cached: CsrfResponse | null = null
  let pending: Promise<CsrfResponse> | null = null

  return {
    async ensureToken() {
      if (cached) return cached
      if (pending) return pending
      pending = (async () => {
        try {
          const response = await fetchImpl(CSRF_ENDPOINT, {
            method: 'GET', credentials: 'same-origin', cache: 'no-store', headers: { Accept: 'application/json' },
          })
          if (response.status !== 200) throw new Error('CSRF token unavailable')
          const body: unknown = await response.json()
          if (!isCsrfResponse(body)) throw new Error('CSRF token unavailable')
          cached = { headerName: body.headerName, parameterName: body.parameterName, token: body.token }
          return cached
        } catch {
          cached = null
          throw new Error('CSRF token unavailable')
        } finally {
          pending = null
        }
      })()
      return pending
    },
    invalidate() { cached = null },
  }
}

export const publicCsrfClient = createCsrfClient()
