import { expect, it } from 'vitest'
import { applyNotFoundSeo, applyProjectUnavailableSeo, applySeo, buildSeoPage } from '@/services/seo'
import { card, project, site } from '../fixtures/publicSnapshots'

it('synchronizes canonical, paired hreflang, OG, and one JSON-LD node idempotently', () => {
  document.head.innerHTML = '<script type="application/ld+json" data-portfolio-seo>{"old":true}</script>'
  const page = { kind: 'home', locale: 'zh-CN', site: site('zh-CN') } as const
  applySeo(page, 'https://yychainsaw.xyz'); applySeo(page, 'https://yychainsaw.xyz')
  expect(document.querySelectorAll('link[rel="canonical"]')).toHaveLength(1)
  expect(document.querySelectorAll('link[rel="alternate"][hreflang]')).toHaveLength(2)
  expect(document.querySelector('meta[property="og:title"]')?.getAttribute('content')).toBe(page.site.seo.title)
  expect(document.querySelectorAll('script[data-portfolio-seo]')).toHaveLength(1)
  applyNotFoundSeo('zh-CN')
  expect(document.querySelector('script[data-portfolio-seo]')).toBeNull()
})

it('preserves shared slug across project alternates', () => {
  const seo = buildSeoPage({ kind: 'project', locale: 'en', site: site('en'), project: project('en'), cover: card('en').cover }, 'https://yychainsaw.xyz')
  expect(seo.alternates['zh-CN']).toBe('https://yychainsaw.xyz/zh-CN/projects/ue-study')
})

it('omits an Open Graph image when a published home has no hero media', () => {
  const withoutHeroMedia = site('en')
  withoutHeroMedia.hero.media = null
  document.head.innerHTML = '<meta property="og:image" content="https://stale.invalid/old.webp">'

  const seo = buildSeoPage({ kind: 'home', locale: 'en', site: withoutHeroMedia }, 'https://yychainsaw.xyz')
  applySeo({ kind: 'home', locale: 'en', site: withoutHeroMedia }, 'https://yychainsaw.xyz')

  expect(seo.image).toBeNull()
  expect(document.querySelector('meta[property="og:image"]')).toBeNull()
})

it('clears project metadata on 404 and reconstructs clean home metadata afterwards', () => {
  const projectPage = { kind: 'project', locale: 'en', site: site('en'), project: project('en'), cover: card('en').cover } as const
  applySeo(projectPage, 'https://yychainsaw.xyz')
  expect(document.querySelector('meta[property="og:image"]')).not.toBeNull()

  applyNotFoundSeo('en')
  expect(document.querySelector('meta[name="description"]')).toBeNull()
  expect(document.querySelector('meta[property^="og:"]')).toBeNull()
  expect(document.querySelector('link[rel="canonical"], link[rel="alternate"]')).toBeNull()
  expect(document.querySelector('script[data-portfolio-seo]')).toBeNull()

  applySeo({ kind: 'home', locale: 'en', site: site('en') }, 'https://yychainsaw.xyz')
  expect(document.querySelector('meta[name="robots"][data-route-noindex]')).toBeNull()
  expect(document.querySelector('link[rel="canonical"]')?.getAttribute('href')).toBe('https://yychainsaw.xyz/en')
  expect(document.querySelectorAll('link[rel="alternate"]')).toHaveLength(2)
  expect(document.querySelectorAll('script[data-portfolio-seo]')).toHaveLength(1)
})

it('removes every stale project signal while a replacement project is unavailable', () => {
  applySeo({ kind: 'project', locale: 'en', site: site('en'), project: project('en'), cover: card('en').cover }, 'https://yychainsaw.xyz')
  applyProjectUnavailableSeo('en')

  expect(document.title).toBe('Project unavailable · Yi Jiaxuan')
  expect(document.querySelector('meta[name="robots"]')?.getAttribute('content')).toBe('noindex,follow')
  expect(document.querySelector('meta[name="description"], meta[property^="og:"]')).toBeNull()
  expect(document.querySelector('link[rel="canonical"], link[rel="alternate"]')).toBeNull()
  expect(document.querySelector('script[data-portfolio-seo]')).toBeNull()
})
