import { readFileSync } from 'node:fs'
import { expect, it } from 'vitest'

it('keeps the route authoritative without an unused production locale preference', () => {
  const source = readFileSync('src/composables/useLocale.ts', 'utf8')
  expect(source).not.toContain('portfolio.locale')
  expect(source).not.toContain('localStorage')
})
