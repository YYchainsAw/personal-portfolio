<script setup lang="ts">
import {
  computed,
  nextTick,
  onBeforeUnmount,
  onMounted,
  ref,
  useId,
  watch,
} from 'vue'
import { onBeforeRouteLeave } from 'vue-router'

import {
  isValidMediaSourceUrl,
  mediaApi,
  type MediaListOptions,
  type UpdateMediaTranslationsRequest,
} from '@/api/mediaApi'
import AsyncPanel from '@/components/common/AsyncPanel.vue'
import { ApiProblem, type ApiProblemBody, type Page } from '@/types/api'
import {
  locales,
  type Locale,
  type MediaAssetView,
  type MediaKind,
  type MediaStatus,
  type MediaTranslationInput,
} from '@/types/content'

type LoadMedia = (options: Readonly<MediaListOptions>) => Promise<Page<MediaAssetView>>
type GetMedia = (id: string) => Promise<MediaAssetView>
type UploadMedia = (file: File) => Promise<MediaAssetView>
type UpdateTranslations = (
  id: string,
  request: Readonly<UpdateMediaTranslationsRequest>,
) => Promise<MediaAssetView>
type ArchiveMedia = (id: string) => Promise<void>
type PreviewUrl = (id: string, variant: string) => string

const props = withDefaults(
  defineProps<{
    load?: LoadMedia
    get?: GetMedia
    upload?: UploadMedia
    updateTranslations?: UpdateTranslations
    archive?: ArchiveMedia
    previewUrl?: PreviewUrl
  }>(),
  {
    load: (options: Readonly<MediaListOptions>) => mediaApi.list(options),
    get: (id: string) => mediaApi.get(id),
    upload: (file: File) => mediaApi.upload(file),
    updateTranslations: (id: string, request: Readonly<UpdateMediaTranslationsRequest>) =>
      mediaApi.updateTranslations(id, request),
    archive: (id: string) => mediaApi.archive(id),
    previewUrl: (id: string, variant: string) => mediaApi.previewUrl(id, variant),
  },
)

const PAGE_SIZE = 24
const POLL_INTERVAL_MS = 5_000
const IMAGE_LIMIT_BYTES = 25 * 1024 * 1024
const PDF_LIMIT_BYTES = 30 * 1024 * 1024
const ALLOWED_MIME_TYPES = new Set(['image/jpeg', 'image/png', 'application/pdf'])
const IMAGE_VARIANT = /^w[1-9][0-9]{0,9}$/

interface DisplayProblem {
  readonly title: string
  readonly traceId: string
  readonly code: string
  readonly status: number
  readonly fieldErrors?: Readonly<Record<string, string>>
}

interface PendingSave {
  readonly assetId: string
  readonly baseVersion: number
  readonly submitted: readonly MediaTranslationInput[]
}

interface PendingArchive {
  readonly assetId: string
  readonly baseVersion: number
  readonly baseStatus: MediaStatus
}

type TranslationField = 'altText' | 'caption' | 'credit' | 'sourceUrl'

const libraryTitle = ref<HTMLElement | null>(null)
const detailTitle = ref<HTMLElement | null>(null)
const fileInput = ref<HTMLInputElement | null>(null)
const items = ref<MediaAssetView[]>([])
const currentPage = ref(0)
const requestedPage = ref(0)
const totalItems = ref(0)
const totalPages = ref(0)
const statusFilter = ref<MediaStatus | ''>('')
const kindFilter = ref<MediaKind | ''>('')
const textFilter = ref('')
const loading = ref(false)
const listProblem = ref<DisplayProblem | null>(null)
const announcement = ref('')

const selectedFile = ref<File | null>(null)
const uploadBusy = ref(false)
const uploadProblem = ref<DisplayProblem | null>(null)
const uploadValidation = ref('')
const uploadUncertain = ref(false)
const uploadResult = ref<MediaAssetView | null>(null)

const selectedId = ref<string | null>(null)
const selectedAsset = ref<MediaAssetView | null>(null)
const detailLoading = ref(false)
const detailProblem = ref<DisplayProblem | null>(null)
const detailOpener = ref<HTMLElement | null>(null)
const translationDraft = ref<MediaTranslationInput[]>([])
const baseTranslations = ref<readonly MediaTranslationInput[]>(Object.freeze([]))
const baseVersion = ref(0)
const localFieldErrors = ref<Readonly<Record<string, string>>>(Object.freeze({}))

const saveBusy = ref(false)
const saveProblem = ref<DisplayProblem | null>(null)
const saveUncertain = ref(false)
const saveConflict = ref(false)
const conflictAsset = ref<MediaAssetView | null>(null)

const archiveBusy = ref(false)
const archiveProblem = ref<DisplayProblem | null>(null)
const archiveUncertain = ref(false)

const fieldPrefix = `media-copy-${useId()}`
let listGeneration = 0
let detailGeneration = 0
let saveGeneration = 0
let archiveGeneration = 0
let pollGeneration = 0
let pollTimer: number | null = null
let pollInFlight = false
let pollingProblemAssetId: string | null = null
const pollingAssetIds = new Map<string, string>()
const knownArchivedAssets = new Map<string, MediaAssetView>()
let pendingSave: PendingSave | null = null
let pendingArchive: PendingArchive | null = null
let mounted = false
let disposed = false

const statusOptions: readonly MediaStatus[] = Object.freeze([
  'PROCESSING',
  'READY',
  'FAILED',
  'ARCHIVED',
  'PENDING_DELETE',
])

const statusCopy: Readonly<Record<MediaStatus, string>> = Object.freeze({
  PROCESSING: '处理中',
  READY: '可用',
  FAILED: '处理失败',
  ARCHIVED: '已归档',
  PENDING_DELETE: '等待清理',
})

function toProblem(cause: unknown, fallback: string): DisplayProblem {
  if (cause instanceof ApiProblem) {
    return {
      title: cause.body.title,
      traceId: cause.body.traceId,
      code: cause.body.code,
      status: cause.body.status,
      fieldErrors: cause.body.fieldErrors,
    }
  }
  return { title: fallback, traceId: 'client', code: 'CLIENT_ERROR', status: 0 }
}

function isUncertainMutation(cause: unknown): boolean {
  if (!(cause instanceof ApiProblem)) return true
  return cause.body.status === 0 || cause.body.status >= 500
}

function sameId(left: string | null | undefined, right: string | null | undefined): boolean {
  return typeof left === 'string' && typeof right === 'string' && left.toLowerCase() === right.toLowerCase()
}

function kindOf(asset: MediaAssetView): MediaKind {
  if (asset.mimeType === 'application/pdf') return 'PDF'
  if (asset.mimeType.startsWith('image/')) return 'IMAGE'
  return 'FILE'
}

function formatBytes(bytes: number): string {
  if (bytes >= 1024 * 1024) return `${(bytes / (1024 * 1024)).toFixed(1)} MiB`
  if (bytes >= 1024) return `${(bytes / 1024).toFixed(1)} KiB`
  return `${bytes} B`
}

function normalizedTranslations(asset: MediaAssetView): MediaTranslationInput[] {
  return locales.map((locale) => {
    const existing = asset.translations.find((entry) => entry.locale === locale)
    return {
      locale,
      altText: existing?.altText ?? '',
      caption: existing?.caption ?? '',
      credit: existing?.credit ?? '',
      sourceUrl: existing?.sourceUrl ?? null,
    }
  })
}

function cloneTranslations(input: readonly MediaTranslationInput[]): MediaTranslationInput[] {
  return input.map((entry) => ({ ...entry }))
}

function sameTranslations(
  left: readonly MediaTranslationInput[],
  right: readonly MediaTranslationInput[],
): boolean {
  if (left.length !== right.length) return false
  return locales.every((locale) => {
    const a = left.find((entry) => entry.locale === locale)
    const b = right.find((entry) => entry.locale === locale)
    return (
      a !== undefined &&
      b !== undefined &&
      a.altText === b.altText &&
      a.caption === b.caption &&
      a.credit === b.credit &&
      a.sourceUrl === b.sourceUrl
    )
  })
}

function resetDraft(asset: MediaAssetView): void {
  const next = normalizedTranslations(asset)
  translationDraft.value = cloneTranslations(next)
  baseTranslations.value = Object.freeze(cloneTranslations(next))
  baseVersion.value = asset.version
  localFieldErrors.value = Object.freeze({})
  saveProblem.value = null
  saveConflict.value = false
  conflictAsset.value = null
}

const detailDirty = computed(
  () =>
    selectedAsset.value !== null &&
    !sameTranslations(translationDraft.value, baseTranslations.value),
)

const visibleItems = computed(() => {
  const query = textFilter.value.trim().toLocaleLowerCase()
  return items.value.filter((asset) => {
    if (!statusMatches(asset)) return false
    const kind = kindOf(asset)
    if (kindFilter.value !== '' && kind !== kindFilter.value) return false
    if (query.length === 0) return true
    return [asset.id, asset.originalFilename, asset.mimeType, asset.status, kind].some((value) =>
      value.toLocaleLowerCase().includes(query),
    )
  })
})

const filtersActive = computed(
  () => kindFilter.value !== '' || textFilter.value.trim().length > 0,
)

const canPrevious = computed(
  () => !loading.value && totalPages.value > 0 && currentPage.value > 0,
)
const canNext = computed(
  () => !loading.value && totalPages.value > 0 && currentPage.value + 1 < totalPages.value,
)

function statusMatches(asset: MediaAssetView): boolean {
  return statusFilter.value === '' || asset.status === statusFilter.value
}

function adjustTotalItems(delta: number): void {
  totalItems.value = Math.max(0, totalItems.value + delta)
  totalPages.value = totalItems.value === 0 ? 0 : Math.ceil(totalItems.value / PAGE_SIZE)
}

function upsertCurrentPage(asset: MediaAssetView, prepend = false): void {
  const index = items.value.findIndex((item) => sameId(item.id, asset.id))
  if (!statusMatches(asset)) {
    if (index >= 0) {
      items.value = items.value.filter((_item, itemIndex) => itemIndex !== index)
      adjustTotalItems(-1)
    }
    return
  }
  if (index >= 0) {
    const current = items.value[index]
    if (current !== undefined && current.version > asset.version) return
    const next = [...items.value]
    next[index] = asset
    items.value = next
    return
  }
  if (!prepend) return
  adjustTotalItems(1)
  if (currentPage.value === 0) items.value = [asset, ...items.value].slice(0, PAGE_SIZE)
}

function applyAsset(asset: MediaAssetView, options: { reset?: boolean; prepend?: boolean } = {}): void {
  if (asset.status === 'ARCHIVED') {
    const key = asset.id.toLowerCase()
    const known = knownArchivedAssets.get(key)
    if (known === undefined || known.version <= asset.version) knownArchivedAssets.set(key, asset)
  }
  upsertCurrentPage(asset, options.prepend)
  if (sameId(uploadResult.value?.id, asset.id) && (uploadResult.value?.version ?? -1) <= asset.version) {
    uploadResult.value = asset
  }
  if (
    sameId(selectedId.value, asset.id) &&
    (selectedAsset.value === null || selectedAsset.value.version <= asset.version)
  ) {
    selectedAsset.value = asset
    if (options.reset) resetDraft(asset)
  }
}

function freshestKnownAsset(candidate: MediaAssetView): MediaAssetView {
  let freshest = candidate
  for (const known of [
    items.value.find((item) => sameId(item.id, candidate.id)),
    sameId(selectedAsset.value?.id, candidate.id) ? selectedAsset.value : null,
    sameId(uploadResult.value?.id, candidate.id) ? uploadResult.value : null,
    knownArchivedAssets.get(candidate.id.toLowerCase()),
  ]) {
    if (
      known !== null &&
      known !== undefined &&
      (known.version > freshest.version ||
        (known.version === freshest.version &&
          known.status === 'ARCHIVED' &&
          freshest.status !== 'ARCHIVED'))
    ) {
      freshest = known
    }
  }
  return freshest
}

async function loadPage(page: number, fallbackAttempted = false): Promise<boolean> {
  if (!Number.isSafeInteger(page) || page < 0 || disposed) return false
  const operation = ++listGeneration
  requestedPage.value = page
  loading.value = true
  listProblem.value = null
  try {
    const result = await props.load({
      page,
      size: PAGE_SIZE,
      ...(statusFilter.value === '' ? {} : { status: statusFilter.value }),
    })
    if (disposed || operation !== listGeneration) return false
    const fallbackPage =
      result.totalPages === 0 && result.page > 0
        ? 0
        : result.totalPages > 0 && result.page >= result.totalPages
          ? result.totalPages - 1
          : null
    if (fallbackPage !== null) {
      if (fallbackAttempted) {
        listProblem.value = {
          title: '媒体分页结果仍然超出有效范围，请稍后重试。',
          traceId: 'client',
          code: 'MEDIA_PAGE_OUT_OF_RANGE',
          status: 0,
        }
        return false
      }
      // A concurrent archive/cleanup can shrink the catalog after the operator
      // requested a formerly valid page. Only issue a safe GET for the new last
      // page once; a repeated invalid response must stop instead of causing a
      // recursive request storm.
      return loadPage(fallbackPage, true)
    }
    items.value = result.items
      .map(freshestKnownAsset)
      .filter((asset) => statusMatches(asset))
    currentPage.value = result.page
    totalItems.value = result.totalItems
    totalPages.value = result.totalPages
    return true
  } catch (cause) {
    if (disposed || operation !== listGeneration) return false
    listProblem.value = toProblem(cause, '无法加载媒体资源')
    return false
  } finally {
    if (!disposed && operation === listGeneration) loading.value = false
  }
}

async function retryList(): Promise<void> {
  await loadPage(requestedPage.value)
}

function previousPage(): void {
  if (canPrevious.value) void loadPage(currentPage.value - 1)
}

function nextPage(): void {
  if (canNext.value) void loadPage(currentPage.value + 1)
}

function validateFile(file: File): string {
  if (!ALLOWED_MIME_TYPES.has(file.type)) {
    return '仅支持 JPEG、PNG 或 PDF 文件。'
  }
  if (file.size <= 0) return '文件不能为空。'
  if (Array.from(file.name).length > 255 || file.name.trim().length === 0) {
    return '文件名必须为 1–255 个字符。'
  }
  if (file.type === 'application/pdf' && file.size > PDF_LIMIT_BYTES) {
    return 'PDF 文件不能超过 30 MiB。'
  }
  if (file.type !== 'application/pdf' && file.size > IMAGE_LIMIT_BYTES) {
    return '图片文件不能超过 25 MiB。'
  }
  return ''
}

function onFileChange(event: Event): void {
  if (uploadBusy.value || uploadUncertain.value) return
  const input = event.target as HTMLInputElement
  const file = input.files?.[0] ?? null
  selectedFile.value = file
  uploadProblem.value = null
  uploadValidation.value = file === null ? '' : validateFile(file)
}

function clearPollTimer(): void {
  if (pollTimer !== null) window.clearTimeout(pollTimer)
  pollTimer = null
}

function stopAllPolling(): void {
  pollGeneration += 1
  pollingAssetIds.clear()
  pollInFlight = false
  clearPollTimer()
}

function stopPollingAsset(assetId: string): void {
  pollingAssetIds.delete(assetId.toLowerCase())
  if (sameId(pollingProblemAssetId, assetId)) {
    pollingProblemAssetId = null
    uploadProblem.value = null
  }
  if (pollingAssetIds.size === 0) clearPollTimer()
}

function nextPollingAssetId(): string | null {
  return pollingAssetIds.values().next().value ?? null
}

function schedulePoll(): void {
  clearPollTimer()
  if (
    disposed ||
    pollingAssetIds.size === 0 ||
    pollInFlight ||
    document.visibilityState !== 'visible'
  ) {
    return
  }
  const operation = pollGeneration
  pollTimer = window.setTimeout(() => {
    pollTimer = null
    void pollUploadedAsset(operation)
  }, POLL_INTERVAL_MS)
}

async function pollUploadedAsset(operation: number): Promise<void> {
  const assetId = nextPollingAssetId()
  if (
    disposed ||
    operation !== pollGeneration ||
    assetId === null ||
    pollInFlight ||
    document.visibilityState !== 'visible'
  ) {
    return
  }
  pollInFlight = true
  try {
    const asset = await props.get(assetId)
    const key = assetId.toLowerCase()
    if (disposed || operation !== pollGeneration || !pollingAssetIds.has(key)) return
    if (sameId(pollingProblemAssetId, assetId)) {
      pollingProblemAssetId = null
      uploadProblem.value = null
    }
    applyAsset(asset, { prepend: asset.status !== 'PROCESSING' })
    if (asset.status !== 'PROCESSING') {
      pollingAssetIds.delete(key)
      announcement.value =
        asset.status === 'READY'
          ? `${asset.originalFilename} 已处理完成，可以使用。`
          : `${asset.originalFilename} 已停止处理，当前状态为 ${statusCopy[asset.status]}。`
    } else {
      // Move the current resource to the back so consecutive uploads are polled
      // fairly without overlapping requests or polling any resource too quickly.
      pollingAssetIds.delete(key)
      pollingAssetIds.set(key, assetId)
    }
  } catch (cause) {
    if (disposed || operation !== pollGeneration) return
    const key = assetId.toLowerCase()
    if (pollingAssetIds.has(key)) {
      pollingAssetIds.delete(key)
      pollingAssetIds.set(key, assetId)
    }
    pollingProblemAssetId = assetId
    uploadProblem.value = toProblem(cause, '暂时无法确认媒体处理状态')
  } finally {
    if (disposed || operation !== pollGeneration) return
    pollInFlight = false
    if (pollingAssetIds.size > 0) schedulePoll()
  }
}

function beginPolling(asset: MediaAssetView): void {
  if (asset.status !== 'PROCESSING') return
  pollingAssetIds.set(asset.id.toLowerCase(), asset.id)
  schedulePoll()
}

async function submitUpload(): Promise<void> {
  const file = selectedFile.value
  if (file === null || uploadBusy.value || uploadUncertain.value) return
  uploadValidation.value = validateFile(file)
  if (uploadValidation.value.length > 0) return

  uploadBusy.value = true
  uploadProblem.value = null
  pollingProblemAssetId = null
  try {
    const asset = await props.upload(file)
    if (disposed) return
    // A list request started before the successful upload cannot prove that its
    // snapshot contains this new asset. Retire it before applying the mutation
    // result so a late response cannot erase the PROCESSING card.
    listGeneration += 1
    loading.value = false
    uploadResult.value = asset
    applyAsset(asset, { prepend: true })
    selectedFile.value = null
    if (fileInput.value !== null) fileInput.value.value = ''
    announcement.value = `${asset.originalFilename} 已上传，当前状态为 ${statusCopy[asset.status]}。`
    beginPolling(asset)
  } catch (cause) {
    if (disposed) return
    pollingProblemAssetId = null
    uploadProblem.value = toProblem(cause, '无法上传媒体文件')
    uploadUncertain.value = isUncertainMutation(cause)
  } finally {
    if (!disposed) uploadBusy.value = false
  }
}

async function refreshAfterUncertainUpload(): Promise<void> {
  if (!uploadUncertain.value || loading.value) return
  await loadPage(currentPage.value)
}

function mayReplaceDetail(): boolean {
  if (saveBusy.value || archiveBusy.value || saveUncertain.value || archiveUncertain.value) return false
  return !detailDirty.value || window.confirm('当前双语说明尚未保存。确定放弃修改吗？')
}

async function focusDetail(): Promise<void> {
  await nextTick()
  detailTitle.value?.focus()
}

async function openAsset(asset: MediaAssetView, event: Event): Promise<void> {
  if (!mayReplaceDetail()) return
  detailGeneration += 1
  const operation = detailGeneration
  selectedId.value = asset.id
  selectedAsset.value = asset
  detailOpener.value = event.currentTarget instanceof HTMLElement ? event.currentTarget : null
  resetDraft(asset)
  detailLoading.value = asset.status !== 'PENDING_DELETE'
  detailProblem.value = null
  saveUncertain.value = false
  archiveUncertain.value = false
  archiveProblem.value = null
  pendingSave = null
  pendingArchive = null
  void focusDetail()
  if (asset.status === 'PENDING_DELETE') return

  try {
    const result = await props.get(asset.id)
    if (disposed || operation !== detailGeneration || !sameId(selectedId.value, asset.id)) return
    const current = selectedAsset.value
    if (current !== null && sameId(current.id, result.id) && current.version > result.version) return
    applyAsset(result, { reset: true })
  } catch (cause) {
    if (disposed || operation !== detailGeneration) return
    detailProblem.value = toProblem(cause, '无法加载媒体详情')
  } finally {
    if (!disposed && operation === detailGeneration) detailLoading.value = false
  }
}

async function closeDetail(force = false): Promise<void> {
  if (!force && !mayReplaceDetail()) return
  detailGeneration += 1
  selectedId.value = null
  selectedAsset.value = null
  translationDraft.value = []
  baseTranslations.value = Object.freeze([])
  detailLoading.value = false
  detailProblem.value = null
  archiveProblem.value = null
  const restore = detailOpener.value
  detailOpener.value = null
  await nextTick()
  if (restore?.isConnected) restore.focus()
  else libraryTitle.value?.focus()
}

function updateTranslation(locale: Locale, field: TranslationField, event: Event): void {
  if (
    detailLoading.value ||
    saveBusy.value ||
    saveUncertain.value ||
    archiveBusy.value ||
    archiveUncertain.value
  ) return
  const value = (event.target as HTMLInputElement | HTMLTextAreaElement).value
  translationDraft.value = translationDraft.value.map((entry) =>
    entry.locale === locale
      ? {
          ...entry,
          [field]: field === 'sourceUrl' && value.length === 0 ? null : value,
        }
      : { ...entry },
  )
  localFieldErrors.value = Object.freeze({
    ...localFieldErrors.value,
    [`${locale}.${field}`]: '',
  })
  saveProblem.value = null
  saveConflict.value = false
  conflictAsset.value = null
}

function validateTranslations(): boolean {
  const errors: Record<string, string> = {}
  for (const entry of translationDraft.value) {
    if (entry.altText.length > 500) errors[`${entry.locale}.altText`] = '替代文字不能超过 500 个字符。'
    if (entry.caption.length > 1000) errors[`${entry.locale}.caption`] = '说明不能超过 1000 个字符。'
    if (entry.credit.length > 300) errors[`${entry.locale}.credit`] = '署名不能超过 300 个字符。'
    const source = entry.sourceUrl ?? ''
    if (!isValidMediaSourceUrl(source)) {
      errors[`${entry.locale}.sourceUrl`] = '来源地址必须为空或使用不含凭据、片段的 HTTPS 地址。'
    }
  }
  localFieldErrors.value = Object.freeze(errors)
  return Object.keys(errors).length === 0
}

function fieldError(locale: Locale, field: TranslationField): string | undefined {
  const local = localFieldErrors.value[`${locale}.${field}`]
  if (local) return local
  const server = saveProblem.value?.fieldErrors
  const index = locale === 'zh-CN' ? 0 : 1
  for (const key of [
    `translations[${index}].${field}`,
    `translations.${index}.${field}`,
    `${index}.${field}`,
    `[${index}].${field}`,
    `input[${index}].${field}`,
    `input.${index}.${field}`,
    `${locale}.${field}`,
  ]) {
    if (server?.[key]) return server[key]
  }
  return undefined
}

function fieldId(locale: Locale, field: TranslationField): string {
  return `${fieldPrefix}-${locale}-${field}`
}

function fieldErrorId(locale: Locale, field: TranslationField): string {
  return `${fieldId(locale, field)}-error`
}

async function saveTranslations(): Promise<void> {
  const asset = selectedAsset.value
  if (
    asset === null ||
    detailLoading.value ||
    saveBusy.value ||
    saveUncertain.value ||
    archiveBusy.value ||
    archiveUncertain.value ||
    asset.status === 'PENDING_DELETE' ||
    !detailDirty.value ||
    !validateTranslations()
  ) {
    return
  }

  const operation = ++saveGeneration
  const targetId = asset.id
  const submitted = Object.freeze(cloneTranslations(translationDraft.value))
  const capturedBase = Object.freeze(cloneTranslations(baseTranslations.value))
  const capturedVersion = baseVersion.value
  saveBusy.value = true
  saveProblem.value = null
  saveConflict.value = false
  conflictAsset.value = null

  try {
    const latest = await props.get(targetId)
    if (disposed || operation !== saveGeneration || !sameId(selectedId.value, targetId)) return
    if (
      latest.version !== capturedVersion ||
      !sameTranslations(normalizedTranslations(latest), capturedBase)
    ) {
      saveConflict.value = true
      conflictAsset.value = latest
      saveProblem.value = {
        title: '服务器上的双语说明已发生变化。请先加载服务器版本，再决定如何修改。',
        traceId: 'client',
        code: 'MEDIA_TRANSLATIONS_STALE',
        status: 409,
      }
      return
    }

    pendingSave = Object.freeze({
      assetId: targetId,
      baseVersion: capturedVersion,
      submitted,
    })
    const updated = await props.updateTranslations(targetId, {
      expectedVersion: capturedVersion,
      translations: submitted,
    })
    if (disposed || operation !== saveGeneration || !sameId(selectedId.value, targetId)) return
    if (
      updated.version !== capturedVersion + 1 ||
      !sameTranslations(normalizedTranslations(updated), submitted)
    ) {
      saveUncertain.value = true
      saveProblem.value = {
        title: '服务器响应尚未证明双语说明已按本次内容保存；不会自动再次提交。',
        traceId: 'client',
        code: 'MEDIA_TRANSLATIONS_UNPROVEN',
        status: 0,
      }
      return
    }
    pendingSave = null
    saveUncertain.value = false
    applyAsset(updated, { reset: true })
    announcement.value = `${updated.originalFilename} 的双语说明已保存。`
  } catch (cause) {
    if (disposed || operation !== saveGeneration || !sameId(selectedId.value, targetId)) return
    const problem = toProblem(cause, '无法保存双语说明')
    saveProblem.value = problem
    if (problem.status === 409 && problem.code === 'MEDIA_VERSION_CONFLICT') {
      pendingSave = null
      saveUncertain.value = false
      saveConflict.value = true
      conflictAsset.value = null
      try {
        const latest = await props.get(targetId)
        if (disposed || operation !== saveGeneration || !sameId(selectedId.value, targetId)) return
        conflictAsset.value = latest
      } catch (reloadCause) {
        if (disposed || operation !== saveGeneration || !sameId(selectedId.value, targetId)) return
        saveProblem.value = toProblem(
          reloadCause,
          '服务器已拒绝旧版本，但暂时无法加载最新双语说明。',
        )
      }
      return
    }
    saveUncertain.value = pendingSave !== null && isUncertainMutation(cause)
    if (!saveUncertain.value) pendingSave = null
  } finally {
    if (!disposed && operation === saveGeneration) saveBusy.value = false
  }
}

async function reconcileSave(): Promise<void> {
  const pending = pendingSave
  if (pending === null || !saveUncertain.value || saveBusy.value) return
  const operation = ++saveGeneration
  saveBusy.value = true
  saveProblem.value = null
  try {
    const current = await props.get(pending.assetId)
    if (disposed || operation !== saveGeneration || !sameId(selectedId.value, pending.assetId)) return
    const currentCopy = normalizedTranslations(current)
    if (current.version > pending.baseVersion && sameTranslations(currentCopy, pending.submitted)) {
      pendingSave = null
      saveUncertain.value = false
      applyAsset(current, { reset: true })
      announcement.value = `${current.originalFilename} 的双语说明已确认保存。`
      return
    }
    if (current.version > pending.baseVersion) {
      saveConflict.value = true
      conflictAsset.value = current
      saveProblem.value = {
        title: '服务器已有不同的更新；不会自动覆盖。请加载服务器版本。',
        traceId: 'client',
        code: 'MEDIA_TRANSLATIONS_DIVERGED',
        status: 409,
      }
      return
    }
    saveProblem.value = {
      title: '服务器仍未证明上次保存已完成；请仅再次检查状态。',
      traceId: 'client',
      code: 'MEDIA_TRANSLATIONS_UNPROVEN',
      status: 0,
    }
  } catch (cause) {
    if (!disposed && operation === saveGeneration) {
      saveProblem.value = toProblem(cause, '仍无法确认双语说明是否保存')
    }
  } finally {
    if (!disposed && operation === saveGeneration) saveBusy.value = false
  }
}

async function loadConflictVersion(): Promise<void> {
  const targetId = selectedId.value
  if (!saveConflict.value || targetId === null || saveBusy.value) return
  const operation = ++saveGeneration
  saveBusy.value = true
  try {
    const current = conflictAsset.value ?? (await props.get(targetId))
    if (disposed || operation !== saveGeneration || !sameId(selectedId.value, targetId)) return
    if (!sameId(current.id, targetId)) {
      saveProblem.value = {
        title: '服务器返回的媒体版本与当前资源不一致。',
        traceId: 'client',
        code: 'MEDIA_CONFLICT_ASSET_MISMATCH',
        status: 0,
      }
      return
    }
    pendingSave = null
    saveUncertain.value = false
    saveConflict.value = false
    conflictAsset.value = null
    applyAsset(current, { reset: true })
    announcement.value = '已加载服务器上的双语说明。'
  } catch (cause) {
    if (!disposed && operation === saveGeneration) {
      saveProblem.value = toProblem(cause, '暂时无法加载服务器上的最新双语说明。')
    }
  } finally {
    if (!disposed && operation === saveGeneration) saveBusy.value = false
  }
}

function resetLocalDraft(): void {
  const asset = selectedAsset.value
  if (
    asset === null ||
    detailLoading.value ||
    !detailDirty.value ||
    saveBusy.value ||
    saveUncertain.value ||
    archiveBusy.value ||
    archiveUncertain.value
  ) {
    return
  }
  if (!window.confirm('撤销尚未保存的双语说明修改？')) return
  resetDraft(asset)
  announcement.value = '已撤销尚未保存的双语说明修改。'
}

const canArchive = computed(
  () =>
    !detailLoading.value &&
    (selectedAsset.value?.status === 'READY' || selectedAsset.value?.status === 'FAILED'),
)

function removeArchivedAsset(assetId: string): void {
  const hadItem = items.value.some((item) => sameId(item.id, assetId))
  items.value = items.value.filter((item) => !sameId(item.id, assetId))
  if (hadItem) {
    totalItems.value = Math.max(0, totalItems.value - 1)
    totalPages.value = totalItems.value === 0 ? 0 : Math.ceil(totalItems.value / PAGE_SIZE)
  }
  if (sameId(uploadResult.value?.id, assetId)) uploadResult.value = null
}

async function finishArchive(asset: MediaAssetView): Promise<void> {
  knownArchivedAssets.set(asset.id.toLowerCase(), asset)
  stopPollingAsset(asset.id)
  const removeFromCurrentStatus =
    statusFilter.value !== '' && statusFilter.value !== 'ARCHIVED'
  if (removeFromCurrentStatus) removeArchivedAsset(asset.id)
  else if (statusFilter.value === 'ARCHIVED') applyAsset(asset, { prepend: true })
  else applyAsset(asset)
  if (sameId(uploadResult.value?.id, asset.id)) uploadResult.value = null
  pendingArchive = null
  archiveUncertain.value = false
  archiveProblem.value = null
  announcement.value = `${asset.originalFilename} 已归档，不再出现在可选媒体中；文件不会立即物理删除。`
  // A status-filtered page either gains or loses catalog membership locally.
  // The unfiltered catalog keeps the same total, so reload it instead of
  // prepending a resource that may belong to another page and double-counting.
  const reconcilePage =
    removeFromCurrentStatus || statusFilter.value === '' ? currentPage.value : null
  await closeDetail(true)
  // Refill the page after any filtered archive, not only after an empty last
  // page: removing one card can shift the first asset from the next page here.
  if (reconcilePage !== null) await loadPage(reconcilePage)
}

async function archiveSelected(): Promise<void> {
  const asset = selectedAsset.value
  if (
    asset === null ||
    detailLoading.value ||
    !canArchive.value ||
    archiveBusy.value ||
    archiveUncertain.value ||
    saveBusy.value ||
    saveUncertain.value ||
    detailDirty.value
  ) {
    return
  }
  if (!window.confirm('确认归档这个媒体资源？归档后它将不再可选，但不会立即物理删除。')) return

  const operation = ++archiveGeneration
  const targetId = asset.id
  pendingArchive = Object.freeze({
    assetId: targetId,
    baseVersion: asset.version,
    baseStatus: asset.status,
  })
  archiveBusy.value = true
  archiveProblem.value = null
  try {
    await props.archive(targetId)
    if (disposed || operation !== archiveGeneration || !sameId(selectedId.value, targetId)) return
    await finishArchive({ ...asset, status: 'ARCHIVED', version: asset.version + 1 })
  } catch (cause) {
    if (disposed || operation !== archiveGeneration || !sameId(selectedId.value, targetId)) return
    archiveProblem.value = toProblem(cause, '无法归档媒体资源')
    archiveUncertain.value = isUncertainMutation(cause)
    if (!archiveUncertain.value) pendingArchive = null
  } finally {
    if (!disposed && operation === archiveGeneration) archiveBusy.value = false
  }
}

async function reconcileArchive(): Promise<void> {
  const pending = pendingArchive
  if (pending === null || !archiveUncertain.value || archiveBusy.value) return
  const operation = ++archiveGeneration
  archiveBusy.value = true
  archiveProblem.value = null
  try {
    const current = await props.get(pending.assetId)
    if (disposed || operation !== archiveGeneration || !sameId(selectedId.value, pending.assetId)) return
    if (current.status === 'ARCHIVED' && current.version > pending.baseVersion) {
      await finishArchive(current)
      return
    }
    archiveProblem.value = {
      title:
        current.version === pending.baseVersion && current.status === pending.baseStatus
          ? '服务器仍未证明归档已完成；请仅再次检查状态。'
          : '服务器状态已发生其他变化；不会自动再次归档。',
      traceId: 'client',
      code: 'MEDIA_ARCHIVE_UNPROVEN',
      status: 0,
    }
  } catch (cause) {
    if (!disposed && operation === archiveGeneration) {
      archiveProblem.value = toProblem(cause, '仍无法确认媒体是否归档')
    }
  } finally {
    if (!disposed && operation === archiveGeneration) archiveBusy.value = false
  }
}

function safeVariantUrl(asset: MediaAssetView, variantName: string): string | null {
  if (
    asset.status === 'PENDING_DELETE' ||
    !asset.variants.some((variant) => variant.name === variantName && variant.status === 'READY')
  ) {
    return null
  }
  try {
    const result = props.previewUrl(asset.id, variantName)
    const expected = `/api/admin/media/${encodeURIComponent(asset.id)}/preview/${encodeURIComponent(variantName)}`
    return result === expected ? result : null
  } catch {
    return null
  }
}

function readyImageVariants(asset: MediaAssetView): Array<{ name: string; width: number }> {
  if (kindOf(asset) !== 'IMAGE') return []
  return asset.variants
    .filter(
      (variant): variant is typeof variant & { width: number } =>
        variant.status === 'READY' &&
        IMAGE_VARIANT.test(variant.name) &&
        typeof variant.width === 'number' &&
        variant.name === `w${variant.width}`,
    )
    .map((variant) => ({ name: variant.name, width: variant.width }))
}

function thumbnailUrl(asset: MediaAssetView): string | null {
  const variant = readyImageVariants(asset).sort((a, b) => a.width - b.width)[0]
  return variant === undefined ? null : safeVariantUrl(asset, variant.name)
}

const detailImageUrl = computed(() => {
  const asset = selectedAsset.value
  if (asset === null) return null
  const variant = readyImageVariants(asset).sort((a, b) => b.width - a.width)[0]
  return variant === undefined ? null : safeVariantUrl(asset, variant.name)
})

const detailPdfUrl = computed(() => {
  const asset = selectedAsset.value
  return asset !== null && kindOf(asset) === 'PDF'
    ? safeVariantUrl(asset, 'document')
    : null
})

function translationComplete(asset: MediaAssetView, locale: Locale): boolean {
  const entry = asset.translations.find((candidate) => candidate.locale === locale)
  return (entry?.altText.trim().length ?? 0) > 0
}

function onVisibilityChange(): void {
  if (document.visibilityState === 'visible') schedulePoll()
  else clearPollTimer()
}

const hasExitRisk = computed(
  () =>
    detailDirty.value ||
    uploadBusy.value ||
    uploadUncertain.value ||
    saveBusy.value ||
    saveUncertain.value ||
    archiveBusy.value ||
    archiveUncertain.value,
)

function confirmRouteLeave(): boolean {
  if (!hasExitRisk.value) return true
  return window.confirm('当前媒体操作或双语说明尚未确认完成。确定离开吗？')
}

function onBeforeUnload(event: BeforeUnloadEvent): void {
  if (!hasExitRisk.value) return
  event.preventDefault()
  event.returnValue = ''
}

watch(statusFilter, () => {
  if (mounted) void loadPage(0)
})

onBeforeRouteLeave(confirmRouteLeave)

onMounted(() => {
  mounted = true
  document.addEventListener('visibilitychange', onVisibilityChange)
  window.addEventListener('beforeunload', onBeforeUnload)
  void loadPage(0)
})

onBeforeUnmount(() => {
  disposed = true
  mounted = false
  listGeneration += 1
  detailGeneration += 1
  saveGeneration += 1
  archiveGeneration += 1
  stopAllPolling()
  document.removeEventListener('visibilitychange', onVisibilityChange)
  window.removeEventListener('beforeunload', onBeforeUnload)
})
</script>

<template>
  <section class="space-y-6" aria-labelledby="media-library-title">
    <header class="flex flex-col gap-3 sm:flex-row sm:items-end sm:justify-between">
      <div>
        <p class="text-xs font-semibold tracking-[0.18em] text-blue-700">MEDIA LIBRARY</p>
        <h1
          id="media-library-title"
          ref="libraryTitle"
          class="mt-2 rounded text-3xl font-semibold text-slate-950 focus:outline-none focus:ring-2 focus:ring-blue-500"
          tabindex="-1"
        >
          媒体库
        </h1>
        <p class="mt-2 max-w-3xl text-sm leading-6 text-slate-600">
          上传作品图片与 PDF，跟踪处理状态，并维护中文和英文的无障碍说明与来源信息。
        </p>
      </div>
      <span class="w-fit rounded-full bg-blue-50 px-3 py-1.5 text-xs font-semibold text-blue-800">
        当前共 {{ totalItems }} 项
      </span>
    </header>

    <p class="sr-only" role="status" aria-live="polite" aria-atomic="true">
      {{ announcement }}
    </p>

    <section
      class="rounded-2xl border border-blue-100 bg-white p-5 shadow-sm sm:p-6"
      aria-labelledby="media-upload-title"
    >
      <div class="flex flex-col gap-5 lg:flex-row lg:items-end lg:justify-between">
        <div class="max-w-2xl">
          <h2 id="media-upload-title" class="text-xl font-semibold text-slate-950">上传媒体</h2>
          <p id="media-upload-help" class="mt-2 text-sm leading-6 text-slate-600">
            仅支持 JPEG、PNG（每张不超过 25 MiB）和 PDF（不超过 30 MiB）。文件签名与实际大小仍由服务器最终校验。
          </p>
        </div>
        <div class="flex min-w-0 flex-1 flex-col gap-3 lg:max-w-xl">
          <input
            ref="fileInput"
            class="block w-full rounded-xl border border-slate-300 bg-slate-50 px-3 py-2.5 text-sm text-slate-800 file:mr-3 file:rounded-lg file:border-0 file:bg-blue-700 file:px-3 file:py-2 file:font-semibold file:text-white"
            type="file"
            accept=".jpg,.jpeg,.png,.pdf,image/jpeg,image/png,application/pdf"
            aria-label="选择要上传的 JPEG、PNG 或 PDF 文件"
            aria-describedby="media-upload-help"
            :aria-invalid="uploadValidation ? 'true' : undefined"
            :disabled="uploadBusy || uploadUncertain"
            @change="onFileChange"
          />
          <button
            class="w-fit rounded-xl bg-blue-700 px-4 py-2.5 text-sm font-semibold text-white hover:bg-blue-800 disabled:cursor-not-allowed disabled:opacity-55"
            type="button"
            data-action="upload"
            :disabled="selectedFile === null || uploadBusy || uploadUncertain || uploadValidation.length > 0"
            :aria-busy="uploadBusy"
            @click="submitUpload"
          >
            {{ uploadBusy ? '正在上传…' : '开始上传' }}
          </button>
        </div>
      </div>

      <p v-if="uploadValidation" class="mt-4 text-sm font-medium text-red-700" role="alert" data-upload-error>
        {{ uploadValidation }}
      </p>
      <div
        v-if="uploadProblem"
        class="mt-4 rounded-xl border border-red-200 bg-red-50 p-4 text-sm text-red-900"
        role="alert"
        data-upload-problem
      >
        <p class="font-semibold">{{ uploadProblem.title }}</p>
        <p class="mt-1 text-xs">请求编号：{{ uploadProblem.traceId }}</p>
      </div>
      <div
        v-if="uploadUncertain"
        class="mt-4 rounded-xl border border-amber-300 bg-amber-50 p-4 text-sm text-amber-950"
        data-upload-uncertain
      >
        <p class="font-semibold">上传结果未知，不会自动重传。</p>
        <p class="mt-1 leading-6">请先刷新列表并查找同名文件，避免生成重复资源。</p>
        <button
          class="mt-3 rounded-lg border border-amber-300 bg-white px-3 py-2 font-semibold disabled:opacity-60"
          type="button"
          data-action="refresh-after-upload"
          :disabled="loading"
          @click="refreshAfterUncertainUpload"
        >仅刷新媒体列表</button>
      </div>
      <article
        v-if="uploadResult"
        class="mt-4 flex flex-wrap items-center justify-between gap-3 rounded-xl border border-blue-200 bg-blue-50 p-4"
        data-upload-result
        :data-status="uploadResult.status"
      >
        <div class="min-w-0">
          <p class="truncate text-sm font-semibold text-slate-950">{{ uploadResult.originalFilename }}</p>
          <p class="mt-1 text-xs text-slate-600">上传任务已建立 · 版本 {{ uploadResult.version }}</p>
        </div>
        <span class="rounded-full bg-white px-3 py-1 text-xs font-semibold text-blue-800">
          {{ statusCopy[uploadResult.status] }} · {{ uploadResult.status }}
        </span>
      </article>
    </section>

    <section class="rounded-2xl border border-slate-200 bg-white p-5 shadow-sm" aria-labelledby="media-filter-title">
      <h2 id="media-filter-title" class="text-lg font-semibold text-slate-950">浏览与筛选</h2>
      <p class="mt-1 text-sm text-slate-500">状态在服务器端分页；类型和文字只筛选当前已加载页面。</p>
      <div class="mt-4 grid gap-4 md:grid-cols-3">
        <label class="text-sm font-semibold text-slate-800">
          处理状态
          <select
            v-model="statusFilter"
            class="mt-2 w-full rounded-xl border border-slate-300 bg-white px-3 py-2.5 font-normal"
            data-filter="status"
          >
            <option value="">全部状态</option>
            <option v-for="status in statusOptions" :key="status" :value="status">
              {{ statusCopy[status] }} · {{ status }}
            </option>
          </select>
        </label>
        <label class="text-sm font-semibold text-slate-800">
          当前页类型
          <select
            v-model="kindFilter"
            class="mt-2 w-full rounded-xl border border-slate-300 bg-white px-3 py-2.5 font-normal"
            data-filter="kind"
          >
            <option value="">全部类型</option>
            <option value="IMAGE">图片</option>
            <option value="PDF">PDF</option>
          </select>
        </label>
        <label class="text-sm font-semibold text-slate-800">
          搜索当前页
          <input
            v-model="textFilter"
            class="mt-2 w-full rounded-xl border border-slate-300 px-3 py-2.5 font-normal"
            type="search"
            autocomplete="off"
            data-filter="text"
            placeholder="文件名、ID、MIME 或状态"
          />
        </label>
      </div>
    </section>

    <div v-if="loading && items.length === 0" data-state="library-loading">
      <AsyncPanel :loading="true" />
    </div>
    <div v-else-if="listProblem && items.length === 0" data-state="library-error">
      <AsyncPanel
        :error-title="listProblem.title"
        :trace-id="listProblem.traceId"
        :on-retry="retryList"
      />
    </div>
    <AsyncPanel
      v-else
      :loading="false"
      :empty="!loading && listProblem === null && totalItems === 0"
    >
      <template #empty>
        <div data-state="library-empty">
          <p class="font-semibold text-slate-800">当前状态下还没有媒体资源</p>
          <p class="mt-1">上传第一个文件后，它会显示在这里。</p>
        </div>
      </template>

      <div class="space-y-4">
        <p
          v-if="loading"
          class="rounded-xl bg-blue-50 px-4 py-3 text-sm font-medium text-blue-800"
          role="status"
          aria-live="polite"
        >正在加载新的媒体页面…</p>
        <div
          v-if="listProblem"
          class="rounded-xl border border-amber-300 bg-amber-50 p-4 text-sm text-amber-950"
          role="alert"
        >
          <p class="font-semibold">{{ listProblem.title }}</p>
          <p class="mt-1 text-xs">请求编号：{{ listProblem.traceId }}</p>
          <button
            class="mt-3 rounded-lg border border-amber-300 bg-white px-3 py-2 font-semibold"
            type="button"
            data-action="retry-library-load"
            @click="retryList"
          >重试当前页</button>
        </div>

        <p class="text-sm text-slate-600" role="status" aria-live="polite">
          当前页显示 {{ visibleItems.length }} / {{ items.length }} 项
        </p>

        <div
          v-if="!loading && items.length > 0 && visibleItems.length === 0"
          class="rounded-2xl border border-dashed border-slate-300 bg-white p-8 text-center"
          data-state="filtered-empty"
          role="status"
        >
          <p class="font-semibold text-slate-800">当前页没有匹配资源</p>
          <p class="mt-1 text-sm text-slate-500">请调整当前页类型或搜索文字，也可以浏览其他页面。</p>
        </div>

        <ul
          v-else-if="visibleItems.length > 0"
          class="grid list-none gap-4 p-0 sm:grid-cols-2 xl:grid-cols-3"
          aria-label="媒体资源列表"
        >
          <li
            v-for="asset in visibleItems"
            :key="asset.id"
            class="overflow-hidden rounded-2xl border border-slate-200 bg-white shadow-sm"
            :data-media-id="asset.id"
            :data-media-kind="kindOf(asset)"
            :data-status="asset.status"
          >
            <div class="grid aspect-video place-items-center overflow-hidden bg-slate-100">
              <img
                v-if="thumbnailUrl(asset)"
                class="h-full w-full object-cover"
                :src="thumbnailUrl(asset)!"
                alt=""
              />
              <span v-else class="text-sm font-semibold text-slate-500" aria-hidden="true">
                {{ kindOf(asset) }}
              </span>
            </div>
            <div class="space-y-3 p-4">
              <div class="flex flex-wrap items-center justify-between gap-2">
                <span class="rounded-full bg-blue-50 px-2.5 py-1 text-xs font-semibold text-blue-800">
                  {{ statusCopy[asset.status] }} · {{ asset.status }}
                </span>
                <span class="text-xs font-medium text-slate-500">{{ formatBytes(asset.byteSize) }}</span>
              </div>
              <div>
                <h3 class="truncate font-semibold text-slate-950" :title="asset.originalFilename">
                  {{ asset.originalFilename }}
                </h3>
                <p class="mt-1 truncate text-xs text-slate-500">{{ asset.mimeType }}</p>
                <p v-if="asset.width && asset.height" class="mt-1 text-xs text-slate-500">
                  {{ asset.width }} × {{ asset.height }}
                </p>
              </div>
              <p class="text-xs text-slate-600">
                Alt：中文 {{ translationComplete(asset, 'zh-CN') ? '完整' : '缺失' }} · English {{ translationComplete(asset, 'en') ? '完整' : '缺失' }}
              </p>
              <button
                class="w-full rounded-xl border border-blue-300 bg-blue-50 px-3 py-2.5 text-sm font-semibold text-blue-800 hover:bg-blue-100 disabled:opacity-60"
                type="button"
                data-action="open-media"
                :data-media-id="asset.id"
                :disabled="saveBusy || archiveBusy || saveUncertain || archiveUncertain"
                :aria-label="`查看媒体详情：${asset.originalFilename}`"
                @click="openAsset(asset, $event)"
              >查看与编辑</button>
            </div>
          </li>
        </ul>

        <nav class="flex flex-wrap items-center justify-between gap-3" aria-label="媒体分页">
          <p class="text-sm text-slate-600">
            {{ totalPages === 0 ? '暂无分页' : `第 ${currentPage + 1} / ${totalPages} 页` }}
          </p>
          <div class="flex gap-2">
            <button
              class="rounded-lg border border-slate-300 bg-white px-3 py-2 text-sm font-semibold disabled:opacity-45"
              type="button"
              data-action="previous-page"
              :disabled="!canPrevious"
              @click="previousPage"
            >上一页</button>
            <button
              class="rounded-lg border border-slate-300 bg-white px-3 py-2 text-sm font-semibold disabled:opacity-45"
              type="button"
              data-action="next-page"
              :disabled="!canNext"
              @click="nextPage"
            >下一页</button>
          </div>
        </nav>
      </div>
    </AsyncPanel>

    <section
      v-if="selectedAsset"
      class="rounded-2xl border border-blue-200 bg-white p-5 shadow-sm sm:p-6"
      data-media-detail
      :data-media-id="selectedAsset.id"
      aria-labelledby="media-detail-title"
      :aria-busy="detailLoading"
    >
      <div class="flex flex-col gap-4 sm:flex-row sm:items-start sm:justify-between">
        <div class="min-w-0">
          <p class="text-xs font-semibold tracking-[0.16em] text-blue-700">MEDIA DETAIL</p>
          <h2
            id="media-detail-title"
            ref="detailTitle"
            class="mt-2 rounded break-words text-2xl font-semibold text-slate-950 focus:outline-none focus:ring-2 focus:ring-blue-500"
            data-detail-title
            tabindex="-1"
          >{{ selectedAsset.originalFilename }}</h2>
          <p class="mt-2 break-all text-xs font-mono text-slate-500">{{ selectedAsset.id }}</p>
        </div>
        <button
          class="rounded-lg border border-slate-300 bg-white px-3 py-2 text-sm font-semibold text-slate-700 disabled:opacity-50"
          type="button"
          data-action="close-detail"
          :disabled="saveBusy || archiveBusy || saveUncertain || archiveUncertain"
          @click="closeDetail()"
        >关闭详情</button>
      </div>

      <div v-if="detailLoading" class="mt-5 rounded-xl bg-slate-50 p-4 text-sm text-slate-600" role="status">
        正在加载最新媒体详情…
      </div>
      <div v-if="detailProblem" class="mt-5 rounded-xl border border-red-200 bg-red-50 p-4 text-sm text-red-900" role="alert">
        <p class="font-semibold">{{ detailProblem.title }}</p>
        <p class="mt-1 text-xs">请求编号：{{ detailProblem.traceId }}</p>
      </div>

      <div class="mt-6 grid gap-6 xl:grid-cols-[minmax(0,0.9fr)_minmax(0,1.4fr)]">
        <div class="space-y-4">
          <div class="overflow-hidden rounded-2xl border border-slate-200 bg-slate-100">
            <img
              v-if="detailImageUrl"
              class="max-h-[34rem] w-full object-contain"
              :src="detailImageUrl"
              alt=""
              data-image-preview
            />
            <div v-else-if="detailPdfUrl" class="grid min-h-52 place-items-center p-6 text-center">
              <div>
                <p class="font-semibold text-slate-800">PDF 使用安全附件响应</p>
                <a
                  class="mt-4 inline-flex rounded-xl bg-blue-700 px-4 py-2.5 text-sm font-semibold text-white"
                  :href="detailPdfUrl"
                  target="_blank"
                  rel="noopener noreferrer"
                  data-pdf-preview
                >在新页面打开或下载 PDF</a>
              </div>
            </div>
            <div v-else class="grid min-h-52 place-items-center p-6 text-center text-sm text-slate-500">
              当前状态没有可安全预览的 READY 变体。
            </div>
          </div>

          <dl class="grid grid-cols-2 gap-3 rounded-2xl bg-slate-50 p-4 text-sm">
            <div><dt class="text-slate-500">状态</dt><dd class="mt-1 font-semibold">{{ statusCopy[selectedAsset.status] }}</dd></div>
            <div><dt class="text-slate-500">版本</dt><dd class="mt-1 font-semibold">{{ selectedAsset.version }}</dd></div>
            <div><dt class="text-slate-500">大小</dt><dd class="mt-1 font-semibold">{{ formatBytes(selectedAsset.byteSize) }}</dd></div>
            <div><dt class="text-slate-500">类型</dt><dd class="mt-1 font-semibold">{{ kindOf(selectedAsset) }}</dd></div>
          </dl>
        </div>

        <div class="space-y-5">
          <div>
            <h3 class="text-lg font-semibold text-slate-950">双语说明</h3>
            <p class="mt-1 text-sm leading-6 text-slate-600">两种语言会在一次请求中共同保存。来源地址可留空，否则必须是严格 HTTPS。</p>
          </div>

          <div v-if="saveProblem" class="rounded-xl border border-red-200 bg-red-50 p-4 text-sm text-red-900" role="alert">
            <p class="font-semibold">{{ saveProblem.title }}</p>
            <p class="mt-1 text-xs">请求编号：{{ saveProblem.traceId }}</p>
          </div>
          <div v-if="saveUncertain" class="rounded-xl border border-amber-300 bg-amber-50 p-4 text-sm text-amber-950" data-save-uncertain>
            <p class="font-semibold">保存结果未知，不会再次提交。</p>
            <button
              class="mt-3 rounded-lg border border-amber-300 bg-white px-3 py-2 font-semibold disabled:opacity-50"
              type="button"
              data-action="reconcile-save"
              :disabled="saveBusy"
              @click="reconcileSave"
            >仅检查服务器状态</button>
          </div>
          <div v-if="saveConflict" class="rounded-xl border border-amber-300 bg-amber-50 p-4 text-sm text-amber-950" data-save-conflict>
            <p class="font-semibold">检测到另一份更新，本地内容尚未覆盖服务器。</p>
            <button
              class="mt-3 rounded-lg border border-amber-300 bg-white px-3 py-2 font-semibold"
              type="button"
              data-action="load-conflict-version"
              :disabled="saveBusy"
              @click="loadConflictVersion"
            >{{ conflictAsset === null ? '重新加载服务器版本' : '加载服务器版本' }}</button>
          </div>

          <div class="grid gap-5 lg:grid-cols-2">
            <fieldset
              v-for="entry in translationDraft"
              :key="entry.locale"
              class="space-y-4 rounded-2xl border border-slate-200 p-4"
              :data-translation-locale="entry.locale"
              :lang="entry.locale"
              :disabled="detailLoading || saveBusy || saveUncertain || archiveBusy || archiveUncertain || selectedAsset.status === 'PENDING_DELETE'"
            >
              <legend class="px-2 text-sm font-semibold text-blue-800">
                {{ entry.locale === 'zh-CN' ? '中文 · zh-CN' : 'English · en' }}
              </legend>

              <label class="block text-sm font-medium text-slate-800" :for="fieldId(entry.locale, 'altText')">
                替代文字
                <textarea
                  :id="fieldId(entry.locale, 'altText')"
                  class="mt-2 w-full resize-y rounded-xl border border-slate-300 px-3 py-2.5"
                  rows="3"
                  maxlength="500"
                  :value="entry.altText"
                  :data-field="`${entry.locale}.altText`"
                  :aria-invalid="fieldError(entry.locale, 'altText') ? 'true' : undefined"
                  :aria-describedby="fieldError(entry.locale, 'altText') ? fieldErrorId(entry.locale, 'altText') : undefined"
                  @input="updateTranslation(entry.locale, 'altText', $event)"
                />
                <span
                  v-if="fieldError(entry.locale, 'altText')"
                  :id="fieldErrorId(entry.locale, 'altText')"
                  class="mt-1 block text-sm text-red-700"
                  :data-field-error="`${entry.locale}.altText`"
                  role="alert"
                >{{ fieldError(entry.locale, 'altText') }}</span>
              </label>

              <label class="block text-sm font-medium text-slate-800" :for="fieldId(entry.locale, 'caption')">
                说明
                <textarea
                  :id="fieldId(entry.locale, 'caption')"
                  class="mt-2 w-full resize-y rounded-xl border border-slate-300 px-3 py-2.5"
                  rows="3"
                  maxlength="1000"
                  :value="entry.caption"
                  :data-field="`${entry.locale}.caption`"
                  :aria-invalid="fieldError(entry.locale, 'caption') ? 'true' : undefined"
                  :aria-describedby="fieldError(entry.locale, 'caption') ? fieldErrorId(entry.locale, 'caption') : undefined"
                  @input="updateTranslation(entry.locale, 'caption', $event)"
                />
                <span
                  v-if="fieldError(entry.locale, 'caption')"
                  :id="fieldErrorId(entry.locale, 'caption')"
                  class="mt-1 block text-sm text-red-700"
                  :data-field-error="`${entry.locale}.caption`"
                  role="alert"
                >{{ fieldError(entry.locale, 'caption') }}</span>
              </label>

              <label class="block text-sm font-medium text-slate-800" :for="fieldId(entry.locale, 'credit')">
                署名
                <input
                  :id="fieldId(entry.locale, 'credit')"
                  class="mt-2 w-full rounded-xl border border-slate-300 px-3 py-2.5"
                  type="text"
                  maxlength="300"
                  :value="entry.credit"
                  :data-field="`${entry.locale}.credit`"
                  :aria-invalid="fieldError(entry.locale, 'credit') ? 'true' : undefined"
                  :aria-describedby="fieldError(entry.locale, 'credit') ? fieldErrorId(entry.locale, 'credit') : undefined"
                  @input="updateTranslation(entry.locale, 'credit', $event)"
                />
                <span
                  v-if="fieldError(entry.locale, 'credit')"
                  :id="fieldErrorId(entry.locale, 'credit')"
                  class="mt-1 block text-sm text-red-700"
                  :data-field-error="`${entry.locale}.credit`"
                  role="alert"
                >{{ fieldError(entry.locale, 'credit') }}</span>
              </label>

              <label class="block text-sm font-medium text-slate-800" :for="fieldId(entry.locale, 'sourceUrl')">
                来源 HTTPS 地址（可选）
                <input
                  :id="fieldId(entry.locale, 'sourceUrl')"
                  class="mt-2 w-full rounded-xl border border-slate-300 px-3 py-2.5"
                  type="url"
                  inputmode="url"
                  maxlength="2048"
                  :value="entry.sourceUrl ?? ''"
                  :data-field="`${entry.locale}.sourceUrl`"
                  :aria-invalid="fieldError(entry.locale, 'sourceUrl') ? 'true' : undefined"
                  :aria-describedby="fieldError(entry.locale, 'sourceUrl') ? fieldErrorId(entry.locale, 'sourceUrl') : undefined"
                  @input="updateTranslation(entry.locale, 'sourceUrl', $event)"
                />
                <span
                  v-if="fieldError(entry.locale, 'sourceUrl')"
                  :id="fieldErrorId(entry.locale, 'sourceUrl')"
                  class="mt-1 block text-sm text-red-700"
                  :data-field-error="`${entry.locale}.sourceUrl`"
                  role="alert"
                >{{ fieldError(entry.locale, 'sourceUrl') }}</span>
              </label>
            </fieldset>
          </div>

          <div class="flex flex-wrap gap-3 border-t border-slate-100 pt-5">
            <button
              class="rounded-xl bg-blue-700 px-4 py-2.5 text-sm font-semibold text-white disabled:cursor-not-allowed disabled:opacity-55"
              type="button"
              data-action="save-translations"
              :disabled="detailLoading || !detailDirty || saveBusy || saveUncertain || saveConflict || archiveBusy || archiveUncertain || selectedAsset.status === 'PENDING_DELETE'"
              :aria-busy="saveBusy"
              @click="saveTranslations"
            >{{ saveBusy ? '正在保存…' : '保存双语说明' }}</button>
            <button
              v-if="detailDirty"
              class="rounded-xl border border-slate-300 bg-white px-4 py-2.5 text-sm font-semibold text-slate-700 disabled:cursor-not-allowed disabled:opacity-55"
              type="button"
              data-action="reset-translations"
              :disabled="detailLoading || saveBusy || saveUncertain || archiveBusy || archiveUncertain"
              @click="resetLocalDraft"
            >撤销本地修改</button>
            <button
              v-if="canArchive"
              class="rounded-xl border border-red-300 bg-white px-4 py-2.5 text-sm font-semibold text-red-800 disabled:cursor-not-allowed disabled:opacity-55"
              type="button"
              data-action="archive-media"
              :disabled="detailLoading || archiveBusy || archiveUncertain || saveBusy || saveUncertain || detailDirty"
              :aria-busy="archiveBusy"
              @click="archiveSelected"
            >{{ archiveBusy ? '正在归档…' : '归档媒体' }}</button>
          </div>

          <div v-if="archiveProblem" class="rounded-xl border border-red-200 bg-red-50 p-4 text-sm text-red-900" role="alert" data-archive-problem>
            <p class="font-semibold">{{ archiveProblem.title }}</p>
            <p class="mt-1 text-xs">请求编号：{{ archiveProblem.traceId }}</p>
          </div>
          <div v-if="archiveUncertain" class="rounded-xl border border-amber-300 bg-amber-50 p-4 text-sm text-amber-950" data-archive-uncertain>
            <p class="font-semibold">归档结果未知，不会再次提交。</p>
            <button
              class="mt-3 rounded-lg border border-amber-300 bg-white px-3 py-2 font-semibold disabled:opacity-50"
              type="button"
              data-action="reconcile-archive"
              :disabled="archiveBusy"
              @click="reconcileArchive"
            >仅检查服务器状态</button>
          </div>
        </div>
      </div>
    </section>
  </section>
</template>
