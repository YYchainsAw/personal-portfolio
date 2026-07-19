import { expect, test as base } from '@playwright/test'

export interface BrowserSafety {
  expectHttpErrors(entries: string[]): void
}

export const test = base.extend<{ browserErrors: BrowserSafety }>({
  browserErrors: [async ({ page }, use) => {
    const expectedHttpErrors: string[] = []
    const actualHttpErrors: string[] = []
    let networkConsoleErrors = 0
    let unexpectedConsoleErrors = 0
    let pageErrors = 0
    page.on('response', (response) => {
      if (response.status() < 400) return
      const url = new URL(response.url())
      if (url.origin !== 'http://127.0.0.1:4175' || !url.pathname.startsWith('/api/')) return
      actualHttpErrors.push(`${response.request().method()} ${url.pathname} ${response.status()}`)
    })
    page.on('console', (message) => {
      if (message.type() !== 'error') return
      if (message.text().startsWith('Failed to load resource: the server responded with a status of')) networkConsoleErrors += 1
      else unexpectedConsoleErrors += 1
    })
    page.on('pageerror', () => { pageErrors += 1 })
    await use({ expectHttpErrors: (entries) => expectedHttpErrors.push(...entries) })
    expect.soft(actualHttpErrors.sort(), 'HTTP errors must be declared by exact method/path/status').toEqual(expectedHttpErrors.sort())
    expect.soft(networkConsoleErrors, 'each declared failed HTTP response may emit one fixed Chromium network error').toBe(actualHttpErrors.length)
    expect.soft({ unexpectedConsoleErrors, pageErrors }, 'browser emitted an unexpected console/page error (details intentionally not retained)').toEqual({
      unexpectedConsoleErrors: 0, pageErrors: 0,
    })
  }, { auto: true }],
})

export { expect }
