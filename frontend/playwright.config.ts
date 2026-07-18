import { defineConfig, devices } from '@playwright/test'

// Playwright's failure helper otherwise writes an AI-oriented DOM snapshot.
// Browser tests exercise contact fields, so keep failure artifacts free of any
// form DOM/body content even when a test fails before its own teardown runs.
process.env.PLAYWRIGHT_NO_COPY_PROMPT = '1'

export default defineConfig({
  testDir: './tests/e2e',
  fullyParallel: false,
  workers: 1,
  forbidOnly: true,
  retries: 0,
  reporter: [['line']],
  projects: [
    { name: 'desktop', use: { ...devices['Desktop Chrome'], viewport: { width: 1440, height: 1000 } } },
    { name: 'mobile', use: { ...devices['iPhone 13'] } },
  ],
  use: {
    baseURL: 'http://127.0.0.1:4175',
    trace: 'retain-on-failure',
    screenshot: 'only-on-failure',
    video: 'off',
  },
  webServer: {
    command: 'npm run dev -- --host 127.0.0.1 --port 4175',
    url: 'http://127.0.0.1:4175/zh-CN',
    reuseExistingServer: false,
    timeout: 120_000,
  },
})
