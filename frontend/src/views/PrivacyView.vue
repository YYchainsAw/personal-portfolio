<script setup lang="ts">
import { computed, watch } from 'vue'
import { useRoute } from 'vue-router'
import AsyncState from '@/components/common/AsyncState.vue'
import LanguageSwitch from '@/components/common/LanguageSwitch.vue'
import { useAsyncRouteData } from '@/composables/useAsyncRouteData'
import { applySeo, publicBaseUrl } from '@/services/seo'
import { currentProjectId, currentSite, publicContentStore } from '@/stores/publicContent'
import { trackAnalytics } from '@/composables/useAnalyticsConsent'
import { useAnalyticsConsent } from '@/composables/useAnalyticsConsent'
import { isLocale, type Locale } from '@/types/public'

const route = useRoute()
const locale = computed<Locale>(() => isLocale(route.params.locale) ? route.params.locale : 'zh-CN')
const resource = useAsyncRouteData(locale, (signal) => publicContentStore.loadPrivacy(locale.value, signal))
const analytics = useAnalyticsConsent()

watch(() => resource.value.value, (content) => {
  if (!content) return
  currentSite.value = content.site
  currentProjectId.value = null
  applySeo({ kind: 'privacy', locale: locale.value, site: content.site }, publicBaseUrl())
  trackAnalytics({ type: 'PAGE_VIEW', pageKey: 'PRIVACY', projectId: null, locale: locale.value, referrer: document.referrer || null })
}, { immediate: true })
</script>

<template>
  <AsyncState :locale="locale" :state="resource.state.value" :trace-id="resource.problem.value?.body.traceId" @retry="resource.retry">
    <header class="privacy-nav"><RouterLink :to="{ name: 'home', params: { locale } }">{{ locale === 'zh-CN' ? '← 首页' : '← Home' }}</RouterLink><LanguageSwitch /></header>
    <main v-if="resource.value.value" id="main-content" class="privacy-page" tabindex="-1">
      <h1>{{ resource.value.value.site.privacy.title }}</h1>
      <div class="prose" v-html="resource.value.value.site.privacy.html"></div>
      <section v-if="!analytics.suppressedByDnt.value" class="analytics-settings" aria-labelledby="analytics-settings-title">
        <h2 id="analytics-settings-title">{{ locale === 'zh-CN' ? '匿名统计设置' : 'Analytics settings' }}</h2>
        <p>{{ locale === 'zh-CN' ? '统计默认关闭。获得同意后，原始事件最多保留 30 天，浏览器标识也会定期轮换。撤回会停止未来收集并清除本浏览器中的标识，但无法从去标识化汇总中识别个人。它不是跨设备人数计数器。' : 'Analytics is off by default. After consent, raw events are retained for at most 30 days and browser identifiers rotate regularly. Withdrawal stops future collection and clears this browser’s identifiers, but cannot identify a person in de-identified aggregates. It is not a cross-device people counter.' }}</p>
        <button v-if="analytics.choice.value === 'granted'" type="button" @click="analytics.withdraw">{{ locale === 'zh-CN' ? '撤回同意' : 'Withdraw consent' }}</button>
        <button v-else type="button" @click="analytics.accept">{{ locale === 'zh-CN' ? '启用匿名统计' : 'Enable analytics' }}</button>
      </section>
    </main>
  </AsyncState>
</template>

<style scoped>
.privacy-nav { position: fixed; top: 1rem; left: 50%; z-index: 10; display: flex; justify-content: space-between; width: min(calc(100% - 2rem), 900px); padding: .6rem .8rem; border: 1px solid var(--line); border-radius: 1rem; background: #fff; transform: translateX(-50%); }
.privacy-page { width: min(100% - 2rem, 780px); min-height: 70vh; margin: 0 auto; padding: 10rem 0 5rem; }
h1 { font: 400 clamp(3rem, 8vw, 6rem)/1 'Instrument Serif', serif; }
.prose { line-height: 1.8; }
</style>
