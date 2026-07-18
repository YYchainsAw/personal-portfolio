<script setup lang="ts">
import { nextTick, ref, useId, watch } from 'vue'

import { mediaApi } from '@/api/mediaApi'
import MediaPickerDialog, {
  type MediaPickerLoad,
} from '@/components/media/MediaPickerDialog.vue'
import type { GalleryPayload } from '@/types/blocks'
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
  modelValue: GalleryPayload
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
  'update:modelValue': [value: GalleryPayload]
}>()

const imageAccept = Object.freeze(['IMAGE'] as const)
const pickerOpen = ref(false)
const list = ref<HTMLOListElement | null>(null)
const addButton = ref<HTMLButtonElement | null>(null)
const announcement = ref('')
const instanceId = useId()
const defaultResolver: MediaResolver = (id) => mediaApi.get(id)
const { stateFor } = useResolvedMedia(
  () => props.modelValue.mediaAssetIds,
  () => props.resolveMedia ?? defaultResolver,
  isResolvedImage,
)

function error(path: string): string | undefined {
  return blockFieldError(props.fieldErrors, path)
}

function errorId(path: string): string {
  return `${instanceId}-gallery-${path.replace(/[^a-zA-Z0-9_-]/g, '-')}-error`
}

function itemHeadingId(index: number): string {
  return `${instanceId}-gallery-${index}-title`
}

function itemFor(id: string): HTMLElement | null {
  return [...(list.value?.querySelectorAll<HTMLElement>('[data-gallery-id]') ?? [])]
    .find((item) => item.dataset.galleryId?.toLocaleLowerCase() === id.toLocaleLowerCase()) ?? null
}

function emitIds(ids: readonly string[]): void {
  emit('update:modelValue', {
    type: 'GALLERY',
    mediaAssetIds: [...ids],
  })
}

function openPicker(): void {
  if (!props.disabled) pickerOpen.value = true
}

function selectImage(value: MediaAssetSummaryDto): void {
  pickerOpen.value = false
  if (props.disabled || !isReadyImageSelection(value)) return
  const normalizedId = value.id.toLocaleLowerCase()
  if (
    props.modelValue.mediaAssetIds.some(
      (id) => id.toLocaleLowerCase() === normalizedId,
    )
  ) {
    return
  }
  const next = [...props.modelValue.mediaAssetIds, value.id]
  emitIds(next)
  announcement.value = `图片已添加，第 ${next.length} 项，共 ${next.length} 项`
}

function canMove(index: number, offset: -1 | 1): boolean {
  const target = index + offset
  return !props.disabled && target >= 0 && target < props.modelValue.mediaAssetIds.length
}

async function moveImage(index: number, offset: -1 | 1, event: MouseEvent): Promise<void> {
  if (!canMove(index, offset)) return
  const target = index + offset
  const reordered = [...props.modelValue.mediaAssetIds]
  const current = reordered[index]
  const adjacent = reordered[target]
  if (current === undefined || adjacent === undefined) return
  reordered[index] = adjacent
  reordered[target] = current
  emitIds(reordered)
  announcement.value = `图片已移动到第 ${target + 1} 项，共 ${reordered.length} 项`

  const trigger = event.currentTarget as HTMLButtonElement | null
  await nextTick()
  if (trigger?.isConnected && !trigger.disabled) {
    trigger.focus()
    return
  }
  const fallbackDirection = offset === -1 ? 'down' : 'up'
  const row = itemFor(current)
  ;(row?.querySelector<HTMLButtonElement>(`[data-direction="${fallbackDirection}"]:not(:disabled)`)
    ?? row?.querySelector<HTMLButtonElement>('[data-action="remove-gallery-image"]'))?.focus()
}

async function removeImage(index: number): Promise<void> {
  const removed = props.modelValue.mediaAssetIds[index]
  if (props.disabled || removed === undefined) return
  const next = props.modelValue.mediaAssetIds.filter((_id, itemIndex) => itemIndex !== index)
  emitIds(next)
  announcement.value = `图片已移除，剩余 ${next.length} 张图片`
  await nextTick()
  const focusId = next[Math.min(index, next.length - 1)]
  if (focusId !== undefined) {
    itemFor(focusId)?.querySelector<HTMLElement>('[data-gallery-heading]')?.focus()
    return
  }
  addButton.value?.focus()
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
    data-block-editor="GALLERY"
    aria-label="画廊内容块"
    :aria-describedby="error('mediaAssetIds') ? errorId('mediaAssetIds') : undefined"
  >
    <div class="flex items-center justify-between gap-3">
      <p class="text-sm text-slate-600">按列表顺序展示图片；至少需要两张图片。</p>
      <button
        ref="addButton"
        type="button"
        data-action="add-gallery-image"
        :disabled="disabled"
        @click="openPicker"
      >添加图片</button>
    </div>

    <p class="sr-only" role="status" aria-live="polite" aria-atomic="true">
      {{ announcement }}
    </p>

    <p
      v-if="error('mediaAssetIds')"
      :id="errorId('mediaAssetIds')"
      class="text-sm text-red-700"
      role="alert"
    >{{ error('mediaAssetIds') }}</p>

    <p
      v-if="modelValue.mediaAssetIds.length === 0"
      class="rounded-xl border border-dashed border-slate-300 p-4 text-sm text-slate-500"
      data-state="empty"
    >尚未选择图片。</p>

    <ol v-else ref="list" class="grid list-none gap-4 p-0" aria-label="画廊图片">
      <li
        v-for="(id, index) in modelValue.mediaAssetIds"
        :key="id.toLocaleLowerCase()"
        class="rounded-xl border border-slate-200 p-4"
        :data-gallery-id="id"
        :data-media-resolution="stateFor(id).status"
        :aria-labelledby="itemHeadingId(index)"
        :aria-describedby="error(`mediaAssetIds.${index}`) ? errorId(`mediaAssetIds.${index}`) : undefined"
      >
        <div class="flex flex-wrap items-start justify-between gap-3">
          <div>
            <h3
              :id="itemHeadingId(index)"
              class="rounded text-sm font-semibold text-slate-800 focus:outline-none focus:ring-2 focus:ring-blue-500"
              data-gallery-heading
              tabindex="-1"
            >图片 {{ index + 1 }}</h3>
            <p class="mt-1 break-all text-xs font-mono text-slate-500">{{ id }}</p>
          </div>
          <div class="flex gap-2" role="group" :aria-label="`图片 ${index + 1} 操作`">
            <button
              type="button"
              data-direction="up"
              :disabled="!canMove(index, -1)"
              :aria-label="`上移图片 ${index + 1}`"
              @click="moveImage(index, -1, $event)"
            >上移</button>
            <button
              type="button"
              data-direction="down"
              :disabled="!canMove(index, 1)"
              :aria-label="`下移图片 ${index + 1}`"
              @click="moveImage(index, 1, $event)"
            >下移</button>
            <button
              type="button"
              data-action="remove-gallery-image"
              :disabled="disabled"
              :aria-label="`移除图片 ${index + 1}`"
              @click="removeImage(index)"
            >移除</button>
          </div>
        </div>

        <div
          class="mt-2"
          role="status"
          aria-live="polite"
          :aria-busy="stateFor(id).status === 'loading'"
        >
          <p v-if="stateFor(id).status === 'loading'" class="text-sm text-slate-600">正在读取媒体信息…</p>
          <p v-else-if="stateFor(id).status === 'error'" class="text-sm text-red-700">无法读取媒体信息</p>
          <template v-else-if="stateFor(id).status === 'ready'">
            <p class="text-sm font-semibold text-slate-900">{{ stateFor(id).asset?.originalFilename }}</p>
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
                :data-complete="mediaTranslationState(stateFor(id).asset, mediaLocale).altText.complete"
              >
                Alt 文本：{{ mediaTranslationState(stateFor(id).asset, mediaLocale).altText.complete
                  ? `完整 · ${mediaTranslationState(stateFor(id).asset, mediaLocale).altText.value}`
                  : '缺失' }}
              </p>
              <p
                class="mt-1"
                data-translation-field="caption"
                :data-complete="mediaTranslationState(stateFor(id).asset, mediaLocale).caption.complete"
              >
                Caption：{{ mediaTranslationState(stateFor(id).asset, mediaLocale).caption.complete
                  ? `完整 · ${mediaTranslationState(stateFor(id).asset, mediaLocale).caption.value}`
                  : '缺失' }}
              </p>
            </li>
            </ul>
          </template>
        </div>

        <p
          v-if="error(`mediaAssetIds.${index}`)"
          :id="errorId(`mediaAssetIds.${index}`)"
          class="mt-2 text-sm text-red-700"
          role="alert"
        >{{ error(`mediaAssetIds.${index}`) }}</p>
      </li>
    </ol>

    <MediaPickerDialog
      v-model:open="pickerOpen"
      :accept="imageAccept"
      :load="loadMedia"
      @select="selectImage"
    />
  </section>
</template>
