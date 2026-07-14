<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref } from 'vue'

defineProps<{
  image: string
  imageAlt: string
  revealImage: string
  revealAlt: string
  eager?: boolean
}>()

const root = ref<HTMLElement | null>(null)
const isActive = ref(false)
let reducedMotion: MediaQueryList | null = null
let animationFrame = 0

const setSpotlight = (event: PointerEvent) => {
  if (!root.value || reducedMotion?.matches || event.pointerType === 'touch') return

  const { left, top } = root.value.getBoundingClientRect()
  const x = event.clientX - left
  const y = event.clientY - top

  cancelAnimationFrame(animationFrame)
  animationFrame = requestAnimationFrame(() => {
    root.value?.style.setProperty('--spot-x', `${x}px`)
    root.value?.style.setProperty('--spot-y', `${y}px`)
    isActive.value = true
  })
}

const hideSpotlight = () => {
  isActive.value = false
}

onMounted(() => {
  reducedMotion = window.matchMedia('(prefers-reduced-motion: reduce)')
})

onBeforeUnmount(() => {
  cancelAnimationFrame(animationFrame)
})
</script>

<template>
  <div
    ref="root"
    class="spotlight-media"
    :class="{ 'is-active': isActive }"
    @pointermove="setSpotlight"
    @pointerleave="hideSpotlight"
  >
    <img
      class="spotlight-media__base"
      :src="image"
      :alt="imageAlt"
      :loading="eager ? 'eager' : 'lazy'"
      :fetchpriority="eager ? 'high' : 'auto'"
    />
    <img
      class="spotlight-media__reveal"
      :src="revealImage"
      :alt="revealAlt"
      aria-hidden="true"
      loading="lazy"
    />
    <p class="spotlight-media__hint" aria-hidden="true">Move to reveal</p>
  </div>
</template>

<style scoped>
.spotlight-media {
  --spot-x: 50%;
  --spot-y: 50%;
  position: relative;
  width: 100%;
  height: 100%;
  overflow: hidden;
  isolation: isolate;
}

.spotlight-media img {
  position: absolute;
  inset: 0;
  width: 100%;
  height: 100%;
  object-fit: cover;
}

.spotlight-media__base {
  transform: scale(1.015);
  transition: transform 1.4s var(--ease-out);
}

.spotlight-media__reveal {
  opacity: 0;
  transform: scale(1.035);
  mask-image: radial-gradient(
    circle 230px at var(--spot-x) var(--spot-y),
    #000 0,
    #000 62%,
    transparent 100%
  );
  transition: opacity 320ms ease;
}

.spotlight-media.is-active .spotlight-media__base {
  transform: scale(1);
}

.spotlight-media.is-active .spotlight-media__reveal {
  opacity: 1;
}

.spotlight-media__hint {
  position: absolute;
  right: 1.2rem;
  bottom: 1.2rem;
  z-index: 2;
  margin: 0;
  padding: 0.55rem 0.8rem;
  border: 1px solid rgb(255 255 255 / 36%);
  border-radius: 999px;
  color: #fff;
  background: rgb(7 7 9 / 56%);
  font-size: 0.67rem;
  font-weight: 600;
  letter-spacing: 0.12em;
  line-height: 1;
  text-transform: uppercase;
  backdrop-filter: blur(12px);
  transition: opacity 240ms ease;
}

.spotlight-media.is-active .spotlight-media__hint {
  opacity: 0;
}

@media (pointer: coarse) {
  .spotlight-media__hint,
  .spotlight-media__reveal {
    display: none;
  }
}

@media (prefers-reduced-motion: reduce) {
  .spotlight-media__hint,
  .spotlight-media__reveal {
    display: none;
  }

  .spotlight-media__base {
    transform: none;
    transition: none;
  }
}
</style>
