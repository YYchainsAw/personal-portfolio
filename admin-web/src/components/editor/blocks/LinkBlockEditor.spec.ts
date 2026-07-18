import { mount } from '@vue/test-utils'
import { defineComponent, nextTick, ref } from 'vue'
import { describe, expect, it } from 'vitest'

import type { LinkPayload } from '@/types/blocks'
import LinkBlockEditor from './LinkBlockEditor.vue'

const payload: LinkPayload = {
  type: 'LINK',
  url: 'https://example.com/work',
  openNewTab: true,
  copy: {
    'zh-CN': { label: '查看项目', description: '中文说明' },
    en: { label: 'View project', description: 'English description' },
  },
}

function mountControlled() {
  const Host = defineComponent({
    components: { LinkBlockEditor },
    setup() {
      return {
        locale: ref<'zh-CN' | 'en'>('zh-CN'),
        payload: ref(payload),
      }
    },
    template: '<LinkBlockEditor v-model="payload" :locale="locale" />',
  })
  return mount(Host)
}

function currentPayload(wrapper: ReturnType<typeof mountControlled>): LinkPayload {
  return (wrapper.vm as unknown as { payload: LinkPayload }).payload
}

describe('LinkBlockEditor', () => {
  it.each([
    'javascript:alert(1)',
    'data:text/html,<script>alert(1)</script>',
    'http://example.com',
    'HTTPS://example.com',
    'https://user@example.com',
    'https://example.com/work#fragment',
    'https://example.com:8443/work',
    'https://example.com/%0aheader',
  ]) (
    'keeps %s in the controlled draft but rejects it with an inline HTTPS error',
    async (unsafeUrl) => {
      const wrapper = mountControlled()

      await wrapper.get('[data-field="url"]').setValue(unsafeUrl)

      expect(currentPayload(wrapper).url).toBe(unsafeUrl)
      const error = wrapper.get('[data-url-error]')
      expect(error.attributes('role')).toBe('alert')
      expect(error.text()).toContain('HTTPS')
      expect(wrapper.get('[data-field="url"]').attributes('aria-invalid')).toBe('true')
    },
  )

  it('accepts HTTPS, updates the target flag, and edits only current-locale copy', async () => {
    const wrapper = mountControlled()

    await wrapper.get('[data-field="url"]').setValue('https://example.com/next')
    expect(currentPayload(wrapper).url).toBe('https://example.com/next')
    expect(wrapper.find('[data-url-error]').exists()).toBe(false)

    await wrapper.get('[data-field="openNewTab"]').setValue(false)
    expect(currentPayload(wrapper).openNewTab).toBe(false)

    await wrapper.get('[data-field="copy.zh-CN.label"]').setValue('下一页')
    expect(currentPayload(wrapper).copy).toEqual({
      'zh-CN': { label: '下一页', description: '中文说明' },
      en: { label: 'View project', description: 'English description' },
    })

    ;(wrapper.vm as unknown as { locale: 'zh-CN' | 'en' }).locale = 'en'
    await nextTick()
    await wrapper.get('[data-field="copy.en.description"]').setValue('Updated English')
    const next = currentPayload(wrapper)
    expect(next.copy).toEqual({
      'zh-CN': { label: '下一页', description: '中文说明' },
      en: { label: 'View project', description: 'Updated English' },
    })
    expect(next).not.toBe(payload)
    expect(next.copy).not.toBe(payload.copy)
    expect(next.copy['zh-CN']).not.toBe(payload.copy['zh-CN'])
    expect(next.copy.en).not.toBe(payload.copy.en)
    expect(payload.url).toBe('https://example.com/work')
  })

  it('honors server field errors and disabled state', async () => {
    const wrapper = mount(LinkBlockEditor, {
      props: {
        modelValue: payload,
        locale: 'en',
        disabled: true,
        fieldErrors: { 'blocks.0.payload.copy.en.label': 'Label is required' },
      },
    })

    expect(wrapper.get('[data-field="copy.en.label"]').attributes('aria-invalid')).toBe('true')
    expect(wrapper.get('[role="alert"]').text()).toBe('Label is required')
    for (const control of wrapper.findAll('input, textarea')) {
      expect(control.attributes('disabled')).toBeDefined()
    }
    await wrapper.get('[data-field="url"]').trigger('input')
    expect(wrapper.emitted('update:modelValue')).toBeUndefined()
  })
})
