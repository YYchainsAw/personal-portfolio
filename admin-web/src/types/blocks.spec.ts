import { describe, expect, expectTypeOf, it } from 'vitest'

import {
  BLOCK_ALIGNMENTS,
  BLOCK_EMPHASES,
  BLOCK_TYPES,
  BLOCK_WIDTHS,
  VIDEO_PROVIDERS,
  createBlock,
  normalizeContentBlock,
  type BlockAlignment,
  type BlockEmphasis,
  type BlockType,
  type BlockWidth,
  type ContentBlockPayload,
  type MetricDto,
  type VideoProvider,
} from './blocks'

const UUID_V4 =
  /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i

type IsAny<Value> = 0 extends 1 & Value ? true : false

const BLOCK_ID = '30000000-0000-4000-8000-000000000100'
const ASSET_ID = '30000000-0000-4000-8000-000000000200'
const SECOND_ASSET_ID = '30000000-0000-4000-8000-000000000201'
const METRIC_ID = '30000000-0000-4000-8000-000000000300'

function wireBlock(payload: unknown): unknown {
  return {
    id: BLOCK_ID,
    sortOrder: 0,
    visible: true,
    width: 'STANDARD',
    alignment: 'LEFT',
    emphasis: 'NONE',
    columns: 1,
    payload,
  }
}

const blockCopy = {
  'zh-CN': { title: '标题', description: '描述' },
  en: { title: 'Title', description: 'Description' },
}

const actionCopy = {
  'zh-CN': { label: '操作', description: '描述' },
  en: { label: 'Action', description: 'Description' },
}

describe('block wire enums', () => {
  it('matches the Java enum and validator vocabularies exactly', () => {
    expect(BLOCK_TYPES).toEqual([
      'MARKDOWN',
      'IMAGE',
      'GALLERY',
      'VIDEO',
      'CODE',
      'QUOTE',
      'METRICS',
      'DOWNLOAD',
      'LINK',
    ])
    expect(BLOCK_WIDTHS).toEqual(['NARROW', 'STANDARD', 'WIDE', 'FULL'])
    expect(BLOCK_ALIGNMENTS).toEqual(['LEFT', 'CENTER', 'RIGHT'])
    expect(BLOCK_EMPHASES).toEqual(['NONE', 'SOFT', 'STRONG'])
    expect(VIDEO_PROVIDERS).toEqual(['BILIBILI', 'YOUTUBE', 'VIMEO'])

    for (const vocabulary of [
      BLOCK_TYPES,
      BLOCK_WIDTHS,
      BLOCK_ALIGNMENTS,
      BLOCK_EMPHASES,
      VIDEO_PROVIDERS,
    ]) {
      expect(Object.isFrozen(vocabulary)).toBe(true)
    }
  })

  it('keeps every vocabulary narrow and excludes invalid wire values', () => {
    expectTypeOf<BlockType>().toEqualTypeOf<(typeof BLOCK_TYPES)[number]>()
    expectTypeOf<BlockWidth>().toEqualTypeOf<(typeof BLOCK_WIDTHS)[number]>()
    expectTypeOf<BlockAlignment>().toEqualTypeOf<(typeof BLOCK_ALIGNMENTS)[number]>()
    expectTypeOf<BlockEmphasis>().toEqualTypeOf<(typeof BLOCK_EMPHASES)[number]>()
    expectTypeOf<VideoProvider>().toEqualTypeOf<(typeof VIDEO_PROVIDERS)[number]>()

    if (false) {
      // @ts-expect-error Java uses uppercase enum names only.
      const invalidWidth: BlockWidth = 'standard'
      // @ts-expect-error WorkspaceValidator rejects lowercase providers.
      const invalidProvider: VideoProvider = 'youtube'
      // @ts-expect-error AUDIO is not a registered Jackson discriminator.
      const invalidType: BlockType = 'AUDIO'
      void [invalidWidth, invalidProvider, invalidType]
    }
  })
})

describe('createBlock', () => {
  it.each(BLOCK_TYPES)('creates %s with exact shared defaults and a UUID v4', (type) => {
    const block = createBlock(type)

    expect(block).toMatchObject({
      sortOrder: 0,
      visible: true,
      width: 'STANDARD',
      alignment: 'LEFT',
      emphasis: 'NONE',
      columns: 1,
      payload: { type },
    })
    expect(block.id).toMatch(UUID_V4)
  })

  it('creates fresh stable identifiers for separate draft blocks', () => {
    const first = createBlock('MARKDOWN')
    const second = createBlock('MARKDOWN')

    expect(first.id).not.toBe(second.id)
    expect(first.id).toBe(first.id)
    expect(second.id).toBe(second.id)
  })

  it('uses exact payload defaults for all nine Jackson discriminators', () => {
    expect(createBlock('MARKDOWN').payload).toEqual({
      type: 'MARKDOWN',
      markdown: { 'zh-CN': '', en: '' },
    })
    expect(createBlock('IMAGE').payload).toEqual({
      type: 'IMAGE',
      mediaAssetId: null,
    })
    expect(createBlock('GALLERY').payload).toEqual({
      type: 'GALLERY',
      mediaAssetIds: [],
    })
    expect(createBlock('VIDEO').payload).toEqual({
      type: 'VIDEO',
      provider: 'BILIBILI',
      url: '',
      coverAssetId: null,
      copy: {
        'zh-CN': { title: '', description: '' },
        en: { title: '', description: '' },
      },
    })
    expect(createBlock('CODE').payload).toEqual({
      type: 'CODE',
      code: '',
      language: 'text',
      showLineNumbers: true,
      copy: {
        'zh-CN': { title: '', description: '' },
        en: { title: '', description: '' },
      },
    })
    expect(createBlock('QUOTE').payload).toEqual({
      type: 'QUOTE',
      copy: {
        'zh-CN': { quote: '', source: '' },
        en: { quote: '', source: '' },
      },
    })
    expect(createBlock('METRICS').payload).toEqual({
      type: 'METRICS',
      metrics: [],
    })
    expect(createBlock('DOWNLOAD').payload).toEqual({
      type: 'DOWNLOAD',
      mediaAssetId: null,
      externalUrl: null,
      copy: {
        'zh-CN': { label: '', description: '' },
        en: { label: '', description: '' },
      },
    })
    expect(createBlock('LINK').payload).toEqual({
      type: 'LINK',
      url: '',
      openNewTab: true,
      copy: {
        'zh-CN': { label: '', description: '' },
        en: { label: '', description: '' },
      },
    })
  })

  it('creates independent locale leaves, payload arrays, and block instances', () => {
    const firstVideo = createBlock('VIDEO')
    const secondVideo = createBlock('VIDEO')
    const firstGallery = createBlock('GALLERY')
    const secondGallery = createBlock('GALLERY')
    const firstMetrics = createBlock('METRICS')
    const secondMetrics = createBlock('METRICS')

    expect(firstVideo.payload.copy['zh-CN']).not.toBe(firstVideo.payload.copy.en)
    expect(firstVideo.payload.copy['zh-CN']).not.toBe(secondVideo.payload.copy['zh-CN'])
    expect(firstGallery.payload.mediaAssetIds).not.toBe(secondGallery.payload.mediaAssetIds)
    expect(firstMetrics.payload.metrics).not.toBe(secondMetrics.payload.metrics)

    firstVideo.payload.copy['zh-CN'].title = '中文标题'
    firstGallery.payload.mediaAssetIds.push('30000000-0000-4000-8000-000000000001')

    expect(firstVideo.payload.copy.en.title).toBe('')
    expect(secondVideo.payload.copy['zh-CN'].title).toBe('')
    expect(secondGallery.payload.mediaAssetIds).toEqual([])
  })

  it('keeps the Java nullable boundaries without widening required fields', () => {
    expect(createBlock('IMAGE').payload.mediaAssetId).toBeNull()
    expect(createBlock('VIDEO').payload.coverAssetId).toBeNull()
    expect(createBlock('DOWNLOAD').payload).toMatchObject({
      mediaAssetId: null,
      externalUrl: null,
    })

    const metricWithNumber: MetricDto = {
      id: '30000000-0000-4000-8000-000000000002',
      sortOrder: 0,
      numericValue: '1.5',
      copy: {
        'zh-CN': { label: '', value: '', suffix: '' },
        en: { label: '', value: '', suffix: '' },
      },
    }
    const metricWithoutNumber: MetricDto = {
      ...metricWithNumber,
      numericValue: null,
    }

    expect(metricWithNumber.numericValue).toBe('1.5')
    expect(metricWithoutNumber.numericValue).toBeNull()

    if (false) {
      // @ts-expect-error LinkPayload.url is a required Java URI component.
      createBlock('LINK').payload.url = null
      // @ts-expect-error GalleryPayload always owns a non-null list.
      createBlock('GALLERY').payload.mediaAssetIds = null
    }
  })

  it('returns a precisely narrowed payload for a literal discriminator', () => {
    expectTypeOf(createBlock('MARKDOWN').payload).toMatchTypeOf<{
      type: 'MARKDOWN'
      markdown: { 'zh-CN': string; en: string }
    }>()
    expectTypeOf(createBlock('IMAGE').payload.mediaAssetId).toEqualTypeOf<string | null>()
    expectTypeOf(createBlock('VIDEO').payload.provider).toEqualTypeOf<VideoProvider>()
    expectTypeOf(createBlock('DOWNLOAD').payload.externalUrl).toEqualTypeOf<string | null>()
    expectTypeOf<IsAny<ContentBlockPayload>>().toEqualTypeOf<false>()
  })

  it('fails closed when an untyped caller supplies an unknown discriminator', () => {
    expect(() => createBlock('AUDIO' as BlockType)).toThrowError(
      'Unsupported content block type: AUDIO',
    )
  })
})

describe('ContentBlockPayload exhaustiveness', () => {
  it('narrows every payload variant to its exact fields', () => {
    const describePayload = (payload: ContentBlockPayload): string => {
      switch (payload.type) {
        case 'MARKDOWN':
          return payload.markdown.en
        case 'IMAGE':
          return payload.mediaAssetId ?? ''
        case 'GALLERY':
          return String(payload.mediaAssetIds.length)
        case 'VIDEO':
          return `${payload.provider}:${payload.url}:${payload.coverAssetId ?? ''}`
        case 'CODE':
          return `${payload.language}:${payload.showLineNumbers}:${payload.code}`
        case 'QUOTE':
          return payload.copy.en.quote
        case 'METRICS':
          return String(payload.metrics.length)
        case 'DOWNLOAD':
          return payload.mediaAssetId ?? payload.externalUrl ?? ''
        case 'LINK':
          return `${payload.url}:${payload.openNewTab}`
        default: {
          const exhaustive: never = payload
          return exhaustive
        }
      }
    }

    expect(BLOCK_TYPES.map((type) => describePayload(createBlock(type).payload))).toEqual([
      '',
      '',
      '0',
      'BILIBILI::',
      'text:true:',
      '',
      '0',
      '',
      ':true',
    ])
  })
})

describe('normalizeContentBlock', () => {
  it('accepts all nine exact backend payloads', () => {
    const values = [
      wireBlock({
        type: 'MARKDOWN',
        markdown: { 'zh-CN': '正文', en: 'Body' },
      }),
      wireBlock({ type: 'IMAGE', mediaAssetId: ASSET_ID }),
      wireBlock({ type: 'GALLERY', mediaAssetIds: [ASSET_ID, SECOND_ASSET_ID] }),
      wireBlock({
        type: 'VIDEO',
        provider: 'YOUTUBE',
        url: 'https://example.test/video',
        coverAssetId: ASSET_ID,
        copy: blockCopy,
      }),
      wireBlock({
        type: 'CODE',
        code: 'const answer = 42',
        language: 'typescript',
        showLineNumbers: true,
        copy: blockCopy,
      }),
      wireBlock({
        type: 'QUOTE',
        copy: {
          'zh-CN': { quote: '引用', source: '来源' },
          en: { quote: 'Quote', source: 'Source' },
        },
      }),
      wireBlock({
        type: 'METRICS',
        metrics: [
          {
            id: METRIC_ID,
            sortOrder: 0,
            numericValue: '42.5',
            copy: {
              'zh-CN': { label: '指标', value: '42.5', suffix: '%' },
              en: { label: 'Metric', value: '42.5', suffix: '%' },
            },
          },
        ],
      }),
      wireBlock({
        type: 'DOWNLOAD',
        mediaAssetId: ASSET_ID,
        externalUrl: null,
        copy: actionCopy,
      }),
      wireBlock({
        type: 'LINK',
        url: 'https://example.test/project',
        openNewTab: false,
        copy: actionCopy,
      }),
    ]

    const normalized = values.map(normalizeContentBlock)

    expect(normalized).not.toContain(null)
    expect(normalized.map((block) => block?.payload.type)).toEqual(BLOCK_TYPES)
    expect(normalized[0]).not.toBe(values[0])
  })

  it('preserves an arbitrary-precision BigDecimal wire string exactly', () => {
    const precise = '9007199254740993.12345678901234567890'
    const normalized = normalizeContentBlock(
      wireBlock({
        type: 'METRICS',
        metrics: [
          {
            id: METRIC_ID,
            sortOrder: 0,
            numericValue: precise,
            copy: {
              'zh-CN': { label: '精确值', value: precise, suffix: '' },
              en: { label: 'Precise value', value: precise, suffix: '' },
            },
          },
        ],
      }),
    )

    expect(normalized?.payload).toMatchObject({
      type: 'METRICS',
      metrics: [{ numericValue: precise }],
    })
  })

  it('normalizes fields omitted by Jackson NON_NULL back to explicit null', () => {
    const video = normalizeContentBlock(
      wireBlock({
        type: 'VIDEO',
        provider: 'VIMEO',
        url: 'https://example.test/video',
        copy: blockCopy,
      }),
    )
    const metrics = normalizeContentBlock(
      wireBlock({
        type: 'METRICS',
        metrics: [
          {
            id: METRIC_ID,
            sortOrder: 0,
            copy: {
              'zh-CN': { label: '', value: '', suffix: '' },
              en: { label: '', value: '', suffix: '' },
            },
          },
        ],
      }),
    )
    const assetDownload = normalizeContentBlock(
      wireBlock({ type: 'DOWNLOAD', mediaAssetId: ASSET_ID, copy: actionCopy }),
    )
    const externalDownload = normalizeContentBlock(
      wireBlock({
        type: 'DOWNLOAD',
        externalUrl: 'https://example.test/resume.pdf',
        copy: actionCopy,
      }),
    )

    expect(video?.payload).toMatchObject({ type: 'VIDEO', coverAssetId: null })
    expect(metrics?.payload).toMatchObject({
      type: 'METRICS',
      metrics: [{ numericValue: null }],
    })
    expect(assetDownload?.payload).toMatchObject({
      type: 'DOWNLOAD',
      mediaAssetId: ASSET_ID,
      externalUrl: null,
    })
    expect(externalDownload?.payload).toMatchObject({
      type: 'DOWNLOAD',
      mediaAssetId: null,
      externalUrl: 'https://example.test/resume.pdf',
    })
  })

  it('also accepts explicit null for every NON_NULL-compatible optional field', () => {
    const video = normalizeContentBlock(
      wireBlock({
        type: 'VIDEO',
        provider: 'BILIBILI',
        url: 'HTTPS://example.test/video',
        coverAssetId: null,
        copy: blockCopy,
      }),
    )
    const metrics = normalizeContentBlock(
      wireBlock({
        type: 'METRICS',
        metrics: [
          {
            id: METRIC_ID,
            sortOrder: 0,
            numericValue: null,
            copy: {
              'zh-CN': { label: '', value: '', suffix: '' },
              en: { label: '', value: '', suffix: '' },
            },
          },
        ],
      }),
    )
    const download = normalizeContentBlock(
      wireBlock({
        type: 'DOWNLOAD',
        mediaAssetId: null,
        externalUrl: 'HTTPS://example.test/file',
        copy: actionCopy,
      }),
    )

    expect(video).not.toBeNull()
    expect(metrics).not.toBeNull()
    expect(download).not.toBeNull()
  })

  it('restores Jackson-omitted BlockCopy leaves as editor-safe empty strings', () => {
    const video = normalizeContentBlock(
      wireBlock({
        type: 'VIDEO',
        provider: 'YOUTUBE',
        url: 'https://example.test/video',
        copy: {
          'zh-CN': { description: null },
          en: { title: 'Video' },
        },
      }),
    )
    const code = normalizeContentBlock(
      wireBlock({
        type: 'CODE',
        code: '',
        language: 'text',
        showLineNumbers: false,
        copy: {
          'zh-CN': {},
          en: { title: null, description: 'Example' },
        },
      }),
    )

    expect(video?.payload).toMatchObject({
      type: 'VIDEO',
      copy: {
        'zh-CN': { title: '', description: '' },
        en: { title: 'Video', description: '' },
      },
    })
    expect(code?.payload).toMatchObject({
      type: 'CODE',
      copy: {
        'zh-CN': { title: '', description: '' },
        en: { title: '', description: 'Example' },
      },
    })
  })

  it('restores Jackson-omitted ActionCopy leaves as editor-safe empty strings', () => {
    const download = normalizeContentBlock(
      wireBlock({
        type: 'DOWNLOAD',
        mediaAssetId: ASSET_ID,
        copy: {
          'zh-CN': { label: null },
          en: { description: 'Download the file' },
        },
      }),
    )
    const link = normalizeContentBlock(
      wireBlock({
        type: 'LINK',
        url: 'https://example.test/project',
        openNewTab: true,
        copy: {
          'zh-CN': {},
          en: { label: 'Open project', description: null },
        },
      }),
    )

    expect(download?.payload).toMatchObject({
      type: 'DOWNLOAD',
      copy: {
        'zh-CN': { label: '', description: '' },
        en: { label: '', description: 'Download the file' },
      },
    })
    expect(link?.payload).toMatchObject({
      type: 'LINK',
      copy: {
        'zh-CN': { label: '', description: '' },
        en: { label: 'Open project', description: '' },
      },
    })
  })

  it.each([
    ['null root', null],
    ['array root', []],
    ['missing root field', { ...wireBlock({ type: 'IMAGE', mediaAssetId: ASSET_ID }) as object, payload: undefined }],
    ['extra root key', { ...(wireBlock({ type: 'IMAGE', mediaAssetId: ASSET_ID }) as object), extra: true }],
    ['bad root UUID', { ...(wireBlock({ type: 'IMAGE', mediaAssetId: ASSET_ID }) as object), id: 'not-a-uuid' }],
    ['fractional sort order', { ...(wireBlock({ type: 'IMAGE', mediaAssetId: ASSET_ID }) as object), sortOrder: 0.5 }],
    ['unsafe sort order', { ...(wireBlock({ type: 'IMAGE', mediaAssetId: ASSET_ID }) as object), sortOrder: Number.MAX_SAFE_INTEGER + 1 }],
    ['bad width', { ...(wireBlock({ type: 'IMAGE', mediaAssetId: ASSET_ID }) as object), width: 'standard' }],
    ['bad alignment', { ...(wireBlock({ type: 'IMAGE', mediaAssetId: ASSET_ID }) as object), alignment: 'MIDDLE' }],
    ['bad emphasis', { ...(wireBlock({ type: 'IMAGE', mediaAssetId: ASSET_ID }) as object), emphasis: 'LOUD' }],
    ['columns below validator boundary', { ...(wireBlock({ type: 'IMAGE', mediaAssetId: ASSET_ID }) as object), columns: 0 }],
    ['columns above validator boundary', { ...(wireBlock({ type: 'IMAGE', mediaAssetId: ASSET_ID }) as object), columns: 5 }],
    ['unknown payload', wireBlock({ type: 'AUDIO', url: 'https://example.test' })],
    ['missing payload discriminator', wireBlock({ mediaAssetId: ASSET_ID })],
  ])('rejects malformed block structure: %s', (_label, value) => {
    expect(normalizeContentBlock(value)).toBeNull()
  })

  it.each([
    ['markdown missing locale', { type: 'MARKDOWN', markdown: { en: 'Body' } }],
    ['markdown extra locale', { type: 'MARKDOWN', markdown: { 'zh-CN': '正文', en: 'Body', fr: 'Corps' } }],
    ['markdown non-string leaf', { type: 'MARKDOWN', markdown: { 'zh-CN': '正文', en: null } }],
    ['image null asset', { type: 'IMAGE', mediaAssetId: null }],
    ['image bad asset UUID', { type: 'IMAGE', mediaAssetId: 'asset-1' }],
    ['gallery too short', { type: 'GALLERY', mediaAssetIds: [ASSET_ID] }],
    ['gallery bad asset UUID', { type: 'GALLERY', mediaAssetIds: [ASSET_ID, 'bad'] }],
    ['video bad provider', { type: 'VIDEO', provider: 'youtube', url: 'https://example.test', copy: blockCopy }],
    ['video unsafe URL', { type: 'VIDEO', provider: 'YOUTUBE', url: 'http://example.test', copy: blockCopy }],
    ['video bad cover UUID', { type: 'VIDEO', provider: 'YOUTUBE', url: 'https://example.test', coverAssetId: 'bad', copy: blockCopy }],
    ['video partial copy', { type: 'VIDEO', provider: 'YOUTUBE', url: 'https://example.test', copy: { en: blockCopy.en } }],
    ['code missing language', { type: 'CODE', code: '', showLineNumbers: true, copy: blockCopy }],
    ['code wrong line toggle', { type: 'CODE', code: '', language: 'text', showLineNumbers: 1, copy: blockCopy }],
    ['quote extra copy key', { type: 'QUOTE', copy: { ...blockCopy, fr: { title: '', description: '' } } }],
    ['metrics empty', { type: 'METRICS', metrics: [] }],
    ['metric bad UUID', { type: 'METRICS', metrics: [{ id: 'bad', sortOrder: 0, copy: { 'zh-CN': { label: '', value: '', suffix: '' }, en: { label: '', value: '', suffix: '' } } }] }],
    ['metric fractional order', { type: 'METRICS', metrics: [{ id: METRIC_ID, sortOrder: 0.5, copy: { 'zh-CN': { label: '', value: '', suffix: '' }, en: { label: '', value: '', suffix: '' } } }] }],
    ['metric numeric JSON number', { type: 'METRICS', metrics: [{ id: METRIC_ID, sortOrder: 0, numericValue: 42.5, copy: { 'zh-CN': { label: '', value: '', suffix: '' }, en: { label: '', value: '', suffix: '' } } }] }],
    ['metric malformed decimal', { type: 'METRICS', metrics: [{ id: METRIC_ID, sortOrder: 0, numericValue: '1.2.3', copy: { 'zh-CN': { label: '', value: '', suffix: '' }, en: { label: '', value: '', suffix: '' } } }] }],
    ['metric non-finite text', { type: 'METRICS', metrics: [{ id: METRIC_ID, sortOrder: 0, numericValue: 'Infinity', copy: { 'zh-CN': { label: '', value: '', suffix: '' }, en: { label: '', value: '', suffix: '' } } }] }],
    ['download has neither source', { type: 'DOWNLOAD', copy: actionCopy }],
    ['download has both sources', { type: 'DOWNLOAD', mediaAssetId: ASSET_ID, externalUrl: 'https://example.test/file', copy: actionCopy }],
    ['download unsafe external URL', { type: 'DOWNLOAD', externalUrl: 'javascript:alert(1)', copy: actionCopy }],
    ['link unsafe URL', { type: 'LINK', url: 'data:text/plain,bad', openNewTab: true, copy: actionCopy }],
    ['link missing toggle', { type: 'LINK', url: 'https://example.test', copy: actionCopy }],
    ['payload extra key', { type: 'LINK', url: 'https://example.test', openNewTab: true, copy: actionCopy, html: '<b>bad</b>' }],
    ['copy leaf extra key', { type: 'LINK', url: 'https://example.test', openNewTab: true, copy: { 'zh-CN': { ...actionCopy['zh-CN'], extra: true }, en: actionCopy.en } }],
  ])('rejects partial or illegal payloads: %s', (_label, payload) => {
    expect(normalizeContentBlock(wireBlock(payload))).toBeNull()
  })

  it('does not retain caller-owned nested references', () => {
    const input = wireBlock({
      type: 'VIDEO',
      provider: 'YOUTUBE',
      url: 'https://example.test/video',
      copy: blockCopy,
    })
    const normalized = normalizeContentBlock(input)

    expect(normalized).not.toBeNull()
    if (normalized?.payload.type !== 'VIDEO') {
      throw new Error('expected normalized video')
    }
    expect(normalized.payload.copy).not.toBe(blockCopy)
    expect(normalized.payload.copy.en).not.toBe(blockCopy.en)
  })
})
