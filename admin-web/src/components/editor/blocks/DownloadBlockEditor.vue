<script setup lang="ts">
import { computed, nextTick, ref, useId, watch } from 'vue'

import { mediaApi } from '@/api/mediaApi'
import MediaPickerDialog, {
  type MediaPickerLoad,
} from '@/components/media/MediaPickerDialog.vue'
import type { ActionCopy, DownloadPayload } from '@/types/blocks'
import {
  locales,
  type Locale,
  type Localized,
  type MediaAssetSummaryDto,
  type MediaAssetView,
} from '@/types/content'

import { isSafeHttpsUrl } from '../blockValidation'
import {
  blockFieldError,
  isReadyPdfSelection,
  isResolvedPdf,
  mediaTranslationState,
} from './blockMedia'
import { useResolvedMedia, type MediaResolver } from './useResolvedMedia'

interface Props {
  modelValue: DownloadPayload
  locale: Locale
  disabled?: boolean
  fieldErrors?: Readonly<Record<string, string>>
  loadMedia?: MediaPickerLoad
  resolveMedia?: (id: string) => Promise<MediaAssetView>
}

const props = withDefaults(defineProps<Props>(), {
  disabled: false,
  fieldErrors: undefined,
  loadMedia: undefined,
  resolveMedia: undefined,
})

const emit = defineEmits<{
  'update:modelValue': [value: DownloadPayload]
}>()

type CopyField = keyof ActionCopy
type DownloadMode = 'media' | 'external'

const pdfAccept = Object.freeze(['PDF'] as const)
const pickerOpen = ref(false)
const selectPdfButton = ref<HTMLButtonElement | null>(null)
const announcement = ref('')
const instanceId = useId()
const modeName = `${instanceId}-download-mode`
const mode = computed<DownloadMode>(() =>
  props.modelValue.externalUrl === null ? 'media' : 'external',
)
const defaultResolver: MediaResolver = (id) => mediaApi.get(id)
const { stateFor } = useResolvedMedia(
  () => [props.modelValue.mediaAssetId],
  () => props.resolveMedia ?? defaultResolver,
  isResolvedPdf,
)
const resolved = computed(() => stateFor(props.modelValue.mediaAssetId))

function inputValue(event: Event): string {
  return (event.target as HTMLInputElement | HTMLTextAreaElement).value
}

function cloneCopy(): Localized<ActionCopy> {
  return {
    'zh-CN': { ...props.modelValue.copy['zh-CN'] },
    en: { ...props.modelValue.copy.en },
  }
}

function completePayload(
  patch: Partial<Omit<DownloadPayload, 'type'>>,
): DownloadPayload {
  return {
    type: 'DOWNLOAD',
    mediaAssetId: props.modelValue.mediaAssetId,
    externalUrl: props.modelValue.externalUrl,
    copy: cloneCopy(),
    ...patch,
  }
}

function error(path: string): string | undefined {
  return blockFieldError(props.fieldErrors, path)
}

function errorId(path: string): string {
  return `${instanceId}-download-${path.replace(/[^a-zA-Z0-9_-]/g, '-')}-error`
}

function urlError(): string | undefined {
  if (mode.value !== 'external') return undefined
  const serverError = error('externalUrl')
  if (serverError !== undefined) return serverError
  return isSafeHttpsUrl(props.modelValue.externalUrl ?? '')
    ? undefined
    : '外部下载地址必须使用安全的 HTTPS 链接'
}

function updateMode(event: Event): void {
  if (props.disabled) return
  const input = event.target as HTMLInputElement
  if (!input.checked) return
  if (input.value === 'media') {
    emit(
      'update:modelValue',
      completePayload({ mediaAssetId: props.modelValue.mediaAssetId, externalUrl: null }),
    )
    announcement.value = '下载来源已切换为媒体库 PDF'
    return
  }
  if (input.value === 'external') {
    emit(
      'update:modelValue',
      completePayload({ mediaAssetId: null, externalUrl: props.modelValue.externalUrl ?? '' }),
    )
    announcement.value = '下载来源已切换为外部 HTTPS 地址'
  }
}

function updateExternalUrl(event: Event): void {
  if (props.disabled) return
  emit(
    'update:modelValue',
    completePayload({ mediaAssetId: null, externalUrl: inputValue(event) }),
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

function openPicker(): void {
  if (!props.disabled) pickerOpen.value = true
}

function selectPdf(value: MediaAssetSummaryDto): void {
  pickerOpen.value = false
  if (props.disabled || !isReadyPdfSelection(value)) return
  emit(
    'update:modelValue',
    completePayload({ mediaAssetId: value.id, externalUrl: null }),
  )
  announcement.value = `已选择 PDF ${value.originalFilename}`
}

async function clearPdf(): Promise<void> {
  if (props.disabled || props.modelValue.mediaAssetId === null) return
  emit(
    'update:modelValue',
    completePayload({ mediaAssetId: null, externalUrl: null }),
  )
  announcement.value = 'PDF 已移除'
  await nextTick()
  selectPdfButton.value?.focus()
}

watch(
  () => props.disabled,
  (disabled) => {
    if (disabled) pickerOpen.value = false
  },
)
</script>

<template>
  <section class="space-y-5" data-block-editor="DOWNLOAD" aria-label="下载内容块">
    <fieldset
      class="space-y-3"
      :aria-describedby="error('target') ? errorId('target') : undefined"
    >
      <legend class="text-sm font-semibold text-slate-800">下载来源</legend>
      <div class="flex flex-wrap gap-4">
        <label class="flex items-center gap-2 text-sm font-medium text-slate-800">
          <input
            type="radio"
            :name="modeName"
            value="media"
            data-mode="media"
            :checked="mode === 'media'"
            :disabled="disabled"
            :aria-invalid="error('target') ? 'true' : undefined"
            :aria-describedby="error('target') ? errorId('target') : undefined"
            @change="updateMode"
          />
          媒体库 PDF
        </label>
        <label class="flex items-center gap-2 text-sm font-medium text-slate-800">
          <input
            type="radio"
            :name="modeName"
            value="external"
            data-mode="external"
            :checked="mode === 'external'"
            :disabled="disabled"
            :aria-invalid="error('target') ? 'true' : undefined"
            :aria-describedby="error('target') ? errorId('target') : undefined"
            @change="updateMode"
          />
          外部 HTTPS 地址
        </label>
      </div>
      <p
        v-if="error('target')"
        :id="errorId('target')"
        class="text-sm text-red-700"
        role="alert"
      >{{ error('target') }}</p>
    </fieldset>

    <p class="sr-only" role="status" aria-live="polite" aria-atomic="true">
      {{ announcement }}
    </p>

    <section
      v-if="mode === 'media'"
      class="rounded-xl border border-slate-200 p-4"
      aria-label="PDF 选择"
      :aria-describedby="error('mediaAssetId') ? errorId('mediaAssetId') : undefined"
    >
      <div
        class="flex flex-wrap gap-2"
        role="group"
        aria-label="PDF 媒体选择"
        :aria-describedby="error('mediaAssetId') ? errorId('mediaAssetId') : undefined"
      >
        <button
          ref="selectPdfButton"
          type="button"
          data-action="select-download-pdf"
          :disabled="disabled"
          @click="openPicker"
        >
          {{ modelValue.mediaAssetId === null ? '选择 PDF' : '更换 PDF' }}
        </button>
        <button
          v-if="modelValue.mediaAssetId !== null"
          type="button"
          data-action="clear-download-pdf"
          :disabled="disabled"
          @click="clearPdf"
        >移除 PDF</button>
      </div>

      <p
        v-if="error('mediaAssetId')"
        :id="errorId('mediaAssetId')"
        class="mt-2 text-sm text-red-700"
        role="alert"
      >{{ error('mediaAssetId') }}</p>

      <div
        v-if="modelValue.mediaAssetId !== null"
        class="mt-3"
        :data-media-id="modelValue.mediaAssetId"
        :data-media-resolution="resolved.status"
        role="status"
        aria-live="polite"
        :aria-busy="resolved.status === 'loading'"
      >
        <p class="break-all text-xs font-mono text-slate-500">{{ modelValue.mediaAssetId }}</p>
        <p v-if="resolved.status === 'loading'" class="mt-2 text-sm text-slate-600">正在读取媒体信息…</p>
        <p v-else-if="resolved.status === 'error'" class="mt-2 text-sm text-red-700">无法读取媒体信息</p>
        <template v-else-if="resolved.status === 'ready'">
          <p class="mt-2 text-sm font-semibold text-slate-900">{{ resolved.asset.originalFilename }}</p>
          <ul class="mt-3 grid gap-3 sm:grid-cols-2" aria-label="PDF 媒体翻译完整度">
            <li
              v-for="mediaLocale in locales"
              :key="mediaLocale"
              class="rounded-lg bg-slate-50 p-3 text-sm"
              :data-media-locale="mediaLocale"
              :data-active="mediaLocale === locale"
            >
              <p class="font-semibold">{{ mediaLocale }}</p>
              <p
                class="mt-1"
                data-translation-field="altText"
                :data-complete="mediaTranslationState(resolved.asset, mediaLocale).altText.complete"
              >
                Alt 文本：{{ mediaTranslationState(resolved.asset, mediaLocale).altText.complete
                  ? `完整 · ${mediaTranslationState(resolved.asset, mediaLocale).altText.value}`
                  : '缺失' }}
              </p>
              <p
                class="mt-1"
                data-translation-field="caption"
                :data-complete="mediaTranslationState(resolved.asset, mediaLocale).caption.complete"
              >
                Caption：{{ mediaTranslationState(resolved.asset, mediaLocale).caption.complete
                  ? `完整 · ${mediaTranslationState(resolved.asset, mediaLocale).caption.value}`
                  : '缺失' }}
              </p>
            </li>
          </ul>
        </template>
      </div>
    </section>

    <label v-else class="block text-sm font-medium text-slate-800">
      外部 HTTPS 下载地址
      <input
        class="mt-2 w-full rounded-xl border border-slate-300 px-3 py-2.5"
        data-field="externalUrl"
        name="externalUrl"
        type="url"
        inputmode="url"
        :value="modelValue.externalUrl ?? ''"
        :disabled="disabled"
        :aria-invalid="urlError() ? 'true' : undefined"
        :aria-describedby="urlError() ? errorId('externalUrl') : undefined"
        @input="updateExternalUrl"
      />
      <span
        v-if="urlError()"
        :id="errorId('externalUrl')"
        class="mt-2 block text-sm text-red-700"
        data-url-error
        role="alert"
      >{{ urlError() }}</span>
    </label>

    <div class="grid gap-4 sm:grid-cols-2">
      <label class="text-sm font-medium text-slate-800">
        按钮文字
        <input
          class="mt-2 w-full rounded-xl border border-slate-300 px-3 py-2.5"
          :data-field="`copy.${locale}.label`"
          :name="`copy.${locale}.label`"
          :lang="locale"
          :value="modelValue.copy[locale].label"
          :disabled="disabled"
          :aria-invalid="error(`copy.${locale}.label`) ? 'true' : undefined"
          :aria-describedby="error(`copy.${locale}.label`) ? errorId(`copy.${locale}.label`) : undefined"
          @input="updateCopy('label', $event)"
        />
        <span
          v-if="error(`copy.${locale}.label`)"
          :id="errorId(`copy.${locale}.label`)"
          class="mt-2 block text-sm text-red-700"
          role="alert"
        >{{ error(`copy.${locale}.label`) }}</span>
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
          :aria-invalid="error(`copy.${locale}.description`) ? 'true' : undefined"
          :aria-describedby="error(`copy.${locale}.description`) ? errorId(`copy.${locale}.description`) : undefined"
          @input="updateCopy('description', $event)"
        />
        <span
          v-if="error(`copy.${locale}.description`)"
          :id="errorId(`copy.${locale}.description`)"
          class="mt-2 block text-sm text-red-700"
          role="alert"
        >{{ error(`copy.${locale}.description`) }}</span>
      </label>
    </div>

    <MediaPickerDialog
      v-model:open="pickerOpen"
      :accept="pdfAccept"
      :load="loadMedia"
      @select="selectPdf"
    />
  </section>
</template>
