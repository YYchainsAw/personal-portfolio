import { mount } from '@vue/test-utils'
import { defineComponent, nextTick, toRaw, type PropType } from 'vue'
import { describe, expect, it, vi } from 'vitest'

import type { MediaPickerLoad } from '@/components/media/MediaPickerDialog.vue'
import {
  BLOCK_TYPES,
  createBlock,
  type ContentBlockDto,
  type ContentBlockPayload,
} from '@/types/blocks'
import type { Locale, MediaAssetView } from '@/types/content'

import BlockCard from './BlockCard.vue'

function editorStub(name: string, type: ContentBlockPayload['type']) {
  return defineComponent({
    name,
    props: {
      modelValue: { type: Object as PropType<ContentBlockPayload>, required: true },
      locale: { type: String as PropType<Locale>, required: true },
      disabled: Boolean,
      fieldErrors: Object as PropType<Readonly<Record<string, string>>>,
      loadMedia: Function as PropType<MediaPickerLoad>,
      resolveMedia: Function as PropType<(id: string) => Promise<MediaAssetView>>,
    },
    emits: { 'update:modelValue': (_payload: ContentBlockPayload) => true },
    template: `<div data-editor-stub="${type}"></div>`,
  })
}

const editorStubs = {
  MarkdownBlockEditor: editorStub('MarkdownBlockEditor', 'MARKDOWN'),
  ImageBlockEditor: editorStub('ImageBlockEditor', 'IMAGE'),
  GalleryBlockEditor: editorStub('GalleryBlockEditor', 'GALLERY'),
  VideoBlockEditor: editorStub('VideoBlockEditor', 'VIDEO'),
  CodeBlockEditor: editorStub('CodeBlockEditor', 'CODE'),
  QuoteBlockEditor: editorStub('QuoteBlockEditor', 'QUOTE'),
  MetricsBlockEditor: editorStub('MetricsBlockEditor', 'METRICS'),
  DownloadBlockEditor: editorStub('DownloadBlockEditor', 'DOWNLOAD'),
  LinkBlockEditor: editorStub('LinkBlockEditor', 'LINK'),
}

const componentNameByType = {
  MARKDOWN: 'MarkdownBlockEditor',
  IMAGE: 'ImageBlockEditor',
  GALLERY: 'GalleryBlockEditor',
  VIDEO: 'VideoBlockEditor',
  CODE: 'CodeBlockEditor',
  QUOTE: 'QuoteBlockEditor',
  METRICS: 'MetricsBlockEditor',
  DOWNLOAD: 'DownloadBlockEditor',
  LINK: 'LinkBlockEditor',
} as const

function mountCard(
  block: ContentBlockDto,
  options: {
    disabled?: boolean
    fieldErrors?: Readonly<Record<string, string>>
    loadMedia?: MediaPickerLoad
    resolveMedia?: (id: string) => Promise<MediaAssetView>
    labelledby?: string
  } = {},
) {
  return mount(BlockCard, {
    props: { block, locale: 'en', ...options },
    global: { stubs: editorStubs },
  })
}

describe('BlockCard', () => {
  it.each(BLOCK_TYPES)('dispatches %s to exactly its exhaustive typed child', (type) => {
    const block = createBlock(type)
    const loadMedia = vi.fn<MediaPickerLoad>()
    const resolveMedia = vi.fn<(id: string) => Promise<MediaAssetView>>()
    const wrapper = mountCard(block, { loadMedia, resolveMedia, labelledby: 'block-position-title' })

    expect(wrapper.findAll('[data-editor-stub]')).toHaveLength(1)
    expect(wrapper.get('[data-editor-stub]').attributes('data-editor-stub')).toBe(type)
    expect(wrapper.attributes('data-block-type')).toBe(type)
    expect(wrapper.attributes('aria-labelledby')).toBe('block-position-title')

    const child = wrapper.findComponent(editorStubs[componentNameByType[type]])
    expect(toRaw(child.props('modelValue') as ContentBlockPayload)).toBe(block.payload)
    expect(child.props('locale')).toBe('en')
    if (['IMAGE', 'GALLERY', 'VIDEO', 'DOWNLOAD'].includes(type)) {
      expect(child.props('loadMedia')).toBe(loadMedia)
      expect(child.props('resolveMedia')).toBe(resolveMedia)
    }
  })

  it.each(BLOCK_TYPES)('emits a complete stable %s block from a child payload update', async (type) => {
    const block = { ...createBlock(type), id: 'stable-id', sortOrder: 17 }
    const wrapper = mountCard(block)
    const child = wrapper.findComponent(editorStubs[componentNameByType[type]])
    const replacement = createBlock(type).payload

    child.vm.$emit('update:modelValue', replacement)
    await nextTick()

    const emitted = wrapper.emitted('update:block')
    expect(emitted).toHaveLength(1)
    expect(emitted![0]![0]).toEqual({ ...block, payload: replacement })
    expect((emitted![0]![0] as ContentBlockDto).id).toBe('stable-id')
    expect((emitted![0]![0] as ContentBlockDto).sortOrder).toBe(17)
  })

  it('rejects a forged payload discriminator from the wrong child', async () => {
    const wrapper = mountCard(createBlock('MARKDOWN'))
    wrapper.findComponent(editorStubs.MarkdownBlockEditor).vm.$emit(
      'update:modelValue',
      createBlock('LINK').payload,
    )
    await nextTick()

    expect(wrapper.emitted('update:block')).toBeUndefined()
  })

  it('edits exactly the shared visible and layout fields', async () => {
    const block = { ...createBlock('QUOTE'), id: 'quote-id', sortOrder: 6 }
    const wrapper = mountCard(block)

    await wrapper.get('[data-field="visible"]').setValue(false)
    await wrapper.get('[data-field="width"]').setValue('FULL')
    await wrapper.get('[data-field="alignment"]').setValue('CENTER')
    await wrapper.get('[data-field="emphasis"]').setValue('STRONG')
    await wrapper.get('[data-field="columns"]').setValue('4')

    const values = wrapper.emitted('update:block')!.map((event) => event[0] as ContentBlockDto)
    expect(values).toHaveLength(5)
    expect(values[0]).toEqual({ ...block, visible: false })
    expect(values[1]).toEqual({ ...block, width: 'FULL' })
    expect(values[2]).toEqual({ ...block, alignment: 'CENTER' })
    expect(values[3]).toEqual({ ...block, emphasis: 'STRONG' })
    expect(values[4]).toEqual({ ...block, columns: 4 })
    for (const value of values) {
      expect(value.id).toBe('quote-id')
      expect(value.sortOrder).toBe(6)
      expect(toRaw(value.payload)).toBe(block.payload)
    }
  })

  it('associates shared errors as escaped text and forwards payload errors', () => {
    const unsafe = '<img src=x onerror=alert(1)>'
    const errors = {
      'blocks[2].columns': unsafe,
      'blocks[2].payload.copy.en.quote': 'Quote is required',
    }
    const wrapper = mountCard(createBlock('QUOTE'), { fieldErrors: errors })

    const columns = wrapper.get('[data-field="columns"]')
    expect(columns.attributes('aria-invalid')).toBe('true')
    expect(columns.attributes('aria-describedby')).toBeTruthy()
    expect(wrapper.get('[data-shared-error="columns"]').text()).toBe(unsafe)
    expect(wrapper.find('img').exists()).toBe(false)
    expect(toRaw(
      wrapper.findComponent(editorStubs.QuoteBlockEditor).props('fieldErrors') as object,
    )).toBe(errors)
  })

  it('disables shared and child controls and ignores forged child updates', async () => {
    const block = createBlock('LINK')
    const wrapper = mountCard(block, { disabled: true })

    for (const control of wrapper.findAll('input, select')) {
      expect(control.attributes('disabled')).toBeDefined()
    }
    expect(wrapper.findComponent(editorStubs.LinkBlockEditor).props('disabled')).toBe(true)

    await wrapper.get('[data-field="visible"]').trigger('change')
    wrapper.findComponent(editorStubs.LinkBlockEditor).vm.$emit(
      'update:modelValue',
      { ...block.payload, url: 'https://example.test/changed' },
    )
    await nextTick()
    expect(wrapper.emitted('update:block')).toBeUndefined()
  })
})
