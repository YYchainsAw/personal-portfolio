<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted } from 'vue'
import { RouterView, useRoute } from 'vue-router'
import AnalyticsConsent from '@/components/analytics/AnalyticsConsent.vue'
import { trackAnalytics } from '@/composables/useAnalyticsConsent'
import { useLocale } from '@/composables/useLocale'
import { uiCopy } from '@/data/uiCopy'
import { currentProjectId, currentSite } from '@/stores/publicContent'
import { delegatedAnalyticsInput } from '@/services/analyticsInstrumentation'

const { locale } = useLocale()
const route = useRoute()
const skipText = computed(() => currentSite.value?.accessibility.skip || uiCopy[locale.value].skip)

function clickAnalytics(event: MouseEvent) {
  const input = delegatedAnalyticsInput(
    event,
    route.name,
    locale.value,
    currentProjectId.value,
    document.referrer || null,
  )
  if (input) trackAnalytics(input)
}

function focusMain(event: MouseEvent) {
  event.preventDefault()
  const main = document.querySelector<HTMLElement>('#main-content')
  if (!main) return
  main.focus({ preventScroll: true })
  main.scrollIntoView({ behavior: 'auto', block: 'start' })
  if (window.location.hash !== '#main-content') window.history.replaceState(window.history.state, '', '#main-content')
}

onMounted(() => document.addEventListener('click', clickAnalytics))
onBeforeUnmount(() => document.removeEventListener('click', clickAnalytics))
</script>

<template>
  <a class="skip-link" href="#main-content" @click="focusMain">{{ skipText }}</a>
  <RouterView />
  <AnalyticsConsent :locale="locale" />
</template>
