import AxeBuilder from '@axe-core/playwright'
import { expect, test } from './e2eTest'
import { card, site } from '../fixtures/publicSnapshots'
import { installPublishedApi } from './mockPublishedApi'

async function expectNoSeriousAxe(page: import('@playwright/test').Page, include?: string) {
  // Audit the settled UI, not a partially transparent frame from the 720 ms
  // reveal transition. This still exercises the real motion implementation;
  // reduced-motion behavior is covered separately below.
  await expect.poll(() => page.locator('.reveal.is-visible').evaluateAll((nodes) => nodes.every((node) => getComputedStyle(node).opacity === '1'))).toBe(true)
  const builder = new AxeBuilder({ page })
  if (include) builder.include(include)
  const result = await builder.analyze()
  expect(result.violations.filter((violation) => ['serious', 'critical'].includes(violation.impact ?? ''))).toEqual([])
}

async function revealAndSettleWholePage(page: import('@playwright/test').Page) {
  const reveals = page.locator('.reveal')
  const count = await reveals.count()
  for (let index = 0; index < count; index += 1) {
    const reveal = reveals.nth(index)
    await reveal.evaluate((element) => element.scrollIntoView({ block: 'center' }))
    await expect(reveal).toHaveClass(/\bis-visible\b/u)
  }
  await expect.poll(() => reveals.evaluateAll((nodes) => nodes.every((node) => getComputedStyle(node).opacity === '1'))).toBe(true)
}

test('has one semantic page, a working skip link, no overflow, and responsive media', async ({ page }, testInfo) => {
  await installPublishedApi(page)
  await page.goto('/en')
  await expect(page.getByRole('heading', { level: 1 })).toHaveCount(1)
  const skip = page.locator('.skip-link')
  if (testInfo.project.name === 'mobile') await skip.focus()
  else await page.keyboard.press('Tab')
  await expect(skip).toBeFocused()
  await page.keyboard.press('Enter')
  await expect(page.locator('#main-content')).toBeFocused()
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= document.documentElement.clientWidth)).toBe(true)

  if (testInfo.project.name === 'mobile') {
    const menu = page.locator('.menu-toggle')
    await menu.click()
    await expect(menu).toHaveAttribute('aria-expanded', 'true')
    const focusable = page.locator('#mobile-menu a[href], #mobile-menu button:not([disabled]), #mobile-menu [tabindex]:not([tabindex="-1"])')
    const first = focusable.first()
    const last = focusable.last()
    await last.focus()
    await page.keyboard.press('Tab')
    await expect(first).toBeFocused()
    await first.focus()
    await page.keyboard.press('Shift+Tab')
    await expect(last).toBeFocused()
    await page.keyboard.press('Escape')
    await expect(menu).toHaveAttribute('aria-expanded', 'false')
    await expect(menu).toBeFocused()
  }

  await revealAndSettleWholePage(page)
  await expectNoSeriousAxe(page)
  const contact = page.locator('#contact')
  await contact.scrollIntoViewIfNeeded()
  await expectNoSeriousAxe(page, '#contact')
  const contactContrast = await page.locator('.contact-form').evaluate((form) => {
    const rgb = (value: string) => (value.match(/[\d.]+/gu) ?? []).slice(0, 3).map(Number)
    const luminance = (value: string) => {
      const channels = rgb(value).map((channel) => {
        const normalized = channel / 255
        return normalized <= 0.03928 ? normalized / 12.92 : ((normalized + 0.055) / 1.055) ** 2.4
      })
      return 0.2126 * channels[0]! + 0.7152 * channels[1]! + 0.0722 * channels[2]!
    }
    const ratio = (one: string, two: string) => {
      const [light, dark] = [luminance(one), luminance(two)].sort((a, b) => b - a)
      return (light! + 0.05) / (dark! + 0.05)
    }
    const surface = getComputedStyle(form).backgroundColor
    const retention = getComputedStyle(form.querySelector('.retention-copy')!).color
    const button = form.querySelector('button')!
    const buttonStyle = getComputedStyle(button)
    return {
      retentionOnSurface: ratio(retention, surface),
      buttonText: ratio(buttonStyle.color, buttonStyle.backgroundColor),
      buttonBoundary: ratio(buttonStyle.backgroundColor, surface),
    }
  })
  expect(contactContrast.retentionOnSurface).toBeGreaterThanOrEqual(4.5)
  expect(contactContrast.buttonText).toBeGreaterThanOrEqual(4.5)
  expect(contactContrast.buttonBoundary).toBeGreaterThanOrEqual(3)
  const projectLink = page.getByRole('link', { name: card('en').title })
  await projectLink.focus()
  await expect(projectLink).toBeFocused()
  await page.keyboard.press('Enter')
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= document.documentElement.clientWidth)).toBe(true)
  await expectNoSeriousAxe(page)

  await page.goto('/en/privacy')
  await expectNoSeriousAxe(page)
  await page.goto('/en/not-a-route')
  await expect(page.getByRole('heading', { level: 1 })).toContainText('not found')
  await expectNoSeriousAxe(page)
})

test('makes asynchronously inserted content immediately visible for reduced motion', async ({ page }) => {
  await page.emulateMedia({ reducedMotion: 'reduce' })
  await installPublishedApi(page)
  await page.goto('/zh-CN')
  await expect(page.getByRole('heading', { level: 1 })).toHaveText(site('zh-CN').hero.displayName)
  const hiddenRevealCount = await page.locator('.reveal').evaluateAll((nodes) => nodes.filter((node) => {
    const style = getComputedStyle(node)
    return style.opacity === '0' || style.transform !== 'none' || style.transitionDuration !== '0s'
  }).length)
  expect(hiddenRevealCount).toBe(0)
})
