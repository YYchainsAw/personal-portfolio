<script setup lang="ts">
import { ref, toRaw, useId, watch } from 'vue'

import MediaPickerDialog, {
  type MediaPickerLoad,
  type MediaPickerPageRequest,
} from '@/components/media/MediaPickerDialog.vue'
import { mediaApi } from '@/api/mediaApi'
import type {
  Locale,
  MediaAssetSummaryDto,
  ProjectCopy,
  ProjectMediaDto,
  ProjectTaxonomyRefDto,
  ProjectWorkspaceDto,
  TaxonomyWorkspaceDto,
} from '@/types/content'

interface Props {
  modelValue: ProjectWorkspaceDto
  locale: Locale
  tagCatalog: readonly TaxonomyWorkspaceDto[]
  skillCatalog: readonly TaxonomyWorkspaceDto[]
  disabled?: boolean
  fieldErrors?: Readonly<Record<string, string>>
  loadMediaPage?: MediaPickerLoad
}

const props = withDefaults(defineProps<Props>(), {
  disabled: false,
  fieldErrors: undefined,
  loadMediaPage: undefined,
})

const emit = defineEmits<{
  'update:modelValue': [value: ProjectWorkspaceDto]
}>()

type ProjectCopyKey = keyof ProjectCopy
type TaxonomyKind = 'tags' | 'skills'
type MediaTextField = 'objectPosition' | 'credit' | 'sourceUrl'

interface CopyField {
  readonly key: ProjectCopyKey
  readonly label: string
  readonly multiline?: boolean
}

const copyFields: readonly CopyField[] = Object.freeze([
  { key: 'status', label: '项目状态' },
  { key: 'eyebrow', label: '眉标' },
  { key: 'title', label: '项目标题' },
  { key: 'summary', label: '项目摘要', multiline: true },
  { key: 'seoTitle', label: 'SEO 标题' },
  { key: 'seoDescription', label: 'SEO 描述', multiline: true },
])
const mediaUsages = Object.freeze(['COVER', 'CARD', 'DETAIL'] as const)
const mediaLayouts = Object.freeze(['wide', 'standard'] as const)
const readyImageMimeTypes = new Set(['image/jpeg', 'image/png'])
const uuidPattern = /^[0-9a-f]{8}-(?:[0-9a-f]{4}-){3}[0-9a-f]{12}$/i
const instanceId = useId()
const pickerOpen = ref(false)

function emitWorkspace(patch: Partial<ProjectWorkspaceDto>): void {
  if (props.disabled) return
  emit('update:modelValue', {
    ...props.modelValue,
    ...patch,
    blocks: toRaw(props.modelValue.blocks),
  })
}

function inputValue(event: Event): string {
  return (event.target as HTMLInputElement | HTMLTextAreaElement).value
}

function checkedValue(event: Event): boolean {
  return (event.target as HTMLInputElement).checked
}

function updateRootString(field: 'slug' | 'number', event: Event): void {
  emitWorkspace({ [field]: inputValue(event) })
}

function updateSortOrder(event: Event): void {
  const input = event.target as HTMLInputElement
  const value = input.valueAsNumber
  if (!Number.isSafeInteger(value) || value < 0 || value > 2_147_483_647) {
    input.value = String(props.modelValue.sortOrder)
    return
  }
  emitWorkspace({ sortOrder: value })
}

function updateRootBoolean(field: 'featured' | 'visible', event: Event): void {
  emitWorkspace({ [field]: checkedValue(event) })
}

function updateTranslation(key: ProjectCopyKey, event: Event): void {
  const nextCopy = {
    ...props.modelValue.translations[props.locale],
    [key]: inputValue(event),
  }
  emitWorkspace({
    translations:
      props.locale === 'zh-CN'
        ? { 'zh-CN': nextCopy, en: toRaw(props.modelValue.translations.en) }
        : { 'zh-CN': toRaw(props.modelValue.translations['zh-CN']), en: nextCopy },
  })
}

function fieldPathCandidates(path: string): readonly string[] {
  const dotPath = path.replace(/\[(\d+)\]/g, '.$1')
  const bracketPath = dotPath.replace(/\.(\d+)(?=\.|$)/g, '[$1]')
  return [...new Set([path, dotPath, bracketPath])]
}

function fieldError(path: string): string | undefined {
  for (const candidate of fieldPathCandidates(path)) {
    const message = props.fieldErrors?.[candidate]
    if (message !== undefined) return message
  }
  return undefined
}

function fieldErrorId(path: string): string {
  const encoded = [...path]
    .map((character) => character.codePointAt(0)!.toString(16))
    .join('-')
  return `${instanceId}-project-field-error-${encoded}`
}

function fieldAriaInvalid(path: string, localError?: string): 'true' | undefined {
  return fieldError(path) !== undefined || localError !== undefined ? 'true' : undefined
}

function fieldAriaDescribedBy(path: string, localError?: string): string | undefined {
  return fieldAriaInvalid(path, localError) === 'true' ? fieldErrorId(path) : undefined
}

function taxonomyCatalog(kind: TaxonomyKind): readonly TaxonomyWorkspaceDto[] {
  return kind === 'tags' ? props.tagCatalog : props.skillCatalog
}

function taxonomyRefs(kind: TaxonomyKind): readonly ProjectTaxonomyRefDto[] {
  return kind === 'tags' ? props.modelValue.tags : props.modelValue.skills
}

function taxonomySelected(kind: TaxonomyKind, id: string): boolean {
  const normalizedId = id.toLowerCase()
  return taxonomyRefs(kind).some((item) => item.id.toLowerCase() === normalizedId)
}

function normalizedTaxonomyRefs(
  refs: readonly ProjectTaxonomyRefDto[],
): ProjectTaxonomyRefDto[] {
  return refs.map((item, sortOrder) => ({
    id: item.id,
    normalizedKey: item.normalizedKey,
    sortOrder,
    names: { 'zh-CN': item.names['zh-CN'], en: item.names.en },
  }))
}

function toggleTaxonomy(
  kind: TaxonomyKind,
  catalogItem: TaxonomyWorkspaceDto,
  event: Event,
): void {
  if (props.disabled) return
  const selected = checkedValue(event)
  const normalizedId = catalogItem.id.toLowerCase()
  const current = taxonomyRefs(kind)
  const alreadySelected = current.some((item) => item.id.toLowerCase() === normalizedId)
  if (selected === alreadySelected) return

  const next = selected
    ? [
        ...current,
        {
          id: catalogItem.id,
          normalizedKey: catalogItem.normalizedKey,
          sortOrder: current.length,
          names: {
            'zh-CN': catalogItem.names['zh-CN'],
            en: catalogItem.names.en,
          },
        },
      ]
    : current.filter((item) => item.id.toLowerCase() !== normalizedId)
  const normalized = normalizedTaxonomyRefs(next)
  if (kind === 'tags') emitWorkspace({ tags: normalized })
  else emitWorkspace({ skills: normalized })
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value)
}

function trustedReadyImage(value: unknown): value is MediaAssetSummaryDto {
  if (!isRecord(value)) return false
  return (
    value.status === 'READY' &&
    value.kind === 'IMAGE' &&
    typeof value.id === 'string' &&
    uuidPattern.test(value.id) &&
    typeof value.mimeType === 'string' &&
    readyImageMimeTypes.has(value.mimeType)
  )
}

function normalizedMediaOrders(items: readonly ProjectMediaDto[]): ProjectMediaDto[] {
  const nextOrder: Record<ProjectMediaDto['usage'], number> = {
    COVER: 0,
    CARD: 0,
    DETAIL: 0,
  }
  return items.map((item) => {
    const sortOrder = nextOrder[item.usage]
    nextOrder[item.usage] += 1
    return { ...item, sortOrder }
  })
}

function hasMediaPair(
  assetId: string,
  usage: ProjectMediaDto['usage'],
  ignoredIndex = -1,
): boolean {
  const normalizedId = assetId.toLowerCase()
  return props.modelValue.media.some(
    (item, index) =>
      index !== ignoredIndex &&
      item.usage === usage &&
      item.assetId.toLowerCase() === normalizedId,
  )
}

function openMediaPicker(): void {
  if (!props.disabled) pickerOpen.value = true
}

function closeMediaPicker(): void {
  pickerOpen.value = false
}

function selectMedia(asset: MediaAssetSummaryDto): void {
  if (props.disabled || !trustedReadyImage(asset)) return
  pickerOpen.value = false
  if (hasMediaPair(asset.id, 'DETAIL')) return
  emitWorkspace({
    media: normalizedMediaOrders([
      ...props.modelValue.media,
      {
        assetId: asset.id,
        usage: 'DETAIL',
        sortOrder: 0,
        layout: 'standard',
        objectPosition: '50% 50%',
        credit: '',
        sourceUrl: '',
      },
    ]),
  })
}

function loadMedia(request: Readonly<MediaPickerPageRequest>) {
  return props.loadMediaPage === undefined
    ? mediaApi.search(request)
    : props.loadMediaPage(request)
}

function replaceMedia(index: number, replacement: ProjectMediaDto): void {
  const current = props.modelValue.media[index]
  if (current === undefined) return
  emitWorkspace({
    media: normalizedMediaOrders(
      props.modelValue.media.map((item, itemIndex) =>
        itemIndex === index ? replacement : item,
      ),
    ),
  })
}

function updateMediaUsage(index: number, event: Event): void {
  const current = props.modelValue.media[index]
  const select = event.target as HTMLSelectElement
  const usage = select.value
  if (
    current === undefined ||
    !mediaUsages.includes(usage as ProjectMediaDto['usage'])
  ) {
    if (current !== undefined) select.value = current.usage
    return
  }
  if (usage === current.usage) return
  const nextUsage = usage as ProjectMediaDto['usage']
  if (hasMediaPair(current.assetId, nextUsage, index)) {
    select.value = current.usage
    return
  }
  replaceMedia(index, { ...current, usage: nextUsage })
}

function updateMediaLayout(index: number, event: Event): void {
  const current = props.modelValue.media[index]
  const layout = (event.target as HTMLSelectElement).value
  if (
    current === undefined ||
    !mediaLayouts.includes(layout as ProjectMediaDto['layout']) ||
    layout === current.layout
  ) {
    return
  }
  replaceMedia(index, { ...current, layout: layout as ProjectMediaDto['layout'] })
}

function updateMediaText(index: number, field: MediaTextField, event: Event): void {
  const current = props.modelValue.media[index]
  if (current === undefined) return
  replaceMedia(index, { ...current, [field]: inputValue(event) })
}

function groupIndices(index: number): number[] {
  const item = props.modelValue.media[index]
  if (item === undefined) return []
  return props.modelValue.media.flatMap((candidate, candidateIndex) =>
    candidate.usage === item.usage ? [candidateIndex] : [],
  )
}

function canMoveMedia(index: number, offset: -1 | 1): boolean {
  if (props.disabled) return false
  const indices = groupIndices(index)
  const position = indices.indexOf(index)
  const target = position + offset
  return position >= 0 && target >= 0 && target < indices.length
}

function moveMedia(index: number, offset: -1 | 1): void {
  if (!canMoveMedia(index, offset)) return
  const indices = groupIndices(index)
  const position = indices.indexOf(index)
  const targetIndex = indices[position + offset]
  const current = props.modelValue.media[index]
  const target = targetIndex === undefined ? undefined : props.modelValue.media[targetIndex]
  if (targetIndex === undefined || current === undefined || target === undefined) return

  const reordered = [...props.modelValue.media]
  reordered[index] = target
  reordered[targetIndex] = current
  emitWorkspace({ media: normalizedMediaOrders(reordered) })
}

function deleteMedia(index: number): void {
  if (props.disabled || props.modelValue.media[index] === undefined) return
  emitWorkspace({
    media: normalizedMediaOrders(
      props.modelValue.media.filter((_item, itemIndex) => itemIndex !== index),
    ),
  })
}

function persistedHttpsUrl(value: string): boolean {
  if (!value.startsWith('https://')) return false
  try {
    const url = new URL(value)
    return url.protocol === 'https:' && url.hostname.length > 0
  } catch {
    return false
  }
}

function sourceUrlError(index: number): string | undefined {
  const path = `media.${index}.sourceUrl`
  const serverError = fieldError(path)
  if (serverError !== undefined) return serverError
  const sourceUrl = props.modelValue.media[index]?.sourceUrl ?? ''
  return persistedHttpsUrl(sourceUrl) ? undefined : '来源链接必须使用 HTTPS 地址'
}

watch(
  () => props.disabled,
  (disabled) => {
    if (disabled) closeMediaPicker()
  },
)
</script>

<template>
  <section class="space-y-8" aria-label="项目元数据">
    <fieldset
      class="min-w-0 space-y-8"
      data-section="project-metadata"
      :disabled="disabled"
    >
      <legend class="sr-only">项目元数据编辑器</legend>

      <section class="rounded-2xl border border-slate-200 bg-white p-5 shadow-sm sm:p-6">
        <h2 class="text-lg font-semibold text-slate-950">项目标识与展示</h2>
        <div class="mt-5 grid gap-5 md:grid-cols-2">
          <label class="text-sm font-medium text-slate-800">
            稳定键（只读）
            <input
              class="mt-2 w-full rounded-xl border border-slate-300 bg-slate-100 px-3 py-2.5 text-slate-600"
              data-field="externalKey"
              name="externalKey"
              :value="modelValue.externalKey"
              readonly
              :aria-invalid="fieldAriaInvalid('externalKey')"
              :aria-describedby="fieldAriaDescribedBy('externalKey')"
            />
            <span
              v-if="fieldError('externalKey')"
              :id="fieldErrorId('externalKey')"
              class="mt-2 block text-sm text-red-700"
              role="alert"
            >{{ fieldError('externalKey') }}</span>
          </label>

          <label class="text-sm font-medium text-slate-800">
            URL Slug
            <input
              class="mt-2 w-full rounded-xl border border-slate-300 px-3 py-2.5"
              data-field="slug"
              name="slug"
              :value="modelValue.slug"
              required
              :aria-invalid="fieldAriaInvalid('slug')"
              :aria-describedby="fieldAriaDescribedBy('slug')"
              @input="updateRootString('slug', $event)"
            />
            <span
              v-if="fieldError('slug')"
              :id="fieldErrorId('slug')"
              class="mt-2 block text-sm text-red-700"
              role="alert"
            >{{ fieldError('slug') }}</span>
          </label>

          <label class="text-sm font-medium text-slate-800">
            项目编号
            <input
              class="mt-2 w-full rounded-xl border border-slate-300 px-3 py-2.5"
              data-field="number"
              name="number"
              :value="modelValue.number"
              :aria-invalid="fieldAriaInvalid('number')"
              :aria-describedby="fieldAriaDescribedBy('number')"
              @input="updateRootString('number', $event)"
            />
            <span
              v-if="fieldError('number')"
              :id="fieldErrorId('number')"
              class="mt-2 block text-sm text-red-700"
              role="alert"
            >{{ fieldError('number') }}</span>
          </label>

          <label class="text-sm font-medium text-slate-800">
            目录顺序
            <input
              class="mt-2 w-full rounded-xl border border-slate-300 px-3 py-2.5"
              data-field="sortOrder"
              name="sortOrder"
              type="number"
              min="0"
              max="2147483647"
              step="1"
              :value="modelValue.sortOrder"
              :aria-invalid="fieldAriaInvalid('sortOrder')"
              :aria-describedby="fieldAriaDescribedBy('sortOrder')"
              @input="updateSortOrder"
            />
            <span
              v-if="fieldError('sortOrder')"
              :id="fieldErrorId('sortOrder')"
              class="mt-2 block text-sm text-red-700"
              role="alert"
            >{{ fieldError('sortOrder') }}</span>
          </label>

          <label class="flex items-center gap-3 text-sm font-medium text-slate-800">
            <input
              data-field="featured"
              name="featured"
              type="checkbox"
              :checked="modelValue.featured"
              :aria-invalid="fieldAriaInvalid('featured')"
              :aria-describedby="fieldAriaDescribedBy('featured')"
              @change="updateRootBoolean('featured', $event)"
            />
            首页精选
            <span
              v-if="fieldError('featured')"
              :id="fieldErrorId('featured')"
              class="text-sm text-red-700"
              role="alert"
            >{{ fieldError('featured') }}</span>
          </label>
          <label class="flex items-center gap-3 text-sm font-medium text-slate-800">
            <input
              data-field="visible"
              name="visible"
              type="checkbox"
              :checked="modelValue.visible"
              :aria-invalid="fieldAriaInvalid('visible')"
              :aria-describedby="fieldAriaDescribedBy('visible')"
              @change="updateRootBoolean('visible', $event)"
            />
            公开显示
            <span
              v-if="fieldError('visible')"
              :id="fieldErrorId('visible')"
              class="text-sm text-red-700"
              role="alert"
            >{{ fieldError('visible') }}</span>
          </label>
        </div>
      </section>

      <section class="rounded-2xl border border-slate-200 bg-white p-5 shadow-sm sm:p-6">
        <h2 class="text-lg font-semibold text-slate-950">
          项目文案
          <span class="ml-2 text-sm font-medium text-blue-700">{{ locale }}</span>
        </h2>
        <div class="mt-5 grid gap-5">
          <label
            v-for="copyField in copyFields"
            :key="copyField.key"
            class="text-sm font-medium text-slate-800"
          >
            {{ copyField.label }}
            <textarea
              v-if="copyField.multiline"
              class="mt-2 w-full resize-y rounded-xl border border-slate-300 px-3 py-2.5"
              :data-field="`translations.${locale}.${copyField.key}`"
              :name="`translations.${locale}.${copyField.key}`"
              :lang="locale"
              rows="4"
              :value="modelValue.translations[locale][copyField.key]"
              :aria-invalid="fieldAriaInvalid(`translations.${locale}.${copyField.key}`)"
              :aria-describedby="fieldAriaDescribedBy(`translations.${locale}.${copyField.key}`)"
              @input="updateTranslation(copyField.key, $event)"
            />
            <input
              v-else
              class="mt-2 w-full rounded-xl border border-slate-300 px-3 py-2.5"
              :data-field="`translations.${locale}.${copyField.key}`"
              :name="`translations.${locale}.${copyField.key}`"
              :lang="locale"
              :value="modelValue.translations[locale][copyField.key]"
              :aria-invalid="fieldAriaInvalid(`translations.${locale}.${copyField.key}`)"
              :aria-describedby="fieldAriaDescribedBy(`translations.${locale}.${copyField.key}`)"
              @input="updateTranslation(copyField.key, $event)"
            />
            <span
              v-if="fieldError(`translations.${locale}.${copyField.key}`)"
              :id="fieldErrorId(`translations.${locale}.${copyField.key}`)"
              class="mt-2 block text-sm text-red-700"
              role="alert"
            >{{ fieldError(`translations.${locale}.${copyField.key}`) }}</span>
          </label>
        </div>
      </section>

      <section class="grid gap-6 lg:grid-cols-2">
        <fieldset
          v-for="kind in (['tags', 'skills'] as const)"
          :key="kind"
          class="rounded-2xl border border-slate-200 bg-white p-5 shadow-sm sm:p-6"
          :data-taxonomy-group="kind"
          :aria-invalid="fieldAriaInvalid(kind)"
          :aria-describedby="fieldAriaDescribedBy(kind)"
        >
          <legend class="px-1 text-lg font-semibold text-slate-950">
            {{ kind === 'tags' ? '项目标签' : '技能栈' }}
          </legend>
          <div class="mt-4 grid gap-3">
            <label
              v-for="item in taxonomyCatalog(kind)"
              :key="item.id"
              class="flex items-center gap-3 rounded-xl border border-slate-200 px-3 py-2.5 text-sm text-slate-800"
            >
              <input
                type="checkbox"
                :data-taxonomy="kind"
                :data-taxonomy-id="item.id"
                :checked="taxonomySelected(kind, item.id)"
                @change="toggleTaxonomy(kind, item, $event)"
              />
              <span :lang="locale">{{ item.names[locale] }}</span>
              <code class="ml-auto text-xs text-slate-500">{{ item.normalizedKey }}</code>
            </label>
          </div>
          <p
            v-if="fieldError(kind)"
            :id="fieldErrorId(kind)"
            class="mt-3 text-sm text-red-700"
            role="alert"
          >{{ fieldError(kind) }}</p>
        </fieldset>
      </section>

      <section class="rounded-2xl border border-slate-200 bg-white p-5 shadow-sm sm:p-6">
        <div class="flex flex-wrap items-start justify-between gap-4">
          <div>
            <h2 class="text-lg font-semibold text-slate-950">项目媒体</h2>
            <p class="mt-1 text-sm text-slate-600">仅可添加已处理完成的图片。</p>
          </div>
          <button
            class="rounded-lg border border-blue-300 bg-blue-50 px-3 py-2 text-sm font-semibold text-blue-800 disabled:opacity-50"
            type="button"
            data-action="add-project-media"
            :disabled="disabled"
            @click="openMediaPicker"
          >添加图片</button>
        </div>

        <p
          v-if="fieldError('media')"
          :id="fieldErrorId('media')"
          class="mt-3 text-sm text-red-700"
          role="alert"
        >{{ fieldError('media') }}</p>

        <div
          v-if="modelValue.media.length === 0"
          class="mt-5 rounded-xl border border-dashed border-slate-300 p-5 text-sm text-slate-500"
          data-media-empty
        >暂无项目媒体</div>

        <ol v-else class="mt-5 grid list-none gap-4 p-0">
          <li
            v-for="(item, index) in modelValue.media"
            :key="`${item.assetId}-${item.usage}-${index}`"
            class="rounded-xl border border-slate-200 p-4"
            :data-project-media-index="index"
          >
            <div class="flex flex-wrap items-center justify-between gap-3">
              <code class="break-all text-xs text-slate-600">{{ item.assetId }}</code>
              <div class="flex flex-wrap gap-2">
                <button
                  type="button"
                  data-action="move-media-up"
                  :disabled="!canMoveMedia(index, -1)"
                  :aria-label="`上移媒体 ${item.assetId}`"
                  @click="moveMedia(index, -1)"
                >上移</button>
                <button
                  type="button"
                  data-action="move-media-down"
                  :disabled="!canMoveMedia(index, 1)"
                  :aria-label="`下移媒体 ${item.assetId}`"
                  @click="moveMedia(index, 1)"
                >下移</button>
                <button
                  type="button"
                  data-action="delete-media"
                  :disabled="disabled"
                  :aria-label="`删除媒体 ${item.assetId}`"
                  @click="deleteMedia(index)"
                >删除</button>
              </div>
            </div>

            <div class="mt-4 grid gap-4 md:grid-cols-2">
              <label class="text-sm font-medium text-slate-800">
                用途
                <select
                  class="mt-2 w-full rounded-lg border border-slate-300 px-3 py-2"
                  :data-field="`media.${index}.usage`"
                  :value="item.usage"
                  :aria-invalid="fieldAriaInvalid(`media.${index}.usage`)"
                  :aria-describedby="fieldAriaDescribedBy(`media.${index}.usage`)"
                  @change="updateMediaUsage(index, $event)"
                >
                  <option v-for="usage in mediaUsages" :key="usage" :value="usage">{{ usage }}</option>
                </select>
                <span
                  v-if="fieldError(`media.${index}.usage`)"
                  :id="fieldErrorId(`media.${index}.usage`)"
                  class="mt-2 block text-sm text-red-700"
                  role="alert"
                >{{ fieldError(`media.${index}.usage`) }}</span>
              </label>

              <label class="text-sm font-medium text-slate-800">
                布局
                <select
                  class="mt-2 w-full rounded-lg border border-slate-300 px-3 py-2"
                  :data-field="`media.${index}.layout`"
                  :value="item.layout"
                  :aria-invalid="fieldAriaInvalid(`media.${index}.layout`)"
                  :aria-describedby="fieldAriaDescribedBy(`media.${index}.layout`)"
                  @change="updateMediaLayout(index, $event)"
                >
                  <option v-for="layout in mediaLayouts" :key="layout" :value="layout">{{ layout }}</option>
                </select>
                <span
                  v-if="fieldError(`media.${index}.layout`)"
                  :id="fieldErrorId(`media.${index}.layout`)"
                  class="mt-2 block text-sm text-red-700"
                  role="alert"
                >{{ fieldError(`media.${index}.layout`) }}</span>
              </label>

              <label
                v-for="field in (['objectPosition', 'credit'] as const)"
                :key="field"
                class="text-sm font-medium text-slate-800"
              >
                {{ field === 'objectPosition' ? '对象位置' : '图片署名' }}
                <input
                  class="mt-2 w-full rounded-lg border border-slate-300 px-3 py-2"
                  :data-field="`media.${index}.${field}`"
                  :value="item[field]"
                  :aria-invalid="fieldAriaInvalid(`media.${index}.${field}`)"
                  :aria-describedby="fieldAriaDescribedBy(`media.${index}.${field}`)"
                  @input="updateMediaText(index, field, $event)"
                />
                <span
                  v-if="fieldError(`media.${index}.${field}`)"
                  :id="fieldErrorId(`media.${index}.${field}`)"
                  class="mt-2 block text-sm text-red-700"
                  role="alert"
                >{{ fieldError(`media.${index}.${field}`) }}</span>
              </label>

              <label class="text-sm font-medium text-slate-800 md:col-span-2">
                来源链接（HTTPS）
                <input
                  class="mt-2 w-full rounded-lg border border-slate-300 px-3 py-2"
                  :data-field="`media.${index}.sourceUrl`"
                  type="url"
                  :value="item.sourceUrl"
                  :aria-invalid="fieldAriaInvalid(`media.${index}.sourceUrl`, sourceUrlError(index))"
                  :aria-describedby="fieldAriaDescribedBy(`media.${index}.sourceUrl`, sourceUrlError(index))"
                  @input="updateMediaText(index, 'sourceUrl', $event)"
                />
                <span
                  v-if="sourceUrlError(index)"
                  :id="fieldErrorId(`media.${index}.sourceUrl`)"
                  class="mt-2 block text-sm text-red-700"
                  role="alert"
                >{{ sourceUrlError(index) }}</span>
              </label>
            </div>
          </li>
        </ol>
      </section>
    </fieldset>

    <MediaPickerDialog
      :open="pickerOpen"
      :accept="['IMAGE']"
      :load="loadMedia"
      @select="selectMedia"
      @close="closeMediaPicker"
      @update:open="(open) => { if (!open) closeMediaPicker() }"
    />
  </section>
</template>
