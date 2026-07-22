<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { RouterLink } from 'vue-router'
import {
  PhArrowRight as ArrowRight,
  PhCrosshair as Crosshair,
  PhCube as Cube,
  PhList as List,
  PhX as X,
} from '@phosphor-icons/vue'
import ResponsiveMedia from '@/components/media/ResponsiveMedia.vue'
import { useLocale, type Locale } from '@/composables/useLocale'
import type { HomeViewModel } from '@/mappers/homeMapper'
import type { ProjectCard } from '@/types/public'
import ueGameplayPrototype from '@/assets/showcase/ue-gameplay-prototype.webp'
import ueGameplayPrototypeThumb from '@/assets/showcase/ue-gameplay-prototype-thumb.webp'
import ueSceneInteractionStudy from '@/assets/showcase/ue-scene-interaction-study.webp'
import ueSceneInteractionStudyThumb from '@/assets/showcase/ue-scene-interaction-study-thumb.webp'
import ueTechnicalBreakdown from '@/assets/showcase/ue-technical-breakdown.webp'
import ueTechnicalBreakdownThumb from '@/assets/showcase/ue-technical-breakdown-thumb.webp'

const props = defineProps<{ model: HomeViewModel }>()
const { setLocale } = useLocale()

interface ProjectEntry {
  kind: 'project'
  key: string
  number: string
  project: ProjectCard
  title: string
  eyebrow: string
  summary: string
  status: string
  tags: string[]
  presentationImage?: string
  presentationThumb?: string
  presentationAlt?: string
}

interface FutureEntry {
  kind: 'future'
  key: string
  number: string
  title: string
  eyebrow: string
  summary: string
  status: string
  tags: string[]
  image: string
  thumbnail: string
  alt: string
}

type ReelEntry = ProjectEntry | FutureEntry

const locale = computed(() => props.model.locale)
const identity = computed(() => props.model.identity)
const navigation = computed(() =>
  Object.fromEntries(
    props.model.copy.navigation.map((item) => [item.target.replace(/^#/, ''), item.label]),
  ),
)
const labels = computed(() => {
  const isChinese = locale.value === 'zh-CN'
  return {
    nav: {
      work: navigation.value.work || props.model.copy.work.label,
      about: navigation.value.about || props.model.copy.about.label,
      roadmap: navigation.value.roadmap || props.model.copy.roadmap.label,
      contact: navigation.value.contact || props.model.copy.contact.label,
    },
    selected: isChinese ? '作品导航' : 'Project index',
    selectedNote: isChinese ? '已发布与制作中' : 'Selected & in development',
    fullCase: isChinese ? '查看完整案例' : 'View full case',
    focus: isChinese ? '项目标签' : 'Project focus',
    status: isChinese ? '当前状态' : 'Status',
    inDevelopment: isChinese ? '学习方向' : 'Learning track',
    futureHint: isChinese
      ? '这是下一阶段的学习与实验方向，形成可展示成果后会继续补充。'
      : 'This is a next-stage learning and experimentation track; the case study will open when it is ready to show.',
    emptyCatalog: isChinese
      ? '项目正在准备中，欢迎稍后再来查看。'
      : 'Projects are being prepared. Please check back soon.',
    selectProject: (number: string) =>
      isChinese ? `选择第 ${number} 个作品` : `Select project ${number}`,
  }
})

const futureEntries = computed<Omit<FutureEntry, 'number'>[]>(() => {
  if (locale.value === 'zh-CN') {
    return [
      {
        kind: 'future',
        key: 'future:gameplay',
        title: '游戏机制原型',
        eyebrow: 'GAMEPLAY SYSTEMS',
        summary: '围绕交互、触发逻辑与玩家反馈建立可迭代的 Blueprint 原型。',
        status: '学习中',
        tags: ['Blueprint', '交互系统', '玩法验证'],
        image: ueGameplayPrototype,
        thumbnail: ueGameplayPrototypeThumb,
        alt: 'Unreal Engine 第三人称游戏机制原型开发场景',
      },
      {
        kind: 'future',
        key: 'future:breakdown',
        title: '开发日志与技术拆解',
        eyebrow: 'TECHNICAL BREAKDOWN',
        summary: '记录场景、光照、材质与性能分析过程，把学习转化为可复用的方法。',
        status: '学习中',
        tags: ['灯光', '材质', '性能诊断'],
        image: ueTechnicalBreakdown,
        thumbnail: ueTechnicalBreakdownThumb,
        alt: 'Unreal Engine 场景灯光材质与性能技术拆解视图',
      },
      {
        kind: 'future',
        key: 'future:vertical-slice',
        title: '可玩关卡垂直切片',
        eyebrow: 'PLAYABLE VERTICAL SLICE',
        summary: '把关卡动线、交互机制和基础视觉表现整合成一段可体验内容。',
        status: '规划中',
        tags: ['Level Design', 'UE5', 'Playtest'],
        image: ueGameplayPrototype,
        thumbnail: ueGameplayPrototypeThumb,
        alt: 'Unreal Engine 可玩关卡垂直切片开发画面',
      },
    ]
  }

  return [
    {
      kind: 'future',
      key: 'future:gameplay',
      title: 'Gameplay Systems Prototype',
      eyebrow: 'GAMEPLAY SYSTEMS',
      summary:
        'An iterative Blueprint prototype focused on interaction, trigger logic, and player feedback.',
      status: 'Learning',
      tags: ['Blueprint', 'Interaction', 'Playtest'],
      image: ueGameplayPrototype,
      thumbnail: ueGameplayPrototypeThumb,
      alt: 'Third-person gameplay prototype being developed in Unreal Engine',
    },
    {
      kind: 'future',
      key: 'future:breakdown',
      title: 'Development Log & Breakdown',
      eyebrow: 'TECHNICAL BREAKDOWN',
      summary: 'A practical record of scene, lighting, material, and performance investigation.',
      status: 'Learning',
      tags: ['Lighting', 'Materials', 'Profiling'],
      image: ueTechnicalBreakdown,
      thumbnail: ueTechnicalBreakdownThumb,
      alt: 'Unreal Engine lighting material and performance breakdown view',
    },
    {
      kind: 'future',
      key: 'future:vertical-slice',
      title: 'Playable Level Vertical Slice',
      eyebrow: 'PLAYABLE VERTICAL SLICE',
      summary:
        'A focused experience combining level flow, interaction systems, and foundational visuals.',
      status: 'Planned',
      tags: ['Level Design', 'UE5', 'Playtest'],
      image: ueGameplayPrototype,
      thumbnail: ueGameplayPrototypeThumb,
      alt: 'Playable Unreal Engine level vertical slice in development',
    },
  ]
})

const reelEntries = computed<ReelEntry[]>(() => {
  const orderedProjects = [...props.model.projects].sort(
    (left, right) => Number(right.featured) - Number(left.featured),
  )
  const projects: ProjectEntry[] = orderedProjects.map((project, index) => ({
    kind: 'project',
    key: `project:${project.projectId}`,
    number: project.number,
    project,
    title: project.title,
    eyebrow: project.eyebrow,
    summary: project.summary,
    status: project.status,
    tags: project.tags,
    presentationImage: index === 0 ? ueSceneInteractionStudy : undefined,
    presentationThumb: index === 0 ? ueSceneInteractionStudyThumb : undefined,
    presentationAlt:
      index === 0
        ? locale.value === 'zh-CN'
          ? 'Unreal Engine 5 现代建筑场景中的角色、交互门、触发体与导航调试视图'
          : 'Unreal Engine 5 development view with a character, interactive door, trigger volume, and navigation debugging in a modern architectural scene'
        : undefined,
  }))
  const openSlots = Math.max(0, 3 - projects.length)
  const futures = futureEntries.value.slice(0, openSlots).map((entry, index) => ({
    ...entry,
    number: String(projects.length + index + 1).padStart(2, '0'),
  }))
  return [...projects, ...futures]
})

const preferredKey = computed(() => {
  return reelEntries.value[0]!.key
})
const activeKey = ref('')
const activeEntry = computed<ReelEntry>(
  () => reelEntries.value.find((entry) => entry.key === activeKey.value) ?? reelEntries.value[0]!,
)
const activeIndex = computed(() =>
  Math.max(
    0,
    reelEntries.value.findIndex((entry) => entry.key === activeEntry.value.key),
  ),
)
const developmentHint = computed(() =>
  props.model.projects.length === 0 ? labels.value.emptyCatalog : labels.value.futureHint,
)

watch(
  reelEntries,
  (entries) => {
    if (!entries.some((entry) => entry.key === activeKey.value))
      activeKey.value = preferredKey.value
  },
  { immediate: true },
)

const menuOpen = ref(false)
let previousBodyOverflow = ''
let desktopMenuQuery: MediaQueryList | undefined
const menuToggle = ref<HTMLButtonElement | null>(null)
const mobileMenu = ref<HTMLElement | null>(null)
const reel = ref<HTMLOListElement | null>(null)

const setLanguage = (nextLocale: Locale) => setLocale(nextLocale)

const closeMenu = (restoreFocus = false, target?: string) => {
  menuOpen.value = false
  nextTick(() => {
    if (target) document.querySelector<HTMLElement>(target)?.focus({ preventScroll: true })
    else if (restoreFocus) menuToggle.value?.focus()
  })
}

const focusableMenuElements = () =>
  Array.from(
    mobileMenu.value?.querySelectorAll<HTMLElement>(
      'a[href], button:not([disabled]), [tabindex]:not([tabindex="-1"])',
    ) ?? [],
  )

const handleGlobalKeydown = (event: KeyboardEvent) => {
  if (!menuOpen.value) return
  if (event.key === 'Escape') {
    event.preventDefault()
    closeMenu(true)
    return
  }
  if (event.key !== 'Tab') return

  const focusable = focusableMenuElements()
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

const selectEntry = (key: string) => {
  activeKey.value = key
}

const handleReelKeydown = (event: KeyboardEvent, index: number) => {
  const keys = ['ArrowLeft', 'ArrowRight', 'ArrowUp', 'ArrowDown', 'Home', 'End']
  if (!keys.includes(event.key)) return
  event.preventDefault()

  const lastIndex = reelEntries.value.length - 1
  let nextIndex = index
  if (event.key === 'Home') nextIndex = 0
  else if (event.key === 'End') nextIndex = lastIndex
  else if (event.key === 'ArrowLeft' || event.key === 'ArrowUp')
    nextIndex = index === 0 ? lastIndex : index - 1
  else nextIndex = index === lastIndex ? 0 : index + 1

  const nextEntry = reelEntries.value[nextIndex]
  if (!nextEntry) return
  selectEntry(nextEntry.key)
  const actions = reel.value?.querySelectorAll<HTMLElement>('.reel__item-action')
  actions?.[nextIndex]?.focus()
}

watch(menuOpen, async (open) => {
  if (open) {
    previousBodyOverflow = document.body.style.overflow
    document.body.style.overflow = 'hidden'
  } else {
    document.body.style.overflow = previousBodyOverflow
  }
  if (open) {
    await nextTick()
    focusableMenuElements()[0]?.focus()
  }
})

const handleDesktopBreakpoint = (event: MediaQueryListEvent) => {
  if (event.matches && menuOpen.value) closeMenu(false)
}

onMounted(() => {
  window.addEventListener('keydown', handleGlobalKeydown)
  desktopMenuQuery = window.matchMedia('(min-width: 1081px)')
  desktopMenuQuery.addEventListener('change', handleDesktopBreakpoint)
})
onBeforeUnmount(() => {
  window.removeEventListener('keydown', handleGlobalKeydown)
  desktopMenuQuery?.removeEventListener('change', handleDesktopBreakpoint)
  document.body.style.overflow = previousBodyOverflow
})
</script>

<template>
  <div id="top" class="project-premiere">
    <header class="premiere-header">
      <a class="premiere-brand" href="#top" :aria-label="model.copy.accessibility.backToTop">
        <h1>{{ identity.displayName }}</h1>
        <span aria-hidden="true">{{ identity.secondaryName }}</span>
      </a>

      <nav class="premiere-nav" :aria-label="model.copy.accessibility.primaryNav">
        <a class="is-current" href="#work">{{ labels.nav.work }}</a>
        <a href="#about">{{ labels.nav.about }}</a>
        <a href="#roadmap">{{ labels.nav.roadmap }}</a>
        <a href="#contact">{{ labels.nav.contact }}</a>
      </nav>

      <div class="header-actions">
        <div class="locale-switch" role="group" :aria-label="model.copy.accessibility.language">
          <button
            type="button"
            lang="zh-CN"
            aria-label="中文"
            :aria-pressed="locale === 'zh-CN'"
            @click="setLanguage('zh-CN')"
          >
            中
          </button>
          <span aria-hidden="true">/</span>
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
          ref="menuToggle"
          class="menu-toggle"
          type="button"
          :aria-expanded="menuOpen"
          aria-controls="mobile-menu"
          :aria-label="
            menuOpen ? model.copy.accessibility.closeMenu : model.copy.accessibility.openMenu
          "
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
        :aria-label="model.copy.accessibility.mobileNav"
      >
        <div class="mobile-menu__header">
          <span>{{ identity.displayName }}</span>
          <button
            type="button"
            :aria-label="model.copy.accessibility.closeMenu"
            @click="closeMenu(true)"
          >
            <X :size="23" weight="bold" aria-hidden="true" />
          </button>
        </div>
        <div
          class="locale-switch locale-switch--mobile"
          role="group"
          :aria-label="model.copy.accessibility.language"
        >
          <button
            type="button"
            lang="zh-CN"
            aria-label="中文"
            :aria-pressed="locale === 'zh-CN'"
            @click="setLanguage('zh-CN')"
          >
            中文
          </button>
          <button
            type="button"
            lang="en"
            aria-label="English"
            :aria-pressed="locale === 'en'"
            @click="setLanguage('en')"
          >
            English
          </button>
        </div>
        <nav :aria-label="model.copy.accessibility.mobileNav">
          <a href="#work" @click="closeMenu(false, '#work-title')"
            ><span>01</span>{{ labels.nav.work }}</a
          >
          <a href="#about" @click="closeMenu(false, '#about-title')"
            ><span>02</span>{{ labels.nav.about }}</a
          >
          <a href="#roadmap" @click="closeMenu(false, '#roadmap-title')"
            ><span>03</span>{{ labels.nav.roadmap }}</a
          >
          <a href="#contact" @click="closeMenu(false, '#contact-title')"
            ><span>04</span>{{ labels.nav.contact }}</a
          >
        </nav>
        <p>{{ model.copy.hero.role }}</p>
      </div>
    </Transition>

    <section
      id="work"
      class="premiere-work"
      aria-labelledby="work-title"
      data-analytics-section="WORK"
      :inert="menuOpen || undefined"
      :aria-hidden="menuOpen || undefined"
    >
      <Transition name="stage" mode="out-in">
        <article
          id="project-stage-panel"
          :key="activeEntry.key"
          class="project-stage"
          role="tabpanel"
          :aria-labelledby="`project-tab-${activeIndex + 1}`"
          tabindex="0"
        >
          <div class="project-stage__copy">
            <p class="stage-kicker">{{ activeEntry.eyebrow }}</p>
            <h2 id="work-title" tabindex="-1">{{ activeEntry.title }}</h2>
            <p class="stage-summary">{{ activeEntry.summary }}</p>

            <dl class="stage-meta">
              <div>
                <dt><Cube :size="17" weight="regular" aria-hidden="true" />{{ labels.focus }}</dt>
                <dd>{{ activeEntry.tags.join(' · ') || activeEntry.eyebrow }}</dd>
              </div>
              <div>
                <dt>
                  <Crosshair :size="17" weight="regular" aria-hidden="true" />{{ labels.status }}
                </dt>
                <dd>{{ activeEntry.status }}</dd>
              </div>
            </dl>

            <RouterLink
              v-if="activeEntry.kind === 'project'"
              class="stage-link"
              :to="{ name: 'project', params: { locale, slug: activeEntry.project.slug } }"
              :aria-label="activeEntry.project.title"
            >
              <span aria-hidden="true">{{ labels.fullCase }}</span>
              <ArrowRight :size="21" weight="bold" aria-hidden="true" />
            </RouterLink>
            <div v-else class="stage-development">
              <span>{{ labels.inDevelopment }}</span>
              <p>{{ developmentHint }}</p>
            </div>
          </div>

          <figure class="project-stage__visual">
            <img
              v-if="activeEntry.kind === 'project' && activeEntry.presentationImage"
              :src="activeEntry.presentationImage"
              :alt="activeEntry.presentationAlt"
              width="1672"
              height="941"
              loading="eager"
              fetchpriority="high"
            />
            <ResponsiveMedia
              v-else-if="activeEntry.kind === 'project'"
              :media="activeEntry.project.cover"
              sizes="(max-width: 760px) 100vw, 64vw"
              eager
            />
            <img
              v-else
              :src="activeEntry.image"
              :alt="activeEntry.alt"
              width="1792"
              height="1024"
              loading="eager"
              fetchpriority="high"
            />
            <div class="visual-caption">
              <span>{{ activeEntry.number }}</span>
              <strong>{{ activeEntry.status }}</strong>
            </div>
            <a
              v-if="
                activeEntry.kind === 'project' &&
                !activeEntry.presentationImage &&
                activeEntry.project.cover.credit &&
                activeEntry.project.cover.sourceUrl.startsWith('https://')
              "
              class="media-credit"
              :href="activeEntry.project.cover.sourceUrl"
              target="_blank"
              rel="noopener noreferrer"
              data-analytics-type="OUTBOUND_CLICK"
              data-analytics-page-key="WORK"
            >
              {{ locale === 'zh-CN' ? '图片' : 'Image' }} / {{ activeEntry.project.cover.credit }}
            </a>
            <span
              v-else-if="
                activeEntry.kind === 'project' &&
                !activeEntry.presentationImage &&
                activeEntry.project.cover.credit
              "
              class="media-credit"
            >
              {{ locale === 'zh-CN' ? '图片' : 'Image' }} / {{ activeEntry.project.cover.credit }}
            </span>
          </figure>
        </article>
      </Transition>

      <div class="project-reel">
        <header class="project-reel__header">
          <p>
            <strong>{{ labels.selected }}</strong
            ><span>/ {{ labels.selectedNote }}</span>
          </p>
          <span aria-hidden="true"></span>
        </header>

        <ol ref="reel" role="tablist" :aria-label="labels.selected">
          <li
            v-for="(entry, index) in reelEntries"
            :key="entry.key"
            role="presentation"
            :class="{ 'is-active': activeEntry.key === entry.key }"
          >
            <button
              class="reel__item-action"
              type="button"
              role="tab"
              :id="`project-tab-${index + 1}`"
              :aria-label="labels.selectProject(entry.number)"
              :aria-selected="activeEntry.key === entry.key"
              aria-controls="project-stage-panel"
              :tabindex="activeEntry.key === entry.key ? 0 : -1"
              @click="selectEntry(entry.key)"
              @focus="selectEntry(entry.key)"
              @keydown="handleReelKeydown($event, index)"
            >
              <span class="reel-copy">
                <strong class="reel-number">{{ entry.number }}</strong>
                <span class="reel-title">{{ entry.title }}</span>
                <span class="reel-tags">{{ entry.tags.slice(0, 2).join(' · ') }}</span>
                <small>{{ entry.status }}</small>
              </span>
              <span class="reel-media" aria-hidden="true">
                <img
                  v-if="entry.kind === 'project' && entry.presentationThumb"
                  :src="entry.presentationThumb"
                  alt=""
                  width="720"
                  height="405"
                  loading="lazy"
                />
                <ResponsiveMedia
                  v-else-if="entry.kind === 'project'"
                  :media="entry.project.cover"
                  sizes="28vw"
                  decorative
                />
                <img v-else :src="entry.thumbnail" alt="" width="720" height="405" loading="lazy" />
              </span>
            </button>
          </li>
        </ol>
      </div>
    </section>
  </div>
</template>

<style scoped>
.project-premiere {
  --premiere-bg: #0b0d0f;
  --premiere-panel: #111417;
  --premiere-paper: #f2f1ed;
  --premiere-muted: #a4a8aa;
  --premiere-line: rgb(255 255 255 / 16%);
  --premiere-cyan: #35d9e7;
  position: relative;
  min-width: 0;
  overflow: clip;
  color: var(--premiere-paper);
  background: var(--premiere-bg);
  font-family: Manrope, 'Noto Sans SC', 'Microsoft YaHei', sans-serif;
}

* {
  box-sizing: border-box;
}

button,
a {
  font: inherit;
}

.premiere-header {
  position: absolute;
  z-index: 30;
  top: 0;
  right: 0;
  left: 0;
  display: grid;
  grid-template-columns: minmax(12rem, 1fr) auto minmax(12rem, 1fr);
  align-items: center;
  gap: 2rem;
  min-height: 5rem;
  padding: 0 clamp(1.25rem, 4vw, 4.5rem);
  border-bottom: 1px solid rgb(255 255 255 / 8%);
  background: linear-gradient(180deg, rgb(8 10 12 / 90%), rgb(8 10 12 / 36%), transparent);
}

.premiere-brand {
  display: inline-flex;
  align-items: baseline;
  gap: 0.75rem;
  width: max-content;
  color: inherit;
  text-decoration: none;
}

.premiere-brand h1 {
  margin: 0;
  font-size: clamp(1rem, 1.25vw, 1.2rem);
  font-weight: 600;
  letter-spacing: -0.03em;
}

.premiere-brand span {
  color: #8c9296;
  font-size: 0.66rem;
  font-weight: 500;
  letter-spacing: 0.08em;
  text-transform: uppercase;
}

.premiere-nav {
  display: flex;
  align-items: stretch;
  align-self: stretch;
  gap: clamp(1.75rem, 3vw, 3.6rem);
}

.premiere-nav a {
  position: relative;
  display: inline-flex;
  align-items: center;
  color: #c8c9c6;
  font-size: 0.82rem;
  font-weight: 500;
  text-decoration: none;
  transition: color 180ms ease;
}

.premiere-nav a::after {
  position: absolute;
  right: 0;
  bottom: 0;
  left: 0;
  height: 2px;
  background: var(--premiere-cyan);
  content: '';
  opacity: 0;
  transform: scaleX(0.3);
  transition:
    opacity 180ms ease,
    transform 180ms ease;
}

.premiere-nav a:hover,
.premiere-nav a:focus-visible,
.premiere-nav a.is-current {
  color: #fff;
}

.premiere-nav a:hover::after,
.premiere-nav a:focus-visible::after,
.premiere-nav a.is-current::after {
  opacity: 1;
  transform: scaleX(1);
}

.header-actions {
  display: flex;
  justify-content: flex-end;
  align-items: center;
  gap: 0.85rem;
}

.locale-switch {
  display: inline-flex;
  align-items: center;
  gap: 0.3rem;
  color: #666b6e;
}

.locale-switch button {
  min-width: 1.75rem;
  min-height: 2.75rem;
  padding: 0;
  border: 0;
  color: #8f9497;
  background: transparent;
  cursor: pointer;
}

.locale-switch button[aria-pressed='true'],
.locale-switch button:hover,
.locale-switch button:focus-visible {
  color: #fff;
}

.menu-toggle {
  display: none;
  place-items: center;
  width: 2.75rem;
  height: 2.75rem;
  padding: 0;
  border: 1px solid var(--premiere-line);
  border-radius: 50%;
  color: #fff;
  background: rgb(15 18 21 / 80%);
  cursor: pointer;
}

.premiere-work {
  min-width: 0;
}

.project-stage {
  display: grid;
  grid-template-columns: minmax(20rem, 0.72fr) minmax(0, 1.28fr);
  min-height: clamp(35rem, 70svh, 48rem);
  padding-top: 5rem;
  background: #0b0d0f;
}

.project-stage:focus-visible {
  outline: 2px solid var(--premiere-cyan);
  outline-offset: -2px;
}

.project-stage__copy {
  position: relative;
  z-index: 2;
  display: flex;
  flex-direction: column;
  justify-content: center;
  min-width: 0;
  padding: clamp(4rem, 7vw, 7.5rem) clamp(2rem, 5vw, 5.5rem);
  background: linear-gradient(90deg, #0a0c0e 0 86%, rgb(10 12 14 / 92%) 94%, transparent);
}

.stage-kicker {
  margin: 0 0 1.2rem;
  color: var(--premiere-cyan);
  font-size: 0.66rem;
  font-weight: 600;
  letter-spacing: 0.16em;
  text-transform: uppercase;
}

.project-stage h2 {
  max-width: 9ch;
  margin: 0;
  color: #fff;
  font-size: clamp(2.85rem, 4.8vw, 5.4rem);
  font-weight: 600;
  line-height: 1.06;
  letter-spacing: -0.055em;
  text-wrap: balance;
}

.stage-summary {
  max-width: 35rem;
  margin: 1.5rem 0 0;
  color: #c1c4c3;
  font-size: clamp(0.9rem, 1.2vw, 1.05rem);
  line-height: 1.85;
}

.stage-meta {
  display: grid;
  gap: 0.8rem;
  max-width: 30rem;
  margin: clamp(2rem, 4vh, 3.2rem) 0 0;
}

.stage-meta div {
  display: grid;
  grid-template-columns: 7.4rem minmax(0, 1fr);
  align-items: center;
  gap: 1rem;
}

.stage-meta dt,
.stage-meta dd {
  margin: 0;
  font-size: 0.72rem;
}

.stage-meta dt {
  display: inline-flex;
  align-items: center;
  gap: 0.55rem;
  color: #858b8d;
}

.stage-meta dd {
  color: #d7d8d5;
}

.stage-link {
  display: inline-flex;
  align-items: center;
  gap: 1rem;
  width: max-content;
  margin-top: clamp(2.2rem, 5vh, 3.7rem);
  padding: 0.65rem 0;
  border-bottom: 1px solid #6f7475;
  color: #f4f4f1;
  font-size: 0.86rem;
  text-decoration: none;
  transition:
    gap 200ms ease,
    border-color 200ms ease,
    color 200ms ease;
}

.stage-link:hover,
.stage-link:focus-visible {
  gap: 1.4rem;
  border-color: var(--premiere-cyan);
  color: var(--premiere-cyan);
}

.stage-development {
  max-width: 30rem;
  margin-top: clamp(2.2rem, 5vh, 3.7rem);
  padding-top: 1rem;
  border-top: 1px solid var(--premiere-line);
}

.stage-development span {
  color: var(--premiere-cyan);
  font-size: 0.69rem;
  font-weight: 600;
  letter-spacing: 0.1em;
  text-transform: uppercase;
}

.stage-development p {
  margin: 0.55rem 0 0;
  color: #8e9394;
  font-size: 0.75rem;
  line-height: 1.65;
}

.project-stage__visual {
  position: relative;
  min-width: 0;
  min-height: 100%;
  margin: 0;
  overflow: hidden;
  background: #181c1f;
}

.project-stage__visual::after {
  position: absolute;
  inset: 0;
  background: linear-gradient(
    90deg,
    rgb(8 10 12 / 62%),
    transparent 23%,
    transparent 72%,
    rgb(8 10 12 / 18%)
  );
  content: '';
  pointer-events: none;
}

.project-stage__visual > :deep(img),
.project-stage__visual > img {
  width: 100%;
  height: 100%;
  object-fit: cover;
  transform: scale(1.002);
}

.visual-caption {
  position: absolute;
  z-index: 2;
  right: clamp(1.25rem, 3vw, 3rem);
  bottom: clamp(1.25rem, 3vw, 2.5rem);
  display: flex;
  align-items: center;
  gap: 0.85rem;
  padding: 0.7rem 0.85rem;
  border: 1px solid rgb(255 255 255 / 18%);
  color: #fff;
  background: rgb(9 11 13 / 72%);
  backdrop-filter: blur(14px);
}

.visual-caption span {
  color: var(--premiere-cyan);
  font-size: 1.1rem;
  font-weight: 500;
}

.visual-caption strong {
  font-size: 0.64rem;
  font-weight: 500;
  letter-spacing: 0.08em;
  text-transform: uppercase;
}

.media-credit {
  position: absolute;
  z-index: 3;
  top: 5.75rem;
  right: 1rem;
  max-width: 22rem;
  padding: 0.4rem 0.55rem;
  color: #e6e8e6;
  background: rgb(9 11 13 / 70%);
  font-size: 0.65rem;
  line-height: 1.4;
  text-decoration: none;
  backdrop-filter: blur(8px);
}

.project-reel {
  position: relative;
  z-index: 3;
  padding: 1rem clamp(1rem, 3.9vw, 4.25rem) clamp(1.2rem, 3vw, 2.75rem);
  border-top: 1px solid var(--premiere-line);
  background: #0d0f11;
}

.project-reel__header {
  display: grid;
  grid-template-columns: auto minmax(2rem, 1fr);
  align-items: center;
  gap: 1.2rem;
  margin-bottom: 0.9rem;
}

.project-reel__header p {
  display: flex;
  align-items: baseline;
  gap: 0.4rem;
  margin: 0;
  font-size: 0.7rem;
}

.project-reel__header strong {
  color: #f0f1ee;
  font-weight: 600;
}

.project-reel__header p span {
  color: #949a9d;
  font-size: 0.65rem;
  letter-spacing: 0.06em;
  text-transform: uppercase;
}

.project-reel__header > span {
  height: 1px;
  background: #3f4446;
}

.project-reel ol {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  grid-auto-flow: column;
  grid-auto-columns: minmax(22rem, 33.333%);
  overflow-x: auto;
  margin: 0;
  padding: 0;
  border: 1px solid rgb(255 255 255 / 10%);
  list-style: none;
  overscroll-behavior-inline: contain;
  scroll-snap-type: inline proximity;
}

.project-reel li {
  min-width: 0;
  min-height: 10.6rem;
  border-left: 1px solid rgb(255 255 255 / 10%);
  background: #0f1113;
  transition:
    background 180ms ease,
    box-shadow 180ms ease;
  scroll-snap-align: start;
}

.project-reel li:first-child {
  border-left: 0;
}

.project-reel li.is-active {
  position: relative;
  z-index: 1;
  background: #111719;
  box-shadow: inset 0 0 0 1px rgb(53 217 231 / 68%);
}

.reel__item-action {
  display: grid;
  grid-template-columns: minmax(9rem, 0.9fr) minmax(8rem, 1.1fr);
  width: 100%;
  height: 100%;
  min-height: 10.6rem;
  padding: 0;
  border: 0;
  color: inherit;
  background: transparent;
  text-align: left;
  text-decoration: none;
  cursor: pointer;
}

.reel__item-action:focus-visible {
  outline: 2px solid var(--premiere-cyan);
  outline-offset: -3px;
}

.reel-copy {
  display: flex;
  flex-direction: column;
  min-width: 0;
  padding: 1rem;
}

.reel-number {
  color: #5f6466;
  font-size: clamp(1.5rem, 2.3vw, 2.35rem);
  font-weight: 400;
  line-height: 1;
  letter-spacing: -0.05em;
}

.is-active .reel-number {
  color: var(--premiere-cyan);
}

.reel-title {
  display: -webkit-box;
  min-height: 2.8em;
  margin-top: 0.75rem;
  overflow: hidden;
  color: #e4e5e2;
  font-size: 0.73rem;
  font-weight: 600;
  line-height: 1.4;
  -webkit-box-orient: vertical;
  -webkit-line-clamp: 2;
}

.reel-tags {
  overflow: hidden;
  margin-top: 0.35rem;
  color: #9a9fa1;
  font-size: 0.65rem;
  line-height: 1.45;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.reel-copy small {
  margin-top: auto;
  color: #9ba0a1;
  font-size: 0.6rem;
}

.is-active .reel-copy small {
  color: var(--premiere-cyan);
}

.reel-media {
  min-width: 0;
  overflow: hidden;
  background: #1a1d1f;
}

.reel-media :deep(img),
.reel-media > img {
  width: 100%;
  height: 100%;
  object-fit: cover;
  filter: saturate(0.75) brightness(0.76);
  transition:
    filter 220ms ease,
    transform 360ms ease;
}

.is-active .reel-media :deep(img),
.is-active .reel-media > img,
.reel__item-action:hover .reel-media :deep(img),
.reel__item-action:hover .reel-media > img {
  filter: saturate(0.95) brightness(0.92);
  transform: scale(1.025);
}

.mobile-menu {
  position: fixed;
  z-index: 80;
  inset: 0;
  display: flex;
  flex-direction: column;
  padding: 1.1rem;
  color: var(--premiere-paper);
  background: #0b0d0f;
}

.mobile-menu__header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding-bottom: 1rem;
  border-bottom: 1px solid var(--premiere-line);
  font-size: 0.95rem;
  font-weight: 600;
}

.mobile-menu__header button {
  display: grid;
  place-items: center;
  width: 2.75rem;
  height: 2.75rem;
  padding: 0;
  border: 1px solid var(--premiere-line);
  border-radius: 50%;
  color: #fff;
  background: transparent;
}

.locale-switch--mobile {
  align-self: flex-start;
  margin-top: 1.4rem;
  padding: 0.25rem;
  border: 1px solid var(--premiere-line);
}

.locale-switch--mobile button {
  min-width: 5rem;
  min-height: 2.5rem;
}

.locale-switch--mobile button[aria-pressed='true'] {
  color: #071012;
  background: var(--premiere-cyan);
}

.mobile-menu nav {
  display: grid;
  margin-top: clamp(2.5rem, 10vh, 6rem);
}

.mobile-menu nav a {
  display: grid;
  grid-template-columns: 2.5rem 1fr;
  align-items: baseline;
  padding: 0.8rem 0;
  color: #f3f3ef;
  font-size: clamp(2rem, 10vw, 3.6rem);
  font-weight: 500;
  line-height: 1.1;
  letter-spacing: -0.05em;
  text-decoration: none;
}

.mobile-menu nav a span {
  color: var(--premiere-cyan);
  font-size: 0.65rem;
  letter-spacing: 0;
}

.mobile-menu > p {
  margin: auto 0 0;
  color: #747a7c;
  font-size: 0.72rem;
}

.stage-enter-active,
.stage-leave-active {
  transition:
    opacity 240ms ease,
    transform 340ms ease;
}

.stage-enter-from {
  opacity: 0;
  transform: translateY(0.6rem);
}

.stage-leave-to {
  opacity: 0;
  transform: translateY(-0.4rem);
}

.menu-enter-active,
.menu-leave-active {
  transition:
    opacity 240ms ease,
    transform 320ms ease;
}

.menu-enter-from,
.menu-leave-to {
  opacity: 0;
  transform: translateY(-1rem);
}

@media (max-width: 1080px) {
  .premiere-header {
    grid-template-columns: minmax(10rem, 1fr) auto;
  }

  .premiere-nav {
    display: none;
  }

  .menu-toggle {
    display: grid;
  }

  .project-stage {
    grid-template-columns: minmax(18rem, 0.8fr) minmax(0, 1.2fr);
  }

  .project-stage__copy {
    padding-right: 2.5rem;
    padding-left: 2.5rem;
  }

  .reel__item-action {
    grid-template-columns: minmax(7rem, 0.85fr) minmax(6.5rem, 1.15fr);
  }
}

@media (max-width: 760px) {
  .premiere-header {
    position: absolute;
    min-height: 4.3rem;
    padding: 0 1rem;
  }

  .premiere-brand span,
  .header-actions > .locale-switch {
    display: none;
  }

  .project-stage {
    grid-template-columns: 1fr;
    min-height: 0;
    padding-top: 4.3rem;
  }

  .project-stage__visual {
    order: -1;
    min-height: clamp(22rem, 58svh, 33rem);
  }

  .project-stage__visual::after {
    background: linear-gradient(0deg, rgb(8 10 12 / 55%), transparent 38%);
  }

  .project-stage__copy {
    padding: 2.2rem 1.2rem 2.8rem;
    background: #0b0d0f;
  }

  .project-stage h2 {
    max-width: 13ch;
    font-size: clamp(2.45rem, 13vw, 4rem);
  }

  .stage-summary {
    font-size: 0.87rem;
  }

  .stage-meta div {
    grid-template-columns: 6.4rem minmax(0, 1fr);
  }

  .media-credit {
    top: 0.8rem;
    right: 0.8rem;
  }

  .project-reel {
    padding: 1rem;
  }

  .project-reel__header p span {
    display: none;
  }

  .project-reel ol {
    grid-template-columns: 1fr;
    grid-auto-flow: row;
    grid-auto-columns: auto;
    overflow-x: visible;
    scroll-snap-type: none;
  }

  .project-reel li {
    min-height: 8.5rem;
    border-top: 1px solid rgb(255 255 255 / 10%);
    border-left: 0;
  }

  .project-reel li:first-child {
    border-top: 0;
  }

  .reel__item-action {
    grid-template-columns: minmax(0, 1fr) minmax(8rem, 42%);
    min-height: 8.5rem;
  }

  .reel-media {
    display: block;
  }

  .reel-copy {
    padding: 0.85rem;
  }

  .reel-number {
    font-size: 1.55rem;
  }

  .reel-title {
    min-height: auto;
    margin-top: 0.45rem;
    font-size: 0.68rem;
  }
}

@media (max-width: 420px) {
  .reel__item-action {
    grid-template-columns: minmax(0, 1fr) 7.5rem;
  }

  .reel-tags {
    max-width: 10rem;
  }
}

@media (prefers-reduced-motion: reduce) {
  *,
  *::before,
  *::after {
    scroll-behavior: auto !important;
    transition-duration: 0.01ms !important;
    animation-duration: 0.01ms !important;
    animation-iteration-count: 1 !important;
  }

  .stage-enter-from,
  .stage-leave-to,
  .menu-enter-from,
  .menu-leave-to,
  .is-active .reel-media :deep(img),
  .is-active .reel-media > img {
    opacity: 1;
    transform: none;
  }
}
</style>
