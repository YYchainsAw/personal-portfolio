import { expect, test } from './e2eTest'
import { homeInitialPayload, projectInitialPayload } from '../fixtures/publicSnapshots'
import { renderServerHtml } from '../fixtures/serverHtml'
import { installPublishedApi } from './mockPublishedApi'

test('reuses the server HOME payload once and preserves project semantics across locale switches', async ({ page }) => {
  const state = await installPublishedApi(page)
  await page.route('http://127.0.0.1:4175/zh-CN', (route) => route.fulfill({
    status: 200, contentType: 'text/html', body: renderServerHtml(homeInitialPayload),
  }))

  await page.goto('/zh-CN')
  await expect(page.getByRole('heading', { level: 1 })).toHaveText(homeInitialPayload.site.hero.displayName)
  await expect(page.locator('#__PORTFOLIO_DATA__')).toHaveCount(0)
  await expect(page.locator('#app > main')).toHaveCount(1)
  await expect(page.locator('#app > header')).toHaveCount(1)
  await expect(page.locator('#app h1')).toHaveCount(1)
  expect(state.publicGets).toBe(0)

  await page.getByRole('link', { name: homeInitialPayload.catalog[0]!.title }).click()
  await expect(page).toHaveURL(/\/zh-CN\/projects\/ue-study$/u)
  await expect(page.getByRole('heading', { level: 1 })).toHaveText(homeInitialPayload.catalog[0]!.title)
  await expect(page.getByRole('list', { name: '标签' })).toBeVisible()
  await expect(page.getByRole('list', { name: '技能' })).toBeVisible()
  await page.getByRole('button', { name: 'English' }).click()
  await expect(page).toHaveURL(/\/en\/projects\/ue-study$/u)
  await expect(page.getByRole('heading', { level: 1 })).toContainText('UE Study')
  await expect(page.getByRole('list', { name: 'Tags' })).toBeVisible()
  await expect(page.getByRole('list', { name: 'Skills' })).toBeVisible()
})

test('reuses the server PROJECT payload with zero matching first-load GETs', async ({ page }) => {
  const state = await installPublishedApi(page)
  await page.route('http://127.0.0.1:4175/en/projects/ue-study', (route) => route.fulfill({
    status: 200, contentType: 'text/html', body: renderServerHtml(projectInitialPayload),
  }))

  await page.goto('/en/projects/ue-study')
  await expect(page.getByRole('heading', { level: 1 })).toHaveText(projectInitialPayload.project.title)
  await expect(page.locator('#app > main')).toHaveCount(1)
  await expect(page.locator('#app h1')).toHaveCount(1)
  expect(state.publicGets).toBe(0)
})

test('accepts a legitimate null hero media payload without a fake visual or lost navigation', async ({ page }) => {
  const payload = structuredClone(homeInitialPayload)
  payload.site.hero.media = null
  const state = await installPublishedApi(page)
  await page.route('http://127.0.0.1:4175/zh-CN', (route) => route.fulfill({
    status: 200, contentType: 'text/html', body: renderServerHtml(payload),
  }))

  await page.goto('/zh-CN')
  await expect(page.locator('figure.hero__visual')).toHaveCount(0)
  await expect(page.getByRole('heading', { level: 1 })).toHaveText(payload.site.hero.displayName)
  await expect(page.getByRole('link', { name: payload.catalog[0]!.title })).toBeVisible()
  expect(state.publicGets).toBe(0)
})

test('aborts a superseded project request and never paints its late payload', async ({ page }) => {
  const state = await installPublishedApi(page, { delayProjectSlug: 'slow-project' })
  await page.goto('/en/projects/slow-project')
  await expect.poll(() => state.sawDelayedProjectRequest).toBe(true)

  await page.evaluate(() => {
    history.pushState({}, '', '/zh-CN/projects/ue-study')
    dispatchEvent(new PopStateEvent('popstate'))
  })

  await expect(page).toHaveURL(/\/zh-CN\/projects\/ue-study$/u)
  await expect(page.getByRole('heading', { level: 1 })).toContainText('UE 学习项目')
  await expect(page.getByRole('heading', { level: 1 })).not.toContainText('slow-project')
  await expect.poll(() => state.supersededAbort).toBe(true)
})
