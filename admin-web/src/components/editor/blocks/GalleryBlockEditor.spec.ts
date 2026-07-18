import { flushPromises, mount } from '@vue/test-utils'
import { defineComponent, ref, type PropType } from 'vue'
import { afterEach, describe, expect, it, vi } from 'vitest'

import type { MediaPickerLoad } from '@/components/media/MediaPickerDialog.vue'
import type { GalleryPayload } from '@/types/blocks'
import type {
  MediaAssetSummaryDto,
  MediaAssetView,
  MediaKind,
} from '@/types/content'

import GalleryBlockEditor from './GalleryBlockEditor.vue'

const firstId = '40000000-0000-4000-8000-000000000001'
const secondId = '40000000-0000-4000-8000-000000000002'
const thirdId = '40000000-0000-4000-8000-000000000003'
const mounted: Array<ReturnType<typeof mount>> = []

const MediaPickerStub = defineComponent({
  name: 'MediaPickerDialog',
  props: {
    open: Boolean,
    accept: { type: Array as PropType<readonly MediaKind[]>, required: true },
    load: Function as PropType<MediaPickerLoad>,
  },
  emits: ['select', 'close', 'update:open'],
  template: '<div v-if="open" data-picker-stub />',
})

function summary(id: string, overrides: Partial<MediaAssetSummaryDto> = {}): MediaAssetSummaryDto {
  return {
    id,
    kind: 'IMAGE',
    originalFilename: `${id}.png`,
    mimeType: 'image/png',
    status: 'READY',
    previewUrl: null,
    width: 800,
    height: 600,
    ...overrides,
  }
}

function detailed(id: string): MediaAssetView {
  return {
    id,
    originalFilename: `${id}.png`,
    mimeType: 'image/png',
    byteSize: 500,
    width: 800,
    height: 600,
    sha256: 'b'.repeat(64),
    status: 'READY',
    version: 1,
    createdAt: '2026-07-18T00:00:00Z',
    updatedAt: '2026-07-18T00:00:01Z',
    translations: [
      { locale: 'zh-CN', altText: `中文 ${id}`, caption: '', credit: '', sourceUrl: null },
      { locale: 'en', altText: '', caption: `Caption ${id}`, credit: '', sourceUrl: null },
    ],
    variants: [],
  }
}

function mountControlled(initial: GalleryPayload = { type: 'GALLERY', mediaAssetIds: [firstId] }) {
  const resolveMedia = vi.fn(async (id: string) => detailed(id))
  const loadMedia = vi.fn<MediaPickerLoad>()
  const Host = defineComponent({
    components: { GalleryBlockEditor },
    setup() {
      return { payload: ref(initial), resolveMedia, loadMedia }
    },
    template: '<GalleryBlockEditor v-model="payload" locale="en" :resolve-media="resolveMedia" :load-media="loadMedia" />',
  })
  const wrapper = mount(Host, {
    attachTo: document.body,
    global: { stubs: { MediaPickerDialog: MediaPickerStub } },
  })
  mounted.push(wrapper)
  return { wrapper, resolveMedia, loadMedia }
}

function payloadOf(wrapper: ReturnType<typeof mount>): GalleryPayload {
  return (wrapper.vm as unknown as { payload: GalleryPayload }).payload
}

afterEach(() => {
  for (const wrapper of mounted.splice(0)) wrapper.unmount()
})

describe('GalleryBlockEditor', () => {
  it('reopens to append, deduplicates UUIDs case-insensitively, and resolves new ids', async () => {
    const { wrapper, resolveMedia, loadMedia } = mountControlled()
    await flushPromises()
    expect(resolveMedia).toHaveBeenCalledWith(firstId)

    const editor = wrapper.getComponent(GalleryBlockEditor)
    const picker = editor.getComponent(MediaPickerStub)
    expect(picker.props('accept')).toEqual(['IMAGE'])
    expect(picker.props('load')).toBe(loadMedia)

    await editor.get('[data-action="add-gallery-image"]').trigger('click')
    expect(picker.props('open')).toBe(true)
    picker.vm.$emit('select', summary(secondId))
    await flushPromises()
    expect(payloadOf(wrapper).mediaAssetIds).toEqual([firstId, secondId])
    expect(picker.props('open')).toBe(false)
    expect(resolveMedia).toHaveBeenCalledWith(secondId)

    await editor.get('[data-action="add-gallery-image"]').trigger('click')
    picker.vm.$emit('select', summary(secondId.toUpperCase()))
    await flushPromises()
    expect(payloadOf(wrapper).mediaAssetIds).toEqual([firstId, secondId])

    await editor.get('[data-action="add-gallery-image"]').trigger('click')
    picker.vm.$emit('select', summary(thirdId))
    await flushPromises()
    expect(payloadOf(wrapper)).toEqual({ type: 'GALLERY', mediaAssetIds: [firstId, secondId, thirdId] })
    expect(Object.keys(payloadOf(wrapper))).toEqual(['type', 'mediaAssetIds'])
    expect(JSON.stringify(payloadOf(wrapper))).not.toContain('.png')
  })

  it('rejects forged selections and reorders/removes ids immutably', async () => {
    const initial: GalleryPayload = { type: 'GALLERY', mediaAssetIds: [firstId, secondId, thirdId] }
    const { wrapper } = mountControlled(initial)
    const editor = wrapper.getComponent(GalleryBlockEditor)
    const picker = editor.getComponent(MediaPickerStub)

    picker.vm.$emit('select', summary('not-a-uuid'))
    picker.vm.$emit('select', summary(thirdId, { status: 'FAILED' }))
    picker.vm.$emit('select', summary(thirdId, { kind: 'PDF', mimeType: 'application/pdf' }))
    await flushPromises()
    expect(payloadOf(wrapper).mediaAssetIds).toEqual([firstId, secondId, thirdId])

    const moveUp = editor.get<HTMLButtonElement>(`[data-gallery-id="${thirdId}"] [data-direction="up"]`)
    moveUp.element.focus()
    await moveUp.trigger('click')
    expect(payloadOf(wrapper).mediaAssetIds).toEqual([firstId, thirdId, secondId])
    expect(payloadOf(wrapper).mediaAssetIds).not.toBe(initial.mediaAssetIds)
    expect(document.activeElement).toBe(
      editor.get(`[data-gallery-id="${thirdId}"] [data-direction="up"]`).element,
    )

    await editor.get(`[data-gallery-id="${firstId}"] [data-action="remove-gallery-image"]`).trigger('click')
    expect(payloadOf(wrapper).mediaAssetIds).toEqual([thirdId, secondId])
    expect(initial.mediaAssetIds).toEqual([firstId, secondId, thirdId])
    expect(document.activeElement).toBe(
      editor.get(`[data-gallery-id="${thirdId}"] [data-gallery-heading]`).element,
    )
    expect(editor.get('[role="status"]').text()).toContain('图片已移除')
  })

  it('shows each resolver state and both locale translation fields', async () => {
    const { wrapper } = mountControlled({ type: 'GALLERY', mediaAssetIds: [firstId, secondId] })
    await flushPromises()
    const editor = wrapper.getComponent(GalleryBlockEditor)
    expect(editor.findAll('[data-media-resolution="ready"]')).toHaveLength(2)
    const first = editor.get(`[data-gallery-id="${firstId}"]`)
    expect(first.get('[role="status"]').attributes('aria-busy')).toBe('false')
    expect(first.get('[data-media-locale="zh-CN"] [data-translation-field="altText"]').text()).toContain(`中文 ${firstId}`)
    expect(first.get('[data-media-locale="en"] [data-translation-field="caption"]').text()).toContain(`Caption ${firstId}`)
  })

  it('shows unreadable failures rather than claiming translation completion', async () => {
    const wrapper = mount(GalleryBlockEditor, {
      props: {
        modelValue: { type: 'GALLERY', mediaAssetIds: [firstId] },
        locale: 'zh-CN',
        resolveMedia: vi.fn().mockRejectedValue(new Error('offline')),
      },
      global: { stubs: { MediaPickerDialog: MediaPickerStub } },
    })
    await flushPromises()
    expect(wrapper.get('[data-media-resolution="error"]').text()).toContain('无法读取')
    expect(wrapper.find('[data-media-locale]').exists()).toBe(false)
  })

  it('associates gallery errors and exposes each action set as a named group', () => {
    const wrapper = mount(GalleryBlockEditor, {
      props: {
        modelValue: { type: 'GALLERY', mediaAssetIds: [firstId] },
        locale: 'zh-CN',
        fieldErrors: { mediaAssetIds: '画廊至少需要两张图片' },
        resolveMedia: vi.fn().mockResolvedValue(detailed(firstId)),
      },
      global: { stubs: { MediaPickerDialog: MediaPickerStub } },
    })

    const error = wrapper.get('[role="alert"]')
    expect(wrapper.get('[data-block-editor="GALLERY"]').attributes('aria-describedby')).toBe(
      error.attributes('id'),
    )
    expect(wrapper.get('[data-gallery-id] [role="group"]').attributes('aria-label')).toContain('图片 1')
  })
})
