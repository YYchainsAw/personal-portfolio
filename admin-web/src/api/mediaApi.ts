import type { AxiosInstance } from 'axios'

import { ApiProblem, type Page } from '@/types/api'
import type {
  MediaAssetSummaryDto,
  MediaAssetView,
  MediaKind,
  MediaMimeType,
  MediaStatus,
  MediaTranslationInput,
  MediaTranslationView,
  MediaVariantView,
} from '@/types/content'

import { http } from './http'

const DEFAULT_PAGE_SIZE = 24
const MAXIMUM_PAGE_SIZE = 100
const MAXIMUM_PAGE = 2_147_483_647
const IMAGE_UPLOAD_BYTE_LIMIT = 25 * 1024 * 1024
const PDF_UPLOAD_BYTE_LIMIT = 30 * 1024 * 1024
const MEDIA_UPLOAD_TIMEOUT_MS = 180_000
const UUID_PATTERN = /^[0-9a-f]{8}-(?:[0-9a-f]{4}-){3}[0-9a-f]{12}$/i
const IMAGE_PREVIEW_VARIANT_PATTERN = /^w[1-9][0-9]{0,9}$/
const PREVIEW_VARIANT_PATTERN = /^(?:document|w[1-9][0-9]{0,9})$/
const SHA256_PATTERN = /^[0-9a-f]{64}$/
const INSTANT_PATTERN = /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d{1,9})?Z$/
const MAXIMUM_DIMENSION = 2_147_483_647
const MEDIA_KINDS = new Set<MediaKind>(['IMAGE', 'PDF', 'FILE'])
const MEDIA_MIME_TYPES = new Set<MediaMimeType>([
  'image/jpeg',
  'image/png',
  'application/pdf',
])
const MEDIA_STATUSES = new Set<MediaAssetSummaryDto['status']>([
  'READY',
  'PROCESSING',
  'FAILED',
  'ARCHIVED',
  'PENDING_DELETE',
])
const DETAILED_MEDIA_STATUSES = new Set<MediaAssetView['status']>([
  'PROCESSING',
  'READY',
  'FAILED',
  'ARCHIVED',
])
const LIST_MEDIA_STATUSES = new Set<MediaAssetView['status']>(MEDIA_STATUSES)
const MEDIA_VARIANT_STATUSES = new Set<MediaVariantView['status']>([
  'PROCESSING',
  'READY',
  'FAILED',
])
const MEDIA_LOCALES = new Set<MediaTranslationView['locale']>(['zh-CN', 'en'])
const MEDIA_ASSET_VIEW_KEYS = [
  'id',
  'originalFilename',
  'mimeType',
  'byteSize',
  'width',
  'height',
  'sha256',
  'status',
  'version',
  'createdAt',
  'updatedAt',
  'translations',
  'variants',
] as const
const MEDIA_TRANSLATION_VIEW_KEYS = [
  'locale',
  'altText',
  'caption',
  'credit',
  'sourceUrl',
] as const
const MEDIA_VARIANT_VIEW_KEYS = ['name', 'width', 'height', 'status'] as const
const MEDIA_PAGE_VIEW_KEYS = ['items', 'page', 'size', 'totalItems', 'totalPages'] as const

export interface MediaSearchOptions {
  readonly page?: number
  readonly size?: number
  readonly kind?: MediaKind | readonly MediaKind[]
  readonly text?: string
}

export interface MediaListOptions {
  readonly page?: number
  readonly size?: number
  readonly status?: MediaStatus
}

export interface UpdateMediaTranslationsRequest {
  readonly expectedVersion: number
  readonly translations: readonly MediaTranslationInput[]
}

export interface MediaApi {
  search(options?: Readonly<MediaSearchOptions>): Promise<Page<MediaAssetSummaryDto>>
  list(options?: Readonly<MediaListOptions>): Promise<Page<MediaAssetView>>
  get(id: string): Promise<MediaAssetView>
  upload(file: File): Promise<MediaAssetView>
  updateTranslations(
    id: string,
    request: Readonly<UpdateMediaTranslationsRequest>,
  ): Promise<MediaAssetView>
  archive(id: string): Promise<void>
  previewUrl(id: string, variant: string): string
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value)
}

function hasExactKeys(
  value: Record<string, unknown>,
  keys: readonly string[],
): boolean {
  const actualKeys = Object.keys(value)
  return (
    actualKeys.length === keys.length &&
    keys.every((key) => Object.prototype.hasOwnProperty.call(value, key))
  )
}

function invalidServerResponse(): ApiProblem {
  return new ApiProblem({
    type: 'invalid_server_response',
    title: '服务器返回了无效的媒体数据',
    status: 0,
    code: 'INVALID_MEDIA_RESPONSE',
    traceId: 'client',
  })
}

function requirePage(value: number | undefined): number {
  const page = value ?? 0
  if (!Number.isSafeInteger(page) || page < 0 || page > MAXIMUM_PAGE) {
    throw new RangeError('media page must be a non-negative safe integer')
  }
  return page
}

function requireSize(value: number | undefined): number {
  const size = value ?? DEFAULT_PAGE_SIZE
  if (!Number.isSafeInteger(size) || size < 1 || size > MAXIMUM_PAGE_SIZE) {
    throw new RangeError('media page size is invalid')
  }
  return size
}

function requireStatus(value: MediaStatus | undefined): MediaStatus | undefined {
  if (value === undefined) return undefined
  if (!LIST_MEDIA_STATUSES.has(value)) {
    throw new TypeError('media status is invalid')
  }
  return value
}

function isMediaId(value: unknown): value is string {
  return typeof value === 'string' && UUID_PATTERN.test(value)
}

function isPreviewVariant(value: unknown): value is string {
  return typeof value === 'string' && PREVIEW_VARIANT_PATTERN.test(value)
}

function isPositiveSafeInteger(value: unknown, maximum = Number.MAX_SAFE_INTEGER): value is number {
  return Number.isSafeInteger(value) && (value as number) > 0 && (value as number) <= maximum
}

function isInstant(value: unknown): value is string {
  return (
    typeof value === 'string' &&
    INSTANT_PATTERN.test(value) &&
    Number.isFinite(Date.parse(value))
  )
}

function isStrictHttpsSourceUrl(value: unknown): value is string | null {
  if (value === null) return true
  if (
    typeof value !== 'string' ||
    value.length > 2048 ||
    value.trim() !== value ||
    !/^https:\/\//i.test(value) ||
    /[\u0000-\u0020\u007f]/u.test(value)
  ) {
    return false
  }

  const authority = value.slice('https://'.length).split(/[/?#]/u, 1)[0] ?? ''
  if (
    authority.length === 0 ||
    authority.endsWith(':') ||
    authority.includes('@') ||
    authority.includes('%') ||
    /[^\x21-\x7e]/u.test(authority) ||
    value.includes('\\') ||
    value.includes('#') ||
    !hasBackendCompatibleHost(authority)
  ) {
    return false
  }

  try {
    const parsed = new URL(value)
    if (
      parsed.protocol !== 'https:' ||
      parsed.hostname.length === 0 ||
      parsed.username.length > 0 ||
      parsed.password.length > 0 ||
      parsed.hash.length > 0
    ) {
      return false
    }
    if (parsed.port.length > 0) {
      const port = Number(parsed.port)
      if (!Number.isInteger(port) || port < 1 || port > 65_535) return false
    }
    return true
  } catch {
    return false
  }
}

function hasBackendCompatibleHost(authority: string): boolean {
  let host = authority
  if (authority.startsWith('[')) {
    const closingBracket = authority.indexOf(']')
    if (closingBracket < 2) return false
    host = authority.slice(0, closingBracket + 1)
    const suffix = authority.slice(closingBracket + 1)
    if (suffix.length > 0 && !/^:[0-9]{1,5}$/u.test(suffix)) return false
    return /^\[[0-9a-f:.]+\]$/iu.test(host)
  }

  const colon = authority.lastIndexOf(':')
  if (colon >= 0) {
    if (authority.indexOf(':') !== colon || !/^:[0-9]{1,5}$/u.test(authority.slice(colon))) {
      return false
    }
    host = authority.slice(0, colon)
  }
  if (host.length === 0 || host.length > 253) return false

  if (/^[0-9.]+$/u.test(host)) {
    const octets = host.split('.')
    return (
      octets.length === 4 &&
      octets.every(
        (octet) =>
          /^(?:0|[1-9][0-9]{0,2})$/u.test(octet) && Number(octet) <= 255,
      )
    )
  }

  const domain = host.endsWith('.') ? host.slice(0, -1) : host
  if (domain.length === 0) return false
  return domain.split('.').every(
    (label) =>
      label.length > 0 &&
      label.length <= 63 &&
      /^[a-z0-9](?:[a-z0-9-]*[a-z0-9])?$/iu.test(label),
  )
}

/** Form helper: an empty field maps to null; every non-empty value must match the backend rule. */
export function isValidMediaSourceUrl(value: string): boolean {
  return value.length === 0 || isStrictHttpsSourceUrl(value)
}

function normalizeTranslation(value: unknown): MediaTranslationView | null {
  if (
    !isRecord(value) ||
    !hasExactKeys(value, MEDIA_TRANSLATION_VIEW_KEYS) ||
    typeof value.locale !== 'string' ||
    !MEDIA_LOCALES.has(value.locale as MediaTranslationView['locale']) ||
    typeof value.altText !== 'string' ||
    value.altText.length > 500 ||
    typeof value.caption !== 'string' ||
    value.caption.length > 1000 ||
    typeof value.credit !== 'string' ||
    value.credit.length > 300 ||
    !isStrictHttpsSourceUrl(value.sourceUrl)
  ) {
    return null
  }

  return {
    locale: value.locale as MediaTranslationView['locale'],
    altText: value.altText,
    caption: value.caption,
    credit: value.credit,
    sourceUrl: value.sourceUrl,
  }
}

function normalizeTranslations(value: unknown): MediaTranslationView[] | null {
  if (!Array.isArray(value)) return null
  const result: MediaTranslationView[] = []
  const locales = new Set<MediaTranslationView['locale']>()
  for (const candidate of value) {
    const translation = normalizeTranslation(candidate)
    if (translation === null || locales.has(translation.locale)) return null
    locales.add(translation.locale)
    result.push(translation)
  }
  return result
}

function normalizeVariant(value: unknown): MediaVariantView | null {
  if (
    !isRecord(value) ||
    !hasExactKeys(value, MEDIA_VARIANT_VIEW_KEYS) ||
    !isPreviewVariant(value.name) ||
    typeof value.status !== 'string' ||
    !MEDIA_VARIANT_STATUSES.has(value.status as MediaVariantView['status'])
  ) {
    return null
  }

  if (value.name === 'document') {
    if (value.width !== null || value.height !== null) return null
    return {
      name: value.name,
      width: null,
      height: null,
      status: value.status as MediaVariantView['status'],
    }
  }

  if (
    !isPositiveSafeInteger(value.width, MAXIMUM_DIMENSION) ||
    !isPositiveSafeInteger(value.height, MAXIMUM_DIMENSION) ||
    value.name !== `w${value.width}`
  ) {
    return null
  }
  return {
    name: value.name,
    width: value.width,
    height: value.height,
    status: value.status as MediaVariantView['status'],
  }
}

function normalizeVariants(value: unknown): MediaVariantView[] | null {
  if (!Array.isArray(value)) return null
  const result: MediaVariantView[] = []
  const names = new Set<string>()
  for (const candidate of value) {
    const variant = normalizeVariant(candidate)
    if (variant === null || names.has(variant.name)) return null
    names.add(variant.name)
    result.push(variant)
  }
  return result
}

function normalizeDetailedAsset(
  value: unknown,
  requestedId?: string,
  allowPendingDelete = false,
): MediaAssetView | null {
  if (
    !isRecord(value) ||
    !hasExactKeys(value, MEDIA_ASSET_VIEW_KEYS) ||
    !isMediaId(value.id) ||
    (requestedId !== undefined &&
      value.id.toLocaleLowerCase() !== requestedId.toLocaleLowerCase()) ||
    typeof value.originalFilename !== 'string' ||
    value.originalFilename.trim() !== value.originalFilename ||
    value.originalFilename.length === 0 ||
    Array.from(value.originalFilename).length > 255 ||
    typeof value.mimeType !== 'string' ||
    !MEDIA_MIME_TYPES.has(value.mimeType as MediaMimeType) ||
    !isPositiveSafeInteger(value.byteSize) ||
    typeof value.sha256 !== 'string' ||
    !SHA256_PATTERN.test(value.sha256) ||
    typeof value.status !== 'string' ||
    !(allowPendingDelete ? LIST_MEDIA_STATUSES : DETAILED_MEDIA_STATUSES).has(
      value.status as MediaAssetView['status'],
    ) ||
    !Number.isSafeInteger(value.version) ||
    (value.version as number) < 0 ||
    !isInstant(value.createdAt) ||
    !isInstant(value.updatedAt)
  ) {
    return null
  }

  const mimeType = value.mimeType as MediaMimeType
  const hasValidDimensions =
    mimeType === 'application/pdf'
      ? value.width === null && value.height === null
      : isPositiveSafeInteger(value.width, MAXIMUM_DIMENSION) &&
        isPositiveSafeInteger(value.height, MAXIMUM_DIMENSION)
  if (!hasValidDimensions) return null

  const translations = normalizeTranslations(value.translations)
  const variants = normalizeVariants(value.variants)
  if (translations === null || variants === null) return null

  return {
    id: value.id,
    originalFilename: value.originalFilename,
    mimeType,
    byteSize: value.byteSize,
    width: value.width as number | null,
    height: value.height as number | null,
    sha256: value.sha256,
    status: value.status as MediaAssetView['status'],
    version: value.version as number,
    createdAt: value.createdAt,
    updatedAt: value.updatedAt,
    translations,
    variants,
  }
}

function normalizeDetailedPage(
  value: unknown,
  expectedPage: number,
  expectedSize: number,
  expectedStatus: MediaStatus | undefined,
): Page<MediaAssetView> {
  if (
    !isRecord(value) ||
    !hasExactKeys(value, MEDIA_PAGE_VIEW_KEYS) ||
    !Array.isArray(value.items) ||
    !Number.isSafeInteger(value.page) ||
    (value.page as number) < 0 ||
    !Number.isSafeInteger(value.size) ||
    (value.size as number) < 1 ||
    !Number.isSafeInteger(value.totalItems) ||
    (value.totalItems as number) < 0 ||
    !Number.isSafeInteger(value.totalPages) ||
    (value.totalPages as number) < 0
  ) {
    throw invalidServerResponse()
  }

  const page = value.page as number
  const size = value.size as number
  const totalItems = value.totalItems as number
  const totalPages = value.totalPages as number
  const calculatedTotalPages = totalItems === 0 ? 0 : Math.ceil(totalItems / size)
  const pageIsInRange = totalPages > 0 && page < totalPages
  const remainingItems = pageIsInRange ? Math.max(0, totalItems - page * size) : 0
  const maximumPageItems = Math.min(size, remainingItems)

  if (
    page !== expectedPage ||
    size !== expectedSize ||
    totalPages !== calculatedTotalPages ||
    value.items.length > maximumPageItems
  ) {
    throw invalidServerResponse()
  }

  const items: MediaAssetView[] = []
  const ids = new Set<string>()
  for (const candidate of value.items) {
    const asset = normalizeDetailedAsset(candidate, undefined, true)
    const normalizedId = asset?.id.toLocaleLowerCase()
    if (
      asset === null ||
      normalizedId === undefined ||
      ids.has(normalizedId) ||
      (expectedStatus !== undefined && asset.status !== expectedStatus)
    ) {
      throw invalidServerResponse()
    }
    ids.add(normalizedId)
    items.push(asset)
  }

  return { items, page, size, totalItems, totalPages }
}

function requireUpload(file: File): File {
  if (
    typeof File === 'undefined' ||
    !(file instanceof File) ||
    file.size <= 0 ||
    file.name.trim().length === 0 ||
    !MEDIA_MIME_TYPES.has(file.type as MediaMimeType) ||
    file.size >
      (file.type === 'application/pdf' ? PDF_UPLOAD_BYTE_LIMIT : IMAGE_UPLOAD_BYTE_LIMIT)
  ) {
    throw new TypeError('media upload file is invalid')
  }
  return file
}

function translationsMatch(
  actual: readonly MediaTranslationView[],
  expected: readonly MediaTranslationInput[],
): boolean {
  if (actual.length !== expected.length) return false
  const expectedByLocale = new Map(expected.map((value) => [value.locale, value]))
  return actual.every((value) => {
    const target = expectedByLocale.get(value.locale)
    return (
      target !== undefined &&
      value.altText === target.altText &&
      value.caption === target.caption &&
      value.credit === target.credit &&
      value.sourceUrl === target.sourceUrl
    )
  })
}

function normalizeTranslationInput(
  input: readonly MediaTranslationInput[],
): MediaTranslationInput[] {
  if (!Array.isArray(input) || input.length !== 2) {
    throw new TypeError('both media translations are required')
  }

  const translations = new Map<MediaTranslationView['locale'], MediaTranslationInput>()
  for (const candidate of input) {
    const normalized = normalizeTranslation(candidate)
    if (normalized === null || translations.has(normalized.locale)) {
      throw new TypeError('media translations are invalid')
    }
    translations.set(normalized.locale, { ...normalized })
  }

  const zh = translations.get('zh-CN')
  const en = translations.get('en')
  if (zh === undefined || en === undefined) {
    throw new TypeError('both media translations are required')
  }
  return [zh, en]
}

function normalizeTranslationUpdateRequest(
  request: Readonly<UpdateMediaTranslationsRequest>,
): { expectedVersion: number; translations: MediaTranslationInput[] } {
  if (
    !isRecord(request) ||
    !hasExactKeys(request, ['expectedVersion', 'translations']) ||
    !Number.isSafeInteger(request.expectedVersion) ||
    (request.expectedVersion as number) < 0 ||
    (request.expectedVersion as number) >= Number.MAX_SAFE_INTEGER
  ) {
    throw new TypeError('media expectedVersion is invalid')
  }
  return {
    expectedVersion: request.expectedVersion as number,
    translations: normalizeTranslationInput(
      request.translations as readonly MediaTranslationInput[],
    ),
  }
}

function mediaKind(mimeType: string): MediaKind {
  if (mimeType.startsWith('image/')) return 'IMAGE'
  if (mimeType === 'application/pdf') return 'PDF'
  return 'FILE'
}

function nullableDimension(value: unknown): number | null {
  return Number.isSafeInteger(value) && (value as number) > 0 ? (value as number) : null
}

interface ReadyVariant {
  readonly name: string
  readonly width: number | null
}

function readyVariants(value: unknown): ReadyVariant[] {
  if (!Array.isArray(value)) return []
  const result: ReadyVariant[] = []
  for (const candidate of value) {
    if (
      !isRecord(candidate) ||
      candidate.status !== 'READY' ||
      !isPreviewVariant(candidate.name)
    ) {
      continue
    }

    if (candidate.name === 'document') {
      if (candidate.width !== null) continue
      result.push({ name: candidate.name, width: null })
      continue
    }

    const width = nullableDimension(candidate.width)
    if (width === null || width !== Number(candidate.name.slice(1))) continue
    result.push({ name: candidate.name, width })
  }
  return result
}

function previewVariant(value: Record<string, unknown>, kind: MediaKind): string | null {
  const variants = readyVariants(value.variants)
  if (kind === 'PDF') return variants.find((variant) => variant.name === 'document')?.name ?? null
  if (kind !== 'IMAGE') return null
  return (
    variants
      .filter(
        (variant) => IMAGE_PREVIEW_VARIANT_PATTERN.test(variant.name) && variant.width !== null,
      )
      .sort((left, right) => left.width! - right.width!)[0]?.name ?? null
  )
}

function normalizeAsset(
  value: unknown,
  toPreviewUrl: (id: string, variant: string) => string,
): MediaAssetSummaryDto | null {
  if (
    !isRecord(value) ||
    !isMediaId(value.id) ||
    typeof value.originalFilename !== 'string' ||
    value.originalFilename.trim().length === 0 ||
    typeof value.mimeType !== 'string' ||
    !MEDIA_MIME_TYPES.has(value.mimeType as MediaMimeType) ||
    typeof value.status !== 'string' ||
    !MEDIA_STATUSES.has(value.status as MediaAssetSummaryDto['status'])
  ) {
    return null
  }

  const mimeType = value.mimeType as MediaMimeType
  const kind = mediaKind(mimeType)
  const variant = previewVariant(value, kind)
  return {
    id: value.id,
    kind,
    originalFilename: value.originalFilename,
    mimeType,
    status: value.status as MediaAssetSummaryDto['status'],
    previewUrl: variant === null ? null : toPreviewUrl(value.id, variant),
    width: nullableDimension(value.width),
    height: nullableDimension(value.height),
  }
}

function requestedKinds(kind: MediaSearchOptions['kind']): ReadonlySet<MediaKind> | null {
  if (kind === undefined) return null
  return new Set((Array.isArray(kind) ? kind : [kind]).filter((item) => MEDIA_KINDS.has(item)))
}

function matchesText(asset: MediaAssetSummaryDto, text: string): boolean {
  if (text.length === 0) return true
  return [asset.id, asset.originalFilename, asset.mimeType, asset.kind].some((value) =>
    value.toLocaleLowerCase().includes(text),
  )
}

function normalizePage(
  value: unknown,
  options: Readonly<MediaSearchOptions>,
  toPreviewUrl: (id: string, variant: string) => string,
  expectedPage: number,
  expectedSize: number,
): Page<MediaAssetSummaryDto> {
  if (
    !isRecord(value) ||
    !Array.isArray(value.items) ||
    !Number.isSafeInteger(value.page) ||
    (value.page as number) < 0 ||
    !Number.isSafeInteger(value.size) ||
    (value.size as number) < 1 ||
    !Number.isSafeInteger(value.totalItems) ||
    (value.totalItems as number) < 0 ||
    !Number.isSafeInteger(value.totalPages) ||
    (value.totalPages as number) < 0
  ) {
    throw invalidServerResponse()
  }

  const page = value.page as number
  const size = value.size as number
  const totalItems = value.totalItems as number
  const totalPages = value.totalPages as number
  const calculatedTotalPages = totalItems === 0 ? 0 : Math.ceil(totalItems / size)
  const pageIsInRange = totalPages > 0 && page < totalPages
  const remainingItems = pageIsInRange ? Math.max(0, totalItems - page * size) : 0
  const maximumPageItems = Math.min(size, remainingItems)

  if (
    page !== expectedPage ||
    size !== expectedSize ||
    totalPages !== calculatedTotalPages ||
    value.items.length > maximumPageItems
  ) {
    throw invalidServerResponse()
  }

  const kinds = requestedKinds(options.kind)
  const text = options.text?.trim().toLocaleLowerCase() ?? ''
  const items = value.items
    .map((item) => normalizeAsset(item, toPreviewUrl))
    .filter((item): item is MediaAssetSummaryDto => item !== null)
    .filter((item) => item.status === 'READY')
    .filter((item) => kinds === null || kinds.has(item.kind))
    .filter((item) => matchesText(item, text))

  return {
    items,
    page,
    size,
    totalItems,
    totalPages,
  }
}

export function createMediaApi(client: AxiosInstance): MediaApi {
  const previewUrl = (id: string, variant: string): string => {
    if (!isMediaId(id)) throw new TypeError('media id must be a UUID')
    if (!isPreviewVariant(variant)) throw new TypeError('media preview variant is invalid')
    return `/api/admin/media/${encodeURIComponent(id)}/preview/${encodeURIComponent(variant)}`
  }

  return {
    async search(options = {}) {
      const page = requirePage(options.page)
      const size = requireSize(options.size)
      const response = await client.get<unknown>('/api/admin/media', {
        params: { page, size, status: 'READY' },
      })
      return normalizePage(response.data, options, previewUrl, page, size)
    },
    async list(options = {}) {
      const page = requirePage(options.page)
      const size = requireSize(options.size)
      const status = requireStatus(options.status)
      const response = await client.get<unknown>('/api/admin/media', {
        params: {
          page,
          size,
          ...(status === undefined ? {} : { status }),
        },
      })
      return normalizeDetailedPage(response.data, page, size, status)
    },
    async get(id) {
      if (!isMediaId(id)) throw new TypeError('media id must be a UUID')
      const response = await client.get<unknown>(
        `/api/admin/media/${encodeURIComponent(id)}`,
      )
      const asset = normalizeDetailedAsset(response.data, id)
      if (asset === null) throw invalidServerResponse()
      return asset
    },
    async upload(file) {
      const acceptedFile = requireUpload(file)
      const form = new FormData()
      form.append('file', acceptedFile)
      const response = await client.post<unknown>('/api/admin/media', form, {
        timeout: MEDIA_UPLOAD_TIMEOUT_MS,
      })
      const asset = normalizeDetailedAsset(response.data)
      if (
        asset === null ||
        asset.status !== 'PROCESSING' ||
        asset.version !== 0 ||
        asset.byteSize !== acceptedFile.size ||
        asset.mimeType !== acceptedFile.type ||
        asset.translations.length !== 0 ||
        asset.variants.length !== 0
      ) {
        throw invalidServerResponse()
      }
      return asset
    },
    async updateTranslations(id, request) {
      if (!isMediaId(id)) throw new TypeError('media id must be a UUID')
      const { expectedVersion, translations } = normalizeTranslationUpdateRequest(request)
      const response = await client.put<unknown>(
        `/api/admin/media/${encodeURIComponent(id)}/translations`,
        { expectedVersion, translations },
      )
      const asset = normalizeDetailedAsset(response.data, id)
      if (
        asset === null ||
        asset.version !== expectedVersion + 1 ||
        !translationsMatch(asset.translations, translations)
      ) {
        throw invalidServerResponse()
      }
      return asset
    },
    async archive(id) {
      if (!isMediaId(id)) throw new TypeError('media id must be a UUID')
      const response = await client.delete<unknown>(
        `/api/admin/media/${encodeURIComponent(id)}`,
      )
      if (
        response.status !== 204 ||
        !(response.data === undefined || response.data === null || response.data === '')
      ) {
        throw invalidServerResponse()
      }
    },
    previewUrl,
  }
}

export const mediaApi = createMediaApi(http)
