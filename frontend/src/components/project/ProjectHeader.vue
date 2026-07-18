<script setup lang="ts">
import ResponsiveMedia from '@/components/media/ResponsiveMedia.vue'
import MediaAttribution from './MediaAttribution.vue'
import type { PublicMedia, PublicProject } from '@/types/public'

defineProps<{ project: PublicProject; cover: PublicMedia | null }>()
</script>

<template>
  <header class="project-header">
    <p>{{ project.eyebrow }} · {{ project.number }} · {{ project.status }}</p>
    <h1>{{ project.title }}</h1>
    <p>{{ project.summary }}</p>
    <figure v-if="cover">
      <ResponsiveMedia :media="cover" sizes="(max-width: 760px) 100vw, 86vw" eager />
      <figcaption v-if="cover.caption">{{ cover.caption }}</figcaption>
      <MediaAttribution :media="cover" />
    </figure>
  </header>
</template>

<style scoped>
.project-header { width: min(100% - 2rem, 1100px); margin: 0 auto; padding: 10rem 0 3rem; }
h1 { max-width: 14ch; margin: .35rem 0 1rem; font: 400 clamp(3.2rem, 9vw, 7.5rem)/.9 'Instrument Serif', serif; }
p { max-width: 68ch; color: var(--muted); }
figure { margin: 2rem 0 0; }
img { width: 100%; height: auto; border-radius: 1.4rem; }
figcaption { margin-top: .5rem; color: var(--muted); }
</style>
