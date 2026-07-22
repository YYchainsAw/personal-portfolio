<script setup lang="ts">
import { computed, watch } from 'vue'
import { useRoute } from 'vue-router'
import {
  PhChartLineUp as ChartLineUp,
  PhClockCounterClockwise as ClockCounterClockwise,
  PhShieldCheck as ShieldCheck,
} from '@phosphor-icons/vue'
import AsyncState from '@/components/common/AsyncState.vue'
import PublicSiteFooter from '@/components/common/PublicSiteFooter.vue'
import PublicSiteHeader from '@/components/common/PublicSiteHeader.vue'
import { useAnalyticsConsent } from '@/composables/useAnalyticsConsent'
import { trackAnalytics } from '@/composables/useAnalyticsConsent'
import { useAsyncRouteData } from '@/composables/useAsyncRouteData'
import { applySeo, publicBaseUrl } from '@/services/seo'
import { currentProjectId, currentSite, publicContentStore } from '@/stores/publicContent'
import { isLocale, type Locale } from '@/types/public'

const route = useRoute()
const locale = computed<Locale>(() => (isLocale(route.params.locale) ? route.params.locale : 'zh-CN'))
const resource = useAsyncRouteData(locale, (signal) =>
  publicContentStore.loadPrivacy(locale.value, signal),
)
const content = computed(() => resource.value.value)
const analytics = useAnalyticsConsent()

const copy = computed(() =>
  locale.value === 'zh-CN'
    ? {
        back: '← 首页',
        section: '隐私与数据',
        eyebrow: 'PRIVACY / DATA',
        summary: '了解本站如何处理留言、访问统计和浏览器标识。',
        principles: '数据原则',
        minimal: '最少收集',
        minimalText: '只为运行作品站与回复留言处理必要信息。',
        retention: '有限保留',
        retentionText: '原始统计事件设定最长保留期，标识会定期轮换。',
        settings: '匿名统计设置',
        settingsLabel: '你的选择',
        analyticsText:
          '统计默认关闭。获得同意后，原始事件最多保留 30 天，浏览器标识也会定期轮换。撤回会停止未来收集并清除本浏览器中的标识，但无法从去标识化汇总中识别个人。它不是跨设备人数计数器。',
        withdraw: '撤回同意',
        enable: '启用匿名统计',
      }
    : {
        back: '← Home',
        section: 'Privacy & data',
        eyebrow: 'PRIVACY / DATA',
        summary: 'How this portfolio handles messages, visit analytics, and browser identifiers.',
        principles: 'Data principles',
        minimal: 'Collect less',
        minimalText: 'Only information required to operate the portfolio and respond to messages is processed.',
        retention: 'Retain briefly',
        retentionText: 'Raw analytics have a fixed maximum retention window and identifiers rotate regularly.',
        settings: 'Analytics settings',
        settingsLabel: 'Your choice',
        analyticsText:
          'Analytics is off by default. After consent, raw events are retained for at most 30 days and browser identifiers rotate regularly. Withdrawal stops future collection and clears this browser’s identifiers, but cannot identify a person in de-identified aggregates. It is not a cross-device people counter.',
        withdraw: 'Withdraw consent',
        enable: 'Enable analytics',
      },
)

watch(
  () => resource.value.value,
  (nextContent) => {
    if (!nextContent) return
    currentSite.value = nextContent.site
    currentProjectId.value = null
    applySeo({ kind: 'privacy', locale: locale.value, site: nextContent.site }, publicBaseUrl())
    trackAnalytics({
      type: 'PAGE_VIEW',
      pageKey: 'PRIVACY',
      projectId: null,
      locale: locale.value,
      referrer: document.referrer || null,
    })
  },
  { immediate: true },
)
</script>

<template>
  <AsyncState
    :locale="locale"
    :state="resource.state.value"
    :trace-id="resource.problem.value?.body.traceId"
    @retry="resource.retry"
  >
    <template v-if="content">
      <PublicSiteHeader
        :locale="locale"
        :site="content.site"
        :back-to="{ name: 'home', params: { locale } }"
        :back-label="copy.back"
        :section-label="copy.section"
      />

      <main id="main-content" class="privacy-page" tabindex="-1">
        <section class="privacy-hero" aria-labelledby="privacy-title">
          <p class="privacy-eyebrow">{{ copy.eyebrow }}</p>
          <h1 id="privacy-title">{{ content.site.privacy.title }}</h1>
          <p class="privacy-summary">{{ copy.summary }}</p>
          <ShieldCheck :size="44" weight="thin" aria-hidden="true" />
        </section>

        <div class="privacy-layout">
          <aside class="privacy-principles" :aria-label="copy.principles">
            <p class="section-index">01 / {{ copy.principles }}</p>
            <article>
              <ShieldCheck :size="21" weight="regular" aria-hidden="true" />
              <h2>{{ copy.minimal }}</h2>
              <p>{{ copy.minimalText }}</p>
            </article>
            <article>
              <ClockCounterClockwise :size="21" weight="regular" aria-hidden="true" />
              <h2>{{ copy.retention }}</h2>
              <p>{{ copy.retentionText }}</p>
            </article>
          </aside>

          <article class="privacy-document">
            <p class="section-index">02 / {{ content.site.privacy.title }}</p>
            <div class="privacy-prose" v-html="content.site.privacy.html"></div>
          </article>
        </div>

        <section
          v-if="!analytics.suppressedByDnt.value"
          class="analytics-settings"
          aria-labelledby="analytics-settings-title"
        >
          <div class="settings-heading">
            <ChartLineUp :size="28" weight="regular" aria-hidden="true" />
            <div>
              <p class="section-index">03 / {{ copy.settingsLabel }}</p>
              <h2 id="analytics-settings-title">{{ copy.settings }}</h2>
            </div>
          </div>
          <p>{{ copy.analyticsText }}</p>
          <button
            v-if="analytics.choice.value === 'granted'"
            type="button"
            @click="analytics.withdraw"
          >
            {{ copy.withdraw }}
          </button>
          <button v-else type="button" @click="analytics.accept">{{ copy.enable }}</button>
        </section>
      </main>

      <PublicSiteFooter :locale="locale" :site="content.site" />
    </template>
  </AsyncState>
</template>

<style scoped>
.privacy-page {
  min-height: 100vh;
  padding: 6.5rem clamp(1.1rem, 4vw, 4.5rem) 5rem;
  color: #f2f1ed;
  background: #0b0d0f;
  font-family: Manrope, 'Noto Sans SC', 'Microsoft YaHei', sans-serif;
}

.privacy-hero {
  position: relative;
  display: grid;
  grid-template-columns: minmax(0, 1fr) auto;
  align-items: end;
  gap: 1rem 3rem;
  width: min(100%, 92rem);
  margin: 0 auto;
  padding: clamp(4rem, 10vw, 8rem) 0 clamp(3rem, 7vw, 5rem);
  border-bottom: 1px solid rgb(255 255 255 / 16%);
}

.privacy-eyebrow,
.section-index {
  margin: 0;
  color: #35d9e7;
  font-size: 0.65rem;
  font-weight: 600;
  letter-spacing: 0.14em;
  text-transform: uppercase;
}

.privacy-hero h1 {
  grid-column: 1;
  max-width: 12ch;
  margin: 0;
  font-size: clamp(3.5rem, 8vw, 8rem);
  font-weight: 600;
  line-height: 0.94;
  letter-spacing: -0.065em;
  text-wrap: balance;
}

.privacy-summary {
  grid-column: 1;
  max-width: 42rem;
  margin: 0.7rem 0 0;
  color: #a4a8aa;
  font-size: clamp(0.88rem, 1.4vw, 1.05rem);
  line-height: 1.8;
}

.privacy-hero > svg {
  grid-column: 2;
  grid-row: 1 / span 3;
  align-self: center;
  color: #35d9e7;
}

.privacy-layout {
  display: grid;
  grid-template-columns: minmax(15rem, 0.72fr) minmax(0, 1.28fr);
  gap: clamp(2.5rem, 7vw, 8rem);
  width: min(100%, 92rem);
  margin: 0 auto;
  padding: clamp(3rem, 7vw, 6rem) 0;
}

.privacy-principles {
  align-self: start;
}

.privacy-principles article {
  display: grid;
  grid-template-columns: auto minmax(0, 1fr);
  gap: 0.4rem 0.9rem;
  padding: 1.6rem 0;
  border-bottom: 1px solid rgb(255 255 255 / 12%);
}

.privacy-principles article:first-of-type {
  margin-top: 1.2rem;
  border-top: 1px solid rgb(255 255 255 / 12%);
}

.privacy-principles svg {
  grid-row: 1 / span 2;
  color: #35d9e7;
}

.privacy-principles h2,
.analytics-settings h2 {
  margin: 0;
  color: #f2f1ed;
  font-size: 0.95rem;
  font-weight: 600;
  letter-spacing: -0.02em;
}

.privacy-principles p:not(.section-index) {
  margin: 0;
  color: #8f9497;
  font-size: 0.75rem;
  line-height: 1.75;
}

.privacy-document {
  min-width: 0;
}

.privacy-prose {
  margin-top: 1.8rem;
  color: #c9ccca;
  font-size: clamp(0.9rem, 1.15vw, 1.02rem);
  line-height: 1.9;
}

.privacy-prose :deep(h2),
.privacy-prose :deep(h3) {
  margin: 2.8rem 0 0.8rem;
  color: #f2f1ed;
  font-weight: 600;
  line-height: 1.2;
  letter-spacing: -0.035em;
}

.privacy-prose :deep(h2) {
  padding-top: 1.5rem;
  border-top: 1px solid rgb(255 255 255 / 12%);
  font-size: clamp(1.35rem, 2.4vw, 2rem);
}

.privacy-prose :deep(h3) {
  font-size: 1.1rem;
}

.privacy-prose :deep(p),
.privacy-prose :deep(ul),
.privacy-prose :deep(ol) {
  margin: 0.8rem 0;
}

.privacy-prose :deep(a) {
  color: #35d9e7;
  text-underline-offset: 0.2em;
}

.analytics-settings {
  display: grid;
  grid-template-columns: minmax(15rem, 0.72fr) minmax(0, 1.28fr) auto;
  align-items: center;
  gap: 2rem clamp(2rem, 5vw, 5rem);
  width: min(100%, 92rem);
  margin: 0 auto;
  padding: clamp(2rem, 4vw, 3.2rem);
  border: 1px solid rgb(255 255 255 / 16%);
  background: #111417;
}

.settings-heading {
  display: grid;
  grid-template-columns: auto minmax(0, 1fr);
  align-items: center;
  gap: 1rem;
}

.settings-heading > svg {
  color: #35d9e7;
}

.settings-heading h2 {
  margin-top: 0.45rem;
}

.analytics-settings > p {
  margin: 0;
  color: #a4a8aa;
  font-size: 0.78rem;
  line-height: 1.75;
}

.analytics-settings button {
  min-height: 3rem;
  padding: 0.75rem 1.2rem;
  border: 1px solid #35d9e7;
  color: #071012;
  background: #35d9e7;
  font: inherit;
  font-size: 0.75rem;
  font-weight: 700;
  cursor: pointer;
  transition: color 180ms ease, background 180ms ease;
}

.analytics-settings button:hover,
.analytics-settings button:focus-visible {
  color: #35d9e7;
  background: transparent;
}

@media (max-width: 900px) {
  .privacy-layout,
  .analytics-settings {
    grid-template-columns: 1fr;
  }

  .privacy-principles {
    display: grid;
    grid-template-columns: repeat(2, minmax(0, 1fr));
    gap: 0 1.5rem;
  }

  .privacy-principles > .section-index {
    grid-column: 1 / -1;
  }

  .analytics-settings button {
    width: max-content;
  }
}

@media (max-width: 600px) {
  .privacy-page {
    padding: 4.8rem 1rem 3rem;
  }

  .privacy-hero {
    grid-template-columns: 1fr;
    padding: 3.5rem 0 2.5rem;
  }

  .privacy-hero > svg {
    display: none;
  }

  .privacy-hero h1 {
    font-size: clamp(3rem, 16vw, 5rem);
  }

  .privacy-principles {
    grid-template-columns: 1fr;
  }

  .analytics-settings {
    gap: 1.5rem;
    padding: 1.4rem;
  }

  .analytics-settings button {
    width: 100%;
  }
}

@media (prefers-reduced-motion: reduce) {
  .analytics-settings button {
    transition: none;
  }
}
</style>
