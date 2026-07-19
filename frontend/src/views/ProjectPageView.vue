<script setup lang="ts">
import { computed, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import AsyncState from '@/components/common/AsyncState.vue'
import { useAsyncRouteData } from '@/composables/useAsyncRouteData'
import { applyProjectUnavailableSeo, applySeo, publicBaseUrl } from '@/services/seo'
import { currentProjectId, currentSite, publicContentStore } from '@/stores/publicContent'
import { trackAnalytics } from '@/composables/useAnalyticsConsent'
import { isLocale, type Locale } from '@/types/public'
import ProjectDetailView from './ProjectDetailView.vue'

const route = useRoute()
const router = useRouter()
const locale = computed<Locale>(() => isLocale(route.params.locale) ? route.params.locale : 'zh-CN')
const slug = computed(() => typeof route.params.slug === 'string' ? route.params.slug : '')
const key = computed(() => `${locale.value}:${slug.value}`)
const resource = useAsyncRouteData(key, (signal) => publicContentStore.loadProject(locale.value, slug.value, signal))

watch(key, () => {
  currentProjectId.value = null
  applyProjectUnavailableSeo(locale.value)
})

watch(() => resource.problem.value, (problem) => {
  if (problem) {
    currentProjectId.value = null
    applyProjectUnavailableSeo(locale.value)
  }
  if (problem?.body.status === 404 && problem.body.code === 'PROJECT_NOT_FOUND') {
    void router.replace({
      name: 'not-found', params: { pathMatch: route.path.split('/').filter(Boolean) },
      query: { requested: route.fullPath },
    })
  }
})

watch(() => resource.value.value, (content) => {
  if (!content) return
  currentSite.value = content.site
  currentProjectId.value = content.project.projectId
  const cover = content.catalog.find((card) => card.projectId === content.project.projectId)?.cover || content.project.media[0] || null
  applySeo({ kind: 'project', locale: locale.value, site: content.site, project: content.project, cover }, publicBaseUrl())
  const event = { pageKey: 'PROJECT_DETAIL', projectId: content.project.projectId, locale: locale.value, referrer: document.referrer || null } as const
  trackAnalytics({ ...event, type: 'PAGE_VIEW' })
  trackAnalytics({ ...event, type: 'PROJECT_VIEW' })
}, { immediate: true })
</script>

<template>
  <AsyncState :locale="locale" :state="resource.state.value" :trace-id="resource.problem.value?.body.traceId" @retry="resource.retry">
    <ProjectDetailView v-if="resource.value.value" :locale="locale" :project="resource.value.value.project" :catalog="resource.value.value.catalog" />
  </AsyncState>
</template>
