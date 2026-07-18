# Portfolio Admin Web Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build a production-ready `/admin` single-administrator Vue application for secure authentication, bilingual site and project editing, typed sortable content blocks, complete media/inbox/analytics/settings operations, conflict-safe autosave, preview, publishing, history, administrator security, audit, and operational status.

**Architecture:** Create a standalone `admin-web/` Vite application served from `/admin/`. Route components consume typed domain clients built on one same-origin Axios instance; server session state remains authoritative, all mutations carry CSRF, and versioned editors use one reusable autosave/conflict state machine. Editing UI works only against workspace DTOs, while preview/publish/history use the publishing API and never synthesize public snapshots in the browser.

**Tech Stack:** Node.js 22.18; Vue 3.5.31; Vue Router 4.6.4; Axios 1.18.1; qrcode 1.5.4; Tailwind CSS 4.2.2; Vite 8.1.5; TypeScript 6.0.3; Vitest 4.1.10; Vue Test Utils; Playwright.

## Global Constraints

- The administrator application is mounted at `/admin`; there is one administrator, no public registration, no multi-tenant support, no RBAC matrix, and no approval workflow.
- Pin Node.js to `22.18.x`, Vue to `3.5.31`, Vue Router to `4.6.4`, Axios to `1.18.1`, qrcode to `1.5.4`, Tailwind CSS to `4.2.2`, Vite to `8.1.5`, Vitest to `4.1.10`, and TypeScript to `6.0.3`.
- The application and `/api/admin/*` are same-origin. Authentication uses a Spring Security server session in an `HttpOnly`, `Secure`, `SameSite=Strict` cookie; never store a bearer token, password, TOTP code, recovery code, or session identifier in Web Storage.
- The login sequence is username/password followed by TOTP or a recovery code. All modifying requests use the `XSRF-TOKEN` cookie and `X-XSRF-TOKEN` header. A `401 AUTHENTICATION_REQUIRED` means the session is absent and returns to login, while `401 AUTHENTICATION_FAILED` from login or security reauthentication remains a local form error; `403` means CSRF/access rejection, `409` means state conflict/expired enrollment, `422` means validation, and `429` means a bounded retry delay.
- Supported locales are exactly `zh-CN` and `en`. Both translations remain visible in completion status and all publish-required fields must be complete before preview or publication succeeds.
- Autosave runs every 15 seconds while dirty, does not retry mutations automatically, warns before leaving with unsaved changes, and stops on `409` until the operator explicitly reloads the server version.
- Project detail uses one ordered typed block list. `MARKDOWN` is one block type; do not create a parallel long-form body, arbitrary payload JSON, arbitrary HTML, or a generic low-code page builder.
- Supported block types are exactly `MARKDOWN`, `IMAGE`, `GALLERY`, `VIDEO`, `CODE`, `QUOTE`, `METRICS`, `DOWNLOAD`, and `LINK`. Video is an HTTPS external URL; uploaded video and embed HTML are rejected.
- Media selectors may choose only `READY` assets. Draft/history media uses authenticated management URLs; the browser never receives permanent COS credentials.
- Preview first requests a 10-minute token and opens only the same-origin `/api/admin/publishing/previews/{token}` URL constructed by the client. Publishing sends the exact expected workspace, publication, and—where applicable—catalog versions; a conflict is displayed rather than overwritten.
- The inbox is complete: cursor list, escaped detail, versioned status changes, email retry, and confirmed deletion use plan-06 contracts. Analytics renders summary, zero-filled timeseries, breakdowns, definitions, timezone, and data delay. Public contact submission and public event collection still belong to plan 06 and never run in `admin-web`.
- Settings includes administrator password/TOTP/recovery-code maintenance, session revocation, immutable audit browsing, and plan-07 redacted backup/maintenance/deployment status with no secret/config editing or host operation controls. TOTP/recovery operations are one-time, `Cache-Control: no-store`, never persisted or logged, and revoke other sessions after success.
- `npm --prefix admin-web run build` outputs `admin-web/dist/index.html` and `admin-web/dist/assets/*`; release packaging copies the entire `admin-web/dist/` contents to `/opt/portfolio/releases/{releaseId}/admin/`, then atomically points `/opt/portfolio/current-admin` at that `release/admin` directory for `/admin/`. Spring does not consume an admin Vite manifest.
- Preserve the approved API problem shape: `{ type, title, status, code, traceId, fieldErrors? }`; never render stack traces, SQL, filesystem paths, or internal exception text.
- Follow TDD: add a focused failing Vitest or Playwright test, observe the intended failure, write the smallest complete implementation, rerun the focused test, then run the relevant suite.
- Commit after every task using the exact scoped paths shown in that task. Do not mix backend or public-site changes into an admin-web commit.

---

## File and Responsibility Map

```text
admin-web/
├── package.json                         pinned scripts and dependencies
├── package-lock.json                    npm-resolved lockfile
├── index.html                           admin application mount point
├── vite.config.ts                       /admin base, Tailwind, Vitest, local API proxy
├── playwright.config.ts                 browser tests against Vite
├── tsconfig.json
├── tsconfig.app.json
├── tsconfig.node.json
├── env.d.ts
├── src/
│   ├── main.ts                          Vue/bootstrap entry
│   ├── App.vue                          top-level RouterView
│   ├── assets/admin.css                 Tailwind import and admin design tokens
│   ├── router/index.ts                  authenticated route graph and guard factory
│   ├── router/redirect.ts               pure same-origin admin redirect sanitizer
│   ├── api/http.ts                      same-origin Axios, CSRF, problem conversion
│   ├── api/authApi.ts                   password/TOTP/session/logout calls
│   ├── api/siteApi.ts                   versioned SITE workspace calls
│   ├── api/projectApi.ts                projects, ordering, versioned workspace calls
│   ├── api/mediaApi.ts                  READY asset search and authenticated previews
│   ├── api/publishingApi.ts             preview tokens, publish, history, restore
│   ├── api/operationsApi.ts             inbox and analytics operations
│   ├── api/settingsApi.ts               security, audit, and system status
│   ├── types/api.ts                     ApiProblem and page/version primitives
│   ├── types/auth.ts                    session and two-step login DTOs
│   ├── types/content.ts                 locale, SITE, project, media DTOs
│   ├── types/blocks.ts                  discriminated block union
│   ├── types/publishing.ts              preview/publication/revision DTOs
│   ├── stores/session.ts                in-memory authoritative session facade
│   ├── stores/sessionInstance.ts        production session singleton
│   ├── composables/useVersionedDraft.ts 15-second autosave and 409 state machine
│   ├── composables/useTranslationStatus.ts required-field completion
│   ├── components/layout/AdminShell.vue authenticated navigation and logout
│   ├── components/common/AsyncPanel.vue loading/error/empty/retry state
│   ├── components/common/TranslationTabs.vue locale switch and completeness
│   ├── components/common/ConflictBanner.vue safe optimistic conflict actions
│   ├── components/media/MediaPickerDialog.vue READY-asset selection
│   ├── components/editor/BlockEditor.vue ordered block list and add controls
│   ├── components/editor/BlockCard.vue typed editor dispatcher
│   ├── components/editor/blocks/*.vue    one focused editor per block type
│   ├── components/publishing/PublishPanel.vue validation/preview/publish controls
│   ├── views/auth/LoginView.vue
│   ├── views/auth/TotpView.vue
│   ├── views/DashboardView.vue
│   ├── views/site/SiteEditorView.vue
│   ├── views/projects/ProjectListView.vue
│   ├── views/projects/ProjectEditorView.vue
│   ├── views/media/MediaLibraryView.vue
│   ├── views/publishing/PublishingHistoryView.vue
│   ├── views/messages/MessagesView.vue  complete cursor inbox and detail
│   ├── views/analytics/AnalyticsView.vue
│   └── views/settings/SettingsView.vue  security, sessions, audit, operations
└── tests/
    ├── unit/                             colocated-domain Vitest coverage
    ├── fixtures/                         fixed bilingual API responses
    └── e2e/                              login/TOTP/edit/preview/publish flows
```

## Cross-Task Interfaces

All API methods return the unwrapped JSON `data` value. The shared client converts non-2xx responses into this exact class:

```ts
export type FieldErrors = Record<string, string>

export interface ApiProblemBody {
  type: string
  title: string
  status: number
  code: string
  traceId: string
  fieldErrors?: FieldErrors
  retryAfterSeconds?: number
}

export class ApiProblem extends Error {
  constructor(readonly body: ApiProblemBody) {
    super(body.title)
    this.name = 'ApiProblem'
  }
}

/** UI-only adapter used by the autosave composable; it is not an HTTP envelope. */
export interface VersionedDraft<T> {
  version: number
  value: T
}

export interface Page<T> {
  items: T[]
  page: number
  size: number
  totalItems: number
  totalPages: number
}

export interface CursorPage<T> {
  items: T[]
  nextCursor: string | null
}
```

The frontend fixes these management endpoints so backend and browser work can proceed independently:

| Method and path | Request | Success response |
|---|---|---|
| `GET /api/admin/auth/me` | none | `MeResponse`; `401` when anonymous/pending |
| `GET /api/admin/auth/csrf` | none | `CsrfResponse` |
| `POST /api/admin/auth/password` | `PasswordStageRequest` | `PasswordStageResponse` |
| `POST /api/admin/auth/second-factor` | `SecondFactorRequest` | `MeResponse` |
| `POST /api/admin/auth/logout` | none | `204` |
| `GET /api/admin/site/workspace` | none | `SiteWorkspaceDto` |
| `PUT /api/admin/site/workspace` | `{ expectedVersion, workspace: SiteWorkspaceDto }` | `SiteWorkspaceDto` |
| `GET /api/admin/projects` | none | `ProjectWorkspaceDto[]` |
| `POST /api/admin/projects` | `ProjectWorkspaceDto` | `ProjectWorkspaceDto` |
| `GET /api/admin/projects/{projectId}/workspace` | none | `ProjectWorkspaceDto` |
| `PUT /api/admin/projects/{projectId}/workspace` | `{ expectedVersion, workspace: ProjectWorkspaceDto }` | `ProjectWorkspaceDto` |
| `GET /api/admin/tags` | none | `TaxonomyWorkspaceDto[]` |
| `PUT /api/admin/tags/{tagId}` | `UpdateTaxonomyRequest` | `TaxonomyWorkspaceDto` |
| `GET /api/admin/skills` | none | `TaxonomyWorkspaceDto[]` |
| `PUT /api/admin/skills/{skillId}` | `UpdateTaxonomyRequest` | `TaxonomyWorkspaceDto` |
| `GET /api/admin/media` | query `page,size,status=READY` | `MediaPageView` |
| `POST /api/admin/media` | multipart part `file` | `MediaAssetView` |
| `PUT /api/admin/media/{id}/translations` | `MediaTranslationInput[]` | `MediaAssetView` |
| `GET /api/admin/media/{id}/preview/{variant}` | none | authenticated stream or `302` signed URL |
| `DELETE /api/admin/media/{id}` | none | `204` or `409 MEDIA_STILL_REFERENCED` |
| `POST /api/admin/publishing/preview-tokens` | `PreviewTokenRequest` | `PreviewTokenResponse` |
| `GET /api/admin/publishing/previews/{token}` | none | authenticated preview snapshot |
| `POST /api/admin/publishing/site` | `PublishSiteCommand` | `PublicationResultDto` |
| `POST /api/admin/publishing/projects/{projectId}` | `PublishProjectCommand` | `PublicationResultDto` |
| `POST /api/admin/publishing/projects/{projectId}/archive` | `ArchiveProjectCommand` | `PublicationResultDto` |
| `PUT /api/admin/publishing/catalog/order` | `ReorderCatalogCommand` | `PublicationResultDto` |
| `GET /api/admin/publishing/{aggregateType}/{aggregateId}/history` | none | `RevisionSummaryDto[]` |
| `POST /api/admin/publishing/revisions/{revisionId}/restore` | `{ expectedWorkspaceVersion }` | `204` |
| `GET /api/admin/messages` | query `status,cursor,limit=30` | `CursorPage<MessageSummaryDto>` |
| `GET /api/admin/messages/{id}` | none | `MessageDetailDto` |
| `PATCH /api/admin/messages/{id}/status` | `{ status, version }` | `MessageDetailDto` |
| `POST /api/admin/messages/{id}/email/retry` | none | `204` |
| `DELETE /api/admin/messages/{id}` | none | `204` |
| `GET /api/admin/analytics/summary` | query `from,to,locale,zone=Asia/Hong_Kong` | `AnalyticsSummaryDto` |
| `GET /api/admin/analytics/timeseries` | query `from,to,metric,eventType,zone=Asia/Hong_Kong` | `AnalyticsPointDto[]` |
| `GET /api/admin/analytics/breakdown` | query `from,to,metric,eventType,dimension=PAGE\|PROJECT\|REFERRER\|DEVICE\|LOCALE,limit` | `AnalyticsBreakdownItemDto[]` |
| `GET /api/admin/security/sessions` | none | plan-01 `SessionView[]` |
| `POST /api/admin/security/sessions/{metadataId}/revoke` | none | `204` |
| `POST /api/admin/security/password` | plan-01 `PasswordChangeRequest(currentPassword,currentTotp,newPassword)` | `204` |
| `POST /api/admin/security/totp/enrollment` | plan-01 `ReauthenticationRequest(currentPassword,currentTotp)` | `TotpEnrollmentResponse(enrollmentId,provisioningUri,expiresAt)` |
| `POST /api/admin/security/totp/confirm` | plan-01 `TotpConfirmRequest(enrollmentId,newTotp)` | `RecoveryCodesResponse(recoveryCodes)` |
| `POST /api/admin/security/recovery-codes/regenerate` | plan-01 `ReauthenticationRequest(currentPassword,currentTotp)` | `RecoveryCodesResponse(recoveryCodes)` |
| `GET /api/admin/audit` | query `cursor,action,outcome,from,to,limit` | plan-01 `AdminAuditPage(items,nextCursor)` |
| `GET /api/admin/system/operations` | none | plan-07 `OperationsStatus` |

The SITE and PROJECT save bodies deliberately use the backend field name `workspace`, while `VersionedDraft<T>` is only a browser adapter around the DTO's own `version`. For the UI operations in this plan, the latest immutable revision's `version` (or `0` for empty history) is the expected SITE/PROJECT/PROJECT_CATALOG version; a successful `PublicationResultDto` immediately replaces that local CAS value. The client never retries `POST`, `PUT`, `PATCH`, or `DELETE` automatically.

Task 11 consumes the complete plan-06 message and analytics contracts: cursor list and escaped detail are live reads; status PATCH sends the loaded version; email retry POST and hard-delete DELETE require explicit confirmation and are real mutations; summary, timeseries, and breakdown are real report reads. Every listed endpoint is implemented and exercised in this plan.

### Task 1: Scaffold the pinned admin application and unit-test harness

**Files:**
- Create: `admin-web/package.json`
- Create: `admin-web/package-lock.json` (generated by npm)
- Create: `admin-web/index.html`
- Create: `admin-web/vite.config.ts`
- Create: `admin-web/tsconfig.json`
- Create: `admin-web/tsconfig.app.json`
- Create: `admin-web/tsconfig.node.json`
- Create: `admin-web/env.d.ts`
- Create: `admin-web/src/main.ts`
- Create: `admin-web/src/App.vue`
- Create: `admin-web/src/assets/admin.css`
- Test: `admin-web/src/App.spec.ts`

**Interfaces:**
- Consumes: no application code; local development API target `http://127.0.0.1:18080`.
- Produces: `npm --prefix admin-web run dev`, `test:unit`, `type-check`, and `build`; Vite base `/admin/`; alias `@ -> admin-web/src`.

- [x] **Step 1: Add pinned metadata, compiler configuration, and the first failing mount test**

Create `admin-web/package.json` exactly as follows, then create the listed TypeScript configs with `strict`, `noUncheckedIndexedAccess`, DOM libraries, and `@/* -> ./src/*`:

```json
{
  "name": "portfolio-admin-web",
  "version": "0.1.0",
  "private": true,
  "type": "module",
  "engines": { "node": "22.18.x" },
  "scripts": {
    "dev": "vite",
    "build": "npm run type-check && vite build",
    "preview": "vite preview",
    "type-check": "vue-tsc --build",
    "test": "vitest",
    "test:unit": "vitest run",
    "test:e2e": "playwright test"
  },
  "dependencies": {
    "axios": "1.18.1",
    "qrcode": "1.5.4",
    "vue": "3.5.31",
    "vue-router": "4.6.4"
  },
  "devDependencies": {
    "@playwright/test": "1.58.2",
    "@tailwindcss/vite": "4.2.2",
    "@types/node": "22.18.0",
    "@types/qrcode": "1.5.6",
    "@vitejs/plugin-vue": "6.0.3",
    "@vue/test-utils": "2.4.6",
    "@vue/tsconfig": "0.9.1",
    "jsdom": "28.0.0",
    "tailwindcss": "4.2.2",
    "typescript": "6.0.3",
    "vite": "8.1.5",
    "vitest": "4.1.10",
    "vue-tsc": "3.3.7"
  }
}
```

Create `admin-web/src/App.spec.ts` before `App.vue`:

```ts
import { mount } from '@vue/test-utils'
import { describe, expect, it } from 'vitest'
import App from './App.vue'

describe('App', () => {
  it('provides the admin application landmark', () => {
    const wrapper = mount(App, { global: { stubs: ['RouterView'] } })
    expect(wrapper.get('[data-admin-app]').exists()).toBe(true)
  })
})
```

- [x] **Step 2: Install dependencies and verify the test fails for the missing application component**

Run: `npm --prefix admin-web install`

Expected: npm writes `admin-web/package-lock.json` with no unresolved dependency.

Run: `npm --prefix admin-web run test:unit -- src/App.spec.ts`

Expected: FAIL because `admin-web/src/App.vue` does not exist.

- [x] **Step 3: Add the minimal app, Tailwind entry, and Vite configuration**

```ts
// admin-web/vite.config.ts
import { fileURLToPath, URL } from 'node:url'
import tailwindcss from '@tailwindcss/vite'
import vue from '@vitejs/plugin-vue'
import { configDefaults, defineConfig } from 'vitest/config'

export default defineConfig({
  base: '/admin/',
  plugins: [vue(), tailwindcss()],
  resolve: { alias: { '@': fileURLToPath(new URL('./src', import.meta.url)) } },
  server: { proxy: { '/api': 'http://127.0.0.1:18080' } },
  test: {
    environment: 'jsdom',
    exclude: [...configDefaults.exclude, 'tests/e2e/**'],
    restoreMocks: true,
    setupFiles: [],
  },
})
```

```css
/* admin-web/src/assets/admin.css */
@import "tailwindcss";

:root {
  color: #17202c;
  background: #f4f6fa;
  font-family: Inter, "Microsoft YaHei", "PingFang SC", sans-serif;
}

body { margin: 0; min-width: 320px; min-height: 100vh; }
button, input, textarea, select { font: inherit; }
:focus-visible { outline: 2px solid #315efb; outline-offset: 2px; }
```

```vue
<!-- admin-web/src/App.vue -->
<template>
  <div data-admin-app>
    <RouterView />
  </div>
</template>
```

```ts
// admin-web/src/main.ts
import './assets/admin.css'
import { createApp } from 'vue'
import App from './App.vue'
import router from './router'

createApp(App).use(router).mount('#app')
```

Use a temporary `admin-web/src/router/index.ts` exporting an empty `createRouter({ history: createWebHistory('/'), routes: [] })`; Task 3 replaces it with the guarded graph. The browser-history base remains `/` because the route records themselves own the `/admin` prefix; Vite's separate `/admin/` base controls asset URLs.

- [x] **Step 4: Verify mount, compiler, and production build**

Run: `npm --prefix admin-web run test:unit -- src/App.spec.ts`

Expected: PASS, 1 test.

Run: `npm --prefix admin-web run type-check && npm --prefix admin-web run build`

Expected: both commands exit 0 and `admin-web/dist/index.html` references hashed assets below `/admin/assets/`.

Verification (2026-07-18): the missing-`App.vue` test failed first as required. Under the exact `node:22.18.0-bookworm-slim` image, the final mount test passed, type-check and production build exited 0, the built HTML referenced hashed `/admin/assets/` JavaScript and CSS, and both full and production npm audits reported zero vulnerabilities. Axios, Vite, and Vitest were refreshed to the compatible exact security pins recorded above after npm identified advisories in the originally drafted versions.

- [x] **Step 5: Commit the isolated scaffold**

```bash
git add admin-web/package.json admin-web/package-lock.json admin-web/index.html admin-web/vite.config.ts admin-web/tsconfig*.json admin-web/env.d.ts admin-web/src
git commit -m "build(admin): scaffold pinned Vue admin app"
```

### Task 2: Build the same-origin HTTP, problem, CSRF, and session foundation

**Files:**
- Create: `admin-web/src/types/api.ts`
- Create: `admin-web/src/types/auth.ts`
- Create: `admin-web/src/api/http.ts`
- Create: `admin-web/src/api/authApi.ts`
- Create: `admin-web/src/stores/session.ts`
- Create: `admin-web/src/stores/sessionInstance.ts`
- Test: `admin-web/src/api/http.spec.ts`
- Test: `admin-web/src/api/authApi.spec.ts`
- Test: `admin-web/src/stores/session.spec.ts`

**Interfaces:**
- Consumes: Axios 1.18.1 and the auth endpoints in Cross-Task Interfaces.
- Produces: `http`, `ApiProblem`, `authApi`, `createSessionStore(authPort)`, the exact-code `AUTHENTICATION_REQUIRED` subscription hook, and a local `invalidate()` transition; no token persistence.

- [x] **Step 1: Write failing tests for CSRF configuration, problem conversion, and two-stage state**

```ts
// admin-web/src/api/http.spec.ts
import type { AxiosError } from 'axios'
import { describe, expect, it } from 'vitest'
import { http, toApiProblem } from './http'

describe('http', () => {
  it('uses same-origin credentials and Spring CSRF names', () => {
    expect(http.defaults.allowAbsoluteUrls).toBe(false)
    expect(http.defaults.withCredentials).toBe(true)
    expect(http.defaults.withXSRFToken).toBeUndefined()
    expect(http.defaults.xsrfCookieName).toBe('XSRF-TOKEN')
    expect(http.defaults.xsrfHeaderName).toBe('X-XSRF-TOKEN')
  })

  it('preserves the safe problem body', () => {
    const error = {
      isAxiosError: true,
      response: { status: 409, data: { type: 'conflict', title: '版本冲突', status: 409, code: 'VERSION_CONFLICT', traceId: '01ABC' } },
    } as AxiosError
    expect(toApiProblem(error).body.code).toBe('VERSION_CONFLICT')
  })
})
```

```ts
// admin-web/src/stores/session.spec.ts
import { describe, expect, it, vi } from 'vitest'
import { ApiProblem } from '@/types/api'
import { createSessionStore } from './session'

describe('session store', () => {
it('keeps only the transient second-factor expiry after password login', async () => {
    const port = {
      getMe: vi.fn().mockRejectedValue(new ApiProblem({ type: 'unauthorized', title: '需要登录', status: 401, code: 'AUTHENTICATION_REQUIRED', traceId: 't0' })),
      ensureCsrf: vi.fn().mockResolvedValue(undefined),
      passwordStage: vi.fn().mockResolvedValue({ next: 'SECOND_FACTOR', expiresAt: '2026-07-14T12:00:00Z' }),
      secondFactor: vi.fn(),
      logout: vi.fn(),
    }
    const store = createSessionStore(port)
    await store.login('admin', 'secret')
    expect(store.state.phase).toBe('TOTP_REQUIRED')
    expect(store.state.secondFactorExpiresAt).toBe('2026-07-14T12:00:00Z')
    expect(localStorage.length).toBe(0)
  })
})
```

- [x] **Step 2: Run the focused tests and observe missing-module failures**

Run: `npm --prefix admin-web run test:unit -- src/api/http.spec.ts src/stores/session.spec.ts`

Expected: FAIL because `http.ts` and `session.ts` do not exist.

- [x] **Step 3: Implement the exact DTOs, safe Axios instance, and in-memory session state**

```ts
// admin-web/src/types/auth.ts
export type SecondFactorMethod = 'TOTP' | 'RECOVERY_CODE'
export interface PasswordStageRequest { username: string; password: string }
export interface SecondFactorRequest { method: SecondFactorMethod; code: string }
export interface PasswordStageResponse { next: 'SECOND_FACTOR'; expiresAt: string }
export interface CsrfResponse { headerName: string; parameterName: string; token: string }
export interface MeResponse { id: string; username: string }
```

```ts
// admin-web/src/api/http.ts
import axios from 'axios'
import { ApiProblem, type ApiProblemBody } from '@/types/api'

export const http = axios.create({
  baseURL: '/',
  allowAbsoluteUrls: false,
  withCredentials: true,
  xsrfCookieName: 'XSRF-TOKEN',
  xsrfHeaderName: 'X-XSRF-TOKEN',
  timeout: 15_000,
})

export function toApiProblem(error: unknown): ApiProblem {
  if (error instanceof ApiProblem) return error
  if (axios.isAxiosError(error)) {
    const body = normalizeApiProblemBody(error.response?.data, error.response?.status)
    if (body) return new ApiProblem(body)
  }
  return new ApiProblem({
    type: 'network_error', title: '无法连接服务器', status: 0,
    code: 'NETWORK_ERROR', traceId: 'client',
  })
}

http.interceptors.response.use(
  (response) => response,
  (error) => Promise.reject(toApiProblem(error)),
)
```

`normalizeApiProblemBody` is a runtime allowlist, not a TypeScript cast: it requires matching HTTP/body status and bounded `type`, `title`, `code`, and `traceId`; clones only valid string `fieldErrors` plus a bounded positive `retryAfterSeconds`; accepts the same bounded delay from a header-only `Retry-After`; and drops Spring `detail`, `instance`, paths, stacks, and every unknown property. A request interceptor accepts only control-free, single-leading-slash paths with base `/`, rejecting absolute, protocol-relative, backslash-normalized, control-normalized, and overridden-base URLs before dispatch. Axios's `withXSRFToken` stays unset so its browser adapter adds the XSRF header only for same-origin requests; setting it to `true` would explicitly bypass Axios's same-origin check.

```ts
// admin-web/src/stores/session.ts
import type { MeResponse, PasswordStageResponse, SecondFactorMethod } from '@/types/auth'

export interface AuthPort {
  getMe(): Promise<MeResponse>
  ensureCsrf(): Promise<void>
  passwordStage(username: string, password: string): Promise<PasswordStageResponse>
  secondFactor(method: SecondFactorMethod, code: string): Promise<MeResponse>
  logout(): Promise<void>
}

export type SessionPhase = 'UNKNOWN' | 'ANONYMOUS' | 'TOTP_REQUIRED' | 'AUTHENTICATED'

export type SessionState =
  | { readonly phase: 'UNKNOWN' | 'ANONYMOUS'; readonly user: null; readonly secondFactorExpiresAt: null }
  | { readonly phase: 'TOTP_REQUIRED'; readonly user: null; readonly secondFactorExpiresAt: string }
  | { readonly phase: 'AUTHENTICATED'; readonly user: MeResponse; readonly secondFactorExpiresAt: null }

export interface SessionStore {
  readonly state: Readonly<SessionState>
  bootstrap(): Promise<SessionPhase>
  login(username: string, password: string): Promise<void>
  verifySecondFactor(method: SecondFactorMethod, code: string): Promise<void>
  logout(): Promise<void>
  invalidate(): void
}
```

Implement `authApi` with exact plan-01 calls: `GET /api/admin/auth/me`, `GET /api/admin/auth/csrf`, `POST /api/admin/auth/password`, `POST /api/admin/auth/second-factor`, and `POST /api/admin/auth/logout`. `ensureCsrf` accepts/ignores the safe response body because Axios sends the issued cookie/header pair; a `GET me` 401 is converted only by the session store into ANONYMOUS. Keep `session.ts` factory-only. In `sessionInstance.ts`, export the single production value `export const sessionStore = createSessionStore(authApi)` so tests can instantiate isolated stores without importing a singleton.

Runtime-check every successful auth response before changing state: UUID/nonblank administrator fields, exact `SECOND_FACTOR`, parseable ISO Instant expiry, and the exact CSRF names. The store exposes an idempotent `invalidate()`, subscribes the production singleton only to `401 AUTHENTICATION_REQUIRED`, abandons any old challenge before a new password attempt, publishes atomic frozen discriminated snapshots, serializes login/verification/logout mutations, and uses a generation/single-flight guard so a late bootstrap response cannot undo logout or invalidation. It never imports the router; Task 3 owns navigation after observing the state transition.

- [x] **Step 4: Verify HTTP and session behavior**

Run: `npm --prefix admin-web run test:unit -- src/api/http.spec.ts src/api/authApi.spec.ts src/stores/session.spec.ts`

Expected: PASS, including no Web Storage writes.

Run: `npm --prefix admin-web run type-check`

Expected: exit 0 with all auth unions exhaustively narrowed.

Verification (2026-07-18): the three focused suites first failed on the intentionally missing modules. Under exact Node 22.18, the completed implementation passed 21 focused tests; the final full admin suite passed 22/22, strict `vue-tsc` and production build exited 0, and npm audit reported zero vulnerabilities. Coverage includes browser control/backslash/cross-origin URL rejection, safe problem and Retry-After allowlisting, exact global invalidation, runtime success validation, no Web Storage writes, atomic state snapshots, serialized authentication mutations, challenge reset, and the bootstrap/logout late-response race.

- [x] **Step 5: Commit the API/security foundation**

```bash
git add admin-web/src/api admin-web/src/types/api.ts admin-web/src/types/auth.ts admin-web/src/stores
git commit -m "feat(admin): add session and CSRF API foundation"
```

### Task 3: Implement guarded routes, password login, and TOTP verification

**Files:**
- Modify: `admin-web/vite.config.ts`
- Modify: `admin-web/src/router/index.ts`
- Modify: `admin-web/src/router/index.spec.ts`
- Create: `admin-web/src/router/redirect.ts`
- Create: `admin-web/src/views/auth/LoginView.vue`
- Create: `admin-web/src/views/auth/TotpView.vue`
- Create: `admin-web/src/views/FeatureShellView.vue`
- Create: `admin-web/src/views/NotFoundView.vue`
- Test: `admin-web/src/router/index.spec.ts`
- Test: `admin-web/src/views/auth/LoginView.spec.ts`
- Test: `admin-web/src/views/auth/TotpView.spec.ts`

**Interfaces:**
- Consumes: `createSessionStore` contract and the production `sessionStore` from Task 2.
- Produces: named routes `login`, `totp`, `dashboard`, `site`, `projects`, `project-new`, `project-edit`, `media`, `publishing-history`, `messages`, `analytics`, `settings`, and `admin-not-found`.

- [x] **Step 1: Write failing guard and two-step form tests**

```ts
// admin-web/src/router/index.spec.ts
import { describe, expect, it, vi } from 'vitest'
import { createMemoryHistory } from 'vue-router'
import { createAdminRouter } from './index'

describe('admin route guard', () => {
  it('redirects an anonymous dashboard request to login', async () => {
    const session = { state: { phase: 'UNKNOWN' }, bootstrap: vi.fn().mockImplementation(function () { this.state.phase = 'ANONYMOUS'; return 'ANONYMOUS' }) }
    const router = createAdminRouter(session as never, createMemoryHistory('/'))
    await router.push('/admin/dashboard')
    await router.isReady()
    expect(router.currentRoute.value.name).toBe('login')
    expect(router.currentRoute.value.query.redirect).toBe('/admin/dashboard')
  })
})
```

```ts
// admin-web/src/views/auth/TotpView.spec.ts
import { mount } from '@vue/test-utils'
import { describe, expect, it, vi } from 'vitest'
import TotpView from './TotpView.vue'

it('submits exactly six TOTP digits to the session store', async () => {
  const verifySecondFactor = vi.fn().mockResolvedValue(undefined)
  const wrapper = mount(TotpView, {
    props: {
      session: { verifySecondFactor, invalidate: vi.fn() },
      onAuthenticated: vi.fn(),
      onRestart: vi.fn(),
    },
  })
  await wrapper.get('input[autocomplete="one-time-code"]').setValue('123456')
  await wrapper.get('form').trigger('submit')
  expect(verifySecondFactor).toHaveBeenCalledWith('TOTP', '123456')
})
```

Also test that the password form uses `autocomplete="username"` and `autocomplete="current-password"`, disables itself while submitting, and displays only `ApiProblem.body.title` plus `traceId`.

- [x] **Step 2: Run the focused tests and observe route/view failures**

Run: `npm --prefix admin-web run test:unit -- src/router/index.spec.ts src/views/auth`

Expected: FAIL because guarded router and auth views do not exist.

- [x] **Step 3: Implement the route factory and accessible auth views**

Implement `createAdminRouter(session, history)` with the complete named route graph and a phase-first guard over `UNKNOWN`, `AUTHENTICATED`, `TOTP_REQUIRED`, and `ANONYMOUS`. The guard tolerates bootstrap failure on the login destination, requires a live challenge before TOTP, invalidates expired challenges, and redirects authenticated users away from authentication pages. A reactive phase watcher returns a protected/TOTP page to login after exact global session invalidation; `disposeAdminRouter(router)` releases that watcher for tests and HMR. Keep canonical redirect parsing in side-effect-free `router/redirect.ts`, including length, control/backslash, cross-origin, encoded-path, dot-segment, nested-slash, and authentication-destination rejection.

`FeatureShellView.vue` is a small accessible compile-time route destination with required `title` and optional route-ID props; it exists only so Task 3 type-checks before later slices create their real views. The temporary parent outlet owns the single `main` landmark and `FeatureShellView` is a section, so Task 4 can replace that outlet with `AdminShell` without nested landmarks. Tasks 6, 7, 9, and 10 replace every remaining named temporary destination when their files exist.

`LoginView.vue` accepts injectable `session` and `onChallenge` props with production defaults (`sessionStore` and safe router navigation), calls `session.login`, clears the password before invoking navigation, and can retry a failed route load without resubmitting credentials. It observes the challenge phase and deadline so a stranded, expired challenge unlocks the password form. `TotpView.vue` likewise accepts injectable `session`, `onAuthenticated`, and `onRestart` props. Its default `TOTP` mode validates the raw value against `^[0-9]{6}$`; its explicit fallback normalizes one recovery-code format. Verification and post-authentication navigation are separate states, so a failed lazy route retries only navigation. Expiry/restart never invalidates an in-flight verification; a successful response wins, while a failed response after the deadline invalidates locally. Both pages clear secrets before completion callbacks, use only a pure canonical same-origin `/admin/` redirect sanitizer, and neither logs form values nor writes them to storage.

- [x] **Step 4: Verify auth routing and forms**

Run: `npm --prefix admin-web run test:unit -- src/router/index.spec.ts src/views/auth`

Expected: PASS for anonymous redirect, authenticated redirect, expired/missing challenge, six-digit validation, loading state, and safe error rendering.

Run: `npm --prefix admin-web run type-check`

Expected: exit 0.

Verification (2026-07-18): the focused suites first failed on the intentionally missing router/views, then passed 29/29 under exact Node 22.18. The final full admin suite passed 51/51 across seven files; strict `vue-tsc`, the Vite production build, and `git diff --check` exited 0; npm audit reported zero vulnerabilities. Coverage includes the complete phase matrix, bootstrap failure, live invalidation, expired challenges, canonical redirect attacks, lazy-route retry without duplicate credentials, both sides of the in-flight expiry race, secret clearing, safe problem rendering, and accessible landmarks. Three independent final audits reported no remaining P1, P2, or P3 findings.

- [x] **Step 5: Commit the authenticated entry flow**

```bash
git add admin-web/src/router admin-web/src/views/auth admin-web/src/views/FeatureShellView.vue admin-web/src/views/NotFoundView.vue docs/superpowers/plans/2026-07-14-portfolio-04-admin-web.md
git commit -m "feat(admin): add password and TOTP entry flow"
```

### Task 4: Add the authenticated shell and complete navigation dashboard

**Files:**
- Modify: `admin-web/src/router/index.ts`
- Create: `admin-web/src/components/layout/AdminShell.vue`
- Create: `admin-web/src/components/common/AsyncPanel.vue`
- Create: `admin-web/src/views/DashboardView.vue`
- Test: `admin-web/src/components/layout/AdminShell.spec.ts`
- Test: `admin-web/src/components/common/AsyncPanel.spec.ts`
- Test: `admin-web/src/views/DashboardView.spec.ts`

**Interfaces:**
- Consumes: `sessionStore.logout()` and named routes from Task 3.
- Produces: the shared authenticated layout and a complete route-navigation dashboard; it does not invent an aggregate dashboard endpoint.

- [x] **Step 1: Write failing shell and dashboard state tests**

```ts
// admin-web/src/components/layout/AdminShell.spec.ts
import { mount } from '@vue/test-utils'
import { describe, expect, it } from 'vitest'
import AdminShell from './AdminShell.vue'

describe('AdminShell', () => {
  it('exposes every approved administration destination', () => {
    const wrapper = mount(AdminShell, {
      props: { username: 'admin', onLogout: async () => undefined },
      global: { stubs: { RouterLink: { template: '<a><slot /></a>' }, RouterView: true } },
    })
    for (const label of ['仪表盘', '站点内容', '项目', '媒体库', '留言', '访问统计', '设置']) {
      expect(wrapper.text()).toContain(label)
    }
  })
})
```

```ts
// admin-web/src/views/DashboardView.spec.ts
import { mount } from '@vue/test-utils'
import { expect, it } from 'vitest'
import DashboardView from './DashboardView.vue'

it('links every complete administration area without an invented summary request', () => {
  const wrapper = mount(DashboardView, { global: { stubs: { RouterLink: { template: '<a><slot /></a>' } } } })
  for (const label of ['站点内容', '项目', '媒体库', '留言', '访问统计', '安全与运维']) {
    expect(wrapper.text()).toContain(label)
  }
})
```

- [x] **Step 2: Run the tests and confirm missing shell/dashboard failures**

Run: `npm --prefix admin-web run test:unit -- src/components/layout src/views/DashboardView.spec.ts`

Expected: FAIL because the layout and dashboard do not exist.

- [x] **Step 3: Implement the shell, retryable state component, and navigation dashboard**

Implement `AdminShell.vue` with the seven exact named destinations, a first-focus skip link, one focusable `main`, responsive navigation, the authenticated username, current-area `aria-current` for direct/project/publication subroutes, and a serialized safe logout action. Logout failures render only the allowlisted title and trace ID and never navigate. Once logout succeeds, immediately remove the protected `RouterView`, navigation, and administrator identity before attempting the login route; if lazy navigation fails, retain a signed-out screen whose button retries navigation without calling logout again. The global anonymous-session watcher and shell navigation may race safely to the same login route.

Implement `AsyncPanel.vue` with four explicit states: `loading` announces `正在加载`; a supplied safe error title plus trace ID and retry button; an empty slot; and a default content slot. Implement `DashboardView.vue` as six semantic cards with the exact named route, concise scope description, and direct action for SITE, projects, media, inbox, analytics, and security/operations. Operational data is rendered in its owning complete views from Tasks 10–12; the dashboard makes no API request and does not duplicate or approximate message, analytics, audit, or maintenance totals.

Replace Task 3's parent `RouteOutlet` with the now-existing `AdminShell.vue`, and replace only the `dashboard` route's `FeatureShellView` with `DashboardView.vue`. Leave later compile-time destinations import-safe until their owning task creates them.

- [x] **Step 4: Verify shell, dashboard, and keyboard landmarks**

Run: `npm --prefix admin-web run test:unit -- src/components/layout src/components/common src/views/DashboardView.spec.ts`

Expected: PASS; the navigation has an accessible label, every destination is keyboard-reachable, and the dashboard has no request to an unowned aggregate endpoint.

Verification (2026-07-18): all three focused suites first failed because the shell, panel, and dashboard modules did not exist, then passed 16/16. Under exact Node 22.18, the final full admin suite passed 67/67 across ten files; strict `vue-tsc`, Vite production build, and `git diff --check` exited 0; npm audit reported zero vulnerabilities. Vitest is capped at four workers after the unbounded Docker default twice started more forks than the environment could reliably initialize. Coverage includes all named links, project/publication active areas, skip/main landmarks, duplicate and failed logout, immediate protected-screen clearing, navigation-only retry, concurrent anonymous redirects, safe four-state async rendering, rejected/unmounted retry lifecycles, six source-free semantic dashboard cards, and transport/session zero-call assertions. Three independent final audits reported no remaining P1, P2, or P3 findings.

- [x] **Step 5: Commit the dashboard slice**

```bash
git add admin-web/vite.config.ts admin-web/src/router admin-web/src/components/layout admin-web/src/components/common/AsyncPanel.vue admin-web/src/components/common/AsyncPanel.spec.ts admin-web/src/views/DashboardView.vue admin-web/src/views/DashboardView.spec.ts docs/superpowers/plans/2026-07-14-portfolio-04-admin-web.md
git commit -m "feat(admin): add authenticated shell and dashboard"
```

### Task 5: Create bilingual completion and versioned autosave primitives

**Files:**
- Create: `admin-web/src/types/content.ts`
- Create: `admin-web/src/composables/useTranslationStatus.ts`
- Create: `admin-web/src/composables/useVersionedDraft.ts`
- Create: `admin-web/src/components/common/TranslationTabs.vue`
- Create: `admin-web/src/components/common/ConflictBanner.vue`
- Test: `admin-web/src/composables/useTranslationStatus.spec.ts`
- Test: `admin-web/src/composables/useVersionedDraft.spec.ts`
- Test: `admin-web/src/components/common/TranslationTabs.spec.ts`
- Test: `admin-web/src/components/common/ConflictBanner.spec.ts`

**Interfaces:**
- Consumes: `ApiProblem`, UI-only `VersionedDraft<T>`, and the backend's version-bearing workspace DTOs.
- Produces: `Locale`, `Localized<T>`, `translationStatus`, and `useVersionedDraft<T>`. Site and project editors must use these rather than independent timers.

- [x] **Step 1: Write failing tests for completion, 15-second save, and 409 stop state**

```ts
// admin-web/src/composables/useTranslationStatus.spec.ts
import { describe, expect, it } from 'vitest'
import { translationStatus } from './useTranslationStatus'

describe('translationStatus', () => {
  it('reports required fields independently for zh-CN and en', () => {
    const result = translationStatus(
      { 'zh-CN': { title: '标题', summary: '摘要' }, en: { title: 'Title', summary: '' } },
      ['title', 'summary'],
    )
    expect(result['zh-CN']).toEqual({ complete: 2, total: 2 })
    expect(result.en).toEqual({ complete: 1, total: 2 })
  })
})
```

```ts
// admin-web/src/composables/useVersionedDraft.spec.ts
import { nextTick } from 'vue'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { ApiProblem } from '@/types/api'
import { useVersionedDraft } from './useVersionedDraft'

describe('useVersionedDraft', () => {
  beforeEach(() => vi.useFakeTimers())

  it('saves a dirty draft after 15 seconds with the loaded version', async () => {
    const save = vi.fn().mockResolvedValue({ version: 8, value: { title: 'new' } })
    const model = useVersionedDraft({ load: async () => ({ version: 7, value: { title: 'old' } }), save })
    await model.reload()
    model.draft.value!.title = 'new'
    await nextTick()
    await vi.advanceTimersByTimeAsync(15_000)
    expect(save).toHaveBeenCalledWith({ expectedVersion: 7, workspace: { title: 'new' } })
    model.stop()
  })

  it('stops autosave after a version conflict', async () => {
    const save = vi.fn().mockRejectedValue(new ApiProblem({ type: 'conflict', title: '版本冲突', status: 409, code: 'VERSION_CONFLICT', traceId: 't1' }))
    const model = useVersionedDraft({ load: async () => ({ version: 1, value: { title: 'a' } }), save })
    await model.reload(); model.draft.value!.title = 'b'; await nextTick(); await model.saveNow()
    expect(model.conflict.value?.body.code).toBe('VERSION_CONFLICT')
    await vi.advanceTimersByTimeAsync(30_000)
    expect(save).toHaveBeenCalledTimes(1)
    model.stop()
  })
})
```

- [x] **Step 2: Run the focused tests and observe missing-composable failures**

Run: `npm --prefix admin-web run test:unit -- src/composables/useTranslationStatus.spec.ts src/composables/useVersionedDraft.spec.ts`

Expected: FAIL because both composables are absent.

- [x] **Step 3: Implement exact locale and autosave state**

```ts
// admin-web/src/types/content.ts (shared beginning)
export const locales = ['zh-CN', 'en'] as const
export type Locale = (typeof locales)[number]
export type Localized<T> = Record<Locale, T>
export interface SaveWorkspaceRequest<T> { expectedVersion: number; workspace: T }
```

```ts
// admin-web/src/composables/useTranslationStatus.ts
import type { Locale, Localized } from '@/types/content'

export function translationStatus<T extends Record<string, unknown>>(
  translations: Localized<T>, required: readonly (keyof T)[],
): Record<Locale, { complete: number; total: number }> {
  return Object.fromEntries((['zh-CN', 'en'] as const).map((locale) => {
    const complete = required.filter((key) => {
      const value = translations[locale][key]
      return typeof value === 'string' ? value.trim().length > 0 : value !== null && value !== undefined
    }).length
    return [locale, { complete, total: required.length }]
  })) as Record<Locale, { complete: number; total: number }>
}
```

```ts
// admin-web/src/composables/useVersionedDraft.ts
import { getCurrentScope, nextTick, onScopeDispose, ref, watch, type Ref } from 'vue'
import { ApiProblem, type VersionedDraft } from '@/types/api'
import type { SaveWorkspaceRequest } from '@/types/content'

export function useVersionedDraft<T>(options: {
  load(): Promise<VersionedDraft<T>>
  save(request: SaveWorkspaceRequest<T>): Promise<VersionedDraft<T>>
  intervalMs?: number
}) {
  const draft = ref<T | null>(null) as Ref<T | null>
  const version = ref(0)
  const loading = ref(false), saving = ref(false), dirty = ref(false)
  const error = ref<ApiProblem | null>(null), conflict = ref<ApiProblem | null>(null)
  let hydrating = false
  let timer: ReturnType<typeof setInterval> | undefined

  watch(draft, () => { if (!hydrating && draft.value) dirty.value = true }, { deep: true })

  async function apply(result: VersionedDraft<T>) {
    hydrating = true
    draft.value = structuredClone(result.value)
    version.value = result.version
    dirty.value = false
    conflict.value = null
    await nextTick()
    hydrating = false
  }
  async function reload() {
    loading.value = true; error.value = null
    try { await apply(await options.load()) }
    catch (cause) { error.value = cause instanceof ApiProblem ? cause : new ApiProblem({ type: 'client_error', title: '加载失败', status: 0, code: 'LOAD_FAILED', traceId: 'client' }) }
    finally { loading.value = false }
  }
  async function saveNow() {
    if (!draft.value || !dirty.value || saving.value || conflict.value) return
    saving.value = true; error.value = null
    try { await apply(await options.save({ expectedVersion: version.value, workspace: structuredClone(draft.value) })) }
    catch (cause) {
      const problem = cause instanceof ApiProblem ? cause : new ApiProblem({ type: 'client_error', title: '保存失败', status: 0, code: 'SAVE_FAILED', traceId: 'client' })
      if (problem.body.status === 409) conflict.value = problem
      else error.value = problem
    } finally { saving.value = false }
  }
  const beforeUnload = (event: BeforeUnloadEvent) => { if (dirty.value) event.preventDefault() }
  function start() {
    if (timer) return
    timer = setInterval(() => void saveNow(), options.intervalMs ?? 15_000)
    if (typeof window !== 'undefined') window.addEventListener('beforeunload', beforeUnload)
  }
  function stop() {
    if (timer) clearInterval(timer)
    timer = undefined
    if (typeof window !== 'undefined') window.removeEventListener('beforeunload', beforeUnload)
  }
  start()
  if (getCurrentScope()) onScopeDispose(stop)
  return { draft, version, loading, saving, dirty, error, conflict, reload, saveNow, stop }
}
```

Implement `TranslationTabs.vue` as a two-button tablist with `aria-selected`, visible `complete/total`, and emitted `update:modelValue`. Implement `ConflictBanner.vue` with the server trace ID, `保留当前页面` (dismisses no state and therefore keeps autosave stopped), and `重新载入服务器版本` (emits `reload` after an explicit confirmation); do not provide a force-overwrite action.

The completed primitives serialize saves, deep-clone recursively unwrapped reactive workspaces, preserve edits made during in-flight saves, ignore stale save/reload responses, and require an explicit manual retry after non-409 mutation failures. Each locale tab owns a unique panel and renders only its active scoped slot. `ConflictBanner` requires a controlled `reloading` prop so failed reloads can recover without duplicate requests; confirmation, cancellation, replacement, and completion preserve keyboard focus without pulling it back after the user leaves the banner.

- [x] **Step 4: Verify timing, conflict, and accessibility behavior**

Run: `npm --prefix admin-web run test:unit -- src/composables src/components/common/TranslationTabs.spec.ts src/components/common/ConflictBanner.spec.ts`

Expected: PASS; fake timers cause exactly one save at 15 seconds, `409` stops later calls, and both locale tabs expose completion counts.

Verification (2026-07-18): the focused tests first failed because the content types, composables, and components were absent, then passed 33/33 under exact Node 22.18. The final full admin suite passed 100/100 across 14 files; strict `vue-tsc` and the Vite production build exited 0; npm audit reported zero vulnerabilities. Coverage includes trimmed bilingual completion with deduplicated requirements, exact one-shot 15-second autosave, manual-only retry after non-409 failures, hard stop and explicit reload after any real 409, edits during saves, stale reload/save responses, deep reactive ordered-list cloning, clone failures, scope/unload cleanup, unique tab/panel associations, active-only scoped slots, keyboard and multi-instance isolation, safe conflict text, required controlled reload state, duplicate-request prevention, and focus preservation without focus theft. Three independent final audits reported no remaining P1, P2, or P3 findings.

- [x] **Step 5: Commit the reusable editor state**

```bash
git add admin-web/src/types/content.ts admin-web/src/composables admin-web/src/components/common/TranslationTabs.vue admin-web/src/components/common/TranslationTabs.spec.ts admin-web/src/components/common/ConflictBanner.vue admin-web/src/components/common/ConflictBanner.spec.ts docs/superpowers/plans/2026-07-14-portfolio-04-admin-web.md
git commit -m "feat(admin): add bilingual conflict-safe autosave"
```

### Task 6: Build the complete bilingual SITE workspace editor and media selector

**Files:**
- Modify: `admin-web/src/router/index.ts`
- Modify: `admin-web/src/router/index.spec.ts`
- Modify: `admin-web/src/types/content.ts`
- Modify: `admin-web/src/composables/useVersionedDraft.ts`
- Modify: `admin-web/src/composables/useVersionedDraft.spec.ts`
- Create: `admin-web/src/api/siteApi.ts`
- Test: `admin-web/src/api/siteApi.spec.ts`
- Create: `admin-web/src/api/mediaApi.ts`
- Test: `admin-web/src/api/mediaApi.spec.ts`
- Create: `admin-web/src/components/media/MediaPickerDialog.vue`
- Create: `admin-web/src/components/site/SiteIdentityForm.vue`
- Create: `admin-web/src/components/site/SiteTranslationForm.vue`
- Create: `admin-web/src/components/site/OrderedLocalizedList.vue`
- Create: `admin-web/src/tests/fixtures/siteWorkspace.ts`
- Create: `admin-web/src/views/site/SiteEditorView.vue`
- Test: `admin-web/src/components/media/MediaPickerDialog.spec.ts`
- Test: `admin-web/src/components/site/*.spec.ts`
- Test: `admin-web/src/views/site/SiteEditorView.spec.ts`

**Interfaces:**
- Consumes: UI-only `VersionedDraft<T>`, `useVersionedDraft`, locale tabs, conflict banner, plan-03 `GET/PUT /api/admin/site/workspace`, and plan-02 READY media search.
- Produces: `SiteWorkspaceDto`, `MediaAssetSummaryDto`, `siteApi`, `mediaApi`, and a complete editor for the approved `SITE` aggregate workspace.

- [x] **Step 1: Write failing tests for every SITE content group and READY-only selection**

```ts
// admin-web/src/views/site/SiteEditorView.spec.ts
import { flushPromises, mount } from '@vue/test-utils'
import { expect, it, vi } from 'vitest'
import SiteEditorView from './SiteEditorView.vue'
import { siteFixture } from '@/tests/fixtures/siteWorkspace'

it('exposes identity, SEO, a11y, navigation, hero, about, work, roadmap, contact, privacy, social, and resume groups', async () => {
  const wrapper = mount(SiteEditorView, {
    props: { load: vi.fn().mockResolvedValue(siteFixture), save: vi.fn() },
    global: { stubs: { MediaPickerDialog: true, PublishPanel: true } },
  })
  await flushPromises()
  for (const heading of ['身份', 'SEO', '无障碍', '导航', 'Hero', '关于', '作品区', '路线图', '联系', '隐私', '社交链接', '双语简历']) {
    expect(wrapper.text()).toContain(heading)
  }
})
```

```ts
// admin-web/src/components/media/MediaPickerDialog.spec.ts
import { flushPromises, mount } from '@vue/test-utils'
import { expect, it, vi } from 'vitest'
import MediaPickerDialog from './MediaPickerDialog.vue'

it('returns only a READY compatible asset', async () => {
  const select = vi.fn()
  const wrapper = mount(MediaPickerDialog, {
    props: { open: true, accept: ['IMAGE'], load: vi.fn().mockResolvedValue({ items: [{ id: 'a1', kind: 'IMAGE', originalFilename: 'hero.jpg', mimeType: 'image/jpeg', status: 'READY', previewUrl: '/api/admin/media/a1/preview/thumbnail', width: 1600, height: 900 }], page: 0, size: 24, totalItems: 1, totalPages: 1 }) },
    attrs: { onSelect: select },
  })
  await flushPromises(); await wrapper.get('[data-asset-id="a1"]').trigger('click')
  expect(select).toHaveBeenCalledWith(expect.objectContaining({ id: 'a1', status: 'READY' }))
})
```

- [x] **Step 2: Run focused tests and verify the editor contracts are absent**

Run: `npm --prefix admin-web run test:unit -- src/views/site src/components/media`

Expected: FAIL because SITE/media DTOs and components do not exist.

- [x] **Step 3: Define the SITE/media DTOs and clients**

Append these exact TypeScript mirrors of plan 03's `SiteWorkspaceDto` records to `types/content.ts`; every ordered item uses its backend field name `sortOrder`:

```ts
export interface IdentityCopy { displayName: string; secondaryName: string }
export interface SeoCopy { title: string; description: string }
export interface AccessibilityCopy { skip: string; primaryNav: string; mobileNav: string; openMenu: string; closeMenu: string; language: string; backToTop: string; projectTags: string }
export interface NavigationItem { id: string; target: string; sortOrder: number; visible: boolean; labels: Localized<string> }
export interface HeroCopy { eyebrow: string; displayName: string; secondaryName: string; role: string; headline: string; introduction: string; availability: string; primaryCta: string; secondaryCta: string; visualLabel: string; stageLabel: string }
export interface Hero { id: string; version: number; mediaAssetId: string | null; objectPosition: string | null; credit: string | null; sourceUrl: string | null; copy: Localized<HeroCopy> }
export interface AboutCopy { label: string; title: string; statement: string; focusLabel: string; focusTitle: string; focusIntro: string }
export interface LabelValueCopy { label: string; value: string }
export interface ProfileFact { id: string; externalKey: string; sortOrder: number; copy: Localized<LabelValueCopy> }
export interface SkillStatusCopy { name: string; status: string }
export interface ProfileSkill { id: string; externalKey: string; sortOrder: number; copy: Localized<SkillStatusCopy> }
export interface WorkCopy { label: string; title: string; introduction: string; imageNotice: string; openSlotLabel: string; openSlotTitle: string; openSlotText: string; openSlotMeta: string }
export interface RoadmapHeaderCopy { label: string; title: string; introduction: string }
export interface RoadmapOutcome { id: string; sortOrder: number; text: Localized<string> }
export interface RoadmapStageCopy { period: string; title: string; summary: string }
export interface RoadmapStage { id: string; externalKey: string; number: string; sortOrder: number; visible: boolean; copy: Localized<RoadmapStageCopy>; outcomes: RoadmapOutcome[] }
export interface Roadmap { header: Localized<RoadmapHeaderCopy>; stages: RoadmapStage[] }
export interface ContactCopy { label: string; title: string; introduction: string; emailLabel: string; workCta: string; roadmapCta: string; footerNote: string }
export interface PrivacyCopy { title: string; bodyMarkdown: string }
export interface SocialLink { id: string; platform: string; url: string; sortOrder: number; visible: boolean }
export interface ResumeDocument { id: string; locale: Locale; mediaAssetId: string; versionLabel: string; current: boolean; documentDate: string }
export interface SiteWorkspaceDto {
  siteId: string; version: number; monogram: string; email: string
  identity: Localized<IdentityCopy>; seo: Localized<SeoCopy>; accessibility: Localized<AccessibilityCopy>
  navigation: NavigationItem[]; hero: Hero; about: Localized<AboutCopy>; facts: ProfileFact[]
  profileSkills: ProfileSkill[]; work: Localized<WorkCopy>; roadmap: Roadmap
  contact: Localized<ContactCopy>; privacy: Localized<PrivacyCopy>; socialLinks: SocialLink[]; resumes: ResumeDocument[]
}
export type MediaKind = 'IMAGE' | 'PDF' | 'FILE'
export interface MediaAssetSummaryDto {
  id: string; kind: MediaKind; originalFilename: string; mimeType: string
  status: 'READY' | 'PROCESSING' | 'FAILED' | 'ARCHIVED'
  previewUrl: string | null; width: number | null; height: number | null
}
```

```ts
// admin-web/src/api/siteApi.ts
import { http } from './http'
import type { VersionedDraft } from '@/types/api'
import type { SaveWorkspaceRequest, SiteWorkspaceDto } from '@/types/content'
const draft = (workspace: SiteWorkspaceDto): VersionedDraft<SiteWorkspaceDto> => ({ version: workspace.version, value: workspace })
export const siteApi = {
  async get(): Promise<VersionedDraft<SiteWorkspaceDto>> {
    return draft((await http.get<SiteWorkspaceDto>('/api/admin/site/workspace')).data)
  },
  async save(request: SaveWorkspaceRequest<SiteWorkspaceDto>): Promise<VersionedDraft<SiteWorkspaceDto>> {
    const body = { expectedVersion: request.expectedVersion, workspace: request.workspace }
    return draft((await http.put<SiteWorkspaceDto>('/api/admin/site/workspace', body)).data)
  },
}
```

`mediaApi.search` always sends `status=READY`, normalizes the plan-02 `MediaPageView` into `Page<MediaAssetSummaryDto>`, and filters `kind`/text locally because the backend list contract exposes only `page`, `size`, and `status`. It constructs authenticated previews exclusively as `/api/admin/media/{encodeURIComponent(id)}/preview/{encodeURIComponent(variant)}`. `MediaPickerDialog` rejects a returned asset if its status is not `READY` or its kind is outside `accept`, even if a faulty response includes it.

- [x] **Step 4: Implement and verify the complete SITE form**

`SiteEditorView.vue` must:

1. call `useVersionedDraft({ load: siteApi.get, save: siteApi.save })` and `reload()` on mount;
2. render explicit sections for every property in `SiteWorkspaceDto`—no JSON textarea;
3. switch the form locale without hiding completion for the other locale;
4. reorder navigation, facts, profile skills, roadmap stages, and outcomes while rewriting contiguous `sortOrder` values;
5. validate email format and HTTPS social/source URLs client-side while treating backend `422 fieldErrors` as authoritative;
6. select `hero.mediaAssetId` and each locale's current `ResumeDocument.mediaAssetId` through `MediaPickerDialog` (`IMAGE` and `PDF` respectively); media alt/caption stays in plan-02 `media_translation` and is never duplicated into the SITE DTO;
7. show saved/saving/dirty/conflict state, call `saveNow` from a visible button, and use `onBeforeRouteLeave` to confirm when dirty;
8. replace the temporary `site` route destination with `SiteEditorView`, then mount `PublishPanel` for aggregate `{ aggregateType: 'SITE', aggregateId: '00000000-0000-0000-0000-000000000001' }` after Task 9.

Run: `npm --prefix admin-web run test:unit -- src/views/site src/components/site src/components/media`

Expected: PASS for every section, locale switch, ordered-list renumbering, READY filtering, manual save, and `409` conflict banner.

Run: `npm --prefix admin-web run type-check`

Expected: exit 0; every SITE field is typed and no `any` is introduced.

Verification (2026-07-18): focused tests first failed for the missing SITE editor and for the invalid-draft autosave recovery defect. The completed slice passed the full Node 22.18 admin suite with 160/160 tests across 21 files, strict `vue-tsc`, the Vite production build, and npm audit with zero vulnerabilities. The production build keeps `SiteEditorView` in an independent lazy chunk (18.68 kB gzip). Coverage includes exact Java DTO and Jackson `non_null` wire normalization, all-or-none Hero media attribution, bilingual completion and language-of-parts, contiguous ordered lists, nested accessible field errors, complete reload/409 states, local-validation autosave recovery, save/media races, strict READY/MIME/UUID selection, preview path binding, malformed pagination, focus trapping/restoration, idempotent close, and late-request invalidation. Three independent final audits reported no remaining P1, P2, or P3 findings.

- [x] **Step 5: Commit the SITE editor slice**

```bash
git add admin-web/src/router admin-web/src/types/content.ts admin-web/src/composables/useVersionedDraft.ts admin-web/src/composables/useVersionedDraft.spec.ts admin-web/src/api/siteApi.ts admin-web/src/api/siteApi.spec.ts admin-web/src/api/mediaApi.ts admin-web/src/api/mediaApi.spec.ts admin-web/src/components/media admin-web/src/components/site admin-web/src/tests/fixtures/siteWorkspace.ts admin-web/src/views/site docs/superpowers/plans/2026-07-14-portfolio-04-admin-web.md
git commit -m "feat(admin): add bilingual site workspace editor"
```

### Task 7: Add project catalog, bilingual metadata, and the full block DTO union

**Files:**
- Modify: `admin-web/src/router/index.ts`
- Create: `admin-web/src/types/blocks.ts`
- Modify: `admin-web/src/types/content.ts`
- Create: `admin-web/src/api/projectApi.ts`
- Create: `admin-web/src/views/projects/ProjectListView.vue`
- Create: `admin-web/src/views/projects/ProjectEditorView.vue`
- Create: `admin-web/src/components/projects/ProjectMetadataForm.vue`
- Test: `admin-web/src/types/blocks.spec.ts`
- Test: `admin-web/src/views/projects/ProjectListView.spec.ts`
- Test: `admin-web/src/views/projects/ProjectEditorView.spec.ts`

**Interfaces:**
- Consumes: media summaries, locale/completion components, and versioned autosave.
- Produces: `ProjectWorkspaceDto`, `ProjectListItemDto`, `ContentBlockDto`, `projectApi`, and editor metadata into which Task 8 mounts `BlockEditor`.

- [ ] **Step 1: Write failing tests for discriminated blocks and project ordering**

```ts
// admin-web/src/types/blocks.spec.ts
import { describe, expect, it } from 'vitest'
import { createBlock } from './blocks'

describe('createBlock', () => {
  it.each(['MARKDOWN', 'IMAGE', 'GALLERY', 'VIDEO', 'CODE', 'QUOTE', 'METRICS', 'DOWNLOAD', 'LINK'] as const)(
    'creates an exact %s payload with a stable id', (type) => {
      const block = createBlock(type)
      expect(block.payload.type).toBe(type)
      expect(block.id).toMatch(/^[0-9a-f-]{36}$/)
      expect(block.sortOrder).toBe(0)
    },
  )
})
```

Project list tests must assert client-side status/search filtering over the exact plan-03 `ProjectWorkspaceDto[]`, accessible empty state, deterministic `sortOrder`, and edit navigation by stable UUID rather than slug. Catalog reorder is a publishing mutation and is wired with its exact CAS command in Task 9.

- [ ] **Step 2: Run project/block tests and observe missing-contract failures**

Run: `npm --prefix admin-web run test:unit -- src/types/blocks.spec.ts src/views/projects`

Expected: FAIL because the project and block modules do not exist.

- [ ] **Step 3: Define the complete typed block and project workspace contracts**

```ts
// admin-web/src/types/blocks.ts
import type { Localized } from './content'
export type BlockType = 'MARKDOWN' | 'IMAGE' | 'GALLERY' | 'VIDEO' | 'CODE' | 'QUOTE' | 'METRICS' | 'DOWNLOAD' | 'LINK'
export type BlockWidth = 'NARROW' | 'STANDARD' | 'WIDE' | 'FULL'
export type BlockAlignment = 'LEFT' | 'CENTER' | 'RIGHT'
export type BlockEmphasis = 'NONE' | 'SOFT' | 'STRONG'
export interface BlockCopy { title: string; description: string }
export interface ActionCopy { label: string; description: string }
export interface QuoteCopy { quote: string; source: string }
export interface MetricCopy { label: string; value: string; suffix: string }
export interface MetricDto { id: string; sortOrder: number; numericValue: number | null; copy: Localized<MetricCopy> }
export type ContentBlockPayload =
  | { type: 'MARKDOWN'; markdown: Localized<string> }
  | { type: 'IMAGE'; mediaAssetId: string | null }
  | { type: 'GALLERY'; mediaAssetIds: string[] }
  | { type: 'VIDEO'; provider: string; url: string; coverAssetId: string | null; copy: Localized<BlockCopy> }
  | { type: 'CODE'; code: string; language: string; showLineNumbers: boolean; copy: Localized<BlockCopy> }
  | { type: 'QUOTE'; copy: Localized<QuoteCopy> }
  | { type: 'METRICS'; metrics: MetricDto[] }
  | { type: 'DOWNLOAD'; mediaAssetId: string | null; externalUrl: string | null; copy: Localized<ActionCopy> }
  | { type: 'LINK'; url: string; openNewTab: boolean; copy: Localized<ActionCopy> }
export interface ContentBlockDto {
  id: string; sortOrder: number; visible: boolean; width: BlockWidth
  alignment: BlockAlignment; emphasis: BlockEmphasis; columns: number; payload: ContentBlockPayload
}

const localized = <T>(factory: () => T): Localized<T> => ({ 'zh-CN': factory(), en: factory() })
export function createBlock(type: BlockType): ContentBlockDto {
  const base = { id: crypto.randomUUID(), sortOrder: 0, visible: true, width: 'STANDARD', alignment: 'LEFT', emphasis: 'NONE', columns: 1 } as const
  switch (type) {
    case 'MARKDOWN': return { ...base, payload: { type, markdown: localized(() => '') } }
    case 'IMAGE': return { ...base, payload: { type, mediaAssetId: null } }
    case 'GALLERY': return { ...base, payload: { type, mediaAssetIds: [] } }
    case 'VIDEO': return { ...base, payload: { type, provider: 'BILIBILI', url: '', coverAssetId: null, copy: localized(() => ({ title: '', description: '' })) } }
    case 'CODE': return { ...base, payload: { type, code: '', language: 'text', showLineNumbers: true, copy: localized(() => ({ title: '', description: '' })) } }
    case 'QUOTE': return { ...base, payload: { type, copy: localized(() => ({ quote: '', source: '' })) } }
    case 'METRICS': return { ...base, payload: { type, metrics: [] } }
    case 'DOWNLOAD': return { ...base, payload: { type, mediaAssetId: null, externalUrl: null, copy: localized(() => ({ label: '', description: '' })) } }
    case 'LINK': return { ...base, payload: { type, url: '', openNewTab: true, copy: localized(() => ({ label: '', description: '' })) } }
  }
}
```

```ts
// append to admin-web/src/types/content.ts
import type { ContentBlockDto } from './blocks'
export interface ProjectCopy { status: string; eyebrow: string; title: string; summary: string; seoTitle: string; seoDescription: string }
export interface ProjectTaxonomyRefDto { id: string; normalizedKey: string; sortOrder: number; names: Localized<string> }
export interface TaxonomyWorkspaceDto { id: string; normalizedKey: string; version: number; names: Localized<string> }
export interface UpdateTaxonomyRequest { expectedVersion: number; names: Localized<string> }
export interface ProjectMediaDto { assetId: string; usage: string; sortOrder: number; layout: string; objectPosition: string; credit: string; sourceUrl: string }
export interface ProjectWorkspaceDto {
  id: string; externalKey: string; slug: string; number: string; sortOrder: number
  featured: boolean; visible: boolean; publicationDirty: boolean; version: number
  translations: Localized<ProjectCopy>; tags: ProjectTaxonomyRefDto[]; skills: ProjectTaxonomyRefDto[]
  media: ProjectMediaDto[]; blocks: ContentBlockDto[]
}
export interface ProjectListItemDto { id: string; slug: string; number: string; sortOrder: number; featured: boolean; visible: boolean; publicationDirty: boolean; title: Localized<string>; status: Localized<string>; workspaceVersion: number }
```

```ts
// admin-web/src/api/projectApi.ts
import { http } from './http'
import type { VersionedDraft } from '@/types/api'
import type { ProjectWorkspaceDto, SaveWorkspaceRequest, TaxonomyWorkspaceDto, UpdateTaxonomyRequest } from '@/types/content'

const draft = (workspace: ProjectWorkspaceDto): VersionedDraft<ProjectWorkspaceDto> => ({ version: workspace.version, value: workspace })
export const projectApi = {
  async list(): Promise<ProjectWorkspaceDto[]> { return (await http.get<ProjectWorkspaceDto[]>('/api/admin/projects')).data },
  async create(workspace: ProjectWorkspaceDto): Promise<VersionedDraft<ProjectWorkspaceDto>> {
    return draft((await http.post<ProjectWorkspaceDto>('/api/admin/projects', workspace)).data)
  },
  async get(projectId: string): Promise<VersionedDraft<ProjectWorkspaceDto>> {
    const id = encodeURIComponent(projectId)
    return draft((await http.get<ProjectWorkspaceDto>(`/api/admin/projects/${id}/workspace`)).data)
  },
  async save(projectId: string, request: SaveWorkspaceRequest<ProjectWorkspaceDto>): Promise<VersionedDraft<ProjectWorkspaceDto>> {
    if (request.workspace.id !== projectId) throw new Error('project path/body id mismatch')
    const id = encodeURIComponent(projectId)
    const body = { expectedVersion: request.expectedVersion, workspace: request.workspace }
    return draft((await http.put<ProjectWorkspaceDto>(`/api/admin/projects/${id}/workspace`, body)).data)
  },
  async tags(): Promise<TaxonomyWorkspaceDto[]> { return (await http.get<TaxonomyWorkspaceDto[]>('/api/admin/tags')).data },
  async skills(): Promise<TaxonomyWorkspaceDto[]> { return (await http.get<TaxonomyWorkspaceDto[]>('/api/admin/skills')).data },
  async updateTag(id: string, request: UpdateTaxonomyRequest): Promise<TaxonomyWorkspaceDto> {
    return (await http.put<TaxonomyWorkspaceDto>(`/api/admin/tags/${encodeURIComponent(id)}`, request)).data
  },
  async updateSkill(id: string, request: UpdateTaxonomyRequest): Promise<TaxonomyWorkspaceDto> {
    return (await http.put<TaxonomyWorkspaceDto>(`/api/admin/skills/${encodeURIComponent(id)}`, request)).data
  },
}
```

- [ ] **Step 4: Implement list and metadata editor, then verify**

`projectApi` uses `GET /api/admin/projects`, `POST /api/admin/projects`, `GET/PUT /api/admin/projects/{projectId}/workspace`, and the exact taxonomy endpoints without client mutation retries. It maps each version-bearing workspace to/from UI-only `VersionedDraft<ProjectWorkspaceDto>` exactly as `siteApi` does; PUT sends `{ expectedVersion, workspace }`. Global taxonomy editing uses versioned `TaxonomyWorkspaceDto` plus `{ expectedVersion, names }`; the project DTO separately embeds `ProjectTaxonomyRefDto` with `sortOrder`, and the UI never substitutes one shape for the other. `ProjectListView` maps the returned array into display rows and applies status/search filters locally. `ProjectEditorView` loads by `projectId`, or creates a complete plan-03 `ProjectWorkspaceDto` only after slug (`^[a-z0-9]+(?:-[a-z0-9]+)*$`) and number are valid. It mounts exact bilingual `translations`, `media`, `tags`, `skills`, autosave status, conflict handling, and an initially empty block region that Task 8 replaces with `BlockEditor`; media alt/caption remains the selected plan-02 media translation, not a duplicate project field. Replace the `projects`, `project-new`, and `project-edit` temporary route destinations only after both views exist.

Run: `npm --prefix admin-web run test:unit -- src/types/blocks.spec.ts src/views/projects src/components/projects`

Expected: PASS for create/edit paths, ASCII slug validation, locale completion, stable UUID navigation, client-side status filtering, deterministic `sortOrder`, taxonomy loading, and READY project-media selection.

Run: `npm --prefix admin-web run type-check`

Expected: exit 0; `switch (block.payload.type)` is exhaustive.

- [ ] **Step 5: Commit the project workspace contracts and metadata UI**

```bash
git add admin-web/src/router/index.ts admin-web/src/types/blocks.ts admin-web/src/types/content.ts admin-web/src/api/projectApi.ts admin-web/src/views/projects admin-web/src/components/projects
git commit -m "feat(admin): add bilingual project workspace editor"
```

### Task 8: Implement the typed sortable block editor and media-backed block controls

**Files:**
- Create: `admin-web/src/components/editor/blockOrder.ts`
- Create: `admin-web/src/components/editor/BlockEditor.vue`
- Create: `admin-web/src/components/editor/BlockCard.vue`
- Create: `admin-web/src/components/editor/blocks/MarkdownBlockEditor.vue`
- Create: `admin-web/src/components/editor/blocks/ImageBlockEditor.vue`
- Create: `admin-web/src/components/editor/blocks/GalleryBlockEditor.vue`
- Create: `admin-web/src/components/editor/blocks/VideoBlockEditor.vue`
- Create: `admin-web/src/components/editor/blocks/CodeBlockEditor.vue`
- Create: `admin-web/src/components/editor/blocks/QuoteBlockEditor.vue`
- Create: `admin-web/src/components/editor/blocks/MetricsBlockEditor.vue`
- Create: `admin-web/src/components/editor/blocks/DownloadBlockEditor.vue`
- Create: `admin-web/src/components/editor/blocks/LinkBlockEditor.vue`
- Modify: `admin-web/src/views/projects/ProjectEditorView.vue`
- Test: `admin-web/src/components/editor/blockOrder.spec.ts`
- Test: `admin-web/src/components/editor/BlockEditor.spec.ts`
- Test: `admin-web/src/components/editor/BlockCard.spec.ts`

**Interfaces:**
- Consumes: `ContentBlockDto`, `createBlock`, current `Locale`, and `MediaPickerDialog`.
- Produces: `BlockEditor` with `v-model:blocks`, stable ordering, keyboard controls, pointer drag/drop, exhaustive typed child editors, and no raw HTML input.

- [ ] **Step 1: Write failing reorder and exhaustive-render tests**

```ts
// admin-web/src/components/editor/blockOrder.spec.ts
import { expect, it } from 'vitest'
import { moveBlock } from './blockOrder'

it('moves by stable id and rewrites contiguous sortOrder values', () => {
  const blocks = [
    { id: 'a', sortOrder: 0 }, { id: 'b', sortOrder: 1 }, { id: 'c', sortOrder: 2 },
  ]
  expect(moveBlock(blocks, 'c', 0)).toEqual([
    { id: 'c', sortOrder: 0 }, { id: 'a', sortOrder: 1 }, { id: 'b', sortOrder: 2 },
  ])
})
```

```ts
// admin-web/src/components/editor/BlockEditor.spec.ts
import { mount } from '@vue/test-utils'
import { defineComponent, ref } from 'vue'
import { expect, it } from 'vitest'
import BlockEditor from './BlockEditor.vue'
import type { ContentBlockDto } from '@/types/blocks'

it('adds every approved block type and emits ordered blocks', async () => {
  const Harness = defineComponent({
    components: { BlockEditor },
    setup() { return { blocks: ref([]) } },
    template: '<BlockEditor v-model:blocks="blocks" locale="zh-CN" />',
  })
  const wrapper = mount(Harness)
  for (const type of ['MARKDOWN', 'IMAGE', 'GALLERY', 'VIDEO', 'CODE', 'QUOTE', 'METRICS', 'DOWNLOAD', 'LINK']) {
    await wrapper.get(`[data-add-block="${type}"]`).trigger('click')
  }
  const blocks = (wrapper.vm as unknown as { blocks: ContentBlockDto[] }).blocks
  expect(blocks.map((block) => block.payload.type)).toEqual(['MARKDOWN', 'IMAGE', 'GALLERY', 'VIDEO', 'CODE', 'QUOTE', 'METRICS', 'DOWNLOAD', 'LINK'])
  expect(blocks.map((block) => block.sortOrder)).toEqual([0, 1, 2, 3, 4, 5, 6, 7, 8])
})
```

Add a parameterized `BlockCard.spec.ts` that mounts each union member and asserts the matching editor is visible, then assert unsafe `javascript:` and `data:` URLs produce an inline error before save.

- [ ] **Step 2: Run block tests and observe missing editor failures**

Run: `npm --prefix admin-web run test:unit -- src/components/editor`

Expected: FAIL because the ordering utility and block components do not exist.

- [ ] **Step 3: Implement immutable ordering and the block list**

```ts
// admin-web/src/components/editor/blockOrder.ts
export function moveBlock<T extends { id: string; sortOrder: number }>(items: readonly T[], id: string, targetIndex: number): T[] {
  const currentIndex = items.findIndex((item) => item.id === id)
  if (currentIndex < 0) return items.map((item, sortOrder) => ({ ...item, sortOrder }))
  const next = items.map((item) => ({ ...item }))
  const [moved] = next.splice(currentIndex, 1)
  if (!moved) return next
  next.splice(Math.max(0, Math.min(targetIndex, next.length)), 0, moved)
  return next.map((item, sortOrder) => ({ ...item, sortOrder }))
}
```

```vue
<!-- admin-web/src/components/editor/BlockEditor.vue -->
<script setup lang="ts">
import { ref } from 'vue'
import type { BlockType, ContentBlockDto } from '@/types/blocks'
import type { Locale } from '@/types/content'
import { createBlock } from '@/types/blocks'
import { moveBlock } from './blockOrder'
import BlockCard from './BlockCard.vue'

const props = defineProps<{ blocks: ContentBlockDto[]; locale: Locale }>()
const emit = defineEmits<{ 'update:blocks': [ContentBlockDto[]] }>()
const types: BlockType[] = ['MARKDOWN', 'IMAGE', 'GALLERY', 'VIDEO', 'CODE', 'QUOTE', 'METRICS', 'DOWNLOAD', 'LINK']
const draggedId = ref<string | null>(null)
const add = (type: BlockType) => emit('update:blocks', [...props.blocks, { ...createBlock(type), sortOrder: props.blocks.length }])
const update = (index: number, block: ContentBlockDto) => emit('update:blocks', props.blocks.map((value, i) => i === index ? block : value))
const remove = (id: string) => emit('update:blocks', props.blocks.filter((block) => block.id !== id).map((block, sortOrder) => ({ ...block, sortOrder })))
</script>

<template>
  <section aria-labelledby="content-blocks-title">
    <h2 id="content-blocks-title">项目内容块</h2>
    <div class="flex flex-wrap gap-2" aria-label="添加内容块">
      <button v-for="type in types" :key="type" type="button" :data-add-block="type" @click="add(type)">{{ type }}</button>
    </div>
    <ol class="mt-4 grid gap-4">
      <li v-for="(block, index) in blocks" :key="block.id" draggable="true"
          @dragstart="draggedId = block.id" @dragover.prevent
          @drop="draggedId && emit('update:blocks', moveBlock(blocks, draggedId, index))">
        <div class="flex gap-2">
          <button type="button" :disabled="index === 0" :aria-label="`上移 ${block.payload.type}`" @click="emit('update:blocks', moveBlock(blocks, block.id, index - 1))">↑</button>
          <button type="button" :disabled="index === blocks.length - 1" :aria-label="`下移 ${block.payload.type}`" @click="emit('update:blocks', moveBlock(blocks, block.id, index + 1))">↓</button>
          <button type="button" :aria-label="`删除 ${block.payload.type}`" @click="remove(block.id)">删除</button>
        </div>
        <BlockCard :block="block" :locale="locale" @update:block="update(index, $event)" />
      </li>
    </ol>
  </section>
</template>
```

`BlockCard.vue` uses an exhaustive `switch` over `block.payload.type`, passes the narrowed payload plus shared block layout and current locale to the matching editor, and emits a complete `ContentBlockDto`. A final `never` assertion guarantees a new backend payload type cannot compile without an editor.

- [ ] **Step 4: Implement all nine focused editors and media rules**

Each editor must bind the following complete fields and emit a new object rather than mutating props:

| Editor | Required controls |
|---|---|
| `MarkdownBlockEditor` | exact `payload.markdown` bilingual textareas; no HTML mode and no rendered editable DOM |
| `ImageBlockEditor` | exact `payload.mediaAssetId` READY image picker; show selected media's bilingual alt/caption completeness from plan-02 media translations without copying it into the block DTO |
| `GalleryBlockEditor` | exact ordered `payload.mediaAssetIds` with at least two READY images; show per-asset media-translation completeness without adding per-item objects |
| `VideoBlockEditor` | exact `provider`, HTTPS `url`, optional READY `coverAssetId`, and bilingual `copy`; no upload or embed field |
| `CodeBlockEditor` | code textarea, language identifier, line-number toggle, bilingual title/description; preview uses text content, never `v-html` |
| `QuoteBlockEditor` | exact bilingual `copy.quote/source` |
| `MetricsBlockEditor` | exact ordered `metrics` with stable IDs, `sortOrder`, optional `numericValue`, and bilingual `copy.label/value/suffix` |
| `DownloadBlockEditor` | exact `mediaAssetId`/`externalUrl` choice plus bilingual `copy`; require one READY PDF/FILE or one allowlisted HTTPS URL |
| `LinkBlockEditor` | exact HTTPS `url`, `openNewTab`, and bilingual `copy` |

Shared layout controls bind only the exact `width`, `alignment`, `emphasis`, and integer `columns` fields from plan 03. Client URL validation is `new URL(value).protocol === 'https:'`; the backend remains authoritative and returns path-specific `422` errors. Integrate `<BlockEditor v-model:blocks="draft.blocks" :locale="activeLocale" />` into `ProjectEditorView` and include the exact nested `payload` union in the existing autosave request.

Run: `npm --prefix admin-web run test:unit -- src/components/editor src/views/projects/ProjectEditorView.spec.ts`

Expected: PASS for all types, locale switching without losing the other locale, pointer and keyboard ordering, media constraints, URL protocol rejection, deletion renumbering, and autosave payload preservation.

Run: `npm --prefix admin-web run type-check`

Expected: exit 0 with no unhandled `BlockType` branch.

- [ ] **Step 5: Commit the block-editor slice**

```bash
git add admin-web/src/components/editor admin-web/src/views/projects/ProjectEditorView.vue
git commit -m "feat(admin): add typed sortable project blocks"
```

### Task 9: Add validation, short-lived preview, atomic publish, history, and restore UI

**Files:**
- Modify: `admin-web/src/router/index.ts`
- Create: `admin-web/src/types/publishing.ts`
- Create: `admin-web/src/api/publishingApi.ts`
- Create: `admin-web/src/components/publishing/PublishPanel.vue`
- Create: `admin-web/src/views/publishing/PublishingHistoryView.vue`
- Modify: `admin-web/src/views/site/SiteEditorView.vue`
- Modify: `admin-web/src/views/projects/ProjectListView.vue`
- Modify: `admin-web/src/views/projects/ProjectEditorView.vue`
- Test: `admin-web/src/components/publishing/PublishPanel.spec.ts`
- Test: `admin-web/src/views/publishing/PublishingHistoryView.spec.ts`

**Interfaces:**
- Consumes: current workspace versions, exact plan-03 publishing commands/results, and client-side translation completion.
- Produces: `publishingApi`, 10-minute preview-token handling, SITE/PROJECT publish, catalog reorder, immutable history, and restore-to-current-editor behavior.

- [ ] **Step 1: Write failing validation/publish/history tests**

```ts
// admin-web/src/components/publishing/PublishPanel.spec.ts
import { mount } from '@vue/test-utils'
import { expect, it, vi } from 'vitest'
import PublishPanel from './PublishPanel.vue'

it('blocks publish when local bilingual completeness contains errors', async () => {
  const publish = vi.fn()
  const wrapper = mount(PublishPanel, {
    props: {
      target: { aggregateType: 'PROJECT', aggregateId: '00000000-0000-0000-0000-000000000111', expectedWorkspaceVersion: 4, expectedProjectPublicationVersion: 2, expectedCatalogVersion: 9 },
      locale: 'en',
      completion: { 'zh-CN': { complete: 5, total: 5 }, en: { complete: 4, total: 5 } },
      createPreview: vi.fn(), publish,
    },
  })
  expect(wrapper.get('[data-action="publish"]').attributes('disabled')).toBeDefined()
  expect(wrapper.text()).toContain('4/5')
  expect(publish).not.toHaveBeenCalled()
})
```

Add a second test where completion is full, `publish` rejects with plan-03 `422` field errors, and the panel renders every returned path plus the safe problem title/trace ID. History tests assert immutable revision IDs, versions, schema versions, timestamps, and checksums; restore posts the current workspace version to the selected revision path, receives `204`, reloads the same editor, and leaves the historical row unchanged.

- [ ] **Step 2: Run publishing tests and observe missing-module failures**

Run: `npm --prefix admin-web run test:unit -- src/components/publishing src/views/publishing`

Expected: FAIL because publishing contracts and components do not exist.

- [ ] **Step 3: Implement typed publication contracts and client**

```ts
// admin-web/src/types/publishing.ts
import type { Locale } from './content'
export type AggregateType = 'SITE' | 'PROJECT' | 'PROJECT_CATALOG'
export interface PreviewTokenRequest { aggregateType: 'SITE' | 'PROJECT'; aggregateId: string; workspaceVersion: number; locale: Locale }
export interface PreviewTokenResponse { token: string; expiresAt: string }
export interface PublishSiteCommand { expectedWorkspaceVersion: number; expectedPublicationVersion: number }
export interface PublishProjectCommand { projectId: string; expectedWorkspaceVersion: number; expectedProjectPublicationVersion: number; expectedCatalogVersion: number }
export interface ArchiveProjectCommand { projectId: string; expectedProjectPublicationVersion: number; expectedCatalogVersion: number }
export interface ReorderCatalogCommand { expectedCatalogVersion: number; projectIdsInOrder: string[] }
export interface PublicationResultDto { revisionId: string; aggregateVersion: number; catalogRevisionId: string | null; catalogVersion: number | null; checksum: string }
export interface RevisionSummaryDto { id: string; type: AggregateType; aggregateId: string; version: number; schemaVersion: number; checksum: string; publishedBy: string; publishedAt: string }
export interface RestoreRevisionRequest { expectedWorkspaceVersion: number }
export type PublishTarget =
  | ({ aggregateType: 'SITE'; aggregateId: string } & PublishSiteCommand)
  | ({ aggregateType: 'PROJECT'; aggregateId: string } & Omit<PublishProjectCommand, 'projectId'>)
```

```ts
// admin-web/src/api/publishingApi.ts
import { http } from './http'
import type { AggregateType, ArchiveProjectCommand, PreviewTokenRequest, PreviewTokenResponse, PublicationResultDto, PublishSiteCommand, PublishProjectCommand, ReorderCatalogCommand, RestoreRevisionRequest, RevisionSummaryDto } from '@/types/publishing'
export const publishingApi = {
  async createPreview(request: PreviewTokenRequest): Promise<PreviewTokenResponse> {
    return (await http.post<PreviewTokenResponse>('/api/admin/publishing/preview-tokens', request)).data
  },
  previewUrl(token: string): string {
    return `/api/admin/publishing/previews/${encodeURIComponent(token)}`
  },
  async publishSite(command: PublishSiteCommand): Promise<PublicationResultDto> {
    return (await http.post<PublicationResultDto>('/api/admin/publishing/site', command)).data
  },
  async publishProject(command: PublishProjectCommand): Promise<PublicationResultDto> {
    const path = `/api/admin/publishing/projects/${encodeURIComponent(command.projectId)}`
    return (await http.post<PublicationResultDto>(path, command)).data
  },
  async archiveProject(command: ArchiveProjectCommand): Promise<PublicationResultDto> {
    const path = `/api/admin/publishing/projects/${encodeURIComponent(command.projectId)}/archive`
    return (await http.post<PublicationResultDto>(path, command)).data
  },
  async reorderCatalog(command: ReorderCatalogCommand): Promise<PublicationResultDto> {
    return (await http.put<PublicationResultDto>('/api/admin/publishing/catalog/order', command)).data
  },
  async history(aggregateType: AggregateType, aggregateId: string): Promise<RevisionSummaryDto[]> {
    const type = encodeURIComponent(aggregateType)
    const id = encodeURIComponent(aggregateId)
    return (await http.get<RevisionSummaryDto[]>(`/api/admin/publishing/${type}/${id}/history`)).data
  },
  async restore(revisionId: string, request: RestoreRevisionRequest): Promise<void> {
    await http.post(`/api/admin/publishing/revisions/${encodeURIComponent(revisionId)}/restore`, request)
  },
}
```

- [ ] **Step 4: Implement publish and history behavior, then verify**

`PublishPanel.vue` renders both locale completion counts before enabling preview or publish. Preview sends exact `{ aggregateType, aggregateId, workspaceVersion, locale }`, verifies that the token is nonblank and not already expired, constructs the same-origin URL only through `publishingApi.previewUrl`, and opens it with `window.open(url, '_blank', 'noopener,noreferrer')`. Publication reconfirms immediately, dispatches either `publishSite` or `publishProject`, disables all actions while active, and emits the exact `PublicationResultDto`. There is no invented validation endpoint: a `409` shows `ConflictBanner`, while authoritative preview/publish `422 fieldErrors` render without discarding the draft.

`PublishingHistoryView.vue` validates route params (`SITE|PROJECT|PROJECT_CATALOG`, UUID), loads the exact revision array, sorts a defensive copy by descending `version`, and displays immutable revision metadata. SITE/PROJECT rows offer restore only after explicit confirmation; they post `{ expectedWorkspaceVersion }` to the selected `revisionId`, receive `204`, then call the editor's reload callback and route back to `/admin/site` or `/admin/projects/{aggregateId}` constructed locally. PROJECT_CATALOG history is read-only because plan 03 rejects catalog restore.

Site and project editors load their expected publication versions from the first history row (or `0`), retain the workspace DTO's own version for `expectedWorkspaceVersion`, and replace publication/catalog CAS values from each successful result. `ProjectListView` also loads `PROJECT_CATALOG` history using aggregate ID `00000000-0000-0000-0000-000000000002`; reorder sends exact `{ expectedCatalogVersion, projectIdsInOrder }`, uses the returned `catalogVersion`, and on `409` reloads projects plus history instead of retrying. This plan does not expose archive UI, so it never attempts to infer a post-archive project pointer from revision count.

Replace the temporary `publishing-history` route destination only after `PublishingHistoryView.vue` exists. Add `PublishPanel` to SITE/PROJECT editors and exact reorder wiring to the project list.

Run: `npm --prefix admin-web run test:unit -- src/components/publishing src/views/publishing src/views/site src/views/projects`

Expected: PASS for incomplete translation, server `422`, expired preview token, safe preview URL construction, SITE publish with `expectedPublicationVersion`, PROJECT publish with both publication/catalog versions, catalog reorder CAS, `409`, descending history, and restore/reload navigation.

- [ ] **Step 5: Commit the publication workflow**

```bash
git add admin-web/src/router/index.ts admin-web/src/types/publishing.ts admin-web/src/api/publishingApi.ts admin-web/src/components/publishing admin-web/src/views/publishing admin-web/src/views/site/SiteEditorView.vue admin-web/src/views/projects/ProjectListView.vue admin-web/src/views/projects/ProjectEditorView.vue
git commit -m "feat(admin): add preview publish and history workflow"
```

### Task 10: Complete authenticated media upload, translation, preview, and archive

**Files:**
- Modify: `admin-web/src/router/index.ts`
- Modify: `admin-web/src/types/content.ts`
- Modify: `admin-web/src/api/mediaApi.ts`
- Create: `admin-web/src/views/media/MediaLibraryView.vue`
- Test: `admin-web/src/views/media/MediaLibraryView.spec.ts`
- Test: `admin-web/src/api/mediaApi.spec.ts`

**Interfaces:**
- Consumes: exact plan-02 media management endpoints, `MediaPickerDialog`, and `AsyncPanel`.
- Produces: complete media library search/status pages, upload, bilingual metadata editing, authenticated preview, reference-safe archive, and the real `media` route.

- [ ] **Step 1: Write failing media-management tests**

Test loading/error/empty/retry, then upload and archive boundaries:

```ts
// admin-web/src/views/media/MediaLibraryView.spec.ts
import { flushPromises, mount } from '@vue/test-utils'
import { expect, it, vi } from 'vitest'
import MediaLibraryView from './MediaLibraryView.vue'

it('uploads the selected file as multipart and refreshes processing state', async () => {
  const upload = vi.fn().mockResolvedValue({ id: 'a1', status: 'PROCESSING' })
  const wrapper = mount(MediaLibraryView, { props: { load: vi.fn().mockResolvedValue({ items: [], page: 0, size: 24, totalItems: 0, totalPages: 0 }), upload } })
  await flushPromises()
  const file = new File(['png'], 'work.png', { type: 'image/png' })
  await wrapper.get('input[type="file"]').setValue(file)
  await wrapper.get('[data-action="upload"]').trigger('click')
  expect(upload).toHaveBeenCalledWith(file)
  expect(wrapper.text()).toContain('PROCESSING')
})
```

- [ ] **Step 2: Run the media test and observe the missing mutation UI**

Run: `npm --prefix admin-web run test:unit -- src/views/media/MediaLibraryView.spec.ts src/components/media`

Expected: FAIL because `MediaLibraryView` and management methods do not exist.

- [ ] **Step 3: Extend the exact plan-02 media contract and client**

```ts
// append to admin-web/src/types/content.ts
export interface MediaTranslationInput { locale: Locale; altText: string; caption: string; credit: string; sourceUrl: string | null }
export interface MediaAssetView extends MediaAssetSummaryDto { translations: MediaTranslationInput[]; variants: Array<{ name: string; width: number | null; height: number | null; status: string }> }
```

Extend `mediaApi` with `upload(file)` using a new `FormData` and multipart key `file` without manually setting the boundary; `updateTranslations(id, input)` using exact locale/altText/caption/credit/sourceUrl fields; `previewUrl(id, variant)` using encoded path segments; and `archive(id)`. Search continues to send only plan-02 `page`, `size`, and `status`, applying kind/text locally. Mutations are never retried.

- [ ] **Step 4: Implement the complete media library and verify boundaries**

- `MediaLibraryView` paginates every status, filters kind/text locally, polls a just-uploaded PROCESSING asset no faster than every five seconds while the page is visible, and stops polling at READY/FAILED or unmount.
- The file control accepts only the approved image/PDF/file MIME set, displays server size/signature failures through safe `ApiProblem`, and never previews SVG/HTML/script bytes.
- Selecting an asset opens its authenticated preview and an explicit two-locale metadata form. Save sends both locale rows; HTTPS validation for `sourceUrl` is client-side while backend `422` remains authoritative.
- Archive requires confirmation. `409 MEDIA_STILL_REFERENCED` renders the returned reference-safe message and trace ID; success removes the asset from selectable READY results without physical-delete claims.
- Replace only the temporary `media` route destination after the complete view exists.

Run: `npm --prefix admin-web run test:unit -- src/views/media src/components/media src/api/mediaApi.spec.ts`

Expected: PASS for four-state rendering, multipart upload, status polling/cleanup, bilingual translation save, authenticated preview, READY-only selection, confirmed archive, and referenced-asset `409`.

- [ ] **Step 5: Commit complete media management**

```bash
git add admin-web/src/router/index.ts admin-web/src/types/content.ts admin-web/src/api/mediaApi.ts admin-web/src/views/media admin-web/src/components/media
git commit -m "feat(admin): complete media management"
```

### Task 11: Complete the plan-06 message inbox and analytics workbench

**Files:**
- Modify: `admin-web/src/router/index.ts`
- Create: `admin-web/src/types/operations.ts`
- Create: `admin-web/src/api/operationsApi.ts`
- Create: `admin-web/src/views/messages/MessagesView.vue`
- Create: `admin-web/src/views/messages/MessageDetailPanel.vue`
- Create: `admin-web/src/views/analytics/AnalyticsView.vue`
- Test: `admin-web/src/views/messages/MessagesView.spec.ts`
- Test: `admin-web/src/views/analytics/AnalyticsView.spec.ts`
- Test: `admin-web/src/api/operationsApi.spec.ts`

**Interfaces:**
- Consumes: the exact plan-06 admin message and analytics endpoints; execute this task only after plan 06's controllers are available.
- Produces: complete cursor inbox/detail/status/retry/delete behavior and summary/timeseries/breakdown analytics with definitions and delay.

- [ ] **Step 1: Write failing inbox mutation and analytics-definition tests**

```ts
// admin-web/src/views/messages/MessagesView.spec.ts
import { flushPromises, mount } from '@vue/test-utils'
import { expect, it, vi } from 'vitest'
import MessagesView from './MessagesView.vue'

it('opens escaped detail and sends the loaded version on status change', async () => {
  const updateStatus = vi.fn().mockResolvedValue({ id: 'm1', status: 'READ', version: 4 })
  const wrapper = mount(MessagesView, { props: {
    list: vi.fn().mockResolvedValue({ items: [{ id: 'm1', visitorName: 'A', visitorEmail: 'a@example.com', subject: '<b>Hello</b>', status: 'UNREAD', emailStatus: 'FAILED', createdAt: '2026-07-14T00:00:00Z', version: 3 }], nextCursor: null }),
    detail: vi.fn().mockResolvedValue({ id: 'm1', visitorName: 'A', visitorEmail: 'a@example.com', subject: '<b>Hello</b>', body: '<script>alert(1)</script>', status: 'UNREAD', email: { status: 'FAILED', attempts: 1, nextAttemptAt: '2026-07-14T00:01:01Z', sentAt: null, updatedAt: '2026-07-14T00:00:01Z', errorCategory: 'SMTP_UNAVAILABLE' }, privacyAcceptedAt: '2026-07-14T00:00:00Z', createdAt: '2026-07-14T00:00:00Z', updatedAt: '2026-07-14T00:00:00Z', version: 3 }),
    updateStatus,
  } })
  await flushPromises(); await wrapper.get('[data-message-id="m1"]').trigger('click'); await flushPromises()
  expect(wrapper.find('script').exists()).toBe(false)
  expect(wrapper.text()).toContain('SMTP_UNAVAILABLE')
  expect(wrapper.text()).toContain('2026-07-14T00:01:01Z')
  await wrapper.get('[data-status="READ"]').trigger('click')
  expect(updateStatus).toHaveBeenCalledWith('m1', { status: 'READ', version: 3 })
})
```

```ts
// admin-web/src/views/analytics/AnalyticsView.spec.ts
import { flushPromises, mount } from '@vue/test-utils'
import { expect, it, vi } from 'vitest'
import AnalyticsView from './AnalyticsView.vue'

it('labels PV, anonymous daily UV, definitions, zone, and data delay', async () => {
  const wrapper = mount(AnalyticsView, { props: { load: vi.fn().mockResolvedValue({
    summary: { pageViews: 120, dailyUniqueVisitors: 45, projectViews: 30, resumeDownloads: 2, demoDownloads: 4, outboundClicks: 8, dataCompleteThrough: '2026-07-14T00:55:00Z', zone: 'Asia/Hong_Kong', definitions: { DAILY_UV: '匿名日 UV 按自然日求和' } },
    timeseries: [{ date: '2026-07-14', value: 120 }], breakdown: [{ dimensionValue: 'p1', value: 30 }],
  }) } })
  await flushPromises()
  expect(wrapper.text()).toContain('PV'); expect(wrapper.text()).toContain('匿名日 UV')
  expect(wrapper.text()).toContain('Asia/Hong_Kong'); expect(wrapper.text()).toContain('2026-07-14T00:55:00Z')
})
```

- [ ] **Step 2: Run focused tests and observe missing complete pages**

Run: `npm --prefix admin-web run test:unit -- src/views/messages src/views/analytics src/api/operationsApi.spec.ts`

Expected: FAIL because the complete views and clients do not exist.

- [ ] **Step 3: Define exact plan-06 DTOs and clients**

```ts
// admin-web/src/types/operations.ts
export type MessageStatus = 'UNREAD' | 'READ' | 'ARCHIVED' | 'SPAM'
export interface MessageSummaryDto { id: string; visitorName: string; visitorEmail: string; subject: string; status: MessageStatus; emailStatus: string; createdAt: string; version: number }
export interface EmailDeliveryView { status: string; attempts: number; nextAttemptAt: string | null; sentAt: string | null; updatedAt: string; errorCategory: string | null }
export interface MessageDetailDto { id: string; visitorName: string; visitorEmail: string; subject: string; body: string; status: MessageStatus; email: EmailDeliveryView; privacyAcceptedAt: string; createdAt: string; updatedAt: string; version: number }
export interface UpdateMessageStatusRequest { status: MessageStatus; version: number }
export interface AnalyticsSummaryDto { pageViews: number; dailyUniqueVisitors: number; projectViews: number; resumeDownloads: number; demoDownloads: number; outboundClicks: number; dataCompleteThrough: string | null; zone: string; definitions: Record<string, string> }
export interface AnalyticsPointDto { date: string; value: number }
export type AnalyticsDimension = 'PAGE' | 'PROJECT' | 'REFERRER' | 'DEVICE' | 'LOCALE'
export interface AnalyticsBreakdownItemDto { dimensionValue: string; value: number }
```

`operationsApi` implements every fixed message path from Cross-Task Interfaces: cursor list with `limit=30`, detail GET, versioned status PATCH, email retry POST, and delete. It also implements the exact analytics queries: summary sends `from,to,locale,zone`; timeseries sends `from,to,metric,eventType,zone`; breakdown sends `from,to,metric,eventType,dimension,limit,zone`. The zone is always `Asia/Hong_Kong`; range is at most 366 days; mutations never retry.

- [ ] **Step 4: Implement the complete inbox and analytics UX**

- Inbox status changes reset the cursor; “load more” appends without duplicates by UUID. Selecting a row loads full detail, renders subject/body only by Vue text interpolation, and moves focus to the detail heading.
- Status actions send the detail's current version and replace both detail/list row from the response. A `409 MESSAGE_VERSION_CONFLICT` reloads that message and asks the operator to retry explicitly.
- Email detail mirrors all six plan-06 delivery fields: status, attempts, scheduled `nextAttemptAt`, nullable `sentAt`, `updatedAt`, and nullable safe `errorCategory`. Render null times/categories as an explicit unavailable state, use text interpolation, and never display an exception or SMTP response. Retry appears only when the exact status is `FAILED` or `DEAD`, shows the next scheduled attempt when present, confirms, shows progress, and reloads detail. Delete requires typing `DELETE`, calls the exact endpoint, removes the row, clears detail, and never includes visitor PII in logs/toasts.
- Analytics exposes date/locale/metric/event/dimension controls, validates the range, loads all three endpoints together, renders six summary cards, a table/chart with an accessible table equivalent, ranked breakdown, every definition entry, timezone, and `dataCompleteThrough` delay. A null `dataCompleteThrough` renders as “尚无完整聚合 / No complete aggregation”; empty data is a valid zero state, and request failures remain retryable.
- Replace the temporary `messages` and `analytics` route destinations only after both complete views exist. Do not add public event collection to the admin bundle.

Run: `npm --prefix admin-web run test:unit -- src/views/messages src/views/analytics src/api/operationsApi.spec.ts`

Expected: PASS for cursor reset/append, escaped detail, all six email-delivery fields, safe error category/next-attempt display, all status transitions, version conflict, retry/delete confirmation, exact analytics queries, definitions, delay, empty data, and retry.

- [ ] **Step 5: Commit complete inbox and analytics**

```bash
git add admin-web/src/router/index.ts admin-web/src/types/operations.ts admin-web/src/api/operationsApi.ts admin-web/src/views/messages admin-web/src/views/analytics
git commit -m "feat(admin): complete inbox and analytics"
```

### Task 12: Complete administrator security, sessions, audit, and operations settings

**Files:**
- Modify: `admin-web/src/router/index.ts`
- Create: `admin-web/src/types/settings.ts`
- Create: `admin-web/src/api/settingsApi.ts`
- Create: `admin-web/src/views/settings/SettingsView.vue`
- Create: `admin-web/src/components/settings/SecuritySettings.vue`
- Create: `admin-web/src/components/settings/TotpEnrollmentPanel.vue`
- Create: `admin-web/src/components/settings/OneTimeRecoveryCodes.vue`
- Create: `admin-web/src/components/settings/SessionTable.vue`
- Create: `admin-web/src/components/settings/AuditTable.vue`
- Create: `admin-web/src/components/settings/OperationsStatus.vue`
- Test: `admin-web/src/views/settings/SettingsView.spec.ts`
- Test: `admin-web/src/components/settings/SecuritySettings.spec.ts`
- Test: `admin-web/src/components/settings/SessionTable.spec.ts`
- Test: `admin-web/src/api/settingsApi.spec.ts`

**Interfaces:**
- Consumes: the exact plan-01 credential/session/audit contracts and the single fixed plan-07 system endpoint in Cross-Task Interfaces; the matching auth/system backend slice must land before this task.
- Produces: complete password/TOTP/recovery forms, local-only TOTP QR rendering, acknowledged one-time recovery-code handling, current/other-session revocation, immutable cursor audit browsing, plan-07 backup/maintenance/deployment status, and links to SITE SEO/resume editors.

- [ ] **Step 1: Write failing one-time-secret and session-revocation tests**

```ts
// admin-web/src/components/settings/SecuritySettings.spec.ts
import { mount } from '@vue/test-utils'
import { expect, it, vi } from 'vitest'
import SecuritySettings from './SecuritySettings.vue'

it('requires offline-save acknowledgement before clearing one-time recovery codes', async () => {
  const regenerate = vi.fn().mockResolvedValue({ recoveryCodes: ['ALPH-BRAV-2345', 'CHAR-DELT-6789'] })
  const wrapper = mount(SecuritySettings, { props: { regenerate } })
  await wrapper.get('[data-field="current-password"]').setValue('temporary')
  await wrapper.get('[data-field="current-totp"]').setValue('123456')
  await wrapper.get('[data-action="regenerate-recovery"]').trigger('click')
  expect(wrapper.text()).toContain('ALPH-BRAV-2345')
  expect(wrapper.get('[data-action="dismiss-codes"]').attributes('disabled')).toBeDefined()
  await wrapper.get('[data-field="codes-saved-offline"]').setValue(true)
  await wrapper.get('[data-action="dismiss-codes"]').trigger('click')
  expect(wrapper.text()).not.toContain('ALPH-BRAV-2345')
  expect(localStorage.length).toBe(0); expect(sessionStorage.length).toBe(0)
})
```

Add tests that:

- password change requires a matching local repeated-password field but sends exactly `{ currentPassword, currentTotp, newPassword }` with no confirmation-only key;
- enrollment sends `ReauthenticationRequest`, passes only the returned `provisioningUri` to `QRCode.toCanvas`, sends `{ enrollmentId, newTotp }` to confirm, and performs no QR/image/network request containing the provisioning URI;
- the canvas, provisioning URI, TOTP/password fields, and recovery-code array are cleared on success, expiry, dismissal, and unmount; an unacknowledged code view installs a `beforeunload` warning;
- `401 AUTHENTICATION_FAILED` remains a local reauthentication error, `401 AUTHENTICATION_REQUIRED` returns to login, `409 TOTP_ENROLLMENT_EXPIRED` clears candidate state, `422` binds safe `fieldErrors`, and `429` disables submit until the bounded retry time;
- each successful password/enrollment/confirmation/regeneration notice explicitly says all other sessions were revoked and the current session remains active;
- `SessionTable` marks `current`, a selected other ACTIVE session uses normal confirmation, selected current ACTIVE session uses stronger confirmation and redirects to login after `204`, and the exact mutation is POST `/{metadataId}/revoke`;
- audit cursor/filter append/reset has no mutation control, and operations status never displays secret/config values.

- [ ] **Step 2: Run focused settings tests and observe missing modules**

Run: `npm --prefix admin-web run test:unit -- src/views/settings src/components/settings src/api/settingsApi.spec.ts`

Expected: FAIL because settings contracts, API, and components are absent.

- [ ] **Step 3: Define exact security and system contracts**

```ts
// admin-web/src/types/settings.ts
export interface SessionView { id: string; status: 'ACTIVE' | 'REVOKED' | 'EXPIRED'; createdAt: string; endedAt: string | null; lastAccessMillis: number; clientSummary: string; reason: string | null; current: boolean }
export interface PasswordChangeRequest { currentPassword: string; currentTotp: string; newPassword: string }
export interface ReauthenticationRequest { currentPassword: string; currentTotp: string }
export interface TotpConfirmRequest { enrollmentId: string; newTotp: string }
export interface TotpEnrollmentResponse { enrollmentId: string; provisioningUri: string; expiresAt: string }
export interface RecoveryCodesResponse { recoveryCodes: string[] }
export interface AdminAuditItem { id: string; actorAdminId: string; action: string; targetType: string; targetId: string | null; outcome: string; traceId: string; metadata: Record<string, string>; timestamp: string }
export interface AdminAuditPage { items: AdminAuditItem[]; nextCursor: string | null }
export interface MaintenanceView { type: string; status: string; startedAt: string; finishedAt: string | null; artifactChecksum: string | null; errorCategory: string | null }
export interface OperationsStatus { databaseBackup: MaintenanceView | null; mediaBackup: MaintenanceView | null; analyticsAggregation: MaintenanceView | null; contactRetention: MaintenanceView | null; mediaCleanup: MaintenanceView | null; deployment: MaintenanceView | null; restoreDrill: MaintenanceView | null; serverTime: string }
```

`settingsApi` implements every security/system path in the table with these exact methods: `changePassword(body): Promise<void>`, `beginTotpEnrollment(body): Promise<TotpEnrollmentResponse>`, `confirmTotp(body): Promise<RecoveryCodesResponse>`, `regenerateRecoveryCodes(body): Promise<RecoveryCodesResponse>`, `listSessions(): Promise<SessionView[]>`, and `revokeSession(metadataId): Promise<void>`. It does not define a QR endpoint. `TotpEnrollmentPanel` passes the response's provisioning URI directly to browser-side `QRCode.toCanvas` from pinned `qrcode`; it never uses `toDataURL`, an external QR service, an image URL, or telemetry. All six API methods use the common CSRF-aware same-origin client, validate strict response shape, and never retry a mutation.

`getAudit({ cursor, action, outcome, from, to, limit })` returns exact `AdminAuditPage`, omits blank query values, enforces limit 1–100, and resets cursor whenever a filter changes. Credential, provisioning, and recovery responses remain only in component memory and are cleared by overwriting reactive fields before dismissal/unmount; none enters a store, URL, storage, logger, toast payload, analytics, error report, or screenshot.

`getOperations(): Promise<OperationsStatus>` calls only `GET /api/admin/system/operations`, validates the seven named maintenance keys plus `serverTime`, and sends no action. No second system-status shape or host-action API is defined.

- [ ] **Step 4: Implement complete settings behavior and verify**

- `SecuritySettings` has three isolated forms. Password change requires current password/current TOTP, a 14–128 character new password, and an equal local confirmation; only the three backend fields are sent. TOTP enrollment reauthenticates, renders the QR locally plus a guarded copyable provisioning URI, shows the exact expiry countdown, and confirms only `enrollmentId/newTotp`. Recovery regeneration reauthenticates and requires a destructive confirmation. Disable duplicate submit and clear every password/TOTP field in `finally`.
- `OneTimeRecoveryCodes` receives only `recoveryCodes`, labels them one-use, supports explicit clipboard copy and print without network/storage, and requires a “saved offline” checkbox before Dismiss. While unacknowledged it warns on in-app navigation and `beforeunload`; regardless of path, unmount clears the array. Confirmation/regeneration cannot replay the one-time response. After acknowledgement, clear codes before closing and return focus to the originating control.
- Map safe errors per form: local `401 AUTHENTICATION_FAILED` does not clear authenticated app state; global `401 AUTHENTICATION_REQUIRED` does. A `409 TOTP_ENROLLMENT_EXPIRED` immediately blanks URI/ID/canvas and offers a fresh reauthenticated start. `422` attaches `fieldErrors` to named fields/summary. `429` uses server retry seconds with a maximum displayed one-hour countdown; other failures show trace ID plus explicit retry without resending automatically.
- Every successful credential action renders “All other sessions were revoked; this session remains active,” then refreshes the session list. Do not claim this before a success response.
- `SessionTable` renders the exact descending plan-01 history fields and `current` marker. Terminal rows are read-only. Revoking another ACTIVE row requires a standard confirmation, posts its encoded metadata UUID, and reloads. Revoking the current ACTIVE row requires typing `REVOKE CURRENT SESSION`; after `204`, clear the session store and `router.replace('/admin/login')` without attempting another authenticated reload.
- `AuditTable` uses opaque keyset `nextCursor`: filter changes replace items and clear cursor; Load more appends without duplicate UUIDs. It renders exact action/target/outcome/trace/timestamp and only the allowlisted `metadata` strings supplied by plan 01, has no raw-JSON view or mutation, and handles invalid filters as local `422` errors.
- `OperationsStatus` renders exactly seven plan-07 cards—database backup, media backup, analytics aggregation, contact retention, media cleanup, deployment, and restore drill—plus `serverTime`. Each null view is an explicit “never recorded” state; otherwise show only type/status/start/finish/checksum/error category. There is no raw maintenance payload, live storage/provider switch, job queue, path/bucket/object key, exception text, credential, or web control to start backup/restore/deploy/cleanup/key rotation.
- `SettingsView` links “SEO” and “简历” to `/admin/site#seo` and `/admin/site#resumes`, composes all settings panels with independent loading/error/empty/retry state, and replaces the temporary `settings` route destination only when complete.

Run: `npm --prefix admin-web run test:unit -- src/views/settings src/components/settings src/api/settingsApi.spec.ts`

Expected: PASS for exact security bodies/responses, local-only QR generation, password clearing, TOTP expiry/confirm, saved-offline acknowledgement and one-time code clearing, current/other-session revoke behavior, audit cursor/filter immutability, SEO/resume links, all seven redacted plan-07 operation statuses, safe 401/409/422/429 handling, and explicit retry states.

- [ ] **Step 5: Commit complete settings**

```bash
git add admin-web/src/router/index.ts admin-web/src/types/settings.ts admin-web/src/api/settingsApi.ts admin-web/src/views/settings admin-web/src/components/settings
git commit -m "feat(admin): complete security and operations settings"
```

### Task 13: Add Playwright flows and run the complete admin verification gate

**Files:**
- Create: `admin-web/playwright.config.ts`
- Create: `admin-web/tests/e2e/mockAdminApi.ts`
- Create: `admin-web/tests/e2e/auth.spec.ts`
- Create: `admin-web/tests/e2e/edit-publish.spec.ts`
- Create: `admin-web/tests/e2e/conflict.spec.ts`
- Create: `admin-web/tests/e2e/operations.spec.ts`
- Create: `admin-web/tests/e2e/security-settings.spec.ts`
- Modify: `admin-web/package.json` only if the installed Playwright command differs from the Task 1 script

**Interfaces:**
- Consumes: all named routes, DTOs, and API clients from Tasks 1–12.
- Produces: deterministic browser evidence for authentication, editing/publishing, complete media/inbox/analytics/settings behavior, keyboard navigation, and protected routes.

- [ ] **Step 1: Write browser tests against a deterministic mock API**

```ts
// admin-web/playwright.config.ts
import { defineConfig } from '@playwright/test'
export default defineConfig({
  testDir: './tests/e2e',
  use: { baseURL: 'http://127.0.0.1:4174', trace: 'retain-on-failure' },
  webServer: { command: 'npm run dev -- --host 127.0.0.1 --port 4174', url: 'http://127.0.0.1:4174/admin/', reuseExistingServer: false },
})
```

```ts
// admin-web/tests/e2e/auth.spec.ts
import { expect, test } from '@playwright/test'
import { installAdminApi } from './mockAdminApi'

test.use({ trace: 'off' })

test('password then TOTP reaches the protected dashboard', async ({ page }) => {
  await installAdminApi(page)
  await page.goto('/admin/dashboard')
  await expect(page).toHaveURL(/\/admin\/login/)
  await page.getByLabel('用户名').fill('admin')
  await page.getByLabel('密码').fill('correct horse battery staple')
  await page.getByRole('button', { name: '继续' }).click()
  await page.getByLabel('六位验证码').fill('123456')
  await page.getByRole('button', { name: '验证并登录' }).click()
  await expect(page.getByRole('heading', { name: '仪表盘' })).toBeVisible()
})
```

`mockAdminApi.ts` maintains in-memory auth phase, exact SITE/project workspace DTO versions, publication/catalog versions, immutable revision arrays, synthetic messages, analytics, plan-01 `SessionView` rows (including `current`), opaque-cursor `AdminAuditPage`, and system status. Auth mocks the exact plan-01 `/csrf`, `/password`, `/second-factor`, `/me`, and `/logout` behavior. Security settings validate the exact four request DTOs, return `204`, `TotpEnrollmentResponse`, or `RecoveryCodesResponse` as specified, mark every non-current session revoked after each success, and script local `401 AUTHENTICATION_FAILED`, global `401 AUTHENTICATION_REQUIRED`, enrollment `409`, validation `422`, and rate-limit `429`. Session revoke accepts only POST `/{metadataId}/revoke`; current revoke flips the mock to anonymous.

The mock checks every non-GET request for `X-XSRF-TOKEN` and returns `403` when missing; returns one scripted `409` in `conflict.spec.ts`; and returns `{ token: 'ticket-1', expiresAt: '2026-07-14T12:10:00Z' }` from `/api/admin/publishing/preview-tokens`, then an exact preview snapshot from `/api/admin/publishing/previews/ticket-1`. Message fixtures are synthetic and contain no real PII. `auth.spec.ts` and `security-settings.spec.ts` set `test.use({ trace: 'off' })`, never attach screenshots/HTML, and use synthetic credentials/codes because Playwright trace network records would otherwise retain request or one-time response bodies.

- [ ] **Step 2: Run the browser tests and observe failures before missing details are corrected**

Run: `npm --prefix admin-web run test:e2e -- auth.spec.ts edit-publish.spec.ts conflict.spec.ts operations.spec.ts security-settings.spec.ts`

Expected: initial FAIL pinpoints any incomplete labels, routes, CSRF behavior, or editor wiring; do not relax assertions.

- [ ] **Step 3: Complete browser-visible details required by the tests**

Make the smallest changes in existing admin files so these exact flows pass:

1. anonymous deep link preserves a safe redirect through login/TOTP;
2. locale tabs edit both SITE and project text without discarding inactive locale values;
3. 15-second autosave sends the loaded version and updates the saved indicator;
4. a scripted `409` stops autosave and exposes reload-server-version;
5. preview opens the client-constructed short-lived token URL and publish carries current workspace, project-publication, and catalog versions;
6. keyboard-only navigation reaches shell links, editor controls, media dialog, conflict action, and publish confirmation;
7. media upload reaches PROCESSING, metadata saves, and referenced archive shows safe `409`;
8. inbox detail is escaped, status uses its version, retry/delete confirm, and analytics show definitions/delay;
9. password change validates local confirmation, sends the exact three keys, clears secrets, states that other sessions were revoked/current retained, and a local reauthentication 401 does not log out;
10. TOTP enrollment makes no request containing `otpauth:`, renders the returned URI only through the local canvas/manual display until expiry, confirms exact ID/new code, and exposes replacement recovery codes once;
11. recovery-code regeneration requires destructive confirmation; both code-producing flows block Dismiss until offline-save acknowledgement, warn before accidental departure, then erase codes/URI/canvas with no Web Storage entry;
12. another ACTIVE session uses normal confirmed POST revoke/reload; current ACTIVE session is visibly marked, requires `REVOKE CURRENT SESSION`, and goes directly to login after `204`;
13. audit filters reset opaque cursor, Load more appends, audit remains immutable, and all seven `/api/admin/system/operations` maintenance cards render null/success/failure states without config/secrets or host-action buttons;
14. a source/route assertion proves no production route still imports `FeatureShellView` and every approved destination renders its complete owning view;
15. logout invalidates the browser session and the next protected navigation returns to login.

- [ ] **Step 4: Run the complete admin quality gate**

Run: `npm --prefix admin-web run test:unit`

Expected: all Vitest suites pass with zero unhandled promise rejection.

Run: `npm --prefix admin-web run test:e2e`

Expected: all Chromium Playwright tests pass; sensitive auth/security specs produce no trace artifact, and non-sensitive traces contain no password, TOTP/provisioning/recovery value, session identifier, or message body.

Run: `npm --prefix admin-web run type-check && npm --prefix admin-web run build`

Expected: exit 0; `admin-web/dist/index.html` uses `/admin/assets/*`, and no public-site or backend source changed.

Deployment handoff: copy the complete `admin-web/dist/` contents without reshaping into `/opt/portfolio/releases/{releaseId}/admin/`, where `releaseId` is the same Git-plus-public-manifest value injected as `PORTFOLIO_RELEASE_ID` for that release; atomically point `/opt/portfolio/current-admin` to that directory. Do not copy admin assets into shared `/opt/portfolio/assets/`, do not hand an admin manifest to Spring, and do not construct a second admin-only release identifier.

- [ ] **Step 5: Commit the verified admin application**

```bash
git add admin-web/playwright.config.ts admin-web/tests admin-web/src admin-web/package.json admin-web/package-lock.json
git commit -m "test(admin): cover secure edit and publish flows"
```
