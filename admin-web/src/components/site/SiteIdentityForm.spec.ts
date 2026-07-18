import { mount } from '@vue/test-utils'
import { describe, expect, it } from 'vitest'

import SiteIdentityForm from './SiteIdentityForm.vue'

describe('SiteIdentityForm', () => {
  it('exposes controlled, labelled monogram and email fields', async () => {
    const wrapper = mount(SiteIdentityForm, {
      props: {
        monogram: 'YJX',
        email: 'hello@example.com',
      },
    })

    const monogram = wrapper.get<HTMLInputElement>('input[name="monogram"]')
    const email = wrapper.get<HTMLInputElement>('input[name="email"]')
    const monogramLabel = wrapper.get(`label[for="${monogram.attributes('id')}"]`)
    const emailLabel = wrapper.get(`label[for="${email.attributes('id')}"]`)

    expect(wrapper.get('fieldset').attributes('aria-labelledby')).toBe(
      wrapper.get('legend').attributes('id'),
    )
    expect(monogramLabel.text()).toContain('字母标识')
    expect(emailLabel.text()).toContain('联系邮箱')
    expect(monogram.attributes('required')).toBeDefined()
    expect(email.attributes('type')).toBe('email')
    expect(email.attributes('autocomplete')).toBe('email')
    expect(email.attributes('required')).toBeDefined()

    await monogram.setValue('YY')
    await email.setValue('new@example.com')

    expect(wrapper.emitted('update:monogram')).toEqual([['YY']])
    expect(wrapper.emitted('update:email')).toEqual([['new@example.com']])
    expect(wrapper.props('monogram')).toBe('YJX')
    expect(wrapper.props('email')).toBe('hello@example.com')

    await wrapper.setProps({ monogram: 'YY', email: 'new@example.com' })
    expect(monogram.element.value).toBe('YY')
    expect(email.element.value).toBe('new@example.com')
  })

  it('associates field errors and disables both controls', () => {
    const wrapper = mount(SiteIdentityForm, {
      props: {
        monogram: '',
        email: 'not-an-email',
        disabled: true,
        fieldErrors: {
          monogram: '请输入字母标识',
          email: '请输入有效邮箱',
        },
      },
    })

    for (const name of ['monogram', 'email'] as const) {
      const control = wrapper.get<HTMLInputElement>(`input[name="${name}"]`)
      const descriptionId = control.attributes('aria-describedby')
      expect(control.attributes('aria-invalid')).toBe('true')
      expect(control.attributes('disabled')).toBeDefined()
      expect(descriptionId).toBeTruthy()
      expect(wrapper.get(`#${descriptionId}`).attributes('role')).toBe('alert')
      expect(wrapper.get(`#${descriptionId}`).text()).toBe(
        name === 'monogram' ? '请输入字母标识' : '请输入有效邮箱',
      )
    }
  })
})
