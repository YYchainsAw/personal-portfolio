<script lang="ts">
export type StringFieldKey<T extends object> = {
  [K in keyof T]-?: T[K] extends string ? K : never
}[keyof T] & string

export interface SiteTranslationField<Key extends string = string> {
  readonly key: Key
  readonly label: string
  readonly multiline?: boolean
  readonly rows?: number
  readonly required?: boolean
  readonly inputType?: 'text' | 'email' | 'url'
  readonly autocomplete?: string
}
</script>

<script setup lang="ts" generic="T extends object = Record<string, string>">
import { useId } from 'vue'

import type { Locale } from '@/types/content'

const props = withDefaults(
  defineProps<{
    modelValue: T
    locale: Locale
    title: string
    fields: readonly SiteTranslationField<StringFieldKey<T>>[]
    disabled?: boolean
    fieldErrors?: Readonly<Record<string, string>>
  }>(),
  {
    disabled: false,
    fieldErrors: undefined,
  },
)

const emit = defineEmits<{
  'update:modelValue': [value: T]
}>()

const localeLabels: Readonly<Record<Locale, string>> = Object.freeze({
  'zh-CN': '中文',
  en: 'English',
})
const instanceId = useId()
const legendId = `${instanceId}-translation-form-legend`

function controlId(key: StringFieldKey<T>): string {
  return `${instanceId}-translation-field-${key}`
}

function errorId(key: StringFieldKey<T>): string {
  return `${controlId(key)}-error`
}

function valueFor(key: StringFieldKey<T>): string {
  const copy = props.modelValue as unknown as Readonly<Record<string, unknown>>
  const value = copy[key]
  return typeof value === 'string' ? value : ''
}

function updateField(key: StringFieldKey<T>, event: Event): void {
  const value = (event.target as HTMLInputElement | HTMLTextAreaElement).value
  emit('update:modelValue', { ...props.modelValue, [key]: value } as T)
}
</script>

<template>
  <fieldset
    class="rounded-2xl border border-slate-200 bg-white p-5 shadow-sm sm:p-6"
    :aria-labelledby="legendId"
  >
    <legend :id="legendId" class="px-1 text-lg font-semibold text-slate-950">
      {{ title }}
      <span class="ml-2 text-sm font-medium text-blue-700">{{ localeLabels[locale] }}</span>
    </legend>

    <div class="mt-4 grid gap-5">
      <div v-for="field in fields" :key="field.key">
        <label :for="controlId(field.key)" class="block text-sm font-medium text-slate-800">
          {{ field.label }}
          <span v-if="field.required" class="text-red-600" aria-hidden="true">*</span>
        </label>

        <textarea
          v-if="field.multiline"
          :id="controlId(field.key)"
          class="mt-2 w-full resize-y rounded-xl border border-slate-300 bg-white px-3 py-2.5 leading-6 text-slate-950 outline-none transition focus:border-blue-500 focus:ring-2 focus:ring-blue-100 disabled:cursor-not-allowed disabled:bg-slate-100 disabled:text-slate-500"
          :data-field="field.key"
          :name="field.key"
          :lang="locale"
          :rows="field.rows ?? 4"
          :value="valueFor(field.key)"
          :required="field.required"
          :disabled="disabled"
          :aria-invalid="props.fieldErrors?.[field.key] ? 'true' : undefined"
          :aria-describedby="props.fieldErrors?.[field.key] ? errorId(field.key) : undefined"
          @input="updateField(field.key, $event)"
        />
        <input
          v-else
          :id="controlId(field.key)"
          class="mt-2 w-full rounded-xl border border-slate-300 bg-white px-3 py-2.5 text-slate-950 outline-none transition focus:border-blue-500 focus:ring-2 focus:ring-blue-100 disabled:cursor-not-allowed disabled:bg-slate-100 disabled:text-slate-500"
          :data-field="field.key"
          :name="field.key"
          :lang="locale"
          :type="field.inputType ?? 'text'"
          :autocomplete="field.autocomplete"
          :value="valueFor(field.key)"
          :required="field.required"
          :disabled="disabled"
          :aria-invalid="props.fieldErrors?.[field.key] ? 'true' : undefined"
          :aria-describedby="props.fieldErrors?.[field.key] ? errorId(field.key) : undefined"
          @input="updateField(field.key, $event)"
        />

        <p
          v-if="props.fieldErrors?.[field.key]"
          :id="errorId(field.key)"
          class="mt-2 text-sm text-red-700"
          role="alert"
        >
          {{ props.fieldErrors[field.key] }}
        </p>
      </div>
    </div>
  </fieldset>
</template>
