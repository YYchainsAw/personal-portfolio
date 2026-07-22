<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, watch } from 'vue'
import { RouterLink } from 'vue-router'
import { PhArrowUpRight as ArrowUpRight, PhCheck as Check } from '@phosphor-icons/vue'
import ContactForm from '@/components/contact/ContactForm.vue'
import ProjectPremiere from '@/components/home/ProjectPremiere.vue'
import { trackAnalytics } from '@/composables/useAnalyticsConsent'
import type { HomeViewModel } from '@/mappers/homeMapper'
import type { AnalyticsPageKey } from '@/types/interactions'

const props = defineProps<{ model: HomeViewModel }>()

const locale = computed(() => props.model.locale)
const about = computed(() => props.model.copy.about)
const roadmap = computed(() => props.model.copy.roadmap)
const contact = computed(() => props.model.copy.contact)
const year = new Date().getFullYear()

const normalizeSafeEmail = (raw: string) => {
  const value = raw.trim()
  if (value.length > 254 || !/^[a-z0-9._%+-]+@[a-z0-9.-]+\.[a-z]{2,63}$/i.test(value)) return null
  if (/@(?:example\.(?:com|org|net)|.+\.(?:example|invalid|test))$/i.test(value)) return null
  return value
}

const safeContactEmail = computed(() => normalizeSafeEmail(props.model.contact.email))
const deletionEmail = computed(
  () => safeContactEmail.value ?? normalizeSafeEmail(props.model.identity.email) ?? '',
)
const hasRealEmail = computed(() => safeContactEmail.value !== null)
const mailto = computed(() => {
  if (!safeContactEmail.value) return undefined
  const subject =
    locale.value === 'zh-CN' ? '作品集联系 / 游戏开发交流' : 'Portfolio enquiry / Game development'
  const body = locale.value === 'zh-CN' ? '你好，嘉轩：' : 'Hello Jiaxuan,'
  return `mailto:${safeContactEmail.value}?subject=${encodeURIComponent(subject)}&body=${encodeURIComponent(body)}`
})

let analyticsObserver: IntersectionObserver | null = null
const viewedSections = new Set<AnalyticsPageKey>()

function installSectionAnalytics() {
  analyticsObserver?.disconnect()
  analyticsObserver = null
  if (!('IntersectionObserver' in window)) return

  analyticsObserver = new IntersectionObserver(
    (entries) => {
      for (const entry of entries) {
        if (!entry.isIntersecting) continue
        const pageKey = (entry.target as HTMLElement).dataset.analyticsSection as AnalyticsPageKey
        if (!pageKey || viewedSections.has(pageKey)) continue
        viewedSections.add(pageKey)
        trackAnalytics({
          type: 'PAGE_VIEW',
          pageKey,
          projectId: null,
          locale: locale.value,
          referrer: document.referrer || null,
        })
      }
    },
    { threshold: 0.35 },
  )

  document
    .querySelectorAll<HTMLElement>('[data-analytics-section]')
    .forEach((node) => analyticsObserver?.observe(node))
}

watch(locale, async () => {
  viewedSections.clear()
  await nextTick()
  installSectionAnalytics()
})

onMounted(installSectionAnalytics)
onBeforeUnmount(() => analyticsObserver?.disconnect())
</script>

<template>
  <main id="main-content" class="portfolio-page" tabindex="-1">
    <ProjectPremiere :model="model" />

    <section
      id="about"
      class="profile section-shell"
      aria-labelledby="about-title"
      data-analytics-section="ABOUT"
    >
      <header class="section-intro" v-reveal>
        <p class="section-index">02 / {{ about.label }}</p>
        <h2 id="about-title" tabindex="-1">{{ about.title }}</h2>
        <p>{{ about.statement }}</p>
      </header>

      <div class="profile-grid">
        <div class="profile-copy" v-reveal>
          <p class="micro-label">{{ about.focusLabel }}</p>
          <h3>{{ about.focusTitle }}</h3>
          <p>{{ about.focusIntro }}</p>

          <dl class="fact-list">
            <div v-for="fact in about.facts" :key="fact.label">
              <dt>{{ fact.label }}</dt>
              <dd>{{ fact.value }}</dd>
            </div>
          </dl>
        </div>

        <div class="capability-list" v-reveal>
          <p class="micro-label">
            {{ locale === 'zh-CN' ? '当前能力栈' : 'Current capability stack' }}
          </p>
          <ol>
            <li v-for="(skill, index) in about.skills" :key="skill.name">
              <span>{{ String(index + 1).padStart(2, '0') }}</span>
              <strong>{{ skill.name }}</strong>
              <small>{{ skill.status }}</small>
            </li>
          </ol>
        </div>
      </div>
    </section>

    <section
      id="roadmap"
      class="roadmap section-shell"
      aria-labelledby="roadmap-title"
      data-analytics-section="ROADMAP"
    >
      <header class="section-intro section-intro--split" v-reveal>
        <div>
          <p class="section-index">03 / {{ roadmap.label }}</p>
          <h2 id="roadmap-title" tabindex="-1">{{ roadmap.title }}</h2>
        </div>
        <p>{{ roadmap.introduction }}</p>
      </header>

      <ol class="roadmap-track">
        <li v-for="stage in roadmap.stages" :key="stage.id" v-reveal>
          <div class="roadmap-track__top">
            <span>{{ stage.number }}</span>
            <p>{{ stage.period }}</p>
          </div>
          <h3>{{ stage.title }}</h3>
          <p>{{ stage.summary }}</p>
          <ul>
            <li v-for="outcome in stage.outcomes" :key="outcome">
              <Check :size="15" weight="bold" aria-hidden="true" />
              <span>{{ outcome }}</span>
            </li>
          </ul>
        </li>
      </ol>
    </section>

    <footer
      id="contact"
      class="contact section-shell"
      aria-labelledby="contact-title"
      data-analytics-section="CONTACT"
    >
      <div class="contact-heading" v-reveal>
        <p class="section-index">04 / {{ contact.label }}</p>
        <h2 id="contact-title" tabindex="-1">{{ contact.title }}</h2>
        <p>{{ contact.introduction }}</p>
      </div>

      <div class="contact-layout">
        <ContactForm
          v-reveal
          :locale="locale"
          :contact="model.contact"
          :deletion-email="deletionEmail"
        />

        <aside class="contact-aside" v-reveal>
          <p class="micro-label">{{ locale === 'zh-CN' ? '直接联系' : 'Direct contact' }}</p>
          <a v-if="hasRealEmail" class="contact-email" :href="mailto">
            <span>{{ contact.emailLabel }}</span>
            <strong>{{ contact.email }}</strong>
            <ArrowUpRight :size="22" weight="bold" aria-hidden="true" />
          </a>
          <div v-else class="contact-email contact-email--disabled" aria-disabled="true">
            <span>{{ contact.emailLabel }}</span>
            <strong>{{ contact.email }}</strong>
          </div>

          <nav :aria-label="locale === 'zh-CN' ? '页脚导航' : 'Footer navigation'">
            <a href="#work"
              >{{ contact.workCta }} <ArrowUpRight :size="15" weight="bold" aria-hidden="true"
            /></a>
            <a href="#roadmap"
              >{{ contact.roadmapCta }} <ArrowUpRight :size="15" weight="bold" aria-hidden="true"
            /></a>
            <a
              v-if="model.resume?.href"
              :href="model.resume.href"
              data-analytics-type="RESUME_DOWNLOAD"
            >
              {{ model.resume.label }}
              <ArrowUpRight :size="15" weight="bold" aria-hidden="true" />
            </a>
            <a
              v-for="social in model.socialLinks"
              :key="social.platform"
              :href="social.url"
              target="_blank"
              rel="noopener noreferrer"
              data-analytics-type="OUTBOUND_CLICK"
              data-analytics-page-key="CONTACT"
            >
              {{ social.platform }} <ArrowUpRight :size="15" weight="bold" aria-hidden="true" />
            </a>
          </nav>
        </aside>
      </div>

      <div class="footer-meta">
        <p>© {{ year }} {{ model.identity.displayName }} / {{ model.identity.secondaryName }}</p>
        <p>{{ contact.footerNote }}</p>
        <div>
          <RouterLink :to="{ name: 'privacy', params: { locale } }">
            {{ locale === 'zh-CN' ? '隐私' : 'Privacy' }}
          </RouterLink>
          <a href="https://beian.miit.gov.cn/" target="_blank" rel="noopener noreferrer">
            赣ICP备2026016465号-1
          </a>
          <a href="#top">{{ model.copy.accessibility.backToTop }}</a>
        </div>
      </div>
    </footer>
  </main>
</template>

<style scoped>
.portfolio-page {
  --ink: #f3f6f9;
  --paper: #080d12;
  --surface-soft: #0f161d;
  --accent: #5ed8e7;
  --accent-soft: #11333a;
  --muted: #a9b4c1;
  --line: #26313c;
  --line-strong: #3a4856;
  min-height: 100vh;
  overflow: clip;
  color: var(--ink);
  background: linear-gradient(180deg, #080d12 0%, #0b1117 42%, #080d12 100%);
}

.section-shell {
  width: min(100%, 1440px);
  margin: 0 auto;
  padding: clamp(6rem, 10vw, 10rem) clamp(1.25rem, 5vw, 5.5rem);
}

.section-intro {
  display: grid;
  grid-template-columns: minmax(0, 0.9fr) minmax(18rem, 0.6fr);
  gap: clamp(2rem, 8vw, 9rem);
  align-items: end;
  padding-bottom: clamp(2.5rem, 5vw, 4.5rem);
  border-bottom: 1px solid var(--line);
}

.section-index,
.micro-label {
  color: var(--accent);
  font-size: 0.73rem;
  font-weight: 750;
  letter-spacing: 0.12em;
  text-transform: uppercase;
}

.section-intro h2,
.contact-heading h2 {
  max-width: 9ch;
  margin-top: 1.1rem;
  font-size: clamp(3.1rem, 7.2vw, 7.4rem);
  font-weight: 650;
  line-height: 0.91;
  letter-spacing: -0.075em;
}

.section-intro > p,
.section-intro--split > p,
.contact-heading > p:last-child {
  max-width: 44rem;
  color: var(--muted);
  font-size: clamp(1rem, 1.35vw, 1.18rem);
  line-height: 1.85;
}

.profile-grid {
  display: grid;
  grid-template-columns: minmax(0, 0.82fr) minmax(26rem, 1.18fr);
  gap: clamp(3rem, 9vw, 10rem);
  padding-top: clamp(3rem, 6vw, 6rem);
}

.profile-copy h3 {
  max-width: 17ch;
  margin: 1.1rem 0 1.4rem;
  font-size: clamp(2rem, 3.8vw, 4.25rem);
  font-weight: 620;
  line-height: 1.02;
  letter-spacing: -0.055em;
}

.profile-copy > p:last-of-type {
  max-width: 43rem;
  color: var(--muted);
  font-size: 1rem;
  line-height: 1.8;
}

.fact-list {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  margin: clamp(2.5rem, 5vw, 4.5rem) 0 0;
  border-top: 1px solid var(--line);
}

.fact-list div {
  min-width: 0;
  padding: 1.25rem 1rem 0 0;
}

.fact-list div + div {
  padding-left: 1rem;
  border-left: 1px solid var(--line);
}

.fact-list dt {
  color: #7f8b98;
  font-size: 0.72rem;
  letter-spacing: 0.08em;
  text-transform: uppercase;
}

.fact-list dd {
  margin: 0.55rem 0 0;
  font-size: clamp(0.92rem, 1.2vw, 1.08rem);
  font-weight: 680;
  line-height: 1.35;
}

.capability-list ol {
  margin: 1rem 0 0;
  padding: 0;
  border-top: 1px solid var(--line);
  list-style: none;
}

.capability-list li {
  display: grid;
  grid-template-columns: 2.5rem minmax(0, 1fr) auto;
  gap: 1rem;
  align-items: center;
  min-height: 4.65rem;
  border-bottom: 1px solid var(--line);
}

.capability-list li > span {
  color: var(--accent);
  font-size: 0.7rem;
  font-variant-numeric: tabular-nums;
}

.capability-list strong {
  font-size: clamp(1rem, 1.55vw, 1.35rem);
  font-weight: 620;
}

.capability-list small {
  max-width: 18rem;
  color: var(--muted);
  text-align: right;
}

.roadmap {
  border-top: 1px solid var(--line);
}

.section-intro--split {
  align-items: end;
}

.roadmap-track {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  margin: 0;
  padding: 0;
  list-style: none;
}

.roadmap-track > li {
  min-width: 0;
  padding: clamp(2rem, 4vw, 3.6rem);
  border-bottom: 1px solid var(--line);
}

.roadmap-track > li:first-child {
  padding-left: 0;
}

.roadmap-track > li + li {
  border-left: 1px solid var(--line);
}

.roadmap-track__top {
  display: flex;
  justify-content: space-between;
  gap: 1rem;
  color: var(--accent);
  font-size: 0.74rem;
  font-weight: 700;
  letter-spacing: 0.08em;
  text-transform: uppercase;
}

.roadmap-track h3 {
  margin: 3.6rem 0 1.1rem;
  font-size: clamp(1.65rem, 2.7vw, 3rem);
  font-weight: 620;
  line-height: 1.05;
  letter-spacing: -0.045em;
}

.roadmap-track > li > p {
  min-height: 5.2rem;
  color: var(--muted);
  line-height: 1.7;
}

.roadmap-track ul {
  display: grid;
  gap: 0.8rem;
  margin: 2rem 0 0;
  padding: 1.35rem 0 0;
  border-top: 1px solid var(--line);
  list-style: none;
}

.roadmap-track ul li {
  display: flex;
  gap: 0.65rem;
  align-items: flex-start;
  color: #c6d0da;
  font-size: 0.86rem;
}

.roadmap-track ul svg {
  flex: 0 0 auto;
  margin-top: 0.2rem;
  color: var(--accent);
}

.contact {
  width: 100%;
  max-width: none;
  padding-inline: max(clamp(1.25rem, 5vw, 5.5rem), calc((100vw - 1440px) / 2 + 5.5rem));
  border-top: 1px solid var(--line);
  background: #0d141b;
}

.contact-heading {
  display: grid;
  grid-template-columns: minmax(0, 1.1fr) minmax(18rem, 0.55fr);
  gap: clamp(2rem, 8vw, 9rem);
  align-items: end;
  padding-bottom: clamp(2.5rem, 5vw, 4.5rem);
  border-bottom: 1px solid var(--line);
}

.contact-heading .section-index,
.contact-heading h2 {
  grid-column: 1;
}

.contact-heading > p:last-child {
  grid-column: 2;
  grid-row: 1 / span 2;
  align-self: end;
}

.contact-layout {
  display: grid;
  grid-template-columns: minmax(0, 1.15fr) minmax(20rem, 0.55fr);
  gap: clamp(3rem, 8vw, 8rem);
  align-items: start;
  padding-top: clamp(2.5rem, 5vw, 4.5rem);
}

.contact-aside {
  position: sticky;
  top: 7rem;
  padding-top: 2rem;
}

.contact-email {
  display: grid;
  grid-template-columns: 1fr auto;
  gap: 0.65rem 1rem;
  margin-top: 1rem;
  padding: 1.4rem 0;
  border-block: 1px solid var(--line);
  color: var(--ink);
}

.contact-email span {
  grid-column: 1 / -1;
  color: var(--muted);
  font-size: 0.76rem;
}

.contact-email strong {
  min-width: 0;
  overflow-wrap: anywhere;
  font-size: clamp(1rem, 1.7vw, 1.35rem);
}

.contact-email svg {
  color: var(--accent);
}

.contact-email--disabled {
  opacity: 0.65;
}

.contact-aside nav {
  display: grid;
  margin-top: 2rem;
}

.contact-aside nav a {
  display: flex;
  justify-content: space-between;
  gap: 1rem;
  align-items: center;
  padding: 1rem 0;
  border-bottom: 1px solid var(--line);
  color: var(--ink);
  font-weight: 650;
}

.contact-aside a {
  transition: color 180ms ease;
}

.contact-aside a:hover {
  color: var(--accent);
}

.footer-meta {
  display: grid;
  grid-template-columns: auto 1fr auto;
  gap: 2rem;
  align-items: center;
  margin-top: clamp(4rem, 8vw, 8rem);
  padding-top: 1.4rem;
  border-top: 1px solid var(--line);
  color: #7f8b98;
  font-size: 0.74rem;
}

.footer-meta > p:nth-child(2) {
  text-align: center;
}

.footer-meta div {
  display: flex;
  flex-wrap: wrap;
  justify-content: flex-end;
  gap: 1rem;
}

.footer-meta a {
  color: inherit;
}

:deep(.contact-form) {
  width: 100%;
  max-width: none;
  margin: 0;
  padding: 0;
  border: 0;
  border-radius: 0;
  color: var(--ink);
  background: transparent;
  box-shadow: none;
}

:deep(.contact-form input),
:deep(.contact-form textarea) {
  border-color: var(--line-strong);
  border-radius: 0.2rem;
  color: var(--ink);
  background: #111b24;
}

:deep(.contact-form input:focus),
:deep(.contact-form textarea:focus) {
  border-color: var(--accent);
}

:deep(.contact-form .retention-copy),
:deep(.contact-form .form-status) {
  color: #a9b4c1;
}

:deep(.contact-form .retention-copy a) {
  color: #78e1ec;
}

:deep(.contact-form button) {
  border-radius: 0.2rem;
  color: #071015;
  background: #67dce8;
  font-weight: 760;
}

.reveal {
  opacity: 0;
  transform: translateY(28px);
  transition:
    opacity 720ms var(--ease-out),
    transform 720ms var(--ease-out);
}

.reveal.is-visible {
  opacity: 1;
  transform: none;
}

@media (max-width: 980px) {
  .section-intro,
  .profile-grid,
  .contact-heading,
  .contact-layout {
    grid-template-columns: 1fr;
  }

  .section-intro h2,
  .contact-heading h2 {
    max-width: 11ch;
  }

  .contact-heading > p:last-child {
    grid-column: 1;
    grid-row: auto;
  }

  .profile-grid {
    gap: 5rem;
  }

  .roadmap-track {
    grid-template-columns: 1fr;
  }

  .roadmap-track > li,
  .roadmap-track > li:first-child {
    padding: 2.4rem 0;
  }

  .roadmap-track > li + li {
    border-left: 0;
  }

  .roadmap-track h3 {
    margin-top: 2.4rem;
  }

  .roadmap-track > li > p {
    min-height: 0;
  }

  .contact-aside {
    position: static;
  }
}

@media (max-width: 680px) {
  .section-shell {
    padding-block: 5.5rem;
  }

  .section-intro h2,
  .contact-heading h2 {
    font-size: clamp(3rem, 17vw, 5.3rem);
  }

  .fact-list {
    grid-template-columns: 1fr;
  }

  .fact-list div,
  .fact-list div + div {
    display: grid;
    grid-template-columns: 0.8fr 1.2fr;
    gap: 1rem;
    padding: 1rem 0;
    border-bottom: 1px solid var(--line);
    border-left: 0;
  }

  .fact-list dd {
    margin: 0;
    text-align: right;
  }

  .capability-list li {
    grid-template-columns: 2rem minmax(0, 1fr);
    padding-block: 1rem;
  }

  .capability-list small {
    grid-column: 2;
    text-align: left;
  }

  .footer-meta {
    grid-template-columns: 1fr;
  }

  .footer-meta > p:nth-child(2) {
    text-align: left;
  }

  .footer-meta div {
    justify-content: flex-start;
  }
}

@media (prefers-reduced-motion: reduce) {
  .reveal {
    opacity: 1;
    transform: none;
    transition: none;
  }
}
</style>
