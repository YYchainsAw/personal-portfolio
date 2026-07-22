import { expect, test } from './e2eTest'
import { project, site } from '../fixtures/publicSnapshots'
import { installPublishedApi } from './mockPublishedApi'

test('keeps every visitor route inside one dark public shell', async ({ page }) => {
  await installPublishedApi(page)

  const routes = [
    { path: '/en', heading: site('en').hero.displayName },
    { path: '/en/projects/ue-study', heading: project('en').title },
    { path: '/en/privacy', heading: site('en').privacy.title },
    { path: '/en/not-a-route', heading: /not found/iu },
  ]

  for (const route of routes) {
    await page.goto(route.path)
    await expect(page.getByRole('heading', { level: 1 })).toHaveText(route.heading)
    const siteHeader = page.locator('#app header.premiere-header, #app header.public-site-header')
    await expect(siteHeader).toHaveCount(1)
    await expect(siteHeader).toBeVisible()
    await expect(page.getByRole('main')).toHaveCount(1)
    await expect(page.locator('#app h1')).toHaveCount(1)
    await expect(siteHeader.locator('a').first()).toBeVisible()

    const theme = await page.evaluate(() => {
      const root = getComputedStyle(document.documentElement)
      return {
        colorScheme: root.colorScheme,
        paper: root.getPropertyValue('--paper').trim().toLowerCase(),
        htmlBackground: root.backgroundColor,
        bodyBackground: getComputedStyle(document.body).backgroundColor,
      }
    })
    expect(theme.colorScheme).toContain('dark')
    expect(theme.paper).toBe('#080d12')
    expect(theme.htmlBackground).toBe('rgb(8, 13, 18)')
    expect(theme.bodyBackground).toBe(theme.htmlBackground)
  }
})

test('keeps the real site identity and published project content behind the redesigned case-study hero', async ({
  page,
}) => {
  await installPublishedApi(page)
  await page.goto('/en/projects/ue-study')

  await expect(page.locator('.public-site-header .brand__name')).toHaveText(
    site('en').identity.displayName,
  )
  const heroImage = page.locator('.project-hero__visual img')
  await expect(heroImage).toHaveAttribute('src', /ue-scene-interaction-study.*\.webp/u)
  await expect(heroImage).toHaveAttribute('srcset', /ue-scene-interaction-study.*1672w/u)
  await expect(heroImage).toHaveAttribute('alt', /Unreal Engine 5/iu)

  await expect(page.locator('.case-taxonomy')).toContainText(project('en').tags[0]!)
  await expect(page.locator('.case-taxonomy')).toContainText(project('en').skills[0]!)
  await expect(page.locator('[data-content-block]')).toHaveCount(project('en').blocks.length)
})
