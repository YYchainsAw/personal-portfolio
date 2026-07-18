import { describe, expect, it } from 'vitest'

import { createBlock, type ContentBlockDto } from '@/types/blocks'

import { validateBlocks } from './blockValidation'

const MEDIA_ID = '23000000-0000-4000-8000-000000000001'
const SECOND_MEDIA_ID = '23000000-0000-4000-8000-000000000002'

function validBlock(type: ContentBlockDto['payload']['type']): ContentBlockDto {
  const block = createBlock(type)
  switch (block.payload.type) {
    case 'IMAGE':
      block.payload.mediaAssetId = MEDIA_ID
      break
    case 'GALLERY':
      block.payload.mediaAssetIds = [MEDIA_ID, SECOND_MEDIA_ID]
      break
    case 'VIDEO':
      block.payload.url = 'https://www.bilibili.com/video/BV1example'
      break
    case 'METRICS':
      block.payload.metrics = [
        {
          id: '24000000-0000-4000-8000-000000000001',
          sortOrder: 0,
          numericValue: '9007199254740993.12345678901234567890',
          copy: {
            'zh-CN': { label: '帧率', value: '60', suffix: 'FPS' },
            en: { label: 'Frame rate', value: '60', suffix: 'FPS' },
          },
        },
      ]
      break
    case 'DOWNLOAD':
      block.payload.mediaAssetId = MEDIA_ID
      break
    case 'LINK':
      block.payload.url = 'https://yychainsaw.xyz/projects/example'
      break
    default:
      break
  }
  return block
}

describe('validateBlocks', () => {
  it('accepts every complete block type and preserves lossless decimal strings', () => {
    const blocks = [
      'MARKDOWN',
      'IMAGE',
      'GALLERY',
      'VIDEO',
      'CODE',
      'QUOTE',
      'METRICS',
      'DOWNLOAD',
      'LINK',
    ].map((type, sortOrder) => ({
      ...validBlock(type as ContentBlockDto['payload']['type']),
      sortOrder,
    }))

    expect(validateBlocks(blocks)).toEqual({})
    const metrics = blocks[6]
    expect(metrics?.payload.type).toBe('METRICS')
    if (metrics?.payload.type === 'METRICS') {
      expect(metrics.payload.metrics[0]?.numericValue).toBe(
        '9007199254740993.12345678901234567890',
      )
    }
  })

  it.each([
    ['IMAGE', 'blocks[0].mediaAssetId'],
    ['GALLERY', 'blocks[0].mediaAssetIds'],
    ['VIDEO', 'blocks[0].url'],
    ['METRICS', 'blocks[0].metrics'],
    ['DOWNLOAD', 'blocks[0].target'],
    ['LINK', 'blocks[0].url'],
  ] as const)('keeps an incomplete %s block local instead of allowing autosave', (type, path) => {
    expect(validateBlocks([createBlock(type)])).toHaveProperty(path)
  })

  it.each(['javascript:alert(1)', 'data:text/html,hello', 'http://example.com']) (
    'rejects the unsafe URL protocol %s',
    (url) => {
      const link = validBlock('LINK')
      if (link.payload.type === 'LINK') link.payload.url = url
      const video = validBlock('VIDEO')
      if (video.payload.type === 'VIDEO') video.payload.url = url
      const download = validBlock('DOWNLOAD')
      if (download.payload.type === 'DOWNLOAD') {
        download.payload.mediaAssetId = null
        download.payload.externalUrl = url
      }

      expect(validateBlocks([link])).toHaveProperty('blocks[0].url')
      expect(validateBlocks([video])).toHaveProperty('blocks[0].url')
      expect(validateBlocks([download])).toHaveProperty('blocks[0].externalUrl')
    },
  )

  it.each([
    ' HTTPS://example.com',
    'HTTPS://example.com',
    'https://user@example.com',
    'https://example.com/project#fragment',
    'https://example.com:8443/project',
    'https://example.com/%0aheader',
    'https://exa\tmple.com/project',
  ])('rejects a URL that cannot survive persistence and public projection: %s', (url) => {
    const link = validBlock('LINK')
    if (link.payload.type !== 'LINK') throw new Error('invalid fixture')
    link.payload.url = url

    expect(validateBlocks([link])).toHaveProperty('blocks[0].url')
  })

  it.each([
    'https://example.com/{x}',
    'https://example.com/|x',
    'https://example.com/a\\b',
    'https://example.com/%zz',
  ])('rejects raw URLs that WHATWG would normalize but Java URI cannot persist: %s', (url) => {
    const link = validBlock('LINK')
    const video = validBlock('VIDEO')
    const download = validBlock('DOWNLOAD')
    if (
      link.payload.type !== 'LINK' ||
      video.payload.type !== 'VIDEO' ||
      download.payload.type !== 'DOWNLOAD'
    ) {
      throw new Error('invalid fixture')
    }
    link.payload.url = url
    video.payload.url = url
    download.payload.mediaAssetId = null
    download.payload.externalUrl = url

    expect(validateBlocks([link])).toHaveProperty('blocks[0].url')
    expect(validateBlocks([video])).toHaveProperty('blocks[0].url')
    expect(validateBlocks([download])).toHaveProperty('blocks[0].externalUrl')
  })

  it('requires exactly one download target', () => {
    const download = validBlock('DOWNLOAD')
    if (download.payload.type !== 'DOWNLOAD') throw new Error('invalid fixture')
    download.payload.externalUrl = 'https://cdn.yychainsaw.xyz/demo.zip'

    expect(validateBlocks([download])).toHaveProperty('blocks[0].target')
  })

  it('rejects duplicate gallery assets and invalid media ids', () => {
    const gallery = validBlock('GALLERY')
    if (gallery.payload.type !== 'GALLERY') throw new Error('invalid fixture')
    gallery.payload.mediaAssetIds = [MEDIA_ID, MEDIA_ID]
    const image = validBlock('IMAGE')
    if (image.payload.type !== 'IMAGE') throw new Error('invalid fixture')
    image.payload.mediaAssetId = 'not-a-uuid'

    expect(validateBlocks([gallery])).toHaveProperty('blocks[0].mediaAssetIds')
    expect(validateBlocks([image])).toHaveProperty('blocks[0].mediaAssetId')
  })

  it('requires stable unique ids and unique non-negative ordering for blocks and metrics', () => {
    const first = validBlock('MARKDOWN')
    const second = { ...validBlock('METRICS'), id: first.id, sortOrder: first.sortOrder }
    if (second.payload.type !== 'METRICS') throw new Error('invalid fixture')
    second.payload.metrics.push({
      ...second.payload.metrics[0]!,
      sortOrder: 0,
    })

    const errors = validateBlocks([first, second])
    expect(errors).toHaveProperty('blocks[1].id')
    expect(errors).toHaveProperty('blocks[1].sortOrder')
    expect(errors).toHaveProperty('blocks[1].metrics[1].id')
    expect(errors).toHaveProperty('blocks[1].metrics[1].sortOrder')
  })

  it.each(['not-a-number', 'Infinity', 'NaN', '1,000']) (
    'rejects malformed decimal text without coercing it: %s',
    (numericValue) => {
      const block = validBlock('METRICS')
      if (block.payload.type !== 'METRICS') throw new Error('invalid fixture')
      block.payload.metrics[0]!.numericValue = numericValue

      expect(validateBlocks([block])).toHaveProperty(
        'blocks[0].metrics[0].numericValue',
      )
    },
  )

  it('validates the shared column range', () => {
    const block = validBlock('MARKDOWN')
    block.columns = 5
    expect(validateBlocks([block])).toHaveProperty('blocks[0].columns')
  })
})
