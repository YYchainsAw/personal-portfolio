<script setup lang="ts">
import { useId } from 'vue'

import type { BlockCopy, CodePayload } from '@/types/blocks'
import type { Locale, Localized } from '@/types/content'

interface Props {
  modelValue: CodePayload
  locale: Locale
  disabled?: boolean
  fieldErrors?: Readonly<Record<string, string>>
}

const props = withDefaults(defineProps<Props>(), {
  disabled: false,
  fieldErrors: undefined,
})

const emit = defineEmits<{
  'update:modelValue': [value: CodePayload]
}>()

type CopyField = keyof BlockCopy

const instanceId = useId()

function inputValue(event: Event): string {
  return (event.target as HTMLInputElement | HTMLTextAreaElement).value
}

function checkedValue(event: Event): boolean {
  return (event.target as HTMLInputElement).checked
}

function cloneCopy(): Localized<BlockCopy> {
  return {
    'zh-CN': { ...props.modelValue.copy['zh-CN'] },
    en: { ...props.modelValue.copy.en },
  }
}

function completePayload(patch: Partial<Omit<CodePayload, 'type'>>): CodePayload {
  return {
    type: 'CODE',
    code: props.modelValue.code,
    language: props.modelValue.language,
    showLineNumbers: props.modelValue.showLineNumbers,
    copy: cloneCopy(),
    ...patch,
  }
}

function updateText(field: 'code' | 'language', event: Event): void {
  if (props.disabled) return
  emit('update:modelValue', completePayload({ [field]: inputValue(event) }))
}

function updateLineNumbers(event: Event): void {
  if (props.disabled) return
  emit(
    'update:modelValue',
    completePayload({ showLineNumbers: checkedValue(event) }),
  )
}

function updateCopy(field: CopyField, event: Event): void {
  if (props.disabled) return
  const copy = cloneCopy()
  copy[props.locale] = {
    ...copy[props.locale],
    [field]: inputValue(event),
  }
  emit('update:modelValue', completePayload({ copy }))
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
  return `${instanceId}-code-${path.replace(/[^a-zA-Z0-9_-]/g, '-')}-error`
}
</script>

<template>
  <section class="space-y-5" data-block-editor="CODE" aria-label="代码内容块">
    <div class="grid gap-4 sm:grid-cols-[minmax(0,1fr)_auto]">
      <label class="text-sm font-medium text-slate-800">
        语言标识
        <input
          class="mt-2 w-full rounded-xl border border-slate-300 px-3 py-2.5 font-mono"
          data-field="language"
          name="language"
          autocomplete="off"
          :value="modelValue.language"
          :disabled="disabled"
          :aria-invalid="fieldError('language') ? 'true' : undefined"
          :aria-describedby="fieldError('language') ? errorId('language') : undefined"
          @input="updateText('language', $event)"
        />
        <span
          v-if="fieldError('language')"
          :id="errorId('language')"
          class="mt-2 block text-sm text-red-700"
          role="alert"
        >{{ fieldError('language') }}</span>
      </label>

      <label class="flex items-center gap-2 self-end pb-2.5 text-sm font-medium text-slate-800">
        <input
          data-field="showLineNumbers"
          name="showLineNumbers"
          type="checkbox"
          :checked="modelValue.showLineNumbers"
          :disabled="disabled"
          :aria-invalid="fieldError('showLineNumbers') ? 'true' : undefined"
          :aria-describedby="fieldError('showLineNumbers') ? errorId('showLineNumbers') : undefined"
          @change="updateLineNumbers"
        />
        显示行号
        <span
          v-if="fieldError('showLineNumbers')"
          :id="errorId('showLineNumbers')"
          class="text-sm text-red-700"
          role="alert"
        >{{ fieldError('showLineNumbers') }}</span>
      </label>
    </div>

    <label class="block text-sm font-medium text-slate-800">
      代码
      <textarea
        class="mt-2 w-full resize-y rounded-xl border border-slate-300 px-3 py-2.5 font-mono text-sm"
        data-field="code"
        name="code"
        rows="12"
        spellcheck="false"
        :value="modelValue.code"
        :disabled="disabled"
        :aria-invalid="fieldError('code') ? 'true' : undefined"
        :aria-describedby="fieldError('code') ? errorId('code') : undefined"
        @input="updateText('code', $event)"
      />
      <span
        v-if="fieldError('code')"
        :id="errorId('code')"
        class="mt-2 block text-sm text-red-700"
        role="alert"
      >{{ fieldError('code') }}</span>
    </label>

    <div class="grid gap-4 sm:grid-cols-2">
      <label class="text-sm font-medium text-slate-800">
        标题
        <input
          class="mt-2 w-full rounded-xl border border-slate-300 px-3 py-2.5"
          :data-field="`copy.${locale}.title`"
          :name="`copy.${locale}.title`"
          :lang="locale"
          :value="modelValue.copy[locale].title"
          :disabled="disabled"
          :aria-invalid="fieldError(`copy.${locale}.title`) ? 'true' : undefined"
          :aria-describedby="fieldError(`copy.${locale}.title`) ? errorId(`copy.${locale}.title`) : undefined"
          @input="updateCopy('title', $event)"
        />
        <span
          v-if="fieldError(`copy.${locale}.title`)"
          :id="errorId(`copy.${locale}.title`)"
          class="mt-2 block text-sm text-red-700"
          role="alert"
        >{{ fieldError(`copy.${locale}.title`) }}</span>
      </label>

      <label class="text-sm font-medium text-slate-800">
        说明
        <textarea
          class="mt-2 w-full resize-y rounded-xl border border-slate-300 px-3 py-2.5"
          :data-field="`copy.${locale}.description`"
          :name="`copy.${locale}.description`"
          :lang="locale"
          rows="3"
          :value="modelValue.copy[locale].description"
          :disabled="disabled"
          :aria-invalid="fieldError(`copy.${locale}.description`) ? 'true' : undefined"
          :aria-describedby="fieldError(`copy.${locale}.description`) ? errorId(`copy.${locale}.description`) : undefined"
          @input="updateCopy('description', $event)"
        />
        <span
          v-if="fieldError(`copy.${locale}.description`)"
          :id="errorId(`copy.${locale}.description`)"
          class="mt-2 block text-sm text-red-700"
          role="alert"
        >{{ fieldError(`copy.${locale}.description`) }}</span>
      </label>
    </div>

    <div class="rounded-xl bg-slate-950 p-4 text-slate-100" aria-label="代码预览">
      <pre
        class="overflow-x-auto whitespace-pre text-sm"
        data-code-preview
        :data-language="modelValue.language"
        :data-line-numbers="modelValue.showLineNumbers"
      ><code>{{ modelValue.code }}</code></pre>
    </div>
  </section>
</template>
