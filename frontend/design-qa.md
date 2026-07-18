# Public site design QA

Status: **passed** on 2026-07-19.

This document supersedes the historical static-prototype QA. The current site is driven by the published public API, consumes the server-rendered bootstrap payload once, and uses the locale in the URL (`/zh-CN` or `/en`) as the navigation authority.

## Reviewed surfaces

- Bright bilingual portfolio shell with a compact floating header and top-right language switch.
- Responsive hero, about/facts, skills, expandable project catalog, four-stage roadmap, contact form, privacy page, project details, empty/error/retry states, and not-found page.
- Desktop viewport: 1440 × 1000 CSS pixels, Chromium.
- Mobile viewport: iPhone 13 profile, WebKit.
- Fresh full-page screenshots were generated from the current application and inspected locally. They are disposable QA output, not committed release evidence.

The desktop and mobile renders preserve a clear identity → positioning → work → roadmap → contact hierarchy. High-resolution media fills its intended slots without distortion, project cards leave an explicit expansion slot, the contact form remains readable on the cobalt footer, and no clipped or horizontally overflowing content was observed.

## Executable evidence

Run from `frontend/`:

```bash
npm run test:unit
npm run type-check
npm run build-only
npm run test:e2e
```

Final results:

- Unit tests: 27 files, 99 tests passed.
- Browser tests: 48/48 passed with one worker (24 desktop Chromium and 24 mobile WebKit).
- Production build: passed.
- Exact Node 22.18.0 / npm 10.9.3 clean install: passed.
- Full and production-only npm vulnerability audits: 0 findings.
- CycloneDX 1.6 generation and schema validation: passed without skipped validation.

The browser suite covers exact bilingual routes and SEO metadata, first-load bootstrap reuse, stale-request cancellation, project blocks, privacy copy, safe error states, keyboard focus, skip navigation, mobile focus trapping, reduced motion, full-page Axe checks, computed contact contrast, horizontal overflow, contact CSRF behavior, analytics opt-in/withdrawal/DNT, and unexpected console/page errors.

## Content integrity

- The portfolio identifies Jiaxuan Yi / 易嘉轩, Jiangxi Normal University, game development, and the current Unreal Engine learning path.
- Concept stock imagery is visibly credited and remains documented as replaceable content rather than personal UE work.
- `your-email@example.com` is intentionally a publication-blocking placeholder. A real address must be supplied before the production content bundle is approved.

## Remaining content upgrades

- Replace concept imagery with original Unreal Engine screenshots, short gameplay clips, and downloadable builds as projects mature.
- Add real GitHub/demo links, case-study metrics, and postmortems to each published project.
- Supply the final contact email and production privacy/contact details.

Final result: **passed**.
