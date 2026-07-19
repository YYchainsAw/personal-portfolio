<script setup lang="ts">
import type { PublicMedia } from '@/types/public'

withDefaults(defineProps<{
  media: PublicMedia
  sizes?: string
  objectPosition?: string
  eager?: boolean
  decorative?: boolean
}>(), {
  sizes: '100vw', objectPosition: '50% 50%', eager: false, decorative: false,
})
</script>

<template>
  <img
    :src="media.src"
    :srcset="media.srcset"
    :sizes="sizes"
    :width="media.width"
    :height="media.height"
    :alt="decorative ? '' : media.alt"
    :loading="eager ? 'eager' : 'lazy'"
    :fetchpriority="eager ? 'high' : 'auto'"
    :style="{ objectPosition }"
  />
</template>
