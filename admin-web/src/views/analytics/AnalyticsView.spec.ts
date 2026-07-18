import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { nextTick } from 'vue'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

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

import AnalyticsView from './AnalyticsView.vue'

const apiCalls = vi.hoisted(() => ({
  summary: vi.fn(),
  timeseries: vi.fn(),
  breakdown: vi.fn(),
}))

vi.mock('@/api/operationsApi', () => ({
  ANALYTICS_ZONE: 'Asia/Hong_Kong',
  operationsApi: {
    getAnalyticsSummary: apiCalls.summary,
    getAnalyticsTimeseries: apiCalls.timeseries,
    getAnalyticsBreakdown: apiCalls.breakdown,
  },
}))

interface CompositeQuery {
  readonly from: string
  readonly to: string
  readonly locale: AnalyticsLocale
  readonly metric: AnalyticsMetric
  readonly eventType: AnalyticsEventType
  readonly dimension: AnalyticsDimension
  readonly limit: number
  readonly zone: 'Asia/Hong_Kong'
}

type LoadAnalytics = (query: Readonly<CompositeQuery>) => Promise<AnalyticsWorkbenchDto>

function summary(overrides: Partial<AnalyticsSummaryDto> = {}): AnalyticsSummaryDto {
  return {
    pageViews: 1_234,
    dailyUniqueVisitors: 345,
    projectViews: 67,
    resumeDownloads: 8,
    demoDownloads: 9,
    outboundClicks: 10,
    dataCompleteThrough: '2026-07-17T12:34:56Z',
    zone: 'Asia/Hong_Kong',
    definitions: {
      PV: '页面被浏览的总次数。',
      DAILY_UV: '匿名访问者按香港自然日去重。',
      EVENT_COUNT: '所选事件发生的总次数。',
    },
    ...overrides,
  }
}

function workbench(
  overrides: {
    summary?: AnalyticsSummaryDto
    timeseries?: readonly AnalyticsPointDto[]
    breakdown?: readonly AnalyticsBreakdownItemDto[]
  } = {},
): AnalyticsWorkbenchDto {
  return {
    summary: overrides.summary ?? summary(),
    timeseries:
      overrides.timeseries ??
      Object.freeze([
        { date: '2026-07-16', value: 3 },
        { date: '2026-07-17', value: 7 },
      ]),
    breakdown:
      overrides.breakdown ??
      Object.freeze([
        { dimensionValue: '/projects', value: 12 },
        { dimensionValue: '/', value: 8 },
      ]),
  }
}

function deferred<T>() {
  let resolve!: (value: T | PromiseLike<T>) => void
  let reject!: (reason?: unknown) => void
  const promise = new Promise<T>((resolvePromise, rejectPromise) => {
    resolve = resolvePromise
    reject = rejectPromise
  })
  return { promise, resolve, reject }
}

const mounted: VueWrapper[] = []

function mountAnalytics(load?: LoadAnalytics): VueWrapper {
  const wrapper = mount(AnalyticsView, {
    props: load === undefined ? {} : { load },
  })
  mounted.push(wrapper)
  return wrapper
}

async function mountLoaded(
  load = vi.fn<LoadAnalytics>(async () => workbench()),
): Promise<{ wrapper: VueWrapper; load: ReturnType<typeof vi.fn<LoadAnalytics>> }> {
  const wrapper = mountAnalytics(load)
  await flushPromises()
  return { wrapper, load }
}

async function setRange(wrapper: VueWrapper, from: string, to: string): Promise<void> {
  await wrapper.get<HTMLInputElement>('[data-query="from"]').setValue(from)
  await wrapper.get<HTMLInputElement>('[data-query="to"]').setValue(to)
}

async function submit(wrapper: VueWrapper): Promise<void> {
  await wrapper.get('[data-query-form]').trigger('submit')
  await flushPromises()
}

function inclusiveDays(from: string, to: string): number {
  return (Date.parse(`${to}T00:00:00Z`) - Date.parse(`${from}T00:00:00Z`)) / 86_400_000 + 1
}

beforeEach(() => {
  apiCalls.summary.mockReset().mockResolvedValue(summary())
  apiCalls.timeseries.mockReset().mockResolvedValue(workbench().timeseries)
  apiCalls.breakdown.mockReset().mockResolvedValue(workbench().breakdown)
})

afterEach(() => {
  for (const wrapper of mounted.splice(0)) wrapper.unmount()
  vi.restoreAllMocks()
})

describe('AnalyticsView', () => {
  it('loads all three production datasets in parallel and commits only their complete snapshot', async () => {
    const summaryGate = deferred<AnalyticsSummaryDto>()
    const timeseriesGate = deferred<readonly AnalyticsPointDto[]>()
    const breakdownGate = deferred<readonly AnalyticsBreakdownItemDto[]>()
    apiCalls.summary.mockReturnValueOnce(summaryGate.promise)
    apiCalls.timeseries.mockReturnValueOnce(timeseriesGate.promise)
    apiCalls.breakdown.mockReturnValueOnce(breakdownGate.promise)

    const wrapper = mountAnalytics()
    await nextTick()

    expect(apiCalls.summary).toHaveBeenCalledOnce()
    expect(apiCalls.timeseries).toHaveBeenCalledOnce()
    expect(apiCalls.breakdown).toHaveBeenCalledOnce()

    const summaryQuery = apiCalls.summary.mock.calls[0]![0]
    const timeseriesQuery = apiCalls.timeseries.mock.calls[0]![0]
    const breakdownQuery = apiCalls.breakdown.mock.calls[0]![0]
    expect(summaryQuery).toMatchObject({ locale: 'zh-CN' })
    expect(inclusiveDays(summaryQuery.from, summaryQuery.to)).toBe(30)
    expect(timeseriesQuery).toEqual({
      from: summaryQuery.from,
      to: summaryQuery.to,
      metric: 'PV',
      eventType: 'PAGE_VIEW',
    })
    expect(breakdownQuery).toEqual({
      from: summaryQuery.from,
      to: summaryQuery.to,
      metric: 'PV',
      eventType: 'PAGE_VIEW',
      dimension: 'PAGE',
      limit: 10,
    })

    summaryGate.resolve(summary())
    await flushPromises()
    expect(wrapper.get('[data-async-panel]').attributes('aria-busy')).toBe('true')
    expect(wrapper.find('[data-summary-card]').exists()).toBe(false)

    timeseriesGate.resolve(workbench().timeseries)
    breakdownGate.resolve(workbench().breakdown)
    await flushPromises()

    expect(wrapper.get('[data-async-panel]').attributes('aria-busy')).toBe('false')
    expect(wrapper.findAll('[data-summary-card]')).toHaveLength(6)
    expect(wrapper.text()).toContain('Asia/Hong_Kong')
  })

  it('renders six KPI cards, three definitions, the watermark, and equivalent trend representations', async () => {
    const duplicateBreakdown = Object.freeze([
      { dimensionValue: '同名项目', value: 2 },
      { dimensionValue: '热门项目', value: 9 },
      { dimensionValue: '同名项目', value: 2 },
      { dimensionValue: 'Alpha', value: 2 },
    ])
    const result = workbench({
      timeseries: Object.freeze([
        { date: '2026-07-15', value: 0 },
        { date: '2026-07-16', value: 4 },
        { date: '2026-07-17', value: 9 },
      ]),
      breakdown: duplicateBreakdown,
    })
    const { wrapper } = await mountLoaded(vi.fn<LoadAnalytics>(async () => result))

    const cards = wrapper.findAll('[data-summary-card]')
    expect(cards).toHaveLength(6)
    expect(cards.map((card) => card.attributes('data-summary-key'))).toEqual([
      'pageViews',
      'dailyUniqueVisitors',
      'projectViews',
      'resumeDownloads',
      'demoDownloads',
      'outboundClicks',
    ])
    expect(cards.map((card) => card.get('data').attributes('value'))).toEqual([
      '1234',
      '345',
      '67',
      '8',
      '9',
      '10',
    ])

    expect(wrapper.findAll('[data-definition-key]')).toHaveLength(3)
    expect(wrapper.get('[data-definition-key="PV"]').text()).toContain('页面被浏览的总次数')
    expect(wrapper.get('[data-watermark]').get('time').attributes('datetime')).toBe(
      '2026-07-17T12:34:56Z',
    )
    expect(wrapper.get('[data-zone]').text()).toContain('Asia/Hong_Kong')

    const visualRows = wrapper.findAll('[data-timeseries-visual-row]')
    const tableRows = wrapper.findAll('[data-timeseries-table] tbody tr')
    expect(visualRows).toHaveLength(3)
    expect(tableRows).toHaveLength(3)
    expect(
      visualRows.map((row) => [row.attributes('data-date'), row.attributes('data-value')]),
    ).toEqual(
      tableRows.map((row) => [
        row.get('time').attributes('datetime'),
        row.get('data').attributes('value'),
      ]),
    )
    expect(wrapper.get('[data-timeseries-visual]').attributes('aria-hidden')).toBe('true')
    expect(wrapper.get('[data-timeseries-table]').attributes('aria-hidden')).toBeUndefined()

    const breakdownRows = wrapper.findAll('[data-breakdown-row]')
    expect(breakdownRows.map((row) => row.attributes('data-value'))).toEqual(['9', '2', '2', '2'])
    expect(breakdownRows.map((row) => row.attributes('data-dimension-value'))).toEqual([
      '热门项目',
      'Alpha',
      '同名项目',
      '同名项目',
    ])
    const safeKeys = breakdownRows.map((row) => row.attributes('data-breakdown-key'))
    expect(new Set(safeKeys).size).toBe(breakdownRows.length)
  })

  it('shows bilingual null-watermark, all-zero trend, and empty-breakdown states without hiding zeros', async () => {
    const zeroSummary = summary({
      pageViews: 0,
      dailyUniqueVisitors: 0,
      projectViews: 0,
      resumeDownloads: 0,
      demoDownloads: 0,
      outboundClicks: 0,
      dataCompleteThrough: null,
    })
    const { wrapper } = await mountLoaded(
      vi.fn<LoadAnalytics>(async () =>
        workbench({
          summary: zeroSummary,
          timeseries: Object.freeze([
            { date: '2026-07-16', value: 0 },
            { date: '2026-07-17', value: 0 },
          ]),
          breakdown: Object.freeze([]),
        }),
      ),
    )

    expect(wrapper.findAll('[data-summary-card] data').map((value) => value.text())).toEqual([
      '0',
      '0',
      '0',
      '0',
      '0',
      '0',
    ])
    expect(wrapper.get('[data-watermark-empty]').text()).toContain('尚无完整聚合')
    expect(wrapper.get('[data-watermark-empty]').text()).toContain('No complete aggregation')
    expect(wrapper.get('[data-timeseries-zero]').text()).toContain('All values are zero')
    expect(wrapper.findAll('[data-timeseries-table] tbody tr')).toHaveLength(2)
    expect(wrapper.get('[data-breakdown-empty]').text()).toContain('No breakdown data')
  })

  it('keeps definition locale separate and enforces metric/event compatibility before querying', async () => {
    const { wrapper, load } = await mountLoaded()
    expect(wrapper.get('[data-locale-help]').text()).toContain('仅影响指标说明语言')
    expect(wrapper.get('[data-locale-help]').text()).toContain('does not filter visits')
    expect(wrapper.get<HTMLSelectElement>('[data-query="eventType"]').element.disabled).toBe(true)

    await setRange(wrapper, '2026-07-01', '2026-07-07')
    await wrapper.get<HTMLSelectElement>('[data-query="locale"]').setValue('en')
    await wrapper.get<HTMLSelectElement>('[data-query="metric"]').setValue('EVENT_COUNT')
    await wrapper.get<HTMLSelectElement>('[data-query="eventType"]').setValue('PROJECT_VIEW')
    await wrapper.get<HTMLSelectElement>('[data-query="dimension"]').setValue('PROJECT')
    await wrapper.get<HTMLInputElement>('[data-query="limit"]').setValue('25')
    await submit(wrapper)

    expect(load).toHaveBeenLastCalledWith({
      from: '2026-07-01',
      to: '2026-07-07',
      locale: 'en',
      metric: 'EVENT_COUNT',
      eventType: 'PROJECT_VIEW',
      dimension: 'PROJECT',
      limit: 25,
      zone: 'Asia/Hong_Kong',
    })

    await wrapper.get<HTMLSelectElement>('[data-query="metric"]').setValue('PV')
    await nextTick()
    const eventType = wrapper.get<HTMLSelectElement>('[data-query="eventType"]')
    expect(eventType.element.disabled).toBe(true)
    expect(eventType.element.value).toBe('PAGE_VIEW')
    await submit(wrapper)
    expect(load.mock.calls.at(-1)?.[0]).toMatchObject({ metric: 'PV', eventType: 'PAGE_VIEW' })
  })

  it('rejects incomplete, reversed, overlong, and invalid-limit queries but accepts 366 inclusive days', async () => {
    const { wrapper, load } = await mountLoaded()
    load.mockClear()

    await wrapper.get<HTMLInputElement>('[data-query="from"]').setValue('')
    await submit(wrapper)
    expect(load).not.toHaveBeenCalled()
    expect(wrapper.get('[data-query-problem]').text()).toContain('Choose both dates')

    await setRange(wrapper, '2026-07-03', '2026-07-02')
    await submit(wrapper)
    expect(load).not.toHaveBeenCalled()
    expect(wrapper.get('[data-query-problem]').text()).toContain('不能晚于结束日期')

    await setRange(wrapper, '2025-01-01', '2026-01-02')
    await submit(wrapper)
    expect(load).not.toHaveBeenCalled()
    expect(wrapper.get('[data-query-problem]').text()).toContain('最多包含 366 天')

    await setRange(wrapper, '2025-01-01', '2026-01-01')
    await wrapper.get<HTMLInputElement>('[data-query="limit"]').setValue('101')
    await submit(wrapper)
    expect(load).not.toHaveBeenCalled()
    expect(wrapper.get('[data-query-problem]').text()).toContain('1–100')

    await wrapper.get<HTMLInputElement>('[data-query="limit"]').setValue('100')
    await submit(wrapper)
    expect(load).toHaveBeenCalledOnce()
    expect(load.mock.calls[0]![0]).toMatchObject({
      from: '2025-01-01',
      to: '2026-01-01',
      limit: 100,
    })
    expect(wrapper.find('[data-query-problem]').exists()).toBe(false)
  })

  it('shows allowlisted API failures, retries the same snapshot, and never exposes unknown details', async () => {
    const problem = new ApiProblem({
      type: 'analytics_unavailable',
      title: '访问统计暂时不可用',
      status: 503,
      code: 'ANALYTICS_UNAVAILABLE',
      traceId: 'analytics-trace-7',
    })
    const load = vi
      .fn<LoadAnalytics>()
      .mockRejectedValueOnce(problem)
      .mockResolvedValueOnce(workbench())
    const wrapper = mountAnalytics(load)
    await flushPromises()

    expect(wrapper.get('[role="alert"]').text()).toContain('访问统计暂时不可用')
    expect(wrapper.get('[role="alert"]').text()).toContain('analytics-trace-7')
    const failedQuery = load.mock.calls[0]![0]

    await wrapper.get('[data-async-panel] button').trigger('click')
    await flushPromises()
    expect(load).toHaveBeenCalledTimes(2)
    expect(load.mock.calls[1]![0]).toEqual(failedQuery)
    expect(wrapper.findAll('[data-summary-card]')).toHaveLength(6)

    const privateLoad = vi.fn<LoadAnalytics>().mockRejectedValue(
      new Error('jdbc:postgresql://private-host/internal analytics SQL'),
    )
    const privateWrapper = mountAnalytics(privateLoad)
    await flushPromises()
    expect(privateWrapper.get('[role="alert"]').text()).toContain('无法加载访问统计')
    expect(privateWrapper.text()).not.toContain('private-host')
    expect(privateWrapper.text()).not.toContain('SQL')
  })

  it('allows query B while A is pending and never lets A overwrite the complete B snapshot', async () => {
    const a = deferred<AnalyticsWorkbenchDto>()
    const b = deferred<AnalyticsWorkbenchDto>()
    const load = vi
      .fn<LoadAnalytics>()
      .mockResolvedValueOnce(workbench())
      .mockReturnValueOnce(a.promise)
      .mockReturnValueOnce(b.promise)
    const { wrapper } = await mountLoaded(load)

    await setRange(wrapper, '2026-06-01', '2026-06-02')
    await wrapper.get('[data-query-form]').trigger('submit')
    await setRange(wrapper, '2026-07-01', '2026-07-02')
    await wrapper.get('[data-query-form]').trigger('submit')
    expect(load).toHaveBeenCalledTimes(3)

    b.resolve(
      workbench({
        summary: summary({ pageViews: 222, projectViews: 22 }),
        timeseries: Object.freeze([{ date: '2026-07-01', value: 22 }]),
        breakdown: Object.freeze([{ dimensionValue: 'B', value: 22 }]),
      }),
    )
    await flushPromises()
    expect(wrapper.get('[data-summary-key="pageViews"] data').attributes('value')).toBe('222')
    expect(wrapper.get('[data-timeseries-table] tbody tr').text()).toContain('22')
    expect(wrapper.get('[data-breakdown-row]').attributes('data-dimension-value')).toBe('B')

    a.resolve(
      workbench({
        summary: summary({ pageViews: 111, projectViews: 11 }),
        timeseries: Object.freeze([{ date: '2026-06-01', value: 11 }]),
        breakdown: Object.freeze([{ dimensionValue: 'A', value: 11 }]),
      }),
    )
    await flushPromises()
    expect(wrapper.get('[data-summary-key="pageViews"] data').attributes('value')).toBe('222')
    expect(wrapper.get('[data-timeseries-table] tbody tr').text()).toContain('22')
    expect(wrapper.get('[data-breakdown-row]').attributes('data-dimension-value')).toBe('B')
  })

  it('retires a pending query on unmount without post-unmount updates or warnings', async () => {
    const gate = deferred<AnalyticsWorkbenchDto>()
    const load = vi.fn<LoadAnalytics>(() => gate.promise)
    const warn = vi.spyOn(console, 'warn').mockImplementation(() => undefined)
    const error = vi.spyOn(console, 'error').mockImplementation(() => undefined)
    const wrapper = mountAnalytics(load)
    await nextTick()

    wrapper.unmount()
    gate.resolve(workbench())
    await flushPromises()

    expect(load).toHaveBeenCalledOnce()
    expect(warn).not.toHaveBeenCalled()
    expect(error).not.toHaveBeenCalled()
  })
})
