import type { Locale } from './public'

export const PUBLIC_INTERACTION_ENDPOINTS = {
  contact: '/api/public/contact',
  events: '/api/public/events',
} as const

export interface ContactRequest {
  name: string
  email: string
  subject: string
  message: string
  website: string
  privacyAccepted: true
}

export interface ContactReceipt { accepted: true }

export const analyticsEventTypes = [
  'PAGE_VIEW', 'PROJECT_VIEW', 'RESUME_DOWNLOAD', 'DEMO_DOWNLOAD', 'OUTBOUND_CLICK',
] as const
export type AnalyticsEventType = (typeof analyticsEventTypes)[number]
export const analyticsPageKeys = [
  'HOME', 'ABOUT', 'WORK', 'ROADMAP', 'CONTACT', 'PRIVACY', 'PROJECT_DETAIL',
] as const
export type AnalyticsPageKey = (typeof analyticsPageKeys)[number]

export interface AnalyticsEvent {
  eventId: string
  type: AnalyticsEventType
  pageKey: AnalyticsPageKey
  projectId: string | null
  referrer: string | null
  locale: Locale
}

export interface AnalyticsEventBatch {
  analyticsConsent: true
  visitorId: string
  sessionId: string
  events: AnalyticsEvent[]
}
