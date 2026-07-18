<script setup lang="ts">
import { computed, nextTick, ref, useId, watch } from 'vue'

import { mediaApi } from '@/api/mediaApi'
import MediaPickerDialog, {
  type MediaPickerLoad,
} from '@/components/media/MediaPickerDialog.vue'
import type { ImagePayload } from '@/types/blocks'
import {
  locales,
  type Locale,
  type MediaAssetSummaryDto,
  type MediaAssetView,
} from '@/types/content'

import {
  blockFieldError,
  isReadyImageSelection,
  isResolvedImage,
  mediaTranslationState,
} from './blockMedia'
import { useResolvedMedia, type MediaResolver } from './useResolvedMedia'

interface Props {
  modelValue: ImagePayload
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
  'update:modelValue': [value: ImagePayload]
}>()

const imageAccept = Object.freeze(['IMAGE'] as const)
const pickerOpen = ref(false)
const selectButton = ref<HTMLButtonElement | null>(null)
const announcement = ref('')
const instanceId = useId()
const defaultResolver: MediaResolver = (id) => mediaApi.get(id)
const { stateFor } = useResolvedMedia(
  () => [props.modelValue.mediaAssetId],
  () => props.resolveMedia ?? defaultResolver,
  isResolvedImage,
)
const resolved = computed(() => stateFor(props.modelValue.mediaAssetId))

function error(path: string): string | undefined {
  return blockFieldError(props.fieldErrors, path)
}

function errorId(path: string): string {
  return `${instanceId}-image-${path.replace(/[^a-zA-Z0-9_-]/g, '-')}-error`
}

function openPicker(): void {
  if (!props.disabled) pickerOpen.value = true
}

function selectImage(value: MediaAssetSummaryDto): void {
  pickerOpen.value = false
  if (props.disabled || !isReadyImageSelection(value)) return
  emit('update:modelValue', {
    type: 'IMAGE',
    mediaAssetId: value.id,
  })
  announcement.value = `已选择图片 ${value.originalFilename}`
}

async function clearImage(): Promise<void> {
  if (props.disabled || props.modelValue.mediaAssetId === null) return
  emit('update:modelValue', { type: 'IMAGE', mediaAssetId: null })
  announcement.value = '图片已移除'
  await nextTick()
  selectButton.value?.focus()
}

watch(
  () => props.disabled,
  (disabled) => {
    if (disabled) pickerOpen.value = false
  },
)
</script>

<template>
  <section
    class="space-y-4"
    data-block-editor="IMAGE"
    aria-label="图片内容块"
    :aria-describedby="error('mediaAssetId') ? errorId('mediaAssetId') : undefined"
  >
    <div
      class="flex flex-wrap gap-2"
      role="group"
      aria-label="图片选择"
      :aria-describedby="error('mediaAssetId') ? errorId('mediaAssetId') : undefined"
    >
      <button
        ref="selectButton"
        type="button"
        data-action="select-image"
        :disabled="disabled"
        @click="openPicker"
      >{{ modelValue.mediaAssetId === null ? '选择图片' : '更换图片' }}</button>
      <button
        v-if="modelValue.mediaAssetId !== null"
        type="button"
        data-action="clear-image"
        :disabled="disabled"
        @click="clearImage"
      >移除图片</button>
    </div>

    <p class="sr-only" role="status" aria-live="polite" aria-atomic="true">
      {{ announcement }}
    </p>

    <p
      v-if="error('mediaAssetId')"
      :id="errorId('mediaAssetId')"
      class="text-sm text-red-700"
      role="alert"
    >{{ error('mediaAssetId') }}</p>

    <div
      v-if="modelValue.mediaAssetId !== null"
      class="rounded-xl border border-slate-200 p-4"
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
        <ul class="mt-3 grid gap-3 sm:grid-cols-2" aria-label="媒体翻译完整度">
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

    <MediaPickerDialog
      v-model:open="pickerOpen"
      :accept="imageAccept"
      :load="loadMedia"
      @select="selectImage"
    />
  </section>
</template>
