import type { AxiosInstance } from 'axios'

import { ApiProblem } from '@/types/api'
import {
  PROJECT_CATALOG_ID,
  SITE_ID,
  type AggregateType,
  type ArchiveProjectCommand,
  type PreviewTokenRequest,
  type PreviewTokenResponse,
  type PublicationResultDto,
  type PublicationStateDto,
  type PublicationStatus,
  type PublishProjectCommand,
  type PublishSiteCommand,
  type PublishTarget,
  type ReorderCatalogCommand,
  type RestoreRevisionRequest,
  type RevisionSummaryDto,
} from '@/types/publishing'

import { http } from './http'

const UUID = /^[0-9a-f]{8}-(?:[0-9a-f]{4}-){3}[0-9a-f]{12}$/i
const LOWERCASE_SHA256 = /^[0-9a-f]{64}$/
const BASE64URL = /^[A-Za-z0-9_-]+$/
const UTC_INSTANT =
  /^(\d{4})-(\d{2})-(\d{2})T(\d{2}):(\d{2}):(\d{2})(?:\.(\d{1,9}))?Z$/
const AGGREGATE_TYPES = new Set<AggregateType>(['SITE', 'PROJECT', 'PROJECT_CATALOG'])
const PUBLICATION_STATUSES = new Set<PublicationStatus>([
  'UNPUBLISHED',
  'PUBLISHED',
  'ARCHIVED',
])
const JAVA_INT_MAX = 2_147_483_647
const MUTATION_VERSION_MAX = Number.MAX_SAFE_INTEGER - 1
const MAXIMUM_TOKEN_CHARACTERS = 2_048
const SIGNATURE_CHARACTERS = 43

type RecordValue = Record<string, unknown>

export interface PublishingApi {
  createPreview(request: PreviewTokenRequest): Promise<PreviewTokenResponse>
  preflightPreview(token: string): Promise<void>
  previewUrl(token: string): string
  publishSite(command: PublishSiteCommand): Promise<PublicationResultDto>
  publishProject(command: PublishProjectCommand): Promise<PublicationResultDto>
  archiveProject(command: ArchiveProjectCommand): Promise<PublicationResultDto>
  reorderCatalog(command: ReorderCatalogCommand): Promise<PublicationResultDto>
  history(type: AggregateType, id: string): Promise<RevisionSummaryDto[]>
  restore(revisionId: string, request: RestoreRevisionRequest): Promise<void>
  state(type: AggregateType, id: string): Promise<PublicationStateDto>
  publishTarget(target: PublishTarget): Promise<PublicationResultDto>
}

function isRecord(value: unknown): value is RecordValue {
  if (typeof value !== 'object' || value === null || Array.isArray(value)) return false
  const prototype = Object.getPrototypeOf(value)
  return prototype === Object.prototype || prototype === null
}

function hasOwn(value: RecordValue, key: string): boolean {
  return Object.prototype.hasOwnProperty.call(value, key)
}

function hasExactKeys(value: unknown, keys: readonly string[]): value is RecordValue {
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

function isAggregateType(value: unknown): value is AggregateType {
  return typeof value === 'string' && AGGREGATE_TYPES.has(value as AggregateType)
}

function isNonnegativeVersion(value: unknown): value is number {
  return typeof value === 'number' && Number.isSafeInteger(value) && value >= 0
}

function isMutationVersion(value: unknown): value is number {
  return isNonnegativeVersion(value) && value <= MUTATION_VERSION_MAX
}

function isPositiveVersion(value: unknown): value is number {
  return typeof value === 'number' && Number.isSafeInteger(value) && value > 0
}

function isSchemaVersion(value: unknown): value is number {
  return isPositiveVersion(value) && value <= JAVA_INT_MAX
}

function daysInMonth(year: number, month: number): number {
  if (month === 2) {
    const leap = year % 4 === 0 && (year % 100 !== 0 || year % 400 === 0)
    return leap ? 29 : 28
  }
  return [4, 6, 9, 11].includes(month) ? 30 : 31
}

function isUtcInstant(value: unknown): value is string {
  if (typeof value !== 'string') return false
  const match = UTC_INSTANT.exec(value)
  if (match === null) return false
  const year = Number(match[1])
  const month = Number(match[2])
  const day = Number(match[3])
  const hour = Number(match[4])
  const minute = Number(match[5])
  const second = Number(match[6])
  return (
    year >= 1 &&
    month >= 1 &&
    month <= 12 &&
    day >= 1 &&
    day <= daysInMonth(year, month) &&
    hour >= 0 &&
    hour <= 23 &&
    minute >= 0 &&
    minute <= 59 &&
    second >= 0 &&
    second <= 59 &&
    Number.isFinite(Date.parse(value))
  )
}

function isPreviewToken(value: unknown): value is string {
  if (typeof value !== 'string' || value.length === 0 || value.length > MAXIMUM_TOKEN_CHARACTERS) {
    return false
  }
  const segments = value.split('.')
  const payload = segments[0]
  const signature = segments[1]
  return (
    segments.length === 2 &&
    payload !== undefined &&
    signature !== undefined &&
    payload.length % 4 !== 1 &&
    signature.length === SIGNATURE_CHARACTERS &&
    BASE64URL.test(payload) &&
    BASE64URL.test(signature)
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

function requireAggregateIdentity(type: unknown, id: unknown): asserts type is AggregateType {
  if (!isAggregateType(type) || !isUuid(id)) {
    throw new TypeError('publication aggregate identity is invalid')
  }
  if (
    (type === 'SITE' && !sameUuid(id, SITE_ID)) ||
    (type === 'PROJECT_CATALOG' && !sameUuid(id, PROJECT_CATALOG_ID)) ||
    (type === 'PROJECT' && (sameUuid(id, SITE_ID) || sameUuid(id, PROJECT_CATALOG_ID)))
  ) {
    throw new TypeError('publication aggregate identity is invalid')
  }
}

function requirePreviewRequest(value: unknown): PreviewTokenRequest {
  if (
    !hasExactKeys(value, ['aggregateType', 'aggregateId', 'workspaceVersion']) ||
    (value.aggregateType !== 'SITE' && value.aggregateType !== 'PROJECT')
  ) {
    throw new TypeError('preview token request is invalid')
  }
  requireAggregateIdentity(value.aggregateType, value.aggregateId)
  if (!isMutationVersion(value.workspaceVersion)) {
    throw new TypeError('preview token request is invalid')
  }
  return {
    aggregateType: value.aggregateType,
    aggregateId: value.aggregateId as string,
    workspaceVersion: value.workspaceVersion,
  }
}

function normalizePreviewResponse(value: unknown): PreviewTokenResponse {
  if (
    !hasExactKeys(value, ['token', 'expiresAt']) ||
    !isPreviewToken(value.token) ||
    !isUtcInstant(value.expiresAt) ||
    Date.parse(value.expiresAt) <= Date.now()
  ) {
    throw invalidServerResponse()
  }
  return { token: value.token, expiresAt: value.expiresAt }
}

function requireSiteCommand(value: unknown): PublishSiteCommand {
  if (
    !hasExactKeys(value, ['expectedWorkspaceVersion', 'expectedPublicationVersion']) ||
    !isMutationVersion(value.expectedWorkspaceVersion) ||
    !isMutationVersion(value.expectedPublicationVersion)
  ) {
    throw new TypeError('site publication command is invalid')
  }
  return {
    expectedWorkspaceVersion: value.expectedWorkspaceVersion,
    expectedPublicationVersion: value.expectedPublicationVersion,
  }
}

function requireProjectCommand(value: unknown): PublishProjectCommand {
  if (
    !hasExactKeys(value, [
      'projectId',
      'expectedWorkspaceVersion',
      'expectedProjectPublicationVersion',
      'expectedCatalogVersion',
    ]) ||
    !isMutationVersion(value.expectedWorkspaceVersion) ||
    !isMutationVersion(value.expectedProjectPublicationVersion) ||
    !isMutationVersion(value.expectedCatalogVersion)
  ) {
    throw new TypeError('project publication command is invalid')
  }
  requireAggregateIdentity('PROJECT', value.projectId)
  return {
    projectId: value.projectId as string,
    expectedWorkspaceVersion: value.expectedWorkspaceVersion,
    expectedProjectPublicationVersion: value.expectedProjectPublicationVersion,
    expectedCatalogVersion: value.expectedCatalogVersion,
  }
}

function requireArchiveCommand(value: unknown): ArchiveProjectCommand {
  if (
    !hasExactKeys(value, [
      'projectId',
      'expectedProjectPublicationVersion',
      'expectedCatalogVersion',
    ]) ||
    !isMutationVersion(value.expectedProjectPublicationVersion) ||
    !isMutationVersion(value.expectedCatalogVersion)
  ) {
    throw new TypeError('project archive command is invalid')
  }
  requireAggregateIdentity('PROJECT', value.projectId)
  return {
    projectId: value.projectId as string,
    expectedProjectPublicationVersion: value.expectedProjectPublicationVersion,
    expectedCatalogVersion: value.expectedCatalogVersion,
  }
}

function normalizeUniqueUuidList(value: unknown): string[] | null {
  if (!Array.isArray(value)) return null
  const normalized: string[] = []
  const ids = new Set<string>()
  for (const candidate of value) {
    if (!isUuid(candidate)) return null
    const canonical = candidate.toLowerCase()
    if (ids.has(canonical)) return null
    ids.add(canonical)
    normalized.push(candidate)
  }
  return normalized
}

function requireReorderCommand(value: unknown): ReorderCatalogCommand {
  if (
    !hasExactKeys(value, ['expectedCatalogVersion', 'projectIdsInOrder']) ||
    !isMutationVersion(value.expectedCatalogVersion)
  ) {
    throw new TypeError('catalog reorder command is invalid')
  }
  const projectIdsInOrder = normalizeUniqueUuidList(value.projectIdsInOrder)
  if (projectIdsInOrder === null) {
    throw new TypeError('catalog reorder command is invalid')
  }
  for (const id of projectIdsInOrder) requireAggregateIdentity('PROJECT', id)
  return { expectedCatalogVersion: value.expectedCatalogVersion, projectIdsInOrder }
}

function normalizePublicationResult(value: unknown): PublicationResultDto {
  if (!isRecord(value)) throw invalidServerResponse()
  const hasCatalogRevision = hasOwn(value, 'catalogRevisionId')
  const hasCatalogVersion = hasOwn(value, 'catalogVersion')
  const expectedKeys = hasCatalogRevision || hasCatalogVersion
    ? ['revisionId', 'aggregateVersion', 'catalogRevisionId', 'catalogVersion', 'checksum']
    : ['revisionId', 'aggregateVersion', 'checksum']

  if (
    hasCatalogRevision !== hasCatalogVersion ||
    !hasExactKeys(value, expectedKeys) ||
    !isUuid(value.revisionId) ||
    !isPositiveVersion(value.aggregateVersion) ||
    typeof value.checksum !== 'string' ||
    !LOWERCASE_SHA256.test(value.checksum)
  ) {
    throw invalidServerResponse()
  }

  let catalogRevisionId: string | null = null
  let catalogVersion: number | null = null
  if (hasCatalogRevision) {
    if (value.catalogRevisionId === null && value.catalogVersion === null) {
      // Jackson may send explicit nulls or omit both nullable catalog fields.
    } else if (isUuid(value.catalogRevisionId) && isPositiveVersion(value.catalogVersion)) {
      catalogRevisionId = value.catalogRevisionId
      catalogVersion = value.catalogVersion
    } else {
      throw invalidServerResponse()
    }
  }

  return {
    revisionId: value.revisionId,
    aggregateVersion: value.aggregateVersion,
    catalogRevisionId,
    catalogVersion,
    checksum: value.checksum,
  }
}

function requireNextVersion(actual: number, expected: number): void {
  if (actual !== expected + 1) throw invalidServerResponse()
}

function requireNoCatalogResult(result: PublicationResultDto): void {
  if (result.catalogRevisionId !== null || result.catalogVersion !== null) {
    throw invalidServerResponse()
  }
}

function requireNextCatalogResult(result: PublicationResultDto, expected: number): void {
  if (result.catalogRevisionId === null || result.catalogVersion === null) {
    throw invalidServerResponse()
  }
  requireNextVersion(result.catalogVersion, expected)
}

function normalizeHistory(
  value: unknown,
  expectedType: AggregateType,
  expectedId: string,
): RevisionSummaryDto[] {
  if (!Array.isArray(value)) throw invalidServerResponse()
  const summaries: RevisionSummaryDto[] = []
  const ids = new Set<string>()
  const versions = new Set<number>()
  for (const candidate of value) {
    if (
      !hasExactKeys(candidate, [
        'id',
        'type',
        'aggregateId',
        'version',
        'schemaVersion',
        'checksum',
        'publishedBy',
        'publishedAt',
      ]) ||
      !isUuid(candidate.id) ||
      candidate.type !== expectedType ||
      !isUuid(candidate.aggregateId) ||
      !sameUuid(candidate.aggregateId, expectedId) ||
      !isPositiveVersion(candidate.version) ||
      !isSchemaVersion(candidate.schemaVersion) ||
      typeof candidate.checksum !== 'string' ||
      !LOWERCASE_SHA256.test(candidate.checksum) ||
      !isUuid(candidate.publishedBy) ||
      !isUtcInstant(candidate.publishedAt)
    ) {
      throw invalidServerResponse()
    }
    const id = candidate.id.toLowerCase()
    if (ids.has(id) || versions.has(candidate.version)) throw invalidServerResponse()
    ids.add(id)
    versions.add(candidate.version)
    summaries.push({
      id: candidate.id,
      type: candidate.type as AggregateType,
      aggregateId: candidate.aggregateId,
      version: candidate.version,
      schemaVersion: candidate.schemaVersion,
      checksum: candidate.checksum,
      publishedBy: candidate.publishedBy,
      publishedAt: candidate.publishedAt,
    })
  }
  return summaries
}

function normalizeState(
  value: unknown,
  expectedType: AggregateType,
  expectedId: string,
): PublicationStateDto {
  if (
    !hasExactKeys(value, [
      'aggregateType',
      'aggregateId',
      'status',
      'version',
      'currentRevisionId',
      'publishedAt',
      'projectIdsInOrder',
    ]) ||
    value.aggregateType !== expectedType ||
    !isUuid(value.aggregateId) ||
    !sameUuid(value.aggregateId, expectedId) ||
    typeof value.status !== 'string' ||
    !PUBLICATION_STATUSES.has(value.status as PublicationStatus) ||
    !isNonnegativeVersion(value.version)
  ) {
    throw invalidServerResponse()
  }

  const status = value.status as PublicationStatus
  if (
    (status === 'UNPUBLISHED' &&
      (value.version !== 0 || value.currentRevisionId !== null || value.publishedAt !== null)) ||
    (status !== 'UNPUBLISHED' &&
      (!isPositiveVersion(value.version) ||
        !isUuid(value.currentRevisionId) ||
        !isUtcInstant(value.publishedAt)))
  ) {
    throw invalidServerResponse()
  }

  const projectIdsInOrder = normalizeUniqueUuidList(value.projectIdsInOrder)
  if (
    projectIdsInOrder === null ||
    (expectedType !== 'PROJECT_CATALOG' && projectIdsInOrder.length !== 0)
  ) {
    throw invalidServerResponse()
  }
  for (const id of projectIdsInOrder) {
    try {
      requireAggregateIdentity('PROJECT', id)
    } catch {
      throw invalidServerResponse()
    }
  }

  return {
    aggregateType: expectedType,
    aggregateId: value.aggregateId,
    status,
    version: value.version,
    currentRevisionId: value.currentRevisionId as string | null,
    publishedAt: value.publishedAt as string | null,
    projectIdsInOrder,
  }
}

function requireRestoreRequest(value: unknown): RestoreRevisionRequest {
  if (
    !hasExactKeys(value, ['expectedWorkspaceVersion']) ||
    !isMutationVersion(value.expectedWorkspaceVersion)
  ) {
    throw new TypeError('revision restore request is invalid')
  }
  return { expectedWorkspaceVersion: value.expectedWorkspaceVersion }
}

export function createPublishingApi(client: AxiosInstance): PublishingApi {
  const previewUrl = (token: string): string => {
    if (!isPreviewToken(token)) throw new TypeError('preview token is invalid')
    return `/api/admin/publishing/previews/${encodeURIComponent(token)}`
  }

  const publishSite = async (input: PublishSiteCommand): Promise<PublicationResultDto> => {
    const command = requireSiteCommand(input)
    const response = await client.post<unknown>('/api/admin/publishing/site', command)
    const result = normalizePublicationResult(response.data)
    requireNextVersion(result.aggregateVersion, command.expectedPublicationVersion)
    requireNoCatalogResult(result)
    return result
  }

  const publishProject = async (
    input: PublishProjectCommand,
  ): Promise<PublicationResultDto> => {
    const command = requireProjectCommand(input)
    const response = await client.post<unknown>(
      `/api/admin/publishing/projects/${encodeURIComponent(command.projectId)}`,
      command,
    )
    const result = normalizePublicationResult(response.data)
    requireNextVersion(result.aggregateVersion, command.expectedProjectPublicationVersion)
    requireNextCatalogResult(result, command.expectedCatalogVersion)
    return result
  }

  const archiveProject = async (
    input: ArchiveProjectCommand,
  ): Promise<PublicationResultDto> => {
    const command = requireArchiveCommand(input)
    const response = await client.post<unknown>(
      `/api/admin/publishing/projects/${encodeURIComponent(command.projectId)}/archive`,
      command,
    )
    const result = normalizePublicationResult(response.data)
    requireNextVersion(result.aggregateVersion, command.expectedProjectPublicationVersion)
    requireNextCatalogResult(result, command.expectedCatalogVersion)
    return result
  }

  const reorderCatalog = async (
    input: ReorderCatalogCommand,
  ): Promise<PublicationResultDto> => {
    const command = requireReorderCommand(input)
    const response = await client.put<unknown>('/api/admin/publishing/catalog/order', command)
    const result = normalizePublicationResult(response.data)
    requireNextVersion(result.aggregateVersion, command.expectedCatalogVersion)
    requireNoCatalogResult(result)
    return result
  }

  return {
    async createPreview(input) {
      const request = requirePreviewRequest(input)
      const response = await client.post<unknown>('/api/admin/publishing/preview-tokens', request)
      return normalizePreviewResponse(response.data)
    },
    async preflightPreview(token) {
      await client.get<unknown>(previewUrl(token))
    },
    previewUrl,
    publishSite,
    publishProject,
    archiveProject,
    reorderCatalog,
    async history(type, id) {
      requireAggregateIdentity(type, id)
      const response = await client.get<unknown>(
        `/api/admin/publishing/${encodeURIComponent(type)}/${encodeURIComponent(id)}/history`,
      )
      return normalizeHistory(response.data, type, id)
    },
    async restore(revisionId, input) {
      if (!isUuid(revisionId)) throw new TypeError('revision id must be a UUID')
      const request = requireRestoreRequest(input)
      const response = await client.post<void>(
        `/api/admin/publishing/revisions/${encodeURIComponent(revisionId)}/restore`,
        request,
      )
      if (response.status !== 204) throw invalidServerResponse()
    },
    async state(type, id) {
      requireAggregateIdentity(type, id)
      const response = await client.get<unknown>(
        `/api/admin/publishing/${encodeURIComponent(type)}/${encodeURIComponent(id)}/state`,
      )
      return normalizeState(response.data, type, id)
    },
    async publishTarget(target) {
      const candidate: unknown = target
      if (
        !isRecord(candidate) ||
        (candidate.aggregateType !== 'SITE' && candidate.aggregateType !== 'PROJECT')
      ) {
        throw new TypeError('publication target is invalid')
      }
      if (candidate.aggregateType === 'SITE') {
        if (
          !hasExactKeys(candidate, [
            'aggregateType',
            'aggregateId',
            'expectedWorkspaceVersion',
            'expectedPublicationVersion',
          ])
        ) {
          throw new TypeError('publication target is invalid')
        }
        requireAggregateIdentity('SITE', candidate.aggregateId)
        return publishSite({
          expectedWorkspaceVersion: candidate.expectedWorkspaceVersion as number,
          expectedPublicationVersion: candidate.expectedPublicationVersion as number,
        })
      }
      if (
        !hasExactKeys(candidate, [
          'aggregateType',
          'aggregateId',
          'expectedWorkspaceVersion',
          'expectedProjectPublicationVersion',
          'expectedCatalogVersion',
        ])
      ) {
        throw new TypeError('publication target is invalid')
      }
      requireAggregateIdentity('PROJECT', candidate.aggregateId)
      return publishProject({
        projectId: candidate.aggregateId as string,
        expectedWorkspaceVersion: candidate.expectedWorkspaceVersion as number,
        expectedProjectPublicationVersion:
          candidate.expectedProjectPublicationVersion as number,
        expectedCatalogVersion: candidate.expectedCatalogVersion as number,
      })
    },
  }
}

export const publishingApi = createPublishingApi(http)
