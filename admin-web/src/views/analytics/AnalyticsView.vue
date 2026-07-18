<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'

import { ANALYTICS_ZONE, operationsApi } from '@/api/operationsApi'
import AsyncPanel from '@/components/common/AsyncPanel.vue'
import { ApiProblem } from '@/types/api'
import type {
  AnalyticsBreakdownItemDto,
  AnalyticsDimension,
  AnalyticsEventType,
  AnalyticsLocale,
  AnalyticsMetric,
  AnalyticsPointDto,
  AnalyticsSummaryDto,
  AnalyticsWorkbenchDto,
} from '@/types/operations'

interface AnalyticsWorkbenchQuery {
  readonly from: string
  readonly to: string
  readonly locale: AnalyticsLocale
  readonly metric: AnalyticsMetric
  readonly eventType: AnalyticsEventType
  readonly dimension: AnalyticsDimension
  readonly limit: number
  readonly zone: typeof ANALYTICS_ZONE
}

type LoadAnalytics = (
  query: Readonly<AnalyticsWorkbenchQuery>,
) => Promise<AnalyticsWorkbenchDto>

interface DisplayProblem {
  readonly title: string
  readonly traceId?: string
}

interface SummaryCard {
  readonly key:
    | 'pageViews'
    | 'dailyUniqueVisitors'
    | 'projectViews'
    | 'resumeDownloads'
    | 'demoDownloads'
    | 'outboundClicks'
  readonly label: string
  readonly englishLabel: string
  readonly value: number
}

interface BreakdownRow extends AnalyticsBreakdownItemDto {
  readonly key: string
  readonly domKey: string
}

const DAY_MS = 86_400_000
const MAXIMUM_DAYS = 366
const MAXIMUM_BREAKDOWN_LIMIT = 100

const metricOptions: readonly Readonly<{ value: AnalyticsMetric; label: string }>[] =
  Object.freeze([
    { value: 'PV', label: '页面浏览量（PV）' },
    { value: 'DAILY_UV', label: '匿名日访客（Daily UV）' },
    { value: 'EVENT_COUNT', label: '事件次数（Event count）' },
  ])

const eventOptions: readonly Readonly<{ value: AnalyticsEventType; label: string }>[] =
  Object.freeze([
    { value: 'PAGE_VIEW', label: '页面浏览 / Page view' },
    { value: 'PROJECT_VIEW', label: '项目浏览 / Project view' },
    { value: 'RESUME_DOWNLOAD', label: '简历下载 / Resume download' },
    { value: 'DEMO_DOWNLOAD', label: '演示下载 / Demo download' },
    { value: 'OUTBOUND_CLICK', label: '外链点击 / Outbound click' },
  ])

const dimensionOptions: readonly Readonly<{
  value: AnalyticsDimension
  label: string
}>[] = Object.freeze([
  { value: 'PAGE', label: '页面 / Page' },
  { value: 'PROJECT', label: '项目 / Project' },
  { value: 'REFERRER', label: '来源 / Referrer' },
  { value: 'DEVICE', label: '设备 / Device' },
  { value: 'LOCALE', label: '访问语言 / Visitor locale' },
])

function zoneDateToday(): string {
  const parts = new Intl.DateTimeFormat('en-US', {
    timeZone: ANALYTICS_ZONE,
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
  }).formatToParts(new Date())
  const year = parts.find((part) => part.type === 'year')?.value
  const month = parts.find((part) => part.type === 'month')?.value
  const day = parts.find((part) => part.type === 'day')?.value
  if (year === undefined || month === undefined || day === undefined) {
    return new Date().toISOString().slice(0, 10)
  }
  return `${year}-${month}-${day}`
}

function shiftDate(value: string, days: number): string {
  const date = new Date(`${value}T00:00:00Z`)
  date.setUTCDate(date.getUTCDate() + days)
  return date.toISOString().slice(0, 10)
}

async function defaultLoad(
  query: Readonly<AnalyticsWorkbenchQuery>,
): Promise<AnalyticsWorkbenchDto> {
  const { from, to, locale, metric, eventType, dimension, limit } = query
  const [summary, timeseries, breakdown] = await Promise.all([
    operationsApi.getAnalyticsSummary({ from, to, locale }),
    operationsApi.getAnalyticsTimeseries({ from, to, metric, eventType }),
    operationsApi.getAnalyticsBreakdown({
      from,
      to,
      metric,
      eventType,
      dimension,
      limit,
    }),
  ])
  return { summary, timeseries, breakdown }
}

const props = defineProps<{
  load?: LoadAnalytics
}>()

const defaultTo = zoneDateToday()
const from = ref(shiftDate(defaultTo, -29))
const to = ref(defaultTo)
const locale = ref<AnalyticsLocale>('zh-CN')
const metric = ref<AnalyticsMetric>('PV')
const eventType = ref<AnalyticsEventType>('PAGE_VIEW')
const dimension = ref<AnalyticsDimension>('PAGE')
const limit = ref<number | string>(10)

const loading = ref(false)
const loadProblem = ref<DisplayProblem | null>(null)
const queryProblem = ref('')
const workbench = ref<AnalyticsWorkbenchDto | null>(null)
const requestedQuery = ref<Readonly<AnalyticsWorkbenchQuery> | null>(null)
const committedQuery = ref<Readonly<AnalyticsWorkbenchQuery> | null>(null)

let generation = 0
let disposed = false

watch(metric, (nextMetric) => {
  if (nextMetric !== 'EVENT_COUNT') eventType.value = 'PAGE_VIEW'
})

function isCanonicalDate(value: string): boolean {
  if (!/^\d{4}-\d{2}-\d{2}$/.test(value)) return false
  const instant = Date.parse(`${value}T00:00:00Z`)
  return Number.isFinite(instant) && new Date(instant).toISOString().slice(0, 10) === value
}

function safeQuery(): Readonly<AnalyticsWorkbenchQuery> | null {
  queryProblem.value = ''
  if (!isCanonicalDate(from.value) || !isCanonicalDate(to.value)) {
    queryProblem.value = '请选择开始和结束日期 / Choose both dates.'
    return null
  }

  const fromEpoch = Date.parse(`${from.value}T00:00:00Z`)
  const toEpoch = Date.parse(`${to.value}T00:00:00Z`)
  if (fromEpoch > toEpoch) {
    queryProblem.value = '开始日期不能晚于结束日期 / Start date must not be after end date.'
    return null
  }
  const days = (toEpoch - fromEpoch) / DAY_MS + 1
  if (!Number.isInteger(days) || days > MAXIMUM_DAYS) {
    queryProblem.value = '日期范围最多包含 366 天（含首尾）/ Select at most 366 inclusive days.'
    return null
  }

  const normalizedLimit =
    typeof limit.value === 'number' ? limit.value : Number(limit.value)
  if (
    !Number.isSafeInteger(normalizedLimit) ||
    normalizedLimit < 1 ||
    normalizedLimit > MAXIMUM_BREAKDOWN_LIMIT
  ) {
    queryProblem.value = '分布条数必须是 1–100 的整数 / Limit must be an integer from 1–100.'
    return null
  }

  const compatibleEvent = metric.value === 'EVENT_COUNT' ? eventType.value : 'PAGE_VIEW'
  if (eventType.value !== compatibleEvent) eventType.value = compatibleEvent
  limit.value = normalizedLimit
  return Object.freeze({
    from: from.value,
    to: to.value,
    locale: locale.value,
    metric: metric.value,
    eventType: compatibleEvent,
    dimension: dimension.value,
    limit: normalizedLimit,
    zone: ANALYTICS_ZONE,
  })
}

function immutableWorkbench(value: AnalyticsWorkbenchDto): AnalyticsWorkbenchDto {
  const summary: AnalyticsSummaryDto = Object.freeze({
    ...value.summary,
    definitions: Object.freeze({ ...value.summary.definitions }),
  })
  const timeseries: readonly AnalyticsPointDto[] = Object.freeze(
    value.timeseries.map((point) => Object.freeze({ ...point })),
  )
  const breakdown: readonly AnalyticsBreakdownItemDto[] = Object.freeze(
    value.breakdown.map((item) => Object.freeze({ ...item })),
  )
  return Object.freeze({ summary, timeseries, breakdown })
}

function displayProblem(cause: unknown): DisplayProblem {
  if (cause instanceof ApiProblem) {
    return { title: cause.body.title, traceId: cause.body.traceId }
  }
  return { title: '无法加载访问统计，请稍后重试 / Unable to load analytics.' }
}

async function loadSnapshot(query: Readonly<AnalyticsWorkbenchQuery>): Promise<void> {
  if (disposed) return
  const operation = ++generation
  requestedQuery.value = query
  loading.value = true
  loadProblem.value = null
  try {
    const loader = props.load ?? defaultLoad
    const result = await loader(query)
    if (disposed || operation !== generation) return
    workbench.value = immutableWorkbench(result)
    committedQuery.value = query
  } catch (cause) {
    if (disposed || operation !== generation) return
    loadProblem.value = displayProblem(cause)
  } finally {
    if (!disposed && operation === generation) loading.value = false
  }
}

function submitQuery(): void {
  const query = safeQuery()
  if (query === null) {
    generation += 1
    loading.value = false
    loadProblem.value = null
    return
  }
  void loadSnapshot(query)
}

async function retryLoad(): Promise<void> {
  const query = requestedQuery.value
  if (query !== null) await loadSnapshot(query)
}

const summaryCards = computed<readonly SummaryCard[]>(() => {
  const value = workbench.value?.summary
  if (value === undefined) return Object.freeze([])
  return Object.freeze([
    {
      key: 'pageViews',
      label: '页面浏览量',
      englishLabel: 'Page views',
      value: value.pageViews,
    },
    {
      key: 'dailyUniqueVisitors',
      label: '匿名日访客',
      englishLabel: 'Daily unique visitors',
      value: value.dailyUniqueVisitors,
    },
    {
      key: 'projectViews',
      label: '项目浏览',
      englishLabel: 'Project views',
      value: value.projectViews,
    },
    {
      key: 'resumeDownloads',
      label: '简历下载',
      englishLabel: 'Resume downloads',
      value: value.resumeDownloads,
    },
    {
      key: 'demoDownloads',
      label: '演示下载',
      englishLabel: 'Demo downloads',
      value: value.demoDownloads,
    },
    {
      key: 'outboundClicks',
      label: '外链点击',
      englishLabel: 'Outbound clicks',
      value: value.outboundClicks,
    },
  ])
})

const definitionEntries = computed(() => {
  const definitions = workbench.value?.summary.definitions
  if (definitions === undefined) return Object.freeze([])
  return Object.freeze([
    { key: 'PV' as const, value: definitions.PV },
    { key: 'DAILY_UV' as const, value: definitions.DAILY_UV },
    { key: 'EVENT_COUNT' as const, value: definitions.EVENT_COUNT },
  ])
})

const numberLocale = computed(() => (committedQuery.value?.locale === 'en' ? 'en-US' : 'zh-CN'))

function formatNumber(value: number): string {
  return new Intl.NumberFormat(numberLocale.value).format(value)
}

function formatWatermark(value: string): string {
  return new Intl.DateTimeFormat(numberLocale.value, {
    timeZone: ANALYTICS_ZONE,
    dateStyle: 'medium',
    timeStyle: 'short',
  }).format(new Date(value))
}

const seriesMax = computed(() =>
  Math.max(1, ...(workbench.value?.timeseries.map((point) => point.value) ?? [0])),
)
const seriesIsZero = computed(
  () =>
    (workbench.value?.timeseries.length ?? 0) > 0 &&
    workbench.value?.timeseries.every((point) => point.value === 0) === true,
)

function compareDimensionValues(left: string, right: string): number {
  if (left === right) return 0
  return left < right ? -1 : 1
}

const breakdownRows = computed<readonly BreakdownRow[]>(() => {
  const sorted = [...(workbench.value?.breakdown ?? [])].sort(
    (left, right) =>
      right.value - left.value ||
      compareDimensionValues(left.dimensionValue, right.dimensionValue),
  )
  const occurrences = new Map<string, number>()
  return Object.freeze(
    sorted.map((item) => {
      const fingerprint = `${item.dimensionValue}\u0000${item.value}`
      const occurrence = occurrences.get(fingerprint) ?? 0
      occurrences.set(fingerprint, occurrence + 1)
      const key = `${fingerprint}\u0000${occurrence}`
      return Object.freeze({
        ...item,
        key,
        domKey: encodeURIComponent(`${item.dimensionValue}|${item.value}|${occurrence}`),
      })
    }),
  )
})

const breakdownMax = computed(() =>
  Math.max(1, ...(breakdownRows.value.map((row) => row.value) ?? [0])),
)

onMounted(() => {
  const query = safeQuery()
  if (query !== null) void loadSnapshot(query)
})

onBeforeUnmount(() => {
  disposed = true
  generation += 1
})
</script>

<template>
  <section aria-labelledby="analytics-title">
    <header class="max-w-3xl">
      <p class="text-sm font-semibold tracking-[0.18em] text-blue-600">ANALYTICS WORKBENCH</p>
      <h1
        id="analytics-title"
        class="mt-3 text-3xl font-semibold tracking-tight text-slate-950 sm:text-4xl"
      >
        访问统计
      </h1>
      <p class="mt-3 text-base leading-7 text-slate-600">
        用同一组查询条件查看总览、逐日趋势和来源分布。所有自然日均按香港时区计算。
      </p>
    </header>

    <form
      data-query-form
      class="mt-8 rounded-2xl border border-slate-200 bg-white p-5 shadow-sm sm:p-6"
      aria-labelledby="analytics-query-title"
      novalidate
      @submit.prevent="submitQuery"
    >
      <div class="flex flex-wrap items-start justify-between gap-3">
        <div>
          <h2 id="analytics-query-title" class="text-lg font-semibold text-slate-950">查询条件</h2>
          <p class="mt-1 text-sm text-slate-500">Query controls</p>
        </div>
        <p
          data-zone
          class="rounded-full border border-blue-100 bg-blue-50 px-3 py-1.5 text-xs font-semibold text-blue-800"
        >
          Asia/Hong_Kong · UTC+08:00
        </p>
      </div>

      <div class="mt-5 grid gap-4 sm:grid-cols-2 xl:grid-cols-4">
        <label class="block text-sm font-medium text-slate-700">
          开始日期 / From
          <input
            v-model="from"
            data-query="from"
            class="mt-2 block w-full rounded-xl border border-slate-300 bg-white px-3 py-2.5 text-slate-950 outline-none focus:border-blue-500 focus:ring-2 focus:ring-blue-100"
            type="date"
          />
        </label>

        <label class="block text-sm font-medium text-slate-700">
          结束日期 / To
          <input
            v-model="to"
            data-query="to"
            class="mt-2 block w-full rounded-xl border border-slate-300 bg-white px-3 py-2.5 text-slate-950 outline-none focus:border-blue-500 focus:ring-2 focus:ring-blue-100"
            type="date"
          />
        </label>

        <label class="block text-sm font-medium text-slate-700">
          说明语言 / Definition language
          <select
            v-model="locale"
            data-query="locale"
            class="mt-2 block w-full rounded-xl border border-slate-300 bg-white px-3 py-2.5 text-slate-950 outline-none focus:border-blue-500 focus:ring-2 focus:ring-blue-100"
          >
            <option value="zh-CN">中文</option>
            <option value="en">English</option>
          </select>
        </label>

        <label class="block text-sm font-medium text-slate-700">
          指标 / Metric
          <select
            v-model="metric"
            data-query="metric"
            class="mt-2 block w-full rounded-xl border border-slate-300 bg-white px-3 py-2.5 text-slate-950 outline-none focus:border-blue-500 focus:ring-2 focus:ring-blue-100"
          >
            <option v-for="option in metricOptions" :key="option.value" :value="option.value">
              {{ option.label }}
            </option>
          </select>
        </label>

        <label class="block text-sm font-medium text-slate-700">
          事件 / Event
          <select
            v-model="eventType"
            data-query="eventType"
            class="mt-2 block w-full rounded-xl border border-slate-300 bg-white px-3 py-2.5 text-slate-950 outline-none focus:border-blue-500 focus:ring-2 focus:ring-blue-100 disabled:cursor-not-allowed disabled:bg-slate-100 disabled:text-slate-500"
            :disabled="metric !== 'EVENT_COUNT'"
          >
            <option v-for="option in eventOptions" :key="option.value" :value="option.value">
              {{ option.label }}
            </option>
          </select>
        </label>

        <label class="block text-sm font-medium text-slate-700">
          分布维度 / Dimension
          <select
            v-model="dimension"
            data-query="dimension"
            class="mt-2 block w-full rounded-xl border border-slate-300 bg-white px-3 py-2.5 text-slate-950 outline-none focus:border-blue-500 focus:ring-2 focus:ring-blue-100"
          >
            <option v-for="option in dimensionOptions" :key="option.value" :value="option.value">
              {{ option.label }}
            </option>
          </select>
        </label>

        <label class="block text-sm font-medium text-slate-700">
          分布条数 / Limit
          <input
            v-model.number="limit"
            data-query="limit"
            class="mt-2 block w-full rounded-xl border border-slate-300 bg-white px-3 py-2.5 text-slate-950 outline-none focus:border-blue-500 focus:ring-2 focus:ring-blue-100"
            type="number"
            min="1"
            max="100"
            step="1"
            inputmode="numeric"
          />
        </label>

        <div class="flex items-end">
          <button
            data-action="run-query"
            class="inline-flex w-full items-center justify-center rounded-xl bg-blue-600 px-4 py-2.5 text-sm font-semibold text-white shadow-sm hover:bg-blue-700 focus:outline-none focus:ring-2 focus:ring-blue-300 focus:ring-offset-2"
            type="submit"
          >
            {{ loading ? '刷新查询 / Refresh again' : '运行查询 / Run query' }}
          </button>
        </div>
      </div>

      <p id="analytics-locale-help" data-locale-help class="mt-4 text-xs leading-5 text-slate-500">
        此选项仅影响指标说明语言，不筛选访问数据 / Definition language does not filter visits.
      </p>
      <p
        v-if="queryProblem"
        data-query-problem
        class="mt-4 rounded-xl border border-amber-200 bg-amber-50 px-4 py-3 text-sm text-amber-900"
        role="alert"
      >
        {{ queryProblem }}
      </p>
    </form>

    <AsyncPanel
      class="mt-6"
      :loading="loading"
      :error-title="loadProblem?.title"
      :trace-id="loadProblem?.traceId"
      :on-retry="retryLoad"
    >
      <div v-if="workbench !== null" class="space-y-6">
        <section aria-labelledby="analytics-summary-title">
          <div class="flex flex-wrap items-end justify-between gap-3">
            <div>
              <h2 id="analytics-summary-title" class="text-xl font-semibold text-slate-950">
                数据总览
              </h2>
              <p class="mt-1 text-sm text-slate-500">Overview</p>
            </div>
            <div class="text-right text-xs leading-5 text-slate-500">
              <p data-zone>{{ workbench.summary.zone }} · UTC+08:00</p>
              <p data-watermark>
                <template v-if="workbench.summary.dataCompleteThrough !== null">
                  数据完整至 / Data complete through
                  <time :datetime="workbench.summary.dataCompleteThrough">
                    {{ formatWatermark(workbench.summary.dataCompleteThrough) }}
                  </time>
                </template>
                <span v-else data-watermark-empty>
                  尚无完整聚合 / No complete aggregation
                </span>
              </p>
            </div>
          </div>

          <div class="mt-4 grid gap-3 sm:grid-cols-2 xl:grid-cols-3">
            <article
              v-for="card in summaryCards"
              :key="card.key"
              data-summary-card
              :data-summary-key="card.key"
              class="rounded-2xl border border-slate-200 bg-white p-5 shadow-sm"
            >
              <p class="text-sm font-medium text-slate-600">{{ card.label }}</p>
              <p class="mt-1 text-xs text-slate-400">{{ card.englishLabel }}</p>
              <data
                class="mt-4 block text-3xl font-semibold tracking-tight text-slate-950"
                :value="card.value"
              >
                {{ formatNumber(card.value) }}
              </data>
            </article>
          </div>
        </section>

        <section
          class="rounded-2xl border border-slate-200 bg-white p-5 shadow-sm sm:p-6"
          aria-labelledby="analytics-definitions-title"
        >
          <h2 id="analytics-definitions-title" class="text-lg font-semibold text-slate-950">
            指标定义 / Metric definitions
          </h2>
          <dl class="mt-4 grid gap-4 lg:grid-cols-3">
            <div
              v-for="entry in definitionEntries"
              :key="entry.key"
              :data-definition-key="entry.key"
              class="rounded-xl bg-slate-50 p-4"
            >
              <dt class="font-semibold text-slate-900">{{ entry.key }}</dt>
              <dd class="mt-2 text-sm leading-6 text-slate-600">{{ entry.value }}</dd>
            </div>
          </dl>
        </section>

        <div class="grid gap-6 xl:grid-cols-[minmax(0,1.4fr)_minmax(18rem,0.6fr)]">
          <section
            class="min-w-0 rounded-2xl border border-slate-200 bg-white p-5 shadow-sm sm:p-6"
            aria-labelledby="analytics-trend-title"
          >
            <div>
              <h2 id="analytics-trend-title" class="text-lg font-semibold text-slate-950">
                逐日趋势 / Daily trend
              </h2>
              <p class="mt-1 text-sm text-slate-500">
                {{ committedQuery?.metric }} · {{ committedQuery?.eventType }}
              </p>
            </div>

            <p
              v-if="seriesIsZero"
              data-timeseries-zero
              class="mt-4 rounded-xl border border-dashed border-slate-300 bg-slate-50 p-4 text-sm text-slate-600"
              role="status"
            >
              所选范围内所有数值均为 0 / All values are zero in this range.
            </p>

            <ol
              v-if="workbench.timeseries.length > 0"
              data-timeseries-visual
              class="mt-5 max-h-96 list-none space-y-3 overflow-y-auto p-0 pr-1"
              aria-hidden="true"
            >
              <li
                v-for="point in workbench.timeseries"
                :key="point.date"
                data-timeseries-visual-row
                :data-date="point.date"
                :data-value="point.value"
                class="grid grid-cols-[6.5rem_minmax(0,1fr)_4rem] items-center gap-3 text-sm"
              >
                <time :datetime="point.date" class="tabular-nums text-slate-600">{{ point.date }}</time>
                <meter
                  class="h-3 w-full accent-blue-600"
                  min="0"
                  :max="seriesMax"
                  :value="point.value"
                />
                <data :value="point.value" class="text-right font-semibold tabular-nums text-slate-900">
                  {{ formatNumber(point.value) }}
                </data>
              </li>
            </ol>
            <p
              v-else
              class="mt-5 rounded-xl border border-dashed border-slate-300 p-5 text-sm text-slate-500"
              role="status"
            >
              暂无趋势数据 / No trend data
            </p>

            <table data-timeseries-table class="sr-only">
              <caption>逐日趋势数据 / Daily trend data</caption>
              <thead>
                <tr>
                  <th scope="col">日期 / Date</th>
                  <th scope="col">数值 / Value</th>
                </tr>
              </thead>
              <tbody>
                <tr v-for="point in workbench.timeseries" :key="`table-${point.date}`">
                  <th scope="row"><time :datetime="point.date">{{ point.date }}</time></th>
                  <td><data :value="point.value">{{ point.value }}</data></td>
                </tr>
              </tbody>
            </table>
          </section>

          <section
            class="min-w-0 rounded-2xl border border-slate-200 bg-white p-5 shadow-sm sm:p-6"
            aria-labelledby="analytics-breakdown-title"
          >
            <h2 id="analytics-breakdown-title" class="text-lg font-semibold text-slate-950">
              分布 / Breakdown
            </h2>
            <p class="mt-1 text-sm text-slate-500">
              {{ committedQuery?.dimension }} · Top {{ committedQuery?.limit }}
            </p>

            <ol v-if="breakdownRows.length > 0" class="mt-5 list-none space-y-4 p-0">
              <li
                v-for="row in breakdownRows"
                :key="row.key"
                data-breakdown-row
                :data-breakdown-key="row.domKey"
                :data-dimension-value="row.dimensionValue"
                :data-value="row.value"
              >
                <div class="flex items-baseline justify-between gap-3 text-sm">
                  <span class="min-w-0 break-words font-medium text-slate-700">
                    {{ row.dimensionValue }}
                  </span>
                  <data :value="row.value" class="shrink-0 font-semibold tabular-nums text-slate-950">
                    {{ formatNumber(row.value) }}
                  </data>
                </div>
                <meter
                  class="mt-2 h-2 w-full accent-blue-600"
                  min="0"
                  :max="breakdownMax"
                  :value="row.value"
                  :aria-label="`${row.dimensionValue}: ${row.value}`"
                />
              </li>
            </ol>
            <p
              v-else
              data-breakdown-empty
              class="mt-5 rounded-xl border border-dashed border-slate-300 bg-slate-50 p-5 text-sm text-slate-600"
              role="status"
            >
              暂无分布数据 / No breakdown data
            </p>
          </section>
        </div>
      </div>

      <p
        v-else
        class="rounded-2xl border border-dashed border-slate-300 bg-white p-8 text-center text-sm text-slate-500"
        role="status"
      >
        尚无已加载的统计快照 / No analytics snapshot loaded
      </p>
    </AsyncPanel>
  </section>
</template>
