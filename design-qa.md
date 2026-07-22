# Homepage Project-First Redesign — Design QA

## Evidence

- Source visual truth: `C:\Users\YYchainsaw\IdeaProjects\personal-portfolio\.worktrees\portfolio-platform\docs\design\homepage-project-first-reference.png`
- Browser implementation: `C:\Users\YYchainsaw\IdeaProjects\personal-portfolio\.worktrees\portfolio-platform\docs\design\homepage-project-first-desktop-final.png`
- Mobile implementation: `C:\Users\YYchainsaw\IdeaProjects\personal-portfolio\.worktrees\portfolio-platform\docs\design\homepage-project-first-mobile-final.png`
- Full-view comparison: `C:\Users\YYchainsaw\IdeaProjects\personal-portfolio\.worktrees\portfolio-platform\docs\design\homepage-project-first-comparison-final.png`
- Focused project-reel comparison: `C:\Users\YYchainsaw\IdeaProjects\personal-portfolio\.worktrees\portfolio-platform\docs\design\homepage-project-reel-comparison-final.png`
- Route: `http://127.0.0.1:5176/zh-CN`
- State: Chinese locale, first project selected, desktop and mobile dark theme. The analytics preference control was hidden only in the comparison screenshots so it would not obscure the design; it remains functional in the product.

## Normalization

- Source pixels: 1487 × 1058.
- Desktop implementation pixels: 1440 × 1024.
- CSS viewport: 1440 × 1024; device scale factor 1.
- Mobile implementation pixels and CSS viewport: 390 × 844; device scale factor 1.
- For the comparison artifact, the source was bicubic-normalized to 1440 × 1024. The implementation remained at its native 1440 × 1024 capture.
- The focused comparison uses the same normalized 1440 × 1024 sources and the same 740–1024 px project-reel region.

## Findings

- No actionable P0, P1, or P2 differences remain.
- Typography: the final build preserves the modern sans-serif hierarchy, compact navigation, large project title, readable supporting copy, and restrained microcopy of the target. Manrope weights and line-height remain legible at desktop and mobile sizes.
- Spacing and layout rhythm: the two-column project stage, 36/64 copy-to-image balance, header height, reel placement, and three-entry rhythm match the intended composition. Mobile collapses to image-first without horizontal overflow.
- Colors and tokens: charcoal surfaces, warm off-white text, muted metadata, and restrained cyan state accents match the target. Automated WCAG checks pass after increasing muted-label contrast.
- Image quality and fidelity: all visible showcase art is real raster imagery. The final lead image intentionally differs from the early architectural render by showing an explicit UE development state—character, interactive doorway, trigger volume, transform gizmo, and navigation debugging—as requested. Full-size WebP assets remain 1672 × 941, with dedicated 720 × 405 thumbnails.
- Copy and content: the first entry uses published project data; the two future entries are explicitly labeled as learning tracks rather than falsely presented as completed projects. Project metadata is described as tags and status instead of inventing stack/type fields.
- Interaction semantics: the reel is a single-click tab interface with arrow-key navigation, selected-state semantics, and a separate main-stage link for opening the real case study. The mobile menu traps focus, closes on Escape, restores focus, and closes when returning to desktop width.

## Comparison History

### Pass 1 — blocked

- P2: the published tree-island cover was visually generic and did not communicate Unreal Engine development.
- P2: the project-reel note and tag text failed the 4.5:1 contrast requirement.
- P2: a non-active real project required two clicks because the reel item looked like a link but first acted as a selector.
- P2: the three-column implementation had no graceful expansion path and hid thumbnails at the tablet breakpoint.
- Evidence: `docs/design/homepage-project-first-comparison.png` and `docs/design/homepage-project-first-desktop.png`.

Fixes made:

- Generated and installed three purposeful UE development visuals, including a dedicated scene-and-interaction lead image.
- Increased muted text contrast and minimum microcopy sizing.
- Rebuilt the reel as an accessible tab selector; kept detail navigation in the main-stage CTA.
- Added horizontally expandable project navigation and retained thumbnails through the tablet breakpoint.
- Converted full-size assets and thumbnails to high-quality WebP, reducing their delivered sizes to approximately 104–363 KB and 21–65 KB respectively.

### Pass 2 — passed

- The final full-view comparison retains the target hierarchy and composition while using more technically credible UE imagery.
- The focused reel comparison confirms number, title, tags, status, thumbnail, active outline, and three-entry rhythm remain intact.
- Desktop and mobile captures show no clipping, broken crop, misplaced controls, or horizontal overflow.
- Evidence: `docs/design/homepage-project-first-comparison-final.png` and `docs/design/homepage-project-reel-comparison-final.png`.

## Browser and Interaction Verification

- Project tabs found: 3.
- Mouse selection changed the main visual from `ue-scene-interaction-study.webp` to `ue-gameplay-prototype.webp`.
- Arrow-right keyboard selection moved to the third project.
- Mobile menu opened, Escape closed it, and focus returned to the menu button.
- No horizontal overflow at 390 px.
- Browser console errors: 0.
- Page errors: 0.
- Failed requests: 0.
- Targeted responsive/accessibility Playwright suite: 4/4 passed.
- Full Playwright regression: 48/48 passed (24 desktop, 24 mobile).
- Unit suite: 99/99 passed.

## Follow-up Polish

- P3: when a future backend content model is available, move homepage-curated showcase art and learning-track copy into the admin CMS so they can be changed without a frontend release.

## Implementation Checklist

- [x] Project-first responsive layout.
- [x] Earlier editorial project-navigation structure restored and modernized.
- [x] Purposeful UE development imagery installed.
- [x] Single-click and keyboard project switching.
- [x] Future project expansion supported.
- [x] Desktop/mobile visual comparison complete.
- [x] Accessibility, console, network, unit, lint, and production-build checks complete.

final result: passed
