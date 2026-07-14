<script setup lang="ts">
import { computed } from 'vue'
import { RouterView } from 'vue-router'
import { useLocale } from '@/composables/useLocale'
import { getCopy } from '@/data/portfolio'

const { locale } = useLocale()
const skipText = computed(() => getCopy(locale.value).a11y.skip)
</script>

<template>
  <a class="skip-link" href="#main-content">{{ skipText }}</a>
  <RouterView />
</template>
