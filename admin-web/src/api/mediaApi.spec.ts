import { afterEach, describe, expect, it, vi } from 'vitest'

import { http } from './http'
import { mediaApi } from './mediaApi'

const uuid = (value: number): string =>
  `10000000-0000-0000-0000-${value.toString().padStart(12, '0')}`

function image(id: string, overrides: Record<string, unknown> = {}): Record<string, unknown> {
  return {
    id,
    originalFilename: 'Hero Sunrise.JPG',
    mimeType: 'image/jpeg',
    status: 'READY',
    width: 1600,
    height: 900,
    variants: [
      { name: 'w1280', width: 1280, height: 720, status: 'READY' },
      { name: 'w640', width: 640, height: 360, status: 'READY' },
    ],
    ...overrides,
  }
}

describe('mediaApi', () => {
  afterEach(() => {
    vi.restoreAllMocks()
  })

  it('sends only page, size, and READY while applying kind and text filters locally', async () => {
    const heroId = uuid(1)
    const get = vi.spyOn(http, 'get').mockResolvedValue({
      data: {
        items: [
          {
            ...image(heroId),
            kind: 'PDF',
            previewUrl: 'https://attacker.invalid/not-trusted',
          },
          {
            id: uuid(2),
            originalFilename: 'Resume.pdf',
            mimeType: 'application/pdf',
            status: 'READY',
            width: null,
            height: null,
            variants: [{ name: 'document', width: null, height: null, status: 'READY' }],
          },
          {
            ...image(uuid(3), {
              originalFilename: 'hero-processing.png',
              mimeType: 'image/png',
              status: 'PROCESSING',
              variants: [],
            }),
          },
        ],
        page: 2,
        size: 24,
        totalItems: 51,
        totalPages: 3,
      },
    } as never)

    const result = await mediaApi.search({
      page: 2,
      size: 24,
      kind: 'IMAGE',
      text: '  sunrise  ',
    })

    expect(get).toHaveBeenCalledWith('/api/admin/media', {
      params: { page: 2, size: 24, status: 'READY' },
    })
    expect(result).toEqual({
      items: [
        {
          id: heroId,
          kind: 'IMAGE',
          originalFilename: 'Hero Sunrise.JPG',
          mimeType: 'image/jpeg',
          status: 'READY',
          previewUrl: `/api/admin/media/${heroId}/preview/w640`,
          width: 1600,
          height: 900,
        },
      ],
      page: 2,
      size: 24,
      totalItems: 51,
      totalPages: 3,
    })
    expect(JSON.stringify(get.mock.calls[0]?.[1])).not.toContain('kind')
    expect(JSON.stringify(get.mock.calls[0]?.[1])).not.toContain('text')
    expect(JSON.stringify(result)).not.toContain('attacker.invalid')
  })

  it('normalizes PDF previews and supports a local kind allowlist without changing pagination', async () => {
    const resumeId = uuid(4)
    vi.spyOn(http, 'get').mockResolvedValue({
      data: {
        items: [
          {
            id: resumeId,
            originalFilename: 'Yi-Jiaxuan.pdf',
            mimeType: 'application/pdf',
            status: 'READY',
            width: null,
            height: null,
            variants: [{ name: 'document', width: null, height: null, status: 'READY' }],
          },
          image(uuid(5), { originalFilename: 'portrait.jpg', width: 640, height: 640 }),
        ],
        page: 0,
        size: 24,
        totalItems: 2,
        totalPages: 1,
      },
    } as never)

    const result = await mediaApi.search({ kind: ['PDF', 'FILE'], text: '.PDF' })

    expect(result.items).toEqual([
      expect.objectContaining({
        id: resumeId,
        kind: 'PDF',
        previewUrl: `/api/admin/media/${resumeId}/preview/document`,
      }),
    ])
    expect(result.totalItems).toBe(2)
    expect(result.totalPages).toBe(1)
  })

  it('accepts only UUID resource ids and allowlisted preview variants', () => {
    const assetId = uuid(6)
    expect(mediaApi.previewUrl(assetId, 'w640')).toBe(
      `/api/admin/media/${assetId}/preview/w640`,
    )
    expect(mediaApi.previewUrl(assetId, 'document')).toBe(
      `/api/admin/media/${assetId}/preview/document`,
    )

    for (const invalidId of ['', '.', '..', 'asset/id', `${assetId}/..`, 'not-a-uuid']) {
      expect(() => mediaApi.previewUrl(invalidId, 'w640')).toThrow(TypeError)
    }
    for (const invalidVariant of [
      '',
      '.',
      '..',
      'w0',
      'w0640',
      'W640',
      'thumbnail',
      '../w640',
      'w640/large',
      'document/..',
    ]) {
      expect(() => mediaApi.previewUrl(assetId, invalidVariant)).toThrow(TypeError)
    }
  })

  it('drops invalid resource ids and never constructs previews from malformed variants', async () => {
    const wrongWidthId = uuid(7)
    const unsafeVariantId = uuid(8)
    const validId = uuid(9)
    vi.spyOn(http, 'get').mockResolvedValue({
      data: {
        items: [
          image('..'),
          image(wrongWidthId, {
            variants: [{ name: 'w640', width: 641, height: 360, status: 'READY' }],
          }),
          image(unsafeVariantId, {
            variants: [{ name: '../w640', width: 640, height: 360, status: 'READY' }],
          }),
          image(validId),
        ],
        page: 0,
        size: 24,
        totalItems: 4,
        totalPages: 1,
      },
    } as never)

    const result = await mediaApi.search()

    expect(result.items.map((item) => item.id)).toEqual([wrongWidthId, unsafeVariantId, validId])
    expect(result.items.find((item) => item.id === wrongWidthId)?.previewUrl).toBeNull()
    expect(result.items.find((item) => item.id === unsafeVariantId)?.previewUrl).toBeNull()
    expect(result.items.find((item) => item.id === validId)?.previewUrl).toBe(
      `/api/admin/media/${validId}/preview/w640`,
    )
  })

  it.each([
    {
      name: 'unsafe total item count',
      options: {},
      data: {
        items: [],
        page: 0,
        size: 24,
        totalItems: Number.MAX_SAFE_INTEGER + 1,
        totalPages: 0,
      },
    },
    {
      name: 'page request echo mismatch',
      options: {},
      data: { items: [], page: 1, size: 24, totalItems: 48, totalPages: 2 },
    },
    {
      name: 'size request echo mismatch',
      options: {},
      data: { items: [], page: 0, size: 12, totalItems: 0, totalPages: 0 },
    },
    {
      name: 'inconsistent totalPages',
      options: {},
      data: { items: [], page: 0, size: 24, totalItems: 25, totalPages: 1 },
    },
    {
      name: 'page outside the reported range',
      options: { page: 2 },
      data: { items: [], page: 2, size: 24, totalItems: 48, totalPages: 2 },
    },
    {
      name: 'too many items on the final page',
      options: { page: 2 },
      data: {
        items: [{}, {}],
        page: 2,
        size: 24,
        totalItems: 49,
        totalPages: 3,
      },
    },
  ])('rejects malformed pagination metadata: $name', async ({ options, data }) => {
    vi.spyOn(http, 'get').mockResolvedValue({ data } as never)

    await expect(mediaApi.search(options)).rejects.toMatchObject({
      body: { code: 'INVALID_MEDIA_RESPONSE', traceId: 'client' },
    })
  })

  it('rejects unsafe request pagination before making a request', async () => {
    const get = vi.spyOn(http, 'get')

    await expect(mediaApi.search({ page: Number.MAX_SAFE_INTEGER + 1 })).rejects.toThrow(RangeError)
    await expect(mediaApi.search({ size: Number.MAX_SAFE_INTEGER + 1 })).rejects.toThrow(RangeError)
    expect(get).not.toHaveBeenCalled()
  })
})
