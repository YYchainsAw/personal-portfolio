import { afterEach, describe, expect, it, vi } from 'vitest'

import type {
  AnalyticsBreakdownQuery,
  AnalyticsSummaryQuery,
  AnalyticsTimeseriesQuery,
  MessageListOptions,
} from '@/types/operations'

import { http } from './http'
import { operationsApi } from './operationsApi'

const messageId = (value: number): string =>
  `94000000-0000-4000-8000-${value.toString().padStart(12, '0')}`

function summary(id = messageId(1), overrides: Record<string, unknown> = {}) {
  return {
    id,
    visitorName: 'Visitor <script>',
    visitorEmail: 'visitor@example.com',
    subject: '<b>Subject</b>',
    status: 'UNREAD',
    emailStatus: 'FAILED',
    createdAt: '2026-07-18T01:02:03.123456Z',
    version: 3,
    ...overrides,
  }
}

function detail(id = messageId(1), overrides: Record<string, unknown> = {}) {
  return {
    id,
    visitorName: 'Visitor <script>',
    visitorEmail: 'visitor@example.com',
    subject: '<b>Subject</b>',
    body: '<script>alert(1)</script>',
    status: 'UNREAD',
    email: {
      status: 'FAILED',
      attempts: 2,
      nextAttemptAt: '2026-07-18T01:04:03Z',
      updatedAt: '2026-07-18T01:03:03Z',
      errorCategory: 'SMTP_DELIVERY_FAILED',
    },
    privacyAcceptedAt: '2026-07-18T01:01:03Z',
    createdAt: '2026-07-18T01:02:03Z',
    updatedAt: '2026-07-18T01:03:03Z',
    version: 3,
    ...overrides,
  }
}

function analyticsSummary(overrides: Record<string, unknown> = {}) {
  return {
    pageViews: 120,
    dailyUniqueVisitors: 45,
    projectViews: 30,
    resumeDownloads: 2,
    demoDownloads: 4,
    outboundClicks: 8,
    dataCompleteThrough: '2026-07-14T00:55:00Z',
    zone: 'Asia/Hong_Kong',
    definitions: {
      PV: '页面浏览次数',
      DAILY_UV: '匿名日 UV 按自然日求和',
      EVENT_COUNT: '事件次数',
    },
    ...overrides,
  }
}

const summaryQuery: AnalyticsSummaryQuery = {
  from: '2026-07-01',
  to: '2026-07-14',
  locale: 'zh-CN',
}

const timeseriesQuery: AnalyticsTimeseriesQuery = {
  from: '2026-07-01',
  to: '2026-07-03',
  metric: 'PV',
  eventType: 'PAGE_VIEW',
}

const breakdownQuery: AnalyticsBreakdownQuery = {
  from: '2026-07-01',
  to: '2026-07-14',
  metric: 'EVENT_COUNT',
  eventType: 'PROJECT_VIEW',
  dimension: 'PROJECT',
  limit: 10,
}

afterEach(() => {
  vi.restoreAllMocks()
})

describe('operationsApi message inbox', () => {
  it('lists the first 30 messages and normalizes an omitted NON_NULL cursor to null', async () => {
    const wire = { items: [summary()] }
    const get = vi.spyOn(http, 'get').mockResolvedValue({ data: wire } as never)

    const result = await operationsApi.listMessages()

    expect(get).toHaveBeenCalledWith('/api/admin/messages', { params: { limit: 30 } })
    expect(result).toEqual({ items: wire.items, nextCursor: null })
    expect(result.items).not.toBe(wire.items)
    expect(result.items[0]).not.toBe(wire.items[0])
  })

  it('treats the cursor as opaque and sends only the exact status/cursor/limit query', async () => {
    const cursor = 'opaque_cursor_which_the_browser_must_not_decode'
    const options: MessageListOptions = { status: 'ARCHIVED', cursor }
    const get = vi.spyOn(http, 'get').mockResolvedValue({
      data: {
        items: Array.from({ length: 30 }, (_, index) =>
          summary(messageId(index + 1), { status: 'ARCHIVED' }),
        ),
        nextCursor: 'next_cursor',
      },
    } as never)

    const result = await operationsApi.listMessages(options)

    expect(get).toHaveBeenCalledWith('/api/admin/messages', {
      params: { status: 'ARCHIVED', cursor, limit: 30 },
    })
    expect(result.nextCursor).toBe('next_cursor')
  })

  it('rejects invalid list inputs without transport or cursor decoding', async () => {
    const get = vi.spyOn(http, 'get')
    for (const options of [
      { status: 'unread' },
      { cursor: '' },
      { cursor: 'not+base64url' },
      { cursor: 'a'.repeat(117) },
    ] as MessageListOptions[]) {
      await expect(operationsApi.listMessages(options)).rejects.toThrow(TypeError)
    }
    expect(get).not.toHaveBeenCalled()
  })

  it.each([
    ['an extra root field', { items: [], internal: true }],
    ['a null items list', { items: null }],
    ['more than the fixed limit', { items: Array.from({ length: 31 }, (_, i) => summary(messageId(i + 1))) }],
    ['duplicate ids', { items: [summary(), summary()] }],
    ['an invalid cursor', { items: [], nextCursor: 'bad+cursor' }],
    ['a cursor on a short page', { items: [summary()], nextCursor: 'next_cursor' }],
    ['a leaking summary field', { items: [{ ...summary(), body: 'secret' }] }],
    ['a mismatched filtered status', { items: [summary(undefined, { status: 'READ' })] }],
  ])('rejects a list response containing %s', async (_name, response) => {
    vi.spyOn(http, 'get').mockResolvedValue({ data: response } as never)
    await expect(
      operationsApi.listMessages(
        _name === 'a mismatched filtered status' ? { status: 'UNREAD' } : undefined,
      ),
    ).rejects.toMatchObject({ body: { code: 'INVALID_OPERATIONS_RESPONSE' } })
  })

  it('gets a complete escaped detail and normalizes omitted nullable email fields', async () => {
    const id = messageId(2)
    const wire = detail(id, {
      email: {
        status: 'PENDING',
        attempts: 0,
        nextAttemptAt: '2026-07-18T01:04:03Z',
        updatedAt: '2026-07-18T01:03:03Z',
      },
    })
    const get = vi.spyOn(http, 'get').mockResolvedValue({ data: wire } as never)

    const result = await operationsApi.getMessage(id)

    expect(get).toHaveBeenCalledWith(`/api/admin/messages/${id}`)
    expect(result.email.sentAt).toBeNull()
    expect(result.email.errorCategory).toBeNull()
    expect(result.body).toBe('<script>alert(1)</script>')
    expect(result).not.toBe(wire)
    expect(result.email).not.toBe(wire.email)
  })

  it('accepts SENT only with sentAt and the exact safe email state', async () => {
    const id = messageId(3)
    const wire = detail(id, {
      email: {
        status: 'SENT',
        attempts: 2,
        nextAttemptAt: '2026-07-18T01:04:03Z',
        sentAt: '2026-07-18T01:05:03Z',
        updatedAt: '2026-07-18T01:05:03Z',
      },
    })
    vi.spyOn(http, 'get').mockResolvedValue({ data: wire } as never)

    await expect(operationsApi.getMessage(id)).resolves.toMatchObject({
      email: { status: 'SENT', sentAt: '2026-07-18T01:05:03Z', errorCategory: null },
    })
  })

  it.each([
    ['a mismatched id', (id: string) => detail(messageId(9))],
    ['an extra detail field', (id: string) => ({ ...detail(id), rawIp: '127.0.0.1' })],
    ['an extra email field', (id: string) => detail(id, { email: { ...(detail(id).email as object), lastErrorSummary: 'secret' } })],
    ['a missing next attempt', (id: string) => {
      const email = { ...(detail(id).email as Record<string, unknown>) }
      delete email.nextAttemptAt
      return detail(id, { email })
    }],
    ['an unsafe error category', (id: string) => detail(id, { email: { ...(detail(id).email as object), errorCategory: 'SMTP_UNAVAILABLE' } })],
    ['sent without sentAt', (id: string) => detail(id, { email: { ...(detail(id).email as object), status: 'SENT' } })],
    ['non-sent with sentAt', (id: string) => detail(id, { email: { ...(detail(id).email as object), sentAt: '2026-07-18T01:05:03Z' } })],
    ['an invalid version', (id: string) => detail(id, { version: -1 })],
    ['an invalid timestamp order', (id: string) => detail(id, { updatedAt: '2026-07-18T01:00:00Z' })],
  ])('rejects detail with %s', async (_name, build) => {
    const id = messageId(4)
    vi.spyOn(http, 'get').mockResolvedValue({ data: build(id) } as never)
    await expect(operationsApi.getMessage(id)).rejects.toMatchObject({
      body: { code: 'INVALID_OPERATIONS_RESPONSE' },
    })
  })

  it('sends the loaded version and accepts only the exact full version-plus-one detail', async () => {
    const id = messageId(5)
    const updated = detail(id, { status: 'READ', version: 4 })
    const patch = vi.spyOn(http, 'patch').mockResolvedValue({ data: updated } as never)

    const result = await operationsApi.updateMessageStatus(id, { status: 'READ', version: 3 })

    expect(patch).toHaveBeenCalledWith(`/api/admin/messages/${id}/status`, {
      status: 'READ',
      version: 3,
    })
    expect(result).toEqual({
      ...updated,
      email: { ...(updated.email as object), sentAt: null },
    })
  })

  it('accepts the exact Java int version boundary without overflowing status mutation', async () => {
    const id = messageId(51)
    const maximumVersion = 2_147_483_647
    vi.spyOn(http, 'get').mockResolvedValue({
      data: { items: [summary(id, { version: maximumVersion })] },
    } as never)

    await expect(operationsApi.listMessages()).resolves.toMatchObject({
      items: [{ id, version: maximumVersion }],
    })

    const patch = vi.spyOn(http, 'patch').mockResolvedValue({
      data: detail(id, { status: 'READ', version: maximumVersion }),
    } as never)
    await expect(
      operationsApi.updateMessageStatus(id, {
        status: 'READ',
        version: maximumVersion - 1,
      }),
    ).resolves.toMatchObject({ id, status: 'READ', version: maximumVersion })

    expect(patch).toHaveBeenCalledWith(`/api/admin/messages/${id}/status`, {
      status: 'READ',
      version: maximumVersion - 1,
    })
  })

  it('rejects invalid status mutations and unproven responses', async () => {
    const id = messageId(6)
    const patch = vi.spyOn(http, 'patch')
    await expect(
      operationsApi.updateMessageStatus(id, { status: 'read' as never, version: 3 }),
    ).rejects.toThrow(TypeError)
    await expect(
      operationsApi.updateMessageStatus(id, { status: 'READ', version: -1 }),
    ).rejects.toThrow(RangeError)
    expect(patch).not.toHaveBeenCalled()

    patch.mockResolvedValueOnce({ data: detail(id, { status: 'READ', version: 3 }) } as never)
    await expect(
      operationsApi.updateMessageStatus(id, { status: 'READ', version: 3 }),
    ).rejects.toMatchObject({ body: { code: 'INVALID_OPERATIONS_RESPONSE' } })
  })

  it('uses the exact retry and hard-delete endpoints and verifies empty 204 responses', async () => {
    const id = messageId(7)
    const post = vi.spyOn(http, 'post').mockResolvedValue({ status: 204, data: '' } as never)
    const remove = vi.spyOn(http, 'delete').mockResolvedValue({ status: 204 } as never)

    await expect(operationsApi.retryMessageEmail(id)).resolves.toBeUndefined()
    await expect(operationsApi.deleteMessage(id)).resolves.toBeUndefined()

    expect(post).toHaveBeenCalledWith(`/api/admin/messages/${id}/email/retry`)
    expect(remove).toHaveBeenCalledWith(`/api/admin/messages/${id}`)
  })

  it('rejects malformed 204 responses and invalid ids', async () => {
    const id = messageId(8)
    const post = vi.spyOn(http, 'post').mockResolvedValue({ status: 200, data: {} } as never)
    const remove = vi.spyOn(http, 'delete').mockResolvedValue({ status: 204, data: { deleted: true } } as never)

    await expect(operationsApi.retryMessageEmail(id)).rejects.toMatchObject({
      body: { code: 'INVALID_OPERATIONS_RESPONSE' },
    })
    await expect(operationsApi.deleteMessage(id)).rejects.toMatchObject({
      body: { code: 'INVALID_OPERATIONS_RESPONSE' },
    })
    await expect(operationsApi.getMessage('../secret')).rejects.toThrow(TypeError)
  })

  it('never retries PATCH, POST, or DELETE mutations', async () => {
    const failure = new Error('network failed')
    const patch = vi.spyOn(http, 'patch').mockRejectedValue(failure)
    const post = vi.spyOn(http, 'post').mockRejectedValue(failure)
    const remove = vi.spyOn(http, 'delete').mockRejectedValue(failure)
    const id = messageId(10)

    await expect(
      operationsApi.updateMessageStatus(id, { status: 'SPAM', version: 3 }),
    ).rejects.toBe(failure)
    await expect(operationsApi.retryMessageEmail(id)).rejects.toBe(failure)
    await expect(operationsApi.deleteMessage(id)).rejects.toBe(failure)
    expect(patch).toHaveBeenCalledOnce()
    expect(post).toHaveBeenCalledOnce()
    expect(remove).toHaveBeenCalledOnce()
  })
})

describe('operationsApi analytics reports', () => {
  it('loads the exact localized summary query and preserves explicit null completeness', async () => {
    const wire = analyticsSummary({ dataCompleteThrough: null })
    const get = vi.spyOn(http, 'get').mockResolvedValue({ data: wire } as never)

    const result = await operationsApi.getAnalyticsSummary(summaryQuery)

    expect(get).toHaveBeenCalledWith('/api/admin/analytics/summary', {
      params: { ...summaryQuery, zone: 'Asia/Hong_Kong' },
    })
    expect(result).toEqual(wire)
    expect(result).not.toBe(wire)
    expect(result.definitions).not.toBe(wire.definitions)
  })

  it.each([
    ['an extra field', analyticsSummary({ rawEvents: [] })],
    ['an unsafe count', analyticsSummary({ pageViews: Number.MAX_SAFE_INTEGER + 1 })],
    ['a negative count', analyticsSummary({ outboundClicks: -1 })],
    ['a wrong zone', analyticsSummary({ zone: 'UTC' })],
    ['a missing definition', analyticsSummary({ definitions: { PV: 'PV', DAILY_UV: 'UV' } })],
    ['an extra definition', analyticsSummary({ definitions: { PV: 'PV', DAILY_UV: 'UV', EVENT_COUNT: 'Events', SECRET: 'x' } })],
    ['an invalid watermark', analyticsSummary({ dataCompleteThrough: '2026-07-14' })],
  ])('rejects a summary response containing %s', async (_name, response) => {
    vi.spyOn(http, 'get').mockResolvedValue({ data: response } as never)
    await expect(operationsApi.getAnalyticsSummary(summaryQuery)).rejects.toMatchObject({
      body: { code: 'INVALID_OPERATIONS_RESPONSE' },
    })
  })

  it('loads and validates the exact inclusive zero-filled timeseries', async () => {
    const wire = [
      { date: '2026-07-01', value: 10 },
      { date: '2026-07-02', value: 0 },
      { date: '2026-07-03', value: 5 },
    ]
    const get = vi.spyOn(http, 'get').mockResolvedValue({ data: wire } as never)

    const result = await operationsApi.getAnalyticsTimeseries(timeseriesQuery)

    expect(get).toHaveBeenCalledWith('/api/admin/analytics/timeseries', {
      params: { ...timeseriesQuery, zone: 'Asia/Hong_Kong' },
    })
    expect(result).toEqual(wire)
    expect(result).not.toBe(wire)
  })

  it.each([
    ['a missing day', [{ date: '2026-07-01', value: 1 }, { date: '2026-07-03', value: 1 }]],
    ['an out-of-order day', [{ date: '2026-07-01', value: 1 }, { date: '2026-07-03', value: 1 }, { date: '2026-07-02', value: 1 }]],
    ['an extra point field', [{ date: '2026-07-01', value: 1, visitorKey: 'secret' }, { date: '2026-07-02', value: 0 }, { date: '2026-07-03', value: 0 }]],
    ['a negative value', [{ date: '2026-07-01', value: -1 }, { date: '2026-07-02', value: 0 }, { date: '2026-07-03', value: 0 }]],
  ])('rejects a timeseries with %s', async (_name, response) => {
    vi.spyOn(http, 'get').mockResolvedValue({ data: response } as never)
    await expect(operationsApi.getAnalyticsTimeseries(timeseriesQuery)).rejects.toMatchObject({
      body: { code: 'INVALID_OPERATIONS_RESPONSE' },
    })
  })

  it('loads the exact ranked breakdown query and clones its stable response', async () => {
    const wire = [
      { dimensionValue: 'Alpha project', value: 4 },
      { dimensionValue: 'Zulu project', value: 4 },
      { dimensionValue: messageId(11), value: 2 },
    ]
    const get = vi.spyOn(http, 'get').mockResolvedValue({ data: wire } as never)

    const result = await operationsApi.getAnalyticsBreakdown(breakdownQuery)

    expect(get).toHaveBeenCalledWith('/api/admin/analytics/breakdown', {
      params: { ...breakdownQuery, zone: 'Asia/Hong_Kong' },
    })
    expect(result).toEqual(wire)
    expect(result).not.toBe(wire)
    expect(result[0]).not.toBe(wire[0])
  })

  it.each([
    ['more rows than the requested limit', Array.from({ length: 11 }, (_, index) => ({ dimensionValue: `p${index}`, value: 11 - index }))],
    ['an unstable value order', [{ dimensionValue: 'a', value: 1 }, { dimensionValue: 'b', value: 2 }]],
    ['an unstable tie order', [{ dimensionValue: 'Zulu', value: 2 }, { dimensionValue: 'Alpha', value: 2 }]],
    ['an empty dimension', [{ dimensionValue: '', value: 1 }]],
    ['an extra row field', [{ dimensionValue: 'a', value: 1, projectId: messageId(1) }]],
  ])('rejects a breakdown with %s', async (_name, response) => {
    vi.spyOn(http, 'get').mockResolvedValue({ data: response } as never)
    await expect(operationsApi.getAnalyticsBreakdown(breakdownQuery)).rejects.toMatchObject({
      body: { code: 'INVALID_OPERATIONS_RESPONSE' },
    })
  })

  it('validates canonical dates, inclusive 366-day ranges, enums, compatibility, and limit locally', async () => {
    const get = vi.spyOn(http, 'get')
    const invalidRanges = [
      { ...summaryQuery, from: '2026-7-01' },
      { ...summaryQuery, from: '2026-02-30' },
      { ...summaryQuery, from: '2026-07-15', to: '2026-07-14' },
      { ...summaryQuery, from: '2025-07-14', to: '2026-07-15' },
      { ...summaryQuery, locale: 'ZH-cn' },
    ] as AnalyticsSummaryQuery[]
    for (const query of invalidRanges) {
      await expect(operationsApi.getAnalyticsSummary(query)).rejects.toThrow()
    }
    await expect(
      operationsApi.getAnalyticsTimeseries({
        ...timeseriesQuery,
        metric: 'PV',
        eventType: 'PROJECT_VIEW',
      }),
    ).rejects.toThrow(TypeError)
    await expect(
      operationsApi.getAnalyticsTimeseries({
        ...timeseriesQuery,
        metric: 'pv' as never,
      }),
    ).rejects.toThrow(TypeError)
    await expect(
      operationsApi.getAnalyticsBreakdown({ ...breakdownQuery, dimension: 'ALL' as never }),
    ).rejects.toThrow(TypeError)
    await expect(
      operationsApi.getAnalyticsBreakdown({ ...breakdownQuery, limit: 101 }),
    ).rejects.toThrow(RangeError)
    expect(get).not.toHaveBeenCalled()
  })

  it('accepts the maximum inclusive date range and every EVENT_COUNT event type', async () => {
    const get = vi.spyOn(http, 'get').mockResolvedValue({ data: [] } as never)
    for (const eventType of [
      'PAGE_VIEW',
      'PROJECT_VIEW',
      'RESUME_DOWNLOAD',
      'DEMO_DOWNLOAD',
      'OUTBOUND_CLICK',
    ] as const) {
      await expect(
        operationsApi.getAnalyticsBreakdown({
          from: '2025-07-16',
          to: '2026-07-16',
          metric: 'EVENT_COUNT',
          eventType,
          dimension: 'DEVICE',
          limit: 100,
        }),
      ).resolves.toEqual([])
    }
    expect(get).toHaveBeenCalledTimes(5)
  })
})
