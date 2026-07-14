import fs from 'node:fs/promises'
import path from 'node:path'
import { fileURLToPath, pathToFileURL } from 'node:url'

const baseUrl = 'http://127.0.0.1:4173/'
const scriptDir = path.dirname(fileURLToPath(import.meta.url))
const outputDir = path.resolve(scriptDir, '../artifacts/design-qa')
const localeKey = 'portfolio.locale'

const imageDataUrl = async (filePath) =>
  `data:image/png;base64,${(await fs.readFile(filePath)).toString('base64')}`

const canAccess = async (filePath) => {
  try {
    await fs.access(filePath)
    return true
  } catch {
    return false
  }
}

const resolvePlaywrightRoot = async () => {
  if (process.env.PLAYWRIGHT_ROOT) return process.env.PLAYWRIGHT_ROOT

  const cacheRoot = path.join(process.env.LOCALAPPDATA ?? '', 'ms-playwright-go')
  if (!(await canAccess(cacheRoot))) return undefined

  const versions = (await fs.readdir(cacheRoot, { withFileTypes: true }))
    .filter((entry) => entry.isDirectory())
    .map((entry) => entry.name)
    .sort((left, right) => right.localeCompare(left, undefined, { numeric: true }))

  for (const version of versions) {
    const candidate = path.join(cacheRoot, version, 'package')
    if (await canAccess(path.join(candidate, 'index.mjs'))) return candidate
  }

  return undefined
}

const resolveBrowserPath = async () => {
  const candidates = [
    process.env.PLAYWRIGHT_BROWSER_PATH,
    path.join(process.env.LOCALAPPDATA ?? '', 'Google/Chrome/Application/chrome.exe'),
    path.join(process.env.PROGRAMFILES ?? '', 'Google/Chrome/Application/chrome.exe'),
    path.join(process.env['PROGRAMFILES(X86)'] ?? '', 'Microsoft/Edge/Application/msedge.exe'),
  ].filter(Boolean)

  for (const candidate of candidates) {
    if (await canAccess(candidate)) return candidate
  }

  return undefined
}

const playwrightRoot = await resolvePlaywrightRoot()
const browserPath = await resolveBrowserPath()

if (!playwrightRoot || !browserPath) {
  throw new Error(
    'A Playwright package and Chrome/Edge executable are required. Set PLAYWRIGHT_ROOT and PLAYWRIGHT_BROWSER_PATH when auto-discovery is unavailable.',
  )
}

const { chromium } = await import(pathToFileURL(path.join(playwrightRoot, 'index.mjs')).href)
await fs.mkdir(outputDir, { recursive: true })

const browser = await chromium.launch({
  executablePath: browserPath,
  headless: true,
  args: ['--disable-gpu', '--hide-scrollbars'],
})

const report = {
  url: baseUrl,
  generatedAt: new Date().toISOString(),
  browserPath,
  deviceScaleFactor: 2,
  checks: {},
  desktop: {},
  mobile: {},
  reducedMotion: {},
  screenshots: [],
  consoleErrors: [],
  pageErrors: [],
  failures: [],
}

const check = (name, pass, details = {}) => {
  report.checks[name] = { pass, ...details }
  if (!pass) report.failures.push(name)
}

const recordScreenshot = async (page, name, options = {}) => {
  const filePath = path.join(outputDir, name)
  await page.screenshot({ path: filePath, animations: 'disabled', ...options })
  report.screenshots.push(name)
}

const waitForReady = async (page) => {
  await page.evaluate(() => document.fonts.ready)
  await page.waitForFunction(() =>
    Array.from(document.images).every((image) => image.complete && image.naturalWidth > 0),
  )
  await page.waitForTimeout(450)
}

const openPage = async (
  viewport,
  { reducedMotion = 'no-preference', mobile = false, resetLocale = true } = {},
) => {
  const context = await browser.newContext({
    viewport,
    deviceScaleFactor: 2,
    reducedMotion,
    isMobile: mobile,
    hasTouch: mobile,
  })
  const page = await context.newPage()

  page.on('console', (message) => {
    if (message.type() !== 'error') return
    report.consoleErrors.push({ url: page.url(), message: message.text() })
  })
  page.on('pageerror', (error) => {
    report.pageErrors.push({ url: page.url(), message: error.message })
  })

  await page.goto(baseUrl, { waitUntil: 'networkidle' })
  if (resetLocale) {
    await page.evaluate((key) => localStorage.removeItem(key), localeKey)
    await page.reload({ waitUntil: 'networkidle' })
  }
  await waitForReady(page)

  return { context, page }
}

const revealPage = async (page) => {
  const targets = page.locator('[data-reveal]')
  for (let index = 0; index < (await targets.count()); index += 1) {
    await targets.nth(index).scrollIntoViewIfNeeded()
    await page.waitForTimeout(70)
  }
  await page.evaluate(() => window.scrollTo({ top: 0, behavior: 'instant' }))
  await page.waitForTimeout(300)
}

const overflowSnapshot = (page) =>
  page.evaluate(() => ({
    viewport: window.innerWidth,
    scrollWidth: document.documentElement.scrollWidth,
    overflow: Math.max(0, document.documentElement.scrollWidth - window.innerWidth),
  }))

try {
  const desktop = await openPage({ width: 1440, height: 1024 })

  const initialLocale = await desktop.page.evaluate((key) => ({
    htmlLang: document.documentElement.lang,
    stored: localStorage.getItem(key),
    heading: document.querySelector('#hero-title')?.textContent?.trim() ?? '',
  }), localeKey)
  report.desktop.initialLocale = initialLocale
  check('default locale is Chinese after clearing storage', initialLocale.htmlLang === 'zh-CN', {
    actual: initialLocale,
  })

  await recordScreenshot(desktop.page, 'zh-desktop-hero.png')
  await revealPage(desktop.page)
  await recordScreenshot(desktop.page, 'zh-desktop-full.png', { fullPage: true })

  const desktopOverflow = await overflowSnapshot(desktop.page)
  report.desktop.overflow = desktopOverflow
  check('desktop has no horizontal overflow', desktopOverflow.overflow <= 1, {
    actual: desktopOverflow,
  })

  const work = desktop.page.locator('#work')
  await work.scrollIntoViewIfNeeded()
  await desktop.page.waitForTimeout(350)
  await recordScreenshot(desktop.page, 'zh-desktop-work.png', { fullPage: false })

  const roadmap = desktop.page.locator('#roadmap')
  await roadmap.scrollIntoViewIfNeeded()
  await desktop.page.waitForTimeout(350)
  await recordScreenshot(desktop.page, 'zh-desktop-roadmap.png', { fullPage: false })

  await desktop.page.locator('.desktop-nav a[href="#about"]').click()
  await desktop.page.waitForTimeout(900)
  const desktopAnchor = await desktop.page.evaluate(() => ({
    hash: window.location.hash,
    top: Math.round(document.querySelector('#about')?.getBoundingClientRect().top ?? -1),
  }))
  report.desktop.aboutAnchor = desktopAnchor
  check('desktop anchor navigation reaches About', desktopAnchor.hash === '#about', {
    actual: desktopAnchor,
  })

  await desktop.page.locator('.site-header .language-switch button').nth(1).click()
  await desktop.page.waitForFunction(() => document.documentElement.lang === 'en')
  const englishLocale = await desktop.page.evaluate((key) => ({
    htmlLang: document.documentElement.lang,
    stored: localStorage.getItem(key),
    heading: document.querySelector('#hero-title')?.textContent?.trim() ?? '',
    englishPressed: document
      .querySelectorAll('.site-header .language-switch button')[1]
      ?.getAttribute('aria-pressed'),
  }), localeKey)
  report.desktop.englishLocale = englishLocale
  check(
    'English switch updates content, html lang, pressed state, and storage',
    englishLocale.htmlLang === 'en' &&
      englishLocale.stored === 'en' &&
      englishLocale.englishPressed === 'true' &&
      /jiaxuan/i.test(englishLocale.heading),
    { actual: englishLocale },
  )

  await desktop.page.evaluate(() => window.scrollTo({ top: 0, behavior: 'instant' }))
  await desktop.page.waitForTimeout(300)
  await recordScreenshot(desktop.page, 'en-desktop-hero.png')

  await desktop.page.reload({ waitUntil: 'networkidle' })
  await waitForReady(desktop.page)
  const persistedEnglish = await desktop.page.evaluate((key) => ({
    htmlLang: document.documentElement.lang,
    stored: localStorage.getItem(key),
    heading: document.querySelector('#hero-title')?.textContent?.trim() ?? '',
  }), localeKey)
  report.desktop.persistedEnglish = persistedEnglish
  check(
    'English locale persists after reload',
    persistedEnglish.htmlLang === 'en' &&
      persistedEnglish.stored === 'en' &&
      /jiaxuan/i.test(persistedEnglish.heading),
    { actual: persistedEnglish },
  )

  await desktop.page.locator('.site-header .language-switch button').nth(0).click()
  await desktop.page.waitForFunction(() => document.documentElement.lang === 'zh-CN')
  const switchedBack = await desktop.page.evaluate((key) => ({
    htmlLang: document.documentElement.lang,
    stored: localStorage.getItem(key),
    chinesePressed: document
      .querySelectorAll('.site-header .language-switch button')[0]
      ?.getAttribute('aria-pressed'),
  }), localeKey)
  report.desktop.switchedBack = switchedBack
  check(
    'Chinese switch restores html lang, pressed state, and storage',
    switchedBack.htmlLang === 'zh-CN' &&
      switchedBack.stored === 'zh-CN' &&
      switchedBack.chinesePressed === 'true',
    { actual: switchedBack },
  )
  await desktop.context.close()

  const mobile = await openPage(
    { width: 390, height: 844 },
    { mobile: true, resetLocale: true },
  )
  await recordScreenshot(mobile.page, 'zh-mobile-hero.png')

  const mobileOverflow = await overflowSnapshot(mobile.page)
  report.mobile.overflow = mobileOverflow
  check('mobile has no horizontal overflow', mobileOverflow.overflow <= 1, {
    actual: mobileOverflow,
  })

  const menuToggle = mobile.page.locator('.menu-toggle')
  await menuToggle.click()
  await mobile.page.locator('#mobile-menu').waitFor({ state: 'visible' })
  await mobile.page.waitForTimeout(350)
  const menuOpenState = await mobile.page.evaluate(() => {
    const menu = document.querySelector('#mobile-menu')
    const active = document.activeElement
    return {
      expanded: document.querySelector('.menu-toggle')?.getAttribute('aria-expanded'),
      focusInside: Boolean(menu && active && menu.contains(active)),
      activeTag: active?.tagName ?? '',
      activeText: active?.textContent?.trim() ?? '',
      bodyOverflow: document.body.style.overflow,
    }
  })
  report.mobile.menuOpen = menuOpenState
  check(
    'mobile menu opens, receives focus, and locks body scroll',
    menuOpenState.expanded === 'true' &&
      menuOpenState.focusInside &&
      menuOpenState.bodyOverflow === 'hidden',
    { actual: menuOpenState },
  )
  await recordScreenshot(mobile.page, 'zh-mobile-menu.png')

  await mobile.page.keyboard.press('Escape')
  await mobile.page.locator('#mobile-menu').waitFor({ state: 'detached' })
  await mobile.page.waitForTimeout(250)
  const menuEscapeState = await mobile.page.evaluate(() => ({
    expanded: document.querySelector('.menu-toggle')?.getAttribute('aria-expanded'),
    focusReturned: document.activeElement === document.querySelector('.menu-toggle'),
    bodyOverflow: document.body.style.overflow,
  }))
  report.mobile.menuClosedWithEscape = menuEscapeState
  check(
    'Escape closes mobile menu, restores focus, and unlocks body scroll',
    menuEscapeState.expanded === 'false' &&
      menuEscapeState.focusReturned &&
      menuEscapeState.bodyOverflow === '',
    { actual: menuEscapeState },
  )

  await menuToggle.click()
  await mobile.page.locator('#mobile-menu').waitFor({ state: 'visible' })
  await mobile.page.locator('#mobile-menu a[href="#work"]').click()
  await mobile.page.locator('#mobile-menu').waitFor({ state: 'detached' })
  await mobile.page.waitForTimeout(900)
  const mobileAnchor = await mobile.page.evaluate(() => ({
    hash: window.location.hash,
    bodyOverflow: document.body.style.overflow,
    top: Math.round(document.querySelector('#work')?.getBoundingClientRect().top ?? -1),
    focusId: document.activeElement?.id ?? '',
  }))
  report.mobile.workAnchor = mobileAnchor
  check(
    'mobile menu link closes menu, unlocks body, and reaches Work',
    mobileAnchor.hash === '#work' && mobileAnchor.bodyOverflow === '' && mobileAnchor.focusId === 'work-title',
    { actual: mobileAnchor },
  )
  await recordScreenshot(mobile.page, 'zh-mobile-work.png')

  await mobile.page.locator('#contact').scrollIntoViewIfNeeded()
  await mobile.page.waitForTimeout(450)
  await recordScreenshot(mobile.page, 'zh-mobile-contact.png')

  const mobileFinalOverflow = await overflowSnapshot(mobile.page)
  report.mobile.finalOverflow = mobileFinalOverflow
  check('mobile remains overflow-free after interactions', mobileFinalOverflow.overflow <= 1, {
    actual: mobileFinalOverflow,
  })
  await mobile.context.close()

  const reduced = await openPage(
    { width: 1440, height: 1024 },
    { reducedMotion: 'reduce', resetLocale: true },
  )
  const reducedMotion = await reduced.page.evaluate(() => {
    const reveal = document.querySelector('[data-reveal]')
    const availabilityDot = document.querySelector('.availability span')
    const heroImage = document.querySelector('.hero__visual > img')
    return {
      requested: window.matchMedia('(prefers-reduced-motion: reduce)').matches,
      revealOpacity: reveal ? getComputedStyle(reveal).opacity : null,
      revealTransform: reveal ? getComputedStyle(reveal).transform : null,
      revealTransitionDuration: reveal ? getComputedStyle(reveal).transitionDuration : null,
      availabilityAnimation: availabilityDot
        ? getComputedStyle(availabilityDot).animationName
        : null,
      heroTransitionDuration: heroImage ? getComputedStyle(heroImage).transitionDuration : null,
    }
  })
  report.reducedMotion = reducedMotion
  check(
    'reduced-motion preference disables reveal and decorative motion',
    reducedMotion.requested &&
      reducedMotion.revealOpacity === '1' &&
      ['none', 'matrix(1, 0, 0, 1, 0, 0)'].includes(reducedMotion.revealTransform) &&
      reducedMotion.availabilityAnimation === 'none' &&
      reducedMotion.heroTransitionDuration === '0s',
    { actual: reducedMotion },
  )
  await reduced.context.close()

  const comparisonContext = await browser.newContext({
    viewport: { width: 1440, height: 620 },
    deviceScaleFactor: 1,
  })
  const comparisonPage = await comparisonContext.newPage()
  const sourceImage = await imageDataUrl(path.join(outputDir, 'source-portfolio-cosmic.png'))
  const implementationImage = await imageDataUrl(path.join(outputDir, 'zh-desktop-hero.png'))
  await comparisonPage.setContent(`
    <!doctype html>
    <html>
      <head>
        <meta charset="utf-8">
        <style>
          * { box-sizing: border-box; }
          body { margin: 0; color: #17202c; background: #e8edf5; font-family: Arial, sans-serif; }
          .comparison { display: grid; grid-template-columns: 1fr 1fr; gap: 16px; width: 1440px; padding: 16px; }
          .panel { overflow: hidden; border: 1px solid #cbd5e1; border-radius: 12px; background: white; }
          .label { height: 42px; padding: 12px 14px; border-bottom: 1px solid #dbe2ea; font-size: 13px; font-weight: 700; }
          .frame { width: 100%; height: 520px; overflow: hidden; background: #f8fafc; }
          .frame img { width: 100%; height: 100%; object-fit: cover; object-position: center top; }
          .focused .frame { height: 350px; }
        </style>
      </head>
      <body>
        <section id="full" class="comparison">
          <article class="panel"><div class="label">Source structure / MotionSites Portfolio Cosmic</div><div class="frame"><img src="${sourceImage}"></div></article>
          <article class="panel"><div class="label">Implementation / Light bilingual game portfolio</div><div class="frame"><img src="${implementationImage}"></div></article>
        </section>
        <section id="focused" class="comparison focused">
          <article class="panel"><div class="label">Source focus / Navigation and hero hierarchy</div><div class="frame"><img src="${sourceImage}"></div></article>
          <article class="panel"><div class="label">Implementation focus / Navigation and hero hierarchy</div><div class="frame"><img src="${implementationImage}"></div></article>
        </section>
      </body>
    </html>
  `)
  await comparisonPage.locator('#full').screenshot({ path: path.join(outputDir, 'redesign-full-comparison.png') })
  await comparisonPage.locator('#focused').screenshot({ path: path.join(outputDir, 'redesign-focused-comparison.png') })
  report.screenshots.push('redesign-full-comparison.png', 'redesign-focused-comparison.png')
  await comparisonContext.close()
} catch (error) {
  report.failures.push('QA script completed without an unhandled exception')
  report.unhandledError = error instanceof Error ? error.stack ?? error.message : String(error)
} finally {
  await browser.close()
}

const uniqueConsoleErrors = new Map(
  report.consoleErrors.map((entry) => [`${entry.url}\n${entry.message}`, entry]),
)
const uniquePageErrors = new Map(report.pageErrors.map((entry) => [`${entry.url}\n${entry.message}`, entry]))
report.consoleErrors = [...uniqueConsoleErrors.values()]
report.pageErrors = [...uniquePageErrors.values()]

check('no browser console errors', report.consoleErrors.length === 0, {
  actual: report.consoleErrors,
})
check('no uncaught page errors', report.pageErrors.length === 0, {
  actual: report.pageErrors,
})

report.passed = report.failures.length === 0
await fs.writeFile(
  path.join(outputDir, 'browser-results.json'),
  `${JSON.stringify(report, null, 2)}\n`,
)

console.log(JSON.stringify(report, null, 2))
if (!report.passed) process.exitCode = 1
