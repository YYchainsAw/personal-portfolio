import { afterEach, describe, expect, it, vi } from 'vitest'

import { http } from './http'
import { isValidMediaSourceUrl, mediaApi } from './mediaApi'

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

function detailedAsset(id: string) {
  return {
    id,
    originalFilename: 'hero.png',
    mimeType: 'image/png',
    byteSize: 4096,
    width: 1280,
    height: 720,
    sha256: 'a'.repeat(64),
    status: 'READY',
    version: 2,
    createdAt: '2026-07-17T00:00:00Z',
    updatedAt: '2026-07-17T00:00:01.123456789Z',
    translations: [
      {
        locale: 'zh-CN',
        altText: 'Gameplay zh',
        caption: 'Caption zh',
        credit: 'Credit zh',
        sourceUrl: null,
      },
      {
        locale: 'en',
        altText: 'Gameplay',
        caption: 'Caption',
        credit: 'Credit',
        sourceUrl: 'https://example.com/source',
      },
    ],
    variants: [
      { name: 'w640', width: 640, height: 360, status: 'READY' },
      { name: 'w1280', width: 1280, height: 720, status: 'PROCESSING' },
    ],
  }
}

describe('mediaApi', () => {
  afterEach(() => {
    vi.restoreAllMocks()
  })

  it('gets and strictly normalizes one complete media asset view', async () => {
    const assetId = uuid(10)
    const response = detailedAsset(assetId)
    const get = vi.spyOn(http, 'get').mockResolvedValue({ data: response } as never)

    const result = await mediaApi.get(assetId)

    expect(get).toHaveBeenCalledOnce()
    expect(get).toHaveBeenCalledWith(`/api/admin/media/${assetId}`)
    expect(result).toEqual(response)
    expect(result).not.toBe(response)
    expect(result.translations).not.toBe(response.translations)
    expect(result.variants).not.toBe(response.variants)
  })

  it.each([
    {
      name: 'no translations or variants',
      build: (id: string) => ({
        ...detailedAsset(id),
        translations: [],
        variants: [],
      }),
    },
    {
      name: 'one existing translation',
      build: (id: string) => {
        const asset = detailedAsset(id)
        return { ...asset, translations: [asset.translations[1]] }
      },
    },
  ])('accepts a complete get response with $name', async ({ build }) => {
    const assetId = uuid(15)
    const response = build(assetId)
    vi.spyOn(http, 'get').mockResolvedValue({ data: response } as never)

    await expect(mediaApi.get(assetId)).resolves.toEqual(response)
  })

  it('rejects unsafe get ids before making a request', async () => {
    const get = vi.spyOn(http, 'get')

    for (const invalidId of ['', '.', '..', 'asset/id', `${uuid(11)}/..`, 'not-a-uuid']) {
      await expect(mediaApi.get(invalidId)).rejects.toThrow(TypeError)
    }
    expect(get).not.toHaveBeenCalled()
  })

  it('does not retry a failed get request', async () => {
    const assetId = uuid(14)
    const failure = new Error('network failed')
    const get = vi.spyOn(http, 'get').mockRejectedValue(failure)

    await expect(mediaApi.get(assetId)).rejects.toBe(failure)
    expect(get).toHaveBeenCalledOnce()
  })

  it.each([
    {
      name: 'a mismatched response id',
      build: (id: string) => ({ ...detailedAsset(id), id: uuid(12) }),
    },
    {
      name: 'an unexpected root field',
      build: (id: string) => ({ ...detailedAsset(id), objectKey: 'private/secret' }),
    },
    {
      name: 'a blank filename',
      build: (id: string) => ({ ...detailedAsset(id), originalFilename: ' ' }),
    },
    {
      name: 'an unsupported MIME type',
      build: (id: string) => ({ ...detailedAsset(id), mimeType: 'image/webp' }),
    },
    {
      name: 'an unsafe byte size',
      build: (id: string) => ({ ...detailedAsset(id), byteSize: Number.MAX_SAFE_INTEGER + 1 }),
    },
    {
      name: 'invalid image dimensions',
      build: (id: string) => ({ ...detailedAsset(id), width: null }),
    },
    {
      name: 'paired null dimensions on a processing image',
      build: (id: string) => ({
        ...detailedAsset(id),
        status: 'PROCESSING',
        width: null,
        height: null,
      }),
    },
    {
      name: 'dimensions on a PDF',
      build: (id: string) => ({ ...detailedAsset(id), mimeType: 'application/pdf' }),
    },
    {
      name: 'an invalid sha256',
      build: (id: string) => ({ ...detailedAsset(id), sha256: 'A'.repeat(64) }),
    },
    {
      name: 'an invalid asset status',
      build: (id: string) => ({ ...detailedAsset(id), status: 'DELETED' }),
    },
    {
      name: 'a pending-delete asset status',
      build: (id: string) => ({ ...detailedAsset(id), status: 'PENDING_DELETE' }),
    },
    {
      name: 'an invalid version',
      build: (id: string) => ({ ...detailedAsset(id), version: -1 }),
    },
    {
      name: 'an invalid timestamp',
      build: (id: string) => ({ ...detailedAsset(id), updatedAt: '2026-07-17' }),
    },
    {
      name: 'duplicate translations',
      build: (id: string) => {
        const asset = detailedAsset(id)
        return { ...asset, translations: [asset.translations[0], asset.translations[0]] }
      },
    },
    {
      name: 'an unsupported translation locale',
      build: (id: string) => {
        const asset = detailedAsset(id)
        return {
          ...asset,
          translations: [{ ...asset.translations[0], locale: 'fr' }],
        }
      },
    },
    {
      name: 'an unsafe translation source URL',
      build: (id: string) => {
        const asset = detailedAsset(id)
        return {
          ...asset,
          translations: [
            asset.translations[0],
            { ...asset.translations[1], sourceUrl: 'https://user:secret@example.com/#private' },
          ],
        }
      },
    },
    {
      name: 'a malformed variant',
      build: (id: string) => {
        const asset = detailedAsset(id)
        return {
          ...asset,
          variants: [{ name: 'w640', width: 641, height: 360, status: 'READY' }],
        }
      },
    },
  ])('rejects a get response containing $name', async ({ build }) => {
    const assetId = uuid(13)
    vi.spyOn(http, 'get').mockResolvedValue({ data: build(assetId) } as never)

    await expect(mediaApi.get(assetId)).rejects.toMatchObject({
      body: { code: 'INVALID_MEDIA_RESPONSE', traceId: 'client' },
    })
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
    await expect(mediaApi.search({ page: 2_147_483_648 })).rejects.toThrow(RangeError)
    await expect(mediaApi.search({ size: Number.MAX_SAFE_INTEGER + 1 })).rejects.toThrow(RangeError)
    expect(get).not.toHaveBeenCalled()
  })

  it('keeps an echoed empty READY page valid after concurrent archive', async () => {
    vi.spyOn(http, 'get').mockResolvedValue({
      data: { items: [], page: 2, size: 24, totalItems: 48, totalPages: 2 },
    } as never)

    await expect(mediaApi.search({ page: 2 })).resolves.toEqual({
      items: [],
      page: 2,
      size: 24,
      totalItems: 48,
      totalPages: 2,
    })
  })

  it('lists the exact management page without inventing a status filter', async () => {
    const ready = detailedAsset(uuid(20))
    const pendingDelete = {
      ...detailedAsset(uuid(21)),
      status: 'PENDING_DELETE',
      version: 8,
    }
    const get = vi.spyOn(http, 'get').mockResolvedValue({
      data: {
        items: [ready, pendingDelete],
        page: 0,
        size: 24,
        totalItems: 2,
        totalPages: 1,
      },
    } as never)

    const result = await mediaApi.list()

    expect(get).toHaveBeenCalledWith('/api/admin/media', {
      params: { page: 0, size: 24 },
    })
    expect(result.items).toEqual([ready, pendingDelete])
    expect(result.items[0]).not.toBe(ready)
    expect(result.items[1]).not.toBe(pendingDelete)
  })

  it('lists one requested status and rejects a mismatched item', async () => {
    const failed = { ...detailedAsset(uuid(22)), status: 'FAILED' }
    const get = vi.spyOn(http, 'get').mockResolvedValue({
      data: {
        items: [failed],
        page: 1,
        size: 1,
        totalItems: 2,
        totalPages: 2,
      },
    } as never)

    await expect(mediaApi.list({ page: 1, size: 1, status: 'FAILED' })).resolves.toEqual({
      items: [failed],
      page: 1,
      size: 1,
      totalItems: 2,
      totalPages: 2,
    })
    expect(get).toHaveBeenCalledWith('/api/admin/media', {
      params: { page: 1, size: 1, status: 'FAILED' },
    })

    get.mockResolvedValueOnce({
      data: {
        items: [{ ...failed, status: 'READY' }],
        page: 0,
        size: 24,
        totalItems: 1,
        totalPages: 1,
      },
    } as never)
    await expect(mediaApi.list({ status: 'FAILED' })).rejects.toMatchObject({
      body: { code: 'INVALID_MEDIA_RESPONSE' },
    })
  })

  it('accepts an echoed empty page that became out of range after concurrent removal', async () => {
    const get = vi.spyOn(http, 'get').mockResolvedValue({
      data: {
        items: [],
        page: 2,
        size: 24,
        totalItems: 24,
        totalPages: 1,
      },
    } as never)

    await expect(mediaApi.list({ page: 2 })).resolves.toEqual({
      items: [],
      page: 2,
      size: 24,
      totalItems: 24,
      totalPages: 1,
    })
    expect(get).toHaveBeenCalledWith('/api/admin/media', {
      params: { page: 2, size: 24 },
    })
  })

  it.each([
    {
      name: 'an unexpected page field',
      data: {
        items: [],
        page: 0,
        size: 24,
        totalItems: 0,
        totalPages: 0,
        objectKeys: [],
      },
    },
    {
      name: 'duplicate asset ids',
      data: {
        items: [detailedAsset(uuid(23)), detailedAsset(uuid(23))],
        page: 0,
        size: 24,
        totalItems: 2,
        totalPages: 1,
      },
    },
    {
      name: 'an invalid detailed item',
      data: {
        items: [{ ...detailedAsset(uuid(24)), objectKey: 'private/secret' }],
        page: 0,
        size: 24,
        totalItems: 1,
        totalPages: 1,
      },
    },
  ])('rejects a management page containing $name', async ({ data }) => {
    vi.spyOn(http, 'get').mockResolvedValue({ data } as never)

    await expect(mediaApi.list()).rejects.toMatchObject({
      body: { code: 'INVALID_MEDIA_RESPONSE', traceId: 'client' },
    })
  })

  it('validates management list options before transport', async () => {
    const get = vi.spyOn(http, 'get')

    await expect(mediaApi.list({ page: -1 })).rejects.toThrow(RangeError)
    await expect(mediaApi.list({ page: 2_147_483_648 })).rejects.toThrow(RangeError)
    await expect(mediaApi.list({ size: 0 })).rejects.toThrow(RangeError)
    await expect(mediaApi.list({ status: 'DELETED' as never })).rejects.toThrow(TypeError)
    expect(get).not.toHaveBeenCalled()
  })

  it('uploads exactly one multipart file with a bounded large-file timeout and no manual boundary', async () => {
    const file = new File(['valid-png'], 'work.png', { type: 'image/png' })
    const asset = {
      ...detailedAsset(uuid(25)),
      status: 'PROCESSING',
      version: 0,
      byteSize: file.size,
      mimeType: file.type,
      translations: [],
      variants: [],
    }
    const post = vi.spyOn(http, 'post').mockResolvedValue({ data: asset } as never)

    await expect(mediaApi.upload(file)).resolves.toEqual(asset)

    expect(post).toHaveBeenCalledOnce()
    expect(post.mock.calls[0]?.[0]).toBe('/api/admin/media')
    const body = post.mock.calls[0]?.[1]
    expect(body).toBeInstanceOf(FormData)
    expect([...((body as FormData).entries())]).toEqual([['file', file]])
    expect(post.mock.calls[0]?.[2]).toEqual({ timeout: 180_000 })
    expect(JSON.stringify(post.mock.calls[0]?.[2])).not.toContain('Content-Type')
  })

  it('rejects an empty upload before transport and never retries a failed upload', async () => {
    const post = vi.spyOn(http, 'post').mockRejectedValue(new Error('network failed'))

    await expect(
      mediaApi.upload(new File([], 'empty.png', { type: 'image/png' })),
    ).rejects.toThrow(TypeError)
    expect(post).not.toHaveBeenCalled()

    await expect(
      mediaApi.upload(new File(['png'], 'work.png', { type: 'image/png' })),
    ).rejects.toThrow('network failed')
    expect(post).toHaveBeenCalledOnce()
  })

  it('rejects unsupported and oversized upload files before transport', async () => {
    const post = vi.spyOn(http, 'post')
    const unsupported = new File(['webp'], 'work.webp', { type: 'image/webp' })
    const oversized = new File(['png'], 'work.png', { type: 'image/png' })
    Object.defineProperty(oversized, 'size', { value: 25 * 1024 * 1024 + 1 })

    await expect(mediaApi.upload(unsupported)).rejects.toThrow(TypeError)
    await expect(mediaApi.upload(oversized)).rejects.toThrow(TypeError)
    expect(post).not.toHaveBeenCalled()
  })

  it('rejects an upload response that does not identify the submitted bytes and MIME', async () => {
    const id = uuid(31)
    const file = new File(['png'], 'work.png', { type: 'image/png' })
    const base = {
      ...detailedAsset(id),
      status: 'PROCESSING',
      version: 0,
      byteSize: file.size,
      mimeType: file.type,
      translations: [],
      variants: [],
    }
    const post = vi
      .spyOn(http, 'post')
      .mockResolvedValueOnce({ data: { ...base, byteSize: file.size + 1 } } as never)
      .mockResolvedValueOnce({ data: { ...base, mimeType: 'image/jpeg' } } as never)

    await expect(mediaApi.upload(file)).rejects.toMatchObject({
      body: { code: 'INVALID_MEDIA_RESPONSE' },
    })
    await expect(mediaApi.upload(file)).rejects.toMatchObject({
      body: { code: 'INVALID_MEDIA_RESPONSE' },
    })
    expect(post).toHaveBeenCalledTimes(2)
  })

  it('sends both translation rows in canonical order with exact fields', async () => {
    const id = uuid(26)
    const input = [
      {
        locale: 'en' as const,
        altText: 'Gameplay',
        caption: 'Boss arena',
        credit: 'Yi Jiaxuan',
        sourceUrl: 'https://example.com/source',
      },
      {
        locale: 'zh-CN' as const,
        altText: '游戏画面',
        caption: '首领战场景',
        credit: '易嘉轩',
        sourceUrl: null,
      },
    ]
    const response = { ...detailedAsset(id), translations: [input[0], input[1]] }
    const put = vi.spyOn(http, 'put').mockResolvedValue({ data: response } as never)

    await expect(
      mediaApi.updateTranslations(id, { expectedVersion: 1, translations: input }),
    ).resolves.toEqual(response)

    expect(put).toHaveBeenCalledWith(`/api/admin/media/${id}/translations`, {
      expectedVersion: 1,
      translations: [input[1], input[0]],
    })
    expect(input[0]?.locale).toBe('en')
  })

  it('requires the translation response to advance exactly one requested version', async () => {
    const id = uuid(33)
    const translations = [
      { locale: 'zh-CN' as const, altText: '游戏画面', caption: '', credit: '', sourceUrl: null },
      { locale: 'en' as const, altText: 'Gameplay', caption: '', credit: '', sourceUrl: null },
    ]
    vi.spyOn(http, 'put').mockResolvedValue({
      data: { ...detailedAsset(id), version: 3, translations },
    } as never)

    await expect(
      mediaApi.updateTranslations(id, { expectedVersion: 1, translations }),
    ).rejects.toMatchObject({ body: { code: 'INVALID_MEDIA_RESPONSE' } })
  })

  it('shares the exact optional HTTPS source URL rule with form validation', () => {
    expect(isValidMediaSourceUrl('')).toBe(true)
    expect(isValidMediaSourceUrl('https://example.com/source?ref=portfolio')).toBe(true)
    expect(isValidMediaSourceUrl('https://example.com./source')).toBe(true)
    for (const invalid of [
      ' ',
      'http://example.com',
      'https://user:secret@example.com',
      'https://example.com/#private',
      'https://example.com/#',
      'https://example.com#',
      'https://example.com:',
      'https://example.com:000443/path',
      'https://example.com:000001/path',
      'https://例子.测试/path',
      'https://%65xample.com/path',
      'https://127.1/path',
      'https://01.02.03.04/path',
      'https://[fe80::1%25eth0]/path',
      'https://example.com\\evil/path',
    ]) {
      expect(isValidMediaSourceUrl(invalid)).toBe(false)
    }
  })

  it.each([
    {
      name: 'one locale only',
      input: [
        { locale: 'en', altText: '', caption: '', credit: '', sourceUrl: null },
      ],
    },
    {
      name: 'duplicate locales',
      input: [
        { locale: 'en', altText: '', caption: '', credit: '', sourceUrl: null },
        { locale: 'en', altText: '', caption: '', credit: '', sourceUrl: null },
      ],
    },
    {
      name: 'an unsafe source URL',
      input: [
        { locale: 'zh-CN', altText: '', caption: '', credit: '', sourceUrl: null },
        { locale: 'en', altText: '', caption: '', credit: '', sourceUrl: 'http://example.com' },
      ],
    },
    {
      name: 'an unexpected field',
      input: [
        { locale: 'zh-CN', altText: '', caption: '', credit: '', sourceUrl: null },
        { locale: 'en', altText: '', caption: '', credit: '', sourceUrl: null, objectKey: 'x' },
      ],
    },
  ])('rejects translation input containing $name before transport', async ({ input }) => {
    const put = vi.spyOn(http, 'put')

    await expect(
      mediaApi.updateTranslations(uuid(27), {
        expectedVersion: 1,
        translations: input as never,
      }),
    ).rejects.toThrow(TypeError)
    expect(put).not.toHaveBeenCalled()
  })

  it('never retries translation or archive mutations', async () => {
    const failure = new Error('network failed')
    const put = vi.spyOn(http, 'put').mockRejectedValue(failure)
    const remove = vi.spyOn(http, 'delete').mockRejectedValue(failure)
    const translations = [
      { locale: 'zh-CN' as const, altText: '', caption: '', credit: '', sourceUrl: null },
      { locale: 'en' as const, altText: '', caption: '', credit: '', sourceUrl: null },
    ]

    await expect(
      mediaApi.updateTranslations(uuid(28), { expectedVersion: 1, translations }),
    ).rejects.toBe(failure)
    await expect(mediaApi.archive(uuid(28))).rejects.toBe(failure)
    expect(put).toHaveBeenCalledOnce()
    expect(remove).toHaveBeenCalledOnce()
  })

  it('rejects mutation responses that do not prove the submitted result', async () => {
    const id = uuid(30)
    const translations = [
      { locale: 'zh-CN' as const, altText: '新说明', caption: '', credit: '', sourceUrl: null },
      { locale: 'en' as const, altText: 'New alt', caption: '', credit: '', sourceUrl: null },
    ]
    const put = vi.spyOn(http, 'put').mockResolvedValue({
      data: detailedAsset(id),
    } as never)
    const remove = vi.spyOn(http, 'delete').mockResolvedValue({
      status: 200,
      data: {},
    } as never)

    await expect(
      mediaApi.updateTranslations(id, { expectedVersion: 1, translations }),
    ).rejects.toMatchObject({
      body: { code: 'INVALID_MEDIA_RESPONSE' },
    })
    await expect(mediaApi.archive(id)).rejects.toMatchObject({
      body: { code: 'INVALID_MEDIA_RESPONSE' },
    })
    expect(put).toHaveBeenCalledOnce()
    expect(remove).toHaveBeenCalledOnce()
  })

  it.each([-1, 1.5, Number.MAX_SAFE_INTEGER])(
    'rejects invalid expectedVersion %s before transport',
    async (expectedVersion) => {
      const put = vi.spyOn(http, 'put')
      const translations = [
        { locale: 'zh-CN' as const, altText: '', caption: '', credit: '', sourceUrl: null },
        { locale: 'en' as const, altText: '', caption: '', credit: '', sourceUrl: null },
      ]

      await expect(
        mediaApi.updateTranslations(uuid(32), { expectedVersion, translations }),
      ).rejects.toThrow(TypeError)
      expect(put).not.toHaveBeenCalled()
    },
  )

  it('rejects unexpected translation-envelope fields before transport', async () => {
    const put = vi.spyOn(http, 'put')
    const translations = [
      { locale: 'zh-CN' as const, altText: '', caption: '', credit: '', sourceUrl: null },
      { locale: 'en' as const, altText: '', caption: '', credit: '', sourceUrl: null },
    ]

    await expect(
      mediaApi.updateTranslations(uuid(34), {
        expectedVersion: 1,
        translations,
        overwrite: true,
      } as never),
    ).rejects.toThrow(TypeError)
    expect(put).not.toHaveBeenCalled()
  })

  it('archives only UUID resources through the exact endpoint', async () => {
    const id = uuid(29)
    const remove = vi.spyOn(http, 'delete').mockResolvedValue({ status: 204 } as never)

    await expect(mediaApi.archive(id)).resolves.toBeUndefined()
    expect(remove).toHaveBeenCalledWith(`/api/admin/media/${id}`)

    await expect(mediaApi.archive('../asset')).rejects.toThrow(TypeError)
    expect(remove).toHaveBeenCalledOnce()
  })
})
