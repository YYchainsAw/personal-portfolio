import { flushPromises, mount } from '@vue/test-utils'
import { expect, it, vi } from 'vitest'
import ContactForm from '@/components/contact/ContactForm.vue'
import { contactApi } from '@/services/contactApi'
import { ContactSubmissionError } from '@/services/contactApi'
import { site } from '../fixtures/publicSnapshots'

it.each(['zh-CN', 'en'] as const)('validates and submits the exact localized form in %s', async (locale) => {
  const submit = vi.spyOn(contactApi, 'submit').mockResolvedValue({ accepted: true })
  const wrapper = mount(ContactForm, { props: { locale, contact: site(locale).contact, deletionEmail: 'delete@yychainsaw.xyz' } })
  await wrapper.get('form').trigger('submit')
  expect(submit).not.toHaveBeenCalled(); expect(wrapper.get('[role="alert"]').exists()).toBe(true)
  await wrapper.get('input[name="name"]').setValue(' Tester ')
  await wrapper.get('input[name="email"]').setValue(' test@example.test ')
  await wrapper.get('input[name="subject"]').setValue(' Hello ')
  await wrapper.get('textarea[name="message"]').setValue(' Synthetic message ')
  await wrapper.get('input[name="privacyAccepted"]').setValue(true)
  await wrapper.get('form').trigger('submit'); await flushPromises()
  expect(submit).toHaveBeenCalledWith({ name: 'Tester', email: 'test@example.test', subject: 'Hello', message: 'Synthetic message', website: '', privacyAccepted: true }, expect.any(AbortSignal))
  expect(wrapper.text()).toContain(locale === 'zh-CN' ? '留言已被安全接收' : 'message was safely accepted')
  expect(wrapper.text()).toContain('delete@yychainsaw.xyz')
  expect(wrapper.get('input[name="name"]').element).toHaveProperty('value', '')
})

it('keeps the honeypot outside the keyboard and accessibility flow', () => {
  const wrapper = mount(ContactForm, { props: { locale: 'en', contact: site('en').contact, deletionEmail: 'delete@example.test' } })
  expect(wrapper.get('.honeypot').attributes('aria-hidden')).toBe('true')
  expect(wrapper.get('input[name="website"]').attributes()).toMatchObject({ autocomplete: 'off', tabindex: '-1', maxlength: '200' })
  for (const field of ['name', 'email', 'subject', 'message', 'privacyAccepted']) {
    const control = wrapper.get(`[name="${field}"]`)
    expect(control.attributes('id')).toBeTruthy()
    expect(control.attributes('aria-describedby')).toBeTruthy()
    expect(wrapper.find(`#${control.attributes('aria-describedby')}`).exists()).toBe(true)
  }
})

async function fill(wrapper: ReturnType<typeof mount>) {
  await wrapper.get('input[name="name"]').setValue('Tester')
  await wrapper.get('input[name="email"]').setValue('test@example.test')
  await wrapper.get('input[name="subject"]').setValue('Hello')
  await wrapper.get('textarea[name="message"]').setValue('Preserved synthetic message')
  await wrapper.get('input[name="privacyAccepted"]').setValue(true)
}

it.each([
  [new ContactSubmissionError('validation', { email: 'Server-safe email error' }), 'Server-safe email error'],
  [new ContactSubmissionError('malformed'), 'Please check the fields below'],
] as const)('focuses the safe summary and retains input for %s', async (failure, expected) => {
  vi.spyOn(contactApi, 'submit').mockRejectedValue(failure)
  const wrapper = mount(ContactForm, { attachTo: document.body, props: { locale: 'en', contact: site('en').contact, deletionEmail: 'delete@example.test' } })
  await fill(wrapper); await wrapper.get('form').trigger('submit'); await flushPromises()
  expect(wrapper.text()).toContain(expected)
  expect(document.activeElement).toBe(wrapper.get('[role="alert"]').element)
  expect((wrapper.get('textarea[name="message"]').element as HTMLTextAreaElement).value).toBe('Preserved synthetic message')
  wrapper.unmount()
})

it('retains values for 429/500 and succeeds only through explicit retry', async () => {
  const submit = vi.spyOn(contactApi, 'submit')
    .mockRejectedValueOnce(new ContactSubmissionError('rate-limited', {}, 60))
    .mockRejectedValueOnce(new ContactSubmissionError('retryable'))
    .mockResolvedValueOnce({ accepted: true })
  const wrapper = mount(ContactForm, { props: { locale: 'en', contact: site('en').contact, deletionEmail: 'delete@example.test' } })
  await fill(wrapper); await wrapper.get('form').trigger('submit'); await flushPromises()
  expect(wrapper.text()).toContain('60s'); expect((wrapper.get('input[name="name"]').element as HTMLInputElement).value).toBe('Tester')
  await wrapper.get('form').trigger('submit'); await flushPromises()
  expect(wrapper.text()).toContain('preserved for retry')
  expect(submit).toHaveBeenCalledTimes(2)
  expect(wrapper.get('button[type="submit"]').text()).toBe('Retry')
  await wrapper.get('form').trigger('submit'); await flushPromises()
  expect(submit).toHaveBeenCalledTimes(3); expect(wrapper.text()).toContain('safely accepted')
})
