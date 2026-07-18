import type { AxiosInstance } from 'axios'

import { normalizeContentBlock, type ContentBlockDto } from '@/types/blocks'
import { ApiProblem, type VersionedDraft } from '@/types/api'
import type {
  CreateProjectWorkspaceRequest,
  Localized,
  ProjectCopy,
  ProjectMediaDto,
  ProjectTaxonomyRefDto,
  ProjectWorkspaceDto,
  SaveWorkspaceRequest,
  TaxonomyWorkspaceDto,
  UpdateTaxonomyRequest,
} from '@/types/content'

import { http } from './http'

const UUID = /^[0-9a-f]{8}-(?:[0-9a-f]{4}-){3}[0-9a-f]{12}$/i
const SLUG = /^[a-z0-9]+(?:-[a-z0-9]+)*$/
const JAVA_INT_MAX = 2_147_483_647
const MUTATION_VERSION_MAX = Number.MAX_SAFE_INTEGER - 1
const PROJECT_MEDIA_USAGES = new Set(['COVER', 'CARD', 'DETAIL'])
const PROJECT_MEDIA_LAYOUTS = new Set(['wide', 'standard'])

function isRecord(value: unknown): value is Record<string, unknown> {
  if (typeof value !== 'object' || value === null || Array.isArray(value)) return false
  const prototype = Object.getPrototypeOf(value)
  return prototype === Object.prototype || prototype === null
}

function hasOwn(value: Record<string, unknown>, key: string): boolean {
  return Object.prototype.hasOwnProperty.call(value, key)
}

function hasExactKeys(value: unknown, keys: readonly string[]): value is Record<string, unknown> {
  if (!isRecord(value)) return false
  const actual = Object.keys(value)
  return actual.length === keys.length && keys.every((key) => hasOwn(value, key))
}

function isUuid(value: unknown): value is string {
  return typeof value === 'string' && UUID.test(value)
}

function sameUuid(left: string, right: string): boolean {
  return left.toLowerCase() === right.toLowerCase()
}

function isVersion(value: unknown): value is number {
  return typeof value === 'number' && Number.isSafeInteger(value) && value >= 0
}

function isSortOrder(value: unknown): value is number {
  return isVersion(value) && value <= JAVA_INT_MAX
}

function isPersistedHttpsUrl(value: unknown): value is string {
  if (typeof value !== 'string' || !value.startsWith('https://')) return false
  try {
    return new URL(value).protocol === 'https:'
  } catch {
    return false
  }
}

function normalizeLocalized<Value>(
  value: unknown,
  normalizeLeaf: (candidate: unknown) => Value | null,
): Localized<Value> | null {
  if (!hasExactKeys(value, ['zh-CN', 'en'])) return null
  const chinese = normalizeLeaf(value['zh-CN'])
  const english = normalizeLeaf(value.en)
  if (chinese === null || english === null) return null
  return { 'zh-CN': chinese, en: english }
}

function normalizeString(value: unknown): string | null {
  return typeof value === 'string' ? value : null
}

function normalizeProjectCopy(value: unknown): ProjectCopy | null {
  const keys = ['status', 'eyebrow', 'title', 'summary', 'seoTitle', 'seoDescription']
  if (!hasExactKeys(value, keys) || !keys.every((key) => typeof value[key] === 'string')) {
    return null
  }
  return {
    status: value.status as string,
    eyebrow: value.eyebrow as string,
    title: value.title as string,
    summary: value.summary as string,
    seoTitle: value.seoTitle as string,
    seoDescription: value.seoDescription as string,
  }
}

function normalizeTaxonomyRef(value: unknown): ProjectTaxonomyRefDto | null {
  if (
    !hasExactKeys(value, ['id', 'normalizedKey', 'sortOrder', 'names']) ||
    !isUuid(value.id) ||
    typeof value.normalizedKey !== 'string' ||
    value.normalizedKey.trim().length === 0 ||
    !isSortOrder(value.sortOrder)
  ) {
    return null
  }
  const names = normalizeLocalized(value.names, normalizeString)
  return names === null
    ? null
    : {
        id: value.id,
        normalizedKey: value.normalizedKey,
        sortOrder: value.sortOrder,
        names,
      }
}

function normalizeTaxonomyRefs(value: unknown): ProjectTaxonomyRefDto[] | null {
  if (!Array.isArray(value)) return null
  const result: ProjectTaxonomyRefDto[] = []
  const ids = new Set<string>()
  const sortOrders = new Set<number>()
  const normalizedKeys = new Set<string>()
  for (const candidate of value) {
    const item = normalizeTaxonomyRef(candidate)
    const id = item?.id.toLowerCase()
    if (
      item === null ||
      ids.has(id!) ||
      sortOrders.has(item.sortOrder) ||
      normalizedKeys.has(item.normalizedKey)
    ) {
      return null
    }
    ids.add(id!)
    sortOrders.add(item.sortOrder)
    normalizedKeys.add(item.normalizedKey)
    result.push(item)
  }
  return result
}

function normalizeProjectMedia(value: unknown): ProjectMediaDto | null {
  if (
    !hasExactKeys(value, [
      'assetId',
      'usage',
      'sortOrder',
      'layout',
      'objectPosition',
      'credit',
      'sourceUrl',
    ]) ||
    !isUuid(value.assetId) ||
    typeof value.usage !== 'string' ||
    !PROJECT_MEDIA_USAGES.has(value.usage) ||
    !isSortOrder(value.sortOrder) ||
    typeof value.layout !== 'string' ||
    !PROJECT_MEDIA_LAYOUTS.has(value.layout) ||
    typeof value.objectPosition !== 'string' ||
    typeof value.credit !== 'string' ||
    !isPersistedHttpsUrl(value.sourceUrl)
  ) {
    return null
  }
  return {
    assetId: value.assetId,
    usage: value.usage as ProjectMediaDto['usage'],
    sortOrder: value.sortOrder,
    layout: value.layout as ProjectMediaDto['layout'],
    objectPosition: value.objectPosition,
    credit: value.credit,
    sourceUrl: value.sourceUrl,
  }
}

function normalizeProjectMediaList(value: unknown): ProjectMediaDto[] | null {
  if (!Array.isArray(value)) return null
  const result: ProjectMediaDto[] = []
  const assetUsages = new Set<string>()
  const usageOrders = new Set<string>()
  for (const candidate of value) {
    const item = normalizeProjectMedia(candidate)
    if (item === null) return null
    const assetUsage = `${item.assetId.toLowerCase()}\u0000${item.usage}`
    const usageOrder = `${item.usage}\u0000${item.sortOrder}`
    if (assetUsages.has(assetUsage) || usageOrders.has(usageOrder)) return null
    assetUsages.add(assetUsage)
    usageOrders.add(usageOrder)
    result.push(item)
  }
  return result
}

function normalizeBlocks(value: unknown): ContentBlockDto[] | null {
  if (!Array.isArray(value)) return null
  const result: ContentBlockDto[] = []
  const nestedIds = new Set<string>()
  const blockSortOrders = new Set<number>()

  for (const candidate of value) {
    const block = normalizeContentBlock(candidate)
    if (
      block === null ||
      !isSortOrder(block.sortOrder) ||
      nestedIds.has(block.id.toLowerCase()) ||
      blockSortOrders.has(block.sortOrder)
    ) {
      return null
    }
    nestedIds.add(block.id.toLowerCase())
    blockSortOrders.add(block.sortOrder)

    if (block.payload.type === 'IMAGE' && block.payload.mediaAssetId === null) return null
    if (block.payload.type === 'GALLERY') {
      const mediaIds = block.payload.mediaAssetIds.map((id) => id.toLowerCase())
      if (new Set(mediaIds).size !== mediaIds.length) return null
    }
    if (block.payload.type === 'METRICS') {
      const metricSortOrders = new Set<number>()
      for (const metric of block.payload.metrics) {
        const metricId = metric.id.toLowerCase()
        if (
          !isSortOrder(metric.sortOrder) ||
          nestedIds.has(metricId) ||
          metricSortOrders.has(metric.sortOrder)
        ) {
          return null
        }
        nestedIds.add(metricId)
        metricSortOrders.add(metric.sortOrder)
      }
    }
    result.push(block)
  }
  return result
}

function normalizeProjectWorkspace(value: unknown): ProjectWorkspaceDto | null {
  if (
    !hasExactKeys(value, [
      'id',
      'externalKey',
      'slug',
      'number',
      'sortOrder',
      'featured',
      'visible',
      'publicationDirty',
      'version',
      'translations',
      'tags',
      'skills',
      'media',
      'blocks',
    ]) ||
    !isUuid(value.id) ||
    typeof value.externalKey !== 'string' ||
    value.externalKey.trim().length === 0 ||
    value.externalKey.length > 96 ||
    typeof value.slug !== 'string' ||
    !SLUG.test(value.slug) ||
    value.slug.length > 120 ||
    typeof value.number !== 'string' ||
    value.number.length > 16 ||
    !isSortOrder(value.sortOrder) ||
    typeof value.featured !== 'boolean' ||
    typeof value.visible !== 'boolean' ||
    typeof value.publicationDirty !== 'boolean' ||
    !isVersion(value.version)
  ) {
    return null
  }

  const translations = normalizeLocalized(value.translations, normalizeProjectCopy)
  const tags = normalizeTaxonomyRefs(value.tags)
  const skills = normalizeTaxonomyRefs(value.skills)
  const media = normalizeProjectMediaList(value.media)
  const blocks = normalizeBlocks(value.blocks)
  if (
    translations === null ||
    tags === null ||
    skills === null ||
    media === null ||
    blocks === null
  ) {
    return null
  }

  return {
    id: value.id,
    externalKey: value.externalKey,
    slug: value.slug,
    number: value.number,
    sortOrder: value.sortOrder,
    featured: value.featured,
    visible: value.visible,
    publicationDirty: value.publicationDirty,
    version: value.version,
    translations,
    tags,
    skills,
    media,
    blocks,
  }
}

function normalizeTaxonomyWorkspace(value: unknown): TaxonomyWorkspaceDto | null {
  if (
    !hasExactKeys(value, ['id', 'normalizedKey', 'version', 'names']) ||
    !isUuid(value.id) ||
    typeof value.normalizedKey !== 'string' ||
    value.normalizedKey.trim().length === 0 ||
    !isVersion(value.version)
  ) {
    return null
  }
  const names = normalizeLocalized(value.names, normalizeString)
  return names === null
    ? null
    : {
        id: value.id,
        normalizedKey: value.normalizedKey,
        version: value.version,
        names,
      }
}

function invalidServerResponse(): ApiProblem {
  return new ApiProblem({
    type: 'invalid_server_response',
    title: '服务器返回了无效的项目数据',
    status: 0,
    code: 'INVALID_SERVER_RESPONSE',
    traceId: 'client',
  })
}

function requireProjectWorkspace(value: unknown): ProjectWorkspaceDto {
  const workspace = normalizeProjectWorkspace(value)
  if (workspace === null) throw invalidServerResponse()
  return workspace
}

function requireProjectList(value: unknown): ProjectWorkspaceDto[] {
  if (!Array.isArray(value)) throw invalidServerResponse()
  const result: ProjectWorkspaceDto[] = []
  const ids = new Set<string>()
  const sortOrders = new Set<number>()
  const externalKeys = new Set<string>()
  const slugs = new Set<string>()
  for (const candidate of value) {
    const workspace = normalizeProjectWorkspace(candidate)
    const id = workspace?.id.toLowerCase()
    if (
      workspace === null ||
      ids.has(id!) ||
      sortOrders.has(workspace.sortOrder) ||
      externalKeys.has(workspace.externalKey) ||
      slugs.has(workspace.slug)
    ) {
      throw invalidServerResponse()
    }
    ids.add(id!)
    sortOrders.add(workspace.sortOrder)
    externalKeys.add(workspace.externalKey)
    slugs.add(workspace.slug)
    result.push(workspace)
  }
  return result
}

function requireTaxonomyList(value: unknown): TaxonomyWorkspaceDto[] {
  if (!Array.isArray(value)) throw invalidServerResponse()
  const result: TaxonomyWorkspaceDto[] = []
  const ids = new Set<string>()
  const keys = new Set<string>()
  for (const candidate of value) {
    const item = normalizeTaxonomyWorkspace(candidate)
    const id = item?.id.toLowerCase()
    if (item === null || ids.has(id!) || keys.has(item.normalizedKey)) {
      throw invalidServerResponse()
    }
    ids.add(id!)
    keys.add(item.normalizedKey)
    result.push(item)
  }
  return result
}

function requireProjectId(value: string): string {
  if (!isUuid(value)) throw new TypeError('project id must be a UUID')
  return value
}

function requireTaxonomyId(value: string): string {
  if (!isUuid(value)) throw new TypeError('taxonomy id must be a UUID')
  return value
}

function requireExpectedVersion(value: number): number {
  if (!isVersion(value) || value > MUTATION_VERSION_MAX) {
    throw new RangeError('expectedVersion must be a non-negative safe integer')
  }
  return value
}

function sameNames(left: Localized<string>, right: Localized<string>): boolean {
  return left['zh-CN'] === right['zh-CN'] && left.en === right.en
}

function requireTaxonomyRequest(request: UpdateTaxonomyRequest): void {
  requireExpectedVersion(request.expectedVersion)
  const names = normalizeLocalized(request.names, normalizeString)
  if (
    names === null ||
    names['zh-CN'].trim().length === 0 ||
    names.en.trim().length === 0
  ) {
    throw new TypeError('taxonomy names must contain non-blank zh-CN and en values')
  }
}

function draft(workspace: ProjectWorkspaceDto): VersionedDraft<ProjectWorkspaceDto> {
  return { version: workspace.version, value: workspace }
}

export interface ProjectApi {
  list(): Promise<ProjectWorkspaceDto[]>
  create(workspace: ProjectWorkspaceDto): Promise<VersionedDraft<ProjectWorkspaceDto>>
  get(projectId: string): Promise<VersionedDraft<ProjectWorkspaceDto>>
  save(
    projectId: string,
    request: SaveWorkspaceRequest<ProjectWorkspaceDto>,
  ): Promise<VersionedDraft<ProjectWorkspaceDto>>
  tags(): Promise<TaxonomyWorkspaceDto[]>
  skills(): Promise<TaxonomyWorkspaceDto[]>
  updateTag(id: string, request: UpdateTaxonomyRequest): Promise<TaxonomyWorkspaceDto>
  updateSkill(id: string, request: UpdateTaxonomyRequest): Promise<TaxonomyWorkspaceDto>
}

export function createProjectApi(client: AxiosInstance): ProjectApi {
  async function updateTaxonomy(
    kind: 'tags' | 'skills',
    idValue: string,
    request: UpdateTaxonomyRequest,
  ): Promise<TaxonomyWorkspaceDto> {
    const id = requireTaxonomyId(idValue)
    requireTaxonomyRequest(request)
    const response = await client.put<unknown>(
      `/api/admin/${kind}/${encodeURIComponent(id)}`,
      request,
    )
    const result = normalizeTaxonomyWorkspace(response.data)
    if (
      result === null ||
      !sameUuid(result.id, id) ||
      result.version !== request.expectedVersion + 1 ||
      !sameNames(result.names, request.names)
    ) {
      throw invalidServerResponse()
    }
    return result
  }

  return {
    async list() {
      const response = await client.get<unknown>('/api/admin/projects')
      return requireProjectList(response.data)
    },

    async create(workspace) {
      const body: CreateProjectWorkspaceRequest = { workspace }
      const response = await client.post<unknown>('/api/admin/projects', body)
      const result = requireProjectWorkspace(response.data)
      if (
        result.version !== 0 ||
        !result.publicationDirty ||
        result.externalKey !== workspace.externalKey
      ) {
        throw invalidServerResponse()
      }
      return draft(result)
    },

    async get(projectIdValue) {
      const projectId = requireProjectId(projectIdValue)
      const response = await client.get<unknown>(
        `/api/admin/projects/${encodeURIComponent(projectId)}/workspace`,
      )
      const result = requireProjectWorkspace(response.data)
      if (!sameUuid(result.id, projectId)) throw invalidServerResponse()
      return draft(result)
    },

    async save(projectIdValue, request) {
      const projectId = requireProjectId(projectIdValue)
      const expectedVersion = requireExpectedVersion(request.expectedVersion)
      if (!isUuid(request.workspace.id) || !sameUuid(projectId, request.workspace.id)) {
        throw new Error('project path/body id mismatch')
      }
      if (!isVersion(request.workspace.version)) {
        throw new RangeError('workspace.version must be a non-negative safe integer')
      }
      if (expectedVersion !== request.workspace.version) {
        throw new Error('expectedVersion must match workspace.version')
      }
      const body: SaveWorkspaceRequest<ProjectWorkspaceDto> = {
        expectedVersion,
        workspace: request.workspace,
      }
      const response = await client.put<unknown>(
        `/api/admin/projects/${encodeURIComponent(projectId)}/workspace`,
        body,
      )
      const result = requireProjectWorkspace(response.data)
      if (
        !sameUuid(result.id, projectId) ||
        result.externalKey !== request.workspace.externalKey ||
        result.version !== expectedVersion + 1 ||
        !result.publicationDirty
      ) {
        throw invalidServerResponse()
      }
      return draft(result)
    },

    async tags() {
      const response = await client.get<unknown>('/api/admin/tags')
      return requireTaxonomyList(response.data)
    },

    async skills() {
      const response = await client.get<unknown>('/api/admin/skills')
      return requireTaxonomyList(response.data)
    },

    async updateTag(id, request) {
      return updateTaxonomy('tags', id, request)
    },

    async updateSkill(id, request) {
      return updateTaxonomy('skills', id, request)
    },
  }
}

export const projectApi = createProjectApi(http)
