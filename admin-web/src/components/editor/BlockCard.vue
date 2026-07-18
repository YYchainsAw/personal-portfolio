<script setup lang="ts">
import { computed, useId } from 'vue'

import type { MediaPickerLoad } from '@/components/media/MediaPickerDialog.vue'
import CodeBlockEditor from '@/components/editor/blocks/CodeBlockEditor.vue'
import DownloadBlockEditor from '@/components/editor/blocks/DownloadBlockEditor.vue'
import GalleryBlockEditor from '@/components/editor/blocks/GalleryBlockEditor.vue'
import ImageBlockEditor from '@/components/editor/blocks/ImageBlockEditor.vue'
import LinkBlockEditor from '@/components/editor/blocks/LinkBlockEditor.vue'
import MarkdownBlockEditor from '@/components/editor/blocks/MarkdownBlockEditor.vue'
import MetricsBlockEditor from '@/components/editor/blocks/MetricsBlockEditor.vue'
import QuoteBlockEditor from '@/components/editor/blocks/QuoteBlockEditor.vue'
import VideoBlockEditor from '@/components/editor/blocks/VideoBlockEditor.vue'
import {
  BLOCK_ALIGNMENTS,
  BLOCK_EMPHASES,
  BLOCK_WIDTHS,
  type BlockType,
  type CodePayload,
  type ContentBlockDto,
  type ContentBlockPayload,
  type DownloadPayload,
  type GalleryPayload,
  type ImagePayload,
  type LinkPayload,
  type MarkdownPayload,
  type MetricsPayload,
  type QuotePayload,
  type VideoPayload,
} from '@/types/blocks'
import type { Locale, MediaAssetView } from '@/types/content'

interface Props {
  readonly block: ContentBlockDto
  readonly locale: Locale
  readonly disabled?: boolean
  readonly fieldErrors?: Readonly<Record<string, string>>
  readonly loadMedia?: MediaPickerLoad
  readonly resolveMedia?: (id: string) => Promise<MediaAssetView>
  readonly labelledby?: string
}

const props = withDefaults(defineProps<Props>(), { disabled: false })
const emit = defineEmits<{
  'update:block': [block: ContentBlockDto]
}>()

const instanceId = useId()
const activeType = computed(() => exhaustiveType(props.block.payload))

function assertNever(value: never): never {
  throw new Error(`Unsupported content block payload: ${String(value)}`)
}

function exhaustiveType(payload: ContentBlockPayload): BlockType {
  switch (payload.type) {
    case 'MARKDOWN':
    case 'IMAGE':
    case 'GALLERY':
    case 'VIDEO':
    case 'CODE':
    case 'QUOTE':
    case 'METRICS':
    case 'DOWNLOAD':
    case 'LINK':
      return payload.type
    default:
      return assertNever(payload)
  }
}

function emitShared(patch: Partial<Pick<ContentBlockDto,
  'visible' | 'width' | 'alignment' | 'emphasis' | 'columns'
>>): void {
  if (props.disabled) return
  emit('update:block', { ...props.block, ...patch, payload: props.block.payload })
}

function updateVisible(event: Event): void {
  emitShared({ visible: (event.target as HTMLInputElement).checked })
}

function updateWidth(event: Event): void {
  const width = (event.target as HTMLSelectElement).value
  if (BLOCK_WIDTHS.includes(width as ContentBlockDto['width'])) {
    emitShared({ width: width as ContentBlockDto['width'] })
  }
}

function updateAlignment(event: Event): void {
  const alignment = (event.target as HTMLSelectElement).value
  if (BLOCK_ALIGNMENTS.includes(alignment as ContentBlockDto['alignment'])) {
    emitShared({ alignment: alignment as ContentBlockDto['alignment'] })
  }
}

function updateEmphasis(event: Event): void {
  const emphasis = (event.target as HTMLSelectElement).value
  if (BLOCK_EMPHASES.includes(emphasis as ContentBlockDto['emphasis'])) {
    emitShared({ emphasis: emphasis as ContentBlockDto['emphasis'] })
  }
}

function updateColumns(event: Event): void {
  const columns = Number((event.target as HTMLSelectElement).value)
  if (Number.isInteger(columns) && columns >= 1 && columns <= 4) emitShared({ columns })
}

function updatePayload(expected: BlockType, payload: ContentBlockPayload): void {
  if (
    props.disabled ||
    props.block.payload.type !== expected ||
    payload.type !== expected
  ) {
    return
  }
  emit('update:block', { ...props.block, payload })
}

const updateMarkdown = (payload: MarkdownPayload) => updatePayload('MARKDOWN', payload)
const updateImage = (payload: ImagePayload) => updatePayload('IMAGE', payload)
const updateGallery = (payload: GalleryPayload) => updatePayload('GALLERY', payload)
const updateVideo = (payload: VideoPayload) => updatePayload('VIDEO', payload)
const updateCode = (payload: CodePayload) => updatePayload('CODE', payload)
const updateQuote = (payload: QuotePayload) => updatePayload('QUOTE', payload)
const updateMetrics = (payload: MetricsPayload) => updatePayload('METRICS', payload)
const updateDownload = (payload: DownloadPayload) => updatePayload('DOWNLOAD', payload)
const updateLink = (payload: LinkPayload) => updatePayload('LINK', payload)

function fieldError(field: string): string | undefined {
  for (const [path, message] of Object.entries(props.fieldErrors ?? {})) {
    if (path === field || path.endsWith(`.${field}`)) return message
  }
  return undefined
}

function errorId(field: string): string {
  return `${instanceId}-block-shared-${field}-error`
}
</script>

<template>
  <section
    class="space-y-5"
    data-block-card
    :data-block-type="activeType"
    :aria-labelledby="labelledby"
  >
    <fieldset :disabled="disabled" class="rounded-xl border border-slate-200 bg-white p-4">
      <legend class="px-1 text-sm font-semibold text-slate-900">显示与布局</legend>
      <div class="grid gap-4 sm:grid-cols-2 lg:grid-cols-5">
        <label class="flex items-center gap-2 text-sm font-medium text-slate-800">
          <input
            :checked="block.visible"
            data-field="visible"
            type="checkbox"
            :disabled="disabled"
            :aria-invalid="fieldError('visible') ? 'true' : undefined"
            :aria-describedby="fieldError('visible') ? errorId('visible') : undefined"
            @change="updateVisible"
          />
          公开显示
          <span
            v-if="fieldError('visible')"
            :id="errorId('visible')"
            data-shared-error="visible"
            class="text-xs text-red-700"
            role="alert"
          >{{ fieldError('visible') }}</span>
        </label>

        <label class="text-sm font-medium text-slate-800">
          宽度
          <select
            :value="block.width"
            data-field="width"
            class="mt-2 w-full rounded-lg border border-slate-300 bg-white px-3 py-2"
            :disabled="disabled"
            :aria-invalid="fieldError('width') ? 'true' : undefined"
            :aria-describedby="fieldError('width') ? errorId('width') : undefined"
            @change="updateWidth"
          >
            <option v-for="width in BLOCK_WIDTHS" :key="width" :value="width">{{ width }}</option>
          </select>
          <span
            v-if="fieldError('width')"
            :id="errorId('width')"
            data-shared-error="width"
            class="mt-1 block text-xs text-red-700"
            role="alert"
          >{{ fieldError('width') }}</span>
        </label>

        <label class="text-sm font-medium text-slate-800">
          对齐
          <select
            :value="block.alignment"
            data-field="alignment"
            class="mt-2 w-full rounded-lg border border-slate-300 bg-white px-3 py-2"
            :disabled="disabled"
            :aria-invalid="fieldError('alignment') ? 'true' : undefined"
            :aria-describedby="fieldError('alignment') ? errorId('alignment') : undefined"
            @change="updateAlignment"
          >
            <option v-for="alignment in BLOCK_ALIGNMENTS" :key="alignment" :value="alignment">{{ alignment }}</option>
          </select>
          <span
            v-if="fieldError('alignment')"
            :id="errorId('alignment')"
            data-shared-error="alignment"
            class="mt-1 block text-xs text-red-700"
            role="alert"
          >{{ fieldError('alignment') }}</span>
        </label>

        <label class="text-sm font-medium text-slate-800">
          强调
          <select
            :value="block.emphasis"
            data-field="emphasis"
            class="mt-2 w-full rounded-lg border border-slate-300 bg-white px-3 py-2"
            :disabled="disabled"
            :aria-invalid="fieldError('emphasis') ? 'true' : undefined"
            :aria-describedby="fieldError('emphasis') ? errorId('emphasis') : undefined"
            @change="updateEmphasis"
          >
            <option v-for="emphasis in BLOCK_EMPHASES" :key="emphasis" :value="emphasis">{{ emphasis }}</option>
          </select>
          <span
            v-if="fieldError('emphasis')"
            :id="errorId('emphasis')"
            data-shared-error="emphasis"
            class="mt-1 block text-xs text-red-700"
            role="alert"
          >{{ fieldError('emphasis') }}</span>
        </label>

        <label class="text-sm font-medium text-slate-800">
          列数
          <select
            :value="block.columns"
            data-field="columns"
            class="mt-2 w-full rounded-lg border border-slate-300 bg-white px-3 py-2"
            :disabled="disabled"
            :aria-invalid="fieldError('columns') ? 'true' : undefined"
            :aria-describedby="fieldError('columns') ? errorId('columns') : undefined"
            @change="updateColumns"
          >
            <option v-for="columns in [1, 2, 3, 4]" :key="columns" :value="columns">{{ columns }}</option>
          </select>
          <span
            v-if="fieldError('columns')"
            :id="errorId('columns')"
            data-shared-error="columns"
            class="mt-1 block text-xs text-red-700"
            role="alert"
          >{{ fieldError('columns') }}</span>
        </label>
      </div>
    </fieldset>

    <MarkdownBlockEditor
      v-if="block.payload.type === 'MARKDOWN'"
      :model-value="block.payload"
      :locale="locale"
      :disabled="disabled"
      :field-errors="fieldErrors"
      @update:model-value="updateMarkdown"
    />
    <ImageBlockEditor
      v-else-if="block.payload.type === 'IMAGE'"
      :model-value="block.payload"
      :locale="locale"
      :disabled="disabled"
      :field-errors="fieldErrors"
      :load-media="loadMedia"
      :resolve-media="resolveMedia"
      @update:model-value="updateImage"
    />
    <GalleryBlockEditor
      v-else-if="block.payload.type === 'GALLERY'"
      :model-value="block.payload"
      :locale="locale"
      :disabled="disabled"
      :field-errors="fieldErrors"
      :load-media="loadMedia"
      :resolve-media="resolveMedia"
      @update:model-value="updateGallery"
    />
    <VideoBlockEditor
      v-else-if="block.payload.type === 'VIDEO'"
      :model-value="block.payload"
      :locale="locale"
      :disabled="disabled"
      :field-errors="fieldErrors"
      :load-media="loadMedia"
      :resolve-media="resolveMedia"
      @update:model-value="updateVideo"
    />
    <CodeBlockEditor
      v-else-if="block.payload.type === 'CODE'"
      :model-value="block.payload"
      :locale="locale"
      :disabled="disabled"
      :field-errors="fieldErrors"
      @update:model-value="updateCode"
    />
    <QuoteBlockEditor
      v-else-if="block.payload.type === 'QUOTE'"
      :model-value="block.payload"
      :locale="locale"
      :disabled="disabled"
      :field-errors="fieldErrors"
      @update:model-value="updateQuote"
    />
    <MetricsBlockEditor
      v-else-if="block.payload.type === 'METRICS'"
      :model-value="block.payload"
      :locale="locale"
      :disabled="disabled"
      :field-errors="fieldErrors"
      @update:model-value="updateMetrics"
    />
    <DownloadBlockEditor
      v-else-if="block.payload.type === 'DOWNLOAD'"
      :model-value="block.payload"
      :locale="locale"
      :disabled="disabled"
      :field-errors="fieldErrors"
      :load-media="loadMedia"
      :resolve-media="resolveMedia"
      @update:model-value="updateDownload"
    />
    <LinkBlockEditor
      v-else-if="block.payload.type === 'LINK'"
      :model-value="block.payload"
      :locale="locale"
      :disabled="disabled"
      :field-errors="fieldErrors"
      @update:model-value="updateLink"
    />
  </section>
</template>
