import { expect, it } from 'vitest'
import { delegatedAnalyticsInput } from '@/services/analyticsInstrumentation'

it('uses a closed explicit CONTACT marker instead of misclassifying a footer social click as HOME', () => {
  const anchor = document.createElement('a')
  anchor.dataset.analyticsType = 'OUTBOUND_CLICK'
  anchor.dataset.analyticsPageKey = 'CONTACT'
  const child = document.createElement('span')
  anchor.append(child)
  const event = new MouseEvent('click', { bubbles: true, cancelable: true })
  child.dispatchEvent(event)

  expect(delegatedAnalyticsInput(event, 'home', 'en', null, null)).toEqual({
    type: 'OUTBOUND_CLICK', pageKey: 'CONTACT', projectId: null, locale: 'en', referrer: null,
  })
  expect(event.defaultPrevented).toBe(false)
})

it('derives project context only from route state and drops invalid markers', () => {
  const anchor = document.createElement('a')
  anchor.dataset.analyticsType = 'DEMO_DOWNLOAD'
  const event = new MouseEvent('click', { bubbles: true, cancelable: true })
  anchor.dispatchEvent(event)
  expect(delegatedAnalyticsInput(event, 'project', 'zh-CN', '00000000-0000-0000-0000-000000000001', 'https://referrer.example/')).toEqual({
    type: 'DEMO_DOWNLOAD', pageKey: 'PROJECT_DETAIL', projectId: '00000000-0000-0000-0000-000000000001',
    locale: 'zh-CN', referrer: 'https://referrer.example/',
  })
  anchor.dataset.analyticsPageKey = 'ARBITRARY'
  expect(delegatedAnalyticsInput(event, 'project', 'zh-CN', null, null)).toBeNull()
  anchor.dataset.analyticsPageKey = 'PROJECT_DETAIL'
  anchor.dataset.analyticsType = 'CLICK'
  expect(delegatedAnalyticsInput(event, 'project', 'zh-CN', null, null)).toBeNull()
})

it('drops an already-cancelled click and never cancels a normal marker click itself', () => {
  const anchor = document.createElement('a')
  anchor.href = 'https://example.com/'
  anchor.dataset.analyticsType = 'OUTBOUND_CLICK'
  anchor.dataset.analyticsPageKey = 'HOME'

  let normalInput: ReturnType<typeof delegatedAnalyticsInput> = null
  let preventedByInstrumentation = true
  anchor.addEventListener('click', (event) => {
    normalInput = delegatedAnalyticsInput(event, 'home', 'en', null, null)
    preventedByInstrumentation = event.defaultPrevented
    event.preventDefault() // test-only: suppress jsdom's external navigation after observing the handler
  }, { once: true })
  const normal = new MouseEvent('click', { bubbles: true, cancelable: true })
  anchor.dispatchEvent(normal)
  expect(normalInput).not.toBeNull()
  expect(preventedByInstrumentation).toBe(false)

  anchor.addEventListener('click', (event) => event.preventDefault(), { once: true })
  let cancelledInput: ReturnType<typeof delegatedAnalyticsInput> = null
  anchor.addEventListener('click', (event) => { cancelledInput = delegatedAnalyticsInput(event, 'home', 'en', null, null) }, { once: true })
  const cancelled = new MouseEvent('click', { bubbles: true, cancelable: true })
  anchor.dispatchEvent(cancelled)
  expect(cancelled.defaultPrevented).toBe(true)
  expect(cancelledInput).toBeNull()
})
