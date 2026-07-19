<script setup lang="ts">
import { computed, watch } from 'vue'
import { RouterLink, useRoute } from 'vue-router'
import LanguageSwitch from '@/components/common/LanguageSwitch.vue'
import { uiCopy } from '@/data/uiCopy'
import { applyNotFoundSeo } from '@/services/seo'
import { isLocale, type Locale } from '@/types/public'
import { currentProjectId, currentSite } from '@/stores/publicContent'

const route = useRoute()
const locale = computed<Locale>(() => {
  const first = route.path.split('/').filter(Boolean)[0]
  return isLocale(first) ? first : 'zh-CN'
})
currentSite.value = null
currentProjectId.value = null
watch(locale, (value) => applyNotFoundSeo(value), { immediate: true })
</script>

<template>
  <main id="main-content" class="not-found" tabindex="-1">
    <LanguageSwitch />
    <p>404</p>
    <h1>{{ uiCopy[locale].notFoundTitle }}</h1>
    <p>{{ uiCopy[locale].notFoundText }}</p>
    <RouterLink :to="{ name: 'home', params: { locale } }">{{ uiCopy[locale].backHome }}</RouterLink>
  </main>
</template>

<style scoped>
.not-found { min-height: 100vh; display: grid; align-content: center; justify-items: start; width: min(100% - 2rem, 900px); margin: 0 auto; }
h1 { margin: .2rem 0; font: 400 clamp(4rem, 12vw, 9rem)/.9 'Instrument Serif', serif; }
a { margin-top: 1rem; color: var(--accent); }
</style>
