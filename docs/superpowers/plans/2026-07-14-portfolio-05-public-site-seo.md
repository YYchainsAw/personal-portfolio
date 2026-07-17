# Portfolio Public Site API and SEO Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Connect the existing portfolio design to published SITE, PROJECT_CATALOG, and PROJECT snapshots; add localized semantic routes and project/privacy/404 pages; implement the public contact and privacy-preserving opt-in analytics journeys; reuse server-embedded initial JSON; and preserve accessibility, responsive media, animation, and SEO behavior without retaining `portfolio.ts` as a second runtime content source.

**Architecture:** Keep `frontend/` as a Vue SPA for interaction, but treat Spring-rendered localized HTML plus an embedded versioned JSON payload as the initial page authority. A typed `portfolioApi` and mapper layer serves client navigation and retry states; route containers load data while the existing visual components remain presentational. Contact and analytics adapters share one memory-only plan-01 CSRF provider before calling the plan-06 public POST contracts; analytics creates identifiers only after opt-in and makes DNT suppression an earlier boundary than consent UI, storage, or CSRF acquisition. Server HTML owns indexable first response metadata/content, and the client SEO synchronizer updates those same fields only after in-app navigation.

**Tech Stack:** The versions already locked by `frontend/package-lock.json`: Vue 3.5.39, Vue Router 5.1.0, Vite 8.1.4, TypeScript 6.0.3, Vue Test Utils, Vitest, Playwright, and axe-core Playwright integration.

## Global Constraints

- Preserve the existing public-site Vue, Vue Router, Vite, and TypeScript versions; do not downgrade or rebuild it to match `admin-web`.
- Preserve the approved current public visual design, responsive behavior, focus handling, reduced-motion behavior, and bilingual content intent. This plan changes data/routing/SEO boundaries rather than redesigning the site.
- Runtime public content comes only from published `SITE`, `PROJECT_CATALOG`, and `PROJECT` snapshots. `frontend/src/data/portfolio.ts` remains the one-time import source for the approved exporter but no runtime component, route, composable, or mapper may import it after cutover.
- Supported locales are exactly `zh-CN` and `en`; public URLs are `/zh-CN`, `/en`, `/{locale}/projects/{slug}`, and `/{locale}/privacy`. `/` is a server `302` to `/zh-CN`; client development fallback must match it.
- A language switch preserves page semantics: home stays home, privacy stays privacy, and a project keeps the same shared ASCII slug. The route locale, not localStorage, is authoritative; localStorage only remembers a later root preference for non-production development.
- Server HTML embeds the exact published initial payload used to render the page in inert `<template id="__PORTFOLIO_DATA__">`. Vue consumes a matching payload once and must not issue a duplicate first-load request that could observe a newer revision.
- Initial JSON contains only published data and must match route kind, locale, and slug before reuse. Invalid or mismatched payloads are ignored and the normal public API path runs.
- Public GET APIs are exactly `GET /api/public/site?locale={locale}`, `GET /api/public/projects?locale={locale}`, and `GET /api/public/projects/{slug}?locale={locale}`; responses are published snapshots with browser-revalidated ETags.
- Every asynchronous route exposes loading, empty, safe error with trace ID, and retry states. An absent project produces the localized 404 route; a temporarily failed API remains retryable and does not masquerade as 404.
- Replace the mount-time reveal scan with a directive that observes every asynchronously inserted node. Reduced-motion users see content immediately and no content remains at `opacity: 0` because it arrived after mount.
- The server is authoritative for initial `title`, description, canonical, paired hreflang, Open Graph, JSON-LD, and indexable body. Client navigation must synchronize the same values without inventing URLs or structured data.
- Public media mirrors plan-03 `PublicMediaDto` fields `src`, `srcset`, intrinsic dimensions, alt, caption, credit, and sourceUrl. Components supply contextual `sizes`, never upscale beyond the supplied candidates, render hero attribution from `PublicSite.hero`, and visibly render each project media item's own credit with an HTTPS-only source link; the browser does not invent absent media fields.
- Public content blocks are already localized and sanitized in a published snapshot. The browser accepts only plan-03 `payload.html` for `MARKDOWN` and `privacy.html` for the privacy body; it never executes Markdown, accepts editor HTML, or reads draft/history media URLs.
- Render a bilingual contact form with exactly `name`, `email`, `subject`, `message`, hidden honeypot `website`, and required `privacyAccepted`. Acquire the plan-01 same-origin CSRF token first, send only that plan-06 body to `POST /api/public/contact` with the returned header/token, preserve entered values through retryable failures, and state that messages are retained for one year unless deleted earlier.
- Analytics is explicit opt-in and defaults off. Reject and withdraw create no events or analytics-triggered CSRF requests; withdrawal removes analytics identifiers. When `navigator.doNotTrack === '1'`, suppress the prompt, remove any existing visitor/session analytics IDs, and perform zero analytics-triggered CSRF/event requests.
- A consented browser visitor ID is 128 random bits encoded base64url in localStorage and rotates after 30 days. The session ID is independently 128 random bits in sessionStorage and rotates after 30 minutes of inactivity. Raw identifiers must never enter logs, URLs, rendered text, or error reports.
- Send only `PAGE_VIEW`, `PROJECT_VIEW`, `RESUME_DOWNLOAD`, `DEMO_DOWNLOAD`, and `OUTBOUND_CLICK` events with UUID event IDs, plan-06 page keys, batches of at most 20, and the shared CSRF response header/token to `POST /api/public/events`. Do not persist the event queue or collect arbitrary labels, query strings, user text, screen details, or browser metadata.
- Unknown `/api` paths must remain API `404` responses; no frontend or Nginx fallback may turn them into HTML.
- `npm --prefix frontend run build` outputs `frontend/dist/assets/*` and `frontend/dist/.vite/manifest.json`. Each release keeps both under `/opt/portfolio/releases/{releaseId}/public-assets/`; deployment copies hashed assets without overwrite into shared `/opt/portfolio/assets/`, and Nginx `/assets/` is a fixed alias to that shared directory. The same manifest is copied into the Spring image at classpath `/public-assets/.vite/manifest.json`, and its SHA-256 participates in the value injected as `PORTFOLIO_RELEASE_ID`; manifests from the newest three retained releases define the safe asset-cleanup set.
- Follow TDD, commit after every task, and restrict commits to `frontend/` plus this plan’s test fixtures/configuration.

---

## File and Responsibility Map

```text
frontend/
├── package.json                              add test commands only; preserve core versions
├── package-lock.json                         resolved test dependencies
├── vite.config.ts                            Vitest and local /api proxy
├── playwright.config.ts                      responsive/a11y route tests
├── index.html                                development shell; production HTML is server-rendered
├── src/
│   ├── main.ts                               initialize initial payload, router, reveal directive
│   ├── App.vue                               API-backed skip copy and RouterView
│   ├── router/index.ts                       locale/project/privacy/404 route graph
│   ├── types/public.ts                       published snapshot and block DTOs
│   ├── types/interactions.ts                 exact plan-06 contact/analytics request types
│   ├── services/portfolioApi.ts              public GET transport and ApiProblem
│   ├── services/initialPayload.ts            one-shot schema/route-checked JSON reader
│   ├── services/seo.ts                       client-navigation metadata synchronizer
│   ├── services/csrfClient.ts               shared in-memory Spring CSRF token provider
│   ├── services/contactApi.ts                strict public contact POST adapter
│   ├── services/analyticsClient.ts           consent-gated IDs, batching, and event POSTs
│   ├── mappers/homeMapper.ts                 SITE + CATALOG -> existing home view model
│   ├── stores/publicContent.ts               initial-data registry and API-backed route cache
│   ├── composables/useLocale.ts               route-derived locale and semantic switch
│   ├── composables/useAsyncRouteData.ts       loading/empty/error/retry lifecycle
│   ├── composables/useAnalyticsConsent.ts     default-off consent/DNT/withdraw state
│   ├── directives/reveal.ts                  async-safe IntersectionObserver directive
│   ├── components/common/AsyncState.vue       localized route state UI
│   ├── components/common/LanguageSwitch.vue    semantic path locale switch
│   ├── components/media/ResponsiveMedia.vue   picture/srcset/focal-point renderer
│   ├── components/contact/ContactForm.vue      bilingual validation and retryable submission
│   ├── components/analytics/AnalyticsConsent.vue accessible accept/reject/withdraw controls
│   ├── components/project/ContentBlockRenderer.vue exhaustive public block renderer
│   ├── views/HomePageView.vue                 home data container
│   ├── views/HomeView.vue                     existing design, converted to view-model props
│   ├── views/ProjectPageView.vue              project route data container
│   ├── views/ProjectDetailView.vue            project metadata and content blocks
│   ├── views/PrivacyView.vue                  published privacy content
│   └── views/NotFoundView.vue                 localized 404
└── tests/
    ├── fixtures/publicSnapshots.ts            fixed SITE/CATALOG/PROJECT payloads
    ├── fixtures/serverHtml.ts                 canonical/hreflang/OG/JSON-LD HTML contract
    ├── unit/                                  DTO/API/route/directive/renderer tests
    ├── contracts/                             no-static-runtime and initial-JSON checks
    └── e2e/                                   locale, responsive, a11y, navigation, retry tests
```

## Cross-Task Public Interfaces

The published API is locale-specific; no response returns drafts or both mutable translation rows. The frontend uses these primitives:

```ts
export const locales = ['zh-CN', 'en'] as const
export type Locale = (typeof locales)[number]

export interface PublicApiProblemBody {
  type: string
  title: string
  status: number
  code: string
  traceId: string
}

export interface PublishedEnvelope<T> {
  revisionVersion: number
  checksum: string
  data: T
}
```

Every unsafe public request remains behind Spring Security's existing CSRF boundary. There is exactly one token endpoint: anonymous, same-origin `GET /api/admin/auth/csrf`, returning plan-01 `CsrfResponse { headerName, parameterName, token }` with `Cache-Control: no-store`. Contact and consented analytics share one memory-only provider; no `/api/public/csrf` route, cookie-reading fallback, storage copy, or logged token is allowed. Each public POST calls the provider immediately beforehand and sends the returned `token` under the returned `headerName`.

The server-embedded payload is this discriminated union and is consumed at most once:

```ts
export type PageBootstrap =
  | { kind: 'home'; locale: Locale; site: PublicSite; catalog: ProjectCard[] }
  | { kind: 'project'; locale: Locale; site: PublicSite; catalog: ProjectCard[]; project: PublicProject }
  | { kind: 'privacy'; locale: Locale; site: PublicSite }
```

### Task 1: Add the public-site unit/browser test harness without changing runtime versions

**Files:**
- Modify: `frontend/package.json`
- Modify: `frontend/package-lock.json` (generated by npm)
- Modify: `frontend/vite.config.ts`
- Create: `frontend/src/test/setup.ts`
- Create: `frontend/src/test/baseline.spec.ts`

**Interfaces:**
- Consumes: existing Vue/Vite application and current lockfile.
- Produces: `test:unit`, `test:e2e`, jsdom setup, and a local `/api -> http://127.0.0.1:18080` development proxy.

- [ ] **Step 1: Add a baseline failing test before test configuration**

```ts
// frontend/src/test/baseline.spec.ts
import { describe, expect, it } from 'vitest'

describe('public-site baseline', () => {
  it('provides the deterministic reduced-motion query used by reveal tests', () => {
    expect(matchMedia('(prefers-reduced-motion: reduce)').matches).toBe(false)
  })
})
```

Add these scripts without changing existing build/lint/format entries:

```json
"test": "vitest",
"test:unit": "vitest run",
"test:e2e": "playwright test"
```

Add exact development dependencies `@playwright/test@1.58.2`, `@axe-core/playwright@4.11.1`, `@vue/test-utils@2.4.6`, `jsdom@28.0.0`, and `vitest@4.0.18`. Do not alter the installed core versions recorded in the current lockfile.

- [ ] **Step 2: Install and verify the test initially fails because Vite has no test environment**

Run: `npm --prefix frontend install`

Expected: lockfile updates only for test packages and their transitive dependencies.

Run: `npm --prefix frontend run test:unit -- src/test/baseline.spec.ts`

Expected: FAIL with `ReferenceError: matchMedia is not defined` until jsdom setup is active.

- [ ] **Step 3: Extend the existing Vite config rather than replacing it**

```ts
// frontend/vite.config.ts
import { fileURLToPath, URL } from 'node:url'
import vue from '@vitejs/plugin-vue'
import { defineConfig } from 'vitest/config'

export default defineConfig({
  plugins: [vue()],
  resolve: { alias: { '@': fileURLToPath(new URL('./src', import.meta.url)) } },
  server: { proxy: { '/api': 'http://127.0.0.1:18080' } },
  test: { environment: 'jsdom', setupFiles: ['./src/test/setup.ts'], restoreMocks: true },
})
```

`setup.ts` clears document body and local/session storage after each test and installs deterministic `matchMedia`, `scrollTo`, and `requestAnimationFrame` stubs without changing production globals.

- [ ] **Step 4: Verify baseline and pinned runtime versions**

Run: `npm --prefix frontend run test:unit -- src/test/baseline.spec.ts`

Expected: PASS, 1 test.

Run: `npm --prefix frontend ls vue vue-router vite typescript --depth=0`

Expected: Vue `3.5.39`, Vue Router `5.1.0`, Vite `8.1.4`, TypeScript `6.0.3`; no downgrade.

- [ ] **Step 5: Commit the test-only foundation**

```bash
git add frontend/package.json frontend/package-lock.json frontend/vite.config.ts frontend/src/test
git commit -m "test(public): add API migration test harness"
```

### Task 2: Define published DTOs, responsive media, API errors, and GET transport

**Files:**
- Create: `frontend/src/types/public.ts`
- Create: `frontend/src/services/portfolioApi.ts`
- Create: `frontend/tests/fixtures/publicSnapshots.ts`
- Test: `frontend/tests/unit/portfolioApi.spec.ts`
- Test: `frontend/tests/unit/publicTypes.spec.ts`

**Interfaces:**
- Consumes: the three approved public GET endpoints.
- Produces: exact TypeScript mirrors `PublicSite`, `PublicMedia`, `ProjectCard`, `PublicProject`, `PublicBlock`, plus `PublicApiProblem` and `portfolioApi`.

- [ ] **Step 1: Write failing transport tests for locale, slug, 404, and abort**

```ts
// frontend/tests/unit/portfolioApi.spec.ts
import { afterEach, describe, expect, it, vi } from 'vitest'
import { createPortfolioApi, PublicApiProblem } from '@/services/portfolioApi'

describe('portfolioApi', () => {
  afterEach(() => vi.unstubAllGlobals())

  it('encodes locale and shared ASCII slug', async () => {
    const fetcher = vi.fn().mockResolvedValue(new Response(JSON.stringify({ revisionVersion: 2, checksum: 'abc', data: { projectId: 'p1', slug: 'ue-study' } }), { status: 200, headers: { 'Content-Type': 'application/json' } }))
    const api = createPortfolioApi(fetcher)
    await api.getProject('en', 'ue-study')
    expect(fetcher).toHaveBeenCalledWith('/api/public/projects/ue-study?locale=en', expect.objectContaining({ credentials: 'same-origin' }))
  })

  it('preserves a safe API problem for retry/404 decisions', async () => {
    const fetcher = vi.fn().mockResolvedValue(new Response(JSON.stringify({ type: 'not_found', title: '未找到项目', status: 404, code: 'PROJECT_NOT_FOUND', traceId: 't1' }), { status: 404, headers: { 'Content-Type': 'application/problem+json' } }))
    await expect(createPortfolioApi(fetcher).getProject('zh-CN', 'missing')).rejects.toMatchObject({ body: { status: 404, code: 'PROJECT_NOT_FOUND' } })
  })
})
```

- [ ] **Step 2: Run tests and observe missing DTO/API failures**

Run: `npm --prefix frontend run test:unit -- tests/unit/portfolioApi.spec.ts tests/unit/publicTypes.spec.ts`

Expected: FAIL because the types and service do not exist.

- [ ] **Step 3: Implement the complete published DTO union**

```ts
// exact TypeScript mirrors in frontend/src/types/public.ts
export interface PublicMedia { assetId: string; variant: string; src: string; srcset: string; alt: string; caption: string; credit: string; sourceUrl: string; width: number; height: number }
export interface PublicSeo { title: string; description: string }
export interface PublicIdentity { monogram: string; displayName: string; secondaryName: string; email: string }
export interface PublicAccessibility { skip: string; primaryNav: string; mobileNav: string; openMenu: string; closeMenu: string; language: string; backToTop: string; projectTags: string }
export interface PublicNavigationItem { target: string; sortOrder: number; label: string }
export interface PublicHero { eyebrow: string; displayName: string; secondaryName: string; role: string; headline: string; introduction: string; availability: string; primaryCta: string; secondaryCta: string; visualLabel: string; stageLabel: string; objectPosition: string; credit: string; sourceUrl: string; media: PublicMedia }
export interface PublicFact { label: string; value: string }
export interface PublicSkill { name: string; status: string }
export interface PublicAbout { label: string; title: string; statement: string; focusLabel: string; focusTitle: string; focusIntro: string; facts: PublicFact[]; skills: PublicSkill[] }
export interface PublicWork { label: string; title: string; introduction: string; imageNotice: string; openSlotLabel: string; openSlotTitle: string; openSlotText: string; openSlotMeta: string }
export interface PublicRoadmapStage { id: string; number: string; period: string; title: string; summary: string; outcomes: string[] }
export interface PublicRoadmap { label: string; title: string; introduction: string; stages: PublicRoadmapStage[] }
export interface PublicContact { label: string; title: string; introduction: string; emailLabel: string; email: string; workCta: string; roadmapCta: string; footerNote: string }
export interface PublicPrivacy { title: string; html: string }
export interface PublicSocialLink { platform: string; url: string }
export interface PublicResume { label: string; documentDate: string; href: string }
export interface PublicSite { identity: PublicIdentity; seo: PublicSeo; accessibility: PublicAccessibility; navigation: PublicNavigationItem[]; hero: PublicHero; about: PublicAbout; work: PublicWork; roadmap: PublicRoadmap; contact: PublicContact; privacy: PublicPrivacy; socialLinks: PublicSocialLink[]; resume: PublicResume }
export interface ProjectCard { projectId: string; slug: string; number: string; sortOrder: number; featured: boolean; status: string; eyebrow: string; title: string; summary: string; tags: string[]; cover: PublicMedia }
interface PublicBlockBase<T extends string, P> { id: string; type: T; sortOrder: number; width: string; alignment: string; emphasis: string; columns: number; payload: P & { type: T } }
export interface PublicMetric { id: string; numericValue: number | null; label: string; value: string; suffix: string }
export type PublicBlock =
  | PublicBlockBase<'MARKDOWN', { html: string }>
  | PublicBlockBase<'IMAGE', { media: PublicMedia }>
  | PublicBlockBase<'GALLERY', { media: PublicMedia[] }>
  | PublicBlockBase<'VIDEO', { provider: string; embedUrl: string; cover: PublicMedia | null; title: string; description: string }>
  | PublicBlockBase<'CODE', { code: string; language: string; showLineNumbers: boolean; title: string; description: string }>
  | PublicBlockBase<'QUOTE', { quote: string; source: string }>
  | PublicBlockBase<'METRICS', { metrics: PublicMetric[] }>
  | PublicBlockBase<'DOWNLOAD', { href: string; label: string; description: string; mimeType: string | null; byteSize: number | null }>
  | PublicBlockBase<'LINK', { href: string; openNewTab: boolean; label: string; description: string }>
export interface PublicProject { projectId: string; slug: string; number: string; featured: boolean; status: string; eyebrow: string; title: string; summary: string; seoTitle: string; seoDescription: string; tags: string[]; skills: string[]; media: PublicMedia[]; blocks: PublicBlock[] }
```

Include `PublishedEnvelope<T>`, `PageBootstrap`, `Locale`, and `PublicApiProblemBody` from Cross-Task Interfaces in this file. Fixtures contain one bilingual SITE pair, one catalog array per locale, and one nine-block project per locale.

- [ ] **Step 4: Implement fetch transport and verify**

```ts
// frontend/src/services/portfolioApi.ts
import type { Locale, ProjectCard, PublicProject, PublishedEnvelope, PublicApiProblemBody, PublicSite } from '@/types/public'
export class PublicApiProblem extends Error { constructor(readonly body: PublicApiProblemBody) { super(body.title); this.name = 'PublicApiProblem' } }

export function createPortfolioApi(fetcher: typeof fetch = fetch) {
  async function get<T>(url: string, signal?: AbortSignal): Promise<PublishedEnvelope<T>> {
    const response = await fetcher(url, { method: 'GET', credentials: 'same-origin', cache: 'no-cache', headers: { Accept: 'application/json' }, signal })
    const body = await response.json()
    if (!response.ok) throw new PublicApiProblem(body as PublicApiProblemBody)
    return body as PublishedEnvelope<T>
  }
  return {
    getSite: (locale: Locale, signal?: AbortSignal) => get<PublicSite>(`/api/public/site?locale=${encodeURIComponent(locale)}`, signal),
    getProjects: (locale: Locale, signal?: AbortSignal) => get<ProjectCard[]>(`/api/public/projects?locale=${encodeURIComponent(locale)}`, signal),
    getProject: (locale: Locale, slug: string, signal?: AbortSignal) => get<PublicProject>(`/api/public/projects/${encodeURIComponent(slug)}?locale=${encodeURIComponent(locale)}`, signal),
  }
}
export const portfolioApi = createPortfolioApi()
```

Run: `npm --prefix frontend run test:unit -- tests/unit/portfolioApi.spec.ts tests/unit/publicTypes.spec.ts`

Expected: PASS for all three URLs, encoded input, abort forwarding, published envelopes, safe problem conversion, and exhaustive block types.

- [ ] **Step 5: Commit the public API contract**

```bash
git add frontend/src/types/public.ts frontend/src/services/portfolioApi.ts frontend/tests/fixtures/publicSnapshots.ts frontend/tests/unit/portfolioApi.spec.ts frontend/tests/unit/publicTypes.spec.ts
git commit -m "feat(public): add published snapshot API contract"
```

### Task 3: Parse and consume route-matched initial JSON exactly once

**Files:**
- Create: `frontend/src/services/initialPayload.ts`
- Create: `frontend/src/stores/publicContent.ts`
- Test: `frontend/tests/unit/initialPayload.spec.ts`
- Test: `frontend/tests/unit/publicContent.spec.ts`

**Interfaces:**
- Consumes: `PageBootstrap`, route descriptor `{ kind, locale, slug? }`, and `portfolioApi`.
- Produces: `readInitialPayload`, `matchesInitialRoute`, and `createPublicContentStore(api, initial)` with home/project/privacy loaders.

- [ ] **Step 1: Write failing tests for safe parsing, mismatch rejection, and no duplicate request**

```ts
// frontend/tests/unit/initialPayload.spec.ts
import { expect, it } from 'vitest'
import { readInitialPayload, matchesInitialRoute } from '@/services/initialPayload'
import { homeInitialPayload } from '../fixtures/publicSnapshots'

it('reads the exact bootstrap JSON and removes it from future consumption', () => {
  document.body.innerHTML = `<template id="__PORTFOLIO_DATA__">${JSON.stringify(homeInitialPayload)}</template>`
  expect(readInitialPayload()).toEqual(homeInitialPayload)
  expect(readInitialPayload()).toBeNull()
})

it('rejects a locale or slug mismatch', () => {
  expect(matchesInitialRoute(homeInitialPayload, { kind: 'home', locale: 'en' })).toBe(false)
})
```

```ts
// frontend/tests/unit/publicContent.spec.ts
import { expect, it, vi } from 'vitest'
import { createPublicContentStore } from '@/stores/publicContent'
import { homeInitialPayload } from '../fixtures/publicSnapshots'

it('reuses matching HOME data without a first-load API request', async () => {
  const api = { getSite: vi.fn(), getProjects: vi.fn(), getProject: vi.fn() }
  const store = createPublicContentStore(api, homeInitialPayload)
  const result = await store.loadHome('zh-CN')
  expect(result.site).toBe(homeInitialPayload.site)
  expect(api.getSite).not.toHaveBeenCalled()
  expect(api.getProjects).not.toHaveBeenCalled()
})
```

- [ ] **Step 2: Run tests and verify missing initial-data behavior**

Run: `npm --prefix frontend run test:unit -- tests/unit/initialPayload.spec.ts tests/unit/publicContent.spec.ts`

Expected: FAIL because the parser and store do not exist.

- [ ] **Step 3: Implement schema checks and route matching without executing script content**

```ts
// frontend/src/services/initialPayload.ts
import { isLocale, type PageBootstrap, type Locale } from '@/types/public'
export type PageDescriptor = { kind: 'home' | 'privacy'; locale: Locale } | { kind: 'project'; locale: Locale; slug: string }

export function readInitialPayload(doc: Document = document): PageBootstrap | null {
  const node = doc.querySelector<HTMLTemplateElement>('#__PORTFOLIO_DATA__')
  if (!node) return null
  node.remove()
  try {
    const value = JSON.parse(node.content.textContent?.trim() ?? '') as Record<string, unknown>
    if (!isLocale(value.locale) || !['home', 'project', 'privacy'].includes(String(value.kind)) || typeof value.site !== 'object' || value.site === null) return null
    if ((value.kind === 'home' || value.kind === 'project') && !Array.isArray(value.catalog)) return null
    if (value.kind === 'project') {
      if (typeof value.project !== 'object' || value.project === null) return null
      if (typeof (value.project as Record<string, unknown>).slug !== 'string') return null
    }
    return value as PageBootstrap
  } catch { return null }
}

export function matchesInitialRoute(payload: PageBootstrap, route: PageDescriptor): boolean {
  if (payload.kind !== route.kind || payload.locale !== route.locale) return false
  return payload.kind !== 'project' || (route.kind === 'project' && payload.project.slug === route.slug)
}
```

`types/public.ts` exports `isLocale(value): value is Locale`. The API store checks that each response body has numeric `revisionVersion`, a nonblank checksum, and the expected data shape before caching; the bootstrap is already unwrapped because Spring generated it from the same publication reads used for HTML.

- [ ] **Step 4: Implement one-shot store loaders and verify no revision race**

The store owns one nullable initial payload. Its private `takeInitial(descriptor)` sets that reference to `null` before checking the descriptor, so even a mismatch is discarded permanently. `loadHome(locale)` reuses a matching `home`, otherwise runs `Promise.all([api.getSite(locale), api.getProjects(locale)])` and unwraps both envelopes. `loadProject(locale, slug)` reuses a matching `project`, otherwise requests and unwraps site/catalog/project in parallel. `loadPrivacy(locale)` reuses a matching `privacy`, otherwise requests and unwraps site. Successful API responses are cached by route key together with the returned revision versions only for the current browser session; rejected promises and `404` are never cached.

Run: `npm --prefix frontend run test:unit -- tests/unit/initialPayload.spec.ts tests/unit/publicContent.spec.ts`

Expected: PASS for home/project/privacy matching, mismatched fallback, invalid structure, invalid locale, malformed JSON, single consumption, no first-load fetch, and later-route fetch.

- [ ] **Step 5: Commit initial JSON reuse**

```bash
git add frontend/src/services/initialPayload.ts frontend/src/stores/publicContent.ts frontend/tests/unit/initialPayload.spec.ts frontend/tests/unit/publicContent.spec.ts
git commit -m "feat(public): reuse server initial snapshot data"
```

### Task 4: Make localized paths authoritative and preserve semantic language switches

**Files:**
- Modify: `frontend/src/router/index.ts`
- Modify: `frontend/src/composables/useLocale.ts`
- Modify: `frontend/src/main.ts`
- Modify: `frontend/src/App.vue`
- Create: `frontend/src/data/uiCopy.ts`
- Create: `frontend/src/components/common/LanguageSwitch.vue`
- Create: `frontend/src/views/NotFoundView.vue`
- Test: `frontend/tests/unit/router.spec.ts`
- Test: `frontend/tests/unit/useLocale.spec.ts`

**Interfaces:**
- Consumes: `Locale`, named pages, and the singleton public content store.
- Produces: named routes `home`, `project`, `privacy`, `not-found`; `localeRouteLocation(route,target)`; route-derived `useLocale()`.

- [ ] **Step 1: Write failing route and semantic-switch tests**

```ts
// frontend/tests/unit/router.spec.ts
import { describe, expect, it } from 'vitest'
import { createMemoryHistory } from 'vue-router'
import { createPublicRouter } from '@/router'

describe('localized routes', () => {
  it.each([
    ['/zh-CN', 'home'], ['/en', 'home'],
    ['/zh-CN/projects/ue-study', 'project'], ['/en/privacy', 'privacy'],
  ])('matches %s as %s', async (path, name) => {
    const router = createPublicRouter(createMemoryHistory())
    await router.push(path); await router.isReady()
    expect(router.currentRoute.value.name).toBe(name)
  })

  it('uses the same server root policy in development', async () => {
    const router = createPublicRouter(createMemoryHistory())
    await router.push('/'); await router.isReady()
    expect(router.currentRoute.value.fullPath).toBe('/zh-CN')
  })
})
```

```ts
// frontend/tests/unit/useLocale.spec.ts
import { expect, it } from 'vitest'
import { localeRouteLocation } from '@/composables/useLocale'

it('keeps a project slug while changing locale', () => {
  expect(localeRouteLocation({ name: 'project', params: { locale: 'zh-CN', slug: 'ue-study' } }, 'en'))
    .toEqual({ name: 'project', params: { locale: 'en', slug: 'ue-study' } })
})
```

- [ ] **Step 2: Run tests and observe the current root-only router failure**

Run: `npm --prefix frontend run test:unit -- tests/unit/router.spec.ts tests/unit/useLocale.spec.ts`

Expected: FAIL because the current router exposes only `/` and locale is localStorage-driven.

- [ ] **Step 3: Implement the localized route graph and pure semantic switch**

```ts
// frontend/src/router/index.ts
import { createRouter, createWebHistory, type RouterHistory } from 'vue-router'
import { isLocale } from '@/types/public'

export function createPublicRouter(history: RouterHistory) {
  const router = createRouter({
    history,
    routes: [
      { path: '/', redirect: '/zh-CN' },
      { path: '/:locale', name: 'home', component: () => import('@/views/HomePageView.vue') },
      { path: '/:locale/projects/:slug', name: 'project', component: () => import('@/views/ProjectPageView.vue') },
      { path: '/:locale/privacy', name: 'privacy', component: () => import('@/views/PrivacyView.vue') },
      { path: '/:pathMatch(.*)*', name: 'not-found', component: () => import('@/views/NotFoundView.vue') },
    ],
    scrollBehavior(to) { return to.hash ? { el: to.hash, behavior: 'smooth', top: 24 } : { top: 0 } },
  })
  router.beforeEach((to) => {
    if ((to.name === 'home' || to.name === 'project' || to.name === 'privacy') && !isLocale(to.params.locale)) {
      return { name: 'not-found', params: { pathMatch: to.path.split('/').filter(Boolean) } }
    }
    return true
  })
  return router
}
export default createPublicRouter(createWebHistory(import.meta.env.BASE_URL))
```

```ts
// frontend/src/composables/useLocale.ts
import { computed, watch } from 'vue'
import { useRoute, useRouter, type RouteLocationNormalizedLoaded, type RouteLocationRaw } from 'vue-router'
import { isLocale, locales, type Locale } from '@/types/public'
export { locales, type Locale }
export type Localized<T> = Record<Locale, T>

export function localeRouteLocation(route: Pick<RouteLocationNormalizedLoaded, 'name' | 'params'>, locale: Locale): RouteLocationRaw {
  if (route.name === 'project' && typeof route.params.slug === 'string') return { name: 'project', params: { locale, slug: route.params.slug } }
  if (route.name === 'privacy') return { name: 'privacy', params: { locale } }
  return { name: 'home', params: { locale } }
}

export function useLocale() {
  const route = useRoute(), router = useRouter()
  const locale = computed<Locale>(() => isLocale(route.params.locale) ? route.params.locale : 'zh-CN')
  watch(locale, (value) => {
    document.documentElement.lang = value
    try { localStorage.setItem('portfolio.locale', value) } catch { /* route remains authoritative */ }
  }, { immediate: true })
  return { locale, setLocale: (value: Locale) => router.push(localeRouteLocation(route, value)) }
}
```

```vue
<!-- frontend/src/components/common/LanguageSwitch.vue -->
<script setup lang="ts">
import { useLocale } from '@/composables/useLocale'
const { locale, setLocale } = useLocale()
</script>
<template>
  <div role="group" aria-label="Language / 语言">
    <button type="button" :aria-pressed="locale === 'zh-CN'" @click="setLocale('zh-CN')">中文</button>
    <button type="button" :aria-pressed="locale === 'en'" @click="setLocale('en')">English</button>
  </div>
</template>
```

- [ ] **Step 4: Update bootstrap/App and verify route authority**

Remove `initializeLocale()` from `main.ts`. `App.vue` reads the current published SITE accessibility skip label when available and otherwise uses exact static UI fallback from `uiCopy.ts`; it never imports `portfolio.ts`. Use the shared `LanguageSwitch` in the existing home navigation and later project/privacy views so all semantic paths visibly expose the same behavior. `NotFoundView` chooses a supported locale from the first path segment or `zh-CN`, renders a localized heading/explanation, and links to that locale home.

Run: `npm --prefix frontend run test:unit -- tests/unit/router.spec.ts tests/unit/useLocale.spec.ts src/App.spec.ts`

Expected: PASS for both home routes, project/privacy, invalid locale 404, root redirect, project semantic switch, privacy semantic switch, `html.lang`, and no localStorage override of an explicit path.

- [ ] **Step 5: Commit localized routing**

```bash
git add frontend/src/router frontend/src/composables/useLocale.ts frontend/src/main.ts frontend/src/App.vue frontend/src/data/uiCopy.ts frontend/src/components/common/LanguageSwitch.vue frontend/src/views/NotFoundView.vue frontend/tests/unit/router.spec.ts frontend/tests/unit/useLocale.spec.ts
git commit -m "feat(public): add semantic localized routes"
```

### Task 5: Convert the existing home design to API data and async-safe reveal behavior

**Files:**
- Create: `frontend/src/mappers/homeMapper.ts`
- Create: `frontend/src/composables/useAsyncRouteData.ts`
- Create: `frontend/src/directives/reveal.ts`
- Create: `frontend/src/components/common/AsyncState.vue`
- Create: `frontend/src/components/media/ResponsiveMedia.vue`
- Create: `frontend/src/views/HomePageView.vue`
- Modify: `frontend/src/views/HomeView.vue:1-496`
- Modify: `frontend/src/main.ts`
- Test: `frontend/tests/unit/homeMapper.spec.ts`
- Test: `frontend/tests/unit/useAsyncRouteData.spec.ts`
- Test: `frontend/tests/unit/reveal.spec.ts`
- Test: `frontend/tests/unit/HomePageView.spec.ts`

**Interfaces:**
- Consumes: `publicContentStore.loadHome(locale, signal)`, SITE/CATALOG envelopes, and existing HomeView CSS.
- Produces: `HomeViewModel`, retryable home container, `v-reveal`, and reusable responsive media.

- [ ] **Step 1: Write failing mapping, state, and late-node reveal tests**

```ts
// frontend/tests/unit/homeMapper.spec.ts
import { expect, it } from 'vitest'
import { mapHomeViewModel } from '@/mappers/homeMapper'
import { zhCatalogEnvelope, zhSiteEnvelope } from '../fixtures/publicSnapshots'

it('maps every catalog item with its own cover and shared slug', () => {
  const model = mapHomeViewModel('zh-CN', zhSiteEnvelope.data, zhCatalogEnvelope.data)
  expect(model.projects[0]).toMatchObject({ slug: 'ue-study', cover: { assetId: 'cover-1' } })
  expect(model.projects).toHaveLength(zhCatalogEnvelope.data.length)
})
```

```ts
// frontend/tests/unit/reveal.spec.ts
import { mount } from '@vue/test-utils'
import { expect, it, vi } from 'vitest'
import { revealDirective } from '@/directives/reveal'

it('observes an element inserted after the parent has mounted', async () => {
  const observe = vi.fn()
  vi.stubGlobal('IntersectionObserver', vi.fn(() => ({ observe, unobserve: vi.fn(), disconnect: vi.fn() })))
  const wrapper = mount({ data: () => ({ shown: false }), template: '<div><p v-if="shown" v-reveal>late</p></div>' }, { global: { directives: { reveal: revealDirective } } })
  await wrapper.setData({ shown: true })
  expect(observe).toHaveBeenCalledWith(wrapper.get('p').element)
})
```

State tests assert loading, success, retry after `PublicApiProblem`, abort on locale change, and that a project catalog with zero items is a valid ready value surfaced as a work-section empty state.

- [ ] **Step 2: Run focused tests and observe missing migration modules**

Run: `npm --prefix frontend run test:unit -- tests/unit/homeMapper.spec.ts tests/unit/useAsyncRouteData.spec.ts tests/unit/reveal.spec.ts tests/unit/HomePageView.spec.ts`

Expected: FAIL because mapper, route state, directive, and container do not exist.

- [ ] **Step 3: Implement the view mapper and async resource lifecycle**

```ts
// frontend/src/mappers/homeMapper.ts
import type { Locale, ProjectCard, PublicMedia, PublicSite } from '@/types/public'
export interface HomeViewModel {
  locale: Locale
  identity: PublicSite['identity']
  copy: Omit<PublicSite, 'identity' | 'seo' | 'privacy' | 'socialLinks' | 'resume'>
  heroAsset: PublicMedia
  projects: ProjectCard[]
}
export function mapHomeViewModel(locale: Locale, site: PublicSite, catalog: ProjectCard[]): HomeViewModel {
  const { identity, hero, seo: _seo, privacy: _privacy, socialLinks: _social, resume: _resume, ...copy } = site
  return { locale, identity, copy: { ...copy, hero }, heroAsset: hero.media, projects: [...catalog].sort((a, b) => a.sortOrder - b.sortOrder) }
}
```

`useAsyncRouteData` accepts a watched key and `(signal) => Promise<T>`, aborts the previous controller before a new load, ignores `AbortError`, and exposes `{ state: 'loading'|'ready'|'error', value, problem, retry }`. It never turns a network/500 error into an empty value.

```ts
// frontend/src/composables/useAsyncRouteData.ts
import { onScopeDispose, ref, watch, type Ref, type WatchSource } from 'vue'
import { PublicApiProblem } from '@/services/portfolioApi'

export function useAsyncRouteData<T>(key: WatchSource<unknown>, load: (signal: AbortSignal) => Promise<T>) {
  const state = ref<'loading' | 'ready' | 'error'>('loading')
  const value = ref<T | null>(null) as Ref<T | null>
  const problem = ref<PublicApiProblem | null>(null)
  let controller: AbortController | null = null
  let run = 0
  async function execute() {
    controller?.abort()
    controller = new AbortController()
    const current = ++run
    state.value = 'loading'; problem.value = null
    try {
      const next = await load(controller.signal)
      if (current !== run) return
      value.value = next; state.value = 'ready'
    } catch (cause) {
      if (current !== run || (cause instanceof DOMException && cause.name === 'AbortError')) return
      problem.value = cause instanceof PublicApiProblem
        ? cause
        : new PublicApiProblem({ type: 'network_error', title: 'Unable to load', status: 0, code: 'NETWORK_ERROR', traceId: 'client' })
      state.value = 'error'
    }
  }
  watch(key, () => void execute(), { immediate: true })
  onScopeDispose(() => { run += 1; controller?.abort() })
  return { state, value, problem, retry: execute }
}
```

- [ ] **Step 4: Implement responsive media, reveal directive, and refactor HomeView**

```vue
<!-- frontend/src/components/media/ResponsiveMedia.vue -->
<script setup lang="ts">
import type { PublicMedia } from '@/types/public'
withDefaults(defineProps<{ media: PublicMedia; sizes?: string; objectPosition?: string; eager?: boolean; decorative?: boolean }>(), {
  sizes: '100vw', objectPosition: '50% 50%', eager: false, decorative: false,
})
</script>
<template>
  <img :src="media.src" :srcset="media.srcset" :sizes="sizes" :width="media.width" :height="media.height"
      :alt="decorative ? '' : media.alt" :loading="eager ? 'eager' : 'lazy" :fetchpriority="eager ? 'high' : 'auto'"
    :style="{ objectPosition }" />
</template>
```

```ts
// frontend/src/directives/reveal.ts
import type { ObjectDirective } from 'vue'
const observers = new WeakMap<HTMLElement, IntersectionObserver>()
export const revealDirective: ObjectDirective<HTMLElement> = {
  mounted(element) {
    if (matchMedia('(prefers-reduced-motion: reduce)').matches) element.classList.add('is-visible')
    else {
      const observer = new IntersectionObserver(([entry]) => {
        if (entry?.isIntersecting) { element.classList.add('is-visible'); observer.disconnect(); observers.delete(element) }
      }, { rootMargin: '0px 0px -7% 0px', threshold: 0.08 })
      observers.set(element, observer); observer.observe(element)
    }
  },
  unmounted(element) { observers.get(element)?.disconnect(); observers.delete(element) },
}
```

Register `app.directive('reveal', revealDirective)` in `main.ts`. `HomePageView` derives locale from the route, loads through `publicContentStore`, applies `mapHomeViewModel`, sets the current SITE for `App.vue`, and renders `AsyncState` with retry. Refactor only the script/template portion of existing `HomeView.vue`:

- accept required `model: HomeViewModel`;
- remove all imports from `@/data/portfolio`, local `assetById`, and the client-side SEO watcher;
- preserve menu/focus/pointer logic and all existing scoped CSS;
- replace `data-reveal` with `v-reveal`;
- replace hero/card `<img>` with `ResponsiveMedia`, pass `site.hero.objectPosition` only for the hero, remove the old string `heroVisualStyle`, and use exact `project.featured` for the wide-card class because plan-03 `ProjectCard` has no `layout` field;
- render each published card as a `RouterLink` to `{ name: 'project', params: { locale: model.locale, slug: project.slug } }`;
- render a localized work-section empty message when `projects.length === 0`, then retain the open-slot card;
- retain mailto contact until plan 06, deriving it from published SITE email.

Run: `npm --prefix frontend run test:unit -- tests/unit/homeMapper.spec.ts tests/unit/useAsyncRouteData.spec.ts tests/unit/reveal.spec.ts tests/unit/HomePageView.spec.ts`

Expected: PASS for initial data, API fallback, loading/error/retry, empty catalog, one-to-one project/media mapping, late reveal, reduced motion, localized project links, and responsive image attributes.

- [ ] **Step 5: Commit the home migration**

```bash
git add frontend/src/mappers frontend/src/composables/useAsyncRouteData.ts frontend/src/directives frontend/src/components/common frontend/src/components/media frontend/src/views/HomePageView.vue frontend/src/views/HomeView.vue frontend/src/main.ts frontend/tests/unit
git commit -m "feat(public): render home from published snapshots"
```

### Task 6: Add localized project detail with exhaustive safe block rendering

**Files:**
- Create: `frontend/src/views/ProjectPageView.vue`
- Create: `frontend/src/views/ProjectDetailView.vue`
- Create: `frontend/src/components/project/ContentBlockRenderer.vue`
- Create: `frontend/src/components/project/ProjectHeader.vue`
- Create: `frontend/src/components/project/project-blocks.css`
- Test: `frontend/tests/unit/ProjectPageView.spec.ts`
- Test: `frontend/tests/unit/ContentBlockRenderer.spec.ts`
- Test: `frontend/tests/unit/ResponsiveMedia.spec.ts`

**Interfaces:**
- Consumes: `publicContentStore.loadProject(locale,slug,signal)`, exact `PublicBlock`, the matching catalog `ProjectCard`, and `ResponsiveMedia`.
- Produces: PROJECT route handling, explicit 404 distinction, and rendering for all nine published block types.

- [ ] **Step 1: Write failing project-route and block renderer tests**

```ts
// frontend/tests/unit/ContentBlockRenderer.spec.ts
import { mount } from '@vue/test-utils'
import { expect, it } from 'vitest'
import ContentBlockRenderer from '@/components/project/ContentBlockRenderer.vue'
import { enProjectEnvelope } from '../fixtures/publicSnapshots'

it('renders every approved published block in server order', () => {
  const wrapper = mount(ContentBlockRenderer, { props: { locale: 'en', blocks: [...enProjectEnvelope.data.blocks].reverse() } })
  expect(wrapper.findAll('[data-content-block]').map((node) => Number(node.attributes('data-order'))))
    .toEqual([0, 1, 2, 3, 4, 5, 6, 7, 8])
})

it('renders code as text and only published payload.html as HTML', () => {
  const wrapper = mount(ContentBlockRenderer, { props: { locale: 'en', blocks: enProjectEnvelope.data.blocks } })
  expect(wrapper.get('pre code').text()).toContain('<Actor>')
  expect(wrapper.find('script').exists()).toBe(false)
})

it.each([
  [{ credit: 'Studio A', sourceUrl: 'https://example.com/source' }, true],
  [{ credit: 'Studio B', sourceUrl: 'http://example.com/source' }, false],
  [{ credit: '', sourceUrl: 'https://example.com/source' }, false],
])('renders visible media credit and links only a credited HTTPS source', (attribution, linked) => {
  const wrapper = mount(ContentBlockRenderer, {
    props: { locale: 'en', blocks: mediaBlocksWithAttribution(attribution) },
  })
  expect(wrapper.findAll('[data-media-credit]').length).toBe(attribution.credit ? 3 : 0)
  expect(wrapper.findAll('[data-media-source]').length).toBe(linked ? 3 : 0)
  expect(wrapper.findAll('[data-media-source]').every((node) => node.attributes('rel') === 'noopener noreferrer')).toBe(true)
  expect(wrapper.findAll('[data-media-source]').every((node) => node.attributes('target') === '_blank')).toBe(true)
})

it('renders server-projected metadata for a media-backed download', () => {
  const wrapper = mount(ContentBlockRenderer, {
    props: { locale: 'en', blocks: [downloadBlock({ mimeType: 'application/zip', byteSize: 1_572_864 })] },
  })
  expect(wrapper.get('[data-download-metadata]').text()).toBe('ZIP · 1.5 MiB')
})

it('omits metadata for an external download whose projection has null metadata', () => {
  const wrapper = mount(ContentBlockRenderer, {
    props: { locale: 'en', blocks: [downloadBlock({ mimeType: null, byteSize: null })] },
  })
  expect(wrapper.find('[data-download-metadata]').exists()).toBe(false)
})
```

`mediaBlocksWithAttribution` returns one IMAGE, one GALLERY item, and one VIDEO cover so all three paths are pinned. Add cases for blank credit, blank/malformed/non-HTTPS `sourceUrl`, exact visible credit text, and absence of unsafe anchors. Add the equivalent `zh-CN` DOWNLOAD assertion (`ZIP · 1.5 MiB` may share the unit token while surrounding copy is Chinese), a byte/KiB/MiB/GiB boundary table, and a fetch spy proving neither media-backed nor external DOWNLOAD rendering performs `HEAD` or any metadata request. Project page tests distinguish `PROJECT_NOT_FOUND` (`router.replace` to localized not-found while preserving the requested path) from a network/500 error (retry panel remains on project route).

- [ ] **Step 2: Run tests and observe missing page/renderer failures**

Run: `npm --prefix frontend run test:unit -- tests/unit/ProjectPageView.spec.ts tests/unit/ContentBlockRenderer.spec.ts tests/unit/ResponsiveMedia.spec.ts`

Expected: FAIL because project components are absent.

- [ ] **Step 3: Implement exhaustive rendering with safe link/media attributes**

`ContentBlockRenderer.vue` sorts a copied block array by `sortOrder` and uses an exhaustive switch over `block.payload.type`; it also asserts `block.type === block.payload.type` in development fixtures:

- `MARKDOWN`: `<div class="prose" v-html="block.payload.html">`; the exact public DTO contains no raw Markdown field.
- `IMAGE`: `ResponsiveMedia` from `block.payload.media`, `<figure>`, its optional caption, and media attribution from the exact fields below.
- `GALLERY`: labelled responsive grid over `block.payload.media`, using every item’s intrinsic dimensions, alt, optional caption, and its own media attribution.
- `VIDEO`: only render an iframe when `new URL(block.payload.embedUrl).protocol === 'https:'`; use its title, `loading="lazy"`, `referrerpolicy="strict-origin-when-cross-origin"`, and `sandbox="allow-scripts allow-same-origin allow-presentation"`; otherwise render a safe unavailable notice. Render `payload.cover` plus its media attribution when present.
- `CODE`: escaped interpolation of `payload.code` inside `<pre><code>`, language label, optional line-number CSS; never `v-html`.
- `QUOTE`: semantic `<blockquote>` and `<cite>` from the payload.
- `METRICS`: semantic list over `payload.metrics` with visible label/value/suffix.
- `DOWNLOAD`: validated HTTPS/current-origin `payload.href`, label, and description. Mirror `payload.mimeType: string | null` and `payload.byteSize: number | null` exactly. When both are non-null (media-backed), derive a short localized display type from the MIME value and a deterministic human-readable IEC size with one decimal only when needed; expose the full MIME and exact byte count to assistive text/title. When both are null (external HTTPS), render no type/size fallback label. Never issue `HEAD`, infer from the URL extension, or add a filename field.
- `LINK`: validated HTTPS `payload.href`, using `rel="noreferrer noopener"` when `payload.openNewTab` is true.

The final script branch assigns `block` to `never`, forcing compilation failure if the union changes.

For IMAGE, each GALLERY item, and VIDEO cover, render `media.credit` visibly whenever it is nonblank. Only when that same item has nonblank credit and `new URL(media.sourceUrl).protocol === 'https:'`, wrap the credit/source label in an external anchor with `target="_blank" rel="noopener noreferrer"`; otherwise keep credit as plain text and omit the unsafe/blank source link. Do not substitute `alt`/caption as credit, use a parent block's source, hide attribution in hover-only UI, or make a network request to validate the source.

- [ ] **Step 4: Implement project state/404 behavior and verify**

`ProjectPageView` validates locale and ASCII slug, loads the exact PROJECT payload, puts its SITE in shared current state, applies project SEO in Task 7, and renders `ProjectDetailView`. `ProjectDetailView` contains the shared visible language switch, a localized back-to-work link, `ProjectHeader`, tags/skills, exact `project.media`, and ordered blocks. It finds the matching catalog card by `card.projectId === project.projectId` for the responsive cover; if the card is absent it uses the first `project.media` entry and preserves a meaningful alt. A `404 PROJECT_NOT_FOUND` replaces to `not-found` with `query.requested=<original fullPath>`; all other errors retain retry.

Run: `npm --prefix frontend run test:unit -- tests/unit/ProjectPageView.spec.ts tests/unit/ContentBlockRenderer.spec.ts tests/unit/ResponsiveMedia.spec.ts`

Expected: PASS for initial PROJECT reuse, API navigation, all nine blocks, ordering, responsive media, safe external attributes, code escaping, 404 distinction, error retry, and localized back navigation.

Run: `npm --prefix frontend run type-check`

Expected: exit 0 with an exhaustive public block union.

- [ ] **Step 5: Commit project detail**

```bash
git add frontend/src/views/ProjectPageView.vue frontend/src/views/ProjectDetailView.vue frontend/src/components/project frontend/tests/unit/ProjectPageView.spec.ts frontend/tests/unit/ContentBlockRenderer.spec.ts frontend/tests/unit/ResponsiveMedia.spec.ts
git commit -m "feat(public): add localized project detail pages"
```

### Task 7: Add privacy/404 data, client navigation SEO, and server HTML contract tests

**Files:**
- Create: `frontend/src/services/seo.ts`
- Modify: `frontend/src/views/PrivacyView.vue`
- Modify: `frontend/src/views/NotFoundView.vue`
- Modify: `frontend/src/views/HomePageView.vue`
- Modify: `frontend/src/views/ProjectPageView.vue`
- Modify: `frontend/index.html`
- Create: `frontend/tests/fixtures/serverHtml.ts`
- Create: `frontend/tests/contracts/seoHtml.spec.ts`
- Create: `frontend/tests/contracts/noRuntimePortfolioImports.spec.ts`
- Test: `frontend/tests/unit/seo.spec.ts`
- Test: `frontend/tests/unit/PrivacyView.spec.ts`

**Interfaces:**
- Consumes: exact `PublicSite`/`PublicProject` DTOs, route locale/kind/slug, and the configured public base URL.
- Produces: `buildSeoPage(page, baseUrl)`, `applySeo(page, baseUrl, document)`, privacy/404 pages, exact server HTML expectations, and an enforced ban on runtime `portfolio.ts` imports.

- [ ] **Step 1: Write failing SEO, privacy, and runtime-truth contract tests**

```ts
// frontend/tests/unit/seo.spec.ts
import { expect, it } from 'vitest'
import { applySeo } from '@/services/seo'
import { zhSiteEnvelope } from '../fixtures/publicSnapshots'

it('synchronizes canonical, both hreflang links, OG, and JSON-LD without duplicates', () => {
  const page = { kind: 'home', locale: 'zh-CN', site: zhSiteEnvelope.data } as const
  applySeo(page, 'https://yychainsaw.xyz', document)
  applySeo(page, 'https://yychainsaw.xyz', document)
  expect(document.querySelectorAll('link[rel="canonical"]')).toHaveLength(1)
  expect(document.querySelectorAll('link[rel="alternate"][hreflang]')).toHaveLength(2)
  expect(document.querySelector('meta[property="og:title"]')?.getAttribute('content')).toBe(zhSiteEnvelope.data.seo.title)
  expect(document.querySelectorAll('script[type="application/ld+json"][data-portfolio-seo]')).toHaveLength(1)
})
```

```ts
// frontend/tests/contracts/noRuntimePortfolioImports.spec.ts
import { readdirSync, readFileSync, statSync } from 'node:fs'
import { join } from 'node:path'
import { expect, it } from 'vitest'

function sourceFiles(path: string): string[] {
  return readdirSync(path).flatMap((name) => {
    const next = join(path, name)
    return statSync(next).isDirectory() ? sourceFiles(next) : /\.(ts|vue)$/.test(name) ? [next] : []
  })
}

it('keeps portfolio.ts exclusively as an import-export source', () => {
  const offenders = sourceFiles('src')
    .filter((file) => !file.endsWith('data\\portfolio.ts') && !file.endsWith('data/portfolio.ts'))
    .filter((file) => readFileSync(file, 'utf8').includes('@/data/portfolio'))
  expect(offenders).toEqual([])
})
```

`seoHtml.spec.ts` parses the fixed server HTML fixture and expects matching `lang`, title, description, canonical, exactly two alternate hreflang URLs, OG title/description/url/image, one valid JSON-LD object, indexable H1/body text, and an inert `#__PORTFOLIO_DATA__` template whose kind/locale/content match the rendered fixture.

- [ ] **Step 2: Run tests and observe current client-only SEO/static-data failures**

Run: `npm --prefix frontend run test:unit -- tests/unit/seo.spec.ts tests/unit/PrivacyView.spec.ts tests/contracts/seoHtml.spec.ts tests/contracts/noRuntimePortfolioImports.spec.ts`

Expected: FAIL because current HomeView imports `portfolio.ts`, SEO only changes a subset of meta fields, and privacy/server contracts are absent.

- [ ] **Step 3: Implement deterministic metadata synchronization**

```ts
// frontend/src/services/seo.ts
import type { Locale, PublicMedia, PublicProject, PublicSite } from '@/types/public'

export type SeoPage =
  | { kind: 'home'; locale: Locale; site: PublicSite }
  | { kind: 'project'; locale: Locale; site: PublicSite; project: PublicProject; cover: PublicMedia | null }
  | { kind: 'privacy'; locale: Locale; site: PublicSite }

interface SeoDocument {
  title: string; description: string; canonical: string
  alternates: Record<Locale, string>; ogType: 'website' | 'article'; image: string | null
  structuredData: Record<string, unknown>
}

const pathFor = (page: SeoPage, locale: Locale) => page.kind === 'home'
  ? `/${locale}`
  : page.kind === 'privacy' ? `/${locale}/privacy` : `/${locale}/projects/${encodeURIComponent(page.project.slug)}`

export function buildSeoPage(page: SeoPage, baseUrl: string): SeoDocument {
  const absolute = (path: string) => new URL(path, baseUrl).href
  const canonical = absolute(pathFor(page, page.locale))
  const alternates = { 'zh-CN': absolute(pathFor(page, 'zh-CN')), en: absolute(pathFor(page, 'en')) }
  if (page.kind === 'project') return {
    title: page.project.seoTitle, description: page.project.seoDescription, canonical, alternates,
    ogType: 'article', image: page.cover ? absolute(page.cover.src) : null,
    structuredData: { '@context': 'https://schema.org', '@type': 'CreativeWork', name: page.project.title, description: page.project.summary, url: canonical },
  }
  const title = page.kind === 'privacy' ? `${page.site.privacy.title} · ${page.site.identity.displayName}` : page.site.seo.title
  return {
    title, description: page.site.seo.description, canonical, alternates, ogType: 'website',
    image: page.kind === 'home' ? absolute(page.site.hero.media.src) : null,
    structuredData: page.kind === 'home'
      ? { '@context': 'https://schema.org', '@type': 'Person', name: page.site.identity.displayName, url: canonical, sameAs: page.site.socialLinks.map((link) => link.url) }
      : { '@context': 'https://schema.org', '@type': 'WebPage', name: page.site.privacy.title, url: canonical },
  }
}

function meta(doc: Document, selector: string, attributes: Record<string, string>) {
  let element = doc.head.querySelector<HTMLMetaElement>(selector)
  if (!element) { element = doc.createElement('meta'); doc.head.append(element) }
  Object.entries(attributes).forEach(([name, value]) => element!.setAttribute(name, value))
}
function link(doc: Document, selector: string, attributes: Record<string, string>) {
  let element = doc.head.querySelector<HTMLLinkElement>(selector)
  if (!element) { element = doc.createElement('link'); doc.head.append(element) }
  Object.entries(attributes).forEach(([name, value]) => element!.setAttribute(name, value))
}
export function applySeo(page: SeoPage, baseUrl: string, doc: Document = document) {
  const seo = buildSeoPage(page, baseUrl)
  doc.title = seo.title
  meta(doc, 'meta[name="description"]', { name: 'description', content: seo.description })
  meta(doc, 'meta[property="og:type"]', { property: 'og:type', content: seo.ogType })
  meta(doc, 'meta[property="og:title"]', { property: 'og:title', content: seo.title })
  meta(doc, 'meta[property="og:description"]', { property: 'og:description', content: seo.description })
  meta(doc, 'meta[property="og:url"]', { property: 'og:url', content: seo.canonical })
  if (seo.image) meta(doc, 'meta[property="og:image"]', { property: 'og:image', content: seo.image })
  else doc.head.querySelector('meta[property="og:image"]')?.remove()
  link(doc, 'link[rel="canonical"]', { rel: 'canonical', href: seo.canonical })
  doc.head.querySelectorAll('link[rel="alternate"][hreflang]').forEach((node) => node.remove())
  for (const locale of ['zh-CN', 'en'] as const) {
    link(doc, `link[rel="alternate"][hreflang="${locale}"]`, { rel: 'alternate', hreflang: locale, href: seo.alternates[locale] })
  }
  doc.head.querySelector('meta[name="robots"][data-route-noindex]')?.remove()
  let script = doc.head.querySelector<HTMLScriptElement>('script[data-portfolio-seo]')
  if (!script) { script = doc.createElement('script'); script.type = 'application/ld+json'; script.dataset.portfolioSeo = ''; doc.head.append(script) }
  script.textContent = JSON.stringify(seo.structuredData).replaceAll('<', '\\u003c')
}
```

Home, project, and privacy route containers call `applySeo` only after ready data, using `VITE_PUBLIC_BASE_URL` in production and `window.location.origin` only in development. Project calls include the catalog-derived cover. Initial server metadata already matches this derivation and the operation is idempotent; `serverHtml.ts` pins parity so Java and client changes cannot drift. Error states do not replace valid initial metadata with an error trace.

- [ ] **Step 4: Implement privacy/404 and verify the server/client SEO boundary**

`PrivacyView.vue` loads SITE through `publicContentStore.loadPrivacy`, calls `applySeo({ kind: 'privacy', locale, site }, baseUrl)`, renders the shared visible language switch, the exact `site.privacy.title`, and only `site.privacy.html`; it has loading/error/retry and does not reference fields absent from plan 03. `NotFoundView.vue` sets a localized non-sensitive title plus `<meta name="robots" content="noindex,follow" data-route-noindex>` for client navigation, removes any `script[data-portfolio-seo]`, and never emits JSON-LD for a missing page. On the next successful route, `applySeo` removes that tagged noindex override and replaces stale alternates.

Update `index.html` only as a development fallback: retain charset/viewport/theme, set a neutral `Portfolio development shell` title, and document that production metadata/body/initial JSON are emitted by Spring Boot. Do not add canonical or JSON-LD values that could diverge from a request URL.

Run: `npm --prefix frontend run test:unit -- tests/unit/seo.spec.ts tests/unit/PrivacyView.spec.ts tests/contracts/seoHtml.spec.ts tests/contracts/noRuntimePortfolioImports.spec.ts`

Expected: PASS for both locales, canonical/hreflang/OG/JSON-LD, privacy initial reuse and retry, 404 noindex, removal of stale meta, and zero runtime imports of `portfolio.ts`.

- [ ] **Step 5: Commit SEO and content-source enforcement**

```bash
git add frontend/src/services/seo.ts frontend/src/views/PrivacyView.vue frontend/src/views/NotFoundView.vue frontend/src/views/HomePageView.vue frontend/src/views/ProjectPageView.vue frontend/index.html frontend/tests/fixtures/serverHtml.ts frontend/tests/contracts frontend/tests/unit/seo.spec.ts frontend/tests/unit/PrivacyView.spec.ts
git commit -m "feat(public): add localized SEO and privacy contracts"
```

### Task 8: Implement shared plan-01 CSRF and the bilingual plan-06 contact form

**Files:**
- Create: `frontend/src/types/interactions.ts`
- Create: `frontend/src/services/csrfClient.ts`
- Create: `frontend/src/services/contactApi.ts`
- Create: `frontend/src/components/contact/ContactForm.vue`
- Modify: `frontend/src/views/HomeView.vue`
- Create: `frontend/tests/unit/csrfClient.spec.ts`
- Create: `frontend/tests/unit/contactApi.spec.ts`
- Create: `frontend/tests/unit/ContactForm.spec.ts`

**Interfaces:**
- Consumes: plan-03 `PublicSite.contact`, `PublicSite.identity.email`, current route locale, the common safe `ApiProblem` parser, plan-01 anonymous `GET /api/admin/auth/csrf`, and plan-06 `POST /api/public/contact`.
- Produces: one shared memory-only CSRF provider plus a keyboard-accessible bilingual form that sends the exact protected request, renders client/server validation, respects the hidden honeypot, and preserves input through retryable failures.

- [ ] **Step 1: Write failing CSRF-provider, adapter, and component tests**

In `csrfClient.spec.ts`, pin a same-origin `GET /api/admin/auth/csrf` with `Accept: application/json`, `credentials: 'same-origin'`, and `cache: 'no-store'`. Accept only status `200` and the exact nonblank `CsrfResponse { headerName, parameterName, token }`; cache it only in the provider instance, coalesce concurrent acquisition, and prove later callers reuse it without storage/cookie parsing or another GET. Missing fields, malformed JSON, and network/non-200 responses reject safely, leave the cache empty, and never expose the body or token in an error.

Pin the public wire contract in `contactApi.spec.ts`: a valid call has the exact order `GET /api/admin/auth/csrf` then `POST /api/public/contact`; the POST uses the response's `headerName` with its exact `token`, `Content-Type: application/json`, `Accept: application/json`, and exactly the six request JSON keys. A second submission reuses the in-memory token but still calls `ensureToken()` before POST. A missing/invalid token or failed token GET causes no contact POST and becomes a safe retryable failure. A `403 CSRF_INVALID` invalidates the cached token and retains the form for an explicit retry; contact submission is never automatically replayed. A `202` body is exactly `{ accepted: true }`; an unknown response field is rejected as a protocol error. Assert that no submitted value, response body, header name, or token appears in thrown error text. Test field validation `422 VALIDATION_ERROR`, malformed/unknown JSON `400 MALFORMED_REQUEST`, rate-limit `429` plus `Retry-After`, abort, and network/5xx failure. Do not create or call `/api/public/csrf`.

Mount the form for both locales in `ContactForm.spec.ts` and assert:

1. labels, instructions, errors, status, and buttons are localized without changing field names;
2. visible controls are `name`, `email`, `subject`, `message`, and `privacyAccepted`; the labelled `website` honeypot and its instruction exist off-screen in an `aria-hidden="true"` wrapper with `autocomplete="off"` and `tabindex="-1"`, so legitimate keyboard/screen-reader users never encounter it;
3. required/format/length checks match the server bounds: name 100, email 320, subject 160, message 5,000, website 200, and accepted privacy;
4. submission sends trimmed visible fields, an empty website, and literal `privacyAccepted: true`, disables duplicate submission, and exposes an `aria-live="polite"` progress status;
5. success clears name/email/subject/message/checkbox, does not display the returned body or visitor data, and says the message was accepted rather than promising email delivery;
6. `422 VALIDATION_ERROR` attaches safe field errors to the form and focuses its summary; malformed `400` shows only generic guidance; `429` shows a localized retry-later message using a bounded Retry-After value; network/5xx failures show a retry button and retain every entered value;
7. the privacy copy says the message is retained for one year unless deleted earlier and links a deletion request to the published `site.contact.email` (falling back only to `site.identity.email`).

- [ ] **Step 2: Run focused tests and verify the missing implementation**

Run: `npm --prefix frontend run test:unit -- tests/unit/csrfClient.spec.ts tests/unit/contactApi.spec.ts tests/unit/ContactForm.spec.ts`

Expected: FAIL because the shared CSRF provider, interaction DTOs, adapter, and form do not exist.

- [ ] **Step 3: Define the shared CSRF provider, exact public DTOs, and strict adapter**

```ts
// frontend/src/services/csrfClient.ts
export const CSRF_ENDPOINT = '/api/admin/auth/csrf'
export interface CsrfResponse { headerName: string; parameterName: string; token: string }
export interface CsrfTokenProvider {
  ensureToken(): Promise<CsrfResponse>
  invalidate(): void
}
```

`createCsrfClient(fetchImpl)` keeps only a validated `CsrfResponse` and an optional in-flight acquisition promise inside its closure; export one `publicCsrfClient` singleton for both public adapters and allow provider injection in tests. `ensureToken()` returns cached memory state or performs the exact no-store GET, clears the in-flight reference on every outcome, and caches only a complete successful response. `invalidate()` clears only memory. Never read the CSRF cookie, use local/session storage, serialize the token into diagnostics, or introduce another endpoint.

```ts
// frontend/src/types/interactions.ts
import type { Locale } from './public'

export const PUBLIC_INTERACTION_ENDPOINTS = {
  contact: '/api/public/contact',
  events: '/api/public/events',
} as const

export interface ContactRequest {
  name: string
  email: string
  subject: string
  message: string
  website: string
  privacyAccepted: true
}

export interface ContactReceipt { accepted: true }

export const analyticsEventTypes = [
  'PAGE_VIEW', 'PROJECT_VIEW', 'RESUME_DOWNLOAD', 'DEMO_DOWNLOAD', 'OUTBOUND_CLICK',
] as const
export type AnalyticsEventType = (typeof analyticsEventTypes)[number]
export const analyticsPageKeys = [
  'HOME', 'ABOUT', 'WORK', 'ROADMAP', 'CONTACT', 'PRIVACY', 'PROJECT_DETAIL',
] as const
export type AnalyticsPageKey = (typeof analyticsPageKeys)[number]
export interface AnalyticsEvent {
  eventId: string
  type: AnalyticsEventType
  pageKey: AnalyticsPageKey
  projectId: string | null
  referrer: string | null
  locale: Locale
}
export interface AnalyticsEventBatch {
  analyticsConsent: true
  visitorId: string
  sessionId: string
  events: AnalyticsEvent[]
}
```

`contactApi.submit(request, signal?)` calls `publicCsrfClient.ensureToken()` first, stops if acquisition fails or the caller has since aborted, then performs one POST with `[csrf.headerName]: csrf.token`; it accepts only status `202` plus the strict receipt. A `403 CSRF_INVALID` calls `invalidate()` and returns a retryable error without replaying the mutation. Reuse the safe problem shape, but never concatenate the submitted request, CSRF response, response body, email, or message into an exception. Treat abort separately from a retryable transport failure. Parse `Retry-After` as either delta seconds or HTTP date, cap the UI value at one hour, and fall back to the localized generic rate-limit copy when invalid. Plan 06 intentionally does not expose `messageId`; do not add it to the frontend type.

- [ ] **Step 4: Implement validation, state transitions, privacy copy, and home integration**

`ContactForm.vue` owns a typed editable model whose checkbox is `boolean`; it constructs `ContactRequest` only after validation narrows `privacyAccepted` to `true`. Set native `required`, `maxlength`, `type="email"`, and `aria-describedby` attributes, then run the same deterministic checks before the request. Do not rely on native browser validation alone because all errors require bilingual text and predictable focus. The hidden `website` field remains in the request and is reset with the other values after success.

Use explicit states `IDLE | SUBMITTING | SUCCEEDED | VALIDATION_ERROR | RATE_LIMITED | RETRYABLE_ERROR`. A new submit aborts an obsolete controller, unmount aborts without showing failure, and a retry reuses the preserved model. On `422`, render only the safe field map; on malformed `400`, render generic guidance without echoing values. On success, announce acceptance and move focus to the success status. On `429` and network/5xx, keep inputs and privacy acceptance intact.

Replace the home contact section's mailto-only primary action with the form while retaining the published email as an alternate contact/deletion path. Pass only `locale`, `site.contact`, and the deletion email into the component; do not couple contact submission to analytics consent or record a contact event because that type is absent from the allowlist.

- [ ] **Step 5: Verify and commit the contact journey**

Run: `npm --prefix frontend run test:unit -- tests/unit/csrfClient.spec.ts tests/unit/contactApi.spec.ts tests/unit/ContactForm.spec.ts`

Expected: PASS for memory-only CSRF acquisition/reuse, exact GET→POST header flow, no POST without a token, strict request/receipt parsing, both locales, bounds, privacy copy, focus, success clearing, validation, rate limiting, abort, and preserved retry input.

```bash
git add frontend/src/types/interactions.ts frontend/src/services/csrfClient.ts frontend/src/services/contactApi.ts frontend/src/components/contact/ContactForm.vue frontend/src/views/HomeView.vue frontend/tests/unit/csrfClient.spec.ts frontend/tests/unit/contactApi.spec.ts frontend/tests/unit/ContactForm.spec.ts
git commit -m "feat(public): add private contact form"
```

### Task 9: Implement consent, DNT suppression, rotating identifiers, and allowlisted analytics

**Files:**
- Create: `frontend/src/services/analyticsClient.ts`
- Create: `frontend/src/composables/useAnalyticsConsent.ts`
- Create: `frontend/src/components/analytics/AnalyticsConsent.vue`
- Modify: `frontend/src/main.ts`
- Modify: `frontend/src/App.vue`
- Modify: `frontend/src/router/index.ts`
- Modify: `frontend/src/views/HomePageView.vue`
- Modify: `frontend/src/views/HomeView.vue`
- Modify: `frontend/src/views/ProjectPageView.vue`
- Modify: `frontend/src/views/ProjectDetailView.vue`
- Modify: `frontend/src/views/PrivacyView.vue`
- Modify: `frontend/src/components/project/ContentBlockRenderer.vue`
- Create: `frontend/tests/unit/analyticsClient.spec.ts`
- Create: `frontend/tests/unit/AnalyticsConsent.spec.ts`
- Create: `frontend/tests/unit/analyticsInstrumentation.spec.ts`

**Interfaces:**
- Consumes: the exact interaction DTOs and shared memory-only `CsrfTokenProvider` from Task 8, Web Crypto, localStorage/sessionStorage, `navigator.doNotTrack`, Vue Router ready navigation, and plan-06 `POST /api/public/events`.
- Produces: explicit default-off consent, irreversible-per-request DNT suppression that does not even acquire CSRF, 128-bit rotating browser/session IDs, CSRF-protected allowlisted instrumentation, and non-persistent batches of at most 20.

- [ ] **Step 1: Write failing privacy, rotation, batch, and instrumentation tests**

Use a fake clock, deterministic `crypto.getRandomValues`, deterministic `crypto.randomUUID`, fake storage, and a fetch spy. Cover these boundaries:

1. absent consent means no IDs, no events POST, and no CSRF GET; the prompt is shown only when DNT is not `1`;
2. reject persists `denied`, creates no IDs, and sends nothing; the privacy control can explicitly enable later; accept persists `granted` before creating IDs; withdraw changes to `denied`, clears the visitor record, clears the session record and in-memory queue, and sends nothing about withdrawal;
3. `DNT=1` wins over stored `granted`, deletes pre-existing analytics visitor/session records before the app installs instrumentation, hides the prompt, does not call Web Crypto, never invokes the CSRF provider, and results in zero CSRF GETs and event POSTs;
4. visitor age `30 days - 1 ms` reuses the ID and `30 days` rotates it; session inactivity `30 minutes - 1 ms` reuses the ID and `30 minutes` rotates it; active events update only `lastActivityAt` in sessionStorage;
5. each generated ID is exactly 16 random bytes encoded unpadded base64url (22 characters), and every event uses `crypto.randomUUID()`;
6. a consented flush rechecks privacy, calls `ensureToken()`, and only then sends 1–20 events with the exact response `headerName: token`, exact JSON keys, `analyticsConsent: true`, and UTF-8 JSON size at most 32 KiB; the twenty-first event or first event that would cross 32 KiB starts the next batch; CSRF acquisition failure causes no event POST and joins the same at-most-two retry schedule; a failed batch preserves event UUIDs and is then discarded without local/session persistence;
7. only the five event types and seven page keys compile and pass runtime guards; invalid type/key/project combinations are dropped locally without a request or raw-value logging;
8. router/home/project/block instrumentation emits the mappings specified in Step 4 exactly once per user action.

- [ ] **Step 2: Run focused tests and verify the privacy client is absent**

Run: `npm --prefix frontend run test:unit -- tests/unit/analyticsClient.spec.ts tests/unit/AnalyticsConsent.spec.ts tests/unit/analyticsInstrumentation.spec.ts`

Expected: FAIL because consent state, identifier lifecycle, client, UI, and event hooks do not exist.

- [ ] **Step 3: Implement consent and identifier boundaries before event collection**

Use these storage keys and versioned records so tests and future migrations are explicit:

```ts
const CONSENT_KEY = 'portfolio.analytics.consent.v1'
const VISITOR_KEY = 'portfolio.analytics.visitor.v1'
const SESSION_KEY = 'portfolio.analytics.session.v1'
type ConsentChoice = 'granted' | 'denied' | null
interface VisitorRecord { version: 1; id: string; createdAt: number }
interface SessionRecord { version: 1; id: string; lastActivityAt: number }
```

`useAnalyticsConsent()` evaluates DNT synchronously before reading consent for UI: when it equals `1`, remove both identifier keys, expose `{ suppressedByDnt: true, choice: null, promptVisible: false }`, and never construct `AnalyticsClient`. Missing/invalid consent is off with a prompt; invalid ID records are removed. Reject writes only `denied`. Accept writes `granted`, then constructs the client. Withdraw writes `denied`, calls `client.destroy()`, clears its timers/queue and both identifier records, and returns to a non-prompting denied state. Storage exceptions fail closed: the site remains functional and analytics stays off.

Generate IDs only with `crypto.getRandomValues(new Uint8Array(16))`; encode using base64url with `+ -> -`, `/ -> _`, and no padding. Never use Math.random, fingerprints, cookies, server-provided IDs, or values derived from contact/public content. Rotate visitor IDs when `now - createdAt >= 30 * 24h`; rotate session IDs when `now - lastActivityAt >= 30m`. Reads, visibility changes, and navigation without an emitted event do not extend session activity.

`AnalyticsConsent.vue` is an accessible non-modal region with localized purpose/30-day raw-event retention copy, equally reachable Accept and Reject buttons, and a permanent privacy/settings control that offers Enable while denied and Withdraw while granted. It never renders IDs. The privacy page repeats the current status and withdrawal action, explains that withdrawal stops future collection and clears browser IDs but cannot identify/delete already de-identified aggregates, and makes clear analytics is not a cross-device people counter. DNT suppression renders neither prompt nor an enable control or misleading enabled state.

- [ ] **Step 4: Implement strict event creation, batching, and route/action mappings**

`analyticsClient.track(input)` first rechecks DNT and consent, validates the closed type/page-key pair, obtains current IDs, creates the UUID, and adds only the six event fields. Before enqueue, measure the complete candidate JSON with `TextEncoder`; flush the existing batch before a twentieth-plus-one event or an addition that would exceed 32 KiB. It otherwise flushes after a short two-second batch window and on `pagehide` using `fetch(endpoint, { keepalive: true })`; every payload contains 1–20 events.

For every normal, retry, and pagehide flush, recheck DNT and consent before touching `CsrfTokenProvider`; when suppressed/off, clear the pending queue and identifier records without a CSRF GET or events POST. When allowed, call the shared `ensureToken()` immediately before the event POST, then recheck DNT and consent again after token acquisition and before sending, so withdrawal or a new DNT signal during the GET cannot leak a batch. Send `[csrf.headerName]: csrf.token`, `Content-Type: application/json`, and the exact body with `credentials: 'same-origin'`. Token acquisition/protocol failure sends no POST and retries the same immutable batch at 1s then 3s; a `403 CSRF_INVALID` invalidates the cache and follows that same bounded retry, acquiring a fresh token next time. A normal `204` clears the batch; network/5xx/429 follows the bounded retry; other validation 4xx discards it. Do not use `sendBeacon` because its content type and error behavior would diverge from the tested adapter. `destroy()` aborts requests, clears timers/queue, and prevents new tracking. Never send `analyticsConsent: false` from the browser, serialize a queue/token into storage, or log IDs, tokens, headers, referrers, or event bodies.

Install delegated click instrumentation once in `App.vue` and route instrumentation after `router.isReady()`. Use the nearest explicit `data-analytics-*` marker rather than inferring from arbitrary text or URLs. Emit only these mappings:

| User-visible action | Type | Page key | Project ID |
|---|---|---|---|
| successful navigation to home route | `PAGE_VIEW` | `HOME` | `null` |
| first intersection/navigation to the About, Work, Roadmap, or Contact home section | `PAGE_VIEW` | matching `ABOUT`, `WORK`, `ROADMAP`, `CONTACT` | `null` |
| successful navigation to privacy | `PAGE_VIEW` | `PRIVACY` | `null` |
| ready published project detail | `PAGE_VIEW` and `PROJECT_VIEW` | `PROJECT_DETAIL` | exact `project.projectId` on both |
| click published resume `href` | `RESUME_DOWNLOAD` | current page key | `null` |
| click any published project `DOWNLOAD` block | `DEMO_DOWNLOAD` | `PROJECT_DETAIL` | exact `project.projectId` |
| click an external published social/content link | `OUTBOUND_CLICK` | current page key | project UUID on project detail, otherwise `null` |

Use `document.referrer || null` as the only referrer input; do not include the destination URL or query string. Section observers keep an in-memory route-generation set so re-render/reveal does not duplicate a section view; a new home navigation creates a new generation. The project container emits only after its exact ready payload is current, never during loading/error/404. Locale switches are new successful page/project views in the destination locale. Resume/demo/outbound handlers do not block navigation and do not emit when default is prevented. The backend owns ten-second duplicate suppression; the UI additionally prevents duplicate listeners and repeated ready callbacks.

- [ ] **Step 5: Verify fail-closed behavior and commit analytics**

Run: `npm --prefix frontend run test:unit -- tests/unit/analyticsClient.spec.ts tests/unit/AnalyticsConsent.spec.ts tests/unit/analyticsInstrumentation.spec.ts`

Expected: PASS for default-off/reject/withdraw/DNT, zero suppressed CSRF acquisition, exact 30-day and 30-minute boundaries, 128-bit base64url IDs, UUIDs, allowlists, page/action mappings, exact CSRF-header event POSTs, batch 20, bounded in-memory retry, and zero identifier/request leakage while suppressed.

```bash
git add frontend/src/services/analyticsClient.ts frontend/src/composables/useAnalyticsConsent.ts frontend/src/components/analytics/AnalyticsConsent.vue frontend/src/main.ts frontend/src/App.vue frontend/src/router/index.ts frontend/src/views frontend/src/components/project/ContentBlockRenderer.vue frontend/tests/unit/analyticsClient.spec.ts frontend/tests/unit/AnalyticsConsent.spec.ts frontend/tests/unit/analyticsInstrumentation.spec.ts
git commit -m "feat(public): add consented private analytics"
```

### Task 10: Add Playwright contact, analytics, SEO, responsive-media, route, and accessibility verification

**Files:**
- Create: `frontend/playwright.config.ts`
- Create: `frontend/tests/e2e/mockPublishedApi.ts`
- Create: `frontend/tests/e2e/locales-and-initial-data.spec.ts`
- Create: `frontend/tests/e2e/project-and-seo.spec.ts`
- Create: `frontend/tests/e2e/contact-and-analytics.spec.ts`
- Create: `frontend/tests/e2e/responsive-and-a11y.spec.ts`
- Modify: `frontend/vite.config.ts`
- Modify: `frontend/package.json` only if required to keep the Task 1 test command exact

**Interfaces:**
- Consumes: final localized routes, initial-data script contract, all three content GET APIs, plan-01 anonymous CSRF GET, both plan-06 public POST APIs, responsive media, consent/DNT storage boundaries, and current accessibility interactions.
- Produces: browser evidence for shared memory-only CSRF acquisition/header use, contact validation/retry/success, default-off and DNT analytics privacy, identifier rotation/batch/event contracts, no duplicate content fetch, semantic locale switching, SEO synchronization, responsive imagery/layout, reduced motion, keyboard focus, and WCAG smoke checks.

- [ ] **Step 1: Write deterministic server-shell and API-backed browser tests**

```ts
// frontend/playwright.config.ts
import { defineConfig, devices } from '@playwright/test'
export default defineConfig({
  testDir: './tests/e2e',
  projects: [
    { name: 'desktop', use: { ...devices['Desktop Chrome'], viewport: { width: 1440, height: 1000 } } },
    { name: 'mobile', use: { ...devices['iPhone 13'] } },
  ],
  use: { baseURL: 'http://127.0.0.1:4175', trace: 'retain-on-failure' },
  webServer: { command: 'npm run dev -- --host 127.0.0.1 --port 4175', url: 'http://127.0.0.1:4175/zh-CN', reuseExistingServer: false },
})
```

```ts
// excerpt: frontend/tests/e2e/locales-and-initial-data.spec.ts
import { expect, test } from '@playwright/test'
import { homeInitialPayload } from '../fixtures/publicSnapshots'
import { fulfillPublishedApi } from './mockPublishedApi'

test('reuses server HOME JSON and keeps project semantics across language switch', async ({ page }) => {
  let publicGets = 0
  await page.route('**/api/public/**', async (route) => {
    publicGets += 1
    await fulfillPublishedApi(route)
  })
  await page.route('http://127.0.0.1:4175/zh-CN', (route) => route.fulfill({
    contentType: 'text/html',
    body: `<!doctype html><html lang="zh-CN"><head><title>${homeInitialPayload.site.seo.title}</title></head><body><div id="app"><h1>${homeInitialPayload.site.hero.displayName}</h1></div><template id="__PORTFOLIO_DATA__">${JSON.stringify(homeInitialPayload).replaceAll('<', '\\u003c')}</template><script type="module" src="/src/main.ts"></script></body></html>`,
  }))
  await page.goto('/zh-CN')
  await expect(page.getByRole('heading', { level: 1 })).toContainText(homeInitialPayload.site.hero.displayName)
  expect(publicGets).toBe(0)
  await page.getByRole('link', { name: homeInitialPayload.catalog[0]!.title }).click()
  await page.getByRole('button', { name: 'English' }).click()
  await expect(page).toHaveURL(/\/en\/projects\/ue-study$/)
})
```

The mock API returns fixed SITE/CATALOG/PROJECT envelopes, one scripted `500` followed by success for retry, and a `404 PROJECT_NOT_FOUND`. Its only CSRF route is `GET /api/admin/auth/csrf`, returning exact `{ headerName: 'X-XSRF-TOKEN', parameterName: '_csrf', token: '<synthetic>' }` with `Cache-Control: no-store`; a scripted malformed/failing response verifies that no public POST follows. It validates that contact/events call CSRF acquisition first when the shared cache is empty and that every POST sends the returned header/token. It then validates exact JSON keys for `POST /api/public/contact`, scripts `400`, `429` with `Retry-After`, one `500`, and `202 {"accepted":true}`, and validates each `POST /api/public/events` as `204` with exact batch/event keys, consent true, allowlisted types/page keys, UUID event IDs, 22-character base64url IDs, and 1–20 events. Every mock records only method/path/status/order and boolean contract results; it never writes contact fields, CSRF headers/tokens, event bodies, referrers, or browser IDs to test output.

In `contact-and-analytics.spec.ts`, set `test.use({ trace: 'off' })` at file scope, create no screenshots/HTML/body attachments, and use only synthetic contact inputs and identifiers; Playwright network traces would otherwise retain contact bodies, CSRF tokens, referrers, and analytics identifiers. Use separate clean contexts for undecided, denied, granted, withdrawn, and DNT journeys. Install DNT before application code and preseed stale identifiers for the suppression test:

```ts
await context.addInitScript(() => {
  Object.defineProperty(navigator, 'doNotTrack', { configurable: true, value: '1' })
  localStorage.setItem('portfolio.analytics.visitor.v1', JSON.stringify({ version: 1, id: 'AAAAAAAAAAAAAAAAAAAAAA', createdAt: 0 }))
  sessionStorage.setItem('portfolio.analytics.session.v1', JSON.stringify({ version: 1, id: 'BBBBBBBBBBBBBBBBBBBBBB', lastActivityAt: 0 }))
})
```

Assert after `page.goto` that both identifier keys are absent, the consent prompt is absent, Web Crypto was not used for analytics IDs, and both the analytics-triggered CSRF GET count and event POST count remain zero after navigation and clicks. For normal consent, freeze time through an injectable clock in development/test, accept, navigate/click through each mapped action, and inspect contract booleans/order without printing raw requests. Advance to the exact 30-day/30-minute boundaries and assert rotation without printing the identifier values.

- [ ] **Step 2: Run browser tests and observe any remaining integration failures**

Run: `npm --prefix frontend run test:e2e`

Expected: initial FAIL identifies missing contact/analytics journeys, privacy boundaries, server-shell reuse, route labels, SEO fields, responsive media, focus behavior, or accessibility semantics; do not weaken assertions.

- [ ] **Step 3: Complete the smallest route/UI details required by browser evidence**

Make scoped changes so the following exact assertions pass:

1. HOME and PROJECT initial payloads cause zero matching first-load GETs;
2. client navigation requests the new locale/slug and aborts superseded requests;
3. `/zh-CN`, `/en`, both project routes, privacy, and 404 have one visible H1 and a reachable skip link;
4. project language switching preserves slug, privacy stays privacy, and invalid locale shows 404;
5. client navigation has correct title, canonical, paired hreflang, OG, and JSON-LD;
6. 500 shows safe trace ID/retry, empty catalog has explicit localized text, and project API 404 shows not-found;
7. every content image has intrinsic width/height, nonempty localized alt unless decorative, and `srcset`/`sizes`; no viewport has horizontal overflow;
8. mobile menu traps/restores focus as before, Escape closes it, keyboard reaches project links, and reduced-motion content is visible without transition;
9. axe reports no serious or critical violations on home, project, privacy, and 404;
10. both locales expose the exact contact controls and one-year/deletion copy; empty/invalid/overlong values block submit, the honeypot remains visually hidden, and privacy acceptance is mandatory;
11. valid contact submission performs exact CSRF GET→contact POST order, sends the response header/token plus exactly six body keys, and shows safe acceptance; missing/malformed CSRF sends no POST, `400` focuses the validation summary, `429` shows retry-later guidance, and a scripted `500` retains values then succeeds through Retry;
12. before opt-in and after Reject, no analytics identifiers, analytics-triggered CSRF GETs, or event POSTs exist; Accept creates one 22-character visitor record and one session record, acquires CSRF before the first POST, and Withdraw removes both plus stops later requests;
13. `DNT=1` removes preseeded identifiers before instrumentation, shows no prompt, never invokes the CSRF provider, and produces zero analytics-triggered CSRF GETs/event POSTs across routes/actions;
14. consented requests reuse the shared memory token, carry its exact returned header, contain only exact plan-06 batch/event keys, UUID event IDs, allowed type/page mappings, correct locale/project ID/null rules, and no batch over 20; token failure never sends a POST, and exact visitor/session rotation boundaries pass;
15. resume, every published project DOWNLOAD, and external links emit their exact allowlisted event once without preventing navigation; DOWNLOAD metadata renders for media-backed payloads, remains absent for external payloads, and causes no `HEAD` request.

- [ ] **Step 4: Run the full public-site verification gate**

Update `vite.config.ts` build settings to `build: { manifest: true }` so Spring Boot can reference the exact hashed public assets from the same release.

Handoff contract: use `frontend/dist/.vite/manifest.json` verbatim as the Docker build input for Spring classpath `/public-assets/.vite/manifest.json`; do not regenerate it during Maven packaging. Keep that manifest plus `frontend/dist/assets/*` under `/opt/portfolio/releases/{releaseId}/public-assets/`. Copy each content-hashed file into `/opt/portfolio/assets/` only when the filename is absent, never overwrite a hash name, and configure Nginx `/assets/` as a fixed alias to `/opt/portfolio/assets/`. Compute the manifest SHA-256 once, combine it with the Git commit to form `releaseId`, inject that same value as `PORTFOLIO_RELEASE_ID`, and permit cleanup only for shared hash files unreferenced by the newest three retained release manifests.

Run: `npm --prefix frontend run test:unit`

Expected: all Vitest unit and contract tests pass, including shared memory-only CSRF acquisition, contact/analytics privacy boundaries, exact DOWNLOAD metadata handling, and zero runtime `portfolio.ts` imports.

Run: `npm --prefix frontend run test:e2e`

Expected: desktop and mobile Playwright projects pass with exact CSRF GET→public POST ordering, zero suppressed analytics CSRF/event requests, exact contact/analytics contract assertions, no serious/critical axe violations, and no unexpected console error.

Run: `npm --prefix frontend run type-check && npm --prefix frontend run build`

Expected: exit 0; `frontend/dist/.vite/manifest.json` exists, assets are content-hashed, and the build contains no admin or backend secret.

- [ ] **Step 5: Commit the verified public integration**

```bash
git add frontend/playwright.config.ts frontend/tests/e2e frontend/vite.config.ts frontend/package.json frontend/package-lock.json frontend/src
git commit -m "test(public): verify contact privacy seo and accessibility"
```
