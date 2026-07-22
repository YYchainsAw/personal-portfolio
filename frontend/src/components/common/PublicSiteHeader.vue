<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { RouterLink, useRoute, type RouteLocationRaw } from 'vue-router'
import {
  PhArrowLeft as ArrowLeft,
  PhList as List,
  PhX as X,
} from '@phosphor-icons/vue'
import LanguageSwitch from '@/components/common/LanguageSwitch.vue'
import type { Locale, PublicSite } from '@/types/public'

const props = defineProps<{
  locale: Locale
  site?: PublicSite | null
  backTo?: RouteLocationRaw
  backLabel?: string
  sectionLabel?: string
}>()

const route = useRoute()
const menuOpen = ref(false)
const menuToggle = ref<HTMLButtonElement | null>(null)
const mobileMenu = ref<HTMLElement | null>(null)
let desktopMenuQuery: MediaQueryList | undefined
let previousBodyOverflow = ''
let inertElements: Array<{ element: HTMLElement; inert: boolean }> = []

const identity = computed(() => ({
  displayName:
    props.site?.identity.displayName || (props.locale === 'zh-CN' ? '易嘉轩' : 'Jiaxuan Yi'),
  secondaryName:
    props.site?.identity.secondaryName || (props.locale === 'zh-CN' ? '游戏开发' : 'Game Development'),
}))

const labels = computed(() =>
  props.locale === 'zh-CN'
    ? {
        primaryNav: props.site?.accessibility.primaryNav || '主要导航',
        mobileNav: props.site?.accessibility.mobileNav || '移动导航',
        openMenu: props.site?.accessibility.openMenu || '打开菜单',
        closeMenu: props.site?.accessibility.closeMenu || '关闭菜单',
        menu: '导航',
        note: '作品、学习与开发记录',
      }
    : {
        primaryNav: props.site?.accessibility.primaryNav || 'Primary navigation',
        mobileNav: props.site?.accessibility.mobileNav || 'Mobile navigation',
        openMenu: props.site?.accessibility.openMenu || 'Open menu',
        closeMenu: props.site?.accessibility.closeMenu || 'Close menu',
        menu: 'Navigation',
        note: 'Projects, learning, and development notes',
      },
)

const navigation = computed(() =>
  [...(props.site?.navigation ?? [])]
    .filter((item) => item.target.startsWith('#'))
    .sort((left, right) => left.sortOrder - right.sortOrder),
)

const homeLocation = computed<RouteLocationRaw>(() => ({
  name: 'home',
  params: { locale: props.locale },
}))

const navigationLocation = (target: string): RouteLocationRaw => ({
  name: 'home',
  params: { locale: props.locale },
  hash: target,
})

const visibleBackLabel = (label: string) => label.replace(/^←\s*/u, '')

const focusableMenuElements = () =>
  Array.from(
    mobileMenu.value?.querySelectorAll<HTMLElement>(
      'a[href], button:not([disabled]), [tabindex]:not([tabindex="-1"])',
    ) ?? [],
  )

const restorePageInteraction = () => {
  document.body.style.overflow = previousBodyOverflow
  for (const { element, inert } of inertElements) element.inert = inert
  inertElements = []
}

const closeMenu = (restoreFocus = false) => {
  if (!menuOpen.value) return
  menuOpen.value = false
  if (restoreFocus) nextTick(() => menuToggle.value?.focus())
}

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
  const last = focusable.at(-1)
  if (!first || !last) return

  if (event.shiftKey && document.activeElement === first) {
    event.preventDefault()
    last.focus()
  } else if (!event.shiftKey && document.activeElement === last) {
    event.preventDefault()
    first.focus()
  }
}

const handleDesktopBreakpoint = (event: MediaQueryListEvent) => {
  if (event.matches) closeMenu(false)
}

watch(menuOpen, async (open) => {
  if (open) {
    previousBodyOverflow = document.body.style.overflow
    document.body.style.overflow = 'hidden'
    inertElements = Array.from(
      document.querySelectorAll<HTMLElement>('#main-content, .public-site-footer'),
    ).map((element) => ({ element, inert: element.inert }))
    for (const { element } of inertElements) element.inert = true
    await nextTick()
    focusableMenuElements()[0]?.focus()
  } else {
    restorePageInteraction()
  }
})

watch(
  () => route.fullPath,
  () => closeMenu(false),
)

onMounted(() => {
  window.addEventListener('keydown', handleGlobalKeydown)
  desktopMenuQuery = window.matchMedia('(min-width: 921px)')
  desktopMenuQuery.addEventListener('change', handleDesktopBreakpoint)
})

onBeforeUnmount(() => {
  window.removeEventListener('keydown', handleGlobalKeydown)
  desktopMenuQuery?.removeEventListener('change', handleDesktopBreakpoint)
  restorePageInteraction()
})
</script>

<template>
  <header class="public-site-header">
    <div
      class="public-site-header__inner"
      :inert="menuOpen"
      :aria-hidden="menuOpen ? 'true' : undefined"
    >
      <div class="brand-lockup">
        <RouterLink class="brand" :to="homeLocation">
          <span class="brand__name">{{ identity.displayName }}</span>
          <span class="brand__secondary">{{ identity.secondaryName }}</span>
        </RouterLink>
        <span v-if="sectionLabel" class="section-label">{{ sectionLabel }}</span>
      </div>

      <nav v-if="navigation.length" class="desktop-nav" :aria-label="labels.primaryNav">
        <RouterLink
          v-for="item in navigation"
          :key="item.target"
          :to="navigationLocation(item.target)"
        >
          {{ item.label }}
        </RouterLink>
      </nav>

      <div class="header-actions">
        <RouterLink
          v-if="backTo && backLabel"
          class="back-link"
          :to="backTo"
          :aria-label="backLabel"
        >
          <ArrowLeft :size="17" weight="bold" aria-hidden="true" />
          <span>{{ visibleBackLabel(backLabel) }}</span>
        </RouterLink>
        <LanguageSwitch class="desktop-language" />
        <button
          v-if="navigation.length || (backTo && backLabel)"
          ref="menuToggle"
          class="menu-toggle"
          type="button"
          :aria-label="labels.openMenu"
          aria-controls="public-mobile-menu"
          :aria-expanded="menuOpen"
          @click="menuOpen = true"
        >
          <List :size="21" weight="bold" aria-hidden="true" />
        </button>
      </div>
    </div>

    <Transition name="public-menu">
      <div
        v-if="menuOpen"
        id="public-mobile-menu"
        ref="mobileMenu"
        class="mobile-menu"
        role="dialog"
        aria-modal="true"
        :aria-label="labels.mobileNav"
      >
        <div class="mobile-menu__header">
          <div>
            <strong>{{ identity.displayName }}</strong>
            <span>{{ sectionLabel || labels.menu }}</span>
          </div>
          <button type="button" :aria-label="labels.closeMenu" @click="closeMenu(true)">
            <X :size="22" weight="bold" aria-hidden="true" />
          </button>
        </div>

        <LanguageSwitch class="mobile-language" />

        <nav :aria-label="labels.mobileNav">
          <RouterLink
            v-if="backTo && backLabel"
            class="mobile-back"
            :to="backTo"
            :aria-label="backLabel"
            @click="closeMenu(false)"
          >
            <ArrowLeft :size="20" weight="bold" aria-hidden="true" />
            {{ visibleBackLabel(backLabel) }}
          </RouterLink>
          <RouterLink
            v-for="(item, index) in navigation"
            :key="item.target"
            :to="navigationLocation(item.target)"
            @click="closeMenu(false)"
          >
            <span aria-hidden="true">{{ String(index + 1).padStart(2, '0') }}</span>
            {{ item.label }}
          </RouterLink>
        </nav>

        <p>{{ labels.note }}</p>
      </div>
    </Transition>
  </header>
</template>

<style scoped>
.public-site-header {
  position: sticky;
  z-index: 60;
  top: 0;
  min-width: 0;
  border-bottom: 1px solid rgb(255 255 255 / 8%);
  color: var(--ink);
  background: rgb(8 13 18 / 98%);
}

.public-site-header__inner {
  display: grid;
  grid-template-columns: minmax(13rem, 1fr) auto minmax(13rem, 1fr);
  align-items: center;
  gap: 2rem;
  min-height: var(--header-height);
  padding: 0 var(--page-gutter);
}

.brand-lockup,
.brand,
.header-actions,
.back-link {
  display: flex;
  align-items: center;
}

.brand-lockup {
  grid-column: 1;
  min-width: 0;
  gap: 1rem;
}

.brand {
  min-height: 2.75rem;
  gap: 0.7rem;
  color: var(--ink);
}

.brand__name {
  overflow: hidden;
  font-size: clamp(0.94rem, 1.2vw, 1.12rem);
  font-weight: 600;
  letter-spacing: -0.035em;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.brand__secondary,
.section-label {
  color: #77838f;
  font-size: 0.62rem;
  font-weight: 600;
  letter-spacing: 0.1em;
  text-transform: uppercase;
}

.section-label {
  overflow: hidden;
  max-width: 12rem;
  padding-left: 1rem;
  border-left: 1px solid var(--line);
  color: var(--accent);
  text-overflow: ellipsis;
  white-space: nowrap;
}

.desktop-nav {
  grid-column: 2;
  display: flex;
  align-self: stretch;
  align-items: stretch;
  gap: clamp(1.5rem, 2.8vw, 3.2rem);
}

.desktop-nav a {
  position: relative;
  display: inline-flex;
  align-items: center;
  min-height: 2.75rem;
  color: #abb5be;
  font-size: 0.75rem;
  font-weight: 500;
  transition: color 180ms ease;
}

.desktop-nav a::after {
  position: absolute;
  right: 0;
  bottom: 0;
  left: 0;
  height: 2px;
  background: var(--accent);
  content: '';
  opacity: 0;
  transform: scaleX(0.4);
  transition:
    opacity 180ms ease,
    transform 180ms ease;
}

.desktop-nav a:hover,
.desktop-nav a:focus-visible {
  color: #fff;
}

.desktop-nav a:hover::after,
.desktop-nav a:focus-visible::after {
  opacity: 1;
  transform: scaleX(1);
}

.header-actions {
  grid-column: 3;
  justify-content: flex-end;
  gap: 0.7rem;
}

.back-link {
  min-height: 2.75rem;
  gap: 0.55rem;
  padding: 0 0.2rem;
  border-bottom: 1px solid #52606c;
  color: #c9d1d8;
  font-size: 0.72rem;
  font-weight: 600;
  transition:
    color 180ms ease,
    border-color 180ms ease;
}

.back-link:hover,
.back-link:focus-visible {
  border-color: var(--accent);
  color: var(--accent);
}

.menu-toggle {
  display: none;
  place-items: center;
  width: 2.75rem;
  height: 2.75rem;
  padding: 0;
  border: 1px solid var(--line);
  border-radius: 50%;
  color: var(--ink);
  background: var(--surface);
}

.mobile-menu {
  position: fixed;
  z-index: 90;
  inset: 0;
  display: flex;
  flex-direction: column;
  min-width: 0;
  min-height: 100dvh;
  padding: 1rem;
  color: var(--ink);
  background: var(--paper);
}

.mobile-menu__header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  min-height: 3.4rem;
  padding-bottom: 0.8rem;
  border-bottom: 1px solid var(--line);
}

.mobile-menu__header > div {
  display: grid;
  gap: 0.2rem;
}

.mobile-menu__header strong {
  font-size: 0.95rem;
  letter-spacing: -0.03em;
}

.mobile-menu__header span {
  color: var(--accent);
  font-size: 0.59rem;
  font-weight: 600;
  letter-spacing: 0.1em;
  text-transform: uppercase;
}

.mobile-menu__header button {
  display: grid;
  place-items: center;
  width: 2.75rem;
  height: 2.75rem;
  padding: 0;
  border: 1px solid var(--line);
  border-radius: 50%;
  color: var(--ink);
  background: var(--surface);
}

.mobile-language {
  align-self: flex-start;
  margin-top: 1.25rem;
}

.mobile-menu nav {
  display: grid;
  margin-top: clamp(2rem, 8vh, 5rem);
}

.mobile-menu nav a {
  display: grid;
  grid-template-columns: 2.6rem minmax(0, 1fr);
  align-items: center;
  min-height: 3.6rem;
  padding: 0.5rem 0;
  border-bottom: 1px solid var(--line);
  color: var(--ink);
  font-size: clamp(1.8rem, 9vw, 3.5rem);
  font-weight: 500;
  line-height: 1.08;
  letter-spacing: -0.05em;
}

.mobile-menu nav a span {
  color: var(--accent);
  font-size: 0.62rem;
  letter-spacing: 0;
}

.mobile-menu nav .mobile-back {
  grid-template-columns: 2.6rem minmax(0, 1fr);
  margin-bottom: 1rem;
  color: var(--muted);
  font-size: 0.8rem;
  font-weight: 600;
  letter-spacing: 0;
}

.mobile-menu > p {
  margin: auto 0 0;
  color: #6f7b86;
  font-size: 0.7rem;
}

.public-menu-enter-active,
.public-menu-leave-active {
  transition:
    opacity 220ms ease,
    transform 320ms var(--ease-out);
}

.public-menu-enter-from,
.public-menu-leave-to {
  opacity: 0;
  transform: translateY(-1rem);
}

@media (max-width: 1120px) {
  .public-site-header__inner {
    grid-template-columns: minmax(11rem, 1fr) auto minmax(11rem, 1fr);
    gap: 1rem;
  }

  .brand__secondary {
    display: none;
  }
}

@media (max-width: 920px) {
  .public-site-header__inner {
    grid-template-columns: minmax(0, 1fr) auto;
  }

  .desktop-nav {
    display: none;
  }

  .header-actions {
    grid-column: 2;
  }

  .menu-toggle {
    display: grid;
  }
}

@media (max-width: 560px) {
  .section-label {
    display: none;
  }
}

@media (max-width: 360px) {
  .public-site-header__inner {
    grid-template-columns: 1fr;
  }

  .brand-lockup {
    display: none;
  }

  .header-actions {
    grid-column: 1;
    justify-content: space-between;
    width: 100%;
  }
}

@media (prefers-reduced-motion: reduce) {
  .public-menu-enter-active,
  .public-menu-leave-active {
    transition-duration: 0.01ms;
  }

  .public-menu-enter-from,
  .public-menu-leave-to {
    opacity: 1;
    transform: none;
  }
}
</style>
