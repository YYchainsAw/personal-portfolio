<script setup lang="ts">
import { nextTick, ref, useId, watch } from 'vue'

import type {
  MediaPickerLoad,
} from '@/components/media/MediaPickerDialog.vue'
import BlockCard from '@/components/editor/BlockCard.vue'
import {
  BLOCK_TYPES,
  createBlock,
  type BlockType,
  type ContentBlockDto,
} from '@/types/blocks'
import type { Locale, MediaAssetView } from '@/types/content'

import { moveBlock, removeBlock, renumberBlocks, replaceBlock } from './blockOrder'

interface Props {
  readonly blocks: readonly ContentBlockDto[]
  readonly locale: Locale
  readonly disabled?: boolean
  readonly fieldErrors?: Readonly<Record<string, string>>
  readonly loadMedia?: MediaPickerLoad
  readonly resolveMedia?: (id: string) => Promise<MediaAssetView>
}

const props = withDefaults(defineProps<Props>(), { disabled: false })
const emit = defineEmits<{
  'update:blocks': [blocks: ContentBlockDto[]]
}>()

const editorSection = ref<HTMLElement | null>(null)
const list = ref<HTMLOListElement | null>(null)
const draggedId = ref<string | null>(null)
const dropTargetIndex = ref<number | null>(null)
const announcement = ref('')
const instanceId = useId()
const headingId = `${instanceId}-content-blocks-title`
const sortHelpId = `${instanceId}-content-blocks-sort-help`

const labels: Readonly<Record<BlockType, string>> = Object.freeze({
  MARKDOWN: 'Markdown',
  IMAGE: '图片',
  GALLERY: '画廊',
  VIDEO: '视频',
  CODE: '代码',
  QUOTE: '引用',
  METRICS: '指标',
  DOWNLOAD: '下载',
  LINK: '链接',
})

function emitBlocks(blocks: ContentBlockDto[]): void {
  if (!props.disabled) emit('update:blocks', blocks)
}

function blockHeadingId(index: number): string {
  return `${instanceId}-content-block-${index}-title`
}

function blockErrorSummaryId(index: number): string {
  return `${instanceId}-content-block-${index}-errors`
}

function blockHeading(id: string): HTMLElement | null {
  return blockRow(id)?.querySelector<HTMLElement>('[data-block-heading]') ?? null
}

async function add(type: BlockType): Promise<void> {
  if (props.disabled) return
  const created = createBlock(type)
  const next = renumberBlocks([...props.blocks, created])
  emitBlocks(next)
  announcement.value = `${labels[type]} 已添加，第 ${next.length} 项，共 ${next.length} 项`
  await nextTick()
  blockHeading(created.id)?.focus()
}

function update(id: string, replacement: ContentBlockDto): void {
  if (props.disabled) return
  const current = props.blocks.find((block) => block.id === id)
  if (current === undefined || current.payload.type !== replacement.payload.type) return
  emitBlocks(replaceBlock(props.blocks, id, replacement))
}

async function remove(id: string, event: MouseEvent): Promise<void> {
  if (props.disabled) return
  const currentIndex = props.blocks.findIndex((block) => block.id === id)
  if (currentIndex < 0) return
  const removed = props.blocks[currentIndex]
  const next = removeBlock(props.blocks, id)
  emitBlocks(next)
  const removedLabel = removed === undefined ? '内容块' : labels[removed.payload.type]
  announcement.value = `${removedLabel} 已删除，剩余 ${next.length} 个内容块`

  await nextTick()
  const targetIndex = Math.min(currentIndex, next.length - 1)
  if (targetIndex >= 0) {
    blockHeading(next[targetIndex]?.id ?? '')?.focus()
    return
  }
  editorSection.value?.querySelector<HTMLButtonElement>('[data-add-block]')?.focus()
}

function blockRow(id: string): HTMLElement | null {
  if (id.length === 0) return null
  return (
    [...(list.value?.querySelectorAll<HTMLElement>('[data-block-id]') ?? [])]
      .find((row) => row.dataset.blockId === id) ?? null
  )
}

async function restoreMoveFocus(
  id: string,
  offset: -1 | 1,
  trigger: HTMLButtonElement | null,
): Promise<void> {
  await nextTick()
  if (trigger?.isConnected && !trigger.disabled) {
    trigger.focus()
    return
  }

  const fallbackDirection = offset === -1 ? 'down' : 'up'
  const row = blockRow(id)
  const fallback = row?.querySelector<HTMLButtonElement>(
    `[data-direction="${fallbackDirection}"]:not(:disabled)`,
  )
  ;(fallback ?? row?.querySelector<HTMLButtonElement>('[data-drag-handle]'))?.focus()
}

async function moveByOffset(id: string, offset: -1 | 1, event: MouseEvent): Promise<void> {
  if (props.disabled) return
  const currentIndex = props.blocks.findIndex((block) => block.id === id)
  const targetIndex = currentIndex + offset
  if (currentIndex < 0 || targetIndex < 0 || targetIndex >= props.blocks.length) return

  const current = props.blocks[currentIndex]
  emitBlocks(moveBlock(props.blocks, id, targetIndex))
  const currentLabel = current === undefined ? '内容块' : labels[current.payload.type]
  announcement.value = `${currentLabel} 已移动到第 ${targetIndex + 1} 项，共 ${props.blocks.length} 项`
  await restoreMoveFocus(id, offset, event.currentTarget as HTMLButtonElement | null)
}

function startDrag(id: string, event: DragEvent): void {
  if (props.disabled || !props.blocks.some((block) => block.id === id)) {
    event.preventDefault()
    return
  }
  draggedId.value = id
  if (event.dataTransfer !== null) {
    event.dataTransfer.effectAllowed = 'move'
    event.dataTransfer.setData('text/plain', id)
  }
}

function dragOver(targetIndex: number, event: DragEvent): void {
  if (props.disabled || draggedId.value === null) return
  event.preventDefault()
  dropTargetIndex.value = targetIndex
  if (event.dataTransfer !== null) event.dataTransfer.dropEffect = 'move'
}

async function dropAt(targetIndex: number, event: DragEvent): Promise<void> {
  if (props.disabled || draggedId.value === null) return
  event.preventDefault()
  const id = draggedId.value
  draggedId.value = null
  dropTargetIndex.value = null
  const currentIndex = props.blocks.findIndex((block) => block.id === id)
  if (currentIndex < 0) return

  const safeTarget = Math.max(0, Math.min(targetIndex, props.blocks.length - 1))
  emitBlocks(moveBlock(props.blocks, id, safeTarget))
  const current = props.blocks[currentIndex]
  const currentLabel = current === undefined ? '内容块' : labels[current.payload.type]
  announcement.value = `${currentLabel} 已移动到第 ${safeTarget + 1} 项，共 ${props.blocks.length} 项`
  await nextTick()
  blockHeading(id)?.focus()
}

function finishDrag(): void {
  draggedId.value = null
  dropTargetIndex.value = null
}

function scopedFieldErrors(index: number): Readonly<Record<string, string>> | undefined {
  if (props.fieldErrors === undefined) return undefined
  const bracketPrefix = `blocks[${index}].`
  const dotPrefix = `blocks.${index}.`
  const bracketRoot = `blocks[${index}]`
  const dotRoot = `blocks.${index}`
  const result: Record<string, string> = {}

  for (const [path, message] of Object.entries(props.fieldErrors)) {
    let localPath: string | null = null
    if (path === bracketRoot || path === dotRoot) {
      result[path] = message
      result.block = message
      if (props.blocks[index]?.payload.type === 'DOWNLOAD') {
        result.target = message
        result['payload.target'] = message
      }
    } else if (path.startsWith(bracketPrefix)) localPath = path.slice(bracketPrefix.length)
    else if (path.startsWith(dotPrefix)) localPath = path.slice(dotPrefix.length)
    else if (!/^blocks(?:\[|\.)/.test(path)) result[path] = message

    if (localPath !== null) {
      result[path] = message
      result[localPath] = message
    }
  }

  return Object.keys(result).length === 0 ? undefined : result
}

function blockStructuralErrors(index: number): readonly string[] {
  const scoped = scopedFieldErrors(index)
  if (scoped === undefined) return []
  return [...new Set([scoped.block, scoped.id, scoped.sortOrder].filter((message): message is string =>
    typeof message === 'string' && message.length > 0,
  ))]
}

watch(
  () => props.disabled,
  (disabled) => {
    if (disabled) finishDrag()
  },
)
</script>

<template>
  <section ref="editorSection" class="rounded-2xl border border-slate-200 bg-white p-6 shadow-sm" :aria-labelledby="headingId">
    <div class="flex flex-col gap-4 sm:flex-row sm:items-start sm:justify-between">
      <div>
        <p class="text-xs font-semibold tracking-[0.16em] text-blue-700">CONTENT BLOCKS</p>
        <h2 :id="headingId" class="mt-2 text-xl font-semibold text-slate-950">项目内容块</h2>
        <p class="mt-2 text-sm leading-6 text-slate-600">使用稳定编号排序，并按当前语言编辑双语内容。</p>
      </div>
      <div class="flex flex-wrap gap-2" aria-label="添加内容块">
        <button
          v-for="type in BLOCK_TYPES"
          :key="type"
          class="rounded-lg border border-slate-300 bg-white px-3 py-2 text-sm font-medium text-slate-700 hover:border-blue-300 hover:text-blue-800 disabled:cursor-not-allowed disabled:opacity-45"
          type="button"
          :data-add-block="type"
          :disabled="disabled"
          @click="add(type)"
        >
          {{ labels[type] }}
        </button>
      </div>
    </div>

    <p
      class="sr-only"
      data-block-announcement
      role="status"
      aria-live="polite"
      aria-atomic="true"
    >
      {{ announcement }}
    </p>
    <p :id="sortHelpId" class="sr-only">
      拖动手柄仅供指针操作；使用键盘时，请用每个内容块的上移和下移按钮排序。
    </p>

    <p
      v-if="blocks.length === 0"
      class="mt-5 rounded-xl border border-dashed border-slate-300 bg-slate-50 p-5 text-sm text-slate-500"
      data-empty-blocks
    >
      暂无内容块。请从上方选择一种类型。
    </p>

    <ol
      v-else
      ref="list"
      class="mt-5 grid list-none gap-4 p-0"
      aria-label="项目内容块顺序"
      :aria-describedby="sortHelpId"
    >
      <li
        v-for="(block, index) in blocks"
        :key="block.id"
        class="rounded-2xl border border-slate-200 bg-slate-50/70 p-4 transition"
        :class="{
          'border-blue-500 ring-2 ring-blue-200': dropTargetIndex === index && draggedId !== block.id,
        }"
        :data-block-id="block.id"
        :data-drop-target="dropTargetIndex === index && draggedId !== block.id ? 'true' : undefined"
        :aria-labelledby="blockHeadingId(index)"
        :aria-describedby="blockStructuralErrors(index).length > 0 ? blockErrorSummaryId(index) : undefined"
        @dragover="dragOver(index, $event)"
        @drop="dropAt(index, $event)"
      >
        <div class="mb-4 flex flex-wrap items-center justify-between gap-3">
          <div>
            <h3
              :id="blockHeadingId(index)"
              class="rounded text-sm font-semibold text-slate-900 focus:outline-none focus:ring-2 focus:ring-blue-500"
              data-block-heading
              tabindex="-1"
            >
              {{ labels[block.payload.type] }}
            </h3>
            <p class="mt-0.5 text-xs text-slate-500">第 {{ index + 1 }} 项，共 {{ blocks.length }} 项</p>
          </div>
          <div
            class="flex flex-wrap gap-2"
            role="group"
            :aria-label="`第 ${index + 1} 项 ${labels[block.payload.type]} 排序和删除`"
          >
            <span
              class="select-none rounded-lg border border-slate-300 bg-white px-3 py-2 text-sm text-slate-700"
              :class="disabled ? 'cursor-not-allowed opacity-45' : 'cursor-grab active:cursor-grabbing'"
              data-drag-handle
              :draggable="!disabled"
              :data-disabled="disabled ? 'true' : undefined"
              aria-hidden="true"
              @dragstart="startDrag(block.id, $event)"
              @dragend="finishDrag"
            >
              拖动
            </span>
            <button
              class="rounded-lg border border-slate-300 bg-white px-3 py-2 text-sm text-slate-700 disabled:cursor-not-allowed disabled:opacity-45"
              type="button"
              data-direction="up"
              :disabled="disabled || index === 0"
              :aria-label="`第 ${index + 1} 项 ${labels[block.payload.type]}：上移`"
              @click="moveByOffset(block.id, -1, $event)"
            >
              上移
            </button>
            <button
              class="rounded-lg border border-slate-300 bg-white px-3 py-2 text-sm text-slate-700 disabled:cursor-not-allowed disabled:opacity-45"
              type="button"
              data-direction="down"
              :disabled="disabled || index === blocks.length - 1"
              :aria-label="`第 ${index + 1} 项 ${labels[block.payload.type]}：下移`"
              @click="moveByOffset(block.id, 1, $event)"
            >
              下移
            </button>
            <button
              class="rounded-lg border border-red-200 bg-white px-3 py-2 text-sm font-medium text-red-700 hover:bg-red-50 disabled:cursor-not-allowed disabled:opacity-45"
              type="button"
              data-action="delete-block"
              :disabled="disabled"
              :aria-label="`第 ${index + 1} 项 ${labels[block.payload.type]}：删除`"
              @click="remove(block.id, $event)"
            >
              删除
            </button>
          </div>
        </div>

        <div
          v-if="blockStructuralErrors(index).length > 0"
          :id="blockErrorSummaryId(index)"
          class="mb-4 rounded-xl border border-red-200 bg-red-50 p-3 text-sm text-red-800 focus:outline-none focus:ring-2 focus:ring-red-500"
          data-block-error-summary
          role="alert"
          tabindex="-1"
        >
          <p class="font-semibold">请修正第 {{ index + 1 }} 个内容块</p>
          <ul class="mt-1 list-disc space-y-1 pl-5">
            <li v-for="message in blockStructuralErrors(index)" :key="message">{{ message }}</li>
          </ul>
        </div>

        <BlockCard
          :block="block"
          :locale="locale"
          :disabled="disabled"
          :field-errors="scopedFieldErrors(index)"
          :load-media="loadMedia"
          :resolve-media="resolveMedia"
          :labelledby="blockHeadingId(index)"
          @update:block="update(block.id, $event)"
        />
      </li>
    </ol>
  </section>
</template>
