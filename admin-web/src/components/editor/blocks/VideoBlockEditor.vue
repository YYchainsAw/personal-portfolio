<script setup lang="ts">
import { computed, nextTick, ref, useId, watch } from 'vue'

import { mediaApi } from '@/api/mediaApi'
import MediaPickerDialog, {
  type MediaPickerLoad,
} from '@/components/media/MediaPickerDialog.vue'
import {
  VIDEO_PROVIDERS,
  type BlockCopy,
  type VideoPayload,
  type VideoProvider,
} from '@/types/blocks'
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
  isReadyImageSelection,
  isResolvedImage,
  mediaTranslationState,
} from './blockMedia'
import { useResolvedMedia, type MediaResolver } from './useResolvedMedia'

interface Props {
  modelValue: VideoPayload
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
  'update:modelValue': [value: VideoPayload]
}>()

type CopyField = keyof BlockCopy

const imageAccept = Object.freeze(['IMAGE'] as const)
const pickerOpen = ref(false)
const selectCoverButton = ref<HTMLButtonElement | null>(null)
const announcement = ref('')
const instanceId = useId()
const defaultResolver: MediaResolver = (id) => mediaApi.get(id)
const { stateFor } = useResolvedMedia(
  () => [props.modelValue.coverAssetId],
  () => props.resolveMedia ?? defaultResolver,
  isResolvedImage,
)
const resolved = computed(() => stateFor(props.modelValue.coverAssetId))

function inputValue(event: Event): string {
  return (event.target as HTMLInputElement | HTMLTextAreaElement).value
}

function cloneCopy(): Localized<BlockCopy> {
  return {
    'zh-CN': { ...props.modelValue.copy['zh-CN'] },
    en: { ...props.modelValue.copy.en },
  }
}

function completePayload(patch: Partial<Omit<VideoPayload, 'type'>>): VideoPayload {
  return {
    type: 'VIDEO',
    provider: props.modelValue.provider,
    url: props.modelValue.url,
    coverAssetId: props.modelValue.coverAssetId,
    copy: cloneCopy(),
    ...patch,
  }
}

function error(path: string): string | undefined {
  return blockFieldError(props.fieldErrors, path)
}

function errorId(path: string): string {
  return `${instanceId}-video-${path.replace(/[^a-zA-Z0-9_-]/g, '-')}-error`
}

function urlError(): string | undefined {
  const serverError = error('url')
  if (serverError !== undefined) return serverError
  return isSafeHttpsUrl(props.modelValue.url)
    ? undefined
    : '视频地址必须使用安全的 HTTPS 链接'
}

function updateProvider(event: Event): void {
  if (props.disabled) return
  const select = event.target as HTMLSelectElement
  const provider = select.value
  if (!VIDEO_PROVIDERS.includes(provider as VideoProvider)) {
    select.value = props.modelValue.provider
    return
  }
  emit('update:modelValue', completePayload({ provider: provider as VideoProvider }))
}

function updateUrl(event: Event): void {
  if (props.disabled) return
  emit('update:modelValue', completePayload({ url: inputValue(event) }))
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

function selectCover(value: MediaAssetSummaryDto): void {
  pickerOpen.value = false
  if (props.disabled || !isReadyImageSelection(value)) return
  emit('update:modelValue', completePayload({ coverAssetId: value.id }))
  announcement.value = `已选择视频封面 ${value.originalFilename}`
}

async function clearCover(): Promise<void> {
  if (props.disabled || props.modelValue.coverAssetId === null) return
  emit('update:modelValue', completePayload({ coverAssetId: null }))
  announcement.value = '视频封面已移除'
  await nextTick()
  selectCoverButton.value?.focus()
}

watch(
  () => props.disabled,
  (disabled) => {
    if (disabled) pickerOpen.value = false
  },
)
</script>

<template>
  <section class="space-y-5" data-block-editor="VIDEO" aria-label="视频内容块">
    <div class="grid gap-4 sm:grid-cols-2">
      <label class="text-sm font-medium text-slate-800">
        视频平台
        <select
          class="mt-2 w-full rounded-xl border border-slate-300 px-3 py-2.5"
          data-field="provider"
          name="provider"
          :value="modelValue.provider"
          :disabled="disabled"
          :aria-invalid="error('provider') ? 'true' : undefined"
          :aria-describedby="error('provider') ? errorId('provider') : undefined"
          @change="updateProvider"
        >
          <option v-for="provider in VIDEO_PROVIDERS" :key="provider" :value="provider">
            {{ provider }}
          </option>
        </select>
        <span
          v-if="error('provider')"
          :id="errorId('provider')"
          class="mt-2 block text-sm text-red-700"
          role="alert"
        >{{ error('provider') }}</span>
      </label>

      <label class="text-sm font-medium text-slate-800">
        HTTPS 视频地址
        <input
          class="mt-2 w-full rounded-xl border border-slate-300 px-3 py-2.5"
          data-field="url"
          name="url"
          type="url"
          inputmode="url"
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
    </div>

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
          :aria-invalid="error(`copy.${locale}.title`) ? 'true' : undefined"
          :aria-describedby="error(`copy.${locale}.title`) ? errorId(`copy.${locale}.title`) : undefined"
          @input="updateCopy('title', $event)"
        />
        <span
          v-if="error(`copy.${locale}.title`)"
          :id="errorId(`copy.${locale}.title`)"
          class="mt-2 block text-sm text-red-700"
          role="alert"
        >{{ error(`copy.${locale}.title`) }}</span>
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

    <section
      class="rounded-xl border border-slate-200 p-4"
      aria-label="视频封面"
      :aria-describedby="error('coverAssetId') ? errorId('coverAssetId') : undefined"
    >
      <div
        class="flex flex-wrap gap-2"
        role="group"
        aria-label="视频封面选择"
        :aria-describedby="error('coverAssetId') ? errorId('coverAssetId') : undefined"
      >
        <button
          ref="selectCoverButton"
          type="button"
          data-action="select-video-cover"
          :disabled="disabled"
          @click="openPicker"
        >
          {{ modelValue.coverAssetId === null ? '选择可选封面' : '更换封面' }}
        </button>
        <button
          v-if="modelValue.coverAssetId !== null"
          type="button"
          data-action="clear-video-cover"
          :disabled="disabled"
          @click="clearCover"
        >移除封面</button>
      </div>

      <p class="sr-only" role="status" aria-live="polite" aria-atomic="true">
        {{ announcement }}
      </p>

      <p
        v-if="error('coverAssetId')"
        :id="errorId('coverAssetId')"
        class="mt-2 text-sm text-red-700"
        role="alert"
      >{{ error('coverAssetId') }}</p>

      <div
        v-if="modelValue.coverAssetId !== null"
        class="mt-3"
        :data-media-id="modelValue.coverAssetId"
        :data-media-resolution="resolved.status"
        role="status"
        aria-live="polite"
        :aria-busy="resolved.status === 'loading'"
      >
        <p class="break-all text-xs font-mono text-slate-500">{{ modelValue.coverAssetId }}</p>
        <p v-if="resolved.status === 'loading'" class="mt-2 text-sm text-slate-600">正在读取媒体信息…</p>
        <p v-else-if="resolved.status === 'error'" class="mt-2 text-sm text-red-700">无法读取媒体信息</p>
        <template v-else-if="resolved.status === 'ready'">
          <p class="mt-2 text-sm font-semibold text-slate-900">{{ resolved.asset.originalFilename }}</p>
          <ul class="mt-3 grid gap-3 sm:grid-cols-2" aria-label="封面媒体翻译完整度">
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

    <MediaPickerDialog
      v-model:open="pickerOpen"
      :accept="imageAccept"
      :load="loadMedia"
      @select="selectCover"
    />
  </section>
</template>
