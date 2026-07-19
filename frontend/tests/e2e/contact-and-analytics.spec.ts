import type { Locator, Page } from '@playwright/test'
import { expect, test } from './e2eTest'
import { card } from '../fixtures/publicSnapshots'
import { installPublishedApi } from './mockPublishedApi'

test.use({ trace: 'off', screenshot: 'off', video: 'off' })
test.afterEach(async ({ page }) => { await page.close() })

async function rejectAnalytics(page: Page) {
  const reject = page.getByRole('button', { name: /Reject|拒绝/u })
  if (await reject.isVisible()) await reject.click()
}

async function fillContact(page: Page) {
  await page.locator('input[name="name"]').fill('Synthetic Tester')
  await page.locator('input[name="email"]').fill('synthetic@example.test')
  await page.locator('input[name="subject"]').fill('Synthetic portfolio enquiry')
  await page.locator('textarea[name="message"]').fill('Synthetic browser-contract message; contains no personal data.')
  await page.locator('input[name="privacyAccepted"]').check()
}

async function submitContact(page: Page) {
  await page.locator('form.contact-form').evaluate((form: HTMLFormElement) => form.requestSubmit())
}

async function clickWithoutNavigation(locator: Locator) {
  await locator.evaluate((element) => {
    window.addEventListener('click', (event) => event.preventDefault(), { once: true })
    ;(element as HTMLElement).click()
  })
}

for (const locale of ['zh-CN', 'en'] as const) {
  test(`exposes the exact ${locale} contact controls and blocks overlong or unconsented input locally`, async ({ page }) => {
    const state = await installPublishedApi(page)
    await page.goto(`/${locale}`)
    await rejectAnalytics(page)

    const labels = locale === 'zh-CN'
      ? ['姓名', '邮箱', '主题', '留言']
      : ['Name', 'Email', 'Subject', 'Message']
    for (const label of labels) await expect(page.getByLabel(label, { exact: true })).toBeVisible()

    const form = page.locator('form.contact-form')
    await expect(form.locator('[name="name"]')).toHaveAttribute('maxlength', '100')
    await expect(form.locator('[name="email"]')).toHaveAttribute('maxlength', '320')
    await expect(form.locator('[name="subject"]')).toHaveAttribute('maxlength', '160')
    await expect(form.locator('[name="message"]')).toHaveAttribute('maxlength', '5000')
    await expect(form.locator('.honeypot')).toHaveAttribute('aria-hidden', 'true')
    await expect(form.locator('[name="website"]')).toHaveAttribute('tabindex', '-1')
    await expect(form.locator('[name="privacyAccepted"]')).toHaveAttribute('required', '')
    await expect(form.locator('.retention-copy')).toContainText(locale === 'zh-CN'
      ? '留言最长保留一年，除非更早删除'
      : 'Messages are retained for one year unless deleted earlier')

    await form.locator('[name="name"]').evaluate((element: HTMLInputElement) => {
      element.value = 'x'.repeat(101)
      element.dispatchEvent(new Event('input', { bubbles: true }))
    })
    await form.locator('[name="email"]').fill('synthetic@example.test')
    await form.locator('[name="subject"]').fill('Synthetic subject')
    await form.locator('[name="message"]').fill('Synthetic message')
    await submitContact(page)
    await expect(page.getByRole('alert')).toBeFocused()
    await expect(form.locator('[name="name"]')).toHaveAttribute('aria-invalid', 'true')
    await expect(form.locator('[name="privacyAccepted"]')).toHaveAttribute('aria-invalid', 'true')
    expect(state.csrfGets).toBe(0)
    expect(state.contactPosts).toBe(0)
  })
}

test('defaults analytics off, keeps Reject request-free, and validates contact before CSRF', async ({ page }) => {
  const state = await installPublishedApi(page)
  await page.goto('/en')
  await expect(page.getByRole('region', { name: 'Analytics privacy choice' })).toBeVisible()
  expect(await page.evaluate(() => ({
    visitor: localStorage.getItem('portfolio.analytics.visitor.v1'),
    session: sessionStorage.getItem('portfolio.analytics.session.v1'),
  }))).toEqual({ visitor: null, session: null })
  expect(state.csrfGets).toBe(0)
  expect(state.eventPosts).toBe(0)

  await submitContact(page)
  await expect(page.getByRole('alert')).toBeFocused()
  expect(state.csrfGets).toBe(0)
  expect(state.contactPosts).toBe(0)

  await page.getByRole('button', { name: 'Reject' }).click()
  await page.getByRole('link', { name: card('en').title }).click()
  await page.waitForTimeout(2_100)
  expect(state.csrfGets).toBe(0)
  expect(state.eventPosts).toBe(0)
})

test('performs exact CSRF then contact POST and shows only the safe receipt', async ({ page }) => {
  const state = await installPublishedApi(page)
  await page.goto('/en')
  await rejectAnalytics(page)
  await fillContact(page)
  await submitContact(page)

  await expect(page.getByText('Your message was safely accepted.')).toBeVisible()
  expect(state.contactPosts).toBe(1)
  expect(state.contactContracts).toEqual([true])
  expect(state.order.filter((entry) => entry.includes('/auth/csrf') || entry.includes('/contact'))).toEqual([
    'GET /api/admin/auth/csrf 200', 'POST /api/public/contact 202',
  ])
})

test('never sends contact without a valid token and preserves synthetic input', async ({ page }) => {
  const state = await installPublishedApi(page, { csrfMode: 'malformed' })
  await page.goto('/en')
  await rejectAnalytics(page)
  await fillContact(page)
  await submitContact(page)

  await expect(page.getByText(/input is preserved for retry/u)).toBeVisible()
  await expect(page.locator('textarea[name="message"]')).toHaveValue('Synthetic browser-contract message; contains no personal data.')
  expect(state.csrfGets).toBe(1)
  expect(state.contactPosts).toBe(0)
})

test('handles 400, 429, and 500 safely before succeeding through explicit retry', async ({ page, browserErrors }) => {
  browserErrors.expectHttpErrors([
    'POST /api/public/contact 400', 'POST /api/public/contact 429', 'POST /api/public/contact 500',
  ])
  const state = await installPublishedApi(page, { contactStatuses: [400, 429, 500, 202] })
  await page.goto('/en')
  await rejectAnalytics(page)
  await fillContact(page)

  await submitContact(page)
  await expect(page.getByRole('alert')).toBeFocused()
  await expect(page.getByRole('alert')).toContainText('Please check')

  await submitContact(page)
  await expect(page.getByText(/Too many requests/u)).toContainText('60s')
  await expect(page.locator('textarea[name="message"]')).toHaveValue('Synthetic browser-contract message; contains no personal data.')

  await submitContact(page)
  await expect(page.getByText(/input is preserved for retry/u)).toBeVisible()
  await expect(page.getByRole('button', { name: 'Retry' })).toBeVisible()
  await submitContact(page)
  await expect(page.getByText('Your message was safely accepted.')).toBeVisible()
  expect(state.contactContracts).toEqual([true, true, true, true])
})

test('binds a safe 422 field error without echoing the submitted value', async ({ page, browserErrors }) => {
  browserErrors.expectHttpErrors(['POST /api/public/contact 422'])
  const state = await installPublishedApi(page, { contactStatuses: [422] })
  await page.goto('/en')
  await rejectAnalytics(page)
  await fillContact(page)
  await submitContact(page)

  await expect(page.getByRole('alert')).toBeFocused()
  await expect(page.locator('#contact-email-error')).toHaveText('Synthetic email validation error')
  expect(state.contactContracts).toEqual([true])
})

test('enforces DNT before storage, crypto, consent UI, CSRF, or events', async ({ context, page }) => {
  await context.addInitScript(() => {
    Object.defineProperty(navigator, 'doNotTrack', { configurable: true, value: '1' })
    localStorage.setItem('portfolio.analytics.consent.v1', 'granted')
    localStorage.setItem('portfolio.analytics.visitor.v1', JSON.stringify({ version: 1, id: 'AAAAAAAAAAAAAAAAAAAAAA', createdAt: 0 }))
    sessionStorage.setItem('portfolio.analytics.session.v1', JSON.stringify({ version: 1, id: 'BBBBBBBBBBBBBBBBBBBBBB', lastActivityAt: 0 }))
    const original = crypto.getRandomValues.bind(crypto)
    let calls = 0
    Object.defineProperty(globalThis, '__analyticsCryptoCalls', { configurable: true, get: () => calls })
    Object.defineProperty(crypto, 'getRandomValues', { configurable: true, value: <T extends ArrayBufferView | null>(array: T) => { calls += 1; return original(array) } })
  })
  const state = await installPublishedApi(page)
  await page.goto('/en')

  expect(await page.evaluate(() => ({
    visitor: localStorage.getItem('portfolio.analytics.visitor.v1'),
    session: sessionStorage.getItem('portfolio.analytics.session.v1'),
    calls: (globalThis as typeof globalThis & { __analyticsCryptoCalls: number }).__analyticsCryptoCalls,
  }))).toEqual({ visitor: null, session: null, calls: 0 })
  await expect(page.getByRole('region', { name: 'Analytics privacy choice' })).toHaveCount(0)
  await expect(page.getByRole('button', { name: /Enable analytics|Withdraw analytics/u })).toHaveCount(0)
  await page.getByRole('link', { name: card('en').title }).click()
  await page.waitForTimeout(2_100)
  expect(state.csrfGets).toBe(0)
  expect(state.eventPosts).toBe(0)
})

test('sends only mapped consented actions, reuses memory CSRF, and withdraws cleanly', async ({ page }) => {
  const state = await installPublishedApi(page)
  await page.goto('/en')
  await fillContact(page)
  await submitContact(page)
  await expect(page.getByText('Your message was safely accepted.')).toBeVisible()
  expect(state.csrfGets).toBe(1)
  await page.getByRole('button', { name: 'Accept' }).click()
  expect(await page.evaluate(() => {
    const visitor = JSON.parse(localStorage.getItem('portfolio.analytics.visitor.v1') || '{}')
    const session = JSON.parse(sessionStorage.getItem('portfolio.analytics.session.v1') || '{}')
    return /^[A-Za-z0-9_-]{22}$/u.test(visitor.id) && /^[A-Za-z0-9_-]{22}$/u.test(session.id)
  })).toBe(true)

  await clickWithoutNavigation(page.locator('[data-analytics-type="RESUME_DOWNLOAD"]'))
  await clickWithoutNavigation(page.locator('a[data-analytics-page-key="CONTACT"]'))
  await page.getByRole('link', { name: card('en').title }).click()
  await expect(page.getByRole('heading', { level: 1 })).toContainText('UE Study')
  await clickWithoutNavigation(page.locator('[data-media-source]').first())
  await clickWithoutNavigation(page.locator('[data-content-block][data-order="8"] a'))
  await clickWithoutNavigation(page.locator('[data-analytics-type="DEMO_DOWNLOAD"]'))

  await expect.poll(() => state.eventPosts, { timeout: 8_000 }).toBeGreaterThan(0)
  expect(state.eventContracts.every(Boolean)).toBe(true)
  expect(state.flags).toMatchObject({
    sawProjectView: true, sawResumeDownload: true, sawDemoDownload: true,
    sawContactOutbound: true, sawProjectOutbound: true,
  })
  expect(state.eventCounts).toMatchObject({
    PROJECT_VIEW: 1, RESUME_DOWNLOAD: 1, DEMO_DOWNLOAD: 1, OUTBOUND_CLICK: 3,
  })
  expect(state.csrfGets).toBe(1)
  expect(state.order.indexOf('GET /api/admin/auth/csrf 200')).toBeLessThan(state.order.indexOf('POST /api/public/events 204'))

  const postsBeforeWithdraw = state.eventPosts
  await page.getByRole('button', { name: 'Withdraw analytics consent' }).click()
  expect(await page.evaluate(() => ({
    visitor: localStorage.getItem('portfolio.analytics.visitor.v1'),
    session: sessionStorage.getItem('portfolio.analytics.session.v1'),
  }))).toEqual({ visitor: null, session: null })
  await page.getByRole('button', { name: '中文' }).click()
  await page.waitForTimeout(2_100)
  expect(state.eventPosts).toBe(postsBeforeWithdraw)
})

test('splits the twenty-first browser action into a second bounded batch', async ({ page }) => {
  const state = await installPublishedApi(page)
  await page.goto('/en')
  await page.getByRole('button', { name: 'Accept' }).click()
  await page.evaluate(() => {
    const action = document.createElement('button')
    action.type = 'button'
    action.dataset.analyticsType = 'OUTBOUND_CLICK'
    action.dataset.analyticsPageKey = 'HOME'
    document.body.append(action)
    for (let index = 0; index < 21; index += 1) action.click()
  })

  expect(await page.evaluate(() => localStorage.getItem('portfolio.analytics.consent.v1'))).toBe('granted')
  await expect.poll(() => state.csrfGets, { timeout: 8_000 }).toBe(1)
  await expect.poll(() => state.eventPosts, { timeout: 8_000 }).toBe(2)
  expect(state.eventBatchSizes).toEqual([20, 1])
  expect(state.eventContracts).toEqual([true, true])
})

test('withdraws while CSRF acquisition is pending and sends no analytics POST afterwards', async ({ page }) => {
  const state = await installPublishedApi(page, { delayCsrfMs: 1_500 })
  await page.goto('/en')
  await page.getByRole('button', { name: 'Accept' }).click()
  await page.evaluate(() => {
    const action = document.createElement('button')
    action.type = 'button'
    action.dataset.analyticsType = 'OUTBOUND_CLICK'
    action.dataset.analyticsPageKey = 'HOME'
    document.body.append(action)
    for (let index = 0; index < 20; index += 1) action.click()
  })

  await expect.poll(() => state.csrfGets, { timeout: 8_000 }).toBe(1)
  await page.getByRole('button', { name: 'Withdraw analytics consent' }).click()
  await page.waitForTimeout(1_700)
  expect(state.eventPosts).toBe(0)
  expect(await page.evaluate(() => ({
    visitor: localStorage.getItem('portfolio.analytics.visitor.v1'),
    session: sessionStorage.getItem('portfolio.analytics.session.v1'),
  }))).toEqual({ visitor: null, session: null })
})

test('bounds analytics token failure to three acquisitions and zero event POSTs', async ({ page, browserErrors }) => {
  browserErrors.expectHttpErrors([
    'GET /api/admin/auth/csrf 503', 'GET /api/admin/auth/csrf 503', 'GET /api/admin/auth/csrf 503',
  ])
  const state = await installPublishedApi(page, { csrfMode: 'error' })
  await page.goto('/en')
  await page.getByRole('button', { name: 'Accept' }).click()
  await page.evaluate(() => {
    const action = document.createElement('button')
    action.type = 'button'
    action.dataset.analyticsType = 'OUTBOUND_CLICK'
    action.dataset.analyticsPageKey = 'HOME'
    document.body.append(action)
    action.click()
  })

  await expect.poll(() => state.csrfGets, { timeout: 9_000 }).toBe(3)
  expect(state.eventPosts).toBe(0)
})

test('invalidates an exact CSRF_INVALID event response and retries once with a fresh token', async ({ page, browserErrors }) => {
  browserErrors.expectHttpErrors(['POST /api/public/events 403'])
  const state = await installPublishedApi(page, { eventStatuses: [403, 204] })
  await page.goto('/en')
  await page.getByRole('button', { name: 'Accept' }).click()
  await page.evaluate(() => {
    const action = document.createElement('button')
    action.type = 'button'
    action.dataset.analyticsType = 'OUTBOUND_CLICK'
    action.dataset.analyticsPageKey = 'HOME'
    document.body.append(action)
    action.click()
  })

  await expect.poll(() => state.eventPosts, { timeout: 8_000 }).toBe(2)
  expect(state.csrfGets).toBe(2)
  expect(state.eventContracts).toEqual([true, true])
  expect(state.order.filter((entry) => entry.includes('/auth/csrf') || entry.includes('/events'))).toEqual([
    'GET /api/admin/auth/csrf 200', 'POST /api/public/events 403',
    'GET /api/admin/auth/csrf 200', 'POST /api/public/events 204',
  ])
})

test('rotates visitor and session records at their exact browser boundaries', async ({ context, page }) => {
  await context.addInitScript(() => {
    Object.defineProperty(globalThis, '__portfolioNow', { configurable: true, writable: true, value: 1_000_000 })
    Date.now = () => (globalThis as typeof globalThis & { __portfolioNow: number }).__portfolioNow
  })
  await installPublishedApi(page)
  await page.goto('/en')
  await page.getByRole('button', { name: 'Accept' }).click()
  const action = page.locator('a[data-analytics-page-key="CONTACT"]')

  await page.evaluate(() => {
    const scope = globalThis as typeof globalThis & { __portfolioNow: number; __firstVisitor: string; __firstSession: string }
    scope.__firstVisitor = JSON.parse(localStorage.getItem('portfolio.analytics.visitor.v1')!).id
    scope.__firstSession = JSON.parse(sessionStorage.getItem('portfolio.analytics.session.v1')!).id
    scope.__portfolioNow += 30 * 24 * 60 * 60 * 1000 - 1
    const session = JSON.parse(sessionStorage.getItem('portfolio.analytics.session.v1')!)
    session.lastActivityAt = scope.__portfolioNow
    sessionStorage.setItem('portfolio.analytics.session.v1', JSON.stringify(session))
  })
  await clickWithoutNavigation(action)
  expect(await page.evaluate(() => JSON.parse(localStorage.getItem('portfolio.analytics.visitor.v1')!).id
    === (globalThis as typeof globalThis & { __firstVisitor: string }).__firstVisitor)).toBe(true)

  await page.evaluate(() => {
    const scope = globalThis as typeof globalThis & { __portfolioNow: number }
    scope.__portfolioNow += 1
    const session = JSON.parse(sessionStorage.getItem('portfolio.analytics.session.v1')!)
    session.lastActivityAt = scope.__portfolioNow
    sessionStorage.setItem('portfolio.analytics.session.v1', JSON.stringify(session))
  })
  await clickWithoutNavigation(action)
  expect(await page.evaluate(() => JSON.parse(localStorage.getItem('portfolio.analytics.visitor.v1')!).id
    !== (globalThis as typeof globalThis & { __firstVisitor: string }).__firstVisitor)).toBe(true)

  await page.evaluate(() => {
    const scope = globalThis as typeof globalThis & { __portfolioNow: number; __sessionBase: number; __firstSession: string }
    scope.__sessionBase = scope.__portfolioNow
    scope.__firstSession = JSON.parse(sessionStorage.getItem('portfolio.analytics.session.v1')!).id
    const visitor = JSON.parse(localStorage.getItem('portfolio.analytics.visitor.v1')!)
    visitor.createdAt = scope.__portfolioNow
    localStorage.setItem('portfolio.analytics.visitor.v1', JSON.stringify(visitor))
    scope.__portfolioNow = scope.__sessionBase + 30 * 60 * 1000 - 1
  })
  await clickWithoutNavigation(action)
  expect(await page.evaluate(() => JSON.parse(sessionStorage.getItem('portfolio.analytics.session.v1')!).id
    === (globalThis as typeof globalThis & { __firstSession: string }).__firstSession)).toBe(true)

  await page.evaluate(() => {
    const scope = globalThis as typeof globalThis & { __portfolioNow: number; __sessionBase: number }
    const session = JSON.parse(sessionStorage.getItem('portfolio.analytics.session.v1')!)
    session.lastActivityAt = scope.__sessionBase
    sessionStorage.setItem('portfolio.analytics.session.v1', JSON.stringify(session))
    scope.__portfolioNow = scope.__sessionBase + 30 * 60 * 1000
  })
  await clickWithoutNavigation(action)
  expect(await page.evaluate(() => JSON.parse(sessionStorage.getItem('portfolio.analytics.session.v1')!).id
    !== (globalThis as typeof globalThis & { __firstSession: string }).__firstSession)).toBe(true)
})
