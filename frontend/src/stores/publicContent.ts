import { shallowRef } from 'vue'
import { portfolioApi, type PortfolioApi } from '@/services/portfolioApi'
import { matchesInitialRoute, type PageDescriptor } from '@/services/initialPayload'
import type { Locale, PageBootstrap, ProjectCard, PublicProject, PublicSite } from '@/types/public'

export interface HomeContent { site: PublicSite; catalog: ProjectCard[] }
export interface ProjectContent extends HomeContent { project: PublicProject }
export interface PrivacyContent { site: PublicSite }

export function createPublicContentStore(api: PortfolioApi, bootstrap: PageBootstrap | null) {
  let initial = bootstrap
  const cache = new Map<string, unknown>()

  const takeInitial = (descriptor: PageDescriptor) => {
    const candidate = initial
    initial = null
    return candidate && matchesInitialRoute(candidate, descriptor) ? candidate : null
  }

  const cached = async <T>(key: string, load: () => Promise<T>): Promise<T> => {
    if (cache.has(key)) return cache.get(key) as T
    const value = await load()
    cache.set(key, value)
    return value
  }

  return {
    replaceInitial(value: PageBootstrap | null) { initial = value },
    clear() { initial = null; cache.clear() },
    async loadHome(locale: Locale, signal?: AbortSignal): Promise<HomeContent> {
      const candidate = takeInitial({ kind: 'home', locale })
      if (candidate?.kind === 'home') return { site: candidate.site, catalog: candidate.catalog }
      return cached(`home:${locale}`, async () => {
        const [site, catalog] = await Promise.all([api.getSite(locale, signal), api.getProjects(locale, signal)])
        return { site: site.data, catalog: catalog.data }
      })
    },
    async loadProject(locale: Locale, slug: string, signal?: AbortSignal): Promise<ProjectContent> {
      const candidate = takeInitial({ kind: 'project', locale, slug })
      if (candidate?.kind === 'project') return { site: candidate.site, catalog: candidate.catalog, project: candidate.project }
      return cached(`project:${locale}:${slug}`, async () => {
        const [site, catalog, project] = await Promise.all([
          api.getSite(locale, signal), api.getProjects(locale, signal), api.getProject(locale, slug, signal),
        ])
        return { site: site.data, catalog: catalog.data, project: project.data }
      })
    },
    async loadPrivacy(locale: Locale, signal?: AbortSignal): Promise<PrivacyContent> {
      const candidate = takeInitial({ kind: 'privacy', locale })
      if (candidate?.kind === 'privacy') return { site: candidate.site }
      return cached(`privacy:${locale}`, async () => ({ site: (await api.getSite(locale, signal)).data }))
    },
  }
}

export const currentSite = shallowRef<PublicSite | null>(null)
export const currentProjectId = shallowRef<string | null>(null)
export const publicContentStore = createPublicContentStore(portfolioApi, null)
