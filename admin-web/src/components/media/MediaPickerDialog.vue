<script lang="ts">
import type { Page as ApiPage } from '@/types/api'
import type { MediaAssetSummaryDto as PickerAsset } from '@/types/content'

export interface MediaPickerPageRequest {
  readonly page: number
  readonly size: number
}

export type MediaPickerLoad = (
  request: Readonly<MediaPickerPageRequest>,
) => Promise<ApiPage<PickerAsset>>
</script>

<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, ref, useId, watch } from 'vue'

import { mediaApi } from '@/api/mediaApi'
import { ApiProblem, type Page } from '@/types/api'
import type { MediaAssetSummaryDto, MediaKind } from '@/types/content'

const DEFAULT_PAGE_SIZE = 24
const MEDIA_KINDS = new Set<MediaKind>(['IMAGE', 'PDF', 'FILE'])
const UUID_PATTERN = /^[0-9a-f]{8}-(?:[0-9a-f]{4}-){3}[0-9a-f]{12}$/i
const IMAGE_PREVIEW_VARIANT_PATTERN = /^w[1-9][0-9]{0,9}$/

const props = withDefaults(
  defineProps<{
    open: boolean
    accept: readonly MediaKind[]
    load?: MediaPickerLoad
    pageSize?: number
  }>(),
  {
    load: (request: MediaPickerPageRequest) => mediaApi.search(request),
    pageSize: 24,
  },
)

const emit = defineEmits<{
  select: [asset: MediaAssetSummaryDto]
  close: []
  'update:open': [open: boolean]
}>()

interface DisplayError {
  readonly title: string
  readonly traceId: string
}

const dialog = ref<HTMLElement | null>(null)
const searchInput = ref<HTMLInputElement | null>(null)
const items = ref<MediaAssetSummaryDto[]>([])
const currentPage = ref(0)
const requestedPage = ref(0)
const totalPages = ref(0)
const searchText = ref('')
const loading = ref(false)
const error = ref<DisplayError | null>(null)
const selectionLocked = ref(false)
const titleId = `media-picker-title-${useId()}`
const searchId = `media-picker-search-${useId()}`
let requestGeneration = 0
let restoreTarget: HTMLElement | null = null
let closing = false

function normalizedPageSize(): number {
  return Number.isSafeInteger(props.pageSize) && props.pageSize > 0 && props.pageSize <= 100
    ? props.pageSize
    : DEFAULT_PAGE_SIZE
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value)
}

function isPage(
  value: unknown,
  expectedPage: number,
  expectedSize: number,
): value is Page<MediaAssetSummaryDto> {
  if (
    !isRecord(value) ||
    !Array.isArray(value.items) ||
    !Number.isSafeInteger(value.page) ||
    (value.page as number) < 0 ||
    !Number.isSafeInteger(value.size) ||
    (value.size as number) < 1 ||
    !Number.isSafeInteger(value.totalItems) ||
    (value.totalItems as number) < 0 ||
    !Number.isSafeInteger(value.totalPages) ||
    (value.totalPages as number) < 0
  ) {
    return false
  }

  const page = value.page as number
  const size = value.size as number
  const totalItems = value.totalItems as number
  const totalPages = value.totalPages as number
  const calculatedTotalPages = totalItems === 0 ? 0 : Math.ceil(totalItems / size)
  const pageIsInRange = totalPages > 0 && page < totalPages
  const remainingItems = pageIsInRange ? Math.max(0, totalItems - page * size) : 0
  const maximumPageItems = Math.min(size, remainingItems)

  return (
    page === expectedPage &&
    size === expectedSize &&
    totalPages === calculatedTotalPages &&
    value.items.length <= maximumPageItems
  )
}

function safeError(cause: unknown): DisplayError {
  if (cause instanceof ApiProblem) {
    return { title: cause.body.title, traceId: cause.body.traceId }
  }
  return { title: '无法加载媒体资源', traceId: 'client' }
}

function acceptedKinds(): ReadonlySet<MediaKind> {
  return new Set(props.accept.filter((kind) => MEDIA_KINDS.has(kind)))
}

function kindMatchesMimeType(kind: MediaKind, mimeType: string): boolean {
  if (kind === 'IMAGE') return mimeType === 'image/jpeg' || mimeType === 'image/png'
  if (kind === 'PDF') return mimeType === 'application/pdf'
  return !mimeType.startsWith('image/') && mimeType !== 'application/pdf'
}

function isSelectable(value: unknown): value is MediaAssetSummaryDto {
  if (!isRecord(value)) return false
  return (
    value.status === 'READY' &&
    typeof value.kind === 'string' &&
    MEDIA_KINDS.has(value.kind as MediaKind) &&
    acceptedKinds().has(value.kind as MediaKind) &&
    typeof value.id === 'string' &&
    UUID_PATTERN.test(value.id) &&
    typeof value.originalFilename === 'string' &&
    value.originalFilename.trim().length > 0 &&
    typeof value.mimeType === 'string' &&
    value.mimeType.trim().length > 0 &&
    kindMatchesMimeType(value.kind as MediaKind, value.mimeType)
  )
}

const selectableItems = computed(() => items.value.filter(isSelectable))

const visibleItems = computed(() => {
  const query = searchText.value.trim().toLocaleLowerCase()
  if (query.length === 0) return selectableItems.value
  return selectableItems.value.filter((asset) =>
    [asset.id, asset.originalFilename, asset.mimeType, asset.kind].some((value) =>
      value.toLocaleLowerCase().includes(query),
    ),
  )
})

const canGoPrevious = computed(() => !loading.value && currentPage.value > 0)
const canGoNext = computed(
  () =>
    !loading.value &&
    totalPages.value > 0 &&
    currentPage.value + 1 < totalPages.value,
)

const emptyTitle = computed(() => {
  if (searchText.value.trim().length > 0) return '当前页没有匹配的媒体'
  if (totalPages.value > 1) return '当前页没有兼容的媒体'
  return '没有可选择的媒体'
})

const emptyDescription = computed(() => {
  if (searchText.value.trim().length > 0) return '请调整搜索内容，或浏览其他页面。'
  if (totalPages.value > 1) return '本页资源与当前选择类型不兼容，请浏览其他页面。'
  return '请先上传并等待资源处理完成。'
})

function safePreviewUrl(asset: MediaAssetSummaryDto): string | null {
  if (
    asset.kind !== 'IMAGE' ||
    !UUID_PATTERN.test(asset.id) ||
    typeof asset.previewUrl !== 'string'
  ) {
    return null
  }

  const prefix = `/api/admin/media/${encodeURIComponent(asset.id)}/preview/`
  if (!asset.previewUrl.startsWith(prefix)) return null
  const variant = asset.previewUrl.slice(prefix.length)
  return IMAGE_PREVIEW_VARIANT_PATTERN.test(variant) ? asset.previewUrl : null
}

async function loadPage(
  page: number,
  manageFocus = false,
  allowOutOfRangeFallback = true,
): Promise<void> {
  if (!props.open || loading.value || !Number.isSafeInteger(page) || page < 0) return
  const pageSize = normalizedPageSize()
  const ownsFocus =
    manageFocus && dialog.value !== null && dialog.value.contains(document.activeElement)
  if (ownsFocus) dialog.value?.focus()
  const operation = ++requestGeneration
  requestedPage.value = page
  loading.value = true
  error.value = null
  try {
    const result = await props.load({ page, size: pageSize })
    if (operation !== requestGeneration || !props.open) return
    if (!isPage(result, page, pageSize)) throw new Error('invalid media page')
    const fallbackPage = result.totalPages === 0 ? 0 : result.totalPages - 1
    if (result.page !== fallbackPage && result.page >= result.totalPages) {
      if (!allowOutOfRangeFallback) throw new Error('media page remained out of range')
      loading.value = false
      await loadPage(fallbackPage, manageFocus, false)
      return
    }
    items.value = [...result.items]
    currentPage.value = result.page
    totalPages.value = result.totalPages
  } catch (cause) {
    if (operation !== requestGeneration || !props.open) return
    items.value = []
    error.value = safeError(cause)
  } finally {
    if (operation !== requestGeneration) return
    loading.value = false
    if (ownsFocus) {
      await nextTick()
      if (
        operation === requestGeneration &&
        props.open &&
        document.activeElement === dialog.value
      ) {
        searchInput.value?.focus()
      }
    }
  }
}

function requestClose(): void {
  if (!props.open || closing) return
  closing = true
  selectionLocked.value = true
  requestGeneration += 1
  loading.value = false
  emit('close')
  emit('update:open', false)
}

function selectAsset(asset: unknown): void {
  if (selectionLocked.value || closing || !isSelectable(asset)) return
  selectionLocked.value = true
  emit('select', asset)
  requestClose()
}

function retry(): void {
  if (!loading.value) void loadPage(requestedPage.value, true)
}

function previousPage(): void {
  if (canGoPrevious.value) void loadPage(currentPage.value - 1, true)
}

function nextPage(): void {
  if (canGoNext.value) void loadPage(currentPage.value + 1, true)
}

function focusableElements(): HTMLElement[] {
  if (dialog.value === null) return []
  return [...dialog.value.querySelectorAll<HTMLElement>(
    'button:not([disabled]), input:not([disabled]), [href], [tabindex]:not([tabindex="-1"])',
  )].filter((element) => !element.hasAttribute('hidden'))
}

function onDialogKeydown(event: KeyboardEvent): void {
  if (event.key === 'Escape') {
    event.preventDefault()
    requestClose()
    return
  }
  if (event.key !== 'Tab') return
  const focusable = focusableElements()
  const first = focusable[0]
  const last = focusable.at(-1)
  if (first === undefined || last === undefined) {
    event.preventDefault()
    dialog.value?.focus()
    return
  }
  if (event.shiftKey && (document.activeElement === first || document.activeElement === dialog.value)) {
    event.preventDefault()
    last.focus()
  } else if (!event.shiftKey && document.activeElement === last) {
    event.preventDefault()
    first.focus()
  }
}

function onGridKeydown(event: KeyboardEvent): void {
  if (!['ArrowLeft', 'ArrowRight', 'ArrowUp', 'ArrowDown', 'Home', 'End'].includes(event.key)) {
    return
  }
  const buttons = dialog.value === null
    ? []
    : [...dialog.value.querySelectorAll<HTMLButtonElement>('[data-asset-id]:not([disabled])')]
  if (buttons.length === 0) return
  const activeIndex = buttons.findIndex((button) => button === document.activeElement)
  if (activeIndex < 0) return
  let nextIndex = activeIndex
  if (event.key === 'Home') nextIndex = 0
  else if (event.key === 'End') nextIndex = buttons.length - 1
  else if (event.key === 'ArrowLeft' || event.key === 'ArrowUp') {
    nextIndex = Math.max(0, activeIndex - 1)
  } else {
    nextIndex = Math.min(buttons.length - 1, activeIndex + 1)
  }
  if (nextIndex === activeIndex) return
  event.preventDefault()
  buttons[nextIndex]?.focus()
}

watch(
  () => props.open,
  async (open) => {
    requestGeneration += 1
    loading.value = false
    selectionLocked.value = false
    if (!open) {
      closing = true
      await nextTick()
      if (restoreTarget?.isConnected) restoreTarget.focus()
      restoreTarget = null
      return
    }

    closing = false
    restoreTarget = document.activeElement instanceof HTMLElement ? document.activeElement : null
    searchText.value = ''
    items.value = []
    currentPage.value = 0
    requestedPage.value = 0
    totalPages.value = 0
    error.value = null
    await nextTick()
    searchInput.value?.focus()
    void loadPage(0)
  },
  { immediate: true },
)

onBeforeUnmount(() => {
  requestGeneration += 1
  closing = true
  loading.value = false
  if (restoreTarget?.isConnected) restoreTarget.focus()
  restoreTarget = null
})
</script>

<template>
  <div
    v-if="open"
    class="fixed inset-0 z-50 flex items-center justify-center bg-slate-950/45 p-4"
    data-media-picker-overlay
    @mousedown.self="requestClose"
  >
    <section
      ref="dialog"
      class="flex max-h-[min(48rem,calc(100vh-2rem))] w-full max-w-5xl flex-col overflow-hidden rounded-2xl bg-white shadow-2xl"
      role="dialog"
      aria-modal="true"
      :aria-labelledby="titleId"
      :aria-busy="loading"
      tabindex="-1"
      @keydown="onDialogKeydown"
    >
      <header class="flex items-start justify-between gap-4 border-b border-slate-200 px-6 py-5">
        <div>
          <p class="text-xs font-semibold tracking-[0.16em] text-slate-500">MEDIA LIBRARY</p>
          <h2 :id="titleId" class="mt-1 text-xl font-semibold text-slate-950">选择媒体</h2>
          <p class="mt-1 text-sm text-slate-600">仅显示处理完成且类型兼容的资源。</p>
        </div>
        <button
          class="rounded-lg border border-slate-300 px-3 py-2 text-sm font-semibold text-slate-700 hover:bg-slate-50"
          type="button"
          data-action="close"
          aria-label="关闭媒体选择器"
          @click="requestClose"
        >
          关闭
        </button>
      </header>

      <div class="border-b border-slate-200 px-6 py-4">
        <label :for="searchId" class="text-sm font-semibold text-slate-800">搜索当前页</label>
        <input
          :id="searchId"
          ref="searchInput"
          v-model="searchText"
          class="mt-2 w-full rounded-xl border border-slate-300 px-4 py-2.5 text-sm text-slate-950"
          type="search"
          autocomplete="off"
          placeholder="按文件名、类型或资源编号筛选"
        />
      </div>

      <div class="min-h-64 flex-1 overflow-y-auto px-6 py-5">
        <div v-if="loading" class="grid min-h-48 place-items-center" role="status" aria-live="polite">
          <p class="text-sm font-medium text-slate-600">正在加载媒体资源…</p>
        </div>

        <div
          v-else-if="error"
          class="rounded-xl border border-rose-200 bg-rose-50 p-4 text-rose-950"
          role="alert"
        >
          <p class="font-semibold">{{ error.title }}</p>
          <p class="mt-1 text-xs text-rose-800">请求编号：{{ error.traceId }}</p>
          <button
            class="mt-3 rounded-lg bg-rose-800 px-3 py-2 text-sm font-semibold text-white hover:bg-rose-900"
            type="button"
            data-action="retry"
            @click="retry"
          >
            重试
          </button>
        </div>

        <div
          v-else-if="visibleItems.length === 0"
          class="grid min-h-48 place-items-center rounded-xl border border-dashed border-slate-300 bg-slate-50 p-6 text-center"
          data-state="empty"
        >
          <div>
            <p class="font-semibold text-slate-800">
              {{ emptyTitle }}
            </p>
            <p class="mt-1 text-sm text-slate-600">
              {{ emptyDescription }}
            </p>
          </div>
        </div>

        <div
          v-else
          class="grid grid-cols-1 gap-4 sm:grid-cols-2 lg:grid-cols-3"
          data-asset-grid
          aria-label="可选择的媒体资源"
          @keydown="onGridKeydown"
        >
          <button
            v-for="asset in visibleItems"
            :key="asset.id"
            class="overflow-hidden rounded-xl border border-slate-200 bg-white text-left shadow-sm transition hover:border-blue-400 hover:shadow-md disabled:cursor-wait disabled:opacity-60"
            type="button"
            :data-asset-id="asset.id"
            :disabled="selectionLocked"
            :aria-label="`选择媒体：${asset.originalFilename}`"
            @click="selectAsset(asset)"
          >
            <span class="grid aspect-video place-items-center overflow-hidden bg-slate-100">
              <img
                v-if="safePreviewUrl(asset)"
                class="h-full w-full object-cover"
                :src="safePreviewUrl(asset)!"
                alt=""
              />
              <span v-else class="text-sm font-semibold text-slate-500" aria-hidden="true">
                {{ asset.kind }}
              </span>
            </span>
            <span class="block p-3">
              <span class="block truncate text-sm font-semibold text-slate-900">
                {{ asset.originalFilename }}
              </span>
              <span class="mt-1 block text-xs text-slate-600">
                {{ asset.kind }} · {{ asset.mimeType }}
              </span>
              <span v-if="asset.width && asset.height" class="mt-1 block text-xs text-slate-500">
                {{ asset.width }} × {{ asset.height }}
              </span>
            </span>
          </button>
        </div>
      </div>

      <footer class="flex flex-wrap items-center justify-between gap-3 border-t border-slate-200 px-6 py-4">
        <p class="text-sm text-slate-600" aria-live="polite">
          {{ totalPages === 0 ? '暂无分页' : `第 ${currentPage + 1} / ${totalPages} 页` }}
        </p>
        <nav class="flex gap-2" aria-label="媒体分页">
          <button
            class="rounded-lg border border-slate-300 px-3 py-2 text-sm font-semibold text-slate-700 hover:bg-slate-50 disabled:cursor-not-allowed disabled:opacity-45"
            type="button"
            data-action="previous"
            :disabled="!canGoPrevious"
            @click="previousPage"
          >
            上一页
          </button>
          <button
            class="rounded-lg border border-slate-300 px-3 py-2 text-sm font-semibold text-slate-700 hover:bg-slate-50 disabled:cursor-not-allowed disabled:opacity-45"
            type="button"
            data-action="next"
            :disabled="!canGoNext"
            @click="nextPage"
          >
            下一页
          </button>
        </nav>
      </footer>
    </section>
  </div>
</template>
