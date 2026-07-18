import { mount } from '@vue/test-utils'
import type { DefineComponent } from 'vue'
import { describe, expect, it } from 'vitest'

import type { Locale, SeoCopy } from '@/types/content'

import SiteTranslationForm, {
  type SiteTranslationField,
  type StringFieldKey,
} from './SiteTranslationForm.vue'

const fields = [
  { key: 'title', label: '标题', required: true },
  { key: 'description', label: '描述', multiline: true, rows: 5 },
] as const satisfies readonly SiteTranslationField<StringFieldKey<SeoCopy>>[]

type SeoTranslationFormProps = {
  modelValue: SeoCopy
  locale: Locale
  title: string
  fields: readonly SiteTranslationField<StringFieldKey<SeoCopy>>[]
  disabled?: boolean
  fieldErrors?: Readonly<Record<string, string>>
}

// Vue Test Utils cannot infer a generic SFC from direct mount props; production templates do.
const SeoTranslationForm = SiteTranslationForm as unknown as DefineComponent<SeoTranslationFormProps>

describe('SiteTranslationForm', () => {
  it('edits every configured field without mutating the controlled copy', async () => {
    const copy = Object.freeze({ title: 'Old title', description: 'Old description' })
    const wrapper = mount(SeoTranslationForm, {
      props: {
        modelValue: copy,
        locale: 'en',
        title: 'SEO',
        fields,
      },
    })

    const fieldset = wrapper.get('fieldset')
    const title = wrapper.get<HTMLInputElement>('[data-field="title"]')
    const description = wrapper.get<HTMLTextAreaElement>('[data-field="description"]')

    expect(fieldset.attributes('lang')).toBeUndefined()
    expect(fieldset.attributes('aria-labelledby')).toBe(wrapper.get('legend').attributes('id'))
    expect(wrapper.get('legend').text()).toContain('SEO')
    expect(wrapper.get('legend').text()).toContain('English')
    expect(wrapper.get(`label[for="${title.attributes('id')}"]`).text()).toContain('标题')
    expect(wrapper.get(`label[for="${description.attributes('id')}"]`).text()).toContain('描述')
    expect(title.attributes('required')).toBeDefined()
    expect(title.attributes('lang')).toBe('en')
    expect(description.attributes('rows')).toBe('5')
    expect(description.attributes('lang')).toBe('en')

    await title.setValue('New title')

    expect(copy).toEqual({ title: 'Old title', description: 'Old description' })
    expect(wrapper.emitted('update:modelValue')).toEqual([
      [{ title: 'New title', description: 'Old description' }],
    ])
    expect(wrapper.props('modelValue')).toBe(copy)

    await wrapper.setProps({
      modelValue: { title: 'New title', description: 'Old description' },
    })
    await description.setValue('New description')
    expect(wrapper.emitted('update:modelValue')?.at(-1)).toEqual([
      { title: 'New title', description: 'New description' },
    ])
  })

  it('connects locale-specific errors and disabled state to each generated control', () => {
    const wrapper = mount(SeoTranslationForm, {
      props: {
        modelValue: { title: '', description: '' },
        locale: 'zh-CN',
        title: 'SEO',
        fields,
        disabled: true,
        fieldErrors: { description: '请填写描述' },
      },
    })

    const title = wrapper.get<HTMLInputElement>('[data-field="title"]')
    const description = wrapper.get<HTMLTextAreaElement>('[data-field="description"]')
    const descriptionId = description.attributes('aria-describedby')

    expect(wrapper.get('fieldset').attributes('lang')).toBeUndefined()
    expect(wrapper.get('legend').text()).toContain('中文')
    expect(title.attributes('disabled')).toBeDefined()
    expect(title.attributes('lang')).toBe('zh-CN')
    expect(title.attributes('aria-invalid')).toBeUndefined()
    expect(description.attributes('disabled')).toBeDefined()
    expect(description.attributes('lang')).toBe('zh-CN')
    expect(description.attributes('aria-invalid')).toBe('true')
    expect(descriptionId).toBeTruthy()
    expect(wrapper.get(`#${descriptionId}`).text()).toBe('请填写描述')
  })
})
