<script setup lang="ts">
import { nextTick, ref, useId } from 'vue'

import { locales, type Locale, type TranslationStatus } from '@/types/content'

const props = withDefaults(
  defineProps<{
    modelValue: Locale
    status: TranslationStatus
    statusLabel?: string
    disabled?: boolean
  }>(),
  { disabled: false, statusLabel: '翻译完成度' },
)

const emit = defineEmits<{
  'update:modelValue': [locale: Locale]
}>()

const tablist = ref<HTMLElement | null>(null)
const instanceId = useId()
const labels: Readonly<Record<Locale, string>> = Object.freeze({
  'zh-CN': '中文',
  en: 'English',
})

function tabId(locale: Locale): string {
  return `${instanceId}-translation-tab-${locale}`
}

function panelId(locale: Locale): string {
  return `${instanceId}-translation-panel-${locale}`
}

async function select(locale: Locale, moveFocus = false): Promise<void> {
  if (props.disabled) return
  emit('update:modelValue', locale)
  if (!moveFocus) return
  await nextTick()
  tablist.value
    ?.querySelector<HTMLButtonElement>(`button[data-locale="${locale}"]`)
    ?.focus()
}

function adjacent(locale: Locale, offset: number): Locale {
  const index = locales.indexOf(locale)
  return locales[(index + offset + locales.length) % locales.length] ?? locales[0]
}

function onKeydown(event: KeyboardEvent, locale: Locale): void {
  let target: Locale | null = null
  if (event.key === 'ArrowRight') target = adjacent(locale, 1)
  if (event.key === 'ArrowLeft') target = adjacent(locale, -1)
  if (event.key === 'Home') target = 'zh-CN'
  if (event.key === 'End') target = 'en'
  if (target === null) return
  event.preventDefault()
  void select(target, true)
}
</script>

<template>
  <div>
    <div
      ref="tablist"
      class="inline-flex rounded-xl border border-slate-200 bg-slate-100 p-1"
      role="tablist"
      aria-label="内容语言"
    >
      <button
        v-for="locale in locales"
        :id="tabId(locale)"
        :key="locale"
        class="rounded-lg px-3 py-2 text-sm font-medium text-slate-600 transition-colors hover:text-slate-950 disabled:cursor-not-allowed disabled:opacity-60"
        :class="{ 'bg-white text-blue-800 shadow-sm': modelValue === locale }"
        type="button"
        role="tab"
        :data-locale="locale"
        :aria-controls="panelId(locale)"
        :aria-selected="modelValue === locale"
        :aria-label="`${labels[locale]}，${statusLabel} ${status[locale].complete}/${status[locale].total}`"
        :tabindex="modelValue === locale ? 0 : -1"
        :disabled="disabled"
        @click="select(locale)"
        @keydown="onKeydown($event, locale)"
      >
        <span>{{ labels[locale] }}</span>
        <span class="ml-2 text-xs text-slate-500">
          <span class="sr-only">{{ statusLabel }} </span>
          {{ status[locale].complete }}/{{ status[locale].total }}
        </span>
      </button>
    </div>

    <div
      v-for="locale in locales"
      :id="panelId(locale)"
      :key="`${locale}-panel`"
      role="tabpanel"
      :aria-labelledby="tabId(locale)"
      :hidden="modelValue !== locale"
    >
      <slot v-if="modelValue === locale" :locale="locale" />
    </div>
  </div>
</template>
