<script setup lang="ts">
import { useId } from 'vue'

import type { ActionCopy, LinkPayload } from '@/types/blocks'
import type { Locale, Localized } from '@/types/content'
import { isSafeHttpsUrl } from '../blockValidation'

interface Props {
  modelValue: LinkPayload
  locale: Locale
  disabled?: boolean
  fieldErrors?: Readonly<Record<string, string>>
}

const props = withDefaults(defineProps<Props>(), {
  disabled: false,
  fieldErrors: undefined,
})

const emit = defineEmits<{
  'update:modelValue': [value: LinkPayload]
}>()

type CopyField = keyof ActionCopy

const instanceId = useId()

function inputValue(event: Event): string {
  return (event.target as HTMLInputElement | HTMLTextAreaElement).value
}

function checkedValue(event: Event): boolean {
  return (event.target as HTMLInputElement).checked
}

function cloneCopy(): Localized<ActionCopy> {
  return {
    'zh-CN': { ...props.modelValue.copy['zh-CN'] },
    en: { ...props.modelValue.copy.en },
  }
}

function completePayload(patch: Partial<Omit<LinkPayload, 'type'>>): LinkPayload {
  return {
    type: 'LINK',
    url: props.modelValue.url,
    openNewTab: props.modelValue.openNewTab,
    copy: cloneCopy(),
    ...patch,
  }
}

function updateUrl(event: Event): void {
  if (props.disabled) return
  emit('update:modelValue', completePayload({ url: inputValue(event) }))
}

function updateOpenNewTab(event: Event): void {
  if (props.disabled) return
  emit(
    'update:modelValue',
    completePayload({ openNewTab: checkedValue(event) }),
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

function urlError(): string | undefined {
  const serverError = fieldError('url')
  if (serverError !== undefined) return serverError
  return isSafeHttpsUrl(props.modelValue.url)
    ? undefined
    : '链接必须是无账号、片段或危险编码的标准 HTTPS 地址'
}

function errorId(path: string): string {
  return `${instanceId}-link-${path.replace(/[^a-zA-Z0-9_-]/g, '-')}-error`
}
</script>

<template>
  <section class="grid gap-4" data-block-editor="LINK" aria-label="链接内容块">
    <label class="text-sm font-medium text-slate-800">
      HTTPS 链接
      <input
        class="mt-2 w-full rounded-xl border border-slate-300 px-3 py-2.5"
        data-field="url"
        name="url"
        type="url"
        inputmode="url"
        autocomplete="url"
        :value="modelValue.url"
        :disabled="disabled"
        :aria-invalid="urlError() ? 'true' : undefined"
        :aria-describedby="urlError() ? errorId('url') : undefined"
        @input="updateUrl"
      />
      <span
        v-if="urlError()"
        :id="errorId('url')"
        class="mt-2 block text-sm text-red-700"
        data-url-error
        role="alert"
      >{{ urlError() }}</span>
    </label>

    <label class="flex items-center gap-2 text-sm font-medium text-slate-800">
      <input
        data-field="openNewTab"
        name="openNewTab"
        type="checkbox"
        :checked="modelValue.openNewTab"
        :disabled="disabled"
        :aria-invalid="fieldError('openNewTab') ? 'true' : undefined"
        :aria-describedby="fieldError('openNewTab') ? errorId('openNewTab') : undefined"
        @change="updateOpenNewTab"
      />
      在新标签页中打开
      <span
        v-if="fieldError('openNewTab')"
        :id="errorId('openNewTab')"
        class="text-sm text-red-700"
        role="alert"
      >{{ fieldError('openNewTab') }}</span>
    </label>

    <div class="grid gap-4 sm:grid-cols-2">
      <label class="text-sm font-medium text-slate-800">
        链接文字
        <input
          class="mt-2 w-full rounded-xl border border-slate-300 px-3 py-2.5"
          :data-field="`copy.${locale}.label`"
          :name="`copy.${locale}.label`"
          :lang="locale"
          :value="modelValue.copy[locale].label"
          :disabled="disabled"
          :aria-invalid="fieldError(`copy.${locale}.label`) ? 'true' : undefined"
          :aria-describedby="fieldError(`copy.${locale}.label`) ? errorId(`copy.${locale}.label`) : undefined"
          @input="updateCopy('label', $event)"
        />
        <span
          v-if="fieldError(`copy.${locale}.label`)"
          :id="errorId(`copy.${locale}.label`)"
          class="mt-2 block text-sm text-red-700"
          role="alert"
        >{{ fieldError(`copy.${locale}.label`) }}</span>
      </label>

      <label class="text-sm font-medium text-slate-800">
        链接说明
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
  </section>
</template>
