import { expect, test, type Page } from '@playwright/test'

import { installAdminApi } from './mockAdminApi'

type MockAdminApi = Awaited<ReturnType<typeof installAdminApi>>

function requestsFor(api: MockAdminApi, path: string, method?: string) {
  return api.requests.filter(
    (request) =>
      request.path === path &&
      (method === undefined || request.method === method),
  )
}

async function tabUntil(page: Page, selector: string, limit = 60): Promise<void> {
  for (let attempt = 0; attempt < limit; attempt += 1) {
    await page.keyboard.press('Tab')
    const reached = await page.locator(selector).evaluateAll((elements) =>
      elements.some((element) => element === document.activeElement),
    )
    if (reached) return
  }
  throw new Error(`keyboard focus did not reach ${selector} within ${limit} steps`)
}

test('a scripted 409 stops autosave until keyboard-confirmed server reload', async ({
  page,
}) => {
  await page.clock.install()
  const api = await installAdminApi(page, {
    initialAuth: 'authenticated',
    conflictOnce: 'site-save',
  })

  await page.goto('/admin/site')
  const headline = page.locator(
    '[data-section="hero-copy"] [data-field="headline"]',
  )
  const initialVersion = api.state.siteWorkspace.version
  const initialHeadline = await headline.inputValue()
  await expect(page.locator('[data-save-state]').first()).toContainText(`v${initialVersion}`)
  await headline.fill(`${initialHeadline} · conflicting local edit`)
  await page.locator('[data-action="save"]').click()

  const conflict = page
    .locator('section[role="alert"]')
    .filter({ hasText: 'VERSION CONFLICT' })
  await expect(conflict).toBeVisible()
  await expect(conflict).toContainText('当前页面不会继续自动保存')
  await expect(page.locator('[data-save-state]').first()).toContainText('版本冲突')
  await expect.poll(
    () => requestsFor(api, '/api/admin/site/workspace', 'PUT').length,
  ).toBe(1)
  const winningVersion = api.state.siteWorkspace.version
  const winningHeadline = api.state.siteWorkspace.hero.copy['zh-CN'].headline
  expect(winningVersion).toBe(initialVersion + 1)
  expect(winningHeadline).not.toBe(initialHeadline)
  expect(winningHeadline).toContain('server-winning edit')

  await page.clock.fastForward(31_000)
  expect(requestsFor(api, '/api/admin/site/workspace', 'PUT')).toHaveLength(1)

  const reloadButton = conflict.getByRole('button', {
    name: '重新载入服务器版本',
    exact: true,
  })
  await tabUntil(page, 'section[role="alert"] button:nth-of-type(2)')
  await expect(reloadButton).toBeFocused()
  await page.keyboard.press('Enter')

  const cancelButton = conflict.getByRole('button', { name: '取消', exact: true })
  await expect(cancelButton).toBeFocused()
  await page.keyboard.press('Tab')
  const confirmButton = conflict.getByRole('button', {
    name: '确认重新载入',
    exact: true,
  })
  await expect(confirmButton).toBeFocused()
  await page.keyboard.press('Enter')

  await expect(conflict).toHaveCount(0)
  await expect(headline).toHaveValue(winningHeadline)
  await expect(page.locator('[data-save-state]').first()).toContainText('已保存')
  await expect(page.locator('[data-save-state]').first()).toContainText(`v${winningVersion}`)

  const savedAfterReload = `${winningHeadline} · saved after reload`
  await headline.fill(savedAfterReload)
  await page.clock.fastForward(15_100)
  await expect.poll(
    () => requestsFor(api, '/api/admin/site/workspace', 'PUT').length,
  ).toBe(2)
  const saveAfterReload = requestsFor(api, '/api/admin/site/workspace', 'PUT')[1]
  expect(saveAfterReload?.body).toMatchObject({ expectedVersion: winningVersion })
  expect(
    (
      saveAfterReload?.body as {
        workspace: { hero: { copy: { 'zh-CN': { headline: string } } } }
      }
    ).workspace.hero.copy['zh-CN'].headline,
  ).toBe(savedAfterReload)
  await expect(page.locator('[data-save-state]').first()).toContainText('已保存')
  await expect(page.locator('[data-save-state]').first()).toContainText(
    `v${winningVersion + 1}`,
  )
})
