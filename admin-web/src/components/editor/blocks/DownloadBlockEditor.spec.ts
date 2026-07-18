import { flushPromises, mount } from '@vue/test-utils'
import { defineComponent, nextTick, ref, type PropType } from 'vue'
import { afterEach, describe, expect, it, vi } from 'vitest'

import type { MediaPickerLoad } from '@/components/media/MediaPickerDialog.vue'
import type { DownloadPayload } from '@/types/blocks'
import type {
  MediaAssetSummaryDto,
  MediaAssetView,
  MediaKind,
} from '@/types/content'

import DownloadBlockEditor from './DownloadBlockEditor.vue'

const pdfId = '60000000-0000-4000-8000-000000000001'
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
    id: pdfId,
    kind: 'PDF',
    originalFilename: 'resume.pdf',
    mimeType: 'application/pdf',
    status: 'READY',
    previewUrl: null,
    width: null,
    height: null,
    ...overrides,
  }
}

function detailed(): MediaAssetView {
  return {
    id: pdfId,
    originalFilename: 'resume.pdf',
    mimeType: 'application/pdf',
    byteSize: 1000,
    width: null,
    height: null,
    sha256: 'd'.repeat(64),
    status: 'READY',
    version: 1,
    createdAt: '2026-07-18T00:00:00Z',
    updatedAt: '2026-07-18T00:00:01Z',
    translations: [
      { locale: 'zh-CN', altText: '中文简历', caption: '', credit: '', sourceUrl: null },
      { locale: 'en', altText: '', caption: 'English resume', credit: '', sourceUrl: null },
    ],
    variants: [],
  }
}

const initial: DownloadPayload = {
  type: 'DOWNLOAD',
  mediaAssetId: null,
  externalUrl: 'https://example.com/resume.pdf',
  copy: {
    'zh-CN': { label: '下载简历', description: '中文说明' },
    en: { label: 'Download resume', description: 'English description' },
  },
}

function mountControlled(payload: DownloadPayload = initial) {
  const resolveMedia = vi.fn().mockResolvedValue(detailed())
  const loadMedia = vi.fn<MediaPickerLoad>()
  const Host = defineComponent({
    components: { DownloadBlockEditor },
    setup() {
      return {
        locale: ref<'zh-CN' | 'en'>('zh-CN'),
        payload: ref(payload),
        resolveMedia,
        loadMedia,
      }
    },
    template: '<DownloadBlockEditor v-model="payload" :locale="locale" :resolve-media="resolveMedia" :load-media="loadMedia" />',
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

function payloadOf(wrapper: ReturnType<typeof mount>): DownloadPayload {
  return (wrapper.vm as unknown as { payload: DownloadPayload }).payload
}

describe('DownloadBlockEditor', () => {
  it('keeps native radio groups and controlled payloads isolated between instances', async () => {
    const Host = defineComponent({
      components: { DownloadBlockEditor },
      setup() {
        return {
          first: ref<DownloadPayload>({ ...initial, copy: initial.copy }),
          second: ref<DownloadPayload>({
            ...initial,
            externalUrl: 'https://example.com/second.pdf',
            copy: initial.copy,
          }),
        }
      },
      template: `
        <DownloadBlockEditor v-model="first" locale="zh-CN" />
        <DownloadBlockEditor v-model="second" locale="zh-CN" />
      `,
    })
    const wrapper = mount(Host, {
      global: { stubs: { MediaPickerDialog: MediaPickerStub } },
    })
    const editors = wrapper.findAllComponents(DownloadBlockEditor)
    expect(editors).toHaveLength(2)
    const firstExternal = editors[0]!.get<HTMLInputElement>('[data-mode="external"]')
    const secondExternal = editors[1]!.get<HTMLInputElement>('[data-mode="external"]')
    expect(firstExternal.element.checked).toBe(true)
    expect(secondExternal.element.checked).toBe(true)
    expect(firstExternal.attributes('name')).not.toBe(secondExternal.attributes('name'))

    await editors[0]!.get('[data-mode="media"]').setValue(true)

    expect(editors[0]!.get<HTMLInputElement>('[data-mode="media"]').element.checked).toBe(true)
    expect(editors[1]!.get<HTMLInputElement>('[data-mode="external"]').element.checked).toBe(true)
    const values = wrapper.vm as unknown as {
      first: DownloadPayload
      second: DownloadPayload
    }
    expect(values.first).toMatchObject({ mediaAssetId: null, externalUrl: null })
    expect(values.second).toMatchObject({
      mediaAssetId: null,
      externalUrl: 'https://example.com/second.pdf',
    })
  })

  it('switches media/external modes atomically and retains unsafe URL drafts with an error', async () => {
    const { wrapper } = mountControlled()
    const editor = wrapper.getComponent(DownloadBlockEditor)

    expect(editor.get<HTMLInputElement>('[data-mode="external"]').element.checked).toBe(true)
    await editor.get('[data-mode="media"]').setValue(true)
    expect(payloadOf(wrapper).mediaAssetId).toBeNull()
    expect(payloadOf(wrapper).externalUrl).toBeNull()

    await editor.get('[data-mode="external"]').setValue(true)
    expect(payloadOf(wrapper).mediaAssetId).toBeNull()
    expect(payloadOf(wrapper).externalUrl).toBe('')

    await editor.get('[data-field="externalUrl"]').setValue('data:text/plain,secret')
    expect(payloadOf(wrapper)).toMatchObject({
      mediaAssetId: null,
      externalUrl: 'data:text/plain,secret',
    })
    expect(editor.get('[data-url-error]').text()).toContain('HTTPS')

    await editor.get('[data-field="externalUrl"]').setValue('https://cdn.example.com/resume.pdf')
    expect(payloadOf(wrapper).externalUrl).toBe('https://cdn.example.com/resume.pdf')
    expect(editor.find('[data-url-error]').exists()).toBe(false)
  })

  it('accepts only a READY PDF, clears the external URL, resolves copy, and leaks no summary', async () => {
    const { wrapper, resolveMedia, loadMedia } = mountControlled()
    const editor = wrapper.getComponent(DownloadBlockEditor)
    const picker = editor.getComponent(MediaPickerStub)
    expect(picker.props('accept')).toEqual(['PDF'])
    expect(picker.props('load')).toBe(loadMedia)

    picker.vm.$emit('select', summary())
    await flushPromises()
    expect(payloadOf(wrapper).mediaAssetId).toBe(pdfId)
    expect(payloadOf(wrapper).externalUrl).toBeNull()
    expect(Object.keys(payloadOf(wrapper))).toEqual([
      'type',
      'mediaAssetId',
      'externalUrl',
      'copy',
    ])
    expect(JSON.stringify(payloadOf(wrapper))).not.toContain('resume.pdf')
    expect(resolveMedia).toHaveBeenCalledWith(pdfId)
    const media = editor.get('[data-media-resolution="ready"]')
    expect(media.attributes('role')).toBe('status')
    expect(media.attributes('aria-busy')).toBe('false')
    expect(media.get('[data-media-locale="en"]').text()).toContain('English resume')

    const before = payloadOf(wrapper)
    picker.vm.$emit('select', summary({ kind: 'FILE' }))
    picker.vm.$emit('select', summary({ kind: 'IMAGE', mimeType: 'image/png' }))
    picker.vm.$emit('select', summary({ status: 'PROCESSING' }))
    await flushPromises()
    expect(payloadOf(wrapper)).toBe(before)

    await editor.get('[data-action="clear-download-pdf"]').trigger('click')
    expect(payloadOf(wrapper).mediaAssetId).toBeNull()
    expect(document.activeElement).toBe(editor.get('[data-action="select-download-pdf"]').element)
    expect(editor.findAll('[role="status"]')[0]!.text()).toContain('PDF 已移除')
  })

  it('edits only current-locale action copy and preserves the other language', async () => {
    const { wrapper } = mountControlled()
    const editor = wrapper.getComponent(DownloadBlockEditor)
    await editor.get('[data-field="copy.zh-CN.label"]').setValue('获取简历')
    ;(wrapper.vm as unknown as { locale: 'zh-CN' | 'en' }).locale = 'en'
    await nextTick()
    await editor.get('[data-field="copy.en.description"]').setValue('Updated English')

    expect(payloadOf(wrapper).copy).toEqual({
      'zh-CN': { label: '获取简历', description: '中文说明' },
      en: { label: 'Download resume', description: 'Updated English' },
    })
    expect(initial.copy.en.description).toBe('English description')
  })

  it('shows resolver failure as unreadable and honors disabled/errors', async () => {
    const mediaPayload: DownloadPayload = {
      ...initial,
      mediaAssetId: pdfId,
      externalUrl: null,
    }
    const wrapper = mount(DownloadBlockEditor, {
      props: {
        modelValue: mediaPayload,
        locale: 'en',
        disabled: true,
        fieldErrors: { 'blocks[0].payload.mediaAssetId': 'PDF is required' },
        resolveMedia: vi.fn().mockRejectedValue(new Error('offline')),
      },
      global: { stubs: { MediaPickerDialog: MediaPickerStub } },
    })
    await flushPromises()
    expect(wrapper.get('[data-media-resolution="error"]').text()).toContain('无法读取')
    expect(wrapper.get('[role="alert"]').text()).toBe('PDF is required')
    for (const control of wrapper.findAll('button, input, textarea')) {
      expect(control.attributes('disabled')).toBeDefined()
    }
    await wrapper.get('[data-mode="external"]').trigger('change')
    expect(wrapper.emitted('update:modelValue')).toBeUndefined()
  })

  it('associates target errors with both source radios', () => {
    const wrapper = mount(DownloadBlockEditor, {
      props: {
        modelValue: initial,
        locale: 'zh-CN',
        fieldErrors: { target: '请且只选择一种下载来源' },
      },
      global: { stubs: { MediaPickerDialog: MediaPickerStub } },
    })

    const error = wrapper.get('[role="alert"]')
    for (const radio of wrapper.findAll('input[type="radio"]')) {
      expect(radio.attributes('aria-invalid')).toBe('true')
      expect(radio.attributes('aria-describedby')).toBe(error.attributes('id'))
    }
  })
})
