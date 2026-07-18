import type {
  Locale,
  MediaAssetSummaryDto,
  MediaAssetView,
  MediaTranslationView,
} from '@/types/content'

const UUID_PATTERN = /^[0-9a-f]{8}-(?:[0-9a-f]{4}-){3}[0-9a-f]{12}$/i
const IMAGE_MIME_TYPES = new Set(['image/jpeg', 'image/png'])

export interface TranslationFieldState {
  readonly value: string
  readonly complete: boolean
}

export interface MediaTranslationState {
  readonly locale: Locale
  readonly altText: TranslationFieldState
  readonly caption: TranslationFieldState
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value)
}

export function isMediaUuid(value: unknown): value is string {
  return typeof value === 'string' && UUID_PATTERN.test(value)
}

function hasSummaryShape(value: Record<string, unknown>): boolean {
  return (
    typeof value.originalFilename === 'string' &&
    value.originalFilename.trim().length > 0 &&
    (value.previewUrl === null || typeof value.previewUrl === 'string') &&
    (value.width === null || (Number.isSafeInteger(value.width) && (value.width as number) > 0)) &&
    (value.height === null || (Number.isSafeInteger(value.height) && (value.height as number) > 0))
  )
}

export function isReadyImageSelection(value: unknown): value is MediaAssetSummaryDto {
  if (!isRecord(value) || !hasSummaryShape(value)) return false
  return (
    isMediaUuid(value.id) &&
    value.status === 'READY' &&
    value.kind === 'IMAGE' &&
    typeof value.mimeType === 'string' &&
    IMAGE_MIME_TYPES.has(value.mimeType) &&
    Number.isSafeInteger(value.width) &&
    (value.width as number) > 0 &&
    Number.isSafeInteger(value.height) &&
    (value.height as number) > 0
  )
}

export function isReadyPdfSelection(value: unknown): value is MediaAssetSummaryDto {
  if (!isRecord(value) || !hasSummaryShape(value)) return false
  return (
    isMediaUuid(value.id) &&
    value.status === 'READY' &&
    value.kind === 'PDF' &&
    value.mimeType === 'application/pdf' &&
    value.width === null &&
    value.height === null
  )
}

function hasResolvedBase(
  value: unknown,
  requestedId: string,
): value is MediaAssetView {
  if (!isRecord(value)) return false
  return (
    isMediaUuid(requestedId) &&
    isMediaUuid(value.id) &&
    value.id.toLocaleLowerCase() === requestedId.toLocaleLowerCase() &&
    value.status === 'READY' &&
    typeof value.originalFilename === 'string' &&
    value.originalFilename.trim().length > 0 &&
    Array.isArray(value.translations)
  )
}

export function isResolvedImage(
  value: unknown,
  requestedId: string,
): value is MediaAssetView {
  if (!hasResolvedBase(value, requestedId)) return false
  return (
    IMAGE_MIME_TYPES.has(value.mimeType) &&
    Number.isSafeInteger(value.width) &&
    (value.width as number) > 0 &&
    Number.isSafeInteger(value.height) &&
    (value.height as number) > 0
  )
}

export function isResolvedPdf(
  value: unknown,
  requestedId: string,
): value is MediaAssetView {
  if (!hasResolvedBase(value, requestedId)) return false
  return (
    value.mimeType === 'application/pdf' &&
    value.width === null &&
    value.height === null
  )
}

function translationFor(
  asset: MediaAssetView | null | undefined,
  locale: Locale,
): MediaTranslationView | undefined {
  if (asset === null || asset === undefined) return undefined
  return asset.translations.find(
    (translation) =>
      isRecord(translation) &&
      translation.locale === locale &&
      typeof translation.altText === 'string' &&
      typeof translation.caption === 'string',
  )
}

function translationField(value: unknown): TranslationFieldState {
  const text = typeof value === 'string' ? value : ''
  return { value: text, complete: text.trim().length > 0 }
}

export function mediaTranslationState(
  asset: MediaAssetView | null | undefined,
  locale: Locale,
): MediaTranslationState {
  const translation = translationFor(asset, locale)
  return {
    locale,
    altText: translationField(translation?.altText),
    caption: translationField(translation?.caption),
  }
}

function normalizedPath(path: string): string {
  return path.replace(/\[(\d+)\]/g, '.$1')
}

export function blockFieldError(
  fieldErrors: Readonly<Record<string, string>> | undefined,
  path: string,
): string | undefined {
  const expected = normalizedPath(path)
  for (const [candidate, message] of Object.entries(fieldErrors ?? {})) {
    const normalizedCandidate = normalizedPath(candidate)
    if (
      normalizedCandidate === expected ||
      normalizedCandidate === `payload.${expected}` ||
      normalizedCandidate.endsWith(`.payload.${expected}`)
    ) {
      return message
    }
  }
  return undefined
}
