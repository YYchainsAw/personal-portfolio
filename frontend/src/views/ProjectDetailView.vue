<script setup lang="ts">
import { computed } from 'vue'
import { RouterLink } from 'vue-router'
import { PhArrowRight as ArrowRight, PhArrowUpRight as ArrowUpRight } from '@phosphor-icons/vue'
import sceneInteractionStudy from '@/assets/showcase/ue-scene-interaction-study.webp'
import PublicSiteFooter from '@/components/common/PublicSiteFooter.vue'
import PublicSiteHeader from '@/components/common/PublicSiteHeader.vue'
import ResponsiveMedia from '@/components/media/ResponsiveMedia.vue'
import ContentBlockRenderer from '@/components/project/ContentBlockRenderer.vue'
import MediaAttribution from '@/components/project/MediaAttribution.vue'
import ProjectHeader from '@/components/project/ProjectHeader.vue'
import type { Locale, ProjectCard, PublicMedia, PublicProject, PublicSite } from '@/types/public'

const props = defineProps<{
  locale: Locale
  site: PublicSite
  project: PublicProject
  catalog: ProjectCard[]
}>()

const detailCopy = {
  'zh-CN': {
    back: '返回作品',
    section: '完整案例',
    overview: '项目概览',
    overviewNote: '从目标、实现到复盘，记录一次可继续迭代的开发过程。',
    tags: '项目标签',
    tagsAria: '标签',
    skills: '技术能力',
    skillsAria: '技能',
    gallery: '开发画面',
    galleryNote: '项目过程中保留的界面、场景与迭代画面。',
    process: '开发记录',
    processNote: '以下内容保留了关键决策、实现方式与阶段结果。',
    emptyLabel: '记录整理中',
    emptyTitle: '本案例会随开发继续补充。',
    emptyText: '完成新的可玩版本后，这里将加入关键决策、蓝图或 C++ 实现与复盘。',
    next: '下一个案例',
    allWork: '查看全部作品',
    imageAlt: 'Unreal Engine 5 场景交互学习项目画面',
    imageCaption: 'UE5 实时场景与交互学习画面',
  },
  en: {
    back: 'Back to work',
    section: 'Case study',
    overview: 'Project overview',
    overviewNote:
      'A development record that moves from intent and implementation to reflection and iteration.',
    tags: 'Project tags',
    tagsAria: 'Tags',
    skills: 'Capabilities',
    skillsAria: 'Skills',
    gallery: 'Development frames',
    galleryNote: 'Interface, environment, and iteration captures retained throughout the project.',
    process: 'Development log',
    processNote: 'Key decisions, implementation details, and outcomes from the build.',
    emptyLabel: 'Notes in progress',
    emptyTitle: 'This case will grow with the build.',
    emptyText: 'The next playable iteration will add key decisions, Blueprint or C++ implementation notes, and a concise postmortem.',
    next: 'Next case',
    allWork: 'View all work',
    imageAlt: 'Unreal Engine 5 environment interaction study',
    imageCaption: 'UE5 real-time environment and interaction study',
  },
} as const

const copy = computed(() => detailCopy[props.locale])
const actualCover = computed(
  () =>
    props.catalog.find((card) => card.projectId === props.project.projectId)?.cover ||
    props.project.media[0] ||
    null,
)
const catalogIndex = computed(() =>
  props.catalog.findIndex((card) => card.projectId === props.project.projectId),
)
const isSceneInteractionCase = computed(() =>
  ['ue-environment-study', 'ue-study'].includes(props.project.slug),
)

const showcaseCover = computed<PublicMedia>(() => ({
  assetId: 'showcase-ue-scene-interaction-study',
  variant: 'optimized',
  src: sceneInteractionStudy,
  srcset: `${sceneInteractionStudy} 1672w`,
  alt: copy.value.imageAlt,
  caption: copy.value.imageCaption,
  credit: '',
  sourceUrl: '',
  width: 1672,
  height: 941,
}))

const presentationCover = computed(() =>
  isSceneInteractionCase.value ? showcaseCover.value : actualCover.value,
)
const additionalMedia = computed(() => {
  let representedByCover = false
  return props.project.media.filter((media) => {
    if (
      !representedByCover &&
      actualCover.value &&
      media.assetId === actualCover.value.assetId &&
      media.variant === actualCover.value.variant
    ) {
      representedByCover = true
      return false
    }
    return true
  })
})

const nextProject = computed(() => {
  if (props.catalog.length < 2 || catalogIndex.value < 0) return null
  return props.catalog[(catalogIndex.value + 1) % props.catalog.length] ?? null
})
</script>

<template>
  <PublicSiteHeader
    :locale="locale"
    :site="site"
    :back-to="{ name: 'home', params: { locale }, hash: '#work' }"
    :back-label="copy.back"
    :section-label="copy.section"
  />

  <main id="main-content" class="project-detail" tabindex="-1">
    <article>
      <ProjectHeader
        :locale="locale"
        :project="project"
        :cover="presentationCover"
        :badge="isSceneInteractionCase ? 'UE' : 'CASE'"
      />

      <section class="case-overview" aria-labelledby="case-overview-title">
        <div class="case-overview__intro">
          <p class="case-index">01 / {{ copy.overview }}</p>
          <h2 id="case-overview-title">{{ copy.overview }}</h2>
          <p>{{ copy.overviewNote }}</p>
        </div>

        <div v-if="project.tags.length || project.skills.length" class="case-taxonomy">
          <div v-if="project.tags.length">
            <h3>{{ copy.tags }}</h3>
            <ul :aria-label="copy.tagsAria">
              <li v-for="tag in project.tags" :key="tag">{{ tag }}</li>
            </ul>
          </div>
          <div v-if="project.skills.length">
            <h3>{{ copy.skills }}</h3>
            <ul :aria-label="copy.skillsAria">
              <li v-for="skill in project.skills" :key="skill">{{ skill }}</li>
            </ul>
          </div>
        </div>
      </section>

      <section
        v-if="additionalMedia.length"
        class="project-gallery"
        :aria-labelledby="`project-gallery-${project.projectId}`"
      >
        <div class="project-gallery__heading">
          <p class="case-index">02 / {{ copy.gallery }}</p>
          <h2 :id="`project-gallery-${project.projectId}`">{{ copy.gallery }}</h2>
          <p>{{ copy.galleryNote }}</p>
        </div>
        <div
          class="project-gallery__grid"
          :class="{ 'project-gallery__grid--single': additionalMedia.length === 1 }"
        >
          <figure
            v-for="(media, mediaIndex) in additionalMedia"
            :key="`${media.assetId}:${media.variant}`"
          >
            <span class="project-gallery__number" aria-hidden="true">{{
              String(mediaIndex + 1).padStart(2, '0')
            }}</span>
            <ResponsiveMedia :media="media" sizes="(max-width: 760px) 100vw, 48vw" />
            <figcaption v-if="media.caption">{{ media.caption }}</figcaption>
            <MediaAttribution :media="media" />
          </figure>
        </div>
      </section>

      <section
        class="project-narrative"
        :aria-labelledby="`project-narrative-${project.projectId}`"
      >
        <div class="project-narrative__heading">
          <p class="case-index">{{ additionalMedia.length ? '03' : '02' }} / {{ copy.process }}</p>
          <h2 :id="`project-narrative-${project.projectId}`">{{ copy.process }}</h2>
          <p>{{ copy.processNote }}</p>
        </div>
        <ContentBlockRenderer :locale="locale" :blocks="project.blocks" />
        <div v-if="project.blocks.length === 0" class="project-narrative__empty">
          <p>{{ copy.emptyLabel }}</p>
          <strong>{{ copy.emptyTitle }}</strong>
          <span>{{ copy.emptyText }}</span>
        </div>
      </section>

      <nav
        class="case-navigation"
        :class="{ 'case-navigation--single': !nextProject }"
        :aria-label="locale === 'zh-CN' ? '案例导航' : 'Case navigation'"
      >
        <RouterLink
          class="case-navigation__all"
          :to="{ name: 'home', params: { locale }, hash: '#work' }"
        >
          <span>{{ copy.allWork }}</span>
          <ArrowUpRight :size="19" weight="bold" aria-hidden="true" />
        </RouterLink>
        <RouterLink
          v-if="nextProject"
          class="case-navigation__next"
          :to="{ name: 'project', params: { locale, slug: nextProject.slug } }"
        >
          <span>{{ copy.next }} / {{ nextProject.number }}</span>
          <strong>{{ nextProject.title }}</strong>
          <ArrowRight class="case-navigation__arrow" :size="27" weight="bold" aria-hidden="true" />
        </RouterLink>
      </nav>
    </article>
  </main>

  <PublicSiteFooter :locale="locale" :site="site" />
</template>

<style scoped>
.project-detail {
  min-width: 0;
  color: var(--ink, #f3f6f9);
  background: var(--paper, #080d12);
}

.project-detail article {
  min-width: 0;
}

.case-overview,
.project-gallery,
.project-narrative,
.case-navigation {
  width: min(100%, 1440px);
  margin: 0 auto;
  padding-right: clamp(1.25rem, 5vw, 5.5rem);
  padding-left: clamp(1.25rem, 5vw, 5.5rem);
}

.case-overview {
  display: grid;
  grid-template-columns: minmax(18rem, 0.72fr) minmax(24rem, 1.28fr);
  gap: clamp(3rem, 9vw, 10rem);
  padding-top: clamp(5rem, 9vw, 9rem);
  padding-bottom: clamp(5rem, 9vw, 9rem);
  border-bottom: 1px solid var(--line, #26313c);
}

.case-index {
  margin: 0;
  color: var(--accent, #5ed8e7);
  font-size: 0.7rem;
  font-weight: 750;
  letter-spacing: 0.13em;
  text-transform: uppercase;
}

.case-overview__intro h2,
.project-gallery__heading h2,
.project-narrative__heading h2 {
  margin: 1rem 0 1.25rem;
  font-size: clamp(2.2rem, 4.2vw, 4.8rem);
  font-weight: 640;
  line-height: 0.98;
  letter-spacing: -0.06em;
  text-wrap: balance;
}

.case-overview__intro > p:last-child,
.project-gallery__heading > p:last-child,
.project-narrative__heading > p:last-child {
  max-width: 39rem;
  margin-bottom: 0;
  color: var(--muted, #a9b4c1);
  line-height: 1.8;
}

.case-taxonomy {
  align-self: end;
  border-top: 1px solid var(--line, #26313c);
}

.case-taxonomy > div {
  display: grid;
  grid-template-columns: minmax(7rem, 0.3fr) minmax(0, 1fr);
  gap: 1.5rem;
  padding: 1.2rem 0;
  border-bottom: 1px solid var(--line, #26313c);
}

.case-taxonomy h3 {
  margin: 0.23rem 0 0;
  color: #7f8b98;
  font-size: 0.7rem;
  font-weight: 650;
  letter-spacing: 0.08em;
  text-transform: uppercase;
}

.case-taxonomy ul {
  display: flex;
  flex-wrap: wrap;
  gap: 0.55rem 1.4rem;
  margin: 0;
  padding: 0;
  list-style: none;
}

.case-taxonomy li {
  color: #d9dee2;
  font-size: 0.88rem;
  line-height: 1.6;
}

.case-taxonomy li::before {
  margin-right: 0.48rem;
  color: var(--accent, #5ed8e7);
  content: '·';
}

.project-gallery {
  padding-top: clamp(5rem, 9vw, 9rem);
  padding-bottom: clamp(5rem, 9vw, 9rem);
  border-bottom: 1px solid var(--line, #26313c);
}

.project-gallery__heading,
.project-narrative__heading {
  display: grid;
  grid-template-columns: minmax(0, 0.75fr) minmax(18rem, 0.45fr);
  column-gap: clamp(3rem, 8vw, 8rem);
  align-items: end;
  margin-bottom: clamp(2.5rem, 5vw, 4.5rem);
}

.project-gallery__heading .case-index,
.project-narrative__heading .case-index {
  grid-column: 1 / -1;
}

.project-gallery__heading h2,
.project-narrative__heading h2 {
  margin-bottom: 0;
}

.project-gallery__grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: clamp(1rem, 2.2vw, 2rem);
}

.project-gallery__grid--single {
  grid-template-columns: minmax(0, 1fr);
}

.project-gallery figure {
  position: relative;
  min-width: 0;
  margin: 0;
  padding-top: 2.2rem;
  border-top: 1px solid var(--line, #26313c);
}

.project-gallery__number {
  position: absolute;
  top: 0.6rem;
  left: 0;
  color: var(--accent, #5ed8e7);
  font-size: 0.65rem;
  font-variant-numeric: tabular-nums;
}

.project-gallery :deep(img) {
  display: block;
  width: 100%;
  height: auto;
  aspect-ratio: 16 / 10;
  object-fit: cover;
}

.project-gallery figcaption,
.project-gallery :deep([data-media-credit]) {
  display: block;
  margin-top: 0.7rem;
  color: var(--muted, #a9b4c1);
  font-size: 0.74rem;
  line-height: 1.55;
}

.project-narrative {
  padding-top: clamp(5rem, 9vw, 9rem);
  padding-bottom: clamp(5rem, 9vw, 9rem);
}

.project-narrative__empty {
  display: grid;
  grid-template-columns: minmax(8rem, 0.35fr) minmax(12rem, 0.65fr) minmax(16rem, 1fr);
  gap: clamp(1rem, 4vw, 4rem);
  align-items: start;
  width: min(100%, 920px);
  margin: 0 auto;
  padding: clamp(1.5rem, 4vw, 2.8rem) 0;
  border-block: 1px solid var(--line, #26313c);
}

.project-narrative__empty p,
.project-narrative__empty strong,
.project-narrative__empty span {
  margin: 0;
}

.project-narrative__empty p {
  color: var(--accent, #5ed8e7);
  font-size: 0.68rem;
  font-weight: 700;
  letter-spacing: 0.1em;
  text-transform: uppercase;
}

.project-narrative__empty strong {
  color: #eef3f6;
  font-size: clamp(1rem, 1.8vw, 1.35rem);
  line-height: 1.5;
}

.project-narrative__empty span {
  color: var(--muted, #a9b4c1);
  font-size: 0.82rem;
  line-height: 1.8;
}

.case-navigation {
  display: grid;
  grid-template-columns: minmax(12rem, 0.35fr) minmax(0, 1fr);
  padding-bottom: clamp(5rem, 8vw, 8rem);
}

.case-navigation--single {
  grid-template-columns: 1fr;
}

.case-navigation--single .case-navigation__all {
  width: min(100%, 920px);
  margin: 0 auto;
}

.case-navigation a {
  color: inherit;
  text-decoration: none;
}

.case-navigation__all {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 1rem;
  min-height: 10rem;
  padding: 1.5rem;
  border: 1px solid var(--line, #26313c);
  color: #c3cbd1;
  font-size: 0.8rem;
}

.case-navigation__next {
  display: grid;
  grid-template-columns: minmax(0, 1fr) auto;
  align-content: center;
  min-height: 16rem;
  padding: clamp(2rem, 5vw, 4.5rem);
  border-top: 1px solid var(--line, #26313c);
  border-right: 1px solid var(--line, #26313c);
  border-bottom: 1px solid var(--line, #26313c);
  background: var(--surface-soft, #0f161d);
}

.case-navigation__next > span:first-child {
  grid-column: 1 / -1;
  margin-bottom: 1rem;
  color: var(--accent, #5ed8e7);
  font-size: 0.68rem;
  font-weight: 700;
  letter-spacing: 0.11em;
  text-transform: uppercase;
}

.case-navigation__next strong {
  max-width: 17ch;
  font-size: clamp(1.8rem, 3.8vw, 4.2rem);
  font-weight: 620;
  line-height: 1;
  letter-spacing: -0.055em;
}

.case-navigation__arrow {
  align-self: center;
  color: var(--accent, #5ed8e7);
  transition: transform 180ms ease;
}

.case-navigation a {
  transition:
    border-color 180ms ease,
    color 180ms ease,
    background-color 180ms ease;
}

.case-navigation a:hover,
.case-navigation a:focus-visible {
  border-color: var(--accent, #5ed8e7);
  color: #fff;
}

.case-navigation__next:hover .case-navigation__arrow,
.case-navigation__next:focus-visible .case-navigation__arrow {
  transform: translateX(0.4rem);
}

@media (max-width: 820px) {
  .case-overview {
    grid-template-columns: 1fr;
  }

  .project-gallery__heading,
  .project-narrative__heading {
    grid-template-columns: 1fr;
  }

  .project-gallery__heading > p:last-child,
  .project-narrative__heading > p:last-child {
    margin-top: 1.5rem;
  }

  .project-gallery__grid,
  .case-navigation {
    grid-template-columns: 1fr;
  }

  .project-narrative__empty {
    grid-template-columns: 1fr;
  }

  .case-navigation__next {
    border-top: 0;
    border-left: 1px solid var(--line, #26313c);
  }
}

@media (max-width: 560px) {
  .case-overview,
  .project-gallery,
  .project-narrative,
  .case-navigation {
    padding-right: 1.25rem;
    padding-left: 1.25rem;
  }

  .case-taxonomy > div {
    grid-template-columns: 1fr;
    gap: 0.8rem;
  }

  .case-navigation {
    padding-right: 0;
    padding-left: 0;
  }

  .case-navigation__all,
  .case-navigation__next {
    border-right: 0;
    border-left: 0;
  }
}
</style>
