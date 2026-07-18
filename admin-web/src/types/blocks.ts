import type { Localized } from './content'

export const BLOCK_TYPES = Object.freeze([
  'MARKDOWN',
  'IMAGE',
  'GALLERY',
  'VIDEO',
  'CODE',
  'QUOTE',
  'METRICS',
  'DOWNLOAD',
  'LINK',
] as const)

export const BLOCK_WIDTHS = Object.freeze(['NARROW', 'STANDARD', 'WIDE', 'FULL'] as const)

export const BLOCK_ALIGNMENTS = Object.freeze(['LEFT', 'CENTER', 'RIGHT'] as const)

export const BLOCK_EMPHASES = Object.freeze(['NONE', 'SOFT', 'STRONG'] as const)

/** WorkspaceValidator accepts only these exact, case-sensitive provider values. */
export const VIDEO_PROVIDERS = Object.freeze(['BILIBILI', 'YOUTUBE', 'VIMEO'] as const)

export type BlockType = (typeof BLOCK_TYPES)[number]
export type BlockWidth = (typeof BLOCK_WIDTHS)[number]
export type BlockAlignment = (typeof BLOCK_ALIGNMENTS)[number]
export type BlockEmphasis = (typeof BLOCK_EMPHASES)[number]
export type VideoProvider = (typeof VIDEO_PROVIDERS)[number]

export interface BlockCopy {
  title: string
  description: string
}

export interface ActionCopy {
  label: string
  description: string
}

export interface QuoteCopy {
  quote: string
  source: string
}

export interface MetricCopy {
  label: string
  value: string
  suffix: string
}

export interface MetricDto {
  id: string
  sortOrder: number
  /** Lossless wire form of Java BigDecimal; never coerce this value to number. */
  numericValue: string | null
  copy: Localized<MetricCopy>
}

export interface MarkdownPayload {
  type: 'MARKDOWN'
  markdown: Localized<string>
}

export interface ImagePayload {
  type: 'IMAGE'
  mediaAssetId: string | null
}

export interface GalleryPayload {
  type: 'GALLERY'
  mediaAssetIds: string[]
}

export interface VideoPayload {
  type: 'VIDEO'
  provider: VideoProvider
  url: string
  coverAssetId: string | null
  copy: Localized<BlockCopy>
}

export interface CodePayload {
  type: 'CODE'
  code: string
  language: string
  showLineNumbers: boolean
  copy: Localized<BlockCopy>
}

export interface QuotePayload {
  type: 'QUOTE'
  copy: Localized<QuoteCopy>
}

export interface MetricsPayload {
  type: 'METRICS'
  metrics: MetricDto[]
}

export interface DownloadPayload {
  type: 'DOWNLOAD'
  mediaAssetId: string | null
  externalUrl: string | null
  copy: Localized<ActionCopy>
}

export interface LinkPayload {
  type: 'LINK'
  url: string
  openNewTab: boolean
  copy: Localized<ActionCopy>
}

export interface ContentBlockPayloadByType {
  MARKDOWN: MarkdownPayload
  IMAGE: ImagePayload
  GALLERY: GalleryPayload
  VIDEO: VideoPayload
  CODE: CodePayload
  QUOTE: QuotePayload
  METRICS: MetricsPayload
  DOWNLOAD: DownloadPayload
  LINK: LinkPayload
}

export type ContentBlockPayload<Type extends BlockType = BlockType> =
  ContentBlockPayloadByType[Type]

export interface ContentBlockDto<Type extends BlockType = BlockType> {
  id: string
  sortOrder: number
  visible: boolean
  width: BlockWidth
  alignment: BlockAlignment
  emphasis: BlockEmphasis
  columns: number
  payload: ContentBlockPayload<Type>
}

const UUID =
  /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i
const BIG_DECIMAL = /^[+-]?(?:(?:\d+(?:\.\d*)?)|(?:\.\d+))(?:[eE][+-]?\d+)?$/
const JAVA_INT_MIN = -2_147_483_648
const JAVA_INT_MAX = 2_147_483_647

function localized<Value>(factory: () => Value): Localized<Value> {
  return {
    'zh-CN': factory(),
    en: factory(),
  }
}

function unsupportedBlockType(value: never): never {
  throw new Error(`Unsupported content block type: ${String(value)}`)
}

export function createBlock<Type extends BlockType>(type: Type): ContentBlockDto<Type>
export function createBlock(type: BlockType): ContentBlockDto {
  const base = {
    id: globalThis.crypto.randomUUID(),
    sortOrder: 0,
    visible: true,
    width: 'STANDARD',
    alignment: 'LEFT',
    emphasis: 'NONE',
    columns: 1,
  } as const

  switch (type) {
    case 'MARKDOWN':
      return {
        ...base,
        payload: { type, markdown: localized(() => '') },
      }
    case 'IMAGE':
      return {
        ...base,
        payload: { type, mediaAssetId: null },
      }
    case 'GALLERY':
      return {
        ...base,
        payload: { type, mediaAssetIds: [] },
      }
    case 'VIDEO':
      return {
        ...base,
        payload: {
          type,
          provider: 'BILIBILI',
          url: '',
          coverAssetId: null,
          copy: localized(() => ({ title: '', description: '' })),
        },
      }
    case 'CODE':
      return {
        ...base,
        payload: {
          type,
          code: '',
          language: 'text',
          showLineNumbers: true,
          copy: localized(() => ({ title: '', description: '' })),
        },
      }
    case 'QUOTE':
      return {
        ...base,
        payload: {
          type,
          copy: localized(() => ({ quote: '', source: '' })),
        },
      }
    case 'METRICS':
      return {
        ...base,
        payload: { type, metrics: [] },
      }
    case 'DOWNLOAD':
      return {
        ...base,
        payload: {
          type,
          mediaAssetId: null,
          externalUrl: null,
          copy: localized(() => ({ label: '', description: '' })),
        },
      }
    case 'LINK':
      return {
        ...base,
        payload: {
          type,
          url: '',
          openNewTab: true,
          copy: localized(() => ({ label: '', description: '' })),
        },
      }
    default:
      return unsupportedBlockType(type)
  }
}

function isRecord(value: unknown): value is Record<string, unknown> {
  if (typeof value !== 'object' || value === null || Array.isArray(value)) {
    return false
  }

  const prototype = Object.getPrototypeOf(value)
  return prototype === Object.prototype || prototype === null
}

function hasOwn(value: Record<string, unknown>, key: string): boolean {
  return Object.prototype.hasOwnProperty.call(value, key)
}

function hasExactKeys(
  value: Record<string, unknown>,
  required: readonly string[],
  optional: readonly string[] = [],
): boolean {
  const allowed = new Set([...required, ...optional])
  return (
    required.every((key) => hasOwn(value, key)) &&
    Object.keys(value).every((key) => allowed.has(key))
  )
}

function isOneOf<const Values extends readonly string[]>(
  value: unknown,
  values: Values,
): value is Values[number] {
  return typeof value === 'string' && (values as readonly string[]).includes(value)
}

function isUuid(value: unknown): value is string {
  return typeof value === 'string' && UUID.test(value)
}

function isJavaInteger(value: unknown): value is number {
  return (
    typeof value === 'number' &&
    Number.isSafeInteger(value) &&
    value >= JAVA_INT_MIN &&
    value <= JAVA_INT_MAX
  )
}

function isHttpsUrl(value: unknown): value is string {
  if (typeof value !== 'string') {
    return false
  }

  try {
    return new URL(value).protocol === 'https:'
  } catch {
    return false
  }
}

function normalizeLocalized<Value>(
  value: unknown,
  normalizeLeaf: (leaf: unknown) => Value | null,
): Localized<Value> | null {
  if (!isRecord(value) || !hasExactKeys(value, ['zh-CN', 'en'])) {
    return null
  }

  const chinese = normalizeLeaf(value['zh-CN'])
  const english = normalizeLeaf(value.en)
  if (chinese === null || english === null) {
    return null
  }

  return { 'zh-CN': chinese, en: english }
}

function normalizeString(value: unknown): string | null {
  return typeof value === 'string' ? value : null
}

type NormalizedStringLeaf =
  | { readonly valid: true; readonly value: string }
  | { readonly valid: false }

function normalizeJacksonNullableString(
  value: Record<string, unknown>,
  key: string,
): NormalizedStringLeaf {
  if (!hasOwn(value, key) || value[key] === null) {
    return { valid: true, value: '' }
  }
  return typeof value[key] === 'string'
    ? { valid: true, value: value[key] }
    : { valid: false }
}

function normalizeBlockCopy(value: unknown): BlockCopy | null {
  if (!isRecord(value) || !hasExactKeys(value, [], ['title', 'description'])) {
    return null
  }

  const title = normalizeJacksonNullableString(value, 'title')
  const description = normalizeJacksonNullableString(value, 'description')
  return title.valid && description.valid
    ? { title: title.value, description: description.value }
    : null
}

function normalizeActionCopy(value: unknown): ActionCopy | null {
  if (!isRecord(value) || !hasExactKeys(value, [], ['label', 'description'])) {
    return null
  }

  const label = normalizeJacksonNullableString(value, 'label')
  const description = normalizeJacksonNullableString(value, 'description')
  return label.valid && description.valid
    ? { label: label.value, description: description.value }
    : null
}

function normalizeQuoteCopy(value: unknown): QuoteCopy | null {
  if (
    !isRecord(value) ||
    !hasExactKeys(value, ['quote', 'source']) ||
    typeof value.quote !== 'string' ||
    typeof value.source !== 'string'
  ) {
    return null
  }

  return { quote: value.quote, source: value.source }
}

function normalizeMetricCopy(value: unknown): MetricCopy | null {
  if (
    !isRecord(value) ||
    !hasExactKeys(value, ['label', 'value', 'suffix']) ||
    typeof value.label !== 'string' ||
    typeof value.value !== 'string' ||
    typeof value.suffix !== 'string'
  ) {
    return null
  }

  return { label: value.label, value: value.value, suffix: value.suffix }
}

function normalizeMetric(value: unknown): MetricDto | null {
  if (
    !isRecord(value) ||
    !hasExactKeys(value, ['id', 'sortOrder', 'copy'], ['numericValue']) ||
    !isUuid(value.id) ||
    !isJavaInteger(value.sortOrder)
  ) {
    return null
  }

  let numericValue: string | null = null
  if (hasOwn(value, 'numericValue') && value.numericValue !== null) {
    if (
      typeof value.numericValue !== 'string' ||
      !BIG_DECIMAL.test(value.numericValue)
    ) {
      return null
    }
    numericValue = value.numericValue
  }

  const copy = normalizeLocalized(value.copy, normalizeMetricCopy)
  if (copy === null) {
    return null
  }

  return {
    id: value.id,
    sortOrder: value.sortOrder,
    numericValue,
    copy,
  }
}

function normalizePayload(value: unknown): ContentBlockPayload | null {
  if (!isRecord(value) || !isOneOf(value.type, BLOCK_TYPES)) {
    return null
  }

  switch (value.type) {
    case 'MARKDOWN': {
      if (!hasExactKeys(value, ['type', 'markdown'])) {
        return null
      }
      const markdown = normalizeLocalized(value.markdown, normalizeString)
      return markdown === null ? null : { type: value.type, markdown }
    }
    case 'IMAGE':
      return hasExactKeys(value, ['type', 'mediaAssetId']) && isUuid(value.mediaAssetId)
        ? { type: value.type, mediaAssetId: value.mediaAssetId }
        : null
    case 'GALLERY': {
      if (
        !hasExactKeys(value, ['type', 'mediaAssetIds']) ||
        !Array.isArray(value.mediaAssetIds) ||
        value.mediaAssetIds.length < 2
      ) {
        return null
      }
      const mediaAssetIds: string[] = []
      for (const mediaAssetId of value.mediaAssetIds) {
        if (!isUuid(mediaAssetId)) {
          return null
        }
        mediaAssetIds.push(mediaAssetId)
      }
      return { type: value.type, mediaAssetIds }
    }
    case 'VIDEO': {
      if (
        !hasExactKeys(value, ['type', 'provider', 'url', 'copy'], ['coverAssetId']) ||
        !isOneOf(value.provider, VIDEO_PROVIDERS) ||
        !isHttpsUrl(value.url)
      ) {
        return null
      }
      let coverAssetId: string | null = null
      if (hasOwn(value, 'coverAssetId') && value.coverAssetId !== null) {
        if (!isUuid(value.coverAssetId)) {
          return null
        }
        coverAssetId = value.coverAssetId
      }
      const copy = normalizeLocalized(value.copy, normalizeBlockCopy)
      return copy === null
        ? null
        : {
            type: value.type,
            provider: value.provider,
            url: value.url,
            coverAssetId,
            copy,
          }
    }
    case 'CODE': {
      if (
        !hasExactKeys(value, [
          'type',
          'code',
          'language',
          'showLineNumbers',
          'copy',
        ]) ||
        typeof value.code !== 'string' ||
        typeof value.language !== 'string' ||
        typeof value.showLineNumbers !== 'boolean'
      ) {
        return null
      }
      const copy = normalizeLocalized(value.copy, normalizeBlockCopy)
      return copy === null
        ? null
        : {
            type: value.type,
            code: value.code,
            language: value.language,
            showLineNumbers: value.showLineNumbers,
            copy,
          }
    }
    case 'QUOTE': {
      if (!hasExactKeys(value, ['type', 'copy'])) {
        return null
      }
      const copy = normalizeLocalized(value.copy, normalizeQuoteCopy)
      return copy === null ? null : { type: value.type, copy }
    }
    case 'METRICS': {
      if (
        !hasExactKeys(value, ['type', 'metrics']) ||
        !Array.isArray(value.metrics) ||
        value.metrics.length === 0
      ) {
        return null
      }
      const metrics: MetricDto[] = []
      for (const metricValue of value.metrics) {
        const metric = normalizeMetric(metricValue)
        if (metric === null) {
          return null
        }
        metrics.push(metric)
      }
      return { type: value.type, metrics }
    }
    case 'DOWNLOAD': {
      if (!hasExactKeys(value, ['type', 'copy'], ['mediaAssetId', 'externalUrl'])) {
        return null
      }

      let mediaAssetId: string | null = null
      if (hasOwn(value, 'mediaAssetId') && value.mediaAssetId !== null) {
        if (!isUuid(value.mediaAssetId)) {
          return null
        }
        mediaAssetId = value.mediaAssetId
      }

      let externalUrl: string | null = null
      if (hasOwn(value, 'externalUrl') && value.externalUrl !== null) {
        if (!isHttpsUrl(value.externalUrl)) {
          return null
        }
        externalUrl = value.externalUrl
      }

      if ((mediaAssetId === null) === (externalUrl === null)) {
        return null
      }
      const copy = normalizeLocalized(value.copy, normalizeActionCopy)
      return copy === null
        ? null
        : { type: value.type, mediaAssetId, externalUrl, copy }
    }
    case 'LINK': {
      if (
        !hasExactKeys(value, ['type', 'url', 'openNewTab', 'copy']) ||
        !isHttpsUrl(value.url) ||
        typeof value.openNewTab !== 'boolean'
      ) {
        return null
      }
      const copy = normalizeLocalized(value.copy, normalizeActionCopy)
      return copy === null
        ? null
        : { type: value.type, url: value.url, openNewTab: value.openNewTab, copy }
    }
    default:
      return unsupportedBlockType(value.type)
  }
}

/**
 * Converts an untrusted management-API value into the exact block DTO.
 * Jackson's global NON_NULL policy may omit approved nullable fields; the
 * normalized result always restores them as explicit nulls for editor safety.
 */
export function normalizeContentBlock(value: unknown): ContentBlockDto | null {
  try {
    if (
      !isRecord(value) ||
      !hasExactKeys(value, [
        'id',
        'sortOrder',
        'visible',
        'width',
        'alignment',
        'emphasis',
        'columns',
        'payload',
      ]) ||
      !isUuid(value.id) ||
      !isJavaInteger(value.sortOrder) ||
      typeof value.visible !== 'boolean' ||
      !isOneOf(value.width, BLOCK_WIDTHS) ||
      !isOneOf(value.alignment, BLOCK_ALIGNMENTS) ||
      !isOneOf(value.emphasis, BLOCK_EMPHASES) ||
      !isJavaInteger(value.columns) ||
      value.columns < 1 ||
      value.columns > 4
    ) {
      return null
    }

    const payload = normalizePayload(value.payload)
    if (payload === null) {
      return null
    }

    return {
      id: value.id,
      sortOrder: value.sortOrder,
      visible: value.visible,
      width: value.width,
      alignment: value.alignment,
      emphasis: value.emphasis,
      columns: value.columns,
      payload,
    }
  } catch {
    return null
  }
}
