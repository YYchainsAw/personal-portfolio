<script setup lang="ts">
import { nextTick, ref, useId } from 'vue'

import type {
  MetricCopy,
  MetricDto,
  MetricsPayload,
} from '@/types/blocks'
import type { Locale, Localized } from '@/types/content'

interface Props {
  modelValue: MetricsPayload
  locale: Locale
  disabled?: boolean
  fieldErrors?: Readonly<Record<string, string>>
}

const props = withDefaults(defineProps<Props>(), {
  disabled: false,
  fieldErrors: undefined,
})

const emit = defineEmits<{
  'update:modelValue': [value: MetricsPayload]
}>()

type CopyField = keyof MetricCopy

const decimalPattern = /^[+-]?(?:(?:\d+(?:\.\d*)?)|(?:\.\d+))(?:[eE][+-]?\d+)?$/
const copyFields = Object.freeze([
  { key: 'label', label: '标签' },
  { key: 'value', label: '展示值' },
  { key: 'suffix', label: '后缀' },
] as const)
const instanceId = useId()
const list = ref<HTMLOListElement | null>(null)
const addButton = ref<HTMLButtonElement | null>(null)
const announcement = ref('')

function inputValue(event: Event): string {
  return (event.target as HTMLInputElement).value
}

function cloneCopy(copy: Localized<MetricCopy>): Localized<MetricCopy> {
  return {
    'zh-CN': { ...copy['zh-CN'] },
    en: { ...copy.en },
  }
}

function cloneMetric(metric: MetricDto, sortOrder = metric.sortOrder): MetricDto {
  return {
    id: metric.id,
    sortOrder,
    numericValue: metric.numericValue,
    copy: cloneCopy(metric.copy),
  }
}

function emitMetrics(metrics: readonly MetricDto[]): void {
  emit('update:modelValue', {
    type: 'METRICS',
    metrics: metrics.map((metric) => cloneMetric(metric)),
  })
}

function replaceMetric(index: number, replacement: MetricDto): void {
  if (props.disabled || props.modelValue.metrics[index] === undefined) return
  emitMetrics(
    props.modelValue.metrics.map((metric, metricIndex) =>
      metricIndex === index ? replacement : metric,
    ),
  )
}

function updateNumericValue(index: number, event: Event): void {
  const metric = props.modelValue.metrics[index]
  if (props.disabled || metric === undefined) return
  const raw = inputValue(event)
  replaceMetric(index, {
    ...cloneMetric(metric),
    numericValue: raw === '' ? null : raw,
  })
}

function updateCopy(index: number, field: CopyField, event: Event): void {
  const metric = props.modelValue.metrics[index]
  if (props.disabled || metric === undefined) return
  const copy = cloneCopy(metric.copy)
  copy[props.locale] = {
    ...copy[props.locale],
    [field]: inputValue(event),
  }
  replaceMetric(index, { ...cloneMetric(metric), copy })
}

function normalizeOrders(metrics: readonly MetricDto[]): MetricDto[] {
  return metrics.map((metric, sortOrder) => cloneMetric(metric, sortOrder))
}

function metricHeadingId(index: number): string {
  return `${instanceId}-metric-${index}-title`
}

function metricErrorSummaryId(index: number): string {
  return `${instanceId}-metric-${index}-structural-errors`
}

function metricRow(id: string): HTMLElement | null {
  return [...(list.value?.querySelectorAll<HTMLElement>('[data-metric-id]') ?? [])]
    .find((item) => item.dataset.metricId === id) ?? null
}

async function addMetric(): Promise<void> {
  if (props.disabled) return
  const metric: MetricDto = {
    id: globalThis.crypto.randomUUID(),
    sortOrder: props.modelValue.metrics.length,
    numericValue: null,
    copy: {
      'zh-CN': { label: '', value: '', suffix: '' },
      en: { label: '', value: '', suffix: '' },
    },
  }
  const next = normalizeOrders([...props.modelValue.metrics, metric])
  emitMetrics(next)
  announcement.value = `指标已添加，第 ${next.length} 项，共 ${next.length} 项`
  await nextTick()
  metricRow(metric.id)?.querySelector<HTMLInputElement>('[data-field="numericValue"]')?.focus()
}

function canMove(index: number, offset: -1 | 1): boolean {
  const target = index + offset
  return !props.disabled && target >= 0 && target < props.modelValue.metrics.length
}

async function moveMetric(index: number, offset: -1 | 1, event: MouseEvent): Promise<void> {
  if (!canMove(index, offset)) return
  const target = index + offset
  const reordered = props.modelValue.metrics.map((metric) => cloneMetric(metric))
  const current = reordered[index]
  const adjacent = reordered[target]
  if (current === undefined || adjacent === undefined) return
  reordered[index] = adjacent
  reordered[target] = current
  const normalized = normalizeOrders(reordered)
  emitMetrics(normalized)
  announcement.value = `指标已移动到第 ${target + 1} 项，共 ${normalized.length} 项`

  const trigger = event.currentTarget as HTMLButtonElement | null
  await nextTick()
  if (trigger?.isConnected && !trigger.disabled) {
    trigger.focus()
    return
  }
  const fallbackDirection = offset === -1 ? 'down' : 'up'
  const row = metricRow(current.id)
  ;(row?.querySelector<HTMLButtonElement>(`[data-direction="${fallbackDirection}"]:not(:disabled)`)
    ?? row?.querySelector<HTMLButtonElement>('[data-action="delete-metric"]'))?.focus()
}

async function deleteMetric(index: number): Promise<void> {
  if (props.disabled || props.modelValue.metrics[index] === undefined) return
  const next = normalizeOrders(
    props.modelValue.metrics.filter((_metric, metricIndex) => metricIndex !== index),
  )
  emitMetrics(next)
  announcement.value = `指标已删除，剩余 ${next.length} 项`
  await nextTick()
  const focusMetric = next[Math.min(index, next.length - 1)]
  if (focusMetric !== undefined) {
    metricRow(focusMetric.id)?.querySelector<HTMLElement>('[data-metric-heading]')?.focus()
    return
  }
  addButton.value?.focus()
}

function normalizedPath(path: string): string {
  return path.replace(/\[(\d+)\]/g, '.$1')
}

function fieldError(path: string): string | undefined {
  const expected = normalizedPath(path)
  for (const [candidate, message] of Object.entries(props.fieldErrors ?? {})) {
    const normalizedCandidate = normalizedPath(candidate)
    if (
      normalizedCandidate === expected ||
      normalizedCandidate === `payload.${expected}` ||
      normalizedCandidate.endsWith(`.payload.${expected}`)
    ) {
      return message
    }
  }
  return undefined
}

function decimalError(index: number): string | undefined {
  const serverError = fieldError(`metrics.${index}.numericValue`)
  if (serverError !== undefined) return serverError
  const value = props.modelValue.metrics[index]?.numericValue
  if (value === null || value === undefined || decimalPattern.test(value)) return undefined
  return 'numericValue 必须是有效的 decimal 字符串'
}

function errorId(path: string): string {
  return `${instanceId}-metric-${path.replace(/[^a-zA-Z0-9_-]/g, '-')}-error`
}

function metricStructuralErrors(index: number): readonly string[] {
  return [...new Set([
    fieldError(`metrics.${index}.id`),
    fieldError(`metrics.${index}.sortOrder`),
  ].filter((message): message is string => typeof message === 'string' && message.length > 0))]
}
</script>

<template>
  <section
    class="space-y-4"
    data-block-editor="METRICS"
    aria-label="指标内容块"
    :aria-describedby="fieldError('metrics') ? errorId('metrics') : undefined"
  >
    <div class="flex items-center justify-between gap-3">
      <p class="text-sm text-slate-600">指标按照当前顺序展示，数值会以原始 decimal 文本保存。</p>
      <button
        ref="addButton"
        class="rounded-lg border border-slate-300 px-3 py-2 text-sm font-semibold text-slate-700 disabled:opacity-45"
        type="button"
        data-action="add-metric"
        :disabled="disabled"
        @click="addMetric"
      >添加指标</button>
    </div>

    <p class="sr-only" role="status" aria-live="polite" aria-atomic="true">
      {{ announcement }}
    </p>

    <p
      v-if="fieldError('metrics')"
      :id="errorId('metrics')"
      class="text-sm text-red-700"
      role="alert"
    >{{ fieldError('metrics') }}</p>

    <p
      v-if="modelValue.metrics.length === 0"
      class="rounded-xl border border-dashed border-slate-300 bg-slate-50 p-4 text-sm text-slate-500"
      data-state="empty"
    >尚未添加指标。</p>

    <ol v-else ref="list" class="grid list-none gap-4 p-0" aria-label="指标列表">
      <li
        v-for="(metric, index) in modelValue.metrics"
        :key="metric.id"
        class="rounded-xl border border-slate-200 p-4"
        :data-metric-id="metric.id"
        :aria-labelledby="metricHeadingId(index)"
        :aria-describedby="metricStructuralErrors(index).length > 0 ? metricErrorSummaryId(index) : undefined"
      >
        <div class="flex flex-wrap items-center justify-between gap-3">
          <h3
            :id="metricHeadingId(index)"
            class="rounded text-sm font-semibold text-slate-800 focus:outline-none focus:ring-2 focus:ring-blue-500"
            data-metric-heading
            tabindex="-1"
          >指标 {{ index + 1 }}</h3>
          <div class="flex flex-wrap gap-2" role="group" :aria-label="`指标 ${index + 1} 操作`">
            <button
              type="button"
              data-action="move-metric"
              data-direction="up"
              :disabled="!canMove(index, -1)"
              :aria-label="`上移指标 ${index + 1}`"
              @click="moveMetric(index, -1, $event)"
            >上移</button>
            <button
              type="button"
              data-action="move-metric"
              data-direction="down"
              :disabled="!canMove(index, 1)"
              :aria-label="`下移指标 ${index + 1}`"
              @click="moveMetric(index, 1, $event)"
            >下移</button>
            <button
              type="button"
              data-action="delete-metric"
              :disabled="disabled"
              :aria-label="`删除指标 ${index + 1}`"
              @click="deleteMetric(index)"
            >删除</button>
          </div>
        </div>

        <div
          v-if="metricStructuralErrors(index).length > 0"
          :id="metricErrorSummaryId(index)"
          class="mt-4 rounded-xl border border-red-200 bg-red-50 p-3 text-sm text-red-800 focus:outline-none focus:ring-2 focus:ring-red-500"
          data-metric-error-summary
          role="alert"
          tabindex="-1"
        >
          <p class="font-semibold">请修正指标 {{ index + 1 }}</p>
          <ul class="mt-1 list-disc space-y-1 pl-5">
            <li v-for="message in metricStructuralErrors(index)" :key="message">{{ message }}</li>
          </ul>
        </div>

        <div class="mt-4 grid gap-4 sm:grid-cols-2 lg:grid-cols-4">
          <label class="text-sm font-medium text-slate-800">
            Decimal 数值（可选）
            <input
              class="mt-2 w-full rounded-xl border border-slate-300 px-3 py-2.5 font-mono"
              data-field="numericValue"
              :name="`metrics.${index}.numericValue`"
              type="text"
              inputmode="decimal"
              autocomplete="off"
              :value="metric.numericValue ?? ''"
              :disabled="disabled"
              :aria-invalid="decimalError(index) ? 'true' : undefined"
              :aria-describedby="decimalError(index) ? errorId(`metrics.${index}.numericValue`) : undefined"
              @input="updateNumericValue(index, $event)"
            />
            <span
              v-if="decimalError(index)"
              :id="errorId(`metrics.${index}.numericValue`)"
              class="mt-2 block text-sm text-red-700"
              data-decimal-error
              role="alert"
            >{{ decimalError(index) }}</span>
          </label>

          <label
            v-for="copyField in copyFields"
            :key="copyField.key"
            class="text-sm font-medium text-slate-800"
          >
            {{ copyField.label }}
            <input
              class="mt-2 w-full rounded-xl border border-slate-300 px-3 py-2.5"
              :data-field="`copy.${locale}.${copyField.key}`"
              :name="`metrics.${index}.copy.${locale}.${copyField.key}`"
              :lang="locale"
              :value="metric.copy[locale][copyField.key]"
              :disabled="disabled"
              :aria-invalid="fieldError(`metrics.${index}.copy.${locale}.${copyField.key}`) ? 'true' : undefined"
              :aria-describedby="fieldError(`metrics.${index}.copy.${locale}.${copyField.key}`) ? errorId(`metrics.${index}.copy.${locale}.${copyField.key}`) : undefined"
              @input="updateCopy(index, copyField.key, $event)"
            />
            <span
              v-if="fieldError(`metrics.${index}.copy.${locale}.${copyField.key}`)"
              :id="errorId(`metrics.${index}.copy.${locale}.${copyField.key}`)"
              class="mt-2 block text-sm text-red-700"
              role="alert"
            >{{ fieldError(`metrics.${index}.copy.${locale}.${copyField.key}`) }}</span>
          </label>
        </div>
      </li>
    </ol>
  </section>
</template>
