import { readFileSync } from 'node:fs'
import { expect, it } from 'vitest'
import { buildSeoPage } from '@/services/seo'
import { site } from '../fixtures/publicSnapshots'

it('pins production SEO to the approved origin instead of any request Host', () => {
  expect(readFileSync('.env.production', 'utf8').trim()).toBe('VITE_PUBLIC_BASE_URL=https://yychainsaw.xyz')
  for (const requestHost of ['http://127.0.0.1:4175', 'https://www.yychainsaw.xyz', 'https://preview.invalid']) {
    window.history.replaceState({}, '', new URL('/en', requestHost).pathname)
    const seo = buildSeoPage({ kind: 'home', locale: 'en', site: site('en') }, 'https://yychainsaw.xyz')
    expect(seo.canonical).toBe('https://yychainsaw.xyz/en')
    expect(seo.structuredData.url).toBe('https://yychainsaw.xyz/en')
  }
})
