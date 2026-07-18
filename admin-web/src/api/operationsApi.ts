import type { AxiosInstance } from 'axios'

import { ApiProblem } from '@/types/api'
import type {
  AnalyticsBreakdownItemDto,
  AnalyticsBreakdownQuery,
  AnalyticsDateRange,
  AnalyticsDefinitionKey,
  AnalyticsDimension,
  AnalyticsEventType,
  AnalyticsLocale,
  AnalyticsMetric,
  AnalyticsPointDto,
  AnalyticsSummaryDto,
  AnalyticsSummaryQuery,
  AnalyticsTimeseriesQuery,
  EmailDeliveryStatus,
  EmailDeliveryView,
  EmailErrorCategory,
  MessageDetailDto,
  MessageListOptions,
  MessagePageDto,
  MessageStatus,
  MessageSummaryDto,
  UpdateMessageStatusRequest,
} from '@/types/operations'

import { http } from './http'

export const ANALYTICS_ZONE = 'Asia/Hong_Kong' as const

const MESSAGE_PAGE_LIMIT = 30
const MAXIMUM_CURSOR_LENGTH = 116
const JAVA_INT_MAX = 2_147_483_647
const MAXIMUM_ANALYTICS_DAYS = 366
const MAXIMUM_BREAKDOWN_LIMIT = 100
const UUID = /^[0-9a-f]{8}-(?:[0-9a-f]{4}-){3}[0-9a-f]{12}$/i
const CURSOR = /^[A-Za-z0-9_-]+$/
const DATE = /^(\d{4})-(\d{2})-(\d{2})$/
const UTC_INSTANT =
  /^(\d{4})-(\d{2})-(\d{2})T(\d{2}):(\d{2}):(\d{2})(?:\.(\d{1,9}))?Z$/

const MESSAGE_STATUSES = new Set<MessageStatus>(['UNREAD', 'READ', 'ARCHIVED', 'SPAM'])
const EMAIL_STATUSES = new Set<EmailDeliveryStatus>([
  'PENDING',
  'SENDING',
  'SENT',
  'FAILED',
  'DEAD',
  'CANCELED',
])
const EMAIL_ERROR_CATEGORIES = new Set<EmailErrorCategory>([
  'SMTP_AUTHENTICATION_FAILED',
  'SMTP_CONNECTION_FAILED',
  'MESSAGE_PREPARATION_FAILED',
  'SMTP_DELIVERY_FAILED',
  'UNEXPECTED_DELIVERY_FAILURE',
  'DELIVERY_INTERRUPTED',
])
const ANALYTICS_LOCALES = new Set<AnalyticsLocale>(['zh-CN', 'en'])
const ANALYTICS_METRICS = new Set<AnalyticsMetric>(['PV', 'DAILY_UV', 'EVENT_COUNT'])
const ANALYTICS_EVENT_TYPES = new Set<AnalyticsEventType>([
  'PAGE_VIEW',
  'PROJECT_VIEW',
  'RESUME_DOWNLOAD',
  'DEMO_DOWNLOAD',
  'OUTBOUND_CLICK',
])
const ANALYTICS_DIMENSIONS = new Set<AnalyticsDimension>([
  'PAGE',
  'PROJECT',
  'REFERRER',
  'DEVICE',
  'LOCALE',
])
const ANALYTICS_DEFINITION_KEYS: readonly AnalyticsDefinitionKey[] = [
  'PV',
  'DAILY_UV',
  'EVENT_COUNT',
]

const MESSAGE_SUMMARY_KEYS = [
  'id',
  'visitorName',
  'visitorEmail',
  'subject',
  'status',
  'emailStatus',
  'createdAt',
  'version',
] as const
const MESSAGE_DETAIL_KEYS = [
  'id',
  'visitorName',
  'visitorEmail',
  'subject',
  'body',
  'status',
  'email',
  'privacyAcceptedAt',
  'createdAt',
  'updatedAt',
  'version',
] as const
const EMAIL_REQUIRED_KEYS = ['status', 'attempts', 'nextAttemptAt', 'updatedAt'] as const
const EMAIL_OPTIONAL_KEYS = ['sentAt', 'errorCategory'] as const
const ANALYTICS_SUMMARY_KEYS = [
  'pageViews',
  'dailyUniqueVisitors',
  'projectViews',
  'resumeDownloads',
  'demoDownloads',
  'outboundClicks',
  'dataCompleteThrough',
  'zone',
  'definitions',
] as const

type RecordValue = Record<string, unknown>

export interface OperationsApi {
  listMessages(options?: Readonly<MessageListOptions>): Promise<MessagePageDto>
  getMessage(id: string): Promise<MessageDetailDto>
  updateMessageStatus(
    id: string,
    request: Readonly<UpdateMessageStatusRequest>,
  ): Promise<MessageDetailDto>
  retryMessageEmail(id: string): Promise<void>
  deleteMessage(id: string): Promise<void>
  getAnalyticsSummary(query: Readonly<AnalyticsSummaryQuery>): Promise<AnalyticsSummaryDto>
  getAnalyticsTimeseries(
    query: Readonly<AnalyticsTimeseriesQuery>,
  ): Promise<readonly AnalyticsPointDto[]>
  getAnalyticsBreakdown(
    query: Readonly<AnalyticsBreakdownQuery>,
  ): Promise<readonly AnalyticsBreakdownItemDto[]>
}

function isRecord(value: unknown): value is RecordValue {
  if (typeof value !== 'object' || value === null || Array.isArray(value)) return false
  const prototype = Object.getPrototypeOf(value)
  return prototype === Object.prototype || prototype === null
}

function hasExactKeys(value: unknown, keys: readonly string[]): value is RecordValue {
  if (!isRecord(value)) return false
  const actual = Object.keys(value)
  return (
    actual.length === keys.length &&
    keys.every((key) => Object.prototype.hasOwnProperty.call(value, key))
  )
}

function hasRequiredAndOptionalKeys(
  value: unknown,
  required: readonly string[],
  optional: readonly string[],
): value is RecordValue {
  if (!isRecord(value)) return false
  const allowed = new Set([...required, ...optional])
  const actual = Object.keys(value)
  return (
    required.every((key) => Object.prototype.hasOwnProperty.call(value, key)) &&
    actual.every((key) => allowed.has(key))
  )
}

function invalidServerResponse(): ApiProblem {
  return new ApiProblem({
    type: 'invalid_server_response',
    title: '服务器返回了无效的运营数据',
    status: 0,
    code: 'INVALID_OPERATIONS_RESPONSE',
    traceId: 'client',
  })
}

function isUuid(value: unknown): value is string {
  return typeof value === 'string' && UUID.test(value)
}

function requireUuid(value: unknown): string {
  if (!isUuid(value)) throw new TypeError('message id must be a UUID')
  return value
}

function sameUuid(left: string, right: string): boolean {
  return left.toLowerCase() === right.toLowerCase()
}

function isBoundedText(value: unknown, maximumCodePoints: number): value is string {
  return (
    typeof value === 'string' &&
    value.trim().length > 0 &&
    Array.from(value).length <= maximumCodePoints
  )
}

function isNonnegativeSafeInteger(value: unknown): value is number {
  return Number.isSafeInteger(value) && (value as number) >= 0
}

function isJavaNonnegativeInteger(value: unknown): value is number {
  return isNonnegativeSafeInteger(value) && value <= JAVA_INT_MAX
}

function daysInMonth(year: number, month: number): number {
  if (month === 2) {
    const leap = year % 4 === 0 && (year % 100 !== 0 || year % 400 === 0)
    return leap ? 29 : 28
  }
  return [4, 6, 9, 11].includes(month) ? 30 : 31
}

function isCanonicalDate(value: unknown): value is string {
  if (typeof value !== 'string') return false
  const match = DATE.exec(value)
  if (match === null) return false
  const year = Number(match[1])
  const month = Number(match[2])
  const day = Number(match[3])
  return (
    month >= 1 &&
    month <= 12 &&
    day >= 1 &&
    day <= daysInMonth(year, month)
  )
}

function isUtcInstant(value: unknown): value is string {
  if (typeof value !== 'string') return false
  const match = UTC_INSTANT.exec(value)
  if (match === null) return false
  const date = `${match[1]}-${match[2]}-${match[3]}`
  const hour = Number(match[4])
  const minute = Number(match[5])
  const second = Number(match[6])
  return (
    isCanonicalDate(date) &&
    hour >= 0 &&
    hour <= 23 &&
    minute >= 0 &&
    minute <= 59 &&
    second >= 0 &&
    second <= 59 &&
    Number.isFinite(Date.parse(value))
  )
}

function isMessageStatus(value: unknown): value is MessageStatus {
  return typeof value === 'string' && MESSAGE_STATUSES.has(value as MessageStatus)
}

function isEmailStatus(value: unknown): value is EmailDeliveryStatus {
  return typeof value === 'string' && EMAIL_STATUSES.has(value as EmailDeliveryStatus)
}

function isEmailErrorCategory(value: unknown): value is EmailErrorCategory {
  return (
    typeof value === 'string' &&
    EMAIL_ERROR_CATEGORIES.has(value as EmailErrorCategory)
  )
}

function isCursor(value: unknown): value is string {
  return (
    typeof value === 'string' &&
    value.length >= 1 &&
    value.length <= MAXIMUM_CURSOR_LENGTH &&
    CURSOR.test(value)
  )
}

function normalizeMessageSummary(
  value: unknown,
  expectedStatus?: MessageStatus,
): MessageSummaryDto | null {
  if (
    !hasExactKeys(value, MESSAGE_SUMMARY_KEYS) ||
    !isUuid(value.id) ||
    !isBoundedText(value.visitorName, 100) ||
    !isBoundedText(value.visitorEmail, 320) ||
    !isBoundedText(value.subject, 160) ||
    !isMessageStatus(value.status) ||
    (expectedStatus !== undefined && value.status !== expectedStatus) ||
    !isEmailStatus(value.emailStatus) ||
    !isUtcInstant(value.createdAt) ||
    !isJavaNonnegativeInteger(value.version)
  ) {
    return null
  }
  return {
    id: value.id,
    visitorName: value.visitorName,
    visitorEmail: value.visitorEmail,
    subject: value.subject,
    status: value.status,
    emailStatus: value.emailStatus,
    createdAt: value.createdAt,
    version: value.version,
  }
}

function normalizeEmail(value: unknown): EmailDeliveryView | null {
  if (
    !hasRequiredAndOptionalKeys(value, EMAIL_REQUIRED_KEYS, EMAIL_OPTIONAL_KEYS) ||
    !isEmailStatus(value.status) ||
    !isJavaNonnegativeInteger(value.attempts) ||
    !isUtcInstant(value.nextAttemptAt) ||
    !isUtcInstant(value.updatedAt)
  ) {
    return null
  }

  const sentAt = value.sentAt === undefined || value.sentAt === null ? null : value.sentAt
  const errorCategory =
    value.errorCategory === undefined || value.errorCategory === null
      ? null
      : value.errorCategory
  if (
    (sentAt !== null && !isUtcInstant(sentAt)) ||
    (value.status === 'SENT') !== (sentAt !== null) ||
    (errorCategory !== null && !isEmailErrorCategory(errorCategory))
  ) {
    return null
  }

  return {
    status: value.status,
    attempts: value.attempts,
    nextAttemptAt: value.nextAttemptAt,
    sentAt,
    updatedAt: value.updatedAt,
    errorCategory,
  }
}

function normalizeMessageDetail(value: unknown, expectedId?: string): MessageDetailDto | null {
  if (
    !hasExactKeys(value, MESSAGE_DETAIL_KEYS) ||
    !isUuid(value.id) ||
    (expectedId !== undefined && !sameUuid(value.id, expectedId)) ||
    !isBoundedText(value.visitorName, 100) ||
    !isBoundedText(value.visitorEmail, 320) ||
    !isBoundedText(value.subject, 160) ||
    !isBoundedText(value.body, 5_000) ||
    !isMessageStatus(value.status) ||
    !isUtcInstant(value.privacyAcceptedAt) ||
    !isUtcInstant(value.createdAt) ||
    !isUtcInstant(value.updatedAt) ||
    Date.parse(value.privacyAcceptedAt) > Date.parse(value.createdAt) ||
    Date.parse(value.updatedAt) < Date.parse(value.createdAt) ||
    !isJavaNonnegativeInteger(value.version)
  ) {
    return null
  }
  const email = normalizeEmail(value.email)
  if (email === null) return null
  return {
    id: value.id,
    visitorName: value.visitorName,
    visitorEmail: value.visitorEmail,
    subject: value.subject,
    body: value.body,
    status: value.status,
    email,
    privacyAcceptedAt: value.privacyAcceptedAt,
    createdAt: value.createdAt,
    updatedAt: value.updatedAt,
    version: value.version,
  }
}

function requireMessageListOptions(options: unknown): MessageListOptions {
  if (!isRecord(options)) throw new TypeError('message list options are invalid')
  const keys = Object.keys(options)
  if (keys.some((key) => key !== 'status' && key !== 'cursor')) {
    throw new TypeError('message list options are invalid')
  }
  if (options.status !== undefined && !isMessageStatus(options.status)) {
    throw new TypeError('message status filter is invalid')
  }
  if (options.cursor !== undefined && !isCursor(options.cursor)) {
    throw new TypeError('message cursor is invalid')
  }
  return {
    ...(options.status === undefined ? {} : { status: options.status }),
    ...(options.cursor === undefined ? {} : { cursor: options.cursor }),
  }
}

function normalizeMessagePage(
  value: unknown,
  requestedStatus?: MessageStatus,
): MessagePageDto {
  if (!hasRequiredAndOptionalKeys(value, ['items'], ['nextCursor']) || !Array.isArray(value.items)) {
    throw invalidServerResponse()
  }
  if (value.items.length > MESSAGE_PAGE_LIMIT) throw invalidServerResponse()
  const nextCursor =
    value.nextCursor === undefined || value.nextCursor === null ? null : value.nextCursor
  if (
    (nextCursor !== null && !isCursor(nextCursor)) ||
    (nextCursor !== null && value.items.length !== MESSAGE_PAGE_LIMIT)
  ) {
    throw invalidServerResponse()
  }

  const items: MessageSummaryDto[] = []
  const ids = new Set<string>()
  for (const candidate of value.items) {
    const item = normalizeMessageSummary(candidate, requestedStatus)
    if (item === null || ids.has(item.id.toLowerCase())) throw invalidServerResponse()
    ids.add(item.id.toLowerCase())
    items.push(item)
  }
  return { items, nextCursor }
}

function requireStatusRequest(value: unknown): UpdateMessageStatusRequest {
  if (!hasExactKeys(value, ['status', 'version']) || !isMessageStatus(value.status)) {
    throw new TypeError('message status request is invalid')
  }
  if (
    !Number.isSafeInteger(value.version) ||
    (value.version as number) < 0 ||
    (value.version as number) >= JAVA_INT_MAX
  ) {
    throw new RangeError('message mutation version is invalid')
  }
  return { status: value.status, version: value.version as number }
}

function requireEmptyNoContent(response: { status?: unknown; data?: unknown }): void {
  if (
    response.status !== 204 ||
    !(response.data === undefined || response.data === null || response.data === '')
  ) {
    throw invalidServerResponse()
  }
}

function dateEpoch(value: string): number {
  return Date.parse(`${value}T00:00:00Z`)
}

function requireDateRange(value: unknown, expectedKeys: readonly string[]): AnalyticsDateRange {
  if (!hasExactKeys(value, expectedKeys)) throw new TypeError('analytics query is invalid')
  if (!isCanonicalDate(value.from) || !isCanonicalDate(value.to)) {
    throw new TypeError('analytics date is invalid')
  }
  const difference = (dateEpoch(value.to) - dateEpoch(value.from)) / 86_400_000
  if (!Number.isInteger(difference) || difference < 0 || difference + 1 > MAXIMUM_ANALYTICS_DAYS) {
    throw new RangeError('analytics date range is invalid')
  }
  return { from: value.from, to: value.to }
}

function expectedDates(range: AnalyticsDateRange): string[] {
  const dates: string[] = []
  const from = dateEpoch(range.from)
  const to = dateEpoch(range.to)
  for (let instant = from; instant <= to; instant += 86_400_000) {
    dates.push(new Date(instant).toISOString().slice(0, 10))
  }
  return dates
}

function isAnalyticsLocale(value: unknown): value is AnalyticsLocale {
  return typeof value === 'string' && ANALYTICS_LOCALES.has(value as AnalyticsLocale)
}

function isAnalyticsMetric(value: unknown): value is AnalyticsMetric {
  return typeof value === 'string' && ANALYTICS_METRICS.has(value as AnalyticsMetric)
}

function isAnalyticsEventType(value: unknown): value is AnalyticsEventType {
  return (
    typeof value === 'string' &&
    ANALYTICS_EVENT_TYPES.has(value as AnalyticsEventType)
  )
}

function isAnalyticsDimension(value: unknown): value is AnalyticsDimension {
  return (
    typeof value === 'string' &&
    ANALYTICS_DIMENSIONS.has(value as AnalyticsDimension)
  )
}

function requireMetricEvent(value: RecordValue): {
  metric: AnalyticsMetric
  eventType: AnalyticsEventType
} {
  if (!isAnalyticsMetric(value.metric) || !isAnalyticsEventType(value.eventType)) {
    throw new TypeError('analytics metric or event type is invalid')
  }
  if (value.metric !== 'EVENT_COUNT' && value.eventType !== 'PAGE_VIEW') {
    throw new TypeError('analytics metric and event type are incompatible')
  }
  return { metric: value.metric, eventType: value.eventType }
}

function requireSummaryQuery(value: unknown): AnalyticsSummaryQuery {
  const range = requireDateRange(value, ['from', 'to', 'locale'])
  if (!isRecord(value) || !isAnalyticsLocale(value.locale)) {
    throw new TypeError('analytics locale is invalid')
  }
  return { ...range, locale: value.locale }
}

function requireTimeseriesQuery(value: unknown): AnalyticsTimeseriesQuery {
  const range = requireDateRange(value, ['from', 'to', 'metric', 'eventType'])
  if (!isRecord(value)) throw new TypeError('analytics query is invalid')
  return { ...range, ...requireMetricEvent(value) }
}

function requireBreakdownQuery(value: unknown): AnalyticsBreakdownQuery {
  const range = requireDateRange(value, [
    'from',
    'to',
    'metric',
    'eventType',
    'dimension',
    'limit',
  ])
  if (!isRecord(value)) throw new TypeError('analytics query is invalid')
  const metricEvent = requireMetricEvent(value)
  if (!isAnalyticsDimension(value.dimension)) {
    throw new TypeError('analytics dimension is invalid')
  }
  if (
    !Number.isSafeInteger(value.limit) ||
    (value.limit as number) < 1 ||
    (value.limit as number) > MAXIMUM_BREAKDOWN_LIMIT
  ) {
    throw new RangeError('analytics breakdown limit is invalid')
  }
  return {
    ...range,
    ...metricEvent,
    dimension: value.dimension,
    limit: value.limit as number,
  }
}

function normalizeDefinitions(value: unknown): Readonly<Record<AnalyticsDefinitionKey, string>> | null {
  if (!hasExactKeys(value, ANALYTICS_DEFINITION_KEYS)) return null
  if (ANALYTICS_DEFINITION_KEYS.some((key) => !isBoundedText(value[key], 2_000))) return null
  return {
    PV: value.PV as string,
    DAILY_UV: value.DAILY_UV as string,
    EVENT_COUNT: value.EVENT_COUNT as string,
  }
}

function normalizeAnalyticsSummary(value: unknown): AnalyticsSummaryDto {
  if (!hasExactKeys(value, ANALYTICS_SUMMARY_KEYS)) throw invalidServerResponse()
  const counts = [
    value.pageViews,
    value.dailyUniqueVisitors,
    value.projectViews,
    value.resumeDownloads,
    value.demoDownloads,
    value.outboundClicks,
  ]
  const definitions = normalizeDefinitions(value.definitions)
  if (
    counts.some((count) => !isNonnegativeSafeInteger(count)) ||
    !(value.dataCompleteThrough === null || isUtcInstant(value.dataCompleteThrough)) ||
    value.zone !== ANALYTICS_ZONE ||
    definitions === null
  ) {
    throw invalidServerResponse()
  }
  return {
    pageViews: value.pageViews as number,
    dailyUniqueVisitors: value.dailyUniqueVisitors as number,
    projectViews: value.projectViews as number,
    resumeDownloads: value.resumeDownloads as number,
    demoDownloads: value.demoDownloads as number,
    outboundClicks: value.outboundClicks as number,
    dataCompleteThrough: value.dataCompleteThrough as string | null,
    zone: ANALYTICS_ZONE,
    definitions,
  }
}

function normalizeTimeseries(
  value: unknown,
  query: AnalyticsTimeseriesQuery,
): readonly AnalyticsPointDto[] {
  if (!Array.isArray(value)) throw invalidServerResponse()
  const dates = expectedDates(query)
  if (value.length !== dates.length) throw invalidServerResponse()
  return value.map((candidate, index) => {
    const date = dates[index]
    if (
      date === undefined ||
      !hasExactKeys(candidate, ['date', 'value']) ||
      candidate.date !== date ||
      !isNonnegativeSafeInteger(candidate.value)
    ) {
      throw invalidServerResponse()
    }
    return { date, value: candidate.value }
  })
}

function compareDimensionValues(left: string, right: string): number {
  if (left === right) return 0
  return left < right ? -1 : 1
}

function normalizeBreakdown(
  value: unknown,
  query: AnalyticsBreakdownQuery,
): readonly AnalyticsBreakdownItemDto[] {
  if (!Array.isArray(value) || value.length > query.limit) throw invalidServerResponse()
  const items = value.map((candidate) => {
    if (
      !hasExactKeys(candidate, ['dimensionValue', 'value']) ||
      !isBoundedText(candidate.dimensionValue, 1_000) ||
      !isNonnegativeSafeInteger(candidate.value)
    ) {
      throw invalidServerResponse()
    }
    return { dimensionValue: candidate.dimensionValue, value: candidate.value }
  })
  for (let index = 1; index < items.length; index += 1) {
    const previous = items[index - 1]
    const current = items[index]
    if (
      previous === undefined ||
      current === undefined ||
      previous.value < current.value ||
      (previous.value === current.value &&
        compareDimensionValues(previous.dimensionValue, current.dimensionValue) > 0)
    ) {
      throw invalidServerResponse()
    }
  }
  return items
}

export function createOperationsApi(client: AxiosInstance): OperationsApi {
  return {
    async listMessages(options = {}) {
      const normalized = requireMessageListOptions(options)
      const response = await client.get<unknown>('/api/admin/messages', {
        params: {
          ...(normalized.status === undefined ? {} : { status: normalized.status }),
          ...(normalized.cursor === undefined ? {} : { cursor: normalized.cursor }),
          limit: MESSAGE_PAGE_LIMIT,
        },
      })
      return normalizeMessagePage(response.data, normalized.status)
    },

    async getMessage(id) {
      const messageId = requireUuid(id)
      const response = await client.get<unknown>(
        `/api/admin/messages/${encodeURIComponent(messageId)}`,
      )
      const result = normalizeMessageDetail(response.data, messageId)
      if (result === null) throw invalidServerResponse()
      return result
    },

    async updateMessageStatus(id, request) {
      const messageId = requireUuid(id)
      const normalized = requireStatusRequest(request)
      const response = await client.patch<unknown>(
        `/api/admin/messages/${encodeURIComponent(messageId)}/status`,
        normalized,
      )
      const result = normalizeMessageDetail(response.data, messageId)
      if (
        result === null ||
        result.status !== normalized.status ||
        result.version !== normalized.version + 1
      ) {
        throw invalidServerResponse()
      }
      return result
    },

    async retryMessageEmail(id) {
      const messageId = requireUuid(id)
      const response = await client.post<unknown>(
        `/api/admin/messages/${encodeURIComponent(messageId)}/email/retry`,
      )
      requireEmptyNoContent(response)
    },

    async deleteMessage(id) {
      const messageId = requireUuid(id)
      const response = await client.delete<unknown>(
        `/api/admin/messages/${encodeURIComponent(messageId)}`,
      )
      requireEmptyNoContent(response)
    },

    async getAnalyticsSummary(query) {
      const normalized = requireSummaryQuery(query)
      const response = await client.get<unknown>('/api/admin/analytics/summary', {
        params: { ...normalized, zone: ANALYTICS_ZONE },
      })
      return normalizeAnalyticsSummary(response.data)
    },

    async getAnalyticsTimeseries(query) {
      const normalized = requireTimeseriesQuery(query)
      const response = await client.get<unknown>('/api/admin/analytics/timeseries', {
        params: { ...normalized, zone: ANALYTICS_ZONE },
      })
      return normalizeTimeseries(response.data, normalized)
    },

    async getAnalyticsBreakdown(query) {
      const normalized = requireBreakdownQuery(query)
      const response = await client.get<unknown>('/api/admin/analytics/breakdown', {
        params: { ...normalized, zone: ANALYTICS_ZONE },
      })
      return normalizeBreakdown(response.data, normalized)
    },
  }
}

export const operationsApi = createOperationsApi(http)
