import type { Locale, ProjectCard, PublicMedia, PublicSite } from '@/types/public'

export interface HomeViewModel {
  locale: Locale
  identity: PublicSite['identity']
  copy: Omit<PublicSite, 'identity' | 'seo' | 'privacy' | 'socialLinks' | 'resume'>
  heroAsset: PublicMedia | null
  projects: ProjectCard[]
  contact: PublicSite['contact']
  socialLinks: PublicSite['socialLinks']
  resume: PublicSite['resume']
}

export function mapHomeViewModel(locale: Locale, site: PublicSite, catalog: ProjectCard[]): HomeViewModel {
  const { identity, hero, seo: _seo, privacy: _privacy, socialLinks, resume, ...copy } = site
  return {
    locale,
    identity,
    copy: { ...copy, hero },
    heroAsset: hero.media,
    projects: [...catalog].sort((a, b) => a.sortOrder - b.sortOrder),
    contact: site.contact,
    socialLinks,
    resume,
  }
}
