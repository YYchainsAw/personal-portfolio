<script setup lang="ts">
import ResponsiveMedia from '@/components/media/ResponsiveMedia.vue'
import MediaAttribution from './MediaAttribution.vue'
import type { Locale, PublicMedia, PublicProject } from '@/types/public'

defineProps<{
  locale: Locale
  project: PublicProject
  cover: PublicMedia | null
  badge?: string
}>()
</script>

<template>
  <header class="project-hero">
    <div class="project-hero__copy">
      <div class="project-hero__kicker">
        <span>{{ project.eyebrow }}</span>
        <span aria-hidden="true">/</span>
        <span>{{ project.number }}</span>
      </div>

      <h1>{{ project.title }}</h1>
      <p class="project-hero__summary">{{ project.summary }}</p>

      <dl class="project-hero__facts">
        <div>
          <dt>{{ locale === 'zh-CN' ? '类型' : 'Discipline' }}</dt>
          <dd>{{ project.eyebrow }}</dd>
        </div>
        <div>
          <dt>{{ locale === 'zh-CN' ? '状态' : 'Status' }}</dt>
          <dd>
            <span class="project-hero__status-dot" aria-hidden="true"></span>{{ project.status }}
          </dd>
        </div>
      </dl>
    </div>

    <figure v-if="cover" class="project-hero__visual">
      <ResponsiveMedia :media="cover" sizes="(max-width: 820px) 100vw, 62vw" eager />
      <div class="project-hero__frame" aria-hidden="true">
        <span>{{ badge || 'CASE' }}</span>
        <strong>{{ project.number }}</strong>
      </div>
      <figcaption v-if="cover.caption">{{ cover.caption }}</figcaption>
      <MediaAttribution :media="cover" />
    </figure>
  </header>
</template>

<style scoped>
.project-hero {
  display: grid;
  grid-template-columns: minmax(20rem, 0.72fr) minmax(0, 1.28fr);
  min-height: min(51rem, calc(100svh - 5rem));
  border-bottom: 1px solid var(--line, #26313c);
  color: var(--ink, #f3f6f9);
  background: #0a0f14;
}

.project-hero__copy {
  position: relative;
  z-index: 2;
  display: flex;
  flex-direction: column;
  justify-content: center;
  min-width: 0;
  padding: clamp(4.5rem, 8vw, 8.5rem) clamp(1.5rem, 5vw, 5.5rem);
  background: #090e13;
}

.project-hero__kicker {
  display: flex;
  flex-wrap: wrap;
  gap: 0.7rem;
  margin-bottom: 1.4rem;
  color: var(--accent, #5ed8e7);
  font-size: 0.69rem;
  font-weight: 720;
  letter-spacing: 0.14em;
  text-transform: uppercase;
}

.project-hero h1 {
  max-width: 9.5ch;
  margin: 0;
  font-size: clamp(3rem, 5.2vw, 6rem);
  font-weight: 650;
  line-height: 0.98;
  letter-spacing: -0.065em;
  text-wrap: balance;
}

.project-hero__summary {
  max-width: 37rem;
  margin: clamp(1.5rem, 3vw, 2.3rem) 0 0;
  color: #b7c0c8;
  font-size: clamp(0.96rem, 1.25vw, 1.1rem);
  line-height: 1.85;
}

.project-hero__facts {
  display: grid;
  gap: 0;
  width: min(100%, 34rem);
  margin: clamp(2.5rem, 5vw, 4.5rem) 0 0;
  border-top: 1px solid var(--line, #26313c);
}

.project-hero__facts div {
  display: grid;
  grid-template-columns: 7rem minmax(0, 1fr);
  gap: 1rem;
  padding: 0.95rem 0;
  border-bottom: 1px solid var(--line, #26313c);
}

.project-hero__facts dt,
.project-hero__facts dd {
  margin: 0;
  font-size: 0.73rem;
}

.project-hero__facts dt {
  color: #778490;
  letter-spacing: 0.08em;
  text-transform: uppercase;
}

.project-hero__facts dd {
  display: inline-flex;
  align-items: center;
  gap: 0.55rem;
  color: #d8dde1;
  font-weight: 650;
}

.project-hero__status-dot {
  width: 0.42rem;
  height: 0.42rem;
  border-radius: 50%;
  background: var(--accent, #5ed8e7);
  box-shadow: 0 0 0 0.2rem rgb(94 216 231 / 11%);
}

.project-hero__visual {
  position: relative;
  min-width: 0;
  min-height: 38rem;
  margin: 0;
  overflow: hidden;
  background: #141c22;
}

.project-hero__visual::after {
  position: absolute;
  inset: 0;
  background: rgb(5 9 12 / 8%);
  content: '';
  pointer-events: none;
}

.project-hero__visual > :deep(img) {
  width: 100%;
  height: 100%;
  object-fit: cover;
}

.project-hero__frame {
  position: absolute;
  z-index: 2;
  top: clamp(1.25rem, 3vw, 2.75rem);
  right: clamp(1.25rem, 3vw, 2.75rem);
  display: flex;
  align-items: baseline;
  gap: 0.75rem;
  padding: 0.65rem 0.8rem;
  border: 1px solid rgb(255 255 255 / 20%);
  color: #fff;
  background: rgb(8 12 15 / 68%);
  backdrop-filter: blur(12px);
}

.project-hero__frame span {
  color: var(--accent, #5ed8e7);
  font-size: 0.7rem;
  font-weight: 800;
  letter-spacing: 0.12em;
}

.project-hero__frame strong {
  font-size: 0.72rem;
  font-weight: 600;
}

.project-hero__visual figcaption,
.project-hero__visual :deep([data-media-credit]) {
  position: absolute;
  z-index: 2;
  bottom: 1.2rem;
  color: #d5dcdf;
  font-size: 0.72rem;
}

.project-hero__visual figcaption {
  left: 1.3rem;
}

.project-hero__visual :deep([data-media-credit]) {
  right: 1.3rem;
}

@media (max-width: 900px) {
  .project-hero {
    grid-template-columns: 1fr;
    min-height: 0;
  }

  .project-hero__copy {
    min-height: 34rem;
    padding-top: 6rem;
    background: #090e13;
  }

  .project-hero__visual {
    min-height: 0;
    aspect-ratio: 16 / 10;
  }
}

@media (max-width: 560px) {
  .project-hero__copy {
    min-height: 31rem;
    padding-right: 1.25rem;
    padding-left: 1.25rem;
  }

  .project-hero h1 {
    font-size: clamp(2.75rem, 14vw, 4rem);
  }

  .project-hero__visual {
    aspect-ratio: 4 / 3;
  }

  .project-hero__visual figcaption {
    right: 1rem;
    bottom: 0.9rem;
    left: 1rem;
  }

  .project-hero__visual :deep([data-media-credit]) {
    display: none;
  }
}

@media (prefers-reduced-motion: no-preference) {
  .project-hero__visual > :deep(img) {
    animation: project-hero-settle 900ms cubic-bezier(0.2, 0.7, 0.2, 1) both;
  }

  @keyframes project-hero-settle {
    from {
      opacity: 0;
      transform: scale(1.035);
    }
    to {
      opacity: 1;
      transform: scale(1);
    }
  }
}
</style>
