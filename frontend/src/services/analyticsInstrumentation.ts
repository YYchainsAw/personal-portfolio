import {
  analyticsEventTypes,
  analyticsPageKeys,
  type AnalyticsEventType,
  type AnalyticsPageKey,
} from '@/types/interactions'
import type { Locale } from '@/types/public'
import type { AnalyticsTrackInput } from './analyticsClient'

export function delegatedAnalyticsInput(
  event: MouseEvent,
  routeName: unknown,
  locale: Locale,
  currentProjectId: string | null,
  referrer: string | null,
): AnalyticsTrackInput | null {
  if (event.defaultPrevented) return null
  const eventTarget = event.target
  const marker = eventTarget instanceof Element
    ? eventTarget.closest<HTMLElement>('[data-analytics-type]')
    : null
  if (!marker) return null

  const rawType = marker.dataset.analyticsType
  if (!rawType || !analyticsEventTypes.includes(rawType as AnalyticsEventType)) return null

  const fallbackPageKey: AnalyticsPageKey = routeName === 'project'
    ? 'PROJECT_DETAIL'
    : routeName === 'privacy'
      ? 'PRIVACY'
      : 'HOME'
  const rawPageKey = marker.dataset.analyticsPageKey
  if (rawPageKey && !analyticsPageKeys.includes(rawPageKey as AnalyticsPageKey)) return null
  const pageKey = (rawPageKey as AnalyticsPageKey | undefined) ?? fallbackPageKey

  return {
    type: rawType as AnalyticsEventType,
    pageKey,
    projectId: pageKey === 'PROJECT_DETAIL' ? currentProjectId : null,
    locale,
    referrer,
  }
}
