import { nextTick } from 'vue'
import { flushPromises } from '@vue/test-utils'
import { describe, expect, it, vi } from 'vitest'

const cases = [
  { path: '/zh-CN', kind: 'home', locale: 'zh-CN', slug: undefined, heroMedia: true },
  { path: '/en', kind: 'home', locale: 'en', slug: undefined, heroMedia: true },
  { path: '/en?fixture=no-hero', kind: 'home', locale: 'en', slug: undefined, heroMedia: false },
  {
    path: '/zh-CN/projects/ue-study',
    kind: 'project',
    locale: 'zh-CN',
    slug: 'ue-study',
    heroMedia: true,
  },
  {
    path: '/en/projects/ue-study',
    kind: 'project',
    locale: 'en',
    slug: 'ue-study',
    heroMedia: true,
  },
  { path: '/zh-CN/privacy', kind: 'privacy', locale: 'zh-CN', slug: undefined, heroMedia: true },
  { path: '/en/privacy', kind: 'privacy', locale: 'en', slug: undefined, heroMedia: true },
] as const

describe('production SSR bootstrap hand-off', () => {
  it.each(cases)(
    'keeps published $kind content visible at $path when JavaScript starts',
    async ({ path, kind, locale, slug, heroMedia }) => {
      const marker = `published-${kind}-${locale}${slug ? `-${slug}` : ''}`
      const media = {
        assetId: '00000000-0000-0000-0000-000000000010',
        variant: 'w1200',
        src: '/api/public/media/00000000-0000-0000-0000-000000000010/w1200',
        srcset: '/api/public/media/00000000-0000-0000-0000-000000000010/w1200 1200w',
        alt: marker,
        caption: '',
        credit: '',
        sourceUrl: '',
        width: 1200,
        height: 800,
      }
      const site = {
        identity: {
          monogram: 'YJX',
          displayName: marker,
          secondaryName: 'Yi Jiaxuan',
          email: 'owner@example.test',
        },
        seo: { title: marker, description: marker },
        accessibility: {
          skip: locale === 'zh-CN' ? '跳到主要内容' : 'Skip to main content',
          primaryNav: 'Primary',
          mobileNav: 'Mobile',
          openMenu: 'Open',
          closeMenu: 'Close',
          language: 'Language',
          backToTop: 'Top',
          projectTags: 'Tags',
        },
        navigation: [
          { target: '#about', sortOrder: 0, label: 'About' },
          { target: '#work', sortOrder: 1, label: 'Work' },
          { target: '#roadmap', sortOrder: 2, label: 'Roadmap' },
          { target: '#contact', sortOrder: 3, label: 'Contact' },
        ],
        hero: {
          eyebrow: marker,
          displayName: marker,
          secondaryName: 'Yi Jiaxuan',
          role: 'Game developer',
          headline: marker,
          introduction: marker,
          availability: 'Available',
          primaryCta: 'Work',
          secondaryCta: 'Roadmap',
          visualLabel: 'Visual',
          stageLabel: 'Learning UE',
          objectPosition: '50% 50%',
          credit: '',
          sourceUrl: '',
          media: heroMedia ? media : null,
        },
        about: {
          label: 'About',
          title: 'About',
          statement: marker,
          focusLabel: 'Focus',
          focusTitle: 'UE',
          focusIntro: marker,
          facts: [],
          skills: [],
        },
        work: {
          label: 'Work',
          title: 'Work',
          introduction: marker,
          imageNotice: '',
          openSlotLabel: 'Next',
          openSlotTitle: 'More work soon',
          openSlotText: marker,
          openSlotMeta: 'Expandable',
        },
        roadmap: { label: 'Roadmap', title: 'Roadmap', introduction: marker, stages: [] },
        contact: {
          label: 'Contact',
          title: 'Contact',
          introduction: marker,
          emailLabel: 'Email',
          email: 'owner@example.test',
          workCta: 'Work',
          roadmapCta: 'Roadmap',
          footerNote: marker,
        },
        privacy: { title: marker, html: `<p>${marker}</p>` },
        socialLinks: [],
        resume: { label: 'Resume', documentDate: '2026-07-19', href: '/resume.pdf' },
      }
      const project = {
        projectId: '00000000-0000-0000-0000-000000000001',
        slug: slug || 'ue-study',
        number: '01',
        featured: true,
        status: 'Published',
        eyebrow: 'Project',
        title: marker,
        summary: marker,
        seoTitle: marker,
        seoDescription: marker,
        tags: [],
        skills: [],
        media: [media],
        blocks: [],
      }
      const bootstrap = {
        kind,
        locale,
        site,
        ...(kind === 'home' ? { catalog: [] } : {}),
        ...(kind === 'project' ? { catalog: [], project } : {}),
      }

      window.history.replaceState({}, '', path)
      const fetcher = vi.fn()
      vi.stubGlobal('fetch', fetcher)
      document.body.innerHTML = `
      <div id="app"><main id="main-content"><h1>${marker}</h1></main></div>
      <template id="__PORTFOLIO_DATA__">${JSON.stringify(bootstrap)}</template>
    `

      vi.resetModules()
      const { app, router } = await import('@/main')
      await router.isReady()
      await flushPromises()
      await nextTick()

      expect(document.querySelector('#app')?.textContent).toContain(marker)
      expect(document.querySelectorAll('#app > main')).toHaveLength(1)
      expect(document.querySelectorAll('#app h1')).toHaveLength(1)
      expect(document.querySelectorAll('#app > header')).toHaveLength(
        kind === 'project' || kind === 'privacy' ? 1 : 0,
      )
      expect(document.querySelectorAll('#app > main header.premiere-header')).toHaveLength(
        kind === 'home' ? 1 : 0,
      )
      expect(fetcher).not.toHaveBeenCalled()
      app.unmount()
    },
    15_000,
  )
})
