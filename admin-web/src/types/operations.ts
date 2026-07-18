import type { CursorPage } from './api'

export type MessageStatus = 'UNREAD' | 'READ' | 'ARCHIVED' | 'SPAM'

export type EmailDeliveryStatus =
  | 'PENDING'
  | 'SENDING'
  | 'SENT'
  | 'FAILED'
  | 'DEAD'
  | 'CANCELED'

export type EmailErrorCategory =
  | 'SMTP_AUTHENTICATION_FAILED'
  | 'SMTP_CONNECTION_FAILED'
  | 'MESSAGE_PREPARATION_FAILED'
  | 'SMTP_DELIVERY_FAILED'
  | 'UNEXPECTED_DELIVERY_FAILURE'
  | 'DELIVERY_INTERRUPTED'

export interface MessageSummaryDto {
  readonly id: string
  readonly visitorName: string
  readonly visitorEmail: string
  readonly subject: string
  readonly status: MessageStatus
  readonly emailStatus: EmailDeliveryStatus
  readonly createdAt: string
  readonly version: number
}

export type MessagePageDto = CursorPage<MessageSummaryDto>

export interface EmailDeliveryView {
  readonly status: EmailDeliveryStatus
  readonly attempts: number
  readonly nextAttemptAt: string
  readonly sentAt: string | null
  readonly updatedAt: string
  readonly errorCategory: EmailErrorCategory | null
}

export interface MessageDetailDto {
  readonly id: string
  readonly visitorName: string
  readonly visitorEmail: string
  readonly subject: string
  readonly body: string
  readonly status: MessageStatus
  readonly email: EmailDeliveryView
  readonly privacyAcceptedAt: string
  readonly createdAt: string
  readonly updatedAt: string
  readonly version: number
}

export interface MessageListOptions {
  readonly status?: MessageStatus
  readonly cursor?: string
}

export interface UpdateMessageStatusRequest {
  readonly status: MessageStatus
  readonly version: number
}

export type AnalyticsLocale = 'zh-CN' | 'en'
export type AnalyticsMetric = 'PV' | 'DAILY_UV' | 'EVENT_COUNT'
export type AnalyticsEventType =
  | 'PAGE_VIEW'
  | 'PROJECT_VIEW'
  | 'RESUME_DOWNLOAD'
  | 'DEMO_DOWNLOAD'
  | 'OUTBOUND_CLICK'
export type AnalyticsDimension = 'PAGE' | 'PROJECT' | 'REFERRER' | 'DEVICE' | 'LOCALE'
export type AnalyticsDefinitionKey = AnalyticsMetric

export interface AnalyticsDateRange {
  readonly from: string
  readonly to: string
}

export interface AnalyticsSummaryQuery extends AnalyticsDateRange {
  /** Selects the language of metric definitions; it does not filter collected data. */
  readonly locale: AnalyticsLocale
}

export interface AnalyticsTimeseriesQuery extends AnalyticsDateRange {
  readonly metric: AnalyticsMetric
  readonly eventType: AnalyticsEventType
}

export interface AnalyticsBreakdownQuery extends AnalyticsTimeseriesQuery {
  readonly dimension: AnalyticsDimension
  readonly limit: number
}

export interface AnalyticsSummaryDto {
  readonly pageViews: number
  readonly dailyUniqueVisitors: number
  readonly projectViews: number
  readonly resumeDownloads: number
  readonly demoDownloads: number
  readonly outboundClicks: number
  readonly dataCompleteThrough: string | null
  readonly zone: 'Asia/Hong_Kong'
  readonly definitions: Readonly<Record<AnalyticsDefinitionKey, string>>
}

export interface AnalyticsPointDto {
  readonly date: string
  readonly value: number
}

export interface AnalyticsBreakdownItemDto {
  /** PROJECT breakdowns contain a resolved title when one is available, not a stable project id. */
  readonly dimensionValue: string
  readonly value: number
}

export interface AnalyticsWorkbenchDto {
  readonly summary: AnalyticsSummaryDto
  readonly timeseries: readonly AnalyticsPointDto[]
  readonly breakdown: readonly AnalyticsBreakdownItemDto[]
}
