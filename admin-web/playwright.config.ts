import { defineConfig, devices } from '@playwright/test'

// Playwright 1.58 otherwise writes an automatic error-context.md page snapshot
// on failure, even when traces and screenshots are disabled. Admin pages can
// contain one-time credentials, visitor messages, and session identifiers.
process.env.PLAYWRIGHT_NO_COPY_PROMPT = '1'

export default defineConfig({
  testDir: './tests/e2e',
  outputDir: './test-results',
  fullyParallel: false,
  forbidOnly: true,
  retries: 0,
  workers: 1,
  reporter: [['list']],
  use: {
    baseURL: 'http://127.0.0.1:4174',
    trace: 'off',
    screenshot: 'off',
    video: 'off',
  },
  projects: [
    {
      name: 'chromium',
      use: { ...devices['Desktop Chrome'] },
    },
  ],
  webServer: {
    command: 'npm run dev -- --host 127.0.0.1 --port 4174',
    url: 'http://127.0.0.1:4174/admin/',
    reuseExistingServer: false,
  },
})
