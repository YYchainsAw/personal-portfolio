<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch, type CSSProperties } from 'vue'
import {
  PhArrowDown as ArrowDown,
  PhArrowUpRight as ArrowUpRight,
  PhCheck as Check,
  PhList as List,
  PhPlus as Plus,
  PhX as X,
} from '@phosphor-icons/vue'
import { useLocale, type Locale } from '@/composables/useLocale'
import {
  getCopy,
  heroAsset,
  identity,
  projectAssets,
  type ProjectAsset,
  type ProjectCopy,
} from '@/data/portfolio'

type ProjectView = ProjectCopy & { asset: ProjectAsset }

const { locale, setLocale } = useLocale()
const copy = computed(() => getCopy(locale.value))
const assetById = new Map(projectAssets.map((asset) => [asset.id, asset]))
const projects = computed<ProjectView[]>(() =>
  copy.value.projects.flatMap((project) => {
    const asset = assetById.get(project.id)
    return asset ? [{ ...project, asset }] : []
  }),
)

const menuOpen = ref(false)
const menuToggle = ref<HTMLButtonElement | null>(null)
const mobileMenu = ref<HTMLElement | null>(null)
const heroVisual = ref<HTMLElement | null>(null)
const year = new Date().getFullYear()
const hasRealEmail = computed(() => !identity.email.endsWith('@example.com'))

let revealObserver: IntersectionObserver | undefined
let pointerFrame = 0
let menuFocusTimer: ReturnType<typeof setTimeout> | undefined

const mailto = computed(() => {
  const subject =
    locale.value === 'zh-CN' ? '作品集联系 / 游戏开发交流' : 'Portfolio enquiry / Game development'
  const body = locale.value === 'zh-CN' ? '你好，嘉轩：' : 'Hello Jiaxuan,'
  return `mailto:${identity.email}?subject=${encodeURIComponent(subject)}&body=${encodeURIComponent(body)}`
})

const heroVisualStyle = computed(
  () => ({ '--hero-position': heroAsset.objectPosition }) as CSSProperties,
)

const setLanguage = (nextLocale: Locale) => {
  setLocale(nextLocale)
}

const ensureMeta = (selector: string, attribute: string, value: string) => {
  const element = document.querySelector<HTMLMetaElement>(selector)
  element?.setAttribute(attribute, value)
}

watch(
  copy,
  (currentCopy) => {
    if (typeof document === 'undefined') return
    document.title = currentCopy.seo.title
    ensureMeta('meta[name="description"]', 'content', currentCopy.seo.description)
    ensureMeta('meta[property="og:title"]', 'content', currentCopy.seo.title)
    ensureMeta('meta[property="og:description"]', 'content', currentCopy.seo.description)
  },
  { immediate: true },
)

const moveHeroVisual = (event: PointerEvent) => {
  if (!heroVisual.value || event.pointerType === 'touch') return
  if (window.matchMedia('(prefers-reduced-motion: reduce)').matches) return

  const rect = heroVisual.value.getBoundingClientRect()
  const x = (event.clientX - rect.left) / rect.width - 0.5
  const y = (event.clientY - rect.top) / rect.height - 0.5

  cancelAnimationFrame(pointerFrame)
  pointerFrame = requestAnimationFrame(() => {
    heroVisual.value?.style.setProperty('--media-x', `${x * 10}px`)
    heroVisual.value?.style.setProperty('--media-y', `${y * 8}px`)
  })
}

const resetHeroVisual = () => {
  heroVisual.value?.style.setProperty('--media-x', '0px')
  heroVisual.value?.style.setProperty('--media-y', '0px')
}

const closeMenu = (restoreFocus = false, focusTarget?: string) => {
  menuOpen.value = false
  if (menuFocusTimer) clearTimeout(menuFocusTimer)

  if (focusTarget) {
    const delay = window.matchMedia('(prefers-reduced-motion: reduce)').matches ? 0 : 430
    menuFocusTimer = setTimeout(() => {
      document.querySelector<HTMLElement>(focusTarget)?.focus({ preventScroll: true })
    }, delay)
  } else if (restoreFocus) {
    nextTick(() => menuToggle.value?.focus())
  }
}

const focusableMenuElements = () =>
  Array.from(
    mobileMenu.value?.querySelectorAll<HTMLElement>(
      'a[href], button:not([disabled]), [tabindex]:not([tabindex="-1"])',
    ) ?? [],
  )

const handleKeydown = (event: KeyboardEvent) => {
  if (!menuOpen.value) return

  if (event.key === 'Escape') {
    closeMenu(true)
    return
  }

  if (event.key !== 'Tab') return
  const focusable = focusableMenuElements()
  if (!focusable.length) return
  const first = focusable[0]
  const last = focusable[focusable.length - 1]
  if (!first || !last) return

  if (event.shiftKey && document.activeElement === first) {
    event.preventDefault()
    last.focus()
  } else if (!event.shiftKey && document.activeElement === last) {
    event.preventDefault()
    first.focus()
  }
}

watch(menuOpen, async (isOpen) => {
  document.body.style.overflow = isOpen ? 'hidden' : ''
  if (isOpen) {
    await nextTick()
    focusableMenuElements()[0]?.focus()
  }
})

onMounted(() => {
  revealObserver = new IntersectionObserver(
    (entries) => {
      entries.forEach((entry) => {
        if (!entry.isIntersecting) return
        entry.target.classList.add('is-visible')
        revealObserver?.unobserve(entry.target)
      })
    },
    { rootMargin: '0px 0px -7% 0px', threshold: 0.08 },
  )

  document.querySelectorAll('[data-reveal]').forEach((element) => revealObserver?.observe(element))
  window.addEventListener('keydown', handleKeydown)
})

onBeforeUnmount(() => {
  revealObserver?.disconnect()
  cancelAnimationFrame(pointerFrame)
  if (menuFocusTimer) clearTimeout(menuFocusTimer)
  window.removeEventListener('keydown', handleKeydown)
  document.body.style.overflow = ''
})
</script>

<template>
  <header class="site-header">
    <a class="brand" href="#top" :aria-label="copy.a11y.backToTop">
      <span class="brand__mark">{{ identity.monogram }}</span>
      <span class="brand__name">
        <strong>{{ identity.nameZh }}</strong>
        <small>{{ identity.nameEn }}</small>
      </span>
    </a>

    <nav class="desktop-nav" :aria-label="copy.a11y.primaryNav">
      <a href="#about">{{ copy.nav.about }}</a>
      <a href="#work">{{ copy.nav.work }}</a>
      <a href="#roadmap">{{ copy.nav.roadmap }}</a>
    </nav>

    <div class="nav-actions">
      <div class="language-switch" role="group" :aria-label="copy.a11y.language">
        <button
          type="button"
          lang="zh-CN"
          aria-label="中文"
          :aria-pressed="locale === 'zh-CN'"
          @click="setLanguage('zh-CN')"
        >
          中
        </button>
        <button
          type="button"
          lang="en"
          aria-label="English"
          :aria-pressed="locale === 'en'"
          @click="setLanguage('en')"
        >
          EN
        </button>
      </div>
      <a class="nav-contact" href="#contact">
        {{ copy.nav.contact }}
        <ArrowUpRight :size="15" weight="bold" aria-hidden="true" />
      </a>
      <button
        ref="menuToggle"
        class="menu-toggle"
        type="button"
        :aria-expanded="menuOpen"
        aria-controls="mobile-menu"
        :aria-label="menuOpen ? copy.a11y.closeMenu : copy.a11y.openMenu"
        @click="menuOpen = !menuOpen"
      >
        <X v-if="menuOpen" :size="21" weight="bold" aria-hidden="true" />
        <List v-else :size="21" weight="bold" aria-hidden="true" />
      </button>
    </div>
  </header>

  <Transition name="menu">
    <div
      v-if="menuOpen"
      id="mobile-menu"
      ref="mobileMenu"
      class="mobile-menu"
      role="dialog"
      aria-modal="true"
      :aria-label="copy.a11y.mobileNav"
    >
      <div class="mobile-menu__top">
        <span class="brand__mark">{{ identity.monogram }}</span>
        <div
          class="language-switch language-switch--menu"
          role="group"
          :aria-label="copy.a11y.language"
        >
          <button
            type="button"
            lang="zh-CN"
            aria-label="中文"
            :aria-pressed="locale === 'zh-CN'"
            @click="setLanguage('zh-CN')"
          >
            中
          </button>
          <button
            type="button"
            lang="en"
            aria-label="English"
            :aria-pressed="locale === 'en'"
            @click="setLanguage('en')"
          >
            EN
          </button>
        </div>
        <button
          class="mobile-menu__close"
          type="button"
          :aria-label="copy.a11y.closeMenu"
          @click="closeMenu(true)"
        >
          <X :size="22" weight="bold" aria-hidden="true" />
        </button>
      </div>
      <nav :aria-label="copy.a11y.mobileNav">
        <a href="#about" @click="closeMenu(false, '#about-title')"
          ><span>01</span>{{ copy.nav.about }}</a
        >
        <a href="#work" @click="closeMenu(false, '#work-title')"
          ><span>02</span>{{ copy.nav.work }}</a
        >
        <a href="#roadmap" @click="closeMenu(false, '#roadmap-title')"
          ><span>03</span>{{ copy.nav.roadmap }}</a
        >
        <a href="#contact" @click="closeMenu(false, '#contact-title')"
          ><span>04</span>{{ copy.nav.contact }}</a
        >
      </nav>
      <p>{{ copy.hero.role }}</p>
    </div>
  </Transition>

  <main id="main-content">
    <section id="top" class="hero" aria-labelledby="hero-title">
      <div class="hero__copy">
        <p class="eyebrow" data-reveal><span aria-hidden="true"></span>{{ copy.hero.eyebrow }}</p>
        <div class="hero__identity" data-reveal>
          <p>{{ copy.hero.secondaryName }}</p>
          <h1 id="hero-title">{{ copy.hero.displayName }}</h1>
        </div>
        <p class="hero__role" data-reveal>{{ copy.hero.role }}</p>
        <h2 data-reveal>{{ copy.hero.headline }}</h2>
        <p class="hero__intro" data-reveal>{{ copy.hero.introduction }}</p>
        <div class="hero__actions" data-reveal>
          <a class="button button--primary" href="#work">
            {{ copy.hero.primaryCta }}
            <ArrowDown :size="16" weight="bold" aria-hidden="true" />
          </a>
          <a class="button button--secondary" href="#roadmap">{{ copy.hero.secondaryCta }}</a>
        </div>
        <p class="availability" data-reveal>
          <span aria-hidden="true"></span>{{ copy.hero.availability }}
        </p>
      </div>

      <figure
        ref="heroVisual"
        class="hero__visual"
        :style="heroVisualStyle"
        data-reveal
        @pointermove="moveHeroVisual"
        @pointerleave="resetHeroVisual"
      >
        <img
          :src="heroAsset.image"
          :alt="heroAsset.alt[locale]"
          fetchpriority="high"
          width="1600"
          height="900"
        />
        <figcaption>
          <span>{{ copy.hero.visualLabel }}</span>
          <strong>{{ copy.hero.stageLabel }}</strong>
        </figcaption>
        <a class="image-credit" :href="heroAsset.sourceUrl" target="_blank" rel="noreferrer">
          Photo / {{ heroAsset.credit }}
        </a>
      </figure>
    </section>

    <section id="about" class="section about" aria-labelledby="about-title">
      <header class="section-heading" data-reveal>
        <p>{{ copy.about.label }}</p>
        <h2 id="about-title" tabindex="-1">{{ copy.about.title }}</h2>
        <p>{{ copy.about.statement }}</p>
      </header>

      <div class="fact-grid">
        <article v-for="fact in copy.about.facts" :key="fact.label" data-reveal>
          <p>{{ fact.label }}</p>
          <strong>{{ fact.value }}</strong>
        </article>
      </div>

      <div class="focus-panel" data-reveal>
        <div class="focus-panel__copy">
          <p class="micro-label">{{ copy.about.focusLabel }}</p>
          <h3>{{ copy.about.focusTitle }}</h3>
          <p>{{ copy.about.focusIntro }}</p>
        </div>
        <ul class="skill-list">
          <li v-for="skill in copy.about.skills" :key="skill.name">
            <span>{{ skill.name }}</span>
            <small>{{ skill.status }}</small>
          </li>
        </ul>
      </div>
    </section>

    <section id="work" class="section work" aria-labelledby="work-title">
      <header class="section-heading section-heading--split" data-reveal>
        <div>
          <p>{{ copy.work.label }}</p>
          <h2 id="work-title" tabindex="-1">{{ copy.work.title }}</h2>
        </div>
        <p>{{ copy.work.introduction }}</p>
      </header>

      <div class="project-grid">
        <article
          v-for="(project, index) in projects"
          :key="project.id"
          class="project-card"
          :class="{ 'project-card--wide': project.asset.layout === 'wide' }"
          data-reveal
        >
          <figure class="project-card__visual">
            <img
              :src="project.asset.image"
              :alt="project.asset.alt[locale]"
              :loading="index === 0 ? 'eager' : 'lazy'"
              width="1600"
              height="1000"
              :style="{ objectPosition: project.asset.objectPosition }"
            />
            <div class="project-card__visual-top">
              <span>{{ project.number }}</span>
              <span>{{ project.status }}</span>
            </div>
            <a
              class="image-credit"
              :href="project.asset.sourceUrl"
              target="_blank"
              rel="noreferrer"
            >
              Photo / {{ project.asset.credit }}
            </a>
          </figure>
          <div class="project-card__body">
            <p class="micro-label">{{ project.eyebrow }}</p>
            <h3>{{ project.title }}</h3>
            <p>{{ project.summary }}</p>
            <ul class="tag-list" :aria-label="copy.a11y.projectTags">
              <li v-for="tag in project.tags" :key="tag">{{ tag }}</li>
            </ul>
            <p class="image-notice"><span aria-hidden="true"></span>{{ copy.work.imageNotice }}</p>
          </div>
        </article>

        <article class="project-card project-card--open" data-reveal>
          <div class="open-slot__top">
            <p>{{ copy.work.openSlotLabel }}</p>
            <Plus :size="24" weight="bold" aria-hidden="true" />
          </div>
          <div>
            <h3>{{ copy.work.openSlotTitle }}</h3>
            <p>{{ copy.work.openSlotText }}</p>
          </div>
          <p class="open-slot__meta">{{ copy.work.openSlotMeta }}</p>
        </article>
      </div>
    </section>

    <section id="roadmap" class="section roadmap" aria-labelledby="roadmap-title">
      <header class="section-heading section-heading--split" data-reveal>
        <div>
          <p>{{ copy.roadmap.label }}</p>
          <h2 id="roadmap-title" tabindex="-1">{{ copy.roadmap.title }}</h2>
        </div>
        <p>{{ copy.roadmap.introduction }}</p>
      </header>

      <ol class="roadmap-list">
        <li v-for="stage in copy.roadmap.stages" :key="stage.id" data-reveal>
          <div class="roadmap-card__number">{{ stage.number }}</div>
          <div class="roadmap-card__copy">
            <p>{{ stage.period }}</p>
            <h3>{{ stage.title }}</h3>
            <p>{{ stage.summary }}</p>
          </div>
          <ul>
            <li v-for="outcome in stage.outcomes" :key="outcome">
              <Check :size="16" weight="bold" aria-hidden="true" />
              <span>{{ outcome }}</span>
            </li>
          </ul>
        </li>
      </ol>
    </section>

    <footer id="contact" class="contact" aria-labelledby="contact-title">
      <div class="contact__top" data-reveal>
        <p>{{ copy.contact.label }}</p>
        <p>{{ copy.contact.introduction }}</p>
      </div>
      <h2 id="contact-title" tabindex="-1" data-reveal>{{ copy.contact.title }}</h2>
      <div class="contact__actions" data-reveal>
        <a v-if="hasRealEmail" class="contact__email" :href="mailto">
          <span>{{ copy.contact.emailLabel }}</span>
          <strong>{{ copy.contact.email }}</strong>
          <ArrowUpRight :size="24" weight="bold" aria-hidden="true" />
        </a>
        <div v-else class="contact__email contact__email--placeholder" aria-disabled="true">
          <span>{{ copy.contact.emailLabel }}</span>
          <strong>{{ copy.contact.email }}</strong>
        </div>
        <div>
          <a href="#work"
            >{{ copy.contact.workCta }} <ArrowUpRight :size="15" weight="bold" aria-hidden="true"
          /></a>
          <a href="#roadmap"
            >{{ copy.contact.roadmapCta }}
            <ArrowUpRight :size="15" weight="bold" aria-hidden="true"
          /></a>
        </div>
      </div>
      <div class="footer-meta">
        <p>© {{ year }} {{ identity.nameZh }} / {{ identity.nameEn }}</p>
        <p>{{ copy.contact.footerNote }}</p>
        <a href="#top"
          >{{ copy.a11y.backToTop }} <ArrowUpRight :size="13" weight="bold" aria-hidden="true"
        /></a>
      </div>
    </footer>
  </main>
</template>

<style scoped>
.site-header {
  position: fixed;
  top: 1rem;
  left: 50%;
  z-index: 50;
  display: grid;
  grid-template-columns: auto 1fr auto;
  align-items: center;
  width: min(calc(100% - 2rem), 1280px);
  min-height: 4rem;
  padding: 0.45rem 0.5rem 0.45rem 0.65rem;
  border: 1px solid var(--line);
  border-radius: 1.1rem;
  color: var(--ink);
  background: rgb(255 255 255 / 90%);
  box-shadow: 0 14px 40px rgb(36 48 73 / 9%);
  backdrop-filter: blur(18px);
  transform: translateX(-50%);
}

.site-header a,
.mobile-menu a {
  color: inherit;
}

.brand,
.brand__name,
.desktop-nav,
.nav-actions,
.nav-contact,
.language-switch {
  display: flex;
  align-items: center;
}

.brand {
  gap: 0.65rem;
}

.brand__mark {
  display: grid;
  width: 2.75rem;
  aspect-ratio: 1;
  place-items: center;
  border-radius: 0.75rem;
  color: #fff;
  background: var(--accent);
  font-size: 0.72rem;
  font-weight: 750;
  letter-spacing: -0.04em;
}

.brand__name {
  align-items: flex-start;
  flex-direction: column;
  line-height: 1.2;
}

.brand__name strong {
  font-size: 0.78rem;
  font-weight: 750;
}

.brand__name small {
  color: var(--muted);
  font-size: 0.62rem;
}

.desktop-nav {
  justify-content: center;
  gap: 0.15rem;
}

.desktop-nav a {
  display: inline-flex;
  align-items: center;
  min-height: 2.7rem;
  padding: 0 0.95rem;
  border-radius: 0.75rem;
  font-size: 0.76rem;
  font-weight: 650;
  transition:
    color 180ms ease,
    background-color 180ms ease;
}

.desktop-nav a:hover,
.desktop-nav a:focus-visible {
  color: var(--accent);
  background: var(--accent-soft);
}

.nav-actions {
  gap: 0.45rem;
}

.language-switch {
  gap: 0.15rem;
  padding: 0.22rem;
  border: 1px solid var(--line);
  border-radius: 0.72rem;
  background: var(--paper);
}

.language-switch button {
  min-width: 2.35rem;
  min-height: 2.2rem;
  padding: 0 0.45rem;
  border: 0;
  border-radius: 0.52rem;
  color: var(--muted);
  background: transparent;
  font-size: 0.68rem;
  font-weight: 750;
}

.language-switch button[aria-pressed='true'] {
  color: #fff;
  background: var(--ink);
}

.nav-contact {
  justify-content: center;
  gap: 0.35rem;
  min-height: 2.75rem;
  padding: 0 0.9rem;
  border-radius: 0.75rem;
  color: #fff !important;
  background: var(--accent);
  font-size: 0.72rem;
  font-weight: 750;
}

.menu-toggle,
.mobile-menu__close {
  display: none;
  width: 2.75rem;
  height: 2.75rem;
  place-items: center;
  border: 1px solid var(--line);
  border-radius: 0.75rem;
  color: var(--ink);
  background: #fff;
}

.mobile-menu {
  position: fixed;
  inset: 0;
  z-index: 70;
  display: flex;
  flex-direction: column;
  justify-content: space-between;
  padding: 1rem var(--page-gutter) 2rem;
  color: var(--ink);
  background: var(--paper);
}

.mobile-menu__top {
  display: grid;
  grid-template-columns: 1fr auto auto;
  gap: 0.55rem;
  align-items: center;
}

.mobile-menu__close {
  display: grid;
}

.mobile-menu nav {
  display: grid;
  border-top: 1px solid var(--line-strong);
}

.mobile-menu nav a {
  display: grid;
  grid-template-columns: 2.2rem 1fr;
  align-items: center;
  padding: 0.35em 0;
  border-bottom: 1px solid var(--line-strong);
  font-size: clamp(3.2rem, 14vw, 6rem);
  font-weight: 680;
  letter-spacing: -0.065em;
  line-height: 1;
}

.mobile-menu nav a span {
  align-self: start;
  padding-top: 0.65rem;
  color: var(--accent);
  font-size: 0.65rem;
  letter-spacing: 0;
}

.mobile-menu > p {
  color: var(--muted);
  font-size: 0.78rem;
}

.menu-enter-active,
.menu-leave-active {
  transition:
    opacity 260ms ease,
    transform 420ms var(--ease-out);
}

.menu-enter-from,
.menu-leave-to {
  opacity: 0;
  transform: translateY(-1.5rem);
}

.hero {
  display: grid;
  grid-template-columns: minmax(0, 0.95fr) minmax(30rem, 1.05fr);
  gap: clamp(2rem, 5vw, 5.5rem);
  align-items: center;
  min-height: 100svh;
  padding: 8.3rem var(--page-gutter) 4rem;
  overflow: hidden;
}

.hero__copy {
  position: relative;
  z-index: 2;
  max-width: 46rem;
}

.eyebrow,
.availability {
  display: flex;
  align-items: center;
  gap: 0.65rem;
  color: var(--muted);
  font-size: 0.7rem;
  font-weight: 700;
  letter-spacing: 0.08em;
  text-transform: uppercase;
}

.eyebrow span,
.availability span {
  width: 0.55rem;
  height: 0.55rem;
  flex: 0 0 auto;
  border-radius: 50%;
  background: var(--accent);
  box-shadow: 0 0 0 0.28rem var(--accent-soft);
}

.hero__identity {
  margin-top: clamp(2.4rem, 5vw, 4.6rem);
}

.hero__identity p {
  margin-bottom: 0.4rem;
  color: var(--muted);
  font-size: 0.84rem;
  font-weight: 650;
  letter-spacing: 0.03em;
}

.hero h1 {
  font-size: clamp(4.6rem, 8.7vw, 9rem);
  font-weight: 720;
  letter-spacing: -0.085em;
  line-height: 0.86;
}

.hero__role {
  margin-top: 1.25rem;
  color: var(--accent);
  font-size: 0.78rem;
  font-weight: 750;
  letter-spacing: 0.04em;
}

.hero h2 {
  max-width: 14ch;
  margin-top: clamp(2rem, 3.5vw, 3rem);
  font-size: clamp(2.35rem, 4.3vw, 4.8rem);
  font-weight: 640;
  letter-spacing: -0.06em;
  line-height: 1.02;
  text-wrap: balance;
}

.hero__intro {
  max-width: 38rem;
  margin-top: 1.4rem;
  color: var(--muted);
  font-size: clamp(0.9rem, 1.15vw, 1.05rem);
  line-height: 1.75;
}

.hero__actions {
  display: flex;
  flex-wrap: wrap;
  gap: 0.7rem;
  margin-top: 2rem;
}

.button {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: 0.55rem;
  min-height: 3.25rem;
  padding: 0 1.15rem;
  border: 1px solid var(--line-strong);
  border-radius: 0.8rem;
  color: var(--ink);
  font-size: 0.74rem;
  font-weight: 750;
  transition:
    transform 220ms var(--ease-out),
    box-shadow 220ms ease;
}

.button:hover {
  transform: translateY(-2px);
}

.button--primary {
  border-color: var(--accent);
  color: #fff;
  background: var(--accent);
  box-shadow: 0 12px 26px rgb(49 94 251 / 18%);
}

.button--secondary {
  background: #fff;
}

.availability {
  margin-top: 2rem;
  color: var(--ink);
  font-size: 0.64rem;
  letter-spacing: 0.04em;
  text-transform: none;
}

.availability span {
  width: 0.45rem;
  height: 0.45rem;
  background: var(--success);
  box-shadow: 0 0 0 0.27rem rgb(45 171 112 / 11%);
  animation: pulse 2.4s ease-out infinite;
}

.hero__visual {
  --media-x: 0px;
  --media-y: 0px;
  position: relative;
  min-height: min(74svh, 760px);
  margin: 0;
  overflow: hidden;
  border: 1px solid var(--line);
  border-radius: 2rem;
  background: var(--accent-soft);
  box-shadow: 0 32px 80px rgb(37 54 88 / 13%);
}

.hero__visual::before {
  position: absolute;
  inset: 1rem;
  z-index: 1;
  border: 1px solid rgb(255 255 255 / 56%);
  border-radius: 1.35rem;
  content: '';
  pointer-events: none;
}

.hero__visual > img {
  position: absolute;
  top: -0.625rem;
  left: -0.625rem;
  width: calc(100% + 1.25rem);
  height: calc(100% + 1.25rem);
  max-width: none;
  object-fit: cover;
  object-position: var(--hero-position);
  transform: translate3d(calc(-0.625rem + var(--media-x)), calc(-0.625rem + var(--media-y)), 0)
    scale(1.025);
  transition: transform 650ms var(--ease-out);
}

.hero__visual figcaption {
  position: absolute;
  right: 1.5rem;
  bottom: 1.5rem;
  left: 1.5rem;
  z-index: 2;
  display: flex;
  align-items: flex-end;
  justify-content: space-between;
  gap: 1rem;
  padding: 1rem;
  border: 1px solid rgb(255 255 255 / 45%);
  border-radius: 1rem;
  color: #fff;
  background: rgb(25 38 64 / 88%);
  backdrop-filter: blur(14px);
}

.hero__visual figcaption span {
  font-size: 0.68rem;
}

.hero__visual figcaption strong {
  font-size: 0.8rem;
}

.image-credit {
  position: absolute;
  top: 1.5rem;
  right: 1.5rem;
  z-index: 3;
  max-width: calc(100% - 3rem);
  padding: 0.5rem 0.7rem;
  overflow: hidden;
  border: 1px solid rgb(255 255 255 / 42%);
  border-radius: 999px;
  color: #fff;
  background: rgb(21 31 50 / 88%);
  font-size: 0.56rem;
  font-weight: 650;
  text-overflow: ellipsis;
  white-space: nowrap;
  backdrop-filter: blur(12px);
}

.section {
  padding: clamp(6.5rem, 10vw, 10rem) var(--page-gutter);
}

.section-heading > p:first-child,
.section-heading > div > p:first-child,
.micro-label,
.contact__top > p:first-child {
  color: var(--accent);
  font-size: 0.68rem;
  font-weight: 750;
  letter-spacing: 0.09em;
  text-transform: uppercase;
}

.section-heading h2 {
  max-width: 19ch;
  margin-top: 1.8rem;
  font-size: clamp(2.8rem, 5.6vw, 6.3rem);
  font-weight: 650;
  letter-spacing: -0.07em;
  line-height: 0.98;
  text-wrap: balance;
}

.section-heading > p:last-child {
  max-width: 57rem;
  margin-top: 2rem;
  color: var(--muted);
  font-size: clamp(1rem, 1.45vw, 1.2rem);
  line-height: 1.75;
}

.section-heading--split {
  display: grid;
  grid-template-columns: minmax(0, 1fr) minmax(16rem, 0.42fr);
  gap: 4rem;
  align-items: end;
}

.section-heading--split > p:last-child {
  margin: 0 0 0.45rem;
  font-size: 0.92rem;
}

.about {
  background: var(--surface-soft);
}

.fact-grid {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 0.8rem;
  margin-top: clamp(3.5rem, 7vw, 6rem);
}

.fact-grid article {
  display: flex;
  min-height: 10.5rem;
  flex-direction: column;
  justify-content: space-between;
  padding: 1.25rem;
  border: 1px solid var(--line);
  border-radius: 1.25rem;
  background: #fff;
}

.fact-grid p {
  color: var(--muted);
  font-size: 0.67rem;
}

.fact-grid strong {
  font-size: clamp(1rem, 1.6vw, 1.28rem);
  line-height: 1.35;
}

.focus-panel {
  display: grid;
  grid-template-columns: minmax(0, 0.85fr) minmax(22rem, 1.15fr);
  gap: clamp(3rem, 7vw, 8rem);
  margin-top: 0.8rem;
  padding: clamp(2rem, 4.5vw, 4rem);
  border: 1px solid var(--line);
  border-radius: 1.4rem;
  background: #fff;
}

.focus-panel__copy h3 {
  max-width: 18ch;
  margin-top: 1.2rem;
  font-size: clamp(2rem, 3.5vw, 3.8rem);
  font-weight: 640;
  letter-spacing: -0.055em;
  line-height: 1.04;
}

.focus-panel__copy > p:last-child {
  max-width: 35rem;
  margin-top: 1.3rem;
  color: var(--muted);
  line-height: 1.7;
}

.skill-list {
  margin: 0;
  padding: 0;
  border-top: 1px solid var(--line-strong);
  list-style: none;
}

.skill-list li {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 1rem;
  min-height: 3.8rem;
  border-bottom: 1px solid var(--line-strong);
}

.skill-list span {
  font-size: 0.85rem;
  font-weight: 680;
}

.skill-list small {
  color: var(--muted);
  font-size: 0.65rem;
}

.work {
  background: var(--paper);
}

.project-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 1rem;
  margin-top: clamp(4rem, 8vw, 7rem);
}

.project-card {
  overflow: hidden;
  border: 1px solid var(--line);
  border-radius: 1.45rem;
  background: #fff;
}

.project-card--wide {
  grid-column: 1 / -1;
  display: grid;
  grid-template-columns: minmax(0, 1.25fr) minmax(19rem, 0.75fr);
}

.project-card__visual {
  position: relative;
  min-height: 31rem;
  margin: 0;
  overflow: hidden;
  background: var(--surface-soft);
}

.project-card:not(.project-card--wide) .project-card__visual {
  min-height: 27rem;
}

.project-card__visual > img {
  position: absolute;
  inset: 0;
  width: 100%;
  height: 100%;
  object-fit: cover;
  transition: transform 800ms var(--ease-out);
}

.project-card:hover .project-card__visual > img {
  transform: scale(1.025);
}

.project-card__visual-top {
  position: absolute;
  top: 1rem;
  right: 1rem;
  left: 1rem;
  z-index: 2;
  display: flex;
  justify-content: space-between;
  gap: 1rem;
  color: #fff;
  font-size: 0.65rem;
  font-weight: 750;
}

.project-card__visual-top span {
  padding: 0.55rem 0.7rem;
  border: 1px solid rgb(255 255 255 / 42%);
  border-radius: 999px;
  background: rgb(21 31 50 / 88%);
  backdrop-filter: blur(12px);
}

.project-card__visual .image-credit {
  top: auto;
  bottom: 1rem;
}

.project-card__body {
  display: flex;
  flex-direction: column;
  padding: clamp(1.35rem, 3vw, 2.4rem);
}

.project-card__body h3,
.project-card--open h3 {
  margin-top: 1.1rem;
  font-size: clamp(2rem, 3.8vw, 4.1rem);
  font-weight: 640;
  letter-spacing: -0.065em;
  line-height: 1;
}

.project-card:not(.project-card--wide) .project-card__body h3 {
  font-size: clamp(2rem, 3vw, 3.2rem);
}

.project-card__body > p:not(.micro-label, .image-notice) {
  margin-top: 1.25rem;
  color: var(--muted);
  font-size: 0.86rem;
  line-height: 1.7;
}

.tag-list {
  display: flex;
  flex-wrap: wrap;
  gap: 0.4rem;
  margin: auto 0 0;
  padding: 2rem 0 0;
  list-style: none;
}

.tag-list li {
  padding: 0.45rem 0.65rem;
  border: 1px solid var(--line-strong);
  border-radius: 999px;
  color: var(--muted);
  font-size: 0.61rem;
  font-weight: 650;
}

.image-notice {
  display: flex;
  align-items: center;
  gap: 0.5rem;
  margin-top: 1rem;
  color: var(--muted);
  font-size: 0.62rem;
}

.image-notice span {
  width: 0.4rem;
  height: 0.4rem;
  border-radius: 50%;
  background: var(--warning);
}

.project-card--open {
  display: flex;
  min-height: 31rem;
  flex-direction: column;
  justify-content: space-between;
  padding: clamp(1.6rem, 3.5vw, 3rem);
  border-style: dashed;
  background: var(--accent-soft);
}

.open-slot__top {
  display: flex;
  align-items: center;
  justify-content: space-between;
  color: var(--accent);
  font-size: 0.68rem;
  font-weight: 750;
  letter-spacing: 0.08em;
}

.project-card--open > div:nth-child(2) > p {
  max-width: 37rem;
  margin-top: 1.25rem;
  color: var(--muted);
  line-height: 1.7;
}

.open-slot__meta {
  max-width: 32rem;
  padding-top: 1rem;
  border-top: 1px solid var(--line-strong);
  color: var(--muted);
  font-size: 0.68rem;
}

.roadmap {
  background: #fff;
}

.roadmap-list {
  display: grid;
  margin: clamp(4rem, 8vw, 7rem) 0 0;
  padding: 0;
  border-top: 1px solid var(--line-strong);
  list-style: none;
}

.roadmap-list > li {
  display: grid;
  grid-template-columns: 4rem minmax(14rem, 0.75fr) minmax(20rem, 1.25fr);
  gap: clamp(1.5rem, 5vw, 6rem);
  padding: clamp(2rem, 4vw, 3.5rem) 0;
  border-bottom: 1px solid var(--line-strong);
}

.roadmap-card__number {
  display: grid;
  width: 2.75rem;
  height: 2.75rem;
  place-items: center;
  border-radius: 0.75rem;
  color: var(--accent);
  background: var(--accent-soft);
  font-size: 0.68rem;
  font-weight: 750;
}

.roadmap-card__copy > p:first-child {
  color: var(--accent);
  font-size: 0.68rem;
  font-weight: 750;
  letter-spacing: 0.06em;
  text-transform: uppercase;
}

.roadmap-card__copy h3 {
  margin-top: 0.8rem;
  font-size: clamp(1.7rem, 2.8vw, 3rem);
  font-weight: 640;
  letter-spacing: -0.055em;
  line-height: 1.08;
}

.roadmap-card__copy > p:last-child {
  margin-top: 1rem;
  color: var(--muted);
  font-size: 0.8rem;
  line-height: 1.65;
}

.roadmap-list > li > ul {
  display: grid;
  align-content: start;
  gap: 0.8rem;
  margin: 0;
  padding: 0;
  list-style: none;
}

.roadmap-list > li > ul li {
  display: grid;
  grid-template-columns: 1.2rem 1fr;
  gap: 0.6rem;
  align-items: start;
  color: var(--muted);
  font-size: 0.78rem;
  line-height: 1.55;
}

.roadmap-list > li > ul svg {
  margin-top: 0.2rem;
  color: var(--success);
}

.contact {
  padding: clamp(6rem, 10vw, 9rem) var(--page-gutter) 2rem;
  color: #fff;
  background: var(--accent);
}

.contact__top {
  display: grid;
  grid-template-columns: 1fr minmax(18rem, 0.45fr);
  gap: 3rem;
  padding-bottom: 1.4rem;
  border-bottom: 1px solid rgb(255 255 255 / 30%);
}

.contact__top > p:first-child {
  color: #fff;
}

.contact__top > p:last-child {
  color: #fff;
  font-size: 0.85rem;
  line-height: 1.65;
}

.contact h2 {
  max-width: 11ch;
  margin: clamp(4rem, 8vw, 7rem) 0;
  font-size: clamp(4.5rem, 11vw, 12rem);
  font-weight: 650;
  letter-spacing: -0.085em;
  line-height: 0.82;
  text-wrap: balance;
}

.contact__actions {
  display: grid;
  grid-template-columns: minmax(0, 1fr) auto;
  gap: 2rem;
  align-items: end;
}

.contact__email {
  display: grid;
  grid-template-columns: minmax(0, 1fr) auto;
  gap: 0.5rem 1rem;
  align-items: center;
  padding: 1.35rem 0;
  border-top: 1px solid rgb(255 255 255 / 35%);
  border-bottom: 1px solid rgb(255 255 255 / 35%);
  color: #fff;
}

.contact__email span {
  grid-column: 1 / -1;
  color: #fff;
  font-size: 0.62rem;
}

.contact__email strong {
  min-width: 0;
  font-size: clamp(1.1rem, 2.6vw, 2.6rem);
  line-height: 1.2;
  overflow-wrap: anywhere;
}

.contact__email--placeholder {
  cursor: default;
}

.contact__actions > div {
  display: grid;
  gap: 0.6rem;
}

.contact__actions > div a,
.footer-meta a {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 1rem;
  color: #fff;
  font-size: 0.7rem;
  font-weight: 700;
}

.contact__actions > div a {
  min-width: 13rem;
  padding: 0.85rem 0;
  border-bottom: 1px solid rgb(255 255 255 / 30%);
}

.footer-meta {
  display: grid;
  grid-template-columns: 1fr auto auto;
  gap: 2rem;
  margin-top: clamp(5rem, 9vw, 8rem);
  padding-top: 1.5rem;
  border-top: 1px solid rgb(255 255 255 / 30%);
  color: #fff;
  font-size: 0.62rem;
}

[data-reveal] {
  opacity: 0;
  transform: translateY(1.25rem);
  transition:
    opacity 720ms ease,
    transform 820ms var(--ease-out);
}

[data-reveal].is-visible {
  opacity: 1;
  transform: translateY(0);
}

@keyframes pulse {
  0%,
  100% {
    box-shadow: 0 0 0 0 rgb(45 171 112 / 24%);
  }
  50% {
    box-shadow: 0 0 0 0.45rem rgb(45 171 112 / 0%);
  }
}

@media (max-width: 1060px) {
  .desktop-nav,
  .nav-contact,
  .brand__name {
    display: none;
  }

  .site-header {
    grid-template-columns: 1fr auto;
  }

  .menu-toggle {
    display: grid;
  }

  .hero {
    grid-template-columns: 1fr 0.9fr;
  }

  .fact-grid {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}

@media (max-width: 820px) {
  .hero {
    grid-template-columns: 1fr;
    padding-top: 7.7rem;
  }

  .hero__copy {
    max-width: 45rem;
  }

  .hero h1 {
    font-size: clamp(4.5rem, 18vw, 8rem);
  }

  .hero__visual {
    min-height: 62svh;
  }

  .section-heading--split,
  .focus-panel,
  .project-card--wide,
  .roadmap-list > li {
    grid-template-columns: 1fr;
  }

  .section-heading--split {
    gap: 1.5rem;
  }

  .focus-panel {
    gap: 3rem;
  }

  .roadmap-list > li {
    gap: 1.5rem;
  }

  .roadmap-list > li > ul {
    padding-left: 4.1rem;
  }
}

@media (max-width: 640px) {
  .site-header {
    top: 0.65rem;
    width: calc(100% - 1.3rem);
    min-height: 3.6rem;
    border-radius: 0.9rem;
  }

  .brand__mark,
  .menu-toggle {
    width: 2.45rem;
    height: 2.45rem;
  }

  .language-switch button {
    min-width: 2rem;
    min-height: 1.95rem;
    padding: 0 0.35rem;
    font-size: 0.62rem;
  }

  .hero {
    gap: 2.5rem;
    min-height: auto;
    padding-top: 7.2rem;
  }

  .eyebrow {
    max-width: 18rem;
    line-height: 1.5;
  }

  .hero__identity {
    margin-top: 2.8rem;
  }

  .hero h1 {
    font-size: clamp(4.4rem, 23vw, 6.8rem);
  }

  .hero h2 {
    font-size: clamp(2.35rem, 12vw, 3.65rem);
  }

  .hero__actions {
    display: grid;
    grid-template-columns: 1fr 1fr;
  }

  .button {
    min-height: 3.15rem;
    padding: 0 0.65rem;
    font-size: 0.68rem;
  }

  .hero__visual {
    min-height: 64svh;
    border-radius: 1.4rem;
  }

  .hero__visual figcaption {
    right: 1rem;
    bottom: 1rem;
    left: 1rem;
    padding: 0.8rem;
  }

  .hero__visual figcaption span {
    max-width: 9rem;
  }

  .image-credit {
    top: 1rem;
    right: 1rem;
  }

  .section-heading h2 {
    font-size: clamp(2.7rem, 13.5vw, 4.4rem);
  }

  .fact-grid,
  .project-grid {
    grid-template-columns: 1fr;
  }

  .fact-grid article {
    min-height: 8.5rem;
  }

  .focus-panel {
    grid-template-columns: 1fr;
    padding: 1.35rem;
  }

  .skill-list li {
    gap: 0.5rem;
  }

  .skill-list small {
    max-width: 8rem;
    text-align: right;
  }

  .project-card--wide {
    grid-column: auto;
    display: block;
  }

  .project-card__visual,
  .project-card:not(.project-card--wide) .project-card__visual {
    min-height: 24rem;
  }

  .project-card--open {
    min-height: 26rem;
  }

  .roadmap-list > li > ul {
    padding-left: 0;
  }

  .contact__top,
  .contact__actions,
  .footer-meta {
    grid-template-columns: 1fr;
  }

  .contact__top {
    gap: 1.5rem;
  }

  .contact h2 {
    font-size: clamp(4.2rem, 20vw, 6.5rem);
  }

  .contact__actions > div a {
    min-width: 0;
  }

  .footer-meta {
    gap: 0.7rem;
  }

  .footer-meta a {
    margin-top: 0.5rem;
  }
}

@media (prefers-reduced-motion: reduce) {
  .availability span {
    animation: none;
  }

  .hero__visual > img,
  .project-card__visual > img,
  .button,
  .desktop-nav a,
  .menu-enter-active,
  .menu-leave-active,
  [data-reveal] {
    transition: none;
    transform: none;
  }

  [data-reveal] {
    opacity: 1;
  }
}
</style>
