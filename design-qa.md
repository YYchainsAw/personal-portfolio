# Design QA — unified public portfolio

## Scope

- Public routes: home, project detail, privacy, 404, loading/error states, analytics consent, and server-rendered first paint.
- Excluded by request: `/admin/**` and the administrator application.
- Source of truth: the approved dark, project-first home experience in `frontend/artifacts/design-qa-home-desktop.png` and `frontend/artifacts/design-qa-home-mobile.png`.

## Evidence

| Surface | State | CSS viewport | Screenshot |
| --- | --- | ---: | --- |
| Home source | published content, desktop | 1440 × 1000 | `frontend/artifacts/design-qa-home-desktop.png` |
| Home source | published content, mobile | 390 × 844 | `frontend/artifacts/design-qa-home-mobile.png` |
| Project detail | published `ue-environment-study`, desktop viewport | 1440 × 1000 | `frontend/artifacts/design-qa-project-viewport-final.png` |
| Project detail | published `ue-environment-study`, mobile viewport | 390 × 844 | `frontend/artifacts/design-qa-project-mobile-viewport-final.png` |
| Project detail | English locale, desktop viewport | 1440 × 1000 | `frontend/artifacts/design-qa-project-en-final.png` |
| Project detail | complete page | 1440 × 2607 | `frontend/artifacts/design-qa-project-full-final.png` |
| Privacy | current published content, desktop complete page | 1440 × 1733 | `frontend/artifacts/design-qa-privacy-desktop.png` |
| Privacy | current published content, mobile complete page | 390 × 1706 | `frontend/artifacts/design-qa-privacy-mobile.png` |
| 404 | unknown public route, desktop complete page | 1440 × 1421 | `frontend/artifacts/design-qa-404-desktop.png` |
| Mobile menu | open, narrow viewport | 320 × 720 | `frontend/artifacts/design-qa-mobile-menu-320.png` |

Screenshots were captured in Chromium at device scale factor 1. The desktop and mobile source/implementation pairs are assembled in `frontend/artifacts/design-qa-comparison-desktop.png` (2888 × 1044) and `frontend/artifacts/design-qa-comparison-mobile.png` (788 × 888).

## Fidelity review

- Typography: consistent Manrope hierarchy, compact uppercase micro-labels, strong project titles, and no remaining oversized light-theme serif treatment.
- Color and surfaces: common near-black canvas, cool white type, cyan state/accent color, restrained blue-grey rules, and border-led panels.
- Layout: the project page preserves the source system's split UE hero, project taxonomy, numbered editorial sections, image-first evidence, and compact navigation while expanding into a full case-study hierarchy.
- Media: the selected UE environment case uses the matching high-resolution, bundled Unreal Engine scene instead of the former generic plant visual.
- Responsive behavior: desktop, 390 px mobile, and 320 px narrow-mobile captures have no horizontal overflow. The mobile menu is opaque after its transition, traps focus, locks scroll, closes with Escape, and restores focus.
- Public-only scope: shared shell and fallback styling are rooted in the public application. The administrator application is unchanged.

## First-pass findings and fixes

1. Empty skills and narrative areas produced large blank regions. Empty taxonomy rows now collapse and the narrative renders a deliberate ruled “in progress” record.
2. The first version inferred the UE treatment from catalog order. It now selects the UE case by stable slug and makes the case badge explicit.
3. The 320 px header could crowd brand and actions. The narrow breakpoint now prioritizes navigation actions and avoids overflow.
4. Mobile-menu background content remained discoverable to assistive technology. The underlying header is now inert and hidden from the accessibility tree while the dialog is open.
5. Unknown localized URLs could receive an API-shaped response. The public controller now returns a themed HTML document with HTTP 404, `no-store`, and `noindex`, while API/admin routes stay outside the catch-all.
6. Server-rendered markup could briefly show the previous light presentation before hydration. Public templates now carry scoped, transient dark fallback styles that disappear when Vue replaces their children.
7. Final review found that direct 404 first paint was not yet covered by the fallback styles and that the UE case's SSR cover could differ from the client cover. The 404 now has its own transient dark shell, and the renderer resolves the same hashed 1672 × 941 UE WebP used by Vue for both first paint and `og:image`.

## Functional and content notes

- All nine project content-block types remain supported, including analytics event handling and URL-safety rules.
- Locale switching preserves project and privacy destinations.
- The English project route was checked independently: localized title/metadata rendered, horizontal overflow was 0 px, and browser console/page errors were 0.
- The currently published privacy title/body contain the literal value `test`; the layout is valid, but that copy should be replaced in the CMS before treating the privacy notice as final editorial content.
- Because the source and implementation are different route states, comparison is system-level rather than pixel-for-pixel cloning.

## Verification

- Frontend production build: passed (1610 modules).
- Frontend unit and SSR contract tests: 107/107 passed.
- Public Playwright regression: 52/52 passed (26 desktop, 26 iPhone 13).
- Oxlint and ESLint: passed with no diagnostics.
- Java 17 backend reactor: 1951 tests, 0 failures, 0 errors, 14 skipped.
- Public catch-all scope: localized public paths only; admin, API, actuator, and root assets remain outside it.
- Final patch whitespace check: passed.

## Final result

passed
