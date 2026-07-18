<script setup lang="ts">
import { computed } from 'vue'
import { RouterLink } from 'vue-router'
import ContentBlockRenderer from '@/components/project/ContentBlockRenderer.vue'
import ProjectHeader from '@/components/project/ProjectHeader.vue'
import LanguageSwitch from '@/components/common/LanguageSwitch.vue'
import ResponsiveMedia from '@/components/media/ResponsiveMedia.vue'
import MediaAttribution from '@/components/project/MediaAttribution.vue'
import { uiCopy } from '@/data/uiCopy'
import type { Locale, ProjectCard, PublicProject } from '@/types/public'

const props = defineProps<{ locale: Locale; project: PublicProject; catalog: ProjectCard[] }>()
const cover = computed(() => props.catalog.find((card) => card.projectId === props.project.projectId)?.cover || props.project.media[0] || null)
const additionalMedia = computed(() => {
  let representedByCover = false
  return props.project.media.filter((media) => {
    if (!representedByCover && cover.value && media.assetId === cover.value.assetId && media.variant === cover.value.variant) {
      representedByCover = true
      return false
    }
    return true
  })
})
</script>

<template>
  <header class="detail-nav">
    <RouterLink :to="{ name: 'home', params: { locale }, hash: '#work' }">← {{ uiCopy[locale].backWork }}</RouterLink>
    <LanguageSwitch />
  </header>
  <main id="main-content" tabindex="-1">
    <ProjectHeader :project="project" :cover="cover" />
    <div class="project-taxonomy">
      <ul :aria-label="locale === 'zh-CN' ? '标签' : 'Tags'"><li v-for="tag in project.tags" :key="tag">{{ tag }}</li></ul>
      <ul :aria-label="locale === 'zh-CN' ? '技能' : 'Skills'"><li v-for="skill in project.skills" :key="skill">{{ skill }}</li></ul>
    </div>
    <section v-if="additionalMedia.length" class="project-media" :aria-label="locale === 'zh-CN' ? '项目媒体' : 'Project media'">
      <figure v-for="media in additionalMedia" :key="`${media.assetId}:${media.variant}`">
        <ResponsiveMedia :media="media" sizes="(max-width: 760px) 100vw, 48vw" />
        <figcaption v-if="media.caption">{{ media.caption }}</figcaption>
        <MediaAttribution :media="media" />
      </figure>
    </section>
    <ContentBlockRenderer :locale="locale" :blocks="project.blocks" />
  </main>
</template>

<style scoped>
.detail-nav { position: fixed; z-index: 20; top: 1rem; left: 50%; display: flex; align-items: center; justify-content: space-between; width: min(calc(100% - 2rem), 1100px); padding: .6rem .8rem; border: 1px solid var(--line); border-radius: 1rem; background: rgb(255 255 255 / 92%); transform: translateX(-50%); }
.detail-nav > a { color: inherit; }
.project-taxonomy { width: min(100% - 2rem, 900px); margin: 0 auto; }
ul { display: flex; flex-wrap: wrap; gap: .5rem; padding: 0; list-style: none; }
li { padding: .4rem .7rem; border: 1px solid var(--line); border-radius: 999px; }
.project-media { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 1rem; width: min(100% - 2rem, 1100px); margin: 2rem auto; }
.project-media figure { margin: 0; }
.project-media img { width: 100%; height: auto; border-radius: 1rem; }
.project-media figcaption { margin-top: .5rem; color: var(--muted); }
@media (max-width: 680px) { .project-media { grid-template-columns: 1fr; } }
</style>
