import { afterEach, expect, it, vi } from 'vitest'
import { CONSENT_KEY, initializeAnalyticsConsent, resetAnalyticsForTests, useAnalyticsConsent } from '@/composables/useAnalyticsConsent'
import { SESSION_KEY, VISITOR_KEY } from '@/services/analyticsClient'

afterEach(() => {
  resetAnalyticsForTests()
  Object.defineProperty(navigator, 'doNotTrack', { configurable: true, value: null })
})

it('defaults off, creates IDs only after explicit accept, and withdraws cleanly', () => {
  initializeAnalyticsConsent()
  const consent = useAnalyticsConsent()
  expect(consent.promptVisible.value).toBe(true)
  expect(localStorage.getItem(VISITOR_KEY)).toBeNull()
  consent.accept()
  expect(localStorage.getItem(CONSENT_KEY)).toBe('granted')
  expect(localStorage.getItem(VISITOR_KEY)).not.toBeNull()
  expect(sessionStorage.getItem(SESSION_KEY)).not.toBeNull()
  consent.withdraw()
  expect(localStorage.getItem(CONSENT_KEY)).toBe('denied')
  expect(localStorage.getItem(VISITOR_KEY)).toBeNull(); expect(sessionStorage.getItem(SESSION_KEY)).toBeNull()
})

it('makes DNT an earlier boundary than consent, crypto, or CSRF', () => {
  Object.defineProperty(navigator, 'doNotTrack', { configurable: true, value: '1' })
  localStorage.setItem(CONSENT_KEY, 'granted'); localStorage.setItem(VISITOR_KEY, '{"version":1,"id":"AAAAAAAAAAAAAAAAAAAAAA","createdAt":0}')
  sessionStorage.setItem(SESSION_KEY, '{"version":1,"id":"BBBBBBBBBBBBBBBBBBBBBB","lastActivityAt":0}')
  const random = vi.spyOn(crypto, 'getRandomValues')
  initializeAnalyticsConsent()
  const consent = useAnalyticsConsent()
  expect(consent.suppressedByDnt.value).toBe(true); expect(consent.promptVisible.value).toBe(false)
  expect(localStorage.getItem(VISITOR_KEY)).toBeNull(); expect(sessionStorage.getItem(SESSION_KEY)).toBeNull()
  expect(random).not.toHaveBeenCalled()
})

it.each([null, 'denied', 'invalid'])('removes stale identifiers while consent is %s', (saved) => {
  if (saved !== null) localStorage.setItem(CONSENT_KEY, saved)
  localStorage.setItem(VISITOR_KEY, '{"version":1,"id":"AAAAAAAAAAAAAAAAAAAAAA","createdAt":0}')
  sessionStorage.setItem(SESSION_KEY, '{"version":1,"id":"BBBBBBBBBBBBBBBBBBBBBB","lastActivityAt":0}')
  initializeAnalyticsConsent()
  expect(localStorage.getItem(VISITOR_KEY)).toBeNull(); expect(sessionStorage.getItem(SESSION_KEY)).toBeNull()
  if (saved === 'invalid') expect(localStorage.getItem(CONSENT_KEY)).toBeNull()
})

it('clears a partially granted state when storage fails', () => {
  initializeAnalyticsConsent()
  const consent = useAnalyticsConsent()
  const write = vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => { throw new DOMException('denied', 'SecurityError') })
  consent.accept()
  expect(consent.choice.value).toBeNull()
  expect(localStorage.getItem(CONSENT_KEY)).toBeNull()
  expect(localStorage.getItem(VISITOR_KEY)).toBeNull()
  write.mockRestore()
})
