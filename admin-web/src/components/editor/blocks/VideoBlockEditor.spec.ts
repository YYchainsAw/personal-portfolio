import { flushPromises, mount } from '@vue/test-utils'
import { defineComponent, nextTick, ref, type PropType } from 'vue'
import { afterEach, describe, expect, it, vi } from 'vitest'

import type { MediaPickerLoad } from '@/components/media/MediaPickerDialog.vue'
import type { VideoPayload } from '@/types/blocks'
import type {
  MediaAssetSummaryDto,
  MediaAssetView,
  MediaKind,
} from '@/types/content'

import VideoBlockEditor from './VideoBlockEditor.vue'

const coverId = '50000000-0000-4000-8000-000000000001'
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

function summary(overrides: Partial<MediaAssetSummaryDto> = {}): MediaAssetSummaryDto {
  return {
    id: coverId,
    kind: 'IMAGE',
    originalFilename: 'cover.png',
    mimeType: 'image/png',
    status: 'READY',
    previewUrl: null,
    width: 1280,
    height: 720,
    ...overrides,
  }
}

function detailed(): MediaAssetView {
  return {
    id: coverId,
    originalFilename: 'cover.png',
    mimeType: 'image/png',
    byteSize: 1000,
    width: 1280,
    height: 720,
    sha256: 'c'.repeat(64),
    status: 'READY',
    version: 1,
    createdAt: '2026-07-18T00:00:00Z',
    updatedAt: '2026-07-18T00:00:01Z',
    translations: [
      { locale: 'zh-CN', altText: '视频封面', caption: '', credit: '', sourceUrl: null },
      { locale: 'en', altText: '', caption: 'Video cover', credit: '', sourceUrl: null },
    ],
    variants: [],
  }
}

const initial: VideoPayload = {
  type: 'VIDEO',
  provider: 'BILIBILI',
  url: 'https://www.bilibili.com/video/BV1',
  coverAssetId: null,
  copy: {
    'zh-CN': { title: '演示', description: '中文说明' },
    en: { title: 'Demo', description: 'English description' },
  },
}

function mountControlled(payload: VideoPayload = initial) {
  const resolveMedia = vi.fn().mockResolvedValue(detailed())
  const loadMedia = vi.fn<MediaPickerLoad>()
  const Host = defineComponent({
    components: { VideoBlockEditor },
    setup() {
      return {
        locale: ref<'zh-CN' | 'en'>('zh-CN'),
        payload: ref(payload),
        resolveMedia,
        loadMedia,
      }
    },
    template: '<VideoBlockEditor v-model="payload" :locale="locale" :resolve-media="resolveMedia" :load-media="loadMedia" />',
  })
  const wrapper = mount(Host, {
    attachTo: document.body,
    global: { stubs: { MediaPickerDialog: MediaPickerStub } },
  })
  mounted.push(wrapper)
  return {
    wrapper,
    resolveMedia,
    loadMedia,
  }
}

afterEach(() => {
  for (const wrapper of mounted.splice(0)) wrapper.unmount()
})

function payloadOf(wrapper: ReturnType<typeof mount>): VideoPayload {
  return (wrapper.vm as unknown as { payload: VideoPayload }).payload
}

describe('VideoBlockEditor', () => {
  it('edits provider, keeps unsafe URL drafts with an inline error, and accepts safe HTTPS', async () => {
    const { wrapper } = mountControlled()
    const editor = wrapper.getComponent(VideoBlockEditor)

    await editor.get('[data-field="provider"]').setValue('VIMEO')
    expect(payloadOf(wrapper).provider).toBe('VIMEO')

    await editor.get('[data-field="url"]').setValue('javascript:alert(1)')
    expect(payloadOf(wrapper).url).toBe('javascript:alert(1)')
    expect(editor.get('[data-url-error]').text()).toContain('HTTPS')

    await editor.get('[data-field="url"]').setValue('https://vimeo.com/12345')
    expect(payloadOf(wrapper).url).toBe('https://vimeo.com/12345')
    expect(editor.find('[data-url-error]').exists()).toBe(false)

    const provider = editor.get<HTMLSelectElement>('[data-field="provider"]')
    provider.element.value = 'ATTACKER'
    await provider.trigger('change')
    expect(payloadOf(wrapper).provider).toBe('VIMEO')
  })

  it('edits only current-locale copy across locale switches', async () => {
    const { wrapper } = mountControlled()
    const editor = wrapper.getComponent(VideoBlockEditor)
    await editor.get('[data-field="copy.zh-CN.title"]').setValue('新演示')
    expect(payloadOf(wrapper).copy).toEqual({
      'zh-CN': { title: '新演示', description: '中文说明' },
      en: { title: 'Demo', description: 'English description' },
    })

    ;(wrapper.vm as unknown as { locale: 'zh-CN' | 'en' }).locale = 'en'
    await nextTick()
    await editor.get('[data-field="copy.en.description"]').setValue('Updated English')
    expect(payloadOf(wrapper).copy).toEqual({
      'zh-CN': { title: '新演示', description: '中文说明' },
      en: { title: 'Demo', description: 'Updated English' },
    })
    expect(initial.copy['zh-CN'].title).toBe('演示')
  })

  it('strictly selects an image cover, resolves its translations, and leaks no media summary', async () => {
    const { wrapper, resolveMedia, loadMedia } = mountControlled()
    const editor = wrapper.getComponent(VideoBlockEditor)
    const picker = editor.getComponent(MediaPickerStub)
    expect(picker.props('accept')).toEqual(['IMAGE'])
    expect(picker.props('load')).toBe(loadMedia)

    picker.vm.$emit('select', summary())
    await flushPromises()
    expect(payloadOf(wrapper).coverAssetId).toBe(coverId)
    expect(Object.keys(payloadOf(wrapper))).toEqual([
      'type',
      'provider',
      'url',
      'coverAssetId',
      'copy',
    ])
    expect(JSON.stringify(payloadOf(wrapper))).not.toContain('cover.png')
    expect(resolveMedia).toHaveBeenCalledWith(coverId)
    const media = editor.get('[data-media-resolution="ready"]')
    expect(media.attributes('role')).toBe('status')
    expect(media.attributes('aria-busy')).toBe('false')
    expect(media.get('[data-media-locale="zh-CN"]').text()).toContain('视频封面')

    const before = payloadOf(wrapper)
    picker.vm.$emit('select', summary({ status: 'FAILED' }))
    picker.vm.$emit('select', summary({ kind: 'PDF', mimeType: 'application/pdf' }))
    await flushPromises()
    expect(payloadOf(wrapper)).toBe(before)

    await editor.get('[data-action="clear-video-cover"]').trigger('click')
    expect(payloadOf(wrapper).coverAssetId).toBeNull()
    expect(document.activeElement).toBe(editor.get('[data-action="select-video-cover"]').element)
    expect(editor.findAll('[role="status"]')[0]!.text()).toContain('视频封面已移除')
  })

  it('honors field errors and disabled state', async () => {
    const wrapper = mount(VideoBlockEditor, {
      props: {
        modelValue: initial,
        locale: 'en',
        disabled: true,
        fieldErrors: { 'blocks[2].payload.copy.en.title': 'Title is required' },
      },
      global: { stubs: { MediaPickerDialog: MediaPickerStub } },
    })
    expect(wrapper.get('[data-field="copy.en.title"]').attributes('aria-invalid')).toBe('true')
    expect(wrapper.get('[role="alert"]').text()).toBe('Title is required')
    for (const control of wrapper.findAll('button, input, textarea, select')) {
      expect(control.attributes('disabled')).toBeDefined()
    }
    await wrapper.get('[data-field="url"]').trigger('input')
    expect(wrapper.emitted('update:modelValue')).toBeUndefined()
  })
})
