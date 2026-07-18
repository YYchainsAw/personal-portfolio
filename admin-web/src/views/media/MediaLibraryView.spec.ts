import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { afterEach, describe, expect, it, vi } from 'vitest'

const routeHooks = vi.hoisted(() => ({
  leaveGuards: [] as Array<() => boolean>,
}))

vi.mock('vue-router', async (importOriginal) => {
  const actual = await importOriginal<typeof import('vue-router')>()
  return {
    ...actual,
    onBeforeRouteLeave: (guard: () => boolean) => routeHooks.leaveGuards.push(guard),
  }
})

import type {
  MediaListOptions,
  UpdateMediaTranslationsRequest,
} from '@/api/mediaApi'
import { ApiProblem, type Page } from '@/types/api'
import type {
  MediaAssetView,
  MediaTranslationInput,
} from '@/types/content'

import MediaLibraryView from './MediaLibraryView.vue'

const mounted: VueWrapper[] = []

const uuid = (value: number): string =>
  `10000000-0000-4000-8000-${value.toString().padStart(12, '0')}`

function translation(
  locale: 'zh-CN' | 'en',
  overrides: Partial<MediaTranslationInput> = {},
): MediaTranslationInput {
  return {
    locale,
    altText: locale === 'zh-CN' ? '作品截图' : 'Gameplay screenshot',
    caption: locale === 'zh-CN' ? '战斗场景' : 'Combat scene',
    credit: locale === 'zh-CN' ? '易嘉轩' : 'Yi Jiaxuan',
    sourceUrl: null,
    ...overrides,
  }
}

function image(
  id = uuid(1),
  overrides: Partial<MediaAssetView> = {},
): MediaAssetView {
  return {
    id,
    originalFilename: `work-${id.at(-1)}.png`,
    mimeType: 'image/png',
    byteSize: 4096,
    width: 1600,
    height: 900,
    sha256: 'a'.repeat(64),
    status: 'READY',
    version: 3,
    createdAt: '2026-07-18T00:00:00Z',
    updatedAt: '2026-07-18T00:00:01Z',
    translations: [translation('zh-CN'), translation('en')],
    variants: [
      { name: 'w640', width: 640, height: 360, status: 'READY' },
      { name: 'w1280', width: 1280, height: 720, status: 'READY' },
    ],
    ...overrides,
  }
}

function pdf(
  id = uuid(2),
  overrides: Partial<MediaAssetView> = {},
): MediaAssetView {
  return image(id, {
    originalFilename: 'resume.pdf',
    mimeType: 'application/pdf',
    width: null,
    height: null,
    variants: [{ name: 'document', width: null, height: null, status: 'READY' }],
    ...overrides,
  })
}

function page(
  items: MediaAssetView[],
  pageNumber = 0,
  totalItems = items.length,
  size = 24,
): Page<MediaAssetView> {
  return {
    items,
    page: pageNumber,
    size,
    totalItems,
    totalPages: totalItems === 0 ? 0 : Math.ceil(totalItems / size),
  }
}

interface Deferred<T> {
  readonly promise: Promise<T>
  readonly resolve: (value: T) => void
}

function deferred<T>(): Deferred<T> {
  let resolve!: (value: T) => void
  const promise = new Promise<T>((accept) => {
    resolve = accept
  })
  return { promise, resolve }
}

async function selectFile(wrapper: VueWrapper, file: File): Promise<void> {
  const input = wrapper.get<HTMLInputElement>('input[type="file"]')
  Object.defineProperty(input.element, 'files', {
    configurable: true,
    value: [file],
  })
  await input.trigger('change')
}

function problem(
  status: number,
  code: string,
  title = '安全的错误信息',
  traceId = 'trace-media',
): ApiProblem {
  return new ApiProblem({
    type: 'about:blank',
    title,
    status,
    code,
    traceId,
  })
}

async function mountLibrary(options: {
  load?: (options: Readonly<MediaListOptions>) => Promise<Page<MediaAssetView>>
  get?: (id: string) => Promise<MediaAssetView>
  upload?: (file: File) => Promise<MediaAssetView>
  updateTranslations?: (
    id: string,
    request: Readonly<UpdateMediaTranslationsRequest>,
  ) => Promise<MediaAssetView>
  archive?: (id: string) => Promise<void>
  previewUrl?: (id: string, variant: string) => string
} = {}) {
  const first = image()
  const load = vi.fn(options.load ?? (async () => page([first, pdf()])))
  const get = vi.fn(options.get ?? (async (id: string) => (id === first.id ? first : pdf())))
  const upload = vi.fn(options.upload ?? (async () => image(uuid(9), { status: 'PROCESSING', version: 0, variants: [] })))
  const updateTranslations = vi.fn(
    options.updateTranslations ??
      (async (_id: string, request: Readonly<UpdateMediaTranslationsRequest>) =>
        image(first.id, {
          version: request.expectedVersion + 1,
          translations: [...request.translations],
        })),
  )
  const archive = vi.fn(options.archive ?? (async () => undefined))
  const previewUrl = vi.fn(
    options.previewUrl ??
      ((id: string, variant: string) =>
        `/api/admin/media/${encodeURIComponent(id)}/preview/${encodeURIComponent(variant)}`),
  )
  const wrapper = mount(MediaLibraryView, {
    attachTo: document.body,
    props: { load, get, upload, updateTranslations, archive, previewUrl },
  })
  mounted.push(wrapper)
  await flushPromises()
  return { wrapper, load, get, upload, updateTranslations, archive, previewUrl }
}

afterEach(() => {
  for (const wrapper of mounted.splice(0)) wrapper.unmount()
  routeHooks.leaveGuards.length = 0
  vi.useRealTimers()
  vi.restoreAllMocks()
})

describe('MediaLibraryView', () => {
  it('renders loading, safe error retry, empty, pagination, and server status boundaries', async () => {
    const gate = deferred<Page<MediaAssetView>>()
    const load = vi
      .fn<(options: Readonly<MediaListOptions>) => Promise<Page<MediaAssetView>>>()
      .mockReturnValueOnce(gate.promise)
      .mockRejectedValueOnce(problem(500, 'MEDIA_LIST_FAILED', '无法加载媒体', 'trace-list'))
      .mockResolvedValueOnce(page([]))
      .mockResolvedValueOnce(page([image(uuid(3), { status: 'FAILED' })]))
    const wrapper = mount(MediaLibraryView, { props: { load } })
    mounted.push(wrapper)
    await wrapper.vm.$nextTick()

    expect(wrapper.find('[data-state="library-loading"] [role="status"]').exists()).toBe(true)
    gate.resolve(page([image()], 0, 25))
    await flushPromises()
    expect(wrapper.get('[data-action="next-page"]').attributes()).not.toHaveProperty('disabled')

    await wrapper.get('[data-action="next-page"]').trigger('click')
    await flushPromises()
    expect(wrapper.get('[role="alert"]').text()).toContain('无法加载媒体')
    expect(wrapper.text()).toContain('trace-list')

    await wrapper.get('[data-action="retry-library-load"]').trigger('click')
    await flushPromises()
    expect(wrapper.find('[data-state="library-empty"]').exists()).toBe(true)

    await wrapper.get<HTMLSelectElement>('[data-filter="status"]').setValue('FAILED')
    await flushPromises()
    expect(load).toHaveBeenLastCalledWith({ page: 0, size: 24, status: 'FAILED' })
    expect(wrapper.find(`[data-media-id="${uuid(3)}"]`).exists()).toBe(true)
  })

  it('filters kind and text only inside the loaded page without changing pagination requests', async () => {
    const { wrapper, load } = await mountLibrary()
    expect(load).toHaveBeenCalledTimes(1)

    await wrapper.get<HTMLSelectElement>('[data-filter="kind"]').setValue('PDF')
    expect(wrapper.findAll('li[data-media-id]')).toHaveLength(1)
    expect(wrapper.get('li[data-media-id]').attributes('data-media-kind')).toBe('PDF')

    await wrapper.get<HTMLInputElement>('[data-filter="text"]').setValue('does-not-match')
    expect(wrapper.find('[data-state="filtered-empty"]').exists()).toBe(true)
    expect(load).toHaveBeenCalledTimes(1)
  })

  it('falls back with GET only when concurrent removal leaves the requested page out of range', async () => {
    const survivor = image(uuid(4))
    const directLoad = vi
      .fn<(options: Readonly<MediaListOptions>) => Promise<Page<MediaAssetView>>>()
      .mockResolvedValueOnce(page([], 2, 25))
      .mockResolvedValueOnce(page([survivor], 1, 25))
    const direct = mount(MediaLibraryView, { props: { load: directLoad } })
    mounted.push(direct)
    await flushPromises()
    expect(directLoad.mock.calls.map(([request]) => request.page)).toEqual([0, 1])
    expect(direct.find(`li[data-media-id="${survivor.id}"]`).exists()).toBe(true)
  })

  it('bounds an invalid page fallback instead of recursively issuing GET requests', async () => {
    const load = vi.fn().mockResolvedValue(page([], 2, 25))
    const { wrapper } = await mountLibrary({ load })

    expect(load).toHaveBeenCalledTimes(2)
    expect(load.mock.calls.map(([request]) => request.page)).toEqual([0, 1])
    expect(wrapper.get('[role="alert"]').text()).toContain('分页结果仍然超出有效范围')
  })

  it('rejects unsafe MIME and size boundaries before upload and never previews local bytes', async () => {
    const createObjectUrl = vi.spyOn(URL, 'createObjectURL')
    const { wrapper, upload } = await mountLibrary()
    await selectFile(wrapper, new File(['<svg/>'], 'unsafe.svg', { type: 'image/svg+xml' }))
    expect(wrapper.get('[data-upload-error]').text()).toContain('JPEG')
    expect(upload).not.toHaveBeenCalled()

    const tooLarge = new File(['x'], 'large.png', { type: 'image/png' })
    Object.defineProperty(tooLarge, 'size', { value: 25 * 1024 * 1024 + 1 })
    await selectFile(wrapper, tooLarge)
    expect(wrapper.get('[data-upload-error]').text()).toContain('25 MiB')
    expect(createObjectUrl).not.toHaveBeenCalled()
  })

  it('uploads once, shows PROCESSING immediately, and polls no faster than five seconds until READY', async () => {
    vi.useFakeTimers()
    const uploaded = image(uuid(9), { status: 'PROCESSING', version: 0, variants: [] })
    const ready = image(uploaded.id, { status: 'READY', version: 1 })
    const { wrapper, upload, get } = await mountLibrary({
      upload: vi.fn().mockResolvedValue(uploaded),
      get: vi.fn().mockResolvedValue(ready),
    })

    const file = new File(['png'], 'work.png', { type: 'image/png' })
    await selectFile(wrapper, file)
    await wrapper.get('[data-action="upload"]').trigger('click')
    await flushPromises()

    expect(upload).toHaveBeenCalledOnce()
    expect(upload).toHaveBeenCalledWith(file)
    expect(wrapper.get('[data-upload-result]').attributes('data-status')).toBe('PROCESSING')
    expect(get).not.toHaveBeenCalled()

    await vi.advanceTimersByTimeAsync(4_999)
    expect(get).not.toHaveBeenCalled()
    await vi.advanceTimersByTimeAsync(1)
    await flushPromises()
    expect(get).toHaveBeenCalledOnce()
    expect(get).toHaveBeenCalledWith(uploaded.id)
    expect(wrapper.get('[data-upload-result]').attributes('data-status')).toBe('READY')

    await vi.advanceTimersByTimeAsync(10_000)
    expect(get).toHaveBeenCalledOnce()
  })

  it('does not erase a successful upload when an older list snapshot arrives later', async () => {
    const staleList = deferred<Page<MediaAssetView>>()
    const uploaded = image(uuid(9), { status: 'PROCESSING', version: 0, variants: [] })
    const { wrapper } = await mountLibrary({
      load: () => staleList.promise,
      upload: async () => uploaded,
    })

    await selectFile(wrapper, new File(['png'], 'new-work.png', { type: 'image/png' }))
    await wrapper.get('[data-action="upload"]').trigger('click')
    await flushPromises()
    expect(wrapper.find(`[data-media-id="${uploaded.id}"]`).exists()).toBe(true)

    staleList.resolve(page([]))
    await flushPromises()
    expect(wrapper.find(`[data-media-id="${uploaded.id}"]`).exists()).toBe(true)
    expect(wrapper.get('[data-upload-result]').attributes('data-status')).toBe('PROCESSING')
  })

  it('keeps polling every processing asset after consecutive uploads', async () => {
    vi.useFakeTimers()
    const first = image(uuid(8), { status: 'PROCESSING', version: 0, variants: [] })
    const second = image(uuid(9), { status: 'PROCESSING', version: 0, variants: [] })
    const get = vi.fn((id: string) =>
      Promise.resolve(image(id, { status: 'READY', version: 1 })),
    )
    const { wrapper, upload } = await mountLibrary({
      upload: vi.fn().mockResolvedValueOnce(first).mockResolvedValueOnce(second),
      get,
    })

    await selectFile(wrapper, new File(['a'], 'first.png', { type: 'image/png' }))
    await wrapper.get('[data-action="upload"]').trigger('click')
    await flushPromises()
    await selectFile(wrapper, new File(['b'], 'second.png', { type: 'image/png' }))
    await wrapper.get('[data-action="upload"]').trigger('click')
    await flushPromises()

    expect(upload).toHaveBeenCalledTimes(2)
    await vi.advanceTimersByTimeAsync(5_000)
    await flushPromises()
    expect(get).toHaveBeenNthCalledWith(1, first.id)
    await vi.advanceTimersByTimeAsync(5_000)
    await flushPromises()
    expect(get).toHaveBeenNthCalledWith(2, second.id)
    expect(wrapper.get('[data-upload-result]').attributes('data-status')).toBe('READY')
  })

  it('pauses upload polling while hidden and cancels timers and late results on unmount', async () => {
    vi.useFakeTimers()
    let visibility: DocumentVisibilityState = 'hidden'
    vi.spyOn(document, 'visibilityState', 'get').mockImplementation(() => visibility)
    const poll = deferred<MediaAssetView>()
    const { wrapper, get } = await mountLibrary({
      upload: vi.fn().mockResolvedValue(image(uuid(9), { status: 'PROCESSING', variants: [] })),
      get: vi.fn().mockReturnValue(poll.promise),
    })

    await selectFile(wrapper, new File(['png'], 'work.png', { type: 'image/png' }))
    await wrapper.get('[data-action="upload"]').trigger('click')
    await flushPromises()
    await vi.advanceTimersByTimeAsync(20_000)
    expect(get).not.toHaveBeenCalled()

    visibility = 'visible'
    document.dispatchEvent(new Event('visibilitychange'))
    await vi.advanceTimersByTimeAsync(5_000)
    expect(get).toHaveBeenCalledOnce()
    wrapper.unmount()
    poll.resolve(image(uuid(9), { status: 'READY', version: 1 }))
    await flushPromises()
    await vi.advanceTimersByTimeAsync(10_000)
    expect(get).toHaveBeenCalledOnce()
  })

  it('latches an uncertain upload and offers GET-only list refresh without a second POST', async () => {
    const upload = vi.fn().mockRejectedValue(problem(0, 'NETWORK_ERROR', '网络中断'))
    const { wrapper, load } = await mountLibrary({ upload })
    await selectFile(wrapper, new File(['png'], 'work.png', { type: 'image/png' }))
    await wrapper.get('[data-action="upload"]').trigger('click')
    await flushPromises()

    expect(wrapper.find('[data-upload-uncertain]').exists()).toBe(true)
    await wrapper.get('[data-action="refresh-after-upload"]').trigger('click')
    await flushPromises()
    expect(load).toHaveBeenCalledTimes(2)
    expect(upload).toHaveBeenCalledOnce()
    expect(wrapper.get('[data-action="upload"]').attributes()).toHaveProperty('disabled')
  })

  it('opens a focused image detail, renders both locale forms, and submits exactly both rows', async () => {
    const base = image()
    const updated = image(base.id, {
      version: 4,
      translations: [
        translation('zh-CN', { sourceUrl: 'https://example.com/zh' }),
        translation('en'),
      ],
    })
    const get = vi.fn().mockResolvedValue(base)
    const updateTranslations = vi.fn().mockResolvedValue(updated)
    const { wrapper } = await mountLibrary({ get, updateTranslations })

    await wrapper.get(`[data-action="open-media"][data-media-id="${base.id}"]`).trigger('click')
    await flushPromises()
    expect(document.activeElement).toBe(wrapper.get('[data-detail-title]').element)
    expect(wrapper.findAll('[data-translation-locale]')).toHaveLength(2)
    expect(wrapper.get('[data-image-preview]').attributes('src')).toBe(
      `/api/admin/media/${base.id}/preview/w1280`,
    )

    await wrapper
      .get<HTMLInputElement>('[data-field="zh-CN.sourceUrl"]')
      .setValue('http://example.com/not-safe')
    await wrapper.get('[data-action="save-translations"]').trigger('click')
    expect(updateTranslations).not.toHaveBeenCalled()
    expect(wrapper.find('[data-field-error="zh-CN.sourceUrl"]').exists()).toBe(true)

    await wrapper
      .get<HTMLInputElement>('[data-field="zh-CN.sourceUrl"]')
      .setValue('https://example.com/zh')
    await wrapper.get('[data-action="save-translations"]').trigger('click')
    await flushPromises()

    expect(get).toHaveBeenCalledTimes(2)
    expect(updateTranslations).toHaveBeenCalledWith(base.id, {
      expectedVersion: base.version,
      translations: [
        translation('zh-CN', { sourceUrl: 'https://example.com/zh' }),
        translation('en'),
      ],
    })
  })

  it('renders a PDF only as a safe attachment link and never as embedded HTML', async () => {
    const document = pdf()
    const { wrapper } = await mountLibrary({
      load: async () => page([document]),
      get: async () => document,
    })
    await wrapper.get('[data-action="open-media"]').trigger('click')
    await flushPromises()

    const link = wrapper.get('[data-pdf-preview]')
    expect(link.attributes('href')).toBe(
      `/api/admin/media/${document.id}/preview/document`,
    )
    expect(link.attributes('target')).toBe('_blank')
    expect(link.attributes('rel')).toContain('noopener')
    expect(wrapper.find('iframe').exists()).toBe(false)
    expect(wrapper.find('object').exists()).toBe(false)
    expect(wrapper.find('embed').exists()).toBe(false)
  })

  it('maps wrapped translation validation paths back to the exact locale field', async () => {
    const base = image()
    const get = vi.fn().mockResolvedValue(base)
    const updateTranslations = vi.fn().mockRejectedValue(
      new ApiProblem({
        type: 'about:blank',
        title: '双语说明校验失败',
        status: 422,
        code: 'VALIDATION_FAILED',
        traceId: 'trace-field',
        fieldErrors: {
          'translations[0].sourceUrl': '来源地址不符合后端规则。',
        },
      }),
    )
    const { wrapper } = await mountLibrary({ get, updateTranslations })

    await wrapper.get('[data-action="open-media"]').trigger('click')
    await flushPromises()
    await wrapper
      .get<HTMLInputElement>('[data-field="zh-CN.sourceUrl"]')
      .setValue('https://example.com/changed')
    await wrapper.get('[data-action="save-translations"]').trigger('click')
    await flushPromises()

    expect(wrapper.get('[data-field-error="zh-CN.sourceUrl"]').text()).toContain(
      '来源地址不符合后端规则',
    )
  })

  it('ignores a late detail response after another asset is selected', async () => {
    const first = image(uuid(1))
    const second = image(uuid(2), { originalFilename: 'second.png' })
    const firstGate = deferred<MediaAssetView>()
    const secondGate = deferred<MediaAssetView>()
    const { wrapper } = await mountLibrary({
      load: async () => page([first, second]),
      get: (id) => (id === first.id ? firstGate.promise : secondGate.promise),
    })

    await wrapper.get(`[data-action="open-media"][data-media-id="${first.id}"]`).trigger('click')
    await wrapper.get(`[data-action="open-media"][data-media-id="${second.id}"]`).trigger('click')
    secondGate.resolve(second)
    await flushPromises()
    firstGate.resolve(first)
    await flushPromises()

    expect(wrapper.get('[data-media-detail]').attributes('data-media-id')).toBe(second.id)
    expect(wrapper.get('[data-detail-title]').text()).toContain('second.png')
  })

  it('does not PUT after a preflight detects a newer server translation version', async () => {
    const base = image()
    const newer = image(base.id, {
      version: base.version + 1,
      translations: [translation('zh-CN', { caption: '服务器更新' }), translation('en')],
    })
    const get = vi.fn().mockResolvedValueOnce(base).mockResolvedValueOnce(newer)
    const updateTranslations = vi.fn()
    const { wrapper } = await mountLibrary({ get, updateTranslations })
    await wrapper.get('[data-action="open-media"]').trigger('click')
    await flushPromises()
    await wrapper.get<HTMLTextAreaElement>('[data-field="zh-CN.caption"]').setValue('本地更新')
    await wrapper.get('[data-action="save-translations"]').trigger('click')
    await flushPromises()

    expect(updateTranslations).not.toHaveBeenCalled()
    expect(wrapper.find('[data-save-conflict]').exists()).toBe(true)
    expect(wrapper.get<HTMLTextAreaElement>('[data-field="zh-CN.caption"]').element.value).toBe('本地更新')
  })

  it('handles an atomic version conflict after preflight without overwriting either copy', async () => {
    const base = image()
    const newer = image(base.id, {
      version: base.version + 1,
      translations: [translation('zh-CN', { caption: '另一位管理员的更新' }), translation('en')],
    })
    const get = vi
      .fn()
      .mockResolvedValueOnce(base)
      .mockResolvedValueOnce(base)
      .mockResolvedValueOnce(newer)
    const updateTranslations = vi
      .fn()
      .mockRejectedValue(problem(409, 'MEDIA_VERSION_CONFLICT', '媒体版本已变化'))
    const { wrapper } = await mountLibrary({ get, updateTranslations })

    await wrapper.get('[data-action="open-media"]').trigger('click')
    await flushPromises()
    await wrapper.get<HTMLTextAreaElement>('[data-field="zh-CN.caption"]').setValue('我的本地更新')
    await wrapper.get('[data-action="save-translations"]').trigger('click')
    await flushPromises()

    expect(updateTranslations).toHaveBeenCalledWith(base.id, {
      expectedVersion: base.version,
      translations: [translation('zh-CN', { caption: '我的本地更新' }), translation('en')],
    })
    expect(get).toHaveBeenCalledTimes(3)
    expect(wrapper.find('[data-save-conflict]').exists()).toBe(true)
    expect(wrapper.get<HTMLTextAreaElement>('[data-field="zh-CN.caption"]').element.value).toBe(
      '我的本地更新',
    )

    await wrapper.get('[data-action="load-conflict-version"]').trigger('click')
    await flushPromises()
    expect(get).toHaveBeenCalledTimes(3)
    expect(wrapper.get<HTMLTextAreaElement>('[data-field="zh-CN.caption"]').element.value).toBe(
      '另一位管理员的更新',
    )
  })

  it('reconciles an uncertain translation save using GET only and never sends a second PUT', async () => {
    const base = image()
    const submitted = [translation('zh-CN', { caption: '本地更新' }), translation('en')]
    const applied = image(base.id, { version: base.version + 1, translations: submitted })
    const get = vi.fn().mockResolvedValueOnce(base).mockResolvedValueOnce(base).mockResolvedValueOnce(applied)
    const updateTranslations = vi.fn().mockRejectedValue(problem(0, 'NETWORK_ERROR', '结果未知'))
    const { wrapper } = await mountLibrary({ get, updateTranslations })
    await wrapper.get('[data-action="open-media"]').trigger('click')
    await flushPromises()
    await wrapper.get<HTMLTextAreaElement>('[data-field="zh-CN.caption"]').setValue('本地更新')
    await wrapper.get('[data-action="save-translations"]').trigger('click')
    await flushPromises()

    expect(wrapper.find('[data-save-uncertain]').exists()).toBe(true)
    await wrapper.get('[data-action="reconcile-save"]').trigger('click')
    await flushPromises()
    expect(updateTranslations).toHaveBeenCalledOnce()
    expect(get).toHaveBeenCalledTimes(3)
    expect(wrapper.find('[data-save-uncertain]').exists()).toBe(false)
  })

  it('confirms archive, exposes referenced 409 safely, and never claims physical deletion', async () => {
    const confirm = vi.spyOn(window, 'confirm').mockReturnValue(true)
    const archive = vi.fn().mockRejectedValue(
      problem(409, 'MEDIA_STILL_REFERENCED', '仍被作品引用', 'trace-reference'),
    )
    const { wrapper } = await mountLibrary({ archive })
    await wrapper.get('[data-action="open-media"]').trigger('click')
    await flushPromises()
    await wrapper.get('[data-action="archive-media"]').trigger('click')
    await flushPromises()

    expect(confirm).toHaveBeenCalledOnce()
    expect(archive).toHaveBeenCalledOnce()
    expect(wrapper.get('[data-archive-problem]').text()).toContain('仍被作品引用')
    expect(wrapper.get('[data-archive-problem]').text()).toContain('trace-reference')
    expect(wrapper.text()).not.toContain('永久删除')
    expect(wrapper.text()).not.toContain('物理删除完成')
  })

  it('inserts a newly archived asset when the operator switches to ARCHIVED before completion', async () => {
    const base = image()
    const load = vi
      .fn<(options: Readonly<MediaListOptions>) => Promise<Page<MediaAssetView>>>()
      .mockResolvedValueOnce(page([base]))
      .mockResolvedValueOnce(page([]))
    vi.spyOn(window, 'confirm').mockReturnValue(true)
    const { wrapper } = await mountLibrary({ load, get: async () => base })

    await wrapper.get('[data-action="open-media"]').trigger('click')
    await flushPromises()
    await wrapper.get<HTMLSelectElement>('[data-filter="status"]').setValue('ARCHIVED')
    await flushPromises()
    expect(wrapper.find(`li[data-media-id="${base.id}"]`).exists()).toBe(false)

    await wrapper.get('[data-action="archive-media"]').trigger('click')
    await flushPromises()
    expect(wrapper.get(`li[data-media-id="${base.id}"]`).attributes('data-status')).toBe('ARCHIVED')
  })

  it('reconciles the unfiltered page without double-counting an archived asset from another page', async () => {
    const base = image()
    const secondPageAsset = image(uuid(7), { originalFilename: 'second-page.png' })
    const load = vi
      .fn<(options: Readonly<MediaListOptions>) => Promise<Page<MediaAssetView>>>()
      .mockResolvedValueOnce(page([base], 0, 25))
      .mockResolvedValueOnce(page([secondPageAsset], 1, 25))
      .mockResolvedValueOnce(page([secondPageAsset], 1, 25))
    vi.spyOn(window, 'confirm').mockReturnValue(true)
    const { wrapper } = await mountLibrary({ load, get: async () => base })

    await wrapper.get('[data-action="open-media"]').trigger('click')
    await flushPromises()
    await wrapper.get('[data-action="next-page"]').trigger('click')
    await flushPromises()
    expect(wrapper.find(`li[data-media-id="${base.id}"]`).exists()).toBe(false)

    await wrapper.get('[data-action="archive-media"]').trigger('click')
    await flushPromises()
    expect(load).toHaveBeenCalledTimes(3)
    expect(wrapper.find(`li[data-media-id="${base.id}"]`).exists()).toBe(false)
    expect(wrapper.find(`li[data-media-id="${secondPageAsset.id}"]`).exists()).toBe(true)
  })

  it('reconciles an uncertain archive with GET only, removes READY results, and restores focus safely', async () => {
    const base = image()
    const archived = image(base.id, { status: 'ARCHIVED', version: base.version + 1 })
    vi.spyOn(window, 'confirm').mockReturnValue(true)
    const get = vi.fn().mockResolvedValueOnce(base).mockResolvedValueOnce(archived)
    const archive = vi.fn().mockRejectedValue(problem(0, 'NETWORK_ERROR', '结果未知'))
    const { wrapper } = await mountLibrary({
      load: async () => page([base]),
      get,
      archive,
    })
    await wrapper.get<HTMLSelectElement>('[data-filter="status"]').setValue('READY')
    await flushPromises()
    const opener = wrapper.get('[data-action="open-media"]')
    ;(opener.element as HTMLElement).focus()
    await opener.trigger('click')
    await flushPromises()
    await wrapper.get('[data-action="archive-media"]').trigger('click')
    await flushPromises()
    expect(wrapper.find('[data-archive-uncertain]').exists()).toBe(true)

    await wrapper.get('[data-action="reconcile-archive"]').trigger('click')
    await flushPromises()
    expect(archive).toHaveBeenCalledOnce()
    expect(get).toHaveBeenCalledTimes(2)
    expect(wrapper.find('[data-media-id]').exists()).toBe(false)
    expect(document.activeElement).toBe(wrapper.get('#media-library-title').element)
  })

  it('does not resurrect an archived resource from an older in-flight list response', async () => {
    const base = image()
    const concurrentReady = image(base.id, { version: base.version + 1 })
    const staleList = deferred<Page<MediaAssetView>>()
    const archiveGate = deferred<void>()
    const load = vi
      .fn()
      .mockResolvedValueOnce(page([base]))
      .mockReturnValueOnce(staleList.promise)
      .mockResolvedValueOnce(page([concurrentReady]))
    vi.spyOn(window, 'confirm').mockReturnValue(true)
    const { wrapper } = await mountLibrary({
      load,
      get: async () => base,
      archive: () => archiveGate.promise,
    })

    await wrapper.get('[data-action="open-media"]').trigger('click')
    await flushPromises()
    await wrapper.get('[data-action="archive-media"]').trigger('click')
    await wrapper.get<HTMLSelectElement>('[data-filter="status"]').setValue('READY')
    await flushPromises()

    archiveGate.resolve()
    await flushPromises()
    expect(wrapper.find(`[data-media-id="${base.id}"]`).exists()).toBe(false)

    staleList.resolve(page([concurrentReady]))
    await flushPromises()
    expect(wrapper.find(`[data-media-id="${base.id}"]`).exists()).toBe(false)
  })

  it('guards in-app and browser navigation while media work is unfinished', async () => {
    const { wrapper } = await mountLibrary()
    expect(routeHooks.leaveGuards).toHaveLength(1)
    expect(routeHooks.leaveGuards[0]?.()).toBe(true)

    await wrapper.get('[data-action="open-media"]').trigger('click')
    await flushPromises()
    await wrapper
      .get<HTMLTextAreaElement>('[data-field="zh-CN.caption"]')
      .setValue('尚未保存的本地说明')

    const confirm = vi.spyOn(window, 'confirm').mockReturnValue(false)
    expect(routeHooks.leaveGuards[0]?.()).toBe(false)
    expect(confirm).toHaveBeenCalledWith('当前媒体操作或双语说明尚未确认完成。确定离开吗？')

    const unload = new Event('beforeunload', { cancelable: true })
    window.dispatchEvent(unload)
    expect(unload.defaultPrevented).toBe(true)

    confirm.mockReturnValue(true)
    expect(routeHooks.leaveGuards[0]?.()).toBe(true)
  })
})
