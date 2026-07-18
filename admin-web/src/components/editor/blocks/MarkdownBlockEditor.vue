<script setup lang="ts">
import { useId } from 'vue'

import type { MarkdownPayload } from '@/types/blocks'
import type { Locale } from '@/types/content'

interface Props {
  modelValue: MarkdownPayload
  locale: Locale
  disabled?: boolean
  fieldErrors?: Readonly<Record<string, string>>
}

const props = withDefaults(defineProps<Props>(), {
  disabled: false,
  fieldErrors: undefined,
})

const emit = defineEmits<{
  'update:modelValue': [value: MarkdownPayload]
}>()

const instanceId = useId()

function inputValue(event: Event): string {
  return (event.target as HTMLTextAreaElement).value
}

function normalizedPath(path: string): string {
  return path.replace(/\[(\d+)\]/g, '.$1')
}

function fieldError(path: string): string | undefined {
  const expected = normalizedPath(path)
  for (const [candidate, message] of Object.entries(props.fieldErrors ?? {})) {
    const normalizedCandidate = normalizedPath(candidate)
    if (
      normalizedCandidate === expected ||
      normalizedCandidate === `payload.${expected}` ||
      normalizedCandidate.endsWith(`.payload.${expected}`)
    ) {
      return message
    }
  }
  return undefined
}

function errorId(path: string): string {
  return `${instanceId}-markdown-${path.replace(/[^a-zA-Z0-9_-]/g, '-')}-error`
}

function updateMarkdown(event: Event): void {
  if (props.disabled) return
  const nextValue = inputValue(event)
  emit('update:modelValue', {
    type: 'MARKDOWN',
    markdown: {
      'zh-CN': props.locale === 'zh-CN' ? nextValue : props.modelValue.markdown['zh-CN'],
      en: props.locale === 'en' ? nextValue : props.modelValue.markdown.en,
    },
  })
}
</script>

<template>
  <section class="space-y-3" data-block-editor="MARKDOWN" aria-label="Markdown 内容块">
    <label class="block text-sm font-medium text-slate-800">
      Markdown 正文
      <textarea
        class="mt-2 w-full resize-y rounded-xl border border-slate-300 px-3 py-2.5 font-mono text-sm"
        :data-field="`markdown.${locale}`"
        :name="`markdown.${locale}`"
        :lang="locale"
        rows="12"
        :value="modelValue.markdown[locale]"
        :disabled="disabled"
        :aria-invalid="fieldError(`markdown.${locale}`) ? 'true' : undefined"
        :aria-describedby="fieldError(`markdown.${locale}`) ? errorId(`markdown.${locale}`) : undefined"
        @input="updateMarkdown"
      />
      <span
        v-if="fieldError(`markdown.${locale}`)"
        :id="errorId(`markdown.${locale}`)"
        class="mt-2 block text-sm text-red-700"
        role="alert"
      >{{ fieldError(`markdown.${locale}`) }}</span>
    </label>
  </section>
</template>
