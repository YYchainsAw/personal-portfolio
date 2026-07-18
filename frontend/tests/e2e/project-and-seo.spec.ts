import type { Page } from '@playwright/test'
import { expect, test } from './e2eTest'
import { card, project, site } from '../fixtures/publicSnapshots'
import { installPublishedApi } from './mockPublishedApi'

interface SeoExpectation {
  title: string
  description: string
  canonicalPath: string
  alternatePaths: { 'zh-CN': string; en: string }
  ogType: 'website' | 'article'
  imagePath: string | null
  jsonType: 'Person' | 'CreativeWork' | 'WebPage'
  jsonName: string
}

async function expectSeo(page: Page, expected: SeoExpectation) {
  const absolute = (path: string) => `http://127.0.0.1:4175${path}`
  const canonical = absolute(expected.canonicalPath)
  await expect(page).toHaveTitle(expected.title)
  await expect(page.locator('meta[name="description"]')).toHaveAttribute('content', expected.description)
  await expect(page.locator('link[rel="canonical"]')).toHaveAttribute('href', canonical)
  await expect(page.locator('link[rel="alternate"][hreflang="zh-CN"]')).toHaveAttribute('href', absolute(expected.alternatePaths['zh-CN']))
  await expect(page.locator('link[rel="alternate"][hreflang="en"]')).toHaveAttribute('href', absolute(expected.alternatePaths.en))
  await expect(page.locator('meta[property="og:type"]')).toHaveAttribute('content', expected.ogType)
  await expect(page.locator('meta[property="og:title"]')).toHaveAttribute('content', expected.title)
  await expect(page.locator('meta[property="og:description"]')).toHaveAttribute('content', expected.description)
  await expect(page.locator('meta[property="og:url"]')).toHaveAttribute('content', canonical)
  if (expected.imagePath) await expect(page.locator('meta[property="og:image"]')).toHaveAttribute('content', absolute(expected.imagePath))
  else await expect(page.locator('meta[property="og:image"]')).toHaveCount(0)
  const script = page.locator('script[type="application/ld+json"][data-portfolio-seo]')
  await expect(script).toHaveCount(1)
  const json = JSON.parse((await script.textContent()) ?? '{}') as Record<string, unknown>
  expect(json).toMatchObject({ '@context': 'https://schema.org', '@type': expected.jsonType, name: expected.jsonName, url: canonical })
}

test('synchronizes home/project SEO, renders projected media contracts, and clears stale metadata on 404', async ({ page, browserErrors }) => {
  browserErrors.expectHttpErrors(['GET /api/public/projects/missing 404'])
  const state = await installPublishedApi(page)
  await page.goto('/en')
  await expect(page.getByRole('heading', { level: 1 })).toHaveText(site('en').hero.displayName)
  await expectSeo(page, {
    title: site('en').seo.title, description: site('en').seo.description, canonicalPath: '/en',
    alternatePaths: { 'zh-CN': '/zh-CN', en: '/en' }, ogType: 'website', imagePath: site('en').hero.media!.src,
    jsonType: 'Person', jsonName: site('en').identity.displayName,
  })

  await page.getByRole('link', { name: card('en').title }).click()
  await expect(page.getByRole('heading', { level: 1 })).toHaveText(project('en').title)
  await expectSeo(page, {
    title: project('en').seoTitle, description: project('en').seoDescription, canonicalPath: '/en/projects/ue-study',
    alternatePaths: { 'zh-CN': '/zh-CN/projects/ue-study', en: '/en/projects/ue-study' }, ogType: 'article', imagePath: card('en').cover.src,
    jsonType: 'CreativeWork', jsonName: project('en').title,
  })
  await expect(page.locator('[data-content-block]')).toHaveCount(9)
  await expect(page.locator('[data-download-metadata]')).toHaveText('ZIP · 1.5 MiB')
  const imagesValid = await page.locator('img').evaluateAll((images) => images.every((node) => {
    const image = node as HTMLImageElement
    return image.width > 0 && image.height > 0
      && Number(image.getAttribute('width')) > 0 && Number(image.getAttribute('height')) > 0
      && !!image.alt.trim() && !!image.getAttribute('srcset') && !!image.getAttribute('sizes')
  }))
  expect(imagesValid).toBe(true)
  expect(state.headRequests).toBe(0)

  await page.evaluate(() => {
    history.pushState({}, '', '/en/projects/missing')
    dispatchEvent(new PopStateEvent('popstate'))
  })
  await expect(page.getByRole('heading', { level: 1 })).toContainText('not found')
  await expect(page.locator('meta[name="robots"]')).toHaveAttribute('content', 'noindex,follow')
  await expect(page.locator('link[rel="canonical"], link[rel="alternate"], meta[property^="og:"], meta[name="description"], script[data-portfolio-seo]')).toHaveCount(0)

  await page.getByRole('link', { name: 'Back home' }).click()
  await expectSeo(page, {
    title: site('en').seo.title, description: site('en').seo.description, canonicalPath: '/en',
    alternatePaths: { 'zh-CN': '/zh-CN', en: '/en' }, ogType: 'website', imagePath: site('en').hero.media!.src,
    jsonType: 'Person', jsonName: site('en').identity.displayName,
  })
  await expect(page.locator('meta[name="robots"][data-route-noindex]')).toHaveCount(0)
})

test('keeps privacy localized and exposes the complete retention/withdrawal explanation', async ({ page }) => {
  await installPublishedApi(page)
  await page.goto('/zh-CN/privacy')
  await expect(page.getByRole('heading', { level: 1 })).toHaveText(site('zh-CN').privacy.title)
  await expect(page.getByRole('link', { name: '← 首页' })).toBeVisible()
  await expect(page.getByText(/原始事件最多保留 30 天/u)).toBeVisible()
  await expectSeo(page, {
    title: `${site('zh-CN').privacy.title} · ${site('zh-CN').identity.displayName}`,
    description: site('zh-CN').seo.description, canonicalPath: '/zh-CN/privacy',
    alternatePaths: { 'zh-CN': '/zh-CN/privacy', en: '/en/privacy' }, ogType: 'website', imagePath: null,
    jsonType: 'WebPage', jsonName: site('zh-CN').privacy.title,
  })
  await page.getByRole('button', { name: 'English' }).click()
  await expect(page).toHaveURL(/\/en\/privacy$/u)
  await expect(page.getByRole('link', { name: '← Home' })).toBeVisible()
  await expect(page.getByText(/raw events are retained for at most 30 days/u)).toBeVisible()
  await expectSeo(page, {
    title: `${site('en').privacy.title} · ${site('en').identity.displayName}`,
    description: site('en').seo.description, canonicalPath: '/en/privacy',
    alternatePaths: { 'zh-CN': '/zh-CN/privacy', en: '/en/privacy' }, ogType: 'website', imagePath: null,
    jsonType: 'WebPage', jsonName: site('en').privacy.title,
  })
})

test('clears project A SEO while project B fails and rebuilds only after Retry succeeds', async ({ page, browserErrors }) => {
  browserErrors.expectHttpErrors(['GET /api/public/projects/project-b 500'])
  await installPublishedApi(page, { failProjectSlugOnce: 'project-b' })
  await page.goto('/en/projects/project-a')
  await expect(page.getByRole('heading', { level: 1 })).toBeVisible()
  await expect(page.locator('link[rel="canonical"]')).toHaveAttribute('href', /\/en\/projects\/project-a$/u)

  await page.evaluate(() => {
    history.pushState({}, '', '/en/projects/project-b')
    dispatchEvent(new PopStateEvent('popstate'))
  })
  await expect(page.getByRole('alert')).toContainText('synthetic-500')
  await expect(page).toHaveTitle('Project unavailable · Yi Jiaxuan')
  await expect(page.locator('meta[name="robots"]')).toHaveAttribute('content', 'noindex,follow')
  await expect(page.locator('link[rel="canonical"], link[rel="alternate"], meta[property^="og:"], meta[name="description"], script[data-portfolio-seo]')).toHaveCount(0)

  await page.getByRole('button', { name: 'Retry' }).click()
  await expect(page.getByRole('heading', { level: 1 })).toHaveText(project('en').title)
  await expectSeo(page, {
    title: project('en').seoTitle, description: project('en').seoDescription, canonicalPath: '/en/projects/project-b',
    alternatePaths: { 'zh-CN': '/zh-CN/projects/project-b', en: '/en/projects/project-b' }, ogType: 'article', imagePath: card('en').cover.src,
    jsonType: 'CreativeWork', jsonName: project('en').title,
  })
  await expect(page.locator('meta[name="robots"][data-route-noindex]')).toHaveCount(0)
})

test('shows a safe retry state with trace ID and an explicit empty catalog state', async ({ page, browserErrors }) => {
  browserErrors.expectHttpErrors(['GET /api/public/site 500'])
  await installPublishedApi(page, { failSiteOnce: true, emptyCatalog: true })
  await page.goto('/en')
  await expect(page.getByRole('alert')).toContainText('synthetic-500')
  await page.getByRole('button', { name: 'Retry' }).click()
  await expect(page.getByRole('heading', { level: 1 })).toHaveText(site('en').hero.displayName)
  await expect(page.getByText('Projects are being prepared. Please check back soon.')).toBeVisible()
})
