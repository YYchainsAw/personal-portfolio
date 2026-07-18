<script setup lang="ts">
import { computed } from 'vue'
import ResponsiveMedia from '@/components/media/ResponsiveMedia.vue'
import MediaAttribution from './MediaAttribution.vue'
import type { Locale, PublicBlock } from '@/types/public'

const props = defineProps<{ locale: Locale; blocks: PublicBlock[] }>()

function assertExhaustivePayload(value: never): never {
  throw new Error(`Unsupported published block payload: ${JSON.stringify(value)}`)
}

function isKnownPayload(block: PublicBlock): true {
  switch (block.payload.type) {
    case 'MARKDOWN':
    case 'IMAGE':
    case 'GALLERY':
    case 'VIDEO':
    case 'CODE':
    case 'QUOTE':
    case 'METRICS':
    case 'DOWNLOAD':
    case 'LINK': return true
    default: return assertExhaustivePayload(block.payload)
  }
}

const ordered = computed(() => props.blocks.filter((block) => {
  if (block.type !== block.payload.type) {
    if (import.meta.env.DEV) throw new Error(`Published block type mismatch: ${block.type} / ${block.payload.type}`)
    return false
  }
  return isKnownPayload(block)
}).sort((a, b) => a.sortOrder - b.sortOrder))

const codeLines = (code: string) => code.split('\n')

function safeHref(value: string): string | null {
  try {
    const url = new URL(value, window.location.origin)
    return url.protocol === 'https:' || url.origin === window.location.origin ? url.href : null
  } catch { return null }
}

function safeVideo(value: string): string | null {
  try {
    const url = new URL(value)
    if (url.protocol !== 'https:' || url.username || url.password || url.port || url.hash) return null
    if (url.hostname === 'www.youtube.com') {
      return /^\/embed\/[A-Za-z0-9_-]{3,64}$/u.test(url.pathname) && !url.search ? url.href : null
    }
    if (url.hostname === 'player.vimeo.com') {
      return /^\/video\/[0-9]{3,20}$/u.test(url.pathname) && !url.search ? url.href : null
    }
    if (url.hostname === 'player.bilibili.com' && url.pathname === '/player.html') {
      const values = url.searchParams.getAll('bvid')
      return values.length === 1
        && [...url.searchParams.keys()].length === 1
        && /^[A-Za-z0-9]{6,32}$/u.test(values[0] ?? '')
        ? url.href
        : null
    }
    return null
  } catch { return null }
}

function formatBytes(bytes: number): string {
  if (bytes < 1024) return `${bytes} B`
  const units = ['KiB', 'MiB', 'GiB']
  let value = bytes / 1024
  let unit = units[0]!
  for (let index = 1; index < units.length && value >= 1024; index += 1) {
    value /= 1024
    unit = units[index]!
  }
  return `${Number.isInteger(value) ? value.toFixed(0) : value.toFixed(1)} ${unit}`
}

function mimeLabel(mime: string): string {
  const exact: Record<string, string> = {
    'application/zip': 'ZIP', 'application/pdf': 'PDF', 'application/x-rar-compressed': 'RAR',
  }
  return exact[mime.toLowerCase()] || mime.split('/').pop()?.toUpperCase() || mime.toUpperCase()
}
</script>

<template>
  <section
    v-for="block in ordered"
    :key="block.id"
    class="content-block"
    :class="[
      `content-block--${block.width.toLowerCase()}`,
      `content-block--${block.alignment.toLowerCase()}`,
      `content-block--${block.emphasis.toLowerCase()}`,
    ]"
    data-content-block
    :data-order="block.sortOrder"
  >
    <div v-if="block.payload.type === 'MARKDOWN'" class="prose" v-html="block.payload.html"></div>

    <figure v-else-if="block.payload.type === 'IMAGE'">
      <ResponsiveMedia :media="block.payload.media" sizes="(max-width: 760px) 100vw, 72vw" />
      <figcaption v-if="block.payload.media.caption">{{ block.payload.media.caption }}</figcaption>
      <MediaAttribution :media="block.payload.media" />
    </figure>

    <div
      v-else-if="block.payload.type === 'GALLERY'"
      class="block-gallery"
      role="group"
      :aria-label="locale === 'zh-CN' ? '项目图片集' : 'Project image gallery'"
      :style="{ '--columns': block.columns }"
    >
      <figure v-for="(media, mediaIndex) in block.payload.media" :key="`${media.assetId}:${media.variant}:${mediaIndex}`">
        <ResponsiveMedia :media="media" sizes="(max-width: 760px) 100vw, 36vw" />
        <figcaption v-if="media.caption">{{ media.caption }}</figcaption>
        <MediaAttribution :media="media" />
      </figure>
    </div>

    <figure v-else-if="block.payload.type === 'VIDEO'" class="block-video">
      <ResponsiveMedia v-if="block.payload.cover" :media="block.payload.cover" sizes="(max-width: 760px) 100vw, 72vw" />
      <MediaAttribution v-if="block.payload.cover" :media="block.payload.cover" />
      <iframe
        v-if="safeVideo(block.payload.embedUrl)"
        :src="safeVideo(block.payload.embedUrl)!"
        :title="block.payload.title"
        loading="lazy"
        referrerpolicy="strict-origin-when-cross-origin"
        sandbox="allow-scripts allow-same-origin"
      ></iframe>
      <p v-else>{{ locale === 'zh-CN' ? '此视频暂时无法安全播放。' : 'This video cannot be played safely.' }}</p>
      <figcaption v-if="block.payload.description">{{ block.payload.description }}</figcaption>
    </figure>

    <figure v-else-if="block.payload.type === 'CODE'" class="block-code">
      <figcaption v-if="block.payload.title">{{ block.payload.title }} <small>{{ block.payload.language }}</small></figcaption>
      <pre :class="{ 'has-line-numbers': block.payload.showLineNumbers }"><code v-if="block.payload.showLineNumbers" class="code-lines"><span v-for="(line, lineIndex) in codeLines(block.payload.code)" :key="lineIndex" :data-line="lineIndex + 1">{{ line }}</span></code><code v-else>{{ block.payload.code }}</code></pre>
      <p v-if="block.payload.description">{{ block.payload.description }}</p>
    </figure>

    <blockquote v-else-if="block.payload.type === 'QUOTE'">
      <p>{{ block.payload.quote }}</p><cite>{{ block.payload.source }}</cite>
    </blockquote>

    <ul v-else-if="block.payload.type === 'METRICS'" class="block-metrics">
      <li v-for="metric in block.payload.metrics" :key="metric.id">
        <strong>{{ metric.value }}{{ metric.suffix }}</strong><span>{{ metric.label }}</span>
      </li>
    </ul>

    <a
      v-else-if="block.payload.type === 'DOWNLOAD' && safeHref(block.payload.href)"
      class="block-action"
      :href="safeHref(block.payload.href)!"
      data-analytics-type="DEMO_DOWNLOAD"
    >
      <strong>{{ block.payload.label }}</strong><span>{{ block.payload.description }}</span>
      <small
        v-if="block.payload.mimeType !== null && block.payload.byteSize !== null"
        data-download-metadata
        :title="`${block.payload.mimeType}; ${block.payload.byteSize} bytes`"
      >{{ mimeLabel(block.payload.mimeType) }} · {{ formatBytes(block.payload.byteSize) }}</small>
    </a>

    <a
      v-else-if="block.payload.type === 'LINK' && safeHref(block.payload.href)"
      class="block-action"
      :href="safeHref(block.payload.href)!"
      :target="block.payload.openNewTab ? '_blank' : undefined"
      :rel="block.payload.openNewTab ? 'noreferrer noopener' : undefined"
      data-analytics-type="OUTBOUND_CLICK"
    ><strong>{{ block.payload.label }}</strong><span>{{ block.payload.description }}</span></a>
  </section>
</template>

<style src="./project-blocks.css"></style>
