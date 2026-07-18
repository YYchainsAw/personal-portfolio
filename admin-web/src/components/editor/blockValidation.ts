import type { FieldErrors } from '@/types/api'
import type { ContentBlockDto } from '@/types/blocks'

const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i
const BIG_DECIMAL = /^[+-]?(?:(?:\d+(?:\.\d*)?)|(?:\.\d+))(?:[eE][+-]?\d+)?$/
const JAVA_URI_ILLEGAL_RAW_CHARACTERS = /[\u0000-\u0020\u007f<>"{}|\\^`]/u
const MALFORMED_PERCENT_ESCAPE = /%(?![0-9a-f]{2})/iu

function isUuid(value: string | null): value is string {
  return value !== null && UUID.test(value)
}

export function isSafeHttpsUrl(value: string): boolean {
  if (
    value.length === 0 ||
    value !== value.trim() ||
    !value.startsWith('https://') ||
    JAVA_URI_ILLEGAL_RAW_CHARACTERS.test(value) ||
    value.includes('[') ||
    value.includes(']') ||
    MALFORMED_PERCENT_ESCAPE.test(value) ||
    /%0d|%0a/iu.test(value)
  ) {
    return false
  }

  try {
    const url = new URL(value)
    return (
      url.protocol === 'https:' &&
      url.hostname.length > 0 &&
      url.username.length === 0 &&
      url.password.length === 0 &&
      url.hash.length === 0 &&
      (url.port.length === 0 || url.port === '443')
    )
  } catch {
    return false
  }
}

function assertNever(value: never): never {
  throw new Error(`Unsupported block payload: ${String(value)}`)
}

/**
 * Checks only the structure required to persist a workspace. Publication-only
 * copy completeness remains an authoritative preview/publish concern.
 */
export function validateBlocks(blocks: readonly ContentBlockDto[]): FieldErrors {
  const errors: Record<string, string> = {}
  const ids = new Set<string>()
  const blockOrders = new Set<number>()

  blocks.forEach((block, index) => {
    const path = `blocks[${index}]`
    const blockId = block.id.toLocaleLowerCase()
    if (!UUID.test(block.id) || ids.has(blockId)) {
      errors[`${path}.id`] = '内容块必须使用唯一且有效的编号'
    }
    ids.add(blockId)

    if (
      !Number.isSafeInteger(block.sortOrder) ||
      block.sortOrder < 0 ||
      blockOrders.has(block.sortOrder)
    ) {
      errors[`${path}.sortOrder`] = '内容块排序必须是唯一的非负整数'
    }
    blockOrders.add(block.sortOrder)
    if (!Number.isSafeInteger(block.columns) || block.columns < 1 || block.columns > 4) {
      errors[`${path}.columns`] = '列数必须是 1 到 4 之间的整数'
    }

    const payload = block.payload
    switch (payload.type) {
      case 'MARKDOWN':
      case 'CODE':
      case 'QUOTE':
        break
      case 'IMAGE':
        if (!isUuid(payload.mediaAssetId)) {
          errors[`${path}.mediaAssetId`] = '请选择一张已就绪的图片'
        }
        break
      case 'GALLERY': {
        const galleryIds = new Set<string>()
        if (payload.mediaAssetIds.length < 2) {
          errors[`${path}.mediaAssetIds`] = '画廊至少需要两张已就绪的图片'
        }
        for (const mediaId of payload.mediaAssetIds) {
          const normalized = mediaId.toLocaleLowerCase()
          if (!UUID.test(mediaId) || galleryIds.has(normalized)) {
            errors[`${path}.mediaAssetIds`] = '画廊图片编号必须有效且不能重复'
            break
          }
          galleryIds.add(normalized)
        }
        break
      }
      case 'VIDEO':
        if (!isSafeHttpsUrl(payload.url)) {
          errors[`${path}.url`] = '视频地址必须是安全的 HTTPS 链接'
        }
        if (payload.coverAssetId !== null && !isUuid(payload.coverAssetId)) {
          errors[`${path}.coverAssetId`] = '视频封面编号无效'
        }
        break
      case 'METRICS':
        if (payload.metrics.length === 0) {
          errors[`${path}.metrics`] = '指标模块至少需要一项指标'
        }
        {
          const metricOrders = new Set<number>()
        payload.metrics.forEach((metric, metricIndex) => {
          const metricPath = `${path}.metrics[${metricIndex}]`
          const metricId = metric.id.toLocaleLowerCase()
          if (!UUID.test(metric.id) || ids.has(metricId)) {
            errors[`${metricPath}.id`] = '指标必须使用唯一且有效的编号'
          }
          ids.add(metricId)
          if (
            !Number.isSafeInteger(metric.sortOrder) ||
            metric.sortOrder < 0 ||
            metricOrders.has(metric.sortOrder)
          ) {
            errors[`${metricPath}.sortOrder`] = '指标排序必须是唯一的非负整数'
          }
          metricOrders.add(metric.sortOrder)
          if (metric.numericValue !== null && !BIG_DECIMAL.test(metric.numericValue)) {
            errors[`${metricPath}.numericValue`] = '数值必须是有效的十进制文本'
          }
        })
        }
        break
      case 'DOWNLOAD': {
        const hasMedia = payload.mediaAssetId !== null
        const hasExternalUrl = payload.externalUrl !== null
        if (hasMedia === hasExternalUrl) {
          errors[`${path}.target`] = '下载项必须且只能选择媒体文件或外部链接之一'
        }
        if (hasMedia && !isUuid(payload.mediaAssetId)) {
          errors[`${path}.mediaAssetId`] = '下载媒体编号无效'
        }
        if (hasExternalUrl && !isSafeHttpsUrl(payload.externalUrl ?? '')) {
          errors[`${path}.externalUrl`] = '下载地址必须是安全的 HTTPS 链接'
        }
        break
      }
      case 'LINK':
        if (!isSafeHttpsUrl(payload.url)) {
          errors[`${path}.url`] = '链接必须是安全的 HTTPS 地址'
        }
        break
      default:
        assertNever(payload)
    }
  })

  return errors
}
