import type {
  Locale,
  ProjectCard,
  PublicApiProblemBody,
  PublicProject,
  PublicSite,
  PublishedEnvelope,
} from '@/types/public'

const fallbackProblem = (status: number): PublicApiProblemBody => ({
  type: 'about:blank',
  title: status === 0 ? 'Unable to connect' : 'Unable to load this page',
  status,
  code: status === 0 ? 'NETWORK_ERROR' : 'UNEXPECTED_RESPONSE',
  traceId: 'client',
})

const isProblem = (value: unknown): value is PublicApiProblemBody => {
  if (!value || typeof value !== 'object') return false
  const item = value as Record<string, unknown>
  return typeof item.type === 'string' && typeof item.title === 'string'
    && typeof item.status === 'number' && typeof item.code === 'string'
    && typeof item.traceId === 'string'
}

const isEnvelope = <T>(value: unknown): value is PublishedEnvelope<T> => {
  if (!value || typeof value !== 'object') return false
  const item = value as Record<string, unknown>
  return Number.isFinite(item.revisionVersion) && typeof item.checksum === 'string'
    && item.checksum.trim().length > 0 && 'data' in item
}

export class PublicApiProblem extends Error {
  constructor(readonly body: PublicApiProblemBody) {
    super(body.title)
    this.name = 'PublicApiProblem'
  }
}

export interface PortfolioApi {
  getSite(locale: Locale, signal?: AbortSignal): Promise<PublishedEnvelope<PublicSite>>
  getProjects(locale: Locale, signal?: AbortSignal): Promise<PublishedEnvelope<ProjectCard[]>>
  getProject(locale: Locale, slug: string, signal?: AbortSignal): Promise<PublishedEnvelope<PublicProject>>
}

export function createPortfolioApi(fetcher: typeof fetch = fetch): PortfolioApi {
  async function get<T>(url: string, signal?: AbortSignal): Promise<PublishedEnvelope<T>> {
    let response: Response
    try {
      response = await fetcher(url, {
        method: 'GET',
        credentials: 'same-origin',
        cache: 'no-cache',
        headers: { Accept: 'application/json' },
        signal,
      })
    } catch (cause) {
      if (cause instanceof DOMException && cause.name === 'AbortError') throw cause
      throw new PublicApiProblem(fallbackProblem(0))
    }

    let body: unknown
    try {
      body = await response.json()
    } catch {
      throw new PublicApiProblem(fallbackProblem(response.status))
    }

    if (!response.ok) throw new PublicApiProblem(isProblem(body) ? body : fallbackProblem(response.status))
    if (!isEnvelope<T>(body)) throw new PublicApiProblem(fallbackProblem(response.status))
    return body
  }

  return {
    getSite: (locale, signal) => get(`/api/public/site?locale=${encodeURIComponent(locale)}`, signal),
    getProjects: (locale, signal) => get(`/api/public/projects?locale=${encodeURIComponent(locale)}`, signal),
    getProject: (locale, slug, signal) => get(`/api/public/projects/${encodeURIComponent(slug)}?locale=${encodeURIComponent(locale)}`, signal),
  }
}

export const portfolioApi = createPortfolioApi()
