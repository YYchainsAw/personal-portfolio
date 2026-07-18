import type { AxiosInstance } from 'axios'

import { ApiProblem, type Page } from '@/types/api'
import type { MediaAssetSummaryDto, MediaKind, MediaMimeType } from '@/types/content'

import { http } from './http'

const DEFAULT_PAGE_SIZE = 24
const MAXIMUM_PAGE_SIZE = 100
const UUID_PATTERN = /^[0-9a-f]{8}-(?:[0-9a-f]{4}-){3}[0-9a-f]{12}$/i
const IMAGE_PREVIEW_VARIANT_PATTERN = /^w[1-9][0-9]{0,9}$/
const PREVIEW_VARIANT_PATTERN = /^(?:document|w[1-9][0-9]{0,9})$/
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
])

export interface MediaSearchOptions {
  readonly page?: number
  readonly size?: number
  readonly kind?: MediaKind | readonly MediaKind[]
  readonly text?: string
}

export interface MediaApi {
  search(options?: Readonly<MediaSearchOptions>): Promise<Page<MediaAssetSummaryDto>>
  previewUrl(id: string, variant: string): string
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value)
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
  if (!Number.isSafeInteger(page) || page < 0) {
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

function isMediaId(value: unknown): value is string {
  return typeof value === 'string' && UUID_PATTERN.test(value)
}

function isPreviewVariant(value: unknown): value is string {
  return typeof value === 'string' && PREVIEW_VARIANT_PATTERN.test(value)
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
  const pageIsInRange = totalPages === 0 ? page === 0 : page < totalPages
  const remainingItems = pageIsInRange ? Math.max(0, totalItems - page * size) : 0
  const maximumPageItems = Math.min(size, remainingItems)

  if (
    page !== expectedPage ||
    size !== expectedSize ||
    totalPages !== calculatedTotalPages ||
    !pageIsInRange ||
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
    previewUrl,
  }
}

export const mediaApi = createMediaApi(http)
