import { mount } from '@vue/test-utils'
import { describe, expect, it } from 'vitest'

import type { CodePayload } from '@/types/blocks'
import CodeBlockEditor from './CodeBlockEditor.vue'

const payload: CodePayload = {
  type: 'CODE',
  code: 'const answer = 42',
  language: 'typescript',
  showLineNumbers: true,
  copy: {
    'zh-CN': { title: '示例', description: '中文说明' },
    en: { title: 'Example', description: 'English description' },
  },
}

describe('CodeBlockEditor', () => {
  it('emits complete immutable payloads for code settings', async () => {
    const wrapper = mount(CodeBlockEditor, {
      props: { modelValue: payload, locale: 'zh-CN' },
    })

    await wrapper.get('[data-field="code"]').setValue('console.log("next")')
    const codeUpdate = wrapper.emitted('update:modelValue')?.at(-1)?.[0] as CodePayload
    expect(codeUpdate).toEqual({ ...payload, code: 'console.log("next")' })
    expect(codeUpdate).not.toBe(payload)
    expect(codeUpdate.copy).not.toBe(payload.copy)
    expect(codeUpdate.copy['zh-CN']).not.toBe(payload.copy['zh-CN'])
    expect(codeUpdate.copy.en).not.toBe(payload.copy.en)

    await wrapper.get('[data-field="language"]').setValue('tsx')
    expect(wrapper.emitted('update:modelValue')?.at(-1)?.[0]).toEqual({
      ...payload,
      language: 'tsx',
    })

    await wrapper.get('[data-field="showLineNumbers"]').setValue(false)
    expect(wrapper.emitted('update:modelValue')?.at(-1)?.[0]).toEqual({
      ...payload,
      showLineNumbers: false,
    })
    expect(payload).toEqual({
      type: 'CODE',
      code: 'const answer = 42',
      language: 'typescript',
      showLineNumbers: true,
      copy: {
        'zh-CN': { title: '示例', description: '中文说明' },
        en: { title: 'Example', description: 'English description' },
      },
    })
  })

  it('updates only active-locale copy and renders code as escaped text', async () => {
    const unsafe: CodePayload = {
      ...payload,
      code: '<img src=x onerror=alert(1)>',
    }
    const wrapper = mount(CodeBlockEditor, {
      props: { modelValue: unsafe, locale: 'en' },
    })

    expect(wrapper.get('[data-code-preview]').text()).toContain('<img src=x onerror=alert(1)>')
    expect(wrapper.find('[data-code-preview] img').exists()).toBe(false)
    expect(wrapper.find('[contenteditable]').exists()).toBe(false)

    await wrapper.get('[data-field="copy.en.title"]').setValue('Updated title')
    const next = wrapper.emitted('update:modelValue')?.at(-1)?.[0] as CodePayload
    expect(next.copy).toEqual({
      'zh-CN': { title: '示例', description: '中文说明' },
      en: { title: 'Updated title', description: 'English description' },
    })
    expect(next.copy['zh-CN']).not.toBe(payload.copy['zh-CN'])
  })

  it('disables every control and exposes field errors', async () => {
    const wrapper = mount(CodeBlockEditor, {
      props: {
        modelValue: payload,
        locale: 'zh-CN',
        disabled: true,
        fieldErrors: { 'copy.zh-CN.description': '请填写说明' },
      },
    })

    expect(wrapper.get('[data-field="copy.zh-CN.description"]').attributes('aria-invalid')).toBe('true')
    expect(wrapper.get('[role="alert"]').text()).toBe('请填写说明')
    for (const control of wrapper.findAll('input, textarea')) {
      expect(control.attributes('disabled')).toBeDefined()
    }
    await wrapper.get('[data-field="code"]').trigger('input')
    expect(wrapper.emitted('update:modelValue')).toBeUndefined()
  })
})
