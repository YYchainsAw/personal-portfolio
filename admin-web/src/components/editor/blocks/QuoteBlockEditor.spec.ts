import { mount } from '@vue/test-utils'
import { describe, expect, it } from 'vitest'

import type { QuotePayload } from '@/types/blocks'
import QuoteBlockEditor from './QuoteBlockEditor.vue'

const payload: QuotePayload = {
  type: 'QUOTE',
  copy: {
    'zh-CN': { quote: '中文引文', source: '中文来源' },
    en: { quote: 'English quote', source: 'English source' },
  },
}

describe('QuoteBlockEditor', () => {
  it('edits quote and source only for the current locale', async () => {
    const wrapper = mount(QuoteBlockEditor, {
      props: { modelValue: payload, locale: 'en' },
    })

    await wrapper.get('[data-field="copy.en.quote"]').setValue('Updated quote')
    let next = wrapper.emitted('update:modelValue')?.at(-1)?.[0] as QuotePayload
    expect(next).toEqual({
      type: 'QUOTE',
      copy: {
        'zh-CN': { quote: '中文引文', source: '中文来源' },
        en: { quote: 'Updated quote', source: 'English source' },
      },
    })
    expect(next).not.toBe(payload)
    expect(next.copy).not.toBe(payload.copy)
    expect(next.copy['zh-CN']).not.toBe(payload.copy['zh-CN'])
    expect(next.copy.en).not.toBe(payload.copy.en)

    await wrapper.setProps({ locale: 'zh-CN' })
    await wrapper.get('[data-field="copy.zh-CN.source"]').setValue('新来源')
    next = wrapper.emitted('update:modelValue')?.at(-1)?.[0] as QuotePayload
    expect(next.copy).toEqual({
      'zh-CN': { quote: '中文引文', source: '新来源' },
      en: { quote: 'English quote', source: 'English source' },
    })
    expect(payload.copy.en.source).toBe('English source')
  })

  it('honors disabled and fieldErrors', async () => {
    const wrapper = mount(QuoteBlockEditor, {
      props: {
        modelValue: payload,
        locale: 'zh-CN',
        disabled: true,
        fieldErrors: { 'payload.copy.zh-CN.quote': '引文不能为空' },
      },
    })

    const quote = wrapper.get('[data-field="copy.zh-CN.quote"]')
    expect(quote.attributes('disabled')).toBeDefined()
    expect(quote.attributes('aria-invalid')).toBe('true')
    expect(wrapper.get('[role="alert"]').text()).toBe('引文不能为空')
    await quote.trigger('input')
    expect(wrapper.emitted('update:modelValue')).toBeUndefined()
  })
})
