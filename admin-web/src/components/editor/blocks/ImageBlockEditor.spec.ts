import { flushPromises, mount } from '@vue/test-utils'
import { defineComponent, type PropType } from 'vue'
import { afterEach, describe, expect, it, vi } from 'vitest'

import type { MediaPickerLoad } from '@/components/media/MediaPickerDialog.vue'
import type { ImagePayload } from '@/types/blocks'
import type {
  MediaAssetSummaryDto,
  MediaAssetView,
  MediaKind,
} from '@/types/content'

import ImageBlockEditor from './ImageBlockEditor.vue'

const imageId = '30000000-0000-4000-8000-000000000001'
const nextId = '30000000-0000-4000-8000-000000000002'
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

function summary(
  id = nextId,
  overrides: Partial<MediaAssetSummaryDto> = {},
): MediaAssetSummaryDto {
  return {
    id,
    kind: 'IMAGE',
    originalFilename: 'cover.jpg',
    mimeType: 'image/jpeg',
    status: 'READY',
    previewUrl: null,
    width: 1280,
    height: 720,
    ...overrides,
  }
}

function detailed(id = imageId): MediaAssetView {
  return {
    id,
    originalFilename: 'cover.jpg',
    mimeType: 'image/jpeg',
    byteSize: 1024,
    width: 1280,
    height: 720,
    sha256: 'a'.repeat(64),
    status: 'READY',
    version: 1,
    createdAt: '2026-07-18T00:00:00Z',
    updatedAt: '2026-07-18T00:00:01Z',
    translations: [
      {
        locale: 'zh-CN',
        altText: '中文替代文本',
        caption: '',
        credit: '',
        sourceUrl: null,
      },
      {
        locale: 'en',
        altText: '',
        caption: 'English caption',
        credit: '',
        sourceUrl: null,
      },
    ],
    variants: [],
  }
}

function mountEditor(
  payload: ImagePayload = { type: 'IMAGE', mediaAssetId: imageId },
  options: Record<string, unknown> = {},
) {
  const wrapper = mount(ImageBlockEditor, {
    props: { modelValue: payload, locale: 'zh-CN', ...options },
    attachTo: document.body,
    global: { stubs: { MediaPickerDialog: MediaPickerStub } },
  })
  mounted.push(wrapper)
  return wrapper
}

afterEach(() => {
  for (const wrapper of mounted.splice(0)) wrapper.unmount()
})

describe('ImageBlockEditor', () => {
  it('resolves selected media and reports alt/caption completeness per locale', async () => {
    const resolveMedia = vi.fn().mockResolvedValue(detailed())
    const wrapper = mountEditor(undefined, { resolveMedia })

    expect(resolveMedia).toHaveBeenCalledWith(imageId)
    expect(wrapper.get('[data-media-resolution]').attributes('data-media-resolution')).toBe('loading')
    expect(wrapper.get('[data-media-resolution]').attributes('role')).toBe('status')
    expect(wrapper.get('[data-media-resolution]').attributes('aria-busy')).toBe('true')
    await flushPromises()

    expect(wrapper.get('[data-media-resolution]').attributes('data-media-resolution')).toBe('ready')
    expect(wrapper.get('[data-media-resolution]').attributes('aria-busy')).toBe('false')
    const chinese = wrapper.get('[data-media-locale="zh-CN"]')
    const english = wrapper.get('[data-media-locale="en"]')
    expect(chinese.attributes('data-active')).toBe('true')
    expect(chinese.get('[data-translation-field="altText"]').attributes('data-complete')).toBe('true')
    expect(chinese.get('[data-translation-field="altText"]').text()).toContain('中文替代文本')
    expect(chinese.get('[data-translation-field="caption"]').attributes('data-complete')).toBe('false')
    expect(english.get('[data-translation-field="altText"]').attributes('data-complete')).toBe('false')
    expect(english.get('[data-translation-field="caption"]').text()).toContain('English caption')
  })

  it('emits only the exact image payload after a strict second validation', async () => {
    const loadMedia = vi.fn<MediaPickerLoad>()
    const wrapper = mountEditor({ type: 'IMAGE', mediaAssetId: null }, { loadMedia })
    const picker = wrapper.getComponent(MediaPickerStub)
    expect(picker.props('accept')).toEqual(['IMAGE'])
    expect(picker.props('load')).toBe(loadMedia)

    picker.vm.$emit('select', summary())
    await flushPromises()
    const next = wrapper.emitted('update:modelValue')?.at(-1)?.[0] as ImagePayload
    expect(next).toEqual({ type: 'IMAGE', mediaAssetId: nextId })
    expect(Object.keys(next)).toEqual(['type', 'mediaAssetId'])
    expect(JSON.stringify(next)).not.toContain('cover.jpg')

    const count = wrapper.emitted('update:modelValue')?.length
    picker.vm.$emit('select', summary(nextId, { status: 'PROCESSING' }))
    picker.vm.$emit('select', summary(nextId, { kind: 'PDF', mimeType: 'application/pdf' }))
    picker.vm.$emit('select', summary('javascript:alert(1)'))
    await flushPromises()
    expect(wrapper.emitted('update:modelValue')).toHaveLength(count ?? 0)
  })

  it('shows unreadable resolver failures and can clear immutably', async () => {
    const wrapper = mountEditor(undefined, {
      resolveMedia: vi.fn().mockRejectedValue(new Error('offline')),
    })
    await flushPromises()
    expect(wrapper.get('[data-media-resolution="error"]').text()).toContain('无法读取')

    await wrapper.get('[data-action="clear-image"]').trigger('click')
    expect(wrapper.emitted('update:modelValue')?.at(-1)?.[0]).toEqual({
      type: 'IMAGE',
      mediaAssetId: null,
    })
    expect(document.activeElement).toBe(wrapper.get('[data-action="select-image"]').element)
    expect(wrapper.findAll('[role="status"]')[0]!.text()).toContain('图片已移除')
  })

  it('renders field errors and ignores all controls while disabled', async () => {
    const wrapper = mountEditor(undefined, {
      disabled: true,
      fieldErrors: { 'blocks[0].payload.mediaAssetId': '请选择图片' },
      resolveMedia: vi.fn().mockResolvedValue(detailed()),
    })
    await flushPromises()
    const error = wrapper.get('[role="alert"]')
    expect(error.text()).toBe('请选择图片')
    expect(wrapper.get('[role="group"]').attributes('aria-describedby')).toBe(error.attributes('id'))
    for (const button of wrapper.findAll('button')) {
      expect(button.attributes('disabled')).toBeDefined()
    }
    await wrapper.get('[data-action="clear-image"]').trigger('click')
    expect(wrapper.emitted('update:modelValue')).toBeUndefined()
  })
})
