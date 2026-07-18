import type { Locale, PublicMedia, PublicProject, PublicSite } from '@/types/public'

export type SeoPage =
  | { kind: 'home'; locale: Locale; site: PublicSite }
  | { kind: 'project'; locale: Locale; site: PublicSite; project: PublicProject; cover: PublicMedia | null }
  | { kind: 'privacy'; locale: Locale; site: PublicSite }

export function publicBaseUrl(): string {
  const configured = import.meta.env.VITE_PUBLIC_BASE_URL?.trim()
  if (configured) {
    const url = new URL(configured)
    if (url.protocol !== 'https:' || url.pathname !== '/' || url.search || url.hash) {
      throw new Error('VITE_PUBLIC_BASE_URL must be an HTTPS origin')
    }
    return url.origin
  }
  if (import.meta.env.DEV) return window.location.origin
  throw new Error('VITE_PUBLIC_BASE_URL is required for production SEO')
}

interface SeoDocument {
  title: string
  description: string
  canonical: string
  alternates: Record<Locale, string>
  ogType: 'website' | 'article'
  image: string | null
  structuredData: Record<string, unknown>
}

const pathFor = (page: SeoPage, locale: Locale) => page.kind === 'home'
  ? `/${locale}`
  : page.kind === 'privacy'
    ? `/${locale}/privacy`
    : `/${locale}/projects/${encodeURIComponent(page.project.slug)}`

export function buildSeoPage(page: SeoPage, baseUrl: string): SeoDocument {
  const absolute = (path: string) => new URL(path, baseUrl).href
  const canonical = absolute(pathFor(page, page.locale))
  const alternates = {
    'zh-CN': absolute(pathFor(page, 'zh-CN')),
    en: absolute(pathFor(page, 'en')),
  }

  if (page.kind === 'project') return {
    title: page.project.seoTitle,
    description: page.project.seoDescription,
    canonical,
    alternates,
    ogType: 'article',
    image: page.cover ? absolute(page.cover.src) : null,
    structuredData: {
      '@context': 'https://schema.org', '@type': 'CreativeWork', name: page.project.title,
      description: page.project.summary, url: canonical,
    },
  }

  const title = page.kind === 'privacy'
    ? `${page.site.privacy.title} · ${page.site.identity.displayName}`
    : page.site.seo.title
  return {
    title,
    description: page.site.seo.description,
    canonical,
    alternates,
    ogType: 'website',
    image: page.kind === 'home' && page.site.hero.media ? absolute(page.site.hero.media.src) : null,
    structuredData: page.kind === 'home'
      ? {
          '@context': 'https://schema.org', '@type': 'Person', name: page.site.identity.displayName,
          url: canonical, sameAs: page.site.socialLinks.map((link) => link.url),
        }
      : { '@context': 'https://schema.org', '@type': 'WebPage', name: page.site.privacy.title, url: canonical },
  }
}

function ensureMeta(doc: Document, selector: string, attributes: Record<string, string>) {
  let element = doc.head.querySelector<HTMLMetaElement>(selector)
  if (!element) { element = doc.createElement('meta'); doc.head.append(element) }
  Object.entries(attributes).forEach(([name, value]) => element!.setAttribute(name, value))
}

function ensureLink(doc: Document, selector: string, attributes: Record<string, string>) {
  let element = doc.head.querySelector<HTMLLinkElement>(selector)
  if (!element) { element = doc.createElement('link'); doc.head.append(element) }
  Object.entries(attributes).forEach(([name, value]) => element!.setAttribute(name, value))
}

export function applySeo(page: SeoPage, baseUrl: string, doc: Document = document) {
  const seo = buildSeoPage(page, baseUrl)
  doc.title = seo.title
  ensureMeta(doc, 'meta[name="description"]', { name: 'description', content: seo.description })
  ensureMeta(doc, 'meta[property="og:type"]', { property: 'og:type', content: seo.ogType })
  ensureMeta(doc, 'meta[property="og:title"]', { property: 'og:title', content: seo.title })
  ensureMeta(doc, 'meta[property="og:description"]', { property: 'og:description', content: seo.description })
  ensureMeta(doc, 'meta[property="og:url"]', { property: 'og:url', content: seo.canonical })
  if (seo.image) ensureMeta(doc, 'meta[property="og:image"]', { property: 'og:image', content: seo.image })
  else doc.head.querySelector('meta[property="og:image"]')?.remove()
  ensureLink(doc, 'link[rel="canonical"]', { rel: 'canonical', href: seo.canonical })
  doc.head.querySelectorAll('link[rel="alternate"][hreflang]').forEach((node) => node.remove())
  for (const locale of ['zh-CN', 'en'] as const) {
    ensureLink(doc, `link[rel="alternate"][hreflang="${locale}"]`, {
      rel: 'alternate', hreflang: locale, href: seo.alternates[locale],
    })
  }
  doc.head.querySelector('meta[name="robots"][data-route-noindex]')?.remove()
  let script = doc.head.querySelector<HTMLScriptElement>('script[type="application/ld+json"][data-portfolio-seo]')
  if (!script) {
    script = doc.createElement('script')
    script.type = 'application/ld+json'
    script.dataset.portfolioSeo = ''
    doc.head.append(script)
  }
  script.textContent = JSON.stringify(seo.structuredData).replaceAll('<', '\\u003c')
}

function applyNoindexSeo(title: string, doc: Document) {
  doc.title = title
  let robots = doc.head.querySelector<HTMLMetaElement>('meta[name="robots"][data-route-noindex]')
  if (!robots) { robots = doc.createElement('meta'); doc.head.append(robots) }
  robots.name = 'robots'
  robots.content = 'noindex,follow'
  robots.dataset.routeNoindex = ''
  doc.head.querySelector('meta[name="description"]')?.remove()
  doc.head.querySelectorAll('meta[property^="og:"]').forEach((node) => node.remove())
  doc.head.querySelector('link[rel="canonical"]')?.remove()
  doc.head.querySelectorAll('link[rel="alternate"][hreflang]').forEach((node) => node.remove())
  doc.head.querySelector('script[data-portfolio-seo]')?.remove()
}

export function applyNotFoundSeo(locale: Locale, doc: Document = document) {
  applyNoindexSeo(locale === 'zh-CN' ? '页面未找到 · 易嘉轩' : 'Page not found · Yi Jiaxuan', doc)
}

export function applyProjectUnavailableSeo(locale: Locale, doc: Document = document) {
  applyNoindexSeo(locale === 'zh-CN' ? '作品暂时无法加载 · 易嘉轩' : 'Project unavailable · Yi Jiaxuan', doc)
}
