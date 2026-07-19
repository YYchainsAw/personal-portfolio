import { publicCsrfClient, type CsrfTokenProvider } from './csrfClient'
import { PUBLIC_INTERACTION_ENDPOINTS, type ContactReceipt, type ContactRequest } from '@/types/interactions'

export type ContactErrorKind = 'aborted' | 'validation' | 'rate-limited' | 'retryable' | 'malformed'
export class ContactSubmissionError extends Error {
  constructor(
    readonly kind: ContactErrorKind,
    readonly fieldErrors: Record<string, string> = {},
    readonly retryAfterSeconds: number | null = null,
  ) {
    super(`Contact submission ${kind}`)
    this.name = 'ContactSubmissionError'
  }
}

const allowedFields = new Set(['name', 'email', 'subject', 'message', 'website', 'privacyAccepted'])

function retryAfter(value: string | null, now = Date.now()): number | null {
  if (!value) return null
  const seconds = /^\d+$/.test(value.trim()) ? Number(value) : Math.ceil((Date.parse(value) - now) / 1000)
  return Number.isFinite(seconds) && seconds >= 0 ? Math.min(seconds, 3600) : null
}

function problemFields(value: unknown): Record<string, string> {
  if (!value || typeof value !== 'object') return {}
  const body = value as Record<string, unknown>
  const candidate = body.fieldErrors
  if (!candidate || typeof candidate !== 'object') return {}
  return Object.fromEntries(Object.entries(candidate as Record<string, unknown>)
    .filter(([key, message]) => allowedFields.has(key) && typeof message === 'string')
    .map(([key, message]) => [key, String(message).slice(0, 200)]))
}

export function createContactApi(
  fetchImpl: typeof fetch = fetch,
  csrf: CsrfTokenProvider = publicCsrfClient,
) {
  return {
    async submit(request: ContactRequest, signal?: AbortSignal): Promise<ContactReceipt> {
      if (signal?.aborted) throw new ContactSubmissionError('aborted')
      let token
      try { token = await csrf.ensureToken() } catch { throw new ContactSubmissionError('retryable') }
      if (signal?.aborted) throw new ContactSubmissionError('aborted')

      let response: Response
      try {
        response = await fetchImpl(PUBLIC_INTERACTION_ENDPOINTS.contact, {
          method: 'POST', credentials: 'same-origin', signal,
          headers: { Accept: 'application/json', 'Content-Type': 'application/json', [token.headerName]: token.token },
          body: JSON.stringify(request),
        })
      } catch (cause) {
        if (signal?.aborted || (cause instanceof DOMException && cause.name === 'AbortError')) throw new ContactSubmissionError('aborted')
        throw new ContactSubmissionError('retryable')
      }

      let body: unknown = null
      try { body = await response.json() } catch { /* classified by status below */ }
      if (response.status === 202) {
        if (!body || typeof body !== 'object' || Object.keys(body).length !== 1 || (body as Record<string, unknown>).accepted !== true) {
          throw new ContactSubmissionError('malformed')
        }
        return { accepted: true }
      }

      const problem = body && typeof body === 'object' ? body as Record<string, unknown> : {}
      if (response.status === 403 && problem.code === 'CSRF_INVALID') {
        csrf.invalidate()
        throw new ContactSubmissionError('retryable')
      }
      if (response.status === 422 && problem.code === 'VALIDATION_ERROR') {
        throw new ContactSubmissionError('validation', problemFields(body))
      }
      if (response.status === 429) throw new ContactSubmissionError('rate-limited', {}, retryAfter(response.headers.get('Retry-After')))
      if (response.status >= 500) throw new ContactSubmissionError('retryable')
      throw new ContactSubmissionError('malformed')
    },
  }
}

export const contactApi = createContactApi()
