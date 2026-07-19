<script setup lang="ts">
import { computed } from 'vue'
import type { PublicMedia } from '@/types/public'

const props = defineProps<{ media: PublicMedia }>()
const safeSource = computed(() => {
  if (!props.media.credit.trim() || !props.media.sourceUrl.trim()) return null
  try {
    const url = new URL(props.media.sourceUrl)
    return url.protocol === 'https:' ? url.href : null
  } catch { return null }
})
</script>

<template>
  <a v-if="safeSource" data-media-credit data-media-source data-analytics-type="OUTBOUND_CLICK" data-analytics-page-key="PROJECT_DETAIL" :href="safeSource" target="_blank" rel="noopener noreferrer">{{ media.credit }}</a>
  <span v-else-if="media.credit.trim()" data-media-credit>{{ media.credit }}</span>
</template>
