<script setup lang="ts">
import { computed, watch } from 'vue'
import { useRoute } from 'vue-router'
import AsyncState from '@/components/common/AsyncState.vue'
import { useAsyncRouteData } from '@/composables/useAsyncRouteData'
import { mapHomeViewModel } from '@/mappers/homeMapper'
import { applySeo, publicBaseUrl } from '@/services/seo'
import { currentProjectId, currentSite, publicContentStore } from '@/stores/publicContent'
import { isLocale, type Locale } from '@/types/public'
import HomeView from './HomeView.vue'
import { trackAnalytics } from '@/composables/useAnalyticsConsent'

const route = useRoute()
const locale = computed<Locale>(() => isLocale(route.params.locale) ? route.params.locale : 'zh-CN')
const resource = useAsyncRouteData(locale, (signal) => publicContentStore.loadHome(locale.value, signal))
const model = computed(() => resource.value.value
  ? mapHomeViewModel(locale.value, resource.value.value.site, resource.value.value.catalog)
  : null)

watch(() => resource.value.value, (content) => {
  if (!content) return
  currentSite.value = content.site
  currentProjectId.value = null
  applySeo({ kind: 'home', locale: locale.value, site: content.site }, publicBaseUrl())
  trackAnalytics({ type: 'PAGE_VIEW', pageKey: 'HOME', projectId: null, locale: locale.value, referrer: document.referrer || null })
}, { immediate: true })

</script>

<template>
  <AsyncState :locale="locale" :state="resource.state.value" :trace-id="resource.problem.value?.body.traceId" @retry="resource.retry">
    <HomeView v-if="model" :model="model" />
  </AsyncState>
</template>
