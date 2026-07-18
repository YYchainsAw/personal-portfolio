import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { afterEach, describe, expect, it, vi } from 'vitest'

import { ApiProblem, type Page } from '@/types/api'
import type { MediaAssetSummaryDto, MediaKind } from '@/types/content'

import MediaPickerDialog, {
  type MediaPickerLoad,
  type MediaPickerPageRequest,
} from './MediaPickerDialog.vue'

interface Deferred<T> {
  readonly promise: Promise<T>
  readonly resolve: (value: T) => void
  readonly reject: (cause: unknown) => void
}

function deferred<T>(): Deferred<T> {
  let resolve!: (value: T) => void
  let reject!: (cause: unknown) => void
  const promise = new Promise<T>((accept, decline) => {
    resolve = accept
    reject = decline
  })
  return { promise, resolve, reject }
}

const uuid = (value: number): string =>
  `10000000-0000-0000-0000-${value.toString().padStart(12, '0')}`

function asset(
  id: string,
  kind: MediaKind,
  status: MediaAssetSummaryDto['status'] = 'READY',
  originalFilename = `${id}.${kind === 'IMAGE' ? 'jpg' : 'pdf'}`,
): MediaAssetSummaryDto {
  return {
    id,
    kind,
    originalFilename,
    mimeType: kind === 'IMAGE' ? 'image/jpeg' : 'application/pdf',
    status,
    previewUrl: kind === 'IMAGE' ? `/api/admin/media/${id}/preview/w640` : null,
    width: kind === 'IMAGE' ? 1600 : null,
    height: kind === 'IMAGE' ? 900 : null,
  }
}

function page(
  items: MediaAssetSummaryDto[],
  currentPage = 0,
  totalPages = items.length === 0 ? 0 : 1,
): Page<MediaAssetSummaryDto> {
  return {
    items,
    page: currentPage,
    size: 24,
    totalItems: totalPages * 24,
    totalPages,
  }
}

const mounted: VueWrapper[] = []

function mountPicker(options: {
  load: MediaPickerLoad
  accept?: readonly MediaKind[]
  open?: boolean
}): VueWrapper {
  const wrapper = mount(MediaPickerDialog, {
    attachTo: document.body,
    props: {
      open: options.open ?? true,
      accept: options.accept ?? ['IMAGE'],
      load: options.load,
    },
  })
  mounted.push(wrapper)
  return wrapper
}

function unmountPicker(wrapper: VueWrapper): void {
  wrapper.unmount()
  const index = mounted.indexOf(wrapper)
  if (index >= 0) mounted.splice(index, 1)
}

afterEach(() => {
  for (const wrapper of mounted.splice(0)) wrapper.unmount()
  vi.restoreAllMocks()
})

describe('MediaPickerDialog', () => {
  it('exposes a labelled busy modal, loading state, and initial keyboard focus', async () => {
    const gate = deferred<Page<MediaAssetSummaryDto>>()
    const load = vi.fn<MediaPickerLoad>().mockReturnValue(gate.promise)
    const wrapper = mountPicker({ load })

    await flushPromises()

    const dialog = wrapper.get('[role="dialog"]')
    const title = wrapper.get('h2')
    expect(dialog.attributes('aria-modal')).toBe('true')
    expect(dialog.attributes('aria-labelledby')).toBe(title.attributes('id'))
    expect(dialog.attributes('aria-busy')).toBe('true')
    expect(wrapper.get('[role="status"]').text()).toContain('正在加载')
    expect(load).toHaveBeenCalledWith({ page: 0, size: 24 })
    expect(document.activeElement).toBe(wrapper.get('input[type="search"]').element)

    gate.resolve(page([asset(uuid(1), 'IMAGE')]))
    await flushPromises()
    expect(wrapper.find('[role="status"]').exists()).toBe(false)
    expect(dialog.attributes('aria-busy')).toBe('false')
  })

  it('filters locally and emits selection and close only once for a READY compatible asset', async () => {
    const heroId = uuid(2)
    const portraitId = uuid(3)
    const processingId = uuid(4)
    const resumeId = uuid(5)
    const spoofedId = uuid(6)
    const load = vi.fn<MediaPickerLoad>().mockResolvedValue(
      page([
        asset(heroId, 'IMAGE', 'READY', 'Hero cover.jpg'),
        asset(portraitId, 'IMAGE', 'READY', 'Portrait.jpg'),
        asset(processingId, 'IMAGE', 'PROCESSING', 'Hero pending.jpg'),
        asset(resumeId, 'PDF', 'READY', 'Hero resume.pdf'),
        { ...asset(spoofedId, 'IMAGE'), mimeType: 'application/pdf' },
      ]),
    )
    const wrapper = mountPicker({ load, accept: ['IMAGE'] })
    await flushPromises()

    expect(wrapper.find(`[data-asset-id="${heroId}"]`).exists()).toBe(true)
    expect(wrapper.find(`[data-asset-id="${portraitId}"]`).exists()).toBe(true)
    expect(wrapper.find(`[data-asset-id="${processingId}"]`).exists()).toBe(false)
    expect(wrapper.find(`[data-asset-id="${resumeId}"]`).exists()).toBe(false)
    expect(wrapper.find(`[data-asset-id="${spoofedId}"]`).exists()).toBe(false)

    await wrapper.get('input[type="search"]').setValue('  HERO  ')

    expect(load).toHaveBeenCalledTimes(1)
    expect(wrapper.find(`[data-asset-id="${heroId}"]`).exists()).toBe(true)
    expect(wrapper.find(`[data-asset-id="${portraitId}"]`).exists()).toBe(false)

    const hero = wrapper.get(`[data-asset-id="${heroId}"]`)
    await hero.trigger('click')
    await hero.trigger('click')

    expect(wrapper.emitted('select')).toEqual([[expect.objectContaining({ id: heroId })]])
    expect(wrapper.emitted('close')).toEqual([[]])
    expect(wrapper.emitted('update:open')).toEqual([[false]])
  })

  it('binds image previews to the same UUID and rejects dot segments and unsafe variants', async () => {
    const validId = uuid(7)
    const mismatchedId = uuid(8)
    const dotVariantId = uuid(9)
    const queryVariantId = uuid(10)
    const wrapper = mountPicker({
      load: vi.fn<MediaPickerLoad>().mockResolvedValue(
        page([
          asset(validId, 'IMAGE'),
          {
            ...asset(mismatchedId, 'IMAGE'),
            previewUrl: `/api/admin/media/${validId}/preview/w640`,
          },
          {
            ...asset(dotVariantId, 'IMAGE'),
            previewUrl: `/api/admin/media/${dotVariantId}/preview/..`,
          },
          {
            ...asset(queryVariantId, 'IMAGE'),
            previewUrl: `/api/admin/media/${queryVariantId}/preview/w640?download=true`,
          },
          asset('..', 'IMAGE'),
        ]),
      ),
    })
    await flushPromises()

    expect(wrapper.find(`[data-asset-id="${validId}"] img`).exists()).toBe(true)
    expect(wrapper.find(`[data-asset-id="${mismatchedId}"] img`).exists()).toBe(false)
    expect(wrapper.find(`[data-asset-id="${dotVariantId}"] img`).exists()).toBe(false)
    expect(wrapper.find(`[data-asset-id="${queryVariantId}"] img`).exists()).toBe(false)
    expect(wrapper.find('[data-asset-id=".."]').exists()).toBe(false)
    expect(wrapper.findAll('img')).toHaveLength(1)
  })

  it('renders a safe error and keeps focus in the dialog throughout retry', async () => {
    const problem = new ApiProblem({
      type: 'network_error',
      title: '媒体暂时无法加载',
      status: 503,
      code: 'MEDIA_UNAVAILABLE',
      traceId: 'trace-media-503',
    })
    problem.stack = 'SQL SECRET /etc/portfolio'
    const gate = deferred<Page<MediaAssetSummaryDto>>()
    const load = vi
      .fn<MediaPickerLoad>()
      .mockRejectedValueOnce(problem)
      .mockReturnValueOnce(gate.promise)
    const wrapper = mountPicker({ load })
    await flushPromises()

    const error = wrapper.get('[role="alert"]')
    expect(error.text()).toContain('媒体暂时无法加载')
    expect(error.text()).toContain('trace-media-503')
    expect(error.text()).not.toContain('SQL SECRET')
    expect(error.text()).not.toContain('/etc/portfolio')

    const retryButton = wrapper.get('[data-action="retry"]')
    ;(retryButton.element as HTMLElement).focus()
    await retryButton.trigger('click')

    const dialog = wrapper.get('[role="dialog"]')
    expect(document.activeElement).toBe(dialog.element)
    expect(dialog.attributes('aria-busy')).toBe('true')

    gate.resolve(page([]))
    await flushPromises()

    expect(load).toHaveBeenCalledTimes(2)
    expect(wrapper.get('[data-state="empty"]').text()).toContain('没有可选择的媒体')
    expect(document.activeElement).toBe(wrapper.get('input[type="search"]').element)
  })

  it('paginates explicitly, keeps focus contained, and keeps search local to the loaded page', async () => {
    const firstId = uuid(11)
    const secondId = uuid(12)
    const secondPage = deferred<Page<MediaAssetSummaryDto>>()
    const load = vi.fn((request: MediaPickerPageRequest) => {
      if (request.page === 0) return Promise.resolve(page([asset(firstId, 'IMAGE')], 0, 2))
      return secondPage.promise
    })
    const wrapper = mountPicker({ load })
    await flushPromises()

    expect(wrapper.get('[data-action="previous"]').attributes()).toHaveProperty('disabled')
    const next = wrapper.get('[data-action="next"]')
    expect(next.attributes()).not.toHaveProperty('disabled')
    ;(next.element as HTMLElement).focus()

    await next.trigger('click')

    const dialog = wrapper.get('[role="dialog"]')
    expect(document.activeElement).toBe(dialog.element)
    expect(dialog.attributes('aria-busy')).toBe('true')

    secondPage.resolve(page([asset(secondId, 'IMAGE')], 1, 2))
    await flushPromises()

    expect(load).toHaveBeenLastCalledWith({ page: 1, size: 24 })
    expect(wrapper.find(`[data-asset-id="${firstId}"]`).exists()).toBe(false)
    expect(wrapper.find(`[data-asset-id="${secondId}"]`).exists()).toBe(true)
    expect(wrapper.text()).toContain('第 2 / 2 页')
    expect(document.activeElement).toBe(wrapper.get('input[type="search"]').element)

    await wrapper.get('input[type="search"]').setValue('missing')
    expect(load).toHaveBeenCalledTimes(2)
    expect(wrapper.get('[data-state="empty"]').text()).toContain('当前页没有匹配')
  })

  it('retries the requested page after a pagination failure', async () => {
    const firstId = uuid(13)
    const secondId = uuid(14)
    let secondPageAttempts = 0
    const load = vi.fn(async (request: MediaPickerPageRequest) => {
      if (request.page === 0) return page([asset(firstId, 'IMAGE')], 0, 2)
      secondPageAttempts += 1
      if (secondPageAttempts === 1) throw new Error('private backend detail')
      return page([asset(secondId, 'IMAGE')], 1, 2)
    })
    const wrapper = mountPicker({ load })
    await flushPromises()

    await wrapper.get('[data-action="next"]').trigger('click')
    await flushPromises()
    expect(wrapper.get('[role="alert"]').text()).toContain('无法加载媒体资源')
    expect(wrapper.text()).not.toContain('private backend detail')

    await wrapper.get('[data-action="retry"]').trigger('click')
    await flushPromises()

    expect(load).toHaveBeenLastCalledWith({ page: 1, size: 24 })
    expect(wrapper.find(`[data-asset-id="${secondId}"]`).exists()).toBe(true)
    expect(wrapper.text()).toContain('第 2 / 2 页')
  })

  it.each([
    {
      name: 'unsafe integer',
      data: {
        items: [],
        page: 0,
        size: 24,
        totalItems: Number.MAX_SAFE_INTEGER + 1,
        totalPages: 0,
      },
    },
    {
      name: 'page echo mismatch',
      data: { items: [], page: 1, size: 24, totalItems: 48, totalPages: 2 },
    },
    {
      name: 'size echo mismatch',
      data: { items: [], page: 0, size: 12, totalItems: 0, totalPages: 0 },
    },
    {
      name: 'inconsistent totalPages',
      data: { items: [], page: 0, size: 24, totalItems: 25, totalPages: 1 },
    },
    {
      name: 'too many final-page items',
      data: {
        items: [asset(uuid(15), 'IMAGE'), asset(uuid(16), 'IMAGE')],
        page: 0,
        size: 24,
        totalItems: 1,
        totalPages: 1,
      },
    },
  ])('rejects malformed loader pagination: $name', async ({ data }) => {
    const load = vi
      .fn<MediaPickerLoad>()
      .mockResolvedValue(data as Page<MediaAssetSummaryDto>)
    const wrapper = mountPicker({ load })
    await flushPromises()

    expect(wrapper.get('[role="alert"]').text()).toContain('无法加载媒体资源')
    expect(wrapper.find('[data-asset-grid]').exists()).toBe(false)
  })

  it('rejects an out-of-range page returned after navigation', async () => {
    const firstId = uuid(17)
    const load = vi.fn(async (request: MediaPickerPageRequest) => {
      if (request.page === 0) return page([asset(firstId, 'IMAGE')], 0, 2)
      return {
        items: [],
        page: 1,
        size: 24,
        totalItems: 24,
        totalPages: 1,
      }
    })
    const wrapper = mountPicker({ load })
    await flushPromises()

    await wrapper.get('[data-action="next"]').trigger('click')
    await flushPromises()

    expect(wrapper.get('[role="alert"]').text()).toContain('无法加载媒体资源')
    expect(wrapper.text()).not.toContain('第 2 / 1 页')
  })

  it('uses an accurate current-page empty state when other pages may contain compatible media', async () => {
    const wrapper = mountPicker({
      load: vi.fn<MediaPickerLoad>().mockResolvedValue(
        page([asset(uuid(18), 'PDF')], 0, 2),
      ),
      accept: ['IMAGE'],
    })
    await flushPromises()

    const empty = wrapper.get('[data-state="empty"]')
    expect(empty.text()).toContain('当前页没有兼容的媒体')
    expect(empty.text()).toContain('浏览其他页面')
    expect(empty.text()).not.toContain('请先上传')
    expect(wrapper.get('[data-action="next"]').attributes()).not.toHaveProperty('disabled')
  })

  it('makes close idempotent, traps keyboard focus, and restores the opener', async () => {
    const opener = document.createElement('button')
    opener.type = 'button'
    opener.textContent = '打开媒体库'
    document.body.append(opener)
    opener.focus()
    const firstId = uuid(19)
    const secondId = uuid(20)
    const wrapper = mountPicker({
      load: vi.fn<MediaPickerLoad>().mockResolvedValue(
        page([asset(firstId, 'IMAGE'), asset(secondId, 'IMAGE')]),
      ),
    })

    try {
      await flushPromises()
      const first = wrapper.get(`[data-asset-id="${firstId}"]`)
      const second = wrapper.get(`[data-asset-id="${secondId}"]`)
      ;(first.element as HTMLElement).focus()
      await wrapper.get('[data-asset-grid]').trigger('keydown', { key: 'ArrowRight' })
      expect(document.activeElement).toBe(second.element)

      const close = wrapper.get('[data-action="close"]')
      ;(close.element as HTMLElement).focus()
      await wrapper.get('[role="dialog"]').trigger('keydown', { key: 'Tab', shiftKey: true })
      expect(document.activeElement).toBe(second.element)

      await wrapper.get('[role="dialog"]').trigger('keydown', { key: 'Escape' })
      await wrapper.get('[role="dialog"]').trigger('keydown', { key: 'Escape' })
      expect(wrapper.emitted('close')).toEqual([[]])
      expect(wrapper.emitted('update:open')).toEqual([[false]])

      await wrapper.setProps({ open: false })
      await flushPromises()
      expect(document.activeElement).toBe(opener)
    } finally {
      opener.remove()
    }
  })

  it('ignores pending loads after close and after unmount', async () => {
    const closeGate = deferred<Page<MediaAssetSummaryDto>>()
    const closingWrapper = mountPicker({
      load: vi.fn<MediaPickerLoad>().mockReturnValue(closeGate.promise),
    })
    await flushPromises()

    await closingWrapper.get('[role="dialog"]').trigger('keydown', { key: 'Escape' })
    await closingWrapper.get('[role="dialog"]').trigger('keydown', { key: 'Escape' })
    expect(closingWrapper.emitted('close')).toEqual([[]])
    expect(closingWrapper.find('[role="status"]').exists()).toBe(false)

    closeGate.resolve(page([asset(uuid(21), 'IMAGE')]))
    await flushPromises()
    expect(closingWrapper.find(`[data-asset-id="${uuid(21)}"]`).exists()).toBe(false)

    await closingWrapper.setProps({ open: false })
    await flushPromises()

    const unmountGate = deferred<Page<MediaAssetSummaryDto>>()
    const unmountedWrapper = mountPicker({
      load: vi.fn<MediaPickerLoad>().mockReturnValue(unmountGate.promise),
    })
    await flushPromises()
    unmountPicker(unmountedWrapper)

    unmountGate.resolve(page([asset(uuid(22), 'IMAGE')]))
    await flushPromises()
    expect(unmountedWrapper.exists()).toBe(false)
  })
})
