import { expect, test, type Page } from '@playwright/test'

import { installAdminApi } from './mockAdminApi'

const SITE_ID = '00000000-0000-0000-0000-000000000001'
const PROJECT_ID = '20000000-0000-4000-8000-000000000001'

type MockAdminApi = Awaited<ReturnType<typeof installAdminApi>>

function requestsFor(api: MockAdminApi, path: string, method?: string) {
  return api.requests.filter(
    (request) =>
      request.path === path &&
      (method === undefined || request.method === method),
  )
}

function bodyOf(request: MockAdminApi['requests'][number] | undefined) {
  expect(request, 'expected the API request to be recorded').toBeDefined()
  expect(request?.body).not.toBeNull()
  expect(typeof request?.body).toBe('object')
  return request?.body as Record<string, unknown>
}

async function tabUntil(
  page: Page,
  selector: string,
  options: { key?: 'Tab' | 'Shift+Tab'; limit?: number } = {},
): Promise<void> {
  const key = options.key ?? 'Tab'
  const limit = options.limit ?? 120
  for (let attempt = 0; attempt < limit; attempt += 1) {
    await page.keyboard.press(key)
    const reached = await page.locator(selector).evaluateAll((elements) =>
      elements.some((element) => element === document.activeElement),
    )
    if (reached) return
  }
  throw new Error(`keyboard focus did not reach ${selector} within ${limit} steps`)
}

test('site locale drafts survive tab switches and autosave feeds preview and publish versions', async ({
  page,
}) => {
  await page.clock.install()
  const api = await installAdminApi(page, { initialAuth: 'authenticated' })

  await page.goto('/admin/site')
  await expect(page.getByRole('heading', { name: '站点内容', exact: true })).toBeVisible()
  await expect(page.locator('[data-save-state]').first()).toContainText('已保存')

  const zhHeadline = page.locator(
    '[data-section="hero-copy"] [data-field="headline"]',
  )
  const originalZh = await zhHeadline.inputValue()
  const nextZh = `${originalZh} · 自动保存验证`
  await zhHeadline.fill(nextZh)

  await page.locator('button[data-locale="en"]').click()
  const enHeadline = page.locator(
    '[data-section="hero-copy"] [data-field="headline"]',
  )
  const originalEn = await enHeadline.inputValue()
  const nextEn = `${originalEn} · autosave proof`
  await enHeadline.fill(nextEn)

  await page.locator('button[data-locale="zh-CN"]').click()
  await expect(zhHeadline).toHaveValue(nextZh)
  await page.locator('button[data-locale="en"]').click()
  await expect(enHeadline).toHaveValue(nextEn)
  await expect(page.locator('[data-save-state]').first()).toContainText('有未保存修改')

  await page.clock.fastForward(15_100)
  await expect
    .poll(() => requestsFor(api, '/api/admin/site/workspace', 'PUT').length)
    .toBe(1)
  await expect(page.locator('[data-save-state]').first()).toContainText('已保存')

  const saveBody = bodyOf(
    requestsFor(api, '/api/admin/site/workspace', 'PUT')[0],
  )
  const savedWorkspace = saveBody.workspace as Record<string, any>
  const expectedVersion = saveBody.expectedVersion as number
  expect(Number.isSafeInteger(expectedVersion)).toBe(true)
  expect(savedWorkspace.hero.copy['zh-CN'].headline).toBe(nextZh)
  expect(savedWorkspace.hero.copy.en.headline).toBe(nextEn)

  const popupPromise = page.waitForEvent('popup')
  await page.locator('[data-publish-panel] [data-action="preview"]').click()
  const previewPage = await popupPromise
  await expect(previewPage).toHaveURL(
    /\/api\/admin\/publishing\/previews\/ticket-1\.[A-Za-z0-9_-]+$/,
  )
  await previewPage.close()

  await expect
    .poll(() => requestsFor(api, '/api/admin/publishing/preview-tokens', 'POST').length)
    .toBe(1)
  expect(
    bodyOf(requestsFor(api, '/api/admin/publishing/preview-tokens', 'POST')[0]),
  ).toEqual({
    aggregateType: 'SITE',
    aggregateId: SITE_ID,
    workspaceVersion: expectedVersion + 1,
  })

  const renderedSaveState = await page.locator('[data-save-state]').first().innerText()
  const renderedWorkspaceMatch = /v(\d+)/.exec(renderedSaveState)
  expect(renderedWorkspaceMatch).not.toBeNull()
  const renderedWorkspaceVersion = Number(renderedWorkspaceMatch?.[1])
  expect(renderedWorkspaceVersion).toBe(expectedVersion + 1)

  const renderedPublicationState = page.locator('[data-publication-state]')
  await expect(renderedPublicationState).toContainText('发布状态：PUBLISHED')
  const renderedPublicationMatch = /发布状态：PUBLISHED\s*·\s*v(\d+)/.exec(
    await renderedPublicationState.innerText(),
  )
  expect(renderedPublicationMatch).not.toBeNull()
  const renderedPublicationVersion = Number(renderedPublicationMatch?.[1])

  page.once('dialog', async (dialog) => {
    expect(dialog.type()).toBe('confirm')
    expect(dialog.message()).toContain('确认发布当前已保存版本')
    await dialog.accept()
  })
  const publishResponsePromise = page.waitForResponse(
    (response) =>
      new URL(response.url()).pathname === '/api/admin/publishing/site' &&
      response.request().method() === 'POST',
  )
  await page.locator('[data-publish-panel] [data-action="publish"]').click()
  const publishResponse = await publishResponsePromise
  expect(publishResponse.status()).toBe(200)
  await expect
    .poll(() => requestsFor(api, '/api/admin/publishing/site', 'POST').length)
    .toBe(1)

  const publishBody = bodyOf(
    requestsFor(api, '/api/admin/publishing/site', 'POST')[0],
  )
  expect(publishBody).toEqual({
    expectedWorkspaceVersion: renderedWorkspaceVersion,
    expectedPublicationVersion: renderedPublicationVersion,
  })
  await expect(renderedPublicationState).toContainText(
    `发布状态：PUBLISHED · v${renderedPublicationVersion + 1}`,
  )
  await expect(page.locator('[data-publication-notice]')).toContainText('发布成功')
  await expect(page.locator('[data-publication-conflict]')).toHaveCount(0)
  await expect(page.locator('[data-publication-error]')).toHaveCount(0)
  await expect(page.locator('[data-post-publish-refresh-error]')).toHaveCount(0)
  await expect(page.locator('[data-publication-state-error]')).toHaveCount(0)
})

test('project locale drafts publish with workspace, project, and catalog versions', async ({
  page,
}) => {
  const api = await installAdminApi(page, { initialAuth: 'authenticated' })
  const workspacePath = `/api/admin/projects/${PROJECT_ID}/workspace`
  const publishPath = `/api/admin/publishing/projects/${PROJECT_ID}`

  await page.goto(`/admin/projects/${PROJECT_ID}`)
  await expect(page.getByRole('heading', { name: '编辑项目', exact: true })).toBeVisible()

  const zhTitle = page.locator('[data-field="translations.zh-CN.title"]')
  const originalZh = await zhTitle.inputValue()
  const nextZh = `${originalZh} · 双语草稿`
  await zhTitle.fill(nextZh)

  await page.locator('button[data-locale="en"]').click()
  const enTitle = page.locator('[data-field="translations.en.title"]')
  const originalEn = await enTitle.inputValue()
  const nextEn = `${originalEn} · bilingual draft`
  await enTitle.fill(nextEn)

  await page.locator('button[data-locale="zh-CN"]').click()
  await expect(zhTitle).toHaveValue(nextZh)
  await page.locator('button[data-locale="en"]').click()
  await expect(enTitle).toHaveValue(nextEn)

  const publicationText = await page.locator('[data-publication-version]').innerText()
  const publicationVersions = /项目发布版本\s+(\d+)\s+·\s+目录版本\s+(\d+)/.exec(
    publicationText,
  )
  expect(publicationVersions).not.toBeNull()
  const projectPublicationVersion = Number(publicationVersions?.[1])
  const catalogVersion = Number(publicationVersions?.[2])

  await page.locator('[data-action="save-bottom"]').click()
  await expect.poll(() => requestsFor(api, workspacePath, 'PUT').length).toBe(1)
  await expect(page.locator('[data-save-state]').first()).toContainText('已保存')

  const saveBody = bodyOf(requestsFor(api, workspacePath, 'PUT')[0])
  const expectedVersion = saveBody.expectedVersion as number
  const savedWorkspace = saveBody.workspace as Record<string, any>
  expect(savedWorkspace.version).toBe(expectedVersion)
  expect(savedWorkspace.translations['zh-CN'].title).toBe(nextZh)
  expect(savedWorkspace.translations.en.title).toBe(nextEn)

  const renderedSaveState = await page.locator('[data-save-state]').first().innerText()
  const renderedWorkspaceMatch = /版本\s+(\d+)/.exec(renderedSaveState)
  expect(renderedWorkspaceMatch).not.toBeNull()
  const renderedWorkspaceVersion = Number(renderedWorkspaceMatch?.[1])
  expect(renderedWorkspaceVersion).toBe(expectedVersion + 1)

  const publishResponsePromise = page.waitForResponse(
    (response) =>
      new URL(response.url()).pathname === publishPath &&
      response.request().method() === 'POST',
  )
  const dialogPromise = page.waitForEvent('dialog')
  const publishClick = page.locator('[data-publish-panel] [data-action="publish"]').click()
  const dialog = await dialogPromise
  expect(dialog.type()).toBe('confirm')
  expect(dialog.message()).toContain('确认发布当前已保存版本')
  await dialog.accept()
  await publishClick
  const publishResponse = await publishResponsePromise
  expect(publishResponse.status()).toBe(200)
  await expect.poll(() => requestsFor(api, publishPath, 'POST').length).toBe(1)
  expect(bodyOf(requestsFor(api, publishPath, 'POST')[0])).toEqual({
    projectId: PROJECT_ID,
    expectedWorkspaceVersion: renderedWorkspaceVersion,
    expectedProjectPublicationVersion: projectPublicationVersion,
    expectedCatalogVersion: catalogVersion,
  })
  await expect(
    page.locator('p.sr-only[role="status"]').filter({
      hasText: '项目发布成功，最新工作区与目录状态已经载入。',
    }),
  ).toBeAttached()
  await expect(page.locator('[data-publication-version]')).toContainText(
    `项目发布版本 ${projectPublicationVersion + 1} · 目录版本 ${catalogVersion + 1}`,
  )
  await expect(page.locator('[data-save-state]').first()).toContainText(
    `版本 ${renderedWorkspaceVersion + 1}`,
  )
  await expect(page.locator('[data-publication-conflict]')).toHaveCount(0)
  await expect(page.locator('[data-publication-error]')).toHaveCount(0)
  await expect(page.locator('[data-publication-refresh-problem]')).toHaveCount(0)
  await expect(page.locator('[data-publication-state-error]')).toHaveCount(0)
})

test('keyboard navigation reaches shell links, locale tabs, the media dialog, and publish confirmation', async ({
  page,
}) => {
  await installAdminApi(page, { initialAuth: 'authenticated' })
  await page.goto('/admin/dashboard')

  await tabUntil(page, 'a[href="/admin/site"]', { limit: 30 })
  await page.keyboard.press('Enter')
  await expect(page).toHaveURL(/\/admin\/site$/)
  await expect(page.getByRole('heading', { name: '站点内容', exact: true })).toBeVisible()

  await tabUntil(page, 'button[data-locale="zh-CN"]', { limit: 50 })
  await page.keyboard.press('ArrowRight')
  await expect(page.locator('button[data-locale="en"]')).toBeFocused()
  await expect(page.locator('button[data-locale="en"]')).toHaveAttribute(
    'aria-selected',
    'true',
  )

  await tabUntil(page, 'button[data-media-target="hero"]', { limit: 100 })
  await page.keyboard.press('Enter')
  const picker = page.getByRole('dialog', { name: '选择媒体' })
  await expect(picker).toBeVisible()
  await expect(picker.getByRole('searchbox')).toBeFocused()
  await page.keyboard.press('Escape')
  await expect(picker).toHaveCount(0)
  await expect(page.locator('button[data-media-target="hero"]')).toBeFocused()

  await tabUntil(page, '[data-publish-panel] [data-action="publish"]', {
    key: 'Shift+Tab',
    limit: 100,
  })
  let confirmationSeen = false
  page.once('dialog', async (dialog) => {
    confirmationSeen = true
    expect(dialog.message()).toContain('确认发布当前已保存版本')
    await dialog.dismiss()
  })
  await page.keyboard.press('Enter')
  await expect.poll(() => confirmationSeen).toBe(true)
})
