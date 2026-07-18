import type { ContentBlockDto } from './blocks'

export const locales = Object.freeze(['zh-CN', 'en'] as const)

export type Locale = (typeof locales)[number]

export type Localized<T> = Record<Locale, T>

export interface SaveWorkspaceRequest<T> {
  readonly expectedVersion: number
  readonly workspace: T
}

export interface TranslationCompletion {
  readonly complete: number
  readonly total: number
}

export type TranslationStatus = Readonly<Record<Locale, Readonly<TranslationCompletion>>>

export interface IdentityCopy {
  displayName: string
  secondaryName: string
}

export interface SeoCopy {
  title: string
  description: string
}

export interface AccessibilityCopy {
  skip: string
  primaryNav: string
  mobileNav: string
  openMenu: string
  closeMenu: string
  language: string
  backToTop: string
  projectTags: string
}

export interface NavigationItem {
  id: string
  target: string
  sortOrder: number
  visible: boolean
  labels: Localized<string>
}

export interface HeroCopy {
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
}

export interface Hero {
  id: string
  version: number
  mediaAssetId: string | null
  objectPosition: string | null
  credit: string | null
  sourceUrl: string | null
  copy: Localized<HeroCopy>
}

export interface AboutCopy {
  label: string
  title: string
  statement: string
  focusLabel: string
  focusTitle: string
  focusIntro: string
}

export interface LabelValueCopy {
  label: string
  value: string
}

export interface ProfileFact {
  id: string
  externalKey: string
  sortOrder: number
  copy: Localized<LabelValueCopy>
}

export interface SkillStatusCopy {
  name: string
  status: string
}

export interface ProfileSkill {
  id: string
  externalKey: string
  sortOrder: number
  copy: Localized<SkillStatusCopy>
}

export interface WorkCopy {
  label: string
  title: string
  introduction: string
  imageNotice: string
  openSlotLabel: string
  openSlotTitle: string
  openSlotText: string
  openSlotMeta: string
}

export interface RoadmapHeaderCopy {
  label: string
  title: string
  introduction: string
}

export interface RoadmapOutcome {
  id: string
  sortOrder: number
  text: Localized<string>
}

export interface RoadmapStageCopy {
  period: string
  title: string
  summary: string
}

export interface RoadmapStage {
  id: string
  externalKey: string
  number: string
  sortOrder: number
  visible: boolean
  copy: Localized<RoadmapStageCopy>
  outcomes: RoadmapOutcome[]
}

export interface Roadmap {
  header: Localized<RoadmapHeaderCopy>
  stages: RoadmapStage[]
}

export interface ContactCopy {
  label: string
  title: string
  introduction: string
  emailLabel: string
  workCta: string
  roadmapCta: string
  footerNote: string
}

export interface PrivacyCopy {
  title: string
  bodyMarkdown: string
}

export interface SocialLink {
  id: string
  platform: string
  url: string
  sortOrder: number
  visible: boolean
}

export interface ResumeDocument {
  id: string
  locale: Locale
  mediaAssetId: string
  versionLabel: string
  current: boolean
  documentDate: string
}

export interface SiteWorkspaceDto {
  siteId: string
  version: number
  monogram: string
  email: string
  identity: Localized<IdentityCopy>
  seo: Localized<SeoCopy>
  accessibility: Localized<AccessibilityCopy>
  navigation: NavigationItem[]
  hero: Hero
  about: Localized<AboutCopy>
  facts: ProfileFact[]
  profileSkills: ProfileSkill[]
  work: Localized<WorkCopy>
  roadmap: Roadmap
  contact: Localized<ContactCopy>
  privacy: Localized<PrivacyCopy>
  socialLinks: SocialLink[]
  resumes: ResumeDocument[]
}

export type MediaKind = 'IMAGE' | 'PDF' | 'FILE'

export type MediaMimeType = 'image/jpeg' | 'image/png' | 'application/pdf'

export type MediaStatus =
  | 'PROCESSING'
  | 'READY'
  | 'FAILED'
  | 'ARCHIVED'
  | 'PENDING_DELETE'

export type MediaVariantStatus = 'PROCESSING' | 'READY' | 'FAILED'

export interface MediaTranslationView {
  locale: Locale
  altText: string
  caption: string
  credit: string
  sourceUrl: string | null
}

/** Exact plan-02 bilingual mutation row. Both locales must be submitted together. */
export interface MediaTranslationInput {
  locale: Locale
  altText: string
  caption: string
  credit: string
  sourceUrl: string | null
}

export interface MediaVariantView {
  name: string
  width: number | null
  height: number | null
  status: MediaVariantStatus
}

/** Exact plan-02 management response; it intentionally has no kind or previewUrl field. */
export interface MediaAssetView {
  id: string
  originalFilename: string
  mimeType: MediaMimeType
  byteSize: number
  width: number | null
  height: number | null
  sha256: string
  status: MediaStatus
  version: number
  createdAt: string
  updatedAt: string
  translations: MediaTranslationView[]
  variants: MediaVariantView[]
}

export interface MediaPageView {
  items: MediaAssetView[]
  page: number
  size: number
  totalItems: number
  totalPages: number
}

/** UI-normalized selectable media; mediaApi derives kind and constructs previewUrl locally. */
export interface MediaAssetSummaryDto {
  id: string
  kind: MediaKind
  originalFilename: string
  mimeType: MediaMimeType
  status: MediaStatus
  previewUrl: string | null
  width: number | null
  height: number | null
}

export interface ProjectCopy {
  status: string
  eyebrow: string
  title: string
  summary: string
  seoTitle: string
  seoDescription: string
}

export interface ProjectTaxonomyRefDto {
  id: string
  normalizedKey: string
  sortOrder: number
  names: Localized<string>
}

export interface TaxonomyWorkspaceDto {
  id: string
  normalizedKey: string
  version: number
  names: Localized<string>
}

export interface UpdateTaxonomyRequest {
  expectedVersion: number
  names: Localized<string>
}

export type ProjectMediaUsage = 'COVER' | 'CARD' | 'DETAIL'
export type ProjectMediaLayout = 'wide' | 'standard'

export interface ProjectMediaDto {
  assetId: string
  usage: ProjectMediaUsage
  sortOrder: number
  layout: ProjectMediaLayout
  objectPosition: string
  credit: string
  sourceUrl: string
}

export interface ProjectWorkspaceDto {
  id: string
  externalKey: string
  slug: string
  number: string
  sortOrder: number
  featured: boolean
  visible: boolean
  publicationDirty: boolean
  version: number
  translations: Localized<ProjectCopy>
  tags: ProjectTaxonomyRefDto[]
  skills: ProjectTaxonomyRefDto[]
  media: ProjectMediaDto[]
  blocks: ContentBlockDto[]
}

/** UI-only project catalog row derived from the full management workspace response. */
export interface ProjectListItemDto {
  id: string
  slug: string
  number: string
  sortOrder: number
  featured: boolean
  visible: boolean
  publicationDirty: boolean
  title: Localized<string>
  status: Localized<string>
  workspaceVersion: number
}

export interface CreateProjectWorkspaceRequest {
  workspace: ProjectWorkspaceDto
}
