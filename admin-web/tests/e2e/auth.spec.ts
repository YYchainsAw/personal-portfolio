import { expect, test, type Page } from '@playwright/test'

import { installAdminApi } from './mockAdminApi'

test.use({ trace: 'off', screenshot: 'off', video: 'off' })

type MockAdminApi = Awaited<ReturnType<typeof installAdminApi>>

function requestsFor(api: MockAdminApi, path: string) {
  return api.requests.filter((request) => request.path === path)
}

async function completePasswordAndTotp(page: Page): Promise<void> {
  await page.getByLabel('用户名', { exact: true }).fill('admin')
  await page.getByLabel('密码', { exact: true }).fill('correct horse battery staple')
  await page.getByRole('button', { name: '继续', exact: true }).click()

  await expect(page).toHaveURL(/\/admin\/totp\?redirect=/)
  await page.getByLabel('动态验证码', { exact: true }).fill('123456')
  await page.getByRole('button', { name: '验证并登录', exact: true }).click()
}

test('safe deep-link password and TOTP login reaches the protected dashboard', async ({
  page,
}) => {
  const api = await installAdminApi(page)

  await page.goto('/admin/dashboard?source=e2e#overview')

  await expect(page).toHaveURL(/\/admin\/login\?redirect=/)
  await expect(page.getByRole('heading', { name: '登录管理后台' })).toBeVisible()

  await completePasswordAndTotp(page)

  await expect(page).toHaveURL(/\/admin\/dashboard\?source=e2e#overview$/)
  await expect(page.getByRole('heading', { name: '仪表盘', exact: true })).toBeVisible()

  await expect.poll(() => requestsFor(api, '/api/admin/auth/password').length).toBe(1)
  await expect.poll(() => requestsFor(api, '/api/admin/auth/second-factor').length).toBe(1)

  const csrfRequestIndex = api.requests.findIndex(
    (request) => request.method === 'GET' && request.path === '/api/admin/auth/csrf',
  )
  const passwordRequestIndex = api.requests.findIndex(
    (request) => request.method === 'POST' && request.path === '/api/admin/auth/password',
  )
  const factorRequestIndex = api.requests.findIndex(
    (request) =>
      request.method === 'POST' && request.path === '/api/admin/auth/second-factor',
  )
  expect(csrfRequestIndex).toBeGreaterThanOrEqual(0)
  expect(passwordRequestIndex).toBeGreaterThan(csrfRequestIndex)
  expect(factorRequestIndex).toBeGreaterThan(csrfRequestIndex)

  const passwordRequest = requestsFor(api, '/api/admin/auth/password')[0]
  const factorRequest = requestsFor(api, '/api/admin/auth/second-factor')[0]
  expect(passwordRequest?.body).toEqual({
    username: 'admin',
    password: 'correct horse battery staple',
  })
  expect(factorRequest?.body).toEqual({ method: 'TOTP', code: '123456' })
  expect(passwordRequest?.headers['x-xsrf-token']).toBeTruthy()
  expect(factorRequest?.headers['x-xsrf-token']).toBeTruthy()

  const authPhaseBeforeRejectedLogout = api.state.authPhase
  const rejectedLogout = await page.evaluate(async () => {
    const response = await fetch('/api/admin/auth/logout', { method: 'POST' })
    const body = (await response.json()) as { code?: string }
    return { status: response.status, code: body.code }
  })
  expect(rejectedLogout).toEqual({ status: 403, code: 'CSRF_REJECTED' })
  expect(api.state.authPhase).toBe(authPhaseBeforeRejectedLogout)
  expect(api.state.authPhase).toBe('authenticated')
  const rejectedLogoutRequest = requestsFor(api, '/api/admin/auth/logout').at(-1)
  expect(rejectedLogoutRequest?.method).toBe('POST')
  expect(rejectedLogoutRequest?.headers['x-xsrf-token']).toBeUndefined()
  await expect(page).toHaveURL(/\/admin\/dashboard\?source=e2e#overview$/)
  await expect(page.getByRole('heading', { name: '仪表盘', exact: true })).toBeVisible()
})

test('an unsafe post-login redirect is reduced to the dashboard', async ({ page }) => {
  await installAdminApi(page)

  await page.goto(
    '/admin/login?redirect=https%3A%2F%2Fevil.example%2Fsteal%3Ffrom%3Dadmin',
  )
  await completePasswordAndTotp(page)

  await expect(page).toHaveURL(/\/admin\/dashboard$/)
  await expect(page.getByRole('heading', { name: '仪表盘', exact: true })).toBeVisible()
  expect(new URL(page.url()).origin).toBe('http://127.0.0.1:4174')
})

test('logout removes protected content and the next protected navigation returns to login', async ({
  page,
}) => {
  const api = await installAdminApi(page, { initialAuth: 'authenticated' })

  await page.goto('/admin/dashboard')
  await expect(page.getByRole('heading', { name: '仪表盘', exact: true })).toBeVisible()

  await page.getByRole('button', { name: '安全退出', exact: true }).click()

  await expect(page).toHaveURL(/\/admin\/login$/)
  await expect(page.getByRole('heading', { name: '登录管理后台' })).toBeVisible()
  await expect(page.getByRole('heading', { name: '仪表盘', exact: true })).toHaveCount(0)
  await expect.poll(() => requestsFor(api, '/api/admin/auth/logout').length).toBe(1)
  const logoutRequest = requestsFor(api, '/api/admin/auth/logout')[0]
  expect(logoutRequest?.method).toBe('POST')
  expect(logoutRequest?.headers['x-xsrf-token']).toBeTruthy()

  await page.goto('/admin/settings')
  await expect(page).toHaveURL(/\/admin\/login\?redirect=/)
  await expect(page.getByRole('heading', { name: '登录管理后台' })).toBeVisible()
  await expect(page.getByRole('heading', { name: '设置中心' })).toHaveCount(0)
})
