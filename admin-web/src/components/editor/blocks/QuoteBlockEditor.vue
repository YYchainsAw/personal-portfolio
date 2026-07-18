<script setup lang="ts">
import { useId } from 'vue'

import type { QuoteCopy, QuotePayload } from '@/types/blocks'
import type { Locale, Localized } from '@/types/content'

interface Props {
  modelValue: QuotePayload
  locale: Locale
  disabled?: boolean
  fieldErrors?: Readonly<Record<string, string>>
}

const props = withDefaults(defineProps<Props>(), {
  disabled: false,
  fieldErrors: undefined,
})

const emit = defineEmits<{
  'update:modelValue': [value: QuotePayload]
}>()

type CopyField = keyof QuoteCopy

const instanceId = useId()

function inputValue(event: Event): string {
  return (event.target as HTMLInputElement | HTMLTextAreaElement).value
}

function cloneCopy(): Localized<QuoteCopy> {
  return {
    'zh-CN': { ...props.modelValue.copy['zh-CN'] },
    en: { ...props.modelValue.copy.en },
  }
}

function updateCopy(field: CopyField, event: Event): void {
  if (props.disabled) return
  const copy = cloneCopy()
  copy[props.locale] = {
    ...copy[props.locale],
    [field]: inputValue(event),
  }
  emit('update:modelValue', { type: 'QUOTE', copy })
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
  return `${instanceId}-quote-${path.replace(/[^a-zA-Z0-9_-]/g, '-')}-error`
}
</script>

<template>
  <section class="grid gap-4" data-block-editor="QUOTE" aria-label="引文内容块">
    <label class="text-sm font-medium text-slate-800">
      引文
      <textarea
        class="mt-2 w-full resize-y rounded-xl border border-slate-300 px-3 py-2.5"
        :data-field="`copy.${locale}.quote`"
        :name="`copy.${locale}.quote`"
        :lang="locale"
        rows="5"
        :value="modelValue.copy[locale].quote"
        :disabled="disabled"
        :aria-invalid="fieldError(`copy.${locale}.quote`) ? 'true' : undefined"
        :aria-describedby="fieldError(`copy.${locale}.quote`) ? errorId(`copy.${locale}.quote`) : undefined"
        @input="updateCopy('quote', $event)"
      />
      <span
        v-if="fieldError(`copy.${locale}.quote`)"
        :id="errorId(`copy.${locale}.quote`)"
        class="mt-2 block text-sm text-red-700"
        role="alert"
      >{{ fieldError(`copy.${locale}.quote`) }}</span>
    </label>

    <label class="text-sm font-medium text-slate-800">
      来源
      <input
        class="mt-2 w-full rounded-xl border border-slate-300 px-3 py-2.5"
        :data-field="`copy.${locale}.source`"
        :name="`copy.${locale}.source`"
        :lang="locale"
        :value="modelValue.copy[locale].source"
        :disabled="disabled"
        :aria-invalid="fieldError(`copy.${locale}.source`) ? 'true' : undefined"
        :aria-describedby="fieldError(`copy.${locale}.source`) ? errorId(`copy.${locale}.source`) : undefined"
        @input="updateCopy('source', $event)"
      />
      <span
        v-if="fieldError(`copy.${locale}.source`)"
        :id="errorId(`copy.${locale}.source`)"
        class="mt-2 block text-sm text-red-700"
        role="alert"
      >{{ fieldError(`copy.${locale}.source`) }}</span>
    </label>
  </section>
</template>
