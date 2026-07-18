import { readFile } from 'node:fs/promises'

import { expect, test } from '@playwright/test'

import { installAdminApi, MOCK_IDS } from './mockAdminApi'

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

test('media upload, bilingual metadata, and referenced archive stay explicit and versioned', async ({
  page,
}) => {
  const api = await installAdminApi(page, {
    initialAuth: 'authenticated',
    referencedMediaArchiveConflict: true,
  })
  const readyBefore = api.state.media.find((asset) => asset.id === MOCK_IDS.readyImage)
  expect(readyBefore).toBeDefined()

  await page.goto('/admin/media')
  await expect(page.getByRole('heading', { name: '媒体库', exact: true })).toBeVisible()

  await page.getByLabel('选择要上传的 JPEG、PNG 或 PDF 文件').setInputFiles({
    name: 'synthetic-e2e.jpg',
    mimeType: 'image/jpeg',
    buffer: Buffer.from([0xff, 0xd8, 0xff, 0xd9]),
  })
  await page.locator('[data-action="upload"]').click()
  await expect(page.locator('[data-upload-result]')).toHaveAttribute(
    'data-status',
    'PROCESSING',
  )
  await expect.poll(() => requestsFor(api, '/api/admin/media', 'POST').length).toBe(1)

  await page
    .locator(
      `[data-action="open-media"][data-media-id="${MOCK_IDS.readyImage}"]`,
    )
    .click()
  const detail = page.locator(
    `[data-media-detail][data-media-id="${MOCK_IDS.readyImage}"]`,
  )
  await expect(detail).toBeVisible()

  const zhAlt = '合成端到端替代文字'
  const enAlt = 'Synthetic end-to-end alt text'
  await detail.locator('[data-field="zh-CN.altText"]').fill(zhAlt)
  await detail.locator('[data-field="en.altText"]').fill(enAlt)
  await detail.locator('[data-action="save-translations"]').click()

  const translationsPath = `/api/admin/media/${MOCK_IDS.readyImage}/translations`
  await expect.poll(() => requestsFor(api, translationsPath, 'PUT').length).toBe(1)
  const translationBody = bodyOf(requestsFor(api, translationsPath, 'PUT')[0])
  expect(translationBody.expectedVersion).toBe(readyBefore?.version)
  const translations = translationBody.translations as Array<
    Record<string, unknown>
  >
  expect(translations.find((item) => item.locale === 'zh-CN')?.altText).toBe(zhAlt)
  expect(translations.find((item) => item.locale === 'en')?.altText).toBe(enAlt)
  await expect(detail.locator('[data-action="save-translations"]')).toBeDisabled()

  await detail.locator('[data-action="close-detail"]').click()
  await page
    .locator(
      `[data-action="open-media"][data-media-id="${MOCK_IDS.referencedImage}"]`,
    )
    .click()
  const referencedDetail = page.locator(
    `[data-media-detail][data-media-id="${MOCK_IDS.referencedImage}"]`,
  )
  await expect(referencedDetail).toBeVisible()

  page.once('dialog', async (dialog) => {
    expect(dialog.message()).toContain('确认归档这个媒体资源')
    await dialog.accept()
  })
  await referencedDetail.locator('[data-action="archive-media"]').click()
  const archivePath = `/api/admin/media/${MOCK_IDS.referencedImage}`
  await expect.poll(() => requestsFor(api, archivePath, 'DELETE').length).toBe(1)
  await expect(referencedDetail.locator('[data-archive-problem]')).toContainText(
    '仍被项目引用',
  )
  await expect(referencedDetail.locator('[data-archive-uncertain]')).toHaveCount(0)
})

test('message content is escaped and status, retry, and delete mutations are deliberate', async ({
  page,
}) => {
  await page.addInitScript(() => {
    ;(window as Window & { __E2E_XSS__?: number }).__E2E_XSS__ = 0
  })
  const api = await installAdminApi(page, { initialAuth: 'authenticated' })

  await page.goto('/admin/messages')
  await expect(
    page.getByRole('heading', { name: '留言收件箱', exact: true }),
  ).toBeVisible()

  const firstMessage = page.locator('button[data-message-id]').first()
  const messageId = await firstMessage.getAttribute('data-message-id')
  const listVersion = Number(await firstMessage.getAttribute('data-version'))
  expect(messageId).toMatch(/^[0-9a-f-]{36}$/i)
  expect(Number.isSafeInteger(listVersion)).toBe(true)
  await firstMessage.click()

  const detail = page.locator(`[data-message-detail][data-message-id="${messageId}"]`)
  await expect(detail).toBeVisible()
  const messageBody = detail.locator('[data-message-body]')
  await expect(messageBody).toContainText('<img src=x onerror=')
  await expect(messageBody.locator('img')).toHaveCount(0)
  expect(
    await page.evaluate(
      () => (window as Window & { __E2E_XSS__?: number }).__E2E_XSS__,
    ),
  ).toBe(0)

  const detailVersion = Number(await detail.getAttribute('data-version'))
  expect(detailVersion).toBe(listVersion)
  await detail.locator('button[data-status="READ"]').click()
  const statusPath = `/api/admin/messages/${messageId}/status`
  await expect.poll(() => requestsFor(api, statusPath, 'PATCH').length).toBe(1)
  expect(bodyOf(requestsFor(api, statusPath, 'PATCH')[0])).toEqual({
    status: 'READ',
    version: detailVersion,
  })
  await expect(detail).toHaveAttribute('data-version', String(detailVersion + 1))
  await expect(detail.locator('button[data-status="READ"]')).toHaveAttribute(
    'aria-pressed',
    'true',
  )

  const retryDialogPromise = page.waitForEvent('dialog')
  const retryClick = detail.locator('[data-action="retry-email"]').click()
  const retryDialog = await retryDialogPromise
  expect(retryDialog.type()).toBe('confirm')
  expect(retryDialog.message()).toBe('确认将这条留言的邮件重新加入投递队列？')
  await retryDialog.accept()
  await retryClick
  const retryPath = `/api/admin/messages/${messageId}/email/retry`
  await expect.poll(() => requestsFor(api, retryPath, 'POST').length).toBe(1)
  await expect(detail.locator('[data-email-field="status"] dd')).toHaveText('PENDING')
  await expect(detail.locator('[data-retry-uncertain]')).toHaveCount(0)

  await detail.locator('[data-delete-confirmation]').fill('DELETE')
  const deleteDialogPromise = page.waitForEvent('dialog')
  const deleteClick = detail.locator('[data-action="delete-message"]').click()
  const deleteDialog = await deleteDialogPromise
  expect(deleteDialog.type()).toBe('confirm')
  expect(deleteDialog.message()).toBe('永久删除这条留言及其投递记录？此操作不可撤销。')
  await deleteDialog.accept()
  await deleteClick
  const deletePath = `/api/admin/messages/${messageId}`
  await expect.poll(() => requestsFor(api, deletePath, 'DELETE').length).toBe(1)
  await expect(detail).toHaveCount(0)
  await expect(page.locator(`button[data-message-id="${messageId}"]`)).toHaveCount(0)
  expect(api.state.deletedMessageIds).toContain(messageId)
})

test('analytics exposes definitions, a completeness watermark, and one coherent query', async ({
  page,
}) => {
  const api = await installAdminApi(page, { initialAuth: 'authenticated' })
  await page.goto('/admin/analytics')

  await expect(
    page.getByRole('heading', { name: '访问统计', exact: true }),
  ).toBeVisible()
  await expect(page.locator('[data-summary-card]')).toHaveCount(6)
  await expect(page.locator('[data-definition-key]')).toHaveCount(3)
  for (const key of ['PV', 'DAILY_UV', 'EVENT_COUNT']) {
    await expect(page.locator(`[data-definition-key="${key}"] dd`)).not.toBeEmpty()
  }

  const watermark = page.locator('[data-watermark] time')
  await expect(watermark).toBeVisible()
  const completeThrough = await watermark.getAttribute('datetime')
  const queryTo = await page.locator('[data-query="to"]').inputValue()
  expect(Date.parse(completeThrough ?? '')).toBeLessThan(
    Date.parse(`${queryTo}T23:59:59.999Z`),
  )

  await expect
    .poll(() => requestsFor(api, '/api/admin/analytics/summary', 'GET').length)
    .toBe(1)
  await expect
    .poll(() => requestsFor(api, '/api/admin/analytics/timeseries', 'GET').length)
    .toBe(1)
  await expect
    .poll(() => requestsFor(api, '/api/admin/analytics/breakdown', 'GET').length)
    .toBe(1)

  await page.locator('[data-query="locale"]').selectOption('en')
  await page.locator('[data-action="run-query"]').click()
  await expect
    .poll(() => requestsFor(api, '/api/admin/analytics/summary', 'GET').length)
    .toBe(2)
  await expect(page.locator('[data-definition-key="PV"] dd')).toContainText(
    'Synthetic page-view count',
  )

  const summaryRequest = requestsFor(
    api,
    '/api/admin/analytics/summary',
    'GET',
  ).at(-1)
  const timeseriesRequest = requestsFor(
    api,
    '/api/admin/analytics/timeseries',
    'GET',
  ).at(-1)
  const breakdownRequest = requestsFor(
    api,
    '/api/admin/analytics/breakdown',
    'GET',
  ).at(-1)
  expect(summaryRequest?.query).toMatchObject({
    from: await page.locator('[data-query="from"]').inputValue(),
    to: queryTo,
    locale: 'en',
    zone: 'Asia/Hong_Kong',
  })
  expect(timeseriesRequest?.query.zone).toBe('Asia/Hong_Kong')
  expect(breakdownRequest?.query.zone).toBe('Asia/Hong_Kong')
  expect(timeseriesRequest?.query.from).toBe(summaryRequest?.query.from)
  expect(timeseriesRequest?.query.to).toBe(summaryRequest?.query.to)
  expect(breakdownRequest?.query.from).toBe(summaryRequest?.query.from)
  expect(breakdownRequest?.query.to).toBe(summaryRequest?.query.to)
})

test('settings keeps audit immutable and paginated while operations remain seven read-only statuses', async ({
  page,
}) => {
  const api = await installAdminApi(page, { initialAuth: 'authenticated' })
  await page.goto('/admin/settings')
  await expect(
    page.getByRole('heading', { name: '设置中心', exact: true }),
  ).toBeVisible()

  await expect(page.getByRole('link', { name: 'SEO 设置' })).toHaveAttribute(
    'href',
    '/admin/site#seo',
  )
  await expect(page.getByRole('link', { name: '简历设置' })).toHaveAttribute(
    'href',
    '/admin/site#resumes',
  )

  const auditSection = page.getByRole('region', { name: '安全审计', exact: true })
  await expect(auditSection.locator('[data-audit-id]')).toHaveCount(50)
  const firstPageIds = await auditSection
    .locator('[data-audit-id]')
    .evaluateAll((rows) => rows.map((row) => row.getAttribute('data-audit-id')))
  await auditSection.locator('[data-action="load-more-audit"]').click()
  await expect(auditSection.locator('[data-audit-id]')).toHaveCount(55)
  const allIds = await auditSection
    .locator('[data-audit-id]')
    .evaluateAll((rows) => rows.map((row) => row.getAttribute('data-audit-id')))
  expect(new Set(allIds).size).toBe(allIds.length)
  expect(allIds.slice(0, firstPageIds.length)).toEqual(firstPageIds)
  await expect
    .poll(() => requestsFor(api, '/api/admin/audit', 'GET').length)
    .toBe(2)
  const nextCursor = requestsFor(api, '/api/admin/audit', 'GET')[1]?.query.cursor
  expect(nextCursor).toEqual(expect.any(String))
  expect(nextCursor).toMatch(/^[A-Za-z0-9_-]+$/)
  expect(nextCursor?.length).toBeGreaterThan(0)

  await auditSection.locator('[data-filter="action"]').fill('ADMIN_LOGIN_SUCCEEDED')
  await auditSection.locator('[data-filter="outcome"]').selectOption('SUCCESS')
  await expect(auditSection.locator('[data-action="load-more-audit"]')).toHaveCount(0)
  await auditSection.locator('[data-action="apply-audit-filters"]').click()
  await expect.poll(() => requestsFor(api, '/api/admin/audit', 'GET').length).toBe(3)
  const filteredRequest = requestsFor(api, '/api/admin/audit', 'GET').at(-1)
  expect(filteredRequest?.query).toMatchObject({
    action: 'ADMIN_LOGIN_SUCCEEDED',
    outcome: 'SUCCESS',
    limit: '50',
  })
  expect(filteredRequest?.query.cursor).toBeUndefined()
  await expect(auditSection.locator('[data-audit-id]')).not.toHaveCount(0)
  await expect(
    auditSection.locator('ol[aria-label="管理员审计记录"] input, ol[aria-label="管理员审计记录"] select, ol[aria-label="管理员审计记录"] textarea, ol[aria-label="管理员审计记录"] button'),
  ).toHaveCount(0)

  const operationsSection = page.getByRole('region', {
    name: '运维状态',
    exact: true,
  })
  const cards = operationsSection.locator('[data-operation-key]')
  await expect(cards).toHaveCount(7)
  const operationKeys = await cards.evaluateAll((items) =>
    items.map((item) => item.getAttribute('data-operation-key')),
  )
  expect(operationKeys).toEqual([
    'databaseBackup',
    'mediaBackup',
    'analyticsAggregation',
    'contactRetention',
    'mediaCleanup',
    'deployment',
    'restoreDrill',
  ])
  await expect(operationsSection.locator('[data-operation-empty]')).toHaveCount(2)
  await expect(operationsSection).toContainText('SUCCEEDED')
  await expect(operationsSection).toContainText('FAILED')
  await expect(operationsSection.locator('input, select, textarea')).toHaveCount(0)
  await expect(operationsSection.locator('button')).toHaveCount(1)
  await expect(
    operationsSection.locator(
      '[data-action]:not([data-action="refresh-operations"])',
    ),
  ).toHaveCount(0)
})

test('every approved admin destination resolves to a real view and router source has no feature shell', async ({
  page,
}) => {
  const routerSource = await readFile(
    new URL('../../src/router/index.ts', import.meta.url),
    'utf8',
  )
  expect(routerSource).not.toContain('FeatureShellView')
  for (const component of [
    'dashboard',
    'siteEditor',
    'projectList',
    'projectEditor',
    'mediaLibrary',
    'publishingHistory',
    'messages',
    'analytics',
    'settings',
  ]) {
    expect(routerSource).toContain(`component: ${component}`)
  }

  await installAdminApi(page, { initialAuth: 'authenticated' })
  const destinations: ReadonlyArray<readonly [string, string]> = [
    ['/admin/dashboard', '仪表盘'],
    ['/admin/site', '站点内容'],
    ['/admin/projects', '项目与作品'],
    ['/admin/projects/new', '创建项目'],
    [`/admin/projects/${MOCK_IDS.project}`, '编辑项目'],
    ['/admin/media', '媒体库'],
    ['/admin/messages', '留言收件箱'],
    ['/admin/analytics', '访问统计'],
    ['/admin/settings', '设置中心'],
    [
      `/admin/publishing/SITE/${MOCK_IDS.site}/history`,
      '站点发布历史',
    ],
  ]

  for (const [path, heading] of destinations) {
    await page.goto(path)
    await expect(page.getByRole('heading', { name: heading, exact: true })).toBeVisible()
    await expect(page.locator('main')).not.toContainText('FeatureShellView')
  }
})
