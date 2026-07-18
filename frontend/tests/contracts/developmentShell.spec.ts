import { readFileSync } from 'node:fs'
import { expect, it } from 'vitest'

it('keeps Vite index.html as a neutral development shell, not production SEO', () => {
  const source = readFileSync('index.html', 'utf8')
  const doc = new DOMParser().parseFromString(source, 'text/html')

  expect(doc.title).toBe('Portfolio development shell')
  expect(doc.documentElement.lang).toBe('en')
  expect(doc.querySelector('meta[name="description"]')).toBeNull()
  expect(doc.querySelector('meta[property^="og:"]')).toBeNull()
  expect(doc.querySelector('link[rel="canonical"], link[rel="alternate"]')).toBeNull()
  expect(doc.querySelector('script[type="application/ld+json"]')).toBeNull()
  expect(doc.querySelector('link[rel="icon"]')?.getAttribute('href')).toBe('/favicon.svg')
  expect(doc.querySelector('#app')?.childElementCount).toBe(0)
  expect(doc.querySelector('script[type="module"]')?.getAttribute('src')).toBe('/src/main.ts')
})
