import { mount } from '@vue/test-utils'
import { describe, expect, it } from 'vitest'

import type { MarkdownPayload } from '@/types/blocks'
import MarkdownBlockEditor from './MarkdownBlockEditor.vue'

const payload: MarkdownPayload = {
  type: 'MARKDOWN',
  markdown: {
    'zh-CN': '# 中文正文',
    en: '# English body',
  },
}

describe('MarkdownBlockEditor', () => {
  it('edits only the active locale and treats markdown as textarea text', async () => {
    const wrapper = mount(MarkdownBlockEditor, {
      props: { modelValue: payload, locale: 'zh-CN' },
    })

    const textarea = wrapper.get<HTMLTextAreaElement>('[data-field="markdown.zh-CN"]')
    expect(textarea.element.value).toBe('# 中文正文')
    expect(wrapper.attributes('data-block-editor')).toBe('MARKDOWN')
    expect(wrapper.find('[contenteditable]').exists()).toBe(false)

    const nextMarkdown = '**新的正文** <img src=x onerror=alert(1)>'
    await textarea.setValue(nextMarkdown)

    const emitted = wrapper.emitted('update:modelValue')
    expect(emitted).toHaveLength(1)
    const next = emitted![0]![0] as MarkdownPayload
    expect(next).toEqual({
      type: 'MARKDOWN',
      markdown: { 'zh-CN': nextMarkdown, en: '# English body' },
    })
    expect(next).not.toBe(payload)
    expect(next.markdown).not.toBe(payload.markdown)
    expect(payload.markdown['zh-CN']).toBe('# 中文正文')
    expect(wrapper.find('img').exists()).toBe(false)
  })

  it('follows locale changes without losing the other translation', async () => {
    const wrapper = mount(MarkdownBlockEditor, {
      props: { modelValue: payload, locale: 'zh-CN' },
    })

    await wrapper.setProps({ locale: 'en' })
    const textarea = wrapper.get<HTMLTextAreaElement>('[data-field="markdown.en"]')
    expect(textarea.element.value).toBe('# English body')
    await textarea.setValue('Updated English')

    expect(wrapper.emitted('update:modelValue')?.at(-1)?.[0]).toEqual({
      type: 'MARKDOWN',
      markdown: { 'zh-CN': '# 中文正文', en: 'Updated English' },
    })
  })

  it('renders server errors and does not emit while disabled', async () => {
    const wrapper = mount(MarkdownBlockEditor, {
      props: {
        modelValue: payload,
        locale: 'zh-CN',
        disabled: true,
        fieldErrors: { 'payload.markdown.zh-CN': '正文不能为空' },
      },
    })

    const textarea = wrapper.get<HTMLTextAreaElement>('textarea')
    expect(textarea.attributes('disabled')).toBeDefined()
    expect(textarea.attributes('aria-invalid')).toBe('true')
    expect(wrapper.get('[role="alert"]').text()).toBe('正文不能为空')
    await textarea.trigger('input')
    expect(wrapper.emitted('update:modelValue')).toBeUndefined()
  })
})
