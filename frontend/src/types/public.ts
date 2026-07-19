export const locales = ['zh-CN', 'en'] as const
export type Locale = (typeof locales)[number]
export type Localized<T> = Record<Locale, T>

export const isLocale = (value: unknown): value is Locale =>
  typeof value === 'string' && locales.includes(value as Locale)

export interface PublicApiProblemBody {
  type: string
  title: string
  status: number
  code: string
  traceId: string
  fieldErrors?: Record<string, string>
}

export interface PublishedEnvelope<T> {
  revisionVersion: number
  checksum: string
  data: T
}

export interface PublicMedia {
  assetId: string
  variant: string
  src: string
  srcset: string
  alt: string
  caption: string
  credit: string
  sourceUrl: string
  width: number
  height: number
}

export interface PublicSeo { title: string; description: string }
export interface PublicIdentity { monogram: string; displayName: string; secondaryName: string; email: string }
export interface PublicAccessibility {
  skip: string
  primaryNav: string
  mobileNav: string
  openMenu: string
  closeMenu: string
  language: string
  backToTop: string
  projectTags: string
}
export interface PublicNavigationItem { target: string; sortOrder: number; label: string }
export interface PublicHero {
  eyebrow: string
  displayName: string
  secondaryName: string
  role: string
  headline: string
  introduction: string
  availability: string
  primaryCta: string
  secondaryCta: string
  visualLabel: string
  stageLabel: string
  objectPosition: string
  credit: string
  sourceUrl: string
  media: PublicMedia | null
}
export interface PublicFact { label: string; value: string }
export interface PublicSkill { name: string; status: string }
export interface PublicAbout {
  label: string
  title: string
  statement: string
  focusLabel: string
  focusTitle: string
  focusIntro: string
  facts: PublicFact[]
  skills: PublicSkill[]
}
export interface PublicWork {
  label: string
  title: string
  introduction: string
  imageNotice: string
  openSlotLabel: string
  openSlotTitle: string
  openSlotText: string
  openSlotMeta: string
}
export interface PublicRoadmapStage {
  id: string
  number: string
  period: string
  title: string
  summary: string
  outcomes: string[]
}
export interface PublicRoadmap {
  label: string
  title: string
  introduction: string
  stages: PublicRoadmapStage[]
}
export interface PublicContact {
  label: string
  title: string
  introduction: string
  emailLabel: string
  email: string
  workCta: string
  roadmapCta: string
  footerNote: string
}
export interface PublicPrivacy { title: string; html: string }
export interface PublicSocialLink { platform: string; url: string }
export interface PublicResume { label: string; documentDate: string; href: string }
export interface PublicSite {
  identity: PublicIdentity
  seo: PublicSeo
  accessibility: PublicAccessibility
  navigation: PublicNavigationItem[]
  hero: PublicHero
  about: PublicAbout
  work: PublicWork
  roadmap: PublicRoadmap
  contact: PublicContact
  privacy: PublicPrivacy
  socialLinks: PublicSocialLink[]
  resume: PublicResume
}

export interface ProjectCard {
  projectId: string
  slug: string
  number: string
  sortOrder: number
  featured: boolean
  status: string
  eyebrow: string
  title: string
  summary: string
  tags: string[]
  cover: PublicMedia
}

export const publicBlockWidths = ['NARROW', 'STANDARD', 'WIDE', 'FULL'] as const
export const publicBlockAlignments = ['LEFT', 'CENTER', 'RIGHT'] as const
export const publicBlockEmphases = ['NONE', 'SOFT', 'STRONG'] as const
export const publicVideoProviders = ['youtube', 'vimeo', 'bilibili'] as const
export type PublicBlockWidth = (typeof publicBlockWidths)[number]
export type PublicBlockAlignment = (typeof publicBlockAlignments)[number]
export type PublicBlockEmphasis = (typeof publicBlockEmphases)[number]
export type PublicVideoProvider = (typeof publicVideoProviders)[number]

interface PublicBlockBase<T extends string, P> {
  id: string
  type: T
  sortOrder: number
  width: PublicBlockWidth
  alignment: PublicBlockAlignment
  emphasis: PublicBlockEmphasis
  columns: number
  payload: P & { type: T }
}

export interface PublicMetric {
  id: string
  numericValue: number | null
  label: string
  value: string
  suffix: string
}

export type PublicBlock =
  | PublicBlockBase<'MARKDOWN', { html: string }>
  | PublicBlockBase<'IMAGE', { media: PublicMedia }>
  | PublicBlockBase<'GALLERY', { media: PublicMedia[] }>
  | PublicBlockBase<'VIDEO', { provider: PublicVideoProvider; embedUrl: string; cover: PublicMedia | null; title: string; description: string }>
  | PublicBlockBase<'CODE', { code: string; language: string; showLineNumbers: boolean; title: string; description: string }>
  | PublicBlockBase<'QUOTE', { quote: string; source: string }>
  | PublicBlockBase<'METRICS', { metrics: PublicMetric[] }>
  | PublicBlockBase<'DOWNLOAD', { href: string; label: string; description: string; mimeType: string | null; byteSize: number | null }>
  | PublicBlockBase<'LINK', { href: string; openNewTab: boolean; label: string; description: string }>

export interface PublicProject {
  projectId: string
  slug: string
  number: string
  featured: boolean
  status: string
  eyebrow: string
  title: string
  summary: string
  seoTitle: string
  seoDescription: string
  tags: string[]
  skills: string[]
  media: PublicMedia[]
  blocks: PublicBlock[]
}

export type PageBootstrap =
  | { kind: 'home'; locale: Locale; site: PublicSite; catalog: ProjectCard[] }
  | { kind: 'project'; locale: Locale; site: PublicSite; catalog: ProjectCard[]; project: PublicProject }
  | { kind: 'privacy'; locale: Locale; site: PublicSite }
