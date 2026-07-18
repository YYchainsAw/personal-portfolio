import { expect, test, type Page } from '@playwright/test'

import { installAdminApi } from './mockAdminApi'

test.use({ trace: 'off', screenshot: 'off', video: 'off' })

type MockAdminApi = Awaited<ReturnType<typeof installAdminApi>>

const CURRENT_PASSWORD = 'correct horse battery staple'
const CURRENT_TOTP = '123456'
const VALID_NEW_PASSWORD = 'E2E-New-Passphrase-2026!'
const RECOVERY_CONFIRMATION = 'REGENERATE RECOVERY CODES'

function requestsFor(api: MockAdminApi, path: string) {
  return api.requests.filter((request) => request.path === path)
}

async function openSettings(page: Page): Promise<void> {
  await page.goto('/admin/settings')
  await expect(page.getByRole('heading', { name: '设置中心', exact: true })).toBeVisible()
  await expect(
    page.getByRole('heading', { name: '管理员安全 / Security', exact: true }),
  ).toBeVisible()
}

async function fillReauthentication(page: Page): Promise<void> {
  await page.locator('[data-field="current-password"]').fill(CURRENT_PASSWORD)
  await page.locator('[data-field="current-totp"]').fill(CURRENT_TOTP)
}

async function fillPasswordChange(page: Page, confirmation = VALID_NEW_PASSWORD): Promise<void> {
  await fillReauthentication(page)
  await page.locator('[data-field="new-password"]').fill(VALID_NEW_PASSWORD)
  await page.locator('[data-field="confirm-password"]').fill(confirmation)
}

async function beforeUnloadIsBlocked(page: Page): Promise<boolean> {
  return page.evaluate(() => {
    const event = new Event('beforeunload', { cancelable: true })
    window.dispatchEvent(event)
    return event.defaultPrevented
  })
}

async function storageText(page: Page): Promise<string> {
  return page.evaluate(() => {
    const snapshot = {
      local: Object.fromEntries(
        Array.from({ length: localStorage.length }, (_, index) => localStorage.key(index))
          .filter((key): key is string => key !== null)
          .map((key) => [key, localStorage.getItem(key)]),
      ),
      session: Object.fromEntries(
        Array.from({ length: sessionStorage.length }, (_, index) => sessionStorage.key(index))
          .filter((key): key is string => key !== null)
          .map((key) => [key, sessionStorage.getItem(key)]),
      ),
    }
    return JSON.stringify(snapshot)
  })
}

function recoveryCodesFrom(text: string): readonly string[] {
  return text.match(/[A-HJ-NP-Z2-9]{4}(?:-[A-HJ-NP-Z2-9]{4}){2}/g) ?? []
}

test('password change enforces local confirmation and surfaces remote reauthentication and validation errors safely', async ({
  page,
}) => {
  const api = await installAdminApi(page, { initialAuth: 'authenticated' })
  await openSettings(page)

  const otherSession = page.locator(
    'tr:has([data-action="revoke-session"][data-current="false"])',
  )
  await expect(otherSession).toBeVisible()
  const otherSessionId = await otherSession.getAttribute('data-session-id')
  expect(otherSessionId).toBeTruthy()

  await fillPasswordChange(page, 'E2E-New-Passphrase-2026?')
  await page.getByRole('button', { name: '修改密码 / Change password' }).click()

  await expect(page.getByText(/两次输入的新密码不一致/)).toBeVisible()
  expect(requestsFor(api, '/api/admin/security/password')).toHaveLength(0)

  api.scriptSecurityError('password', {
    status: 401,
    code: 'AUTHENTICATION_FAILED',
    title: '当前凭据错误 / Current credentials are invalid',
  })
  await page
    .locator('[data-field="confirm-password"]')
    .fill(VALID_NEW_PASSWORD)
  await page.getByRole('button', { name: '修改密码 / Change password' }).click()

  await expect(page.getByRole('alert').filter({ hasText: '当前凭据错误' })).toBeVisible()
  await expect(page).toHaveURL(/\/admin\/settings$/)
  await expect(page.locator('[data-field="current-password"]')).toHaveValue('')
  await expect(page.locator('[data-field="current-totp"]')).toHaveValue('')
  await expect(page.locator('[data-field="new-password"]')).toHaveValue('')
  await expect(page.locator('[data-field="confirm-password"]')).toHaveValue('')

  api.scriptSecurityError('password', {
    status: 422,
    code: 'VALIDATION_FAILED',
    title: '服务端拒绝新密码 / Password rejected',
    fieldErrors: { newPassword: '该密码不能使用 / This password cannot be used' },
  })
  await fillPasswordChange(page)
  await page.getByRole('button', { name: '修改密码 / Change password' }).click()

  await expect(page.getByText('该密码不能使用 / This password cannot be used')).toBeVisible()
  await expect(page).toHaveURL(/\/admin\/settings$/)

  await fillPasswordChange(page)
  await page.getByRole('button', { name: '修改密码 / Change password' }).click()

  await expect(page.locator('[data-security-success]')).toContainText('其他会话已撤销')
  await expect.poll(() => requestsFor(api, '/api/admin/security/password').length).toBe(3)
  for (const request of requestsFor(api, '/api/admin/security/password')) {
    expect(Object.keys(request.body as Record<string, unknown>).sort()).toEqual([
      'currentPassword',
      'currentTotp',
      'newPassword',
    ])
    expect(request.body).toEqual({
      currentPassword: CURRENT_PASSWORD,
      currentTotp: CURRENT_TOTP,
      newPassword: VALID_NEW_PASSWORD,
    })
    expect(request.headers['x-xsrf-token']).toBeTruthy()
  }

  await expect(page.locator(`tr[data-session-id="${otherSessionId}"]`)).toContainText('REVOKED')
  await expect(page.locator('[data-field="current-password"]')).toHaveValue('')
  await expect(page.locator('[data-field="current-totp"]')).toHaveValue('')
  await expect(page.locator('[data-field="new-password"]')).toHaveValue('')
  await expect(page.locator('[data-field="confirm-password"]')).toHaveValue('')
})

test('TOTP replacement keeps the provisioning URI local and erases one-time recovery data after acknowledgement', async ({
  page,
}) => {
  const api = await installAdminApi(page, { initialAuth: 'authenticated' })
  const externalNetworkRequests: string[] = []
  page.on('request', (request) => {
    const url = new URL(request.url())
    if (
      (url.protocol === 'http:' || url.protocol === 'https:') &&
      url.origin !== 'http://127.0.0.1:4174'
    ) {
      externalNetworkRequests.push(request.url())
    }
  })
  api.scriptSecurityError('totp-enrollment', {
    status: 409,
    code: 'TOTP_ENROLLMENT_EXPIRED',
    title: '绑定已失效 / Enrollment expired',
  })
  await openSettings(page)

  await fillReauthentication(page)
  await page.getByRole('button', { name: '开始替换 / Begin replacement' }).click()
  await expect(page.getByRole('alert').filter({ hasText: '绑定已失效' })).toBeVisible()
  await expect(page.locator('[data-totp-enrollment]')).toHaveCount(0)

  await fillReauthentication(page)
  await page.getByRole('button', { name: '开始替换 / Begin replacement' }).click()

  const enrollmentPanel = page.locator('[data-totp-enrollment]')
  await expect(enrollmentPanel).toBeVisible()
  const canvas = enrollmentPanel.locator('[data-totp-canvas]')
  await expect.poll(() => canvas.evaluate((element: HTMLCanvasElement) => element.width)).toBeGreaterThan(0)

  const provisioningUri = (
    (await enrollmentPanel.locator('[data-provisioning-uri]').textContent()) ?? ''
  ).trim()
  expect(provisioningUri).toMatch(/^otpauth:\/\/totp\//)

  await expect.poll(() => requestsFor(api, '/api/admin/security/totp/enrollment').length).toBe(2)
  const successfulEnrollmentRequest = requestsFor(
    api,
    '/api/admin/security/totp/enrollment',
  )[1]
  expect(successfulEnrollmentRequest?.body).toEqual({
    currentPassword: CURRENT_PASSWORD,
    currentTotp: CURRENT_TOTP,
  })
  expect(successfulEnrollmentRequest?.headers['x-xsrf-token']).toBeTruthy()
  expect(JSON.stringify(api.requests)).not.toContain('otpauth:')
  expect(externalNetworkRequests).toEqual([])

  await page.locator('[data-field="new-totp"]').fill('123456')
  await page.getByRole('button', { name: '确认并替换 / Confirm & replace' }).click()

  const recoveryDialog = page.getByRole('dialog', { name: /一次性恢复码/ })
  await expect(recoveryDialog).toBeVisible()
  const dialogText = (await recoveryDialog.textContent()) ?? ''
  const recoveryCodes = recoveryCodesFrom(dialogText)
  expect(recoveryCodes).toHaveLength(10)
  expect(new Set(recoveryCodes).size).toBe(10)

  const confirmationRequest = requestsFor(api, '/api/admin/security/totp/confirm')[0]
  expect(confirmationRequest?.body).toEqual({
    enrollmentId: expect.stringMatching(
      /^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/,
    ),
    newTotp: '123456',
  })
  expect(confirmationRequest?.headers['x-xsrf-token']).toBeTruthy()

  const dismiss = recoveryDialog.getByRole('button', { name: '清除并关闭 / Clear & dismiss' })
  await expect(dismiss).toBeDisabled()
  expect(await beforeUnloadIsBlocked(page)).toBe(true)

  const storedBeforeDismiss = await storageText(page)
  expect(storedBeforeDismiss).not.toContain(provisioningUri)
  for (const code of recoveryCodes) expect(storedBeforeDismiss).not.toContain(code)

  await recoveryDialog.locator('[data-field="codes-saved-offline"]').check()
  await expect(dismiss).toBeEnabled()
  expect(await beforeUnloadIsBlocked(page)).toBe(false)
  await dismiss.click()

  await expect(page.locator('[data-recovery-codes]')).toHaveCount(0)
  await expect(page.locator('[data-totp-enrollment]')).toHaveCount(0)
  const bodyAfterDismiss = (await page.locator('body').textContent()) ?? ''
  expect(bodyAfterDismiss).not.toContain(provisioningUri)
  for (const code of recoveryCodes) expect(bodyAfterDismiss).not.toContain(code)
  const storedAfterDismiss = await storageText(page)
  expect(storedAfterDismiss).not.toContain(provisioningUri)
  for (const code of recoveryCodes) expect(storedAfterDismiss).not.toContain(code)
})

test('recovery-code regeneration requires the destructive phrase and blocks departure until offline save', async ({
  page,
}) => {
  const api = await installAdminApi(page, { initialAuth: 'authenticated' })
  await openSettings(page)

  await fillReauthentication(page)
  await page.locator('[data-field="recovery-confirmation"]').fill('REGENERATE')
  await page
    .getByRole('button', { name: '使旧码失效并重生成 / Invalidate & regenerate' })
    .click()

  await expect(page.getByText(/请输入 REGENERATE RECOVERY CODES 以确认/)).toBeVisible()
  expect(requestsFor(api, '/api/admin/security/recovery-codes/regenerate')).toHaveLength(0)

  await page.locator('[data-field="recovery-confirmation"]').fill(RECOVERY_CONFIRMATION)
  await page
    .getByRole('button', { name: '使旧码失效并重生成 / Invalidate & regenerate' })
    .click()

  const recoveryDialog = page.getByRole('dialog', { name: /一次性恢复码/ })
  await expect(recoveryDialog).toBeVisible()
  const dialogText = (await recoveryDialog.textContent()) ?? ''
  const recoveryCodes = recoveryCodesFrom(dialogText)
  expect(recoveryCodes).toHaveLength(10)
  expect(new Set(recoveryCodes).size).toBe(10)

  const request = requestsFor(api, '/api/admin/security/recovery-codes/regenerate')[0]
  expect(request?.body).toEqual({
    currentPassword: CURRENT_PASSWORD,
    currentTotp: CURRENT_TOTP,
  })
  expect(Object.keys(request?.body as Record<string, unknown>).sort()).toEqual([
    'currentPassword',
    'currentTotp',
  ])
  expect(request?.headers['x-xsrf-token']).toBeTruthy()

  expect(await beforeUnloadIsBlocked(page)).toBe(true)
  await page.evaluate(() => {
    const dashboardLink = document.querySelector<HTMLAnchorElement>('a[href="/admin/dashboard"]')
    dashboardLink?.dispatchEvent(
      new MouseEvent('click', { bubbles: true, cancelable: true, view: window }),
    )
  })
  await expect(page).toHaveURL(/\/admin\/settings$/)

  const dismiss = recoveryDialog.getByRole('button', { name: '清除并关闭 / Clear & dismiss' })
  await expect(dismiss).toBeDisabled()
  await recoveryDialog.locator('[data-field="codes-saved-offline"]').check()
  await dismiss.click()

  await expect(recoveryDialog).toHaveCount(0)
  const bodyAfterDismiss = (await page.locator('body').textContent()) ?? ''
  for (const code of recoveryCodes) expect(bodyAfterDismiss).not.toContain(code)
  expect(await beforeUnloadIsBlocked(page)).toBe(false)
  const storedAfterDismiss = await storageText(page)
  for (const code of recoveryCodes) expect(storedAfterDismiss).not.toContain(code)
})

test('other-session revoke reloads normally while current-session revoke requires the exact phrase and logs out', async ({
  page,
}) => {
  const api = await installAdminApi(page, { initialAuth: 'authenticated' })
  await openSettings(page)

  const otherRow = page.locator(
    'tr:has([data-action="revoke-session"][data-current="false"])',
  )
  await expect(otherRow).toBeVisible()
  const otherId = await otherRow.getAttribute('data-session-id')
  expect(otherId).toBeTruthy()

  const revokeDialogPromise = page.waitForEvent('dialog')
  const revokeClick = otherRow.getByRole('button', { name: '撤销会话' }).click()
  const revokeDialog = await revokeDialogPromise
  expect(revokeDialog.type()).toBe('confirm')
  expect(revokeDialog.message()).toBe('确认撤销这个其他设备上的活动会话？')
  await revokeDialog.accept()
  await revokeClick

  await expect
    .poll(() => requestsFor(api, `/api/admin/security/sessions/${otherId}/revoke`).length)
    .toBe(1)
  await expect(page.locator(`tr[data-session-id="${otherId}"]`)).toContainText('REVOKED')
  const otherRequest = requestsFor(
    api,
    `/api/admin/security/sessions/${otherId}/revoke`,
  )[0]
  expect(otherRequest?.method).toBe('POST')
  expect(otherRequest?.headers['x-xsrf-token']).toBeTruthy()

  const currentRow = page.locator('tr:has([data-current-session])')
  await expect(currentRow).toBeVisible()
  const currentId = await currentRow.getAttribute('data-session-id')
  expect(currentId).toBeTruthy()
  await currentRow.getByRole('button', { name: '撤销会话' }).click()

  const confirmationDialog = page.getByRole('alertdialog', { name: '撤销当前会话并退出' })
  await expect(confirmationDialog).toBeVisible()
  const confirmButton = confirmationDialog.getByRole('button', { name: '确认撤销并退出' })
  await expect(confirmButton).toBeDisabled()
  await confirmationDialog.getByLabel('确认短语', { exact: true }).fill('REVOKE CURRENT SESSION')
  await expect(confirmButton).toBeEnabled()
  await confirmButton.click()

  await expect(page).toHaveURL(/\/admin\/login$/)
  await expect(page.getByRole('heading', { name: '登录管理后台' })).toBeVisible()
  await expect
    .poll(() => requestsFor(api, `/api/admin/security/sessions/${currentId}/revoke`).length)
    .toBe(1)
  const currentRequest = requestsFor(
    api,
    `/api/admin/security/sessions/${currentId}/revoke`,
  )[0]
  expect(currentRequest?.method).toBe('POST')
  expect(currentRequest?.headers['x-xsrf-token']).toBeTruthy()
})
