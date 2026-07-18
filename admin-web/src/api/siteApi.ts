import type { AxiosInstance, AxiosResponse } from 'axios'

import { ApiProblem, type VersionedDraft } from '@/types/api'
import type { SaveWorkspaceRequest, SiteWorkspaceDto } from '@/types/content'

import { http } from './http'

const SITE_ID = '00000000-0000-0000-0000-000000000001'
const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i
const LOCAL_DATE = /^\d{4}-\d{2}-\d{2}$/

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value)
}

function isExactRecord(
  value: unknown,
  keys: readonly string[],
): value is Record<string, unknown> {
  if (!isRecord(value)) return false
  const actual = Object.keys(value)
  return (
    actual.length === keys.length &&
    keys.every((key) => Object.prototype.hasOwnProperty.call(value, key))
  )
}

function hasStringFields(value: unknown, keys: readonly string[]): boolean {
  return isExactRecord(value, keys) && keys.every((key) => typeof value[key] === 'string')
}

function isUuid(value: unknown): value is string {
  return typeof value === 'string' && UUID.test(value)
}

function isVersion(value: unknown): value is number {
  return Number.isSafeInteger(value) && (value as number) >= 0
}

function isSortOrder(value: unknown): value is number {
  return Number.isSafeInteger(value) && (value as number) >= 0
}

function isLocalized(
  value: unknown,
  item: (candidate: unknown) => boolean,
): boolean {
  return (
    isExactRecord(value, ['zh-CN', 'en']) && item(value['zh-CN']) && item(value.en)
  )
}

function isString(value: unknown): value is string {
  return typeof value === 'string'
}

function isIdentityCopy(value: unknown): boolean {
  return hasStringFields(value, ['displayName', 'secondaryName'])
}

function isSeoCopy(value: unknown): boolean {
  return hasStringFields(value, ['title', 'description'])
}

function isAccessibilityCopy(value: unknown): boolean {
  return hasStringFields(value, [
    'skip',
    'primaryNav',
    'mobileNav',
    'openMenu',
    'closeMenu',
    'language',
    'backToTop',
    'projectTags',
  ])
}

function isNavigationItem(value: unknown): boolean {
  return (
    isExactRecord(value, ['id', 'target', 'sortOrder', 'visible', 'labels']) &&
    isUuid(value.id) &&
    typeof value.target === 'string' &&
    isSortOrder(value.sortOrder) &&
    typeof value.visible === 'boolean' &&
    isLocalized(value.labels, isString)
  )
}

function isHeroCopy(value: unknown): boolean {
  return hasStringFields(value, [
    'eyebrow',
    'displayName',
    'secondaryName',
    'role',
    'headline',
    'introduction',
    'availability',
    'primaryCta',
    'secondaryCta',
    'visualLabel',
    'stageLabel',
  ])
}

function isHero(value: unknown): boolean {
  if (
    !isExactRecord(value, [
      'id',
      'version',
      'mediaAssetId',
      'objectPosition',
      'credit',
      'sourceUrl',
      'copy',
    ]) ||
    !isUuid(value.id) ||
    !isVersion(value.version) ||
    !isLocalized(value.copy, isHeroCopy)
  ) {
    return false
  }

  const tuple = [value.mediaAssetId, value.objectPosition, value.credit, value.sourceUrl]
  if (tuple.every((item) => item === null)) return true
  return (
    isUuid(value.mediaAssetId) &&
    typeof value.objectPosition === 'string' &&
    typeof value.credit === 'string' &&
    typeof value.sourceUrl === 'string'
  )
}

function normalizeOmittedHeroMedia(value: unknown): unknown {
  if (!isExactRecord(value, ['id', 'version', 'copy'])) return value
  return {
    ...value,
    mediaAssetId: null,
    objectPosition: null,
    credit: null,
    sourceUrl: null,
  }
}

function isAboutCopy(value: unknown): boolean {
  return hasStringFields(value, [
    'label',
    'title',
    'statement',
    'focusLabel',
    'focusTitle',
    'focusIntro',
  ])
}

function isLabelValueCopy(value: unknown): boolean {
  return hasStringFields(value, ['label', 'value'])
}

function isProfileFact(value: unknown): boolean {
  return (
    isExactRecord(value, ['id', 'externalKey', 'sortOrder', 'copy']) &&
    isUuid(value.id) &&
    typeof value.externalKey === 'string' &&
    isSortOrder(value.sortOrder) &&
    isLocalized(value.copy, isLabelValueCopy)
  )
}

function isSkillStatusCopy(value: unknown): boolean {
  return hasStringFields(value, ['name', 'status'])
}

function isProfileSkill(value: unknown): boolean {
  return (
    isExactRecord(value, ['id', 'externalKey', 'sortOrder', 'copy']) &&
    isUuid(value.id) &&
    typeof value.externalKey === 'string' &&
    isSortOrder(value.sortOrder) &&
    isLocalized(value.copy, isSkillStatusCopy)
  )
}

function isWorkCopy(value: unknown): boolean {
  return hasStringFields(value, [
    'label',
    'title',
    'introduction',
    'imageNotice',
    'openSlotLabel',
    'openSlotTitle',
    'openSlotText',
    'openSlotMeta',
  ])
}

function isRoadmapHeaderCopy(value: unknown): boolean {
  return hasStringFields(value, ['label', 'title', 'introduction'])
}

function isRoadmapOutcome(value: unknown): boolean {
  return (
    isExactRecord(value, ['id', 'sortOrder', 'text']) &&
    isUuid(value.id) &&
    isSortOrder(value.sortOrder) &&
    isLocalized(value.text, isString)
  )
}

function isRoadmapStageCopy(value: unknown): boolean {
  return hasStringFields(value, ['period', 'title', 'summary'])
}

function isRoadmapStage(value: unknown): boolean {
  return (
    isExactRecord(value, [
      'id',
      'externalKey',
      'number',
      'sortOrder',
      'visible',
      'copy',
      'outcomes',
    ]) &&
    isUuid(value.id) &&
    typeof value.externalKey === 'string' &&
    typeof value.number === 'string' &&
    isSortOrder(value.sortOrder) &&
    typeof value.visible === 'boolean' &&
    isLocalized(value.copy, isRoadmapStageCopy) &&
    Array.isArray(value.outcomes) &&
    value.outcomes.every(isRoadmapOutcome)
  )
}

function isRoadmap(value: unknown): boolean {
  return (
    isExactRecord(value, ['header', 'stages']) &&
    isLocalized(value.header, isRoadmapHeaderCopy) &&
    Array.isArray(value.stages) &&
    value.stages.every(isRoadmapStage)
  )
}

function isContactCopy(value: unknown): boolean {
  return hasStringFields(value, [
    'label',
    'title',
    'introduction',
    'emailLabel',
    'workCta',
    'roadmapCta',
    'footerNote',
  ])
}

function isPrivacyCopy(value: unknown): boolean {
  return hasStringFields(value, ['title', 'bodyMarkdown'])
}

function isSocialLink(value: unknown): boolean {
  return (
    isExactRecord(value, ['id', 'platform', 'url', 'sortOrder', 'visible']) &&
    isUuid(value.id) &&
    typeof value.platform === 'string' &&
    typeof value.url === 'string' &&
    isSortOrder(value.sortOrder) &&
    typeof value.visible === 'boolean'
  )
}

function isLocalDate(value: unknown): value is string {
  if (typeof value !== 'string' || !LOCAL_DATE.test(value)) return false
  const date = new Date(`${value}T00:00:00.000Z`)
  return Number.isFinite(date.getTime()) && date.toISOString().slice(0, 10) === value
}

function isResumeDocument(value: unknown): boolean {
  return (
    isExactRecord(value, [
      'id',
      'locale',
      'mediaAssetId',
      'versionLabel',
      'current',
      'documentDate',
    ]) &&
    isUuid(value.id) &&
    (value.locale === 'zh-CN' || value.locale === 'en') &&
    isUuid(value.mediaAssetId) &&
    typeof value.versionLabel === 'string' &&
    typeof value.current === 'boolean' &&
    isLocalDate(value.documentDate)
  )
}

function isSiteWorkspace(value: unknown): value is SiteWorkspaceDto {
  return (
    isExactRecord(value, [
      'siteId',
      'version',
      'monogram',
      'email',
      'identity',
      'seo',
      'accessibility',
      'navigation',
      'hero',
      'about',
      'facts',
      'profileSkills',
      'work',
      'roadmap',
      'contact',
      'privacy',
      'socialLinks',
      'resumes',
    ]) &&
    value.siteId === SITE_ID &&
    isVersion(value.version) &&
    typeof value.monogram === 'string' &&
    typeof value.email === 'string' &&
    isLocalized(value.identity, isIdentityCopy) &&
    isLocalized(value.seo, isSeoCopy) &&
    isLocalized(value.accessibility, isAccessibilityCopy) &&
    Array.isArray(value.navigation) &&
    value.navigation.every(isNavigationItem) &&
    isHero(value.hero) &&
    isLocalized(value.about, isAboutCopy) &&
    Array.isArray(value.facts) &&
    value.facts.every(isProfileFact) &&
    Array.isArray(value.profileSkills) &&
    value.profileSkills.every(isProfileSkill) &&
    isLocalized(value.work, isWorkCopy) &&
    isRoadmap(value.roadmap) &&
    isLocalized(value.contact, isContactCopy) &&
    isLocalized(value.privacy, isPrivacyCopy) &&
    Array.isArray(value.socialLinks) &&
    value.socialLinks.every(isSocialLink) &&
    Array.isArray(value.resumes) &&
    value.resumes.every(isResumeDocument)
  )
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

function requireSiteWorkspace(value: unknown): SiteWorkspaceDto {
  if (!isRecord(value)) throw invalidServerResponse()
  const hero = normalizeOmittedHeroMedia(value.hero)
  const normalized = hero === value.hero ? value : { ...value, hero }
  if (!isSiteWorkspace(normalized)) throw invalidServerResponse()
  return normalized
}

function draft(workspace: SiteWorkspaceDto): VersionedDraft<SiteWorkspaceDto> {
  return { version: workspace.version, value: workspace }
}

export function createSiteApi(client: AxiosInstance) {
  return {
    async get(): Promise<VersionedDraft<SiteWorkspaceDto>> {
      const response = await client.get<unknown>('/api/admin/site/workspace')
      return draft(requireSiteWorkspace(response.data))
    },

    async save(
      request: SaveWorkspaceRequest<SiteWorkspaceDto>,
    ): Promise<VersionedDraft<SiteWorkspaceDto>> {
      const body: SaveWorkspaceRequest<SiteWorkspaceDto> = {
        expectedVersion: request.expectedVersion,
        workspace: request.workspace,
      }
      const response = await client.put<
        unknown,
        AxiosResponse<unknown>,
        SaveWorkspaceRequest<SiteWorkspaceDto>
      >('/api/admin/site/workspace', body)
      return draft(requireSiteWorkspace(response.data))
    },
  }
}

export const siteApi = createSiteApi(http)
