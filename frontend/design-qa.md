# Design QA

**Findings**

- No actionable P0, P1, or P2 issues remain after the final comparison and browser pass.
- [P3] The contact email is intentionally still `your-email@example.com`. It is rendered as a non-interactive, clearly labelled placeholder until a real address is supplied.
- [P3] The four stock images are credited and explicitly labelled as visual concepts/placeholders. Replace them with Jiaxuan's own Unreal Engine captures as projects become available.

**Comparison Target**

- Source visual truth: `artifacts/design-qa/source-portfolio-cosmic.png`
- Rendered implementation: `artifacts/design-qa/zh-desktop-hero.png`
- Viewport: `1440 x 1024` CSS pixels, Chrome, device scale factor 2
- State: default Chinese locale, light theme, desktop navigation visible, menu closed, reveal motion settled
- The source remains the structural reference for the compact floating navigation, oversized identity, short positioning statement, paired actions, and image-led hero. The light palette, bilingual controls, game-development content, project grid, and roadmap are intentional changes required by the user's redesign brief.

**Full-view Comparison Evidence**

- Side-by-side comparison: `artifacts/design-qa/redesign-full-comparison.png`
- Both views preserve a clear navigation-to-identity-to-positioning-to-action hierarchy. The implementation translates the source's cinematic single-column composition into a brighter two-column format so the high-resolution 3D image and game-development positioning can share the first screen.
- The source and implementation are normalized into equal panels with the same top crop. The palette difference is intentional; proportions, hierarchy, density, and interaction prominence are the fidelity targets.

**Focused Region Comparison Evidence**

- Navigation and hero crop: `artifacts/design-qa/redesign-focused-comparison.png`
- The focused comparison verifies compact navigation, an immediate personal name, a concise role line, a large positioning statement, and visible primary/secondary actions.
- The implementation adds a persistent `中 / EN` segmented control without displacing navigation or contact actions.

**Required Fidelity Surfaces**

- Fonts and typography: passed. Manrope is bundled for Latin text; Chinese uses the explicit Microsoft YaHei / PingFang SC / Noto Sans SC fallback stack. Display, body, metadata, and control weights remain legible in both locales without clipped or truncated headings.
- Spacing and layout rhythm: passed. The hero, fact grid, expandable project grid, roadmap rows, and contact section use consistent gutters, borders, radii, and vertical intervals. Desktop `1440px` and mobile `390px` report zero horizontal overflow.
- Colors and visual tokens: passed. The light paper, white surface, cobalt accent, slate ink, semantic green, and warm placeholder marker are applied consistently. Small contact text is full white on cobalt, and image overlays use an 88% dark surface for stable contrast over bright photography.
- Image quality and asset fidelity: passed. All four local raster assets are high-resolution, correctly cropped with `object-fit: cover`, fully fill their measured slots, and retain visible photographer credits. No visible imagery or icons are replaced by CSS art, custom SVG, emoji, or placeholder drawings.
- Copy and content: passed. Chinese and English cover Jiaxuan Yi's identity, Jiangxi Normal University status, Unreal Engine learning focus, honest work-in-progress project states, and a four-stage roadmap. Concept imagery and the missing email are disclosed instead of being presented as finished personal work.
- Icons: passed. All interface icons use the Phosphor family with consistent bold stroke weight, size, and alignment.

**Interaction and Accessibility Checks**

- Browser results: `artifacts/design-qa/browser-results.json`
- Final browser pass: 14 / 14 checks passed.
- Primary interactions tested: default Chinese locale, switch to English, reload persistence, switch back to Chinese, desktop anchor navigation, mobile menu open/close, Escape close, focus restoration, target-heading focus after mobile navigation, and contact/work/roadmap anchors.
- Locale state: `html[lang]`, `aria-pressed`, visible copy, and `localStorage` stay synchronized.
- Mobile menu: focus moves inside the dialog, Tab is trapped, body scrolling locks, Escape restores the toggle focus, and choosing Work focuses `#work-title` after navigation.
- Responsive evidence: `zh-desktop-full.png`, `zh-desktop-work.png`, `zh-desktop-roadmap.png`, `en-desktop-hero.png`, `zh-mobile-hero.png`, `zh-mobile-menu.png`, `zh-mobile-work.png`, and `zh-mobile-contact.png` under `artifacts/design-qa/`.
- Console errors: none. Page errors: none.
- Reduced motion: reveal, image, button, navigation, and full-screen menu transitions are disabled; content remains visible.

**Comparison History**

1. Initial light redesign comparison found a P2 hero-media sizing defect: the intrinsic 16:9 image did not resolve against a `min-height` container and left a large empty lower area. Fixed by absolutely positioning the hero image to fill its measured figure. Post-fix evidence: `zh-desktop-hero.png`.
2. Responsive review found the same intrinsic-height behavior in project media, a P1 because it produced large empty blocks at `390px` and affected tablet widths. Fixed with absolute inset positioning and `object-fit: cover` for every project image. Post-fix evidence: `zh-mobile-work.png` and `zh-desktop-work.png`.
3. Accessibility review found P1 contrast failures in translucent image captions and small contact/footer text. Image overlays were raised to 88% dark opacity and contact text to solid white. Post-fix evidence: `zh-desktop-hero.png`, `zh-desktop-work.png`, and `zh-mobile-contact.png`.
4. Interaction review found P2 polish gaps: mobile anchor navigation could drop focus to the body, one project tag label remained English in Chinese mode, the full-screen menu transition remained active under reduced motion, and desktop navigation text was not vertically centred. Focus now lands on the destination heading, the label is localized, reduced-motion coverage is complete, and links use flex centring. The final browser pass confirms all four fixes.
5. Final comparison and browser pass found no remaining P0/P1/P2 mismatch, overflow, interaction failure, console error, or page error.

**Open Questions**

- A real email address and Jiaxuan's own Unreal Engine screenshots are still needed for production publication. Their absence is disclosed and does not block this local prototype.

**Implementation Checklist**

- [x] Bright, simple, bilingual responsive layout.
- [x] Right-side Chinese/English switch with persistence and metadata updates.
- [x] Honest game-development positioning and expandable project structure.
- [x] Four-stage development roadmap through graduation.
- [x] High-resolution credited imagery with explicit concept labels.
- [x] Keyboard, focus, contrast, reduced-motion, console, overflow, type, lint, and production-build checks.

**Follow-up Polish**

- Replace the placeholder email and stock images with real contact information, UE captures, downloadable builds, GitHub links, and short case-study videos.

final result: passed
