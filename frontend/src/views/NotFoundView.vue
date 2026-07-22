<script setup lang="ts">
import { computed, watch } from 'vue'
import { RouterLink, useRoute } from 'vue-router'
import { PhArrowRight as ArrowRight, PhCompass as Compass } from '@phosphor-icons/vue'
import PublicSiteFooter from '@/components/common/PublicSiteFooter.vue'
import PublicSiteHeader from '@/components/common/PublicSiteHeader.vue'
import { uiCopy } from '@/data/uiCopy'
import { applyNotFoundSeo } from '@/services/seo'
import { currentProjectId, currentSite } from '@/stores/publicContent'
import { isLocale, type Locale } from '@/types/public'

const route = useRoute()
const locale = computed<Locale>(() => {
  const first = route.path.split('/').filter(Boolean)[0]
  return isLocale(first) ? first : 'zh-CN'
})
const copy = computed(() =>
  locale.value === 'zh-CN'
    ? {
        section: '未知区域',
        eyebrow: '404 / LOST COORDINATES',
        code: '路径未收录',
        hint: '回到首页，从已发布的作品与开发记录继续浏览。',
      }
    : {
        section: 'Unknown sector',
        eyebrow: '404 / LOST COORDINATES',
        code: 'Route not indexed',
        hint: 'Return to the portfolio and continue from the published projects and development notes.',
      },
)

currentSite.value = null
currentProjectId.value = null
watch(locale, (value) => applyNotFoundSeo(value), { immediate: true })
</script>

<template>
  <PublicSiteHeader :locale="locale" :section-label="copy.section" />

  <main id="main-content" class="not-found" tabindex="-1">
    <div class="not-found__index" aria-hidden="true">
      <span>04</span>
      <span>/</span>
      <span>04</span>
    </div>

    <section class="not-found__content" aria-labelledby="not-found-title">
      <p class="not-found__eyebrow">{{ copy.eyebrow }}</p>
      <div class="not-found__title-row">
        <h1 id="not-found-title">{{ uiCopy[locale].notFoundTitle }}</h1>
        <Compass :size="54" weight="thin" aria-hidden="true" />
      </div>
      <p class="not-found__message">{{ uiCopy[locale].notFoundText }}</p>
      <p class="not-found__hint">{{ copy.hint }}</p>
      <RouterLink class="not-found__action" :to="{ name: 'home', params: { locale } }">
        <span>{{ uiCopy[locale].backHome }}</span>
        <ArrowRight :size="21" weight="bold" aria-hidden="true" />
      </RouterLink>
    </section>

    <div class="not-found__status" aria-hidden="true">
      <span>{{ copy.code }}</span>
      <span>PORTFOLIO / {{ locale }}</span>
    </div>
  </main>

  <PublicSiteFooter :locale="locale" />
</template>

<style scoped>
.not-found {
  display: grid;
  grid-template-columns: minmax(6rem, 0.22fr) minmax(0, 1fr);
  grid-template-rows: 1fr auto;
  gap: 2rem clamp(2rem, 8vw, 9rem);
  min-height: calc(100svh - var(--header-height));
  padding: clamp(3rem, 8vw, 7rem) var(--page-gutter) 1.5rem;
  color: #f2f1ed;
  background: #0b0d0f;
  font-family: Manrope, 'Noto Sans SC', 'Microsoft YaHei', sans-serif;
}

.not-found__index {
  display: flex;
  align-items: flex-start;
  gap: 0.6rem;
  padding-top: 0.45rem;
  color: #7d878c;
  font-size: clamp(1.3rem, 2.2vw, 2.4rem);
  font-weight: 400;
  letter-spacing: -0.05em;
}

.not-found__index span:last-child {
  color: #35d9e7;
}

.not-found__content {
  align-self: center;
  max-width: 68rem;
}

.not-found__eyebrow {
  margin: 0 0 1.6rem;
  color: #35d9e7;
  font-size: 0.66rem;
  font-weight: 600;
  letter-spacing: 0.15em;
}

.not-found__title-row {
  display: flex;
  align-items: flex-start;
  gap: 2rem;
}

.not-found__title-row svg {
  flex: 0 0 auto;
  margin-top: 0.7rem;
  color: #35d9e7;
}

.not-found h1 {
  max-width: 11ch;
  margin: 0;
  font-size: clamp(4rem, 10vw, 10rem);
  font-weight: 600;
  line-height: 0.92;
  letter-spacing: -0.07em;
  text-wrap: balance;
}

.not-found__message {
  max-width: 38rem;
  margin: 2rem 0 0;
  color: #c8ccca;
  font-size: clamp(1rem, 1.5vw, 1.25rem);
  line-height: 1.65;
}

.not-found__hint {
  max-width: 36rem;
  margin: 0.75rem 0 0;
  color: #838b90;
  font-size: 0.76rem;
  line-height: 1.8;
}

.not-found__action {
  display: inline-flex;
  align-items: center;
  gap: 1rem;
  width: max-content;
  min-height: 3rem;
  margin-top: 2.8rem;
  padding: 0.65rem 0;
  border-bottom: 1px solid #626a6e;
  color: #f2f1ed;
  font-size: 0.82rem;
  font-weight: 600;
  transition: gap 180ms ease, color 180ms ease, border-color 180ms ease;
}

.not-found__action:hover,
.not-found__action:focus-visible {
  gap: 1.4rem;
  border-color: #35d9e7;
  color: #35d9e7;
}

.not-found__status {
  grid-column: 1 / -1;
  display: flex;
  justify-content: space-between;
  gap: 1rem;
  padding-top: 1rem;
  border-top: 1px solid rgb(255 255 255 / 14%);
  color: #7d878c;
  font-size: 0.6rem;
  font-weight: 600;
  letter-spacing: 0.12em;
  text-transform: uppercase;
}

@media (max-width: 700px) {
  .not-found {
    grid-template-columns: 1fr;
    grid-template-rows: auto 1fr auto;
    gap: 2rem;
    min-height: calc(100svh - 4.5rem);
    padding: 2rem 1rem 1rem;
  }

  .not-found__index {
    font-size: 1.2rem;
  }

  .not-found__title-row svg {
    display: none;
  }

  .not-found h1 {
    font-size: clamp(3.6rem, 18vw, 6rem);
  }

  .not-found__status {
    grid-column: 1;
  }
}

@media (prefers-reduced-motion: reduce) {
  .not-found__action {
    transition: none;
  }
}
</style>
