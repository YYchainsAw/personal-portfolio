import type { PageBootstrap } from '@/types/public'

const escapedJson = (value: unknown) => JSON.stringify(value)
  .replaceAll('<', '\\u003c')
  .replaceAll('>', '\\u003e')
  .replaceAll('&', '\\u0026')
  .replaceAll('\u2028', '\\u2028')
  .replaceAll('\u2029', '\\u2029')

export function renderServerHtml(payload: PageBootstrap) {
  const base = 'http://127.0.0.1:4175'
  const path = payload.kind === 'home'
    ? `/${payload.locale}`
    : payload.kind === 'privacy'
      ? `/${payload.locale}/privacy`
      : `/${payload.locale}/projects/${payload.project.slug}`
  const title = payload.kind === 'project'
    ? payload.project.seoTitle
    : payload.kind === 'privacy'
      ? `${payload.site.privacy.title} · ${payload.site.identity.displayName}`
      : payload.site.seo.title
  const h1 = payload.kind === 'project'
    ? payload.project.title
    : payload.kind === 'privacy'
      ? payload.site.privacy.title
      : payload.site.hero.displayName
  const alternatePath = (locale: 'zh-CN' | 'en') => payload.kind === 'home'
    ? `/${locale}`
    : payload.kind === 'privacy'
      ? `/${locale}/privacy`
      : `/${locale}/projects/${payload.project.slug}`

  return `<!doctype html><html lang="${payload.locale}"><head>
    <meta charset="UTF-8"><meta name="viewport" content="width=device-width, initial-scale=1.0">
    <title>${title}</title><meta name="description" content="${payload.site.seo.description}">
    <link rel="canonical" href="${base}${path}">
    <link rel="alternate" hreflang="zh-CN" href="${base}${alternatePath('zh-CN')}">
    <link rel="alternate" hreflang="en" href="${base}${alternatePath('en')}">
    <meta property="og:type" content="${payload.kind === 'project' ? 'article' : 'website'}">
    <meta property="og:title" content="${title}"><meta property="og:url" content="${base}${path}">
    <script type="application/ld+json" data-portfolio-seo>{"@context":"https://schema.org","@type":"${payload.kind === 'project' ? 'CreativeWork' : 'WebPage'}"}</script>
  </head><body><div id="app">
    <a class="skip-link" href="#main-content">${payload.site.accessibility.skip}</a>
    <header data-server-shell>Published navigation</header>
    <main id="main-content" tabindex="-1"><h1>${h1}</h1></main>
  </div><template id="__PORTFOLIO_DATA__">${escapedJson(payload)}</template>
  <script type="module" src="/src/main.ts"></script></body></html>`
}
