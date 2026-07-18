<script setup lang="ts">
import { nextTick, onBeforeUnmount, reactive, ref } from 'vue'
import { contactApi, ContactSubmissionError } from '@/services/contactApi'
import type { ContactRequest } from '@/types/interactions'
import type { Locale, PublicContact } from '@/types/public'

const props = defineProps<{ locale: Locale; contact: PublicContact; deletionEmail: string }>()
type Field = 'name' | 'email' | 'subject' | 'message' | 'privacyAccepted'
type FormState = 'IDLE' | 'SUBMITTING' | 'SUCCEEDED' | 'VALIDATION_ERROR' | 'RATE_LIMITED' | 'RETRYABLE_ERROR'

const model = reactive({ name: '', email: '', subject: '', message: '', website: '', privacyAccepted: false })
const errors = reactive<Partial<Record<Field, string>>>({})
const state = ref<FormState>('IDLE')
const retryAfter = ref<number | null>(null)
const summary = ref<HTMLElement | null>(null)
const status = ref<HTMLElement | null>(null)
let controller: AbortController | null = null

const t = () => props.locale === 'zh-CN' ? {
  name: '姓名', email: '邮箱', subject: '主题', message: '留言', submit: '发送留言', sending: '正在发送…',
  privacy: '我同意仅为回复此留言而处理这些信息。', success: '留言已被安全接收。', retry: '重试',
  error: '暂时无法发送，请保留内容后重试。', invalid: '请检查以下字段。', rate: '请求过于频繁，请稍后再试。',
  required: '此项为必填项。', emailInvalid: '请输入有效邮箱。', tooLong: '内容过长。', tooShort: '留言至少需要 1 个字。',
} : {
  name: 'Name', email: 'Email', subject: 'Subject', message: 'Message', submit: 'Send message', sending: 'Sending…',
  privacy: 'I agree to this information being processed only to reply to this message.', success: 'Your message was safely accepted.', retry: 'Retry',
  error: 'Unable to send right now. Your input is preserved for retry.', invalid: 'Please check the fields below.', rate: 'Too many requests. Please try again later.',
  required: 'This field is required.', emailInvalid: 'Enter a valid email address.', tooLong: 'This value is too long.', tooShort: 'The message must contain at least 1 character.',
}

function validate(): ContactRequest | null {
  Object.keys(errors).forEach((key) => delete errors[key as Field])
  const copy = t()
  const name = model.name.trim(), email = model.email.trim(), subject = model.subject.trim(), message = model.message.trim()
  if (!name) errors.name = copy.required; else if (name.length > 100) errors.name = copy.tooLong
  if (!email) errors.email = copy.required; else if (email.length > 320 || !/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(email)) errors.email = copy.emailInvalid
  if (!subject) errors.subject = copy.required; else if (subject.length > 160) errors.subject = copy.tooLong
  if (!message) errors.message = copy.required; else if (message.length > 5000) errors.message = copy.tooLong
  if (!model.privacyAccepted) errors.privacyAccepted = copy.required
  if (Object.keys(errors).length) return null
  return { name, email, subject, message, website: model.website.slice(0, 200), privacyAccepted: true }
}

async function submit() {
  if (state.value === 'SUBMITTING') return
  const request = validate()
  if (!request) {
    state.value = 'VALIDATION_ERROR'; await nextTick(); summary.value?.focus(); return
  }
  controller?.abort(); controller = new AbortController(); state.value = 'SUBMITTING'
  try {
    await contactApi.submit(request, controller.signal)
    Object.assign(model, { name: '', email: '', subject: '', message: '', website: '', privacyAccepted: false })
    state.value = 'SUCCEEDED'; await nextTick(); status.value?.focus()
  } catch (cause) {
    if (!(cause instanceof ContactSubmissionError) || cause.kind === 'aborted') return
    if (cause.kind === 'validation' || cause.kind === 'malformed') {
      if (cause.kind === 'validation') for (const [key, message] of Object.entries(cause.fieldErrors)) if (['name', 'email', 'subject', 'message', 'privacyAccepted'].includes(key)) errors[key as Field] = message
      state.value = 'VALIDATION_ERROR'; await nextTick(); summary.value?.focus()
    } else if (cause.kind === 'rate-limited') {
      retryAfter.value = cause.retryAfterSeconds; state.value = 'RATE_LIMITED'
    } else state.value = 'RETRYABLE_ERROR'
  }
}

onBeforeUnmount(() => controller?.abort())
</script>

<template>
  <form class="contact-form" novalidate @submit.prevent="submit">
    <div v-if="state === 'VALIDATION_ERROR'" ref="summary" class="form-summary" role="alert" tabindex="-1">{{ t().invalid }}</div>
    <div class="form-grid">
      <label for="contact-name">{{ t().name }}<input id="contact-name" v-model="model.name" name="name" required maxlength="100" :aria-invalid="!!errors.name" aria-describedby="contact-name-error" /><small id="contact-name-error">{{ errors.name || '' }}</small></label>
      <label for="contact-email">{{ t().email }}<input id="contact-email" v-model="model.email" name="email" type="email" required maxlength="320" autocomplete="email" :aria-invalid="!!errors.email" aria-describedby="contact-email-error" /><small id="contact-email-error">{{ errors.email || '' }}</small></label>
    </div>
    <label for="contact-subject">{{ t().subject }}<input id="contact-subject" v-model="model.subject" name="subject" required maxlength="160" :aria-invalid="!!errors.subject" aria-describedby="contact-subject-error" /><small id="contact-subject-error">{{ errors.subject || '' }}</small></label>
    <label for="contact-message">{{ t().message }}<textarea id="contact-message" v-model="model.message" name="message" required maxlength="5000" rows="6" :aria-invalid="!!errors.message" aria-describedby="contact-message-error"></textarea><small id="contact-message-error">{{ errors.message || '' }}</small></label>
    <div class="honeypot" aria-hidden="true"><label>Website<input v-model="model.website" name="website" maxlength="200" autocomplete="off" tabindex="-1" /></label><p>Leave this field empty.</p></div>
    <label class="privacy-check" for="contact-privacy"><input id="contact-privacy" v-model="model.privacyAccepted" name="privacyAccepted" type="checkbox" required :aria-invalid="!!errors.privacyAccepted" aria-describedby="contact-privacy-error" /> <span>{{ t().privacy }}</span><small id="contact-privacy-error">{{ errors.privacyAccepted || '' }}</small></label>
    <p class="retention-copy">
      {{ locale === 'zh-CN' ? '留言最长保留一年，除非更早删除。删除请求请联系' : 'Messages are retained for one year unless deleted earlier. For deletion requests, contact' }}
      <a v-if="deletionEmail" :href="`mailto:${deletionEmail}`">{{ deletionEmail }}</a>
      <span v-else>{{ locale === 'zh-CN' ? '站点所有者' : 'the site owner' }}</span>.
    </p>
    <p ref="status" class="form-status" aria-live="polite" :tabindex="state === 'SUCCEEDED' ? -1 : undefined">
      <template v-if="state === 'SUBMITTING'">{{ t().sending }}</template>
      <template v-else-if="state === 'SUCCEEDED'">{{ t().success }}</template>
      <template v-else-if="state === 'RATE_LIMITED'">{{ t().rate }}<span v-if="retryAfter"> ({{ retryAfter }}s)</span></template>
      <template v-else-if="state === 'RETRYABLE_ERROR'">{{ t().error }}</template>
    </p>
    <button type="submit" :disabled="state === 'SUBMITTING'">{{ state === 'RETRYABLE_ERROR' ? t().retry : t().submit }}</button>
  </form>
</template>

<style scoped>
.contact-form { display: grid; gap: 1rem; max-width: 760px; margin: 2rem 0; padding: clamp(1.25rem, 3vw, 2.25rem); border: 1px solid rgb(255 255 255 / 72%); border-radius: 1.5rem; color: var(--ink); background: #fff; box-shadow: 0 24px 60px rgb(21 39 96 / 18%); }
.form-grid { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 1rem; }
label { display: grid; gap: .45rem; font-size: .84rem; }
input, textarea { width: 100%; padding: .85rem 1rem; border: 1px solid var(--line); border-radius: .8rem; color: var(--ink); background: #fff; font: inherit; }
input[aria-invalid="true"], textarea[aria-invalid="true"] { border-color: #b42318; }
small, .form-summary { color: #b42318; }
.privacy-check { grid-template-columns: auto 1fr; align-items: start; }
.privacy-check input { width: 1rem; margin-top: .2rem; }
.privacy-check small { grid-column: 2; }
.retention-copy, .form-status { color: var(--muted); font-size: .82rem; }
.retention-copy a { color: #244dcc; text-decoration: underline; text-underline-offset: .15em; }
button { justify-self: start; padding: .8rem 1.25rem; border: 0; border-radius: 999px; color: #fff; background: var(--accent); cursor: pointer; }
button:disabled { opacity: .65; }
.honeypot { position: absolute !important; width: 1px; height: 1px; overflow: hidden; clip: rect(0 0 0 0); clip-path: inset(50%); white-space: nowrap; }
@media (max-width: 620px) { .contact-form { padding: 1.1rem; border-radius: 1.1rem; } .form-grid { grid-template-columns: 1fr; } }
</style>
